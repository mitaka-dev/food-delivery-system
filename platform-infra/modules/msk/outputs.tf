output "cluster_arn" {
  description = "MSK Serverless cluster ARN"
  value       = aws_msk_serverless_cluster.this.arn
}

output "bootstrap_brokers_sasl_iam" {
  description = "MSK bootstrap endpoint for SASL/IAM (TLS) — use this in application config"
  value       = aws_msk_serverless_cluster.this.bootstrap_brokers_sasl_iam
}

output "glue_registry_arn" {
  description = "Glue Schema Registry ARN"
  value       = aws_glue_registry.this.arn
}

output "glue_registry_name" {
  description = "Glue Schema Registry name"
  value       = aws_glue_registry.this.registry_name
}

output "security_group_id" {
  description = "ID of the MSK security group"
  value       = aws_security_group.msk.id
}
