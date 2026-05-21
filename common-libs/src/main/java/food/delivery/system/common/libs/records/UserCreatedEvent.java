package food.delivery.system.common.libs.records;

import food.delivery.system.common.libs.enums.UserRole;

import java.util.UUID;

public record UserCreatedEvent(
        UUID userId,
        String email,
        String username,
        UserRole role
) {}
