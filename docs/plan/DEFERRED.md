# Deferred Items & Known Gaps

Things that were intentionally deferred, skipped for later, or identified as incomplete during a phase review.
Work through this list before considering the platform production-ready.

Each item records **what is missing**, **why it was deferred**, and **when to address it**.

---

## Phase 0 — Foundation & Infrastructure

### D-01 · `scripts/bootstrap-kafka-topics.sh` does not exist

**What:** `platform-infra/modules/msk/topics.tf` references a script
`scripts/bootstrap-kafka-topics.sh` that is supposed to be run once from an EKS pod
after `terraform apply` to create the v1 Kafka topics with explicit configs
(partition count, retention). The script was never created.

**Why deferred:** MSK Serverless auto-creates topics on first produce, so services
won't fail immediately. The missing script only matters when explicit topic
configuration (partition count, retention) is required.

**When to address:** Before Phase 6 (order-service). `order-events` requires
6 partitions for per-orderId ordering guarantees and 14-day retention. Applying
default auto-created config will produce a topic with wrong settings.

**What needs to be done:**
- Create `platform-infra/scripts/bootstrap-kafka-topics.sh`
- Run from an EKS pod (or bastion) with MSK IAM access after `terraform apply`
- Topics to create with explicit settings:
  - `user-events` — 3 partitions, retention 7 days, key=userId
  - `order-events` — 6 partitions, retention 14 days, key=orderId
  - `payment-events` — 3 partitions, retention 30 days (audit), key=orderId

---

### D-02 · ExternalDNS and External Secrets Operator not installed

**What:** Step 0.3 created IRSA roles for ExternalDNS and External Secrets Operator
(ESO). Step 0.11's `install-argocd.sh` installs AWS LBC, ArgoCD, and Argo Rollouts —
but not ExternalDNS or ESO. The controllers don't exist in the cluster.

**Why deferred:** Not needed until the first service is deployed to EKS.

**When to address:** At the start of **Step 2.5** (user-service K8s deploy) — before
applying any service manifests. Without ESO, `ExternalSecret` resources can't pull
secrets from Secrets Manager. Without ExternalDNS, Route53 records won't be created
from `Ingress` annotations.

**What needs to be done:**
Add to `install-argocd.sh` (or create a separate `install-controllers.sh`):

```bash
# External Secrets Operator
helm upgrade --install external-secrets external-secrets/external-secrets \
  -n external-secrets --create-namespace \
  --set serviceAccount.annotations."eks\.amazonaws\.com/role-arn"=\
"arn:aws:iam::${ACCOUNT_ID}:role/${CLUSTER_NAME}-external-secrets-irsa"

# ExternalDNS
helm upgrade --install external-dns bitnami/external-dns \
  -n external-dns --create-namespace \
  --set provider=aws \
  --set serviceAccount.annotations."eks\.amazonaws\.com/role-arn"=\
"arn:aws:iam::${ACCOUNT_ID}:role/${CLUSTER_NAME}-external-dns-irsa"
```

---

### D-03 · CoreDNS Fargate annotation patch not documented

**What:** On pure-Fargate EKS clusters, the CoreDNS Deployment carries an annotation
`eks.amazonaws.com/compute-type: ec2` that prevents pods scheduling on Fargate.
The `addons.tf` comment calls this a "one-time manual step documented in post-apply
instructions" — those instructions were never written.

**Why deferred:** Terraform's synchronous `aws_eks_addon` apply would deadlock waiting
for CoreDNS to become ACTIVE, so the CoreDNS addon is not managed in Terraform at all.
EKS manages it as a built-in; the annotation just needs patching once.

**When to address:** Immediately after the first `terraform apply` on the EKS cluster,
before any workloads are scheduled. Without this patch, in-cluster DNS resolution fails
for all pods.

**What needs to be done:**
Run once after `terraform apply` (from a machine with kubectl access):

```bash
kubectl patch deployment coredns -n kube-system \
  --type=json \
  -p='[{"op":"remove","path":"/spec/template/metadata/annotations/eks.amazonaws.com~1compute-type"}]'

kubectl rollout restart deployment/coredns -n kube-system
kubectl rollout status deployment/coredns -n kube-system
```

Add this step to `platform-infra/scripts/install-argocd.sh` (it must run before
`helm install argocd`, since ArgoCD itself needs DNS).

---

### D-04 · Avro schemas use `double` for monetary amounts

**What:** The Glue Schema Registry schemas in `platform-infra/modules/msk/topics.tf`
use `double` for `PaymentEvent.amount` and `OrderEvent.totalAmount`. The build plan
explicitly requires "Money uses BigDecimal... never double" (Step 1.2 key details).

**Why deferred:** The Glue schemas were bootstrapped to get the registry in place.
The Java event records (with proper `BigDecimal`) are created in Step 1.2 — the
Avro schemas must be kept in sync with them.

**When to address:** **Step 1.2** (common-events module). When the `.avsc` files are
created alongside the Java event records, update the Glue schemas in `topics.tf` to
use the Avro `decimal` logical type:

```json
{ "type": "bytes", "logicalType": "decimal", "precision": 10, "scale": 2 }
```

Both `PaymentEvent.amount` and `OrderEvent.totalAmount` need this change.
A `terraform apply` after the change will update the schemas in the Glue Registry
(BACKWARD compatibility is preserved: adding a logical type annotation is non-breaking
since the underlying wire type stays `bytes`).

---

## Phase 1 — Shared Libraries & Platform BOM

*(none yet)*

---

## Phase 2 — User Service

### D-05 · JWT key lifecycle is Terraform-owned; `terraform destroy` rotates the key

**What:** The RS256 private key for JWT issuance is generated by a `tls_private_key`
Terraform resource and stored in Secrets Manager via `aws_secretsmanager_secret_version`
(see `platform-infra/envs/production/user-service-irsa.tf`). This means:
- The private key material lives in the Terraform state file (encrypted, but still there).
- Running `terraform destroy` + `terraform apply` generates a brand-new keypair,
  immediately invalidating all active refresh tokens for every logged-in user.

**Why deferred:** Acceptable during the pilot phase (no real users, no real data).
Terraform ownership is the simplest way to bootstrap the key before first deploy.

**When to address:** **Phase 10 (Production Hardening)** — before opening to real
customer traffic.

**What needs to be done:**
1. Remove the `tls_private_key` resource and `aws_secretsmanager_secret_version` for
   the JWT key from `user-service-irsa.tf`. Keep the `aws_secretsmanager_secret`
   resource (the shell stays Terraform-managed; only the key material moves out).
2. Create a Secrets Manager **rotation Lambda** (Python or Node, ~50 lines):
   - Generates a new RSA-2048 keypair on the configured schedule (e.g. 90 days).
   - Writes the new private key to the SM secret.
   - Publishes the new public key to the SSM path
     `/food-delivery/user-service/jwt-public-key`.
3. Update `user-service`'s `JwtPublicKeyResolver` to re-read the SSM parameter on
   a short TTL (e.g. every 5 minutes via `@Scheduled`) rather than only at startup.
   This ensures the API Gateway Lambda authorizer and other verifiers pick up the
   rotated public key without a rolling restart.
4. During the rotation window, keep the old public key valid for the remaining
   lifetime of already-issued access tokens (max 15 minutes) before removing it.
   This avoids a brief 401 storm during rotation.

**Key invariant to preserve:** refresh tokens survive rotation — they're opaque
DB-backed tokens, not JWT-signed. Only access tokens (15-min TTL) are affected,
and only those issued in the ~15-minute overlap window.

---

## Future phases

*(items will be added here as phases are reviewed)*
