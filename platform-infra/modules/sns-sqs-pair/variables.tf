variable "name" {
  type        = string
  description = "Base name for the SNS topic and SQS queue (e.g. 'charge-payment')"
}

variable "environment" {
  type = string
}

variable "kms_key_arn" {
  type        = string
  description = "ARN of the KMS CMK used to encrypt SNS and SQS messages"
}

variable "max_receive_count" {
  type        = number
  default     = 5
  description = "Number of receives before a message is moved to the DLQ"
}

variable "visibility_timeout_seconds" {
  type    = number
  default = 30
}

variable "message_retention_seconds" {
  type    = number
  default = 345600 # 4 days
}

variable "dlq_message_retention_seconds" {
  type    = number
  default = 1209600 # 14 days
}

variable "alarm_actions" {
  type        = list(string)
  default     = []
  description = "SNS topic ARNs to notify when the DLQ depth alarm fires"
}

variable "tags" {
  type    = map(string)
  default = {}
}
