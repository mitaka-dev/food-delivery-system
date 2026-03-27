package food.ordering.system.order.service.record;

import food.ordering.system.common.libs.records.OrderItem;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

public record CreateOrderDto(
        @NotEmpty(message = "Order must contain at least one item") List<OrderItem> items,
        @NotNull(message = "Total amount is required") @Positive(message = "Total amount must be positive") BigDecimal totalAmount
) {}
