package food.ordering.system.order.service.controller;

import food.ordering.system.order.service.record.CreateOrderDto;
import food.ordering.system.order.service.record.OrderResponseDto;
import food.ordering.system.order.service.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponseDto> createOrder(
            @RequestHeader("X-User-Name") String username,
            @RequestBody @Valid CreateOrderDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(dto, username));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponseDto> getOrder(
            @RequestHeader("X-User-Name") String username,
            @PathVariable UUID id) {
        return ResponseEntity.ok(orderService.getOrder(id, username));
    }

    @GetMapping
    public ResponseEntity<List<OrderResponseDto>> getOrders(
            @RequestHeader("X-User-Name") String username) {
        return ResponseEntity.ok(orderService.getOrdersForUser(username));
    }
}