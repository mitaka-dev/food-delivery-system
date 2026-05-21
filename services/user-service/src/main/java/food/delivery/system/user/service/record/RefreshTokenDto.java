package food.delivery.system.user.service.record;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Refresh token request")
public record RefreshTokenDto(
        @Schema(description = "The refresh token received at login or last refresh")
        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {}
