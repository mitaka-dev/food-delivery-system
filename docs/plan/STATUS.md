# Project Status

**Last updated:** 2026-05-28

## Where We Are

- **Progress:** 25 / 97 steps complete
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

- **Done:** 3.1 (product-service Aurora wiring: `application-production.yml` with HikariCP tuning, `V1__create_products.sql` Flyway migration, `FlywayConfig` bean matching user-service pattern, `ProductRepositoryIT` with Testcontainers PostgreSQL — 2/2 tests pass: CRUD roundtrip + OLE on concurrent stock update. Also fixed root pom.xml Surefire config to include `*IT.java` tests platform-wide.), 3.2 (`ProductCacheConfig` with `@EnableCaching` + `RedisCacheManager` — key prefix `product:v1:`, TTL 30 min, JSON serialization; `@Cacheable` on `getProduct`, `@CacheEvict(beforeInvocation=true)` on `reserveStock`/`releaseStock`; `GET /api/v1/products/search?q=` LIKE endpoint; `?nocache=true` ADMIN bypass on `GET /{id}`; `ProductServiceCacheIT` with Testcontainers Redis — 3/3 tests pass: cache hit, eviction, post-eviction DB fetch. 6/6 product-service tests pass total.), 3.3 (`PATCH /api/v1/products/{id}` with `@CacheEvict(beforeInvocation=true)` + partial-update semantics; `POST /api/v1/products/{id}/image-upload-url` returns pre-signed S3 PUT URL (content-addressed key `products/{id}/{sha256}.jpg`, 5-min TTL); `V2__add_image_s3_key.sql` migration; `ImageUploadService` + `S3Config` with `@ConditionalOnProperty`; `ProductResponseDto` extended with `imageUrl` (CloudFront); `ProductControllerIT` with `@MockitoBean ImageUploadService` + Testcontainers — 4/4 tests pass: price update cache eviction, 403 on non-ADMIN, presigned URL, 403 on non-ADMIN. 10/10 product-service tests pass total.), 3.4 (`common-libs/src/main/proto/product.proto` — `VerifyProduct` RPC; `protobuf-maven-plugin` + `os-maven-plugin` added to common-libs; `grpc-bom:1.71.0` added to root pom dependencyManagement; `ProductGrpcService` extends generated base, programmatic Resilience4j rate limiter 1000 req/s; `GrpcServerConfig` SmartLifecycle on port 9090 with `@ConditionalOnProperty`; `ProductGrpcServiceTest` with in-process gRPC + Testcontainers — 4/4 tests pass: in-stock, out-of-stock, not-found, p99<50ms. 14/14 product-service tests pass total.)
- **Next:** 3.5 — RESTAURANT_PAUSED listener + manifests + deployment

## Key Files

- `docs/plan/BUILD-PLAN.md` — phased steps with `- [ ]` / `- [x]` checkboxes
- `docs/plan/DEFERRED.md` — deferred items and known gaps
- `docs/plan/ARCHITECTURE.md` — reference architecture
- `docs/API_AUDIT.md` — outstanding API hardening gaps
