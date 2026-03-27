package food.ordering.system.order.service.record;

import food.ordering.system.common.libs.records.OrderItem;
import food.ordering.system.order.service.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponseDto(
        UUID id,
        String username,
        OrderStatus status,
        List<OrderItem> items,
        BigDecimal totalAmount,
        LocalDateTime createdAt
) {}
