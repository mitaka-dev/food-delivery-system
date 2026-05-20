module "vpc" {
  source = "../../modules/vpc"

  vpc_name    = "food-delivery-production"
  environment = "production"
  vpc_cidr    = "10.0.0.0/16"

  azs = ["eu-west-1a", "eu-west-1b"]

  public_subnet_cidrs   = ["10.0.1.0/24", "10.0.2.0/24"]
  private_subnet_cidrs  = ["10.0.16.0/22", "10.0.20.0/22"]
  isolated_subnet_cidrs = ["10.0.100.0/24", "10.0.101.0/24"]

  single_nat_gateway = true
}
