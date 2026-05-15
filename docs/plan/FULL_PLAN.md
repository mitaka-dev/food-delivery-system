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

**Part A — Reference Documentation** - check "Reference Documentation.md" file
**Part B — Build Steps** - check "Build Steps.md" file