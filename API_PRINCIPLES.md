# API Design Principles

> **Purpose.** This is the rubric for any HTTP surface added to — or changed in — the Food Ordering System. Before writing a new controller, changing a DTO shape, or introducing a new service, read this file. Before merging, verify the change respects every principle.
>
> **Scope.** Applies to the 4 HTTP-exposing services: `gateway-service`, `user-service`, `order-service`, `product-service`. Event-driven services (`payment-service`, `analytics-service`) have no HTTP surface but should still obey principles 1 (consistency), 5 (error handling in logs/DLQs), 9 (idempotency), and 12 (testability).

---

## 1. Consistency — the foundation of good APIs

Once a developer learns one part of the API, they should predict the rest.

- Use the same naming conventions across endpoints
- Keep request and response formats uniform
- Apply consistent error handling structures
- Standardize authentication and headers

**How we apply it**
- Every public path is `/api/v1/{resource-plural}` — no singular nouns, no verbs (`/api/v1/orders`, not `/api/v1/order` or `/api/v1/createOrder`).
- Every authenticated request carries its identity as `X-User-Name` + `X-User-Role` injected by the gateway; downstream services never re-parse JWTs.
- Every DTO lives under a `record/` package as an immutable Java `record`.
- Every response is a typed DTO (never a raw String, never a raw entity).

---

## 2. Simplicity — reduce complexity

An API should be easy to understand and use without unnecessary abstraction.

- Avoid deeply nested structures
- Keep endpoints focused and minimal
- Use clear and descriptive naming
- Return only necessary data

**How we apply it**
- Each service owns **one bounded context**; if a new endpoint doesn't belong, it goes in a different service.
- Response DTOs expose only fields the client needs (no JPA entity leakage — note `ProductResponseDto`, `OrderResponseDto`).
- Flat DTOs — max 2 levels of nesting (e.g., `OrderResponseDto` → `List<OrderItem>`).

---

## 3. Resource-oriented design — think in entities

Design APIs around resources (nouns), not actions (verbs).

- `/users` → user resources
- `/orders` → order resources
- Use HTTP methods instead of verbs in URLs
  - `GET` → retrieve
  - `POST` → create
  - `PUT`/`PATCH` → update
  - `DELETE` → remove

**How we apply it**
- All current paths are nouns: `/users`, `/orders`, `/products`, `/auth` (the one exception — identity actions like `login`/`refresh`/`logout` are acceptable as sub-paths under `/auth` because they are verbs on tokens, not entities).
- State transitions are triggered by POSTing events, not by verb-in-URL — e.g., an order moves from PENDING → PAID via the Saga, not via `POST /orders/{id}/pay`.

---

## 4. Versioning — plan for change

APIs evolve; versioning preserves backward compatibility.

- Use versioning in URLs (`/v1/users`)
- Avoid breaking changes inside a version
- Document version differences
- Deprecate old versions gradually

**How we apply it**
- **URL versioning** at `/api/v1/` — applied uniformly across all controllers and gateway routes.
- A breaking change requires a `/api/v2/` cut, not mutation of `/api/v1/` responses.
- When `v2` ships, `v1` remains live for ≥ 1 release cycle with `Deprecation` + `Sunset` HTTP headers and a deprecation note in the OpenAPI description.

---

## 5. Error handling — communicate clearly

Errors should help developers fix issues quickly.

- Use standard HTTP status codes
  - `200` success · `400` client error · `401` unauthorized · `403` forbidden · `404` not found · `409` conflict · `500` server error
- Return meaningful error messages
- Include error codes and descriptions
- Suggest possible fixes when relevant

**How we apply it**
- Every service **must** have a `@RestControllerAdvice` (`GlobalExceptionHandler`).
- Every error body uses the shared envelope: `{ status, error, message, timestamp }` (currently a record in `product-service`; promote to `common-libs` so every service returns the identical shape).
- `201 Created` for synchronous creation; `202 Accepted` for async creation where the resource isn't final yet (e.g., registration Saga). Do **not** return `200` from a POST that creates a resource.
- Validation failures (`MethodArgumentNotValidException`) always return `400` with a field→message map.

---

## 6. Security — protect data and users

Security is not optional.

- HTTPS on every request
- Authentication (API keys, OAuth, JWT)
- Validate all inputs
- Apply rate limiting
- Prevent unauthorized access

**How we apply it**
- **JWT (HS256)** at the gateway, two-layer validation (gateway + user-service re-check).
- `@Valid` + Jakarta validation annotations (`@NotBlank`, `@Size`, `@Email`, `@Positive`, etc.) on **every** request DTO — no exceptions.
- Rate limiting at the gateway, IP-based, Redis-backed; tighter on registration (5 rps) than on read-heavy paths (10 rps).
- **HTTPS / TLS** is currently absent at the edge (open in `PLAN.md` → add nginx/Traefik in front of gateway).
- Passwords BCrypt-hashed; secrets via env vars produced by `generate-secrets.sh`.

---

## 7. Performance — optimize for speed

- Paginate large datasets
- Cache where possible
- Reduce payload size
- Support filtering and query parameters
- Avoid over-fetching and under-fetching

**How we apply it**
- List endpoints **must** support `Pageable` with a sensible default (`@PageableDefault(size = 20)` — see `ProductController#listProducts`).
- Filters use query params, not path segments (`GET /products?category=PIZZA`).
- JPA fetches default to `LAZY`; any new `@OneToMany`/`@ManyToOne` must justify `EAGER` in a comment.
- Response DTOs exclude audit columns the client doesn't need.

---

## 8. Documentation — enable developers

Good documentation is as important as the API itself.

- Clear endpoint descriptions
- Request/response examples
- Authentication method documented
- Error responses explained
- Quick-start guides

**How we apply it**
- Every HTTP service has an `OpenApiConfig` (springdoc-openapi) exposing `/swagger-ui.html`.
- Every endpoint has `@Operation(summary, description)` + `@ApiResponses` covering success **and** expected error codes.
- DTO fields carry `@Schema(description, example)` so Swagger UI renders usable examples.
- Auth requirements marked with `@SecurityRequirement(name = "bearerAuth")`.

---

## 9. Idempotency — safe retries

Idempotent operations produce the same result on retries.

- `GET` always idempotent
- `PUT` should be idempotent
- `DELETE` should be idempotent
- Use idempotency keys for critical `POST`s

**How we apply it**
- `GET` endpoints on this system have no side effects (confirmed in all 4 HTTP services).
- For Saga-triggering `POST`s (notably `POST /orders`), an **`Idempotency-Key` header** should be accepted and its `(key, username)` tuple stored server-side for ≥ 24h; repeat requests return the original response. _Not yet implemented — see audit._
- Kafka consumers are idempotent at the business level — a repeated `OrderCreatedEvent` must not double-reserve stock (enforced via `@Version` optimistic locking on `Product`).

---

## 10. Scalability — design for growth

- Stateless services
- Horizontal scaling
- Separate services when needed
- Optimize database queries
- Monitor and log performance

**How we apply it**
- Services are stateless w.r.t. HTTP — no `HttpSession`, no in-memory per-request caches. The only stateful store is Redis (refresh tokens, rate-limit buckets, analytics counters).
- Each service owns its database — no shared schemas (`user_db`, `order_db`, `payment_db`).
- Async Saga communication over Kafka, not synchronous RPC chains, so back-pressure is absorbed by topic partitions.
- Prometheus scrapes `/actuator/prometheus` every 15s; Grafana dashboards for `orders_*` and `payments_processed_total`.

---

## 11. Flexibility — support different clients

- Allow filtering, sorting, pagination
- Support multiple formats where needed (JSON, XML)
- Enable partial responses
- Extensible data structures

**How we apply it**
- JSON is the sole response format (no XML clients planned — revisit if that changes).
- List endpoints accept `?page=`, `?size=`, `?sort=` via Spring's `Pageable`.
- Filters are additive query params (don't break if omitted).

---

## 12. Testability — ensure reliability

- Unit + integration tests
- Sandbox/staging environments
- Consistent data contracts
- Automated test pipelines

**How we apply it**
- **Unit tests**: `@WebMvcTest` for controller slices, mocking services.
- **Integration tests**: `@SpringBootTest` with Testcontainers (Postgres + Kafka + Redis) for Saga flows end-to-end.
- DTO records are validated by schema tests — if a record shape changes, the test must change with it (contract-first).
- `start.sh` provides a local sandbox; CI runs Maven in a reproducible container.

---

## Audit Checklist — living status

The current audit snapshot lives in `API_AUDIT.md`. Legend: 🟢 pass · 🟡 partial · 🔴 gap. When a principle is remediated for a service, update both `API_AUDIT.md` and `PLAN.md` (under "API Hardening"), not this file — this file is the rubric, not the scoreboard.
