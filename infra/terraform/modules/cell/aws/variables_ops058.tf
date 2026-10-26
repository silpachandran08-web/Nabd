# OPS-058: who may assume the secret roles. Empty = the roles trust only principals of this cell that carry the tag
# cell_name = var.name (set by the compute story); nothing outside the account can assume them either way.
variable "secrets_app_principal_arns" {
  type    = list(string)
  default = []
}
variable "secrets_operator_principal_arns" {
  type    = list(string)
  default = []
}
variable "secret_audit_retention_days" {
  type    = number
  default = 400 # a CloudWatch Logs retention value; matches the longest audit window of the in-region log store
}
