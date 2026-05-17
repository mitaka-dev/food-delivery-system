# Skill-Creator Prompts — Food Ordering Platform

> **Purpose**: Twenty copy-paste-ready prompts for Claude Code's skill-creator. Each prompt produces one SKILL.md for the food-ordering-platform monorepo.
>
> **How to use**: Open Claude Code, invoke `skill-creator` (or run `/create-skill` if your installation has it as a slash command), paste the prompt for the skill you want to build, review the produced SKILL.md before committing.
>
> **Quality bar**: every prompt below specifies (1) skill name, (2) trigger conditions, (3) full content outline, (4) anti-patterns to flag, (5) reference docs to cite. Use them as-is or adjust details to your codebase before invoking skill-creator.
>
> **Companion docs**: `architecture.md`, `build-plan.md`, `common-conventions.md`. Skills reference these as the source of truth.

---

## Index

**Tier 1 — write before Phase 0**
1. [spring-boot-service-conventions](#1-spring-boot-service-conventions)
2. [outbox-pattern](#2-outbox-pattern)
3. [terraform-module-conventions](#3-terraform-module-conventions)
4. [aws-sdk-v2-conventions](#4-aws-sdk-v2-conventions)
5. [monorepo-maven-conventions](#5-monorepo-maven-conventions)

**Tier 2 — write at the start of the matching phase**
6. [kafka-msk-iam-auth](#6-kafka-msk-iam-auth)
7. [dynamodb-access-patterns](#7-dynamodb-access-patterns)
8. [postgresql-flyway-migrations](#8-postgresql-flyway-migrations)
9. [redis-elasticache-patterns](#9-redis-elasticache-patterns)
10. [saga-state-machine](#10-saga-state-machine)
11. [resilience4j-patterns](#11-resilience4j-patterns)
12. [grpc-service-conventions](#12-grpc-service-conventions)

**Tier 3 — write at the start of the matching phase, lighter**
13. [aws-codebuild-buildspec-template](#13-aws-codebuild-buildspec-template)
14. [argocd-app-of-apps](#14-argocd-app-of-apps)
15. [aws-lambda-sam-conventions](#15-aws-lambda-sam-conventions)
16. [observability-stack-conventions](#16-observability-stack-conventions)
17. [test-conventions](#17-test-conventions)
18. [event-schema-evolution](#18-event-schema-evolution)
19. [commit-and-pr-conventions](#19-commit-and-pr-conventions)
20. [local-development-setup](#20-local-development-setup)

---

# Tier 1 — Foundational Skills

## 1. spring-boot-service-conventions

```text
Create a Claude Code skill named `spring-boot-service-conventions`.

PURPOSE
This skill encodes how every Spring Boot service in the food-ordering-platform monorepo is structured. It is the most-loaded skill across the project — used in build steps for Identity, Menu, Basket, Order, Payment, Promotion, Kitchen, Delivery, and Review services. Without it, each service drifts into slightly different conventions and the codebase becomes inconsistent.

WHEN TO TRIGGER
Auto-load this skill whenever the working directory is `services/{service-name}/` or whenever the user is editing:
- Any `*.java` file under `services/*/src/main/java/`
- Any `application*.yml` file under `services/*/src/main/resources/`
- Any service `pom.xml` (one with `<artifactId>{name}-service</artifactId>`)
Also load on prompts mentioning: "spring boot service", "create a controller", "add an endpoint", "service skeleton", "@RestController", "Spring Boot 4", "service module".

CONTENT TO INCLUDE
The SKILL.md should cover, in this order:

1. **Tech stack lock**: Java 25 LTS + Spring Framework 7 + Spring Boot 4.0.x. Servlet 6.1 (Jakarta EE 11). Embedded Tomcat or Jetty (NOT Undertow — incompatible with Servlet 6.1). Virtual threads enabled (`spring.threads.virtual.enabled=true`).

2. **Package layout** (the canonical structure every service follows):
   ```
   com.{org}.{service}/
   ├── {Service}Application.java       — Spring Boot entry point
   ├── api/                            — controllers, request/response DTOs
   │   ├── {Resource}Controller.java
   │   └── dto/
   ├── service/                        — business logic, transactional boundaries
   ├── domain/                         — entities, repositories, value objects
   ├── client/                         — outbound HTTP/gRPC clients
   ├── listener/                       — Kafka/SQS message consumers
   ├── config/                         — Spring @Configuration classes
   └── security/                       — filters, authorization rules
   ```

3. **Required dependencies** (declare in service POM, no versions — BOM provides):
   - `spring-boot-starter-web`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`
   - `spring-boot-starter-security` for authenticated services
   - `com.{org}.platform:common-exceptions`, `:common-events`, `:common-resilience`, `:common-observability`, `:common-outbox` (where applicable)

4. **Standard imports cheat sheet**:
   - Use `java.time.Instant` for timestamps, never `Date` or `LocalDateTime` for cross-service data
   - Use the platform `Money` type from `common-dto`, never `BigDecimal` directly
   - Use `org.jspecify.annotations.Nullable` and `@NonNull`, not `javax.annotation.Nullable` or Lombok's
   - SLF4J for logging via `LoggerFactory.getLogger(MyClass.class)`, never `System.out.println`

5. **Controller conventions**:
   - Path format: `/v{n}/{resource}` with kebab-case plural (`/v1/orders`, `/v1/payment-methods`)
   - DTOs are Java records, never classes
   - Validation: `@Valid` on request bodies; `@RequestParam(required=false)` for optional params
   - Return types: prefer `ResponseEntity<T>` for endpoints that vary status codes; plain `T` for simple cases
   - Idempotency-Key header REQUIRED on POST/PATCH/DELETE — see `common-conventions.md` Section 13
   - All errors thrown as domain exceptions; converted to `ErrorResponse` by `@RestControllerAdvice` from `common-exceptions`

6. **Service layer conventions**:
   - Methods that mutate state are `@Transactional` (PostgreSQL services) or use explicit DDB conditional writes
   - Inject `Clock` bean for time — never `Instant.now()` directly (testability)
   - Outbox writes happen in the SAME transaction as the state change — see skill `outbox-pattern`

7. **Virtual thread configuration**:
   - Enable globally: `spring.threads.virtual.enabled=true`
   - Listener pools: explicit virtual-thread executor beans for SQS/Kafka listeners
   - Avoid synchronized blocks in hot paths (pinning issue, even on Java 25); use `java.util.concurrent.locks.ReentrantLock`

8. **Standard application.yml structure** (refer to `common-conventions.md` Section 20 for the template).

9. **Required environment variables**: `SERVICE_NAME`, `SERVICE_VERSION`, `ENV`, `AWS_REGION`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_RESOURCE_ATTRIBUTES`.

10. **Health endpoint exposure**: `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus`, `/actuator/info`. Liveness never fails on downstream issues; readiness checks DB pool, Kafka producer, Redis. See `common-conventions.md` Section 6.

11. **Filter chain order** (Spring Security):
    - Tracing filter (from common-observability)
    - Rate limiter (from common-resilience)
    - JWT authentication (from common-security or per-service)
    - Authorization checks via `@PreAuthorize`

ANTI-PATTERNS TO FLAG
The skill should warn the user when it detects any of these:
- `System.out.println` or `printStackTrace()` anywhere
- `Date`, `Calendar`, or `LocalDateTime` for cross-service timestamps
- `BigDecimal` for money fields not wrapped in `Money`
- Hardcoded URLs, ports, or credentials
- `@Autowired` on fields (use constructor injection)
- Lombok `@Data` on entities (mutability + toString issues)
- Missing `@Valid` on request bodies
- Endpoints without `/v{n}/` prefix
- Direct `Instant.now()` without `Clock` injection
- `synchronized` blocks in virtual-thread code paths

REFERENCE DOCS
The skill should explicitly cite these for the user to consult:
- `architecture.md` — overall service architecture
- `common-conventions.md` — Sections 1, 3, 5, 6, 9, 10, 11, 13, 19, 20
- `build-plan.md` — Phase 1 for shared libs, Phase 2 onward for service implementations

EXAMPLES TO INCLUDE
At least one short, complete example for each layer (controller, service, domain, listener) showing the conventions in action.

OUTPUT FORMAT
Standard SKILL.md with frontmatter:
- name: spring-boot-service-conventions
- description: (one-line summary)
- when_to_use: (the trigger conditions in natural language for the dispatcher)
- allowed-tools: read-only file tools, plus edit tools restricted to `services/**` paths
```

---

## 2. outbox-pattern

```text
Create a Claude Code skill named `outbox-pattern`.

PURPOSE
The outbox pattern is the foundation of reliable event publishing in this platform. Six services use it (Identity, Order, Promotion, Delivery, Kitchen, Payment). Without this skill, Claude Code re-derives the implementation slightly differently each time, and the resulting outbox publishers have subtle drifts that show up as data inconsistency under load.

WHEN TO TRIGGER
Auto-load when:
- Working directory is `services/{name}/` AND the user mentions outbox, event publisher, "publish to Kafka", "publish to SNS", "transactional outbox", "@Transactional", "event emission".
- Editing files matching `**/outbox/*.java`, `**/V*outbox*.sql`, or `**/lambdas/outbox-publisher/**`.
- Editing migration files that match `V*__*.sql` and the migration's content references events or outbox.
- The user mentions "Debezium", "CDC", "DynamoDB Streams" in a publishing context.

CONTENT TO INCLUDE

1. **Why the outbox exists** (one paragraph): the dual-write problem and why writing to DB + sending an event in two separate operations causes inconsistency. The outbox writes both atomically inside the DB transaction; a separate publisher delivers to Kafka/SQS later.

2. **PostgreSQL outbox schema** (canonical, copy-pasteable):
   ```sql
   CREATE TABLE outbox (
       id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
       aggregate_type  VARCHAR(50)  NOT NULL,
       aggregate_id    VARCHAR(100) NOT NULL,
       event_type      VARCHAR(100) NOT NULL,
       partition_key   VARCHAR(100) NOT NULL,
       destination     VARCHAR(50)  NOT NULL,    -- 'KAFKA' or 'SQS'
       destination_name VARCHAR(100) NOT NULL,   -- topic name or queue name
       payload         JSONB        NOT NULL,
       headers         JSONB        NOT NULL,
       created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
       processed_at    TIMESTAMPTZ  NULL,
       trace_id        VARCHAR(64)  NULL
   );
   CREATE INDEX idx_outbox_unprocessed
       ON outbox (created_at) WHERE processed_at IS NULL;
   ```

3. **DynamoDB outbox shape** (for Kitchen, Payment):
   - PK: `OUTBOX#{service}`, SK: `{ulid}` for natural ordering
   - Item attributes: same fields as PG version
   - DynamoDB Streams enabled with `NEW_IMAGE` view
   - Stream → Lambda publisher

4. **The OutboxRouter** (decision logic):
   - Each event type maps to a destination (`KAFKA` or `SQS`) and a destination name
   - Domain events (something happened) → Kafka (replay value)
   - Saga commands (do this) → SQS (point-to-point, no replay)
   - Lambda triggers (notification) → Kafka direct subscription
   - Webhook intake → SQS (after API Gateway → SQS direct)
   - Mapping config lives in `application-outbox.yml` per service

5. **The polling publisher** (PostgreSQL services):
   - Runs as a Spring `@Scheduled` task in the same pod (sidecar pattern)
   - Polls every 500ms with `LIMIT 100`
   - Uses `SELECT ... FOR UPDATE SKIP LOCKED` so multiple pod instances are safe
   - For each row: publish, mark `processed_at`, commit
   - On publish failure: leave `processed_at` NULL — next poll retries
   - Idempotency on Kafka side: producer config `enable.idempotence=true`, `acks=all`

6. **The Streams publisher Lambda** (DynamoDB services):
   - Event source: DynamoDB Streams on outbox table
   - Batch size: 100, max batching window 1s
   - Filters on `eventName=INSERT`
   - Publishes to Kafka or SQS based on item's `destination` attribute
   - Failed batches → SQS DLQ
   - Lambda concurrency limited to prevent thundering herds on Kafka

7. **Partition key derivation**:
   - For Kafka: use `aggregate_id` field as the partition key — guarantees per-aggregate ordering
   - For SQS FIFO: same value as `MessageGroupId`
   - For SNS+SQS Standard: not used

8. **Trace propagation**:
   - On insert: capture current OTel `traceId` into the row's `trace_id` column
   - On publish: add `traceId` to Kafka message headers AND to SQS message attributes
   - Consumer-side: extract from headers, set as parent of the consumer span

9. **Consumer-side idempotency**:
   - Outbox guarantees at-least-once, never exactly-once
   - Consumers must dedupe on `eventId` (from event envelope)
   - Store seen IDs in Redis with 7-day TTL or in a DynamoDB idempotency table

10. **Common operational queries**:
    ```sql
    -- Outbox lag (oldest unpublished row age)
    SELECT MAX(NOW() - created_at) FROM outbox WHERE processed_at IS NULL;
    -- Stuck rows (older than 5 min)
    SELECT * FROM outbox WHERE processed_at IS NULL AND created_at < NOW() - INTERVAL '5 minutes';
    -- Throughput (publishes per minute)
    SELECT date_trunc('minute', processed_at), COUNT(*) FROM outbox
      WHERE processed_at > NOW() - INTERVAL '1 hour' GROUP BY 1 ORDER BY 1;
    ```

ANTI-PATTERNS TO FLAG
- A `@Transactional` method that calls `kafkaTemplate.send()` or `snsClient.publish()` directly — that's the dual-write bug. Insert into outbox instead.
- Polling without `SKIP LOCKED` — multiple pods will fight for the same row.
- Updating outbox rows instead of marking processed — outbox is append-only.
- Publishing without setting partition key — breaks per-aggregate ordering.
- Missing `traceId` propagation.
- Consumer that doesn't dedupe on `eventId`.
- Storing PII or secrets in outbox `payload` — outbox rows live longer than message retention.

REFERENCE DOCS
- `architecture.md` Section 8 (Saga & Outbox Pattern)
- `common-conventions.md` Section 8 (Event Envelope), Section 14 (Resilience naming), Section 17 (Schema versioning)
- `build-plan.md` Step 1.4 (common-outbox), Step 2.2 (Identity outbox), Step 8.4-8.6 (Order saga)

EXAMPLES TO INCLUDE
- A complete `@Transactional` method that writes business state + outbox row in one transaction.
- A complete polling publisher with the SKIP LOCKED query.
- A Lambda handler skeleton for the DDB Streams variant.
- An OutboxRouter config example showing both Kafka and SQS destinations.

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit access — this skill is hands-on with code generation.
```

---

## 3. terraform-module-conventions

```text
Create a Claude Code skill named `terraform-module-conventions`.

PURPOSE
This skill defines how Terraform code is organized in `platform-infra/`. Phase 0 has 11 IaC steps; Phase 13 has 6 more. Every Terraform PR routes through this skill. Inconsistency in module structure makes the codebase progressively harder to reason about; codifying the patterns up front prevents drift.

WHEN TO TRIGGER
- Working directory under `platform-infra/`
- Editing `*.tf`, `*.tfvars`, `*.tfvars.json`, or `versions.tf`
- User mentions Terraform, "infrastructure", "provision", "VPC", "EKS", "RDS", "MSK", "DynamoDB", "Aurora", "module", "remote state"

CONTENT TO INCLUDE

1. **Repository layout**:
   ```
   platform-infra/
   ├── modules/                 — reusable modules
   │   ├── vpc/
   │   ├── eks/
   │   ├── rds-aurora/
   │   ├── dynamodb-table/
   │   ├── elasticache-redis/
   │   ├── msk/
   │   ├── sns-sqs-pair/
   │   ├── ecr-repo/
   │   ├── api-gateway/
   │   ├── service-pipeline/
   │   └── waf/
   ├── envs/
   │   ├── shared/              — cross-env: ECR, IAM, CodeArtifact
   │   ├── staging/
   │   ├── production/
   │   └── load-test/
   ├── buildspec-templates/     — reusable buildspecs
   └── scripts/                 — bootstrap and helpers
   ```

2. **Standard module file structure**:
   ```
   modules/{name}/
   ├── main.tf          — primary resources
   ├── variables.tf     — input variables, all with descriptions and types
   ├── outputs.tf       — exported values for other modules
   ├── versions.tf      — required Terraform + provider versions
   ├── README.md        — module docs (terraform-docs auto-generates parts)
   └── examples/        — minimal working example invocations
   ```

3. **Tagging strategy** (every taggable resource):
   ```hcl
   provider "aws" {
     default_tags {
       tags = {
         Project     = "food-ordering-platform"
         Environment = var.environment
         Service     = var.service_name        # null at env level, set per-module
         Owner       = var.owner_team
         CostCenter  = var.cost_center
         ManagedBy   = "terraform"
         Repository  = "food-ordering-platform"
       }
     }
   }
   ```

4. **Naming convention** (every resource):
   - Pattern: `{org}-{env}-{service}-{resource}`
   - Examples: `acme-prod-payment-ledger`, `acme-staging-order-service-irsa`
   - Implemented via locals: `local.name_prefix = "${var.org}-${var.environment}"`

5. **Variable conventions**:
   - Every variable has `description` and `type`
   - Sensitive variables marked `sensitive = true`
   - Validation blocks for inputs that have constrained values
   - Default values only for variables that have a sensible platform-wide default; otherwise required

6. **Output conventions**:
   - Outputs are stable contracts — other modules consume them
   - Every output has a `description`
   - Sensitive outputs marked `sensitive = true`
   - Don't export internal IDs unless they're consumed elsewhere

7. **State backend**:
   ```hcl
   terraform {
     backend "s3" {
       bucket         = "{org}-tfstate-{account-id}"
       key            = "envs/{env}/{module}.tfstate"
       region         = "{primary-region}"
       dynamodb_table = "{org}-tfstate-locks"
       encrypt        = true
     }
   }
   ```

8. **Provider versions and pinning**:
   - Pin Terraform to `~> 1.7`
   - Pin AWS provider to `~> 5.x`
   - Pin Kubernetes provider to `~> 2.x` (where used)
   - All in `versions.tf`

9. **Loops and dynamic blocks**:
   - Use `for_each` over `count` (stable identity under add/remove)
   - Avoid dynamic blocks unless they reduce >5 lines of duplication

10. **Secrets handling**:
    - NEVER put secrets in `.tfvars` or commit them
    - All secrets stored in Secrets Manager, fetched via `data.aws_secretsmanager_secret_version`
    - DB master passwords generated via `random_password` and stored to Secrets Manager in the same apply

11. **Required CI checks** (the pre-commit hook covers most):
    - `terraform fmt -check -recursive`
    - `terraform validate` per env
    - `tflint` with AWS plugin
    - `tfsec` for static security scanning
    - `checkov` for compliance checks

12. **Module testing**:
    - Each module has at least one example in `examples/` that `terraform validate` passes against
    - Critical modules (EKS, RDS, MSK) have `terratest` integration tests

ANTI-PATTERNS TO FLAG
- Hardcoded account IDs, region strings, or AZ names — use variables and `data.aws_caller_identity` / `data.aws_region`
- Hardcoded CIDRs in modules — pass as variables
- Resources without tags
- `count` used for loops over lists (use `for_each`)
- Resources with `aws:*:*` IAM
- `aws_security_group` with `0.0.0.0/0` ingress on non-public-facing services
- Resources without `lifecycle` blocks where appropriate (e.g., RDS without `prevent_destroy`)
- Using inline policies on IAM roles instead of attached managed policies
- Modules that read from another env's state (cross-env reads belong in `envs/shared/`)
- Default-deny security groups that aren't actually default-deny

REFERENCE DOCS
- `architecture.md` Section 3 (AWS Service Mapping) for what to provision per service
- `build-plan.md` Phase 0 (all 11 steps) and Phase 13
- `common-conventions.md` Section 19 (Naming Conventions) — Terraform must produce names matching this table

EXAMPLES TO INCLUDE
- A complete minimal module showing files, variables, outputs, and naming
- A complete env-level invocation calling 3 modules
- A pattern for cross-env-shared resources (ECR, IAM)

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit access for `platform-infra/**`.
```

---

## 4. aws-sdk-v2-conventions

```text
Create a Claude Code skill named `aws-sdk-v2-conventions`.

PURPOSE
Every service touches AWS via the SDK. Without conventions, you get inconsistent client config, security misses (hardcoded keys, default retry policies that misbehave under load), and unnecessary client instantiations.

WHEN TO TRIGGER
- Editing Java code that imports `software.amazon.awssdk.*`
- User mentions AWS SDK, S3 client, DynamoDB client, SNS, SQS, Kafka client config (when AWS auth is involved), Secrets Manager, KMS, Parameter Store
- Editing `@Configuration` classes that create AWS clients

CONTENT TO INCLUDE

1. **Tech stack**: AWS SDK for Java v2 (NOT v1). BOM imported via `platform-bom`.

2. **BOM import pattern** (already in platform-bom; never override per service):
   ```xml
   <!-- platform-bom/pom.xml -->
   <dependencyManagement>
     <dependencies>
       <dependency>
         <groupId>software.amazon.awssdk</groupId>
         <artifactId>bom</artifactId>
         <version>${aws-sdk-v2.version}</version>
         <type>pom</type>
         <scope>import</scope>
       </dependency>
     </dependencies>
   </dependencyManagement>
   ```

3. **Credentials provider**: ALWAYS `DefaultCredentialsProvider.create()` — picks up IRSA on EKS, instance profile on EC2, env vars locally. NEVER hardcode keys or use `StaticCredentialsProvider` outside of explicit test code.

4. **Region resolution**: from `AWS_REGION` env var (set by Kubernetes downward API or CodeBuild). NEVER hardcode region strings.

5. **Client as Spring bean** (canonical pattern):
   ```java
   @Configuration
   public class AwsClientConfig {
     @Bean
     public DynamoDbClient dynamoDbClient() {
       return DynamoDbClient.builder()
           .region(Region.of(System.getenv("AWS_REGION")))
           .credentialsProvider(DefaultCredentialsProvider.create())
           .overrideConfiguration(c -> c
               .apiCallTimeout(Duration.ofSeconds(5))
               .apiCallAttemptTimeout(Duration.ofSeconds(2))
               .retryStrategy(SdkDefaultRetryStrategy.defaultRetryStrategy()))
           .build();
     }
   }
   ```

6. **Standard timeouts** (per AWS service):
   | Service | API call timeout | Per-attempt timeout |
   |---|---|---|
   | DynamoDB | 5s | 1s |
   | S3 (small objects) | 10s | 3s |
   | S3 (multipart upload) | 5m | 30s per part |
   | SNS / SQS | 5s | 2s |
   | Kafka producer | 10s | (handled differently — see kafka-msk-iam-auth) |
   | Secrets Manager | 5s | 2s |
   | Lambda invoke | per-Lambda's max execution + buffer | (varies) |

7. **Retry strategy**: use `SdkDefaultRetryStrategy.defaultRetryStrategy()` which retries on throttling and transient errors with exponential backoff. Override only for specific operations (e.g., DynamoDB conditional writes that should NOT retry on `ConditionalCheckFailedException`).

8. **Paginator usage** (always — never manual continuation tokens):
   ```java
   client.listObjectsV2Paginator(req).contents()
       .stream()
       .map(S3Object::key)
       .forEach(this::process);
   ```

9. **Async vs sync clients**:
   - Default: synchronous clients with virtual threads (Java 25 makes blocking I/O essentially free)
   - Async clients only when streaming or true concurrency is needed (DynamoDB enhanced async, S3 transfer manager)

10. **VPC endpoints**: when `USE_VPC_ENDPOINTS=true` env var is set, override the endpoint:
    ```java
    .endpointOverride(URI.create(System.getenv("DYNAMODB_VPC_ENDPOINT")))
    ```

11. **KMS encryption**: every S3 bucket and DynamoDB table provisioned with KMS CMK; SDK calls don't need to specify (server-side encryption is the bucket default).

12. **DynamoDB Enhanced Client**: prefer over the low-level client for typed access:
    ```java
    @Bean
    public DynamoDbEnhancedClient enhancedClient(DynamoDbClient client) {
      return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    }
    ```

13. **Closing clients**: SDK v2 clients are thread-safe singletons; never close inside business methods. Spring lifecycle handles shutdown via the `@Bean` destroy method (`AutoCloseable` interface is honored).

14. **Pagination size**: default page sizes are conservative; for high-throughput operations, increase `Limit` (DynamoDB) or `MaxKeys` (S3). Document explicitly.

ANTI-PATTERNS TO FLAG
- `software.amazon.awssdk.services.*.AmazonXxxClient` (that's v1) — flag and suggest v2
- `new AWSStaticCredentialsProvider(new BasicAWSCredentials(...))` — hardcoded keys
- `Region.US_EAST_1` or any literal region — use env var
- Constructing AWS clients inside controller or service methods (must be Spring beans)
- Manual continuation token loops (use paginators)
- Missing `apiCallTimeout` (defaults to ages)
- `withMaxConnections` set higher than 50 without justification
- Synchronous SDK calls inside reactive code paths (and vice versa)
- Try-with-resources around long-lived clients
- DDB writes without `ReturnConsumedCapacity` instrumentation in load-sensitive code

REFERENCE DOCS
- `architecture.md` Section 3 (AWS Service Mapping)
- `common-conventions.md` Section 20 (Spring Boot config)
- `build-plan.md` Step 1.4 (common-observability includes some AWS client metrics)

USE CONTEXT7
This skill should explicitly suggest "use context7 to look up AWS SDK v2 docs" whenever the user is implementing a new SDK call. AWS SDK v2 has frequent breaking changes; live docs prevent outdated patterns.

EXAMPLES TO INCLUDE
- A complete `AwsClientConfig` Spring bean class with DynamoDB, SNS, SQS, S3 clients
- Paginator usage for DynamoDB Query and S3 ListObjects
- DynamoDB conditional write with proper exception handling

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit access.
```

---

## 5. monorepo-maven-conventions

```text
Create a Claude Code skill named `monorepo-maven-conventions`.

PURPOSE
This monorepo uses Maven multi-module with a BOM ("Bill of Materials") for unified versioning. Every service POM is < 50 lines because the BOM owns versions. Without this skill, services drift into declaring their own versions, which causes Spring Boot 4.0.3 in one service and 4.0.6 in another, and we lose the consistency that motivated the monorepo in the first place.

WHEN TO TRIGGER
- Editing any `pom.xml` in the monorepo
- Editing `platform-bom/pom.xml`
- User mentions "BOM", "platform-bom", "Maven dependency", "version conflict", "reactor build", "mvn -pl"
- User mentions adding a new service or library module

CONTENT TO INCLUDE

1. **Repository layout** (Maven perspective):
   ```
   food-ordering-platform/                    — the monorepo root
   ├── pom.xml                                — root reactor POM (lists all modules)
   ├── platform-bom/
   │   └── pom.xml                            — BOM with dependencyManagement only
   ├── platform-shared-libs/
   │   ├── pom.xml                            — parent for shared modules
   │   ├── common-dto/
   │   ├── common-exceptions/
   │   ├── common-events/
   │   ├── common-resilience/
   │   ├── common-observability/
   │   ├── common-outbox/
   │   └── common-security/
   ├── services/
   │   ├── identity-service/
   │   ├── menu-service/
   │   ├── basket-service/
   │   ├── order-service/
   │   ├── payment-service/
   │   ├── promotion-service/
   │   ├── kitchen-service/
   │   ├── delivery-service/
   │   ├── review-service/
   │   └── notification-service/   (Lambda; uses SAM but still Maven-built)
   └── e2e-tests/
   ```

2. **Root pom.xml** (the reactor):
   ```xml
   <project>
     <groupId>com.{org}.platform</groupId>
     <artifactId>food-ordering-platform-parent</artifactId>
     <version>1.0.0-SNAPSHOT</version>
     <packaging>pom</packaging>

     <modules>
       <module>platform-bom</module>
       <module>platform-shared-libs/common-dto</module>
       <module>platform-shared-libs/common-exceptions</module>
       <!-- ... all shared lib modules ... -->
       <module>services/identity-service</module>
       <module>services/menu-service</module>
       <!-- ... all service modules ... -->
       <module>e2e-tests</module>
     </modules>
   </project>
   ```

3. **The BOM** (`platform-bom/pom.xml`):
   ```xml
   <project>
     <groupId>com.{org}.platform</groupId>
     <artifactId>platform-bom</artifactId>
     <version>1.0.0</version>           <!-- bumped on any breaking change -->
     <packaging>pom</packaging>

     <properties>
       <java.version>25</java.version>
       <maven.compiler.source>25</maven.compiler.source>
       <maven.compiler.target>25</maven.compiler.target>
       <spring-boot.version>4.0.6</spring-boot.version>
       <aws-sdk.version>2.30.0</aws-sdk.version>
       <resilience4j.version>2.4.0</resilience4j.version>
       <!-- ... all version properties ... -->
     </properties>

     <dependencyManagement>
       <dependencies>
         <!-- Spring Boot BOM imported -->
         <dependency>
           <groupId>org.springframework.boot</groupId>
           <artifactId>spring-boot-dependencies</artifactId>
           <version>${spring-boot.version}</version>
           <type>pom</type>
           <scope>import</scope>
         </dependency>
         <!-- AWS SDK BOM imported -->
         <dependency>
           <groupId>software.amazon.awssdk</groupId>
           <artifactId>bom</artifactId>
           <version>${aws-sdk.version}</version>
           <type>pom</type>
           <scope>import</scope>
         </dependency>
         <!-- Our own shared libs -->
         <dependency>
           <groupId>com.{org}.platform</groupId>
           <artifactId>common-events</artifactId>
           <version>${platform.version}</version>
         </dependency>
         <!-- ... etc ... -->
       </dependencies>
     </dependencyManagement>
   </project>
   ```

4. **Service POM** (canonical, minimal):
   ```xml
   <project>
     <parent>
       <groupId>com.{org}.platform</groupId>
       <artifactId>platform-bom</artifactId>
       <version>1.0.0</version>
       <relativePath>../../platform-bom/pom.xml</relativePath>
     </parent>

     <artifactId>identity-service</artifactId>

     <dependencies>
       <!-- NO VERSIONS — BOM provides them -->
       <dependency>
         <groupId>org.springframework.boot</groupId>
         <artifactId>spring-boot-starter-web</artifactId>
       </dependency>
       <dependency>
         <groupId>com.{org}.platform</groupId>
         <artifactId>common-outbox</artifactId>
       </dependency>
     </dependencies>
   </project>
   ```

5. **Reactor commands**:
   - Build everything: `mvn -B verify`
   - Build one service + its dependencies: `mvn -B -pl services/order-service -am verify`
   - Build everything that depends on a shared lib: `mvn -B -pl platform-shared-libs/common-events -amd verify`
   - Skip integration tests: `mvn -B verify -DskipITs`
   - Run only integration tests: `mvn -B verify -Dskip.unit.tests`
   - Build with native profile: `mvn -B verify -Pnative`
   - Selective from changed paths (CI uses this — see hook-specs):
     ```
     CHANGED=$(git diff --name-only main... | grep -oP '(services|platform-shared-libs)/[^/]+' | sort -u)
     mvn -B verify -pl "$CHANGED" -am
     ```

6. **Profiles**:
   - `local` (default): no special activation
   - `staging`, `production`: activated by `-Dspring.profiles.active=...` at runtime, not a Maven profile
   - `coverage`: activates JaCoCo with 80% threshold
   - `integration-test`: activates Failsafe for `*IT.java` tests
   - `native`: GraalVM native image build

7. **Versioning**:
   - Snapshot during development: `1.0.0-SNAPSHOT`
   - Release tags via Maven release plugin: `1.0.0`, `1.0.1`, ...
   - BOM version bumped whenever any shared lib bumps its version
   - Service versions are independent (Identity can be `1.4.2` while Menu is `2.1.0`)

8. **Required Maven plugins** (declared once in BOM, not per service):
   - `spring-boot-maven-plugin`
   - `flyway-maven-plugin`
   - `jacoco-maven-plugin`
   - `maven-failsafe-plugin`
   - `protobuf-maven-plugin`
   - `git-commit-id-maven-plugin`
   - `spotless-maven-plugin` for formatting

9. **Publishing to CodeArtifact**:
   - `mvn deploy` publishes shared libs to CodeArtifact's `internal` repo
   - Services are NEVER published (only built into Docker images)
   - `~/.m2/settings.xml` template uses `aws codeartifact get-authorization-token` for auth

10. **Dependency hygiene**:
    - Run `mvn dependency:tree` and `mvn versions:display-dependency-updates` periodically
    - Forbidden: any dependency from Maven Central directly (everything goes through CodeArtifact's upstream proxy)
    - Banned dependencies: anything with known critical CVEs, anything LGPL/GPL, anything from `unmaintained` projects (use `versions:dependency-updates-report`)

11. **Common mistakes**:
    - Forgetting `<relativePath>` in service POM's `<parent>` causes Maven to try Maven Central
    - Specifying `<version>` on a dep that's in the BOM — BOM is silently ignored, version drifts
    - Adding a transitive dep as a direct dep without thinking — increases coupling

ANTI-PATTERNS TO FLAG
- Service POM with `<version>` on any dependency (BOM violation)
- Direct dependency from Maven Central without going through CodeArtifact
- `<scope>system</scope>` (forbidden)
- Snapshot dependency in a release build
- Service depending on another service's classes (services depend only on shared libs)
- Cross-shared-lib circular deps
- Plugin declarations duplicated in service POMs (should inherit from BOM)
- Use of `${project.version}` to reference shared lib versions (use BOM-managed)

REFERENCE DOCS
- `architecture.md` for service decomposition
- `common-conventions.md` Section 15 (Build Conventions), Section 18 (Library Versioning)
- `build-plan.md` Phase 1 for BOM construction

EXAMPLES TO INCLUDE
- A complete BOM excerpt
- A complete minimal service POM
- A complete shared-lib POM
- Sample `mvn` commands for the most common operations

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit access for `*pom.xml` files.
```

---

# Tier 2 — Phase-Specific Skills

## 6. kafka-msk-iam-auth

```text
Create a Claude Code skill named `kafka-msk-iam-auth`.

PURPOSE
Six services produce or consume from Amazon MSK with IAM auth. The configuration has subtle gotchas (MSK IAM library JAR, SASL mechanism, callback handler, IRSA permissions per topic). Codifying it once prevents 6 separate "why does this not connect" debugging sessions.

WHEN TO TRIGGER
- Editing Java code with `org.apache.kafka.clients.*` or `org.springframework.kafka.*` imports
- Editing `application*.yml` files with `spring.kafka` keys
- User mentions Kafka, MSK, KafkaListener, KafkaTemplate, "produce to topic", "consume from topic"
- Editing Avro schema files (`*.avsc`)
- Editing `proto` files for events
- Editing IAM policies that include `kafka-cluster:*` actions

CONTENT TO INCLUDE

1. **Tech stack**:
   - Spring Kafka (latest in BOM)
   - `software.amazon.msk:aws-msk-iam-auth` library
   - Kafka client matched to MSK Kafka version (3.6+ at the time of writing)
   - AWS Glue Schema Registry client for Avro

2. **Required dependencies in service POM**:
   ```xml
   <dependency>
     <groupId>org.springframework.kafka</groupId>
     <artifactId>spring-kafka</artifactId>
   </dependency>
   <dependency>
     <groupId>software.amazon.msk</groupId>
     <artifactId>aws-msk-iam-auth</artifactId>
   </dependency>
   <dependency>
     <groupId>software.amazon.glue</groupId>
     <artifactId>schema-registry-serde</artifactId>
   </dependency>
   ```

3. **Producer config** (canonical):
   ```yaml
   spring:
     kafka:
       bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
       properties:
         security.protocol: SASL_SSL
         sasl.mechanism: AWS_MSK_IAM
         sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
         sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler
       producer:
         acks: all
         enable-idempotence: true
         retries: 2147483647            # max int — relies on delivery.timeout.ms instead
         compression-type: lz4
         max-in-flight-requests-per-connection: 5
         linger-ms: 10
         batch-size: 32768
         properties:
           delivery.timeout.ms: 120000
           value.serializer: com.amazonaws.services.schemaregistry.serializers.avro.GlueSchemaRegistryAvroSerializer
   ```

4. **Consumer config** (canonical):
   ```yaml
   spring:
     kafka:
       consumer:
         group-id: ${SERVICE_NAME}-${KAFKA_TOPIC_NAME}    # e.g. order-service-payment-events
         enable-auto-commit: false                         # manual commits only
         auto-offset-reset: earliest                       # for new consumer groups
         max-poll-records: 50
         properties:
           value.deserializer: com.amazonaws.services.schemaregistry.deserializers.avro.GlueSchemaRegistryKafkaDeserializer
       listener:
         ack-mode: MANUAL_IMMEDIATE
         concurrency: 3                                    # parallel consumers per pod
   ```

5. **Listener pattern** (canonical):
   ```java
   @KafkaListener(topics = "payment-events", groupId = "${spring.kafka.consumer.group-id}")
   public void onPaymentEvent(
       ConsumerRecord<String, GenericRecord> record,
       Acknowledgment ack
   ) {
       String eventType = new String(record.headers().lastHeader("eventType").value());
       if (!"PAYMENT_SUCCESS".equals(eventType)) {
           ack.acknowledge();   // not for us, skip
           return;
       }
       try {
           // process — must be idempotent on event ID
           handlePaymentSuccess(record.value());
           ack.acknowledge();
       } catch (Exception e) {
           // do NOT ack — Kafka will redeliver
           log.error("Failed to process event", e);
           throw e;   // triggers Spring's error handler → DLT after retries
       }
   }
   ```

6. **Header conventions**:
   - `eventType` — string, set by producer, read by consumer for filtering
   - `eventId` — string, used for consumer-side dedup
   - `traceId` — string, for OTel propagation
   - `schemaVersion` — integer
   - `contentType` — `application/avro` or `application/json`

7. **Partition key strategy**:
   - For domain events: use the aggregate ID (`orderId`, `userId`, `restaurantId`) as the message key
   - Per-key ordering is preserved within a partition
   - Don't use null keys (round-robin) for events that need ordering

8. **Topic configuration** (declared in Terraform, mentioned here for awareness):
   - 12 partitions for high-volume topics (`order-events`, `payment-events`)
   - 6 partitions for lower-volume topics (`identity-events`, `kitchen-events`)
   - Retention: 7 days for transactional events, 30 days for audit-relevant events
   - Compression: lz4 (set on broker; producer matches)
   - `min.insync.replicas`: 2 (along with `acks=all` for durability)

9. **Glue Schema Registry**:
   - Schema name format: `{topic-name}-value` (e.g., `order-events-value`)
   - Compatibility mode: `BACKWARD` (consumers can read older messages with newer schema)
   - Schemas live in `platform-shared-libs/common-events/src/main/avro/{event-name}.avsc`
   - Generated Java classes go to `target/generated-sources/` (use `avro-maven-plugin`)
   - Registry auth: IRSA on the producer/consumer pod

10. **IRSA permissions** (per producer):
    ```hcl
    statement {
      actions = [
        "kafka-cluster:Connect",
        "kafka-cluster:WriteData",
        "kafka-cluster:DescribeTopic"
      ]
      resources = [
        "arn:aws:kafka:*:*:cluster/{cluster-name}/*",
        "arn:aws:kafka:*:*:topic/{cluster-name}/*/{topic-name}",
        "arn:aws:kafka:*:*:transactional-id/{cluster-name}/*/${SERVICE_NAME}-*"
      ]
    }
    ```

11. **DLT (dead-letter topic) pattern**:
    - On retry exhaustion, Spring Kafka publishes to `{original-topic}.DLT`
    - DLT consumed by an operations Lambda or alerting consumer
    - Original headers preserved + `kafka_dlt-original-topic`, `kafka_dlt-exception-fqcn`, etc.

12. **Local development with Testcontainers**:
    ```java
    @Testcontainers
    class OrderSagaIT {
      @Container
      static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
      @DynamicPropertySource
      static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.properties.security.protocol", () -> "PLAINTEXT");  // local only
        registry.add("spring.kafka.properties.sasl.mechanism", () -> "");
      }
    }
    ```

ANTI-PATTERNS TO FLAG
- `enable-auto-commit: true` — must be manual for at-least-once
- Missing `acks=all` on producer — risk of message loss
- Missing `enable.idempotence: true` — risk of duplicate publishes on retry
- `linger.ms` set to 0 (no batching) on a high-throughput producer
- Missing `max.in.flight.requests.per.connection` (default of 5 with idempotence is OK; explicitly set elsewhere)
- Consumer methods that catch all exceptions and ack anyway (silent message loss)
- Consumer using `@KafkaListener` without specifying `groupId`
- Multiple `@KafkaListener` methods on the same topic in same service without different group IDs (consumer group conflict)
- Hardcoded bootstrap server URLs
- Missing Schema Registry — using raw Java serialization or untyped Maps for payloads
- Using `String` as message value when payload should be Avro-typed

REFERENCE DOCS
- `architecture.md` Section 1 (architecture diagram), Section 3 (AWS mapping)
- `common-conventions.md` Section 8 (Event Envelope), Section 17 (Schema versioning)
- `build-plan.md` Step 0.7 (MSK provisioning)

USE CONTEXT7
Suggest "use context7 to look up Spring Kafka 4.x docs" or "use context7 for Glue Schema Registry latest docs" — these libraries change frequently.

EXAMPLES TO INCLUDE
- Complete `KafkaConfig.java` Spring class
- Complete producer + listener pattern
- Avro schema example with `EventEnvelope` wrapping a payload
- Testcontainers setup for integration testing

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit.
```

---

## 7. dynamodb-access-patterns

```text
Create a Claude Code skill named `dynamodb-access-patterns`.

PURPOSE
Four services use DynamoDB heavily (Menu, Kitchen, Payment, Review). DynamoDB rewards good single-table design and punishes bad partition keys (hot partitions, expensive scans, GSI explosion). This skill encodes the design patterns that work for our access patterns.

WHEN TO TRIGGER
- Editing Java code that imports `software.amazon.awssdk.services.dynamodb.*` or uses Enhanced Client
- Editing Terraform `aws_dynamodb_table` resources
- User mentions DynamoDB, partition key, sort key, GSI, "single-table design", "atomic counter", DDB Streams
- Editing DDB schema definitions in `services/{name}/docs/data-model.md`

CONTENT TO INCLUDE

1. **Single-table design philosophy**:
   - One DDB table per service, NOT one per entity
   - PK and SK designed for access patterns FIRST, ergonomics second
   - Different entity types share the same table via prefixed keys

2. **PK/SK conventions** (per service):
   - **Menu service** (`menus` table):
     - PK: `RESTAURANT#{restaurantId}`
     - SK: `MENU` for the document, `ITEM#{itemId}` for individual items
   - **Kitchen service** (`tickets` table):
     - PK: `RESTAURANT#{restaurantId}`
     - SK: `TICKET#{ticketId}` for tickets, `CAPACITY` for capacity counter
   - **Payment service** (`payment-ledger` table):
     - PK: `PAYMENT#{paymentIntentId}`
     - SK: `ENTRY#{seqId}` for ledger entries (monotonic ULIDs)
   - **Review service** (`reviews` table):
     - PK: `REVIEW#{type}#{entityId}` where type = `RESTAURANT|DRIVER|MEAL`
     - SK: `{orderId}#{userId}` ensuring one review per (user, order, entity)

3. **GSI patterns**:
   - Only add a GSI for an access pattern that can't be served by PK/SK
   - Each GSI costs storage + write amplification; budget conservatively
   - Use sparse GSIs (only items with the attribute populated) where possible
   - Common GSI patterns:
     - `payment-ledger`: GSI on `idempotency_key` for duplicate-charge detection
     - `tickets`: GSI on `state` for "list all PREPARING tickets per restaurant"
     - `reviews`: GSI on `(userId, submittedAt)` for "my reviews"

4. **Partition key uniformity**:
   - Avoid hot partitions: never use sequential IDs (timestamps, auto-increment) as PK
   - Use ULIDs or UUIDs prefixed with entity type
   - For high-write tenants (large restaurants), consider write sharding: `RESTAURANT#{id}#{shard}` where shard is `0..N`

5. **Atomic counters** (Kitchen capacity):
   ```java
   client.updateItem(req -> req
       .tableName("tickets")
       .key(Map.of(
           "PK", AttributeValue.fromS("RESTAURANT#" + restaurantId),
           "SK", AttributeValue.fromS("CAPACITY")))
       .updateExpression("ADD active_count :inc")
       .expressionAttributeValues(Map.of(":inc", AttributeValue.fromN("1")))
       .returnValues(ReturnValue.UPDATED_NEW))
       .attributes()
       .get("active_count")
       .n();
   ```

6. **Conditional writes for idempotency**:
   ```java
   try {
     client.putItem(req -> req
         .tableName("payment-ledger")
         .item(itemAttributes)
         .conditionExpression("attribute_not_exists(PK) AND attribute_not_exists(SK)"));
   } catch (ConditionalCheckFailedException e) {
     // Already exists — return cached result
   }
   ```

7. **Optimistic locking**:
   ```java
   client.updateItem(req -> req
       .conditionExpression("version = :expectedVersion")
       .updateExpression("SET version = :newVersion, ...")
       .expressionAttributeValues(Map.of(
           ":expectedVersion", AttributeValue.fromN(String.valueOf(currentVersion)),
           ":newVersion", AttributeValue.fromN(String.valueOf(currentVersion + 1)))));
   ```

8. **Streams pattern** (for outbox, aggregates):
   - Enable Streams with `NEW_IMAGE` view (you usually only need new state)
   - Use `KEYS_ONLY` for delete-driven workflows
   - Lambda event source: batch size 100, max 1s window, parallelization 4
   - Filter at Lambda level (not in code) to reduce invocations

9. **PITR and backups**:
   - Point-in-time recovery enabled on financially-sensitive tables (`payment-ledger`)
   - On-demand backups before risky migrations
   - Cross-region backup for DR (Phase 15)

10. **Capacity mode**:
    - On-demand for unpredictable workloads (Menu reads, Kitchen tickets at lunch rush)
    - Provisioned + auto-scaling for steady workloads (Review aggregates)
    - Calculate the breakeven: on-demand wins below ~14% sustained utilization

11. **Item size limits**:
    - 400KB max per item
    - For Menu items larger than 400KB (rare, but possible with rich descriptions and many modifiers), split into header item + child items
    - Never store binary data > 100KB in DDB; use S3 with the S3 key in DDB

12. **Anti-pattern: Scans on large tables**:
    - Forbidden in production paths
    - Allowed for analytics jobs run in off-hours via DynamoDB Export to S3
    - For "list all items" features, design a GSI

13. **Pagination**:
    - DDB returns at most 1MB per query/scan; always paginate
    - Use the SDK's paginator; never `lastEvaluatedKey` loops manually
    - Expose API-level pagination via the cursor pattern (Section 4 of common-conventions)

14. **Test setup** (Testcontainers):
    ```java
    @Container
    static GenericContainer<?> ddbLocal = new GenericContainer<>("amazon/dynamodb-local:latest")
        .withExposedPorts(8000)
        .withCommand("-jar DynamoDBLocal.jar -inMemory");
    ```

ANTI-PATTERNS TO FLAG
- Scan operations in service code paths (not analytics)
- PK based on a timestamp or sequential ID
- GSI added "just in case"
- Multiple `getItem` calls in a loop (should be `batchGet`)
- Multiple `putItem` calls in a loop (should be `batchWrite` or `transactWrite`)
- Missing `ReturnConsumedCapacity` instrumentation in load-sensitive paths
- Storing >100KB binary in DDB
- Using DDB for reporting queries (use S3 export + Athena)
- Missing TTL on idempotency-key tables
- `LastEvaluatedKey` ignored on a single query (silent partial results)

REFERENCE DOCS
- `architecture.md` Section 3 (AWS mapping), Section 5 (Data Design)
- `common-conventions.md` Section 21 (Database Conventions)
- `build-plan.md` Steps 0.5, 5.1, 7.1, 9.1, 11.1

USE CONTEXT7
Suggest "use context7 for AWS SDK v2 DynamoDB Enhanced Client docs" — the API has evolved significantly.

EXAMPLES TO INCLUDE
- A complete `MenuRepository` using Enhanced Client
- An atomic counter increment with bounded check
- A conditional write with proper exception handling
- A Streams-triggered Lambda skeleton
- A migration script (DynamoDB doesn't have schema migrations per se, but item-shape migrations are needed when fields change)

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit.
```

---

## 8. postgresql-flyway-migrations

```text
Create a Claude Code skill named `postgresql-flyway-migrations`.

PURPOSE
Four services use PostgreSQL with Flyway (Identity, Order, Promotion, Delivery). Migration mistakes are operationally painful: a non-backwards-compatible migration deployed alongside code that doesn't expect the new schema can take down all pods. This skill prevents those classes of error.

WHEN TO TRIGGER
- Editing files matching `services/*/src/main/resources/db/migration/V*__*.sql`
- Editing JPA entities or Spring Data repositories
- User mentions Flyway, migration, "V3__add_column", schema change, ALTER TABLE
- User mentions HikariCP, connection pool, JDBC URL

CONTENT TO INCLUDE

1. **Tech stack**:
   - PostgreSQL 16 (Aurora Serverless v2)
   - Flyway latest in BOM
   - HikariCP as connection pool (default in Spring Boot)
   - Spring Data JDBC or JPA — JDBC preferred for new services (less magic)

2. **Migration file naming**:
   - Format: `V{n}__{snake_case_description}.sql` — exactly two underscores
   - `n` is a monotonically increasing integer (1, 2, 3, ...) — no gaps, no reuse
   - Description is snake_case, descriptive but brief
   - Examples: `V1__create_users.sql`, `V3__add_outbox_table.sql`, `V12__add_user_locale_column.sql`

3. **Flyway behaviors locked in**:
   - `flyway.baseline-on-migrate=false` (force explicit baseline)
   - `flyway.validate-on-migrate=true`
   - `flyway.out-of-order=false` (strict ordering)
   - `flyway.locations=classpath:db/migration`

4. **Migrations run via init container**, NEVER at app startup:
   ```yaml
   initContainers:
   - name: flyway
     image: flyway/flyway:10
     command: ["flyway", "-url=$(DB_URL)", "-user=$(DB_USER)", "-password=$(DB_PASSWORD)", "migrate"]
     envFrom:
     - secretRef:
         name: order-service-db
   ```
   This prevents N pods racing on the same migration.

5. **Backwards-compatible patterns** (the rule that prevents outages):

   - **Adding a column**:
     - Migration 1 (deploy A): `ALTER TABLE orders ADD COLUMN failure_reason TEXT NULL;`
     - Code change: write to new column where applicable
     - Migration 2 (deploy B, weeks later): `ALTER TABLE orders ALTER COLUMN failure_reason SET NOT NULL;` (if needed)

   - **Removing a column**:
     - Code change first: stop reading or writing the column
     - Migration (later deploy): `ALTER TABLE orders DROP COLUMN deprecated_column;`
     - Wait at least one full deploy cycle in between

   - **Renaming a column**:
     - Migration 1: `ALTER TABLE orders ADD COLUMN new_name TEXT;`
     - Code change: dual-write to both, read from new
     - Migration 2: backfill `UPDATE orders SET new_name = old_name WHERE new_name IS NULL;`
     - Code change: stop writing old
     - Migration 3: `ALTER TABLE orders DROP COLUMN old_name;`

   - **Changing a type**:
     - Almost always requires the rename pattern above

6. **Locking patterns**:
   - `SELECT ... FOR UPDATE` for state machine transitions (Order saga)
   - `SELECT ... FOR UPDATE NOWAIT` for race-resolution (Delivery claim) — fails fast for losers
   - `SELECT ... FOR UPDATE SKIP LOCKED` for outbox pollers — multiple pods process different rows
   - Advisory locks (`pg_advisory_lock`) for cross-pod coordination (saga timeout enforcer)

7. **Indexing rules**:
   - Index every foreign key
   - Index every column used in WHERE clauses on hot queries
   - Composite index where access pattern uses multiple columns together
   - Periodic check: drop unused indexes (verify via `pg_stat_user_indexes`)
   - Migration-style index naming: `idx_{table}_{cols}` (e.g., `idx_orders_customer_id_created_at`)

8. **HikariCP tuning**:
   ```yaml
   spring:
     datasource:
       hikari:
         maximum-pool-size: 10                # = (RDS max_connections / pod_count) * 0.8
         minimum-idle: 2
         connection-timeout: 5000
         idle-timeout: 300000                 # 5 min
         max-lifetime: 1800000                # 30 min — must be < RDS connection timeout
         leak-detection-threshold: 60000      # 1 min
         pool-name: order-service-pool
   ```

9. **Aurora-specific config**:
   - Use the *cluster* endpoint for writes
   - Use the *reader* endpoint for read replicas (`@Transactional(readOnly=true)` triggers reader)
   - Configure read-write split via `spring.datasource` properties pointing at cluster, plus a separate reader datasource
   - Failover is fast (<30s) but apps must handle it: HikariCP's connection-timeout should be tight to detect dead connections

10. **Outbox table per service**: see `outbox-pattern` skill for the schema.

11. **Common SQL patterns we use**:

    - Upsert with returning:
      ```sql
      INSERT INTO promo_codes (user_id, code, code_type, ...)
      VALUES (?, ?, ?, ...)
      ON CONFLICT (user_id, code_type) DO NOTHING
      RETURNING id;
      ```

    - Outbox poll with skip-locked:
      ```sql
      SELECT * FROM outbox
      WHERE processed_at IS NULL
      ORDER BY created_at
      LIMIT 100
      FOR UPDATE SKIP LOCKED;
      ```

    - Saga timeout query:
      ```sql
      SELECT * FROM orders
      WHERE state NOT IN ('DELIVERED', 'CANCELED', 'FAILED')
        AND updated_at < NOW() - INTERVAL '5 minutes';
      ```

12. **Migration safety hook** (referenced — see hook-specs.md):
    - The `flyway-migration-safety` pre-commit hook rejects DROP COLUMN, etc., without `--allow-breaking` flag

13. **Test setup**:
    - Testcontainers PostgreSQL
    - Run migrations against the container in test setup
    - `@Sql` annotation for per-test data setup

ANTI-PATTERNS TO FLAG
- Editing a previously-merged `V` file (migrations are immutable)
- Same migration version number used twice
- DROP COLUMN, DROP TABLE, ALTER COLUMN ... NOT NULL in a migration without preceding nullable-add migration
- Long-running ALTER TABLE on production tables (use `pg_repack` or table swap instead)
- App-level migrations via Spring Data's auto-DDL (`spring.jpa.hibernate.ddl-auto=update`) — must be `none`
- Schema migrations bundled with code changes in same PR (do them as separate PRs, separate deploys)
- HikariCP `maximum-pool-size` set without considering RDS max connections
- `LocalDateTime` columns instead of `TIMESTAMPTZ`

REFERENCE DOCS
- `architecture.md` Section 5 (Data Design)
- `common-conventions.md` Section 21 (Database Conventions)
- `build-plan.md` Steps 0.4 (RDS provisioning), 2.1, 4.1, 8.1, 10.1

EXAMPLES TO INCLUDE
- A complete `V1__create_users.sql` with proper constraints and indexes
- A complete backwards-compatible "add column" migration sequence
- A complete `V*__add_outbox_table.sql` using the schema from `outbox-pattern` skill
- HikariCP config example
- Testcontainers PG setup

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit access for `*.sql`, repository classes, application yml.
```

---

## 9. redis-elasticache-patterns

```text
Create a Claude Code skill named `redis-elasticache-patterns`.

PURPOSE
Three services use Redis (Basket as primary store, Menu cache, shared rate limiting + sessions). Each plays a different role with different correctness requirements. This skill encodes the patterns and prevents using Redis incorrectly (treating cache as primary, treating primary as cache).

WHEN TO TRIGGER
- Editing Java code that imports `org.springframework.data.redis.*`, `io.lettuce.*`, or uses `RedisTemplate`
- Editing `application*.yml` with `spring.data.redis` keys
- User mentions Redis, ElastiCache, cache-aside, "cache key", session, "rate limit", Lua script
- Editing Lua scripts (`*.lua`)

CONTENT TO INCLUDE

1. **Tech stack**:
   - Redis 7.x (ElastiCache Cluster Mode)
   - Lettuce client (Spring Boot default, NOT Jedis)
   - Spring Data Redis abstraction
   - In-transit TLS, AUTH token from Secrets Manager

2. **Connection config** (canonical):
   ```yaml
   spring:
     data:
       redis:
         cluster:
           nodes: ${REDIS_CLUSTER_NODES}    # comma-separated host:port list
         password: ${REDIS_AUTH_TOKEN}
         ssl:
           enabled: true
         timeout: 2000ms
         connect-timeout: 5000ms
         lettuce:
           cluster:
             refresh:
               adaptive: true
               period: 30s
   ```

3. **Three usage patterns**:

   **(a) Basket — primary store**:
   - Redis IS the source of truth, not a cache
   - Cluster Mode for durability via replication
   - TLS in transit, encryption at rest (KMS)
   - 24-hour TTL on each cart key
   - Hash data structure: `HSET basket:{userId} restaurantId X items_json Y created_at Z`
   - On every modification: refresh TTL via `EXPIRE`
   - Snapshot retention 7 days (config in Terraform)

   **(b) Menu cache — cache-aside**:
   - Redis is a cache; DynamoDB is the source of truth
   - Read flow: cache lookup → miss → DDB read → cache populate (TTL 30 min) → return
   - Write flow: DDB write → explicit `DEL` of affected cache keys
   - Key versioning: `menu:v1:restaurant:{restaurantId}` — bump v1→v2 to invalidate everything platform-wide
   - Compress JSON if >5KB (snappy)

   **(c) Cross-service rate limit + sessions**:
   - Sliding window via Lua script (atomic check-and-increment)
   - Session keys: `session:{jti}` with TTL = remaining JWT lifetime
   - Idempotency keys: `idem:{service}:{userId}:{key}` with 24h TTL

4. **Cluster mode key tagging**:
   - Keys with `{tag}` substring hash to the same slot — useful for multi-key operations
   - Example: `basket:{user_abc123}:meta`, `basket:{user_abc123}:items` are co-located
   - Use sparingly: defeats sharding if overused

5. **Lua scripts** (sliding window rate limit example):
   ```lua
   -- KEYS[1]: rate limit key, e.g. "rl:login:{ip:1.2.3.4}"
   -- ARGV[1]: window in seconds (e.g. 60)
   -- ARGV[2]: limit (e.g. 10)
   -- ARGV[3]: now in milliseconds
   local key = KEYS[1]
   local window = tonumber(ARGV[1])
   local limit = tonumber(ARGV[2])
   local now = tonumber(ARGV[3])
   local cutoff = now - (window * 1000)
   redis.call('ZREMRANGEBYSCORE', key, 0, cutoff)
   local count = redis.call('ZCARD', key)
   if count >= limit then
     return {0, count, redis.call('PTTL', key)}
   end
   redis.call('ZADD', key, now, now)
   redis.call('EXPIRE', key, window)
   return {1, count + 1, window * 1000}
   ```
   Run via `RedisTemplate.execute(RedisScript.of(script, List.class), keys, args...)`

6. **Atomic operations vs WATCH/MULTI**:
   - For single-key updates: use `INCR`, `SETNX`, `HSETNX`, conditional Lua scripts
   - For multi-step transactions: use Lua scripts (single round-trip, atomic)
   - Avoid WATCH/MULTI/EXEC unless really needed (slower, more error paths)

7. **Spring Data Redis usage** (canonical):
   ```java
   @Component
   public class BasketRepository {
     private final StringRedisTemplate redis;
     private final ObjectMapper json;

     public Optional<Basket> findByUserId(String userId) {
       Map<Object, Object> entries = redis.opsForHash().entries("basket:" + userId);
       if (entries.isEmpty()) return Optional.empty();
       redis.expire("basket:" + userId, Duration.ofHours(24));   // touch
       return Optional.of(deserialize(entries));
     }
   }
   ```

8. **Connection pool**:
   - Lettuce uses one connection per cluster node by default (not pooled per node)
   - For highest throughput, use `commandLatencyCollector` and watch `lettuce.command.latency` metric

9. **Cache key conventions**:
   - Prefix by service: `basket:`, `menu:v1:`, `idem:order-service:`
   - Always include version when caching shapes: `menu:v2:` not `menu:`
   - URL-safe ASCII only — no spaces, special chars
   - TTL in key name when not obvious: `session:30m:abc123`

10. **Eviction handling**:
    - For cache use case: eviction is expected; code must handle missing keys
    - For primary-store use case (Basket): eviction is rare but possible under memory pressure; have monitoring
    - Cluster Mode `maxmemory-policy`: `allkeys-lru` for caches, `volatile-ttl` for sessions

11. **Test setup** (Testcontainers):
    ```java
    @Container
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry r) {
      r.add("spring.data.redis.host", redis::getHost);
      r.add("spring.data.redis.port", redis::getFirstMappedPort);
      r.add("spring.data.redis.ssl.enabled", () -> false);
    }
    ```

ANTI-PATTERNS TO FLAG
- `KEYS *` — blocks the server, never use in production
- `FLUSHDB`, `FLUSHALL` — never in service code; ops-only
- Long-running Lua scripts (>50ms) — block other clients
- Storing PII or sensitive data without encryption-at-rest enabled
- Missing TTL on cache keys (memory leak)
- Cache invalidation via TTL alone (use explicit DEL on writes)
- Treating Redis-as-cache like a primary store (no fallback path)
- Treating Redis-as-primary like a cache (no eviction monitoring)
- `RedisTemplate<Object, Object>` — use typed `StringRedisTemplate` or define types explicitly
- Connection-per-request patterns (Lettuce shares connections; don't break that)

REFERENCE DOCS
- `architecture.md` Section 3 (AWS mapping), Section 5.3 (Redis usage)
- `common-conventions.md` Section 12 (Rate Limiting), Section 13 (Idempotency)
- `build-plan.md` Step 0.6 (Redis provisioning), Phase 6 (Basket)

USE CONTEXT7
Suggest "use context7 for Spring Data Redis 4 docs" — significant API changes in newer versions.

EXAMPLES TO INCLUDE
- BasketRepository complete impl (primary-store pattern)
- MenuCache complete impl (cache-aside pattern)
- Sliding-window rate limit Lua script with usage code
- Testcontainers setup

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit.
```

---

## 10. saga-state-machine

```text
Create a Claude Code skill named `saga-state-machine`.

PURPOSE
Phase 8 has 11 steps and all of them touch the order saga. The saga's correctness depends on rigorous attention to: state transitions, idempotency under retry, compensation paths, timeout handling. Without a single-source-of-truth skill, Step 8.4 might define different state names than Step 8.7 used. Phase 8 is the riskiest 11-step stretch of the build.

WHEN TO TRIGGER
- Working directory `services/order-service/`
- User mentions saga, state machine, OrderState, compensation, "Spring StateMachine"
- Editing files matching `services/order-service/**/saga/**` or `**/state*.java`
- Editing migration files for `orders`, `saga_compensation_acks`, or related tables
- User mentions "PAYMENT_FAILED handler", "PAYMENT_SUCCESS handler", any of the saga events

CONTENT TO INCLUDE

1. **The 10 states**:
   ```
   PENDING                — order created, waiting for payment
   PAID                   — payment captured, waiting for kitchen
   KITCHEN_ACCEPTED       — kitchen confirmed
   FOOD_READY             — kitchen finished, awaiting pickup
   OUT_FOR_DELIVERY       — driver has the food
   DELIVERED              — terminal success
   CANCELING              — user/system requested cancel; compensation in progress
   COMPENSATING           — failure detected; rollback in progress
   CANCELED               — terminal: user-requested cancel completed
   FAILED                 — terminal: system-detected failure compensated
   ```

2. **The 13 valid transitions**:
   ```
   FORWARD:
     PENDING            → PAID                 [PAYMENT_SUCCESS]
     PAID               → KITCHEN_ACCEPTED     [KITCHEN_ACCEPT]
     KITCHEN_ACCEPTED   → FOOD_READY           [FOOD_READY]
     FOOD_READY         → OUT_FOR_DELIVERY     [DRIVER_PICKED_UP]
     OUT_FOR_DELIVERY   → DELIVERED            [DELIVERED]

   USER-INITIATED:
     PENDING            → CANCELED             [USER_CANCEL] (no compensation needed)
     PAID               → CANCELING            [USER_CANCEL]
     KITCHEN_ACCEPTED   → CANCELING            [USER_CANCEL] (with restaurant approval rules)

   COMPENSATION:
     {any forward}      → COMPENSATING         [PAYMENT_FAILED, RESTAURANT_REJECTED, SAGA_TIMEOUT]
     CANCELING          → CANCELED             [all compensation acks received]
     COMPENSATING       → FAILED               [all compensation acks received]
   ```

3. **Spring StateMachine config** (canonical):
   ```java
   @Configuration
   @EnableStateMachineFactory
   public class OrderStateMachineConfig extends StateMachineConfigurerAdapter<OrderState, OrderEvent> {
     @Override
     public void configure(StateMachineStateConfigurer<OrderState, OrderEvent> states) throws Exception {
       states.withStates()
         .initial(OrderState.PENDING)
         .states(EnumSet.allOf(OrderState.class))
         .end(OrderState.DELIVERED)
         .end(OrderState.CANCELED)
         .end(OrderState.FAILED);
     }
     @Override
     public void configure(StateMachineTransitionConfigurer<OrderState, OrderEvent> t) throws Exception {
       t.withExternal()
         .source(OrderState.PENDING).target(OrderState.PAID).event(OrderEvent.PAYMENT_SUCCESS)
       .and()
         .withExternal()
         .source(OrderState.PAID).target(OrderState.KITCHEN_ACCEPTED).event(OrderEvent.KITCHEN_ACCEPT)
         // ... 13 transitions total
       ;
     }
   }
   ```

4. **The OrderSaga** (handler class structure):
   ```java
   @Service
   public class OrderSaga {
     @Transactional
     public void onPaymentSuccess(PaymentSuccessEvent event) {
       Order order = repo.findByIdForUpdate(event.orderId())
         .orElseThrow(() -> new OrderNotFoundException(event.orderId()));

       // Idempotency: if already past PENDING, ignore
       if (order.getState() != OrderState.PENDING) {
         log.warn("Idempotent ignore: PAYMENT_SUCCESS for {} in state {}",
           order.getId(), order.getState());
         return;
       }
       order.setState(OrderState.PAID);
       order.setPaidAt(clock.instant());
       order.setPaymentIntentId(event.paymentIntentId());
       repo.save(order);

       // Outbox: next saga commands
       outbox.save(makeOutboxRow(order, "ORDER_PAID", payloadFor(order), "KAFKA", "order-events"));
       outbox.save(makeOutboxRow(order, "RECEIPT_REQUESTED", receiptPayload(order), "KAFKA", "order-events"));
     }
     // ... onKitchenAccepted, onFoodReady, onDelivered ...
     // ... onPaymentFailed, onRestaurantRejected, onUserCanceled ...
     // ... onCompensationAck ...
   }
   ```

5. **The compensation plan** (CompensationPlan class):
   ```java
   public class CompensationPlan {
     public List<OutboxCommand> compensationsFor(Order order, FailureCause cause) {
       List<OutboxCommand> cmds = new ArrayList<>();

       if (order.getState().hasReachedKitchen()) {
         cmds.add(cancel("CANCEL_KITCHEN_TICKET", "kitchen-compensation", order));
       }
       if (order.hasPromoCode()) {
         cmds.add(restore("RESTORE_PROMO_CODE", "promotion-compensation", order));
       }
       cmds.add(restore("RESTORE_BASKET", "basket-compensation", order));
       cmds.add(notify(cause == FailureCause.PAYMENT_FAILED ? "NOTIFY_PAYMENT_FAILED" : "NOTIFY_CANCELED", order));

       return cmds;
     }
   }
   ```

6. **Idempotency rules** (every handler):
   - Lock the row with `SELECT FOR UPDATE`
   - Check state — if not the expected predecessor, log warn and return (no error, no work)
   - Apply the transition
   - Save (with version increment for optimistic locking)
   - Write outbox rows in same transaction

7. **The expected_compensation_acks JSONB**:
   When compensation starts, populate the column with the list of expected ack types:
   ```json
   {
     "expected": ["TICKET_CANCELED", "PROMO_RESTORED", "BASKET_RESTORED", "NOTIFICATION_SENT"],
     "received": []
   }
   ```
   Each ack handler appends to `received`. When `received.length == expected.length`, transition to terminal.

8. **The SagaTimeoutEnforcer**:
   ```java
   @Component
   public class SagaTimeoutEnforcer {
     @Scheduled(fixedDelay = 30_000)
     public void run() {
       try (var lock = advisoryLock("saga-timeout-enforcer")) {
         List<Order> stuck = repo.findStuckOrders(clock.instant().minus(timeoutFor(state)));
         for (Order o : stuck) {
           log.warn("Saga timeout for {} in state {}", o.getId(), o.getState());
           saga.onSagaTimeout(o.getId());
         }
       }
     }
     // timeoutFor: PENDING=2min, PAID=5min, KITCHEN_ACCEPTED=30min, etc.
   }
   ```

9. **Test patterns** (CRITICAL):
   For every transition you implement, write three tests:
   - **Happy**: state advances correctly
   - **Idempotent**: same event arriving twice is a no-op
   - **Out-of-order**: event for a future state arriving doesn't break (logged and ignored)

   Example:
   ```java
   @Test
   void should_advance_to_PAID_when_PAYMENT_SUCCESS_received_in_PENDING() { ... }

   @Test
   void should_be_idempotent_when_PAYMENT_SUCCESS_received_twice() { ... }

   @Test
   void should_not_advance_when_PAYMENT_SUCCESS_received_in_FOOD_READY() { ... }
   ```

10. **Logging in saga handlers**:
    - Use MDC: `try (var mdc = MDC.putCloseable("orderId", id))`
    - Log at INFO for state transitions, WARN for idempotent ignores, ERROR for unexpected state

11. **Visualization**:
    - Spring StateMachine has built-in visualization to PlantUML
    - Generate on every CI build to `target/saga.puml` for docs

12. **Performance considerations**:
    - The PENDING-to-DELIVERED happy path involves ~6 row updates total
    - With virtual threads enabled, listener concurrency can be high (20+) without thread pressure
    - Watch `saga.transition.duration_seconds` metric — anything >100ms p99 needs investigation

ANTI-PATTERNS TO FLAG
- Direct state assignment without going through the state machine (`order.state = PAID`)
- Missing `SELECT FOR UPDATE` on the order load
- Side effects (Kafka publish, REST call) outside the `@Transactional` method
- Compensation handler that's not idempotent
- Saga handler that throws on idempotent retry (must be silent no-op)
- Missing `expected_compensation_acks` population at COMPENSATING entry
- Hardcoded timeouts in handlers (use `timeoutFor(state)` from config)
- State transitions not tested for the "out-of-order event" case
- New state added without updating the StateMachineConfig
- New event added without updating `OrderEvent` enum AND adding a transition entry

REFERENCE DOCS
- `architecture.md` Section 7 (Order flow), Section 8 (Saga & Outbox)
- `build-plan.md` Steps 8.1 through 8.11 (entire Phase 8)
- `common-conventions.md` Section 8 (Event Envelope) — saga events use this

USE CONTEXT7
Suggest "use context7 for Spring StateMachine docs" — moderately small project, docs change.

EXAMPLES TO INCLUDE
- Complete StateMachineConfig
- Complete OrderSaga handler for one forward transition
- Complete CompensationPlan
- Complete SagaTimeoutEnforcer
- Three tests for one transition (happy/idempotent/out-of-order)

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit access for `services/order-service/**`.
```

---

## 11. resilience4j-patterns

```text
Create a Claude Code skill named `resilience4j-patterns`.

PURPOSE
Phases 6, 7, 8 are heavy on resilience patterns. Bad defaults cascade — a too-aggressive retry can DDoS Stripe; a too-loose circuit breaker can let a partial outage become a full outage. This skill encodes per-call-type defaults and the naming conventions that make Grafana dashboards uniform.

WHEN TO TRIGGER
- Editing Java code that imports `io.github.resilience4j.*`
- User mentions circuit breaker, retry, bulkhead, rate limiter, time limiter, fallback, "Resilience4j", "@CircuitBreaker", "@Retry"
- Editing `application*.yml` with `resilience4j.*` keys
- Editing client classes (Stripe, gRPC, internal HTTP)

CONTENT TO INCLUDE

1. **Tech stack**:
   - Resilience4j (latest in BOM)
   - Spring Boot integration via `resilience4j-spring-boot4` (or whichever is current)
   - Micrometer integration for metrics

2. **Defaults** (in `common-resilience/src/main/resources/application-resilience.yml`):
   ```yaml
   resilience4j:
     circuitbreaker:
       configs:
         default:
           sliding-window-type: COUNT_BASED
           sliding-window-size: 20
           minimum-number-of-calls: 10
           failure-rate-threshold: 50
           wait-duration-in-open-state: 60s
           permitted-number-of-calls-in-half-open-state: 5
           record-exceptions:
             - java.io.IOException
             - java.util.concurrent.TimeoutException
           ignore-exceptions:
             - com.{org}.platform.exceptions.ValidationException
     retry:
       configs:
         default:
           max-attempts: 3
           wait-duration: 200ms
           exponential-backoff-multiplier: 2
           retry-exceptions:
             - java.io.IOException
             - java.util.concurrent.TimeoutException
           ignore-exceptions:
             - com.{org}.platform.exceptions.ClientErrorException
     timelimiter:
       configs:
         default:
           timeout-duration: 5s
           cancel-running-future: true
     bulkhead:
       configs:
         default:
           max-concurrent-calls: 25
           max-wait-duration: 100ms
     ratelimiter:
       configs:
         default:
           limit-for-period: 100
           limit-refresh-period: 1s
           timeout-duration: 0
   ```

3. **Per-call-type overrides** (each service tunes for its specific calls):

   | Call type | Timeout | Retry | Circuit breaker | Bulkhead |
   |---|---|---|---|---|
   | gRPC internal (Menu, Promotion) | 200ms | 2 attempts, 50ms backoff | 50% failure / 20 calls | 20 concurrent |
   | REST internal | 500ms | 1 attempt | 50% / 20 | 20 |
   | Stripe API charge | 5s | 3 attempts, 200ms × 2 backoff | 50% / 20 | 20 (charge bulkhead) |
   | Stripe API refund | 5s | 3 attempts | 50% / 20 | 10 (refund bulkhead, separate from charge) |
   | DynamoDB | 1s | (SDK default) | n/a | n/a |
   | SES email | 3s | 2 attempts | 60% / 20 | 10 |

4. **Naming conventions** (for dashboards and alerts):
   - Circuit breakers: `{remote-target}-{operation}` — e.g. `stripe-charge`, `menu-grpc-verify`
   - Retries: same as circuit breakers
   - Bulkheads: `{operation-class}` — e.g. `payment-charge`, `payment-refund`
   - Time limiters: same as circuit breakers
   - Rate limiters: `{endpoint-or-feature}` — e.g. `login`, `register`

5. **Annotation usage** (canonical):
   ```java
   @CircuitBreaker(name = "stripe-charge", fallbackMethod = "chargeFallback")
   @Retry(name = "stripe-charge")
   @Bulkhead(name = "payment-charge", type = Bulkhead.Type.SEMAPHORE)
   @TimeLimiter(name = "stripe-charge")
   public CompletableFuture<ChargeResult> charge(ChargeRequest req) {
     return CompletableFuture.supplyAsync(() -> stripeClient.charge(req));
   }

   public CompletableFuture<ChargeResult> chargeFallback(ChargeRequest req, Throwable t) {
     log.error("Charge fallback for order {}", req.orderId(), t);
     throw new PaymentGatewayUnavailableException(req.orderId(), t);
   }
   ```

6. **Fallback method rules**:
   - Same return type as the primary method
   - Accepts the original args + a `Throwable` parameter
   - Fallback can have its own annotations (rare)
   - Fallback is the *graceful degradation* — for Stripe failure, throw a wrapped domain exception with code `PAYMENT_GATEWAY_UNAVAILABLE`; for Menu Service failure on a non-critical path, return a stale cached result

7. **Bulkhead types**:
   - `SEMAPHORE` (default): caller blocks waiting for a permit; cheap but doesn't bound thread usage
   - `THREADPOOL`: dedicated thread pool; bounds threads but more memory; use for slow/blocking calls
   - With virtual threads (Java 25), prefer `SEMAPHORE` everywhere

8. **TimeLimiter requirements**:
   - Only works on async return types (`CompletableFuture<T>`, `Mono<T>`)
   - For sync methods, use HTTP client timeouts directly + retry on `TimeoutException`

9. **Exceptions and retry semantics**:
   - Retry only idempotent operations (GET, conditional-write PATCH/DELETE)
   - NEVER retry non-idempotent operations without an idempotency key (you'll double-charge)
   - List specific retryable exceptions — never `Throwable` catch-all
   - `ignore-exceptions` for client errors (4xx) that won't succeed on retry

10. **Metrics**:
    - All Resilience4j components auto-register Micrometer metrics
    - Critical metric names:
      - `resilience4j.circuitbreaker.state` (with name= label)
      - `resilience4j.circuitbreaker.calls` (with kind= label: successful/failed/slow/not_permitted)
      - `resilience4j.retry.calls`
      - `resilience4j.bulkhead.available.concurrent.calls`
    - Exposed via `/actuator/prometheus`

11. **Event listeners** (optional, for logging):
    ```java
    @Bean
    public RegistryEventConsumer<CircuitBreaker> cbEventConsumer() {
      return new RegistryEventConsumer<>() {
        public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> event) {
          event.getAddedEntry().getEventPublisher()
            .onStateTransition(e -> log.warn("CB {} state {} → {}",
                e.getCircuitBreakerName(), e.getStateTransition().getFromState(),
                e.getStateTransition().getToState()));
        }
      };
    }
    ```

12. **Testing resilience**:
    - Use WireMock to inject failures, slow responses, intermittent errors
    - Verify circuit opens after expected failure count
    - Verify fallback returns expected response on `CallNotPermittedException`
    - Verify retry hits the expected number of attempts

ANTI-PATTERNS TO FLAG
- Annotation without a corresponding `name` config in `application.yml`
- Retrying non-idempotent operations
- `retry-exceptions: java.lang.Throwable` (catches everything, even bugs)
- Missing `fallbackMethod` on `@CircuitBreaker` (hard failure on circuit open)
- Fallback method with different return type
- Fallback method that itself can fail without handling (cascade)
- Bulkhead size larger than the connection pool of the underlying client
- Time limiter on a sync method (only works on async returns)
- `Bulkhead.Type.THREADPOOL` with virtual threads enabled (defeats the purpose)
- Hardcoded thresholds in code instead of config

REFERENCE DOCS
- `architecture.md` Section 4 (Resilience patterns per service)
- `common-conventions.md` Section 14 (Resilience pattern naming)
- `build-plan.md` Step 1.3 (common-resilience), Phase 6, 7, 8

USE CONTEXT7
Suggest "use context7 for Resilience4j Spring Boot 4 integration docs" — important since the integration module renamed across versions.

EXAMPLES TO INCLUDE
- Complete `application-resilience.yml` with defaults
- Complete annotated client method with all 4 patterns
- Fallback method showing graceful degradation
- WireMock test injecting failures

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit.
```

---

## 12. grpc-service-conventions

```text
Create a Claude Code skill named `grpc-service-conventions`.

PURPOSE
Four services have gRPC clients or servers (Menu produces, Promotion produces, Basket consumes, Order consumes). Without conventions, .proto files and channel configs drift, and consumer-driven contract tests become impossible.

WHEN TO TRIGGER
- Editing `*.proto` files
- Editing Java code with `io.grpc.*` imports
- User mentions gRPC, "stub", "channel", "deadline", `protoc`, "consumer-driven contracts", Pact

CONTENT TO INCLUDE

1. **Tech stack**:
   - gRPC Java (latest in BOM)
   - protobuf 3
   - protobuf-maven-plugin for code generation
   - Spring Boot gRPC integration (yidongnan/grpc-spring-boot-starter or successor)

2. **Where .proto files live**:
   - `platform-shared-libs/common-events/src/main/proto/{service}.proto`
   - One file per service that exposes gRPC
   - Generated stubs available to all services via the BOM

3. **Service definition pattern** (canonical):
   ```protobuf
   syntax = "proto3";
   package menu.v1;
   option java_package = "com.{org}.platform.menu.grpc";
   option java_multiple_files = true;

   service MenuService {
     rpc VerifyItem(VerifyItemRequest) returns (VerifyItemResponse);
   }

   message VerifyItemRequest {
     string restaurant_id = 1;
     string item_id = 2;
   }

   message VerifyItemResponse {
     bool exists = 1;
     bool available_now = 2;
     string current_price = 3;            // Money serialized as string "amount;currency"
     bool restaurant_paused = 4;
   }
   ```

4. **Versioning via package**:
   - Package name includes major version: `menu.v1`, `promotion.v1`
   - Breaking change: new package `menu.v2`, both versions supported during deprecation
   - Within a version: only additive changes (new fields with new tag numbers)

5. **Server side** (canonical):
   ```java
   @GrpcService
   public class MenuGrpcService extends MenuServiceGrpc.MenuServiceImplBase {
     @Override
     public void verifyItem(VerifyItemRequest req, StreamObserver<VerifyItemResponse> observer) {
       try (var mdc = MDC.putCloseable("restaurantId", req.getRestaurantId())) {
         var result = menuService.verify(req.getRestaurantId(), req.getItemId());
         observer.onNext(toGrpcResponse(result));
         observer.onCompleted();
       } catch (NotFoundException e) {
         observer.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
       } catch (Exception e) {
         observer.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
       }
     }
   }
   ```

6. **Client side** (canonical):
   ```java
   @Component
   public class MenuGrpcClient {
     @GrpcClient("menu-service")
     private MenuServiceGrpc.MenuServiceBlockingStub stub;

     @CircuitBreaker(name = "menu-grpc-verify", fallbackMethod = "verifyFallback")
     @Retry(name = "menu-grpc-verify")
     public ItemAvailability verify(String restaurantId, String itemId) {
       try {
         var resp = stub.withDeadlineAfter(200, TimeUnit.MILLISECONDS)
                        .verifyItem(VerifyItemRequest.newBuilder()
                            .setRestaurantId(restaurantId)
                            .setItemId(itemId).build());
         return ItemAvailability.fromGrpc(resp);
       } catch (StatusRuntimeException e) {
         throw mapStatus(e);
       }
     }
   }
   ```

7. **Channel configuration** (Spring Boot starter syntax):
   ```yaml
   grpc:
     client:
       menu-service:
         address: 'discovery:///menu-service'
         negotiation-type: TLS
         enable-keep-alive: true
         keep-alive-time: 30s
         keep-alive-timeout: 10s
         keep-alive-without-calls: true
   ```

8. **Deadline propagation rule**:
   - Caller sets the deadline (`withDeadlineAfter`) — never assume server will time out
   - Server respects the deadline via `Context.current().getDeadline()`
   - On long internal computations, check deadline periodically and abort early

9. **Error model** (gRPC Status mapping to platform error codes):
   ```
   NOT_FOUND               → ErrorCode appropriate to context (USER_NOT_FOUND, ORDER_NOT_FOUND, ...)
   INVALID_ARGUMENT        → VALIDATION_FAILED
   FAILED_PRECONDITION     → context-specific (PROMO_CODE_INELIGIBLE, etc.)
   ALREADY_EXISTS          → context-specific (USER_EMAIL_TAKEN, etc.)
   UNAVAILABLE             → SERVICE_UNAVAILABLE / DEPENDENCY_CIRCUIT_OPEN
   DEADLINE_EXCEEDED       → SERVICE_UNAVAILABLE
   PERMISSION_DENIED       → AUTH_INSUFFICIENT_ROLE
   UNAUTHENTICATED         → AUTH_TOKEN_INVALID / AUTH_TOKEN_EXPIRED
   INTERNAL                → INTERNAL_ERROR (logged on server)
   ```

   For richer errors, use the `google.rpc.Status` proto with `details` (Any-typed list) instead of plain strings.

10. **Health checks**:
    - gRPC has its own health check protocol (`grpc.health.v1.Health`)
    - Spring Boot starter exposes it automatically
    - K8s liveness/readiness uses HTTP health endpoint, not gRPC health (simpler)

11. **Tracing propagation**:
    - OTel auto-instrumentation handles this if the agent is attached
    - Otherwise: client interceptor that puts traceId in metadata; server interceptor that extracts and starts a span
    - In `common-observability`: `GrpcTracingInterceptor` registered globally

12. **Authentication**:
    - For internal-only gRPC: mTLS via cluster certificates (managed by service mesh or manual)
    - For service-to-service auth: JWT in metadata header `authorization: Bearer ...` (extract on server side via interceptor)

13. **Testing**:
    - Use `InProcessServer` and `InProcessChannel` for unit tests
    - Use Pact for consumer-driven contracts where the proto is stable but the wire-level behavior matters
    - Integration tests via Testcontainers running the actual server pod

14. **Reflection**:
    - Enable for staging (allows `grpcurl` debugging)
    - Disable in production for security

ANTI-PATTERNS TO FLAG
- Missing `withDeadlineAfter` on client calls (server can hang client indefinitely)
- Server returning `Status.OK` with empty response when there should be a not-found (use `NOT_FOUND`)
- `Status.INTERNAL` with a description that leaks internal details
- Generated stubs committed to git (regenerate on build)
- .proto without `option java_multiple_files = true` (results in nested classes)
- Field number reuse after deletion (breaks wire compatibility)
- Required fields (proto3 has no required; old proto2 patterns leak)
- Using `Any` types without strong reason
- Missing OTel propagation interceptor

REFERENCE DOCS
- `architecture.md` Section 1 (gRPC use cases), Section 3
- `common-conventions.md` Section 16 (API Versioning), Section 19 (Naming)
- `build-plan.md` Steps 4.3 (Promotion gRPC), 5.4 (Menu gRPC), 6.3 (Basket client), 8.3 (Order client)

USE CONTEXT7
Suggest "use context7 for grpc-spring-boot-starter docs" — Spring Boot 4 compatibility may require updates.

EXAMPLES TO INCLUDE
- Complete .proto file
- Complete server impl
- Complete client wrapper with circuit breaker + retry + deadline
- Pact contract test example

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit.
```

---

# Tier 3 — Lighter Skills

## 13. aws-codebuild-buildspec-template

```text
Create a Claude Code skill named `aws-codebuild-buildspec-template`.

PURPOSE
Phase 13 has 6 steps and most touch buildspec.yml files. Each service gets its own pipeline; buildspecs would drift without a central reference. This skill provides the canonical phases and patterns.

WHEN TO TRIGGER
- Editing `buildspec*.yml` files
- Editing `platform-infra/buildspec-templates/**`
- User mentions CodeBuild, "buildspec", "CodePipeline", CI build phases, ECR push

CONTENT TO INCLUDE

1. **Standard phases**:
   - `install`: language runtimes, package managers
   - `pre_build`: auth (CodeArtifact, ECR), env setup
   - `build`: compile, test, package
   - `post_build`: publish (ECR push, GitOps bump)

2. **Standard buildspec for service build-test-scan**:
   ```yaml
   version: 0.2

   env:
     variables:
       JAVA_HOME: /usr/lib/jvm/java-25-amazon-corretto
       MAVEN_OPTS: "-Xmx2048m -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn"
     parameter-store:
       CODEARTIFACT_DOMAIN: /platform/codeartifact-domain
       CODEARTIFACT_REPO: /platform/codeartifact-repo
       SONAR_HOST_URL: /platform/sonar-host
     secrets-manager:
       SONAR_TOKEN: platform/sonar:token

   phases:
     install:
       runtime-versions:
         java: corretto25
       commands:
         - echo "Java $(java -version 2>&1)"
         - mvn -v
     pre_build:
       commands:
         - aws codeartifact login --tool maven --domain $CODEARTIFACT_DOMAIN --repository $CODEARTIFACT_REPO
         - export CODEARTIFACT_AUTH_TOKEN=$(aws codeartifact get-authorization-token --domain $CODEARTIFACT_DOMAIN --query authorizationToken --output text)
     build:
       commands:
         - mvn -B -pl $SERVICE_PATH -am verify -Pcoverage
         - python3 platform-infra/scripts/check-coverage.py $SERVICE_PATH/target/site/jacoco/jacoco.xml 80
         - mvn -B -pl $SERVICE_PATH org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7
     post_build:
       commands:
         - aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $ECR_REGISTRY
         - cd $SERVICE_PATH && docker buildx build --platform linux/amd64,linux/arm64 \
             --tag $ECR_REGISTRY/$SERVICE_NAME:$CODEBUILD_RESOLVED_SOURCE_VERSION \
             --tag $ECR_REGISTRY/$SERVICE_NAME:latest-staging \
             --push .

   artifacts:
     files:
       - imagedefinitions.json
   reports:
     jacoco-coverage:
       files: $SERVICE_PATH/target/site/jacoco/jacoco.xml
       file-format: JACOCOXML
     surefire-reports:
       files: $SERVICE_PATH/target/surefire-reports/*.xml
       file-format: JUNITXML
   ```

3. **Path-filter handling for monorepo**:
   - `SERVICE_PATH` env var passed in by CodePipeline source action
   - `mvn -pl $SERVICE_PATH -am` builds only that service + its deps
   - If `platform-shared-libs/**` changed, build all services (handled by separate trigger logic)

4. **Buildspec variants** (one per pipeline stage):
   - `buildspec-build-test-scan.yml` — main build
   - `buildspec-integration-test.yml` — Testcontainers tests in a separate stage
   - `buildspec-package-and-push.yml` — Docker build + push (split for caching)
   - `buildspec-gitops-bump.yml` — clone gitops, update kustomize image tag, commit, push
   - `buildspec-smoke-test.yml` — hits staging endpoints + k6 perf gate

5. **Caching**:
   ```yaml
   cache:
     paths:
       - '/root/.m2/**/*'
       - 'node_modules/**/*'                   # if frontend
   ```
   Backed by S3 in CodeBuild project config.

6. **Multi-arch Docker**:
   - Always `linux/amd64,linux/arm64` for cost flexibility on EKS
   - `docker buildx` requires QEMU setup; use `docker/setup-qemu-action` equivalent in CodeBuild via `docker buildx create --use`

7. **Artifact passing between stages**:
   - `imagedefinitions.json` for ECS-style deploys (not used in our K8s setup)
   - Use S3 artifacts for Maven jar passing if rare
   - Most stages re-build from source — simpler than artifact passing

8. **Secrets and parameters**:
   - `parameter-store`: non-secret config (URLs, names)
   - `secrets-manager`: actual secrets (tokens, passwords)
   - Never hardcode either

9. **Reports**:
   - JaCoCo via `JACOCOXML`
   - Surefire/Failsafe via `JUNITXML`
   - SonarQube via `mvn sonar:sonar` (separate command)

10. **Build environment**:
    - Recommended image: `aws/codebuild/amazonlinux2-x86_64-standard:5.0` (or Java 25 equivalent when available)
    - Compute size: `BUILD_GENERAL1_MEDIUM` for build/test, `BUILD_GENERAL1_LARGE` for image build
    - Privileged mode required for Docker builds

ANTI-PATTERNS TO FLAG
- Hardcoded ECR registry URLs (use `$ECR_REGISTRY`)
- Hardcoded service names (use `$SERVICE_NAME`)
- `latest` Docker tag in production (only `latest-staging` allowed for convenience)
- `mvn install` instead of `mvn verify` (install creates side effects in `~/.m2/`)
- No coverage gate
- No CVE scan
- Caching `target/**` (those are build artifacts)

REFERENCE DOCS
- `architecture.md` Section 10 (CI/CD)
- `build-plan.md` Phase 13 (entire)
- `common-conventions.md` Section 15 (Build Conventions)

USE CONTEXT7
Suggest "use context7 for AWS CodeBuild buildspec docs" — syntax has evolved.

EXAMPLES TO INCLUDE
- Complete buildspec for build-test-scan
- Complete buildspec for gitops-bump
- Complete buildspec for smoke-test with k6

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit access for `buildspec-templates/**`, `platform-infra/buildspec-templates/**`.
```

---

## 14. argocd-app-of-apps

```text
Create a Claude Code skill named `argocd-app-of-apps`.

PURPOSE
Phase 13 + every service deploy step interact with the GitOps repo structure. Get the layout wrong once and refactoring 10 services' manifests is painful. This skill encodes the kustomize layering and ArgoCD app definitions.

WHEN TO TRIGGER
- Working directory `food-ordering-gitops/`
- Editing `*.yaml` files under `apps/`, `argocd/`, or `kustomization.yaml`
- User mentions ArgoCD, kustomize, "app of apps", "Argo Rollouts", canary, sync wave, AnalysisTemplate

CONTENT TO INCLUDE

1. **Repository layout**:
   ```
   food-ordering-gitops/
   ├── apps/
   │   ├── identity-service/
   │   │   ├── base/
   │   │   │   ├── deployment.yaml
   │   │   │   ├── service.yaml
   │   │   │   ├── hpa.yaml
   │   │   │   ├── serviceaccount.yaml
   │   │   │   ├── externalsecret.yaml
   │   │   │   ├── servicemonitor.yaml
   │   │   │   ├── networkpolicy.yaml
   │   │   │   └── kustomization.yaml
   │   │   └── overlays/
   │   │       ├── staging/
   │   │       │   ├── kustomization.yaml
   │   │       │   ├── image-tag.yaml         ← CI updates this
   │   │       │   ├── replicas.yaml
   │   │       │   └── env-config.yaml
   │   │       └── production/
   │   │           ├── kustomization.yaml
   │   │           ├── image-tag.yaml
   │   │           ├── replicas.yaml
   │   │           ├── env-config.yaml
   │   │           └── rollout.yaml           ← Argo Rollouts canary config
   │   └── ... (one folder per service)
   ├── argocd/
   │   ├── install/
   │   │   └── values.yaml                    ← Helm values for ArgoCD itself
   │   ├── projects/
   │   │   └── services.yaml                  ← AppProject restricting child apps
   │   ├── applications/
   │   │   ├── _app-of-apps.yaml              ← root that creates all children
   │   │   ├── identity-service-staging.yaml
   │   │   ├── identity-service-production.yaml
   │   │   └── ... (one per service per env)
   │   └── notifications/
   │       ├── configmap.yaml
   │       └── triggers.yaml
   └── shared/
       ├── monitoring/                         ← prometheus, grafana, otel collector
       └── observability/                      ← service-monitors, prometheusrules
   ```

2. **App-of-Apps pattern**:
   ```yaml
   # _app-of-apps.yaml
   apiVersion: argoproj.io/v1alpha1
   kind: Application
   metadata:
     name: app-of-apps
     namespace: argocd
   spec:
     project: default
     source:
       repoURL: ssh://git-codecommit...
       targetRevision: main
       path: argocd/applications
       directory:
         recurse: false
         exclude: '_app-of-apps.yaml'      # don't recurse into self
     destination:
       server: https://kubernetes.default.svc
       namespace: argocd
     syncPolicy:
       automated:
         prune: true
         selfHeal: true
   ```

3. **Per-service Application (staging)**:
   ```yaml
   apiVersion: argoproj.io/v1alpha1
   kind: Application
   metadata:
     name: identity-service-staging
     namespace: argocd
   spec:
     project: services
     source:
       repoURL: ssh://git-codecommit...
       targetRevision: main
       path: apps/identity-service/overlays/staging
     destination:
       server: https://kubernetes.default.svc
       namespace: identity
     syncPolicy:
       automated:
         prune: true
         selfHeal: true
       syncOptions:
         - CreateNamespace=true
         - ServerSideApply=true
       retry:
         limit: 3
         backoff:
           duration: 5s
           factor: 2
           maxDuration: 1m
   ```

4. **Per-service Application (production with Argo Rollouts)**:
   ```yaml
   spec:
     syncPolicy:
       automated: null                        # NO auto-sync in prod!
       syncOptions:
         - CreateNamespace=false
         - ServerSideApply=true
   ```
   Production sync is manual (triggered by CodePipeline approval).

5. **Argo Rollouts canary**:
   ```yaml
   apiVersion: argoproj.io/v1alpha1
   kind: Rollout
   metadata:
     name: identity-service
   spec:
     replicas: 4
     strategy:
       canary:
         canaryService: identity-service-canary
         stableService: identity-service-stable
         trafficRouting:
           alb:
             ingress: identity-service-ingress
             servicePort: 8080
         steps:
           - setWeight: 10
           - pause: { duration: 5m }
           - analysis:
               templates:
                 - templateName: error-rate
               args:
                 - name: service-name
                   value: identity-service
           - setWeight: 50
           - pause: { duration: 10m }
           - analysis:
               templates:
                 - templateName: error-rate
                 - templateName: latency-p99
           - setWeight: 100
   ```

6. **AnalysisTemplate** (SLO-based rollback):
   ```yaml
   apiVersion: argoproj.io/v1alpha1
   kind: AnalysisTemplate
   metadata:
     name: error-rate
     namespace: argocd
   spec:
     args:
       - name: service-name
     metrics:
       - name: error-rate
         interval: 1m
         successCondition: result[0] < 0.01
         provider:
           prometheus:
             address: https://prometheus.amp.aws/...
             query: |
               sum(rate(http_server_requests_seconds_count{
                 service="{{args.service-name}}", status=~"5.."}[2m]))
               /
               sum(rate(http_server_requests_seconds_count{
                 service="{{args.service-name}}"}[2m]))
   ```

7. **Sync waves**:
   - Wave 0: namespaces, CRDs, RBAC
   - Wave 1: ConfigMaps, Secrets (External Secrets Operator)
   - Wave 2: Deployments/Rollouts
   - Wave 3: Services, HPAs
   - Wave 4: Ingresses, NetworkPolicies, ServiceMonitors
   - Annotation: `argocd.argoproj.io/sync-wave: "2"`

8. **AppProject** (restrict what apps can deploy):
   ```yaml
   apiVersion: argoproj.io/v1alpha1
   kind: AppProject
   metadata:
     name: services
   spec:
     sourceRepos:
       - 'ssh://git-codecommit.{region}.amazonaws.com/v1/repos/food-ordering-gitops'
     destinations:
       - namespace: 'identity'
         server: https://kubernetes.default.svc
       - namespace: 'order'
         server: https://kubernetes.default.svc
       # ... one per service namespace
     clusterResourceWhitelist:
       - group: ''
         kind: Namespace
     namespaceResourceWhitelist:
       - group: '*'
         kind: '*'
   ```

9. **Notifications**:
   ```yaml
   apiVersion: v1
   kind: ConfigMap
   metadata:
     name: argocd-notifications-cm
   data:
     trigger.on-sync-failed: |
       - when: app.status.operationState.phase in ['Error', 'Failed']
         send: [slack-prod-deploys, pagerduty-sre]
     template.app-sync-failed: |
       message: '{{.app.metadata.name}} sync failed: {{.app.status.operationState.message}}'
     subscriptions: |
       - recipients: [slack:prod-deploys]
         triggers: [on-sync-failed, on-sync-succeeded]
   ```

10. **Image-tag overlay file** (the file CI updates):
    ```yaml
    # apps/identity-service/overlays/staging/image-tag.yaml
    apiVersion: kustomize.config.k8s.io/v1alpha1
    kind: Component
    images:
      - name: identity-service
        newName: 123456789.dkr.ecr.us-east-1.amazonaws.com/identity-service
        newTag: abc123def456    # ← CI overwrites this string
    ```

11. **External Secrets pattern**:
    ```yaml
    apiVersion: external-secrets.io/v1beta1
    kind: ExternalSecret
    metadata:
      name: identity-service-db
    spec:
      refreshInterval: 1h
      secretStoreRef:
        name: aws-secrets-manager
        kind: ClusterSecretStore
      target:
        name: identity-service-db
      dataFrom:
        - extract:
            key: identity-service/staging/db
    ```

ANTI-PATTERNS TO FLAG
- Production app with `automated.selfHeal: true` (prod must be manual-promote)
- Image tags hardcoded in `deployment.yaml` (should be in `image-tag.yaml`)
- Secrets in plain YAML (use ExternalSecrets)
- Skipping sync waves on resources with dependencies
- Multiple Application resources targeting the same path (conflict)
- `automated.prune: false` (allows orphan resources to accumulate)
- Missing AppProject membership (allows app to deploy to any namespace)

REFERENCE DOCS
- `architecture.md` Section 10 (CI/CD)
- `build-plan.md` Step 0.10 (ArgoCD bootstrap), Phase 13.4 (canary)

USE CONTEXT7
Suggest "use context7 for ArgoCD 2.x and Argo Rollouts latest docs" — both evolve frequently.

EXAMPLES TO INCLUDE
- Complete kustomize base + overlay structure for one service
- Complete Argo Rollout with canary + AnalysisTemplate
- Complete ArgoCD Application for staging
- Complete AppProject

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit access for `food-ordering-gitops/**`.
```

---

## 15. aws-lambda-sam-conventions

```text
Create a Claude Code skill named `aws-lambda-sam-conventions`.

PURPOSE
Notification Service runs as Lambda. Outbox publishers for Kitchen and Payment are Lambdas. Review aggregator is a Lambda. Without conventions, each Lambda's SAM template, packaging, and deployment differ.

WHEN TO TRIGGER
- Editing `template.yaml` (SAM templates)
- Working directory contains `lambdas/`
- User mentions Lambda, SAM, "AWS Serverless", `sam deploy`, "function URL"
- Editing handler classes implementing `RequestHandler`

CONTENT TO INCLUDE

1. **Tech stack**:
   - Java 25 runtime (Corretto)
   - AWS SAM CLI for build/deploy
   - AWS Lambda Powertools (logging, tracing, metrics)
   - SnapStart enabled for Java functions (huge cold-start reduction)

2. **Standard SAM template structure**:
   ```yaml
   AWSTemplateFormatVersion: '2010-09-09'
   Transform: AWS::Serverless-2016-10-31

   Globals:
     Function:
       Runtime: java25
       MemorySize: 512
       Timeout: 30
       SnapStart:
         ApplyOn: PublishedVersions
       Environment:
         Variables:
           POWERTOOLS_SERVICE_NAME: !Ref ServiceName
           POWERTOOLS_LOG_LEVEL: INFO
       Tracing: Active

   Parameters:
     ServiceName:
       Type: String
     Environment:
       Type: String
       AllowedValues: [staging, production]

   Resources:
     NotificationFunction:
       Type: AWS::Serverless::Function
       Properties:
         FunctionName: !Sub '{org}-${Environment}-${ServiceName}'
         CodeUri: ./
         Handler: com.{org}.platform.notification.NotificationHandler::handleRequest
         Architectures: [arm64]              # cheaper + faster than x86_64
         Events:
           UserCreatedEvent:
             Type: MSK
             Properties:
               Stream: !Ref MskClusterArn
               Topics: [identity-events]
               StartingPosition: LATEST
               BatchSize: 25
         Policies:
           - AWSLambdaMSKExecutionRole
           - SESCrudPolicy:
               IdentityName: '*'
   ```

3. **Handler class structure**:
   ```java
   public class NotificationHandler implements RequestHandler<KafkaEvent, Void> {
     @Logging(logEvent = false)            // Powertools — log invocation metadata
     @Tracing
     @Metrics(captureColdStart = true)
     public Void handleRequest(KafkaEvent event, Context context) {
       for (var records : event.getRecords().values()) {
         for (var record : records) {
           processRecord(record);
         }
       }
       return null;
     }
   }
   ```

4. **SnapStart specifics**:
   - Cold-start reduced from ~10s to ~200ms for Java
   - Code that runs once at startup (Spring init, AWS client construction) runs at snapshot creation time, not per cold start
   - Avoid in-snapshot state that's environment-specific (random seeds, network connections)
   - Re-fetch secrets after snapshot resume if they may have rotated

5. **Concurrency control**:
   - Reserved concurrency = upper bound (back-pressure on upstream)
   - Provisioned concurrency = guaranteed warm pool (use sparingly, costs more)
   - For SES-backed notification: reserved=10 to avoid SES throttling

6. **Event sources**:
   - **MSK (Kafka)**: native event source mapping; specify topics, batch size, starting position
   - **SQS**: native event source mapping; visibility timeout must be > Lambda timeout
   - **DynamoDB Streams**: native; specify batch size, parallelization, filter criteria
   - **S3**: notification configuration on the bucket (not on the Lambda template directly)

7. **Local development**:
   - `sam local invoke` for one-off invocation tests
   - `sam local start-lambda` for local API
   - `sam local generate-event` for crafting test events
   - Testcontainers for full integration testing

8. **Deployment via CodeBuild**:
   ```yaml
   # buildspec for Lambda deploy
   phases:
     build:
       commands:
         - mvn -B -pl services/notification-service package
         - sam build --base-dir services/notification-service
     post_build:
       commands:
         - sam deploy --stack-name notification-${ENV} --no-confirm-changeset \
             --parameter-overrides Environment=${ENV} ServiceName=notification \
             --capabilities CAPABILITY_IAM
   ```

9. **Aliases and traffic shifting**:
   ```yaml
   AutoPublishAlias: live
   DeploymentPreference:
     Type: Canary10Percent10Minutes
     Alarms:
       - !Ref ErrorAlarm
   ```
   CodeDeploy handles the canary; rolls back automatically on alarm.

10. **Layer usage**:
    - Avoid layers for Java — fat-jar is simpler and SnapStart works better
    - Use layers for shared utilities only when you have multiple Lambdas with the same large dependency

11. **DLQ + retries**:
    - Configure on the function level
    - For SQS event source: visibility timeout 6× function timeout
    - On retry exhaustion: SQS DLQ + CloudWatch alarm

12. **Environment variables vs Parameter Store**:
    - Static config: env vars
    - Frequently-rotating values: Parameter Store with `AWS_LAMBDA_PARAMS_AND_SECRETS_EXTENSION` layer
    - Secrets: Secrets Manager

ANTI-PATTERNS TO FLAG
- Java Lambda without SnapStart (10s cold starts)
- Memory < 512MB for Java (slow GC)
- Handler that does heavy init per invocation (move to static initializer for SnapStart benefit)
- Reserved concurrency = 0 (disables the function — usually a mistake)
- SQS visibility timeout < Lambda timeout (causes duplicate invocations)
- DDB Streams batch size 1 (high invocation cost)
- Missing tracing (invisible debugging)
- Hardcoded ARNs (use SAM Refs)
- Layers used for code that should be in the function package

REFERENCE DOCS
- `architecture.md` Section 2.10 (Notification), Section 3.10
- `build-plan.md` Phase 3, Step 7.5, 9.4, 11.3

USE CONTEXT7
Suggest "use context7 for AWS Lambda Java 25 + SnapStart latest docs" — Java SnapStart docs evolved through 2025.

EXAMPLES TO INCLUDE
- Complete SAM template with MSK event source
- Handler class with Powertools annotations
- buildspec for SAM deploy
- Local testing setup

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit.
```

---

## 16. observability-stack-conventions

```text
Create a Claude Code skill named `observability-stack-conventions`.

PURPOSE
Phase 12 builds the observability stack. Every service phase produces logs, metrics, and traces. Without conventions, dashboards have to handle 10 different shapes. The metric naming, tracing setup, and logging format must be uniform.

WHEN TO TRIGGER
- Editing `application*.yml` with `management.*`, `logging.*`, `otel.*` keys
- Editing files in `food-ordering-gitops/shared/observability/**`
- Editing Grafana dashboard JSON, PrometheusRule YAML
- User mentions Prometheus, Grafana, OpenTelemetry, X-Ray, "trace ID", structured logging, SLO, alert
- Editing PrometheusRule, ServiceMonitor, AnalysisTemplate

CONTENT TO INCLUDE

1. **Tech stack**:
   - Amazon Managed Prometheus (AMP) workspace per env
   - Amazon Managed Grafana (AMG) workspace shared
   - OpenTelemetry Java agent attached via `-javaagent:` JVM flag
   - OTel Collector deployed as DaemonSet on EKS
   - AWS X-Ray for traces (via OTel exporter)
   - CloudWatch Logs via Fluent Bit (sidecar or DaemonSet)
   - Prometheus agent (kube-prometheus-stack in agent mode) forwards via remote_write to AMP

2. **Three pillars and their boundaries**:
   - **Metrics**: aggregated numbers over time. Use for SLOs, alerting, dashboards. NOT for individual request inspection.
   - **Logs**: discrete events with full context. Use for forensics, debugging specific request failures.
   - **Traces**: distributed call graph for one request. Use for understanding latency, finding bottlenecks across services.
   - **Correlation**: every log line and metric event includes the active `traceId` so you can pivot between them.

3. **Metric naming conventions** (Prometheus best practices):
   - Format: `{namespace}_{noun}_{unit}_{type}`
   - Examples:
     - `http_server_requests_seconds` (histogram)
     - `kafka_consumer_lag_seconds` (gauge)
     - `outbox_unprocessed_count` (gauge)
     - `saga_state_transitions_total{from="PENDING", to="PAID"}` (counter)
     - `payment_charge_failures_total{reason="declined"}` (counter)
   - Labels: bounded cardinality (e.g., `state` is OK, `userId` is NOT)
   - Standard labels on every metric: `service`, `env`, `version` (set globally)

4. **Log format** (see `common-conventions.md` Section 7 for full schema):
   ```json
   {
     "timestamp": "2026-05-08T14:23:11.123Z",
     "level": "INFO",
     "logger": "com.acme.order.OrderService",
     "message": "Order created",
     "service": "order-service",
     "version": "1.4.2",
     "env": "production",
     "traceId": "1-65b2f8a1-1234567890abcdef",
     "spanId": "abcdef1234567890",
     "userId": "usr_abc123",
     "thread": "VirtualThread-42",
     "context": {
       "orderId": "ord_xyz789"
     }
   }
   ```
   Configured via `logback-spring.xml` in `common-observability`.

5. **Trace propagation**:
   - HTTP: W3C Trace Context (`traceparent`, `tracestate` headers) — auto by OTel agent
   - Kafka: `traceparent` header on records — by `KafkaTracingProducerInterceptor`
   - SQS: `traceparent` message attribute — by SDK interceptor in `common-observability`
   - gRPC: metadata — by `GrpcTracingInterceptor`
   - Manual code: `Span.current()` to read; `Tracer.spanBuilder()` to create children

6. **Sampling**:
   - Production: tail-based 1% baseline + 100% on errors
   - Staging: head-based 100%
   - Local: head-based 100%
   - OTel Collector configured with `tail_sampling` processor

7. **Per-service ServiceMonitor** (Prometheus scrape config):
   ```yaml
   apiVersion: monitoring.coreos.com/v1
   kind: ServiceMonitor
   metadata:
     name: order-service
     labels:
       prometheus: kube-prometheus
   spec:
     selector:
       matchLabels:
         app: order-service
     endpoints:
       - port: actuator
         path: /actuator/prometheus
         interval: 30s
   ```

8. **Standard dashboard structure** (per service):
   - **Row 1**: RED metrics — Rate, Errors, Duration (p50/p95/p99)
   - **Row 2**: JVM — heap usage, GC time, thread count
   - **Row 3**: Dependencies — DB pool, Kafka consumer lag, circuit breaker state
   - **Row 4**: Service-specific business metrics (saga state distribution, payment success rate, etc.)
   - **Row 5**: Errors panel — recent error log entries with traceId links

9. **SLO-based alerting** (multi-window burn rate):
   ```yaml
   apiVersion: monitoring.coreos.com/v1
   kind: PrometheusRule
   metadata:
     name: order-service-slo
   spec:
     groups:
       - name: order.success.rate
         interval: 30s
         rules:
           - alert: OrderSuccessRateBurn
             expr: |
               (
                 1 - (sum(rate(orders_completed_total{state="DELIVERED"}[5m]))
                      / sum(rate(orders_completed_total[5m])))
               ) > (1 - 0.995) * 14.4         # 2% budget burn in 1h
             for: 2m
             labels:
               severity: page
               slo: order-success-rate
             annotations:
               summary: Order success rate burning fast
               runbook: https://wiki.{org}.com/runbooks/order-success-rate
   ```

10. **AlertManager routing**:
    - `severity=page` → PagerDuty (SEV1)
    - `severity=warn` → Slack (SEV2)
    - `severity=info` → Email digest

11. **Custom business metrics** (Micrometer):
    ```java
    @Component
    public class OrderMetrics {
      private final Counter ordersCompleted;
      private final Counter sagaCompensations;

      public OrderMetrics(MeterRegistry registry) {
        this.ordersCompleted = Counter.builder("orders_completed_total")
            .description("Number of orders that reached a terminal state")
            .tag("state", "")  // overridden per increment
            .register(registry);
      }
    }
    ```

12. **Runbook linkage**:
    - Every alert MUST include a `runbook` annotation pointing to a real document
    - Runbooks live in `food-ordering-gitops/shared/observability/runbooks/`

ANTI-PATTERNS TO FLAG
- Metric names that change between releases (breaks dashboards)
- High-cardinality labels (userId, requestId)
- Missing standard labels (service, env, version)
- Logging traces instead of using OTel
- `System.out.println` (bypasses structured logger)
- Trace headers ignored on async boundaries (broken trace tree)
- Threshold-based alerts instead of SLO burn-rate
- Missing runbook link on alerts
- Logging PII at INFO

REFERENCE DOCS
- `architecture.md` Section 9.6 (Observability practices)
- `common-conventions.md` Section 7 (Logging), Section 14 (Resilience metrics)
- `build-plan.md` Phase 12 entire

USE CONTEXT7
Suggest "use context7 for OpenTelemetry Java SDK docs" and "for Prometheus best-practices docs".

EXAMPLES TO INCLUDE
- Complete logback-spring.xml
- Complete OTel agent JVM args
- Complete ServiceMonitor + PrometheusRule
- Complete Grafana dashboard JSON skeleton
- Multi-window burn-rate alert example

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit.
```

---

## 17. test-conventions

```text
Create a Claude Code skill named `test-conventions`.

PURPOSE
Tests are mentioned in every build step's acceptance criteria. Superpowers enforces TDD globally; this skill enforces our specific test conventions (Testcontainers, AssertJ, naming, layering).

WHEN TO TRIGGER
- Editing files matching `**/src/test/**/*.java` or `**/*Test.java`, `**/*IT.java`
- User mentions test, JUnit, AssertJ, Mockito, Testcontainers, WireMock, "TDD", coverage
- Editing `pom.xml` test-related sections

CONTENT TO INCLUDE

1. **Tech stack**:
   - JUnit 5 (Jupiter) as runner
   - AssertJ for assertions (NOT JUnit `assertEquals`/`assertTrue`)
   - Mockito 5+ for mocking (NOT PowerMock)
   - Testcontainers for integration tests
   - WireMock for HTTP mocking
   - Awaitility for async assertions
   - JaCoCo for coverage (80% gate)

2. **Test layering**:
   | Layer | What's tested | Config | Naming |
   |---|---|---|---|
   | Unit | Single class with all deps mocked | None | `MyClassTest` |
   | Slice | One Spring layer (`@WebMvcTest`, `@DataJdbcTest`) | Spring slice annotation | `MyClassSliceTest` |
   | Integration | Multiple components + real infra (Testcontainers) | `@SpringBootTest` | `MyClassIT` |
   | Contract | API contracts | Pact | `MyClassContractTest` |
   | E2E | Multi-service flow against deployed env | k6 / Postman | (separate folder) |

3. **Test method naming** (Should/When/Given pattern):
   ```java
   @Test
   void should_return409_when_idempotencyKey_isReusedWithDifferentBody() { ... }

   @Test
   void givenPendingOrder_whenPaymentSucceeds_thenStateAdvancesToPaid() { ... }
   ```

4. **AssertJ patterns**:
   ```java
   // Single value
   assertThat(order.getState()).isEqualTo(OrderState.PAID);

   // Collection
   assertThat(order.getItems())
       .hasSize(3)
       .extracting(OrderItem::getName)
       .containsExactly("Pizza", "Salad", "Drink");

   // Custom condition
   assertThat(response).satisfies(r -> {
       assertThat(r.getStatus()).isEqualTo(201);
       assertThat(r.getOrderId()).startsWith("ord_");
   });

   // Exception
   assertThatThrownBy(() -> service.charge(invalidRequest))
       .isInstanceOf(PaymentDeclinedException.class)
       .hasMessageContaining("declined")
       .satisfies(e -> {
           assertThat(((PaymentDeclinedException)e).errorCode())
               .isEqualTo(ErrorCode.PAYMENT_DECLINED);
       });
   ```

5. **Mockito patterns**:
   ```java
   @ExtendWith(MockitoExtension.class)
   class OrderServiceTest {
     @Mock OrderRepository repo;
     @Mock OutboxRepository outbox;
     @Mock Clock clock;
     @InjectMocks OrderService service;

     @Test
     void test() {
       when(repo.findByIdForUpdate("ord_1")).thenReturn(Optional.of(order));
       when(clock.instant()).thenReturn(Instant.parse("2026-05-08T14:00:00Z"));
       service.markPaid("ord_1");
       verify(repo).save(argThat(o -> o.getState() == OrderState.PAID));
       verify(outbox).save(any(OutboxEvent.class));
     }
   }
   ```

6. **Testcontainers reuse** (across test classes):
   ```java
   @Testcontainers(disabledWithoutDocker = true)
   public abstract class IntegrationTestBase {
     @Container
     static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
         .withDatabaseName("test").withUsername("test").withPassword("test");

     @Container
     static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6"));

     @DynamicPropertySource
     static void props(DynamicPropertyRegistry r) {
       r.add("spring.datasource.url", postgres::getJdbcUrl);
       r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
     }
   }
   ```

7. **Awaitility for async**:
   ```java
   await().atMost(5, TimeUnit.SECONDS)
       .pollInterval(100, TimeUnit.MILLISECONDS)
       .untilAsserted(() -> {
           assertThat(outboxRepository.findUnprocessed()).isEmpty();
       });
   ```

8. **WireMock for HTTP mocking**:
   ```java
   @Test
   void should_retryThreeTimes_when_stripeReturns503() {
     stubFor(post("/charges")
         .inScenario("retry")
         .whenScenarioStateIs(STARTED)
         .willReturn(serviceUnavailable())
         .willSetStateTo("retry-1"));
     // ... continue scenario stubs
     paymentService.charge(request);
     verify(exactly(3), postRequestedFor(urlEqualTo("/charges")));
   }
   ```

9. **Test data builders** (avoid ad-hoc construction):
   ```java
   public class OrderTestBuilder {
     private String customerId = "usr_test";
     private OrderState state = OrderState.PENDING;
     // ... fluent setters
     public Order build() { ... }
     public static OrderTestBuilder anOrder() { return new OrderTestBuilder(); }
   }
   ```

10. **Required tests for saga transitions** (see `saga-state-machine` skill):
    - happy: state advances correctly
    - idempotent: same event twice = no-op
    - out-of-order: future state's event is silently dropped

11. **Required tests for write endpoints**:
    - happy 200/201
    - validation 400
    - auth 401
    - authorization 403
    - idempotency: same key + same body returns same response
    - idempotency: same key + different body returns 409
    - missing idempotency key returns 400

12. **Coverage**:
    - 80% line coverage minimum (gate in CI)
    - 100% on saga handlers (Order Service)
    - 100% on idempotency-key paths
    - Coverage diff check: PR can't lower coverage by more than 1%

ANTI-PATTERNS TO FLAG
- JUnit assertEquals/assertTrue (use AssertJ)
- PowerMock anywhere (refactor instead)
- Tests that depend on test order
- Tests that share mutable state via static fields
- `Thread.sleep()` (use Awaitility)
- `@Disabled` tests committed to main
- Snapshot tests for things that aren't actually stable
- Mocking the SUT itself (you're testing the wrong thing)
- Mocking value types (DTOs, entities) — use real instances
- Coverage-driven testing (writing tests just to hit lines)
- 95%+ coverage on getters/setters at the cost of missing edge cases

REFERENCE DOCS
- `common-conventions.md` Section 22 (Testing Conventions)
- `build-plan.md` various test acceptance criteria

USE CONTEXT7
Suggest "use context7 for AssertJ 4.x docs" and "for Mockito 5 docs" — APIs evolved.

EXAMPLES TO INCLUDE
- Complete unit test with Mockito
- Complete integration test with Testcontainers
- Complete async test with Awaitility
- WireMock retry-scenario test

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit access for `**/src/test/**`.
```

---

## 18. event-schema-evolution

```text
Create a Claude Code skill named `event-schema-evolution`.

PURPOSE
Six services produce events. As features change, event schemas change. A breaking schema change deployed before consumers are updated is a production incident. This skill encodes the evolution rules.

WHEN TO TRIGGER
- Editing `*.avsc` files
- Editing `EventEnvelope.java` or any class in `common-events`
- User mentions Avro, schema, "schemaVersion", "Glue Schema Registry", "BACKWARD compatibility", schema evolution
- Editing protobuf event definitions

CONTENT TO INCLUDE

1. **Schema registry**: AWS Glue Schema Registry, BACKWARD compatibility mode (consumers can read older messages with newer schema).

2. **Allowed changes** (no `schemaVersion` bump needed — Avro handles compatibly):
   - Add a new field with a default value
   - Add a new optional field
   - Remove an optional field that had a default value
   - Add a new enum value at the END of the enum list (Avro)
   - Add a new field to a nested record

3. **Disallowed changes** (require new `schemaVersion`):
   - Remove a required field
   - Change a field's type (string → int, etc.)
   - Rename a field (treat as remove + add across two releases)
   - Change the meaning of a field (semantic change without shape change)
   - Change a field's default value
   - Reorder enum values
   - Rename a record

4. **The renaming pattern** (the most common painful case):
   - Release 1: add the new field, dual-write both old and new
   - Release 2 (after all consumers updated): stop reading old field, mark deprecated
   - Release 3 (after retention window for old messages): remove old field

5. **`schemaVersion` rules**:
   - Bump on disallowed change (or semantic change)
   - Don't bump on additive changes — Avro handles those
   - Both versions live in topic at retention boundary; consumers must handle both
   - Consumer code that handles multiple versions:
     ```java
     switch (envelope.schemaVersion()) {
       case 1 -> handleV1(envelope.payload());
       case 2 -> handleV2(envelope.payload());
       default -> throw new UnknownSchemaVersionException(envelope.schemaVersion());
     }
     ```

6. **Multi-version handling**:
   - Producer: always emits the latest schema version
   - Consumer: handles whatever versions are in flight
   - Old messages (within topic retention) may have older schemas
   - Topic retention determines how long you must support old versions

7. **Deprecation flow**:
   - Mark field deprecated in `.avsc` (`"doc": "DEPRECATED: removed in v2"`)
   - Add to deprecation tracker: `platform-shared-libs/common-events/DEPRECATIONS.md`
   - Wait at least 6 months
   - Verify no consumer is still reading
   - Bump schemaVersion + remove field

8. **Glue Schema Registry CLI** (used in pre-commit hook):
   ```bash
   aws glue check-schema-version-validity \
       --data-format AVRO \
       --schema-definition file://order-events-value.avsc \
       --metadata-key-value-pair Latest=true
   ```

9. **Avro best practices**:
   - Use named records (avoid anonymous), so types are stable
   - Use unions for nullable fields: `["null", "string"]` with default `null`
   - Prefer `long` over `int` for any timestamp or ID
   - Use logical types: `decimal` for money, `timestamp-millis` for instants
   - Document every field with `doc` attribute

10. **Example .avsc** (canonical):
    ```json
    {
      "type": "record",
      "name": "OrderPaid",
      "namespace": "com.{org}.platform.events.order.v1",
      "doc": "Emitted when an order's payment is captured",
      "fields": [
        {"name": "orderId", "type": "string", "doc": "ord_<ulid>"},
        {"name": "customerId", "type": "string"},
        {"name": "restaurantId", "type": "string"},
        {"name": "amount", "type": {"type": "bytes", "logicalType": "decimal", "precision": 12, "scale": 2}},
        {"name": "currency", "type": "string", "doc": "ISO 4217"},
        {"name": "paidAt", "type": {"type": "long", "logicalType": "timestamp-millis"}},
        {"name": "metadata", "type": ["null", {"type": "map", "values": "string"}], "default": null}
      ]
    }
    ```

11. **Java record-to-Avro mapping** (via avro-maven-plugin):
    - Generated classes go to `target/generated-sources/avro/`
    - Don't commit generated classes; regenerate on build
    - Use the generic `GenericRecord` for forward-compatible consumers; specific record for type safety

12. **Producer-side validation**:
    - Producer's serializer (Glue Schema Registry serde) auto-validates against registered schema
    - Schema mismatch fails fast at serialization

13. **Consumer-side handling**:
    - Use `GenericRecord` if you want to handle multiple versions in one code path
    - Use specific record types if you only support one version at a time
    - Always check `eventType` header before deserializing payload

14. **Topic retention vs schema retention**:
    - Topic retention 7d → support schema versions up to 7 days old
    - Topic retention 30d → support schema versions up to 30 days old
    - Set retention based on business needs, then plan deprecation cycle accordingly

ANTI-PATTERNS TO FLAG
- Removing a required field without bumping schemaVersion
- Renaming a field directly (use rename pattern)
- Reordering enum values
- Default values changing in incompatible ways (e.g., `null` → `""`)
- Adding required field (no default)
- Skipping registry registration (using raw JSON serialization)
- Producer that emits a not-yet-registered schema
- Consumer that doesn't handle the previous schema version during transition
- Removing a field in the same PR that introduces it (use the deprecation flow)

REFERENCE DOCS
- `architecture.md` Section 8 (events)
- `common-conventions.md` Section 8 (Event Envelope), Section 17 (Schema Versioning)
- `build-plan.md` Step 1.2 (common-events)

USE CONTEXT7
Suggest "use context7 for Avro 1.12 docs" and "for AWS Glue Schema Registry latest docs".

EXAMPLES TO INCLUDE
- Complete .avsc with all required attributes
- The rename pattern across three releases
- Consumer code handling two schema versions
- Glue Schema Registry compatibility check command

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit access for `*.avsc`, `common-events/**`.
```

---

## 19. commit-and-pr-conventions

```text
Create a Claude Code skill named `commit-and-pr-conventions`.

PURPOSE
Standardizes commits, branches, and PRs so that monorepo CI can route changes to the right pipelines (path filter), so that automated tooling (changelogs, semantic-release if used) works, and so that PR review is uniform across 85 build steps.

WHEN TO TRIGGER
- User about to commit
- User asks for commit message
- User asks for PR description
- User mentions Conventional Commits, branch name, "PR template", squash merge

CONTENT TO INCLUDE

1. **Conventional Commits format**:
   ```
   {type}({scope}): {subject}

   [body]

   [footer]
   ```
   Subject line is imperative present tense, max 72 chars, no trailing period.

2. **Type vocabulary**:
   - `feat`: new feature (user-visible)
   - `fix`: bug fix (user-visible)
   - `refactor`: code restructuring without behavior change
   - `perf`: performance improvement
   - `test`: test additions/changes only
   - `docs`: documentation only
   - `build`: build system changes (Maven, Docker, BOM)
   - `ci`: CI/CD pipeline changes (CodeBuild, CodePipeline, Terraform for CI)
   - `chore`: routine maintenance, dependency bumps
   - `revert`: revert a previous commit

3. **Scope vocabulary** (for our monorepo):
   - `{service-name}`: e.g., `order-service`, `payment-service`
   - `shared-libs`: any change in `platform-shared-libs/`
   - `bom`: changes to `platform-bom/`
   - `infra`: `platform-infra/` (Terraform)
   - `gitops`: `food-ordering-gitops/`
   - `e2e`: end-to-end tests

4. **Branch naming**:
   - `feature/{step-id}-{slug}`: e.g., `feature/8.4-payment-success-handler`
   - `fix/{ticket-or-description}`: e.g., `fix/saga-timeout-deadlock`
   - `chore/{description}`: e.g., `chore/bump-spring-boot-405`
   - `revert/{commit-sha-prefix}`: e.g., `revert/abc1234-restore-old-impl`

5. **PR title**: same format as commit subject (becomes squash-commit message on merge).

6. **PR description template**:
   ```markdown
   ## Build Step
   Implements **Step X.Y** from `build-plan.md`.

   ## What changed
   - {bullet 1}
   - {bullet 2}

   ## Acceptance criteria
   - [x] {criterion from build step}
   - [x] {criterion from build step}

   ## Testing
   - {how this was tested — unit, IT, manual}

   ## Schema/migration changes
   - [ ] No schema/migration changes
   - [ ] Schema migration included; backwards-compatible per `postgresql-flyway-migrations` skill
   - [ ] Avro schema change; compatibility verified per `event-schema-evolution` skill

   ## Risk
   {Low | Medium | High} — {brief justification}

   ## Rollback plan
   {how to roll back if this breaks production}

   ## Checklist
   - [ ] Tests pass locally (`mvn -B verify`)
   - [ ] Coverage ≥ 80%
   - [ ] No new CVEs (Dependency-Check passes)
   - [ ] Conventions adhered to (`common-conventions.md`)
   - [ ] PR title is Conventional Commits format
   ```

7. **Commit body when needed**:
   ```
   feat(order-service): add saga timeout enforcer (step 8.8)

   Implements the SagaTimeoutEnforcer that runs every 30s and
   triggers compensation for orders stuck > 5min in non-terminal state.

   Uses PostgreSQL advisory locks to prevent concurrent execution
   across multiple pod instances.

   Closes #142
   ```

8. **Footer references**:
   - `Closes #N` — closes a GitHub/CodeCommit issue
   - `Refs #N` — references without closing
   - `BREAKING CHANGE: {description}` — must be in footer for breaking changes (drives semver if using semantic-release)
   - `Co-authored-by: Name <email>` for pair work

9. **Merge strategy**:
   - **Squash and merge** for feature branches
   - Linear history on `main`
   - No merge commits
   - The squash commit message = PR title + PR description body (cleaned)

10. **Tags and releases** (per service):
    - Tag format: `{service-name}-v{X.Y.Z}` (e.g., `order-service-v1.4.2`)
    - Tags created on `main` only
    - GitHub release notes auto-generated from Conventional Commits since previous tag

11. **Auto-generated changelog**:
    - Per service: `services/{name}/CHANGELOG.md`
    - Generated by `git-cliff` or `semantic-release` from Conventional Commits
    - Updated on each tag

12. **PR review etiquette**:
    - Review within 24h of opening
    - Pre-merge: at least one approval, all CI green
    - Post-merge: confirm staging deploy succeeded before starting next step
    - Architecture changes: require platform team approval (CODEOWNERS)

ANTI-PATTERNS TO FLAG
- Subject line > 72 chars
- Past-tense subject ("Added X" instead of "Add X")
- Missing scope on PRs that touch only one service
- Commit titled `WIP` or `fix typo` reaching `main` (squash should fix this)
- Branch name without step ID for build-plan-driven work
- Skipping `BREAKING CHANGE:` footer when introducing a breaking API change
- Bundling code change + migration in same PR
- Force-pushing to shared branches
- Merge commits on `main`

REFERENCE DOCS
- `common-conventions.md` Section 23 (Commit & PR Conventions)
- `build-plan.md` for step IDs

EXAMPLES TO INCLUDE
- Three full commit messages (feat, fix, refactor)
- A complete PR description filled in for a hypothetical step
- A breaking-change commit with footer

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: read-only (commit and PR drafting helpers).
```

---

## 20. local-development-setup

```text
Create a Claude Code skill named `local-development-setup`.

PURPOSE
Lower the friction for local development. Without conventions, every developer's local setup differs (different docker-compose, different ports, different seed data) and "works on my machine" creeps in.

WHEN TO TRIGGER
- User mentions "run locally", "local dev", "docker-compose", "LocalStack", "spring-boot:run", "dev seed"
- Editing files matching `docker-compose*.yml`, `dev/**`, `scripts/dev*.sh`
- Editing `application-local.yml`

CONTENT TO INCLUDE

1. **Goal**: every service runnable locally with one command (`scripts/dev/run.sh {service}`), against local docker-compose dependencies. No AWS account needed for development.

2. **docker-compose.yml** (root level, shared across all services):
   ```yaml
   version: '3.9'
   services:
     postgres:
       image: postgres:16-alpine
       ports: [5432:5432]
       environment:
         POSTGRES_USER: dev
         POSTGRES_PASSWORD: dev
       volumes:
         - postgres-data:/var/lib/postgresql/data
         - ./dev/postgres-init:/docker-entrypoint-initdb.d
     redis:
       image: redis:7-alpine
       ports: [6379:6379]
       command: redis-server --requirepass dev
     kafka:
       image: confluentinc/cp-kafka:7.6.0
       ports: [9092:9092]
       environment:
         KAFKA_NODE_ID: 1
         KAFKA_PROCESS_ROLES: broker,controller
         # ... full single-node config
     localstack:
       image: localstack/localstack:latest
       ports: [4566:4566]
       environment:
         SERVICES: dynamodb,sqs,sns,s3,secretsmanager,kms
         AWS_DEFAULT_REGION: us-east-1
   volumes:
     postgres-data:
   ```

3. **Per-service `application-local.yml`**:
   ```yaml
   spring:
     datasource:
       url: jdbc:postgresql://localhost:5432/order_db
       username: dev
       password: dev
     kafka:
       bootstrap-servers: localhost:9092
       properties:
         security.protocol: PLAINTEXT       # local only — no IAM
         sasl.mechanism: ""
     data:
       redis:
         host: localhost
         port: 6379
         password: dev
         ssl:
           enabled: false                   # local only

   aws:
     endpoint-override: http://localhost:4566   # LocalStack
     region: us-east-1
     credentials:
       access-key-id: dev
       secret-access-key: dev
   ```

4. **Run scripts** (`scripts/dev/run.sh`):
   ```bash
   #!/usr/bin/env bash
   SERVICE=$1
   docker-compose up -d
   ./scripts/dev/wait-for-deps.sh
   ./scripts/dev/seed.sh
   cd services/$SERVICE
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```

5. **Seed data** (`dev/seed/`):
   - SQL files for PG: `dev/seed/postgres/01-users.sql`, `02-restaurants.sql`
   - JSON for DDB via aws-cli script: `dev/seed/dynamodb/load.sh`
   - Avro schemas registered to LocalStack-hosted Schema Registry mock if used

6. **wait-for-deps.sh**:
   ```bash
   ./wait-for-it.sh localhost:5432 -t 30
   ./wait-for-it.sh localhost:6379 -t 30
   ./wait-for-it.sh localhost:9092 -t 30
   curl -sf http://localhost:4566/_localstack/health | jq -e '.services.dynamodb=="available"'
   ```

7. **Test users** (seeded automatically):
   - Customer: `alice@dev.local` / `password123` → role CUSTOMER
   - Restaurant owner: `bob@dev.local` / `password123` → role RESTAURANT_OWNER, restaurantId `rest_dev1`
   - Driver: `carol@dev.local` / `password123` → role DRIVER, driverId `drv_dev1`
   - Admin: `admin@dev.local` / `password123` → role ADMIN

8. **Seeded JWT** (for testing without going through login):
   - `dev/keys/dev-private.pem` — RS256 private key (committed; LOCAL ONLY)
   - `scripts/dev/issue-jwt.sh {role}` — issues a token for the given role

9. **API testing**:
   - Postman collection at `dev/postman/food-ordering.postman_collection.json`
   - HTTPie scripts at `dev/scripts/api/`
   - Optional: `Insomnia` collection at `dev/insomnia/`

10. **Hot reload**:
    - Spring Boot DevTools auto-restart on classpath changes
    - Activated via `local` profile (NEVER in staging/production)

11. **Faster startup**:
    - `mvnDaemon` for incremental builds
    - Avoid SnapStart locally (irrelevant for non-Lambda)
    - Pre-warm Maven repo via `scripts/dev/warm-cache.sh`

12. **Docker resources**:
    - Recommended Docker Desktop allocation: 8GB RAM, 4 CPUs
    - Without that, Kafka+Postgres+Redis+LocalStack is sluggish

13. **Cleanup**:
    - `scripts/dev/clean.sh` — `docker-compose down -v` + `mvn clean` for the workspace

14. **IDE setup notes** (IntelliJ-specific):
    - Lombok plugin installed (if used)
    - Annotation processing enabled
    - Run/Debug config: Spring Boot main class, profile = `local`, env vars from `application-local.yml`

ANTI-PATTERNS TO FLAG
- Real AWS credentials in any local config file
- Real Stripe keys in local config (use Stripe test mode keys)
- `docker-compose up` without `wait-for-deps.sh` (race conditions)
- Hardcoded `localhost` in service code (should be config)
- Production data dumps used for seed (PII risk)
- Different docker-compose files per service (duplication)
- Missing `dev/seed` for a new service (impossible to test locally)

REFERENCE DOCS
- `common-conventions.md` Section 20 (Spring Boot config)
- `build-plan.md` Step 0.1 (developer prerequisites)

EXAMPLES TO INCLUDE
- Complete root `docker-compose.yml`
- Complete `application-local.yml` for one service
- Complete `run.sh`, `wait-for-deps.sh`, `seed.sh`
- JWT issuance script

OUTPUT FORMAT
Standard SKILL.md. Allowed tools: full edit for `dev/**`, `scripts/dev/**`, `application-local.yml` files.
```

---

# Usage Notes

## How to invoke skill-creator

For each prompt above, in Claude Code:

1. Open a fresh session.
2. Invoke skill-creator: `/skill-creator` (or the equivalent slash command for your installation).
3. Paste the entire prompt block (everything between the triple-backticks).
4. Review the produced `SKILL.md` carefully — skill-creator may need follow-up clarification.
5. Commit to `~/.claude/skills/{skill-name}/SKILL.md` (user-level) or `.claude/skills/{skill-name}/` (project-level — recommended for these, since they're project-specific).

## Recommended order

Build in the priority order from the "Final consolidated stack proposal":
1. Tier 1 (5 skills) before Phase 0
2. Tier 2 (7 skills) before the matching phase begins
3. Tier 3 (8 skills) just-in-time as you reach those phases

## Pairing with Superpowers

These skills are *domain knowledge*. Superpowers handles *cross-cutting methodology* (TDD, debugging, brainstorming, code review). They coexist: when you start a build step with `/build-step`, Superpowers' dispatcher activates the methodology skills, and the relevant domain skills here auto-load based on context (file paths, prompt keywords).

If a skill below ever conflicts with a Superpowers skill, the domain skill (these) wins on domain-specific decisions; Superpowers wins on process. Document the resolution in the skill's "Anti-patterns" section if it comes up repeatedly.

---

*End of skill-prompts.md.*
