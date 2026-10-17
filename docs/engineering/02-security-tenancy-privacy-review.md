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

The highest-risk items (finding IDs reference the Section 10 table):

1. **Token-type confusion (Critical — F-01).** Every JWT the system mints shares one signing key and issuer, and the resource server accepts any of them as a bearer credential. These include the `mfa_pending` challenge, `mfa_setup_required`, the owner `pending_workspace` token and operator tokens. With only a victim's clinic code, email and PIN, a party holding the first-factor-only challenge token can:
   - call `POST /v1/auth/break-glass/activate` and receive a full access/refresh pair carrying **Owner** permissions, or
   - call `POST /v1/auth/mfa/enroll` to overwrite the victim's TOTP secret.

   Either way MFA is bypassed completely.
2. **Break-glass is self-service Owner elevation (Critical — F-02).** Any authenticated staff member, with no permission check, can obtain the clinic Owner's full grant set for 30 minutes. They can then turn that into 30 days of persistent Owner rights through role delegation.
3. **RLS is probably inert in deployed environments (Critical, deployment-dependent — F-03).** No table uses `FORCE ROW LEVEL SECURITY`. The Docker image and `scripts/dev-up.sh` run Flyway as the application's own datasource user, which makes that user the table owner, and Postgres skips RLS for owners. Only the test harness migrates as a superuser and connects as a non-owner, so the tests hide the problem.
4. **Privilege escalation through staff role assignment (High — F-08).** `PATCH /v1/staff/{id}` and invites accept any `roleId`, the built-in Owner role included, without the "no grant beyond your own" check that the role builder enforces. Role changes are not audited.
5. **Weak brute-force protection and credential handling on several authentication paths (High — F-05, F-07, F-21, F-10, F-12).**
   - TOTP verification has no attempt limit (F-05).
   - The per-IP limit trusts a client-supplied `X-Forwarded-For` header (F-07).
   - PINs are 4–6 digits (F-21).
   - Owner logins and platform-operator logins (including `super_admin`) can complete with a PIN only (F-10).
   - OTPs and PIN-reset tokens are written to the application log by default (F-12).
6. **Clinical PHI is not scoped inside a tenant (High — F-06, F-09).** The "own patients only" doctor scope, the dual-verification gate and the consent-withdrawal gate all exist only on `/v1/patients/**`. Every `/v1/clinical/**` read returns full history for any patient ID in the tenant. PHI reads are not audited at all.
7. **Consent, residency and key management are missing (High, design gaps — F-04, F-11, F-13, F-29).** The system has:
   - no affirmative consent artefact (only an opt-out withdrawal marker),
   - no cross-hospital sharing path except an RLS "brand visibility scope" that silently widens visibility to sibling facilities without consent, expiry or audit (F-04),
   - one shared database for India and KSA tenants, with no region for the UAE or Qatar (F-13),
   - one global AES key that is not managed by a KMS (F-11),
   - no data-subject-rights, retention or backup machinery (F-29).

Section 6 records the current consent and data-sharing state only; the target architecture is being written separately in `docs/engineering/07-consent-and-data-sharing-architecture.md`.

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
| **Platform audit console** `search_audit_log()` (`V16`, `V85`) | `SECURITY DEFINER`, reads across tenants, including `before`/`after` JSON. | Legitimate but too broad. The function is executable by the application role itself (`V85:45-70`), so the only gate is the class-level `@PreAuthorize("hasAuthority('audit_compliance:view')")` (`platform/audit/AuditSearchController.java:26-27`). `before`/`after` may hold clinical values by policy (`common/AuditService.java:16-19`), and `audit_compliance:view` is held by `sre` as well as `compliance_dpo` (`platform/PlatformPermissions.java:43`). The page size is caller-controlled with no upper bound (`AuditSearchController.java:46`, `AuditSearchService.java:51-54`), so the interactive view sidesteps the 10,000-row cap and the audit record that the CSV export has (`AuditSearchService.java:34,76-118`). Viewing is not audited. See F-14. |
| **Other `SECURITY DEFINER` functions**: `find_session_by_token_hash` (`V1:120`), `find_staff_by_invite_token_hash` (`V2:12-21`), `staff_counts_by_tenant` (`V18:55-63`), `claim_outbox_events` (`V44:48`), `apply_whatsapp_status` (`V46:38`), `apply_whatsapp_template_update` (`V47:42`) | Each bypasses RLS for one narrow purpose. All pin `search_path` and revoke from `PUBLIC`. | Acceptable as designed. Each is keyed by a high-entropy token hash, returns aggregates only, or is an outbox/webhook write. They only form a privilege boundary when owned by a role other than the app role. In the §3.3 topology that distinction disappears. |
| **`master` schema** (operators, sessions, tickets, support grants, subscriptions, discounts) | No RLS by design. Platform controllers gate by authority. | Acceptable for platform metadata. However, `master.support_tickets` holds free text written by clinic staff, which can contain PHI, and every `support_tickets:view` operator can read it across tenants (`TicketAdminController.java:19-20`). See F-37. |
| **Owner workspace select** (`owner/OwnerService.java:129-146`) | Sets tenant context from the request's `clinicId` after `ownsClinic()` verifies brand ownership (`OwnerRepository.java:137-145`). | The ownership check is correct. The tenant lifecycle status is not checked, so an owner can still enter a suspended or offboarded clinic (F-16). |
| **Support access view** (`platform/access/SupportAccessService.java:82-104`) | An operator-held grant sets the tenant context and reads a redacted patient. | The operator self-grants it, with no tenant approval (F-20). It returns name, phone and DOB, which are direct identifiers even though clinical fields are withheld. Each read is audited. |
| **`AuditService.record()` context switch** (`common/AuditService.java:45`) | Calls `TenantContext.set(tenantId)` inside the caller's transaction. | Not a bypass today, because every caller audits the tenant it is already working in. It does, however, silently replace `app.tenant_id` for the rest of the transaction, which is a latent hazard for future callers. It should assert that the tenant is unchanged, or restore the previous value. |

### 3.5 Isolation test coverage and context-leak analysis

**What `CrossTenantIsolationApiTest` covers** (`backend/src/test/java/com/nabd/hms/support/CrossTenantIsolationApiTest.java`):

- patient GET by id and list search (`:25-64`)
- staff list and GET by id (`:66-83`)
- appointment GET by id (`:85-112`)
- note templates (list, PATCH, DELETE) and vaccine records (list, create against a foreign patient), plus a direct RLS probe under the other tenant's context (`:114-160`). The file itself says the last test was "written, NOT executed" (`:114`).

**What it does not cover:**

- all `/v1/clinical/**` reads: notes, vitals, prescriptions, allergies, conditions, timeline, documents and dental chart
- billing and invoices, pharmacy, packages, queue, reports, WhatsApp messages, audit log and sessions
- most write paths that take a foreign id in the body or path
- creating a row in tenant B that references tenant A's ids (F-23)

**What `CrossFacilityVisibilityTest` covers** (`platform/CrossFacilityVisibilityTest.java:34-103`): it asserts only that the brand scope widens visibility on `patients`. It encodes the unilateral `TENANT` behaviour as *expected* (`:76-88`). It does not test writes into sibling tenants, which the missing `WITH CHECK` allows.

**Structural gaps:**

1. There is no schema-level test that asserts:
   - every table with a `tenant_id` column has RLS enabled, forced, and has a policy;
   - the runtime role is neither the table owner nor `BYPASSRLS`.

   This one test would have caught §3.3.
2. The harness migrates as a superuser and runs as a separate role (`support/ApiTestBase.java:93-128`), which is the opposite of the deployed topology.
3. The suite cannot currently run against Docker Engine 29, because of Testcontainers 1.19.8 (F-30).

**Tables holding tenant data without RLS** (from §3.2, assessed):

| Table | Assessment |
|---|---|
| `login_attempts` | Holds emails and IPs across tenants. Read only by auth code, by staff or IP. Add RLS, or move it to `master`, and add a retention purge. |
| `tenant_lifecycle_events` | Includes operator reasons. Platform-only, so this is acceptable. |
| `workflow_definitions` | `tenant_id` is nullable, and tenant snapshots are written by migration only. Add a policy of the form `tenant_id IS NULL OR tenant_id = current tenant`. |
| `package_eligible_doctors` | Has no `tenant_id` and is reached only through `packages` joins (`PackageRepository.java:99-116`). Add `tenant_id` and RLS for defence in depth. |
| `owners`, `brands`, `legal_entities`, `tax_registrations` | Identity and tax data, including owner PIN hashes, visible at the DB layer to every tenant session. Restrict them with column grants or move them to `master`. |

**Connection-pool / `SET LOCAL` analysis** [reasoned, not reproduced]:

- `TenantContext.set` uses `set_config(..., true)` for both GUCs (`common/TenantContext.java:34-47`). Both are transaction-scoped and revert on commit or rollback, so a pooled HikariCP connection cannot carry one request's tenant into the next.
- I scanned every `tenantContext.set(` call site. All of them run inside a `@Transactional` method:
  - The flagged private helpers in `ProvisioningStepRunner` and `CheckoutService` are only called from transactional public methods.
  - `SessionLivenessChecker.isActive` and `OutboxEventProcessor.runHandler` are package-private `@Transactional` methods on class-based proxies, which Spring 6 supports.
- If a future caller forgot the transaction, the `set_config` would run in its own autocommit statement. The next query would then see either a NULL setting (no rows) or `''` (the `::uuid` cast raises). Both fail closed.
- Residual risks:
  - the `AuditService` context switch (§3.4);
  - anyone introducing session-level `SET`, or a PgBouncer session-pool assumption, in place of `set_config(..., true)`.

  Keep the transaction-local form, and add an ArchUnit-style test that every repository call on an RLS table runs inside a transaction.

---

## 4. Authentication

### 4.1 Token model and JWT key management

- **One key, one issuer, no type separation (F-01, Critical).** Every token is RS256-signed by the same in-memory key with issuer `https://api.nabd.health`:
  - staff access tokens (`auth/AuthService.java:578-598`)
  - `mfa_pending` (`:522-535`) and `mfa_setup_required` (`:144-157`)
  - `step_up` (`:279-288`)
  - owner `owner_pending_workspace` (`owner/OwnerService.java:156-167`)
  - operator access and `platform_mfa_pending` tokens (`platform/PlatformAuthService.java:207-243`)

  The resource server accepts any of them as a bearer credential (`config/SecurityConfig.java:94`). The revocation wrapper deliberately passes through any token that lacks `sid` or `tenantId` (`auth/SessionRevocationJwtDecoder.java:12-20,38`). Several authenticated endpoints have no `@PreAuthorize` and act on `sub`/`tenantId` alone (`AuthController.java:98-136`): MFA enrol and confirm, break-glass activate and deactivate, unlock, and support ticket raise.

  **Impact:** an intermediate token issued *before* the second factor is completed is accepted by endpoints that must require a full session. The result is that MFA provides no protection for any account, and staff can be elevated to Owner within the tenant (combined with F-02).

  **Fix:**
  - Add a mandatory `typ` (or `token_use`) claim and an `aud` per audience (`staff-api`, `platform-api`, `mfa`, `owner-select`).
  - Configure the resource-server decoder to accept *only* `typ=access` with the right audience. Validate intermediate tokens only in the controllers that consume them, with a separate decoder.
  - Require `hasAuthority(...)` or a non-empty session (`sid`) on every non-public endpoint.
- **Signing key regenerated at every boot (F-26, Medium).** `config/JwtConfig.java:32-36` generates a fresh RSA-2048 key, with a random `kid` (`:42`), on each start. Every deploy or restart invalidates all access tokens. A second replica rejects the first one's tokens. There is no JWKS, no rotation overlap, no `aud`, and issuer is not validated (`:53-55`). **Fix:** load the key from KMS/HSM (NB-009), publish a JWKS with two active `kid`s for rotation, and validate `iss`, `aud` and `typ`.

### 4.2 Token lifetimes, storage, rotation and revocation

- **TTLs:** access tokens last 15 minutes and refresh tokens 30 days (`application.yml:58-60`). Every rotation issues a fresh 30-day expiry (`AuthService.java:600-603`), so a session that keeps refreshing has **no absolute lifetime**.
- **Rotation and reuse detection:** correct (S4).
- **Storage (F-17, Medium):** the clinic frontend stores both tokens in `localStorage` (`frontend/app/login/page.tsx:45-46`) and **never calls `/auth/refresh`**. Only the platform console refreshes (`frontend/app/platform/platformApi.ts:22-49`). This was confirmed by the frontend review (`04-frontend-review.md` §2.2). There is no CSP (`frontend/next.config.ts:3-5`). **Impact:** any script injection exposes a 30-day refresh credential with PHI access; today there is no known XSS sink. **Fix:** move to a same-origin BFF with an `httpOnly; Secure; SameSite=Strict` refresh cookie, keep the access token in memory, add CSRF protection on cookie-authenticated routes, and enforce a strict CSP.
- **Revocation gaps (F-16, Medium):**
  - Operator tokens carry `sid` but no `tenantId`, so the liveness check skips them (`SessionRevocationJwtDecoder.java:38`). Operator logout and revoke only take effect when the access token expires.
  - Tenant suspension and offboarding (`platform/tenant/TenantLifecycleService.java:44-56`) revoke nothing.
  - `refresh()` does not check tenant status (`AuthService.java:435-461`), and neither does owner workspace selection.
  - A role change in `PATCH /staff/{id}` does not revoke sessions (`staff/StaffService.java:111-145`).

  **Fix:**
  - Run the liveness check for operator tokens against `master.sessions`.
  - Revoke every session in the tenant on suspension, and check tenant status on refresh and workspace select.
  - Revoke the target's sessions on any role, scope or field-grant change.
  - Add an absolute session lifetime of about 12 hours, or one shift.

### 4.3 PIN login, OTP and brute-force protection (F-05 High, F-07 High)

- **No TOTP attempt limit (F-05, High).** `verifyMfa` (`AuthService.java:229-257`), `issueStepUpToken` (`:264-291`) and `confirmMfa` (`:310-329`) check the code against the stored secret with a ±1-step (±30s) acceptance window (`TotpService.java:24,48-56`) and **no per-account counter**. The 5-minute challenge TTL bounds a single challenge, but a fresh login re-issues a challenge, so there is no global ceiling on TOTP guesses once the PIN is known. **Impact:** a 6-digit code with a 3-window match is brute-forceable across re-issued challenges, defeating the second factor. **Fix:** count failed TOTP attempts per staff/operator and lock on the same threshold as the PIN lockout.
- **Per-IP rate limit trusts a client-supplied header (F-07, High).** `RequestMeta.clientIp()` returns the first value of the `X-Forwarded-For` header when present (`common/RequestMeta.java:12-14`); the per-IP throttle (`AuthService.java:490-495`, 20/min) counts against that value. **Impact:** rotating a forged `X-Forwarded-For` defeats the throttle entirely. The per-account lockout still applies, but on endpoints that don't resolve to one account (OTP request, PIN-reset) the IP limit is the only control, and it is bypassable. **Fix:** derive the client IP from the trusted reverse-proxy's rightmost hop (`ForwardedHeaderFilter` + a trusted-proxy allow-list), never from the raw header.
- **PINs are 4–6 digits (F-21, Medium).** All credential inputs use `^[0-9]{4,6}$` (`LoginRequest.java:10`, `UnlockRequest.java:6`, `PinResetConfirmRequest.java:10`, `AcceptInviteRequest.java:6`, `OwnerLoginRequest.java:9`, `OperatorLoginRequest.java:9`). A 4-digit PIN is 10,000 values. Defensible for interactive use behind the lockout, but weak if the lockout is evaded (F-07) and marginal for Owner/operator accounts. **Fix:** require ≥6 digits, and a passphrase for Owner/operator.
- **Account lockout is correct and non-renewing (strength).** Failures since last success drive exponential backoff capped at 30 min (`AuthService.java:503-520`); a still-locked hit deliberately does **not** record a fresh failure (`:497-502`), which prevents a trivial permanent-lockout DoS. Mirrored in `OwnerService.java:179-196` and `PlatformAuthService.java:188-205`.
- **PIN reset and OTP are no-enumeration and single-use (strength, with F-12 caveat).** PIN reset always returns 202, stores a 256-bit token as SHA-256 with a 15-minute expiry, and consumes-and-sets atomically (`AuthService.java:335-371`, `AuthRepository.java:137-149`). But the reset token and login OTP are both sent through `otpSender`, which logs the raw value when `WHATSAPP_LOG_OTP=true` — the default (see F-12, §8).

### 4.4 MFA enforcement, secrets, and step-up

- **MFA secret encryption (strength).** 160-bit TOTP secrets (`TotpService.java:28-32`), AES-256-GCM with a random 96-bit IV (`AesGcmCipher.java:33-47`); `mfa_enabled` is flipped only after a confirming code (`AuthRepository.java:113-120`); `APP_CRYPTO_KEY` has no default and the app fails to boot without it (`AesGcmCipher.java:29-31`, `application.yml:233-237`). Key-management weaknesses are tracked in F-11 (§8).
- **MFA is not forced for the accounts that most need it (F-10, High).** Enforcement is a per-role boolean `roles.mfa_required` (`V31:9`, `AuthService.java:139-142`) — a deliberate, defensible design (free-text role names are an unsafe signal), but there is no invariant forcing it on for the built-in Owner role or for any role holding `billing:refundDiscount`/`:approve`. Platform operators enforce MFA only if `mfa_enabled` is set per operator (`PlatformAuthService.java:91`); there is no server-side requirement that `super_admin` has MFA. Owner accounts have no MFA concept at all (`OwnerService.java:70-91`). **Impact:** the highest-privilege identities can be configured PIN-only. **Fix:** make MFA non-optional for the built-in Owner role, every role with refund/discount/approve authorities, all platform operators, and owner accounts.
- **Step-up token is not bound to a session or tenant (F-34, Low).** The `step_up` token carries only `purpose` + `sub` (`AuthService.java:281-287`), and `StepUpVerifier` checks only those (`StepUpVerifier.java:31-32`). It is adequate for the destructive-action gate it protects (patient merge/unmerge, staff suspension — `StepUpVerifier`, `PatientController.java:100-114`) but is not tied to the session that minted it. **Fix:** bind `sid`/`tenantId` into the step-up token and verify them.

### 4.5 Break-glass emergency access (V31) (F-02, Critical)

`POST /v1/auth/break-glass/activate` carries **no `@PreAuthorize`** (`AuthController.java:126-130`). Any authenticated caller whose token carries `sub`+`tenantId` — including the first-factor-only `mfa_pending` token of F-01 — supplies a free-text reason and immediately receives a token pair into which the tenant's built-in **Owner** grant set is folded for 30 minutes (`AuthService.java:375-388,560-572`; `AuthRepository.insertBreakGlass`/`hasActiveBreakGlass`/`findBuiltInOwnerRole`). Activation is audited (`auth.break_glass_activated`) and surfaced to anyone with `staff:view` (`AuthController.java:138-142`), but there is no permission gate, no approver, no step-up and no notification channel.

**Impact:** self-service privilege escalation to Owner for any staff account. Because the granted set includes `staff:edit`, the holder can create a role delegation (§5.4) to persist Owner-equivalent rights for up to 30 days beyond the 30-minute window. Combined with F-01, a first-factor-only party reaches this.

**Fix:** gate activation on a dedicated permission, require step-up MFA, require a second approver or out-of-band notification, and narrow the granted scope to read-only emergency access rather than the full Owner write set. `POST /v1/auth/break-glass/{id}/deactivate` is also un-gated (F-36, Low) but scoped to the caller's own tenant context (`AuthService.java:390-397`), so its risk is low.

### 4.6 Recovery, PIN reset and session management

- **No MFA recovery or reset path (F-22, Medium).** Eight single-use recovery codes are issued and hashed at enrolment (`AuthService.java:321-328`, `AuthRepository.java:122-133`), but `consumeRecoveryCode` has **no caller** — nothing verifies a recovery code to complete MFA, and there is no admin MFA-reset flow. **Impact:** a user who loses their authenticator is locked out with no self-service or audited admin recovery. **Fix:** wire recovery-code verification into the MFA challenge; add an audited admin reset.
- **Session revocation is strong for staff (strength).** Liveness is checked on every staff request by the wrapping decoder (`SessionRevocationJwtDecoder.java:184-193`, `SessionLivenessChecker.java:220-224`), so a revoke takes effect on the next request; suspending a staff member kills all their sessions (`StaffService.java:149-155`). Gaps are tracked in F-16 (§4.2): operator tokens skip the liveness check, tenant suspension revokes nothing, and refresh does not re-check tenant status. There is also no absolute refresh-family lifetime, so a stolen-but-unused refresh token is valid for the full 30 days (F-35, Low).

### 4.7 Owner invite flow and shadow-staff

- Owner login is two-step: PIN → `owner_pending_workspace` token (5 min) → clinic selection mints a normal staff-shaped token (`OwnerService.java:70-146`). Clinic selection is data-scoped by `ownsClinic` and returns 404 for an unowned clinic (`OwnerRepository.java:136-144`). Good — but it does not check tenant lifecycle status, so an owner can enter a suspended/offboarded clinic (tracked in F-16).
- Shadow staff gets the clinic's built-in full-access role with `pin_hash` left NULL forever (`OwnerRepository.java:154-176`); the NULL PIN blocks normal login (`AuthService.java:115`). Sound.
- Invite tokens (owner and staff) are 256-bit, stored as SHA-256, 72-hour expiry, single-use (`OwnerService.java:100-120`, `StaffService.java:83-96`), revealed once via the provisioning response (`ProvisioningStepRunner.java:207-258`). Good. The raw owner-account invite link is also emailed in clear (`:215-218`); acceptable for an invite link, but email is not a confidential channel.

### 4.8 Idle lock

Idle lock is client-only (5 min; `frontend/app/IdleLockGuard.tsx:11`), PIN-verified against the live session without re-login (`AuthService.unlock:413-421`). It avoids losing draft notes, but it is pure UI — the access/refresh tokens in `localStorage` are untouched, so a stolen device is not protected by the lock (cross-references F-17). A unlock after the 15-minute access-token expiry returns 401, which the UI reports as "Incorrect PIN." (frontend review §2.2) — a usability-driven pressure to lengthen the access TTL.

---

## 5. Authorization

### 5.1 Model and enforcement

Clinic RBAC is a per-tenant set of roles whose `grants` is a JSON array of `ModuleGrant` rows (module + view/create/edit/delete/approve/refundDiscount/export — `common/ModuleGrant.java:31-40`). At login the grants are flattened to `module:action` strings into the JWT `permissions` claim (`common/GrantsFlattener.java:12-26`, `AuthService.java:542,586`), which Spring maps directly to authorities with no prefix (`config/SecurityConfig.java:43-50`); `@PreAuthorize("hasAuthority('module:action')")` with `@EnableMethodSecurity` (`:29`) is the enforcement point. Platform operators use a fixed role→surface matrix, not configurable grants (`platform/PlatformPermissions.java:29-63`). Delegations (V30) and break-glass (V31) fold extra grants into the token at mint time (`AuthService.java:547-572`).

This is a clean deny-by-default design *where the annotation is present*. The weaknesses: enforcement is permission-level only, not object-level (§5.3); a few sensitive endpoints have no annotation (§5.2); and there is **no server-side endpoint registry** — the Security Controls sheet lists NB-050 ("deny-by-default authorisation on every endpoint") as an unverified go-live blocker, and no DB-backed endpoint→permission table exists. The OpenAPI `x-required-permission` values are documentation, not enforcement, and are stale (§5.2). **(F-24, Medium.)**

### 5.2 Endpoints lacking a permission check

Inventory of all 264 `@*Mapping` methods cross-checked against `@PreAuthorize` (method- and class-level): 234 carry an authority check; 30 do not. Of those 30, 28 are intentional — the login/bootstrap flows `permitAll` in `SecurityConfig.java:76-91` (login, refresh, OTP, mfa/verify, pin-reset, invite-accept, owner/platform login, WhatsApp webhook) and `authenticated`-only session endpoints that scope to the caller's own `sub` (`/auth/logout`, `/auth/sessions`, `/platform/auth/me`, `/owners/me/workspaces`, `/owners/workspaces/select`). The two that matter are both break-glass: activate (F-02, Critical) and deactivate (F-36, Low). `POST /v1/support/tickets` is `authenticated`-only by design (any staff may raise a ticket — `TicketController.java:28`), acceptable.

**OpenAPI cross-check (F-24, Medium).** The spec documents 131 operations; the code exposes 264, so 139 code endpoints have no `x-required-permission` entry at all, and the one documented mismatch (`GET /v1/clinical/drugs/search`, accepts `clinical:view` OR `clinical:edit` — `PrescriptionController.java:74`) is benign. The OpenAPI permission catalogue therefore cannot serve as the authorization source of truth. **Fix:** generate an endpoint→permission registry from the annotations and verify it in CI; regenerate the OpenAPI permission values from the same source.

### 5.3 Object-level access (BOLA / IDOR) (F-06, High)

Tenant scoping is enforced in SQL on nearly every clinic query (≈340 explicit `tenant_id = ?` predicates, S1) on top of RLS; a scan for id-lookups lacking a tenant predicate found only master-schema/platform tables (cross-tenant by design) and the bootstrap `SECURITY DEFINER` token lookups. No clinic-facing repository fetches a tenant-scoped row by bare id without a predicate or RLS. Good.

**But intra-tenant object-level scoping is confined to the Patients and Reports modules.** `requireVerifiedAndResolveScope` and the `own_patients_only` row-scope live only in `PatientService` (`:426-434`) and `ReportsService` (`:146-164`), and the consent gate is a SQL `NOT EXISTS` only in `PatientRepository` (`:28-32`). The clinical controllers — `TimelineController`, `NoteController`, `PrescriptionController`, `ClinicalDocumentController`, `VitalsController`, `AllergyController`, `ConditionController`, dental — gate on `clinical:view`/`clinical:edit` only and fetch by `patientId`/`queueEntryId` with tenant scoping but **no doctor-ownership scope, no verification gate and no consent check**. **Impact:** any staff member with `clinical:view` can read the full clinical history (timeline, notes, prescriptions, documents, vitals, allergies) of **any** patient in the tenant, including patients whose `data_processing` consent is withdrawn (withdrawal removes them from `/v1/patients/**` but not `/v1/clinical/**`); these reads are unaudited (F-09). **Fix:** apply the consent, verification and (where the product requires) doctor-ownership gates across all `/v1/clinical/**` reads, enforced in SQL, and audit every PHI read.

### 5.4 Privilege escalation via staff role assignment (F-08, High)

Role create/update correctly enforces "no grant beyond your own" and blocks editing built-in roles (`RoleService.java:77-97`); delegation create runs the same check against the delegated role's grants and caps the window at 30 days (`:101-115`). **But `PATCH /v1/staff/{id}` and the invite flow accept any `roleId` with no such check** — they validate only that the role exists (`StaffService.java:116-118,85-96`) and assign it, and the built-in Owner role is a valid `roleId`. **Impact:** a holder of `staff:edit`/`staff:create` (which does not imply Owner-level grants) can assign the Owner role — or any role richer than their own — to another account or a freshly invited account they control, escalating privilege; the role-builder guard is bypassed because assignment is a separate code path. Role/scope changes via `patch` are also not audited (only `professionalRegistrationNo` is — `:127-146`). **Fix:** apply "assigned role's grants ⊆ caller's grants" in `patch` and `invite`; audit every role/scope change; revoke the target's sessions on the change (F-16).

### 5.5 Department / brand visibility scope

Department assignment drives queue/checkout status routing (`QueueService.java:141`, `CheckoutService.java:116`) but is not a data-isolation boundary — there is no "staff see only their department's patients" scope. Brand visibility scope (`brands.data_visibility_scope`) widens RLS across sibling facilities (F-04, §3.4): latent today but a consent-free cross-facility sharing mechanism with no `WITH CHECK` (so it would also permit cross-facility writes), no expiry and no audit.

### 5.6 UI-only gating and field-level restriction (F-25, Medium)

NB-052 requires field-level restrictions applied in the API response, not merely hidden in the UI. This is enforced server-side for exactly one case — the patient financial field is nulled when the caller lacks the `financial` field-grant (`PatientService.java:475-476`). `field_grants` is consulted in no other response. Given F-06 (clinical reads unscoped), the field-level-restriction control is substantially unmet beyond that single field. Maker-checker controls are present where required: refund approval and discount approval both block self-approval (`PackageInstanceService.java:316-318`, `platform/billing/DiscountService.java:134-137`). **Fix:** apply `field_grants` consistently across responses.

---

## 6. Consent and data sharing (current state)

This section records only what exists today. The target architecture is being written separately in `docs/engineering/07-consent-and-data-sharing-architecture.md`; it is not reproduced here.

- **Artefact.** A single `consents` table (`V5:41-54`) with `consent_type ∈ {treatment, data_processing, messaging, guardian_access}` (V28 added the last), `granted_at`, `withdrawn_at`, `version`, RLS-scoped per tenant. There is **no consent capture at registration** — the V5 header says only withdrawal is modelled; rows are created on withdrawal (`PatientRepository.java:262`) or guardian grant. "Consent" today is an opt-out marker, not a versioned affirmative artefact (NB-241/242 unmet).
- **Enforcement.** A withdrawn `data_processing` consent is a hard SQL gate on `/v1/patients/**` (`PatientRepository.java:28-32`), meeting NB-053 for that module only — it does not gate `/v1/clinical/**` (F-06). Messaging consent is default-deny (no row = blocked, `WhatsAppMessageRepository.java:43-52`, S14).
- **Guardians (V28).** Proxy relationship on `patients.guardian_id`; `guardian_access` consent is written atomically with the guardian change by `PatientService` and the grant/reassign/revoke actions are audited; the generic withdraw endpoint excludes `guardian_access` to avoid an inconsistent state (`V28:14-20`). An age-18 review worklist exists.
- **Support access (V15).** A platform operator's 60-minute, reason-logged, audited read of a tenant's **redacted** patient view (clinical fields never returned). "Consent" here is the operator's own logged request, not a tenant-side approval (F-20); each view is audited (`patient.support_view`).
- **Cross-facility sharing** today is only the brand visibility scope (F-04): wholesale, consent-free, no expiry, no audit. No consent artefact governs cross-facility or cross-hospital flow.

---

## 7. Audit logging

- **Completeness (F-09, High).** `audit_log` (V14) is written via `AuditService.record` from 20 services and captures who/what/when/before/after for patient update, allergy/vitals corrections, prescription signing/overrides, note amendments, document issue, day-close, refunds, discounts, delegations, break-glass, support view and WhatsApp send. **PHI *reads* are not audited** — the only logged read is the platform support view and the audit export itself. Ordinary clinical reads (`/v1/clinical/**`, patient detail, timeline) write no audit row, so no trace exists of who read which patient, defeating NB-293's mass-export detection and the privacy packs' access-logging. Staff role/scope changes (F-08) and interactive cross-tenant audit searches (F-14) are also unaudited. **Fix:** audit every PHI read and add read-volume alerting.
- **Tamper evidence (strength, with F-27 caveat).** Two independent defences (`V14:1-18`): `BEFORE UPDATE/DELETE/TRUNCATE` triggers `RAISE` unconditionally (`:81-92`, grant-independent), and a per-tenant SHA-256 hash chain (`audit_row_hash`/`audit_log_chain`/`verify_audit_chain`, `:48-113`). This is strong (S8). **Caveats (F-27, Medium):** nothing runs `verify_audit_chain` on a schedule, and there is no off-box signed checkpoint, so a privileged attacker who disables triggers (possible in the §3.3 owner-role topology, F-03) and rewrites the whole chain for a tenant would go undetected until a manual verification. **Fix:** scheduled chain verification with alerting; periodic external checkpoints; ensure the app role is a non-owner (F-03).
- **Retention (F-28, Medium).** No retention or partitioning policy for `audit_log` (no purge job — `V1:110` notes the same for sessions). The privacy packs require 3-year audit retention (NB-078) with per-region clocks (NB-247); unimplemented.

---

## 8. Data protection

- **Field-level encryption.** National ID and TOTP secrets are AES-256-GCM encrypted (`AesGcmCipher`, `V3:26`, `V7:27`); national ID is never returned by the API (S7). Name, phone, DOB and address are stored in clear, relying on RLS + at-rest disk encryption (deployment-dependent, unverified). This matches the stated scope (NB-285 scopes column encryption to national identifiers), but DOB/phone/address are PHI under all four regimes.
- **Key management (F-11, High).** One global `APP_CRYPTO_KEY` (`application.yml:233-237`) encrypts every tenant's secrets across both IN and KSA regions; no per-tenant or per-region key, no KMS, no rotation runbook (deferred to NB-009/NB-283, both open). The JWT signing key is ephemeral (F-26). **Impact:** a single key compromise exposes every tenant's MFA secrets and national IDs across regions; rotation is impossible without re-encrypting in place. NB-283 (region-scoped KMS, per-tenant keys) is a go-live blocker and unmet. **Fix:** region-scoped KMS, per-tenant data keys, documented rotation.
- **PHI / secrets in logs (F-12, High).** `WHATSAPP_LOG_OTP` defaults to `true` (`application.yml:232`), so every login OTP **and every PIN-reset token** is written to the application log (`TemplateWhatsAppOtpSender.java:25-27`) — both credential-equivalent. `application.yml:196-199` acknowledges no PHI-redaction scanner (NB-158/NB-304 open) and that logging is "hand-disciplined"; a scan of hot-path log statements found IDs rather than names/phones, and the RFC 7807 handler logs only title/detail/traceId (`GlobalExceptionHandler.java:62`), so routine PHI leakage is low — but the OTP/reset-token default is a clear defect, and free-text `reason` fields are logged (`AuthService.java:386`) and may carry PHI. **Fix:** default `WHATSAPP_LOG_OTP=false`; never log reset tokens; add the NB-304 redaction scanner before real data.
- **Data residency (F-13, High).** `tenants.region` is constrained to `IN`/`KSA` only (`V1:23`) — no region for the UAE or Qatar, two of five target jurisdictions — and **one database holds both IN and KSA tenants**, isolated by RLS only, not a regional boundary. NB-271 (region pinned at provisioning, no cross-region replication) and NB-283 are go-live blockers and unmet. **Impact:** KSA PDPL and prospective UAE/QA residency rules require in-country storage; a shared cross-region database cannot satisfy them. **Fix:** per-region deployments with region pinned irreversibly at provisioning; add UAE/QA.
- **Backups and data-subject rights vs retention (F-29, Medium, design gap).** No backup/PITR, retention, legal-hold or true-erasure mechanism exists (no purge jobs, no DSR engine; `reports/retention` is an aggregate dashboard only). Patient `delete` is a soft status flip / merge. NB-078/244/245/247/306 are go-live blockers and unmet; the medical-record-retention-overrides-erasure tension (legal hold) is unmodelled. **Fix:** DSR engine with statutory timers, per-region retention clocks + legal hold, encrypted regional backups with PITR.

---

## 9. Web / API hygiene

- **CORS.** Explicit origin allow-list (localhost + Vercel origin), env-overridable, restricted methods/headers, credentials not enabled (`SecurityConfig.java:53-65`, `application.yml:201-206`). Reasonable.
- **CSRF.** Disabled (`SecurityConfig.java:72`), correct for a stateless bearer-token API with no cookies. If the F-17 cookie-based BFF is adopted, CSRF protection must be added for cookie-authenticated mutations.
- **Security headers / CSP (F-15, High).** `frontend/next.config.ts:3-5` is empty — **no CSP, HSTS, X-Frame-Options, Referrer-Policy or Permissions-Policy**; the backend sets none either. With tokens in `localStorage` (F-17), the absence of CSP means any XSS or malicious dependency can exfiltrate both tokens. NB-291 (CSP in enforce mode) is a go-live blocker and unmet. **Fix:** strict CSP, HSTS preload, frame-deny, Permissions-Policy at the edge/Next middleware.
- **Rate limiting (F-18, High).** The only limit is the auth per-IP limit, which trusts `X-Forwarded-For` (F-07) and covers auth paths only. There is no per-tenant/user/phone limit, and no limit on business endpoints — the audit-search `limit` is caller-controlled with no clamp (`AuditSearchController.java:46`, F-14). NB-288 is a go-live blocker and largely unmet. **Fix:** multi-dimension rate limiting; clamp all page sizes server-side.
- **SQL construction (strength).** No user input is concatenated into SQL; dynamic `SET`/`WHERE` builders use fixed column literals with bound parameters (`PatientRepository.java:223-231`; the `EncounterListRepository`/`TimelineRepository`/`AppointmentRepository` builders append only constant clause fragments and bind all values), audit-search `LIKE` input is escaped (`V85:39-40`), and CSV exports prefix formula-trigger characters to block CSV injection (`AuditSearchService.java:160-176`). No SQL-injection finding (S11).
- **File uploads for clinical documents (V54) (F-19, High).** `clinical_documents` stores only structured text (`body jsonb`) plus an issued snapshot; there is **no binary upload, object store or file-bytes handling anywhere in the backend** (no `MultipartFile`/S3/blob/bytea, no multipart size config). The feature is text-only today, so the controls (NB-292 type-verify/AV-scan/signed short-lived URLs; NB-076 expiring access-logged links, never a file in open chat) are entirely absent — a go-live blocker the moment any clinical-document or attachment upload ships. **Fix:** build the upload pipeline before any upload feature.
- **WhatsApp webhook signature (strength).** HMAC-SHA256 over the raw body, constant-time compared, rejecting everything when the app secret is blank (`WhatsAppWebhookController.java:57-63,96-104`); the subscribe handshake constant-time-compares the verify token (`:47-54`). Correct (S9).
- **Secrets in the repo (strength).** None found. Tracked `frontend/.env.production` holds only a public API URL; a tree + git-history scan for private keys/cloud tokens/live secrets was clean (the only `DB_PASSWORD=nabd_app` in history is a local-dev default). `APP_CRYPTO_KEY` has no default and must be supplied at boot (S16).
- **Dependency versions (F-30 and F-31).** `next@16.3.1` (`frontend/package.json:14`) is flagged **critical** by npm audit — upgrade to ≥16.4.2 (F-31, High); no committed SBOM/SCA (NB-290 unmet). Testcontainers 1.19.8 is incompatible with Docker Engine 29 (F-30, Medium), so the Postgres-backed isolation/auth tests — the only ones that exercise RLS and lockout (§3.5) — may not run in current CI, and the suite's comments confirm some tests were authored but never executed. Backend uses Spring Boot 3.3.4 and BouncyCastle 1.78.1; Logback not log4j-core (S16). **Fix:** upgrade Next; align Testcontainers with the CI Docker engine so the RLS tests run; add SCA + SBOM to CI.

---

## 10. Findings

Severity is impact × likelihood **given the five-jurisdiction healthcare context, before any real patient data**. Effort: S (≤1 day), M (days), L (weeks+). F-01, F-02, F-05, F-07, F-14, F-16, F-17, F-20, F-23, F-26, F-30 and F-37 were opened in §§3–4.2; the remainder are opened in §§4.3–9.

| ID | Sev | Area | Finding | Evidence | Impact | Fix | Effort |
|---|---|---|---|---|---|---|---|
| F-01 | Critical | AuthN | One signing key/issuer; challenge/pending/step-up tokens accepted as bearer credentials; several handlers don't check `purpose` | §4.1; `SecurityConfig.java:94`, `SessionRevocationJwtDecoder.java:12-20,38`, `AuthService.java:144-157,522-535` | First-factor-only party reaches break-glass + MFA enroll → full MFA bypass | `typ`/`aud` per audience; access-only resource decoder; assert `purpose` per handler | M |
| F-02 | Critical | AuthZ | Break-glass activation has no permission check; any authenticated staff self-elevate to Owner for 30 min, extendable via delegation | §4.5; `AuthController.java:126-130`, `AuthService.java:375-388,560-572` | Self-service Owner escalation; persistent via 30-day delegation | Dedicated permission + step-up + approver/notification; scope to read-only | M |
| F-03 | Critical (dep.) | Tenancy | No `FORCE ROW LEVEL SECURITY`; deploy paths migrate as app role → table owner → RLS skipped | §3.3; `Dockerfile:22`, `scripts/dev-up.sh:14,25`, `application.yml:161-164` | Any missing `tenant_id` predicate = cross-tenant breach; audit triggers disablable | Separate owner role / non-owner connection / `FORCE RLS`; verify via `pg_class` | M |
| F-04 | High | Tenancy | Brand visibility scope widens RLS across sibling facilities with no consent/expiry/audit and no `WITH CHECK` (permits cross-facility writes) | §3.4; `TenantContext.java:34-47`, `V6:92-131`, `CrossFacilityVisibilityTest.java:76-88` | Latent cross-facility PHI sharing/writes without consent once scope is set | Consent-governed sharing; per-command `WITH CHECK`; audit widening | L |
| F-05 | High | AuthN | TOTP verification has no attempt limit | §4.3; `AuthService.java:229-257,264-291`, `TotpService.java:48-56` | 6-digit TOTP brute-forceable across re-issued challenges | Per-account failed-TOTP counter + lockout | S |
| F-06 | High | AuthZ | `/v1/clinical/**` reads gated on `clinical:view` only — no consent/verification/ownership scope | §5.3; clinical controllers vs `PatientService.java:426-434`, `PatientRepository.java:28-32` | Any `clinical:view` holder reads any patient's full history incl. consent-withdrawn; unaudited | Apply consent/verification/ownership gates in SQL across clinical reads | L |
| F-07 | High | AuthN | Per-IP rate limit trusts client-supplied `X-Forwarded-For` | §4.3; `RequestMeta.java:12-14`, `AuthService.java:490-495` | IP throttle (only control on OTP/reset probing) bypassable | Derive IP from trusted proxy hop | S |
| F-08 | High | AuthZ | `PATCH /v1/staff/{id}` and invite accept any `roleId` (incl. Owner) with no "no grant beyond your own" check; role changes unaudited | §5.4; `StaffService.java:83-96,111-126` vs `RoleService.java:88-97` | `staff:edit`/`create` holder assigns Owner/richer role → escalation | Enforce assigned-grants ⊆ caller-grants; audit; revoke sessions on change | M |
| F-09 | High | Audit | PHI reads are not audited except the support view | §7; `AuditService` call sites | No trail of who read which patient; defeats mass-export detection | Audit clinical/patient reads; read-volume alerting | M |
| F-10 | High | AuthN | MFA not forced for Owner, refund/discount/approve roles, or operators (incl. `super_admin`); owner accounts have no MFA | §4.4; `AuthService.java:139-142`, `V31:9`, `PlatformAuthService.java:91`, `OwnerService.java:70-91` | Highest-privilege accounts can be PIN-only | Force MFA on those roles/accounts | M |
| F-11 | High | Crypto | One global `APP_CRYPTO_KEY` across tenants and both regions; no KMS/per-tenant/per-region key, no rotation | §8; `application.yml:233-237`, `AesGcmCipher.java:29-31` | Single key compromise exposes all tenants' MFA secrets + national IDs | Region-scoped KMS, per-tenant keys, rotation (NB-283) | L |
| F-12 | High | Data | `WHATSAPP_LOG_OTP=true` by default logs every login OTP and PIN-reset token; no PHI-redaction scanner | §4.3/§8; `application.yml:232`, `TemplateWhatsAppOtpSender.java:25-27` | Credential-equivalent values in logs | Default off; never log reset tokens; add NB-304 scanner | S |
| F-13 | High | Residency | Region is `IN`/`KSA` only (no UAE/QA); one DB holds both regions, RLS-isolated only | §8; `V1:23` | Cannot meet KSA/UAE/QA in-country storage | Per-region deploys, region pinned at provisioning (NB-271) | L |
| F-14 | High | Audit/AuthZ | Platform audit console reads all tenants' before/after JSON; page size uncapped; interactive view unaudited | §3.4/§7; `AuditSearchController.java:26-27,46`, `AuditSearchService.java:51-54`, `PlatformPermissions.java:43` | Any `audit_compliance:view` operator bulk-reads cross-tenant PHI-bearing audit with no trace | Clamp page size; audit interactive searches; narrow who holds the grant | M |
| F-15 | High | Web | No CSP/HSTS/security headers (empty `next.config.ts`); backend sets none | §9; `frontend/next.config.ts:3-5` | XSS can exfiltrate `localStorage` tokens; NB-291 unmet | Strict CSP + HSTS + frame-deny + Permissions-Policy | M |
| F-16 | Medium | AuthN | Session revocation gaps: operator liveness skipped, tenant suspension revokes nothing, role change doesn't revoke, no absolute lifetime | §4.2; `SessionRevocationJwtDecoder.java:38`, `TenantLifecycleService.java:44-56`, `AuthService.java:435-461`, `StaffService.java:111-145` | Suspended tenant's staff and operators act past revocation | Operator liveness vs `master.sessions`; revoke on suspension/role change; re-check tenant on refresh; absolute lifetime | M |
| F-17 | Medium | Web/AuthN | Both tokens in `localStorage`; clinic app never refreshes 15-min token | §4.2; `04-frontend-review.md §2.2`, `login/page.tsx:45-46` | XSS steals 30-day refresh → persistent PHI access; churn pressures longer TTLs | Cookie-based BFF; in-memory access token; single-flight refresh | L |
| F-18 | High | Web | Rate limiting auth-only + IP-forgeable; no per-tenant/user/phone; no business-endpoint limit; audit-search `limit` unclamped | §9; `AuthService.java:490-495`, `AuditSearchController.java:46` | Credential/abuse/scraping unthrottled; NB-288 unmet | Multi-dimension rate limiting; clamp page sizes | M |
| F-19 | High | Web | No file-upload pipeline; any future clinical-document upload has no type/AV/signed-URL controls | §9; `V54`, no `MultipartFile`/object store in backend | Go-live blocker the moment uploads ship (NB-292/076) | Build upload pipeline: type-verify, AV scan, signed short-lived URLs | L |
| F-20 | Medium | Privacy | Support access self-granted, no tenant approval; returns direct identifiers (name/phone/DOB) | §3.4/§6; `SupportAccessService.java:24-31,82-104` | Operator views identifiable patient data without tenant consent | Tenant-side approval step; minimise returned identifiers | M |
| F-21 | Medium | AuthN | PINs are 4–6 digits | §4.3; `LoginRequest.java:10` et al. | Low-entropy credential | ≥6 digits; passphrase for Owner/operator | S |
| F-22 | Medium | AuthN | MFA recovery-code consumption has no caller; no self-service or admin MFA reset | §4.6; `AuthRepository.java:128-133` (no caller) | Lost authenticator = lockout; ad-hoc DB fixes | Wire recovery-code verification; audited admin reset | M |
| F-23 | Medium | Test | Isolation suite covers few tables; cross-tenant writes and clinical reads untested; some tests never executed | §3.5; `CrossTenantIsolationApiTest.java:114`, `CrossFacilityVisibilityTest.java` | Missing-predicate regressions on untested tables go uncaught | Schema-level RLS test + per-table isolation + write tests | M |
| F-24 | Medium | AuthZ | No server-side endpoint→permission registry (NB-050); OpenAPI `x-required-permission` stale (131 of 264 ops) | §5.1-5.2 | Authorization correctness relies on per-method annotations with no single source of truth | Generate + CI-verify an endpoint registry from annotations | M |
| F-25 | Medium | AuthZ | Field-level restriction (NB-052) enforced in one place only (patient financial field) | §5.6; `PatientService.java:475-476` | Other restricted fields not masked server-side | Apply `field_grants` consistently in responses | M |
| F-26 | Medium | AuthN | JWT signing key regenerated per boot; no JWKS/rotation; `iss`/`aud` not validated | §4.1; `JwtConfig.java:32-55` | Restart/scale-out invalidates all tokens; effectively single-instance | KMS-backed key; rotating JWKS; validate `iss`/`aud`/`typ` | M |
| F-27 | Medium | Audit | Hash-chain never verified on a schedule; no off-box checkpoint; integrity depends on non-owner app role (F-03) | §7; `V14:62-113`, `AuditService.java:55-61` | Undetected tampering if owner role + no scheduled verify | Scheduled `verify_audit_chain` + alerting; external checkpoints | M |
| F-28 | Medium | Audit | No audit/data retention or partitioning (3-yr, per-region required) | §7; `V1:110` | NB-078/247 unmet | Retention + partitioning with per-region clocks | M |
| F-29 | Medium (design) | Privacy | No backup/PITR, DSR engine, retention, legal hold or true erasure; delete is soft | §8 | NB-078/244/245/247/306 unmet; DSR-vs-retention unmodelled | DSR engine, retention clocks, legal hold, encrypted regional backups | L |
| F-30 | Medium | Supply/Test | Testcontainers 1.19.8 incompatible with Docker Engine 29; RLS/auth tests may not run | §3.5/§9 | Isolation tests unverified in current CI | Align Testcontainers with CI Docker engine | S |
| F-31 | High | Supply chain | `next@16.3.1` critical per npm audit; no SBOM/SCA | §9; `frontend/package.json:14` | Known-vuln framework; NB-290 unmet | Upgrade to ≥16.4.2; add SCA/SBOM to CI | S |
| F-32 | Medium | Tenancy | `AuditService.record()` silently replaces `app.tenant_id` for the rest of the transaction | §3.4; `AuditService.java:45` | Latent cross-tenant hazard for a future multi-tenant transaction | Assert tenant unchanged, or save/restore the prior GUC | S |
| F-33 | Medium | Tenancy | Tenant-data tables without RLS (`login_attempts`, `workflow_definitions`, `package_eligible_doctors`; `owners`/`brands`/tax at DB layer) | §3.2/§3.5; `V1:132-140`, `V40:7-15`, `V27:58-62` | Only app-level predicate protects these; no RLS backstop | Add RLS (or move to `master`); add `login_attempts` purge | M |
| F-34 | Low | AuthN | Step-up token not bound to session/tenant | §4.4; `AuthService.java:281-287`, `StepUpVerifier.java:31-32` | Step-up token reusable outside its minting session | Bind `sid`/`tenantId` and verify | S |
| F-35 | Low | AuthN | No absolute refresh-family lifetime; stolen-unused refresh token valid 30 days | §4.2; `AuthService.java:600-603` | Extended theft window | Absolute family lifetime (~12h/shift) | S |
| F-36 | Low | AuthZ | `POST /auth/break-glass/{id}/deactivate` has no permission check | §5.2; `AuthController.java:132-136`, `AuthService.java:390-397` | Scoped to caller's tenant context; low risk | Gate on a permission for consistency | S |
| F-37 | Medium | Privacy | `master.support_tickets` holds staff free-text (possible PHI), cross-tenant readable by every `support_tickets:view` operator | §3.4; `TicketAdminController.java:19-20` | Operator reads PHI pasted into tickets across tenants | Classify/redact ticket text; restrict/au­dit access | M |

Strengths S1–S16 (§2) stand; not repeated.

---

## 11. Compliance mapping

Legend: ✓ substantially met · ◐ partial · ✗ gap.

| Control | India — DPDP Act 2023 / DPDP Rules & ABDM | UAE — PDPL & Federal Law 2/2019 & DHA/ADHICS | KSA — PDPL & NCA ECC | Qatar — Law 13/2016 | Current state | Gap |
|---|---|---|---|---|---|---|
| Consent artefact (versioned, affirmative, withdrawable) | DPDP s.6 notice+consent; ABDM consent-manager | PDPL Art.4 consent | PDPL Art.5-6 | Law 13 Art.4 | ✗ opt-out marker only; no capture-at-registration (§6, F-04) | Consent artefact + registry (NB-241/242); doc 07 |
| Consent enforced at query level | DPDP purpose limitation | PDPL | PDPL | Law 13 | ◐ `/v1/patients/**` only, not `/v1/clinical/**` (F-06) | Extend gate to all PHI reads |
| Data residency / no cross-border | DPDP s.16 + ABDM localisation | PDPL Art.22; DHA in-UAE | PDPL Art.29; NCA ECC | Law 13 cross-border | ✗ IN+KSA one DB; no UAE/QA (F-13) | Per-region deploys pinned at provisioning (NB-271) |
| Encryption at rest / KMS per region | DPDP safeguards | ADHICS crypto | NCA ECC-2 | Law 13 | ◐ AES-GCM for national ID/TOTP; one global key (F-11) | Region KMS, per-tenant keys (NB-283) |
| Encryption in transit (TLS1.3/HSTS) | DPDP | ADHICS | NCA ECC | Law 13 | ◐ deployment-dependent; no HSTS/CSP from app (F-15) | Enforce TLS1.3 + HSTS (NB-284) |
| Access control / least privilege (incl. BOLA) | DPDP safeguards | ADHICS IAM | NCA ECC-2 | Law 13 | ◐ strong RBAC but clinical reads unscoped (F-06), role escalation (F-08), break-glass (F-02) | Fix F-02/F-06/F-08 |
| Audit / access logging of PHI | DPDP + ABDM | ADHICS logging | NCA ECC-4 | Law 13 | ◐ writes audited + hash-chained; reads not (F-09) | Audit reads; scheduled verify (F-27) |
| Breach detection & notification | DPDP s.8(6) | PDPL Art.9 | PDPL Art.20 | Law 13 | ✗ no SIEM/alerting/runbook (NB-293/248/249 open) | Detection + runbook |
| Data-subject rights (access/erasure/correction) | DPDP s.11-13 | PDPL Art.13-17 | PDPL Art.4 | Law 13 Art.5 | ✗ no DSR engine; soft-delete only (F-29) | DSR engine with statutory timers (NB-244) |
| Retention + medical minimums + legal hold | DPDP s.8(7) + clinical | DHA retention | MOH + PDPL | Law 13 | ✗ no retention/legal-hold (F-28/F-29) | Per-region retention + legal hold (NB-245/247) |
| MFA for privileged access | DPDP safeguards | ADHICS | NCA ECC-2 | Law 13 | ◐ available, not forced for Owner/operator (F-10) | Force MFA on privileged roles |
| Rate limiting / anti-abuse | — | ADHICS | NCA ECC | Law 13 | ✗ auth-only + IP-forgeable (F-18, F-07) | Multi-dimension limits (NB-288) |
| Secure messaging / no file in open chat | ABDM/DLT (India) | DHA | — | — | ✗ no expiring-link delivery; India DLT not done (NB-076/194); no uploads yet (F-19) | Secure delivery; DLT for India |
| Supply-chain / vuln management | DPDP safeguards | ADHICS | NCA ECC-5 | Law 13 | ✗ `next` critical; no SBOM/SCA (F-31) | Upgrade; SCA/SBOM (NB-290) |

---

## 12. Prioritised remediation roadmap

### Now — before any real patient data touches the system
1. **F-03** — connect as a non-owner DB role (or set `FORCE ROW LEVEL SECURITY` on every policy table); verify with the `pg_class` query in §3.3. Until proven, every RLS-backstop claim and the audit tamper story are unverified.
2. **F-01 / F-02** — stop accepting challenge/pending tokens as bearer credentials; gate break-glass (permission + step-up + approver). Together these are a single first-factor-only path to Owner.
3. **F-12** — default `WHATSAPP_LOG_OTP=false`; stop logging PIN-reset tokens.
4. **F-07 / F-05** — fix `X-Forwarded-For` trust; add a TOTP attempt limit. Both small.
5. **F-08** — add the "no grant beyond your own" check to staff role assignment/invite; audit role changes.
6. **F-31 / F-30** — upgrade `next` to ≥16.4.2; align Testcontainers so the RLS/auth tests actually run in CI.

### Before launch (go-live blockers from the Security Controls sheet)
7. **F-06 / F-09** — scope and audit all `/v1/clinical/**` PHI reads (consent + verification + ownership + read audit).
8. **F-26 / F-11** — persisted KMS-backed signing key with rotation/JWKS; region-scoped KMS with per-tenant keys (NB-009/283).
9. **F-13 / F-04** — per-region deployment with residency pinned at provisioning; add UAE/QA; replace brand visibility scope with consent-governed sharing + `WITH CHECK`.
10. **F-17 / F-15** — cookie-based BFF for tokens + strict CSP/HSTS.
11. **F-10 / F-18 / F-16** — force MFA on privileged roles; multi-dimension rate limiting; revoke sessions on suspension/role change.
12. **Consent & DSR** — consent artefact/registry and DSR engine per doc 07 and NB-241/242/244; retention + legal hold (NB-245/247); backups/PITR (NB-306) (F-28/F-29).
13. **F-19** — build the file-upload security pipeline before any clinical-document upload ships.
14. **F-14 / F-37 / F-20 / F-33 / F-32** — clamp and audit the platform audit console; classify/restrict support-ticket text; add a tenant-approval step to support access; add RLS to tenant-data tables; harden `AuditService` tenant-context handling.

### After launch (hardening and assurance)
15. **F-21 / F-22 / F-34 / F-35 / F-36** — longer PINs; MFA recovery and admin-reset; bind step-up to session; absolute refresh lifetime; gate break-glass deactivate.
16. **F-27 / F-28** — scheduled hash-chain verification with off-box checkpoints; audit retention/partitioning.
17. **F-24 / F-25** — CI-verified endpoint→permission registry; consistent field-level masking.
18. Independent VAPT (CERT-In India, KSA pen test), SIEM alerting, breach-runbook tabletop (NB-294/295/293/248).
