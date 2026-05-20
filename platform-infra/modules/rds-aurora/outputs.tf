output "cluster_endpoint" {
  description = "Writer endpoint — used by application services for reads/writes"
  value       = aws_rds_cluster.this.endpoint
}

output "reader_endpoint" {
  description = "Read-only load-balanced endpoint across all reader instances"
  value       = aws_rds_cluster.this.reader_endpoint
}

output "cluster_identifier" {
  description = "Aurora cluster identifier"
  value       = aws_rds_cluster.this.cluster_identifier
}

output "cluster_resource_id" {
  description = "Cluster resource ID — referenced in IAM auth policies for RDS IAM authentication"
  value       = aws_rds_cluster.this.cluster_resource_id
}

output "port" {
  description = "Database port (5432)"
  value       = aws_rds_cluster.this.port
}

output "master_user_secret_arn" {
  description = "Secrets Manager ARN holding the master credentials — consumed by External Secrets Operator at Step 0.11"
  value       = aws_rds_cluster.this.master_user_secret[0].secret_arn
}

output "security_group_id" {
  description = "Security group ID attached to the Aurora cluster"
  value       = aws_security_group.rds.id
}
