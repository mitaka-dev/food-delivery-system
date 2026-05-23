package food.delivery.system.user.service.service;

import food.delivery.system.common.libs.records.UserCreatedEvent;
import food.delivery.system.user.service.entity.User;
import food.delivery.system.user.service.enums.UserStatus;
import food.delivery.system.user.service.record.UserRegistrationDto;
import food.delivery.system.user.service.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.UUID;

import static food.delivery.system.common.libs.constants.KafkaConstants.*;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final BCryptPasswordEncoder passwordEncoder;
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    public UserService(UserRepository userRepository,
                       KafkaTemplate<Object, Object> kafkaTemplate,
                       BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void registerUser(UserRegistrationDto dto) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(dto.email());
        user.setPasswordHash(passwordEncoder.encode(dto.password()));
        user.setRole(dto.role());
        user.setStatus(UserStatus.PENDING);

        userRepository.save(user);

        UserCreatedEvent event = new UserCreatedEvent(user.getId(), user.getEmail(), user.getEmail(), user.getRole());

        log.info("Sending user creation event to Kafka: {}", user.getId());
        kafkaTemplate.send(USER_TOPIC, user.getId().toString(), event);
    }

    @KafkaListener(topics = USER_CONFIRMATION_TOPIC, groupId = USER_GROUP)
    public void confirmUser(String userId) {
        UUID id = UUID.fromString(userId);
        userRepository.findById(id).ifPresent(user -> {
            user.setStatus(UserStatus.ACTIVE);
            userRepository.save(user);
            log.info("SAGA DONE: User {} is ACTIVE!", id);
        });
    }
}