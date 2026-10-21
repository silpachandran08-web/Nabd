# OPS-056 edge inputs. Defaults keep the edge off until the cell wires a hostname (module main.tf is shared; wire there when OPS-055/058 land).
variable "edge_api_host" {
  type        = string
  default     = ""
  description = "Public API hostname served from this cell (e.g. api.<clinic domain>). Empty = no edge."
}
variable "edge_app_host" {
  type        = string
  default     = ""
  description = "Public staff-app (BFF) hostname."
}
variable "edge_public_zone_id" {
  type        = string
  default     = ""
  description = "Public Route 53 zone that holds the validation and alias records."
}
variable "edge_rate_limit_5min" {
  type        = number
  default     = 2000
  description = "Requests per source IP per 5 minutes before the WAF blocks (per-IP rate limit)."
}
variable "edge_log_retention_days" {
  type    = number
  default = 365 # CERT-In asks for 180 days in India; stay above it
}

locals {
  edge = var.edge_api_host != "" && var.edge_app_host != "" && var.edge_public_zone_id != ""
}
