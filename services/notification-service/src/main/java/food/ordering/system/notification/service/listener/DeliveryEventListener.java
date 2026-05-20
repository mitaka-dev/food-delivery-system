package food.ordering.system.notification.service.listener;

import food.ordering.system.common.libs.constants.KafkaConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DeliveryEventListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryEventListener.class);

    @KafkaListener(topics = KafkaConstants.DELIVERY_ORDER_TOPIC, groupId = KafkaConstants.NOTIFICATION_GROUP)
    public void onDeliveryUpdated(String payload) {
        log.info("NOTIFICATION: received delivery event — dispatch placeholder, payload={}", payload);
    }
}
