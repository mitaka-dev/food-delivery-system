#!/usr/bin/env bash
# Idempotent developer bootstrap. Run once after cloning.
# Usage: ./scripts/bootstrap-dev.sh
set -euo pipefail

REQUIRED_JAVA="corretto-25"
REQUIRED_MAVEN="3.9.9"
REQUIRED_TF="1.7.5"
REQUIRED_KUBECTL="1.30.7"
REQUIRED_HELM="3.15.4"

info()  { echo "[INFO]  $*"; }
warn()  { echo "[WARN]  $*"; }
ok()    { echo "[OK]    $*"; }

# --- asdf (version manager) ---
if ! command -v asdf &>/dev/null; then
  warn "asdf not found. Install it first: https://asdf-vm.com/guide/getting-started.html"
  exit 1
fi

info "Installing tool versions from .tool-versions..."
asdf plugin add java       2>/dev/null || true
asdf plugin add maven      2>/dev/null || true
asdf plugin add terraform  2>/dev/null || true
asdf plugin add kubectl    2>/dev/null || true
asdf plugin add helm       2>/dev/null || true
asdf install
ok "All tool versions installed"

# --- AWS CLI ---
if ! command -v aws &>/dev/null; then
  warn "AWS CLI not found. Install: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"
else
  ok "AWS CLI: $(aws --version 2>&1 | head -1)"
fi

# --- direnv ---
if ! command -v direnv &>/dev/null; then
  warn "direnv not found (optional but recommended). Install: https://direnv.net/docs/installation.html"
else
  ok "direnv: $(direnv version)"
fi

# --- CodeArtifact login (requires AWS credentials + Phase 0.8 to be complete) ---
if command -v aws &>/dev/null && [ -f ".envrc" ] && grep -q CODEARTIFACT_AUTH_TOKEN .envrc 2>/dev/null; then
  info "Logging in to CodeArtifact..."
  aws codeartifact login --tool maven \
    --domain food-delivery-platform \
    --repository internal 2>/dev/null && ok "CodeArtifact login successful" \
    || warn "CodeArtifact login failed — run Phase 0.8 first, or check AWS_PROFILE"
else
  info "Skipping CodeArtifact login (Phase 0.8 not yet complete or .envrc not configured)"
fi

echo ""
ok "Bootstrap complete. Run 'docker compose up -d' to start local dependencies."
