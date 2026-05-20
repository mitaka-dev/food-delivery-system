package food.ordering.system.promotion.service.listener;

import food.ordering.system.common.libs.constants.KafkaConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class UserCreatedEventListener {

    private static final Logger log = LoggerFactory.getLogger(UserCreatedEventListener.class);

    // TODO: consume typed UserCreatedEvent from common-libs to issue welcome promotions
    @KafkaListener(topics = KafkaConstants.USER_TOPIC, groupId = KafkaConstants.PROMOTION_GROUP)
    public void onUserCreated(String payload) {
        log.info("PROMOTION: received user-created event — processing placeholder, payload={}", payload);
    }
}
