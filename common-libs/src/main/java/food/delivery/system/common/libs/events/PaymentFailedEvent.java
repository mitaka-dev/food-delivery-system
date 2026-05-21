package food.delivery.system.common.libs.events;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record PaymentFailedEvent(
        @JsonProperty("paymentId") UUID paymentId,
        @JsonProperty("orderId") UUID orderId,
        @JsonProperty("reason") String reason,
        @JsonProperty("schemaVersion") int schemaVersion
) {}
