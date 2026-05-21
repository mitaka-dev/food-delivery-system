package food.delivery.system.delivery.service.record;

import food.delivery.system.delivery.service.enums.DeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateDeliveryStatusRequestDto(
        @NotNull
        @Schema(description = "New delivery status", example = "IN_TRANSIT")
        DeliveryStatus status,

        @Schema(description = "Driver name (required when status is ASSIGNED)", example = "John Smith")
        String driverName
) {}
