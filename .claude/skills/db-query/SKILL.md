---
name: db-query
description: Run a SQL query against any of the four project databases for debugging. Use when checking Saga state, order status, payment records, user activation, or product stock. Databases: user (user_db), order (order_db), payment (payment_db), product (product_db).
disable-model-invocation: true
allowed-tools: Bash(docker *)
argument-hint: "<db: user|order|payment|product> <sql>"
---

Run a SQL query inside the `postgres-db` container. Credentials: user `user`, password `password`.

## Database map

| Argument | Database | Key tables |
|----------|----------|------------|
| `user` | `user_db` | `users` (id, username, status: PENDING→ACTIVE) |
| `order` | `order_db` | `orders` (id, status: PENDING→PAID/FAILED, items, user_id) |
| `payment` | `payment_db` | `payments` (id, order_id, status: PENDING→SUCCESS/FAILED/REFUNDED, amount) |
| `product` | `product_db` | `products` (id, name, price, stock, version) |

## Steps

1. Parse the first argument as the database shortname (`user`, `order`, `payment`, or `product`). The remaining arguments form the SQL query.

2. Map the shortname to the full database name using the table above (append `_db`).

3. Run:
   ```
   docker exec postgres-db psql -U user -d <db>_db -c "<sql>"
   ```

4. If no argument is given, show a brief usage example and the database map table.

## Common debug queries

```sql
-- Check stuck PENDING orders
SELECT id, status, created_at FROM orders WHERE status = 'PENDING' ORDER BY created_at DESC LIMIT 10;

-- Check a specific user's activation state
SELECT id, username, status FROM users WHERE username = '<name>';

-- Check payment for an order
SELECT id, order_id, status, amount FROM payments WHERE order_id = '<id>';

-- Check product stock
SELECT id, name, stock, version FROM products ORDER BY name;
```
