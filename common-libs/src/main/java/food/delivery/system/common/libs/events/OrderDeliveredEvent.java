package food.delivery.system.common.libs.events;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record OrderDeliveredEvent(
        @JsonProperty("orderId") UUID orderId,
        @JsonProperty("deliveryId") UUID deliveryId,
        @JsonProperty("schemaVersion") int schemaVersion
) {}
