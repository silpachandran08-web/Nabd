/* Shared sample data for the prototype. All people and records are fictional examples.
   Modules may add their own data under NB.D.<module>, but should reuse these records so
   the same patient shows up consistently across reception, consult, pharmacy, lab and billing. */
window.NB = window.NB || {};
NB.D = {};

/* Markets decide currency, tax, national ID and integrations. Language only decides the script. */
NB.MARKETS = {
  india: { id: "india", name: "India", flag: "IN", cur: "₹", curCode: "INR", tax: "GST", taxRate: 18, taxNote: "Healthcare services exempt; pharmacy 5–12%, devices 12–18%",
    natId: "ABHA number", natId2: "Aadhaar (masked)", phone: "+91", tz: "Asia/Kolkata", city: "Bengaluru",
    hie: "ABDM (HIE-CM)", claims: "TPA / PM-JAY", einv: "GST e-invoice (B2B only)", eRx: "Signed e-Rx + QR", regulator: "NMC / State council",
    residency: "India region (Mumbai)", privacyLaw: "DPDP Act 2023", fx: 1 },
  uae: { id: "uae", name: "UAE", flag: "AE", cur: "AED", curCode: "AED", tax: "VAT", taxRate: 5, taxNote: "Healthcare zero-rated for qualifying services",
    natId: "Emirates ID", natId2: "Passport", phone: "+971", tz: "Asia/Dubai", city: "Dubai",
    hie: "NABIDH (Dubai) / Malaffi (Abu Dhabi)", claims: "DHA eClaimLink / Shafafiya", einv: "UAE e-invoicing (2027)", eRx: "DHA e-Prescription", regulator: "DHA / DoH / MOHAP",
    residency: "UAE region (in-country)", privacyLaw: "PDPL + Federal Law 2/2019", fx: 0.044 },
  ksa: { id: "ksa", name: "Saudi Arabia", flag: "SA", cur: "SAR", curCode: "SAR", tax: "VAT", taxRate: 15, taxNote: "ZATCA Fatoora phase 2 QR on every invoice",
    natId: "National ID / Iqama", natId2: "Passport", phone: "+966", tz: "Asia/Riyadh", city: "Riyadh",
    hie: "NPHIES / Seha", claims: "NPHIES", einv: "ZATCA Fatoora phase 2", eRx: "Wasfaty", regulator: "MOH / SCFHS",
    residency: "KSA region (in-Kingdom)", privacyLaw: "PDPL (SDAIA)", fx: 0.045 },
  qatar: { id: "qatar", name: "Qatar", flag: "QA", cur: "QAR", curCode: "QAR", tax: "No VAT", taxRate: 0, taxNote: "No VAT in Qatar today",
    natId: "QID", natId2: "Passport", phone: "+974", tz: "Asia/Qatar", city: "Doha",
    hie: "MOPH HIE", claims: "Insurer portals", einv: "—", eRx: "MOPH e-Rx", regulator: "MOPH / QCHP",
    residency: "Qatar region", privacyLaw: "Law No. 13 of 2016", fx: 0.044 }
};

/* Facility models. The same product scales from one doctor to a hospital chain. */
NB.FACILITY_TYPES = {
  solo: { id: "solo", name: "Solo clinic", desc: "One doctor, no front desk. The doctor runs queue, consult and billing from one screen or a phone." },
  clinic: { id: "clinic", name: "Specialty clinic", desc: "2–5 doctors, one receptionist, optional nurse." },
  poly: { id: "poly", name: "Polyclinic", desc: "Multi-specialty, departments, in-house pharmacy and lab." },
  hospital: { id: "hospital", name: "Hospital / chain", desc: "Multiple facilities, OPD + IPD, wards, OT, insurance desk." }
};

NB.D.facilities = {
  india: [
    { id: "f1", name: "Suvidha Multi-Speciality Clinic", area: "Koramangala, Bengaluru", type: "poly" },
    { id: "f2", name: "Suvidha Hospital", area: "Whitefield, Bengaluru", type: "hospital" },
    { id: "f3", name: "Dr. Meera's Skin Clinic", area: "Indiranagar, Bengaluru", type: "solo" }
  ],
  uae: [
    { id: "f1", name: "Al Noor Polyclinic", area: "Al Barsha, Dubai", type: "poly" },
    { id: "f2", name: "Al Noor Hospital", area: "Khalifa City, Abu Dhabi", type: "hospital" },
    { id: "f3", name: "Dr. Khalid Dental Studio", area: "Jumeirah, Dubai", type: "solo" }
  ],
  ksa: [
    { id: "f1", name: "Al Noor Medical Complex", area: "Al Olaya, Riyadh", type: "poly" },
    { id: "f2", name: "Al Noor Hospital", area: "Al Malqa, Riyadh", type: "hospital" },
    { id: "f3", name: "Dr. Khalid Dental Clinic", area: "Al Rawdah, Jeddah", type: "solo" }
  ],
  qatar: [
    { id: "f1", name: "Al Noor Polyclinic", area: "Al Sadd, Doha", type: "poly" },
    { id: "f2", name: "Al Noor Hospital", area: "West Bay, Doha", type: "hospital" },
    { id: "f3", name: "Dr. Khalid Dental Studio", area: "The Pearl, Doha", type: "solo" }
  ]
};

NB.D.doctors = [
  { id: "d1", name: "Dr. Meera Iyer", ar: "د. ميرا آيير", spec: "Dermatology", dept: "Skin & Hair", room: "Room 3", avgMin: 9, status: "in", delay: 0, regNo: "KMC-48211" },
  { id: "d2", name: "Dr. Vikram Shah", ar: "د. فيكرام شاه", spec: "General Medicine", dept: "Medicine", room: "Room 1", avgMin: 7, status: "in", delay: 15, regNo: "KMC-39102" },
  { id: "d3", name: "Dr. Nisha Bhat", ar: "د. نيشا بهات", spec: "Paediatrics", dept: "Child Health", room: "Room 2", avgMin: 11, status: "in", delay: 0, regNo: "KMC-51877" },
  { id: "d4", name: "Dr. Khalid Al-Saeed", ar: "د. خالد السعيد", spec: "Cardiology", dept: "Heart Centre", room: "Room 6", avgMin: 14, status: "break", delay: 0, regNo: "SCFHS-22918" },
  { id: "d5", name: "Dr. Fatima Rahman", ar: "د. فاطمة رحمن", spec: "Obstetrics & Gynaecology", dept: "Women's Health", room: "Room 5", avgMin: 12, status: "in", delay: 5, regNo: "DHA-P-77120" }
];

/* Patients: one global Nabd person, linked to a record in each facility they visited. */
NB.D.patients = [
  { id: "p1", mrn: "SUV-10231", name: "Kavya Reddy", ar: "كافيا ريدي", age: 34, sex: "F", phone: "98450 11223", lang: "en", allergies: [], conditions: ["Acne vulgaris"], tags: [], ins: null },
  { id: "p2", mrn: "SUV-10232", name: "Ananya Rao", ar: "أنانيا راو", age: 32, sex: "F", phone: "99001 22334", lang: "en", allergies: [], conditions: ["Melasma"], tags: ["Pregnant · 14 wk"], ins: null },
  { id: "p3", mrn: "SUV-10233", name: "Rakesh Menon", ar: "راكيش مينون", age: 58, sex: "M", phone: "98860 33445", lang: "en", allergies: [{ a: "Penicillin", sev: "Severe", rx: "Anaphylaxis 2019" }], conditions: ["Type 2 diabetes", "Hypertension"], tags: ["Diabetic"], ins: { name: "Star Health", pol: "SH-882190", tpa: "MediAssist" } },
  { id: "p4", mrn: "SUV-10234", name: "Zoya Khan", ar: "زويا خان", age: 6, sex: "F", phone: "98451 44556", lang: "hi", allergies: [{ a: "Egg", sev: "Moderate", rx: "Hives" }], conditions: [], tags: ["Minor · guardian Imran Khan"], ins: null },
  { id: "p5", mrn: "SUV-10235", name: "Suresh Pillai", ar: "سوريش بيلاي", age: 45, sex: "M", phone: "97400 55667", lang: "en", allergies: [], conditions: [], tags: ["Package 3/6"], ins: null },
  { id: "p6", mrn: "SUV-10236", name: "Fatima Sheikh", ar: "فاطمة شيخ", age: 29, sex: "F", phone: "99860 66778", lang: "en", allergies: [{ a: "Sulfonamides", sev: "Moderate", rx: "Rash" }], conditions: ["Hypothyroidism"], tags: [], ins: { name: "HDFC Ergo", pol: "HE-55120", tpa: "FHPL" } },
  { id: "p7", mrn: "SUV-10237", name: "Arjun Nair", ar: "أرجون ناير", age: 12, sex: "M", phone: "98455 77889", lang: "en", allergies: [], conditions: ["Asthma"], tags: ["Minor"], ins: null },
  { id: "p8", mrn: "SUV-10238", name: "Mohammed Ali", ar: "محمد علي", age: 67, sex: "M", phone: "98800 88990", lang: "en", allergies: [{ a: "Aspirin", sev: "Mild", rx: "Gastric upset" }], conditions: ["Hypertension", "CKD stage 3"], tags: ["Elderly", "Caregiver: son in Dubai"], ins: { name: "PM-JAY", pol: "PMJAY-77120", tpa: "NHA" } },
  { id: "p9", mrn: "SUV-10239", name: "Priya Sharma", ar: "بريا شارما", age: 41, sex: "F", phone: "98861 99001", lang: "hi", allergies: [], conditions: ["Migraine"], tags: [], ins: null },
  { id: "p10", mrn: "SUV-10240", name: "Abdullah Al-Harbi", ar: "عبدالله الحربي", age: 52, sex: "M", phone: "50 123 4567", lang: "ar", allergies: [], conditions: ["Dyslipidaemia"], tags: ["Insured"], ins: { name: "Bupa Arabia", pol: "BA-1192034", tpa: "NPHIES" } }
];

/* Today's live queue. eta = minutes until consult starts (rolling estimate from each doctor's consult-time distribution). */
NB.D.queue = [
  { tok: "T-13", p: "p1", d: "d1", type: "Follow-up", src: "WhatsApp", arr: "08:55", st: "completed", wait: 0, eta: 0, reason: "Acne review" },
  { tok: "T-14", p: "p2", d: "d1", type: "Follow-up", src: "Web", arr: "09:12", st: "inconsult", wait: 8, eta: 0, reason: "Melasma follow-up" },
  { tok: "T-15", p: "p3", d: "d2", type: "Walk-in", src: "Desk", arr: "09:20", st: "waiting", wait: 16, eta: 22, reason: "Fever, 3 days", flag: "urg" },
  { tok: "T-16", p: "p4", d: "d3", type: "Appointment", src: "WhatsApp", arr: "09:24", st: "vitals", wait: 12, eta: 6, reason: "Immunisation due" },
  { tok: "T-17", p: "p5", d: "d1", type: "Package", src: "App", arr: "09:31", st: "waiting", wait: 5, eta: 9, reason: "Laser session 4/6" },
  { tok: "T-18", p: "p6", d: "d2", type: "Appointment", src: "WhatsApp", arr: "09:35", st: "checkout", wait: 0, eta: 0, reason: "Thyroid panel review" },
  { tok: "T-19", p: "p7", d: "d3", type: "Walk-in", src: "Desk", arr: "09:40", st: "waiting", wait: 2, eta: 17, reason: "Ear pain, right", flag: "crit" },
  { tok: "T-20", p: "p8", d: "d2", type: "Appointment", src: "Caregiver", arr: "09:44", st: "waiting", wait: 1, eta: 36, reason: "BP review" },
  { tok: "T-21", p: "p9", d: "d1", type: "Appointment", src: "Self check-in", arr: "09:52", st: "arrived", wait: 0, eta: 18, reason: "Hair fall" },
  { tok: "T-22", p: "p10", d: "d4", type: "Appointment", src: "Nabd Connect", arr: "—", st: "booked", wait: 0, eta: 48, reason: "Lipid review" }
];

/* Booking requests waiting for manual acceptance (clinic chose "Review each booking"). */
NB.D.requests = [
  { id: "r1", name: "Deepa Krishnan", phone: "98450 22110", d: "d1", slot: "Today 11:40", src: "WhatsApp", reason: "Rash on arms, 1 week", new: true, age: 27, mins: 3 },
  { id: "r2", name: "Rahul Verma", phone: "99000 11334", d: "d2", slot: "Today 12:10", src: "Nabd Connect", reason: "Follow-up BP", new: false, age: 49, mins: 7 },
  { id: "r3", name: "Sara Thomas (for son Aaron, 4)", phone: "98861 55102", d: "d3", slot: "Today 16:20", src: "WhatsApp", reason: "Cough and fever", new: true, age: 4, mins: 12, guardian: true },
  { id: "r4", name: "Imtiaz Ahmed", phone: "97400 44120", d: "d4", slot: "Tomorrow 10:00", src: "Web", reason: "Chest discomfort on exertion", new: true, age: 61, mins: 18, flag: "Clinical triage suggested" }
];

/* Drug master (subset). sched: India schedule H/H1/X; preg: pregnancy category; cls: drug class for allergy cross-check. */
NB.D.drugs = [
  { id: "m1", name: "Amoxicillin 500 mg capsule", gen: "Amoxicillin", cls: "Penicillin", sched: "H", preg: "B", price: 6.5, unit: "cap" },
  { id: "m2", name: "Azithromycin 500 mg tablet", gen: "Azithromycin", cls: "Macrolide", sched: "H", preg: "B", price: 21, unit: "tab" },
  { id: "m3", name: "Paracetamol 650 mg tablet", gen: "Paracetamol", cls: "Analgesic", sched: "OTC", preg: "B", price: 2.1, unit: "tab" },
  { id: "m4", name: "Metformin 500 mg tablet", gen: "Metformin", cls: "Biguanide", sched: "H", preg: "B", price: 1.8, unit: "tab" },
  { id: "m5", name: "Amlodipine 5 mg tablet", gen: "Amlodipine", cls: "CCB", sched: "H", preg: "C", price: 3.2, unit: "tab" },
  { id: "m6", name: "Isotretinoin 20 mg capsule", gen: "Isotretinoin", cls: "Retinoid", sched: "H1", preg: "X", price: 38, unit: "cap" },
  { id: "m7", name: "Cetirizine 10 mg tablet", gen: "Cetirizine", cls: "Antihistamine", sched: "OTC", preg: "B", price: 1.5, unit: "tab" },
  { id: "m8", name: "Levothyroxine 50 mcg tablet", gen: "Levothyroxine", cls: "Thyroid", sched: "H", preg: "A", price: 1.9, unit: "tab" },
  { id: "m9", name: "Ibuprofen 400 mg tablet", gen: "Ibuprofen", cls: "NSAID", sched: "OTC", preg: "D (3rd tri)", price: 2.4, unit: "tab" },
  { id: "m10", name: "Alprazolam 0.25 mg tablet", gen: "Alprazolam", cls: "Benzodiazepine", sched: "H1", preg: "D", price: 4.8, unit: "tab" },
  { id: "m11", name: "Salbutamol 100 mcg inhaler", gen: "Salbutamol", cls: "Beta-agonist", sched: "H", preg: "C", price: 145, unit: "inh" },
  { id: "m12", name: "Atorvastatin 10 mg tablet", gen: "Atorvastatin", cls: "Statin", sched: "H", preg: "X", price: 5.6, unit: "tab" }
];

/* Stock batches for FEFO dispensing and expiry alerts. */
NB.D.batches = [
  { drug: "m1", batch: "AMX2408", exp: "2026-11-30", qty: 120, loc: "A-03", mrp: 7.2 },
  { drug: "m1", batch: "AMX2502", exp: "2027-06-30", qty: 400, loc: "A-03", mrp: 7.2 },
  { drug: "m3", batch: "PCM2501", exp: "2027-01-31", qty: 860, loc: "B-01", mrp: 2.4 },
  { drug: "m6", batch: "ISO2412", exp: "2026-10-31", qty: 30, loc: "H1-02", mrp: 42 },
  { drug: "m10", batch: "ALP2503", exp: "2027-03-31", qty: 60, loc: "H1-01", mrp: 5.4 },
  { drug: "m4", batch: "MET2410", exp: "2026-12-31", qty: 18, loc: "C-02", mrp: 2.0 },
  { drug: "m11", batch: "SAL2501", exp: "2027-02-28", qty: 9, loc: "D-04", mrp: 160 },
  { drug: "m12", batch: "ATV2409", exp: "2026-12-15", qty: 240, loc: "C-05", mrp: 6.2 }
];

NB.D.labTests = [
  { code: "CBC", name: "Complete blood count", tat: 120, price: 350, sample: "EDTA blood" },
  { code: "HBA1C", name: "HbA1c", tat: 180, price: 550, sample: "EDTA blood" },
  { code: "LIPID", name: "Lipid profile", tat: 240, price: 650, sample: "Serum (fasting)" },
  { code: "TSH", name: "Thyroid stimulating hormone", tat: 240, price: 450, sample: "Serum" },
  { code: "KFT", name: "Kidney function test", tat: 180, price: 700, sample: "Serum" },
  { code: "URINE", name: "Urine routine", tat: 60, price: 200, sample: "Urine" },
  { code: "CRP", name: "C-reactive protein", tat: 120, price: 500, sample: "Serum" }
];

/* Consent records: cross-facility record sharing. Needs patient AND treating-doctor approval; always time-limited. */
NB.D.consents = [
  { id: "C-2041", p: "p8", from: "Suvidha Hospital (Cardiology)", to: "Suvidha Multi-Speciality Clinic", by: "Dr. Vikram Shah", purpose: "Continuity of care", data: ["Encounters", "Prescriptions", "Lab results"], range: "Last 12 months", st: "pending-doctor", pt: "Approved via WhatsApp OTP · 09:41", dr: "Waiting for Dr. Khalid Al-Saeed", until: "—", reads: 0 },
  { id: "C-2038", p: "p3", from: "Apollo Clinic, HSR Layout", to: "Suvidha Multi-Speciality Clinic", by: "Dr. Vikram Shah", purpose: "Referral — diabetic foot", data: ["Lab results", "Discharge summary"], range: "Since Jan 2025", st: "active", pt: "Approved in app · 14 Oct", dr: "Approved by Dr. Anil Kumar · 14 Oct", until: "21 Oct 2026, 18:00", reads: 3 },
  { id: "C-2031", p: "p6", from: "Suvidha Multi-Speciality Clinic", to: "Cloudnine Hospital", by: "Dr. Shreya Patil", purpose: "Antenatal care", data: ["Encounters", "Allergies", "Lab results"], range: "Last 24 months", st: "expired", pt: "Approved via WhatsApp · 01 Oct", dr: "Approved by Dr. Fatima Rahman · 01 Oct", until: "08 Oct 2026", reads: 5 },
  { id: "C-2029", p: "p1", from: "Suvidha Multi-Speciality Clinic", to: "Kaya Skin Clinic", by: "Dr. Rohan Das", purpose: "Second opinion", data: ["Encounters", "Photos"], range: "Last 6 months", st: "denied", pt: "Declined by patient · 28 Sep", dr: "—", until: "—", reads: 0 }
];

NB.D.beds = [
  { ward: "General Ward A", beds: [
    { no: "A-01", st: "occ", p: "Ramesh Gowda", age: 63, dx: "Pneumonia", doc: "Dr. Vikram Shah", los: 3, news: 4 },
    { no: "A-02", st: "occ", p: "Lakshmi Devi", age: 71, dx: "CHF exacerbation", doc: "Dr. Khalid Al-Saeed", los: 5, news: 6, crit: true },
    { no: "A-03", st: "free" },
    { no: "A-04", st: "clean", note: "Cleaning · ready 10:40" },
    { no: "A-05", st: "occ", p: "Joseph Mathew", age: 49, dx: "Cellulitis, left leg", doc: "Dr. Vikram Shah", los: 2, news: 2, dis: true },
    { no: "A-06", st: "occ", p: "Shabana Begum", age: 55, dx: "DKA — resolving", doc: "Dr. Vikram Shah", los: 4, news: 3 }
  ]},
  { ward: "Paediatric Ward", beds: [
    { no: "P-01", st: "occ", p: "Aarav S.", age: 3, dx: "Bronchiolitis", doc: "Dr. Nisha Bhat", los: 1, news: 3 },
    { no: "P-02", st: "free" },
    { no: "P-03", st: "reserved", note: "Reserved · admission from OPD T-19" },
    { no: "P-04", st: "free" }
  ]},
  { ward: "ICU", beds: [
    { no: "ICU-1", st: "occ", p: "Venkatesh Rao", age: 68, dx: "Post-op CABG day 2", doc: "Dr. Khalid Al-Saeed", los: 2, news: 7, crit: true },
    { no: "ICU-2", st: "occ", p: "Meenakshi P.", age: 59, dx: "Sepsis", doc: "Dr. Vikram Shah", los: 6, news: 5 },
    { no: "ICU-3", st: "free" }
  ]}
];

/* Helpers to look up records. */
NB.pat = function (id) { return NB.D.patients.find(function (p) { return p.id === id; }) || {}; };
NB.doc = function (id) { return NB.D.doctors.find(function (d) { return d.id === id; }) || {}; };
NB.drug = function (id) { return NB.D.drugs.find(function (d) { return d.id === id; }) || {}; };
