# Project Status

**Last updated:** 2026-05-22

## Where We Are

- **Progress:** 14 / 97 steps complete
- **Active branch:** `build/phase-1`
- **Environment:** Single env — `platform-infra/envs/production/` only.
- **Repo layout:** Services under `services/`. GitOps repo at `../food-delivery-gitops/`.

## Phase 0 — Complete

All infrastructure provisioned: VPC, EKS Fargate, Aurora PostgreSQL Serverless v2, ElastiCache Redis, MSK Serverless, SNS/SQS queues, ECR repos, CodeArtifact, API Gateway + WAF, ArgoCD with Cognito SSO. See `BUILD-PLAN.md` Steps 0.1–0.11 for details.

## Phase 1 — In Progress

- **Done:** 1.1 (root reactor + platform-bom), 1.2 (events, DTOs, exceptions in `common-libs`), 1.3 (resilience package — Resilience4j + `@Idempotent`), 1.4 (observability + outbox via Spring Modulith)
- **Next:** 1.5 — first service skeleton (user-service)

## Key Files

- `docs/plan/BUILD-PLAN.md` — phased steps with `- [ ]` / `- [x]` checkboxes
- `docs/plan/DEFERRED.md` — deferred items and known gaps
- `docs/plan/ARCHITECTURE.md` — reference architecture
- `docs/API_AUDIT.md` — outstanding API hardening gaps
