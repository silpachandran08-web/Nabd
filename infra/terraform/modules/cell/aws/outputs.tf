output "network" {
  value = { vpc_id = aws_vpc.this.id, private_subnet_ids = aws_subnet.private[*].id, db_sg_id = aws_security_group.db.id }
}
output "postgres" {
  value = { identifier = aws_db_instance.this.identifier, address = aws_db_instance.this.address, arn = aws_db_instance.this.arn, master_secret_arn = one(aws_db_instance.this.master_user_secret[*].secret_arn) }
}
output "valkey" {
  value = {
    primary_endpoint  = aws_elasticache_replication_group.valkey.primary_endpoint_address
    reader_endpoint   = aws_elasticache_replication_group.valkey.reader_endpoint_address
    auth_secret_arn   = aws_secretsmanager_secret.valkey_auth.arn
    tls_required      = aws_elasticache_replication_group.valkey.transit_encryption_mode == "required"
    replicas          = aws_elasticache_replication_group.valkey.num_cache_clusters - 1
    encrypted_at_rest = aws_elasticache_replication_group.valkey.at_rest_encryption_enabled
    zones             = aws_elasticache_replication_group.valkey.preferred_cache_cluster_azs
  }
}
output "storage" {
  value = { data_bucket = aws_s3_bucket.this["data"].id, audit_bucket = aws_s3_bucket.this["audit"].id }
}
output "kms" {
  value = { for k, v in aws_kms_key.root : k => v.arn }
}
output "dns" {
  value = { zone_id = aws_route53_zone.private.zone_id, db = aws_route53_record.db.fqdn }
}
output "backup" {
  value = {
    pitr_days          = aws_db_instance.this.backup_retention_period
    alerts_topic       = aws_sns_topic.alerts.arn
    drill_sg_id        = aws_security_group.drill.id
    drill_subnet_group = aws_db_subnet_group.this.name
  }
}
output "dr" {
  value = {
    region            = var.dr_region
    bucket            = aws_s3_bucket.dr.id
    backup_copy_arn   = one(aws_db_instance_automated_backups_replication.dr[*].id)
    kms               = local.dr_key_arn
    db_replica        = null # placeholder: cross-region read replica needs a DR VPC; added with the DR failover story
    container_cluster = null # placeholder: DEC-21
  }
}
