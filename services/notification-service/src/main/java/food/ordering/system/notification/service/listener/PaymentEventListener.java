package food.ordering.system.notification.service.listener;

import food.ordering.system.common.libs.constants.KafkaConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    @KafkaListener(topics = KafkaConstants.PAYMENT_TOPIC, groupId = KafkaConstants.NOTIFICATION_GROUP)
    public void onPaymentProcessed(String payload) {
        log.info("NOTIFICATION: received payment-processed event — dispatch placeholder, payload={}", payload);
    }
}
