output "secrets" {
  value = {
    platform_prefix = "cell/${var.name}/platform/"
    tenants_prefix  = "cell/${var.name}/tenants/"
    kms             = { for k, v in aws_kms_key.s58 : k => v.arn }
    roles = {
      platform_reader   = aws_iam_role.secrets_platform_reader.arn
      tenant_reader     = aws_iam_role.secrets_tenant_reader.arn
      platform_operator = aws_iam_role.secrets_platform_operator.arn
      tenant_operator   = aws_iam_role.secrets_tenant_operator.arn
    }
    audit_log_group = aws_cloudwatch_log_group.secret_access.name
  }
}
