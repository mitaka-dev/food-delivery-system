variable "cluster_name" {
  description = "Name of the EKS cluster (e.g., food-delivery-staging-eks)"
  type        = string
}

variable "environment" {
  description = "Environment name: staging or production"
  type        = string
  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment must be staging or production"
  }
}

variable "kubernetes_version" {
  description = "EKS Kubernetes version"
  type        = string
  default     = "1.30"
}

variable "vpc_id" {
  description = "VPC ID from the VPC module"
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for Fargate pods and cluster ENIs"
  type        = list(string)
}

variable "vpc_cidr_block" {
  description = "CIDR of the VPC — used in security group rules"
  type        = string
}

variable "cluster_endpoint_public_access_cidrs" {
  description = "CIDRs allowed to reach the public Kubernetes API endpoint. Defaults open — restrict at Step 10 once a VPN/bastion is in place."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "fargate_profile_groups" {
  description = "Map of profile name to list of namespaces. Each profile supports max 5 selectors; AWS default limit is 10 profiles per cluster."
  type        = map(list(string))
  default = {
    system     = ["kube-system", "argocd", "monitoring", "external-secrets", "external-dns"]
    services-a = ["identity", "menu", "basket", "payment", "order"]
    services-b = ["kitchen", "delivery", "review", "promotion", "notification"]
  }
}

variable "cloudwatch_log_retention_days" {
  description = "Retention in days for EKS control plane logs in CloudWatch"
  type        = number
  default     = 90
}

variable "cluster_admin_arns" {
  description = "IAM user/role ARNs granted cluster-admin access via EKS Access Entry (e.g., the Terraform executor, developer IAM users)"
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Additional tags merged into all resources"
  type        = map(string)
  default     = {}
}
