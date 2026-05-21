package food.ordering.system.basket.service.record;

import java.math.BigDecimal;
import java.util.UUID;

public record BasketItemDto(UUID productId, String productName, int quantity, BigDecimal price) {}
