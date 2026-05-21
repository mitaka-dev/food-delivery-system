package food.delivery.system.promotion.service.record;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePromotionRequestDto(
        @NotBlank @Size(max = 50) String code,
        @Min(1) @Max(100) int discountPercent
) {}
