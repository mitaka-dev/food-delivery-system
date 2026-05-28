package food.delivery.system.product.service.repository;

import food.delivery.system.product.service.entity.Product;
import food.delivery.system.product.service.enums.ProductCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    Page<Product> findByCategory(ProductCategory category, Pageable pageable);

    @Query("SELECT p FROM Product p " +
           "WHERE (LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :q, '%'))) " +
           "AND (p.restaurantId IS NULL OR NOT EXISTS " +
           "(SELECT rs FROM RestaurantStatus rs " +
           "WHERE rs.restaurantId = p.restaurantId AND rs.paused = true))")
    Page<Product> search(@Param("q") String q, Pageable pageable);
}
