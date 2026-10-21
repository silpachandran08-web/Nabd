# OPS-055 data tier, evaluated with the other policies in this directory (package main).
package main

import rego.v1

pooler_asgs := [r |
	some r in changes
	r.type == "aws_autoscaling_group"
	endswith(r.change.after.name, "-pgbouncer")
]

# Postgres is Multi-AZ and storage autoscales (prod-in: 2 TB ceiling).
deny contains msg if {
	some r in changes
	r.type == "aws_db_instance"
	r.change.after.multi_az != true
	msg := sprintf("%v: Postgres must be Multi-AZ", [r.address])
}

deny contains msg if {
	some r in changes
	r.type == "aws_db_instance"
	object.get(r.change.after, "max_allocated_storage", 0) <= object.get(r.change.after, "allocated_storage", 0)
	msg := sprintf("%v: Postgres needs storage autoscaling (max_allocated_storage above allocated_storage)", [r.address])
}

deny contains msg if {
	some r in changes
	r.type == "aws_db_parameter_group"
	not max_connections(r.change.after)
	msg := sprintf("%v: parameter group must pin max_connections to 200 or less (pool sizes are budgeted against it)", [r.address])
}

max_connections(a) if {
	some p in a.parameter
	p.name == "max_connections"
	to_number(p.value) <= 200
}

# PgBouncer HA: two instances, internal NLB, IMDSv2.
deny contains msg if {
	some r in pooler_asgs
	r.change.after.min_size < 2
	msg := sprintf("%v: PgBouncer needs at least 2 instances", [r.address])
}

deny contains msg if {
	some r in changes
	r.type == "aws_lb"
	endswith(r.change.after.name, "-pooler")
	not r.change.after.internal == true
	msg := sprintf("%v: the pooler load balancer must be internal", [r.address])
}

deny contains msg if {
	some r in changes
	r.type == "aws_launch_template"
	not imdsv2(r.change.after)
	msg := sprintf("%v: launch template must require IMDSv2", [r.address])
}

imdsv2(a) if a.metadata_options[0].http_tokens == "required"

# A pooler without the wait alarm (> 100 ms for 5 min) is not shippable.
deny contains msg if {
	count(pooler_asgs) > 0
	not pool_wait_alarm
	msg := "PgBouncer present but no AvgWaitTimeMs alarm (<= 100 ms threshold, >= 5 min)"
}

pool_wait_alarm if {
	some r in changes
	r.type == "aws_cloudwatch_metric_alarm"
	a := r.change.after
	a.metric_name == "AvgWaitTimeMs"
	a.threshold <= 100
	a.period * a.evaluation_periods >= 300
}
