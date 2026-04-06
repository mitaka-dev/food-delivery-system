# Food Ordering System — Project Plan

> This file tracks what is implemented and what remains to be built.
> Update it as features are completed or plans change.

---

## Status Legend
- `[x]` Done
- `[ ]` Not started
- `[~]` Partial / in progress

---

## Services

| Service | Port | Status |
|---|---|---|
| `common-libs` | — | `[x]` Done |
| `gateway-service` | 8080 | `[x]` Done |
| `user-service` | 8081 | `[x]` Done |
| `analytics-service` | 8082 | `[x]` Done |
| `order-service` | 8083 | `[x]` Done |
| `product-service` | 8085 | `[x]` Done |
| `payment-service` | 8084 | `[x]` Done |

---

## What Is Done

### common-libs
- [x] `KafkaConstants` — topic names and consumer group IDs
- [x] `UserRole` enum — ADMIN, USER, GUEST
- [x] `UserCreatedEvent` record — userId (UUID), email, username, role
- [x] `OrderItem` record — productId (UUID), quantity (int)
- [x] `OrderCreatedEvent` record — orderId, username, totalAmount, items (List<OrderItem>)

### user-service
- [x] User entity (id, username, password, role, status)
- [x] `UserStatus` enum — PENDING, ACTIVE, FAILED
- [x] `POST /api/v1/users` — registration endpoint
- [x] User saved with `status=PENDING`, `UserCreatedEvent` published to Kafka
- [x] Kafka consumer on `user-confirmation-topic` → updates user to `ACTIVE`
- [x] `POST /api/v1/auth/login` — JWT access + refresh token response
- [x] `POST /api/v1/auth/refresh` — exchange refresh token for new access token
- [x] `POST /api/v1/auth/logout` — revoke refresh token from Redis
- [x] `JwtService` — generate/validate access tokens (15 min) and refresh tokens (7 days)
- [x] Refresh tokens stored in Redis
- [x] `JwtAuthenticationFilter` — validates Bearer tokens on incoming requests
- [x] `SecurityConfig` — public routes for /api/v1/users and /api/v1/auth/**, stateless session
- [x] BCrypt password encoding
- [x] Only ACTIVE users can authenticate

### gateway-service
- [x] Global `JwtAuthenticationFilter` — validates tokens, whitelists public routes
- [x] Forwards `X-User-Name` and `X-User-Role` headers to downstream services
- [x] Rate limiting by IP address (5 req/s, burst 10 for registration)
- [x] Route: `/api/v1/auth/**` → user-service (public)
- [x] Route: `/api/v1/users` → user-service (public, rate-limited)
- [x] Route: `/api/v1/orders/**` → order-service (placeholder, order-service not built yet)
- [x] Actuator endpoints exposed

### analytics-service
- [x] Kafka consumer on `user-topics` (consumer group: analytics-group)
- [x] Increments Redis counter `stats:roles:{ROLE}` on user creation
- [x] Publishes confirmation to `user-confirmation-topic`

### Infrastructure & Observability
- [x] PostgreSQL 16 with user_db, order_db, payment_db (databases created, tables auto-created by Hibernate)
- [x] Redis (Alpine) for refresh tokens and analytics counters
- [x] Kafka with Zookeeper (Confluent CP 7.5.0), topic `user-topics` (3 partitions)
- [x] Docker Compose with all services, health checks, and dependencies
- [x] Dockerfiles (eclipse-temurin:25-jre-alpine, non-root spring:spring user)
- [x] OpenTelemetry + Micrometer → Tempo (distributed tracing)
- [x] Loki log aggregation
- [x] Grafana dashboards
- [x] `start.sh` — automated build, secret generation, health checks
- [x] `generate-secrets.sh` — generates JWT_SECRET and credentials

---

## What Is Left To Build

### 1. order-service (Port 8083) — Next Priority
The gateway already has a placeholder route for `/api/v1/orders/**`.

- [x] Create Maven module and add to parent `pom.xml` (currently commented out)
- [x] Order entity (id, username, status, items, totalAmount, createdAt)
- [x] `OrderStatus` enum — PENDING, CONFIRMED, PAID, CANCELLED, FAILED
- [x] `POST /api/v1/orders` — place a new order
- [x] `GET /api/v1/orders/{id}` — get order by ID
- [x] `GET /api/v1/orders` — list orders for authenticated user
- [x] Publish `OrderCreatedEvent` to Kafka (`order-topics`)
- [x] Kafka consumer for payment confirmation → update order to PAID or FAILED
- [x] Add `order-topics` constant to `common-libs`
- [x] Add `OrderCreatedEvent` record to `common-libs`
- [x] Dockerfile
- [x] Add to `docker-compose.yml`
- [x] `application.yaml` (port 8083, order_db, Kafka)

### 2. product-service — Depends on order-service
- [x] Create Maven module and add to parent `pom.xml`
- [x] Product entity (id, name, description, price, category, stock) with `@Version` optimistic locking
- [x] `GET /api/v1/products` — list all products (paginated, filterable by category)
- [x] `GET /api/v1/products/{id}` — get product by ID
- [x] `POST /api/v1/products` — create product (ADMIN only, enforced via X-User-Role header)
- [x] Stock reservation logic (`reserveStock`, `releaseStock` in ProductService)
- [x] Kafka consumer (`StockReservationService`) for order events to manage inventory
- [x] Global exception handler (`GlobalExceptionHandler`) — 404, 409, 400, 403
- [x] Add gateway route
- [x] Dockerfile + docker-compose entry
- [x] `application.yaml`

### 3. payment-service — Depends on order-service
- [x] Create Maven module and add to parent `pom.xml`
- [x] Payment entity (id, orderId, username, amount, status, createdAt)
- [x] `PaymentStatus` enum — PENDING, SUCCESS, FAILED, REFUNDED
- [x] Kafka consumer on `order-topics` — process payment on order creation
- [x] Publish `PaymentProcessedEvent` to Kafka (`payment-topics`)
- [x] Refund logic on order cancellation
- [x] Add `payment-topics` constant and `PaymentProcessedEvent` to `common-libs`
- [x] Dockerfile + docker-compose entry
- [x] `application.yaml`

### 4. Order Saga (Cross-service)
The full order flow following the same Saga pattern as user registration:
- [x] `POST /api/v1/orders` → order saved as PENDING → `OrderCreatedEvent` on Kafka
- [x] payment-service consumes event → processes payment → publishes `PaymentProcessedEvent`
- [x] order-service consumes payment result → updates order to PAID or FAILED
- [x] Compensation logic: if payment fails → cancel order, release reserved stock

### 5. Miscellaneous
- [x] Add gateway routes for product-service and payment-service
- [ ] Integration tests between services
- [ ] Reduce trace sampling rate from 100% for production readiness
- [ ] Grafana dashboard panels for order/payment metrics
- [ ] HTTPS / TLS termination — add nginx or Traefik as a reverse proxy in front of the gateway; internal Docker traffic stays HTTP, only the public edge is HTTPS (self-signed cert for dev, real cert for production)

---

## Suggested Build Order

```
1. order-service (entity, endpoints, Kafka producer)
2. common-libs updates (OrderCreatedEvent, order-topics)
3. payment-service (Kafka consumer, payment logic, PaymentProcessedEvent)
4. Order Saga wiring (order ← payment confirmation)
5. product-service (catalog + stock reservation)
6. Gateway routes for new services
7. Integration tests
```
