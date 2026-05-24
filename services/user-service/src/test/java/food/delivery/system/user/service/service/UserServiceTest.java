package food.delivery.system.user.service.service;

import food.delivery.system.common.libs.enums.UserRole;
import food.delivery.system.user.service.entity.User;
import food.delivery.system.user.service.enums.UserStatus;
import food.delivery.system.user.service.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @InjectMocks
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    @Test
    void confirmUser_knownUser_setsStatusToActive() {
        UUID id = UUID.randomUUID();
        User pending = new User();
        pending.setId(id);
        pending.setUsername("bob");
        pending.setEmail("bob@example.com");
        pending.setPasswordHash("hash");
        pending.setRole(UserRole.USER);
        pending.setStatus(UserStatus.PENDING);
        when(userRepository.findById(id)).thenReturn(Optional.of(pending));

        userService.confirmUser(id.toString());

        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void confirmUser_unknownUser_neverCallsSave() {
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        userService.confirmUser(UUID.randomUUID().toString());

        verify(userRepository, never()).save(any());
    }
}
