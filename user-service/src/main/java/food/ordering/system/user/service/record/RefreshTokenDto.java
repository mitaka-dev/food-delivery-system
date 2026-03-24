package food.ordering.system.user.service.record;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenDto(
        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {}
