#!/usr/bin/env bash
# install-argocd.sh — Bootstrap ArgoCD on the food-delivery EKS cluster.
#
# Run once from a workstation after `terraform apply` has completed in
# platform-infra/envs/production. Requires: aws cli, helm 3, kubectl, jq.
#
# What this script does:
#   1. Updates kubeconfig for the EKS cluster
#   2. Fetches OIDC parameters and SSH credentials from SSM / Secrets Manager
#   3. Installs the AWS Load Balancer Controller (prerequisite for ALB ingress)
#   4. Installs ArgoCD via Helm, creating the OIDC and repo secrets first
#   5. Installs Argo Rollouts (enables canary deploys in Phase 8.4)
#   6. Applies the AppProject and root App-of-Apps Application

set -euo pipefail

REGION="${REGION:-eu-west-1}"
CLUSTER_NAME="${CLUSTER_NAME:-food-delivery-production-eks}"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"

ARGOCD_NAMESPACE="argocd"
ARGOCD_CHART_VERSION="${ARGOCD_CHART_VERSION:-7.4.4}"         # ArgoCD 2.10.x
ROLLOUTS_CHART_VERSION="${ROLLOUTS_CHART_VERSION:-2.37.3}"    # Argo Rollouts 1.7.x
LBC_CHART_VERSION="${LBC_CHART_VERSION:-1.8.3}"               # AWS LBC 2.8.x

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GITOPS_DIR="${SCRIPT_DIR}/../../../food-delivery-gitops"

check_prereqs() {
  local missing=()
  for cmd in aws helm kubectl jq; do
    command -v "${cmd}" &>/dev/null || missing+=("${cmd}")
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    echo "ERROR: missing required tools: ${missing[*]}" >&2
    exit 1
  fi
}

# ── Step 1: kubeconfig ────────────────────────────────────────────────────────

update_kubeconfig() {
  echo "==> Updating kubeconfig for ${CLUSTER_NAME}"
  aws eks update-kubeconfig --region "${REGION}" --name "${CLUSTER_NAME}"
}

# ── Step 2: fetch secrets ─────────────────────────────────────────────────────

fetch_secrets() {
  echo "==> Fetching OIDC parameters from SSM"
  OIDC_ISSUER="$(aws ssm get-parameter \
    --name /food-delivery/argocd/oidc-issuer \
    --query 'Parameter.Value' --output text --region "${REGION}")"
  OIDC_CLIENT_ID="$(aws ssm get-parameter \
    --name /food-delivery/argocd/oidc-client-id \
    --query 'Parameter.Value' --output text --region "${REGION}")"
  OIDC_CLIENT_SECRET="$(aws ssm get-parameter \
    --name /food-delivery/argocd/oidc-client-secret \
    --with-decryption --query 'Parameter.Value' --output text --region "${REGION}")"

  echo "==> Fetching gitops SSH credentials from Secrets Manager"
  SSH_SECRET="$(aws secretsmanager get-secret-value \
    --secret-id /food-delivery/argocd/gitops-ssh-key \
    --query 'SecretString' --output text --region "${REGION}")"
  CODECOMMIT_URL="$(echo "${SSH_SECRET}" | jq -r '.codecommit_url')"
  SSH_PRIVATE_KEY="$(echo "${SSH_SECRET}" | jq -r '.private_key')"
}

# ── Step 3: AWS Load Balancer Controller ──────────────────────────────────────

install_lbc() {
  echo "==> Installing AWS Load Balancer Controller"

  local lbc_role_arn="arn:aws:iam::${ACCOUNT_ID}:role/${CLUSTER_NAME}-lbc-irsa"

  helm repo add eks https://aws.github.io/eks-charts
  helm repo update

  kubectl create namespace kube-system --dry-run=client -o yaml | kubectl apply -f -

  helm upgrade --install aws-load-balancer-controller eks/aws-load-balancer-controller \
    --namespace kube-system \
    --version "${LBC_CHART_VERSION}" \
    --set "clusterName=${CLUSTER_NAME}" \
    --set "serviceAccount.create=true" \
    --set "serviceAccount.name=aws-load-balancer-controller" \
    --set "serviceAccount.annotations.eks\\.amazonaws\\.com/role-arn=${lbc_role_arn}" \
    --set "region=${REGION}" \
    --set "vpcId=$(aws eks describe-cluster --name "${CLUSTER_NAME}" --query 'cluster.resourcesVpcConfig.vpcId' --output text --region "${REGION}")" \
    --wait --timeout 5m
}

# ── Step 4: ArgoCD ────────────────────────────────────────────────────────────

install_argocd() {
  echo "==> Installing ArgoCD ${ARGOCD_CHART_VERSION}"

  helm repo add argo https://argoproj.github.io/argo-helm
  helm repo update

  kubectl create namespace "${ARGOCD_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

  # Secret for OIDC client secret — referenced as $oidc.cognito.clientSecret in argocd-cm
  kubectl create secret generic argocd-oidc-secret \
    --namespace "${ARGOCD_NAMESPACE}" \
    --from-literal="oidc.cognito.clientSecret=${OIDC_CLIENT_SECRET}" \
    --dry-run=client -o yaml | kubectl apply -f -

  # Repo secret — ArgoCD discovers this via the argocd.argoproj.io/secret-type label
  kubectl create secret generic argocd-repo-gitops-ssh \
    --namespace "${ARGOCD_NAMESPACE}" \
    --from-literal="url=${CODECOMMIT_URL}" \
    --from-literal="sshPrivateKey=${SSH_PRIVATE_KEY}" \
    --dry-run=client -o yaml | kubectl apply -f -

  kubectl label secret argocd-repo-gitops-ssh \
    --namespace "${ARGOCD_NAMESPACE}" \
    "argocd.argoproj.io/secret-type=repository" \
    --overwrite

  # Merge values.yaml with the runtime OIDC config (issuer + clientID vary per deployment)
  local tmp_values
  tmp_values="$(mktemp)"
  cat > "${tmp_values}" <<OIDCEOF
configs:
  cm:
    oidc.config: |
      name: Cognito
      issuer: ${OIDC_ISSUER}
      clientID: ${OIDC_CLIENT_ID}
      clientSecret: \$oidc.cognito.clientSecret
      requestedScopes:
        - openid
        - profile
        - email
      requestedIDTokenClaims:
        email:
          essential: true
OIDCEOF

  helm upgrade --install argocd argo/argo-cd \
    --namespace "${ARGOCD_NAMESPACE}" \
    --version "${ARGOCD_CHART_VERSION}" \
    --values "${GITOPS_DIR}/argocd/install/values.yaml" \
    --values "${tmp_values}" \
    --wait --timeout 10m

  rm -f "${tmp_values}"
}

# ── Step 5: Argo Rollouts ─────────────────────────────────────────────────────

install_rollouts() {
  echo "==> Installing Argo Rollouts ${ROLLOUTS_CHART_VERSION}"

  kubectl create namespace argo-rollouts --dry-run=client -o yaml | kubectl apply -f -

  helm upgrade --install argo-rollouts argo/argo-rollouts \
    --namespace argo-rollouts \
    --version "${ROLLOUTS_CHART_VERSION}" \
    --set "controller.replicas=1" \
    --wait --timeout 5m
}

# ── Step 6: bootstrap Applications ───────────────────────────────────────────

apply_bootstrap() {
  echo "==> Applying AppProject 'services'"
  kubectl apply -f "${GITOPS_DIR}/argocd/projects/services.yaml"

  echo "==> Applying root App-of-Apps Application"
  # Substitute the live CodeCommit SSH URL into the template manifest
  sed "s|__CODECOMMIT_URL__|${CODECOMMIT_URL}|g" \
    "${GITOPS_DIR}/argocd/applications/_app-of-apps.yaml" \
    | kubectl apply -f -
}

# ── Main ──────────────────────────────────────────────────────────────────────

main() {
  check_prereqs
  update_kubeconfig
  fetch_secrets
  install_lbc
  install_argocd
  install_rollouts
  apply_bootstrap

  echo ""
  echo "==> Bootstrap complete."
  echo "    ArgoCD UI: https://argocd.internal.food-delivery-platform.io"
  echo "    (accessible only from within the VPC or via VPN)"
  echo "    Port-forward fallback: kubectl port-forward svc/argocd-server -n argocd 8080:80"
}

main "$@"
