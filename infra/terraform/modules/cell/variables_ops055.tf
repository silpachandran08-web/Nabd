# OPS-055: only what differs per cell is passed through; the rest has prod-in defaults in aws/variables_ops055.tf.
variable "pgbouncer_ami_id" {
  description = "Baked PgBouncer AMI (infra/pgbouncer/AMI.md). Empty fails the plan."
  type        = string
  default     = ""
}

variable "db_max_allocated_storage_gb" {
  description = "RDS storage autoscaling ceiling."
  type        = number
  default     = 2048
}

variable "pgbouncer_app_pool_size" {
  description = "Server connections per PgBouncer instance for nabd_app; checked against Postgres max_connections."
  type        = number
  default     = 24
}
