/* Lab module (role: lab). Runs as an in-house lab or a standalone diagnostic lab (toggle NB.v("lbStandalone")).
   Screens: worklist, sample collection, result entry, validation & release, tests/panels/QC.
   Overlays: labresult (order detail), criticalcall (call-back log), labreport (PDF preview), lbprint (labels).
   All data is fictional and lives under NB.D.lab. */
(function () {
  "use strict";
  var ui = NB.ui, L = NB.L, esc = ui.esc;

  /* ---------------- Styles (lb- prefix, nb.css variables only) ---------------- */
  (function () {
    if (document.getElementById("lb-css")) return;
    var s = document.createElement("style"); s.id = "lb-css";
    s.textContent = [
      ".lb-mode{display:flex;align-items:center;gap:12px 16px;flex-wrap:wrap;padding:12px 16px;border:1px solid var(--b-sub);border-radius:var(--r-lg);background:var(--sheen),var(--s1)}",
      ".lb-mode.sa{border-color:rgba(88,178,245,.35)}",
      ".lb-mi{width:36px;height:36px;border-radius:10px;display:grid;place-items:center;background:var(--acc-tint);color:var(--acc);flex:none}.lb-mode.sa .lb-mi{background:var(--info-t);color:var(--info)}",
      ".lb-mode .t{flex:1;min-width:220px}.lb-mode .t b{font-size:13.5px;font-weight:600}.lb-mode .t small{display:block;color:var(--tx3);font-size:12px;line-height:17px;margin-top:2px}",
      ".lb-sw{display:inline-flex;align-items:center;gap:10px;cursor:pointer;font-size:12.5px;font-weight:600;color:var(--tx2);padding:6px 10px;border-radius:999px;border:1px solid var(--b-def);background:var(--s2)}",
      ".lb-pipe{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:6px}",
      ".lb-st{display:flex;flex-direction:column;gap:2px;padding:10px 12px;border-radius:var(--r-md);background:var(--s1);border:1px solid var(--b-sub);cursor:pointer;text-align:start;min-width:0;position:relative}",
      ".lb-st:hover{border-color:var(--b-def)}.lb-st.on{border-color:var(--acc-line);background:var(--acc-tint)}",
      ".lb-st b{font:600 20px/26px var(--font);font-variant-numeric:tabular-nums}.lb-st small{font-size:11.5px;color:var(--tx3);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
      ".lb-st .ico{position:absolute;top:10px;inset-inline-end:10px;color:var(--tx4)}",
      ".lb-tat{min-width:120px}.lb-tat .bar{height:6px;margin-top:4px}",
      ".lb-tube{display:inline-block;width:12px;height:22px;border-radius:3px 3px 6px 6px;border:1px solid var(--b-str);position:relative;flex:none;vertical-align:middle;background:var(--s3)}",
      ".lb-tube::before{content:'';position:absolute;inset:-1px -1px auto;height:7px;border-radius:3px 3px 0 0;background:var(--c)}",
      ".lb-tubes{display:flex;flex-direction:column}.lb-tubes .r{display:flex;align-items:center;gap:12px;padding:10px 0;border-bottom:1px solid var(--div)}.lb-tubes .r:last-child{border-bottom:0}",
      ".lb-tubes .r b{display:block;font-size:13.5px;font-weight:500}.lb-tubes .r small{display:block;font-size:12px;color:var(--tx3)}",
      ".lb-labels{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:10px}",
      ".lb-label{background:var(--tx);color:var(--bg-root);border-radius:8px;padding:10px 12px;font-size:11px;line-height:15px;min-width:0;direction:ltr;text-align:left}",
      ".lb-label b{font-size:12.5px;display:block}.lb-label svg{display:block;width:100%;height:34px;margin:6px 0 2px}.lb-label svg rect{fill:var(--bg-root)}.lb-label .mt{color:var(--tx4)}",
      ".lb-in{width:90px;height:32px;border-radius:8px;border:1px solid var(--b-def);background:var(--s3);padding:0 8px;font-size:13px;font-weight:600;font-variant-numeric:tabular-nums}",
      ".lb-in.h{border-color:rgba(232,179,60,.6)}.lb-in.c{border-color:rgba(255,77,79,.7);box-shadow:0 0 0 3px var(--crit-t)}",
      ".lb-src{display:inline-flex;align-items:center;gap:4px;font-size:11px;color:var(--tx3)}",
      ".lb-qc{width:100%;height:auto;display:block}.lb-qc text{fill:var(--tx3);font:500 10px var(--font)}",
      ".lb-qc .m{stroke:var(--tx2);stroke-width:1}.lb-qc .s1{stroke:var(--tx4);stroke-dasharray:2 3}.lb-qc .s2{stroke:var(--warn);stroke-dasharray:5 4;opacity:.8}.lb-qc .s3{stroke:var(--crit);stroke-dasharray:5 4;opacity:.8}",
      ".lb-qc .ln{fill:none;stroke:var(--tx2);stroke-width:1.5}.lb-qc .pt{fill:var(--s1);stroke:var(--tx);stroke-width:1.5}.lb-qc .bad{fill:var(--crit);stroke:var(--crit)}.lb-qc .wn{fill:var(--warn);stroke:var(--warn)}",
      ".lb-qc .band{fill:var(--crit-t)}",
      ".lb-rep{background:var(--tx);color:var(--bg-root);border-radius:var(--r-md);padding:20px;font-size:12px;line-height:17px}",
      ".lb-rep h3{margin:0;font-size:15px}.lb-rep table{width:100%;border-collapse:collapse;margin-top:10px}.lb-rep th{text-align:start;font-size:10.5px;text-transform:uppercase;letter-spacing:.4px;color:var(--tx4);border-bottom:1px solid rgba(0,0,0,.2);padding:6px 4px}",
      ".lb-rep td{padding:6px 4px;border-bottom:1px solid rgba(0,0,0,.08)}.lb-rep .fl{font-weight:700}.lb-rep .hd{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;border-bottom:2px solid var(--bg-root);padding-bottom:10px}",
      ".lb-rep .mt{color:var(--tx4)}.lb-qr{line-height:0;display:inline-block}.lb-qr svg rect{fill:var(--bg-root)}",
      ".lb-wa{background:var(--wa-out);border-radius:10px;padding:10px 12px;font-size:13px;line-height:19px;max-width:340px;color:var(--tx)}",
      ".lb-rc{display:flex;flex-direction:column;gap:8px;padding:14px;border-radius:var(--r-lg);border:1px solid var(--b-sub);background:var(--s1);cursor:pointer}",
      ".lb-pick{display:flex;flex-direction:column;gap:2px;padding:10px 12px;border-radius:var(--r-md);border:1px solid var(--b-sub);background:var(--s1);cursor:pointer;text-align:start;width:100%}",
      ".lb-pick.on{border-color:var(--acc-line);background:var(--acc-tint)}.lb-pick b{font-size:13.5px;font-weight:500}.lb-pick small{font-size:12px;color:var(--tx3)}",
      ".frame.tablet .lb-pipe{grid-template-columns:repeat(4,minmax(0,1fr))}",
      ".frame.mobile .lb-pipe{grid-template-columns:repeat(2,minmax(0,1fr))}",
      "@media (max-width:760px){.lb-pipe{grid-template-columns:repeat(2,minmax(0,1fr))}.lb-mode .t{min-width:0;flex-basis:100%}}"
    ].join("\n");
    document.head.appendChild(s);
  })();

  /* ---------------- Helpers ---------------- */
  var NOW = 10 * 60 + 30; // 10:30 on Sat 17 Oct 2026
  function hm(m) { return String(Math.floor(m / 60)).padStart(2, "0") + ":" + String(m % 60).padStart(2, "0"); }
  function mins(t) { var p = t.split(":"); return +p[0] * 60 + +p[1]; }
  function n(x) { return '<span class="num ltr">' + x + "</span>"; }
  function standalone() { return !!NB.v("lbStandalone", false); }
  function india() { return NB.state.market === "india"; }
  function si() { return NB.v("lbUnits", NB.gcc() ? "si" : "conv") === "si"; }

  /* ---------------- Data (NB.D.lab) ---------------- */
  var D = NB.D.lab = {
    tests: NB.D.labTests.map(function (t) { return Object.assign({}, t); }).concat([
      { code: "FBS", name: "Fasting blood glucose", tat: 60, price: 100, sample: "Fluoride plasma" },
      { code: "LFT", name: "Liver function test", tat: 180, price: 650, sample: "Serum" },
      { code: "TROP", name: "Troponin I (high-sensitivity)", tat: 60, price: 1200, sample: "Serum" },
      { code: "NS1", name: "Dengue NS1 antigen", tat: 120, price: 600, sample: "Serum" },
      { code: "VITD", name: "Vitamin D (25-OH)", tat: 1440, price: 1400, sample: "Serum", out: true },
      { code: "PTINR", name: "Prothrombin time / INR", tat: 90, price: 350, sample: "Citrate plasma" },
      { code: "HBSAG", name: "Hepatitis B surface antigen", tat: 180, price: 400, sample: "Serum" },
      { code: "UCULT", name: "Urine culture & sensitivity", tat: 4320, price: 800, sample: "Urine (sterile)", out: true },
      { code: "HPE", name: "Histopathology, small biopsy", tat: 7200, price: 1800, sample: "Tissue in formalin", out: true }
    ]),
    method: { CBC: "Flow cytometry, 5-part", HBA1C: "HPLC", LIPID: "Enzymatic", TSH: "CLIA", KFT: "Enzymatic / ISE", URINE: "Strip + microscopy", CRP: "Immunoturbidimetry", FBS: "Hexokinase", LFT: "Photometry", TROP: "CLIA", NS1: "ELISA", VITD: "CLIA", PTINR: "Clotting", HBSAG: "CLIA", UCULT: "Culture (reference lab)", HPE: "H&E (reference lab)" },
    nabl: { CBC: 1, HBA1C: 1, LIPID: 1, TSH: 1, KFT: 1, CRP: 1, FBS: 1, LFT: 1, URINE: 1 },
    panels: [
      { name: "Diabetes care panel", tests: ["FBS", "HBA1C", "LIPID", "KFT", "URINE"], price: 1999 },
      { name: "Fever panel", tests: ["CBC", "CRP", "NS1", "URINE"], price: 1299 },
      { name: "Antenatal profile", tests: ["CBC", "TSH", "HBSAG", "URINE", "FBS"], price: 1499 },
      { name: "Executive health check", tests: ["CBC", "FBS", "HBA1C", "LIPID", "KFT", "LFT", "TSH", "URINE", "VITD"], price: 3999 }
    ]
  };
  function test(code) { return D.tests.find(function (t) { return t.code === code; }) || { code: code, name: code, tat: 120, price: 0, sample: "Serum" }; }

  // Analytes: ref is [lo, hi] or fn(patient); crit [lo, hi]; f converts conventional -> SI.
  var AN = {
    HB: { n: "Haemoglobin", u: "g/dL", ref: function (p) { return p.age < 12 ? [11.0, 14.5] : p.sex === "F" ? [12.0, 15.0] : [13.0, 17.0]; }, crit: [7, 20], dp: 1, d: { abs: 2 } },
    WBC: { n: "Total leucocyte count", u: "×10³/µL", ref: [4.0, 11.0], crit: [2, 30], dp: 1, d: { pct: 100 } },
    PLT: { n: "Platelet count", u: "×10³/µL", ref: [150, 410], crit: [20, 1000], dp: 0, d: { pct: 50 } },
    NEU: { n: "Neutrophils", u: "%", ref: [40, 75], dp: 0 },
    LYM: { n: "Lymphocytes", u: "%", ref: [20, 45], dp: 0 },
    CRP: { n: "C-reactive protein", u: "mg/L", ref: [0, 5], dp: 1 },
    UREA: { n: "Urea", u: "mg/dL", si: "mmol/L", f: 0.1665, ref: [17, 43], dp: 0 },
    CREA: { n: "Creatinine", u: "mg/dL", si: "µmol/L", f: 88.4, ref: function (p) { return p.sex === "F" ? [0.6, 1.1] : [0.7, 1.3]; }, dp: 2, d: { pct: 30 } },
    EGFR: { n: "eGFR (CKD-EPI 2021)", u: "mL/min/1.73m²", ref: [90, 200], dp: 0 },
    NA: { n: "Sodium", u: "mmol/L", ref: [136, 145], crit: [120, 160], dp: 0, d: { abs: 8 } },
    K: { n: "Potassium", u: "mmol/L", ref: [3.5, 5.1], crit: [2.8, 6.0], dp: 1, d: { abs: 1 } },
    HBA1C: { n: "HbA1c", u: "%", ref: [4.0, 5.6], dp: 1 },
    TSH: { n: "TSH", u: "mIU/L", ref: [0.4, 4.5], dp: 2 },
    GLU: { n: "Glucose, fasting", u: "mg/dL", si: "mmol/L", f: 0.0555, ref: [70, 99], crit: [40, 450], dp: 0 },
    CHOL: { n: "Total cholesterol", u: "mg/dL", si: "mmol/L", f: 0.02586, ref: [0, 200], dp: 0 },
    TG: { n: "Triglycerides", u: "mg/dL", si: "mmol/L", f: 0.01129, ref: [0, 150], dp: 0 },
    HDL: { n: "HDL cholesterol", u: "mg/dL", si: "mmol/L", f: 0.02586, ref: function (p) { return p.sex === "F" ? [50, 100] : [40, 100]; }, dp: 0 },
    LDL: { n: "LDL cholesterol (calc.)", u: "mg/dL", si: "mmol/L", f: 0.02586, ref: [0, 100], dp: 0 }
  };
  var PANEL_AN = { CBC: ["HB", "WBC", "PLT", "NEU", "LYM"], CRP: ["CRP"], KFT: ["UREA", "CREA", "EGFR", "NA", "K"], HBA1C: ["HBA1C"], TSH: ["TSH"], FBS: ["GLU"], LIPID: ["CHOL", "TG", "HDL", "LDL"] };
  var INSTR = { CBC: "HA-01", CRP: "CC-01", KFT: "CC-01", LIPID: "CC-01", HBA1C: "HP-01", TSH: "IA-01", FBS: "CC-01" };

  function ext(name, age, sex, phone, lang) { return { name: name, age: age, sex: sex, phone: phone, lang: lang || "en", ext: true }; }
  function orders() {
    var g = NB.gcc(), sa = standalone();
    var list = [
      { id: "LB-0141", p: "p3", d: "d2", tests: ["CBC", "CRP", "KFT"], pri: "stat", src: "opd", ord: "09:20", col: "09:41", rec: "09:48", st: "resulted", crit: true, note: "Fever 3 days, diabetic on metformin" },
      { id: "LB-0149", p: "p7", d: "d3", tests: ["CBC", "CRP"], pri: "stat", src: "opd", ord: "10:12", st: "ordered", note: "Wheeze, febrile" },
      { id: "LB-0142", p: "p4", d: "d3", tests: ["CBC"], pri: "routine", src: "opd", ord: "09:30", col: "09:52", st: "collected", note: "Capillary sample, paediatric" },
      { id: "LB-0143", p: "p8", d: "d2", tests: ["KFT", "LIPID"], pri: "routine", src: "opd", ord: "09:05", col: "09:20", rec: "09:28", st: "inprocess", note: "CKD stage 3, follow-up" },
      { id: "LB-0145", pt: g ? ext("Khalid Mansour", 39, "M", "50 771 2290") : ext("Sanjay Kulkarni", 39, "M", "98450 70112"), tests: ["CBC", "NS1", "CRP"], pri: "routine", src: "walkin", ord: "09:58", col: "10:06", rec: "10:10", st: "received", note: "Self-referred, fever 2 days" },
      { id: "LB-0147", p: "p10", d: "d4", tests: ["LIPID", "FBS"], pri: "routine", src: "opd", ord: "10:15", st: "ordered", fasting: true, note: "Dyslipidaemia review" },
      { id: "LB-0146", pt: g ? ext("Aisha Rahman", 74, "F", "55 210 4471", "ar") : ext("Thankamma Joseph", 74, "F", "94470 22118"), refBy: g ? "Dr. Huda Karim" : "Dr. Joseph Kurian", tests: ["HBA1C", "LIPID", "KFT"], pri: "routine", src: "home", ord: "08:00", col: "07:42", rec: "08:55", st: "inprocess", fasting: true, note: "Home collection, caregiver abroad gets the report" },
      { id: "LB-0144", p: "p6", d: "d2", tests: ["TSH"], pri: "routine", src: "opd", ord: "08:40", col: "08:52", rec: "09:01", st: "resulted", note: "On levothyroxine 50 mcg" },
      { id: "LB-0150", pt: g ? ext("Omar Saleh", 44, "M", "50 412 7781") : ext("Deepak Verma", 44, "M", "99011 23456"), refBy: g ? "Dr. Sameer Haddad" : "Dr. Anil Kumar", tests: ["UCULT"], pri: "routine", src: "ref", ord: "08:15", col: "08:30", rec: "08:35", st: "inprocess", out: true, note: "Sent to reference lab · pickup 11:00" },
      { id: "LB-0138", p: "p9", d: "d1", tests: ["CBC", "TSH"], pri: "routine", src: "opd", ord: "08:10", col: "08:22", rec: "08:30", st: "validated", note: "Hair fall work-up" },
      { id: "LB-0136", p: "p2", d: "d5", tests: ["CBC", "TSH", "HBSAG", "URINE"], pri: "routine", src: "opd", ord: "07:55", col: "08:05", rec: "08:12", st: "released", note: "Antenatal profile, 14 weeks" }
    ];
    if (sa) list.forEach(function (o) { if (o.src === "opd") { o.src = "ref"; o.refBy = NB.doc(o.d).name; o.refClinic = g ? "Al Noor Polyclinic" : "Suvidha Multi-Speciality Clinic"; } });
    return list;
  }
  function ord(id) { return orders().find(function (o) { return o.id === id; }) || orders()[0]; }
  function pat(o) { if (o.pt) return o.pt; var p = NB.pat(o.p); return { name: NB.pname(p), age: p.age, sex: p.sex, phone: p.phone, lang: p.lang, mrn: p.mrn, tags: p.tags || [], allergies: p.allergies || [], ins: p.ins }; }
  function tatOf(o) { return o.pri === "stat" ? 60 : Math.max.apply(null, o.tests.map(function (c) { return test(c).tat; })); }
  function dueOf(o) { return mins(o.ord) + tatOf(o); }
  function orderer(o) {
    if (o.d && !standalone()) { var d = NB.doc(o.d); return [NB.dname(d), d.spec]; }
    if (o.src === "walkin") return [L("Walk-in, self-referred", "مراجع بدون إحالة", "वॉक-इन, स्वयं"), L("Pays at registration", "يدفع عند التسجيل", "पंजीकरण पर भुगतान")];
    return [o.refBy || "—", o.refClinic || (o.src === "home" ? L("Home collection", "سحب منزلي", "होम कलेक्शन") : L("External referral", "إحالة خارجية", "बाहरी रेफ़रल"))];
  }

  var ST = [
    ["ordered", "Ordered", "مطلوب", "ऑर्डर", "mute", "clipboard"], ["collected", "Collected", "تم السحب", "सैंपल लिया", "info", "syringe"], ["received", "Received", "مستلم", "प्राप्त", "info", "box"],
    ["inprocess", "In process", "قيد التحليل", "प्रोसेस में", "sync", null], ["resulted", "Awaiting validation", "بانتظار التحقق", "सत्यापन बाकी", "warn", "eye"],
    ["validated", "Validated", "تم التحقق", "सत्यापित", "ok", "shieldcheck"], ["released", "Released", "صدر", "जारी", "acc", "send"]
  ];
  function stOf(k) { return ST.find(function (s) { return s[0] === k; }); }
  function stPill(k) { var s = stOf(k); return ui.pill(s[4], L(s[1], s[2], s[3]), s[5] || undefined); }
  function priPill(p) { return p === "stat" ? ui.pill("crit", "STAT", "bolt") : ui.pill("mute", L("Routine", "روتيني", "रूटीन"), "clockh"); }
  function srcCell(o) {
    var m = { opd: ["steth", L("OPD order", "طلب عيادة", "OPD ऑर्डर")], walkin: ["arrivals", L("Walk-in", "بدون موعد", "वॉक-इन")], home: ["home", L("Home collection", "سحب منزلي", "होम कलेक्शन")], ref: ["share", L("Referral", "إحالة", "रेफ़रल")] }[o.src];
    return ui.icon(m[0], "xs") + " " + m[1];
  }
  function tatCell(o) {
    var due = dueOf(o), left = due - NOW, tot = tatOf(o), done = o.st === "validated" || o.st === "released";
    if (done) return '<div class="lb-tat"><span class="small okc">' + ui.icon("checkcircle", "xs") + " " + L("Within TAT", "ضمن الوقت", "TAT में") + '</span><div class="small faint">' + L("due", "الموعد", "देय") + " " + n(hm(due % 1440)) + "</div></div>";
    if (o.out) return '<div class="lb-tat"><span class="small muted">' + ui.icon("truck", "xs") + " " + L("Reference lab", "مختبر مرجعي", "रेफ़रेंस लैब") + '</span><div class="small faint">' + L("due", "الموعد", "देय") + " 20 Oct</div></div>";
    var pct = Math.min(100, Math.max(0, (tot - left) / tot * 100));
    var lab = left < 0 ? '<b class="critc small">' + ui.icon("alertfill", "xs") + " " + L("Overdue", "متأخر", "देर") + " " + n(-left + " min") + "</b>" : left <= 20 ? '<b class="warnc small">' + n(left + " min") + " " + L("left", "متبقٍ", "बाकी") + "</b>"
      : '<span class="small">' + n(left >= 120 ? Math.floor(left / 60) + " h " + (left % 60) + " min" : left + " min") + " " + L("left", "متبقٍ", "बाकी") + "</span>";
    return '<div class="lb-tat">' + lab + ui.bar(pct, left < 0 ? "crit" : left <= 20 ? "warn" : "") + "</div>";
  }

  function modeBar() {
    var sa = standalone(), f = NB.fac(), ft = NB.state.facType;
    var nm = { india: ["Sanjeevani Diagnostics", "Vijay Nagar, Indore · 6 collection centres", "NABL (ISO 15189) track"], uae: ["Al Noor Diagnostic Laboratory", "Al Barsha, Dubai", "DHA clinical lab licence · CAP"], ksa: ["Nakheel Diagnostics", "Olaya, Riyadh", "CBAHI accredited"], qatar: ["Doha Diagnostic Lab", "Al Sadd, Doha", "MOPH lab licence"] }[NB.state.market];
    var kind = sa ? L("Standalone diagnostic lab", "مختبر تشخيصي مستقل", "स्टैंडअलोन डायग्नोस्टिक लैब") : ft === "hospital" ? L("Hospital laboratory", "مختبر المستشفى", "अस्पताल लैब") : L("In-house lab", "مختبر داخلي", "इन-हाउस लैब");
    var title = sa ? esc(nm[0]) + ' <span class="faint">· ' + esc(nm[1]) + "</span>" : esc(f.name) + ' <span class="faint">· ' + esc(NB.FACILITY_TYPES[ft].name) + "</span>";
    var sub = sa ? L("Orders arrive from referring doctors on Nabd, walk-ins and home collection; reports go to the referrer and the patient.", "تصل الطلبات من الأطباء المحيلين والزوار والسحب المنزلي.", "रेफ़र करने वाले डॉक्टरों, वॉक-इन और होम कलेक्शन से ऑर्डर आते हैं।") + " · " + esc(nm[2])
      : L("Orders come straight from the consult screen; results land in the doctor's inbox.", "تصل الطلبات من شاشة الاستشارة مباشرة.", "ऑर्डर सीधे कंसल्ट स्क्रीन से; रिपोर्ट डॉक्टर के इनबॉक्स में।") + " · " + esc(NB.mkt().hie);
    return '<div class="lb-mode' + (sa ? " sa" : "") + '"><span class="lb-mi">' + ui.icon(sa ? "flask" : "building") + '</span><div class="t"><div class="row">' + ui.pill(sa ? "info" : "acc", kind, sa ? "flask" : "building") + "<b>" + title + "</b></div><small>" + sub + "</small></div>" +
      '<div class="lb-sw" data-toggle="lbStandalone" role="switch" aria-checked="' + sa + '" tabindex="0"><span>' + L("Run as standalone lab", "تشغيل كمختبر مستقل", "स्टैंडअलोन लैब के रूप में चलाएँ") + '</span><span class="sw ' + (sa ? "on" : "") + '"></span></div></div>';
  }
  function chips(codes) { return '<div class="chips" style="gap:4px">' + codes.map(function (c) { return '<span class="pill mute" style="letter-spacing:.2px">' + esc(c) + "</span>"; }).join("") + "</div>"; }

  /* ================= SCREEN: Worklist ================= */
  NB.screen("lab.worklist", function (c) {
    var list = orders(), f = NB.v("lbSt", "open"), sa = standalone();
    var open = function (o) { return o.st !== "released"; };
    var rows = list.filter(function (o) { return f === "all" ? true : f === "open" ? open(o) : o.st === f; }).sort(function (a, b) { return (a.pri === "stat" ? -1000 : 0) + dueOf(a) - ((b.pri === "stat" ? -1000 : 0) + dueOf(b)); });
    var h = modeBar() + ui.header(L("Worklist", "قائمة العمل", "वर्कलिस्ट"), "Sat, 17 Oct 2026 · 10:30 · " + L("sorted by priority, then time left to TAT", "مرتبة حسب الأولوية ثم الوقت المتبقي", "प्राथमिकता, फिर बचे TAT के अनुसार"),
      ui.btn(L("Scan sample", "مسح عينة", "सैंपल स्कैन"), { cls: "pri", act: "toast", arg: "Scanned LB-0145-1 · marked received at 10:31", icon: "barcode" }) + ui.btn(L("New walk-in order", "طلب جديد", "नया वॉक-इन ऑर्डर"), { act: "toast", arg: "Walk-in registration opened", icon: "userplus" }));
    h += '<div class="grid g4 kpis">' + ui.kpi(n(148), L("Samples today", "عينات اليوم", "आज के सैंपल"), sa ? L("from 42 referring doctors", "من 42 طبيباً محيلاً", "42 रेफ़रिंग डॉक्टरों से") : L("62% ordered from consults", "62% من الاستشارات", "62% कंसल्ट से"), "faint") +
      ui.kpi(n(list.filter(function (o) { return o.pri === "stat" && open(o); }).length), L("STAT open", "عاجل مفتوح", "STAT खुले"), ui.icon("bolt", "xs") + " " + L("target 60 min", "الهدف 60 د", "लक्ष्य 60 मिनट"), "critc") +
      ui.kpi(n("94%"), L("Within TAT (7 days)", "ضمن الوقت (7 أيام)", "TAT में (7 दिन)"), L("1 breach today", "تجاوز واحد اليوم", "आज 1 उल्लंघन"), "warnc") +
      ui.kpi(n(1), L("Critical awaiting call-back", "قيمة حرجة بانتظار الإبلاغ", "क्रिटिकल कॉल-बैक बाकी"), '<span class="critc">' + ui.icon("alertfill", "xs") + " " + L("4 min to 15-min limit", "4 د للحد", "15 मिनट सीमा में 4 बाकी") + "</span>", "") + "</div>";
    // Pipeline
    h += '<div class="lb-pipe">' + ST.map(function (s) {
      var cnt = list.filter(function (o) { return o.st === s[0]; }).length;
      return '<button class="lb-st ' + (f === s[0] ? "on" : "") + '" data-set="lbSt" data-val="' + s[0] + '">' + ui.icon(s[5] || "cloudsync", "sm") + "<b>" + cnt + "</b><small>" + esc(L(s[1], s[2], s[3])) + "</small></button>";
    }).join("") + "</div>";
    h += '<div class="row sb">' + ui.seg("lbSt", [["open", L("Open", "مفتوحة", "खुले"), list.filter(open).length], ["all", L("All today", "كل اليوم", "आज के सभी"), list.length]], "open") +
      (f !== "open" && f !== "all" ? '<span class="small faint">' + L("Filtered by stage", "مصفّاة حسب المرحلة", "चरण से फ़िल्टर") + ": " + esc(L(stOf(f)[1], stOf(f)[2], stOf(f)[3])) + "</span>" : "") + "</div>";
    var body;
    if (c.mobile) {
      body = '<div class="stack">' + rows.map(function (o) {
        var p = pat(o);
        return '<div class="lb-rc" data-ov="labresult" data-arg="' + o.id + '"><div class="row sb nw"><span class="tok">' + o.id + '</span><span class="row nw" style="gap:6px">' + (o.pri === "stat" ? priPill("stat") : "") + stPill(o.st) + "</span></div>" +
          ui.cellp(esc(p.name) + (o.crit ? " " + ui.icon("alertfill", "xs critc") : ""), p.age + " " + p.sex + " · " + esc(o.tests.join(", "))) + tatCell(o) + "</div>";
      }).join("") + "</div>";
    } else {
      body = ui.table([{ h: L("Accession", "رقم العينة", "एक्सेशन") }, { h: L("Patient", "المريض", "मरीज़") }, { h: L("Tests", "الفحوصات", "टेस्ट") }, { h: L("Ordered by", "الطالب", "किसने ऑर्डर किया") }, { h: "TAT" }, { h: L("Status", "الحالة", "स्थिति") }],
        rows.map(function (o) {
          var p = pat(o), ob = orderer(o);
          return { cls: o.crit ? "crit" : o.pri === "stat" ? "urg" : "", ov: "labresult", arg: o.id, cells: [ui.cellp('<span class="tok">' + o.id + "</span>" + (o.pri === "stat" ? " " + priPill("stat") : ""), '<span class="lb-src">' + srcCell(o) + "</span>"),
            ui.cellp(esc(p.name), p.age + " " + p.sex + (o.fasting ? " · " + L("fasting", "صائم", "ख़ाली पेट") : "")), '<span class="small" style="white-space:nowrap">' + esc(o.tests.join(" · ")) + "</span>", ui.cellp(esc(ob[0]), esc(ob[1])), tatCell(o),
            stPill(o.st) + (o.crit ? '<div style="margin-top:4px">' + ui.pill("crit", L("Critical", "حرجة", "क्रिटिकल")) + "</div>" : "")] };
        }), { min: 820, empty: L("Nothing at this stage.", "لا شيء في هذه المرحلة.", "इस चरण में कुछ नहीं।") });
    }
    var rail = ui.card(L("Analysers", "أجهزة التحليل", "एनालाइज़र"), '<div class="lst">' + [
      ["HA-01", L("Haematology, 5-part", "أمراض الدم", "हीमेटोलॉजी"), ui.pill("ok", L("Online", "متصل", "ऑनलाइन"), "cloudsync")],
      ["CC-01", L("Clinical chemistry", "الكيمياء السريرية", "क्लिनिकल केमिस्ट्री"), ui.pill("warn", L("QC failed: glucose", "فشل الجودة: الجلوكوز", "QC फ़ेल: ग्लूकोज़"))],
      ["IA-01", L("Immunoassay", "المناعة", "इम्यूनोएसे"), ui.pill("ok", L("Online", "متصل", "ऑनलाइन"), "cloudsync")],
      ["HP-01", "HbA1c HPLC", ui.pill("sync", L("Running 6", "يحلل 6", "6 चल रहे"))]
    ].map(function (a) { return ui.li('<span class="av">' + ui.icon("activity", "sm") + "</span>", esc(a[0]), esc(a[1]), a[2], { go: a[0] === "CC-01" ? "lab.catalog" : null }); }).join("") + "</div>", { icon: "activity", right: '<span class="small faint">HL7 / ASTM</span>' });
    rail += ui.card(L("Home collection today", "السحب المنزلي اليوم", "आज होम कलेक्शन"), '<div class="lst">' + [["07:30", "Collected", "ok"], ["11:00", "En route", "sync"], ["12:30", "Scheduled", "mute"]].map(function (x, i) {
      return ui.li('<span class="av">' + n(x[0].slice(0, 2)) + "</span>", [NB.gcc() ? "Aisha Rahman" : "Thankamma Joseph", NB.gcc() ? "Faisal Noor" : "Vinod Shetty", NB.gcc() ? "Mona Adel" : "Ruksana Begum"][i], x[0] + " · " + ["Manoj K.", "Manoj K.", "Priya D."][i], ui.pill(x[2], x[1], x[2] === "mute" ? "calendar" : undefined));
    }).join("") + "</div>", { icon: "home", right: ui.btn(L("Schedule", "الجدول", "शेड्यूल"), { cls: "sm ghost", go: "lab.collect" }) });
    return h + '<div class="split">' + body + '<div class="stack s16">' + rail + "</div></div>";
  });

  NB.overlay("labresult", function (id) {
    var o = ord(id), p = pat(o), ob = orderer(o), idx = ST.findIndex(function (s) { return s[0] === o.st; });
    var times = { ordered: o.ord, collected: o.col, received: o.rec, inprocess: o.rec ? hm(mins(o.rec) + 8) : null, resulted: o.st === "resulted" || idx > 4 ? "10:19" : null, validated: idx >= 5 ? "09:58" : null, released: idx >= 6 ? "10:01" : null };
    var tl = '<div class="tl">' + ST.map(function (s, i) {
      var done = i <= idx, cur = i === idx;
      return '<div class="ev"><span class="dotc ' + (cur ? "acc" : done ? "ok" : "") + '">' + ui.icon(done && !cur ? "check" : s[5] || "cloudsync", "sm") + '</span><div class="bd"><b>' + esc(L(s[1], s[2], s[3])) + "</b><small>" + (done && times[s[0]] ? n(times[s[0]]) + (s[0] === "collected" ? " · " + (o.src === "home" ? "Manoj K. (home)" : "Ravi P.") : s[0] === "received" ? " · " + L("barcode scanned at bench", "تم مسح الباركود", "बेंच पर बारकोड स्कैन") : "") : L("pending", "قيد الانتظار", "बाकी")) + "</small></div></div>";
    }).join("") + "</div>";
    var next = { ordered: [L("Collect sample", "سحب العينة", "सैंपल लें"), "lab.collect"], collected: [L("Mark received", "تأكيد الاستلام", "प्राप्त चिह्नित करें"), null], received: [L("Load on analyser", "تحميل على الجهاز", "एनालाइज़र पर लोड करें"), null], inprocess: [L("Enter results", "إدخال النتائج", "रिज़ल्ट दर्ज करें"), "lab.results"], resulted: [L("Validate", "تحقق", "सत्यापित करें"), "lab.validate"], validated: [L("Release", "إصدار", "जारी करें"), "lab.validate"], released: [L("View report", "عرض التقرير", "रिपोर्ट देखें"), null] }[o.st];
    return { kind: "drawer", title: L("Order", "الطلب", "ऑर्डर") + ' <span class="tok">' + o.id + "</span>", body:
      '<div class="row nw">' + ui.av(p.name, "lg") + '<div class="fl"><b style="font-size:15px">' + esc(p.name) + '</b><div class="small faint">' + p.age + " " + p.sex + (p.mrn ? " · " + p.mrn : "") + ' · <span class="ltr">' + NB.mkt().phone + " " + esc(p.phone) + '</span></div><div class="chips" style="margin-top:6px">' + priPill(o.pri) + stPill(o.st) + (o.fasting ? ui.pill("info", L("Fasting test", "فحص صيام", "फ़ास्टिंग टेस्ट"), "clockh") : "") + (o.crit ? ui.pill("crit", L("Critical value", "قيمة حرجة", "क्रिटिकल मान")) : "") + "</div></div></div>" +
      ui.card(L("Order", "الطلب", "ऑर्डर"), '<dl class="kv"><dt>' + L("Ordered by", "الطالب", "ऑर्डर") + "</dt><dd>" + esc(ob[0]) + "</dd><dt>" + L("Source", "المصدر", "स्रोत") + "</dt><dd>" + srcCell(o) + "</dd><dt>" + L("Clinical note", "ملاحظة سريرية", "क्लिनिकल नोट") + "</dt><dd>" + esc(o.note || "—") + "</dd><dt>TAT</dt><dd>" + n(tatOf(o) >= 1440 ? Math.round(tatOf(o) / 1440) + " d" : tatOf(o) + " min") + " · " + L("due", "الموعد", "देय") + " " + n(hm(dueOf(o) % 1440)) + "</dd></dl>" +
        '<div class="hr" style="margin:12px 0"></div>' + o.tests.map(function (c) { var t = test(c); return ui.li('<span class="av">' + ui.icon("flask", "sm") + "</span>", esc(t.name), esc(c + " · " + t.sample + (t.out ? " · reference lab" : "")), NB.money(t.price)); }).join(""), { icon: "clipboard" }) +
      ui.card(L("Sample journey", "رحلة العينة", "सैंपल यात्रा"), tl, { icon: "history" }),
      foot: ui.btn(L("Close", "إغلاق", "बंद करें"), { act: "close", cls: "ghost" }) + (o.st === "released" ? ui.btn(next[0], { cls: "pri", ov: "labreport", arg: o.id, icon: "file" }) : next[1] ? ui.btn(next[0], { cls: "pri", go: next[1], icon: "arrowright" }) : ui.btn(next[0], { cls: "pri", act: "toast", arg: o.id + ": " + next[0], icon: "check" })) };
  });

  /* ================= SCREEN: Sample collection ================= */
  var TUBE = {
    edta: ["EDTA (lavender top)", "--sub", "2 ml", 4], sst: ["Serum separator (gold top)", "--warn", "5 ml", 2], flu: ["Fluoride (grey top)", "--tx3", "2 ml", 5],
    cit: ["Citrate (light-blue top)", "--info", "2.7 ml", 1], uri: ["Sterile urine container", "--tx2", "30 ml", 6], form: ["Formalin container", "--tx2", "10% NBF", 7], cap: ["Capillary EDTA microtainer", "--sub", "0.5 ml", 4]
  };
  function tubeOf(code, child) { var t = test(code).sample; if (/EDTA/.test(t)) return child ? "cap" : "edta"; if (/Fluoride/.test(t)) return "flu"; if (/Citrate/.test(t)) return "cit"; if (/Urine/.test(t)) return "uri"; if (/Tissue/.test(t)) return "form"; return "sst"; }
  function tubesFor(o) {
    var p = pat(o), m = {};
    o.tests.forEach(function (c) { var k = tubeOf(c, p.age < 8); (m[k] = m[k] || []).push(c); });
    return Object.keys(m).sort(function (a, b) { return TUBE[a][3] - TUBE[b][3]; }).map(function (k, i) { return { k: k, tests: m[k], seq: i + 1 }; });
  }
  function barcode(seed, w, h) {
    var x = 0, s = '<svg viewBox="0 0 ' + w + " " + h + '" preserveAspectRatio="none" aria-hidden="true">', r = seed * 7919 + 13;
    while (x < w - 2) { r = (r * 9301 + 49297) % 233280; var bw = 1 + Math.floor(r / 233280 * 3); s += '<rect x="' + x + '" y="0" width="' + bw + '" height="' + h + '"/>'; r = (r * 9301 + 49297) % 233280; x += bw + 1 + Math.floor(r / 233280 * 3); }
    return s + "</svg>";
  }
  function labelCard(o, t) {
    var p = pat(o);
    return '<div class="lb-label"><div class="row sb nw"><b>' + esc(p.name) + '</b><span class="mt">' + p.age + p.sex + "</span></div>" + barcode(+o.id.slice(-3) * 10 + t.seq, 200, 34) +
      '<div class="row sb nw"><b class="num">' + o.id + "-" + t.seq + '</b><span class="mt">' + esc(TUBE[t.k][0].split(" (")[0]) + "</span></div><div>" + esc(t.tests.join(" · ")) + (o.pri === "stat" ? " · STAT" : "") + '</div><div class="mt">17-10-26 ' + (o.col || hm(NOW)) + "</div></div>";
  }

  NB.screen("lab.collect", function (c) {
    var tab = NB.v("lbColT", "desk"), list = orders();
    var h = modeBar() + ui.header(L("Sample collection", "جمع العينات", "सैंपल कलेक्शन"), L("Two identifiers before every draw. Labels print in order of draw.", "معرّفان قبل كل سحب. تُطبع الملصقات بترتيب السحب.", "हर सैंपल से पहले दो पहचान। लेबल ड्रॉ क्रम में छपते हैं।"),
      ui.tabs("lbColT", [["desk", L("Walk-in & OPD", "العيادة والزوار", "वॉक-इन और OPD"), "arrivals"], ["home", L("Home collection", "السحب المنزلي", "होम कलेक्शन"), "home"]], "desk"));
    if (tab === "home") return h + homeCollection(c);
    var waiting = list.filter(function (o) { return o.st === "ordered" || o.id === "LB-0142"; });
    var sel = ord(NB.v("lbCol", "LB-0147")), p = pat(sel), tubes = tubesFor(sel);
    var queue = ui.card(L("Waiting to be drawn", "بانتظار السحب", "सैंपल के लिए प्रतीक्षा"), '<div class="stack s8">' + waiting.map(function (o, i) {
      var pp = pat(o);
      return '<button class="lb-pick ' + (o.id === sel.id ? "on" : "") + '" data-set="lbCol" data-val="' + o.id + '"><span class="row sb nw"><b>' + esc(pp.name) + "</b>" + (o.pri === "stat" ? priPill("stat") : o.st === "collected" ? stPill("collected") : '<span class="tok">L-' + (21 + i) + "</span>") + "</span><small>" + esc(o.tests.join(", ")) + (o.fasting ? " · " + L("fasting", "صيام", "फ़ास्टिंग") : "") + "</small></button>";
    }).join("") + "</div>", { icon: "users", right: '<span class="small faint">' + L("median wait 6 min", "متوسط الانتظار 6 د", "औसत 6 मिनट") + "</span>" });

    var idOk = NB.v("lbId_" + sel.id, sel.st === "collected");
    var idCard = ui.card(L("1 · Patient identity", "1 · هوية المريض", "1 · मरीज़ की पहचान"), '<div class="row nw">' + ui.av(p.name, "lg") + '<div class="fl"><b style="font-size:15px">' + esc(p.name) + '</b><div class="small faint">' + p.age + " " + p.sex + (p.mrn ? " · " + p.mrn : "") + ' · <span class="ltr">' + NB.mkt().phone + " " + esc(p.phone) + "</span></div></div>" + priPill(sel.pri) + "</div>" +
      '<div class="hr" style="margin:12px 0"></div>' + ui.toggle("lbId_" + sel.id, L("Patient stated full name and date of birth", "ذكر المريض اسمه وتاريخ ميلاده", "मरीज़ ने पूरा नाम और जन्मतिथि बताई"), L("Matches the order · open question, never “are you…?”", "مطابق للطلب", "ऑर्डर से मेल · खुला प्रश्न पूछें"), sel.st === "collected") +
      ui.toggle("lbIdDoc_" + sel.id, L("ID scanned", "تم مسح الهوية", "आईडी स्कैन") + ": " + NB.mkt().natId, L("Optional second check for walk-ins and home visits", "تحقق إضافي اختياري", "वॉक-इन और होम विज़िट के लिए वैकल्पिक"), false), { icon: "fingerprint", right: idOk ? ui.pill("ok", L("Verified", "تم التحقق", "सत्यापित")) : ui.pill("warn", L("Check ID", "تحقق من الهوية", "आईडी जाँचें"), "eye") });

    var fast = "";
    if (sel.fasting) {
      var last = NB.v("lbMeal", "21:30"), hrs = Math.round(((24 * 60 - mins(last)) + NOW) / 6) / 10;
      fast = ui.card(L("2 · Fasting check", "2 · التحقق من الصيام", "2 · फ़ास्टिंग जाँच"), '<div class="fgrid">' + '<div class="field"><label for="lb-meal">' + L("Last meal (patient says)", "آخر وجبة", "आख़िरी भोजन") + '</label><select id="lb-meal" data-change="lbMeal">' + ["21:30", "23:00", "06:30", "08:45"].map(function (t) { return "<option" + (t === last ? " selected" : "") + ">" + t + "</option>"; }).join("") + "</select></div>" +
        '<div class="field"><label>' + L("Fasting duration", "مدة الصيام", "फ़ास्टिंग अवधि") + '</label><b class="num">' + hrs + " h</b></div></div>" +
        (hrs >= 8 && hrs <= 14 ? ui.note("ok", L("Fasting 8–14 h: suitable for glucose and lipid profile. Water only is fine.", "صيام 8–14 ساعة: مناسب.", "8–14 घंटे: ग्लूकोज़ और लिपिड के लिए ठीक।")) : ui.note("warn", L("Not fasting long enough. Draw non-fasting tests now and rebook the fasting ones, or report as non-fasting with a comment.", "مدة الصيام غير كافية.", "पर्याप्त फ़ास्टिंग नहीं। नॉन-फ़ास्टिंग रिपोर्ट करें या दोबारा बुक करें।"))), { icon: "clockh" });
    } else fast = ui.card(L("2 · Fasting check", "2 · التحقق من الصيام", "2 · फ़ास्टिंग जाँच"), '<p class="small muted" style="margin:0">' + L("No fasting test in this order.", "لا يوجد فحص صيام في هذا الطلب.", "इस ऑर्डर में कोई फ़ास्टिंग टेस्ट नहीं।") + (p.age < 12 ? " " + L("Child: capillary sample, apply numbing patch if parent agrees.", "طفل: عينة شعرية.", "बच्चा: केपिलरी सैंपल।") : "") + "</p>", { icon: "clockh" });

    var tubeCard = ui.card(L("3 · Tubes, in order of draw", "3 · الأنابيب بترتيب السحب", "3 · ट्यूब, ड्रॉ क्रम में"), '<div class="lb-tubes">' + tubes.map(function (t) {
      var T = TUBE[t.k];
      return '<div class="r"><span class="tok">' + t.seq + '</span><span class="lb-tube" style="--c:var(' + T[1] + ')"></span><div class="fl"><b>' + esc(T[0]) + "</b><small>" + esc(T[2]) + " · " + esc(t.tests.join(", ")) + '</small></div><span class="num small faint">' + sel.id + "-" + t.seq + "</span></div>";
    }).join("") + '</div><div class="hr" style="margin:12px 0"></div><div class="eyebrow" style="margin-bottom:10px">' + L("Label preview", "معاينة الملصق", "लेबल प्रीव्यू") + '</div><div class="lb-labels">' + tubes.map(function (t) { return labelCard(sel, t); }).join("") + "</div>", { icon: "barcode" });

    var act = '<div class="btns">' + ui.btn(L("Print", "طباعة", "प्रिंट") + " " + tubes.length + " " + L("labels", "ملصقات", "लेबल"), { ov: "lbprint", arg: sel.id, icon: "printer" }) +
      ui.btn(L("Mark collected", "تأكيد السحب", "कलेक्शन पूरा"), { cls: "pri", act: "toast", arg: sel.id + " collected at 10:31 by Kiran Rao · sent to bench", icon: "check", disabled: !idOk || sel.st === "collected" }) + ui.btn(L("Patient refused / unable", "رفض / تعذر", "मरीज़ ने मना किया"), { cls: "ghost", act: "toast", arg: "Reason recorded; doctor notified" }) + "</div>";
    return h + '<div class="split l">' + queue + '<div class="stack s16">' + idCard + fast + tubeCard + act + "</div></div>";
  });

  function homeCollection(c) {
    var g = NB.gcc();
    var rows = [
      ["07:30", g ? "Aisha Rahman, 74 F" : "Thankamma Joseph, 74 F", g ? "Jumeirah 1" : "Kowdiar, Thiruvananthapuram", "HBA1C, LIPID, KFT", "Manoj K.", ["ok", L("Delivered to lab", "سُلّمت للمختبر", "लैब पहुँचा"), "checkcircle"], L("Fasting reminder sent 21:00", "تذكير الصيام", "फ़ास्टिंग रिमाइंडर")],
      ["11:00", g ? "Faisal Noor, 66 M" : "Vinod Shetty, 66 M", g ? "Al Barsha 2" : "HSR Layout", "PTINR", "Manoj K.", ["sync", L("En route · ETA 12 min", "في الطريق", "रास्ते में · 12 मिनट")], L("Live location shared", "الموقع مباشر", "लाइव लोकेशन")],
      ["12:30", g ? "Mona Adel, 31 F" : "Ruksana Begum, 31 F", g ? "Al Sufouh" : "BTM Layout", "CBC, TSH, HBSAG", "Priya D.", ["mute", L("Scheduled", "مجدولة", "शेड्यूल"), "calendar"], L("Antenatal, 22 weeks", "متابعة حمل", "गर्भावस्था 22 सप्ताह")],
      ["16:00", g ? "Yousef Nasser, 58 M" : "Prakash Rao, 58 M", g ? "Karama" : "Jayanagar", "KFT, CBC", "Priya D.", ["mute", L("Scheduled", "مجدولة", "शेड्यूल"), "calendar"], L("Bedbound, caregiver present", "طريح الفراش", "बिस्तर पर, देखभालकर्ता मौजूद")],
      ["07:00 +1", g ? "Huda Saleh, 49 F" : "Meena Iyer, 49 F", g ? "Al Barsha 1" : "Koramangala", "FBS, LIPID", "Manoj K.", ["info", L("Booked via WhatsApp", "حُجزت عبر واتساب", "व्हाट्सऐप से बुक"), "chat"], L("Fasting reminder at 21:00", "تذكير الصيام 21:00", "फ़ास्टिंग रिमाइंडर 21:00")]
    ];
    return '<div class="grid g3 kpis">' + ui.kpi(n(14), L("Home visits today", "زيارات منزلية اليوم", "आज होम विज़िट")) + ui.kpi(n("2"), L("Phlebotomists out", "فنيو سحب في الميدان", "फ़्लेबोटॉमिस्ट बाहर")) + ui.kpi(n("38 min"), L("Avg sample-to-lab time", "متوسط وصول العينة", "सैंपल से लैब औसत समय"), L("cold box 2–8 °C logged", "صندوق مبرد", "कोल्ड बॉक्स 2–8°C"), "faint") + "</div>" +
      ui.table([{ h: L("Slot", "الموعد", "स्लॉट") }, { h: L("Patient", "المريض", "मरीज़") }, { h: L("Area", "المنطقة", "क्षेत्र") }, { h: L("Tests", "الفحوصات", "टेस्ट") }, { h: L("Phlebotomist", "فني السحب", "फ़्लेबोटॉमिस्ट") }, { h: L("Status", "الحالة", "स्थिति") }],
        rows.map(function (r) { return [n(r[0]), ui.cellp(esc(r[1]), esc(r[6])), esc(r[2]), chips(r[3].split(", ")), ui.cellp(esc(r[4]), r[4] === "Manoj K." ? L("Route A · 6 stops", "المسار أ", "रूट A · 6 स्टॉप") : L("Route B · 8 stops", "المسار ب", "रूट B · 8 स्टॉप")), ui.pill(r[5][0], r[5][1], r[5][2])]; }), { min: 860 }) +
      ui.note("info", L("Patients book home collection on WhatsApp or the app; they get the phlebotomist's name, photo and live arrival time. Samples are scanned at the door, so collection time and cold-box temperature are on the record.", "يحجز المرضى السحب المنزلي عبر واتساب أو التطبيق.", "मरीज़ व्हाट्सऐप या ऐप पर होम कलेक्शन बुक करते हैं; फ़्लेबोटॉमिस्ट का नाम, फ़ोटो और आने का समय मिलता है।"));
  }

  NB.overlay("lbprint", function (id) {
    var o = ord(id), t = tubesFor(o);
    return { kind: "modal", title: L("Print labels", "طباعة الملصقات", "लेबल प्रिंट") + " · " + o.id, body: '<div class="lb-labels">' + t.map(function (x) { return labelCard(o, x); }).join("") + "</div>" +
      ui.field(L("Printer", "الطابعة", "प्रिंटर"), "Zebra-type thermal 50×25 mm · Collection desk", { options: ["Zebra-type thermal 50×25 mm · Collection desk", "Thermal 38×25 mm · Home kit"] }),
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Print", "طباعة", "प्रिंट") + " " + t.length, { cls: "pri", act: "toast", arg: t.length + " labels sent to printer", icon: "printer" }) };
  });

  /* ================= Results data ================= */
  // [analyte, value(conv), previous(conv) or null, source]
  var RES = {
    "LB-0141": { CBC: [["HB", 13.4, 13.9], ["WBC", 12.8, 7.2], ["PLT", 168, 214], ["NEU", 81, 62], ["LYM", 14, 30]], CRP: [["CRP", 48, 3.1]], KFT: [["UREA", 52, 38], ["CREA", 1.62, 1.21], ["EGFR", 49, 68], ["NA", 134, 138], ["K", 6.1, 4.6]], prev: "12 Aug 2026" },
    "LB-0143": { KFT: [["UREA", 61, 58], ["CREA", 1.74, 1.69], ["EGFR", 41, 43], ["NA", 139, 140], ["K", 4.9, 4.8]], LIPID: [["CHOL", null], ["TG", null], ["HDL", null], ["LDL", null]], prev: "10 Jul 2026" },
    "LB-0144": { TSH: [["TSH", 6.82, 3.1]], prev: "14 Apr 2026" },
    "LB-0146": { HBA1C: [["HBA1C", 7.9, 8.4]], KFT: [["UREA", 34, 31], ["CREA", 0.98, 0.94], ["EGFR", 58, 61], ["NA", 137, 138], ["K", 4.4, 4.2]], LIPID: [["CHOL", null], ["TG", null], ["HDL", null], ["LDL", null]], prev: "20 Jun 2026" },
    "LB-0138": { CBC: [["HB", 11.2, 11.0], ["WBC", 6.4, 7.0], ["PLT", 262, 250], ["NEU", 58, 60], ["LYM", 33, 31]], TSH: [["TSH", 2.1, null]], prev: "02 May 2026" }
  };
  function refOf(a, p) { var r = AN[a].ref; return typeof r === "function" ? r(p) : r; }
  function conv(a, v) { var A = AN[a]; if (v == null) return null; if (si() && A.f) return v * A.f; return v; }
  function unitOf(a) { return si() && AN[a].si ? AN[a].si : AN[a].u; }
  function dpOf(a) { var A = AN[a]; if (si() && A.f) return A.f > 10 ? 0 : A.f < 0.1 ? 1 : 1; return A.dp != null ? A.dp : 1; }
  function fmtV(a, v) { return v == null ? "—" : conv(a, v).toFixed(dpOf(a)); }
  function refTxt(a, p) { var r = refOf(a, p); if (a === "EGFR") return "≥ 90"; return r[0] === 0 ? "< " + fmtV(a, r[1]) : r[1] >= 100 && a === "HDL" ? "> " + fmtV(a, r[0]) : fmtV(a, r[0]) + " – " + fmtV(a, r[1]); }
  function flagOf(a, v, p) {
    if (v == null) return null;
    var A = AN[a], r = refOf(a, p);
    if (A.crit && (v >= A.crit[1] || v <= A.crit[0])) return "C";
    if (v > r[1]) return "H"; if (v < r[0]) return "L"; return "";
  }
  function flagPill(f) { return f === "C" ? ui.pill("crit", L("Critical", "حرجة", "क्रिटिकल")) : f === "H" ? ui.pill("urg", L("High", "مرتفع", "उच्च"), "alertcircle") : f === "L" ? ui.pill("info", L("Low", "منخفض", "निम्न"), "alertcircle") : f === "" ? '<span class="small faint">' + L("Normal", "طبيعي", "सामान्य") + "</span>" : ""; }
  function delta(a, v, pv) {
    var D = AN[a].d; if (!D || v == null || pv == null) return { txt: pv == null ? "—" : "", bad: false };
    var diff = v - pv, pc = diff / pv * 100, bad = D.abs ? Math.abs(diff) >= D.abs : Math.abs(pc) >= D.pct;
    return { txt: (diff >= 0 ? "+" : "") + (D.abs ? conv(a, Math.abs(diff)).toFixed(dpOf(a)) * (diff < 0 ? -1 : 1) : Math.round(pc) + "%"), bad: bad };
  }

  /* ================= SCREEN: Result entry ================= */
  NB.screen("lab.results", function (c) {
    var pend = ["LB-0141", "LB-0143", "LB-0146", "LB-0144"], sel = ord(NB.v("lbRes", "LB-0141")), p = pat(sel), R = RES[sel.id] || {};
    var mode = NB.v("lbMode", "analyser");
    var h = modeBar() + ui.header(L("Result entry", "إدخال النتائج", "रिज़ल्ट एंट्री"), L("Reference ranges by age and sex; flags and delta checks run as values arrive.", "المدى المرجعي حسب العمر والجنس؛ تعمل العلامات وفحص الفرق تلقائياً.", "उम्र और लिंग के अनुसार रेफ़रेंस रेंज; फ़्लैग और डेल्टा जाँच अपने-आप।"),
      ui.seg("lbUnits", [["conv", L("Conventional units", "وحدات تقليدية", "पारंपरिक इकाइयाँ")], ["si", L("SI units", "وحدات SI", "SI इकाइयाँ")]], NB.gcc() ? "si" : "conv"));
    var picker = ui.card(L("Pending results", "نتائج معلقة", "बाकी रिज़ल्ट"), '<div class="stack s8">' + pend.map(function (id) {
      var o = ord(id), pp = pat(o);
      return '<button class="lb-pick ' + (id === sel.id ? "on" : "") + '" data-set="lbRes" data-val="' + id + '"><span class="row sb nw"><b>' + esc(pp.name) + '</b><span class="tok">' + id + "</span></span><small>" + esc(o.tests.join(", ")) + " · " + pp.age + " " + pp.sex + "</small>" +
        (o.crit ? '<span style="margin-top:4px">' + ui.pill("crit", L("Critical", "حرجة", "क्रिटिकल")) + "</span>" : "") + "</button>";
    }).join("") + "</div>", { icon: "list" });
    var anyCrit = false, body = "";
    Object.keys(R).filter(function (k) { return k !== "prev"; }).forEach(function (tc) {
      body += '<tr class="sel"><td colspan="6"><b>' + esc(test(tc).name) + '</b> <span class="lb-src">· ' + (mode === "analyser" ? ui.icon("activity", "xs") + " " + (INSTR[tc] || "") + " · HL7" : ui.icon("edit", "xs") + " " + L("manual", "يدوي", "मैन्युअल")) + " · " + esc(D.method[tc] || "") + "</span></td></tr>";
      R[tc].forEach(function (r) {
        var a = r[0], A = AN[a], v = r[1], f = flagOf(a, v, p), dlt = delta(a, v, r[2]);
        if (f === "C") anyCrit = true;
        var val = v == null ? '<span class="pill sync"><span class="pulse"></span>' + L("Running", "قيد التحليل", "चल रहा") + "</span>"
          : '<input class="lb-in' + (f === "C" ? " c" : f ? " h" : "") + '" value="' + fmtV(a, v) + '" aria-label="' + esc(A.n) + '"' + (mode === "analyser" ? " readonly" : "") + ">";
        var prev = r[2] != null ? '<span class="small num ltr">' + fmtV(a, r[2]) + "</span>" + (dlt.txt ? ' <span class="small num ltr ' + (dlt.bad ? "warnc" : "faint") + '">(' + dlt.txt + ")</span>" : "") + (dlt.bad ? " " + ui.pill("warn", L("Delta", "فرق", "डेल्टा"), "alertcircle") : "") : '<span class="faint">—</span>';
        body += '<tr' + (f === "C" ? ' class="crit"' : "") + "><td>" + esc(A.n) + "</td><td>" + val + '</td><td><span class="small muted">' + esc(unitOf(a)) + '</span></td><td><span class="small num ltr">' + refTxt(a, p) + "</span></td><td>" + flagPill(f) + '</td><td style="white-space:nowrap">' + prev + "</td></tr>";
      });
    });
    var tbl = '<div class="tw"><table class="t" style="min-width:760px"><thead><tr><th>' + L("Analyte", "التحليل", "एनालाइट") + "</th><th>" + L("Result", "النتيجة", "परिणाम") + "</th><th>" + L("Unit", "الوحدة", "इकाई") + "</th><th>" + L("Reference", "المرجع", "रेफ़रेंस") + " · " + p.age + " " + p.sex + "</th><th>" + L("Flag", "العلامة", "फ़्लैग") + "</th><th>" + L("Previous", "السابق", "पिछला") + (R.prev ? " · " + R.prev : "") + " · Δ</th></tr></thead><tbody>" + body + "</tbody></table></div>";
    var top = '<div class="row sb"><div class="row">' + '<b style="font-size:15px">' + esc(p.name) + '</b><span class="small faint">' + p.age + " " + p.sex + " · " + sel.id + " · " + esc(orderer(sel)[0]) + "</span>" + priPill(sel.pri) + "</div>" +
      ui.seg("lbMode", [["analyser", L("Analyser (interfaced)", "من الجهاز", "एनालाइज़र")], ["manual", L("Manual entry", "إدخال يدوي", "मैन्युअल एंट्री")]], "analyser") + "</div>";
    var notes = "";
    if (anyCrit) notes += ui.note("crit", "<b>" + L("Critical value: potassium", "قيمة حرجة: البوتاسيوم", "क्रिटिकल मान: पोटैशियम") + " " + fmtV("K", 6.1) + " mmol/L.</b> " + L("Haemolysis index 0 (sample not haemolysed); rerun on the same tube gave 6.0. The treating doctor must be informed within 15 minutes; the call-back starts as soon as you submit.", "مؤشر انحلال الدم 0؛ إعادة التحليل 6.0. يجب إبلاغ الطبيب خلال 15 دقيقة.", "हीमोलिसिस इंडेक्स 0; दोबारा जाँच 6.0। 15 मिनट में डॉक्टर को सूचित करना ज़रूरी।"));
    if (sel.id === "LB-0141") notes += ui.note("warn", L("Delta check: creatinine up 34% and potassium up 1.5 mmol/L since 12 Aug. Consistent with acute kidney injury on metformin; comment suggested below.", "فحص الفرق: ارتفاع الكرياتينين 34% والبوتاسيوم 1.5.", "डेल्टा जाँच: 12 अगस्त से क्रिएटिनिन 34% और पोटैशियम 1.5 बढ़ा।"));
    if (mode === "manual") notes += ui.note("info", L("Manual entry: every value is typed twice (double entry) or checked by a second person before validation.", "الإدخال اليدوي: يُدخل كل رقم مرتين.", "मैन्युअल: हर मान दो बार दर्ज या दूसरे व्यक्ति से जाँच।"));
    var com = ui.card(L("Comments on report", "تعليقات التقرير", "रिपोर्ट पर टिप्पणी"), '<div class="chips" style="margin-bottom:10px">' + [L("Repeat to confirm", "أعد للتأكيد", "पुष्टि के लिए दोहराएँ"), L("Sample haemolysed", "العينة منحلة", "सैंपल हीमोलाइज़्ड"), L("Non-fasting sample", "عينة غير صائمة", "नॉन-फ़ास्टिंग"), L("Correlate clinically", "يُربط سريرياً", "क्लिनिकली मिलाएँ"), L("Critical value phoned", "أُبلغ هاتفياً", "क्रिटिकल फ़ोन किया")].map(function (x) { return '<span class="chip">' + ui.icon("plus", "xs") + esc(x) + "</span>"; }).join("") + "</div>" +
      ui.field(L("Comment", "تعليق", "टिप्पणी"), sel.id === "LB-0141" ? "Hyperkalaemia with rising creatinine; non-haemolysed sample, confirmed on rerun. Suggest urgent clinical review." : "", { area: true }), { icon: "edit" });
    var foot = '<div class="row sb"><span class="small faint">' + ui.icon("user", "xs") + " " + L("Entered by Kiran Rao · technologist", "أدخلها كيران راو", "किरन राव द्वारा दर्ज") + '</span><div class="btns">' + ui.btn(L("Rerun", "إعادة", "दोबारा चलाएँ"), { cls: "ghost", act: "toast", arg: "Rerun requested on " + (INSTR.KFT), icon: "refresh" }) +
      ui.btn(L("Submit for validation", "إرسال للتحقق", "सत्यापन के लिए भेजें"), { cls: "pri", act: "toast", arg: sel.id + " sent to pathologist" + (anyCrit ? " · critical call-back started" : ""), icon: "send" }) + "</div></div>";
    return h + '<div class="split l">' + picker + '<div class="stack s16">' + top + notes + tbl + com + foot + "</div></div>";
  });

  /* ================= SCREEN: Validate & release ================= */
  function pathologist() { return NB.gcc() ? { n: "Dr. Rana Haddad", t: "Consultant Clinical Pathologist", r: { uae: "DHA-P-61120", ksa: "SCFHS-61120", qatar: "QCHP-61120" }[NB.state.market] } : { n: "Dr. Shalini Rao", t: "MD Pathology", r: "KMC-55120" }; }
  var CALLS = [
    ["10:19", "alertfill", "crit", "Critical flagged by CC-01: potassium 6.1 mmol/L", "Push alert sent to Dr. Vikram Shah (app + WhatsApp) · delivered, not read"],
    ["10:22", "phone", "warn", "Called Dr. Vikram Shah, Room 1", "No answer (in consultation) · retry scheduled"],
    ["10:24", "phone", "ok", "Informed Anita D'Souza, OPD nurse in charge", "Read-back confirmed: “potassium six point one” · she is taking it to the doctor"]
  ];
  NB.screen("lab.validate", function (c) {
    var q = ["LB-0141", "LB-0144", "LB-0138"], sel = ord(NB.v("lbVal", "LB-0141")), p = pat(sel), R = RES[sel.id] || {}, P = pathologist(), mk = NB.mkt();
    var h = modeBar() + ui.header(L("Validate & release", "التحقق والإصدار", "सत्यापन और जारी"), L("Pathologist review, critical value call-back and report delivery.", "مراجعة أخصائي علم الأمراض والإبلاغ عن القيم الحرجة وتسليم التقارير.", "पैथोलॉजिस्ट समीक्षा, क्रिटिकल कॉल-बैक और रिपोर्ट डिलीवरी।"));
    var queue = ui.card(L("Awaiting sign-off", "بانتظار الاعتماد", "साइन-ऑफ़ बाकी"), '<div class="stack s8">' + q.map(function (id) {
      var o = ord(id), pp = pat(o), auto = id === "LB-0138";
      return '<button class="lb-pick ' + (id === sel.id ? "on" : "") + '" data-set="lbVal" data-val="' + id + '"><span class="row sb nw"><b>' + esc(pp.name) + '</b><span class="tok">' + id + "</span></span><small>" + esc(o.tests.join(", ")) + "</small><span class=\"row\" style=\"margin-top:4px\">" +
        (o.crit ? ui.pill("crit", L("Critical", "حرجة", "क्रिटिकल")) : auto ? ui.pill("ok", L("Auto-verify passed", "اجتاز التحقق الآلي", "ऑटो-वेरिफ़ाई पास"), "sparkle") : ui.pill("urg", L("Abnormal", "غير طبيعي", "असामान्य"), "alertcircle")) + "</span></button>";
    }).join("") + "</div>" + '<p class="tiny faint" style="margin:10px 0 0">' + L("Auto-verification releases normal, delta-clean results from interfaced analysers after QC passes; anything flagged waits for a pathologist.", "التحقق الآلي للنتائج الطبيعية فقط.", "सामान्य और डेल्टा-साफ़ रिज़ल्ट QC पास होने पर ऑटो-वेरिफ़ाई होते हैं।") + "</p>", { icon: "shieldcheck" });

    var res = [];
    Object.keys(R).filter(function (k) { return k !== "prev"; }).forEach(function (tc) { R[tc].forEach(function (r) { if (r[1] == null) return; var f = flagOf(r[0], r[1], p), dl = delta(r[0], r[1], r[2]); res.push({ cls: f === "C" ? "crit" : "", cells: [esc(AN[r[0]].n), '<b class="num ltr' + (f === "C" ? " critc" : f ? " warnc" : "") + '">' + fmtV(r[0], r[1]) + "</b> " + '<span class="small faint">' + esc(unitOf(r[0])) + "</span>", '<span class="small num ltr">' + refTxt(r[0], p) + "</span>", flagPill(f), r[2] != null ? '<span class="small num ltr faint">' + fmtV(r[0], r[2]) + "</span>" + (dl.bad ? " " + ui.icon("alertcircle", "xs warnc") : "") : "—"] }); }); });
    var resCard = ui.card(esc(p.name) + " · " + p.age + " " + p.sex + " · " + sel.id, ui.table([{ h: L("Analyte", "التحليل", "एनालाइट") }, { h: L("Result", "النتيجة", "परिणाम") }, { h: L("Reference", "المرجع", "रेफ़रेंस") }, { h: L("Flag", "العلامة", "फ़्लैग") }, { h: L("Previous", "السابق", "पिछला") }], res, { min: 560 }) +
      (sel.id === "LB-0141" ? '<p class="small muted" style="margin:12px 0 0">' + ui.icon("edit", "xs") + " " + esc("Hyperkalaemia with rising creatinine; non-haemolysed sample, confirmed on rerun. Suggest urgent clinical review.") + "</p>" : ""), { icon: "file", right: priPill(sel.pri) });

    var call = "";
    if (sel.crit) {
      var left = mins("10:34") - NOW;
      call = ui.card(L("Critical value call-back", "الإبلاغ عن القيمة الحرجة", "क्रिटिकल मान कॉल-बैक"), '<div class="row sb" style="margin-bottom:12px"><span class="small muted">' + L("Policy: treating doctor (or covering clinician) acknowledges within 15 min; fallback chain after 2 failed attempts.", "يجب الإقرار خلال 15 دقيقة.", "नीति: 15 मिनट में डॉक्टर की पुष्टि; 2 असफल प्रयासों के बाद फ़ॉलबैक।") + "</span>" +
        ui.pill(left <= 5 ? "crit" : "warn", left + " " + L("min left", "د متبقية", "मिनट बाकी"), "hourglass") + '</div><div class="tl">' + CALLS.map(function (e) {
          return '<div class="ev"><span class="dotc ' + e[2] + '">' + ui.icon(e[1], "sm") + '</span><div class="bd"><b><span class="num">' + e[0] + "</span> · " + esc(e[3]) + "</b><small>" + esc(e[4]) + "</small></div></div>";
        }).join("") + '<div class="ev"><span class="dotc">' + ui.icon("clockh", "sm") + '</span><div class="bd"><b>' + L("Waiting for doctor acknowledgement", "بانتظار إقرار الطبيب", "डॉक्टर की पुष्टि की प्रतीक्षा") + "</b><small>" + L("Next in chain at 10:32: Dr. Nisha Bhat (covering), then medical director", "التالي: الطبيب المناوب ثم المدير الطبي", "अगला 10:32: डॉ. निशा भट, फिर मेडिकल डायरेक्टर") + "</small></div></div></div>" +
        '<div class="btns">' + ui.btn(L("Log a call", "تسجيل اتصال", "कॉल दर्ज करें"), { cls: "pri", ov: "criticalcall", arg: sel.id, icon: "phone" }) + ui.btn(L("Escalate now", "تصعيد الآن", "अभी एस्केलेट करें"), { act: "toast", arg: "Escalated to Dr. Nisha Bhat (covering) · call placed", icon: "bolt" }) + "</div>", { cls: "crit", icon: "alertfill" });
    }

    var rel = ui.card(L("Release to", "الإصدار إلى", "जारी करें"), ui.toggle("lbRelDoc", L("Doctor's results inbox", "صندوق نتائج الطبيب", "डॉक्टर का रिज़ल्ट इनबॉक्स"), esc(orderer(sel)[0]) + " · " + L("abnormal values highlighted", "القيم غير الطبيعية مميزة", "असामान्य मान हाइलाइट"), true) +
      ui.toggle("lbRelWa", L("Patient on WhatsApp (PDF, secure link)", "المريض عبر واتساب (PDF برابط آمن)", "मरीज़ को व्हाट्सऐप (PDF, सुरक्षित लिंक)"), L("Link opens with OTP, expires in 7 days; held back until the doctor has seen critical results", "الرابط بكلمة مرور لمرة واحدة؛ يُحجب حتى يراه الطبيب", "OTP से खुलता है, 7 दिन में समाप्त; क्रिटिकल रिज़ल्ट डॉक्टर के देखने तक रुका"), true) +
      ui.toggle("lbRelApp", L("Patient app & family caregiver", "تطبيق المريض ومقدم الرعاية", "मरीज़ ऐप और परिवार"), L("Only caregivers with consented access", "فقط من لديهم موافقة", "केवल सहमति वाले देखभालकर्ता"), true) +
      ui.toggle("lbRelHie", mk.hie, india() ? L("Linked to the patient's ABHA when they have consented", "", "सहमति हो तो मरीज़ के ABHA से जुड़ा") : L("Mandatory submission of lab results", "إرسال إلزامي للنتائج", ""), !india()) +
      '<div class="hr" style="margin:12px 0"></div><div class="row"><div class="lb-wa">' + (p.lang === "hi" ? "नमस्ते " : p.lang === "ar" ? "مرحباً " : "Hello ") + esc(p.name.split(" ")[0]) + ", " +
        (p.lang === "hi" ? "आपकी लैब रिपोर्ट तैयार है। सुरक्षित लिंक से देखें (OTP)।" : p.lang === "ar" ? "تقرير المختبر جاهز. افتحه عبر الرابط الآمن (رمز تحقق)." : "your lab report is ready. Open it with the secure link (OTP).") + '<br><span class="small" style="color:var(--acc-300)">nabd.health/r/' + sel.id.toLowerCase() + "</span></div></div>", { icon: "send" });
    var sign = '<div class="row sb"><span class="small faint">' + ui.icon("pen", "xs") + " " + esc(P.n) + " · " + esc(P.t) + ' · <span class="ltr">' + esc(P.r) + '</span></span><div class="btns">' + ui.btn(L("Preview PDF", "معاينة PDF", "PDF प्रीव्यू"), { ov: "labreport", arg: sel.id, icon: "file" }) +
      ui.btn(L("Send back for rerun", "إعادة للتحليل", "दोबारा चलाने भेजें"), { cls: "ghost", act: "toast", arg: "Returned to bench with note", icon: "refresh" }) +
      ui.btn(L("Validate & release", "تحقق وأصدر", "सत्यापित करें और जारी करें"), { cls: "pri", act: "toast", arg: sel.crit ? "Validated · doctor report released; patient copy held until the doctor acknowledges" : sel.id + " validated and released", icon: "shieldcheck" }) + "</div></div>";
    return h + '<div class="split l">' + queue + '<div class="stack s16">' + call + resCard + rel + sign + "</div></div>";
  });

  NB.overlay("criticalcall", function (id) {
    var o = ord(id), p = pat(o);
    return { kind: "modal", title: L("Log critical value call", "تسجيل اتصال قيمة حرجة", "क्रिटिकल कॉल दर्ज करें"), body:
      ui.note("crit", "<b>" + esc(p.name) + " · " + L("Potassium", "البوتاسيوم", "पोटैशियम") + " " + fmtV("K", 6.1) + " mmol/L</b> · " + o.id) +
      '<div class="fgrid">' + ui.field(L("Person informed", "الشخص المُبلَّغ", "किसे सूचित किया"), "Dr. Vikram Shah (treating)", { options: ["Dr. Vikram Shah (treating)", "Dr. Nisha Bhat (covering)", "Anita D'Souza (nurse in charge)", "Medical director", "Patient (no clinician reachable)"], full: true }) +
      ui.field(L("Channel", "القناة", "माध्यम"), L("Phone call", "مكالمة", "फ़ोन कॉल"), { options: [L("Phone call", "مكالمة", "फ़ोन कॉल"), L("In person", "شخصياً", "व्यक्तिगत रूप से"), L("Nabd secure message", "رسالة نبض الآمنة", "नब्ज़ सुरक्षित संदेश")] }) + ui.field(L("Time", "الوقت", "समय"), "10:31") +
      ui.field(L("Outcome", "النتيجة", "परिणाम"), L("Reached, read back", "تم الوصول والتأكيد", "संपर्क हुआ, दोहराया"), { options: [L("Reached, read back", "تم الوصول والتأكيد", "संपर्क हुआ, दोहराया"), L("No answer", "لا إجابة", "कोई जवाब नहीं"), L("Left message", "ترك رسالة", "संदेश छोड़ा")], full: true }) + "</div>" +
      ui.toggle("lbReadBack", L("Read-back confirmed", "تم التأكد بالإعادة", "रीड-बैक पुष्टि"), L("The receiver repeated the patient's name, test and value", "كرر المستلم الاسم والفحص والقيمة", "प्राप्तकर्ता ने नाम, टेस्ट और मान दोहराया"), true) +
      ui.field(L("Notes", "ملاحظات", "नोट"), "Doctor will see the patient now; repeat K and ECG ordered.", { area: true }),
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Save to call-back log", "حفظ في السجل", "लॉग में सेव करें"), { cls: "pri", act: "toast", arg: "Call logged 10:31 · acknowledgement recorded within 12 min", icon: "check" }) };
  });

  NB.overlay("labreport", function (id) {
    var o = ord(id), p = pat(o), R = RES[o.id] || RES["LB-0138"], P = pathologist(), sa = standalone(), f = NB.fac();
    var rows = "";
    Object.keys(R).filter(function (k) { return k !== "prev"; }).forEach(function (tc) {
      rows += '<tr><td colspan="4"><b>' + esc(test(tc).name) + '</b> <span class="mt">· ' + esc(D.method[tc] || "") + "</span></td></tr>";
      R[tc].forEach(function (r) { if (r[1] == null) return; var fl = flagOf(r[0], r[1], p); rows += "<tr><td>" + esc(AN[r[0]].n) + '</td><td class="' + (fl ? "fl" : "") + '">' + fmtV(r[0], r[1]) + (fl ? " " + (fl === "C" ? "(CRITICAL)" : fl) : "") + "</td><td>" + esc(unitOf(r[0])) + "</td><td>" + refTxt(r[0], p) + "</td></tr>"; });
    });
    var lab = sa ? { india: "Sanjeevani Diagnostics, Indore", uae: "Al Noor Diagnostic Laboratory, Dubai", ksa: "Nakheel Diagnostics, Riyadh", qatar: "Doha Diagnostic Lab" }[NB.state.market] : f.name + " · Laboratory";
    return { kind: "drawer", size: "lg", title: L("Report preview", "معاينة التقرير", "रिपोर्ट प्रीव्यू") + " · " + o.id, body:
      '<div class="lb-rep" dir="ltr"><div class="hd"><div><h3>' + esc(lab) + '</h3><div class="mt">' + (india() ? "NABL ISO 15189 · " : "") + "Sat 17 Oct 2026</div></div>" + '<span class="lb-qr">' + (NB.phQr ? NB.phQr(11, 64) : barcode(11, 120, 40)) + "</span></div>" +
      '<div style="display:flex;gap:24px;flex-wrap:wrap;margin-top:10px"><div><span class="mt">Patient</span><br><b>' + esc(p.name) + "</b> · " + p.age + " " + p.sex + '</div><div><span class="mt">Referred by</span><br>' + esc(orderer(o)[0]) + '</div><div><span class="mt">Collected / reported</span><br>' + (o.col || "—") + ' / 10:31</div><div><span class="mt">Accession</span><br>' + o.id + "</div></div>" +
      "<table><thead><tr><th>Test</th><th>Result</th><th>Unit</th><th>Reference</th></tr></thead><tbody>" + rows + "</tbody></table>" +
      '<p style="margin:12px 0 0"><span class="mt">Electronically validated by</span><br><b>' + esc(P.n) + "</b>, " + esc(P.t) + " · " + esc(P.r) + '</p><p class="mt" style="margin:8px 0 0">Scan the QR to verify this report. Results are interpreted with clinical findings.</p></div>',
      foot: ui.btn(L("Close", "إغلاق", "बंद करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Download PDF", "تنزيل PDF", "PDF डाउनलोड"), { act: "toast", arg: "Report PDF downloaded", icon: "download" }) + ui.btn(L("Send on WhatsApp", "إرسال عبر واتساب", "व्हाट्सऐप पर भेजें"), { cls: "pri", act: "toast", arg: "Secure report link sent to " + p.name, icon: "send" }) };
  });

  /* ================= SCREEN: Tests, panels & QC ================= */
  // QC series in SD units from the mean; 20 runs.
  var QC = {
    GLU: { n: "Glucose · control level 1", mean: 98, sd: 2.4, u: "mg/dL", lot: "Lot 26-114", v: [0.3, -0.6, 0.9, -1.0, 0.4, 0.2, -0.3, 1.1, -0.7, 0.5, 0.8, -0.2, 1.3, 0.6, 1.0, 1.4, 1.7, 1.2, 2.3, 2.6] },
    K: { n: "Potassium · control level 2", mean: 5.9, sd: 0.08, u: "mmol/L", lot: "Lot 26-087", v: [0.2, -0.4, 0.6, -0.9, 0.1, 0.7, -0.3, 1.0, -0.6, 0.2, 0.5, -1.1, 0.3, 0.8, -0.2, 0.4, -0.5, 0.9, 0.1, -0.3] },
    HB: { n: "Haemoglobin · control normal", mean: 13.2, sd: 0.15, u: "g/dL", lot: "Lot H-2210", v: [0.1, 0.4, -0.5, 0.8, -0.2, 0.3, -0.9, 0.5, 1.1, -0.4, 0.2, -0.7, 0.6, 2.2, 0.4, -0.3, 0.7, -0.1, 0.5, 0.2] }
  };
  function westgard(v) {
    var last = v.length - 1, out = [];
    var r13 = v.some(function (x) { return Math.abs(x) > 3; });
    var r22 = v[last] > 2 && v[last - 1] > 2 || v[last] < -2 && v[last - 1] < -2;
    var r4s = Math.abs(v[last] - v[last - 1]) > 4 && Math.abs(v[last]) > 2 && Math.abs(v[last - 1]) > 2;
    var r41 = v.slice(-4).every(function (x) { return x > 1; }) || v.slice(-4).every(function (x) { return x < -1; });
    var r10 = v.slice(-10).every(function (x) { return x > 0; }) || v.slice(-10).every(function (x) { return x < 0; });
    var w12 = v.some(function (x) { return Math.abs(x) > 2; });
    return [["1-2s", L("warning: one control beyond 2 SD", "تحذير: قيمة خارج 2 انحراف", "चेतावनी: 2 SD से बाहर"), w12, "warn"], ["1-3s", L("one control beyond 3 SD (random error)", "قيمة خارج 3 انحراف", "3 SD से बाहर"), r13, "crit"],
      ["2-2s", L("two consecutive beyond 2 SD, same side (systematic)", "قيمتان متتاليتان خارج 2 انحراف", "लगातार दो 2 SD से बाहर"), r22, "crit"], ["R-4s", L("range of two controls exceeds 4 SD", "المدى يتجاوز 4 انحراف", "दो कंट्रोल का अंतर 4 SD से अधिक"), r4s, "crit"],
      ["4-1s", L("four consecutive beyond 1 SD, same side", "أربع متتالية خارج 1 انحراف", "लगातार चार 1 SD से बाहर"), r41, "crit"], ["10x", L("ten consecutive on one side of the mean", "عشر متتالية في جهة واحدة", "लगातार दस एक ओर"), r10, "crit"]];
  }
  function ljChart(q) {
    var w = 660, h = 230, pl = 44, pr = 12, pt = 10, pb = 22, n = q.v.length, sx = (w - pl - pr) / (n - 1);
    var y = function (sd) { return pt + (h - pt - pb) * (4 - sd) / 8; };
    var s = '<svg class="lb-qc" viewBox="0 0 ' + w + " " + h + '" direction="ltr" role="img" aria-label="Levey-Jennings chart, ' + esc(q.n) + '">';
    s += '<rect class="band" x="' + pl + '" y="' + y(4) + '" width="' + (w - pl - pr) + '" height="' + (y(3) - y(4)) + '"/><rect class="band" x="' + pl + '" y="' + y(-3) + '" width="' + (w - pl - pr) + '" height="' + (y(-4) - y(-3)) + '"/>';
    [[3, "s3"], [2, "s2"], [1, "s1"], [0, "m"], [-1, "s1"], [-2, "s2"], [-3, "s3"]].forEach(function (l) {
      s += '<line class="' + l[1] + '" x1="' + pl + '" x2="' + (w - pr) + '" y1="' + y(l[0]) + '" y2="' + y(l[0]) + '"/>';
      s += '<text x="' + (pl - 6) + '" y="' + (y(l[0]) + 3) + '" text-anchor="end">' + (l[0] === 0 ? "x̄" : (l[0] > 0 ? "+" : "") + l[0] + "s") + "</text>";
    });
    s += '<polyline class="ln" points="' + q.v.map(function (v, i) { return (pl + i * sx).toFixed(1) + "," + y(v).toFixed(1); }).join(" ") + '"/>';
    q.v.forEach(function (v, i) {
      var cls = Math.abs(v) > 2 ? (i >= n - 2 && Math.abs(q.v[n - 1]) > 2 && Math.abs(q.v[n - 2]) > 2 ? "bad" : "wn") : "";
      s += '<circle class="pt ' + cls + '" cx="' + (pl + i * sx).toFixed(1) + '" cy="' + y(v).toFixed(1) + '" r="' + (cls ? 4.5 : 3.2) + '"><title>Run ' + (i + 1) + ": " + (q.mean + v * q.sd).toFixed(q.sd < 0.5 ? 2 : 1) + " " + q.u + "</title></circle>";
      if (i % 4 === 0 || i === n - 1) s += '<text x="' + (pl + i * sx).toFixed(1) + '" y="' + (h - 6) + '" text-anchor="middle">' + (i === n - 1 ? "17 Oct" : String(i + 1)) + "</text>";
    });
    return s + "</svg>";
  }
  NB.screen("lab.catalog", function (c) {
    var tab = NB.v("lbCat", "tests"), sa = standalone();
    var h = modeBar() + ui.header(L("Tests, panels & QC", "الفحوصات واللوحات والجودة", "टेस्ट, पैनल और QC"), L("Price list, turnaround, outsourcing and daily quality control.", "قائمة الأسعار ووقت الإنجاز والتحويل وضبط الجودة.", "प्राइस लिस्ट, TAT, आउटसोर्सिंग और दैनिक QC।"),
      ui.btn(L("Add test", "إضافة فحص", "टेस्ट जोड़ें"), { act: "toast", arg: "New test form opened", icon: "plus" }));
    h += ui.tabs("lbCat", [["tests", L("Tests", "الفحوصات", "टेस्ट"), "flask"], ["panels", L("Panels", "اللوحات", "पैनल"), "layers"], ["out", L("Outsourcing", "التحويل", "आउटसोर्सिंग"), "truck"], ["qc", L("Quality control", "ضبط الجودة", "गुणवत्ता नियंत्रण"), "activity"]], "tests");
    var b = "";
    if (tab === "tests") {
      b = ui.table([{ h: L("Code", "الرمز", "कोड") }, { h: L("Test", "الفحص", "टेस्ट") }, { h: L("Sample & tube", "العينة والأنبوب", "सैंपल और ट्यूब") }, { h: L("Method", "الطريقة", "विधि") }, { h: "TAT" }, { h: L("Where", "المكان", "कहाँ") }, { h: L("Price", "السعر", "मूल्य"), cls: "r" }, { h: sa ? L("Referrer rate", "سعر الإحالة", "रेफ़रर दर") : L("Staff rate", "سعر الموظفين", "स्टाफ़ दर"), cls: "r" }],
        D.tests.map(function (t) {
          var tk = tubeOf(t.code), T = TUBE[tk];
          return ['<span class="tok">' + t.code + "</span>", ui.cellp(esc(t.name), D.nabl[t.code] && india() ? "NABL scope" : ""), '<span class="row nw" style="gap:8px"><span class="lb-tube" style="--c:var(' + T[1] + ')"></span><span class="small">' + esc(t.sample) + "</span></span>", '<span class="small muted">' + esc(D.method[t.code] || "") + "</span>",
            n(t.tat >= 1440 ? Math.round(t.tat / 1440) + " d" : t.tat >= 60 ? (t.tat / 60) + " h" : t.tat + " min"), t.out ? ui.pill("info", L("Reference lab", "مختبر مرجعي", "रेफ़रेंस लैब"), "truck") : ui.pill("ok", L("In-house", "داخلي", "इन-हाउस"), "home"), NB.money(t.price), NB.money(Math.round(t.price * (sa ? 0.8 : 0.7)))];
        }), { min: 980 });
    } else if (tab === "panels") {
      b = '<div class="grid g2">' + D.panels.map(function (pn) {
        var sum = pn.tests.reduce(function (s, c2) { return s + test(c2).price; }, 0);
        return ui.card(esc(pn.name), chips(pn.tests) + '<dl class="kv" style="margin-top:12px"><dt>' + L("Sum of tests", "مجموع الفحوصات", "टेस्ट का योग") + '</dt><dd><s class="faint">' + NB.money(sum) + "</s></dd><dt>" + L("Panel price", "سعر اللوحة", "पैनल मूल्य") + "</dt><dd>" + NB.money(pn.price) + "</dd><dt>" + L("Patient saves", "يوفر المريض", "मरीज़ की बचत") + "</dt><dd>" + Math.round((1 - pn.price / sum) * 100) + "%</dd><dt>" + L("Tubes", "الأنابيب", "ट्यूब") + "</dt><dd>" + tubesFor({ tests: pn.tests, pt: { age: 40 } }).length + "</dd></dl>", { icon: "layers", right: ui.btn(L("Edit", "تعديل", "संपादित"), { cls: "sm ghost", act: "toast", arg: "Panel editor opened" }) });
      }).join("") + "</div>";
    } else if (tab === "out") {
      var g = NB.gcc(), partner = g ? "Gulf Reference Laboratory, Dubai" : "Metro Reference Laboratory, Mumbai", partner2 = g ? "Emirates Histopathology Centre" : "Central Histopathology Centre, Pune";
      b = '<div class="grid g3 kpis">' + ui.kpi(n(23), L("Samples sent this week", "عينات مرسلة هذا الأسبوع", "इस सप्ताह भेजे सैंपल")) + ui.kpi(n("96%"), L("Returned within partner TAT", "ضمن وقت الشريك", "पार्टनर TAT में")) + ui.kpi(n("11:00, 17:00"), L("Courier pickups", "مواعيد الاستلام", "कूरियर पिकअप"), L("cold box with temperature logger", "صندوق مبرد مع مسجل حرارة", "तापमान लॉगर के साथ"), "faint") + "</div>" +
        ui.table([{ h: L("Test", "الفحص", "टेस्ट") }, { h: L("Partner", "الشريك", "पार्टनर") }, { h: L("Partner cost", "تكلفة الشريك", "पार्टनर लागत"), cls: "r" }, { h: L("Our price", "سعرنا", "हमारी क़ीमत"), cls: "r" }, { h: L("Margin", "الهامش", "मार्जिन"), cls: "r" }, { h: "TAT" }, { h: L("Results back", "عودة النتائج", "रिज़ल्ट वापसी") }],
          [["UCULT", partner, 480, "3 d"], ["HPE", partner2, 1100, "5 d"], ["VITD", partner, 720, "1 d"]].map(function (r) {
            var t = test(r[0]); return [ui.cellp(esc(t.name), r[0]), esc(r[1]), NB.money(r[2]), NB.money(t.price), n(Math.round((1 - r[2] / t.price) * 100) + "%"), n(r[3]), ui.pill("ok", L("HL7 into worklist", "HL7 إلى قائمة العمل", "HL7 से वर्कलिस्ट"), "link")];
          }), { min: 860 }) +
        ui.note("info", L("Outsourced results come back electronically, are reviewed by our pathologist and released under both labs' names. Vitamin D moves in-house when volume passes 15 a day.", "تعود النتائج إلكترونياً وتُراجع قبل الإصدار.", "आउटसोर्स रिज़ल्ट इलेक्ट्रॉनिक रूप से लौटते हैं और हमारे पैथोलॉजिस्ट द्वारा जाँचे जाते हैं।"));
    } else {
      var an = NB.v("lbQc", "GLU"), q = QC[an], wr = westgard(q.v), fail = wr.some(function (r) { return r[2] && r[3] === "crit"; }), warn = wr.some(function (r) { return r[2]; });
      b = '<div class="row sb">' + ui.seg("lbQc", [["GLU", L("Glucose", "الجلوكوز", "ग्लूकोज़")], ["K", L("Potassium", "البوتاسيوم", "पोटैशियम")], ["HB", L("Haemoglobin", "الهيموغلوبين", "हीमोग्लोबिन")]], "GLU") +
        (fail ? ui.pill("crit", L("Run rejected", "تشغيل مرفوض", "रन अस्वीकृत")) : warn ? ui.pill("warn", L("Warning, run accepted", "تحذير، مقبول", "चेतावनी, रन स्वीकृत")) : ui.pill("ok", L("In control", "تحت السيطرة", "नियंत्रण में"))) + "</div>" +
        '<div class="split wide">' + ui.card(esc(q.n) + " · Levey-Jennings", ljChart(q) + '<p class="tiny faint" style="margin:6px 0 0">' + esc(q.lot) + " · mean " + q.mean + " " + esc(q.u) + " · SD " + q.sd + " · " + L("last 20 runs, 2 per day", "آخر 20 تشغيلاً", "पिछले 20 रन") + "</p>", { icon: "activity", right: '<span class="small faint">' + (an === "HB" ? "HA-01" : "CC-01") + "</span>" }) +
        ui.card(L("Westgard rules", "قواعد ويستغارد", "वेस्टगार्ड नियम"), '<div class="lst">' + wr.map(function (r) {
          return ui.li('<span class="tok" style="min-width:42px">' + r[0] + "</span>", esc(r[1]), "", r[2] ? ui.pill(r[3] === "warn" ? "warn" : "crit", r[3] === "warn" ? L("Warning", "تحذير", "चेतावनी") : L("Violated", "مخالفة", "उल्लंघन")) : ui.pill("ok", L("Pass", "ناجح", "पास")));
        }).join("") + "</div>", { icon: "scale" }) + "</div>" +
        (fail ? ui.note("crit", "<b>" + L("2-2s violation on glucose: systematic error.", "مخالفة 2-2s: خطأ منهجي.", "ग्लूकोज़ पर 2-2s उल्लंघन: व्यवस्थित त्रुटि।") + "</b> " + L("Glucose results from CC-01 are on hold. Check reagent lot and calibration, recalibrate, rerun both QC levels, then re-test the 14 patient samples run since the last accepted QC (08:10). Each step is logged for the accreditation audit.", "النتائج معلقة. أعد المعايرة ثم أعد فحص 14 عينة منذ آخر ضبط مقبول.", "CC-01 के ग्लूकोज़ रिज़ल्ट रोके गए। रीएजेंट और कैलिब्रेशन जाँचें, दोबारा QC चलाएँ, फिर पिछली स्वीकृत QC (08:10) के बाद के 14 सैंपल दोबारा जाँचें।") +
          '<div class="btns" style="margin-top:8px">' + ui.btn(L("Start corrective action", "بدء الإجراء التصحيحي", "सुधारात्मक कार्रवाई शुरू करें"), { cls: "sm", act: "toast", arg: "Corrective action CA-0412 opened · 14 samples on hold", icon: "sliders" }) + "</div>")
          : warn ? ui.note("warn", L("One 1-2s warning in the last 20 runs; no rejection rule broken. Keep watching the trend.", "تحذير واحد؛ لا مخالفة.", "पिछले 20 रन में एक 1-2s चेतावनी; कोई अस्वीकृति नियम नहीं टूटा।")) : ui.note("ok", L("All Westgard rules pass. Patient results release normally.", "جميع القواعد ناجحة.", "सभी नियम पास।")));
    }
    return h + b;
  });
})();
