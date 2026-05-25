package food.delivery.system.user.service.controller;

import food.delivery.system.user.service.exception.EmailAlreadyTakenException;
import food.delivery.system.user.service.record.AuthResponse;
import food.delivery.system.user.service.record.LoginDto;
import food.delivery.system.user.service.record.RefreshTokenDto;
import food.delivery.system.user.service.record.RegisterRequest;
import food.delivery.system.user.service.record.RegisterResponse;
import food.delivery.system.user.service.service.AuthenticationService;
import food.delivery.system.user.service.service.UserRegistrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @InjectMocks
    private AuthController controller;

    @Mock
    private UserRegistrationService registrationService;

    @Mock
    private AuthenticationService authService;

    private static final AuthResponse AUTH_RESPONSE =
            new AuthResponse("access-token", "refresh-token", 900_000L);

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returnsOkWithTokens() {
        when(authService.login(any())).thenReturn(AUTH_RESPONSE);

        ResponseEntity<AuthResponse> response = controller.login(new LoginDto("user@test.com", "pass"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(AUTH_RESPONSE);
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Test
    void refresh_validToken_returnsOkWithNewTokens() {
        when(authService.refresh("r-token")).thenReturn(AUTH_RESPONSE);

        ResponseEntity<AuthResponse> response = controller.refresh(new RefreshTokenDto("r-token"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(AUTH_RESPONSE);
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_invokesServiceAndReturns204() {
        ResponseEntity<Void> response = controller.logout(
                new RefreshTokenDto("some-token"), "Bearer access-token");

        verify(authService).logout(eq("some-token"), eq("Bearer access-token"));
        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Test
    void register_validRequest_returns202WithBody() {
        UUID id = UUID.randomUUID();
        when(registrationService.register(any())).thenReturn(
                new RegisterResponse(id, "alice", "PENDING", "Registration accepted — account is pending activation."));

        ResponseEntity<RegisterResponse> response = controller.register(
                new RegisterRequest("alice", "alice@example.com", "securepass"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().username()).isEqualTo("alice");
    }

    @Test
    void register_duplicateEmail_propagatesException() {
        when(registrationService.register(any())).thenThrow(new EmailAlreadyTakenException("alice@example.com"));

        assertThrows(EmailAlreadyTakenException.class,
                () -> controller.register(new RegisterRequest("alice", "alice@example.com", "securepass")));
    }
}
