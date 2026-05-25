package food.delivery.system.user.service.controller;

import food.delivery.system.common.libs.api.ApiError;
import food.delivery.system.user.service.exception.AccountLockedException;
import food.delivery.system.user.service.exception.InvalidTokenException;
import food.delivery.system.user.service.record.AuthResponse;
import food.delivery.system.user.service.record.LoginDto;
import food.delivery.system.user.service.record.RefreshTokenDto;
import food.delivery.system.user.service.record.RegisterRequest;
import food.delivery.system.user.service.record.RegisterResponse;
import food.delivery.system.user.service.service.AuthenticationService;
import food.delivery.system.user.service.service.UserRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@Tag(name = "Authentication", description = "Registration, login, token refresh, and logout")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRegistrationService registrationService;
    private final AuthenticationService authService;

    public AuthController(UserRegistrationService registrationService,
                          AuthenticationService authService) {
        this.registrationService = registrationService;
        this.authService = authService;
    }

    @Operation(summary = "Register", description = "Create a CUSTOMER account. Returns 202 Accepted — the account is PENDING until the USER_CREATED event flow completes.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Registration accepted, account is PENDING"),
            @ApiResponse(responseCode = "400", description = "Validation error — field-level details in body"),
            @ApiResponse(responseCode = "409", description = "Email already registered")
    })
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Registration request for email='{}'", request.email());
        RegisterResponse response = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @Operation(summary = "Login", description = "Authenticate and receive access + refresh tokens. Only ACTIVE users can log in.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tokens issued"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials or user not ACTIVE"),
            @ApiResponse(responseCode = "429", description = "Account locked — too many failed attempts")
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginDto dto) {
        log.info("Login attempt for email='{}'", dto.username());
        AuthResponse response = authService.login(dto);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Refresh tokens", description = "Exchange a valid refresh token for a new access + refresh token pair.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New tokens issued"),
            @ApiResponse(responseCode = "401", description = "Refresh token invalid, expired, or revoked")
    })
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenDto dto) {
        log.info("Token refresh request");
        AuthResponse response = authService.refresh(dto.refreshToken());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Logout", description = "Revoke the refresh token and add the access token jti to the denylist.")
    @ApiResponse(responseCode = "204", description = "Logged out successfully")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Valid @RequestBody RefreshTokenDto dto,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        authService.logout(dto.refreshToken(), authHeader);
        log.info("Logout completed");
        return ResponseEntity.noContent().build();
    }

    // ── Exception handlers ────────────────────────────────────────────────────

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiError> handleLockout(AccountLockedException ex, HttpServletRequest req) {
        log.warn("Login blocked — account locked: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(new ApiError(
                        HttpStatus.TOO_MANY_REQUESTS.value(),
                        "Too Many Requests",
                        "ACCOUNT_LOCKED",
                        ex.getMessage(),
                        Instant.now(),
                        req.getRequestURI(),
                        null,
                        null
                ));
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ApiError> handleInvalidToken(InvalidTokenException ex, HttpServletRequest req) {
        log.warn("Token operation rejected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError(
                        HttpStatus.UNAUTHORIZED.value(),
                        "Unauthorized",
                        "INVALID_TOKEN",
                        ex.getMessage(),
                        Instant.now(),
                        req.getRequestURI(),
                        null,
                        null
                ));
    }
}
