# OPS-058: every GetSecretValue on this cell's secret paths (principal, secret id, time; the API never returns the value to
# the log) goes from CloudTrail management events, through EventBridge, into an encrypted log group in the cell's region.
resource "aws_cloudwatch_log_group" "secret_access" {
  name              = "/aws/events/nabd/${var.name}/secret-access"
  retention_in_days = var.secret_audit_retention_days
  kms_key_id        = aws_kms_key.s58["secret-audit-logs"].arn
  tags              = merge(var.tags, { data_class = "audit" })
}

resource "aws_cloudwatch_log_resource_policy" "secret_access" {
  policy_name = "${var.name}-secret-access-events"
  policy_document = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = ["events.amazonaws.com", "delivery.logs.amazonaws.com"] }
      Action    = ["logs:CreateLogStream", "logs:PutLogEvents"]
      Resource  = "${aws_cloudwatch_log_group.secret_access.arn}:*"
    }]
  })
}

resource "aws_cloudwatch_event_rule" "secret_access" {
  name        = "${var.name}-secret-access"
  description = "Audit: reads of ${var.name} secrets"
  tags        = merge(var.tags, { data_class = "audit" })
  event_pattern = jsonencode({
    source        = ["aws.secretsmanager"]
    "detail-type" = ["AWS API Call via CloudTrail"]
    detail = {
      eventName = ["GetSecretValue", "BatchGetSecretValue"]
      # secretId may be sent as a name or as an ARN
      requestParameters = { secretId = [{ prefix = "cell/${var.name}/" }, { prefix = "${local.s58_secret_arn}:cell/${var.name}/" }] }
    }
  })
}

resource "aws_cloudwatch_event_target" "secret_access" {
  rule = aws_cloudwatch_event_rule.secret_access.name
  arn  = aws_cloudwatch_log_group.secret_access.arn
}
