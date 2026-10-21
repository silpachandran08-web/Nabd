# OPS-052: India cell. Primary Mumbai, DR Hyderabad. One state per environment:
#   tofu init -backend-config=backend-staging.hcl && tofu plan -var-file=staging.tfvars
terraform {
  required_version = ">= 1.8"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.81" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
  backend "s3" {} # bucket/key/lock table come from backend-<env>.hcl (created by bootstrap/aws)
}

variable "environment" { type = string }
variable "vpc_cidr" { type = string }
variable "db_instance_class" { type = string }
variable "dns_zone" { type = string }
variable "pgbouncer_ami_id" {
  type    = string
  default = "" # set after the AMI bake (infra/pgbouncer/AMI.md); empty fails the plan on purpose
}
variable "db_allocated_storage_gb" { type = number }
variable "db_max_allocated_storage_gb" { type = number }

# No credentials here: CI gets short-lived ones via GitHub OIDC (bootstrap/aws), operators via SSO.
provider "aws" {
  region = "ap-south-1"
}

provider "aws" {
  alias  = "dr"
  region = "ap-south-2"
}

module "cell" {
  source    = "../../modules/cell"
  providers = { aws = aws, aws.dr = aws.dr }

  cloud             = "aws"
  cell              = "in"
  country           = "IN"
  environment       = var.environment
  region            = "ap-south-1"
  dr_region         = "ap-south-2"
  azs               = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]
  vpc_cidr          = var.vpc_cidr
  db_instance_class = var.db_instance_class
  dns_zone          = var.dns_zone

  # OPS-055
  pgbouncer_ami_id            = var.pgbouncer_ami_id
  db_allocated_storage_gb     = var.db_allocated_storage_gb
  db_max_allocated_storage_gb = var.db_max_allocated_storage_gb
}

output "cell" { value = module.cell.cell }
output "postgres" { value = module.cell.postgres }
output "storage" { value = module.cell.storage }
output "valkey" { value = module.cell.valkey }
output "kms" { value = module.cell.kms }
output "dns" { value = module.cell.dns }
output "backup" { value = module.cell.backup }
output "dr" { value = module.cell.dr }
output "data_tier" { value = module.cell.data_tier }
