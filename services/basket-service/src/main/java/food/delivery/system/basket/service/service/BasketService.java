package food.delivery.system.basket.service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import food.delivery.system.basket.service.exception.BasketItemNotFoundException;
import food.delivery.system.basket.service.record.AddItemRequestDto;
import food.delivery.system.basket.service.record.BasketDto;
import food.delivery.system.basket.service.record.BasketItemDto;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class BasketService {

    private static final String KEY_PREFIX = "basket:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public BasketService(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public BasketDto getBasket(String userId) {
        String json = redis.opsForValue().get(KEY_PREFIX + userId);
        if (json == null) return emptyBasket(userId);
        try {
            return mapper.readValue(json, BasketDto.class);
        } catch (JsonProcessingException e) {
            return emptyBasket(userId);
        }
    }

    public BasketDto addItem(String userId, AddItemRequestDto req) {
        BasketDto current = getBasket(userId);
        List<BasketItemDto> items = new ArrayList<>(current.items());
        items.removeIf(i -> i.productId().equals(req.productId()));
        items.add(new BasketItemDto(req.productId(), req.productName(), req.quantity(), req.price()));
        BasketDto updated = new BasketDto(userId, items, calculateTotal(items));
        save(userId, updated);
        return updated;
    }

    public BasketDto removeItem(String userId, UUID productId) {
        BasketDto current = getBasket(userId);
        List<BasketItemDto> items = current.items().stream()
                .filter(i -> !i.productId().equals(productId))
                .toList();
        if (items.size() == current.items().size()) {
            throw new BasketItemNotFoundException("Item " + productId + " not found in basket");
        }
        BasketDto updated = new BasketDto(userId, items, calculateTotal(items));
        save(userId, updated);
        return updated;
    }

    public void clearBasket(String userId) {
        redis.delete(KEY_PREFIX + userId);
    }

    private void save(String userId, BasketDto basket) {
        try {
            redis.opsForValue().set(KEY_PREFIX + userId, mapper.writeValueAsString(basket), TTL);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize basket", e);
        }
    }

    private BasketDto emptyBasket(String userId) {
        return new BasketDto(userId, List.of(), BigDecimal.ZERO);
    }

    private BigDecimal calculateTotal(List<BasketItemDto> items) {
        return items.stream()
                .map(i -> i.price().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
