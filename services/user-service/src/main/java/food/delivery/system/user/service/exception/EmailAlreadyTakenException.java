package food.delivery.system.user.service.exception;

public class EmailAlreadyTakenException extends RuntimeException {
    public EmailAlreadyTakenException(String email) {
        super("Email already registered: " + email);
    }
}
