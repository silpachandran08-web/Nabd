# Control Plane extraction (PLT-061)

Status: **phase 1 built, cut-over not done.** Core still serves every `/v1/platform/**` endpoint and still owns the
`master` schema. This document is the plan and the inventory the cut-over works from. Inventories below were taken at
`develop-initial` 85e1a176; the commands that produced them are in section 10, so they can be taken again before the
cut-over (19 stories were being built on Core at the same time).

Sources: ADR-01, ADR-09, MF-61, doc06 section 4.4 and decision D3, PLT-009 (roles), PLT-052 (classification).

## 1. What exists after phase 1

| Piece | Where | State |
|---|---|---|
| Deployable `control-plane` (Spring Boot 4.1.1, Java 21, same libraries as the Edge) | `services/control-plane` | builds and tests on its own workflow (`.github/workflows/control-plane.yml`); not deployed anywhere |
| Its database schema | `services/control-plane/src/main/resources/db/migration` | V1 `tenant_directory`, V2 the `master` tables (generated from Core), V3 grants |
| No-PHI gate | `services/control-plane/classification.yaml` + `NoPhiSchemaScanTest` | every column tagged, none `phi`, fails on an untagged column |
| Tenant directory feed | `TenantFeed` (consumer group `control-plane` on `ev:<cell>:organization`) | built; Core needed no change |
| Copy-and-verify job | `MasterCopy`, `MasterCopyJob` | built and tested against a stand-in source; never run against a real Core |
| Core boot self-check | `backend/.../common/MasterWriteSelfCheck.java`, property `app.master-cutover-check.enabled` | OFF by default; ON it refuses to boot today (correct: Core still writes `master`) |
| Schema drift gate | `backend/.../platform/Plt061ControlPlaneSchemaTest.java` | fails when Core's `master` schema and V2 differ |

Not built, on purpose (section 6 gives the order): moving the platform code and operator sign-in, the `ops.nabd.health`
ingress and allow-list, replacing Core's reads of `master`, the data cut-over, dropping the schema, the outage test.

## 2. Target shape

- **Service** `control-plane`, one deployment in India (doc06 D3; the hosting location and the GCC owner-contact transfer
  rule need counsel's view before the AE cell exists: open, owner = compliance).
- **Database** its own Postgres. Role `control_app` (DML only; append-only on `master.operator_audit_log`); migrations run
  as the owning role in a separate job, as for Core and the Edge. No Core role gets a login on it.
- **Schema** `master.*` keeps Core's table and column names, so the platform code keeps its SQL when it moves.
  `public.tenant_directory` is new. Tenant, staff, owner and brand ids are plain uuids here: the foreign keys to Core
  tables and the tenant RLS policies are not carried (V2 lists each one at its end).
- **No PHI.** The Control Plane stores ids, plan and billing data, operator accounts and operator actions. See section 9
  for the two places where free text typed by clinic staff reaches it today.
- **Ingress** `ops.nabd.health`, IP allow-listed, serving the operator console API. Not built.
- **Core after cut-over** keeps a local `tenants` projection (organization owns it), has no `master` schema and no
  `/v1/platform/**` route.

## 3. Tables to move

23 tables and one sequence (`master.platform_invoice_seq`). "Tenant RLS" = Core forces tenant row level security on it
today, which means clinic-facing code writes it inside a tenant transaction: those are the hard ones (section 4.3).

| Table | Holds | Tenant RLS in Core | Owner module today |
|---|---|---|---|
| `operators` | Nabd staff accounts, PIN hash, MFA secret (encrypted) | no | platform |
| `sessions` | operator sessions (token hash, IP, user agent) | no | platform |
| `login_attempts` | operator sign-in attempts | no | platform |
| `operator_audit_log` | every operator action; immutable (triggers) | no | platform |
| `operator_training_completions` | PLT-054 training records | no | platform |
| `regulatory_watch_entries` | PLT-054 regulatory watch list | no | platform |
| `plans`, `plan_versions`, `plan_entitlements` | price list and what each plan includes | no | platform |
| `subscriptions` | one row per clinic: plan, MRR, renewal, trial | no (exempt) | platform |
| `subscription_changes` | scheduled and applied plan changes | **yes** | platform |
| `platform_invoices` (+ `platform_invoice_seq`) | Nabd's invoices to the clinic | **yes** | platform |
| `discount_requests` | commercial discount approvals | no (exempt) | platform |
| `tenant_feature_overrides`, `kill_switches` | per-clinic and global feature switches | no (exempt) | platform |
| `provisioning_jobs`, `provisioning_job_steps` | the provisioning saga | no | platform |
| `trial_signups` | public trial requests (hashed mobile and IP) | no | platform |
| `activation_steps` | onboarding funnel per clinic | **yes** | platform |
| `white_glove_engagements` | paid onboarding help | **yes** | platform |
| `support_tickets` | tickets raised by clinic staff | no (exempt) | platform |
| `support_access_grants` | time-boxed support access, approved by the clinic | no (exempt) | platform |
| `deletion_certificates` | signed proof of erasure after offboarding | **yes** | organization |

Not in `master` but tied to it: `public.tenant_lifecycle_events.changed_by` references `master.operators(id)`. That foreign
key (and any other from a Core table into `master`; query in section 10) must be dropped before the schema can be.

## 4. Who reads and writes `master.*` today

### 4.1 Java, by table (w = writes, r = reads only)

Paths are under `backend/src/main/java/com/nabd/hms/`. Everything in `platform/**` moves with the service; **every line
outside `platform/` is a Core read or write that needs a replacement before the cut-over** (section 4.4).

| Table | Writers | Readers outside the writers |
|---|---|---|
| `activation_steps` | platform/activation/ActivationRepository | |
| `deletion_certificates` | **organization/offboarding/OffboardingService** | |
| `discount_requests` | platform/billing/BillingRepository | |
| `kill_switches` | platform/entitlements/FeatureSwitchController | platform/entitlements/PlanEntitlements |
| `login_attempts` | platform/PlatformAuthRepository | platform/PlatformAuthService |
| `operator_audit_log` | platform/audit/AuditSearchRepository | platform/audit/AuditSearchService |
| `operator_training_completions` | platform/compliance/OperatorTrainingService | |
| `operators` | platform/PlatformAuthRepository | **common/CryptoReencryptJob**, platform/PlatformAudit, PlatformAuthService, access/SupportAccessRepository, audit/AuditSearchRepository, billing/BillingRepository, compliance/OperatorTrainingService, compliance/RegulatoryWatchService, tenant/TenantLifecycleRepository |
| `plan_entitlements` | platform/plans/PlanRepository | platform/entitlements/PlanEntitlements, subscriptions/OwnerPlanRepository |
| `plan_versions` | platform/plans/PlanRepository | platform/billing/BillingRepository, entitlements/PlanEntitlements, subscriptions/OwnerPlanRepository |
| `plans` | platform/plans/PlanRepository | **auth/internal/guard/OtpCounterRepository**, **organization/setup/SetupRepository**, **shared/ratelimit/PlanRateLimits**, platform/billing/BillingRepository, entitlements/PlanEntitlements, provisioning/ProvisioningRepository, subscriptions/OwnerPlanRepository, subscriptions/WhiteGloveRepository, tenant/TrialClockJob, territory/TerritoryRepository |
| `platform_invoices`, `platform_invoice_seq` | platform/subscriptions/OwnerPlanRepository | |
| `provisioning_jobs`, `provisioning_job_steps` | platform/provisioning/ProvisioningRepository | |
| `regulatory_watch_entries` | platform/compliance/RegulatoryWatchService | |
| `sessions` | platform/PlatformAuthRepository | **auth/ChainDecoders**, **auth/SessionLivenessChecker**, **auth/SessionRevocationJwtDecoder**, **common/SessionRevocation** |
| `subscription_changes` | platform/subscriptions/OwnerPlanRepository | |
| `subscriptions` | platform/billing/BillingRepository, provisioning/ProvisioningRepository, subscriptions/OwnerPlanRepository, tenant/TrialClockJob | **auth/internal/guard/OtpCounterRepository**, **common/TrialSubscriptions**, **organization/dpa/DataGate**, **organization/dpa/DpaRepository**, **organization/setup/SetupRepository**, **shared/ratelimit/PlanRateLimits**, platform/billing/TrialSubscriptionReader, entitlements/PlanEntitlements, plans/PlanRepository, plans/PlanService, subscriptions/OwnerPlanService, subscriptions/WhiteGloveRepository, territory/TerritoryRepository |
| `support_access_grants` | platform/access/SupportAccessRepository | |
| `support_tickets` | platform/ticket/TicketRepository | platform/access/SupportAccessRepository |
| `tenant_feature_overrides` | platform/entitlements/FeatureSwitchController | platform/entitlements/PlanEntitlements |
| `trial_signups` | platform/trial/TrialRepository | |
| `white_glove_engagements` | platform/subscriptions/WhiteGloveRepository | |

### 4.2 SQL functions and triggers in Core's database

| Function | Migration | Rights | Reads | Writes |
|---|---|---|---|---|
| `activation_funnel`, `activation_stuck` | V3120 | definer | activation_steps | |
| `claim_activation_nudges` | V3120 | definer | | activation_steps |
| `claim_due_plan_changes` | V3061 | definer | plans | subscription_changes |
| `deletion_certificate` | V3420 | definer | deletion_certificates | |
| `erase_offboarded_tenant` | V3420 | definer | | support_tickets (erases the raiser's name and email) |
| `tenants_data_gate_guard` (trigger on `tenants`) | V1831 | definer | subscriptions | |
| `white_glove_queue`, `white_glove_tenant_of` | V3121 | definer | white_glove_engagements | |
| `master.operator_audit_log_immutable` (trigger) | V1410 | invoker | | refuses UPDATE, DELETE, TRUNCATE on operator_audit_log |

`search_audit_log` (V16, V1410) is the reverse direction: an operator reading a clinic's `audit_log`. It stays in Core and
becomes a Core internal endpoint the Control Plane calls.

Migrations also seed `master.plans`, `plan_versions` and `plan_entitlements` (V2170, V3121, V3590; e6's PHL-056 adds
lab plans in this sprint). After the cut-over, plan seeds are Control Plane migrations.

### 4.3 Routes

`platform/**` holds 24 controllers. They are not all operator routes:

- **Operator console, moves to `ops.nabd.health`:** `/v1/platform/**` (auth, tenants, lifecycle, region, provisioning-jobs,
  plans, billing, territories, senders, support, support-access, audit-log, activation, white-glove, feature switches).
- **Clinic-facing, stays on the clinic host but writes `master` today:** `/v1/setup/subscription` (OwnerPlanController),
  `/v1/setup/white-glove`, `/v1/owner/branches` (starts a provisioning job), `/v1/support/tickets`,
  `/v1/support-access/requests` (the clinic approves or refuses access), `/v1/public/trials`, plan catalogue and
  `me/entitlements`. After the cut-over each of these is a Core endpoint that calls the Control Plane's internal API (or,
  for reads on the request path, a Core projection). This is the largest part of subtask PLT-061.2 and is not started.

### 4.4 Core reads that must not depend on the Control Plane being up

"Core unaffected by a Control Plane outage" means none of these may become a synchronous call:

| Core reader | Needs | Replacement |
|---|---|---|
| `platform/entitlements/PlanEntitlements`, `shared/ratelimit/PlanRateLimits`, `auth/internal/guard/OtpCounterRepository` | plan, entitlements, limits per clinic, on the request path | Core projection `tenant_entitlements`, fed by `platform.entitlements.changed.v1` (the event exists today) plus a snapshot endpoint for rebuild |
| `organization/dpa/DataGate`, `DpaRepository`, `organization/setup/SetupRepository`, `common/TrialSubscriptions`, trigger `tenants_data_gate_guard` | is the clinic on a trial, which plan | same projection (trial flag and plan code) |
| `auth/SessionLivenessChecker`, `SessionRevocationJwtDecoder`, `ChainDecoders`, `common/SessionRevocation` | is an operator session alive | gone from Core: operator tokens come from the Control Plane issuer (PLT-009, T-111) and are only accepted on `ops.nabd.health` |
| `common/CryptoReencryptJob` | re-encrypts `operators.mfa_secret_enc` | moves with the operators table; the Control Plane gets its own key |
| `organization/offboarding/OffboardingService` | writes `deletion_certificates` | Core calls the Control Plane internal API after erasure; retried from the outbox (erasure never waits for it) |
| tenant status (`tenants.status`) | suspend, offboard | Core keeps `tenants`; the Control Plane sends the command, Core applies it and publishes the status event (as today) |

## 5. Tenant directory

`tenant_directory (tenant_id, brand_id, owner_id, region_code, cell, status, status_version)`: ids and codes only.

- **Feed.** `TenantFeed` is consumer group `control-plane` on `ev:<cell>:organization`, the stream Core's outbox relay
  already fills (events.md sections 7 and 8). It applies `organization.tenant.status_changed.v1` (every lifecycle move;
  the first one is provisioning -> trialing at go-live), `organization.tenant.region_pinned.v1` (region and cell at
  go-live) and `organization.branch.created.v1` (brand and owner of a new branch). No new event type was added:
  `platform.tenant.status_changed.v1` in the story is the reverse direction (Control Plane -> Core) and belongs to the
  cut-over.
- **Timing.** Relay: on commit, at the latest every 2 s. Consumer: polls every 1 s. Budget 1 minute; alert on
  `NabdConsumerLagging` for group `control-plane` (to be added to `infra/observability/event-consumers.rules.yml` when the
  service is deployed).
- **Order and repeats.** A status is applied only if `data.version` is higher than the last one applied for that tenant.
  The other two events are idempotent upserts. A failing entry is retried every 5 s and moved to `ev:<cell>:dlq` after 5
  attempts; entries behind it are not held up.
- **Gaps, known.**
  1. Brand and owner of a first clinic are in no event. `CONTROL_COPY_MODE=directory` fills them from Core (safe to
     repeat); after the cut-over the provisioning saga runs here and writes them itself.
  2. A stream keeps about 7 days. Clinics older than the consumer group need the same directory sync once.
  3. One Valkey per cell: this instance reads the cells listed in `CONTROL_REGIONS` from one `VALKEY_URL`. A second cell
     needs either one feed instance per cell or a push from the cell to the Control Plane. Decide with the AE cell.
  4. A region without Valkey (local dev) has no stream, so no feed.
- **Outage.** Core never calls the Control Plane. While it is down the entries wait in the stream (up to its retention)
  and are applied when it returns.

## 6. Cut-over sequence

Each step is releasable on its own and has its own way back (section 7). Steps 1 to 4 change nothing a clinic can see.

1. **Deploy the empty Control Plane** (database, `control_app`, migrations V1-V3, service with the feed) next to the IN
   cell. Run `directory` mode once. Gate: `/healthz` up; `verify` reports `public.tenant_directory` OK for a week.
2. **Operator sign-in on the Control Plane issuer** (PLT-009 second half, T-111): `PlatformAuthService` and its key set
   run in the Control Plane; Core accepts those tokens on `/v1/platform/**` during the transition.
3. **Replace Core's reads** (section 4.4): build the Core projections and switch each reader, one release per group,
   behind a flag, comparing the projection with `master` in the dual-read window.
4. **Move the platform code** (PLT-061.2): controllers, services and repositories of `platform/**` into
   `services/control-plane` on its own DataSource; the clinic-facing routes of 4.3 become Core endpoints calling its
   internal API. The console (staff-web `platform` feature) points at `ops.nabd.health`. Writers are still Core until
   step 6: the moved code is deployed dark.
5. **Rehearsal on staging** with a copy of production `master`: steps 6 and 7 end to end, timed. Gate: every object OK,
   console smoke test on the Control Plane, time within the maintenance window.
6. **Cut-over** (maintenance window, operator console read-only; clinics are not affected):
   1. stop the writers: console read-only, pause `TrialClockJob`, the plan-change job, activation nudges and provisioning;
   2. `CONTROL_COPY_MODE=copy` (section 8). It commits only if every table and sequence matches;
   3. switch the console and the internal API to the Control Plane (ingress and flag);
   4. Core: `REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA master FROM nabd_app` and from every role it
      can become, and revoke EXECUTE on the definer functions of 4.2 that write `master`; a Core migration
      (number from the then-current block) does this;
   5. deploy Core with `app.master-cutover-check.enabled=true`: it refuses to start if any write path is left.
7. **Dual-read window, 2 weeks.** Core's `master` is frozen and readable. Run `verify` daily: it must stay OK for every
   table that nobody writes on either side; tables the Control Plane now writes will differ and are compared by the
   reconciliation report instead (to be written with step 4).
8. **Drop.** Core migration `V1xx__drop_master_schema.sql`: drop the foreign keys from Core tables into `master`, the
   definer functions of 4.2, then `DROP SCHEMA master`. Revoke `SELECT`. Remove `/v1/platform/**` from Core, the
   `platform` package, `rls-exemptions.txt` lines and `nabd_control` grants. The self-check stays on.

## 7. Rollback

| After step | Way back |
|---|---|
| 1 | delete the deployment and its database; nothing depends on it |
| 2 | Core's own operator issuer is still there: switch the console's sign-in back |
| 3 | per reader: flag back to the `master` read |
| 4 | the moved code is dark: stop routing to it |
| 6, before 6.4 | switch the console back to Core, resume the jobs; the Control Plane copy is discarded (recreate its database before the next attempt: the copy refuses a non-empty target) |
| 6, after 6.4 | re-grant `nabd_app` (reverse migration), turn the self-check off, switch back. Anything written on the Control Plane since 6.3 must be copied back by hand from the reconciliation report: keep the window between 6.3 and the go/no-go short and the console read-only until the go |
| 7 | as above, while Core's `master` still exists |
| 8 | none without a restore. Take a logical dump of `master` first and keep it for the retention period |

## 8. Copy-and-verify job

A one-off run of the Control Plane image; there is no HTTP endpoint.

```
CONTROL_COPY_MODE=copy|verify|directory \
CONTROL_COPY_SOURCE_URL=jdbc:postgresql://<core-db>/<db> CONTROL_COPY_SOURCE_USER=... CONTROL_COPY_SOURCE_PASSWORD=... \
CONTROL_DB_URL=... CONTROL_DB_USER=<owning role> CONTROL_DB_PASSWORD=... \
java -jar app.jar --spring.main.web-application-type=none
```

- **Source role.** Read-only on Core: `USAGE` on `master`, `SELECT` on its tables and sequences and on `public.tenants`,
  `brands`, `regions`, and **not subject to RLS** on them (five `master` tables force tenant RLS; a normal role would see
  no rows and every count would "match" at zero). The job checks `row_security_active` per table and refuses. Create a
  dedicated login role for the window (member of `nabd_definer`, or `BYPASSRLS` with the SELECT grants) and drop it after.
- **copy.** Refuses unless: both sides have the same `master` tables with the same columns and types, every target table
  is empty, every source sequence exists and is readable. Reads the source in one REPEATABLE READ snapshot. Writes every
  table with `COPY` in one transaction with the foreign keys deferred, sets every sequence to the source's last value,
  fills `tenant_directory`, then verifies and commits only when everything matches.
- **verify.** For every table: row count and a checksum (the sum, in two 64-bit halves, of the md5 of each row's text
  form, all columns in name order, with time zone, date and float output pinned on both sides). For every sequence: the
  last value. For the directory: the same checksum over Core's tenants (status other than `provisioning`) joined to brand
  and region.
- **Output.** One line per object: `OK` or `MISMATCH`, counts and checksums. Exit code 0 only when all match. Row data
  is never logged; a database error is logged as its class and SQLSTATE.
- **Limits.** The checksum compares text output, so both databases must be the same Postgres major version (16). A row
  that differs only in a value whose text form is equal is not detected (none known for the column types in use).

## 9. No PHI: what to settle before the cut-over

`NoPhiSchemaScanTest` proves that every column is classified and none is `phi` or named like clinical data. Two columns
are free text typed by clinic staff, so the schema cannot prove what they hold:

- `master.support_tickets.subject` and `.description`. Staff can type a patient's name or condition into a ticket.
  Tagged `pii` with a note. Needed before the cut-over: a rule shown on the ticket form, and a decision whether
  a redaction pass runs before a ticket leaves the cell. Owner: DPO.
- `master.support_access_grants.reason` and `operator_audit_log` detail fields: operator-typed. Same rule for operators.

Owner names and contacts in `provisioning_jobs` and `trial_signups` are PII (business contacts), not PHI; their transfer
out of a Gulf cell is the doc06 D3 question.

## 10. Keeping this true

- **Schema drift.** `Plt061ControlPlaneSchemaTest` (backend) regenerates V2 from Core's catalog and compares. When it
  fails because someone changed `master.*`:
  `mvn -f backend/pom.xml test -Dtest=Plt061ControlPlaneSchemaTest -Dcontrolplane.write=true` (alone, through the test
  lock), commit V2, and tag the new columns in `services/control-plane/classification.yaml`. This in-place regeneration
  is right only until a Control Plane database exists; from then on the change is a new Control Plane migration.
- **Java inventory (4.1).** `grep -rnE "master\.[a-z_]+" backend/src/main/java --include='*.java'`, a writer being a file
  with `INSERT INTO|UPDATE|DELETE FROM master.<table>`.
- **Functions (4.2).** `SELECT p.oid::regprocedure, p.prosecdef FROM pg_proc p WHERE p.prosrc ~ 'master\.'`.
- **Foreign keys from Core tables into master.** `SELECT conrelid::regclass, conname FROM pg_constraint WHERE contype = 'f'
  AND confrelid IN (SELECT oid FROM pg_class WHERE relnamespace = 'master'::regnamespace) AND conrelid NOT IN (SELECT oid
  FROM pg_class WHERE relnamespace = 'master'::regnamespace)`.
- **Core's remaining write paths.** Start Core with `app.master-cutover-check.enabled=true` against a copy of the
  database: the error lists every table, role and function.

## 11. Chaos test plan (Control Plane down)

Not runnable until step 4 (today Core does not call the Control Plane, so stopping it changes nothing; phase 1 pins that
with `IndependentDeployTest.coreHasNoConfiguredWayToCallTheControlPlane`). To run on staging after step 4, and again
after step 6:

1. Baseline: the e2e smoke journeys (check-in, consult with Rx, checkout and payment, pharmacy dispense) green; note p95.
2. Stop the Control Plane (scale to 0) and block its database from the cell's network. Keep it down for 30 minutes.
3. During the outage, assert:
   - the same journeys stay green, p95 within 10% of the baseline;
   - staff sign-in, token refresh and step-up work (no operator-session lookup on the clinic path);
   - entitlement checks answer from the Core projection (a clinic keeps its modules; a new plan change is simply not
     seen yet);
   - a suspended clinic stays suspended (status is Core's own column);
   - owner plan screens and support tickets show a clear "try again later" and write nothing half-done;
   - no Core pod restarts, no error-budget burn alert on the clinic SLOs.
4. Start it again. Assert: the feed catches up (`consumer_lag_seconds{group="control-plane"}` back under 60 s), queued
   internal calls (deletion certificates, ticket creation retries) drain, `verify` reports the directory OK.
5. Repeat with the Control Plane up but slow (2 s added latency) and with Valkey down in the cell.

Pass = every assertion in 3 and 4. Record the run in `docs/gates` with the date and the commit.
