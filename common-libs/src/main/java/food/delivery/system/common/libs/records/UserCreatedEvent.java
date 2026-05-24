package food.delivery.system.common.libs.records;

import food.delivery.system.common.libs.enums.UserRole;
import org.springframework.modulith.events.Externalized;

import java.util.UUID;

@Externalized("user-events")
public record UserCreatedEvent(
        UUID userId,
        String email,
        String username,
        UserRole role
) {}
