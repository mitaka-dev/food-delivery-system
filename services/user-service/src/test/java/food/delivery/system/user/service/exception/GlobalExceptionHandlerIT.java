package food.delivery.system.user.service.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import food.delivery.system.user.service.service.UserProfileService;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Testcontainers
class GlobalExceptionHandlerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("user_handler_test_db")
            .withUsername("test")
            .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.9.0"));

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired StringRedisTemplate redisTemplate;
    @MockitoSpyBean UserProfileService profileService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    MockMvc mockMvc;

    private static final String EMAIL = "handler-it@example.com";
    private static final String PASSWORD = "Password1!";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @BeforeEach
    void setup() {
        Filter securityFilter = wac.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(securityFilter).build();

        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM event_publication");
        jdbcTemplate.execute("DELETE FROM users");

        jdbcTemplate.update("""
                INSERT INTO users (id, username, email, password_hash, role, status, locale)
                VALUES (?, 'handler_user', ?, ?, 'USER', 'ACTIVE', 'en')
                """, USER_ID, EMAIL, passwordEncoder.encode(PASSWORD));

        clearRedisKeys("login_attempts:*");
        clearRedisKeys("jti_denylist:*");
    }

    // ── §5: Error handling — ApiError shape ──────────────────────────────────

    @Test
    void register_emailAlreadyTaken_returns409WithApiErrorBody() throws Exception {
        // Register once (real service → email now taken)
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(EMAIL, PASSWORD)));

        // Second registration with same email → 409 + ApiError
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(EMAIL, PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("AUTH_EMAIL_TAKEN"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/v1/auth/register"));
    }

    @Test
    void login_badCredentials_returns401WithApiErrorBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(EMAIL, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid credentials"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void register_validationFailure_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"email\":\"not-an-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
    }

    @Test
    void login_accountLocked_returns429WithRetryAfterHeader() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson(EMAIL, "wrong")));
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(EMAIL, PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void refresh_invalidToken_returns401WithApiErrorBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"ffffffff-ffff-ffff-ffff-ffffffffffff\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void getMe_userNotFound_returns404WithApiErrorBody() throws Exception {
        String accessToken = doLogin();

        doThrow(new UserNotFoundException(EMAIL)).when(profileService).getProfile(any());

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void getMe_unexpectedException_returns500WithRedactedMessage() throws Exception {
        String accessToken = doLogin();

        doThrow(new RuntimeException("internal-db-secret")).when(profileService).getProfile(any());

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                // Stack trace must not leak — only the generic message
                .andExpect(jsonPath("$.message").value("An internal error occurred"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String doLogin() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private void clearRedisKeys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private static String loginJson(String email, String password) {
        return "{\"username\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String registerJson(String email, String password) {
        return "{\"username\":\"newuser\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
