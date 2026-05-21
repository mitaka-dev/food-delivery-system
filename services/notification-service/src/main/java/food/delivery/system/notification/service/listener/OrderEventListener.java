package food.delivery.system.notification.service.listener;

import food.delivery.system.common.libs.constants.KafkaConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    @KafkaListener(topics = KafkaConstants.ORDER_TOPIC, groupId = KafkaConstants.NOTIFICATION_GROUP)
    public void onOrderCreated(String payload) {
        log.info("NOTIFICATION: received order-created event — dispatch placeholder, payload={}", payload);
    }
}
