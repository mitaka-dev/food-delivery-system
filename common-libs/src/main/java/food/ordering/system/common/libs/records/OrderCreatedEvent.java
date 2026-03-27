package food.ordering.system.common.libs.records;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(
        UUID orderId,
        String username,
        BigDecimal totalAmount,
        List<OrderItem> items
) {}
