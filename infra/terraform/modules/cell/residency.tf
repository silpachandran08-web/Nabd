# Residency allow-list per cloud and country (data, not provider code). Region lists are to be verified per ADR 0016 before use.
locals {
  residency = {
    aws = {
      IN = ["ap-south-1", "ap-south-2"]
      AE = ["me-central-1"]
      SA = []
      QA = []
    }
  }
  allowed_regions = local.residency[var.cloud][var.country]
}

resource "terraform_data" "residency" {
  lifecycle {
    precondition {
      condition     = contains(local.allowed_regions, var.region) && contains(local.allowed_regions, var.dr_region)
      error_message = "region and dr_region must both be inside the cell's country (residency allow-list in residency.tf)."
    }
  }
}
