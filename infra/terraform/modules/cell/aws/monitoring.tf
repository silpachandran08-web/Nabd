# OPS-054: PITR lag alarm. infra/backup/pitr-lag.py publishes Nabd/Backup PitrLagSeconds (now - LatestRestorableTime) every 5 min.
resource "aws_sns_topic" "alerts" {
  name              = "${var.name}-alerts"
  kms_master_key_id = aws_kms_key.root["ops"].arn
  tags              = merge(var.tags, { data_class = "ops" })
}

resource "aws_cloudwatch_metric_alarm" "pitr_lag" {
  alarm_name          = "${var.name}-pitr-lag"
  alarm_description   = "Latest restorable time older than ${var.pitr_lag_alarm_seconds}s (RPO 5 min)"
  namespace           = "Nabd/Backup"
  metric_name         = "PitrLagSeconds"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.this.identifier }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = var.pitr_lag_alarm_seconds
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching" # missing data is the heartbeat alarm's job
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
  tags                = merge(var.tags, { data_class = "ops" })
}

resource "aws_cloudwatch_metric_alarm" "pitr_lag_heartbeat" {
  alarm_name          = "${var.name}-pitr-lag-heartbeat"
  alarm_description   = "No PITR lag sample for 15 min: the lag publisher is down, so the lag is unknown"
  namespace           = "Nabd/Backup"
  metric_name         = "PitrLagSeconds"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.this.identifier }
  statistic           = "SampleCount"
  period              = 300
  evaluation_periods  = 3
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  tags                = merge(var.tags, { data_class = "ops" })
}
