# Nabd — the plan on two pages

Everything the review teams produced, boiled down. Details live in the numbered documents; the day-to-day task list lives in **`Nabd-Development-Tracker.xlsx`**; what the finished product should look like is in **`../prototype/`** (open `index.html`).

---

## 1. Where we are today

| | |
|---|---|
| **What works** | A solid staff-facing system for one clinic's outpatients: login with MFA, roles, queue and appointments, consult notes, prescriptions with allergy/pregnancy checks, nursing, packages, basic billing and pharmacy, and a SaaS operator console. 560 backend tests pass. |
| **What is missing** | Everything patient-facing (WhatsApp booking, patient app, public booking site), one profile per doctor and patient across hospitals, consent-based sharing, lab, inpatient wards, insurance claims, proper tax invoices (GST/VAT/ZATCA), AI prescribing support, Arabic. |
| **What is dangerous** | A login flaw lets someone with only a PIN get an owner session; hospital data separation is probably switched off in deployed setups; double booking, double check-in and double payment are possible; staff are logged out every 15 minutes. **No real patient data until these are fixed.** |

## 2. What we decided

1. **Launch in India first** (Bengaluru, Hyderabad): solo and small specialty clinics that run token queues. Dubai at months 9–15, Saudi after month 18, Qatar opportunistic.
2. **Win on time, not on booking.** The live, per-doctor ETA with a WhatsApp "leave home now" message is the product's edge. WhatsApp booking alone is copied easily.
3. **One product for every clinic size.** Solo mode, auto- or manual-accept bookings, self or desk check-in, departments and cross-specialty access are settings.
4. **Consent before sharing.** Records stay with each hospital. Sharing needs the patient and the treating doctor, is time-limited, and every read is shown to the patient.
5. **AI is a safety net.** Allergy, interaction, pregnancy and kidney-dose checks explain themselves and can be overridden in one tap with a reason. Nothing is prescribed automatically.
6. **Architecture:** keep one main application (with enforced internal modules) plus four services: WhatsApp gateway, patient booking edge, AI service, platform console. Live screens get pushed updates instead of polling; health data is never cached in shared caches.
7. **Hosting per country.** India, UAE and Saudi each get their own data region; the UAE and Saudi require in-country hosting.

## 3. What we build, in order

| Phase | When | Goal | Done when |
|---|---|---|---|
| **0 · Make it safe** | Weeks 0–6 | Fix the critical security and data-integrity defects, add CI and monitoring | Every P0 task in the tracker is Done |
| **1 · India pilot** | Months 1–4 | WhatsApp booking, live ETA, self check-in, e-Rx with expiry and QR, GST billing, basic pharmacy; 10–20 pilot clinics | Booking < 60 s, ETA within ±10 min for 80% of visits, waiting-room median < 15 min |
| **2 · Clinic GA** | Months 4–8 | General availability, standalone pharmacy, lab, India data-protection compliance (by 13 May 2027) | Paying clinics outside the pilot |
| **3 · Network** | Months 6–12 | One profile per doctor/patient, consented sharing, public booking site, AI safety checks, patient app (if pilot data supports it) | Cross-hospital request approved end to end |
| **4 · UAE** | Months 9–15 | Arabic, in-country hosting, NABIDH, insurance claims, VAT e-invoicing | First Dubai clinics live |
| **5 · Hospitals + Saudi** | Months 15–24 | Wards, OT, NPHIES, ZATCA, Wasfaty, multi-facility chains | First hospital and first Saudi clinic live |

## 4. Decisions only you can make

| Decision | Our recommendation |
|---|---|
| Product name | Replace "Nabd" (a big Arabic news app and a Feb 2026 doctor-booking app already use it). Shortlist: **Intizam**, Raahat, Nadiyo. Clear it with a trademark attorney. |
| Doctor approval for a patient's own records | Apply the doctor's approval only to hospital-to-hospital sharing. Patients' access rights may make a doctor veto unlawful. |
| Phase 0 length | Plan 6 weeks (engineering estimate); the pilot date does not move. |
| Hosting | Move off Render/Vercel to a provider with India, UAE and Saudi regions before the UAE launch. |
| Patient app | Build it only if the pilot shows strong WhatsApp use and caregiver demand. |

## 5. Where to find what

| You want to… | Open |
|---|---|
| See the finished product | `prototype/index.html` (start at "Product map") |
| Assign and track work | `docs/Nabd-Development-Tracker.xlsx` |
| Understand the market and rules | `docs/strategy/01` |
| Pick a name | `docs/strategy/02` |
| Understand users | `docs/strategy/03` |
| See what's built | `docs/strategy/04` |
| See the roadmap and epics | `docs/strategy/05` |
| See engineering findings and plan | `docs/engineering/06` (summary of 01–05) |
| Understand consent and sharing | `docs/engineering/07` |
