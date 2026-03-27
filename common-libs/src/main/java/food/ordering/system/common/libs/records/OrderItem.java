package food.ordering.system.common.libs.records;

import java.util.UUID;

public record OrderItem(
        UUID productId,
        int quantity
) {}
