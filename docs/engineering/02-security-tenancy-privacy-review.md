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

1. **Token-type confusion (Critical — AUTH-2).** Every JWT the system mints shares one signing key and issuer, and the resource server accepts any of them as a bearer credential. These include the `mfa_pending` challenge, `mfa_setup_required`, the owner `pending_workspace` token and operator tokens. With only a victim's clinic code, email and PIN, a party holding the first-factor-only challenge token can:
   - call `POST /v1/auth/break-glass/activate` and receive a full access/refresh pair carrying **Owner** permissions, or
   - call `POST /v1/auth/mfa/enroll` to overwrite the victim's TOTP secret.

   Either way MFA is bypassed completely.
2. **Break-glass is self-service Owner elevation (Critical — AUTH-8).** Any authenticated staff member, with no permission check, can obtain the clinic Owner's full grant set for 30 minutes. They can then turn that into 30 days of persistent Owner rights through role delegation.
3. **RLS is probably inert in deployed environments (Critical, deployment-dependent — ISO-1).** No table uses `FORCE ROW LEVEL SECURITY`. The Docker image and `scripts/dev-up.sh` run Flyway as the application's own datasource user, which makes that user the table owner, and Postgres skips RLS for owners. Only the test harness migrates as a superuser and connects as a non-owner, so the tests hide the problem.
4. **Privilege escalation through staff role assignment (High — AUTHZ-3).** `PATCH /v1/staff/{id}` and invites accept any `roleId`, the built-in Owner role included, without the "no grant beyond your own" check that the role builder enforces. Role changes are not audited.
5. **Weak brute-force protection and credential handling on several authentication paths (High — AUTH-6, AUTH-5, AUTH-4, AUTH-7, DP-2).**
   - TOTP verification has no attempt limit (AUTH-6).
   - The per-IP limit trusts a client-supplied `X-Forwarded-For` header (AUTH-5).
   - PINs are 4–6 digits (AUTH-4).
   - Owner logins and platform-operator logins (including `super_admin`) can complete with a PIN only (AUTH-7).
   - OTPs and PIN-reset tokens are written to the application log by default (DP-2).
6. **Clinical PHI is not scoped inside a tenant (High — AUTHZ-2, AUD-1).** The "own patients only" doctor scope, the dual-verification gate and the consent-withdrawal gate all exist only on `/v1/patients/**`. Every `/v1/clinical/**` read returns full history for any patient ID in the tenant. PHI reads are not audited at all.
7. **Consent, residency and key management are missing (High, design gaps — ISO-2, DP-1, DP-3, DP-4).** The system has:
   - no affirmative consent artefact (only an opt-out withdrawal marker),
   - no cross-hospital sharing path except an RLS "brand visibility scope" that silently widens visibility to sibling facilities without consent, expiry or audit (ISO-2),
   - one shared database for India and KSA tenants, with no region for the UAE or Qatar (DP-3),
   - one global AES key that is not managed by a KMS (DP-1),
   - no data-subject-rights, retention or backup machinery (DP-4).

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
| **Platform audit console** `search_audit_log()` (`V16`, `V85`) | `SECURITY DEFINER`, reads across tenants, including `before`/`after` JSON. | Legitimate but broad. The function is `REVOKE ALL ... FROM PUBLIC` and pinned to `search_path = public` (`V16:23-26`, `V85:24-25,45`), so only the app role can call it, and the controller gates it on `audit_compliance:view` (`AuditSearchController.java:27`). The residual concern is that any operator holding that one surface grant (`super_admin`, `sre`, `compliance_dpo` — `PlatformPermissions.java:43`) can read the full before/after JSON of every tenant's audited actions with no per-tenant consent or scoping, and the search `limit` is caller-supplied with no server clamp (`AuditSearchController.java:46`), so a single call can page very large result sets. Each read is itself audited per tenant on export (`AuditSearchService.java:104-117`) but **not** on interactive search. |

### 3.5 Isolation test coverage and connection-pool context

**What the isolation suite covers.** `support/CrossTenantIsolationApiTest.java` (161 lines) seeds two fully separate tenants with validly-signed JWTs and asserts one tenant's staff cannot read the other's patients (`:26-45`), patient list (`:47-64`), staff (`:66-83`), appointments (`:85-112`), note templates or vaccine records (`:116-160`). The last test is the only one that reaches past the API to query the table directly under the other tenant's RLS context (`:147-159`), so it is the only one that actually exercises the RLS policy as opposed to the app-level `tenant_id = ?` predicate. `platform/CrossFacilityVisibilityTest.java` (104 lines) covers the brand/owner visibility scope: default FACILITY isolation (`:34-45`), BRAND widening within a brand (`:47-59`), no leak across unrelated brands (`:61-74`), TENANT scope spanning an owner's brands (`:76-88`) and live scope change (`:90-103`).

**What is not covered.**
- The suite tests **five** of the ~68 RLS-protected tables through the API (patients, staff, appointments, note_templates, vaccine_records) and only **two** at the RLS layer directly. The other clinical and financial tables (prescriptions, clinical_notes, vitals, allergies, invoices, payments, clinical_documents, …) are not individually asserted, so a missing or malformed `tenant_id` predicate on any of them would not be caught by a test.
- **The tests run on a different topology than production (see §3.3).** `ApiTestBase.bootstrapAppRole()` migrates as a superuser with Flyway disabled, then connects as a non-owner `nabd_app` role that is only `GRANT`ed DML (`:93-114`, `spring.flyway.enabled=false` at `:122`). This is the one configuration in which RLS is actually in force. Every deployment path that lets Flyway migrate as the app role (Docker `Dockerfile:22`, `scripts/dev-up.sh:14,25`) makes that role the table owner, and Postgres skips RLS for owners because no table sets `FORCE ROW LEVEL SECURITY`. The tests therefore *prove RLS works* on a topology the product may not deploy — a false sense of safety, already flagged as Critical in §3.3 (finding ISO-1).
- Several isolation tests carry a comment that they are "written, NOT executed: no Docker/Postgres in the authoring environment" (`CrossTenantIsolationApiTest.java:114`). Treat their pass/fail status as **unverified** until run in CI against real Postgres; Testcontainers 1.19.8 is reported incompatible with Docker Engine 29 (see §9), which may be why.
- **Tenant tables with no RLS policy (suspected data-exposure surface).** A migration scan found seven tenant-scoped `public` tables carrying `tenant_id` but no RLS policy: `login_attempts` (holds email, IP, staff_id — `V1:132-140`), `tenant_lifecycle_events`, `workflow_definitions` (tenant_id nullable, platform-authored rows are tenant-wide by design — `V40:7-15`) and `package_eligible_doctors` (reached only through the RLS-protected `packages` by FK — `V27:58-62`). For these the **only** isolation control is the app-level `tenant_id = ?` predicate; if that predicate is ever omitted there is no RLS backstop. `login_attempts` is the most sensitive: it is queried by staff_id/IP without a tenant predicate on some paths (`AuthRepository.java:241-266`), which is correct for rate-limiting but means the table is a cross-tenant store of authentication metadata with no row security.

**Connection-pool / `SET LOCAL` context-leak analysis.** `TenantContext.set()` uses `set_config('app.tenant_id', ?, true)` — the `true` makes it transaction-local, so the value is discarded at commit/rollback and cannot leak to the next borrower of a pooled connection (`TenantContext.java:34-47`). Every caller sets it inside an `@Transactional` method; a scan for `tenantContext.set(` outside a transactional boundary found only false positives (private helpers inside already-`@Transactional` public methods, and class-level `@Transactional` services such as `ProvisioningStepRunner`, `OutboxEventProcessor.runHandler` at `:35-45`). No `REQUIRES_NEW`, `@Async` or parallel stream was found that could run a query on a connection whose GUC was set by a different transaction. When the GUC is absent the `current_setting('app.tenant_id', true)::uuid` cast raises, so a policy evaluated with no context **fails closed** (denies), not open. This is a genuine strength. The one caveat is `app.accessible_tenant_ids` (§3.4): it is recomputed on every `set()` from the brand's *current* scope, which is correct for liveness but means a brand-scope change takes effect mid-session with no re-authentication and no audit of the widening.

---

## 4. Authentication

### 4.1 JWT signing, key management and rotation

- **Algorithm.** RS256 over a 2048-bit RSA keypair (`JwtConfig.java:129-152`). Asymmetric signing is appropriate. Nimbus is configured with an explicit public-key decoder (`:150-151`), so the `alg`/`jwk`-header confusion classes do not apply.
- **Keys are generated fresh on every boot (`JwtConfig.java:128-133`) and never persisted.** The class comment acknowledges this ("breaks refresh-token continuity across restarts/replicas … Swap for a persisted keypair loaded from Vault/KMS (NB-009)"). **Impact:** every restart or horizontal scale-out invalidates all live access tokens (users bounced to login), and with more than one instance, tokens signed by instance A are rejected by instance B — the system is effectively single-instance. There is no key rotation mechanism, no `kid`-based key set for overlap, and no JWKS endpoint. **Fix:** load a persisted keypair from a KMS/Vault, publish a rotating JWKS with overlapping `kid`s, and rotate on a schedule (NB-009/NB-283). **[Finding AUTH-1, High]**
- **One key and one issuer sign every token type.** Access, refresh-less challenge (`mfa_pending`), `mfa_setup_required`, `step_up`, owner `owner_pending_workspace` and platform-operator tokens are all signed with the same key and issuer `https://api.nabd.health` (`AuthService.java:62`, `OwnerService.java:48`, `PlatformAuthService.java:48`). The resource server accepts any of them as a bearer credential and distinguishes them only by a `purpose`/`sid` claim checked *inside each handler*, not at the filter. This is a token-confusion surface — see finding AUTH-2 below and the §5 object-level notes.

### 4.2 Token TTLs, storage and refresh

- Access token TTL **15 minutes**, refresh token TTL **30 days** (`application.yml:212-213`, `AuthService.java:576,601`). Challenge/step-up tokens are 5 minutes (`:214`, `:280`).
- **Frontend stores both tokens in `localStorage` and the clinic app never refreshes** (confirmed by the frontend review, `04-frontend-review.md:125-129`; `app/login/page.tsx:45-46`). Only the platform console refreshes (`platformApi.ts:22-49`). **Impact:** (a) `localStorage` is readable by any XSS, a malicious dependency or a browser extension, and the 30-day refresh token there grants persistent account takeover with PHI access; the app/API are cross-site (Vercel ↔ Render) and there is no CSP (§9); (b) staff are bounced to login ~15 minutes after sign-in mid-task, creating pressure to lengthen the access TTL, which weakens the blast-radius story. **Fix:** move the refresh token to an `httpOnly; Secure; SameSite=Strict` cookie behind a same-origin BFF, keep only a short-lived access token in memory, add single-flight refresh. **[Finding AUTH-3, High]** (frontend-owned; cross-referenced from the frontend review's most-serious finding).
- Refresh tokens are 256-bit random values stored only as SHA-256 (`OpaqueTokens.java:18-21`, `AuthRepository.java:268-275`). Good.

### 4.3 Refresh rotation and reuse detection

Rotation is correct: on refresh the presented session is revoked and a new one minted in the same family (`AuthService.java:458-460`); presenting an already-revoked token revokes the **whole family** (`:442-447`), and the revocation write survives the thrown exception via `noRollbackFor` (`:435`). Platform operators mirror this (`PlatformAuthService.java:124-147`). This is a genuine strength (already listed S4). One gap: reuse detection relies on the family row still existing; there is no absolute cap on family age beyond the 30-day refresh TTL, and a stolen-but-never-reused refresh token remains valid for the full 30 days.

### 4.4 PIN login brute-force protection and lockout

- **PINs are 4–6 digits** (`LoginRequest.java:10`, regex `^[0-9]{4,6}$`; same for owner, operator, unlock, invite-accept, pin-reset). A 4-digit PIN is 10,000 values. **Impact:** low-entropy credential; combined with the per-account lockout it is defensible for interactive use but weak if the lockout can be evaded. **Fix:** require 6 digits minimum, or raise to an alphanumeric passphrase for Owner/operator accounts. **[Finding AUTH-4, Medium]**
- **Account lockout** counts failed attempts since the last success and applies exponential backoff capped at 30 min (`AuthService.java:503-520`). It deliberately does **not** record a fresh failure while already locked, which correctly prevents a self-renewing permanent-lockout DoS (`:497-502`) — a real strength, mirrored in owner and operator services.
- **Per-IP rate limit trusts a client-supplied `X-Forwarded-For`.** `RequestMeta.clientIp()` returns the first value of the `X-Forwarded-For` header when present (`RequestMeta.java:12-14`), and the per-IP limit (`AuthService.java:490-495`, 20/min) counts against that value. **Impact:** an attacker can rotate a forged `X-Forwarded-For` on each request to defeat the per-IP throttle entirely; the per-account lockout still applies, but the IP limit — the only control on endpoints that don't resolve to one account, such as OTP/PIN-reset probing — is bypassable. **Fix:** derive the client IP from the trusted proxy's rightmost hop (configure `ForwardedHeaderFilter`/trusted-proxy list), never from the raw header. **[Finding AUTH-5, High]**
- **TOTP verification has no attempt limit.** `verifyMfa`, `issueStepUpToken` and `confirmMfa` check the code with no per-account counter and a ±1-step (±30s) window (`AuthService.java:229-257,264-291,310-329`; `TotpService.java:24,48-56`). The 5-minute challenge TTL bounds a single challenge, but a fresh login re-issues a challenge, so there is no global cap on TOTP guesses against a known PIN. **Impact:** a 6-digit TOTP with a 3-window acceptance is brute-forceable given enough challenges. **Fix:** count failed TOTP attempts per staff/operator and lock on threshold, same as PIN lockout. **[Finding AUTH-6, High]**

### 4.5 MFA, token-type confusion and the challenge-token surface

- **Token-type confusion on the MFA challenge token (Critical).** The `mfa_pending` and `mfa_setup_required` tokens are signed with the production key and carry `sub` + `tenantId` but no `sid` (`AuthService.java:147-156,522-535`). Because the resource server accepts any validly-signed token as a bearer credential, these half-authenticated tokens are accepted by `@AuthenticationPrincipal`-only endpoints that do not re-check `purpose`. `POST /v1/auth/mfa/enroll` and `/mfa/confirm` are explicitly designed to accept the `mfa_setup_required` token (`AuthService.java:294-308`), and `POST /v1/auth/break-glass/activate` reads only `sub`+`tenantId` (`:376-388`) with no `purpose` check and no `@PreAuthorize`. **Impact:** a party holding only a victim's clinic code, email and PIN — i.e. who has passed the first factor but not TOTP — holds an `mfa_pending` token that can be presented to break-glass activation (yielding a full Owner-grant access/refresh pair, §4.6) or to `/mfa/enroll` (overwriting the victim's pending TOTP secret). Either path defeats the second factor. **Fix:** (a) stop accepting non-access tokens as resource-server credentials — give challenge/step-up/pending tokens a distinct audience or a dedicated decoder, and reject them on all `/v1/**` business endpoints; (b) have every handler that consumes a special-purpose token assert its `purpose` claim (break-glass, enroll, confirm, unlock all currently rely on the token merely being signed). **[Finding AUTH-2, Critical]**
- **MFA secret encryption.** TOTP secrets are 160-bit, stored AES-256-GCM encrypted with a random 96-bit IV (`TotpService.java:28-32`, `AesGcmCipher.java:33-47`), and `mfa_enabled` is flipped only after a confirming code (`AuthRepository.java:113-120`). `APP_CRYPTO_KEY` has no default; the app fails to boot without it (`application.yml:233-237`, `AesGcmCipher.java:29-31`). Good. **Caveats:** the key is a single global AES key, not per-tenant or per-region, and is not KMS-managed today (NB-009/NB-283 open); and the step-up token carries no `tenantId` (`AuthService.java:281-287`), so `StepUpVerifier` checks only `purpose` + `sub` (`StepUpVerifier.java:31-32`) — adequate but it means a step-up token is not bound to a session. **[key management → Finding DP-1, §8]**
- **MFA is enforced only by an explicit per-role `mfa_required` flag** (`roles.mfa_required`, `V31:9`; `AuthService.java:139-142`). This is a deliberate, defensible design (free-text role names are not a safe signal). But it means MFA enforcement for Owner and refund/discount holders depends on whoever builds the role setting the flag; there is no invariant forcing it on for the built-in Owner role or for any role holding `billing:refundDiscount`. Platform operators enforce MFA only if `mfa_enabled` is set per operator (`PlatformAuthService.java:91`) — there is **no** server-side requirement that `super_admin` has MFA. **Impact:** the highest-privilege accounts can be configured PIN-only. **Fix:** make MFA non-optional for the built-in Owner role, any role with refund/discount/approve authorities, and all platform operators. **[Finding AUTH-7, High]**

### 4.6 Break-glass (V31)

`POST /v1/auth/break-glass/activate` has **no `@PreAuthorize`** (confirmed by endpoint inventory; `AuthController.java:126-130`). Any authenticated staff member — any valid token carrying `sub`+`tenantId`, including the `mfa_pending` token of §4.5 — can activate it with a free-text reason and immediately receive a token pair into which the tenant's built-in **Owner** grant set is folded for 30 minutes (`AuthService.java:375-388,560-572`; `AuthRepository.insertBreakGlass`, `hasActiveBreakGlass`, `findBuiltInOwnerRole`). Activation is audited and listed to anyone with `staff:view` (`:384-386`, `AuthController.java:138-142`), but there is no approval, no notification channel, and no permission gate on activation itself. **Impact:** self-service privilege escalation to Owner for any staff account; and because break-glass yields full grants including `staff:edit`, the holder can then create a role delegation (§5) to persist Owner-equivalent rights for up to 30 days beyond the 30-minute window. **Fix:** gate activation behind a dedicated permission, require step-up MFA, require a second approver or out-of-band notification, and narrow the granted scope to read-only emergency access rather than full Owner write. **[Finding AUTH-8, Critical]**

### 4.7 Owner invite flow and shadow-staff

- Owner login is two-step: PIN → `owner_pending_workspace` token (5 min) → clinic selection mints a normal staff-shaped token (`OwnerService.java:70-146`). Clinic selection is data-scoped by `ownsClinic` (`OwnerRepository.java:136-144`) and returns 404 (not 403) for a clinic the owner doesn't own. Good.
- **Owner can log in PIN-only.** `OwnerService.login` performs no MFA step at all (`:70-91`); there is no `mfa_enabled` concept on the `owners` table. **Impact:** the most privileged clinic identity is single-factor. **Fix:** require MFA enrolment for owner accounts. **[rolled into Finding AUTH-7]**
- **Shadow staff gets the built-in full-access role.** On first workspace entry a staff row is created with the clinic's `built_in = true` role and `pin_hash` left NULL forever (`OwnerRepository.java:154-176`). The NULL PIN correctly blocks normal login (`AuthService.java:115`). This is sound, but it means every owner silently holds the full Owner grant set in each clinic with no per-clinic role assignment visible in the staff list as a normal role.
- Invite tokens are 256-bit, stored as SHA-256, 72-hour expiry, single-use (`OwnerService.java:100-120`, `OwnerRepository.java:58-73`). The staff invite path mirrors this (`StaffService.java:83-96`). Both are revealed once via the provisioning job response (`ProvisioningStepRunner.java:207-258`). Good. **Minor:** the invite accept endpoints are `permitAll` by design (token is the credential), and the raw owner-account invite token is also emailed in clear in the body (`ProvisioningStepRunner.java:215-218`) — acceptable for an invite link but note that email is not a confidential channel. 

### 4.8 Recovery and PIN reset

- MFA recovery codes: 8 codes, stored as SHA-256, single-use via atomic check-and-consume (`AuthService.java:321-328`, `AuthRepository.java:122-133`). **Gap:** there is no endpoint that *consumes* a recovery code to complete MFA (`consumeRecoveryCode` exists in the repository but has no caller) — so a user who loses their authenticator currently has no self-service recovery path, and there is no admin MFA-reset flow either. **Fix:** wire recovery-code verification into the MFA challenge, and add an audited admin reset. **[Finding AUTH-9, Medium]**
- PIN reset: no-enumeration (always 202), 256-bit token stored hashed, 15-min expiry, atomic consume-and-set (`AuthService.java:335-371`, `AuthRepository.java:137-149`). Good. **But** the reset token is delivered over WhatsApp via the same `otpSender`, which logs the raw token when `WHATSAPP_LOG_OTP=true` — the default (`application.yml:232`, `TemplateWhatsAppOtpSender.java:25-27`). See §8. **The OTP/PIN-reset code is written to the application log by default** — a reset token in the logs is a password-equivalent. **[Finding DP-2, High]**

### 4.9 Session revocation and idle lock

- Server-side session liveness is checked on **every** staff request by a wrapping decoder (`SessionRevocationJwtDecoder.java:184-193`, `SessionLivenessChecker.java:220-224`), so a revoked session is denied on the next request, not at the 15-min token expiry. Suspending a staff member revokes all their sessions (`StaffService.java:149-155`). Strong (S5). **Note:** the liveness check applies only to tokens carrying both `sid` and `tenantId`; challenge/step-up/pending/operator tokens pass through unchecked (`:187-191`) — which is the same surface exploited in AUTH-2.
- **Tenant suspension does not revoke staff sessions.** `TenantLifecycleService.transition` to `suspended`/`offboarded` updates `tenants.status` and logs an event (`TenantLifecycleService.java:44-56`) but does not revoke that tenant's sessions, and `SessionLivenessChecker` checks only the session row, not tenant status. Login is blocked for a suspended tenant (`AuthService.java:100-102`) but **already-issued access tokens keep working until their 15-min expiry, and refresh is only blocked indirectly** (refresh re-mints from a still-active session; it does not re-check tenant status — `AuthService.java:453-460`). **Impact:** a suspended/offboarded tenant's staff can continue to act for up to 15 minutes, and arguably refresh indefinitely. **Fix:** revoke all sessions on suspension/offboarding and re-check tenant status on refresh. **[Finding AUTH-10, Medium]**
- Idle lock is client-only (5 min; `IdleLockGuard.tsx`), PIN-verified against the live session without re-login (`AuthService.unlock:413-421`). Reasonable for not losing draft notes, but it is pure UI — the access/refresh tokens are untouched, so a stolen device with the tokens in `localStorage` is not protected by the lock. Cross-referenced to AUTH-3.

---

## 5. Authorization

### 5.1 Model and enforcement mechanism

Clinic RBAC is a per-tenant set of roles whose `grants` is a JSON array of `ModuleGrant` rows (module + view/create/edit/delete/approve/refundDiscount/export booleans — `ModuleGrant.java:31-40`). At login the grants are flattened to `module:action` strings and placed in the JWT `permissions` claim (`GrantsFlattener.java:12-26`, `AuthService.java:542,586`). Spring maps that claim directly to authorities with no prefix (`SecurityConfig.java:43-50`), and `@PreAuthorize("hasAuthority('module:action')")` on controller methods is the enforcement point (`@EnableMethodSecurity`, `:29`). Platform operators use a separate fixed role→surface matrix (`PlatformPermissions.java:29-63`), not user-configurable grants. Delegations (V30) and break-glass (V31) fold extra grants into the token at mint time (`AuthService.java:547-572`).

This is a clean, deny-by-default design *where the annotation is present*. The weaknesses are (a) enforcement is permission-level only, not object-level (§5.3); (b) a handful of sensitive endpoints have no annotation (§5.2); (c) there is no server-side **endpoint registry** — the Security Controls sheet lists NB-050 "deny-by-default server-side authorisation on every endpoint" as an unverified go-live blocker, and no DB-backed endpoint→permission registry exists (V2 is staff/role columns, not an endpoint table). Enforcement is the `@PreAuthorize` annotations plus the OpenAPI `x-required-permission` values, which are documentation, not enforcement.

### 5.2 Endpoints lacking a permission check

Inventory of all 264 `@*Mapping` methods cross-checked against `@PreAuthorize` (class- and method-level): 234 carry an authority check; **30 do not**. Of those 30, 28 are intentional: the auth/login/bootstrap flows and the `permitAll` endpoints in `SecurityConfig.java:76-91` (login, refresh, OTP, mfa/verify, pin-reset, invite-accept, owner login, platform login, WhatsApp webhook) plus `authenticated`-only session-management endpoints (`/auth/logout`, `/auth/sessions`, `/platform/auth/me`, `/owners/me/workspaces`, `/owners/workspaces/select`) where the handler scopes to the caller's own `sub`. The two that matter:

- `POST /v1/auth/break-glass/activate` — no permission check; self-service Owner elevation (AUTH-8, Critical).
- `POST /v1/auth/break-glass/{id}/deactivate` — no permission check; a caller can only deactivate within their own tenant context (`AuthService.java:390-397`), low risk, but still unauthenticated-by-role.

Also note `POST /v1/support/tickets` (`TicketController.java:28`) is `authenticated`-only by design (any staff can raise a ticket), which is acceptable.

**Cross-check against OpenAPI:** one mismatch — `GET /v1/clinical/drugs/search` is documented as `clinical:view (or clinical:edit)` and the code accepts either (`PrescriptionController.java:74`), which is consistent. 139 code endpoints have no `x-required-permission` entry in the spec at all (the spec documents 131 operations vs 264 in code), so the OpenAPI permission catalogue is **stale/incomplete** and cannot be used as the authorization source of truth. **[Finding AUTHZ-1, Medium]**

### 5.3 Object-level access (BOLA/IDOR)

**Resources are fetched by id with tenant/facility scoping enforced in SQL for most paths, but there is no object-level scoping *within* a tenant for clinical records.**

- Tenant scoping in SQL: patient/clinical/financial repositories carry explicit `WHERE tenant_id = ?` predicates (≈340 of them) on top of RLS (S1). A scan for id-lookups lacking a tenant predicate found only: platform/master-schema tables (cross-tenant by design, gated by operator surface grants), and bootstrap `SECURITY DEFINER` lookups (`find_session_by_token_hash`, `find_staff_by_invite_token_hash`) where possession of the opaque token is the authorization. No clinic-facing repository was found fetching a tenant-scoped row by bare id without either a `tenant_id` predicate or RLS. Good.
- **The "own patients only" doctor scope, the dual-channel verification gate and the consent-withdrawal gate exist only in the Patients and Reports modules.** `requireVerifiedAndResolveScope` and the `own_patients_only` row-scoping are applied in `PatientService` (`:426-434`) and `ReportsService` (`:146-164`), and the consent gate is a SQL `NOT EXISTS` only in `PatientRepository` (`:28-32`). The clinical modules — `TimelineController`, `ClinicalDocumentController`, `NoteController`, `PrescriptionController`, `VitalsController`, `AllergyController`, etc. — gate on `clinical:view`/`clinical:edit` **only** and fetch by `patientId`/`queueEntryId` with tenant scoping but **no doctor-ownership scope, no verification gate and no consent check**. **Impact:** any staff member holding `clinical:view` can read the full clinical history (timeline, notes, prescriptions, documents, vitals, allergies) of **any** patient in the tenant, including patients who are not theirs and patients whose `data_processing` consent has been withdrawn (the withdrawal removes them from `/v1/patients/**` reads but not from `/v1/clinical/**`). PHI reads on these endpoints are also not audited (§7). **Fix:** apply the same consent gate, verification gate and (where the product requires it) doctor-ownership scope across all `/v1/clinical/**` read paths, enforced in SQL; audit every PHI read. **[Finding AUTHZ-2, High]**
- **Clinical write authorship is the visit's doctor OR anyone with `clinical:approve`.** Note upsert/sign writes against the queue entry's recorded `doctor_id` regardless of caller (`NoteService.java:51-69`); prescription sign/decision allows the visit doctor or a `clinical:approve` holder (`PrescriptionService.java:68-70,120-124`). This is a documented maker-checker design, not a defect, but combined with AUTHZ-2 it means `clinical:approve` is effectively a tenant-wide clinical write grant.

### 5.4 Privilege escalation through role/staff assignment

- **Role create/update enforces "no grant beyond your own"** (`RoleService.requireNoPrivilegeEscalation:88-97`) and blocks editing built-in roles (`:77-79`). Delegation create also runs the same check against the delegated role's grants and caps the window at 30 days (`:101-115`). Good.
- **But `PATCH /v1/staff/{id}` and the invite flow accept any `roleId` with no "no grant beyond your own" check.** `StaffService.patch` validates only that the role exists in the tenant (`:116-118`) then assigns it (`:124`); `invite` does the same (`:85-96`). The built-in Owner role is a valid `roleId`. **Impact:** a staff member holding `staff:edit`/`staff:create` (which does not imply Owner-level grants) can assign the built-in Owner role — or any role richer than their own — to another account (or to a freshly invited account they control), escalating privilege. The role-builder's escalation guard is bypassed because assignment is a different code path. Role/scope changes via `patch` are also **not audited** (only `professionalRegistrationNo` changes are — `StaffService.java:127-146`). **Fix:** apply the same "assigned role's grants ⊆ caller's grants" check in `patch` and `invite`; audit every role/scope change. **[Finding AUTHZ-3, High]**

### 5.5 Department / brand visibility scope

- Department assignment drives queue/checkout status sequences (`QueueService.java:141`, `CheckoutService.java:116`) but is not a data-isolation boundary — there is no "staff only see their department's patients" scope in SQL. Department is operational routing, not access control.
- Brand visibility scope (`brands.data_visibility_scope`) widens RLS across sibling facilities (§3.4). It is latent (no API sets it today, app-level predicates neutralise it for most queries) but is a **consent-free cross-facility data-sharing mechanism** with no `WITH CHECK` (so it would also permit cross-facility *writes*), no expiry and no audit. Flagged as finding ISO-2 in §3.4.

### 5.6 UI-only gating

The Security Controls sheet requires "restricted elements absent from the UI, never disabled" (NB-054) and "field-level restrictions applied in the API response, not merely hidden in the UI" (NB-052). Field-level restriction is enforced server-side for the one case that exists — the patient financial field is nulled in the response when the caller lacks the `financial` field-grant (`PatientService.java:475-476`). But `field_grants` is consulted in **only** that one place; no other response applies it. Given AUTHZ-2 (clinical reads unscoped), the field-level-restriction control (NB-052) is substantially unmet beyond the single financial field. **[Finding AUTHZ-4, Medium]**

---

## 6. Consent and data sharing (current state)

This section records only what exists today. The target design belongs in `docs/engineering/07-consent-and-data-sharing-architecture.md` (being written separately); it is not reproduced here.

- **Consent artefact.** A single `consents` table (`V5:41-54`) with `consent_type ∈ {treatment, data_processing, messaging, guardian_access}` (V28 added the last), `granted_at`, `withdrawn_at`, `version`. It is RLS-scoped per tenant. There is **no consent capture at registration** — only withdrawal is modelled (the V5 header says so explicitly); rows are created on withdrawal (`PatientRepository.java:262`) or on guardian grant. So "consent" today is effectively an opt-out marker, not a versioned, affirmative, auditable artefact.
- **Enforcement.** A withdrawn `data_processing` consent is a hard SQL gate on `/v1/patients/**` reads (`PatientRepository.java:28-32`), satisfying NB-053 *for that module only* — it does **not** gate `/v1/clinical/**` (AUTHZ-2). Messaging consent is default-deny (no row = blocked) in `WhatsAppMessageRepository.java:43-52` (S14).
- **Guardians (V28).** Guardian/proxy relationship lives on `patients.guardian_id`; `guardian_access` consent is written atomically with the guardian change by `PatientService` (guardian grant/reassign/revoke are audited — actions `patient.guardian_grant/_reassign/_revoke`). The generic withdraw endpoint deliberately excludes `guardian_access` to avoid an inconsistent state (`V28:14-20`). An age-18 review worklist exists (`GET /v1/patients/guardian-reviews-due`).
- **Support access (V15).** A platform operator's time-boxed (60-min), reason-logged, audited read of one tenant's **redacted** patient view (`SupportAccessService.java:48-104`; clinical fields never returned — `PatientViewResponse` carries demographics only). "Consent" here is the operator's own logged request, not a tenant-side approval — the service comment acknowledges no tenant/owner approval step exists yet (`:24-31`). Each view is audited (`patient.support_view`).
- **Cross-facility sharing** today is only the brand visibility scope (§3.4/ISO-2): wholesale, consent-free, no expiry, no audit. There is no consent artefact governing cross-facility or cross-hospital data flow.

**Target design:** see `docs/engineering/07-consent-and-data-sharing-architecture.md`.

---

## 7. Audit logging

- **Completeness.** `audit_log` (`V14`) captures who/what/when/before/after and is written via `AuditService.record` from 20 services. Audited actions include patient update, allergy/vitals corrections, prescription signing and overrides, note amendments, document issue, day-close, refunds, discounts, delegations, break-glass, support view and WhatsApp send (enumerated from the code). **Gap: PHI *reads* are not audited.** The only read that is logged is the platform support view (`patient.support_view`) and the audit-export itself. Ordinary clinical reads (`/v1/clinical/**`, patient detail, timeline) write no audit row. The Security Controls sheet (NB-293) expects SIEM alerting on "mass export" and the privacy packs require access logging of PHI; today a doctor or any `clinical:view` holder reading any patient's full history leaves no trace. **[Finding AUD-1, High]** Also, staff role/scope changes via `PATCH /v1/staff/{id}` are not audited (AUTHZ-3), and interactive cross-tenant audit *searches* by operators are not audited (only exports are).
- **Tamper evidence.** Two independent defences (`V14:1-18`): (1) `BEFORE UPDATE/DELETE/TRUNCATE` triggers `RAISE` unconditionally (`:81-92`), so application-level tampering fails regardless of grants; (2) a per-tenant SHA-256 hash chain — each row's hash covers its content plus the previous row's hash for that tenant (`audit_row_hash`, `audit_log_chain`, `:48-79`), recomputable by `verify_audit_chain` (`:97-113`, wrapped by `AuditService.verify`). This is a genuine strength (S8). **Caveats:** (a) the chain's `SELECT ... FOR UPDATE` of the previous row is itself RLS-scoped, which is correct *only if the app connects as a non-owner* — if RLS is inert (§3.3 ISO-1), the chain query and the immutability triggers both still fire (triggers are grant-independent), but a table-owning app role could `ALTER TABLE audit_log DISABLE TRIGGER` and then edit history; the chain would detect it on re-verification, but nothing runs `verify_audit_chain` on a schedule; (b) there is no append of a periodic signed checkpoint to external storage, so a sufficiently privileged attacker who can recompute and rewrite the whole chain for a tenant would not be detected. **Fix:** run chain verification on a schedule with alerting; anchor periodic checkpoints off-box; ensure the app role is a non-owner (ISO-1). **[Finding AUD-2, Medium]**
- **Retention.** No retention or partitioning policy exists for `audit_log` (no purge job — `V1:110` notes the same for sessions). The privacy packs require 3-year audit retention (NB-078) with per-region clocks (NB-247); none is implemented. **[Finding AUD-3, Medium]**

---

## 8. Data protection

- **Field-level encryption.** National ID and TOTP secrets are AES-256-GCM encrypted (`AesGcmCipher`, `V3:26`, `V7:27`); national ID is never returned by the API (S7). Patient name, phone, DOB, address are stored in clear (relying on RLS + at-rest disk encryption, which is deployment-dependent and unverified here). The Security Controls sheet scopes column-level encryption to national identifiers (NB-285), so this matches the stated scope, but DOB/phone/address are PHI under all four regimes and have no field encryption.
- **Key management (DP-1, High).** One global `APP_CRYPTO_KEY` (`application.yml:233-237`) encrypts every tenant's secrets across both IN and KSA regions. There is no per-tenant or per-region key, no KMS, and no rotation runbook (the code comments defer all of this to NB-009/NB-283, both open). The JWT signing key is ephemeral (AUTH-1). **Impact:** a single key compromise exposes every tenant's MFA secrets and national IDs across regions; rotation is impossible without re-encrypting in place. The Cryptography controls (NB-283 region-scoped KMS with per-tenant keys) are go-live blockers and unmet. **Fix:** region-scoped KMS, per-tenant data keys, documented rotation. **[Finding DP-1, High]**
- **PHI / secrets in logs (DP-2, High).** `WHATSAPP_LOG_OTP` defaults to `true` (`application.yml:232`), so every login OTP **and every PIN-reset token** is written to the application log (`TemplateWhatsAppOtpSender.java:25-27`) — both are credential-equivalent. `application.yml:196-199` acknowledges there is no PHI-redaction scanner (NB-158/NB-304 open) and logging is "hand-disciplined." A scan of log statements found IDs rather than names/phones in the hot paths, and the RFC 7807 handler logs only title/detail/traceId (`GlobalExceptionHandler.java:62`), so routine PHI leakage into logs is low — but the OTP/reset-token default is a clear defect, and break-glass/various `reason` free-text fields are logged (`AuthService.java:386`) and could contain PHI entered by staff. **Fix:** default `WHATSAPP_LOG_OTP=false`; never log reset tokens; add the NB-304 PHI-redaction scanner before real data. **[Finding DP-2, High]**
- **Data residency per country (DP-3, High).** `tenants.region` is constrained to `IN` or `KSA` only (`V1:23`) — there is no region for the UAE or Qatar, two of the five stated target jurisdictions. More importantly, **one database holds both IN and KSA tenants**; isolation between them is RLS only, not a physical/regional boundary. The residency controls (NB-271 region-locked-at-provisioning, no cross-region replication; NB-283 region-scoped KMS) are go-live blockers and unmet. **Impact:** KSA PDPL and (prospective) UAE/Qatar residency rules require in-country storage; a shared cross-region database cannot satisfy them. **Fix:** per-region deployments with region pinned irreversibly at provisioning, and add UAE/QA regions. **[Finding DP-3, High]**
- **Backups / data-subject rights vs retention.** No backup, PITR, retention, legal-hold or erasure mechanism exists in the codebase (no purge jobs, no DSR engine; `reports/retention` is an aggregate dashboard only). Patient `delete` is a soft status flip / merge, not erasure. The DSR, retention and backup controls (NB-078/244/245/247/306) are go-live blockers and unmet. Medical-record retention vs right-to-erasure tension (erasure must yield to statutory retention with legal hold) is unmodelled. **[Finding DP-4, Medium]** (design gap, large).

---

## 9. Web / API hygiene

- **CORS.** Explicit origin allow-list, defaulting to localhost + the Vercel origin, overridable by env (`SecurityConfig.java:53-65`, `application.yml:201-206`). Methods/headers are restricted; credentials are not enabled (bearer-token model). Reasonable.
- **CSRF.** Disabled (`SecurityConfig.java:72`), correct for a stateless bearer-token API with no cookies — **but** this assumes the frontend never moves the refresh token into a cookie. If AUTH-3's recommended cookie-based BFF is adopted, CSRF protection must be added for cookie-authenticated mutations. Noted, not a current finding.
- **Security headers / CSP (WEB-1, High).** `next.config.ts` is empty (`frontend/next.config.ts:3-5`) — **no CSP, HSTS, X-Frame-Options, Referrer-Policy or Permissions-Policy**. Combined with tokens in `localStorage` (AUTH-3), the absence of CSP means any XSS or malicious dependency can exfiltrate both tokens. NB-291 (CSP in enforce mode, no inline script) is a go-live blocker and unmet. The backend sets no security headers either. **Fix:** strict CSP, HSTS preload, frame-deny, at the edge/Next middleware. **[Finding WEB-1, High]**
- **Rate limiting (WEB-2, High).** The only rate limit is the per-IP login limit, which trusts `X-Forwarded-For` (AUTH-5) and applies to auth paths only (`AuthService.java:490-495`). There is no per-tenant, per-user or per-phone-number limit, and no limit at all on business endpoints (the audit-search `limit` is caller-controlled with no clamp — AUTH/§3.4). NB-288 (rate limiting per IP/tenant/user/phone) is a go-live blocker and largely unmet. **[Finding WEB-2, High]**
- **SQL construction.** No user input is concatenated into SQL; dynamic `SET`/`WHERE` builders use fixed column literals with bound parameters (`PatientRepository.java:223-231`, the clinical `EncounterListRepository`/`TimelineRepository`/`AppointmentRepository` builders append only constant clause fragments and bind all values), and audit-search `LIKE` input is escaped (`V85:39-40`). CSV exports prefix formula-trigger characters to prevent CSV injection (`AuditSearchService.java:160-176`). No SQL-injection finding. (S11 confirmed.)
- **File uploads for clinical documents (V54) (WEB-3, High).** `clinical_documents` stores only structured text fields (`body jsonb`) plus an issued snapshot (`header`); there is **no binary upload, no object store, no file bytes** anywhere in the backend (no `MultipartFile`, no S3/blob/bytea handling found) and no multipart size config. So the V54 "clinical documents" feature is text-only today. **Impact:** the file-upload security pipeline the controls require (NB-292: type-verification, AV scanning, signed short-lived URLs; NB-076: expiring access-logged links, never a file in open chat) is **entirely absent** — which is fine while no files are uploaded, but is a go-live blocker the moment any clinical-document or attachment upload ships. **[Finding WEB-3, High]** (design gap; blocks any future upload feature).
- **WhatsApp webhook signature verification.** HMAC-SHA256 over the **raw** body, constant-time compared, rejecting everything when the app secret is blank (`WhatsAppWebhookController.java:57-63,96-104`); the subscribe handshake constant-time-compares the verify token (`:47-54`). Correct (S9).
- **Secrets committed to the repo.** None found. The tracked `frontend/.env.production` holds only a public API URL; a pattern scan of the tree and of git history for private keys/cloud tokens/live secrets was clean (the only `DB_PASSWORD=nabd_app` in history is a local-dev default, not a production secret). `.gitignore` excludes `.env.dev`/`target`. `APP_CRYPTO_KEY` has no default and must be supplied at boot. Good (S16).
- **Dependency versions (WEB-4).** `next@16.3.1` (`frontend/package.json:14`) is flagged **critical** by `npm audit`; upgrade to `>= 16.4.2`. Testcontainers 1.19.8 is incompatible with Docker Engine 29, which means the Postgres-backed isolation/auth tests (the only ones that actually exercise RLS and lockout — §3.5) may not run in a current CI environment; the suite's own comments confirm some tests were authored but never executed. Backend uses Spring Boot 3.3.4 and BouncyCastle 1.78.1 (`backend/pom.xml:10,67-68`); no committed CVE scan / SBOM (NB-290 unmet). Logback (not log4j-core) is used (S16). **Fix:** upgrade Next to ≥16.4.2, align Testcontainers with the CI Docker engine so the RLS tests run, add SCA/SBOM to CI. **[Finding WEB-4, High]**

---

## 10. Findings

Severity reflects impact × likelihood **given the five-jurisdiction healthcare context and before any real patient data**. "Effort" is S (≤1 day), M (days), L (weeks+).

| ID | Sev | Area | Finding | Evidence | Impact | Fix | Effort |
|---|---|---|---|---|---|---|---|
| AUTH-2 | Critical | AuthN | Token-type confusion: challenge/pending/step-up tokens are accepted as bearer credentials; several handlers don't check `purpose` | `SecurityConfig.java:92-94`, `SessionRevocationJwtDecoder.java:187-191`, `AuthService.java:147-156,376-388,294-308` | A first-factor-only party can reach break-glass activation and MFA enroll with an `mfa_pending` token, bypassing the second factor | Give special-purpose tokens a distinct audience/decoder and reject on `/v1/**`; assert `purpose` in every handler | M |
| AUTH-8 | Critical | AuthZ | Break-glass activation has no permission check; any authenticated staff self-elevate to Owner grants for 30 min, extendable via delegation | `AuthController.java:126-130`, `AuthService.java:375-388,560-572` | Self-service privilege escalation to Owner; persistent via a 30-day delegation | Gate on a dedicated permission + step-up MFA + approver/notification; scope to read-only | M |
| ISO-1 | Critical (deployment-dependent) | Tenancy | No table uses `FORCE ROW LEVEL SECURITY`; deploy paths migrate as the app role, making it the table owner, so RLS is skipped | §3.3; `Dockerfile:22`, `scripts/dev-up.sh:14,25`, `application.yml:161-164`; tests differ (`ApiTestBase.java:93-122`) | Any missing `tenant_id` predicate becomes a cross-tenant breach; audit triggers can be disabled by the owner | Migrate as a separate owner role, connect as non-owner, or set `FORCE RLS` on every policy table; verify with `pg_class` query in §3.3 | M |
| AUTH-1 | High | AuthN | JWT signing keypair generated fresh per boot; no persistence, rotation or JWKS | `JwtConfig.java:128-152` | Restart/scale-out invalidates all tokens; effectively single-instance; no rotation | Persist keypair in KMS/Vault; rotating JWKS with overlapping `kid` | M |
| AUTH-3 | High | AuthN | Both tokens in `localStorage`; clinic app never refreshes the 15-min token | `04-frontend-review.md:125-129`; `login/page.tsx:45-46` | XSS/extension steals a 30-day refresh token → persistent PHI access; staff churn pushes for longer TTLs | Cookie-based BFF (`httpOnly;Secure;SameSite`); in-memory access token; single-flight refresh | L |
| AUTH-5 | High | AuthN | Per-IP rate limit trusts client-supplied `X-Forwarded-For` | `RequestMeta.java:12-14`, `AuthService.java:490-495` | IP throttle (the only control on OTP/reset probing) is bypassable by header rotation | Derive IP from trusted proxy hop; configure trusted-proxy list | S |
| AUTH-6 | High | AuthN | TOTP verification has no attempt limit | `AuthService.java:229-257,264-291`, `TotpService.java:48-56` | 6-digit TOTP brute-forceable across re-issued challenges | Per-account failed-TOTP counter + lockout | S |
| AUTH-7 | High | AuthN | MFA is not forced for Owner, refund/discount roles, or platform operators (incl. `super_admin`) | `AuthService.java:139-142`, `V31:9`, `PlatformAuthService.java:91`, `OwnerService.java:70-91` | Highest-privilege accounts can be PIN-only | Force MFA on built-in Owner, refund/discount/approve roles, and all operators | M |
| AUTHZ-2 | High | AuthZ | `/v1/clinical/**` reads are gated on `clinical:view` only — no doctor-ownership scope, no verification gate, no consent gate; PHI reads unaudited | `TimelineController.java:26`, `ClinicalDocumentController.java:39,59`, `NoteController.java`, vs `PatientService.java:426-434`, `PatientRepository.java:28-32` | Any `clinical:view` holder reads any patient's full history, incl. consent-withdrawn patients; no audit trail | Apply consent/verification/ownership gates in SQL across clinical reads; audit every PHI read | L |
| AUTHZ-3 | High | AuthZ | `PATCH /v1/staff/{id}` and invite accept any `roleId` (incl. built-in Owner) with no "no grant beyond your own" check; role changes unaudited | `StaffService.java:111-126,83-96` vs `RoleService.java:88-97` | A `staff:edit`/`staff:create` holder assigns Owner/richer role to an account they control → escalation | Enforce assigned-grants ⊆ caller-grants; audit role/scope changes | M |
| AUD-1 | High | Audit | PHI reads are not audited anywhere except the support view | §7; `AuditService` call sites | No trail of who read which patient; defeats SIEM mass-export detection (NB-293) and privacy access-logging | Audit clinical/patient reads; add read-volume alerting | M |
| DP-1 | High | Crypto | One global `APP_CRYPTO_KEY` across tenants and both regions; no KMS, no per-tenant/region key, no rotation; JWT key ephemeral | `application.yml:233-237`, `AesGcmCipher.java:29-31`, `JwtConfig.java` | Single key compromise exposes all tenants' MFA secrets + national IDs across regions; rotation impossible | Region-scoped KMS, per-tenant data keys, rotation runbook (NB-283) | L |
| DP-2 | High | Data | `WHATSAPP_LOG_OTP=true` by default logs every login OTP and PIN-reset token; no PHI-redaction scanner | `application.yml:232`, `TemplateWhatsAppOtpSender.java:25-27`, `application.yml:196-199` | Credential-equivalent values in application logs | Default off; never log reset tokens; add NB-304 redaction scanner | S |
| DP-3 | High | Residency | Region is `IN`/`KSA` only (no UAE/QA); one DB holds both regions, isolated by RLS only | `V1:23` | Cannot meet KSA/UAE/QA in-country storage; shared cross-region store | Per-region deploys, region pinned at provisioning, add UAE/QA (NB-271) | L |
| WEB-1 | High | Web | No CSP / HSTS / security headers (empty `next.config.ts`); backend sets none | `frontend/next.config.ts:3-5` | XSS can exfiltrate `localStorage` tokens; NB-291 unmet | Strict CSP + HSTS preload + frame-deny + Permissions-Policy | M |
| WEB-2 | High | Web | Rate limiting is auth-only and IP-forgeable; no per-tenant/user/phone limit; no business-endpoint limit; audit-search `limit` unclamped | `AuthService.java:490-495`, `AuditSearchController.java:46` | Credential/abuse/scraping unthrottled; NB-288 unmet | Multi-dimension rate limiting; clamp page sizes | M |
| WEB-3 | High | Web | No file-upload pipeline exists; any future clinical-document/attachment upload has no type/AV/signed-URL controls | `V54`, no `MultipartFile`/object-store in backend | Go-live blocker the moment uploads ship (NB-292/NB-076) | Build upload pipeline: type-verify, AV scan, signed short-lived URLs | L |
| WEB-4 | High | Supply chain | `next@16.3.1` critical per npm audit; Testcontainers 1.19.8 incompatible with Docker 29 so RLS tests may not run; no SBOM/SCA in CI | `frontend/package.json:14`; `backend/pom.xml`; §3.5 | Known-vuln framework; isolation tests unverified; NB-290 unmet | Next ≥16.4.2; align Testcontainers with CI Docker; add SCA/SBOM | M |
| ISO-2 | High (design gap) | Tenancy | Brand visibility scope widens RLS across sibling facilities with no consent, expiry, audit or `WITH CHECK` (permits cross-facility writes) | §3.4; `TenantContext.java:34-47`, `V6:92-131`, `CrossFacilityVisibilityTest.java:76-88` | Latent cross-facility PHI sharing/writes without consent once the scope is set | Replace with consent-governed sharing; add per-command `WITH CHECK`; audit widening | L |
| AUTH-4 | Medium | AuthN | PINs are 4–6 digits | `LoginRequest.java:10` et al. | Low-entropy credential | Require ≥6 digits; passphrase for Owner/operator | S |
| AUTH-9 | Medium | AuthN | MFA recovery-code consumption has no caller; no self-service or admin MFA reset | `AuthRepository.java:128-133` (no caller) | Lost authenticator = lockout; or ad-hoc DB fixes | Wire recovery-code verification; add audited admin reset | M |
| AUTH-10 | Medium | AuthN | Tenant suspension/offboarding doesn't revoke sessions; refresh doesn't re-check tenant status | `TenantLifecycleService.java:44-56`, `AuthService.java:453-460` | Suspended tenant's staff act for ≥15 min, possibly refresh on | Revoke sessions on suspension; re-check tenant status on refresh | S |
| AUTHZ-1 | Medium | AuthZ | No server-side endpoint→permission registry (NB-050); OpenAPI `x-required-permission` is stale (131 of 264 ops) | §5.1-5.2; OpenAPI vs inventory | Authorization correctness relies on per-method annotations with no single source of truth | Generate/verify an endpoint registry in CI against annotations | M |
| AUTHZ-4 | Medium | AuthZ | Field-level restriction (NB-052) enforced in one place only (patient financial field) | `PatientService.java:475-476` | Other restricted fields not masked server-side | Apply `field_grants` consistently in responses | M |
| AUD-2 | Medium | Audit | Hash-chain never verified on a schedule; no off-box checkpoint; chain integrity depends on non-owner app role (ISO-1) | `V14:62-113`, `AuditService.java:55-61` | Undetected tampering if owner role + no scheduled verification | Scheduled `verify_audit_chain` + alerting; external signed checkpoints | M |
| AUD-3 | Medium | Audit | No audit retention/partitioning (3-yr, per-region required) | §7; `V1:110` | NB-078/247 unmet | Retention + partitioning with per-region clocks | M |
| DP-4 | Medium (design gap) | Privacy | No backup/PITR, DSR engine, retention, legal-hold or true erasure; delete is soft | §8; no purge jobs/DSR in code | NB-078/244/245/247/306 unmet; DSR-vs-retention unmodelled | Build DSR engine, retention clocks, legal hold, encrypted regional backups | L |

Strengths S1–S16 remain as recorded in §2; they are not repeated here.

---

## 11. Compliance mapping

Legend: ✓ substantially met · ◐ partial · ✗ gap. Each cell is current state; the final column is the net gap.

| Control | India — DPDP Act 2023 / DPDP Rules & ABDM | UAE — PDPL & Federal Law 2/2019 & DHA/ADHICS | KSA — PDPL & NCA ECC | Qatar — Law 13/2016 | Current state | Gap |
|---|---|---|---|---|---|---|
| Lawful basis / **consent** artefact (versioned, affirmative, withdrawable) | DPDP s.6 notice+consent; ABDM consent-manager model | PDPL Art.4 consent | PDPL Art.5-6 consent | Law 13 Art.4 consent | ✗ opt-out marker only; no capture-at-registration; no versioned registry (§6) | Build consent artefact + registry (NB-241/242); target design in doc 07 |
| Consent enforced at query level, not UI | DPDP purpose limitation | PDPL purpose limitation | PDPL | Law 13 | ◐ enforced for `/v1/patients/**` only, not `/v1/clinical/**` (AUTHZ-2) | Extend consent gate to all PHI reads |
| **Data residency** / no cross-border transfer | DPDP s.16 + ABDM data-localisation; MeitY | PDPL Art.22 cross-border; DHA data in-UAE | PDPL Art.29 + NCA ECC region | Law 13 cross-border controls | ✗ IN+KSA share one DB; no UAE/QA region (DP-3) | Per-region deploys pinned at provisioning (NB-271) |
| Encryption at rest / **KMS per region** | DPDP reasonable safeguards | ADHICS cryptographic controls | NCA ECC-2 cryptography | Law 13 security measures | ◐ AES-GCM for national ID/TOTP; one global key, no KMS (DP-1) | Region-scoped KMS, per-tenant keys (NB-283) |
| Encryption in transit (TLS1.3/HSTS) | DPDP safeguards | ADHICS | NCA ECC | Law 13 | ◐ deployment-dependent; no HSTS/CSP from app (WEB-1) | Enforce TLS1.3+HSTS; NB-284 |
| **Access control / least privilege** incl. BOLA | DPDP safeguards | ADHICS access control | NCA ECC-2 IAM | Law 13 | ◐ strong RBAC but clinical reads unscoped (AUTHZ-2), staff-role escalation (AUTHZ-3), break-glass (AUTH-8) | Fix AUTHZ-2/3, AUTH-8 |
| **Audit / access logging** of PHI | DPDP + ABDM audit | ADHICS logging | NCA ECC-4 event logs | Law 13 | ◐ writes audited + hash-chained; **reads not** (AUD-1) | Audit PHI reads; scheduled chain verify (AUD-2) |
| Breach detection & notification | DPDP s.8(6) breach report | PDPL Art.9 breach notice | PDPL Art.20 breach | Law 13 Art. breach | ✗ no SIEM/alerting/runbook (NB-293/248/249 open) | Build detection + runbook |
| **Data-subject rights** (access/erasure/correction) | DPDP s.11-13 | PDPL Art.13-17 | PDPL Art.4 rights | Law 13 Art.5 rights | ✗ no DSR engine; soft-delete only (DP-4) | DSR engine with statutory timers (NB-244) |
| **Retention** + medical-record minimums + legal hold | DPDP s.8(7) + clinical retention | DHA record-retention | MOH retention + PDPL | Law 13 retention | ✗ no retention/legal-hold (AUD-3/DP-4) | Per-region retention + legal hold (NB-245/247) |
| MFA for privileged access | DPDP safeguards | ADHICS | NCA ECC-2 | Law 13 | ◐ available but not forced for Owner/operator (AUTH-7) | Force MFA on privileged roles |
| Rate limiting / anti-abuse (incl. public number) | — | ADHICS | NCA ECC | Law 13 | ✗ auth-only + IP-forgeable (WEB-2, AUTH-5) | Multi-dimension limits (NB-288) |
| Secure messaging / no file in open chat | ABDM/DLT (DLT India-specific) | DHA | — | — | ✗ no expiring-link delivery; India DLT not done (NB-076/194) | Build secure delivery; DLT for India |
| Supply-chain / vuln management | DPDP safeguards | ADHICS | NCA ECC-5 | Law 13 | ✗ `next` critical; no SBOM/SCA (WEB-4) | Upgrade; add SCA/SBOM (NB-290) |

---

## 12. Prioritised remediation roadmap

### Now — before any real patient data touches the system
These are either trivially exploitable or make every later control untrustworthy.
1. **ISO-1** — ensure the app connects as a non-owner role (or set `FORCE ROW LEVEL SECURITY` on every policy table) and verify with the `pg_class` query in §3.3. Until this is proven, every "RLS backstop" claim and the audit hash-chain's tamper story are unverified.
2. **AUTH-2 / AUTH-8** — stop accepting challenge/pending tokens as bearer credentials and gate break-glass activation (permission + step-up + approver). Together these are a single first-factor-only path to Owner.
3. **DP-2** — default `WHATSAPP_LOG_OTP=false`; stop logging PIN-reset tokens.
4. **AUTH-5 / AUTH-6** — fix `X-Forwarded-For` trust; add a TOTP attempt limit. Both are small changes.
5. **AUTHZ-3** — add the "no grant beyond your own" check to staff role assignment/invite, and audit role changes.
6. **WEB-4** — upgrade `next` to ≥16.4.2 and align Testcontainers so the RLS/auth tests actually run in CI.

### Before launch (go-live blockers from the Security Controls sheet)
7. **AUTHZ-2 / AUD-1** — scope and audit all `/v1/clinical/**` PHI reads (consent + verification + ownership + read audit).
8. **AUTH-1 / DP-1** — persisted KMS-backed signing key with rotation/JWKS; region-scoped KMS with per-tenant keys (NB-009/283).
9. **DP-3 / ISO-2** — per-region deployment with residency pinned at provisioning; add UAE/QA regions; replace the brand visibility scope with consent-governed sharing + `WITH CHECK`.
10. **AUTH-3 / WEB-1** — cookie-based BFF for tokens + strict CSP/HSTS.
11. **AUTH-7 / WEB-2 / AUTH-10** — force MFA on privileged roles; multi-dimension rate limiting; revoke sessions on tenant suspension.
12. **Consent & DSR** — build the consent artefact/registry and DSR engine per doc 07 and NB-241/242/244; retention + legal hold (NB-245/247); backups/PITR (NB-306).
13. **WEB-3** — build the file-upload security pipeline before any clinical-document upload ships.

### After launch (hardening and assurance)
14. **AUTH-4 / AUTH-9** — longer PINs; MFA recovery and admin-reset flows.
15. **AUD-2 / AUD-3** — scheduled hash-chain verification with off-box checkpoints; audit retention/partitioning.
16. **AUTHZ-1 / AUTHZ-4** — CI-verified endpoint→permission registry; consistent field-level masking.
17. Independent VAPT (CERT-In for India, KSA pen test), SIEM alerting, breach-runbook tabletop (NB-294/295/293/248). broad. The function is executable by the application role itself (`V85:45-70`), so the only gate is the class-level `@PreAuthorize("hasAuthority('audit_compliance:view')")` (`platform/audit/AuditSearchController.java:26-27`). `before`/`after` may hold clinical values by policy (`common/AuditService.java:16-19`), and `audit_compliance:view` is held by `sre` as well as `compliance_dpo` (`platform/PlatformPermissions.java:43`). The page size is caller-controlled with no upper bound (`AuditSearchController.java:46`, `AuditSearchService.java:51-54`), so the interactive view sidesteps the 10,000-row cap and the audit record that the CSV export has (`AuditSearchService.java:34,76-118`). Viewing is not audited. See F-14. |
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

-