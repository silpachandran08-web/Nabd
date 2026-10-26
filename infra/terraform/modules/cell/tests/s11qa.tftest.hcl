# Independent QA (S11 OPS-052/OPS-054): plan-only residency, PITR and input guards with mocked providers. Run: tofu test.
mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "111111111111" }
  }
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
  pgbouncer_ami_id = "ami-0123456789abcdef0"
  environment      = "prod"
  dns_zone         = "qa.cells.nabd.internal"
  cell             = "in"
  country          = "IN"
  region           = "ap-south-1"
  dr_region        = "ap-south-2"
  azs              = ["ap-south-1a", "ap-south-1b"]
}

run "qa_prod_in_has_35_day_pitr_and_hyderabad_dr_placeholders" {
  command = plan
  assert {
    condition     = output.backup.pitr_days == 35
    error_message = "prod-in PITR window must be 35 days"
  }
  assert {
    condition     = output.dr.region == "ap-south-2" && output.dr.db_replica == null && output.dr.container_cluster == null
    error_message = "Hyderabad DR region with replica placeholders expected"
  }
  assert {
    condition     = output.cell.environment == "prod" && output.cell.country == "IN"
    error_message = "cell contract output wrong"
  }
}

run "qa_dr_in_singapore_is_refused" {
  command = plan
  variables {
    dr_region = "ap-southeast-1"
  }
  expect_failures = [terraform_data.residency]
}

run "qa_primary_outside_india_is_refused" {
  command = plan
  variables {
    region = "eu-west-1"
  }
  expect_failures = [terraform_data.residency]
}

# SA and QA have no allow-listed AWS region yet (DEC-04/DEC-27 open): instantiation is refused rather than planned abroad.
run "qa_sa_cell_cannot_be_planned_until_a_region_is_listed" {
  command = plan
  variables {
    cell      = "sa"
    country   = "SA"
    region    = "me-central-1"
    dr_region = "me-central-1"
    azs       = ["me-central-1a", "me-central-1b"]
  }
  expect_failures = [terraform_data.residency]
}

run "qa_unknown_environment_is_refused" {
  command = plan
  variables {
    environment = "dev"
  }
  expect_failures = [var.environment]
}

run "qa_single_az_is_refused" {
  command = plan
  variables {
    azs = ["ap-south-1a"]
  }
  expect_failures = [var.azs]
}

run "qa_audit_lock_below_cert_in_floor_is_refused" {
  command = plan
  variables {
    audit_lock_days = 30
  }
  expect_failures = [var.audit_lock_days]
}
