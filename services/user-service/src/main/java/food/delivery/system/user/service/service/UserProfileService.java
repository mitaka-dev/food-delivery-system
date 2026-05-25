package food.delivery.system.user.service.service;

import food.delivery.system.user.service.entity.User;
import food.delivery.system.user.service.exception.UserNotFoundException;
import food.delivery.system.user.service.record.UpdateProfileRequest;
import food.delivery.system.user.service.record.UserProfileResponse;
import food.delivery.system.user.service.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {

    private final UserRepository userRepository;

    public UserProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(String email) {
        User user = findByEmail(email);
        return toResponse(user);
    }

    @Transactional
    public UserProfileResponse updateProfile(String email, UpdateProfileRequest req) {
        User user = findByEmail(email);

        if (req.name() != null) {
            user.setUsername(req.name());
        }
        if (req.locale() != null) {
            user.setLocale(req.locale());
        }
        if (req.phone() != null) {
            user.setPhone(req.phone());
        }

        return toResponse(userRepository.save(user));
    }

    private User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(email));
    }

    private UserProfileResponse toResponse(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                user.getLocale(),
                user.getPhone(),
                user.getRole(),
                user.getCreatedAt()
        );
    }
}
