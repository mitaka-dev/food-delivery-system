---
name: commit
description: Stage all changes, generate a conventional commit message, commit, and push to origin/main
disable-model-invocation: true
allowed-tools: Bash(git *)
---

Commit and push all current changes to `origin/main`.

## Steps

1. **Inspect the working tree:**
   ```bash
   git status
   git diff
   git log --oneline -5
   ```

2. **Stage all non-sensitive files.** Never stage `.env`, `*.secret`, `*.key`, or credential files. Stage everything else:
   ```bash
   git add .
   git reset HEAD .env 2>/dev/null || true
   ```

3. **Generate a commit message** using [Conventional Commits](https://www.conventionalcommits.org/) format:
   - `feat:` — new feature or capability
   - `fix:` — bug fix
   - `chore:` — tooling, config, dependency updates
   - `docs:` — documentation only
   - `refactor:` — code restructure with no behaviour change
   - `test:` — adding or updating tests

   Write a concise subject line (≤72 chars). Add a body only if the change needs context that isn't obvious from the diff. Always append the Co-Authored-By trailer.

4. **Commit:**
   ```bash
   git commit -m "$(cat <<'EOF'
   <message here>

   Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
   EOF
   )"
   ```

5. **Push:**
   ```bash
   git push origin main
   ```

6. **Confirm** by printing the commit SHA and a one-line summary:
   ```bash
   git log --oneline -1
   ```
