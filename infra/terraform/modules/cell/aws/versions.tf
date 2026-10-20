# AWS implementation of the cell contract (one DEC-04 option). `aws` = primary region, `aws.dr` = DR region.
terraform {
  required_version = ">= 1.8"
  required_providers {
    aws = {
      source                = "hashicorp/aws"
      version               = "~> 5.81"
      configuration_aliases = [aws.dr]
    }
    random = { source = "hashicorp/random", version = "~> 3.6" } # OPS-020: Valkey AUTH token
  }
}
