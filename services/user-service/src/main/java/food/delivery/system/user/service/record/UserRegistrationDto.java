package food.delivery.system.user.service.record;

import food.delivery.system.common.libs.enums.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "User registration request")
public record UserRegistrationDto(
        @Schema(description = "Unique username", example = "john_doe") String username,
        @Schema(description = "Password (will be BCrypt-hashed)", example = "secret123") String password,
        @Schema(description = "Email address", example = "john@example.com") String email,
        @Schema(description = "Role assigned to the user") UserRole role
) {}
