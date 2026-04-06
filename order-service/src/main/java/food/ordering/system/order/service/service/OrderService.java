package food.ordering.system.order.service.service;

import food.ordering.system.common.libs.records.OrderCreatedEvent;
import food.ordering.system.common.libs.records.PaymentProcessedEvent;
import food.ordering.system.order.service.entity.Order;
import food.ordering.system.order.service.enums.OrderStatus;
import food.ordering.system.order.service.record.CreateOrderDto;
import food.ordering.system.order.service.record.OrderResponseDto;
import food.ordering.system.order.service.repository.OrderRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static food.ordering.system.common.libs.constants.KafkaConstants.*;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

    public OrderService(OrderRepository orderRepository,
                        KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate) {
        this.orderRepository = orderRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    public OrderResponseDto createOrder(CreateOrderDto dto, String username) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setUsername(username);
        order.setStatus(OrderStatus.PENDING);
        order.setItems(dto.items());
        order.setTotalAmount(dto.totalAmount());
        order.setCreatedAt(LocalDateTime.now());

        orderRepository.save(order);

        OrderCreatedEvent event = new OrderCreatedEvent(
                order.getId(),
                order.getUsername(),
                order.getTotalAmount(),
                order.getItems()
        );
        log.info("Sending order creation event to Kafka: orderId={}, items={}", order.getId(), dto.items().size());
        kafkaTemplate.send(ORDER_TOPIC, order.getId().toString(), event);

        return toDto(order);
    }

    public OrderResponseDto getOrder(UUID id, String username) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        if (!order.getUsername().equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        return toDto(order);
    }

    public List<OrderResponseDto> getOrdersForUser(String username) {
        return orderRepository.findByUsername(username).stream()
                .map(this::toDto)
                .toList();
    }

    @KafkaListener(topics = ORDER_CONFIRMATION_TOPIC, groupId = ORDER_GROUP)
    public void confirmOrder(PaymentProcessedEvent event) {
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            if ("SUCCESS".equals(event.status())) {
                order.setStatus(OrderStatus.PAID);
                orderRepository.save(order);
                log.info("SAGA DONE: Order {} is PAID", event.orderId());
            } else {
                order.setStatus(OrderStatus.FAILED);
                orderRepository.save(order);
                log.warn("SAGA COMPENSATION: Order {} FAILED — payment status={}", event.orderId(), event.status());
            }
        });
    }

    private OrderResponseDto toDto(Order order) {
        return new OrderResponseDto(
                order.getId(),
                order.getUsername(),
                order.getStatus(),
                order.getItems(),
                order.getTotalAmount(),
                order.getCreatedAt()
        );
    }
}