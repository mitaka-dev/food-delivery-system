package food.delivery.system.review.service.listener;

import food.delivery.system.common.libs.constants.KafkaConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderDeliveredEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderDeliveredEventListener.class);

    // TODO: consume a typed OrderDeliveredEvent from common-libs once the upstream saga publishes to review-order-topic
    @KafkaListener(topics = KafkaConstants.REVIEW_ORDER_TOPIC, groupId = KafkaConstants.REVIEW_GROUP)
    public void onOrderDelivered(String payload) {
        log.info("REVIEW: received order-delivered event — processing placeholder, payload={}", payload);
    }
}
