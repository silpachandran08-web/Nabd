# OPS-052: provider-neutral cell contract. Provider resources live only in submodules (./aws today; DEC-04 open).
terraform {
  required_version = ">= 1.8" # OpenTofu >= 1.8 or Terraform >= 1.8 (mock_provider in tests)
  required_providers {
    aws = {
      source                = "hashicorp/aws"
      version               = "~> 5.81"
      configuration_aliases = [aws.dr]
    }
  }
}
