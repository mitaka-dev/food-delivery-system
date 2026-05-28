package food.delivery.system.basket.service.record;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BasketDto(
        String userId,
        UUID restaurantId,
        List<BasketItemDto> items,
        BigDecimal totalAmount,
        Instant lastModified
) {}
