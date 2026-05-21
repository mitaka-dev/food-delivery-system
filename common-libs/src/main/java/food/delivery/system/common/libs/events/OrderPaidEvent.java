package food.delivery.system.common.libs.events;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderPaidEvent(
        @JsonProperty("orderId") UUID orderId,
        @JsonProperty("userId") UUID userId,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("schemaVersion") int schemaVersion
) {}
