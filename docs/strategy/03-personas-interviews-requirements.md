# Nabd — Personas, Simulated Interviews & Feature Requirements

**Doc:** Strategy 03 · **Owner:** UX Research + Business Analysis · **Status:** Draft v0.1 (hypotheses — not validated) · **Markets:** India (metro + tier-2), UAE, KSA, Qatar

> **Design north star:** *Time.* Every feature must measurably save patient time (waiting, travelling, repeating history) or staff time (typing, calling, chasing, re-keying). If a feature cannot state the minutes it saves and for whom, it does not ship.

---

## 1. Research method note

### 1.1 What this document is (and is not)

Everything below is a **synthesized proto-persona set** and **simulated interviews**, built from (a) founding-team domain knowledge of Indian and GCC outpatient operations, (b) published research on OPD waiting time, consultation length, EHR burden and burnout, and (c) public regulatory material on data protection and e-claims. **None of the interviews happened.** Quotes are illustrative composites written to stress-test the concept, including deliberately skeptical voices.

Treat every persona attribute, quote and number as a **hypothesis (H)** to be confirmed or killed in Phase-1 discovery. Items in the backlog marked "Must" are Must *on current evidence*; at least the top 20 must be re-ranked after real interviews.

### 1.2 Evidence used to ground the personas

| # | Finding used | Source |
|---|---|---|
| E1 | Pune tertiary OPD: mean wait **59 min/visit** vs mean consultation **6.9 min** (n=80, time-motion) | [Acharya et al., IJCMPH 2023](https://www.ijcmph.com/index.php/ijcmph/article/view/11281) |
| E2 | PGIMER neurosurgery OPD: ~45 min registration queue + ~60 min wait for doctor, ~10 min face time | [Indian J Neurosurgery 2015](https://thieme-connect.de/products/ejournals/html/10.1055/s-0035-1549064?lang=en) |
| E3 | Primary-care consult length ranges from 48 s to 22.5 min across 67 countries; ~50% of world population gets ≤5 min | [Irving et al., BMJ Open 2017](https://bmjopen.bmj.com/content/7/10/e017902) |
| E4 | Ambulatory physicians spend ~2 h on EHR/desk work per 1 h of patient time; 1–2 h extra each night | [Sinsky et al., Ann Intern Med 2016](https://doi.org/10.7326/M16-0961) |
| E5 | India nationwide survey (n=763): personal / work / patient-related burnout 47% / 31% / 35% | [Maheshwari et al. (Manipal)](https://researcher.manipal.edu/en/publications/assessment-of-burnout-among-healthcare-practitioners-in-india-a-s/) |
| E6 | Riyadh MOH primary care: median total wait ~23 min, longer in urban centres; 20–30 min cited as acceptable | [Health Services Insights 2023](https://doaj.org/article/af93a77647624a76ac14c3d9dd571055) |
| E7 | Al-Qassim PHCs: majority dissatisfied with waits for dispensing, vitals, dental, radiology | [Alrasheedi et al. 2019 (PMC)](https://pmc.ncbi.nlm.nih.gov/articles/PMC6614942/) |
| E8 | UAE Federal Law No. 2 of 2019 (ICT in Health): health-data localisation (Art. 13), consent for disclosure | [Al Tamimi](https://tamimi.com/law-update-articles/the-federal-law-regulating-the-use-of-information-and-communication-technology-in-the-uae-healthcare-sector) |
| E9 | Dubai NABIDH HIE connects >9.47M records, >1,300 facilities | [WAM](https://www.wam.ae/en/article/b60ypdt-dubai-health-authority’s-nabidh-connects-over-947) |
| E10 | KSA PDPL treats health data as sensitive; explicit consent / statutory basis, extra safeguards | [Bird & Bird 2025](https://cm.twobirds.com/en/insights/2025/saudi-arabia-health-data-under-the-personal-data-protection-law) |
| E11 | Qatar Law No. 13 of 2016 (PDPPL) — first general GCC data-protection law | [DLA Piper](https://www.dlapiperdataprotection.com/?c=QA&t=law) |
| E12 | India DPDP Act 2023: consent must be free, specific, informed; withdrawal as easy as grant; ABDM consent-manager model | [AMLegals](https://amlegals.com/health-data-and-the-dpdp-act-a-practical-guide/) |
| E13 | KSA NPHIES: national FHIR-based eligibility, pre-auth, claims, payment exchange; vendor certification test cases | [MocDoc (vendor)](https://mocdoc.com/nphies-integration) — verify on nphies.sa |
| E14 | DHA eAuthorization on eClaimLink | [Arabian Post](https://thearabianpost.com/dubai-health-authoritys-health-funding-department-announces-launch-of-the-eauthorization-initiative-on-eclaimlink/) |
| E15 | Kerala left-behind elderly in emigrant households report social exclusion; want children's presence over remittances (n=600) | [Rajasenan et al.](https://sgbeta.inflibnet.ac.in/items/e4dcfc7f-0a1d-41d5-a4e6-1e8dc56af2e7/full) |
| E16 | Indian clinics use WhatsApp for booking, reminders, report/Rx PDFs; personal numbers create data-control risk | [Hyperleap (vendor)](https://hyperleap.ai/blog/whatsapp-clinics-hospitals-india-patient-communication), [Greentick (vendor)](https://greentick.ai/blogs/whatsapp-api-for-doctors/) |

**Key implication:** In Indian OPDs the wait-to-consult ratio is roughly **8–10 : 1** (E1, E2). The consult is not the bottleneck; the *unpredictability* of the queue is. Doctors lose comparable time to documentation (E4), feeding burnout (E5). Nabd's two levers are therefore **predictable queue time** for patients and **sub-90-second documentation** for doctors. Regulatory sources E8–E14 are law-firm or vendor summaries. Counsel must confirm them before build.

### 1.3 Real-interview plan (Phase-1 discovery, 8 weeks)

**Sample size.** Aim for 5–6 interviews per persona per market where the persona exists. That is enough to saturate themes for qualitative work. Then run a quantitative survey of 300–400 patients per market to size the problems.

| Persona cluster | IN metro | IN tier-2 | UAE | KSA | QA | Total |
|---|---|---|---|---|---|---|
| Solo / small-clinic doctors (GP, paeds, dental, owner-doctor) | 6 | 6 | 5 | 5 | 3 | 25 |
| Specialists at multiple facilities | 4 | 2 | 3 | 3 | 2 | 14 |
| Front desk (receptionist, assistant) | 6 | 4 | 4 | 4 | 2 | 20 |
| Nurses / triage | 3 | 2 | 3 | 5 | 2 | 15 |
| Pharmacists (standalone + in-clinic) | 5 | 5 | 4 | 4 | 3 | 21 |
| Lab tech / manager | 3 | 3 | 2 | 2 | 2 | 12 |
| Billing / claims | 2 | 2 | 5 | 5 | 3 | 17 |
| Admin / COO / IT-compliance | 3 | 2 | 3 | 3 | 3 | 14 |
| Patients and caregivers (incl. NRI caregivers) | 8 | 8 | 6 | 6 | 4 | 32 |
| **Total** | 40 | 34 | 35 | 37 | 24 | **170** |

**Recruiting screener (core questions):**
1. Role, facility type (solo / single-specialty / polyclinic / hospital / chain), number of doctors and daily OPD volume. *Exclude anyone who works for an HMS or health-tech vendor.*
2. Current tools: paper register, Excel, Practo/Ray, Eka Care, an HMS (e.g., Cerner, Epic, local vendor), WhatsApp Business. *Quota at least 40% paper or WhatsApp-only clinics.*
3. Who accepts bookings, and who checks patients in? *Use this to cover every workflow variant.*
4. Patients: visits in the last 6 months, booking channel, whether they booked for someone else, insurance status, and preferred language. *Quota at least 30% non-English-primary and at least 20% aged 60+ or caregivers.*
5. Willingness to take part in a 60-minute session plus a 30-minute shadowing visit (staff).

**Discussion guide (60 min, semi-structured):** (1) Warm-up: role and a typical day (5 min). (2) Walk me through yesterday, hour by hour, and where you waited or redid work (15 min). (3) Critical incident: the worst queue day in the last month (10 min). (4) Current tools and workarounds: show me your WhatsApp, register, Excel (10 min). (5) Concept probes, using a Figma prototype or WhatsApp sandbox: live ETA, auto-accept, self check-in, consented sharing, AI Rx, e-Rx expiry, pharmacy link. Probe likes, fears and what you would turn off (15 min). (6) Willingness to pay with Van Westendorp price questions; who decides and who pays (5 min).

**Field methods:** at least 2 hours of contextual inquiry or shadowing at 10 clinics per market (stopwatch time-motion, reusing E1's method). Patient diary study over WhatsApp for one visit cycle (n=30). Arabic sessions are run by native moderators; Malayalam, Telugu, Kannada, Marathi and Hindi by local moderators.

**Incentives (indicative):** Doctors get ₹3,000–5,000 / AED 400–600 / SAR 400–600 / QAR 400 per hour, or a free year of Nabd Solo. Staff get ₹1,000–1,500 / AED 150 / SAR 150 / QAR 150 in vouchers. Patients get ₹500–800 / AED 100 / SAR 100 / QAR 100 in vouchers, plus a free family health checkup at partner labs. Executives get a benchmark report on their facility versus peers, not cash. Follow facility conflict-of-interest rules and avoid any payment that looks like referral inducement.

**Ethics:** informed consent (bilingual), no PHI captured, recordings deleted after synthesis, IRB-style review for patient diary study.

---

## 2. Personas

Market codes: **IN-M** India metro, **IN-T2** India tier-2, **AE** UAE, **SA** KSA, **QA** Qatar. Tech comfort is rated from 1 (low) to 5 (high). "⏱ lost" marks the minutes lost at that step.

### Persona index

| # | Persona | Role / setting | Market | Primary language(s) | RTL |
|---|---|---|---|---|---|
| P1 | Dr. Arjun Rao, 41 | Solo GP, no staff | IN-M Bangalore | English, Kannada | No |
| P2 | Dr. Sravani Reddy, 38 (+ Lakshmi, assistant) | Paediatrician + 1 assistant | IN-M Hyderabad | Telugu, English, Hindi | No |
| P3 | Dr. Rania Haddad, 36 | Dentist-owner, 3 chairs | AE Dubai | English, Arabic | Yes |
| P4 | Priya Shinde, 26 | Receptionist, 14-doctor polyclinic | IN-M Mumbai | Marathi, Hindi, English | No |
| P5 | Maricel Santos, 34 | Triage nurse, hospital OPD | SA Riyadh | English, Tagalog, working Arabic | Yes (patients) |
| P6 | Vikram Nair, 49 | COO, 5-hospital chain | IN-T2 Kerala/Karnataka | English, Malayalam | No |
| P7 | Dr. Mahesh Patil, 52 | Owner-doctor + attached pharmacy | IN-T2 Nashik | Marathi, Hindi, English | No |
| P8A / P8B | Ramesh Gupta, 45 / Ahmed Fathy, 39 | Chemist shop / community pharmacist | IN-T2 Lucknow / QA Doha | Hindi / Arabic, English | Yes (8B) |
| P9 | Sunita Verma, 35 | Lab manager, NABL-track diagnostic lab | IN-T2 Indore | Hindi, English | No |
| P10 | Joseph Mathew, 42 | Claims & RCM supervisor | AE Dubai (+ new KSA branch) | English, Malayalam, basic Arabic | Yes (docs) |
| P11 | Karthik Iyer, 31 | Patient, software PM | IN-M Bangalore | English, Tamil | No |
| P12 | Thankamma Joseph, 74 + son Biju, 46 | Elderly patient + caregiver abroad | IN-T2 Thrissur + AE | Malayalam / English | No |
| P13 | Aisha Khan, 33 | Insured expat patient | AE Dubai | English, Urdu | No |
| P14 | Nouf Al-Harbi, 31 | Mother booking for 3 children | SA Dammam | Arabic only | **Yes** |
| P15 | Dr. Imran Qureshi, 49 | Interventional cardiologist at 3 hospitals | IN-M Chennai | English, Tamil, Urdu | No |
| P16 | Mohammed Al-Kuwari, 40 | Head of IT & Information Governance | QA Doha | Arabic, English | Yes |

---

### P1 · Dr. Arjun Rao, 41 — solo GP, Whitefield, Bangalore (IN-M)
**Setting:** One-room clinic seeing 35–50 patients a day (9–1 and 5–9), with no staff. **Tech** 4 · **Devices:** Android phone (always in hand), old laptop, thermal printer · **Languages:** English, Kannada, some Hindi/Tamil.
- **Goals:** See patients without phone interruptions, get home by 21:30, keep regulars loyal against chain clinics.
- **Frustrations:** WhatsApp booking messages answered between patients. Walk-ins crowd the door. Pharmacists call him about his handwriting. No visit history.
- **Day (⏱ = time lost):** 08:40 answers 22 overnight WhatsApps (⏱ 20 min) → 09:00–13:00 sees 24 patients, unlocks his phone ~40 times (⏱ 35 min) → 13:10 tallies cash and UPI in a notebook (⏱ 10 min) → 17:30 six arrive together and argue over who came first (⏱ 15 min) → 21:15 photographs the register as a "backup" (⏱ 5 min).
- **Quotes:** "My phone is my receptionist, and it is a bad one." "If an app makes me type more than I write, I uninstall it in a week."
- **Success metrics:** No WhatsApp replies during consults; repeat Rx < 30 s; leaves by 21:30 on 4 nights out of 5.
- **Top features:** WhatsApp auto-booking with caps, one-hand token queue, Rx favourites/repeat, UPI link, "running late" broadcast.

### P2 · Dr. Sravani Reddy, 38 + Lakshmi (assistant, 29) — paediatrics, Kukatpally, Hyderabad (IN-M)
**Setting:** 60–90 children a day, with monsoon and flu peaks. Lakshmi handles phones, weighing and the vaccine register. **Tech:** doctor 4, Lakshmi 3 · **Devices:** iPad at the desk, Android phones · **Languages:** Telugu, English, Hindi (Dakhini).
- **Goals:** Error-free weight-based dosing, vaccine reminders that reach parents, sick and well children kept apart.
- **Frustrations:** Parents arrive an hour early "to be safe" and the room overflows with feverish children. Parents lose the paper vaccine cards.
- **Day:** 10:00 Lakshmi answers ~60 "what's my token?" calls (⏱ 70 min) → 10:30–14:00 doctor works out mg/kg by hand (⏱ ~45 min) → 15:00 Lakshmi types vaccine reminders (⏱ 30 min) → 18:00 20 parents in a room built for 12 (average patient wait ~65 min, H).
- **Quotes:** "Lakshmi spends half her day saying 'You are 34, current is 21.'" "If the app says come at 6:40, they must be seen by 6:50."
- **Success metrics:** Calls −70%; zero dose-calculation errors; on-time vaccination > 85%.
- **Top features:** Live token/ETA on WhatsApp, paediatric dose calculator, vaccine schedule auto-reminders, sibling booking, sick/well slots.

### P3 · Dr. Rania Haddad, 36 — dentist-owner, JLT, Dubai (AE)
**Setting:** 3 chairs, 2 associates, 1 hygienist, 1 coordinator. Appointments run 30–90 minutes; about 70% of patients are insured. **Tech** 4 · **Devices:** MacBook, iPhone, a dental system she dislikes · **Languages:** English, Levantine Arabic; multinational patients (Arabic RTL for patient messages).
- **Goals:** Chair utilisation (an empty chair costs ~AED 600 an hour), fewer insurance rejections, Google reviews.
- **Frustrations:** No-shows of 12–15%. Crown pre-auths take days. Patients read an "ETA" as a promise even when procedures overrun.
- **Day:** 08:30 coordinator phones the day's patients to confirm (⏱ 45 min) → 10:00 no-show with no waitlist (⏱ 60 min of chair time) → 13:00 crown pre-auth rejected for a coding mismatch (⏱ 25 min) → 17:30 an RCT overruns and 3 patients wait 40 min without being told.
- **Quotes:** "Never a live ETA for dental — 'running 20 min late' I can live with." "Fill my cancelled slot automatically and I'll pay in a month."
- **Success metrics:** No-shows < 6%; chair utilisation > 85%; pre-auth < 24 h.
- **Top features:** WhatsApp confirm/cancel, waitlist backfill, procedure-length slots, delay-only notices, eClaimLink pre-auth, Arabic/English messages.

### P4 · Priya Shinde, 26 — receptionist, 14-doctor polyclinic, Andheri, Mumbai (IN-M)
**Setting:** 400–500 OPD patients a day across 9 specialties; 3 at the desk; token printer; legacy HMS. **Tech** 3 (fast typist, wary of new systems) · **Devices:** desktop, landline, a shared WhatsApp phone · **Languages:** Marathi, Hindi, English.
- **Goals:** A calm queue, no blame for late doctors, leaving on time.
- **Frustrations:** Doctors 30–60 min late while patients shout at her. Three "Suresh Patil" records. Data typed three times (HMS, billing sheet, referral slip).
- **Day:** 09:00 40 walk-ins at 2.5 min each makes a 30-min line → 10:30 the ortho doctor is late and she phones 18 patients (⏱ 25 min) → 12:00 re-writes 15 referral slips (⏱ 45 min) → 16:00 photocopies TPA cards and fills forms by hand (⏱ 12 min each).
- **Quotes:** "If patients check themselves in, what am I for?" "Give me keyboard shortcuts, not ten mouse clicks."
- **Success metrics:** Walk-in registration < 45 s; zero manual delay calls; fewer abuse incidents.
- **Top features:** Keyboard-first registration, phone lookup and fuzzy match, multi-doctor board, one-click delay broadcast, referral as a queue transfer.

### P5 · Maricel Santos, 34 — triage nurse, private hospital OPD, Riyadh (SA)
**Setting:** 40 clinics, ~1,200 visits a day, a 4-nurse vitals station, mixed Saudi and expat patients. **Tech** 4 · **Devices:** workstation on wheels, a non-integrated BP monitor · **Languages:** English, Tagalog, working Arabic; patient surfaces must be Arabic RTL.
- **Goals:** Catch red flags early, enter vitals once, avoid language mix-ups.
- **Frustrations:** Vitals written on paper, then typed. Allergy history missing. No view of who in the waiting room is deteriorating.
- **Day:** 08:00 30 patients at vitals, 4 min each, 60% of it typing (⏱ ~70 min) → 10:00 a chest-pain patient sits in the general queue for 25 min (safety risk) → 12:00 re-asks history because the form was English-only (⏱ 5 min each).
- **Quotes:** "Triage should push someone up the queue in one tap, not a call to the charge nurse." "Arabic on the patient's side, English on mine — same record."
- **Success metrics:** Vitals < 60 s; every red-flag patient escalated < 5 min; ≥ 60% of patients arrive with digital pre-visit history.
- **Top features:** Arabic WhatsApp pre-visit questionnaire, device-linked vitals, ESI-style priority that reorders the queue, bilingual record view.

### P6 · Vikram Nair, 49 — COO, 5-hospital chain (Kochi, Thrissur, Kozhikode, Mangalore, Coimbatore) (IN-T2)
**Setting:** 900 beds, ~4,500 OPD visits a day, 3 legacy HMS instances, a central call centre, NABH accredited. **Tech** 3 (dashboards, Excel) · **Devices:** laptop, iPad, WhatsApp · **Languages:** English, Malayalam.
- **Goals:** OPD throughput, consultant utilisation, patient leakage < 10%, one patient ID across sites, Gulf NRI patients.
- **Frustrations:** No cross-site view, consultant lateness not measured, patients re-registered at each hospital.
- **Day:** 08:00 waits for 5 MIS emails in 5 formats (⏱ 30 min) → 11:00 VIP waited 2 h at Kozhikode, firefighting (⏱ 45 min) → 15:00 4th HMS integration call this quarter (⏱ 60 min).
- **Quotes:** "One number every morning: door-to-doctor time per hospital." "Records stay with each hospital legally, but the patient shouldn't feel that."
- **Success metrics:** Door-to-doctor < 30 min; consultant on-time start > 90%; zero cross-site re-registration.
- **Top features:** Chain dashboard, chain MPI, punctuality tracking, central call-centre booking, per-site workflow config, SSO.

### P7 · Dr. Mahesh Patil, 52 — owner-doctor with an attached pharmacy, Nashik (IN-T2)
**Setting:** General medicine and diabetology, 70 patients a day, 1 receptionist; the pharmacy is run by his nephew and a D.Pharm. **Tech** 2–3 · **Devices:** desktop, Android, standalone pharmacy billing · **Languages:** Marathi, Hindi, English.
- **Goals:** Pharmacy profitability, expiry control, diabetic continuity.
- **Frustrations:** Every Rx re-keyed at the pharmacy. Stock-outs send patients elsewhere. Expired strips written off.
- **Day:** 10:00–14:00 pharmacy re-types 70 Rx at 1.5 min each (⏱ 105 min) → 13:00 a drug is out of stock and the patient leaves (lost sale and lost follow-up) → monthly near-expiry check by hand (⏱ 3 h).
- **Quotes:** "e-Rx to *any* pharmacy? Why build my competitor's business?" "Show the Rx already in the billing screen and I'll buy."
- **Success metrics:** Rx-to-bill < 20 s; expiry write-off −50%; diabetic follow-up adherence > 70%.
- **Top features:** Clinic → pharmacy handoff (in-house default plus patient choice), stock-aware prescribing, expiry alerts, follow-up reminders, GST billing, offline mode.

### P8 · Standalone pharmacists (paired persona)
**P8A · Ramesh Gupta, 45 — chemist shop owner, Aliganj, Lucknow (IN-T2).** About 250 bills a day, 2 helpers, prescriptions from ~30 local doctors. **Tech** 2 · **Devices:** billing PC, Android · **Languages:** Hindi.
**P8B · Ahmed Fathy, 39 — pharmacist-in-charge, chain community pharmacy, Doha (QA).** About 70% of scripts insured. **Tech** 4 · **Devices:** POS, insurer portals · **Languages:** Arabic, English (RTL labels).
- **Goals:** Fast counter, compliance (Schedule H1 register in IN; controlled drugs in QA), fewer calls to doctors.
- **Frustrations:** 8A: illegible prescriptions and patients reusing old antibiotic prescriptions. 8B: insurance approvals for chronic meds and verifying prescriptions from private clinics.
- **Day:** 8A: 11:00 deciphers 15 illegible Rx (⏱ 30 min) → 18:00 12 people at the counter (⏱ patients wait ~15 min) → Sunday writes the H1 register (⏱ 90 min). 8B: 10:00–22:00 about 25 approvals at ~8 min each (⏱ patients wait for the payer).
- **Quotes:** 8A: "An e-Rx with a QR and an expiry — finally I can refuse the old antibiotic Rx without a fight." 8B: "Insurance approval is my waiting room."
- **Success metrics:** e-Rx dispense < 20 s; H1 register auto-generated; insured dispense wait < 5 min.
- **Top features:** QR verify and auto-filled bill, expiry/refill enforcement, auto H1 register, standalone tenant, eligibility at dispense (GCC), Hindi/Arabic UI.

### P9 · Sunita Verma, 35 — lab manager, diagnostic lab + 6 collection centres, Indore (IN-T2)
**Setting:** 600 samples a day from walk-ins, clinic referrals and home collection; working toward NABL. **Tech** 4 · **Devices:** LIS desktop, barcode printers, analysers · **Languages:** Hindi, English.
- **Goals:** Turnaround time, no mix-ups, doctor-referral revenue.
- **Frustrations:** Handwritten test slips. "Is my report ready?" calls. Critical values phoned to doctors who don't answer.
- **Day:** 07:30 fasting rush, 40 people in the registration line (⏱ 25 min for patients) → 12:00 "report ready?" calls (⏱ 60 min a day) → 16:00 critical potassium result needs 4 calls to reach the doctor (⏱ 20 min, safety risk).
- **Quote:** "A digital order with patient details saves 3 minutes a sample and every typo."
- **Success metrics:** Registration < 60 s; zero "ready?" calls; critical values acknowledged < 15 min.
- **Top features:** Digital orders from clinics, barcode workflow, WhatsApp report link, critical-value escalation with read receipt, home-collection scheduling.

### P10 · Joseph Mathew, 42 — claims/RCM supervisor, clinic group, Dubai (+ new Riyadh branch) (AE / SA)
**Setting:** 4 Dubai clinics on DHA eClaimLink and 1 Riyadh clinic on NPHIES, 5 coders. **Tech** 4 (Excel power user) · **Languages:** English, Malayalam, basic Arabic (Arabic payer documents).
- **Goals:** First-pass acceptance > 90%, resubmission inside payer windows, A/R < 45 days.
- **Frustrations:** Coding doesn't match documentation. Pre-auth requested after the service. Manual remittance reconciliation. NPHIES learning curve.
- **Day:** 09:00 reconciles remittance in Excel (⏱ 90 min) → 11:00 40 "missing diagnosis" rejections sent back to doctors (⏱ 2 days of chasing) → 14:00 walk-in eligibility checked on portals (⏱ 4 min each).
- **Quote:** "Rejections are born at the front desk and in the consult room, not in my office."
- **Success metrics:** First-pass acceptance > 90%; same-day submission ≥ 95%; reconciliation < 15 min a day.
- **Top features:** Eligibility at booking, pre-auth prompts at order time, claim scrubber, eClaimLink/NPHIES connectors, auto-reconciliation, rejection queue.

### P11 · Karthik Iyer, 31 — working-professional patient, HSR Layout, Bangalore (IN-M)
**Tech** 5 · **Devices:** iPhone, MacBook · **Languages:** English, Tamil. **Context:** 4–6 GP or dermatology visits a year; also books for his parents.
- **Goals:** Book in < 1 min, arrive just in time, no paper.
- **Frustrations:** A "10:30 appointment" means being seen at 11:45. Half-days off for a 10-minute consult. Reports scattered across chats.
- **Day:** 10:15 arrives → 10:15–11:40 waits in the lobby with his laptop (⏱ 85 min) → 11:52 pharmacy queue (⏱ 10 min); about 2.5 h lost per visit including travel.
- **Quote:** "Tell me honestly when to leave home. I don't need an app, I need one WhatsApp message."
- **Success metrics:** Lobby time < 15 min; all Rx and reports in one thread.
- **Top features:** WhatsApp slot buttons, "leave now" nudge, QR check-in, digital Rx/reports, family booking.

### P12 · Thankamma Joseph, 74 + son Biju, 46 (in Dubai) — Thrissur, Kerala (IN-T2 + AE)
**Thankamma:** Type 2 diabetes, hypertension and knee osteoarthritis. Basic Android phone; Malayalam only; **Tech** 1; a home nurse visits twice a week. **Biju:** engineer in Dubai, **Tech** 4, WhatsApp-heavy, pays the bills. Kerala's emigration leaves many elderly parents without children nearby (E15).
- **Goals (Biju):** Real-time view of her appointments, Rx and labs; approve and pay remotely; alerts on missed follow-ups.
- **Frustrations:** Learns about medication changes a week late. Blurry photos of reports. She sits through long fasting waits.
- **Day (Thankamma):** 07:00 leaves fasting → 07:30–09:00 lab queue (⏱ 90 min, fasting) → 10:00–12:30 doctor queue (⏱ 150 min) → 13:00 pharmacy → Biju hears at 21:00 Dubai time.
- **Quotes:** Thankamma: "I don't read English. Tell me in a voice message." Biju: "I want to be in the room without being in the room."
- **Success metrics:** Total visit time < 90 min; Biju notified within 5 min of every Rx or result.
- **Top features:** Consented caregiver access, Malayalam voice reminders, remote payment, lab → consult sequencing, missed-follow-up alerts.

### P13 · Aisha Khan, 33 — insured expat, Dubai Marina (AE)
**Tech** 5 · **Devices:** iPhone, aggregator apps · **Languages:** English, Urdu. **Context:** Enhanced employer plan with a 20% co-pay.
- **Goals:** Know her co-pay up front, see an in-network doctor fast, no "not covered" surprises.
- **Frustrations:** 15-minute insurance check at the counter while feverish. "Approval pending" at the pharmacy. Records don't follow her between clinics.
- **Day:** 18:10 arrives → 18:10–18:25 eligibility check at the desk (⏱ 15 min) → 18:25–18:55 waits (⏱ 30 min) → 19:10 pharmacy waits for approval (⏱ 20 min).
- **Quote:** "Verify my insurance when I book, not when I'm standing at the desk with a fever."
- **Success metrics:** Zero counter time for insurance; co-pay known before arrival.
- **Top features:** Card capture and eligibility at booking, co-pay estimate, Emirates ID scan, consented record sharing.

### P14 · Nouf Al-Harbi, 31 — Saudi mother of 3, Dammam (SA)
**Tech** 3 (WhatsApp, Snapchat, Sehhaty) · **Devices:** iPhone · **Languages:** Arabic only (RTL); prefers voice notes. **Context:** Books for her children aged 2, 6 and 9; her husband often drives.
- **Goals:** Book siblings together, wait in the car, a female doctor for herself.
- **Frustrations:** Systems want each child's own phone number. English forms. Being called by name in a mixed waiting room.
- **Day:** 16:30 calls for 2 sick children, put on hold and transferred (⏱ 12 min) → 17:30–18:20 waits inside with a febrile toddler (⏱ 50 min).
- **Quotes:** "Message me in Arabic when it's our turn — I'll wait in the car." "Don't make me type. Give me buttons."
- **Success metrics:** Booking < 60 s for 2 children; ≤ 10 min inside the clinic.
- **Top features:** Native Arabic RTL WhatsApp with buttons, dependants under the guardian's ID, back-to-back sibling slots, call-forward alert, female-doctor filter, Hijri dates.

### P15 · Dr. Imran Qureshi, 49 — interventional cardiologist at 3 hospitals, Chennai (IN-M)
**Setting:** Hospital A mornings (salaried), hospital B evenings (fee-share), hospital C 2 days a month; 3 HMSs and 3 logins. **Tech** 4 · **Devices:** iPhone, iPad · **Languages:** English, Tamil, Urdu.
- **Goals:** One schedule, no double-booking, protected evenings, other hospitals' investigations available.
- **Frustrations:** Hospital B books him while he is in the cath lab at A. Three prescription formats. "Bring the CD from the other hospital."
- **Day:** 13:30 a procedure overruns while B's queue has already started (⏱ 25 patients × 40 min) → 19:00 asks patients for external echo reports (⏱ 10 min each).
- **Quotes:** "AI telling me what to prescribe? No. AI telling me the eGFR is 28 before metformin — yes." "My profile is mine; records belong to the hospital — fine — but let me request them properly."
- **Success metrics:** Zero cross-hospital clashes; patients notified < 2 min after a delay; consented record access < 2 min.
- **Top features:** Global profile and unified calendar, delay propagation, consented cross-hospital request, explainable CDS alerts, portable favourites.

### P16 · Mohammed Al-Kuwari, 40 — Head of IT & Information Governance, 200-bed private hospital, Doha (QA)
**Setting:** Owns MOPH compliance, cybersecurity and vendor risk. **Tech** 5 · **Devices:** laptop, SIEM console · **Languages:** Arabic, English.
- **Goals:** In-country residency, auditability, an end to shadow IT on personal WhatsApp.
- **Frustrations:** Vendors who can't say where data lives. No read-audit. Every integration means a new security review.
- **Day:** 09:00 vendor security questionnaire (⏱ 2 h) → 13:00 investigates a doctor forwarding reports on personal WhatsApp (⏱ 1 h) → 16:00 manual access-log pull for an audit (⏱ 90 min).
- **Quote:** "WhatsApp is already our biggest leak. Make it governed and that's a reason to buy; add a leak and I block you."
- **Success metrics:** Security review < 2 weeks; any access log produced in < 5 min; zero PHI in message bodies.
- **Top features:** Regional hosting, per-tenant/customer-managed keys, immutable audit log, RBAC plus break-glass, consent ledger, SSO, SIEM export.

---

## 3. Simulated interviews

> *Simulated, illustrative transcripts.* "I" = interviewer. Answers are deliberately varied, with skepticism kept in.

### 3.1 Dr. Arjun Rao (P1) — solo GP, Bangalore

**I:** Walk me through how a patient books with you today.
**Arjun:** They WhatsApp my personal number. I reply "come after 6" or give a token. Sometimes I forget to reply and they come anyway.
**I:** How long does that take you each day?
**Arjun:** Honestly, an hour, broken into tiny pieces, which is the worst kind.
**I:** What if bookings were auto-accepted up to a cap you set, with tokens issued automatically?
**Arjun:** Yes, but I need a cap per session and the ability to block walk-in slots. Without a cap, auto-accept will drown me on Monday mornings.
**I:** Patients would get a live ETA on WhatsApp. How do you feel?
**Arjun:** Nervous. If I spend 20 minutes with one elderly patient, the ETA becomes a lie and they blame me. Show a range. And let me hide it on bad days.
**I:** How do you write prescriptions?
**Arjun:** On a pad. Typing on a phone is slow. I would do it only if my top 50 combinations are one tap: "viral fever adult", "HTN refill".
**I:** AI that warns about interactions or allergies?
**Arjun:** Warnings are fine if they're rare and right. If it pops up for every paracetamol, I'll ignore all of them.
**I:** What would you pay?
**Arjun:** ₹1,000–1,500 a month if it saves me an hour a day. Not more than my internet bill plus a bit. A free tier first.
**I:** What worries you most?
**Arjun:** Data. If Nabd shows my patients to the chain clinic next door on the aggregator, I'm out.
**I:** What would make you recommend it?
**Arjun:** If after a month I eat dinner with my kids.

**Takeaways:** a cap is essential for auto-accept; show the ETA as a range, with an opt-out; favourites-first prescribing; fear of the aggregator poaching patients means patient ownership must be explicit.

### 3.2 Priya Shinde (P4) — receptionist, Mumbai polyclinic

**I:** What is the hardest hour of your day?
**Priya:** 9 to 10. Forty people, three of us, and the doctors are not in yet.
**I:** What takes longest in registration?
**Priya:** Finding the old record. Same names, wrong phone numbers. And typing the address that nobody needs.
**I:** If patients could check themselves in with a QR code at the door?
**Priya:** *(pause)* Then management will say we need two receptionists, not three. Also, old people won't scan. They'll still come to me, just angrier.
**I:** What if self check-in handled the easy 50% and you handled exceptions?
**Priya:** If it means fewer people shouting at my counter, okay. But I must be able to see who checked in, and correct it. Patients will check in from home to jump the queue.
**I:** Geofencing could restrict check-in to near the clinic.
**Priya:** Good. Otherwise it's useless.
**I:** When a doctor is late, what do you do?
**Priya:** Call each patient. Or nobody calls, and I get abused.
**I:** A button that messages all waiting patients with the new estimate?
**Priya:** That's the best thing you've said. But the doctor must not be able to block it. They always say "I'm coming in 5 minutes."
**I:** Mouse or keyboard?
**Priya:** Keyboard. F-keys. The old DOS system was faster than the new one.
**I:** What would make you hate Nabd?
**Priya:** If it's slower than paper on day one. We have no training time.

**Takeaways:** fear of job loss, so self check-in should be framed as "exception handling"; geofence check-in; delay broadcast that does not depend on the doctor; keyboard-first; zero-training UX.

### 3.3 Dr. Rania Haddad (P3) — dentist-owner, Dubai

**I:** How do patients book?
**Rania:** Instagram DMs, WhatsApp, calls, a booking widget on the website. Four inboxes.
**I:** Your biggest leak?
**Rania:** No-shows and late cancels. 12–15% of chair time.
**I:** WhatsApp confirm/reschedule buttons 24 hours before, with the slot backfilled automatically from a waitlist?
**Rania:** That I'd pay for tomorrow. AED 800 a month easily, if it fills two chairs a week.
**I:** And a live ETA for patients?
**Rania:** No. Dental is appointment-based. If I show a live ETA, patients arrive late on purpose. A "running 15 minutes late" notice, only when I choose to send it, is fine.
**I:** Auto-accept bookings?
**Rania:** For check-ups and cleaning, yes. For implants, no. My coordinator must screen those, because they need a consult first.
**I:** Insurance?
**Rania:** Pre-auth for crowns and RCT is a nightmare. If the system knows the plan's dental limit at booking, my coordinator stops promising things.
**I:** Shared records across clinics, with consent?
**Rania:** Patients rarely ask. X-rays, yes, if another dentist has them. But that's a "could".
**I:** Arabic?
**Rania:** Messages in Arabic for my Emirati and Arab patients. The admin side can stay English.
**I:** Any deal-breakers?
**Rania:** If it can't do a tooth chart, it's not for me. I'd keep my dental software and use you just for booking and the queue.

**Takeaways:** configurable ETA modes per specialty; auto-accept by service type; the waitlist backfill alone is a wedge with clear ROI; a "booking layer only" mode for clinics with specialist systems, so integration is needed.

### 3.4 Dr. Mahesh Patil (P7) — owner-doctor with pharmacy, Nashik

**I:** How does a prescription reach your pharmacy?
**Mahesh:** The patient carries my slip downstairs and they type it again.
**I:** What if it appeared in the pharmacy billing screen automatically?
**Mahesh:** That I want. My nephew would save two hours a day.
**I:** Nabd also lets patients send their e-Rx to any pharmacy.
**Mahesh:** *(laughs)* Why would I build my competitor's business? Half my income is the pharmacy.
**I:** Patients may already take their paper slip anywhere. What if the default is your pharmacy, and the patient can choose otherwise?
**Mahesh:** Default mine. OK. But don't send them a list of other pharmacies with discounts.
**I:** Stock-aware prescribing, where you see what is available while writing?
**Mahesh:** Very good. I'll substitute a brand I have instead of losing the patient.
**I:** An e-Rx expiry, say 30 days for most and 5 days for antibiotics?
**Mahesh:** For antibiotics, yes. For my diabetics, monthly refills for 3 months without coming back is better for them.
**I:** Price?
**Mahesh:** Clinic plus pharmacy, ₹2,500 a month. The pharmacy part alone justifies ₹1,500 if it does GST billing and expiry.
**I:** Concerns?
**Mahesh:** Internet. Power cuts. The pharmacy must bill even when the internet is down.

**Takeaways:** a potential conflict of interest around pharmacy routing. Policy needs a clear default plus patient choice, and no steering ads (needs a compliance review). Refill-aware expiry. Offline-first pharmacy billing is a Must for tier-2.

### 3.5 Dr. Imran Qureshi (P15) — cardiologist at 3 hospitals, Chennai

**I:** How do you manage three schedules?
**Imran:** My secretary has a WhatsApp group with three coordinators. It fails weekly.
**I:** A single global calendar across hospitals, each hospital seeing only its own slots plus "busy" blocks?
**Imran:** Yes, but hospitals will fight about it. Hospital B shouldn't know I'm at A, just that I'm unavailable.
**I:** If you run late at A, B's patients get an automatic delay message?
**Imran:** Only if it's accurate. Let me tap "45 minutes late" from my phone, and do the rest yourselves.
**I:** AI prescription suggestions?
**Imran:** I don't trust "suggestions". Who's liable? I've seen interaction checkers fire on everything. I want the eGFR, potassium and allergy flags in my face, with sources. No auto-filled drugs.
**I:** If the AI showed its reasoning and you could tune the alert levels?
**Imran:** Then I'd trial it. Show me sensitivity data on Indian patients, not American.
**I:** Requesting records from another hospital with patient consent?
**Imran:** If the patient approves on WhatsApp in front of me and I get the echo in two minutes, it changes care. If it's a form and 3 days, forget it.
**I:** Who should approve on the hospital side?
**Imran:** Auto-approve if the patient consents and it's a treating doctor. Don't make it wait for medical records staff.
**I:** Willingness to pay?
**Imran:** The hospitals pay. I'd pay ₹500 a month for my personal calendar and favourites.

**Takeaways:** a privacy-preserving "busy" calendar across tenants; distrust of AI, so default to safety alerts with explainability and local validation; consent at the point of care; release on a policy rule rather than a manual step.

### 3.6 Biju Joseph (P12) — caregiver son in Dubai, for mother in Thrissur

**I:** How do you know what happens at Amma's doctor visits?
**Biju:** The home nurse sends me a photo of the prescription. Sometimes blurry. Sometimes never.
**I:** What if you were a linked caregiver on her Nabd profile, with her consent?
**Biju:** That's exactly what I need. Notifications when she checks in, when the prescription is written, and when lab results come.
**I:** Should you see everything?
**Biju:** *(thinks)* Medicines, labs, appointments. Maybe not everything. She has a right to privacy too. Let her choose. Actually, let the doctor help her choose, because she'll say yes to anything.
**I:** How would she give consent?
**Biju:** A Malayalam voice message explaining it, and she presses "yes", or the nurse helps at the clinic. Not an OTP; she can't read SMS.
**I:** Payment?
**Biju:** A UPI link to me or a card from the UAE. I pay everything anyway.
**I:** Would you book for her?
**Biju:** Yes, and I want the lab first thing, then the doctor right after, so she doesn't sit fasting for three hours.
**I:** Concerns?
**Biju:** Fraud. Old people get scam messages. Messages must come from a verified business name, and never ask for her bank details.
**I:** Would you pay for this yourself?
**Biju:** ₹300 a month for "family care" alerts? Yes. Many Gulf Malayalis would.

**Takeaways:** a caregiver role with scoped access; assisted consent (voice plus staff witness); cross-border payment; sequencing of lab and consult; a verified sender to fight scams; a possible B2C revenue stream from caregivers.

### 3.7 Nouf Al-Harbi (P14) — Saudi mother, Dammam (conducted in Arabic, translated)

**I:** How do you book for your children now?
**Nouf:** I call. The line is busy. Or Sehhaty for government clinics, but for private I call.
**I:** If you could book on WhatsApp in Arabic, with buttons?
**Nouf:** Yes, if it's real Arabic, not Google Translate. And not English numbers mixed in strangely.
**I:** It would ask for each child's details.
**Nouf:** They don't have phones. Put them under my number and my husband's ID. I'll add them once.
**I:** Two children at once?
**Nouf:** Back-to-back, same doctor. Don't make me come twice.
**I:** While waiting?
**Nouf:** In the car, with the AC. Message me when there are two before us.
**I:** Being called in the waiting room?
**Nouf:** By number, not my name, please.
**I:** Voice notes?
**Nouf:** I send voice notes to everyone. If I can send one saying "fever since yesterday", the doctor knows before I arrive.
**I:** Would you trust a clinic's WhatsApp with your children's health information?
**Nouf:** If it's the clinic's official account, with the green badge. I wouldn't want medical results in the chat preview on my lock screen.
**I:** Anything that would make you stop?
**Nouf:** If I'm asked to type a lot or to fill in a form in English.

**Takeaways:** a native Arabic RTL tone; a dependant model; sibling booking; a car-wait call-forward; privacy in preview text; voice-note intake; a verified business account.

### 3.8 Mohammed Al-Kuwari (P16) — Head of IT & Information Governance, Doha

**I:** What is your first question to any SaaS vendor?
**Mohammed:** Where does the data live, who can access it, and can I get it back and delete it?
**I:** Nabd would host GCC tenants in-region. Isolated per hospital.
**Mohammed:** Logically isolated or physically? Encryption keys per tenant? Can I bring my own key?
**I:** Per-tenant keys with an option for customer-managed keys.
**Mohammed:** Then we can talk.
**I:** Patients book via WhatsApp. How do you feel?
**Mohammed:** Conflicted. Meta is outside our jurisdiction. I'd accept appointment logistics on WhatsApp, but not diagnoses or results in the message body. Send a secure link with OTP.
**I:** Cross-hospital sharing with patient and doctor consent, time-limited?
**Mohammed:** Good in principle. I need the full audit trail: who requested, who approved, what was viewed, and for how long. And automatic revocation.
**I:** AI decision support?
**Mohammed:** Where does inference run? If patient data leaves the country for a model, no. Also, I need a model register for MOPH questions.
**I:** Integration?
**Mohammed:** FHIR APIs, HL7 v2 for our lab analysers, SAML SSO with our Azure AD. And a SIEM export.
**I:** Deal-breaker?
**Mohammed:** No penetration test report, no ISO 27001 roadmap, no deal.

**Takeaways:** no PHI in WhatsApp message bodies by default; in-region AI inference; customer-managed keys; an audit trail with a viewing log; a model register; enterprise SSO and a SIEM export from the start.

---

## 4. Synthesis

### 4.1 Affinity-mapped insights (top 15)

| # | Insight | Evidence (personas/source) | Design implication |
|---|---|---|---|
| I1 | **Unpredictability, not wait length, is the pain.** People accept a 40 min wait they know about; they hate a 15 min one they don't. | P11, P14, P2; E1–E2 | Publish ETA *ranges* and *position*, plus a "leave now" nudge |
| I2 | **ETA credibility is fragile.** It survives about 2 misses, then gets ignored. Clinics fear blame. | P1, P3, P11 | ETA modes per clinic (exact / range / position-only / delay-only), with an accuracy SLO |
| I3 | **Front-desk and doctor phones are the hidden queue.** "What's my number?" calls take 1–2 hours of staff time a day. | P2, P4, P1 | Self-serve status on WhatsApp and proactive updates |
| I4 | **Auto-accept needs guardrails:** session caps, service-type rules, walk-in buffers. | P1, P3 | Rule-based acceptance, not just on/off |
| I5 | **Receptionists fear replacement.** Self check-in will be resisted unless it frames their job as exception handling. | P4 | Front-desk "exceptions inbox"; override and audit; change-management messaging |
| I6 | **Doctors type only when it beats the pen.** Favourites, repeat, and templates beat AI. | P1, P15, P7; E4 | Favourites-first Rx; AI as a silent checker; voice dictation (Could) |
| I7 | **AI is trusted as a safety net, not a co-pilot.** Alert fatigue kills trust; liability is unclear. | P15, P1 | High-specificity alerts, explainability, tunable tiers, no auto-prescribing |
| I8 | **Pharmacy economics drive e-Rx attitudes.** Owner-doctors want an in-house default; chemists want verification and expiry. | P7, P8A, P8B | Patient-choice e-Rx with clinic-default routing; QR verification; refill rules |
| I9 | **Family is the real user.** Guardians book for children, parents and in-laws; NRI caregivers manage remotely. | P12, P14, P11 | Dependant/caregiver model with scoped consent |
| I10 | **Language is identity, not a translation.** Arabic RTL and Malayalam/Telugu voice must feel native. | P14, P12, P5 | Native copy, RTL-first components, voice notes in and out |
| I11 | **Rejections are born at booking and in the consult room.** | P10, P13, P8B; E13–E14 | Eligibility at booking, pre-auth prompts, coding validation at sign-off |
| I12 | **Multi-facility doctors are the glue and the friction.** One profile, many calendars, many logins. | P15, P6 | Global doctor identity, cross-tenant busy calendar, portable favourites |
| I13 | **WhatsApp is already the shadow EMR, and the biggest leak.** | P16, P1; E16 | Governed WhatsApp: no PHI in message bodies, secure links, official business number |
| I14 | **Consent must be point-of-care fast or it won't happen.** | P15, P12, P16; E10–E12 | One-tap WhatsApp consent, auto-release by policy, auto-expiry, patient-visible log |
| I15 | **Tier-2 needs offline tolerance and zero-training UX.** | P7, P4, P8A | Offline queue and billing, sync on reconnect, defaults that work out of the box |

### 4.2 Jobs-to-be-done

| # | When… (situation) | I want to… (motivation) | So I can… (outcome) | Personas |
|---|---|---|---|---|
| J1 | I feel unwell and want to see my doctor today | book in under a minute on WhatsApp | stop calling and waiting on hold | P11, P14, P13 |
| J2 | I have a booking | know exactly when to leave home | spend less than 15 minutes in the waiting room | P11, P14, P12 |
| J3 | I'm a solo doctor seeing a patient | have bookings and queries handled without me | focus and finish on time | P1, P2 |
| J4 | a walk-in arrives at the busy counter | register them in under 45 seconds | keep the line moving | P4 |
| J5 | the doctor is late or a case overruns | tell every affected patient instantly | avoid abuse and wasted waits | P4, P15, P3 |
| J6 | I prescribe | reuse my common regimens and be warned of real risks | prescribe safely in under 90 seconds | P1, P15, P2 |
| J7 | a prescription arrives at the pharmacy | verify it and bill without retyping | serve the counter queue faster and stay compliant | P8A, P8B, P7 |
| J8 | I care for a parent remotely | see appointments, prescriptions and results with their consent | act early and pay remotely | P12 |
| J9 | I need a patient's records from another hospital | request and receive them with consent at the point of care | avoid repeat tests and delays | P15, P13 |
| J10 | I submit insurance claims | catch errors before submission | get paid first time | P10 |
| J11 | I run a chain | see door-to-doctor time per site every morning | fix bottlenecks and punctuality | P6 |
| J12 | I govern hospital data | prove who accessed what and keep data in-country | pass audits and avoid breaches | P16 |
| J13 | a lab result is critical | reach the treating doctor and confirm receipt | prevent harm | P9 |
| J14 | a slot is cancelled | fill it from a waitlist automatically | keep chairs and doctors utilised | P3 |

### 4.3 Key journey maps

Format: **Step → today (time-waster) → Nabd target → time target.**

#### J-A · Patient booking → arrival → consult → Rx → pharmacy → follow-up (P11, P14)

| # | Step | Today (time-waster) | Nabd target experience | Target |
|---|---|---|---|---|
| 1 | Discover and book | Calls, hold, "doctor available?" | WhatsApp: "Hi" → choose doctor (list) → choose slot (buttons) → confirm. Returning patient is recognised by phone number | < 60 s, ≤ 5 taps |
| 2 | Pre-visit | Nothing; history re-asked at the desk | WhatsApp Flow: reason for visit, allergies, current meds, insurance card photo (optional), voice note | < 2 min, optional |
| 3 | Leave home | Arrives early "to be safe" | Live position and ETA range; "leave now" nudge based on travel time | Waiting-room time < 15 min |
| 4 | Arrive / check in | Line at the desk | Scan QR at the door (geofenced) or tap "I've arrived"; token confirmed | < 15 s |
| 5 | Wait | Blind; asks the receptionist | Position updates on each change; car-wait mode; "2 ahead" alert | 0 status queries |
| 6 | Consult | Repeats history | Doctor sees the pre-visit summary and prior visits on one screen | Doctor context < 5 s |
| 7 | Rx | Illegible paper | e-Rx (QR, expiry) sent to WhatsApp as a secure link; default pharmacy pre-selected | Rx < 90 s |
| 8 | Pharmacy | Retyped; queue | Pharmacy scans QR → items pre-filled → bill | Dispense lookup < 20 s |
| 9 | Pay | Cash counting, UPI screenshot | Payment link, or POS integrated into the bill | < 30 s |
| 10 | Follow-up | Forgotten | Auto reminder with a one-tap rebook on day N; lab order pre-attached | Follow-up adherence +30% (H) |

#### J-B · Walk-in (P4, P1)

| # | Step | Today | Target | Target time |
|---|---|---|---|---|
| 1 | Arrival | Joins the counter line | Door QR: "Walk-in → choose doctor"; joins the walk-in lane with an ETA, or the receptionist registers | — |
| 2 | Identify | Name search, duplicates | Phone-number lookup → family members listed → pick | < 10 s returning |
| 3 | New patient | Long form | Minimum dataset (name, age/DOB, sex, phone); rest captured later or by the patient over WhatsApp | < 45 s |
| 4 | Queue placement | Paper token | Rule-based interleave (for example, 2 booked : 1 walk-in), configurable | Instant |
| 5 | Inform | Verbal guess | WhatsApp token and ETA range sent automatically | < 10 s |

#### J-C · Emergency / priority (P5, P2)

| # | Step | Today | Target | Target time |
|---|---|---|---|---|
| 1 | Red-flag arrival | Sits in the general queue | Triage tags priority (ESI-like 1–5) in one tap; queue reorders; doctor alerted | Escalation < 5 min |
| 2 | Others waiting | Unexplained jump causes anger | Others' ETA updates with a neutral "urgent case" reason (no PHI) | Update < 60 s |
| 3 | Audit | None | Priority change logged with user, reason, time | 100% logged |
| 4 | True emergency (solo clinic) | Doctor improvises | "Pause queue" plus broadcast; option to suggest the nearest ER | 2 taps |

#### J-D · No-show (P3, P1)

| # | Step | Today | Target | Target time |
|---|---|---|---|---|
| 1 | T-24 h | Coordinator phones | WhatsApp template: Confirm / Reschedule / Cancel buttons | 0 staff minutes |
| 2 | T-2 h, unconfirmed | Nothing | Second nudge; slot marked "at risk" | — |
| 3 | Cancel | Slot wasted | Waitlist auto-offer: first to accept takes it | Backfill < 10 min |
| 4 | No-show at T+15 | Waits in hope | Auto-mark no-show (grace configurable); next patient pulled forward; ETAs recomputed | Automatic |
| 5 | Repeat offender | — | No-show count visible; optional deposit / prepay rule | Configurable |

#### J-E · Doctor running late (P4, P15)

| # | Step | Today | Target | Target time |
|---|---|---|---|---|
| 1 | Doctor knows they're late | Calls the desk (maybe) | Doctor taps "Late by 15/30/45/60" in app or WhatsApp; or the system infers it from no login or no first call by start + X | 2 taps |
| 2 | Patients informed | Receptionist calls 18 people | Broadcast to all affected booked and queued patients with a new ETA and options (wait / reschedule / see another doctor) | All notified < 60 s |
| 3 | Desk | Absorbs anger | Desk sees who chose what; reschedules done | 0 calls |
| 4 | Analytics | Not measured | Punctuality recorded per doctor and session for the COO | Daily |

#### J-F · Multi-doctor queue (P4, P6)

| # | Step | Today | Target | Target time |
|---|---|---|---|---|
| 1 | Desk sees load | Paper per room | Board: each doctor's queue, current token, average consult, ETA, delay status | Glanceable, live |
| 2 | Rebalance | Shouting across the hall | Keyboard-move a patient to another doctor of the same specialty; patient gets a consent message | < 10 s |
| 3 | Shared services | Vitals/lab queues invisible | Multi-station journey (vitals → doctor → lab → pharmacy) with a per-station ETA | Per-station wait visible |
| 4 | Lobby display | Tokens only, English | Bilingual tokens (English + Arabic/regional), no names | 0 desk queries |

#### J-G · Referral across specialties, same hospital (P4, P5)

| # | Step | Today | Target | Target time |
|---|---|---|---|---|
| 1 | Doctor refers | Paper slip, patient walks to desk | "Refer → Dietitian / Ortho" in the consult screen with a reason | < 10 s |
| 2 | Access | Records re-keyed | Specialty access rules apply (hospital setting); referral note and relevant record shared | Instant |
| 3 | Queue | Re-registers | Patient added to the target doctor's queue (today or a booked slot) with a WhatsApp update | 0 desk time |
| 4 | Close loop | None | Referring doctor notified of the outcome | Automatic |

#### J-H · Cross-hospital record request with consent (P15, P13, P16)

| # | Step | Today | Target | Target time |
|---|---|---|---|---|
| 1 | Doctor needs records from Hospital A while at Hospital B | "Bring the CD" | Search the patient's global profile: lists facilities with records (metadata only) | < 10 s |
| 2 | Request | Form, fax, days | Request scoped data (for example, "Echo + discharge summary, last 12 months") for a duration (24 h / 7 d / 30 d) with a purpose | 3 taps |
| 3 | Patient consent | — | WhatsApp: "Dr. Imran at Hospital B requests… [Approve] [Decline]" with a secure link; assisted consent mode for low-literacy users | < 2 min |
| 4 | Source facility approval | Medical records officer, days | Policy: auto-release if patient consent plus treating relationship; otherwise queue for a designated approver with an SLA | Auto, or < 4 h |
| 5 | Access | — | Read-only view (and optional import into B's record by the doctor, tagged with the source) | Instant |
| 6 | Expiry and audit | — | Auto-revoke at expiry; patient sees "who viewed what"; both tenants keep an immutable log | 100% |

---

## 5. Feature requirements — MoSCoW backlog

**Legend:** **M** = Must for market launch, **S** = Should, **C** = Could, **W** = Won't now. Personas are listed by P-number. Market: **All**, IN, AE, SA, QA. "Time saved" is the hypothesised mechanism; discovery assigns each feature a metric.

### 5.1 Patient — WhatsApp & aggregator

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| PW-01 | WhatsApp booking bot with buttons/lists (doctor → date → slot → confirm); returning patient recognised by phone number | M | P11, P14, P13, P1 | No calls or hold; < 60 s | All |
| PW-02 | Clinic-owned WhatsApp Business number, or a shared Nabd number with clinic branding | M | P1, P16 | Ends personal-number chaos | All |
| PW-03 | Live position and ETA range on demand, plus a push on material change | M | P11, P2, P14 | Kills "what's my number" calls | All |
| PW-04 | "Leave now" nudge (ETA minus the patient's travel buffer) | M | P11, P14 | Wait moves from lobby to home or car | All |
| PW-05 | Confirm / Reschedule / Cancel templates at T-24 h and T-2 h | M | P3, P1 | Fewer no-shows and confirmation calls | All |
| PW-06 | Dependants and family under one phone number; guardian books | M | P14, P11, P12 | One registration, many patients | All |
| PW-07 | Pre-visit intake via WhatsApp Flows (reason, allergies, meds, insurance card photo) | S | P5, P13, P14 | No repeated history | All |
| PW-08 | Voice-note intake attached to the visit (transcription later) | S | P14, P12 | Low-literacy, fast input | All |
| PW-09 | e-Rx, invoice and report as an OTP-protected secure link (no PHI in message body) | M | P11, P16 | Paperless, governed | All |
| PW-10 | Payment links (UPI in IN; card/Apple Pay in GCC), payable by the caregiver | S | P1, P12 | Faster checkout | All |
| PW-11 | Web aggregator: profile, languages, insurance accepted, next slot | S | P11, P13 | Find and book in one flow | All |
| PW-12 | Clinic-controlled aggregator visibility; no competitor promotion on a clinic's channel | M | P1, P7 | Removes an adoption blocker | All |
| PW-13 | Native mobile app | W | — | WhatsApp first; revisit after PMF | — |

### 5.2 Booking & queue engine

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| BQ-01 | Hybrid scheduling: slots, tokens, or both per doctor/session | M | P1, P2, P3 | Fits every clinic model | All |
| BQ-02 | Auto-accept rules (session caps, service types, walk-in buffer); otherwise routed to a designated approver | M | P1, P3 | No manual accepting, no overload | All |
| BQ-03 | ETA engine: rolling per-doctor consult-duration model by visit type with a confidence range | M | P11, P2, P6 | Predictable waits (I1) | All |
| BQ-04 | ETA display modes: exact / range / position-only / delay-only / hidden | M | P3, P1 | Addresses fear of blame (I2) | All |
| BQ-05 | Walk-in/booked interleave rules (ratio, reserved slots) | M | P4, P1 | Fair, fewer disputes | All |
| BQ-06 | Priority/triage override with reason and audit; pause/resume queue | M | P5, P2, P1 | Fast escalation | All |
| BQ-07 | "Late by X" (manual or auto-detected) with mass notification and options | M | P4, P15, P3 | No manual calls (J-E) | All |
| BQ-08 | No-show auto-mark with grace period, pull-forward, ETA recompute | M | P3, P1 | Flow keeps moving | All |
| BQ-09 | Waitlist and auto-backfill of cancelled slots (first to accept wins) | S | P3, P11 | Recovers idle chair time | All |
| BQ-10 | Multi-station journey (vitals → doctor → lab → pharmacy), including lab-before-consult sequencing | S | P5, P9, P12 | Exposes hidden queues; shorter fasting | All |
| BQ-11 | Sibling/family back-to-back booking | S | P14, P2 | One trip | All |
| BQ-12 | Slot types (sick/well, gender-specific) and a female-doctor filter | S | P2, P14 | Preference, infection control | All (SA/QA) |
| BQ-13 | Lobby token display (bilingual, no names) | S | P4, P5 | Fewer desk queries | All |
| BQ-14 | Recurring series (physio, dialysis, ANC) | C | P6 | Fewer bookings to manage | All |

### 5.3 Front desk

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| FD-01 | Keyboard-first registration (shortcuts, tab order, no mouse) | M | P4 | < 45 s walk-in | All |
| FD-02 | Phone lookup with family list and fuzzy match; duplicate-merge suggestions | M | P4, P6 | No duplicate searching | All |
| FD-03 | Minimum-dataset registration; patient completes the rest on WhatsApp | M | P4, P1 | Shorter counter time | All |
| FD-04 | ID capture: Emirates ID / Iqama / QID reader; ABHA in IN (Aadhaar never mandatory) | S | P4, P13, P5 | No ID typing | Market-specific |
| FD-05 | Self check-in (door QR / "I'm here") with geofence and desk override; printed QR token fallback | M | P11, P4 | Removes the counter line | All |
| FD-06 | Exceptions inbox (failed check-ins, unpaid, unconfirmed) | M | P4 | Reframes the role (I5) | All |
| FD-07 | Multi-doctor queue board with move/reassign | M | P4, P6 | Rebalancing without shouting | All |
| FD-08 | Quick desk billing (fees, packages, discounts with reason) | M | P4, P1 | One screen | All |
| FD-09 | Call-centre console for central booking across sites | S | P6 | No transfers | All |

### 5.4 Clinical / EMR

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| CL-01 | One-screen consult view: intake, vitals, allergies, last 3 visits, active meds, attachments | M | P15, P1 | Context < 5 s | All |
| CL-02 | One-hand mobile doctor mode (call next → view → Rx → done) | M | P1, P2 | Solo doctor without a laptop | All |
| CL-03 | Vitals entry by any role; device integration later | M | P5, P2 | Single entry | All |
| CL-04 | Quick notes with specialty templates and smart phrases | M | P1, P15 | Fewer keystrokes (E4) | All |
| CL-05 | ICD-10 / ICD-10-AM search with favourites | M | P10, P15 | Coding at source | All |
| CL-06 | Internal referral that becomes a queue entry | M | P4, P15 | No paper slips (J-G) | All |
| CL-07 | Paediatric growth charts and vaccination schedule with reminders | S | P2, P14 | Automated reminders | All |
| CL-08 | Dental chart module, or a "booking-layer-only" mode beside the clinic's existing dental system | C | P3 | Wins dental | All |
| CL-09 | Voice dictation to notes | C | P15, P1 | Faster notes | All |
| CL-10 | Inpatient EMR (IPD, nursing charting, OT) | W | P6 | OPD-first scope | — |

### 5.5 AI clinical decision support

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| AI-01 | Rx safety checks: allergy, drug–drug interaction, duplicate therapy, with severity tiers | M | P15, P1, P2 | Replaces manual lookup | All |
| AI-02 | Weight-based paediatric dose calculator | M | P2 | ~1 min per child | All |
| AI-03 | Explainability (why plus source); 1-tap override with reason; tunable thresholds and alert-burden analytics | M | P15, P1 | Trust without fatigue (I7) | All |
| AI-04 | Condition- and lab-aware alerts (renal/hepatic dosing, pregnancy) | S | P15 | Catches risk without searching | All |
| AI-05 | In-region inference, model register, versioning | M | P16 | Compliance prerequisite | All |
| AI-06 | Opt-in, labelled regimen *suggestions* (never auto-filled) | C | P1 | Possible speed-up; trust risk | All |
| AI-07 | Autonomous diagnosis/triage by AI | W | — | Regulatory and liability risk | — |

### 5.6 e-Prescription

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| RX-01 | Favourites, order sets and "repeat last Rx", portable across hospitals | M | P1, P15, P7 | < 90 s new, < 30 s repeat | All |
| RX-02 | Drug master with brand ↔ generic mapping and local formularies | M | P8A, P8B, P7 | Fast search, substitution | Per market |
| RX-03 | Bilingual Rx PDF/print with QR, registration number and signature | M | P8A, P14 | Legible, verifiable | All |
| RX-04 | Expiry and refill rules per drug class; controlled-drug workflow per market | M | P8A, P7, P8B | No reuse disputes; compliance | All |
| RX-05 | Patient-choice pharmacy routing with a clinic-default pharmacy | M | P7, P11 | In-house sale plus patient choice | All |
| RX-06 | Stock-aware prescribing for in-house pharmacy | S | P7 | Fewer lost patients | All |
| RX-07 | National e-Rx integration (KSA Wasfaty; UAE/Qatar platforms as mandated) | S | P8B, P10 | No double entry | SA, AE, QA |

### 5.7 Pharmacy (sellable standalone)

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| PH-01 | Scan e-Rx QR → verify → pre-filled bill → mark dispensed | M | P8A, P8B, P7 | No retyping | All |
| PH-02 | POS with FEFO batch selection and GST (IN) / VAT (GCC) invoices | M | P8A, P7 | Faster counter | All |
| PH-03 | Offline billing with safe sync | M | P7, P8A | Bills during outages | All (IN critical) |
| PH-04 | Auto Schedule H/H1 register (IN); controlled-drug logs (GCC) | M | P8A | Saves hours a week | IN, GCC |
| PH-05 | Standalone pharmacy tenant; photo capture of paper Rx | M | P8A, P8B | Market wedge | All |
| PH-06 | Insurance eligibility/approval at dispense | S | P8B, P13 | Cuts payer wait | GCC |
| PH-07 | Refill reminders with "ready for pickup" | S | P8B, P12 | Repeat revenue | All |
| PH-08 | Home delivery dispatch | C | P12 | Elderly access | All |

### 5.8 Lab

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| LB-01 | Digital lab orders from the consult screen to in-house or partner lab | M | P9, P15 | No handwritten slips | All |
| LB-02 | Barcode labelling and sample tracking | S | P9 | No mix-ups | All |
| LB-03 | Secure report link and "report ready" push | M | P9, P11, P12 | Ends "ready?" calls | All |
| LB-04 | Critical-value escalation with read receipt and a fallback chain | M | P9, P15 | Acknowledged < 15 min | All |
| LB-05 | Result validation plus analyser interface (HL7/ASTM) | S | P9 | No transcription | All |
| LB-06 | Home-collection booking and phlebotomist routing | C | P9, P12 | Elderly access | IN |

### 5.9 Billing & insurance

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| BI-01 | Patient billing (consults, packages, deposits, refunds; UPI/card/cash) with fee schedules and payer price lists | M | P1, P4, P10 | Single checkout | All |
| BI-02 | Real-time eligibility at booking and at the desk | M | P13, P10 | Saves ~4 min/patient | GCC |
| BI-03 | DHA eClaimLink and DoH Shafafiya e-claims and pre-auth | M | P10, P3 | Direct submission | AE |
| BI-04 | NPHIES connector (eligibility, pre-auth, claim, payment) | M | P10 | Mandated channel (E13) | SA |
| BI-05 | Pre-auth prompts at order time, plus a claim scrubber (coding/payer rules) | S | P10, P3 | First-pass acceptance > 90% | GCC |
| BI-06 | Remittance auto-reconciliation and rejection work queue | S | P10 | Saves 90 min/day | GCC |
| BI-07 | Co-pay estimate for the patient at booking | S | P13 | No surprise bills | GCC |
| BI-08 | ZATCA (KSA) and UAE e-invoicing readiness | S | P10 | Compliance without rework | SA, AE |
| BI-09 | India TPA cashless and PM-JAY/ABDM claims; Qatar insurer integration as rules mature | C | P6, P8B | Desk time | IN, QA |

### 5.10 Inventory

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| IV-01 | Batch/expiry stock with near-expiry alerts | M | P7, P8A | Cuts write-offs and monthly checks | All |
| IV-02 | Purchase orders, GRN, suppliers, reorder-level suggestions | S | P7, P8A | Fewer stock-outs | All |
| IV-03 | Consumables and multi-store transfers for chains | C | P3, P6 | Visibility | All |

### 5.11 Consent & interoperability

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| CI-01 | Consent ledger (purpose, scope, duration, revocable, tagged approval trail); patient "who accessed" view | M | P16, P15, P12, P11 | Instant audit (E8, E10–E12) | All |
| CI-02 | Cross-hospital request → patient WhatsApp approval → policy auto-release or approver → time-boxed view → auto-revoke | M | P15, P13 | Days become minutes (J-H) | All |
| CI-03 | Assisted consent (staff-witnessed, local-language voice explanation) | M | P12, P14 | Low-literacy inclusion | All |
| CI-04 | Caregiver/guardian roles with scoped access | M | P12, P14 | Remote family care | All |
| CI-05 | Intra-hospital cross-specialty policy (open / referral-only / break-glass) | M | P6, P16 | Configurable privacy | All |
| CI-06 | Global patient identity separated from tenant clinical records | M | P13, P15 | No re-registration | All |
| CI-07 | FHIR R4 APIs; HL7 v2 for labs and devices | S | P16, P9 | Faster integration | All |
| CI-08 | ABDM (ABHA linking, HIP/HIU, consent manager) | S | P6, P11 | National interop | IN |
| CI-09 | GCC HIE connectors: NABIDH, Malaffi, Riayati; KSA/Qatar exchanges as available | S | P16, P3 | Licensing and interop (E9) | AE, SA, QA |

### 5.12 Admin, settings & workflows

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| AD-01 | Workflow presets (Solo / Clinic / Polyclinic / Hospital) with toggles: auto/manual accept, self/desk check-in, nurse triage | M | P1, P4, P6 | Onboarding < 30 min | All |
| AD-02 | Roles and permissions per staff type | M | P6, P16 | Correct access | All |
| AD-03 | Schedules, leave, Ramadan timings | M | P3, P15 | Fewer clashes | All |
| AD-04 | Bilingual message template manager | S | P3, P4 | Self-serve comms | All |
| AD-05 | Guided onboarding with CSV import of patients and drugs | S | P1, P7 | Go-live in a day | All |

### 5.13 Multi-facility / chain

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| MF-01 | Global doctor profile, one login with tenant switcher, unified calendar with privacy-preserving "busy" blocks | M | P15 | No clashes, no 3 logins | All |
| MF-02 | Delay propagation across a doctor's facilities | S | P15 | Automatic notices | All |
| MF-03 | Chain MPI within the chain's tenancy; central config with site overrides | S | P6 | Zero re-registration | All |

### 5.14 Reporting

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| RP-01 | Ops dashboard: door-to-doctor, waits, no-shows, ETA accuracy, punctuality; chain benchmarking | M | P6, P1, P3 | One number each morning | All |
| RP-02 | Revenue, payer mix, rejection rates | S | P10, P6 | Finance visibility | All |
| RP-03 | Daily WhatsApp summary to owner-doctor | S | P1, P7 | No laptop needed | All |

### 5.15 Platform & security

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| PS-01 | Tenant isolation; per-tenant keys; customer-managed keys (enterprise) | M | P16, P6 | Faster security review | All |
| PS-02 | In-region residency (IN, UAE, KSA, Qatar) | M | P16 | Legal prerequisite (E8, E10, E11) | All |
| PS-03 | Immutable audit log, including reads | M | P16 | Audit in minutes | All |
| PS-04 | SSO (SAML/OIDC), MFA, session policy | M | P16, P6 | Enterprise ready | All |
| PS-05 | Offline-tolerant clients (queue, check-in, billing) | M | P7, P4, P1 | No downtime | All |
| PS-06 | PHI-minimised notifications; lock-screen-safe previews | M | P16, P14 | Privacy by default | All |
| PS-07 | Performance SLOs (p95 < 300 ms queue actions; 99.9% uptime) | M | P4, P1 | Speed is a feature | All |
| PS-08 | Break-glass access with justification and alerts | S | P5, P16 | Emergencies without standing access | All |
| PS-09 | SIEM export, pen-test, ISO 27001 roadmap, data export/deletion | S | P16 | Procurement speed | All |

### 5.16 Localization

| ID | Feature | Pri | Personas | Time saved | Market |
|---|---|---|---|---|---|
| LC-01 | Arabic RTL UI (staff and patient), mirrored layouts, digit choice | M | P14, P5, P16 | Native usability | GCC |
| LC-02 | Patient WhatsApp in English, Arabic, Hindi | M | P14, P8A | Reach | All |
| LC-03 | Locale: Hijri/Gregorian dates, local weekends, prayer-aware slots, currency/tax/number formats (lakh/crore) | M | P14, P3 | Correct schedules and bills | All |
| LC-04 | Regional Indian languages for patients (ML, TA, TE, KN, MR, BN) | S | P12, P2 | Inclusion | IN |
| LC-05 | Voice reminders in local language | S | P12, P14 | Low literacy | All |

**Backlog size:** 120 features: 72 Must, 37 Should, 8 Could, 3 Won't-now. The Must list is deliberately broad because it covers the full OPD loop: book → queue → consult → Rx → pharmacy/lab → bill → consent. Four Musts apply only in some markets (BI-02, BI-03, BI-04, LC-01). If launch scope must shrink, demote these first: CI-05 (ship a single default policy), CL-05, the Ramadan-timing part of AD-03, and the GCC controlled-drug logs in PH-04.

**Must-for-launch headline (the "Nabd Core"):** WhatsApp booking with family, reminders and secure links → rule-based auto-accept → ETA engine with display modes and "leave now" → walk-in interleave, priority override, delay broadcast, no-show handling → keyboard-first desk, self check-in and exceptions inbox → one-screen consult and one-hand mobile doctor mode → favourites/repeat e-Rx with QR, expiry and patient-choice routing → in-region AI safety checks with explainability → standalone pharmacy with offline billing, FEFO and H1 → digital lab orders, report links and critical values → billing, GCC eligibility, DHA and NPHIES claims → consent ledger, cross-hospital request, caregiver roles, global identity → tenant isolation, residency, audit, SSO, PHI-minimised messaging → Arabic RTL, Hijri dates and bilingual documents.

---

## 6. UX principles for Nabd

1. **Time is the metric.** Every screen has a target task time. Releases are blocked if they regress a "moment that matters" by more than 10%.
2. **≤ 3 taps or keystrokes for the top 10 tasks:** call next, check in, register a returning patient, repeat Rx, mark dispensed, send delay, confirm booking, view status, approve consent, take payment.
3. **Keyboard-first for desk roles.** Every action has a shortcut, there are no mouse-only controls, focus lands on the search field, and Enter means "next". Training target: productive in under 15 minutes.
4. **One-hand mobile for solo doctors.** Thumb-zone primary actions, a large "Call next", swipe to complete, and everything works on a ₹12k Android phone at 3G speed.
5. **Offline-tolerant.** Queue, check-in, Rx drafting and pharmacy billing work offline and sync safely. The connectivity state is always visible.
6. **Buttons and lists, not free text, on WhatsApp.** Use reply buttons, list messages and Flows. Free text and voice notes are accepted, but the flow never depends on them. Never write "type 1 for…".
7. **Bilingual and RTL-native.** Design Arabic first for GCC patient surfaces (mirrored icons, Arabic typography, no machine translation in production copy). The record is the same whatever the display language.
8. **Low-literacy inclusion.** Voice-note reminders, icons with labels, assisted consent, readable token numbers, and no OTP-only flows for elderly users.
9. **Honest time.** Show ranges and positions. Explain delays without PHI. Never show a precise ETA the model cannot back up. Measure and publish ETA accuracy internally.
10. **Defaults over settings.** Workflow presets work out of the box; advanced settings come later.
11. **Privacy by default.** No PHI in notification bodies. Lock-screen-safe previews. Visible access logs for patients.
12. **Safety without fatigue.** Interruptive alerts are only for high-severity issues; the rest are passive. Every override takes one tap plus a reason chip.
13. **Staff augmentation, not replacement.** Automation removes repetitive work and routes exceptions to people. Copy and training frame staff as the people who handle what the system cannot.

### 6.1 Top 10 moments that matter — acceptance criteria & time targets

| # | Moment | Persona | Acceptance criteria | Time target |
|---|---|---|---|---|
| MM1 | **Book via WhatsApp** | P11, P14 | Returning patient recognised by phone number; ≤ 5 interactions; no free text needed; confirmation with token/slot, ETA mode and add-to-calendar; available in AR/EN/HI | **< 60 s** (median), ≤ 90 s p90 |
| MM2 | **"Leave now" + live status** | P11, P14 | Push when ETA changes by ≥ 10 min or position ≤ N; ETA actual within ±10 min for ≥ 80% of visits (range mode); status reply < 3 s | Waiting-room time **< 15 min** median |
| MM3 | **Self check-in** | P11, P4 | QR scan or "I'm here" button; geofence ≤ 200 m (configurable); front desk sees it instantly and can override | **< 15 s** |
| MM4 | **Receptionist walk-in registration** | P4 | Keyboard-only; minimum dataset; duplicate check inline; token printed or sent | **< 45 s** new, **< 15 s** returning |
| MM5 | **Doctor running late broadcast** | P4, P15, P3 | 2 taps (doctor or desk); all affected patients get new ETA plus options (wait / reschedule / other doctor); desk sees responses | All notified **< 60 s** |
| MM6 | **Doctor call-next and context** | P1, P15 | One tap; summary card shows reason, vitals, allergies, active meds, last visit; mobile one-hand | Context visible **< 5 s** |
| MM7 | **Complete a prescription** | P1, P15, P2 | Favourites/order sets; repeat last; safety checks inline < 1 s; interruptive alert only for severe; e-Rx sent with QR and expiry | **< 90 s** new with favourites, **< 30 s** repeat |
| MM8 | **Pharmacy dispense from e-Rx** | P8A, P8B, P7 | QR scan → verified → items pre-filled with FEFO batch → bill; expiry/refill enforcement; works offline | **< 20 s** to bill-ready |
| MM9 | **Insurance eligibility & claim** | P13, P10 | Eligibility at booking or desk with co-pay shown; claim scrubbed and auto-submitted (eClaimLink/NPHIES) same day | Eligibility **< 30 s**; ≥ 95% claims submitted same day; first-pass acceptance ≥ 90% |
| MM10 | **Consented cross-hospital record access** | P15, P13, P12, P16 | Doctor request in ≤ 3 taps; patient approves on WhatsApp (or assisted consent); policy auto-release; time-boxed read-only view; auto-revoke; full audit visible to the patient | Request → access **< 2 min** (auto-release path) |

**Next steps:** (1) Build a Figma and WhatsApp-sandbox prototype of MM1–MM7. (2) Run the Phase-1 interview plan (§1.3) and replace simulated quotes with real ones. (3) Run time-motion baselines at 10 pilot clinics per market to set real targets. (4) Re-rank the Must list using RICE scores informed by the measured time saved.
