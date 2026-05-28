package food.delivery.system.basket.service.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record BasketItem(UUID productId, String productName, int quantity, BigDecimal price) {}
