# Food Ordering System

A microservices-based food ordering platform built with Spring Boot 3.5, demonstrating the **Saga pattern** for distributed transactions across independently deployable services.

## Architecture

```
Client → Gateway (8080) → Order Service (8083)
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
| `gateway-service` | 8080 | JWT validation, routing, rate limiting |
| `user-service` | 8081 | Registration, authentication |
| `analytics-service` | 8082 | Kafka consumer, Redis counters |
| `order-service` | 8083 | Order CRUD, Saga orchestration |
| `payment-service` | 8084 | Event-driven payment processor |
| `product-service` | 8085 | Product catalog, stock reservation |

## Tech Stack

- **Java 25**, **Spring Boot 3.5**, **Spring Cloud Gateway**
- **PostgreSQL 16** — `user_db`, `order_db`, `payment_db`, `product_db`
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
| `/health` | Check the health of all 6 services via their actuator endpoints |
| `/logs [service]` | Tail Docker logs for a specific service, or all microservices at once |
| `/commit` | Stage changes, generate a Conventional Commits message, commit, and push |
| `/saga-test` | Run a full end-to-end Saga test (registration → login → happy-path order → failure-path order) |
| `/plan-session` | Read `PLAN.md` and summarise what is done, pending, and suggested next |

### Usage examples

```
/start                    # bring up the whole system
/health                   # quick status check
/logs order-service       # tail a specific service
/saga-test                # verify the Saga flows end-to-end
/commit                   # stage, commit, and push all changes
```

## Further Reading

- [`SERVICES.md`](SERVICES.md) — endpoint reference, data models, Kafka topics, Saga flows
- [`CLAUDE.md`](CLAUDE.md) — architecture notes and conventions for AI-assisted development
- [`PLAN.md`](PLAN.md) — project roadmap and progress tracking
- [`OBSERVABILITY.md`](OBSERVABILITY.md) — tracing and logging setup details
