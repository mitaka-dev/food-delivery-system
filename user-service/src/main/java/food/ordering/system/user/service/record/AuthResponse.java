package food.ordering.system.user.service.record;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "JWT authentication response")
public record AuthResponse(
        @Schema(description = "Short-lived access token (15 min)") String accessToken,
        @Schema(description = "Long-lived refresh token (7 days), stored in Redis") String refreshToken,
        @Schema(description = "Token type, always 'Bearer'", example = "Bearer") String tokenType,
        @Schema(description = "Access token TTL in milliseconds") long expiresIn
) {
    public AuthResponse(String accessToken, String refreshToken, long expiresIn) {
        this(accessToken, refreshToken, "Bearer", expiresIn);
    }
}
