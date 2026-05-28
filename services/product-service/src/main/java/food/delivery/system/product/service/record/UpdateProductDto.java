package food.delivery.system.product.service.record;

import food.delivery.system.product.service.enums.ProductCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

@Schema(description = "Partial product update — null fields are left unchanged")
public record UpdateProductDto(
        @Schema(description = "Product name") String name,
        @Schema(description = "Product description") String description,
        @Schema(description = "Price in currency units") @Positive BigDecimal price,
        @Schema(description = "Product category") ProductCategory category,
        @Schema(description = "Available stock") @Min(0) Integer stock
) {}
