# OPS-055: the cell has no internet egress, so the PgBouncer hosts reach Secrets Manager and CloudWatch over
# interface endpoints, and applications reach the pool through its own DNS name (db.<zone> stays the DIRECT endpoint).
resource "aws_security_group" "vpc_endpoints" {
  name        = "${var.name}-vpc-endpoints"
  description = "Interface endpoints: HTTPS from inside the cell VPC"
  vpc_id      = aws_vpc.this.id
  tags        = merge(var.tags, { data_class = "network" })

  ingress {
    description = "HTTPS from the VPC"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }
}

resource "aws_vpc_endpoint" "pooler" {
  for_each            = toset(["secretsmanager", "monitoring"])
  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${var.region}.${each.key}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.vpc_endpoints.id]
  private_dns_enabled = true
  tags                = merge(var.tags, { data_class = "network", Name = "${var.name}-${each.key}" })
}

resource "aws_route53_record" "pool" {
  zone_id = aws_route53_zone.private.zone_id
  name    = "pool.${var.dns_zone}"
  type    = "CNAME"
  ttl     = 60
  records = [aws_lb.pooler.dns_name]
}
