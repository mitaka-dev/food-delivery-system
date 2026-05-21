package food.delivery.system.common.libs.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FieldError(
        @JsonProperty("field") String field,
        @JsonProperty("rejectedValue") Object rejectedValue,
        @JsonProperty("message") String message
) {}
