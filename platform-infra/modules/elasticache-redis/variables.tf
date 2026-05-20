variable "cluster_id" {
  type        = string
  description = "Identifier for the ElastiCache replication group"
}

variable "environment" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "vpc_cidr_block" {
  type        = string
  description = "VPC CIDR — Redis SG allows inbound 6379 from this range"
}

variable "node_type" {
  type    = string
  default = "cache.t4g.micro"
}

variable "redis_version" {
  type    = string
  default = "7.1"
}

variable "num_shards" {
  type    = number
  default = 2
}

variable "replicas_per_shard" {
  type    = number
  default = 1
}

variable "snapshot_retention_days" {
  type    = number
  default = 1
}

variable "cloudwatch_log_retention_days" {
  type    = number
  default = 30
}

variable "tags" {
  type    = map(string)
  default = {}
}
