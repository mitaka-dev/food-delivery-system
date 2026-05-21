package food.delivery.system.product.service.exception;

import java.util.UUID;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(UUID productId, int requested, int available) {
        super("Insufficient stock for product " + productId +
              ": requested=" + requested + ", available=" + available);
    }
}
