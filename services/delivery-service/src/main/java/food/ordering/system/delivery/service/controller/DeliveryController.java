package food.ordering.system.delivery.service.controller;

import food.ordering.system.delivery.service.record.DeliveryDto;
import food.ordering.system.delivery.service.record.UpdateDeliveryStatusRequestDto;
import food.ordering.system.delivery.service.service.DeliveryService;
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
@RequestMapping("/api/v1/delivery")
@Tag(name = "Delivery", description = "Delivery tracking — view status and update progress for drivers")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "Get delivery by order", description = "Returns the delivery record for a given order ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Delivery found"),
            @ApiResponse(responseCode = "404", description = "No delivery found for this order")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<DeliveryDto> getByOrderId(@PathVariable UUID orderId) {
        return ResponseEntity.ok(deliveryService.getByOrderId(orderId));
    }

    @PutMapping("/{deliveryId}/status")
    @Operation(summary = "Update delivery status", description = "Updates the status of a delivery. Use driverName when transitioning to ASSIGNED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "404", description = "Delivery not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<DeliveryDto> updateStatus(
            @PathVariable UUID deliveryId,
            @RequestBody @Valid UpdateDeliveryStatusRequestDto request) {
        return ResponseEntity.ok(deliveryService.updateStatus(deliveryId, request));
    }
}
