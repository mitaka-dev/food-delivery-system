package food.delivery.system.product.service.listener;

import food.delivery.system.common.libs.records.KitchenRestaurantEvent;
import food.delivery.system.grpc.product.ProductAvailability;
import food.delivery.system.grpc.product.ProductServiceGrpc;
import food.delivery.system.grpc.product.VerifyProductRequest;
import food.delivery.system.product.service.entity.Product;
import food.delivery.system.product.service.enums.ProductCategory;
import food.delivery.system.product.service.grpc.ProductGrpcService;
import food.delivery.system.product.service.repository.ProductRepository;
import food.delivery.system.product.service.repository.RestaurantStatusRepository;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1,
               topics = {"kitchen-events", "order-topics", "payment-topics"},
               bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = {
        "grpc.server.enabled=false",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.cache.type=redis",
        "management.tracing.sampling.probability=0.0",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer"
})
class RestaurantPausedListenerIT {

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

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired RestaurantStatusRepository restaurantStatusRepository;
    @Autowired ProductGrpcService productGrpcService;

    Server grpcServer;
    ManagedChannel grpcChannel;
    ProductServiceGrpc.ProductServiceBlockingStub grpcStub;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        String serverName = InProcessServerBuilder.generateName();
        grpcServer = InProcessServerBuilder.forName(serverName)
                .addService(productGrpcService).directExecutor().build().start();
        grpcChannel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        grpcStub = ProductServiceGrpc.newBlockingStub(grpcChannel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        grpcChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        grpcServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        productRepository.deleteAll();
        restaurantStatusRepository.deleteAll();
    }

    @Test
    void pauseRestaurant_hidesProductsFromSearch() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        productRepository.save(buildProduct("Pasta Carbonara", restaurantId));

        kafkaTemplate.send("kitchen-events",
                new KitchenRestaurantEvent(restaurantId, "RESTAURANT_PAUSED", Instant.now()));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(restaurantStatusRepository.findById(restaurantId))
                        .isPresent().get().matches(rs -> rs.isPaused()));

        mockMvc.perform(get("/api/v1/products/search").param("q", "Pasta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void resumeRestaurant_showsProductsInSearch() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        productRepository.save(buildProduct("Grilled Salmon", restaurantId));

        kafkaTemplate.send("kitchen-events",
                new KitchenRestaurantEvent(restaurantId, "RESTAURANT_PAUSED", Instant.now()));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(restaurantStatusRepository.findById(restaurantId))
                        .isPresent().get().matches(rs -> rs.isPaused()));

        kafkaTemplate.send("kitchen-events",
                new KitchenRestaurantEvent(restaurantId, "RESTAURANT_RESUMED", Instant.now()));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(restaurantStatusRepository.findById(restaurantId))
                        .isPresent().get().matches(rs -> !rs.isPaused()));

        mockMvc.perform(get("/api/v1/products/search").param("q", "Salmon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Grilled Salmon"));
    }

    @Test
    void pauseRestaurant_grpcReturnsRestaurantPaused() {
        UUID restaurantId = UUID.randomUUID();
        Product saved = productRepository.save(buildProduct("Sushi Platter", restaurantId));

        kafkaTemplate.send("kitchen-events",
                new KitchenRestaurantEvent(restaurantId, "RESTAURANT_PAUSED", Instant.now()));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(restaurantStatusRepository.findById(restaurantId))
                        .isPresent().get().matches(rs -> rs.isPaused()));

        ProductAvailability result = grpcStub.verifyProduct(
                VerifyProductRequest.newBuilder().setProductId(saved.getId().toString()).build());

        assertThat(result.getExists()).isTrue();
        assertThat(result.getRestaurantPaused()).isTrue();
    }

    private Product buildProduct(String name, UUID restaurantId) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setName(name);
        p.setDescription("Test description");
        p.setPrice(BigDecimal.valueOf(10.00));
        p.setCategory(ProductCategory.PIZZA);
        p.setStock(10);
        p.setRestaurantId(restaurantId);
        return p;
    }
}
