---
name: kafka-msk-iam-auth
description: Kafka + Amazon MSK with IAM auth for this platform. Use when writing producers, consumers, @KafkaListener, KafkaTemplate, spring.kafka config, or MSK IAM policy Terraform.
allowed-tools: Read, Edit, Write, Bash(./mvnw *)
---

# Kafka MSK IAM Auth

Amazon MSK with IAM authentication. Six services produce or consume: Order, Payment, Promotion, Kitchen, Delivery, Notification.

## Required Dependencies (service pom.xml — no versions, BOM provides)

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
<dependency>
    <groupId>software.amazon.msk</groupId>
    <artifactId>aws-msk-iam-auth</artifactId>
</dependency>
```

## Producer Config (application.yml)

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
    properties:
      security.protocol: SASL_SSL
      sasl.mechanism: AWS_MSK_IAM
      sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
      sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler
    producer:
      acks: all
      enable-idempotence: true
      retries: 2147483647
      compression-type: lz4
      max-in-flight-requests-per-connection: 5
      linger-ms: 10
      batch-size: 32768
      properties:
        delivery.timeout.ms: 120000
        key-serializer: org.apache.kafka.common.serialization.StringSerializer
        value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
```

## Consumer Config (application.yml)

```yaml
spring:
  kafka:
    consumer:
      group-id: ${SERVICE_NAME}-${KAFKA_TOPIC_NAME}
      enable-auto-commit: false
      auto-offset-reset: earliest
      max-poll-records: 50
      properties:
        key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
        value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
        spring.json.trusted.packages: "food.ordering.system.*"
    listener:
      ack-mode: MANUAL_IMMEDIATE
      concurrency: 3
```

## Listener Pattern (canonical)

```java
@Component
public class PaymentResultListener {

    private final OrderSagaService saga;

    public PaymentResultListener(OrderSagaService saga) {
        this.saga = saga;
    }

    @KafkaListener(
        topics = KafkaConstants.PAYMENT_TOPIC,
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onPaymentResult(
            ConsumerRecord<String, PaymentProcessedEvent> record,
            Acknowledgment ack) {

        String eventType = headerAsString(record, "eventType");
        if (!"PAYMENT_SUCCESS".equals(eventType) && !"PAYMENT_FAILED".equals(eventType)) {
            ack.acknowledge();
            return;
        }
        try {
            saga.onPaymentResult(record.value());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process payment event {}", record.key(), e);
            throw e;   // do NOT ack — Kafka redelivers; Spring error handler → DLT
        }
    }

    private String headerAsString(ConsumerRecord<?, ?> r, String key) {
        var h = r.headers().lastHeader(key);
        return h == null ? "" : new String(h.value());
    }
}
```

## Required Kafka Headers

| Header | Type | Purpose |
|---|---|---|
| `eventType` | String | Filter inside multi-event topic listeners |
| `eventId` | String | Consumer-side idempotency dedup |
| `traceId` | String | OTel propagation |
| `schemaVersion` | String | Schema evolution tracking |

## Partition Key Strategy

Use aggregate ID (orderId, userId, restaurantId) as the Kafka message key. Per-key ordering is preserved within a partition. Never use null keys for events that need ordering.

## Topic Configuration (from Terraform)

| Topic | Partitions | Retention |
|---|---|---|
| `order-topics` | 12 | 7 days |
| `payment-topics` | 12 | 30 days |
| `kitchen-events` | 6 | 7 days |
| `identity-events` | 6 | 7 days |

## IRSA Permissions (Terraform)

Producer needs: `kafka-cluster:Connect`, `kafka-cluster:WriteData`, `kafka-cluster:DescribeTopic`.
Consumer needs: `kafka-cluster:Connect`, `kafka-cluster:ReadData`, `kafka-cluster:AlterGroup`, `kafka-cluster:DescribeTopic`.

## Testcontainers (integration tests)

```java
@Container
static KafkaContainer kafka = new KafkaContainer(
    DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

@DynamicPropertySource
static void kafkaProps(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("spring.kafka.properties.security.protocol", () -> "PLAINTEXT");
    registry.add("spring.kafka.properties.sasl.mechanism", () -> "");
    registry.add("spring.kafka.properties.sasl.jaas.config", () -> "");
}
```

## Anti-Patterns — Flag Immediately

| Anti-pattern | Fix |
|---|---|
| `enable-auto-commit: true` | Must be `false` — manual commits only |
| Missing `acks: all` on producer | Risk of message loss on broker failure |
| Missing `enable-idempotence: true` | Risk of duplicate messages on retry |
| Listener catches exception AND acks | Silent message loss — rethrow or skip ack |
| `@KafkaListener` without `groupId` | Always specify group ID |
| Hardcoded bootstrap server URL | Use `${KAFKA_BOOTSTRAP_SERVERS}` |
| Inline topic string like `"order-topics"` | Use `KafkaConstants.ORDER_TOPIC` |
| SASL config present in Testcontainers tests | Override to PLAINTEXT in `@DynamicPropertySource` |
