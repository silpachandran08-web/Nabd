# 05 — Gap Analysis & Product Roadmap

**Owner:** Head of Product · **As of:** 17 Oct 2026 · **Status:** v1, for founder review
**Inputs:** S01 = `01-market-competitors-regulation.md`, S03 = `03-personas-interviews-requirements.md`, S04 = `04-current-product-inventory.md`, plus engineering reviews 01–05 (finding IDs `F-` backend, `DP-` data, `FE-` frontend, `QA-` QA). Naming and brand are covered in `02-brand-and-naming.md`, which recommends replacing "Nabd" (high conflict risk) with a cleared name, Intizam being the top candidate; this document keeps "Nabd" as the working name until the founder decides.

**Conventions.** Epics are `EP01…EP42` (§6). Releases map to phases: **R0** Make it safe, **R1** India Clinic pilot, **R2** Clinic GA + Pharmacy + Lab, **R3** Patient app, aggregator, sharing, AI, **R4** UAE, **R5** Hospital/chain + KSA. Week 0 is Mon 26 Oct 2026, so "month 4" ≈ late Feb 2027 and "month 12" ≈ late Oct 2027. Effort: **S** ≤ 2 engineer-weeks, **M** ≤ 6, **L** ≤ 16, **XL** > 16.

---

## 1. Executive summary

**Where Nabd stands.** Nabd is a well-engineered, staff-facing, single-facility OPD clinic system with a strong platform console (S04 §1). The internal loop works end to end: book or walk in → token → visit pipeline → vitals → SOAP note → safety-checked, DB-immutable prescription → checkout → day close. Queue mechanics (score 4), clinical record integrity (4), prescribing (4), packages (4) and the operator console (4) are real assets. But everything the vision puts in front of the patient scores 0. WhatsApp is outbound plumbing with no business caller (1). Billing (2), pharmacy (2), localization (1) and interoperability (0) are thin. Of the 109 Must and Should features in S03 §5, **2 are built strong, 19 are built but need improvement, 26 are partial and 62 are not built** (§3). Of the 72 Musts, 33 are not built.

Engineering reviews also show that **the product cannot hold real patient data yet**. A PIN alone can reach 30 days of Owner access (F-01, QA-01…03). RLS is probably inert in deployed environments (F-02). Money and queue writes race (F-04/05, QA-06/10/11). There is no CI or observability (QA-36, F-14). These are fixable in about four weeks (Phase 0).

**The five biggest gaps against the vision**

| # | Gap | Why it matters | Fixed in |
|---|---|---|---|
| G1 | **No patient channel, and the time promise is not credible yet.** No inbound WhatsApp or bot, no secure links, no patient-visible queue. The ETA is statistically wrong (DP-08) and screens poll every 15 s. | Time is the core design axis (S03 north star). Today no patient saves a minute. | R1 |
| G2 | **Trust foundation.** Critical auth and tenancy defects, no consent capture (absence of consent = consent), one global key, one undifferentiated region (IN/KSA only, QA-21). | Blocks any pilot with real PHI, DPDP (May 2027), and every GCC sale. | R0–R2 |
| G3 | **Money and pharmacy compliance.** Flat tax %, tax on pre-discount amount (QA-13), no refunds or GST invoices, no batch/expiry/FEFO, Rx-only drugs sold OTC (QA-14), no standalone pharmacy. | Billing and pharmacy are where Indian clinics feel daily pain and where expansion revenue lives. | R1–R2 |
| G4 | **Identity and sharing.** Patients and doctors exist per facility only. No Person or Practitioner, no consented sharing, no ABDM or FHIR. | This is the differentiator (S01 §2.3) and a prerequisite for hospitals and chains. | R2–R3 |
| G5 | **Market plumbing.** No i18n, Arabic/RTL, Indian languages, e-claims, HIE, e-invoicing, labs or hospital modules. | It gates the UAE (R4), KSA and hospitals (R5). | R1 (i18n base), R4–R5 |

**Recommended launch market and beachhead.** Follow S01 §5.1–5.2: **India first, Bengaluru and Hyderabad. The beachhead is solo and 2–5-doctor specialty clinics with 40 or more walk-ins a day** (paediatrics, GP, ENT, dermatology, orthopaedics, gynaecology). Personas P1, P2, P7 and P11. The wedge is WhatsApp booking + live ETA range + "leave now" + self check-in, wrapped around the clinical loop we already have, then billing, then pharmacy. The **UAE (Dubai/Sharjah polyclinics) is second, months 9–15. KSA is third, from month 15.** The current code treats KSA as a launch region; we deprioritise it but generalise the region model now so KSA is not lost (EP09).

**What "launch-ready" means (India GA gate, end of Phase 2).** All of the following must hold:
1. No open S1/S2 defects. Security Controls ≥ 90 % verified (QA §4.10). An external pen test has passed. FORCE RLS is verified on the production topology by a CI job.
2. A Clinical Safety Case is signed by the CSO. Every hazard has a verifying test (QA §4.6).
3. DPDP readiness before 13 May 2027: notice, consent ledger, erasure with 48 h notice, 72 h breach runbook, 1-year logs. CERT-In 6 h runbook. Indian hosting.
4. Moments that matter are met at pilot clinics (S03 §6.1): booking median < 60 s, ETA within ±10 min for ≥ 80 % of visits, waiting-room median < 15 min, walk-in registration < 45 s, repeat Rx < 30 s, dispense < 20 s, delay broadcast < 60 s.
5. Operational proof: day close exact for 10 consecutive days at every pilot site; receptionist SUS ≥ 70; ≥ 60 % of pilot clinics convert to paid.

---

## 2. Vision, positioning and product family

**Vision.** *Every visit runs on time you can see.* Nabd gives patients back the hours they lose in waiting rooms, and gives clinicians back the hours they lose to desks and keyboards. One platform carries a doctor from a one-room clinic to a hospital chain, across India and the GCC.

**Positioning statement.** *For* doctor-owners and clinic teams in urban India and the GCC who lose hours a day to unpredictable queues, phone calls and re-keying, *Nabd is* a WhatsApp-first clinic operating system *that* tells every patient honestly when to leave home, and lets the clinic go from booking to Rx to pharmacy to bill without typing anything twice. *Unlike* Practo Ray, Eka and HealthPlix (appointments and EMR without a live ETA) or TOKENN (a queue point tool with no clinical depth) (S01 §2), *Nabd* combines a consult-duration-aware ETA with a governed WhatsApp channel and a safety-checked clinical loop, and grows into pharmacy, lab, consented sharing and hospital modules on the same identity.

**Product family**

| Product | Who | Core promise | Built on (today → target) |
|---|---|---|---|
| **Nabd Clinic** | Solo to 30-doctor OPD | Queue + ETA, front desk, consult, e-Rx, billing | Today's core (S04 §3.6–3.13) + EP06–EP24 |
| **Nabd Pharmacy** (also standalone) | In-clinic counters, independent chemists, community pharmacies | e-Rx QR dispense, FEFO, GST/VAT, H1 / controlled registers, offline | Hybrid pharmacy (score 2) → EP21, EP25, EP26. A facility type + entitlement on the same core, not a separate service (F-11, backend review §10.1) |
| **Nabd Lab** | In-house and partner diagnostic labs | Digital orders, barcodes, report links, critical-value escalation | Not built → EP27, later the HL7/ASTM adapter |
| **Nabd Connect** (patient) | Patients, guardians, NRI caregivers | WhatsApp booking, live status, records via secure links; later an Expo app and web aggregator | Not built → Patient Access Edge (EP12, EP34, EP35) |
| **Nabd Hospital** | Nursing homes, hospitals, chains | Multi-facility, MPI, IPD/OT, call centre, SSO | Org hierarchy plumbing → EP40–EP42 |
| **Nabd Platform** | Nabd operators, IT/compliance buyers (P16) | Provisioning, residency cells, consent ledger, audit, entitlements | Console (score 4) → Control Plane extraction, EP09, EP31 |

**Packaging and pricing (hypotheses to test; S01 §5.3 anchors).** India prices include GST (S01 §3.1: the 18 % is a real cost to the clinic). Per-doctor means per active prescriber.

| Tier | India | UAE | KSA | Entitlements |
|---|---|---|---|---|
| **Solo Starter** | ₹0, ≤ 300 visits/month | — | — | Queue + ETA (range/position modes), WhatsApp booking on the shared Nabd number with clinic branding, self check-in QR, basic e-Rx with QR, 1 user |
| **Solo Pro** | ₹999–1,499 per doctor/month, **or** ₹1–2 per completed visit (A/B test) | AED 199–249 | SAR 249–299 | Starter + unlimited visits, own WhatsApp number, AI safety checks, favourites/repeat Rx, GST/VAT billing and UPI/card links, daily WhatsApp summary, ABDM M1 (IN), 2 users |
| **Clinic** (2–9 doctors) | ₹2,500–4,000 base + ₹799–999 per doctor | AED 499 base + 199–349 per practitioner | SAR 599 base + 249–399 | Pro + front desk (keyboard-first, exceptions inbox, multi-doctor board), lobby display, in-clinic pharmacy (basic), packages, reports, waitlist backfill; GCC: eligibility + e-claims add-on |
| **Polyclinic** (10–30 doctors, nursing homes) | ₹9,000–15,000 base + per doctor *(estimate)* | AED 1,500 base + per practitioner *(estimate)* | SAR 1,800 base + per practitioner *(estimate)* | Clinic + multi-station journeys, vitals/triage station, internal referrals, lab module, call-centre console, role templates, audit UI, SIEM export |
| **Hospital / Chain** | Quote (per bed / per module) | Quote | Quote | Polyclinic + chain MPI, central config with site overrides, chain dashboard, SSO, customer-managed keys, IPD/OT (R5), NABH/ADHICS packs |
| **Pharmacy** (standalone) | ₹750–1,500 per store/month | AED 249–399 | SAR 299–449 (+ RSD) | POS, batches/FEFO, GST/VAT, H1/controlled registers, e-Rx QR ingestion, paper-Rx photo, refill reminders, PO/GRN, offline billing |
| **Add-ons** | Lab ₹1,000–2,500; Family Care (B2C caregiver) ₹300/month *(P12 hypothesis)* | Lab AED 299+ | Lab SAR 349+ | WhatsApp fees passed through at cost + 10–20 % |

Plans carry no module entitlements today and seat limits are not enforced (S04 §3.3 NB-058, QA-33), so **entitlements are a Phase 1 prerequisite for pricing** (EP23).

---

## 3. Gap analysis matrix (all Must and Should items from S03 §5)

**Status:** **BS** built strong · **BNI** built, needs improvement · **P** partial · **NB** not built. Status comes from S04. Market-specific items are tagged in the Release column.

### 3.1 Patient — WhatsApp & aggregator

| ID | Feature | Pri | Status | Gap (evidence) | Eff | Dependency | Rel |
|---|---|---|---|---|---|---|---|
| PW-01 | WhatsApp booking bot | M | NB | Inbound dropped (S04 §3.10); E18 0/20; no conversation state | XL | EP07, EP10, EP12 | R1 |
| PW-02 | Clinic-owned or shared branded number | M | NB | One global WABA; provisioning is a logging stub (F-32) | L | EP07, BSP contract | R1 shared / R2 own |
| PW-03 | Live position + ETA range, push on change | M | P | Staff-side naive ETA only | L | EP06, EP11 | R1 |
| PW-04 | "Leave now" nudge | M | NB | No travel buffer, no notifier | M | EP11, EP13 | R1 |
| PW-05 | Confirm/Reschedule/Cancel at T-24 h/T-2 h | M | NB | No business sender, no scheduler (backend review §9) | M | EP07, EP13 | R1 |
| PW-06 | Family under one phone | M | P | Guardian/minor exists; one-number family not built (NB-080/083) | M | EP12 (persons/links) | R1 |
| PW-07 | Pre-visit intake (WhatsApp Flows) | S | NB | No intake model, no uploads | M | EP19, EP29 | R2 |
| PW-08 | Voice-note intake | S | NB | No attachment storage anywhere | M | EP19 | R2 store / R3 transcribe |
| PW-09 | OTP-protected secure links (Rx, invoice, report) | M | NB | Print only; "sharing not available yet" | M | EP19, EP08 | R1 |
| PW-10 | Payment links, payable by caregiver | S | NB | Recording only, no gateway (NB-168) | M | EP20, F-25 idempotency | R1 UPI / R4 card |
| PW-11 | Web aggregator | S | NB | E19 0/7 | L | EP35, CDN/OpenSearch (DP §5.7) | R3 |
| PW-12 | Clinic-controlled visibility, no competitor promotion | M | NB | Not designed | S | EP12 policy; EP35 enforcement | R1 / R3 |

### 3.2 Booking & queue engine

| ID | Feature | Pri | Status | Gap (evidence) | Eff | Dependency | Rel |
|---|---|---|---|---|---|---|---|
| BQ-01 | Hybrid slots/tokens per session | M | BNI | Both exist, but the cap is shared; no per-session mode; overlap guard only on identical start (F-06) | M | EP02, EP10 | R1 |
| BQ-02 | Auto-accept rules | M | NB | No "requested" state (S04 §3.1) | M | EP10 | R1 |
| BQ-03 | ETA engine with confidence range | M | BNI | mean(invoice − check-in) × count includes waiting; 2.25 s per call (DP-08) | L | EP06, EP11 | R1 |
| BQ-04 | ETA display modes | M | NB | — | S | EP11 | R1 |
| BQ-05 | Walk-in/booked interleave | M | P | Walk-ins work; no ratio or reserved slots | M | EP10 | R1 |
| BQ-06 | Priority override + pause/resume | M | BNI | Priority + ack built; no pause; no neutral patient update | S | EP14 | R1 |
| BQ-07 | "Late by X" + mass notification | M | P | Staff delay built; no broadcast; never expires; ETA ignores it (QA-23) | M | EP07, EP14 | R1 |
| BQ-08 | No-show auto-mark, pull-forward | M | P | Manual only; never-arrived cannot be marked (QA-31) | M | EP14 | R1 |
| BQ-09 | Waitlist auto-backfill | S | BNI | Built staff-side; patient never notified; past slots offered (QA-24) | M | EP07, EP29 | R2 |
| BQ-10 | Multi-station journey, lab-before-consult | S | P | Department transfers + encounter id; no per-station ETA | L | EP11, EP27 | R2 |
| BQ-11 | Sibling back-to-back booking | S | NB | — | S | EP12, EP29 | R2 |
| BQ-12 | Slot types (sick/well, gender), female-doctor filter | S | NB | — | M | EP10, EP29 | R2 / R4 gender |
| BQ-13 | Lobby token display | S | NB | NB-337–339 not started | S | EP06, EP15 | R1 |

### 3.3 Front desk

| ID | Feature | Pri | Status | Gap (evidence) | Eff | Dependency | Rel |
|---|---|---|---|---|---|---|---|
| FD-01 | Keyboard-first registration | M | P | Only search shortcuts (FE-23) | M | EP04, EP15 | R1 |
| FD-02 | Phone lookup, family, fuzzy match, merge | M | BNI | Trigram search + duplicate hints built; merge API-only; search not indexed (DP-15); registry truncates at 3,000 (DP-03) | M | EP03, EP17 | R1 |
| FD-03 | Minimum dataset; patient completes on WhatsApp | M | P | Registration works; WhatsApp completion missing | M | EP12, EP15 | R1 |
| FD-04 | ID capture (EID/Iqama/QID; ABHA) | S | NB | National ID encrypted only | M | EP22 (ABHA); EP39 EID | R1 IN / R4 / R5 |
| FD-05 | Self check-in QR + geofence + override | M | NB | NB-207 not started | M | EP12, EP15 | R1 |
| FD-06 | Exceptions inbox | M | NB | — | M | EP15 | R1 |
| FD-07 | Multi-doctor board with move/reassign | M | BNI | Board polls every 15 s; transfer exists | M | EP06, EP15 | R1 |
| FD-08 | Quick desk billing | M | BNI | Works, but payments race (QA-10–12); flat tax | M | EP02, EP20 | R1 |
| FD-09 | Call-centre console | S | NB | — | L | EP32, EP40 | R5 |

### 3.4 Clinical / EMR

| ID | Feature | Pri | Status | Gap (evidence) | Eff | Dependency | Rel |
|---|---|---|---|---|---|---|---|
| CL-01 | One-screen consult view | M | BNI | Pre-consult summary + timeline exist; no intake or attachments; 1,489-line page (FE-13) | M | EP16, EP19 | R1 |
| CL-02 | One-hand mobile doctor mode | M | P | Responsive only; no solo flow (S04 §3.1) | L | EP16 | R1 |
| CL-03 | Vitals by any role | M | BS | Built with ranges and timestamps; devices later | S | — (devices R5) | R1 |
| CL-04 | Specialty templates, smart phrases | M | BNI | Templates private per doctor; 1 of 9 specialties | M | EP28 | R2 |
| CL-05 | ICD-10 / ICD-10-AM search | M | P | Shape check only, "no list loaded" | M | EP28; licences | R2 IN / R4 CM / R5 AM |
| CL-06 | Referral becomes a queue entry | M | P | Referral document + transfer exist, not linked | S | EP28 | R2 |
| CL-07 | Growth charts, vaccine schedule + reminders | S | P | Vaccines record-only; dose calculator exists | M | EP28, EP07 | R2 |

### 3.5 AI clinical decision support

| ID | Feature | Pri | Status | Gap (evidence) | Eff | Dependency | Rel |
|---|---|---|---|---|---|---|---|
| AI-01 | Allergy, DDI, duplicate-therapy checks with tiers | M | BNI | Allergy/pregnancy/controlled checks are hand-curated (QA-16); no DDI | L | EP05 CSO, EP18 drug master, EP28, licensed KB | R1 allergy / R2 DDI |
| AI-02 | Paediatric dose calculator | M | BS | Built, with rule source and stale-weight warning; add a max-age test (H-07) | S | EP05 | R1 |
| AI-03 | Explainability, override with reason, tunable tiers, alert analytics | M | P | Reason codes + severity built; no tuning or analytics | M | EP28, EP36 | R2 / R3 |
| AI-04 | Condition- and lab-aware alerts | S | NB | Pregnancy only | L | EP27 results, EP36 | R3 |
| AI-05 | In-region inference, model register | M | NB | No AI at all | M | EP36, EP09 | R3 (before any LLM) |

### 3.6 e-Prescription

| ID | Feature | Pri | Status | Gap (evidence) | Eff | Dependency | Rel |
|---|---|---|---|---|---|---|---|
| RX-01 | Favourites, repeat last, portable | M | BNI | Favourites built; repeat-last is API-only; not portable | S / L | EP18; EP32 portable | R1 / R3 |
| RX-02 | Drug master, brand↔generic, formularies | M | NB | Tenant item master only; typeahead scans history (DP-16) | L | EP18, licence | R1 IN / R4 / R5 |
| RX-03 | Bilingual Rx PDF with QR, reg no, signature | M | P | Print + registration number; no QR/PDF; English only | M | EP18, EP19 | R1 / R4 AR |
| RX-04 | Expiry/refill rules; controlled-drug workflow | M | P | Controlled warnings exist (6 drugs); no expiry column | M | EP18, EP21 | R1 |
| RX-05 | Patient-choice routing, clinic default | M | NB | — | M | EP18, legal review | R1 |
| RX-06 | Stock-aware prescribing | S | NB | Needs batches | S | EP21, EP26 | R2 |
| RX-07 | National e-Rx (Wasfaty, UAE/QA) | S | NB | — | L | EP39 / EP42 | R4 / R5 |

### 3.7 Pharmacy

| ID | Feature | Pri | Status | Gap (evidence) | Eff | Dependency | Rel |
|---|---|---|---|---|---|---|---|
| PH-01 | Scan QR → verify → pre-filled bill → dispensed | M | P | Dispensing queue is a view; dispensing happens through checkout | M | EP18, EP21 | R1 |
| PH-02 | POS with FEFO + GST/VAT invoices | M | P | OTC sale works; integer stock (DP-28); flat tax | L | EP20, EP21 | R1 / R4 VAT |
| PH-03 | Offline billing, safe sync | M | NB | Only note drafts work offline | XL | EP25 | R2 |
| PH-04 | Auto H/H1 register; GCC controlled logs | M | NB | NB-182; Rx-only items sold OTC (QA-14) | M | EP05, EP21 | R1 IN / R4–R5 |
| PH-05 | Standalone tenant, paper-Rx photo | M | NB | Depends on clinic catalogue/checkout (S04 §3.14) | L | EP19, EP26 | R2 |
| PH-06 | Eligibility/approval at dispense | S | NB | — | L | EP39 | R4 |
| PH-07 | Refill reminders, "ready for pickup" | S | NB | — | S | EP07, EP26 | R2 |

### 3.8 Lab

| ID | Feature | Pri | Status | Gap (evidence) | Eff | Dependency | Rel |
|---|---|---|---|---|---|---|---|
| LB-01 | Digital lab orders | M | NB | "External diagnostic request: Not available yet" | L | EP27 | R2 |
| LB-02 | Barcodes, sample tracking | S | NB | — | M | EP27 | R2 |
| LB-03 | Secure report link + "ready" push | M | NB | No uploads | M | EP19, EP27 | R2 |
| LB-04 | Critical value escalation, read receipt, fallback | M | NB | — | M | EP07, EP27 | R2 |
| LB-05 | Validation + HL7/ASTM analysers | S | NB | — | L | EP27 adapter | R3 |

### 3.9 Billing & insurance

| ID | Feature | Pri | Status | Gap (evidence) | Eff | Dependency | Rel |
|---|---|---|---|---|---|---|---|
| BI-01 | Billing incl. deposits, refunds, fee schedules, payer prices | M | BNI | Checkout, packages, day close built; no refunds, credit notes or invoice list; global INV sequence (F-21); races | L | EP02, EP20 | R1 / R4 payer lists |
| BI-02 | Real-time eligibility (GCC) | M | NB | — | L | EP39 | R4 |
| BI-03 | DHA eClaimLink / Shafafiya | M | NB | Explicit non-goal SYS-10 (S04 §3.13) | XL | EP39 | R4 DHPO / R5 Shafafiya |
| BI-04 | NPHIES | M | NB | Static copy only | XL | EP42 | R5 |
| BI-05 | Pre-auth prompts + claim scrubber | S | NB | — | L | EP39, CL-05 | R4 |
| BI-06 | Remittance reconciliation + rejection queue | S | NB | — | L | EP39 | R4 |
| BI-07 | Co-pay estimate at booking | S | NB | — | M | EP39 | R4 |
| BI-08 | ZATCA + UAE e-invoicing | S → M in market | NB | Peppol B2B applies from 1 Jul 2027 (<AED 50M); ZATCA Wave 25 from 1 Feb 2027 (S01 §3.2–3.3), so both are mandatory on market entry | L | EP20 tax engine, ASP partner | R4 / R5 |

### 3.10 Inventory

| ID | Feature | Pri | Status | Gap (evidence) | Eff | Dependency | Rel |
|---|---|---|---|---|---|---|---|
| IV-01 | Batch/expiry, near-expiry alerts | M | NB | No batches (DP-28, QA-39) | L | EP21 | R1 |
| IV-02 | PO, GRN, suppliers, reorder | S | NB | Not even planned | L | EP26 | R2 |

### 3.11 Consent & interoperability

| ID | Feature | Pri | Status | Gap (evidence) | Eff | Dependency | Rel |
|---|---|---|---|---|---|---|---|
| CI-01 | Consent ledger + patient "who accessed" view | M | P | Withdrawal gate; messaging default-deny; no capture, no ledger, no read audit | L | EP08, EP31, EP33 | R1 base / R3 full |
| CI-02 | Cross-hospital request → approve → time-boxed view | M | NB | Brand scope shares wholesale without consent (security review §3.4) | XL | EP32, EP33 | R3 |
| CI-03 | Assisted consent | M | NB | — | M | EP08; voice in EP34 | R1 / R3 |
| CI-04 | Caregiver/guardian scoped roles | M | P | Guardian/minor only | M | EP34 | R3 |
| CI-05 | Intra-hospital specialty policy | M | NB | `/clinical/**` ignores own-patients scope (security review #6) | M | EP02 scoping; EP40 policy | R1 default / R5 |
| CI-06 | Global patient identity | M | NB | Per-tenant patients; national ID unusable as a key (F-22) | L | EP12 (Person model), EP32 | R1 model / R3 cross-facility |
| CI-07 | FHIR R4; HL7 v2 | S | NB | — | L | EP30; EP27 adapter | R2 / R3 |
| CI-08 | ABDM (ABHA, HIP/HIU) | S | NB | — | L | EP22, EP30, EP33 | M1 R1, M2 R2, M3 R3 |
| CI-09 | NABIDH, Malaffi, Riayati | S → M in market | NB | Licensing prerequisite in the UAE (S01 §2.2) | XL | EP30, EP39 | R4 NABIDH / R5 |

### 3.12 Admin, settings & workflows

| ID | Feature | Pri | Status | Gap (evidence) | Eff | Dependency | Rel |
|---|---|---|---|---|---|---|---|
| AD-01 | Workflow presets with toggles | M | P | 3 templates, 1 toggle; no Solo preset | M | EP16, EP23 | R1 / R2 / R5 |
| AD-02 | Roles per staff type | M | BNI | Strong matrix, but only Owner seeded; doctor detected by role name; Owner has `clinical:approve` (QA-17); escalation via role PATCH | S | EP01, EP05 | R0 |
| AD-03 | Schedules, leave, Ramadan | M | BNI | Whole-day leave only; midnight split (QA-22); no Ramadan mode | M | EP10 | R1 / R4 Ramadan |
| AD-04 | Bilingual template manager | S | BNI | Template library with a language field exists | S | EP07, EP24 | R1 |
| AD-05 | Guided onboarding + CSV import | S | P | Wizard built; import is a stub that never processes (S04 §3.4) | M | EP23 | R1 |

### 3.13 Multi-facility, reporting, platform, localization

| ID | Feature | Pri | Status | Gap (evidence) | Eff | Dependency | Rel |
|---|---|---|---|---|---|---|---|
| MF-01 | Global doctor profile, one login, busy blocks | M | NB | 3 hospitals = 3 staff rows | XL | EP32 | R3 |
| MF-02 | Delay propagation across facilities | S | NB | — | M | EP32 | R3 |
| MF-03 | Chain MPI, central config | S | P | Hierarchy + brand scope exist but are unsafe (DP-02) | L | EP40 | R5 |
| RP-01 | Ops dashboard (door-to-doctor, ETA accuracy…) | M | P | 10 live OLTP reports (DP-10); no stage timestamps | M | EP11 events, EP23 | R1 / R5 chain |
| RP-02 | Revenue, payer mix, rejections | S | P | Money reports exist; no payer data | M | EP20; EP39 | R2 / R4 |
| RP-03 | Daily WhatsApp summary to owner | S | NB | — | S | EP07, EP16 | R1 |
| PS-01 | Tenant isolation, per-tenant keys, CMK | M | BNI | RLS design strong but inert (F-02); single global AES key (F-36) | L | EP02, EP31, EP40 | R0 / R2 / R5 |
| PS-02 | In-region residency | M | NB | One Render/Vercel deployment; region IN/KSA only (F-16, QA-21) | XL | EP09, EP38, EP42 | R1 IN / R4 / R5 |
| PS-03 | Immutable audit incl. reads | M | BNI | Hash chain forks (F-07); 5 s trigger (DP-05); reads not audited; no UI | M | EP02, EP31 | R1 / R2 UI |
| PS-04 | SSO, MFA, session policy | M | BNI | MFA bypassable (F-01); SSO a non-goal | S / L | EP01; EP40 SSO | R0 / R5 |
| PS-05 | Offline-tolerant queue, check-in, billing | M | P | Note drafts only; pill reads `navigator.onLine` (FE-24) | XL | EP25 | R2 |
| PS-06 | PHI-minimised, lock-screen-safe notifications | M | NB | Variable-fill templates exist; no PHI policy | S | EP07, EP08 | R1 |
| PS-07 | SLOs (p95 < 300 ms queue; 99.9 %) | M | NB | Multi-second queries (DP-01/02/05); no metrics (F-14) | M | EP03 | R0 / R1 |
| PS-08 | Break-glass with justification + alerts | S | BNI | Exists but is self-service Owner elevation (QA-02) | S | EP01 | R0 |
| PS-09 | SIEM export, pen test, ISO 27001, DSR | S | NB | E21 18/19 not started | L | EP31 | R2 / R4 ISO |
| LC-01 | Arabic RTL | M (GCC) | NB | No i18n; CSS already logical (FE §7.2) | L | EP24, EP37 | R4 |
| LC-02 | Patient WhatsApp EN/AR/HI | M | NB | — | M | EP07, EP24 | R1 EN/HI / R4 AR |
| LC-03 | Hijri, weekends, prayer slots, currency/lakh formats | M | P | ClinicClock strong; INR/SAR only; Hijri on 1 screen | M | EP09, EP24, EP37 | R1 IN / R4 / R5 |
| LC-04 | Regional Indian languages | S | NB | — | M | EP24 | R1 KN/TE / R2 rest |
| LC-05 | Local-language voice reminders | S | NB | — | M | EP34 | R3 |

**Reading the matrix.** R1 touches 59 items (some only in their India slice): the full beachhead loop (book → queue → consult → Rx → pharmacy counter → bill) for Indian solo and small clinics. Everything GCC-specific is held back to R4, apart from the region model (EP09) and the i18n foundation (EP24), both of which are much cheaper now than retrofitted (FE-05, S01 risk 12).

---

## 4. What we built that we should make better

| Area | Today (evidence) | UX change | Measurable time target |
|---|---|---|---|
| **Queue ETA** | mean(invoice − check-in) × entries ahead. It includes waiting time, ignores delay, priority and elapsed consult, and costs 2.25 s per call (DP-08, QA-23) | Stage events + doctor sessions; a log-normal service time per doctor × visit type with empirical-Bayes shrinkage; a conditional remaining-time term; a range ("10:40–10:55"); modes exact / range / position / delay-only / hidden (DP §5.5) | ETA within ±10 min for ≥ 80 % of visits (MM2); "what's my number" calls −70 % (P2: 70 min/day of Lakshmi's time → ≤ 20); waiting-room median < 15 min |
| **Live boards** | 15 s polling with N+1 fan-out (FE-08, DP-04) | SSE patches into a TanStack Query cache; batch `/queue/board` | Desks agree within 2 s (p95); about 70 % less read QPS |
| **Booking rules** | Staff-only; double-booking guard only on identical start; leave and hours ignored (F-06, QA-09) | Booking-request state; auto-accept by session cap / service / walk-in buffer; `EXCLUDE` constraint; partial-day leave; session-based clinic day (QA-22) | Doctor time spent accepting bookings → 0 (P1: 20 min/day of overnight replies → < 5); zero double bookings |
| **Doctor delay** | Recorded staff-side, never expires, no broadcast | 2-tap "Late by 15/30/45/60" from phone or desk; auto-broadcast with wait / reschedule / other-doctor options; the doctor cannot block it (P4) | All affected patients notified < 60 s (MM5); P4's 25 min/day of calls → 0 |
| **Waitlist** | Offers visible only to staff; past slots offered (QA-24) | WhatsApp auto-offer, first to accept wins, 15 min window | Backfill < 10 min (J-D); P3 no-shows 12–15 % → < 6 % |
| **Billing tax** | Flat tax % per charge, applied before discount (QA-13); global `INV-` sequence (F-21); 2-decimal assumption (FE-18) | Tax engine: IN GST (exempt clinical services, HSN-wise 5 %/0 % medicines, CGST/SGST/IGST); per-GSTIN, per-financial-year gapless series via `invoice_series`; currency on every invoice; adapters for VAT (UAE 0/5 %, KSA nationality split) and ZATCA later | Checkout < 30 s (J-A step 9); day close exact 10 days running; P1 cash/UPI tally 10 min → 2 min |
| **Refunds & dues** | Package refunds only; no invoice list (S04 §3.13) | Credit notes, refunds with approval, dues list, payment links | Dues follow-up < 1 min per patient |
| **Pharmacy** | Integer stock on the catalogue; no dispense action; Rx-only items sold OTC (QA-14) | Batches with auto-FEFO pick, near-expiry alerts, dispense from e-Rx QR, partial dispense and substitution recorded, Rx-link required for `is_rx`, auto H1 register | Rx-to-bill < 20 s (MM8); P7 re-keying 105 min/day → < 25; monthly expiry check 3 h → 15 min; P8A H1 register 90 min/week → 0 |
| **WhatsApp** | Outbound plumbing, no callers; inbound dropped incl. STOP (QA-30); the package "Send reminder" button records a send that never happens | Domain events → Comms Gateway; inbound persisted, STOP → consent withdrawn; **relabel the package button now** ("Mark reminded") until EP07 wires it | 0 fake "sent" states; 100 % of STOPs honoured in < 1 min |
| **Rx safety content** | Illustrative lists (11 pregnancy drugs, 6 controlled per region); no brands; repeat-last is API-only | CSO-owned, versioned dictionary with brand names → licensed KB; repeat-last button; tiers so that only severe alerts interrupt | Repeat Rx < 30 s, new < 90 s (MM7); interruptive alerts ≤ 1 per 20 Rx |
| **Scope and roles** | Own-patients scope inferred from appointments, so walk-ins are invisible; `/clinical/**` unscoped; doctor detected by role name; only Owner seeded | Scope on encounters; scope enforced on clinical reads; `is_clinician` flag + licence; seeded role templates per preset | Setup of roles < 5 min (part of AD-01's < 30 min onboarding) |
| **Import/export** | Stub jobs that never run; no file upload anywhere | CSV import of patients, drugs and stock with a dry-run diff | Go-live in a day (AD-05) |
| **Spec drift** | OpenAPI is missing 139 of 264 operations (QA-35); XLSX status drifts both ways; DB PDF covers V1–V5 only | springdoc-generated spec + oasdiff CI gate; generated FE client (FE-14); ERD from the catalog; **this backlog replaces the XLSX status column for planning** | 100 % of operations in the spec; zero hand-written API types |
| **i18n** | ~2,700+ hard-coded strings, `lang="en"`, Latin font, `atob` corrupts non-ASCII names (FE-04/05) | next-intl, extraction one feature at a time, pseudo-localisation in CI, Devanagari/Kannada/Telugu fonts now, Arabic in R4 | Non-English booking completion ≥ 90 % of the English rate; patient messages in the patient's language from day one of the pilot |

---

## 5. Release roadmap

```
Month:     0   2   4   6   8   10  12  14  16  18  20  22  24
Phase 0    ██                                                   Make it safe (wk 0–4) + discovery wk 0–8
Phase 1    ░████████                                            India Clinic MVP / pilot (M1–4)
Phase 2            ████████                                     Clinic GA + Pharmacy + Lab (M4–8)
Phase 3                ████████████                             Patient app, aggregator, sharing, AI (M6–12)
Phase 4                      ██████████████                     UAE (M9–15)
Phase 5                                    ████████████████████ Hospital/chain + KSA (M15–24)
```

**Adjustments to the suggested shape, with reasons.**
1. **Discovery runs alongside Phase 0** (weeks 0–8, S03 §1.3), so Phase 1 scope is re-ranked on real evidence by week 6. Phase 1 starts building non-PHI work (Comms Gateway in the Meta sandbox, ETA simulator, design) in week 2. **No real patient data goes in until Phase 0 exits.**
2. **ABDM M1 is *submitted* in Phase 1 and *certified* in Phase 2.** NHA's process (sandbox → functional test → empanelled security audit → demo) takes 3–6 months (S01 §3.1, risk 8), and the audit needs Phase 0 fixes first.
3. **Offline billing (PS-05/PH-03) moves to Phase 2.** It is XL. The metro pilot can run online with a print fallback, but tier-2 and standalone pharmacy GA cannot (I15).
4. **The patient app in Phase 3 is gated.** S03 marks it Won't-now (PW-13); FE §10 recommends Expo. We build it only if the §8 signals pass, and only for what WhatsApp does badly: caregivers, records, consent, payments.
5. **DPDP has a hard date.** Most obligations apply from 13 May 2027 (≈ month 6.5), so the compliance pack (EP31) starts in late Phase 1, not Phase 3.

### Phase 0 — "Make it safe" (weeks 0–4)
- **Goal:** the product can hold real patient data. The detailed engineering remediation plan is in `docs/engineering/06`; this is the product summary.
- **Scope:** EP01 auth & privilege (F-01/03/08/09/17/18, QA-01…04/26/27). EP02 tenancy & integrity (F-02 migrator/runtime split + FORCE RLS + boot self-check; F-04/05/06, QA-06/09…13/28; idempotency keys). EP03 foundations (CI with quality gates, actuator/Prometheus, non-root Docker, Testcontainers ≥ 1.21, DP-01/02/05/09 index and RLS fixes, springdoc + oasdiff). EP04 frontend safety (FE-01…04, FE-11/12/17; BFF + httpOnly cookies; shared API client). EP05 clinical-safety baseline (CSO appointed, hazard log, QA-05/14/16/17/18).
- **Exit criteria:** zero open S1 and zero security S2; two replicas behind a load balancer pass the suite plus a "prod-config" job as a non-owner role; registry p95 < 300 ms at 500k patients; check-in p95 < 300 ms (PS-07); Security Controls ≥ 60 % verified; hazard log v1 signed.
- **Dependencies:** a CSO hire or contract; cloud account in India.
- **Risks:** fixes uncover more data-model debt (mitigation: time-box and defer anything that is not a blocker to Phase 1 hardening); the team wants to start features (mitigation: an explicit gate in the founder review).
- **Team:** ~13 (§7).

### Phase 1 — "India Clinic MVP / pilot" (months 1–4)
- **Goal:** prove that Nabd saves measurable time for patients and staff at 10–20 Bengaluru/Hyderabad clinics.
- **Scope:** EP06–EP24 (§6). The full beachhead loop, from WhatsApp booking (shared number) with ETA, leave-now, reminders, delay and self check-in, through solo/desk flows and e-Rx v2, to GST billing, UPI links and a basic in-clinic pharmacy. Plus what it rests on: events/SSE, Comms Gateway, consent, India hosting, ABDM M1, pilot ops and the i18n base (Hindi, Kannada, Telugu).
- **Pilot plan:** cohort A of 5 clinics in month 3 (2 weeks shadow, then live, per QA §4.10); cohort B of 10–15 in month 4. Mix: 4 solo GP/paediatrics, 4 ENT/dermatology/orthopaedics, 3 two-to-five-doctor clinics, 2 with an attached pharmacy (P7), 1 with a lab counter.
- **Exit criteria (pilot medians, vs time-motion baseline):** booking < 60 s (p90 ≤ 90 s); ETA within ±10 min for ≥ 80 % of visits by week 6 live; waiting-room median < 15 min for WhatsApp-booked patients; walk-in registration < 45 s new / < 15 s returning; self check-in < 15 s; repeat Rx < 30 s / new < 90 s; dispense < 20 s; delay broadcast < 60 s; "what's my number" calls −70 %; ≥ 50 % of bookings via WhatsApp; 0 safety incidents; receptionist SUS ≥ 70; day close exact 10 days; ABDM M1 submitted.
- **Dependencies:** a Meta BSP (Tech Provider later); licensed India drug KB (CIMS/MIMS); UPI gateway; DLT/SMS fallback; utility templates approved (R-05 risk).
- **Risks:** Meta template delays (start submissions in week 2); ETA misses destroy credibility (default to range mode, publish accuracy internally, P1's "hide on bad days"); receptionist resistance (I5: "exceptions inbox" framing).
- **Team:** ~22.

### Phase 2 — "Clinic GA + Pharmacy standalone + Lab" (months 4–8)
- **Goal:** sell repeatably in India across three SKUs.
- **Scope:** EP25 offline desk and pharmacy; EP26 standalone pharmacy edition (PO/GRN, refills, stock-aware Rx, own WABA numbers); EP27 lab orders and results; EP28 clinical depth (shared templates, ICD-10, referrals, paediatrics, licensed DDI, alert analytics); EP29 patient extras (intake Flows, voice notes, waitlist backfill, sibling and sick/well slots); EP30 ABDM M2 + FHIR core; EP31 trust and compliance pack (DPDP, audit UI incl. reads, DSR, retention, per-tenant keys, pen test).
- **Exit criteria:** 100 paying clinics, 30 standalone pharmacies, 5 labs; 90-day logo retention ≥ 85 %; zero lost bills across offline incidents; critical values acknowledged < 15 min (LB-04); "report ready?" calls → 0 at pilot labs; DPDP pack live before 13 May 2027; pen test passed with no Critical or High open; ABDM M1 certified, M2 submitted; GA "launch-ready" gate (§1) met.
- **Dependencies:** a lab partner (S01 §5.5); empanelled ABDM auditor; offline-sync design spike done in Phase 1.
- **Risks:** pharmacy scope creep towards Marg-style ERP (stay on counter speed + compliance); lab interfaces vary (manual result entry first, analysers in R3).
- **Team:** ~32.

### Phase 3 — "Patient app + aggregator + consented sharing + AI CDS" (months 6–12)
- **Goal:** turn clinic adoption into a network: patients, caregivers and doctors across facilities.
- **Scope:** EP32–EP36 (§6): Person/Practitioner identity (global doctor profile, busy-block calendar, delay propagation, portable favourites), consented sharing with ABDM M3, caregivers + gated Expo app, web aggregator, Clinical Intelligence service.
- **Exit criteria:** request → access < 2 min on the auto-release path (MM10); 100 % of expiries enforced server-side; caregiver notified < 5 min of Rx or result (P12); ≥ 20 % of Rx at multi-facility doctors use portable favourites; interruptive alert override rate < 50 % (an overridden alert is noise); 0 PHI sent to out-of-region inference; ABDM M3 submitted.
- **Dependencies:** Patient Access Edge with an event backbone that can replay (Redpanda, §9 D2); SaMD positioning reviewed by counsel (S01 risk 1).
- **Risks:** aggregator alienates clinics (PW-12: clinic-controlled visibility, no promotion on a clinic's channel); AI trust (I7: safety net, never co-pilot).
- **Team:** ~41 (overlapping with Phase 4 hiring).

### Phase 4 — "GCC entry (UAE)" (months 9–15)
- **Goal:** 25 paying Dubai/Sharjah polyclinics at 5–10× India ARPU.
- **Scope:** EP37 Arabic RTL and GCC locale; EP38 UAE cell (in-country hosting, ADHICS gap closure, in-UAE inference); EP39 UAE insurance (eligibility, DHPO claims, pre-auth prompts, scrubber, remittance, co-pay, dispense eligibility); NABIDH, UAE VAT and Peppol e-invoicing, Emirates ID reading and DHA e-Rx are scoped inside EP37 and EP39. Shafafiya and Malaffi wait for Phase 5.
- **Exit criteria:** NABIDH connection approved; eligibility < 30 s; ≥ 95 % of claims submitted same day; first-pass acceptance ≥ 90 % (MM9); Arabic patient flows rated native by Arabic moderators; 0 PHI egress outside the UAE; 3–5 design partners live by month 12, 25 paying by month 15.
- **Dependencies:** UAE entity; Peppol ASP partner; TPA/billing reseller (S01 §5.4); UAE DPO.
- **Risks:** the certification treadmill (S01 risk 2): one emirate at a time, a certified integrator partner.
- **Team:** ~51, including a ~10-person GCC squad (§7).

### Phase 5 — "Hospital/chain + KSA" (months 15–24)
- **Goal:** move upmarket (nursing homes and chains in India, polyclinics and hospitals in the GCC) and enter KSA.
- **Scope:** EP40 chain and enterprise (chain MPI, central config, chain dashboard, call centre, SSO, CMK, specialty access policy); EP41 IPD and OT (S03 CL-10 re-enters from Won't-now, based on nursing-home pipeline evidence at month 12); EP42 KSA (cell, NPHIES, ZATCA Phase 2, Wasfaty, SFDA RSD, Hijri, Iqama). Malaffi/Shafafiya for Abu Dhabi.
- **Exit criteria:** 3 live hospital deployments (needed for NABH HIS/EMR certification together with ABDM M3, S01 §3.1); chain door-to-doctor < 30 min and consultant on-time start > 90 % (P6); NPHIES certified; 100 % of ZATCA invoices cleared or reported; first 10 KSA clinics live.
- **Risks:** IPD breadth (partner rather than build if the month-12 decision says so); ZATCA and NPHIES certification cycles.
- **Team:** ~62.

---

## 6. Epic backlog (build order)

Epics are listed in build order. An epic starts only when the epics it depends on have met their exit criteria. Stories use the S03 personas, and every acceptance criterion carries a time target where one applies.

### Phase 0

**EP01 · Auth & privilege hardening** — R0 · L · P16, P6
*Problem:* a PIN alone reaches Owner rights (F-01, QA-01…03); JWT keys regenerate on every boot (F-03). *Outcome:* MFA cannot be bypassed, elevation is controlled and audited, and KMS-backed keys survive deploys.
- As an IT governance head, I want challenge tokens accepted only on their own endpoint, so that MFA cannot be skipped. **AC:** `token_use` claim + validator; negative tests on every endpoint.
- As an owner, I want break-glass to need eligibility + step-up and to notify me, so that staff cannot self-elevate. **AC:** notification < 60 s; clinical-read scope only.
- As an owner, I want role changes to obey "no grant beyond your own" and to be audited, so that nobody can assign themselves Owner. **AC:** 403 on escalation; audit row on every change.

**EP02 · Tenancy enforcement & data integrity** — R0 · L · P16, P4
*Problem:* RLS is inert outside tests; money and queue writes race. *Outcome:* isolation enforced by the database; no lost updates; no overlapping bookings (`EXCLUDE` constraint, F-06).
- As IT governance, I want migrations run by a separate migrator role with FORCE RLS and a boot self-check, so that a missed predicate cannot leak tenants. **AC:** prod-config CI job green.
- As a receptionist, I want a second check-in of the same appointment rejected, so that one patient never gets two tokens. **AC:** 409 under two concurrent desks.
- As a cashier, I want payments to be idempotent and atomic, so that a retry never double-charges. **AC:** concurrent-payment test; status derived from SUM.

**EP03 · Engineering foundations & performance** — R0 · L · all
*Problem:* no CI or observability; multi-second queries (DP-01/02/05). *Outcome:* every change is gated; SLOs are measurable.
- As an engineer, I want CI with unit, integration, contract (oasdiff), SAST and dependency gates, so that defects cannot merge. **AC:** pipeline < 15 min.
- As a receptionist, I want patient search to answer in < 300 ms p95, so that lookup never stalls the line. **AC:** k6 at 500k patients.
- As an SRE, I want metrics, health and traces with outbox-lag gauges, so that I can see incidents before clinics call. **AC:** alerting on SLO burn.

**EP04 · Frontend safety & data layer** — R0 · L · P4, P1
*Problem:* tokens in `localStorage`, no refresh, a vulnerable Next version (FE-01…03). *Outcome:* secure sessions and one typed client.
- As a doctor, I want my session to refresh silently, so that I never lose a half-written note. **AC:** 8 h shift with 0 forced logouts.
- As IT governance, I want tokens in httpOnly cookies behind a same-origin BFF, so that XSS cannot steal sessions. **AC:** CSP + security headers.
- As an engineer, I want a generated API client with TanStack Query, so that i18n, errors and caching are handled once. **AC:** 0 hand-written API types in touched pages.

**EP05 · Clinical safety baseline** — R0 · M · P15, P2, P8A
*Problem:* thin safety content and unsafe gaps (QA-05/14/16/17/18). *Outcome:* a signed safety case before pilot.
- As a doctor, I want to record pregnancy status and "no known allergies" from the UI, so that safety rules can fire. **AC:** E2E J7 green.
- As a pharmacist, I want Rx-only items blocked at OTC sale without a linked signed Rx, so that I stay compliant. **AC:** 100 % of `is_rx` lines linked.
- As a CSO, I want a versioned allergy/pregnancy dictionary including brands, so that common brands are caught. **AC:** ≥ 300 test pairs per class; PIT ≥ 90 %.
- As an owner, I want signing restricted to licensed clinicians, so that non-clinicians cannot override hard stops. **AC:** Owner seed aligned with the RBAC matrix.

### Phase 1

**EP06 · Event spine & real-time** — R1 · L · P4, P2
*Problem:* no domain events; 15 s polling. *Outcome:* every state change is pushed in under 2 s.
- As an engineer, I want 15–20 domain events published through the outbox to Valkey Streams, so that ETA, cache and notifier stay consistent. **AC:** idempotent consumers keyed on `eventId`.
- As a receptionist, I want the board updated by SSE, so that two desks never disagree. **AC:** p95 < 2 s end to end; 60 s fallback poll.
- As a doctor, I want My Day to update itself, so that I see who is waiting without refreshing. **AC:** badge updates < 2 s.

**EP07 · Comms Gateway & WhatsApp inbound** — R1 · L · P1, P16
*Problem:* inbound messages dropped; one global number; no callers. *Outcome:* a governed two-way channel.
- As a patient, I want my reply to reach the clinic, so that I am not ignored. **AC:** persist-then-ack webhook; deduplicated.
- As a patient, I want "STOP" to stop messages, so that my choice is respected. **AC:** consent withdrawn < 1 min.
- As a solo doctor, I want messages sent from a verified business sender with my clinic's name, so that patients trust them (P12 scam fear). **AC:** shared Nabd number with branding; per-tenant number in R2.

**EP08 · Consent capture & PHI-safe messaging** — R1 · M · P16, P12, P11
*Problem:* absence of consent counts as consent. *Outcome:* DPDP-shaped notice and consent from the first visit.
- As a patient, I want an itemised notice and separate consents (processing, WhatsApp, marketing), so that I know what I agreed to. **AC:** captured in < 20 s at the desk or in chat.
- As a receptionist, I want an assisted, witnessed consent mode, so that elderly patients are included. **AC:** witness recorded; local-language explainer.
- As IT governance, I want templates linted so that they carry no diagnosis or result text, so that lock screens leak nothing. **AC:** PHI lint blocks template approval.

**EP09 · Region model & India hosting** — R1 · L · P16
*Problem:* region is IN/KSA only; one shared deployment (QA-21, F-16). *Outcome:* one data region per tenant, ready for more cells.
- As an operator, I want a region record (country, currency, tax, regulator, timezone, phone rules), so that a Dubai clinic never gets SAR. **AC:** region-parameterised test suites.
- As IT governance, I want Indian tenants hosted in India with KMS keys, so that residency and CERT-In logs are satisfied. **AC:** 0 PHI outside the cell.
- As an operator, I want the region pinned at onboarding, so that no tenant can drift. **AC:** immutable after go-live.

**EP10 · Booking rules engine** — R1 · L · P1, P3, P4
*Problem:* only staff create bookings; no caps or interleaving. *Outcome:* clinics set rules once and never accept bookings by hand.
- As a solo GP, I want bookings auto-accepted up to a per-session cap and by service type, so that Monday mornings don't drown me. **AC:** over-cap → approver queue; decision < 2 s.
- As a receptionist, I want a walk-in/booked interleave (for example 2:1) and reserved walk-in slots, so that the queue feels fair. **AC:** placement instant.
- As a doctor, I want partial-day leave and evening sessions that cross midnight, so that my calendar matches reality. **AC:** QA-22 midnight tests green.

**EP11 · ETA engine v2 & display modes** — R1 · L · P11, P2, P3
*Problem:* the ETA is statistically wrong. *Outcome:* honest ranges that patients learn to trust.
- As a patient, I want a range ("10:40–10:55") and my position, so that I can plan. **AC:** ±10 min for ≥ 80 % of visits.
- As a dentist, I want delay-only mode, so that patients do not arrive late on purpose (P3). **AC:** five modes per doctor/session.
- As a COO, I want ETA accuracy logged per clinic, so that we can see it drift. **AC:** alert when P80 coverage < 70 %.

**EP12 · WhatsApp booking (Patient Access Edge v1)** — R1 · XL · P11, P14, P2
*Problem:* there is no patient booking path. *Outcome:* "Hi" → booked in under a minute.
- As a returning patient, I want to be recognised by my phone number and book with buttons, so that booking takes < 60 s. **AC:** ≤ 5 interactions; no free text needed.
- As a mother, I want my children listed under my number, so that I register once (PW-06). **AC:** pick a dependant in 1 tap.
- As a doctor-owner, I want my channel to show only my clinic, so that Nabd never steers my patients elsewhere (PW-12). **AC:** no cross-clinic content on a clinic channel.
- As a new patient, I want to finish registration in chat, so that the desk only captures the minimum (FD-03). **AC:** desk part < 45 s.

**EP13 · Live status, leave-now & reminders** — R1 · M · P11, P3, P1
*Problem:* patients cannot see their status and are not reminded. *Outcome:* the wait moves from the lobby to home or the car.
- As a patient, I want a "leave now" message based on my travel buffer, so that I arrive just in time. **AC:** sent once; ≥ 10 min shift threshold with hysteresis; ≤ 3 per visit.
- As a patient, I want to ask "status" at any time, so that I never need to call. **AC:** reply < 3 s.
- As a dentist, I want T-24 h/T-2 h confirm/reschedule/cancel buttons, so that no-shows fall. **AC:** no-show < 6 % at confirming clinics.

**EP14 · Delay broadcast, no-show & priority automation** — R1 · M · P4, P15, P5
*Problem:* delay and priority changes stay at the desk. *Outcome:* everyone affected knows within a minute.
- As a receptionist, I want a 2-tap delay broadcast that the doctor cannot block, so that I stop phoning 18 people. **AC:** all notified < 60 s; responses shown on the desk.
- As a receptionist, I want no-shows auto-marked after a grace period and the queue pulled forward, so that flow keeps moving. **AC:** configurable grace; ETAs recomputed < 5 s.
- As a solo doctor, I want "pause queue" with a neutral message, so that I can handle an emergency. **AC:** 2 taps; no PHI in the message.

**EP15 · Front desk v2 (keyboard-first, self check-in, exceptions)** — R1 · M · P4, P11
*Problem:* mouse-heavy desk; everyone queues at the counter. *Outcome:* the desk handles exceptions only.
- As a receptionist, I want full keyboard registration with F-keys, so that a walk-in takes < 45 s. **AC:** returning patient < 15 s.
- As a patient, I want to scan the door QR or tap "I'm here", so that I skip the counter. **AC:** < 15 s; geofence ≤ 200 m; desk override.
- As a receptionist, I want an exceptions inbox (failed check-ins, unpaid, unconfirmed), so that I see only what needs me. **AC:** zero-training test: productive in < 15 min.
- As a patient in the lobby, I want a bilingual token screen without names, so that I never ask the desk. **AC:** updates < 2 s.

**EP16 · Solo mode & one-hand doctor** — R1 · L · P1, P2
*Problem:* no single-screen flow for clinics without staff. *Outcome:* a solo doctor runs the clinic from a phone.
- As a solo GP, I want call next → context → Rx → bill on one phone, so that I need no laptop. **AC:** context < 5 s; thumb-zone actions; works on a ₹12k Android over 3G.
- As a solo GP, I want a "Solo" preset (self check-in, auto-accept, no vitals step), so that onboarding takes < 30 min. **AC:** preset applied in 1 step.
- As an owner-doctor, I want a daily WhatsApp summary (visits, collections, no-shows), so that I don't open a laptop. **AC:** sent at clinic close.

**EP17 · Registry & lookup performance** — R1 · M · P4, P6
*Problem:* duplicates, a truncated registry, merge only in the API. *Outcome:* one record per patient, found in seconds.
- As a receptionist, I want a phone lookup that lists the family, so that I pick the right patient in < 10 s. **AC:** registry read model (DP §5.6).
- As a receptionist, I want merge suggestions with the matched fields and a 30-day undo in the UI, so that I clean duplicates safely. **AC:** step-up enforced.
- As an owner, I want registry filters to cover all patients, not the first 3,000, so that nobody disappears. **AC:** keyset paging.

**EP18 · e-Prescription v2** — R1 · L · P1, P8A, P7
*Problem:* no drug master, QR, expiry or routing. *Outcome:* legible, verifiable, fast prescriptions.
- As a doctor, I want "repeat last" and favourites from a licensed India drug master, so that a repeat Rx takes < 30 s and a new one < 90 s. **AC:** local typeahead < 100 ms.
- As a chemist, I want a PDF/print with QR, registration number and expiry (5 days for antibiotics; refills for chronic drugs), so that I can refuse reuse. **AC:** QR verifies offline-signed payload.
- As an owner-doctor, I want my pharmacy as the default while the patient can choose any pharmacy, so that I keep sales without steering (RX-05). **AC:** no discount lists; counsel sign-off.

**EP19 · Secure documents & uploads** — R1 · M · P11, P16, P12
*Problem:* nothing can be shared or attached. *Outcome:* paperless and governed.
- As a patient, I want my Rx and invoice as an OTP-protected link, so that nothing sits in the chat body. **AC:** link expiry configurable; opened < 5 s.
- As a doctor, I want to attach outside reports, so that context lives in one place. **AC:** virus-scanned; tenant-scoped storage.
- As IT governance, I want every link open logged, so that access is auditable. **AC:** log produced in < 5 min.

**EP20 · GST billing, refunds & payments** — R1 · L · P1, P4, P7
*Problem:* flat tax, races, no refunds or links. *Outcome:* compliant bills in seconds.
- As a cashier, I want GST computed per line (exempt services, HSN rates for medicines) after discounts, so that invoices are correct. **AC:** golden-file tests; QA-13 fixed; per-GSTIN, per-financial-year gapless series.
- As a patient or caregiver, I want a UPI link, so that checkout takes < 30 s. **AC:** idempotent webhook reconciliation.
- As a cashier, I want refunds and credit notes with approval, so that mistakes are reversible. **AC:** day close reflects them.

**EP21 · In-clinic pharmacy v2** — R1 · L · P7, P8A
*Problem:* no dispense action, batches or registers. *Outcome:* Rx to bill in under 20 s.
- As a pharmacist, I want to scan the Rx QR and get a pre-filled bill with FEFO batches, so that I never retype. **AC:** < 20 s; partial dispense and substitution recorded.
- As an owner, I want near-expiry alerts at 90/60/30 days, so that write-offs halve. **AC:** monthly check < 15 min.
- As a chemist, I want the H1 register generated automatically, so that Sundays are free. **AC:** printable for inspection; kept 3 years.

**EP22 · ABDM M1** — R1 · M · P6, P11
*Problem:* no national identity rail. *Outcome:* ABHA linking and Scan & Share at the desk.
- As a receptionist, I want to create or verify ABHA during registration, so that it adds < 20 s. **AC:** never mandatory; Aadhaar not stored.
- As a patient, I want Scan & Share check-in, so that I can use ABHA or WhatsApp. **AC:** token issued < 15 s.
- As the product owner, I want the sandbox test cases passed and submitted, so that certification starts. **AC:** empanelled audit booked.

**EP23 · Pilot operations: onboarding, import, entitlements, ops dashboard** — R1 · M · P1, P6
*Problem:* import is a stub; plans have no entitlements; no time metrics. *Outcome:* go-live in a day; KPIs visible daily.
- As an implementation manager, I want CSV import with a dry-run diff, so that a clinic goes live in a day. **AC:** 10k patients imported < 10 min.
- As Nabd commercial, I want module entitlements and seat enforcement per plan, so that tiers are real. **AC:** QA-33 fixed.
- As an owner, I want door-to-doctor, waits, no-shows, ETA accuracy and punctuality each morning, so that I fix bottlenecks. **AC:** from rollups, not OLTP.

**EP24 · i18n foundation & Indian languages** — R1 · L · P2, P8A, P12
*Problem:* English-only, Latin-only. *Outcome:* native-language patient comms now; staff i18n ready.
- As a parent in Hyderabad, I want messages in Telugu, so that I understand without help. **AC:** human-reviewed copy; EN/HI/KN/TE.
- As an engineer, I want next-intl with lint-blocked literals and pseudo-localisation in CI, so that no new hard-coded strings appear. **AC:** 0 literal-string violations in migrated folders.
- As a staff member with a non-Latin name, I want my name rendered correctly, so that the system respects me. **AC:** FE-04 fixed.

### Phase 2

**EP25 · Offline-tolerant desk & pharmacy** — R2 · XL · P7, P8A, P4
*Problem:* power and internet cuts stop work in tier-2. *Outcome:* billing and check-in survive outages.
- As a pharmacist, I want to bill offline and sync later, so that no customer leaves. **AC:** 0 lost or duplicate bills; conflicts surfaced.
- As a receptionist, I want offline check-in with provisional tokens, so that the line keeps moving. **AC:** reconciled < 60 s after reconnect.
- As staff, I want honest sync status, so that I know what is pending. **AC:** pill driven by the sync queue, not `navigator.onLine`.

**EP26 · Standalone pharmacy edition** — R2 · L · P8A, P8B
*Problem:* the pharmacy cannot run without a clinic. *Outcome:* a sellable SKU on the same core.
- As a chemist, I want a pharmacy-only tenant with POS, so that I can buy Nabd without a clinic. **AC:** setup < 30 min; paper-Rx photo attached to the bill in < 10 s.
- As a chemist, I want POs, GRN and reorder suggestions, so that stock-outs fall. **AC:** GRN with batches < 2 min.
- As a patient, I want a "ready for pickup" refill reminder, so that I don't wait at the counter. **AC:** utility template only.

**EP27 · Lab orders & results** — R2 · L · P9, P15, P12
*Problem:* handwritten slips; "ready?" calls; unacknowledged critical values. *Outcome:* closed-loop diagnostics.
- As a doctor, I want to order tests from the consult screen, so that the lab gets a digital order. **AC:** < 10 s; in-house or partner lab.
- As a lab manager, I want barcodes and sample tracking, so that there are no mix-ups. **AC:** registration < 60 s.
- As a doctor, I want critical values escalated with read receipt and a fallback chain, so that no result is missed. **AC:** acknowledged < 15 min.

**EP28 · Clinical depth & licensed DDI** — R2 · L · P15, P2, P10
*Problem:* private templates, no ICD list, no DDI. *Outcome:* faster notes, coding at source, real safety checks.
- As a doctor, I want shared specialty templates and smart phrases, so that notes take fewer keystrokes. **AC:** note < 60 s for a common visit.
- As a doctor, I want DDI and duplicate-therapy checks from a licensed KB with the source shown, so that I trust them. **AC:** check < 1 s; interruptive alerts only for severe.
- As a doctor, I want "Refer → specialty" to create a queue entry, so that patients don't re-register. **AC:** < 10 s.

**EP29 · Patient experience extras** — R2 · M · P14, P3, P5
*Problem:* history is re-asked, cancelled slots are wasted, siblings are booked twice. *Outcome:* fewer trips and fuller chairs.
- As a patient, I want a pre-visit WhatsApp Flow (reason, allergies, meds, card photo), so that I'm not re-asked. **AC:** < 2 min, optional.
- As a mother, I want sibling back-to-back slots, so that I come once. **AC:** booking for 2 children < 60 s.
- As a dentist, I want cancelled slots offered to the waitlist automatically, so that chairs fill. **AC:** backfill < 10 min.

**EP30 · ABDM M2 + FHIR core** — R2 · L · P6, P11
*Problem:* no FHIR layer; ABDM M2 is a hospital buying criterion. *Outcome:* one FHIR core that every national rail reuses.
- As a patient, I want my visit records discoverable in my ABHA app, so that I carry them anywhere. **AC:** care contexts linked at visit close.
- As an engineer, I want a FHIR R4 mapping layer, so that ABDM, NABIDH and NPHIES reuse it. **AC:** NRCeS profiles validated.
- As the product owner, I want M1 certified and M2 submitted, so that hospital RFPs are winnable. **AC:** NHA demo passed.

**EP31 · Trust & compliance pack** — R2 · L · P16, P6
*Problem:* DPDP applies from 13 May 2027; no DSR, read audit or per-tenant keys. *Outcome:* compliant before the deadline and ready for procurement.
- As a patient, I want to export or erase my data, with 48 h notice before erasure, so that DPDP rights work. **AC:** DSR SLA met; sectoral retention respected.
- As an owner, I want an audit screen including who *read* each record, so that I answer any audit in < 5 min. **AC:** read audit on all clinical endpoints.
- As IT governance, I want per-tenant data keys and a passed pen test, so that the security review takes < 2 weeks. **AC:** key versioning + AAD (F-36); no Critical/High open.

### Phase 3

**EP32 · Person & Practitioner identity** — R3 · XL · P15, P13
*Problem:* a doctor at 3 hospitals is 3 unrelated staff rows (F-22). *Outcome:* one doctor and one patient identity, while records stay with each facility.
- As a cardiologist at 3 hospitals, I want one login with a tenant switcher, so that I stop juggling 3 credentials. **AC:** switch < 3 s.
- As a doctor, I want a unified calendar where other hospitals see only "busy", so that I'm never double-booked across sites. **AC:** 0 cross-site clashes.
- As a doctor, I want my favourites portable and "late by 45" propagated to every facility, so that patients everywhere know. **AC:** all notified < 2 min.

**EP33 · Consented cross-facility sharing** — R3 · XL · P15, P13, P16
*Problem:* today it is "bring the CD", or a brand scope that shares wholesale without consent. *Outcome:* consented, time-boxed, audited access in minutes.
- As a doctor, I want to request a scoped, time-boxed record set in ≤ 3 taps, so that I avoid repeat tests. **AC:** request → access < 2 min on auto-release.
- As a patient, I want to approve on WhatsApp (or with assistance) and see who viewed what, so that I stay in control. **AC:** revocation effective < 1 min.
- As a custodian hospital, I want release by policy (consent + treating relationship) with an approver fallback, so that records staff aren't a bottleneck. **AC:** approver SLA < 4 h; immutable logs in both tenants.

**EP34 · Caregivers & patient app (gated)** — R3 · L · P12, P14
*Problem:* caregivers rely on blurry photos; WhatsApp is weak for records and payments. *Outcome:* families act within minutes, with the patient's consent.
- As an NRI son, I want scoped caregiver access approved by my mother, so that I'm notified of Rx and results. **AC:** < 5 min after signing.
- As a caregiver abroad, I want to pay by UPI or a UAE card, so that I settle bills remotely. **AC:** payment < 60 s.
- As an elderly patient, I want voice reminders in Malayalam, so that I understand without reading. **AC:** delivered by the scheduled time.

**EP35 · Web aggregator** — R3 · L · P11, P13
*Problem:* new patients cannot find Nabd clinics. *Outcome:* demand for clinics without poaching their patients (PW-12).
- As a patient, I want to find a doctor by specialty, language, insurance and next slot, so that I book in one flow. **AC:** search < 1 s from CDN/OpenSearch.
- As a clinic owner, I want to opt in or out of listing, so that I control visibility. **AC:** listing is opt-in; delisting takes effect < 5 min.
- As a patient, I want no symptom interpretation, so that I am never misdirected (S04 §5.1 non-goal). **AC:** emergency phrases redirect to emergency services.

**EP36 · Clinical Intelligence service** — R3 · L · P15, P16
*Problem:* safety rules are local and narrow, and there is no governed path for AI. *Outcome:* explainable, in-region decision support with a local fallback.
- As a cardiologist, I want eGFR- and potassium-aware dosing alerts with sources, so that I catch renal risk. **AC:** < 800 ms or local fallback stamped `cdsSource=local`.
- As IT governance, I want in-region inference and a model register, so that regulators get answers. **AC:** 0 out-of-region PHI calls.
- As a CSO, I want alert-burden analytics and tunable tiers, so that fatigue is managed. **AC:** override rate reported per alert type weekly.

### Phase 4

**EP37 · Arabic RTL & GCC locale** — R4 · L · P14, P5, P3
*Problem:* no Arabic, RTL or GCC formats. *Outcome:* native Arabic for patients; bilingual records and documents.
- As a Saudi or Emirati mother, I want native Arabic WhatsApp with buttons, so that I never type. **AC:** native-moderator review; booking < 60 s.
- As a nurse, I want Arabic on the patient side and English on mine for the same record, so that nothing is re-entered. **AC:** RTL visual snapshots for every screen.
- As a clinic, I want bilingual Rx, invoices and AED/VAT formats, so that documents are compliant. **AC:** zero-rated vs 5 % lines correct.

**EP38 · UAE cell & ADHICS** — R4 · L · P16
*Problem:* UAE law requires health data to stay in-country. *Outcome:* a UAE cell holding PHI, logs and inference.
- As IT governance, I want all PHI, logs and inference in UAE regions, so that Federal Law 2/2019 Art. 13 is met. **AC:** egress tests in CI.
- As an operator, I want to provision a UAE tenant into the UAE cell, so that residency is automatic. **AC:** cell routing in the Control Plane.
- As a buyer, I want an ADHICS gap report and a 25-year retention policy, so that procurement passes. **AC:** retention configurable per jurisdiction.

**EP39 · UAE insurance, NABIDH & e-invoicing** — R4 · XL · P10, P13, P3, P8B
*Problem:* every UAE clinic lives on insurance and NABIDH (S01 §1.2). *Outcome:* zero counter time for insurance; clean claims the same day.
- As an insured patient, I want eligibility and co-pay checked when I book, so that I spend 0 minutes at the counter. **AC:** < 30 s.
- As a claims supervisor, I want scrubbed claims auto-submitted to DHPO the same day, with remittance reconciled, so that first-pass acceptance exceeds 90 %. **AC:** ≥ 95 % same day; reconciliation < 15 min/day.
- As a clinic, I want NABIDH connected and B2B invoices sent via Peppol, so that the licence and tax obligations are met. **AC:** NABIDH approval; ASP integration live.

### Phase 5

**EP40 · Chain & enterprise** — R5 · XL · P6, P16
*Problem:* no cross-site view, MPI or enterprise controls. *Outcome:* one chain, one patient, one number every morning.
- As a COO, I want one chain MPI and central config with site overrides, so that nobody re-registers across sites. **AC:** 0 cross-site re-registrations.
- As a COO, I want door-to-doctor and punctuality per hospital each morning, so that I act on one number. **AC:** dashboard from OLAP by 08:00.
- As IT governance, I want SAML/OIDC SSO, customer-managed keys and a specialty access policy (open / referral-only / break-glass), so that enterprise review passes. **AC:** SIEM export; access log < 5 min.

**EP41 · IPD & OT** — R5 · XL · P6, P5
*Problem:* only OPD exists; nursing homes need beds and theatres. *Outcome:* inpatients and OT on the same record.
- As a ward nurse, I want admissions, beds and nursing charting, so that inpatients are on the same record. **AC:** bed status live < 2 s.
- As an OT coordinator, I want OT scheduling tied to surgeons' unified calendars, so that clashes disappear. **AC:** 0 double-booked theatres.
- As a billing officer, I want an interim IPD bill and a consolidated discharge bill, so that discharge is not delayed. **AC:** discharge bill < 15 min.

**EP42 · KSA market pack** — R5 · XL · P10, P14, P8B
*Problem:* ZATCA, NPHIES, Wasfaty and RSD gate every KSA sale. *Outcome:* compliant KSA clinics from day one.
- As a KSA clinic, I want ZATCA Phase 2 clearance/reporting with nationality-aware VAT, so that every invoice is compliant from day one. **AC:** 100 % cleared or reported.
- As a claims officer, I want NPHIES eligibility, pre-auth, claims and payment, so that insured patients flow. **AC:** NPHIES certification passed.
- As a pharmacist, I want Wasfaty and SFDA RSD dispense reporting, so that I meet national rules. **AC:** RSD event < 1 min after dispense.

---

## 7. Team & org plan

**Squads (stable, outcome-owned):**
- **Patient Access** (WhatsApp, Edge, ETA)
- **Clinical** (consult, Rx, CDS)
- **Money & Pharmacy**
- **Platform & Trust** (security, residency, consent, SRE)

Added later:
- **Diagnostics & Interop** (Phase 2)
- **Patient App** (Phase 3)
- **GCC** (Phase 4)
- **Hospital** and **KSA** (Phase 5)

| Role | P0 | P1 | P2 | P3 | P4 | P5 |
|---|---|---|---|---|---|---|
| Head of Product / PM | 1 | 2 | 3 | 4 | 5 | 6 |
| Solution architect | 1 | 1 | 1 | 2 | 2 | 2 |
| Business analyst (domain: billing/tax, claims, pharmacy) | 1 | 1 | 2 | 2 | 3 | 4 |
| UX designer / researcher / content (incl. Arabic from P4) | 1 | 2 | 3 | 3 | 4 | 4 |
| Backend engineers (Java core + integrations) | 4 | 6 | 8 | 9 | 11 | 14 |
| Frontend engineers (web) | 2 | 3 | 4 | 4 | 5 | 6 |
| Mobile (Expo) | – | – | – | 2 | 2 | 2 |
| Data/ML (ETA engine, CDS, analytics; Python) | – | 1 | 1 | 2 | 2 | 3 |
| SRE / security engineer | 1 | 1 | 2 | 2 | 3 | 3 |
| QA / SDET | 1 | 2 | 2 | 3 | 3 | 4 |
| Clinical Safety Officer (+ clinical informaticist from P3) | 0.5 | 0.5 | 1 | 2 | 2 | 2 |
| Compliance / DPO per market (IN → UAE → KSA) | 0.5 | 0.5 | 1 | 1 | 2 | 3 |
| Customer success / implementation (field) | – | 2 | 4 | 5 | 7 | 9 |
| **Approx. headcount** | **~13** | **~22** | **~32** | **~41** | **~51** | **~62** |

The UAE (P4) and Hospital/KSA (P5) columns include headcount carried over from the overlapping earlier phases. The XLSX assumed a peak of about 30 people by sprints 14–18 (S04 §5.1). This plan reaches that size in Phase 2, but with an explicit scope cut per phase instead of absorbing a double v1 scope (risk R-09).

**Rituals**
- **Continuous discovery:** 3 interviews or shadowing sessions a week, plus a fortnightly "pilot day" (PM, designer and engineers on site at one clinic with a stopwatch). Monthly pilot advisory council (5 doctors, 2 receptionists, 1 pharmacist).
- **Design review (weekly):** every flow is shown with its task-time target. A release is blocked if a moment that matters regresses by > 10 % (S03 §6 principle 1). Prototype tests use timed tasks, not opinions.
- **Clinical safety review (fortnightly, CSO-chaired):** hazard log, alert analytics, release sign-off. A release with an open hazard and no verifying test cannot ship (QA §4.6).
- **Security & privacy review:** a threat model per epic touching PHI or messaging; DPO sign-off on any new data flow; quarterly regulation watch (S01 risk 11).
- **Metrics review (weekly):** pilot KPIs from EP23 (ETA MAE, wait, booking time, Rx time, dispense time, WhatsApp share).

---

## 8. Validation plan

**Hypotheses to test first** (from S03 §4.1 insights; each has a kill/pivot rule):

| # | Hypothesis | Method | Signal that changes the plan |
|---|---|---|---|
| H1 | Unpredictability, not length, is the pain; ranges cut lobby time (I1) | Time-motion baseline at 10 clinics × 2 cities, then pilot A/B (range vs no ETA) | Lobby median not ≥ 30 % below baseline after 6 weeks → reposition the wedge around desk automation, not ETA |
| H2 | An ETA can be accurate enough to keep trust (I2) | ETA simulator on pilot event logs; live MAE | MAE > 15 min after 4 weeks of tuning → default every clinic to position-only and invest in delay signals |
| H3 | Auto-accept is wanted *with* caps (I4) | Concept probes (S03 §1.3 guide part 5), pilot settings telemetry | < 40 % of clinics enable auto-accept → manual-approve-first UX |
| H4 | Receptionists accept self check-in framed as exceptions (I5) | Shadowing, SUS, exceptions-inbox usage | SUS < 70 or self check-in < 30 % → assisted kiosk mode first |
| H5 | Favourites and repeat beat AI for speed (I6/I7) | Timed Rx tasks; alert override analytics | Repeat Rx > 30 s median → redesign the Rx pad before EP28 |
| H6 | Owner-doctors accept a clinic-default + patient-choice pharmacy (I8) | P7-type interviews (n ≥ 10), pilot routing data | Strong rejection → make "any pharmacy" share patient-initiated only |
| H7 | Families book for dependants; NRI caregivers will pay ~₹300/month (I9, P12) | Patient diary study (n = 30), Kerala/Gulf caregiver survey | < 15 % stated willingness → drop Family Care as B2C; keep it as a free feature |
| H8 | WTP is ₹999–1,499 per doctor/month vs ₹1–2 per visit (S01 §5.3) | Van Westendorp in interviews; pilot cohort A/B pricing | Median acceptable price < ₹700 → lead with per-visit pricing and pharmacy/lab revenue |
| H9 | Consent at the point of care can be fast (I14) | Prototype test of MM10 with patients 60+ | Median > 2 min → assisted consent becomes the default path |
| H10 | Indian-language WhatsApp is needed from day one (I10) | Language mix of pilot bookings | < 10 % non-English → defer LC-04 rest to R3 |

**Interview and pilot plan.** Run S03 §1.3 in waves. **Wave 1 (weeks 0–8): India only**, 74 interviews (IN-M 40, IN-T2 34) with the screener quotas (≥ 40 % paper/WhatsApp-only clinics, ≥ 30 % non-English patients, ≥ 20 % aged 60+ or caregivers), a 30-patient diary study, and stopwatch baselines at 10 clinics per city. **Wave 2 (months 6–9): UAE**, 35 interviews plus 5 design-partner polyclinics, run before EP37–EP39 scope is frozen. **Wave 3 (months 12–15): KSA and Qatar.** Simulated quotes in S03 are replaced by real ones, and the Must list is re-ranked with RICE using measured minutes saved (S03 §6.1 next steps).

**Pilot design.**
- **Cohort A** (5 clinics, month 3) and **cohort B** (10–15 clinics, month 4) in Bengaluru and Hyderabad.
- Free for 3 months, then the tier price, with Solo pricing split between per-doctor and per-visit.
- Two weeks of shadow mode, then live (QA §4.10).
- On-site QA and daily triage for the first two live weeks.
- Weekly KPI review against the Phase 1 exit criteria.

**Portfolio-level signals that change the plan**
- **< 60 % of pilots convert to paid:** stop Phase 2 expansion and re-run discovery.
- **Pharmacy demand outruns clinic demand** (for example, more than 3 inbound chemists per clinic sold): pull EP26 forward.
- **UAE wave 2 shows "booking layer only" demand from specialty clinics** (P3): ship a booking-layer SKU before full EMR parity.

---

## 9. Risks & open decisions for the founder

| # | Decision / risk | Recommendation |
|---|---|---|
| D1 | **Launch market.** The code is built for IN + KSA, while research says India first. | **India first** (S01 §5.1). KSA moves to Phase 5. Generalise the region model now (EP09) so no KSA work is wasted. |
| D2 | **Event backbone.** Valkey Streams (DP review) or Redpanda (F-review §10.4). | **Valkey Streams per region in Phase 1.** We need Valkey anyway for the ETA projection, cache and SSE fan-out. Adopt **Redpanda in Phase 3** when Edge projections and OLAP need replay. Use a CloudEvents envelope from day one so the swap is cheap. |
| D3 | **Patient app timing.** S03 says Won't-now; FE review says Expo soon. | **WhatsApp first.** Build the Expo app in Phase 3 only if H7 and the WhatsApp adoption signals pass. Share the API client and i18n packages so the option stays cheap. |
| D4 | **Pharmacy routing ethics.** In-house default vs patient choice (P7 vs P8A). | Default to the clinic's pharmacy, with a neutral QR that any pharmacy can verify. No discounts, ads or lists. Counsel review before EP18 ships. |
| D5 | **AI as SaMD** (CDSCO draft, SFDA MDS-G010). | Keep the AI non-autonomous: "flag + source, clinician decides". No LLM on PHI before in-region inference (EP36). Start an ISO 13485 / IEC 62304 track in Phase 3. |
| D6 | **Pricing model for Solo.** | A/B test per-doctor vs per-visit in the pilot (H8). Keep Solo Starter free with a visit cap, as the referral engine. |
| D7 | **Drug knowledge base licence cost.** | License one India KB in Phase 1 (it is needed for RX-02 and AI-01). Negotiate GCC terms in Phase 3. Show the source in the UI (S01 §3.5). |
| D8 | **WhatsApp dependency** (pricing, policy, R-05 template delays). | Start with a BSP and move to Tech Provider in Phase 2. Use only utility templates and pass costs through. SMS/RCS fallback. Keep the bot business-specific (Meta's Jan 2026 rule). |
| D9 | **Scope discipline** (R-09: double v1). | Retire the XLSX status column for planning. This epic backlog is the source of truth. Each phase has an explicit cut line, and new scope needs a time-saving metric (S03 north star). |
| D10 | **Key hires before the pilot.** | Hire or contract the CSO and India DPO in Phase 0. Hire the UAE DPO and an Arabic content designer by month 8. |
| D11 | **Hospital modules: build or partner.** | Defer. Decide at month 12 using nursing-home pipeline data. CL-10 stays Won't-now until then. |
| D12 | **Cross-facility sharing legal exposure** (S01 risk 9). | Within-country sharing only. Dual consent, time-boxed, server-enforced expiry, layered on ABDM/HIEs rather than competing with them. |
