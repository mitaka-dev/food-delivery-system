package food.delivery.system.user.service.controller;

import food.delivery.system.user.service.record.UserRegistrationDto;
import food.delivery.system.user.service.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Users", description = "User registration")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(
            summary = "Register user",
            description = "Creates a user with status PENDING and publishes a UserCreatedEvent to Kafka. " +
                          "The user becomes ACTIVE after analytics-service confirms via the Saga."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registration accepted, user is PENDING"),
            @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    @PostMapping
    public ResponseEntity<String> register(@RequestBody UserRegistrationDto dto) {
        log.info("Registration request received for username='{}', role={}", dto.username(), dto.role());
        userService.registerUser(dto);
        log.info("Registration accepted for username='{}' — status=PENDING, awaiting Saga confirmation", dto.username());
        return ResponseEntity.ok("User created and is being processed (PENDING)...");
    }
}
