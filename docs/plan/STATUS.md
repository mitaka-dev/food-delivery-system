# Project Status

**Last updated:** 2026-05-19

## Current Situation

The local development system is complete — 11 services running on Docker Compose (see `docs/PROJECT.md`). The production AWS build plan (`docs/plan/BUILD-PLAN.md`) has not been started; all checkboxes are unchecked.

## Where We Are

- **Local system:** Complete. All services built and working.
- **AWS build plan:** Not started (Phase 0 through Phase 15 all unchecked). Plan significantly revised 2026-05-19 — see notes below.
- **API Hardening:** Gaps identified in `docs/API_AUDIT.md` — not yet remediated on local services. Audit gap remediation is now formally integrated into the AWS build plan (Steps 2.6, 6.5, 8.12, 11.5, 14.0) so the production code will be built correctly from scratch.

## BUILD-PLAN.md Changes (2026-05-19)

The plan was substantially revised today:
- **New "Build Strategy" section** — Spring profile strategy (`local` / `staging` / `production`), pilot-first sequencing (user-service goes end-to-end before the remaining 9 services), and detailed rationale for the two-repo layout.
- **Total sessions: ~91** (was 85) — 6 new steps added: 2.6 (user-service audit gaps), 2.7 (deploy template checkpoint), 6.5 (basket audit gaps), 8.12 (order-service audit gaps), 11.5 (review audit gaps), 14.0 (per-service test scaffolding).
- **`common-libs` naming used throughout** — an intermediate rename to `platform-shared-libs` was reverted; all file paths in the plan use `common-libs/`.
- **Phase 5 renamed** to "Product (Restaurant Menu) Service" (was "Restaurant Menu Service").
- **Steps 12.1 + 13.1–13.3 pulled forward** into the Phase 2 pilot — user-service ships with full observability and CI/CD before the remaining services are built.

## Next Step

Two open paths — pick one at the start of the session:

1. **API hardening (local)** — address gaps in `docs/API_AUDIT.md` on the existing local services (global exception handlers, request validation, idempotency keys, placeholder tests). Standalone — does not require starting the AWS build.
2. **AWS build plan** — start Phase 0, Step 0.1 (monorepo bootstrap & developer prerequisites).

## Key Files

- `docs/plan/BUILD-PLAN.md` — phased build steps with `- [ ]` / `- [x]` checkboxes (~91 steps)
- `docs/plan/ARCHITECTURE.md` — reference architecture (sections 1–10)
- `docs/API_AUDIT.md` — outstanding API gaps from 2026-04-17 audit
