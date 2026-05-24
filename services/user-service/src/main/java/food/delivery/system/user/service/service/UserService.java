package food.delivery.system.user.service.service;

import food.delivery.system.user.service.enums.UserStatus;
import food.delivery.system.user.service.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static food.delivery.system.common.libs.constants.KafkaConstants.USER_CONFIRMATION_TOPIC;
import static food.delivery.system.common.libs.constants.KafkaConstants.USER_GROUP;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
