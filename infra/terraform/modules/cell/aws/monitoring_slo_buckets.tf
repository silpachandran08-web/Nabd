# OPS-026: telemetry storage inside the cell's region, SSE-KMS with the cell keys. Collector/Prometheus/Loki/Tempo/Grafana compute is
# Needs external action (DEC-21: sidecar vs daemonset vs ECS); configs live in infra/observability/stack.
locals {
  obs_buckets = {
    metrics    = { class = "ops", days = var.obs_metrics_retention_days, lock = false }
    logs       = { class = "ops", days = var.obs_log_retention_days, lock = false }
    traces     = { class = "ops", days = var.obs_trace_retention_days, lock = false }
    audit-logs = { class = "audit", days = var.obs_log_retention_days, lock = true } # audit-relevant streams: immutable
  }
}

resource "aws_s3_bucket" "obs" {
  for_each            = local.obs_buckets
  bucket              = "${var.name}-obs-${each.key}-${local.account}"
  object_lock_enabled = each.value.lock
  force_destroy       = false
  tags                = merge(var.tags, { data_class = each.value.class })
}

resource "aws_s3_bucket_public_access_block" "obs" {
  for_each                = aws_s3_bucket.obs
  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "obs" {
  for_each = aws_s3_bucket.obs
  bucket   = each.value.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "obs" {
  for_each = aws_s3_bucket.obs
  bucket   = each.value.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.root[local.obs_buckets[each.key].class].arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_object_lock_configuration" "obs_audit_logs" {
  bucket = aws_s3_bucket.obs["audit-logs"].id
  rule {
    default_retention {
      mode = "COMPLIANCE"
      days = var.obs_log_retention_days
    }
  }
}

# Backstop for the apps' own retention (Loki/Tempo compactors expire first). Audit logs expire one day after the lock ends.
resource "aws_s3_bucket_lifecycle_configuration" "obs" {
  for_each = aws_s3_bucket.obs
  bucket   = each.value.id
  rule {
    id     = "expire"
    status = "Enabled"
    filter {}
    expiration { days = local.obs_buckets[each.key].days + 1 }
    noncurrent_version_expiration { noncurrent_days = local.obs_buckets[each.key].days + 1 }
  }
  depends_on = [aws_s3_bucket_versioning.obs]
}

# Telemetry endpoints are private to the cell: reachable from the VPC only.
resource "aws_security_group" "obs" {
  name        = "${var.name}-obs"
  description = "Telemetry endpoints (OTLP, Prometheus, Loki, Tempo, Grafana): in-VPC only"
  vpc_id      = aws_vpc.this.id
  tags        = merge(var.tags, { data_class = "ops" })
}

resource "aws_vpc_security_group_ingress_rule" "obs_in_vpc" {
  for_each          = toset(["4317", "4318", "9090", "3100", "3200", "3000"])
  security_group_id = aws_security_group.obs.id
  cidr_ipv4         = var.vpc_cidr
  ip_protocol       = "tcp"
  from_port         = tonumber(each.key)
  to_port           = tonumber(each.key)
  tags              = merge(var.tags, { data_class = "ops" })
}

output "obs_bucket_names" {
  value = { for k, b in aws_s3_bucket.obs : k => b.bucket }
}
