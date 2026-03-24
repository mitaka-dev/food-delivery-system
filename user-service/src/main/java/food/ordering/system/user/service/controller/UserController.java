package food.ordering.system.user.service.controller;

import food.ordering.system.user.service.record.UserRegistrationDto;
import food.ordering.system.user.service.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<String> register(@RequestBody UserRegistrationDto dto) {
        log.info("Registration request received for username='{}', role={}", dto.username(), dto.role());
        userService.registerUser(dto);
        log.info("Registration accepted for username='{}' — status=PENDING, awaiting Saga confirmation", dto.username());
        return ResponseEntity.ok("User created and is being processed (PENDING)...");
    }
}