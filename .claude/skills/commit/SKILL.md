---
name: commit
description: Stage all changes, generate a conventional commit message, and commit. Use when the user wants to commit, save their changes, create a commit, or checkpoint their work.
disable-model-invocation: true
allowed-tools: Bash(git *)
---

Stage and commit all current changes. Does not push.

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
   git ls-files --cached '*.secret' '*.key' | xargs -r git reset HEAD -- 2>/dev/null || true
   ```

3. **Generate a commit message** using [Conventional Commits](https://www.conventionalcommits.org/) format:
   - `feat:` — new feature or capability
   - `fix:` — bug fix
   - `chore:` — tooling, config, dependency updates
   - `docs:` — documentation only
   - `refactor:` — code restructure with no behaviour change
   - `test:` — adding or updating tests

   Write a concise subject line (≤72 chars). Add a body only if the change needs context that isn't obvious from the diff. Dont append the Co-Authored-By trailer.

4. **Commit:**
   ```bash
   git commit -m "$(cat <<'EOF'
   <message here>
   EOF
   )"
   ```