package food.ordering.system.review.service.record;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateReviewRequestDto(
        @NotBlank String orderId,
        @Min(1) @Max(5) int rating,
        String comment
) {}
