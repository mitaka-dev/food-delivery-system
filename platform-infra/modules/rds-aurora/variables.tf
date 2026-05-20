variable "cluster_identifier" {
  description = "Unique identifier for the Aurora cluster (e.g., food-delivery-staging)"
  type        = string
}

variable "environment" {
  description = "Deployment environment: staging or production"
  type        = string
  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment must be staging or production"
  }
}

variable "engine_version" {
  description = "Aurora PostgreSQL engine version"
  type        = string
  default     = "16.6"
}

variable "master_username" {
  description = "Master DB username"
  type        = string
  default     = "fooddelivery"
}

variable "vpc_id" {
  description = "VPC ID from the VPC module"
  type        = string
}

variable "isolated_subnet_ids" {
  description = "Isolated subnet IDs (no internet route) for the DB subnet group"
  type        = list(string)
}

variable "vpc_cidr_block" {
  description = "VPC CIDR block — used in the security group ingress rule"
  type        = string
}

variable "min_capacity" {
  description = "Minimum Aurora Serverless v2 capacity in ACUs (0.5 minimum)"
  type        = number
  default     = 0.5
}

variable "max_capacity" {
  description = "Maximum Aurora Serverless v2 capacity in ACUs"
  type        = number
  default     = 4
}

variable "instance_count" {
  description = "Number of cluster instances. 1 = writer only (staging). 2 = writer + reader (production multi-AZ)."
  type        = number
  default     = 1
}

variable "backup_retention_days" {
  description = "Automated backup retention period in days (1–35)"
  type        = number
  default     = 7
}

variable "performance_insights_retention_days" {
  description = "Performance Insights retention in days. 7 = free tier."
  type        = number
  default     = 7
}

variable "deletion_protection" {
  description = "Prevent the cluster from being deleted. Enable for production."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Additional tags merged into all resources"
  type        = map(string)
  default     = {}
}
