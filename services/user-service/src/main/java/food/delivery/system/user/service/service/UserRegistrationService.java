package food.delivery.system.user.service.service;

import food.delivery.system.common.libs.enums.UserRole;
import food.delivery.system.common.libs.records.UserCreatedEvent;
import food.delivery.system.user.service.entity.User;
import food.delivery.system.user.service.enums.UserStatus;
import food.delivery.system.user.service.exception.DuplicateEmailException;
import food.delivery.system.user.service.record.RegisterRequest;
import food.delivery.system.user.service.record.RegisterResponse;
import food.delivery.system.user.service.repository.UserRepository;
import food.delivery.system.user.service.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(UserRegistrationService.class);

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;

    public UserRegistrationService(UserRepository userRepository,
                                   BCryptPasswordEncoder passwordEncoder,
                                   ApplicationEventPublisher events) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }

        User user = new User();
        user.setId(UuidV7.generate());
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.PENDING);

        userRepository.save(user);

        // Spring Modulith intercepts this call, writes to event_publication in the same
        // transaction, and delivers to Kafka asynchronously after commit.
        events.publishEvent(new UserCreatedEvent(user.getId(), user.getEmail(), user.getUsername(), user.getRole()));

        log.info("Registration accepted: userId={}, email='{}', status=PENDING", user.getId(), user.getEmail());

        return new RegisterResponse(user.getId(), user.getUsername(), user.getStatus().name(),
                "Registration accepted — account is pending activation.");
    }
}
