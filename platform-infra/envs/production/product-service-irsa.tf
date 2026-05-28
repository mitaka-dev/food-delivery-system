# ── product-service IRSA role ─────────────────────────────────────────────────

resource "aws_iam_role" "product_service_irsa" {
  name = "food-delivery-production-product-service-irsa"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = module.eks.oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          # oidc_provider_url already strips the https:// prefix
          "${module.eks.oidc_provider_url}:sub" = "system:serviceaccount:product-service:product-service"
          "${module.eks.oidc_provider_url}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })

  tags = { Service = "product-service" }
}

resource "aws_iam_role_policy" "product_service_irsa" {
  name = "product-service-policy"
  role = aws_iam_role.product_service_irsa.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # ── Secrets Manager ──────────────────────────────────────────────────────
      {
        Sid    = "ReadServiceSecrets"
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
        Resource = [
          aws_secretsmanager_secret.product_service_db.arn,
          module.redis.auth_token_secret_arn,
        ]
      },
      # ── S3 product images ────────────────────────────────────────────────────
      # product-service generates pre-signed PUT URLs; also needs GetObject for
      # serving image metadata (key presence check) from the presigned URL flow.
      {
        Sid      = "S3ProductImages"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
        Resource = "arn:aws:s3:::food-delivery-production-product-images/*"
      },
      # ── MSK IAM auth ─────────────────────────────────────────────────────────
      # product-service consumes from order-topics, payment-topics, kitchen-events.
      {
        Sid      = "MSKConnect"
        Effect   = "Allow"
        Action   = ["kafka-cluster:Connect", "kafka-cluster:AlterCluster", "kafka-cluster:DescribeCluster"]
        Resource = module.msk.cluster_arn
      },
      {
        Sid      = "MSKTopics"
        Effect   = "Allow"
        Action   = ["kafka-cluster:*Topic*", "kafka-cluster:WriteData", "kafka-cluster:ReadData"]
        Resource = "arn:aws:kafka:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:topic/food-delivery-production/*"
      },
      {
        Sid      = "MSKGroups"
        Effect   = "Allow"
        Action   = ["kafka-cluster:AlterGroup", "kafka-cluster:DescribeGroup"]
        Resource = "arn:aws:kafka:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:group/food-delivery-production/*"
      },
    ]
  })
}

# ── DB credentials ────────────────────────────────────────────────────────────

resource "aws_secretsmanager_secret" "product_service_db" {
  name        = "food-delivery/production/product-service/db"
  description = "Database connection credentials for product-service (product_db)"
  kms_key_id  = aws_kms_key.sns_sqs.arn

  tags = { Service = "product-service" }
}

resource "aws_secretsmanager_secret_version" "product_service_db" {
  secret_id = aws_secretsmanager_secret.product_service_db.id

  secret_string = jsonencode({
    DB_URL      = "jdbc:postgresql://${module.rds.cluster_endpoint}:${module.rds.port}/product_db"
    DB_USERNAME = "product_user"
    DB_PASSWORD = "REPLACE_AFTER_TF_APPLY"
  })
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "product_service_irsa_role_arn" {
  description = "IRSA role ARN — paste into apps/product-service/base/serviceaccount.yaml"
  value       = aws_iam_role.product_service_irsa.arn
}

output "product_service_db_secret_arn" {
  description = "DB credentials secret ARN"
  value       = aws_secretsmanager_secret.product_service_db.arn
}
