variable "cloud" {
  description = "Provider submodule to use. Only \"aws\" exists today; DEC-04 may add others."
  type        = string
  default     = "aws"
  validation {
    condition     = contains(["aws"], var.cloud)
    error_message = "cloud must name an existing submodule (aws)."
  }
}

variable "cell" {
  description = "Cell id, e.g. in, ae, sa, qa."
  type        = string
  validation {
    condition     = can(regex("^[a-z]{2}$", var.cell))
    error_message = "cell must be a two-letter lowercase id."
  }
}

variable "country" {
  description = "ISO country the cell's data must stay in (IN, AE, SA, QA)."
  type        = string
  validation {
    condition     = contains(["IN", "AE", "SA", "QA"], var.country)
    error_message = "country must be one of IN, AE, SA, QA."
  }
}

variable "environment" {
  description = "staging or prod."
  type        = string
  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment must be staging or prod."
  }
}

variable "service" {
  description = "Value of the service tag."
  type        = string
  default     = "nabd-core"
}

variable "region" {
  description = "Primary region (provider region name)."
  type        = string
}

variable "dr_region" {
  description = "DR region for backup copies and replica targets; must be in the same country."
  type        = string
}

variable "azs" {
  description = "Availability zones for private subnets (at least 2 for Multi-AZ Postgres)."
  type        = list(string)
  validation {
    condition     = length(var.azs) >= 2
    error_message = "At least two AZs are needed for Multi-AZ Postgres."
  }
}

variable "vpc_cidr" {
  type    = string
  default = "10.40.0.0/16"
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "db_allocated_storage_gb" {
  type    = number
  default = 50
}

variable "audit_lock_days" {
  description = "Object-lock retention for the audit archive. 180 = CERT-In log retention floor; compliance confirms the final value."
  type        = number
  default     = 180
  validation {
    condition     = var.audit_lock_days >= 180
    error_message = "audit_lock_days must be at least 180 (CERT-In)."
  }
}

variable "dns_zone" {
  description = "Private DNS zone for the cell, e.g. in.staging.cells.nabd.internal."
  type        = string
}

variable "pitr_lag_alarm_seconds" {
  description = "Alarm when the latest restorable time is older than this (OPS-054: 5 min)."
  type        = number
  default     = 300
}

variable "valkey_node_type" {
  description = "OPS-020: ElastiCache Valkey node type (primary and its replica)."
  type        = string
  default     = "cache.t4g.small"
}
