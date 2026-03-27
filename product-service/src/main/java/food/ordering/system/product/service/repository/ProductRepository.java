package food.ordering.system.product.service.repository;

import food.ordering.system.product.service.entity.Product;
import food.ordering.system.product.service.enums.ProductCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    Page<Product> findByCategory(ProductCategory category, Pageable pageable);
}
