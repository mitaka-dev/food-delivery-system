package food.delivery.system.order.service.controller;

import food.delivery.system.order.service.record.CreateOrderDto;
import food.delivery.system.order.service.record.OrderResponseDto;
import food.delivery.system.order.service.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Orders", description = "Order creation and retrieval")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(
            summary = "Create order",
            description = "Creates an order with status PENDING and triggers the Saga: " +
                          "product-service reserves stock, payment-service processes payment, " +
                          "then order is updated to PAID or FAILED."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order created, Saga initiated"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    })
    @PostMapping
    public ResponseEntity<OrderResponseDto> createOrder(
            @Parameter(hidden = true) @RequestHeader("X-User-Name") String username,
            @RequestBody @Valid CreateOrderDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(dto, username));
    }

    @Operation(summary = "Get order by ID", description = "Returns an order belonging to the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order found"),
            @ApiResponse(responseCode = "404", description = "Order not found or does not belong to user"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    })
    @GetMapping("/{id}")
    public ResponseEntity<OrderResponseDto> getOrder(
            @Parameter(hidden = true) @RequestHeader("X-User-Name") String username,
            @PathVariable UUID id) {
        return ResponseEntity.ok(orderService.getOrder(id, username));
    }

    @Operation(summary = "List my orders", description = "Returns all orders for the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order list returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    })
    @GetMapping
    public ResponseEntity<List<OrderResponseDto>> getOrders(
            @Parameter(hidden = true) @RequestHeader("X-User-Name") String username) {
        return ResponseEntity.ok(orderService.getOrdersForUser(username));
    }
}
