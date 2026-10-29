environment       = "prod"
vpc_cidr          = "10.41.0.0/16"
db_instance_class = "db.r7g.4xlarge"
dns_zone          = "in.prod.cells.nabd.internal"
# OPS-055 (DP section 6): India primary 16 vCPU / 128 GB memory-optimised, storage autoscaling up to 2 TB.
db_allocated_storage_gb     = 500
db_max_allocated_storage_gb = 2048

# OPS-059 (ADR-09): Hyderabad warm standby. DR VPC must not overlap vpc_cidr.
dr_standby_enabled = true
dr_vpc_cidr        = "10.42.0.0/16"
dr_azs             = ["ap-south-2a", "ap-south-2b", "ap-south-2c"]
