resource "aws_db_subnet_group" "this" {
  name       = var.name
  subnet_ids = aws_subnet.private[*].id
  tags       = merge(var.tags, { data_class = "network" })
}

resource "aws_db_parameter_group" "this" {
  name   = var.name
  family = "postgres16"
  tags   = merge(var.tags, { data_class = "phi" })

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
}

resource "aws_db_instance" "this" {
  identifier                    = var.name
  engine                        = "postgres"
  engine_version                = "16"
  instance_class                = var.db_instance_class
  allocated_storage             = var.db_allocated_storage_gb
  storage_type                  = "gp3"
  db_name                       = "nabd"
  username                      = "nabd_admin"
  manage_master_user_password   = true # password generated and held in Secrets Manager, never in state or git
  master_user_secret_kms_key_id = aws_kms_key.root["ops"].arn
  multi_az                      = true
  publicly_accessible           = false
  db_subnet_group_name          = aws_db_subnet_group.this.name
  vpc_security_group_ids        = [aws_security_group.db.id]
  parameter_group_name          = aws_db_parameter_group.this.name
  storage_encrypted             = true
  kms_key_id                    = aws_kms_key.root["phi"].arn
  # OPS-054: PITR window 35 days (RDS maximum), continuous WAL archiving by RDS.
  backup_retention_period             = 35
  backup_window                       = "20:30-21:00" # UTC = 02:00 IST
  maintenance_window                  = "sun:21:30-sun:22:00"
  copy_tags_to_snapshot               = true
  deletion_protection                 = true
  skip_final_snapshot                 = false
  final_snapshot_identifier           = "${var.name}-final"
  delete_automated_backups            = false
  iam_database_authentication_enabled = true
  auto_minor_version_upgrade          = true
  performance_insights_enabled        = true
  performance_insights_kms_key_id     = aws_kms_key.root["ops"].arn
  tags                                = merge(var.tags, { data_class = "phi" })
}

# OPS-054: PITR backups copied continuously to the DR region (Hyderabad for IN), 35 days, encrypted with the DR replica key.
resource "aws_db_instance_automated_backups_replication" "dr" {
  count                  = local.cross_region ? 1 : 0
  provider               = aws.dr
  source_db_instance_arn = aws_db_instance.this.arn
  kms_key_id             = local.dr_key_arn["phi"]
  retention_period       = 35
}
