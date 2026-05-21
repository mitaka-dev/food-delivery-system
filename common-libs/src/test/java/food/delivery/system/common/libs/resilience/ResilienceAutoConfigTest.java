package food.delivery.system.common.libs.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = ResilienceAutoConfigTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // Exclude Redis auto-config (Spring Boot 4 class name) so no Redis connection is
        // attempted; IdempotencyKeyAspect's @ConditionalOnBean(StringRedisTemplate) then skips it.
        properties = "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
class ResilienceAutoConfigTest {

    @SpringBootApplication
    static class TestApp {
        @Bean
        TestService testService() {
            return new TestService();
        }
    }

    static class TestService {
        @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "test")
        public String call() {
            throw new RuntimeException("induced failure");
        }
    }

    @Autowired
    TestService testService;

    @Autowired
    CircuitBreakerRegistry registry;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void circuitOpensAfterFailureThreshold() {
        // Default config: minimumNumberOfCalls=5, failureRateThreshold=50%.
        // 10 consecutive failures → 100% failure rate → circuit OPEN.
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(testService::call).isInstanceOf(RuntimeException.class);
        }

        assertThat(registry.circuitBreaker("test").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        assertThat(meterRegistry.find("resilience4j.circuitbreaker.state")
                .tag("name", "test")
                .gauge())
                .isNotNull();
    }
}
