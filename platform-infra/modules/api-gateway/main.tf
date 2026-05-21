locals {
  common_tags = merge(var.tags, {
    Environment = var.environment
    ManagedBy   = "terraform"
  })
}

# ── VPC Link security group ───────────────────────────────────────────────────
# The VPC Link initiates outbound connections to internal ALBs — only egress
# is needed here. ALB security groups must add an inbound rule allowing this SG.

resource "aws_security_group" "vpc_link" {
  name        = "${var.name}-vpc-link"
  description = "API Gateway VPC Link — egress to internal ALBs on port 80"
  vpc_id      = var.vpc_id

  egress {
    description = "ALB HTTP"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/8"]
  }

  tags = merge(local.common_tags, { Name = "${var.name}-vpc-link" })
}

# ── VPC Link ──────────────────────────────────────────────────────────────────

resource "aws_apigatewayv2_vpc_link" "this" {
  name               = var.name
  subnet_ids         = var.private_subnet_ids
  security_group_ids = [aws_security_group.vpc_link.id]

  tags = merge(local.common_tags, { Name = var.name })
}

# ── HTTP API ───────────────────────────────────────────────────────────────────
# CORS is configured at API level so preflight OPTIONS requests bypass the
# Lambda authorizer — clients won't be blocked by missing Authorization header.

resource "aws_apigatewayv2_api" "this" {
  name          = var.name
  protocol_type = "HTTP"

  cors_configuration {
    allow_methods  = ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"]
    allow_headers  = ["Authorization", "Content-Type", "X-Request-ID"]
    expose_headers = ["X-Request-ID"]
    max_age        = 86400
  }

  tags = merge(local.common_tags, { Name = var.name })
}

# ── Access log group ──────────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "access_logs" {
  name              = "/aws/apigateway/${var.name}"
  retention_in_days = var.access_log_retention_days

  tags = local.common_tags
}

# ── Default stage ─────────────────────────────────────────────────────────────

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = "$default"
  auto_deploy = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.access_logs.arn
    format = jsonencode({
      requestId          = "$context.requestId"
      ip                 = "$context.identity.sourceIp"
      requestTime        = "$context.requestTime"
      httpMethod         = "$context.httpMethod"
      routeKey           = "$context.routeKey"
      status             = "$context.status"
      responseLength     = "$context.responseLength"
      integrationLatency = "$context.integrationLatency"
      integrationError   = "$context.integrationErrorMessage"
      authorizerError    = "$context.authorizer.error"
    })
  }

  default_route_settings {
    throttling_burst_limit   = var.default_throttling_burst_limit
    throttling_rate_limit    = var.default_throttling_rate_limit
    detailed_metrics_enabled = true
  }

  tags = merge(local.common_tags, { Name = "${var.name}-default" })
}

# ── WAF Web ACL association ───────────────────────────────────────────────────

resource "aws_wafv2_web_acl_association" "this" {
  count = var.waf_acl_arn != "" ? 1 : 0

  resource_arn = aws_apigatewayv2_stage.default.arn
  web_acl_arn  = var.waf_acl_arn
}
