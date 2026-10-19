locals {
  # Every resource carries these plus its own data_class tag (cost and residency audits).
  tags = {
    cell        = var.cell
    service     = var.service
    country     = var.country
    environment = var.environment
    managed_by  = "terraform"
  }
  name = "nabd-${var.cell}-${var.environment}"
}

module "aws" {
  source     = "./aws"
  count      = var.cloud == "aws" ? 1 : 0
  depends_on = [terraform_data.residency]
  providers = {
    aws    = aws
    aws.dr = aws.dr
  }

  name                    = local.name
  tags                    = local.tags
  region                  = var.region
  dr_region               = var.dr_region
  azs                     = var.azs
  vpc_cidr                = var.vpc_cidr
  db_instance_class       = var.db_instance_class
  db_allocated_storage_gb = var.db_allocated_storage_gb
  audit_lock_days         = var.audit_lock_days
  dns_zone                = var.dns_zone
  pitr_lag_alarm_seconds  = var.pitr_lag_alarm_seconds
}
