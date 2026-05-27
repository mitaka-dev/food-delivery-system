# Project Status

**Last updated:** 2026-05-27

## Where We Are

- **Progress:** 22 / 97 steps complete
- **Active branch:** `build/phase-1`
- **Environment:** Single env — `platform-infra/envs/production/` only.
- **Repo layout:** Services under `services/`. GitOps repo at `../food-delivery-gitops/`.

## Phase 0 — Complete

All infrastructure provisioned: VPC, EKS Fargate, Aurora PostgreSQL Serverless v2, ElastiCache Redis, MSK Serverless, SNS/SQS queues, ECR repos, CodeArtifact, API Gateway + WAF, ArgoCD with Cognito SSO. See `BUILD-PLAN.md` Steps 0.1–0.11 for details.

## Phase 1 — Complete

- **Done:** 1.1 (root reactor + platform-bom), 1.2 (events, DTOs, exceptions in `common-libs`), 1.3 (resilience package — Resilience4j + `@Idempotent`), 1.4 (observability + outbox via Spring Modulith)

## Phase 2 — Complete

- **Done:** 2.1 (user-service skeleton: Flyway migrations V1–V3, HikariCP, profile-split YAMLs, Corretto Dockerfile, FlywayConfig for Boot 4.0, `GET /actuator/health` UP), 2.2 (`POST /api/v1/auth/register`, transactional outbox via Spring Modulith `event_publication` + `UserEventPublisher`, UUID v7, 30/30 tests pass), 2.3 (RS256 JWT issuance, DB-backed hashed refresh tokens, family invalidation, brute-force lockout, jti denylist on logout, 38/38 tests pass), 2.4 (`GET /api/v1/users/me` + `PATCH /api/v1/users/me`, `@EnableMethodSecurity` + `@PreAuthorize`, RFC 6750 `WWW-Authenticate` on 401, V6 phone migration, 8/8 IT tests pass), 2.5 (IRSA role + SM secrets in Terraform, Kustomize base+overlay, ArgoCD Application — all 9 K8s resources render cleanly, `terraform validate` passes; end-to-end deploy requires live cluster), 2.6 (`GlobalExceptionHandler` — all error paths return `ApiError` envelope; `EmailAlreadyTakenException` + `UserNotFoundException` typed exceptions; `AccountLockedException`/`InvalidTokenException` moved from controller-local to global; 7/7 `GlobalExceptionHandlerIT` tests pass), 2.7 (`docs/service-deploy-template.md` created — six-phase deploy guide covering IRSA, Secrets Manager, Spring Boot production profile, CI/CD buildspec, Kustomize layout, ArgoCD app-of-apps registration, verification checklist, and FAQ with 6 user-service lessons)

## Phase 3 — In Progress

- **Done:** 3.1 (product-service Aurora wiring: `application-production.yml` with HikariCP tuning, `V1__create_products.sql` Flyway migration, `FlywayConfig` bean matching user-service pattern, `ProductRepositoryIT` with Testcontainers PostgreSQL — 2/2 tests pass: CRUD roundtrip + OLE on concurrent stock update. Also fixed root pom.xml Surefire config to include `*IT.java` tests platform-wide.)
- **Next:** 3.2 — caching layer + search endpoint

## Key Files

- `docs/plan/BUILD-PLAN.md` — phased steps with `- [ ]` / `- [x]` checkboxes
- `docs/plan/DEFERRED.md` — deferred items and known gaps
- `docs/plan/ARCHITECTURE.md` — reference architecture
- `docs/API_AUDIT.md` — outstanding API hardening gaps
