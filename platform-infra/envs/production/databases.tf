module "rds" {
  source = "../../modules/rds-aurora"

  cluster_identifier  = "food-delivery-production"
  environment         = "production"
  vpc_id              = module.vpc.vpc_id
  isolated_subnet_ids = module.vpc.isolated_subnet_ids
  vpc_cidr_block      = module.vpc.vpc_cidr_block

  min_capacity          = 2
  max_capacity          = 32
  instance_count        = 2
  backup_retention_days = 35
  deletion_protection   = true

  tags = { Project = "food-delivery" }
}

output "rds_cluster_endpoint" {
  description = "Aurora writer endpoint — used by application services"
  value       = module.rds.cluster_endpoint
}

output "rds_reader_endpoint" {
  description = "Aurora reader endpoint"
  value       = module.rds.reader_endpoint
}

output "rds_master_user_secret_arn" {
  description = "Secrets Manager ARN for the master credentials"
  value       = module.rds.master_user_secret_arn
}
