package food.delivery.system.user.service.controller;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Testcontainers
class UserControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("user_profile_test_db")
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    MockMvc mockMvc;

    private static final String EMAIL = "profile-it@example.com";
    private static final String PASSWORD = "Password1!";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setup() {
        Filter securityFilter = wac.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(securityFilter).build();

        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM event_publication");
        jdbcTemplate.execute("DELETE FROM users");

        jdbcTemplate.update("""
                INSERT INTO users (id, username, email, password_hash, role, status, locale)
                VALUES (?, 'profile_it_user', ?, ?, 'USER', 'ACTIVE', 'en')
                """, USER_ID, EMAIL, passwordEncoder.encode(PASSWORD));

        clearRedisKeys("jti_denylist:*");
    }

    // ── 1. GET /me — valid token returns profile ──────────────────────────────

    @Test
    void getMe_withValidToken_returns200AndProfile() throws Exception {
        String accessToken = doLogin();

        String body = mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.username").value("profile_it_user"))
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(body);
        assertThat(node.get("id").asText()).isEqualTo(USER_ID.toString());
    }

    // ── 2. GET /me — no token returns 401 with WWW-Authenticate ──────────────

    @Test
    void getMe_noToken_returns401WithWwwAuthenticate() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
    }

    // ── 3. GET /me — tampered token returns 401 ───────────────────────────────

    @Test
    void getMe_tamperedToken_returns401() throws Exception {
        String validToken = doLogin();
        // Corrupt the signature segment
        String tampered = validToken.substring(0, validToken.lastIndexOf('.') + 1) + "invalidsig";

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    // ── 4. GET /me — logged-out token (jti in denylist) returns 401 ──────────

    @Test
    void getMe_afterLogout_returns401() throws Exception {
        String accessToken = doLogin();
        String refreshToken = doLoginForRefresh();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    // ── 5. PATCH /me — partial update persists changed fields ─────────────────

    @Test
    void patchMe_partialUpdate_persistsChangedFields() throws Exception {
        String accessToken = doLogin();

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\",\"locale\":\"fr\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("New Name"))
                .andExpect(jsonPath("$.locale").value("fr"))
                .andExpect(jsonPath("$.email").value(EMAIL));
    }

    // ── 6. PATCH /me — phone update stored ────────────────────────────────────

    @Test
    void patchMe_phoneUpdate_isStored() throws Exception {
        String accessToken = doLogin();

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"+1 555 123 4567\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+1 555 123 4567"));
    }

    // ── 7. PATCH /me — invalid phone format returns 400 ──────────────────────

    @Test
    void patchMe_invalidPhone_returns400() throws Exception {
        String accessToken = doLogin();

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"not-a-phone!\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── 8. PATCH /me — omitted fields remain unchanged ───────────────────────

    @Test
    void patchMe_omittedFields_unchanged() throws Exception {
        String accessToken = doLogin();

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"de\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("de"))
                .andExpect(jsonPath("$.username").value("profile_it_user"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String doLogin() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private String doLoginForRefresh() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("refreshToken").asText();
    }

    private void clearRedisKeys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
