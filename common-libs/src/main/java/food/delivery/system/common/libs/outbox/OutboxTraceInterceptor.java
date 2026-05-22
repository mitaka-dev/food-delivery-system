package food.delivery.system.common.libs.outbox;

import food.delivery.system.common.libs.obs.KafkaTracePropagator;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Kafka ProducerInterceptor that injects W3C traceparent headers using MDC trace context.
 *
 * Spring Modulith delivers events via @TransactionalEventListener(AFTER_COMMIT) in the same
 * thread that committed the transaction, so MDC values set by micrometer-tracing are still
 * present when this interceptor runs.
 *
 * Wire it in application.yml:
 * <pre>
 *   spring.kafka.producer.properties.interceptor.classes: \
 *     food.delivery.system.common.libs.outbox.OutboxTraceInterceptor
 * </pre>
 */
public class OutboxTraceInterceptor implements ProducerInterceptor<Object, Object> {

    @Override
    public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
        KafkaTracePropagator.inject(record, MDC.get("traceId"), MDC.get("spanId"));
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {}

    @Override
    public void close() {}

    @Override
    public void configure(Map<String, ?> configs) {}
}
