output "network" {
  value = { vpc_id = aws_vpc.this.id, private_subnet_ids = aws_subnet.private[*].id, db_sg_id = aws_security_group.db.id }
}
output "postgres" {
  value = { identifier = aws_db_instance.this.identifier, address = aws_db_instance.this.address, arn = aws_db_instance.this.arn, master_secret_arn = one(aws_db_instance.this.master_user_secret[*].secret_arn) }
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
