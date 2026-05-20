package food.ordering.system.promotion.service.controller;

import food.ordering.system.promotion.service.record.CreatePromotionRequestDto;
import food.ordering.system.promotion.service.record.PromotionDto;
import food.ordering.system.promotion.service.service.PromotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/promotions")
@Tag(name = "Promotions", description = "Promo code management")
public class PromotionController {

    private final PromotionService promotionService;

    public PromotionController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @PostMapping
    @Operation(summary = "Create a promo code", description = "Creates a new promotion. Requires ADMIN role.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Promotion created"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "403", description = "Forbidden — ADMIN role required")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<PromotionDto> create(
            @RequestHeader("X-User-Role") String role,
            @RequestBody @Valid CreatePromotionRequestDto request) {
        if (!"ADMIN".equalsIgnoreCase(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(promotionService.create(request));
    }

    @GetMapping("/{code}")
    @Operation(summary = "Look up a promo code", description = "Returns promo details for the given code.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Promotion found"),
            @ApiResponse(responseCode = "404", description = "Promotion not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<PromotionDto> getByCode(@PathVariable String code) {
        return ResponseEntity.ok(promotionService.getByCode(code));
    }
}
