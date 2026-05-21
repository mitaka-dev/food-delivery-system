package food.delivery.system.user.service.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import static food.delivery.system.common.libs.constants.KafkaConstants.USER_TOPIC;

@Configuration
public class KafkaConfig {
    @Bean
    public NewTopic userTopic() {
        return TopicBuilder.name(USER_TOPIC).partitions(3).replicas(1).build();
    }
}
