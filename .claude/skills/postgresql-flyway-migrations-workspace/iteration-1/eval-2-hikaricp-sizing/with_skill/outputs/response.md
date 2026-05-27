# HikariCP Sizing for product-service on Aurora PostgreSQL

## Pool Size Calculation

The formula from the project skill:

```
maximum-pool-size = (Aurora max_connections / peak pod count) × 0.8
```

With your numbers:
- Aurora max_connections: 1000
- Peak pod count: 20 (must size for worst case, not steady state)
- `(1000 / 20) × 0.8 = 40`

At peak: 20 pods × 40 connections = **800 connections** used, leaving 200 as headroom for Aurora internals, the RDS Proxy (if used), superuser connections, and monitoring agents. Never size for the normal 8-pod count — if HikariCP is configured for 8 pods and you scale to 20, each pod will try to open (1000/8)×0.8 = 100 connections and you'll exhaust the cluster at 2000 attempted connections.

## Other Settings That Matter for Aurora

| Setting | Value | Why |
|---------|-------|-----|
| `minimum-idle` | `2` | Keeps a small warm pool without wasting connections when pods are idle |
| `connection-timeout` | `5000` | 5 s — fail fast so Kubernetes can restart the pod rather than queuing requests indefinitely. Aurora failover takes ~30 s; a shorter timeout here ensures your upstream caller (API Gateway) sees a clean error instead of a hung connection |
| `idle-timeout` | `300000` | 5 min — reclaim connections that pods opened during a traffic spike but no longer need |
| `max-lifetime` | `1800000` | 30 min — must be less than Aurora's `wait_timeout` (default 8 h). Prevents stale connections after an Aurora maintenance event or failover |
| `leak-detection-threshold` | `60000` | 1 min — logs a warning if a connection is held longer than 60 s. Essential for catching missing `@Transactional` boundaries or long-running queries starving the pool |
| `pool-name` | `product-service-pool` | Appears in HikariCP metrics and thread names — makes it easy to correlate pool exhaustion warnings with the right service in CloudWatch |

The `connection-timeout` is the most common cause of the errors you're seeing: if it is too long (e.g., the user-service default of 30 000 ms), requests queue up waiting for a connection, threads back-pressure into the web container, and the service appears hung rather than failing fast.

## application-production.yml for product-service

```yaml
spring:
  datasource:
    url: ${DB_URL}                        # Aurora cluster endpoint (writes)
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 40              # (1000 Aurora max_connections / 20 peak pods) × 0.8
      minimum-idle: 2
      connection-timeout: 5000           # 5 s — fail fast; shorter than API Gateway timeout
      idle-timeout: 300000               # 5 min — reclaim idle connections after traffic spike
      max-lifetime: 1800000              # 30 min — below Aurora's wait_timeout
      leak-detection-threshold: 60000    # 1 min — warn on connections held too long
      pool-name: product-service-pool

  flyway:
    enabled: true
    locations: classpath:db/migration/product-service

  jpa:
    hibernate:
      ddl-auto: validate                 # never update/create-drop in production
    show-sql: false

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
    properties:
      security.protocol: SASL_SSL
      sasl.mechanism: AWS_MSK_IAM
      sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
      sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler

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

## Anti-Patterns to Avoid

- Do not use the normal (8-pod) count in the formula — always size for `max_pods`.
- Do not leave `connection-timeout` at the Spring default (30 000 ms) — you'll queue threads under load instead of shedding.
- Do not set `maximum-pool-size` the same across all services without accounting for how many services share the same Aurora cluster. If order-service, payment-service, and product-service all run on the same cluster, their peak connection totals must also sum to ≤ 800 (leaving the 20% headroom cluster-wide, not per-service).
- Do not leave `ddl-auto: update` (currently in `application.yaml`) active — override it to `validate` in the production profile as shown above. `update` is safe locally but will attempt DDL changes against Aurora on pod startup.

## Note on Current application.yaml

The base `application.yaml` has `ddl-auto: update`, which the production profile above correctly overrides to `validate`. The base file is fine for local dev; just ensure the production profile is loaded in Kubernetes via `SPRING_PROFILES_ACTIVE=production`.
