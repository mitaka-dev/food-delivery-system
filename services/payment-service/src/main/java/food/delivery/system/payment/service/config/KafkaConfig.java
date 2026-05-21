package food.delivery.system.payment.service.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import static food.delivery.system.common.libs.constants.KafkaConstants.ORDER_CONFIRMATION_TOPIC;
import static food.delivery.system.common.libs.constants.KafkaConstants.PAYMENT_TOPIC;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic paymentTopic() {
        return TopicBuilder.name(PAYMENT_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic orderConfirmationTopic() {
        return TopicBuilder.name(ORDER_CONFIRMATION_TOPIC).partitions(3).replicas(1).build();
    }
}
