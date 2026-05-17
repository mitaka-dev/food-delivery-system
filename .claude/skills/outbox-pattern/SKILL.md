---
name: outbox-pattern
description: Reliable event publishing via the transactional outbox pattern. Use when implementing Kafka event publishing, saga steps, or any @Transactional method that also sends a message.
allowed-tools: Read, Edit, Write
---

# Outbox Pattern

## Why It Exists

Writing to a database and sending a Kafka message in two separate operations causes inconsistency:
the DB write might succeed but the Kafka send fail (or crash between the two), leaving the
system in an inconsistent state. The outbox pattern solves this by writing both atomically inside
a single DB transaction. A separate publisher then delivers reliably.

**Current approach in this project**: direct Kafka publish inside `@Transactional` methods (no
outbox table yet). This is the existing pattern across order-service, payment-service, etc.
The full outbox table + CDC/polling publisher is a future phase. Both patterns are documented here.

---

## Existing Pattern (current codebase)

Used in `order-service`, `payment-service`, `product-service`:

```java
@Transactional
public OrderDto create(String username, CreateOrderRequestDto request) {
    Order order = buildOrder(username, request);
    orderRepository.save(order);                         // DB write
    kafka.send(KafkaConstants.ORDER_TOPIC,               // Kafka publish (best-effort)
               new OrderCreatedEvent(order.getId(), username, order.getTotalAmount(), items));
    return toDto(order);
}
```

**Risk**: if the JVM crashes after `save()` but before `kafka.send()`, the event is lost.
Acceptable for the current development phase; address with outbox table in production hardening.

---

## Full Outbox Pattern (production target)

### Step 1 — Outbox table migration

```sql
CREATE TABLE outbox (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50)  NOT NULL,   -- e.g. 'ORDER'
    aggregate_id    VARCHAR(100) NOT NULL,   -- e.g. order UUID
    event_type      VARCHAR(100) NOT NULL,   -- e.g. 'ORDER_CREATED'
    partition_key   VARCHAR(100) NOT NULL,   -- used as Kafka message key
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
```

### Step 2 — Write to outbox inside the same transaction

```java
@Transactional
public OrderDto create(String username, CreateOrderRequestDto request) {
    Order order = buildOrder(username, request);
    orderRepository.save(order);

    // Write event to outbox in the SAME transaction — atomic with the state change
    outboxRepository.save(OutboxEntry.builder()
        .aggregateType("ORDER")
        .aggregateId(order.getId().toString())
        .eventType("ORDER_CREATED")
        .partitionKey(order.getId().toString())
        .payload(objectMapper.writeValueAsString(new OrderCreatedEvent(...)))
        .build());

    return toDto(order);
}
```

### Step 3 — Outbox publisher (polling or CDC)

A `@Scheduled` publisher polls `WHERE published_at IS NULL`, sends to Kafka, marks published:

```java
@Scheduled(fixedDelay = 1000)
@Transactional
public void publishPendingEvents() {
    List<OutboxEntry> pending = outboxRepository.findUnpublished(Pageable.ofSize(100));
    for (OutboxEntry entry : pending) {
        kafka.send(topicFor(entry.getEventType()), entry.getPartitionKey(), entry.getPayload());
        entry.setPublishedAt(Instant.now());
    }
    outboxRepository.saveAll(pending);
}
```

---

## Current Saga Flow (this project)

```
POST /api/v1/orders
  → order-service: saves Order{PENDING}, publishes OrderCreatedEvent on order-topics
      ↓
  product-service: reserves stock (or fails → publishes stock-failure event)
  payment-service: processes payment, publishes PaymentProcessedEvent on payment-topics
      ↓
  order-service: consumes PaymentProcessedEvent → updates Order to PAID or FAILED
  product-service: if FAILED → releases reserved stock (compensation)
```

Key files:
- `order-service/.../listener/PaymentResultListener.java` — saga completion
- `payment-service/.../listener/OrderEventListener.java` — payment trigger
- `product-service/.../service/StockReservationService.java` — inventory saga step
- `common-libs/.../constants/KafkaConstants.java` — all topic/group names

---

## Anti-Patterns

| Anti-pattern | Fix |
|---|---|
| `kafka.send()` BEFORE `repository.save()` | Always save to DB first |
| `kafka.send()` outside a `@Transactional` method | Move inside transaction boundary |
| Inline topic string (`"order-topics"`) | Use `KafkaConstants.ORDER_TOPIC` |
| Ignoring `kafka.send()` return value | Log failures; in production use outbox for guaranteed delivery |
