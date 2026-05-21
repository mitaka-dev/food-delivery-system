# ── S3 artifact bucket ───────────────────────────────────────────────────────
# Account ID suffix makes the global bucket name unique without random IDs.

resource "aws_s3_bucket" "cicd_artifacts" {
  bucket = "food-delivery-cicd-artifacts-${data.aws_caller_identity.current.account_id}"
  tags   = { Name = "food-delivery-cicd-artifacts" }
}

resource "aws_s3_bucket_versioning" "cicd_artifacts" {
  bucket = aws_s3_bucket.cicd_artifacts.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "cicd_artifacts" {
  bucket = aws_s3_bucket.cicd_artifacts.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.shared.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "cicd_artifacts" {
  bucket                  = aws_s3_bucket.cicd_artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ── CodeBuild service role ───────────────────────────────────────────────────

resource "aws_iam_role" "codebuild" {
  name = "food-delivery-codebuild"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "codebuild.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "codebuild" {
  name = "food-delivery-codebuild-policy"
  role = aws_iam_role.codebuild.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # GetAuthorizationToken is account-level — no specific repo ARN available.
        Sid      = "ECRAuth"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "ECRPush"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:PutImage"
        ]
        Resource = [for v in module.ecr : v.repository_arn]
      },
      {
        Sid    = "CodeArtifact"
        Effect = "Allow"
        Action = [
          "codeartifact:GetAuthorizationToken",
          "codeartifact:GetRepositoryEndpoint",
          "codeartifact:ReadFromRepository",
          "codeartifact:PublishPackageVersion",
          "codeartifact:PutPackageMetadata",
          "sts:GetServiceBearerToken"
        ]
        Resource = "*"
      },
      {
        Sid    = "S3Artifacts"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:GetObjectVersion",
          "s3:GetBucketVersioning",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.cicd_artifacts.arn,
          "${aws_s3_bucket.cicd_artifacts.arn}/*"
        ]
      },
      {
        # Scoped to food-delivery MSK clusters so integration tests can produce
        # and consume without granting broad Kafka permissions.
        Sid    = "MSKIntegrationTests"
        Effect = "Allow"
        Action = [
          "kafka-cluster:Connect",
          "kafka-cluster:AlterCluster",
          "kafka-cluster:DescribeCluster",
          "kafka-cluster:ReadData",
          "kafka-cluster:WriteData",
          "kafka-cluster:DescribeTopic",
          "kafka-cluster:CreateTopic",
          "kafka-cluster:DeleteTopic",
          "kafka-cluster:DescribeGroup",
          "kafka-cluster:AlterGroup",
          "kafka-cluster:DeleteGroup"
        ]
        Resource = [
          "arn:aws:kafka:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:cluster/food-delivery-*/*",
          "arn:aws:kafka:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:topic/food-delivery-*/*/*",
          "arn:aws:kafka:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:group/food-delivery-*/*"
        ]
      },
      {
        Sid    = "CloudWatchLogs"
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "arn:aws:logs:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:log-group:/aws/codebuild/*"
      },
      {
        Sid    = "KMSArtifacts"
        Effect = "Allow"
        Action = ["kms:GenerateDataKey*", "kms:Decrypt"]
        Resource = aws_kms_key.shared.arn
      },
      {
        Sid    = "SecretsManager"
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = "arn:aws:secretsmanager:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:secret:food-delivery/*"
      }
    ]
  })
}

# ── CodePipeline service role ────────────────────────────────────────────────

resource "aws_iam_role" "codepipeline" {
  name = "food-delivery-codepipeline"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "codepipeline.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "codepipeline" {
  name = "food-delivery-codepipeline-policy"
  role = aws_iam_role.codepipeline.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3Artifacts"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:GetObjectVersion",
          "s3:GetBucketVersioning",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.cicd_artifacts.arn,
          "${aws_s3_bucket.cicd_artifacts.arn}/*"
        ]
      },
      {
        Sid    = "CodeBuild"
        Effect = "Allow"
        Action = ["codebuild:BatchGetBuilds", "codebuild:StartBuild"]
        Resource = "arn:aws:codebuild:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:project/food-delivery-*"
      },
      {
        Sid      = "PassRoleToCodeBuild"
        Effect   = "Allow"
        Action   = "iam:PassRole"
        Resource = aws_iam_role.codebuild.arn
      },
      {
        Sid      = "ECRDescribe"
        Effect   = "Allow"
        Action   = ["ecr:DescribeImages"]
        Resource = [for v in module.ecr : v.repository_arn]
      },
      {
        Sid    = "KMSArtifacts"
        Effect = "Allow"
        Action = ["kms:GenerateDataKey*", "kms:Decrypt"]
        Resource = aws_kms_key.shared.arn
      }
    ]
  })
}

# ── Outputs ──────────────────────────────────────────────────────────────────

output "codebuild_role_arn" {
  description = "CodeBuild service role ARN"
  value       = aws_iam_role.codebuild.arn
}

output "codepipeline_role_arn" {
  description = "CodePipeline service role ARN"
  value       = aws_iam_role.codepipeline.arn
}

output "cicd_artifact_bucket" {
  description = "S3 bucket for CI/CD pipeline artifacts"
  value       = aws_s3_bucket.cicd_artifacts.bucket
}
