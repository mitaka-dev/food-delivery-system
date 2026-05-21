package food.delivery.system.common.libs.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        @JsonProperty("status") int status,
        @JsonProperty("error") String error,
        @JsonProperty("code") String code,
        @JsonProperty("message") String message,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("path") String path,
        @JsonProperty("traceId") String traceId,
        @JsonProperty("fieldErrors") List<FieldError> fieldErrors
) {}
