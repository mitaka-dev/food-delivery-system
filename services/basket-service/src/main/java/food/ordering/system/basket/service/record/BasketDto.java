package food.ordering.system.basket.service.record;

import java.math.BigDecimal;
import java.util.List;

public record BasketDto(String userId, List<BasketItemDto> items, BigDecimal totalAmount) {}
