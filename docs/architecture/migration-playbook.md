# Migration playbook (expand / contract)

Goal: a deploy during OPD hours never locks `queue_entries` and never breaks pods still running the previous release. Enforced in CI by `ci/migration-lint/lint.py` (rules MIG-001..MIG-007). Status: draft, needs review by Data & Infra and Core Backend leads (NFR).

## Version numbers
- Flyway runs with out-of-order disabled. A new file must be higher than the highest version on `main` (MIG-005).
- Reserve the number first in `db/migrations/VERSIONS` (`V<n> <owner>`); the lint fails an unlisted file (MIG-006). Gaps are allowed, collisions are not.

## Lock timeout
Every migration starts with `SET lock_timeout = '5s';` (MIG-004). A blocked DDL then fails fast and the deploy retries, instead of queueing behind a long transaction and blocking every reader and writer behind it.

## Concurrent indexes
`CREATE INDEX` on an existing table takes a write-blocking lock (MIG-001). Use `CREATE INDEX CONCURRENTLY IF NOT EXISTS`. It cannot run inside a transaction, so give the file its own Flyway script config with `executeInTransaction=false` and keep it to index statements only. Indexes on a table created in the same file are exempt.

## Check constraints
A plain `ADD CONSTRAINT ... CHECK` scans the table under an ACCESS EXCLUSIVE lock (MIG-002). Do it in two steps:
```sql
ALTER TABLE queue_entries ADD CONSTRAINT queue_entries_status_check CHECK (status IN ('waiting','done')) NOT VALID;
ALTER TABLE queue_entries VALIDATE CONSTRAINT queue_entries_status_check;  -- SHARE UPDATE EXCLUSIVE, reads/writes continue
```
(V37 swapped the status/source CHECKs without `NOT VALID`; this is the fix.)

## Set not null
`SET NOT NULL` scans the table (MIG-003). Add a validated `CHECK (col IS NOT NULL)` first; Postgres 12+ then skips the scan:
```sql
ALTER TABLE queue_entries ADD CONSTRAINT qe_encounter_nn CHECK (encounter_id IS NOT NULL) NOT VALID;
ALTER TABLE queue_entries VALIDATE CONSTRAINT qe_encounter_nn;
ALTER TABLE queue_entries ALTER COLUMN encounter_id SET NOT NULL;
ALTER TABLE queue_entries DROP CONSTRAINT qe_encounter_nn;
```
(V43 backfilled and set NOT NULL in one transaction; split it as below.)

## Expand and contract
1. Expand release: add the new column/table (nullable, no default rewrite), dual-write in code.
2. Backfill as a batched job (below), then validate constraints.
3. Switch reads; stop using the old column in code. Ship.
4. Contract release (a later release): `DROP COLUMN`, `DROP TABLE`, `ALTER COLUMN ... TYPE` (MIG-007). The lint fails these unless the PR carries the `contract` label and sets `CONTRACT_REF` to the earlier expand release/PR. Never drop in the release that stops using the object: old pods still read it during rollout.

## Batched backfill
Never backfill a large table inside the migration transaction. Run a job that updates 10,000 rows per batch (keyed range or `WHERE id IN (SELECT ... LIMIT 10000 FOR UPDATE SKIP LOCKED)`), commits, then `pg_sleep(0.1..1)` between batches. Suppress `set_updated_at` for the job so a backfill does not rewrite every `updated_at` (DP-13), with `SET LOCAL nabd.skip_updated_at = 'on';` as the first statement of each batch transaction (V225: `set_updated_at()` honours it; no superuser or DISABLE TRIGGER needed). `LastVisitRepairJob` is the reference use; `Ops004BackfillTriggerTest` is the check. Re-runnable: the batch predicate selects only rows still to do.

## CI
- Stage 4 (any PR touching `db/**`): lint added files, `flyway validate` + `migrate` on an empty DB and on the seeded snapshot (< 3 min), see `.github/workflows/migrations.yml`.
- Main build: ERD (SVG) and per-table schema doc generated from the catalog with SchemaSpy, published as build artefacts. `docs/Nabd HMS - Database Architecture.pdf` is superseded (covers 12 of 86 tables).
