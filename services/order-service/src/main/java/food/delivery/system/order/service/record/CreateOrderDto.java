package food.delivery.system.order.service.record;

import food.delivery.system.common.libs.records.OrderItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Order creation request")
public record CreateOrderDto(
        @Schema(description = "List of items in the order")
        @NotEmpty(message = "Order must contain at least one item") List<OrderItem> items,

        @Schema(description = "Total order amount. Payments over 500 are simulated as FAILED.", example = "49.99")
        @NotNull(message = "Total amount is required") @Positive(message = "Total amount must be positive") BigDecimal totalAmount
) {}
