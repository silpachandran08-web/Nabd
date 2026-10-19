# OPS-052: plan-only reuse test with mocked providers (no cloud account, no credentials). Run: tofu test (in modules/cell).
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
  environment = "staging"
  dns_zone    = "test.cells.nabd.internal"
}

run "in_cell_mumbai_with_hyderabad_dr" {
  command = plan
  variables {
    cell      = "in"
    country   = "IN"
    region    = "ap-south-1"
    dr_region = "ap-south-2"
    azs       = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]
  }
  assert {
    condition     = output.cell.region == "ap-south-1" && output.cell.dr_region == "ap-south-2"
    error_message = "IN cell must plan in Mumbai with Hyderabad DR"
  }
}

run "second_region_ae_plans_cleanly" {
  command = plan
  variables {
    cell      = "ae"
    country   = "AE"
    region    = "me-central-1"
    dr_region = "me-central-1" # single in-country region on AWS: DR copies stay in-region
    azs       = ["me-central-1a", "me-central-1b"]
  }
  assert {
    condition     = output.cell.cell == "ae" && output.cell.region == "me-central-1"
    error_message = "AE instantiation must reuse the same module with only region variables changed"
  }
}

run "dr_outside_country_is_refused" {
  command = plan
  variables {
    cell      = "in"
    country   = "IN"
    region    = "ap-south-1"
    dr_region = "me-central-1"
    azs       = ["ap-south-1a", "ap-south-1b"]
  }
  expect_failures = [terraform_data.residency]
}
