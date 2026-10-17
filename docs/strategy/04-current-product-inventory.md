# 04 — Current Product Inventory: Built vs Planned vs Missing

**Author:** Product Management
**As of:** 17 Oct 2026. Repository HEAD is `03942d3` (merge of PR #52). The last commits are dated 15 Oct 2026.
**Method:** Every claim below comes from reading the code, not the plan. Sources: the controllers and services (about 260 handler methods in 44 controllers), the 61 Flyway migrations (V1–V85, with gaps), the 40 frontend `page.tsx` routes and their components, `api/openapi.yaml`, the database-architecture PDF, the implementation plan workbook, and `git log` (339 commits).

**Path shorthand used throughout:**
- `BE/` = `backend/src/main/java/com/nabd/hms/`
- `FE/` = `frontend/app/`
- `MIG/` = `db/migrations/`
- `XLSX` = `Nabd HMS - Implementation Task Plan v2.xlsx`

**Status legend (used in every inventory table):**

| Code | Meaning |
|---|---|
| **a** | Backend and UI both complete: a user can do it end to end today |
| **b** | Backend only: API exists, but no screen calls it |
| **c** | UI only, or mock/static content with no backend |
| **d** | Schema only: tables or columns exist, but no feature reads or writes them |
| **e** | Planned in XLSX but not built |
| **f** | Not planned at all, neither in XLSX nor in code |

When a cell reads "a (partial)", the end-to-end flow works but covers only part of what the feature name suggests. The Notes column says which part is missing.

---

## 1. Executive summary

Nabd today is a **staff-facing, single-facility outpatient clinic system**, plus a **SaaS operator console**. Both are of good engineering quality. The core OPD loop is genuinely usable end to end:

> book or walk in → token → configurable visit pipeline → vitals → SOAP note → structured, safety-checked prescription → visit documents → checkout → day close

Packages, nursing worklists, owner reports and platform provisioning are also real.

Everything the founder vision puts in front of the **patient** is absent: WhatsApp self-service, a patient app or portal, an aggregator, live-queue tracking by patients, and consent-based record sharing. Absent too are all the **hospital** modules: LIS, RIS, IPD, OT, ER and insurance. The WhatsApp layer is outbound plumbing only. No business event sends a message yet, and inbound messages are dropped.

### 1.1 Maturity snapshot

Scale: 0 = nothing; 1 = schema or plumbing only; 2 = thin slice, not sellable; 3 = usable with clear gaps; 4 = solid, production-shaped; 5 = complete and differentiated.

| Module | Score | One-line justification |
|---|:-:|---|
| Staff auth & sessions (clinic, owner, operator) | **4** | PIN + WhatsApp OTP + TOTP MFA, refresh rotation with reuse detection, lockout, idle lock, PIN reset, recovery codes and break-glass are all built. Tokens are kept in `localStorage`. |
| RBAC & staff management | **4** | Module × 7-action grants, custom roles, a permission-matrix UI, own-patients scope, field grants and step-up on suspend. "Doctor" is detected by role *name*. Delegations are API-only. |
| Tenancy & org hierarchy | **3** | Facility-level Postgres RLS is solid. Owner → Brand → Facility → Department exists. Cross-facility visibility is half-wired, and there are no multi-facility screens. |
| Clinic setup & admin | **3** | Wizard, charge master, policies, holidays, shifts, licences and payroll CSV work. Import and export jobs are stubs that only record a request. |
| Patient registry & family | **3** | Registration, trigram search, duplicate hints, registry filters, 360 drawer and guardian/minor handling exist. Merge is API-only. There are no attachments and no global identity. |
| Queue, scheduling & waitlist | **4** | Tokens, walk-ins, capacity caps, a per-department state machine, priority, transfers, waitlist offers, doctor delay, a naive ETA, and day/week boards. No real-time push; the UI polls every 15 s. |
| Departments & configurable workflows | **3** | Three platform-authored templates with toggles, a transfer graph, and encounter identity across legs. The template library is tiny and there is no stage-level staffing. |
| Clinical workspace (notes, vitals, allergies, timeline) | **4** | SOAP notes with sign and amend, doctor templates, offline draft and conflict merge, a graded allergy register with history, pregnancy status, conditions and a timeline. Diagnosis is free text. |
| Prescribing & Rx safety | **4** | Structured Rx, tenant drug search, favourite sets, and name/class-based allergy, pregnancy and controlled-drug checks with a mandatory reason. DB-immutable once signed. Print only; no AI. |
| Visit documents (summary/referral/certificate) | **3** | Draft → issue with header snapshot, DB-immutable after issue, print view. No sharing and no uploads. |
| Specialty workspaces | **1** | Dental FDI chart only, with per-tooth history and supernumerary teeth. Eight other specialties are absent. |
| Nursing, orders & triage | **3** | Vitals worklist, urgent flag and ack, medication administration (witness/refuse), procedure orders with consent, record-only vaccine log. |
| Treatment packages | **4** | Builder, sale, ledger, session booking and redemption, expiry ladder, refunds with approval, liability. Reminders are recorded but never sent. |
| Billing & payments | **2** | Checkout invoices, partial payments, flat per-charge tax %, gated discount, OTC sale, day close. No GST/VAT engine, refunds, gateway, dues list or insurance. |
| Pharmacy | **2** | "Hybrid" mode only: item master, stock count with an immutable ledger, dispensing-queue *view*, and dispense-by-billing. No batch/expiry, PO, suppliers or standalone use. |
| Owner reports | **3** | Ten report endpoints on live queries with CSV export, plus an owner overview. Single facility only; no read model. |
| Messaging / WhatsApp infrastructure | **1** | Outbox, template library with Meta status/quality webhooks, a consent gate and delivery tracking. **No business flow calls it.** Inbound is dropped. |
| Patient-facing surfaces (WhatsApp bot, app, portal) | **0** | Nothing. |
| Aggregator / marketplace | **0** | Nothing. |
| Compliance, consent & data protection | **1** | Audit log; withdrawal-only consent gate; guardian consent. No DSR, retention, consent capture or clinic audit UI. |
| Platform / SaaS console | **4** | Provisioning job queue with rollback, 7 operator roles, fleet, lifecycle, plans, subscriptions, discount approvals, tickets, time-boxed support access, audit search and export. |
| Localization | **1** | Strong clinic time-zone handling (`ClinicClock`) and region-aware currency. No i18n, no Arabic, no RTL. Hijri appears on one screen. |
| Interoperability (FHIR, ABDM, NABIDH, HL7) | **0** | Nothing. |
| LIS / RIS / IPD / OT / ER / Insurance | **0** | Only placeholder enum values (`ward`, `bed`, `inpatient`). |
| Engineering foundations (tests, CI, ops) | **2** | 64 backend test classes ("532/532 green" per commit `fix(test): backend suite green`). No CI pipeline in the repo, no observability or IaC. |

### 1.2 Headline gaps against the founder vision

1. **No patient channel of any kind.** WhatsApp is outbound-only plumbing with zero callers, and there is no patient login, app or web aggregator.
2. **Patient and doctor identity are per-facility.** No global patient index, no global practitioner profile, and no consent-based cross-hospital sharing.
3. **No hospital modules.** No labs, imaging, IPD/beds, OT, ER or insurance claims. The product is an OPD clinic system.
4. **Thin money layer.** No GST/VAT/ZATCA, refunds, payment gateway or receivables, and the pharmacy cannot run standalone.
5. **No Arabic/RTL**, despite KSA being a launch region.
6. **The XLSX status column lags the code** in both directions (see §5.4). It should not be used as-is for planning.

---

## 2. Architecture at a glance (product angle)

### 2.1 Tenancy model: what a "tenant" is

The org hierarchy in code is a five-level chain. The architecture doc calls it "Arogya Fabric" (cited in `MIG/V41__org_hierarchy_additive.sql`, which maps doc levels L1–L5 onto tables; the doc itself is not in the repo).

| Product concept | Table | Evidence | Notes |
|---|---|---|---|
| **Owner** (business account, "L1 Tenant" in the doc) | `owners` | `MIG/V6__owner_brand_hierarchy.sql` | Global email, own PIN login (`BE/owner/OwnerController.java` `/v1/owners/auth/login`). Not RLS-scoped. |
| **Brand** (L2) | `brands` | V6, V42 | `brands.data_visibility_scope` ∈ FACILITY / BRAND / TENANT (V42). |
| **Facility / Clinic** (L3), which is **the RLS boundary** | `tenants` | `MIG/V1__staff_auth_core.sql`, V41 | `region` ∈ IN / KSA and is immutable (V5 trigger). `facility_type` ∈ clinic / single_specialty / multi_specialty and `billing_mode` (V41) are stored but read by no code. `timezone` comes from V17. |
| **Department** (L4) | `departments` | `MIG/V37__departments.sql` | Has `kind` (clinical / diagnostic / pharmacy / support / ward) and `specialty_code` (V41); neither is used yet. |
| **Service point** (L5: room/chair/bed) | `service_points` | V41 | **Schema only (d).** No API. |
| Commercial axis: legal entity → tax registration → invoice series | `legal_entities`, `tax_registrations`, `invoice_series` | V41 | **Schema only (d).** Invoices still number from a global `INV-` sequence (`MIG/V20__checkout_invoices.sql`). |

**Isolation.** Each of the tenant-scoped tables (68 `CREATE POLICY` statements across 32 migrations) has a `tenant_isolation` RLS policy on `app.tenant_id`. `BE/common/TenantContext.java` sets it per transaction, and repositories *also* filter `WHERE tenant_id = ?`. This is two layers, and it is well documented (PDF p.2). Narrow `SECURITY DEFINER` functions handle the bootstrap reads: refresh token, invite token, outbox claim, and WhatsApp status webhook.

**Cross-facility visibility (NB-359).** `TenantContext.set()` computes `app.accessible_tenant_ids` from the brand's visibility scope. However:
- Only the tables from V1–V19 (roles, staff, sessions, patients, appointments, queue, consents, merges, setup tables, clinical_notes) have policies extended to honour it. Every later table (invoices, prescriptions, allergies, vitals, packages, nursing, documents…) checks `app.tenant_id` only.
- Every repository query is pinned to one tenant: `WHERE tenant_id = ?` in `BE/patient/PatientRepository.java`.

So **no screen or API actually shows cross-facility data today**. It is plumbing, inconsistently applied.

### 2.2 User types

| User type | Identity store | Login | Home |
|---|---|---|---|
| **Platform operator** (7 fixed roles: super_admin, implementation, support_engineer, billing, sre, commercial, compliance_dpo) | `master.operators` (`MIG/V7__master_schema_operators.sql`), a separate schema | `/v1/platform/auth/login` + MFA (`BE/platform/PlatformAuthController.java`) | `FE/platform/*`, filtered per role (`FE/platform/surfaces.ts`) |
| **Owner** | `owners` (global) | `/v1/owners/auth/login` → pick a workspace (`FE/owner/workspaces/page.tsx`) → a "shadow" `staff` row per clinic holding the built-in Owner role (`BE/owner/OwnerService.java`) | `/overview` |
| **Clinic staff** (Doctor, Nurse, Receptionist, Pharmacist, custom) | `staff`, unique per `(tenant_id, email)` | Clinic code + email + PIN, or WhatsApp OTP, + TOTP (`BE/auth/AuthController.java`; `FE/login/page.tsx` "Clinic code") | Landing by permission (`FE/lib/session.ts` `landingPathFor`): nurse → `/nursing`, doctor → `/my-day`, reception → `/arrivals` |
| **Patient** | `patients`, a **per-facility record**, not a user | **None.** No credentials, no portal, no app. | — |

### 2.3 Roles and permissions model

- **Grants.** `roles.grants` is a JSON array of `ModuleGrant` objects: a module plus seven booleans (view, create, edit, delete, approve, refundDiscount, export). `BE/common/GrantsFlattener.java` flattens them into JWT authorities such as `clinical:edit`. Every endpoint uses `@PreAuthorize(hasAuthority(...))`.
- **Modules in use:** staff, patients, queue, setup, clinical, billing, specialty_dental, reports, pharmacy, packages, nursing, departments (`BE/platform/provisioning/ProvisioningStepRunner.java` `seedMasters`).
- **Row scope.** `staff.scope` ∈ `own_patients_only` / `all_clinic_patients`. It is enforced in SQL (`PatientRepository.SCOPE_GATE`), using appointments as the ownership signal.
- **Field grants.** `staff.field_grants text[]` (V2), for example "no financial fields".
- **UI contract.** A restricted control is *absent*, never disabled (`FE/components/ui/AccessGate.tsx`; a `check:permissions` script in `frontend/package.json`).
- **Delegations.** Temporary grants (V30, `/v1/roles/delegations`) exist in the API, with no UI (b).
- **Weaknesses:**
  - Provisioning seeds only the built-in **Owner** role. Doctor, Nurse and Reception roles must be hand-built.
  - The schema has no "is a clinician" attribute. The doctor roster is `roles.name ILIKE 'doctor'` (`BE/staff/StaffRepository.java` `listRoster`), so renaming the role silently empties every doctor picker.

---

## 3. Module-by-module feature inventory

### 3.1 Founder requirements: direct answers

| Founder requirement | Exists? | Status | Evidence | Notes |
|---|---|:-:|---|---|
| WhatsApp patient booking & self-service (book, manage, view records) | **No** | e | XLSX E18 (NB-201…NB-220): all 20 rows "Not started". `BE/messaging/WhatsAppWebhookController.java` logs "inbound message received and dropped (no inbox yet, NB-197)". | There is no conversational flow. Outbound is template-only plumbing (§3.10), and **nothing in the business code calls `WhatsAppMessageService.queueTemplate`**; the only live WhatsApp send is the staff login OTP (`BE/messaging/TemplateWhatsAppOtpSender.java`). |
| Patient-facing web aggregator / patient app / patient login | **No** | e (aggregator), f (patient app/login) | XLSX E19 NB-221…227 are "Not started", Phase 2. No patient route in `FE/`. `patients` has no credential columns. | The XLSX envisages WhatsApp as the *only* patient surface; a web or app portal is not planned (f). The only planned web patient touchpoint is NB-341 (PWA, staff-oriented). |
| Live queue with ETA/turn prediction | **Partial, staff-side only** | a (partial) | `GET /v1/queue/wait-estimate` (`BE/queue/QueueService.waitEstimate`); used in `FE/arrivals/page.tsx`. | ETA = (avg check-in→invoice time over the last 30 visits) × patients ahead. It ignores the active doctor delay and the elapsed time of the current consult. Patients cannot see it (NB-204 not started). |
| Doctor delay | **Yes (staff-side)** | a | `MIG/V33__doctor_delay.sql`; `POST/GET /v1/doctors/{id}/delay`, `/delay/clear` (`BE/queue/ScheduleController.java`); Arrivals delay form. | The "broadcast ladder" to patients does not exist; no message is sent. |
| Waitlist | **Yes** | a | `MIG/V32__appointment_waitlist.sql`; `BE/queue/WaitlistService.java` (15-minute offer window, cascade on expiry); `FE/schedule/WaitlistPanel.tsx`. | Offers are visible to staff only; the patient is never notified. |
| Walk-ins | **Yes** | a | `POST /v1/queue/check-in` (no `appointmentId`); `FE/components/arrivals/CheckInDialog.tsx`; TopBar "Register walk-in". | Tokens come from a per-doctor-per-day advisory lock (PDF p.2). |
| Priority | **Yes** | a | `queue_entries.priority/priority_reason` (V4); `POST /queue/{id}/reorder`, `/priority/acknowledge`; Nursing "Mark urgent". | A reason is mandatory and the doctor acknowledgement is tracked. |
| Auto-accept vs manual-accept of bookings | **No** | f | `appointments.status` ∈ scheduled / cancelled / completed / no_show (V4). | Only staff create bookings, so no "requested / pending" state exists. Not in the XLSX either. |
| Self check-in vs receptionist check-in | **Receptionist only** | a (reception); e (self) | Check-in needs `queue:create`. NB-207 "Arrival check-in — QR or in-chat" is not started. | No kiosk or QR. |
| Solo-doctor mode (no reception) | **No explicit mode** | f | No setting or flag. | A doctor whose role holds queue:create, billing:create and clinical:edit can technically run every step, but there is no combined single-screen flow. The `clinic_walkin` template with `vitals_enabled=false` gets close. |
| Configurable workflows per facility | **Yes (template-based)** | a | `MIG/V40__workflow_definitions.sql` (3 templates: `clinic_walkin`, `clinic_walkin_with_billing`, `dental_procedure`, with `vitals_enabled` toggle); `GET/POST /v1/departments/{id}/workflow`; `FE/setup/departments/page.tsx`. | V39's free-reorder `visit_flow_steps` was **dropped** in V40 in favour of platform-authored templates. Owners pick a template and toggles but cannot author one. |
| Multi-tenant isolation (RLS) | **Yes** | a | Policies in 32 migrations; `BE/common/TenantContext.java`. | Strong. |
| Org hierarchy owner → brand → facility → department | **Yes (data + owner workspace switch)** | a (partial) | V6, V37, V41; `FE/owner/workspaces/page.tsx`. | No brand-level or owner-level consolidated UI. Owners switch clinics one at a time. |
| Cross-facility visibility & data-visibility scope | **Plumbing only** | d | V42 `brands.data_visibility_scope`; the GUC in `TenantContext`. | No API or UI to set the scope, and no query reads across facilities (see §2.1). |
| Consent-based cross-hospital record sharing (time-limited, approvals tagged) | **No** | f | No sharing, grant or access-request tables. | The closest analogue is *operator* support access: a 60-minute, reason-stated, audited grant (`MIG/V15__support_access.sql`, `BE/platform/access/SupportAccessService.java`), which is not patient-consented. Not in the XLSX. |
| Intra-hospital cross-specialty access settings | **No** | f | Visibility is the tenant-wide own/all scope only. | No department- or specialty-level record restriction exists. Department staffing is "informational only" (V39 comment). |
| Global patient & doctor profiles vs per-tenant records | **Per-tenant only** | f | `patients.tenant_id NOT NULL`, `UNIQUE(tenant_id, mrn)` (V3); `staff UNIQUE(tenant_id,email)` (V1). | A doctor working at 3 hospitals is **3 unrelated staff rows** with 3 logins, each via its own clinic code. The only cross-clinic identity is `owners`. A patient visiting 2 clinics becomes 2 records. The national ID is AES-GCM encrypted and never returned, so it is unusable as a matching key. No global master patient index. |
| AI suggestions / LLM integration | **No** | f | Searches for openai, anthropic, llm and gpt return nothing in `backend/src/main` or `FE/`. | The README lists AGG-03 "no symptom interpretation" as an explicit non-goal. |
| FHIR / ABDM-ABHA / NABIDH / Malaffi / HL7 | **No** | f | No matches anywhere. | Not in the XLSX either. |

### 3.2 Identity, authentication & sessions

| Feature | Status | Evidence | Notes / quality |
|---|:-:|---|---|
| PIN login (clinic code + email + 4–6 digit PIN) | a | `BE/auth/dto/LoginRequest.java`; `FE/login/page.tsx` | Argon2id hashes (PDF p.3). NFR-06 forbids passwords and SSO. |
| WhatsApp OTP login | a | `/v1/auth/otp/request`, `/otp/verify`; `BE/messaging/TemplateWhatsAppOtpSender.java` | Sent synchronously through the Meta Cloud API, or logged when not configured. |
| TOTP MFA enroll/verify, recovery codes | a | `/auth/mfa/enroll|confirm|verify`; `MIG/V31__mfa_recovery_pin_reset_break_glass.sql`; `FE/account/page.tsx` | |
| Refresh rotation with family revocation on reuse | a | V1 `sessions.family_id`; `BE/auth/SessionRevocationJwtDecoder.java` | Good security posture. |
| Session list & remote revoke | a | `/auth/sessions`; `FE/account/page.tsx` | |
| Lockout / unlock, PIN reset | a | `login_attempts` (V1); `/auth/unlock`, `/auth/pin/reset-*` | |
| Idle timeout & clinical screen lock | a (FE) | `FE/IdleLockGuard.tsx` | |
| Break-glass emergency access | a | V31 `break_glass_grants`; `/auth/break-glass/*`; `FE/account/page.tsx` | Staff-level, time-boxed, within one tenant. |
| Staff invite → accept → set PIN | a | `/staff/invitations/{token}/accept`; `FE/accept-invite/[token]` | |
| Owner account invite & workspace select | a | `MIG/V35`, `V36`; `BE/owner/*`; `FE/owner/*` | |
| Operator login with MFA (master schema) | a | `BE/platform/PlatformAuthController.java`; `FE/platform/login` | |
| SSO / OIDC | f (explicit non-goal) | XLSX README NFR-06 | |
| **Concern:** tokens in `localStorage` | — | `FE/pharmacy/page.tsx` (`localStorage.getItem("nabd_access_token")`) | XSS-exposed. NB-291 "frontend hardening" is not started. |

### 3.3 RBAC, staff & access

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Custom role builder + 10-module permission matrix | a | `BE/staff/RoleController.java`; `FE/staff/page.tsx`, `FE/components/staff/PermissionMatrix.tsx` | Matches the XLSX RBAC Matrix. |
| Staff directory, invite, edit, suspend with step-up MFA | a | `BE/staff/StaffController.java`; `FE/staff/page.tsx` (calls `/auth/mfa/verify` before suspend) | |
| Own-patients-only scope | a | `PatientRepository.SCOPE_GATE`; `ReportsService` | Ownership = has an appointment with the doctor. A walk-in-only patient is invisible to an own-scope doctor. |
| Field-level grants | a (partial) | `staff.field_grants` (V2) | Limited flag set. |
| Role delegations (temporary access) | b | V30; `/v1/roles/delegations` | No UI (no frontend reference). |
| Professional registration number | a | `MIG/V55`; `FE/components/staff/RegistrationNoDialog.tsx` | Snapshotted onto issued documents and prescriptions. |
| Subscription-vs-permission separation (NB-058) | e | XLSX "Not started" | Plans carry no module entitlements (`BE/platform/plans/PlanModels.java`). |

### 3.4 Clinic setup & administration

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Guided setup wizard + checklist (skip/resume) | a | `/v1/setup/checklist*`; `FE/setup/wizard/page.tsx`; steps in V37 (welcome, profile, tax, doctors, departments, schedule, charges, pharmacy, whatsapp, go_live) | |
| Clinic profile incl. time zone | a | `/setup/profile`; `FE/setup/TimezoneSelect.tsx` | |
| Charge-head / price master (base, follow-up, tax %) | a | `/setup/charges`; `MIG/V17__clinic_setup.sql`, V20 | Also serves as the pharmacy item master. |
| Policies, consent-contact, holidays | a | `/setup/policies`, `/consent-contact`, `/holidays` | |
| Shifts & attendance, payroll CSV export | a | `/setup/shifts`, `/setup/payroll-export` | CSV only, by design (CAD-10). |
| Licence & facility registry | a | `/setup/licences` | No expiry alerting (NB-250 not started). |
| Self-serve data import / export | **b/c stub** | `BE/setup/SetupService.addImportJob` inserts a `pending` row; there is no processor and no file upload anywhere in the codebase | XLSX says "Done" (NB-068), which overstates it. |
| Departments + transfer graph | a | `BE/department/DepartmentController.java`; `FE/setup/departments/page.tsx` | |
| WhatsApp template management | a | `/v1/setup/whatsapp-templates`; `FE/setup/WhatsAppTemplates.tsx` | Meta approval status and quality rating arrive via webhook. |
| Plan & subscription view | a | `/setup/subscription` | Read-only. Seat usage is shown but not enforced. |

### 3.5 Patients, family & guardianship

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Registration (name, phone, DOB, gender, address, encrypted national ID) | a | `MIG/V3__patients.sql`; `POST /v1/patients`; `FE/patients/page.tsx` | MRN uses a **global** sequence (`patients_mrn_seq`) with per-tenant uniqueness. |
| Search (phone/MRN prefix, trigram name) | a | V3 indexes, V83; TopBar `/` and ⌘K focus search | Script-agnostic trigram search, so it works for Arabic names too. |
| Registry with filters (recent, follow-up, package, balance, duplicate, archived), cursor paging | a | `GET /patients/registry`; `FE/patients/page.tsx` | |
| Duplicate detection with "which fields matched" | a | REC-038 commit; `PatientService` | |
| Supervised merge + 30-day undo | **b** | `/patients/{id}/merge`, `/merges/{id}/undo` | No UI calls them. |
| Patient 360 drawer (allergies, conditions, Rx history, timeline, vitals, dental) | a | `FE/patients/page.tsx` | |
| Guardian / minor, guardian consent, age-18 review list, revoke guardian | a (partial) | `MIG/V28__family_guardianship.sql`; `DELETE /patients/{id}/guardian`; `GET /guardian-reviews-due` | The review list is **b** (no FE reference). "One WhatsApp number, whole family" and proxy booking (NB-080/083) are e. |
| Consent: withdraw data_processing → hidden from every read | b | `/patients/{id}/consent/withdraw`; `PatientRepository.CONSENT_GATE` | No consent capture at registration. A patient with no consent rows is treated as consented (V5 comment). No UI. |
| Document / report attachments, expiring links | e | NB-075/NB-076 "Not started"; no multipart handling anywhere | |
| Data export / erasure (DSR) | e | NB-078, NB-243/244 | |
| Global patient identity / cross-facility record | f | §3.1 | |

### 3.6 Queue, scheduling & availability

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Doctor weekly hours, slot length, session cap (`max_patients`) | a | V4/V5; `/v1/doctors/{id}/working-hours`; `FE/my-schedule`, `FE/setup` | The cap is shared by bookings and walk-ins (`QueueService.enforceSessionCapacity`). |
| Leave blocks | a | `doctor_leave`; `/doctors/{id}/leave` | Whole days only. Existing bookings are not flagged (V4 comment). |
| Availability / slot finder | a | `/doctors/{id}/availability`; `FE/schedule/NewAppointmentDialog.tsx` | |
| Book, cancel (with reason), reschedule | a | `BE/queue/AppointmentController.java`; `FE/schedule/AppointmentActions.tsx` | Double-booking is blocked by a partial unique index (proven under concurrency per PDF p.2). |
| Day and week schedule board | a | `/v1/schedule/day|week`; `FE/schedule/page.tsx` | |
| Live queue board | a | `FE/schedule/LiveQueueBoard.tsx` | Polls every 15 s (`POLL_MS = 15_000`). Real-time sync (NB-096) is not started. |
| Check-in (scheduled or walk-in), token, presenting complaint, expected payment mode, source tag | a | `POST /queue/check-in`; V24, V45; `FE/components/arrivals/CheckInDialog.tsx` | |
| Per-department state machine with illegal-transition guard | a | `QueueService.allowedNextStatuses` + `DepartmentService.resolveStatusSequence` | |
| Inter-department transfer (same encounter) | a | `POST /queue/{id}/transfer`; V37, V43 `encounter_id` | |
| Priority flag + doctor acknowledgement | a | `/queue/{id}/reorder`, `/priority/acknowledge` | |
| No-show handling | a | Status `no_show` mirrored to appointment | |
| Waitlist with auto-offer of freed slots | a | V32; `WaitlistService` | Staff-side only. |
| Callback list | a | `/appointments/callback-list` | |
| Doctor delay announce/clear + punctuality report | a | V33; `/reports/doctor-punctuality` | No patient broadcast. |
| Wait-time estimate | a (naive) | `QueueService.waitEstimate` | See §3.1. |
| Follow-up booking from consult + doctor follow-up list | a | `FE/components/consult/FollowUpPanel.tsx`; `/appointments/follow-ups`; `FE/follow-ups` | The panel itself says "Reminder: not available yet". |
| Waiting-room token display (TV) | e | NB-337/338/339 | |
| Patient self-booking / reschedule / check-in | e | NB-202/206/207 | |
| Ramadan / prayer-time schedule mode | e | NB-036 | |

### 3.7 Departments & workflows

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Department CRUD, default department | a | V37; `/v1/departments` | |
| Workflow template selection + toggles | a | V40; `/departments/{id}/workflow`, `/flow` | 3 templates. A consult or nursing "start" follows the department pipeline (DOC-019, NUR commits). |
| Interim billing per department leg | a | `billing_mode='per_department'` default (V41); `invoices.queue_entry_id UNIQUE` | Consolidated billing across legs is not built. |
| Encounter identity + fixed stage vocabulary (`current_stage`) | a (data) | `MIG/V43__encounter_columns.sql` (triggers) | `class` ∈ ambulatory / procedure / teleconsult / inpatient, but only ambulatory is ever produced (d for the others). |
| Staffing-department per stage, service points | d | V39 `staffing_department_id` (dropped with the table in V40), V41 `service_points` | |
| Owner-authored or platform-authoring UI for templates | f | Templates are seeded in SQL only | No operator screen to publish a new template. |

### 3.8 Clinical workspace

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Consultation workspace (step rail, session strip, pinned timeline, consult timer) | a | `FE/consult/page.tsx` (1,489 lines); `FE/components/consult/ConsultTimer.tsx`, `VisitTimeline.tsx` | Responsive at 1024 and 768 (commit DOC-026). |
| Pre-consult summary dialog | a | `FE/components/consult/PreConsultDialog.tsx` | |
| SOAP note + diagnosis, autosave, sign, amendments with history | a | `MIG/V19`, V34 `note_amendments`; `BE/clinical/NoteController.java` | |
| Offline draft (IndexedDB) and conflict resolution (mine / server / merge) | a | `FE/components/consult/draftStore.ts`, `ConflictDialog.tsx` | A strong time-saver for flaky clinic Wi-Fi. |
| Doctor-owned note templates, "repeat previous visit" | a | `MIG/V69__note_templates.sql`; `/clinical/note-templates`; `FE/components/consult/NoteTemplates.tsx` | Templates are private per doctor; there is no shared clinic library. |
| ICD-10 coding | c (partial) | `FE/components/consult/DiagnosisField.tsx` checks the shape only: "No ICD-10 code list is loaded" | Free text (V21 comment). |
| Vitals capture & charting, validation ranges, weight timestamp | a | `BE/clinical/VitalsController.java`, `VitalsRanges.java`; V72; `FE/lib/vitals.ts` | |
| Allergy register: graded severity, category, status ("no known allergies" documented), history, removal reason | a | V21, V50; `BE/clinical/AllergyController.java`; `FE/components/consult/AllergyRegister.tsx` | Event-sourced history; TRUNCATE is blocked. |
| Chronic conditions / problem list, "due" reviews | a | `MIG/V23`; `/clinical/conditions*` | |
| Pregnancy / lactation status with history | a | `MIG/V52`; `/pregnancy-status`; `FE/components/consult/PregnancyChip.tsx` | |
| Patient timeline (paged) | a | `/clinical/patients/{id}/timeline` | |
| Encounters list (doctor's own, with note/Rx/doc state) | a | `/clinical/encounters`; `FE/encounters` | |
| Dental chart (FDI 11–48, supernumerary, per-tooth history) | a | V21/V22; `BE/clinical/dental/DentalController.java`; `FE/components/consult/DentalDrawer.tsx` | The only specialty. |
| Visit documents: visit summary (prefill), referral, certificate; issue → immutable; print | a | `MIG/V54__clinical_documents.sql`; `FE/components/consult/VisitOutputs.tsx`; `FE/documents/[id]/print` | "Sharing by WhatsApp or a secure link is not available yet" (UI text). |
| Doctor orders → nurse (administration, procedures), procedure consent gate | a | `FE/components/consult/DoctorOrders.tsx`; V29 | External diagnostic request, photo consent and package consent are shown as "Not available yet". |
| Vaccines | a (record-only) | `MIG/V78__vaccine_records.sql`; `FE/components/nursing/VaccinePanel.tsx` | No schedule and no due list (explicitly stated). |
| Lab / imaging orders & results | f / e | `DoctorOrders.tsx` "External diagnostic request: Not available yet"; no tables | Only NB-123/135/138 (attach radiograph/X-ray/USG) are planned. |
| Dermatology, paediatrics growth, gynae, ortho, ENT, Ayurveda, homeopathy | e | XLSX E12 (19 of 21 rows not started) | |

### 3.9 Prescribing

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Structured Rx line: drug, brand, dose, frequency, duration, route, quantity, sig, instructions | a | `MIG/V53`; `FE/components/consult/RxPad.tsx` | |
| Drug type-ahead | a | `GET /clinical/drugs/search`; `FE/components/consult/DrugCombobox.tsx` | Sources are the tenant's own item master and past prescriptions. **No drug database or formulary** (V53 comment). |
| Favourite Rx sets (private/shared scope, stale flag, last used, edit/delete), apply set | a | V34, V60; `FE/components/consult/FavouriteRxSetsPanel.tsx`; `FE/prescriptions` | |
| Repeat last prescription | b | `POST /prescriptions/{id}/repeat-last` (DOC-R2) | No FE call. Note "repeat previous" covers notes only. |
| Weight-based dose calculator with rule source & stale-weight warning | a | `/patients/{id}/dose-calculator`; `FE/components/consult/DoseCalculator.tsx` | |
| Allergy check (name + drug-class, both directions, spelling variants) | a | `BE/clinical/AllergyDrugClasses.java`, `RxSafetyChecks.java` | Hand-curated list that leans towards false positives; flagged as "clinical-safety-owned". |
| Pregnancy/lactation check (age/sex heuristic + recorded status escalation) | a | `RxSafetyChecks.pregnancyWarning`, `recordedStatusWarning` | An 11-drug illustrative list. |
| Controlled-substance warning, India Schedule H1 vs KSA SFDA by region | a | `RxSafetyChecks.controlledSubstanceWarning` | Six drugs per list. The UI states the check is "name and class based only" (commit V1-006). |
| Structured warnings with mandatory override reason codes | a | `RxWarningDecision`; V50 `patient_allergy_assessments`; `FE/components/consult/RxWarnings.tsx` | |
| Drug–drug interaction checking | f | — | Not planned. |
| Signing; DB-level immutability after sign (no update, delete or truncate) | a | `MIG/V51__rx_signed_immutable_pharmacy_stock_log.sql` | Excellent medico-legal posture. |
| Allergy statement snapshot at signing, printed | a | `MIG/V79`; `FE/components/consult/RxPrint.tsx` | Printing is blocked on a malformed snapshot. |
| Print with doctor registration number header | a | `/prescriptions/{id}/print-header`; `FE/consult/rx/[id]/print` | |
| Share Rx to patient (WhatsApp / link) | e | NB-213 | |
| Rx expiry / validity | f | No column | |
| Generic-name preference (India) | e | NB-110 | |
| AI suggestions | f | §3.1 | |

### 3.10 Messaging / WhatsApp

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Transactional outbox + dispatcher with retry/backoff | a (infra) | `MIG/V44__outbox_events.sql`; `BE/common/OutboxDispatcher.java` (`@Scheduled`) | The only scheduled job in the backend. |
| Template library, Meta approval status & quality via webhook | a | V47; `BE/messaging/WhatsAppTemplateService.java`; `WhatsAppWebhookController` | |
| Variable-fill-only sending (no free text) | a (infra) | `WhatsAppMessageService.queueTemplate`; `TemplateRules` | |
| Consent gate at send time; template-status gate | a (infra) | `BE/messaging/WhatsAppSendHandler.java` (`hasMessagingConsent`) | |
| Delivery state (sent → delivered → read / failed) | a (infra) | `MIG/V46__whatsapp_messages.sql`; webhook | |
| **Any business event sending a message** (booking confirmation, reminder, delay, Rx, package expiry) | **e** | `queueTemplate` has **no callers**. `PackageInstanceService.sendReminder` says "No WhatsApp/notification channel exists yet — this records that a reminder went out". | **Important:** the "Send reminder" button in Packages records a ledger event but sends nothing. |
| Inbound messages, 24 h session window, shared inbox | e | Webhook drops inbound (NB-192/197) | |
| Per-tenant WABA numbers, onboarding, SMS fallback, DLT | e | NB-188/189/193/194; `BE/common/LoggingWabaProvisioningGateway.java` (logging stub for the `provision_whatsapp` step) | |
| Campaigns | e | NB-199 | |

### 3.11 Nursing, orders & triage

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Arrivals & vitals worklist (segmented, mobile cards) | a | `FE/nursing/page.tsx` (1,192 lines) | |
| Urgent / priority triage + acknowledgement | a | Queue priority APIs | |
| Medication administration orders (route, witness required, refuse with reason) = MAR | a | `MIG/V29__nursing_orders_triage.sql`; `/v1/nursing/administration-orders*` | |
| Procedure orders (ordered → prepped → completed) with consent capture | a | `/nursing/procedure-orders*`; `FE/components/consult/ProcedureConsent.tsx` | Procedure charges flow into checkout. |
| Package session administration | a | `FE/components/consult/PackageSessionCard.tsx`; package redeem APIs | |
| Today's activity log | a | `/nursing/activity/today` | |
| Vaccine record log | a (record-only) | V78 | |
| Clinical triage inbox, handoff/escalation SLA | e | NB-144, NB-149 | |

### 3.12 Treatment packages

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Package builder, catalogue, activate/deactivate, settings | a | `MIG/V27__treatment_packages.sql`; `BE/packages/PackageController.java`; `FE/packages/page.tsx` (1,067 lines) | |
| Doctor-restricted eligibility | a | `package_eligible_doctors` | |
| Sale wizard (eligibility, region payment labels, invoice-backed tax step) | a | `/packages/sell`; REC-056 commits | |
| Instance ledger, book & redeem sessions, extend (approval), expiring-soon list | a | `/packages/instances*` | |
| Pro-rata refund preview → refund → approval | a | `/refund-preview`, `/refund`, `/refunds/{id}/approve` | |
| Liability & revenue recognition report | a | `/packages/liability`; `/reports` | |
| Expiry reminder | **c-ish** | `sendReminder` only records the event | See §3.10. |
| Instalments, transfer/gifting, family sharing | e | NB-156, NB-159, NB-088 | |

### 3.13 Billing & payments

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Checkout per visit leg: charge lines (consult/follow-up fee, procedures, pharmacy items), invoice | a | `BE/billing/CheckoutController.java`; `FE/checkout/[queueEntryId]/page.tsx` | Invoices are numbered `INV-000001` from a **global** sequence (V20), not per facility or fiscal year. |
| Payments (cash, card, UPI/"Mada & digital", other), partial payments | a | `invoice_payments`; `FE/components/checkout/paymentMethods.ts` | Recording only; no gateway. |
| Tax | a (flat) | `charge_catalogue.tax_rate_percent` (V20) | A flat % per charge. No GST (CGST/SGST/IGST, HSN-based), VAT or ZATCA e-invoice (NB-163/164 "Not started"). `hsn_code` is stored (V25) but not computed on. |
| Discount at checkout (gated by `billing.discount`) | a (partial) | REC-043 commits; `CheckoutController.requireDiscountPermission` | One flat amount; no thresholds or approval queue (NB-165). |
| OTC counter sale without a patient | a | `MIG/V26__otc_sale.sql`; `/billing/otc-checkout`; `FE/checkout/otc/page.tsx` | |
| Day close with cash count, variance note, reopen with reason; blocks billing on a closed day | a | `MIG/V48__day_close.sql`; `BE/billing/DayCloseController.java`; `FE/billing/page.tsx` | XLSX says NB-172 "Not started", which is stale. |
| Invoice list, dues/receivables, follow-up | e | No list endpoint (only `GET /invoices/{id}`); NB-171 | |
| Refunds, credit notes, voids | e | NB-166 | Only package refunds exist. |
| Payment gateway, payment links, QR | e | NB-168/169 | |
| Invoice PDF / secure share | e (partial) | Invoice view in checkout; NB-174 | |
| Insurance: claim export, NPHIES eligibility | e | NB-175/176 | `FE/platform/provisioning/page.tsx` lists an "Insurance / NPHIES connector" secret as static copy only (c). |
| DHA / TPA / full claims, Ayushman | f (explicit non-goal) | XLSX README SYS-10 | |

### 3.14 Pharmacy

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Pharmacy mode (external / hybrid / in_house) | a | `MIG/V25__pharmacy.sql`; `/pharmacy/settings` | Item writes are **rejected outside Hybrid** (`PharmacyService.requireHybridMode`). |
| Item master (Rx flag, HSN, price, tax, stock qty), activate/deactivate | a | Items are `charge_catalogue` rows; `/pharmacy/items`; `FE/components/setup/PharmacyPanel.tsx`; `FE/pharmacy` | |
| Stock adjustments (delta or stock-take with mandatory reason); immutable movement ledger | a / b | V51 `pharmacy_stock_movements` (no update or delete); `/stock-adjustments`; `/stock-movements` | Movement history has no FE call (b). |
| e-Rx dispensing queue (signed prescriptions awaiting dispense) | a (view) | `/pharmacy/dispensing-queue` | There is no "dispense" action. Dispensing = adding the item at checkout, which decrements stock (`CheckoutRepository.decrementPharmacyStockIfApplicable`). No partial dispense or substitution record. |
| OTC sale | a | §3.13 | |
| Batch / lot, expiry, FEFO | e | NB-181 (Phase 2); UI text: "No batch, no expiry, no purchase orders in Hybrid" | |
| Purchase orders, suppliers, GRN | f | Not in the XLSX backlog | |
| Controlled-drug register | e | NB-182 | |
| Low-stock / expiry alerts, stock valuation | e | NB-183, NB-187 | |
| Standalone pharmacy product (retail without a clinic) | f | Pharmacy depends on the clinic's charge catalogue, checkout and Rx | Not sellable standalone today. |

### 3.15 Reports & owner insights

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Owner overview (KPIs, licences, subscription, setup checklist) | a | `/reports/overview`; `FE/overview/page.tsx` | Single facility. |
| Money (per tender, with counts), sources, staff performance, billing leakage | a | `BE/reports/ReportsController.java`; `FE/reports/page.tsx` | |
| Doctor punctuality, retention, rule-based no-show risk | a | Same | |
| Daily money | b | `/reports/daily-money` has no FE call | Superseded by `/reports/money`. |
| CSV export | a | `/reports/export` | |
| Reporting read model / nightly aggregation | e | NB-228; `ReportsService` header comment | Live OLTP queries will not scale to chains. |
| Multi-facility / brand consolidated reporting | e/f | NB-274 "Multi-branch & multi-clinic groups" (Phase 2) | |
| Scheduled delivery | e (in progress) | NB-237 | |

### 3.16 Compliance, consent & audit

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Immutable audit trail service | a (backend) | `MIG/V14__audit_log.sql`; `BE/common/AuditService.java` | PHI policy: IDs only (V3-013 commit). |
| Clinic-facing audit log screen | e | NB-239; the owner nav "Compliance & Audit" links to `/setup?tab=licences` (`FE/ClinicNav.tsx`) | OpenAPI documents `/audit-events`, but **no controller implements it**. |
| Consent capture, versioned registry, hard gate | e | NB-240/241/242 | Withdrawal only (b). |
| DSR, retention, legal hold, breach ops, DPDP and PDPL packs | e | E21 (18 of 19 rows not started) | |
| Region lock (IN/KSA immutable) | a | V5 trigger | Multi-region deployment and residency (NB-271) are not built; there is one deployment (Render/Vercel per `application.yml` CORS). |

### 3.17 Platform / SaaS console

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| 7 operator roles with per-surface gating | a | V7; `FE/platform/surfaces.ts` | |
| Provisioning as a job queue (create_tenant → migrate_schema → seed_masters → provision_whatsapp → verify_invite_owner → go_live), advance/approve, automatic rollback, self-serve vs enterprise paths | a | V8–V10, V35; `BE/platform/provisioning/*`; `FE/platform/provisioning/page.tsx` | `provision_whatsapp` uses a logging gateway. The page also shows static Meta-onboarding steps and a secrets checklist (c). |
| Tenant lifecycle (7 audited states) | b / a | V11; `/platform/tenants/{id}/lifecycle` (no FE call) | Subscription transitions *are* in the UI (`FE/platform/billing/page.tsx`). |
| Fleet console + summary | a | `BE/platform/fleet/*`; `FE/platform/fleet` | |
| Plans (price, currency INR/SAR, seat limit) | a | V18; `FE/platform/plans` | No module entitlements; seats not enforced. |
| Subscriptions, dunning transitions, discount approval queue | a | `BE/platform/billing/*`; `FE/platform/billing` | |
| Support tickets (clinic raises, operator transitions) | a | V13; `/v1/support/tickets`, `/v1/platform/support/tickets` | |
| Support access: 60-minute, reasoned, audited, clinical-field-blind patient view | a | V15; `SupportAccessService` | No tenant-side approval or banner (comment admits it). |
| Platform audit search + actor filter + audited CSV export | a | V16, V85; `FE/platform/audit` | |
| Territories | a (read-only) | `GET /v1/platform/territories` only | |
| Platform health, WABA fleet quality, trial funnel, usage quotas | e | NB-263/264/275/273 | |

### 3.18 Localization

| Feature | Status | Evidence | Notes |
|---|:-:|---|---|
| Clinic time zone everywhere (`ClinicClock`) | a | `BE/common/ClinicClock.java` (guarded by a usage test); `tenants.timezone` | Strong: it fixed real midnight and session-cap bugs. |
| Region-aware money format (INR en-IN, SAR ﷼), region payment labels | a | `FE/lib/money.ts`; `paymentMethods.ts` | Two currencies only, fixed by region. **No multi-currency** within a tenant. |
| Hijri date | a (one screen) | `FE/nursing/page.tsx` (`islamic-umalqura` date line for KSA) | NB-034 dual calendar is not started. |
| i18n framework, Arabic strings, RTL layout | e | `FE/layout.tsx` `lang="en"`, Poppins Latin only; `FE/TopBar.tsx` comment: "no i18n … system exists anywhere in this app" | E04: 0 of 10 done. |
| WhatsApp template language field | a | `FE/setup/WhatsAppTemplates.tsx` | |

### 3.19 Hospital modules & interoperability: absence check

| Capability | Status | Evidence |
|---|:-:|---|
| LIS (lab orders, results, analysers) | f | No tables or routes. Only "attach report" items are planned (NB-123/135/138). |
| Radiology / RIS / PACS | f (attach-only planned: e) | Same. |
| IPD: admissions, wards, beds, nursing charts | d (placeholders) / f | V41 `departments.kind='ward'`, `service_points.kind='bed'`; V43 `class='inpatient'`. Nothing reads them. |
| OT scheduling | f | — |
| Emergency department | f | AGG-02 "emergency phrase interception" (NB-222) is a *redirect to emergency services*, not an ED module. |
| Teleconsultation | e | NB-220 (Phase 3); V43 enum only. |
| FHIR, HL7 v2, ABDM/ABHA, NABIDH, Malaffi, NPHIES | f (NPHIES-lite e) | No matches in code; NB-176 for NPHIES eligibility-lite. |

---

## 4. Product quality observations from the UI code

### 4.1 Strengths and time-savers already present

- **Honest empty states.** Unbuilt capabilities say "Not available yet" instead of showing fake numbers: `FE/components/myday/MyDayParts.tsx` ("never a fake number"), `FollowUpPanel.tsx`, `VaccinePanel.tsx`, `TemplatesScreen.tsx`, `DoctorOrders.tsx`. This reduces support confusion and builds clinical trust.
- **Shared state primitives.** `Skeleton`, `EmptyState`, `ErrorState` with Retry (`FE/components/ui/StateViews.tsx`) are used in 56 files. Route-level `loading.tsx` exists for the owner and platform routes.
- **Keyboard.** Global patient search on `/` and ⌘/Ctrl-K (`FE/TopBar.tsx`). The drug combobox supports arrow, Home/End and Enter (`DrugCombobox.tsx`). The shared `Dialog` has a focus trap, Esc, focus return and stacked-dialog handling (`FE/components/ui/Dialog.tsx`; several fix commits).
- **Responsive and touch.** Breakpoints at 1024, 768/767, 640 and 430 px. `@media (pointer: coarse)` gives 44 px targets (commit V2-018). The nursing worklist turns into cards on mobile, and there is a phone bottom nav with a "More" menu (OWN-060). `prefers-reduced-motion` is respected.
- **Clinical speed features:**
  - favourite Rx sets with a stale flag;
  - note templates and "repeat previous";
  - dose calculator;
  - pre-consult summary;
  - consult timer (pause/extend);
  - offline note drafts with three-way conflict merge;
  - a TopBar primary action per page ("Register walk-in", "Capture vitals", the owner's "Start day close");
  - My Day doctor home with a waiting badge;
  - role-based landing pages.
- **Safety UX.**
  - Allergy status must be documented ("never says no known allergies without a documented status").
  - Rx warnings show severity and "what gets logged".
  - Printing is blocked on a malformed allergy snapshot.
  - Note editing is locked until the note has loaded.
  - Network-error states are distinguished from "none" (for example, follow-up shows "Unknown", not "Not set").
- **Online/offline pill** in the TopBar (a connectivity indicator only; there is no write queue).

### 4.2 Weaknesses

- **No real-time updates.** Arrivals, the live queue board and nursing poll every 15 s (`POLL_MS`). Concurrent desks can disagree for up to 15 s.
- **Monolithic pages:**

  | Page | Lines |
  |---|--:|
  | `FE/consult/page.tsx` | 1,489 |
  | `FE/arrivals/page.tsx` | 1,313 |
  | `FE/nursing/page.tsx` | 1,192 |
  | `FE/patients/page.tsx` | 1,140 |
  | `FE/packages/page.tsx` | 1,067 |

  Each page re-implements its own `authedFetch` with `localStorage` tokens instead of using a shared client. This slows iteration and makes cross-cutting concerns (i18n, RTL, error handling) expensive to retrofit.
- **No design-system package.** NB-012…NB-020 are "Not started". The UI kit is five components in `FE/components/ui/`; styling is CSS modules per page.
- **English-only, Latin-only typography** (Poppins, `subsets: ["latin"]`). The RTL retrofit will touch every CSS module.
- **No invoice list or receivables screen.** Billing is reachable only through a visit or OTC sale.
- **Discoverability gaps.** Several valuable backend features have no UI: patient merge/undo, consent withdrawal, repeat-last Rx, role delegations, guardian reviews due, stock-movement history, tenant lifecycle transitions.
- **Frontend tests are thin.** One unit test file (`frontend/tests/unit`), plus scripts for permission rendering and consult flow.

---

## 5. What's planned (XLSX summary)

### 5.1 Programme shape

- **Plan:** v2 of 13 Aug 2026, prepared for "Harish". It traces 185 wireframe requirement IDs across 30 prefixes.
- **Size:** 359 tasks / 2,270 story points over 23 two-week sprints, with 2→5 squads at 40 points per squad per sprint (peak about 30 people in S14–18).
- **Phases:** 300 tasks MVP, 50 Phase 2, 6 Phase 3, 3 Post-MVP.
- **Tracks:** Shared, Clinic, Patient, Platform.
- **Explicit non-goals:**
  - No SSO.
  - No payroll engine.
  - No live claims pipeline (certified middleware in Phase 3; Ayushman out).
  - Controlled drugs warn, never block.
  - No review gating.
  - Adherence data is never monetised.
  - **No symptom interpretation in the aggregator.**
  - No UI-computed growth percentiles.

### 5.2 Status by epic (points-weighted, from the Backlog "Status" column)

Overall: **122 Done / 3 In progress / 234 Not started**, which is **742 of 2,270 points (32.7%)**. Owner/Squad is blank on every row, and the Epics, Sprints and Tracks rollups are formula-driven with no cached values.

| Epic | Tasks | Done | Points | Done pts | % pts |
|---|--:|--:|--:|--:|--:|
| E01 Foundation & Architecture | 13 | 2 | 74 | 0 | 0% |
| E02 Design System & UI Kit | 10 | 0 | 60 | 0 | 0% |
| E03 Charts / Data-viz | 8 | 0 | 49 | 0 | 0% |
| E04 Localisation, RTL & i18n | 10 | 0 | 53 | 0 | 0% |
| E05 Identity, Auth & Session | 11 | 11 | 42 | 42 | 100% |
| E06 RBAC & Permissions | 11 | 10 | 73 | 68 | 93% |
| E07 Clinic Setup & Admin | 11 | 11 | 74 | 74 | 100% |
| E08 Patient Records | 9 | 6 | 52 | 37 | 71% |
| E09 Caregiver & Family | 10 | 3 | 65 | 21 | 32% |
| E10 Queue & Scheduling | 16 | 15 | 83 | 78 | 94% |
| E11 Clinical Workspace | 19 | 14 | 111 | 85 | 77% |
| E12 Specialty Workspaces | 21 | 2 | 150 | 21 | 14% |
| E13 Nursing, Orders & Triage | 8 | 6 | 44 | 31 | 70% |
| E14 Treatment Packages | 12 | 10 | 83 | 70 | 84% |
| E15 Billing, Tax & Payments | 15 | 1 | 115 | 13 | 11% |
| E16 Pharmacy | 11 | 5 | 70 | 34 | 49% |
| E17 Messaging & WhatsApp Infra | 13 | 0 | 99 | 0 | 0% |
| E18 Patient WhatsApp Surface | 20 | 0 | 133 | 0 | 0% |
| E19 Aggregator & Demand | 7 | 0 | 56 | 0 | 0% |
| E20 Owner Insights & Reports | 10 | 8 | 54 | 43 | 80% |
| E21 Compliance, Consent & DP | 19 | 1 | 126 | 8 | 6% |
| E22 Platform Console | 20 | 16 | 149 | 112 | 75% |
| E23 Trial, Sales & Growth | 7 | 0 | 36 | 0 | 0% |
| E24 Cybersecurity | 15 | 0 | 103 | 0 | 0% |
| E25 DevOps & Infrastructure | 15 | 0 | 90 | 0 | 0% |
| E26 Quality Assurance | 12 | 1 | 84 | 5 | 6% |
| E27 Performance & Offline | 9 | 0 | 53 | 0 | 0% |
| E28 Mobile, Tablet & Display | 9 | 0 | 47 | 0 | 0% |
| E29 Launch & Handover | 8 | 0 | 42 | 0 | 0% |

**In progress:** NB-116 (follow-up & recall engine), NB-121 (specialty framework & entitlement gating), NB-237 (scheduled report delivery).

**Shape of the work so far:** the team has built the clinic "inside" (E05–E14, E20, E22) and skipped:
- the **patient side**: E17–E19, 0 of 40 tasks;
- **money compliance**: E15, 1 of 15;
- **regulatory**: E21, 1 of 19;
- **non-functional foundations**: E24–E29, 1 of 68 tasks.

84 tasks scheduled in sprints 1–12 are still not Done.

### 5.3 Notable open items

- **Patient & growth:**
  - NB-201/202 WhatsApp zero-form registration and six-tap booking;
  - NB-204 live-queue tracking for patients;
  - NB-188 managed WABA onboarding;
  - NB-197 shared inbox.
- **Money:**
  - NB-163 India GST;
  - NB-164 Saudi VAT + ZATCA Phase 2;
  - NB-166 refunds and credit notes;
  - NB-168 payment gateway;
  - NB-171 dues;
  - NB-175/176 claim export and NPHIES-lite.
- **Compliance:**
  - NB-241/242 consent as a hard gate plus registry;
  - NB-244 DSR engine;
  - NB-253/254 DPDP and PDPL packs;
  - NB-276 DPA gate before patient data.
- **Platform:**
  - NB-271 multi-region residency;
  - NB-263 health monitoring;
  - NB-274 multi-branch groups.
- **Foundations:**
  - NB-096 real-time queue sync;
  - NB-228 reporting read model;
  - NB-298 CI;
  - NB-304 observability with PHI redaction;
  - NB-306/307 backup and DR;
  - NB-030/031 i18n and RTL.
- **Security controls sheet:** 69 controls, 67 "Verified? = No". 57 are go-live blockers, plus 3 India-only, 3 KSA-only and 1 aggregator blocker.

### 5.4 Risks sheet

There are 20 risks. The highest scores (Impact × Likelihood ≥ 15):
- **R-05** Meta template/onboarding delays stall the patient product (5×4 = 20).
- **R-09** Scope is double v1 and cannot be absorbed by one team (5×4 = 20).
- **R-01** A clinical-safety defect in prescribing, dosing or allergy checks (5×3).
- **R-03** Cross-tenant or cross-region leakage (5×3).
- **R-06** ZATCA rejection (5×3).
- **R-13** Family/proxy data leak (5×3).
- **R-08** India DLT delays (4×4).
- **R-10/R-11** Specialty and RBAC complexity (4×4).
- **R-14** Late RTL/Hijri defects (3×4).
- **R-15** Poor connectivity and lost work (4×4).

Code reality against the risks:
- **R-01** is partly mitigated: a mandatory override reason, DB immutability, and a "leans towards false positives" policy. However, the drug lists are illustrative, not a validated source, and NB-321 (hazard log) is not started.
- **R-15** is partly mitigated by offline note drafts only.
- **R-03** is well mitigated at facility level by RLS.

### 5.5 Plan-vs-code status drift

The XLSX status column is **not reliable** in either direction.

**Marked "Not started" but code exists (at least in part):**

| Task | What exists in code |
|---|---|
| NB-007 outbox | V44 + `OutboxDispatcher` |
| NB-172 day close | V48 + UI |
| NB-190 template library | V47 + UI |
| NB-191 variable-fill enforcement | `TemplateRules` |
| NB-193 delivery tracking | V46 |
| NB-196 consent gate at send | `WhatsAppSendHandler` |
| NB-118 referral letters & certificates | V54 |
| NB-117 print visit outputs | Print only |
| NB-131 vaccination register | Record-only |
| NB-165 discount control | Permission gate only |
| NB-020 state patterns | `StateViews` |

**Marked "Done" but thinner than the title:**

| Task | What exists in code |
|---|---|
| NB-068 data import/export | Stub rows only |
| NB-053 consent at query level | Withdrawal only; no capture |
| NB-101 wait-time estimation | Naive average |
| NB-273 usage metering & quota | Seats displayed, not enforced |
| NB-270 territories | Read-only list |
| NB-359 cross-facility visibility | GUC set, but queries pinned to one tenant and later tables not covered |

**Other drift:**
- Git commits use a newer ID scheme (DOC-0xx, REC-0xx, OWN-0xx, PLT-0xx, NUR-0xx, CGV-0xx, PHA-0xx, T*-BUG-*), which looks like a persona-level wireframe tracker, with no mapping back to NB-IDs.
- **The DB-architecture PDF covers V1–V5 only** (12 tables, generated 16 Aug). It omits about 80% of today's schema.
- **`api/openapi.yaml`** (99 paths) documents `/tenants/{id}`, `/tenants/{id}/branches` and `/audit-events`, which **don't exist** in code. It omits billing, packages, departments, waitlist delay, WhatsApp templates and most platform endpoints, which **do** exist.

---

## 6. Consolidated lists

### 6.1 Built & strong

- **Staff authentication and session security:** PIN/OTP/TOTP, rotation with reuse detection, lockout, idle lock, break-glass, recovery codes, step-up for sensitive actions.
- **Facility-level multi-tenancy** with RLS on every tenant table, plus explicit `tenant_id` filters and narrow SECURITY DEFINER bootstraps.
- **Configurable RBAC** (module × action, custom roles, own-patients scope) with an "absent, not disabled" UI contract.
- **Queue engine:** DB-guaranteed no double-booking and unique tokens, shared session caps, per-department state machine, transfers with encounter identity, priority with acknowledgement, waitlist auto-offer, doctor delay, no-show.
- **Clinical record integrity:**
  - signed notes with amendments;
  - DB-immutable signed prescriptions and issued documents;
  - allergy and pregnancy event history (TRUNCATE-proof);
  - allergy snapshot at signing.
- **Prescribing workflow:** structured pad, type-ahead, favourite sets, dose calculator, region-specific controlled-drug warnings, mandatory override reasons.
- **Treatment packages:** full lifecycle including pro-rata refunds, approval and liability.
- **Platform console:** provisioning job queue with rollback, 7 segregated operator roles, subscriptions and discount approvals, support tickets, time-boxed audited support access, audit search and export.
- **Clinic time-zone correctness** (`ClinicClock`) across scheduling, queue, packages and reports.

### 6.2 Built but needs improvement

| Area | Specific gap |
|---|---|
| Wait-time ETA | Average check-in→invoice time × patients ahead. It ignores the active doctor delay, the current consult's elapsed time and priority jumps. Staff-only. |
| Live queue | 15-second polling; needs SSE/WebSocket (NB-096) before any patient-facing tracking. |
| Workflows | Only 3 templates and one toggle. No operator UI to author or publish templates. Stage staffing is not enforced. |
| Rx safety | Hand-curated lists (11 pregnancy-risk drugs, 6 controlled per region). No drug DB, formulary, interactions or ICD list. Needs a licensed knowledge source and clinical sign-off (R-01). |
| Pharmacy | No real "dispense" action, no batch/expiry, no PO or supplier. Only Hybrid mode accepts writes. Stock-movement history has no UI. |
| Billing | Flat tax %. Global invoice numbering (not per facility or fiscal year, although `invoice_series` exists). No invoice list, dues, refunds or gateway. |
| Package reminders | The button records "sent" but sends nothing. Wire it to `WhatsAppMessageService` or relabel it. |
| Own-patients scope | Ownership inferred from `appointments`, so walk-in-only patients are invisible to own-scope doctors. Encounters now exist and should be used. |
| Doctor identity | Detected by role name "Doctor". Provisioning seeds only the Owner role. Add a clinician flag and seeded role templates. |
| Consent | Withdrawal-only. Absence of consent = consent. Capture is needed before WhatsApp goes live. |
| Import/export | Records jobs and never processes them. No file upload exists anywhere. |
| Docs | The PDF (V1–V5) and the OpenAPI spec are both out of date; the XLSX status column drifts. |
| Frontend architecture | 1,000–1,500-line pages, duplicated fetch helpers, tokens in `localStorage`, no design-system package. Refactor before i18n/RTL. |

### 6.3 Partially built

- **Org hierarchy beyond a single clinic:** brands, visibility scope and the legal-entity/tax/invoice-series schema exist, but there is no consolidated owner view, no scope settings UI, and RLS is inconsistent on tables after V19.
- **WhatsApp infrastructure:** outbox, templates, webhooks, consent gate and delivery state are ready, but there are no business senders, no inbound handling and no per-tenant numbers.
- **Family / guardianship:** guardian consent, age-18 review (API) and revocation exist. Proxy booking and the one-number family are not built.
- **Visit outputs:** generate, issue and print work. Share, letterhead and templates do not.
- **Vaccines:** record log only; no schedule or due list.
- **Specialties:** dental chart only.
- **Localization:** time zones and currency format are done; Hijri appears on one screen; no Arabic or RTL.
- **Compliance:** audit log backend and withdrawal gate exist; no clinic audit UI, DSR or retention.
- **Backend-only features awaiting UI:** patient merge/undo, consent withdraw, repeat-last Rx, role delegations, guardian reviews due, tenant lifecycle transitions, stock movements, daily-money report.

### 6.4 Not built

**Patient-facing:**
- WhatsApp booking, self-service, reminders and records.
- Patient app, portal or login.
- Web aggregator / marketplace.
- Self check-in (QR, kiosk).
- Patient-visible queue and ETA.
- Waiting-room display.
- Teleconsultation.

**Identity & sharing:**
- Global patient index.
- Global practitioner profile across hospitals.
- Consent-based, time-limited cross-hospital record sharing.
- Intra-hospital cross-specialty access rules.

**Booking modes:**
- Booking request with auto/manual accept.
- Solo-doctor mode.

**Hospital modules:**
- LIS, RIS/PACS, IPD (admissions, wards, beds), OT, ER, nursing charts for inpatients.

**Money:**
- India GST engine; Saudi VAT + ZATCA e-invoicing.
- Refunds and credit notes; payment gateway and links.
- Receivables and dues.
- Insurance (claim export, NPHIES, DHA, TPA).

**Pharmacy:**
- Batch and expiry.
- Purchase orders, suppliers, GRN.
- Controlled-drug register.
- Standalone pharmacy.

**Clinical:**
- File and document attachments.
- ICD/SNOMED terminologies.
- Drug knowledge base and interactions.
- AI/LLM assistance of any kind.
- 8 specialty workspaces.

**Platform & foundations:**
- Interoperability: FHIR, HL7, ABDM/ABHA, NABIDH/Malaffi.
- i18n, Arabic, RTL, dual calendar, Ramadan mode.
- Multi-region residency, platform health, observability, CI/CD, backup and DR, security hardening and VAPT.
