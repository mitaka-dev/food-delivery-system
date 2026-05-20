# Food Delivery System — System Design Overview

> Plain-language explanation of every service: which database it uses, why that database was chosen, how it communicates with other services, and which patterns it applies.

---

## The Whole System at a Glance

```
  CUSTOMER / RESTAURANT / DRIVER APP
              │
              ▼
      ┌───────────────┐
      │  API Gateway  │  ← checks JWT on every request
      └───────┬───────┘
              │ REST
  ┌───────────┼──────────────────────────────┐
  │           │   EDGE TIER                  │
  │  ┌────────▼───┐  ┌──────────┐  ┌───────┐ │
  │  │    USER    │  │ PRODUCT  │  │BASKET │ │
  │  │ Postgres   │  │ DynamoDB │  │ Redis │ │
  │  │ Redis      │  │ Redis    │  │ only  │ │
  │  └────────────┘  └──────────┘  └───────┘ │
  └──────────────────────────────────────────┘
              │ gRPC (Basket→Product, Order→Promotion)
              │
  ┌───────────┼──────────────────────────────┐
  │           │   CORE TIER                  │
  │  ┌────────▼───┐  ┌──────────┐  ┌───────┐ │
  │  │   ORDER    │  │ PAYMENT  │  │PROMO  │ │
  │  │ Postgres   │  │ DynamoDB │  │Postgr.│ │
  │  │ (+ outbox) │  │ (ledger) │  │       │ │
  │  └────────────┘  └──────────┘  └───────┘ │
  └──────────────────────────────────────────┘
              │
  ┌───────────┼──────────────────────────────┐
  │           │   FULFILLMENT TIER           │
  │  ┌────────▼───┐  ┌──────────┐  ┌───────┐ │
  │  │  KITCHEN   │  │DELIVERY  │  │REVIEW │ │
  │  │ DynamoDB   │  │ Postgres │  │Dynamo │ │
  │  └────────────┘  └──────────┘  └───────┘ │
  └──────────────────────────────────────────┘
              │
  ┌───────────▼──────────────────────────────┐
  │         ASYNC MESSAGING LAYER            │
  │                                          │
  │  Kafka (MSK)              SQS queues     │
  │  ─────────────            ───────────    │
  │  user-events              charge-payment │
  │  order-events             basket-comp.   │
  │  payment-events           kitchen-comp.  │
  │  kitchen-events           delivery-comp. │
  │  delivery-events          stripe-webhooks│
  │  promotion-events                        │
  └──────────────────────────────────────────┘
              │
  ┌───────────▼──────────────────────────────┐
  │     NOTIFICATION (AWS Lambda)            │
  │     DynamoDB (deduplication only)        │
  └──────────────────────────────────────────┘
```

---

## Per-Service: What, Why, and How

### User Service → PostgreSQL + Redis

Handles registration, login, and JWT token issuance. PostgreSQL is needed because registration has to be atomic: write the user row AND an outbox event (`USER_CREATED`) in one transaction — if either fails, both roll back. Redis holds refresh tokens (30-day TTL) and caches the JWT public key so other services don't have to call User Service on every request; they just verify the signature locally.

---

### Product Service → DynamoDB + Redis + S3

The "digital menu" for all restaurants. DynamoDB holds product data because a menu is a flexible, nested document (categories → items → modifiers), accessed almost always by `restaurantId` — a single-key lookup with no joins needed. Redis sits in front as a 30-minute cache because menus are read thousands of times for every one write. Product images live in S3 with CloudFront CDN. This service also exposes a **gRPC endpoint** so Basket Service can ask "is this item still available and what's the current price?" in real time before adding to cart.

---

### Basket Service → Redis only

Carts are temporary — a user's shopping session. Redis is the primary (and only) store: carts are stored as Redis hashes keyed by `userId` and auto-expire after 24 hours. There's no PostgreSQL here because you don't need a transaction log of "abandoned carts." Before adding any item, Basket makes a **gRPC call** to Product Service to confirm the item is still active and the price hasn't changed.

---

### Order Service → PostgreSQL (the most important service)

The **Saga Leader** — it orchestrates the entire order lifecycle across multiple services. PostgreSQL is essential here because it uses the **Outbox Pattern**: every time the saga advances to a new state, it writes the new state AND the next command (e.g., "charge this payment") into the same database transaction. Either both happen or neither does — no inconsistent state. It publishes domain events to **Kafka** (`order-events`) and compensation commands to **SQS** (e.g., `RESTORE_BASKET` if payment fails).

---

### Payment Service → DynamoDB

Receives a `CHARGE_PAYMENT` command from an **SQS queue** (not a REST call — order-service drops a message and walks away). Before calling Stripe, it checks DynamoDB for the order ID — if it's already been charged (duplicate message), it returns the prior result without charging twice. This is **idempotency**. The DynamoDB table is an append-only ledger: every charge, failure, or refund is a new row, never an update. Results go back to Kafka (`payment-events`) so Order Service can move the saga forward.

---

### Kitchen Service → DynamoDB

Tracks the "ticket" for each order being prepared. DynamoDB is used because the access pattern is simple: "give me all active tickets for restaurant X." It uses an atomic DynamoDB counter to track how many orders are in-flight — when a restaurant hits its limit, Kitchen publishes a `RESTAURANT_PAUSED` event to Kafka, and Product Service reacts by hiding that restaurant from search results.

---

### Delivery Service → PostgreSQL

Manages driver task assignment. PostgreSQL is chosen specifically for one reason: the **claim race**. When a food-ready notification goes out to nearby drivers, multiple drivers may try to claim the same delivery at the same moment. PostgreSQL's `SELECT … FOR UPDATE NOWAIT` gives exactly one driver the lock — all others instantly get a `409 Conflict`. No DynamoDB equivalent handles this as cleanly.

---

### Promotion Service → PostgreSQL

Issues and validates discount codes. PostgreSQL's **unique constraints** enforce the business rule "one promo code per user per type" at the database level — even if two requests arrive simultaneously, the DB rejects the duplicate. When order-service needs to validate a code during checkout, it makes a **gRPC call** to Promotion Service. If the order later gets canceled, Order Service issues a `RESTORE_PROMO_CODE` compensation command via SQS.

---

### Review Service → DynamoDB

Stores ratings for restaurants, drivers, and meals — three different shapes of data. DynamoDB is ideal because the schema is flexible (restaurant reviews have different fields than driver reviews), and the access pattern is simple (`GET reviews WHERE restaurantId=X`). A **DynamoDB Streams Lambda** watches for new reviews and maintains aggregate stats (average rating, review count) in a separate table — so you can read aggregates instantly without scanning all reviews.

---

### Notification Service → Lambda + DynamoDB

Triggered by Kafka events (a native Lambda → MSK integration). It fetches a Mustache template from S3, renders it with the event payload, and sends via SES (email) or SNS (push notification). DynamoDB is used only for deduplication: before sending anything, it checks whether `(eventId, channel, recipient)` was already processed — because Lambda can be invoked more than once for the same Kafka message.

---

## The Three Cross-Cutting Patterns

### Outbox

Solves: *"what if we save to DB but Kafka crashes right after?"*

Write the business data AND the event payload into the same DB transaction as two rows. A separate background process reads unprocessed rows and publishes them to Kafka/SQS. The DB commit is the source of truth — the message will always eventually be sent.

```
  App code (one transaction):
    INSERT INTO orders (...)     ← business state
    INSERT INTO outbox (...)     ← event payload

  Background publisher (separate process):
    SELECT * FROM outbox WHERE processed_at IS NULL
    → publish to Kafka or SQS
    → UPDATE outbox SET processed_at = NOW()
```

### Saga

Solves: *"how do we coordinate a multi-step transaction across 5 services with no distributed lock?"*

Order Service is the coordinator. It tracks which step the order is on, sends commands to each service in sequence, and if anything fails, it sends *compensation commands* (undo operations) to reverse the steps that already succeeded.

```
  Happy path:
    PENDING → PAID → COMPLETED

  Payment fails:
    PENDING → COMPENSATING → CANCELED
               └─ sends RESTORE_BASKET to basket-service
               └─ waits for BASKET_RESTORED ack
               └─ only then transitions to CANCELED
```

### Idempotency

Solves: *"what if a message is delivered twice?"* (Kafka and SQS both guarantee at-least-once delivery, not exactly-once.)

Every operation that changes state accepts an idempotency key (an order ID, a UUID). Before doing any work, the service checks "have I already processed this key?" If yes, return the previous result and do nothing. This makes it safe to retry any operation any number of times.

---

## Database Choice Summary

| Service | Database | Why |
|---|---|---|
| User | PostgreSQL + Redis | ACID for outbox; Redis for token/session cache |
| Product | DynamoDB + Redis | Flexible menu schema; Redis cache for read-heavy load |
| Basket | Redis only | Carts are temporary; sub-ms reads; TTL handles expiry |
| Order | PostgreSQL | Outbox pattern needs ACID; saga state needs transactions |
| Payment | DynamoDB | Append-only ledger; single-key idempotency lookups |
| Kitchen | DynamoDB | Simple key lookups; atomic counters for capacity |
| Delivery | PostgreSQL | `FOR UPDATE NOWAIT` for claim-race locking |
| Promotion | PostgreSQL | Unique constraints enforce one-code-per-user rules |
| Review | DynamoDB | Flexible schema per entity type; high write throughput |
| Notification | Lambda + DynamoDB | Stateless compute; DynamoDB only for deduplication |

## Messaging Choice Summary

| Channel | Used for | Why not the other |
|---|---|---|
| **Kafka** | Domain events (`ORDER_PAID`, `USER_CREATED`, `FOOD_READY`) | Replay-capable — new consumers can read past events |
| **SQS** | Saga compensation commands (`RESTORE_BASKET`, `CHARGE_PAYMENT`) | Point-to-point; time-bound; replaying a compensation command would be wrong |
| **gRPC** | Synchronous internal calls (Basket→Product, Order→Promotion) | Lower latency than REST; strongly typed contracts via `.proto` files |
| **REST** | All public-facing APIs via API Gateway | Standard; JWT auth handled at the gateway |
