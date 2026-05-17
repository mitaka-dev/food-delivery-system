# Hook Specifications — Food Ordering Platform

> **Purpose**: Comprehensive specifications for all Claude Code hooks across the build. Each hook section includes: when it fires, what it checks, how it implements the check, edge cases, exit codes, and example output.
>
> **How to use**: Read the spec, implement the script (bash/Python/Node), drop it into `.claude/hooks/` (or `~/.claude/hooks/` for user-level), and add it to `.claude/settings.json`. Use these specs as direct input to skill-creator's "create a hook" capability or implement manually.
>
> **Reference**: Hooks integrate with Claude Code via lifecycle events. Pre-commit hooks gate Git commits; pre-tool-use hooks gate Claude Code's tool calls; post-tool-use hooks run after tool calls succeed; session-start/end hooks fire at session boundaries.

---

## Index

**Pre-commit hooks (gate Git commits)**
1. [enforce-conventions](#1-enforce-conventions)
2. [validate-build-step-completion](#2-validate-build-step-completion)
3. [flyway-migration-safety](#3-flyway-migration-safety)
4. [terraform-fmt-check](#4-terraform-fmt-check)
5. [proto-breaking-change](#5-proto-breaking-change)
6. [outbox-event-schema-check](#6-outbox-event-schema-check)
7. [iam-policy-overbroad](#7-iam-policy-overbroad)
8. [secret-scanner](#8-secret-scanner)
9. [kafka-schema-compat-check](#9-kafka-schema-compat-check)
10. [dockerfile-best-practices](#10-dockerfile-best-practices)
11. [branch-naming-enforcement](#11-branch-naming-enforcement)

**Pre-tool-use hooks (gate Claude Code's tool calls)**
12. [protect-production](#12-protect-production)
13. [confirm-data-mutation](#13-confirm-data-mutation)
14. [block-cross-account-mistake](#14-block-cross-account-mistake)

**Post-tool-use hooks (run after tool calls succeed)**
15. [auto-format](#15-auto-format)
16. [regenerate-grpc-stubs](#16-regenerate-grpc-stubs)
17. [update-bom-on-dep-change](#17-update-bom-on-dep-change)
18. [cost-budget-warning](#18-cost-budget-warning)

**Session-start hooks**
19. [aws-context-snapshot](#19-aws-context-snapshot)
20. [current-step-detector](#20-current-step-detector)

**Session-end hooks**
21. [commit-progress-marker](#21-commit-progress-marker)

---

## Hook fundamentals

Before the specs, three quick principles that apply to every hook below.

**Exit codes are the contract.** Hooks signal pass/fail/warn through process exit codes:
- `0` = pass (Claude Code or Git proceeds)
- `1` = fail (block the action with an error message)
- `2` = warn (allow the action but surface a message; for hooks where this is supported)

Stdout/stderr is presented to the user; keep messages actionable.

**Hooks must be fast.** Pre-commit hooks run on every commit; if they take more than a couple of seconds, developers will start running `git commit --no-verify`. Aim for sub-second execution; defer expensive work (full builds, integration tests) to CI.

**Hooks must be deterministic.** Same inputs → same exit code. No flaky network calls without a timeout + fallback. If the network is required (e.g., schema-registry compatibility check), fail-open with a warning rather than fail-closed (otherwise developers can't commit on a flaky day).

---

# Pre-commit Hooks

## 1. enforce-conventions

### Purpose
Catches the silent mistakes that cost a week of debugging in production: hardcoded `latest` Docker tags, hardcoded secrets, version drift in service POMs, banned strings, etc. This is the workhorse hook.

### When it fires
Pre-commit, on every commit attempt.

### What it checks
The hook runs a battery of `grep`-based and AST-based checks against staged files. Each check is independent; if any fails, the commit is blocked.

| Check | Pattern / Rule | Files scanned |
|---|---|---|
| No `latest` tags in Docker | `image:.*:latest` (allow only in `*local*.yml`) | `*.yaml`, `*.yml`, `Dockerfile`, `Dockerfile.*` |
| No AWS access keys | `AKIA[A-Z0-9]{16}` | All staged files |
| No AWS secret keys | High-entropy strings + `aws_secret_access_key` proximity | All staged files |
| No Stripe live keys | `sk_live_[A-Za-z0-9]{24,}` | All staged files |
| No password literals | `password\s*=\s*["'][^"']{4,}["']` (excluded in test fixtures under `**/test/**`) | `*.java`, `*.yml`, `*.tf` |
| No service POM versions | `<version>` tags inside `<dependencies>` | `services/*/pom.xml` |
| No legacy Spring Boot | `Spring Boot 3` or `spring-boot.*3\.[0-9]+` | All staged files |
| No legacy Java | `java\.version.*21` or `corretto21` (this project locks to 25) | `*.xml`, `*.yml` |
| No `kubectl apply` in CI | `kubectl\s+apply` | `buildspec*.yml`, `*.sh` under `scripts/ci/` |
| No `latest` Maven dep | `<version>LATEST</version>` or `<version>RELEASE</version>` | `*.xml` |

### Implementation skeleton

```bash
#!/usr/bin/env bash
# .claude/hooks/pre-commit/enforce-conventions
set -euo pipefail

STAGED=$(git diff --cached --name-only --diff-filter=ACM)
[ -z "$STAGED" ] && exit 0

FAIL=0
report() {
  echo "❌ $1"
  echo "   File: $2"
  [ -n "${3:-}" ] && echo "   Match: $3"
  FAIL=1
}

for f in $STAGED; do
  [ -f "$f" ] || continue   # deleted

  # Docker latest tags (allow in *local* files)
  if [[ "$f" =~ \.(yaml|yml)$ ]] && [[ ! "$f" =~ local ]]; then
    if grep -nE 'image:.*:latest( |$)' "$f"; then
      report "Docker tag 'latest' is forbidden outside local files" "$f"
    fi
  fi

  # AWS access keys
  if grep -nE 'AKIA[A-Z0-9]{16}' "$f" > /dev/null; then
    MATCH=$(grep -nE 'AKIA[A-Z0-9]{16}' "$f" | head -1)
    report "AWS access key prefix detected" "$f" "$MATCH"
  fi

  # Stripe live keys
  if grep -nE 'sk_live_[A-Za-z0-9]{24,}' "$f" > /dev/null; then
    report "Stripe LIVE key detected (use sk_test_ for development)" "$f"
  fi

  # Service POM with version tags inside dependencies
  if [[ "$f" =~ services/[^/]+/pom\.xml$ ]]; then
    # Use xmllint to find <version> children of <dependencies>
    if command -v xmllint >/dev/null && \
       xmllint --xpath "//*[local-name()='dependencies']/*[local-name()='dependency']/*[local-name()='version']" "$f" 2>/dev/null | grep -q '<version>'; then
      report "Service POM declares dependency versions (BOM owns versions)" "$f"
    fi
  fi

  # Legacy Spring Boot
  if grep -nE '(Spring Boot 3|spring-boot\.[a-z-]*[<:].*3\.[0-9])' "$f" > /dev/null; then
    report "Legacy Spring Boot reference detected (project locks to 4.x)" "$f"
  fi

  # ... additional checks ...
done

if [ $FAIL -eq 1 ]; then
  echo ""
  echo "Pre-commit checks failed. Fix the issues above or use --no-verify if you really mean it."
  echo "(Reminder: --no-verify is rarely the right answer.)"
  exit 1
fi
exit 0
```

### Edge cases
- **Test fixtures**: passwords in `src/test/**` and `**/fixtures/**` are exempt — they're test data, not real secrets.
- **Generated files**: skip `target/**`, `node_modules/**`, `*.generated.*`.
- **Binary files**: skip; `git diff --cached --name-only` includes them but `grep` would error.
- **Large files**: skip files > 1MB (likely binary); a separate hook handles those.
- **Re-running after fix**: the hook runs again on the next `git commit`; no special handling needed.

### Exit codes
- `0`: all checks passed
- `1`: at least one check failed; commit blocked

### Example output
```
❌ Docker tag 'latest' is forbidden outside local files
   File: services/order-service/k8s/base/deployment.yaml
   Match: 12:        image: order-service:latest

❌ Service POM declares dependency versions (BOM owns versions)
   File: services/payment-service/pom.xml

Pre-commit checks failed. Fix the issues above or use --no-verify if you really mean it.
(Reminder: --no-verify is rarely the right answer.)
```

### Tuning over time
Add new checks here as new categories of mistakes are discovered in code review. Keep checks fast — if a check takes more than 100ms, move it to CI instead.

---

## 2. validate-build-step-completion

### Purpose
The most common mistake on a build-plan-driven project is marking a step done without actually meeting its acceptance criteria. This hook detects when a step transitions from `- [ ]` to `- [x]` in `build-plan.md` and runs the criteria where automatable.

### When it fires
Pre-commit, only when `build-plan.md` is in the staged changes AND a `- [ ]` → `- [x]` transition is detected.

### What it checks
Parses `build-plan.md` for the step that just got marked done. Locates the step's "Acceptance criteria" section. For each criterion:
- If automatable (e.g., "All migrations apply", "Unit tests pass", "Coverage ≥ 80%"), run the corresponding command.
- If not automatable (e.g., "Restaurant POS app receives push notification"), print the criterion and prompt for `y/n` confirmation.

### Implementation skeleton

```python
#!/usr/bin/env python3
# .claude/hooks/pre-commit/validate-build-step-completion
import subprocess
import sys
import re
from pathlib import Path

def main():
    # Find the step that flipped to done
    diff = subprocess.run(
        ["git", "diff", "--cached", "--", "build-plan.md"],
        capture_output=True, text=True
    ).stdout

    flipped_steps = []
    for line in diff.split("\n"):
        # Looking for "- [x] **Objective**" lines newly added
        if re.match(r"^\+\s*-\s*\[x\]\s+\*\*Objective", line):
            flipped_steps.append(line)
        # And matching "- [ ] **Objective**" lines being removed
        # (sanity check — true flip transition)

    if not flipped_steps:
        return 0  # no transitions; nothing to do

    # Parse build-plan.md to find the actual Step X.Y this corresponds to
    plan_text = Path("build-plan.md").read_text()
    # Identify each newly-completed step by matching the line context

    # For each completed step, extract its Acceptance criteria block
    for step_id, criteria in find_completed_steps(plan_text, flipped_steps):
        print(f"Validating completion of Step {step_id}...")
        for crit in criteria:
            ok = validate_criterion(crit, step_id)
            if not ok:
                print(f"❌ Criterion not met: {crit}")
                return 1
        print(f"✅ Step {step_id} criteria validated")
    return 0

def validate_criterion(crit: str, step_id: str) -> bool:
    """Try to map criterion text to an automatable command."""
    if "mvn" in crit.lower() and "verify" in crit.lower():
        # Extract service path from step context, run `mvn -pl ... verify`
        service = infer_service_from_step(step_id)
        return run([f"mvn -B -pl services/{service} -am verify"])
    elif "coverage" in crit.lower() and "%" in crit:
        # Run JaCoCo report and check threshold
        threshold = int(re.search(r"(\d+)%", crit).group(1))
        return check_coverage_threshold(step_id, threshold)
    elif "migrations apply" in crit.lower():
        service = infer_service_from_step(step_id)
        return run([f"mvn -pl services/{service} flyway:migrate"])
    elif "Testcontainers" in crit:
        service = infer_service_from_step(step_id)
        return run([f"mvn -pl services/{service} verify -Pintegration-test"])
    elif "ArgoCD shows Synced/Healthy" in crit:
        # Manual confirmation needed
        return prompt_user(crit)
    else:
        # Default: print and prompt
        return prompt_user(crit)

def prompt_user(crit: str) -> bool:
    print(f"  Manual check: {crit}")
    response = input("  Confirmed? [y/N] ").strip().lower()
    return response == "y"

if __name__ == "__main__":
    sys.exit(main())
```

### Edge cases
- **Multiple steps flipped in one commit**: validate each.
- **Step flipped back from `[x]` to `[ ]`**: silently ignore; that's a deliberate revert.
- **Build step not found in `build-plan.md`**: warn but don't block (could be a step rename or a typo).
- **Criterion text changes** (e.g., new acceptance criterion added in a different commit): the hook only validates the criteria as they exist *now* in `build-plan.md`; not historical versions.
- **Long-running commands**: `mvn verify` can take 5+ minutes. Consider providing a `--quick` flag that skips the full build and runs only fast checks; rely on CI for the full validation.
- **Tests need infrastructure**: Testcontainers requires Docker. If Docker isn't running, fall back to manual confirmation.

### Exit codes
- `0`: step transitions valid; all criteria met
- `1`: at least one criterion not met; commit blocked
- `2`: no automated check possible; manual confirmation declined

### Example output
```
Validating completion of Step 8.4...
  Running: mvn -B -pl services/order-service -am verify
  ✓ Build passed (4m 23s)
  Running: mvn -pl services/order-service verify -Pintegration-test
  ✓ Integration tests passed (1m 12s)
  Manual check: ArgoCD shows Synced/Healthy in staging
  Confirmed? [y/N] y
✅ Step 8.4 criteria validated
```

### Tuning
Build the `validate_criterion` mapper incrementally. Start with the most common criteria phrasings (`mvn verify`, coverage, migrations) and add more as you encounter them. The criterion-to-command mapping is the long-term value of this hook.

---

## 3. flyway-migration-safety

### Purpose
A non-backwards-compatible migration deployed alongside code that doesn't expect the new schema can take down all pods of a service simultaneously. This hook catches the most common offenders before they reach `main`.

### When it fires
Pre-commit, when any file matching `services/*/src/main/resources/db/migration/V*__*.sql` is in the staged changes.

### What it checks
Each new or modified migration file is parsed (cheaply, with regex — full SQL parsing isn't needed). The hook rejects:

| Pattern | Reason | Allow with override? |
|---|---|---|
| `DROP COLUMN` | Column drops break running pods that still SELECT the column | Yes, with `--allow-breaking` flag |
| `DROP TABLE` | Same | Yes, with `--allow-breaking` flag |
| `ALTER COLUMN ... SET NOT NULL` (without preceding nullable add) | Backfill must complete before enforcing NOT NULL | Yes, with `--allow-breaking` flag |
| `ALTER COLUMN ... TYPE` | Type changes can fail or block | Yes, with `--allow-breaking` flag |
| `ALTER TABLE ... RENAME COLUMN` | Renames break running pods | Yes |
| `TRUNCATE TABLE` | Almost certainly a mistake in migration files | Yes |
| File modified after merge | Migrations are immutable | No (this is an absolute rule) |
| Skipped version number | Causes Flyway "out of order" issues | No |

### Implementation skeleton

```bash
#!/usr/bin/env bash
# .claude/hooks/pre-commit/flyway-migration-safety
set -euo pipefail

STAGED_MIGRATIONS=$(git diff --cached --name-only --diff-filter=ACM | \
  grep -E 'services/[^/]+/src/main/resources/db/migration/V[0-9]+__.*\.sql$' || true)

[ -z "$STAGED_MIGRATIONS" ] && exit 0

FAIL=0
ALLOW_BREAKING=false
[ "${1:-}" = "--allow-breaking" ] && ALLOW_BREAKING=true

for f in $STAGED_MIGRATIONS; do
  # Check 1: file modified (not added) — IMMUTABILITY rule
  if ! git diff --cached --diff-filter=A --name-only | grep -qF "$f"; then
    # File was modified, not added
    if git ls-tree HEAD "$f" >/dev/null 2>&1; then
      # AND it exists in HEAD (so it was previously merged)
      echo "❌ ABSOLUTE: Migration file $f was modified after merge."
      echo "   Migrations are immutable. Add a new migration instead."
      FAIL=1
      continue
    fi
  fi

  # Check 2: dangerous patterns
  for pattern in 'DROP\s+COLUMN' 'DROP\s+TABLE' 'TRUNCATE\s+TABLE' 'RENAME\s+COLUMN'; do
    if grep -iE "$pattern" "$f" > /dev/null; then
      MATCH=$(grep -iE "$pattern" "$f" | head -1)
      if [ "$ALLOW_BREAKING" = "true" ]; then
        echo "⚠ Breaking migration allowed: $f contains $pattern"
      else
        echo "❌ Breaking migration: $f contains $pattern"
        echo "   Match: $MATCH"
        echo "   Use --allow-breaking flag if intentional, but verify the deploy plan first."
        FAIL=1
      fi
    fi
  done

  # Check 3: ALTER COLUMN ... SET NOT NULL needs prior nullable column
  if grep -iE 'ALTER\s+COLUMN\s+\w+\s+SET\s+NOT\s+NULL' "$f" > /dev/null; then
    COLUMN=$(grep -iE 'ALTER\s+COLUMN\s+\w+\s+SET\s+NOT\s+NULL' "$f" | \
             grep -oE 'COLUMN\s+\w+' | awk '{print $2}' | head -1)
    SERVICE=$(echo "$f" | sed -E 's|services/([^/]+)/.*|\1|')
    PRIOR_MIGRATIONS="services/$SERVICE/src/main/resources/db/migration/"

    if ! grep -lE "ADD\s+COLUMN\s+$COLUMN.*NULL" "$PRIOR_MIGRATIONS"V*.sql 2>/dev/null | head -1 > /dev/null; then
      if [ "$ALLOW_BREAKING" = "true" ]; then
        echo "⚠ NOT NULL on $COLUMN allowed without prior nullable add"
      else
        echo "❌ Migration $f sets NOT NULL on column '$COLUMN' without a preceding nullable add."
        echo "   Pattern: add nullable → backfill → set NOT NULL across two deploys."
        FAIL=1
      fi
    fi
  fi

  # Check 4: version number sequence
  VERSION=$(basename "$f" | grep -oE '^V[0-9]+' | tr -d 'V')
  SERVICE_PATH=$(dirname "$f")
  PREV_VERSION=$(ls "$SERVICE_PATH" 2>/dev/null | grep -E '^V[0-9]+' | sed -E 's/^V([0-9]+).*/\1/' | sort -n | tail -2 | head -1)
  if [ -n "$PREV_VERSION" ] && [ "$VERSION" -ne $((PREV_VERSION + 1)) ]; then
    echo "❌ Migration version V$VERSION skips from V$PREV_VERSION (no gaps allowed)"
    FAIL=1
  fi
done

[ $FAIL -eq 1 ] && exit 1 || exit 0
```

### Edge cases
- **Comments containing dangerous patterns**: e.g., a SQL comment `-- TODO: add DROP COLUMN later`. Filter out comment lines before pattern matching.
- **Multi-line statements**: `DROP\s+COLUMN` matches across lines; ensure regex uses appropriate flags.
- **First migration in a new service**: no prior `Vn` files exist; `PREV_VERSION` is empty; that's fine.
- **Migration in a non-conventional path**: e.g., a service uses `db/changelog/` instead. The hook only checks the standard path; non-standard paths bypass the check (acceptable — the hook only enforces the convention).
- **Repeatable migrations** (`R__*.sql`): Flyway concept. The hook only enforces sequencing on `V*` files, not `R*`.

### Exit codes
- `0`: all migrations safe
- `1`: at least one migration violates a rule

### Example output
```
❌ Breaking migration: services/order-service/src/main/resources/db/migration/V12__cleanup_old_columns.sql contains DROP\s+COLUMN
   Match: ALTER TABLE orders DROP COLUMN deprecated_field;
   Use --allow-breaking flag if intentional, but verify the deploy plan first.

❌ Migration services/payment-service/src/main/resources/db/migration/V8__make_idempotency_key_required.sql sets NOT NULL on column 'idempotency_key' without a preceding nullable add.
   Pattern: add nullable → backfill → set NOT NULL across two deploys.
```

### Tuning
The "must have prior nullable add" check is the trickiest. Consider extending it to verify the prior add exists in a *previous* migration (committed in HEAD), not just any migration in the working tree.

---

## 4. terraform-fmt-check

### Purpose
Style consistency in Terraform code. `terraform fmt` is fast and zero-cost; running it as a check prevents pointless style noise in PRs.

### When it fires
Pre-commit, when any `*.tf` or `*.tfvars` file is staged.

### What it checks
Runs `terraform fmt -check -recursive` on the directories containing staged files. If formatting differs, the hook prints the diff and offers to auto-fix.

### Implementation skeleton

```bash
#!/usr/bin/env bash
# .claude/hooks/pre-commit/terraform-fmt-check
set -euo pipefail

STAGED_TF=$(git diff --cached --name-only --diff-filter=ACM | grep -E '\.(tf|tfvars)$' || true)
[ -z "$STAGED_TF" ] && exit 0

# Get unique directories
DIRS=$(echo "$STAGED_TF" | xargs -I {} dirname {} | sort -u)

FAIL=0
for d in $DIRS; do
  OUTPUT=$(terraform fmt -check -recursive "$d" 2>&1 || true)
  if [ -n "$OUTPUT" ]; then
    echo "❌ Terraform formatting differs in $d:"
    terraform fmt -diff -recursive "$d"
    FAIL=1
  fi
done

if [ $FAIL -eq 1 ]; then
  echo ""
  echo "Run 'terraform fmt -recursive .' to fix, then 'git add' the changes."
  exit 1
fi
exit 0
```

### Edge cases
- **Terraform not installed**: print a warning and exit 0 (don't block; not everyone has Terraform locally).
- **Wrong Terraform version**: `terraform fmt` is forward-compatible across recent versions; not a real concern.
- **Auto-fix mode**: optionally support a `--auto-fix` flag that runs `terraform fmt -recursive` and re-stages the files. Recommended only for hooks invoked manually, not pre-commit (auto-modifying staged content is surprising).

### Exit codes
- `0`: all `.tf` files properly formatted (or Terraform not installed)
- `1`: at least one file needs formatting

### Example output
```
❌ Terraform formatting differs in platform-infra/modules/eks:
--- old/main.tf
+++ new/main.tf
@@ -42,7 +42,7 @@
   tags = {
-      Name = local.cluster_name
+    Name = local.cluster_name
     Environment = var.environment
   }

Run 'terraform fmt -recursive .' to fix, then 'git add' the changes.
```

---

## 5. proto-breaking-change

### Purpose
Protocol Buffers maintain wire-level compatibility through specific rules: never reuse field numbers, never change field types, never make required fields. A breaking change to a `.proto` deployed before consumers update is a production incident. `buf breaking` automates the check.

### When it fires
Pre-commit, when any `*.proto` file is staged.

### What it checks
Runs `buf breaking` against the previous version of the proto (HEAD) using `BACKWARD` rules. Reports any breaking changes.

Optional escape hatch: if the change is intentional and the new package version is bumped (e.g., `menu.v1` → `menu.v2`), allow with annotation.

### Implementation skeleton

```bash
#!/usr/bin/env bash
# .claude/hooks/pre-commit/proto-breaking-change
set -euo pipefail

STAGED_PROTOS=$(git diff --cached --name-only --diff-filter=ACM | grep -E '\.proto$' || true)
[ -z "$STAGED_PROTOS" ] && exit 0

if ! command -v buf >/dev/null; then
  echo "⚠ buf CLI not installed; skipping proto compatibility check"
  exit 0
fi

# Run buf breaking against HEAD
OUTPUT=$(buf breaking --against ".git#branch=HEAD" 2>&1 || true)
if [ -n "$OUTPUT" ] && echo "$OUTPUT" | grep -q "FAIL"; then
  echo "❌ Breaking proto changes detected:"
  echo "$OUTPUT"
  echo ""
  echo "If this is intentional, bump the package version (e.g., v1 → v2) and update consumers."
  echo "If accidental, add the field with a new field number instead of reusing one."
  exit 1
fi
exit 0
```

### Edge cases
- **First-time proto** (no HEAD version): `buf breaking` finds no baseline; it succeeds. Correct behavior.
- **Proto deletion**: removing a `.proto` file IS a breaking change for any consumer. The hook should flag it.
- **buf not installed**: warn-and-skip rather than fail. Developers on fresh machines shouldn't be blocked.
- **buf.yaml configuration**: depends on a `buf.yaml` at repo root specifying the modules. Without it, `buf breaking` won't find anything. The hook should verify `buf.yaml` exists first.
- **Generated stubs out of sync**: `buf` doesn't check generated Java code; that's the `regenerate-grpc-stubs` post-tool-use hook's job.

### Exit codes
- `0`: no breaking changes (or buf not installed)
- `1`: breaking changes detected

### Example output
```
❌ Breaking proto changes detected:
WARN: platform-shared-libs/common-events/src/main/proto/menu.proto:14:5:Field "1" with name "restaurant_id" on message "VerifyItemRequest" changed type from "string" to "int32".

If this is intentional, bump the package version (e.g., v1 → v2) and update consumers.
If accidental, add the field with a new field number instead of reusing one.
```

---

## 6. outbox-event-schema-check

### Purpose
When a new event type is introduced (a new `eventType` value emitted by some service), it must:
1. Have a matching Avro schema in the `common-events` shared lib.
2. Be registered in Glue Schema Registry (via Terraform).
3. Have a defined route in `OutboxRouter` (Kafka topic or SQS queue).

This hook detects new event types and verifies all three. Without it, a service might emit an event no one knows how to consume.

### When it fires
Pre-commit, when any Java file is staged that contains `outbox.save(`, `EventType.`, or `eventType:` with a string literal that wasn't in the previous version.

### What it checks
1. Extract candidate event-type strings from the diff (ALL_CAPS strings adjacent to outbox/event keywords).
2. For each candidate:
   - Verify a matching `.avsc` file exists in `common-events/src/main/avro/`.
   - Verify the event type appears in `OutboxRouter`'s mapping config.
   - Verify the destination topic/queue is provisioned in Terraform.

### Implementation skeleton

```python
#!/usr/bin/env python3
# .claude/hooks/pre-commit/outbox-event-schema-check
import subprocess
import re
from pathlib import Path
import sys

def main():
    diff = subprocess.run(
        ["git", "diff", "--cached", "--unified=0"],
        capture_output=True, text=True
    ).stdout

    # Extract candidate event types from added lines
    # Looking for ALL_CAPS_WITH_UNDERSCORES near "outbox", "eventType", "EventType."
    candidates = set()
    for line in diff.split("\n"):
        if not line.startswith("+"):
            continue
        # Patterns like outbox.save(...event("ORDER_PAID")...) or .eventType("PAYMENT_FAILED")
        for match in re.finditer(r'"([A-Z][A-Z_]{4,})"', line):
            candidates.add(match.group(1))

    if not candidates:
        return 0

    # Filter to only NEW event types (not in HEAD)
    head_text = subprocess.run(
        ["git", "show", "HEAD:--"],
        capture_output=True, text=True
    ).stdout
    # ... (in practice, scan the relevant files in HEAD for existing event types)

    new_event_types = filter_new_only(candidates)

    fail = False
    for et in new_event_types:
        avsc_exists = check_avsc_exists(et)
        router_exists = check_router_mapping(et)
        topic_exists = check_topic_provisioned(et)

        if not (avsc_exists and router_exists and topic_exists):
            print(f"❌ New event type {et} is missing prerequisites:")
            if not avsc_exists:
                print(f"  - No matching schema in common-events/src/main/avro/{et.lower()}.avsc")
            if not router_exists:
                print(f"  - No mapping in OutboxRouter / application-outbox.yml")
            if not topic_exists:
                print(f"  - No Kafka topic / SQS queue provisioned in Terraform")
            fail = True

    return 1 if fail else 0

def check_avsc_exists(event_type: str) -> bool:
    avsc_dir = Path("platform-shared-libs/common-events/src/main/avro")
    if not avsc_dir.exists():
        return False
    # Convention: lowercase with underscores becomes the .avsc filename
    expected_files = [
        avsc_dir / f"{event_type.lower()}.avsc",
        avsc_dir / f"{event_type.lower().replace('_', '-')}.avsc",
    ]
    return any(f.exists() for f in expected_files)

def check_router_mapping(event_type: str) -> bool:
    router_yml = Path("platform-shared-libs/common-outbox/src/main/resources/application-outbox.yml")
    if router_yml.exists() and event_type in router_yml.read_text():
        return True
    # Also check service-level outbox configs
    for cfg in Path("services").rglob("application-outbox.yml"):
        if event_type in cfg.read_text():
            return True
    return False

def check_topic_provisioned(event_type: str) -> bool:
    # Convention: event name suggests a topic
    # ORDER_PAID, ORDER_DELIVERED → order-events topic
    # PAYMENT_SUCCESS, PAYMENT_FAILED → payment-events topic
    domain = event_type.split("_")[0].lower()
    topic = f"{domain}-events"

    # Search Terraform for topic provisioning
    msk_tf = Path("platform-infra/envs/staging/messaging.tf")
    if msk_tf.exists() and topic in msk_tf.read_text():
        return True
    return False

if __name__ == "__main__":
    sys.exit(main())
```

### Edge cases
- **False positives**: ALL_CAPS strings are common in code (enum names, constants) and not all are event types. The pattern matching needs context — only flag strings adjacent to outbox/event keywords. Tune over time.
- **Generated event names** (e.g., from JSON config): the hook can't statically detect dynamic event types. Consider it acceptable; CI runtime tests will catch missing schemas.
- **Event type renamed**: technically the old name disappearing should also be flagged (it's now orphaned in the schema registry). Out of scope for this hook; handled by `event-schema-evolution` skill.

### Exit codes
- `0`: no missing prerequisites
- `1`: at least one new event type is missing schema/router/topic

---

## 7. iam-policy-overbroad

### Purpose
Overbroad IAM is the most common cloud security mistake. Wildcards on actions or resources, missing conditions on sensitive operations, public-by-default S3 buckets — all preventable with a static check before commit.

### When it fires
Pre-commit, when any `*.tf` or YAML K8s ServiceAccount manifest is staged.

### What it checks
Parses Terraform `aws_iam_policy_document`, `aws_iam_policy`, and `aws_iam_role_policy` resources, plus K8s ServiceAccount IRSA annotations. Flags:

| Pattern | Severity |
|---|---|
| `Action: "*"` | Block |
| `Resource: "*"` on sensitive actions (`s3:DeleteObject`, `iam:CreateUser`, `kms:Decrypt`, `secretsmanager:GetSecretValue`) | Block |
| `Effect: Allow` with no Conditions on assume-role for cross-account principals | Block |
| `Action` list including 5+ services (e.g., `s3:*`, `dynamodb:*`, `kafka:*` all in one statement) | Warn |
| Unrestricted `aws_s3_bucket_public_access_block` | Block |
| Security Group `cidr_blocks = ["0.0.0.0/0"]` on non-public ports | Block |

Override available via comment annotation: `# allowed-by-design: {reason}` on the line above the resource.

### Implementation skeleton

```python
#!/usr/bin/env python3
# .claude/hooks/pre-commit/iam-policy-overbroad
import subprocess
import sys
import re
import hcl2  # python-hcl2 library

SENSITIVE_ACTIONS = [
    "s3:DeleteObject", "s3:DeleteBucket", "iam:CreateUser", "iam:DeleteUser",
    "iam:AttachUserPolicy", "kms:Decrypt", "kms:DescribeKey",
    "secretsmanager:GetSecretValue", "secretsmanager:DeleteSecret",
    "dynamodb:DeleteTable", "rds:DeleteDBInstance",
]

def main():
    staged = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACM"],
        capture_output=True, text=True
    ).stdout.split("\n")
    tf_files = [f for f in staged if f.endswith(".tf")]
    if not tf_files:
        return 0

    fail = False
    for f in tf_files:
        try:
            with open(f) as fh:
                content = fh.read()
                tf = hcl2.loads(content)
        except Exception as e:
            print(f"⚠ Could not parse {f}: {e}")
            continue

        # Check policy documents
        for stmt in extract_iam_statements(tf):
            if has_override_annotation(content, stmt):
                continue
            if "*" in stmt.get("actions", []):
                print(f"❌ IAM Action: '*' in {f}")
                print_statement_context(f, stmt)
                fail = True
            for action in stmt.get("actions", []):
                if action in SENSITIVE_ACTIONS and "*" in stmt.get("resources", []):
                    print(f"❌ Sensitive action {action} on Resource: '*' in {f}")
                    fail = True

        # Check security groups
        for sg in extract_security_groups(tf):
            for ingress in sg.get("ingress", []):
                if ingress.get("cidr_blocks") == ["0.0.0.0/0"]:
                    port = ingress.get("from_port")
                    if port not in (80, 443):  # only HTTP(S) public is OK
                        print(f"❌ Security group {sg['name']} allows 0.0.0.0/0 on port {port} in {f}")
                        fail = True

    return 1 if fail else 0

if __name__ == "__main__":
    sys.exit(main())
```

### Edge cases
- **Parsing failures**: HCL parsing libraries vary in robustness; wrap in try/except and warn-and-continue.
- **Variables and locals**: `Resource: var.bucket_arn` looks fine but might resolve to `*` at apply time. The hook can't fully evaluate; rely on `tfsec` and `checkov` in CI for deeper analysis.
- **Override annotations**: support `# allowed-by-design: {reason}` for legitimate cases (e.g., a `s3:GetObject` on `*` for a bucket that lists external public datasets).
- **CDK/Pulumi**: not Terraform; out of scope. (We use Terraform for this project.)

### Exit codes
- `0`: no overbroad policies
- `1`: at least one violation

---

## 8. secret-scanner

### Purpose
A dedicated entropy-based secret scanner is stronger than the simple regex patterns in `enforce-conventions`. Tools like `gitleaks` or `truffleHog` look for high-entropy strings and known secret formats from many providers.

### When it fires
Pre-commit, on every commit attempt.

### What it checks
Runs `gitleaks protect --staged` (or `truffleHog filesystem --no-update --since-commit HEAD`). Reports any findings.

### Implementation skeleton

```bash
#!/usr/bin/env bash
# .claude/hooks/pre-commit/secret-scanner
set -euo pipefail

if ! command -v gitleaks >/dev/null; then
  echo "⚠ gitleaks not installed; falling back to enforce-conventions checks only"
  exit 0
fi

# Run on staged content only
gitleaks protect --staged --no-banner --redact 2>&1
EXIT=$?

if [ $EXIT -eq 1 ]; then
  echo ""
  echo "Secret detected in staged changes. If this is a false positive,"
  echo "add the path to .gitleaksignore (with justification in a comment)."
  exit 1
fi
exit 0
```

### Configuration: `.gitleaks.toml`

```toml
title = "food-ordering-platform gitleaks config"

[allowlist]
description = "Test fixtures"
paths = [
  '''dev/keys/dev-private\.pem''',         # local-only test JWT key
  '''dev/seed/.*''',                        # seed data is committed test data
  '''.*\bsrc/test\b.*''',                  # test fixtures
]

[[rules]]
id = "stripe-test-key"
description = "Allow Stripe test keys (sk_test_)"
regex = '''sk_test_[A-Za-z0-9]{24,}'''
allowlist.regexes = ['''sk_test_'''
]
```

### Edge cases
- **Test fixtures**: dev/seed/, src/test/, dev/keys/ — all allowlisted via `.gitleaks.toml`.
- **History scan**: `gitleaks protect --staged` only checks staged content. To scan history (e.g., before pushing a feature branch), use `gitleaks detect`. Consider a separate pre-push hook for that.
- **False positives**: high-entropy strings are common in test data, generated IDs, tokens. Allowlist as needed; document why each entry exists.
- **Tool not installed**: warn-and-skip; don't block.

### Exit codes
- `0`: no secrets detected
- `1`: secret detected; commit blocked

---

## 9. kafka-schema-compat-check

### Purpose
When an `.avsc` file changes, Glue Schema Registry's compatibility check should pass before the change reaches `main`. Otherwise, a schema deploy will be rejected at runtime.

### When it fires
Pre-commit, when any `*.avsc` file is staged.

### What it checks
For each modified schema:
1. Determine the schema name (filename or `name` field in the schema).
2. Call `aws glue check-schema-version-validity` with the new content.
3. Report any compatibility violations.

### Implementation skeleton

```bash
#!/usr/bin/env bash
# .claude/hooks/pre-commit/kafka-schema-compat-check
set -euo pipefail

STAGED_SCHEMAS=$(git diff --cached --name-only --diff-filter=ACM | grep -E '\.avsc$' || true)
[ -z "$STAGED_SCHEMAS" ] && exit 0

if ! command -v aws >/dev/null; then
  echo "⚠ aws CLI not installed; skipping schema compatibility check"
  exit 0
fi

REGISTRY_NAME="${GLUE_SCHEMA_REGISTRY_NAME:-food-ordering-platform-staging}"

FAIL=0
for f in $STAGED_SCHEMAS; do
  SCHEMA_NAME=$(basename "$f" .avsc)
  SCHEMA_DEF=$(cat "$f")

  # Check if schema exists in registry
  if ! aws glue get-schema --schema-id "RegistryName=$REGISTRY_NAME,SchemaName=$SCHEMA_NAME" >/dev/null 2>&1; then
    echo "ℹ Schema $SCHEMA_NAME is new (not in registry yet) — skipping compat check"
    continue
  fi

  RESULT=$(aws glue check-schema-version-validity \
    --data-format AVRO \
    --schema-definition "$SCHEMA_DEF" \
    --output json 2>&1 || true)

  if echo "$RESULT" | grep -q '"Valid": false'; then
    REASON=$(echo "$RESULT" | jq -r .Error)
    echo "❌ Schema $SCHEMA_NAME failed compatibility check:"
    echo "   $REASON"
    FAIL=1
  fi
done

[ $FAIL -eq 1 ] && exit 1 || exit 0
```

### Edge cases
- **Network unavailable**: `aws glue` calls fail. Print a warning and exit 0 (fail-open) — don't block commits when offline.
- **AWS auth not configured**: same — warn and skip.
- **Schema registry not in staging account** (e.g., developer's personal AWS): the check uses whatever profile is active. Document that developers should set `AWS_PROFILE=staging` for accurate checks.
- **New schema (not yet in registry)**: skip check — there's no baseline to compare against.
- **Schema deletion**: deleting a `.avsc` is a breaking change. Treat similarly to proto deletion: surface as a warning and require explicit override.

### Exit codes
- `0`: all schemas compatible (or AWS unavailable)
- `1`: at least one schema fails compatibility

---

## 10. dockerfile-best-practices

### Purpose
Docker image quality affects security, image size, and startup time. A few static checks catch the most common offenders.

### When it fires
Pre-commit, when a `Dockerfile` or `Dockerfile.*` is staged.

### What it checks

| Pattern | Severity |
|---|---|
| Single-stage build for Java service | Warn (multi-stage cuts image size) |
| `USER root` at the end | Block (must run non-root) |
| No `HEALTHCHECK` directive | Warn |
| `apt-get install` without `--no-install-recommends` | Warn |
| `apt-get install` without `rm -rf /var/lib/apt/lists/*` | Warn (keeps image bloated) |
| `COPY . .` without preceding `.dockerignore` | Block |
| Base image not pinned to digest or version (`FROM x:latest`) | Block |
| `RUN curl ... | bash` without checksum verification | Warn |
| Distroless or Alpine encouraged for runtime stage (informational) | Info |

### Implementation skeleton

```bash
#!/usr/bin/env bash
# .claude/hooks/pre-commit/dockerfile-best-practices
set -euo pipefail

STAGED_DOCKERFILES=$(git diff --cached --name-only --diff-filter=ACM | grep -E '(^|/)Dockerfile(\..*)?$' || true)
[ -z "$STAGED_DOCKERFILES" ] && exit 0

FAIL=0
WARN=0

for f in $STAGED_DOCKERFILES; do
  CONTENT=$(cat "$f")

  # Block: latest tag on base
  if echo "$CONTENT" | grep -E '^FROM .*:latest( |$)' > /dev/null; then
    echo "❌ $f: base image uses :latest (pin to a version or digest)"
    FAIL=1
  fi

  # Block: no version tag at all
  if echo "$CONTENT" | grep -E '^FROM [^:]+($| AS )' | grep -v '@sha256:' > /dev/null; then
    echo "❌ $f: base image has no version tag or digest"
    FAIL=1
  fi

  # Block: USER root at end
  LAST_USER=$(echo "$CONTENT" | grep -E '^USER ' | tail -1 | awk '{print $2}' || true)
  if [ "$LAST_USER" = "root" ] || [ "$LAST_USER" = "0" ]; then
    echo "❌ $f: final USER is root (must be non-root)"
    FAIL=1
  fi
  if [ -z "$LAST_USER" ]; then
    echo "❌ $f: no USER directive (defaults to root)"
    FAIL=1
  fi

  # Block: COPY . without .dockerignore
  if echo "$CONTENT" | grep -E '^COPY \. ' > /dev/null; then
    DIR=$(dirname "$f")
    if [ ! -f "$DIR/.dockerignore" ]; then
      echo "❌ $f: COPY . . without a .dockerignore file (will include .git, target/, etc.)"
      FAIL=1
    fi
  fi

  # Warn: no HEALTHCHECK
  if ! echo "$CONTENT" | grep -E '^HEALTHCHECK ' > /dev/null; then
    echo "⚠ $f: no HEALTHCHECK directive"
    WARN=1
  fi

  # Warn: apt-get without cleanup
  if echo "$CONTENT" | grep -E 'apt-get install' > /dev/null; then
    if ! echo "$CONTENT" | grep -E 'rm -rf /var/lib/apt/lists' > /dev/null; then
      echo "⚠ $f: apt-get install without cleanup (rm -rf /var/lib/apt/lists/*)"
      WARN=1
    fi
  fi
done

if [ $FAIL -eq 1 ]; then
  exit 1
fi
exit 0
```

### Edge cases
- **Multi-stage**: `USER` can vary by stage. The hook checks the LAST `USER` (in the runtime stage).
- **Distroless base images**: don't have `apt-get`, so the apt-cleanup check doesn't apply. Detect distroless and skip.
- **Base image with sha256 digest**: counts as pinned. The check shouldn't flag it.

### Exit codes
- `0`: no blocking issues (warnings allowed)
- `1`: blocking issue

---

## 11. branch-naming-enforcement

### Purpose
Branches named `feature/{step-id}-{slug}` make it trivial to see which step a branch implements. Bad branch names (`my-feature`, `wip`, `dev`) make tracking 85 build steps impossible.

### When it fires
Pre-commit, on the first commit to a new branch.

### What it checks
Validates branch name against allowed patterns:
- `feature/{step-id}-{slug}` — e.g., `feature/8.4-payment-success-handler`
- `fix/{description}` — e.g., `fix/saga-timeout-deadlock`
- `chore/{description}` — e.g., `chore/bump-spring-boot-405`
- `revert/{sha}-{description}` — e.g., `revert/abc1234-restore-old-impl`

Reserved branch names that are always allowed: `main`, `staging`, `production`.

### Implementation skeleton

```bash
#!/usr/bin/env bash
# .claude/hooks/pre-commit/branch-naming-enforcement
set -euo pipefail

BRANCH=$(git rev-parse --abbrev-ref HEAD)

# Allowed reserved branches
case "$BRANCH" in
  main|staging|production) exit 0 ;;
esac

# Patterns
if [[ "$BRANCH" =~ ^feature/[0-9]+\.[0-9]+-[a-z0-9-]+$ ]]; then exit 0; fi
if [[ "$BRANCH" =~ ^fix/[a-z0-9-]+$ ]]; then exit 0; fi
if [[ "$BRANCH" =~ ^chore/[a-z0-9-]+$ ]]; then exit 0; fi
if [[ "$BRANCH" =~ ^revert/[a-z0-9]+-[a-z0-9-]+$ ]]; then exit 0; fi

echo "❌ Branch name '$BRANCH' doesn't match conventions:"
echo ""
echo "Allowed patterns:"
echo "  feature/{step-id}-{slug}    — e.g. feature/8.4-payment-success-handler"
echo "  fix/{description}           — e.g. fix/saga-timeout-deadlock"
echo "  chore/{description}         — e.g. chore/bump-spring-boot-405"
echo "  revert/{sha}-{description}  — e.g. revert/abc1234-restore-old-impl"
echo ""
echo "To rename: git branch -m old new"
exit 1
```

### Edge cases
- **Detached HEAD**: branch name is `HEAD` or empty. Skip the check.
- **Slug with underscores or capitals**: rejected. Convention is kebab-case.
- **Long slugs**: no maximum enforced; rely on Git's branch length limit.
- **Override**: no built-in override; if a developer needs an exotic branch name, they can `git commit --no-verify`.

### Exit codes
- `0`: branch name valid
- `1`: branch name invalid

---

# Pre-tool-use Hooks

## 12. protect-production

### Purpose
The single most important hook in this list. Prevents the "I thought my AWS profile was staging but it was prod" mistake — the kind that ends a career and explains a Friday night outage. A small friction cost on prod-touching commands prevents catastrophic mistakes.

### When it fires
Pre-tool-use, before EVERY `bash` tool call from Claude Code.

### What it checks
Inspects the command being run. If it matches any of the patterns below AND the current AWS profile resolves to a production account, the hook blocks and prompts for explicit confirmation.

| Pattern | Risk |
|---|---|
| `terraform apply.*` (in any path) | IaC change to prod |
| `terraform destroy.*` | IaC destroy in prod |
| `kubectl.*--context.*prod` | Direct K8s API call to prod cluster |
| `aws.*delete.*` (with prod profile) | Destructive AWS call |
| `aws s3 rm .* --recursive` | Bulk S3 deletion |
| `aws rds delete-*` | RDS deletion |
| `aws dynamodb delete-table` | DDB table deletion |
| `aws iam delete-*` | IAM deletion |
| `aws ssm send-command` | Remote command execution on EC2 |
| `eksctl.*delete` | EKS resource deletion |
| `argocd app delete` | GitOps app deletion |

The hook prompts: "About to run [command] against PRODUCTION (account ID 1234567890). Type EXACTLY 'I confirm production' to proceed."

### Implementation skeleton

```python
#!/usr/bin/env python3
# .claude/hooks/pre-tool-use/protect-production
import json
import sys
import re
import subprocess

DANGEROUS_PATTERNS = [
    (r"terraform\s+apply", "Terraform apply"),
    (r"terraform\s+destroy", "Terraform destroy"),
    (r"kubectl.*--context.*prod", "kubectl against prod cluster"),
    (r"aws\s+s3\s+rm.*--recursive", "Bulk S3 delete"),
    (r"aws\s+rds\s+delete-", "RDS delete"),
    (r"aws\s+dynamodb\s+delete-table", "DDB table delete"),
    (r"aws\s+iam\s+delete-", "IAM delete"),
    (r"aws\s+ssm\s+send-command", "Remote command via SSM"),
    (r"eksctl.*delete", "EKS delete via eksctl"),
    (r"argocd\s+app\s+delete", "ArgoCD app delete"),
]

PRODUCTION_ACCOUNT_IDS = {"123456789012"}  # configure for your org

def main():
    # Claude Code passes tool input via stdin as JSON
    tool_input = json.load(sys.stdin)
    if tool_input.get("tool") != "bash":
        return 0  # not a bash call; don't interfere

    command = tool_input.get("input", {}).get("command", "")

    matched = None
    for pattern, label in DANGEROUS_PATTERNS:
        if re.search(pattern, command, re.IGNORECASE):
            matched = label
            break

    if not matched:
        return 0  # no dangerous pattern

    # Check AWS context
    try:
        result = subprocess.run(
            ["aws", "sts", "get-caller-identity", "--query", "Account", "--output", "text"],
            capture_output=True, text=True, timeout=5
        )
        account_id = result.stdout.strip()
    except Exception:
        # Can't determine account; fail safe (block)
        print(f"⚠ Cannot determine AWS account. Blocking dangerous command: {matched}", file=sys.stderr)
        return 1

    if account_id not in PRODUCTION_ACCOUNT_IDS:
        return 0  # not prod; let it through

    # Prompt the user
    print(f"\n⚠⚠⚠ PRODUCTION-TOUCHING COMMAND: {matched}", file=sys.stderr)
    print(f"     Account: {account_id} (PRODUCTION)", file=sys.stderr)
    print(f"     Command: {command}", file=sys.stderr)
    print(f"\nTo proceed, type EXACTLY: I confirm production", file=sys.stderr)
    response = input("> ")
    if response.strip() == "I confirm production":
        return 0
    print("❌ Confirmation not given; command blocked.", file=sys.stderr)
    return 1

if __name__ == "__main__":
    sys.exit(main())
```

### Edge cases
- **AWS not authenticated**: `aws sts get-caller-identity` fails. Fail-safe by blocking — better to break a build than silently destroy prod.
- **Account ID resolution slow**: cache the result for the session; first call is sub-second after warmup, subsequent calls free.
- **Multiple AWS profiles in one shell**: track the resolved account ID per command, not per session.
- **Read-only operations**: `aws s3 ls`, `aws ec2 describe-*` aren't dangerous; the patterns only match write/destroy operations.
- **Confirmation phrase**: deliberately requires typing the exact phrase, not just `y` — friction is the feature.
- **Override during automation**: in CI (where there's no human to type), the hook should be disabled via env var (`SKIP_PROTECT_PRODUCTION=true`). Document this and use sparingly.

### Exit codes
- `0`: command allowed (not dangerous, not prod, or confirmation given)
- `1`: command blocked

### Example output
```
⚠⚠⚠ PRODUCTION-TOUCHING COMMAND: Terraform apply
     Account: 123456789012 (PRODUCTION)
     Command: terraform apply -auto-approve

To proceed, type EXACTLY: I confirm production
> I confirm prod
❌ Confirmation not given; command blocked.
```

---

## 13. confirm-data-mutation

### Purpose
Even in non-production, a `DROP TABLE` or `DELETE FROM users` issued without thinking can ruin a developer's afternoon. This hook prompts on data-mutating SQL run via tooling, regardless of environment.

### When it fires
Pre-tool-use, before `bash` tool calls that include data-mutation patterns.

### What it checks

| Pattern | Severity |
|---|---|
| `DROP\s+TABLE` (in psql, mysql, sqlite3 commands) | Confirm |
| `DROP\s+DATABASE` | Confirm |
| `TRUNCATE` | Confirm |
| `DELETE\s+FROM .* WHERE ?$` (no WHERE clause) | Confirm |
| `aws dynamodb delete-table` | Already covered by protect-production for prod; also confirm for non-prod |
| `aws dynamodb batch-write-item` with deletes | Confirm |
| `aws s3 rm.*--recursive` | Confirm |
| `flyway clean` | Confirm — wipes the schema |
| `aws rds delete-db-instance` (without `--skip-final-snapshot=false`) | Confirm |

### Implementation skeleton

Similar to `protect-production`, but doesn't gate on account ID — gates on the command pattern alone. Lower-friction confirmation: typing `yes` is sufficient (vs the `I confirm production` phrase).

```python
#!/usr/bin/env python3
# .claude/hooks/pre-tool-use/confirm-data-mutation
import json, sys, re

PATTERNS = [
    (r"DROP\s+TABLE", "DROP TABLE"),
    (r"DROP\s+DATABASE", "DROP DATABASE"),
    (r"TRUNCATE", "TRUNCATE"),
    (r"DELETE\s+FROM\s+\w+\s*;", "DELETE without WHERE"),
    (r"aws\s+s3\s+rm.*--recursive", "S3 recursive delete"),
    (r"aws\s+dynamodb\s+delete-table", "DDB delete-table"),
    (r"flyway\s+clean", "flyway clean"),
]

def main():
    inp = json.load(sys.stdin)
    if inp.get("tool") != "bash":
        return 0
    cmd = inp.get("input", {}).get("command", "")

    for pattern, label in PATTERNS:
        if re.search(pattern, cmd, re.IGNORECASE):
            print(f"⚠ Data mutation: {label}", file=sys.stderr)
            print(f"  Command: {cmd}", file=sys.stderr)
            response = input("Continue? [yes/N] ").strip().lower()
            if response == "yes":
                return 0
            return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
```

### Edge cases
- **DELETE with WHERE**: not flagged. Most DELETEs with WHERE are intentional.
- **In-script comments**: `-- DROP TABLE users` in a comment shouldn't trigger. The regex matches the keyword but the comment context is lost; tune by requiring the keyword to be at the start of a line or after a semicolon.
- **psql -c "DELETE FROM ..."**: matched correctly via regex.
- **Heredoc SQL**: `psql <<EOF DROP TABLE ... EOF` — caught.
- **CI bypass**: same env var as protect-production: `SKIP_CONFIRM_DATA_MUTATION=true`.

### Exit codes
- `0`: not a mutation, or confirmation given
- `1`: confirmation declined

---

## 14. block-cross-account-mistake

### Purpose
Subtle category of mistake: editing `envs/staging/*.tf` while authenticated as the production AWS account, then running `terraform apply`. The IaC code says staging; the AWS context says prod; result is unexpected.

### When it fires
Pre-tool-use, before `bash` tool calls involving `terraform apply` or `terraform destroy`.

### What it checks
1. Inspect the working directory of the tool call.
2. Determine the env from the path (`envs/staging/`, `envs/production/`, etc.).
3. Determine the AWS account from `aws sts get-caller-identity`.
4. If the env-vs-account mismatch occurs, block and prompt.

### Implementation skeleton

```python
#!/usr/bin/env python3
# .claude/hooks/pre-tool-use/block-cross-account-mistake
import json, sys, subprocess, re

ENV_TO_ACCOUNT = {
    "staging": "111111111111",
    "production": "123456789012",
    "shared": None,  # any account
    "load-test": "222222222222",
}

def main():
    inp = json.load(sys.stdin)
    if inp.get("tool") != "bash":
        return 0
    cmd = inp.get("input", {}).get("command", "")
    cwd = inp.get("input", {}).get("cwd", "")

    if not re.search(r"terraform\s+(apply|destroy)", cmd):
        return 0

    # Determine env from path
    env_match = re.search(r"envs/([^/]+)", cwd)
    if not env_match:
        return 0  # can't determine env; let it through (probably not in our tree)
    env = env_match.group(1)
    expected = ENV_TO_ACCOUNT.get(env)
    if expected is None:
        return 0  # this env can use any account

    # Get current account
    try:
        result = subprocess.run(
            ["aws", "sts", "get-caller-identity", "--query", "Account", "--output", "text"],
            capture_output=True, text=True, timeout=5,
        )
        current = result.stdout.strip()
    except Exception:
        print("⚠ Cannot determine current AWS account", file=sys.stderr)
        return 1

    if current != expected:
        print(f"❌ MISMATCH: editing envs/{env} but AWS profile is {current}, expected {expected}", file=sys.stderr)
        print(f"   Switch profile: aws sso login --profile {env}", file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
```

### Edge cases
- **Custom env folders**: `envs/dev-{name}` (developer-specific). Skip the check.
- **Multi-account staging**: each developer has their own staging account. Use a regex on env names instead of a hardcoded mapping.
- **`shared` env**: cross-account ECR, IAM. The current account doesn't matter — usually shared resources are managed from a specific account, but it's environment-dependent. Mark as "any account allowed".

### Exit codes
- `0`: no mismatch
- `1`: mismatch detected

---

# Post-tool-use Hooks

## 15. auto-format

### Purpose
After Claude Code edits a file, run the appropriate formatter. Keeps diffs clean. Eliminates style discussions in PR review.

### When it fires
Post-tool-use, after `str_replace`, `create_file`, or any tool that modifies file content.

### What it does
Inspects the modified file path. Selects the formatter:

| File extension | Formatter |
|---|---|
| `.java` | `mvn spotless:apply` (scoped to the changed module) |
| `.tf`, `.tfvars` | `terraform fmt` |
| `.yaml`, `.yml` | `yamlfmt` (or `prettier --parser=yaml`) |
| `.json` | `prettier --parser=json` (sorted keys) |
| `.md` | `prettier --parser=markdown` (or skip — Markdown formatting is opinionated) |
| `.proto` | `buf format` |
| `.sh` | `shfmt -i 2 -ci` |
| `.py` | `ruff format` |

Output is silent unless formatting changes were applied; in that case, log "Reformatted: {file}".

### Implementation skeleton

```python
#!/usr/bin/env python3
# .claude/hooks/post-tool-use/auto-format
import json, sys, subprocess, os

FORMATTERS = {
    ".tf": ["terraform", "fmt"],
    ".tfvars": ["terraform", "fmt"],
    ".yaml": ["yamlfmt"],
    ".yml": ["yamlfmt"],
    ".json": ["prettier", "--write", "--parser=json"],
    ".proto": ["buf", "format", "-w"],
    ".sh": ["shfmt", "-w", "-i", "2", "-ci"],
    ".py": ["ruff", "format"],
}

def main():
    inp = json.load(sys.stdin)
    tool = inp.get("tool")
    if tool not in {"str_replace", "create_file"}:
        return 0
    path = inp.get("input", {}).get("path") or inp.get("input", {}).get("file")
    if not path or not os.path.exists(path):
        return 0

    # Find extension
    ext = os.path.splitext(path)[1]
    formatter = FORMATTERS.get(ext)
    if formatter:
        subprocess.run(formatter + [path], capture_output=True)
        return 0

    # Java: handled separately via Maven
    if ext == ".java":
        # Find the module root (closest pom.xml)
        d = os.path.dirname(path)
        while d and not os.path.exists(os.path.join(d, "pom.xml")):
            d = os.path.dirname(d)
        if d:
            subprocess.run(
                ["mvn", "-q", "spotless:apply", "-pl", d, "-DskipTests"],
                capture_output=True
            )
    return 0

if __name__ == "__main__":
    sys.exit(main())
```

### Edge cases
- **Formatter not installed**: skip silently (don't block; reduce friction).
- **Spotless slow**: `mvn spotless:apply` boots the Maven JVM. Can take 5+ seconds. Consider scoping to the file via `-DspotlessFiles=...` or running async.
- **Generated code**: don't format `target/generated-sources/`. Most formatters skip auto-generated headers; ensure paths are excluded.
- **Massive file rewrites**: if the formatter rewrites 1000 lines, the original Claude Code edit's diff becomes hard to review. Consider a pre-edit format step instead, so subsequent edits land on already-formatted code.
- **Rapid successive edits**: don't re-format on every tiny edit; debounce per session.

### Exit codes
Always `0` — never block. Logs failures but doesn't fail.

---

## 16. regenerate-grpc-stubs

### Purpose
When a `.proto` changes, generated Java stubs must be regenerated. Otherwise the next `mvn compile` either fails or uses stale stubs. Easy to forget. Not so easy to debug.

### When it fires
Post-tool-use, after edits to any `.proto` file.

### What it does
Runs `mvn -pl platform-shared-libs/common-events protobuf:compile generate-sources -q` to regenerate stubs. Stages any new generated files (since they live in `target/generated-sources/`, they're typically gitignored, but in some projects `src/main/generated/` is committed — adapt accordingly).

### Implementation skeleton

```bash
#!/usr/bin/env bash
# .claude/hooks/post-tool-use/regenerate-grpc-stubs
set -euo pipefail

INPUT=$(cat)
TOOL=$(echo "$INPUT" | jq -r .tool)
PATH_=$(echo "$INPUT" | jq -r '.input.path // .input.file')

if [[ "$TOOL" != "str_replace" && "$TOOL" != "create_file" ]]; then exit 0; fi
if [[ ! "$PATH_" =~ \.proto$ ]]; then exit 0; fi

echo "Regenerating gRPC stubs..." >&2
mvn -q -pl platform-shared-libs/common-events protobuf:compile generate-sources >/dev/null 2>&1 || {
  echo "⚠ Stub regeneration failed; check buf/protoc errors" >&2
}
exit 0
```

### Edge cases
- **Stub generation fails**: log a warning; don't block subsequent operations. The dev will see the failure on next build.
- **Multiple .proto edits in one session**: re-run the generate target, but no harm done.
- **Generated stubs committed**: if your project commits the stubs (some prefer this for build speed), the hook should also `git add target/generated-sources/`.

### Exit codes
Always `0`.

---

## 17. update-bom-on-dep-change

### Purpose
When a service POM imports a new dependency, that dependency probably belongs in the BOM (so other services consume it consistently). This hook detects new dependency declarations in service POMs and prompts to promote.

### When it fires
Post-tool-use, after edits to `services/*/pom.xml`.

### What it does
1. Diff the new POM against HEAD.
2. Find newly added `<dependency>` blocks.
3. For each: check if the groupId+artifactId already appears in `platform-bom/pom.xml`.
4. If not: prompt "This dep is not in BOM. Promote it? (recommended for cross-service deps)"

### Implementation skeleton

```python
#!/usr/bin/env python3
# .claude/hooks/post-tool-use/update-bom-on-dep-change
import json, sys, subprocess, re
from pathlib import Path

def main():
    inp = json.load(sys.stdin)
    path = inp.get("input", {}).get("path") or inp.get("input", {}).get("file")
    if not path or not re.search(r"services/[^/]+/pom\.xml$", path):
        return 0

    # Diff against HEAD
    diff = subprocess.run(["git", "diff", "HEAD", "--", path],
                          capture_output=True, text=True).stdout

    # Find added <dependency> blocks
    added_deps = re.findall(
        r"\+\s*<dependency>.*?<groupId>(.+?)</groupId>.*?<artifactId>(.+?)</artifactId>",
        diff, re.DOTALL
    )

    bom_path = Path("platform-bom/pom.xml")
    bom_text = bom_path.read_text() if bom_path.exists() else ""

    for group, artifact in added_deps:
        if f"<artifactId>{artifact}</artifactId>" in bom_text:
            continue  # already in BOM
        print(f"ℹ New dependency '{group}:{artifact}' in {path} is not in platform-bom.")
        print(f"  Consider promoting via: edit platform-bom/pom.xml dependencyManagement section.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
```

### Edge cases
- **Service-specific deps**: some deps are legitimately service-specific (e.g., a Stripe SDK only used by Payment). Don't force BOM promotion; this is informational.
- **BOM-managed but service POM declares version**: that's a different problem caught by `enforce-conventions`.
- **Removed deps**: ignore. Promotion only for additions.

### Exit codes
Always `0` — informational only.

---

## 18. cost-budget-warning

### Purpose
A `terraform plan` that adds 3 NAT Gateways or upgrades RDS to db.r6g.8xlarge silently increases monthly bill by hundreds or thousands of dollars. This hook surfaces estimated cost impact before apply.

### When it fires
Post-tool-use, after `terraform plan` finishes successfully.

### What it does
Parses the plan output (or the JSON form `terraform plan -out plan.bin && terraform show -json plan.bin > plan.json`). For each added or changed resource, looks up an approximate monthly cost in a static cost table, sums them, and warns if the delta exceeds a threshold.

Tools like `infracost` do this professionally; this hook can either wrap infracost or use a hand-maintained table.

### Implementation skeleton (with infracost)

```bash
#!/usr/bin/env bash
# .claude/hooks/post-tool-use/cost-budget-warning
set -euo pipefail

INPUT=$(cat)
TOOL=$(echo "$INPUT" | jq -r .tool)
COMMAND=$(echo "$INPUT" | jq -r '.input.command // empty')

if [[ "$TOOL" != "bash" ]]; then exit 0; fi
if [[ ! "$COMMAND" =~ terraform\ plan ]]; then exit 0; fi

if ! command -v infracost >/dev/null; then
  echo "ℹ infracost not installed; skipping cost estimate" >&2
  exit 0
fi

# Locate the plan file from the command (best effort)
PLAN_FILE=$(echo "$COMMAND" | grep -oE -- '-out[ =][^ ]+' | sed -E 's/-out[ =]//' | head -1 || true)
[ -z "$PLAN_FILE" ] && exit 0

THRESHOLD_USD="${COST_THRESHOLD_USD:-100}"
EST=$(infracost diff --path "$PLAN_FILE" --format json 2>/dev/null | jq '.diffTotalMonthlyCost | tonumber' 2>/dev/null || echo "0")

if (( $(echo "$EST > $THRESHOLD_USD" | bc -l) )); then
  echo "" >&2
  echo "⚠⚠ Estimated monthly cost increase: \$${EST} (threshold: \$${THRESHOLD_USD})" >&2
  echo "    Run 'infracost diff --path $PLAN_FILE' for details." >&2
fi
exit 0
```

### Edge cases
- **No infracost**: warn-and-skip. Don't make this a hard requirement.
- **Plan file not specified**: ignore (we can't diff what we can't find).
- **Resources with variable cost** (e.g., S3 storage, data transfer): infracost estimates these conservatively.
- **Threshold tuning**: the right threshold depends on the project. Start at $100/month for staging, $500 for prod, adjust based on developer feedback.

### Exit codes
Always `0` — informational only. Never block on cost (that's a human judgment call).

---

# Session-start Hooks

## 19. aws-context-snapshot

### Purpose
Small quality-of-life: at session start, dump the current AWS context (profile, region, account ID, EKS context) into Claude's working notes so it knows whether you're pointed at staging or prod when planning work.

### When it fires
Session start (Claude Code session begins).

### What it does
Runs `aws sts get-caller-identity`, `aws configure get region`, `kubectl config current-context`, and outputs a one-paragraph summary that Claude Code reads as context.

### Implementation skeleton

```bash
#!/usr/bin/env bash
# .claude/hooks/session-start/aws-context-snapshot
set -euo pipefail

if ! command -v aws >/dev/null; then
  echo "AWS CLI not installed; skipping context snapshot" >&2
  exit 0
fi

ACCOUNT=$(aws sts get-caller-identity --query Account --output text 2>/dev/null || echo "unknown")
PROFILE=${AWS_PROFILE:-default}
REGION=$(aws configure get region 2>/dev/null || echo "unknown")
KCTX=$(kubectl config current-context 2>/dev/null || echo "none")

# Map account ID to env name
case "$ACCOUNT" in
  111111111111) ENV="staging" ;;
  123456789012) ENV="PRODUCTION" ;;
  222222222222) ENV="load-test" ;;
  *) ENV="unknown" ;;
esac

echo "AWS Context: profile=$PROFILE region=$REGION account=$ACCOUNT (env=$ENV) k8s-context=$KCTX"
exit 0
```

### Edge cases
- **AWS CLI not configured**: skip silently.
- **kubectl not installed**: print "k8s-context=none".
- **Multiple AWS profiles**: only the active one is reported. Acceptable.
- **Slow STS call**: cache the result for 1 hour. If `~/.cache/claude-code/aws-context` is fresh, reuse.

### Exit codes
Always `0`.

---

## 20. current-step-detector

### Purpose
Removes the friction of "where was I?" at the start of each session. Scans `build-plan.md` for the first unchecked step and offers to load it.

### When it fires
Session start.

### What it does
Reads `build-plan.md`, finds the first `- [ ]` line, identifies the step ID and title, prints a context line that Claude Code can act on.

### Implementation skeleton

```bash
#!/usr/bin/env bash
# .claude/hooks/session-start/current-step-detector
set -euo pipefail

PLAN_FILE="${BUILD_PLAN_PATH:-build-plan.md}"
[ -f "$PLAN_FILE" ] || exit 0

# Find first unchecked step (line with "- [ ]")
LINE=$(grep -n "^- \[ \]" "$PLAN_FILE" | head -1 || true)
[ -z "$LINE" ] && exit 0

LINE_NUM=$(echo "$LINE" | cut -d: -f1)
# Look upward for the most recent "### Step X.Y: ..." header
STEP_HEADER=$(head -n "$LINE_NUM" "$PLAN_FILE" | grep -E "^### Step " | tail -1 || true)
[ -z "$STEP_HEADER" ] && exit 0

STEP_ID=$(echo "$STEP_HEADER" | grep -oE "Step [0-9]+\.[0-9]+" | sed 's/Step //')
STEP_TITLE=$(echo "$STEP_HEADER" | sed -E 's/^### Step [0-9]+\.[0-9]+: //')

echo "First unchecked step is $STEP_ID: $STEP_TITLE"
echo "Run /build-step $STEP_ID to begin, or /next-step for options."
exit 0
```

### Edge cases
- **No `build-plan.md`** (early-stage project): silently skip.
- **All steps complete**: print "All build steps complete!"
- **Multi-line step titles**: only the first line is captured.
- **Step IDs in non-standard format**: regex assumes `X.Y`; tune if format differs.

### Exit codes
Always `0`.

---

# Session-end Hooks

## 21. commit-progress-marker

### Purpose
Catches the case where the developer finishes implementing a step but forgets to mark it done in `build-plan.md`. This hook fires at session end and prompts if there are signs that a step was completed without the checkbox flip.

### When it fires
Session end.

### What it does
1. Check if any commits in the current branch reference a step ID in their message (e.g., `feat(order-service): step 8.4 - ...`).
2. For each referenced step, check whether `- [ ]` for that step is still present in `build-plan.md`.
3. If yes: prompt "Step X.Y has commits but isn't marked done. Update `build-plan.md`?"

### Implementation skeleton

```bash
#!/usr/bin/env bash
# .claude/hooks/session-end/commit-progress-marker
set -euo pipefail

# Get commits since branch diverged from main
COMMITS=$(git log main..HEAD --pretty=format:%s 2>/dev/null || true)
[ -z "$COMMITS" ] && exit 0

PLAN_FILE="${BUILD_PLAN_PATH:-build-plan.md}"
[ -f "$PLAN_FILE" ] || exit 0

# Extract step IDs from commit subjects: "step 8.4", "Step 8.4"
STEP_IDS=$(echo "$COMMITS" | grep -oiE "step [0-9]+\.[0-9]+" | sed 's/step //I' | sort -u || true)
[ -z "$STEP_IDS" ] && exit 0

UNCHECKED=()
for sid in $STEP_IDS; do
  # Find the step in the plan; check if the next [ ] follows the header
  if grep -A 2 "^### Step $sid:" "$PLAN_FILE" | grep -q "^- \[ \]"; then
    UNCHECKED+=("$sid")
  fi
done

[ ${#UNCHECKED[@]} -eq 0 ] && exit 0

echo "" >&2
echo "ℹ The following steps have commits but aren't marked done in $PLAN_FILE:" >&2
for sid in "${UNCHECKED[@]}"; do
  echo "  - Step $sid" >&2
done
echo "" >&2
echo "Update them with:" >&2
echo "  sed -i 's/^- \[ \] \*\*Objective\*\*: <step text>/- [x] **Objective**: <step text>/' $PLAN_FILE" >&2
echo "Or edit manually." >&2
exit 0
```

### Edge cases
- **Step partially implemented**: a commit might reference a step that's only partly done. The hook can't tell — it's informational only.
- **Different commit message formats**: tune the regex (`step X.Y`, `Step X.Y`, `step-X.Y`) based on project convention.
- **Multiple commits per step**: deduplicated by `sort -u`.
- **No `main` branch**: use `origin/main` or whatever the project's default branch is; configurable via env var.

### Exit codes
Always `0` — informational only.

---

# Hooks Configuration

## `.claude/settings.json` (project-level configuration)

```json
{
  "hooks": {
    "preCommit": [
      "enforce-conventions",
      "validate-build-step-completion",
      "flyway-migration-safety",
      "terraform-fmt-check",
      "proto-breaking-change",
      "outbox-event-schema-check",
      "iam-policy-overbroad",
      "secret-scanner",
      "kafka-schema-compat-check",
      "dockerfile-best-practices",
      "branch-naming-enforcement"
    ],
    "preToolUse": [
      "protect-production",
      "confirm-data-mutation",
      "block-cross-account-mistake"
    ],
    "postToolUse": [
      "auto-format",
      "regenerate-grpc-stubs",
      "update-bom-on-dep-change",
      "cost-budget-warning"
    ],
    "sessionStart": [
      "aws-context-snapshot",
      "current-step-detector"
    ],
    "sessionEnd": [
      "commit-progress-marker"
    ]
  }
}
```

## Git pre-commit chain integration

For pre-commit hooks, in addition to Claude Code's hook system, you may want them in a real `.git/hooks/pre-commit` so they fire on plain `git commit` (not just Claude-Code-driven commits). One approach: wrap them in [pre-commit framework](https://pre-commit.com/) and use both:

```yaml
# .pre-commit-config.yaml
repos:
  - repo: local
    hooks:
      - id: enforce-conventions
        name: enforce-conventions
        entry: .claude/hooks/pre-commit/enforce-conventions
        language: script
        pass_filenames: false
      # ... etc for each hook
```

This makes hooks effective regardless of whether the commit comes from Claude Code or directly.

---

# Recommended Build Order for Hooks

Tier these by risk-reduction value:

**Day 1 (highest-leverage 4 hooks):**
1. protect-production
2. enforce-conventions
3. secret-scanner
4. validate-build-step-completion

**Week 1 (round out the safety net):**
5. flyway-migration-safety
6. iam-policy-overbroad
7. confirm-data-mutation
8. block-cross-account-mistake

**As phases reach them:**
9. proto-breaking-change (Phase 1+)
10. outbox-event-schema-check (Phase 2+)
11. kafka-schema-compat-check (Phase 2+)
12. dockerfile-best-practices (Phase 2+)
13. branch-naming-enforcement (Day 1, but lowest priority)
14. terraform-fmt-check (Phase 0)

**Quality of life (whenever):**
15. auto-format
16. regenerate-grpc-stubs
17. update-bom-on-dep-change
18. cost-budget-warning
19. aws-context-snapshot
20. current-step-detector
21. commit-progress-marker

---

*End of hook-specs.md.*
