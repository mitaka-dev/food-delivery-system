package food.delivery.system.kitchen.service.listener;

import food.delivery.system.common.libs.constants.KafkaConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderPaidEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidEventListener.class);

    // TODO: consume a typed event (e.g. OrderPaidEvent from common-libs) once the upstream saga publishes to kitchen-order-topic
    @KafkaListener(topics = KafkaConstants.KITCHEN_ORDER_TOPIC, groupId = KafkaConstants.KITCHEN_GROUP)
    public void onOrderPaid(String payload) {
        log.info("KITCHEN: received order event — processing placeholder, payload={}", payload);
    }
}
