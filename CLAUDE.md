# CLAUDE.md

## Permissions
- Read any file in this project without asking for confirmation

## Architecture
Client → Gateway (8080) → Order Service (8083)
                              ↓ Kafka (order-topics)
                    ┌─────────┴──────────┐
             product-service        payment-service
             (stock reserve)        (process payment)
                    │                    │
                    └──── order-confirmation-topic ────┐
                                                       ↓
                                          Order Service (PENDING → PAID)

### Services
| Service | Port | Responsibility |
| `gateway-service` | 8080 | API routing, JWT validation, rate limiting (IP-based, Redis) |
| `user-service` | 8081 | User registration + auth, Kafka producer + consumer |
| `analytics-service` | 8082 | Consumes user events, updates Redis counters, triggers Saga confirmation |
| `order-service` | 8083 | Order CRUD, Kafka producer + consumer |
| `payment-service` | 8084 | Event-driven payment processor — consumes `order-topics`, publishes to `order-confirmation-topic` + `payment-topics` (no HTTP endpoints) |
| `product-service` | 8085 | Product catalog CRUD, stock reservation via Kafka consumer on `order-topics`; optimistic locking (`@Version`) |
| `common-libs` | — | Shared DTOs, enums, Kafka constants |

## Tech Stack
- **Java 25**, **Spring Boot 3.5.11**, **Spring Cloud 2025.1.1**
- **PostgreSQL 16** — user_db, order_db, payment_db
- **Apache Kafka 7.5.0** (Confluent CP) — async Saga messaging
- **Redis (Alpine)** — refresh token storage, gateway rate limiting, analytics counters
- **Spring WebFlux** — gateway and analytics (reactive)
- **Spring MVC** — user-service, order-service, product-service, payment-service (imperative)
- **OpenTelemetry + Micrometer → Tempo** — distributed tracing
- **Loki + Grafana** — log aggregation and dashboards

## Startup

```bash
# Automated startup (generates secrets, builds, health-checks)
./start.sh

# Generate JWT_SECRET and credentials separately
./generate-secrets.sh

**Note:** `JWT_SECRET` must be set in the environment before starting gateway and user services. `start.sh` handles this automatically.

## JWT Architecture (Two-Layer Validation)

**Gateway Layer** (`JwtAuthenticationFilter` — GlobalFilter, order -1):
- Validates every request except public paths (`/api/v1/users`, `/api/v1/auth/**`, `/actuator`)
- Parses JWT, checks expiration and `type == "access"`
- Injects headers downstream: `X-User-Name: {username}`, `X-User-Role: {role}`
- Returns 401 directly; downstream services never see invalid tokens

**Service Layer** (`JwtAuthenticationFilter` — OncePerRequestFilter in user-service):
- Re-validates the Bearer token, loads UserDetails from DB (including `status == ACTIVE` check)
- Sets Spring Security context; continues silently if no token (for public endpoints)

**JWT Claims Structure:**
```json
{ "sub": "username", "role": "ADMIN|USER|GUEST", "type": "access|refresh", "iat": ..., "exp": ... }
- Access token: 15 min; Refresh token: 7 days
- Refresh tokens stored in Redis: key = `refresh_token:{username}`, TTL = 7 days
- Logout deletes the Redis key, revoking the refresh token

**Downstream services receive user identity via headers, not by re-parsing JWT.**

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

## Gateway Routes
| Route | Path | Target | Auth | Rate Limit |
| auth-route | `/api/v1/auth/**` | user-service:8081 | public | — |
| user-registration-route | `/api/v1/users` | user-service:8081 | public | 5 req/s, burst 10 |
| order-service-route | `/api/v1/orders/**` | order-service:8083 | JWT required | 10 req/s, burst 20 |
| product-service-route | `/api/v1/products/**` | product-service:8085 | JWT required | 10 req/s, burst 20 |

**Note:** payment-service has no gateway route — it is fully internal/event-driven.

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
- Grafana: http://localhost:3000
- Tempo (traces): http://localhost:3200
- Loki (logs): http://localhost:3100
- Zipkin receiver (for OTel export): http://localhost:9411
- Actuator: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus` (gateway)

## Important Notes
- Eureka is **disabled** — all services use hardcoded Docker hostnames (e.g., `kafka:29092`, `redis-cache:6379`)
- All Docker containers run as non-root user `spring:spring` (eclipse-temurin:25-jre-alpine base)
- 100% trace sampling is configured — reduce for production
- Rate limiting: 5 req/s (burst 10) on `/api/v1/users`; 10 req/s (burst 20) on `/api/v1/orders/**` and `/api/v1/products/**`

## Project Plan & Progress
> **Session start:** Read `PLAN.md` in the project root to know what is done and what is left.
> **During the session:** Update `PLAN.md` checkboxes as features are completed.
> **Session end:** When the user says anything like "we're done", "goodbye", "end session", or "wrap up" — review and update both `PLAN.md` and `CLAUDE.md` before responding, then confirm both have been updated.
