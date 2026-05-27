---
name: resilience4j-patterns
description: Resilience4j circuit breakers, retries, bulkheads, and time limiters for this platform. Use when adding @CircuitBreaker, @Retry, @Bulkhead, @TimeLimiter, or editing resilience4j.* in application.yml.
allowed-tools: Read, Edit, Write, Bash(./mvnw *)
---

# Resilience4j Patterns

Resilience4j (latest via BOM) with Spring Boot 4 integration. Applied on all outbound calls: Stripe, gRPC services, internal REST, SES.

## Default Config (in common-resilience, application-resilience.yml)

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 60s
        permitted-number-of-calls-in-half-open-state: 5
        record-exceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
        ignore-exceptions:
          - food.ordering.system.common.exception.ValidationException
  retry:
    configs:
      default:
        max-attempts: 3
        wait-duration: 200ms
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
        ignore-exceptions:
          - food.ordering.system.common.exception.ClientErrorException
  timelimiter:
    configs:
      default:
        timeout-duration: 5s
        cancel-running-future: true
  bulkhead:
    configs:
      default:
        max-concurrent-calls: 25
        max-wait-duration: 100ms
```

## Per-Call-Type Overrides

| Call | Timeout | Retries | CB threshold |
|---|---|---|---|
| gRPC internal (Menu, Promotion) | 200ms | 2, 50ms backoff | 50% / 20 |
| REST internal | 500ms | 1 | 50% / 20 |
| Stripe charge | 5s | 3, 200ms × 2 | 50% / 20 |
| Stripe refund | 5s | 3 | 50% / 20 |
| DynamoDB | 1s | SDK default | n/a |
| SES email | 3s | 2 | 60% / 20 |

## Naming Convention

For Grafana dashboards — be consistent:
- Circuit breakers & retries: `{remote-target}-{operation}` — e.g. `stripe-charge`, `menu-grpc-verify`
- Bulkheads: `{operation-class}` — e.g. `payment-charge`, `payment-refund`
- Rate limiters: `{endpoint-or-feature}` — e.g. `login`, `register`

## Canonical Annotation Usage

```java
@CircuitBreaker(name = "stripe-charge", fallbackMethod = "chargeFallback")
@Retry(name = "stripe-charge")
@Bulkhead(name = "payment-charge", type = Bulkhead.Type.SEMAPHORE)
@TimeLimiter(name = "stripe-charge")
public CompletableFuture<ChargeResult> charge(ChargeRequest req) {
    return CompletableFuture.supplyAsync(() -> stripeClient.charge(req));
}

public CompletableFuture<ChargeResult> chargeFallback(ChargeRequest req, Throwable t) {
    log.error("Charge fallback for order {}", req.orderId(), t);
    throw new PaymentGatewayUnavailableException(req.orderId(), t);
}
```

Annotation order matters: `@CircuitBreaker` outermost, `@TimeLimiter` innermost when stacked.

## Service-Level yml Override

```yaml
# services/payment-service/src/main/resources/application.yml
resilience4j:
  circuitbreaker:
    instances:
      stripe-charge:
        base-config: default
        failure-rate-threshold: 50
  retry:
    instances:
      stripe-charge:
        base-config: default
        max-attempts: 3
        wait-duration: 200ms
        exponential-backoff-multiplier: 2
  bulkhead:
    instances:
      payment-charge:
        max-concurrent-calls: 20
      payment-refund:
        max-concurrent-calls: 10   # separate bulkhead — charge and refund don't starve each other
```

## Key Rules

**Fallback methods:** same return type as the primary method, last parameter must be `Throwable`. For Stripe failures: throw a wrapped domain exception. For non-critical reads (e.g. menu enrichment): return stale cached data.

**Bulkhead type:** Use `SEMAPHORE` (default). With Java 25 virtual threads, `THREADPOOL` defeats the model — avoid it.

**TimeLimiter scope:** Only works on `CompletableFuture<T>`. For sync calls, set the HTTP client timeout directly + catch `TimeoutException` in the retry config. Do not put `@TimeLimiter` on sync methods.

**Retry safety:** Only retry idempotent operations. Never retry a charge/order-create without an idempotency key — you will double-charge. Safe to retry: GET, SDK calls with idempotency headers, conditional writes.

## Metrics (auto-registered via Micrometer → /actuator/prometheus)

- `resilience4j.circuitbreaker.state{name=stripe-charge}` — CLOSED/OPEN/HALF_OPEN
- `resilience4j.circuitbreaker.calls{name=stripe-charge,kind=failed}`
- `resilience4j.retry.calls{name=stripe-charge,kind=successful_without_retry}`
- `resilience4j.bulkhead.available.concurrent.calls{name=payment-charge}`

## CB State Change Logging

```java
@Bean
public RegistryEventConsumer<CircuitBreaker> cbStateLogger() {
    return new RegistryEventConsumer<>() {
        public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> ev) {
            ev.getAddedEntry().getEventPublisher().onStateTransition(e ->
                log.warn("CB {} {} → {}",
                    e.getCircuitBreakerName(),
                    e.getStateTransition().getFromState(),
                    e.getStateTransition().getToState()));
        }
    };
}
```

## Anti-Patterns — Flag Immediately

| Anti-pattern | Fix |
|---|---|
| Annotation `name` has no matching `instances:` in yml | Add the yml config block |
| `retry-exceptions: java.lang.Throwable` | List specific retryable exceptions only |
| Retrying a non-idempotent POST without idempotency key | Add `Idempotency-Key` header or use outbox |
| `@CircuitBreaker` without `fallbackMethod` | Hard failure on circuit open — always add fallback |
| Fallback method with wrong return type | Must exactly match the primary method's return type |
| `Bulkhead.Type.THREADPOOL` with virtual threads | Use `SEMAPHORE` instead |
| `@TimeLimiter` on a sync method | Only works on `CompletableFuture<T>` |
| Hardcoded thresholds inside annotations | Move to `application.yml` instances block |
| Bulkhead `max-concurrent-calls` > connection pool size | Size bulkhead ≤ underlying pool |
