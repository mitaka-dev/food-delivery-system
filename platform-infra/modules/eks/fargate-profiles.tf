# ── Fargate pod execution role ────────────────────────────────────────────────
# One shared role for all Fargate profiles in this cluster.

resource "aws_iam_role" "fargate_pod_execution" {
  name = "${var.cluster_name}-fargate-pod-execution"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "eks-fargate-pods.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "fargate_AmazonEKSFargatePodExecutionRolePolicy" {
  role       = aws_iam_role.fargate_pod_execution.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSFargatePodExecutionRolePolicy"
}

# ── Fargate profiles ──────────────────────────────────────────────────────────
# Grouped profiles — AWS default limit is 10 profiles per cluster, but each
# profile supports up to 5 namespace selectors. Three groups of 5 covers all
# 15 namespaces while staying well under the limit.

resource "aws_eks_fargate_profile" "namespaces" {
  for_each = var.fargate_profile_groups

  cluster_name           = aws_eks_cluster.this.name
  fargate_profile_name   = "${var.cluster_name}-${each.key}"
  pod_execution_role_arn = aws_iam_role.fargate_pod_execution.arn
  subnet_ids             = var.private_subnet_ids

  dynamic "selector" {
    for_each = each.value
    content {
      namespace = selector.value
    }
  }

  tags = merge(var.tags, { Name = "${var.cluster_name}-fargate-${each.key}" })
}
