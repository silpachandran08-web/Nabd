variable "name" { type = string }
variable "tags" { type = map(string) }
variable "region" { type = string }
variable "dr_region" { type = string }
variable "azs" { type = list(string) }
variable "vpc_cidr" { type = string }
variable "db_instance_class" { type = string }
variable "db_allocated_storage_gb" { type = number }
variable "audit_lock_days" { type = number }
variable "dns_zone" { type = string }
variable "pitr_lag_alarm_seconds" { type = number }

data "aws_caller_identity" "current" {}

locals {
  account = data.aws_caller_identity.current.account_id
  # Single-region countries (e.g. AE on AWS) keep DR copies in-region: KMS replicas and RDS backup replication need a second region.
  cross_region = var.dr_region != var.region
}
