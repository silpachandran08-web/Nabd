# 02 — Security, Tenancy & Privacy Review (Nabd HMS)

| | |
|---|---|
| Reviewer role | Principal Security Engineer (independent review) |
| Date | 2026-10-17 |
| Scope | `backend/` (Spring Boot 3.3.4, JdbcTemplate, Postgres), `db/migrations/` (V1–V85), `frontend/` (Next.js 16.3.1 / React 19.2.8), `api/openapi.yaml`, "Security Controls" sheet of `Nabd HMS - Implementation Task Plan v2.xlsx` |
| Method | Read-only static review. I read the code and wrote local analysis scripts (RLS coverage, endpoint/authorization inventory, tenant-predicate heuristics, secret scan). I did **not** run Maven, the test suite, or a live database, and I did not look at the deployed environment. A claim that depends on deployment is labelled **[deployment-dependent]**. A claim reasoned from documented Postgres/Spring behaviour but not reproduced is labelled **[reasoned, not reproduced]**. |
| Line references | `path:line` refers to the file as it is in the working tree on the review date. |

---

## 1. Executive summary

Nabd's isolation design is better than most early multi-tenant health products. Nearly every query carries an explicit `tenant_id = ?` predicate, and 68 tables also have a Postgres RLS policy. The tenant GUC is transaction-local and fails closed. The audit log is append-only and hash-chained. Refresh tokens rotate, with reuse detection. Argon2id, AES-GCM and HMAC-verified webhooks are used correctly.

The **authentication and privilege layer around that design has several exploitable holes**. The system also has **no consent, cross-hospital sharing, residency or key-management architecture** yet, and the product requirements and the five target jurisdictions all need one.

The highest-risk items:

1. **Token-type confusion (Critical).** Every JWT the system mints shares one signing key and issuer, and the resource server accepts any of them as a bearer credential. These include the `mfa_pending` challenge, `mfa_setup_required`, the owner `pending_workspace` token and operator tokens. With only a victim's clinic code, email and PIN, an attacker can use the MFA challenge token to:
   - call `POST /v1/auth/break-glass/activate` and receive a full access/refresh pair carrying **Owner** permissions, or
   - call `POST /v1/auth/mfa/enroll` to overwrite the victim's TOTP secret.

   Either way MFA is bypassed completely.
2. **Break-glass is self-service Owner elevation (Critical).** Any authenticated staff member, with no permission check, can obtain the clinic Owner's full grant set for 30 minutes. They can then turn that into 30 days of persistent Owner rights through role delegation.
3. **RLS is probably inert in deployed environments (Critical, deployment-dependent).** No table uses `FORCE ROW LEVEL SECURITY`. The Docker image and `scripts/dev-up.sh` run Flyway as the application's own datasource user, which makes that user the table owner, and Postgres skips RLS for owners. Only the test harness migrates as a superuser and connects as a non-owner, so the tests hide the problem.
4. **Privilege escalation through staff role assignment (High).** `PATCH /v1/staff/{id}` and invites accept any `roleId`, the built-in Owner role included, without the "no grant beyond your own" check that the role builder enforces. Role changes are not audited.
5. **Weak brute-force protection on several authentication paths (High).**
   - TOTP verification has no attempt limit.
   - The per-IP limit trusts a client-supplied `X-Forwarded-For` header.
   - PINs are 4–6 digits.
   - Owner logins and platform-operator logins (including `super_admin`) can complete with a PIN only.
   - OTPs and PIN-reset tokens are written to the application log by default.
6. **Clinical PHI is not scoped inside a tenant (High).** The "own patients only" doctor scope, the dual-verification gate and the consent-withdrawal gate all exist only on `/v1/patients/**`. Every `/v1/clinical/**` read returns full history for any patient ID in the tenant. PHI reads are not audited at all.
7. **Consent, residency and identity are missing (High, design gap).** The system has:
   - no consent artefact,
   - no cross-hospital sharing path except an RLS "brand visibility scope" that silently widens visibility to sibling facilities without consent,
   - no global patient or doctor identity,
   - one shared database for India and KSA tenants, with no region for the UAE or Qatar,
   - one global AES key that is not managed by a KMS.

Section 6 proposes a concrete consent architecture: a consent artefact service aligned with ABDM and FHIR, a Cedar-based policy decision point, a custodian-side read gateway, and policy-driven break-glass with post-hoc review.

---

## 2. Strengths (verified)

| # | Strength | Evidence |
|---|---|---|
| S1 | Defense-in-depth tenancy: about 340 explicit `tenant_id = ?` predicates in repositories, on top of RLS. A heuristic scan of tenant-table SQL without a tenant predicate found only FK joins hanging off tenant-filtered roots. | e.g. `clinical/documents/ClinicalDocumentRepository.java:44-114`, `clinical/PrescriptionRepository.java:30-206` |
| S2 | The tenant GUC is set with `set_config(..., true)`, which is transaction-local. Every caller is inside a `@Transactional` method. There is no `REQUIRES_NEW`, `@Async` or parallel stream that could escape it. When the GUC reverts to `''` on a pooled connection, the `::uuid` cast raises, so the failure mode is closed. | `common/TenantContext.java:30-48`; grep found no `REQUIRES_NEW`/`@Async` |
| S3 | `SECURITY DEFINER` escape hatches pin `search_path = public` and `REVOKE ALL ... FROM PUBLIC`. | `V1__staff_auth_core.sql:120-128`, `V85__audit_search_actor_filter.sql:23-45` |
| S4 | Refresh tokens are 256-bit random values stored as SHA-256. Rotation comes with family-wide revocation on reuse. The revocation write survives the thrown exception (`noRollbackFor`). | `auth/AuthService.java:435-461`, `common/OpaqueTokens.java:20` |
| S5 | Server-side session liveness is checked on every staff request, so a revoked session is denied immediately. Suspending a staff member kills all their sessions. | `auth/SessionRevocationJwtDecoder.java:34-42`, `staff/StaffService.java:149-155` |
| S6 | Argon2id at the OWASP minimum (m=19 MiB, t=2, p=1). The lockout backoff does not renew itself under polling. | `config/SecurityConfig.java:37-40`, `auth/AuthService.java:497-520` |
| S7 | AES-256-GCM with a random 96-bit IV for TOTP secrets and national IDs. The app refuses to boot without `APP_CRYPTO_KEY`. National ID is never returned by the API. | `common/AesGcmCipher.java:29-58`, `application.yml:80-84`, `V3__patients.sql:26` |
| S8 | Audit log is append-only by trigger (UPDATE/DELETE/TRUNCATE all raise), hash-chained per tenant, and keeps PHI out by policy (patient updates record field names only). | `V14__audit_log.sql:62-92`, `common/AuditService.java:16-19` |
| S9 | The WhatsApp webhook verifies HMAC-SHA256 over the raw body in constant time and rejects everything when the secret is blank. | `messaging/WhatsAppWebhookController.java:57-63,96-104` |
| S10 | Inbound `X-Request-Id` is accepted only as a valid UUID, which blocks log injection (CWE-117). | `common/RequestCorrelationFilter.java:47-59` |
| S11 | No user input is concatenated into SQL. Dynamic `SET`/`WHERE` builders use fixed literals, and `LIKE` input is escaped. | `patient/PatientRepository.java:223-232`, `V85__...sql:39-40`, `clinical/PrescriptionService.java:614` |
| S12 | Step-up MFA (`X-Step-Up-Token`) is required for destructive actions such as patient merge/unmerge and staff suspension. | `common/StepUpVerifier.java:21-36`, `patient/PatientController.java:100-114` |
| S13 | Clinical integrity: signed prescriptions and issued documents are immutable at the DB layer, and note amendments are append-only. | `V51__...sql`, `V54__clinical_documents.sql:8-27`, `V34__...sql:6-16` |
| S14 | Messaging consent is default-deny: no row means blocked. | `messaging/WhatsAppMessageRepository.java:43-52` |
| S15 | `@PreAuthorize` is used on 212 of 264 endpoints. Platform controllers use a class-level `@PreAuthorize`. The remaining 52 are login/bootstrap flows by design, plus the problem cases covered in Section 4. | inventory script (Appendix A) |
| S16 | Tests use Testcontainers against real Postgres with a separate non-owner `nabd_app` role. No secrets are committed: the tracked `.env.production` contains only a public URL, and the secret-pattern scan was clean. Logback is used rather than log4j-core. | `support/ApiTestBase.java:96-122`, `frontend/.env.production`, `backend/pom.xml:24-34` |

---

## 3. Tenant isolation

### 3.1 How tenant context is established

- **Source of truth is the `tenantId` claim in the signed access JWT.** Controllers read it (`patient/PatientController.java:124-126`) and services pass it to `TenantContext.set()` before any query. No header, subdomain or path parameter is trusted. OpenAPI states the same (`api/openapi.yaml:1864-1872`).
- **Before authentication**, the tenant is resolved from `tenantSlug` in the login body against the unprotected `tenants` table (`auth/AuthService.java:100-104`).
- **Bootstrap lookups** without a tenant, such as refresh tokens and staff invites, go through `SECURITY DEFINER` functions (`V1:120-128`, `V2:12-21`).

### 3.2 RLS coverage (script output, Appendix A)

| Metric | Value |
|---|---|
| Tables created by migrations | 87 (77 `public`, 10 `master`) |
| Tables with `ENABLE ROW LEVEL SECURITY` and a policy | 68 |
| Tables with `FORCE ROW LEVEL SECURITY` | **0** |
| Policies with an explicit `WITH CHECK` | **0** (`USING` doubles as the write check) |
| Restrictive policies, or per-command (`FOR SELECT`/`FOR INSERT`) policies | **0** |
| `public` tables without RLS | `tenants`, `owners`, `brands`, `legal_entities`, `tax_registrations`, `login_attempts` (carries tenant_id, emails, IPs), `tenant_lifecycle_events` (carries tenant_id), `workflow_definitions` (tenant_id nullable), `package_eligible_doctors` (reached through `packages`) |
| `master.*` tables without RLS (by design) | operators, sessions, login_attempts, provisioning_jobs, provisioning_job_steps, support_tickets, support_access_grants, plans, subscriptions, discount_requests |

Every patient, clinical, financial and auth table that carries `tenant_id` NOT NULL has a policy.

### 3.3 Does the app connect as a non-owner role? (Critical, deployment-dependent)

- Postgres does not apply RLS to the table owner unless `FORCE ROW LEVEL SECURITY` is set, and no migration sets it.
- `application.yml:4-11` gives Flyway no separate `spring.flyway.user`, so Flyway migrates as the datasource user (`DB_USER`, default `nabd_app`).
- The production image (`Dockerfile:22-26`) and `scripts/dev-up.sh:12-14,25` (which grants `ALL ON DATABASE` and lets Spring auto-migrate) both run Flyway at startup with the app credentials.
- In those topologies **the app role creates, and therefore owns, every table, so every RLS policy is skipped**.
- Only `support/ApiTestBase.java:96-122` migrates as a superuser and connects as a non-owner (`spring.flyway.enabled=false`). The tests therefore pass on a topology that differs from the one deployed.
- `api/README.md` knows the rule ("RLS is bypassed by table owners/superusers"), but nothing enforces it.

Consequences:

- Any missing `tenant_id` predicate becomes a cross-tenant breach.
- Owning the tables lets the app role run `ALTER TABLE audit_log DISABLE TRIGGER` (see §7).
- The audit hash-chain trigger's "RLS-scoped" `SELECT` becomes global (§7).

**Verify now:**

```sql
SELECT relname, relowner::regrole, relrowsecurity, relforcerowsecurity FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind='r';
```

### 3.4 Bypass paths

| Path | Behaviour | Assessment |
|---|---|---|
| **Brand/owner visibility scope** (V6, V42, `TenantContext.java:35-46`) | `app.accessible_tenant_ids` is populated from `brands.data_visibility_scope` (FACILITY/BRAND/TENANT). The widened clause is on `patients`, `staff`, `sessions`, `roles`, `appointments`, `queue_entries`, `consents`, `patient_merges`, `doctor_working_hours`, `doctor_leave`, `clinical_notes`, and nine setup tables (`V6:92-131`, `V17`, `V19:28-31`). | Wrong for the product requirement. Cross-hospital sharing should need patient and doctor consent; this shares wholesale with no consent, expiry or audit. `TENANT` scope is **unilateral**: brand X can read brand Y's facilities even when Y is FACILITY (`CrossFacilityVisibilityTest.java:76-88`). There is no `WITH CHECK`, so RLS would also allow **writes** into sibling tenants. The scope is inconsistent: clinical notes are widened, while vitals, prescriptions and allergies are not. No API sets the scope today, and app-level `tenant_id = ?` predicates neutralise it for most queries, so it is latent rather than live. |
| **Platform audit console** `search_audit_log()` (`V16`, `V85`) | `SECURITY DEFINER`, reads across tenants, including `before`/`after` JSON. | Legitimate but too