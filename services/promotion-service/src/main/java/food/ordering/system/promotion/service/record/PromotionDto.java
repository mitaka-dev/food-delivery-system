package food.ordering.system.promotion.service.record;

import java.time.LocalDateTime;
import java.util.UUID;

public record PromotionDto(
        UUID id,
        String code,
        int discountPercent,
        boolean active,
        LocalDateTime createdAt
) {}
