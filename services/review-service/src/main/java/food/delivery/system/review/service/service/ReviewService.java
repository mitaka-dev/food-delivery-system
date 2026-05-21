package food.delivery.system.review.service.service;

import food.delivery.system.review.service.domain.Review;
import food.delivery.system.review.service.exception.ReviewNotFoundException;
import food.delivery.system.review.service.record.CreateReviewRequestDto;
import food.delivery.system.review.service.record.ReviewDto;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class ReviewService {

    private final DynamoDbTable<Review> reviewTable;

    public ReviewService(DynamoDbEnhancedClient enhancedClient) {
        this.reviewTable = enhancedClient.table("reviews", TableSchema.fromBean(Review.class));
    }

    public ReviewDto createReview(String username, CreateReviewRequestDto request) {
        Review review = new Review();
        review.setReviewId(UUID.randomUUID().toString());
        review.setOrderId(request.orderId());
        review.setUsername(username);
        review.setRating(request.rating());
        review.setComment(request.comment());
        review.setCreatedAt(Instant.now().toString());
        reviewTable.putItem(review);
        return toDto(review);
    }

    public ReviewDto getReview(String reviewId) {
        Review key = new Review();
        key.setReviewId(reviewId);
        Review review = reviewTable.getItem(key);
        if (review == null) {
            throw new ReviewNotFoundException("Review not found: " + reviewId);
        }
        return toDto(review);
    }

    public List<ReviewDto> getByOrderId(String orderId) {
        ScanEnhancedRequest request = ScanEnhancedRequest.builder()
                .filterExpression(Expression.builder()
                        .expression("#oi = :orderId")
                        .expressionNames(Map.of("#oi", "orderId"))
                        .expressionValues(Map.of(":orderId", AttributeValue.fromS(orderId)))
                        .build())
                .build();
        return StreamSupport.stream(reviewTable.scan(request).items().spliterator(), false)
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private ReviewDto toDto(Review review) {
        return new ReviewDto(
                review.getReviewId(),
                review.getOrderId(),
                review.getUsername(),
                review.getRating(),
                review.getComment(),
                review.getCreatedAt()
        );
    }
}
