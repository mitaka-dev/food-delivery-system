package food.delivery.system.product.service.listener;

import food.delivery.system.common.libs.records.KitchenRestaurantEvent;
import food.delivery.system.product.service.entity.RestaurantStatus;
import food.delivery.system.product.service.repository.RestaurantStatusRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import static food.delivery.system.common.libs.constants.KafkaConstants.KITCHEN_EVENTS_TOPIC;
import static food.delivery.system.common.libs.constants.KafkaConstants.PRODUCT_GROUP;

@Service
public class RestaurantPausedListener {

    private static final Logger log = LoggerFactory.getLogger(RestaurantPausedListener.class);

    private final RestaurantStatusRepository restaurantStatusRepository;

    public RestaurantPausedListener(RestaurantStatusRepository restaurantStatusRepository) {
        this.restaurantStatusRepository = restaurantStatusRepository;
    }

    @Transactional
    @KafkaListener(topics = KITCHEN_EVENTS_TOPIC, groupId = PRODUCT_GROUP)
    public void handleKitchenEvent(KitchenRestaurantEvent event) {
        if ("RESTAURANT_PAUSED".equals(event.eventType())) {
            RestaurantStatus rs = restaurantStatusRepository.findById(event.restaurantId())
                    .orElse(new RestaurantStatus());
            rs.setRestaurantId(event.restaurantId());
            rs.setPaused(true);
            rs.setPausedAt(event.occurredAt());
            restaurantStatusRepository.save(rs);
            log.info("Restaurant paused: restaurantId={}", event.restaurantId());

        } else if ("RESTAURANT_RESUMED".equals(event.eventType())) {
            restaurantStatusRepository.findById(event.restaurantId()).ifPresentOrElse(rs -> {
                rs.setPaused(false);
                rs.setResumedAt(event.occurredAt());
                restaurantStatusRepository.save(rs);
                log.info("Restaurant resumed: restaurantId={}", event.restaurantId());
            }, () -> log.warn("RESTAURANT_RESUMED received for unknown restaurantId={}", event.restaurantId()));
        }
    }
}
