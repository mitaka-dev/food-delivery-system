package food.delivery.system.common.libs.events;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentSuccessEvent(
        @JsonProperty("paymentId") UUID paymentId,
        @JsonProperty("orderId") UUID orderId,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("schemaVersion") int schemaVersion
) {}
