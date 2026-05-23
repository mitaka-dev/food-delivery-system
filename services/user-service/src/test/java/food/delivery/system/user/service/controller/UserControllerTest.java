package food.delivery.system.user.service.controller;

import food.delivery.system.common.libs.enums.UserRole;
import food.delivery.system.user.service.record.UserRegistrationDto;
import food.delivery.system.user.service.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class UserControllerTest {

    @InjectMocks
    private UserController controller;

    @Mock
    private UserService userService;

    private static final UserRegistrationDto DTO =
            new UserRegistrationDto("bob@example.com", "secret", UserRole.USER);

    @Test
    void register_delegatesToServiceAndReturns200() {
        ResponseEntity<String> response = controller.register(DTO);

        verify(userService).registerUser(DTO);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("PENDING");
    }

    @Test
    void register_serviceThrows_propagatesException() {
        doThrow(new RuntimeException("db error")).when(userService).registerUser(DTO);

        assertThrows(RuntimeException.class, () -> controller.register(DTO));
    }
}
