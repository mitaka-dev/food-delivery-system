# Food Ordering System — Observability Reference

> Covers logging, distributed tracing, and metrics for all services.
> Stack: **Loki** (logs) · **Tempo** (traces) · **Prometheus/Actuator** (metrics) · **Grafana** (dashboards)

---

## Architecture Overview

```
Each Service
  ├── Logs    → logback-spring.xml → Loki4j appender → Loki (3100)
  ├── Traces  → Micrometer OTel bridge → Zipkin exporter → Tempo (9411)
  └── Metrics → Spring Actuator → /actuator/prometheus ← scraped by Grafana

Grafana (3000) reads from:
  ├── Loki  (3100) — log storage
  └── Tempo (3200) — trace storage
```

Every log line includes `traceId` and `spanId`, linking logs directly to traces in Grafana.

---

## Stack Components

| Component | Image | Port | Role |
|-----------|-------|------|------|
| Loki | grafana/loki:latest | 3100 | Log aggregation and storage |
| Tempo | grafana/tempo:latest | 3200 (query), 9411 (Zipkin ingest) | Distributed trace storage |
| Grafana | grafana/grafana:latest | 3000 | Unified dashboard, log/trace explorer |

---

## 1. Logging

### How it works

Each service has a `logback-spring.xml` that configures two appenders:

- **CONSOLE** — colored output with ANSI codes for local `docker compose logs` reading
- **LOKI** — plain text shipped to Loki via HTTP push (`http://loki:3100/loki/api/v1/push`)

The Loki appender uses the [Loki4j](https://loki4j.github.io/loki-logback-appender/) library (`loki-logback-appender:1.5.2`).

### Log format

**Console:**
```
14:03:22.411  INFO --- [main] f.o.s.order.service.OrderService : [traceId=abc123, spanId=def456] Order created: id=uuid
```

**Loki (plain text, no ANSI):**
```
2026-03-27 14:03:22.411  INFO [order-service] [main] traceId=abc123 spanId=def456 logger=OrderService | Order created: id=uuid
```

### Loki labels

Each log stream is labeled with:
- `app` — the service name (e.g. `order-service`, `payment-service`)
- `level` — log level (`INFO`, `WARN`, `ERROR`)

### Querying logs in Grafana

1. Open **http://localhost:3000** → **Explore** → select **Loki**
2. Use LogQL:

```logql
# All logs from a service
{app="order-service"}

# Errors only
{app="user-service", level="ERROR"}

# Saga-related logs across all services
{app=~".+"} |= "SAGA"

# Search by traceId (link from a trace in Tempo)
{app=~".+"} |= "traceId=abc123def456"

# Payment processing logs
{app="payment-service"} |= "Payment"

# All WARN and ERROR across all services
{level=~"WARN|ERROR"}
```

### Per-service logback location

| Service | File |
|---------|------|
| gateway-service | `gateway-service/src/main/resources/logback-spring.xml` |
| user-service | `user-service/src/main/resources/logback-spring.xml` |
| analytics-service | `analytics-service/src/main/resources/logback-spring.xml` |
| order-service | `order-service/src/main/resources/logback-spring.xml` |
| payment-service | `payment-service/src/main/resources/logback-spring.xml` |
| product-service | `product-service/src/main/resources/logback-spring.xml` |

---

## 2. Distributed Tracing

### How it works

```
Service → Micrometer (OTel bridge) → opentelemetry-exporter-zipkin → Tempo (9411) → Grafana
```

Each service uses:
- `micrometer-tracing-bridge-otel` — bridges Spring's Micrometer tracing API to OpenTelemetry
- `opentelemetry-exporter-zipkin` — exports spans to Tempo's Zipkin-compatible endpoint

Spans are automatically created for:
- Incoming HTTP requests (via Spring MVC / WebFlux instrumentation)
- Kafka producer sends and consumer receives
- Database queries (via Spring Data JPA instrumentation)

A `traceId` is propagated across service calls via HTTP headers (`traceparent`) and Kafka message headers, meaning a single user request can be traced end-to-end through the gateway → service → Kafka → downstream service.

### Configuration (same for all services)

```yaml
management:
  tracing:
    sampling:
      probability: 1.0        # 100% of requests traced — reduce for production
  zipkin:
    tracing:
      endpoint: http://tempo:9411/api/v2/spans
```

### Tempo configuration (`tempo-config.yaml`)

```yaml
distributor:
  receivers:
    zipkin:                    # accepts Zipkin v2 JSON format

storage:
  trace:
    backend: local
    local:
      path: /tmp/tempo/traces  # ephemeral — traces lost on container restart

overrides:
  metrics_generator_processors: [service-graphs, span-metrics]
```

> **Note:** Tempo uses local storage — traces do not persist across `docker compose down`. For persistence, configure an object storage backend (S3, GCS).

### Querying traces in Grafana

1. Open **http://localhost:3000** → **Explore** → select **Tempo**
2. Search options:
   - **By Service Name** — select from dropdown (e.g. `order-service`)
   - **By Trace ID** — paste a `traceId` from a log line
   - **By Tag** — e.g. `http.url=/api/v1/orders`

**Following a Saga in traces:**
1. Find the initial HTTP span in Grafana (e.g. `POST /api/v1/orders` in `gateway-service`)
2. Copy the `traceId`
3. Search Loki with `{app=~".+"} |= "traceId=<id>"` to see all log lines across all services for that request

### Sampling rate

Currently set to `1.0` (100%) — every request is traced. This is appropriate for development but should be reduced for production to avoid Tempo storage pressure:

```yaml
management:
  tracing:
    sampling:
      probability: 0.1   # 10% sampling for production
```

---

## 3. Metrics

### How it works

Each service exposes a Prometheus-compatible metrics endpoint via Spring Boot Actuator:

```
GET /actuator/prometheus  →  Prometheus text format metrics
```

The `micrometer-registry-prometheus` dependency registers a Prometheus `MeterRegistry`, which Micrometer uses to export all collected metrics. Grafana scrapes this endpoint directly (no separate Prometheus server is configured — Grafana can scrape Prometheus format natively).

### Available actuator endpoints (all services)

| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Liveness + readiness status |
| `/actuator/info` | Application info |
| `/actuator/metrics` | List of all metric names |
| `/actuator/metrics/{name}` | Detail for a specific metric |
| `/actuator/prometheus` | All metrics in Prometheus text format |

### Key metrics exposed

**JVM:**
- `jvm.memory.used` / `jvm.memory.max` — heap and non-heap usage
- `jvm.gc.pause` — garbage collection pause times
- `jvm.threads.live` — active thread count

**HTTP (Spring MVC/WebFlux):**
- `http.server.requests` — request count, duration, status code, URI
- `http.server.requests.active` — in-flight requests

**Kafka:**
- `kafka.consumer.fetch.manager.records.consumed.total` — messages consumed
- `kafka.producer.record.send.total` — messages produced
- `kafka.consumer.coordinator.rebalance.latency.avg` — consumer group rebalances

**Database (JPA services):**
- `spring.data.repository.invocations` — repository method call counts and durations
- `hikaricp.connections.*` — connection pool metrics

**Custom Saga metrics (via log correlation):**
Saga completion and status transitions are logged but not yet exposed as custom metrics. To add one:
```java
meterRegistry.counter("saga.order.completed", "status", "PAID").increment();
```

### Querying metrics in Grafana

1. Open **http://localhost:3000** → **Explore** → select **Prometheus** (or add a Prometheus data source pointing to any service's `/actuator/prometheus`)
2. Example queries:
```promql
# HTTP request rate per service
rate(http_server_requests_seconds_count[1m])

# 95th percentile response time for order-service
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{job="order-service"}[5m]))

# JVM heap usage
jvm_memory_used_bytes{area="heap"}

# Kafka messages consumed per second
rate(kafka_consumer_fetch_manager_records_consumed_total[1m])
```

---

## 4. Per-Service Observability Summary

| Service | Loki label | Traces | Actuator port |
|---------|-----------|--------|---------------|
| gateway-service | `app="gateway-service"` | ✓ | :8080/actuator |
| user-service | `app="user-service"` | ✓ | :8081/actuator |
| analytics-service | `app="analytics-service"` | ✓ | :8082/actuator |
| order-service | `app="order-service"` | ✓ | :8083/actuator |
| payment-service | `app="payment-service"` | ✓ | :8084/actuator |
| product-service | `app="product-service"` | ✓ | :8085/actuator |

---

## 5. Useful Debug Commands

```bash
# Tail logs for a specific service
docker compose logs -f order-service

# Check if a service's actuator is healthy
curl http://localhost:8083/actuator/health

# See all metrics names exposed by a service
curl -s http://localhost:8083/actuator/metrics | jq '.names[]'

# Get a specific metric value
curl -s http://localhost:8083/actuator/metrics/jvm.memory.used | jq '.measurements'

# Tail logs for all services, filtering for errors only
docker compose logs -f | grep ERROR

# Find all Saga-related log lines across all services
docker compose logs | grep -i "saga"
```

---

## 6. Production Readiness Checklist

- [ ] Reduce trace sampling from `1.0` to `0.1` or lower
- [ ] Configure Tempo with persistent object storage (S3/GCS) instead of local
- [ ] Add a dedicated Prometheus server and configure scrape targets for all services
- [ ] Create Grafana dashboards for order/payment Saga success rates
- [ ] Add alerting rules in Grafana for error rate spikes and JVM memory pressure
- [ ] Add custom Micrometer counters for Saga state transitions (PENDING → PAID, PENDING → FAILED)
