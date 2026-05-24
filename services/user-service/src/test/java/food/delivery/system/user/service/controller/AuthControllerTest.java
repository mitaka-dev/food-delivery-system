package food.delivery.system.user.service.controller;

import food.delivery.system.user.service.exception.DuplicateEmailException;
import food.delivery.system.user.service.record.AuthResponse;
import food.delivery.system.user.service.record.LoginDto;
import food.delivery.system.user.service.record.RefreshTokenDto;
import food.delivery.system.user.service.record.RegisterRequest;
import food.delivery.system.user.service.record.RegisterResponse;
import food.delivery.system.user.service.service.JwtService;
import food.delivery.system.user.service.service.UserDetailsServiceImpl;
import food.delivery.system.user.service.service.UserRegistrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AuthControllerTest {

    @InjectMocks
    private AuthController controller;

    @Mock
    private UserRegistrationService registrationService;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserDetailsServiceImpl userDetailsService;

    @Mock
    private JwtService jwtService;

    private static final UserDetails USER_DETAILS = new User(
            "user@test.com", "hashed",
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
    );

    @Test
    void login_validCredentials_returnsOkWithTokens() {
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(USER_DETAILS);
        when(jwtService.generateAccessToken(any())).thenReturn("acc");
        when(jwtService.generateRefreshToken(any())).thenReturn("ref");
        when(jwtService.getAccessTokenExpiration()).thenReturn(900_000L);

        ResponseEntity<AuthResponse> response = controller.login(new LoginDto("user@test.com", "pass"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isEqualTo("acc");
        assertThat(response.getBody().refreshToken()).isEqualTo("ref");
        assertThat(response.getBody().expiresIn()).isEqualTo(900_000L);
    }

    @Test
    void login_badCredentials_propagatesException() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThrows(BadCredentialsException.class,
                () -> controller.login(new LoginDto("user@test.com", "wrong")));
    }

    @Test
    void refresh_validToken_returnsOkWithNewTokens() {
        when(jwtService.extractUsername("r-token")).thenReturn("user@test.com");
        when(jwtService.isRefreshTokenValid("user@test.com", "r-token")).thenReturn(true);
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(USER_DETAILS);
        when(jwtService.generateAccessToken(any())).thenReturn("new-acc");
        when(jwtService.generateRefreshToken(any())).thenReturn("new-ref");
        when(jwtService.getAccessTokenExpiration()).thenReturn(900_000L);

        ResponseEntity<AuthResponse> response = controller.refresh(new RefreshTokenDto("r-token"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().accessToken()).isEqualTo("new-acc");
    }

    @Test
    void refresh_invalidToken_returns401() {
        when(jwtService.extractUsername("bad-token")).thenReturn("user@test.com");
        when(jwtService.isRefreshTokenValid("user@test.com", "bad-token")).thenReturn(false);

        ResponseEntity<AuthResponse> response = controller.refresh(new RefreshTokenDto("bad-token"));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void logout_revokesRefreshTokenAndReturns204() {
        when(jwtService.extractUsername("some-token")).thenReturn("user@test.com");

        ResponseEntity<Void> response = controller.logout(new RefreshTokenDto("some-token"));

        verify(jwtService).revokeRefreshToken("user@test.com");
        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }

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
        assertThat(response.getBody().status()).isEqualTo("PENDING");
    }

    @Test
    void register_duplicateEmail_propagatesException() {
        when(registrationService.register(any())).thenThrow(new DuplicateEmailException("alice@example.com"));

        assertThrows(DuplicateEmailException.class,
                () -> controller.register(new RegisterRequest("alice", "alice@example.com", "securepass")));
    }
}
