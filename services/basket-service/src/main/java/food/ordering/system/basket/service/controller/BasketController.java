package food.ordering.system.basket.service.controller;

import food.ordering.system.basket.service.record.AddItemRequestDto;
import food.ordering.system.basket.service.record.BasketDto;
import food.ordering.system.basket.service.service.BasketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/basket")
@Tag(name = "Basket", description = "Cart management — add, remove, and clear items; cart persists in Redis for 24 hours")
public class BasketController {

    private final BasketService basketService;

    public BasketController(BasketService basketService) {
        this.basketService = basketService;
    }

    @GetMapping
    @Operation(summary = "Get basket", description = "Returns the current user's basket. Returns an empty basket if none exists.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Basket retrieved")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<BasketDto> getBasket(@RequestHeader("X-User-Name") String userName) {
        return ResponseEntity.ok(basketService.getBasket(userName));
    }

    @PostMapping("/items")
    @Operation(summary = "Add item to basket", description = "Adds or replaces an item in the basket. If the same productId exists, it is overwritten.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Item added; updated basket returned"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<BasketDto> addItem(
            @RequestHeader("X-User-Name") String userName,
            @RequestBody @Valid AddItemRequestDto request) {
        return ResponseEntity.ok(basketService.addItem(userName, request));
    }

    @DeleteMapping("/items/{productId}")
    @Operation(summary = "Remove item from basket", description = "Removes a specific item by productId. Returns 404 if the item is not in the basket.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Item removed; updated basket returned"),
            @ApiResponse(responseCode = "404", description = "Item not found in basket")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<BasketDto> removeItem(
            @RequestHeader("X-User-Name") String userName,
            @PathVariable UUID productId) {
        return ResponseEntity.ok(basketService.removeItem(userName, productId));
    }

    @DeleteMapping
    @Operation(summary = "Clear basket", description = "Removes all items from the basket.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Basket cleared")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> clearBasket(@RequestHeader("X-User-Name") String userName) {
        basketService.clearBasket(userName);
        return ResponseEntity.noContent().build();
    }
}
