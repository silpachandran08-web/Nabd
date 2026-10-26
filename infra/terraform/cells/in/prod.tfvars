environment       = "prod"
vpc_cidr          = "10.41.0.0/16"
db_instance_class = "db.r7g.4xlarge"
dns_zone          = "in.prod.cells.nabd.internal"
# OPS-055 (DP section 6): India primary 16 vCPU / 128 GB memory-optimised, storage autoscaling up to 2 TB.
db_allocated_storage_gb     = 500
db_max_allocated_storage_gb = 2048
