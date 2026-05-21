package food.ordering.system.basket.service.publisher;

import food.ordering.system.basket.service.record.BasketDto;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class CheckoutEventPublisher {

    // TODO: define BASKET_CHECKOUT_TOPIC in common-libs KafkaConstants
    private static final String CHECKOUT_TOPIC = "basket-checkout-topic";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public CheckoutEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    // Placeholder: wire BasketCheckedOutEvent (to be added to common-libs) when checkout endpoint is implemented.
    public void publishCheckout(String userId, BasketDto basket) {
    }
}
