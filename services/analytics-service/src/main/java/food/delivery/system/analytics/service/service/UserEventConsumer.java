package food.delivery.system.analytics.service.service;

import food.delivery.system.common.libs.records.UserCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import static food.delivery.system.common.libs.constants.KafkaConstants.*;

@Service
public class UserEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserEventConsumer.class);
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public UserEventConsumer(StringRedisTemplate redisTemplate, KafkaTemplate<Object, Object> kafkaTemplate) {
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = USER_TOPIC, groupId = ANALYTICS_GROUP)
    public void consumeUserCreated(UserCreatedEvent event) {
        log.info("Analytics Service received event for user: {} with role: {}", event.username(), event.role());

        // 1. Update Redis counter for the specific role
        // Key format: "stats:roles:ADMIN" or "stats:roles:USER"
        String redisKey = "stats:roles:" + event.role();
        redisTemplate.opsForValue().increment(redisKey);

        log.info("Updated Redis counter for key: {}", redisKey);

        // 2. Saga Confirmation: Send signal back to User Service
        // We send the userId as a string to the confirmation topic
        log.info("Sending confirmation back to User Service for ID: {}", event.userId());
        kafkaTemplate.send(USER_CONFIRMATION_TOPIC, event.userId().toString(), "SUCCESS");
    }
}