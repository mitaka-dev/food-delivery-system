# Food Ordering System — Build Plan for Claude Code

> **Purpose**: A step-by-step build guide for Claude Code (Claude Pro) to construct a production-grade food ordering microservices platform on AWS.
Each build step is sized to fit in a single Claude Pro session.

## How To Use This Plan

1. **Part A** (sections 1–10) is the reference architecture. Read it once before starting. Re-reference it from any step.
2. **Part B** is the build plan, organized into phases. Phases run in order; steps within a phase can sometimes run in parallel
(each step lists its dependencies).
3. **One step = one Claude Code session.** Open a fresh session, paste the step's full text as your prompt, and let Claude Code work through it.
Do not combine steps.
4. **Mark progress** by replacing `- [ ]` with `- [x]` after a step's acceptance criteria are met.
5. **If a session runs out of tokens mid-step**, save what you have, start a new session, and continue with the same step — do not skip ahead.
6. **Tech stack** (fixed across all steps):
   - Java 25 + Spring Boot 4.x
   - Maven (multi-module) + AWS CodeArtifact for shared libraries
   - PostgreSQL 16 (Aurora), DynamoDB on-demand, Redis (ElastiCache)
   - SNS + SQS for events, gRPC for low-latency internal sync
   - EKS Fargate for services, AWS Lambda for Notification
   - Terraform for infrastructure as code
   - ArgoCD 2.10+ for GitOps deployment
   - AWS CodePipeline + CodeBuild + CodeCommit for CI/CD

**Part A — Reference Documentation** - check `ARCHITECTURE.md`
**Part B — Build Steps** - check `BUILD-PLAN.md`

---

## Reference Specs

These files define what skills and hooks need to be built. Consult them when starting a new phase to know what tooling should be created alongside the feature work.

- **`docs/plan/SKILL-PROMPTS.md`** — 20 skill specs, tiered by phase. Tier 1 (foundational) should exist before Phase 0. Tier 2 and Tier 3 skills are created at the start of the phase that needs them. Each entry is a copy-paste-ready prompt for skill-creator.

- **`docs/plan/HOOK-SPECS.md`** — 21 hook specs across pre-commit, pre-tool-use, post-tool-use, session-start, and session-end lifecycle events. The "Recommended Build Order" section at the bottom prioritises which hooks to add first. Many hooks depend on infrastructure (Terraform, Schema Registry, gRPC) that only exists in later phases — check that dependency before implementing.