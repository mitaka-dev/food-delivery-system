package food.delivery.system.user.service.record;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "User registration request")
public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 50)
        @Schema(description = "Display name", example = "alice") String username,

        @NotBlank @Email
        @Schema(description = "Email address", example = "alice@example.com") String email,

        @NotBlank @Size(min = 8)
        @Schema(description = "Password — minimum 8 characters (NIST 800-63B)", example = "s3cur3pass") String password
) {}
