# One customer-managed root key per data class (multi-region so the DR region gets replicas). Per-tenant DEKs are wrapped by these later.
locals {
  data_classes = toset(["phi", "audit", "ops"])
}

resource "aws_kms_key" "root" {
  for_each                = local.data_classes
  description             = "${var.name} ${each.key} root key"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  multi_region            = true
  tags                    = merge(var.tags, { data_class = each.key })
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid       = "AccountAdmin"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${local.account}:root" }
        Action    = "kms:*"
        Resource  = "*"
      }],
      each.key == "ops" ? [{
        Sid       = "CloudWatchAlarmsToEncryptedSns"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
        Action    = ["kms:Decrypt", "kms:GenerateDataKey*"]
        Resource  = "*"
      }] : []
    )
  })
}

resource "aws_kms_alias" "root" {
  for_each      = local.data_classes
  name          = "alias/${var.name}-${each.key}"
  target_key_id = aws_kms_key.root[each.key].key_id
}

resource "aws_kms_replica_key" "dr" {
  for_each                = local.cross_region ? local.data_classes : toset([])
  provider                = aws.dr
  description             = "${var.name} ${each.key} root key (DR replica)"
  primary_key_arn         = aws_kms_key.root[each.key].arn
  deletion_window_in_days = 30
  tags                    = merge(var.tags, { data_class = each.key, dr = "true" })
}

locals {
  dr_key_arn = { for k in local.data_classes : k => local.cross_region ? aws_kms_replica_key.dr[k].arn : aws_kms_key.root[k].arn }
}
