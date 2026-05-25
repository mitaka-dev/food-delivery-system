package food.delivery.system.user.service.record;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Size(min = 2, max = 100) String name,
        @Size(min = 2, max = 10) String locale,
        @Pattern(regexp = "^\\+?[0-9 ()\\-]{7,20}$", message = "Invalid phone number format") String phone
) {}
