package food.ordering.system.product.service.record;

import food.ordering.system.product.service.enums.ProductCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Product response")
public record ProductResponseDto(
        @Schema(description = "Product UUID") UUID id,
        @Schema(description = "Product name") String name,
        @Schema(description = "Product description") String description,
        @Schema(description = "Price in currency units") BigDecimal price,
        @Schema(description = "Product category") ProductCategory category,
        @Schema(description = "Available stock (uses optimistic locking)") int stock
) {}
