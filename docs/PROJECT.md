# PROJECT.md
Project-specific context for the Food Ordering System.

## Architecture
Client → user-service (8081)   — auth / registration
Client → order-service (8083)  — order CRUD (Bearer token)
Client → product-service (8085) — catalog CRUD (Bearer token)

Order flow:
order-service → Kafka (order-topics)
                    ┌─────────┴──────────┐
             product-service        payment-service
             (stock reserve)        (process payment)
                    │                    │
                    └──── order-confirmation-topic ────┐
                                                       ↓
                                          Order Service (PENDING → PAID)

**Note:** In production, all traffic routes through AWS API Gateway (see `docs/plan/architecture.md` and Terraform Phase 0).

### Services
| Service | Port | Responsibility |
| `user-service` | 8081 | User registration + auth, Kafka producer + consumer |
| `analytics-service` | 8082 | Consumes user events, updates Redis counters, triggers Saga confirmation |
| `order-service` | 8083 | Order CRUD, Kafka producer + consumer |
| `payment-service` | 8084 | Event-driven payment processor — consumes `order-topics`, publishes to `order-confirmation-topic` + `payment-topics` (no HTTP endpoints) |
| `product-service` | 8085 | Product catalog CRUD, stock reservation via Kafka consumer on `order-topics`; optimistic locking (`@Version`) |
| `common-libs` | — | Shared DTOs, enums, Kafka constants |

## Tech Stack
- **Java 25**, **Spring Boot 4.0.6**
- **PostgreSQL 16** — user_db, order_db, payment_db
- **Apache Kafka 7.5.0** (Confluent CP) — async Saga messaging
- **Redis (Alpine)** — refresh token storage, analytics counters
- **Spring MVC** — all services (imperative)
- **OpenTelemetry + Micrometer → Tempo** — distributed tracing
- **Prometheus** — metrics scraping from all services (`/actuator/prometheus`, port 9090)
- **Loki + Grafana** — log aggregation and pre-provisioned dashboards

## Startup

```bash
# Automated startup (generates secrets, builds, health-checks)
./start.sh

# Generate JWT_SECRET and credentials separately
./generate-secrets.sh
```

**Note:** `JWT_SECRET` must be set in the environment before starting user-service. `start.sh` handles this automatically.

## JWT Architecture

**user-service** (`JwtAuthenticationFilter` — OncePerRequestFilter):
- Validates Bearer token on protected endpoints; passes through public paths (`/api/v1/users`, `/api/v1/auth/**`, `/actuator`)
- Parses JWT, checks expiration and `type == "access"`, loads UserDetails from DB (`status == ACTIVE`)
- Sets Spring Security context; returns 401 on invalid token

**JWT Claims Structure:**
```json
{ "sub": "username", "role": "ADMIN|USER|GUEST", "type": "access|refresh", "iat": ..., "exp": ... }
```
- Access token: 15 min; Refresh token: 7 days
- Refresh tokens stored in Redis: key = `refresh_token:{username}`, TTL = 7 days
- Logout deletes the Redis key, revoking the refresh token

**User Registration Saga:**
1. `POST /api/v1/users` → User saved as `PENDING` → `UserCreatedEvent` → `user-topics`
2. analytics-service consumes → increments `stats:roles:{ROLE}` in Redis → publishes `"SUCCESS"` → `user-confirmation-topic`
3. user-service consumes → updates user to `ACTIVE` → user can now login

**Order Creation Saga:**
1. `POST /api/v1/orders` (requires Bearer token) → Order saved as `PENDING` → `OrderCreatedEvent` → `order-topics`
2. product-service consumes → reserves stock (optimistic locking via `@Version`)
3. payment-service consumes → processes payment (simulated: amount > 500 → FAILED) → publishes `PaymentProcessedEvent` to `order-confirmation-topic` + `payment-topics`
4. order-service consumes `PaymentProcessedEvent` from `order-confirmation-topic` → `PAID` on SUCCESS, `FAILED` on failure
5. product-service consumes `PaymentProcessedEvent` from `payment-topics` → releases reserved stock on FAILED (compensation)

## Kafka Topics (defined in `common-libs/KafkaConstants`)
| Constant | Value | Notes |
| `USER_TOPIC` | `user-topics` | 3 partitions |
| `USER_CONFIRMATION_TOPIC` | `user-confirmation-topic` | analytics → user-service |
| `ORDER_TOPIC` | `order-topics` | 3 partitions |
| `ORDER_CONFIRMATION_TOPIC` | `order-confirmation-topic` | payment → order-service; carries `PaymentProcessedEvent` |
| `PAYMENT_TOPIC` | `payment-topics` | payment-service producer |
| `ANALYTICS_GROUP` | `analytics-group` | |
| `USER_GROUP` | `user-group` | |
| `ORDER_GROUP` | `order-group` | |
| `PAYMENT_GROUP` | `payment-group` | |
| `PRODUCT_GROUP` | `product-group` | |

## Service Endpoints (Local)
| Service | Port | Public Paths | Auth Required |
| `user-service` | 8081 | `/api/v1/users`, `/api/v1/auth/**` | `/api/v1/auth/logout`, `/actuator` |
| `order-service` | 8083 | — | `/api/v1/orders/**` |
| `product-service` | 8085 | — | `/api/v1/products/**` |
| `analytics-service` | 8082 | `/actuator` | — |
| `payment-service` | 8084 | — | internal/event-driven only |

## Key Data Notes
- `Order.items` is stored as plain `TEXT` (no normalization); expected to be JSON string from client
- `Order.status` transitions: `PENDING → PAID` (on confirmation) or `PENDING → FAILED/CANCELLED`
- `Payment.status` transitions: `PENDING → SUCCESS | FAILED | REFUNDED`
- `User.status` transitions: `PENDING → ACTIVE` (on Saga confirmation); only `ACTIVE` users can authenticate
- `Product.stock` uses `@Version` optimistic locking for concurrent reservation safety
- `ProductCategory` enum: `BURGER, PIZZA, PASTA, SALAD, DESSERT, BEVERAGE, SIDE, OTHER`
- Hibernate `ddl-auto: update` — schema auto-evolves; no migration tooling yet

## Database
- Host: `postgres-db:5432` (Docker), `localhost:5432` (local)
- Credentials: `user` / `password`
- Databases: `user_db`, `order_db`, `payment_db` — created by `init-db/init.sh`

## Observability
- Grafana: http://localhost:3000 — "Food Ordering System" dashboard auto-provisioned on startup
- Prometheus: http://localhost:9090 — scrapes all 6 services every 15s
- Tempo (traces): http://localhost:3200
- Loki (logs): http://localhost:3100
- Zipkin receiver (for OTel export): http://localhost:9411
- Actuator: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus` (all services)

**Custom business metrics (Micrometer counters):**
- `orders_created_total`, `orders_confirmed_total`, `orders_failed_total` — order-service
- `payments_processed_total{status="SUCCESS|FAILED"}` — payment-service

**Grafana provisioning files:** `grafana/provisioning/` (auto-loaded by docker-compose volume mount)

## Important Notes
- Eureka is **disabled** — all services use hardcoded Docker hostnames (e.g., `kafka:29092`, `redis-cache:6379`)
- All Docker containers run as non-root user `spring:spring` (eclipse-temurin:25-jre-alpine base)
- 100% trace sampling is configured — reduce for production
- Rate limiting: 5 req/s (burst 10) on `/api/v1/users`; 10 req/s (burst 20) on `/api/v1/orders/**` and `/api/v1/products/**`
