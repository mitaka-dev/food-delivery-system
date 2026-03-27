package food.ordering.system.product.service.record;

import food.ordering.system.product.service.enums.ProductCategory;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponseDto(
        UUID id,
        String name,
        String description,
        BigDecimal price,
        ProductCategory category,
        int stock
) {}
