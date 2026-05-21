package food.delivery.system.common.libs.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record Money(
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("currency") String currency
) {}
