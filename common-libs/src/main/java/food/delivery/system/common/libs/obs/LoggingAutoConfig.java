package food.delivery.system.common.libs.obs;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Auto-configuration for structured logging and trace context propagation.
 *
 * Activates when spring-web is on the classpath (i.e., any web service).
 * Registers TraceContextFilter to copy X-User-Id → MDC.userId for every request.
 *
 * traceId/spanId are populated into MDC automatically by micrometer-tracing-bridge-otel
 * once a service configures management.tracing.*. No additional wiring needed here.
 *
 * Services opt into JSON logging by including logback-obs-json.xml in their logback-spring.xml.
 */
@AutoConfiguration
@ConditionalOnClass(OncePerRequestFilter.class)
@ConditionalOnWebApplication
public class LoggingAutoConfig {

    @Bean
    TraceContextFilter traceContextFilter() {
        return new TraceContextFilter();
    }
}
