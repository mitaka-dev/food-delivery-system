package food.ordering.system.product.service.service;

import food.ordering.system.common.libs.records.OrderCreatedEvent;
import food.ordering.system.common.libs.records.OrderItem;
import food.ordering.system.common.libs.records.PaymentProcessedEvent;
import food.ordering.system.product.service.exception.InsufficientStockException;
import food.ordering.system.product.service.exception.ProductNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import static food.ordering.system.common.libs.constants.KafkaConstants.ORDER_TOPIC;
import static food.ordering.system.common.libs.constants.KafkaConstants.PAYMENT_TOPIC;
import static food.ordering.system.common.libs.constants.KafkaConstants.PRODUCT_GROUP;

@Service
public class StockReservationService {

    private static final Logger log = LoggerFactory.getLogger(StockReservationService.class);

    private final ProductService productService;

    public StockReservationService(ProductService productService) {
        this.productService = productService;
    }

    @KafkaListener(topics = ORDER_TOPIC, groupId = PRODUCT_GROUP)
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("Reserving stock for orderId={}, items={}", event.orderId(), event.items().size());

        for (OrderItem item : event.items()) {
            try {
                productService.reserveStock(item.productId(), item.quantity());
            } catch (ProductNotFoundException ex) {
                log.warn("Stock reservation skipped — product not found: productId={}, orderId={}",
                        item.productId(), event.orderId());
            } catch (InsufficientStockException ex) {
                log.warn("Stock reservation failed — insufficient stock: productId={}, orderId={}, reason={}",
                        item.productId(), event.orderId(), ex.getMessage());
            }
        }

        log.info("Stock reservation complete for orderId={}", event.orderId());
    }

    @KafkaListener(topics = PAYMENT_TOPIC, groupId = PRODUCT_GROUP)
    public void handlePaymentProcessed(PaymentProcessedEvent event) {
        if (!"FAILED".equals(event.status())) {
            return;
        }

        log.warn("SAGA COMPENSATION: Releasing stock for failed payment, orderId={}", event.orderId());

        if (event.items() == null || event.items().isEmpty()) {
            log.warn("SAGA COMPENSATION: No items in event for orderId={} — skipping stock release", event.orderId());
            return;
        }

        for (OrderItem item : event.items()) {
            try {
                productService.releaseStock(item.productId(), item.quantity());
            } catch (ProductNotFoundException ex) {
                log.error("SAGA COMPENSATION FAILED: product not found during stock release: productId={}, orderId={}",
                        item.productId(), event.orderId());
            }
        }

        log.warn("SAGA COMPENSATION DONE: Stock released for orderId={}", event.orderId());
    }
}
