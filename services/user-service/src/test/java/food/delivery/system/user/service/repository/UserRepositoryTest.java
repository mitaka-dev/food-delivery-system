package food.delivery.system.user.service.repository;

import food.delivery.system.common.libs.enums.UserRole;
import food.delivery.system.user.service.entity.User;
import food.delivery.system.user.service.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Spring Boot 4.0 removed @DataJpaTest (orm.jpa slice no longer exists).
// Using full context with webEnvironment=NONE; Flyway and Kafka suppressed in application-test.yaml.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void findByEmail_existingUser_returnsUser() {
        userRepository.save(buildUser("carol@example.com"));

        Optional<User> result = userRepository.findByEmail("carol@example.com");

        assertThat(result).isPresent();
        assertThat(result.get().getEmail()).isEqualTo("carol@example.com");
    }

    @Test
    void findByEmail_nonExistent_returnsEmpty() {
        Optional<User> result = userRepository.findByEmail("nobody@example.com");
        assertThat(result).isEmpty();
    }

    @Test
    void save_duplicateEmail_throwsConstraintViolation() {
        userRepository.saveAndFlush(buildUser("dup@example.com"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(buildUser("dup@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private User buildUser(String email) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(email.split("@")[0]);
        user.setEmail(email);
        user.setPasswordHash("hashed");
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.PENDING);
        return user;
    }
}
