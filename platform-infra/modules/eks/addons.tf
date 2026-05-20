# Native EKS add-ons only. AWS Load Balancer Controller, External DNS, and
# External Secrets Operator are Helm-based and installed at Step 0.11 (ArgoCD).
# Cluster Autoscaler is omitted — it has no effect on pure Fargate clusters.
#
# CoreDNS is intentionally NOT managed here. On pure-Fargate clusters, CoreDNS
# pods carry an `eks.amazonaws.com/compute-type: ec2` annotation that prevents
# scheduling on Fargate. This annotation must be patched with kubectl before
# CoreDNS can become ACTIVE, creating a bootstrapping deadlock with Terraform's
# synchronous wait model. EKS manages CoreDNS as a built-in component regardless
# of whether it is declared here; the annotation patch is a one-time manual step
# documented in post-apply instructions.

resource "aws_eks_addon" "vpc_cni" {
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = "vpc-cni"
  addon_version               = "v1.20.5-eksbuild.1"
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"
  service_account_role_arn    = aws_iam_role.vpc_cni_irsa.arn

  tags       = var.tags
  depends_on = [aws_eks_fargate_profile.namespaces]
}

resource "aws_eks_addon" "kube_proxy" {
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = "kube-proxy"
  addon_version               = "v1.30.14-eksbuild.28"
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  tags       = var.tags
  depends_on = [aws_eks_fargate_profile.namespaces]
}
