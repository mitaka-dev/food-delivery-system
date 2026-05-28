package food.delivery.system.product.service.repository;

import food.delivery.system.product.service.entity.RestaurantStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RestaurantStatusRepository extends JpaRepository<RestaurantStatus, UUID> {}
