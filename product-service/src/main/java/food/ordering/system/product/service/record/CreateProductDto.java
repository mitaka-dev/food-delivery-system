package food.ordering.system.product.service.record;

import food.ordering.system.product.service.enums.ProductCategory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateProductDto(
        @NotBlank(message = "Product name is required") String name,
        String description,
        @NotNull(message = "Price is required") @Positive(message = "Price must be positive") BigDecimal price,
        @NotNull(message = "Category is required") ProductCategory category,
        @Min(value = 0, message = "Stock cannot be negative") int stock
) {}
