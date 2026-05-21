variable "name" {
  description = "Name prefix for all resources"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID for the VPC Link security group"
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for the VPC Link"
  type        = list(string)
}

variable "default_throttling_burst_limit" {
  description = "Default burst throttle limit (requests per second)"
  type        = number
  default     = 1000
}

variable "default_throttling_rate_limit" {
  description = "Default steady-state throttle limit (requests per second)"
  type        = number
  default     = 500
}

variable "waf_acl_arn" {
  description = "WAF Web ACL ARN to associate with the default stage; empty string disables association"
  type        = string
  default     = ""
}

variable "access_log_retention_days" {
  description = "CloudWatch log retention for API Gateway access logs"
  type        = number
  default     = 30
}

variable "tags" {
  description = "Additional resource tags"
  type        = map(string)
  default     = {}
}
