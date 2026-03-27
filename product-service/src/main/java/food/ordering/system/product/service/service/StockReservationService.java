package food.ordering.system.product.service.service;

import food.ordering.system.common.libs.records.OrderCreatedEvent;
import food.ordering.system.common.libs.records.OrderItem;
import food.ordering.system.product.service.exception.InsufficientStockException;
import food.ordering.system.product.service.exception.ProductNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import static food.ordering.system.common.libs.constants.KafkaConstants.ORDER_TOPIC;

@Service
public class StockReservationService {

    private static final Logger log = LoggerFactory.getLogger(StockReservationService.class);

    private final ProductService productService;

    public StockReservationService(ProductService productService) {
        this.productService = productService;
    }

    @KafkaListener(topics = ORDER_TOPIC, groupId = "product-group")
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
                // TODO: publish compensation event when payment-service failure handling is wired
            }
        }

        log.info("Stock reservation complete for orderId={}", event.orderId());
    }
}
