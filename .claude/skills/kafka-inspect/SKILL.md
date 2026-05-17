---
name: kafka-inspect
description: Check Kafka consumer group lag and topic offsets for all Saga groups. Use when Saga flows are stuck, orders stay PENDING, or you want to verify Kafka consumer health. Groups: order-group, product-group, payment-group, user-group, analytics-group.
disable-model-invocation: true
allowed-tools: Bash(docker *)
argument-hint: "[group-name|all]"
---

Check Kafka consumer group lag using `kafka-consumer-groups` inside the `kafka` container (bootstrap: `localhost:9092`).

## Groups

| Group | Consumer | Topic |
|-------|----------|-------|
| `order-group` | order-service | order-confirmation-topic |
| `product-group` | product-service | order-topics, payment-topics |
| `payment-group` | payment-service | order-topics |
| `user-group` | user-service | user-confirmation-topic |
| `analytics-group` | analytics-service | user-topics |

## Steps

1. Parse the argument (if provided). Valid values: a group name from the table above, or `all` / no argument.

2. If a specific group was given, run:
   ```
   docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group <group>
   ```

3. If argument is `all` or omitted, run the describe command for every group in the table above — one command per group. Print a clear `=== <group> ===` separator before each result.

4. Interpret the output:
   - `LAG` column: `0` means the consumer is caught up. Any positive value means unconsumed messages.
   - `-` in CONSUMER-ID means no active consumer is connected (service may be down).
   - If a group has no committed offsets yet (e.g., `Error: Consumer group 'X' does not exist`), note that it hasn't consumed any messages yet.

5. Summarize what you found: which groups are healthy, which have lag, and whether any services appear disconnected.
