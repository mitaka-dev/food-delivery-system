module "redis" {
  source = "../../modules/elasticache-redis"

  cluster_id         = "food-delivery-production"
  environment        = "production"
  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
  vpc_cidr_block     = module.vpc.vpc_cidr_block

  tags = { Project = "food-delivery" }
}

output "redis_configuration_endpoint" {
  description = "Redis configuration endpoint (cluster mode) — use in app config"
  value       = module.redis.configuration_endpoint
}

output "redis_auth_token_secret_arn" {
  description = "Secrets Manager ARN for the Redis AUTH token"
  value       = module.redis.auth_token_secret_arn
}
