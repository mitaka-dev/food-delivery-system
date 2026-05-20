package food.ordering.system.order.service.record;

import food.ordering.system.common.libs.records.OrderItem;
import food.ordering.system.order.service.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Order response")
public record OrderResponseDto(
        @Schema(description = "Order UUID") UUID id,
        @Schema(description = "Username of the order owner") String username,
        @Schema(description = "Order status: PENDING → PAID or FAILED") OrderStatus status,
        @Schema(description = "Items in the order") List<OrderItem> items,
        @Schema(description = "Total order amount") BigDecimal totalAmount,
        @Schema(description = "Order creation timestamp") LocalDateTime createdAt
) {}
