package food.delivery.system.common.libs.exceptions;

public abstract class PlatformException extends RuntimeException {

    private final String code;

    protected PlatformException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
