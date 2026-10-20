resource "aws_route53_zone" "private" {
  name = var.dns_zone
  tags = merge(var.tags, { data_class = "ops" })
  vpc { vpc_id = aws_vpc.this.id }
}

resource "aws_route53_record" "db" {
  zone_id = aws_route53_zone.private.zone_id
  name    = "db.${var.dns_zone}"
  type    = "CNAME"
  ttl     = 60
  records = [aws_db_instance.this.address]
}
