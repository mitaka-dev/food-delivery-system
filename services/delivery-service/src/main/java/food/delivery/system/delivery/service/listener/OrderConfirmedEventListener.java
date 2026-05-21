package food.delivery.system.delivery.service.listener;

import food.delivery.system.common.libs.constants.KafkaConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderConfirmedEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedEventListener.class);

    // TODO: consume a typed event (e.g. OrderDeliveryEvent from common-libs) once the upstream saga publishes to delivery-order-topic
    @KafkaListener(topics = KafkaConstants.DELIVERY_ORDER_TOPIC, groupId = KafkaConstants.DELIVERY_GROUP)
    public void onOrderConfirmed(String payload) {
        log.info("DELIVERY: received order event — processing placeholder, payload={}", payload);
    }
}
