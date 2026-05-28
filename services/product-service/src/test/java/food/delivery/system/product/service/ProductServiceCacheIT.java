package food.delivery.system.product.service;

import food.delivery.system.product.service.entity.Product;
import food.delivery.system.product.service.enums.ProductCategory;
import food.delivery.system.product.service.exception.ProductNotFoundException;
import food.delivery.system.product.service.record.ProductResponseDto;
import food.delivery.system.product.service.repository.ProductRepository;
import food.delivery.system.product.service.service.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.cache.type=redis",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "management.tracing.sampling.probability=0.0"
})
class ProductServiceCacheIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void warmupRedis() {
        // Establish the Lettuce connection pool before the first cache operation.
        // Without this, the first @Cacheable store may fail silently if the pool
        // has not yet connected to Redis.
        cacheManager.getCache("products").get("__warmup__");
    }

    @AfterEach
    void clearCache() {
        cacheManager.getCache("products").clear();
    }

    @Test
    void getProduct_secondCallHitsCache() {
        Product saved = productRepository.save(buildProduct("Margherita Pizza"));

        // First call — cache miss, executes DB query and populates cache
        ProductResponseDto first = productService.getProduct(saved.getId());
        assertThat(first.name()).isEqualTo("Margherita Pizza");

        // Remove from DB — the entity no longer exists in PostgreSQL
        productRepository.deleteById(saved.getId());

        // Second call — product is gone from DB; must return from cache (not throw)
        ProductResponseDto fromCache = productService.getProduct(saved.getId());
        assertThat(fromCache.id()).isEqualTo(saved.getId());
        assertThat(fromCache.name()).isEqualTo("Margherita Pizza");
    }

    @Test
    void reserveStock_evictsCacheEntry() {
        Product saved = productRepository.save(buildProduct("Garlic Bread"));

        // Populate cache with stock=10
        ProductResponseDto initial = productService.getProduct(saved.getId());
        assertThat(initial.stock()).isEqualTo(10);

        // Reserve 3 units — must evict cache, DB now has stock=7
        productService.reserveStock(saved.getId(), 3);

        // Next read must be a cache miss → DB → returns fresh stock (7, not cached 10)
        ProductResponseDto updated = productService.getProduct(saved.getId());
        assertThat(updated.stock()).isEqualTo(7);
    }

    @Test
    void getProduct_afterCacheEviction_fetchesFreshFromDb() {
        Product saved = productRepository.save(buildProduct("Test Product"));

        // Populate cache, then evict
        productService.getProduct(saved.getId());
        cacheManager.getCache("products").evict(saved.getId().toString());

        // Delete from DB to confirm the next call does NOT serve from cache
        productRepository.deleteById(saved.getId());
        assertThatThrownBy(() -> productService.getProduct(saved.getId()))
                .isInstanceOf(ProductNotFoundException.class);
    }

    private Product buildProduct(String name) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setName(name);
        p.setDescription("Test description");
        p.setPrice(BigDecimal.valueOf(9.99));
        p.setCategory(ProductCategory.PIZZA);
        p.setStock(10);
        return p;
    }
}
