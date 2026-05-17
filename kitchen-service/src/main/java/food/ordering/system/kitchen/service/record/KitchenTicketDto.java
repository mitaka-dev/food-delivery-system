package food.ordering.system.kitchen.service.record;

import food.ordering.system.kitchen.service.enums.TicketStatus;

public record KitchenTicketDto(
        String ticketId,
        String orderId,
        TicketStatus status,
        String createdAt
) {}
