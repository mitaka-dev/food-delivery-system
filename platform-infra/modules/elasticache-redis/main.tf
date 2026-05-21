locals {
  common_tags = merge(var.tags, {
    Environment = var.environment
    ManagedBy   = "terraform"
  })
}

# ── Subnet group (private subnets — have internet egress via NAT) ─────────────

resource "aws_elasticache_subnet_group" "this" {
  name        = "${var.cluster_id}-subnet-group"
  description = "ElastiCache subnet group for ${var.cluster_id}"
  subnet_ids  = var.private_subnet_ids

  tags = merge(local.common_tags, { Name = "${var.cluster_id}-subnet-group" })
}

# ── Security group ────────────────────────────────────────────────────────────

resource "aws_security_group" "redis" {
  name        = "${var.cluster_id}-redis-sg"
  description = "Redis access from within the VPC only"
  vpc_id      = var.vpc_id

  ingress {
    description = "Redis from VPC"
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr_block]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, { Name = "${var.cluster_id}-redis-sg" })
}

# ── CloudWatch log groups ─────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "slow_log" {
  name              = "/aws/elasticache/${var.cluster_id}/slow-log"
  retention_in_days = var.cloudwatch_log_retention_days

  tags = local.common_tags
}

resource "aws_cloudwatch_log_group" "engine_log" {
  name              = "/aws/elasticache/${var.cluster_id}/engine-log"
  retention_in_days = var.cloudwatch_log_retention_days

  tags = local.common_tags
}

# ── AUTH token → Secrets Manager ─────────────────────────────────────────────

resource "random_password" "auth_token" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "redis_auth_token" {
  name        = "food-delivery/${var.environment}/redis-auth-token"
  description = "Redis AUTH token for ${var.cluster_id}"

  tags = local.common_tags
}

resource "aws_secretsmanager_secret_version" "redis_auth_token" {
  secret_id     = aws_secretsmanager_secret.redis_auth_token.id
  secret_string = random_password.auth_token.result
}

# ── ElastiCache replication group (Redis cluster mode) ────────────────────────
# Cluster mode enabled = keyspace sharded across num_node_groups.
# Clients must connect via configuration_endpoint_address, not primary_endpoint.

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.cluster_id
  description          = "Redis cluster for ${var.environment}"

  engine         = "redis"
  engine_version = var.redis_version
  node_type      = var.node_type
  port           = 6379

  # Cluster mode requires the .cluster.on parameter group family
  parameter_group_name = "default.redis7.cluster.on"

  num_node_groups         = var.num_shards
  replicas_per_node_group = var.replicas_per_shard

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  transit_encryption_mode    = "required"
  auth_token                 = random_password.auth_token.result

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.redis.id]

  snapshot_retention_limit = var.snapshot_retention_days
  snapshot_window          = "02:00-03:00"
  maintenance_window       = "sun:05:00-sun:06:00"

  auto_minor_version_upgrade = true
  apply_immediately          = false

  log_delivery_configuration {
    destination      = aws_cloudwatch_log_group.slow_log.name
    destination_type = "cloudwatch-logs"
    log_format       = "json"
    log_type         = "slow-log"
  }

  log_delivery_configuration {
    destination      = aws_cloudwatch_log_group.engine_log.name
    destination_type = "cloudwatch-logs"
    log_format       = "json"
    log_type         = "engine-log"
  }

  tags = merge(local.common_tags, { Name = var.cluster_id })

  lifecycle {
    ignore_changes = [engine_version]
  }
}
