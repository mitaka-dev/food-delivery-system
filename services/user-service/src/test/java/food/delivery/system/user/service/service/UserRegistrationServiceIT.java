package food.delivery.system.user.service.service;

import food.delivery.system.common.libs.enums.UserRole;
import food.delivery.system.user.service.entity.User;
import food.delivery.system.user.service.enums.UserStatus;
import food.delivery.system.user.service.exception.DuplicateEmailException;
import food.delivery.system.user.service.record.RegisterRequest;
import food.delivery.system.user.service.record.RegisterResponse;
import food.delivery.system.user.service.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class UserRegistrationServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("user_test_db")
            .withUsername("test")
            .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.9.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // Enable Flyway so V1–V5 migrations run against the test PostgreSQL container
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // Redis is never touched in this test — point to a closed port so any accidental
        // call fails fast rather than hanging.
        registry.add("spring.data.redis.port", () -> "6399");
        registry.add("jwt.secret", () -> "test-secret-key-for-integration-tests-only-xxxxxxxx");
    }

    @Autowired
    private UserRegistrationService registrationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("DELETE FROM event_publication");
        userRepository.deleteAll();
    }

    @Test
    void register_createsUserWithPendingStatusAndCustomerRole() {
        var request = new RegisterRequest("alice", "alice@example.com", "securepass123");

        RegisterResponse response = registrationService.register(request);

        assertThat(response.userId()).isNotNull();
        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.status()).isEqualTo("PENDING");

        User saved = userRepository.findById(response.userId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(saved.getRole()).isEqualTo(UserRole.USER);
        assertThat(saved.getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void register_writesOutboxRowThenCompletesDelivery() {
        var request = new RegisterRequest("bob", "bob@example.com", "securepass123");

        registrationService.register(request);

        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_publication",
                Integer.class);
        assertThat(total).as("total event_publication rows").isGreaterThan(0);

        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE event_type LIKE '%UserCreatedEvent%'",
                Integer.class);
        assertThat(pending).isEqualTo(1);

        // Spring Modulith publishes to Kafka asynchronously after commit; await completion.
        await().atMost(15, SECONDS).until(() -> {
            Integer completed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NOT NULL",
                    Integer.class);
            return completed != null && completed > 0;
        });
    }

    @Test
    void register_duplicateEmail_throwsDuplicateEmailException() {
        var first = new RegisterRequest("charlie", "charlie@example.com", "securepass123");
        registrationService.register(first);

        var duplicate = new RegisterRequest("charlie2", "charlie@example.com", "anotherpass456");
        assertThatThrownBy(() -> registrationService.register(duplicate))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessageContaining("charlie@example.com");
    }
}
