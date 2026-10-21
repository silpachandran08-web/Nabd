# OPS-058: installation secrets (cell/<name>/platform/*) and tenant integration secrets (cell/<name>/tenants/<tenantId>/*)
# are separate paths under separate KMS keys and separate roles. Secret values are never in Terraform: the secrets are
# created by the cell bootstrap and by tenant onboarding; this file only grants access by path.
locals {
  s58_secret_arn   = "arn:aws:secretsmanager:${var.region}:${local.account}:secret"
  s58_platform_arn = "${local.s58_secret_arn}:cell/${var.name}/platform/*"
  s58_tenants_arn  = "${local.s58_secret_arn}:cell/${var.name}/tenants/*"
  # IAM policy variable: resolved by AWS from the session tag the app sets when it assumes the role for one tenant.
  s58_own_tenant_arn = "${local.s58_secret_arn}:cell/${var.name}/tenants/$${aws:PrincipalTag/tenant_id}/*"
  s58_trust = {
    app = length(var.secrets_app_principal_arns) > 0 ? var.secrets_app_principal_arns : ["arn:aws:iam::${local.account}:root"]
    ops = length(var.secrets_operator_principal_arns) > 0 ? var.secrets_operator_principal_arns : ["arn:aws:iam::${local.account}:root"]
  }
}

data "aws_iam_policy_document" "s58_trust_app" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "AWS"
      identifiers = local.s58_trust.app
    }
    # With the account-root fallback only principals of this cell can assume; explicit ARNs are trusted as listed.
    dynamic "condition" {
      for_each = length(var.secrets_app_principal_arns) > 0 ? [] : [1]
      content {
        test     = "StringEquals"
        variable = "aws:PrincipalTag/cell_name"
        values   = [var.name]
      }
    }
  }
}

data "aws_iam_policy_document" "s58_trust_ops" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "AWS"
      identifiers = local.s58_trust.ops
    }
    condition {
      test     = "Bool"
      variable = "aws:MultiFactorAuthPresent"
      values   = ["true"]
    }
  }
}

# Runtime read of installation secrets (DB credentials, JWT keys, Meta app secret). Cannot touch tenants/*.
resource "aws_iam_role" "secrets_platform_reader" {
  name               = "${var.name}-secrets-platform-reader"
  assume_role_policy = data.aws_iam_policy_document.s58_trust_app.json
  tags               = merge(var.tags, { data_class = "secrets", cell_name = var.name })
}

resource "aws_iam_role_policy" "secrets_platform_reader" {
  name = "read-platform-secrets"
  role = aws_iam_role.secrets_platform_reader.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Sid = "ReadPlatformPath", Effect = "Allow", Action = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"], Resource = local.s58_platform_arn },
      { Sid = "DecryptPlatformKey", Effect = "Allow", Action = ["kms:Decrypt", "kms:DescribeKey"], Resource = aws_kms_key.s58["secrets-platform"].arn },
      { Sid = "NeverTenantPath", Effect = "Deny", Action = "secretsmanager:*", Resource = local.s58_tenants_arn }
    ]
  })
}

# Runtime read of ONE tenant's integration secrets: the app assumes this role with the session tag tenant_id=<id>, so the
# resource below expands to that tenant's path only. No tag, no access.
resource "aws_iam_role" "secrets_tenant_reader" {
  name               = "${var.name}-secrets-tenant-reader"
  assume_role_policy = data.aws_iam_policy_document.s58_trust_app.json
  tags               = merge(var.tags, { data_class = "secrets", cell_name = var.name })
}

resource "aws_iam_role_policy" "secrets_tenant_reader" {
  name = "read-own-tenant-secrets"
  role = aws_iam_role.secrets_tenant_reader.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Sid = "ReadOwnTenantPath", Effect = "Allow", Action = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"], Resource = local.s58_own_tenant_arn },
      { Sid = "DecryptTenantKey", Effect = "Allow", Action = ["kms:Decrypt", "kms:DescribeKey"], Resource = aws_kms_key.s58["secrets-tenants"].arn },
      { Sid = "NoTenantTagNoAccess", Effect = "Deny", Action = "secretsmanager:*", Resource = "*", Condition = { Null = { "aws:PrincipalTag/tenant_id" = "true" } } },
      { Sid = "NeverPlatformPath", Effect = "Deny", Action = "secretsmanager:*", Resource = local.s58_platform_arn }
    ]
  })
}

# Operators: write and rotate, never read a value (GetSecretValue is not granted). One role per secret class.
resource "aws_iam_role" "secrets_platform_operator" {
  name               = "${var.name}-secrets-platform-operator"
  assume_role_policy = data.aws_iam_policy_document.s58_trust_ops.json
  tags               = merge(var.tags, { data_class = "secrets", cell_name = var.name })
}

resource "aws_iam_role_policy" "secrets_platform_operator" {
  name = "manage-platform-secrets"
  role = aws_iam_role.secrets_platform_operator.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Sid = "ManagePlatformPath", Effect = "Allow", Action = ["secretsmanager:CreateSecret", "secretsmanager:PutSecretValue", "secretsmanager:UpdateSecret", "secretsmanager:RotateSecret", "secretsmanager:DescribeSecret", "secretsmanager:TagResource"], Resource = local.s58_platform_arn },
      { Sid = "EncryptPlatformKey", Effect = "Allow", Action = ["kms:GenerateDataKey", "kms:Encrypt", "kms:DescribeKey"], Resource = aws_kms_key.s58["secrets-platform"].arn },
      { Sid = "NeverTenantPath", Effect = "Deny", Action = "secretsmanager:*", Resource = local.s58_tenants_arn }
    ]
  })
}

resource "aws_iam_role" "secrets_tenant_operator" {
  name               = "${var.name}-secrets-tenant-operator"
  assume_role_policy = data.aws_iam_policy_document.s58_trust_ops.json
  tags               = merge(var.tags, { data_class = "secrets", cell_name = var.name })
}

resource "aws_iam_role_policy" "secrets_tenant_operator" {
  name = "manage-tenant-secrets"
  role = aws_iam_role.secrets_tenant_operator.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Sid = "ManageTenantPaths", Effect = "Allow", Action = ["secretsmanager:CreateSecret", "secretsmanager:PutSecretValue", "secretsmanager:UpdateSecret", "secretsmanager:RotateSecret", "secretsmanager:DescribeSecret", "secretsmanager:TagResource"], Resource = local.s58_tenants_arn },
      { Sid = "EncryptTenantKey", Effect = "Allow", Action = ["kms:GenerateDataKey", "kms:Encrypt", "kms:DescribeKey"], Resource = aws_kms_key.s58["secrets-tenants"].arn },
      { Sid = "NeverPlatformPath", Effect = "Deny", Action = "secretsmanager:*", Resource = local.s58_platform_arn }
    ]
  })
}
