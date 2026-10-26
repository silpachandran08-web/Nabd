# OPS-056: WAF on the load balancer: AWS managed rules (on, blocking) and a per-IP rate limit. Regional scope, so it stays in the cell region.
resource "aws_wafv2_web_acl" "edge" {
  count = local.edge ? 1 : 0
  name  = "${var.name}-edge"
  scope = "REGIONAL"
  tags  = merge(var.tags, { data_class = "network" })

  default_action {
    allow {}
  }

  rule {
    name     = "rate-per-ip"
    priority = 0
    action {
      block {}
    }
    statement {
      rate_based_statement {
        limit              = var.edge_rate_limit_5min
        aggregate_key_type = "IP"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "rate-per-ip"
      sampled_requests_enabled   = true
    }
  }

  dynamic "rule" {
    for_each = { for i, n in ["AWSManagedRulesCommonRuleSet", "AWSManagedRulesKnownBadInputsRuleSet", "AWSManagedRulesSQLiRuleSet", "AWSManagedRulesAmazonIpReputationList"] : n => 10 + i }
    content {
      name     = rule.key
      priority = rule.value
      override_action {
        none {}
      }
      statement {
        managed_rule_group_statement {
          name        = rule.key
          vendor_name = "AWS"
        }
      }
      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = rule.key
        sampled_requests_enabled   = true
      }
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.name}-edge"
    sampled_requests_enabled   = true
  }
}

resource "aws_wafv2_web_acl_association" "edge" {
  count        = local.edge ? 1 : 0
  resource_arn = aws_lb.edge[0].arn
  web_acl_arn  = aws_wafv2_web_acl.edge[0].arn
}

# WAF logs name the client IP, never the body. Credentials are redacted before they are written.
resource "aws_cloudwatch_log_group" "waf" {
  count             = local.edge ? 1 : 0
  name              = "aws-waf-logs-${var.name}"
  retention_in_days = var.edge_log_retention_days
  tags              = merge(var.tags, { data_class = "ops" })
}

resource "aws_wafv2_web_acl_logging_configuration" "edge" {
  count                   = local.edge ? 1 : 0
  resource_arn            = aws_wafv2_web_acl.edge[0].arn
  log_destination_configs = [aws_cloudwatch_log_group.waf[0].arn]
  redacted_fields {
    single_header { name = "authorization" }
  }
  redacted_fields {
    single_header { name = "cookie" }
  }
}
