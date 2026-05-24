package food.delivery.system.user.service.record;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "User registration response")
public record RegisterResponse(
        @Schema(description = "Assigned user ID (UUID v7)") UUID userId,
        @Schema(description = "Username") String username,
        @Schema(description = "Initial account status") String status,
        @Schema(description = "Human-readable result message") String message
) {}
