package food.delivery.system.basket.service;

import food.delivery.system.basket.service.api.IdempotencyFilter;
import food.delivery.system.basket.service.client.ProductClientFallback;
import food.delivery.system.basket.service.client.ProductGrpcClient;
import food.delivery.system.basket.service.exception.ProductServiceUnavailableException;
import food.delivery.system.grpc.product.ProductAvailability;
import food.delivery.system.grpc.product.ProductServiceGrpc;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:9092",
        "management.tracing.sampling.probability=0.0",
        "grpc.client.product.enabled=false"
})
class ProductGrpcClientIT {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void configureRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @MockitoBean
    ProductGrpcClient productGrpcClient;

    @Autowired
    WebApplicationContext wac;

    @Autowired
    IdempotencyFilter idempotencyFilter;

    @Autowired
    StringRedisTemplate redisTemplate;

    MockMvc mockMvc;

    private static final String USER_ID = "grpc-it-user";
    private static final UUID RESTAURANT_A = UUID.randomUUID();
    private static final UUID PRODUCT_A = UUID.randomUUID();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .addFilters(idempotencyFilter)
                .build();
    }

    @AfterEach
    void cleanup() {
        redisTemplate.delete("basket:" + USER_ID);
        Set<String> idemKeys = redisTemplate.keys("idem:basket:" + USER_ID + ":*");
        if (idemKeys != null && !idemKeys.isEmpty()) {
            redisTemplate.delete(idemKeys);
        }
    }

    @Test
    void productAvailable_priceMatch_addsToBasket() throws Exception {
        when(productGrpcClient.verify(any())).thenReturn(
                ProductAvailability.newBuilder()
                        .setExists(true).setInStock(true)
                        .setCurrentPrice("10.00").setRestaurantPaused(false)
                        .build());

        mockMvc.perform(post("/api/v1/basket/items")
                        .header("X-User-Name", USER_ID)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemRequest(PRODUCT_A, "10.00")))
                .andExpect(status().isOk());
    }

    @Test
    void outOfStock_returns409() throws Exception {
        when(productGrpcClient.verify(any())).thenReturn(
                ProductAvailability.newBuilder()
                        .setExists(true).setInStock(false)
                        .build());

        mockMvc.perform(post("/api/v1/basket/items")
                        .header("X-User-Name", USER_ID)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemRequest(PRODUCT_A, "10.00")))
                .andExpect(status().isConflict());
    }

    @Test
    void restaurantPaused_returns409() throws Exception {
        when(productGrpcClient.verify(any())).thenReturn(
                ProductAvailability.newBuilder()
                        .setExists(true).setInStock(true)
                        .setCurrentPrice("10.00").setRestaurantPaused(true)
                        .build());

        mockMvc.perform(post("/api/v1/basket/items")
                        .header("X-User-Name", USER_ID)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemRequest(PRODUCT_A, "10.00")))
                .andExpect(status().isConflict());
    }

    @Test
    void priceMismatch_returns409() throws Exception {
        when(productGrpcClient.verify(any())).thenReturn(
                ProductAvailability.newBuilder()
                        .setExists(true).setInStock(true)
                        .setCurrentPrice("12.00").setRestaurantPaused(false)
                        .build());

        mockMvc.perform(post("/api/v1/basket/items")
                        .header("X-User-Name", USER_ID)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemRequest(PRODUCT_A, "10.00")))
                .andExpect(status().isConflict());
    }

    @Test
    void deadServer_failsFast() {
        ManagedChannel dead = ManagedChannelBuilder.forAddress("localhost", 29999)
                .usePlaintext()
                .build();
        try {
            var stub = ProductServiceGrpc.newBlockingStub(dead);
            RetryRegistry rr = RetryRegistry.of(RetryConfig.custom()
                    .maxAttempts(2).waitDuration(Duration.ofMillis(1)).build());
            ProductGrpcClient directClient = new ProductGrpcClient(
                    stub, CircuitBreakerRegistry.ofDefaults(), rr, new ProductClientFallback());

            long start = System.currentTimeMillis();
            assertThatThrownBy(() -> directClient.verify(UUID.randomUUID()))
                    .isInstanceOf(ProductServiceUnavailableException.class);
            assertThat(System.currentTimeMillis() - start).isLessThan(1000L);
        } finally {
            dead.shutdownNow();
        }
    }

    private String itemRequest(UUID productId, String price) {
        return """
                {"productId":"%s","restaurantId":"%s","productName":"Pizza","quantity":1,"price":"%s"}
                """.formatted(productId, RESTAURANT_A, price);
    }
}
