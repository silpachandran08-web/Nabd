# OPS-055: PgBouncer pool metrics (published by infra/pgbouncer/pool-metrics.py every minute into Nabd/PgBouncer, dimension
# Instance) and the alarms. avg_wait_time is how long clients waited for a server connection: > 100 ms for 5 minutes pages.
resource "aws_cloudwatch_metric_alarm" "pool_wait" {
  alarm_name          = "${var.name}-pgbouncer-wait"
  alarm_description   = "PgBouncer avg_wait_time above ${var.alarm_pool_wait_ms} ms for 5 minutes (pool saturated: raise pool size or find the slow query)"
  namespace           = "Nabd/PgBouncer"
  metric_name         = "AvgWaitTimeMs"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  datapoints_to_alarm = 5
  threshold           = var.alarm_pool_wait_ms
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching" # missing data is the heartbeat alarm's job
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
  tags                = merge(var.tags, { data_class = "ops" })
}

resource "aws_cloudwatch_metric_alarm" "pool_waiting_clients" {
  alarm_name          = "${var.name}-pgbouncer-cl-waiting"
  alarm_description   = "Clients queued for a server connection (cl_waiting > 0) for 5 minutes"
  namespace           = "Nabd/PgBouncer"
  metric_name         = "ClWaiting"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  datapoints_to_alarm = 5
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
  tags                = merge(var.tags, { data_class = "ops" })
}

resource "aws_cloudwatch_metric_alarm" "pool_heartbeat" {
  alarm_name          = "${var.name}-pgbouncer-metrics-heartbeat"
  alarm_description   = "No PgBouncer pool sample for 5 min: the pooler or its publisher is down, so pool health is unknown"
  namespace           = "Nabd/PgBouncer"
  metric_name         = "SvActive"
  statistic           = "SampleCount"
  period              = 60
  evaluation_periods  = 5
  datapoints_to_alarm = 5
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  tags                = merge(var.tags, { data_class = "ops" })
}

# HA: the NLB target group must keep both PgBouncer instances healthy.
resource "aws_cloudwatch_metric_alarm" "pooler_unhealthy_hosts" {
  alarm_name          = "${var.name}-pgbouncer-unhealthy-host"
  alarm_description   = "A PgBouncer instance is unhealthy behind the NLB (the other still serves, HA is gone)"
  namespace           = "AWS/NetworkELB"
  metric_name         = "UnHealthyHostCount"
  dimensions          = { LoadBalancer = aws_lb.pooler.arn_suffix, TargetGroup = aws_lb_target_group.pooler.arn_suffix }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  tags                = merge(var.tags, { data_class = "ops" })
}
