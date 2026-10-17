# Requirements coverage audit

**Date:** 17 Oct 2026
**Tracker:** `docs/Nabd-Development-Tracker.xlsx`. The row-level detail is in the "Requirement coverage" sheet.

## 1. What was checked

We traced 1,165 requirements to three places: the code, the prototype and the tracker tasks.

| Source | Rows | What it covers |
|---|--:|---|
| FRD `Clinic_SaaS_IN_KSA_FRD_v1` | 412 | Every row of all 11 sheets |
| Founder brief | 92 | 53 items the founder asked for directly, plus 39 that a standard HMS implies |
| Old plan `Nabd HMS - Implementation Task Plan v2.xlsx` | 661 | Backlog (359 rows), Requirement Coverage (185), RBAC Matrix (50), Security Controls (67) |

- **Code:** checked against `docs/strategy/04-current-product-inventory.md`, plus searches of the code.
- **Prototype:** checked against the screen and overlay ids in `prototype/js/modules`.
- **Tracker:** checked against tasks T-001 to T-295. A row only counts as Covered if a task's acceptance criteria contain the work.

The audit proposed 116 tasks: 53 from the FRD and 63 from the brief and old plan. We merged them as follows:

- **Combined across sources:** where two proposals described the same work, they became one task. One task can cite FRD ids, brief ids (B-xx) and old-plan ids (NB-xxx) together.
- **Merged into existing tasks:** where an existing task already did part of the work, we extended that task instead of adding a new one.
- **Result:** 89 new tasks (T-296 to T-384). Existing Task IDs did not change. 19 existing tasks had their scope extended.
- **Extended tasks:** T-018, T-096, T-097, T-122, T-123, T-148, T-151, T-160, T-166, T-190, T-191, T-217, T-223, T-227, T-232, T-239, T-244, T-268, T-290.
- **Mapping:** every proposal now maps to a final task. No Task ID is duplicated.

## 2. Coverage before and after the merge

| Source | Covered | Covered (planned) | Partly covered | Missing | Conflict | Deliberately deferred |
|---|--:|--:|--:|--:|--:|--:|
| FRD, before | 154 | - | 83 | 99 | 57 | 19 |
| FRD, after | 154 | 179 | 2 | 1 | 57 | 19 |
| Founder brief, before | 60 | - | 15 | 14 | 0 | 3 |
| Founder brief, after | 60 | 29 | 0 | 0 | 0 | 3 |
| Old plan v2, before | 386 | - | 143 | 95 | 3 | 34 |
| Old plan v2, after | 386 | 230 | 6 | 2 | 3 | 34 |
| **Total, before** | **600** | - | **241** | **208** | **60** | **56** |
| **Total, after** | **600** | **438** | **8** | **3** | **60** | **56** |

"Covered (planned)" means a task now covers the gap, but the work is not built yet.

Covered plus covered (planned) went from **51%** to **89%**.

All 53 items the founder asked for directly are now covered or planned. The one exception is the patient app: the founder asked for it "soon", but it is deferred to Phase 3 (DEC-41).

**Effort added: 915 person-days**

| Phase | New tasks | Person-days | Priority split |
|--:|--:|--:|---|
| 1 | 35 | 275 | 25 P1, 10 P2 |
| 2 | 35 | 362 | 3 P1, 27 P2, 5 P3 |
| 3 | 7 | 97 | all P3 |
| 5 | 12 | 181 | all P3 |

The tracker total is now 384 tasks and 3,884 person-days. Phase 1 alone grows from 1,067 to 1,342 person-days. This is risk R-34: the audit adds a lot of scope and could dilute the pilot.

## 3. Top gaps closed

1. **Specialty workspaces** (FRD Doctor Specialization Profiles, brief B-75, old E12):
   - Framework: T-298.
   - Dermatology, dental and GP (Phase 1): T-299, T-300, T-301.
   - Paediatrics, ENT/ortho, gynaecology, AYUSH, derm Phase 2 and dental plans: T-335 to T-340.
2. **Recall engine,** which the FRD calls "the retention engine" (PAT-20, B-73, NB-116): T-296.
3. **WhatsApp channel:**
   - Reception shared inbox (NB-018/197): T-312.
   - Nurse triage inbox: T-344.
   - SMS fallback with India DLT registration (the most serious compliance hole): T-309.
   - Sender quality monitoring: T-307.
   - Number-change re-verification: T-317.
   - "My records & privacy" menu (B-02): T-331.
4. **Package money features:**
   - Real cards and nudges: T-304.
   - Per-line tax split: T-333.
   - Instalments: T-347.
   - Transfer and upgrade: T-348.
   - Sell in WhatsApp: T-346.
   - Reports: T-324.
5. **Price master** (per-doctor fees, follow-up window, emergency tier, change log, wizard starter lists): T-306. **Discount approval:** T-325. **Gateway refunds:** T-342. **Estimates:** T-341.
6. **Go-to-market:**
   - Self-serve trial with demo clinic and plan change: T-320.
   - Signed DPA required before any patient data: T-316.
   - Full data export and deletion certificate: T-311.
   - Role presets: T-305.
   - Licence expiry alerts: T-310.
   - Help and training: T-318.
7. **Founder asks:**
   - Light theme in the product, using the prototype's tokens: T-313.
   - Clinic-branded booking site and widget: T-352.
   - Web booking management: T-368.
   - Discovery on the shared Nabd WhatsApp number: T-369.
   - AI prescription suggestions: T-370.
   - Lab-only tenant: T-353.
   - Workflow presets: T-351.
   - Doctor payouts: T-355.
   - Feedback and complaints: T-356.
   - Teleconsultation: T-366.
8. **Multi-branch lite for 2-3 branches** (FRD SYS-24): T-332.
9. **Standard hospital modules,** all Phase 5 and behind DEC-15: discharge (T-374), ER (T-375), radiology (T-376), blood bank (T-377), stores (T-378), CSSD (T-379), dietary (T-380), rostering (T-381), medical records (T-382), birth and death registers (T-383) and ambulance (T-384).
10. **KSA items with no task at any phase** (PDPL pack, prayer-window sends, Mada/STC Pay, gender-of-doctor default): T-373.

Each new task names its prototype design reference in Notes, for example `doctor.specialty`, `er.board` and `owner.pricemaster`. T-327 completes those prototype screens and removes three things in the prototype that contradict the FRD:
- the "kiosk" label;
- the 5% package refund fee;
- clinic-set data retention.

## 4. Conflicts that need a founder decision

All of these are Open in the Decisions sheet, with options, a recommendation, an owner and a needed-by date. Together they account for 60 Conflict rows.

| ID | Decision | Recommendation | Needed by |
|---|---|---|---|
| DEC-27 | KSA at MVP (FRD) vs Phase 5 (plan) | India first; supersede the FRD rows. Use "KSA lite" only if a KSA customer is signed | 30 Oct 2026 |
| DEC-28 | Hospital and enterprise re-scope vs the FRD removals | Deliberate re-scope: multi-branch lite first, then chains | 27 Nov 2026 |
| DEC-29 | "Corporates" is not defined anywhere | Founder to define it (chains, or employer billing) | 13 Nov 2026 |
| DEC-30 | Lab persona (removed by the FRD) | Lab module behind an entitlement; clinics attach outside reports | 1 Feb 2027 |
| DEC-31 | Insurance desk depth | Claim pack now; claims rails bought through middleware | 11 Jan 2027 |
| DEC-32 | Pharmacy model sequencing | External mode as the clinic default; standalone pharmacy recorded as a new segment | 25 Jan 2027 |
| DEC-33 | ABDM and FHIR earlier than the FRD | Keep, with ABHA opt-in and off by default | 11 Jan 2027 |
| DEC-34 | Per-doctor or per-visit pricing vs the FRD flat per-clinic price | A/B test per-doctor vs per-clinic; drop per-visit | 25 Jan 2027 |
| DEC-35 | WhatsApp markup vs fair-use allowance | Allowance included; markup only above it | 16 Nov 2026 |
| DEC-36 | 99.9% vs 99.5% uptime | Contract 99.5%; keep 99.9% as the internal target | 14 Dec 2026 |
| DEC-37 | Offline at MVP | Pull offline check-in into Phase 1 if a tier-2 clinic is in the pilot | 14 Dec 2026 |
| DEC-38 | Kiosk ban vs the prototype's kiosk | Phone/QR self check-in only | 13 Nov 2026 |
| DEC-39 | SSO (forbidden by the old plan) vs T-283 | Hospital tier only, in Phase 5 | 1 Nov 2027 |
| DEC-40 | "Records kept in hospital premises": on-premise or custodian model | Custodian model on regional cloud | 27 Nov 2026 |
| DEC-41 | Patient app "soon" vs Phase 3 | Keep Phase 3; confirm with the founder | 30 Nov 2026 |
| DEC-42 | AI prescription suggestions vs flag-and-cite only | Opt-in, labelled suggestions in Phase 3 | 3 May 2027 |
| DEC-43 | Referral fees vs fee-splitting ethics | Revenue share only; referral fees off | 1 Feb 2027 |

## 5. Still open and deliberately deferred

**Deliberately deferred (56 rows).** These are unchanged. They are Could items dropped under DEC-13, and KSA, IPD/OT, campaign and patient-app items deferred by DEC-05, DEC-07, DEC-12 and DEC-15.

**Still open (11 rows):**

| Rows | Why open | Where it is tracked |
|---|---|---|
| Inside sales: SCS-02, NB-277 | Sales motion, not product | Risk R-31 |
| Support SLA: NFR-19, NB-347, SSA-08 | Operations commitment | Risk R-30 |
| Offline queue at MVP: SYS-05 | Waits for a decision | DEC-37 |
| Old-plan coverage rows CAD-05, SYS-02, SYS-04, NFR-06 | Only the deferred part remains: Ramadan mode, campaigns, SSO | - |

**New risks** (R-29 to R-37):
- regulatory watch;
- support SLA;
- inside sales;
- DLT;
- two diverging requirement baselines;
- added scope;
- prototype drift;
- ZATCA deadlines;
- instalment and referral-fee rules.

**Keeping this current:** T-329 keeps the coverage sheet up to date. It is signed off at each phase gate.
