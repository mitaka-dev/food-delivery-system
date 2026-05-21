package food.delivery.system.common.libs.resilience;

import io.github.resilience4j.timelimiter.TimeLimiterConfig;

import java.time.Duration;

public final class TimeoutDefaults {

    // Single timeout covering the full operation. HTTP connect/read timeouts
    // are configured separately per service's HTTP client (e.g., RestClient).
    public static final Duration TIMEOUT = Duration.ofSeconds(5);

    private TimeoutDefaults() {}

    public static TimeLimiterConfig config() {
        return TimeLimiterConfig.custom()
                .timeoutDuration(TIMEOUT)
                .cancelRunningFuture(true)
                .build();
    }
}
