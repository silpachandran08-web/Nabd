# Contract every provider submodule must satisfy. Read later by the control-plane regions screen (no PHI).
output "cell" {
  value = {
    cell        = var.cell
    country     = var.country
    environment = var.environment
    region      = var.region
    dr_region   = var.dr_region
  }
}

output "network" { value = one(module.aws[*].network) }
output "postgres" { value = one(module.aws[*].postgres) }
output "storage" { value = one(module.aws[*].storage) }
output "valkey" { value = one(module.aws[*].valkey) }
output "kms" { value = one(module.aws[*].kms) }
output "dns" { value = one(module.aws[*].dns) }
output "backup" { value = one(module.aws[*].backup) }
output "dr" { value = one(module.aws[*].dr) }
output "dr_standby" { value = one(module.aws[*].dr_standby) } # OPS-059
