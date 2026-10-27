variable "dr_standby_enabled" {
  type    = bool
  default = false
}

variable "dr_vpc_cidr" {
  type    = string
  default = "10.42.0.0/16"
}

variable "dr_azs" {
  type    = list(string)
  default = []
}

variable "dr_replica_lag_alarm_seconds" {
  description = "Replica lag that pages (the RPO is 5 minutes)."
  type        = number
  default     = 300
}
