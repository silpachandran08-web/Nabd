output "edge" {
  value = local.edge ? {
    alb_dns         = aws_lb.edge[0].dns_name
    alb_arn         = aws_lb.edge[0].arn
    target_groups   = { for k, v in aws_lb_target_group.edge : k => v.arn }
    waf_acl_arn     = aws_wafv2_web_acl.edge[0].arn
    certificate_arn = aws_acm_certificate_validation.edge[0].certificate_arn
  } : null
}
