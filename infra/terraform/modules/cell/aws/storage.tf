locals {
  buckets = {
    data  = { class = "phi", lock = false }
    audit = { class = "audit", lock = true }
  }
}

resource "aws_s3_bucket" "this" {
  for_each            = local.buckets
  bucket              = "${var.name}-${each.key}-${local.account}"
  object_lock_enabled = each.value.lock
  force_destroy       = false
  tags                = merge(var.tags, { data_class = each.value.class })
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each                = aws_s3_bucket.this
  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "this" {
  for_each = aws_s3_bucket.this
  bucket   = each.value.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = aws_s3_bucket.this
  bucket   = each.value.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.root[local.buckets[each.key].class].arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_object_lock_configuration" "audit" {
  bucket = aws_s3_bucket.this["audit"].id
  rule {
    default_retention {
      mode = "COMPLIANCE"
      days = var.audit_lock_days
    }
  }
}

# DR bucket in the DR region: receives the audit archive (and later other copies). Same guarantees as the primary.
resource "aws_s3_bucket" "dr" {
  provider            = aws.dr
  bucket              = "${var.name}-dr-${local.account}"
  object_lock_enabled = true
  force_destroy       = false
  tags                = merge(var.tags, { data_class = "backup", dr = "true" })
}

resource "aws_s3_bucket_public_access_block" "dr" {
  provider                = aws.dr
  bucket                  = aws_s3_bucket.dr.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "dr" {
  provider = aws.dr
  bucket   = aws_s3_bucket.dr.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "dr" {
  provider = aws.dr
  bucket   = aws_s3_bucket.dr.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = local.dr_key_arn["audit"]
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_object_lock_configuration" "dr" {
  provider = aws.dr
  bucket   = aws_s3_bucket.dr.id
  rule {
    default_retention {
      mode = "COMPLIANCE"
      days = var.audit_lock_days
    }
  }
}

resource "aws_iam_role" "replication" {
  name = "${var.name}-s3-replication"
  tags = merge(var.tags, { data_class = "ops" })
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "s3.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy" "replication" {
  name = "replicate-audit-to-dr"
  role = aws_iam_role.replication.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:GetReplicationConfiguration", "s3:ListBucket"], Resource = aws_s3_bucket.this["audit"].arn },
      { Effect = "Allow", Action = ["s3:GetObjectVersionForReplication", "s3:GetObjectVersionAcl", "s3:GetObjectVersionTagging", "s3:GetObjectRetention", "s3:GetObjectLegalHold"], Resource = "${aws_s3_bucket.this["audit"].arn}/*" },
      { Effect = "Allow", Action = ["s3:ReplicateObject", "s3:ReplicateDelete", "s3:ReplicateTags"], Resource = "${aws_s3_bucket.dr.arn}/*" },
      { Effect = "Allow", Action = ["kms:Decrypt"], Resource = aws_kms_key.root["audit"].arn },
      { Effect = "Allow", Action = ["kms:Encrypt", "kms:GenerateDataKey"], Resource = local.dr_key_arn["audit"] },
    ]
  })
}

resource "aws_s3_bucket_replication_configuration" "audit" {
  depends_on = [aws_s3_bucket_versioning.this, aws_s3_bucket_versioning.dr]
  bucket     = aws_s3_bucket.this["audit"].id
  role       = aws_iam_role.replication.arn
  rule {
    id     = "audit-to-dr"
    status = "Enabled"
    filter {}
    delete_marker_replication { status = "Disabled" }
    source_selection_criteria {
      sse_kms_encrypted_objects { status = "Enabled" }
    }
    destination {
      bucket = aws_s3_bucket.dr.arn
      encryption_configuration { replica_kms_key_id = local.dr_key_arn["audit"] }
    }
  }
}
