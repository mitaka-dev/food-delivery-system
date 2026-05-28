package food.delivery.system.basket.service;

import food.delivery.system.basket.service.api.IdempotencyFilter;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:9092",
        "management.tracing.sampling.probability=0.0"
})
class BasketControllerIT {

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
    WebApplicationContext wac;

    @Autowired
    IdempotencyFilter idempotencyFilter;

    @Autowired
    StringRedisTemplate redisTemplate;

    MockMvc mockMvc;

    private static final String USER_ID = "user-ctrl-it";
    private static final UUID RESTAURANT_A = UUID.randomUUID();
    private static final UUID PRODUCT_A = UUID.randomUUID();
    private static final UUID PRODUCT_B = UUID.randomUUID();

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
    void sameKey_sameBody_twice_returnsIdenticalResponse() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String body = itemRequest(PRODUCT_A);

        MvcResult first = mockMvc.perform(post("/api/v1/basket/items")
                        .header("X-User-Name", USER_ID)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/v1/basket/items")
                        .header("X-User-Name", USER_ID)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // Only one item despite two POSTs
        mockMvc.perform(get("/api/v1/basket").header("X-User-Name", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void sameKey_differentBody_returns409() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/basket/items")
                        .header("X-User-Name", USER_ID)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemRequest(PRODUCT_A)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/basket/items")
                        .header("X-User-Name", USER_ID)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemRequest(PRODUCT_B)))
                .andExpect(status().isConflict());
    }

    @Test
    void missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/basket/items")
                        .header("X-User-Name", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemRequest(PRODUCT_A)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void differentKeys_addItemsIndependently() throws Exception {
        mockMvc.perform(post("/api/v1/basket/items")
                        .header("X-User-Name", USER_ID)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemRequest(PRODUCT_A)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/basket/items")
                        .header("X-User-Name", USER_ID)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemRequest(PRODUCT_B)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/basket").header("X-User-Name", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    private String itemRequest(UUID productId) {
        return """
                {"productId":"%s","restaurantId":"%s","productName":"Pizza","quantity":1,"price":"10.00"}
                """.formatted(productId, RESTAURANT_A);
    }
}
