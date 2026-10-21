# OPS-055 / ADR-01: Comms Gateway and Patient Access Edge each get their own database and roles on the cell's Postgres
# (nabd_comms, nabd_edge) with no grants on Core. The databases and roles are created by infra/db/bootstrap-satellites.sql
# (RDS cannot create a database or role from Terraform without a Postgres provider and a path into the private subnet).
# Passwords are generated here, held in Secrets Manager (ops key) and handed to that script by the migrate job.
locals {
  # nabd_pgbouncer_auth runs auth_query only; nabd_pgbouncer_stats may SHOW the pool counters (pool-metrics.py).
  satellite_roles = toset(["comms_migrator", "comms_app", "edge_migrator", "edge_app", "pgbouncer_auth", "pgbouncer_stats"])
}

resource "random_password" "db_role" {
  for_each = local.satellite_roles
  length   = 48
  special  = false # goes into connection strings and userlist.txt unescaped
}

resource "aws_secretsmanager_secret" "db_role" {
  for_each   = local.satellite_roles
  name       = "${var.name}/db/${each.key}"
  kms_key_id = aws_kms_key.root["ops"].arn
  tags       = merge(var.tags, { data_class = "ops", db_role = each.key })
}

resource "aws_secretsmanager_secret_version" "db_role" {
  for_each      = local.satellite_roles
  secret_id     = aws_secretsmanager_secret.db_role[each.key].id
  secret_string = random_password.db_role[each.key].result
}
