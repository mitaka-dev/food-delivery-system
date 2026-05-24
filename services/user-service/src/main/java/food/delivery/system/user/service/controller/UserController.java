package food.delivery.system.user.service.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Profile endpoints (GET /me, PATCH /me) are added in Step 2.4.
@Tag(name = "Users", description = "User profile management")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {}
