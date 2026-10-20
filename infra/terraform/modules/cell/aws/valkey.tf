# OPS-020: managed Valkey (ElastiCache) for the cell, used for session revocation, rate limits and config
# invalidation (keys and ids only, never PHI values: docs/architecture/valkey.md). DEC-19 prefers managed.
# TLS is required (no plaintext listener), AUTH token from Secrets Manager, encrypted at rest with the cell's ops key,
# one replica in a second AZ with automatic failover, private subnets only: ElastiCache has no public endpoint and the
# security group admits the cell VPC alone.

resource "aws_elasticache_subnet_group" "valkey" {
  name       = "${var.name}-valkey"
  subnet_ids = aws_subnet.private[*].id
  tags       = merge(var.tags, { data_class = "network" })
}

resource "aws_security_group" "valkey" {
  name        = "${var.name}-valkey"
  description = "Valkey: reachable only from inside the cell VPC"
  vpc_id      = aws_vpc.this.id
  tags        = merge(var.tags, { data_class = "network" })

  ingress {
    description = "Valkey (TLS) from the VPC"
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }
}

# The app reads this as part of VALKEY_URL (rediss://:<token>@<primary>:6379). Never in git or tfvars.
resource "random_password" "valkey_auth" {
  length  = 64
  special = false # ElastiCache AUTH tokens refuse most symbols
}

resource "aws_secretsmanager_secret" "valkey_auth" {
  name       = "${var.name}/valkey-auth"
  kms_key_id = aws_kms_key.root["ops"].arn
  tags       = merge(var.tags, { data_class = "ops" })
}

resource "aws_secretsmanager_secret_version" "valkey_auth" {
  secret_id     = aws_secretsmanager_secret.valkey_auth.id
  secret_string = random_password.valkey_auth.result
}

resource "aws_elasticache_replication_group" "valkey" {
  replication_group_id        = "${var.name}-valkey"
  description                 = "Nabd ${var.name} cache (ids and keys only, no PHI values)"
  engine                      = "valkey"
  engine_version              = "7.2"
  parameter_group_name        = "default.valkey7"
  node_type                   = var.valkey_node_type
  port                        = 6379
  num_cache_clusters          = 2 # primary + one replica
  preferred_cache_cluster_azs = slice(var.azs, 0, 2)
  automatic_failover_enabled  = true
  multi_az_enabled            = true
  subnet_group_name           = aws_elasticache_subnet_group.valkey.name
  security_group_ids          = [aws_security_group.valkey.id]
  transit_encryption_enabled  = true
  transit_encryption_mode     = "required"
  auth_token                  = random_password.valkey_auth.result
  at_rest_encryption_enabled  = true
  kms_key_id                  = aws_kms_key.root["ops"].arn
  snapshot_retention_limit    = 0 # cache only: every value is rebuilt from Postgres (revocation falls back to the DB)
  auto_minor_version_upgrade  = true
  maintenance_window          = "sun:22:00-sun:23:00" # UTC, after the Postgres window
  apply_immediately           = false
  tags                        = merge(var.tags, { data_class = "ops" })

  lifecycle {
    precondition {
      condition     = length(var.azs) >= 2
      error_message = "Valkey needs two availability zones (primary and replica in different zones)."
    }
  }
}
