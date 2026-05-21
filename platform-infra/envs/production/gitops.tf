# ── CodeCommit gitops repo ────────────────────────────────────────────────────

resource "aws_codecommit_repository" "gitops" {
  repository_name = "food-delivery-gitops"
  description     = "GitOps manifests watched by ArgoCD"

  tags = { Name = "food-delivery-gitops" }
}

# ── ArgoCD gitops reader IAM user ─────────────────────────────────────────────

resource "aws_iam_user" "argocd_gitops_reader" {
  name = "argocd-gitops-reader"
  tags = { Name = "argocd-gitops-reader" }
}

resource "aws_iam_user_policy" "argocd_codecommit_read" {
  name = "codecommit-gitops-read"
  user = aws_iam_user.argocd_gitops_reader.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "codecommit:BatchGet*",
        "codecommit:Get*",
        "codecommit:List*",
        "codecommit:Describe*",
        "codecommit:GitPull",
      ]
      Resource = aws_codecommit_repository.gitops.arn
    }]
  })
}

# RSA key pair: public key registered as IAM SSH credential, private key in Secrets Manager.
# CodeCommit SSH URL embeds the IAM SSH key ID as the username:
#   ssh://<SSH_KEY_ID>@git-codecommit.<region>.amazonaws.com/v1/repos/<repo>
resource "tls_private_key" "argocd_gitops" {
  algorithm = "RSA"
  rsa_bits  = 4096
}

resource "aws_iam_user_ssh_key" "argocd_gitops" {
  username   = aws_iam_user.argocd_gitops_reader.name
  encoding   = "SSH"
  public_key = tls_private_key.argocd_gitops.public_key_openssh
}

resource "aws_secretsmanager_secret" "argocd_gitops_ssh_key" {
  name       = "/food-delivery/argocd/gitops-ssh-key"
  kms_key_id = aws_kms_key.sns_sqs.arn

  tags = { Name = "argocd-gitops-ssh-key" }
}

resource "aws_secretsmanager_secret_version" "argocd_gitops_ssh_key" {
  secret_id = aws_secretsmanager_secret.argocd_gitops_ssh_key.id

  secret_string = jsonencode({
    private_key    = tls_private_key.argocd_gitops.private_key_pem
    ssh_key_id     = aws_iam_user_ssh_key.argocd_gitops.ssh_public_key_id
    codecommit_url = "ssh://${aws_iam_user_ssh_key.argocd_gitops.ssh_public_key_id}@git-codecommit.${data.aws_region.current.name}.amazonaws.com/v1/repos/food-delivery-gitops"
  })
}

# ── Cognito user pool for ArgoCD SSO ─────────────────────────────────────────

resource "aws_cognito_user_pool" "argocd" {
  name = "food-delivery-argocd"

  # Admin-only user creation: platform engineers are provisioned manually
  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  password_policy {
    minimum_length                   = 12
    require_uppercase                = true
    require_lowercase                = true
    require_numbers                  = true
    require_symbols                  = true
    temporary_password_validity_days = 7
  }

  schema {
    name                = "email"
    attribute_data_type = "String"
    required            = true
    mutable             = true
  }

  tags = { Name = "food-delivery-argocd" }
}

resource "aws_cognito_user_pool_domain" "argocd" {
  domain       = "food-delivery-argocd-${data.aws_caller_identity.current.account_id}"
  user_pool_id = aws_cognito_user_pool.argocd.id
}

resource "aws_cognito_user_pool_client" "argocd" {
  name         = "argocd"
  user_pool_id = aws_cognito_user_pool.argocd.id

  generate_secret = true

  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "profile", "email"]
  allowed_oauth_flows_user_pool_client = true

  # Callback must match the ArgoCD server URL configured in values.yaml
  callback_urls = ["https://argocd.internal.food-delivery-platform.io/auth/callback"]
  logout_urls   = ["https://argocd.internal.food-delivery-platform.io"]

  supported_identity_providers = ["COGNITO"]

  explicit_auth_flows = [
    "ALLOW_USER_SRP_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH",
  ]
}

# SSM parameters — consumed by install-argocd.sh to inject OIDC config at install time
resource "aws_ssm_parameter" "argocd_oidc_issuer" {
  name  = "/food-delivery/argocd/oidc-issuer"
  type  = "String"
  value = "https://cognito-idp.${data.aws_region.current.name}.amazonaws.com/${aws_cognito_user_pool.argocd.id}"
}

resource "aws_ssm_parameter" "argocd_oidc_client_id" {
  name  = "/food-delivery/argocd/oidc-client-id"
  type  = "String"
  value = aws_cognito_user_pool_client.argocd.id
}

resource "aws_ssm_parameter" "argocd_oidc_client_secret" {
  name  = "/food-delivery/argocd/oidc-client-secret"
  type  = "SecureString"
  value = aws_cognito_user_pool_client.argocd.client_secret
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "codecommit_gitops_clone_url_ssh" {
  description = "CodeCommit SSH clone URL (key ID embedded as username)"
  value       = "ssh://${aws_iam_user_ssh_key.argocd_gitops.ssh_public_key_id}@git-codecommit.${data.aws_region.current.name}.amazonaws.com/v1/repos/food-delivery-gitops"
}

output "codecommit_gitops_clone_url_https" {
  description = "CodeCommit HTTPS clone URL"
  value       = aws_codecommit_repository.gitops.clone_url_http
}

output "argocd_ssh_key_secret_arn" {
  description = "Secrets Manager ARN containing the ArgoCD gitops SSH private key"
  value       = aws_secretsmanager_secret.argocd_gitops_ssh_key.arn
}

output "cognito_argocd_user_pool_id" {
  description = "Cognito user pool ID for ArgoCD SSO"
  value       = aws_cognito_user_pool.argocd.id
}

output "cognito_argocd_hosted_ui" {
  description = "Cognito hosted UI base URL for OIDC discovery"
  value       = "https://${aws_cognito_user_pool_domain.argocd.domain}.auth.${data.aws_region.current.name}.amazoncognito.com"
}
