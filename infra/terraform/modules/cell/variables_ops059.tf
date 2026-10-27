# OPS-059: the DR standby (Hyderabad for IN). Off by default; only what differs per cell is passed through (aws/variables_ops059.tf has the rest).
variable "dr_standby_enabled" {
  description = "Create the DR network and the cross-region encrypted read replica in dr_region (needs dr_region != region)."
  type        = bool
  default     = false
}

variable "dr_vpc_cidr" {
  description = "CIDR of the DR VPC; must not overlap vpc_cidr (routing between the two is a platform concern)."
  type        = string
  default     = "10.42.0.0/16"
}

variable "dr_azs" {
  description = "Availability zones in dr_region for the DR private subnets (at least 2 for Multi-AZ Postgres)."
  type        = list(string)
  default     = []
}
