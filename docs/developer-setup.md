# Developer Setup

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java (Corretto) | 25 | via asdf (see below) |
| Maven | 3.9.9 | via asdf |
| Terraform | 1.7.5 | via asdf |
| kubectl | 1.30.7 | via asdf |
| Helm | 3.15.4 | via asdf |
| Docker + Compose plugin | latest | [docs.docker.com](https://docs.docker.com/get-docker/) |
| AWS CLI | v2 latest | [aws install guide](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) |
| asdf | latest | [asdf-vm.com](https://asdf-vm.com/guide/getting-started.html) |
| direnv (optional) | latest | [direnv.net](https://direnv.net/docs/installation.html) |

## One-time bootstrap

```bash
# 1. Clone the repo
git clone <CodeCommit-URL>/food-delivery-platform
cd food-delivery-platform

# 2. Install all tool versions
./scripts/bootstrap-dev.sh

# 3. Configure your environment
cp .envrc.template .envrc
# Edit .envrc: set AWS_PROFILE to your AWS profile name, adjust AWS_REGION if needed
direnv allow   # or: source .envrc manually

# 4. Start local dependencies
docker compose up -d

# 5. Build all services
./mvnw clean install -DskipTests

# 6. Start a specific service (example)
cd services/user-service && ../../../mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

## Spring profiles

See [spring-profiles.md](spring-profiles.md) for the full profile convention. Short version:

- **`local`** — runs against Docker Compose containers + LocalStack. Default.
- **`production`** — runs against real AWS resources. EKS only.

`SPRING_PROFILES_ACTIVE=local` is set in `.envrc.template` so `local` is the default on your laptop.

## Running tests

```bash
# All modules
./mvnw test

# Single service
./mvnw test -pl services/user-service
```

## Local service ports

| Service | Port |
|---------|------|
| user-service | 8081 |
| analytics-service | 8082 |
| order-service | 8083 |
| payment-service | 8084 |
| product-service | 8085 |
| basket-service | 8086 |
| kitchen-service | 8087 |
| delivery-service | 8088 |
| review-service | 8089 |
| promotion-service | 8090 |

## AWS setup (required for staging/production)

AWS access is needed for Phase 0+ work. Set up your AWS profile before starting Phase 0.2.

```bash
aws configure --profile food-delivery-dev
# Enter: Access Key ID, Secret Access Key, region (e.g. eu-west-1), output format (json)
```

After Phase 0.8 provisions CodeArtifact, run `./scripts/bootstrap-dev.sh` again to authenticate Maven.

## Repo layout

```
food-delivery-platform/
├── services/           # All microservices (one directory per service)
├── common-libs/        # Shared Java libraries (events, exceptions, resilience)
├── platform-bom/       # Platform Bill of Materials (populated in Phase 1)
├── platform-infra/     # Terraform infrastructure (populated in Phase 0.2+)
├── e2e-tests/          # End-to-end test suite (populated in Phase 9)
├── dev/seed/           # Seed data and scripts for local development
├── scripts/            # Developer tooling (bootstrap-dev.sh, etc.)
└── docs/               # Project documentation
```

The companion repo `food-delivery-gitops` holds Kubernetes manifests watched by ArgoCD. It is a separate repository.
