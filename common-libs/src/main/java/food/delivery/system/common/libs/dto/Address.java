package food.delivery.system.common.libs.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Address(
        @JsonProperty("street") String street,
        @JsonProperty("city") String city,
        @JsonProperty("postcode") String postcode,
        @JsonProperty("country") String country
) {}
