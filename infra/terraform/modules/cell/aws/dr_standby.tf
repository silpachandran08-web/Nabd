# OPS-059 (ADR-09): warm standby for the cell in dr_region. Network + an encrypted cross-region read replica of the primary
# Postgres. Failover = promote the replica (infra/dr/dr_failover.py, docs/runbooks/dr-failover.md); the runtime (core, worker,
# bff) is started from the same manifests in a DR cluster. The replica sits in the DR region only: residency.tf has already
# refused a dr_region outside the cell's country. Everything here is off unless dr_standby_enabled and dr_region != region.
locals {
  dr_standby = var.dr_standby_enabled && local.cross_region
}

resource "aws_vpc" "dr" {
  count                = local.dr_standby ? 1 : 0
  provider             = aws.dr
  cidr_block           = var.dr_vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(var.tags, { Name = "${var.name}-dr", data_class = "network", dr = "true" })
}

resource "aws_subnet" "dr_private" {
  count                   = local.dr_standby ? length(var.dr_azs) : 0
  provider                = aws.dr
  vpc_id                  = aws_vpc.dr[0].id
  availability_zone       = var.dr_azs[count.index]
  cidr_block              = cidrsubnet(var.dr_vpc_cidr, 4, count.index)
  map_public_ip_on_launch = false
  tags                    = merge(var.tags, { Name = "${var.name}-dr-private-${count.index}", data_class = "network", tier = "private", dr = "true" })
}

resource "aws_security_group" "dr_db" {
  count       = local.dr_standby ? 1 : 0
  provider    = aws.dr
  name        = "${var.name}-dr-db"
  description = "DR Postgres: reachable only from inside the DR VPC"
  vpc_id      = aws_vpc.dr[0].id
  tags        = merge(var.tags, { data_class = "network", dr = "true" })

  ingress {
    description = "Postgres from the DR VPC"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.dr_vpc_cidr]
  }
}

resource "aws_db_subnet_group" "dr" {
  count      = local.dr_standby ? 1 : 0
  provider   = aws.dr
  name       = "${var.name}-dr"
  subnet_ids = aws_subnet.dr_private[*].id
  tags       = merge(var.tags, { data_class = "network", dr = "true" })
}

# Same settings as the primary (max_connections, SCRAM, forced TLS): the pool sizes were checked against them (OPS-055).
resource "aws_db_parameter_group" "dr" {
  count    = local.dr_standby ? 1 : 0
  provider = aws.dr
  name     = "${var.name}-dr"
  family   = "postgres16"
  tags     = merge(var.tags, { data_class = "phi", dr = "true" })

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name         = "max_connections"
    value        = tostring(var.db_max_connections)
    apply_method = "pending-reboot"
  }

  parameter {
    name  = "password_encryption"
    value = "scram-sha-256"
  }
}

resource "aws_db_instance" "dr_replica" {
  count                 = local.dr_standby ? 1 : 0
  provider              = aws.dr
  identifier            = "${var.name}-dr"
  replicate_source_db   = aws_db_instance.this.arn # cross-region replicas name the source by ARN
  instance_class        = var.db_instance_class
  storage_type          = "gp3"
  max_allocated_storage = var.db_max_allocated_storage_gb
  # Multi-AZ from day one: after promotion this is the production database and a later modify would cost minutes of RTO.
  multi_az                            = true
  publicly_accessible                 = false
  db_subnet_group_name                = aws_db_subnet_group.dr[0].name
  vpc_security_group_ids              = [aws_security_group.dr_db[0].id]
  parameter_group_name                = aws_db_parameter_group.dr[0].name
  storage_encrypted                   = true
  kms_key_id                          = local.dr_key_arn["phi"] # the DR replica of the cell phi key: data never leaves the country
  backup_retention_period             = 35                      # PITR continues on the promoted instance
  deletion_protection                 = true
  skip_final_snapshot                 = false
  final_snapshot_identifier           = "${var.name}-dr-final"
  delete_automated_backups            = false
  iam_database_authentication_enabled = true
  auto_minor_version_upgrade          = true
  performance_insights_enabled        = true
  performance_insights_kms_key_id     = local.dr_key_arn["ops"]
  tags                                = merge(var.tags, { data_class = "phi", dr = "true" })

  lifecycle {
    precondition {
      condition     = length(var.dr_azs) >= 2
      error_message = "dr_azs needs at least two zones in dr_region for the DR subnet group."
    }
  }
}

# The RPO is 5 minutes: replica lag beyond it pages. The topic carries alarm metadata only (no PHI); it is not KMS-encrypted
# because CloudWatch cannot publish to a topic under the replica key's default policy.
resource "aws_sns_topic" "dr_alerts" {
  count    = local.dr_standby ? 1 : 0
  provider = aws.dr
  name     = "${var.name}-dr-alerts"
  tags     = merge(var.tags, { data_class = "ops", dr = "true" })
}

resource "aws_cloudwatch_metric_alarm" "dr_replica_lag" {
  count               = local.dr_standby ? 1 : 0
  provider            = aws.dr
  alarm_name          = "${var.name}-dr-replica-lag"
  alarm_description   = "DR replica more than ${var.dr_replica_lag_alarm_seconds}s behind the primary (RPO 5 min, OPS-059)"
  namespace           = "AWS/RDS"
  metric_name         = "ReplicaLag"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.dr_replica[0].identifier }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  threshold           = var.dr_replica_lag_alarm_seconds
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching" # no lag sample means replication is not running
  alarm_actions       = [aws_sns_topic.dr_alerts[0].arn]
  ok_actions          = [aws_sns_topic.dr_alerts[0].arn]
  tags                = merge(var.tags, { data_class = "ops", dr = "true" })
}
