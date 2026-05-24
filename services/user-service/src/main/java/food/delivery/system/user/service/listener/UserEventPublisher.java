package food.delivery.system.user.service.listener;

import food.delivery.system.common.libs.constants.KafkaConstants;
import food.delivery.system.common.libs.records.UserCreatedEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class UserEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    UserEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserCreated(UserCreatedEvent event) {
        kafkaTemplate.send(KafkaConstants.USER_EVENTS_TOPIC, event.userId().toString(), event);
    }
}
