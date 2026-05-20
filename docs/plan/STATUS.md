# Project Status

**Last updated:** 2026-05-20

## Current Situation

The local development system is complete. Steps 0.1 and 0.2 of the AWS build plan are done — the monorepo is reorganised and the VPC is live in `eu-west-1`. Step 0.3 (EKS on Fargate) is next.

## Where We Are

- **Local system:** Complete. All services built and working, reorganised under `services/`.
- **AWS build plan:** In progress — 2/98 steps complete (Steps 0.1, 0.2).
- **AWS account:** Dedicated `food-delivery-dev` account created; credentials configured. See `docs/aws-account-setup.md`.
- **Repo layout:** This repo IS `food-delivery-platform`. Services under `services/`. `food-delivery-gitops` at `../food-delivery-gitops/`.
- **Active branch:** `build/phase-0` (pushed to `origin/build/phase-0`).
- **API Hardening:** Gaps identified in `docs/API_AUDIT.md` — remediation integrated into the AWS build plan (Steps 2.6, 6.5, 8.12, 11.5, 14.0).

## Step 0.1 — Completed (2026-05-20)

- All 11 services moved under `services/`
- Maven `relativePath` fixed in all service POMs (`../../pom.xml`)
- `docker-compose.yml` build contexts and `SERVICE_PATH` args updated; observability image tags pinned
- Added: `.tool-versions`, `.terraform-version`, `.envrc.template`, `scripts/bootstrap-dev.sh`, `docs/spring-profiles.md`, `docs/developer-setup.md`
- Empty dirs created: `platform-bom/`, `platform-infra/`, `e2e-tests/`, `dev/seed/`
- `food-delivery-gitops` repo initialized at `../food-delivery-gitops/`

## Step 0.2 — Completed (2026-05-20)

- VPC module at `platform-infra/modules/vpc/` (main.tf, variables.tf, outputs.tf)
- Staging env: `platform-infra/envs/staging/` — 2 AZs, single NAT GW, `10.0.0.0/16`
- Production env: `platform-infra/envs/production/` — 2 AZs, 2 NAT GWs, `10.0.0.0/16`
- `terraform apply` succeeded — 38 resources created in `eu-west-1`
- VPC endpoints: S3 + DynamoDB (gateway), ECR/API/DKR, Secrets Manager, SNS, SQS, STS, Logs (interface)
- Flow logs → CloudWatch; `.tool-versions` updated to `temurin-25.0.3+9.0.LTS` (corretto.aws DNS issue)

## Next Step

**Step 0.3** — Terraform: EKS cluster on Fargate (`platform-infra/modules/eks/`, `platform-infra/envs/staging/eks.tf`, `platform-infra/envs/production/eks.tf`).

## Key Files

- `docs/plan/BUILD-PLAN.md` — phased build steps with `- [ ]` / `- [x]` checkboxes (98 steps)
- `docs/plan/ARCHITECTURE.md` — reference architecture (sections 1–10)
- `docs/API_AUDIT.md` — outstanding API gaps from 2026-04-17 audit
- `docs/spring-profiles.md` — three-profile convention (local / staging / production)
- `docs/developer-setup.md` — new developer onboarding guide
