# OPS-055: plan-only checks of the data tier with mocked providers (no cloud account). Run: tofu test (in modules/cell).
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
  dns_zone         = "data.cells.nabd.internal"
  cell             = "in"
  country          = "IN"
  region           = "ap-south-1"
  dr_region        = "ap-south-2"
  azs              = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]
}

run "in_cell_has_two_pgbouncers_a_direct_endpoint_and_three_databases" {
  command = plan
  assert {
    condition     = output.data_tier.pooler_instances == 2 && output.data_tier.pooler_port == 6432 && output.data_tier.prepared_statements
    error_message = "two PgBouncer instances on 6432 with prepared statements"
  }
  assert {
    condition     = output.data_tier.pooler_host != output.data_tier.direct_host && output.data_tier.direct_port == 5432
    error_message = "the relay's direct endpoint must differ from the pool"
  }
  assert {
    condition     = output.data_tier.server_pool_total + 30 <= output.data_tier.max_connections
    error_message = "pools plus the direct reserve must fit in max_connections"
  }
  assert {
    condition     = toset(keys(output.data_tier.databases)) == toset(["core", "comms", "edge"])
    error_message = "core, comms and edge databases"
  }
}

run "pool_larger_than_max_connections_is_refused" {
  command = plan
  variables {
    pgbouncer_app_pool_size = 90
  }
  expect_failures = [aws_launch_template.pooler]
}

run "empty_ami_is_refused" {
  command = plan
  variables {
    pgbouncer_ami_id = ""
  }
  expect_failures = [aws_launch_template.pooler]
}
