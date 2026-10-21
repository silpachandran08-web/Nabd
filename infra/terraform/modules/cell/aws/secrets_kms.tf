# OPS-058: KMS key hierarchy per cell, one root key per data class, rotation on, usable only by principals of this cell.
# Per-tenant DEKs (T-130) are wrapped by field-encryption. The older phi/audit/ops keys live in kms.tf.
locals {
  s58_key_classes = toset(["field-encryption", "backups", "object-storage", "cache-projection", "secrets-platform", "secrets-tenants", "secret-audit-logs"])
  s58_use_actions = ["kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey"]
  # Cell isolation: the key policy never grants use to the account root itself, only to principals tagged with this cell's
  # name (IAM roles of this cell) and to AWS services acting for the account. Another cell's roles carry another cell_name.
  s58_key_policy = { for k in local.s58_key_classes : k => jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid       = "AccountAdminNoUse"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${local.account}:root" }
        Action = ["kms:Create*", "kms:Describe*", "kms:Enable*", "kms:List*", "kms:Put*", "kms:Update*", "kms:Revoke*",
        "kms:Disable*", "kms:Get*", "kms:Delete*", "kms:TagResource", "kms:UntagResource", "kms:ScheduleKeyDeletion", "kms:CancelKeyDeletion"]
        Resource = "*"
      },
      {
        Sid       = "UseOnlyByThisCell"
        Effect    = "Allow"
        Principal = { AWS = "*" }
        Action    = local.s58_use_actions
        Resource  = "*"
        Condition = {
          StringEquals = { "aws:PrincipalTag/cell_name" = var.name, "aws:PrincipalAccount" = local.account }
        }
      },
      {
        Sid       = "DenyOtherCells"
        Effect    = "Deny"
        Principal = { AWS = "*" }
        Action    = local.s58_use_actions
        Resource  = "*"
        Condition = {
          StringNotEquals = { "aws:PrincipalTag/cell_name" = var.name }
          Bool            = { "aws:PrincipalIsAWSService" = "false" }
        }
      }],
      k == "secret-audit-logs" ? [{
        Sid       = "LogsServiceEncryptsAuditLogs"
        Effect    = "Allow"
        Principal = { Service = "logs.${var.region}.amazonaws.com" }
        Action    = ["kms:Encrypt*", "kms:Decrypt*", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:Describe*"]
        Resource  = "*"
        Condition = { ArnLike = { "kms:EncryptionContext:aws:logs:arn" = "arn:aws:logs:${var.region}:${local.account}:log-group:/aws/events/nabd/${var.name}/*" } }
      }] : []
    )
  }) }
}

resource "aws_kms_key" "s58" {
  for_each                = local.s58_key_classes
  description             = "${var.name} ${each.key} root key (OPS-058)"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  multi_region            = true
  tags                    = merge(var.tags, { data_class = each.key })
  policy                  = local.s58_key_policy[each.key]
}

resource "aws_kms_alias" "s58" {
  for_each      = local.s58_key_classes
  name          = "alias/${var.name}-${each.key}"
  target_key_id = aws_kms_key.s58[each.key].key_id
}

resource "aws_kms_replica_key" "s58_dr" {
  for_each                = local.cross_region ? local.s58_key_classes : toset([])
  provider                = aws.dr
  description             = "${var.name} ${each.key} root key (OPS-058, DR replica)"
  primary_key_arn         = aws_kms_key.s58[each.key].arn
  deletion_window_in_days = 30
  tags                    = merge(var.tags, { data_class = each.key, dr = "true" })
  policy                  = local.s58_key_policy[each.key]
}
