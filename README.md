# Food Ordering System

A microservices-based food ordering platform built with Spring Boot 3.5, demonstrating the **Saga pattern** for distributed transactions across independently deployable services.

## Architecture

```
Client → user-service  (8081)  — auth / registration
Client → order-service (8083)  — orders (Bearer token)
                               ↓ Kafka (order-topics)
                     ┌─────────┴──────────┐
              product-service        payment-service
              (stock reserve)        (process payment)
                     │                    │
                     └──── order-confirmation-topic ────┐
                                                        ↓
                                           Order Service (PENDING → PAID)
```

| Service | Port | Role |
|---------|------|------|
| `user-service` | 8081 | Registration, authentication |
| `analytics-service` | 8082 | Kafka consumer, Redis counters |
| `order-service` | 8083 | Order CRUD, Saga orchestration |
| `payment-service` | 8084 | Event-driven payment processor |
| `product-service` | 8085 | Product catalog, stock reservation |
| `basket-service` | 8086 | Redis-backed shopping basket |
| `kitchen-service` | 8087 | Kitchen ticket management |
| `delivery-service` | 8088 | Delivery tracking |
| `review-service` | 8089 | DynamoDB-backed order reviews |
| `promotion-service` | 8090 | Promotion codes |
| `notification-service` | — | Event-driven notifications (no HTTP) |

## Tech Stack

- **Java 25**, **Spring Boot 4.0.6**
- **PostgreSQL 16** — `user_db`, `order_db`, `payment_db`
- **Apache Kafka** — async Saga messaging
- **Redis** — refresh tokens, rate limiting, analytics counters
- **OpenTelemetry → Tempo + Loki + Grafana** — tracing and logs

## Quick Start

```bash
git clone <repo-url>
cd food-ordering-system
./start.sh
```

`start.sh` generates secrets, builds JARs, starts all Docker containers, and waits for health checks. Run `./start.sh --rebuild` to force a full Maven + Docker rebuild.

**Prerequisites:** Java 25, Docker with Compose plugin.

## API Documentation

Swagger UI is available once the system is running:

| Service | URL |
|---------|-----|
| User Service | http://localhost:8081/swagger-ui.html |
| Order Service | http://localhost:8083/swagger-ui.html |
| Product Service | http://localhost:8085/swagger-ui.html |
| Basket Service | http://localhost:8086/swagger-ui.html |
| Kitchen Service | http://localhost:8087/swagger-ui.html |
| Delivery Service | http://localhost:8088/swagger-ui.html |
| Review Service | http://localhost:8089/swagger-ui.html |
| Promotion Service | http://localhost:8090/swagger-ui.html |

Raw OpenAPI specs: `/v3/api-docs` on each service port.

## Observability

| Tool | URL |
|------|-----|
| Grafana (logs + traces) | http://localhost:3000 |
| Tempo (traces) | http://localhost:3200 |
| Loki (logs) | http://localhost:3100 |

## Claude Code Skills

This project ships with [Claude Code](https://claude.ai/code) skills in `.claude/skills/`. If you use Claude Code, these slash commands are available automatically after cloning — no setup required.

| Command | Description |
|---------|-------------|
| `/start` | Build Docker images, start all services, and verify health checks |
| `/health` | Check the health of all services via their actuator endpoints |
| `/logs [service]` | Tail Docker logs for a specific service, or all microservices at once |
| `/commit` | Stage changes, generate a Conventional Commits message, and commit |
| `/saga-test` | Run a full end-to-end Saga test (registration → login → happy-path order → failure-path order) |

### Usage examples

```
/start                    # bring up the whole system
/health                   # quick status check
/logs order-service       # tail a specific service
/saga-test                # verify the Saga flows end-to-end
/commit                   # stage and commit all changes
```

## Further Reading

- [`docs/SERVICES.md`](docs/SERVICES.md) — endpoint reference, data models, Kafka topics, Saga flows
- [`CLAUDE.md`](CLAUDE.md) — architecture notes and conventions for AI-assisted development
- [`docs/plan/PLAN.md`](docs/plan/PLAN.md) — project roadmap and progress tracking
- [`docs/OBSERVABILITY.md`](docs/OBSERVABILITY.md) — tracing and logging setup details
