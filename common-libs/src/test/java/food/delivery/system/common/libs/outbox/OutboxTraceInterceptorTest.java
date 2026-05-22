package food.delivery.system.common.libs.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for OutboxTraceInterceptor.
 *
 * Verifies that W3C traceparent is injected from MDC into Kafka ProducerRecord headers.
 * End-to-end propagation through Spring Modulith's async executor requires MDC context
 * propagation configured at the service level (MDCTaskDecorator on AsyncConfigurer).
 */
class OutboxTraceInterceptorTest {

    private final OutboxTraceInterceptor interceptor = new OutboxTraceInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void injectsTraceparentHeader_whenMdcHasTraceContext() {
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");

        ProducerRecord<Object, Object> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        var header = record.headers().lastHeader("traceparent");
        assertThat(header).isNotNull();
        assertThat(new String(header.value()))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    void noHeader_whenMdcHasNoTraceContext() {
        ProducerRecord<Object, Object> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        assertThat(record.headers().lastHeader("traceparent")).isNull();
    }

    @Test
    void returnsRecord_unchanged_exceptForHeader() {
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");

        ProducerRecord<Object, Object> record = new ProducerRecord<>("my-topic", "my-key", "my-value");
        ProducerRecord<Object, Object> result = interceptor.onSend(record);

        assertThat(result).isSameAs(record);
        assertThat(result.topic()).isEqualTo("my-topic");
        assertThat(result.key()).isEqualTo("my-key");
        assertThat(result.value()).isEqualTo("my-value");
    }
}
