# OPS-020: managed Valkey (ElastiCache) for the cell, used for session revocation, rate limits, config invalidation
# and (OPS-016/017) the event streams ev:<region>:<domain> (keys, ids and PHI-free envelopes only:
# docs/architecture/valkey.md). DEC-19 prefers managed.
# The streams are NOT a cache: the relay marks an outbox row done once its XADD is acknowledged, so an acknowledged
# entry must survive a restart or failover (QA-7 D2). ElastiCache for Valkey 9 durability "sync" writes every command
# to a Multi-AZ transaction log before the ACK (the managed equivalent of AOF appendfsync always; AOF itself is not
# offered on ElastiCache). Durability is fixed at creation: moving an existing cell means a new replication group and
# a cut-over (docs/ops/cells.md). Durable clusters need a Graviton r/m/c7gn node type (var.valkey_node_type).
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
  engine_version              = "9.0"  # durability needs Valkey 9.0+; the engine's default parameter group applies
  durability                  = "sync" # streams: an acknowledged XADD is in the Multi-AZ log (async may drop ~10 s)
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
  snapshot_retention_limit    = 7             # daily snapshots on top of the log; the outbox redrive (events.md section 8) covers the rest
  snapshot_window             = "20:00-21:00" # UTC, before the maintenance window
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
