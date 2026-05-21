data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# ── Shared KMS CMK (ECR image encryption + CodeArtifact package encryption) ──
# ECR requires CreateGrant + DescribeKey so it can create internal data-key
# grants on behalf of image push/pull callers — without this, encrypted
# repositories silently fail to authenticate even with correct IAM.
# CodeArtifact needs the same to encrypt package assets at rest.

resource "aws_kms_key" "shared" {
  description             = "Platform CMK — ECR images and CodeArtifact packages"
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
        Sid       = "AllowECRGrants"
        Effect    = "Allow"
        Principal = { Service = "ecr.amazonaws.com" }
        Action    = ["kms:CreateGrant", "kms:DescribeKey"]
        Resource  = "*"
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      },
      {
        Sid       = "AllowCodeArtifactGrants"
        Effect    = "Allow"
        Principal = { Service = "codeartifact.amazonaws.com" }
        Action    = ["kms:CreateGrant", "kms:DescribeKey", "kms:GenerateDataKey"]
        Resource  = "*"
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      }
    ]
  })

  tags = { Name = "food-delivery-shared-cmk" }
}

resource "aws_kms_alias" "shared" {
  name          = "alias/food-delivery/shared"
  target_key_id = aws_kms_key.shared.key_id
}

# ── ECR repositories — v1 services ──────────────────────────────────────────

locals {
  v1_services = toset(["user-service", "product-service", "basket-service", "payment-service", "order-service"])
}

module "ecr" {
  source   = "../../modules/ecr-repo"
  for_each = local.v1_services

  name        = each.key
  kms_key_arn = aws_kms_key.shared.arn

  tags = { Project = "food-delivery" }
}

# ── Outputs ──────────────────────────────────────────────────────────────────

output "ecr_repository_urls" {
  description = "ECR repository URLs keyed by service name"
  value       = { for k, v in module.ecr : k => v.repository_url }
}

output "ecr_repository_arns" {
  description = "ECR repository ARNs keyed by service name"
  value       = { for k, v in module.ecr : k => v.repository_arn }
}

output "shared_kms_key_arn" {
  description = "Shared KMS CMK ARN (ECR + CodeArtifact)"
  value       = aws_kms_key.shared.arn
}
