# OPS-056: ACM certificate in the cell region, DNS-validated, so ACM renews it by itself (no manual renewal, > 20 days validity at all times).
resource "aws_acm_certificate" "edge" {
  count                     = local.edge ? 1 : 0
  domain_name               = var.edge_api_host
  subject_alternative_names = [var.edge_app_host]
  validation_method         = "DNS"
  tags                      = merge(var.tags, { data_class = "network" })
  lifecycle { create_before_destroy = true }
}

resource "aws_route53_record" "edge_validation" {
  for_each = local.edge ? {
    for o in aws_acm_certificate.edge[0].domain_validation_options : o.domain_name => o
  } : {}
  zone_id         = var.edge_public_zone_id
  name            = each.value.resource_record_name
  type            = each.value.resource_record_type
  records         = [each.value.resource_record_value]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "edge" {
  count                   = local.edge ? 1 : 0
  certificate_arn         = aws_acm_certificate.edge[0].arn
  validation_record_fqdns = [for r in aws_route53_record.edge_validation : r.fqdn]
}

# Alarm well before the 20-day acceptance floor if ACM ever fails to renew.
resource "aws_cloudwatch_metric_alarm" "edge_cert_expiry" {
  count               = local.edge ? 1 : 0
  alarm_name          = "${var.name}-edge-cert-expiry"
  namespace           = "AWS/CertificateManager"
  metric_name         = "DaysToExpiry"
  dimensions          = { CertificateArn = aws_acm_certificate.edge[0].arn }
  statistic           = "Minimum"
  period              = 86400
  evaluation_periods  = 1
  comparison_operator = "LessThanThreshold"
  threshold           = 30
  alarm_actions       = [aws_sns_topic.alerts.arn]
  tags                = merge(var.tags, { data_class = "ops" })
}
