package food.delivery.system.basket.service.domain;

import java.util.Optional;

public interface BasketRepository {
    void save(Basket basket);
    Optional<Basket> findByUserId(String userId);
    void delete(String userId);
}
