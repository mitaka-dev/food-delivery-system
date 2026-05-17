---
name: spring-boot-service-conventions
description: How every Spring Boot service in this monorepo is structured. Use when creating or editing any service — controllers, DTOs, Kafka listeners, entities, or application config.
allowed-tools: Read, Edit, Write, Bash(./mvnw *)
---

# Spring Boot Service Conventions

This project runs **Java 25 + Spring Boot 4.0.6 + Spring Framework 7 + Servlet 6.1 (Jakarta EE 11)**.

## Package Layout

Every service follows this structure under `food.ordering.system.{service}.service`:

```
{service}-service/src/main/java/food/ordering/system/{service}/service/
├── {Service}Application.java       ← @SpringBootApplication main class
├── controller/                     ← @RestController + request/response records
├── service/                        ← Business logic, @Transactional methods
├── domain/                         ← @Entity classes + JpaRepository interfaces
├── record/                         ← DTOs (Java records — immutable, no-arg impossible)
├── listener/                       ← @KafkaListener beans
├── config/                         ← @Configuration classes (OpenApiConfig, etc.)
└── exception/                      ← Domain exceptions + GlobalExceptionHandler
```

## Controller Conventions

```java
@RestController
@RequestMapping("/api/v1/{resources}")   // kebab-case, plural, always /api/v1/ prefix
public class OrderController {

    private final OrderService orderService;

    // Constructor injection — NEVER @Autowired on fields
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderDto> create(
            @RequestHeader("X-User-Name") String username,   // gateway-injected identity
            @RequestBody @Valid CreateOrderRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(username, request));
    }
}
```

- DTOs are **Java records** (`public record CreateOrderRequestDto(@NotBlank String item) {}`)
- Use `ResponseEntity<T>` when status code varies; plain `T` for always-200 reads
- Identity comes from `X-User-Name` / `X-User-Role` headers injected upstream — no JWT parsing in downstream services
- `@Valid` on every `@RequestBody` — never skip it

## Service Layer

```java
@Service
public class OrderService {

    private final OrderRepository repository;
    private final KafkaTemplate<String, Object> kafka;

    public OrderService(OrderRepository repository, KafkaTemplate<String, Object> kafka) {
        this.repository = repository;
        this.kafka = kafka;
    }

    @Transactional
    public OrderDto create(String username, CreateOrderRequestDto request) {
        Order order = new Order();
        // ... populate
        repository.save(order);
        kafka.send(KafkaConstants.ORDER_TOPIC, new OrderCreatedEvent(...));
        return toDto(order);
    }
}
```

- All state-changing methods are `@Transactional`
- Kafka publish and DB save happen in the same method (see `/outbox-pattern` for reliability)

## Domain / Entity

```java
@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // No Lombok @Data — use explicit getters/setters or just field access
    // @Version for optimistic locking on contended tables (see product-service)
}
```

## Kafka Listener

```java
@Component
public class PaymentResultListener {
    @KafkaListener(topics = KafkaConstants.PAYMENT_TOPIC, groupId = KafkaConstants.ORDER_GROUP)
    public void onPaymentProcessed(PaymentProcessedEvent event) {
        // ...
    }
}
```

- All topic names and group IDs live in `common-libs` `KafkaConstants` — never inline strings

## application.yaml Template

```yaml
server:
  port: 808X

spring:
  application:
    name: {service}-service
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/{service}_db}
  jpa:
    hibernate:
      ddl-auto: validate        # always validate in production; use create-drop in tests
    show-sql: false
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:kafka:29092}
    consumer:
      group-id: {service}-group
      auto-offset-reset: earliest

management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  tracing:
    sampling:
      probability: 1.0
  zipkin:
    tracing:
      endpoint: http://tempo:9411/api/v2/spans
```

## Global Exception Handler Pattern

Copy from `product-service/src/main/java/food/ordering/system/product/service/exception/GlobalExceptionHandler.java`:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    public record ErrorResponse(String message) {}

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(NotFoundException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        return new ErrorResponse(ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .collect(Collectors.joining(", ")));
    }
}
```

## Anti-Patterns — Flag Immediately

| Anti-pattern | Fix |
|---|---|
| `System.out.println` | Use `LoggerFactory.getLogger(...)` |
| `@Autowired` on a field | Constructor injection |
| `new Date()` or `LocalDateTime.now()` for event timestamps | `Instant.now()` |
| Missing `@Valid` on `@RequestBody` | Add it |
| Endpoint path without `/api/v1/` prefix | Add prefix |
| Inline Kafka topic string (`"order-topics"`) | Use `KafkaConstants.ORDER_TOPIC` |
| `<version>` inside service `pom.xml` `<dependency>` | Remove — BOM manages versions |
| `ddl-auto: create` or `create-drop` in main `application.yaml` | Use `validate` |
