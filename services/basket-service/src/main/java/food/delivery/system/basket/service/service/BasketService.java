package food.delivery.system.basket.service.service;

import food.delivery.system.basket.service.domain.Basket;
import food.delivery.system.basket.service.domain.BasketItem;
import food.delivery.system.basket.service.domain.BasketRepository;
import food.delivery.system.basket.service.exception.BasketItemNotFoundException;
import food.delivery.system.basket.service.exception.BasketLimitExceededException;
import food.delivery.system.basket.service.record.AddItemRequestDto;
import food.delivery.system.basket.service.record.BasketDto;
import food.delivery.system.basket.service.record.BasketItemDto;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BasketService {

    private static final int MAX_ITEMS = 50;

    private final BasketRepository basketRepository;

    public BasketService(BasketRepository basketRepository) {
        this.basketRepository = basketRepository;
    }

    public BasketDto getBasket(String userId) {
        return basketRepository.findByUserId(userId)
                .map(this::toDto)
                .orElse(emptyBasket(userId));
    }

    public BasketDto addItem(String userId, AddItemRequestDto req) {
        Basket result = basketRepository.executeAtomically(userId, optCurrent -> {
            Basket current = optCurrent.orElse(
                    new Basket(userId, req.restaurantId(), new ArrayList<>(), Instant.now()));

            if (!req.restaurantId().equals(current.getRestaurantId())) {
                current = new Basket(userId, req.restaurantId(), new ArrayList<>(), Instant.now());
            }

            List<BasketItem> items = current.getItems();
            int before = items.size();
            items.removeIf(i -> i.productId().equals(req.productId()));
            boolean isNewProduct = items.size() == before;

            if (isNewProduct && items.size() >= MAX_ITEMS) {
                throw new BasketLimitExceededException("Basket limit of " + MAX_ITEMS + " items reached");
            }

            items.add(new BasketItem(req.productId(), req.productName(), req.quantity(), req.price()));
            current.setLastModified(Instant.now());
            return current;
        });
        return toDto(result);
    }

    public BasketDto removeItem(String userId, UUID productId) {
        Basket result = basketRepository.executeAtomically(userId, optCurrent -> {
            Basket current = optCurrent.orElse(
                    new Basket(userId, null, new ArrayList<>(), Instant.now()));

            int before = current.getItems().size();
            current.getItems().removeIf(i -> i.productId().equals(productId));

            if (current.getItems().size() == before) {
                throw new BasketItemNotFoundException("Item " + productId + " not found in basket");
            }

            current.setLastModified(Instant.now());
            return current;
        });
        return toDto(result);
    }

    public void clearBasket(String userId) {
        basketRepository.delete(userId);
    }

    private BasketDto toDto(Basket basket) {
        List<BasketItemDto> itemDtos = basket.getItems().stream()
                .map(i -> new BasketItemDto(i.productId(), i.productName(), i.quantity(), i.price()))
                .toList();
        return new BasketDto(basket.getUserId(), basket.getRestaurantId(), itemDtos,
                basket.subtotal(), basket.getLastModified());
    }

    private BasketDto emptyBasket(String userId) {
        return new BasketDto(userId, null, List.of(), BigDecimal.ZERO, null);
    }
}
