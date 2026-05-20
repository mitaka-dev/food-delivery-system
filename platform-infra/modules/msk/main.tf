locals {
  common_tags = merge(var.tags, {
    Environment = var.environment
    ManagedBy   = "terraform"
  })
}

# ── Security group ────────────────────────────────────────────────────────────
# Port 9098: MSK Serverless SASL/IAM over TLS (the only supported auth mode)

resource "aws_security_group" "msk" {
  name        = "${var.cluster_name}-msk-sg"
  description = "MSK Serverless access from within the VPC only"
  vpc_id      = var.vpc_id

  ingress {
    description = "Kafka SASL/IAM from VPC"
    from_port   = 9098
    to_port     = 9098
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

  tags = merge(local.common_tags, { Name = "${var.cluster_name}-msk-sg" })
}

# ── MSK Serverless cluster ────────────────────────────────────────────────────
# Serverless: no broker count/size to manage; auto-scales on throughput.
# At-rest encryption is always on (AWS-managed key — CMK not supported for serverless).
# In-transit TLS is always enforced.

resource "aws_msk_serverless_cluster" "this" {
  cluster_name = var.cluster_name

  vpc_config {
    subnet_ids         = var.private_subnet_ids
    security_group_ids = [aws_security_group.msk.id]
  }

  client_authentication {
    sasl {
      iam {
        enabled = true
      }
    }
  }

  tags = merge(local.common_tags, { Name = var.cluster_name })
}

# ── Glue Schema Registry ──────────────────────────────────────────────────────
# Producers/consumers reference schema ID — no embedded schema in each message.

resource "aws_glue_registry" "this" {
  registry_name = var.cluster_name
  description   = "Avro schema registry for ${var.cluster_name} Kafka topics"

  tags = local.common_tags
}
