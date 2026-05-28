package food.delivery.system.basket.service;

import food.delivery.system.basket.service.exception.BasketLimitExceededException;
import food.delivery.system.basket.service.record.AddItemRequestDto;
import food.delivery.system.basket.service.record.BasketDto;
import food.delivery.system.basket.service.service.BasketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:9092",
        "management.tracing.sampling.probability=0.0"
})
class BasketServiceIT {

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

    @Autowired
    private BasketService basketService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String USER_ID = "user-123";
    private static final UUID RESTAURANT_A = UUID.randomUUID();
    private static final UUID RESTAURANT_B = UUID.randomUUID();

    @AfterEach
    void cleanup() {
        redisTemplate.delete("basket:" + USER_ID);
    }

    @Test
    void addItem_persistsInRedis() {
        AddItemRequestDto req = pizza(UUID.randomUUID(), RESTAURANT_A, 2);

        BasketDto result = basketService.addItem(USER_ID, req);

        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.restaurantId()).isEqualTo(RESTAURANT_A);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).productName()).isEqualTo("Margherita Pizza");
        assertThat(result.totalAmount()).isEqualByComparingTo("24.98");

        BasketDto fromStore = basketService.getBasket(USER_ID);
        assertThat(fromStore.items()).hasSize(1);
        assertThat(fromStore.restaurantId()).isEqualTo(RESTAURANT_A);
    }

    @Test
    void addItem_updatesExistingItem_refreshesTtl() throws Exception {
        UUID productId = UUID.randomUUID();
        basketService.addItem(USER_ID, pizza(productId, RESTAURANT_A, 1));

        // Update quantity for the same product
        basketService.addItem(USER_ID, new AddItemRequestDto(productId, RESTAURANT_A, "Margherita Pizza", 3, new BigDecimal("12.49")));

        BasketDto updated = basketService.getBasket(USER_ID);
        assertThat(updated.items()).hasSize(1);
        assertThat(updated.items().get(0).quantity()).isEqualTo(3);

        Long ttl = redisTemplate.getExpire("basket:" + USER_ID, TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(86400L);
    }

    @Test
    void addItem_differentRestaurant_clearsBasket() {
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();

        basketService.addItem(USER_ID, new AddItemRequestDto(productA, RESTAURANT_A, "Pizza", 1, new BigDecimal("10.00")));
        assertThat(basketService.getBasket(USER_ID).items()).hasSize(1);

        // Adding from a different restaurant must clear the existing basket
        basketService.addItem(USER_ID, new AddItemRequestDto(productB, RESTAURANT_B, "Burger", 2, new BigDecimal("8.00")));

        BasketDto result = basketService.getBasket(USER_ID);
        assertThat(result.restaurantId()).isEqualTo(RESTAURANT_B);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).productName()).isEqualTo("Burger");
    }

    @Test
    void addItem_exceedsLimit_throws() {
        for (int i = 0; i < 50; i++) {
            basketService.addItem(USER_ID,
                    new AddItemRequestDto(UUID.randomUUID(), RESTAURANT_A, "Item " + i, 1, new BigDecimal("1.00")));
        }

        assertThatThrownBy(() ->
                basketService.addItem(USER_ID,
                        new AddItemRequestDto(UUID.randomUUID(), RESTAURANT_A, "Item 51", 1, new BigDecimal("1.00"))))
                .isInstanceOf(BasketLimitExceededException.class)
                .hasMessageContaining("50");
    }

    private AddItemRequestDto pizza(UUID productId, UUID restaurantId, int quantity) {
        return new AddItemRequestDto(productId, restaurantId, "Margherita Pizza", quantity, new BigDecimal("12.49"));
    }
}
