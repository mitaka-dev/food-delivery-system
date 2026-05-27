# Service Deploy Template

Captured from the user-service pilot (Steps 2.1–2.6). Follow this guide top-to-bottom to deploy
any new service to production EKS without reading the full build plan.

## Two-Repo Model

| Repo | Purpose |
|------|---------|
| `food-delivery-system` (this repo) | Application code, Terraform IaC, Dockerfiles, buildspec |
| `food-delivery-gitops` | Kustomize manifests, ArgoCD Application definitions |

CI writes a new image tag to the GitOps repo; ArgoCD detects the change and reconciles the cluster.
Never apply manifests directly with `kubectl` — ArgoCD is the single source of truth.

## Six-Phase Checklist

- [ ] **Phase 1 — Terraform:** IRSA role + Secrets Manager secrets
- [ ] **Phase 2 — Spring Boot:** production profile (`application-production.yml`)
- [ ] **Phase 3 — CI/CD:** `buildspec.yml` + path-filter Lambda wiring
- [ ] **Phase 4 — Kustomize:** base manifests + production overlay in GitOps repo
- [ ] **Phase 5 — ArgoCD:** child Application manifest in `argocd/applications/`
- [ ] **Phase 6 — Verify:** all checklist items in §7 pass

---

## Phase 1 — Terraform: IRSA + Secrets Manager

### 1.1 Create one `.tf` file per service

Location: `platform-infra/envs/production/{service-name}-irsa.tf`

Reference: `platform-infra/envs/production/user-service-irsa.tf`

### 1.2 IAM role naming convention

```
food-delivery-{env}-{service-name}-irsa
```

Example: `food-delivery-production-user-service-irsa`

### 1.3 Namespace mapping

Each service deploys to its own K8s namespace. The IRSA trust policy must use the **actual
namespace name**, not assumed patterns. Current namespace assignments:

| Service | K8s namespace |
|---------|--------------|
| user-service | `identity` |
| product-service | `product-service` |
| basket-service | `basket-service` |
| order-service | `order-service` |
| payment-service | `payment-service` |
| kitchen-service | `kitchen-service` |
| delivery-service | `delivery-service` |
| review-service | `review-service` |
| notification-service | `notification-service` |
| driver-service | `driver-service` |

### 1.4 IRSA trust policy template

```hcl
resource "aws_iam_role" "{service_name_underscored}_irsa" {
  name = "food-delivery-production-{service-name}-irsa"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = module.eks.oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${module.eks.oidc_provider_url}:sub" = "system:serviceaccount:{namespace}:{service-name}"
          "${module.eks.oidc_provider_url}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })

  tags = { Service = "{service-name}" }
}
```

> **Pitfall:** `oidc_provider_url` already strips the `https://` prefix. Do not add it back.

### 1.5 Standard permission blocks

Include only what the service actually uses. Copy from `user-service-irsa.tf`:

| Block | When to include |
|-------|----------------|
| `ReadServiceSecrets` (Secrets Manager) | Always — DB credentials at minimum |
| `JwtPublicKeySSM` (SSM get/put) | Only for identity services that issue JWTs |
| `MSKConnect` + `MSKTopics` + `MSKGroups` | Any service that produces or consumes Kafka |

### 1.6 Secrets Manager conventions

Path pattern: `food-delivery/production/{service-name}/{secret-name}`

Standard secrets per service:

```hcl
# DB credentials (all services with a database)
resource "aws_secretsmanager_secret" "{service}_db" {
  name       = "food-delivery/production/{service-name}/db"
  kms_key_id = aws_kms_key.sns_sqs.arn
}

resource "aws_secretsmanager_secret_version" "{service}_db" {
  secret_id     = aws_secretsmanager_secret.{service}_db.id
  secret_string = jsonencode({
    DB_URL      = "jdbc:postgresql://${module.rds.cluster_endpoint}:${module.rds.port}/{db_name}"
    DB_USERNAME = "{service}_user"
    DB_PASSWORD = "REPLACE_AFTER_TF_APPLY"
  })
}
```

> **After `terraform apply`:** update `DB_PASSWORD` in the Secrets Manager console (or via CLI)
> with the actual RDS user password created in step 2.1.

### 1.7 Terraform outputs to capture

After `terraform apply`, collect these ARNs — you'll need them in Phase 4:

```hcl
output "{service}_irsa_role_arn" {
  value = aws_iam_role.{service}_irsa.arn
}

output "{service}_db_secret_arn" {
  value = aws_secretsmanager_secret.{service}_db.arn
}
```

---

## Phase 2 — Spring Boot Production Profile

File: `services/{service-name}/src/main/resources/application-production.yml`

Reference: `services/user-service/src/main/resources/application-production.yml`

### 2.1 What goes in the production profile

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 30000

  flyway:
    enabled: true
    locations: classpath:db/migration/{service-name}

  jpa:
    hibernate:
      ddl-auto: validate   # never create/update in production
    show-sql: false

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
    properties:
      security.protocol: SASL_SSL
      sasl.mechanism: AWS_MSK_IAM
      sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
      sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler

  data:
    redis:                         # omit if service doesn't use Redis
      host: ${REDIS_HOST}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_AUTH_TOKEN}
      ssl:
        enabled: true

management:
  endpoint:
    health:
      probes:
        enabled: true
  tracing:
    sampling:
      probability: 0.1
  zipkin:
    tracing:
      endpoint: ${OTEL_EXPORTER_ZIPKIN_ENDPOINT:}
```

### 2.2 What stays in `application.yaml` (local only)

- `localhost` DB URLs (Docker Compose)
- Plaintext Kafka (`localhost:9092`, no SASL)
- Redis without TLS and without auth
- `ddl-auto: create-drop` or `update`

No hardcoded secrets anywhere. All production values come from env vars backed by K8s Secrets
(injected by ExternalSecrets Operator — see Phase 4).

### 2.3 Activating the profile

Set in the Kustomize ConfigMap patch (overlay):
```yaml
SPRING_PROFILES_ACTIVE: production
```

---

## Phase 3 — CI/CD: buildspec.yml

File: `services/{service-name}/buildspec.yml`

Reference: `services/user-service/buildspec.yml`

### 3.1 Template

```yaml
version: 0.2

env:
  variables:
    SERVICE_NAME: {service-name}
    SERVICE_PATH: services/{service-name}
  parameter-store:
    ECR_REPO_URI: /food-delivery/ecr/{service-name}/uri
    AWS_ACCOUNT_ID: /food-delivery/aws-account-id

phases:
  pre_build:
    commands:
      - aws ecr get-login-password --region $AWS_DEFAULT_REGION | docker login --username AWS --password-stdin $ECR_REPO_URI
      - export CODEARTIFACT_AUTH_TOKEN=$(aws codeartifact get-authorization-token --domain food-delivery-platform --domain-owner $AWS_ACCOUNT_ID --query authorizationToken --output text)
      - IMAGE_TAG=$(echo $CODEBUILD_RESOLVED_SOURCE_VERSION | cut -c1-7)

  build:
    commands:
      - ./mvnw verify -pl $SERVICE_PATH -am -s settings.xml -Pcodeartifact
      - docker build --build-arg SERVICE_PATH=$SERVICE_PATH -t $SERVICE_NAME:$IMAGE_TAG $SERVICE_PATH

  post_build:
    commands:
      - docker tag $SERVICE_NAME:$IMAGE_TAG $ECR_REPO_URI:$IMAGE_TAG
      - docker tag $SERVICE_NAME:$IMAGE_TAG $ECR_REPO_URI:latest
      - docker push $ECR_REPO_URI:$IMAGE_TAG
      - docker push $ECR_REPO_URI:latest
      - printf '[{"name":"%s","imageUri":"%s"}]' $SERVICE_NAME $ECR_REPO_URI:$IMAGE_TAG > imagedefinitions.json

artifacts:
  files:
    - imagedefinitions.json
```

### 3.2 Path-filter Lambda wiring

The CI/CD pipeline uses a path-filter Lambda to trigger only the affected service's CodeBuild
project. Add the new service's source path to the Lambda's routing config:

```
services/{service-name}/**  →  codebuild-{service-name}
```

(The Lambda config lives in the CodePipeline Terraform module; check `platform-infra/modules/`
for the routing map.)

### 3.3 Notes

- **CodeArtifact token must be fetched every build** — it has a short TTL and is not cached across runs.
- `ECR_REPO_URI` is read from SSM Parameter Store at build time, not hardcoded.
- `-am` in the Maven command builds upstream modules (common-libs, etc.) from source.

---

## Phase 4 — Kustomize Manifests

Location in GitOps repo: `food-delivery-gitops/apps/{service-name}/`

### 4.1 Directory layout

```
apps/{service-name}/
├── base/
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── serviceaccount.yaml       # annotated with IRSA role ARN
│   ├── externalsecret.yaml       # pulls from Secrets Manager → K8s Secret
│   ├── servicemonitor.yaml       # Prometheus scraping config
│   ├── networkpolicy.yaml
│   ├── configmap.yaml            # non-secret env vars
│   └── kustomization.yaml
└── overlays/
    └── production/
        ├── configmap-patch.yaml  # env-specific env vars (secret ARNs, endpoints)
        ├── image-tag.yaml        # updated by CI with each new image tag
        └── kustomization.yaml
```

### 4.2 ServiceAccount (IRSA annotation)

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {service-name}
  namespace: {namespace}
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::{account-id}:role/food-delivery-production-{service-name}-irsa
```

The role ARN comes from the `terraform output {service}_irsa_role_arn` captured in Phase 1.

### 4.3 ExternalSecrets pattern

ExternalSecrets Operator (ESO) watches `ExternalSecret` resources, pulls values from Secrets
Manager, and creates a K8s `Secret`. The `ClusterSecretStore` named `aws-secretsmanager` is
already installed cluster-wide (managed by the user-service ArgoCD app until a dedicated system
app takes over).

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: {service-name}-secrets
  namespace: {namespace}
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: aws-secretsmanager
    kind: ClusterSecretStore
  target:
    name: {service-name}-secrets
    creationPolicy: Owner
  data:
    - secretKey: DB_URL
      remoteRef:
        key: food-delivery/production/{service-name}/db
        property: DB_URL
    - secretKey: DB_USERNAME
      remoteRef:
        key: food-delivery/production/{service-name}/db
        property: DB_USERNAME
    - secretKey: DB_PASSWORD
      remoteRef:
        key: food-delivery/production/{service-name}/db
        property: DB_PASSWORD
```

### 4.4 Deployment (env injection)

```yaml
spec:
  template:
    spec:
      serviceAccountName: {service-name}
      containers:
        - name: {service-name}
          image: {ecr-uri}/{service-name}:latest   # overridden by image-tag.yaml
          envFrom:
            - configMapRef:
                name: {service-name}-config
            - secretRef:
                name: {service-name}-secrets
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 20
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 15
```

### 4.5 Image tag update (CI → GitOps)

After pushing the image to ECR, CI commits a change to the GitOps repo:

```bash
cd food-delivery-gitops
kustomize edit set image {service-name}={ecr-uri}/{service-name}:${IMAGE_TAG} \
  --kustomization=apps/{service-name}/overlays/production/kustomization.yaml
git add apps/{service-name}/overlays/production/
git commit -m "ci: update {service-name} image to ${IMAGE_TAG}"
git push
```

ArgoCD detects the push and syncs within seconds.

### 4.6 ServiceMonitor (Prometheus)

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {service-name}
  namespace: {namespace}
spec:
  selector:
    matchLabels:
      app: {service-name}
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 30s
```

---

## Phase 5 — ArgoCD App Registration

### 5.1 Create a child Application manifest

File: `food-delivery-gitops/argocd/applications/{service-name}.yaml`

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: {service-name}
  namespace: argocd
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: services

  source:
    repoURL: ssh://*@git-codecommit.eu-west-1.amazonaws.com/v1/repos/food-delivery-gitops
    targetRevision: HEAD
    path: apps/{service-name}/overlays/production

  destination:
    server: https://kubernetes.default.svc
    namespace: {namespace}

  syncPolicy:
    automated:
      prune: false    # never auto-prune — require manual sync to delete resources
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

### 5.2 How registration works

The root `app-of-apps` Application watches `argocd/applications/`. Merging this PR to the GitOps
repo's main branch causes ArgoCD to auto-discover the new child Application within its next sync
cycle (~60 s). No manual `argocd app create` needed.

---

## Phase 6 — Verification Checklist

"Service X is fully deployed to production EKS" means **all** of the following pass:

- [ ] `terraform validate && terraform plan` — no errors for the new `{service}-irsa.tf` file
- [ ] Kustomize renders cleanly: `kubectl kustomize apps/{service-name}/overlays/production`
- [ ] ArgoCD UI shows Application `{service-name}` → `Synced` + `Healthy`
- [ ] Pod reaches `Running` state: `kubectl get pods -n {namespace}`
- [ ] ExternalSecret synced: `kubectl get externalsecret -n {namespace}` → `SecretSynced`
- [ ] Health endpoint responds: `kubectl port-forward ... 8080:8080` → `GET /actuator/health` → `{"status":"UP"}`
- [ ] Kafka producer functional: app logs show successful topic writes; check CloudWatch MSK metrics
- [ ] ServiceMonitor discovered: Prometheus targets UI shows `{service-name}` endpoint as `UP`

---

## FAQ — Surprises from User-Service

### 1. Wrong namespace in the IRSA trust policy → silent auth failure

The IRSA condition must use the **actual K8s namespace**, not the service name. user-service lives
in namespace `identity`, not `user-service`. Using the wrong namespace makes the pod start without
IAM credentials — Secrets Manager calls return `AccessDenied` with no obvious K8s-side error.

```hcl
# Correct for user-service:
"${oidc_provider_url}:sub" = "system:serviceaccount:identity:user-service"

# Wrong — would silently fail:
"${oidc_provider_url}:sub" = "system:serviceaccount:user-service:user-service"
```

Check the namespace assignments table in §1.3 before writing the trust policy.

### 2. JWT public key is published by the app, not managed in Terraform

The RS256 **private** key is generated by Terraform and stored in Secrets Manager. At startup,
user-service loads the private key via AWS SDK and derives + publishes the **public** key to SSM
(`/food-delivery/user-service/jwt-public-key`). The API Gateway Lambda authorizer reads the
public key from SSM. Do not attempt to manage the public key in IaC — it lives in SSM as an
application-owned artifact.

### 3. CodeArtifact token must be fetched every CodeBuild run

The token has a short TTL and is not cached across builds. The `pre_build` phase always fetches
a fresh token:

```bash
export CODEARTIFACT_AUTH_TOKEN=$(aws codeartifact get-authorization-token \
  --domain food-delivery-platform --domain-owner $AWS_ACCOUNT_ID \
  --query authorizationToken --output text)
```

If this step is omitted, Maven cannot resolve `common-libs` from CodeArtifact and the build fails
with a dependency resolution error.

### 4. Spring Boot 4.0 requires an explicit `FlywayConfig` bean

Standard `spring.flyway.*` properties alone are not sufficient in Boot 4.0 — the stricter
autoconfiguration requires a `@Configuration` class with a `Flyway` bean. See
`services/user-service/src/main/java/com/foodordering/user/config/FlywayConfig.java` as the
reference implementation to copy for each new service.

### 5. `prune: false` in ArgoCD is intentional — do not change it

Set on the root app-of-apps. If a manifest file is accidentally deleted from the GitOps repo,
ArgoCD will **not** cascade-delete production workloads. To intentionally remove a resource, run:

```bash
argocd app sync {service-name} --prune
```

### 6. EKS Fargate cold starts on first deploy

Fargate schedules pods ~30–60 seconds after initial deployment. `kubectl rollout status` may time
out on the first deploy — this is normal. Wait for the pod to reach `Running` before investigating
any issues. Subsequent rollouts are much faster (warm Fargate capacity already assigned).
