package food.delivery.system.basket.service.record;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record AddItemRequestDto(
        @NotNull @Schema(description = "Product ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        UUID productId,

        @NotNull @Schema(description = "Restaurant ID", example = "b2c3d4e5-f6a7-8901-bcde-f12345678901")
        UUID restaurantId,

        @NotBlank @Schema(description = "Product name", example = "Margherita Pizza")
        String productName,

        @Min(1) @Schema(description = "Quantity", example = "2")
        int quantity,

        @NotNull @Positive @Schema(description = "Unit price", example = "12.50")
        BigDecimal price
) {}
