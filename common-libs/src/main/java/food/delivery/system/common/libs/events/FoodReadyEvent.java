package food.delivery.system.common.libs.events;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record FoodReadyEvent(
        @JsonProperty("orderId") UUID orderId,
        @JsonProperty("kitchenTicketId") UUID kitchenTicketId,
        @JsonProperty("schemaVersion") int schemaVersion
) {}
