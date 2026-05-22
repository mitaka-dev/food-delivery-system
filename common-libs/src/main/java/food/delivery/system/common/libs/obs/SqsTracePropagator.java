package food.delivery.system.common.libs.obs;

import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Injects and extracts traceId from SQS message attributes.
 *
 * SQS message attributes are simpler than W3C traceparent — we store the raw traceId
 * as a String attribute. Consumers restore the correlation context from it.
 */
public class SqsTracePropagator {

    private static final String TRACE_ID_ATTR = "traceId";

    /**
     * Add the traceId message attribute to an outgoing SQS request builder.
     * Returns the builder unchanged if traceId is null.
     */
    public static SendMessageRequest.Builder inject(SendMessageRequest.Builder builder,
                                                    String traceId) {
        if (traceId == null || traceId.isBlank()) return builder;
        Map<String, MessageAttributeValue> attrs = new HashMap<>();
        attrs.put(TRACE_ID_ATTR, MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(traceId)
                .build());
        return builder.messageAttributes(attrs);
    }

    /**
     * Extract the traceId from an incoming SQS message, or null if the attribute is absent.
     */
    public static String extract(Message message) {
        MessageAttributeValue attr = message.messageAttributes().get(TRACE_ID_ATTR);
        return attr != null ? attr.stringValue() : null;
    }
}
