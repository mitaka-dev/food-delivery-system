package food.ordering.system.delivery.service.record;

import food.ordering.system.delivery.service.enums.DeliveryStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record DeliveryDto(
        UUID id,
        UUID orderId,
        String username,
        DeliveryStatus status,
        String driverName,
        LocalDateTime createdAt
) {}
