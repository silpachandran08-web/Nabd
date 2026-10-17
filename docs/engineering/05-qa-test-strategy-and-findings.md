# 05 — QA Test Strategy & Findings

| | |
|---|---|
| **Scope** | Spring Boot backend (`backend/`), Flyway migrations (`db/migrations/`), Next.js frontend (`frontend/`), OpenAPI contract (`api/openapi.yaml`), plan workbook (`Nabd HMS - Implementation Task Plan v2.xlsx`) |
| **Author** | QA Lead |
| **Date** | 2026-10-17 |
| **Method** | Scripted inventory (grep / Python over sources), static code review of the highest-risk flows, review of the automated runs the lead started (`mvn test`, `npm` lint/tsc/unit/permission/build) |
| **Status of code** | Working tree at time of review. This report changes no product code. |

---

## 0. Executive summary

The backend suite is **green: 560 tests, 0 failures, 0 errors** (after a Docker API workaround, see §7). It runs in about 90 s against a real Postgres with RLS active. Its foundations are good. It uses real HTTP, real SQL, per-test tenants and DB-level immutability checks. Even so, a static review of the high-risk flows found **42 defects or gaps (QA-01…QA-42)**, including **3 Critical (S1)** and **15 High (S2)**. The green suite misses them because it is mostly single-actor, happy-path and sequential:

* **No test sends a token to the wrong endpoint** (purpose-scoped JWTs). That gap hides an **MFA bypass**: the `mfa_pending` challenge token, issued after the PIN alone, is accepted by `/v1/auth/break-glass/activate`, which returns a full token pair with Owner permissions (QA-01). Together with "any staff can break-glass" (QA-02) and "self-delegation of the Owner role for 30 days" (QA-03), someone with **only a PIN** can get **30 days of Owner access**.
* **No test runs two receptionists at once.** Only 3 of 64 test classes use threads at all. The same appointment can be checked in twice (two tokens). It also stays cancellable or reschedulable while the patient is in the consult room (QA-06).
* **No test drives the app through a browser.** The API rejects `PUT` in CORS preflight, so the UI cannot record **pregnancy status** or "no known allergies". The pregnancy escalation rule exists, is well tested at API level, and still cannot be triggered from the UI (QA-05).
* **Billing is tested one checkpoint at a time.** After an interim payment the invoice stays `paid` even when later charges raise the total, so the balance can never be collected (QA-10). Payments race (lost update, QA-11). Discount permission can be bypassed through client-supplied unit prices (QA-12). Tax is charged on the pre-discount amount (QA-13).
* **Clinical safety content** (pregnancy-risk list, brand names) is thin. Prescription-only items can be sold OTC with no prescription (QA-14). The Owner role is seeded with `clinical:approve`, against the RBAC Matrix, so a business owner who is not a clinician can sign prescriptions and override severe-allergy hard stops (QA-17).
* **Product scope gap:** the product is sold for India and the GCC, but only regions `IN` and `KSA` exist. A Dubai or Doha clinic gets SAR, SFDA lists, Saudi phone rules and the Riyadh timezone (QA-21).
* **Process:** there is no CI (no `.github/workflows` or any other pipeline). There is no coverage tool, mutation testing, SAST or dependency scan. 139 of the 264 implemented operations (53%) are missing from the OpenAPI spec. All 67 rows of the workbook's *Security Controls* sheet say "Verified? = No".

Before the pilot, fix QA-01…QA-06, QA-10…QA-14 and QA-17, add the tests listed in §6, and stand up the CI gates in §5.

---

## 1. Current test inventory

### 1.1 Raw counts (scripted)

* `grep -E "@Test\b"` over `backend/src/test/java` finds **501 `@Test` methods** in **64 test classes**: 63 `*Test.java` files, plus `ApiTestBase` and the `TestEchoOutboxHandler` helper. Two `@ParameterizedTest` methods in `AllergyDrugClassesTest` expand to the remaining invocations, which gives **560** in Surefire.
* Test code is about 13,300 lines. Production Java is 25,770 lines in 17 packages.
* **264 HTTP operations** (`@Get/Post/Put/Patch/DeleteMapping`) sit in 43 controllers.
* Frontend: 135 TS/TSX files (≈25,000 LOC, 40 pages). Automated checks are `tests/unit/vitals.test.mjs` (9 tests), `scripts/check-permission-rendering.mjs` (a static check for disabled-with-reason buttons) and `scripts/test-consult-flow.mjs`. That last one holds 8 tests but **is not wired into any npm script**, so nothing runs it.

### 1.2 Endpoint ↔ test mapping method

`scratchpad/qa/ep3.py` lists every controller mapping, prefixed with the class-level `@RequestMapping`. It then rebuilds every URL used in the tests by concatenation: `"/v1/x/" + id + "/y"` becomes `/v1/x/SEG/y`, and `%s` becomes `SEG`. Each endpoint's path template is turned into a regex and matched against those URLs. The method is ignored, so the result is an **upper bound**: "at least one test calls this path". It is not proof that each verb, error branch or role is tested.

### 1.3 Coverage heat-map by module

Heat legend: 🟥 high business or safety risk with shallow tests · 🟧 reasonable breadth with important gaps · 🟩 adequate for current scope.

| Module | Prod LOC | Endpoints | Endpoints hit by ≥1 test | Test classes | Test methods | Tests / kLOC | Concurrency / multi-actor tests | Heat | Main gaps |
|---|---:|---:|---:|---:|---:|---:|---|---|---|
| auth | 1,486 | 16 | 15 (93%) | 2 | 31 | 20.9 | none | 🟥 | `/auth/unlock` untested; no token-purpose misuse tests (QA-01); MFA re-enroll (QA-04) |
| billing | 1,226 | 10 | 9 (90%) | 3 | 20 | 16.3 | none | 🟥 | payments race, interim-paid status, price-override bypass, tax on discount |
| clinical (+dental, docs, templates) | 5,419 | 52 | 50 (96%) | 15 | 103 | 19.0 | 2 (sign lock, allergy correction) | 🟧 | note amendments untested; controlled-substance rule untested; note sign authz |
| common (outbox, audit, clock, phone) | 974 | 0 | – | 5 | 12 | 12.3 | none | 🟧 | outbox lease fencing / duplicates; phone validation |
| department | 681 | 10 | 10 (100%) | 1 | 13 | 19.1 | none | 🟩 | – |
| messaging (WhatsApp) | 864 | 5 | 5 (100%) | 2 | 9 | 10.4 | none | 🟧 | duplicate-send, inbound STOP, provider outage |
| nursing | 894 | 12 | 10 (83%) | 1 | 17 | 19.0 | none | 🟧 | procedures-today list, procedure notes |
| owner | 531 | 4 | 4 (100%) | 1 | 18 | 33.9 | none | 🟩 | – |
| packages | 1,391 | 21 | 15 (71%) | 1 | 13 | 9.3 | none | 🟥 | 6 endpoints untested; double refund; settings untested |
| patient (+family) | 1,180 | 10 | 10 (100%) | 2 | 27 | 22.9 | none | 🟧 | phone validation; proxy-access leakage (R-13) |
| pharmacy | 607 | 9 | 9 (100%) | 1 | 17 | 28.0 | 1 (stock adjust) | 🟧 | Rx-only OTC gate; batch/expiry absent |
| platform (operators, tenants, billing, tickets) | 3,972 | 36 | 36 (100%) | 15 | 110 | 27.7 | none | 🟩 | – |
| queue / scheduling / waitlist | 2,882 | 28 | 27 (96%) | 8 | 53 | 18.4 | **none** | 🟥 | double check-in, overlapping bookings, midnight rollover, waitlist vs direct booking |
| reports | 906 | 10 | 10 (100%) | 1 | 14 | 15.5 | none | 🟩 | – |
| setup | 1,476 | 27 | 26 (96%) | 1 | 15 | 10.2 | none | 🟧 | mostly CRUD happy paths; `GET /v1/setup/staff` untested |
| staff / roles / delegations | 1,089 | 14 | 14 (100%) | 2 | 26 | 23.9 | none | 🟥 | self-delegation, delegation of break-glass permissions |
| support (cross-tenant) | – | – | – | 1 | 5 | – | – | 🟧 | only 5 list endpoints probed for leakage |
| **Total** | **25,770** | **264** | **250 (95%)** | **64** | **501 (+59 param.)** | **19.4** | **3 classes** | | |

**Endpoints no test calls (14):**
`POST /v1/packages/{id}/deactivate`, `GET|PATCH /v1/packages/settings`, `GET /v1/packages/instances`, `POST /v1/packages/instances/{id}/send-reminder`, `GET /v1/packages/refunds`, `POST /v1/auth/unlock`, `POST|GET /v1/clinical/notes/{queueEntryId}/amendments`, `DELETE /v1/waitlist/{id}`, `GET /v1/billing/checkout/{queueEntryId}/invoice`, `GET /v1/nursing/procedure-orders/today`, `PATCH /v1/nursing/procedure-orders/{id}/notes`, `GET /v1/setup/staff`.

**Reading the heat-map.** Path coverage of 95% looks healthy, but it says little about depth. About 60 assertions check a 403 Forbidden response, roughly one per controller ("role without X grant is forbidden"). No test generates authorization cases from the RBAC Matrix. Only 3 classes use threads (`AllergyApiTest`, `PrescriptionApiTest`, `PharmacyApiTest`). No queue, booking, billing or package test is concurrent.

### 1.4 Test infrastructure quality (`support/ApiTestBase.java`)

Strengths:

* **One Postgres container per JVM** (`postgres:16-alpine`), started in a static initializer. It runs the real Flyway migrations from `../db/migrations` as superuser, then connects the app as an unprivileged `nabd_app` role, so **RLS is actually enforced**. This is the right design for a multi-tenant product. Only 5 Spring contexts are created for the whole run, so context caching works.
* **Isolation by data, not by truncation.** Each test seeds its own tenant (`seedTenant()` with a random slug), so tests never share mutable state and can run in any order. No `@Order`/`@TestMethodOrder` exists.
* **Real HTTP** via `TestRestTemplate` on a random port, using the JDK HttpClient factory (the comment explains the 401 streaming bug it avoids).
* Helpers for TOTP (RFC 6238, ±1 step drift tolerated, so not flaky), step-up and owner/operator logins. `superuserConnection()` lets tests simulate tampering that `nabd_app` cannot do. DB-level guarantees are tested this way: signed-Rx immutability, append-only stock movements, audit hash chain.
* Background outbox polling is disabled (`poll-interval-ms = MAX_VALUE`) and tests call `poll()` directly, so they are deterministic.

Weaknesses / risks:

1. **Testcontainers 1.19.8** (through the Spring Boot 3.3.4 BOM) speaks Docker API 1.32. Docker Engine 29 requires at least 1.40, so on any current Docker host the **whole suite errors (459/560)**. See QA-34. The lead worked around it with `~/.docker-java.properties api.version=1.44`. CI needs a pinned, upgraded Testcontainers (≥1.21.x).
2. **No time abstraction.** Production code reads `Instant.now()` / `LocalDate.now(zone)` directly; `ClinicClock` has no injectable `java.time.Clock`. Tests therefore use the wall clock and work around it:
   * `AppointmentApiTest.nearFutureSlot()` and `WaitlistApiTest` fall back to `now + 5 min` after 23:30 UTC. After about 23:40 UTC that slot lies past the seeded 00:00–23:45 working block, so `bookingBeyondSessionCapacityIsRejected` sees no capacity cap. After 23:55 UTC the slot crosses into a different day-of-week. **Suspected flaky window: 23:40–00:00 UTC.**
   * `ScheduleApiTest.availabilityReturnsSlotsWithinWorkingHours` dropped its main assertion ("not asserting non-empty: flaky in the last few minutes of the UTC day"). The test is effectively assertion-free.
   * All seeded tenants use `timezone='UTC'` (V17 default), so the IST/Gulf day boundary is barely exercised. Only `ClinicTimezoneApiTest` (3 tests) covers it.
3. **`ClinicClockUsageTest`** is a good static guard. It bans `LocalDate.now()` with no zone, `ZoneOffset.UTC`, `CURRENT_DATE` and `::date` in production code. It is a regex scan, though, so it cannot catch semantic day-boundary bugs like QA-22.
4. **Sleeps:** there is one `Thread.sleep(100)` polling loop (`PrescriptionApiTest.waitUntilABackendIsBlockedOnALock`, bounded at 10 s). It is acceptable and could become Awaitility.
5. **Data growth:** every test adds tenants to one shared database and nothing is cleaned up. That is fine at 560 tests. Queries that are not tenant-filtered (platform fleet/audit "across tenants" lists) can become order-sensitive as the suite grows. `FleetApiTest.everySeededTenantAppearsInAFullListing` will slow down.
6. **No coverage measurement** (no JaCoCo in `pom.xml`). No mutation testing. Assertions are good but often single-field.
7. **Frontend:** only one unit-test file. There are no component tests, no E2E tests, and no accessibility or RTL checks.

---

## 2. Requirements traceability

### 2.1 Workbook state

* **Backlog:** 361 rows: 122 *Done*, 3 *In progress*, 234 *Not started*.
* **Requirement Coverage:** 186 requirement IDs. All tasks delivering a requirement are Done for only **37**; 45 are partly delivered and 104 have no Done task.
* **Security Controls:** 67 controls; **0 verified** (all "No").
* Tests cite backlog IDs in only 46 places (`NB-xxx` in comments or names). Traceability below was therefore rebuilt by reading test names against each Done item's acceptance criteria.

### 2.2 Done items with strong test evidence (sample)

NB-040/041/042/045/046/047/048 (auth: `AuthApiTest`, `StaffApiTest`), NB-049/050/052/056/057 (`RoleApiTest`, `PatientApiTest.restrictedFieldGrant…`, `DiscountApiTest.requester…`), NB-060–070 (`SetupApiTest`), NB-071–085 (`PatientApiTest`, `PatientRegistryApiTest`), NB-091/092/098/099/101 (`ScheduleApiTest`, `WaitlistApiTest`, `QueueApiTest`), NB-103/105–109/112–114/119/122/127 (clinical suites), NB-143/145/147/148 (`NursingApiTest`), NB-150/152–155/160/161 (`PackageApiTest`), NB-162/177–180/186 (`CheckoutApiTest`, `PharmacyApiTest`), NB-229–236 (`ReportsApiTest`), NB-238 (`AuditServiceTest`), NB-257–272 (platform suites), NB-350–360.

### 2.3 Done but untested (or tested well below the acceptance criteria)

| Backlog item | Status in workbook | What is missing | Evidence |
|---|---|---|---|
| **NB-044** Idle timeout & clinical screen lock | Done | `POST /v1/auth/unlock` is never called. It also has no throttling (QA-27). | endpoint scan; `AuthService.java:414` |
| **NB-043** Session management & **device trust** | Done | Sessions are tested; "device trust" has no test and no implementation beyond a stored label. | no `device` matches in tests |
| **NB-104** Clinical note amendment & versioning | Done | Both amendments endpoints are untested; sign/amend authorization is loose (QA-18). | `NoteController.java:53,61` |
| **NB-111** Schedule H/H1 and SFDA controlled-substance rules | Done | No test triggers `controlledSubstanceWarning` for either region. Its decision flow (reason `documentation_confirmed`) is unverified. | grep: only `AllergyDrugClassesTest` mentions tramadol (opioid allergy) |
| **NB-273** Per-tenant usage metering & **quota enforcement** | Done | The seat limit is stored and reported but **never enforced** (no check on staff invite). | `seat` appears only in `PlanRepository/PlanService` (QA-33) |
| **NB-090** Scheduling engine — "no double-booking under concurrent load" | Done | Only a sequential double-booking test exists; no concurrent booking test. | `AppointmentApiTest.doubleBookingSameSlotConflicts` |
| **NB-093** Token & walk-in queue | Done | No concurrent check-in test, though the advisory lock is the core design. Double check-in of the same appointment is possible (QA-06). | `QueueApiTest` |
| **NB-097** Cancellation, reschedule & **no-show** | Done | A never-arrived appointment cannot be marked no-show (no endpoint) (QA-31). | `AppointmentController` mappings |
| **NB-100** Doctor delay **broadcast** ladder | Done | Only the in-app announce/clear exists. The patient broadcast is not implemented (comment in `ScheduleService`). The delay never expires and does not feed the ETA (QA-23). | `ScheduleService.java` "delay ladder" comment |
| **NB-099** Waitlist | Done | `DELETE /v1/waitlist/{id}` untested; waitlist vs direct booking conflict (QA-24). | endpoint scan |
| **NB-146** Today's procedures worklist | Done | `GET /v1/nursing/procedure-orders/today` and `PATCH …/notes` untested. | endpoint scan |
| **NB-151/157/158** Package lifecycle, expiry ladder, nudges | Done | Deactivate, send-reminder, instances list, settings and refunds list are untested. | endpoint scan |
| **NB-160** Package refunds | Done | Only the happy path is tested; double refund is possible (QA-15). | `PackageApiTest.approvingARefund…` |
| **NB-162** Checkout | Done | `GET …/checkout/{id}/invoice` untested; interim-paid status defect (QA-10); concurrent payments (QA-11). | |
| **NB-267** Subscriptions, **dunning** & involuntary churn | Done | Only the `overdue` transition is tested; there is no dunning schedule or churn automation. | `BillingApiTest` |
| **NB-102** Consultation workspace shell | Done | `scripts/test-consult-flow.mjs` (8 tests) exists but is not run by any npm script or CI. | `frontend/package.json` |
| **NB-055 / NB-059 / NB-095 / NB-352** (role builder UI, Staff & Access screen, Today/Arrivals screen, provisioning console page) | Done | No UI-level automated test. Only lint, build and the static permission-rendering scan. | `frontend/tests/unit` has one file |
| **NB-054** UI permission rendering contract ("absent, never disabled") | Done | The static scan is useful but cannot prove absence at runtime for each role. Role-by-page E2E tests are needed. | `check-permission-rendering.mjs` |
| **NB-113** Pregnancy & lactation warnings | Done | API-level tests are good, but the browser cannot `PUT` the status (QA-05), and the drug list lacks brands and classes (QA-16). | `SecurityConfig.java:59` |
| **NB-313** Unit & integration test framework | Done | It exists, but there is no CI, no coverage, and it breaks on current Docker (QA-34). | |

Also note: **`Security Controls` 67/67 unverified.** Treat that sheet as the pre-go-live gate checklist; §4.6 maps tests to it.

---

## 3. Static bug hunt — highest-risk flows

Every item below was verified by reading the code at the cited lines. Items marked **(suspected)** depend on runtime conditions not proven here. IDs match the findings table in §6.

### 3.1 Authentication, MFA, break-glass, delegation

**QA-01 (S1, confirmed) — Purpose-scoped JWTs are accepted as general bearer tokens → MFA bypass.**
`SecurityConfig.java:76-93` authenticates any token signed by the single app key (`JwtConfig`). The authorities converter only maps the `permissions` claim. There is no check of `purpose`, `aud` or token type. `SessionRevocationJwtDecoder` checks session liveness only for tokens that carry both `sid` and `tenantId`, and passes the others through unchanged (documented in its Javadoc). The `mfa_pending` challenge issued after the PIN alone (`AuthService.java:523-533`) carries `sub` and `tenantId`. So does the `mfa_setup_required` token (`AuthService.java:144-156`). Endpoints without `@PreAuthorize` that act on `sub`/`tenantId`: `/v1/auth/break-glass/activate`, `/break-glass/{id}/deactivate`, `/mfa/enroll`, `/mfa/confirm`, `/unlock`, `/sessions`, `/logout`.
*Repro:* (1) Seed staff with `mfa_enabled=true`. (2) `POST /v1/auth/login {tenantSlug,email,pin}` returns `{challengeId}`. (3) `POST /v1/auth/break-glass/activate` with `Authorization: Bearer <challengeId>` and body `{"reason":"x"}` returns **200 and a full `TokenPairResponse`** with the built-in Owner role's permissions (`AuthService.java:376-388` → `mintTokenPair`). No TOTP code was ever entered.
*Variant:* use the challenge token on `/mfa/enroll`, which overwrites the secret (QA-04). Then `/mfa/confirm` with your own code, then `/mfa/verify` with the still-valid challenge plus a code from the new secret.
*Variant 2:* a role with `mfa_required` hands out the `mfa_setup_required` token, and break-glass with that token skips mandatory enrollment entirely.

**QA-02 (S1, confirmed) — Break-glass is self-service for every authenticated user and grants full Owner rights.**
`AuthController.java:126-130` has no `@PreAuthorize`. `activateBreakGlass` only needs a reason string, with no step-up and no role eligibility check. `mintTokenPair` folds in **every** permission of the built-in Owner role (`AuthService.java:559-569`): staff and role administration, billing approve/discount, day reopen and `clinical:approve`. Break-glass in a clinical system should be limited to clinical read and emergency write for clinically credentialed roles, and paired with step-up and real-time notification.
*Repro:* log in as a receptionist (queue/patients only) and call activate: the new access token's `permissions` include `staff:edit`, `billing:approve` and `clinical:approve`.

**QA-03 (S1, confirmed) — Self-delegation and delegation of temporary rights turn 30 minutes of break-glass into 30 days of Owner access.**
`RoleService.createDelegation` (`RoleService.java:102-115`) checks `requireNoPrivilegeEscalation` against `callerPermissions` from the JWT. Those include delegated and break-glass permissions. It never rejects `req.staffId == callerStaffId`, never requires the delegatee to differ from the grantor, and never validates that `staffId` belongs to the tenant beyond the FK.
*Repro:* after QA-02, `GET /v1/roles` gives the Owner role id. Then `POST /v1/roles/delegations {staffId:<self>, delegatedRoleId:<Owner>, reason:"cover", expiresAt:+29d}` returns 201. Every refresh and login for 29 days now mints Owner permissions (`AuthService.java:543-557`). Combined with QA-01, a stolen PIN alone gives this.

**QA-04 (S2, confirmed) — MFA re-enrollment overwrites the active secret without proof of the current factor; recovery codes accumulate.**
`enrollMfa` (`AuthService.java:299-307`) writes the new secret straight into `staff.mfa_secret_enc` (`AuthRepository.java:114-116`) while `mfa_enabled` stays true. It does not ask for the current TOTP or a step-up token. Calling enroll and abandoning it locks the user out of MFA, and an attacker holding any session token can take over the second factor. `confirmMfa` inserts 8 new recovery codes (`AuthService.java:322-326`) without invalidating the old ones, so earlier leaked codes stay valid.
*Repro:* MFA user → `POST /mfa/enroll` with an access token → log out → log in → the old authenticator code fails at `/mfa/verify`.

**QA-26 (S3, confirmed) — Break-glass deactivation: no authorization, no session revocation, audit noise.**
`/break-glass/{id}/deactivate` has no `@PreAuthorize`: any staff member can end anyone's emergency grant. `deactivateBreakGlass` writes an audit row even if no grant matched (`AuthService.java:391-397`), and it does not revoke the elevated session. The elevated access token lives 15 more minutes (`access-token-ttl-minutes: 15`). A refresh at minute 29 of a 30-minute grant mints another 15-minute Owner token, so effective elevation can reach about 45 minutes.

**QA-27 (S3, confirmed) — `/v1/auth/unlock` is an unthrottled PIN oracle.**
`AuthService.unlock` (`:414`) compares the PIN with no IP rate limit (unlike login and PIN reset) and no failed-attempt record. With one stolen access token an attacker can brute-force the 4-digit PIN within the token's lifetime, then use it on `/v1/auth/login` from any device.

**QA-37 (S3, confirmed, documented in the code as a "ponytail" known shortcut) — The RSA signing key is generated at every boot** (`JwtConfig`). Every restart logs out all users and rejects every refresh token, and running more than one replica breaks authentication. This matters for any availability or load test, and it blocks horizontal scaling.

### 3.2 Queue token assignment, check-in, concurrency

**QA-06 (S2, confirmed) — The same appointment can be checked in more than once; a checked-in appointment stays `scheduled`.**
`QueueService.checkIn` (`QueueService.java:88-117`) only requires the appointment to be `scheduled` (line 94). It never changes the appointment's status: appointments have no `checked_in` state, and only `completed`/`no_show` are propagated at lines 134-136. `queue_entries.appointment_id` has **no unique constraint** (`V4__queue_appointments.sql:81`; no later migration adds one). The status check also runs *before* the advisory lock (line 102) and is never repeated after it.
*Repro (sequential):* book an appointment → `POST /v1/queue/check-in {appointmentId,…}` twice → two entries, tokens 1 and 2, same patient.
*Repro (two receptionists):* the same in parallel gives the same result. The lock only serializes token numbering.
*Knock-on effects:* while the patient is in the consult room, `POST /v1/appointments/{id}/cancel` still succeeds and `WaitlistService.onSlotFreed` offers the slot to someone else. Reschedule also succeeds, leaving the queue entry pointing at a cancelled appointment.
*Fix:* add an appointment `checked_in` (or `arrived`) status set inside the lock, plus a partial unique index `UNIQUE (appointment_id) WHERE appointment_id IS NOT NULL AND status <> 'transferred_out'`. Optionally warn when the same patient already has an active entry with the same doctor today.

**QA-07 (S3, confirmed) — Check-in does not verify that the appointment is for today.** Line 93-97 checks status, patient and doctor only. An appointment for next week can be checked in today (queue_date = today) and then `markTerminal('completed')`.

**QA-08 (S3, confirmed) — A transferred visit leaves its appointment `scheduled` forever.** `transfer()` (`:157-184`) sets the leg to `transferred_out`, and `markTerminal` only fires for `completed`/`no_show` via `updateStatus`. The new leg has `appointment_id = null`. The original appointment stays `scheduled`, which keeps the slot occupied, allows a second check-in (QA-06), and puts follow-ups on the callback list after 15 days (`AppointmentRepository.listCallbackList`).

**QA-32 (S3, confirmed) — Queue status can skip billing.** `allowedNextStatuses` lets `PATCH /v1/queue/{id}/status {"status":"completed"}` move a visit from `checkout_pending` with only `queue:edit`, so billing is never touched. Day close later catches it ("completed consultation still has no bill"), but it is a revenue-leak path. `no_show` is also allowed from `in_consult`. Recommendation: only `CheckoutService` may move to `completed`, and `no_show` is allowed only before `in_consult`.

**QA-42 (S4, confirmed)** — Check-in with a doctor id that is not in the tenant makes `findCheckInDepartment` (`queryForObject`) throw `EmptyResultDataAccessException`, which surfaces as a **500**. `GlobalExceptionHandler` maps only `ApiException` and validation errors, so `DataIntegrityViolation`, `EmptyResult` and other DB errors all become 500s.

### 3.3 Appointment booking, reschedule, cancellation

**QA-09 (S2, confirmed) — Booking ignores doctor leave, working hours, slot alignment and the past.**
`AppointmentService.book` (`:60-75`) checks clinic holidays and the session cap, and relies on `uq_appointments_doctor_slot (doctor_id, start_time)` (`V4:68`). It does not check:
* **Doctor leave.** Availability hides leave days (`ScheduleService.availability`), but `POST /v1/appointments` books into them.
* **Overlap.** The unique index is on the *start instant*, so with 15-minute slots both 10:00 and 10:05 can be booked: two patients in one slot.
* **Working hours.** A booking outside any block silently gets the 15-minute default and skips the cap.
* **Past start times.** `@NotNull Instant` has no `@Future`. The same applies to `RescheduleRequest`.
*Repro:* doctor on leave today; `POST /v1/appointments {startTime: today 11:00 local}` returns 201. Then book 10:00 and 10:05 for the same doctor: both 201.

**QA-24 (S3, confirmed) — Waitlist promotion edge cases.**
* `onSlotFreed` (`WaitlistService.java:91-99`) offers the freed slot even when it is already **in the past**, for example a missed 10:00 appointment cancelled at 14:00. Plain accept (`:136-159`) then books a past appointment, because only the chosen-slot path checks `isAfter(now)`.
* `AppointmentService.book` never consults `isSlotOnLiveOffer`, so the front desk can book a slot that is on a 15-minute offer to a waitlisted patient. That patient's accept then fails with 409, which breaks the single-offer promise.
* Expiry is lazy and runs only when list/accept/cancel is called. If no one opens the waitlist, an expired offer is never passed on.

### 3.4 Doctor delay propagation

**QA-23 (S3, confirmed / partly suspected).**
* `findActiveDelay` (`ScheduleRepository.java:153`) is just `cleared_at IS NULL` with no day bound, so a delay announced yesterday and never cleared is still "active" today.
* `DelayAnnounceRequest` has `@Min(1)` but no `@Max`; 100,000 minutes is accepted.
* `QueueService.waitEstimate` (`:218-225`) ignores the active delay, so the patient ETA does not move when a doctor runs 45 minutes late. That is the point of NB-100/NB-101.
* No patient broadcast exists (deferred, but the item is marked Done).
* *(Suspected)* two concurrent announcements both run `clearActiveDelay` and then insert. The unique active-per-doctor index makes one fail with an unhandled `DataIntegrityViolationException`, which returns 500.

### 3.5 Timezone & day-boundary

**QA-22 (S3, confirmed) — Midnight rollover splits late sessions.** `queue_date` is the clinic's date at check-in (`QueueService.java:90`). Working hours must satisfy `end_time > start_time` (`V4`), so a session cannot cross midnight. GCC clinics, especially in Ramadan, often run to 01:00. At 00:00 local:
* Token numbering restarts at 1 for the same doctor and session.
* `GET /v1/queue` defaults to `clock.today`, so every patient checked in before midnight **disappears from the live board**.
* Walk-ins after midnight skip the session cap, because no block covers 00:30.
* Day close for "yesterday" is impossible once the date has rolled (see QA-25).
Asia/Kolkata (+05:30) and Asia/Dubai (+04:00) are handled correctly for normal daytime hours. `ClinicClock`, `countSessionOccupancy` and `AppointmentRepository.listPage` all build instants in the clinic zone.

**QA-21 (S2, confirmed) — "GCC" means Saudi Arabia only.** `tenants.region CHECK (region IN ('IN','KSA'))` (`V1:23`, also in `V8`, `V17`). Every regional switch is binary (`CheckoutService.java:306-309`, `DayCloseService.java:50,81`, `RxSafetyChecks.java:95`, `PhoneNumbers.java:24`, `ClinicClock.defaultZoneFor`). A UAE or Qatar clinic would get SAR invoices, SFDA lists, Saudi `05x` phone rules and Asia/Riyadh, which is **one hour off for Dubai** unless corrected by hand. AED/QAR, 5% UAE VAT, Qatar (no VAT) and MOHAP/DHA/DoH controlled lists are absent.

### 3.6 Prescriptions: signing immutability, allergy, pregnancy, controlled drugs

**Signing immutability — no defect found.** `upsert`, `applySet` and `repeatLast` all go through `upsert`. That method takes the register lock and then the row lock, and refuses `signed` (`PrescriptionService.java:129-135`). V51 adds DB triggers that refuse UPDATE/DELETE/INSERT of items on a signed parent, UPDATE/DELETE of a signed header, and TRUNCATE. `PrescriptionApiTest` proves this with superuser tamper attempts. Good.

**QA-05 (S2, confirmed in code; verify in browser) — The UI cannot record pregnancy status or "no known allergies".**
`SecurityConfig.java:59` allows `GET, POST, PATCH, DELETE` but not **`PUT`**. Two endpoints are `PUT`: `PregnancyStatusController.java:39` and `AllergyController.java:83`. The frontend calls the API cross-origin (`NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1"` from the app on `:3000`) with `method: "PUT"` (`PregnancyChip.tsx:57`, `AllergyRegister.tsx:151`). The browser preflight is therefore rejected (Spring returns 403 "Invalid CORS request"). Backend tests use `TestRestTemplate`, which does no CORS, so they pass.
*Clinical impact:* the DOC-021 escalation ("recorded PREGNANT → severe, fresh decision") can never be triggered from the product, and an NKA statement cannot be recorded.
*Fix:* add `PUT` (and `OPTIONS` handling) to the allowed methods, plus a CORS contract test.

**QA-16 (S2, confirmed content gap — needs Clinical Safety Officer review).**
* `PREGNANCY_RISK_DRUGS` (`RxSafetyChecks.java:21-23`) has 11 generic stems and **no brand names**, unlike the allergy classes. It misses widely used teratogens and lactation-risk drugs: ramipril and other ACE inhibitors beyond lisinopril/enalapril, ARBs (losartan, telmisartan, olmesartan — very common in India), statins, carbamazepine, phenytoin, topiramate, lithium, mycophenolate, leflunomide, finasteride/dutasteride, acitretin, and brands such as Accutane/Isotroin, Coumadin/Warf, Depakote/Valparin/Encorate.
* *Repro:* female, 28 years, prescribe "Isotroin 20 mg" or "Telma 40" (telmisartan): no pregnancy warning.
* The allergy class lists also miss common brands: Mox / Wymox (amoxicillin; "mox" is under the 4-character rule), Contramal / Ultracet (tramadol), Cataflam and Profinal (GCC diclofenac and ibuprofen). Arabic-script names are never matched.
* `matches()` in `RxSafetyChecks.java:100-103` uses `toLowerCase()` with the default locale; under a Turkish JVM locale "I" folds differently.
* A recorded `pregnant` status never expires, even past its expected delivery date.

**QA-14 (S2, confirmed) — Prescription-only items can be sold without a prescription.** `charge_catalogue.is_rx` is stored and editable (`PharmacyRepository`) but nothing on the billing path reads it. `CheckoutService.otcCheckout` → `resolvePharmacyLines` (`CheckoutService.java:283-300`) only checks `active`. Alprazolam and other Schedule H/H1 or SFDA-controlled items can be sold OTC with no patient and no Rx. Visit checkout likewise never reconciles dispensed pharmacy lines against the signed Rx.
*Repro:* hybrid mode, add item `{code:"ALP05", isRx:true}`, `POST /v1/billing/otc-checkout` with that line → 201 and stock decremented.

**QA-17 (S2, confirmed) — The Owner role has full clinical approval, against the RBAC Matrix.** Provisioning seeds the Owner with `fullGrant("clinical")` (`ProvisioningStepRunner.java:194`), and so does `ApiTestBase.seedFullAccessRole`. The RBAC Matrix sheet says *Owner / Manager → Clinical Records: Create N, Edit N, Approve Y*. Separately, `PrescriptionService.mayActAsVisitDoctor` treats `clinical:approve` as "may sign or decide on any Rx". The result: a non-clinician owner, and anyone on break-glass (QA-02), can sign prescriptions and record severe-allergy override decisions. Signing and override authority should depend on a clinical licence (`professional_registration_no` present and a clinician role), not on a permission any owner holds.

**QA-18 (S3, confirmed) — Clinical note sign/amend authorization is looser than the Rx rule.** `NoteService.sign` (`:66-71`) and `amend` (`:79-93`) require only `clinical:edit`, which nurses hold per the RBAC Matrix. They do not check that the caller is the visit's doctor (the V1-007 rule used for Rx and documents). `clinical_notes` has no `signed_by` column, and signing is not audited. A nurse can sign, or later amend, a doctor's note.

### 3.7 Pharmacy stock

* **Negative stock / concurrent dispensing: no defect found.** The checkout decrement is a guarded `UPDATE … SET stock_qty = stock_qty - ? WHERE stock_qty >= ? RETURNING` (`CheckoutRepository.java:176-184`), and manual adjustments lock the row. Movement rows carry `CHECK (qty_after >= 0)`. `PharmacyApiTest.concurrentAdjustmentsDoNotLoseUpdates` exists.
* **QA-39 (S4, gap)** — There is no batch, expiry or FEFO model: `stock_qty` is a single integer, by design (V25 header: "in-house mode … explicitly" out of scope). Expired-batch dispensing therefore cannot be prevented at all. There is also **no OTC return / credit-note path** in billing (packages have refunds; pharmacy sales do not). Any return would have to be done as a manual stock adjustment, which leaves the invoice unchanged.

### 3.8 Billing totals, tax, discounts, payments, day close

**QA-10 (S2, confirmed) — An invoice paid at an interim checkpoint stays `paid` after later charges.** At a later checkpoint `CheckoutService.checkout` (`:137-148`) appends lines and calls `updateInvoiceTotals` (`CheckoutRepository.java:150-155`). That method updates subtotal/discount/tax/total but **not `status`**. `recordPayment` (`:237-240`) then refuses with `invoice-already-paid`.
*Repro (dental two-checkpoint flow):* consult fee billed at `billing_pending` → pay in full (status `paid`) → procedure added at `checkout_pending` (total rises) → `POST /v1/billing/invoices/{id}/payments` returns **400 "Invoice already paid"** while the response shows `balance > 0`. The test `dentalClinicFlowAccumulatesChargesAcrossTwoBillingCheckpointsOnOneInvoice` never pays in between.

**QA-11 (S2, confirmed by code) — Concurrent payments lose updates.** `recordPayment` reads `invoice.paid()` without a lock, computes `newPaid`, and writes an absolute `paid = ?` (`CheckoutRepository.java:226-228`). Two cashiers recording 300 and 200 against a 500 invoice at the same moment can leave `paid = 300` and status `partial` while two payment rows sum to 500. Day-close "collected" (which sums the payment rows) then disagrees with invoice balances.
*Fix:* `SELECT … FOR UPDATE` on the invoice, or `UPDATE invoices SET paid = paid + ? … RETURNING paid, total`.

**QA-12 (S2, confirmed) — Discount permission bypass.** `requireDiscountPermission` (`CheckoutController.java:82-89`) only looks at the `discount` field. For any non-pharmacy line, the client-supplied `unitPrice` and `chargeName` are trusted (`CheckoutService.java:283-290`; `LineItemRequest` allows `unitPrice ≥ 0`), and `chargeCode` is not checked against `charge_catalogue` at all.
*Repro:* a receptionist **without** `billing.discount` posts the consultation line `{chargeCode:"CONSULT", unitPrice:0}` (or 1) → 201, an effectively 100% discount with no approval and no audit. Separately, at a second checkpoint `discount` **replaces** the earlier one (`updateInvoiceTotals`). A user without discount rights sending `discount: 0` silently removes a discount an authorized colleague approved, and vice versa.
*Fix:* price non-pharmacy lines from the catalogue (base or follow-up amount), and route any override through `billing.discount` with an audited reason.

**QA-13 (S2, confirmed in code; tax-advisor confirmation needed) — Tax is computed on the pre-discount amount.** `computeTotals` (`CheckoutService.java:208-230`) charges tax per line on `unitPrice × qty`, then subtracts the invoice-level discount from the subtotal without reducing taxable value. Under GST (a discount shown on the invoice reduces transaction value) and KSA VAT, this **over-collects tax**.
*Example:* ₹1,000 at 18% with a ₹100 discount → tax ₹180 (should be ₹162), total ₹1,080 (should be ₹1,062).
Two further issues: round-off to a **whole currency unit** (`setScale(0)`) is wrong for SAR invoices (halalas; ZATCA expects 2-decimal amounts). Per-line `HALF_UP` rounding of tax then summing can differ from the invoice-level tax on multi-line bills. The workbook already lists this risk (R-06, R-07).

**QA-25 (S3, confirmed / partly suspected) — Day close and reopen.**
* `reopen` accepts any `date` (`DayCloseService.java:95-104`), but `close` only works on today (`:65`). A past day that is reopened stays `reopened` forever, with no way back to `closed`. Billing writes always land on today anyway, so reopening a past day has no use.
* *(Suspected)* `requireTodayOpen` and `close` are not serialized: a payment in flight while the day closes can commit after the expected-cash snapshot, so the closed record and the actual payments diverge. A per-tenant-day advisory lock is the fix.
* Variance tolerance is hard-coded per region (₹200 / SAR 20; no AED/QAR).

### 3.9 Packages

**QA-15 (S2, confirmed) — Double refund and stale refund amounts.**
* `requestRefund` (`PackageInstanceService.java:289-297`) creates a refund row without checking instance status or existing pending/approved refunds.
* `approveRefund` (`:309-330`) checks only the refund's own status and requester ≠ approver.
*Repro:* two refund requests on one instance → two different approvers each approve one → **two credit notes** (`CN-000001`, `CN-000002`) for the same package.
* Sessions redeemed between request and approval are not deducted: the amount is frozen at request time, and redeem is still allowed while a refund is pending.
* Two approvers on the same refund at the same moment both pass the `approved` check (no lock): two credit-note numbers are consumed and one is overwritten.
* Minor: `ExtendRequest.newValidityEnd` has no `@Future` and no "later than current end" check, so "extend" can shorten or back-date validity and works on refunded instances. Session over-consumption is correctly prevented by the guarded `incrementConsumed` and `CHECK (quantity_consumed BETWEEN 0 AND quantity_total)`.

### 3.10 Cross-tenant isolation, pagination, input validation

**QA-28 (S3, confirmed) — Single-column FKs let one tenant reference another tenant's patients or staff.** Postgres referential-integrity checks **bypass RLS**. `appointments.patient_id REFERENCES patients(id)` (`V4:45`), `queue_entries.patient_id` (`V4:82`), `doctor_id REFERENCES staff(id)`, waitlist and delegation `staff_id` and similar columns are not composite `(tenant_id, id)`. The services do not check that the referenced patient belongs to the caller's tenant: `AppointmentService.book`, `QueueService.checkIn`, `WaitlistService.join`, `RoleService.createDelegation`. A tenant-A user who knows a tenant-B patient UUID can create appointments or queue entries for it (they show as "Unknown patient"). It does not leak reads, but it corrupts data, and it is a building block for confused-deputy bugs. *Fix:* composite FKs `(tenant_id, patient_id) REFERENCES patients(tenant_id, id)`.

**QA-40 (S4, defence-in-depth)** — `package_eligible_doctors`, `legal_entities` and `tax_registrations` have no RLS. No policy is `FORCE`d. The brand-scope policies (`V17`, `V19`) are `USING`-only. With `accessible_tenant_ids` populated, the DB layer therefore allows UPDATE/DELETE on sibling facilities' rows, including clinical notes, and only the app's `tenant_id = ?` predicates stop it. Add `WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid)`.

**QA-19 (S3, confirmed) — Pagination `limit` is unbounded and unvalidated** on `GET /v1/appointments`, `/v1/staff`, `/v1/patients` (list and search), `/v1/clinical/patients/{id}/timeline`, `/v1/platform/audit-log`, `/v1/platform/tenants`, and `/v1/platform/billing/subscriptions|discounts`. Only the encounter list, follow-ups, registry and drug search clamp it.
* `limit=0` → `limit+1 = 1` row fetched, `hasMore = true`, `page.get(-1)` throws `IndexOutOfBoundsException` → **500** (`AppointmentService.java:84-88`, and the same pattern in Staff/Patient/Timeline/Audit/Fleet services).
* `limit=-5` → SQL `LIMIT -4` → Postgres error → 500.
* `limit=2147483647` → integer overflow → negative LIMIT → 500.
* `limit=1000000` → one unaudited call dumps every patient in the tenant (PHI bulk export that bypasses the audited export path).

**QA-20 (S3, confirmed) — Phone numbers are normalized but never validated.** `PhoneNumbers.digits` (`PhoneNumbers.java:15-32`) returns whatever digits remain, and `e164` returns the raw text when no digits remain. `PatientWriteRequest.phone` is only `@NotBlank`.
* `"abc"` is stored as `"abc"`; `"12345"` is stored as `"+12345"`.
* UAE `"0501234567"` becomes `+966501234567` in a KSA clinic and `+910501234567` in an Indian one.
* `"+91 0 98123 45678"` keeps the trunk 0 (`+9109812345678`), so duplicate detection misses the same person.
* Indian numbers that do not start with 6–9 are accepted.
* Qatar (+974, 8 digits) and UAE (+971, 9 digits) have no rules at all.
`PhoneNumbersTest` covers only valid IN and SA inputs. Adopt libphonenumber with per-region validation.

### 3.11 WhatsApp messaging and outbox

* **Webhook duplicates — OK.** `apply_whatsapp_status` only moves status forward, so Meta's batch retries are idempotent. HMAC uses constant-time compare.
* **QA-29 (S3, suspected; conditional on multiple instances or a slow provider) — Outbox duplicate sends.** `claim_outbox_events` takes a 60-second lease on up to 20 events (`application.yml:66-67`). The dispatcher processes them one after another, each with a 10-second read timeout (`MetaWhatsAppClient.java:20-21`), so a degraded provider pushes later events past their lease. `markDone` and `markFailed` (`OutboxRepository.java:29-38`) update `WHERE id = ?` with no lease or attempt fencing, and `WhatsAppSendHandler` reads the message without `FOR UPDATE`. With two app instances (once QA-37 is fixed), events can be reclaimed and **sent twice**, and a stale worker's `markFailed` can reset an event another worker already finished. The class Javadoc accepts the single crash-window duplicate; the lease-expiry duplicates are not covered by it. *Fix:* fence updates with `AND attempts = :claimedAttempts AND status = 'processing'`, lock the message row, and size the batch so batch × timeout is less than the lease.
* **QA-30 (S3, confirmed) — Inbound messages are dropped** (`WhatsAppWebhookController.java:87-89`). Patient "STOP" or opt-out replies are not processed, which is a Meta policy and consent problem (R-05, R-08), and the planned WhatsApp booking flow has no inbound path yet.

### 3.12 Guardian / minor access and family proxy

`PatientApiTest` covers the minor-requires-guardian rule, guardian reassignment that moves consent, deleting a minor's guardian being blocked, and the age-18 handover review. No defect was confirmed in the time available. Not tested: a guardian's proxy scope across siblings (R-13: "one member's data leaks to another"), WhatsApp messages to a guardian's phone after the age-18 handover, and consent-at-query-level (NB-053) checks that *withdrawn* consent hides records rather than only changing a flag. Add these to the multi-tenant and proxy fuzz suite (§4.5).

---

## 4. Test strategy going forward

### 4.1 Test pyramid targets (12-month)

| Layer | Today | Target | Notes |
|---|---|---|---|
| Pure unit (rules, calculators, matchers) | ≈30 methods (`RxSafetyChecks`, `AllergyDrugClasses`, `TotpService`, `PhoneNumbers`, rules tests) | 40% of test count, < 10 s | Extract more pure functions: `computeTotals`, `allowedNextStatuses`, refund preview, slot generation, pregnancy rule. Inject a `java.time.Clock` into `ClinicClock`. |
| API integration (Testcontainers) | 470 | 45%, < 4 min, parallel by class | Keep the real-RLS approach. Add multi-actor and concurrency suites and generated RBAC tests. |
| Contract (OpenAPI) | 0 | Every operation in the spec; CI-blocking | §4.2 |
| E2E (Playwright) | 0 | 15 journeys × 2 locales, < 15 min | §4.3 |
| Non-functional (k6, ZAP, axe, chaos) | 0 | Nightly / pre-release | §4.4–4.8 |

### 4.2 Contract testing against OpenAPI

* **Drift today:** 264 implemented operations versus 131 in `api/openapi.yaml`. **139 are undocumented** (all of billing, packages, departments, reports, platform auth, break-glass, MFA enroll, waitlist operations). **6 spec operations have no implementation**: `/v1/audit-events`, `/v1/tenants/{id}`, `/v1/tenants/{id}/branches` (GET/POST), and **`/v1/auth/pin-reset/request|confirm`**, which the code implements as `/v1/auth/pin/reset-request|confirm` (QA-35).
* **Approach:**
  1. Add `springdoc-openapi` and generate the spec from code in CI.
  2. Run `openapi-diff` (or `oasdiff`) between the generated spec and `api/openapi.yaml`, and fail on undocumented or breaking changes.
  3. Run **Schemathesis** against a preview environment with a seeded tenant and tokens for each role. Use stateful links and these checks: `not_a_server_error`, `status_code_conformance`, `response_schema_conformance`. That alone would have found QA-19 (limit=0 → 500) and QA-42.
  4. Check `x-required-permission` in the spec against `@PreAuthorize` with a reflection test. The SecurityConfig Javadoc promises this mapping 1:1.

### 4.3 E2E Playwright suite — top 15 journeys

Run against a preview environment with synthetic data, in Chromium, WebKit and Firefox, in English and Arabic (RTL). Timings are measured from the first user action to the confirmation state, on reference hardware over simulated "clinic 4G" (10 Mbps / 80 ms RTT).

| # | Journey | Acceptance criteria | SLA |
|---|---|---|---|
| J1 | **Walk-in registration + check-in** (new patient) | Duplicate-candidate banner appears for an existing phone; token shown; entry appears on the Today board for the receptionist and on the doctor's queue within 5 s | **< 45 s** end-to-end, < 1 s per API call p95 |
| J2 | Returning patient with appointment → check-in | Appointment found by phone; **second check-in of the same appointment is refused** (QA-06); appointment shows "arrived" | < 20 s |
| J3 | Book appointment by phone | Leave days and past slots not offered **and not bookable via API** (QA-09); two browsers booking the same slot → exactly one succeeds | < 30 s |
| J4 | Cancel → waitlist offer → accept | Offer only for future slots; slot not bookable by others while on offer (QA-24); offer expires at 15 min | < 30 s |
| J5 | Doctor consult: next patient → notes autosave → sign | Autosave ≤ 1 s after typing stops; signed note read-only; only the visit doctor or an approver can sign (QA-18) | < 3 min total, sign < 2 s |
| J6 | e-Rx with severe allergy | Hard stop shown; sign blocked until a named reason is chosen; audit entry; print header has prescriber registration no. | warning < 500 ms after line entry |
| J7 | Pregnancy status → Rx escalation | Recording "pregnant" **from the UI succeeds** (QA-05); a teratogen line escalates to severe; prior generic override not reused | < 10 s |
| J8 | Nurse triage: vitals + priority flag → doctor acknowledges | Implausible vitals rejected; visit advances to vitals_done; flag visible to doctor; ack recorded | vitals capture < 60 s |
| J9 | Checkout: consult + pharmacy item, split cash + UPI | Price taken from catalogue; stock decremented; tax per band; receipt; balance 0 | < 60 s |
| J10 | OTC sale | Rx-only item blocked without a linked prescription (QA-14); receipt; stock movement row | < 30 s |
| J11 | Dental flow, two billing checkpoints with interim payment | One invoice; after interim payment + procedure, **balance collectable** (QA-10) | < 90 s |
| J12 | Package sale → session redemption → refund (maker-checker) | Counter decrements only on redeem; second refund request refused (QA-15); credit note once | < 2 min |
| J13 | Day close with variance note → reopen with reason | Close blocked with unbilled visits; note required over tolerance; reopen audited; re-close possible | < 60 s |
| J14 | Staff invite → accept → MFA enroll → login with TOTP → break-glass | Challenge token useless outside `/mfa/verify` (QA-01); break-glass visible to owner within 5 s; auto-expires at 30 min | login < 10 s |
| J15 | Platform provisioning → owner invite → workspace select → setup wizard go-live | Six steps; rollback on failure; owner lands in Owner Insights; checklist resumable | provisioning < 2 min |

Each journey also runs as a **role-permission sweep**: log in as each RBAC Matrix role and assert that restricted elements are *absent* from the DOM (NB-054), not just disabled.

### 4.4 Performance & load testing (k6)

| Scenario | Shape | Pass criteria |
|---|---|---|
| **Morning OPD rush** | 50 tenants × 3 receptionists + 4 doctors; ramp to 2 check-ins/s per tenant for 30 min, mixed with queue polling every 5 s per screen, status transitions and checkouts | p95 check-in < 300 ms, queue list < 200 ms, checkout < 500 ms; **0 duplicate tokens, 0 double check-ins, 0 cross-tenant rows**; DB CPU < 70% |
| **Same-doctor contention** | 20 virtual receptionists booking and checking in for one doctor | Advisory lock wait p99 < 100 ms; no deadlocks; session cap never exceeded |
| **WhatsApp booking/reminder burst** | 5,000 reminders queued in 5 min across tenants; provider stub with 200 ms latency and 5% 5xx | Outbox drains < 10 min; **0 duplicate sends** (stub counts message ids); dead-letter only after 8 attempts |
| **Webhook storm** | 200 rps status callbacks incl. duplicates and out-of-order | p95 < 100 ms; status forward-only |
| **Day-end** | 50 tenants close simultaneously while payments still arrive | No payment committed after close (QA-25) |
| **Soak** | 8 h at 30% of peak | No memory growth, connection-pool exhaustion or lease backlog |

Prerequisite: QA-37, a persisted signing key, so the system can run more than one replica.

### 4.5 Security testing

* **Authz matrix tests generated from the RBAC Matrix sheet.** Parse rows (Role × Module × View/Create/Edit/Delete/Approve/Refund-Discount/Export) into `ModuleGrant` fixtures, and map each controller operation to its `x-required-permission`. Generate a JUnit `@TestFactory` that calls every operation with every role and expects 2xx/4xx-not-403 when the permission is granted and 403 when it is not. Add the operator roles from *SaaS Operator Roles* the same way. This would flag QA-17 immediately, since the matrix says Owner Clinical Edit = N.
* **Token-misuse suite:** for each token type (`mfa_pending`, `mfa_setup_required`, `step_up`, owner pending, platform operator, refresh-as-access, expired, other tenant), call every authenticated endpoint and expect 401. This catches QA-01.
* **OWASP ZAP** baseline on every PR preview, and an authenticated full scan nightly with a context per role. Add a CORS policy test (QA-05) and header checks (CSP, HSTS, no token in URLs).
* **Multi-tenant isolation fuzzing:** a property-based test. Create tenants A and B, record every id produced in B, then replay every A-scoped endpoint with B's ids in path, query and body. Expect 404 or 400 and never 2xx; on success, nothing about B appears in the response. This catches QA-28 (FK-only references) and keeps checking new endpoints automatically. Extend `CrossTenantIsolationApiTest` from 5 list endpoints to every list endpoint. Add family/proxy variants: guardian A must not see sibling B (R-13).
* **Input fuzzing:** `limit`, `cursor`, dates and phone numbers in all formats (QA-19, QA-20), oversized free text (for example `presentingComplaint` has no `@Size`), and Unicode/Arabic in names and drug fields.
* **SAST and secrets:** CodeQL or Semgrep (Java/TS), gitleaks. **SCA:** OWASP Dependency-Check or Trivy for Maven. `npm audit` currently reports **10 vulnerabilities (9 high, 1 critical)**; gate on new high or critical findings.

### 4.6 Clinical safety testing (DCB0129-style)

Create a **Clinical Safety Case** with a hazard log owned by a named Clinical Safety Officer, and back every hazard control with automated tests. Initial hazard log entries derived from this review:

| Hazard | Cause (examples) | Controls | Verifying tests |
|---|---|---|---|
| H-01 Allergy not flagged → anaphylaxis | Brand names absent (Mox, Contramal, Cataflam), Arabic names, misspellings | Curated class list with CSO sign-off, versioned; brand dictionary per region | Parameterized catalogue ≥ 300 positive/negative pairs per class; **PIT mutation score ≥ 90%** on `AllergyDrugClasses` |
| H-02 Teratogen prescribed in pregnancy | Thin list (QA-16); status not recordable (QA-05) | Expanded list + brands; CORS fix; status staleness rule | J7 E2E; rule catalogue tests; mutation tests on `RxSafetyChecks` |
| H-03 Rx/notes on wrong patient | Double check-in (QA-06), transfer artifacts | Unique active entry per appointment; patient banner on consult | Concurrency tests; E2E J2 |
| H-04 Controlled drug without Rx | OTC ignores `is_rx` (QA-14) | Rx-link required for `is_rx` lines | API + E2E J10 |
| H-05 Unqualified person signs or overrides | Owner/break-glass `clinical:approve` (QA-17, QA-02) | Licence-based signing | Generated authz tests |
| H-06 Signed record altered | Code paths around V51 triggers | DB triggers (present) | Existing tamper tests ✔ |
| H-07 Wrong dose from stale weight | Weight from earlier visit | `recordedAt` shown (present) | Existing `VitalsApiTest` ✔; add a max-age warning test |
| H-08 Patient misses care due to wrong ETA | Delay not in ETA (QA-23), midnight board reset (QA-22) | Delay-aware ETA | Clock-injected tests |

Each release produces a **safety test report** linked to the hazard log, and a release with an open hazard (no verifying test) cannot ship.

### 4.7 Data-migration testing

* **Flyway checks in CI:** `flyway validate` and `migrate` on an empty DB **and** on an anonymized prod-like snapshot. Version gaps exist (V49, V56–59, V61–68, V70–71, V73–77, V80–82, V84 are missing). V51's header explains that out-of-order is disabled, so add a lint rule: a new version must be greater than every version applied in staging.
* **Expand/contract lint:** reject `DROP`, `ALTER … TYPE`, or `NOT NULL` without a default in the same release as the code change.
* **Round-trip tests** for backfills (for example the V37 default department and the V43 encounter id).
* **Legacy-import tests** (R-18) with deliberately dirty synthetic data: duplicates, mixed phone formats, Arabic/Latin name variants.

### 4.8 Accessibility, localization, device matrix

* **axe-core** through `@axe-core/playwright` on every E2E page. Gate on zero critical or serious violations; target WCAG 2.1 AA (keyboard-only receptionist flow, focus order, contrast).
* **Localization:** Arabic RTL (mirrored layout, numerals, dental-chart mirroring, R-14), Hindi (Devanagari rendering, long strings), Hijri date display, AED/SAR/QAR/INR formatting. Pseudo-localization runs in CI to catch hard-coded strings.
* **Device matrix:** reception desktop (Windows 10/11, Chrome/Edge, 1366×768), doctor tablet (iPad Safari, Android Chrome 10"), nurse phone (Android Chrome 6", iOS Safari), thermal receipt printer and A4/A5 Rx print. Low-bandwidth profile (R-15).

### 4.9 Chaos and resilience (WhatsApp provider)

Use a fault-injecting provider stub (Toxiproxy or WireMock) with these modes: provider down for 30 min, 429 storms, 10 s latency, partial 5xx, webhook delivery delayed or reordered or duplicated, and DB failover during an outbox batch.

Expectations:
* No message loss (dead-letter visible).
* No duplicate sends beyond the documented crash window.
* Queue/check-in latency unaffected, because messaging is asynchronous.
* Operator alert when the dead-letter count exceeds 0.
* A reminder that misses its window is cancelled rather than sent late. That logic needs adding.

### 4.10 UAT with pilot clinics

* **Pilots:** one GP clinic in India (Asia/Kolkata, GST, Schedule H1), one dental clinic in KSA (Arabic, VAT, SFDA), and, once QA-21 is fixed, one UAE clinic.
* **Entry criteria:** no open S1/S2 issues; all 15 E2E journeys green; safety case signed; Security Controls ≥ 90% verified.
* **Format:** two weeks shadow-mode (parallel with paper or the existing system), then two weeks live with on-site QA. Scripted scenarios from J1–J15 plus free exploratory sessions per role. Daily defect triage.
* **Exit criteria:** walk-in registration median < 45 s, checkout < 60 s (R-17), zero safety incidents, receptionist SUS ≥ 70, and day-close reconciliation exact for 10 consecutive days.

---

## 5. Quality gates & CI pipeline proposal

**Confirmed:** the repo has no `.github/workflows`, GitLab, Jenkins or other CI config. `find` shows only `application.yml` and `api/openapi.yaml`. The `Dockerfile` builds with `-DskipTests` and runs as root.

### 5.1 Pipeline stages (GitHub Actions or equivalent)

1. **Static (≤ 3 min, every push):**
   * Backend: `mvn -q verify -DskipITs` with Spotless/Checkstyle and SpotBugs.
   * Frontend: `npm ci`, `next typegen`, `tsc --noEmit`, `eslint`. Note: `tsc` fails before typegen because `LayoutProps` is generated by Next 16 typegen.
   * gitleaks; Semgrep/CodeQL.
2. **Unit (≤ 2 min):** backend pure unit tests; frontend `node --test tests/unit/*.mjs scripts/test-consult-flow.mjs` (the consult-flow tests are currently not run).
3. **Integration (≤ 6 min, parallel by class):** `mvn test` with Testcontainers **≥ 1.21** pinned in `pom.xml`; JaCoCo report.
4. **Migration:** `flyway validate`/`migrate` on an empty DB and on a seeded snapshot; expand/contract lint; version-monotonicity check.
5. **Contract:** springdoc spec → `oasdiff` against `api/openapi.yaml` (fail on breaking or undocumented changes) → Schemathesis against an ephemeral environment.
6. **Build & scan:** Docker image (non-root, distroless), Trivy image scan, OWASP Dependency-Check, `npm audit --audit-level=high`, SBOM (CycloneDX).
7. **Preview environment** per PR: ephemeral namespace, synthetic tenant seed, ZAP baseline, Playwright smoke (J1, J5, J6, J9) and axe.
8. **Nightly:** full E2E (15 journeys × 2 locales), PIT mutation, ZAP full, k6 regression profile, chaos suite, cross-tenant fuzz.
9. **Release:** signed safety test report, hazard log check, change log, manual CSO approval for any change under `clinical/` safety rules.

### 5.2 Required checks and thresholds

| Gate | Threshold |
|---|---|
| Backend line coverage (JaCoCo) | ≥ 75% overall, **≥ 90% line / 85% branch** for `clinical.RxSafetyChecks`, `AllergyDrugClasses`, `PrescriptionService`, `billing.CheckoutService`, `auth.*`; new code ≥ 80% |
| Mutation (PIT) | ≥ 85% mutation score on the safety and billing rule classes above, nightly; a drop greater than 2 points blocks |
| Contract | 0 undocumented operations; 0 Schemathesis server errors |
| Security | 0 new high/critical SAST/SCA; ZAP 0 high |
| a11y | 0 critical/serious axe violations on changed pages |
| Performance | k6 p95 budgets from §4.4 within +10% of baseline |
| Flaky-test policy | A test failing ≥ 2/20 reruns is quarantined with an owner and a 5-day SLA |

### 5.3 Test data management

* **Synthetic PHI only.** Use a generator (for example Synthea adapted to IN/GCC names, Aadhaar/Iqama-shaped *fake* ids with checksum disabled, and the phone ranges reserved for testing). Real patient data is never allowed in non-prod. Enforce this with a pre-load scanner for real-looking national ids and phone ranges outside test blocks.
* Seed builders mirror `ApiTestBase` (tenant-per-test). Provide named personas per RBAC role and per region.
* Preview environments are destroyed after 72 h. Backups of non-prod are not kept.

### 5.4 Defect severity definitions (clinical product)

| Severity | Definition | Examples | Release / SLA |
|---|---|---|---|
| **S1 Critical** | Possible patient harm; PHI exposure or cross-tenant leak; auth/MFA bypass; loss or corruption of clinical or financial records; regulatory breach | QA-01, QA-02, QA-03 | Blocks release; hotfix ≤ 24 h; CSO/DPO notified |
| **S2 High** | Safety control weakened or bypassable; incorrect money or tax; a core workflow blocked for a role with no reasonable workaround | QA-05, QA-06, QA-10–QA-17, QA-21 | Blocks release; fix ≤ 5 days |
| **S3 Medium** | Incorrect behaviour with a workaround; data-quality issue; hardening gap | QA-18–QA-33 | Fix in next sprint |
| **S4 Low** | Cosmetic; minor validation; defence-in-depth | QA-38–QA-42 | Backlog |

Orthogonal tags: **[CLIN-SAFETY]** requires CSO review and a hazard-log entry; **[PRIVACY]** requires DPO review.

---

## 6. Findings table

| ID | Sev | Area | Defect / Gap | Evidence | Repro | Recommended fix | Test to add |
|---|---|---|---|---|---|---|---|
| QA-01 | S1 | Auth | `mfa_pending` / `mfa_setup_required` tokens accepted on general endpoints → MFA bypass via break-glass | `SecurityConfig.java:76-93`; `AuthService.java:144-156, 376-388, 523-533`; `SessionRevocationJwtDecoder` | PIN login → use `challengeId` as Bearer on `POST /v1/auth/break-glass/activate` → full Owner tokens | Reject any token with a `purpose` claim except on its designated endpoint (custom converter); add `aud`/`typ`; separate keys | Token-misuse matrix: every token type × every endpoint ⇒ 401 |
| QA-02 | S1 | Auth/RBAC | Any staff can self-activate break-glass; grants full Owner rights, no step-up | `AuthController.java:126-130`; `AuthService.java:559-569` | Receptionist → activate → token has `staff:edit`, `billing:approve`, `clinical:approve` | Gate by role eligibility + step-up; grant clinical-read/emergency scope only; notify owner | Authz tests per role; scope assertion on break-glass token |
| QA-03 | S1 | Staff/RBAC | Self-delegation allowed; delegated/break-glass rights count as grantor rights → 30-day Owner | `RoleService.java:102-115` | After QA-02: `POST /v1/roles/delegations {staffId:self, Owner, +29d}` → 201 | Forbid self-delegation; compute grantor rights from base role only; require approve + step-up | Delegation escalation tests (self, chained, break-glass) |
| QA-04 | S2 | Auth/MFA | Enroll overwrites active TOTP secret without current factor; old recovery codes not revoked | `AuthService.java:299-307, 318-326`; `AuthRepository.java:114-116` | MFA user → `/mfa/enroll` → old authenticator fails | Pending-secret column; require current TOTP/step-up; delete old codes on confirm | Re-enroll keeps old secret until confirm; recovery-code rotation test |
| QA-05 | S2 [CLIN-SAFETY] | Clinical/CORS | CORS disallows `PUT` → UI cannot record pregnancy status or NKA | `SecurityConfig.java:59`; `PregnancyStatusController.java:39`; `AllergyController.java:83`; `PregnancyChip.tsx:57` | Browser on :3000 → record pregnancy → preflight 403 | Add PUT (and OPTIONS) to allowed methods; or same-origin proxy | CORS preflight test per verb; E2E J7 |
| QA-06 | S2 [CLIN-SAFETY] | Queue | Same appointment checked in repeatedly (incl. two receptionists); appointment stays `scheduled`, cancellable mid-visit | `QueueService.java:92-117, 134-136`; `V4__queue_appointments.sql:81` (no unique) | Check in same `appointmentId` twice → two tokens; then cancel appointment → 200, waitlist offered | `arrived` status set under lock; partial unique index on `appointment_id`; block cancel/reschedule once arrived | Concurrent check-in test (2 threads); sequential re-check-in ⇒ 409 |
| QA-07 | S3 | Queue | Check-in accepts appointment for another day | `QueueService.java:93-97` | Book tomorrow → check in today → 201 | Require appointment date = clinic today (configurable early window) | API negative test |
| QA-08 | S3 | Queue | Transfer leaves appointment `scheduled` forever | `QueueService.java:157-184` | Appointment → check-in → in_consult → transfer → appointment still scheduled | Mark appointment terminal/arrived on transfer | Transfer + appointment state test |
| QA-09 | S2 | Scheduling | Booking ignores doctor leave, working hours, slot alignment, past times → overlapping bookings | `AppointmentService.java:60-75`; `V4:68`; `AppointmentWriteRequest` | Leave today → book → 201; book 10:00 and 10:05 → both 201 | Validate against availability (leave, hours, grid, future); exclusion constraint on `tstzrange(start,end)` | Booking validation suite; concurrent overlapping bookings |
| QA-10 | S2 | Billing | Interim-paid invoice stays `paid` after later charges → balance uncollectable | `CheckoutService.java:137-148`; `CheckoutRepository.java:150-155`; `:237-240` | Dental flow: pay interim in full → add procedure → payment ⇒ 400 already-paid | Recompute status in `updateInvoiceTotals` | Two-checkpoint with interim payment test |
| QA-11 | S2 | Billing | Concurrent payments lose updates | `CheckoutService.java:233-247`; `CheckoutRepository.java:226-228` | Two parallel payments → paid < sum(payments) | Row lock or atomic `paid = paid + ?` | Concurrent payment test (CountDownLatch) |
| QA-12 | S2 | Billing | Discount permission bypass through client unit price / arbitrary charge codes; later discount silently replaces earlier | `CheckoutController.java:82-89`; `CheckoutService.java:283-290`; `CheckoutRepository.java:147-155` | No-discount role posts consult `unitPrice:0` → 201 | Catalogue pricing server-side; overrides require `billing.discount` + reason + audit; cumulative discounts | Price-override authz tests; discount persistence across checkpoints |
| QA-13 | S2 | Billing/Tax | Tax charged on pre-discount value; whole-unit round-off for SAR; line-level rounding | `CheckoutService.java:208-230` | ₹1,000 @18%, ₹100 discount → tax 180 (exp. 162) | Allocate discount to lines before tax; region-specific rounding (2dp for SAR) | Tax golden-file tests per region; PIT on `computeTotals` |
| QA-14 | S2 [CLIN-SAFETY] | Pharmacy | Rx-only (`is_rx`) items sold OTC / dispensed without matching Rx | `CheckoutService.java:171-199, 283-300` (no `is_rx` use) | OTC checkout of `isRx:true` item → 201 | Require linked signed Rx line for `is_rx` items; controlled-drug register | OTC Rx gate tests; visit dispense vs Rx reconciliation |
| QA-15 | S2 | Packages | Multiple refunds per instance → double credit notes; refund amount stale; approve race | `PackageInstanceService.java:289-330` | Two requests → two approvals → CN-1 & CN-2 | One open refund per instance; lock instance; recompute at approval; block redeem while pending | Double-refund + concurrent approve tests |
| QA-16 | S2 [CLIN-SAFETY] | Clinical rules | Pregnancy-risk list thin, no brands; allergy brand gaps; default-locale lowercasing | `RxSafetyChecks.java:21-23, 100-103`; `AllergyDrugClasses.java:50-98` | Female 28y, "Isotroin 20mg" → no warning | CSO-owned versioned drug dictionary per region incl. brands; Locale.ROOT | Clinical rule catalogue (≥300 cases) + PIT |
| QA-17 | S2 [CLIN-SAFETY] | RBAC | Owner seeded with `clinical:approve` (contrary to RBAC Matrix) → can sign Rx/override severe allergy | `ProvisioningStepRunner.java:194`; `PrescriptionService.mayActAsVisitDoctor` | Owner (non-clinician) signs another doctor's Rx with override → 200 | Align seed with matrix; signing requires clinician licence | RBAC-matrix-generated tests |
| QA-18 | S3 | Clinical | Note sign/amend by any `clinical:edit` (nurse); no `signed_by`; not audited | `NoteService.java:66-93`; `NoteController.java:47-58` | Nurse signs doctor's note → 200 | Same rule as Rx (visit doctor or approver); add signed_by + audit | Note sign authz tests; amendment tests (currently none) |
| QA-19 | S3 | API | Unbounded/invalid `limit` → 500s and bulk PHI dump | `AppointmentService.java:78-91` (+Staff/Patient/Timeline/Audit/Fleet/Billing services) | `GET /v1/appointments?limit=0` → 500; `limit=1000000` → all rows | `@Min(1) @Max(100)` + clamp; shared `PageRequest` type | Schemathesis + boundary tests |
| QA-20 | S3 | Patients | Phone numbers not validated; UAE/QA mis-prefixed; trunk-0 kept | `PhoneNumbers.java:15-38`; `PatientWriteRequest` | Register phone "abc" → 201 | libphonenumber, per-region validation, reject invalid | Phone matrix tests IN/AE/SA/QA |
| QA-21 | S2 | Product/Region | Only IN/KSA regions — UAE/Qatar get SAR, SFDA, KSA phone, Riyadh TZ | `V1__staff_auth_core.sql:23`; `CheckoutService.java:306-309`; `ClinicClock.defaultZoneFor` | Provision Dubai clinic → invoices in SAR | Region model (country, currency, tax, regulator, TZ) | Region-parameterized suites |
| QA-22 | S3 | Queue/TZ | Midnight splits evening sessions; board empties; tokens restart; no cross-midnight hours | `QueueService.java:90, 122`; `V4` CHECK end>start | Check-in 23:50 and 00:10 → two queues, token 1 twice | Session-based "clinic day" (configurable day-start hour) | Clock-injected midnight tests in Kolkata/Dubai |
| QA-23 | S3 | Queue | Delay never expires, no max, ignored by ETA; concurrent announce → 500 (suspected) | `ScheduleRepository.java:153-174`; `QueueService.java:218-225`; `DelayAnnounceRequest` | Announce yesterday → still active today | Day-scope delays; @Max; ETA adds delay; handle unique violation | Delay/ETA tests |
| QA-24 | S3 | Waitlist | Past slots offered; direct booking ignores live offers; lazy expiry | `WaitlistService.java:91-113, 136-159`; `AppointmentService.book` | Cancel past appt → offer created → accept books past slot | Skip past slots; book() checks `isSlotOnLiveOffer`; scheduled expiry job | Waitlist edge tests |
| QA-25 | S3 | Day close | Reopened past day can never be re-closed; close/payment race (suspected) | `DayCloseService.java:62-104` | Reopen yesterday → no way to close | Allow close of reopened date; advisory lock tenant-day | Reopen/re-close + concurrent payment-during-close |
| QA-26 | S3 | Auth | Break-glass deactivate: no authz, no session revoke, audit on unknown id; effective ~45 min | `AuthController.java:132-136`; `AuthService.java:391-397` | Any staff deactivates others' grant → 204 | `staff:edit` or self; revoke sessions minted under grant | Break-glass lifecycle tests |
| QA-27 | S3 | Auth | `/auth/unlock` unthrottled PIN oracle | `AuthService.java:414-420` | 10,000 unlock attempts → no lockout | Rate-limit + lockout counter shared with login | Brute-force test |
| QA-28 | S3 [PRIVACY] | Tenancy | FK checks bypass RLS → cross-tenant patient/staff references | `V4:45,82`; `AppointmentService.book`; `QueueService.checkIn`; `RoleService.createDelegation` | Tenant A books with tenant B patient UUID → 201 | Composite `(tenant_id,id)` FKs; service existence checks | Cross-tenant id-replay fuzzer |
| QA-29 | S3 | Messaging | Outbox updates not lease-fenced; batch×timeout > lease → duplicate sends (suspected, multi-instance) | `OutboxRepository.java:29-38`; `application.yml:66-67`; `MetaWhatsAppClient.java:20-21` | 2 instances + slow stub → same message id sent twice | Fence by attempts/status; lock message row; smaller batch | Chaos test with slow provider stub |
| QA-30 | S3 [PRIVACY] | Messaging | Inbound WhatsApp (incl. STOP) dropped | `WhatsAppWebhookController.java:87-89` | Patient replies STOP → still messaged | Store inbound; opt-out keyword handling → consent withdraw | Webhook inbound tests |
| QA-31 | S3 | Scheduling | No way to mark a never-arrived appointment no-show | `AppointmentController.java` mappings | – | `POST /appointments/{id}/no-show` or auto job | API test |
| QA-32 | S3 | Queue/Billing | `completed` via PATCH bypasses checkout; no_show from in_consult | `QueueService.java:46-59, 127-138` | PATCH checkout_pending→completed with queue:edit → 200 | Only billing completes; restrict no_show | Transition matrix tests |
| QA-33 | S3 | Platform | Seat-limit quota (NB-273 Done) not enforced | grep: `seat` only in `PlanRepository/PlanService` | Invite staff beyond plan seats → 201 | Enforce on invite/activate | Quota test |
| QA-34 | S2 | Test infra | Testcontainers 1.19.8 incompatible with Docker Engine 29 (API 1.32 < 1.40) → 459/560 errors | `mvn-test.log` ("client version 1.32 is too old") | Run `mvn test` on Docker 29 | Pin Testcontainers ≥ 1.21 in pom | CI on current Docker |
| QA-35 | S3 | Contract | OpenAPI drift: 139/264 ops undocumented; 6 spec ops missing (pin-reset path mismatch) | `api/openapi.yaml` vs controllers (script) | – | springdoc + oasdiff gate | Contract stage |
| QA-36 | S3 | Process | No CI; Docker image `-DskipTests`, root user; `npm audit` 10 vulns (1 critical); tsc needs `next typegen`; consult-flow tests not wired | repo root; `Dockerfile`; `npm-ci.log`; `fe-tsc.log` | – | §5 pipeline | – |
| QA-37 | S3 | Auth/Ops | JWT keypair regenerated per boot → logout on restart, no multi-replica | `JwtConfig.java` | Restart → refresh fails | Persisted key (KMS/Vault), rotation with `kid` | Restart/refresh test |
| QA-38 | S4 | Test infra | Wall-clock dependence; flaky 23:40–00:00 UTC windows; assertion removed in availability test | `AppointmentApiTest.nearFutureSlot`; `WaitlistApiTest`; `ScheduleApiTest:89` | Run suite at 23:50 UTC | Inject `Clock`; fixed instants | Clock-based tests |
| QA-39 | S4 | Pharmacy | No batch/expiry/FEFO; no OTC return/credit note | `V25__pharmacy.sql` header; billing has no return path | – | In-house mode epic; returns flow | – |
| QA-40 | S4 [PRIVACY] | Tenancy | Tables without RLS; USING-only policies allow sibling writes at DB layer | migrations scan; `V19__clinical_notes.sql:28-31` | – | RLS + `WITH CHECK`; consider FORCE RLS | RLS policy assertions test |
| QA-41 | S4 | Auth | Break-glass passes User-Agent as device label | `AuthController.java:128` | – | Pass device header | – |
| QA-42 | S4 | API | Unhandled DB exceptions (EmptyResult/DataIntegrity) → 500 | `GlobalExceptionHandler`; `QueueRepository.findCheckInDepartment` | Check-in with unknown doctorId → 500 | Map to 400/404/409 | Schemathesis `not_a_server_error` |

---

## 7. Results of the automated runs

### 7.1 Backend (`mvn test`)

| Run | Log | Result | Root cause |
|---|---|---|---|
| #1 | `scratchpad/mvn-test.log` | **Tests run: 560, Failures: 0, Errors: 459, Skipped: 0 — BUILD FAILURE** | Environmental. Testcontainers 1.19.8 (managed by the Spring Boot 3.3.4 BOM) negotiates Docker API **1.32**; Docker Engine 29 rejects it ("client version 1.32 is too old. Minimum supported API version is 1.40"). Every test class extending `ApiTestBase` fails in its static initializer (container start). The 101 non-erroring tests are pure unit tests or Spring contexts that never touch the container. **Real finding QA-34**: any developer or CI runner on current Docker sees the whole suite fail. Fix: pin `org.testcontainers` ≥ 1.21.x. |
| #2 | `scratchpad/mvn-test2.log` (with `~/.docker-java.properties api.version=1.44`) | **Tests run: 560, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS, 1:31 min** | – |

Run #2 details:
* 501 `@Test` + 59 parameterized invocations = 560.
* Summed per-class time is ≈ 88 s.
* Slowest classes: `PackageApiTest` 17.1 s (it is first and pays for container start + Flyway + context), `AuthApiTest` 6.0 s (Argon2id hashing), `PrescriptionApiTest` 5.3 s (lock-wait tests).
* Only 5 Spring contexts started, so caching works.
* There were no failures, so no failure root-cause analysis is needed. The 288 WARN/ERROR log lines are expected negative-path logging (illegal transitions, 4xx `ApiException`s).

### 7.2 Frontend

| Check | Result | Notes |
|---|---|---|
| `npm ci` | OK | **10 vulnerabilities (9 high, 1 critical)** reported by npm audit (QA-36) |
| `eslint` | OK | – |
| `tsc --noEmit` | **Failed before build** (`app/layout.tsx(19,50)` and `app/platform/layout.tsx(4,54)`: `Cannot find name 'LayoutProps'`); **passes after `next build`** | `LayoutProps` is a global type generated by Next 16 typegen. CI must run `next typegen` (or build) before `tsc`. |
| Unit (`node --test tests/unit/*.test.mjs`) | **9/9 pass**, 217 ms | Only `vitals.test.mjs`; `scripts/test-consult-flow.mjs` (8 tests) is not run by any script |
| Permission rendering check | OK ("no disabled-with-permission-reason buttons found") | Static scan only |
| `next build` | OK | 40 routes built |

### 7.3 Interpretation

All automated checks pass once the environment is fixed, yet §3 lists 42 defects or gaps, 18 of them S1/S2. The suite is high quality for what it covers: real RLS, DB-level invariants, careful clinical-rule tests. What it misses is a set of whole categories:
* concurrency and multi-actor scenarios;
* token misuse and generated authorization tests;
* browser-level integration (CORS);
* multi-checkpoint financial sequences;
* region breadth (UAE/Qatar);
* clinical content completeness.
The strategy in §4 and the gates in §5 target exactly those categories.

---

### Appendix A — Scripts used (scratchpad, not committed)

* `/tmp/claude-0/-home-user-Nabd/b7763512-fd1b-5a01-8814-88ca57a93dd5/scratchpad/qa/ep3.py`: endpoint enumeration plus test-path matching; output in `…/qa/eps.json`.
* Inline Python (openpyxl) over the plan workbook: Backlog status counts, Requirement Coverage roll-up, RBAC Matrix, Risks, Security Controls.
* Inline Python over `db/migrations`: tables without RLS and the OpenAPI-vs-controller diff.
