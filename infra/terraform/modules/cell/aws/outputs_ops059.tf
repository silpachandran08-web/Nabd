# OPS-059: what infra/dr/dr_failover.py needs (identifiers and endpoints, no secrets).
output "dr_standby" {
  value = local.dr_standby ? {
    region             = var.dr_region
    replica_identifier = aws_db_instance.dr_replica[0].identifier
    replica_arn        = aws_db_instance.dr_replica[0].arn
    replica_endpoint   = aws_db_instance.dr_replica[0].address
    vpc_id             = aws_vpc.dr[0].id
    subnet_ids         = aws_subnet.dr_private[*].id
    db_security_group  = aws_security_group.dr_db[0].id
    alarm              = aws_cloudwatch_metric_alarm.dr_replica_lag[0].alarm_name
  } : null
}
