locals {
  common_tags = merge(var.tags, {
    Environment = var.environment
    ManagedBy   = "terraform"
  })
}

# ── DB subnet group (isolated subnets — no route to internet) ─────────────────

resource "aws_db_subnet_group" "this" {
  name        = "${var.cluster_identifier}-subnet-group"
  description = "Aurora subnet group for ${var.cluster_identifier}"
  subnet_ids  = var.isolated_subnet_ids

  tags = merge(local.common_tags, { Name = "${var.cluster_identifier}-subnet-group" })
}

# ── Security group ────────────────────────────────────────────────────────────

resource "aws_security_group" "rds" {
  name        = "${var.cluster_identifier}-rds-sg"
  description = "Aurora PostgreSQL access from within the VPC only"
  vpc_id      = var.vpc_id

  ingress {
    description = "PostgreSQL from VPC"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr_block]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
    description = "Allow all outbound"
  }

  tags = merge(local.common_tags, { Name = "${var.cluster_identifier}-rds-sg" })
}

# ── Aurora cluster ────────────────────────────────────────────────────────────
# engine_mode = "provisioned" is correct for Serverless v2.
# The legacy "serverless" engine_mode is Aurora Serverless v1 (deprecated).
# Serverless v2 scaling is driven by the db.serverless instance class below.

resource "aws_rds_cluster" "this" {
  cluster_identifier = var.cluster_identifier
  engine             = "aurora-postgresql"
  engine_version     = var.engine_version
  engine_mode        = "provisioned"

  serverlessv2_scaling_configuration {
    min_capacity = var.min_capacity
    max_capacity = var.max_capacity
  }

  # RDS generates the master password and stores it in Secrets Manager.
  # Rotation is managed by the RDS service — no Lambda required.
  manage_master_user_password = true
  master_username             = var.master_username

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  backup_retention_period         = var.backup_retention_days
  storage_encrypted               = true
  enabled_cloudwatch_logs_exports = ["postgresql"]

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = !var.deletion_protection
  final_snapshot_identifier = var.deletion_protection ? "${var.cluster_identifier}-final-snapshot" : null

  tags = merge(local.common_tags, { Name = var.cluster_identifier })

  lifecycle {
    # Aurora may increment engine_version during a maintenance window;
    # prevent Terraform from force-replacing the cluster on the next plan.
    ignore_changes = [engine_version]
  }
}

# ── Cluster instances ─────────────────────────────────────────────────────────
# instance_count = 1 → writer only (staging)
# instance_count = 2 → writer + reader in separate AZs (production multi-AZ)
# Aurora places instances across AZs automatically via the subnet group.

resource "aws_rds_cluster_instance" "this" {
  count = var.instance_count

  identifier         = "${var.cluster_identifier}-${count.index}"
  cluster_identifier = aws_rds_cluster.this.id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.this.engine
  engine_version     = aws_rds_cluster.this.engine_version

  performance_insights_enabled          = true
  performance_insights_retention_period = var.performance_insights_retention_days

  auto_minor_version_upgrade = true

  tags = merge(local.common_tags, { Name = "${var.cluster_identifier}-${count.index}" })
}
