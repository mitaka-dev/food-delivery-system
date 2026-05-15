# CLAUDE.md
Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.
**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding
**Don't assume. Don't hide confusion. Surface tradeoffs.**
Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First
**Minimum code that solves the problem. Nothing speculative.**
- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.
Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes
**Touch only what you must. Clean up only your own mess.**
When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.
When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.
The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution
**Define success criteria. Loop until verified.**
Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"
For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.
**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

## Project Context
> **Architecture, services, tech stack, Kafka topics, gateway routes, JWT design, database, and observability** are in `docs/PROJECT.md`. Read it at session start or when working on infrastructure/config changes.

## API Guidelines
> **Before touching any HTTP controller, DTO, or gateway route — read `docs/API_PRINCIPLES.md`.**
> It is the rubric every HTTP change is reviewed against. Current gap status is in `docs/API_AUDIT.md`; remediation tasks are tracked in `docs/PLAN.md` under "API Hardening".

## Project Plan & Progress
> **Session start:** Read `docs/PLAN.md` to know what is done and what is left.
> **During the session:** Update `docs/PLAN.md` checkboxes as features are completed.
> **Session end:** When the user says anything like "we're done", "goodbye", "end session", or "wrap up" — review and update both `docs/PLAN.md` and `CLAUDE.md` before responding, then confirm both have been updated.

## Commands

| Command | Description |
|---------|-------------|
| `./start.sh` | Full startup: generates secrets, builds all services, health-checks |
| `./mvnw test` | Run all tests |
| `./mvnw test -pl <service>` | Run tests for a single service (e.g. `-pl order-service`) |

## Gotchas

- `JWT_SECRET` env var must be set before starting gateway/user services (`start.sh` handles this automatically)
- Payment simulation: any order amount > 500 → FAILED (intentional test behavior, not a bug)
- `product-service` uses optimistic locking (`@Version`) — concurrent stock updates may throw `OptimisticLockingFailureException`
- Kafka Saga ordering: `order-topics` → product-service + payment-service → `order-confirmation-topic` → order status update

## Skills

| Skill | When to use |
|-------|-------------|
| `/plan-session` | Session start — reads PLAN.md, shows what's done and what's next |
| `/logs [service]` | Tail logs for a specific service |
| `/health` | Check health of all running services without starting anything |