package food.ordering.system.common.libs.constants;

public class KafkaConstants {

    private KafkaConstants() {}

    public static final String USER_TOPIC = "user-topics";
    public static final String USER_CONFIRMATION_TOPIC = "user-confirmation-topic";
    public static final String ANALYTICS_GROUP = "analytics-group";
    public static final String USER_GROUP = "user-group";

    public static final String ORDER_TOPIC = "order-topics";
    public static final String ORDER_CONFIRMATION_TOPIC = "order-confirmation-topic";
    public static final String ORDER_GROUP = "order-group";

    public static final String PAYMENT_TOPIC = "payment-topics";
    public static final String PAYMENT_GROUP = "payment-group";
}
