package food.delivery.system.common.libs.records;

import java.time.Instant;
import java.util.UUID;

public record KitchenRestaurantEvent(UUID restaurantId, String eventType, Instant occurredAt) {}
