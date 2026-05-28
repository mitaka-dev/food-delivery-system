package food.delivery.system.basket.service.domain;

import java.util.Optional;
import java.util.function.Function;

public interface BasketRepository {
    void save(Basket basket);
    Optional<Basket> findByUserId(String userId);
    void delete(String userId);
    Basket executeAtomically(String userId, Function<Optional<Basket>, Basket> modifier);
}
