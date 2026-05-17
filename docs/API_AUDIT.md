# API Audit — Snapshot

> **Date:** 2026-05-18 (re-baselined; original audit 2026-04-17)
> **Scope:** All HTTP-exposing services — `user-service`, `order-service`, `product-service`, `basket-service`, `kitchen-service`, `delivery-service`, `review-service`, `promotion-service`.
> **Not in scope:** `analytics-service` (actuator only), `payment-service` (event-driven only), `notification-service` (event-driven only). `gateway-service` retired — removed from audit.
> **Method:** Static code audit. Runtime verification (Pass B — live HTTPS, rate-limit burst, N+1 SQL) still deferred; rerun with `./start.sh` to confirm empirically.
> **Rubric:** See `API_PRINCIPLES.md`.
>
> Legend: 🟢 pass · 🟡 partial · 🔴 gap · ⚪ N/A

---

## Summary Matrix — Original Services

| # | Principle       | user | order | product |
|---|-----------------|:----:|:-----:|:-------:|
| 1 | Consistency     | 🔴 | 🟢 | 🟢 |
| 2 | Simplicity      | 🟢 | 🟢 | 🟢 |
| 3 | Resource-oriented | 🟢 | 🟢 | 🟢 |
| 4 | Versioning      | 🟢 | 🟢 | 🟢 |
| 5 | Error handling  | 🔴 | 🔴 | 🟢 |
| 6 | Security        | 🔴 | 🟡 | 🟡 |
| 7 | Performance     | 🟢 | 🟡 | 🟢 |
| 8 | Documentation   | 🟢 | 🟢 | 🟢 |
| 9 | Idempotency     | 🟢 | 🔴 | 🟢 |
| 10 | Scalability    | 🟢 | 🟢 | 🟢 |
| 11 | Flexibility    | ⚪ | 🟡 | 🟢 |
| 12 | Testability    | 🔴 | 🔴 | 🔴 |

> All 🔴 / 🟡 from the 2026-04-17 audit remain open — none have been remediated.

---

## Summary Matrix — New Services (2026-05-18)

| # | Principle       | basket | kitchen | delivery | review | promotion |
|---|-----------------|:------:|:-------:|:--------:|:------:|:---------:|
| 1 | Consistency     | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 |
| 2 | Simplicity      | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 |
| 3 | Resource-oriented | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 |
| 4 | Versioning      | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 |
| 5 | Error handling  | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 |
| 6 | Security        | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 |
| 7 | Performance     | ⚪ | ⚪ | ⚪ | 🟡 | ⚪ |
| 8 | Documentation   | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 |
| 9 | Idempotency     | 🟡 | ⚪ | ⚪ | 🟡 | ⚪ |
| 10 | Scalability    | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 |
| 11 | Flexibility    | ⚪ | ⚪ | ⚪ | ⚪ | ⚪ |
| 12 | Testability    | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 |

---

## Findings by Principle

### 1. Consistency

**🔴 `user-service`** (gap from 2026-04-17, still open)
- `POST /api/v1/users` returns `ResponseEntity<String>` with body `"User created and is being processed (PENDING)..."`. Every other creation endpoint returns a typed DTO. `UserController.java:37-42`.
- **Remediation.** Return a `UserRegistrationResponse` record — at minimum `{ username, status, message }`. Change status code from `200` to `202 Accepted` (async — user is not yet `ACTIVE`).

**🟢 All other services** — typed DTOs, consistent status codes.

---

### 2. Simplicity — 🟢 all services

No gaps. Endpoint counts are small, DTOs are flat, responses expose only necessary fields.

---

### 3. Resource-oriented — 🟢 all services

All paths are nouns. `/auth/login|refresh|logout` remains an acceptable exception.

---

### 4. Versioning — 🟢 all services

`/api/v1/` uniformly applied across all controllers.

---

### 5. Error handling

**🔴 `user-service`** (gap from 2026-04-17, still open)
- No `@RestControllerAdvice`. Unhandled exceptions yield default Spring Boot error shape.
- **Remediation.** Create `user-service/.../exception/GlobalExceptionHandler.java`. Handle: `BadCredentialsException` → 401, `MethodArgumentNotValidException` → 400, `Exception` → 500.

**🔴 `order-service`** (gap from 2026-04-17, still open)
- No `@RestControllerAdvice`.
- **Remediation.** Create `order-service/.../exception/GlobalExceptionHandler.java`. Handle: `OrderNotFoundException` → 404, `MethodArgumentNotValidException` → 400, `Exception` → 500.

**🟢 `product-service`, `basket-service`, `kitchen-service`, `delivery-service`, `review-service`, `promotion-service`** — all have `GlobalExceptionHandler`.

**Structural recommendation (still open):** Promote `ErrorResponse` record to `common-libs` so all services share one error envelope shape.

---

### 6. Security

**🔴 `user-service`** (gap from 2026-04-17, still open)
- `UserRegistrationDto` has no validation annotations; `UserController#register` does not use `@Valid`. Registration accepts empty usernames, blank passwords, malformed emails.
- **Remediation.** Add `@NotBlank @Size(min=3, max=50)` on `username`, `@NotBlank @Size(min=8)` on `password`, `@Email @NotBlank` on `email`, `@NotNull` on `role`. Add `@Valid` to the controller parameter.

**🟡 `order-service`, `product-service`** — `@Valid` applied and DTOs annotated; no HTTPS at the edge (system-wide).

**🟢 `basket-service`, `kitchen-service`, `delivery-service`, `review-service`, `promotion-service`** — `@Valid` applied, DTOs carry validation annotations.

**System-wide gap** — no HTTPS at the edge (local + staging). Production will rely on AWS ALB/API Gateway TLS termination.

---

### 7. Performance

**🟡 `order-service`** (gap from 2026-04-17, still open)
- `GET /api/v1/orders` returns `List<OrderResponseDto>` unpaginated. A user with many orders gets one large payload.
- **Remediation.** Change to `Page<OrderResponseDto>`, accept `Pageable` with `@PageableDefault(size = 20)`.

**🟡 `review-service`** (new)
- `GET /api/v1/reviews/orders/{orderId}` returns all reviews for an order unbounded. Low urgency now (orders typically have 1–2 reviews), but should be paginated before any public launch.
- **Remediation.** Accept `Pageable`, return `Page<ReviewResponseDto>`.

**⚪ `basket-service`, `kitchen-service`, `delivery-service`, `promotion-service`** — endpoints are by-ID or single-record; pagination N/A.

---

### 8. Documentation — 🟢 all services

All services have `OpenApiConfig`, `@Tag`/`@Operation`/`@ApiResponse` on controllers. Swagger UI available at `/swagger-ui.html` on each service port.

> `gateway-service` OpenAPI gap (2026-04-17) is moot — service retired.

---

### 9. Idempotency

**🔴 `order-service`** (gap from 2026-04-17, still open)
- `POST /api/v1/orders` has no `Idempotency-Key` support. A network retry can create duplicate orders and trigger duplicate payments through the Saga. Highest-impact reliability gap in the system.
- **Remediation.** Accept `Idempotency-Key` header; cache `(key, username) → orderId` in Redis for 24h; return cached response on repeat.

**🟡 `basket-service`**
- `POST /api/v1/basket/items` has no idempotency guard. Adding the same product twice creates duplicate basket entries instead of incrementing quantity.
- **Remediation.** Upsert by `productId`: if item already in basket, increment quantity rather than insert new row.

**🟡 `review-service`**
- `POST /api/v1/reviews` can create multiple reviews for the same order from the same user if retried.
- **Remediation.** Add a unique constraint on `(orderId, username)` at the DB level; let the constraint bubble up as a 409 via the `GlobalExceptionHandler`.

**⚪ `kitchen-service`, `delivery-service`, `promotion-service`** — only PUT/GET endpoints or admin-only POST; lower-risk.

---

### 10. Scalability — 🟢 all services

Services are stateless; Redis holds the only shared runtime state (tokens, basket, counters). No in-memory per-request caches observed.

---

### 11. Flexibility

**🟡 `order-service`** (gap from 2026-04-17, still open)
- `GET /api/v1/orders` has no filtering (by status, date range) or sorting. Follows from the pagination remediation in #7.
- **Remediation.** Add `?status=PAID`, `?from=...`, `?to=...` after pagination is in place.

**⚪ all other services** — endpoints are by-ID; filtering not applicable to their current shapes.

---

### 12. Testability — 🔴 all services

**Original services** (gap from 2026-04-17, still open)
- `user-service`, `order-service`, `product-service`, `payment-service`, `analytics-service` — all have one placeholder `contextLoads()` test. No `@WebMvcTest`, no `@DataJpaTest`, no Saga integration tests.

**New services** (new finding)
- `basket-service`, `kitchen-service`, `delivery-service`, `review-service`, `promotion-service` — zero test files.

**Remediation (staged, applies to all services):**
1. **Controller slices** — `@WebMvcTest` per controller, mocking the service layer, covering happy path + validation errors + 4xx responses.
2. **Repository slices** — `@DataJpaTest` for JPA services; DynamoDB Enhanced Client tests with LocalStack for `review-service` and `kitchen-service`.
3. **Saga integration** — one `@SpringBootTest` + Testcontainers (Postgres, Kafka, Redis) verifying the Order Saga end-to-end: place order → reserve stock → process payment → order `PAID`.
4. **Contract tests** — DTO schema tests to catch accidental breaking changes across services.

---

## Cross-Cutting Recommendations

1. **`ApiError` record** in `common-libs` — `{ status, error, message, timestamp, fieldErrors? }`. Replaces the private record in `product-service/GlobalExceptionHandler.java`. Required by user-service and order-service when they add their handlers.
2. **`IdempotencyKeyFilter`** — reusable servlet filter reading `Idempotency-Key`, checking Redis, short-circuiting with cached response. Needed by `order-service` (critical) and optionally `review-service`.
3. **`PageResponse<T>` wrapper** — consider once multiple services have paginated list endpoints; not worth it yet.

---

## What Is Out of Scope for This Snapshot

- **Runtime verification (Pass B).** Live HTTPS check, rate-limit burst test, N+1 SQL inspection via Hibernate logs, and horizontal scale test require the stack running. Rerun after `./start.sh`.
- **Event-driven services.** `payment-service`, `analytics-service`, `notification-service` have no HTTP surface — not applicable to this rubric.
- **Fixes.** Every 🔴 and 🟡 has a remediation line above. Prioritised order: testability (12) → order idempotency (9) → user validation (6) → exception handlers (5) → order pagination (7).
