package food.delivery.system.common.libs.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import food.delivery.system.common.libs.enums.UserRole;

import java.util.UUID;

public record UserCreatedEvent(
        @JsonProperty("userId") UUID userId,
        @JsonProperty("email") String email,
        @JsonProperty("username") String username,
        @JsonProperty("role") UserRole role,
        @JsonProperty("schemaVersion") int schemaVersion
) {}
