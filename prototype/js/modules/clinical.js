/* Clinical module: doctor (my day, consultation with AI safety net and e-Rx, patients, results inbox, ward rounds,
   Rx favourites, schedule, record requests), nurse (triage & vitals, nursing orders, procedures & vaccines,
   clinical inbox) and solo doctor (today, phone-first consult, patients, payments, settings).
   Overlays: clvitals, rxpreview, rxoverride, referral, consentdecide, icd, cldelay, roundnote, clmsg, clvaccine, clleave.
   Module data lives under NB.D.clinical. CSS classes are prefixed cl-. */
(function () {
  "use strict";
  var ui = NB.ui, L = NB.L, esc = ui.esc;

  /* ================= Data ================= */
  var CD = NB.D.clinical = {};

  // Extra drugs used by the consult scenarios (the shared master stays in NB.D.drugs).
  CD.drugs = [
    { id: "x1", name: "Tretinoin 0.025% cream", gen: "Tretinoin (topical)", cls: "Retinoid (topical)", sched: "H", preg: "C", price: 145, unit: "tube" },
    { id: "x2", name: "Azelaic acid 20% cream", gen: "Azelaic acid", cls: "Dicarboxylic acid", sched: "OTC", preg: "B", price: 310, unit: "tube" },
    { id: "x3", name: "Sunscreen SPF 50 gel (broad-spectrum)", gen: "Sunscreen", cls: "Photoprotection", sched: "OTC", preg: "A", price: 520, unit: "tube" },
    { id: "x4", name: "Telmisartan 40 mg tablet", gen: "Telmisartan", cls: "ARB", sched: "H", preg: "D", price: 4.1, unit: "tab" },
    { id: "x5", name: "Pantoprazole 40 mg tablet", gen: "Pantoprazole", cls: "PPI", sched: "H", preg: "B", price: 3.4, unit: "tab" },
    { id: "x6", name: "Doxycycline 100 mg capsule", gen: "Doxycycline", cls: "Tetracycline", sched: "H", preg: "D", price: 5.2, unit: "cap" },
    { id: "x7", name: "Cefuroxime 500 mg tablet", gen: "Cefuroxime", cls: "Cephalosporin", sched: "H", preg: "B", price: 28, unit: "tab" },
    { id: "x8", name: "Montelukast 10 mg tablet", gen: "Montelukast", cls: "LTRA", sched: "H", preg: "B", price: 9.5, unit: "tab" },
    { id: "x9", name: "Ondansetron 4 mg tablet", gen: "Ondansetron", cls: "Antiemetic", sched: "H", preg: "B", price: 4.2, unit: "tab" },
    { id: "x10", name: "ORS sachet (WHO low-osmolarity)", gen: "Oral rehydration salts", cls: "Electrolyte", sched: "OTC", preg: "A", price: 21, unit: "sachet" },
    { id: "x11", name: "Minoxidil 5% topical solution", gen: "Minoxidil (topical)", cls: "Vasodilator (topical)", sched: "OTC", preg: "C", price: 640, unit: "bottle" }
  ];
  function drug(id) { return NB.D.drugs.find(function (d) { return d.id === id; }) || CD.drugs.find(function (d) { return d.id === id; }) || { name: id, gen: id, cls: "", preg: "" }; }
  function allDrugs() { return NB.D.drugs.concat(CD.drugs); }
  var STOCK = { m1: 520, m2: 84, m3: 860, m4: 18, m5: 240, m6: 30, m7: 300, m8: 210, m9: 400, m10: 60, m11: 9, m12: 240, x1: 12, x2: 6, x3: 22, x4: 180, x5: 300, x6: 90, x7: 40, x8: 60, x9: 120, x10: 200, x11: 0 };

  // ICD-10 (WHO; GCC payers use ICD-10-AM, same codes at this level).
  CD.icd = [["J18.9", "Pneumonia, unspecified organism"], ["J20.9", "Acute bronchitis, unspecified"], ["J06.9", "Acute upper respiratory infection, unspecified"],
    ["R50.9", "Fever, unspecified"], ["A90", "Dengue fever (classical)"], ["A09", "Infectious gastroenteritis and colitis"], ["E11.2", "Type 2 diabetes mellitus with renal complications"],
    ["E11.9", "Type 2 diabetes mellitus without complications"], ["I10", "Essential (primary) hypertension"], ["N18.3", "Chronic kidney disease, stage 3"], ["E87.5", "Hyperkalaemia"],
    ["E78.5", "Hyperlipidaemia, unspecified"], ["E03.9", "Hypothyroidism, unspecified"], ["K29.7", "Gastritis, unspecified"], ["L70.0", "Acne vulgaris"], ["L81.1", "Chloasma (melasma)"],
    ["L65.9", "Nonscarring hair loss, unspecified"], ["G43.9", "Migraine, unspecified"], ["J45.9", "Asthma, unspecified"], ["H66.9", "Otitis media, unspecified"], ["Z33", "Pregnant state, incidental"],
    ["M17.9", "Gonarthrosis (knee osteoarthritis), unspecified"], ["R07.4", "Chest pain, unspecified"]];
  CD.icdFav = ["J06.9", "R50.9", "E11.9", "I10", "J18.9", "A09"];
  CD.imaging = [["CXR", "Chest X-ray PA view", "Radiology"], ["ECG", "ECG 12-lead", "Cardiology"], ["USGA", "USG abdomen", "Radiology"], ["ECHO", "2D echo", "Cardiology"]];

  // Per-patient clinical scenarios used by the consult screen.
  CD.sc = {
    p3: { tok: "T-15", visit: "Walk-in · triaged ESI 3", reason: "Fever 3 days, productive cough", egfr: 41, egfrAt: "12 Oct (CKD-EPI 2021)", ckd: "CKD G3b",
      v: { bp: ["148/92", "warn"], hr: [102, "warn"], t: [38.6, "warn"], spo2: [95, "warn"], rr: [22, "warn"], wt: [78, ""], rbs: [228, "warn"], pain: [3, ""] }, vBy: "Anita D'Souza · 09:38 · Omron HEM-7156 (Bluetooth)",
      pre: [["Complaint", "Fever since 14 Oct (max 39.2 °C at home), cough with yellow sputum, body ache"], ["Red flags asked on WhatsApp", "Breathless on stairs: yes · Chest pain: no · Confusion: no"], ["Taken at home", "Paracetamol 650 mg × 4 doses"], ["Home sugars", "220–260 mg/dL fasting this week"]],
      problems: [["E11.2", "Type 2 diabetes with renal complications", "since 2014"], ["I10", "Essential hypertension", "since 2016"], ["N18.3", "CKD stage 3b", "since 2025"]],
      meds: [["x4", "Telmisartan 40 mg", "1-0-0", "since 2019"], ["m4", "Metformin 500 mg", "1-0-1 after food", "since 2014"], ["m5", "Amlodipine 5 mg", "0-0-1", "since 2016"], ["m12", "Atorvastatin 10 mg", "0-0-1", "since 2021"]],
      labs: [["Potassium", "6.1", "mmol/L", "3.5–5.1", "crit", "Today 09:58"], ["eGFR", "41", "mL/min/1.73m²", "> 60", "warn", "12 Oct"], ["Creatinine", "1.68", "mg/dL", "0.7–1.3", "warn", "12 Oct"], ["HbA1c", "8.4", "%", "< 7.0", "warn", "12 Oct"], ["CRP", "—", "", "", "pend", "Ordered"]],
      tl: [["acc", "steth", "Today 09:20 · Walk-in", "Fever 3 days · triaged ESI 3 by nurse"], ["crit", "flask", "Today 09:58 · K+ 6.1 mmol/L", "Critical value phoned by lab · not yet acknowledged"],
        ["", "share", "14 Oct · Outside records (Apollo Clinic)", "Diabetic foot referral · consent C-2038 · 3 of 5 views used"], ["", "flask", "12 Oct · KFT + HbA1c", "eGFR 52 → 41 in 6 months · HbA1c 7.6 → 8.4"],
        ["ok", "pill", "02 Jul · Diabetes review (you)", "Metformin continued · telmisartan 40 mg · foot exam normal"], ["crit", "alertfill", "2019 · Anaphylaxis to amoxicillin", "ER, Manipal Hospital · adrenaline × 1 · allergy verified"]],
      soap: { s: "Fever 3 days (max 39.2 °C), cough with yellow sputum, breathless on stairs. No chest pain, no confusion. Home sugars 220–260.", o: "T 38.6 °C, HR 102, RR 22, SpO2 95% on air, BP 148/92. Crackles right lower zone. No pedal oedema.",
        a: "Community-acquired pneumonia, right lower zone; CURB-65 = 0 (outpatient). T2DM uncontrolled. CKD G3b. Hyperkalaemia 6.1 (critical) on telmisartan.", p: "Penicillin-free oral antibiotic, antipyretic. CXR, CBC, CRP; repeat K+ and ECG today. Hold telmisartan until K+ rechecked. Review in 48 h, sooner if worse." },
      dx: ["J18.9", "E11.2", "E87.5", "N18.3"], orders: ["CBC", "CRP", "KFT", "CXR", "ECG"], fav: "CAP adult — OPD",
      rx: [{ d: "m1", q: "2", f: "1-1-1", n: "5 days", food: "After food" }, { d: "m3", q: "1", f: "SOS", n: "5 days", food: "After food" }, { d: "m9", q: "1", f: "1-0-1", n: "3 days", food: "After food" }, { d: "m4", q: "2", f: "1-0-1", n: "30 days", food: "After food" }],
      sug: [["CURB-65 = 0 (no confusion, RR < 30, BP normal, age < 65): outpatient treatment is reasonable.", "BTS CAP guideline 2019"], ["K+ 6.1: repeat venous potassium and a 12-lead ECG today; review telmisartan.", "KDIGO 2024 CKD §3.10"], ["Fever with uncontrolled diabetes: give sick-day rules (pause metformin if vomiting or not eating).", "ADA Standards of Care 2026 §16"], ["Last urine albumin–creatinine ratio was 14 months ago.", "KDIGO 2022 diabetes in CKD"]] },
    p8: { tok: "T-20", visit: "Appointment · booked by caregiver (son, Dubai)", reason: "BP review, knee pain", egfr: 34, egfrAt: "09 Oct (CKD-EPI 2021)", ckd: "CKD G3b",
      v: { bp: ["168/98", "warn"], hr: [78, ""], t: [36.8, ""], spo2: [97, ""], rr: [16, ""], wt: [71, ""], rbs: [132, ""], pain: [5, ""] }, vBy: "Anita D'Souza · 09:52",
      pre: [["Complaint", "Home BP 160–175 systolic for 2 weeks; right knee pain on stairs"], ["Caregiver note", "Son (Dubai) uploaded 14 BP readings in the app"], ["Taken at home", "Amlodipine 5 mg, sometimes forgets night dose"]],
      problems: [["I10", "Essential hypertension", "since 2008"], ["N18.3", "CKD stage 3", "since 2022"], ["M17.9", "Knee osteoarthritis", "since 2024"]],
      meds: [["m5", "Amlodipine 5 mg", "1-0-0", "since 2008"], ["m12", "Atorvastatin 10 mg", "0-0-1", "since 2020"]],
      labs: [["eGFR", "34", "mL/min/1.73m²", "> 60", "warn", "09 Oct"], ["Creatinine", "1.92", "mg/dL", "0.7–1.3", "warn", "09 Oct"], ["Potassium", "4.9", "mmol/L", "3.5–5.1", "", "09 Oct"]],
      tl: [["acc", "steth", "Today 09:44 · Appointment", "Booked by son via caregiver access"], ["", "flask", "09 Oct · KFT", "eGFR 34 (was 39 in April)"], ["ok", "pill", "11 Apr · BP review", "Amlodipine 5 mg continued"]],
      soap: { s: "Home BP 160–175/90–100 for 2 weeks. Right knee pain on stairs, no swelling. Misses night doses sometimes.", o: "BP 168/98 (repeat 164/96), HR 78. Right knee crepitus, no effusion.", a: "Uncontrolled hypertension. CKD G3b, eGFR 34. Knee osteoarthritis.", p: "Increase amlodipine. Avoid NSAIDs. Paracetamol for knee pain, physiotherapy. Recheck BP in 2 weeks." },
      dx: ["I10", "N18.3", "M17.9"], orders: ["KFT"], fav: "HTN refill 30 d",
      rx: [{ d: "m5", q: "2", f: "1-0-0", n: "30 days", food: "Any time" }, { d: "m9", q: "1", f: "SOS", n: "7 days", food: "After food" }, { d: "m12", q: "1", f: "0-0-1", n: "30 days", food: "Any time" }],
      sug: [["Consider ACE inhibitor or ARB for CKD with hypertension once potassium is stable (K+ 4.9).", "KDIGO 2021 BP in CKD"], ["Knee OA: topical diclofenac has less systemic exposure than oral NSAIDs if paracetamol fails.", "NICE NG226 (2022)"]] },
    p2: { tok: "T-14", visit: "Follow-up · booked on web", reason: "Melasma follow-up", preg: "14 weeks (LMP 11 Jul 2026)",
      v: { bp: ["112/70", ""], hr: [84, ""], t: [36.7, ""], spo2: [99, ""], rr: [14, ""], wt: [61, ""], rbs: [96, ""], pain: [0, ""] }, vBy: "Self check-in kiosk · 09:15",
      pre: [["Complaint", "Dark patches on cheeks worse since pregnancy"], ["Pregnancy", "14 weeks · under Dr. Fatima Rahman (Obstetrics)"], ["Asked on WhatsApp", "Wants to restart old cream and acne tablet"]],
      problems: [["L81.1", "Melasma", "since 2024"], ["Z33", "Pregnant, 14 weeks", "2026"], ["L70.0", "Acne vulgaris — resolved", "Jun 2026"]],
      meds: [["", "Folic acid 5 mg", "1-0-0", "since Jul 2026"], ["", "Iron + folic acid", "0-1-0", "since Sep 2026"]],
      labs: [["Haemoglobin", "11.2", "g/dL", "11–14 (pregnancy)", "", "02 Oct"], ["TSH", "1.9", "mIU/L", "0.1–2.5 (T1)", "", "02 Oct"]],
      tl: [["acc", "steth", "Today 09:12 · Follow-up", "Self check-in"], ["warn", "baby", "28 Aug · Pregnancy recorded", "By Dr. Fatima Rahman · shared to your clinic with consent"], ["ok", "pill", "12 Jun · Acne + melasma (you)", "Isotretinoin 20 mg, tretinoin 0.025%, SPF 50"]],
      soap: { s: "Melasma darker on both cheeks since pregnancy. Acne settled. Wants old cream and acne tablet.", o: "Centrofacial melasma, MASI 12. No active acne.", a: "Melasma, worsened in pregnancy (14 weeks).", p: "Pregnancy-safe topical only. Strict photoprotection. Review after delivery for stronger options." },
      dx: ["L81.1", "Z33"], orders: [], fav: "Repeat last Rx (12 Jun 2026)",
      rx: [{ d: "m6", q: "1", f: "0-0-1", n: "30 days", food: "After food" }, { d: "x1", q: "1", f: "0-0-1", n: "30 days", food: "Any time" }, { d: "x3", q: "1", f: "1-0-0", n: "90 days", food: "Any time" }],
      sug: [["Azelaic acid 20% is pregnancy category B and helps melasma.", "ACOG; BAD pregnancy prescribing 2023"], ["Hydroquinone: avoid in pregnancy (≈35–45% systemic absorption).", "Bozzo et al., Can Fam Physician 2011"]] }
  };
  function scen(pid) {
    var p = NB.pat(pid);
    return CD.sc[pid] || { tok: "—", visit: "Appointment", reason: (p.conditions && p.conditions[0]) || "Consultation", v: {}, vBy: "Vitals not recorded yet",
      pre: [["Complaint", "Not captured — patient skipped the WhatsApp questionnaire"]], problems: (p.conditions || []).map(function (c) { return ["", c, ""]; }), meds: [], labs: [], tl: [],
      soap: { s: "", o: "", a: "", p: "" }, dx: [], orders: [], fav: "", rx: [], sug: [] };
  }

  // Doctor's day (Dr. Vikram Shah). fac = index into NB.facilities(). min = minutes after 09:00 for the expected start.
  CD.day = [
    { tok: "T-05", name: "Harish Gowda", as: "44 M", type: "Follow-up", reason: "Viral fever review", fac: 0, st: "completed", t: "09:02", dur: 6 },
    { tok: "T-07", name: "Lata Menon", as: "61 F", type: "Appointment", reason: "Diabetes refill", fac: 0, st: "completed", t: "09:10", dur: 5 },
    { tok: "T-08", name: "Sunil Rao", as: "38 M", type: "Walk-in", reason: "Gastroenteritis", fac: 0, st: "completed", t: "09:16", dur: 8 },
    { tok: "T-10", name: "Mary Thomas", as: "52 F", type: "Appointment", reason: "Thyroid review", fac: 0, st: "completed", t: "09:25", dur: 7 },
    { tok: "T-11", name: "Kiran Hegde", as: "29 M", type: "Walk-in", reason: "Sore throat", fac: 0, st: "completed", t: "09:33", dur: 6 },
    { tok: "T-12", name: "Ramya S.", as: "33 F", type: "Teleconsult", reason: "Lab review", fac: 0, st: "completed", t: "09:40", dur: 9 },
    { tok: "T-18", p: "p6", src: "WhatsApp", type: "Appointment", reason: "Thyroid panel review", fac: 0, st: "checkout", t: "09:50", dur: 8 },
    { tok: "T-15", p: "p3", type: "Walk-in", reason: "Fever 3 days, cough", fac: 0, st: "next", min: 60, flag: "urg" },
    { tok: "T-20", p: "p8", src: "Caregiver app", type: "Appointment", reason: "BP review, knee pain", fac: 0, st: "waiting", min: 70 },
    { tok: "T-24", name: "Rahul Verma", as: "49 M", type: "Follow-up", reason: "BP follow-up", fac: 0, st: "booked", min: 80, src: "Nabd Connect" },
    { tok: "T-26", name: "Geeta Rao", as: "57 F", type: "Appointment", reason: "Joint pains", fac: 0, st: "booked", min: 95, src: "WhatsApp" },
    { tok: "T-27", name: "Asif Khan", as: "36 M", type: "Walk-in", reason: "Cough 1 week", fac: 0, st: "arrived", min: 105, src: "Self check-in" },
    { tok: "W", name: "Ward round", as: "4 inpatients", type: "Rounds", reason: "General Ward A + ICU", fac: 1, st: "rounds", min: 270 },
    { tok: "E-01", name: "Shabana Begum", as: "55 F", type: "Follow-up", reason: "Post-DKA OPD plan", fac: 1, st: "booked", min: 480, src: "Desk" },
    { tok: "E-02", name: "Ravi Teja", as: "42 M", type: "Appointment", reason: "Fatty liver review", fac: 1, st: "booked", min: 490, src: "WhatsApp" },
    { tok: "E-03", name: "Nalini Kamath", as: "66 F", type: "Appointment", reason: "Dizziness", fac: 1, st: "booked", min: 500, src: "Caregiver" },
    { tok: "E-04", name: "Prakash J.", as: "50 M", type: "Teleconsult", reason: "Report review", fac: 1, st: "booked", min: 515, src: "App" }
  ];

  CD.results = [
    { id: "R1", p: "p3", test: "Kidney function test", at: "Today 09:58", lab: "Suvidha in-house lab", flag: "crit", key: "K+ 6.1 mmol/L", sla: "Critical: acknowledge within 30 min · 14 min left",
      rows: [["Potassium", "6.1", "mmol/L", "3.5–5.1", "crit"], ["Sodium", "136", "mmol/L", "135–145", ""], ["Creatinine", "1.71", "mg/dL", "0.7–1.3", "warn"], ["eGFR (CKD-EPI 2021)", "40", "mL/min/1.73m²", "> 60", "warn"], ["Urea", "58", "mg/dL", "15–45", "warn"]],
      tr: [4.6, 4.9, 5.2, 6.1], trl: "Potassium, last 4", note: "Phoned to you by Kiran Rao (lab) at 10:00 · read-back confirmed · sample not haemolysed" },
    { id: "R2", p: "p3", test: "HbA1c", at: "12 Oct", lab: "Suvidha in-house lab", flag: "warn", key: "8.4 %", rows: [["HbA1c", "8.4", "%", "< 7.0 (target)", "warn"], ["Estimated average glucose", "194", "mg/dL", "", ""]], tr: [7.2, 7.6, 8.4], trl: "HbA1c, last 3" },
    { id: "R3", p: "p8", test: "Kidney function test", at: "09 Oct", lab: "Thyrocare (partner)", flag: "warn", key: "eGFR 34", rows: [["Creatinine", "1.92", "mg/dL", "0.7–1.3", "warn"], ["eGFR (CKD-EPI 2021)", "34", "mL/min/1.73m²", "> 60", "warn"], ["Potassium", "4.9", "mmol/L", "3.5–5.1", ""]], tr: [44, 39, 34], trl: "eGFR, last 3" },
    { id: "R4", p: "p6", test: "Thyroid panel", at: "Yesterday 17:20", lab: "Suvidha in-house lab", flag: "warn", key: "TSH 7.8 mIU/L", rows: [["TSH", "7.8", "mIU/L", "0.4–4.0", "warn"], ["Free T4", "0.9", "ng/dL", "0.8–1.8", ""]], tr: [3.1, 4.2, 7.8], trl: "TSH, last 3" },
    { id: "R5", p: "p10", test: "Lipid profile", at: "Yesterday 12:05", lab: "Suvidha Hospital lab", flag: "warn", key: "LDL 162 mg/dL", rows: [["LDL cholesterol", "162", "mg/dL", "< 100", "warn"], ["HDL", "38", "mg/dL", "> 40", "warn"], ["Triglycerides", "190", "mg/dL", "< 150", "warn"]], tr: [148, 155, 162], trl: "LDL, last 3" },
    { id: "R6", p: "p6", test: "Complete blood count", at: "Yesterday 17:20", lab: "Suvidha in-house lab", flag: "ok", key: "Within range", rows: [["Haemoglobin", "12.9", "g/dL", "12–15", ""], ["WBC", "7.2", "×10⁹/L", "4–11", ""], ["Platelets", "260", "×10⁹/L", "150–400", ""]], tr: [12.4, 12.7, 12.9], trl: "Hb, last 3" }
  ];

  CD.inc = [ // incoming record requests needing the treating doctor's approval
    { id: "C-2052", p: "p3", reqIn: "Apollo Hospitals, Jayanagar", reqGcc: "Mediclinic City Hospital, Dubai", by: "Dr. Imran Qureshi", spec: "Cardiology", purpose: "Pre-angiography work-up",
      data: ["Encounters", "Lab results", "ECG", "Prescriptions"], range: "Last 12 months", valid: "7 days", pt: "Approved via WhatsApp OTP · 10:02", ptCh: "WhatsApp OTP", age: "12 min", urgent: false, sla: "Escalates to department head in 3 h 48 min" },
    { id: "C-2050", p: "p8", reqIn: "Manipal Hospital, Old Airport Road", reqGcc: "King Faisal Specialist Hospital, Riyadh", by: "Dr. Leela Krishnan", spec: "Nephrology", purpose: "Referral — CKD stage 3b",
      data: ["Encounters", "Lab results", "Prescriptions", "Discharge summaries"], range: "Last 24 months", valid: "30 days", pt: "Approved by caregiver (son, verified guardian) in app · 08:40", ptCh: "App biometric (caregiver)", age: "1 h 32 min", urgent: false, sla: "Escalates in 2 h 28 min" }
  ];

  CD.triage = [
    { tok: "T-23", name: "Imtiaz Ahmed", as: "61 M", arr: "09:58", reason: "Chest discomfort on exertion, sweating", d: "d4", st: "new", red: "Chest pain + sweating", esi: 2,
      v: { bp: "156/94", hr: "108", t: "36.9", spo2: "96", rr: "20", rbs: "182", pain: "6", wt: "82" } },
    { tok: "T-19", p: "p7", arr: "09:40", reason: "Ear pain, right · fever", d: "d3", st: "new", esi: 4, v: { bp: "108/68", hr: "112", t: "38.9", spo2: "98", rr: "22", rbs: "", pain: "7", wt: "38" } },
    { tok: "T-21", p: "p9", arr: "09:52", reason: "Hair fall", d: "d1", st: "new", esi: 5, v: { bp: "118/76", hr: "76", t: "36.6", spo2: "99", rr: "14", rbs: "", pain: "0", wt: "64" } },
    { tok: "T-16", p: "p4", arr: "09:24", reason: "Immunisation due", d: "d3", st: "inprog", esi: 5, v: { bp: "", hr: "96", t: "36.8", spo2: "99", rr: "20", rbs: "", pain: "0", wt: "19.5" } },
    { tok: "T-20", p: "p8", arr: "09:44", reason: "BP review", d: "d2", st: "done", esi: 4, done: "09:52 · BP 168/98 flagged" },
    { tok: "T-15", p: "p3", arr: "09:20", reason: "Fever 3 days", d: "d2", st: "done", esi: 3, done: "09:38 · T 38.6, RR 22, SpO2 95 flagged" }
  ];

  CD.norders = [
    { id: "N1", due: "09:45", st: "overdue", p: "p3", ord: "ECG 12-lead", det: "K+ 6.1 critical — urgent ECG", by: "Dr. Vikram Shah", kind: "Procedure", pri: "crit" },
    { id: "N2", due: "10:05", st: "due", p: "p3", ord: "Repeat potassium", det: "Venous sample, no fist clenching, send STAT", by: "Dr. Vikram Shah", kind: "Sample", pri: "urg" },
    { id: "N3", due: "10:10", st: "due", p: "p7", ord: "Paracetamol 500 mg oral (15 mg/kg)", det: "Weight 38 kg · T 38.9 °C · no known allergies", by: "Dr. Nisha Bhat", kind: "Medication", pri: "" },
    { id: "N4", due: "10:30", st: "later", p: "p4", ord: "DTaP booster 2 · IM left deltoid", det: "Egg allergy (hives) — not a contraindication · observe 15 min", by: "Dr. Nisha Bhat", kind: "Vaccine", pri: "" },
    { id: "N5", due: "11:00", st: "later", name: "Ravi Kumar", as: "59 M", ord: "Dressing — diabetic foot ulcer, left heel", det: "Saline wash, silver alginate, offloading pad · photo to chart", by: "Dr. Vikram Shah", kind: "Dressing", pri: "" },
    { id: "N6", due: "11:30", st: "later", name: "Shalini Gupta", as: "46 F", ord: "Methylcobalamin 1500 mcg IM", det: "Dose 3 of 5 · B12 deficiency", by: "Dr. Vikram Shah", kind: "Injection", pri: "" },
    { id: "N7", due: "12:00", st: "later", p: "p8", ord: "Repeat BP after 10 min rest, both arms", det: "First reading 168/98", by: "Dr. Vikram Shah", kind: "Observation", pri: "" },
    { id: "N8", due: "09:30", st: "done", name: "Harish Gowda", as: "44 M", ord: "Tetanus toxoid 0.5 mL IM", det: "Given 09:31 · R deltoid · batch TT2508B · exp 06/2027", by: "Dr. Vikram Shah", kind: "Vaccine", pri: "" }
  ];

  CD.procs = [
    { t: "09:30", room: "Procedure room 1", p: "p5", proc: "Laser hair reduction · face", pkg: "Session 4 of 6", st: "inprog", by: "Dr. Meera Iyer" },
    { t: "10:30", room: "Vaccine bay", p: "p4", proc: "DTaP booster 2 + MMR 3", pkg: "IAP schedule 4–6 y", st: "due", by: "Anita D'Souza" },
    { t: "11:00", room: "Dressing room", name: "Ravi Kumar", proc: "Diabetic foot dressing", pkg: "Package 5 of 10 dressings", st: "due", by: "Anita D'Souza" },
    { t: "11:45", room: "Procedure room 1", name: "Mohan Das", proc: "Chemical peel (glycolic 35%)", pkg: "Session 2 of 4", st: "due", by: "Dr. Meera Iyer" },
    { t: "12:30", room: "Procedure room 2", name: "Sneha Pai", proc: "Nebulisation + spirometry", pkg: "Single", st: "due", by: "Anita D'Souza" },
    { t: "16:00", room: "Vaccine bay", name: "Baby of Divya R. (6 wk)", proc: "Hexavalent 1 + PCV 1 + Rotavirus 1", pkg: "IAP 6 weeks", st: "booked", by: "Anita D'Souza" }
  ];
  CD.vrec = [ // Zoya Khan vaccine record
    ["BCG", "Birth", "12 Mar 2020", "BCG-0420-17", "11/2020", "L upper arm", "Govt. hospital"], ["Hexavalent 3", "14 weeks", "18 Jun 2020", "A21CF117A", "04/2021", "L thigh", "Suvidha"],
    ["MMR 1", "9 months", "14 Dec 2020", "SII-MMR-4402", "07/2021", "R upper arm", "Suvidha"], ["MMR 2", "15 months", "16 Jun 2021", "SII-MMR-4467", "11/2021", "R upper arm", "Suvidha"],
    ["DTaP booster 1", "18 months", "15 Sep 2021", "A21CD402B", "02/2022", "L thigh", "Suvidha"], ["Typhoid conjugate", "2 years", "20 Mar 2022", "TCV-2203-11", "09/2023", "L deltoid", "Suvidha"]];
  CD.vstock = [["Infanrix (DTaP)", "A21CD554A", "03/2027", 14, "FEFO pick"], ["Infanrix (DTaP)", "A21CD601C", "09/2027", 20, ""], ["Tresivac (MMR)", "SII-MMR-4512", "01/2027", 9, "FEFO pick"], ["Typbar-TCV", "TCV-2507-02", "08/2027", 11, ""]];

  CD.inbox = [
    { id: "M1", p: "p8", from: "Arif Ali (son, caregiver · Dubai)", at: "10:06", pri: "High", lang: "English", text: "Abbu's BP was 182/104 this morning and he has a headache since an hour. Should we come in?",
      bot: "Red-flag rule: BP ≥ 180/100 + headache → High · not answered by bot · routed to nurse, SLA 10 min", status: "new" },
    { id: "M2", p: "p7", from: "Deepa Nair (mother of Arjun)", at: "09:41", pri: "Medium", lang: "English", text: "Arjun is wheezy at night and is using the blue inhaler 4 times a day this week.",
      bot: "Asthma control keywords: reliever use > 2×/week → Medium · routed to nurse, SLA 2 h", status: "new" },
    { id: "M3", p: "p9", from: "Priya Sharma", at: "09:12", pri: "Routine", lang: "Hindi", text: "माइग्रेन की दवा ख़त्म हो गई है, क्या दोबारा मिल सकती है?", tr: "My migraine medicine has run out, can I get it again?",
      bot: "Refill request → needs doctor approval · routed to nurse to prepare for Dr. Meera Iyer", status: "new" },
    { id: "M4", p: "p6", from: "Fatima Sheikh", at: "08:55", pri: "Routine", lang: "English", text: "Can I take my thyroxine with my morning tea?",
      bot: "Medication question · no red flags · routed to nurse, SLA 4 h", status: "new" },
    { id: "M5", p: "p10", from: "Abdullah Al-Harbi", at: "08:30", pri: "Info", lang: "Arabic", text: "هل يجب أن أصوم قبل تحليل الدهون؟", tr: "Do I need to fast before the lipid test?",
      bot: "Answered by bot from the clinic's approved FAQ (fasting 9–12 h) · closed · visible for audit", status: "auto" }
  ];

  CD.favs = {
    "General Medicine": [
      { id: "F1", name: "CAP adult — OPD", note: "Community-acquired pneumonia, no comorbidity", used: 46, sec: 31, lines: [["m1", "2", "1-1-1", "5 days", "After food"], ["m3", "1", "SOS", "5 days", "After food"]] },
      { id: "F2", name: "CAP — penicillin allergy", note: "Macrolide or doxycycline", used: 9, sec: 34, lines: [["x6", "1", "1-0-1", "5 days", "After food"], ["m3", "1", "SOS", "5 days", "After food"]] },
      { id: "F3", name: "Viral fever adult", note: "Symptomatic, hydration", used: 212, sec: 18, lines: [["m3", "1", "1-1-1", "3 days", "After food"], ["m7", "1", "0-0-1", "5 days", "Any time"], ["x10", "1", "1-1-1", "3 days", "Any time"]] },
      { id: "F4", name: "HTN refill 30 d", note: "Stable BP, repeat", used: 158, sec: 12, lines: [["m5", "1", "1-0-0", "30 days", "Any time"], ["x4", "1", "1-0-0", "30 days", "Any time"]] },
      { id: "F5", name: "T2DM refill 90 d", note: "eGFR ≥ 45 only", used: 131, sec: 14, lines: [["m4", "1", "1-0-1", "90 days", "After food"], ["m12", "1", "0-0-1", "90 days", "Any time"]] },
      { id: "F6", name: "Acute gastroenteritis adult", note: "ORS first", used: 74, sec: 20, lines: [["x10", "1", "1-1-1", "3 days", "Any time"], ["x9", "1", "SOS", "3 days", "Before food"], ["x5", "1", "1-0-0", "5 days", "Before food"]] }
    ],
    "Dermatology": [
      { id: "F7", name: "Melasma — pregnancy-safe", note: "Azelaic acid + strict SPF", used: 22, sec: 16, lines: [["x2", "1", "1-0-1", "30 days", "Any time"], ["x3", "1", "1-0-0", "90 days", "Any time"]] },
      { id: "F8", name: "Acne moderate", note: "Not for pregnancy or planning", used: 64, sec: 19, lines: [["x6", "1", "1-0-0", "30 days", "After food"], ["x1", "1", "0-0-1", "30 days", "Any time"]] },
      { id: "F9", name: "Androgenetic alopecia", note: "Minoxidil + review at 3 months", used: 41, sec: 15, lines: [["x11", "1", "1-0-1", "90 days", "Any time"]] }
    ],
    "Paediatrics": [
      { id: "F10", name: "Fever child (weight-based)", note: "Paracetamol 15 mg/kg", used: 188, sec: 22, lines: [["m3", "½", "SOS", "3 days", "After food"]] },
      { id: "F11", name: "Asthma step-up", note: "Spacer technique check", used: 27, sec: 25, lines: [["m11", "2", "SOS", "30 days", "Any time"], ["x8", "1", "0-0-1", "30 days", "Any time"]] }
    ]
  };

  CD.solo = [ // Dr. Meera Iyer's day (solo clinic)
    { tok: "T-13", p: "p1", st: "completed", src: "WhatsApp", pay: "paid", payBy: "UPI", t: "08:55", fee: 600 },
    { tok: "T-14", p: "p2", st: "inconsult", src: "Web", pay: "due", t: "09:12", fee: 500 },
    { tok: "T-17", p: "p5", st: "waiting", src: "App", pay: "pkg", t: "09:31", fee: 0, eta: 9 },
    { tok: "T-21", p: "p9", st: "arrived", src: "Self check-in", pay: "due", t: "09:52", fee: 600, eta: 18 },
    { tok: "T-25", name: "Deepa Krishnan", as: "27 F", reason: "Rash on arms, 1 week", st: "booked", src: "WhatsApp · auto-accepted", pay: "due", t: "10:40", fee: 600, eta: 31, isNew: true },
    { tok: "T-28", name: "Rohit Menon", as: "31 M", reason: "Dandruff, itchy scalp", st: "booked", src: "Nabd Connect · auto-accepted", pay: "prepaid", t: "11:00", fee: 600, eta: 44 },
    { tok: "T-29", name: "Mohan Das", as: "48 M", reason: "Chemical peel 2/4", st: "booked", src: "Package", pay: "pkg", t: "11:45", fee: 0, eta: 70 }
  ];
  CD.dues = [["Priya Sharma", "Consultation · today", 600, "Unpaid at checkout"], ["Mohan Das", "Chemical peel 1/4 · 12 Oct", 1500, "WhatsApp reminder sent 2×"], ["Arvind Nair", "Consultation · 09 Oct", 600, "Payment link opened, not paid"]];

  /* ================= Helpers ================= */
  function pn(p) { return esc(NB.pname(p)); }
  function clone(x) { return JSON.parse(JSON.stringify(x)); }
  function today(add) { var d = new Date(2026, 9, 17); d.setDate(d.getDate() + (add || 0)); return d.getDate() + " " + ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][d.getMonth()] + " " + d.getFullYear(); }
  function hm(min) { var t = 9 * 60 + min, h = Math.floor(t / 60), m = t % 60; return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m; }
  function sevPill(sev) { return sev === "crit" ? ui.pill("crit", L("Critical", "حرج", "गंभीर")) : sev === "major" ? ui.pill("urg", L("Major", "كبير", "प्रमुख")) : sev === "moderate" ? ui.pill("warn", L("Moderate", "متوسط", "मध्यम")) : ui.pill("info", "Info"); }
  function flagPill(f) { return f === "crit" ? ui.pill("crit", L("Critical", "حرج", "गंभीर")) : f === "warn" ? ui.pill("warn", L("Abnormal", "غير طبيعي", "असामान्य")) : f === "pend" ? ui.pill("perm", L("Pending", "قيد الانتظار", "लंबित"), "hourglass") : ui.pill("ok", L("Normal", "طبيعي", "सामान्य")); }
  function ext(o) { return NB.gcc() ? o.reqGcc : o.reqIn; }
  function isPreg(p) { return (p.tags || []).some(function (t) { return /Pregnant/.test(t); }); }
  function natNo(p) { var m = NB.mkt(); return m.id === "india" ? "91-" + p.phone.replace(/\D/g, "").slice(0, 4) + "-xxxx-" + p.mrn.slice(-4) : m.natId + " ••• " + p.mrn.slice(-4); }
  function eRxName() { var m = NB.mkt(); return m.id === "ksa" ? "Wasfaty" : m.id === "uae" ? "DHA e-Prescription" : m.id === "qatar" ? "MOPH e-Rx" : "ABDM e-Prescription"; }
  function eRxNote() {
    var m = NB.mkt();
    return m.id === "ksa" ? "Submitted to MOH Wasfaty on signing. The patient collects at any Wasfaty pharmacy with their National ID / Iqama; the QR is a backup."
      : m.id === "uae" ? "Sent to the DHA e-Prescription platform and shared to NABIDH. Any DHA-licensed pharmacy can dispense by Emirates ID or QR."
      : m.id === "qatar" ? "Sent to the MOPH e-Rx service. Pharmacies dispense by QID or QR."
      : "Digitally signed PDF with QR and your " + "NMC/State council registration number. Shared to the patient's ABHA locker (ABDM, FHIR R4) if linked.";
  }
  // Deterministic QR-like code (visual only).
  function qr(seed, px) {
    var n = 25, s = 7, h = "";
    String(seed).split("").forEach(function (c) { s = (s * 31 + c.charCodeAt(0)) >>> 0; });
    function bit() { s = (s * 1103515245 + 12345) >>> 0; return (s >>> 16) & 1; }
    function finder(x, y) { return '<rect x="' + x + '" y="' + y + '" width="7" height="7"/><rect class="w" x="' + (x + 1) + '" y="' + (y + 1) + '" width="5" height="5"/><rect x="' + (x + 2) + '" y="' + (y + 2) + '" width="3" height="3"/>'; }
    for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) {
      var inF = (x < 8 && y < 8) || (x > n - 9 && y < 8) || (x < 8 && y > n - 9);
      if (!inF && bit()) h += '<rect x="' + x + '" y="' + y + '" width="1" height="1"/>';
    }
    return '<svg class="cl-qr" viewBox="-1 -1 ' + (n + 2) + " " + (n + 2) + '" width="' + px + '" height="' + px + '" role="img" aria-label="QR code">' + finder(0, 0) + finder(n - 7, 0) + finder(0, n - 7) + h + "</svg>";
  }
  function spark(vals, w, h, crit) {
    var mn = Math.min.apply(null, vals), mx = Math.max.apply(null, vals), r = mx - mn || 1;
    var pts = vals.map(function (v, i) { return [4 + i * (w - 8) / (vals.length - 1), h - 4 - (v - mn) / r * (h - 8)]; });
    return '<svg class="cl-spark' + (crit ? " crit" : "") + '" viewBox="0 0 ' + w + " " + h + '" width="' + w + '" height="' + h + '" aria-hidden="true"><polyline points="' + pts.map(function (p) { return p[0].toFixed(1) + "," + p[1].toFixed(1); }).join(" ") + '"/>' +
      pts.map(function (p, i) { return '<circle cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="' + (i === pts.length - 1 ? 3.5 : 2) + '"/>'; }).join("") + "</svg>";
  }
  function chip(label, on, attrs) { return '<button class="cl-ch' + (on ? " on" : "") + '" ' + attrs + ">" + label + "</button>"; }
  function setChip(key, val, label, cur) { return chip(esc(label), cur === val, 'data-set="' + key + '" data-val="' + esc(val) + '"'); }
  function stat(label, value, sub, kind) { return '<div class="cl-stat ' + (kind || "") + '"><small>' + label + "</small><b>" + value + "</b>" + (sub ? "<span>" + sub + "</span>" : "") + "</div>"; }
  function newsKind(n) { return n >= 7 ? "crit" : n >= 5 ? "urg" : n >= 3 ? "warn" : "ok"; }

  /* ================= Styles ================= */
  if (!document.getElementById("cl-style")) {
    var css = [
      ".cl-cx{display:grid;grid-template-columns:244px minmax(0,1fr) 332px;gap:16px;align-items:start}",
      ".cl-col{display:flex;flex-direction:column;gap:16px;min-width:0}",
      ".cl-al .btn{white-space:normal;height:auto;min-height:32px;padding:6px 12px;line-height:16px;text-align:start;justify-content:flex-start}",
      ".cl-left .cl-kv{grid-template-columns:minmax(0,1fr);gap:2px}.cl-left .cl-kv dd{margin-bottom:6px}",
      ".cl-left .li .t b,.cl-left .li .t small,.cl-lw .li .t b,.cl-lw .li .t small{white-space:normal}",
      ".frame.tablet .cl-cx,.cl-cx.cmp{grid-template-columns:minmax(0,1fr) 320px}",
      ".frame.tablet .cl-cx .cl-left,.cl-cx.cmp .cl-left{grid-column:1/-1;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;align-items:start}",
      ".cl-ph{display:flex;gap:14px;align-items:flex-start;flex-wrap:wrap}",
      ".cl-ph .who{flex:1;min-width:220px}",
      ".cl-ph h2{margin:0;font-size:20px;line-height:28px;font-weight:600;letter-spacing:-.2px}",
      ".cl-meta{display:flex;flex-wrap:wrap;gap:4px 12px;font-size:12.5px;color:var(--tx2);margin-top:2px}",
      ".cl-bn{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}",
      ".cl-bn>div{display:flex;align-items:center;gap:8px;padding:6px 12px;border-radius:var(--r-md);border:1px solid var(--b-def);font-size:12.5px;line-height:16px;background:var(--s2);min-width:0}",
      ".cl-bn>div b{font-size:11px;letter-spacing:.6px;text-transform:uppercase;font-weight:700}",
      "[dir=rtl] .cl-bn>div b{letter-spacing:0}",
      ".cl-bn .crit{background:var(--crit-t);border-color:var(--crit);border-inline-start-width:3px}.cl-bn .crit .ico,.cl-bn .crit b{color:var(--crit)}",
      ".cl-bn .warn{background:var(--warn-t);border-color:var(--warn);border-style:dotted}.cl-bn .warn .ico,.cl-bn .warn b{color:var(--warn)}",
      ".cl-bn .urg{background:var(--urg-t);border-color:var(--urg);border-inline-start-width:3px}.cl-bn .urg .ico,.cl-bn .urg b{color:var(--urg)}",
      ".cl-bn .info{background:var(--info-t);border-color:var(--info)}.cl-bn .info .ico,.cl-bn .info b{color:var(--info)}",
      ".cl-act{display:flex;align-items:center;gap:8px;flex-wrap:wrap}",
      ".cl-timer{display:inline-flex;align-items:center;gap:8px;height:40px;padding:0 14px;border-radius:var(--r-md);background:var(--acc-tint);border:1px solid var(--acc-line);color:var(--acc-300);font-weight:600;white-space:nowrap;font-size:13px}",
      ".cl-timer b{font-variant-numeric:tabular-nums;font-size:16px;direction:ltr;unicode-bidi:isolate}",
      ".cl-timer.over{background:var(--warn-t);border-color:var(--warn);color:var(--warn)}",
      ".cl-timer.done{background:var(--ok-t);border-color:var(--ok);color:var(--ok)}",
      ".cl-vt{display:grid;grid-template-columns:repeat(auto-fill,minmax(100px,1fr));gap:8px}",
      ".cl-v{background:var(--s2);border:1px solid var(--b-sub);border-radius:var(--r-md);padding:9px 11px;min-width:0}",
      ".cl-v small{display:flex;align-items:center;gap:4px;font-size:11px;color:var(--tx3);line-height:14px}",
      ".cl-v b{display:block;font-size:18px;line-height:24px;font-weight:600;font-variant-numeric:tabular-nums;direction:ltr;unicode-bidi:isolate;text-align:start}",
      ".cl-v .u{font-size:11px;color:var(--tx3);font-weight:500}",
      ".cl-v.warn{border-color:var(--warn);border-style:dotted}.cl-v.warn b{color:var(--warn)}",
      ".cl-v.urg{border-color:var(--urg);border-inline-start-width:3px}.cl-v.urg b{color:var(--urg)}",
      ".cl-v.crit{border-color:var(--crit);border-inline-start-width:3px}.cl-v.crit b{color:var(--crit)}",
      ".cl-ch{display:inline-flex;align-items:center;gap:5px;height:28px;padding:0 10px;border-radius:999px;background:var(--s2);border:1px solid var(--b-def);font-family:inherit;font-size:12.5px;font-weight:500;color:var(--tx2);cursor:pointer;white-space:nowrap}",
      ".cl-ch:hover{border-color:var(--b-str);color:var(--tx)}",
      ".cl-ch.on{background:var(--acc-tint);border-color:var(--acc-line);color:var(--acc-300)}",
      ".cl-ch.sub{border-style:dashed;border-color:var(--sub);color:var(--sub);background:var(--sub-t)}",
      ".cl-ch .ico{width:13px;height:13px}",
      ".cl-cr{display:flex;flex-wrap:wrap;gap:6px;align-items:center;min-width:0}",
      ".cl-lb{font-size:10.5px;color:var(--tx3);text-transform:uppercase;letter-spacing:.6px;font-weight:600;min-width:38px}",
      "[dir=rtl] .cl-lb{letter-spacing:0}",
      ".cl-rxl{border:1px solid var(--b-def);border-radius:var(--r-md);background:var(--s2);padding:12px;display:flex;flex-direction:column;gap:8px;min-width:0}",
      ".cl-rxl.crit{border-color:var(--crit);border-inline-start-width:3px}",
      ".cl-rxl.urg{border-color:var(--urg);border-inline-start-width:3px}",
      ".cl-rxl.warn{border-color:var(--warn);border-style:dotted}",
      ".cl-rxl .nm b{display:block;font-weight:600;font-size:14px;line-height:20px}",
      ".cl-rxl .nm small{display:block;font-size:11.5px;color:var(--tx3);line-height:16px}",
      ".cl-n{width:24px;height:24px;border-radius:8px;background:var(--s3);display:grid;place-items:center;font-size:12px;font-weight:600;color:var(--tx2);flex:none}",
      ".cl-sig{font-size:12.5px;line-height:18px;color:var(--tx2);background:var(--sunk);border-radius:var(--r-sm);padding:8px 10px;display:flex;flex-direction:column;gap:2px}",
      ".cl-sig .pl{color:var(--tx)}",
      ".card.cl-ai{border-color:var(--sub);background:linear-gradient(180deg,var(--sub-t),transparent 140px),var(--s1)}",
      ".cl-ai .ch .eyebrow{color:var(--sub)}",
      ".cl-al{border:1px solid var(--b-def);border-inline-start:3px solid var(--warn);border-radius:var(--r-md);padding:12px;background:var(--s2);display:flex;flex-direction:column;gap:8px;min-width:0}",
      ".cl-al.crit{border-color:var(--crit);border-inline-start-color:var(--crit);background:linear-gradient(var(--crit-t),var(--crit-t)),var(--s2)}",
      ".cl-al.major{border-inline-start-color:var(--urg)}",
      ".cl-al.ovr{border-inline-start-color:var(--perm);border-style:dashed;opacity:.92;background:var(--s2)}",
      ".cl-al .tt{display:flex;gap:8px;align-items:flex-start;font-weight:600;font-size:13.5px;line-height:19px}",
      ".cl-al .tt .ico{margin-top:1px;flex:none}.cl-al.crit .tt .ico{color:var(--crit)}.cl-al.major .tt .ico{color:var(--urg)}.cl-al.moderate .tt .ico{color:var(--warn)}",
      ".cl-al p{margin:0;font-size:12.5px;line-height:18px;color:var(--tx2)}",
      ".cl-src{font-size:11.5px;line-height:16px;color:var(--tx3);display:flex;gap:6px;align-items:flex-start}",
      ".cl-src .ico{width:13px;height:13px;margin-top:1px}",
      ".cl-pass{display:flex;gap:8px;font-size:12.5px;line-height:17px;color:var(--tx2);align-items:flex-start;padding:3px 0}",
      ".cl-pass .ico{color:var(--ok);width:15px;height:15px;margin-top:1px}",
      ".cl-sug{border:1px dashed var(--sub);border-radius:var(--r-md);padding:10px 12px;background:var(--sub-t);display:flex;flex-direction:column;gap:6px;font-size:12.5px;line-height:18px}",
      ".cl-sug.off{opacity:.55}",
      ".cl-qr{background:var(--tx);border-radius:var(--r-sm);padding:6px;display:block;flex:none}",
      ".cl-qr rect{fill:var(--bg-root)}.cl-qr rect.w{fill:var(--tx)}",
      ".cl-spark{display:block;overflow:visible}.cl-spark polyline{fill:none;stroke:var(--acc);stroke-width:2}.cl-spark circle{fill:var(--acc)}.cl-spark.crit polyline{stroke:var(--crit)}.cl-spark.crit circle{fill:var(--crit)}",
      ".cl-stat{background:var(--sheen),var(--s1);border:1px solid var(--b-sub);border-radius:var(--r-lg);padding:14px 16px;display:flex;flex-direction:column;gap:2px;min-width:0}",
      ".cl-stat small{font-size:12px;color:var(--tx2)}.cl-stat b{font-size:24px;line-height:30px;font-weight:600;font-variant-numeric:tabular-nums}.cl-stat span{font-size:11.5px;color:var(--tx3)}",
      ".cl-stat.warn b{color:var(--warn)}.cl-stat.ok b{color:var(--ok)}.cl-stat.acc b{color:var(--acc)}.cl-stat.crit b{color:var(--crit)}",
      ".cl-kv{display:grid;grid-template-columns:minmax(96px,auto) 1fr;gap:6px 12px;font-size:12.5px;line-height:18px}",
      ".cl-kv dt{color:var(--tx3)}.cl-kv dd{margin:0;color:var(--tx)}",
      ".cl-news{width:44px;height:44px;border-radius:12px;display:grid;place-items:center;font-weight:700;font-size:17px;border:1px solid;flex:none;font-variant-numeric:tabular-nums}",
      ".cl-news small{display:block;font-size:8.5px;font-weight:600;letter-spacing:.5px;line-height:9px;margin-top:-6px}",
      ".cl-news.ok{color:var(--ok);background:var(--ok-t);border-color:var(--ok)}.cl-news.warn{color:var(--warn);background:var(--warn-t);border-color:var(--warn);border-style:dotted}",
      ".cl-news.urg{color:var(--urg);background:var(--urg-t);border-color:var(--urg)}.cl-news.crit{color:var(--crit);background:var(--crit-t);border-color:var(--crit)}",
      ".cl-steps{display:flex;flex-wrap:wrap;gap:6px 0;align-items:center}",
      ".cl-step{display:flex;align-items:center;gap:6px;font-size:12px;color:var(--tx3);padding-inline-end:10px}",
      ".cl-step i{width:20px;height:20px;border-radius:99px;display:grid;place-items:center;background:var(--s3);font-style:normal;font-size:10.5px;font-weight:700;flex:none}",
      ".cl-step.ok{color:var(--tx2)}.cl-step.ok i{background:var(--ok-t);color:var(--ok)}",
      ".cl-step.now{color:var(--acc-300);font-weight:600}.cl-step.now i{background:var(--acc-fill);color:var(--on-acc)}",
      ".cl-step+.cl-step::before{content:'';width:14px;height:1px;background:var(--b-str);margin-inline-end:4px}",
      ".cl-day{display:grid;grid-template-columns:72px minmax(0,1fr);gap:10px;align-items:start;padding:10px 0;border-bottom:1px solid var(--div)}",
      ".cl-day:last-child{border-bottom:0}",
      ".cl-day>b{font-size:13px;padding-top:6px}",
      ".cl-blks{display:flex;flex-wrap:wrap;gap:6px;min-width:0}",
      ".cl-blk{border-radius:var(--r-sm);padding:6px 10px;font-size:12px;line-height:16px;border:1px solid var(--acc-line);background:var(--acc-tint);min-width:0}",
      ".cl-blk b{display:block;font-weight:600;font-variant-numeric:tabular-nums}.cl-blk small{display:block;color:var(--tx2);font-size:11px}",
      ".cl-blk.f1{border-color:var(--info);background:var(--info-t)}",
      ".cl-blk.busy{border-style:dashed;border-color:var(--b-str);background:var(--perm-t);color:var(--tx3)}",
      ".cl-blk.leave{border-color:var(--warn);border-style:dotted;background:var(--warn-t)}",
      ".cl-big{height:56px;font-size:16px;border-radius:var(--r-lg);padding:0 22px}",
      ".cl-hero{display:flex;gap:16px;align-items:center;flex-wrap:wrap}",
      ".cl-tok{font-size:30px;line-height:36px;font-weight:700;color:var(--acc);font-variant-numeric:tabular-nums;direction:ltr;unicode-bidi:isolate}",
      ".cl-amt{font-size:34px;line-height:42px;font-weight:700;font-variant-numeric:tabular-nums}",
      ".cl-mic{display:inline-flex;align-items:center;gap:8px}",
      ".cl-mic.on{color:var(--crit);border-color:var(--crit);background:var(--crit-t)}",
      ".cl-ta{width:100%;background:var(--s3);border:1px solid var(--b-def);border-radius:var(--r-md);padding:8px 12px;font-family:inherit;font-size:13px;line-height:19px;color:var(--tx);resize:vertical;min-height:58px}.cl-card-g .cl-ta{min-height:96px}",
      ".cl-ta:focus{outline:0;border-color:var(--acc);box-shadow:0 0 0 3px var(--acc-tint)}",
      ".cl-soap{display:grid;grid-template-columns:28px minmax(0,1fr);gap:8px 10px;align-items:start}",
      ".cl-soap>span{width:28px;height:28px;border-radius:8px;background:var(--acc-tint2);color:var(--acc-300);display:grid;place-items:center;font-weight:700;font-size:13px;margin-top:4px}",
      ".cl-inp{display:flex;align-items:center;gap:8px;height:40px;background:var(--s3);border:1px solid var(--b-def);border-radius:var(--r-md);padding:0 12px;min-width:0}",
      ".cl-inp input{flex:1;min-width:0;background:transparent;border:0;outline:0;font-size:13.5px;color:var(--tx)}",
      ".cl-inp:focus-within{border-color:var(--acc);box-shadow:0 0 0 3px var(--acc-tint)}",
      ".cl-dsug{display:none;flex-direction:column;border:1px solid var(--b-def);border-radius:var(--r-md);background:var(--overlay);max-height:240px;overflow-y:auto}",
      ".cl-dsug.on{display:flex}",
      ".cl-dsug button{display:flex;gap:10px;align-items:center;text-align:start;background:none;border:0;border-bottom:1px solid var(--div);padding:9px 12px;font-family:inherit;font-size:13px;color:var(--tx);cursor:pointer}",
      ".cl-dsug button:hover{background:var(--acc-tint)}",
      ".cl-dsug button small{color:var(--tx3);font-size:11.5px}",
      ".cl-doc{background:var(--s1);border:1px solid var(--b-def);border-radius:var(--r-lg);padding:18px;display:flex;flex-direction:column;gap:12px}",
      ".cl-doc .hd{display:flex;gap:12px;align-items:flex-start;justify-content:space-between;flex-wrap:wrap;border-bottom:1px solid var(--div);padding-bottom:12px}",
      ".cl-doc ol{margin:0;padding-inline-start:20px;display:flex;flex-direction:column;gap:8px;font-size:13px}",
      ".cl-doc ol small{display:block;color:var(--tx2);font-size:12px;line-height:17px}",
      ".cl-msg{border-radius:var(--r-md);padding:10px 12px;font-size:13px;line-height:19px;max-width:88%}",
      ".cl-msg.in{background:var(--s3);align-self:flex-start}.cl-msg.out{background:var(--acc-tint);border:1px solid var(--acc-line);align-self:flex-end}",
      ".cl-msg small{display:block;color:var(--tx3);font-size:11px;margin-top:4px}",
      ".cl-thread{display:flex;flex-direction:column;gap:8px}",
      ".cl-vin{display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:10px}",
      ".cl-vin .fx{display:flex;flex-direction:column;gap:4px;min-width:0}",
      ".cl-vin .field input{font-size:18px;font-weight:600;font-variant-numeric:tabular-nums}",
      ".cl-vin .fx .pill{align-self:flex-start}",
      ".cl-rowc{display:flex;gap:12px;align-items:center;padding:12px;border:1px solid var(--b-sub);border-radius:var(--r-md);background:var(--s2);cursor:pointer;min-width:0}",
      ".cl-rowc:hover{border-color:var(--b-def)}.cl-rowc.on{border-color:var(--acc-line);background:var(--acc-tint)}",
      ".cl-rowc.crit{border-inline-start:3px solid var(--crit)}.cl-rowc.urg{border-inline-start:3px solid var(--urg)}",
      ".cl-rowc .t{flex:1;min-width:0}.cl-rowc .t b{display:block;font-size:13.5px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
      ".cl-rowc .t small{display:block;font-size:12px;color:var(--tx3);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
      ".cl-wrap{white-space:normal!important}",
      ".cl-tabsm{position:sticky;top:0;z-index:2;background:var(--bg-canvas);padding-block:4px}",
      ".cl-grid2{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}",
      ".frame.mobile .cl-grid2{grid-template-columns:minmax(0,1fr)}",
      ".frame.mobile .cl-hide-m{display:none}",
      ".cl-quick{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.cl-quick .btn{padding:0 10px}",
      ".cl-q{display:flex;flex-direction:column;gap:8px}",
      ".cl-qi{display:flex;gap:12px;align-items:center;padding:12px 14px;border-radius:var(--r-md);background:var(--s2);border:1px solid var(--b-sub);min-width:0}",
      ".cl-qi .tok{font-size:15px;min-width:44px}",
      ".cl-qi .t{flex:1;min-width:0}.cl-qi .t b{display:block;font-weight:600;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.cl-qi .t small{display:block;font-size:12px;color:var(--tx3);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
      ".cl-qi.now{border-color:var(--acc-line);background:var(--acc-tint)}",
      "@media (max-width:1180px){.frame.desktop .cl-cx{grid-template-columns:minmax(0,1fr) 290px}.frame.desktop .cl-cx .cl-left{grid-column:1/-1;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;align-items:start}}",
      "@media (max-width:760px){.cl-cx,.cl-cx.cmp{grid-template-columns:minmax(0,1fr)}.cl-cx .cl-left,.cl-cx.cmp .cl-left{grid-template-columns:minmax(0,1fr)}.cl-grid2{grid-template-columns:minmax(0,1fr)}}"
    ].join("\n");
    var stEl = document.createElement("style"); stEl.id = "cl-style"; stEl.textContent = css; document.head.appendChild(stEl);
  }

  // Live "Rx in m:ss" timer: updates in place without re-rendering.
  var rxStart = {};
  function rxElapsed(pid) { if (!rxStart[pid]) rxStart[pid] = Date.now() - 41000; return Math.floor((Date.now() - rxStart[pid]) / 1000); }
  function fmt(sec) { return Math.floor(sec / 60) + ":" + ("0" + (sec % 60)).slice(-2); }
  if (!NB._clTimer) NB._clTimer = setInterval(function () {
    var els = document.querySelectorAll("[data-cl-timer]");
    for (var i = 0; i < els.length; i++) {
      var pid = els[i].getAttribute("data-cl-timer"), s = rxElapsed(pid), b = els[i].querySelector("b");
      if (b) b.textContent = fmt(s);
      els[i].classList.toggle("over", s > 90);
    }
  }, 1000);

  // Client-side filtering for search boxes (no re-render, keeps focus).
  document.addEventListener("input", function (ev) {
    var t = ev.target; if (!t || !t.getAttribute) return;
    var tgt = t.getAttribute("data-cl-filter"); if (!tgt) return;
    var q = t.value.trim().toLowerCase(), box = document.getElementById(tgt); if (!box) return;
    var items = box.querySelectorAll("[data-q]"), any = 0;
    for (var i = 0; i < items.length; i++) { var ok = !q || items[i].getAttribute("data-q").toLowerCase().indexOf(q) >= 0; items[i].style.display = ok ? "" : "none"; if (ok) any++; }
    if (box.classList.contains("cl-dsug")) box.classList.toggle("on", !!q && any > 0);
  });

  /* ================= Prescription model, signatura & AI safety engine ================= */
  function getRx(pid) { return NB.v("clRx_" + pid, null) || clone(scen(pid).rx); }
  function setRx(pid, rx, extra) { var o = extra || {}; o["clRx_" + pid] = rx; NB.setUI(o); }
  function unitOf(d) { return /cream|gel|solution|lotion/i.test(d.name) ? "top" : d.unit === "cap" ? "cap" : d.unit === "inh" ? "inh" : d.unit === "sachet" ? "sachet" : "tab"; }
  function strengthMg(d) { var m = /(\d+(?:\.\d+)?)\s*mg/.exec(d.name); return m ? +m[1] : 0; }
  function perDay(l) { var q = l.q === "½" ? 0.5 : +l.q || 1; var f = l.f === "SOS" ? 3 : l.f.split("-").reduce(function (a, b) { return a + (+b || 0); }, 0); return q * f; }
  function daysOf(l) { return parseInt(l.n, 10) || 0; }
  var SIG = {
    en: { tab: ["tablet", "tablets"], cap: ["capsule", "capsules"], inh: ["puff", "puffs"], sachet: ["sachet in 1 L water", "sachets"], top: "Apply a thin layer", half: "Half a tablet",
      f: { "1-0-0": "in the morning", "0-0-1": "at night", "1-0-1": "morning and night", "1-1-1": "three times a day", "0-1-0": "in the afternoon", "SOS": "only when needed (up to 3 a day)" },
      food: { "After food": "after food", "Before food": "before food", "Any time": "with or without food" }, dur: function (n) { return "for " + n + " days"; } },
    hi: { tab: ["गोली", "गोलियाँ"], cap: ["कैप्सूल", "कैप्सूल"], inh: ["पफ़", "पफ़"], sachet: ["पैकेट 1 लीटर पानी में", "पैकेट"], top: "पतली परत लगाएँ", half: "आधी गोली",
      f: { "1-0-0": "सुबह", "0-0-1": "रात को", "1-0-1": "सुबह और रात", "1-1-1": "दिन में तीन बार", "0-1-0": "दोपहर में", "SOS": "ज़रूरत होने पर ही (दिन में 3 बार तक)" },
      food: { "After food": "खाने के बाद", "Before food": "खाने से पहले", "Any time": "खाने के साथ या बिना" }, dur: function (n) { return n + " दिन तक"; } },
    ar: { tab: ["قرص", "أقراص"], cap: ["كبسولة", "كبسولات"], inh: ["بخة", "بختان"], sachet: ["كيس في لتر ماء", "أكياس"], top: "ضع طبقة رقيقة", half: "نصف قرص",
      f: { "1-0-0": "صباحًا", "0-0-1": "ليلًا", "1-0-1": "صباحًا ومساءً", "1-1-1": "ثلاث مرات يوميًا", "0-1-0": "ظهرًا", "SOS": "عند الحاجة فقط (حتى 3 مرات يوميًا)" },
      food: { "After food": "بعد الأكل", "Before food": "قبل الأكل", "Any time": "مع الأكل أو بدونه" }, dur: function (n) { return "لمدة " + n + " أيام"; } }
  };
  function sig(l, lang) {
    var S = SIG[lang] || SIG.en, d = drug(l.d), u = unitOf(d), n = daysOf(l), qty, sep = lang === "ar" ? "، " : ", ";
    if (u === "top") qty = S.top;
    else if (l.q === "½") qty = S.half;
    else if (lang === "ar" && u === "sachet") qty = "كيس واحد في لتر ماء";
    else if (lang === "ar" && l.q === "1") qty = S[u][0] + (u === "cap" ? " واحدة" : " واحد");
    else if (lang === "ar" && l.q === "2") qty = u === "cap" ? "كبسولتان" : u === "inh" ? "بختان" : "قرصان";
    else qty = l.q + " " + S[u][l.q === "1" ? 0 : 1];
    return qty + " " + (S.f[l.f] || l.f) + (u === "top" ? "" : sep + S.food[l.food || "Any time"]) + sep + S.dur(n);
  }
  function dispenseQty(l) { var d = drug(l.d), u = unitOf(d); if (u === "top") return "1 tube"; var t = Math.ceil(perDay(l) * daysOf(l)); return (u === "inh" ? 1 : t) + " " + (u === "inh" ? "inhaler" : d.unit || "tab"); }
  function sigLang(pid) { var p = NB.pat(pid); return NB.v("clSigL_" + pid, p.lang && p.lang !== "en" ? p.lang : NB.gcc() ? "ar" : "hi"); }

  function checks(pid, rx) {
    var p = NB.pat(pid), sc = scen(pid), out = [], cur = (sc.meds || []).map(function (m) { return m[0]; }).filter(Boolean);
    var KB = NB.gcc() ? "SFDA / DHA drug database 2026-09" : "CIMS India 2026-09 + National Formulary of India 2021";
    rx.forEach(function (l, i) {
      var d = drug(l.d);
      (p.allergies || []).forEach(function (a) {
        if (a.a === "Penicillin" && d.cls === "Penicillin") out.push({ id: "alg-" + l.d, line: i, sev: "crit", kind: L("Allergy", "حساسية", "एलर्जी"),
          title: "Penicillin allergy · " + d.gen + " is a penicillin",
          why: "Recorded reaction: " + a.rx.toLowerCase() + " (" + a.sev.toLowerCase() + "). " + d.gen + " shares the beta-lactam core of penicillin, so cross-reactivity is expected. Do not give any penicillin; avoid first-generation cephalosporins.",
          src: "Patient allergy record (verified 2019) · BNF: Penicillins, contra-indications · AAAAI/ACAAI drug allergy practice parameter 2022",
          data: ["Allergy: Penicillin (anaphylaxis)", "Drug class: " + d.cls],
          fixes: [{ lbl: "Azithromycin 500 mg once daily × 3 days", set: { d: "m2", q: "1", f: "1-0-0", n: "3 days", food: "Any time" } }, { lbl: "Doxycycline 100 mg twice daily × 5 days", set: { d: "x6", q: "1", f: "1-0-1", n: "5 days", food: "After food" } }] });
        if (a.a === "Penicillin" && d.cls === "Cephalosporin") out.push({ id: "algx-" + l.d, line: i, sev: "moderate", kind: L("Cross-reactivity", "تفاعل متصالب", "क्रॉस-रिएक्टिविटी"),
          title: "Possible cross-reactivity · " + d.gen + " with penicillin anaphylaxis", why: "Cross-reactivity with second-generation cephalosporins is about 1–2% and mostly side-chain related. With an anaphylaxis history, give only with monitoring or choose a non-beta-lactam.",
          src: "AAAAI/ACAAI drug allergy practice parameter 2022", data: ["Allergy: Penicillin (anaphylaxis)"], fixes: [{ lbl: "Azithromycin 500 mg once daily × 3 days", set: { d: "m2", q: "1", f: "1-0-0", n: "3 days", food: "Any time" } }] });
        if (a.a === "Aspirin" && d.cls === "NSAID") out.push({ id: "algn-" + l.d, line: i, sev: "moderate", kind: L("Allergy", "حساسية", "एलर्जी"),
          title: "Aspirin reaction on record · " + d.gen + " is an NSAID", why: "Recorded reaction to aspirin: " + a.rx.toLowerCase() + " (" + a.sev.toLowerCase() + "). This is more likely intolerance than allergy, but NSAIDs share the COX-1 mechanism and can cause the same reaction.",
          src: "Patient allergy record · EAACI NSAID hypersensitivity position paper 2019", data: ["Allergy: Aspirin (" + a.rx + ")"], fixes: [{ lbl: "Remove " + d.gen + " (paracetamol instead)", rm: true }] });
      });
      if (isPreg(p) && /^X/.test(d.preg)) out.push({ id: "preg-" + l.d, line: i, sev: "crit", kind: L("Pregnancy", "الحمل", "गर्भावस्था"),
        title: "Pregnancy category X · " + d.gen + " at 14 weeks", why: d.gen + " is a known teratogen (craniofacial, cardiac and CNS defects; miscarriage). It is contraindicated in pregnancy. This line came from the last prescription (12 Jun), written before the pregnancy was recorded.",
        src: "US FDA label (iPLEDGE) · CDSCO prescribing information · pregnancy recorded by Dr. Fatima Rahman, 28 Aug", data: ["Pregnant: 14 weeks", "Category: X"], fixes: [{ lbl: "Remove " + d.gen + " from this prescription", rm: true }] });
      if (isPreg(p) && d.id === "x1") out.push({ id: "pregt-" + l.d, line: i, sev: "moderate", kind: L("Pregnancy", "الحمل", "गर्भावस्था"),
        title: "Topical retinoid in pregnancy · " + d.gen, why: "Systemic absorption is low, but there are case reports of retinoid embryopathy and no safety data. Guidelines advise avoiding topical retinoids in pregnancy.",
        src: "British Association of Dermatologists, prescribing in pregnancy 2023", data: ["Pregnant: 14 weeks", "Category: C"], fixes: [{ lbl: "Switch to Azelaic acid 20% cream twice daily", set: { d: "x2", q: "1", f: "1-0-1", n: "30 days", food: "Any time" } }] });
      if (sc.egfr && d.gen === "Metformin") {
        var mg = strengthMg(d) * perDay(l);
        if (sc.egfr < 30) out.push({ id: "ren-" + l.d, line: i, sev: "crit", kind: L("Renal dose", "جرعة كلوية", "किडनी डोज़"), title: "Metformin contraindicated · eGFR " + sc.egfr, why: "Metformin is contraindicated below eGFR 30 (lactic acidosis risk).", src: "KDIGO 2022 diabetes in CKD, rec 4.1.3", data: ["eGFR " + sc.egfr], fixes: [{ lbl: "Remove metformin", rm: true }] });
        else if (sc.egfr < 45 && mg > 1000) out.push({ id: "ren-" + l.d, line: i, sev: "major", kind: L("Renal dose", "جرعة كلوية", "किडनी डोज़"),
          title: "Dose too high for eGFR " + sc.egfr + " · metformin " + mg.toLocaleString("en-IN") + " mg/day",
          why: "For eGFR 30–44 the maximum is 1,000 mg/day and the dose should not be increased. Acute febrile illness adds lactic-acidosis risk: give sick-day advice.",
          src: "KDIGO 2022 diabetes in CKD, rec 4.1.3 · US FDA metformin label (2016 renal update)", data: ["eGFR " + sc.egfr + " · " + sc.egfrAt, "Current dose 1,000 mg/day"],
          fixes: [{ lbl: "Keep 500 mg twice daily (1,000 mg/day)", set: { q: "1", f: "1-0-1" } }] });
      }
      if (d.cls === "NSAID") {
        var raas = cur.indexOf("x4") >= 0 || rx.some(function (x) { return x.d === "x4"; });
        if (raas) out.push({ id: "ddi-" + l.d, line: i, sev: "major", kind: L("Interaction", "تداخل دوائي", "ड्रग इंटरैक्शन"),
          title: d.gen + " + Telmisartan in CKD (eGFR " + sc.egfr + ", K+ 6.1)",
          why: "NSAIDs with an ARB reduce kidney perfusion (acute kidney injury risk), raise potassium further and blunt blood-pressure control. Potassium is already 6.1 mmol/L today.",
          src: "Stockley's Drug Interactions: NSAIDs + angiotensin II receptor antagonists · KDIGO 2024 CKD guideline §3.10 · " + KB, data: ["Current: Telmisartan 40 mg", "eGFR " + sc.egfr, "K+ 6.1 (critical)"],
          fixes: [{ lbl: "Remove ibuprofen (paracetamol already on Rx)", rm: true }] });
        else if (sc.egfr && sc.egfr < 60) out.push({ id: "renn-" + l.d, line: i, sev: "major", kind: L("Renal dose", "جرعة كلوية", "किडनी डोज़"),
          title: "Avoid NSAIDs · eGFR " + sc.egfr + " (" + sc.ckd + ")", why: "Regular NSAID use accelerates CKD progression and can cause acute kidney injury, especially in older adults. Use paracetamol; consider topical NSAID for knee pain.",
          src: "KDIGO 2024 CKD guideline §3.10 · NICE NG226", data: ["eGFR " + sc.egfr + " · " + sc.egfrAt, "Age " + p.age], fixes: [{ lbl: "Swap to Paracetamol 650 mg when needed", set: { d: "m3", q: "1", f: "SOS", n: "7 days", food: "After food" } }] });
      }
    });
    // Duplicate therapy (same class twice)
    var seen = {};
    rx.forEach(function (l, i) { var cl = drug(l.d).cls; if (seen[cl] != null && cl) out.push({ id: "dup-" + l.d, line: i, sev: "moderate", kind: L("Duplicate", "تكرار", "दोहराव"), title: "Duplicate therapy · two " + cl + " medicines", why: "Two drugs from the same class rarely add benefit and increase side effects.", src: KB, data: [], fixes: [{ lbl: "Remove this line", rm: true }] }); seen[cl] = i; });
    var passes = [], ids = out.map(function (a) { return a.id.split("-")[0]; });
    var algN = (p.allergies || []).length;
    if (!ids.some(function (x) { return /^alg/.test(x); })) passes.push(algN ? "Allergy: no conflict with " + p.allergies.map(function (a) { return a.a; }).join(", ") : "Allergy: no known drug allergies (confirmed today)");
    if (ids.indexOf("ddi") < 0) passes.push("Interactions: none between " + rx.length + " new and " + cur.length + " current medicines");
    if (!isPreg(p)) passes.push(p.sex === "M" ? "Pregnancy: not applicable (male, " + p.age + ")" : p.age < 12 ? "Pregnancy: not applicable (child)" : "Pregnancy: not pregnant per record (asked at check-in)");
    else if (!ids.some(function (x) { return /^preg/.test(x); })) passes.push("Pregnancy (14 weeks): every line is category A or B");
    if (sc.egfr) { if (!ids.some(function (x) { return /^ren/.test(x); })) passes.push("Renal: doses fit eGFR " + sc.egfr + " (" + sc.egfrAt + ")"); }
    else passes.push("Renal: no eGFR on file in the last 12 months");
    if (ids.indexOf("dup") < 0) passes.push("Duplicate therapy: none");
    return { alerts: out, passes: passes, kb: KB };
  }
  function alSt(pid) { return NB.v("clAl_" + pid, {}); }

  NB.act("cl-rx", function (arg) { var a = arg.split("|"), rx = getRx(a[0]); rx[+a[1]][a[2]] = a[3]; setRx(a[0], rx); });
  NB.act("cl-rxdel", function (arg) { var a = arg.split("|"), rx = getRx(a[0]); rx.splice(+a[1], 1); setRx(a[0], rx); });
  NB.act("cl-rxadd", function (arg) {
    var a = arg.split("|"), rx = getRx(a[0]), d = drug(a[1]), u = unitOf(d);
    rx.push({ d: a[1], q: "1", f: u === "top" ? "0-0-1" : d.cls === "Analgesic" ? "SOS" : "1-0-1", n: "5 days", food: u === "top" ? "Any time" : "After food" });
    setRx(a[0], rx); NB.toast(d.name + " added · safety checks re-run");
  });
  NB.act("cl-fav", function (arg) {
    var a = arg.split("|"), rx = getRx(a[0]), set = null;
    Object.keys(CD.favs).forEach(function (k) { CD.favs[k].forEach(function (f) { if (f.id === a[1]) set = f; }); });
    if (!set) return;
    set.lines.forEach(function (x) { if (!rx.some(function (l) { return l.d === x[0]; })) rx.push({ d: x[0], q: x[1], f: x[2], n: x[3], food: x[4] }); });
    setRx(a[0], rx); NB.toast("“" + set.name + "” applied · every line re-checked for this patient");
  });
  NB.act("cl-fix", function (arg) {
    var a = arg.split("|"), pid = a[0], rx = getRx(pid), al = checks(pid, rx).alerts.find(function (x) { return x.id === a[1]; });
    if (!al) return; var fx = al.fixes[+a[2]], before = drug(rx[al.line].d).gen, log = NB.v("clLog_" + pid, []).slice();
    if (fx.rm) rx.splice(al.line, 1); else Object.assign(rx[al.line], fx.set);
    log.push({ t: fx.rm ? "Removed " + before : before + " → " + fx.lbl, why: al.title, at: "10:1" + (log.length + 2) });
    var o = {}; o["clLog_" + pid] = log; setRx(pid, rx, o); NB.toast("Accepted: " + (fx.rm ? "removed " + before : fx.lbl));
  });
  NB.act("cl-ovr", function (arg) {
    var a = arg.split("|"), st = Object.assign({}, alSt(a[0])), txt = document.getElementById("cl-ovr-txt");
    st[a[1]] = { r: NB.v("clOvrR", "Benefit outweighs risk; patient monitored"), note: txt ? txt.value : "", at: "10:14" };
    var o = { clOvrR: undefined }; o["clAl_" + a[0]] = st; NB.state.overlay = null; NB.setUI(o); NB.toast("Override recorded in the audit log with your reason");
  });
  NB.act("cl-sug", function (arg) { var a = arg.split("|"), s = Object.assign({}, NB.v("clSug_" + a[0], {})); s[a[1]] = a[2]; var o = {}; o["clSug_" + a[0]] = s; NB.setUI(o); NB.toast(a[2] === "add" ? "Added to plan. You can edit or remove it." : "Dismissed · feedback helps tune suggestions"); });
  NB.act("cl-ord", function (arg) { var a = arg.split("|"), k = "clOrd_" + a[0], cur = NB.v(k, scen(a[0]).orders).slice(), i = cur.indexOf(a[1]); if (i >= 0) cur.splice(i, 1); else cur.push(a[1]); var o = {}; o[k] = cur; NB.setUI(o); });
  NB.act("cl-dx", function (arg) { var a = arg.split("|"), k = "clDx_" + a[0], cur = NB.v(k, scen(a[0]).dx).slice(), i = cur.indexOf(a[1]); if (i >= 0) cur.splice(i, 1); else cur.push(a[1]); var o = {}; o[k] = cur; NB.state.overlay = null; NB.setUI(o); });
  NB.act("cl-dict", function () { var on = !NB.v("clDict", false); NB.setUI({ clDict: on }); if (!on) NB.toast("Dictation added to Subjective · review before signing"); });
  NB.act("cl-tpl", function (a) { NB.setUI({ clTpl: a }); NB.toast("Template “" + a + "” loaded · smart phrases ready"); });
  NB.act("cl-sign", function (pid) {
    var s = rxElapsed(pid), o = {}; o["clSigned_" + pid] = { at: "10:15", sec: s, id: "RX-" + (NB.mkt().id === "ksa" ? "WSF-" : "") + "26-" + (40812 + pid.length * 7) };
    NB.state.overlay = null; NB.setUI(o); NB.toast("Signed in " + fmt(s) + " · sent to WhatsApp and routed to pharmacy");
  });
  NB.act("cl-open", function (pid) { NB.state.ui.clPat = pid; NB.go("doctor", "consult"); });
  NB.act("cl-soloopen", function (pid) { NB.state.ui.clSoloPat = pid; NB.go("solo", "consult"); });
  NB.act("cl-reset", function (pid) { ["clRx_", "clAl_", "clLog_", "clSigned_", "clSug_"].forEach(function (k) { delete NB.state.ui[k + pid]; }); delete rxStart[pid]; NB.render(); NB.toast("Consult reset to the starting draft"); });

  /* ================= Consultation (doctor, solo, mobile) ================= */
  function banners(p, sc) {
    var b = [];
    (p.allergies || []).forEach(function (a) { b.push(["crit", "alertfill", L("Allergy", "حساسية", "एलर्जी"), a.a + " — " + a.rx + " (" + a.sev.toLowerCase() + ")"]); });
    if (!(p.allergies || []).length) b.push(["info", "checkcircle", L("Allergies", "الحساسية", "एलर्जी"), "No known drug allergies · confirmed today"]);
    if (isPreg(p)) b.push(["urg", "baby", L("Pregnant", "حامل", "गर्भवती"), (sc.preg || "14 weeks") + " · category X/D checks on"]);
    if (sc.egfr) b.push(["warn", "activity", L("Renal", "الكلى", "किडनी"), sc.ckd + " · eGFR " + sc.egfr + " (" + sc.egfrAt.split(" (")[0] + ")"]);
    (sc.labs || []).forEach(function (r) { if (r[4] === "crit") b.push(["crit", "flask", L("Critical lab", "نتيجة حرجة", "गंभीर रिपोर्ट"), r[0] + " " + r[1] + " " + r[2] + " · " + r[5]]); });
    var chron = (p.conditions || []).filter(function (c) { return !/CKD/.test(c); });
    if (chron.length) b.push(["info", "heartpulse", L("Chronic", "مزمن", "दीर्घकालिक"), chron.join(" · ")]);
    return '<div class="cl-bn">' + b.map(function (x) { return '<div class="' + x[0] + '">' + ui.icon(x[1], "sm") + "<b>" + x[2] + "</b><span>" + esc(x[3]) + "</span></div>"; }).join("") + "</div>";
  }
  function timerHtml(pid, signed) {
    if (signed) return '<span class="cl-timer done">' + ui.icon("checkcircle", "sm") + L("Signed in", "وُقّعت خلال", "हस्ताक्षरित") + " <b>" + fmt(signed.sec) + "</b></span>";
    var s = rxElapsed(pid);
    return '<span class="cl-timer' + (s > 90 ? " over" : "") + '" data-cl-timer="' + pid + '" title="Target: new Rx in under 90 s">' + ui.icon("hourglass", "sm") + L("Rx in", "الوصفة خلال", "Rx में") + " <b>" + fmt(s) + "</b><span class=\"small faint\">/ 1:30</span></span>";
  }
  function patHeader(pid, p, sc, ctx) {
    var signed = NB.v("clSigned_" + pid, null), m = NB.mkt();
    var crit = ctx.open.filter(function (a) { return a.sev === "crit"; }).length;
    var acts = timerHtml(pid, signed) + (ctx.open.length ? ui.pill(crit ? "crit" : "warn", ctx.open.length + " " + L("alerts open", "تنبيهات مفتوحة", "अलर्ट खुले")) : ui.pill("ok", L("Checks clear", "الفحوص سليمة", "जाँच साफ़"))) +
      ui.btn(L("Preview", "معاينة", "पूर्वावलोकन"), { ov: "rxpreview", arg: pid, icon: "eye", cls: ctx.mobile ? "sm" : "" }) +
      ui.btn(signed ? L("Signed", "موقّعة", "हस्ताक्षरित") : L("Sign & send", "توقيع وإرسال", "साइन करके भेजें"), { cls: "pri" + (ctx.mobile ? " sm" : ""), ov: "rxpreview", arg: pid, icon: signed ? "checkcircle" : "send", disabled: !!signed });
    return '<div class="card"><div class="cl-ph">' + ui.av(p.name, "xl") + '<div class="who"><div class="row"><h2>' + pn(p) + '</h2><span class="tok">' + esc(sc.tok) + "</span>" +
      (pid === "p3" ? ui.pill("urg", "Urgent · ESI 3") : "") + "</div>" +
      '<div class="cl-meta"><span class="num">' + p.age + " " + p.sex + "</span><span>MRN " + esc(p.mrn) + '</span><span class="ltr">' + esc(m.id === "india" ? "ABHA " + natNo(p) : natNo(p)) + "</span><span>" + esc(sc.visit) + "</span>" +
      (p.ins ? "<span>" + ui.icon("shieldcheck", "xs") + " " + esc(p.ins.name) + "</span>" : "<span>Self-pay</span>") + "</div>" +
      '<div class="cl-meta"><span>' + L("Reason", "السبب", "कारण") + ": <b style=\"color:var(--tx);font-weight:500\">" + esc(sc.reason) + "</b></span></div></div>" +
      '<div class="cl-act">' + acts + "</div></div>" + banners(p, sc) + "</div>";
  }
  function vitalsCard(pid, sc, small) {
    var v = sc.v || {}, items = [["bp", "BP", "mmHg", "activity"], ["hr", "Pulse", "/min", "heartpulse"], ["t", "Temp", "°C", "thermo"], ["spo2", "SpO₂", "%", "pulse"], ["rr", "Resp.", "/min", "activity"], ["wt", "Weight", "kg", "scale"], ["rbs", "RBS", "mg/dL", "flask"], ["pain", "Pain", "/10", "alertcircle"]];
    var body = Object.keys(v).length ? '<div class="cl-vt">' + items.map(function (it) {
      var x = v[it[0]]; if (!x) return "";
      return '<div class="cl-v ' + (x[1] || "") + '"><small>' + ui.icon(it[3], "xs") + it[1] + (x[1] ? " " + ui.icon(x[1] === "crit" ? "alertfill" : "alertcircle", "xs") : "") + "</small><b>" + x[0] + ' <span class="u">' + it[2] + "</span></b></div>";
    }).join("") + '</div><p class="tiny faint" style="margin:10px 0 0">' + ui.icon("user", "xs") + " " + esc(sc.vBy) + (pid === "p3" ? " · NEWS2 4 (low–medium: RR 22, SpO₂ 95, T 38.6, HR 102)" : "") + "</p>"
      : ui.note("warn", "No vitals yet. " + "<a data-ov=\"clvitals\" data-arg=\"" + pid + "\" style=\"cursor:pointer\">Record now</a> or ask the nurse.");
    return ui.card(L("Vitals", "العلامات الحيوية", "वाइटल्स"), body, { icon: "heartpulse", right: ui.btn(L("Edit", "تعديل", "बदलें"), { cls: "sm ghost", ov: "clvitals", arg: pid, icon: "edit" }) });
  }
  function noteCard(pid, sc) {
    var dict = NB.v("clDict", false), tpl = NB.v("clTpl", "");
    var tpls = ["Fever adult", "URTI", "Diabetes review", "HTN review", "Skin"];
    var head = '<div class="cl-cr" style="margin-bottom:12px"><span class="cl-lb">' + L("Template", "قالب", "टेम्पलेट") + "</span>" + tpls.map(function (t) { return chip(esc(t), tpl === t, 'data-act="cl-tpl" data-arg="' + t + '"'); }).join("") + "</div>";
    var rows = [["S", "Subjective", sc.soap.s], ["O", "Objective", sc.soap.o], ["A", "Assessment", sc.soap.a], ["P", "Plan", sc.soap.p]];
    var body = head + (dict ? ui.note("crit", '<span class="row nw" style="gap:8px"><span class="pulse" style="color:var(--crit)"></span><b>Listening…</b></span> English–Hindi code-mix · in-region speech model · audio not stored. Tap the mic again to insert text into Subjective.') : "") +
      '<div class="cl-soap">' + rows.map(function (r) { return "<span title=\"" + r[1] + "\">" + r[0] + '</span><textarea class="cl-ta" dir="auto" aria-label="' + r[1] + '" placeholder="' + r[1] + ' — type, dictate or use .smart phrases">' + esc(r[2]) + "</textarea>"; }).join("") + "</div>" +
      '<p class="tiny faint" style="margin:10px 0 0">Smart phrases: <span class="ltr">.fev .cough .dm .htn .nad</span> · Notes autosave every 5 s and lock when you sign.</p>';
    return ui.card(L("Clinical note (SOAP)", "الملاحظة السريرية", "क्लिनिकल नोट (SOAP)"), body, { icon: "edit",
      right: '<button class="btn sm cl-mic' + (dict ? " on" : "") + '" data-act="cl-dict">' + ui.icon("mic", "sm") + (dict ? L("Stop", "إيقاف", "रोकें") : L("Dictate", "إملاء", "बोलकर लिखें")) + "</button>" });
  }
  function icdName(code) { var r = CD.icd.find(function (x) { return x[0] === code; }); return r ? r[1] : code; }
  function dxCard(pid, sc) {
    var cur = NB.v("clDx_" + pid, sc.dx), std = NB.gcc() ? "ICD-10-AM" : "ICD-10 (WHO)";
    var body = '<div class="chips">' + cur.map(function (c, i) {
      return '<span class="cl-ch on" style="cursor:default"><b class="ltr">' + esc(c) + "</b> " + esc(icdName(c)) + (i === 0 ? ' <span class="pill acc" style="height:18px">Primary</span>' : "") +
        '<span role="button" tabindex="0" data-act="cl-dx" data-arg="' + pid + "|" + c + '" title="Remove" style="cursor:pointer;display:inline-flex">' + ui.icon("close", "xs") + "</span></span>";
    }).join("") + "</div>" + '<div class="row" style="margin-top:12px">' + ui.btn(L("Search " + std, "بحث " + std, std + " खोजें"), { cls: "sm", ov: "icd", arg: pid, icon: "search" }) +
      '<span class="small faint">' + L("Coded at source for claims", "ترميز من المصدر للمطالبات", "क्लेम के लिए कोडिंग") + " · " + esc(NB.mkt().claims) + "</span></div>";
    return ui.card(L("Diagnosis", "التشخيص", "निदान"), body, { icon: "clipboard" });
  }
  function ordersCard(pid, sc) {
    var cur = NB.v("clOrd_" + pid, sc.orders), tot = 0;
    NB.D.labTests.forEach(function (t) { if (cur.indexOf(t.code) >= 0) tot += t.price; });
    var body = '<div class="cl-cr"><span class="cl-lb">Lab</span>' + NB.D.labTests.map(function (t) { return chip((cur.indexOf(t.code) >= 0 ? ui.icon("check", "xs") : "") + esc(t.name.replace("Thyroid stimulating hormone", "TSH").replace("Complete blood count", "CBC").replace("Kidney function test", "KFT")), cur.indexOf(t.code) >= 0, 'data-act="cl-ord" data-arg="' + pid + "|" + t.code + '"'); }).join("") + "</div>" +
      '<div class="cl-cr" style="margin-top:8px"><span class="cl-lb">' + L("Imaging", "الأشعة", "इमेजिंग") + "</span>" + CD.imaging.map(function (t) { return chip((cur.indexOf(t[0]) >= 0 ? ui.icon("check", "xs") : "") + esc(t[1]), cur.indexOf(t[0]) >= 0, 'data-act="cl-ord" data-arg="' + pid + "|" + t[0] + '"'); }).join("") + "</div>" +
      '<div class="row sb" style="margin-top:12px"><span class="small muted">' + cur.length + " orders · lab " + NB.money(tot) + " · " + (cur.indexOf("CRP") >= 0 || cur.indexOf("CBC") >= 0 ? "results in ~2 h" : "—") + " · barcodes print at the collection desk</span>" +
      ui.btn(L("Lab worklist", "قائمة المختبر", "लैब वर्कलिस्ट"), { cls: "sm ghost", go: "lab.worklist", icon: "arrowright" }) + "</div>";
    return ui.card(L("Orders", "الطلبات", "ऑर्डर"), body, { icon: "flask" });
  }
  function rxLine(pid, l, i, lineAlerts, lang, compact) {
    var d = drug(l.d), u = unitOf(d), worst = lineAlerts.some(function (a) { return a.sev === "crit"; }) ? "crit" : lineAlerts.some(function (a) { return a.sev === "major"; }) ? "urg" : lineAlerts.length ? "warn" : "";
    var arg = function (f, v) { return 'data-act="cl-rx" data-arg="' + pid + "|" + i + "|" + f + "|" + v + '"'; };
    var qs = u === "top" ? [] : u === "inh" ? ["1", "2"] : u === "sachet" ? ["1"] : ["½", "1", "2"];
    var fs = u === "top" ? ["1-0-0", "0-0-1", "1-0-1"] : ["1-0-0", "0-0-1", "1-0-1", "1-1-1", "SOS"];
    var ns = ["3 days", "5 days", "7 days", "14 days", "30 days", "90 days"];
    var stock = STOCK[l.d];
    return '<div class="cl-rxl ' + worst + '"><div class="row nw" style="align-items:flex-start"><span class="cl-n">' + (i + 1) + '</span><div class="nm fl"><b>' + esc(d.name) + "</b><small>" + esc(d.gen + " · " + d.cls + (d.sched && d.sched !== "OTC" ? " · Sch " + d.sched : " · OTC")) + " · " +
      (stock === 0 ? '<span class="warnc">not in stock</span>' : stock < 20 ? '<span class="warnc">low stock (' + stock + ")</span>" : "in stock (" + stock + ")") + "</small></div>" +
      lineAlerts.map(function (a) { return sevPill(a.sev); }).join("") +
      '<button class="ibtn" style="width:32px;height:32px" data-act="cl-rxdel" data-arg="' + pid + "|" + i + '" title="Remove line">' + ui.icon("close", "sm") + "</button></div>" +
      '<div class="cl-cr">' + (qs.length ? '<span class="cl-lb">' + L("Dose", "الجرعة", "खुराक") + "</span>" + qs.map(function (q) { return chip(q, l.q === q, arg("q", q)); }).join("") : "") +
      '<span class="cl-lb">' + L("When", "متى", "कब") + "</span>" + fs.map(function (f) { return chip('<span class="ltr">' + f + "</span>", l.f === f, arg("f", f)); }).join("") + "</div>" +
      '<div class="cl-cr"><span class="cl-lb">' + L("For", "المدة", "अवधि") + "</span>" + ns.map(function (n) { return chip(n.replace(" days", " d"), l.n === n, arg("n", n)); }).join("") +
      (u === "top" ? "" : (compact ? "</div><div class=\"cl-cr\">" : "") + '<span class="cl-lb">' + L("Food", "الطعام", "भोजन") + "</span>" + ["After food", "Before food", "Any time"].map(function (f) { return chip(f.replace(" food", ""), (l.food || "Any time") === f, arg("food", f)); }).join("")) + "</div>" +
      '<div class="cl-sig"><span class="pl" dir="ltr">' + esc(sig(l, "en")) + ' <span class="faint">· dispense ' + esc(dispenseQty(l)) + "</span></span>" + (lang !== "en" ? '<span dir="' + (lang === "ar" ? "rtl" : "ltr") + '" lang="' + lang + '">' + esc(sig(l, lang)) + "</span>" : "") + "</div></div>";
  }
  function rxCard(pid, ctx, compact) {
    var rx = ctx.rx, lang = sigLang(pid), spec = ctx.solo ? "Dermatology" : "General Medicine", log = NB.v("clLog_" + pid, []);
    var byLine = function (i) { return ctx.open.filter(function (a) { return a.line === i; }); };
    var favs = CD.favs[spec].slice(0, 5);
    var body = '<div class="cl-cr"><span class="cl-lb">' + L("Sets", "مجموعات", "सेट") + "</span>" + favs.map(function (f) { return chip(ui.icon("star", "xs") + esc(f.name), false, 'data-act="cl-fav" data-arg="' + pid + "|" + f.id + '"'); }).join("") +
      chip(ui.icon("history", "xs") + L("Repeat last Rx", "تكرار آخر وصفة", "पिछला Rx दोहराएँ"), false, 'data-act="toast" data-arg="Last Rx already loaded as the starting draft"') + "</div>" +
      '<div class="stack s4" style="margin-top:10px"><label class="cl-inp">' + ui.icon("search", "sm faint") + '<input data-cl-filter="cl-ds-' + pid + '" placeholder="' + esc(L("Add drug: brand, generic or class", "أضف دواء", "दवा जोड़ें: ब्रांड, जेनेरिक")) + '" autocomplete="off"></label>' +
      '<div class="cl-dsug" id="cl-ds-' + pid + '">' + allDrugs().map(function (d) {
        return '<button data-act="cl-rxadd" data-arg="' + pid + "|" + d.id + '" data-q="' + esc(d.name + " " + d.gen + " " + d.cls) + '">' + ui.icon("pill", "sm faint") + '<span class="fl">' + esc(d.name) + "<br><small>" + esc(d.gen + " · " + d.cls + " · preg " + d.preg) + "</small></span>" + NB.money(d.price) + "</button>";
      }).join("") + "</div></div>" +
      '<div class="stack s8" style="margin-top:12px">' + (rx.length ? rx.map(function (l, i) { return rxLine(pid, l, i, byLine(i), lang, compact); }).join("") : '<div class="empty">' + L("No medicines yet. Tap a set or search.", "لا أدوية بعد", "अभी कोई दवा नहीं") + "</div>") + "</div>" +
      (log.length ? '<div class="stack s4" style="margin-top:12px">' + log.map(function (x) { return '<div class="cl-pass">' + ui.icon("checkcircle") + "<span><b style=\"color:var(--tx);font-weight:500\">" + esc(x.t) + "</b> · accepted AI safety alert · " + x.at + "</span></div>"; }).join("") + "</div>" : "") +
      '<div class="row sb" style="margin-top:12px"><span class="small faint">' + L("Patient instructions in", "تعليمات المريض بـ", "मरीज़ निर्देश भाषा") + "</span>" + ui.seg("clSigL_" + pid, [["hi", "हिंदी"], ["ar", "العربية"], ["en", "English only"]], lang) + "</div>";
    return ui.card(L("e-Prescription", "الوصفة الإلكترونية", "ई-प्रिस्क्रिप्शन") + ' <span class="pill mute" style="text-transform:none;letter-spacing:0">' + esc(eRxName()) + "</span>", body, { icon: "pill", right: '<span class="small faint">' + rx.length + " " + L("items", "أصناف", "आइटम") + "</span>" });
  }
  function alertCard(pid, a, ov) {
    var h = '<div class="cl-al ' + a.sev + (ov ? " ovr" : "") + '"><div class="row" style="gap:6px">' + sevPill(a.sev) + ui.pill("mute", a.kind, "dot") + (ov ? ui.pill("perm", L("Overridden · audited", "تم التجاوز · مُدقق", "ओवरराइड · ऑडिटेड")) : "") + "</div>" +
      '<div class="tt">' + ui.icon(a.sev === "crit" ? "alertfill" : a.sev === "major" ? "bolt" : "alertcircle", "sm") + "<span>" + esc(a.title) + "</span></div><p>" + esc(a.why) + "</p>" +
      (a.data.length ? '<div class="chips" style="gap:4px">' + a.data.map(function (x) { return '<span class="pill mute" style="text-transform:none;letter-spacing:0;font-weight:500">' + esc(x) + "</span>"; }).join("") + "</div>" : "") +
      '<div class="cl-src">' + ui.icon("file") + "<span>" + L("Source", "المصدر", "स्रोत") + ": " + esc(a.src) + "</span></div>";
    if (ov) h += '<div class="small muted">' + ui.icon("lock", "xs") + " Dr. Vikram Shah · " + ov.at + " · " + esc(ov.r) + (ov.note ? " — " + esc(ov.note) : "") + "</div>";
    else h += '<div class="stack s4">' + a.fixes.map(function (f, k) { return ui.btn(L("Accept", "قبول", "स्वीकार") + ": " + f.lbl, { cls: "sm okb", act: "cl-fix", arg: pid + "|" + a.id + "|" + k, icon: "check" }); }).join("") +
      ui.btn(L("Override with reason", "تجاوز مع ذكر السبب", "कारण सहित ओवरराइड"), { cls: "sm ghost", ov: "rxoverride", arg: pid + "|" + a.id, icon: "edit" }) + "</div>";
    return h + "</div>";
  }
  function safetyCard(pid, ctx) {
    var st = alSt(pid), r = ctx.res, crit = ctx.open.filter(function (a) { return a.sev === "crit"; }).length;
    var head = '<div class="row sb" style="margin-bottom:10px">' + ui.pill("sub", L("Checks only · you decide", "فحوص فقط · القرار لك", "सिर्फ़ जाँच · फ़ैसला आपका")) +
      '<span class="tiny faint">' + ctx.rx.length + " lines · 0.4 s</span></div>" +
      (ctx.open.length ? '<p class="small" style="margin:0 0 10px">' + (crit ? '<b class="critc">' + crit + " critical</b> must be accepted or overridden before signing. " : "") + (ctx.open.length - crit ? (ctx.open.length - crit) + " other alert" + (ctx.open.length - crit > 1 ? "s" : "") + " to review." : "") + "</p>"
        : ui.note("ok", L("No open safety alerts. Ready to sign.", "لا توجد تنبيهات مفتوحة. جاهزة للتوقيع.", "कोई अलर्ट नहीं। साइन के लिए तैयार।")));
    var body = head + '<div class="stack s8">' + r.alerts.map(function (a) { return alertCard(pid, a, st[a.id]); }).join("") + "</div>" +
      '<div class="stack s4" style="margin-top:12px"><span class="eyebrow">' + L("Passed", "اجتاز", "पास") + "</span>" + r.passes.map(function (x) { return '<div class="cl-pass">' + ui.icon("checkcircle") + "<span>" + esc(x) + "</span></div>"; }).join("") + "</div>" +
      '<p class="tiny faint" style="margin:12px 0 0">Nabd CDS rules v3.2 · drug KB: ' + esc(r.kb) + " · runs in " + esc(NB.mkt().residency) + " · thresholds set by your medical director · overrides are audited and reviewed monthly.</p>";
    return ui.card(L("AI safety net", "شبكة الأمان الذكية", "AI सुरक्षा जाँच"), body, { icon: "sparkle", cls: "cl-ai" });
  }
  function sugCard(pid, sc) {
    var st = NB.v("clSug_" + pid, {});
    if (!sc.sug.length) return "";
    var body = '<div class="row sb" style="margin-bottom:10px">' + ui.pill("sub", L("Suggestion — doctor decides", "اقتراح — القرار للطبيب", "सुझाव — फ़ैसला डॉक्टर का")) + '<span class="tiny faint">Never added automatically</span></div><div class="stack s8">' +
      sc.sug.map(function (s, i) {
        var v = st[i];
        return '<div class="cl-sug' + (v === "dis" ? " off" : "") + '"><span>' + esc(s[0]) + '</span><span class="cl-src">' + ui.icon("file") + esc(s[1]) + "</span>" +
          (v === "add" ? ui.pill("ok", L("Added to plan", "أضيف إلى الخطة", "प्लान में जोड़ा")) : v === "dis" ? ui.pill("mute", L("Dismissed", "تم التجاهل", "ख़ारिज")) :
            '<div class="btns">' + ui.btn(L("Add to plan", "أضف إلى الخطة", "प्लान में जोड़ें"), { cls: "sm", act: "cl-sug", arg: pid + "|" + i + "|add", icon: "plus" }) + ui.btn(L("Dismiss", "تجاهل", "ख़ारिज"), { cls: "sm ghost", act: "cl-sug", arg: pid + "|" + i + "|dis" }) + "</div>") + "</div>";
      }).join("") + "</div>";
    return ui.card(L("AI suggestions", "اقتراحات الذكاء الاصطناعي", "AI सुझाव"), body, { icon: "brain", cls: "cl-ai" });
  }
  function followCard(pid) {
    var fu = NB.v("clFu_" + pid, pid === "p3" ? "48 h" : "2 weeks");
    var body = '<div class="cl-cr">' + ["48 h", "1 week", "2 weeks", "1 month", "SOS"].map(function (x) { return setChip("clFu_" + pid, x, x, fu); }).join("") + "</div>" +
      ui.toggle("clFuBook_" + pid, L("Book the slot and remind on WhatsApp", "احجز الموعد وذكّر عبر واتساب", "स्लॉट बुक करें और व्हाट्सऐप रिमाइंडर"), "Patient gets a confirm / reschedule button 24 h before", true) +
      ui.toggle("clFuRes_" + pid, L("Send results to patient when ready", "أرسل النتائج للمريض عند جاهزيتها", "रिपोर्ट तैयार होने पर मरीज़ को भेजें"), "After you review them in your results inbox", true) +
      '<div class="stack s8" style="margin-top:12px">' + ui.btn(L("Refer within hospital", "إحالة داخل المستشفى", "अस्पताल में रेफ़र करें"), { cls: "blk", ov: "referral", arg: pid + "|int", icon: "arrowright" }) +
      ui.btn(L("Refer outside (needs consent)", "إحالة خارجية (تتطلب موافقة)", "बाहर रेफ़र करें (सहमति)"), { cls: "blk", ov: "referral", arg: pid + "|ext", icon: "share" }) +
      ui.btn(L("Medical certificate", "شهادة طبية", "मेडिकल सर्टिफ़िकेट"), { cls: "blk ghost", act: "toast", arg: "Sick-leave certificate (3 days) added to the visit documents", icon: "file" }) + "</div>";
    return ui.card(L("Follow-up & referral", "المتابعة والإحالة", "फ़ॉलो-अप और रेफ़रल"), body, { icon: "calendar" });
  }
  function signCard(pid, ctx) {
    var signed = NB.v("clSigned_" + pid, null), val = NB.v("clVal", "14"), crit = ctx.open.filter(function (a) { return a.sev === "crit"; }).length;
    var h1 = ctx.rx.some(function (l) { return drug(l.d).sched === "H1"; });
    if (signed) return ui.card(L("Signed & sent", "تم التوقيع والإرسال", "साइन और भेजा गया"), ui.note("ok", "<b>" + esc(signed.id) + "</b> signed at " + signed.at + " in " + fmt(signed.sec) + ". Sent to the patient on WhatsApp and the Nabd app; routed to " + (NB.v("clRoute", "inhouse") === "inhouse" ? "the in-house pharmacy e-Rx queue." : "the patient's chosen pharmacy.")) +
      '<div class="btns" style="margin-top:12px">' + ui.btn(L("Call next patient", "استدعاء المريض التالي", "अगला मरीज़ बुलाएँ"), { cls: "pri", act: "toast", arg: "Next token called on the display and WhatsApp", icon: "play" }) + ui.btn(L("Pharmacy queue", "طابور الصيدلية", "फ़ार्मेसी क़तार"), { go: "pharmacist.dispense", icon: "pill" }) + ui.btn("Reset demo", { cls: "ghost", act: "cl-reset", arg: pid }) + "</div>", { icon: "checkcircle", cls: "hl" });
    var body = '<dl class="cl-kv"><dt>' + L("Standard", "المعيار", "मानक") + "</dt><dd>" + esc(eRxName()) + "</dd><dt>" + L("Valid until", "صالحة حتى", "वैध तक") + '</dt><dd class="num">' + today(+val) + (h1 ? " · H1 lines: single dispense" : "") + "</dd><dt>" + L("Send to", "إرسال إلى", "भेजें") + "</dt><dd>WhatsApp + Nabd app</dd><dt>" + L("Pharmacy", "الصيدلية", "फ़ार्मेसी") + "</dt><dd>" + (NB.v("clRoute", "inhouse") === "inhouse" ? "In-house (patient may choose)" : "Patient's choice") + "</dd></dl>" +
      '<div class="row" style="margin-top:10px"><span class="small faint">' + L("Validity", "الصلاحية", "वैधता") + "</span>" + ui.seg("clVal", [["7", "7 d"], ["14", "14 d"], ["30", "30 d"]], "14") + "</div>" +
      (crit ? '<div style="margin-top:12px">' + ui.note("crit", "Resolve " + crit + " critical alert" + (crit > 1 ? "s" : "") + " (accept or override with a reason) to sign.") + "</div>" : "") +
      '<div class="row" style="margin-top:12px">' + timerHtml(pid) + "</div>" +
      '<div class="btns" style="margin-top:12px">' + ui.btn(L("Preview", "معاينة", "पूर्वावलोकन"), { ov: "rxpreview", arg: pid, icon: "eye" }) + ui.btn(L("Sign & send", "توقيع وإرسال", "साइन करके भेजें"), { cls: "pri", ov: "rxpreview", arg: pid, icon: "send", disabled: !!crit || !ctx.rx.length }) + "</div>";
    return ui.card(L("Sign & send", "توقيع وإرسال", "साइन करके भेजें"), body, { icon: "send" });
  }
  function ctxCards(pid, sc) {
    var pre = ui.card(L("Pre-consult summary", "ملخص ما قبل الاستشارة", "परामर्श-पूर्व सारांश"), '<dl class="cl-kv">' + sc.pre.map(function (r) { return "<dt>" + esc(r[0]) + "</dt><dd>" + esc(r[1]) + "</dd>"; }).join("") + "</dl>", { icon: "chat", right: ui.pill("info", "WhatsApp") });
    var prob = ui.card(L("Problems", "المشكلات", "समस्याएँ"), '<div class="lst">' + (sc.problems.length ? sc.problems.map(function (r) { return ui.li("", esc(r[1]), (r[0] ? '<span class="ltr">' + esc(r[0]) + "</span> · " : "") + esc(r[2])); }).join("") : '<div class="empty">None recorded</div>') + "</div>", { icon: "clipboard" });
    var meds = ui.card(L("Current medicines", "الأدوية الحالية", "वर्तमान दवाएँ"), '<div class="lst">' + (sc.meds.length ? sc.meds.map(function (r) { return ui.li("", esc(r[1]), '<span class="ltr">' + esc(r[2]) + "</span> · " + esc(r[3])); }).join("") : '<div class="empty">None</div>') + "</div>", { icon: "pill" });
    var labs = ui.card(L("Recent results", "أحدث النتائج", "हाल की रिपोर्ट"), '<div class="lst">' + (sc.labs.length ? sc.labs.map(function (r) { return ui.li("", esc(r[0]) + ' <span class="num ' + (r[4] === "crit" ? "critc" : r[4] === "warn" ? "warnc" : "") + '">' + esc(r[1]) + "</span> " + '<span class="small faint">' + esc(r[2]) + "</span>", esc(r[5]) + (r[3] ? " · ref " + esc(r[3]) : ""), r[4] ? flagPill(r[4]) : ""); }).join("") : '<div class="empty">No results</div>') + "</div>", { icon: "flask", right: ui.btn("Inbox", { cls: "sm ghost", go: "doctor.results" }) });
    var tl = sc.tl.length ? ui.card(L("Timeline", "السجل الزمني", "टाइमलाइन"), '<div class="tl">' + sc.tl.map(function (e) { return '<div class="ev"><span class="dotc ' + e[0] + '">' + ui.icon(e[1], "sm") + '</span><div class="bd"><b>' + esc(e[2]) + "</b><small>" + esc(e[3]) + "</small></div></div>"; }).join("") + "</div>", { icon: "history" }) : "";
    return { pre: pre, prob: prob, meds: meds, labs: labs, tl: tl };
  }

  function consult(c, o) {
    var pid = o.pid, p = NB.pat(pid), sc = scen(pid), rx = getRx(pid), res = checks(pid, rx), st = alSt(pid);
    var open = res.alerts.filter(function (a) { return !st[a.id]; });
    var ctx = { rx: rx, res: res, open: open, mobile: c.mobile, solo: o.solo };
    var cc = ctxCards(pid, sc);
    var switcher = '<div class="cl-cr">' + o.queue.map(function (q) { var pp = NB.pat(q[0]); return chip((q[0] === pid ? ui.icon("dot", "xs") : "") + '<span class="tok" style="font-size:12px">' + esc(q[1]) + "</span> " + pn(pp) + (q[2] ? " · " + esc(q[2]) : ""), q[0] === pid, 'data-set="' + o.key + '" data-val="' + q[0] + '"'); }).join("") + "</div>";
    var title = ui.header(L("Consultation", "الاستشارة", "परामर्श"), esc(o.who) + " · " + esc(NB.fac().name), switcher);
    if (c.mobile) {
      var tab = NB.v("clMTab", "rx");
      var tabs = '<div class="cl-tabsm">' + ui.seg("clMTab", [["ctx", L("Summary", "الملخص", "सारांश")], ["note", L("Note", "الملاحظة", "नोट")], ["rx", "Rx", open.length || null], ["send", L("Send", "إرسال", "भेजें")]], "rx") + "</div>";
      var body = tab === "ctx" ? vitalsCard(pid, sc) + cc.pre + cc.labs + cc.prob + cc.meds + cc.tl
        : tab === "note" ? noteCard(pid, sc) + dxCard(pid, sc) + ordersCard(pid, sc)
        : tab === "send" ? signCard(pid, ctx) + followCard(pid)
        : (open.length ? safetyCard(pid, ctx) : "") + rxCard(pid, ctx, true) + (open.length ? "" : safetyCard(pid, ctx)) + sugCard(pid, sc);
      return switcher + patHeader(pid, p, sc, ctx) + tabs + '<div class="stack s16">' + body + "</div>";
    }
    var left = '<div class="cl-col cl-left">' + cc.pre + cc.labs + cc.prob + cc.meds + cc.tl + "</div>";
    var centre = '<div class="cl-col">' + vitalsCard(pid, sc) + noteCard(pid, sc) + dxCard(pid, sc) + rxCard(pid, ctx, o.solo) + ordersCard(pid, sc) + "</div>";
    var right = '<div class="cl-col">' + safetyCard(pid, ctx) + signCard(pid, ctx) + sugCard(pid, sc) + followCard(pid) + "</div>";
    return title + patHeader(pid, p, sc, ctx) + '<div class="cl-cx' + (o.solo ? " cmp" : "") + '">' + left + centre + right + "</div>";
  }

  NB.screen("doctor.consult", function (c) {
    return consult(c, { pid: NB.v("clPat", "p3"), key: "clPat", who: "Dr. Vikram Shah · General Medicine · Room 1",
      queue: [["p3", "T-15", L("now", "الآن", "अभी")], ["p8", "T-20", ""], ["p2", "Demo", L("pregnant", "حامل", "गर्भवती")]] });
  });
  NB.screen("solo.consult", function (c) {
    return consult(c, { pid: NB.v("clSoloPat", "p2"), key: "clSoloPat", solo: true, who: "Dr. Meera Iyer · Dermatology",
      queue: [["p2", "T-14", L("now", "الآن", "अभी")], ["p5", "T-17", ""], ["p9", "T-21", ""]] });
  });

  /* ================= Doctor: My day ================= */
  var DST = { completed: ["ok", "Seen"], checkout: ["info", "Checkout"], next: ["acc", "Next"], waiting: ["warn", "Waiting"], arrived: ["info", "Arrived"], booked: ["mute", "Booked"], rounds: ["sync", "Ward round"] };
  function facShort(i) { var f = NB.facilities()[i] || {}; return (f.name || "").replace(" Multi-Speciality", "").replace(" Polyclinic", ""); }
  function vtiles(sc, keys) {
    var v = sc.v || {}, lab = { bp: ["BP", "mmHg"], hr: ["Pulse", "/min"], t: ["Temp", "°C"], spo2: ["SpO₂", "%"], rr: ["Resp.", "/min"], rbs: ["RBS", "mg/dL"], wt: ["Weight", "kg"], pain: ["Pain", "/10"] };
    return '<div class="cl-vt">' + keys.filter(function (k) { return v[k]; }).map(function (k) { return '<div class="cl-v ' + (v[k][1] || "") + '"><small>' + lab[k][0] + "</small><b>" + v[k][0] + ' <span class="u">' + lab[k][1] + "</span></b></div>"; }).join("") + "</div>";
  }
  NB.screen("doctor.myday", function (c) {
    var delay = +NB.v("clDelay", NB.doc("d2").delay || 0), f = NB.v("clDayF", "all"), fs = NB.facilities(), shift = delay - 15;
    var rows = CD.day.filter(function (r) { return f === "all" || String(r.fac) === f; });
    var seen = CD.day.filter(function (r) { return r.st === "completed" || r.st === "checkout"; }).length;
    var rem = CD.day.filter(function (r) { return /next|waiting|booked|arrived/.test(r.st); }).length;
    var p = NB.pat("p3"), sc = scen("p3");
    var h = ui.header(L("My day", "يومي", "मेरा दिन"), "Sat, 17 Oct 2026 · " + esc(facShort(0)) + " OPD 09:00–13:00 · " + esc(facShort(1)) + " rounds 13:30, OPD 17:00–20:00",
      ui.seg("clDayF", [["all", L("All my facilities", "كل منشآتي", "मेरी सभी सुविधाएँ")], ["0", facShort(0)], ["1", facShort(1)]], "all"));
    h += '<div class="grid g4 kpis">' + stat(L("Seen", "تمت رؤيتهم", "देखे गए"), seen, "of " + (seen + rem) + " today", "ok") + stat(L("Remaining", "المتبقون", "बाकी"), rem, "5 this morning · 4 this evening") +
      stat(L("Avg consult", "متوسط الاستشارة", "औसत परामर्श"), '7.4 <span class="small faint">min</span>', "Target 8 min · Rx median 0:52", "acc") +
      stat(L("Running late", "التأخير", "देरी"), delay ? "+" + delay + ' <span class="small faint">min</span>' : L("On time", "في الوقت", "समय पर"), delay ? "Patients' ETAs already include this" : "No delay broadcast", delay ? "warn" : "ok") + "</div>";
    var next = '<div class="card hl"><div class="ch"><span class="eyebrow">' + ui.icon("play", "sm") + L("Next patient", "المريض التالي", "अगला मरीज़") + "</span>" + ui.pill("urg", "Urgent · ESI 3") + ui.pill("ok", "Vitals done") + "</div>" +
      '<div class="cl-hero">' + ui.av(p.name, "xl") + '<div class="fl" style="min-width:200px"><div class="row"><h2 class="h" style="font-size:19px">' + pn(p) + '</h2><span class="tok">T-15</span></div><div class="small muted">' + p.age + " " + p.sex + " · Walk-in · waited 16 min · " + esc(sc.reason) + "</div></div>" +
      '<div class="btns">' + ui.btn(L("Call in", "استدعاء", "बुलाएँ"), { icon: "bell", act: "toast", arg: "T-15 called on the waiting-room display and WhatsApp" }) + ui.btn(L("Start consult", "ابدأ الاستشارة", "परामर्श शुरू करें"), { cls: "pri", act: "cl-open", arg: "p3", icon: "steth" }) + "</div></div>" +
      banners(p, sc) + '<div class="cl-grid2" style="margin-top:14px"><div class="stack s8"><span class="eyebrow">' + L("Pre-consult (WhatsApp)", "ما قبل الاستشارة", "परामर्श-पूर्व") + '</span><dl class="cl-kv">' + sc.pre.slice(0, 3).map(function (r) { return "<dt>" + esc(r[0]) + "</dt><dd>" + esc(r[1]) + "</dd>"; }).join("") + "</dl></div>" +
      '<div class="stack s8"><span class="eyebrow">' + L("Vitals from nurse · 09:38", "العلامات من الممرضة", "नर्स से वाइटल्स") + "</span>" + vtiles(sc, ["bp", "hr", "t", "spo2", "rr", "rbs"]) +
      '<span class="eyebrow" style="margin-top:4px">' + L("Pending / new results", "نتائج معلقة", "लंबित रिपोर्ट") + '</span><div class="chips">' + ui.pill("crit", "K+ 6.1 · not acknowledged") + ui.pill("warn", "HbA1c 8.4") + ui.pill("perm", "CRP ordered", "hourglass") + "</div></div></div></div>";
    var table = ui.table([{ h: L("Time", "الوقت", "समय") }, { h: L("Token", "الرقم", "टोकन") }, { h: L("Patient", "المريض", "मरीज़") }, { h: L("Visit", "الزيارة", "विज़िट") }, { h: L("Status", "الحالة", "स्थिति") }, { h: "" }],
      rows.map(function (r) {
        var pp = r.p ? NB.pat(r.p) : null, s = DST[r.st], done = r.st === "completed" || r.st === "checkout";
        var time = done ? '<span class="num ltr">' + r.t + '</span><small class="faint" style="display:block">' + r.dur + " min</small>"
          : '<span class="num ltr">' + hm(r.min + (r.fac === 0 ? delay : 0)) + "</span>" + (r.fac === 0 && shift ? '<small class="warnc" style="display:block">' + (shift > 0 ? "+" : "") + shift + " min</small>" : '<small class="faint" style="display:block">ETA</small>');
        var act = r.p && !done ? ui.btn(L("Open", "فتح", "खोलें"), { cls: "sm", act: "cl-open", arg: r.p, icon: "steth" }) : r.st === "rounds" ? ui.btn(L("Rounds", "الجولات", "राउंड"), { cls: "sm", go: "doctor.inpatients", icon: "bed" }) : "";
        return { cls: r.flag || "", cells: [time, '<span class="tok">' + r.tok + "</span>", ui.cellp(pp ? pn(pp) : esc(r.name), pp ? pp.age + " " + pp.sex + " · " + esc(r.reason) : esc(r.as + " · " + r.reason)),
          ui.cellp(esc(r.type) + " · " + esc(r.src || "Desk"), ui.icon("building", "xs") + " " + esc(facShort(r.fac))), ui.pill(s[0], s[1]) + (r.flag === "urg" ? " " + ui.pill("urg", "Urgent") : ""), act] };
      }), { min: 620 });
    var rail = ui.card(L("Running late?", "متأخر؟", "देर हो रही है?"), '<p class="small muted" style="margin:0 0 10px">One tap shifts every waiting and booked patient\'s ETA and sends a WhatsApp update with “wait / reschedule / see another doctor”.</p><div class="chips">' +
        [0, 15, 30, 45, 60].map(function (n) { return '<button class="cl-ch' + (delay === n ? " on" : "") + '" data-ov="cldelay" data-arg="' + n + '">' + (n ? "+" + n + " min" : L("On time", "في الوقت", "समय पर")) + "</button>"; }).join("") + "</div>" +
        '<p class="tiny faint" style="margin:10px 0 0">Last broadcast 09:46 · 6 patients notified · 1 chose another doctor</p>', { icon: "clockh" }) +
      ui.card(L("Needs you", "يحتاجك", "आपकी ज़रूरत"), '<div class="lst">' +
        ui.li('<span class="pill crit" style="width:32px;height:32px;padding:0;justify-content:center">' + ui.icon("flask", "sm") + "</span>", "Results inbox · 5 new", "1 critical: K+ 6.1 (Rakesh Menon) · 14 min to acknowledge", ui.icon("chevright", "sm faint flip"), { go: "doctor.results" }) +
        ui.li('<span class="pill info" style="width:32px;height:32px;padding:0;justify-content:center">' + ui.icon("share", "sm") + "</span>", "Record requests · 2", "Patients approved on WhatsApp · waiting for you", ui.icon("chevright", "sm faint flip"), { go: "doctor.records" }) +
        ui.li('<span class="pill sync" style="width:32px;height:32px;padding:0;justify-content:center">' + ui.icon("bed", "sm") + "</span>", "Ward round 13:30 · 4 patients", "Highest NEWS2 5 (ICU-2) · 1 discharge-ready", ui.icon("chevright", "sm faint flip"), { go: "doctor.inpatients" }) +
        ui.li('<span class="pill mute" style="width:32px;height:32px;padding:0;justify-content:center">' + ui.icon("calendar", "sm") + "</span>", "Leave 24 Oct (conference)", "11 bookings: 8 moved to Dr. Anil Kumar, 2 rescheduled, 1 pending", ui.icon("chevright", "sm faint flip"), { go: "doctor.schedule" }) + "</div>", { icon: "bell" , cls: "cl-lw" }) +
      ui.card(L("Signed in at", "مسجل الدخول في", "लॉग-इन सुविधा"), '<p class="small muted" style="margin:0 0 10px">One login across ' + fs.length + " facilities. Each facility sees only its own patients; the others see you as “busy”.</p>" +
        '<div class="lst">' + fs.slice(0, 2).map(function (x, i) { return ui.li('<span class="av">' + ui.icon("building", "sm") + "</span>", esc(x.name), esc(x.area), i === NB.state.facility ? ui.pill("acc", "Current") : ""); }).join("") + "</div>", { icon: "building", right: ui.btn("Switch", { cls: "sm ghost", ov: "facswitch" }) });
    return h + '<div class="split wide"><div class="stack s16">' + next + table + '</div><div class="stack s16">' + rail + "</div></div>";
  });

  NB.overlay("cldelay", function (arg) {
    var pick = NB.v("clDelayPick", arg != null ? arg : "30"), cur = +NB.v("clDelay", 15), n = +pick, aff = CD.day.filter(function (r) { return r.fac === 0 && /next|waiting|booked|arrived/.test(r.st); });
    var body = ui.note("info", "Patients see a new ETA range on WhatsApp, the app and the waiting-room display within 60 seconds. No clinical details are shared.") +
      '<div class="row"><span class="small faint">' + L("Running late by", "متأخر بمقدار", "देरी") + "</span>" + ui.seg("clDelayPick", [["0", "On time"], ["15", "15 min"], ["30", "30 min"], ["45", "45 min"], ["60", "60 min"]], String(pick)) + "</div>" +
      ui.table([{ h: "Token" }, { h: "Patient" }, { h: "ETA now" }, { h: "New ETA" }], aff.map(function (r) {
        var pp = r.p ? NB.pat(r.p) : null;
        return ['<span class="tok">' + r.tok + "</span>", pp ? pn(pp) : esc(r.name), '<span class="num ltr">' + hm(r.min + cur) + "</span>", '<b class="num ltr ' + (n > cur ? "warnc" : "okc") + '">' + hm(r.min + n) + "–" + hm(r.min + n + 10) + "</b>"];
      }), { min: 420 }) +
      '<div class="stack s8"><span class="eyebrow">WhatsApp preview</span><div class="cl-thread"><div class="cl-msg out">Hello Rahul, Dr. Vikram Shah is running about ' + n + " minutes late today. Your new expected time is " + hm(80 + n) + "–" + hm(90 + n) + ". You can wait, reschedule, or see another doctor.<small>Buttons: Wait · Reschedule · Another doctor</small></div></div></div>" +
      ui.toggle("clDlOpts", "Offer reschedule and another doctor", "Front desk sees each patient's choice", true) + ui.toggle("clDlPause", "Pause new same-day online bookings", "Resumes automatically when you are back on time", n >= 45);
    return { title: L("Running late", "التأخير", "देरी सूचना"), body: body, foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn("Notify " + aff.length + " patients", { cls: "pri", act: "cl-delay", arg: pick, icon: "send" }) };
  });
  NB.act("cl-delay", function (a) { NB.state.overlay = null; NB.setUI({ clDelay: +a, clDelayPick: undefined }); NB.toast(+a ? "Delay +" + a + " min sent · ETAs updated on WhatsApp, app and display" : "Back on time · patients updated"); });

  /* ================= Patients (doctor + solo) ================= */
  CD.mypts = {
    doctor: [["p3", "Today", "48 h (auto-booked)", "Outside records active"], ["p8", "Today", "2 weeks", "Caregiver: son"], ["p6", "Today", "6 weeks", ""], ["p10", "02 Oct", "Lipid review due", ""], ["p7", "14 Sep", "—", "Minor"], ["p9", "21 Aug", "Overdue 3 wk", ""]],
    solo: [["p2", "Today", "After delivery", "Pregnant"], ["p1", "Today", "4 weeks", ""], ["p5", "Today", "Session 5/6 · 31 Oct", "Package"], ["p9", "Today", "3 months", ""], ["p6", "18 Jul", "Recall due", ""], ["p4", "02 May", "—", "Minor"]]
  };
  function patientsScreen(c, role) {
    var list = CD.mypts[role], f = NB.v("clPtF_" + role, "all"), solo = role === "solo";
    var pred = { all: function () { return true; }, today: function (x) { return x[1] === "Today"; }, due: function (x) { return /due|Overdue|Recall/.test(x[2]); }, chronic: function (x) { return (NB.pat(x[0]).conditions || []).length > 0; } };
    var rows = list.filter(pred[f] || pred.all);
    var h = ui.header(solo ? L("Patients", "المرضى", "मरीज़") : L("My patients", "مرضاي", "मेरे मरीज़"), list.length + " " + L("patients · records stay with each facility", "مريض · السجلات تبقى في كل منشأة", "मरीज़ · रिकॉर्ड हर सुविधा में रहते हैं"),
      ui.seg("clPtF_" + role, [["all", L("All", "الكل", "सभी"), list.length], ["today", L("Today", "اليوم", "आज"), list.filter(pred.today).length], ["due", L("Follow-up due", "متابعة مستحقة", "फ़ॉलो-अप बाकी"), list.filter(pred.due).length], ["chronic", L("Chronic", "مزمن", "दीर्घकालिक")]]));
    h += '<label class="cl-inp" style="max-width:520px">' + ui.icon("search", "sm faint") + '<input data-cl-filter="cl-ptl-' + role + '" placeholder="' + esc(L("Filter by name, phone, condition or MRN", "تصفية بالاسم أو الهاتف", "नाम, फ़ोन या बीमारी से खोजें")) + '"></label>';
    var act = solo ? "cl-soloopen" : "cl-open";
    if (c.mobile || (solo && NB.device() === "tablet")) {
      return h + '<div class="stack s8" id="cl-ptl-' + role + '">' + rows.map(function (x) {
        var p = NB.pat(x[0]);
        return '<div class="cl-rowc" data-act="' + act + '" data-arg="' + x[0] + '" data-q="' + esc(p.name + " " + p.phone + " " + p.mrn + " " + (p.conditions || []).join(" ")) + '">' + ui.av(p.name, "lg") + '<div class="t"><b>' + pn(p) + "</b><small>" + p.age + " " + p.sex + " · " + esc((p.conditions || []).join(", ") || "No chronic conditions") + "</small><small>" + L("Last", "آخر", "पिछला") + " " + esc(x[1]) + " · " + L("Next", "التالي", "अगला") + " " + esc(x[2]) + "</small></div>" +
          ((p.allergies || []).length ? ui.pill("crit", p.allergies[0].a) : "") + "</div>";
      }).join("") + "</div>";
    }
    var tb = '<div class="tw"><table class="t" style="min-width:860px"><thead><tr><th>' + [L("Patient", "المريض", "मरीज़"), L("Conditions", "الحالات", "बीमारियाँ"), L("Allergies", "الحساسية", "एलर्जी"), L("Last visit", "آخر زيارة", "पिछली विज़िट"), L("Next / due", "التالي", "अगला"), L("Notes", "ملاحظات", "नोट्स"), ""].join("</th><th>") + '</th></tr></thead><tbody id="cl-ptl-' + role + '">' +
      rows.map(function (x) {
        var p = NB.pat(x[0]);
        return '<tr data-q="' + esc(p.name + " " + p.phone + " " + p.mrn + " " + (p.conditions || []).join(" ")) + '"><td>' + '<div class="row nw">' + ui.av(p.name) + ui.cellp(pn(p), p.age + " " + p.sex + " · " + esc(p.mrn)) + "</div></td><td>" + esc((p.conditions || []).join(", ") || "—") + "</td><td>" +
          ((p.allergies || []).map(function (a) { return ui.pill(a.sev === "Severe" ? "crit" : "warn", a.a); }).join(" ") || '<span class="faint small">NKDA</span>') + "</td><td>" + esc(x[1]) + "</td><td>" + (/Overdue|due/.test(x[2]) ? ui.pill("warn", x[2]) : esc(x[2])) + "</td><td>" +
          (x[3] ? ui.pill(/Outside/.test(x[3]) ? "info" : "mute", x[3], /Outside/.test(x[3]) ? "share" : null) : "") + (isPreg(p) ? " " + ui.pill("urg", "Pregnant", "baby") : "") + "</td><td>" + ui.btn(L("Open chart", "فتح الملف", "चार्ट खोलें"), { cls: "sm", act: act, arg: x[0], icon: "steth" }) + "</td></tr>";
      }).join("") + "</tbody></table></div>";
    return h + tb + ui.note("info", "Opening a chart from another facility needs the patient's consent and the treating doctor's approval. Use <b>Record requests</b> to ask for outside records.");
  }
  NB.screen("doctor.patients", function (c) { return patientsScreen(c, "doctor"); });
  NB.screen("solo.patients", function (c) { return patientsScreen(c, "solo"); });

  /* ================= Doctor: Results inbox ================= */
  NB.screen("doctor.results", function (c) {
    var ack = NB.v("clAck", {}), f = NB.v("clResF", "new"), sel = NB.v("clRes", "R1");
    var pred = { new: function (r) { return !ack[r.id]; }, crit: function (r) { return r.flag === "crit"; }, all: function () { return true; } };
    var list = CD.results.filter(pred[f] || pred.all), r = CD.results.find(function (x) { return x.id === sel; }) || CD.results[0], p = NB.pat(r.p);
    var h = ui.header(L("Results inbox", "صندوق النتائج", "रिपोर्ट इनबॉक्स"), CD.results.filter(pred.new).length + " to review · critical values must be acknowledged within 30 min (escalates to the department head)",
      ui.seg("clResF", [["new", L("To review", "للمراجعة", "समीक्षा बाकी"), CD.results.filter(pred.new).length], ["crit", L("Critical", "حرج", "गंभीर"), CD.results.filter(pred.crit).length], ["all", L("All", "الكل", "सभी"), CD.results.length]], "new"));
    var left = '<div class="stack s8">' + (list.length ? list.map(function (x) {
      var pp = NB.pat(x.p);
      return '<div class="cl-rowc ' + (x.flag === "crit" ? "crit " : "") + (x.id === r.id ? "on" : "") + '" data-set="clRes" data-val="' + x.id + '">' + ui.av(pp.name) + '<div class="t"><b>' + pn(pp) + "</b><small>" + esc(x.test) + " · " + esc(x.key) + "</small><small>" + esc(x.at) + "</small></div>" +
        '<div class="stack s4" style="align-items:flex-end">' + flagPill(x.flag) + (ack[x.id] ? ui.pill("ok", "Reviewed") : "") + "</div></div>";
    }).join("") : '<div class="empty">Nothing to review. Nice.</div>') + "</div>";
    var done = ack[r.id];
    var detail = '<div class="card ' + (r.flag === "crit" && !done ? "crit" : "") + '"><div class="ch"><span class="eyebrow">' + ui.icon("flask", "sm") + esc(r.test) + "</span>" + flagPill(r.flag) + "</div>" +
      '<div class="row" style="margin-bottom:12px">' + ui.av(p.name, "lg") + '<div class="fl"><h2 class="h">' + pn(p) + '</h2><span class="small muted">' + p.age + " " + p.sex + " · " + esc(p.mrn) + " · " + esc(r.lab) + " · " + esc(r.at) + "</span></div>" + ui.btn(L("Open chart", "فتح الملف", "चार्ट खोलें"), { cls: "sm", act: "cl-open", arg: r.p, icon: "steth" }) + "</div>" +
      (r.flag === "crit" && !done ? ui.note("crit", "<b>CRITICAL VALUE.</b> " + esc(r.note) + ". " + esc(r.sla) + ".") : "") + (done ? ui.note("ok", "Reviewed by you at " + done + (r.flag === "crit" ? " · critical-value acknowledgement logged with read-back" : "")) : "") +
      '<div style="margin-top:12px">' + ui.table([{ h: "Test" }, { h: "Result", cls: "r" }, { h: "Unit" }, { h: "Reference" }, { h: "Flag" }], r.rows.map(function (x) {
        return ["<b>" + esc(x[0]) + "</b>", '<b class="num ' + (x[4] === "crit" ? "critc" : x[4] === "warn" ? "warnc" : "") + '">' + esc(x[1]) + "</b>", esc(x[2]), esc(x[3]), x[4] ? flagPill(x[4]) : ui.pill("ok", "Normal")];
      }), { min: 480 }) + "</div>" +
      '<div class="row" style="margin-top:14px;gap:16px">' + spark(r.tr, 160, 48, r.flag === "crit") + '<div class="small muted">' + esc(r.trl) + ': <span class="num ltr">' + r.tr.join(" → ") + "</span></div></div>" +
      '<div class="btns" style="margin-top:16px">' + (done ? "" : ui.btn(r.flag === "crit" ? L("Acknowledge critical", "إقرار النتيجة الحرجة", "गंभीर रिपोर्ट स्वीकारें") : L("Mark reviewed", "تمت المراجعة", "समीक्षा पूर्ण"), { cls: "pri", act: "cl-ack", arg: r.id, icon: "check" })) +
      ui.btn(L("Message patient", "مراسلة المريض", "मरीज़ को संदेश"), { ov: "clmsg", arg: r.id, icon: "chat" }) + ui.btn(L("Order repeat", "إعادة الطلب", "दोबारा टेस्ट"), { act: "toast", arg: "Repeat " + r.test + " ordered · sample at the collection desk", icon: "refresh" }) +
      ui.btn(L("Add to next visit", "أضف للزيارة القادمة", "अगली विज़िट में जोड़ें"), { cls: "ghost", act: "toast", arg: "Added to the agenda of the next visit", icon: "calendar" }) + "</div></div>";
    var aiNote = r.flag !== "ok" ? '<div class="card cl-ai"><div class="ch"><span class="eyebrow">' + ui.icon("sparkle", "sm") + L("AI context", "سياق ذكي", "AI संदर्भ") + "</span>" + ui.pill("sub", "Suggestion — doctor decides") + "</div>" +
      '<p class="small" style="margin:0">' + (r.id === "R1" ? "Potassium rose from 4.6 to 6.1 over 6 months while on telmisartan 40 mg with eGFR falling 52 → 40. Consider holding the ARB, repeating K+ (non-haemolysed) and an ECG today." : r.id === "R4" ? "TSH 7.8 with normal free T4 on levothyroxine 50 mcg: subclinical under-replacement. Check adherence and timing (empty stomach, 30 min before tea)." : r.id === "R3" ? "eGFR dropped 44 → 34 in 12 months (> 5/year: rapid decline). Nephrology referral criteria met (KDIGO)." : "Trend is rising across the last 3 results.") +
      '</p><div class="cl-src" style="margin-top:8px">' + ui.icon("file") + "KDIGO 2024 CKD · ATA hypothyroidism guideline · your clinic's critical-value policy</div></div>" : "";
    return h + '<div class="split l">' + left + '<div class="stack s16">' + detail + aiNote + "</div></div>";
  });
  NB.act("cl-ack", function (id) { var a = Object.assign({}, NB.v("clAck", {})); a[id] = "10:1" + Object.keys(a).length; var nx = CD.results.find(function (x) { return !a[x.id]; }); NB.setUI({ clAck: a, clRes: nx ? nx.id : id }); NB.toast("Acknowledged · logged in the result audit trail"); });
  NB.overlay("clmsg", function (id) {
    var r = CD.results.find(function (x) { return x.id === id; }) || CD.results[0], p = NB.pat(r.p), lang = p.lang === "hi" ? "hi" : p.lang === "ar" || NB.gcc() ? "ar" : "en";
    var msgs = { R1: ["Your kidney test shows a high potassium level. Please do not take Telmisartan from today. Come back to the clinic before 12:00 for a repeat test and ECG. Avoid bananas, coconut water and salt substitutes today.", "आपकी किडनी जाँच में पोटैशियम ज़्यादा है। आज से टेल्मिसर्टन न लें। दोबारा जाँच और ईसीजी के लिए 12 बजे से पहले क्लिनिक आएँ।", "أظهر فحص الكلى ارتفاع البوتاسيوم. لا تتناول تلميسارتان من اليوم، وعد إلى العيادة قبل 12:00 لإعادة الفحص وتخطيط القلب."],
      def: ["Your " + r.test.toLowerCase() + " result is ready and Dr. Vikram Shah has reviewed it. " + (r.flag === "ok" ? "Everything is within the normal range." : "Some values need attention; please book a follow-up this week."), "आपकी रिपोर्ट तैयार है और डॉक्टर ने देख ली है। " + (r.flag === "ok" ? "सब सामान्य है।" : "कृपया इस हफ़्ते फ़ॉलो-अप बुक करें।"), "نتيجتك جاهزة وقد راجعها الطبيب. " + (r.flag === "ok" ? "كل القيم طبيعية." : "يرجى حجز متابعة هذا الأسبوع.")] };
    var m = msgs[id] || msgs.def, txt = lang === "hi" ? m[1] : lang === "ar" ? m[2] : m[0];
    return { title: L("Message patient", "مراسلة المريض", "मरीज़ को संदेश") + " · " + pn(p), body:
      ui.note("info", "The WhatsApp notification says only “A message from your doctor is ready”. The text opens behind a secure link (OTP), so no health details sit in the chat preview.") +
      '<div class="row">' + ui.seg("clMsgCh", [["wa", "WhatsApp"], ["app", "Nabd app"], ["sms", "SMS link"]], "wa") + "</div>" +
      ui.field("Message (" + (lang === "hi" ? "Hindi" : lang === "ar" ? "Arabic" : "English") + ", patient's language)", txt, { area: true, full: true }) +
      (lang !== "en" ? '<p class="small faint" style="margin:0">English: ' + esc(m[0]) + "</p>" : "") +
      ui.toggle("clMsgShare", "Attach the report PDF", "Watermarked with the patient's name", true) + ui.toggle("clMsgCare", "Copy caregiver", p.id === "p8" ? "Arif Ali (son, Dubai) has caregiver access" : "No caregiver linked", p.id === "p8"),
      foot: ui.btn("Cancel", { act: "close", cls: "ghost" }) + ui.btn(L("Send", "إرسال", "भेजें"), { cls: "pri", act: "toast", arg: "Message sent to " + p.name + " · delivery receipt in the chart", icon: "send" }) };
  });

  /* ================= Doctor: Ward rounds ================= */
  CD.round = {
    "ICU-2": { vit: "T 37.8 · HR 98 · RR 21 · SpO₂ 96% (2 L) · BP 112/70", plan: "Day 6 meropenem; blood culture: E. coli (sensitive). Lactate 1.8 ↓. De-escalate to ceftriaxone?", todo: ["Repeat lactate", "Review central line day 6"], np: [["Resp. rate", "21", 2], ["SpO₂ (scale 1)", "96% on O₂", 2], ["Systolic BP", "112", 0], ["Pulse", "98", 1], ["Temp", "37.8", 0], ["Consciousness", "Alert", 0]] },
    "A-01": { vit: "T 37.9 · HR 96 · RR 20 · SpO₂ 94% (2 L) · BP 128/80", plan: "Day 3 IV ceftriaxone + azithromycin; afebrile 18 h. Switch to oral if afebrile 24 h.", todo: ["Wean O₂ to air", "Repeat CRP"], np: [["Resp. rate", "20", 0], ["SpO₂ (scale 1)", "94% on O₂", 3], ["Systolic BP", "128", 0], ["Pulse", "96", 1], ["Temp", "37.9", 0], ["Consciousness", "Alert", 0]] },
    "A-06": { vit: "T 37.1 · HR 92 · RR 21 · SpO₂ 98% · BP 122/76 · CBG 168", plan: "DKA resolved (pH 7.36, ketones 0.4). On basal-bolus insulin. Diabetes education today.", todo: ["Dietitian", "Insulin pen teaching"], np: [["Resp. rate", "21", 2], ["SpO₂", "98%", 0], ["Systolic BP", "122", 0], ["Pulse", "92", 1], ["Temp", "37.1", 0], ["Consciousness", "Alert", 0]] },
    "A-05": { vit: "T 36.8 · HR 92 · RR 16 · SpO₂ 98% · BP 108/70", plan: "Cellulitis improving, margin receding. Oral cephalexin to complete 7 days. Discharge today.", todo: ["Discharge summary", "Follow-up OPD in 5 days"], np: [["Resp. rate", "16", 0], ["SpO₂", "98%", 0], ["Systolic BP", "108", 1], ["Pulse", "92", 1], ["Temp", "36.8", 0], ["Consciousness", "Alert", 0]] }
  };
  function myBeds(all) {
    var out = [];
    NB.D.beds.forEach(function (w) { w.beds.forEach(function (b) { if (b.st === "occ" && (all || b.doc === "Dr. Vikram Shah")) out.push(Object.assign({ ward: w.ward }, b)); }); });
    return out.sort(function (a, b) { return b.news - a.news; });
  }
  NB.screen("doctor.inpatients", function (c) {
    var all = NB.v("clRndAll", "mine") === "all", beds = myBeds(all), seen = NB.v("clSeen", {}), mine = myBeds(false);
    var nSeen = mine.filter(function (b) { return seen[b.no]; }).length;
    var h = ui.header(L("Ward rounds", "جولات الأجنحة", "वार्ड राउंड"), esc(facShort(1)) + " · sorted sickest first (NEWS2) · " + nSeen + " of " + mine.length + " seen",
      ui.seg("clRndAll", [["mine", L("My patients", "مرضاي", "मेरे मरीज़"), mine.length], ["all", L("All wards", "كل الأجنحة", "सभी वार्ड"), myBeds(true).length]], "mine"));
    h += '<div class="card"><div class="row sb"><span class="small muted">' + L("Round progress", "تقدم الجولة", "राउंड प्रगति") + '</span><span class="small num">' + nSeen + "/" + mine.length + '</span></div><div style="margin-top:8px">' + ui.bar(mine.length ? nSeen / mine.length * 100 : 0, "ok") + "</div>" +
      '<div class="chips" style="margin-top:10px">' + ui.pill("ok", "NEWS2 0–2") + ui.pill("warn", "3–4 · ward review") + ui.pill("urg", "5–6 · urgent review") + ui.pill("crit", "≥ 7 · emergency") + "</div></div>";
    var cards = beds.map(function (b) {
      var r = CD.round[b.no], k = newsKind(b.news), mineB = b.doc === "Dr. Vikram Shah";
      return '<div class="card ' + (b.news >= 5 ? "crit" : "") + '"><div class="row nw" style="align-items:flex-start"><span class="cl-news ' + k + '">' + b.news + "<small>NEWS2</small></span>" +
        '<div class="fl"><div class="row"><b>' + esc(b.p) + '</b><span class="small faint">' + b.age + " · " + esc(b.no) + " · " + esc(b.ward) + "</span></div><div class=\"small muted\">" + esc(b.dx) + " · day " + b.los + (mineB ? "" : " · " + esc(b.doc)) + "</div></div>" +
        '<div class="stack s4" style="align-items:flex-end">' + (seen[b.no] ? ui.pill("ok", "Seen") : ui.pill("mute", "To see")) + (b.dis ? ui.pill("info", "Discharge-ready", "logout") : "") + "</div></div>" +
        (r ? '<dl class="cl-kv" style="margin-top:12px"><dt>Last obs</dt><dd class="ltr" style="text-align:start">' + esc(r.vit) + "</dd><dt>Plan</dt><dd>" + esc(r.plan) + "</dd><dt>To do</dt><dd>" + r.todo.map(esc).join(" · ") + "</dd></dl>" : "") +
        (mineB ? '<div class="btns" style="margin-top:12px">' + ui.btn(L("Round note", "ملاحظة الجولة", "राउंड नोट"), { cls: "sm" + (seen[b.no] ? "" : " pri"), ov: "roundnote", arg: b.no, icon: "edit" }) +
          ui.btn(L("Orders", "الطلبات", "ऑर्डर"), { cls: "sm", act: "toast", arg: "Orders for " + b.p + " sent to the ward nurse's task list", icon: "clipboard" }) +
          (b.dis ? ui.btn(L("Discharge summary", "ملخص الخروج", "डिस्चार्ज सारांश"), { cls: "sm okb", go: "ward.discharge", icon: "logout" }) : "") + "</div>" : "") + "</div>";
    }).join("");
    return h + '<div class="grid g2">' + cards + "</div>";
  });
  NB.overlay("roundnote", function (no) {
    var b = myBeds(true).find(function (x) { return x.no === no; }) || {}, r = CD.round[no] || { np: [], plan: "", todo: [], vit: "" };
    return { size: "lg", title: L("Round note", "ملاحظة الجولة", "राउंड नोट") + " · " + esc(b.p || "") + " · " + esc(no), body:
      '<div class="row"><span class="cl-news ' + newsKind(b.news) + '">' + b.news + "<small>NEWS2</small></span><div class=\"fl\"><b>" + esc(b.dx) + '</b><div class="small muted">' + esc(b.ward) + " · day " + b.los + " · " + esc(r.vit) + "</div></div></div>" +
      ui.table([{ h: "NEWS2 parameter" }, { h: "Value" }, { h: "Score", cls: "r" }], r.np.map(function (x) { return [esc(x[0]), '<span class="ltr">' + esc(x[1]) + "</span>", '<b class="num ' + (x[2] >= 3 ? "critc" : x[2] ? "warnc" : "") + '">' + x[2] + "</b>"]; }), { min: 360 }) +
      '<div class="fgrid">' + ui.field("Progress (S/O)", "Feels better, ate breakfast. " + r.vit, { area: true, full: true }) + ui.field("Assessment & plan", r.plan, { area: true, full: true }) +
      ui.field("Next review", b.news >= 5 ? "In 1 hour (urgent response)" : "Evening round", { options: ["In 1 hour (urgent response)", "In 4 hours", "Evening round", "Tomorrow"] }) + ui.field("Escalation", b.news >= 5 ? "Critical care outreach informed" : "None", { options: ["None", "Critical care outreach informed", "Senior review requested"] }) + "</div>" +
      '<div class="cl-cr"><span class="cl-lb">Quick orders</span>' + r.todo.concat(["CBC", "CRP", "Blood culture", "Chest X-ray"]).map(function (t) { return chip(ui.icon("plus", "xs") + esc(t), false, 'data-act="toast" data-arg="' + esc(t) + ' ordered"'); }).join("") + "</div>" +
      ui.toggle("clDis_" + no, "Discharge-ready", "Starts the discharge checklist for the ward nurse and billing", !!b.dis),
      foot: ui.btn("Cancel", { act: "close", cls: "ghost" }) + ui.btn(L("Save & mark seen", "حفظ ووضع كمُراجَع", "सहेजें और देखा गया"), { cls: "pri", act: "cl-seen", arg: no, icon: "check" }) };
  });
  NB.act("cl-seen", function (no) { var s = Object.assign({}, NB.v("clSeen", {})); s[no] = true; NB.state.overlay = null; NB.setUI({ clSeen: s }); NB.toast("Round note saved · " + no + " marked seen"); });

  /* ================= Doctor: Rx favourites & templates ================= */
  NB.screen("doctor.rx", function (c) {
    var tab = NB.v("clRxTab", "sets"), spec = NB.v("clRxSpec", "General Medicine");
    var h = ui.header(L("Rx favourites & templates", "الوصفات المفضلة والقوالب", "पसंदीदा नुस्खे और टेम्पलेट"), "Yours, portable across every facility you work at · target: repeat Rx under 30 s", ui.btn(L("New set from last Rx", "مجموعة جديدة من آخر وصفة", "पिछले Rx से नया सेट"), { cls: "pri", act: "toast", arg: "Draft set created from your last prescription", icon: "plus" }));
    h += ui.tabs("clRxTab", [["sets", L("Favourite sets", "المجموعات المفضلة", "पसंदीदा सेट"), "star"], ["tpl", L("Note templates", "قوالب الملاحظات", "नोट टेम्पलेट"), "edit"], ["phr", L("Smart phrases", "عبارات ذكية", "स्मार्ट वाक्यांश"), "bolt"], ["def", L("Drug defaults & validity", "إعدادات الأدوية", "दवा डिफ़ॉल्ट"), "sliders"]], "sets");
    if (tab === "sets") {
      h += '<div class="row">' + ui.seg("clRxSpec", Object.keys(CD.favs).map(function (k) { return [k, k, CD.favs[k].length]; }), "General Medicine") + '<span class="small faint">' + L("Department sets are shared; yours stay private unless you share them.", "مجموعات القسم مشتركة", "विभाग के सेट साझा होते हैं") + "</span></div>";
      h += '<div class="grid g3">' + CD.favs[spec].map(function (f) {
        return '<div class="card"><div class="row nw" style="align-items:flex-start"><span class="acc">' + ui.icon("star") + '</span><div class="fl"><h3 class="h">' + esc(f.name) + '</h3><span class="small faint">' + esc(f.note) + "</span></div></div>" +
          '<ol class="small" style="margin:12px 0;padding-inline-start:18px;display:flex;flex-direction:column;gap:6px">' + f.lines.map(function (x) { var l = { d: x[0], q: x[1], f: x[2], n: x[3], food: x[4] }; return "<li><b style=\"font-weight:500\">" + esc(drug(x[0]).name) + '</b><br><span class="faint">' + esc(sig(l, "en")) + "</span></li>"; }).join("") + "</ol>" +
          '<div class="chips" style="margin-bottom:12px">' + ui.pill("mute", "Used " + f.used + "×") + ui.pill("ok", "Median " + f.sec + " s to sign", "hourglass") + ui.pill("acc", "Portable", "globe") + "</div>" +
          '<div class="btns">' + ui.btn("Edit", { cls: "sm", act: "toast", arg: "Editing " + f.name, icon: "edit" }) + ui.btn("Share", { cls: "sm ghost", act: "toast", arg: f.name + " shared with " + spec, icon: "share" }) + "</div></div>";
      }).join("") + "</div>";
      h += ui.note("ai", "Sets never skip safety checks: every line is re-checked against the patient's allergies, current medicines, pregnancy and eGFR when you apply it.");
    } else if (tab === "tpl") {
      var tpls = [["Fever adult", "S: onset, max temp, chills, rash, bleeding, travel · O: vitals, throat, chest, abdomen · A/P: dengue/typhoid/malaria screen by day of fever", "General Medicine", 312], ["Diabetes review", "HbA1c trend, hypos, foot exam (monofilament), eye check date, eGFR/UACR, statin, BP target", "General Medicine", 184], ["HTN review", "Home BP log, adherence, salt, side-effects, target < 130/80, K+/creatinine on ACEi/ARB", "General Medicine", 160], ["URTI", "Centor score, red flags, antibiotic stewardship statement", "General Medicine", 141], ["Skin lesion", "Morphology, distribution, photos with consent, dermoscopy, Fitzpatrick type", "Dermatology", 96]];
      h += ui.table([{ h: "Template" }, { h: "Sections & prompts" }, { h: "Specialty" }, { h: "Used", cls: "r" }, { h: "" }], tpls.map(function (t) { return ["<b>" + esc(t[0]) + "</b>", '<span class="small muted cl-wrap">' + esc(t[1]) + "</span>", esc(t[2]), '<span class="num">' + t[3] + "</span>", ui.btn("Edit", { cls: "sm", act: "toast", arg: "Editing template " + t[0] })]; }), { min: 760 });
    } else if (tab === "phr") {
      var ph = [[".fev", "Fever for __ days, max __ °C, no rash, no bleeding, no altered sensorium."], [".cough", "Cough __ days, productive/dry, no haemoptysis, no weight loss."], [".dm", "Diabetes: no hypos, feet normal, eye exam due __."], [".htn", "Home BP __/__, adherent, no headache or chest pain."], [".nad", "General examination: no pallor, icterus, cyanosis, clubbing, lymphadenopathy or oedema."], [".sick", "Sick-day rules explained: pause metformin and SGLT2i if vomiting or not eating; keep fluids up."]];
      h += ui.table([{ h: "Type" }, { h: "Expands to" }, { h: "" }], ph.map(function (x) { return ['<code class="ltr acc">' + x[0] + "</code>", '<span class="cl-wrap small">' + esc(x[1]) + "</span>", ui.btn("Edit", { cls: "sm ghost", act: "toast", arg: "Editing " + x[0] })]; }), { min: 560 });
    } else {
      var df = [["Paracetamol 650 mg", "1 tab SOS, max 4 g/day (2 g if liver disease or < 50 kg)", "5 days", "7 days"], ["Azithromycin 500 mg", "1 tab once daily", "3 days", "7 days"], ["Metformin 500 mg", "1 tab twice daily after food; blocked if eGFR < 30", "90 days", "90 days"], ["Alprazolam 0.25 mg (H1)", "1 tab at night", "7 days", "Single dispense · H1 register"], ["Isotretinoin 20 mg (H1)", "Requires negative pregnancy test within 7 days", "30 days", "Single dispense"]];
      h += ui.table([{ h: "Drug" }, { h: "Default sig & guard rail" }, { h: "Default duration" }, { h: "Rx validity" }], df.map(function (x) { return ["<b>" + esc(x[0]) + "</b>", '<span class="small cl-wrap">' + esc(x[1]) + "</span>", esc(x[2]), esc(x[3])]; }), { min: 720 }) +
        ui.note("info", "Validity rules come from the owner's settings and the market: " + esc(NB.mkt().eRx) + " · " + esc(NB.mkt().regulator) + ". Narcotic and Schedule X drugs need a paper or special-format prescription and cannot be sent as a standard e-Rx.");
    }
    return h;
  });

  /* ================= Doctor: Schedule & leave ================= */
  NB.screen("doctor.schedule", function (c) {
    var days = [["Mon", [[0, "09:00–13:00", "OPD"], [1, "17:00–20:00", "OPD"]]], ["Tue", [[0, "09:00–13:00", "OPD"], [1, "13:30–14:30", "Ward round"], ["busy", "15:00–18:00", "Busy (other facility)"]]], ["Wed", [[0, "09:00–13:00", "OPD"], [1, "17:00–20:00", "OPD"]]],
      ["Thu", [[0, "09:00–13:00", "OPD"], ["tele", "14:00–15:00", "Teleconsult"]]], ["Fri", [[0, "09:00–13:00", "OPD"], [1, "17:00–20:00", "OPD"]]], ["Sat", [[0, "09:00–13:00", "OPD"], [1, "13:30–14:30", "Ward round"], [1, "17:00–20:00", "OPD"]]], ["Sun", [["leave", "All day", "Off"]]]];
    var h = ui.header(L("My schedule & leave", "جدولي والإجازات", "मेरा शेड्यूल और छुट्टी"), "Working hours at every facility in one calendar · each facility sees only its own slots, others see “busy”",
      ui.btn(L("Apply for leave", "طلب إجازة", "छुट्टी का आवेदन"), { ov: "clleave", icon: "calendar" }) + ui.btn(L("Add hours", "إضافة ساعات", "समय जोड़ें"), { cls: "pri", act: "toast", arg: "New session added as draft · publish to open bookings", icon: "plus" }));
    var wk = days.map(function (d) {
      return '<div class="cl-day"><b>' + d[0] + '</b><div class="cl-blks">' + d[1].map(function (b) {
        var cls = b[0] === 1 ? "f1" : b[0] === "busy" ? "busy" : b[0] === "leave" ? "leave" : b[0] === "tele" ? "" : "";
        return '<div class="cl-blk ' + cls + '"><b class="ltr">' + b[1] + "</b><small>" + esc(b[2]) + (typeof b[0] === "number" ? " · " + esc(facShort(b[0])) : "") + "</small></div>";
      }).join("") + "</div></div>";
    }).join("");
    var legend = '<div class="chips" style="margin-bottom:8px"><span class="cl-blk"><small>' + esc(facShort(0)) + '</small></span><span class="cl-blk f1"><small>' + esc(facShort(1)) + '</small></span><span class="cl-blk busy"><small>Busy (details hidden)</small></span><span class="cl-blk leave"><small>Leave / off</small></span></div>';
    var rules = [["New consultation", "10 min", "20", "Yes", "Up to cap", NB.money(800)], ["Follow-up (within 7 days)", "6 min", "12", "Yes", "Yes", NB.money(0)], ["Walk-in", "8 min", "30% of slots held", "—", "Desk", NB.money(800)], ["Teleconsult", "10 min", "6", "Yes", "Review each", NB.money(600)], ["Insurance (cashless)", "12 min", "8", "Yes", "Review each", "As per TPA"], ["Procedure (ECG, dressing)", "15 min", "4", "No", "Desk", NB.money(500)]];
    var slot = NB.v("clSlot", "8");
    return h + '<div class="split wide"><div class="stack s16">' + ui.card(L("Weekly hours", "الساعات الأسبوعية", "साप्ताहिक समय"), legend + wk, { icon: "calendar" }) +
      ui.card(L("Visit types & booking rules", "أنواع الزيارات وقواعد الحجز", "विज़िट प्रकार और बुकिंग नियम"), ui.table([{ h: "Visit type" }, { h: "Slot" }, { h: "Max / session" }, { h: "Online" }, { h: "Auto-accept" }, { h: "Fee", cls: "r" }], rules.map(function (r) { return ["<b>" + esc(r[0]) + "</b>", esc(r[1]), esc(r[2]), esc(r[3]), esc(r[4]), r[5]]; }), { min: 640 }) +
        '<div class="row" style="margin-top:14px"><span class="small faint">Default slot length</span>' + ui.seg("clSlot", [["6", "6 min"], ["8", "8 min"], ["10", "10 min"], ["12", "12 min"], ["15", "15 min"]], "8") + '<span class="small faint">→ ' + Math.floor(240 / +slot) + " slots in a 4-hour session</span></div>", { icon: "sliders" }) + "</div>" +
      '<div class="stack s16">' + ui.card(L("Leave & cover", "الإجازات والتغطية", "छुट्टी और कवर"), '<div class="lst">' +
        ui.li('<span class="pill warn" style="width:32px;height:32px;padding:0;justify-content:center">' + ui.icon("calendar", "sm") + "</span>", "Sat 24 Oct · Conference (RSSDI)", "Cover: Dr. Anil Kumar · 11 bookings: 8 moved, 2 rescheduled, 1 pending", ui.pill("ok", "Approved")) +
        ui.li('<span class="pill mute" style="width:32px;height:32px;padding:0;justify-content:center">' + ui.icon("calendar", "sm") + "</span>", "23–27 Dec · Annual leave", "Bookings closed for these dates · no patients affected", ui.pill("warn", "Pending owner")) + "</div>", { icon: "logout" , cls: "cl-lw" }) +
        ui.card(L("Rules", "القواعد", "नियम"), ui.toggle("clR1", "Stop online booking when I am > 30 min late", "Reopens when back on time", true) + ui.toggle("clR2", "Hold 2 emergency slots per session", "Released to walk-ins 30 min before the end", true) +
          ui.toggle("clR3", "Other facilities see only “busy”", "Never which hospital or patient", true) + ui.toggle("clR4", "Approval-record requests can be delegated during leave", "To Dr. Anil Kumar, per facility delegation rules", true), { icon: "shieldcheck" }) + "</div></div>";
  });
  NB.overlay("clleave", function () {
    return { kind: "modal", title: L("Apply for leave", "طلب إجازة", "छुट्टी का आवेदन"), body: '<div class="fgrid">' + ui.field("From", "31 Oct 2026") + ui.field("To", "31 Oct 2026") +
      ui.field("Type", "Casual leave", { options: ["Casual leave", "Conference / CME", "Sick leave", "Annual leave"] }) + ui.field("Cover doctor", "Dr. Anil Kumar (General Medicine)", { options: ["Dr. Anil Kumar (General Medicine)", "No cover — reschedule all"] }) + "</div>" +
      ui.note("warn", "<b>14 booked patients</b> on these dates. They get a WhatsApp message with the cover doctor or the next 3 free slots; the front desk sees who chose what.") +
      ui.toggle("clLvDel", "Delegate record-request approvals to the cover doctor", "Time-boxed to the leave dates, audited", true),
      foot: ui.btn("Cancel", { act: "close", cls: "ghost" }) + ui.btn("Send for approval", { cls: "pri", act: "toast", arg: "Leave sent to the owner for approval · patients notified after approval", icon: "send" }) };
  });

  /* ================= Doctor: Record requests (consent) ================= */
  var CST = { "pending-doctor": ["warn", "Waiting for source doctor"], active: ["ok", "Active"], expired: ["perm", "Expired"], denied: ["crit", "Declined by patient"] };
  NB.screen("doctor.records", function (c) {
    var dec = NB.v("clCons", {}), m = NB.mkt();
    var h = ui.header(L("Record requests", "طلبات السجلات", "रिकॉर्ड अनुरोध"), "Sharing between facilities needs the patient and the treating doctor · always time-limited · every view is logged and shown to the patient",
      ui.btn(L("Request outside records", "طلب سجلات خارجية", "बाहरी रिकॉर्ड माँगें"), { cls: "pri", act: "toast", arg: "Search the patient's global profile: facilities with records are listed (metadata only)", icon: "share" }));
    h += ui.note("info", "<b>How it works.</b> The patient approved first (on WhatsApp with an OTP, in the app, or at the desk). Now you, as the treating doctor at the facility that holds the records, approve, narrow the scope, or decline. You can never widen what the patient approved. If you don't answer, the request escalates to the department head after 4 working hours (30 min if urgent)." + (m.id === "india" ? " Requests through ABDM follow the same step, within the HIP response time." : " Requests through " + esc(m.hie) + " follow the same step."));
    h += '<h2 class="h">' + L("Waiting for you", "بانتظارك", "आपके इंतज़ार में") + " · " + CD.inc.filter(function (x) { return !dec[x.id]; }).length + "</h2>";
    h += '<div class="grid g2">' + CD.inc.map(function (x) {
      var p = NB.pat(x.p), d = dec[x.id];
      return '<div class="card ' + (d ? "" : "hl") + '"><div class="row nw" style="align-items:flex-start">' + ui.av(p.name, "lg") + '<div class="fl"><div class="row"><b>' + pn(p) + '</b><span class="small faint">' + p.age + " " + p.sex + " · " + esc(p.mrn) + "</span></div>" +
        '<div class="small muted">' + esc(x.by) + " · " + esc(x.spec) + " · " + esc(ext(x)) + "</div></div>" + (d ? ui.pill(d.s === "ok" ? "ok" : "crit", d.s === "ok" ? "Approved · " + d.v : "Declined") : ui.pill("warn", x.age + " ago")) + "</div>" +
        '<dl class="cl-kv" style="margin-top:12px"><dt>Purpose</dt><dd>' + esc(x.purpose) + "</dd><dt>Data</dt><dd>" + x.data.map(esc).join(", ") + "</dd><dt>Range</dt><dd>" + esc(x.range) + "</dd><dt>Requested for</dt><dd>" + esc(x.valid) + "</dd><dt>Patient</dt><dd>" + ui.pill("ok", "Approved") + " " + esc(x.pt) + "</dd></dl>" +
        '<div class="cl-steps" style="margin-top:12px"><span class="cl-step ok"><i>✓</i>Requested</span><span class="cl-step ok"><i>✓</i>Patient approved</span><span class="cl-step ' + (d ? "ok" : "now") + '"><i>' + (d ? "✓" : "3") + "</i>" + (d ? "You decided" : "You") + '</span><span class="cl-step ' + (d && d.s === "ok" ? "ok" : "") + '"><i>4</i>Active</span><span class="cl-step"><i>5</i>Auto-revoke</span></div>' +
        (d ? "" : '<p class="tiny faint" style="margin:8px 0 0">' + ui.icon("clockh", "xs") + " " + esc(x.sla) + "</p>" + '<div class="btns" style="margin-top:12px">' + ui.btn(L("Review & approve", "مراجعة وموافقة", "देखें और स्वीकृत करें"), { cls: "pri", ov: "consentdecide", arg: x.id, icon: "shieldcheck" }) + ui.btn(L("Decline", "رفض", "अस्वीकार"), { cls: "dng", ov: "consentdecide", arg: x.id + "|deny" }) + "</div>") + "</div>";
    }).join("") + "</div>";
    var row = function (x) {
      var p = NB.pat(x.p), s = CST[x.st] || ["mute", x.st], mine = x.by === "Dr. Vikram Shah";
      return [esc(x.id), ui.cellp(pn(p), p.age + " " + p.sex + " · " + (mine ? "held by " + esc(x.from) : "to " + esc(x.to))), ui.cellp(esc(x.purpose), esc(x.data.join(", ") + " · " + x.range)),
        ui.pill(s[0], s[1]) + '<small class="faint cl-wrap" style="display:block;margin-top:4px;max-width:280px">' + (x.st === "active" ? "until " + esc(x.until) + " · " + x.reads + "/5 views · " : "") + esc(x.pt) + (x.dr !== "—" ? " · " + esc(x.dr) : "") + "</small>",
        x.st === "active" ? ui.btn("View records", { cls: "sm", act: "toast", arg: "Read-only bundle opened · view " + (x.reads + 1) + "/5 logged", icon: "eye" }) : x.st === "pending-doctor" ? ui.btn("Nudge", { cls: "sm ghost", act: "toast", arg: "Reminder sent to the source doctor", icon: "bell" }) : ""];
    };
    var cols = [{ h: "Consent" }, { h: "Patient" }, { h: "Purpose & data" }, { h: "Status & approval trail" }, { h: "" }];
    h += '<h2 class="h">' + L("Your outgoing requests", "طلباتك الصادرة", "आपके भेजे अनुरोध") + "</h2>" + ui.table(cols, NB.D.consents.filter(function (x) { return x.by === "Dr. Vikram Shah"; }).map(row), { min: 820 });
    h += '<h2 class="h">' + L("Other sharing involving your patients", "مشاركات أخرى لمرضاك", "आपके मरीज़ों से जुड़ी अन्य साझेदारी") + "</h2>" + ui.table(cols, NB.D.consents.filter(function (x) { return x.by !== "Dr. Vikram Shah"; }).map(row), { min: 820 });
    return h;
  });
  NB.overlay("consentdecide", function (arg) {
    var a = String(arg).split("|"), x = CD.inc.find(function (r) { return r.id === a[0]; }) || CD.inc[0], p = NB.pat(x.p), deny = a[1] === "deny";
    var scope = NB.v("clScope_" + x.id, x.data), valid = NB.v("clCV_" + x.id, x.valid);
    var body = '<div class="row">' + ui.av(p.name, "lg") + '<div class="fl"><b>' + pn(p) + '</b><div class="small muted">' + esc(x.by) + " · " + esc(x.spec) + " · " + esc(ext(x)) + "</div></div>" + ui.pill("ok", "Patient approved") + "</div>" +
      '<div class="small" style="padding:10px 12px;border-radius:12px;background:var(--s2);border:1px solid var(--b-sub)">' + ui.icon("shieldcheck", "xs") + " Consent " + esc(x.id) + " · Patient (" + esc(x.ptCh) + ", " + esc(x.pt.split("· ")[1] || "") + ") · purpose: " + esc(x.purpose) + "</div>";
    if (deny) {
      body += '<span class="eyebrow">Reason (shared with the requester and the patient)</span><div class="stack s8">' +
        [["clinically_inappropriate", "Clinically inappropriate to share now"], ["third_party_information", "Contains third-party information"], ["records_incomplete", "Records incomplete — will share after update"], ["not_my_patient", "Not my patient — route to department head"]].map(function (r) { return ui.radio("clDeny", r[0], r[1], null, "not_my_patient"); }).join("") + "</div>" +
        ui.field("Note (optional)", "", { area: true, full: true, ph: "Shown to the requesting doctor" });
      return { title: "Decline request " + esc(x.id), body: body, foot: ui.btn("Cancel", { act: "close", cls: "ghost" }) + ui.btn("Decline request", { cls: "dng", act: "cl-cons", arg: x.id + "|no" }) };
    }
    body += '<span class="eyebrow">Scope · you can narrow, never widen</span><div class="chips">' + x.data.map(function (d) { var on = scope.indexOf(d) >= 0; return chip((on ? ui.icon("check", "xs") : "") + esc(d), on, 'data-act="cl-scope" data-arg="' + x.id + "|" + d + '"'); }).join("") + "</div>" +
      (scope.length < x.data.length ? ui.note("info", "Narrowed scope: the patient is told about the smaller version; no new approval is needed because it is strictly less.") : "") +
      '<div class="fgrid">' + ui.field("Date range", x.range, { options: [x.range, "Last 6 months", "Last 3 months"] }) + '<div class="field"><label>Valid for</label><div style="padding-top:4px">' + ui.seg("clCV_" + x.id, [["24 h", "24 h"], ["7 days", "7 d"], ["30 days", "30 d"]].filter(function (o) { return x.valid === "30 days" || o[0] !== "30 days"; }), x.valid) + "</div></div></div>" +
      '<dl class="cl-kv"><dt>Excluded always</dt><dd>Psychotherapy notes, HIV and sexual-health records (sensitive categories need a separate consent)</dd><dt>Delivered as</dt><dd>Read-only FHIR bundle, watermarked, no download</dd><dt>Auto-revoke</dt><dd>' + esc(valid) + " after approval or when the patient revokes</dd></dl>" +
      ui.field("Authenticator code (step-up)", "", { ph: "6-digit TOTP", id: "cl-totp" });
    return { title: L("Approve record request", "الموافقة على طلب السجلات", "रिकॉर्ड अनुरोध स्वीकृत करें") + " · " + esc(x.id), body: body,
      foot: ui.btn("Decline", { cls: "dng", ov: "consentdecide", arg: x.id + "|deny" }) + ui.btn("Approve & share", { cls: "pri", act: "cl-cons", arg: x.id + "|ok", icon: "shieldcheck", disabled: !scope.length }) };
  });
  NB.act("cl-scope", function (arg) { var a = arg.split("|"), x = CD.inc.find(function (r) { return r.id === a[0]; }), k = "clScope_" + a[0], cur = NB.v(k, x.data).slice(), i = cur.indexOf(a[1]); if (i >= 0) cur.splice(i, 1); else cur.push(a[1]); var o = {}; o[k] = cur; NB.setUI(o); });
  NB.act("cl-cons", function (arg) {
    var a = arg.split("|"), d = Object.assign({}, NB.v("clCons", {})), v = NB.v("clCV_" + a[0], (CD.inc.find(function (r) { return r.id === a[0]; }) || {}).valid);
    d[a[0]] = { s: a[1], v: v }; NB.state.overlay = null; NB.setUI({ clCons: d });
    NB.toast(a[1] === "ok" ? "Approved · shared for " + v + " · patient notified on WhatsApp" : "Declined · requester and patient notified with your reason");
  });

  /* ================= Consult overlays ================= */
  function vflag(k, v, age) {
    if (v === "" || v == null) return null; var n = parseFloat(v);
    if (k === "bp") { var s = String(v).split("/"), sy = +s[0], di = +s[1] || 0; if (sy >= 180 || di >= 120) return ["crit", "Hypertensive crisis"]; if (sy < 90) return ["crit", "Hypotension"]; if (sy >= 140 || di >= 90) return ["warn", "High"]; return ["ok", "Normal"]; }
    if (k === "hr") { var hi = age < 12 ? 120 : 100; if (n > 130) return ["crit", "Tachycardia"]; if (n > hi) return ["warn", "Tachycardia"]; if (n < 50) return ["warn", "Bradycardia"]; return ["ok", "Normal"]; }
    if (k === "t") { if (n >= 39.5) return ["urg", "High fever"]; if (n >= 38) return ["warn", "Fever"]; if (n < 35) return ["crit", "Hypothermia"]; return ["ok", "Normal"]; }
    if (k === "spo2") { if (n < 90) return ["crit", "Hypoxia"]; if (n < 94) return ["urg", "Low"]; if (n < 96) return ["warn", "Borderline"]; return ["ok", "Normal"]; }
    if (k === "rr") { var rh = age < 12 ? 25 : 21; if (n >= 30) return ["crit", "Very high"]; if (n >= rh) return ["warn", "High"]; return ["ok", "Normal"]; }
    if (k === "rbs") { if (n < 70) return ["crit", "Hypoglycaemia"]; if (n >= 300) return ["urg", "Very high"]; if (n >= 200) return ["warn", "High"]; return ["ok", "Normal"]; }
    if (k === "pain") { if (n >= 7) return ["urg", "Severe"]; if (n >= 4) return ["warn", "Moderate"]; return ["ok", "Mild / none"]; }
    return null;
  }
  var VF = [["bp", "BP (mmHg)"], ["hr", "Pulse (/min)"], ["t", "Temp (°C)"], ["spo2", "SpO₂ (%)"], ["rr", "Resp. rate (/min)"], ["rbs", "RBS (mg/dL)"], ["wt", "Weight (kg)"], ["pain", "Pain (0–10)"]];
  function vitalsInputs(vals, age, idp) {
    return '<div class="cl-vin">' + VF.map(function (f) {
      var fl = vflag(f[0], vals[f[0]], age);
      return '<div class="fx">' + ui.field(f[1], vals[f[0]] || "", { id: idp + f[0], ph: "—" }) + (fl ? ui.pill(fl[0], fl[1]) : '<span class="tiny faint">' + (f[0] === "wt" ? "for dosing" : "not taken") + "</span>") + "</div>";
    }).join("") + "</div>";
  }
  NB.overlay("clvitals", function (pid) {
    var p = NB.pat(pid), sc = scen(pid), vals = {};
    Object.keys(sc.v || {}).forEach(function (k) { vals[k] = sc.v[k][0]; });
    return { size: "lg", title: L("Vitals", "العلامات الحيوية", "वाइटल्स") + " · " + pn(p), body:
      '<div class="row">' + ui.pill("ok", "Omron HEM-7156 linked", "link") + ui.pill("mute", "Pulse oximeter: manual") + '<span class="small faint">' + esc(sc.vBy) + "</span></div>" +
      vitalsInputs(vals, p.age, "clv-") + ui.note("info", "Flags use adult ranges unless the patient is under 12 (paediatric ranges). NEWS2 is calculated for adults; anything ≥ 5 alerts the doctor."),
      foot: ui.btn("Cancel", { act: "close", cls: "ghost" }) + ui.btn("Save vitals", { cls: "pri", act: "toast", arg: "Vitals saved to " + p.name + "'s chart · flags sent to the doctor", icon: "check" }) };
  });
  NB.overlay("rxpreview", function (pid) {
    var p = NB.pat(pid), sc = scen(pid), rx = getRx(pid), res = checks(pid, rx), st = alSt(pid), solo = pid === NB.v("clSoloPat", "p2") && NB.state.role === "solo";
    var open = res.alerts.filter(function (a) { return !st[a.id]; }), crit = open.filter(function (a) { return a.sev === "crit"; }).length, ovr = res.alerts.filter(function (a) { return st[a.id]; });
    var d = NB.doc(solo ? "d1" : "d2"), f = NB.fac(), lang = sigLang(pid), val = +NB.v("clVal", "14"), route = NB.v("clRoute", "inhouse"), id = "RX-" + (NB.mkt().id === "ksa" ? "WSF-" : "") + "26-" + (40812 + pid.length * 7);
    var dx = NB.v("clDx_" + pid, sc.dx), fu = NB.v("clFu_" + pid, pid === "p3" ? "48 h" : "2 weeks");
    var doc = '<div class="cl-doc"><div class="hd"><div><b style="font-size:15px">' + esc(f.name) + '</b><div class="small muted">' + esc(f.area) + '</div><div class="small" style="margin-top:6px">' + esc(NB.dname(d)) + " · " + esc(d.spec) + ' · Reg. <span class="ltr">' + esc(d.regNo) + "</span></div></div>" +
      '<div class="row nw" style="align-items:flex-start">' + qr(id + pid + rx.length, 92) + '<div class="small"><b class="ltr">' + id + '</b><div class="faint">17 Oct 2026, 10:15</div><div>' + L("Valid until", "صالحة حتى", "वैध तक") + ' <b class="num">' + today(val) + "</b></div><div class=\"faint\">" + esc(eRxName()) + "</div></div></div></div>" +
      '<div class="small"><b>' + pn(p) + "</b> · " + p.age + " " + p.sex + " · MRN " + esc(p.mrn) + ' · <span class="ltr">' + esc(natNo(p)) + "</span>" + (isPreg(p) ? " · " + ui.pill("urg", "Pregnant 14 wk", "baby") : "") + "</div>" +
      '<div class="small">' + ui.icon("alertfill", "xs critc") + " " + L("Allergies", "الحساسية", "एलर्जी") + ": " + ((p.allergies || []).map(function (a) { return a.a + " (" + a.rx + ")"; }).join(", ") || "None known") + "</div>" +
      '<div class="small faint">Dx: ' + dx.map(function (c) { return '<span class="ltr">' + c + "</span> " + esc(icdName(c)); }).join(" · ") + "</div>" +
      "<ol>" + rx.map(function (l) { var dd = drug(l.d); return "<li><b>" + esc(dd.name) + '</b> <span class="faint">(' + esc(dd.gen) + ")</span><small dir=\"ltr\">" + esc(sig(l, "en")) + " · " + esc(dispenseQty(l)) + "</small>" + (lang !== "en" ? '<small dir="' + (lang === "ar" ? "rtl" : "ltr") + '" lang="' + lang + '">' + esc(sig(l, lang)) + "</small>" : "") + "</li>"; }).join("") + "</ol>" +
      '<div class="small">' + L("Follow-up", "المتابعة", "फ़ॉलो-अप") + ": " + esc(fu) + (pid === "p3" ? " · return today if breathless, confused or vomiting" : "") + '</div><div class="small faint">' + ui.icon("pen", "xs") + " Digitally signed by " + esc(d.name) + " (PIN + device key) on signing</div></div>";
    var body = (crit ? ui.note("crit", crit + " critical alert" + (crit > 1 ? "s are" : " is") + " still open. Go back and accept the safer option or override with a reason.") : ui.note("ok", "All safety checks addressed" + (ovr.length ? " · " + ovr.length + " override" + (ovr.length > 1 ? "s" : "") + " recorded with reasons" : "") + ".")) + doc +
      '<p class="small muted" style="margin:0">' + esc(eRxNote()) + "</p>" +
      '<span class="eyebrow">' + L("Send to patient", "أرسل إلى المريض", "मरीज़ को भेजें") + "</span>" +
      ui.toggle("clSendWa", "WhatsApp (" + (lang === "hi" ? "Hindi + English" : lang === "ar" ? "Arabic + English" : "English") + ")", "PDF + QR behind a secure link; reminders for each dose", true) + ui.toggle("clSendApp", "Nabd app", "Adds to the patient's records and medicine reminders", true) + ui.toggle("clSendPrint", "Print a copy", "Thermal or A5 letterhead", false) +
      '<span class="eyebrow">' + L("Pharmacy", "الصيدلية", "फ़ार्मेसी") + '</span><div class="stack s8">' +
      ui.radio("clRoute", "inhouse", "In-house pharmacy (default)", "All " + rx.length + " items checked against stock · lands in the e-Rx queue now", "inhouse") +
      ui.radio("clRoute", "choice", "Patient's choice of pharmacy", "Any pharmacy can scan the QR" + (NB.mkt().id === "ksa" ? " or look up the Wasfaty number with the National ID" : "") + "; the clinic is not told where it was filled", "inhouse") +
      ui.radio("clRoute", "deliver", "Home delivery partner", "Patient picks a partner in WhatsApp; delivery by evening", "inhouse") + "</div>";
    return { size: "lg", title: L("e-Prescription preview", "معاينة الوصفة", "ई-Rx पूर्वावलोकन"), body: body,
      foot: ui.btn(L("Back to edit", "العودة للتعديل", "वापस बदलें"), { act: "close", cls: "ghost" }) + timerHtml(pid) + ui.btn(L("Sign with PIN & send", "وقّع بالرمز وأرسل", "PIN से साइन करके भेजें"), { cls: "pri", act: "cl-sign", arg: pid, icon: "pen", disabled: !!crit || !rx.length }) };
  });
  NB.overlay("rxoverride", function (arg) {
    var a = String(arg).split("|"), pid = a[0], al = checks(pid, getRx(pid)).alerts.find(function (x) { return x.id === a[1]; });
    if (!al) return { kind: "modal", title: "Alert resolved", body: ui.note("ok", "This alert no longer applies."), foot: ui.btn("Close", { act: "close" }) };
    var reasons = al.kind === L("Allergy", "حساسية", "एलर्जी") ? ["Allergy record is wrong — patient has taken this drug safely since (will update record)", "Benefit outweighs risk; first dose under observation with adrenaline ready", "Allergist-cleared (oral challenge documented)"]
      : ["Benefit outweighs risk; patient monitored", "Specialist advised this dose / combination", "Alert not clinically relevant for this patient", "Short course only; will review at follow-up"];
    return { kind: "modal", title: L("Override with reason", "تجاوز مع ذكر السبب", "कारण सहित ओवरराइड"), body:
      '<div class="row" style="gap:6px">' + sevPill(al.sev) + ui.pill("mute", al.kind, "dot") + "</div><b>" + esc(al.title) + "</b>" +
      '<div class="stack s8">' + reasons.map(function (r, i) { return ui.radio("clOvrR", r, r, null, reasons[0]); }).join("") + "</div>" +
      '<div class="field"><label for="cl-ovr-txt">Note (optional, goes in the chart)</label><input id="cl-ovr-txt" placeholder="e.g. took amoxicillin in 2023 without reaction"></div>' +
      ui.note(al.sev === "crit" ? "crit" : "warn", "Recorded in the audit log with your name, time, the alert version and your reason. " + (al.sev === "crit" ? "Critical overrides are reviewed by the medical director within 7 days." : "Override rates per alert help the medical director tune noisy alerts.")),
      foot: ui.btn("Cancel", { act: "close", cls: "ghost" }) + ui.btn(L("Override & keep", "تجاوز والإبقاء", "ओवरराइड करें"), { cls: "dng", act: "cl-ovr", arg: pid + "|" + al.id, icon: "edit" }) };
  });
  NB.overlay("referral", function (arg) {
    var a = String(arg).split("|"), pid = a[0], p = NB.pat(pid), t = NB.v("clRefT", a[1] || "int"), sc = scen(pid);
    var body = '<div class="row">' + ui.seg("clRefT", [["int", L("Within hospital", "داخل المستشفى", "अस्पताल के भीतर")], ["ext", L("Outside facility", "منشأة خارجية", "बाहरी सुविधा")]], a[1] || "int") + "</div>";
    if (t === "int") {
      body += '<div class="fgrid">' + ui.field("Specialty / doctor", pid === "p2" ? "Obstetrics · Dr. Fatima Rahman (today 12:10)" : "Nephrology · Dr. Leela Krishnan (today 11:40)", { options: ["Nephrology · Dr. Leela Krishnan (today 11:40)", "Cardiology · Dr. Khalid Al-Saeed (Mon 10:00)", "Endocrinology · Dr. Ritu Menon (today 16:30)", "Obstetrics · Dr. Fatima Rahman (today 12:10)", "Dietitian · Ms. Kavitha (today 11:00)", "Physiotherapy (today 15:00)"], full: true }) +
        '<div class="field full"><label>Urgency</label><div style="padding-top:4px">' + ui.seg("clRefU", [["routine", "Routine"], ["soon", "Within 1 week"], ["today", "Today"]], "today") + "</div></div>" +
        ui.field("Reason", pid === "p3" ? "CKD G3b with eGFR 52 → 41 in 6 months and K+ 6.1 on ARB. Please advise on RAAS blockade and diabetes therapy." : sc.reason, { area: true, full: true }) + "</div>" +
        '<div class="stack s8">' + ui.radio("clRefQ", "queue", "Add to their queue today", "Patient keeps their token journey; WhatsApp shows the new room and ETA", "queue") + ui.radio("clRefQ", "slot", "Book the next free slot", "Confirm / reschedule buttons sent on WhatsApp", "queue") + "</div>" +
        ui.toggle("clRefN", "Share referral letter + today's note", "Specialist sees what this referral shares (hospital policy: referral-only access)", true) + ui.toggle("clRefL", "Share relevant results", "KFT, HbA1c, potassium trend", true) +
        ui.note("info", "Same hospital, different department: the owner's access policy is <b>referral-only</b>. You'll be told the outcome when the specialist closes the referral.");
    } else {
      body += '<div class="fgrid">' + ui.field("Facility", NB.gcc() ? "Mediclinic City Hospital, Dubai" : "Apollo Hospitals, Jayanagar", { full: true }) + ui.field("Doctor (optional)", "Dr. Imran Qureshi · Cardiology") + ui.field("Purpose", "Specialist opinion") + "</div>" +
        '<span class="eyebrow">Records to share</span><div class="chips">' + ["Referral letter", "Encounters (12 mo)", "Lab results", "Prescriptions", "Imaging reports"].map(function (x, i) { return chip(ui.icon("check", "xs") + x, i < 3, 'data-act="toast" data-arg="Scope updated"'); }).join("") + "</div>" +
        '<div class="row"><span class="small faint">Valid for</span>' + ui.seg("clRefV", [["7", "7 days"], ["14", "14 days"], ["30", "30 days"]], "14") + "</div>" +
        '<span class="eyebrow">Patient consent</span><div class="stack s8">' + ui.radio("clRefC", "wa", "Send WhatsApp approval now", "“Dr. Vikram Shah wants to share your records with … [Review]” → OTP · usually under 2 minutes", "wa") +
        ui.radio("clRefC", "desk", "Sign at the desk (staff-witnessed)", "Assisted consent in the patient's language", "wa") + "</div>" +
        ui.note("warn", "Nothing is shared until " + pn(p) + " approves. Access ends automatically at expiry; the patient can revoke it any time and sees every view.");
    }
    return { size: "lg", title: L("Referral", "إحالة", "रेफ़रल") + " · " + pn(p), body: body, foot: ui.btn("Cancel", { act: "close", cls: "ghost" }) + ui.btn(t === "int" ? "Send referral" : "Send consent request", { cls: "pri", act: "toast", arg: t === "int" ? "Referral sent · patient added to the specialist's queue · WhatsApp updated" : "Consent request sent on WhatsApp · referral goes out after approval", icon: "send" }) };
  });
  NB.overlay("icd", function (pid) {
    var sc = scen(pid), cur = NB.v("clDx_" + pid, sc.dx), std = NB.gcc() ? "ICD-10-AM" : "ICD-10 (WHO)";
    return { title: L("Diagnosis search", "بحث التشخيص", "निदान खोजें") + " · " + std, body:
      '<label class="cl-inp">' + ui.icon("search", "sm faint") + '<input data-cl-filter="cl-icdl" placeholder="Type a term or code, e.g. pneumonia, J18" autocomplete="off"></label>' +
      '<div class="cl-cr"><span class="cl-lb">Favourites</span>' + CD.icdFav.map(function (c) { return chip('<b class="ltr">' + c + "</b>", cur.indexOf(c) >= 0, 'data-act="cl-dx" data-arg="' + pid + "|" + c + '"'); }).join("") + "</div>" +
      '<div class="stack s8" id="cl-icdl">' + CD.icd.map(function (r) {
        var on = cur.indexOf(r[0]) >= 0;
        return '<div class="cl-rowc' + (on ? " on" : "") + '" data-act="cl-dx" data-arg="' + pid + "|" + r[0] + '" data-q="' + esc(r[0] + " " + r[1]) + '"><b class="ltr acc" style="min-width:56px">' + r[0] + '</b><div class="t"><b class="cl-wrap">' + esc(r[1]) + "</b></div>" + (on ? ui.pill("acc", "Added") : ui.icon("plus", "sm faint")) + "</div>";
      }).join("") + "</div>" + ui.note("info", "Codes flow to the invoice and the " + esc(NB.mkt().claims) + " claim. Selecting a code again removes it.") };
  });

  /* ================= Nurse ================= */
  NB.screen("nurse.triage", function (c) {
    var sel = NB.v("clTri", "T-23"), t = CD.triage.find(function (x) { return x.tok === sel; }) || CD.triage[0], urg = NB.v("clUrg", {});
    var need = CD.triage.filter(function (x) { return x.st === "new"; }).length;
    var who = function (x) { var p = x.p ? NB.pat(x.p) : null; return { name: p ? NB.pname(p) : x.name, as: p ? p.age + " " + p.sex : x.as, age: p ? p.age : parseInt(x.as, 10), al: p ? p.allergies : [] }; };
    var h = ui.header(L("Triage & vitals", "الفرز والعلامات الحيوية", "ट्राइएज और वाइटल्स"), need + " arrivals need vitals · median vitals 0:52 · red flags escalated in under 5 min",
      ui.btn(L("Scan token", "مسح الرقم", "टोकन स्कैन"), { icon: "qr", act: "toast", arg: "Scan the patient's token QR or wristband" }));
    h += '<div class="grid g4 kpis">' + stat("Waiting for vitals", need, "oldest 34 min", "warn") + stat("Red flags", 1, "chest pain · T-23", "crit") + stat("Median vitals time", "0:52", "target < 1:00", "ok") + stat("Escalations today", 2, "both < 5 min", "acc") + "</div>";
    var left = '<div class="stack s8">' + CD.triage.map(function (x) {
      var w = who(x);
      return '<div class="cl-rowc ' + (x.red ? "crit " : "") + (x.tok === t.tok ? "on" : "") + '" data-set="clTri" data-val="' + x.tok + '"><span class="tok">' + x.tok + '</span><div class="t"><b>' + esc(w.name) + "</b><small>" + esc(w.as) + " · " + esc(x.reason) + "</small><small>" + (x.st === "done" ? esc(x.done) : "Arrived " + x.arr + " · for " + esc(NB.dname(NB.doc(x.d)))) + "</small></div>" +
        '<div class="stack s4" style="align-items:flex-end">' + (x.red ? ui.pill("crit", "Red flag") : "") + (x.st === "done" ? ui.pill("ok", "Done") : x.st === "inprog" ? ui.pill("sync", "In progress") : ui.pill("warn", "Needs vitals")) + (urg[x.tok] ? ui.pill("urg", "Urgent") : "") + "</div></div>";
    }).join("") + "</div>";
    var w = who(t), esiK = "clEsi_" + t.tok, esi = NB.v(esiK, String(t.esi)), flags = VF.map(function (f) { var fl = vflag(f[0], (t.v || {})[f[0]], w.age); return fl && fl[0] !== "ok" ? f[1].split(" (")[0] + " " + t.v[f[0]] + " — " + fl[1].toLowerCase() : null; }).filter(Boolean);
    var wt = t.v && +t.v.wt;
    var right = '<div class="card ' + (t.red ? "crit" : "") + '"><div class="ch"><span class="eyebrow">' + ui.icon("heartpulse", "sm") + L("Quick vitals", "علامات سريعة", "त्वरित वाइटल्स") + '</span><span class="tok">' + t.tok + "</span>" + ui.pill("ok", "Omron linked", "link") + "</div>" +
      '<div class="row" style="margin-bottom:12px">' + ui.av(w.name, "lg") + '<div class="fl"><b>' + esc(w.name) + '</b><div class="small muted">' + esc(w.as) + " · " + esc(t.reason) + " · for " + esc(NB.dname(NB.doc(t.d))) + "</div></div>" + ((w.al || []).map(function (a) { return ui.pill("crit", a.a + " allergy"); }).join("")) + "</div>" +
      (t.red ? ui.note("crit", "<b>RED FLAG · " + esc(t.red) + ".</b> Protocol: 12-lead ECG within 10 min of arrival, notify doctor now, suggested ESI 2.") : "") +
      (t.v ? '<div style="margin-top:12px">' + vitalsInputs(t.v, w.age, "clt-") + "</div>" : "") +
      (flags.length ? '<div style="margin-top:12px">' + ui.note("warn", "<b>Auto-flags:</b> " + flags.map(esc).join(" · ")) + "</div>" : "") +
      (wt && w.age < 15 ? '<div style="margin-top:8px">' + ui.note("info", "Weight " + wt + " kg → paracetamol 15 mg/kg = <b>" + Math.round(wt * 15) + " mg</b> per dose (max 4 doses/day). Shown to the doctor.") + "</div>" : "") +
      '<div class="stack s8" style="margin-top:14px"><span class="eyebrow">Triage category (ESI)</span>' + ui.seg(esiK, [["1", "1 · Resuscitation"], ["2", "2 · Emergent"], ["3", "3 · Urgent"], ["4", "4 · Less urgent"], ["5", "5 · Non-urgent"]], String(t.esi)) + "</div>" +
      ui.toggle("clUrgT_" + t.tok, "Mark urgent (moves to the top of the doctor's queue)", "Other patients see a neutral “urgent case” ETA update · logged with your name and reason", +esi <= 2) +
      '<div class="fgrid" style="margin-top:8px">' + ui.field("Reason for priority", t.red ? "Chest pain" : "—", { options: ["—", "Chest pain", "Breathing difficulty", "High fever in a child", "Severe pain", "Pregnancy concern", "Other"] }) + ui.field("Notify", NB.dname(NB.doc(t.d)), { options: [NB.dname(NB.doc(t.d)), "Duty doctor", "Charge nurse"] }) + "</div>" +
      '<div class="btns" style="margin-top:14px">' + ui.btn(L("Save & send to doctor", "حفظ وإرسال للطبيب", "सहेजें और डॉक्टर को भेजें"), { cls: "pri", act: "cl-tri", arg: t.tok, icon: "check" }) + (t.red ? ui.btn(L("Escalate now", "تصعيد الآن", "तुरंत एस्केलेट"), { cls: "dng", act: "cl-esc", arg: t.tok, icon: "bolt" }) : "") + "</div></div>";
    return h + '<div class="split l">' + left + right + "</div>";
  });
  NB.act("cl-tri", function (tok) { var u = Object.assign({}, NB.v("clUrg", {})); if (NB.v("clUrgT_" + tok, false) || +NB.v("clEsi_" + tok, "5") <= 2) u[tok] = true; NB.setUI({ clUrg: u }); NB.toast("Vitals saved for " + tok + " · doctor sees them before calling"); });
  NB.act("cl-esc", function (tok) { var u = Object.assign({}, NB.v("clUrg", {})); u[tok] = true; NB.setUI({ clUrg: u }); NB.toast(tok + " escalated · Dr. Khalid Al-Saeed alerted · ECG ordered · 4 patients' ETAs updated"); });

  NB.screen("nurse.orders", function (c) {
    var done = NB.v("clNDone", {}), grp = [["overdue", L("Overdue", "متأخر", "देर से"), "crit"], ["due", L("Due now", "مستحق الآن", "अभी देय"), "urg"], ["later", L("Later today", "لاحقًا اليوم", "आज बाद में"), "mute"], ["done", L("Done", "تم", "पूर्ण"), "ok"]];
    var stOf = function (o) { return done[o.id] ? "done" : o.st; };
    var h = ui.header(L("Nursing orders", "أوامر التمريض", "नर्सिंग आदेश"), CD.norders.filter(function (o) { return stOf(o) !== "done"; }).length + " open · injections, dressings, samples and observations ordered by doctors",
      ui.seg("clNoF", [["all", "All"], ["Medication", "Medication"], ["Vaccine", "Vaccine"], ["Dressing", "Dressing"], ["Sample", "Sample"]], "all"));
    h += ui.note("info", "Before giving anything: scan the token or wristband, then check the five rights (patient, drug, dose, route, time). Allergies show on every order.");
    var fk = NB.v("clNoF", "all");
    h += grp.map(function (g) {
      var list = CD.norders.filter(function (o) { return stOf(o) === g[0] && (fk === "all" || o.kind === fk); });
      if (!list.length) return "";
      return '<div class="stack s8"><div class="row"><h2 class="h">' + g[1] + "</h2>" + ui.pill(g[2], String(list.length), g[0] === "done" ? "checkcircle" : null) + "</div>" + list.map(function (o) {
        var p = o.p ? NB.pat(o.p) : null, al = p && (p.allergies || []).length ? p.allergies.map(function (a) { return a.a; }).join(", ") : "None known";
        return '<div class="cl-qi ' + (g[0] === "overdue" ? "" : "") + '" style="' + (g[0] === "overdue" ? "border-inline-start:3px solid var(--crit)" : g[0] === "due" ? "border-inline-start:3px solid var(--urg)" : "") + '"><div style="min-width:54px"><b class="num ltr" style="font-size:15px">' + o.due + '</b><div class="tiny faint">' + esc(o.kind) + '</div></div><div class="t"><b class="cl-wrap">' + esc(o.ord) + '</b><small class="cl-wrap">' + (p ? pn(p) + " · " + p.age + " " + p.sex : esc(o.name + " · " + o.as)) + " · " + esc(o.det) + "</small><small>" + ui.icon("alertfill", "xs") + " Allergies: " + esc(al) + " · by " + esc(o.by) + "</small></div>" +
          (g[0] === "done" ? ui.pill("ok", "Done") : '<div class="btns">' + (o.kind === "Vaccine" ? ui.btn("Record", { cls: "sm pri", ov: "clvaccine", arg: o.id, icon: "syringe" }) : ui.btn("Done", { cls: "sm pri", act: "cl-ndone", arg: o.id, icon: "check" })) + ui.btn("Not done", { cls: "sm ghost", act: "toast", arg: "Reason asked: patient refused / not available / held by doctor" }) + "</div>") + "</div>";
      }).join("") + "</div>";
    }).join("");
    return h;
  });
  NB.act("cl-ndone", function (id) { var d = Object.assign({}, NB.v("clNDone", {})); d[id] = true; NB.state.overlay = null; NB.setUI({ clNDone: d }); NB.toast("Recorded with time, your name and site · doctor notified"); });

  NB.screen("nurse.procedures", function (c) {
    var tab = NB.v("clPrTab", "today"), z = NB.pat("p4"), m = NB.mkt();
    var h = ui.header(L("Procedures & vaccines", "الإجراءات واللقاحات", "प्रक्रियाएँ और टीके"), "Procedure rooms, package sessions and immunisations with batch and cold-chain records",
      ui.btn(L("Record vaccine", "تسجيل لقاح", "टीका दर्ज करें"), { cls: "pri", ov: "clvaccine", arg: "N4", icon: "syringe" }));
    h += ui.tabs("clPrTab", [["today", L("Today's schedule", "جدول اليوم", "आज का शेड्यूल"), "calendar"], ["vacc", L("Vaccines", "اللقاحات", "टीके"), "syringe"], ["pkg", L("Package sessions", "جلسات الباقات", "पैकेज सत्र"), "layers"]], "today");
    if (tab === "today") {
      h += ui.table([{ h: "Time" }, { h: "Room" }, { h: "Patient" }, { h: "Procedure" }, { h: "Package / schedule" }, { h: "By" }, { h: "Status" }, { h: "" }], CD.procs.map(function (x) {
        var p = x.p ? NB.pat(x.p) : null, s = x.st === "inprog" ? ui.pill("sync", "In progress") : x.st === "due" ? ui.pill("warn", "Due") : ui.pill("mute", "Booked");
        return { cls: x.st === "inprog" ? "sel" : "", cells: ['<b class="num ltr">' + x.t + "</b>", esc(x.room), p ? pn(p) : esc(x.name), "<b>" + esc(x.proc) + "</b>", esc(x.pkg), esc(x.by), s,
          x.st === "inprog" ? ui.btn("Complete", { cls: "sm okb", act: "toast", arg: "Session completed · package balance updated · photos saved with consent", icon: "check" }) : ui.btn("Start", { cls: "sm", act: "toast", arg: x.proc + " started · consent form verified" })] };
      }), { min: 900 });
    } else if (tab === "vacc") {
      h += '<div class="split wide"><div class="stack s16"><div class="card hl"><div class="row nw">' + ui.av(z.name, "lg") + '<div class="fl"><b>' + pn(z) + '</b><div class="small muted">' + z.age + " y · " + esc(z.tags[0]) + " · token T-16</div></div>" + ui.pill("warn", "Egg allergy (hives)") + "</div>" +
        '<div class="chips" style="margin-top:12px">' + ui.pill("warn", "Due today: DTaP booster 2", "syringe") + ui.pill("warn", "Due today: MMR 3", "syringe") + ui.pill("mute", "Next: Tdap at 10 y") + "</div>" +
        '<div style="margin-top:12px">' + ui.note("info", "Egg allergy is not a contraindication for MMR or influenza vaccines; observe for 15 minutes as usual (IAP 2023, ACIP).") + "</div>" +
        '<div class="btns" style="margin-top:12px">' + ui.btn("Record DTaP booster 2", { cls: "pri", ov: "clvaccine", arg: "N4", icon: "syringe" }) + ui.btn("Record MMR 3", { ov: "clvaccine", arg: "MMR", icon: "syringe" }) + "</div></div>" +
        ui.card("Vaccine record", ui.table([{ h: "Vaccine" }, { h: "Schedule" }, { h: "Given" }, { h: "Batch" }, { h: "Expiry" }, { h: "Site" }, { h: "Where" }], CD.vrec.map(function (r) { return ["<b>" + esc(r[0]) + "</b>", esc(r[1]), esc(r[2]), '<span class="ltr num">' + esc(r[3]) + "</span>", esc(r[4]), esc(r[5]), esc(r[6])]; }), { min: 700 }), { icon: "file", right: ui.btn("Share certificate", { cls: "sm ghost", act: "toast", arg: "Immunisation certificate sent to Imran Khan (guardian) on WhatsApp", icon: "share" }) }) + "</div>" +
        '<div class="stack s16">' + ui.card("Cold chain · vaccine fridge", '<div class="row"><span class="cl-amt num" style="font-size:28px">4.6 °C</span>' + ui.pill("ok", "In range 2–8 °C") + '</div><p class="small faint" style="margin:6px 0 12px">Logged 09:00 by Anita D\'Souza · next check 13:00</p>' +
          ui.table([{ h: "Vaccine" }, { h: "Batch" }, { h: "Expiry" }, { h: "Qty", cls: "r" }], CD.vstock.map(function (s) { return [esc(s[0]) + (s[4] ? " " + ui.pill("acc", s[4]) : ""), '<span class="ltr">' + esc(s[1]) + "</span>", esc(s[2]), '<span class="num">' + s[3] + "</span>"]; }), { min: 380 }), { icon: "thermo" }) +
        ui.note("info", "Records sync to " + esc(m.id === "india" ? "the ABHA locker (ABDM) and U-WIN" : m.hie) + " when the guardian has consented.") + "</div></div>";
    } else {
      var pk = [["p5", "Laser hair reduction · face", 3, 6, "Session 4 today 09:30", "Paid in full"], [null, "Diabetic foot dressing (Ravi Kumar)", 4, 10, "Session 5 today 11:00", "Insurance · Star Health"], [null, "Chemical peel (Mohan Das)", 1, 4, "Session 2 today 11:45", NB.money(1500) + " due"], [null, "Vitamin B12 injections (Shalini Gupta)", 2, 5, "Dose 3 today 11:30", "Paid"]];
      h += '<div class="grid g2">' + pk.map(function (x) {
        var p = x[0] ? NB.pat(x[0]) : null;
        return '<div class="card"><div class="row sb"><b>' + (p ? pn(p) + " · " : "") + esc(x[1]) + '</b><span class="num small">' + x[2] + "/" + x[3] + '</span></div><div style="margin:10px 0">' + ui.bar(x[2] / x[3] * 100) + '</div><div class="row sb"><span class="small muted">' + esc(x[4]) + "</span>" + (/due/.test(x[5]) ? ui.pill("warn", "Balance due") : ui.pill("ok", "Covered")) + '</div><p class="small faint" style="margin:8px 0 0">' + x[5] + "</p></div>";
      }).join("") + "</div>" + ui.note("info", "Completing a session deducts it from the package and books the next one at the agreed interval (WhatsApp confirms).");
    }
    return h;
  });
  NB.overlay("clvaccine", function (arg) {
    var mmr = arg === "MMR", vac = mmr ? "Tresivac (MMR) · dose 3" : "Infanrix (DTaP) · booster 2", stk = CD.vstock.filter(function (s) { return mmr ? /MMR/.test(s[0]) : /DTaP/.test(s[0]); });
    return { title: L("Record vaccine", "تسجيل لقاح", "टीका दर्ज करें") + " · Zoya Khan", body:
      '<div class="fgrid">' + ui.field("Vaccine", vac, { options: [vac], full: true }) + "</div>" +
      '<span class="eyebrow">Batch (first-expiry-first-out suggested)</span><div class="stack s8">' + stk.map(function (s, i) { return ui.radio("clVb", s[1], s[1] + " · exp " + s[2], s[3] + " doses in fridge" + (i === 0 ? " · FEFO pick" : ""), stk[0][1]); }).join("") + "</div>" +
      '<div class="fgrid">' + ui.field("Site", mmr ? "Right upper arm (SC)" : "Left deltoid (IM)", { options: ["Left deltoid (IM)", "Right deltoid (IM)", "Right upper arm (SC)", "Left thigh (IM)"] }) + ui.field("Given by", "Anita D'Souza") +
      ui.field("Guardian consent", "Imran Khan (father) · verbal + WhatsApp", { full: true }) + "</div>" +
      ui.toggle("clVobs", "Start 15-minute observation timer", "Alerts you if the child leaves before it ends", true) + ui.toggle("clVcert", "Send certificate to guardian on WhatsApp", "Includes batch and next due date", true) +
      ui.note("warn", "Report any adverse event (AEFI) from the vaccine record; it goes to the doctor and the national surveillance form."),
      foot: ui.btn("Cancel", { act: "close", cls: "ghost" }) + ui.btn("Record & start observation", { cls: "pri", act: "cl-ndone", arg: "N4", icon: "syringe" }) };
  });

  NB.screen("nurse.inbox", function (c) {
    var sel = NB.v("clMsg", "M1"), msg = CD.inbox.find(function (x) { return x.id === sel; }) || CD.inbox[0], p = NB.pat(msg.p), st = NB.v("clMsgSt", {});
    var PK = { High: "crit", Medium: "warn", Routine: "info", Info: "mute" };
    var h = ui.header(L("Clinical inbox", "الوارد السريري", "क्लिनिकल इनबॉक्स"), "Patient messages the WhatsApp bot could not answer, triaged by rules · the bot never gives clinical advice",
      ui.seg("clInF", [["open", "Open", CD.inbox.filter(function (x) { return x.status === "new" && !st[x.id]; }).length], ["all", "All", CD.inbox.length]], "open"));
    var f = NB.v("clInF", "open");
    var left = '<div class="stack s8">' + CD.inbox.filter(function (x) { return f === "all" || (x.status === "new" && !st[x.id]); }).map(function (x) {
      var pp = NB.pat(x.p);
      return '<div class="cl-rowc ' + (x.pri === "High" ? "crit " : x.pri === "Medium" ? "urg " : "") + (x.id === msg.id ? "on" : "") + '" data-set="clMsg" data-val="' + x.id + '">' + ui.av(pp.name) + '<div class="t"><b>' + esc(x.from) + "</b><small>" + esc(x.tr || x.text) + "</small><small>" + pn(pp) + " · " + x.at + "</small></div>" +
        '<div class="stack s4" style="align-items:flex-end">' + ui.pill(PK[x.pri], x.pri) + (st[x.id] ? ui.pill("ok", st[x.id]) : x.status === "auto" ? ui.pill("sub", "Bot answered") : "") + "</div></div>";
    }).join("") + "</div>";
    var tpl = msg.pri === "High" ? ["Please come to the clinic now. If he has chest pain, weakness on one side or confusion, go to the nearest emergency.", "We have booked an urgent slot at 10:40 with Dr. Vikram Shah."] : msg.id === "M3" ? ["I've sent your refill request to Dr. Meera Iyer. You'll get the e-prescription here once she approves."] : msg.id === "M4" ? ["Take thyroxine on an empty stomach with water, 30–60 minutes before tea or breakfast."] : ["Thanks, I've shared this with the doctor. We'll reply within 2 hours.", "Please book a review: [link]"];
    var right = '<div class="card ' + (msg.pri === "High" ? "crit" : "") + '"><div class="row" style="margin-bottom:12px">' + ui.av(p.name, "lg") + '<div class="fl"><b>' + esc(msg.from) + '</b><div class="small muted">' + L("About", "بخصوص", "बारे में") + " " + pn(p) + " · " + p.age + " " + p.sex + " · " + esc((p.conditions || []).join(", ") || "—") + "</div></div>" + ui.pill(PK[msg.pri], msg.pri) + "</div>" +
      '<div class="cl-thread"><div class="cl-msg in" dir="auto">' + esc(msg.text) + "<small>" + msg.at + " · WhatsApp · " + esc(msg.lang) + "</small></div>" + (msg.tr ? '<div class="small faint">' + ui.icon("translate", "xs") + " " + esc(msg.tr) + "</div>" : "") +
      (st[msg.id] === "Replied" ? '<div class="cl-msg out">' + esc(tpl[0]) + "<small>You · just now · delivered</small></div>" : "") + "</div>" +
      '<div style="margin-top:12px">' + ui.note("ai", "<b>Bot routing (rules, not diagnosis):</b> " + esc(msg.bot)) + "</div>" +
      '<div class="stack s8" style="margin-top:12px"><span class="eyebrow">Approved replies</span>' + tpl.map(function (t) { return '<div class="cl-sug" style="border-style:solid;border-color:var(--b-def);background:var(--s2)"><span>' + esc(t) + "</span></div>"; }).join("") + "</div>" +
      '<textarea class="cl-ta" style="margin-top:10px" placeholder="Write a reply (sent in ' + esc(msg.lang) + ', auto-translated for you)"></textarea>' +
      '<div class="btns" style="margin-top:12px">' + ui.btn("Send reply", { cls: "pri", act: "cl-msgst", arg: msg.id + "|Replied", icon: "send" }) + ui.btn("Escalate to doctor", { cls: msg.pri === "High" ? "dng" : "", act: "cl-msgst", arg: msg.id + "|Escalated", icon: "bolt" }) +
      ui.btn("Book visit", { act: "toast", arg: "Urgent slot offered on WhatsApp", icon: "calendar" }) + ui.btn("Call", { cls: "ghost", act: "toast", arg: "Calling via the clinic number (recorded)", icon: "phone" }) + ui.btn("Close", { cls: "ghost", act: "cl-msgst", arg: msg.id + "|Closed" }) + "</div></div>";
    return h + '<div class="split l">' + left + right + "</div>";
  });
  NB.act("cl-msgst", function (arg) { var a = arg.split("|"), s = Object.assign({}, NB.v("clMsgSt", {})); s[a[0]] = a[1]; NB.setUI({ clMsgSt: s }); NB.toast(a[1] === "Escalated" ? "Escalated to the doctor with the conversation attached" : a[1] === "Replied" ? "Reply sent on WhatsApp" : "Conversation closed"); });

  /* ================= Solo doctor ================= */
  var SPAY = { paid: ["ok", "Paid"], due: ["warn", "Pay after"], pkg: ["info", "Package"], prepaid: ["ok", "Prepaid"] };
  var SQ = { completed: ["ok", "Done"], inconsult: ["acc", "With you"], waiting: ["warn", "Waiting"], arrived: ["info", "Checked in"], booked: ["mute", "Booked"] };
  function soloQ() { var now = NB.v("clSoloNow", 1); return CD.solo.map(function (x, i) { var s = i < now ? "completed" : i === now ? "inconsult" : x.st === "inconsult" ? "waiting" : x.st; return Object.assign({}, x, { st: s, i: i }); }); }
  NB.screen("solo.today", function (c) {
    var q = soloQ(), now = q.find(function (x) { return x.st === "inconsult"; }), next = q.find(function (x) { return x.st === "waiting" || x.st === "arrived"; }), paused = NB.v("clPause", false);
    var nm = function (x) { var p = x.p ? NB.pat(x.p) : null; return { n: p ? NB.pname(p) : x.name, as: p ? p.age + " " + p.sex : x.as, r: p ? ((NB.D.queue.find(function (y) { return y.p === x.p; }) || {}).reason || scen(x.p).reason) : x.reason }; };
    var waiting = q.filter(function (x) { return /waiting|arrived|booked/.test(x.st); }).length;
    var h = ui.header(L("Today", "اليوم", "आज"), "Sat 17 Oct · " + waiting + " to see · auto-accept on (28 of 40 booked) · self check-in on");
    if (paused) h += ui.note("crit", "<b>Queue paused.</b> Waiting patients got a neutral “doctor attending an emergency” message with updated ETAs. " + '<a data-toggle="clPause" style="cursor:pointer">Resume queue</a>');
    var hero = '<div class="card hl">' + (now ? '<div class="row nw"><span class="eyebrow fl">' + ui.icon("steth", "sm") + L("With you now", "معك الآن", "अभी आपके साथ") + '</span><span class="tok">' + now.tok + '</span></div><div class="row" style="margin:8px 0 14px">' + ui.av(nm(now).n, "lg") + '<div class="fl"><b style="font-size:16px">' + esc(nm(now).n) + '</b><div class="small muted">' + esc(nm(now).as + " · " + nm(now).r) + "</div></div>" + ui.pill(SPAY[now.pay][0], SPAY[now.pay][1]) + "</div>" +
      '<div class="cl-grid2">' + ui.btn(L("Open consult", "فتح الاستشارة", "परामर्श खोलें"), { cls: "lg blk", act: "cl-soloopen", arg: now.p || "p2", icon: "steth" }) + ui.btn(L("Collect payment", "تحصيل الدفع", "भुगतान लें"), { cls: "lg blk", go: "solo.money", icon: "wallet" }) + "</div>" : "") +
      '<div class="hr" style="margin:16px 0"></div>' + (next ? '<div class="row"><div class="fl"><span class="eyebrow">' + L("Next", "التالي", "अगला") + '</span><div class="row" style="margin-top:4px"><span class="cl-tok">' + next.tok + '</span><div><b>' + esc(nm(next).n) + '</b><div class="small muted">' + esc(nm(next).as + " · " + nm(next).r) + "</div></div></div></div></div>" +
      '<button class="btn pri blk cl-big" style="margin-top:14px" data-act="cl-callnext">' + ui.icon("play") + L("Call next", "استدعاء التالي", "अगला बुलाएँ") + " · " + next.tok + "</button>" : ui.note("ok", "Queue clear. Enjoy your coffee.")) + "</div>";
    var quick = '<div class="cl-quick">' + ui.btn(paused ? L("Resume queue", "استئناف", "क़तार फिर शुरू") : L("Pause queue", "إيقاف مؤقت", "क़तार रोकें"), { cls: "lg blk" + (paused ? " okb" : " dng"), act: "cl-pause", icon: paused ? "play" : "pause" }) +
      ui.btn(L("Running late", "متأخر", "देर हो रही है"), { cls: "lg blk", ov: "cldelay", arg: "15", icon: "clockh" }) + ui.btn(L("Add walk-in", "إضافة مراجع", "वॉक-इन जोड़ें"), { cls: "lg blk", ov: "walkin", icon: "userplus" }) + ui.btn(L("Settings", "الإعدادات", "सेटिंग्स"), { cls: "lg blk ghost", go: "solo.setup", icon: "sliders" }) + "</div>";
    var list = '<div class="cl-q">' + q.map(function (x) {
      var s = SQ[x.st] || ["mute", x.st], w = nm(x);
      return '<div class="cl-qi ' + (x.st === "inconsult" ? "now" : "") + '" style="' + (x.st === "completed" ? "opacity:.6" : "") + '"><span class="tok">' + x.tok + '</span><div class="t"><b>' + esc(w.n) + (x.isNew ? ' <span class="pill info" style="height:18px">New</span>' : "") + "</b><small>" + esc(w.as + " · " + w.r) + "</small><small>" + ui.icon(/WhatsApp/.test(x.src) ? "chat" : /check-in/.test(x.src) ? "qr" : /Connect|Web/.test(x.src) ? "globe" : "smartphone", "xs") + " " + esc(x.src) + (x.st === "booked" || x.st === "waiting" || x.st === "arrived" ? ' · <span class="ltr">ETA ' + (x.eta || 5) + " min</span>" : " · " + x.t) + "</small></div>" +
        '<div class="stack s4" style="align-items:flex-end">' + ui.pill(s[0], s[1]) + ui.pill(SPAY[x.pay][0], SPAY[x.pay][1], x.pay === "due" ? "wallet" : null) + "</div></div>";
    }).join("") + "</div>";
    var side = ui.card(L("Working for you today", "يعمل لأجلك اليوم", "आज आपके लिए"), '<div class="lst">' +
      ui.li('<span class="pill ok" style="width:32px;height:32px;padding:0;justify-content:center">' + ui.icon("chat", "sm") + "</span>", "12 bookings auto-accepted", "WhatsApp 9 · Nabd Connect 3 · 1 over cap → waitlist") +
      ui.li('<span class="pill info" style="width:32px;height:32px;padding:0;justify-content:center">' + ui.icon("qr", "sm") + "</span>", "4 self check-ins", "Door QR + geofence · no desk needed") +
      ui.li('<span class="pill acc" style="width:32px;height:32px;padding:0;justify-content:center">' + ui.icon("hourglass", "sm") + "</span>", "ETA shown as a range", "“Leave home now” sent to 3 patients") +
      ui.li('<span class="pill warn" style="width:32px;height:32px;padding:0;justify-content:center">' + ui.icon("wallet", "sm") + "</span>", NB.money(11500) + " collected", NB.money(2700) + " due · 3 patients", ui.icon("chevright", "sm faint flip"), { go: "solo.money" }) + "</div>", { icon: "sparkle", cls: "cl-lw" });
    if (c.mobile) return h + hero + quick + '<h2 class="h">' + L("Queue", "الطابور", "क़तार") + "</h2>" + list + side;
    return h + '<div class="split wide"><div class="stack s16">' + hero + '<h2 class="h">' + L("Queue", "الطابور", "क़तार") + "</h2>" + list + '</div><div class="stack s16">' + quick + side + "</div></div>";
  });
  NB.act("cl-callnext", function () {
    var q = soloQ(), nx = q.find(function (x) { return x.st === "waiting" || x.st === "arrived"; }); if (!nx) return;
    var p = nx.p ? NB.pat(nx.p) : null; NB.setUI({ clSoloNow: nx.i, clSoloPat: nx.p || NB.v("clSoloPat", "p2") }); NB.toast(nx.tok + " " + (p ? p.name : nx.name) + " called · WhatsApp sent · ETAs updated");
  });
  NB.act("cl-pause", function () { var on = !NB.v("clPause", false); NB.setUI({ clPause: on }); NB.toast(on ? "Queue paused · neutral message sent to 5 patients" : "Queue resumed · ETAs recalculated"); });

  NB.screen("solo.money", function (c) {
    var m = NB.mkt(), gcc = NB.gcc(), meth = NB.v("clPayM", gcc ? "card" : "upi"), paid = NB.v("clPaid", false), p = NB.pat(NB.v("clSoloPat", "p2"));
    var fee = 500, taxR = m.id === "ksa" ? 15 : 0, tax = Math.round(fee * taxR) / 100, tot = fee + tax;
    var h = ui.header(L("Payments", "المدفوعات", "भुगतान"), "Today " + NB.money(11500 + (paid ? tot : 0)) + " collected · " + NB.money(2700 - (paid ? 0 : 0)) + " due");
    var items = '<dl class="cl-kv"><dt>Follow-up consultation</dt><dd class="num" style="text-align:end">' + NB.money(fee) + "</dd><dt>" + esc(m.tax) + (taxR ? " " + taxR + "%" : "") + '</dt><dd style="text-align:end">' + (taxR ? NB.money(tax) : '<span class="small faint">' + (m.id === "india" ? "Exempt (healthcare service)" : m.id === "uae" ? "Zero-rated healthcare" : "Not applicable") + "</span>") + "</dd></dl>";
    var methods = gcc ? [["card", L("Card / tap", "بطاقة", "कार्ड")], ["link", L("Pay link", "رابط الدفع", "पे लिंक")], ["cash", L("Cash", "نقدًا", "नकद")]] : [["upi", "UPI QR"], ["card", L("Card", "بطاقة", "कार्ड")], ["cash", L("Cash", "نقدًا", "नकद")]];
    var pay = meth === "upi" || meth === "link" ? '<div class="row nw" style="gap:16px;align-items:center">' + qr("pay" + tot + meth, c.mobile ? 128 : 148) + '<div class="stack s4"><span class="small muted">' + (meth === "upi" ? "Scan with any UPI app" : "Scan or open the WhatsApp link") + '</span><span class="cl-amt">' + NB.money(tot) + '</span><span class="small faint ltr">' + (meth === "upi" ? "drmeera.skin@okhdfc" : "pay.nabd.health/x7K2") + "</span>" + ui.pill("sync", L("Waiting for payment", "بانتظار الدفع", "भुगतान का इंतज़ार")) + "</div></div>"
      : meth === "card" ? ui.note("info", "Tap, insert or swipe on the " + (gcc ? "Network International" : "Pine Labs") + " terminal. Amount " + NB.money(tot) + " is sent to the terminal automatically.") : '<div class="fgrid">' + ui.field("Received", "1000") + ui.field("Change to return", "500") + "</div>";
    var collect = '<div class="card hl"><div class="ch"><span class="eyebrow">' + ui.icon("wallet", "sm") + L("Collect now", "حصّل الآن", "अभी लें") + '</span><span class="tok">T-14</span></div><div class="row" style="margin-bottom:12px">' + ui.av(p.name) + '<b class="fl">' + pn(p) + "</b>" + (paid ? ui.pill("ok", "Paid") : ui.pill("warn", "Unpaid")) + "</div>" + items +
      '<div class="row sb" style="margin:12px 0"><b>Total</b><span class="cl-amt" style="font-size:24px">' + NB.money(tot) + "</span></div>" + (paid ? ui.note("ok", "Paid by " + (meth === "upi" ? "UPI" : meth) + " · receipt sent on WhatsApp" + (m.id === "ksa" ? " · ZATCA QR on invoice" : "")) :
      ui.seg("clPayM", methods, gcc ? "card" : "upi") + '<div style="margin-top:14px">' + pay + "</div>" + '<div class="cl-grid2" style="margin-top:14px">' + ui.btn(L("Mark paid", "تم الدفع", "भुगतान हुआ"), { cls: "pri lg blk", act: "cl-paid", icon: "check" }) + ui.btn(L("Send link on WhatsApp", "أرسل الرابط", "व्हाट्सऐप पर लिंक"), { cls: "lg blk", act: "toast", arg: "Payment link sent · patient can pay from home", icon: "send" }) + "</div>") + "</div>";
    var by = gcc ? [["Card / tap", 6200, 7], ["Pay link", 3100, 4], ["Cash", 2200, 2]] : [["UPI", 7200, 9], ["Card", 2500, 1], ["Cash", 1800, 3]];
    var takings = ui.card(L("Today's takings", "إيرادات اليوم", "आज की कमाई"), '<div class="stack s8">' + by.map(function (b) { return '<div><div class="row sb small"><span>' + b[0] + " · " + b[2] + "</span>" + NB.money(b[1]) + '</div><div style="margin-top:4px">' + ui.bar(b[1] / 11500 * 100) + "</div></div>"; }).join("") +
      '<div class="row sb" style="margin-top:6px"><b>Total</b><b>' + NB.money(11500 + (paid ? tot : 0)) + "</b></div><span class=\"small faint\">+ 2 package sessions redeemed (no charge today)</span></div>" + '<div class="btns" style="margin-top:12px">' + ui.btn("Day close", { cls: "sm", act: "toast", arg: "Day closed · summary sent to you on WhatsApp", icon: "wallet" }) + ui.btn("Export", { cls: "sm ghost", act: "toast", arg: "Exported for your accountant", icon: "download" }) + "</div>", { icon: "chart" });
    var dues = ui.card(L("Dues", "المستحقات", "बकाया"), '<div class="lst">' + CD.dues.map(function (d) { return ui.li(ui.av(d[0]), esc(d[0]), esc(d[1] + " · " + d[3]), '<div class="stack s4" style="align-items:flex-end">' + NB.money(d[2]) + ui.btn("Remind", { cls: "sm ghost", act: "toast", arg: "Polite reminder with pay link sent to " + d[0] }) + "</div>"); }).join("") + "</div>", { icon: "receipt", cls: "cl-lw" });
    if (c.mobile) return h + collect + takings + dues;
    return h + '<div class="split wide">' + collect + '<div class="stack s16">' + takings + dues + "</div></div>";
  });
  NB.act("cl-paid", function () { NB.setUI({ clPaid: true }); NB.toast("Payment recorded · receipt sent on WhatsApp"); });

  NB.screen("solo.setup", function (c) {
    var eta = NB.v("clEta", "range"), cap = NB.v("clCap", "40"), lang = NB.v("clGrL", NB.gcc() ? "ar" : "en"), m = NB.mkt();
    var etaTxt = { exact: "Your turn is at 10:42.", range: "Your turn is expected between 10:35 and 10:50.", position: "You are 3rd in the queue.", delay: "Dr. Meera is running about 15 minutes late.", hidden: "We'll message you when it's nearly your turn." }[eta];
    var greet = { en: "Hello! Welcome to Dr. Meera's Skin Clinic. Reply 1 to book, 2 to see your queue, 3 for your prescription.", hi: "नमस्ते! डॉ. मीरा स्किन क्लिनिक में आपका स्वागत है। बुकिंग के लिए 1, क़तार के लिए 2, नुस्खे के लिए 3 भेजें।", ar: "مرحبًا بك في عيادة د. ميرا للجلدية. أرسل 1 للحجز، 2 لمعرفة دورك، 3 للوصفة." }[lang];
    var h = ui.header(L("My clinic settings", "إعدادات عيادتي", "मेरी क्लिनिक सेटिंग्स"), "The six things a solo clinic needs. Everything else has a sensible default.", ui.btn(L("Save", "حفظ", "सहेजें"), { cls: "pri", act: "toast", arg: "Settings saved · patients see changes immediately", icon: "check" }));
    h += ui.note("ok", "<b>Solo preset applied:</b> self check-in on, auto-accept on, no separate vitals step, payment after consult.");
    var cards = [
      ui.card("1 · " + L("Working hours", "ساعات العمل", "काम के घंटे"), [["Mon–Sat", "09:00–13:00", "17:00–20:00"], ["Sunday", "Closed", ""], ["Public holidays", "Closed", "auto from calendar"]].map(function (r) { return '<div class="row sb" style="padding:8px 0;border-bottom:1px solid var(--div)"><b class="small">' + r[0] + '</b><span class="small num ltr">' + r[1] + (r[2] ? " · " + r[2] : "") + "</span></div>"; }).join("") +
        ui.toggle("clHol", "Close bookings 1 day before leave", "Patients booked are offered the next free slot", true), { icon: "calendar", right: ui.btn("Edit", { cls: "sm ghost", act: "toast", arg: "Hours editor opened" }) }),
      ui.card("2 · " + L("Auto-accept rules", "قواعد القبول التلقائي", "ऑटो-स्वीकार नियम"), '<div class="stack s8">' + ui.radio("clAA", "cap", "Auto-accept up to my cap", "Over the cap → waitlist; offered a slot when one frees up", "cap") + ui.radio("clAA", "fu", "Auto-accept follow-ups only", "New patients wait for my approval", "cap") + ui.radio("clAA", "review", "Review each booking", "I get a WhatsApp with Accept / Decline", "cap") + "</div>" +
        ui.toggle("clAAw", "Hold 4 walk-in slots per session", "Released to bookings 1 hour before", true) + ui.toggle("clAAo", "New patients confirm with a phone OTP", "Cuts no-shows from fake bookings", true), { icon: "check" }),
      ui.card("3 · " + L("Max patients per day", "الحد الأقصى للمرضى يوميًا", "प्रतिदिन अधिकतम मरीज़"), ui.seg("clCap", [["30", "30"], ["40", "40"], ["50", "50"], ["60", "60"]], "40") + '<p class="small muted" style="margin:12px 0 0">Split: morning ' + Math.round(cap * 0.6) + " · evening " + Math.round(cap * 0.4) + ". Today: 28 of " + cap + " booked.</p>" + '<div style="margin-top:8px">' + ui.bar(28 / cap * 100, 28 / cap > 0.9 ? "warn" : "") + "</div>", { icon: "users" }),
      ui.card("4 · " + L("ETA shown to patients", "الوقت المتوقع للمرضى", "मरीज़ों को ETA"), '<div class="stack s8">' + [["exact", "Exact time", "Best when you are very predictable"], ["range", "Range (recommended)", "Honest when consults vary"], ["position", "Position only", "“You are 3rd”"], ["delay", "Delay only", "Only tell patients when I'm late"], ["hidden", "Hidden", "For bad days; a message near their turn"]].map(function (r) { return ui.radio("clEta", r[0], r[1], r[2], "range"); }).join("") + "</div>" +
        '<div class="cl-thread" style="margin-top:12px"><div class="cl-msg out">' + esc(etaTxt) + "<small>WhatsApp preview</small></div></div>", { icon: "hourglass" }),
      ui.card("5 · " + L("WhatsApp greeting", "تحية واتساب", "व्हाट्सऐप स्वागत"), '<div class="row" style="margin-bottom:10px">' + ui.seg("clGrL", [["en", "English"], ["hi", "हिंदी"], ["ar", "العربية"]], NB.gcc() ? "ar" : "en") + "</div>" +
        '<textarea class="cl-ta" dir="' + (lang === "ar" ? "rtl" : "ltr") + '">' + esc(greet) + "</textarea>" + '<p class="tiny faint" style="margin:8px 0 0">Sent from ' + (NB.gcc() ? "your verified business number" : "the shared Nabd number with your clinic name") + ". Health details are never put in a message preview.</p>", { icon: "chat", cls: "cl-card-g" }),
      ui.card("6 · " + L("Fees", "الرسوم", "फ़ीस"), '<dl class="cl-kv"><dt>New consultation</dt><dd style="text-align:end">' + NB.money(800) + "</dd><dt>Follow-up (within 7 days)</dt><dd style=\"text-align:end\">" + L("Free", "مجاني", "मुफ़्त") + "</dd><dt>Follow-up (after 7 days)</dt><dd style=\"text-align:end\">" + NB.money(500) + "</dd><dt>Teleconsult</dt><dd style=\"text-align:end\">" + NB.money(600) + "</dd><dt>" + esc(m.tax) + '</dt><dd style="text-align:end" class="small">' + esc(m.taxNote) + "</dd></dl>" +
        ui.toggle("clPre", "Ask new patients to prepay online", "Only for Nabd Connect bookings", false), { icon: "banknote" })
    ];
    return h + '<div class="grid g2">' + cards.join("") + "</div>";
  });
})();
