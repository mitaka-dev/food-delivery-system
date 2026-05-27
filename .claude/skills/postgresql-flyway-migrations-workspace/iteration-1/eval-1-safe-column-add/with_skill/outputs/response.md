# Safe Schema Change: Adding `long_description` (TEXT, eventually NOT NULL) to `products`

## The Core Rule

During a rolling deploy, **old and new pods run simultaneously against the same database**.
Any migration that breaks old-code reads or writes will crash the old pods mid-rollout and cause
an outage. The safe pattern is always: add nullable first, ship code, then enforce NOT NULL.

---

## Prerequisite: Fix `ddl-auto` First

`product-service/src/main/resources/application.yaml` currently has:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update
```

This is explicitly flagged as dangerous — Hibernate's `update` mode will silently modify the
schema at startup in ways that are not versioned, not repeatable, and not safe under concurrent
pod starts. Before adding any Flyway migrations, change this to:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate   # Flyway owns the schema; Hibernate only validates entity mapping
```

---

## Step 1 — Bootstrap Flyway for product-service

product-service has no migration files yet. Create the directory structure and the initial
migration that captures the current schema state (what Hibernate has been managing via `ddl-auto`):

```
services/product-service/src/main/resources/db/migration/product-service/V1__create_products.sql
```

This V1 establishes the baseline. The skill's example is directly applicable here:

```sql
-- V1__create_products.sql
CREATE TABLE IF NOT EXISTS products (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255)  NOT NULL,
    description TEXT,
    price       NUMERIC(10,2) NOT NULL CHECK (price >= 0),
    category    VARCHAR(100)  NOT NULL,
    stock       INTEGER       NOT NULL DEFAULT 0 CHECK (stock >= 0),
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_products_category ON products(category);
```

Use `CREATE TABLE IF NOT EXISTS` because the table already exists in production (Hibernate created
it). Flyway will checksum this file and mark it applied without re-running the DDL.

Also add Flyway config to `application.yaml`:

```yaml
spring:
  flyway:
    baseline-on-migrate: false
    validate-on-migrate: true
    out-of-order: false
    locations: classpath:db/migration/product-service
```

---

## Step 2 — The Two-Migration Sequence for `long_description`

### Migration 2: Add the column as nullable

```
services/product-service/src/main/resources/db/migration/product-service/V2__add_long_description.sql
```

```sql
-- V2__add_long_description.sql
-- Safe for rolling deploy: NULL is accepted by both old and new code.
-- Old pods: don't know the column exists, write nothing — Aurora stores NULL. No crash.
-- New pods: write the column value. Read it back.
ALTER TABLE products ADD COLUMN long_description TEXT NULL;
```

**Why this is safe**: old pods have no mapping for `long_description`. When they INSERT or UPDATE
a product, they simply omit the column — the database stores NULL. When they SELECT *, they get
a column they ignore. Neither reads nor writes fail.

### Migration 3: Enforce NOT NULL (weeks later, separate deploy)

```
services/product-service/src/main/resources/db/migration/product-service/V3__long_description_not_null.sql
```

```sql
-- V3__long_description_not_null.sql
-- Run ONLY after: all pods run new code AND all existing rows have been backfilled.
-- Do NOT merge this migration in the same PR as V2 or the code change.

-- 1. Backfill any rows that existed before new code started writing the column.
UPDATE products SET long_description = '' WHERE long_description IS NULL;

-- 2. Now it is safe to enforce the constraint.
ALTER TABLE products ALTER COLUMN long_description SET NOT NULL;
```

**Why the backfill is required**: rows written before the new code deployed will have
`long_description = NULL`. Setting NOT NULL without backfilling will fail immediately because
existing NULL rows violate the constraint — the ALTER TABLE will error out and Flyway will
leave the schema in a failed state.

---

## Deployment Sequence

```
PR 1  — Flyway bootstrap + V2 migration only (no entity change)
        ├─ V1__create_products.sql     (baseline)
        ├─ V2__add_long_description.sql  (nullable column)
        ├─ application.yaml: ddl-auto → validate
        └─ application.yaml: flyway config added

        Deploy:
        1. initContainer runs Flyway → applies V1 (baseline) + V2 (ADD COLUMN NULL)
        2. New pods start. Old pods still running (rolling). Both work fine — nulls are fine.
        3. Rolling deploy completes. All pods are new version (but new code not yet written).

PR 2  — Code change: map `long_description` in the Product entity + write it on create/update
        ├─ Product.java: add @Column(name = "long_description") String longDescription
        ├─ ProductRequest.java: add longDescription field
        └─ ProductService.java: populate longDescription when persisting

        Deploy:
        1. initContainer runs Flyway → no new migrations, nothing to do.
        2. Rolling deploy: new pods write long_description; old pods (if any remain briefly) write NULL.
           Both are accepted by the DB. No outage.
        3. Rolling deploy completes. All pods write long_description.

        Wait: let this run until you're confident all existing rows have been touched
        (or run a backfill job if products are rarely updated).

PR 3  — V3 migration: enforce NOT NULL
        └─ V3__long_description_not_null.sql  (UPDATE + ALTER COLUMN SET NOT NULL)

        Deploy:
        1. initContainer runs Flyway → UPDATE backfill + ALTER TABLE. Completes before any pod starts.
        2. New pods start with NOT NULL enforced.
        3. Entity annotation can now be: @Column(name = "long_description", nullable = false)
```

---

## Flyway as an initContainer

Migrations must NOT run inside the Spring app itself — multiple pods racing to migrate the same
schema will cause failures on every pod after the first. Run Flyway as a Kubernetes initContainer:

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
          name: product-service-secrets
    volumeMounts:
      - name: migrations
        mountPath: /migrations
```

The initContainer completes (and succeeds) once before any app pod starts. Kubernetes will not
start the main container if the initContainer exits non-zero — so a failed migration stops the
deploy before it can damage running pods.

---

## Summary of the Three-PR Rule

| PR | What it contains | What it does NOT contain |
|----|-----------------|--------------------------|
| 1 | V2 migration (nullable column), Flyway bootstrap | Entity/DTO changes |
| 2 | Entity + DTO + service code writing the column | NOT NULL migration |
| 3 | V3 migration (backfill + NOT NULL) | Any further code changes |

Never combine a schema migration and the code change that depends on it in the same PR. The
rolling deploy window means old pods will run against the new schema for some minutes, and
new pods will start against the old schema for some minutes — the only safe invariant is that
the schema is always a superset of what any running code version requires.
