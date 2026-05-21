locals {
  common_tags = merge(var.tags, {
    Environment = var.environment
    ManagedBy   = "terraform"
  })
}

# ── Dead-letter queue ─────────────────────────────────────────────────────────

resource "aws_sqs_queue" "dlq" {
  name                      = "${var.name}-dlq"
  kms_master_key_id         = var.kms_key_arn
  message_retention_seconds = var.dlq_message_retention_seconds

  tags = merge(local.common_tags, { Name = "${var.name}-dlq" })
}

# ── Main queue ────────────────────────────────────────────────────────────────

resource "aws_sqs_queue" "this" {
  name                       = var.name
  kms_master_key_id          = var.kms_key_arn
  visibility_timeout_seconds = var.visibility_timeout_seconds
  message_retention_seconds  = var.message_retention_seconds

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = var.max_receive_count
  })

  tags = merge(local.common_tags, { Name = var.name })
}

# ── SNS topic ─────────────────────────────────────────────────────────────────

resource "aws_sns_topic" "this" {
  name              = var.name
  kms_master_key_id = var.kms_key_arn

  tags = merge(local.common_tags, { Name = var.name })
}

# ── SNS → SQS subscription ───────────────────────────────────────────────────

resource "aws_sns_topic_subscription" "this" {
  topic_arn = aws_sns_topic.this.arn
  protocol  = "sqs"
  endpoint  = aws_sqs_queue.this.arn
}

# ── SQS queue policy — allow SNS to publish ──────────────────────────────────
# Scoped to this specific topic ARN to prevent confused-deputy escalation.

resource "aws_sqs_queue_policy" "this" {
  queue_url = aws_sqs_queue.this.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowSNSPublish"
      Effect    = "Allow"
      Principal = { Service = "sns.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.this.arn
      Condition = {
        ArnEquals = { "aws:SourceArn" = aws_sns_topic.this.arn }
      }
    }]
  })
}

# ── CloudWatch alarm — DLQ depth > 0 ─────────────────────────────────────────

resource "aws_cloudwatch_metric_alarm" "dlq_depth" {
  alarm_name          = "${var.name}-dlq-depth"
  alarm_description   = "DLQ ${var.name}-dlq has messages — investigate failed consumer"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions

  dimensions = {
    QueueName = aws_sqs_queue.dlq.name
  }

  tags = local.common_tags
}
