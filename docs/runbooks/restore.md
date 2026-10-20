# Restore from point-in-time backup (OPS-054)

Targets: internal **RPO <= 5 min, RTO <= 1 h**. Contract (FRD NFR-13 / CEO-20) is RPO/RTO <= 24 h; DEC-36 is open, engineering keeps 5 min / 1 h either way.

> Current production is still **Neon + Render** (`docs/ops/initial-setup.md`). Everything below applies to the IN cell
> (`infra/terraform/cells/in`) once DEC-04 is decided and the cell is applied. Until then, Neon's own PITR is the only backup.

## What protects the data

| Control | Where | Value |
|---|---|---|
| PITR window | `modules/cell/aws/postgres.tf` `backup_retention_period` | 35 days, continuous WAL archiving by RDS |
| Encryption | same, `kms_key_id` | cell `phi` root key (KMS, rotation on) |
| Copy in Hyderabad | `aws_db_instance_automated_backups_replication.dr` | 35 days, DR replica of the `phi` key; DR region must pass the residency check in `modules/cell/residency.tf` (IN: `ap-south-1`, `ap-south-2` only) |
| Deletion protection | `deletion_protection = true`, `delete_automated_backups = false`, final snapshot on delete | backups survive an accidental instance delete |
| Lag alarm | `monitoring.tf`: `<cell>-pitr-lag` (> 300 s) and `<cell>-pitr-lag-heartbeat` (no sample for 15 min) | fed by `infra/backup/pitr-lag.py` every 5 min (`.github/workflows/backup.yml`) |
| Policy gate | `infra/terraform/policy/cell.rego` | plan fails if PITR < 35 days, storage unencrypted, deletion protection off, or DB public |

Inspect (Needs external action until the cell exists):
```
aws rds describe-db-instances --db-instance-identifier nabd-in-<env> \
  --query 'DBInstances[0].[BackupRetentionPeriod,StorageEncrypted,KmsKeyId,LatestRestorableTime,DeletionProtection]'
aws rds describe-db-instance-automated-backups --region ap-south-2     # the Hyderabad copy
aws rds describe-db-instance-automated-backups --region <any non-IN region>   # must list nothing for nabd-in-*
```

## Real incident: restore to a point in time
1. Pick the target time (UTC) just before the damage. Announce in the incident channel; stop writes if the damage is ongoing.
2. Run the drill script against prod with that time, from the drill runner:
   `infra/backup/restore-drill.sh nabd-in-prod <target-time>` (keeps timings; verification output in `<record>.verify.json`).
   For a real cut-over, comment out the `cleanup` trap first so the restored instance is kept.
3. Check `verify_ok` and `app_boot_ok`. A `missing` line for a tenant means data written before the target time is not in the copy: stop and escalate.
4. Cut over: rename instances (`aws rds modify-db-instance --new-db-instance-identifier`), move the restored one into the production DB security group, re-enable Multi-AZ and deletion protection, and repoint `DB_URL` (private DNS `db.<zone>` CNAME, then roll the API).
5. Record the incident timeline and actual RPO (target time vs. last good write) in the incident doc.

## Drill (quarterly, game day)
Automated: `.github/workflows/backup.yml` job `drill` runs on 15 Jan / Apr / Jul / Oct (cron `17 4 15 1,4,7,10 *`), or on demand.
It restores staging-in (default) to "15 minutes ago" into the isolated drill security group (reachable only from inside that group,
never from production), runs `verify-restore.py` (per-tenant row counts at the target time and `verify_audit_chain`, as `nabd_reports`
with `app.tenant_id` set, RLS stays on), boots the API image against the copy (`/actuator/health/readiness`), writes timings JSON,
and deletes the copy. It fails if verification fails, the app does not become ready, or the total exceeds 1 h.

Read the verification:
- `problems` (fail): rows created at or before the target time missing from the copy; audit chain broken.
- `notes` (ok): rows deleted on the source after the target time; tables without `created_at` (counted, not compared).
- A transaction open across the target time can show a few rows "missing" (its `created_at` is before the target, its commit after). Pick a quiet target time, or re-run with a target 1 minute earlier before escalating.

## Drill record

| Date | Env | Target time | Restore | Verify | Boot | Total | <= 1 h | Verify OK | Who |
|---|---|---|---|---|---|---|---|---|---|
| 2026-10-19 | local Docker, Postgres 16, all 95 migrations, 2 tenants (mechanics only, not an RTO number) | 2026-10-19T19:47:59Z | 2 s | 1 s | not run | n/a | n/a | yes (and fails on damaged copy) | OPS-054 (`infra/backup/test-local-pitr.sh`) |
| _first real drill_ | staging-in | | | | | | | | **Needs external action**: cell applied (DEC-04), drill runner, repo variables |

Schedule: first staging-in drill when the cell is applied (S2 target); prod-in after PITR is enabled there (S4); then quarterly
on the 15th of Jan / Apr / Jul / Oct. Next scheduled run: **15 Jan 2027** (cron above), provided the cell exists by then.
After each drill, add a row here (copy the numbers from the `restore-drill` artifact).

## Known limits
- RDS uploads transaction logs about every 5 minutes, so lag sits near the 300 s threshold; if the alarm flaps, raise
  `pitr_lag_alarm_seconds` only with a DEC-36 decision (it is the RPO).
- The Hyderabad copy is restorable only into a Hyderabad network, which the skeleton does not create yet (DR VPC is a placeholder);
  the drill restores in Mumbai.
- The GitHub schedule can be delayed; the heartbeat alarm covers gaps longer than 15 min.
