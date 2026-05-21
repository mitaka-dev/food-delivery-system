locals {
  # Update when domain is registered. ACM cert will stay PENDING_VALIDATION
  # until the DNS CNAME records from aws_acm_certificate.api.domain_validation_options
  # are added to the DNS provider.
  api_domain_name = "api-production.food-delivery-platform.io"
}

# ── WAF Web ACL ───────────────────────────────────────────────────────────────
# REGIONAL scope — attached to the API Gateway stage in the same region.
# Priority order: managed rules (1-3) evaluated before the rate limit (10).

resource "aws_wafv2_web_acl" "api" {
  name  = "food-delivery-production-api"
  scope = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "AWSManagedRulesCommonRuleSet"
    priority = 1

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "AWSManagedRulesCommonRuleSet"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "AWSManagedRulesKnownBadInputsRuleSet"
    priority = 2

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "AWSManagedRulesKnownBadInputsRuleSet"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "AWSManagedRulesSQLiRuleSet"
    priority = 3

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesSQLiRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "AWSManagedRulesSQLiRuleSet"
      sampled_requests_enabled   = true
    }
  }

  # 2000 requests per 5-minute window (evaluation_window_sec = 300) per IP.
  rule {
    name     = "RateLimitPerIP"
    priority = 10

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit                 = 2000
        aggregate_key_type    = "IP"
        evaluation_window_sec = 300
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "RateLimitPerIP"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "food-delivery-api-waf"
    sampled_requests_enabled   = true
  }

  tags = {
    Project     = "food-delivery"
    Environment = "production"
  }
}

# ── Lambda IAM roles ──────────────────────────────────────────────────────────

resource "aws_iam_role" "lambda_health" {
  name = "food-delivery-production-lambda-health"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "lambda_health_basic" {
  role       = aws_iam_role.lambda_health.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role" "lambda_authorizer" {
  name = "food-delivery-production-lambda-authorizer"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "lambda_authorizer_basic" {
  role       = aws_iam_role.lambda_authorizer.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy" "lambda_authorizer_ssm" {
  name = "food-delivery-authorizer-ssm"
  role = aws_iam_role.lambda_authorizer.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameter"]
      Resource = "arn:aws:ssm:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:parameter/food-delivery/jwt-public-key"
    }]
  })
}

# ── Lambda — health check ─────────────────────────────────────────────────────

data "archive_file" "health" {
  type        = "zip"
  output_path = "${path.module}/lambda-health.zip"

  source {
    filename = "index.js"
    content  = <<-JS
      exports.handler = async () => ({
        statusCode: 200,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status: "healthy", service: "food-delivery-platform" })
      });
    JS
  }
}

resource "aws_lambda_function" "health" {
  function_name    = "food-delivery-production-health"
  role             = aws_iam_role.lambda_health.arn
  runtime          = "nodejs20.x"
  architectures    = ["arm64"]
  handler          = "index.handler"
  filename         = data.archive_file.health.output_path
  source_code_hash = data.archive_file.health.output_base64sha256
  timeout          = 5
  memory_size      = 128

  tags = {
    Project     = "food-delivery"
    Environment = "production"
  }
}

# ── Lambda — JWT authorizer ───────────────────────────────────────────────────
# Uses Node.js built-in crypto.createVerify (no external packages) and
# @aws-sdk/client-ssm (bundled in Node.js 20 runtime). The public key is
# cached in the module-level variable across warm invocations to avoid
# an SSM call on every request.

data "archive_file" "authorizer" {
  type        = "zip"
  output_path = "${path.module}/lambda-authorizer.zip"

  source {
    filename = "index.js"
    content  = <<-JS
      const { SSMClient, GetParameterCommand } = require("@aws-sdk/client-ssm");
      const crypto = require("crypto");

      const ssm = new SSMClient({});
      let cachedPublicKey = null;

      exports.handler = async (event) => {
        const authHeader = event.headers ? (event.headers.authorization || "") : "";
        const token = authHeader.replace(/^Bearer\s+/i, "").trim();
        if (!token) return { isAuthorized: false };

        try {
          if (!cachedPublicKey) {
            const res = await ssm.send(new GetParameterCommand({ Name: "/food-delivery/jwt-public-key" }));
            cachedPublicKey = res.Parameter.Value;
          }

          const parts = token.split(".");
          if (parts.length !== 3) return { isAuthorized: false };

          const headerB64 = parts[0];
          const payloadB64 = parts[1];
          const sigB64 = parts[2];
          const sig = Buffer.from(sigB64.replace(/-/g, "+").replace(/_/g, "/"), "base64");

          const verifier = crypto.createVerify("RSA-SHA256");
          verifier.update(headerB64 + "." + payloadB64);
          if (!verifier.verify(cachedPublicKey, sig)) return { isAuthorized: false };

          const payload = JSON.parse(Buffer.from(payloadB64, "base64url").toString());
          if (payload.exp && Math.floor(Date.now() / 1000) > payload.exp) return { isAuthorized: false };

          return {
            isAuthorized: true,
            context: { userId: String(payload.sub || ""), roles: JSON.stringify(payload.roles || []) }
          };
        } catch (err) {
          return { isAuthorized: false };
        }
      };
    JS
  }
}

resource "aws_lambda_function" "authorizer" {
  function_name    = "food-delivery-production-authorizer"
  role             = aws_iam_role.lambda_authorizer.arn
  runtime          = "nodejs20.x"
  architectures    = ["arm64"]
  handler          = "index.handler"
  filename         = data.archive_file.authorizer.output_path
  source_code_hash = data.archive_file.authorizer.output_base64sha256
  timeout          = 10
  memory_size      = 128

  tags = {
    Project     = "food-delivery"
    Environment = "production"
  }
}

# ── SSM — JWT public key placeholder ─────────────────────────────────────────
# Replaced with the real PEM-encoded RSA public key when user-service is
# deployed (Step 2.x). lifecycle.ignore_changes ensures a subsequent apply
# does not overwrite a value that was set manually.

resource "aws_ssm_parameter" "jwt_public_key" {
  name        = "/food-delivery/jwt-public-key"
  type        = "String"
  value       = "PLACEHOLDER — replace with PEM-encoded RSA public key from user-service"
  description = "RSA public key for JWT RS256 signature verification"

  lifecycle {
    ignore_changes = [value]
  }

  tags = { Project = "food-delivery" }
}

# ── API Gateway ───────────────────────────────────────────────────────────────

module "api_gateway" {
  source = "../../modules/api-gateway"

  name        = "food-delivery-production"
  environment = "production"

  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids

  waf_acl_arn                    = aws_wafv2_web_acl.api.arn
  default_throttling_burst_limit = 1000
  default_throttling_rate_limit  = 500
  access_log_retention_days      = 30

  tags = { Project = "food-delivery" }
}

# ── Lambda permissions ────────────────────────────────────────────────────────
# source_arn scoped to this API's execution ARN — prevents confused-deputy
# attacks where another API triggers this Lambda.

resource "aws_lambda_permission" "health_apigw" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.health.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${module.api_gateway.execution_arn}/*/*"
}

resource "aws_lambda_permission" "authorizer_apigw" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.authorizer.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${module.api_gateway.execution_arn}/*"
}

# ── JWT Lambda authorizer ─────────────────────────────────────────────────────
# Payload format 2.0 — Lambda returns { isAuthorized, context } (simpler than
# the IAM policy document format used by v1.0). 5-minute result cache reduces
# Lambda invocations for repeat requests from the same token.

resource "aws_apigatewayv2_authorizer" "jwt" {
  api_id                            = module.api_gateway.api_id
  name                              = "jwt"
  authorizer_type                   = "REQUEST"
  authorizer_uri                    = aws_lambda_function.authorizer.invoke_arn
  identity_sources                  = ["$request.header.Authorization"]
  authorizer_payload_format_version = "2.0"
  authorizer_result_ttl_in_seconds  = 300
  enable_simple_responses           = true
}

# ── Health route ──────────────────────────────────────────────────────────────
# No authorizer — the health endpoint must be reachable without a JWT so
# load balancer health checks and liveness probes can call it.

resource "aws_apigatewayv2_integration" "health" {
  api_id                 = module.api_gateway.api_id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.health.invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_route" "health" {
  api_id    = module.api_gateway.api_id
  route_key = "GET /health"
  target    = "integrations/${aws_apigatewayv2_integration.health.id}"
}

# ── ACM certificate ───────────────────────────────────────────────────────────
# Stays PENDING_VALIDATION until the CNAME records in
# aws_acm_certificate.api.domain_validation_options are added to the DNS
# provider for local.api_domain_name.

resource "aws_acm_certificate" "api" {
  domain_name       = local.api_domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Project     = "food-delivery"
    Environment = "production"
    Name        = "food-delivery-api-production"
  }
}

# ── Custom domain + API mapping ───────────────────────────────────────────────

resource "aws_apigatewayv2_domain_name" "api" {
  domain_name = local.api_domain_name

  domain_name_configuration {
    certificate_arn = aws_acm_certificate.api.arn
    endpoint_type   = "REGIONAL"
    security_policy = "TLS_1_2"
  }

  tags = {
    Project     = "food-delivery"
    Environment = "production"
    Name        = "food-delivery-api-production"
  }
}

resource "aws_apigatewayv2_api_mapping" "api" {
  api_id      = module.api_gateway.api_id
  domain_name = aws_apigatewayv2_domain_name.api.id
  stage       = module.api_gateway.stage_id
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "api_gateway_endpoint" {
  description = "Default execute-api URL — reachable immediately without DNS"
  value       = module.api_gateway.api_endpoint
}

output "api_gateway_id" {
  description = "API Gateway ID — used when adding routes in service phases"
  value       = module.api_gateway.api_id
}

output "vpc_link_id" {
  description = "VPC Link ID — set as connection_id on ALB-backed integrations"
  value       = module.api_gateway.vpc_link_id
}

output "vpc_link_sg_id" {
  description = "VPC Link SG ID — add to inbound rules of service ALB security groups"
  value       = module.api_gateway.vpc_link_sg_id
}

output "waf_acl_arn" {
  description = "WAF Web ACL ARN"
  value       = aws_wafv2_web_acl.api.arn
}

output "api_custom_domain_target" {
  description = "CNAME target — add as ALIAS or CNAME record for local.api_domain_name in Route53"
  value       = aws_apigatewayv2_domain_name.api.domain_name_configuration[0].target_domain_name
}

output "acm_validation_options" {
  description = "DNS records required to validate the ACM certificate"
  value = [for opt in aws_acm_certificate.api.domain_validation_options : {
    name  = opt.resource_record_name
    type  = opt.resource_record_type
    value = opt.resource_record_value
  }]
}
