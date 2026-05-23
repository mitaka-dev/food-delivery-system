package food.delivery.system.user.service.record;

import food.delivery.system.common.libs.enums.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "User registration request")
public record UserRegistrationDto(
        @Schema(description = "Email address", example = "john@example.com") String email,
        @Schema(description = "Password (will be hashed)", example = "secret123") String password,
        @Schema(description = "Role assigned to the user") UserRole role
) {}
