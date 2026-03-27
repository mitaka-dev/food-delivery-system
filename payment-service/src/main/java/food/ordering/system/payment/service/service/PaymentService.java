package food.ordering.system.payment.service.service;

import food.ordering.system.common.libs.records.OrderCreatedEvent;
import food.ordering.system.common.libs.records.PaymentProcessedEvent;
import food.ordering.system.payment.service.entity.Payment;
import food.ordering.system.payment.service.enums.PaymentStatus;
import food.ordering.system.payment.service.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

import static food.ordering.system.common.libs.constants.KafkaConstants.*;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentService(PaymentRepository paymentRepository,
                          KafkaTemplate<String, Object> kafkaTemplate) {
        this.paymentRepository = paymentRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = ORDER_TOPIC, groupId = PAYMENT_GROUP)
    public void processPayment(OrderCreatedEvent event) {
        log.info("Received order event for payment processing: orderId={}, username={}, amount={}",
                event.orderId(), event.username(), event.totalAmount());

        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setOrderId(event.orderId());
        payment.setUsername(event.username());
        payment.setAmount(event.totalAmount());
        payment.setStatus(PaymentStatus.PENDING);
        payment.setCreatedAt(LocalDateTime.now());

        paymentRepository.save(payment);

        // Simulate payment processing — mark as SUCCESS
        payment.setStatus(PaymentStatus.SUCCESS);
        paymentRepository.save(payment);

        log.info("Payment {} processed successfully for order {}", payment.getId(), event.orderId());

        // Notify order-service to mark the order as PAID
        kafkaTemplate.send(ORDER_CONFIRMATION_TOPIC, event.orderId().toString(), event.orderId().toString());

        // Publish event to payment-topics for future consumers (e.g. product-service stock release)
        PaymentProcessedEvent processedEvent = new PaymentProcessedEvent(
                payment.getId(),
                event.orderId(),
                event.username(),
                event.totalAmount(),
                payment.getStatus().name()
        );
        kafkaTemplate.send(PAYMENT_TOPIC, event.orderId().toString(), processedEvent);

        log.info("SAGA: Order confirmation sent for orderId={}", event.orderId());
    }
}
