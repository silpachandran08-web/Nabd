# PgBouncer AMI (OPS-055)

The cell has no internet egress, so the pooler hosts boot from a baked image. Bake once per release, register the id as `pgbouncer_ami_id` in the cell's tfvars (an empty value fails the plan on purpose).

Contents (arm64, matches `pgbouncer_instance_type`):
- PgBouncer **>= 1.21** (`pgbouncer --version`; the boot script halts the host below 1.21, because prepared statements through transaction pooling need `max_prepared_statements`). Service `pgbouncer`, user `pgbouncer`.
- AWS CLI v2, `psql` client, `openssl`, cron.
- `/etc/pgbouncer/rds-ca-bundle.pem`: the RDS global CA bundle for the region (server TLS is `verify-full`).
- `/usr/local/bin/pool-metrics.py` copied from `infra/pgbouncer/pool-metrics.py`.

Needs external action: the bake itself (no AWS account here), and confirming the package source ships PgBouncer >= 1.21.
