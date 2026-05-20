output "vpc_id" {
  description = "ID of the VPC"
  value       = aws_vpc.this.id
}

output "vpc_cidr_block" {
  description = "CIDR block of the VPC"
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "IDs of the public subnets"
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "IDs of the private subnets"
  value       = aws_subnet.private[*].id
}

output "isolated_subnet_ids" {
  description = "IDs of the isolated (RDS) subnets"
  value       = aws_subnet.isolated[*].id
}

output "nat_gateway_ids" {
  description = "IDs of the NAT gateways"
  value       = aws_nat_gateway.this[*].id
}

output "default_deny_sg_id" {
  description = "ID of the base deny-all security group"
  value       = aws_security_group.default_deny.id
}

output "vpc_endpoint_sg_id" {
  description = "ID of the security group attached to interface VPC endpoints"
  value       = aws_security_group.vpc_endpoints.id
}
