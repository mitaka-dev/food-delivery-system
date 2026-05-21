#!/usr/bin/env bash
# Fetches a short-lived CodeArtifact token and exports it + the repo URL.
# Source this script: `source scripts/codeartifact-login.sh`
# Required: AWS_REGION, CODEARTIFACT_DOMAIN, CODEARTIFACT_DOMAIN_OWNER, CODEARTIFACT_REPO
set -euo pipefail

: "${AWS_REGION:?AWS_REGION must be set}"
: "${CODEARTIFACT_DOMAIN:?CODEARTIFACT_DOMAIN must be set}"
: "${CODEARTIFACT_DOMAIN_OWNER:?CODEARTIFACT_DOMAIN_OWNER must be set}"
: "${CODEARTIFACT_REPO:?CODEARTIFACT_REPO must be set}"

export CODEARTIFACT_TOKEN
CODEARTIFACT_TOKEN=$(aws codeartifact get-authorization-token \
  --domain "$CODEARTIFACT_DOMAIN" \
  --domain-owner "$CODEARTIFACT_DOMAIN_OWNER" \
  --region "$AWS_REGION" \
  --query authorizationToken \
  --output text)

export CODEARTIFACT_URL
CODEARTIFACT_URL="https://${CODEARTIFACT_DOMAIN}-${CODEARTIFACT_DOMAIN_OWNER}.d.codeartifact.${AWS_REGION}.amazonaws.com/maven/${CODEARTIFACT_REPO}/"

echo "CodeArtifact login successful. Token valid for 12 hours."
echo "CODEARTIFACT_URL=${CODEARTIFACT_URL}"
