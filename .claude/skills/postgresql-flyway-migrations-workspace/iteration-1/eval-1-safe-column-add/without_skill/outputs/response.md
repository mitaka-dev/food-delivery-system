# Safe Schema Change: Adding `long_description` (TEXT, eventually NOT NULL)

## The Problem

You cannot add a `NOT NULL` column with no default to a live table in a single step — it requires a full table rewrite in PostgreSQL, which locks the table, and it breaks old pods (which have no code to write that column) the moment the migration runs.

The safe path is the **expand / contract** pattern: three deployment phases spread across two or three releases.

---

## Phase 1 — Expand: Add the column as nullable (Deploy this now)

### Migration: `V2__add_long_description_nullable.sql`

```sql
-- Safe: ADD COLUMN with no NOT NULL constraint is a metadata-only change in PostgreSQL.
-- Old pods ignore the column; new pods can read and write it.
-- No table lock beyond a brief ACCESS EXCLUSIVE for the catalog update (~ms).
ALTER TABLE products ADD COLUMN long_description TEXT;
```

This migration runs at pod startup (via Flyway) before any new-code pods serve traffic. Because the column is nullable:

- Old pods (no `long_description` field) write NULL — perfectly valid.
- New pods read and write the column normally.
- The rolling update is safe throughout: the schema is compatible with both code versions simultaneously.

### Java changes in the same release

**`Product` entity** — add the field (nullable, no `@Column(nullable = false)` yet):

```java
@Column(columnDefinition = "TEXT")
private String longDescription;

public String getLongDescription() { return longDescription; }
public void setLongDescription(String longDescription) { this.longDescription = longDescription; }
```

**`CreateProductDto`** — add optional field:

```java
@Schema(description = "Long-form product description")
String longDescription   // no @NotBlank — still optional this release
```

**`ProductResponseDto`** — add field:

```java
@Schema(description = "Long-form product description") String longDescription
```

**`ProductService.createProduct`** — wire it:

```java
product.setLongDescription(dto.longDescription());
```

**`ProductService.toDto`** — include it:

```java
return new ProductResponseDto(
    product.getId(), product.getName(), product.getDescription(),
    product.getLongDescription(),   // new
    product.getPrice(), product.getCategory(), product.getStock()
);
```

### Deployment sequence — Phase 1

```
1. Merge migration V2 + Java changes.
2. Build and push image (tag: v2).
3. Flyway runs V2 migration at first pod startup — column added, all pods unaffected.
4. Rolling update completes: all pods now on v2 code.
5. Verify: GET /products/{id} returns longDescription (null for existing rows).
```

---

## Phase 2 — Backfill: Populate existing rows (can be in same release or next)

Old rows have `long_description = NULL`. Before enforcing NOT NULL you must give every row a value.

### Migration: `V3__backfill_long_description.sql`

```sql
-- Backfill existing rows with a placeholder so the eventual NOT NULL constraint succeeds.
-- Run this as a separate migration from the NOT NULL migration so it can be monitored.
-- For large tables, consider batching; products table is expected to be small here.
UPDATE products
SET long_description = description  -- sensible default: copy the short description
WHERE long_description IS NULL;
```

> **Note:** If the products table could be large (millions of rows), replace the single UPDATE with a batched loop or use `pg_repack`. For this service's expected data volume (catalogue items), a single UPDATE is fine.

No code change is required for this migration. It runs at pod startup and completes before traffic switches.

---

## Phase 3 — Contract: Enforce NOT NULL (a later release)

Only after every row has a value and every write path always sets the column can you add the constraint.

### Migration: `V4__long_description_not_null.sql`

```sql
-- Enforce NOT NULL now that all rows are populated and all code always sets the column.
-- In PostgreSQL 12+ you can use NOT VALID + VALIDATE to avoid a full table scan lock,
-- but for a simple NOT NULL with no CHECK expression, the standard form is fine for
-- a small catalogue table. For safety on larger tables, use the two-step form below.

-- Option A (simple, fine for small tables):
ALTER TABLE products ALTER COLUMN long_description SET NOT NULL;

-- Option B (large tables — avoids long ACCESS EXCLUSIVE lock):
-- ALTER TABLE products ADD CONSTRAINT chk_long_description_not_null
--     CHECK (long_description IS NOT NULL) NOT VALID;
-- -- Deploy, let traffic settle, then in the next migration:
-- ALTER TABLE products VALIDATE CONSTRAINT chk_long_description_not_null;
-- -- Finally: ALTER TABLE products ALTER COLUMN long_description SET NOT NULL;
-- -- DROP CONSTRAINT chk_long_description_not_null;
```

### Java changes in the same release

**`Product` entity** — make the column non-nullable:

```java
@Column(columnDefinition = "TEXT", nullable = false)
private String longDescription;
```

**`CreateProductDto`** — enforce it at the API layer:

```java
@NotBlank(message = "Long description is required") String longDescription
```

### Deployment sequence — Phase 3

```
1. Merge V4 migration + nullability hardening.
2. Build and push image (tag: v4).
3. Flyway runs V4: ALTER COLUMN SET NOT NULL (~ms, no row rewrite needed because
   PostgreSQL can verify the constraint from the not-null check it already stored).
4. Rolling update: all new pods reject requests missing longDescription.
5. Old pods are gone before this migration runs, so no conflict.
```

---

## Summary: Full deployment sequence across releases

| Release | Migration | Schema state | Code state |
|---------|-----------|--------------|------------|
| R1 | V2: ADD COLUMN nullable | `long_description TEXT` | Reads/writes column, field optional |
| R1 or R2 | V3: UPDATE backfill | All rows non-null (data) | No change |
| R2 or R3 | V4: SET NOT NULL | `long_description TEXT NOT NULL` | Field required in API + entity |

**The invariant:** every migration must be backwards-compatible with the code version that was running immediately before it. V2 is safe because nullable columns are invisible to old code. V3 is safe because it only writes data. V4 is safe because all writes already set the column and no old pods remain.

---

## Why NOT a single migration?

```sql
-- NEVER do this in a rolling deploy:
ALTER TABLE products ADD COLUMN long_description TEXT NOT NULL;
-- ^ Fails immediately: existing rows have no value.

ALTER TABLE products ADD COLUMN long_description TEXT NOT NULL DEFAULT '';
-- ^ Succeeds but: old pods that don't know about the column write '' for every INSERT,
--   and new pods that set @NotBlank will reject those same empty strings — inconsistent.
--   Also, the DEFAULT is a catalog-level default (fine in PG 11+), but empty string
--   is a worse default than NULL for a text column you intend to fill meaningfully.
```

A non-null column with a meaningful default can sometimes be added in one step (PG 11+, metadata-only if the default is immutable), but it only works when the default value is acceptable forever — not just as a migration placeholder. Here the goal is real content, so the three-phase sequence is the right approach.

---

## Project-specific notes

- `product-service` currently uses `ddl-auto: update` (no Flyway yet). Step 3.1 in BUILD-PLAN.md wires Flyway for this service. These migrations (`V1__create_products.sql` through `V4__...`) should be authored as part of that step.
- The existing `description` field is `TEXT`, nullable — the same pattern used here for `long_description` in Phase 1.
- `ddl-auto: update` must be changed to `ddl-auto: validate` (or `none`) before Flyway is enabled in production, otherwise Hibernate and Flyway will fight over the schema.
- File naming follows the project convention: `V{n}__{description}.sql` (two underscores), stored under `services/product-service/src/main/resources/db/migration/`.
