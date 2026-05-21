package food.ordering.system.user.service.controller;

import food.ordering.system.user.service.record.AuthResponse;
import food.ordering.system.user.service.record.LoginDto;
import food.ordering.system.user.service.record.RefreshTokenDto;
import food.ordering.system.user.service.service.JwtService;
import food.ordering.system.user.service.service.UserDetailsServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "Login, token refresh, and logout")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final UserDetailsServiceImpl userDetailsService;
    private final JwtService jwtService;

    public AuthController(AuthenticationManager authenticationManager,
                          UserDetailsServiceImpl userDetailsService,
                          JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtService = jwtService;
    }

    /**
     * Login — authenticate user and return access + refresh tokens.
     * Only ACTIVE users can log in (enforced in UserDetailsServiceImpl).
     */
    @Operation(summary = "Login", description = "Authenticate and receive access + refresh tokens. Only ACTIVE users can log in.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tokens issued"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials or user not ACTIVE")
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginDto dto) {
        log.info("Login attempt for username='{}'", dto.username());

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.username(), dto.password())
        );

        UserDetails userDetails = userDetailsService.loadUserByUsername(dto.username());
        String accessToken = jwtService.generateAccessToken(userDetails);
        String refreshToken = jwtService.generateRefreshToken(userDetails);

        log.info("Login successful for username='{}' — tokens issued, refresh token stored in Redis", dto.username());

        return ResponseEntity.ok(new AuthResponse(
                accessToken,
                refreshToken,
                jwtService.getAccessTokenExpiration()
        ));
    }

    /**
     * Refresh — exchange a valid refresh token for a new access token.
     * The refresh token is validated against Redis (must not be revoked).
     */
    @Operation(summary = "Refresh token", description = "Exchange a valid refresh token for a new access + refresh token pair. Validated against Redis.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New tokens issued"),
            @ApiResponse(responseCode = "401", description = "Refresh token invalid or revoked")
    })
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenDto dto) {
        String username = jwtService.extractUsername(dto.refreshToken());
        log.info("Token refresh request for username='{}'", username);

        if (!jwtService.isRefreshTokenValid(username, dto.refreshToken())) {
            log.warn("Token refresh denied for username='{}' — invalid or revoked refresh token", username);
            return ResponseEntity.status(401).build();
        }

        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        String newAccessToken = jwtService.generateAccessToken(userDetails);
        String newRefreshToken = jwtService.generateRefreshToken(userDetails);

        log.info("Token refreshed successfully for username='{}'", username);

        return ResponseEntity.ok(new AuthResponse(
                newAccessToken,
                newRefreshToken,
                jwtService.getAccessTokenExpiration()
        ));
    }

    /**
     * Logout — revoke the refresh token from Redis.
     */
    @Operation(summary = "Logout", description = "Revoke the refresh token from Redis, preventing future token refreshes.")
    @ApiResponse(responseCode = "204", description = "Logged out successfully")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenDto dto) {
        String username = jwtService.extractUsername(dto.refreshToken());
        jwtService.revokeRefreshToken(username);
        log.info("User '{}' logged out — refresh token revoked from Redis", username);
        return ResponseEntity.noContent().build();
    }
}
