package food.delivery.system.product.service.repository;

import food.delivery.system.product.service.entity.Product;
import food.delivery.system.product.service.enums.ProductCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "management.tracing.sampling.probability=0.0"
})
class ProductRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TransactionTemplate txTemplate;

    @Test
    void saveAndFindById_returnsEqualProduct() {
        UUID id = UUID.randomUUID();

        txTemplate.execute(status -> {
            productRepository.save(buildProduct(id, "Margherita Pizza", BigDecimal.valueOf(12.99), 50));
            return null;
        });

        Product found = txTemplate.execute(status ->
                productRepository.findById(id).orElseThrow());

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getName()).isEqualTo("Margherita Pizza");
        assertThat(found.getDescription()).isEqualTo("Classic tomato and mozzarella");
        assertThat(found.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(12.99));
        assertThat(found.getCategory()).isEqualTo(ProductCategory.PIZZA);
        assertThat(found.getStock()).isEqualTo(50);
        assertThat(found.getVersion()).isEqualTo(0L);
    }

    @Test
    void concurrentStockUpdate_throwsOptimisticLockingFailureException() {
        UUID id = UUID.randomUUID();

        // Tx1: persist (version=0 in DB)
        txTemplate.execute(status -> {
            productRepository.save(buildProduct(id, "Garlic Bread", BigDecimal.valueOf(4.50), 20));
            return null;
        });

        // Tx2: load stale snapshot — entity is detached when transaction ends (version=0)
        Product stale = txTemplate.execute(status ->
                productRepository.findById(id).orElseThrow());

        // Tx3: competing update increments version to 1 in DB
        txTemplate.execute(status -> {
            Product current = productRepository.findById(id).orElseThrow();
            current.setStock(current.getStock() - 1);
            productRepository.save(current);
            return null;
        });

        // Tx4: merging stale entity (version=0) generates UPDATE WHERE version=0,
        // which matches 0 rows (DB has version=1) → ObjectOptimisticLockingFailureException
        stale.setStock(stale.getStock() - 1);
        Product finalStale = stale;
        assertThrows(ObjectOptimisticLockingFailureException.class, () ->
                txTemplate.execute(status -> {
                    productRepository.save(finalStale);
                    return null;
                }));
    }

    private Product buildProduct(UUID id, String name, BigDecimal price, int stock) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setDescription("Classic tomato and mozzarella");
        p.setPrice(price);
        p.setCategory(ProductCategory.PIZZA);
        p.setStock(stock);
        return p;
    }
}
