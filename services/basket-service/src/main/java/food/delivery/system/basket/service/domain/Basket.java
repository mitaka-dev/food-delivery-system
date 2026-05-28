package food.delivery.system.basket.service.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Basket {

    private String userId;
    private UUID restaurantId;
    private List<BasketItem> items;
    private Instant lastModified;

    public Basket() {
        this.items = new ArrayList<>();
        this.lastModified = Instant.now();
    }

    public Basket(String userId, UUID restaurantId, List<BasketItem> items, Instant lastModified) {
        this.userId = userId;
        this.restaurantId = restaurantId;
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        this.lastModified = lastModified;
    }

    public BigDecimal subtotal() {
        return items.stream()
                .map(i -> i.price().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public UUID getRestaurantId() { return restaurantId; }
    public void setRestaurantId(UUID restaurantId) { this.restaurantId = restaurantId; }

    public List<BasketItem> getItems() { return items; }
    public void setItems(List<BasketItem> items) { this.items = items; }

    public Instant getLastModified() { return lastModified; }
    public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }
}
