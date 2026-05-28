package food.delivery.system.basket.service.exception;

public class BasketLimitExceededException extends RuntimeException {
    public BasketLimitExceededException(String message) {
        super(message);
    }
}
