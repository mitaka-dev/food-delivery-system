package food.delivery.system.review.service.controller;

import food.delivery.system.review.service.record.CreateReviewRequestDto;
import food.delivery.system.review.service.record.ReviewDto;
import food.delivery.system.review.service.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reviews")
@Tag(name = "Reviews", description = "Order review submission and retrieval")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    @Operation(summary = "Submit a review", description = "Submits a review for a delivered order.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Review created"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ReviewDto> createReview(
            @RequestHeader("X-User-Name") String username,
            @RequestBody @Valid CreateReviewRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.createReview(username, request));
    }

    @GetMapping("/{reviewId}")
    @Operation(summary = "Get review by ID", description = "Returns a review by its ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Review found"),
            @ApiResponse(responseCode = "404", description = "Review not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ReviewDto> getReview(@PathVariable String reviewId) {
        return ResponseEntity.ok(reviewService.getReview(reviewId));
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "Get reviews by order", description = "Returns all reviews for a given order ID.")
    @ApiResponse(responseCode = "200", description = "Reviews retrieved")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<List<ReviewDto>> getByOrderId(@PathVariable String orderId) {
        return ResponseEntity.ok(reviewService.getByOrderId(orderId));
    }
}
