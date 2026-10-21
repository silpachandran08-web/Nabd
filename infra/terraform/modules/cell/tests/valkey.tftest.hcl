# OPS-020: plan-only check of the cell's managed Valkey with mocked providers (no cloud account). Run: tofu test (in modules/cell).
mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "111111111111" }
  }
  # Computed ARNs must look like ARNs: the provider validates them during plan.
  mock_resource "aws_kms_key" {
    defaults = { arn = "arn:aws:kms:ap-south-1:111111111111:key/mock" }
  }
  mock_resource "aws_db_instance" {
    defaults = { arn = "arn:aws:rds:ap-south-1:111111111111:db:mock" }
  }
  mock_resource "aws_s3_bucket" {
    defaults = { arn = "arn:aws:s3:::mock" }
  }
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::111111111111:role/mock" }
  }
  mock_resource "aws_secretsmanager_secret" {
    defaults = { arn = "arn:aws:secretsmanager:ap-south-1:111111111111:secret:mock" }
  }
  mock_resource "aws_sns_topic" {
    defaults = { arn = "arn:aws:sns:ap-south-1:111111111111:mock" }
  }
}

mock_provider "aws" {
  alias = "dr"
  mock_resource "aws_kms_replica_key" {
    defaults = { arn = "arn:aws:kms:ap-south-2:111111111111:key/mock" }
  }
  mock_resource "aws_s3_bucket" {
    defaults = { arn = "arn:aws:s3:::mock-dr" }
  }
}

variables {
  pgbouncer_ami_id = "ami-0123456789abcdef0"
  environment      = "prod"
  dns_zone         = "valkey.cells.nabd.internal"
  cell             = "in"
  country          = "IN"
  region           = "ap-south-1"
  dr_region        = "ap-south-2"
  azs              = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]
}

run "in_cell_valkey_is_tls_only_encrypted_and_replicated_across_zones" {
  command = plan
  assert {
    condition     = output.valkey.tls_required && output.valkey.encrypted_at_rest && output.valkey.replicas == 1
    error_message = "Valkey must require TLS, be encrypted at rest and keep one replica"
  }
  assert {
    condition     = length(toset(output.valkey.zones)) == 2
    error_message = "Valkey primary and replica must sit in two different zones"
  }
}
