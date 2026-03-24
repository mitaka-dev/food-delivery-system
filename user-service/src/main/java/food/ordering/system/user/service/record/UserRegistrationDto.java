package food.ordering.system.user.service.record;

import food.ordering.system.common.libs.enums.UserRole;

public record UserRegistrationDto(
        String username,
        String password,
        String email,
        UserRole role
) {}
