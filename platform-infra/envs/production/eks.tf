module "eks" {
  source = "../../modules/eks"

  cluster_name       = "food-delivery-production-eks"
  environment        = "production"
  kubernetes_version = "1.32"

  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
  vpc_cidr_block     = module.vpc.vpc_cidr_block

  # TODO Step 10: restrict to VPN CIDR once bastion is provisioned
  cluster_endpoint_public_access_cidrs = ["0.0.0.0/0"]

  cloudwatch_log_retention_days = 30

  # fargate_profile_groups uses module default: 3 grouped profiles covering all 15 namespaces

  tags = {
    Project = "food-delivery"
  }
}

output "eks_cluster_name" {
  description = "Run: aws eks update-kubeconfig --region eu-west-1 --name <value>"
  value       = module.eks.cluster_name
}

output "eks_oidc_provider_arn" {
  description = "OIDC provider ARN — consumed by all IRSA trust policies"
  value       = module.eks.oidc_provider_arn
}

output "eks_oidc_provider_url" {
  description = "OIDC provider URL (without https://) — used in IRSA condition values"
  value       = module.eks.oidc_provider_url
}

output "eks_lbc_irsa_role_arn" {
  value = module.eks.lbc_irsa_role_arn
}

output "eks_external_dns_irsa_role_arn" {
  value = module.eks.external_dns_irsa_role_arn
}

output "eks_external_secrets_irsa_role_arn" {
  value = module.eks.external_secrets_irsa_role_arn
}
