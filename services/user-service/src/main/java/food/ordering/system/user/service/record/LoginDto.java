package food.ordering.system.user.service.record;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Login credentials")
public record LoginDto(
        @Schema(example = "john_doe")
        @NotBlank(message = "Username is required")
        String username,

        @Schema(example = "secret123")
        @NotBlank(message = "Password is required")
        String password
) {}
