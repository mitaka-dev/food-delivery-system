package food.delivery.system.basket.service.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import food.delivery.system.basket.service.domain.Basket;
import food.delivery.system.basket.service.domain.BasketItem;
import food.delivery.system.basket.service.domain.BasketRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Repository
public class BasketRedisRepository implements BasketRepository {

    private static final String KEY_PREFIX = "basket:";
    private static final Duration TTL = Duration.ofHours(24);
    private static final String F_RESTAURANT_ID = "restaurant_id";
    private static final String F_CREATED_AT = "created_at";
    private static final String F_ITEMS_JSON = "items_json";
    private static final TypeReference<List<BasketItem>> ITEM_LIST_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final ObjectMapper basketObjectMapper;

    public BasketRedisRepository(StringRedisTemplate redis,
                                 @Qualifier("basketObjectMapper") ObjectMapper basketObjectMapper) {
        this.redis = redis;
        this.basketObjectMapper = basketObjectMapper;
    }

    @Override
    public void save(Basket basket) {
        String key = KEY_PREFIX + basket.getUserId();
        try {
            HashOperations<String, String, String> ops = redis.opsForHash();
            Map<String, String> hash = new HashMap<>();
            hash.put(F_RESTAURANT_ID, basket.getRestaurantId().toString());
            hash.put(F_CREATED_AT, basket.getLastModified().toString());
            hash.put(F_ITEMS_JSON, basketObjectMapper.writeValueAsString(basket.getItems()));
            ops.putAll(key, hash);
            redis.expire(key, TTL);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize basket items", e);
        }
    }

    @Override
    public Optional<Basket> findByUserId(String userId) {
        String key = KEY_PREFIX + userId;
        HashOperations<String, String, String> ops = redis.opsForHash();
        Map<String, String> hash = ops.entries(key);
        if (hash.isEmpty()) return Optional.empty();
        try {
            UUID restaurantId = UUID.fromString(hash.get(F_RESTAURANT_ID));
            Instant lastModified = Instant.parse(hash.get(F_CREATED_AT));
            List<BasketItem> items = basketObjectMapper.readValue(hash.get(F_ITEMS_JSON), ITEM_LIST_TYPE);
            return Optional.of(new Basket(userId, restaurantId, items, lastModified));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String userId) {
        redis.delete(KEY_PREFIX + userId);
    }
}
