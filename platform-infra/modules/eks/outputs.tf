output "cluster_name" {
  description = "EKS cluster name"
  value       = aws_eks_cluster.this.name
}

output "cluster_endpoint" {
  description = "EKS API server endpoint"
  value       = aws_eks_cluster.this.endpoint
}

output "cluster_ca_certificate" {
  description = "Base64-encoded cluster CA certificate"
  value       = aws_eks_cluster.this.certificate_authority[0].data
  sensitive   = true
}

output "cluster_arn" {
  description = "EKS cluster ARN"
  value       = aws_eks_cluster.this.arn
}

output "oidc_provider_arn" {
  description = "OIDC provider ARN — required for all IRSA trust policies"
  value       = aws_iam_openid_connect_provider.this.arn
}

output "oidc_provider_url" {
  description = "OIDC issuer URL without https:// prefix — used in IRSA condition values"
  value       = local.oidc_host
}

output "oidc_issuer_url" {
  description = "Full OIDC issuer URL with https:// prefix"
  value       = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

output "cluster_security_group_id" {
  description = "EKS-managed cluster security group ID"
  value       = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
}

output "additional_cluster_sg_id" {
  description = "Additional cluster security group ID created by this module"
  value       = aws_security_group.cluster_additional.id
}

output "fargate_pod_execution_role_arn" {
  description = "ARN of the Fargate pod execution IAM role"
  value       = aws_iam_role.fargate_pod_execution.arn
}

output "cluster_role_arn" {
  description = "ARN of the EKS cluster IAM role"
  value       = aws_iam_role.cluster.arn
}

output "vpc_cni_irsa_role_arn" {
  description = "IRSA role ARN for the vpc-cni addon"
  value       = aws_iam_role.vpc_cni_irsa.arn
}

output "lbc_irsa_role_arn" {
  description = "IRSA role ARN for AWS Load Balancer Controller (consumed by Step 0.11 Helm values)"
  value       = aws_iam_role.lbc_irsa.arn
}

output "external_dns_irsa_role_arn" {
  description = "IRSA role ARN for External DNS (consumed by Step 0.11 Helm values)"
  value       = aws_iam_role.external_dns_irsa.arn
}

output "external_secrets_irsa_role_arn" {
  description = "IRSA role ARN for External Secrets Operator (consumed by Step 0.11 Helm values)"
  value       = aws_iam_role.external_secrets_irsa.arn
}
