# OPS-056: ingress in the cell region. TLS 1.2/1.3 only with the AWS forward-secrecy policy (TLS 1.3 preferred), HSTS with preload,
# HTTP only redirects. Pods register through the AWS Load Balancer Controller (TargetGroupBinding in infra/prod-in/targets.yaml).
resource "aws_lb" "edge" {
  count                      = local.edge ? 1 : 0
  name                       = "${var.name}-edge"
  load_balancer_type         = "application"
  internal                   = false
  subnets                    = aws_subnet.edge[*].id
  security_groups            = [aws_security_group.alb[0].id]
  drop_invalid_header_fields = true
  enable_deletion_protection = true
  idle_timeout               = 60
  tags                       = merge(var.tags, { data_class = "network" })
}

resource "aws_lb_target_group" "edge" {
  for_each    = local.edge ? { core = { port = 8080, health = "/actuator/health/readiness", hport = "8081" }, bff = { port = 3000, health = "/", hport = "traffic-port" } } : {}
  name        = "${substr(var.name, 0, 24)}-${each.key}"
  port        = each.value.port
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.this.id
  health_check {
    path                = each.value.health
    port                = each.value.hport
    healthy_threshold   = 2
    unhealthy_threshold = 2
    interval            = 10
    matcher             = "200"
  }
  deregistration_delay = 30
  tags                 = merge(var.tags, { data_class = "network" })
}

resource "aws_lb_listener" "https" {
  count = local.edge ? 1 : 0

  load_balancer_arn                                            = aws_lb.edge[0].arn
  port                                                         = 443
  protocol                                                     = "HTTPS"
  ssl_policy                                                   = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn                                              = aws_acm_certificate_validation.edge[0].certificate_arn
  routing_http_response_strict_transport_security_header_value = "max-age=63072000; includeSubDomains; preload"
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "not found"
      status_code  = "404"
    }
  }
  tags = merge(var.tags, { data_class = "network" })
}

resource "aws_lb_listener" "http_redirect" {
  count             = local.edge ? 1 : 0
  load_balancer_arn = aws_lb.edge[0].arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
  tags = merge(var.tags, { data_class = "network" })
}

resource "aws_lb_listener_rule" "edge" {
  for_each     = local.edge ? { core = { host = var.edge_api_host, prio = 10 }, bff = { host = var.edge_app_host, prio = 20 } } : {}
  listener_arn = aws_lb_listener.https[0].arn
  priority     = each.value.prio
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.edge[each.key].arn
  }
  condition {
    host_header { values = [each.value.host] }
  }
  tags = merge(var.tags, { data_class = "network" })
}

resource "aws_route53_record" "edge" {
  for_each = local.edge ? { api = var.edge_api_host, app = var.edge_app_host } : {}
  zone_id  = var.edge_public_zone_id
  name     = each.value
  type     = "A"
  alias {
    name                   = aws_lb.edge[0].dns_name
    zone_id                = aws_lb.edge[0].zone_id
    evaluate_target_health = true
  }
}
