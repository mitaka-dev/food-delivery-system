package food.delivery.system.common.libs.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void roundTrip_withFieldErrors() throws Exception {
        var error = new ApiError(400, "Bad Request", "VALIDATION_ERROR",
                "Invalid input", Instant.now(), "/api/products", "trace-123",
                List.of(new FieldError("name", "ab", "minimum 3 characters")));

        String json = mapper.writeValueAsString(error);
        ApiError result = mapper.readValue(json, ApiError.class);

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(result.fieldErrors()).hasSize(1);
        assertThat(result.fieldErrors().get(0).field()).isEqualTo("name");
    }

    @Test
    void nullFieldErrors_excludedFromJson() throws Exception {
        var error = new ApiError(404, "Not Found", "NOT_FOUND",
                "Resource not found", Instant.now(), "/api/items/1", "trace-456", null);

        String json = mapper.writeValueAsString(error);

        assertThat(json).doesNotContain("fieldErrors");
    }
}
