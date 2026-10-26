# OPS-055: data tier. Defaults are the prod-in sizing; the cell's tfvars override per environment.
variable "pgbouncer_ami_id" {
  description = "AMI baked from infra/pgbouncer/AMI.md (PgBouncer >= 1.21, RDS CA bundle, pool-metrics.py, aws CLI). Empty fails the plan on purpose: no pooler, no business traffic."
  type        = string
  default     = ""
}

variable "pgbouncer_instance_type" {
  type    = string
  default = "c7g.large" # arm64: bake the AMI for arm64
}

variable "pgbouncer_app_pool_size" {
  description = "Server connections per PgBouncer instance for nabd_app (2 instances x 24 = 48 = 3x a 16 vCPU primary)."
  type        = number
  default     = 24
}

variable "pgbouncer_reports_pool_size" {
  description = "Server connections per PgBouncer instance for nabd_reports."
  type        = number
  default     = 4
}

variable "pgbouncer_max_client_conn" {
  type    = number
  default = 2000
}

variable "db_max_connections" {
  description = "Postgres max_connections (DP section 6). Pool sizes are checked against it."
  type        = number
  default     = 200
}

variable "db_max_allocated_storage_gb" {
  description = "RDS storage autoscaling ceiling (prod-in: 2 TB)."
  type        = number
  default     = 2048
}

variable "db_direct_reserve" {
  description = "Connections kept for direct clients (relay lock + LISTEN, migrate job, admin, superuser_reserved, exporters)."
  type        = number
  default     = 30
}

variable "alarm_pool_wait_ms" {
  description = "PgBouncer avg_wait_time alarm threshold in milliseconds (OPS-055: 100)."
  type        = number
  default     = 100
}
