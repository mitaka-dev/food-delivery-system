variable "name" {
  description = "ECR repository name"
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key ARN for image encryption"
  type        = string
}

variable "tags" {
  description = "Additional resource tags"
  type        = map(string)
  default     = {}
}
