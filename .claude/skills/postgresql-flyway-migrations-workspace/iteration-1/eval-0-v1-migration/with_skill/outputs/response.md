# V1 Flyway Migration — products table

**File:** `services/product-service/src/main/resources/db/migration/product-service/V1__create_products.sql`

```sql
CREATE TABLE products (
    id          UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255)   NOT NULL,
    description TEXT,
    price       NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    category    VARCHAR(100),
    stock       INTEGER        NOT NULL DEFAULT 0 CHECK (stock >= 0),
    version     BIGINT         NOT NULL DEFAULT 0,   -- optimistic locking (@Version)
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_products_category ON products(category);
CREATE INDEX idx_products_name     ON products(name);
```

## Notes

- **File name**: exactly two underscores, no gaps in the version number sequence. This is V1, so the next migration must be V2.
- **`gen_random_uuid()`**: built-in to PostgreSQL 13+ (Aurora Serverless v2 runs PG 16). No extension needed.
- **`version BIGINT NOT NULL DEFAULT 0`**: the `@Version` field. JPA increments it on every `UPDATE`; a stale-read conflict throws `OptimisticLockingFailureException`.
- **`TIMESTAMPTZ` not `TIMESTAMP`**: timezone-aware. `TIMESTAMP`/`LocalDateTime` silently drops timezone info — this is an anti-pattern listed in the skill.
- **`description` is nullable**: matches the entity spec (nullable). No `NOT NULL` constraint.
- **`category` is nullable**: the entity spec says `VARCHAR(100)` with no explicit NOT NULL, so this matches. If the entity later adds `@Column(nullable = false)`, add a backwards-compatible migration: first add `NOT NULL DEFAULT ''`, then remove the default.
- **Indexes**:
  - `idx_products_category` — any listing/filter query by category will use this.
  - `idx_products_name` — name lookups / search-by-name queries. Omit if name is never queried directly; add a `pg_trgm` GIN index later if full-text search is needed.
- **`ddl-auto`**: must be `validate` or `none` in production. Never `update` or `create-drop`.
- **Migration runs via init container**, not inside the Spring app, to prevent pod-race conditions on startup.
