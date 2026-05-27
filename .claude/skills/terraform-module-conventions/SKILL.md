---
name: terraform-module-conventions
description: IaC conventions for platform-infra/. Use when writing or editing any .tf, .tfvars file, or adding a new Terraform module.
allowed-tools: Read, Edit, Write, Bash(terraform fmt *), Bash(terraform validate)
---

# Terraform Module Conventions

All infrastructure lives in `platform-infra/`. Terraform 1.7+, AWS provider ~> 5.x.

## Repository Layout

```
platform-infra/
├── modules/          — reusable modules (vpc, eks, rds-aurora, dynamodb-table, etc.)
│   └── {name}/
│       ├── main.tf
│       ├── variables.tf
│       ├── outputs.tf
│       └── versions.tf
├── envs/
│   ├── shared/       — cross-env: ECR, IAM, CodeArtifact
│   └── production/   — the only active env (no staging/load-test yet)
└── scripts/
```

## Naming Convention

Every resource: `{org}-{env}-{service}-{resource-type}`, implemented via locals:

```hcl
locals {
  name_prefix = "${var.org}-${var.environment}"
}

resource "aws_iam_role" "this" {
  name = "${local.name_prefix}-${var.service_name}-irsa"
}
```

Examples: `acme-prod-user-service-irsa`, `acme-prod-order-service-db`.

## Standard Tags (every taggable resource)

```hcl
provider "aws" {
  default_tags {
    tags = {
      Project     = "food-delivery-platform"
      Environment = var.environment
      Service     = var.service_name
      ManagedBy   = "terraform"
    }
  }
}
```

## Variable Conventions

```hcl
variable "environment" {
  description = "Deployment environment (production)"
  type        = string
  validation {
    condition     = contains(["production", "shared"], var.environment)
    error_message = "Must be 'production' or 'shared'."
  }
}
```

- Every variable has `description` and `type`
- Sensitive vars: `sensitive = true`
- Never set a default for values without a sensible platform-wide default — require them

## State Backend (every env root)

```hcl
terraform {
  backend "s3" {
    bucket         = "acme-tfstate-{account-id}"
    key            = "envs/{env}/{module}.tfstate"
    region         = "us-east-1"
    dynamodb_table = "acme-tfstate-locks"
    encrypt        = true
  }
}
```

## Secrets — Never in .tfvars

```hcl
# ✅ Correct: generate and store in SM
resource "random_password" "db_master" {
  length  = 32
  special = true
}

resource "aws_secretsmanager_secret_version" "db_creds" {
  secret_id     = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({ password = random_password.db_master.result })
}

# ✅ Correct: read existing secret
data "aws_secretsmanager_secret_version" "jwt_key" {
  secret_id = "/production/user-service/jwt-private-key"
}
```

## Loops: for_each over count

```hcl
# ✅ for_each — stable identity under add/remove
resource "aws_ecr_repository" "services" {
  for_each = toset(var.service_names)
  name     = each.key
}

# ❌ count — index shifts when items are removed
```

## IRSA Pattern (every service)

```hcl
# Standard IRSA naming: {org}-{env}-{service}-irsa
module "irsa" {
  source    = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  role_name = "${local.name_prefix}-${var.service_name}-irsa"

  oidc_providers = {
    main = {
      provider_arn               = var.oidc_provider_arn
      namespace_service_accounts = ["${var.service_name}:${var.service_name}"]
    }
  }
}
```

## Anti-Patterns — Flag Immediately

| Anti-pattern | Fix |
|---|---|
| Hardcoded account ID, region string, AZ | Use `data.aws_caller_identity`, `data.aws_region`, `data.aws_availability_zones` |
| `Action: "*"` in IAM | Enumerate specific actions |
| `Resource: "*"` on sensitive actions (s3:Delete, kms:Decrypt) | Scope to ARN |
| `0.0.0.0/0` ingress on non-443/80 ports | Restrict to VPC CIDR or specific SGs |
| Resources without tags | Add to `default_tags` in provider |
| `count` for resource lists | Use `for_each` |
| Inline IAM policy on role | Use attached managed policy |
| Secrets in `.tfvars` or variables | Use Secrets Manager |
| Missing `prevent_destroy` on RDS/DynamoDB | Add lifecycle block |
| `terraform fmt` failures | Run `terraform fmt -recursive .` before commit |

## Required CI Checks

Run locally before committing:
```bash
terraform fmt -check -recursive platform-infra/
terraform validate          # per env directory
```

CI runs `tflint`, `tfsec`, `checkov` additionally.
