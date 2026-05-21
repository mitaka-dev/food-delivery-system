package food.delivery.system.payment.service.service;

import food.delivery.system.common.libs.records.OrderCreatedEvent;
import food.delivery.system.common.libs.records.PaymentProcessedEvent;
import food.delivery.system.payment.service.entity.Payment;
import food.delivery.system.payment.service.enums.PaymentStatus;
import food.delivery.system.payment.service.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static food.delivery.system.common.libs.constants.KafkaConstants.*;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    public PaymentService(PaymentRepository paymentRepository,
                          KafkaTemplate<Object, Object> kafkaTemplate,
                          MeterRegistry meterRegistry) {
        this.paymentRepository = paymentRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
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

        // Deterministic failure: amounts above 500 fail — makes compensation easy to trigger
        boolean paymentFailed = event.totalAmount().compareTo(new BigDecimal("500")) > 0;

        if (paymentFailed) {
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            log.warn("Payment {} FAILED for order {} — amount {} exceeds threshold",
                    payment.getId(), event.orderId(), event.totalAmount());
        } else {
            payment.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.save(payment);
            log.info("Payment {} processed successfully for order {}", payment.getId(), event.orderId());
        }

        PaymentProcessedEvent processedEvent = new PaymentProcessedEvent(
                payment.getId(),
                event.orderId(),
                event.username(),
                event.totalAmount(),
                payment.getStatus().name(),
                event.items()
        );

        // Notify order-service — carries status so it can set PAID or FAILED
        kafkaTemplate.send(ORDER_CONFIRMATION_TOPIC, event.orderId().toString(), processedEvent);

        // Notify product-service (and any future consumers) — carries items for stock rollback
        kafkaTemplate.send(PAYMENT_TOPIC, event.orderId().toString(), processedEvent);

        meterRegistry.counter("payments.processed", "status", payment.getStatus().name()).increment();
        log.info("SAGA: Published PaymentProcessedEvent status={} for orderId={}", payment.getStatus().name(), event.orderId());
    }
}
