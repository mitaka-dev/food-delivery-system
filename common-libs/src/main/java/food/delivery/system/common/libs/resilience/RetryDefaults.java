package food.delivery.system.common.libs.resilience;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;

public final class RetryDefaults {

    public static final int MAX_ATTEMPTS = 3;
    public static final long INITIAL_INTERVAL_MS = 100;
    public static final double MULTIPLIER = 2.0;
    public static final double RANDOMIZATION_FACTOR = 0.5;
    public static final long MAX_INTERVAL_MS = 1_000;

    private RetryDefaults() {}

    public static RetryConfig config() {
        return RetryConfig.custom()
                .maxAttempts(MAX_ATTEMPTS)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        INITIAL_INTERVAL_MS, MULTIPLIER, RANDOMIZATION_FACTOR, MAX_INTERVAL_MS))
                .build();
    }
}
