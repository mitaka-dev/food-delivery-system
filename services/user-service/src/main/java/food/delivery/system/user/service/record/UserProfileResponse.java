package food.delivery.system.user.service.record;

import food.delivery.system.common.libs.enums.UserRole;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String email,
        String username,
        String locale,
        String phone,
        UserRole role,
        OffsetDateTime createdAt
) {}
