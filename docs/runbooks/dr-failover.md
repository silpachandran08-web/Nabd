# DR failover and failback (OPS-059)

Mumbai (ap-south-1) is primary, Hyderabad (ap-south-2) holds a cross-region read replica (ADR-09, India only). Targets: RTO 1 hour,
RPO 5 minutes (internal targets; the contract values are DEC-36, not decided). Related: `restore.md` (point in time restore, known limits),
`prod-in-cutover.md`, `data-tier.md`.

## What exists
- Terraform: `infra/terraform/modules/cell/aws/dr_standby.tf`. Off unless `dr_standby_enabled = true` and the cell has a DR region (`cells/in/prod.tfvars` turns it on). Builds the DR VPC, encrypted multi-AZ replica (35 day retention, deletion protection), and a ReplicaLag alarm (default 300 s) to the `dr_alerts` topic.
- Script: `infra/dr/dr_failover.py` (`preflight`, `failover`, `failback`, `--selftest`). Prints commands unless `--execute`. Refuses a region outside the country list (same list as `residency.tf`) and any config that still has `REPLACE_` values. Writes a timed JSON record.
- Manifests for the DR cluster: `infra/dr/render_dr_manifests.py` (region swapped, target group ARNs filled).
- Rehearsal workflow: `.github/workflows/dr-rehearsal.yml`.

## Decide to fail over
A person decides; there is no automatic failover (it would risk two writers). Fail over when Mumbai is down for longer than the team can wait and replica lag is under 5 minutes.

## Failover
1. Fill `dr-config.json` from `infra/dr/dr-config.example.json` (identifiers only, never secrets).
2. `python3 infra/dr/dr_failover.py preflight --config dr-config.json` (replica available, encrypted, lag at most 300 s, DR cluster answers).
3. `python3 infra/dr/dr_failover.py failover --config dr-config.json --execute --record failover.json` (add `--primary-reachable no` if Mumbai cannot be reached to scale down; then fence it by hand later).
4. Steps, in order: preflight, fence Mumbai runtime, promote replica, wait available, sync database secret to DR, start DR runtime, wait rollout, DNS to Hyderabad, readiness check, audit chain check.
5. After: replay unsent notifications (`Valkey` state is not replicated, see findings), tell clinics, open the incident review.

## Failback
`python3 infra/dr/dr_failover.py failback --config dr-config.json --execute --record failback.json`. Rebuilds Mumbai as a replica of the promoted DR database (old Mumbai instance is kept renamed `-pre-failback`), fences Hyderabad, waits for zero lag, compares key tables (stops if Mumbai is missing rows), promotes Mumbai, moves DNS back, verifies, then recreates the Hyderabad replica.

## Measured timings
| Run | Date | Environment | Total | RTO 1 h | RPO estimate | Note |
|---|---|---|---|---|---|---|
| Dry run (mechanics only) | 2026-10-27 | none, commands printed | not a measurement | n/a | n/a | proves step order and guards only |
| Real rehearsal | not done | staging | not measured | not measured | not measured | Needs external action (AWS account, two clusters, `tofu apply`) |

Do not copy the RTO/RPO claim anywhere until the real row exists.

## Open findings (found while writing this; none fixed here)
1. DR edge is not defined: no ALB, WAF or certificate in Hyderabad in Terraform; the script needs `dr_alb_dns` from somewhere.
2. No PgBouncer or pooler in the DR region; the runtime would connect straight to the database.
3. The database URL secret must be synced to the DR cluster (`db_url_sync_command` is operator supplied); no automation.
4. `infra/prod-in/egress-policy.yaml` allows only Mumbai service endpoints; DR pods would be blocked from RDS/S3/SES until a DR copy is applied.
5. Terraform state: promoting a replica and the failback renames make state differ from reality; `tofu import` after each run.
6. Valkey (queues, idempotency) is not replicated; undelivered outbox rows need a redrive.
7. The audit chain check connects as `nabd_reports` and relies on it being allowed to call `verify_audit_chain` for sampled tenants; `infra/backup/verify-restore.py` does this inside a transaction with the tenant set; confirm against a real database.
8. AWS and kubectl command lines are written from documentation and not proven against a live account.
