# Food Delivery System — Build Plan for Claude Code

> **Purpose**: A step-by-step build guide for Claude Code (Claude Pro) to construct a production-grade food ordering microservices platform on AWS. Each build step is sized to fit in a single Claude Pro session.
>
> **Companion document**: `architecture.md` contains the architectural reference (sections 1–10) — the *what* and *why* behind the choices below. Read it once before starting; re-consult it when a step references a specific section. This file (`build-plan.md`) is the *how* — the action plan you work through one step at a time.

---

## How To Use This Plan

1. **`architecture.md`** (sections 1–10) is the reference architecture. Read it once before starting. Re-reference specific sections from individual steps.
2. **This file (`build-plan.md`)** is the build plan itself, organized into phases. Phases run in order; steps within a phase can sometimes run in parallel (each step lists its dependencies).
3. **One step = one Claude Code session.** Open a fresh session, paste the step's full text as your prompt, and let Claude Code work through it. Do not combine steps.
4. **Mark progress** by replacing `- [ ]` with `- [x]` after a step's acceptance criteria are met.
5. **If a session runs out of tokens mid-step**, save what you have, start a new session, and continue with the same step — do not skip ahead.
6. **Tech stack** (fixed across all steps):
   - **Java 25 (LTS)** + **Spring Framework 7** + **Spring Boot 4.0.x**
   - Maven (multi-module monorepo) with a platform BOM ("Bill of Materials") for unified versioning
   - **AWS CodeArtifact** for hosting the platform BOM, shared libraries, and as a Maven Central proxy/cache
   - PostgreSQL 16 (Aurora Serverless v2), DynamoDB on-demand, Redis 7 (ElastiCache Cluster Mode)
   - **Amazon MSK (Managed Kafka)** for the domain event backbone where replay matters
   - SNS + SQS for Lambda triggers, webhook intake, and simple fan-out where Kafka is overkill
   - gRPC for low-latency internal synchronous calls (Basket → Menu, Order → Promotion)
   - EKS Fargate (1.30+) for services, AWS Lambda for Notification
   - Terraform 1.7+ for infrastructure as code
   - ArgoCD 2.10+ on EKS for GitOps deployment (see "Why ArgoCD" below)
   - AWS CodePipeline + CodeBuild + CodeCommit for CI/CD

### Why Java 25 + Spring Boot 4

Java 25 is the September 2025 LTS release. Combined with Spring Boot 4 (released November 2025, built on Spring Framework 7), this stack gives you:

- **Virtual threads (finalized in Java 21, mature in 25)** — perfect for our SQS/Kafka listener pools and outbox publishers, which are mostly waiting on I/O. Replaces our need for hand-tuned thread pools.
- **First-class Java 25 support in Boot 4** — released specifically aligned to this LTS.
- **Modular Spring Boot codebase** — smaller fat-jars, faster cold starts.
- **JSpecify null-safety annotations** replacing the older `@Nullable` patchwork — much better static analysis.
- **Native API versioning** via enhanced `@HttpExchange` and `RestClient` — useful for our `/v1/...` endpoints.
- **Caveat**: Spring Boot 4 requires Servlet 6.1 (Jakarta EE 11). Stick with embedded Tomcat or Jetty (Undertow is not yet compatible).

Every service in this plan uses `spring-boot-starter-parent:4.0.x`, `<java.version>25</java.version>`, and prefers virtual threads where I/O is the bottleneck.

### Why two repositories (not one, not thirteen)

This plan uses a **monorepo for code + infrastructure** plus a **separate GitOps repo** for Kubernetes manifests. Two repos total:

```
food-delivery-platform        ← all code, shared libs, infra (Terraform), e2e tests
food-delivery-gitops          ← K8s manifests for ArgoCD to reconcile
```

**Why monorepo for the code:**
- 10 services tightly coupled by a saga + shared event schemas — atomic refactors across services in one PR.
- Single team / solo developer — no team-boundary problem that polyrepo solves.
- One IDE workspace shows the full system; cross-service navigation is instant.
- BOM lives next to the code that uses it; bumping Spring Boot is one PR, not 10.
- Maven multi-module with a parent POM gives you reactor-aware builds (`mvn -pl services/order-service -am` rebuilds only affected modules).

**Why a separate GitOps repo:**
- ArgoCD watches the gitops repo continuously. You don't want every code commit churning ArgoCD's reconciliation loop.
- Different access control: developers can merge service code freely, but production-manifest edits go through stricter review.
- The "image tag bump" commit (post-CI artifact) belongs in its own history, not mixed with feature commits.

**CI/CD doesn't get more complex.** Each service still has its own CodePipeline; pipelines use **path-filtered EventBridge triggers** so a push to `services/order-service/**` only triggers `order-pipeline`. A push to `platform-shared-libs/**` or `platform-bom/**` triggers all 10 service pipelines (rebuild everything that consumed the changed lib). One pipeline per service, just pointed at a single repo with a path filter — same fan-out as polyrepo.

The single-repo monorepo layout is documented in detail below in **Phase 0 Step 0.1** and **Phase 1**.

### Why ArgoCD

ArgoCD is a **GitOps controller** for Kubernetes. It runs *inside* the EKS cluster, watches the `food-delivery-gitops` repo, and continuously reconciles the cluster's actual state to match what's declared in Git. Three properties make it the right choice for this platform:

1. **Git is the source of truth.** Every production change is a Git commit. The full audit trail and rollback story comes for free — `git revert` undoes a deploy.
2. **Pull-based deploys.** The cluster pulls from Git; CI never holds Kubernetes credentials. Better security posture than push-based deploys (`kubectl apply` from a CI job).
3. **Continuous reconciliation + drift detection.** If someone hand-edits a deployment in production via `kubectl`, ArgoCD detects the drift and either alerts or auto-corrects. The cluster cannot drift from declared state for long.

**Additional benefits used in this plan:**
- **App-of-Apps pattern** — one root ArgoCD `Application` declares child Applications for each service, so adding a new service is one PR to the gitops repo.
- **Argo Rollouts** (companion project) handles canary deploys: 10% → 50% → 100% with automated rollback on SLO breach. Used in Phase 8 (CI/CD).
- **Sync waves** — ArgoCD applies resources in declared order, so namespaces and CRDs land before Deployments.
- **SSO via OIDC** — Cognito or Okta integration for the ArgoCD UI, no shared admin password.
- **Notifications** — ArgoCD's Notifications service sends sync events to Slack and PagerDuty (Phase 8.6).

ArgoCD is open source (CNCF graduated) and runs perfectly fine on EKS via Helm. There's no equivalent fully-AWS-native GitOps controller — AWS CodeDeploy can deploy to EKS but it's push-based and lacks reconciliation. We use AWS for everything else; ArgoCD is the one pragmatic exception.

### What is AWS CodeArtifact?

CodeArtifact is AWS's managed artifact repository — equivalent to JFrog Artifactory or Sonatype Nexus, but serverless and AWS-native. It supports Maven, npm, NuGet, PyPI, and generic packages. In this plan it serves three purposes:

1. **Hosts the platform BOM and shared libraries.** When a CI build publishes `platform-bom:1.5.0` or `common-events:2.1.0`, it goes to CodeArtifact. Service builds resolve them from there.
2. **Caches Maven Central artifacts.** Instead of every CodeBuild job hitting Maven Central directly, CodeArtifact proxies it. Faster, more reliable, and reproducible (you can pin to a specific snapshot of upstream).
3. **Authentication via IAM.** No separate credentials to manage — CodeBuild jobs and developer machines both auth via `aws codeartifact login --tool maven --domain {org}-platform --repository internal`.

Cost is minimal — pennies per GB stored plus per-request fees. Set up once in Phase 0, used by every subsequent build.


---

## Build Strategy

> Read this section before starting Phase 0. It explains how the 98 build steps are sequenced and a few cross-cutting decisions that apply to every phase. The plan below makes more sense once you've absorbed these.

### Spring profile strategy (two profiles plus one edge case)

Every service runs under one of two Spring profiles. The profile determines the **shape** of dependencies — what beans load, whether IAM auth is enabled, whether TLS is required. The profile does **NOT** carry environment-specific values like hostnames, passwords, or topic names. Those come from environment variables, populated by Kubernetes from ConfigMaps and Secrets (via External Secrets Operator pulling from AWS Secrets Manager) in production, and from `.env` files or `application-local.yml` placeholders during local development.

The two profiles:

- **`local`** — JVM runs on a developer laptop. Dependencies are Docker Compose containers: local PostgreSQL on `localhost:5432`, local Redis with no TLS, local Kafka with `PLAINTEXT` (no IAM auth), LocalStack for AWS APIs. This is the tight dev loop.
- **`production`** — Service runs on EKS. Talks to real AWS resources: Aurora PostgreSQL cluster, ElastiCache Redis cluster with IAM auth + TLS, MSK cluster with IAM auth + TLS, real DynamoDB, real SNS/SQS/S3. No LocalStack.

Plus one edge-case profile, used sparingly:

- **`local-aws`** — JVM runs on a developer laptop, but AWS SDK calls hit **real AWS** instead of LocalStack. For occasional debugging — usually when something reproduces against real DynamoDB or real MSK but not against LocalStack. Activated explicitly via `-Dspring.profiles.active=local-aws`; not the default for any developer.

**The rule**: profile controls shape, env vars provide values.

Concrete example. `application-local.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/identity
    username: dev
    password: dev
  kafka:
    bootstrap-servers: localhost:9092
    properties:
      security.protocol: PLAINTEXT
      sasl.mechanism: ""
aws:
  endpoint-override: http://localhost:4566   # LocalStack
  credentials:
    access-key-id: dev
    secret-access-key: dev
```

`application-production.yml`:

```yaml
spring:
  datasource:
    url: ${DB_URL}                  # injected by K8s from Secret
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
    properties:
      security.protocol: SASL_SSL
      sasl.mechanism: AWS_MSK_IAM
# no aws.endpoint-override — SDK hits real AWS
```

Same code path runs in both. The profile shapes the auth/TLS/endpoint differences; env vars deliver the values.

### Pilot-first sequencing — user-service before the rest

The build does NOT take all 10 services through each phase in parallel. Instead, **user-service goes end-to-end first**, including its CI/CD pipeline and its observability dashboard. This is the pilot. Once user-service is fully running on EKS with all its supporting infrastructure, you pause and capture what you learned. Then services 2–10 follow the template.

The pilot covers, in order:

1. Phase 0 (foundation IaC, complete)
2. Phase 1 (shared libs + BOM, complete)
3. Phase 2 (user-service implementation through K8s deploy to production)
4. **From Phase 7**: Step 7.1 (Managed Prometheus + Grafana backend) and a user-service dashboard
5. **From Phase 8**: Steps 8.1, 8.2, 8.3 (path-filter Lambda, buildspec templates, user-service pipeline)
6. **Checkpoint**: write `docs/service-deploy-template.md` capturing the IRSA setup, Kustomize overlay structure, ServiceAccount/ExternalSecret/ServiceMonitor patterns, and the buildspec wiring that worked. This becomes the template for services 2–10.

The reason for this sequencing: the first service surfaces problems that the other nine will then dodge cheaply. IRSA is fiddly the first time. Kustomize overlay structure crystallizes during user-service and becomes a copy-paste template afterward. The first ServiceMonitor and first dashboard establish conventions. Get them right once on the pilot; replicate cleanly on services 2–10.

**Practical impact on Phase 7 and Phase 8**: Phase 7's Step 7.1 and Phase 8's Steps 8.1–8.3 are moved into the pilot work (executed during the user-service phase). The original Phase 7 and Phase 8 still exist — they cover *cross-cutting* observability (X-Ray, SLO alerts) and *replicating* pipelines/dashboards to services 2–5 in v1, plus all v2/v3 additions later. References to those steps stay; only the execution order changes.

> **Step renumbering note**: an earlier revision of this plan numbered Observability as Phase 12 and CI/CD as Phase 13. Any reference to "Steps 12.1 + 13.1–13.3 pulled forward" in older notes should be read as "Steps 7.1 + 8.1–8.3 pulled forward."

### Expect the first service to be harder than the rest

Three things will be harder on user-service than on any subsequent service. Knowing this in advance prevents the "I must be doing something wrong" feeling:

- **IRSA wiring is fiddly the first time.** Five things need to align — trust policy, ServiceAccount annotation, EKS OIDC provider, pod volume mount, SDK credential chain. Get one wrong and the SDK errors are uninformative. Budget time. Subsequent services are copy-paste from the first.
- **Kustomize overlay structure crystallizes during user-service.** Decisions about what goes in `base/` vs `overlays/`, how env config is templated, how secrets reference External Secrets — all get made during user-service and stay. Spend time getting them right; capture in `docs/service-deploy-template.md`.
- **Observability scaffolding is one-time work.** First ServiceMonitor, first PrometheusRule, first Grafana dashboard. Templates emerge. Don't skip observability on the pilot — you'll lose context on what was happening in production without it.

### API audit gaps are handled inline per service

The platform's API audit (`docs/API_AUDIT.md`) identified specific gaps in services that already exist locally. Rather than treating remediation as a separate phase, each service phase that has identified gaps gets a **dedicated audit step at the end** that addresses them. Specifically:

- **Phase 2 — user-service**: audit step covers global exception handler, `@Valid` on registration, typed `RegisterResponse` with 202 status (audit §1, §5, §6 for user)
- **Phase 6 — basket-service**: audit step covers idempotent add-item via upsert-by-productId (audit §9 for basket)
- **Phase 8 — order-service**: audit step covers `@RestControllerAdvice` with `OrderNotFoundException`, `Page<OrderResponseDto>` with `Pageable` on list endpoint, filter by status/date (audit §5, §7, §9, §11 for order)
- **Phase 11 — review-service**: audit step covers unique `(orderId, userId)` constraint, pagination on `GET /reviews/orders/{orderId}` (audit §7, §9 for review)

Two pieces of cross-cutting audit infrastructure ride in Phase 1's shared libraries:

- **`ApiError` record** in `common-exceptions` — replaces the private record currently in `product-service`'s exception handler; consumed by user-service and order-service's new handlers
- **`IdempotencyKeyFilter`** in `common-resilience` — the Spring AOP aspect already planned in Step 1.3 doubles as the filter the audit recommends for order-service (and optionally basket and review)

The audit's priority order — testability → order idempotency → user validation → exception handlers → order pagination — is reflected in the sequencing. Testability is highest priority but requires services to exist, so it's addressed in Phase 9 (cross-cutting test scaffolding) plus the per-service slice tests added in each per-service audit step.

### Single environment — deploy directly to production

This plan uses a single environment (`production`). Per-service phases (2 through 6 in v1) deploy directly to the production EKS cluster. Phase 10 (Production Hardening) adds WAF, DR validation, and security hardening before opening to real customer traffic — but services are deployed and observable there from the start.

### Version strategy — v1 is the reference, v2/v3/v4 are extensions

The build is split into **four versions**, each one shippable. The point of versioning isn't to defer work — it's to demonstrate the full architecture on a small surface before adding more services. Every version is a real production deployment with the full operational stack (real EKS, real IAM, real CI/CD, real observability, real security, real resilience patterns).

| Version | Adds | Services in total | Approx duration |
|---|---|---|---|
| **v1** | The reference implementation — foundation, 5 services, mini-saga | 5 (user, product, basket, payment, order) | 6–8 weeks |
| **v2** | Restaurant operations | 7 (+ kitchen, delivery) | 3–4 weeks |
| **v3** | Engagement | 10 (+ review, promotion, notification) | 3–4 weeks |
| **v4** | Payment hardening | 10 (payment graduates to full service) | 2–3 weeks |

**v1 is the reference implementation.** It contains every architectural pattern used across the whole platform: the outbox pattern, the saga pattern (small but real), gRPC contracts, Resilience4j circuit breakers/retries/bulkheads, the two Spring profiles, JWT auth, CodePipeline + ArgoCD GitOps, Argo Rollouts canaries, Prometheus + Grafana + X-Ray observability, SLO-based alerts, WAF, DR runbooks. Someone reading the v1 codebase end-to-end should be able to learn every pattern the platform uses — without scrolling through 10 services to find each example. v1 has every pattern represented exactly once. Anything that doesn't demonstrate a distinct pattern doesn't belong in v1.

**v1's 5 services and their roles:**

- **user-service** — registration, login, JWT issuance. Outbox emits `USER_CREATED`. (Pattern shown: outbox, Spring Security, JWT auth, RDS Aurora.)
- **product-service** — product catalog with stock management. (Pattern shown: cache-aside with Redis, PostgreSQL with optimistic locking for concurrent stock updates, S3 product images, gRPC server.)
- **basket-service** — cart with upsert-by-productId. (Pattern shown: Redis as primary store, gRPC client to product-service, request idempotency keys.)
- **payment-service (minimal)** — calls Stripe test mode, records ledger entry, emits PAYMENT_SUCCESS / PAYMENT_FAILED. (Pattern shown: DDB ledger, idempotency on external API calls, outbox emitting to Kafka.)
- **order-service (mini-saga)** — 6-state state machine: PENDING → PAID → COMPLETED, plus PAYMENT_FAILED → COMPENSATING → CANCELED. One compensation action: restore the basket. (Pattern shown: saga pattern, Spring StateMachine, compensation handlers, idempotent event consumers, optimistic locking, saga timeout enforcer.)

**v1's mini-saga in detail:** the order-service drives the small but real saga. Happy path: order placed → outbox writes `CHARGE_PAYMENT` command to SQS → payment-service charges Stripe test mode → emits `PAYMENT_SUCCESS` to Kafka → order-service consumes, transitions to `PAID`, then to `COMPLETED`. Failure path: payment-service emits `PAYMENT_FAILED` → order-service transitions to `COMPENSATING`, writes `RESTORE_BASKET` command to SQS → basket-service consumes, restores cart, emits `BASKET_RESTORED` ack → order-service transitions to `CANCELED`. That's the entire saga: 6 states, 1 forward path, 1 compensation path, 1 ack. Small enough to build cleanly, real enough to demonstrate the pattern.

**v2 adds restaurant operations.** kitchen-service (restaurants accept and prepare orders) and delivery-service (drivers claim and complete deliveries). The order-service saga *expands* to include KITCHEN_ACCEPTED, FOOD_READY, OUT_FOR_DELIVERY, DELIVERED states, plus more compensation paths (cancel kitchen ticket, free driver). No new architectural patterns — just more instances of the patterns established in v1.

**v3 adds engagement features.** review-service (rate restaurants, drivers, meals), promotion-service (discount codes, loyalty), notification-service (welcome emails, receipts, push notifications — runs as Lambda). The platform becomes feature-complete for end users.

**v4 hardens payment.** The minimal payment-service from v1 gets graduated to a production-grade service: idempotency ledger with all 6 entry types, Stripe webhook handling with signature verification, full Resilience4j stack, refund flows, DDB Streams outbox publisher. The v1 minimal payment-service runs alongside during migration, then gets retired.

**Phase numbering across versions:** continuous. Phase 0–10 is v1. Phase 11–14 is v2. Phase 15–17 is v3. Phase 18–20 is v4. This makes step IDs unambiguous (no "v2 Phase 3" / "v3 Phase 3" confusion) and grep-friendly.

**Don't read v2/v3/v4 yet.** Start with v1. The later versions are short and assume you've done v1. They reference v1's conventions, skills, and patterns rather than re-explaining them. Reading v2 cold won't make sense.

---

## Table of Contents

- [Build Strategy](#build-strategy)
- **VERSION 1 — Reference implementation (5 services, mini-saga)**
  - [Phase 0: Foundation & Infrastructure](#phase-0-foundation--infrastructure)
  - [Phase 1: Shared Libraries & Platform BOM](#phase-1-shared-libraries--platform-bom)
  - [Phase 2: User Service (pilot)](#phase-2-user-service-pilot)
  - [Phase 3: Product Service](#phase-3-product-service)
  - [Phase 4: Basket Service](#phase-4-basket-service)
  - [Phase 5: Payment Service (minimal)](#phase-5-payment-service-minimal)
  - [Phase 6: Order Service (mini-saga)](#phase-6-order-service-mini-saga)
  - [Phase 7: Observability](#phase-7-observability)
  - [Phase 8: CI/CD on AWS](#phase-8-cicd-on-aws)
  - [Phase 9: End-to-End Testing](#phase-9-end-to-end-testing)
  - [Phase 10: Production Hardening](#phase-10-production-hardening)
- **VERSION 2 — Restaurant operations (+ kitchen, delivery)**
  - [Phase 11: Kitchen Service](#phase-11-kitchen-service)
  - [Phase 12: Delivery Service](#phase-12-delivery-service)
  - [Phase 13: Expand order-service saga](#phase-13-expand-order-service-saga)
  - [Phase 14: v2 wrap-up](#phase-14-v2-wrap-up)
- **VERSION 3 — Engagement (+ review, promotion, notification)**
  - [Phase 15: Review Service](#phase-15-review-service)
  - [Phase 16: Promotion Service](#phase-16-promotion-service)
  - [Phase 17: Notification Service (Lambda)](#phase-17-notification-service-lambda)
- **VERSION 4 — Payment hardening**
  - [Phase 18: payment-service v2 — full ledger + webhooks + resilience](#phase-18-payment-service-v2--full-ledger--webhooks--resilience)
  - [Phase 19: Migrate from minimal payment to hardened payment](#phase-19-migrate-from-minimal-payment-to-hardened-payment)
  - [Phase 20: v4 wrap-up](#phase-20-v4-wrap-up)
- [Estimated Total Effort](#estimated-total-effort)
- [How to Run a Session](#how-to-run-a-session)

---

# PART B — BUILD STEPS

> Each step below is sized for one Claude Pro session. Mark a step done by changing `- [ ]` to `- [x]`. Steps within a phase that have no dependency on each other can be parallelized across multiple sessions.

---

# VERSION 1 — Reference Implementation

> v1 is the foundation: AWS infrastructure plus 5 services (user, product, basket, payment, order) demonstrating every architectural pattern the platform uses. By end of v1, you have a working food-delivery platform on real AWS — customers can register, browse menus, add to cart, and place orders. The mini-saga coordinates order → payment with proper compensation on failure.
>
> v1 ends at production launch: SLOs green, security audit done, DR drill completed.
>
> Total: ~53 steps across 11 phases. Estimated 6–8 weeks for one engineer, less with parallelism.

## Phase 0: Foundation & Infrastructure

> Goal: provision all shared AWS infrastructure with Terraform before writing a single line of application code. By the end of this phase, you have a working EKS cluster with databases, queues, and ArgoCD ready to receive deployments.

### Step 0.1: Monorepo bootstrap & developer prerequisites
- [x] **Objective**: Reorganize this repo as the `food-delivery-platform` monorepo, initialize the `food-delivery-gitops` companion repo, document local developer setup, and lock in the two-profile convention (`local`, `production`).
- **Decision**: Rather than creating a new `food-delivery-platform` repo from scratch, this existing repo was reorganized to serve as `food-delivery-platform`. All existing services (already built and working locally) were moved under `services/`. Only one new repo was created: `food-delivery-gitops` (at `../food-delivery-gitops/`).
- **Files created/changed**:
  - All service dirs moved: `{service-name}/` → `services/{service-name}/`
  - Root `pom.xml` module paths updated (`services/{service-name}`)
  - Service `pom.xml` `<relativePath>` fixed: `../pom.xml` → `../../pom.xml`
  - `docker-compose.yml` build contexts and `SERVICE_PATH` args updated; observability image tags pinned
  - `start.sh` JAR path check updated
  - `.terraform-version` (1.7.5)
  - `.tool-versions` (asdf: java corretto-25, maven 3.9.9, terraform 1.7.5, kubectl 1.30.7, helm 3.15.4)
  - `.envrc.template` (direnv: AWS_PROFILE, AWS_REGION, CODEARTIFACT_AUTH_TOKEN refresh, SPRING_PROFILES_ACTIVE=local)
  - `scripts/bootstrap-dev.sh` (idempotently installs all dev tools via asdf, CodeArtifact login)
  - `docs/developer-setup.md`
  - `docs/spring-profiles.md` (two-profile convention; shape-vs-values rule)
  - Empty dirs with `.gitkeep`: `platform-bom/`, `platform-infra/`, `e2e-tests/`, `dev/seed/`
  - `../food-delivery-gitops/` repo: README, .gitignore, `apps/`, `argocd/`
- **Key details**:
  - `common-libs/` stays at repo root (correct name per plan — earlier rename to `platform-shared-libs` was reverted).
  - CodeCommit remotes wired in Phase 0.8 once AWS is provisioned. GitHub remote (`mitaka-dev/food-delivery-system`) is the current remote for this repo.
  - **Spring profile convention** (locked in here): two profiles — `local` (Docker Compose + LocalStack), `production` (real AWS). Plus `local-aws` edge case. See `docs/spring-profiles.md`.
  - Naming convention: `{org}-{env}-{service}-{resource}` for every AWS resource.
  - Tag every AWS resource with `Project=food-delivery`, `Environment=production`, `Service={service-name}`, `Owner={team}`, `CostCenter={code}`.
  - Branch protection on `main` for both repos: 1 approval required, status checks must pass, no force-pushes.
- **Acceptance criteria**: Repo reorganized with all services under `services/`. `mvn validate` passes across all modules. `docker compose up` works. `scripts/bootstrap-dev.sh` installs all tools. `docs/spring-profiles.md` documents the three profiles. `food-delivery-gitops` repo initialized.
- **Dependencies**: none

### Step 0.2: Terraform — VPC and networking
- [x] **Objective**: Provision a multi-AZ VPC with public, private, and isolated subnets.
- **Files to create**:
  - `platform-infra/modules/vpc/main.tf`
  - `platform-infra/modules/vpc/variables.tf`
  - `platform-infra/modules/vpc/outputs.tf`
  - `platform-infra/envs/production/network.tf`
- **Key details**:
  - 2 AZs in chosen region
  - CIDR layout: `10.0.0.0/16`. Public `/24`s, private `/22`s, isolated `/24`s for RDS
  - Single NAT Gateway (one AZ — acceptable for a learning project; add per-AZ NATs when HA is needed)
  - Internet Gateway for public subnets
  - VPC endpoints for S3, DynamoDB, ECR, Secrets Manager, SNS, SQS, STS, Logs (gateway + interface)
  - Flow Logs enabled, sent to CloudWatch
  - Default deny-all security group used as base
- **Acceptance criteria**: `terraform plan` produces clean diff. `terraform apply` succeeds. `aws ec2 describe-vpcs` shows the new VPC.
- **Dependencies**: 0.1

### Step 0.3: Terraform — EKS cluster on Fargate
- [x] **Objective**: Provision EKS cluster with Fargate-only profiles, IRSA, and core add-ons.
- **Files to create**:
  - `platform-infra/modules/eks/main.tf`
  - `platform-infra/modules/eks/fargate-profiles.tf`
  - `platform-infra/modules/eks/addons.tf`
  - `platform-infra/envs/production/eks.tf`
- **Key details**:
  - EKS version 1.30+
  - Fargate profiles: one per namespace (`identity`, `menu`, `basket`, etc.) plus `kube-system`
  - OIDC provider enabled for IRSA
  - Add-ons: VPC CNI, CoreDNS, kube-proxy, AWS Load Balancer Controller, Cluster Autoscaler (not needed on Fargate but useful for future), External DNS, External Secrets Operator
  - Logging: control plane logs to CloudWatch (api, audit, authenticator, controllerManager, scheduler)
  - Cluster security group restricts API access to corporate CIDR
- **Acceptance criteria**: `kubectl get nodes` returns Fargate nodes. `kubectl get pods -A` shows kube-system pods Running.
- **Dependencies**: 0.2

### Step 0.4: Terraform — RDS Aurora PostgreSQL cluster
- [x] **Objective**: Provision a shared Aurora PostgreSQL cluster used by Identity, Order, Promotion, Delivery (one DB per service in the same cluster).
- **Files to create**:
  - `platform-infra/modules/rds-aurora/main.tf`
  - `platform-infra/modules/rds-aurora/variables.tf`
  - `platform-infra/envs/production/databases.tf`
- **Key details**:
  - Aurora PostgreSQL 16, Serverless v2 with min 0.5 ACU, max 4 ACU (scales to near-zero when idle)
  - Single instance, in isolated subnets only — no public access
  - Master password in Secrets Manager with rotation Lambda
  - Per-service databases: `identity_db`, `order_db`, `promotion_db`, `delivery_db`
  - Per-service IAM users (created by separate migration step later)
  - Performance Insights enabled, 7-day retention
  - Automated backups: 7 days retention
- **Acceptance criteria**: Can `psql` from a bastion or EKS pod using credentials from Secrets Manager.
- **Dependencies**: 0.2

### Step 0.5: Terraform — DynamoDB tables *(deferred to service phases)*
- **Removed from Phase 0.** Provisioning DynamoDB tables weeks before the services that use them adds no value — same principle as MSK topics in Step 0.7 (*"provisioned when their respective phases land, not now"*). Each table is created alongside its service phase:
  - `payment-ledger` + `outbox-payment` → **Step 5.1** (payment-service skeleton)
  - `tickets` + `outbox-kitchen` → **Step 11.1** (kitchen-service)
  - `reviews` + `review-aggregates` → **Step 15.1** (review-service)
  - `notification-idempotency` → **Step 17.3** (notification-service)
- The reusable `platform-infra/modules/dynamodb-table/` Terraform module (KMS CMK per table, on-demand billing, optional streams/PITR/TTL/GSI) is created in Step 5.1 when first needed, then reused in all later phases.
- **Note**: product-service uses Aurora PostgreSQL (not DynamoDB) — no DynamoDB table is needed for products.

### Step 0.6: Terraform — ElastiCache Redis cluster
- [x] **Objective**: Provision a shared Redis cluster used by Basket (primary store), Menu (cache), and rate limiting.
- **Files to create**:
  - `platform-infra/modules/elasticache-redis/main.tf`
  - `platform-infra/envs/production/cache.tf`
- **Key details**:
  - Redis 7.x, Cluster Mode enabled (sharding for horizontal scale)
  - 2 shards, 1 replica per shard
  - In-transit encryption on (TLS), at-rest encryption on
  - Auth via Redis AUTH token in Secrets Manager
  - In private subnets, security group allows EKS pods only
  - Snapshot retention: 1 day
  - Slow log + engine log to CloudWatch
- **Acceptance criteria**: Can connect from EKS pod with `redis-cli --tls -h <endpoint> -p 6379 -a <token>`.
- **Dependencies**: 0.2

### Step 0.7: Terraform — Amazon MSK (managed Kafka) cluster
- [x] **Objective**: Provision the MSK cluster that hosts v1's domain-event backbone (`user-events`, `order-events`, `payment-events`). v2/v3 will add more topics later.
- **Files to create**:
  - `platform-infra/modules/msk/main.tf`
  - `platform-infra/modules/msk/variables.tf`
  - `platform-infra/modules/msk/topics.tf` (uses `confluentinc/confluent` provider OR a Lambda that calls `kafka-topics` after cluster ready)
  - `platform-infra/envs/production/kafka.tf`
- **Key details**:
  - MSK Serverless cluster (auto-scales, pay-per-throughput — cost-appropriate for a learning project)
  - **v1 topics** (more added in v2/v3):
    - `user-events` (3 partitions, retention 7 days, key=userId)
    - `order-events` (6 partitions, retention 14 days, key=orderId — important for per-order ordering)
    - `payment-events` (3 partitions, retention 30 days for audit, key=orderId)
  - **Future topics** (provisioned when their respective phases land, not now): `kitchen-events`, `delivery-events`, `promotion-events`, `driver-status`. Adding a topic is a Terraform change — cheap to defer.
  - **Authentication**: IAM (IRSA-friendly) — no SASL/SCRAM passwords to manage. Each service's IRSA role gets per-topic produce/consume permissions.
  - **Encryption**: in-transit (TLS 1.2+) and at-rest (KMS CMK).
  - **Schema management**: AWS Glue Schema Registry, Avro format for all topics. Producers/consumers reference schema ID, not embedded schema.
  - **Monitoring**: enhanced monitoring (`PER_TOPIC_PER_PARTITION`), broker logs to CloudWatch, JMX metrics scraped by Prometheus.
  - **Connectivity**: private endpoints only (no public bootstrap). EKS pods connect via interface VPC endpoint.
- **Acceptance criteria**: From an EKS pod with the right IRSA, can `kafka-console-producer` to `user-events` and `kafka-console-consumer` from another pod sees the message. Glue Schema Registry shows registered Avro schemas for each v1 event type.
- **Dependencies**: 0.2, 0.3

### Step 0.8: Terraform — SNS topics, SQS queues, DLQs (compensation + webhook intake)
- [x] **Objective**: Provision the SNS/SQS messaging used for compensation commands, Stripe webhook intake, and the few simple fan-out cases that don't justify a Kafka topic.
- **Files to create**:
  - `platform-infra/modules/sns-sqs-pair/main.tf` (creates topic + queue + subscription + DLQ)
  - `platform-infra/envs/production/messaging-sns-sqs.tf`
- **Key details**:
  - **v1 queues**:
    - `charge-payment` (commands from order-service → payment-service to charge a Stripe order)
    - `basket-compensation` (commands from order-service → basket-service to restore a cart on payment failure)
    - Each queue has a DLQ with `maxReceiveCount = 5`.
  - **v2/v3 additions** (provisioned later, not now): `kitchen-compensation`, `promotion-compensation`, `payment-refund`, `delivery-compensation`, plus SNS topic `stripe-webhooks` and SQS `payment-webhooks` (when v4 introduces real webhook handling), plus SNS Mobile Push platform applications (v2 delivery), plus `notification-stripe-webhooks` (v3 notification Lambda).
  - All messages KMS-encrypted with platform-wide CMK.
  - CloudWatch alarms on every DLQ depth > 0.
- **Acceptance criteria**: Can publish to `charge-payment` and `basket-compensation` via CLI and observe messages arriving in the consumer's poll. DLQ receives messages after 5 failed receives.
- **Dependencies**: 0.2

### Step 0.9: Terraform — ECR repositories, IAM roles, CodeArtifact domain
- [x] **Objective**: Create one ECR repo per v1 service plus the shared IAM roles for CodeBuild and CI processes, plus the CodeArtifact domain that hosts the BOM and shared libraries.
- **Files to create**:
  - `platform-infra/modules/ecr-repo/main.tf`
  - `platform-infra/envs/shared/ecr.tf`
  - `platform-infra/envs/shared/iam-cicd.tf`
  - `platform-infra/envs/shared/codeartifact.tf`
- **Key details**:
  - **v1 ECR repos (5)**: `user-service`, `product-service`, `basket-service`, `payment-service`, `order-service`. v2/v3 add: `kitchen-service`, `delivery-service`, `review-service`, `promotion-service`, `notification-service`.
  - Image scan on push enabled (Inspector v2)
  - Lifecycle policy: keep last 30 untagged images, keep tags `prod-*` forever
  - Image tag immutability enabled
  - **CodeArtifact domain**: `{org}-platform`. Two repos:
    - `internal` — where `platform-bom` and `platform-shared-libs/*` modules publish.
    - `maven-central` — public upstream proxy. The `internal` repo declares `maven-central` as upstream so transitive deps resolve through it.
  - CodeBuild service role with permissions for ECR push, CodeArtifact read+publish, S3 artifact bucket, **MSK produce/consume during integration tests**.
  - CodePipeline service role.
  - Shared KMS CMK for image encryption + CodeArtifact encryption.
- **Acceptance criteria**: `aws ecr describe-repositories` lists 5 v1 repos. `aws codeartifact list-repositories-in-domain --domain {org}-platform` shows both repos. CodeBuild role can be assumed.
- **Dependencies**: 0.1

### Step 0.10: Terraform — API Gateway and ALB foundation
- [x] **Objective**: Provision the public API Gateway plus internal ALBs for service ingress.
- **Files to create**:
  - `platform-infra/modules/api-gateway/main.tf`
  - `platform-infra/envs/production/api.tf`
- **Key details**:
  - HTTP API Gateway (cheaper than REST API, sufficient for our needs)
  - Lambda authorizer that validates JWT signature using Identity public key from Parameter Store
  - WAF Web ACL: AWS managed rule sets (Common, KnownBadInputs, SQLi), rate limit 2000 req/5min per IP
  - Custom domain via ACM certificate
  - Routes to internal ALBs via VPC Link (private connectivity)
  - Per-route throttling: payment endpoints stricter than menu reads
  - Access logs to CloudWatch
- **Acceptance criteria**: `curl https://api-{env}.{domain}/health` returns 200 from a placeholder Lambda.
- **Dependencies**: 0.2

### Step 0.11: ArgoCD installation and bootstrap
- [x] **Objective**: Install ArgoCD on the EKS cluster and wire it to the `food-delivery-gitops` CodeCommit repo.
- **Files to create**:
  - `platform-infra/scripts/install-argocd.sh`
  - `food-delivery-gitops/argocd/install/values.yaml`
  - `food-delivery-gitops/argocd/projects/services.yaml`
  - `food-delivery-gitops/argocd/applications/_app-of-apps.yaml`
  - `food-delivery-gitops/README.md`
- **Key details**:
  - Install ArgoCD via Helm chart, version pinned (2.10+).
  - Configure SSO via OIDC (Cognito or Okta).
  - Disable insecure admin UI; expose only via internal ALB.
  - Generate SSH key pair, store private key in Secrets Manager, public key on the IAM user used by ArgoCD to read the gitops repo.
  - **App-of-Apps pattern**: one root Application (`_app-of-apps.yaml`) declares child Applications for each service × env. Adding a new service is one PR to the gitops repo.
  - **AppProject `services`** restricts which namespaces/repos child apps can use — defense against misconfigured child app pointing at, say, `kube-system`.
  - Install **Argo Rollouts** controller alongside ArgoCD (enables canary deploys in Phase 8.4).
  - Configure **ArgoCD Notifications** controller pointing at SNS for Slack/PagerDuty fan-out (wired up fully in Phase 8.6).
- **Acceptance criteria**: ArgoCD UI accessible via internal ALB with SSO login. Root app shows 0 children initially. Adding a placeholder child app via PR to `food-delivery-gitops` causes ArgoCD to create the namespace within ~1 minute.
- **Dependencies**: 0.3, 0.9

---

## Phase 1: Shared Libraries & Platform BOM

> Goal: stand up the Maven reactor at the monorepo root, publish the platform BOM (which pins every dependency version platform-wide), and build out the shared libraries every service depends on. From here on, every service POM has *no version numbers* — they all come from the BOM.

### Step 1.1: Root reactor POM + platform-bom (Bill of Materials)
- [ ] **Objective**: Create the root `pom.xml` (Maven reactor for the entire monorepo), the `platform-bom` module that pins all dependency versions, and configure CodeArtifact publication. This is the foundation everything else inherits from.
- **Files to create**:
  - `food-delivery-platform/pom.xml` (root reactor — declares `<modules>` for `platform-bom`, `platform-shared-libs`, all services)
  - `food-delivery-platform/platform-bom/pom.xml` (BOM with `<dependencyManagement>` only, no source code)
  - `food-delivery-platform/platform-shared-libs/pom.xml` (parent for shared modules — empty `<modules>` for now, populated in 1.2–1.4)
  - `food-delivery-platform/.mvn/maven.config` (passes `-Pcoverage` etc. consistently)
  - `food-delivery-platform/.mvn/settings.xml` (template; CodeArtifact mirror config)
  - `food-delivery-platform/scripts/codeartifact-login.sh`
  - `food-delivery-platform/scripts/publish-bom.sh`
- **Key details**:
  - **BOM contents** (versions to pin centrally):
    - `spring-boot-dependencies:4.0.x` imported as a BOM
    - `spring-cloud-aws-dependencies:3.x` imported as a BOM
    - `software.amazon.awssdk:bom:2.30.x` imported as a BOM (all DDB, S3, SQS, etc. clients pinned)
    - `io.github.resilience4j:resilience4j-spring-boot4:2.4.x`
    - `io.confluent:kafka-avro-serializer:7.x`
    - `io.opentelemetry:opentelemetry-bom` and `opentelemetry-bom-alpha`
    - `io.grpc:grpc-bom`
    - `org.testcontainers:testcontainers-bom`
    - `org.flywaydb:flyway-database-postgresql:10.x`
    - All `com.{org}.platform:common-*` modules at the current platform version
  - **Java 25, Spring Boot 4** — set `<java.version>25</java.version>` and `<maven.compiler.release>25</maven.compiler.release>` in the BOM properties.
  - **Group ID**: `com.{org}.platform` for the BOM and shared libs; `com.{org}.foodordering.{service}` for service modules.
  - **Versioning**: Platform-wide rolling version `1.0.0-SNAPSHOT` for development; releases tagged as `1.0.0`, `1.1.0`, etc. — bumped together.
  - **Publication**: `mvn deploy` from CI publishes the BOM and all shared libs to CodeArtifact `internal` repo. Service builds resolve them from there (services don't publish).
  - **Reactor module list** in root POM: `platform-bom`, `platform-shared-libs/*`, `services/*`. The reactor is what makes `mvn -pl services/order-service -am verify` work — Maven knows the dependency graph and rebuilds upstream modules if needed.
  - The `.mvn/settings.xml` template uses CodeArtifact as the mirror for everything, so CI builds never hit Maven Central directly.
- **Acceptance criteria**: `mvn -B verify` from the monorepo root succeeds (modules empty but reactor resolves). `mvn -B deploy -pl platform-bom -DskipTests` publishes `platform-bom:1.0.0-SNAPSHOT` to CodeArtifact. A throwaway service POM in `services/test-service/` that imports the BOM resolves all listed dependencies without specifying versions.
- **Dependencies**: 0.9

### Step 1.2: common-events, common-dto, and common-exceptions modules
- [ ] **Objective**: Define shared event payload types, common DTOs with schema versioning, and the shared error envelope (`ApiError`) used by every service's exception handler. These are the wire contracts every service shares.
- **Files to create**:
  - `platform-shared-libs/common-events/pom.xml`
  - `platform-shared-libs/common-events/src/main/java/.../events/UserCreatedEvent.java`
  - `platform-shared-libs/common-events/src/main/java/.../events/OrderPaidEvent.java`
  - `platform-shared-libs/common-events/src/main/java/.../events/PaymentSuccessEvent.java`
  - `platform-shared-libs/common-events/src/main/java/.../events/PaymentFailedEvent.java`
  - `platform-shared-libs/common-events/src/main/java/.../events/FoodReadyEvent.java`
  - `platform-shared-libs/common-events/src/main/java/.../events/OrderDeliveredEvent.java`
  - `platform-shared-libs/common-events/src/main/java/.../events/EventEnvelope.java` (wrapper with `eventId`, `traceId`, `occurredAt`, `schemaVersion`)
  - `platform-shared-libs/common-events/src/main/avro/*.avsc` (Avro schemas, one per event, registered in Glue Schema Registry)
  - `platform-shared-libs/common-events/src/main/proto/menu.proto`, `promotion.proto` (gRPC service contracts)
  - `platform-shared-libs/common-dto/pom.xml`
  - `platform-shared-libs/common-dto/src/main/java/.../dto/Money.java`
  - `platform-shared-libs/common-dto/src/main/java/.../dto/Address.java`
  - `platform-shared-libs/common-dto/src/main/java/.../dto/PaginationCursor.java`
  - `platform-shared-libs/common-exceptions/pom.xml`
  - `platform-shared-libs/common-exceptions/src/main/java/.../api/ApiError.java` (shared error envelope record)
  - `platform-shared-libs/common-exceptions/src/main/java/.../api/FieldError.java` (per-field validation detail used by `ApiError`)
  - `platform-shared-libs/common-exceptions/src/main/java/.../exceptions/PlatformException.java` (abstract base for typed exceptions)
  - `platform-shared-libs/common-exceptions/src/test/java/.../api/ApiErrorSerializationTest.java`
- **Key details**:
  - All event types are **immutable Java records** with JSpecify `@NonNull`/`@Nullable` annotations (Spring Boot 4 + Java 25 idiom).
  - Use Jackson `@JsonProperty` for stable wire format.
  - Include `schemaVersion` field on every event (start at `1`).
  - **Avro schema files** parallel the Java records — a generated `kafka-avro-serializer` will use them with Glue Schema Registry. Schemas are the source of truth for cross-language compatibility (in case a Python analytics consumer is added later).
  - `Money` uses `BigDecimal` with explicit currency code (ISO 4217) — never `double`.
  - DTOs are serialization contracts: any change is breaking, treat schema evolution carefully (Avro's compatibility rules: BACKWARD by default).
  - **Audit-driven (cross-cutting recommendation #1)**: `ApiError` record shape: `{ status: int, error: String, code: String, message: String, timestamp: Instant, path: String, traceId: String, fieldErrors: List<FieldError>? }`. `FieldError` shape: `{ field: String, rejectedValue: Object, message: String }`. Replaces the private record currently in `product-service`'s exception handler. Required by user-service Step 2.6 and order-service Step 4.12.
- **Acceptance criteria**: Records serialize/deserialize round-trip in unit tests. Avro schemas validate against the records via Avro→POJO mapping test. `ApiError` serializes to JSON with stable field names and excludes null `fieldErrors` by default.
- **Dependencies**: 1.1

### Step 1.3: common-resilience module — Resilience4j + Idempotency
- [ ] **Objective**: Centralize Resilience4j configurations and Spring auto-config that every service can apply with one annotation. **This is the module that `architecture.md` Section 4 references.**
- **Files to create**:
  - `platform-shared-libs/common-resilience/pom.xml`
  - `platform-shared-libs/common-resilience/src/main/java/.../resilience/ResilienceAutoConfig.java`
  - `platform-shared-libs/common-resilience/src/main/java/.../resilience/CircuitBreakerDefaults.java`
  - `platform-shared-libs/common-resilience/src/main/java/.../resilience/RetryDefaults.java`
  - `platform-shared-libs/common-resilience/src/main/java/.../resilience/TimeoutDefaults.java`
  - `platform-shared-libs/common-resilience/src/main/java/.../resilience/IdempotencyKeyAspect.java`
  - `platform-shared-libs/common-resilience/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  - `platform-shared-libs/common-resilience/src/main/resources/application-resilience.yml` (default thresholds)
- **Key details**:
  - Default circuit breaker: sliding window 10, failure threshold 50%, wait 60s in open.
  - Default retry: 3 attempts, exponential backoff 100ms × 2, max 1s, jitter 0.5.
  - Default timeout: 2s connect, 5s read.
  - `@Idempotent` annotation backed by Redis-stored keys with configurable TTL. Aspect intercepts methods, looks up `Idempotency-Key` from Spring Web's request scope, returns cached response on duplicate.
  - **Use virtual threads** (Java 25) for `@Bulkhead` thread-pool variant — much higher concurrency without the OS-thread cost.
  - Service-specific overrides via `application.yml` properties (Spring Boot config tree).
  - Micrometer metrics auto-registered for circuit breaker state, retry counters, timeout counters.
- **Acceptance criteria**: A test service that imports this module and adds `@CircuitBreaker(name = "test")` works without additional config. Inducing failures opens the circuit and emits the expected Micrometer metrics.
- **Dependencies**: 1.1

### Step 1.4: common-observability and common-outbox modules
- [ ] **Objective**: Provide structured JSON logging, OTel trace propagation, and the outbox publisher abstraction (Postgres + Kafka destination + SQS destination).
- **Files to create**:
  - `platform-shared-libs/common-observability/pom.xml`
  - `platform-shared-libs/common-observability/src/main/java/.../obs/LoggingAutoConfig.java`
  - `platform-shared-libs/common-observability/src/main/java/.../obs/TraceContextFilter.java`
  - `platform-shared-libs/common-observability/src/main/java/.../obs/KafkaTracePropagator.java`
  - `platform-shared-libs/common-observability/src/main/java/.../obs/SqsTracePropagator.java`
  - `platform-shared-libs/common-observability/src/main/resources/logback-spring.xml`
  - `platform-shared-libs/common-outbox/pom.xml`
  - `platform-shared-libs/common-outbox/src/main/java/.../outbox/OutboxEvent.java` (entity)
  - `platform-shared-libs/common-outbox/src/main/java/.../outbox/OutboxRepository.java` (interface)
  - `platform-shared-libs/common-outbox/src/main/java/.../outbox/JdbcOutboxRepository.java`
  - `platform-shared-libs/common-outbox/src/main/java/.../outbox/OutboxPublisher.java` (Spring `@Scheduled`)
  - `platform-shared-libs/common-outbox/src/main/java/.../outbox/KafkaOutboxDispatcher.java`
  - `platform-shared-libs/common-outbox/src/main/java/.../outbox/SqsOutboxDispatcher.java`
  - `platform-shared-libs/common-outbox/src/main/java/.../outbox/OutboxRouter.java` (decides Kafka vs SQS based on row's `destination_type`)
  - `platform-shared-libs/common-outbox/src/main/resources/db/migration/V1__outbox_table.sql`
- **Key details**:
  - Outbox row schema includes `destination_type` (`KAFKA` | `SQS`) and `destination` (topic name or queue ARN). The publisher reads, the router dispatches, the dispatcher publishes.
  - Structured JSON logs include `traceId`, `spanId`, `userId`, `service`, `version`, `level`, `logger`, `message`.
  - OTel SDK auto-config sends spans to AWS X-Ray (via OTel collector) — see Phase 7.3.
  - Trace context propagated via HTTP `traceparent` header, **Kafka headers** (`traceparent`), and SQS message attributes (`traceId`).
  - Outbox publisher: `@Scheduled(fixedDelay=500)`, batch size 100, `SELECT ... FOR UPDATE SKIP LOCKED`. Uses **virtual threads** for parallel dispatch within a batch.
  - Kafka dispatcher uses Glue Schema Registry serializer for Avro payloads.
  - Configurable per-event-type destination map via Spring properties (e.g., `outbox.routing.USER_CREATED.kafka.topic=identity-events`).
  - Metrics: outbox lag (oldest unprocessed row age), publish success/failure counters per destination type.
- **Acceptance criteria**: An importing service with the outbox V1 migration sees rows it inserts get published to the correct destination (Kafka or SQS) within ~1 second. Trace context propagates through Kafka headers verified end-to-end in an integration test.
- **Dependencies**: 1.1, 1.2

---

## Phase 2: User Service (pilot)

> Goal: working registration + login + JWT issuance, with the outbox emitting `USER_CREATED` events. By end of phase, you can register a user via API Gateway and observe the event in CloudWatch logs of a placeholder consumer.
>
> **PILOT NOTE**: user-service is the **pilot service for v1** (and the template for every later service in v2, v3, v4). In addition to Steps 2.1 through 2.6 below, the user-service pilot also includes — executed in the same overall arc, before any other service phase in v1 starts:
> - **Step 7.1** (Managed Prometheus + Grafana backend, plus user-service dashboard) — pulled forward from Phase 7 (Observability)
> - **Steps 8.1, 8.2, 8.3** (CodeCommit policies + path-filter Lambda + buildspec templates + user-service pipeline) — pulled forward from Phase 8 (CI/CD)
> - **Step 2.7** (consolidate the deploy template) — captures what was learned, for services 2–10 across all versions to reuse
>
> The remainder of Phase 7 and Phase 8 — X-Ray, SLO alerts, canary rollouts, replicating pipelines to the other v1 services — happens after all v1 services exist, in the original phase order.

### Step 2.1: user-service skeleton + DB schema
- [ ] **Objective**: Create the Spring Boot project, configure DB connection, run initial migrations.
- **Files to create**:
  - `services/user-service/pom.xml`
  - `services/user-service/src/main/java/.../UserApplication.java`
  - `services/user-service/src/main/resources/application.yml`
  - `services/user-service/src/main/resources/application-production.yml`
  - `services/user-service/src/main/resources/db/migration/V1__users.sql`
  - `services/user-service/src/main/resources/db/migration/V2__refresh_tokens.sql`
  - `services/user-service/src/main/resources/db/migration/V3__outbox.sql` (use shared snippet from Step 1.4)
  - `services/user-service/buildspec.yml`
  - `services/user-service/Dockerfile`
- **Key details**:
  - Depends on platform-shared-libs (common-dto, common-exceptions, common-resilience, common-observability, common-outbox, common-events)
  - Schema: `users(id UUID PK, email UNIQUE, password_hash, role, locale, created_at, updated_at)`, `refresh_tokens(id, user_id, token_hash, expires_at, revoked)`
  - Argon2id password hashing (Spring Security `Argon2PasswordEncoder`)
  - HikariCP pool: max 10 connections per pod
  - Spring Actuator: `/actuator/health`, `/actuator/prometheus`
  - Profile-based config; secrets resolved from External Secrets Operator at runtime
  - Dockerfile multi-stage: builder + Corretto JRE 25 distroless
- **Acceptance criteria**: `docker compose up` runs the service against a local PostgreSQL with all 3 migrations applied. `GET /actuator/health` returns 200.
- **Dependencies**: 1.4

### Step 2.2: Registration with outbox event
- [ ] **Objective**: Implement `POST /v1/auth/register` that writes user + outbox row in one transaction.
- **Files to create**:
  - `services/user-service/src/main/java/.../api/AuthController.java`
  - `services/user-service/src/main/java/.../service/UserRegistrationService.java`
  - `services/user-service/src/main/java/.../domain/User.java` (entity)
  - `services/user-service/src/main/java/.../domain/UserRepository.java`
  - `services/user-service/src/main/java/.../api/dto/RegisterRequest.java`
  - `services/user-service/src/main/java/.../api/dto/RegisterResponse.java`
  - `services/user-service/src/test/java/.../service/UserRegistrationServiceIT.java`
- **Key details**:
  - `@Transactional` method writes both `users` row and `outbox` row containing `UserCreatedEvent`
  - Validate email format, password strength (NIST 800-63B compliant)
  - Reject duplicate email with `409` and clear error code
  - Generate UUID v7 for user IDs (time-ordered)
  - Default role on registration: CUSTOMER
  - Tests use Testcontainers for PostgreSQL + LocalStack for SNS
  - **Audit-driven (audit §1, §6 for user-service)**: `RegisterRequest` is a Java record with `@NotBlank @Size(min=3, max=50)` on `username`, `@NotBlank @Size(min=8)` on `password`, `@Email @NotBlank` on `email`, `@NotNull` on `role`. Controller parameter is annotated `@Valid`. Endpoint returns a typed `RegisterResponse` record (NOT `ResponseEntity<String>`) with at minimum `{ userId, username, status, message }`. Returns **HTTP 202 Accepted** — registration is async (user transitions from PENDING via the USER_CREATED event flow).
- **Acceptance criteria**: After registration, `outbox` table contains 1 row with `event_type=USER_CREATED`. After ~1s the row's `processed_at` is set and the event lands in the configured SNS topic (verified by Testcontainers LocalStack). Validation rejects empty `username`, blank `password`, malformed `email` with HTTP 400 + field-level error details. Success response is HTTP 202 with a typed `RegisterResponse` body.
- **Dependencies**: 2.1

### Step 2.3: Login + JWT issuance + refresh token rotation
- [ ] **Objective**: Implement `POST /v1/auth/login`, `POST /v1/auth/refresh`, `POST /v1/auth/logout`.
- **Files to create**:
  - `services/user-service/src/main/java/.../service/AuthenticationService.java`
  - `services/user-service/src/main/java/.../service/JwtIssuer.java`
  - `services/user-service/src/main/java/.../security/SecurityConfig.java`
  - `services/user-service/src/main/java/.../domain/RefreshToken.java`
  - `services/user-service/src/main/java/.../domain/RefreshTokenRepository.java`
  - `services/user-service/src/main/java/.../api/dto/LoginRequest.java`
  - `services/user-service/src/main/java/.../api/dto/TokenResponse.java`
  - `services/user-service/src/test/java/.../api/AuthControllerIT.java`
- **Key details**:
  - JWT with RS256, private key from Secrets Manager, public key published to SSM Parameter Store on app startup
  - Access token TTL: 15 minutes; refresh token TTL: 30 days
  - Refresh token stored in DB hashed (SHA-256), opaque token to the client
  - Refresh token rotation: every refresh issues a new pair AND invalidates the previous refresh token
  - Brute-force protection: 5 failed attempts triggers 15-minute lockout (counter in Redis)
  - Logout revokes the refresh token and adds JWT `jti` to a Redis denylist with TTL = remaining lifetime
- **Acceptance criteria**: Integration tests cover happy path login, wrong password, lockout, refresh, refresh reuse detection (security-critical), logout.
- **Dependencies**: 2.2

### Step 2.4: User profile endpoints + security filters
- [ ] **Objective**: Implement `GET /v1/users/me`, `PATCH /v1/users/me` and the JWT validation filter chain.
- **Files to create**:
  - `services/user-service/src/main/java/.../api/UserController.java`
  - `services/user-service/src/main/java/.../service/UserProfileService.java`
  - `services/user-service/src/main/java/.../security/JwtAuthenticationFilter.java`
  - `services/user-service/src/main/java/.../security/JwtPublicKeyResolver.java`
  - `services/user-service/src/test/java/.../api/UserControllerIT.java`
- **Key details**:
  - Filter validates JWT signature, expiration, and `jti` denylist
  - On valid token, populates `SecurityContext` with `Authentication` containing role + userId
  - PATCH supports partial updates: name, locale, phone (with verification flow stubbed for now)
  - Returns 401 with `WWW-Authenticate: Bearer error="..."` per RFC 6750
  - `@PreAuthorize("hasRole('CUSTOMER')")` on profile endpoints
- **Acceptance criteria**: Tokens issued in 2.3 work to call `/v1/users/me`. Tampered or expired tokens return 401.
- **Dependencies**: 2.3

### Step 2.5: K8s manifests + ArgoCD application
- [ ] **Objective**: Wire user-service into the GitOps repo so ArgoCD deploys it to EKS.
- **Files to create**:
  - `food-delivery-gitops/apps/user-service/base/deployment.yaml`
  - `food-delivery-gitops/apps/user-service/base/service.yaml`
  - `food-delivery-gitops/apps/user-service/base/hpa.yaml`
  - `food-delivery-gitops/apps/user-service/base/serviceaccount.yaml` (with IRSA annotation)
  - `food-delivery-gitops/apps/user-service/base/externalsecret.yaml`
  - `food-delivery-gitops/apps/user-service/base/servicemonitor.yaml`
  - `food-delivery-gitops/apps/user-service/base/kustomization.yaml`
  - `food-delivery-gitops/apps/user-service/overlays/production/{kustomization.yaml,image-tag.yaml,replicas.yaml}`
  - `food-delivery-gitops/argocd/applications/user-service.yaml`
- **Key details**:
  - Deployment: 2 replicas, init container runs Flyway migrate
  - HPA: scale on CPU 70%, min 2 max 10
  - SA annotated with `eks.amazonaws.com/role-arn` (IRSA role created by Terraform)
  - ExternalSecret references Secrets Manager paths for DB password + JWT private key
  - ServiceMonitor for Prometheus to scrape `/actuator/prometheus`
  - PodDisruptionBudget: minAvailable 1
  - Resources: 500m CPU / 512Mi mem requests, 1 CPU / 1Gi limits
- **Acceptance criteria**: Push to main branch of food-delivery-gitops causes ArgoCD to deploy user-service. `kubectl get pods -n identity` shows running pods. Public API Gateway URL `POST /v1/auth/register` succeeds end-to-end.
- **Dependencies**: 0.11, 2.4

### Step 2.6: Address user-service audit gaps
- [ ] **Objective**: Close the audit gaps identified in `docs/API_AUDIT.md` for user-service: global exception handler, consistent error envelope, return-type fix. (The `@Valid` work and the typed-response fix from §1/§6 were already addressed in Step 2.2.)
- **Files to create**:
  - `services/user-service/src/main/java/.../exception/GlobalExceptionHandler.java`
  - `services/user-service/src/main/java/.../exception/UserServiceExceptions.java` (typed exceptions: `UserNotFoundException`, `EmailAlreadyTakenException`, `InvalidCredentialsException`, etc.)
  - `services/user-service/src/test/java/.../exception/GlobalExceptionHandlerIT.java`
- **Files to modify**:
  - All controllers in `services/user-service/` — ensure they throw typed exceptions, not generic `RuntimeException`.
- **Key details**:
  - **Audit §5 for user-service**: `@RestControllerAdvice` with explicit handlers:
    - `BadCredentialsException` → HTTP 401, `ApiError` body with code `AUTH_INVALID_CREDENTIALS`
    - `EmailAlreadyTakenException` → HTTP 409, code `AUTH_EMAIL_TAKEN`
    - `UserNotFoundException` → HTTP 404, code `USER_NOT_FOUND`
    - `MethodArgumentNotValidException` → HTTP 400, `ApiError` with `fieldErrors[]` populated from binding result
    - `Exception` (catch-all) → HTTP 500, code `INTERNAL_ERROR`, message redacted (no stack trace leakage)
  - Uses the shared `ApiError` record from `common-exceptions` (created in Step 1.2). Do NOT redefine locally.
  - Logging: every handler logs at WARN for 4xx, ERROR for 5xx, with request ID and user ID (if available) in MDC.
  - Test class verifies each handler produces the expected status + ApiError body shape via `@WebMvcTest`.
- **Acceptance criteria**: All known error paths return uniformly-shaped `ApiError` JSON. Integration test asserts the exact ApiError shape and HTTP status for each exception type. Unhandled exceptions no longer leak Spring's default error format.
- **Dependencies**: 2.5, 1.2 (the shared `ApiError` record must exist)

### Step 2.7: Consolidate deploy template (pilot checkpoint)
- [ ] **Objective**: Now that user-service is fully running on EKS with its CI/CD pipeline and dashboard, capture the patterns that worked. This document becomes the template for services 2–10.
- **Files to create**:
  - `food-delivery-platform/docs/service-deploy-template.md`
- **Key details**:
  - The template covers, for any future service:
    - **IRSA setup**: ServiceAccount annotation pattern, IAM role naming convention (`{org}-{env}-{service}-irsa`), trust policy template, common pitfalls
    - **Kustomize layout**: which manifests go in `base/`, which env-specific bits live in overlays, how `image-tag.yaml` is updated by CI
    - **External Secrets**: per-service `ExternalSecret` pattern, Secrets Manager path conventions
    - **ServiceMonitor + dashboard**: per-service monitoring scaffold, dashboard JSON file location, standard RED panels
    - **Pipeline wiring**: how to add a new service to the path-filter Lambda's routing config, what Terraform module instantiation looks like (one short `.tf` file per service)
    - **Verification checklist**: what "service X is fully deployed to production EKS" means concretely
  - Template includes a short FAQ section listing the surprises encountered on user-service so the next service author doesn't repeat them.
- **Acceptance criteria**: A developer who has never deployed a service to this platform can follow `docs/service-deploy-template.md` start-to-finish and end up with a new service running on EKS without needing to read the build plan's Phase 2.
- **Dependencies**: 2.6, and the pulled-forward Steps 7.1 + 8.1 + 8.2 + 8.3 (all of which complete before this consolidation)

---


## Phase 3: Product Service

> Goal: a working product/menu service with cache-aside, image upload via pre-signed URLs, gRPC verification endpoint, and the public search API.

### Step 3.1: product-service — Aurora wiring + Flyway migrations + test coverage
- [ ] **Objective**: Prepare the existing product-service for AWS deployment — wire Aurora PostgreSQL via the production Spring profile and add Flyway schema migrations replacing the `ddl-auto: update` used locally.
- **Files to create/edit**:
  - `services/product-service/src/main/resources/application-production.yml` (Aurora datasource via `${DB_URL}`, credentials from Secrets Manager wired at Step 0.11)
  - `services/product-service/src/main/resources/db/migration/V1__create_products.sql`
  - `services/product-service/src/test/java/.../repository/ProductRepositoryIT.java` (Testcontainers PostgreSQL)
  - `services/product-service/Dockerfile` (if not already present)
- **Key details**:
  - Schema: `products` table — `id` (UUID PK), `name`, `description`, `price` (NUMERIC), `category` (VARCHAR), `stock` (INTEGER), `version` (BIGINT for optimistic locking)
  - `@Version` optimistic locking already in place — verify tests cover the `OptimisticLockingFailureException` path on concurrent stock updates
  - Staging profile points at Aurora; local profile keeps the Docker Postgres datasource unchanged
  - Uses Aurora PostgreSQL (provisioned in Step 0.4) — no new infrastructure needed
- **Acceptance criteria**: `@DataJpaTest` + Testcontainers write a product, read it back, assert equality. Concurrent stock update test confirms `OptimisticLockingFailureException` fires correctly.
- **Dependencies**: 1.4

### Step 3.2: Caching layer + search endpoint
- [ ] **Objective**: Add Redis cache-aside in front of product reads and a search endpoint.
- **Files to create**:
  - `services/product-service/src/main/java/.../cache/ProductCacheConfig.java`
  - `services/product-service/src/test/java/.../service/ProductServiceCacheIT.java`
- **Key details**:
  - Cache key: `product:v1:{productId}`, TTL 30 min
  - Cache-aside: cache → miss → PostgreSQL → populate → return
  - On write (Step 3.3), explicitly delete cache key — do not rely solely on TTL
  - Cache-bypass query param `?nocache=true` (auth-gated for admins)
  - Search v1: Spring Data JPA `LIKE` query on `name` and `description`; existing category filter already in place
- **Acceptance criteria**: First request hits PostgreSQL; second request within 30 min hits cache (verified by metric `cache.hit`).
- **Dependencies**: 3.1

### Step 3.3: Admin write endpoints + S3 image uploads
- [ ] **Objective**: Admin users can update products; product images upload directly to S3 via pre-signed URLs.
- **Files to create**:
  - `services/product-service/src/main/java/.../service/ImageUploadService.java`
  - `services/product-service/src/test/java/.../api/ProductControllerIT.java`
- **Key details**:
  - JWT must include `role=ADMIN` (already enforced in existing `ProductController`)
  - On any mutation: write to PostgreSQL via JPA, then `cache.delete(productKey)` — order matters
  - `POST /v1/products/{id}/image-upload-url` returns pre-signed S3 PUT URL valid 5 min, max 5 MB
  - Image keys are content-addressed: `products/{productId}/{sha256}.jpg`
  - Lambda triggered on S3 PutObject resizes to standard variants (thumb, medium, full) and updates the `image_s3_key` column
- **Acceptance criteria**: Update product price → first GET after update returns new price (cache invalidation works). Upload an image and access via CloudFront URL.
- **Dependencies**: 3.2

### Step 3.4: gRPC server for internal price/availability verification
- [ ] **Objective**: Expose `ProductService.VerifyProduct(productId)` for Basket and Order services to confirm item availability and current price before acting on it.
- **Files to create**:
  - `platform-shared-libs/common-events/src/main/proto/product.proto`
  - `services/product-service/src/main/java/.../grpc/ProductGrpcService.java`
  - `services/product-service/src/test/java/.../grpc/ProductGrpcServiceTest.java`
- **Key details**:
  - Returns `ProductAvailability { exists, in_stock, current_price, stock }`
  - Cached in Redis with TTL 60s — verifies are slightly stale-tolerant; final price-locking happens at Order checkout
  - Resilience4j on the server side: rate limiter 1000 req/s per source pod
- **Acceptance criteria**: gRPC client from a test calls `VerifyProduct` and gets correct response in < 50ms p99.
- **Dependencies**: 3.3

### Step 3.5: RESTAURANT_PAUSED listener + manifests + deployment
- [ ] **Objective**: Subscribe to Kitchen events to hide overloaded restaurants. Deploy to EKS.
- **Files to create**:
  - `services/product-service/src/main/java/.../listener/RestaurantPausedListener.java`
  - `services/product-service/src/main/java/.../domain/RestaurantStatus.java` (JPA entity — PostgreSQL row tracking paused/resumed state)
  - `food-delivery-gitops/apps/product-service/base/...`
  - `food-delivery-gitops/apps/product-service/overlays/production/...`
  - `food-delivery-gitops/argocd/applications/product-service.yaml`
- **Key details**:
  - **Kafka consumer** subscribed to topic `kitchen-events` with consumer group `product-service`, filtering on `eventType` header (`RESTAURANT_PAUSED`, `RESTAURANT_RESUMED`)
  - Updates `RestaurantStatus` DDB row with `paused = true/false` and `pausedAt`
  - Search and `VerifyItem` filter out paused restaurants
  - Auto-resume after configurable idle period (Kitchen emits `RESTAURANT_RESUMED`)
- **Acceptance criteria**: Manually publish `RESTAURANT_PAUSED` to MSK topic `kitchen-events` → search no longer returns that restaurant. ArgoCD shows Synced/Healthy.
- **Dependencies**: 3.4, 0.11

---

## Phase 4: Basket Service

> Goal: customers add/remove items with idempotency; items are validated against Menu via gRPC in real time.

### Step 4.1: basket-service skeleton with Redis primary store
- [ ] **Objective**: Spring Boot service with Redis (Lettuce) as primary store.
- **Files to create**:
  - `services/basket-service/pom.xml`
  - `services/basket-service/src/main/java/.../BasketApplication.java`
  - `services/basket-service/src/main/java/.../config/RedisConfig.java`
  - `services/basket-service/src/main/java/.../domain/Basket.java`
  - `services/basket-service/src/main/java/.../domain/BasketItem.java`
  - `services/basket-service/src/main/java/.../domain/BasketRepository.java`
  - `services/basket-service/src/main/resources/application.yml`
  - `services/basket-service/Dockerfile`
- **Key details**:
  - Redis hash per basket: key `basket:{userId}`, fields: `restaurant_id`, `created_at`, `items_json`
  - TTL 24 hours, refreshed on every modification
  - `Basket` value object: `userId`, `restaurantId`, `items`, `subtotal`, `lastModified`
  - Hard limit 50 items per cart (server-side)
  - One basket per user — adding from a different restaurant clears existing basket (with explicit UI confirmation)
- **Acceptance criteria**: Create + read + update basket round-trip via integration test against Testcontainers Redis.
- **Dependencies**: 1.4

### Step 4.2: REST endpoints + idempotency layer
- [ ] **Objective**: `GET /v1/basket`, `POST /v1/basket/items`, `DELETE /v1/basket/items/{itemId}`, `POST /v1/basket/clear`.
- **Files to create**:
  - `services/basket-service/src/main/java/.../api/BasketController.java`
  - `services/basket-service/src/main/java/.../service/BasketService.java`
  - `services/basket-service/src/main/java/.../api/IdempotencyInterceptor.java`
  - `services/basket-service/src/main/java/.../api/dto/AddItemRequest.java`
  - `services/basket-service/src/test/java/.../api/BasketControllerIT.java`
- **Key details**:
  - Add-item request requires `Idempotency-Key` header (UUID)
  - Idempotency store: `idem:basket:{userId}:{key}` in Redis with 24h TTL, value = response body to return on retry
  - On retry with same key but different request body → 409
  - Validate item via gRPC (Step 4.3) before persisting
  - `BasketService` uses Redis WATCH/MULTI/EXEC (or Lua) for atomic read-modify-write
- **Acceptance criteria**: Same `Idempotency-Key` posted twice → only one item added; second request returns the same response.
- **Dependencies**: 4.1

### Step 4.3: gRPC client to Product Service with circuit breaker
- [ ] **Objective**: Validate every add-to-basket call against Menu Service in real time.
- **Files to create**:
  - `services/basket-service/src/main/java/.../client/MenuGrpcClient.java`
  - `services/basket-service/src/main/java/.../client/MenuClientConfig.java`
  - `services/basket-service/src/main/java/.../client/MenuClientFallback.java`
  - `services/basket-service/src/test/java/.../client/MenuGrpcClientIT.java`
- **Key details**:
  - Generated stub from `menu.proto` (consumed via shared-libs)
  - Channel target: internal ALB DNS with HTTP/2
  - Resilience4j: circuit breaker `menu-grpc`, retry 2 attempts, timeout 200ms (hot path)
  - Fallback on circuit-open: return `503 Service Unavailable` with retry-after — basket modification rejected
  - On `available_now=false` or `restaurant_paused=true`: `409 Conflict` with reason
  - Compares `current_price` from Menu vs price in request; mismatch → 409
- **Acceptance criteria**: Chaos test: kill Menu Service pods → basket adds fail fast with 503 (not 30s timeouts).
- **Dependencies**: 4.2, 3.4

### Step 4.4: Checkout endpoint + manifests + deployment
- [ ] **Objective**: Implement `POST /v1/basket/checkout` that locks the basket and returns a pre-order DTO; deploy to EKS.
- **Files to create**:
  - `services/basket-service/src/main/java/.../service/CheckoutService.java`
  - `services/basket-service/src/main/java/.../api/CheckoutController.java`
  - `food-delivery-gitops/apps/basket-service/base/...`
  - `food-delivery-gitops/apps/basket-service/overlays/production/...`
  - `food-delivery-gitops/argocd/applications/basket-service.yaml`
- **Key details**:
  - Checkout re-verifies every item via Menu gRPC, computes final subtotal, marks basket `LOCKED` (Redis SETNX with checkout token)
  - Returns `PreOrder` DTO with all data Order Service needs to create the order
  - Locked basket cannot be modified for 5 minutes — if no order is created, lock auto-expires
  - On checkout failure (price changed, item unavailable): 409 with details so UI can refresh
- **Acceptance criteria**: End-to-end: add items, checkout, get a `PreOrder` with locked basket. Try modifying during lock → 409.
- **Dependencies**: 4.3, 0.11

### Step 4.5: Address basket-service audit gaps
- [ ] **Objective**: Close the audit gap identified in `docs/API_AUDIT.md` for basket-service: idempotent add-item via upsert-by-productId.
- **Files to modify**:
  - `services/basket-service/src/main/java/.../service/BasketService.java`
  - `services/basket-service/src/main/java/.../domain/Basket.java`
  - `services/basket-service/src/test/java/.../api/BasketControllerIT.java` (add idempotency-by-product tests)
- **Key details**:
  - **Audit §9 for basket-service**: `POST /v1/basket/items` must upsert by `productId`. If the same product is added twice (with or without `Idempotency-Key` header), the basket should NOT contain two separate entries — it should contain one entry with `quantity = quantity_existing + quantity_new`.
  - Atomicity: use Redis Lua script (single round-trip, atomic) for the read-modify-write. The script: HGET the basket; if product exists, ADD to its quantity; otherwise HSET a new entry. Refresh basket TTL on success.
  - The existing `Idempotency-Key` mechanism (request-level) is still useful for "exactly-once" semantics on retry of the same HTTP call. The upsert-by-productId is for "same product added in two separate calls" — different concern, different solution.
  - Quantity cap: still enforce the 50-item limit, but interpret it as 50 distinct line-items, not 50 units. Increasing quantity on an existing item never hits the limit.
- **Acceptance criteria**: Integration test: POST add-item with same `productId` twice (no shared idempotency key) → basket contains ONE entry with quantity 2. POST with same `Idempotency-Key` twice → returns cached response, basket unchanged from second call.
- **Dependencies**: 4.4

---

---

## Phase 5: Payment Service (minimal for v1)

> Goal: a minimal but real payment service that calls Stripe test mode, records the result in a DynamoDB ledger, and emits `PAYMENT_SUCCESS` / `PAYMENT_FAILED` events to Kafka. Demonstrates the patterns (DDB ledger, idempotency, outbox emitting to Kafka) without the operational weight of v4's full version (webhooks, refunds, circuit breakers, compensation).
>
> **v1 vs v4 distinction**: this v1 payment-service is intentionally minimal. It demonstrates: DDB single-table design, idempotent external-API calls, outbox pattern, async event publishing. It deliberately defers: Stripe webhook signature verification, refund flows, full Resilience4j stack (circuit breaker + retry + bulkhead + rate limiter), DDB Streams-based outbox publisher Lambda. v4 graduates this service to production-grade with all of those.

### Step 5.1: payment-service skeleton + DynamoDB ledger
- [ ] **Objective**: Spring Boot service with append-only payment ledger in DynamoDB.
- **Files to create**:
  - `services/payment-service/pom.xml`
  - `services/payment-service/src/main/java/.../PaymentApplication.java`
  - `services/payment-service/src/main/java/.../domain/PaymentIntent.java`
  - `services/payment-service/src/main/java/.../domain/PaymentLedgerEntry.java`
  - `services/payment-service/src/main/java/.../domain/PaymentRepository.java`
  - `services/payment-service/src/main/resources/application.yml`
  - `services/payment-service/Dockerfile`
- **Key details**:
  - `payment-ledger` table: PK=`payment_intent_id` (S), SK=`entry_seq` (N).
  - Entry types (v1 subset): `INITIATED`, `CAPTURED`, `FAILED`. (v4 adds `AUTHORIZED`, `REFUNDED`, `DISPUTED`.)
  - Append-only: never `UpdateItem`, only `PutItem` with conditional `attribute_not_exists`.
  - GSI on `idempotency_key` for the duplicate-charge check.
  - PII discipline: never log PAN; only `last4` and Stripe token references.
- **Infrastructure** (deferred from Step 0.5 — provision here when the service is built):
  - Create `platform-infra/modules/dynamodb-table/` (reusable module: `main.tf`, `variables.tf`, `outputs.tf`; KMS CMK per table, on-demand billing, optional streams/PITR/TTL/GSI via dynamic blocks) — first use of this module
  - Create `platform-infra/envs/production/dynamodb.tf`, then add:
    - `payment-ledger`: PK=`payment_intent_id` (S), SK=`entry_seq` (N), PITR enabled, GSI on `idempotency_key` (S), KMS CMK
    - `outbox-payment`: PK=`event_id` (S), streams enabled (`NEW_AND_OLD_IMAGES`), KMS CMK
- **Acceptance criteria**: Insert ledger entries, list all entries for a payment intent, assert ordering.
- **Dependencies**: 1.4

### Step 5.2: SQS listener + Stripe test-mode integration + idempotency
- [ ] **Objective**: Consume `CHARGE_PAYMENT` commands from SQS, call Stripe test mode with idempotency, write ledger entry, emit outbox event.
- **Files to create**:
  - `services/payment-service/src/main/java/.../listener/ChargePaymentListener.java`
  - `services/payment-service/src/main/java/.../service/PaymentService.java`
  - `services/payment-service/src/main/java/.../client/StripeClient.java`
  - `services/payment-service/src/main/java/.../client/StripeConfig.java`
  - `services/payment-service/src/test/java/.../service/PaymentServiceIT.java`
- **Key details**:
  - **Spring Cloud AWS SQS listener** on the `charge-payment` queue (provisioned in Step 0.8).
  - Message envelope from order-service includes: `orderId`, `customerId`, `amount`, `currency`, `idempotencyKey` (= order ID).
  - Flow: SQS message arrives → query GSI by `idempotencyKey` → if entry exists, ack the message and emit cached outbox event; otherwise → write `INITIATED` ledger entry → call Stripe `PaymentIntent.create()` with `idempotency_key` header (Stripe also dedups server-side) → write `CAPTURED` or `FAILED` entry → write outbox row with the corresponding event.
  - Crash-safe: if Stripe call succeeds but ledger write fails, recovery job re-queries Stripe by idempotency key. (Recovery job stub in v1; full implementation in v4.)
  - **v1 uses Stripe test mode**: `STRIPE_API_KEY=sk_test_...` from Secrets Manager. Test cards documented in service README. v4 graduates to live mode with proper key rotation.
  - **v1 has no real webhook handling**: webhooks deferred to v4. For v1, we trust the synchronous Stripe response. This is the one place where v1 is genuinely simpler than production-correct — but it's acceptable because all v1 payments are test mode.
  - **v1 resilience**: basic Resilience4j retry (3 attempts, exponential backoff) and time limiter (5s). Full circuit breaker / bulkhead / rate limiter deferred to v4.
- **Acceptance criteria**: SQS message → Stripe charged → ledger entry written → outbox publishes `PAYMENT_SUCCESS` to MSK `payment-events`. Re-delivery of same SQS message (same `idempotencyKey`) produces no second Stripe charge and ack's the queue.
- **Dependencies**: 5.1

### Step 5.3: Polling outbox publisher + K8s manifests + deployment
- [ ] **Objective**: Polling-based outbox publisher (sidecar pattern) for v1; deploy to EKS.
- **Files to create**:
  - `services/payment-service/src/main/java/.../outbox/OutboxPublisher.java` (uses the `common-outbox` library from Step 1.4)
  - `food-delivery-gitops/apps/payment-service/base/...` (deployment, hpa, sa, externalsecret, servicemonitor, networkpolicy)
  - `food-delivery-gitops/apps/payment-service/overlays/production/...`
  - `food-delivery-gitops/argocd/applications/payment-service.yaml`
- **Key details**:
  - **v1 uses polling publisher** running as a `@Scheduled` task in the same pod (sidecar pattern). DDB Streams-based Lambda publisher deferred to v4.
  - Polling interval: 500ms; batch size: 100; uses `SELECT ... FOR UPDATE SKIP LOCKED` semantics on a per-row basis via DDB conditional writes.
  - Publishes to MSK topic `payment-events` (with `orderId` as partition key for per-order ordering).
  - Payment service deployed in dedicated namespace `payment` with stricter NetworkPolicy (only order-service can SQS-send `charge-payment`).
  - Standard manifest set (per `docs/service-deploy-template.md` from Step 2.7).
- **Acceptance criteria**: End-to-end: order-service writes a `CHARGE_PAYMENT` SQS message → payment-service processes → outbox emits `PAYMENT_SUCCESS` to MSK within 1s. ArgoCD shows payment-service Synced/Healthy.
- **Dependencies**: 5.2, 0.11

### Step 5.4: Address payment-service v1 gaps
- [ ] **Objective**: Apply the v1 audit conventions (`@RestControllerAdvice`, validation, test scaffolding) to payment-service. payment-service has no REST surface visible to end users (it's SQS-driven), but it has admin/health endpoints that need the conventions.
- **Files to create**:
  - `services/payment-service/src/main/java/.../exception/GlobalExceptionHandler.java`
  - `services/payment-service/src/main/java/.../exception/PaymentServiceExceptions.java` (typed exceptions)
  - `services/payment-service/src/test/java/.../service/PaymentServiceTest.java` (slice tests)
  - `services/payment-service/src/test/java/.../listener/ChargePaymentListenerIT.java` (Testcontainers SQS + DDB local)
- **Key details**:
  - Use the shared `ApiError` record from `common-exceptions` (Step 1.2).
  - 80% line coverage gate enforced via JaCoCo.
  - Tests must cover: Stripe success path, Stripe decline path, idempotent re-delivery, malformed SQS message handling.
- **Acceptance criteria**: Coverage ≥ 80%. All test patterns from `docs/service-deploy-template.md` represented.
- **Dependencies**: 5.3

---

## Phase 6: Order Service (mini-saga for v1)

> Goal: the v1 saga — small but real. By the end of this phase, a customer can checkout a basket, get charged via the payment-service, and on payment failure the system rolls back cleanly via one compensation action (restore basket). This is v1's most important phase because it demonstrates the saga pattern that v2/v3/v4 will extend.
>
> **v1 vs v2 distinction**: v1's saga has 6 states and 2 paths (forward + compensation-on-payment-failure). v2 expands this to 10 states with kitchen and delivery transitions and 4 compensation actions. v3 adds promotion redemption. The *pattern* established in v1 is what later versions build on.

> **v1 state machine** (6 states, 5 transitions):
> ```
> PENDING ──[PAYMENT_SUCCESS]──→ PAID ──[auto]──→ COMPLETED (terminal)
>    │
>    └──[PAYMENT_FAILED]──→ COMPENSATING ──[BASKET_RESTORED ack]──→ CANCELED (terminal)
>    │
>    └──[SAGA_TIMEOUT]──→ COMPENSATING ──[BASKET_RESTORED ack]──→ FAILED (terminal)
> ```
>
> v1's "COMPLETED" state stands in for what v2 will call "DELIVERED" — for now, paid = done.

### Step 6.1: order-service skeleton + DB schema + state machine config
- [ ] **Objective**: Spring Boot project, schema with orders + order_items + saga_compensation_acks + outbox tables, Spring StateMachine wiring for the v1 mini-saga.
- **Files to create**:
  - `services/order-service/pom.xml`
  - `services/order-service/src/main/java/.../OrderApplication.java`
  - `services/order-service/src/main/resources/application.yml`
  - `services/order-service/src/main/resources/db/migration/V1__orders.sql`
  - `services/order-service/src/main/resources/db/migration/V2__order_items.sql`
  - `services/order-service/src/main/resources/db/migration/V3__saga_compensation_acks.sql`
  - `services/order-service/src/main/resources/db/migration/V4__outbox.sql`
  - `services/order-service/src/main/java/.../config/StateMachineConfig.java`
  - `services/order-service/Dockerfile`
- **Key details**:
  - `orders` table: `id, customer_id, basket_token, state, subtotal, total, currency, payment_intent_id, failure_reason, created_at, updated_at, paid_at, completed_at, expected_compensation_acks JSONB, version`.
  - `order_items` table normalized.
  - `saga_compensation_acks` tracks which compensation actions have been ack'd per order.
  - **v1 states enum (6)**: `PENDING, PAID, COMPLETED, COMPENSATING, CANCELED, FAILED`. v2 will add `KITCHEN_ACCEPTED, FOOD_READY, OUT_FOR_DELIVERY, DELIVERED, CANCELING`.
  - Spring StateMachine config defines all 5 transitions and guards (e.g., can't go to `PAID` from any state except `PENDING`).
  - Optimistic locking via `version` column with `@Version`.
- **Acceptance criteria**: All migrations apply. State machine bean loads. Unit test transitions through happy path and compensation path.
- **Dependencies**: 1.4

### Step 6.2: Create order endpoint (PENDING state) + outbox CHARGE_PAYMENT command
- [ ] **Objective**: `POST /v1/orders` accepts a basket token, creates the order in `PENDING` state, writes outbox row containing the `CHARGE_PAYMENT` command.
- **Files to create**:
  - `services/order-service/src/main/java/.../api/OrderController.java`
  - `services/order-service/src/main/java/.../service/OrderCreationService.java`
  - `services/order-service/src/main/java/.../api/dto/CreateOrderRequest.java`
  - `services/order-service/src/main/java/.../api/dto/OrderResponse.java`
  - `services/order-service/src/test/java/.../service/OrderCreationServiceIT.java`
- **Key details**:
  - Validate basket token by calling basket-service's internal verify endpoint (REST, not in hot path).
  - `@Transactional`: insert order + items + outbox row (`CHARGE_PAYMENT` command targeting SQS `charge-payment` queue) in single tx.
  - `Idempotency-Key` header required; map to `(customer_id, basket_token, idempotency_key)` so same checkout doesn't create two orders.
  - Returns 202 Accepted with order ID — final confirmation comes after payment.
  - v1 has no promo redemption (v3 adds that); total = subtotal.
- **Acceptance criteria**: Order created, outbox row visible. Same idempotency key returns same order. After ~1s the outbox row is published as an SQS message to `charge-payment` queue.
- **Dependencies**: 6.1, 4.4

### Step 6.3: Saga forward path — PAYMENT_SUCCESS handler (PENDING → PAID → COMPLETED)
- [ ] **Objective**: Consume `PAYMENT_SUCCESS` events from Kafka, transition order state through `PAID` to `COMPLETED`.
- **Files to create**:
  - `services/order-service/src/main/java/.../listener/PaymentEventListener.java`
  - `services/order-service/src/main/java/.../saga/OrderSaga.java` (handler methods)
  - `services/order-service/src/test/java/.../saga/OrderSagaPaymentSuccessIT.java`
- **Key details**:
  - **Kafka consumer (Spring Kafka @KafkaListener)** subscribed to topic `payment-events` with consumer group `order-service`. Filters on header `eventType=PAYMENT_SUCCESS`.
  - Loads order with `SELECT FOR UPDATE`; idempotent ignore if state already `PAID`, `COMPLETED`, `COMPENSATING`, `CANCELED`, or `FAILED`.
  - State transition through Spring StateMachine: `PENDING → PAID`, then immediately `PAID → COMPLETED` (in v1, no kitchen/delivery wait).
  - Writes outbox row `ORDER_COMPLETED` (Kafka topic `order-events`, key=orderId).
  - Includes `traceId` propagated from incoming Kafka headers into outgoing events.
- **Acceptance criteria**: Publish a fake `PAYMENT_SUCCESS` to MSK topic `payment-events` → order moves PENDING → PAID → COMPLETED. Outbox publishes `ORDER_COMPLETED` to MSK. Idempotent: republishing same event leaves state unchanged.
- **Dependencies**: 6.2, 5.3

### Step 6.4: Compensation path — PAYMENT_FAILED handler + RESTORE_BASKET command
- [ ] **Objective**: On `PAYMENT_FAILED` event, transition to `COMPENSATING`, write `RESTORE_BASKET` SQS command, wait for ack.
- **Files to create**:
  - `services/order-service/src/main/java/.../listener/PaymentFailedListener.java`
  - `services/order-service/src/main/java/.../saga/CompensationService.java`
  - `services/order-service/src/main/java/.../listener/BasketRestoredAckListener.java`
  - `services/order-service/src/test/java/.../saga/CompensationServiceIT.java`
- **Key details**:
  - On `PAYMENT_FAILED` event: load order with `FOR UPDATE`, transition to `COMPENSATING`, populate `expected_compensation_acks = {"expected": ["BASKET_RESTORED"], "received": []}`, write outbox row `RESTORE_BASKET` to SQS `basket-compensation` queue.
  - On `BASKET_RESTORED` ack (consumed from Kafka `basket-events` or a dedicated ack queue): append to `received[]`. When `expected.length == received.length`, transition `COMPENSATING → CANCELED`. Write outbox row `ORDER_CANCELED`.
  - Each handler is idempotent (same event arriving twice is a no-op, logged at WARN).
  - Out-of-order events (e.g., `PAYMENT_FAILED` arriving in `COMPLETED` state) are silently dropped with a WARN log.
- **Acceptance criteria**: Publish `PAYMENT_FAILED` → order transitions to `COMPENSATING`, RESTORE_BASKET SQS message visible. Manually emit `BASKET_RESTORED` ack → order transitions to `CANCELED`. Idempotent on both event types.
- **Dependencies**: 6.3, 4.5

### Step 6.5: Cancel endpoint + saga timeout enforcer
- [ ] **Objective**: `DELETE /v1/orders/{id}` (state-aware) and a scheduled enforcer that triggers compensation for stuck orders.
- **Files to create**:
  - `services/order-service/src/main/java/.../service/OrderCancellationService.java`
  - `services/order-service/src/main/java/.../api/dto/CancelOrderResponse.java`
  - `services/order-service/src/main/java/.../saga/SagaTimeoutEnforcer.java`
  - `services/order-service/src/test/java/.../api/OrderCancellationIT.java`
- **Key details**:
  - **Cancel endpoint**: state `PENDING` → cancel immediately, transition to `CANCELED`, no compensation needed (no payment yet). State `PAID` or `COMPLETED` → 409 Conflict (v1 doesn't support refund-based cancel; that's v4). Returns 202 Accepted.
  - **Timeout enforcer**: `@Scheduled(fixedDelay = 30_000)` runs every 30s. Query: orders in non-terminal state (`PENDING`, `COMPENSATING`) with `updated_at < NOW() - timeoutFor(state)`. For each stuck order: invoke `CompensationService.onSagaTimeout(orderId)` with reason `SAGA_TIMEOUT`.
  - Configurable per-state timeout: `PENDING → 2min` (charging should be near-instant), `COMPENSATING → 5min` (waiting for basket ack).
  - ShedLock or PostgreSQL advisory lock to prevent multiple pods running the same scan.
  - Metrics: `saga.timeouts.triggered{state=...}`.
- **Acceptance criteria**: DELETE in `PENDING` → 202, state `CANCELED`. DELETE in `COMPLETED` → 409. Manually create a `PENDING` order and freeze time forward via test clock → enforcer triggers compensation.
- **Dependencies**: 6.4

### Step 6.6: Get order + list orders endpoints
- [ ] **Objective**: Read APIs `GET /v1/orders/{id}` and `GET /v1/orders` with pagination, filtering, and sorting.
- **Files to create**:
  - `services/order-service/src/main/java/.../api/OrderQueryController.java`
  - `services/order-service/src/main/java/.../service/OrderQueryService.java`
  - `services/order-service/src/main/java/.../api/dto/OrderListResponse.java`
- **Key details**:
  - **Audit §7 + §11 for order-service**: `GET /v1/orders` returns `Page<OrderResponseDto>` (Spring Data) — never unpaginated `List`. Controller accepts `Pageable` parameter with `@PageableDefault(size = 20)`. Optional filter params: `?status=COMPLETED`, `?from=2026-01-01`, `?to=2026-01-31`, and `?sort=createdAt,desc`. All filters validated; invalid `status` values rejected with 400.
  - Customer can only see their own orders; admin sees all.
  - Includes saga state and timeline (state transition history) — store transitions in dedicated `order_state_history` table updated by state machine listener.
- **Acceptance criteria**: Customer with 50 orders can paginate by 20. Filtering by `status=COMPLETED` returns only completed orders. Invalid status returns HTTP 400 with `ApiError`.
- **Dependencies**: 6.5

### Step 6.7: K8s manifests + audit gaps + observability dashboard
- [ ] **Objective**: Deploy order-service to EKS, address audit gaps, build the saga dashboard.
- **Files to create**:
  - `food-delivery-gitops/apps/order-service/base/...` (deployment, hpa, sa, externalsecret, servicemonitor, networkpolicy)
  - `food-delivery-gitops/apps/order-service/overlays/production/...`
  - `food-delivery-gitops/argocd/applications/order-service.yaml`
  - `food-delivery-gitops/apps/order-service/base/grafana-dashboard-saga.json`
  - `services/order-service/src/main/java/.../exception/GlobalExceptionHandler.java`
  - `services/order-service/src/main/java/.../exception/OrderServiceExceptions.java`
  - `services/order-service/src/test/java/.../exception/GlobalExceptionHandlerIT.java`
- **Key details**:
  - **K8s manifests**: 2 replicas (highest criticality of v1's services), Flyway init container, PodDisruptionBudget minAvailable 1, resources 1 CPU/1Gi request, 2 CPU/2Gi limit, liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness`.
  - NetworkPolicy: allow ingress from API Gateway (via VPC link). Egress to MSK, SQS, payment-service, basket-service.
  - **Audit §5 for order-service**: `@RestControllerAdvice` with explicit handlers for `OrderNotFoundException` → 404, `OrderStateConflictException` → 409 (e.g. "can't cancel order in COMPLETED"), `IdempotencyKeyMismatchException` → 409, `MethodArgumentNotValidException` → 400, `Exception` → 500. Uses shared `ApiError` from `common-exceptions` (Step 1.2).
  - **Saga dashboard panels**: orders by state (PENDING/PAID/COMPLETED/COMPENSATING/CANCELED/FAILED), time-in-state p50/p99, compensation rate, outbox lag, timeout enforcer fires.
  - Alert rules: `compensation_rate > 5%` for 5 min, `outbox_lag > 10s` for 5 min, `orders_stuck > 0` for 30 min.
- **Acceptance criteria**: ArgoCD shows Healthy. End-to-end smoke test: register user → add to basket → checkout → order created → payment captured → order COMPLETED. Then: same but force a Stripe test-mode decline → order CANCELED via the compensation path. Saga dashboard reflects both runs.
- **Dependencies**: 6.6, 0.11, 1.2

---

## Phase 7: Observability

> Goal: every v1 service is fully observable with metrics, traces, logs, and SLO-based alerts. By end of phase, on-call engineer can diagnose any outage in < 5 minutes.
>
> **Note**: Step 7.1 below was pulled forward into the user-service pilot (executed during Phase 2). The user-service dashboard and Managed Prometheus / Managed Grafana setup already exist by the time Phase 7 starts. Phase 7 covers: (a) per-service dashboards for the remaining 4 v1 services (product, basket, payment, order), (b) cross-cutting X-Ray tracing across all v1 services, (c) SLO-based alerts. Step 7.1 here remains as a reference for what the pilot work delivered.

### Step 7.1: Amazon Managed Prometheus + Managed Grafana setup
- [ ] **Objective**: Provision the observability backend and connect EKS to it.
- **Files to create**:
  - `platform-infra/modules/observability/main.tf`
  - `platform-infra/envs/shared/observability.tf`
  - `food-delivery-gitops/apps/observability/prometheus-agent/...`
  - `food-delivery-gitops/apps/observability/grafana-datasources/...`
- **Key details**:
  - Amazon Managed Service for Prometheus (AMP) workspace per environment
  - Amazon Managed Grafana workspace (single, with environment switching via Grafana variables)
  - Prometheus agent (kube-prometheus-stack Helm chart with `agent` mode) deployed on EKS via ArgoCD
  - Agent forwards scraped metrics to AMP via `remote_write` with SigV4 auth
  - Grafana data source: AMP for metrics, CloudWatch for logs, X-Ray for traces
  - SAML/Okta SSO for Grafana access; viewer role default, editor for SREs, admin restricted
- **Acceptance criteria**: Grafana shows AMP datasource healthy. Out-of-the-box K8s dashboards display data from EKS.
- **Dependencies**: 0.11

### Step 7.2: Per-service dashboards + ServiceMonitors
- [ ] **Objective**: Build standardized dashboards for all v1 services covering RED metrics (Rate, Errors, Duration) plus service-specific panels. v1's user-service dashboard already exists (built in the pilot). Build dashboards for the remaining 4: product, basket, payment, order.
- **Files to create**:
  - `food-delivery-gitops/apps/observability/dashboards/identity-dashboard.json`
  - `food-delivery-gitops/apps/observability/dashboards/order-saga-dashboard.json` (already started in Step 4.10)
  - `food-delivery-gitops/apps/observability/dashboards/payment-dashboard.json`
  - `food-delivery-gitops/apps/observability/dashboards/menu-dashboard.json`
  - ... (one per service)
  - `food-delivery-gitops/apps/observability/dashboards/platform-overview.json`
- **Key details**:
  - Standard panels for every service: requests/sec, error rate, p50/p95/p99 latency, JVM heap, GC time, DB pool usage, circuit breaker state
  - Service-specific panels: Order saga state distribution, Payment Stripe latency, Menu cache hit rate, Basket gRPC fallback rate, Kitchen ticket queue depth
  - Platform overview: order success rate (top-level SLO), end-to-end checkout latency, total revenue/hour
  - All dashboards stored as JSON in GitOps and provisioned via Grafana operator
  - Per-service ServiceMonitor in K8s manifests (already added in earlier phases) — verify all are present
- **Acceptance criteria**: All 10 service dashboards render data in Grafana. Platform overview shows live order flow.
- **Dependencies**: 7.1, all service deployment steps

### Step 7.3: AWS X-Ray distributed tracing across services
- [ ] **Objective**: Trace context propagates through HTTP, gRPC, and SQS so a single trace shows the full saga.
- **Files to create**:
  - `platform-shared-libs/common-observability/src/main/java/.../obs/XRayConfig.java`
  - `platform-shared-libs/common-observability/src/main/java/.../obs/SqsTracePropagator.java`
  - `platform-shared-libs/common-observability/src/main/java/.../obs/GrpcTracingInterceptor.java`
  - `food-delivery-gitops/apps/observability/otel-collector/...`
- **Key details**:
  - OpenTelemetry Java agent attached to every service (via JVM `-javaagent:` flag in Dockerfile)
  - OTel Collector deployed as DaemonSet on EKS, exports traces to X-Ray via `awsxray` exporter
  - SQS message attributes carry `traceId` and `traceparent` headers; producers set them in outbox publishers, consumers extract on receipt
  - gRPC client/server interceptors propagate trace context in metadata
  - Sampling: tail-based 1% in prod, 100% on errors (`sampler.error_rate=1.0`)
- **Acceptance criteria**: Single end-to-end order shows up as one trace in X-Ray spanning Order → Promotion → Payment → Kitchen → Delivery.
- **Dependencies**: 7.2

### Step 7.4: SLO-based alerts + runbooks
- [ ] **Objective**: Define SLOs and alert when error budget is at risk. Link every alert to a runbook.
- **Files to create**:
  - `food-delivery-gitops/apps/observability/alerts/slo-orders.yaml` (PrometheusRule)
  - `food-delivery-gitops/apps/observability/alerts/slo-payments.yaml`
  - `food-delivery-gitops/apps/observability/alerts/slo-platform.yaml`
  - `food-delivery-gitops/apps/observability/runbooks/order-success-rate.md`
  - `food-delivery-gitops/apps/observability/runbooks/payment-circuit-open.md`
  - `food-delivery-gitops/apps/observability/runbooks/saga-stuck.md`
  - `platform-infra/envs/shared/sns-pagerduty.tf`
- **Key details**:
  - Top SLOs: Order success rate ≥ 99.5%, Checkout p99 latency < 3s, Payment success rate ≥ 99.9%, API availability ≥ 99.95%
  - Burn rate alerting: 2% budget burn in 1h = page on-call; 5% in 6h = page secondary
  - Page severity: SEV1 (paging) for SLO-impacting alerts only; SEV2 (Slack) for non-SLO operational issues
  - SNS topic → PagerDuty integration via webhook; secondary topic → Slack via AWS Chatbot
  - Each runbook: alert description, dashboard link, common causes, mitigation steps, escalation path
- **Acceptance criteria**: Inject 5% checkout failures via fault injection → burn-rate alert fires → PagerDuty receives page → runbook link present.
- **Dependencies**: 7.3

---

## Phase 8: CI/CD on AWS

> Goal: every service has a fully AWS-native pipeline triggered from the **single** `food-delivery-platform` monorepo via path-filtered EventBridge rules. **No GitHub Actions anywhere.** All pipelines write image-tag bumps to the companion `food-delivery-gitops` repo, which ArgoCD reconciles to EKS.
>
> **Note**: Steps 8.1, 8.2, and 8.3 below were pulled forward into the user-service pilot (executed during Phase 2). The path-filter Lambda, buildspec templates, and user-service pipeline already exist by the time Phase 8 starts. Phase 8 covers: (a) canary rollouts (Step 8.4), (b) replicating pipelines to the remaining 4 v1 services (Step 8.5), (c) pipeline + ArgoCD notifications (Step 8.6). Steps 8.1–8.3 here remain as a reference for what the pilot work delivered.

### Step 8.1: CodeCommit access policies + path-filter Lambda + IAM cross-cutting
- [ ] **Objective**: Configure access controls on the two CodeCommit repos created in Step 0.1, build the path-filter Lambda that decides which pipelines to start on a given commit, and finalize CI IAM roles. (CodeArtifact and most IAM was already provisioned in Step 0.9 — this step is the CI-pipeline-specific glue.)
- **Files to create**:
  - `platform-infra/envs/shared/codecommit-policies.tf` (approval rules, branch protection)
  - `platform-infra/modules/path-filter-lambda/main.tf`
  - `platform-infra/modules/path-filter-lambda/src/handler.py`
  - `platform-infra/envs/shared/eventbridge-codecommit.tf`
  - `platform-infra/envs/shared/iam-cicd-extra.tf` (additional roles for path-filter Lambda + cross-pipeline triggers)
- **Key details**:
  - **Path-filter Lambda** receives EventBridge `CodeCommit Repository State Change` events for `food-delivery-platform`. It uses `git diff --name-only` (via the CodeCommit GetDifferences API) between the previous and new commits to determine which top-level directories changed.
  - Routing logic the Lambda implements:
    - Touched `services/{name}/**` → start `pipeline-{name}`
    - Touched `platform-shared-libs/**` or `platform-bom/**` → start ALL 10 service pipelines (parallel)
    - Touched `platform-infra/**` → start `pipeline-platform-infra` (Terraform plan + apply with manual approval)
    - Touched `e2e-tests/**` → start `pipeline-e2e-tests`
  - The Lambda calls `codepipeline:StartPipelineExecution` on each affected pipeline.
  - **Approval rules** on `food-delivery-platform/main`: 1 approval required, all status checks (build of changed services) must pass before merge.
  - **Approval rules** on `food-delivery-gitops/main`: 1 approval required before merge.
  - SSH key (already provisioned in Step 0.11) is what ArgoCD uses to read `food-delivery-gitops`. CodeBuild gitops-bump jobs use a separate, write-scoped IAM user with HTTPS git credentials in Secrets Manager.
- **Acceptance criteria**: Pushing a commit that touches only `services/user-service/**` triggers `pipeline-user-service` and no other pipelines. Pushing a commit that touches `platform-bom/pom.xml` triggers all v1 service pipelines (5 in v1; this fan-out grows as v2/v3 add services).
- **Dependencies**: 0.9, 0.11

### Step 8.2: Reusable buildspec templates + monorepo helper scripts
- [ ] **Objective**: Build the reusable CodeBuild buildspecs and the monorepo-aware shell scripts every pipeline uses.
- **Files to create**:
  - `platform-infra/buildspec-templates/build-test-scan.yml`
  - `platform-infra/buildspec-templates/integration-test.yml`
  - `platform-infra/buildspec-templates/package-and-push.yml`
  - `platform-infra/buildspec-templates/gitops-bump.yml`
  - `platform-infra/buildspec-templates/smoke-test.yml`
  - `platform-infra/scripts/check-coverage.py`
  - `platform-infra/scripts/wait-for-inspector-scan.sh`
  - `platform-infra/scripts/maven-build-affected.sh` (wrapper around `mvn -pl ... -am` that figures out the right scope from the `SERVICE_PATH` env var)
- **Key details**:
  - `build-test-scan.yml`: Java 25 install (Corretto), CodeArtifact login, `mvn -B -pl $SERVICE_PATH -am verify -Pcoverage`, OWASP Dependency-Check on the service module, JaCoCo gate ≥ 80%, CodeGuru Security scan, fail on Critical/High.
  - `integration-test.yml`: brings up Testcontainers (PostgreSQL, Redis, **Kafka via Confluent image**) inside the build container plus LocalStack (SQS, SNS, DDB), runs `mvn -B -pl $SERVICE_PATH -Pintegration-test`.
  - `package-and-push.yml`: `cd $SERVICE_PATH && docker buildx build --platform linux/amd64,linux/arm64 ...`, tag = git SHA, push to ECR.
  - `gitops-bump.yml`: clones `food-delivery-gitops`, edits `apps/$SERVICE/overlays/production/image-tag.yaml`, commits with `chore(deploy): $SERVICE → $TAG`, pushes. ArgoCD picks up within ~1 minute.
  - `smoke-test.yml`: hits service health endpoint in target env, runs k6 perf script with SLO thresholds.
  - All buildspecs use the **monorepo root** as `CODEBUILD_SRC_DIR` and rely on `SERVICE_PATH` (e.g., `services/user-service`) being set per pipeline.
- **Acceptance criteria**: A test service using these buildspecs builds, tests, scans, and pushes an image to ECR successfully. Modifying a shared lib triggers a rebuild of dependent services thanks to `mvn -am`.
- **Dependencies**: 8.1

### Step 8.3: CodePipeline Terraform module + first pipeline (user-service staging)
- [ ] **Objective**: Reusable Terraform module that defines the full pipeline; instantiate for user-service as the proving ground.
- **Files to create**:
  - `platform-infra/modules/service-pipeline/main.tf`
  - `platform-infra/modules/service-pipeline/variables.tf`
  - `platform-infra/modules/service-pipeline/codebuild-projects.tf`
  - `platform-infra/modules/service-pipeline/pipeline-stages.tf`
  - `platform-infra/modules/service-pipeline/eventbridge-trigger.tf`
  - `platform-infra/envs/production/pipelines/identity-pipeline.tf`
- **Key details**:
  - Module variables: `service_name`, `service_path` (e.g., `services/user-service`), `java_version` (default `25`), `has_database` (toggles PG Testcontainer), `has_grpc`, `has_kafka` (toggles Kafka Testcontainer).
  - The module **does not** create its own EventBridge rule — instead it registers the pipeline with the path-filter Lambda from 8.1 (via SSM Parameter Store config).
  - Pipeline stages match `architecture.md` Section 10.4: Source (CodeCommit `food-delivery-platform`) → Build → Test (parallel) → IntegrationTest → SAST/SCA → PackageAndPush → InspectorScan → DeployApproval → Deploy (gitops bump) → SmokeTest.
  - Inspector scan as a Lambda action that polls Inspector v2 API for image findings, fails on Critical.
  - All artifacts stored in a per-pipeline S3 bucket with KMS encryption + 90-day lifecycle.
  - Pipeline events to EventBridge → SNS → Slack via Chatbot.
- **Acceptance criteria**: Push to `services/user-service/**` on the monorepo's main branch triggers `pipeline-user-service`, which runs all stages → image lands in ECR → `food-delivery-gitops` gets the bump commit → ArgoCD deploys to EKS → smoke test passes.
- **Dependencies**: 8.2, 2.5

### Step 8.4: Manual approval gate + Argo Rollouts canary
- [ ] **Objective**: Add a manual approval gate before deploy and progressive canary via Argo Rollouts.
- **Files to create**:
  - `food-delivery-gitops/apps/user-service/overlays/production/rollout.yaml`
  - `food-delivery-gitops/apps/_argo-rollouts/install.yaml`
  - `food-delivery-gitops/apps/_argo-rollouts/analysis-template-error-rate.yaml`
- **Key details**:
  - CodePipeline manual approval action before the deploy stage; SNS topic `deploy-approvals` notifies via Slack and email.
  - Approver IAM group `deployers` (audited via CloudTrail).
  - Argo Rollouts `Rollout` replaces `Deployment`; canary 10% → 50% → 100% with 5/10/0 minute pauses.
  - AnalysisTemplate queries Prometheus for error rate; `errorRate > 0.01` aborts and rolls back automatically.
  - Auto-rollback also tied to CloudWatch alarm via Lambda hook (defense in depth).
- **Acceptance criteria**: Deploy user-service via approval gate. Canary progresses through 10/50/100. Inject errors during 50% phase → automated rollback.
- **Dependencies**: 8.3

### Step 8.5: Replicate pipelines for the remaining 4 v1 services
- [ ] **Objective**: Use the Terraform module to create pipelines for the remaining 4 v1 services (product, basket, payment, order). The user-service pipeline already exists from the pilot. Each pipeline is one short module instantiation; the path-filter Lambda (Step 8.1) handles the trigger fan-out.
- **Files to create**:
  - `platform-infra/envs/production/pipelines/product-pipeline.tf`
  - `platform-infra/envs/production/pipelines/basket-pipeline.tf`
  - `platform-infra/envs/production/pipelines/payment-pipeline.tf`
  - `platform-infra/envs/production/pipelines/order-pipeline.tf`
- **Key details**:
  - Each `.tf` file is ~12 lines: instantiates the `service-pipeline` module from Step 8.3 with `service_name`, `service_path`, `has_kafka`, `has_database`, `has_grpc` flags.
  - **Payment** pipeline has a business-hours gate on production deploys (Lambda check before approval action) — payment failures hurt more during off-hours.
  - **Order Service** pipeline has an additional integration test stage running full saga simulation with **Testcontainers Kafka** + LocalStack.
  - All pipelines source from `food-delivery-platform` (monorepo); the path-filter Lambda determines which one(s) to start on a given commit.
  - **v2/v3/v4 services** get their pipelines added the same way when they ship — one short `.tf` file per new service. No new pipeline infrastructure work.
- **Acceptance criteria**: All 5 v1 service pipelines visible in CodePipeline console. Pushing to `services/{any v1 service}/**` triggers the matching pipeline only. Pushing to `platform-shared-libs/**` triggers all 5.
- **Dependencies**: 8.4
- **Dependencies**: 8.4

### Step 8.6: Pipeline notifications + ArgoCD sync notifications
- [ ] **Objective**: All pipeline events surface in Slack and PagerDuty appropriately.
- **Files to create**:
  - `platform-infra/envs/shared/eventbridge-pipeline-events.tf`
  - `platform-infra/envs/shared/aws-chatbot.tf`
  - `food-delivery-gitops/argocd/notifications/configmap.yaml`
  - `food-delivery-gitops/argocd/notifications/triggers.yaml`
- **Key details**:
  - EventBridge rules match `aws.codepipeline` events; route to SNS topic by severity.
  - AWS Chatbot configured with Slack workspace; `pipeline-events` for CI builds, `prod-deploys` for approval gate and deploy notifications.
  - Failed prod pipeline → PagerDuty page on-call SRE.
  - ArgoCD Notifications service sends sync events: `OutOfSync` warning to Slack after 5 min, `Degraded` page to PagerDuty.
  - Weekly pipeline metrics report: deployment frequency, lead time, change failure rate, MTTR (DORA metrics) — generated by scheduled Lambda → email.
- **Acceptance criteria**: Trigger a failing build → Slack notification appears within 30s. Force a prod ArgoCD app to Degraded → PagerDuty page received.
- **Dependencies**: 8.5

---

## Phase 9: End-to-End Testing

> Goal: automated test suites validate the three core flows (happy path, cancel, error) on every deploy. Load testing uncovers capacity limits before real traffic does.

### Step 9.0: Per-service test scaffolding (close audit §12)
- [ ] **Objective**: Close the cross-cutting testability gap identified in `docs/API_AUDIT.md` §12. Every HTTP-exposing service gets controller-slice tests, repository-slice tests, and at least one happy-path service-layer test. The Saga end-to-end integration test comes in Step 9.1.
- **Files to create** (per service that has HTTP endpoints — user, order, product, basket, kitchen, delivery, review, promotion):
  - `services/{name}/src/test/java/.../api/{Resource}ControllerTest.java` — `@WebMvcTest` per controller; covers happy path + validation errors + 4xx responses; mocks the service layer
  - `services/{name}/src/test/java/.../domain/{Resource}RepositoryTest.java` — `@DataJpaTest` for JPA services (user, product, order, delivery, promotion); DynamoDB Enhanced Client tests with LocalStack for kitchen and review
  - `services/{name}/src/test/java/.../service/{Resource}ServiceTest.java` — happy path of the main service class with Mockito for dependencies
  - `services/{name}/src/test/java/.../config/IntegrationTestBase.java` — shared Testcontainers setup (Postgres, Redis, Kafka per service needs) reused across IT tests
- **Key details**:
  - **Coverage target**: ≥ 80% line coverage gate per service (already configured in `buildspec-build-test-scan.yml`); per-class minimum 70% on `service/` and `api/` packages.
  - **JUnit 5 + AssertJ** as the standard. No legacy JUnit 4. AssertJ for assertions everywhere, NOT JUnit `assertEquals`.
  - **Mockito 5+**; no PowerMock.
  - **Testcontainers** for any test crossing a DB/queue/cache boundary. Reuse strategy: `IntegrationTestBase` declares static containers shared across tests in the same module.
  - **Contract tests** (DTO schema): one test per service that asserts the wire shape of `ApiError`, the main request/response DTOs, and any saga events. Catches accidental breaking changes before they ship.
  - The audit's call for "Saga integration test" lives in Step 9.1 — it's an end-to-end test across services, not a per-service scaffold concern.
  - **Sequencing**: this step is broken down per-service in practice. The single build-plan checkbox represents "all services have the slice + service-layer + contract tests passing in CI." Mark done only when the coverage gate is green for every service.
- **Acceptance criteria**: Every service's pipeline shows ≥ 80% line coverage in JaCoCo report. Every service has at least one `@WebMvcTest`, one repository slice or Testcontainers-backed test, one service-layer happy-path test, and one ApiError-contract test. Placeholder `contextLoads()` tests removed from every service.
- **Dependencies**: 11.5 (every per-service phase complete, audit gaps closed)

### Step 9.1: E2E happy path test (full order lifecycle)
- [ ] **Objective**: Postman/Newman or k6 script that drives a full order from registration to delivery against EKS.
- **Files to create**:
  - `e2e-tests/scenarios/01-happy-path.js` (k6)
  - `e2e-tests/lib/auth-helper.js`
  - `e2e-tests/lib/order-helper.js`
  - `e2e-tests/lib/driver-simulator.js`
  - `e2e-tests/buildspec.yml`
- **Key details**:
  - Test creates fresh customer + restaurant + driver accounts via Identity API (or uses pre-seeded test accounts)
  - Sequence: register → login → search restaurant → add 3 items to basket → checkout → poll order until PAID (max 10s) → restaurant marks ticket READY → driver claims → driver delivers → assert state DELIVERED
  - Each step has SLO assertions (p95 latency, max time-to-state)
  - Driver simulator polls the available-tasks endpoint and races; verifies only one driver wins
  - Reads order timeline from Order Service to verify all expected state transitions happened
  - Runs as a CodePipeline post-deploy stage after every deploy
- **Acceptance criteria**: Test passes consistently. Failures clearly identify which service/step caused failure.
- **Dependencies**: 11.4

### Step 9.2: E2E cancel and error flow tests
- [ ] **Objective**: Test scripts that exercise the cancel path and the payment-failure compensation path.
- **Files to create**:
  - `e2e-tests/scenarios/02-cancel-before-prepare.js`
  - `e2e-tests/scenarios/03-cancel-after-prepare-rejected.js`
  - `e2e-tests/scenarios/04-payment-failure-compensation.js`
  - `e2e-tests/lib/stripe-test-helper.js`
- **Key details**:
  - Cancel-before-prepare: place order → cancel within 30s → assert state `CANCELED`, refund recorded in Payment ledger, promo code restored
  - Cancel-after-prepare: place order → kitchen accepts → cancel → assert 409
  - Payment failure: use Stripe test card `4000000000000341` (succeeds at first then chargeback) → trigger Stripe webhook for `charge.failed` via Stripe CLI → assert order state `FAILED` after compensation, all 4 compensations ack'd, kitchen ticket canceled, basket restored, customer notified
  - Each scenario also verifies CloudWatch logs and X-Ray trace contain expected trace IDs
- **Acceptance criteria**: All three scenarios pass. State transitions and side effects validated programmatically.
- **Dependencies**: 14.1

### Step 9.3: Load testing with k6 + Distributed Load Testing on AWS
- [ ] **Objective**: Capacity test the critical paths to find the breaking point before production traffic does.
- **Files to create**:
  - `load-tests/scenarios/checkout-load.js`
  - `load-tests/scenarios/menu-read-load.js`
  - `load-tests/scenarios/driver-claim-race.js`
  - `load-tests/Dockerfile`
  - `platform-infra/envs/load-test/load-test-runner.tf` (uses Distributed Load Testing on AWS solution)
- **Key details**:
  - Checkout load: 100 → 500 → 1000 → 2000 RPS ramp; assertions on p99 latency < 3s, error rate < 0.1%
  - Menu read load: 5000 RPS sustained; verify cache hit rate stays > 90%, RDS not touched
  - Driver claim race: 1000 concurrent drivers all claiming the same task; verify exactly 1 wins, response times stable
  - Tests run against an isolated `load-test` environment (separate VPC, separate AWS account ideally)
  - Results stored in CloudWatch + Grafana load-test dashboard
  - Schedule: runs weekly on a cron via EventBridge → CodeBuild
- **Acceptance criteria**: Load tests run to completion. Identify and document the breaking RPS for each scenario. Capacity plan updated with findings.
- **Dependencies**: 14.2

### Step 9.4: Chaos engineering with AWS Fault Injection Simulator
- [ ] **Objective**: Validate resilience patterns work under real failure conditions.
- **Files to create**:
  - `chaos/experiments/kill-payment-pod.json` (FIS template)
  - `chaos/experiments/inject-stripe-latency.json`
  - `chaos/experiments/network-partition-redis.json`
  - `chaos/experiments/db-failover.json`
  - `chaos/runbook.md`
- **Key details**:
  - FIS templates run against EKS — schedule during low-traffic periods
  - Kill payment pod during active charge → verify retries succeed, no duplicate charges (idempotency works)
  - Inject 5s latency on Stripe → verify circuit breaker opens, fallback returns 503 (not timeout)
  - Network-partition Redis → verify Basket service degrades to 503 instead of hanging, cart data preserved on heal
  - Aurora failover → verify all services reconnect, no transactions lost
  - Each experiment has pre-conditions, expected behaviors, rollback steps, success metrics
  - Run monthly during business hours with on-call engineer present
- **Acceptance criteria**: All four experiments execute without permanent damage. Resilience patterns observed working as designed. Findings documented.
- **Dependencies**: 14.3

---

## Phase 10: Production Hardening

> Goal: production-ready security posture, disaster recovery validated, runbooks complete. By end of phase the system can be opened to real customer traffic.

### Step 10.1: WAF rules + bot protection + DDoS mitigation
- [ ] **Objective**: Comprehensive AWS WAF Web ACL on API Gateway and ALBs.
- **Files to create**:
  - `platform-infra/modules/waf/main.tf`
  - `platform-infra/envs/production/waf.tf`
- **Key details**:
  - AWS Managed Rules: Common Rule Set, Known Bad Inputs, SQL Injection, Linux/Unix attacks, Account Takeover Prevention
  - Custom rules: rate limit per IP (2000 req / 5 min), per ASN suspicious traffic, geo-block where business doesn't operate
  - Bot Control: targeted protection enabled, mark `aws:bot:category=verified` as allowed, challenge unknown bots
  - Login endpoint rate limit: 10 req/min per IP (defense against credential stuffing)
  - AWS Shield Advanced subscription on prod (DDoS protection + 24/7 DRT)
  - Logs to S3 + Athena queryable for forensics
- **Acceptance criteria**: WAF deployed in count-mode first for 1 week, then switched to block-mode after tuning. Penetration test from Step 10.2 doesn't bypass WAF.
- **Dependencies**: 9.4

### Step 10.2: Security audit + penetration testing
- [ ] **Objective**: External pentest, AWS security review, fix findings.
- **Files to create**:
  - `security/audit-checklist.md`
  - `security/pentest-scope-of-work.md`
  - `security/findings-log.md` (track + resolve)
  - `platform-infra/envs/shared/guardduty.tf`
  - `platform-infra/envs/shared/security-hub.tf`
  - `platform-infra/envs/shared/iam-access-analyzer.tf`
- **Key details**:
  - Engage external pentest vendor for OWASP Top 10 + AWS-specific tests (S3 misconfigurations, IAM privilege escalation, IMDS access)
  - Enable GuardDuty across all accounts; enable Security Hub with AWS Foundational Security Best Practices standard
  - IAM Access Analyzer scans for resources shared with external accounts
  - IAM least-privilege review: every IRSA role audited, unused permissions removed
  - Secrets review: all secrets in Secrets Manager with rotation enabled, no plaintext credentials anywhere
  - Track findings in CSV with severity (Critical, High, Medium, Low), owner, due date; CI fails on unresolved Critical/High
- **Acceptance criteria**: Pentest report received. All Critical/High findings resolved. Security Hub score > 90%. GuardDuty alerts wired to PagerDuty for High severity.
- **Dependencies**: 10.1

### Step 10.3: Backup, disaster recovery, and runbook validation
- [ ] **Objective**: Verify backups work, document RTO/RPO, run a DR drill.
- **Files to create**:
  - `platform-infra/envs/shared/backup.tf` (AWS Backup plans)
  - `dr/dr-plan.md`
  - `dr/runbooks/restore-rds.md`
  - `dr/runbooks/restore-dynamodb.md`
  - `dr/runbooks/restore-s3.md`
  - `dr/dr-drill-template.md`
- **Key details**:
  - AWS Backup plan: daily backups of RDS, DynamoDB (PITR), S3 (replication to second region), 35-day retention prod
  - RTO targets: critical services (Identity, Order, Payment) ≤ 1 hour; others ≤ 4 hours
  - RPO targets: ≤ 5 minutes for transactional data (Aurora PITR, DynamoDB streams replay)
  - Cross-region replica for Aurora (Aurora Global Database) and DynamoDB Global Tables (when business justifies — document trigger criteria)
  - DR drill: quarterly, restore a snapshot to a separate VPC, run E2E happy path against restored data, document time taken
  - Runbooks include exact CLI commands, prerequisites, verification steps
- **Acceptance criteria**: First DR drill completes. Actual RTO measured and documented. Runbooks validated as executable by someone other than the author.
- **Dependencies**: 10.2

### Step 10.4: Production launch checklist + on-call rotation
- [ ] **Objective**: Operational readiness — define on-call, training, launch criteria.
- **Files to create**:
  - `ops/launch-checklist.md`
  - `ops/oncall-runbook.md`
  - `ops/severity-levels.md`
  - `ops/incident-response-template.md`
  - `ops/post-mortem-template.md`
- **Key details**:
  - Launch checklist categories: technical (all SLOs green for 7 days, all chaos experiments pass, all P1/P2 bugs resolved), operational (on-call rotation defined, runbooks tested, escalation paths documented), business (legal/compliance sign-off, customer support trained, marketing ready)
  - On-call rotation: primary + secondary, 1-week shifts, hand-off doc template
  - Severity levels: SEV1 (customer-impacting outage, page immediately), SEV2 (degraded but workaround exists), SEV3 (minor, next business day), SEV4 (cosmetic)
  - Incident response template: communication channels, roles (IC, scribe, comms), update cadence
  - Post-mortem template: timeline, contributing factors, action items with owners; blameless culture explicit
  - Launch sign-off requires checklist 100% complete, signed by Eng Lead + Product Lead + SRE Lead
- **Acceptance criteria**: Checklist 100% complete. Two consecutive weeks of zero SEV1 incidents. Production launch approved.
- **Dependencies**: 10.3

---

# VERSION 2 — Restaurant Operations (+ kitchen, delivery)

> v2 adds the restaurant-side operational flow. After v2: customers place orders, restaurants accept and prepare them, drivers claim and complete deliveries. The order-service saga expands to include `KITCHEN_ACCEPTED`, `FOOD_READY`, `OUT_FOR_DELIVERY`, `DELIVERED` states, with additional compensation paths (cancel kitchen ticket, free driver).
>
> **No new architectural patterns introduced** — v2 reuses everything from v1. Two new services follow `docs/service-deploy-template.md`. The saga expands but the pattern is the same.
>
> Total: ~15 steps. Estimated 3–4 weeks.

## Phase 11: Kitchen Service

> Goal: restaurants accept tickets when orders are paid, mark them through preparation states, and have capacity-based auto-pause for overloaded restaurants.

### Step 11.1: kitchen-service skeleton + DynamoDB schema
- [ ] **Objective**: Spring Boot service backed by DynamoDB for tickets and capacity counters. Follow `docs/service-deploy-template.md` from v1 pilot.
- **Files**: standard service skeleton per template. Tables: `tickets` (PK=`restaurantId`, SK=`ticketId`, GSI on `state`), `restaurant-capacity` (PK=`restaurantId`, attributes `active_count` atomic counter, `paused` boolean, `pause_threshold` default 20).
- **Key details**: Ticket states `ACCEPTED`, `PREPARING`, `READY_FOR_PICKUP`, `CANCELED`. Outbox table on DDB (Streams-driven publisher via Lambda — first use of this pattern in v2; document it in the deploy template as a v2 addition).
- **Infrastructure** (deferred from Step 0.5 — provision here when the service is built):
  - Add to `platform-infra/envs/{env}/dynamodb.tf` using the `dynamodb-table` module from Step 3.1:
    - `tickets`: PK=`ticket_id` (S), GSI on `restaurant_id` (S) → `created_at` (S), streams enabled, KMS CMK
    - `outbox-kitchen`: PK=`event_id` (S), streams enabled (`NEW_AND_OLD_IMAGES`), KMS CMK
- **Acceptance**: insert ticket, list by state, atomic counter increment work.
- **Dependencies**: 1.4, 2.7

### Step 11.2: ORDER_COMPLETED listener → create ticket; ticket lifecycle endpoints
- [ ] **Objective**: Consume `ORDER_COMPLETED` from Kafka (v1 emits this), create ticket in `ACCEPTED`. Expose `PATCH /v1/tickets/{id}/status` for state transitions.
- **Key details**: idempotent insert with `attribute_not_exists(SK)`. Atomic capacity increment. Transition to `READY_FOR_PICKUP` writes outbox row `FOOD_READY` to Kafka `kitchen-events`.
- **Dependencies**: 11.1, 6.7

### Step 11.3: Capacity-based auto-pause + auto-resume
- [ ] **Objective**: When `active_count > pause_threshold`, emit `RESTAURANT_PAUSED` (consumed by product-service to hide the restaurant). Idle for 30 min with `active_count < threshold/2` → emit `RESTAURANT_RESUMED`.
- **Dependencies**: 11.2

### Step 11.4: CANCEL_KITCHEN_TICKET compensation handler
- [ ] **Objective**: Consume compensation from SQS `kitchen-compensation` queue (provisioned in v2 — add to Step 0.8's Terraform). Cancel ticket, free capacity, emit `TICKET_CANCELED` ack.
- **Dependencies**: 11.3

### Step 11.5: K8s manifests + deployment + audit gaps + dashboard
- [ ] **Objective**: Deploy via GitOps. Follow `service-deploy-template.md`. Build kitchen dashboard.
- **Dependencies**: 11.4, 8.5

## Phase 12: Delivery Service

> Goal: drivers race to claim tasks (first-wins via `FOR UPDATE NOWAIT`), status updates flow in order, free driver on completion.

### Step 12.1: delivery-service skeleton + RDS schema
- [ ] **Objective**: Spring Boot project. PG tables: `delivery_tasks(id, order_id UNIQUE, restaurant_id, customer_address, status, driver_id NULL, broadcast_at, claimed_at, picked_up_at, delivered_at, version)` and `driver_status(driver_id, online, last_heartbeat_at, current_task_id NULL)`.
- **Dependencies**: 1.4, 2.7

### Step 12.2: FOOD_READY listener → create task → broadcast push
- [ ] **Objective**: Consume `FOOD_READY` from Kafka `kitchen-events` → insert task → broadcast SNS Mobile Push to online drivers (FCM/APNS, provisioned in v2 Terraform addendum).
- **Dependencies**: 12.1, 11.2

### Step 12.3: Claim endpoint with FOR UPDATE NOWAIT race resolution
- [ ] **Objective**: `POST /v1/delivery/tasks/{id}/claim` — first driver wins via `SELECT FOR UPDATE NOWAIT`. Losers get 409.
- **Acceptance**: race test with 100 concurrent claims → exactly 1 success.
- **Dependencies**: 12.2

### Step 12.4: Status update endpoint + FIFO queue + RELEASE_DRIVER compensation
- [ ] **Objective**: Driver status updates per-driver-ordered via SQS FIFO (`MessageGroupId = driverId`). On `ORDER_DELIVERED`: emit outbox event, free driver. Compensation listener for `RELEASE_DRIVER`: revert task to `BROADCAST`.
- **Dependencies**: 12.3

### Step 12.5: K8s manifests + audit + dashboard + deployment
- [ ] **Objective**: Deploy via GitOps. Standard manifest set.
- **Dependencies**: 12.4, 8.5

## Phase 13: Expand order-service saga

> Goal: extend v1's mini-saga to handle the kitchen + delivery states. v1 used `PAID → COMPLETED` directly; v2 inserts `PAID → KITCHEN_ACCEPTED → FOOD_READY → OUT_FOR_DELIVERY → DELIVERED` between them. Multiple compensation paths now possible.

### Step 13.1: Add states + transitions to StateMachineConfig
- [ ] **Objective**: Expand `OrderState` enum from 6 states to 10. Add transitions for kitchen and delivery progression. Adjust `expected_compensation_acks` JSONB to support multi-ack compensation.
- **Migration**: V5 SQL adds new state values to CHECK constraint (Postgres can't add enum values atomically; use varchar + constraint).
- **Dependencies**: 6.7, 11.2, 12.4

### Step 13.2: Listeners for kitchen + delivery events; expand CompensationService
- [ ] **Objective**: Add `KitchenEventListener` (consumes `kitchen-events`) and `DeliveryEventListener` (consumes `delivery-events`). Expand `CompensationService.compensationsFor()` to determine which compensations apply based on current state.
- **Key details**: CompensationPlan now returns 1-3 commands depending on state. From `KITCHEN_ACCEPTED`: cancel kitchen ticket + restore basket. From `OUT_FOR_DELIVERY`: release driver + cancel kitchen ticket + restore basket. Each handler idempotent.
- **Dependencies**: 13.1

### Step 13.3: Update saga dashboard for v2 states + extended E2E tests
- [ ] **Objective**: Update Grafana dashboard panels to show all 10 states. Add E2E tests covering: full happy path (order → kitchen → delivery → delivered), payment failure (already tested in v1), kitchen rejection (new), delivery failure (new).
- **Dependencies**: 13.2

## Phase 14: v2 wrap-up

### Step 14.1: v2 observability + alerts
- [ ] **Objective**: Per-service dashboards for kitchen and delivery. Update SLO alerts to include the longer happy-path latency (now: customer → delivered = end-to-end).
- **Dependencies**: 13.3

### Step 14.2: v2 production hardening
- [ ] **Objective**: Run kitchen and delivery through the production hardening process (WAF rules, IAM audit, DR runbooks). Same template as v1's Phase 10; no new patterns.
- **Dependencies**: 14.1

### Step 14.3: v2 production launch
- [ ] **Objective**: Deploy kitchen + delivery + the expanded order saga. Soak 7 days. SLO green for the new flows. Update launch checklist with v2 considerations.
- **Dependencies**: 14.2

---

# VERSION 3 — Engagement features (+ review, promotion, notification)

> v3 adds three services that enrich the customer experience: reviews after delivery, promotion codes (welcome offers, loyalty), and email/push notifications. No new architectural patterns; promotion-service introduces the first cross-version "service that listens to USER_CREATED and reacts" example.
>
> Total: ~13 steps. Estimated 3–4 weeks.

## Phase 15: Review Service

### Step 15.1: review-service skeleton + DynamoDB schema
- [ ] **Objective**: Spring Boot service. DDB tables: `reviews` (PK=`REVIEW#{type}#{entityId}`, SK=`{orderId}#{userId}`, GSI on `(userId, submittedAt)`), `review-aggregates` (PK=`REVIEW_AGG#{type}#{entityId}`, attrs: count, sum, avg, histogram, lastUpdated).
- **Infrastructure** (deferred from Step 0.5 — provision here when the service is built):
  - Add to `platform-infra/envs/{env}/dynamodb.tf` using the `dynamodb-table` module:
    - `reviews`: PK=`review_id` (S), streams enabled (`NEW_AND_OLD_IMAGES`), KMS CMK
    - `review-aggregates`: PK=`restaurant_id` (S), KMS CMK
- **Dependencies**: 1.4, 2.7

### Step 15.2: ORDER_DELIVERED listener + REST CRUD endpoints
- [ ] **Objective**: Open review window on `ORDER_DELIVERED` (v2 event). Endpoints: POST review, PATCH within 24h editableUntil window, GET by entity (paginated per audit §7), GET by user. Profanity filter via S3 word list.
- **Key details**: enforces uniqueness via PK+SK (one review per user-order-entity tuple). Surfaces duplicate as 409 `ApiError {code: REVIEW_ALREADY_SUBMITTED}`.
- **Dependencies**: 15.1, 13.2

### Step 15.3: Aggregation Lambda via DynamoDB Streams
- [ ] **Objective**: Lambda subscribes to `reviews` DDB Stream. On INSERT/MODIFY/REMOVE: atomically update aggregate counters. Compute `avg = sum/count` lazily on read.
- **Dependencies**: 15.2

### Step 15.4: K8s + audit gaps + deployment
- [ ] **Objective**: Standard deployment. Aggregator Lambda via SAM.
- **Dependencies**: 15.3

## Phase 16: Promotion Service

### Step 16.1: promotion-service skeleton + DB schema
- [ ] **Objective**: PG tables: `promo_codes(id, user_id, code, code_type, discount_type, amount, currency, min_order_amount, valid_from, valid_until, state)` UNIQUE `(user_id, code_type)`, `promo_redemptions(id, promo_code_id, order_id)` UNIQUE `(promo_code_id, order_id)`.
- **Dependencies**: 1.4, 2.7

### Step 16.2: USER_CREATED listener → welcome code issuance
- [ ] **Objective**: Kafka listener on `user-events` (consumer group `promotion-service`). On `USER_CREATED`: insert welcome promo code (20% off, max $10, valid 30 days). Idempotent on unique `(userId, codeType)`. Emit `PROMO_ISSUED` to `promotion-events`.
- **Dependencies**: 16.1, 2.5

### Step 16.3: gRPC validation endpoints + integrate into order-service
- [ ] **Objective**: Expose `ValidateCode`, `RedeemCode`, `RestoreCode` gRPC methods. Update order-service: during order creation, call `ValidateCode`; during saga `PAID` transition, call `RedeemCode`; during compensation, call `RestoreCode` (new compensation type added to saga).
- **Note**: this is the v3 update to order-service's saga — `RESTORE_PROMO_CODE` becomes a new compensation action.
- **Dependencies**: 16.2, 13.2

### Step 16.4: K8s + audit + deployment
- **Dependencies**: 16.3

## Phase 17: Notification Service (Lambda)

### Step 17.1: notification-service Lambda skeleton + SAM template
- [ ] **Objective**: AWS SAM project. Java 25 runtime, 512 MB, 30s timeout. MSK event source mappings for: `user-events` (welcome emails), `order-events` (receipts), `kitchen-events` (status pushes), `delivery-events` (delivery pushes), `payment-events` (failure emails). SQS event source for webhook-driven cases (v4 will add Stripe `charge.refunded` here).
- **Dependencies**: 1.4

### Step 17.2: Template engine + S3 templates + render
- [ ] **Objective**: Mustache templates fetched from S3, cached in warm container. Localized via `{templateId}.{locale}.mustache` with `en` fallback. Subject lines as separate S3 objects.
- **Dependencies**: 17.1

### Step 17.3: Idempotency + send paths (SES email + SNS Mobile Push)
- [ ] **Objective**: Conditional write to `notification-idempotency` DDB table with `attribute_not_exists(idem_key)`. SES `SendEmail` for email. SNS Mobile Push for FCM/APNS. EventRouter maps event types to (template, channel, recipient).
- **Infrastructure** (deferred from Step 0.5 — provision here when the service is built):
  - Add to `platform-infra/envs/{env}/dynamodb.tf`: `notification-idempotency` table: PK=`idempotency_key` (S), TTL attribute=`expires_at` (7-day expiry), KMS CMK
- **Dependencies**: 17.2

### Step 17.4: CodePipeline for Lambda (SAM-based) + deploy
- [ ] **Objective**: Lambda-specific pipeline variant (uses `service-pipeline-lambda` Terraform module from v3 addendum). CodeDeploy canary alias 10% for 10 minutes. Auto-rollback on `Errors` alarm.
- **Dependencies**: 17.3, 8.6

---

# VERSION 4 — Payment Hardening

> v4 graduates the minimal payment-service from v1 to production-grade. Adds: full idempotency ledger (all 6 entry types — `AUTHORIZED`, `REFUNDED`, `DISPUTED` join the v1 set), Stripe webhook handling with signature verification, full Resilience4j stack (circuit breaker + retry + bulkhead + rate limiter + time limiter), refund flows, DDB Streams-based outbox publisher.
>
> The migration runs the new hardened payment-service alongside the v1 minimal one, gradually routing traffic via feature flag, then retires v1.
>
> Total: ~7 steps. Estimated 2–3 weeks.

## Phase 18: payment-service v2 — full ledger + webhooks + resilience

### Step 18.1: Add new ledger entry types + GSIs
- [ ] **Objective**: Extend `payment-ledger` to support `AUTHORIZED`, `REFUNDED`, `DISPUTED`. Add new GSIs needed for refund lookups. Migration is backwards-compatible — new entry types coexist with existing.
- **Dependencies**: 5.4

### Step 18.2: Stripe webhook handler with signature verification
- [ ] **Objective**: `POST /v1/webhooks/stripe` endpoint. Verify `Stripe-Signature` header. Replay protection (ignore events > 5 min old). Idempotency on Stripe event ID. Supported events: `charge.failed`, `charge.refunded`, `charge.dispute.created`, `payment_intent.succeeded`.
- **Infra**: provision SNS topic `stripe-webhooks` → SQS `payment-webhooks` (deferred from v1 Step 0.8). API Gateway route + WAF for the webhook endpoint.
- **Dependencies**: 18.1

### Step 18.3: Full Resilience4j stack on Stripe client
- [ ] **Objective**: Replace v1's basic retry+timeout with the full stack: circuit breaker (50% failure / 20 calls / 60s open), retry (3 attempts, exp backoff), rate limiter (25 req/s per pod), bulkhead (charge=20, refund=10 separate semaphores), time limiter (5s).
- **Dependencies**: 18.2

### Step 18.4: Refund endpoint + DDB Streams outbox publisher Lambda
- [ ] **Objective**: `POST /v1/payments/refund` endpoint (idempotent on `(idempotency_key)`). Replace v1's polling outbox with DDB Streams-driven Lambda: stream from `outbox-payment` table, batches up to 100 records, publishes to MSK `payment-events`.
- **Dependencies**: 18.3

## Phase 19: Migrate from minimal payment to hardened payment

### Step 19.1: Feature flag gradual rollout
- [ ] **Objective**: AWS AppConfig (or LaunchDarkly) feature flag `use-hardened-payment` per environment. Order-service reads the flag when emitting `CHARGE_PAYMENT` and routes to either v1's queue (`charge-payment`) or v2's queue (`charge-payment-v2`). Both payment services run side by side.
- **Dependencies**: 18.4

### Step 19.2: Migrate staging traffic
- [ ] **Objective**: Flag traffic to 100% v2. Soak 7 days. Validate dashboards show no regressions. Validate refund flow against Stripe test mode.
- **Dependencies**: 19.1

### Step 19.3: Migrate production traffic (canary)
- [ ] **Objective**: Production rollout: 10% → 50% → 100% over 2 weeks, monitoring payment success rate, latency p99, error rate at each step. Same pattern as v1's Argo Rollouts canary.
- **Dependencies**: 19.2

## Phase 20: v4 wrap-up

### Step 20.1: Retire v1 minimal payment-service
- [ ] **Objective**: After v2 has 100% traffic for 30 days with green SLOs, remove v1 payment-service. Decommission `charge-payment` queue. Update order-service to remove the feature flag (v2 is now the only path).
- **Dependencies**: 19.3

### Step 20.2: Update documentation
- [ ] **Objective**: `docs/payment-service.md` reflects the v2 architecture exclusively. v1 minimal payment moves to `docs/historical/payment-service-v1.md` for reference. Update saga diagrams to show real refund path on cancel.
- **Dependencies**: 20.1

---

# Final Notes

## Estimated Total Effort

### Version 1 (the bulk of the work — also where the patterns get established)

| Phase | Steps | Approx Sessions | Parallel Possible? |
|---|---|---|---|
| 0 — Foundation | 11 | 11 | Mostly sequential (IaC dependencies) |
| 1 — Shared Libs + BOM | 4 | 4 | Yes after 1.1 |
| 2 — User Service (pilot) | 7 | 7+ | Sequential; pilot also includes pulled-forward 7.1 + 8.1–8.3 |
| 3 — Product Service | 5 | 5 | Sequential; can run alongside 4 |
| 4 — Basket Service | 5 | 5 | Sequential; depends on 3.4 |
| 5 — Payment (minimal) | 4 | 4 | Sequential; smaller than v4's version |
| 6 — Order Service (mini-saga) | 7 | 7 | Sequential — the critical path of v1 |
| 7 — Observability | 4 | 4 | 7.1 done in pilot; 7.2 is per-service, parallelizable across 4 services |
| 8 — CI/CD on AWS | 6 | 6 | 8.1–8.3 done in pilot; 8.5 fans out across services |
| 9 — End-to-End Testing | 5 | 5 | 9.0 (per-service test scaffolding) parallelizable |
| 10 — Production Hardening | 4 | 4 | Mostly sequential |
| **v1 total** | **62** | **~62 sessions** | **~6–8 weeks** for one engineer; ~5 weeks with 2 engineers |

> **Pilot weight**: the user-service pilot (Phase 2 expanded, plus pulled-forward 7.1 + 8.1–8.3) is roughly 11–12 sessions on its own. Budget extra time here because the first service surfaces IRSA, Kustomize, observability, and CI/CD friction that subsequent v1 services then dodge. The investment pays back across the remaining 4 v1 services AND every v2/v3/v4 service.

### Version 2 — Restaurant operations (kitchen + delivery + expanded saga)

| Phase | Steps | Approx Sessions | Parallel Possible? |
|---|---|---|---|
| 11 — Kitchen Service | 5 | 5 | Sequential, but can run alongside Phase 12 |
| 12 — Delivery Service | 5 | 5 | Sequential, can run alongside Phase 11 |
| 13 — Expand order-service saga | 3 | 3 | Depends on 11.2 + 12.4 |
| 14 — v2 wrap-up | 3 | 3 | Sequential — soak + launch |
| **v2 total** | **16** | **~16 sessions** | **~3–4 weeks** |

### Version 3 — Engagement (review + promotion + notification)

| Phase | Steps | Approx Sessions | Parallel Possible? |
|---|---|---|---|
| 15 — Review Service | 4 | 4 | Sequential |
| 16 — Promotion Service | 4 | 4 | Can run alongside Phase 15 |
| 17 — Notification (Lambda) | 4 | 4 | Sequential, depends on 15.2 + 16.2 |
| **v3 total** | **12** | **~12 sessions** | **~3–4 weeks** |

### Version 4 — Payment hardening (graduate the v1 minimal payment)

| Phase | Steps | Approx Sessions | Parallel Possible? |
|---|---|---|---|
| 18 — payment-service v2 (full) | 4 | 4 | Sequential |
| 19 — Migrate traffic to v2 | 3 | 3 | Sequential — soak + canary |
| 20 — v4 wrap-up | 2 | 2 | Sequential — retire v1, docs |
| **v4 total** | **9** | **~9 sessions** | **~2–3 weeks** |

### Grand total across all four versions

| Version | Steps | Approx Duration | What you have at the end |
|---|---|---|---|
| v1 | 62 | 6–8 weeks | Full production stack: user, product, basket, payment (minimal), order with mini-saga. Customers can register, browse, add to cart, place orders. Real saga, real AWS, real CI/CD, real observability. |
| v2 | 16 | 3–4 weeks | + kitchen + delivery + expanded saga. Restaurants prepare orders, drivers deliver. End-to-end ordering platform. |
| v3 | 12 | 3–4 weeks | + reviews + promotions + notifications. Customer engagement features. Feature-complete platform for end users. |
| v4 | 9 | 2–3 weeks | + production-grade payment (webhooks, refunds, full resilience). The platform is now hardened end-to-end. |
| **Total** | **99** | **~15–18 weeks** | A 10-service production microservices platform on AWS. |

**Each version is independently shippable.** You can ship v1 to production, run it for months, and only then decide whether v2/v3/v4 is worth the time. v1 is a real food-delivery platform — not a prototype.

## How to Run a Session

1. Open a fresh Claude Code session.
2. Paste this prompt:
   > I'm working on the food ordering microservices platform. Please complete **Step X.Y** from `build-plan.md`, exactly as specified. Honor all dependency requirements, file paths, and acceptance criteria. If anything in the step is ambiguous, ask before proceeding.
3. Attach `build-plan.md` to the session, plus `architecture.md` if the step references a specific section from it. For most steps you only need `build-plan.md`.
4. Let Claude Code work; review each file as it's produced; run tests locally before committing.
5. Mark the step done by changing `- [ ]` → `- [x]` in this file. Commit `build-plan.md` to track progress.

## Mid-Step Recovery

If a session runs out of tokens mid-step:
1. Save what Claude Code has produced so far.
2. Start a new session with the prompt:
   > I was implementing **Step X.Y** from `build-plan.md` and ran out of tokens. Here is what's already produced: [paste files]. Please continue from where it stopped, honoring the same acceptance criteria.

## Avoiding Common Mistakes

- **Don't combine steps.** Each is sized deliberately; combining two often blows the token budget.
- **Don't skip `architecture.md` reference reads.** Sub-step decisions reference design choices documented there.
- **Don't deploy database migrations and code together.** Migrate first (backwards-compatible), then deploy code, then a follow-up cleanup.
- **Don't skip integration tests** — they catch the cross-service issues unit tests can't.
- **Don't disable resilience patterns** to "get it working faster." The compensation flow is the system's reason to exist.

---

*End of build-plan.md. Total: 99 build steps across 21 phases (0–20). Reference architecture in companion file `architecture.md`.*