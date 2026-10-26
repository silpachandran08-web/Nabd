# Data tier: Postgres, PgBouncer, Comms and Edge databases (OPS-055)

Code: `infra/terraform/modules/cell/aws/data_tier_*.tf` (+ `postgres.tf`), `infra/db/bootstrap-{roles,pooler,satellites}.sql`, `infra/pgbouncer/`. Nothing here has been applied: every step marked **Needs external action** requires the AWS account, the baked AMI or a staging cluster.

## Shape
```
API pods (nabd_app, nabd_reports) --6432--> pool.<zone> (internal NLB) --> 2 x PgBouncer (2 AZs, transaction mode) --5432--> RDS Postgres 16 Multi-AZ
relay worker, migrate job, bootstrap scripts ----------------------------5432--> db.<zone> (DIRECT, same RDS)
comms-gateway (comms_app) ----> nabd_comms      patient-edge (edge_app) ----> nabd_edge     (same RDS, no grant on Core)
```
- Prod-in (DP section 6): `db.r7g.4xlarge` (16 vCPU / 128 GB), storage autoscaling 500 GB to 2 TB, `max_connections = 200`, Multi-AZ, KMS-encrypted, 35-day PITR (OPS-054), `rds.force_ssl`. Created **fresh**: no data is imported from Render (DEC-23). If DEC-23 is overturned, a scrub-and-import job is a new story.
- Pool budget: 2 instances x (24 `nabd_app` + 4 `nabd_reports`) = 56 server connections, plus 30 reserved for direct clients, must fit in 200 (Terraform refuses a plan that does not). `nabd_app` pool 48 = 3x vCPU.
- PgBouncer >= 1.21 with `max_prepared_statements = 200`: pgjdbc prepared statements work through the pool. If a pooler older than 1.21 is ever forced, set `prepareThreshold=0` in the JDBC URL instead; the boot script halts hosts below 1.21.
- Tenant isolation is pool-safe: `TenantContext` uses `set_config('app.tenant_id', ..., true)` (transaction-local). Role-level timeouts (`ALTER ROLE nabd_app SET ...`) survive transaction pooling; session-level `SET` does not.

## Which endpoint to use
| Client | Host (Terraform output `data_tier`) | Why |
|---|---|---|
| API (`DB_URL`, `DB_USER=nabd_app`) and reports | `pooler_host:6432` | business traffic; JDBC `sslmode=require` |
| Relay worker (`RELAY_DB_URL`) | `direct_host:5432` | advisory lock + LISTEN are session state; the worker refuses to start if its lock URL contains `:6432` (`RelayConfig`) |
| Migrate job (`nabd_migrator`), bootstrap SQL | `direct_host:5432` | DDL, advisory locks, `SET ROLE` |
| comms-gateway / patient-edge | pooler or direct, their own database | database `nabd_comms` / `nabd_edge`; add the database to `data_tier_pgbouncer.ini.tftpl` when they move behind the pool |

## Provision a cell (order matters)
1. **Needs external action.** Bake the AMI (`infra/pgbouncer/AMI.md`), set `pgbouncer_ami_id` in the cell tfvars.
2. **Needs external action.** `tofu apply` for the cell (CI `plan-in` plans it; applying is manual). Outputs: `data_tier`, `postgres`, `dns`.
3. **Needs external action.** From inside the VPC, with the RDS master secret, against `direct_host`:
   `bootstrap-roles.sql` (OPS-003, in `nabd`), `bootstrap-pooler.sql` (in `nabd`), `bootstrap-satellites.sql` (in `postgres`). Passwords: `nabd_*` from the secret manager, `pgbouncer_auth`/`comms_*`/`edge_*` from Secrets Manager `<cell>/db/<role>` (Terraform generates them).
4. **Needs external action.** Migrate job as `nabd_migrator` (direct). comms/edge run their own Flyway as `comms_migrator`/`edge_migrator`.
5. Point the API `DB_URL` at `pooler_host:6432`, set the worker's `RELAY_DB_URL` to the direct host.
6. Verify (below).

## Verify
- Roles and FORCE RLS: the existing `RlsSelfCheck` runs at API boot as `nabd_app`; the app refuses to start if any tenant table is not forced.
- `infra/db/test-satellite-grants.sh` (runs in CI job `data-tier-grants`): connects as the comms/edge roles and proves `permission denied` on every Core table.
- **Needs external action.** API suite through PgBouncer in staging-in: the suite is Testcontainers-based and has no remote-target mode (see `docs/ops/staging-in.md`); until it has one, run the k6 script `infra/staging-in/rolling-deploy.k6.js` against the pooler URL and watch `cl_waiting`.
- Multi-AZ, encrypted, PITR 35 days, no public access: `python3 infra/terraform/check-terraform.py` (static) and conftest on the plan (`policy/cell.rego`, `policy/data_tier.rego`).

## Metrics and alerts
`infra/pgbouncer/pool-metrics.py` (cron, every minute, per instance) publishes `ClWaiting`, `SvActive`, `AvgWaitTimeMs` to CloudWatch namespace `Nabd/PgBouncer`.
| Alarm | Condition |
|---|---|
| `<cell>-pgbouncer-wait` | `AvgWaitTimeMs` > 100 for 5 consecutive minutes |
| `<cell>-pgbouncer-cl-waiting` | `ClWaiting` > 0 for 5 minutes |
| `<cell>-pgbouncer-metrics-heartbeat` | no sample for 5 minutes (pooler or publisher down) |
| `<cell>-pgbouncer-unhealthy-host` | an instance fails the NLB health check (HA lost) |
All go to the cell's `alerts` SNS topic. On a wait alarm: check `SHOW POOLS` for the saturated role, find the slow or idle-in-transaction session in `pg_stat_activity` first, raise `pgbouncer_app_pool_size` only if `max_connections` has room.

## Rotating a password
Re-run the Terraform apply with `terraform taint random_password.db_role["<role>"]`, then the matching bootstrap script (both are re-runnable and rotate), then restart the service (pooler hosts re-read `userlist.txt` on instance refresh).
