variable "vpc_name" {
  description = "Name prefix for all resources"
  type        = string
}

variable "environment" {
  description = "Environment name (staging, production)"
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "azs" {
  description = "List of availability zone names"
  type        = list(string)
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for public subnets — one per AZ"
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "CIDR blocks for private subnets — one per AZ"
  type        = list(string)
}

variable "isolated_subnet_cidrs" {
  description = "CIDR blocks for isolated subnets (RDS only) — one per AZ"
  type        = list(string)
}

variable "single_nat_gateway" {
  description = "Use one NAT gateway for all AZs instead of one per AZ (cost saving for non-prod)"
  type        = bool
  default     = false
}

variable "tags" {
  description = "Additional tags merged into all resources"
  type        = map(string)
  default     = {}
}
