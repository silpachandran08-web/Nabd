# Audit and PHI read logs: monthly partitions and archive of sealed months (OPS-068)

Sources: DP-11, MF-65, SEC-28, DEC-22, T-156 (design), T-209 (implementation). Related: PLT-038 (chain heads, V1380),
PLT-039 (`phi_read_log`, V1390), OPS-067 (monthly seals, V2140), ADM-016 (offboarding erasure, V3420).

Status of each part is in section 12. The design review (S7) and the DPO's approval of the retention periods are external.

## 1. Why

`audit_log` is expected to grow 300 to 500 GB a year and `phi_read_log` about ten times the write audit (DEC-22). One heap
per table cannot be kept cheaply for the statutory period, cannot be dropped a month at a time, and makes every index one
large tree. Monthly partitions let a month that is past its online retention leave the database as one unit, after it has
been exported and checked.

## 2. Shape

| | `audit_log` | `phi_read_log` |
|---|---|---|
| Partition key | `RANGE (created_at)`, UTC months | `RANGE (read_at)`, UTC months (since V1390) |
| Primary key | `(id, created_at)` (was `(id)`) | `(tenant_id, read_at, id)` (unchanged) |
| Months created by | `audit_log_ensure_partitions(from, months)`: daily job and the migration, 3 months ahead | `phi_read_log_ensure_partitions`, every minute by `PhiReadSealJob` |
| Month name | `audit_log_yYYYYmMM` | `phi_read_log_yYYYYmMM` |
| Default partition | `audit_log_default` | `phi_read_log_default` |
| Migration | V3671 | none needed |

Native partitioning is used, not pg_partman: the extension is not in the test image (`postgres:16-alpine`) and is not
installed anywhere yet. `phi_read_log` already works this way. Moving to pg_partman later changes who creates the months,
not the table shape.

`phi_read_log` is not converted and does not get a `created_at` column or a PK of `(id, created_at)`: it has been monthly
`RANGE (read_at)` since PLT-039 and its PK already contains the partition key. Rewriting it would cost a full copy for no gain.

### 2.1 `audit_log` has one extra level

```
audit_log                 partitioned by RANGE (created_at); owner nabd_migrator (two existing tests pin this owner)
  audit_log_months        the only partition, MINVALUE..MAXVALUE, itself RANGE (created_at); owner nabd_definer
    audit_log_y2026m11    one per UTC month
    audit_log_default     rows of a month that has no partition
```

Adding or detaching a partition needs the owner of the direct parent. The runtime caller is the API role, which reaches
DDL only through SECURITY DEFINER functions owned by `nabd_definer`, and every such function must be owned by a BYPASSRLS
role (`RlsSchemaInvariantTest`). `audit_log` itself must stay owned by `nabd_migrator` (`AuditChainConcurrencyTest`,
`S11QaAuditPhiReadConcurrencyTest`). The extra level gives `nabd_definer` a parent it owns without changing the owner of
`audit_log`. Plans are not affected: Postgres flattens the levels and scans the months directly.

### 2.2 Primary key change

A unique index on a partitioned table must contain the partition key, so the PK becomes `(id, created_at)`. `id` alone is
no longer unique by constraint. It stays unique in practice: every id comes from one sequence and is taken inside the
chain trigger under the tenant's chain-head lock (V1380). Nothing references `audit_log(id)` by foreign key.

## 3. Rules on the parent

Everything is defined on `audit_log` and reaches the months as follows.

| Rule | How it reaches a month |
|---|---|
| Chain trigger `trg_audit_log_chain` (BEFORE INSERT, row) | cloned by Postgres onto every present and future partition |
| `trg_audit_log_no_update`, `trg_audit_log_no_delete` (row) | cloned; the delete trigger keeps its ADM-016 `WHEN` clause because the migration re-creates each trigger from `pg_get_triggerdef` |
| `trg_audit_log_no_truncate` (statement) | statement triggers are not cloned: the migration and `audit_log_ensure_partitions` create it on every table under the parent |
| Forced RLS, four per-command policies (`enforce_tenant_rls`) | queries through the parent use the parent's policies; each partition also gets its own forced policies, so direct access is tenant-bound too |
| Grants | the parent gets exactly the old table's ACL; partitions have no grant for `nabd_app` (rows are reached only through `audit_log`) |
| Indexes | the same five, defined on the parent; a month's indexes are renamed to `<parent index>_yYYYYmMM` so plans name the parent's index |
| Check constraints, FK to `tenants` | defined on the parent, inherited |

`ALTER TABLE audit_log DISABLE TRIGGER ...` (superuser only) recurses to the partitions, so the existing tamper tests that
switch a trigger off and edit a row behave as before.

## 4. Default partition and missing months

A row whose month has no partition lands in `audit_log_default` instead of failing the business transaction. This should
never happen: months are created 3 ahead, daily. If it does (the job was down for more than 3 months):

- `audit_log_ensure_partitions` skips that month with a warning, because Postgres refuses to create a partition whose rows
  already sit in the default partition, and goes on with the later months.
- Alert on `SELECT count(*) FROM audit_log_default` above zero.
- Repair is manual and needs a maintenance window: as the owner, detach the default partition, create the month, move the
  rows with triggers off (`session_replication_role = replica`, ids and hashes copied as they are), re-attach. The rows are
  never rewritten, so the chain is unaffected.

Rows in the default partition are never archived.

## 5. Seals and the chain across a month boundary

OPS-067 writes one `audit_chain_seals` row per tenant per month: the id and hash of the tenant's last row with
`created_at` before the end of that month, signed with an HMAC.

`verify_audit_chain(tenant)` walked the chain from the tenant's first row, whose `prev_hash` must be NULL. Once a month is
dropped the first online row points at a row that is gone. V3670 adds one rule: the first online row may carry a
`prev_hash` when `audit_chain_anchor_ok` finds a seal of that tenant with exactly that hash, a lower id and an earlier
month, for a month that `archive_manifests` records as archived. The walk then continues as before. Without a manifest
row, or with a seal that carries another hash, the same gap is reported as a break. Rows that are online are checked as
before.

Who can make a false anchor: nobody through the API role. `archive_manifests` is written only by `audit_archive_drop`;
seals are insert-only for the API role and a forged seal needs the exact hash the first online row points at.

The platform chain (`tenant_id` NULL) is not covered by the monthly seal job. `audit_archive_drop` seals it for the month
being dropped under the nil uuid, with a signature computed by the archive job. That seal is made at archive time, not at
month end, so it proves less than a tenant seal. Extending the monthly job to the platform chain is a follow-up.

### 5.1 A chain link across midnight

`created_at` is the start time of the writing transaction; the id is taken later, under the chain-head lock. A transaction
that began before midnight UTC on the last day of a month and wrote after another transaction of the same tenant that
began after midnight leaves a row of the old month with a higher id than a row of the new month. Dropping the old month
would then cut that tenant's chain in the middle.

`audit_archive_heads` reports this per tenant (`contiguous = false`) and the archive job refuses the month. Later months
of the table wait, because a chain can only be cut at its oldest end. This is rare (same tenant, both writes within the
same instant around one midnight a month) but will happen eventually. There is no automatic handling yet: the month stays
online and the job fails daily (OPS-013 `job_failures_total{job="audit-archive"}`). Follow-up options: let the chain
trigger never write a `created_at` earlier than the chain's previous row, or archive the two months as one unit.

## 6. Retention policy

`data_retention_policies (region, data_class, online_months, archive_years, legal_basis, approved_by, approved_at)`, PK
`(region, data_class)`, `data_class` in (`audit_log`, `phi_read_log`). No row is seeded: the periods need counsel's and
the DPO's confirmation per market (T-238, T-273). The API role can read the table and cannot write it.

A month is due (`audit_archive_due`) when all of these hold:

- at least one policy of the table is approved;
- every clinic that has rows in the month belongs to a region whose policy for the table is approved;
- the month is older than the longest `online_months` among those regions and all approved policies of the table
  (platform rows and empty months have no region of their own).

With `online_months = 13` in November 2026, September 2025 and earlier are due; October 2025 to November 2026 stay online.
The rule is enforced inside `audit_archive_drop`, not only by the job. The current month is never due.

`archive_years` is stored and not acted on: nothing deletes an archive (section 9).

## 7. Archive

`AuditArchiveService`, daily at 02:40 UTC (`audit-archive`, ShedLock, OPS-013 metrics). The same job first calls
`audit_log_ensure_partitions(today, 3)`. Archiving runs only with `app.audit.archive.enabled=true`; the default is off,
because the only store adapter today is a local directory.

Per table, oldest month first, stop at the first month that is not due or fails:

1. Check the month.
   - `audit_log`: write any missing seal for the month (`AuditChainSealer.sealMonth`, idempotent); for every tenant with
     rows in the month the whole online chain must verify (`verify_audit_chain`), the month must be one stretch of the chain
     (5.1), and the tenant's last row in the month must equal its seal, signature included.
   - `phi_read_log`: no unsealed row, and every batch that lies wholly in the month must recompute to its stored
     `batch_hash` (`phi_read_archive_bad_batches`). A batch with rows in two months is not recomputed.
2. Export every row in id order to a temporary file (`audit_archive_rows`, read through a cursor).
3. Put the file in the store, read it back from the store and compare the SHA-256.
4. `audit_archive_drop`, one transaction: the month must be due and still hold exactly the exported row count and highest
   id (SHARE lock on the month); the platform chain is sealed; the month is detached and dropped; the manifest row is written.

The order differs from the story text ("detach, export, verify, drop") on purpose: the month is exported while still
attached and is detached and dropped only after the stored file has been verified. The end state is the same and there is
no moment in which the rows are neither in the table nor in a verified file. A month past retention is not written to
(rows are append-only and new rows carry `now()`); step 4 checks that anyway.

### 7.1 Format

One object per month: `audit-archive/<table>/<partition>.jsonl.gz`. Gzip, one JSON object per line, one line per row,
in id order. A line is `to_jsonb(row)`: every column by name, `before`/`after` as nested JSON, timestamps as ISO-8601
with microseconds and offset, `inet` as text. A line loads back with `jsonb_populate_record` and the row then hashes to
its stored `row_hash` (the restore drill checks this for every row).

Parquet (the story's format) is not built: it needs a library and the sprint allows no new dependency. The line format
maps one to one to columns; files can be transcoded later, which changes the object's hash and so needs a new manifest
column, not a new export.

`archive_manifests (table_name, partition_name, month, region, object_key, row_count, sha256, archived_at, restored_at)`,
PK `(table_name, partition_name)`. `sha256` is the hash of the stored (compressed) bytes. `region` lists the region codes
of the clinics that had rows in the month.

### 7.2 Store

`AuditArchiveStore` is the port (`put`, `get`). `LocalAuditArchiveStore` writes under `app.audit.archive.dir` and refuses
to work when the directory is not set. It is not a production store: no object lock, no in-region guarantee, no encryption.
The production adapter (regional bucket, object lock in compliance mode, SSE-KMS with the region key) is infrastructure
and is not built. Do not set `app.audit.archive.enabled=true` before it exists.

## 8. Reading archived months: data subject requests and legal hold

`AuditArchiveService.read(table, tenantId, from, to, consumer)` streams one tenant's archived rows of a time range from
the files. Nothing is restored or attached. A file that no longer hashes to its manifest raises an error after the rows
were handed over; the caller must then discard them.

- DSR export (PLT-047, built in parallel): for the "who accessed my record" list it must add `read('phi_read_log', ...)`
  filtered on `patient_id` to the online rows, and likewise `read('audit_log', ...)` where it lists changes. Not wired here.
- Actor names in audit rows are not erased by a DSR; an erased patient appears by pseudonymous reference (PLT-048, not part
  of this story). Archived files are never rewritten.
- Legal hold: the product has no legal-hold feature yet (T-238). Nothing in this design deletes an archive, so every
  archived month is held. When archive expiry (`archive_years`) is built it must check holds first.

Open decision (needs counsel): the offboarding erasure (ADM-016) empties `audit_log` and `phi_read_log` for the clinic and
issues a certificate. It cannot reach archived months, and with object lock nobody can. Either the certificate text must
say that archived audit months are kept for the statutory period, or archives must be written per clinic and encrypted
with the clinic's data key so that destroying the key erases them.

## 9. Restore drill

`AuditArchiveService.restoreDrill(partition)`: checks the file against the manifest hash, loads it into
`<partition>_restored` (a plain table next to the live one: forced RLS, no grant for the API role, never attached),
checks the row count and, for `audit_log`, that every row hashes to its own `row_hash`, sets `restored_at` and drops the
copy. "Readable within 1 h" is a property of production volumes and the production store; it has only been run on a few
rows in tests. Run the drill on staging with one real month before go-live and record the time.

## 10. Cost of the conversion and the online plan

### 10.1 What V3671 does (small tables only)

One transaction: `ACCESS EXCLUSIVE` on `audit_log` from the first statement to commit. Every audited write in the system
waits for that long (and fails after its own `lock_timeout` of 3 s). Inside: rename the old table, create the new one with
its indexes, create the months from the oldest row to three months ahead, copy every row with its id and hashes
(`OVERRIDING SYSTEM VALUE`, no trigger), compare row counts and every row's hashes, set the sequence, create triggers,
RLS and grants. The table is written once more in full (heap and five indexes) and so is the WAL for it.

The migration refuses a table over 1 GB (`pg_total_relation_size`). Below that the block is in the order of a minute on
production hardware; measure it on a copy first. The old table is kept as `audit_log_unpartitioned` (no grant for the API
or reports role, still append-only) and is dropped by `db/deferred/M2__drop_audit_log_unpartitioned.sql` two releases later.

### 10.2 Online conversion (tables over 1 GB): copy and swap

The table is append-only, so a copy never has to track updates or deletes: only the tail of new rows.

1. Before: backup and a named restore point; `verify_audit_chain` clean for every tenant; free disk at least 1.3 times the
   table; replica lag alarms on. Run as `nabd_migrator`.
2. Build `audit_log_new` with the statements of V3671 (same columns, PK `(id, created_at)`, `audit_log_months`, default
   partition, one month per month from the oldest row to three months ahead). No triggers, no RLS yet. Indexes on the
   months may be built after the backfill to make it faster.
3. Backfill in id ranges of about 50 000 rows, one transaction each, throttled on replica lag:
   `INSERT INTO audit_log_new (...) OVERRIDING SYSTEM VALUE SELECT ... FROM audit_log WHERE id > :a AND id <= :b`.
   Ids, `prev_hash`, `row_hash` and `hash_v` are copied as they are.
4. Catch up the tail (rows with an id above the last one copied) until one pass takes well under a second.
5. Swap, one transaction with `lock_timeout = '3s'`, retried until it gets the lock:
   `LOCK TABLE audit_log IN ACCESS EXCLUSIVE MODE`; copy the last tail; compare row count, highest id and each tenant's
   chain head (`audit_chain_heads`) between the two tables; rename `audit_log` to `audit_log_unpartitioned` (and its
   indexes and sequence) and `audit_log_new` to `audit_log`; set the new sequence to the old one's value; create the
   triggers from the old table's definitions; `enforce_tenant_rls` on the parent and every partition; copy the ACL; give
   the tables under `audit_log_months` to `nabd_definer`. The lock is held for the tail copy and catalogue changes only.
6. After: `ANALYZE audit_log`; `verify_audit_chain` for every tenant; deploy the release with V3671, which finds the table
   partitioned and installs only `audit_log_ensure_partitions`.
7. Rollback. Before the swap: drop `audit_log_new`. During the swap: the transaction rolls back. After the swap: the same
   swap in reverse (lock, copy the rows written since from the new table to the old one with triggers off, rename back),
   or point-in-time recovery.

The script for steps 2 to 5 is not written. It must be rehearsed on a production-size copy to measure backfill time, WAL
volume, replica lag and the lock time of the swap. Its approval is part of the design review.

### 10.3 Locks in normal operation

| Operation | Lock | Effect |
|---|---|---|
| Insert | ROW EXCLUSIVE on the parent, the level below and one month | none |
| New month (`audit_log_ensure_partitions`) | builds a standalone table, then `ATTACH`: SHARE UPDATE EXCLUSIVE on `audit_log_months`, ACCESS EXCLUSIVE on the empty default partition | writes continue |
| Archive: export | reads only | none |
| Archive: drop | SHARE on the old month, then `DETACH`: ACCESS EXCLUSIVE on `audit_log_months` for an instant, `lock_timeout` 5 s | audited writes wait for the catalogue change; on timeout the job retries the next day |

`DETACH ... CONCURRENTLY` cannot be used: it does not work with a default partition or inside a transaction.

## 11. Security note

- New capability for the API role. Before this story no path let the API role remove an audit row. `audit_archive_drop`
  (EXECUTE granted to `nabd_app`) drops a whole month. It is bounded in the database: only a monthly partition of the two
  tables, only when due under approved policies the API role cannot write, only with the exact row count and highest id.
  The database cannot check that the export really exists; that check is in the job. A compromised API role could
  therefore drop months that are already past approved retention without a valid file. Mitigations to add with the
  infrastructure: grant the function to a separate worker role when the worker gets its own DataSource (same step as the
  `nabd_platform` note of PLT-009), and the object-lock bucket.
- Archive files hold what `audit_log` holds: clinical before/after values (PHI), actor names and IP addresses. They need
  the same protection as the database: in-region bucket, object lock, encryption with the region key. The local adapter
  provides none of this.
- Tenant isolation: partitions have no grant for the API role and their own forced policies; the read path filters on the
  tenant id the caller passes and must only be called with the bound tenant; log lines carry partition names and tenant
  ids only.
- Tamper evidence: unchanged for online rows. For archived months it rests on the seal (HMAC key in the secret manager),
  the manifest hash and, once it exists, object lock. The seal key holder can forge a seal; an asymmetric or KMS signature
  is the OPS-067 follow-up.
- `id` uniqueness is no longer enforced by a constraint (2.2).
- Offboarding erasure does not reach archives (section 8, open decision).

## 12. Status

| Part | Status |
|---|---|
| This document | written; review and approval external |
| Retention policy and manifest tables, archive functions, `verify_audit_chain` anchor rule (V3670) | built, tested |
| Archive job, read path, restore drill behind the store port | built, tested with the local adapter |
| `phi_read_log` monthly partitions | already in place (V1390) |
| `audit_log` conversion (V3671) | see the story report for whether it shipped in this sprint |
| Online conversion script (10.2) | plan only |
| Parquet | not built (no library) |
| In-region object-lock bucket adapter | not built (infrastructure) |
| Approved retention periods | not seeded (counsel and DPO) |
| DSR export calling the read path (PLT-047) | not wired |
| Legal hold, archive expiry | not built |
