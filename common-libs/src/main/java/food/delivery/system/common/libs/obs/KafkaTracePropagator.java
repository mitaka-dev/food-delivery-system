package food.delivery.system.common.libs.obs;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;

/**
 * Injects and extracts W3C traceparent headers on Kafka records.
 *
 * Format: "00-{32-hex traceId}-{16-hex spanId}-01"
 *
 * Inject side: called by KafkaOutboxDispatcher using the traceId/spanId stored in the outbox row
 * at the time of the original request (captured from MDC by the writing service).
 *
 * Extract side: called by Kafka @KafkaListener methods to restore trace context on the consumer.
 */
public class KafkaTracePropagator {

    private static final String TRACEPARENT_HEADER = "traceparent";

    /**
     * Inject a traceparent header into the producer record using stored trace coordinates.
     * No-op if traceId is null (spans without tracing configured pass through cleanly).
     */
    public static void inject(ProducerRecord<?, ?> record, String traceId, String spanId) {
        if (traceId == null || traceId.isBlank()) return;
        String parentId = (spanId != null && spanId.length() == 16) ? spanId
                : traceId.substring(traceId.length() - 16);
        String traceparent = "00-" + traceId + "-" + parentId + "-01";
        record.headers().add(TRACEPARENT_HEADER, traceparent.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Extract the traceparent header value from a consumer record, or null if absent.
     */
    public static String extract(ConsumerRecord<?, ?> record) {
        Header h = record.headers().lastHeader(TRACEPARENT_HEADER);
        return h != null ? new String(h.value(), StandardCharsets.UTF_8) : null;
    }

    /**
     * Parse the traceId portion from a W3C traceparent value, or null on parse failure.
     */
    public static String parseTraceId(String traceparent) {
        if (traceparent == null) return null;
        String[] parts = traceparent.split("-");
        return parts.length >= 2 ? parts[1] : null;
    }
}
