# OPS-026 retention. Defaults are the spec values (metrics 13 months, logs 180 d = CERT-In for IN, traces 14 d).
variable "obs_metrics_retention_days" {
  type    = number
  default = 395
}
variable "obs_log_retention_days" {
  type    = number
  default = 180
}
variable "obs_trace_retention_days" {
  type    = number
  default = 14
}
