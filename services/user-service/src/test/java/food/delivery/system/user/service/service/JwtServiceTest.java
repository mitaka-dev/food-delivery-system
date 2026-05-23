package food.delivery.system.user.service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class JwtServiceTest {

    @InjectMocks
    private JwtService jwtService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private static final String SECRET = "test-secret-key-for-unit-tests-only-xxxxxxxxxxxxxxxx";
    private static final long ACCESS_EXPIRY  = 900_000L;
    private static final long REFRESH_EXPIRY = 604_800_000L;

    private UserDetails testUser;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", ACCESS_EXPIRY);
        ReflectionTestUtils.setField(jwtService, "refreshTokenExpiration", REFRESH_EXPIRY);

        testUser = new User(
                "user@example.com",
                "hashed",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    @Test
    void generateAccessToken_embedsSubjectAndTypeAccess() {
        String token = jwtService.generateAccessToken(testUser);

        assertThat(jwtService.extractUsername(token)).isEqualTo("user@example.com");
        // Validate: access type means isAccessTokenValid returns true
        assertThat(jwtService.isAccessTokenValid(token, testUser)).isTrue();
    }

    @Test
    void generateRefreshToken_storesTokenInRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        jwtService.generateRefreshToken(testUser);

        verify(valueOps).set(startsWith("refresh_token:"), anyString(), anyLong(), any());
    }

    @Test
    void isAccessTokenValid_validToken_returnsTrue() {
        String token = jwtService.generateAccessToken(testUser);
        assertThat(jwtService.isAccessTokenValid(token, testUser)).isTrue();
    }

    @Test
    void isAccessTokenValid_wrongType_returnsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        String refreshToken = jwtService.generateRefreshToken(testUser);
        assertThat(jwtService.isAccessTokenValid(refreshToken, testUser)).isFalse();
    }

    @Test
    void isAccessTokenValid_expiredToken_returnsFalse() {
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", -1000L);
        String token = jwtService.generateAccessToken(testUser);
        assertThat(jwtService.isAccessTokenValid(token, testUser)).isFalse();
    }

    @Test
    void isRefreshTokenValid_tokenMatchesRedis_returnsTrue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        String token = jwtService.generateRefreshToken(testUser);
        when(valueOps.get("refresh_token:user@example.com")).thenReturn(token);

        assertThat(jwtService.isRefreshTokenValid("user@example.com", token)).isTrue();
    }

    @Test
    void isRefreshTokenValid_tokenNotInRedis_returnsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        String token = jwtService.generateRefreshToken(testUser);
        when(valueOps.get("refresh_token:user@example.com")).thenReturn(null);

        assertThat(jwtService.isRefreshTokenValid("user@example.com", token)).isFalse();
    }

    @Test
    void extractUsername_returnsSubject() {
        String token = jwtService.generateAccessToken(testUser);
        assertThat(jwtService.extractUsername(token)).isEqualTo("user@example.com");
    }

    @Test
    void revokeRefreshToken_deletesKeyFromRedis() {
        jwtService.revokeRefreshToken("user@example.com");
        verify(redisTemplate).delete("refresh_token:user@example.com");
    }
}
