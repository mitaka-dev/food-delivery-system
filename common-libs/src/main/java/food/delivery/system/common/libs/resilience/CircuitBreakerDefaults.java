package food.delivery.system.common.libs.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import java.time.Duration;

public final class CircuitBreakerDefaults {

    public static final int SLIDING_WINDOW_SIZE = 10;
    public static final int MINIMUM_NUMBER_OF_CALLS = 5;
    public static final float FAILURE_RATE_THRESHOLD = 50f;
    public static final Duration WAIT_IN_OPEN = Duration.ofSeconds(60);

    private CircuitBreakerDefaults() {}

    public static CircuitBreakerConfig config() {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(SLIDING_WINDOW_SIZE)
                .minimumNumberOfCalls(MINIMUM_NUMBER_OF_CALLS)
                .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                .waitDurationInOpenState(WAIT_IN_OPEN)
                .build();
    }
}
