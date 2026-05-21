#!/usr/bin/env bash
# Publishes platform-bom (and optionally platform-shared-libs modules) to CodeArtifact.
# Usage: ./scripts/publish-bom.sh [--skip-shared-libs]
# Requires: CODEARTIFACT_TOKEN and CODEARTIFACT_URL (run codeartifact-login.sh first)
set -euo pipefail

: "${CODEARTIFACT_TOKEN:?Run 'source scripts/codeartifact-login.sh' first}"
: "${CODEARTIFACT_URL:?Run 'source scripts/codeartifact-login.sh' first}"

SKIP_SHARED_LIBS=false
if [[ "${1:-}" == "--skip-shared-libs" ]]; then
  SKIP_SHARED_LIBS=true
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Publishing platform-bom to CodeArtifact..."
./mvnw -B deploy \
  -pl platform-bom \
  -DskipTests \
  -s .mvn/settings.xml \
  -Dmaven.repo.local="$REPO_ROOT/.m2/repository"

if [[ "$SKIP_SHARED_LIBS" == "false" ]]; then
  echo "Publishing platform-shared-libs modules to CodeArtifact..."
  ./mvnw -B deploy \
    -pl platform-shared-libs,common-libs \
    -am \
    -DskipTests \
    -s .mvn/settings.xml \
    -Dmaven.repo.local="$REPO_ROOT/.m2/repository"
fi

echo "Publish complete."
