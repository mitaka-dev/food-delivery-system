output "configuration_endpoint" {
  description = "Redis cluster configuration endpoint — clients must use this in cluster mode (not primary endpoint)"
  value       = aws_elasticache_replication_group.this.configuration_endpoint_address
}

output "port" {
  description = "Redis port"
  value       = aws_elasticache_replication_group.this.port
}

output "auth_token_secret_arn" {
  description = "Secrets Manager ARN containing the Redis AUTH token"
  value       = aws_secretsmanager_secret.redis_auth_token.arn
}

output "security_group_id" {
  description = "ID of the Redis security group"
  value       = aws_security_group.redis.id
}

output "replication_group_id" {
  description = "ElastiCache replication group ID"
  value       = aws_elasticache_replication_group.this.id
}
