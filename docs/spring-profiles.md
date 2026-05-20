# Spring Profile Convention

Every service in this platform runs under exactly one of three Spring profiles. The profile controls the **shape** of dependencies — which beans load, whether IAM auth is required, whether TLS is enforced. It does **not** carry environment-specific values like hostnames, passwords, or topic names; those come from environment variables injected at runtime.

> **Rule: profile controls shape, env vars provide values.**

## The Three Profiles

### `local`

JVM runs on a developer laptop. Dependencies are Docker Compose containers defined in the root `docker-compose.yml`.

- PostgreSQL on `localhost:5432`, no TLS
- Redis on `localhost:6379`, no TLS
- Kafka on `localhost:9092`, `PLAINTEXT` (no IAM auth)
- AWS SDK calls go to LocalStack at `http://localhost:4566`

Activated by default via `.envrc` (`SPRING_PROFILES_ACTIVE=local`). Run `docker compose up -d` before starting any service.

### `staging`

Service runs on EKS staging. Talks to real AWS staging resources.

- Aurora PostgreSQL staging cluster (URL from `DB_URL` env var)
- ElastiCache Redis staging cluster (TLS, auth token from env var)
- MSK staging cluster (`SASL_SSL` + `AWS_MSK_IAM` auth)
- Real DynamoDB, SNS, SQS, S3 — no LocalStack, no endpoint override
- Values injected by Kubernetes from ConfigMaps and Secrets (via External Secrets Operator pulling from AWS Secrets Manager)

### `production`

Same shape as `staging`. Pointed at production AWS resources via env vars. Never run locally.

## The Edge-Case Profile

### `local-aws`

JVM runs on a developer laptop, but AWS SDK calls hit **real AWS staging** instead of LocalStack. Use sparingly — only when debugging an issue that reproduces against real DynamoDB or real MSK but not against LocalStack.

Activate explicitly: `-Dspring.profiles.active=local-aws`. Not the default for any workflow.

## Example Configuration

**`application-local.yml`** (shape: Docker Compose + LocalStack):

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/identity
    username: dev
    password: dev
  kafka:
    bootstrap-servers: localhost:9092
    properties:
      security.protocol: PLAINTEXT
      sasl.mechanism: ""
aws:
  endpoint-override: http://localhost:4566
  credentials:
    access-key-id: dev
    secret-access-key: dev
```

**`application-staging.yml`** (shape: real AWS, values from env):

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
    properties:
      security.protocol: SASL_SSL
      sasl.mechanism: AWS_MSK_IAM
# no aws.endpoint-override — SDK hits real AWS
```

`application-production.yml` has the same shape as staging; only the env var values differ.

## What Belongs Where

| Concern | Profile config | Env var |
|---------|---------------|---------|
| Auth / TLS on/off | ✓ | |
| LocalStack vs real AWS endpoint | ✓ | |
| Hostname / URL | | ✓ |
| Password / secret | | ✓ |
| Topic / queue name | | ✓ |
| Feature flags | | ✓ |
