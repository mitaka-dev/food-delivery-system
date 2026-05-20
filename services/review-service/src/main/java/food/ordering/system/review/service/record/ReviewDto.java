package food.ordering.system.review.service.record;

public record ReviewDto(
        String reviewId,
        String orderId,
        String username,
        int rating,
        String comment,
        String createdAt
) {}
