package food.delivery.system.product.service.grpc;

import food.delivery.system.grpc.product.ProductAvailability;
import food.delivery.system.grpc.product.ProductServiceGrpc;
import food.delivery.system.grpc.product.VerifyProductRequest;
import food.delivery.system.product.service.entity.Product;
import food.delivery.system.product.service.enums.ProductCategory;
import food.delivery.system.product.service.repository.ProductRepository;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
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
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "grpc.server.enabled=false",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.cache.type=redis",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "management.tracing.sampling.probability=0.0"
})
class ProductGrpcServiceTest {

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
    ProductGrpcService productGrpcService;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    CacheManager cacheManager;

    Server server;
    ManagedChannel channel;
    ProductServiceGrpc.ProductServiceBlockingStub stub;

    @BeforeEach
    void setup() throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .addService(productGrpcService)
                .directExecutor()
                .build().start();
        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
        stub = ProductServiceGrpc.newBlockingStub(channel);
        cacheManager.getCache("products").get("__warmup__");
    }

    @AfterEach
    void teardown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        productRepository.deleteAll();
        cacheManager.getCache("products").clear();
    }

    @Test
    void verifyProduct_existsInStock_returnsAvailability() {
        Product saved = productRepository.save(buildProduct("Margherita", BigDecimal.valueOf(12.99), 5));

        ProductAvailability result = stub.verifyProduct(
                VerifyProductRequest.newBuilder().setProductId(saved.getId().toString()).build());

        assertThat(result.getExists()).isTrue();
        assertThat(result.getInStock()).isTrue();
        assertThat(result.getCurrentPrice()).isEqualTo("12.99");
        assertThat(result.getStock()).isEqualTo(5);
    }

    @Test
    void verifyProduct_outOfStock_returnsInStockFalse() {
        Product saved = productRepository.save(buildProduct("Sold Out Burger", BigDecimal.valueOf(8.99), 0));

        ProductAvailability result = stub.verifyProduct(
                VerifyProductRequest.newBuilder().setProductId(saved.getId().toString()).build());

        assertThat(result.getExists()).isTrue();
        assertThat(result.getInStock()).isFalse();
        assertThat(result.getStock()).isEqualTo(0);
    }

    @Test
    void verifyProduct_notFound_returnsExistsFalse() {
        ProductAvailability result = stub.verifyProduct(
                VerifyProductRequest.newBuilder().setProductId(UUID.randomUUID().toString()).build());

        assertThat(result.getExists()).isFalse();
    }

    @Test
    void verifyProduct_p99Under50ms() {
        Product saved = productRepository.save(buildProduct("Tiramisu", BigDecimal.valueOf(6.50), 20));
        VerifyProductRequest request = VerifyProductRequest.newBuilder()
                .setProductId(saved.getId().toString()).build();

        stub.verifyProduct(request); // warm the cache

        long[] times = new long[20];
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            stub.verifyProduct(request);
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        long p99Ms = times[19] / 1_000_000;
        assertThat(p99Ms).isLessThan(50);
    }

    private Product buildProduct(String name, BigDecimal price, int stock) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setName(name);
        p.setDescription("Test description");
        p.setPrice(price);
        p.setCategory(ProductCategory.PIZZA);
        p.setStock(stock);
        return p;
    }
}
