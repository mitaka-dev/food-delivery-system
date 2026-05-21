# ── Glue Avro schemas (v1 topics) ────────────────────────────────────────────
# Compatibility = BACKWARD: new schema versions can read data written by the
# previous version. In practice: you may add optional fields, never remove them.
#
# Actual Kafka topics (user-events, order-events, payment-events) are created
# by scripts/bootstrap-kafka-topics.sh run once from an EKS pod after apply.

resource "aws_glue_schema" "user_events" {
  registry_arn  = aws_glue_registry.this.arn
  schema_name   = "UserEvent"
  data_format   = "AVRO"
  compatibility = "BACKWARD"

  schema_definition = jsonencode({
    type      = "record"
    name      = "UserEvent"
    namespace = "com.fooddelivery.user"
    fields = [
      { name = "eventId",   type = "string" },
      { name = "eventType", type = { type = "enum", name = "UserEventType", symbols = ["USER_REGISTERED", "USER_UPDATED", "USER_DELETED"] } },
      { name = "userId",    type = "string" },
      { name = "email",     type = "string" },
      { name = "timestamp", type = { type = "long", logicalType = "timestamp-millis" } }
    ]
  })
}

resource "aws_glue_schema" "order_events" {
  registry_arn  = aws_glue_registry.this.arn
  schema_name   = "OrderEvent"
  data_format   = "AVRO"
  compatibility = "BACKWARD"

  schema_definition = jsonencode({
    type      = "record"
    name      = "OrderEvent"
    namespace = "com.fooddelivery.order"
    fields = [
      { name = "eventId",     type = "string" },
      { name = "eventType",   type = { type = "enum", name = "OrderEventType", symbols = ["ORDER_CREATED", "ORDER_CONFIRMED", "ORDER_CANCELLED", "ORDER_DELIVERED"] } },
      { name = "orderId",     type = "string" },
      { name = "userId",      type = "string" },
      { name = "totalAmount", type = "double" },
      { name = "timestamp",   type = { type = "long", logicalType = "timestamp-millis" } }
    ]
  })
}

resource "aws_glue_schema" "payment_events" {
  registry_arn  = aws_glue_registry.this.arn
  schema_name   = "PaymentEvent"
  data_format   = "AVRO"
  compatibility = "BACKWARD"

  schema_definition = jsonencode({
    type      = "record"
    name      = "PaymentEvent"
    namespace = "com.fooddelivery.payment"
    fields = [
      { name = "eventId",   type = "string" },
      { name = "eventType", type = { type = "enum", name = "PaymentEventType", symbols = ["PAYMENT_INITIATED", "PAYMENT_SUCCEEDED", "PAYMENT_FAILED", "PAYMENT_REFUNDED"] } },
      { name = "paymentId", type = "string" },
      { name = "orderId",   type = "string" },
      { name = "amount",    type = "double" },
      { name = "timestamp", type = { type = "long", logicalType = "timestamp-millis" } }
    ]
  })
}
