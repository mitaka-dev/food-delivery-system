package food.delivery.system.basket.service.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import food.delivery.system.basket.service.domain.Basket;
import food.delivery.system.basket.service.domain.BasketItem;
import food.delivery.system.basket.service.domain.BasketRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

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
        redis.opsForHash().putAll(key, serializeToHash(basket));
        redis.expire(key, TTL);
    }

    @Override
    public Optional<Basket> findByUserId(String userId) {
        String key = KEY_PREFIX + userId;
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<String, String> hash = (Map<String, String>) (Map) redis.opsForHash().entries(key);
        return deserializeFromHash(userId, hash);
    }

    @Override
    public void delete(String userId) {
        redis.delete(KEY_PREFIX + userId);
    }

    @Override
    public Basket executeAtomically(String userId, Function<Optional<Basket>, Basket> modifier) {
        String key = KEY_PREFIX + userId;
        while (true) {
            RuntimeException[] caught = {null};
            Basket[] result = {null};

            Boolean committed = redis.execute(new SessionCallback<Boolean>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public Boolean execute(RedisOperations operations) {
                    operations.watch(key);

                    Map<String, String> hash = (Map<String, String>) (Map) operations.opsForHash().entries(key);
                    Optional<Basket> current = deserializeFromHash(userId, hash);

                    try {
                        result[0] = modifier.apply(current);
                    } catch (RuntimeException ex) {
                        caught[0] = ex;
                        operations.unwatch();
                        return Boolean.FALSE;
                    }

                    operations.multi();
                    try {
                        operations.opsForHash().putAll(key, serializeToHash(result[0]));
                    } catch (RuntimeException ex) {
                        operations.discard();
                        throw ex;
                    }
                    operations.expire(key, TTL);
                    return ((List<?>) operations.exec()) != null;
                }
            });

            if (caught[0] != null) throw caught[0];
            if (Boolean.TRUE.equals(committed)) return result[0];
            // EXEC returned null: key was modified between WATCH and EXEC — retry
        }
    }

    private Map<String, String> serializeToHash(Basket basket) {
        try {
            Map<String, String> hash = new HashMap<>();
            hash.put(F_RESTAURANT_ID, basket.getRestaurantId().toString());
            hash.put(F_CREATED_AT, basket.getLastModified().toString());
            hash.put(F_ITEMS_JSON, basketObjectMapper.writeValueAsString(basket.getItems()));
            return hash;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize basket", e);
        }
    }

    private Optional<Basket> deserializeFromHash(String userId, Map<String, String> hash) {
        if (hash == null || hash.isEmpty()) return Optional.empty();
        try {
            UUID restaurantId = UUID.fromString(hash.get(F_RESTAURANT_ID));
            Instant lastModified = Instant.parse(hash.get(F_CREATED_AT));
            List<BasketItem> items = basketObjectMapper.readValue(hash.get(F_ITEMS_JSON), ITEM_LIST_TYPE);
            return Optional.of(new Basket(userId, restaurantId, items, lastModified));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
