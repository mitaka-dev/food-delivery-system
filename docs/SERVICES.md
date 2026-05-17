# Food Ordering System — Service Reference

> Technical reference for all microservices: endpoints, data models, Kafka flows, and Saga patterns.

## System Overview

Client
  │
  ▼
Gateway (8080)  ──── validates JWT, rate-limits, injects X-User-Name / X-User-Role headers
  │
  ├──▶ User Service       (8081)  ──── registration, authentication
  ├──▶ Order Service      (8083)  ──── order CRUD + Saga producer
  ├──▶ Product Service    (8085)  ──── catalog, stock reservation
  ├──▶ Basket Service     (8086)  ──── shopping basket (Redis)
  ├──▶ Kitchen Service    (8087)  ──── kitchen ticket management
  ├──▶ Delivery Service   (8088)  ──── delivery tracking
  ├──▶ Review Service     (8089)  ──── order reviews (DynamoDB)
  └──▶ Promotion Service  (8090)  ──── promotion codes

Event-driven only (no HTTP):
  Analytics Service    (8082)  ──── Redis counters, user Saga confirmation
  Payment Service      (8084)  ──── payment processing
  Notification Service  (—)    ──── fan-out to order/payment/delivery events

All HTTP traffic must go through the **Gateway on port 8080**. Direct service ports are internal.

## API Documentation (Swagger UI)

| Service | URL |
|---------|-----|
| User Service | http://localhost:8081/swagger-ui.html |
| Order Service | http://localhost:8083/swagger-ui.html |
| Product Service | http://localhost:8085/swagger-ui.html |

Raw OpenAPI specs available at `/v3/api-docs` on each service port.

## Services

### Gateway Service — Port 8080
Handles JWT validation, rate limiting, and routing.

**Public routes (no JWT access token required):**
| Method | Path | Forwards to | Auth mechanism |
|--------|------|-------------|----------------|
| `POST` | `/api/v1/users` | user-service | None |
| `POST` | `/api/v1/auth/login` | user-service | Username + password |
| `POST` | `/api/v1/auth/refresh` | user-service | Refresh token (validated against Redis) — intentionally public since the access token may be expired |

**Protected routes (JWT required):**
| Method | Path | Forwards to | Rate limit |
|--------|------|-------------|------------|
| `POST` | `/api/v1/auth/logout` | user-service | — |
| `POST/GET` | `/api/v1/orders/**` | order-service | 10 req/s, burst 20 |
| `GET/POST` | `/api/v1/products/**` | product-service | 10 req/s, burst 20 |

**Headers injected into downstream requests:**
- `X-User-Name: {username}` — extracted from JWT subject
- `X-User-Role: {ADMIN|USER|GUEST}` — extracted from JWT role claim

### User Service — Port 8081
Manages user accounts and JWT-based authentication.

#### `POST /api/v1/users` — Register
// Request
{
  "username": "john",
  "password": "secret123",
  "email": "john@example.com",
  "role": "USER"
}

// Response 200
{
  "message": "User registered. Account will be activated shortly."
}

> User is created with `status=PENDING`. Becomes `ACTIVE` after the Saga completes (~1-2s). Only `ACTIVE` users can log in.

#### `POST /api/v1/auth/login`
// Request
{ "username": "john", "password": "secret123" }

// Response 200
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 900000
}

#### `POST /api/v1/auth/refresh`
// Request
{ "refreshToken": "eyJ..." }

// Response 200 — new access + refresh tokens
{ "accessToken": "eyJ...", "refreshToken": "eyJ...", "tokenType": "Bearer", "expiresIn": 900000 }

#### `POST /api/v1/auth/logout`
// Request
{ "refreshToken": "eyJ..." }

// Response 204 No Content

**Token details:**
- Access token — 15 minutes, type claim = `"access"`
- Refresh token — 7 days, stored in Redis (`refresh_token:{username}`), revoked on logout

**User data model:**

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | Primary key |
| `username` | String | Unique |
| `password` | String | BCrypt hashed |
| `role` | Enum | ADMIN, USER, GUEST |
| `status` | Enum | PENDING → ACTIVE (via Saga) |

---

### Order Service — Port 8083

Manages orders. All endpoints require a valid JWT (enforced by the gateway).

#### `POST /api/v1/orders` — Place an order

```json
// Request (X-User-Name header set by gateway)
{
  "items": [
    { "productId": "uuid-here", "quantity": 2 },
    { "productId": "uuid-here", "quantity": 1 }
  ],
  "totalAmount": 29.99
}

// Response 201
{
  "id": "uuid",
  "username": "john",
  "status": "PENDING",
  "items": [
    { "productId": "uuid-here", "quantity": 2 },
    { "productId": "uuid-here", "quantity": 1 }
  ],
  "totalAmount": 29.99,
  "createdAt": "2026-03-27T14:00:00"
}
```

#### `GET /api/v1/orders` — List orders for authenticated user

```json
// Response 200
[
  { "id": "uuid", "username": "john", "status": "PAID", "items": [...], "totalAmount": 29.99, "createdAt": "..." }
]
```

#### `GET /api/v1/orders/{id}` — Get order by ID

```json
// Response 200 (403 if order belongs to another user)
{ "id": "uuid", "username": "john", "status": "PAID", "items": [...], "totalAmount": 29.99, "createdAt": "..." }
```

**Order status transitions:**

```
PENDING → PAID      (payment-service confirms via Kafka)
PENDING → FAILED    (payment failure — not yet wired)
PENDING → CANCELLED (manual cancellation — not yet implemented)
```

**Order data model:**

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | Primary key |
| `username` | String | Owner |
| `status` | Enum | PENDING, CONFIRMED, PAID, CANCELLED, FAILED |
| `items` | TEXT | JSON-serialized `List<OrderItem>` via JPA converter |
| `totalAmount` | BigDecimal | Client-provided |
| `createdAt` | LocalDateTime | Set on creation |

---

### Product Service — Port 8085

Manages the product catalog and stock. GET endpoints require a JWT; POST requires ADMIN role.

#### `GET /api/v1/products` — List products (paginated)

```
// Query params (all optional)
?page=0&size=20&sort=name,asc
?category=PIZZA

// Response 200
{
  "content": [
    { "id": "uuid", "name": "Margherita", "description": "...", "price": 12.99, "category": "PIZZA", "stock": 50 }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 20
}
```

#### `GET /api/v1/products/{id}` — Get product by ID

```json
// Response 200
{ "id": "uuid", "name": "Margherita", "description": "Classic tomato and mozzarella", "price": 12.99, "category": "PIZZA", "stock": 50 }

// Response 404
{ "status": 404, "error": "Not Found", "message": "Product not found: uuid", "timestamp": "..." }
```

#### `POST /api/v1/products` — Create product (ADMIN only)

```json
// Request (requires X-User-Role: ADMIN — set by gateway from JWT)
{
  "name": "Margherita",
  "description": "Classic tomato and mozzarella",
  "price": 12.99,
  "category": "PIZZA",
  "stock": 50
}

// Response 201
{ "id": "uuid", "name": "Margherita", "description": "...", "price": 12.99, "category": "PIZZA", "stock": 50 }

// Response 403 (non-ADMIN user)
{ "status": 403, "error": "Forbidden", "message": "Only ADMIN users can create products", "timestamp": "..." }

// Response 400 (validation failure)
{ "name": "Product name is required", "price": "Price must be positive" }
```

**Available categories:** `BURGER`, `PIZZA`, `PASTA`, `SALAD`, `DESSERT`, `BEVERAGE`, `SIDE`, `OTHER`

**Product data model:**

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | Primary key |
| `name` | String | Required |
| `description` | String | Optional, TEXT |
| `price` | BigDecimal | Must be positive |
| `category` | Enum | See categories above |
| `stock` | int | Must be ≥ 0 |
| `version` | Long | Optimistic lock — prevents concurrent stock update races |

---

### Payment Service — Port 8084

Event-driven only — no HTTP endpoints. Consumes `order-topics`, processes payment, and publishes the result.

**Flow:**
1. Consumes `OrderCreatedEvent` from `order-topics`
2. Creates a `Payment` record with `status=PENDING`
3. Simulates payment processing → sets `status=SUCCESS`
4. Publishes orderId to `order-confirmation-topic` → triggers order-service to mark order `PAID`
5. Publishes `PaymentProcessedEvent` to `payment-topics` (for future consumers)

**Payment data model:**

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | Primary key |
| `orderId` | UUID | Reference to order |
| `username` | String | Order owner |
| `amount` | BigDecimal | From `OrderCreatedEvent` |
| `status` | Enum | PENDING, SUCCESS, FAILED, REFUNDED |
| `createdAt` | LocalDateTime | Set on creation |

---

### Basket Service — Port 8086

Redis-backed shopping basket. All endpoints require a valid JWT.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/basket` | Get current user's basket |
| `POST` | `/api/v1/basket/items` | Add item to basket |
| `DELETE` | `/api/v1/basket/items/{productId}` | Remove specific item |
| `DELETE` | `/api/v1/basket` | Clear entire basket |

No Kafka integration — reads/writes Redis directly.

---

### Kitchen Service — Port 8087

Manages kitchen tickets for incoming orders. Listens on `kitchen-order-topic`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/kitchen/tickets/{ticketId}` | Get ticket by ID |
| `PUT` | `/api/v1/kitchen/tickets/{ticketId}/status` | Update ticket status |

**Kafka listener:** `kitchen-order-topic` (group `kitchen-group`) — receives order-paid events, creates kitchen tickets.

> Producer side not yet wired — `kitchen-order-topic` has no active publisher in the current codebase.

---

### Delivery Service — Port 8088

Tracks delivery tasks from kitchen-ready to delivered. Listens on `delivery-order-topic`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/delivery/orders/{orderId}` | Get delivery status for an order |
| `PUT` | `/api/v1/delivery/{deliveryId}/status` | Update delivery status |

**Kafka listener:** `delivery-order-topic` (group `delivery-group`) — receives order-confirmed events.

> Producer side not yet wired — `delivery-order-topic` has no active publisher in the current codebase.

---

### Review Service — Port 8089

DynamoDB-backed post-delivery review system. Listens on `review-order-topic`.

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/reviews` | Submit a review for a delivered order |
| `GET` | `/api/v1/reviews/{reviewId}` | Get review by ID |
| `GET` | `/api/v1/reviews/orders/{orderId}` | Get all reviews for an order |

**Kafka listener:** `review-order-topic` (group `review-group`) — receives order-delivered events.

> Producer side not yet wired — `review-order-topic` has no active publisher in the current codebase.

---

### Promotion Service — Port 8090

Manages promotion codes. Listens on `user-topics` to issue welcome codes on new user registration.

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/promotions` | Create a promotion code (ADMIN) |
| `GET` | `/api/v1/promotions/{code}` | Look up a promotion code |

**Kafka listener:** `user-topics` (group `promotion-group`) — receives `UserCreatedEvent`, issues welcome promotion codes.

---

### Notification Service — No HTTP

Event-driven only — no HTTP endpoints. Fans out to multiple event streams for notification delivery.

**Kafka listeners:**
- `order-topics` (group `notification-group`) — order lifecycle events
- `payment-topics` (group `notification-group`) — payment success/failure events
- `delivery-order-topic` (group `notification-group`) — delivery status updates

---

### Analytics Service — Port 8082

Event-driven only — no HTTP endpoints. Maintains Redis counters for user statistics and triggers the user registration Saga.

**Flow:**
1. Consumes `UserCreatedEvent` from `user-topics`
2. Increments Redis counter: `stats:roles:{ROLE}` (e.g. `stats:roles:USER`)
3. Publishes `"SUCCESS"` to `user-confirmation-topic` → triggers user-service to activate the user

---

## Kafka Topics

| Topic | Producer | Consumer(s) | Message type |
|-------|----------|-------------|--------------|
| `user-topics` | user-service | analytics-service, promotion-service | `UserCreatedEvent` |
| `user-confirmation-topic` | analytics-service | user-service | `String` ("SUCCESS") |
| `order-topics` | order-service | payment-service, product-service, notification-service | `OrderCreatedEvent` |
| `order-confirmation-topic` | payment-service | order-service | `PaymentProcessedEvent` |
| `payment-topics` | payment-service | notification-service | `PaymentProcessedEvent` |
| `kitchen-order-topic` | *(not yet wired)* | kitchen-service | order-paid event |
| `delivery-order-topic` | *(not yet wired)* | delivery-service, notification-service | order-confirmed event |
| `review-order-topic` | *(not yet wired)* | review-service | order-delivered event |

### Event schemas

**`UserCreatedEvent`**
```json
{ "userId": "uuid", "email": "john@example.com", "username": "john", "role": "USER" }
```

**`OrderCreatedEvent`**
```json
{
  "orderId": "uuid",
  "username": "john",
  "totalAmount": 29.99,
  "items": [
    { "productId": "uuid", "quantity": 2 }
  ]
}
```

**`PaymentProcessedEvent`**
```json
{ "paymentId": "uuid", "orderId": "uuid", "username": "john", "amount": 29.99, "status": "SUCCESS" }
```

## Saga Flows

### User Registration Saga

Client                  Gateway          User-Service         Analytics-Service
  │                        │                  │                      │
  │─ POST /api/v1/users ──▶│                  │                      │
  │                        │── route ────────▶│                      │
  │                        │                  │ save User(PENDING)   │
  │                        │                  │── UserCreatedEvent ─▶│
  │◀── 200 "pending..." ───│◀─────────────────│                      │ incr Redis counter
  │                        │                  │                      │── "SUCCESS" ──▶│
  │                        │                  │◀─ user-confirmation-topic ───────────│
  │                        │                  │ update User(ACTIVE)
  │
  │  (wait ~1-2s)
  │── POST /api/v1/auth/login ──▶ ...

### Order Creation Saga

Client          Gateway       Order-Service      Payment-Service     Product-Service
  │               │               │                   │                   │
  │─ POST /orders▶│               │                   │                   │
  │               │── route ─────▶│                   │                   │
  │               │               │save Order(PENDING)│                   │
  │               │               │── OrderCreatedEvent ─────────────────▶│
  │               │               │── OrderCreatedEvent ──▶│              │ reserve stock
  │◀── 201 ───────│◀──────────────│                   │                   │
  │                               │                   │ save Payment      │
  │                               │                   │ (PENDING→SUCCESS) │
  │                               │◀── order-confirmation-topic ──────────│
  │                               │ update Order(PAID)│

> **Note:** The order Saga currently handles only the happy path. Compensation logic (payment failure → cancel order, release reserved stock) is planned but not yet implemented.

## Developer Skills (Claude Code)

Project-scoped Claude Code skills live in `.claude/skills/`. They are available as slash commands when working in this repo with Claude Code.

| Skill | Trigger | Type | What it does |
|-------|---------|------|--------------|
| `/health` | Manual or auto | Read-only | Curls all actuator endpoints and prints a UP/DOWN status table |
| `/logs [service]` | Manual or auto | Read-only | No arg: lists services. With arg: `docker compose logs -f <service>` |
| `/start` | Manual only | Side effects | Runs `./start.sh`, then verifies all services and prints access URLs |
| `/saga-test` | Manual only | Side effects | End-to-end Saga test: register → login → happy-path order (PAID) + failure-path order (FAILED) |
| `/commit` | Manual only | Side effects | Stages changes, generates a conventional commit message, and commits (does not push) |

**Session briefing** is handled automatically via a `SessionStart` hook that injects `docs/plan/STATUS.md` into Claude's context at the start of every session — no skill invocation needed.

**Manual-only** skills require explicit invocation — Claude will not run them automatically because they have side effects.

---

## Consumer Groups

| Group | Service | Listens on |
|-------|---------|------------|
| `analytics-group` | analytics-service | `user-topics` |
| `user-group` | user-service | `user-confirmation-topic` |
| `order-group` | order-service | `order-confirmation-topic` |
| `payment-group` | payment-service | `order-topics` |
| `product-group` | product-service | `order-topics` |
| `kitchen-group` | kitchen-service | `kitchen-order-topic` |
| `delivery-group` | delivery-service | `delivery-order-topic` |
| `review-group` | review-service | `review-order-topic` |
| `promotion-group` | promotion-service | `user-topics` |
| `notification-group` | notification-service | `order-topics`, `payment-topics`, `delivery-order-topic` |