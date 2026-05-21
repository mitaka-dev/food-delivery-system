package food.delivery.system.delivery.service.record;

import food.delivery.system.delivery.service.enums.DeliveryStatus;

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
