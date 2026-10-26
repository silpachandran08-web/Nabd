# OPS-055: see aws/data_tier_outputs.tf
output "data_tier" { value = one(module.aws[*].data_tier) }
