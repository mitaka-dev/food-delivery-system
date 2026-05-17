---
name: aws-sdk-v2-conventions
description: AWS SDK v2 patterns for DynamoDB in this project. Use when working on kitchen-service, review-service, or any service that uses DynamoDB or imports software.amazon.awssdk.
allowed-tools: Read, Edit, Write
---

# AWS SDK v2 Conventions

This project uses `software.amazon.awssdk` v2 (managed via BOM `2.29.0` in root pom.xml).
Two services use DynamoDB: `kitchen-service` (tickets) and `review-service` (reviews).

## DynamoDbConfig Pattern

```java
// config/DynamoDbConfig.java
@Configuration
public class DynamoDbConfig {

    @Value("${aws.region:us-east-1}")
    private String region;

    @Value("${aws.dynamodb.endpoint:}")   // empty = real AWS; set for LocalStack
    private String dynamoDbEndpoint;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region));
        if (!dynamoDbEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(dynamoDbEndpoint));
        }
        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    }
}
```

## DynamoDB Entity — Must Be a Mutable JavaBean

**DynamoDB Enhanced Client cannot use Java records.** Entities must be:
- Mutable JavaBeans (no-arg constructor + getters/setters)
- Annotated with `@DynamoDbBean` on the class
- `@DynamoDbPartitionKey` on the **getter**, not the field

```java
@DynamoDbBean
public class KitchenTicket {

    private String ticketId;
    private String orderId;
    private String status;    // store enum as String via enum.name()
    private String createdAt; // ISO-8601 String, e.g. Instant.now().toString()

    public KitchenTicket() {}  // required

    @DynamoDbPartitionKey
    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
```

## Service Pattern

```java
@Service
public class KitchenTicketService {

    private final DynamoDbTable<KitchenTicket> ticketTable;

    public KitchenTicketService(DynamoDbEnhancedClient enhancedClient) {
        this.ticketTable = enhancedClient.table("kitchen-tickets", TableSchema.fromBean(KitchenTicket.class));
    }

    public KitchenTicket createTicket(String orderId) {
        KitchenTicket ticket = new KitchenTicket();
        ticket.setTicketId(UUID.randomUUID().toString());
        ticket.setOrderId(orderId);
        ticket.setStatus(TicketStatus.PENDING.name());
        ticket.setCreatedAt(Instant.now().toString());
        ticketTable.putItem(ticket);
        return ticket;
    }

    public KitchenTicket getTicket(String ticketId) {
        KitchenTicket result = ticketTable.getItem(Key.builder().partitionValue(ticketId).build());
        if (result == null) throw new TicketNotFoundException(ticketId);
        return result;
    }
}
```

## Scan with Filter Expression

When filtering by a non-key attribute, use a scan with a filter expression.
**Always use expression attribute names (`#attr`) for field names that might clash with DynamoDB reserved words** (e.g., `status`, `name`, `orderId`).

```java
public List<ReviewDto> getByOrderId(String orderId) {
    ScanEnhancedRequest request = ScanEnhancedRequest.builder()
        .filterExpression(Expression.builder()
            .expression("#oi = :orderId")
            .expressionNames(Map.of("#oi", "orderId"))   // escape potential reserved word
            .expressionValues(Map.of(":orderId", AttributeValue.fromS(orderId)))
            .build())
        .build();

    // SdkIterable does NOT implement Collection — bridge to Stream:
    return StreamSupport.stream(reviewTable.scan(request).items().spliterator(), false)
            .map(this::toDto)
            .collect(Collectors.toList());
}
```

## Required Imports

```java
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;
import software.amazon.awssdk.enhanced.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
```

## application.yaml

```yaml
aws:
  region: ${AWS_REGION:us-east-1}
  dynamodb:
    endpoint: ${AWS_DYNAMODB_ENDPOINT:}   # empty = real AWS; set to http://localhost:4566 for LocalStack
```

## Anti-Patterns

| Anti-pattern | Fix |
|---|---|
| Using a Java record as a DynamoDB entity | Use a mutable JavaBean with `@DynamoDbBean` |
| `@DynamoDbPartitionKey` on a field | Must be on the getter method |
| Calling `.items()` on `SdkIterable` as a Collection | Use `StreamSupport.stream(...)` |
| Filtering by non-key attribute without `expressionNames` | Always alias with `#attr` to avoid reserved word collisions |
