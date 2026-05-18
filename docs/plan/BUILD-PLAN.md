# Food Ordering System — Build Plan for Claude Code

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
food-ordering-platform        ← all code, shared libs, infra (Terraform), e2e tests
food-ordering-gitops          ← K8s manifests for ArgoCD to reconcile
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

**CI/CD doesn't get more complex.** Each service still has its own CodePipeline; pipelines use **path-filtered EventBridge triggers** so a push to `services/order-service/**` only triggers `order-pipeline`. A push to `common-libs/**` or `platform-bom/**` triggers all 10 service pipelines (rebuild everything that consumed the changed lib). One pipeline per service, just pointed at a single repo with a path filter — same fan-out as polyrepo.

The single-repo monorepo layout is documented in detail below in **Phase 0 Step 0.1** and **Phase 1**.

### Why ArgoCD

ArgoCD is a **GitOps controller** for Kubernetes. It runs *inside* the EKS cluster, watches the `food-ordering-gitops` repo, and continuously reconciles the cluster's actual state to match what's declared in Git. Three properties make it the right choice for this platform:

1. **Git is the source of truth.** Every production change is a Git commit. The full audit trail and rollback story comes for free — `git revert` undoes a deploy.
2. **Pull-based deploys.** The cluster pulls from Git; CI never holds Kubernetes credentials. Better security posture than push-based deploys (`kubectl apply` from a CI job).
3. **Continuous reconciliation + drift detection.** If someone hand-edits a deployment in production via `kubectl`, ArgoCD detects the drift and either alerts or auto-corrects. The cluster cannot drift from declared state for long.

**Additional benefits used in this plan:**
- **App-of-Apps pattern** — one root ArgoCD `Application` declares child Applications for each service, so adding a new service is one PR to the gitops repo.
- **Argo Rollouts** (companion project) handles canary deploys: 10% → 50% → 100% with automated rollback on SLO breach. Used in Phase 13.
- **Sync waves** — ArgoCD applies resources in declared order, so namespaces and CRDs land before Deployments.
- **SSO via OIDC** — Cognito or Okta integration for the ArgoCD UI, no shared admin password.
- **Notifications** — ArgoCD's Notifications service sends sync events to Slack and PagerDuty (Phase 13.6).

ArgoCD is open source (CNCF graduated) and runs perfectly fine on EKS via Helm. There's no equivalent fully-AWS-native GitOps controller — AWS CodeDeploy can deploy to EKS but it's push-based and lacks reconciliation. We use AWS for everything else; ArgoCD is the one pragmatic exception.

### What is AWS CodeArtifact?

CodeArtifact is AWS's managed artifact repository — equivalent to JFrog Artifactory or Sonatype Nexus, but serverless and AWS-native. It supports Maven, npm, NuGet, PyPI, and generic packages. In this plan it serves three purposes:

1. **Hosts the platform BOM and shared libraries.** When a CI build publishes `platform-bom:1.5.0` or `common-events:2.1.0`, it goes to CodeArtifact. Service builds resolve them from there.
2. **Caches Maven Central artifacts.** Instead of every CodeBuild job hitting Maven Central directly, CodeArtifact proxies it. Faster, more reliable, and reproducible (you can pin to a specific snapshot of upstream).
3. **Authentication via IAM.** No separate credentials to manage — CodeBuild jobs and developer machines both auth via `aws codeartifact login --tool maven --domain {org}-platform --repository internal`.

Cost is minimal — pennies per GB stored plus per-request fees. Set up once in Phase 0, used by every subsequent build.


---

## Build Strategy

> Read this section before starting Phase 0. It explains how the 85 build steps are sequenced and a few cross-cutting decisions that apply to every phase. The plan below makes more sense once you've absorbed these.

### Spring profile strategy (three profiles plus one edge case)

Every service runs under one of three Spring profiles. The profile determines the **shape** of dependencies — what beans load, whether IAM auth is enabled, whether TLS is required. The profile does **NOT** carry environment-specific values like hostnames, passwords, or topic names. Those come from environment variables, populated by Kubernetes from ConfigMaps and Secrets (via External Secrets Operator pulling from AWS Secrets Manager) in staging/production, and from `.env` files or `application-local.yml` placeholders during local development.

The three profiles:

- **`local`** — JVM runs on a developer laptop. Dependencies are Docker Compose containers: local PostgreSQL on `localhost:5432`, local Redis with no TLS, local Kafka with `PLAINTEXT` (no IAM auth), LocalStack for AWS APIs. This is the tight dev loop.
- **`staging`** — Service runs on EKS staging. Talks to real AWS staging resources: Aurora staging cluster, ElastiCache staging cluster, MSK staging cluster with IAM auth + TLS, real DynamoDB, real SNS/SQS/S3. No LocalStack.
- **`production`** — Service runs on EKS production. Same shape as staging but pointed at production resources via env vars.

Plus one edge-case profile, used sparingly:

- **`local-aws`** — JVM runs on a developer laptop, but AWS SDK calls hit **real AWS staging** instead of LocalStack. For occasional debugging — usually when something reproduces against real DynamoDB or real MSK but not against LocalStack. Activated explicitly via `-Dspring.profiles.active=local-aws`; not the default for any developer.

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

`application-staging.yml`:

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

The build does NOT take all 10 services through each phase in parallel. Instead, **user-service goes end-to-end through staging first**, including its CI/CD pipeline and its observability dashboard. This is the pilot. Once user-service is fully running in staging with all its supporting infrastructure, you pause and capture what you learned. Then services 2–10 follow the template.

The pilot covers, in order:

1. Phase 0 (foundation IaC, complete)
2. Phase 1 (shared libs + BOM, complete)
3. Phase 2 (user-service implementation through K8s deploy to staging)
4. **From Phase 12**: Step 12.1 (Managed Prometheus + Grafana backend) and a user-service dashboard
5. **From Phase 13**: Steps 13.1, 13.2, 13.3 (path-filter Lambda, buildspec templates, user-service pipeline)
6. **Checkpoint**: write `docs/service-deploy-template.md` capturing the IRSA setup, Kustomize overlay structure, ServiceAccount/ExternalSecret/ServiceMonitor patterns, and the buildspec wiring that worked. This becomes the template for services 2–10.

The reason for this sequencing: the first service surfaces problems that the other nine will then dodge cheaply. IRSA is fiddly the first time. Kustomize overlay structure crystallizes during user-service and becomes a copy-paste template afterward. The first ServiceMonitor and first dashboard establish conventions. Get them right once on the pilot; replicate cleanly on services 2–10.

**Practical impact on Phase 12 and Phase 13**: Phase 12's Step 12.1 and Phase 13's Steps 13.1–13.3 are moved into the pilot work (executed during the user-service phase). The original Phase 12 and Phase 13 still exist — they cover *cross-cutting* observability (X-Ray, SLO alerts) and *replicating* pipelines/dashboards to services 2–10. References to those steps stay; only the execution order changes.

### Expect the first service to be harder than the rest

Three things will be harder on user-service than on any subsequent service. Knowing this in advance prevents the "I must be doing something wrong" feeling:

- **IRSA wiring is fiddly the first time.** Five things need to align — trust policy, ServiceAccount annotation, EKS OIDC provider, pod volume mount, SDK credential chain. Get one wrong and the SDK errors are uninformative. Budget time. Subsequent services are copy-paste from the first.
- **Kustomize overlay structure crystallizes during user-service.** Decisions about what goes in `base/` vs `overlays/`, how env config is templated, how secrets reference External Secrets — all get made during user-service and stay. Spend time getting them right; capture in `docs/service-deploy-template.md`.
- **Observability scaffolding is one-time work.** First ServiceMonitor, first PrometheusRule, first Grafana dashboard. Templates emerge. Don't skip observability on the pilot — you'll lose context on what was happening in staging without it.

### API audit gaps are handled inline per service

The platform's API audit (`docs/API_AUDIT.md`) identified specific gaps in services that already exist locally. Rather than treating remediation as a separate phase, each service phase that has identified gaps gets a **dedicated audit step at the end** that addresses them. Specifically:

- **Phase 2 — user-service**: audit step covers global exception handler, `@Valid` on registration, typed `RegisterResponse` with 202 status (audit §1, §5, §6 for user)
- **Phase 6 — basket-service**: audit step covers idempotent add-item via upsert-by-productId (audit §9 for basket)
- **Phase 8 — order-service**: audit step covers `@RestControllerAdvice` with `OrderNotFoundException`, `Page<OrderResponseDto>` with `Pageable` on list endpoint, filter by status/date (audit §5, §7, §9, §11 for order)
- **Phase 11 — review-service**: audit step covers unique `(orderId, userId)` constraint, pagination on `GET /reviews/orders/{orderId}` (audit §7, §9 for review)

Two pieces of cross-cutting audit infrastructure ride in Phase 1's shared libraries:

- **`ApiError` record** in `common-exceptions` — replaces the private record currently in `product-service`'s exception handler; consumed by user-service and order-service's new handlers
- **`IdempotencyKeyFilter`** in `common-resilience` — the Spring AOP aspect already planned in Step 1.3 doubles as the filter the audit recommends for order-service (and optionally basket and review)

The audit's priority order — testability → order idempotency → user validation → exception handlers → order pagination — is reflected in the sequencing. Testability is highest priority but requires services to exist, so it's addressed in Phase 14 (cross-cutting test scaffolding) plus the per-service slice tests added in each per-service audit step.

### Staging-only on the first pass

Per-service phases (2 through 11) deploy ONLY to staging. Production deploys batch in Phase 15 (Production Hardening), once every service has been observed running stably in staging, dashboards are green, SLOs are tracked, and security/DR work is complete. This is already implicit in the plan — most service phases say "deploy to staging" — but worth being explicit: do not deploy any service to production until Phase 15.

The only exception is the pilot CI/CD work (Step 13.3 in the pilot context): it sets up user-service's staging pipeline. Production pipeline + canary rollouts come later via Step 13.4.

---

## Table of Contents

- [Build Strategy](#build-strategy)
- [Phase 0: Foundation & Infrastructure](#phase-0-foundation--infrastructure)
- [Phase 1: Shared Libraries & Platform BOM](#phase-1-shared-libraries--platform-bom)
- [Phase 2: User Service](#phase-2-user-service)
- [Phase 3: Notification Service (Lambda)](#phase-3-notification-service-lambda)
- [Phase 4: Promotion & Loyalty Service](#phase-4-promotion--loyalty-service)
- [Phase 5: Product (Restaurant Menu) Service](#phase-5-product-restaurant-menu-service)
- [Phase 6: Basket Service](#phase-6-basket-service)
- [Phase 7: Payment Service](#phase-7-payment-service)
- [Phase 8: Order Orchestrator Service](#phase-8-order-orchestrator-service)
- [Phase 9: Kitchen Service](#phase-9-kitchen-service)
- [Phase 10: Delivery (Dispatch) Service](#phase-10-delivery-dispatch-service)
- [Phase 11: Review & Feedback Service](#phase-11-review--feedback-service)
- [Phase 12: Observability](#phase-12-observability)
- [Phase 13: CI/CD on AWS](#phase-13-cicd-on-aws)
- [Phase 14: End-to-End Testing](#phase-14-end-to-end-testing)
- [Phase 15: Production Hardening](#phase-15-production-hardening)
- [Estimated Total Effort](#estimated-total-effort)
- [How to Run a Session](#how-to-run-a-session)

---

# PART B — BUILD STEPS

> Each step below is sized for one Claude Pro session. Mark a step done by changing `- [ ]` to `- [x]`. Steps within a phase that have no dependency on each other can be parallelized across multiple sessions.

## Phase 0: Foundation & Infrastructure

> Goal: provision all shared AWS infrastructure with Terraform before writing a single line of application code. By the end of this phase, you have a working EKS cluster with databases, queues, and ArgoCD ready to receive deployments.

### Step 0.1: Monorepo bootstrap & developer prerequisites
- [ ] **Objective**: Initialize the `food-ordering-platform` monorepo skeleton, the `food-ordering-gitops` companion repo, document local developer setup, and lock in the three-profile convention (`local`, `staging`, `production`).
- **Files to create**:
  - `food-ordering-platform/README.md` (top-level overview, links to the plan)
  - `food-ordering-platform/.gitignore`
  - `food-ordering-platform/.terraform-version` (1.7.5)
  - `food-ordering-platform/.tool-versions` (asdf format: `java corretto-25`, `maven 3.9.x`, `terraform 1.7.5`, `kubectl 1.30.x`, `helm 3.15.x`)
  - `food-ordering-platform/scripts/bootstrap-dev.sh` (installs awscli, kubectl, helm, terraform, sam, mvn)
  - `food-ordering-platform/docs/developer-setup.md`
  - `food-ordering-platform/docs/architecture.md` (copy of the architecture reference, maintained alongside the code)
  - `food-ordering-platform/docs/spring-profiles.md` (the three-profile convention; describes `local` / `staging` / `production` / `local-aws`)
  - `food-ordering-platform/.envrc.template` (direnv: AWS_PROFILE, AWS_REGION, CODEARTIFACT_AUTH_TOKEN refresh, SPRING_PROFILES_ACTIVE=local default)
  - `food-ordering-platform/docker-compose.yml` (root-level: Postgres, Redis, Kafka in KRaft mode, LocalStack; used by every service when running under the `local` profile)
  - `food-ordering-platform/dev/seed/` (seed data + scripts for local dev)
  - Empty top-level dirs: `services/`, `common-libs/`, `platform-bom/`, `platform-infra/`, `e2e-tests/`
  - `food-ordering-gitops/README.md` (companion repo, watched by ArgoCD)
  - `food-ordering-gitops/.gitignore`
  - Empty top-level dirs in gitops repo: `apps/`, `argocd/`
- **Key details**:
  - Two CodeCommit repos: `food-ordering-platform` (code+infra+tests), `food-ordering-gitops` (K8s manifests). Both initialized with a commit per the layout in `architecture.md` Section 10.1.
  - **Spring profile convention** (locked in here so subsequent service phases follow it): three profiles — `local` (Docker Compose deps + LocalStack), `staging` (real AWS staging), `production` (real AWS production). Plus `local-aws` as a sparingly-used edge case (JVM local, AWS calls hit real staging). See "Build Strategy" section above. `docs/spring-profiles.md` documents the rule "profile controls shape, env vars provide values" with an example.
  - Document the AWS account layout (single account v1, multi-account in Phase 5).
  - Naming convention: `{org}-{env}-{service}-{resource}` for every AWS resource.
  - Tag every AWS resource with `Project=food-ordering`, `Environment={dev|staging|prod}`, `Service={service-name}`, `Owner={team}`, `CostCenter={code}`.
  - The `bootstrap-dev.sh` should idempotently install all dev tools and run `aws codeartifact login --tool maven --domain {org}-platform --repository internal` once Phase 0.8 has provisioned CodeArtifact.
  - Branch protection on `main` for both repos: 1 approval required, status checks must pass, no force-pushes.
- **Acceptance criteria**: Both CodeCommit repos exist with the documented top-level structure. New developer can clone the platform repo, run `scripts/bootstrap-dev.sh`, and end up with all tools installed at correct versions. `docker-compose up` brings up local dependencies. `docs/spring-profiles.md` clearly describes the three profiles and the shape-vs-values rule.
- **Dependencies**: none

### Step 0.2: Terraform — VPC and networking
- [ ] **Objective**: Provision a multi-AZ VPC with public, private, and isolated subnets.
- **Files to create**:
  - `platform-infra/modules/vpc/main.tf`
  - `platform-infra/modules/vpc/variables.tf`
  - `platform-infra/modules/vpc/outputs.tf`
  - `platform-infra/envs/staging/network.tf`
  - `platform-infra/envs/production/network.tf`
- **Key details**:
  - 3 AZs in chosen region
  - CIDR layout: `10.0.0.0/16` per env. Public `/24`s, private `/22`s, isolated `/24`s for RDS
  - One NAT Gateway per AZ (cost vs HA tradeoff: 3 NATs in prod, 1 in staging)
  - Internet Gateway for public subnets
  - VPC endpoints for S3, DynamoDB, ECR, Secrets Manager, SNS, SQS, STS, Logs (gateway + interface)
  - Flow Logs enabled, sent to CloudWatch
  - Default deny-all security group used as base
- **Acceptance criteria**: `terraform plan` produces clean diff. `terraform apply` succeeds. `aws ec2 describe-vpcs` shows the new VPC.
- **Dependencies**: 0.1

### Step 0.3: Terraform — EKS cluster on Fargate
- [ ] **Objective**: Provision EKS cluster with Fargate-only profiles, IRSA, and core add-ons.
- **Files to create**:
  - `platform-infra/modules/eks/main.tf`
  - `platform-infra/modules/eks/fargate-profiles.tf`
  - `platform-infra/modules/eks/addons.tf`
  - `platform-infra/envs/staging/eks.tf`
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
- [ ] **Objective**: Provision a shared Aurora PostgreSQL cluster used by Identity, Order, Promotion, Delivery (one DB per service in the same cluster).
- **Files to create**:
  - `platform-infra/modules/rds-aurora/main.tf`
  - `platform-infra/modules/rds-aurora/variables.tf`
  - `platform-infra/envs/staging/databases.tf`
  - `platform-infra/envs/production/databases.tf`
- **Key details**:
  - Aurora PostgreSQL 16, Serverless v2 with min 0.5 ACU, max 4 ACU (staging) / 2-32 ACU (prod)
  - Multi-AZ in prod, single-AZ in staging for cost
  - In isolated subnets only — no public access
  - Master password in Secrets Manager with rotation Lambda
  - Per-service databases: `identity_db`, `order_db`, `promotion_db`, `delivery_db`
  - Per-service IAM users (created by separate migration step later)
  - Performance Insights enabled, 7-day retention
  - Automated backups: 7 days staging, 35 days prod
- **Acceptance criteria**: Can `psql` from a bastion or EKS pod using credentials from Secrets Manager.
- **Dependencies**: 0.2

### Step 0.5: Terraform — DynamoDB tables
- [ ] **Objective**: Create all DynamoDB tables used by Menu, Kitchen, Payment, Review, and Notification idempotency.
- **Files to create**:
  - `platform-infra/modules/dynamodb-table/main.tf`
  - `platform-infra/envs/staging/dynamodb.tf`
  - `platform-infra/envs/production/dynamodb.tf`
- **Key details**:
  - On-demand billing for all tables
  - Tables to create: `menus`, `tickets` (Kitchen), `payment-ledger`, `outbox-payment`, `outbox-kitchen`, `reviews`, `review-aggregates`, `notification-idempotency`
  - Streams enabled on `menus`, `outbox-payment`, `outbox-kitchen`, `reviews`
  - Point-in-time recovery on `payment-ledger`
  - GSI on `payment-ledger`: `idempotency_key` for duplicate detection
  - GSI on `tickets`: `restaurant_id` for "list active tickets per restaurant"
  - TTL attribute on `notification-idempotency` (7-day expiry)
  - Server-side encryption with KMS CMK per table
- **Acceptance criteria**: All tables visible in console with correct keys, indexes, and streams.
- **Dependencies**: 0.2

### Step 0.6: Terraform — ElastiCache Redis cluster
- [ ] **Objective**: Provision a shared Redis cluster used by Basket (primary store), Menu (cache), and rate limiting.
- **Files to create**:
  - `platform-infra/modules/elasticache-redis/main.tf`
  - `platform-infra/envs/staging/cache.tf`
  - `platform-infra/envs/production/cache.tf`
- **Key details**:
  - Redis 7.x, Cluster Mode enabled (sharding for horizontal scale)
  - 2 shards in staging, 4 shards in prod, with 1 replica per shard
  - In-transit encryption on (TLS), at-rest encryption on
  - Auth via Redis AUTH token in Secrets Manager
  - In private subnets, security group allows EKS pods only
  - Snapshot retention: 1 day staging, 7 days prod
  - Slow log + engine log to CloudWatch
- **Acceptance criteria**: Can connect from EKS pod with `redis-cli --tls -h <endpoint> -p 6379 -a <token>`.
- **Dependencies**: 0.2

### Step 0.7: Terraform — Amazon MSK (managed Kafka) cluster
- [ ] **Objective**: Provision the MSK cluster that hosts the domain-event backbone (`identity-events`, `order-events`, `payment-events`, `kitchen-events`, `delivery-events`, `promotion-events`, `driver-status`).
- **Files to create**:
  - `platform-infra/modules/msk/main.tf`
  - `platform-infra/modules/msk/variables.tf`
  - `platform-infra/modules/msk/topics.tf` (uses `confluentinc/confluent` provider OR a Lambda that calls `kafka-topics` after cluster ready)
  - `platform-infra/envs/staging/kafka.tf`
  - `platform-infra/envs/production/kafka.tf`
- **Key details**:
  - **Staging**: MSK Serverless cluster (cheaper, auto-scales, pay-per-throughput). Suitable while traffic is low.
  - **Production**: MSK Provisioned with 3 brokers (`kafka.m7g.large` ARM-based for cost), Multi-AZ, in private subnets.
  - Topics to create with sensible defaults:
    - `identity-events` (3 partitions staging / 6 prod, retention 7 days, key=userId)
    - `order-events` (6 partitions staging / 12 prod, retention 14 days, key=orderId — important for per-order ordering)
    - `payment-events` (3 partitions / 6, retention 30 days for audit, key=orderId)
    - `kitchen-events` (3 / 6, retention 7 days, key=restaurantId)
    - `delivery-events` (3 / 6, retention 7 days, key=orderId)
    - `promotion-events` (3 / 3, retention 7 days, key=userId)
    - `driver-status` (12 partitions both envs, retention 1 day, key=driverId)
  - **Authentication**: IAM (IRSA-friendly) — no SASL/SCRAM passwords to manage. Each service's IRSA role gets per-topic produce/consume permissions.
  - **Encryption**: in-transit (TLS 1.2+) and at-rest (KMS CMK).
  - **Schema management**: AWS Glue Schema Registry, Avro format for all topics. Producers/consumers reference schema ID, not embedded schema.
  - **Monitoring**: enhanced monitoring (`PER_TOPIC_PER_PARTITION`), broker logs to CloudWatch, JMX metrics scraped by Prometheus.
  - **Connectivity**: private endpoints only (no public bootstrap). EKS pods connect via interface VPC endpoint.
  - Replication factor 3 in prod, 2 in staging.
- **Acceptance criteria**: From an EKS pod with the right IRSA, can `kafka-console-producer` to `identity-events` and `kafka-console-consumer` from another pod sees the message. Glue Schema Registry shows registered Avro schemas for each event type.
- **Dependencies**: 0.2, 0.3

### Step 0.8: Terraform — SNS topics, SQS queues, DLQs (compensation + webhook intake)
- [ ] **Objective**: Provision the SNS/SQS messaging used for compensation commands, Stripe webhook intake, and the few simple fan-out cases that don't justify a Kafka topic.
- **Files to create**:
  - `platform-infra/modules/sns-sqs-pair/main.tf` (creates topic + queue + subscription + DLQ)
  - `platform-infra/envs/staging/messaging-sns-sqs.tf`
  - `platform-infra/envs/production/messaging-sns-sqs.tf`
- **Key details**:
  - **Compensation queues** (point-to-point from Order Orchestrator): `kitchen-compensation`, `promotion-compensation`, `basket-compensation`, `payment-refund`, `delivery-compensation`. Each has a DLQ with `maxReceiveCount = 5`.
  - **Stripe webhook intake**: SNS topic `stripe-webhooks` → SQS queue `payment-webhooks` (consumed by Payment Service). Buffers webhooks, smooths spikes, allows replay from DLQ.
  - **Driver mobile push fan-out**: SNS Mobile Push platform applications for FCM/APNS. Used by Delivery Service to broadcast tasks.
  - **Notification edge cases**: SQS queue `notification-stripe-webhooks` for webhook-driven emails (e.g., refund completed) — Notification Lambda has both MSK *and* SQS triggers.
  - All messages KMS-encrypted with platform-wide CMK.
  - CloudWatch alarms on every DLQ depth > 0.
  - **Note**: The previously-planned SQS FIFO queue for driver status is replaced by the Kafka `driver-status` topic (Step 0.7) — better throughput per key and replay capability for analytics.
- **Acceptance criteria**: Can publish to SNS via CLI and observe message arriving in subscribed SQS. DLQ receives messages after 5 failed receives.
- **Dependencies**: 0.2

### Step 0.9: Terraform — ECR repositories, IAM roles, CodeArtifact domain
- [ ] **Objective**: Create one ECR repo per service plus the shared IAM roles for CodeBuild and CI processes, plus the CodeArtifact domain that hosts the BOM and shared libraries.
- **Files to create**:
  - `platform-infra/modules/ecr-repo/main.tf`
  - `platform-infra/envs/shared/ecr.tf`
  - `platform-infra/envs/shared/iam-cicd.tf`
  - `platform-infra/envs/shared/codeartifact.tf`
- **Key details**:
  - 10 ECR repos (one per service): `user-service`, `product-service`, ..., `notification-service`
  - Image scan on push enabled (Inspector v2)
  - Lifecycle policy: keep last 30 untagged images, keep tags `prod-*` forever
  - Image tag immutability enabled
  - **CodeArtifact domain**: `{org}-platform`. Two repos:
    - `internal` — where `platform-bom` and `common-libs/*` modules publish.
    - `maven-central` — public upstream proxy. The `internal` repo declares `maven-central` as upstream so transitive deps resolve through it.
  - CodeBuild service role with permissions for ECR push, CodeArtifact read+publish, S3 artifact bucket, **MSK produce/consume during integration tests**.
  - CodePipeline service role.
  - Shared KMS CMK for image encryption + CodeArtifact encryption.
- **Acceptance criteria**: `aws ecr describe-repositories` lists 10 repos. `aws codeartifact list-repositories-in-domain --domain {org}-platform` shows both repos. CodeBuild role can be assumed.
- **Dependencies**: 0.1

### Step 0.10: Terraform — API Gateway and ALB foundation
- [ ] **Objective**: Provision the public API Gateway plus internal ALBs for service ingress.
- **Files to create**:
  - `platform-infra/modules/api-gateway/main.tf`
  - `platform-infra/envs/staging/api.tf`
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
- [ ] **Objective**: Install ArgoCD on the EKS cluster and wire it to the `food-ordering-gitops` CodeCommit repo.
- **Files to create**:
  - `platform-infra/scripts/install-argocd.sh`
  - `food-ordering-gitops/argocd/install/values.yaml`
  - `food-ordering-gitops/argocd/projects/services.yaml`
  - `food-ordering-gitops/argocd/applications/_app-of-apps.yaml`
  - `food-ordering-gitops/README.md`
- **Key details**:
  - Install ArgoCD via Helm chart, version pinned (2.10+).
  - Configure SSO via OIDC (Cognito or Okta).
  - Disable insecure admin UI; expose only via internal ALB.
  - Generate SSH key pair, store private key in Secrets Manager, public key on the IAM user used by ArgoCD to read the gitops repo.
  - **App-of-Apps pattern**: one root Application (`_app-of-apps.yaml`) declares child Applications for each service × env. Adding a new service is one PR to the gitops repo.
  - **AppProject `services`** restricts which namespaces/repos child apps can use — defense against misconfigured child app pointing at, say, `kube-system`.
  - Install **Argo Rollouts** controller alongside ArgoCD (enables canary deploys in Phase 13.4).
  - Configure **ArgoCD Notifications** controller pointing at SNS for Slack/PagerDuty fan-out (wired up fully in Phase 13.6).
- **Acceptance criteria**: ArgoCD UI accessible via internal ALB with SSO login. Root app shows 0 children initially. Adding a placeholder child app via PR to `food-ordering-gitops` causes ArgoCD to create the namespace within ~1 minute.
- **Dependencies**: 0.3, 0.9

---

## Phase 1: Shared Libraries & Platform BOM

> Goal: stand up the Maven reactor at the monorepo root, publish the platform BOM (which pins every dependency version platform-wide), and build out the shared libraries every service depends on. From here on, every service POM has *no version numbers* — they all come from the BOM.

### Step 1.1: Root reactor POM + platform-bom (Bill of Materials)
- [ ] **Objective**: Create the root `pom.xml` (Maven reactor for the entire monorepo), the `platform-bom` module that pins all dependency versions, and configure CodeArtifact publication. This is the foundation everything else inherits from.
- **Files to create**:
  - `food-ordering-platform/pom.xml` (root reactor — declares `<modules>` for `platform-bom`, `common-libs`, all services)
  - `food-ordering-platform/platform-bom/pom.xml` (BOM with `<dependencyManagement>` only, no source code)
  - `food-ordering-platform/common-libs/pom.xml` (parent for shared modules — empty `<modules>` for now, populated in 1.2–1.4)
  - `food-ordering-platform/.mvn/maven.config` (passes `-Pcoverage` etc. consistently)
  - `food-ordering-platform/.mvn/settings.xml` (template; CodeArtifact mirror config)
  - `food-ordering-platform/scripts/codeartifact-login.sh`
  - `food-ordering-platform/scripts/publish-bom.sh`
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
  - **Reactor module list** in root POM: `platform-bom`, `common-libs/*`, `services/*`. The reactor is what makes `mvn -pl services/order-service -am verify` work — Maven knows the dependency graph and rebuilds upstream modules if needed.
  - The `.mvn/settings.xml` template uses CodeArtifact as the mirror for everything, so CI builds never hit Maven Central directly.
- **Acceptance criteria**: `mvn -B verify` from the monorepo root succeeds (modules empty but reactor resolves). `mvn -B deploy -pl platform-bom -DskipTests` publishes `platform-bom:1.0.0-SNAPSHOT` to CodeArtifact. A throwaway service POM in `services/test-service/` that imports the BOM resolves all listed dependencies without specifying versions.
- **Dependencies**: 0.9

### Step 1.2: common-events, common-dto, and common-exceptions modules
- [ ] **Objective**: Define shared event payload types, common DTOs with schema versioning, and the shared error envelope (`ApiError`) used by every service's exception handler. These are the wire contracts every service shares.
- **Files to create**:
  - `common-libs/common-events/pom.xml`
  - `common-libs/common-events/src/main/java/.../events/UserCreatedEvent.java`
  - `common-libs/common-events/src/main/java/.../events/OrderPaidEvent.java`
  - `common-libs/common-events/src/main/java/.../events/PaymentSuccessEvent.java`
  - `common-libs/common-events/src/main/java/.../events/PaymentFailedEvent.java`
  - `common-libs/common-events/src/main/java/.../events/FoodReadyEvent.java`
  - `common-libs/common-events/src/main/java/.../events/OrderDeliveredEvent.java`
  - `common-libs/common-events/src/main/java/.../events/EventEnvelope.java` (wrapper with `eventId`, `traceId`, `occurredAt`, `schemaVersion`)
  - `common-libs/common-events/src/main/avro/*.avsc` (Avro schemas, one per event, registered in Glue Schema Registry)
  - `common-libs/common-events/src/main/proto/menu.proto`, `promotion.proto` (gRPC service contracts)
  - `common-libs/common-dto/pom.xml`
  - `common-libs/common-dto/src/main/java/.../dto/Money.java`
  - `common-libs/common-dto/src/main/java/.../dto/Address.java`
  - `common-libs/common-dto/src/main/java/.../dto/PaginationCursor.java`
  - `common-libs/common-exceptions/pom.xml`
  - `common-libs/common-exceptions/src/main/java/.../api/ApiError.java` (shared error envelope record)
  - `common-libs/common-exceptions/src/main/java/.../api/FieldError.java` (per-field validation detail used by `ApiError`)
  - `common-libs/common-exceptions/src/main/java/.../exceptions/PlatformException.java` (abstract base for typed exceptions)
  - `common-libs/common-exceptions/src/test/java/.../api/ApiErrorSerializationTest.java`
- **Key details**:
  - All event types are **immutable Java records** with JSpecify `@NonNull`/`@Nullable` annotations (Spring Boot 4 + Java 25 idiom).
  - Use Jackson `@JsonProperty` for stable wire format.
  - Include `schemaVersion` field on every event (start at `1`).
  - **Avro schema files** parallel the Java records — a generated `kafka-avro-serializer` will use them with Glue Schema Registry. Schemas are the source of truth for cross-language compatibility (in case a Python analytics consumer is added later).
  - `Money` uses `BigDecimal` with explicit currency code (ISO 4217) — never `double`.
  - DTOs are serialization contracts: any change is breaking, treat schema evolution carefully (Avro's compatibility rules: BACKWARD by default).
  - **Audit-driven (cross-cutting recommendation #1)**: `ApiError` record shape: `{ status: int, error: String, code: String, message: String, timestamp: Instant, path: String, traceId: String, fieldErrors: List<FieldError>? }`. `FieldError` shape: `{ field: String, rejectedValue: Object, message: String }`. Replaces the private record currently in `product-service`'s exception handler. Required by user-service Step 2.6 and order-service Step 8.12.
- **Acceptance criteria**: Records serialize/deserialize round-trip in unit tests. Avro schemas validate against the records via Avro→POJO mapping test. `ApiError` serializes to JSON with stable field names and excludes null `fieldErrors` by default.
- **Dependencies**: 1.1

### Step 1.3: common-resilience module — Resilience4j + Idempotency
- [ ] **Objective**: Centralize Resilience4j configurations and Spring auto-config that every service can apply with one annotation. **This is the module that `architecture.md` Section 4 references.**
- **Files to create**:
  - `common-libs/common-resilience/pom.xml`
  - `common-libs/common-resilience/src/main/java/.../resilience/ResilienceAutoConfig.java`
  - `common-libs/common-resilience/src/main/java/.../resilience/CircuitBreakerDefaults.java`
  - `common-libs/common-resilience/src/main/java/.../resilience/RetryDefaults.java`
  - `common-libs/common-resilience/src/main/java/.../resilience/TimeoutDefaults.java`
  - `common-libs/common-resilience/src/main/java/.../resilience/IdempotencyKeyAspect.java`
  - `common-libs/common-resilience/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  - `common-libs/common-resilience/src/main/resources/application-resilience.yml` (default thresholds)
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
  - `common-libs/common-observability/pom.xml`
  - `common-libs/common-observability/src/main/java/.../obs/LoggingAutoConfig.java`
  - `common-libs/common-observability/src/main/java/.../obs/TraceContextFilter.java`
  - `common-libs/common-observability/src/main/java/.../obs/KafkaTracePropagator.java`
  - `common-libs/common-observability/src/main/java/.../obs/SqsTracePropagator.java`
  - `common-libs/common-observability/src/main/resources/logback-spring.xml`
  - `common-libs/common-outbox/pom.xml`
  - `common-libs/common-outbox/src/main/java/.../outbox/OutboxEvent.java` (entity)
  - `common-libs/common-outbox/src/main/java/.../outbox/OutboxRepository.java` (interface)
  - `common-libs/common-outbox/src/main/java/.../outbox/JdbcOutboxRepository.java`
  - `common-libs/common-outbox/src/main/java/.../outbox/OutboxPublisher.java` (Spring `@Scheduled`)
  - `common-libs/common-outbox/src/main/java/.../outbox/KafkaOutboxDispatcher.java`
  - `common-libs/common-outbox/src/main/java/.../outbox/SqsOutboxDispatcher.java`
  - `common-libs/common-outbox/src/main/java/.../outbox/OutboxRouter.java` (decides Kafka vs SQS based on row's `destination_type`)
  - `common-libs/common-outbox/src/main/resources/db/migration/V1__outbox_table.sql`
- **Key details**:
  - Outbox row schema includes `destination_type` (`KAFKA` | `SQS`) and `destination` (topic name or queue ARN). The publisher reads, the router dispatches, the dispatcher publishes.
  - Structured JSON logs include `traceId`, `spanId`, `userId`, `service`, `version`, `level`, `logger`, `message`.
  - OTel SDK auto-config sends spans to AWS X-Ray (via OTel collector) — see Phase 12.3.
  - Trace context propagated via HTTP `traceparent` header, **Kafka headers** (`traceparent`), and SQS message attributes (`traceId`).
  - Outbox publisher: `@Scheduled(fixedDelay=500)`, batch size 100, `SELECT ... FOR UPDATE SKIP LOCKED`. Uses **virtual threads** for parallel dispatch within a batch.
  - Kafka dispatcher uses Glue Schema Registry serializer for Avro payloads.
  - Configurable per-event-type destination map via Spring properties (e.g., `outbox.routing.USER_CREATED.kafka.topic=identity-events`).
  - Metrics: outbox lag (oldest unprocessed row age), publish success/failure counters per destination type.
- **Acceptance criteria**: An importing service with the outbox V1 migration sees rows it inserts get published to the correct destination (Kafka or SQS) within ~1 second. Trace context propagates through Kafka headers verified end-to-end in an integration test.
- **Dependencies**: 1.1, 1.2

---

## Phase 2: User Service

> Goal: working registration + login + JWT issuance, with the outbox emitting `USER_CREATED` events. By end of phase, you can register a user via API Gateway and observe the event in CloudWatch logs of a placeholder consumer.
>
> **PILOT NOTE**: user-service is the **pilot service**. Treat it as the template for services 2–10. In addition to Steps 2.1 through 2.6 below, the user-service pilot also includes — executed in the same overall arc, before any other service phase starts:
> - **Step 12.1** (Managed Prometheus + Grafana backend, plus user-service dashboard) — pulled forward from Phase 12
> - **Steps 13.1, 13.2, 13.3** (CodeCommit policies + path-filter Lambda + buildspec templates + user-service staging pipeline) — pulled forward from Phase 13
> - **Step 2.7** (consolidate the deploy template) — captures what was learned for services 2–10 to reuse
>
> The remainder of Phase 12 and Phase 13 — X-Ray, SLO alerts, canary rollouts, replicating pipelines to services 2–10 — happens after all services exist, in the original phase order.

### Step 2.1: user-service skeleton + DB schema
- [ ] **Objective**: Create the Spring Boot project, configure DB connection, run initial migrations.
- **Files to create**:
  - `services/user-service/pom.xml`
  - `services/user-service/src/main/java/.../UserApplication.java`
  - `services/user-service/src/main/resources/application.yml`
  - `services/user-service/src/main/resources/application-staging.yml`
  - `services/user-service/src/main/resources/db/migration/V1__users.sql`
  - `services/user-service/src/main/resources/db/migration/V2__refresh_tokens.sql`
  - `services/user-service/src/main/resources/db/migration/V3__outbox.sql` (use shared snippet from Step 1.4)
  - `services/user-service/buildspec.yml`
  - `services/user-service/Dockerfile`
- **Key details**:
  - Depends on common-libs (common-dto, common-exceptions, common-resilience, common-observability, common-outbox, common-events)
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
- [ ] **Objective**: Wire user-service into the GitOps repo so ArgoCD deploys it to staging.
- **Files to create**:
  - `food-ordering-gitops/apps/user-service/base/deployment.yaml`
  - `food-ordering-gitops/apps/user-service/base/service.yaml`
  - `food-ordering-gitops/apps/user-service/base/hpa.yaml`
  - `food-ordering-gitops/apps/user-service/base/serviceaccount.yaml` (with IRSA annotation)
  - `food-ordering-gitops/apps/user-service/base/externalsecret.yaml`
  - `food-ordering-gitops/apps/user-service/base/servicemonitor.yaml`
  - `food-ordering-gitops/apps/user-service/base/kustomization.yaml`
  - `food-ordering-gitops/apps/user-service/overlays/staging/{kustomization.yaml,image-tag.yaml,replicas.yaml}`
  - `food-ordering-gitops/apps/user-service/overlays/production/...`
  - `food-ordering-gitops/argocd/applications/user-service-staging.yaml`
- **Key details**:
  - Deployment: 2 replicas staging / 4 prod, init container runs Flyway migrate
  - HPA: scale on CPU 70%, min 2 max 10
  - SA annotated with `eks.amazonaws.com/role-arn` (IRSA role created by Terraform)
  - ExternalSecret references Secrets Manager paths for DB password + JWT private key
  - ServiceMonitor for Prometheus to scrape `/actuator/prometheus`
  - PodDisruptionBudget: minAvailable 1
  - Resources: 500m CPU / 512Mi mem requests, 1 CPU / 1Gi limits
- **Acceptance criteria**: Push to staging branch of food-ordering-gitops causes ArgoCD to deploy user-service. `kubectl get pods -n identity` shows running pods. Public API Gateway URL `POST /v1/auth/register` succeeds end-to-end.
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
- [ ] **Objective**: Now that user-service is fully running in staging with its CI/CD pipeline and dashboard, capture the patterns that worked. This document becomes the template for services 2–10.
- **Files to create**:
  - `food-ordering-platform/docs/service-deploy-template.md`
- **Key details**:
  - The template covers, for any future service:
    - **IRSA setup**: ServiceAccount annotation pattern, IAM role naming convention (`{org}-{env}-{service}-irsa`), trust policy template, common pitfalls
    - **Kustomize layout**: which manifests go in `base/`, which env-specific bits live in overlays, how `image-tag.yaml` is updated by CI
    - **External Secrets**: per-service `ExternalSecret` pattern, Secrets Manager path conventions
    - **ServiceMonitor + dashboard**: per-service monitoring scaffold, dashboard JSON file location, standard RED panels
    - **Pipeline wiring**: how to add a new service to the path-filter Lambda's routing config, what Terraform module instantiation looks like (one short `.tf` file per service)
    - **Verification checklist**: what "service X is fully deployed to staging" means concretely
  - Template includes a short FAQ section listing the surprises encountered on user-service so the next service author doesn't repeat them.
- **Acceptance criteria**: A developer who has never deployed a service to this platform can follow `docs/service-deploy-template.md` start-to-finish and end up with a new service running in staging without needing to read the build plan's Phase 2.
- **Dependencies**: 2.6, and the pulled-forward Steps 12.1 + 13.1 + 13.2 + 13.3 (all of which complete before this consolidation)

---

## Phase 3: Notification Service (Lambda)

> Goal: working `USER_CREATED` → welcome email pipeline. Once this works, User Service has a real consumer for its outbox events.

### Step 3.1: notification-service Lambda skeleton
- [ ] **Objective**: Create AWS SAM project for the Lambda + base SES configuration.
- **Files to create**:
  - `services/notification-service/template.yaml` (SAM)
  - `services/notification-service/pom.xml`
  - `services/notification-service/src/main/java/.../NotificationHandler.java`
  - `services/notification-service/src/main/java/.../config/Config.java`
  - `services/notification-service/buildspec.yml`
  - `services/notification-service/samconfig.toml`
- **Key details**:
  - Java 25 runtime (Corretto), 512 MB memory, 30s timeout
  - **Primary triggers: Lambda MSK event source mappings** — one mapping per source Kafka topic (`identity-events` for welcome emails, `order-events` for receipts, `delivery-events` for delivery push, `payment-events` for failure emails)
  - **Secondary triggers: SQS event source mappings** for webhook-driven cases (e.g., Stripe `charge.refunded` → refund email) and any Kafka-unfriendly fan-outs
  - Concurrency limit: 10 (avoid SES throttling)
  - DLQ on the function configured at the function level
  - SAM template provisions: Lambda, IAM role, MSK event source mappings, SQS event source mappings, SES domain identity, SNS Mobile Push platform applications
  - CloudWatch Logs retention: 30 days
- **Acceptance criteria**: `sam build && sam local invoke` succeeds with a stub event payload.
- **Dependencies**: 1.4

### Step 3.2: Template engine + S3 templates
- [ ] **Objective**: Load Mustache templates from S3, render with event payloads, cache rendered versions.
- **Files to create**:
  - `services/notification-service/src/main/java/.../templates/TemplateLoader.java`
  - `services/notification-service/src/main/java/.../templates/MustacheRenderer.java`
  - `services/notification-service/templates/welcome-email-v1.mustache` (uploaded to S3 by Terraform)
  - `services/notification-service/templates/order-receipt-v1.mustache`
  - `services/notification-service/templates/order-cancelled-v1.mustache`
  - `services/notification-service/templates/payment-failed-v1.mustache`
  - `services/notification-service/src/test/java/.../templates/MustacheRendererTest.java`
- **Key details**:
  - Templates indexed by `(templateId, version)`, fetched from S3 on first use, cached in Lambda memory for the duration of the warm container
  - Localization: `welcome-email-v1.{locale}.mustache`, fallback to `en` if missing
  - Rendered output is HTML for email, plain text for push
  - Subject lines stored as separate S3 objects: `subject/welcome-email-v1.en.txt`
- **Acceptance criteria**: Unit test renders a template with sample variables and produces expected HTML.
- **Dependencies**: 3.1

### Step 3.3: Idempotency layer + send paths
- [ ] **Objective**: Send via SES (email) and SNS Mobile Push, deduplicating on `(eventId, channel, recipient)`.
- **Files to create**:
  - `services/notification-service/src/main/java/.../send/EmailSender.java`
  - `services/notification-service/src/main/java/.../send/PushSender.java`
  - `services/notification-service/src/main/java/.../send/IdempotencyStore.java`
  - `services/notification-service/src/main/java/.../router/EventRouter.java`
  - `services/notification-service/src/test/java/.../router/EventRouterTest.java`
- **Key details**:
  - Conditional write to `notification-idempotency` DynamoDB table with `attribute_not_exists(idem_key)` — if conditional check fails, message was already sent, just ack
  - Router maps event types to (template, channel, recipient resolver): `USER_CREATED → welcome-email + customer email`
  - SES `SendEmail` API used; check for hard bounces and complaints via configured SNS topic
  - SNS Mobile Push uses platform endpoints stored on the user record (synced by user-update event listener — TODO in Phase 5+)
  - Failed sends raise exception → SQS retries → DLQ after 5 attempts
- **Acceptance criteria**: Two SQS messages with the same idem key result in only one email sent. CloudWatch log shows "duplicate, skipping" for the second.
- **Dependencies**: 3.2

### Step 3.4: CodePipeline for Notification (SAM-based)
- [ ] **Objective**: AWS-native pipeline that builds the Lambda artifact, runs tests, and deploys via SAM with CodeDeploy canary.
- **Files to create**:
  - `services/notification-service/buildspec-build.yml`
  - `services/notification-service/buildspec-deploy-staging.yml`
  - `services/notification-service/buildspec-deploy-prod.yml`
  - `platform-infra/envs/shared/pipelines/notification.tf`
- **Key details**:
  - Build: `mvn package shade:shade && sam build`
  - Deploy: `sam deploy --stack-name notification-{env} --no-confirm-changeset` with parameter overrides per env
  - CodeDeploy canary alias: 10% / 5min staging, 10% / 10min prod
  - CloudWatch alarm on `Errors` metric triggers automatic rollback
  - Production stage requires manual approval action in CodePipeline
- **Acceptance criteria**: Push to main triggers pipeline. Staging deploy completes. Smoke test invokes the Lambda with a fake `USER_CREATED` payload and observes an email in SES sandbox.
- **Dependencies**: 3.3, 0.11

---

## Phase 4: Promotion & Loyalty Service

> Goal: when a user registers, the welcome promo code is auto-issued and stored. By end of phase, registering a new user produces both a welcome email AND a redeemable promo code.

### Step 4.1: promotion-service skeleton + DB schema
- [ ] **Objective**: Create the Spring Boot service, run migrations, set up event subscription scaffold.
- **Files to create**:
  - `services/promotion-service/pom.xml`
  - `services/promotion-service/src/main/java/.../PromotionApplication.java`
  - `services/promotion-service/src/main/resources/application.yml`
  - `services/promotion-service/src/main/resources/db/migration/V1__promo_codes.sql`
  - `services/promotion-service/src/main/resources/db/migration/V2__promo_redemptions.sql`
  - `services/promotion-service/src/main/resources/db/migration/V3__outbox.sql`
  - `services/promotion-service/Dockerfile`
  - `services/promotion-service/buildspec.yml`
- **Key details**:
  - Schema `promo_codes(id, user_id, code, code_type, discount_type, amount, currency, min_order_amount, valid_from, valid_until, state, created_at)` with `code_type` enum (`WELCOME`, `BIRTHDAY`, `LOYALTY_TIER`)
  - Schema `promo_redemptions(id, promo_code_id, order_id, redeemed_at)` with unique `(promo_code_id, order_id)` for idempotency
  - Unique constraint `(user_id, code_type)` prevents double-issuance under retries
  - Code states: `ISSUED`, `RESERVED` (during checkout), `USED`, `EXPIRED`, `RESTORED`
  - Same outbox + observability config as Identity (reuse common-outbox)
- **Acceptance criteria**: Service starts, migrations apply, `/actuator/health` returns 200.
- **Dependencies**: 1.4

### Step 4.2: USER_CREATED listener and welcome code issuance
- [ ] **Objective**: SQS consumer processes `USER_CREATED` events, issues a welcome code, and writes a `PROMO_ISSUED` event to outbox (consumed by Notification).
- **Files to create**:
  - `services/promotion-service/src/main/java/.../listener/UserCreatedListener.java`
  - `services/promotion-service/src/main/java/.../service/PromoIssuanceService.java`
  - `services/promotion-service/src/main/java/.../domain/PromoCode.java`
  - `services/promotion-service/src/main/java/.../domain/PromoCodeRepository.java`
  - `services/promotion-service/src/test/java/.../listener/UserCreatedListenerIT.java`
- **Key details**:
  - **Spring for Apache Kafka** `@KafkaListener` consuming `identity-events` topic, filtered on `eventType=USER_CREATED` header.
  - **Glue Schema Registry** Avro deserializer; consumer group `promotion-user-created-v1`.
  - Listener method `@Transactional`: insert promo code + outbox row in one tx (outbox row publishes `PROMO_ISSUED` to MSK topic `promotion-events`).
  - Idempotent: catch `DataIntegrityViolationException` from unique `(userId, codeType)` constraint and ack the offset.
  - Code generation: 8-char alphanumeric uppercase, exclude ambiguous chars (`0`, `O`, `1`, `I`, `l`).
  - Welcome code: 20% off, max discount $10, valid 30 days from issuance.
  - Tests verify: new user → 1 promo code; same user retry → still 1 promo code.
- **Acceptance criteria**: Register a user via Identity → ~1 second later, `promo_codes` table contains 1 row for that user with `code_type = WELCOME`. Outbox publishes `PROMO_ISSUED` to MSK `promotion-events` topic, consumed by Notification.
- **Dependencies**: 4.1, 2.5

### Step 4.3: gRPC validation endpoint for Order Service
- [ ] **Objective**: Expose `ValidateCode`, `RedeemCode`, `RestoreCode` gRPC methods.
- **Files to create**:
  - `common-libs/common-events/src/main/proto/promotion.proto`
  - `services/promotion-service/src/main/java/.../grpc/PromotionGrpcService.java`
  - `services/promotion-service/src/main/java/.../service/PromoValidationService.java`
  - `services/promotion-service/src/test/java/.../grpc/PromotionGrpcServiceTest.java`
- **Key details**:
  - `.proto` file in shared-libs so Order Service can generate a client stub
  - `ValidateCode(userId, code, orderTotal, currency) → DiscountAmount or ValidationError`
  - `RedeemCode(userId, code, orderId)` is idempotent on `(promo_code_id, order_id)`
  - `RestoreCode(userId, code, orderId)` removes the redemption row and sets state back to `ISSUED`
  - gRPC server on port 9090, ALB target group with HTTP/2
  - Resilience4j circuit breaker + rate limiter on the gRPC server methods
- **Acceptance criteria**: Test gRPC client calls `ValidateCode` and gets correct discount calculation. Idempotent `RedeemCode` returns same result on retry.
- **Dependencies**: 4.2

### Step 4.4: K8s manifests + ArgoCD app for promotion-service
- [ ] **Objective**: Wire promotion-service into GitOps and deploy to staging.
- **Files to create**:
  - `food-ordering-gitops/apps/promotion-service/base/{deployment,service,hpa,serviceaccount,externalsecret,servicemonitor,kustomization}.yaml`
  - `food-ordering-gitops/apps/promotion-service/overlays/staging/...`
  - `food-ordering-gitops/apps/promotion-service/overlays/production/...`
  - `food-ordering-gitops/argocd/applications/promotion-service-staging.yaml`
- **Key details**:
  - Service exposes both HTTP (8080) and gRPC (9090) ports
  - Two ALBs: HTTP for actuator, internal gRPC ALB for cross-service calls
  - HPA scales on RPS, not CPU
  - Init container runs Flyway
- **Acceptance criteria**: ArgoCD shows promotion-service Synced/Healthy in staging. End-to-end: register user via API → both welcome email and promo code created.
- **Dependencies**: 4.3, 0.11

---

## Phase 5: Product (Restaurant Menu) Service

> Goal: a working menu service with cache-aside, image upload via pre-signed URLs, gRPC verification endpoint, and the public search API.

### Step 5.1: product-service skeleton + DynamoDB integration
- [ ] **Objective**: Spring Boot service with AWS SDK v2 DynamoDB client and basic CRUD.
- **Files to create**:
  - `services/product-service/pom.xml`
  - `services/product-service/src/main/java/.../ProductApplication.java`
  - `services/product-service/src/main/java/.../config/DynamoDbConfig.java`
  - `services/product-service/src/main/java/.../domain/Menu.java`
  - `services/product-service/src/main/java/.../domain/MenuItem.java`
  - `services/product-service/src/main/java/.../domain/MenuRepository.java`
  - `services/product-service/src/main/resources/application.yml`
  - `services/product-service/Dockerfile`
- **Key details**:
  - DynamoDB Enhanced Client (Java SDK v2)
  - Schema: PK = `RESTAURANT#{restaurantId}`, SK = `MENU` for the full menu document; SK = `ITEM#{itemId}` for individual items
  - `Menu` is a denormalized document containing categories → items → modifiers
  - `MenuItem` fields: `id`, `name`, `description`, `price` (Money), `imageS3Key`, `availability`, `schedule` (optional), `dietaryTags`
  - All amounts use `BigDecimal` via the Money shared DTO
- **Acceptance criteria**: Local Spring Boot test against Testcontainers DynamoDB Local writes a menu, reads it back, asserts equality.
- **Dependencies**: 1.4

### Step 5.2: Public REST endpoints + caching layer
- [ ] **Objective**: Implement `GET /v1/restaurants/{id}/menu` and `GET /v1/restaurants/search` with cache-aside.
- **Files to create**:
  - `services/product-service/src/main/java/.../api/MenuController.java`
  - `services/product-service/src/main/java/.../service/MenuService.java`
  - `services/product-service/src/main/java/.../cache/MenuCache.java`
  - `services/product-service/src/main/java/.../cache/RedisCacheConfig.java`
  - `services/product-service/src/main/resources/application-cache.yml`
  - `services/product-service/src/test/java/.../service/MenuServiceCacheIT.java`
- **Key details**:
  - Cache key: `menu:v1:restaurant:{restaurantId}`, TTL 30 min
  - Cache-aside: cache → miss → DynamoDB → populate → return
  - On write (Step 5.3), explicitly delete cache key — do not rely solely on TTL
  - Cache-bypass query param `?nocache=true` (auth-gated for admins)
  - Compress JSON in cache with snappy if > 5 KB
  - Search v1: simple DynamoDB scan with filter; document plan to migrate to OpenSearch in Phase 5+
- **Acceptance criteria**: First request hits DynamoDB; second request within 30 min hits cache (verified by metric `cache.hit`).
- **Dependencies**: 5.1

### Step 5.3: Restaurant-owner write endpoints + S3 image uploads
- [ ] **Objective**: Restaurant owners can edit menus; image uploads go directly to S3 via pre-signed URLs.
- **Files to create**:
  - `services/product-service/src/main/java/.../api/MenuAdminController.java`
  - `services/product-service/src/main/java/.../service/MenuMutationService.java`
  - `services/product-service/src/main/java/.../service/ImageUploadService.java`
  - `services/product-service/src/main/java/.../security/RestaurantOwnerAuthorizer.java`
  - `services/product-service/src/test/java/.../api/MenuAdminControllerIT.java`
- **Key details**:
  - JWT must include `role=RESTAURANT_OWNER` AND `restaurantId` matching the resource
  - `POST /v1/restaurants/{id}/menu/items` and `PATCH /v1/menu/items/{itemId}`
  - On any mutation: write to DynamoDB, then `cache.delete(menuKey)` — order matters
  - `POST /v1/menu/items/{itemId}/image-upload-url` returns pre-signed S3 PUT URL valid 5 min, max 5 MB
  - Image keys are content-addressed: `menu/{restaurantId}/{itemId}/{sha256}.jpg`
  - Lambda triggered on S3 PutObject resizes to standard variants (thumb, medium, full) and updates the menu item
- **Acceptance criteria**: Update item price → first GET after update returns new price (cache invalidation works). Upload an image and access via CloudFront URL.
- **Dependencies**: 5.2

### Step 5.4: gRPC server for internal verification
- [ ] **Objective**: Expose `MenuService.VerifyItem(restaurantId, itemId)` for Basket and Order services.
- **Files to create**:
  - `common-libs/common-events/src/main/proto/menu.proto`
  - `services/product-service/src/main/java/.../grpc/MenuGrpcService.java`
  - `services/product-service/src/test/java/.../grpc/MenuGrpcServiceTest.java`
- **Key details**:
  - Returns `ItemAvailability { exists, available_now, current_price, restaurant_paused }`
  - `available_now` considers menu schedule (e.g., breakfast 06:00–11:00)
  - `restaurant_paused` reflects state set by Kitchen Service via `RESTAURANT_PAUSED` event consumer
  - Cached in Redis with TTL 60s — verifies are slightly stale-tolerant; final price-locking happens at Order checkout
  - Resilience4j on the server side: rate limiter 1000 req/s per source pod
- **Acceptance criteria**: gRPC client from a test calls `VerifyItem` and gets correct response in < 50ms p99.
- **Dependencies**: 5.3

### Step 5.5: RESTAURANT_PAUSED listener + manifests + deployment
- [ ] **Objective**: Subscribe to Kitchen events to hide overloaded restaurants. Deploy to staging.
- **Files to create**:
  - `services/product-service/src/main/java/.../listener/RestaurantPausedListener.java`
  - `services/product-service/src/main/java/.../domain/RestaurantStatus.java` (DDB item)
  - `food-ordering-gitops/apps/product-service/base/...`
  - `food-ordering-gitops/apps/product-service/overlays/{staging,production}/...`
  - `food-ordering-gitops/argocd/applications/product-service-staging.yaml`
- **Key details**:
  - **Kafka consumer** subscribed to topic `kitchen-events` with consumer group `product-service`, filtering on `eventType` header (`RESTAURANT_PAUSED`, `RESTAURANT_RESUMED`)
  - Updates `RestaurantStatus` DDB row with `paused = true/false` and `pausedAt`
  - Search and `VerifyItem` filter out paused restaurants
  - Auto-resume after configurable idle period (Kitchen emits `RESTAURANT_RESUMED`)
- **Acceptance criteria**: Manually publish `RESTAURANT_PAUSED` to MSK topic `kitchen-events` → search no longer returns that restaurant. ArgoCD shows Synced/Healthy in staging.
- **Dependencies**: 5.4, 0.11

---

## Phase 6: Basket Service

> Goal: customers add/remove items with idempotency; items are validated against Menu via gRPC in real time.

### Step 6.1: basket-service skeleton with Redis primary store
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

### Step 6.2: REST endpoints + idempotency layer
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
  - Validate item via gRPC (Step 6.3) before persisting
  - `BasketService` uses Redis WATCH/MULTI/EXEC (or Lua) for atomic read-modify-write
- **Acceptance criteria**: Same `Idempotency-Key` posted twice → only one item added; second request returns the same response.
- **Dependencies**: 6.1

### Step 6.3: gRPC client to Menu Service with circuit breaker
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
- **Dependencies**: 6.2, 5.4

### Step 6.4: Checkout endpoint + manifests + deployment
- [ ] **Objective**: Implement `POST /v1/basket/checkout` that locks the basket and returns a pre-order DTO; deploy to staging.
- **Files to create**:
  - `services/basket-service/src/main/java/.../service/CheckoutService.java`
  - `services/basket-service/src/main/java/.../api/CheckoutController.java`
  - `food-ordering-gitops/apps/basket-service/base/...`
  - `food-ordering-gitops/apps/basket-service/overlays/{staging,production}/...`
  - `food-ordering-gitops/argocd/applications/basket-service-staging.yaml`
- **Key details**:
  - Checkout re-verifies every item via Menu gRPC, computes final subtotal, marks basket `LOCKED` (Redis SETNX with checkout token)
  - Returns `PreOrder` DTO with all data Order Service needs to create the order
  - Locked basket cannot be modified for 5 minutes — if no order is created, lock auto-expires
  - On checkout failure (price changed, item unavailable): 409 with details so UI can refresh
- **Acceptance criteria**: End-to-end: add items, checkout, get a `PreOrder` with locked basket. Try modifying during lock → 409.
- **Dependencies**: 6.3, 0.11

### Step 6.5: Address basket-service audit gaps
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
- **Dependencies**: 6.4

---

## Phase 7: Payment Service

> Goal: idempotent Stripe integration with circuit breaker, webhook handling, and outbox-driven event emission.

### Step 7.1: payment-service skeleton + DynamoDB ledger
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
  - DynamoDB table from Step 0.5: `payment-ledger` PK = `payment_intent_id`, SK = `entry_seq`
  - Entry types: `INITIATED`, `AUTHORIZED`, `CAPTURED`, `FAILED`, `REFUNDED`, `DISPUTED`
  - Append-only: never `UpdateItem`, only `PutItem` with conditional `attribute_not_exists`
  - GSI on `idempotency_key` for the duplicate-charge check
  - PII discipline: never log PAN; only `last4` and Stripe token references
- **Acceptance criteria**: Insert ledger entries, list all entries for a payment intent, assert ordering.
- **Dependencies**: 1.4

### Step 7.2: Stripe integration with idempotency
- [ ] **Objective**: `POST /v1/payments/charge` calls Stripe with idempotency keys; duplicate requests return cached result.
- **Files to create**:
  - `services/payment-service/src/main/java/.../api/PaymentController.java`
  - `services/payment-service/src/main/java/.../service/PaymentService.java`
  - `services/payment-service/src/main/java/.../client/StripeClient.java`
  - `services/payment-service/src/main/java/.../client/StripeConfig.java`
  - `services/payment-service/src/test/java/.../service/PaymentServiceIT.java`
- **Key details**:
  - Request includes `idempotency_key` (= order ID)
  - Step 1: query GSI by idempotency key — if entry exists, return cached result
  - Step 2: write `INITIATED` ledger entry
  - Step 3: call Stripe `PaymentIntent.create()` with `idempotency_key` header (Stripe also dedups)
  - Step 4: write `CAPTURED` or `FAILED` entry
  - Step 5: write outbox row with the corresponding event
  - Crash-safe: on retry, step 1 finds the existing entry; if step 4 didn't run, recovery job re-queries Stripe by idempotency key
- **Acceptance criteria**: Calling charge with same idempotency key twice produces only one Stripe charge.
- **Dependencies**: 7.1

### Step 7.3: Circuit breaker, retry, rate limiter, bulkhead on Stripe
- [ ] **Objective**: Apply full Resilience4j stack to `StripeClient` calls.
- **Files to create**:
  - `services/payment-service/src/main/java/.../client/ResilientStripeClient.java`
  - `services/payment-service/src/main/resources/application-resilience.yml`
  - `services/payment-service/src/test/java/.../client/StripeResilienceIT.java`
- **Key details**:
  - Circuit breaker: 50% failure rate over 20-call sliding window, opens for 60s
  - Retry: 3 attempts, exponential backoff 200ms × 2, max 2s; only retries on 5xx and `RetryableException`
  - Rate limiter: 25 req/s per pod (Stripe global limit minus headroom)
  - Bulkhead: charge calls and refund calls have separate semaphore bulkheads (`charge=20`, `refund=10`)
  - Time limiter: 5s — Stripe should never take that long
  - Tests inject latency and 503s via WireMock
- **Acceptance criteria**: Inject 50% failures via WireMock → circuit opens after 10 calls and `CallNotPermittedException` is raised on subsequent calls.
- **Dependencies**: 7.2

### Step 7.4: Stripe webhook handler with signature verification
- [ ] **Objective**: `POST /v1/webhooks/stripe` accepts Stripe events for delayed outcomes (chargeback, async failures).
- **Files to create**:
  - `services/payment-service/src/main/java/.../api/StripeWebhookController.java`
  - `services/payment-service/src/main/java/.../service/WebhookProcessor.java`
  - `services/payment-service/src/test/java/.../api/StripeWebhookControllerIT.java`
- **Key details**:
  - Verify `Stripe-Signature` header against webhook secret from Secrets Manager
  - Replay protection: ignore events older than 5 minutes
  - Idempotency: store Stripe event ID in DynamoDB; second arrival returns 200 without processing
  - Supported events: `charge.failed`, `charge.refunded`, `charge.dispute.created`, `payment_intent.succeeded`
  - Each handler writes a ledger entry + outbox row (`PAYMENT_FAILED`, `PAYMENT_REFUNDED`, `PAYMENT_DISPUTED`)
  - Webhook URL exposed via API Gateway with WAF allow-listing Stripe IPs (or relying on signature only)
- **Acceptance criteria**: Stripe CLI (`stripe trigger charge.failed`) fires webhook → ledger entry created → outbox publishes `PAYMENT_FAILED` to MSK topic `payment-events`.
- **Dependencies**: 7.3

### Step 7.5: Refund endpoint + DDB-Streams outbox publisher + manifests + deployment
- [ ] **Objective**: `POST /v1/payments/refund` and DDB-Streams-driven outbox publisher; deploy to staging.
- **Files to create**:
  - `services/payment-service/src/main/java/.../service/RefundService.java`
  - `services/payment-service/lambdas/outbox-publisher/template.yaml`
  - `services/payment-service/lambdas/outbox-publisher/src/...`
  - `food-ordering-gitops/apps/payment-service/base/...`
  - `food-ordering-gitops/apps/payment-service/overlays/{staging,production}/...`
- **Key details**:
  - Refund endpoint also idempotent on `(idempotency_key)` — typically order ID
  - Outbox publisher is a Lambda with DDB Stream as event source on `outbox-payment` table
  - Publisher batches up to 100 records per invocation, **publishes to MSK topic `payment-events`** (with `orderId` as partition key for per-order ordering), deletes successfully-published rows
  - Failed publishes go to SQS DLQ for manual inspection
  - Payment service deployed in dedicated namespace `payment` with stricter NetworkPolicy (only Order Orchestrator can call it)
- **Acceptance criteria**: End-to-end refund flow tested. Outbox events emit within 1s of DDB write.
- **Dependencies**: 7.4, 0.11

---

## Phase 8: Order Orchestrator Service

> Goal: the core saga. By the end of this phase, a customer can checkout a basket, get charged, the kitchen receives the ticket, and on payment failure the system rolls back cleanly via compensation. This is the longest phase — 11 small steps.

### Step 8.1: order-service skeleton + DB schema + state machine config
- [ ] **Objective**: Spring Boot project, schema with orders + order_items + saga_state + outbox tables, Spring StateMachine wiring.
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
  - `orders` table: `id, customer_id, restaurant_id, basket_token, state, subtotal, discount, total, currency, payment_intent_id, promo_code, failure_reason, created_at, updated_at, paid_at, delivered_at, expected_compensation_acks JSONB`
  - `order_items` table normalized
  - `saga_compensation_acks` tracks which compensation actions have been ack'd per order
  - States enum: `PENDING, PAID, KITCHEN_ACCEPTED, FOOD_READY, OUT_FOR_DELIVERY, DELIVERED, CANCELING, COMPENSATING, CANCELED, FAILED`
  - Spring StateMachine config defines all transitions and guards (e.g., can't go to `KITCHEN_ACCEPTED` from `PENDING`)
  - Optimistic locking via `version` column with `@Version`
- **Acceptance criteria**: All migrations apply. State machine bean loads. Unit test transitions through happy path.
- **Dependencies**: 1.4

### Step 8.2: Create order endpoint (PENDING state) + outbox first command
- [ ] **Objective**: `POST /v1/orders` accepts a basket token, creates the order in `PENDING` state, writes outbox row to charge payment.
- **Files to create**:
  - `services/order-service/src/main/java/.../api/OrderController.java`
  - `services/order-service/src/main/java/.../service/OrderCreationService.java`
  - `services/order-service/src/main/java/.../api/dto/CreateOrderRequest.java`
  - `services/order-service/src/main/java/.../api/dto/OrderResponse.java`
  - `services/order-service/src/test/java/.../service/OrderCreationServiceIT.java`
- **Key details**:
  - Validate basket token by calling Basket Service's internal verify endpoint (REST is fine here, not in hot path)
  - `@Transactional`: insert order + items + outbox row (`CHARGE_PAYMENT` command) in single tx
  - Idempotency-Key header required; map to `(customer_id, basket_token, idempotency_key)` so same checkout doesn't create two orders
  - Returns 202 Accepted with order ID — final confirmation comes after payment
  - Total = subtotal − discount (provided by Step 8.3 next)
- **Acceptance criteria**: Order created, basket lock confirmed, outbox row visible. Same idempotency key returns same order.
- **Dependencies**: 8.1, 6.4

### Step 8.3: gRPC client to Promotion + ValidateCode + RedeemCode in checkout flow
- [ ] **Objective**: Before charging, call Promotion gRPC to validate and redeem the promo code.
- **Files to create**:
  - `services/order-service/src/main/java/.../client/PromotionGrpcClient.java`
  - `services/order-service/src/main/java/.../client/PromotionClientFallback.java`
  - `services/order-service/src/main/java/.../service/DiscountResolver.java`
  - `services/order-service/src/test/java/.../client/PromotionGrpcClientIT.java`
- **Key details**:
  - `ValidateCode` is called synchronously during order creation
  - `RedeemCode` is called in the same transaction as the state transition to `PAID` — if payment succeeds, redemption is committed
  - On Promotion service unavailable: order is created without discount (graceful degradation) OR rejected with 503 — make this configurable per env (prod = degrade, staging = reject)
  - Resilience4j: timeout 500ms, retry 1, circuit breaker
- **Acceptance criteria**: Order with valid code gets discount applied. Invalid code returns 400 with reason. Service-down returns 503 (or 200 without discount per config).
- **Dependencies**: 8.2, 4.3

### Step 8.4: Saga forward path — PAYMENT_SUCCESS handler (PENDING → PAID → KITCHEN_ACCEPTED)
- [ ] **Objective**: Consume `PAYMENT_SUCCESS` events, transition order state, emit `ORDER_PAID` command for Kitchen.
- **Files to create**:
  - `services/order-service/src/main/java/.../listener/PaymentEventListener.java`
  - `services/order-service/src/main/java/.../saga/OrderSaga.java` (handler methods)
  - `services/order-service/src/main/java/.../saga/SagaCommandFactory.java`
  - `services/order-service/src/test/java/.../saga/OrderSagaPaymentSuccessIT.java`
- **Key details**:
  - **Kafka consumer (Spring Kafka @KafkaListener)** subscribed to topic `payment-events` with consumer group `order-service`. Filters on header `eventType=PAYMENT_SUCCESS`.
  - Loads order with `SELECT FOR UPDATE`; idempotent ignore if state already `PAID` or beyond
  - State transition through Spring StateMachine
  - Writes outbox rows for: `ORDER_PAID` (Kafka topic `order-events`, key=orderId, consumed by Kitchen) and `RECEIPT_REQUESTED` (Kafka topic `order-events`, consumed by Notification Lambda)
  - Includes `traceId` propagated from incoming Kafka headers into outgoing events
- **Acceptance criteria**: Publish a fake `PAYMENT_SUCCESS` to MSK topic `payment-events` → order moves PENDING → PAID. Outbox publishes `ORDER_PAID` to MSK. Verified end-to-end with Testcontainers Kafka.
- **Dependencies**: 8.3, 7.5

### Step 8.5: Saga forward path — KITCHEN_ACCEPTED, FOOD_READY, ORDER_DELIVERED
- [ ] **Objective**: Wire the remaining forward listeners that consume Kitchen and Delivery events.
- **Files to create**:
  - `services/order-service/src/main/java/.../listener/KitchenEventListener.java`
  - `services/order-service/src/main/java/.../listener/DeliveryEventListener.java`
  - `services/order-service/src/test/java/.../saga/OrderSagaForwardPathIT.java`
- **Key details**:
  - Listeners for: `kitchen-events` (KITCHEN_ACCEPTED, FOOD_READY, RESTAURANT_REJECTED) and `delivery-events` (DRIVER_PICKED_UP, ORDER_DELIVERED, DELIVERY_FAILED)
  - Each listener loads order with `FOR UPDATE`, validates current state matches expected predecessor, transitions
  - On reaching `DELIVERED`: write outbox row `ORDER_FINALIZED` (consumed by Notification for "Enjoy" push)
  - All listeners are idempotent; out-of-order events use state guards to safely no-op
- **Acceptance criteria**: Synthetic test publishes Kitchen + Delivery events in sequence → order reaches `DELIVERED`. Test out-of-order delivery → `KITCHEN_ACCEPTED` arriving twice is no-op.
- **Dependencies**: 8.4

### Step 8.6: Compensation logic — PAYMENT_FAILED, RESTAURANT_REJECTED, USER_CANCELED
- [ ] **Objective**: Implement the compensation entry point with the multi-step rollback logic.
- **Files to create**:
  - `services/order-service/src/main/java/.../saga/CompensationService.java`
  - `services/order-service/src/main/java/.../saga/CompensationPlan.java` (decides what to compensate based on current state)
  - `services/order-service/src/main/java/.../listener/PaymentFailedListener.java`
  - `services/order-service/src/test/java/.../saga/CompensationServiceIT.java`
- **Key details**:
  - On compensation entry: state → `COMPENSATING`, populate `expected_compensation_acks` based on what happened
  - Outbox commands: `CANCEL_KITCHEN_TICKET` (if past KITCHEN_ACCEPTED), `RESTORE_PROMO_CODE` (if promo applied), `RESTORE_BASKET` (always), `NOTIFY_PAYMENT_FAILED` or `NOTIFY_CANCELED` (always)
  - Each compensation handler downstream must be idempotent
  - On ack receipt: insert row in `saga_compensation_acks`, then check if all expected acks present
  - When all acks received: state → `FAILED` or `CANCELED` (depending on cause)
- **Acceptance criteria**: Inject `PAYMENT_FAILED` after order reached `KITCHEN_ACCEPTED` → compensation runs, all 4 commands published, after fake acks state → `FAILED`.
- **Dependencies**: 8.5

### Step 8.7: Cancel order endpoint (state-aware)
- [ ] **Objective**: `DELETE /v1/orders/{id}` — cancellation behavior depends on current state.
- **Files to create**:
  - `services/order-service/src/main/java/.../service/OrderCancellationService.java`
  - `services/order-service/src/main/java/.../api/dto/CancelOrderResponse.java`
  - `services/order-service/src/test/java/.../api/OrderCancellationIT.java`
- **Key details**:
  - States `PENDING`, `PAID` → cancel allowed; transition to `CANCELING`, write outbox commands (refund, restore promo, restore basket, notify cancel)
  - States `KITCHEN_ACCEPTED` → cancel allowed but with stricter rules (configurable per restaurant)
  - States `FOOD_READY`, `OUT_FOR_DELIVERY` → 409 Conflict, manual support flow required
  - Return 202 Accepted with `cancellation_id` so client can poll status
  - Reuses CompensationService from 8.6
- **Acceptance criteria**: DELETE in `PAID` → 202, refund flow runs. DELETE in `OUT_FOR_DELIVERY` → 409.
- **Dependencies**: 8.6

### Step 8.8: Saga timeout enforcer
- [ ] **Objective**: Scheduled job that detects stuck orders and triggers compensation.
- **Files to create**:
  - `services/order-service/src/main/java/.../saga/SagaTimeoutEnforcer.java`
  - `services/order-service/src/main/java/.../saga/SagaTimeoutConfig.java`
  - `services/order-service/src/test/java/.../saga/SagaTimeoutEnforcerIT.java`
- **Key details**:
  - `@Scheduled(fixedDelay = 30_000)` runs every 30s
  - Query: orders in non-terminal state with `updated_at < NOW() - INTERVAL '5 minutes'`
  - For each stuck order: invoke `CompensationService.onSagaTimeout(orderId)` with reason `SAGA_TIMEOUT`
  - Configurable per-state timeout (e.g., `PENDING → 2min`, `KITCHEN_ACCEPTED → 30min`, etc.)
  - ShedLock or PostgreSQL advisory lock to prevent multiple pods running the same scan
  - Metrics: `saga.timeouts.triggered{state=...}`
- **Acceptance criteria**: Manually create an order, freeze time forward via test clock → enforcer triggers compensation.
- **Dependencies**: 8.7

### Step 8.9: Get order + list orders endpoints
- [ ] **Objective**: Read APIs `GET /v1/orders/{id}` and `GET /v1/orders` with pagination, filtering, and sorting.
- **Files to create**:
  - `services/order-service/src/main/java/.../api/OrderQueryController.java`
  - `services/order-service/src/main/java/.../service/OrderQueryService.java`
  - `services/order-service/src/main/java/.../api/dto/OrderListResponse.java`
- **Key details**:
  - **Audit §7 + §11 for order-service**: `GET /v1/orders` returns `Page<OrderResponseDto>` (Spring Data) — never unpaginated `List`. Controller accepts `Pageable` parameter with `@PageableDefault(size = 20)`. Optional filter params: `?status=PAID`, `?from=2026-01-01`, `?to=2026-01-31`, and `?sort=createdAt,desc` (Spring Data convention). All filters validated; invalid `status` values rejected with 400.
  - Cursor-based pagination is also supported on `GET /v1/orders?cursor=...&limit=...` for high-volume customer-history use cases — the two pagination styles coexist (page+filter for admin / restaurant-owner views, cursor for customer "infinite scroll").
  - Read replica for queries (Aurora endpoint configured separately from writes)
  - Customer can only see their own orders; restaurant owner sees orders for their restaurant; admin sees all
  - Includes saga state and timeline (state transition history) — store transitions in dedicated `order_state_history` table updated by state machine listener
- **Acceptance criteria**: Customer with 50 orders can paginate via both page-based and cursor-based mechanisms. Filtering by `status=DELIVERED` returns only delivered orders. Date range filter returns orders within the specified `from`–`to` window. Invalid `status` value (e.g. `?status=BANANA`) returns HTTP 400 with `ApiError`.
- **Dependencies**: 8.8

### Step 8.10: Outbox publisher sidecar + observability dashboards
- [ ] **Objective**: Configure outbox publisher to emit Order events; build a saga-specific Grafana dashboard.
- **Files to create**:
  - `services/order-service/src/main/resources/application-outbox.yml` (event-type-to-topic mapping)
  - `services/order-service/src/main/java/.../config/OutboxTopicConfig.java`
  - `food-ordering-gitops/apps/order-service/base/grafana-dashboard-saga.json`
- **Key details**:
  - Outbox event types map to **Kafka topics** with headers set for downstream filtering and tracing: `eventType`, `aggregateId`, `traceId`. Compensation event types route to SQS compensation queues instead — the `OutboxRouter` (Step 1.4) handles dispatch.
  - Dashboard panels: orders by state, time-in-state p50/p99, saga compensation rate, outbox lag (Kafka producer lag + SQS visibility lag), timeout enforcer fires
  - Alert rules: `compensation_rate > 5%` for 5 min, `outbox_lag > 10s` for 5 min, `orders_stuck > 0` for 30 min
  - Tracing: end-to-end trace visualizes saga across services (X-Ray service map should show order → payment → kitchen → delivery)
- **Acceptance criteria**: Place 10 orders in staging — dashboard shows them progressing through states. Force a payment failure → compensation rate panel updates.
- **Dependencies**: 8.9

### Step 8.11: order-service K8s manifests + ArgoCD app + integration smoke test
- [ ] **Objective**: Wire Order Service into GitOps and validate the full happy path end-to-end.
- **Files to create**:
  - `food-ordering-gitops/apps/order-service/base/...` (deployment, hpa, sa, externalsecret, servicemonitor)
  - `food-ordering-gitops/apps/order-service/overlays/{staging,production}/...`
  - `food-ordering-gitops/argocd/applications/order-service-staging.yaml`
  - `food-ordering-gitops/apps/order-service/base/networkpolicy.yaml` (only Order can call Payment)
- **Key details**:
  - Deployment: 4 replicas in prod (highest criticality)
  - Init container runs Flyway migrations
  - PodDisruptionBudget: minAvailable 2 (always keep 2 pods up during rollouts)
  - NetworkPolicy: allow ingress from API Gateway (via VPC link) and other services on the saga path
  - Resources: 1 CPU / 1 Gi requests, 2 CPU / 2 Gi limits
  - Add liveness probe (`/actuator/health/liveness`) and readiness probe (`/actuator/health/readiness`)
- **Acceptance criteria**: ArgoCD shows Healthy. End-to-end smoke test against staging: register user → add to basket → checkout → order created → payment captured → kitchen ticket appears in DDB.
- **Dependencies**: 8.10, 0.11

### Step 8.12: Address order-service audit gaps
- [ ] **Objective**: Close the remaining audit gaps identified in `docs/API_AUDIT.md` for order-service: global exception handler with `OrderNotFoundException`. (Idempotency-Key was already addressed in Step 8.2; pagination + filtering were addressed in Step 8.9.)
- **Files to create**:
  - `services/order-service/src/main/java/.../exception/GlobalExceptionHandler.java`
  - `services/order-service/src/main/java/.../exception/OrderServiceExceptions.java` (typed exceptions: `OrderNotFoundException`, `OrderStateConflictException`, `IdempotencyKeyMismatchException`, etc.)
  - `services/order-service/src/test/java/.../exception/GlobalExceptionHandlerIT.java`
- **Files to modify**:
  - All controllers and services in `services/order-service/` — replace generic `RuntimeException` throws with typed exceptions from `OrderServiceExceptions`.
- **Key details**:
  - **Audit §5 for order-service**: `@RestControllerAdvice` with explicit handlers:
    - `OrderNotFoundException` → HTTP 404, code `ORDER_NOT_FOUND`
    - `OrderStateConflictException` → HTTP 409, code `ORDER_STATE_CONFLICT` (e.g. "can't cancel order in OUT_FOR_DELIVERY")
    - `IdempotencyKeyMismatchException` → HTTP 409, code `IDEMPOTENCY_KEY_MISMATCH` (same key, different body)
    - `MethodArgumentNotValidException` → HTTP 400, `ApiError` with `fieldErrors[]`
    - `MissingRequestHeaderException` (for missing `Idempotency-Key`) → HTTP 400, code `IDEMPOTENCY_KEY_REQUIRED`
    - `Exception` (catch-all) → HTTP 500, code `INTERNAL_ERROR`, message redacted
  - Uses the shared `ApiError` record from `common-exceptions` (Step 1.2). Do NOT redefine locally.
  - Saga-related exceptions (`SagaCompensationFailedException`, etc.) are server-side and never reach the controller advice — they're handled in the saga layer with retry + alert.
  - Test class verifies each handler produces the expected status + ApiError body via `@WebMvcTest`.
- **Acceptance criteria**: All known error paths return uniformly-shaped `ApiError` JSON. Integration tests assert the exact ApiError shape and HTTP status for each exception. Unhandled exceptions no longer leak Spring's default error format. `GET /v1/orders/nonexistent` returns HTTP 404 with `ApiError { code: "ORDER_NOT_FOUND" }`.
- **Dependencies**: 8.11, 1.2 (shared `ApiError`)

---

## Phase 9: Kitchen Service

> Goal: restaurants can manage tickets through their lifecycle, and capacity-based auto-pause works end-to-end.

### Step 9.1: kitchen-service skeleton + DynamoDB schema
- [ ] **Objective**: Spring Boot service backed by DynamoDB for tickets and capacity counters.
- **Files to create**:
  - `services/kitchen-service/pom.xml`
  - `services/kitchen-service/src/main/java/.../KitchenApplication.java`
  - `services/kitchen-service/src/main/java/.../domain/Ticket.java`
  - `services/kitchen-service/src/main/java/.../domain/RestaurantCapacity.java`
  - `services/kitchen-service/src/main/java/.../domain/TicketRepository.java`
  - `services/kitchen-service/src/main/resources/application.yml`
  - `services/kitchen-service/Dockerfile`
- **Key details**:
  - Tickets table: PK = `restaurantId`, SK = `ticketId`, attributes: `orderId, items, state, createdAt, acceptedAt, readyAt`
  - GSI on `state` for "list active tickets per restaurant"
  - `RestaurantCapacity`: PK = `restaurantId`, attributes: `active_count` (atomic counter), `paused`, `pause_threshold` (default 20)
  - States: `ACCEPTED`, `PREPARING`, `READY_FOR_PICKUP`, `CANCELED`
- **Acceptance criteria**: Insert a ticket, list tickets by state, atomic increment of capacity counter.
- **Dependencies**: 1.4

### Step 9.2: ORDER_PAID listener + ticket lifecycle endpoints
- [ ] **Objective**: Consume `ORDER_PAID`, create ticket; expose endpoints to transition states.
- **Files to create**:
  - `services/kitchen-service/src/main/java/.../listener/OrderPaidListener.java`
  - `services/kitchen-service/src/main/java/.../service/TicketService.java`
  - `services/kitchen-service/src/main/java/.../api/TicketController.java`
  - `services/kitchen-service/src/test/java/.../listener/OrderPaidListenerIT.java`
- **Key details**:
  - On `ORDER_PAID`: insert ticket in `ACCEPTED` state with conditional `attribute_not_exists(SK)` for idempotency
  - Atomic counter increment on capacity (`UpdateExpression: ADD active_count :inc`)
  - If post-increment counter > threshold: write outbox row `RESTAURANT_PAUSED`
  - Transition endpoint: `PATCH /v1/tickets/{id}/status` with allowed transitions enforced server-side
  - On reach `READY_FOR_PICKUP`: write outbox row `FOOD_READY` (DDB-Streams-driven publisher emits)
  - On any terminal state (`READY_FOR_PICKUP` or `CANCELED`): decrement capacity counter
- **Acceptance criteria**: Publish `ORDER_PAID` → ticket appears. PATCH to `READY_FOR_PICKUP` → `FOOD_READY` event published.
- **Dependencies**: 9.1, 8.4

### Step 9.3: Capacity-based auto-pause + auto-resume
- [ ] **Objective**: Configurable threshold pauses restaurant; idle period auto-resumes.
- **Files to create**:
  - `services/kitchen-service/src/main/java/.../service/CapacityManager.java`
  - `services/kitchen-service/src/main/java/.../scheduler/AutoResumeScheduler.java`
  - `services/kitchen-service/src/test/java/.../service/CapacityManagerIT.java`
- **Key details**:
  - Pause emits `RESTAURANT_PAUSED` once (deduplicated via `paused` flag in DDB)
  - Auto-resume scheduler runs every minute; for restaurants paused > 30 minutes with `active_count < threshold/2`, emit `RESTAURANT_RESUMED`
  - Per-restaurant override: admins can manually pause/resume via REST endpoint
- **Acceptance criteria**: Push 21 tickets → restaurant paused, Menu service hides it. After tickets clear and 30 min elapse → resumed automatically.
- **Dependencies**: 9.2

### Step 9.4: CANCEL_KITCHEN_TICKET compensation handler + outbox publisher
- [ ] **Objective**: Listen for compensation commands, free capacity, ack via outbox.
- **Files to create**:
  - `services/kitchen-service/src/main/java/.../listener/CompensationListener.java`
  - `services/kitchen-service/lambdas/outbox-publisher/template.yaml`
  - `services/kitchen-service/lambdas/outbox-publisher/src/...`
- **Key details**:
  - Listener consumes `CANCEL_KITCHEN_TICKET` from the `kitchen-compensation` SQS queue (provisioned in Step 0.8 — compensation commands intentionally use SQS for point-to-point delivery, not Kafka)
  - Idempotent: if ticket already in terminal state, just emit `TICKET_CANCELED` ack
  - On valid cancel: transition ticket to `CANCELED`, decrement capacity, emit `TICKET_CANCELED` ack via outbox → Kafka topic `kitchen-events`
  - Outbox is a DynamoDB table; publisher Lambda triggers on DDB Stream and produces to Kafka
- **Acceptance criteria**: Inject `CANCEL_KITCHEN_TICKET` after ticket created → ticket canceled, capacity freed, ack published.
- **Dependencies**: 9.3

### Step 9.5: K8s manifests + deployment
- [ ] **Objective**: Wire kitchen-service into GitOps.
- **Files to create**:
  - `food-ordering-gitops/apps/kitchen-service/base/...`
  - `food-ordering-gitops/apps/kitchen-service/overlays/{staging,production}/...`
  - `food-ordering-gitops/argocd/applications/kitchen-service-staging.yaml`
- **Key details**:
  - Standard manifest set following the user-service template
  - 2 replicas staging / 3 prod
  - HPA on RPS
- **Acceptance criteria**: ArgoCD shows kitchen-service Healthy. End-to-end: order paid → ticket appears in restaurant POS view (returned by GET endpoint).
- **Dependencies**: 9.4, 0.11

---

## Phase 10: Delivery (Dispatch) Service

> Goal: drivers race to claim tasks, status updates flow in order, compensation works.

### Step 10.1: delivery-service skeleton + RDS schema
- [ ] **Objective**: Spring Boot project, schema for delivery tasks and drivers.
- **Files to create**:
  - `services/delivery-service/pom.xml`
  - `services/delivery-service/src/main/java/.../DeliveryApplication.java`
  - `services/delivery-service/src/main/resources/db/migration/V1__delivery_tasks.sql`
  - `services/delivery-service/src/main/resources/db/migration/V2__driver_status.sql`
  - `services/delivery-service/src/main/resources/db/migration/V3__outbox.sql`
  - `services/delivery-service/src/main/java/.../domain/DeliveryTask.java`
  - `services/delivery-service/src/main/java/.../domain/DriverStatus.java`
  - `services/delivery-service/Dockerfile`
- **Key details**:
  - `delivery_tasks(id, order_id, restaurant_id, customer_address, status, driver_id NULL, broadcast_at, claimed_at, picked_up_at, delivered_at, version)` — `id` UUID, unique on `order_id`
  - `driver_status(driver_id, online, last_heartbeat_at, current_task_id NULL)`
  - States: `BROADCAST, ASSIGNED, PICKED_UP, DELIVERED, FAILED, CANCELED`
- **Acceptance criteria**: Migrations apply. Service starts.
- **Dependencies**: 1.4

### Step 10.2: FOOD_READY listener creates task + broadcasts to drivers
- [ ] **Objective**: Consume `FOOD_READY`, create `delivery_task`, broadcast via SNS Mobile Push.
- **Files to create**:
  - `services/delivery-service/src/main/java/.../listener/FoodReadyListener.java`
  - `services/delivery-service/src/main/java/.../service/DeliveryTaskService.java`
  - `services/delivery-service/src/main/java/.../client/DriverPushBroadcaster.java`
- **Key details**:
  - Idempotent on `order_id`: insert with `ON CONFLICT DO NOTHING`
  - Broadcast: query online drivers near restaurant address (distance approximation by lat/lng or simple zone) — for v1, broadcast to all online drivers in the restaurant's city
  - Each driver receives a push notification with task summary; drivers must call claim endpoint to actually take it
  - Outbox row: `DELIVERY_BROADCAST` for analytics
- **Acceptance criteria**: Publish `FOOD_READY` → task created → driver test app receives push.
- **Dependencies**: 10.1, 9.2

### Step 10.3: Claim endpoint with FOR UPDATE NOWAIT race resolution
- [ ] **Objective**: `POST /v1/delivery/tasks/{id}/claim` — first driver wins, others get 409.
- **Files to create**:
  - `services/delivery-service/src/main/java/.../api/DeliveryClaimController.java`
  - `services/delivery-service/src/main/java/.../service/ClaimService.java`
  - `services/delivery-service/src/test/java/.../api/DeliveryClaimRaceIT.java`
- **Key details**:
  - SQL: `SELECT * FROM delivery_tasks WHERE id = ? FOR UPDATE NOWAIT` — fails fast if locked
  - On lock fail or already claimed: 409 Conflict
  - Update task state to `ASSIGNED` with `driver_id = current driver`, write outbox row `DELIVERY_ASSIGNED`
  - Update `driver_status.current_task_id`
  - Race test: spawn 100 concurrent claim attempts → exactly 1 success, 99 receive 409
- **Acceptance criteria**: Race test passes. Driver who claims first sees task on their app.
- **Dependencies**: 10.2

### Step 10.4: Status update endpoint + FIFO queue + compensation handler
- [ ] **Objective**: Driver progresses task; status updates use SQS FIFO grouped by `driverId`. Cancel handler frees driver.
- **Files to create**:
  - `services/delivery-service/src/main/java/.../api/DeliveryStatusController.java`
  - `services/delivery-service/src/main/java/.../service/StatusUpdateService.java`
  - `services/delivery-service/src/main/java/.../listener/StatusUpdateConsumer.java`
  - `services/delivery-service/src/main/java/.../listener/CompensationListener.java`
- **Key details**:
  - PATCH endpoint enqueues update to FIFO queue with `MessageGroupId = driverId` (ensures per-driver ordering)
  - Consumer applies the update, writes outbox row (`DELIVERY_PICKED_UP`, `ORDER_DELIVERED`, `DELIVERY_FAILED`)
  - On `ORDER_DELIVERED`: free driver (`current_task_id = NULL`)
  - Compensation listener for `RELEASE_DRIVER`: revert task to `BROADCAST`, free driver, emit ack
  - Status updates are idempotent on `(taskId, status)`
- **Acceptance criteria**: Driver moves task PICKED_UP → DELIVERED → outbox publishes `ORDER_DELIVERED`. Concurrent updates from same driver applied in order.
- **Dependencies**: 10.3

### Step 10.5: K8s manifests + deployment + end-to-end test
- [ ] **Objective**: Wire delivery-service into GitOps and validate the full order-to-delivered flow.
- **Files to create**:
  - `food-ordering-gitops/apps/delivery-service/base/...`
  - `food-ordering-gitops/apps/delivery-service/overlays/{staging,production}/...`
  - `food-ordering-gitops/argocd/applications/delivery-service-staging.yaml`
  - `e2e-tests/scenarios/full-happy-path.yaml` (Postman/Newman or k6)
- **Key details**:
  - Standard manifest set
  - 2 replicas staging / 3 prod
  - End-to-end test: register customer + driver → add to basket → checkout → ticket created → mark ready → driver claims → driver delivers → order DELIVERED
- **Acceptance criteria**: End-to-end test passes against staging.
- **Dependencies**: 10.4, 0.11

---

## Phase 11: Review & Feedback Service

> Goal: customers can rate the restaurant, driver, and individual meals after delivery; aggregates update automatically via DynamoDB Streams.

### Step 11.1: review-service skeleton + DynamoDB schema
- [ ] **Objective**: Spring Boot service with the `reviews` and `review-aggregates` DynamoDB tables.
- **Files to create**:
  - `services/review-service/pom.xml`
  - `services/review-service/src/main/java/.../ReviewApplication.java`
  - `services/review-service/src/main/java/.../domain/Review.java`
  - `services/review-service/src/main/java/.../domain/ReviewAggregate.java`
  - `services/review-service/src/main/java/.../domain/ReviewRepository.java`
  - `services/review-service/src/main/resources/application.yml`
  - `services/review-service/Dockerfile`
- **Key details**:
  - `reviews` table: PK = `REVIEW#{type}#{entityId}`, SK = `{orderId}#{userId}` — supports listing all reviews per entity
  - `type` enum: `RESTAURANT`, `DRIVER`, `MEAL`
  - Attributes: `rating` (1–5), `comment` (text, max 500 chars), `submittedAt`, `editableUntil` (24h after submission), `language`
  - GSI on `(userId, submittedAt)` for "my reviews" view
  - `review-aggregates` table: PK = `REVIEW_AGG#{type}#{entityId}`, attributes: `count`, `sum`, `avg`, `histogram` (count per star), `lastUpdated`
- **Acceptance criteria**: Insert reviews, list by entity, list by user. Schema matches design.
- **Dependencies**: 1.4

### Step 11.2: ORDER_DELIVERED listener + REST endpoints
- [ ] **Objective**: Open the review window on delivery, expose CRUD endpoints with edit window enforcement.
- **Files to create**:
  - `services/review-service/src/main/java/.../listener/OrderDeliveredListener.java`
  - `services/review-service/src/main/java/.../api/ReviewController.java`
  - `services/review-service/src/main/java/.../service/ReviewService.java`
  - `services/review-service/src/main/java/.../api/dto/CreateReviewRequest.java`
  - `services/review-service/src/test/java/.../api/ReviewControllerIT.java`
- **Key details**:
  - On `ORDER_DELIVERED`: write a `review_window` row keyed by `orderId` with TTL 7 days — opens the customer's ability to review for that order
  - `POST /v1/reviews` validates: review window exists for `(userId, orderId)`, no existing review for the same `(userId, orderId, type, entityId)` tuple
  - `PATCH /v1/reviews/{id}` only allowed within `editableUntil` (24h after submission) — returns 409 otherwise
  - `GET /v1/restaurants/{id}/reviews` and `GET /v1/drivers/{id}/reviews` paginated, sortable by recency or rating
  - Profanity filter via simple word list (configurable in S3) — flagged reviews queued for moderation, hidden from public
- **Acceptance criteria**: After `ORDER_DELIVERED`, customer can submit one review per (entity-type, entity-id). Editing after 24h returns 409.
- **Dependencies**: 11.1, 10.4

### Step 11.3: Aggregation Lambda via DynamoDB Streams
- [ ] **Objective**: Lambda reads reviews stream, updates aggregate counters in real time.
- **Files to create**:
  - `services/review-service/lambdas/aggregator/template.yaml`
  - `services/review-service/lambdas/aggregator/src/main/java/.../AggregatorHandler.java`
  - `services/review-service/lambdas/aggregator/src/test/java/.../AggregatorHandlerTest.java`
- **Key details**:
  - DDB Stream event source on `reviews` table, batch size 25, parallelization 4
  - For each `INSERT`: atomically increment aggregate `count`, `sum`, `histogram[rating]` using `UpdateExpression: ADD count :one, sum :rating, histogram.#r :one`
  - For each `MODIFY`: subtract old rating from `sum`/`histogram`, add new (only when rating actually changed)
  - For each `REMOVE` (rare, soft-deletes): subtract from aggregate
  - Compute `avg = sum / count` lazily on read (cheaper than rewriting on every update)
  - Failed batches go to SQS DLQ with alarm
- **Acceptance criteria**: Submit a 5-star review → aggregate `count` increments by 1, `histogram.5` increments by 1, within 2 seconds. Edit to 3-star → adjustments applied.
- **Dependencies**: 11.2

### Step 11.4: K8s manifests + deployment
- [ ] **Objective**: Wire review-service into GitOps; deploy aggregator Lambda.
- **Files to create**:
  - `food-ordering-gitops/apps/review-service/base/...`
  - `food-ordering-gitops/apps/review-service/overlays/{staging,production}/...`
  - `food-ordering-gitops/argocd/applications/review-service-staging.yaml`
- **Key details**:
  - Standard manifest set
  - 2 replicas staging / 2 prod (low criticality, low traffic)
  - Aggregator Lambda deployed via SAM in same pipeline as the service
- **Acceptance criteria**: ArgoCD shows review-service Healthy. Public read endpoints respond. Aggregates update on review submission.
- **Dependencies**: 11.3, 0.11

### Step 11.5: Address review-service audit gaps
- [ ] **Objective**: Close the audit gaps identified in `docs/API_AUDIT.md` for review-service: pagination on per-order review listing, uniqueness constraint preventing duplicate reviews from the same user for the same order.
- **Files to modify**:
  - `services/review-service/src/main/java/.../api/ReviewController.java`
  - `services/review-service/src/main/java/.../service/ReviewService.java`
  - `services/review-service/src/main/java/.../domain/ReviewRepository.java`
- **Key details**:
  - **Audit §7 for review-service**: `GET /v1/reviews/orders/{orderId}` and `GET /v1/restaurants/{id}/reviews` accept `Pageable` parameter and return `Page<ReviewResponseDto>` — never unbounded `List`. Defaults `@PageableDefault(size = 20, sort = "submittedAt", direction = DESC)`.
  - **Audit §9 for review-service**: prevent duplicate reviews from the same user for the same order. Since DynamoDB doesn't enforce composite uniqueness across PK/SK alone, the existing PK `REVIEW#{type}#{entityId}` + SK `{orderId}#{userId}` already prevents duplicates *for the same entity*. But a user could currently submit two reviews of *different* entities for the same order (e.g., one for the restaurant and one for the driver) — which is actually allowed by design. The gap is duplicate review of the *same entity* by the same user for the same order, which is naturally prevented by the (PK, SK) combination + `PutItem` with `attribute_not_exists(PK)`.
  - Surface the duplicate-detection failure as HTTP 409 + `ApiError { code: "REVIEW_ALREADY_SUBMITTED" }` via the global exception handler (the existing one — review-service already has `@RestControllerAdvice`, just add the new mapping).
- **Acceptance criteria**: Listing reviews returns a page object (size, total, content[]). Posting a second review with the same `(type, entityId, orderId, userId)` tuple returns 409 with a typed `ApiError` body.
- **Dependencies**: 11.4

---

## Phase 12: Observability

> Goal: every service is fully observable with metrics, traces, logs, and SLO-based alerts. By end of phase, on-call engineer can diagnose any outage in < 5 minutes.
>
> **Note**: Step 12.1 below was pulled forward into the user-service pilot (executed during Phase 2). The user-service dashboard and Managed Prometheus / Managed Grafana setup already exist by the time Phase 12 starts. Phase 12 covers: (a) per-service dashboards for the remaining 9 services, (b) cross-cutting X-Ray tracing, (c) SLO-based alerts. Step 12.1 here remains as a reference for what the pilot work delivered.

### Step 12.1: Amazon Managed Prometheus + Managed Grafana setup
- [ ] **Objective**: Provision the observability backend and connect EKS to it.
- **Files to create**:
  - `platform-infra/modules/observability/main.tf`
  - `platform-infra/envs/shared/observability.tf`
  - `food-ordering-gitops/apps/observability/prometheus-agent/...`
  - `food-ordering-gitops/apps/observability/grafana-datasources/...`
- **Key details**:
  - Amazon Managed Service for Prometheus (AMP) workspace per environment
  - Amazon Managed Grafana workspace (single, with environment switching via Grafana variables)
  - Prometheus agent (kube-prometheus-stack Helm chart with `agent` mode) deployed on EKS via ArgoCD
  - Agent forwards scraped metrics to AMP via `remote_write` with SigV4 auth
  - Grafana data source: AMP for metrics, CloudWatch for logs, X-Ray for traces
  - SAML/Okta SSO for Grafana access; viewer role default, editor for SREs, admin restricted
- **Acceptance criteria**: Grafana shows AMP datasource healthy. Out-of-the-box K8s dashboards display data from EKS.
- **Dependencies**: 0.11

### Step 12.2: Per-service dashboards + ServiceMonitors
- [ ] **Objective**: Build standardized dashboards for all 10 services covering RED metrics (Rate, Errors, Duration) plus service-specific panels.
- **Files to create**:
  - `food-ordering-gitops/apps/observability/dashboards/identity-dashboard.json`
  - `food-ordering-gitops/apps/observability/dashboards/order-saga-dashboard.json` (already started in Step 8.10)
  - `food-ordering-gitops/apps/observability/dashboards/payment-dashboard.json`
  - `food-ordering-gitops/apps/observability/dashboards/menu-dashboard.json`
  - ... (one per service)
  - `food-ordering-gitops/apps/observability/dashboards/platform-overview.json`
- **Key details**:
  - Standard panels for every service: requests/sec, error rate, p50/p95/p99 latency, JVM heap, GC time, DB pool usage, circuit breaker state
  - Service-specific panels: Order saga state distribution, Payment Stripe latency, Menu cache hit rate, Basket gRPC fallback rate, Kitchen ticket queue depth
  - Platform overview: order success rate (top-level SLO), end-to-end checkout latency, total revenue/hour
  - All dashboards stored as JSON in GitOps and provisioned via Grafana operator
  - Per-service ServiceMonitor in K8s manifests (already added in earlier phases) — verify all are present
- **Acceptance criteria**: All 10 service dashboards render data in Grafana. Platform overview shows live order flow.
- **Dependencies**: 12.1, all service deployment steps

### Step 12.3: AWS X-Ray distributed tracing across services
- [ ] **Objective**: Trace context propagates through HTTP, gRPC, and SQS so a single trace shows the full saga.
- **Files to create**:
  - `common-libs/common-observability/src/main/java/.../obs/XRayConfig.java`
  - `common-libs/common-observability/src/main/java/.../obs/SqsTracePropagator.java`
  - `common-libs/common-observability/src/main/java/.../obs/GrpcTracingInterceptor.java`
  - `food-ordering-gitops/apps/observability/otel-collector/...`
- **Key details**:
  - OpenTelemetry Java agent attached to every service (via JVM `-javaagent:` flag in Dockerfile)
  - OTel Collector deployed as DaemonSet on EKS, exports traces to X-Ray via `awsxray` exporter
  - SQS message attributes carry `traceId` and `traceparent` headers; producers set them in outbox publishers, consumers extract on receipt
  - gRPC client/server interceptors propagate trace context in metadata
  - Sampling: tail-based 1% in prod, 100% on errors (`sampler.error_rate=1.0`)
- **Acceptance criteria**: Single end-to-end order shows up as one trace in X-Ray spanning Order → Promotion → Payment → Kitchen → Delivery.
- **Dependencies**: 12.2

### Step 12.4: SLO-based alerts + runbooks
- [ ] **Objective**: Define SLOs and alert when error budget is at risk. Link every alert to a runbook.
- **Files to create**:
  - `food-ordering-gitops/apps/observability/alerts/slo-orders.yaml` (PrometheusRule)
  - `food-ordering-gitops/apps/observability/alerts/slo-payments.yaml`
  - `food-ordering-gitops/apps/observability/alerts/slo-platform.yaml`
  - `food-ordering-gitops/apps/observability/runbooks/order-success-rate.md`
  - `food-ordering-gitops/apps/observability/runbooks/payment-circuit-open.md`
  - `food-ordering-gitops/apps/observability/runbooks/saga-stuck.md`
  - `platform-infra/envs/shared/sns-pagerduty.tf`
- **Key details**:
  - Top SLOs: Order success rate ≥ 99.5%, Checkout p99 latency < 3s, Payment success rate ≥ 99.9%, API availability ≥ 99.95%
  - Burn rate alerting: 2% budget burn in 1h = page on-call; 5% in 6h = page secondary
  - Page severity: SEV1 (paging) for SLO-impacting alerts only; SEV2 (Slack) for non-SLO operational issues
  - SNS topic → PagerDuty integration via webhook; secondary topic → Slack via AWS Chatbot
  - Each runbook: alert description, dashboard link, common causes, mitigation steps, escalation path
- **Acceptance criteria**: Inject 5% checkout failures via fault injection → burn-rate alert fires → PagerDuty receives page → runbook link present.
- **Dependencies**: 12.3

---

## Phase 13: CI/CD on AWS

> Goal: every service has a fully AWS-native pipeline triggered from the **single** `food-ordering-platform` monorepo via path-filtered EventBridge rules. **No GitHub Actions anywhere.** All pipelines write image-tag bumps to the companion `food-ordering-gitops` repo, which ArgoCD reconciles to EKS.
>
> **Note**: Steps 13.1, 13.2, and 13.3 below were pulled forward into the user-service pilot (executed during Phase 2). The path-filter Lambda, buildspec templates, and user-service staging pipeline already exist by the time Phase 13 starts. Phase 13 covers: (a) production deployment with manual approval + canary (Step 13.4), (b) replicating pipelines to the remaining 9 services (Step 13.5), (c) pipeline + ArgoCD notifications (Step 13.6). Steps 13.1–13.3 here remain as a reference for what the pilot work delivered.

### Step 13.1: CodeCommit access policies + path-filter Lambda + IAM cross-cutting
- [ ] **Objective**: Configure access controls on the two CodeCommit repos created in Step 0.1, build the path-filter Lambda that decides which pipelines to start on a given commit, and finalize CI IAM roles. (CodeArtifact and most IAM was already provisioned in Step 0.9 — this step is the CI-pipeline-specific glue.)
- **Files to create**:
  - `platform-infra/envs/shared/codecommit-policies.tf` (approval rules, branch protection)
  - `platform-infra/modules/path-filter-lambda/main.tf`
  - `platform-infra/modules/path-filter-lambda/src/handler.py`
  - `platform-infra/envs/shared/eventbridge-codecommit.tf`
  - `platform-infra/envs/shared/iam-cicd-extra.tf` (additional roles for path-filter Lambda + cross-pipeline triggers)
- **Key details**:
  - **Path-filter Lambda** receives EventBridge `CodeCommit Repository State Change` events for `food-ordering-platform`. It uses `git diff --name-only` (via the CodeCommit GetDifferences API) between the previous and new commits to determine which top-level directories changed.
  - Routing logic the Lambda implements:
    - Touched `services/{name}/**` → start `pipeline-{name}-staging`
    - Touched `common-libs/**` or `platform-bom/**` → start ALL 10 service pipelines (parallel)
    - Touched `platform-infra/**` → start `pipeline-platform-infra-staging` (Terraform plan + apply with manual approval)
    - Touched `e2e-tests/**` → start `pipeline-e2e-tests`
  - The Lambda calls `codepipeline:StartPipelineExecution` on each affected pipeline.
  - **Approval rules** on `food-ordering-platform/main`: 1 approval required, all status checks (build of changed services) must pass before merge.
  - **Approval rules** on `food-ordering-gitops/main`: 2 approvals for `apps/**/overlays/production/**` paths only (production manifest changes need extra eyeballs).
  - SSH key (already provisioned in Step 0.11) is what ArgoCD uses to read `food-ordering-gitops`. CodeBuild gitops-bump jobs use a separate, write-scoped IAM user with HTTPS git credentials in Secrets Manager.
- **Acceptance criteria**: Pushing a commit that touches only `services/user-service/**` triggers `pipeline-user-service-staging` and no other pipelines. Pushing a commit that touches `platform-bom/pom.xml` triggers all 10 service pipelines.
- **Dependencies**: 0.9, 0.11

### Step 13.2: Reusable buildspec templates + monorepo helper scripts
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
  - `gitops-bump.yml`: clones `food-ordering-gitops`, edits `apps/$SERVICE/overlays/$ENV/image-tag.yaml`, commits with `chore(deploy): $SERVICE → $TAG [$ENV]`, pushes. ArgoCD picks up within ~1 minute.
  - `smoke-test.yml`: hits service health endpoint in target env, runs k6 perf script with SLO thresholds.
  - All buildspecs use the **monorepo root** as `CODEBUILD_SRC_DIR` and rely on `SERVICE_PATH` (e.g., `services/user-service`) being set per pipeline.
- **Acceptance criteria**: A test service using these buildspecs builds, tests, scans, and pushes an image to ECR successfully. Modifying a shared lib triggers a rebuild of dependent services thanks to `mvn -am`.
- **Dependencies**: 13.1

### Step 13.3: CodePipeline Terraform module + first pipeline (user-service staging)
- [ ] **Objective**: Reusable Terraform module that defines the full pipeline; instantiate for user-service staging deployment as the proving ground.
- **Files to create**:
  - `platform-infra/modules/service-pipeline/main.tf`
  - `platform-infra/modules/service-pipeline/variables.tf`
  - `platform-infra/modules/service-pipeline/codebuild-projects.tf`
  - `platform-infra/modules/service-pipeline/pipeline-stages.tf`
  - `platform-infra/modules/service-pipeline/eventbridge-trigger.tf`
  - `platform-infra/envs/staging/pipelines/identity-pipeline.tf`
- **Key details**:
  - Module variables: `service_name`, `service_path` (e.g., `services/user-service`), `java_version` (default `25`), `has_database` (toggles PG Testcontainer), `has_grpc`, `has_kafka` (toggles Kafka Testcontainer), `target_env`.
  - The module **does not** create its own EventBridge rule — instead it registers the pipeline with the path-filter Lambda from 13.1 (via SSM Parameter Store config).
  - Pipeline stages match `architecture.md` Section 10.4: Source (CodeCommit `food-ordering-platform`) → Build → Test (parallel) → IntegrationTest → SAST/SCA → PackageAndPush → InspectorScan → DeployStaging (gitops bump) → SmokeTest → ProductionApproval → DeployProduction.
  - Inspector scan as a Lambda action that polls Inspector v2 API for image findings, fails on Critical.
  - All artifacts stored in a per-pipeline S3 bucket with KMS encryption + 90-day lifecycle.
  - Pipeline events to EventBridge → SNS → Slack via Chatbot.
- **Acceptance criteria**: Push to `services/user-service/**` on the monorepo's main branch triggers `pipeline-user-service-staging`, which runs all stages → image lands in ECR → `food-ordering-gitops` gets the bump commit → ArgoCD deploys to staging → smoke test passes.
- **Dependencies**: 13.2, 2.5

### Step 13.4: Production deployment with manual approval + Argo Rollouts canary
- [ ] **Objective**: Add the production deployment branch with manual approval and progressive canary.
- **Files to create**:
  - `platform-infra/envs/production/pipelines/identity-pipeline.tf`
  - `food-ordering-gitops/apps/user-service/overlays/production/rollout.yaml`
  - `food-ordering-gitops/apps/_argo-rollouts/install.yaml`
  - `food-ordering-gitops/apps/_argo-rollouts/analysis-template-error-rate.yaml`
- **Key details**:
  - CodePipeline manual approval action; SNS topic `production-deploy-approvals` notifies approvers via Slack and email.
  - Approver IAM group `production-deployers` (audited via CloudTrail).
  - Argo Rollouts `Rollout` replaces `Deployment` for production overlay; canary 10% → 50% → 100% with 5/10/0 minute pauses.
  - AnalysisTemplate queries Prometheus for error rate; `errorRate > 0.01` aborts and rolls back automatically.
  - Auto-rollback also tied to CloudWatch alarm via Lambda hook (defense in depth).
- **Acceptance criteria**: Promote user-service to production via approval. Canary progresses through 10/50/100. Inject errors during 50% phase → automated rollback.
- **Dependencies**: 13.3

### Step 13.5: Replicate pipelines for all 10 services
- [ ] **Objective**: Use the Terraform module to create pipelines for the remaining 9 services. Each pipeline is one short module instantiation; the path-filter Lambda (Step 13.1) handles the trigger fan-out.
- **Files to create**:
  - `platform-infra/envs/staging/pipelines/menu-pipeline.tf`
  - `platform-infra/envs/staging/pipelines/basket-pipeline.tf`
  - `platform-infra/envs/staging/pipelines/order-pipeline.tf`
  - `platform-infra/envs/staging/pipelines/kitchen-pipeline.tf`
  - `platform-infra/envs/staging/pipelines/payment-pipeline.tf`
  - `platform-infra/envs/staging/pipelines/promotion-pipeline.tf`
  - `platform-infra/envs/staging/pipelines/delivery-pipeline.tf`
  - `platform-infra/envs/staging/pipelines/review-pipeline.tf`
  - `platform-infra/envs/staging/pipelines/notification-pipeline.tf` (uses SAM-deploy variant)
  - `platform-infra/envs/production/pipelines/*.tf` (one per service)
- **Key details**:
  - Each `.tf` file is ~12 lines: instantiates the `service-pipeline` module from Step 13.3 with `service_name`, `service_path`, `has_kafka`, `has_database`, `has_grpc` flags.
  - **Notification** uses a different module variant (`service-pipeline-lambda`) that ends in `sam deploy` instead of gitops bump.
  - **Payment** pipeline has a business-hours gate on production deploys (Lambda check before approval action).
  - **Order Service** pipeline has an additional integration test stage running full saga simulation with **Testcontainers Kafka** + LocalStack.
  - All pipelines source from `food-ordering-platform` (monorepo); the path-filter Lambda determines which one(s) to start on a given commit.
- **Acceptance criteria**: All 10 service pipelines visible in CodePipeline console. Pushing to `services/{any}/**` triggers the matching pipeline only. Pushing to `common-libs/**` triggers all 10.
- **Dependencies**: 13.4

### Step 13.6: Pipeline notifications + ArgoCD sync notifications
- [ ] **Objective**: All pipeline events surface in Slack and PagerDuty appropriately.
- **Files to create**:
  - `platform-infra/envs/shared/eventbridge-pipeline-events.tf`
  - `platform-infra/envs/shared/aws-chatbot.tf`
  - `food-ordering-gitops/argocd/notifications/configmap.yaml`
  - `food-ordering-gitops/argocd/notifications/triggers.yaml`
- **Key details**:
  - EventBridge rules match `aws.codepipeline` events; route to SNS topic by severity.
  - AWS Chatbot configured with Slack workspace; `pipeline-events` channel for staging, `prod-deploys` for production.
  - Failed prod pipeline → PagerDuty page on-call SRE.
  - ArgoCD Notifications service sends sync events: `OutOfSync` warning to Slack after 5 min, `Degraded` page to PagerDuty.
  - Weekly pipeline metrics report: deployment frequency, lead time, change failure rate, MTTR (DORA metrics) — generated by scheduled Lambda → email.
- **Acceptance criteria**: Trigger a failing build → Slack notification appears within 30s. Force a prod ArgoCD app to Degraded → PagerDuty page received.
- **Dependencies**: 13.5

---

## Phase 14: End-to-End Testing

> Goal: automated test suites validate the three core flows (happy path, cancel, error) on every staging deploy. Load testing uncovers capacity limits before production traffic does.

### Step 14.0: Per-service test scaffolding (close audit §12)
- [ ] **Objective**: Close the cross-cutting testability gap identified in `docs/API_AUDIT.md` §12. Every HTTP-exposing service gets controller-slice tests, repository-slice tests, and at least one happy-path service-layer test. The Saga end-to-end integration test comes in Step 14.1.
- **Files to create** (per service that has HTTP endpoints — user, order, product, basket, kitchen, delivery, review, promotion):
  - `services/{name}/src/test/java/.../api/{Resource}ControllerTest.java` — `@WebMvcTest` per controller; covers happy path + validation errors + 4xx responses; mocks the service layer
  - `services/{name}/src/test/java/.../domain/{Resource}RepositoryTest.java` — `@DataJpaTest` for JPA services; DynamoDB Enhanced Client tests with LocalStack for product and kitchen and review
  - `services/{name}/src/test/java/.../service/{Resource}ServiceTest.java` — happy path of the main service class with Mockito for dependencies
  - `services/{name}/src/test/java/.../config/IntegrationTestBase.java` — shared Testcontainers setup (Postgres, Redis, Kafka per service needs) reused across IT tests
- **Key details**:
  - **Coverage target**: ≥ 80% line coverage gate per service (already configured in `buildspec-build-test-scan.yml`); per-class minimum 70% on `service/` and `api/` packages.
  - **JUnit 5 + AssertJ** as the standard. No legacy JUnit 4. AssertJ for assertions everywhere, NOT JUnit `assertEquals`.
  - **Mockito 5+**; no PowerMock.
  - **Testcontainers** for any test crossing a DB/queue/cache boundary. Reuse strategy: `IntegrationTestBase` declares static containers shared across tests in the same module.
  - **Contract tests** (DTO schema): one test per service that asserts the wire shape of `ApiError`, the main request/response DTOs, and any saga events. Catches accidental breaking changes before they ship.
  - The audit's call for "Saga integration test" lives in Step 14.1 — it's an end-to-end test across services, not a per-service scaffold concern.
  - **Sequencing**: this step is broken down per-service in practice. The single build-plan checkbox represents "all services have the slice + service-layer + contract tests passing in CI." Mark done only when the coverage gate is green for every service.
- **Acceptance criteria**: Every service's pipeline shows ≥ 80% line coverage in JaCoCo report. Every service has at least one `@WebMvcTest`, one repository slice or Testcontainers-backed test, one service-layer happy-path test, and one ApiError-contract test. Placeholder `contextLoads()` tests removed from every service.
- **Dependencies**: 11.5 (every per-service phase complete, audit gaps closed)

### Step 14.1: E2E happy path test (full order lifecycle)
- [ ] **Objective**: Postman/Newman or k6 script that drives a full order from registration to delivery against staging.
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
  - Runs as a CodePipeline post-deploy stage in every staging deploy
- **Acceptance criteria**: Test passes consistently in staging. Failures clearly identify which service/step caused failure.
- **Dependencies**: 11.4

### Step 14.2: E2E cancel and error flow tests
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
- **Acceptance criteria**: All three scenarios pass in staging. State transitions and side effects validated programmatically.
- **Dependencies**: 14.1

### Step 14.3: Load testing with k6 + Distributed Load Testing on AWS
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

### Step 14.4: Chaos engineering with AWS Fault Injection Simulator
- [ ] **Objective**: Validate resilience patterns work under real failure conditions.
- **Files to create**:
  - `chaos/experiments/kill-payment-pod.json` (FIS template)
  - `chaos/experiments/inject-stripe-latency.json`
  - `chaos/experiments/network-partition-redis.json`
  - `chaos/experiments/db-failover.json`
  - `chaos/runbook.md`
- **Key details**:
  - FIS templates target staging only initially, prod after confidence builds
  - Kill payment pod during active charge → verify retries succeed, no duplicate charges (idempotency works)
  - Inject 5s latency on Stripe → verify circuit breaker opens, fallback returns 503 (not timeout)
  - Network-partition Redis → verify Basket service degrades to 503 instead of hanging, cart data preserved on heal
  - Aurora failover → verify all services reconnect, no transactions lost
  - Each experiment has pre-conditions, expected behaviors, rollback steps, success metrics
  - Run monthly during business hours with on-call engineer present
- **Acceptance criteria**: All four experiments execute without permanent damage. Resilience patterns observed working as designed. Findings documented.
- **Dependencies**: 14.3

---

## Phase 15: Production Hardening

> Goal: production-ready security posture, disaster recovery validated, runbooks complete. By end of phase the system can be opened to real customer traffic.

### Step 15.1: WAF rules + bot protection + DDoS mitigation
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
- **Acceptance criteria**: WAF deployed in count-mode first for 1 week, then switched to block-mode after tuning. Penetration test from Step 15.2 doesn't bypass WAF.
- **Dependencies**: 13.6

### Step 15.2: Security audit + penetration testing
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
- **Dependencies**: 15.1

### Step 15.3: Backup, disaster recovery, and runbook validation
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
- **Dependencies**: 15.2

### Step 15.4: Production launch checklist + on-call rotation
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
- **Acceptance criteria**: Checklist 100% complete. Two consecutive weeks of zero SEV1 incidents in staging soak. Production launch approved.
- **Dependencies**: 15.3

---

# Final Notes

## Estimated Total Effort

| Phase | Steps | Approx Sessions | Parallel Possible? |
|---|---|---|---|
| 0 — Foundation | 11 | 11 | Mostly sequential (IaC dependencies) |
| 1 — Shared Libs + BOM | 4 | 4 | Yes after 1.1 |
| 2 — User Service (pilot) | 7 | 7+ | Sequential; pilot also includes pulled-forward 12.1 + 13.1–13.3 |
| 3 — Notification | 4 | 4 | Sequential |
| 4 — Promotion | 4 | 4 | Sequential, can run alongside 5/6/7 |
| 5 — Product (Menu) | 5 | 5 | Sequential, can run alongside 4/6/7 |
| 6 — Basket | 5 | 5 | Sequential, depends on 5.4 |
| 7 — Payment | 5 | 5 | Sequential, can run alongside 4/5/6 |
| 8 — Order Orchestrator | 12 | 12 | Sequential — the critical path |
| 9 — Kitchen | 5 | 5 | Sequential, depends on 8.4 |
| 10 — Delivery | 5 | 5 | Sequential, depends on 9.2 |
| 11 — Review | 5 | 5 | Sequential, depends on 10.4 |
| 12 — Observability | 4 | 4 | 12.1 already done in pilot; 12.2 is per-service-9, parallelizable |
| 13 — CI/CD | 6 | 6 | 13.1–13.3 already done in pilot; 13.5 fans out across services |
| 14 — E2E Testing | 5 | 5 | 14.0 (per-service test scaffolding) parallelizable across services |
| 15 — Hardening | 4 | 4 | Mostly sequential |
| **Total** | **91** | **~91 sessions** | ~15 weeks of focused work for one engineer |

With 2–3 engineers running parallel sessions where dependencies allow, the practical timeline is ~12–13 weeks.

**Pilot weight**: the user-service pilot (Phase 2 expanded, plus pulled-forward 12.1 + 13.1–13.3) is roughly 11–12 sessions on its own. Budget extra time here because the first service surfaces IRSA, Kustomize, observability, and CI/CD friction that subsequent services dodge. The investment pays back across the remaining 9 services.

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

*End of build-plan.md. Total: 85 build steps across 16 phases (0–15). Reference architecture in companion file `architecture.md`.*