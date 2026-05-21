# Project Status

**Last updated:** 2026-05-21

## Current Situation

The local development system is complete. Phase 0 infrastructure is in progress — 6 of 7 planned steps done. The core AWS primitives are live: VPC, EKS (Fargate), Aurora PostgreSQL, ElastiCache Redis, and MSK Serverless Kafka. Step 0.8 (SNS/SQS queues for saga compensation) is next.

## Where We Are

- **Local system:** Complete. All services built and working, reorganised under `services/`.
- **AWS build plan:** In progress — 6/97 steps complete (Steps 0.1–0.4, 0.6, 0.7).
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

## Next Step

**Step 0.8** — Terraform: SNS topics, SQS queues, DLQs (compensation + webhook intake).
- `platform-infra/modules/sns-sqs-pair/` — reusable module (SNS topic + SQS queue + subscription + DLQ)
- `platform-infra/envs/production/messaging-sns-sqs.tf`
- v1 queues: `charge-payment`, `basket-compensation` — each with DLQ (`maxReceiveCount=5`)
- All messages KMS-encrypted; CloudWatch alarms on every DLQ depth > 0

## Key Files

- `docs/plan/BUILD-PLAN.md` — phased build steps with `- [ ]` / `- [x]` checkboxes
- `docs/plan/ARCHITECTURE.md` — reference architecture (sections 1–10)
- `docs/API_AUDIT.md` — outstanding API gaps from 2026-04-17 audit
- `docs/spring-profiles.md` — two-profile convention (local / production)
- `docs/developer-setup.md` — new developer onboarding guide
