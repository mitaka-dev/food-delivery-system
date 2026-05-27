---
name: postgresql-flyway-migrations
description: >
  PostgreSQL schema migrations with Flyway for Aurora Serverless v2 services in the food-delivery
  platform. Covers migration file naming, the backwards-compatible change sequences that prevent
  production outages, HikariCP connection pool tuning for Aurora, locking patterns (FOR UPDATE /
  SKIP LOCKED / advisory locks), indexing rules, and Testcontainers-based integration test setup.

  ALWAYS load this skill when: writing or editing any Flyway migration SQL file
  (V*__*.sql), editing JPA entities or Spring Data repositories, configuring HikariCP or Aurora
  datasource settings, or when the user mentions Flyway, schema migration, ALTER TABLE, column
  rename, DROP COLUMN, ddl-auto, HikariCP, connection pool, JDBC URL, or "migrate". Also load for
  step 3.1 (product-service Aurora wiring) and any equivalent step for subsequent services.
---

# PostgreSQL + Flyway Migrations

> Stack: PostgreSQL 16 (Aurora Serverless v2) · Flyway (BOM-managed) · HikariCP · Spring Data JPA/JDBC

## Migration File Naming

Format: `V{n}__{snake_case_description}.sql` — **exactly two underscores**, no gaps in `n`, no reuse.

```
V1__create_products.sql
V2__add_outbox_table.sql
V3__add_stock_reservation_column.sql
V12__add_product_locale_index.sql
```

Migrations are **immutable** once merged. Editing a committed migration file causes Flyway to fail
validation on every pod startup — which is worse than the original mistake. Never edit; always add
a new version.

## Flyway Config (locked in)

```yaml
spring:
  flyway:
    baseline-on-migrate: false   # require explicit baseline — never auto-create
    validate-on-migrate: true    # checksum check on startup
    out-of-order: false          # strict ascending order
    locations: classpath:db/migration/{service-name}
```

**Spring Boot 4.0 quirk**: a `@Configuration` bean is required alongside `spring.flyway.*` properties
to satisfy Boot 4's stricter autoconfiguration. See `services/user-service/src/main/java/.../config/FlywayConfig.java`
as the reference to copy.

## Migrations via Init Container (NOT app startup)

Running Flyway inside the Spring app means N pods race to migrate on the same schema simultaneously.
Instead, run Flyway as a Kubernetes `initContainer` — it completes once before any app pod starts.

```yaml
initContainers:
  - name: flyway-migrate
    image: flyway/flyway:10
    command:
      - flyway
      - -url=$(DB_URL)
      - -user=$(DB_USERNAME)
      - -password=$(DB_PASSWORD)
      - -locations=filesystem:/migrations
      - migrate
    envFrom:
      - secretRef:
          name: {service-name}-secrets   # ExternalSecrets injects DB_URL, DB_USERNAME, DB_PASSWORD
    volumeMounts:
      - name: migrations
        mountPath: /migrations
```

## Backwards-Compatible Change Patterns

These sequences prevent outages when a schema change and a code rollout happen simultaneously.
The rule: **the database must be readable by both the old and new code version** during any
rolling deployment window.

### Adding a column

```sql
-- Migration N (deploy this first, code unchanged):
ALTER TABLE products ADD COLUMN description_long TEXT NULL;

-- Then update code to write to the new column.
-- Migration N+1 (weeks later, after all pods run new code):
ALTER TABLE products ALTER COLUMN description_long SET NOT NULL;
```

### Removing a column

```sql
-- Step 1 (code change): stop reading and writing the column in application code.
-- Step 2 (migration, after full rollout):
ALTER TABLE products DROP COLUMN legacy_sku;
```

Never drop first; always stop using it first.

### Renaming a column

This takes three migrations across three deploys:

```sql
-- Migration N: add new column alongside old
ALTER TABLE orders ADD COLUMN customer_reference TEXT NULL;

-- Code change: dual-write to both columns, read from new one.

-- Migration N+1: backfill rows that arrived before the dual-write
UPDATE orders SET customer_reference = order_ref WHERE customer_reference IS NULL;

-- Code change: stop writing to old column.

-- Migration N+2: drop the old column
ALTER TABLE orders DROP COLUMN order_ref;
```

### Changing a column type

Always use the rename pattern above — add a new column with the desired type, migrate data, drop the old.

## Locking Patterns

| Pattern | Use case |
|---------|---------|
| `SELECT ... FOR UPDATE` | State machine transitions — lock one row, check state, update atomically |
| `SELECT ... FOR UPDATE NOWAIT` | Race resolution (e.g., delivery claim) — fail fast if already locked |
| `SELECT ... FOR UPDATE SKIP LOCKED` | Outbox pollers — multiple pods each grab different rows, no contention |
| `pg_advisory_lock(key)` | Cross-pod coordination (e.g., saga timeout enforcer scheduled task) |

Outbox poll pattern:
```sql
SELECT * FROM outbox
WHERE processed_at IS NULL
ORDER BY created_at
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

## Indexing Rules

- Index every foreign key column (PostgreSQL does not do this automatically).
- Index every column that appears in a `WHERE` clause on a hot query.
- Use composite indexes when queries filter on multiple columns together.
- Name: `idx_{table}_{col1}_{col2}` — e.g., `idx_orders_customer_id_created_at`.
- Periodically audit unused indexes via `pg_stat_user_indexes` and remove dead weight.

```sql
-- In the migration that adds a foreign key, also add its index:
ALTER TABLE order_items ADD CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);
```

## HikariCP Tuning for Aurora

```yaml
spring:
  datasource:
    url: ${DB_URL}                        # cluster endpoint for writes
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 10              # (Aurora max_connections / expected pod count) × 0.8
      minimum-idle: 2
      connection-timeout: 5000           # ms — fail fast rather than queue indefinitely
      idle-timeout: 300000               # 5 min
      max-lifetime: 1800000              # 30 min — must be < Aurora's wait_timeout
      leak-detection-threshold: 60000    # 1 min — logs a warning for connections held too long
      pool-name: {service-name}-pool
```

Aurora-specific notes:
- Use the **cluster endpoint** (`cluster.xxx.rds.amazonaws.com`) for writes.
- For read replicas, add a separate reader datasource using the **reader endpoint** and annotate
  read-only transaction methods with `@Transactional(readOnly = true)`.
- Aurora failover takes ~30 s. HikariCP's `connection-timeout` should be shorter than your
  upstream client's timeout so the pod fails fast and Kubernetes can restart it rather than
  hanging requests.

## Common SQL Patterns

**Upsert (ON CONFLICT):**
```sql
INSERT INTO promo_codes (user_id, code, code_type, redeemed_at)
VALUES (?, ?, ?, NULL)
ON CONFLICT (user_id, code_type) DO NOTHING
RETURNING id;
```

**Saga timeout scan:**
```sql
SELECT * FROM orders
WHERE state NOT IN ('DELIVERED', 'CANCELED', 'FAILED')
  AND updated_at < NOW() - INTERVAL '5 minutes';
```

**Outbox schema** — see the `outbox-pattern` skill for the canonical table definition; the migration
that creates it is `V{n}__add_outbox_table.sql`.

## Integration Test Setup (Testcontainers)

```java
@Testcontainers
@SpringBootTest
class ProductRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Flyway runs its migrations against this container at test startup
    }
}
```

Use `@Sql("/test-data/products.sql")` on individual tests that need pre-existing rows. Keep test
data files small and focused — they run in declaration order within the annotation.

## Example: V1__create_products.sql

```sql
CREATE TABLE products (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    price       NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    category    VARCHAR(100) NOT NULL,
    stock       INTEGER NOT NULL DEFAULT 0 CHECK (stock >= 0),
    version     BIGINT NOT NULL DEFAULT 0,   -- optimistic locking (@Version)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_products_category ON products(category);
```

## Anti-Patterns — Flag These

| Anti-pattern | Why it's dangerous |
|-------------|-------------------|
| Editing a committed `V` migration file | Flyway checksum mismatch fails all pod startups |
| Two migrations with the same version number | Flyway fails on startup; one file will be silently ignored locally |
| `DROP COLUMN` / `NOT NULL` without prior nullable-add migration | Old pods crash reading a schema they don't understand |
| `spring.jpa.hibernate.ddl-auto=update` or `create-drop` | Never in production — use `validate` or `none` |
| Flyway inside the app (not init container) | Multiple pods race; second pod fails mid-migration |
| Schema migrations and code changes in the same PR | Requires coordination that's easy to get wrong; split into two PRs |
| `maximum-pool-size` not accounting for pod count × Aurora max_connections | Aurora connection exhaustion under load |
| `TIMESTAMP` / `LocalDateTime` for timestamped columns | Use `TIMESTAMPTZ` — `TIMESTAMP` silently drops timezone information |
| Long-running `ALTER TABLE ... ADD COLUMN NOT NULL` on large tables | Locks the table for the duration; use `pg_repack` or a nullable-first approach |
