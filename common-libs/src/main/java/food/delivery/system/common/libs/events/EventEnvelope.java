package food.delivery.system.common.libs.events;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("traceId") String traceId,
        @JsonProperty("occurredAt") Instant occurredAt,
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("payload") T payload
) {
    public static <T> EventEnvelope<T> of(T payload, String traceId) {
        return new EventEnvelope<>(UUID.randomUUID(), traceId, Instant.now(), 1, payload);
    }
}
