# V1 Migration — Baseline (no skill)

**V1__create_products_table.sql**

```sql
CREATE TABLE products (
    id          UUID            NOT NULL,
    name        VARCHAR(255)    NOT NULL,
    description TEXT,
    price       NUMERIC(10, 2)  NOT NULL,
    category    VARCHAR(100),
    stock       INTEGER         NOT NULL DEFAULT 0,
    version     BIGINT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_products          PRIMARY KEY (id),
    CONSTRAINT chk_products_price   CHECK (price >= 0),
    CONSTRAINT chk_products_stock   CHECK (stock >= 0)
);

CREATE INDEX idx_products_category  ON products (category);
CREATE INDEX idx_products_name      ON products (name);
```

Key decisions (from general knowledge):

- `version` is `BIGINT NOT NULL DEFAULT 0` — JPA `@Version` with `Long` increments from zero.
- `TIMESTAMPTZ` rather than bare `TIMESTAMP` — Aurora stores in UTC.
- `created_at` / `updated_at` default to `now()` at the DB level as a safety net.
- `price >= 0` and `stock >= 0` constraints mirror JPA validation — defence in depth.
- Index on `category` for filter-by-category query; index on `name` for name searches.
- No `UNIQUE` constraint on `name` — nothing in the spec requires it.

Note: baseline used filename `V1__create_products_table.sql` (not `V1__create_products.sql`).
No mention of gen_random_uuid() default for UUID PK — left id without a DEFAULT.
No mention of Flyway config, init container, or Boot 4.0 FlywayConfig bean requirement.
