package food.ordering.system.basket.service.exception;

public class BasketItemNotFoundException extends RuntimeException {
    public BasketItemNotFoundException(String message) {
        super(message);
    }
}
