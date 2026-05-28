package food.delivery.system.product.service.api;

import food.delivery.system.product.service.entity.Product;
import food.delivery.system.product.service.enums.ProductCategory;
import food.delivery.system.product.service.record.ImageUploadResponse;
import food.delivery.system.product.service.repository.ProductRepository;
import food.delivery.system.product.service.service.ImageUploadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.cache.type=redis",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "management.tracing.sampling.probability=0.0"
})
class ProductControllerIT {

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

    @MockitoBean
    ImageUploadService imageUploadService;

    @Autowired
    WebApplicationContext wac;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    CacheManager cacheManager;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        cacheManager.getCache("products").get("__warmup__");
    }

    @AfterEach
    void teardown() {
        productRepository.deleteAll();
        cacheManager.getCache("products").clear();
    }

    @Test
    void updateProduct_updatesPrice_invalidatesCache() throws Exception {
        Product saved = productRepository.save(buildProduct("Margherita"));

        // Populate cache at the old price
        mockMvc.perform(get("/api/v1/products/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(9.99));

        // PATCH — cache is evicted before the DB write, new price persisted
        mockMvc.perform(patch("/api/v1/products/{id}", saved.getId())
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\": 15.99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(15.99));

        // Next GET must return the new price — stale cache would return 9.99
        mockMvc.perform(get("/api/v1/products/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(15.99));
    }

    @Test
    void updateProduct_withoutAdmin_returns403() throws Exception {
        Product saved = productRepository.save(buildProduct("Burger"));

        mockMvc.perform(patch("/api/v1/products/{id}", saved.getId())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\": 5.00}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getImageUploadUrl_returnsPresignedUrl() throws Exception {
        Product saved = productRepository.save(buildProduct("Tiramisu"));
        String fakeKey = "products/" + saved.getId() + "/abc123def.jpg";

        when(imageUploadService.generatePresignedUrl(saved.getId(), "abc123def"))
                .thenReturn(new ImageUploadResponse("https://s3.example.com/presigned", fakeKey));

        mockMvc.perform(post("/api/v1/products/{id}/image-upload-url", saved.getId())
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sha256\": \"abc123def\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").value("https://s3.example.com/presigned"))
                .andExpect(jsonPath("$.key").value(fakeKey));
    }

    @Test
    void getImageUploadUrl_withoutAdmin_returns403() throws Exception {
        Product saved = productRepository.save(buildProduct("Sorbet"));

        mockMvc.perform(post("/api/v1/products/{id}/image-upload-url", saved.getId())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sha256\": \"abc123def\"}"))
                .andExpect(status().isForbidden());
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
