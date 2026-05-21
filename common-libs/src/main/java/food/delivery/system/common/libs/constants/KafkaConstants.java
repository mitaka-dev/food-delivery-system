package food.delivery.system.common.libs.constants;

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

    public static final String PRODUCT_GROUP = "product-group";

    public static final String KITCHEN_ORDER_TOPIC = "kitchen-order-topic";
    public static final String KITCHEN_GROUP = "kitchen-group";

    public static final String DELIVERY_ORDER_TOPIC = "delivery-order-topic";
    public static final String DELIVERY_GROUP = "delivery-group";

    public static final String REVIEW_ORDER_TOPIC = "review-order-topic";
    public static final String REVIEW_GROUP = "review-group";

    public static final String PROMOTION_GROUP = "promotion-group";

    public static final String NOTIFICATION_GROUP = "notification-group";
}
