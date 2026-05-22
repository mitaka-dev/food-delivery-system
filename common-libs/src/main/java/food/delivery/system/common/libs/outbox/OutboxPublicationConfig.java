package food.delivery.system.common.libs.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.IncompleteEventPublications;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Auto-configuration for Spring Modulith's transactional outbox.
 *
 * Activates only when spring-modulith-events-jdbc is on the classpath. Services that import
 * common-libs and want the outbox must also declare spring-modulith-events-jdbc (+ -kafka
 * and/or -sqs for their externalizers) in their own pom.xml.
 *
 * Usage pattern in a service:
 * <pre>
 *   // 1. Annotate the event record for externalization
 *   {@literal @}Externalized("user-events")  // → Kafka topic
 *   public record UserCreatedEvent(UUID userId, String email) {}
 *
 *   // 2. Publish inside a @Transactional method — stored atomically, delivered after commit
 *   {@literal @}Transactional
 *   public void register(CreateUserRequest req) {
 *       User user = userRepository.save(new User(req));
 *       events.publishEvent(new UserCreatedEvent(user.getId(), user.getEmail()));
 *   }
 * </pre>
 *
 * Required service config (application.yml):
 * <pre>
 *   spring:
 *     modulith:
 *       events:
 *         jdbc:
 *           schema-initialization:
 *             enabled: true   # auto-creates event_publication table (default: true)
 *         republish-outstanding-events-on-restart: true
 *     kafka:
 *       producer:
 *         acks: all
 *         enable-idempotence: true
 *         # Wire trace propagation (reads MDC traceId/spanId set by micrometer-tracing):
 *         properties:
 *           interceptor.classes: food.delivery.system.common.libs.outbox.OutboxTraceInterceptor
 * </pre>
 */
@Configuration
@ConditionalOnClass(IncompleteEventPublications.class)
@ConditionalOnBean(DataSource.class)
public class OutboxPublicationConfig {

    /**
     * Default routing: only externalize events explicitly marked with @Externalized.
     * Services override this bean with @ConditionalOnMissingBean if they need custom routing.
     */
    @Bean
    @ConditionalOnMissingBean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .select(EventExternalizationConfiguration.annotatedAsExternalized())
                .build();
    }

    /**
     * Resubmits publications that were persisted but not dispatched (e.g. after a pod crash).
     * Runs once at startup; the scheduler then handles ongoing republication at its interval.
     */
    @Bean
    ApplicationListener<ApplicationStartedEvent> resubmitIncompletePublications(
            IncompleteEventPublications publications) {
        return event -> publications.resubmitIncompletePublicationsOlderThan(Duration.ofSeconds(10));
    }
}
