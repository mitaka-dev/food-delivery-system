# Common Conventions — Food Delivery Platform

> **Purpose**: This document defines the cross-cutting conventions every service in the platform must follow. These are the contracts that make 10 microservices feel like one coherent platform.
>
> **Where this lives**: This document is the reference. The actual implementations live in `common-libs/` as code that services import via the `platform-bom`. Both must stay in sync — if you change a convention, update both this document and the corresponding shared library.
>
> **Companion documents**: `architecture.md` (the *what* and *why*), `build-plan.md` (the *how*).

---

## Table of Contents

1. [Error Response Format](#1-error-response-format)
2. [Error Code Catalog](#2-error-code-catalog)
3. [Standard HTTP Headers](#3-standard-http-headers)
4. [Pagination Contract](#4-pagination-contract)
5. [Authentication & Authorization](#5-authentication--authorization)
6. [Health & Operational Endpoints](#6-health--operational-endpoints)
7. [Structured Logging Format](#7-structured-logging-format)
8. [Event Envelope Format](#8-event-envelope-format)
9. [Money Type](#9-money-type)
10. [Timestamp Handling](#10-timestamp-handling)
11. [Identifier Conventions](#11-identifier-conventions)
12. [Rate Limiting](#12-rate-limiting)
13. [Idempotency-Key Handling](#13-idempotency-key-handling)
14. [Resilience Pattern Naming](#14-resilience-pattern-naming)
15. [Build Conventions](#15-build-conventions)
16. [API Versioning](#16-api-versioning)
17. [Event Schema Versioning](#17-event-schema-versioning)
18. [Library Versioning](#18-library-versioning)
19. [Naming Conventions Across the Platform](#19-naming-conventions-across-the-platform)
20. [Spring Boot Configuration Conventions](#20-spring-boot-configuration-conventions)
21. [Database Conventions](#21-database-conventions)
22. [Testing Conventions](#22-testing-conventions)
23. [Commit & PR Conventions](#23-commit--pr-conventions)

---

## 1. Error Response Format

Every service returns errors in the same shape, following RFC 7807 (`application/problem+json`) with platform-specific extensions.

**Schema:**

```json
{
  "type": "https://api.{org}.com/errors/{ERROR_CODE}",
  "title": "Human-readable short summary",
  "status": 409,
  "code": "PROMO_CODE_EXPIRED",
  "detail": "More verbose human-readable explanation with specifics.",
  "instance": "/v1/orders/ord_abc123",
  "traceId": "1-65b2f8a1-1234567890abcdef",
  "timestamp": "2026-05-08T14:23:11.123Z",
  "errors": [
    {
      "field": "promoCode",
      "code": "EXPIRED",
      "message": "Code expired 23 days ago"
    }
  ]
}
```

**Field semantics:**

| Field | Required | Purpose |
|---|---|---|
| `type` | Yes | Stable URL identifier; doesn't have to resolve. Format: `https://api.{org}.com/errors/{ERROR_CODE}`. |
| `title` | Yes | Short human-readable summary. May be localized. |
| `status` | Yes | HTTP status code as an integer. |
| `code` | Yes | **Machine-readable error code (the canonical identifier for client switch logic).** From the error code catalog. Never localized. |
| `detail` | No | Verbose human-readable explanation. May be localized. |
| `instance` | Yes | The request URI path that produced the error. |
| `traceId` | Yes | Always populated. Links to X-Ray. |
| `timestamp` | Yes | ISO-8601 UTC with millisecond precision. |
| `errors` | No | Array for validation failures with field-level detail. |

**Critical rule:** Client code switches on `code`, never on `title` or `detail`. Both of those may be localized; `code` is stable across all locales.

**Implementation:**
- Lives in `common-libs/common-exceptions/src/main/java/.../errors/ErrorResponse.java`
- A `@RestControllerAdvice` class in `common-exceptions` converts thrown domain exceptions to this shape.
- Content-Type header: `application/problem+json` for error responses (not `application/json`).
- All services depend on `common-exceptions` via the BOM.

---

## 2. Error Code Catalog

A central enumeration in `common-exceptions/src/main/java/.../errors/ErrorCode.java`. All codes are uppercase snake_case, namespaced by domain.

### Authentication & Authorization
- `AUTH_INVALID_CREDENTIALS` — wrong email/password
- `AUTH_TOKEN_EXPIRED` — JWT past expiration
- `AUTH_TOKEN_INVALID` — JWT signature invalid or malformed
- `AUTH_TOKEN_REVOKED` — JWT in denylist
- `AUTH_INSUFFICIENT_ROLE` — token valid but role doesn't authorize this action
- `AUTH_ACCOUNT_LOCKED` — too many failed login attempts
- `AUTH_REFRESH_TOKEN_REUSE_DETECTED` — refresh token replay attack suspected

### User Management
- `USER_NOT_FOUND`
- `USER_EMAIL_TAKEN`
- `USER_PHONE_TAKEN`
- `USER_PASSWORD_TOO_WEAK`
- `USER_INVALID_EMAIL_FORMAT`

### Order Management
- `ORDER_NOT_FOUND`
- `ORDER_NOT_CANCELABLE` — order in a state that doesn't allow cancellation
- `ORDER_INVALID_STATE_TRANSITION` — internal saga error; should never reach client
- `ORDER_BASKET_LOCKED` — checkout already in progress for this basket
- `ORDER_BASKET_EXPIRED` — basket lock auto-expired before order completion
- `ORDER_RESTAURANT_PAUSED` — restaurant accepted a pause during checkout

### Payment
- `PAYMENT_DECLINED` — gateway declined the charge
- `PAYMENT_DUPLICATE_REQUEST` — idempotency key reused with different body
- `PAYMENT_GATEWAY_UNAVAILABLE` — circuit breaker open
- `PAYMENT_REFUND_NOT_FOUND`
- `PAYMENT_REFUND_ALREADY_PROCESSED`

### Promotion
- `PROMO_CODE_NOT_FOUND`
- `PROMO_CODE_EXPIRED`
- `PROMO_CODE_ALREADY_USED`
- `PROMO_CODE_INELIGIBLE` — order doesn't meet conditions (min amount, etc.)
- `PROMO_CODE_RESERVED_BY_OTHER_ORDER`

### Basket
- `BASKET_ITEM_UNAVAILABLE` — Menu Service reports item disabled
- `BASKET_PRICE_CHANGED` — price now differs from when added
- `BASKET_RESTAURANT_PAUSED` — restaurant paused during basket session
- `BASKET_LIMIT_EXCEEDED` — > 50 items
- `BASKET_DIFFERENT_RESTAURANT` — adding from a second restaurant requires explicit clear

### Kitchen
- `KITCHEN_TICKET_NOT_FOUND`
- `KITCHEN_INVALID_STATE_TRANSITION`
- `KITCHEN_RESTAURANT_AT_CAPACITY`

### Delivery
- `DELIVERY_TASK_NOT_FOUND`
- `DELIVERY_TASK_ALREADY_CLAIMED` — race condition loser
- `DELIVERY_TASK_NOT_AVAILABLE` — task in a state that doesn't allow claim
- `DELIVERY_DRIVER_OFFLINE`

### Review
- `REVIEW_WINDOW_CLOSED` — past 7-day deadline
- `REVIEW_ALREADY_SUBMITTED`
- `REVIEW_EDIT_WINDOW_EXPIRED` — past 24h since submission
- `REVIEW_NOT_FOUND`

### Generic / Cross-cutting
- `RATE_LIMITED` — request throttled
- `IDEMPOTENCY_KEY_REUSE` — same key, different body
- `IDEMPOTENCY_KEY_REQUIRED` — write endpoint missing the header
- `SERVICE_UNAVAILABLE` — circuit breaker open or downstream timeout
- `DEPENDENCY_CIRCUIT_OPEN` — known downstream issue
- `VALIDATION_FAILED` — request body failed schema validation
- `INTERNAL_ERROR` — catch-all; should be rare; always paged on
- `CONFLICTING_UPDATE` — optimistic lock failure

**Rule for adding new codes:** Each service contributes its codes via a static initializer block in `ErrorCode.java`; PRs adding codes require platform team review.

---

## 3. Standard HTTP Headers

### Request headers (consumed by services)

| Header | Purpose | Required | Notes |
|---|---|---|---|
| `Authorization: Bearer {jwt}` | JWT access token | All authenticated endpoints | Validated by `JwtAuthenticationFilter` |
| `Idempotency-Key: {uuid}` | Client-supplied idempotency token | All POST/PATCH/DELETE on resources | Required; UUID v4 format; 24h TTL |
| `X-Request-ID: {uuid}` | Client correlation ID | Optional | Service generates if absent; echoed in response |
| `X-Client-Version: {semver}` | Client app version | Optional, encouraged | Logged for compatibility tracking |
| `Accept-Language: {locale}` | User locale | Optional | Defaults to `en`; only affects messages, not data |
| `User-Agent` | Client identification | Standard | Logged for analytics |
| `traceparent`, `tracestate` | W3C Trace Context | Standard | Auto-handled by OTel agent; do not parse manually |

### Response headers (emitted by services)

| Header | When | Purpose |
|---|---|---|
| `X-Request-ID` | Always | Echoed from request or generated server-side |
| `X-Trace-ID` | Always | Same value as `traceId` in error/log |
| `X-RateLimit-Limit` | Rate-limited endpoints | Configured limit |
| `X-RateLimit-Remaining` | Rate-limited endpoints | Tokens remaining in current window |
| `X-RateLimit-Reset` | Rate-limited endpoints | Unix timestamp when window resets |
| `Retry-After` | 429, 503 responses | Seconds the client should wait before retrying |
| `Cache-Control` | Cacheable responses | Per-endpoint policy; `no-store` for write responses |
| `WWW-Authenticate: Bearer error="..."` | 401 responses | RFC 6750-compliant; details which token check failed |

**Implementation:** A `HeaderInterceptor` in `common-observability` registers as a Spring `HandlerInterceptor` and ensures these are present.

---

## 4. Pagination Contract

Every list endpoint follows the same shape.

**Request:**
```
GET /v1/orders?cursor={opaque-token}&limit=20&sort=-createdAt
```

| Parameter | Required | Default | Max | Notes |
|---|---|---|---|---|
| `cursor` | No | `null` | — | Opaque base64-encoded JSON; from a previous response |
| `limit` | No | 20 | 100 | Number of items |
| `sort` | No | per-endpoint | — | Field name; `-` prefix for descending |

**Response:**
```json
{
  "items": [...],
  "pageInfo": {
    "nextCursor": "eyJjcmVhdGVkX2F0IjoiM...",
    "hasMore": true,
    "total": 1247
  }
}
```

| Field | Required | Notes |
|---|---|---|
| `items` | Yes | Array of resources, possibly empty |
| `pageInfo.nextCursor` | When `hasMore=true` | Opaque base64-encoded JSON |
| `pageInfo.hasMore` | Yes | Whether more items exist beyond this page |
| `pageInfo.total` | Optional | Best-effort; may be approximate for large tables; omit if expensive |

**Cursor format (internal, never exposed to clients):**
```json
{ "createdAt": "2026-05-08T14:23:11.123Z", "id": "ord_xyz789" }
```
Base64-encoded so it survives URL transport without escaping.

**Why cursor not offset:** stable under concurrent inserts; no skipped or duplicated items.

**Implementation:** `PaginationCursor` and `PageInfo` records live in `common-dto`. A `CursorEncoder` utility handles base64 encoding/decoding.

---

## 5. Authentication & Authorization

### Token issuance (Identity Service only)
- **Algorithm**: RS256 (asymmetric; services validate without ever holding the private key)
- **Access token**: 15-minute TTL
- **Refresh token**: 30-day TTL, stored hashed (SHA-256) in DB, opaque to client
- **Refresh rotation**: every refresh issues a new pair AND invalidates the previous refresh token (reuse detection: if a previously-rotated refresh token is presented, all the user's tokens are revoked)
- **Lockout**: 5 failed login attempts in 15 minutes triggers a 15-minute lockout per (email, IP) pair

### Token validation (every service)
- Filter chain order: tracing → rate limiting → JWT validation → authorization
- Validation steps:
  1. Read `Authorization: Bearer {token}` header
  2. Validate signature against public key (cached from SSM Parameter Store, 1-hour TTL)
  3. Validate `exp` (expiration), `nbf` (not-before), `iss` (issuer), `aud` (audience)
  4. Check `jti` against Redis denylist
  5. Populate `SecurityContext` with role + IDs
- Returns 401 with `WWW-Authenticate: Bearer error="invalid_token"` and an `error_description` per RFC 6750

### JWT claims schema

```json
{
  "iss": "https://api.{org}.com/auth",
  "sub": "usr_abc123",
  "aud": "{org}-platform",
  "exp": 1746718991,
  "iat": 1746718091,
  "nbf": 1746718091,
  "jti": "jti_def456",
  "role": "CUSTOMER",
  "email": "alice@example.com",
  "restaurantId": null,
  "driverId": null,
  "scopes": ["orders:read", "orders:write", "basket:write"]
}
```

| Claim | Type | Notes |
|---|---|---|
| `sub` | string | User ID — always `usr_*` |
| `role` | enum | One of `CUSTOMER`, `RESTAURANT_OWNER`, `DRIVER`, `ADMIN` |
| `restaurantId` | string \| null | Set only for `RESTAURANT_OWNER` |
| `driverId` | string \| null | Set only for `DRIVER` |
| `scopes` | array | Fine-grained capabilities; checked alongside role |

### Authorization patterns

Use Spring Security `@PreAuthorize` with custom expressions:

```java
@PreAuthorize("hasRole('CUSTOMER')")  // role only

@PreAuthorize("hasRole('RESTAURANT_OWNER') and #restaurantId == authentication.principal.restaurantId")
// owner can only modify their own restaurant

@PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN') and " +
              "(#order.customerId == authentication.principal.userId or hasRole('ADMIN'))")
// customer sees own orders; admin sees all
```

**Never** authorize via gateway alone — every service enforces independently. Defense in depth.

---

## 6. Health & Operational Endpoints

Every service exposes these endpoints (Spring Boot Actuator).

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /actuator/health/liveness` | None | K8s liveness probe — is the JVM alive |
| `GET /actuator/health/readiness` | None | K8s readiness probe — is the service ready for traffic |
| `GET /actuator/health` | Internal-only | Full health detail; used by humans for diagnosis |
| `GET /actuator/info` | Internal-only | Service name, version, git commit, build time |
| `GET /actuator/prometheus` | Internal-only | Prometheus scrape endpoint |
| `GET /actuator/metrics` | Internal-only | Micrometer metric registry inspection |
| `GET /actuator/loggers` | Admin role | View/modify log levels at runtime |
| `GET /actuator/env` | Admin role | View configuration (sensitive values masked) |
| `GET /actuator/threaddump` | Admin role | Thread dump for debugging |
| `GET /actuator/heapdump` | Admin role | Heap dump (use carefully — large payload) |

### Liveness probe behavior
Always returns 200 unless the process is genuinely broken (deadlock, OOM-imminent). **Never** fails on downstream dependency issues — that's what readiness is for.

### Readiness probe behavior
Returns 200 only when:
- Database connection pool has at least one available connection
- Kafka producer is initialized and connected
- Redis cluster is reachable (if used by this service)
- Required configuration is loaded

Returns 503 during:
- Application startup (until all the above are ready)
- Graceful shutdown (so K8s removes the pod from service before terminating)

**Critical:** readiness does **not** check downstream services. Each downstream has its own circuit breaker. If Promotion is down, Order's readiness should still return 200.

### `/actuator/info` payload

```json
{
  "build": {
    "name": "order-service",
    "version": "1.4.2",
    "time": "2026-05-08T14:23:11.000Z",
    "javaVersion": "25.0.1"
  },
  "git": {
    "branch": "main",
    "commit": {
      "id": "abc123def",
      "time": "2026-05-08T14:00:00.000Z"
    }
  }
}
```

Populated by `org.springframework.boot:spring-boot-starter-actuator` + Maven git-commit-id-plugin. No additional code needed per service.

---

## 7. Structured Logging Format

Every log line is JSON with a fixed minimum schema.

**Schema:**

```json
{
  "timestamp": "2026-05-08T14:23:11.123Z",
  "level": "INFO",
  "logger": "com.acme.order.service.OrderCreationService",
  "message": "Order created",
  "service": "order-service",
  "version": "1.4.2",
  "env": "production",
  "traceId": "1-65b2f8a1-1234567890abcdef",
  "spanId": "abcdef1234567890",
  "userId": "usr_abc123",
  "thread": "http-nio-8080-exec-4",
  "context": {
    "orderId": "ord_xyz789",
    "restaurantId": "rest_def456",
    "amount": "24.99"
  }
}
```

| Field | Always present | Source |
|---|---|---|
| `timestamp` | Yes | ISO-8601 UTC with milliseconds |
| `level` | Yes | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `logger` | Yes | Fully-qualified class name |
| `message` | Yes | The log message |
| `service` | Yes | From environment variable `SERVICE_NAME` |
| `version` | Yes | From environment variable `SERVICE_VERSION` |
| `env` | Yes | From environment variable `ENV` (`local`, `staging`, `production`) |
| `traceId` | When in a span | From OTel context |
| `spanId` | When in a span | From OTel context |
| `userId` | When authenticated | From `SecurityContext` via MDC |
| `thread` | Yes | Current thread name |
| `context` | Optional | Domain-specific fields via `MDC.put()` |
| `exception` | When logging exception | Full stack trace as nested object |

**Configuration:** `logback-spring.xml` in `common-observability` configures the `LogstashEncoder` to produce this shape. No `System.out.println` ever; use SLF4J.

**Rule:** never log PII (full email, full name, address, payment details) at INFO level. WARN/ERROR can include enough to debug. DEBUG can include more, but DEBUG is never enabled in prod.

**Convention for adding context fields:**

```java
try (var mdc = MDC.putCloseable("orderId", order.getId())) {
    log.info("Processing payment");
    paymentService.charge(order);
}
// MDC entry auto-removed after try-with-resources
```

---

## 8. Event Envelope Format

Every Kafka event payload is wrapped in a standard envelope.

**Schema:**

```json
{
  "eventId": "evt_01HXKZ8NQR7WVPM4AKDG3F2YBT",
  "eventType": "ORDER_PAID",
  "schemaVersion": 1,
  "occurredAt": "2026-05-08T14:23:11.123Z",
  "traceId": "1-65b2f8a1-1234567890abcdef",
  "aggregateType": "Order",
  "aggregateId": "ord_xyz789",
  "producer": {
    "service": "order-service",
    "version": "1.4.2"
  },
  "payload": {
    "orderId": "ord_xyz789",
    "customerId": "usr_abc123",
    "restaurantId": "rest_def456",
    "amount": "24.99",
    "currency": "USD",
    "paidAt": "2026-05-08T14:23:11.000Z"
  }
}
```

| Field | Purpose |
|---|---|
| `eventId` | Unique per event — UUID v7 (time-ordered) — used for consumer-side dedup |
| `eventType` | The discriminator that routes to the right handler |
| `schemaVersion` | Integer; bumped on backwards-incompatible changes |
| `occurredAt` | When the business event happened (not when the message was published) |
| `traceId` | Propagated from the producing transaction's span |
| `aggregateType` | Domain object class — `Order`, `User`, `Payment`, etc. |
| `aggregateId` | The specific instance — used as Kafka partition key for per-aggregate ordering |
| `producer` | Service + version that emitted the event — aids debugging |
| `payload` | The actual event data — must match the Avro schema for `eventType` in Glue Schema Registry |

**Kafka headers (set in addition to the envelope):**
- `eventType`: same as envelope; used by consumer-side filtering without parsing the payload
- `schemaVersion`: integer
- `traceId`: for OTel propagation
- `contentType`: `application/avro` or `application/json`

**Rule:** consumers always filter on the header, never on the payload. Filtering by header allows lazy deserialization.

**Implementation:** `EventEnvelope` record in `common-events` shared lib. Outbox publisher constructs it.

---

## 9. Money Type

```java
public record Money(BigDecimal amount, String currency) {
    public Money {
        Objects.requireNonNull(amount, "amount required");
        Objects.requireNonNull(currency, "currency required");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("Currency must be ISO 4217 (3 letters)");
        }
        // Always stored at currency-specific scale
        amount = amount.setScale(scaleFor(currency), RoundingMode.UNNECESSARY);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) { /* ... */ }
    public Money times(int multiplier) { /* ... */ }
    public Money percentOf(BigDecimal percent) { /* ... */ }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }
    // Never use .equals() on the inner BigDecimal — scale matters; use compareTo()
}
```

**Rules:**
- **Never** `double` or `float` for money — ever
- Currency is ISO 4217 (3 uppercase letters: `USD`, `EUR`, `GBP`, ...)
- Operations between different currencies throw `CurrencyMismatchException`
- Comparisons use `compareTo`, not `equals`
- Arithmetic uses BigDecimal's methods with explicit `RoundingMode.HALF_EVEN`
- Scale is currency-specific (USD = 2, JPY = 0, BHD = 3) — managed by `Money.scaleFor(currency)`
- JSON serialization: `{"amount": "24.99", "currency": "USD"}` — amount as string to avoid floating-point JSON parsing on the client

**Implementation:** `Money` record + `MoneySerializer`/`MoneyDeserializer` Jackson modules in `common-dto`.

---

## 10. Timestamp Handling

**Storage:**
- PostgreSQL: `TIMESTAMPTZ` (timestamp with time zone, internally always UTC)
- DynamoDB: ISO-8601 string with `Z` suffix in UTC (e.g., `2026-05-08T14:23:11.123Z`)
- Redis: Unix epoch milliseconds (integer) for TTL; ISO-8601 strings for stored timestamps
- Kafka events: ISO-8601 with `Z` suffix in UTC

**Java code:**
- Use `java.time.Instant` for storage and exchange (always UTC, no zone)
- Use `java.time.ZonedDateTime` only for *display* with the user's timezone (rare; mostly receipts and notifications)
- Use `java.time.LocalDate` for date-only fields like birthdays
- Never `java.util.Date` (legacy)
- Never `LocalDateTime` for any timestamp that crosses a service boundary (no zone information)

**API responses:**
- Always ISO-8601 with `Z` suffix
- Always millisecond precision (not microsecond, not second)
- Example: `"createdAt": "2026-05-08T14:23:11.123Z"`

**Display localization:**
- The `Accept-Language` header and user's profile timezone are used for *display only* — never for storage
- Notification templates render times in the user's timezone via Mustache helper `{{formatDateTime occurredAt user.timezone}}`

**Clock injection:**
- All services inject a `Clock` bean (`Clock.systemUTC()` in production, `Clock.fixed(...)` in tests)
- **Never** call `Instant.now()`, `System.currentTimeMillis()`, or `LocalDateTime.now()` directly in business code — always go through the injected `Clock`
- This makes time-sensitive logic (saga timeouts, expiry checks) deterministically testable

---

## 11. Identifier Conventions

All entity IDs follow a typed prefix format: `{type}_{ulid}`.

**Format:** `{prefix}_{ULID}` where ULID is a 26-character lexicographically-sortable identifier.

Why ULID over UUID:
- Lexicographically sortable (newer IDs sort after older ones — useful for indexes)
- Time-based prefix (first 10 chars are millisecond timestamp)
- Same uniqueness guarantees as UUID v4 in the random portion
- Slightly shorter than UUID and more URL-friendly

**Prefix table:**

| Type | Prefix | Example |
|---|---|---|
| User | `usr_` | `usr_01HXKZ8NQR7WVPM4AKDG3F2YBT` |
| Order | `ord_` | `ord_01HXKZ9PMQR4VTKL3B2YD7FZAB` |
| Restaurant | `rest_` | `rest_01HXKZA3WX5YGT2NCVD4F8B9HJ` |
| Menu item | `item_` | `item_01HXKZB4ZY6...` |
| Basket item | `bskt_` | `bskt_01HXKZC...` |
| Payment intent | `pi_` | `pi_01HXKZD...` |
| Refund | `rfd_` | `rfd_01HXKZE...` |
| Promo code (entity) | `promo_` | `promo_01HXKZF...` |
| Promo redemption | `redm_` | `redm_01HXKZG...` |
| Kitchen ticket | `tkt_` | `tkt_01HXKZH...` |
| Delivery task | `dlv_` | `dlv_01HXKZI...` |
| Driver | `drv_` | `drv_01HXKZJ...` |
| Review | `rvw_` | `rvw_01HXKZK...` |
| Event | `evt_` | `evt_01HXKZL...` |
| JWT ID | `jti_` | `jti_01HXKZM...` |
| Idempotency key | (client UUID v4, no prefix) | `550e8400-e29b-41d4-a716-446655440000` |

**Promo code (user-facing, separate from `promo_` entity ID):**
- 8-char alphanumeric uppercase
- Excludes ambiguous chars: `0`, `O`, `1`, `I`, `l`
- Example: `WELCOME20`, `BFRIDAY24`

**Implementation:** `IdGenerator` utility in `common-dto` with one factory method per type: `IdGenerator.user()`, `IdGenerator.order()`, etc. Backed by ULID library.

**Database storage:** stored as `VARCHAR(40)` in PostgreSQL, as `String` in DynamoDB. Indexed.

---

## 12. Rate Limiting

Three tiers of rate limiting:

### Tier 1: Edge (API Gateway WAF)
- IP-based: 2000 req per 5 minutes per IP
- Geo-based: blocked countries (configurable)
- Bot Control: WAF Bot rules
- Configured in Terraform; never in service code

### Tier 2: Service-level
- Configured per endpoint via `@RateLimited` annotation
- Backed by Redis sliding window via Lua script
- Examples:
  - `POST /v1/auth/login`: 10 req/min per IP
  - `POST /v1/auth/register`: 5 req/min per IP
  - `POST /v1/payments/charge`: 100 req/min per service-pod (internal)

### Tier 3: Per-tenant
- Restaurant API endpoints: 1000 req/min per `restaurantId`
- Restaurant menu writes: 100 req/min per `restaurantId`
- Driver claim attempts: 30 req/min per `driverId`

**Annotation usage:**

```java
@RateLimited(
    name = "login",
    keyExpression = "#remoteIp",  // SpEL — what to rate-limit by
    permitsPerWindow = 10,
    windowSeconds = 60
)
@PostMapping("/v1/auth/login")
public TokenResponse login(...) { ... }
```

**Response on limit hit:**
- HTTP 429
- `Retry-After: {seconds}` header
- Error code `RATE_LIMITED`

**Implementation:** `@RateLimited` aspect in `common-resilience`; Redis Lua script for atomic window check.

---

## 13. Idempotency-Key Handling

### Client contract
- Required header on POST/PATCH/DELETE for any endpoint that has side effects
- Header name: `Idempotency-Key`
- Format: UUID v4
- Recommended TTL: 24 hours

### Server behavior
- On first call: process the request, store the response under key `idem:{service}:{userId}:{key}` in Redis with TTL 24h
- On retry with same key + same body: return the cached response
- On retry with same key + *different* body: return `409 Conflict` with code `IDEMPOTENCY_KEY_REUSE`
- On request without the header (when required): return `400 Bad Request` with code `IDEMPOTENCY_KEY_REQUIRED`

### Body-equivalence check
- Hash the request body (SHA-256) and store alongside the response
- On retry, hash the new body and compare
- Must be deterministic — sort JSON keys before hashing (Jackson `ObjectMapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)`)

### Storage shape

```json
{
  "key": "idem:order-service:usr_abc123:550e8400-e29b-41d4-a716-446655440000",
  "value": {
    "bodyHash": "sha256:a1b2c3...",
    "responseStatus": 201,
    "responseBody": "{...}",
    "createdAt": "2026-05-08T14:23:11.000Z"
  },
  "ttl": 86400
}
```

### Special cases
- **Webhooks (Stripe, etc.)**: idempotency key is the upstream event ID, not a client-provided UUID
- **Internal service-to-service**: Order Service uses the order ID as the idempotency key when calling Payment

**Implementation:** `IdempotencyKeyAspect` in `common-resilience` annotated as `@Idempotent`. Aspect intercepts the controller method, checks Redis, returns cached response or proceeds.

---

## 14. Resilience Pattern Naming

Consistent names across services so dashboards and alerts work uniformly.

### Circuit breakers
Format: `{remote-target}-{operation}`

| Name | Purpose |
|---|---|
| `stripe-charge` | Payment Service → Stripe charge API |
| `stripe-refund` | Payment Service → Stripe refund API |
| `menu-grpc-verify` | Basket/Order → Menu Service gRPC verify |
| `promotion-grpc-validate` | Order Service → Promotion gRPC validate |
| `promotion-grpc-redeem` | Order Service → Promotion gRPC redeem |

### Retries
Same naming as circuit breakers. Configured separately so retry attempts can be tuned independently.

### Bulkheads
Format: `{operation-class}` — describes the *kind* of work being isolated, not the target.

| Name | Purpose |
|---|---|
| `payment-charge` | Charge calls (separate from refund) |
| `payment-refund` | Refund calls (separate from charge) |
| `order-saga-listener` | Saga event listener thread pool |
| `kitchen-ticket-listener` | Ticket event listener thread pool |

### Time limiters
Same naming as circuit breakers.

### Rate limiters
Format: `{endpoint-or-feature}` — what is being limited.

| Name | Purpose |
|---|---|
| `login` | Login endpoint |
| `register` | Registration endpoint |
| `order-create` | Order creation |

### Default settings (overridable per name)

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 20
        failureRateThreshold: 50
        waitDurationInOpenState: 60s
        permittedNumberOfCallsInHalfOpenState: 5
  retry:
    configs:
      default:
        maxAttempts: 3
        waitDuration: 200ms
        exponentialBackoffMultiplier: 2
  timelimiter:
    configs:
      default:
        timeoutDuration: 5s
```

**Implementation:** Defaults in `common-resilience/src/main/resources/application-resilience.yml`, imported by every service.

---

## 15. Build Conventions

### Maven commands

| Command | Purpose |
|---|---|
| `mvn -B verify` | Standard CI build (compile, unit tests, JaCoCo, package) |
| `mvn -B verify -Pintegration-test` | + Testcontainers integration tests |
| `mvn -B verify -Pcoverage` | + JaCoCo coverage gate ≥ 80% |
| `mvn -B -pl services/{name} -am verify` | Build a single service plus its dependencies |
| `mvn spring-boot:run -Dspring-boot.run.profiles=local` | Run a service locally |
| `mvn -B versions:display-dependency-updates` | Check for stale deps |

### Profiles

| Profile | Purpose |
|---|---|
| `local` | Default for development; uses docker-compose dependencies |
| `staging` | Configuration for staging environment |
| `production` | Configuration for production environment |
| `integration-test` | Activates Testcontainers tests |
| `coverage` | Activates JaCoCo with 80% threshold |
| `native` | (Future) GraalVM native image build |

### Required Maven plugins (in parent POM)
- `spring-boot-maven-plugin` — packaging
- `flyway-maven-plugin` — DB migration commands
- `jacoco-maven-plugin` — coverage
- `maven-failsafe-plugin` — integration tests (separate from unit tests)
- `protobuf-maven-plugin` — proto compilation
- `git-commit-id-maven-plugin` — commit info for `/actuator/info`

### Service POM is minimal
Service POMs declare:
- `<parent>` reference to `platform-bom`
- `<artifactId>` (the service name)
- Service-specific dependencies (no versions — BOM provides them)
- Service-specific build customizations (rare)

A typical service POM is < 50 lines. If yours is bigger, something is leaking out of the BOM that should be in it.

---

## 16. API Versioning

- All public endpoints versioned: `/v1/orders`, `/v2/orders`
- Breaking changes go in a new version; old version supported for 6 months minimum after new version GA
- **Within a version, only additive changes** — adding optional fields, adding new endpoints. Never remove fields or change field types.
- Internal endpoints (gRPC) versioned via the `.proto` file's package: `package promotion.v1;`
- Sunset notice: when an old version is deprecated, responses include `Deprecation: true` and `Sunset: {date}` headers per RFC 8594

---

## 17. Event Schema Versioning

Avro schemas in Glue Schema Registry. Compatibility mode: **BACKWARD** (consumers can use newer schemas to read older messages).

### Allowed changes (no version bump)
- Add a new field with a default value
- Add a new optional field
- Remove an optional field that had a default value
- Remove a field that's deprecated for ≥ 6 months (with explicit "no readers" verification)

### Disallowed (require new `schemaVersion`)
- Remove a required field
- Change a field's type
- Rename a field (this is "remove + add" semantically; do it as two separate changes across two releases)
- Change the meaning of a field

### When to bump `schemaVersion`
- Bump when you make a disallowed change above
- Bump when you change the *semantic meaning* of an existing field (even if shape is unchanged)
- Don't bump for additive changes — Avro handles those compatibly

### Multi-version handling
Producers always emit the latest schema version. Consumers handle whatever versions are in flight. Old messages in topic retention may have older schemas — consumer code must handle both until retention expires.

**Implementation:** `.avsc` files live in `common-libs/common-events/src/main/avro/`. Schema Registry CLI runs in pre-commit hook to verify compatibility.

---

## 18. Library Versioning (common-libs)

Semantic versioning with strict rules:

- **Major bump (X.0.0)**: any breaking change to a public API in a `common-*` module
- **Minor bump (1.X.0)**: backwards-compatible additions
- **Patch bump (1.0.X)**: bug fixes only

The `platform-bom` version is bumped whenever any `common-*` module bumps its version.

Services consume libraries via the BOM, never by direct version reference. Bumping a library version is a single edit to `platform-bom/pom.xml` and rolls out to all services on next rebuild.

**Bumping cadence:** routine updates monthly; security fixes immediately; breaking changes batched quarterly.

---

## 19. Naming Conventions Across the Platform

| Element | Convention | Example |
|---|---|---|
| Maven group ID | `com.{org}.platform` | `com.acme.platform` |
| Maven artifact ID | kebab-case | `common-events`, `order-service` |
| Java package | `com.{org}.{service}.{layer}` | `com.acme.order.service` |
| Java class | PascalCase | `OrderCreationService` |
| Java method | camelCase | `createOrder`, `findByUserId` |
| Java constant | UPPER_SNAKE | `DEFAULT_TIMEOUT_SECONDS` |
| REST resource | kebab-case plural | `/v1/orders`, `/v1/payment-methods` |
| REST query param | camelCase | `?customerId=...&minAmount=...` |
| JSON field | camelCase | `"customerId": "..."` |
| Database table | snake_case plural | `orders`, `order_items`, `promo_codes` |
| Database column | snake_case | `customer_id`, `created_at` |
| Database index | `idx_{table}_{cols}` | `idx_orders_customer_id_created_at` |
| Database FK | `fk_{table}_{ref}` | `fk_order_items_order_id` |
| Database name | `{service}_db` | `order_db`, `identity_db` |
| Database user | `{service}_app` | `order_app` |
| Kafka topic | `{domain}-events` | `order-events`, `payment-events` |
| Kafka consumer group | `{service}-{topic}` | `notification-order-events` |
| SQS queue | `{consumer}-{purpose}` | `payment-refund`, `kitchen-compensation` |
| SQS DLQ | `{queue-name}-dlq` | `payment-refund-dlq` |
| DynamoDB table | tagged via Terraform; logical name unprefixed | `payment-ledger`, `tickets` |
| ECR repo | `{service-name}` | `order-service` |
| Docker image tag | git SHA (immutable) | `abc123def456` |
| K8s namespace | `{service-short-name}` | `order`, `payment`, `notification` |
| K8s deployment | `{service-name}` | `order-service` |
| K8s service | `{service-name}` | `order-service` |
| K8s configmap | `{service-name}-config` | `order-service-config` |
| K8s secret (External) | `{service-name}-secrets` | `order-service-secrets` |
| IAM role | `{org}-{env}-{service}-{purpose}` | `acme-prod-order-service-irsa` |
| IAM policy | `{org}-{env}-{service}-{purpose}` | `acme-prod-order-service-msk-access` |
| AWS resource (Terraform-tagged) | `{org}-{env}-{service}-{resource}` | `acme-prod-order-service-rds` |
| Endpoint paths | `/v{n}/{resource}` (kebab-case) | `/v1/orders`, `/v1/payment-methods` |
| Branch name | `feature/{step-id}-{slug}` | `feature/8.4-payment-success-handler` |
| Tag (release) | `{service}-v{X.Y.Z}` | `order-service-v1.4.2` |

---

## 20. Spring Boot Configuration Conventions

### Profile structure
```
src/main/resources/
├── application.yml              # base config, all profiles
├── application-local.yml        # local dev (docker-compose)
├── application-staging.yml      # staging
├── application-production.yml   # production
├── application-test.yml         # automated tests (Testcontainers)
└── application-resilience.yml   # imported from common-resilience
```

### `application.yml` template

```yaml
spring:
  application:
    name: ${SERVICE_NAME:order-service}
  profiles:
    active: ${ENV:local}
  threads:
    virtual:
      enabled: true
  jackson:
    deserialization:
      fail-on-unknown-properties: false
    serialization:
      write-dates-as-timestamps: false
      order-map-entries-by-keys: true   # for idempotency hashing

server:
  port: 8080
  shutdown: graceful
  compression:
    enabled: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      probes:
        enabled: true
      show-details: when-authorized
  metrics:
    tags:
      service: ${spring.application.name}
      env: ${ENV:local}
      version: ${SERVICE_VERSION:unknown}

logging:
  level:
    root: INFO
    com.acme: DEBUG
```

### Required environment variables
Every service requires these in its deployment:

| Variable | Purpose |
|---|---|
| `SERVICE_NAME` | Service identifier for logs/metrics |
| `SERVICE_VERSION` | Build version (set from CI) |
| `ENV` | `local`, `staging`, or `production` |
| `AWS_REGION` | AWS region |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTel collector endpoint |
| `OTEL_RESOURCE_ATTRIBUTES` | Service tags for traces |

### Forbidden in service code
- Hardcoded URLs, hostnames, ports
- Hardcoded magic numbers (timeouts, limits) — use config properties
- Hardcoded credentials or API keys
- Reading from `System.getenv()` directly — use Spring's `@Value` or `@ConfigurationProperties`

---

## 21. Database Conventions

### PostgreSQL services (Identity, Order, Promotion, Delivery)

**Connection pool (HikariCP):**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10        # tune to (RDS max_connections / pod_count) × 0.8
      minimum-idle: 2
      connection-timeout: 5000     # 5s
      idle-timeout: 300000         # 5min
      max-lifetime: 1800000        # 30min — must be < RDS connection timeout
      leak-detection-threshold: 60000  # 1min
```

**Migration rules:**
- File naming: `V{n}__{snake_case_description}.sql` (e.g., `V3__add_outbox_table.sql`)
- Migrations are immutable — never edit a `V` file after merge
- Backwards-compatible always:
  - Add nullable column → backfill in app → make non-null in next migration
  - Add new table before code that uses it
  - Drop column only after code stops referencing it for one release
- Migrations run via init container in K8s deployment, not at app startup

**Indexing rules:**
- Index every foreign key
- Index every column used in WHERE clauses on hot queries
- Composite index where the access pattern uses multiple columns together
- Drop unused indexes (verify via `pg_stat_user_indexes` first)

**Locking patterns:**
- `SELECT FOR UPDATE` for state machine transitions (Order)
- `SELECT FOR UPDATE NOWAIT` for race-resolution (Delivery claim)
- `SELECT FOR UPDATE SKIP LOCKED` for outbox pollers (multiple instances)
- Advisory locks for cross-pod coordination (saga timeout enforcer)

### DynamoDB services (Menu, Kitchen, Payment, Review)

**Table design:**
- Single-table design per service (one table per service, not per entity)
- PK and SK designed for access patterns first
- Avoid hot partitions — never use timestamps as PK
- GSIs only for distinct access patterns (not "just in case")

**Idempotency:**
- Conditional writes: `attribute_not_exists(PK)` for inserts that must not collide
- Atomic counters: `UpdateExpression: ADD counter :inc`
- Optimistic locking: `condition: version = :expected_version`

**Capacity:**
- On-demand billing for unpredictable workloads
- Provisioned with auto-scaling for steady workloads
- PITR enabled for financially-sensitive tables (Payment ledger)

**Outbox publishing:**
- Outbox via Streams + Lambda for DDB-backed services
- Outbox via DB-polling sidecar for PostgreSQL-backed services

---

## 22. Testing Conventions

### Test naming
- Unit tests: `MethodNameTest` or `ClassNameTest`
- Integration tests: `MethodNameIT` or `ClassNameIT` (failsafe convention)
- Test methods: `should_returnX_when_Y()` or `givenX_whenY_thenZ()`

### Tools
- **JUnit 5** as the test runner
- **AssertJ** for assertions (never JUnit `assertEquals`/`assertTrue`)
- **Mockito** for mocking (never PowerMock)
- **Testcontainers** for integration tests requiring real infrastructure
- **WireMock** for mocking external HTTP services (Stripe)
- **Awaitility** for asynchronous assertions
- **Jacoco** for coverage

### Layering
| Layer | What's tested | Dependencies |
|---|---|---|
| Unit | Single class | All dependencies mocked |
| Slice | Spring layer (e.g., `@WebMvcTest`) | Spring context loaded for that slice |
| Integration (`*IT`) | Multiple components + real dependencies | Testcontainers (PG, Redis, Kafka, LocalStack) |
| Contract | API contracts | Pact or REST Assured |
| E2E | Multi-service flow | Deployed staging environment |

### Coverage gate
- 80% line coverage minimum per service
- 100% on saga/compensation logic in Order Service
- 100% on idempotency-key handling
- Coverage runs in CI; PR blocked if it drops below threshold

### Testcontainers patterns
- Use `@DynamicPropertySource` to inject container endpoints into Spring context
- Reuse containers across test classes via `@Testcontainers(disabledWithoutDocker = true)` and `@Container static`
- Each test method starts from a clean state (use `@Sql`, `@DynamoDbCleanup`, etc.)

### Saga-specific tests (Order Service)
For every state transition you introduce, write three tests:
1. **Happy path**: state advances correctly
2. **Idempotent retry**: same event arriving twice is a no-op
3. **Out-of-order**: event for a future state arriving doesn't break the saga

---

## 23. Commit & PR Conventions

### Commit messages (Conventional Commits)

Format: `{type}({scope}): {subject}`

```
feat(order-service): add saga timeout enforcer (step 8.8)

Implements the SagaTimeoutEnforcer that runs every 30s and
triggers compensation for orders stuck > 5min in non-terminal state.

Closes #142
```

| Type | Use |
|---|---|
| `feat` | New feature |
| `fix` | Bug fix |
| `refactor` | Code restructuring without behavior change |
| `perf` | Performance improvement |
| `test` | Test additions/changes only |
| `docs` | Documentation only |
| `build` | Build system changes (Maven, Docker) |
| `ci` | CI/CD pipeline changes |
| `chore` | Routine maintenance |

| Scope | Use |
|---|---|
| `{service-name}` | Specific service |
| `shared-libs` | common-libs |
| `infra` | platform-infra (Terraform) |
| `gitops` | platform-gitops (K8s manifests) |
| `bom` | platform-bom version updates |

### Branch naming
- `feature/{step-id}-{slug}` for build steps
- `fix/{ticket-or-description}` for bug fixes
- `chore/{description}` for routine work

### PR title
Same format as commit message subject. PR title becomes the squash-commit message on merge.

### PR description template

```markdown
## Build Step
Implements **Step X.Y** from `build-plan.md`.

## What changed
{bullet list}

## Acceptance criteria
- [x] {criterion 1}
- [x] {criterion 2}

## Testing
- {how this was tested}

## Risk
{Low | Medium | High} — {why}

## Rollback plan
{how to roll back if this breaks production}
```

### Merge strategy
- Squash and merge for feature branches
- Linear history on `main`
- No merge commits

---

## Implementation Checklist

When you complete a build step that touches a service, verify it adheres to these conventions:

- [ ] Returns errors in the standard format (Section 1)
- [ ] Uses error codes from the catalog (Section 2)
- [ ] Validates and emits standard headers (Section 3)
- [ ] Lists endpoints support cursor pagination (Section 4)
- [ ] All authenticated endpoints validate JWT (Section 5)
- [ ] Health/operational endpoints exposed (Section 6)
- [ ] Logs in structured JSON with required fields (Section 7)
- [ ] Events wrapped in standard envelope (Section 8)
- [ ] All money uses `Money` type (Section 9)
- [ ] All timestamps use `Instant` + injected `Clock` (Section 10)
- [ ] All IDs use the typed prefix format (Section 11)
- [ ] Rate limits applied where specified (Section 12)
- [ ] Write endpoints require Idempotency-Key (Section 13)
- [ ] Resilience patterns named per convention (Section 14)
- [ ] Build runs `mvn -B verify` cleanly (Section 15)
- [ ] APIs versioned (Section 16)
- [ ] Events backwards-compatible (Section 17)
- [ ] BOM updated if libraries changed (Section 18)
- [ ] All naming follows the table (Section 19)
- [ ] Spring config follows the template (Section 20)
- [ ] Database access follows conventions (Section 21)
- [ ] Test coverage ≥ 80% (Section 22)
- [ ] Commit messages follow Conventional Commits (Section 23)

---

*End of common-conventions.md.*
