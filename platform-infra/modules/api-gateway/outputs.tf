output "api_id" {
  description = "HTTP API Gateway ID"
  value       = aws_apigatewayv2_api.this.id
}

output "api_endpoint" {
  description = "Default API Gateway endpoint URL (execute-api.amazonaws.com) — usable before DNS is configured"
  value       = aws_apigatewayv2_api.this.api_endpoint
}

output "execution_arn" {
  description = "Execution ARN — scope Lambda permissions to this API with /*/*"
  value       = aws_apigatewayv2_api.this.execution_arn
}

output "stage_id" {
  description = "Default stage ID — used in aws_apigatewayv2_api_mapping"
  value       = aws_apigatewayv2_stage.default.id
}

output "stage_arn" {
  description = "Default stage ARN"
  value       = aws_apigatewayv2_stage.default.arn
}

output "vpc_link_id" {
  description = "VPC Link ID — reference in ALB-backed route integrations"
  value       = aws_apigatewayv2_vpc_link.this.id
}

output "vpc_link_sg_id" {
  description = "VPC Link security group ID — ALB SGs must allow inbound from this SG on port 80"
  value       = aws_security_group.vpc_link.id
}
