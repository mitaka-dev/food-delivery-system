package food.delivery.system.kitchen.service.record;

import food.delivery.system.kitchen.service.enums.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateTicketStatusRequestDto(
        @NotNull
        @Schema(description = "New ticket status", example = "IN_PROGRESS")
        TicketStatus status
) {}
