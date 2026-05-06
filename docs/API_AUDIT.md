# API Audit — Snapshot

> **Date:** 2026-04-17
> **Scope:** HTTP-exposing services — `gateway-service`, `user-service`, `order-service`, `product-service`.
> **Method:** Static code audit (Pass A + Pass C). Runtime verification (Pass B — principles 6, 7, 10) deferred because no containers were running at audit time; rerun with `./start.sh` to confirm empirically.
> **Rubric:** See `API_PRINCIPLES.md`.
>
> Legend: 🟢 pass · 🟡 partial · 🔴 gap · ⚪ N/A

---

## Summary Matrix

| # | Principle            | gateway | user | order | product |
|---|----------------------|:---:|:---:|:---:|:---:|
| 1 | Consistency          | 🟢 | 🔴 | 🟢 | 🟢 |
| 2 | Simplicity           | 🟢 | 🟢 | 🟢 | 🟢 |
| 3 | Resource-oriented    | 🟢 | 🟢 | 🟢 | 🟢 |
| 4 | Versioning           | 🟢 | 🟢 | 🟢 | 🟢 |
| 5 | Error handling       | ⚪ | 🔴 | 🔴 | 🟢 |
| 6 | Security             | 🟡 | 🔴 | 🟡 | 🟡 |
| 7 | Performance          | 🟢 | 🟢 | 🟡 | 🟢 |
| 8 | Documentation        | 🔴 | 🟢 | 🟢 | 🟢 |
| 9 | Idempotency          | ⚪ | 🟢 | 🔴 | 🟢 |
| 10 | Scalability         | 🟢 | 🟢 | 🟢 | 🟢 |
| 11 | Flexibility         | ⚪ | ⚪ | 🟡 | 🟢 |
| 12 | Testability         | 🔴 | 🔴 | 🔴 | 🔴 |

---

## Findings by Principle

### 1. Consistency — 🔴 `user-service` (1 gap)
- **Gap** — `POST /api/v1/users` returns `ResponseEntity<String>` with body `"User created and is being processed..."`. Every other creation endpoint returns a typed DTO. `UserController.java:37-42`.
  - **Remediation.** Return a typed `UserRegistrationResponse` record with at minimum `{ username, status, message }`. Also change status code from `200` to `202 Accepted` (async — user is not yet `ACTIVE`).

### 2. Simplicity — 🟢
No gaps. Endpoint counts per service are small (1–3 per controller), DTOs are flat, responses expose only necessary fields.

### 3. Resource-oriented — 🟢
All paths are nouns. `/auth/login|refresh|logout` is an acceptable exception — verbs operate on tokens, not on the `User` resource.

### 4. Versioning — 🟢
`/api/v1/` uniformly applied across gateway routes and every controller's `@RequestMapping`.

### 5. Error handling — 🔴 `user-service`, 🔴 `order-service`
- **Gap — `user-service`** — no `@RestControllerAdvice`. Any unhandled exception yields the default Spring Boot error page, including stack traces in dev and arbitrary shapes in prod.
  - **Remediation.** Create `user-service/.../exception/GlobalExceptionHandler.java` modeled on the product-service reference. Handle: `BadCredentialsException` → 401, `MethodArgumentNotValidException` → 400, generic `Exception` → 500.
- **Gap — `order-service`** — same as above, no `@RestControllerAdvice`.
  - **Remediation.** Create `order-service/.../exception/GlobalExceptionHandler.java`. Handle: `OrderNotFoundException` → 404, `MethodArgumentNotValidException` → 400, generic `Exception` → 500.
- **Structural recommendation.** Promote the `ErrorResponse` record (currently defined inline in `product-service/.../GlobalExceptionHandler.java:18`) to `common-libs` so all three services emit an identical error envelope.

### 6. Security — 🟡 (partial across all HTTP services)
- **Gap — `user-service`** — `UserRegistrationDto` has **no validation annotations** (`UserRegistrationDto.java:7-12`) and `UserController#register` does not use `@Valid` (`UserController.java:37`). Registration accepts empty usernames, empty passwords, malformed emails.
  - **Remediation.** Add `@NotBlank @Size(min=3, max=50)` on `username`, `@NotBlank @Size(min=8)` on `password`, `@Email @NotBlank` on `email`, `@NotNull` on `role`. Add `@Valid` on the controller parameter.
- **Gap — system-wide** — no HTTPS at the edge. Currently tracked in `PLAN.md` (miscellaneous #5).
- **Pass — `order-service`, `product-service`, `AuthController`** — `@Valid` applied on request DTOs; `LoginDto`, `RefreshTokenDto`, `CreateOrderDto`, `CreateProductDto` carry validation annotations.
- **Pass — rate limiting, JWT validation, BCrypt, role header injection** — all present.

### 7. Performance — 🟡 `order-service` (1 gap)
- **Gap — `order-service`** — `GET /api/v1/orders` (`OrderController.java:68-72`) returns `List<OrderResponseDto>` unpaginated. A user with hundreds of orders gets a single large payload.
  - **Remediation.** Change return type to `Page<OrderResponseDto>`, accept `Pageable` with `@PageableDefault(size = 20)`, update service method signature accordingly.
- **Pass — `product-service`** — paginated list with optional category filter.
- **Pass — auth endpoints** — single-record responses, no pagination needed.

### 8. Documentation — 🔴 `gateway-service`
- **Gap — `gateway-service`** — no `OpenApiConfig`, no aggregated Swagger UI. Clients have to hit each downstream service individually to discover endpoints.
  - **Remediation (lower priority).** springdoc-openapi does not natively aggregate across microservices — either (a) add a static `openapi.yaml` gateway aggregator, (b) document all routes in `SERVICES.md` (partially done), or (c) accept the limitation because downstream services already expose their own Swagger UIs. Recommendation: **(c) + link to each Swagger UI from `SERVICES.md`** — least-effort, no runtime aggregation complexity.
- **Pass — user, order, product** — OpenAPI configs with titles, descriptions, and bearer scheme declarations. Every endpoint has `@Operation` + `@ApiResponses`.

### 9. Idempotency — 🔴 `order-service`
- **Gap — `order-service`** — `POST /api/v1/orders` has no `Idempotency-Key` support. A network retry between client and gateway can create duplicate orders (and duplicate payments through the Saga).
  - **Remediation.** Accept `Idempotency-Key` header; cache `(key, username) → orderId` in Redis for 24h; on repeat, return the cached response. This is the highest-impact reliability gap in the system.
- **Pass — `product-service`** — `POST /api/v1/products` is ADMIN-only, infrequent; idempotency less critical but could use a `name` unique constraint as defense-in-depth.
- **Pass — `user-service`** — registration is unauthenticated; idempotency typically keyed off a DB unique constraint on username (present).

### 10. Scalability — 🟢
Services are stateless; Redis holds the only shared runtime state; async messaging absorbs spikes. No in-memory per-request caches observed.

### 11. Flexibility — 🟡 `order-service`
- **Gap — `order-service`** — `GET /api/v1/orders` has no filtering (by status, date range) and no sorting (would follow from pagination remediation in #7).
  - **Remediation.** Add `?status=PAID`, `?from=...`, `?to=...` query params when paginated.
- **Pass — `product-service`** — pagination + filter by category + sort via `Pageable`.

### 12. Testability — 🔴 All services
- **Gap — all services** — 5 test files exist; all are placeholder `@SpringBootTest` with an empty `contextLoads()`. No `@WebMvcTest`, no `@DataJpaTest`, no Saga integration tests.
  - **Remediation (staged).**
    1. **Controller slices** — `@WebMvcTest` per controller, mocking services, covering happy path + validation errors.
    2. **Repository slices** — `@DataJpaTest` for each service's repository.
    3. **Saga integration** — one `@SpringBootTest` + Testcontainers (Postgres, Kafka, Redis) verifying the Order Saga end-to-end: create order → stock reserved → payment processed → order PAID.
    4. **Contract tests** — DTO schema tests to catch accidental breaking changes.

---

## Cross-Cutting Recommendations (promote to `common-libs`)

1. **`ApiError` record** (`status, error, message, timestamp, fieldErrors?`) — single shared error envelope across all services. Replaces the private record in `product-service/GlobalExceptionHandler.java:18`.
2. **`PageResponse<T>` wrapper** (optional) — if the raw Spring `Page<T>` JSON shape (with internal fields like `pageable`, `sort.unsorted`) leaks too much infrastructure; otherwise leave `Page<T>` as-is.
3. **`IdempotencyKeyFilter`** — a reusable servlet filter that reads `Idempotency-Key`, checks Redis, short-circuits with cached response if present.

---

## What Is Out of Scope for This Snapshot

- **Runtime verification (Pass B).** Principles 6 (live HTTPS check, live rate-limit burst test), 7 (N+1 query inspection via Hibernate SQL logs), and 10 (horizontal scale test under load) require the stack running. Rerun after `./start.sh` and note results here.
- **Fixes.** Every 🔴 and 🟡 has a remediation line; tracking lives in `PLAN.md` under "API Hardening".
