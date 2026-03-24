# Food Ordering System — Claude Code Guide

## Project Overview
A Spring Boot microservices project for a food ordering platform. Currently implements user registration with an event-driven Saga pattern. Order, product, and payment services are planned but not yet implemented.

## Architecture

```
Client → Gateway (8080) → User Service (8081)
                              ↓ Kafka (user-topics)
                          Analytics Service (8082)
                              ↓ Kafka (user-confirmation-topic)
                          User Service (status: PENDING → ACTIVE)
```

### Services
| Service | Port | Responsibility |
|---|---|---|
| `gateway-service` | 8080 | API routing, rate limiting (10 req/s) |
| `user-service` | 8081 | User registration, Kafka producer + consumer |
| `analytics-service` | 8082 | Consumes user events, updates Redis counters |
| `common-libs` | — | Shared DTOs, enums, Kafka constants |

### Planned (not yet implemented)
- `order-service`
- `product-service`
- `payment-service`

## Tech Stack
- **Java 25**, **Spring Boot 3.5.11**, **Spring Cloud 2025.1.1**
- **PostgreSQL 16** — user_db, order_db, payment_db
- **Apache Kafka 7.5.0** — async messaging between services
- **Redis (Alpine)** — analytics counters, gateway caching
- **Spring WebFlux** — gateway and analytics (reactive)
- **Spring MVC** — user-service (imperative)
- **OpenTelemetry + Micrometer** — distributed tracing
- **Grafana + Loki + Tempo** — observability stack

## Build & Run

```bash
# Build all modules
./mvnw clean package

# Build a single service
./mvnw -pl user-service clean package

# Run all infrastructure + services
docker-compose up

# Rebuild images and restart
docker-compose up --build
```

## Key Kafka Topics (defined in `common-libs`)
- `user-topics` — user-service publishes `UserCreatedEvent` (3 partitions)
- `user-confirmation-topic` — analytics-service publishes confirmation back

## Database
- Host: `postgres-db:5432` (Docker), `localhost:5432` (local)
- User: `user` / Password: `password`
- Tables auto-created by Hibernate (`ddl-auto: update`)
- Init scripts: `init-db/init.sh`, `init-db/init-db.sql`

## Observability Endpoints
- Grafana: http://localhost:3000
- Tempo (traces): http://localhost:3200
- Zipkin receiver: http://localhost:9411
- Loki (logs): http://localhost:3100
- Actuator (gateway): `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`

## User Registration Flow (currently implemented)
1. `POST /api/v1/users` hits gateway → routed to user-service
2. User saved to PostgreSQL with `status=PENDING`
3. `UserCreatedEvent` published to `user-topics`
4. Analytics service consumes event, increments Redis counter (`stats:roles:{ROLE}`)
5. Analytics publishes confirmation to `user-confirmation-topic`
6. User-service updates user `status=ACTIVE`

## Important Notes
- Eureka service discovery is **disabled** — services use hardcoded Docker hostnames
- All services run as non-root user (`spring:spring`) in Docker
- 100% trace sampling configured (fine for dev, reduce for production)
- Security: CSRF disabled, BCrypt password encoding, user endpoints are public
