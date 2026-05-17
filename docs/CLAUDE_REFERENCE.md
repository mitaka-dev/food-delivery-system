# Claude Code Reference — Food Delivery System

> A complete map of everything in `.claude/` for this project: skills, hooks, agents, and settings.
> All paths are relative to the repo root.

---

## Overview

```
.claude/
├── settings.json          ← project-level Claude Code config (tracked in git)
├── settings.local.json    ← personal machine config (gitignored)
├── agents/
│   └── security-reviewer.md
├── hooks/
│   ├── pre-commit/
│   │   ├── enforce-conventions
│   │   ├── secret-scanner
│   │   └── flyway-migration-safety
│   ├── pre-tool-use/
│   │   ├── protect-production
│   │   └── confirm-data-mutation
│   └── session-start/
│       └── plan-briefing
└── skills/
    ├── commit/
    ├── db-query/
    ├── health/
    ├── kafka-inspect/
    ├── logs/
    ├── monorepo-maven-conventions/
    ├── outbox-pattern/
    ├── saga-test/
    ├── spring-boot-service-conventions/
    └── start/
```

---

## Skills

Skills are invoked with `/skill-name` in the Claude Code prompt. Claude also auto-suggests them
when the `description` frontmatter matches the context of what you're working on.

Each skill lives at `.claude/skills/{name}/SKILL.md`.

---

### `/start`

**File:** `.claude/skills/start/SKILL.md`
**Type:** Operational — `disable-model-invocation: true` (runs a fixed script, no AI reasoning needed)
**Allowed tools:** `Bash` (unrestricted)

Runs `./start.sh` with optional flags, then verifies all service health endpoints. Reports UP/DOWN status for each service and prints access URLs.

| Flag | Effect |
|---|---|
| *(none)* | Normal start — skips Maven build if JARs exist |
| `--rebuild` | Force Maven rebuild |
| `--clean` | Wipe Docker volumes (fresh database) |

**When to use:** "start the system", "boot up", "spin up the services", "run everything".

---

### `/health`

**File:** `.claude/skills/health/SKILL.md`
**Type:** Operational — non-destructive read
**Allowed tools:** `Bash(curl *)`, `Bash(docker *)`

Curls `/actuator/health` on all 6 running services (ports 8081–8085 + Grafana at 3000) and
reports UP/DOWN status. Does NOT start anything. Shows last 20 log lines for any DOWN service.

**When to use:** "are services running?", "is everything healthy?", "what's the system status?".

---

### `/logs [service] [lines]`

**File:** `.claude/skills/logs/SKILL.md`
**Type:** Operational — streaming read
**Allowed tools:** `Bash(docker *)`

Tails Docker Compose logs for a named service. Accepts a service name as argument (e.g.,
`/logs order-service`) and an optional line count. If no service is given, lists all available
service names.

**When to use:** "show me what order-service is doing", "tail payment logs", "watch kafka logs",
"what's happening in user-service".

---

### `/db-query <db> <sql>`

**File:** `.claude/skills/db-query/SKILL.md`
**Type:** Operational — `disable-model-invocation: true`
**Allowed tools:** `Bash(docker *)`

Runs a SQL query inside the `postgres-db` container. Maps shortnames to databases:

| Argument | Database | Key tables |
|---|---|---|
| `user` | `user_db` | `users` (id, username, status) |
| `order` | `order_db` | `orders` (id, status, items, user_id) |
| `payment` | `payment_db` | `payments` (id, order_id, status, amount) |
| `product` | `product_db` | `products` (id, name, price, stock, version) |

**When to use:** checking Saga state, debugging stuck orders, inspecting payment records,
verifying user activation, checking stock levels.

---

### `/kafka-inspect [group|all]`

**File:** `.claude/skills/kafka-inspect/SKILL.md`
**Type:** Operational — `disable-model-invocation: true`
**Allowed tools:** `Bash(docker *)`

Runs `kafka-consumer-groups --describe` inside the `kafka` container for one or all consumer groups. Interprets LAG and CONSUMER-ID columns and summarizes which groups are healthy vs. lagging.

| Group | Service | Topic |
|---|---|---|
| `order-group` | order-service | order-confirmation-topic |
| `product-group` | product-service | order-topics, payment-topics |
| `payment-group` | payment-service | order-topics |
| `user-group` | user-service | user-confirmation-topic |
| `analytics-group` | analytics-service | user-topics |

**When to use:** Saga flows are stuck, orders stay PENDING, want to verify consumer health.

---

### `/saga-test`

**File:** `.claude/skills/saga-test/SKILL.md`
**Type:** Operational — `disable-model-invocation: true`
**Allowed tools:** `Bash(curl *)`

Runs a full end-to-end test of both Saga flows against the live system:
1. Registers a timestamped test user
2. Waits for analytics-service activation Saga
3. Logs in and extracts a JWT access token
4. Creates a **happy-path** order (`totalAmount=49.99`) → expects final status `PAID`
5. Creates a **failure-path** order (`totalAmount=999.99`) → expects final status `FAILED`
6. Reports a PASS/FAIL table for all steps

> Note: `amount > 500 → FAILED` is intentional test behavior in payment-service, not a bug.
> Note: The skill still references the gateway on port 8080 — update if port changes.

**When to use:** verifying the Saga works end-to-end after a change, smoke testing after startup.

---

### `/commit`

**File:** `.claude/skills/commit/SKILL.md`
**Type:** Workflow — `disable-model-invocation: true`
**Allowed tools:** `Bash(git *)`

Stages all non-sensitive files (`git add .`), removes `.env` / `*.secret` / `*.key` from staging,
generates a [Conventional Commits](https://www.conventionalcommits.org/) message
(`feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`), and commits. **Does not push.**

Never stages: `.env`, `*.secret`, `*.key`, credential files.

**When to use:** "commit my changes", "save this", "checkpoint the work".

---

### `/spring-boot-service-conventions`

**File:** `.claude/skills/spring-boot-service-conventions/SKILL.md`
**Type:** Reference — convention guide
**Allowed tools:** `Read`, `Edit`, `Write`, `Bash(./mvnw *)`

Encodes how every Spring Boot service in this monorepo is structured:
- Package layout under `food.ordering.system.{service}.service/`
- Controller conventions (`/api/v1/` prefix, records for DTOs, `ResponseEntity<T>`, `@Valid`)
- Service layer (`@Transactional`, constructor injection)
- Entity patterns (no Lombok `@Data`, `@Version` for optimistic locking)
- `application.yaml` template
- `GlobalExceptionHandler` copy-paste pattern
- Anti-patterns table (field injection, missing `@Valid`, inline topic strings, etc.)

**When to use:** creating or editing any controller, service, entity, listener, or `application.yaml`.
Auto-suggested when editing `*.java` files or service `pom.xml`.

---

### `/monorepo-maven-conventions`

**File:** `.claude/skills/monorepo-maven-conventions/SKILL.md`
**Type:** Reference — convention guide
**Allowed tools:** `Read`, `Edit`, `Write`

Documents the BOM-driven dependency management rules:
- BOM owns all versions — service POMs never declare `<version>` in `<dependency>`
- All 12 modules, their ports, and primary data stores
- How to add a new dependency (two-step: service POM + root `<dependencyManagement>`)
- Which versions are explicitly managed (springdoc, loki4j, AWS SDK BOM)

**When to use:** adding dependencies, creating a new module, editing any `pom.xml`.

---

### `/aws-sdk-v2-conventions`

**File:** `.claude/skills/aws-sdk-v2-conventions/SKILL.md`
**Type:** Reference — convention guide
**Allowed tools:** `Read`, `Edit`, `Write`

Patterns for AWS SDK v2 DynamoDB usage in `kitchen-service` and `review-service`:
- `DynamoDbConfig.java` bean setup with LocalStack override via empty `${aws.dynamodb.endpoint:}`
- `@DynamoDbBean` JavaBean requirements (mutable, no-arg constructor, `@DynamoDbPartitionKey` on getter)
- `TableSchema.fromBean(...)` initialization
- Scan with filter expressions — always use `#attr` aliases to avoid DynamoDB reserved word collisions
- `SdkIterable` → `Stream` via `StreamSupport.stream(iterable.spliterator(), false)`

**When to use:** working on `kitchen-service`, `review-service`, or any service importing `software.amazon.awssdk`.

---

### `/outbox-pattern`

**File:** `.claude/skills/outbox-pattern/SKILL.md`
**Type:** Reference — architecture guide
**Allowed tools:** `Read`, `Edit`, `Write`

Documents reliable Kafka event publishing:
- Why the outbox exists (dual-write problem)
- Current pattern in this codebase (direct `kafka.send()` inside `@Transactional` — best-effort)
- Full outbox table schema + polling publisher pattern (production target)
- Current Saga flow map: order-service → payment-service → order-service compensation

**When to use:** implementing Kafka event publishing, new Saga steps, or any `@Transactional` method that also sends a message.

---

## Hooks

Hooks are scripts that run automatically on specific events. This project has two categories:
**Git pre-commit hooks** (block bad commits) and **Claude Code pre-tool-use hooks** (guard tool calls inside sessions).

---

### Git Pre-commit Hooks

These run on every `git commit`. They are chained by `.git/hooks/pre-commit` (auto-installed by `./start.sh` on first run).

> The wrapper at `.git/hooks/pre-commit` is **not tracked in git** (`.git/` is excluded).
> `./start.sh` reinstalls it automatically — so any new developer who runs `start.sh` gets the hooks for free.

#### `enforce-conventions`

**File:** `.claude/hooks/pre-commit/enforce-conventions`
**Language:** bash
**Runs on:** every commit, all staged files

| Check | Scope | Action on match |
|---|---|---|
| `image: *:latest` in Docker/YAML | `*.yaml`, `*.yml` (except `*local*`) | Block |
| AWS access key pattern `AKIA[A-Z0-9]{16}` | All staged files | Block |
| Stripe live key `sk_live_[A-Za-z0-9]{24,}` | All staged files | Block |
| `<version>` inside `<dependency>` in service POMs | `*-service/pom.xml` | Block |
| Spring Boot 3.x reference | `*.xml`, `*.yml`, `*.yaml` | Block |

Reports the file and matching line for every violation. Exit 1 if any fail.

#### `secret-scanner`

**File:** `.claude/hooks/pre-commit/secret-scanner`
**Language:** bash
**Depends on:** `gitleaks` (fail-open with a warning if not installed)

Runs `gitleaks protect --staged --no-banner --redact` against staged content only.
Allowlist in `.gitleaks.toml` covers `src/test/`, `dev/seed/`, `dev/keys/`, `generate-secrets.sh`.

Install gitleaks: https://github.com/gitleaks/gitleaks

#### `flyway-migration-safety`

**File:** `.claude/hooks/pre-commit/flyway-migration-safety`
**Language:** bash
**Triggers on:** staged files matching `*-service/src/main/resources/db/migration/V*__*.sql`

| Rule | Override |
|---|---|
| Modified (not added) migration that exists in HEAD → immutable, always blocked | None |
| `DROP COLUMN`, `DROP TABLE`, `TRUNCATE TABLE`, `RENAME COLUMN` | `--allow-breaking` flag |
| `ALTER COLUMN … SET NOT NULL` without prior nullable add in earlier V* files | `--allow-breaking` flag |
| Version number gap (V2 after V4, etc.) | None |

---

### Claude Code Session-start Hook

Fires automatically when a Claude Code session begins. Outputs JSON with `hookSpecificOutput.additionalContext` to inject content into the model's context before the first message.

#### `plan-briefing`

**File:** `.claude/hooks/session-start/plan-briefing`
**Language:** bash + python3
**Trigger:** session start (automatic, no user action required)

Reads `docs/plan/STATUS.md` and injects its contents into Claude's context as `additionalContext`. This gives Claude an instant briefing on current project state and next step without reading the large plan files.

> Update `docs/plan/STATUS.md` at the end of every session to keep the briefing accurate.

---

### Claude Code Pre-tool-use Hooks

These fire inside Claude Code **before every Bash tool call**. Configured in `.claude/settings.json`.
Input is received as JSON via stdin; exit 0 = proceed, exit 1 = block.

#### `protect-production`

**File:** `.claude/hooks/pre-tool-use/protect-production`
**Language:** Python 3
**Trigger:** any Bash tool call matching dangerous AWS/infra patterns

Detected patterns: `terraform apply/destroy`, `kubectl --context *prod`, `aws.*delete.*`,
`aws s3 rm --recursive`, `aws rds delete-*`, `aws dynamodb delete-table`, `aws iam delete-*`,
`aws ssm send-command`, `eksctl * delete`, `argocd app delete`.

If matched **and** the active AWS account is in `PRODUCTION_ACCOUNT_IDS`:
- Prompts: type exactly `I confirm production` to proceed
- Blocks otherwise

> `PRODUCTION_ACCOUNT_IDS` is currently an empty set — populate it in the script when AWS accounts are provisioned. Until then, the hook passes through (fail-open).

Fail-open if `aws sts get-caller-identity` is unavailable.

#### `confirm-data-mutation`

**File:** `.claude/hooks/pre-tool-use/confirm-data-mutation`
**Language:** Python 3
**Trigger:** any Bash tool call containing data-destructive patterns

Detected patterns: `DROP TABLE`, `DROP DATABASE`, `TRUNCATE`, `DELETE FROM \w+;` (no WHERE),
`aws s3 rm --recursive`, `aws dynamodb delete-table`, `flyway clean`.

Prompts `Continue? [yes/N]`. Applies regardless of environment. Lower friction than
`protect-production` — typing `yes` is sufficient.

---

## Agent

Agents are autonomous sub-models invoked by Claude Code to handle a specific review or analysis task. This project has one agent.

### `security-reviewer`

**File:** `.claude/agents/security-reviewer.md`
**How to invoke:** `@security-reviewer` in the Claude Code prompt

A security audit agent scoped to this project's specific architecture. Reviews code changes for
vulnerabilities with checklist-driven focus on:

- **JWT validation** — `type == "access"` check, claim validation, secret never logged
- **Header injection** — can external clients forge `X-User-Name` / `X-User-Role`?
- **Redis session** — logout atomicity, refresh token revocation paths
- **Payment service** — Kafka-only surface, no accidental HTTP exposure
- **Rate limiting** — X-Forwarded-For spoofing on gateway routes
- **General** — parameterized queries, CORS, no secrets in logs

Reports findings as **Critical** / **High** / **Low/Informational** with file:line locations and concrete fix suggestions.

> **Note:** The agent's system prompt still references `gateway-service` and Spring Boot 3.5 — both are now removed/upgraded. The JWT checklist remains valid; the gateway section is outdated.

**When to use:** before merging changes to auth, JWT handling, Redis token storage, or payment-service logic.

---

## Settings

### `.claude/settings.json` — Project-level (tracked in git)

```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          { "type": "command", "command": "bash -c 'cd \"$(git rev-parse --show-toplevel)\" && bash .claude/hooks/session-start/plan-briefing'", "statusMessage": "Loading project status..." }
        ]
      }
    ],
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          { "type": "command", "command": "bash -c 'cd \"$(git rev-parse --show-toplevel)\" && python3 .claude/hooks/pre-tool-use/protect-production'" },
          { "type": "command", "command": "bash -c 'cd \"$(git rev-parse --show-toplevel)\" && python3 .claude/hooks/pre-tool-use/confirm-data-mutation'" }
        ]
      }
    ]
  }
}
```

Wires the session-start briefing hook and the two pre-tool-use hooks into Claude Code. Applies to every developer who opens this project.

### `.claude/settings.local.json` — Personal/machine-level (gitignored)

```json
{
  "permissions": {
    "allow": [
      "Bash(./mvnw compile *)",
      "Bash(./mvnw clean *)",
      "Bash(./mvnw test *)",
      "Bash(./mvnw dependency:tree -q)",
      "Bash(cd *)",
      "Skill(health)"
    ]
  },
  "enabledMcpjsonServers": [
    "postgres-order",
    "postgres-user",
    "postgres-payment",
    "postgres-product"
  ],
  "outputStyle": "Explanatory"
}
```

- **permissions.allow** — pre-approved Bash commands that don't prompt for confirmation each time
- **enabledMcpjsonServers** — four PostgreSQL MCP servers providing direct SQL access to the running databases (defined in `.mcp.json`)
- **outputStyle** — sets Claude Code's response style to `Explanatory` (educational mode with insights)

This file is personal and not committed — each developer maintains their own copy.

---

## Skill Quick-Reference

| Slash command | Category | One-liner |
|---|---|---|
| `/start [--rebuild\|--clean]` | Operational | Build + launch all services |
| `/health` | Operational | Check actuator health on all services |
| `/logs [service]` | Operational | Tail Docker logs for a service |
| `/db-query <db> <sql>` | Debug | Run SQL against any project database |
| `/kafka-inspect [group\|all]` | Debug | Check consumer group lag |
| `/saga-test` | Test | End-to-end Saga happy + failure path test |
| `/commit` | Workflow | Stage, message, and commit changes |
| `/spring-boot-service-conventions` | Reference | Package layout, controller/service/entity patterns |
| `/monorepo-maven-conventions` | Reference | BOM rules, how to add deps, module list |
| `/aws-sdk-v2-conventions` | Reference | DynamoDB Enhanced Client patterns |
| `/outbox-pattern` | Reference | Reliable Kafka publishing + Saga flow map |

---

## Hook Quick-Reference

| Hook | Type | Trigger | Effect |
|---|---|---|---|
| `plan-briefing` | Claude session-start | Session open | Injects `docs/plan/STATUS.md` into model context |
| `enforce-conventions` | Git pre-commit | Every commit | Blocks `latest` tags, AWS keys, Stripe keys, POM versions, Boot 3.x refs |
| `secret-scanner` | Git pre-commit | Every commit | Blocks secrets detected by gitleaks |
| `flyway-migration-safety` | Git pre-commit | Staged `V*__*.sql` files | Blocks immutable migration edits, dangerous DDL, version gaps |
| `protect-production` | Claude pre-tool-use | Every Bash call | Blocks dangerous AWS ops on production account |
| `confirm-data-mutation` | Claude pre-tool-use | Every Bash call | Prompts before DROP TABLE / TRUNCATE / DELETE without WHERE |
