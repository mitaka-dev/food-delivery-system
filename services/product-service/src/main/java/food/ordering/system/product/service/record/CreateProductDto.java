package food.ordering.system.product.service.record;

import food.ordering.system.product.service.enums.ProductCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

@Schema(description = "Product creation request — requires ADMIN role")
public record CreateProductDto(
        @Schema(description = "Product name", example = "Classic Burger")
        @NotBlank(message = "Product name is required") String name,

        @Schema(description = "Product description", example = "Beef patty with lettuce and tomato")
        String description,

        @Schema(description = "Price in currency units", example = "12.99")
        @NotNull(message = "Price is required") @Positive(message = "Price must be positive") BigDecimal price,

        @Schema(description = "Product category")
        @NotNull(message = "Category is required") ProductCategory category,

        @Schema(description = "Initial stock quantity", example = "100")
        @Min(value = 0, message = "Stock cannot be negative") int stock
) {}
