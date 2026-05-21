data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

locals {
  common_tags = merge(var.tags, {
    Environment = var.environment
    ManagedBy   = "terraform"
  })
  oidc_host = replace(aws_eks_cluster.this.identity[0].oidc[0].issuer, "https://", "")
}

# ── Control plane logs ────────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "eks_control_plane" {
  name              = "/aws/eks/${var.cluster_name}/cluster"
  retention_in_days = var.cloudwatch_log_retention_days
  tags              = merge(local.common_tags, { Name = "${var.cluster_name}-control-plane-logs" })
}

# ── Cluster IAM role ──────────────────────────────────────────────────────────

resource "aws_iam_role" "cluster" {
  name = "${var.cluster_name}-cluster-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "eks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "cluster_AmazonEKSClusterPolicy" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_iam_role_policy_attachment" "cluster_AmazonEKSVPCResourceController" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSVPCResourceController"
}

# ── Additional cluster security group ────────────────────────────────────────

resource "aws_security_group" "cluster_additional" {
  name        = "${var.cluster_name}-sg"
  description = "Additional security group for EKS cluster"
  vpc_id      = var.vpc_id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
    description = "Allow all outbound"
  }

  tags = merge(local.common_tags, { Name = "${var.cluster_name}-sg" })
}

# ── EKS cluster ───────────────────────────────────────────────────────────────

resource "aws_eks_cluster" "this" {
  name     = var.cluster_name
  role_arn = aws_iam_role.cluster.arn
  version  = var.kubernetes_version

  vpc_config {
    subnet_ids              = var.private_subnet_ids
    endpoint_private_access = true
    endpoint_public_access  = true
    public_access_cidrs     = var.cluster_endpoint_public_access_cidrs
    security_group_ids      = [aws_security_group.cluster_additional.id]
  }

  enabled_cluster_log_types = [
    "api",
    "audit",
    "authenticator",
    "controllerManager",
    "scheduler",
  ]

  # API_AND_CONFIG_MAP keeps aws-auth ConfigMap working (needed by ArgoCD / service accounts)
  # while also enabling the newer EKS Access Entry API. Do not change to CONFIG_MAP_ONLY.
  access_config {
    authentication_mode = "API_AND_CONFIG_MAP"
  }

  depends_on = [
    aws_iam_role_policy_attachment.cluster_AmazonEKSClusterPolicy,
    aws_iam_role_policy_attachment.cluster_AmazonEKSVPCResourceController,
    aws_cloudwatch_log_group.eks_control_plane,
  ]

  tags = merge(local.common_tags, { Name = var.cluster_name })
}

# ── OIDC provider (required for IRSA) ────────────────────────────────────────

data "tls_certificate" "eks" {
  url = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "this" {
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.eks.certificates[0].sha1_fingerprint]
  url             = aws_eks_cluster.this.identity[0].oidc[0].issuer

  tags = merge(local.common_tags, { Name = "${var.cluster_name}-oidc" })
}

# ── IRSA: VPC CNI ─────────────────────────────────────────────────────────────
# Used immediately by the vpc-cni addon in addons.tf

resource "aws_iam_role" "vpc_cni_irsa" {
  name = "${var.cluster_name}-vpc-cni-irsa"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Federated = aws_iam_openid_connect_provider.this.arn
      }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_host}:sub" = "system:serviceaccount:kube-system:aws-node"
          "${local.oidc_host}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "vpc_cni_irsa_AmazonEKS_CNI_Policy" {
  role       = aws_iam_role.vpc_cni_irsa.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
}

# ── IRSA: AWS Load Balancer Controller ────────────────────────────────────────
# Helm install deferred to Step 0.11 (ArgoCD bootstrap). Role created now so
# the ARN is available as an output for Step 0.11's Helm values.

resource "aws_iam_policy" "lbc" {
  name   = "${var.cluster_name}-lbc-policy"
  policy = file("${path.module}/iam-policies/lbc-policy.json")
  tags   = local.common_tags
}

resource "aws_iam_role" "lbc_irsa" {
  name = "${var.cluster_name}-lbc-irsa"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Federated = aws_iam_openid_connect_provider.this.arn
      }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_host}:sub" = "system:serviceaccount:kube-system:aws-load-balancer-controller"
          "${local.oidc_host}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "lbc_irsa" {
  role       = aws_iam_role.lbc_irsa.name
  policy_arn = aws_iam_policy.lbc.arn
}

# ── IRSA: External DNS ────────────────────────────────────────────────────────

resource "aws_iam_policy" "external_dns" {
  name   = "${var.cluster_name}-external-dns-policy"
  policy = file("${path.module}/iam-policies/external-dns-policy.json")
  tags   = local.common_tags
}

resource "aws_iam_role" "external_dns_irsa" {
  name = "${var.cluster_name}-external-dns-irsa"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Federated = aws_iam_openid_connect_provider.this.arn
      }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_host}:sub" = "system:serviceaccount:external-dns:external-dns"
          "${local.oidc_host}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "external_dns_irsa" {
  role       = aws_iam_role.external_dns_irsa.name
  policy_arn = aws_iam_policy.external_dns.arn
}

# ── IRSA: External Secrets Operator ──────────────────────────────────────────

resource "aws_iam_policy" "external_secrets" {
  name   = "${var.cluster_name}-external-secrets-policy"
  policy = file("${path.module}/iam-policies/external-secrets-policy.json")
  tags   = local.common_tags
}

resource "aws_iam_role" "external_secrets_irsa" {
  name = "${var.cluster_name}-external-secrets-irsa"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Federated = aws_iam_openid_connect_provider.this.arn
      }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_host}:sub" = "system:serviceaccount:external-secrets:external-secrets-sa"
          "${local.oidc_host}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "external_secrets_irsa" {
  role       = aws_iam_role.external_secrets_irsa.name
  policy_arn = aws_iam_policy.external_secrets.arn
}

# ── Cluster admin access entries ──────────────────────────────────────────────
# EKS does not reliably auto-grant the cluster creator kubectl access.
# Explicitly create Access Entries for IAM users/roles that need cluster-admin.

resource "aws_eks_access_entry" "admins" {
  for_each = toset(var.cluster_admin_arns)

  cluster_name  = aws_eks_cluster.this.name
  principal_arn = each.key
  type          = "STANDARD"
  tags          = local.common_tags
}

resource "aws_eks_access_policy_association" "admins" {
  for_each = toset(var.cluster_admin_arns)

  cluster_name  = aws_eks_cluster.this.name
  principal_arn = each.key
  policy_arn    = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"

  access_scope {
    type = "cluster"
  }

  depends_on = [aws_eks_access_entry.admins]
}
