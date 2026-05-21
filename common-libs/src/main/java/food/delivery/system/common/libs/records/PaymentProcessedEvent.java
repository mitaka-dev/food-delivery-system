package food.delivery.system.common.libs.records;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PaymentProcessedEvent(
        UUID paymentId,
        UUID orderId,
        String username,
        BigDecimal amount,
        String status,
        List<OrderItem> items
) {}
