# Project Status

**Last updated:** 2026-05-21

## Current Situation

The local development system is complete. Phase 0 infrastructure is complete — all 10 planned steps done (0.5 deferred to Step 5.1). ArgoCD is bootstrapped on EKS with Cognito OIDC SSO, internal ALB ingress, the App-of-Apps pattern in place, and the CodeCommit gitops repo wired via SSH. Phase 1 (shared libraries and platform BOM) is next.

## Where We Are

- **Local system:** Complete. All services built and working, reorganised under `services/`.
- **AWS build plan:** In progress — 10/97 steps complete (Steps 0.1–0.4, 0.6–0.11).
- **AWS account:** Dedicated account created; credentials configured. See `docs/aws-account-setup.md`.
- **Environment:** Single env — `platform-infra/envs/production/` only. No staging env.
- **Repo layout:** This repo IS `food-delivery-platform`. Services under `services/`. `food-delivery-gitops` at `../food-delivery-gitops/`.
- **Active branch:** `build/phase-0` (pushed to `origin/build/phase-0`).
- **API Hardening:** Gaps identified in `docs/API_AUDIT.md` — remediation integrated into the AWS build plan.

## Completed Steps

### Step 0.1 (2026-05-20)
- All 11 services moved under `services/`
- Maven `relativePath` fixed in all service POMs
- `docker-compose.yml` updated; observability image tags pinned
- Added: `.tool-versions`, `.terraform-version`, `.envrc.template`, `scripts/bootstrap-dev.sh`, `docs/spring-profiles.md`, `docs/developer-setup.md`
- `food-delivery-gitops` repo initialized at `../food-delivery-gitops/`

### Step 0.2 (2026-05-20)
- VPC module at `platform-infra/modules/vpc/`
- Production env: `platform-infra/envs/production/network.tf` — 2 AZs, single NAT GW, `10.0.0.0/16`
- VPC endpoints: S3 + DynamoDB (gateway), ECR/API/DKR, Secrets Manager, SNS, SQS, STS, Logs (interface)
- Flow logs → CloudWatch

### Step 0.3 (2026-05-20)
- EKS module at `platform-infra/modules/eks/`
- EKS cluster on Fargate, 15 Fargate profiles (one per namespace), IRSA wired
- Add-ons: vpc-cni, coredns, kube-proxy
- IRSA roles pre-created: LBC, ExternalDNS, ExternalSecrets, VPC CNI

### Step 0.4 (2026-05-20)
- RDS Aurora module at `platform-infra/modules/rds-aurora/`
- Aurora PostgreSQL Serverless v2 (0.5–4 ACU), single instance, isolated subnets
- Master credentials in Secrets Manager with managed rotation

### Step 0.5 — Deferred
- DynamoDB tables deferred from Phase 0; provisioned alongside their service phases
- `payment-ledger` / `outbox-payment` → Step 5.1; `tickets` / `outbox-kitchen` → Step 11.1; etc.
- Reusable `platform-infra/modules/dynamodb-table/` module created in Step 5.1 when first needed

### Step 0.6 (2026-05-21)
- ElastiCache Redis module at `platform-infra/modules/elasticache-redis/`
- Redis 7, cluster mode enabled, isolated subnets, TLS + IAM auth
- `platform-infra/envs/production/cache.tf`

### Step 0.7 (2026-05-21)
- MSK module at `platform-infra/modules/msk/`
- MSK Serverless, IAM auth + TLS, Glue Schema Registry wired
- `platform-infra/envs/production/kafka.tf`

### Step 0.8 (2026-05-21)
- Reusable module at `platform-infra/modules/sns-sqs-pair/`
- v1 queues: `charge-payment`, `basket-compensation` — each with DLQ (`maxReceiveCount=5`)
- Platform-wide KMS CMK (`alias/food-delivery/sns-sqs`) with grants for SNS, SQS, and CloudWatch service principals
- CloudWatch alarms on every DLQ depth > 0
- `platform-infra/envs/production/messaging-sns-sqs.tf`

### Step 0.9 (2026-05-21)
- Reusable module at `platform-infra/modules/ecr-repo/` — IMMUTABLE tags, scan-on-push, KMS encryption, lifecycle policy
- 5 v1 ECR repos (`user-service`, `product-service`, `basket-service`, `payment-service`, `order-service`)
- New Terraform root `platform-infra/envs/shared/` for account-level resources
- Shared KMS CMK (`alias/food-delivery/shared`) for ECR image + CodeArtifact package encryption
- CodeBuild role: ECR push, CodeArtifact read/publish, S3 artifacts, MSK produce/consume, Secrets Manager
- CodePipeline role: S3 artifacts, CodeBuild trigger, ECR describe
- S3 artifact bucket `food-delivery-cicd-artifacts-{account_id}` with versioning + SSE-KMS
- CodeArtifact domain `food-delivery-platform` with `internal` repo (upstream: `maven-central` proxy)

### Step 0.10 (2026-05-21)
- `platform-infra/modules/api-gateway/` — HTTP API (v2), VPC Link + SG, CloudWatch access logs, default stage with throttling, optional WAF association
- WAF Web ACL: Common, KnownBadInputs, SQLi managed rule groups + rate limit 2000 req/5min per IP
- Health Lambda (Node.js 20/arm64, inline) — GET /health, no auth
- JWT authorizer Lambda — reads RSA public key from SSM, validates RS256 with `crypto.createVerify`, 5-min result cache
- SSM placeholder `/food-delivery/jwt-public-key` with `ignore_changes = [value]`
- ACM certificate + custom domain `api-production.food-delivery-platform.io` (PENDING_VALIDATION until DNS records added)
- `platform-infra/envs/production/api.tf`

### Step 0.11 (2026-05-21)
- `platform-infra/envs/production/gitops.tf` — CodeCommit repo, IAM user `argocd-gitops-reader` with read-only policy, RSA SSH key pair (public in IAM, private in Secrets Manager), Cognito user pool + client for ArgoCD OIDC SSO, SSM params for OIDC issuer/client
- `platform-infra/scripts/install-argocd.sh` — one-shot bootstrap: installs AWS LBC, ArgoCD (chart 7.4.4 / ArgoCD 2.10.x), Argo Rollouts; creates OIDC + SSH repo secrets; applies AppProject and root App-of-Apps Application
- `food-delivery-gitops/argocd/install/values.yaml` — ArgoCD Helm values: internal ALB ingress, admin disabled, Cognito RBAC, Notifications controller enabled
- `food-delivery-gitops/argocd/projects/services.yaml` — AppProject `services` scoped to service namespaces + CodeCommit source only
- `food-delivery-gitops/argocd/applications/_app-of-apps.yaml` — root Application template watching `apps/` directory
- `food-delivery-gitops/README.md` — updated with accurate bootstrap and usage instructions

## Next Step

**Step 1.1** — Root reactor POM + platform-bom (Bill of Materials).
- `food-delivery-platform/pom.xml` (root Maven reactor)
- `food-delivery-platform/platform-bom/pom.xml` (BOM pinning all dependency versions)
- `food-delivery-platform/platform-shared-libs/pom.xml`
- CodeArtifact publication scripts

## Key Files

- `docs/plan/BUILD-PLAN.md` — phased build steps with `- [ ]` / `- [x]` checkboxes
- `docs/plan/ARCHITECTURE.md` — reference architecture (sections 1–10)
- `docs/API_AUDIT.md` — outstanding API gaps from 2026-04-17 audit
- `docs/spring-profiles.md` — two-profile convention (local / production)
- `docs/developer-setup.md` — new developer onboarding guide
