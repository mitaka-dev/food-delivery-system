---
description: Execute a build step from BUILD-PLAN.md
argument-hint: <step-id, or omit for next unchecked>
---

You're starting work on a build step from `docs/plan/BUILD-PLAN.md`.

Step ID: $ARGUMENTS

The project status (loaded via SessionStart hook) tells you the next
planned step and last completed step. If $ARGUMENTS is empty, use the
next planned step.

Workflow:

1. Read the step from `docs/plan/BUILD-PLAN.md`: objective, tasks,
   acceptance criteria, dependencies and everything else for this step.
   If the step's checkbox is already `- [x]`, stop immediately — tell the
   user it's done and show the next unchecked step instead.
2. Briefly restate the objective in your own words and list the
   skills/conventions that apply (e.g., `spring-boot-service-conventions`,
   `saga-state-machine`, whichever match the file paths and topic).
3. Propose the implementation approach in a few bullets and wait for confirmation.
4. Implement everything after confirmation.
5. Verify the acceptance criteria. Run automatable ones (`mvn verify`,
   coverage, migrations apply). For manual ones, ask the user to confirm.
6. When all criteria pass:
   - Flip `- [ ]` to `- [x]` for this step in `BUILD-PLAN.md`.
   - Update `docs/plan/STATUS.md`: increment the step count, move this step
     into the Completed Steps section, and update the Next Step block to
     reflect the next unchecked step.

If $ARGUMENTS doesn't match a step ID in `BUILD-PLAN.md` (e.g. a
free-form feature request), stop and suggest `/feature-dev` instead.
