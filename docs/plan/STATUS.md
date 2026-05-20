# Project Status

**Last updated:** 2026-05-20

## Current Situation

The local development system is complete. Steps 0.1–0.4 of the AWS build plan are done — the monorepo is organised, VPC is provisioned, EKS cluster is running, and Aurora PostgreSQL cluster is live. The plan has been simplified to a **single environment** (`production` only — no staging). Step 0.6 (ElastiCache Redis) is next.

## Where We Are

- **Local system:** Complete. All services built and working, reorganised under `services/`.
- **AWS build plan:** In progress — 4/97 steps complete (Steps 0.1–0.4).
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

## Next Step

**Step 0.6** — Terraform: ElastiCache Redis cluster (`platform-infra/modules/elasticache-redis/`, `platform-infra/envs/production/cache.tf`).

## Key Files

- `docs/plan/BUILD-PLAN.md` — phased build steps with `- [ ]` / `- [x]` checkboxes
- `docs/plan/ARCHITECTURE.md` — reference architecture (sections 1–10)
- `docs/API_AUDIT.md` — outstanding API gaps from 2026-04-17 audit
- `docs/spring-profiles.md` — two-profile convention (local / production)
- `docs/developer-setup.md` — new developer onboarding guide
