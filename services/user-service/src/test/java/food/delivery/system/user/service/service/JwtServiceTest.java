package food.delivery.system.user.service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private JwtService jwtService;
    private UserDetails testUser;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keyPair = gen.generateKeyPair();

        jwtService = new JwtService(keyPair, redisTemplate);
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", 900_000L);

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
        assertThat(jwtService.isAccessTokenValid(token, testUser)).isTrue();
    }

    @Test
    void generateAccessToken_embedsJti() {
        String token = jwtService.generateAccessToken(testUser);

        assertThat(jwtService.extractJti(token)).isNotBlank();
    }

    @Test
    void isAccessTokenValid_validToken_returnsTrue() {
        String token = jwtService.generateAccessToken(testUser);
        assertThat(jwtService.isAccessTokenValid(token, testUser)).isTrue();
    }

    @Test
    void isAccessTokenValid_expiredToken_returnsFalse() {
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", -1000L);
        String token = jwtService.generateAccessToken(testUser);
        assertThat(jwtService.isAccessTokenValid(token, testUser)).isFalse();
    }

    @Test
    void addJtiToDenylist_storesInRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        jwtService.addJtiToDenylist("some-jti", 60_000L);

        verify(valueOps).set(startsWith("jti_denylist:"), anyString(), anyLong(), any());
    }

    @Test
    void isJtiDenylisted_keyExists_returnsTrue() {
        when(redisTemplate.hasKey("jti_denylist:test-jti")).thenReturn(true);

        assertThat(jwtService.isJtiDenylisted("test-jti")).isTrue();
    }

    @Test
    void isJtiDenylisted_keyAbsent_returnsFalse() {
        when(redisTemplate.hasKey("jti_denylist:missing")).thenReturn(false);

        assertThat(jwtService.isJtiDenylisted("missing")).isFalse();
    }

    @Test
    void extractUsername_returnsSubject() {
        String token = jwtService.generateAccessToken(testUser);
        assertThat(jwtService.extractUsername(token)).isEqualTo("user@example.com");
    }
}
