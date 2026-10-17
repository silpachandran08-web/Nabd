# 03 — Data, Performance & Caching Review (Nabd HMS)

**Reviewer role:** Principal Data / Performance Engineer
**Date:** 2026-10-17
**Scope:** `db/migrations` (V1–V85, 60 files), `backend` (Spring Boot 3.3.4, JdbcTemplate, 463 Java files), `frontend` (Next.js 16.3.1 / React 19), `docs/Nabd HMS - Database Architecture.pdf`.
**Method:** I applied all 60 migrations in version order to a throwaway `postgres:16-alpine` container (every migration applied cleanly) and inspected the resulting catalog: 86 tables, 67 RLS policies, 137 CHECK constraints, 223 foreign keys. I then loaded a synthetic dataset of 200 tenants, 498k patients, 2.49M `queue_entries`, 1.99M `invoices` and 3M `audit_log` rows. I ran the application's own SQL under `EXPLAIN (ANALYZE, BUFFERS)` as a non-owner `nabd_app` role with RLS active, with `app.tenant_id` set exactly as `TenantContext` sets it. Wherever this report quotes a timing, it is measured, not estimated. The container has been removed. Nothing in the repo was modified apart from this file.

---

## 0. Executive summary

The schema is in better shape than most early-stage SaaS schemas. Every tenant table has RLS, timestamps are `timestamptz` throughout, money is `numeric` (never a float), list endpoints use keyset cursors (`common/Cursor.java`), double-booking is prevented by a partial unique index, and the outbox uses `FOR UPDATE SKIP LOCKED`. Clinical immutability is enforced with triggers. All of that is the right foundation.

Seven problems, though, will cause outages or very poor UX long before the target scale of 1,000 clinics and 50k visits a day:

1. **Missing patient index on `queue_entries`.** Every patient-scoped read of visits does a full **cross-tenant** sequential scan: 2.5 s per lookup at 2.5M rows. The Patient Registry runs that scan as a correlated subquery once per patient row. Measured: **24.9 s for 100 rows**. With the 3,000-row cap, that extrapolates to roughly 12 minutes per request.
2. **The OR-shaped RLS policy on 20 tables** (`... OR tenant_id::text = ANY(string_to_array(...))`, introduced for brand visibility) stops Postgres from using `tenant_id` as an index condition. Any query that omits an explicit `tenant_id` therefore scans every tenant's rows. `nextTokenNumber` takes **933 ms** inside the check-in advisory lock. Rewriting the policy as `tenant_id = ANY(uuid[])` brings it to **0.99 ms**.
3. **The audit hash-chain trigger** finds the previous hash with `ORDER BY id DESC LIMIT 1 FOR UPDATE`, filtered only by RLS. For a tenant with no recent audit rows it walks the entire table backwards: **5.0 s at 3M rows**. Because it holds a row lock until the business transaction commits, every audited write within a tenant runs one at a time.
4. **Polling fan-out from the frontend.** Arrivals and Nursing poll every 15 s, and each poll triggers per-row and per-doctor requests (N+1 over HTTP). At target scale that is about 5,000 HTTP requests per second of pure polling, before any user action. One of those per-doctor calls (`/queue/wait-estimate`) costs **2.25 s** on a large tenant.
5. **Synchronous WhatsApp and SMTP calls inside open DB transactions** (OTP request, outbox handler, provisioning). Combined with HikariCP's default pool of 10 connections and no statement or idle-in-transaction timeouts, the pool will be exhausted under load.
6. **No caching or push layer exists at all.** There is no Spring Cache, Caffeine, Redis, SSE or WebSocket, and the frontend has no data-fetching library. On top of every API call, each request pays for an extra DB transaction (session liveness check) plus a three-table join to compute the tenant context.
7. **The queue ETA is statistically wrong.** It is calculated as the mean of (invoice time − check-in time), which *includes waiting time*, multiplied by the number of patients ahead. No stage timestamps exist to fix this.

**Caching and refresh decision, in one paragraph.** Postgres stays the only source of truth. Every state change writes a domain event to the existing outbox in the same transaction. An outbox relay, woken by `pg_notify` rather than a 2-second poll, publishes the event to **Redis (Valkey) Streams** in each residency region. Four consumers read from there:

- a **cache invalidator**, which bumps versioned keys and evicts Caffeine L1 caches across instances;
- an **ETA engine**, which keeps queue projections in Redis;
- an **SSE gateway**, which pushes to staff screens, the patient app and the token TV;
- a **notifier**, which sends WhatsApp "leave home now" messages.

Data is cached by class. Real-time data is pushed, never polled. Availability data gets a 30 s TTL plus event invalidation. Reference and config data sits in Caffeine L1 and Redis L2 with versioned keys. Permissions are a JWT snapshot plus a Redis revocation set. PHI is never stored in shared caches unless the key is tenant-scoped, the value is minimised and the TTL is short. Safety-critical reads (allergies, active Rx, stock, slot booking) always go to the primary. Public aggregator data is served from the CDN with `stale-while-revalidate`. The frontend should adopt **TanStack Query v5**, with per-class `staleTime`, optimistic queue actions and SSE-driven cache patches.

---

## 1. Schema review

### 1.1 Inventory as applied

| Metric | Value | Evidence |
|---|---|---|
| Tables | 86 (76 `public`, 10 `master`) | catalog after V85 |
| RLS-enabled tables | 67. All use `ENABLE`; **none use `FORCE`** | `pg_class.relrowsecurity` / `relforcerowsecurity` |
| Policies with the brand-visibility OR clause | 20 (`patients`, `appointments`, `queue_entries`, `staff`, `sessions`, `roles`, `consents`, `clinical_notes`, `charge_catalogue`, `doctor_working_hours`, …) | `pg_policies` (V6, V41) |
| Tenant-bearing tables without RLS | `login_attempts`, `tenant_lifecycle_events`, `workflow_definitions`, `master.support_tickets`, `master.support_access_grants`, `master.subscriptions`, `master.discount_requests` | catalog. These are deliberate (V1:141–143, V40:11–14, master schema); covered by the security review |
| PKs | 81 `uuid DEFAULT gen_random_uuid()` (v4); 4 `bigint IDENTITY` (`audit_log`, `outbox_events`, `login_attempts`, `tenant_lifecycle_events`) | catalog |
| Column types | 169 `timestamptz`, 0 `timestamp without tz`, 37 `numeric`, **0 float/real/money**, 18 `jsonb`, 0 `json`, 0 PG enums (all `text` + CHECK) | `information_schema.columns` |
| FKs | 223: 215 `NO ACTION`, 8 `CASCADE` (only aggregate parent→child, e.g. `invoice_line_items→invoices` V20:38) | `pg_constraint` |
| Extensions | `citext`, `pg_trgm`, `pgcrypto` | V1:9, V3:12, V14:19 |

### 1.2 Normalization and modelling

- **`queue_entries` is overloaded.** It is the walk-in queue, the encounter segment *and* the encounter itself, via a self-FK `encounter_id` (V43:15–32). For now this is a pragmatic choice that V43:1–8 documents explicitly. The cost is that it is also the table every clinical artefact (notes, Rx, invoices, vitals, documents) hangs off, so it is the hottest and widest OLTP table: 25 columns and 6 indexes.
- **Stage timing is missing.** The table has a single `status` and `created_at`/`updated_at`. There is no `called_at`, `consult_started_at`, `consult_ended_at` or transition history, so consult durations cannot be measured, and that blocks any credible ETA (§5.5).
- **The audit log doubles as a functional data store.** The prescription signer comes from `audit_log` because `prescriptions` has no `signed_by` column (`PrescriptionPrintRepository.java:27–35`), and dental chart history is read from `audit_log` (`DentalRepository.java:85–92`). This ties feature correctness to audit retention, partitioning and archival. Add `prescriptions.signed_by` and a proper `dental_chart_events` table.
- **Pharmacy stock is a single integer** on `charge_catalogue.stock_qty` (V25:9–11). There is no batch, expiry or location model (V25:6–8 marks this "Phase 3"). "Stock by batch/expiry" therefore has no schema yet; see §2 for the target shape.
- **No drug master or formulary exists** (`PrescriptionService.java:52`, `:72`). Typeahead reads the tenant's own catalogue plus its whole prescribing history (§3).
- **Invoice and MRN numbering use global sequences shared across all tenants**: `invoices_number_seq` (V20:29–30) and `patients_mrn_seq` (V3:14–19). The table built for compliant numbering, `invoice_series` (V41:75), is never used by Java code. GST requires a consecutive series per GSTIN and financial year, and VAT/ZATCA has comparable rules. Global sequences also reveal platform volume to each tenant.

### 1.3 Naming consistency

Naming is consistently `snake_case`, and table names are plural. Index naming mixes the explicit `idx_*`/`uq_*` style with Postgres auto-names (e.g. `queue_entries_tenant_id_doctor_id_queue_date_token_number_key`). Some column names are reserved-ish or ambiguous: `queue_entries.class` (V43:37), `audit_log.before/after`, `patient_allergy_events.before/after`. Two tables called `sessions` and two called `login_attempts` exist in different schemas (`public` and `master`); this is legal but easy to mix up in ad-hoc SQL.

### 1.4 Primary keys and index locality

UUID v4 primary keys are generated by `gen_random_uuid()` or by the application (`QueueRepository.java:53`, `AuthService.mintTokenPair` → `UUID.randomUUID()`). Random v4 keys scatter inserts across the whole B-tree. On append-heavy tables (`queue_entries`, `invoices`, `invoice_line_items`, `appointments`, `whatsapp_messages`, `vitals`, `prescriptions`, `prescription_items`, `sessions`) this causes page splits, WAL amplification from full-page writes and a cold buffer cache once the PK index outgrows RAM.

**Recommendation:** switch new rows to **UUID v7**: the `uuidv7()` built-in on Postgres 18, or a time-ordered generator in Java on Postgres 16. Existing v4 values can stay; mixed values in one column are fine. Keep v4 only where an externally visible ID must not reveal creation time (consider this for `patients.id` if it appears in patient-facing URLs). Keep `bigint IDENTITY` for `audit_log` and `outbox_events`.

### 1.5 Tenant consistency of foreign keys

Every FK points at `parent(id)` alone, for example `queue_entries.patient_id → patients(id)` (V4:134). Postgres runs FK checks **without RLS**, so the database itself does not stop a row in tenant A from referencing a patient in tenant B; only application code prevents it. Add `UNIQUE (tenant_id, id)` on parent tables and composite FKs `(tenant_id, patient_id) → patients(tenant_id, id)` for the clinical spine: patients, queue_entries, appointments, prescriptions, invoices and staff. This is cheap because the unique index can replace the plain PK lookup.

### 1.6 ON DELETE behaviour

There are 8 `CASCADE`s, all within aggregates: line items, payments, package items, Rx items, favourite-set items and `tenant_lifecycle_events`. Everything else uses `NO ACTION`, which is correct for clinical data, because nothing clinical should cascade-delete. As a consequence, tenant offboarding cannot be done with `DELETE FROM tenants`; you need an explicit, audited purge or archive procedure per region (§4).

### 1.7 CHECK constraints and enum handling

The 137 CHECK constraints are good discipline, and state vocabularies are text + CHECK. The weak point is how the vocabularies evolve: each change does `DROP CONSTRAINT` + `ADD CONSTRAINT` on the hottest table. `queue_entries_status_check` alone has been rewritten three times: V5:82–83, V37:55–56 and V39:39–40, plus `queue_entries_source_check` at V37:60–61. `ADD CONSTRAINT ... CHECK` without `NOT VALID` scans the whole table while holding `ACCESS EXCLUSIVE`, which blocks all queue reads and writes for the duration. Use `ADD CONSTRAINT ... NOT VALID` followed by a separate `VALIDATE CONSTRAINT`, which takes only a `SHARE UPDATE EXCLUSIVE` lock. Alternatively, move high-churn vocabularies into reference tables with FKs.

### 1.8 Timestamps

`timestamptz` is used everywhere for instants. `date` and `time` are used correctly for clinic-local wall times (`doctor_working_hours.start_time` V4:65, `queue_entries.queue_date` V4:136). `ClinicClock` (`common/ClinicClock.java:14–47`) centralises "clinic today", which is a strong design. Two gaps:

- `tenants.timezone` is nullable with default `'UTC'` (V17:9). Make it `NOT NULL` and populate it from the region.
- **`updated_at` semantics were silently corrupted by backfills.** `trg_queue_entries_updated_at` (V4:147) fires on every UPDATE, so the full-table backfills at V37:72 and V43:28–29, :64 and :94 stamped every existing visit with the migration time. `PatientRepository.findLastVisitAt` (`PatientRepository.java:70–75`) reports `max(updated_at)` as "last visit", so every patient registered before V43 now shows the migration date as their last visit. Backfills must suppress the trigger (`SET LOCAL session_replication_role = replica` in a controlled migration, or a trigger guard), and "last visit" should come from a stage timestamp, not `updated_at`.

### 1.9 Soft delete

There is no `deleted_at` anywhere. Status columns cover the lifecycle (`patients.status`, `staff.status`, `charge_catalogue.active`, `patient_allergies.active` with a deactivation trail). For clinical data that is the right model, and I would not introduce a generic soft-delete column.

### 1.10 JSONB usage

The 18 `jsonb` columns are used sensibly:

| Use | Columns |
|---|---|
| Configuration | `roles.grants`, `workflow_definitions.steps`, `department_workflow_selection.toggles` |
| Event payloads | `outbox_events.payload`, `whatsapp_messages.params` |
| History images | `audit_log.before/after`, `*_events.before/after` |
| Document bodies | `clinical_documents.body/header` |
| Snapshots | `prescriptions.allergy_snapshot` (V79) |

No query filters inside JSONB, so no GIN indexes are needed. Watch two things:

- **Bloat.** `audit_log.before/after` will be the largest data in the system (§4).
- **Grants snapshots.** V38 had to backfill a permission into every built-in role's `grants` JSON (V38:8–13). This will recur with every new module. Model built-in role grants as a platform-versioned reference ("role template v7"), resolved at mint time, rather than a copied JSON snapshot.

### 1.11 Audit columns

Only `invoices.created_by`, `packages.created_by`, `dental_chart_entries.updated_by` and `patient_allergies.updated_by` carry actor columns. 39 tables have **no `updated_at`**, including mutable ones: `invoices` (`paid`, `status` change), `prescriptions` (draft→signed), `procedure_orders`, `administration_orders`, `waitlist_entries` and `vitals`. That is acceptable for audit, since `audit_log` covers it, but it **blocks delta sync and ETags** (§5.4). Add `updated_at` plus a monotonic `row_version`/`change_seq` to every table that the mobile app or a cache will sync.

### 1.12 Money

`numeric(12,2)` is used throughout, which is correct. However, there is **no currency column** on `invoices`, `invoice_payments` or `charge_catalogue`. Currency is implied by `tenants.region`, and `tenants.region` only allows `('IN','KSA')` (V1:23). UAE is not representable, and a 2-decimal scale cannot hold KWD, BHD or OMR (3 decimals) if expansion goes beyond UAE/KSA. Add `currency char(3)` to monetary headers now, while the tables are small, and use `numeric(14,3)` or a minor-unit `bigint` for any new GCC market.

### 1.13 Migration hygiene

| Issue | Where | Risk at scale |
|---|---|---|
| Version gaps: V49, V56–59, V61–68, V70–71, V73–77, V80–82, V84 | file list; V51:4–6 states Flyway runs **without out-of-order** | A gap filled later by a merged branch fails validation ("ignored migration"). Allocate numbers centrally, or use timestamp versions. |
| `CREATE INDEX` without `CONCURRENTLY` on large tables | V83:4 (`patients`), V43:32 (`queue_entries`), V53:19 (`prescription_items`), V60:21 | Blocks writes on the table for the whole build. Put each `CREATE INDEX CONCURRENTLY` in its own migration. Flyway runs it non-transactionally on PostgreSQL when it is the only statement. |
| CHECK swaps without `NOT VALID` | V5:82–83, V37:55–61, V39:39–40, V11:7–8, V28:18–19 | Full scan under `ACCESS EXCLUSIVE` on the hottest table |
| Full-table backfills inside the migration transaction, then `SET NOT NULL` | V37:72–76; V43:28–31, :64–65, :94 (three rewrites of every `queue_entries` row) | Long `ACCESS EXCLUSIVE` locks, table bloat, corrupted `updated_at` (§1.8). Use batched backfills in a job, then `CHECK (col IS NOT NULL) NOT VALID` → `VALIDATE` → `SET NOT NULL`. On PG12+ the last step can skip the scan when a validated CHECK exists. |
| Destructive changes without expand/contract | V5:36 `DROP COLUMN password_hash`; V39:37 `DROP COLUMN requires_vitals`; V40:100 `DROP TABLE visit_flow_steps` (created only one migration earlier, V39) | A rolling deploy with old pods still running fails. Make it policy: additive migration → deploy → backfill → contract in a later release (V85 already does this well; see V85:154–157). |
| `ADD COLUMN ... NOT NULL DEFAULT <constant>` | V2:6, V9, V10, V22:3, V24:3, V31:9, V34:45, V41:9–19, V42:6, V60:8–9 | Fine on PG11+ (metadata-only), **except** V24:3, which also adds a CHECK and so scans the table. |
| The architecture PDF covers only V1–V5 (12 tables) | `docs/Nabd HMS - Database Architecture.pdf` p.1 | 74 tables are undocumented. Regenerate it from the catalog in CI. |

---

## 2. Indexing

### 2.1 Hot query paths versus supporting indexes

These were measured on 2.49M `queue_entries`, 1.99M `invoices`, 498k `patients` and 3M `audit_log` rows, as the `nabd_app` role with RLS active.

| Hot path | Code | Supporting index today | Measured | Verdict / fix |
|---|---|---|---|---|
| Queue for a doctor today (with `tenant_id`) | `QueueRepository.listForDay` :85–91 | `idx_queue_doctor_date (tenant_id, doctor_id, queue_date)` V4:154 | **0.24 ms** | Good |
| Next token number (no `tenant_id` in SQL) | `QueueRepository.nextTokenNumber` :40–45 | Unique index exists, but the OR-RLS policy prevents its use | **933 ms**, bitmap on `queue_date` across all tenants | Add `tenant_id = ?` and fix the policy (§2.2). With the array policy: **0.99 ms** |
| Session occupancy at walk-in (no `tenant_id`) | `ScheduleRepository.countSessionOccupancy` :130–145 | same as above | same pattern as above | Same fix. Both run **inside** `pg_advisory_xact_lock`, so every concurrent check-in for that doctor waits behind them. |
| Wait estimate | `QueueRepository.averageRecentVisitMinutes` :137–151 | none on `invoices(tenant_id, doctor_id, created_at)` | **2,251 ms** (sorts all 39k invoices of the doctor) | Replace with an ETA projection (§5.5). If it is kept, add `invoices (tenant_id, doctor_id, created_at DESC)`. |
| Appointments by facility and date | `ScheduleBoardRepository`, `ReportsRepository.bookingsAndNoShows` :186–189 | `(doctor_id, start_time)`; `(tenant_id, created_at, id)`. **No `(tenant_id, start_time)`** | n/a | Add `appointments (tenant_id, start_time)`. With `doctor_id` first, the existing index can't serve a facility-wide day view. |
| Visits by tenant and day (reports, day close) | `ReportsRepository.visitsOn` :52–55 etc. | Served by accident by `idx_queue_entries_department (tenant_id, department_id, queue_date)` as a skip-less range | 0.24 ms (small tenant); degrades with department count | Add `queue_entries (tenant_id, queue_date)`, or partial `... WHERE status NOT IN ('completed','no_show','transferred_out')` for the active board |
| Patient timeline / last visit | `PatientRepository.registry` :122, `findLastVisitAt` :70–75, `ReportsRepository.noShowRiskToday` | **None on `queue_entries.patient_id`** | **2,485 ms** full seq scan (2.49M rows removed) | `CREATE INDEX CONCURRENTLY ... ON queue_entries (tenant_id, patient_id, queue_date DESC)`. Measured afterwards: **0.095 ms** with a tenant predicate, 0.55 ms through RLS once the array policy is in place. |
| Weight for paediatric dose calculator | `VitalsRepository.findLatestWeight` :48–55 | **None on `vitals.patient_id`** (only unique `queue_entry_id`) | seq scan (by plan) | `vitals (tenant_id, patient_id, weight_recorded_at DESC NULLS LAST) WHERE weight_kg IS NOT NULL`. This is a safety path. |
| Patient search by phone, MRN or name | `PatientRepository.search` :165–172 | `(tenant_id, phone)`, `(tenant_id, mrn)`, GIN trigram on `name` **without tenant** | **490 ms** at 100k patients. Trigram GIN unused; `phone ILIKE 'x%'` can't use the btree; `similarity() > 0.25` is not an indexable operator | See §2.4 |
| Duplicate detection | `findDuplicateCandidates` :193–205 | `(tenant_id, dob)` partially | `regexp_replace(phone)` is non-sargable | Stored generated `phone_digits` column plus btree |
| Encounter documents and notes by patient | `clinical_notes`, `clinical_documents`, `prescriptions`, `invoices` | `(tenant_id, patient_id, created_at DESC)` V19:23, V54, V21:62, V20:33 | n/a | Good |
| Pharmacy stock by item | `pharmacy_stock_movements (tenant_id, item_id, seq DESC)` V51:136 | yes | n/a | Good. Batch/expiry doesn't exist yet. Target: `stock_batches (tenant_id, item_id, expiry_date, batch_no)` plus a partial index `WHERE qty_on_hand > 0` for FEFO picking. |
| Audit search, per tenant | `idx_audit_log_tenant_created (tenant_id, created_at DESC)` V14:39 | yes | n/a | OK |
| Audit search, platform (`search_audit_log`) | V85:165–197 | Catch-all `(p IS NULL OR …)` predicates in a `SECURITY DEFINER` SQL function that cannot be inlined, so one generic plan for every filter combination; `ORDER BY id`; `ILIKE '%name%'` | n/a | Build the query dynamically in Java with only the active predicates, or add `(action, id)` / `(actor_id, id)` indexes plus a trigram index on `actor_name`. Archive older partitions (§4). |
| Audit chain head (trigger) | `audit_log_chain()` V14:62–76 | PK only | **5,041 ms** for a tenant with no audit rows yet (backward scan of 3M rows) | `audit_log (tenant_id, id DESC)`. Measured afterwards: **0.20 ms**. See DP-05 for the locking fix. |
| Outbox polling | `claim_outbox_events` V44:48–67 | partial `(next_attempt_at) WHERE status IN ('pending','processing')` V44:36 | n/a | Good. Add a partial index on `locked_until WHERE status='processing'` only if it shows up in slow-query logs. |
| Drug typeahead | `PrescriptionRepository.searchDrugs` :121–137 | `(tenant_id, lower(drug_name))` btree (V53:19) | `LIKE '%q%'` → scans the tenant's entire Rx-item history plus `GROUP BY` **on every keystroke** | See §5.7 |

### 2.2 RLS policies that defeat indexes (the most important indexing finding)

V6 and V41 rewrote 20 policies to:

```sql
tenant_id = current_setting('app.tenant_id', true)::uuid
OR tenant_id::text = ANY (string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
```

The OR, and the `tenant_id::text` cast in its second arm, make the qual non-indexable. The planner can only apply it as a `Filter`. Compare `invoices`, which has the simple policy: there the RLS predicate appears as an `Index Cond`. This is why every query that relies on RLS for tenant scoping, rather than an explicit `WHERE tenant_id = ?`, becomes a cross-tenant scan on `queue_entries`, `patients`, `appointments` and the other 17 tables.

**Fix (measured):** one array-based policy.

```sql
USING (tenant_id = ANY ((string_to_array(current_setting('app.tenant_id', true)
       || COALESCE(',' || NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ''), ','))::uuid[]))
```

`nextTokenNumber` went from **933 ms → 0.99 ms**, and the patient-timeline subquery from **2,485 ms → 0.55 ms**, with no SQL changes. Even simpler: have `TenantContext.set` write one GUC, `app.visible_tenant_ids`, in Postgres array literal form. In addition, **always** put `tenant_id = ?` in the SQL (the PDF, p.2, claims "every repository query also filters by tenant_id explicitly"; that is false for `QueueRepository.java:42`, `ScheduleRepository.java:135–140`, `:151`, and the registry subqueries at `PatientRepository.java:122–149`). Add an ArchUnit or grep test to enforce it.

### 2.3 Missing and redundant indexes

**Add** (all `CONCURRENTLY`, each in its own migration):

- `queue_entries (tenant_id, patient_id, queue_date DESC)` (Critical)
- `queue_entries (tenant_id, queue_date)`
- `vitals (tenant_id, patient_id, ...)` (partial, as in §2.1)
- `appointments (tenant_id, start_time)`; change `idx_appointments_patient` from `(patient_id)` to `(tenant_id, patient_id, start_time)`
- `invoices (tenant_id, created_at)`; `invoice_payments (tenant_id, recorded_at)`. Reports range over these and today ride on `idx_invoices_patient`, scanning the whole tenant section of the index: 3,801 buffers for 1,094 rows in a one-day window.
- `invoices (tenant_id, status) WHERE status IN ('unpaid','partial')` (outstanding totals, registry balance flag)
- `audit_log (tenant_id, id DESC)`
- `doctor_delays (tenant_id, doctor_id) WHERE cleared_at IS NULL` (replace the tenant-less unique partial index)
- `sessions (expires_at)` for the purge job; `whatsapp_messages (tenant_id, created_at)`; `outbox_events (created_at) WHERE status IN ('done','dead')` for cleanup, or partition (§4)

There are 136 FKs whose columns do not lead any index. Most are actor columns (`*_by → staff`) that are never deleted, so they are harmless. The ones queried by patient are listed above.

**Drop** (pure write amplification, exact duplicates of unique constraints):

- `idx_patients_mrn` (= `patients_tenant_id_mrn_key`), V3:45
- `idx_staff_tenant_email` (= `staff_tenant_id_email_key`), V1:76
- `idx_attendance_records_month` (= `attendance_records_tenant_id_staff_id_record_date_key`), V17:155
- `idx_sessions_token_hash` (partial copy of `sessions_token_hash_key`), V1:104

### 2.4 Patient search done right (Postgres, no new infrastructure)

- **Phone.** Add `phone_digits text GENERATED ALWAYS AS (regexp_replace(phone, '\D', '', 'g')) STORED`, plus `phone_last10 GENERATED ... (right(phone_digits, 10))`, with a btree `(tenant_id, phone_last10 text_pattern_ops)` for exact and prefix lookups. Reception types the local number, not the E.164 form. Also store an E.164-normalised value at write time (`PhoneNumbers` already exists in `common`).
- **Name.** Add `CREATE EXTENSION btree_gin` and `GIN (tenant_id, name_search gin_trgm_ops)`, where `name_search` is a normalised form: lower-case, Arabic diacritics and tatweel stripped, alef/yaa/taa-marbuta variants folded, whitespace collapsed. Query with index-supported operators: `name_search % $q` or `$q <% name_search` (word similarity), ordered by `similarity()` **after** the indexed filter, with `pg_trgm.similarity_threshold` set per query.
- **Structure.** Split the OR into a `UNION ALL` of three indexed branches (phone, MRN, name), each `LIMIT k`. That keeps every branch on an index.
- **When to change engines.** This holds comfortably to ~10M patients per region. Introduce OpenSearch only for the public aggregator (doctor and clinic discovery with geo and facets), never for PHI patient search (§5.7).

---

## 3. Query patterns in Java

| # | Pattern | Evidence | Impact | Fix |
|---|---|---|---|---|
| Q-1 | **Registry: correlated subqueries ×3,000 rows, then filtering, search and OFFSET paging in memory** | `PatientService.java:70–72` (`REGISTRY_CAP = 3000`), `:83–130`; SQL at `PatientRepository.java:119–160`. Each row runs `max(queue_date)` (seq scan, §2.1), condition, allergy, balance, package `LATERAL`, today's-token `LATERAL`, and an **O(N²) duplicate `EXISTS`** with `regexp_replace` on both sides. | Measured **24.9 s for 100 rows** on a 2,000-patient tenant (2.49M visits platform-wide). **Functional bug too:** the SQL orders by name with `LIMIT 3000` *before* in-memory search and filtering, so in a clinic with more than 3,000 patients, anyone after roughly the letter "M" can never be found or filtered in the registry. | Build a `patient_registry_summary` read model (one row per patient: last visit, next appointment, flags), maintained by events and triggers; do the duplicate flag in a nightly job; run SQL-side filters with keyset paging. |
| Q-2 | **HTTP N+1 from polling screens** | `frontend/app/arrivals/page.tsx:228–275`: `/queue` + `/staff/roster`, then `GET /patients/{id}` per uncached row, plus `/queue/wait-estimate` **and** `/doctors/{id}/delay` per roster member, every 15 s (`:47`, `:339–342`). `nursing/page.tsx:292–313`: `GET /patients/{id}` **for every row on every poll (no cache)**, plus `GET /clinical/vitals/{id}` for every row in the "recorded" states. That set grows all day because it includes `completed`. | For a clinic with 5 doctors and 60 visits/day: arrivals ≈ 12 requests/15 s; nursing at midday ≈ 6 + 2×40 ≈ 90 requests/15 s. Each `GET /patients/{id}` runs 5+ queries (`PatientService.get` :226–232), including `findLastVisitAt`. | Server-side projection endpoints (`/queue/board?date=`) that return display name, MRN, allergy flag, vitals-recorded flag, delay and ETA in one response; push updates (§5). |
| Q-3 | Per-row repo calls (classic N+1) | `PackageInstanceService.expiringSoon` :224–240 (one `listInstanceItems` per instance); `CheckoutService.resolvePharmacyLines` :283–299 (one `findPharmacyCharge` per line); `AuthService.mintTokenPair` :537–575 (≈7 queries per token mint, every 15 min per user) | Low to moderate | Batch with `= ANY(?)` (the codebase already does this in `ScheduleBoardRepository.java:74`). |
| Q-4 | **External I/O inside DB transactions** | `AuthService.requestOtp` :160–187 → `otpSender.send()` → Meta HTTP call (10 s read timeout, `MetaWhatsAppClient` :19–21) inside `@Transactional`. Same for `requestPinReset` :335–352. `OutboxEventProcessor.runHandler` :36–44 wraps `WhatsAppSendHandler.handle` (Meta HTTP) in a transaction. `ProvisioningStepRunner.run` :66 (`@Transactional`) sends SMTP at :217 and :242 (5 s timeouts). | Each in-flight call pins one of **10** Hikari connections (default; there is no `spring.datasource.hikari.*` in `application.yml`) while Tomcat accepts up to 200 concurrent requests. A Meta slowdown therefore turns into an API-wide outage. | Two-phase pattern: commit the intent, then make the external call with no transaction open, then record the result in a short transaction. For OTP, publish to the outbox (already transactional) and have the dispatcher send it. |
| Q-5 | Per-request DB overhead | `SessionRevocationJwtDecoder.decode` :34–41 → `SessionLivenessChecker.isActive` (`@Transactional`, :26–30) runs a **separate transaction on every API call**. `TenantContext.set` (:30–48) runs a 3-table join (`tenants`→`brands`→`tenants`) on **every transaction**. `ClinicClock.zone` (:38–43) is a PK lookup per call, and is called 2–3× in `QueueService.checkIn`. `StaffService.getCallerInfo` is called by 16 call sites. | About 4–6 extra round trips per request; multiplied by polling (Q-2) | Cache: session revocation set in Redis; tenant context (timezone, region, accessible tenant IDs, plan) in Caffeine with event invalidation (§5.3). |
| Q-6 | Reports computed live on OLTP, including all-time aggregates | `ReportsService.overview` :72–89 issues ~14 queries per page load; `retention` :171–176 aggregates the tenant's **entire visit history** (`ReportsRepository.java:230–247`). Measured **974 ms** with spill to disk for a 100k-patient tenant. | Grows linearly with tenant history. Dashboards opened by owners on several clinics multiply it. | Daily rollups and a read replica (§5.6). |
| Q-7 | Unbounded and `SELECT *` reads | `SELECT *` in `PackageRepository` :41–45, `AllergyRepository` :29–56, `DentalRepository` :27–38, `NoteRepository` :34, `PrescriptionRepository` :63–81, `SupportAccessRepository` :38 (all grants, no LIMIT), `PlanRepository` :23 | Low today. Schema drift ships new columns, possibly PHI, without review. | Explicit column lists (the `COLUMNS` constant pattern in `QueueRepository` is the right one). |
| Q-8 | OFFSET pagination | Only the registry (in memory, Q-1). Everything else uses the keyset `Cursor` (`common/Cursor.java`). | Good practice elsewhere | Keep |
| Q-9 | Large IN lists, string-built SQL | IN lists: none (`= ANY(?)` with array binding). String-built SQL: only constant fragments (`priorityGate`, `CONSENT_GATE`, `SCOPE_GATE`), all parameter-bound. | No injection risk found | — |
| Q-10 | Read paths not marked `readOnly` | Only 5 `@Transactional(readOnly = true)` usages across 49 transactional classes | Prevents routing to a replica later | Mark reads `readOnly = true`; add a `LazyConnectionDataSourceProxy` + routing data source when replicas arrive. |
| Q-11 | No DB timeouts | No `statement_timeout`, `lock_timeout` or `idle_in_transaction_session_timeout` anywhere (grep) | A single runaway registry query or a stuck Meta call holds locks and connections indefinitely | Role-level: `ALTER ROLE nabd_app SET statement_timeout='5s', idle_in_transaction_session_timeout='15s', lock_timeout='3s'`; a separate `nabd_reports` role with 60 s; `SET LOCAL lock_timeout` in migrations. |
| Q-12 | Autosave write amplification | `frontend/app/consult/page.tsx:404–411`: autosave every 10 s while dirty | Up to ~60 note writes per 10-minute consult, each audited; the hash-chain lock serialises them per tenant | Debounce to "idle 3 s or 30 s max"; send diffs; don't audit autosaves, only sign/amend. |

---

## 4. Data growth, retention and partitioning

Growth at the target load of **50k visits/day**. Row sizes are measured on the synthetic set including indexes (`queue_entries` ≈ 410 B/row, `invoices` ≈ 440 B/row, bare `audit_log` ≈ 300 B/row without before/after JSON).

| Table | Rows/day | Per year (approx.) | Retention recommendation |
|---|---|---|---|
| `audit_log` (≈15–25 audited writes per visit, before/after JSON ~1 KB) | 0.75–1.25M | **270–450M rows, 300–500 GB** (the dominant table) | Online 13–24 months, then cold archive (Parquet in object storage within the region, hash chain preserved) for the statutory period |
| `sessions` (**one row per refresh rotation**, V1:80–97; access TTL 15 min, `application.yml`) | ~600k (15k active staff × 4/h × 10 h) | ~220M, ~60 GB, **with no purge** (V1:110–111 "ponytail") | Purge 30 days after `expires_at` / `revoked_at` |
| `outbox_events` | 0.2–1M (more once queue events flow) | rows kept forever as `done` (no cleanup code) | Delete `done` after 7 days and `dead` after 90 days, or use daily partitions plus `DROP` |
| `whatsapp_messages` | ~200k (confirmation, reminder, ETA, Rx) | ~73M, ~40 GB | 24 months online |
| `login_attempts` | ~100k | ~36M | 30–90 days (V1:150–152) |
| `queue_entries` | 50–60k | ~20M, ~8 GB | Clinical: keep. Partition by `queue_date` yearly once over ~100M rows. |
| `clinical_notes` (TOAST text) | 50k | ~18M, ~25–40 GB | Clinical: keep |
| `invoices` + `invoice_line_items` + `invoice_payments` | 50k + 150k + 55k | ~90M rows, ~30 GB | Fiscal: keep for at least the tax-law period |
| `prescriptions` + `prescription_items` | 40k + 120k | ~60M rows, ~20 GB | Clinical: keep |
| `vitals` (one row per visit) | 50k | ~18M, ~4 GB | Keep. If device streams arrive, add a separate time-series table. |
| `pharmacy_stock_movements` | ~100k | ~36M | Keep for 7+ years (fiscal); partition yearly |

**Partitioning recommendations, in priority order:**

1. **`audit_log`: `PARTITION BY RANGE (created_at)` monthly**, managed by `pg_partman`.
   - The PK becomes `(id, created_at)`.
   - The immutability triggers must be defined on the parent; they propagate to partitions in PG13+ for row triggers.
   - RLS policies must exist on the parent, and the app role must **not** be granted direct access to partitions.
   - Archival is done by `DETACH PARTITION` → export → drop. It needs no `DELETE`, which the immutability trigger forbids anyway.
   - Keep the hash chain verifiable across partitions with a per-month seal row: the last hash of month N is stored in a `audit_chain_seals(tenant_id, month, last_id, last_hash)` table.
2. **`outbox_events`:** daily or weekly partitions, dropping old ones; or simply a nightly `DELETE ... WHERE status='done' AND updated_at < now()-'7 days'` in 10k batches. Partitioning avoids the vacuum churn of a hot queue table.
3. **`whatsapp_messages`, `sessions`, `login_attempts`:** monthly partitions plus `DROP`.
4. **`queue_entries`, `appointments`:** not yet. Revisit at ~100M rows per region. When you do, partition by `queue_date` / `start_time` yearly, and keep `(tenant_id, …)` as the leading column of every local index.

**Retention policy.** Clinical retention is jurisdiction-specific and multi-year: India (clinical establishments rules and NMC guidance, DPDP Act), UAE (health-data law), KSA (MOH, PDPL). Confirm the exact periods with counsel. Implement a `data_retention_policies(region, data_class, online_months, archive_years)` table and one archival job per region, rather than hard-coding periods. Tenant offboarding needs its own export → archive → purge workflow (§1.6).

---

## 5. Caching and refresh architecture

### 5.1 Current state (verified)

**Backend**
- No `@Cacheable`, `@EnableCaching`, Caffeine, Redis, `ConcurrentHashMap` caches, ETag or `Cache-Control` handling (grep over `backend/src/main`).
- Spring Security's default `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` therefore applies to every API response. That is correct for PHI, but it rules out conditional GETs entirely.
- `pom.xml` contains no cache or messaging dependencies.
- No SSE, WebSocket, `LISTEN` or `NOTIFY`. V4:55–57 explicitly defers "real-time sync (NB-071)".
- The only asynchronous mechanism is the outbox, polled every 2 s (`OutboxDispatcher.java:27`, `application.yml app.outbox.poll-interval-ms: 2000`), and the only event type published is `whatsapp.send` (`WhatsAppMessageService.java:51`). Queue, appointment and config changes emit no events, so there is **no change feed** to drive invalidation or push.

**Frontend**
- Dependencies are only `next`, `react` and `react-dom` (`frontend/package.json`). There is no SWR or TanStack Query.
- 94 of 113 TSX files are client components. Data is fetched with ad-hoc `authedFetch` wrappers duplicated per page (`overview/page.tsx:92–108`, `patients/page.tsx:208`, `packages/page.tsx:106`, …).
- The bearer token is read from `localStorage` (98 references). Next.js server-side fetch caching, RSC data fetching and `revalidate` are therefore not used at all.
- Refresh is plain `setInterval` polling at 15 s on Arrivals (`arrivals/page.tsx:47,339–342`), Nursing (`nursing/page.tsx:116,340–344`) and the Live Queue board (`schedule/LiveQueueBoard.tsx:16,63–66`).
- Polling continues in background tabs (there is no `visibilitychange` handling), and requests are not deduplicated across components.
- The only client-side cache is a per-page `useRef(Map)` of patient names on Arrivals (`:133–135`). `ClinicNav.tsx:180` deliberately does not poll.

### 5.2 Data classification and target strategy

| Class | Examples | Consistency need | Mechanism | Freshness target |
|---|---|---|---|---|
| **A. Real-time operational** | queue position, token now serving, ETA, doctor status (in consult, paused, delayed), arrivals, priority flags, nursing worklist | Monotonic, ≤2 s staleness; read-your-writes for the actor | **Push**: outbox → Redis Streams → SSE. Clients keep a TanStack cache patched by events. Snapshot plus versioned deltas. No polling except a 60 s fallback. | p95 < 2 s end-to-end |
| **B. Near-real-time** | slot availability, today's schedule board, waitlist offers, bed and room status | Eventually consistent for display; **strongly consistent at write** (the DB unique index arbitrates) | Redis L2, TTL 30 s, **plus** event invalidation on `appointment.*`, `leave.*`, `working_hours.*`; clients `staleTime` 15–30 s | ≤30 s worst case, usually <2 s |
| **C. Reference / config** | facility settings (timezone, region, tax, pharmacy mode), departments, workflow definitions and selections, charge catalogue, roles and grants, note and Rx templates, WhatsApp templates, plan entitlements, drug master | Read-mostly; changes must propagate within seconds | **Caffeine L1** (per JVM) + **Redis L2**, versioned keys, event-driven invalidation broadcast over Redis pub/sub; long TTLs as a safety net | ≤5 s after the change event |
| **D. Per-user session / permission** | permission set, scope, verified flags, field grants, delegations, break-glass | Revocation must be fast (≤ seconds); grants may lag ≤ access-token TTL | Permissions stay in the **JWT** (already, `AuthService.mintTokenPair` :537). **Revocation set** in Redis (`sess:revoked:{sid}`, TTL = remaining token life) replaces the per-request DB transaction. `CallerInfo` goes in Caffeine, keyed by `staffId:staffVersion`. A role or grant change bumps `staff_version` and forces a refresh. | revocation <1 s; grants ≤15 min, or immediate via version bump |
| **E. PHI** | patient demographics, allergies, problems, Rx, notes, vitals, invoices | Must be correct; access audited | **Not cached in shared caches by default.** Narrow exceptions are listed in §5.3.4. Safety-critical reads always go to the primary. | n/a |
| **F. Public aggregator** | doctor profiles, clinic listings, specialities, public availability summaries, reviews | Eventual; SEO-friendly | **CDN/edge** with `Cache-Control: public, s-maxage, stale-while-revalidate`, tag-based purge on events; Next.js ISR / Cache Components for pages; OpenSearch for discovery | profiles ≤5 min; availability ≤60 s |

### 5.3 Backend cache design

#### 5.3.1 Change feed: outbox as the single event spine

1. Every state-changing service publishes a domain event **in the same transaction**, using the existing `EventPublisher.publish` (`common/EventPublisher.java:30–35`). Events to add:
   - `queue.entry.changed`, `queue.entry.created`
   - `appointment.booked`, `appointment.cancelled`, `appointment.rescheduled`
   - `doctor.delay.changed`, `doctor.status.changed`
   - `config.changed{domain}`, `role.changed`, `staff.changed`, `catalogue.changed`
   - `allergy.changed`, `rx.signed`, `invoice.paid`

   **Payloads carry IDs and versions, not PHI.** For example: `{tenantId, doctorId, date, entryId, status, version}`.
2. Add `pg_notify('outbox', '')` in the same transaction (or an `AFTER INSERT ... FOR EACH STATEMENT` trigger) to wake the relay immediately. This cuts the 2 s poll latency while keeping polling as a fallback. NOTIFY takes a global lock at commit, so send **one** notify per transaction (statement-level trigger), never one per row.
3. The **relay** (a single active instance per region, leader-elected via `pg_try_advisory_lock` on a dedicated connection) claims events with `claim_outbox_events`. It then `XADD`s them to the Redis Stream `ev:{region}` with `MAXLEN ~ 1M`, marks them `done`, and **does not call external APIs itself**.
4. **Consumer groups** on that stream:
   - `cache-invalidator`: bumps versions, `PUBLISH inval` for L1 caches
   - `eta-engine`
   - `sse-fanout`
   - `notifier` (WhatsApp; each send is its own short transaction, with no transaction open across the HTTP call)
   - `projector` (read models, §5.6)

   Consumer offsets give replay after a crash; handlers must be idempotent (event `id` is the bigint outbox ID).

**Why Redis rather than Postgres LISTEN/NOTIFY alone.** NOTIFY payloads are capped at 8 KB, are not durable, require a dedicated non-pooled connection per listener (incompatible with PgBouncer transaction mode) and serialise committing transactions. Redis is also needed anyway for L2 cache, revocation, rate limiting (today `login_attempts` scans in `AuthService.enforceIpRateLimit`), ETA projections and slot locks. One managed Redis or Valkey per residency region, with TLS and encryption at rest, is the right call. For a pilot of fewer than 50 clinics, a NOTIFY-only path into SSE is acceptable as a stepping stone, provided the event contract stays the same.

#### 5.3.2 Key design

```
nabd:{region}:p:{domain}:v{ver}                       platform-wide (workflow defs, plans, drug master)
nabd:{region}:t:{tenantId}:ver:{domain}               INCR-only version counter per tenant per domain
nabd:{region}:t:{tenantId}:cfg:{domain}:v{ver}        tenant config bundle (timezone, region, tax, pharmacy mode, depts, workflow selection)
nabd:{region}:t:{tenantId}:roles:v{ver}               role → flattened permission set
nabd:{region}:t:{tenantId}:avail:{doctorId}:{date}    slot bitmap + capacity counters (class B)
nabd:{region}:t:{tenantId}:qb:{doctorId}:{date}       queue board hash {entryId → token,status,stage,priority,v} (no names)
nabd:{region}:t:{tenantId}:eta:{doctorId}:{date}      ETA projection hash {entryId → pos,p50,p80,v}
nabd:{region}:sess:revoked:{sid}                      revocation marker, TTL = access-token remaining life
nabd:{region}:pub:doctor:{doctorId}:v{ver}            aggregator profile (public)
```

- **The tenant ID is in every tenant key**, and keys are built by one `CacheKeys` helper. Raw string concatenation is never allowed.
- **Versioned namespaces instead of deletes.** A reader does `GET ver` (itself cached in L1 for 1 s) and then `GET cfg:...:v{ver}`. An invalidation is a single `INCR`, and old versions expire by TTL. This avoids `SCAN`/`DEL` storms and makes invalidation atomic across many keys.
- **L1 Caffeine.** Use `AsyncLoadingCache` with `maximumSize`, `expireAfterWrite` set to the class TTL and `refreshAfterWrite` at half of it, so readers are served stale while one reload runs. Invalidate via a Redis pub/sub `inval` message (`{tenantId, domain, ver}`) that every app instance subscribes to.

#### 5.3.3 Stampede protection

- **Per JVM:** Caffeine `AsyncLoadingCache` coalesces concurrent loads of one key into one future (single-flight).
- **Across JVMs:** a soft-TTL / hard-TTL envelope (`{value, softExpiresAt}`; Redis TTL = hard). After the soft expiry, the first reader takes `SET lock:{key} NX PX 3000` and recomputes; everyone else serves the stale value. Add XFetch probabilistic early refresh for very hot keys such as the queue board.
- **TTL jitter** of ±10% on every write.
- **Negative caching** (e.g. "no active delay") with a short TTL of 30 s.
- **Warm-up:** at a clinic's opening time (from `doctor_working_hours`), the ETA engine pre-builds `qb`/`eta`/`avail` for the day.

#### 5.3.4 PHI caching policy

1. **Default deny.** No PHI in Redis or the CDN unless it is listed here.
2. **Allowed in Redis, with tenant-scoped keys, TTL ≤ 12 h, and the value encrypted with a per-region data key (AES-GCM via the existing `AesGcmCipher`):**
   - the queue board display projection: token, first name plus last initial, stage
   - the ETA projection: entry ID → minutes, which is not PHI by itself
3. **Allowed in Caffeine L1 (process memory only), TTL ≤ 60 s, keyed `(tenantId, patientId)`:** patient header (name, MRN, age, sex) for screens that render it repeatedly. Every read through the API is still audited at the API layer, not the cache layer.
4. **Never cached anywhere; always read from the primary inside the transaction that uses them:**
   - allergies, active medications and pregnancy status used for Rx safety checks (V79 already snapshots allergies at signing; keep that)
   - stock quantity at dispense time
   - slot availability at booking time (the partial unique index V4:120 is the arbiter)
   - invoice totals and payments
   - break-glass status
5. **Never served from a read replica:** the items in rule 4, plus anything read immediately after the same user's write.
6. **CDN:** only class F. Authenticated API responses keep `no-store`. Patient-app ETag responses use `Cache-Control: private, no-cache` with an ETag, never `public`.
7. **Logs and metrics never carry cache values** (the existing PHI-logging discipline in `application.yml` applies).

#### 5.3.5 TTL table

| Data | L1 (Caffeine) | L2 (Redis) | Invalidation event | Client `staleTime` |
|---|---|---|---|---|
| Tenant context (timezone, region, accessible tenant IDs, plan entitlements) | 5 min (refresh at 2.5) | 1 h | `config.changed{tenant,brand}` | n/a |
| Session revocation | — | TTL = token remaining | `session.revoked` | n/a |
| Role → permissions | 5 min | 1 h | `role.changed` | 5 min |
| Departments, workflow selection, transfer graph | 5 min | 1 h | `config.changed{departments}` | 10 min |
| Platform workflow definitions, plans | 1 h | 24 h | deploy or `platform.config.changed` | 1 h |
| Charge catalogue (prices; stock excluded) | 5 min | 1 h | `catalogue.changed` | 5 min |
| Note / Rx templates, favourite sets | 5 min | 1 h | `template.changed` | 5 min |
| Drug master (platform) | 1 h | 24 h | version publish | 24 h (client-side search index) |
| Slot availability (per doctor-day) | — | 30 s | `appointment.*`, `leave.*`, `hours.*` | 15 s |
| Queue board / ETA projection | — | until end of day (written by the ETA engine) | SSE delta | ∞ while SSE is connected; 30 s fallback |
| Patient header (PHI, L1 only) | 60 s | — | `patient.updated` | 60 s |
| Reports rollups (closed days) | 10 min | 24 h | `rollup.updated` | 10 min |
| Reports "today" | — | 60 s | `invoice.*`, `queue.*` (debounced) | 60 s |
| Public profiles (CDN) | — | 1 h | `profile.changed` → tag purge | `s-maxage=300, stale-while-revalidate=86400` |
| Public availability (CDN) | — | 30 s | `appointment.*` → tag purge | `s-maxage=30, stale-while-revalidate=60` |

**Consistency rules:**
- Invalidation runs **after commit**. That is guaranteed because invalidations come from the outbox relay, which only sees committed rows.
- The acting client applies the mutation response to its own cache immediately (read-your-writes) and ignores older event versions. Every projection carries a monotonic `v`, the row's `change_seq`.
- If a race repopulates L2 with a pre-commit value, the version bump makes it unreachable.

### 5.4 How the mobile app, WhatsApp bot and partners consume data

- **ETags and conditional GET.** For resource reads, set a weak ETag `W/"{row_version}"` (single row) or `W/"{max(change_seq)}-{count}"` (collections). `If-None-Match` → `304`. This requires the `updated_at` / `row_version` columns from §1.11. Implement it in the controller, not with `ShallowEtagHeaderFilter`, so the database work is skipped and not just the bytes. For PHI, use `private, no-cache`.
- **Delta sync for offline (patient app, doctor mobile).** `GET /v1/sync?since={cursor}&scopes=appointments,rx,documents` returns the rows changed since the cursor plus tombstones. The cursor is a per-tenant bigint `change_seq`, assigned from a sequence by a `BEFORE INSERT OR UPDATE` trigger on synced tables, with an index `(tenant_id, change_seq)`. On-device storage is encrypted SQLite. Conflicts are resolved server-wins for clinical data and last-writer-wins for patient profile drafts.
- **Live ETA in the patient app.** SSE `GET /v1/me/queue/stream` sends a snapshot plus deltas. If the app is backgrounded, push notifications (FCM/APNs) are sent only when the ETA crosses a threshold (§5.5).
- **WhatsApp bot.** It is stateless and runs behind the webhook. Reads (availability, "where am I in the queue") come from Redis projections. Writes (booking) go to Postgres, and the bot confirms **only after commit**, because the unique index decides. Inbound handling is idempotent on Meta's message ID.
- **Aggregator/partner API.** It serves only class F data from the CDN and OpenSearch. It never hits OLTP synchronously, except for the final booking write.

### 5.5 Queue ETA computation design

**Problems with today's implementation** (`QueueService.waitEstimate` :217–225, `QueueRepository.averageRecentVisitMinutes` :137–151):

- It measures *check-in → invoice*, which includes time spent waiting, so it overestimates and the overestimate compounds with queue length.
- It multiplies that by the count of **all** non-terminal entries, including the patient currently in consult.
- It ignores priority, appointment slots, doctor delay, doctor start time and in-progress elapsed time.
- It costs 2.25 s per call and is called per doctor on every Arrivals poll.

**Data model (add):**

```sql
queue_entry_events (id bigint identity, tenant_id, queue_entry_id, doctor_id, from_status, to_status,
                    stage, occurred_at timestamptz, actor_id)              -- written in QueueService.updateStatus/transfer/checkIn
doctor_sessions    (tenant_id, doctor_id, date, started_at, paused_at[], ended_at)   -- "doctor is in / paused / out"
service_time_stats (tenant_id, doctor_id, visit_type, hour_bucket, n, median_s, p80_s, ewma_s, ewma_var, updated_at)
```

Here `visit_type` ∈ {new, follow_up, procedure, review}, derived from `appointments.is_follow_up` (V34:45) and `class`/department.

**Estimation:**

- **Service-time distribution** per (doctor, visit_type), modelled as log-normal (consult times are right-skewed). Seed values: the doctor's slot length (`doctor_working_hours.slot_minutes`), then the clinic-wide median, then the specialty default. Update **online** at every `in_consult → *` transition with an EWMA (α≈0.1) of the log duration plus its variance, and recompute robust medians and p80 nightly over a 30-day window. Doctors with fewer than 20 observations shrink toward the clinic prior (empirical Bayes).
- **Remaining time of the patient in consult.** Use the conditional expectation E[D − t | D > t] under the fitted distribution. That way a long-running consult pushes the ETA out progressively instead of the ETA sitting at zero.
- **ETA for the patient at position k:**

  ```
  ETA_k = max(now, doctor_available_at)
        + E[remaining current]
        + Σ_{i<k} E[D_i]
        + active_delay
        + Σ pre-stage times (vitals/billing per workflow)
  ```

  The ordering follows the actual call policy: priority first, then booked slot time for appointments within ±10 min of their slot, then walk-in token order. The P80 comes from summing variances. Show the patient a **range** ("10:40–10:55"), never a single minute.
- **Recompute triggers.** Any `queue.entry.*` event for that doctor and day, a check-in, a priority flag, a delay announce or clear, doctor start, pause or end, plus a **60 s tick** while a consult is running (elapsed time changes the conditional expectation). Recompute is O(queue length per doctor), usually under 50, so it is cheap and done in memory by the ETA engine.
- **Storage.** The Redis hash `t:{tid}:eta:{doctor}:{date}` → `{entryId: {pos, p50, p80, v}}` plus a sorted set for position. The engine `PUBLISH`es deltas to the SSE channel `t:{tid}:q:{doctor}:{date}`. Staff screens get the full board; the patient app gets only its own entry.
- **"Leave home now" WhatsApp.** Store each patient's travel buffer (asked at booking; default 30 min, editable from the bot). Send a notification when:
  - *Leave-now:* `p50_ETA − travel_buffer − 5 min ≤ now`, sent once.
  - *ETA shift:* |ETA_new − ETA_last_notified| ≥ max(10 min, 20% of the remaining wait), with hysteresis. It must hold for two consecutive recomputes (≥60 s apart) to avoid flapping. At most one shift message per 20 min per patient, at most 3 per visit, and never after the patient has arrived.
  - *Doctor delay announced:* immediate broadcast to everyone booked for that doctor's session. V33:65–68 notes the prayer-window-bypass requirement; keep it as a template flag.

  These must be **utility-category templates**. Meta's per-number throughput and tier limits apply, so the notifier rate-limits per `phone_number_id` with a Redis token bucket and records every send in `whatsapp_messages` (consent gate already exists in `WhatsAppSendHandler.java:51–53`).
- **Accuracy KPI.** Log predicted versus actual call time per entry and track MAE and the P80 hit rate per clinic. Alert when coverage drops below 70%.

### 5.6 Read models and CQRS for reports and dashboards

1. **Now (no new infrastructure):**
   - `patient_registry_summary(tenant_id, patient_id, last_visit_date, next_appt_at, next_doctor_id, balance_due, has_active_condition, followup_due, top_allergy, pkg_used, pkg_total, duplicate_flag, updated_at)`, updated by the projector from events. Index it on `(tenant_id, name_search)` and `(tenant_id, flag…)` for SQL-side filters with keyset paging. This replaces Q-1.
   - `tenant_daily_metrics(tenant_id, day, visits, completed, no_shows, billed, collected, tax, by_method jsonb, packages_sold, …)` and `doctor_daily_metrics(...)`, upserted idempotently by the projector. Closed days are immutable after day-close (`day_closes`). "Today" is the rollup plus a small live delta query bounded to today.
   - `ReportsService.retention` reads rollups instead of full history.
2. **At ~100–200 clinics per region:** a **read replica** for reports, CSV exports, the registry and the aggregator read path. Route by `@Transactional(readOnly = true)` plus a routing data source. Expose replica lag as a metric; if lag exceeds 5 s, report endpoints show "as of HH:MM".
3. **At brand/chain analytics or 500+ clinics:** CDC (logical replication / Debezium) into **ClickHouse per region**, with PHI pseudonymised: patient → salted hash, free text dropped. Use it for cohort, retention, revenue and population-health analytics and for platform fleet metrics. Materialized views in Postgres are fine for **platform-level** nightly aggregates (`REFRESH MATERIALIZED VIEW CONCURRENTLY`), but not for per-tenant live dashboards: a refresh rebuilds everything.

### 5.7 Search

- **Patient search:** Postgres, as described in §2.4. It stays under RLS and in-region, and reads its own writes; that is the right place for PHI.
- **Drug search:**
  - A platform `drug_master` per region (Indian brand/generic list; SFDA registry for KSA; MOHAP/DOH lists for UAE), versioned, with a trigram GIN index on a normalised name.
  - A per-tenant `drug_usage(tenant_id, drug_key, use_count, last_used_at)` upserted on Rx sign, which provides personalised ranking.
  - Caffeine and Redis cache of the top-N drugs per doctor. Because the master is read-only and non-PHI, ship it to the client as a versioned compressed JSON file (≈2–5 MB), cached by the CDN with immutable versioned URLs, so typeahead is local and instant.
  - This replaces `PrescriptionRepository.searchDrugs` :121–137.
- **Aggregator discovery** (doctor and clinic by speciality, location, language, availability): **OpenSearch** (one per region), fed by events; documents are public profile data only, with availability summarised to "next available slot" buckets.

### 5.8 Frontend data-fetching strategy

**Choice: TanStack Query v5.** SWR is the simpler alternative and works well for read-only views. TanStack Query is the better fit here because:

- there are many mutations with optimistic updates and rollback (queue actions, vitals, priority);
- it supports structured query keys with prefix invalidation (`['t', tid, 'queue', date]`);
- `setQueryData` makes SSE patching straightforward;
- it has first-class `refetchOnWindowFocus`, `refetchIntervalInBackground: false` and persistence (`persistQueryClient`) for offline-tolerant screens;
- its devtools help.

**Structure:**

- **One `apiClient`** (fetch wrapper: auth, 401 refresh, Problem-JSON errors, `AbortSignal`) replaces the per-page `authedFetch` copies.
- **Query-key factory:**

  ```ts
  qk.queue(tid, date, {doctorId})   // ['t', tid, 'queue', date, doctorId?]
  qk.patient(tid, id)
  qk.cfg(tid, domain)
  ```
- **`staleTime` per class:** see the TTL table. Real-time screens use `staleTime: Infinity` while SSE is connected, falling back to `refetchInterval: 30_000` when it is disconnected. Config uses 5–10 min. Reports use 60 s. Patient header uses 60 s.
- **Real-time via `useQueueStream(tid, date)`.** A single `EventSource` per tab, shared across components via context, and paused when `document.hidden` for more than 5 min. Events carry `{key, op, entry, v}` and are applied with `queryClient.setQueryData` when `v > current.v`; if a version gap is detected, the query is invalidated and refetched. Use `Last-Event-ID` for resume.
- **Optimistic updates for queue actions** (status advance, priority flag, acknowledge). In `onMutate`, cancel in-flight queries, snapshot, apply the new status, and mark the row `pending`. On `onError`, roll back and show the server's Problem detail (illegal transition → 400). On `onSettled`, reconcile with the response. Do **not** apply optimistic updates to check-in token numbers; those are server-assigned under a lock.
- **Background refetch:** `refetchOnWindowFocus: true` and `refetchOnReconnect: true` for class A and B; `false` for reports.
- **Remove the N+1 calls.** Consume the `/queue/board` projection. Patient headers become `useQueries` keyed by patient and deduplicated across screens, with a batch endpoint `GET /patients?ids=` as a fallback.
- **Next.js:**
  - Clinic screens stay client components (bearer token in `localStorage`; moving to `httpOnly` cookies is a security-review topic, and would also enable RSC prefetch).
  - The **public aggregator** should be a separate route group or app using **RSC + ISR**: `fetch(url, { next: { revalidate: 300, tags: ['doctor:'+id] } })` or Next 16 Cache Components (`'use cache'` with `cacheLife`/`cacheTag`), with `revalidateTag` called from an event-driven webhook.
  - Never cache authenticated or PHI routes at the edge.

---

## 6. Capacity estimates (1,000 clinics, 5,000 doctors, 2M patients, 50k visits/day)

**Traffic model:**
- **Peak hours.** OPD peaks run 10:00–13:00 and 17:00–20:00. The peak hour carries ≈15% of daily visits: 7,500 visits/h ≈ 2 check-ins/s.
- **Writes per visit.** ≈40–80 writes (status ×6–8, vitals, note autosaves, Rx, invoice/lines/payments, audit, outbox), giving **≈100–150 write transactions/s** at peak, with audit rows ×1.5.
- **Reads, current polling design.** About 7 req/s per clinic (§3 Q-2), so ≈5,000 req/s at 70% clinics online. Each request costs at least 2 transactions (JWT liveness plus business) and 4–10 statements, i.e. **20–50k statements/s**, most of them wasted.
- **Reads, push design.** About 300–800 req/s, plus ~5k staff SSE connections and ~10–15k patient SSE connections at peak (patients waiting with the app open), handled by an SSE gateway (Spring WebFlux or a small Node/Go service) behind the load balancer. Plan ~20k concurrent connections per region in total for India.

**Data and memory:**
- Year-1 hot data is ≈150 GB excluding audit, ≈0.5–0.7 TB including audit (§4).
- The patients table is ~1.5 GB plus ~1 GB of trigram index.
- The working set (today's queue, active patients, reference data) is under 20 GB, so it fits in RAM.

**DB sizing (per region, managed Postgres 16/17, Multi-AZ):**

| Region (assumed share) | Primary | Replica | Storage |
|---|---|---|---|
| India (~70%) | 16 vCPU / 128 GB (memory-optimised) | 1× same class, for reports, exports, registry, aggregator reads | 2 TB gp3/io2 with autoscaling, PITR 35 days |
| UAE (~15%) | 8 vCPU / 64 GB | 1× 4–8 vCPU | 500 GB |
| KSA (~15%) | 8 vCPU / 64 GB | 1× 4–8 vCPU | 500 GB |

With the push architecture, the India primary should run at under 30% CPU at peak. Without it, polling alone would need roughly twice that tier.

**Connection pooling:**
- **HikariCP per app instance:** `maximumPoolSize` 15–20, `minimumIdle` = max, `connectionTimeout` 3 s (fail fast rather than queueing 30 s), `maxLifetime` 30 min, `leakDetectionThreshold` 10 s. For 4–6 India instances that gives 60–120 client connections.
- **PgBouncer in transaction mode** in front of the DB: server pool ≈ 2–3× the primary's vCPU (32–48), with `max_connections` = 200 on Postgres.
- **Compatibility with the current code:**
  - ✅ `TenantContext` uses `set_config(..., true)` (transaction-local, `TenantContext.java:35–36`), which is safe with transaction pooling.
  - ✅ `pg_advisory_xact_lock` is transaction-scoped (`QueueRepository.java:37`).
  - ✅ No session-level `SET` and no session advisory locks were found.
  - ⚠ pgjdbc server-side prepared statements (`prepareThreshold=5` by default) need **PgBouncer ≥ 1.21 with `max_prepared_statements > 0`**; otherwise set `prepareThreshold=0`.
  - ⚠ `LISTEN` (if used for the outbox wake-up) and the relay's leader lock need a **dedicated direct connection**, not one through PgBouncer.
  - ⚠ Reads that call `TenantContext.set` must be inside `@Transactional`. Outside a transaction, the transaction-local setting dies with the statement. It fails closed (RLS returns nothing), so this is a correctness risk, not a leak.
- **Role-level timeouts** as in Q-11.

**Multi-region / data residency:**
- Run **separate, full stacks** per residency zone: India (Mumbai primary, Hyderabad DR), UAE (in-country), KSA (in-country). Each has its own Postgres, Redis, object storage, OpenSearch, ClickHouse and WhatsApp number pool, and its own KMS keys.
- A **global control plane** holds only non-PHI data: the tenant directory (`slug → region`, already modelled by `tenants.region`, V1:23, immutable via trigger), plans and billing, and operator identity.
- The edge routes by subdomain or slug to the right regional API. Platform-operator consoles federate reads across regions through regional APIs and never replicate PHI centrally.
- The patient mobile app resolves the region from the clinic or booking.
- Cross-border brands with clinics in both India and the GCC are separate tenants in separate regions. Brand dashboards aggregate non-PHI rollups only.
- **Add `'UAE'` to the `tenants.region` CHECK before onboarding UAE.**

---

## 7. Findings table

Severity: **C** = Critical, **H** = High, **M** = Medium, **L** = Low.
Effort: **S** ≤ 2 days, **M** ≤ 2 weeks, **L** > 2 weeks.

| ID | Sev | Area | Finding | Evidence | Recommendation | Effort |
|---|---|---|---|---|---|---|
| DP-01 | C | Indexing | No `patient_id` index on `queue_entries`; patient-scoped visit reads seq-scan the whole cross-tenant table | Measured 2,485 ms; `PatientRepository.java:70–75`, `:122`, `:147–149`; V4:154 has only doctor/date | `CREATE INDEX CONCURRENTLY ... (tenant_id, patient_id, queue_date DESC)` → measured 0.095 ms; same for `vitals` (`VitalsRepository.java:48`) | S |
| DP-02 | C | RLS / indexing | OR-shaped brand-visibility RLS policy on 20 tables makes `tenant_id` non-indexable; queries without an explicit tenant predicate scan all tenants | `pg_policies` (V6, V41); `QueueRepository.java:42` 933 ms inside the advisory lock; `ScheduleRepository.java:135–140`, `:151` | Array-based policy `tenant_id = ANY(uuid[])` (measured 0.99 ms); always add `tenant_id = ?`; enforce with a lint/ArchUnit test | S |
| DP-03 | C | Query | Patient Registry: correlated subqueries ×3,000 rows, O(N²) duplicate check, in-memory filter, search and OFFSET; patients beyond the first 3,000 by name are invisible | `PatientService.java:70–130`; `PatientRepository.java:119–160`; measured 24.9 s per 100 rows | `patient_registry_summary` read model, SQL filters plus keyset paging, nightly duplicate job | M |
| DP-04 | C | Refresh | 15 s polling with HTTP N+1 (per row and per doctor), including a 2.25 s wait-estimate call per doctor; continues in hidden tabs | `arrivals/page.tsx:47,228–275,339`; `nursing/page.tsx:116,292–313,340`; `LiveQueueBoard.tsx:16,63` | `/queue/board` projection endpoint plus SSE push (§5); TanStack Query; pause when hidden | M |
| DP-05 | C | Audit | Hash-chain trigger does a backward PK scan filtered by RLS (5,041 ms for a new or quiet tenant at 3M rows) and holds `FOR UPDATE` on the last row until the business commit, serialising all audited writes per tenant | V14:62–79; `AuditService.java:41–47` joins the caller's transaction | Now: `audit_log (tenant_id, id DESC)` (measured 0.20 ms). Next: insert unchained rows in the transaction and have a single per-region "sealer" chain them asynchronously (or keep a per-tenant `audit_chain_head` row updated at the very end of the transaction); monthly seals | S / M |
| DP-06 | H | Transactions | WhatsApp (Meta) and SMTP calls made inside open DB transactions; Hikari pool at default 10 | `AuthService.java:160–187`, `:335–352`; `OutboxEventProcessor.java:36–44`; `ProvisioningStepRunner.java:66,217,242`; no `hikari` in `application.yml` | Intent → commit → call → record result; route OTP via the outbox; size Hikari explicitly | M |
| DP-07 | H | Eventing | Outbox is the only async path but carries only `whatsapp.send`; single-threaded sequential dispatcher; 2 s poll latency; `done` rows never cleaned | `WhatsAppMessageService.java:51`; `OutboxDispatcher.java:27–37`; V44 | Publish domain events for queue, appointment and config; NOTIFY wake-up; relay to Redis Streams; consumer pool; retention job or partitions | M |
| DP-08 | H | ETA | ETA = mean(invoice − check-in) × entries ahead: includes wait time, ignores priority, slots, delay and elapsed time; 2.25 s per call | `QueueService.java:210–225`; `QueueRepository.java:137–160` | `queue_entry_events`, `service_time_stats`, ETA engine with Redis projection and threshold-based WhatsApp (§5.5) | L |
| DP-09 | H | Ops | No `statement_timeout`, `idle_in_transaction_session_timeout` or `lock_timeout`; default pool; no readOnly routing | grep; 5 of 49 services use `readOnly` | Role-level timeouts, separate reporting role, Hikari config, routing data source | S |
| DP-10 | H | Reports | Owner dashboards computed live on OLTP (~14 queries per overview; all-time retention aggregates spilling to disk at 974 ms); missing `(tenant_id, created_at)` / `(tenant_id, recorded_at)` / `(tenant_id, queue_date)` indexes | `ReportsService.java:72–89,171–176`; `ReportsRepository.java:37–44,52–61,230–247` | Daily rollup tables via the projector; add indexes; replica for reports; ClickHouse later | M |
| DP-11 | H | Growth | No retention or partitioning on `audit_log` (300–500 GB/yr), `sessions` (one row per refresh, ~600k/day, no purge), `outbox_events`, `whatsapp_messages`, `login_attempts` | V1:80–111,150; V14; V44; V46 | `pg_partman` monthly partitions for audit/messages/sessions; purge jobs; regional cold archive | M |
| DP-12 | H | Migrations | Non-concurrent index builds, CHECK swaps without `NOT VALID`, full-table backfills plus `SET NOT NULL` in a single transaction, destructive drops without expand/contract, version gaps with out-of-order disabled | V5:36,82; V24:3; V37:55–76; V39:37–40; V40:100; V43:28–65,94; V83:4; V51:4 | Migration playbook: CONCURRENTLY in separate files, `NOT VALID` → `VALIDATE`, batched backfill jobs, `lock_timeout`, expand → contract, central version allocation | S |
| DP-13 | H | Data correctness | Backfills fired `set_updated_at`, so every pre-V43 visit's `updated_at` became the migration time; "last visit" is derived from `max(updated_at)` | V4:147; V37:72; V43:28–29,64,94; `PatientRepository.java:70–75` | Derive last visit from `queue_date` or stage timestamps; suppress triggers in future backfills; one-off repair from `created_at`/`queue_date` | S |
| DP-14 | H | Per-request cost | Extra DB transaction per API call for session liveness; 3-way join per transaction in `TenantContext.set`; DB timezone lookup per call | `SessionRevocationJwtDecoder.java:34–41`; `SessionLivenessChecker.java:26–30`; `TenantContext.java:30–48`; `ClinicClock.java:38–43` | Redis revocation set; Caffeine tenant-context cache invalidated by `config.changed{brand,tenant}` | S |
| DP-15 | H | Search | Patient search not index-assisted (OR of `ILIKE` on phone/MRN, `%name%`, `similarity()`); trigram GIN lacks tenant and is unused; 490 ms at 100k patients | `PatientRepository.java:165–172`; V3:44–46 | `phone_digits`/`phone_last10` generated columns, `btree_gin (tenant_id, name_search)`, `%`/`<%` operators, `UNION ALL` branches | S |
| DP-16 | H | Search | Drug typeahead scans and groups the tenant's entire `prescription_items` history per keystroke; no drug master | `PrescriptionRepository.java:121–137`; `PrescriptionService.java:52,72,601` | Regional `drug_master` + `drug_usage`; client-side versioned index | M |
| DP-17 | M | Check-in latency | ~11 statements per check-in, including 2 cross-tenant scans, while holding the per-doctor-day advisory lock | `QueueService.java:87–117`; `QueueRepository.java:36–45`; `ScheduleRepository.java:130–145` | Fix DP-02; cache tenant zone and department defaults; move capacity counters to a per-doctor-day row (`SELECT … FOR UPDATE`) | S |
| DP-18 | M | Schema | Single-column FKs allow cross-tenant references (FK checks bypass RLS) | e.g. V4:133–135; V20:15–17 | `UNIQUE (tenant_id, id)` on parents plus composite FKs on the clinical spine | M |
| DP-19 | M | Schema | 39 tables lack `updated_at`, including mutable `invoices`, `prescriptions`, `procedure_orders`, `administration_orders`, `waitlist_entries`; no row version, blocking ETag and delta sync | catalog | Add `updated_at` + `change_seq` (sequence via trigger) with `(tenant_id, change_seq)` indexes on synced tables | M |
| DP-20 | M | Schema | Audit log used as a functional store (Rx signer, dental history) | `PrescriptionPrintRepository.java:27–35`; `DentalRepository.java:85–92` | `prescriptions.signed_by`; `dental_chart_events`; keep audit write-only | S |
| DP-21 | M | Schema / compliance | Global `MRN` and `INV-` sequences across tenants; `invoice_series` (V41) unused | V3:14–19; V20:29–30; V41:75 | Per-tenant/per-GSTIN/per-FY series via `invoice_series` row lock (`UPDATE … RETURNING`) | M |
| DP-22 | M | Schema | No currency column; `numeric(12,2)` cannot hold 3-decimal GCC currencies; `tenants.region` excludes UAE | V1:23; V18:12,29; V20:18–23 | `currency char(3)` on monetary headers; add `'UAE'`; minor units or `numeric(14,3)` for new markets | S |
| DP-23 | M | PK strategy | UUID v4 PKs on append-heavy tables (random B-tree inserts, WAL amplification) | catalog; `QueueRepository.java:53` | UUID v7 for new rows on high-volume tables (`uuidv7()` on PG18 or app-side) | S |
| DP-24 | M | Indexing | Redundant indexes duplicating unique constraints | V3:45; V1:76,104; V17:155 | Drop 4 indexes | S |
| DP-25 | M | Indexing | Missing `appointments (tenant_id, start_time)`, `invoices (tenant_id, status)` partial, `doctor_delays` tenant-scoped active index, cleanup indexes | §2.3 | Add concurrently | S |
| DP-26 | M | Frontend | No data-fetching library; duplicated `authedFetch`; no dedupe; per-page ad-hoc caches; polling in background tabs | `frontend/package.json`; `overview/page.tsx:92–108`; `arrivals/page.tsx:133–135` | TanStack Query v5, shared `apiClient`, SSE hook, `staleTime` per class | M |
| DP-27 | M | Write amplification | Consult note autosave every 10 s while dirty, each audited, behind the chain lock | `consult/page.tsx:404–411` | Debounce (idle 3 s / max 30 s), don't audit drafts | S |
| DP-28 | M | Pharmacy | Stock is an integer on the catalogue; no batch, expiry or FEFO model | V25:6–11 | `stock_batches` + movement per batch; partial index on on-hand > 0 by expiry | L |
| DP-29 | L | Query hygiene | `SELECT *` in several repositories | `PackageRepository.java:41–45`; `AllergyRepository.java:29–56`; `DentalRepository.java:27–38`; `PrescriptionRepository.java:63–81` | Explicit column lists | S |
| DP-30 | L | Query | Small N+1 loops in package expiry, pharmacy line resolution and token mint | `PackageInstanceService.java:224–240`; `CheckoutService.java:283–299`; `AuthService.java:537–575` | Batch with `= ANY(?)` | S |
| DP-31 | L | Docs | Database Architecture PDF covers V1–V5 only (12 of 86 tables) and claims explicit tenant filters in every query, which is not true | PDF p.1–2 | Generate the schema doc and ERD from the catalog in CI | S |
| DP-32 | L | Schema | `tenants.timezone` nullable, default `'UTC'` | V17:9 | `NOT NULL`, default from region (`ClinicClock.defaultZoneFor`) | S |

### Suggested sequencing

- **Week 1 (all S effort):** DP-01, DP-02, DP-05 (index part), DP-09, DP-13, DP-14 (Caffeine part), DP-24, DP-25. Together these remove every measured multi-second query.
- **Weeks 2–4:** DP-06, DP-07 (domain events plus relay), DP-04 (`/queue/board` projection plus SSE with a NOTIFY-only pilot path), DP-26 (TanStack Query), DP-03 (registry read model), DP-15.
- **Quarter:** Redis/Valkey per region, the ETA engine (DP-08), rollups and replica (DP-10), partitioning and retention (DP-11), the migration playbook (DP-12), drug master (DP-16), composite tenant FKs (DP-18), sync columns (DP-19).
