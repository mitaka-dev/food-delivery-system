# Migration Plan — Food Ordering System → New Architecture

## Context

The existing system is a working 7-service Spring Boot 3.5 / Java 25 monolith-of-services with a Docker Compose local stack. The goal is to migrate it toward the production-grade architecture documented in `docs/plan/` — progressively, not a big-bang rewrite. This plan covers the first concrete migration wave.

**Fixed decisions:**
- Existing service names unchanged (`user-service`, `product-service`, `analytics-service`, etc.)
- `common-libs` stays as `common-libs` (plan docs will be updated to match)
- `gateway-service` removed — replaced by AWS API Gateway (Terraform, future phase)
- All 6 new service skeletons created
- Spring Cloud dependency removed from parent POM (was only used by gateway-service)
- Hooks and skills created via the skill-creator plugin (not manually)

---

## Phase 1 — Spring Boot 4.0 Upgrade

**Goal:** Upgrade all remaining services (user-service, order-service, product-service, payment-service, analytics-service, common-libs) from Spring Boot 3.5.x to Spring Boot 4.0.x and fix all resulting compilation/test failures.

**Critical files:** `pom.xml` (parent), each service's `pom.xml`

### 1.1 — Update parent POM versions

Update `pom.xml` (root):
- `spring-boot-starter-parent`: `3.5.11` → latest stable `4.0.x`
- Remove `spring-cloud-dependencies` BOM import entirely (gateway-service is being removed; no remaining service uses Spring Cloud)
- `springdoc-openapi-starter-webmvc-ui`: `2.8.3` → latest SpringDoc 3.x (Boot 4 compatible)
- Verify Loki4j (`loki-logback-appender`) has a Boot 4-compatible release; update or replace with a Boot 4 equivalent if not
- All other dependencies (Kafka, JPA, Security, Actuator, Redis) are managed by the Boot BOM — they upgrade automatically

**Verify:** `./mvnw dependency:tree -q` compiles without version conflict warnings.

### 1.2 — Fix Spring Security 7 breaking changes

Spring Security 7 (bundled with Spring Framework 7 / Boot 4) has API changes:
- `SecurityFilterChain` lambda DSL is now the only form (was already recommended in Boot 3)
- `HttpSecurity.csrf()` and `HttpSecurity.cors()` API changes — update any deprecated lambda patterns
- `AuthorizationManager` replaces deprecated `AccessDecisionManager` if used
- `@EnableWebSecurity` semantics unchanged but verify no deprecated imports

**Affected files:** `user-service` security config, `gateway-service` security config (being removed anyway)

**Verify:** `./mvnw test -pl user-service` passes.

### 1.3 — Fix Spring Framework 7 breaking changes (other services)

Spring Framework 7 / Jakarta EE 11 changes:
- All services already use `jakarta.*` namespace (migrated in Boot 3), so no namespace changes expected
- Servlet 6.1: verify no code references deprecated Servlet 5/6.0 APIs
- Spring Data: verify repository method signatures and `@Query` annotations compile correctly
- Spring Kafka: managed by BOM, verify no `@KafkaListener` API breakage
- OpenTelemetry / Micrometer: verify actuator auto-configuration still works

**Verify:** `./mvnw test` across all services.

### 1.4 — Full test suite green

Run `./mvnw test` from repo root. All unit tests must pass before proceeding.

**Acceptance criteria:**
- `./mvnw test` exits 0
- No `WARN` lines about deprecated API usage in build output
- `./start.sh` launches successfully with the upgraded JARs

---

## Phase 2 — Remove gateway-service

**Goal:** Remove the Spring Cloud Gateway service. For local development, services are accessed directly by port. AWS API Gateway will handle routing in production (Terraform, future phase).

**Critical files:** `pom.xml` (parent modules list), `docker-compose.yml`, `docs/PROJECT.md`

### 2.1 — Remove gateway-service module

- Delete the `<module>gateway-service</module>` entry from root `pom.xml`
- Delete the `gateway-service/` directory (or archive it in a `_retired/` folder if you want a reference)
- Remove the `gateway-service` container block from `docker-compose.yml`

### 2.2 — Update local development access pattern

Services are now accessed directly:

| Service | Port |
|---|---|
| user-service | 8081 |
| order-service | 8083 |
| product-service | 8085 |
| payment-service | 8084 |
| analytics-service | 8082 |

Update `docs/PROJECT.md` and `CLAUDE.md` to document this. Add a note: "In production, all traffic routes through AWS API Gateway (see `docs/plan/architecture.md` Section 1 and Terraform Phase 0)."

### 2.3 — Update start.sh health checks

`start.sh` currently health-checks the gateway. Update it to health-check each service directly by port.

**Verify:** `./start.sh` completes with all remaining services healthy. No gateway references remain.

---

## Phase 3 — Plan Document Updates

**Goal:** Align the docs/plan/ documents with the actual project structure (common-libs naming, current service names).

### 3.1 — Update naming in plan documents

In `docs/plan/architecture.md`, `docs/plan/build-plan.md`, `docs/plan/common-conventions.md`:
- Replace all references to `platform-shared-libs` with `common-libs` where they refer to the shared module
- Replace all references to `identity-service` with `user-service`, `menu-service` with `product-service`, etc. where they refer to the existing services (new services keep their new names)
- Note: new services (basket, kitchen, delivery, review, notification, promotion) keep the names from the plan

### 3.2 — Update docs/PLAN.md

- Mark Spring Boot 4 upgrade complete (after Phase 1)
- Mark gateway-service removal complete (after Phase 2)
- Add new service skeletons as "in progress"
- Add a cross-reference to `docs/plan/` as the forward-looking architecture

---

## Phase 4 — New Service Skeletons

**Goal:** Create the 6 new services as proper Spring Boot 4 Maven modules with the standard package layout from `docs/plan/common-conventions.md`. Each is a compilable, runnable skeleton — no business logic yet.

**Package layout per service** (from `common-conventions.md`):
```
com.food.ordering.system.{service}/
├── {Service}Application.java
├── api/                   ← controllers + DTOs (records)
├── service/               ← business logic
├── domain/                ← entities, repositories
├── client/                ← outbound HTTP/gRPC clients
├── listener/              ← Kafka/SQS consumers
├── config/
└── security/
```

**Common skeleton requirements per service:**
- `pom.xml` with parent reference, no version overrides (BOM owns versions)
- `Application.java` main class
- `application.yml` (port, DB/Kafka placeholders, actuator endpoints)
- `Dockerfile` (eclipse-temurin:25-jre-alpine, non-root user, HEALTHCHECK)
- Entry in root `pom.xml` modules
- Entry in `docker-compose.yml` (commented-out by default — not running until implemented)

### 4.1 — basket-service (Port: 8086)

Primary store: Redis. Cart hashed by userId. 24h TTL.
- Depends on: common-libs, spring-boot-starter-web, spring-data-redis, spring-boot-starter-actuator
- No DB (Redis is primary store)
- Kafka client for publishing checkout events (placeholder only)

### 4.2 — kitchen-service (Port: 8087)

Primary store: DynamoDB (tickets table). Capacity counters via atomic DDB updates.
- Depends on: common-libs, spring-boot-starter-web, aws-sdk-v2 DynamoDB client, spring-boot-starter-actuator
- Kafka consumer placeholder (for ORDER_PAID events)
- No Postgres

### 4.3 — delivery-service (Port: 8088)

Primary store: PostgreSQL (delivery_db). Driver task state machine.
- Depends on: common-libs, spring-boot-starter-web, spring-data-jpa, postgresql driver, spring-boot-starter-actuator
- Flyway migration placeholder
- Kafka consumer placeholder

### 4.4 — review-service (Port: 8089)

Primary store: DynamoDB (reviews table). Flexible schema for multi-entity reviews.
- Depends on: common-libs, spring-boot-starter-web, aws-sdk-v2 DynamoDB client, spring-boot-starter-actuator
- Kafka consumer placeholder (for ORDER_DELIVERED events)

### 4.5 — promotion-service (Port: 8090)

Primary store: PostgreSQL (promotion_db). Promo codes and loyalty state.
- Depends on: common-libs, spring-boot-starter-web, spring-data-jpa, postgresql driver, spring-boot-starter-actuator
- Kafka consumer placeholder (for USER_CREATED events)
- Flyway migration placeholder

### 4.6 — notification-service (Port: 8091 for local; Lambda in production)

For now: plain Spring Boot app with a Kafka listener skeleton. The Lambda packaging (SAM template, AWS Lambda handler) is a future phase concern.
- Depends on: common-libs, spring-boot-starter, spring-kafka, spring-boot-starter-actuator
- No HTTP endpoints (listener-only service)
- Note in README: "This service will be re-packaged as an AWS Lambda in Phase 3 of the build plan"

### 4.7 — Add AWS SDK v2 BOM to parent POM

The new DynamoDB-backed services need AWS SDK v2. Add `software.amazon.awssdk:bom` to the parent POM `<dependencyManagement>` section so kitchen-service and review-service can declare AWS deps without versions.

**Verify:** `./mvnw compile` succeeds for all 6 new modules (no business logic errors, just skeleton compiles).

---

## Phase 5 — Hooks and Skills (via skill-creator plugin)

**Goal:** Set up the Claude Code automation layer from `docs/plan/hook-specs.md` and `docs/plan/skill-prompts.md` using the skill-creator plugin. No manual file creation.

**Note:** Use the skill-creator plugin for all skills. Use the update-config skill (or manually edit `.claude/settings.json`) for hooks.

### 5.1 — Day-1 hooks (highest-leverage, implement first)

From `docs/plan/hook-specs.md`:
1. **protect-production** (Pre-tool-use) — blocks dangerous AWS commands unless production is confirmed
2. **enforce-conventions** (Pre-commit) — catches `latest` Docker tags, hardcoded secrets, service POM version declarations
3. **secret-scanner** (Pre-commit) — runs `gitleaks protect --staged`
4. **validate-build-step-completion** (Pre-commit) — validates step criteria when `build-plan.md` checkbox flips

### 5.2 — Week-1 hooks

From `docs/plan/hook-specs.md`:
5. **flyway-migration-safety** (Pre-commit) — blocks dangerous migration patterns
6. **iam-policy-overbroad** (Pre-commit) — flags wildcard IAM policies in Terraform
7. **confirm-data-mutation** (Pre-tool-use) — prompts before DROP TABLE / TRUNCATE etc.
8. **block-cross-account-mistake** (Pre-tool-use) — blocks `terraform apply` when env/account mismatch

### 5.3 — Tier 1 skills (create via skill-creator plugin)

From `docs/plan/skill-prompts.md` Tier 1:
1. **spring-boot-service-conventions** — how every Spring Boot service is structured in this project
2. **outbox-pattern** — outbox + saga implementation guide
3. **terraform-module-conventions** — Terraform module structure for AWS resources
4. **aws-sdk-v2-conventions** — AWS SDK v2 client patterns (DynamoDB, SQS, SNS, S3)
5. **monorepo-maven-conventions** — BOM, parent POM, module conventions

### 5.4 — Wire hooks into .claude/settings.json

Update `.claude/settings.json` with the hooks configuration from `docs/plan/hook-specs.md` (the configuration block at the bottom of that file). Adapt paths to match the actual `.claude/hooks/` directory structure.

---

## Verification Checkpoints

| After phase | Command | Expected result |
|---|---|---|
| Phase 1 | `./mvnw test` | All tests green, 0 failures |
| Phase 2 | `./start.sh` | All services healthy, no gateway |
| Phase 4 | `./mvnw compile` | All 13 modules compile |
| Phase 4 | `./mvnw test` | No regressions from new module additions |
| Phase 5 | `git commit --dry-run` on a test change | Hooks fire correctly |

---

## Out of Scope (Future Phases)

- AWS infrastructure (Terraform, EKS, RDS Aurora, MSK, DynamoDB tables) — Phase 0 of build plan
- Business logic for new services — Phases 2–11 of build plan
- CI/CD pipeline (CodePipeline, ArgoCD) — Phase 13 of build plan
- gRPC between services (Basket→Menu, Order→Promotion) — Phase 6 and 8 of build plan
- Outbox pattern implementation in new services — Phase 2 of build plan
- Spring Cloud replacement for new Spring Boot 4 features (if any) — assessed during Phase 1
