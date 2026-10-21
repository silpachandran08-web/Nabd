# OPS-055: names other stories build on (OPS-056 edge, services' deploy config). Host and port only, never a URL with credentials.
output "data_tier" {
  value = {
    pooler_host         = aws_route53_record.pool.fqdn # business traffic: nabd_app / nabd_reports, transaction mode
    pooler_port         = 6432
    pooler_instances    = local.pooler_count
    pooler_sg_id        = aws_security_group.pooler.id
    pooler_nlb_arn      = aws_lb.pooler.arn
    direct_host         = aws_route53_record.db.fqdn # relay leader lock + LISTEN, migrate job, bootstrap scripts
    direct_port         = 5432
    max_connections     = var.db_max_connections
    server_pool_total   = local.pooler_server
    prepared_statements = true
    databases           = { core = "nabd", comms = "nabd_comms", edge = "nabd_edge" }
    role_secret_arns    = { for k, v in aws_secretsmanager_secret.db_role : k => v.arn }
    metrics_namespace   = "Nabd/PgBouncer"
  }
}
