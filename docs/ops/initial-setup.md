# Initial database setup (once per database)

The API refuses to start (`RlsSelfCheck`) if its login is a superuser, has `BYPASSRLS`, owns tables, or if a tenant table lacks
`FORCE ROW LEVEL SECURITY`. So the database needs the OPS-003 role split before the first deploy:

| Role | Used by | Rights |
|---|---|---|
| `nabd_migrator` | the migrate job only | owns every table and function, runs Flyway, `NOBYPASSRLS` |
| `nabd_app` | the API | DML only, owns nothing, `NOBYPASSRLS` |
| `nabd_reports` | read-only reports | `SELECT`, 60 s statements |
| `nabd_definer` | nobody logs in | `BYPASSRLS`, owns the `SECURITY DEFINER` functions |

Never run the API as the provider's default owner login (e.g. Neon's `neondb_owner`).

## Steps (Neon, tested up to step 3 on 2026-10-19; steps 4 and 5 not yet confirmed)

Run steps 1 and 2 in the Neon SQL editor (or `psql`) as the default owner login. Passwords come from the secret manager, never from git.

1. **Create the roles.** `infra/db/bootstrap-roles.sql` (needs `psql` variables):
   ```
   psql -v ON_ERROR_STOP=1 -v migrator_pw="$MIGRATOR_PW" -v app_pw="$APP_PW" -v reports_pw="$REPORTS_PW" \
        -d "$DB_URL" -f infra/db/bootstrap-roles.sql
   ```
   Safe to re-run (it also rotates the passwords).
2. **Grant the owner login the right to hand objects over, then transfer ownership**, only if the tables were created before the
   split (by the old owner login). `infra/db/transfer-ownership.sql` does the grants, the transfer and the cleanup in one file:
   ```
   psql -v ON_ERROR_STOP=1 -d "$DB_URL" -f infra/db/transfer-ownership.sql
   ```
   It is a no-op on a new, empty database. Safe to re-run. Neon's owner is not a superuser, which is why the file first runs
   `GRANT nabd_migrator TO CURRENT_USER` (and `REVOKE`s it at the end) and grants `CREATE` on the `public` and `master` schemas.
3. **Check nothing is left under the old owner** (both queries must return no rows):
   ```sql
   SELECT schemaname, tablename, tableowner FROM pg_tables
    WHERE schemaname IN ('public','master') AND tableowner <> 'nabd_migrator';
   SELECT schemaname, sequencename, sequenceowner FROM pg_sequences
    WHERE schemaname IN ('public','master') AND sequenceowner <> 'nabd_migrator';
   ```
4. **Run the migrate job as `nabd_migrator`.** It applies the pending migrations, including V210 (grants to `nabd_app`, `FORCE ROW LEVEL SECURITY`).
   With the image: `docker build --target migrate -t nabd-migrate .`, then run it with
   `MIGRATE_DB_URL` (JDBC URL, e.g. `jdbc:postgresql://<host>/<db>?sslmode=require`), `MIGRATE_DB_USER=nabd_migrator`, `MIGRATE_DB_PASSWORD`.
   Without Docker, from a built jar (`mvn -f backend/pom.xml package -DskipTests`) with the same three variables set and
   `MIGRATE_LOCATIONS=filesystem:db/migrations`:
   ```
   java -cp backend/target/hms-backend-0.1.0.jar -Dloader.main=com.nabd.hms.common.MigrateMain \
        org.springframework.boot.loader.launch.PropertiesLauncher
   ```
   Run it again on every release, **before** the API rolls out.
5. **Point the API at `nabd_app`** (Render: Environment): `DB_USER=nabd_app`, `DB_PASSWORD=<app_pw>`, `DB_URL` unchanged, plus
   `APP_CRYPTO_KEY` (no default, boot fails without it). Remove every `neondb_owner` credential from the API service.
   PLT-044: also set `APP_CRYPTO_KEYRING=1:<the same base64 as APP_CRYPTO_KEY>` and `APP_CRYPTO_ACTIVE_KID=1` (the prod
   profile refuses to boot without a keyring; keeping APP_CRYPTO_KEY lets pre-PLT-044 values keep decrypting). To migrate old
   values set `APP_CRYPTO_REENCRYPT_ENABLED=true` until the log line `crypto re-encrypt verify` shows `failed=0 legacy=0` for
   every column. Rotation: add `2:<new key>` to the keyring, set `APP_CRYPTO_ACTIVE_KID=2`, run the job again; remove a kid
   (and finally APP_CRYPTO_KEY) only after a clean verify.
   OPS-012/PLT-005: set `OTP_PHONE_HMAC_KEY` (base64 pepper; hashes phone numbers in the OTP send counters; required under prod).
   PHL-013: set `RX_KEYS_FILE` or `RX_KEYS_JSON` (a JWKS with 1+ EC P-256 private keys, each with a `kid`; signs the prescription QR; the first key signs, all verify; required under prod, generate once and keep it: printed QRs stop verifying without it).
   PLT-008: set `VALKEY_URL` (`rediss://...`, TLS required under prod) to turn on the shared API rate limits; unset = no limits, the API still serves (docs/architecture/valkey.md).
   `REAL_DATA_ONBOARDING`: unset means `blocked` under the prod profile, so tenant provisioning returns 409. Set it to `open`
   only after `docs/gates/phase0.md` records a passed gate (pending decision).
   Redeploy. A clean start logs no `RLS self-check failed`.

## After upgrading to Sprint 11 (S2) code

Re-run step 1 (`bootstrap-roles.sql`) once as the owner login: it now also creates `nabd_platform` (operator console, PLT-056;
needs Postgres 16+). Then step 4 (migrate) and step 5 (the new variables above). Owners are forced to enrol MFA on the next
login (PLT-038): tell them before the deploy.

## After upgrading to Sprint 12 (S3) code

Do these in order; each is one line of work.

1. **Java 21 / Spring Boot 4.1.1 (PLT-032).** The image and CI use Java 21. Virtual threads are OFF by default (`APP_VIRTUAL_THREADS=false`);
   turn them on only after the staging k6 run shows check-in p95 within +10 %. Rollback is the previous image (no schema change comes with the upgrade).
2. **Valkey (OPS-020).** Set `VALKEY_URL` (TLS, `rediss://`). Without it the API falls back to the database for session liveness and tenant config
   (slower, still correct). The Terraform change asks for `durability = "sync"`, which forces a **new** replication group: plan it as a replacement.
3. **Relay worker (OPS-016).** Run the relay as its own process with `RELAY_DB_URL` (its own role); one active instance is enough, a second is safe.
   Failed relay rows are re-driven with the audited `RelayRedriveCommand` (not by hand-editing rows).
4. **Migrations.** Run the migrate job (step 4) before the API rolls out. Six unreleased migrations (V1510 to V1630) were edited before release:
   if any database already applied an earlier version of them, Flyway will report a checksum mismatch; repair that database or rebuild it.
5. **Patient phone numbers (ACC-028).** Existing numbers are normalised only after one run with `app.maintenance.phone-renormalise.enabled=true`;
   unreadable ones appear on the "Numbers to fix" screen. Turn the flag off again afterwards.
6. **Scheduled jobs (OPS-013).** Needs the `shedlock` table (created by the migrations). The "purge job failing 3 nights" alarm exists in Terraform but has no pager feed yet.
7. **Front end (FEF-012).** The repository is a pnpm + Turborepo monorepo. The Vercel project's Root Directory must be `frontend/apps/staff-web`,
   and the GitHub team `@nabd/frontend` must exist (CODEOWNERS for `packages/ui` and `packages/tokens`).

## After upgrading to Sprint 13 (S4) code

- Migrations V1830-V2000 (consent notices, DPA gate, WhatsApp inbound/suppressions/template states, template lint rules and catalogue, invoice series, patient search columns, regions). Unreleased V1881 and V1992 were edited before release: a database that applied older copies needs a Flyway repair.
- **Every existing clinic becomes `demo_only` on deploy (ADM-002).** Before deploying, list the pilot clinics with a signed paper DPA and open their gate as described in the note in `V1831__adm_dpa_gate.sql`; until then they cannot register real patients.
- A clinic's region is fixed at provisioning (`regions`, `tenants.region_code`); a database trigger refuses a change (OPS-061).
- New env: `app.internal.comms-token` (blank = notification claim-check endpoint closed), `app.kb.source-*` (drug knowledge base attribution), `app.realtime.*` (live updates; needs Valkey to push), `app.cache.config-*` (config cache; 0 = off). WhatsApp: each clinic needs the opt-out / opt-in confirmation templates approved (`optout_confirm`, `optin_confirm`) or the confirmation is skipped.
- Browser scripts that need their own build: cookie mode (`NEXT_PUBLIC_AUTH_MODE=cookie`, `NABD_CORE_URL`, `CORE_PORT`) for s11-fef009-bff-cookies, s11qa-fef009-fef010 and s13-fef020-live-sse; `NABD_CSP_MODE=enforce` for s11-fef-010-csp and s11qa-fef009-fef010.

## After upgrading to Sprint 14 (S5) code

- Migrations V2110-V2290 (ETA rollups, household and duplicate-patient tables, `nabd_control` role grants, audit-chain seals, patient import, plan catalogue and entitlements, drug master, consent export, tax rules, notice templates, ABDM settings, reporting rollups). `nabd_control` is created NOLOGIN by `infra/db/bootstrap-roles.sql`; make the Control Plane login a member per cell (as for `nabd_platform`). `nabd_app` keeps its master grants for now.
- Two new services with their own databases and Flyway history: `services/comms-gateway` (migrations V1-V5, port 3809, Valkey consumer group `comms`) and `services/patient-access-edge`. Neither is deployed by this repo yet; Core works without them.
- New env, all blank by default: `ABDM_BASE_URL`, `ABDM_CM_ID`, `ABDM_HFR_LOOKUP`, `ABDM_HPR_LOOKUP`, `ABDM_JWKS_URI` (ABDM is off per clinic until the owner enables it; credentials live in the regional secret store). `COMMS_GATEWAY_URL`, `COMMS_GATEWAY_TOKEN`, `COMMS_OTP_CUTOVER` (blank URL = OTPs use the in-process sender; `true` removes that fallback).
- New permission `audit_compliance:values`; give it to the DPO role or the value-level audit views stay hidden.
- Patient import (ADM-012) needs the clinic's DPA gate open (see S4 note) and stores imported allergies as unconfirmed, severity "moderate".
- Tax (FIN-008/009): seeded GST rates are placeholders until a chartered accountant verifies them. Clinics with no tax registration keep taxed behaviour.
- Nightly load tests and the residency/egress checks are GitHub workflows (`nightly-load.yml`, `residency*.yml`) that have not had a first run.
- Five browser scripts need their own builds: see `kanban/CONTEXT.md` (cookie build, `NABD_CSP_MODE=enforce`, `NABD_PSEUDO=1`).

## After upgrading to Sprint 15 (S6) code

- Migrations V2410-V2619 (see `db/migrations/VERSIONS`, including V2604 and V2619 clinic policy keys union) and Comms Gateway V6-V17. Flyway applies them on start; no manual step.
- New env: `APP_TRIAL_HASH_PEPPER` (free-trial sign-up, set a random secret per environment). Other new keys are optional and blank by default.
- Staff pages moved into the `(clinic)` route group; no URL changes.
- Five browser scripts still need their own builds (cookie build, `NABD_CSP_MODE=enforce`, `NABD_PSEUDO=1`); the patient-links script needs a patient-web build.

## After upgrading to Sprint 16 (S7) code

- Migrations V2710-V2909 in Core (see `db/migrations/VERSIONS`), Patient Access Edge V6-V17 and Comms Gateway V18-V23. Flyway applies them on start; no manual step.
- New env: `PATIENT_WEB_BASE_URL` (where a family member opens the consent link; default `http://localhost:3810`). The Edge emergency phrase list is empty until the clinical lead signs a list; do not fill it in without that sign-off.
- Clinic day now starts at the tenant's day-start offset (default 04:00) in queue, no-show and checkout code instead of local midnight.
- Sprint 7 left unindexed foreign keys on `patients.guardian_id`, `patients.merged_into_id` and `queue_entries.patient_id`; a bulk delete or merge of patients scans the table once per row (see `kanban/DECISIONS_PENDING.md`).
- `s16-fef-039-consult-i18n` (consult in the pseudo-locale) needs a build started with `NABD_PSEUDO=1`.
- Clinical glossary changes (`packages/i18n/glossary`) need review by the `@nabd/clinical-owners` GitHub team; create that team and turn on code-owner reviews in the repository settings.

## Errors seen and what they mean

| Error | Cause | Fix |
|---|---|---|
| `RLS self-check failed ... superuser or has BYPASSRLS ... owns tables` | API runs as the default owner login | steps 1 to 5 |
| `must be able to SET ROLE "nabd_migrator"` | owner login is not a superuser | `GRANT nabd_migrator TO CURRENT_USER;` (in the transfer file) |
| `permission denied for schema public` / `master` | new owner lacks `CREATE` on the schema | `GRANT USAGE, CREATE ON SCHEMA <s> TO nabd_migrator, nabd_definer;` (in the transfer file) |
| `cannot change owner of sequence "..._id_seq"` | identity/serial sequences follow their table | already skipped by the transfer file |

## Not covered here

Rotating the passwords later (re-run step 1 with new values, then update the secrets), `nabd_reports` wiring, and the
backups/restore drill. See `docs/ops/ci.md` for the pipeline and `docs/ops/staging-in.md` for the staging cluster.
