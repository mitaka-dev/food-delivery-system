data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# ── Platform-wide KMS CMK for SNS/SQS ────────────────────────────────────────
# SNS and SQS service principals need GenerateDataKey + Decrypt so they can
# encrypt/decrypt messages on behalf of producers and consumers. Without these
# grants the SNS→SQS delivery silently fails even when IAM looks correct.

resource "aws_kms_key" "sns_sqs" {
  description             = "Platform CMK — SNS topics and SQS queues"
  deletion_window_in_days = 14
  enable_key_rotation     = true

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "RootAccountFullControl"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
        }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "AllowSNSSQSEncryption"
        Effect = "Allow"
        Principal = {
          Service = ["sns.amazonaws.com", "sqs.amazonaws.com"]
        }
        Action = [
          "kms:GenerateDataKey*",
          "kms:Decrypt"
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      },
      {
        Sid    = "AllowCloudWatchAlarms"
        Effect = "Allow"
        Principal = {
          Service = "cloudwatch.amazonaws.com"
        }
        Action = [
          "kms:GenerateDataKey*",
          "kms:Decrypt"
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      }
    ]
  })

  tags = { Name = "food-delivery-sns-sqs-cmk" }
}

resource "aws_kms_alias" "sns_sqs" {
  name          = "alias/food-delivery/sns-sqs"
  target_key_id = aws_kms_key.sns_sqs.key_id
}

# ── charge-payment ─────────────────────────────────────────────────────────────
# order-service → SNS → SQS → payment-service: charge a Stripe order

module "charge_payment" {
  source = "../../modules/sns-sqs-pair"

  name        = "charge-payment"
  environment = "production"
  kms_key_arn = aws_kms_key.sns_sqs.arn

  tags = { Project = "food-delivery" }
}

# ── basket-compensation ────────────────────────────────────────────────────────
# order-service → SNS → SQS → basket-service: restore cart on payment failure

module "basket_compensation" {
  source = "../../modules/sns-sqs-pair"

  name        = "basket-compensation"
  environment = "production"
  kms_key_arn = aws_kms_key.sns_sqs.arn

  tags = { Project = "food-delivery" }
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "charge_payment_topic_arn" {
  description = "SNS topic ARN — publish here to trigger payment charging"
  value       = module.charge_payment.topic_arn
}

output "charge_payment_queue_url" {
  description = "SQS queue URL — payment-service polls this"
  value       = module.charge_payment.queue_url
}

output "charge_payment_dlq_arn" {
  description = "DLQ ARN for charge-payment"
  value       = module.charge_payment.dlq_arn
}

output "basket_compensation_topic_arn" {
  description = "SNS topic ARN — publish here to trigger cart restore"
  value       = module.basket_compensation.topic_arn
}

output "basket_compensation_queue_url" {
  description = "SQS queue URL — basket-service polls this"
  value       = module.basket_compensation.queue_url
}

output "basket_compensation_dlq_arn" {
  description = "DLQ ARN for basket-compensation"
  value       = module.basket_compensation.dlq_arn
}
