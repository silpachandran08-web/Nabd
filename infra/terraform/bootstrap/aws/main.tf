# OPS-052: account baseline, applied once by a human with SSO (local state first, then `tofu init -migrate-state` into the bucket).
# Creates the encrypted, versioned, locked remote state and GitHub OIDC roles so CI never holds long-lived cloud keys.
terraform {
  required_version = ">= 1.8"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.81" }
  }
}

variable "github_repo" {
  description = "owner/repo allowed to assume the CI roles."
  type        = string
}

provider "aws" {
  region = "ap-south-1"
}

data "aws_caller_identity" "current" {}

locals {
  account = data.aws_caller_identity.current.account_id
  tags    = { cell = "global", service = "terraform-state", managed_by = "terraform" }
  oidc    = "token.actions.githubusercontent.com"
}

resource "aws_kms_key" "state" {
  description         = "Terraform state encryption"
  enable_key_rotation = true
  tags                = merge(local.tags, { data_class = "ops" })
}

resource "aws_kms_alias" "state" {
  name          = "alias/nabd-tfstate"
  target_key_id = aws_kms_key.state.key_id
}

resource "aws_s3_bucket" "state" {
  bucket = "nabd-tfstate-${local.account}-ap-south-1"
  tags   = merge(local.tags, { data_class = "ops" })
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.state.arn
    }
  }
}

resource "aws_dynamodb_table" "lock" {
  name         = "nabd-tfstate-lock"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"
  tags         = merge(local.tags, { data_class = "ops" })
  attribute {
    name = "LockID"
    type = "S"
  }
  server_side_encryption {
    enabled     = true
    kms_key_arn = aws_kms_key.state.arn
  }
  point_in_time_recovery { enabled = true }
}

# GitHub OIDC. AWS validates GitHub's certificate chain itself, so no thumbprint is pinned (provider >= 5.81).
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://${local.oidc}"
  client_id_list = ["sts.amazonaws.com"]
  tags           = merge(local.tags, { data_class = "ops" })
}

locals {
  # plan: any ref of the repo (PRs), read-only. apply: only the protected GitHub environments.
  roles = {
    plan  = { subjects = ["repo:${var.github_repo}:*"], policy = "arn:aws:iam::aws:policy/ReadOnlyAccess" }
    apply = { subjects = ["repo:${var.github_repo}:environment:infra-staging", "repo:${var.github_repo}:environment:infra-prod"], policy = "arn:aws:iam::aws:policy/AdministratorAccess" }
  }
}

resource "aws_iam_role" "ci" {
  for_each             = local.roles
  name                 = "nabd-ci-terraform-${each.key}"
  max_session_duration = 3600
  tags                 = merge(local.tags, { data_class = "ops" })
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = { "${local.oidc}:aud" = "sts.amazonaws.com" }
        StringLike   = { "${local.oidc}:sub" = each.value.subjects }
      }
    }]
  })
}

# shortcut: apply uses AdministratorAccess; scope it to the cell's services once the resource set settles (before prod-in, S4).
resource "aws_iam_role_policy_attachment" "ci" {
  for_each   = local.roles
  role       = aws_iam_role.ci[each.key].name
  policy_arn = each.value.policy
}

# Both roles take the state lock; only apply may write state (ReadOnlyAccess covers neither DynamoDB writes nor KMS decrypt).
resource "aws_iam_role_policy" "state" {
  for_each = local.roles
  name     = "terraform-state"
  role     = aws_iam_role.ci[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:ListBucket"], Resource = aws_s3_bucket.state.arn },
      { Effect = "Allow", Action = each.key == "apply" ? ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"] : ["s3:GetObject"], Resource = "${aws_s3_bucket.state.arn}/*" },
      { Effect = "Allow", Action = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:DeleteItem"], Resource = aws_dynamodb_table.lock.arn },
      { Effect = "Allow", Action = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey"], Resource = aws_kms_key.state.arn },
    ]
  })
}

output "state_bucket" { value = aws_s3_bucket.state.id }
output "lock_table" { value = aws_dynamodb_table.lock.name }
output "plan_role_arn" { value = aws_iam_role.ci["plan"].arn }
output "apply_role_arn" { value = aws_iam_role.ci["apply"].arn }
