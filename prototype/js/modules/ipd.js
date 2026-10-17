/* IPD module (role "ward"): bed board with NEWS2 early warning, admissions & transfers, medication administration
   record (MAR) with barcode scan, discharge workflow with blockers, and the operation-theatre schedule with the
   WHO surgical safety checklist and consent forms. Data lives in NB.D.ipd; beds come from NB.D.beds. */
(function () {
  "use strict";
  var ui = NB.ui, L = NB.L;

  /* ---------------- Styles (ip- prefix, nb.css tokens only) ---------------- */
  var CSS = [
    ".ip-ews{display:inline-flex;align-items:center;gap:4px;height:22px;padding:0 7px;border-radius:999px;font:600 10.5px/1 var(--font);border:1px solid;white-space:nowrap;font-variant-numeric:tabular-nums}",
    ".ip-ews .ico{width:12px;height:12px}",
    ".ip-ews.ok{color:var(--ok);background:var(--ok-t);border-color:rgba(52,211,153,.38)}",
    ".ip-ews.warn{color:var(--warn);background:var(--warn-t);border-color:rgba(232,179,60,.38)}",
    ".ip-ews.urg{color:var(--urg);background:var(--urg-t);border-color:rgba(255,138,61,.4)}",
    ".ip-ews.crit{color:var(--crit);background:var(--crit-t);border-color:rgba(255,77,79,.45)}",
    ".bed.ip-urg{border-color:rgba(255,138,61,.5);box-shadow:inset 3px 0 0 var(--urg)}",
    ".bed.ip-res{border-color:rgba(88,178,245,.45);border-style:dashed}",
    ".bed .ip-no{font:600 14px/18px var(--font);font-variant-numeric:tabular-nums}",
    ".bed .ip-pn{font-weight:600;font-size:13.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
    ".bed small{display:block;color:var(--tx3);font-size:11.5px;line-height:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
    ".bed .ip-fl{display:flex;flex-wrap:wrap;gap:4px;margin-top:2px}",
    ".bed:hover{background:var(--s3)}.bed.free:hover{background:var(--s1)}",
    ".ip-wh{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:12px}.ip-wh h2{flex:1;min-width:160px}",
    ".ip-wh .bar{width:140px}",
    ".ip-leg{display:flex;flex-wrap:wrap;gap:6px;align-items:center}",
    ".ip-spark{display:block}",
    ".ip-req{display:flex;flex-direction:column;gap:10px}",
    ".ip-req .hd{display:flex;gap:12px;align-items:flex-start;flex-wrap:wrap}",
    ".ip-req .hd .fl b{display:block;font-size:14.5px}",
    ".ip-sug{display:flex;gap:10px;align-items:center;padding:10px 12px;border-radius:var(--r-md);background:var(--acc-tint);border:1px solid var(--acc-line);font-size:12.5px;flex-wrap:wrap}",
    ".ip-sug b{font-size:13px}",
    ".ip-dose{display:inline-flex;align-items:center;gap:4px;height:26px;padding:0 8px;border-radius:8px;font:600 11px/1 var(--font);border:1px solid var(--b-sub);background:transparent;color:var(--tx3);white-space:nowrap;font-variant-numeric:tabular-nums}",
    "button.ip-dose{cursor:pointer}",
    ".ip-dose .ico{width:12px;height:12px}",
    ".ip-dose.given{color:var(--ok);background:var(--ok-t);border-color:rgba(52,211,153,.35)}",
    ".ip-dose.due{color:var(--acc);background:var(--acc-tint);border-color:var(--acc-line)}",
    ".ip-dose.over{color:var(--crit);background:var(--crit-t);border-color:rgba(255,77,79,.45)}",
    ".ip-dose.held{color:var(--tx2);background:var(--perm-t);border-color:rgba(255,255,255,.18);border-style:dashed}",
    ".ip-mar td{padding:8px 10px}.ip-mar th.now{color:var(--acc)}",
    ".ip-mar td.now{background:var(--acc-tint)}",
    ".ip-scan{display:flex;gap:12px;align-items:center;padding:12px 14px;border-radius:var(--r-md);border:1px dashed var(--b-str);background:var(--s2)}",
    ".ip-scan.ok{border-style:solid;border-color:rgba(52,211,153,.45);background:var(--ok-t)}",
    ".ip-scan .t{flex:1;min-width:0}.ip-scan b{display:block;font-size:13.5px}.ip-scan small{display:block;color:var(--tx3);font-size:12px}",
    ".ip-ck{display:flex;flex-direction:column}",
    ".ip-ck .ci{display:flex;gap:10px;align-items:flex-start;padding:11px 0;border-bottom:1px solid var(--div)}.ip-ck .ci:last-child{border-bottom:0}",
    ".ip-ck .ci .t{flex:1;min-width:0}.ip-ck .ci b{display:block;font-size:13.5px;font-weight:500}.ip-ck .ci small{display:block;color:var(--tx3);font-size:12px;line-height:16px}",
    ".ip-ck .ci .ico{margin-top:1px}",
    ".ip-ck .ci.click{cursor:pointer}.ip-ck .ci.click:hover b{color:var(--acc-300)}",
    ".ip-ot{overflow-x:auto;border:1px solid var(--b-sub);border-radius:var(--r-lg);background:var(--s1)}",
    ".ip-otin{min-width:940px;padding:12px 14px 14px}",
    ".ip-otr{display:grid;grid-template-columns:96px minmax(0,1fr);gap:10px;align-items:stretch}",
    ".ip-otr+.ip-otr{margin-top:8px}",
    ".ip-otl{display:flex;flex-direction:column;justify-content:center;font-size:13px;font-weight:600}.ip-otl small{color:var(--tx3);font-weight:400;font-size:11px}",
    ".ip-hrs{display:grid;grid-template-columns:repeat(12,minmax(0,1fr));font:600 10.5px/14px var(--font);color:var(--tx3);font-variant-numeric:tabular-nums}",
    ".ip-hrs span{border-inline-start:1px solid var(--b-def);padding-inline-start:4px}",
    ".ip-lane{position:relative;display:grid;grid-template-columns:repeat(24,minmax(0,1fr));min-height:68px;border-radius:var(--r-md);background-color:var(--sunk);background-image:linear-gradient(90deg,var(--div) 1px,transparent 1px);background-size:calc(100% / 12) 100%}",
    "[dir=rtl] .ip-lane{background-image:linear-gradient(270deg,var(--div) 1px,transparent 1px)}",
    ".ip-case{grid-row:1;margin:4px 2px;border-radius:10px;border:1px solid var(--b-def);background:var(--s2);color:var(--tx);padding:6px 8px;text-align:start;cursor:pointer;min-width:0;overflow:hidden;font:inherit;display:flex;flex-direction:column;gap:2px}",
    ".ip-case b{font-size:12px;line-height:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.ip-case small{font-size:10.5px;line-height:13px;color:var(--tx3);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
    ".ip-case b,.ip-case small{unicode-bidi:plaintext}.ip-case.done{opacity:.6}.ip-case.live{background:var(--acc-tint2);border-color:var(--acc)}.ip-case.delayed{background:var(--warn-t);border-color:rgba(232,179,60,.55)}",
    ".ip-case.emerg{border-color:rgba(255,77,79,.6);box-shadow:inset 3px 0 0 var(--crit)}.ip-case.to{background:transparent;border-style:dashed;color:var(--tx3);cursor:default;align-items:center;justify-content:center}",
    ".ip-case.on{outline:2px solid var(--acc-300);outline-offset:1px}",
    ".ip-now{position:absolute;top:-4px;bottom:-4px;width:2px;background:var(--crit);border-radius:2px;pointer-events:none;z-index:1}",
    ".ip-now::before{content:'';position:absolute;top:-3px;inset-inline-start:-3px;width:8px;height:8px;border-radius:99px;background:var(--crit)}",
    ".ip-tbar{display:flex;flex-direction:column;gap:6px}",
    "@media (max-width:760px){.ip-wh .bar{width:100%}}"
  ].join("\n");
  if (!document.getElementById("ip-css")) { var st = document.createElement("style"); st.id = "ip-css"; st.textContent = CSS; document.head.appendChild(st); }

  /* ---------------- NEWS2 (Royal College of Physicians, SpO2 scale 1) ---------------- */
  function sc(v, bands) { for (var i = 0; i < bands.length; i++) if (v <= bands[i][0]) return bands[i][1]; return bands[bands.length - 1][1]; }
  function news2(v) {
    var parts = [
      ["Respiration rate", v.rr + " /min", sc(v.rr, [[8, 3], [11, 1], [20, 0], [24, 2], [999, 3]])],
      ["SpO₂ (scale 1)", v.spo2 + "%", sc(v.spo2, [[91, 3], [93, 2], [95, 1], [100, 0]])],
      ["Air or oxygen", v.o2 ? "Oxygen " + v.o2 : "Air", v.o2 ? 2 : 0],
      ["Systolic BP", v.sbp + " mmHg", sc(v.sbp, [[90, 3], [100, 2], [110, 1], [219, 0], [999, 3]])],
      ["Pulse", v.hr + " /min", sc(v.hr, [[40, 3], [50, 1], [90, 0], [110, 1], [130, 2], [999, 3]])],
      ["Consciousness", v.cvpu ? "New confusion" : "Alert", v.cvpu ? 3 : 0],
      ["Temperature", v.t.toFixed(1) + " °C", sc(v.t, [[35.0, 3], [36.0, 1], [38.0, 0], [39.0, 1], [99, 2]])]
    ];
    var total = parts.reduce(function (a, p) { return a + p[2]; }, 0), red = parts.some(function (p) { return p[2] === 3; });
    return { parts: parts, total: total, level: total >= 7 ? "crit" : total >= 5 ? "urg" : red ? "warn" : "ok" };
  }
  var LV = {
    ok: ["Low", "Minimum 12-hourly observations (4–6-hourly if score 1–4). Registered nurse assesses."],
    warn: ["Low–medium", "A single parameter scores 3: at least hourly observations and an urgent ward-based review."],
    urg: ["Medium", "Urgent response: at least hourly observations and a review by a clinician competent in acute illness."],
    crit: ["High", "Emergency response: continuous monitoring and immediate review by the critical-care outreach team."]
  };
  function ewsBadge(b, x) {
    if (x && x.pews) return '<span dir="auto" class="ip-ews ' + (b.news >= 5 ? "urg" : b.news >= 3 ? "warn" : "ok") + '">' + ui.icon("activity") + "PEWS " + b.news + "</span>";
    var lv = x && x.v ? news2(x.v).level : b.news >= 7 ? "crit" : b.news >= 5 ? "urg" : "ok", n = x && x.v ? news2(x.v).total : b.news;
    return '<span dir="auto" class="ip-ews ' + lv + '" title="NEWS2 ' + LV[lv][0] + '">' + ui.icon(lv === "ok" ? "activity" : lv === "crit" ? "alertfill" : "alertcircle") + "NEWS2 " + n + "</span>";
  }
  function spark(arr, w, h) {
    var mx = 10, step = w / (arr.length - 1);
    var pts = arr.map(function (v, i) { return (i * step).toFixed(1) + "," + (h - v / mx * h).toFixed(1); }).join(" ");
    return '<svg class="ip-spark" width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + " " + h + '" aria-label="NEWS2 trend"><line x1="0" x2="' + w + '" y1="' + (h - 5 / mx * h) + '" y2="' + (h - 5 / mx * h) + '" style="stroke:var(--warn);stroke-dasharray:3 3;opacity:.6"/>' +
      '<polyline points="' + pts + '" fill="none" style="stroke:var(--acc)" stroke-width="2" stroke-linejoin="round"/></svg>';
  }

  /* ---------------- Sample data (NB.D.ipd) ---------------- */
  var D = NB.D.ipd = {
    bx: {
      "A-01": { sex: "M", mrn: "SUV-IP-2288", adm: "14 Oct", ins: 0, pa: "PA-2214 · query raised", lang: "Kannada", allergy: null, diet: "Diabetic soft", v: { rr: 22, spo2: 95, o2: "", sbp: 118, hr: 96, cvpu: false, t: 37.8 }, trend: [5, 5, 4, 4, 4, 4] },
      "A-02": { sex: "F", mrn: "SUV-IP-2279", adm: "12 Oct", ins: 2, pa: "PA-2211 · partially approved", lang: "Tamil", allergy: "Sulfonamides (rash)", diet: "Low salt, 1.2 L fluid", v: { rr: 24, spo2: 93, o2: "2 L/min", sbp: 112, hr: 88, cvpu: false, t: 36.6 }, trend: [4, 4, 5, 5, 6, 6] },
      "A-05": { sex: "M", mrn: "SUV-IP-2293", adm: "14 Oct", ins: null, pa: "Self-pay · advance received", lang: "Malayalam", allergy: null, diet: "Normal", v: { rr: 16, spo2: 96, o2: "", sbp: 108, hr: 92, cvpu: false, t: 36.9 }, trend: [4, 3, 3, 2, 2, 2] },
      "A-06": { sex: "F", mrn: "SUV-IP-2284", adm: "13 Oct", ins: 1, pa: "Cashless · final approval pending", lang: "Hindi", allergy: null, diet: "Diabetic", v: { rr: 20, spo2: 97, o2: "", sbp: 104, hr: 104, cvpu: false, t: 35.9 }, trend: [7, 6, 5, 4, 3, 3] },
      "P-01": { sex: "M", mrn: "SUV-IP-2295", adm: "16 Oct", ins: null, pa: "Self-pay", lang: "Kannada", allergy: null, diet: "Toddler, breastfed", pews: true, guardian: "Mother (Sowmya S.)", trend: [4, 4, 3, 3, 3, 3] },
      "ICU-1": { sex: "M", mrn: "SUV-IP-2290", adm: "15 Oct", ins: 0, pa: "PA-2207 · approved", lang: "Telugu", allergy: null, diet: "NPO → clear liquids", v: { rr: 23, spo2: 94, o2: "4 L/min", sbp: 98, hr: 86, cvpu: false, t: 37.4 }, trend: [8, 8, 7, 7, 7, 7] },
      "ICU-2": { sex: "F", mrn: "SUV-IP-2281", adm: "11 Oct", ins: 4, pa: "Denied → self-pay estimate", lang: "Tamil", allergy: null, diet: "Soft", v: { rr: 21, spo2: 95, o2: "", sbp: 104, hr: 102, cvpu: false, t: 37.9 }, trend: [9, 8, 7, 6, 5, 5] }
    },
    requests: [
      { id: "ADM-312", name: "Rakesh Menon", age: 58, sex: "M", src: "OPD", from: "OPD T-15 · Dr. Vikram Shah", dx: "Fever 3 days, uncontrolled diabetes, ?pyelonephritis", ward: "General Ward A", prio: "urgent", ins: 0, money: "Pre-auth PA-2219 submitted 18 min ago", est: 62000, allergy: "Penicillin · severe (anaphylaxis)", wait: 14,
        sug: [["A-03", "Free · male bay · 4 m from nursing station · no isolation needs"], ["A-04", "Cleaning · ready 10:40"]] },
      { id: "ADM-311", name: "Arjun Nair", age: 12, sex: "M", src: "OPD", from: "OPD T-19 · Dr. Nisha Bhat", dx: "Acute otitis media, suspected mastoiditis · IV antibiotics", ward: "Paediatric Ward", prio: "urgent", ins: 4, money: "Pre-auth PA-2216 is a draft · 2 documents missing", est: 48000, dep: 15000, wait: 22,
        sug: [["P-03", "Reserved for this patient at 09:58 · guardian bed alongside"], ["P-02", "Free · bay 2"]] },
      { id: "ADM-313", name: "Salim Khan", age: 61, sex: "M", src: "ER", from: "Emergency · Dr. on duty (ER)", dx: "Chest pain, ST depression on ECG, troponin pending · rule out NSTEMI", ward: "ICU", prio: "emergency", ins: null, money: "Emergency: treat first, deposit not required before care", est: 120000, wait: 6,
        sug: [["ICU-3", "Only free ICU bed · monitor and defibrillator checked 09:00"]] },
      { id: "ADM-314", name: "Neha Gupta", age: 29, sex: "F", src: "Elective", from: "Planned · Dr. Anand Kulkarni (surgery)", dx: "Laparoscopic cholecystectomy · tomorrow 08:00, OT-2", ward: "General Ward A", prio: "planned", ins: 1, money: "Pre-auth PA-2203 approved in full", est: 95000, wait: 0,
        sug: [["A-05", "Frees today after Joseph Mathew's discharge · female bay swap requested"]] }
    ],
    transfers: [
      { who: "Lakshmi Devi", from: "A-02", to: "ICU / HDU", type: "Escalation · NEWS2 6", st: "contested", note: "ICU-3 is also wanted by ER (ADM-313). Bed manager decides." },
      { who: "Meenakshi P.", from: "ICU-2", to: "General Ward A · A-04", type: "ICU step-down", st: "criteria", note: "4 of 6 step-down criteria met · awaiting intensivist sign-off" },
      { who: "Shabana Begum", from: "ICU-3", to: "General Ward A · A-06", type: "ICU step-down", st: "done", note: "Completed 13 Oct 18:20 · SBAR handover signed" }
    ],
    // MAR: slots by hour (06..22). Status: given:hh:mm · due · over · held:reason · sched
    mar: [
      { id: "r1", bed: "A-01", drug: "Ceftriaxone 1 g", route: "IV", freq: "OD 08:00", slots: { "08": "over" }, order: "Dr. Vikram Shah", note: "Reconstitute in 10 ml WFI · give over 3–5 min", batch: "CFX2509 · exp 03/2027" },
      { id: "r2", bed: "A-01", drug: "Azithromycin 500 mg", route: "PO", freq: "OD 10:00", slots: { "10": "due" }, order: "Dr. Vikram Shah", batch: "AZT2504 · exp 01/2027" },
      { id: "r3", bed: "A-01", drug: "Paracetamol 1 g", route: "PO", freq: "PRN 6-hourly · max 4 g/day", prn: true, last: "04:10", today: "1 g of 4 g", order: "Dr. Vikram Shah", batch: "PCM2501 · exp 01/2027" },
      { id: "r4", bed: "A-02", drug: "Furosemide 40 mg", route: "IV", freq: "BD 08:00, 20:00", slots: { "08": "given:08:06", "20": "sched" }, order: "Dr. Khalid Al-Saeed", batch: "FRS2503 · exp 09/2027" },
      { id: "r5", bed: "A-02", drug: "Metoprolol succinate 25 mg", route: "PO", freq: "BD 08:00, 20:00", slots: { "08": "held:SBP 96 mmHg at 08:00 · Dr. Khalid informed", "20": "sched" }, check: "Hold if SBP < 100 mmHg or pulse < 55", order: "Dr. Khalid Al-Saeed", batch: "MTP2502 · exp 06/2027" },
      { id: "r6", bed: "A-02", drug: "Enoxaparin 40 mg", route: "SC", freq: "OD 10:00", slots: { "10": "due" }, high: true, order: "Dr. Khalid Al-Saeed", check: "Platelets 212 ×10⁹/L (06:00)", batch: "ENX2506 · exp 11/2027" },
      { id: "r7", bed: "A-05", drug: "Amoxicillin-clavulanate 625 mg", route: "PO", freq: "TDS 06, 14, 22", slots: { "06": "given:06:12", "14": "sched", "22": "sched" }, order: "Dr. Vikram Shah", batch: "AMC2505 · exp 02/2027" },
      { id: "r8", bed: "A-06", drug: "Insulin aspart · sliding scale", route: "SC", freq: "Before meals 08, 12, 18", slots: { "08": "given:07:58", "12": "sched", "18": "sched" }, high: true, check: "Capillary glucose before every dose", order: "Dr. Vikram Shah", batch: "ASP2504 · pen" },
      { id: "r9", bed: "A-06", drug: "Insulin glargine 18 units", route: "SC", freq: "OD 22:00", slots: { "22": "sched" }, high: true, order: "Dr. Vikram Shah", batch: "GLA2503 · pen" },
      { id: "r10", bed: "A-06", drug: "Potassium chloride 20 mmol", route: "PO", freq: "BD 10:00, 22:00", slots: { "10": "due", "22": "sched" }, check: "Last K⁺ 3.4 mmol/L (06:00)", order: "Dr. Vikram Shah", batch: "KCL2502 · exp 08/2027" },
      { id: "r11", bed: "A-06", drug: "Ondansetron 4 mg", route: "IV", freq: "PRN 8-hourly · nausea", prn: true, last: "—", today: "0 of 3 doses", order: "Dr. Vikram Shah", batch: "OND2501 · exp 05/2027" }
    ],
    dis: [
      { bed: "A-05", decided: "08:30", el: 110, target: 120, lang: "Malayalam", pay: "Self-pay", items: [
        ["sum", "Discharge summary signed", "done", "Dr. Vikram Shah · 09:12 · diagnosis, procedures, meds, red flags"],
        ["meds", "Medicines reconciled, take-home meds dispensed", "wait", "Pharmacy · TTO pack ready 10:40 · amoxicillin-clavulanate 5 days, paracetamol PRN"],
        ["bill", "Final bill cleared", "block", "Balance after advance is with the billing desk"],
        ["fu", "Follow-up booked", "done", "Dr. Vikram Shah · 24 Oct 10:30 · WhatsApp confirmation sent"],
        ["edu", "Patient education in Malayalam", "todo", "Wound care, leg elevation, antibiotic course · video + teach-back"],
        ["line", "IV cannula removed, wound checked", "done", "Sr. Mary Joseph · 09:30"],
        ["docs", "Reports, belongings and discharge papers returned", "todo", "Doppler report, lab reports, ID"],
        ["trans", "Transport home", "done", "Family car · 11:00"]] },
      { bed: "A-06", decided: "09:40", el: 40, target: 180, lang: "Hindi", pay: "Cashless", items: [
        ["sum", "Discharge summary signed", "block", "Draft ready · waiting for Dr. Vikram Shah's signature"],
        ["meds", "Medicines reconciled, take-home meds dispensed", "wait", "Insulin pens and glucometer strips · pharmacy notified"],
        ["bill", "Insurer final approval", "wait", "Sent 09:52 to insurer · IRDAI target 3 h · 28 min so far"],
        ["fu", "Follow-up booked", "todo", "Diabetes clinic in 1 week + HbA1c in 3 months"],
        ["edu", "Patient education in Hindi", "todo", "Insulin technique, hypoglycaemia signs, sick-day rules · teach-back"],
        ["line", "IV cannula removed", "todo", ""],
        ["docs", "Reports and belongings returned", "todo", ""],
        ["trans", "Transport home", "done", "Son · 13:00"]] },
      { bed: "P-01", decided: "Planned tomorrow 11:00", el: 0, target: 0, lang: "Kannada", pay: "Self-pay", items: [
        ["sum", "Discharge summary signed", "todo", "Dr. Nisha Bhat · after morning round"], ["meds", "Medicines reconciled", "todo", "Salbutamol nebules → inhaler with spacer"],
        ["bill", "Final bill cleared", "todo", "Interim bill sent to mother on WhatsApp"], ["fu", "Follow-up booked", "done", "Dr. Nisha Bhat · 23 Oct"],
        ["edu", "Parent education in Kannada", "todo", "Spacer technique, breathing danger signs"], ["line", "IV cannula removed", "todo", ""], ["docs", "Reports returned", "todo", ""], ["trans", "Transport home", "todo", ""]] }
    ],
    ot: {
      rooms: [["OT-1", "Cardiac"], ["OT-2", "General surgery"], ["OT-3", "Day care / minor"]],
      cases: [
        { id: "C1", room: 0, s: 7.5, e: 12.5, proc: "CABG ×3 (on-pump)", pt: "Harish Kumar", age: 64, sex: "M", surg: "Dr. Khalid Al-Saeed", an: "Dr. Ravi Prasad", st: "live", stage: 2, side: "N/A", anType: "General", blood: "4 units PRBC cross-matched", cons: { surg: "signed", an: "signed", blood: "signed", hr: "signed" }, lang: "Telugu" },
        { id: "C2", room: 0, s: 14, e: 15.5, proc: "Permanent pacemaker (DDD)", pt: "Gopal Rao", age: 72, sex: "M", surg: "Dr. Khalid Al-Saeed", an: "Dr. Ravi Prasad", st: "sched", stage: 0, side: "Left chest", anType: "Local + sedation", blood: "Group & save", cons: { surg: "signed", an: "signed", blood: "na", hr: "pending" }, lang: "Kannada" },
        { id: "C3", room: 1, s: 8, e: 9.5, proc: "Laparoscopic cholecystectomy", pt: "Divya Menon", age: 38, sex: "F", surg: "Dr. Anand Kulkarni", an: "Dr. Leela Iyer", st: "done", stage: 3, side: "N/A", anType: "General", blood: "Group & save", cons: { surg: "signed", an: "signed", blood: "na", hr: "na" }, lang: "Malayalam" },
        { id: "TO1", room: 1, s: 9.5, e: 10, to: true },
        { id: "C4", room: 1, s: 10, e: 11.5, proc: "Debridement, diabetic foot (right)", pt: "Sunil Varghese", age: 55, sex: "M", surg: "Dr. Anand Kulkarni", an: "Dr. Leela Iyer", st: "delayed", delay: "Start moved to 10:35 · anaesthesia consent pending", stage: 0, side: "Right foot · marked", anType: "Spinal", blood: "Group & save", cons: { surg: "signed", an: "pending", blood: "signed", hr: "na" }, lang: "Malayalam" },
        { id: "C5", room: 1, s: 12, e: 13.5, proc: "Inguinal hernia repair (mesh), left", pt: "Ali Hassan", age: 46, sex: "M", surg: "Dr. Anand Kulkarni", an: "Dr. Leela Iyer", st: "sched", stage: 0, side: "Left groin", anType: "Spinal", blood: "Not required", cons: { surg: "signed", an: "signed", blood: "na", hr: "na" }, lang: "Urdu" },
        { id: "C6", room: 1, s: 14.5, e: 16, proc: "Laparoscopic appendicectomy · emergency add-on", pt: "Ritu Sharma", age: 24, sex: "F", surg: "Dr. Anand Kulkarni", an: "Dr. Leela Iyer", st: "emerg", stage: 0, side: "N/A", anType: "General", blood: "Group & save", cons: { surg: "pending", an: "pending", blood: "na", hr: "na" }, lang: "Hindi" },
        { id: "C7", room: 2, s: 8.5, e: 9, proc: "Excision of lipoma, back", pt: "Kiran Shetty", age: 41, sex: "M", surg: "Dr. Meera Iyer", an: "Local", st: "done", stage: 3, side: "Upper back", anType: "Local", blood: "Not required", cons: { surg: "signed", an: "na", blood: "na", hr: "na" }, lang: "Kannada" },
        { id: "C8", room: 2, s: 9.5, e: 10.5, proc: "Diagnostic cystoscopy", pt: "Mohan Das", age: 67, sex: "M", surg: "Dr. Rahul Menon", an: "Dr. Leela Iyer", st: "live", stage: 2, side: "N/A", anType: "Sedation", blood: "Not required", cons: { surg: "signed", an: "signed", blood: "na", hr: "na" }, lang: "Malayalam" },
        { id: "C9", room: 2, s: 11, e: 11.75, proc: "Carpal tunnel release, right", pt: "Farah Naaz", age: 52, sex: "F", surg: "Dr. Rahul Menon", an: "Regional block", st: "sched", stage: 0, side: "Right wrist · marked", anType: "Regional", blood: "Not required", cons: { surg: "signed", an: "signed", blood: "na", hr: "na" }, lang: "Urdu" },
        { id: "C10", room: 2, s: 15, e: 15.5, proc: "Removal of K-wire, left hand", pt: "Arvind Gowda", age: 33, sex: "M", surg: "Dr. Rahul Menon", an: "Local", st: "sched", stage: 0, side: "Left hand", anType: "Local", blood: "Not required", cons: { surg: "pending", an: "na", blood: "na", hr: "na" }, lang: "Kannada" }
      ]
    }
  };

  /* ---------------- Helpers ---------------- */
  var NOW = 10 + 20 / 60; // 10:20 in the demo
  function allBeds() { var out = []; NB.D.beds.forEach(function (w) { w.beds.forEach(function (b) { out.push({ w: w.ward, b: b }); }); }); return out; }
  function bedByNo(no) { return allBeds().find(function (x) { return x.b.no === no; }); }
  function bedState(b) { var cl = NB.v("ipClean", {}); return b.st === "clean" && cl[b.no] ? "free" : b.st; }
  function payer(i) { return i == null ? L("Self-pay", "دفع ذاتي", "स्व-भुगतान") : NB.billing ? NB.billing.payerName(i) : "Insurance"; }
  function hhmm(h) { var hh = Math.floor(h), mm = Math.round((h - hh) * 60); return (hh < 10 ? "0" : "") + hh + ":" + (mm < 10 ? "0" : "") + mm; }
  function short(name) { return String(name || "").replace(/^Dr\.\s*/, "Dr. ").split(" ").slice(0, 2).join(" "); }
  function ckIcon(st) { return st === "done" ? ui.icon("checkcircle", "sm okc") : st === "block" ? ui.icon("alertfill", "sm critc") : st === "wait" ? ui.icon("hourglass", "sm warnc") : ui.icon("circle", "sm faint"); }
  NB.act("ipgo", function (a) { var p = String(a).split("|"); if (p[2]) NB.state.ui[p[2]] = p[3]; var r = p[0].split("."); NB.go(r[0], r[1]); });

  /* ================= ward.beds ================= */
  NB.screen("ward.beds", function (c) {
    var f = NB.v("ipWard", "all"), list = allBeds(), dis = NB.v("ipDisReady", {});
    var cnt = function (s) { return list.filter(function (x) { return bedState(x.b) === s; }).length; };
    var occ = cnt("occ"), tot = list.length, alerts = list.filter(function (x) { var e = D.bx[x.b.no]; return x.b.st === "occ" && e && !e.pews && news2(e.v).total >= 5; });
    var h = ui.header(L("Bed board", "لوحة الأسرّة", "बेड बोर्ड"), "Live · " + tot + " beds in 3 wards · NEWS2 from the latest observations · updated 10:18",
      ui.btn(L("Transfer", "تحويل", "ट्रांसफ़र"), { ov: "transfer", icon: "arrowright" }) + ui.btn(L("Admit patient", "إدخال مريض", "मरीज़ भर्ती करें"), { cls: "pri", ov: "admitpatient", icon: "userplus" }));
    h += '<div class="grid g4 kpis">' + ui.kpi('<span class="num">' + occ + "/" + tot + "</span>", L("Occupied", "مشغول", "भरे हुए"), Math.round(occ / tot * 100) + "% occupancy", "faint") +
      ui.kpi('<span class="num">' + cnt("free") + "</span>", L("Free now", "متاح الآن", "अभी खाली"), cnt("clean") + " cleaning · " + cnt("reserved") + " reserved", "okc") +
      ui.kpi('<span class="num">' + (1 + Object.keys(dis).length) + "</span>", L("Discharge-ready", "جاهز للخروج", "डिस्चार्ज के लिए तैयार"), "A-05 frees a bed today", "faint") +
      ui.kpi('<span class="num">' + alerts.length + "</span>", L("NEWS2 5 or more", "NEWS2 ٥ أو أكثر", "NEWS2 5 या अधिक"), alerts.map(function (x) { return x.b.no; }).join(" · "), "critc") + "</div>";
    h += '<div class="row sb">' + ui.seg("ipWard", [["all", L("All wards", "كل الأجنحة", "सभी वार्ड")], ["General Ward A", "Ward A"], ["Paediatric Ward", L("Paediatrics", "الأطفال", "बाल")], ["ICU", "ICU"]]) +
      '<div class="ip-leg">' + ['<span dir="auto" class="ip-ews ok">' + ui.icon("activity") + "0–4 low</span>", '<span dir="auto" class="ip-ews warn">' + ui.icon("alertcircle") + "3 in one</span>", '<span dir="auto" class="ip-ews urg">' + ui.icon("alertcircle") + "5–6 medium</span>", '<span dir="auto" class="ip-ews crit">' + ui.icon("alertfill") + "7+ high</span>"].join("") + "</div></div>";
    NB.D.beds.forEach(function (w) {
      if (f !== "all" && f !== w.ward) return;
      var o = w.beds.filter(function (b) { return b.st === "occ"; }).length;
      h += ui.card("", '<div class="ip-wh"><h2 class="h">' + ui.esc(w.ward) + '</h2><span class="small muted"><span class="num">' + o + "/" + w.beds.length + "</span> " + L("occupied", "مشغول", "भरे") + "</span>" + ui.bar(o / w.beds.length * 100, o / w.beds.length > 0.85 ? "warn" : "") + "</div>" +
        '<div class="beds">' + w.beds.map(function (b) { return tile(b); }).join("") + "</div>");
    });
    var esc = ui.card(L("Escalations", "التصعيد", "एस्केलेशन"), '<div class="lst">' + alerts.map(function (x) {
      var e = D.bx[x.b.no], n = news2(e.v);
      return ui.li(ewsBadge(x.b, e), ui.esc(x.b.no + " · " + x.b.p), LV[n.level][1], ui.icon("chevright", "sm faint flip"), { ov: "beddetail", arg: x.b.no });
    }).join("") + "</div>", { icon: "alertcircle", cls: "warnb" });
    var flow = ui.card(L("Bed flow today", "حركة الأسرّة اليوم", "आज बेड प्रवाह"), '<div class="lst">' +
      ui.li('<span class="av">' + ui.icon("userplus", "sm") + "</span>", "4 admissions waiting", "2 from OPD · 1 from ER · 1 elective tomorrow", ui.btn("Open", { cls: "sm", go: "ward.admit" })) +
      ui.li('<span class="av">' + ui.icon("logout", "sm") + "</span>", "2 discharges in progress", "A-05 blocked on final bill · A-06 waiting for insurer", ui.btn("Open", { cls: "sm", go: "ward.discharge" })) +
      ui.li('<span class="av">' + ui.icon("sparkle", "sm") + "</span>", "Housekeeping", "A-04 terminal clean · ready 10:40", ui.btn("Mark ready", { cls: "sm", act: "ipclean", arg: "A-04" })) + "</div>", { icon: "bed" });
    return h + '<div class="grid g2">' + esc + flow + "</div>";
  });
  function tile(b) {
    var s = bedState(b), e = D.bx[b.no], dis = b.dis || NB.v("ipDisReady", {})[b.no];
    if (s === "occ") {
      var lv = e && e.v ? news2(e.v).level : "ok";
      return '<div class="bed occ' + (lv === "crit" ? " crit" : lv === "urg" ? " ip-urg" : "") + '" data-ov="beddetail" data-arg="' + b.no + '" role="button" tabindex="0">' +
        '<div class="row sb nw"><span class="ip-no ltr">' + b.no + "</span>" + ewsBadge(b, e) + "</div>" +
        '<div class="ip-pn" dir="auto">' + ui.esc(b.p) + "</div><small dir=\"auto\">" + b.age + " " + (e ? e.sex : "") + " · " + ui.esc(b.dx) + "</small><small dir=\"auto\">" + L("Day ", "اليوم ", "दिन ") + (b.los + 1) + " · " + ui.esc(short(b.doc)) + "</small>" +
        '<div class="ip-fl">' + (dis ? ui.pill("ok", L("Discharge-ready", "جاهز للخروج", "डिस्चार्ज तैयार"), "logout") : "") + (e && e.allergy ? ui.pill("crit", L("Allergy", "حساسية", "एलर्जी"), "alertfill") : "") + (e && e.v && e.v.o2 ? ui.pill("info", "O₂", "pulse") : "") + "</div></div>";
    }
    if (s === "free") return '<div class="bed free" data-ov="admitpatient" data-arg="bed:' + b.no + '" role="button" tabindex="0"><div class="row sb nw"><span class="ip-no ltr">' + b.no + "</span>" + ui.pill("ok", L("Free", "متاح", "खाली"), "check") + '</div><small dir="auto" style="margin-top:8px">' + L("Ready for admission", "جاهز للإدخال", "भर्ती के लिए तैयार") + '</small><small dir="auto" class="acc">' + ui.icon("plus", "xs") + " " + L("Assign patient", "تخصيص مريض", "मरीज़ असाइन करें") + "</small></div>";
    if (s === "clean") return '<div class="bed clean" data-act="ipclean" data-arg="' + b.no + '" role="button" tabindex="0"><div class="row sb nw"><span class="ip-no ltr">' + b.no + "</span>" + ui.pill("warn", L("Cleaning", "تنظيف", "सफ़ाई"), "sparkle") + '</div><small dir="auto" style="margin-top:8px">' + ui.esc(b.note || "") + '</small><small dir="auto" class="acc">' + L("Tap when ready", "اضغط عند الجاهزية", "तैयार होने पर टैप करें") + "</small></div>";
    return '<div class="bed ip-res" data-ov="admitpatient" data-arg="ADM-311" role="button" tabindex="0"><div class="row sb nw"><span class="ip-no ltr">' + b.no + "</span>" + ui.pill("info", L("Reserved", "محجوز", "आरक्षित"), "lock") + '</div><small dir="auto" style="margin-top:8px">' + ui.esc(b.note || "") + '</small><small dir="auto" class="acc">' + L("Admit now", "إدخال الآن", "अभी भर्ती करें") + "</small></div>";
  }
  NB.act("ipclean", function (no) { var m = Object.assign({}, NB.v("ipClean", {})); m[no] = true; NB.state.ui.ipClean = m; NB.toast(no + " marked clean and free · next admission notified"); });
  NB.act("ipdisready", function (no) { var m = Object.assign({}, NB.v("ipDisReady", {})); m[no] = true; NB.state.ui.ipDisReady = m; NB.state.overlay = null; NB.toast(no + " flagged discharge-ready · billing, pharmacy and family notified"); });

  NB.overlay("beddetail", function (no) {
    var x = bedByNo(no); if (!x) return { title: no, body: "" };
    var b = x.b, e = D.bx[no] || {};
    if (b.st !== "occ") return { kind: "modal", title: "Bed " + no, body: ui.note("info", ui.esc(b.note || "Free bed")), foot: ui.btn("Close", { act: "close", cls: "ghost" }) };
    var body = '<div class="row nw">' + ui.av(b.p, "lg") + '<div class="fl"><h2 class="h">' + ui.esc(b.p) + '</h2><div class="small muted">' + b.age + " " + e.sex + " · " + ui.esc(e.mrn) + " · " + ui.esc(x.w) + " · " + L("day ", "اليوم ", "दिन ") + (b.los + 1) + "</div></div>" + ewsBadge(b, e) + "</div>";
    if (e.allergy) body += ui.note("crit", "<b>Allergy:</b> " + ui.esc(e.allergy));
    if (e.v) {
      var n = news2(e.v);
      body += ui.note(n.level === "ok" ? "ok" : n.level === "warn" ? "warn" : "crit", "<b>NEWS2 " + n.total + " · " + LV[n.level][0] + " risk.</b> " + LV[n.level][1]);
      body += ui.card("NEWS2 · " + L("observations 10:05", "ملاحظات 10:05", "अवलोकन 10:05"), ui.table([{ h: "Parameter" }, { h: "Value", cls: "r" }, { h: "Score", cls: "r" }], n.parts.map(function (p) {
        return [p[0], '<span class="num">' + p[1] + "</span>", p[2] ? '<b class="' + (p[2] === 3 ? "critc" : p[2] === 2 ? "warnc" : "") + ' num">' + p[2] + "</b>" : '<span class="faint num">0</span>'];
      }).concat([["<b>Total</b>", "", "<b class=\"num\">" + n.total + "</b>"]]), { min: 320 }) +
        '<div class="row sb" style="margin-top:12px"><span class="small faint">Last 6 observation sets · dashed line = 5</span>' + spark(e.trend, 160, 36) + "</div>", { icon: "activity" });
    } else body += ui.note("info", "Paediatric patient: PEWS is used instead of NEWS2. Current PEWS " + b.news + ". Guardian: " + ui.esc(e.guardian || "—") + ".");
    body += ui.card(L("Stay", "الإقامة", "भर्ती विवरण"), '<div class="kv"><dt>Diagnosis</dt><dd>' + ui.esc(b.dx) + "</dd><dt>Consultant</dt><dd>" + ui.esc(b.doc) + "</dd><dt>Admitted</dt><dd>" + e.adm + "</dd><dt>Payer</dt><dd>" + ui.esc(payer(e.ins)) + "</dd><dt>Cover</dt><dd>" + ui.esc(e.pa || "") + "</dd><dt>Diet</dt><dd>" + ui.esc(e.diet || "") + "</dd><dt>Language</dt><dd>" + ui.esc(e.lang || "") + "</dd></div>", { icon: "bed" });
    var esc = e.v && news2(e.v).total >= 5 ? ui.btn("Call rapid response", { cls: "dng", act: "toast", arg: "Rapid response team paged for " + no + " · ETA 4 min", icon: "bell" }) : "";
    return { kind: "drawer", title: L("Bed ", "سرير ", "बेड ") + no, body: body,
      foot: esc + ui.btn(L("Record vitals", "تسجيل العلامات", "वाइटल्स दर्ज"), { act: "toast", arg: "Observation chart opened for " + no, icon: "heartpulse" }) + ui.btn(L("Transfer", "تحويل", "ट्रांसफ़र"), { ov: "transfer", arg: no, icon: "arrowright" }) +
        ui.btn(L("Medication rounds", "جولة الأدوية", "दवा राउंड"), { go: "ward.mar", icon: "pill" }) + (b.dis ? ui.btn(L("Discharge", "الخروج", "डिस्चार्ज"), { cls: "pri", act: "ipgo", arg: "ward.discharge|x|ipDis|" + no, icon: "logout" }) : ui.btn(L("Mark discharge-ready", "جاهز للخروج", "डिस्चार्ज-तैयार"), { cls: "pri", act: "ipdisready", arg: no, icon: "logout" })) };
  });

  /* ================= ward.admit ================= */
  var PR = { emergency: ["crit", "Emergency", "alertfill"], urgent: ["urg", "Urgent", "bolt"], planned: ["mute", "Planned", "calendar"] };
  NB.screen("ward.admit", function (c) {
    var done = NB.v("ipAdmitted", {});
    var h = ui.header(L("Admissions & transfers", "الدخول والتحويل", "भर्ती और ट्रांसफ़र"), "Requests from OPD, ER and planned surgery · beds suggested by ward, sex, isolation and distance from the nursing station",
      ui.btn(L("Transfer patient", "تحويل مريض", "मरीज़ ट्रांसफ़र"), { ov: "transfer", icon: "arrowright" }) + ui.btn(L("Admit patient", "إدخال مريض", "मरीज़ भर्ती करें"), { cls: "pri", ov: "admitpatient", icon: "userplus" }));
    h += ui.note("warn", "<b>Bed contention · ICU-3.</b> The only free ICU bed is wanted by ER chest pain (ADM-313, emergency) and by the escalation of Lakshmi Devi from A-02 (NEWS2 6). Suggested: ICU-3 to ER; Lakshmi Devi to HDU-level monitoring on Ward A with hourly NEWS2 until ICU-2 steps down.");
    var reqs = D.requests.map(function (r) {
      var p = PR[r.prio], ad = done[r.id];
      return ui.card("", '<div class="ip-req"><div class="hd">' + ui.av(r.name, "lg") + '<div class="fl"><b>' + ui.esc(r.name) + ' <span class="small muted">' + r.age + " " + r.sex + "</span></b>" +
        '<div class="small muted">' + ui.esc(r.dx) + '</div><div class="small faint">' + ui.icon(r.src === "ER" ? "bolt" : r.src === "OPD" ? "steth" : "calendar", "xs") + " " + ui.esc(r.from) + (r.wait ? " · waiting " + r.wait + " min" : "") + "</div></div>" +
        '<div class="chips">' + ui.pill(p[0], p[1], p[2]) + ui.pill("mute", r.src, r.src === "ER" ? "bolt" : "arrivals") + (ad ? ui.pill("ok", "Admitted · " + ad) : "") + "</div></div>" +
        (r.allergy ? ui.note("crit", "<b>Allergy:</b> " + ui.esc(r.allergy) + " · carried to wristband and MAR") : "") +
        '<div class="ip-sug">' + ui.icon("sparkle", "sm acc") + '<div class="fl"><b>Suggested bed ' + r.sug[0][0] + "</b> · " + ui.esc(r.sug[0][1]) + (r.sug[1] ? '<div class="small faint">Alternative ' + r.sug[1][0] + " · " + ui.esc(r.sug[1][1]) + "</div>" : "") + "</div></div>" +
        '<div class="row sb"><span class="small muted">' + ui.icon(r.ins == null ? "wallet" : "shieldcheck", "xs") + " " + ui.esc(payer(r.ins)) + " · " + ui.esc(r.money) + " · estimate " + NB.money(r.est) + "</span>" +
        '<div class="btns">' + ui.btn(r.ins == null ? (r.prio === "emergency" ? "Running bill" : "Estimate & deposit") : "Pre-auth", { cls: "sm", act: "ipgo", arg: r.ins == null ? "billing.invoices" : "billing.preauth", icon: "receipt" }) +
        (ad ? "" : ui.btn("Allocate bed", { cls: "sm pri", ov: "admitpatient", arg: r.id, icon: "bed" })) + "</div></div></div>", { cls: r.prio === "emergency" ? "crit" : "" });
    }).join("");
    var tr = ui.card(L("Transfers", "التحويلات", "ट्रांसफ़र"), '<div class="lst">' + D.transfers.map(function (t) {
      var pl = t.st === "done" ? ui.pill("ok", "Done") : t.st === "contested" ? ui.pill("crit", "Bed contested", "alertfill") : ui.pill("warn", "Criteria 4/6", "alertcircle");
      return ui.li('<span class="av">' + ui.icon("arrowright", "sm flip") + "</span>", ui.esc(t.who) + ' · <span class="ltr">' + t.from + " → " + ui.esc(t.to) + "</span>", ui.esc(t.type + " · " + t.note), pl, t.st === "done" ? {} : { ov: "transfer", arg: t.from });
    }).join("") + "</div>", { icon: "arrowright" });
    var crit = [["ok", "Off vasopressors for 24 h or more", "Noradrenaline stopped 15 Oct 08:00"], ["ok", "SpO₂ 94% or more on 4 L/min or less", "95% on room air"], ["warn", "NEWS2 4 or less for 12 h", "Currently 5 · trending down (9 → 5)"],
      ["ok", "Lactate below 2 mmol/L", "1.4 at 06:00"], ["ok", "Stable mental state", "Alert, oriented"], ["warn", "Ward bed and nurse ratio available", "A-04 after cleaning · 1:6 on Ward A"]];
    var sd = ui.card(L("ICU step-down · Meenakshi P. (ICU-2)", "التحويل من العناية المركزة", "ICU स्टेप-डाउन"), '<div class="ip-ck">' + crit.map(function (x) {
      return '<div class="ci">' + ui.icon(x[0] === "ok" ? "checkcircle" : "alertcircle", "sm " + (x[0] === "ok" ? "okc" : "warnc")) + '<div class="t"><b>' + x[1] + "</b><small>" + x[2] + "</small></div></div>"; }).join("") + "</div>" +
      '<div class="btns" style="margin-top:8px">' + ui.btn("Ask intensivist to sign off", { cls: "sm", act: "toast", arg: "Sign-off request sent to Dr. on ICU duty", icon: "send" }) + "</div>", { icon: "shieldcheck" });
    return h + '<div class="split wide"><div class="stack s16">' + reqs + '</div><div class="stack s16">' + tr + sd + "</div></div>";
  });

  NB.overlay("admitpatient", function (arg) {
    var r = null, bedPre = null;
    if (arg && arg.indexOf("bed:") === 0) bedPre = arg.slice(4); else r = D.requests.find(function (q) { return q.id === arg; });
    var free = allBeds().filter(function (x) { var s = bedState(x.b); return s === "free" || s === "reserved" || s === "clean"; });
    var opts = r ? r.sug.map(function (s) { return s[0]; }) : [];
    free.forEach(function (x) { if (opts.indexOf(x.b.no) < 0) opts.push(x.b.no); });
    var def = bedPre || (r ? r.sug[0][0] : opts[0]);
    var sel = NB.v("ipBedSel_" + (arg || "x"), def);
    var body = r ? '<div class="row nw">' + ui.av(r.name, "lg") + '<div class="fl"><h2 class="h">' + ui.esc(r.name) + '</h2><div class="small muted">' + r.age + " " + r.sex + " · " + ui.esc(r.dx) + "</div></div>" + ui.pill(PR[r.prio][0], PR[r.prio][1], PR[r.prio][2]) + "</div>"
      : '<div class="fgrid">' + ui.field("Patient", "", { ph: "Name, phone, MRN or " + NB.mkt().natId, full: true }) + ui.field("Admitting consultant", "Dr. Vikram Shah", { options: NB.D.doctors.map(function (d) { return d.name; }) }) + ui.field("Source", "OPD", { options: ["OPD", "Emergency", "Elective / planned", "Transfer in"] }) + ui.field("Provisional diagnosis", "", { full: true }) + "</div>";
    if (r && r.allergy) body += ui.note("crit", "<b>Allergy:</b> " + ui.esc(r.allergy));
    body += '<div class="stack s8"><span class="eyebrow">' + L("Choose bed", "اختر السرير", "बेड चुनें") + "</span>" + opts.slice(0, 5).map(function (no) {
      var x = bedByNo(no), s = x ? bedState(x.b) : "", sug = r && r.sug.find(function (q) { return q[0] === no; });
      return ui.radio("ipBedSel_" + (arg || "x"), no, no + " · " + (x ? x.w : "") + (sug && sug === r.sug[0] ? " · suggested" : ""), sug ? sug[1] : s === "clean" ? "Cleaning · " + (x.b.note || "") : s === "reserved" ? (x.b.note || "Reserved") : "Free", def);
    }).join("") + "</div>";
    var est = r ? r.est : 45000;
    var money = r && r.prio === "emergency" ? ui.note("warn", "<b>Emergency: care first.</b> No deposit is asked before stabilisation. Billing opens a running bill and speaks to the family after the patient is stable.")
      : r && r.ins != null ? ui.note("info", "<b>" + ui.esc(payer(r.ins)) + "</b> · " + ui.esc(r.money) + ". Estimate " + NB.money(est).replace(/<[^>]+>/g, "") + ". Anything the insurer does not approve is shown to the family before it is billed.")
      : ui.note("info", "<b>Self-pay.</b> Estimate " + NB.money(est).replace(/<[^>]+>/g, "") + " · deposit " + NB.money(r && r.dep ? r.dep : 15000).replace(/<[^>]+>/g, "") + " requested by " + (NB.gcc() ? "card or payment link" : "UPI link") + " on WhatsApp. Admission is not held for the deposit.");
    body += ui.card(L("Estimate & deposit", "التقدير والدفعة", "अनुमान और जमा"), money + '<div class="btns" style="margin-top:10px">' + ui.btn("Open in billing", { cls: "sm", act: "ipgo", arg: r && r.ins != null ? "billing.preauth" : "billing.invoices", icon: "receipt" }) + "</div>", { icon: "wallet" });
    body += ui.toggle("ipWb", "Print wristband with barcode", "Name, MRN, allergy flag · scanned before every dose", true) + ui.toggle("ipCons", "General consent for admission signed", "Signed on tablet in the patient's language, witness recorded", true) +
      ui.toggle("ipFam", "Update family on WhatsApp", "Ward, bed, visiting hours and the consultant's name", true);
    return { kind: "drawer", size: "lg", title: L("Admit patient", "إدخال مريض", "मरीज़ भर्ती करें") + (r ? " · " + r.id : ""), body: body,
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Admit to ", "إدخال إلى ", "भर्ती करें ") + sel, { cls: "pri", act: "ipadmit", arg: (r ? r.id : "new") + "|" + sel, icon: "check" }) };
  });
  NB.act("ipadmit", function (a) {
    var p = String(a).split("|"), m = Object.assign({}, NB.v("ipAdmitted", {})); m[p[0]] = p[1]; NB.state.ui.ipAdmitted = m; NB.state.overlay = null;
    NB.toast("Admitted to " + p[1] + " · wristband printing · nurse and housekeeping notified");
  });
  NB.overlay("transfer", function (from) {
    var occ = allBeds().filter(function (x) { return x.b.st === "occ"; }).map(function (x) { return x.b.no + " · " + x.b.p; });
    var cur = occ.find(function (o) { return from && o.indexOf(from) === 0; }) || occ[0];
    return { kind: "drawer", title: L("Transfer patient", "تحويل مريض", "मरीज़ ट्रांसफ़र"), body:
      '<div class="fgrid">' + ui.field("Patient", cur, { options: occ, full: true }) + ui.field("To", "ICU · ICU-3", { options: ["ICU · ICU-3", "General Ward A · A-03", "General Ward A · A-04 (after cleaning)", "Paediatric Ward · P-02"] }) +
      ui.field("Type", "Escalation", { options: ["Escalation", "ICU step-down", "Ward change", "Isolation"] }) + "</div>" +
      ui.card("SBAR handover", '<div class="fgrid">' + ui.field("Situation", "NEWS2 6, increasing O₂ need", { area: true, full: true }) + ui.field("Background", "CHF exacerbation, day 6, furosemide IV", { area: true, full: true }) +
        ui.field("Assessment", "Bibasal crackles, SpO₂ 93% on 2 L", { area: true, full: true }) + ui.field("Recommendation", "HDU monitoring, consider NIV", { area: true, full: true }) + "</div>", { icon: "clipboard" }) +
      ui.note("info", "The running bill, pending doses and the MAR move with the patient. The receiving nurse must accept the handover before the bed status changes."),
      foot: ui.btn("Cancel", { act: "close", cls: "ghost" }) + ui.btn("Request transfer", { cls: "pri", act: "toast", arg: "Transfer requested · receiving ward notified", icon: "send" }) };
  });

  /* ================= ward.mar ================= */
  var SLOTS = ["06", "08", "10", "12", "14", "16", "18", "20", "22"];
  function doseState(r, s) {
    var g = NB.v("ipGiven", {})[r.id + "_" + s]; if (g) return g;
    return r.slots ? r.slots[s] || "" : "";
  }
  function doseCell(r, s) {
    var v = doseState(r, s); if (!v) return '<span class="faint">·</span>';
    var k = v.split(":")[0], rest = v.slice(k.length + 1);
    if (k === "given") return '<span class="ip-dose given" title="Given ' + rest + '">' + ui.icon("check") + rest + "</span>";
    if (k === "held") return '<span class="ip-dose held" title="' + ui.esc(rest) + '">' + ui.icon("pause") + "Held</span>";
    if (k === "due") return '<button class="ip-dose due" data-ov="givedose" data-arg="' + r.id + "|" + s + '">' + ui.icon("clockh") + L("Due", "مستحق", "देय") + "</button>";
    if (k === "over") return '<button class="ip-dose over" data-ov="givedose" data-arg="' + r.id + "|" + s + '">' + ui.icon("alertfill") + L("Late", "متأخر", "देर") + "</button>";
    return '<span class="ip-dose" title="Scheduled ' + s + ':00">' + ui.icon("clockh") + "</span>";
  }
  function patOf(bed) { var x = bedByNo(bed); return x ? x.b.p : bed; }
  NB.screen("ward.mar", function (c) {
    var meds = D.mar, rows = [], prev = "";
    var due = [];
    meds.forEach(function (r) { SLOTS.forEach(function (s) { var v = doseState(r, s); if (v === "due" || v === "over") due.push([r, s, v]); }); });
    var h = ui.header(L("Medication rounds", "سجل إعطاء الأدوية", "दवा प्रशासन रिकॉर्ड"), "General Ward A · 10:20 round · scan wristband and drug before every dose · high-alert drugs need a second nurse",
      ui.btn(L("Scan wristband", "مسح السوار", "रिस्टबैंड स्कैन"), { cls: "pri", act: "toast", arg: "Camera ready · scan the patient wristband", icon: "barcode" }));
    h += '<div class="grid g4 kpis">' + ui.kpi('<span class="num">' + due.filter(function (d) { return d[2] === "due"; }).length + "</span>", L("Due now", "مستحق الآن", "अभी देय"), "10:00 round · window ±30 min", "faint") +
      ui.kpi('<span class="num">' + due.filter(function (d) { return d[2] === "over"; }).length + "</span>", L("Overdue", "متأخر", "देरी से"), "Ceftriaxone A-01 · 2 h 20 min late", "critc") +
      ui.kpi('<span class="num">1</span>', L("Held", "موقوف", "रोकी गई"), "With reason, doctor informed", "faint") + ui.kpi('<span class="num">4</span>', L("Given this shift", "أُعطيت في هذه المناوبة", "इस पाली में दी गई"), "100% barcode-scanned", "okc") + "</div>";
    var dueList = ui.card(L("Give now", "أعطِ الآن", "अभी दें"), due.length ? '<div class="lst">' + due.map(function (d) {
      var r = d[0];
      return ui.li('<span class="ip-dose ' + d[2] + '">' + ui.icon(d[2] === "over" ? "alertfill" : "clockh") + d[1] + ":00</span>", ui.esc(r.drug) + " · " + r.route + (r.high ? ' <span class="pill urg" style="vertical-align:middle">' + ui.icon("bolt") + "High-alert</span>" : ""),
        '<span class="ltr">' + r.bed + "</span> · " + ui.esc(patOf(r.bed)) + (r.check ? " · " + ui.esc(r.check) : ""), ui.btn(L("Give", "أعطِ", "दें"), { cls: "sm pri", ov: "givedose", arg: r.id + "|" + d[1], icon: "barcode" }));
    }).join("") + "</div>" : '<div class="empty">' + L("All doses for this round are recorded.", "تم تسجيل جميع الجرعات.", "इस राउंड की सभी खुराकें दर्ज।") + "</div>", { icon: "clockh", cls: "hl" });
    meds.filter(function (r) { return !r.prn; }).forEach(function (r) {
      var first = r.bed !== prev; prev = r.bed;
      var x = bedByNo(r.bed), e = D.bx[r.bed] || {};
      rows.push({ cells: [first ? ui.cellp('<span class="ltr">' + r.bed + "</span> · " + ui.esc(x ? x.b.p : ""), e.allergy ? '<span class="critc">' + ui.icon("alertfill", "xs") + " " + ui.esc(e.allergy) + "</span>" : "NKDA") : "",
        ui.cellp(ui.esc(r.drug) + (r.high ? " " + ui.icon("bolt", "xs warnc") : ""), r.route + " · " + ui.esc(r.freq))].concat(SLOTS.map(function (s) { return doseCell(r, s); })) });
    });
    var cols = [{ h: L("Patient", "المريض", "मरीज़") }, { h: L("Medicine", "الدواء", "दवा") }].concat(SLOTS.map(function (s) { return { h: s + ":00", cls: s === "10" ? "now" : "" }; }));
    var grid = '<div class="ip-mar">' + ui.table(cols, rows, { min: 980 }) + "</div>";
    var prn = ui.card(L("PRN (as needed)", "عند الحاجة", "आवश्यकतानुसार"), '<div class="lst">' + meds.filter(function (r) { return r.prn; }).map(function (r) {
      var g = NB.v("ipGiven", {})[r.id + "_prn"];
      return ui.li('<span class="av">' + ui.icon("pill", "sm") + "</span>", ui.esc(r.drug) + " · " + r.route + ' <span class="faint small">' + ui.esc(r.bed + " · " + patOf(r.bed)) + "</span>", ui.esc(r.freq) + " · last " + (g ? g.split(":").slice(1).join(":") : r.last) + " · today " + ui.esc(r.today),
        ui.btn(L("Give PRN", "إعطاء عند الحاجة", "PRN दें"), { cls: "sm", ov: "givedose", arg: r.id + "|prn", icon: "plus" }));
    }).join("") + "</div>", { icon: "pill" });
    var legend = '<div class="ip-leg"><span class="ip-dose given">' + ui.icon("check") + "Given</span><span class=\"ip-dose due\">" + ui.icon("clockh") + "Due</span><span class=\"ip-dose over\">" + ui.icon("alertfill") + "Late</span><span class=\"ip-dose held\">" + ui.icon("pause") + "Held</span><span class=\"ip-dose\">" + ui.icon("clockh") + "Scheduled</span><span class=\"small faint\">" + ui.icon("bolt", "xs warnc") + " high-alert</span></div>";
    return h + '<div class="grid g2">' + dueList + prn + "</div>" + legend + grid;
  });

  NB.overlay("givedose", function (arg) {
    var p = String(arg).split("|"), r = D.mar.find(function (m) { return m.id === p[0]; }) || D.mar[0], slot = p[1], x = bedByNo(r.bed), e = D.bx[r.bed] || {};
    var k = r.id + "_" + slot, sp = NB.v("ipScanP_" + k), sd = NB.v("ipScanD_" + k), hold = NB.v("ipHold_" + k), wit = NB.v("ipWit_" + k);
    var body = '<div class="row nw">' + ui.av(x ? x.b.p : "", "lg") + '<div class="fl"><h2 class="h">' + ui.esc(x ? x.b.p : "") + '</h2><div class="small muted"><span class="ltr">' + r.bed + "</span> · " + ui.esc(e.mrn || "") + " · " + (x ? x.b.age + " " + (e.sex || "") : "") + "</div></div>" +
      (e.allergy ? ui.pill("crit", e.allergy.split(" ")[0], "alertfill") : ui.pill("ok", "NKDA")) + "</div>";
    body += ui.card("", '<div class="row sb"><div>' + ui.cellp(ui.esc(r.drug) + " · " + r.route, ui.esc(r.freq) + " · ordered by " + ui.esc(r.order)) + "</div>" + (slot === "prn" ? ui.pill("info", "PRN") : ui.pill(doseState(r, slot) === "over" ? "crit" : "acc", (doseState(r, slot) === "over" ? "Late · " : "Due · ") + slot + ":00")) + "</div>" +
      (r.note ? '<p class="small faint" style="margin:8px 0 0">' + ui.esc(r.note) + "</p>" : ""), { cls: r.high ? "warnb" : "" });
    if (doseState(r, slot) === "over") body += ui.note("warn", "This dose is 2 h 20 min late. Give it now unless the doctor says otherwise; the delay and its reason are recorded.");
    body += '<div class="stack s8">' +
      '<div class="ip-scan' + (sp ? " ok" : "") + '">' + ui.icon(sp ? "checkcircle" : "barcode", "lg " + (sp ? "okc" : "")) + '<div class="t"><b>' + (sp ? "Wristband matched" : "1 · Scan patient wristband") + "</b><small>" + (sp ? ui.esc((x ? x.b.p : "") + " · " + (e.mrn || "")) : "Right patient") + "</small></div>" + (sp ? "" : ui.btn("Scan", { cls: "sm", act: "ipscan", arg: "P|" + k, icon: "camera" })) + "</div>" +
      '<div class="ip-scan' + (sd ? " ok" : "") + '">' + ui.icon(sd ? "checkcircle" : "barcode", "lg " + (sd ? "okc" : "")) + '<div class="t"><b>' + (sd ? "Drug matched to the order" : "2 · Scan drug barcode") + "</b><small>" + (sd ? ui.esc(r.drug + " · batch " + r.batch) : "Right drug, dose and route") + "</small></div>" + (sd ? "" : ui.btn("Scan", { cls: "sm", act: "ipscan", arg: "D|" + k, icon: "camera" })) + "</div></div>";
    body += '<div class="chips">' + ["Right patient", "Right drug", "Right dose", "Right route", "Right time"].map(function (t, i) { var ok = i === 0 ? sp : i < 4 ? sd : true; return ui.pill(ok ? "ok" : "mute", t, ok ? "check" : "dot"); }).join("") + "</div>";
    if (r.check) body += ui.note("info", "<b>Before this dose:</b> " + ui.esc(r.check));
    if (r.high) body += '<div class="ip-scan' + (wit ? " ok" : "") + '">' + ui.icon(wit ? "checkcircle" : "shielduser", "lg " + (wit ? "okc" : "")) + '<div class="t"><b>' + (wit ? "Independent double-check · Sr. Anita D'Souza" : "High-alert drug: second nurse check") + "</b><small>" + (wit ? "Badge scanned 10:21" : "A second nurse checks drug, dose and calculation independently") + "</small></div>" + (wit ? "" : ui.btn("Scan badge", { cls: "sm", act: "ipscan", arg: "W|" + k, icon: "key" })) + "</div>";
    if (slot === "prn") body += '<div class="fgrid">' + ui.field("Reason", r.id === "r3" ? "Pain score 6/10" : "Nausea", { options: ["Pain score 6/10", "Fever 38.6 °C", "Nausea", "Other"] }) + ui.field("Reassess effect", "In 60 min (reminder set)", { options: ["In 30 min", "In 60 min (reminder set)"] }) + "</div>";
    if (hold) body += ui.card(L("Hold this dose", "إيقاف الجرعة", "खुराक रोकें"), '<div class="fgrid">' + ui.field("Reason", "Vital signs outside the order's limits", { id: "ip-hr", options: ["Vital signs outside the order's limits", "Patient nil by mouth (procedure)", "Patient refused", "Drug unavailable", "Doctor asked to hold"] }) +
      ui.field("Doctor informed", "Dr. Vikram Shah · 10:22", { id: "ip-hd" }) + ui.field("Note", "", { id: "ip-hn", full: true, ph: "Optional" }) + "</div>", { icon: "pause", cls: "warnb" });
    var can = sp && sd && (!r.high || wit);
    return { kind: "drawer", title: L("Give medication", "إعطاء الدواء", "दवा दें"), body: body,
      foot: (hold ? ui.btn("Cancel hold", { cls: "ghost", act: "iphold", arg: k }) + ui.btn("Record as held", { cls: "pri", act: "ipheld", arg: k, icon: "pause" })
        : ui.btn(L("Hold…", "إيقاف…", "रोकें…"), { act: "iphold", arg: k, icon: "pause" }) + ui.btn(L("Give & record", "أعطِ وسجّل", "दें और दर्ज करें"), { cls: "pri", act: "ipgive", arg: k, icon: "check", disabled: !can, title: can ? "" : "Scan wristband and drug first" })) };
  });
  NB.act("ipscan", function (a) { var p = String(a).split("|"); NB.setUI((function () { var o = {}; o["ipScan" + p[0] + "_" + p[1]] = true; if (p[0] === "W") { o = {}; o["ipWit_" + p[1]] = true; } return o; })()); });
  NB.act("iphold", function (k) { var o = {}; o["ipHold_" + k] = !NB.v("ipHold_" + k); NB.setUI(o); });
  function setDose(k, v) { var m = Object.assign({}, NB.v("ipGiven", {})); m[k] = v; NB.state.ui.ipGiven = m; NB.state.overlay = null; }
  NB.act("ipgive", function (k) { setDose(k, "given:10:21"); NB.toast("Dose recorded 10:21 · scanned, five rights confirmed"); });
  NB.act("ipheld", function (k) { setDose(k, "held:Held with reason · doctor informed"); NB.toast("Dose recorded as held · reason and doctor notified"); });

  /* ================= ward.discharge ================= */
  function disItems(d) {
    return d.items.map(function (it) {
      var st = it[2];
      if (it[0] === "bill" && d.bed === "A-05" && NB.v("blDone_A-05")) st = "done";
      var dn = NB.v("ipDisDone", {})[d.bed + "_" + it[0]]; if (dn) st = "done";
      return [it[0], it[1], st, it[3]];
    });
  }
  NB.screen("ward.discharge", function (c) {
    var sel = NB.v("ipDis", D.dis[0].bed), d = D.dis.find(function (x) { return x.bed === sel; }) || D.dis[0], x = bedByNo(d.bed), e = D.bx[d.bed] || {};
    var items = disItems(d), done = items.filter(function (i) { return i[2] === "done"; }).length, blocks = items.filter(function (i) { return i[2] === "block" || i[2] === "wait"; });
    var h = ui.header(L("Discharge", "الخروج", "डिस्चार्ज"), "Time to discharge is measured from the doctor's decision · targets: 2 h self-pay, 3 h insured (insurer final approval)",
      ui.btn(L("Discharge summary", "ملخص الخروج", "डिस्चार्ज सारांश"), { act: "toast", arg: "Discharge summary opened for " + d.bed, icon: "file" }));
    var allBlocks = 0; D.dis.forEach(function (q) { allBlocks += disItems(q).filter(function (i) { return i[2] === "block"; }).length; });
    h += '<div class="grid g4 kpis">' + ui.kpi('<span class="num">2</span>', L("In progress", "قيد التنفيذ", "प्रगति में"), "1 planned tomorrow", "faint") + ui.kpi('<span class="ltr">2 h 40 m</span>', L("Median time to discharge", "متوسط وقت الخروج", "औसत डिस्चार्ज समय"), "Last 30 days · target < 3 h", "okc") +
      ui.kpi('<span class="num">' + allBlocks + "</span>", L("Blockers now", "عوائق الآن", "अभी रुकावटें"), "Bill, signature", allBlocks ? "critc" : "okc") + ui.kpi('<span class="num">1</span>', L("Discharged today", "خرجوا اليوم", "आज डिस्चार्ज"), "Before noon: 1 of 1", "faint") + "</div>";
    var list = ui.card(L("Patients", "المرضى", "मरीज़"), '<div class="lst">' + D.dis.map(function (q) {
      var qx = bedByNo(q.bed), it = disItems(q), dn = it.filter(function (i) { return i[2] === "done"; }).length, bl = it.filter(function (i) { return i[2] === "block"; }).length;
      return ui.li('<span class="av' + (q.bed === d.bed ? " lg" : "") + '">' + ui.esc(q.bed) + "</span>", ui.esc(qx ? qx.b.p : ""), (q.el ? q.el + " min since decision · " : ui.esc(q.decided) + " · ") + dn + "/" + it.length + " done",
        bl ? ui.pill("crit", bl + " blocker", "alertfill") : ui.pill(dn === it.length ? "ok" : "mute", dn === it.length ? "Ready" : "Open"), { act: "ipdissel", arg: q.bed });
    }).join("") + "</div>", { icon: "logout" });
    var pct = d.target ? Math.min(100, d.el / d.target * 100) : 0;
    var head = '<div class="row nw">' + ui.av(x ? x.b.p : "", "lg") + '<div class="fl"><h2 class="h">' + ui.esc(x ? x.b.p : "") + ' <span class="small muted ltr">' + d.bed + '</span></h2><div class="small muted">' + ui.esc(x ? x.b.dx : "") + " · " + ui.esc(x ? x.b.doc : "") + " · " + ui.esc(d.pay) + "</div></div>" +
      ui.pill(done === items.length ? "ok" : blocks.length ? "warn" : "mute", done + "/" + items.length + " done") + "</div>";
    if (d.target) head += '<div class="ip-tbar" style="margin-top:14px"><div class="row sb small"><span class="muted">Decision ' + d.decided + " · " + d.el + " min elapsed</span><span class=\"" + (pct > 85 ? "critc" : "faint") + "\">Target " + d.target + " min</span></div>" + ui.bar(pct, pct > 85 ? "crit" : pct > 60 ? "warn" : "") + "</div>";
    else head += '<p class="small faint" style="margin:12px 0 0">' + ui.esc(d.decided) + "</p>";
    var ck = '<div class="ip-ck">' + items.map(function (it) {
      var act = "";
      if (it[2] !== "done") {
        if (it[0] === "bill" && d.bed === "A-05") act = ui.btn("Open final bill", { cls: "sm pri", act: "ipgo", arg: "billing.checkout|x|blCo|A-05", icon: "receipt" });
        else if (it[0] === "bill") act = ui.btn("Chase insurer", { cls: "sm", act: "ipgo", arg: "billing.preauth", icon: "send" });
        else if (it[0] === "edu") act = ui.btn("Send in " + d.lang, { cls: "sm", act: "ipdisdone", arg: d.bed + "_edu", icon: "chat" });
        else if (it[0] === "sum") act = ui.btn("Nudge doctor", { cls: "sm", act: "toast", arg: "Reminder sent to the consultant", icon: "bell" });
        else act = ui.btn("Mark done", { cls: "sm ghost", act: "ipdisdone", arg: d.bed + "_" + it[0], icon: "check" });
      }
      return '<div class="ci">' + ckIcon(it[2]) + '<div class="t"><b>' + ui.esc(it[1]) + "</b>" + (it[3] ? "<small>" + ui.esc(it[3]) + "</small>" : "") + "</div>" +
        (it[2] === "block" ? ui.pill("crit", "Blocking", "alertfill") : it[2] === "wait" ? ui.pill("warn", "Waiting", "hourglass") : "") + act + "</div>";
    }).join("") + "</div>";
    var main = ui.card("", head) + ui.card(L("Checklist", "قائمة التحقق", "चेकलिस्ट"), ck, { icon: "clipboard" });
    var edu = ui.card(L("Patient education · " + d.lang, "تثقيف المريض", "मरीज़ शिक्षा · " + d.lang), '<div class="bl-wa" style="background:var(--wa-bg);border-radius:var(--r-md);padding:12px"><div class="bub out" style="max-width:100%">' +
      (d.bed === "A-06" ? "इंसुलिन पेन कैसे लगाएँ (वीडियो, 3 मिनट)\nशुगर कम होने के लक्षण: पसीना, कंपकंपी, चक्कर\nबीमार होने पर क्या करें" : d.bed === "A-05" ? "മുറിവ് പരിചരണം (വീഡിയോ, 2 മിനിറ്റ്)\nകാൽ ഉയർത്തി വയ്ക്കുക\nആന്റിബയോട്ടിക് 5 ദിവസം മുഴുവനായി കഴിക്കുക" : "ಸ್ಪೇಸರ್ ಬಳಕೆ (ವೀಡಿಯೊ)\nಉಸಿರಾಟದ ಅಪಾಯದ ಲಕ್ಷಣಗಳು") +
      '<span class="ts">preview</span></div></div><p class="small faint" style="margin:10px 0 0">Sent as a secure link on WhatsApp in the patient\'s language. The nurse confirms understanding with teach-back before discharge.</p>', { icon: "translate" });
    return h + '<div class="split l"><div class="stack s16">' + list + edu + '</div><div class="stack s16">' + main + "</div></div>";
  });
  NB.act("ipdissel", function (b) { NB.setUI({ ipDis: b }); });
  NB.act("ipdisdone", function (k) { var m = Object.assign({}, NB.v("ipDisDone", {})); m[k] = true; NB.state.ui.ipDisDone = m; NB.toast(k.indexOf("edu") > 0 ? "Education pack sent on WhatsApp · teach-back recorded" : "Marked done"); });

  /* ================= ward.ot ================= */
  var WHO = [
    ["Sign in", "Before induction of anaesthesia", ["Patient confirmed identity, site, procedure and consent", "Site marked (or not applicable)", "Anaesthesia safety check completed", "Pulse oximeter on the patient and working", "Known allergy checked", "Difficult airway or aspiration risk assessed", "Risk of >500 ml blood loss (7 ml/kg in children) planned for"]],
    ["Time out", "Before skin incision", ["All team members introduced by name and role", "Patient name, procedure and incision site confirmed", "Antibiotic prophylaxis given within the last 60 minutes", "Anticipated critical events reviewed (surgeon, anaesthetist, nursing)", "Essential imaging displayed"]],
    ["Sign out", "Before the patient leaves the theatre", ["Name of the procedure recorded", "Instrument, sponge and needle counts correct", "Specimen labelled, including patient name", "Equipment problems addressed", "Key concerns for recovery reviewed"]]
  ];
  var CST = { done: ["ok", "Done", "check"], live: ["acc", "In theatre", "activity"], sched: ["mute", "Scheduled", "clockh"], delayed: ["warn", "Delayed", "alertcircle"], emerg: ["crit", "Emergency add-on", "bolt"] };
  function whoDone(cs, ph, i) {
    var m = NB.v("ipWho", {})[cs.id + "|" + ph + "|" + i]; if (m != null) return m;
    if (cs.stage > ph) return true;
    if (cs.id === "C4" && ph === 0) return i !== 0;   // consent not yet confirmed
    return false;
  }
  NB.screen("ward.ot", function (c) {
    var O = D.ot, sel = NB.v("ipCase", "C4"), cs = O.cases.find(function (k) { return k.id === sel; }) || O.cases[4];
    var h = ui.header(L("OT schedule", "جدول غرف العمليات", "ओटी शेड्यूल"), "Sat, 17 Oct · 3 theatres · WHO surgical safety checklist and consent on every case",
      ui.btn(L("Book case", "حجز عملية", "केस बुक करें"), { cls: "pri", act: "toast", arg: "Theatre booking opens with surgeon calendars and pre-op checklist", icon: "plus" }));
    var cases = O.cases.filter(function (k) { return !k.to; });
    h += '<div class="grid g4 kpis">' + ui.kpi('<span class="num">' + cases.length + "</span>", L("Cases today", "عمليات اليوم", "आज के केस"), "1 emergency add-on", "faint") + ui.kpi("2/3", L("First case on time", "أول عملية في الموعد", "पहला केस समय पर"), "OT-2 started 08:04", "okc") +
      ui.kpi("78%", L("Theatre utilisation", "استغلال الغرف", "थिएटर उपयोग"), "Target 80%", "faint") + ui.kpi('<span class="ltr">24 min</span>', L("Average turnover", "متوسط وقت التجهيز", "औसत टर्नओवर"), "Target 25 min", "okc") + "</div>";
    var hrs = '<div class="ip-otr"><span></span><div class="ip-hrs">' + [7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18].map(function (x) { return '<span class="ltr">' + (x < 10 ? "0" : "") + x + ":00</span>"; }).join("") + "</div></div>";
    var nowPct = ((NOW - 7) / 12 * 100).toFixed(2);
    var lanes = O.rooms.map(function (r, ri) {
      var blocks = O.cases.filter(function (k) { return k.room === ri; }).map(function (k) {
        var col = "grid-column:" + (Math.round((k.s - 7) * 2) + 1) + " / span " + Math.max(1, Math.round((k.e - k.s) * 2));
        if (k.to) return '<div class="ip-case to" style="' + col + '"><small>' + ui.icon("refresh", "xs") + "</small></div>";
        var s = CST[k.st];
        return '<button class="ip-case ' + k.st + (k.id === cs.id ? " on" : "") + '" style="' + col + '" data-set="ipCase" data-val="' + k.id + '" title="' + ui.esc(k.proc + " · " + k.pt) + '"><b>' + ui.esc(k.proc) + "</b><small>" + ui.icon(s[2], "xs") + " " + ui.esc(k.pt) + "</small><small class=\"ltr\">" + hhmm(k.s) + "–" + hhmm(k.e) + "</small></button>";
      }).join("");
      return '<div class="ip-otr"><div class="ip-otl">' + r[0] + "<small>" + r[1] + '</small></div><div class="ip-lane">' + blocks + '<span class="ip-now" style="inset-inline-start:' + nowPct + '%"></span></div></div>';
    }).join("");
    h += '<div class="ip-ot"><div class="ip-otin">' + hrs + lanes + "</div></div>";
    var s = CST[cs.st];
    var info = ui.card(L("Case", "العملية", "केस"), '<div class="row sb" style="margin-bottom:12px"><div>' + ui.cellp(ui.esc(cs.proc), ui.esc(cs.pt + " · " + cs.age + " " + cs.sex + " · " + O.rooms[cs.room][0] + " · " + hhmm(cs.s) + "–" + hhmm(cs.e))) + "</div>" + ui.pill(s[0], s[1], s[2]) + "</div>" +
      (cs.delay ? ui.note("warn", ui.esc(cs.delay)) : "") +
      '<div class="kv" style="margin-top:10px"><dt>Surgeon</dt><dd>' + ui.esc(cs.surg) + "</dd><dt>Anaesthetist</dt><dd>" + ui.esc(cs.an) + "</dd><dt>Anaesthesia</dt><dd>" + ui.esc(cs.anType) + "</dd><dt>Site / side</dt><dd>" + ui.esc(cs.side) + "</dd><dt>Blood</dt><dd>" + ui.esc(cs.blood) + "</dd><dt>Fasting</dt><dd>Nil by mouth since 22:00</dd><dt>Pre-anaesthesia check</dt><dd>Done · ASA II</dd></div>", { icon: "activity" });
    var CF = [["surg", "Surgical consent"], ["an", "Anaesthesia consent"], ["blood", "Blood transfusion consent"], ["hr", "High-risk consent"]];
    var signed = NB.v("ipConsent", {});
    var cons = ui.card(L("Consent forms", "نماذج الموافقة", "सहमति फ़ॉर्म"), '<div class="ip-ck">' + CF.map(function (f) {
      var v = signed[cs.id + "_" + f[0]] ? "signed" : cs.cons[f[0]];
      if (v === "na") return '<div class="ci">' + ui.icon("circle", "sm faint") + '<div class="t"><b class="faint">' + f[1] + "</b><small>Not required for this case</small></div></div>";
      return '<div class="ci">' + (v === "signed" ? ui.icon("checkcircle", "sm okc") : ui.icon("alertcircle", "sm warnc")) + '<div class="t"><b>' + f[1] + "</b><small>" + (v === "signed" ? "Signed in " + cs.lang + " · witness recorded · PDF in record" : "Explained in " + cs.lang + " · waiting for signature") + "</small>" +
        (v === "signed" ? "" : '<div class="btns" style="margin-top:8px">' + ui.btn("Sign on tablet", { cls: "sm pri", act: "ipconsent", arg: cs.id + "_" + f[0], icon: "pen" }) + ui.btn("e-sign link", { cls: "sm", act: "toast", arg: "Consent link sent on WhatsApp in " + cs.lang, icon: "chat" }) + "</div>") + "</div>" +
        (v === "signed" ? ui.pill("ok", "Signed") : ui.pill("warn", "Pending", "clockh")) + "</div>";
    }).join("") + "</div>", { icon: "pen" });
    var who = ui.card(L("WHO surgical safety checklist", "قائمة منظمة الصحة العالمية للسلامة الجراحية", "WHO सर्जिकल सुरक्षा चेकलिस्ट"), '<div class="grid g3">' + WHO.map(function (ph, pi) {
      var n = ph[2].filter(function (_, i) { return whoDone(cs, pi, i); }).length;
      return '<div class="stack s8"><div class="row sb"><b>' + (pi + 1) + " · " + ph[0] + '</b><span class="pill ' + (n === ph[2].length ? "ok" : n ? "warn" : "mute") + '">' + ui.icon(n === ph[2].length ? "check" : "dot") + n + "/" + ph[2].length + '</span></div><span class="small faint">' + ph[1] + '</span><div class="ip-ck">' +
        ph[2].map(function (it, i) { var d = whoDone(cs, pi, i); return '<div class="ci click" data-act="ipwho" data-arg="' + cs.id + "|" + pi + "|" + i + '" role="checkbox" aria-checked="' + d + '" tabindex="0">' + ui.icon(d ? "checkcircle" : "circle", "sm " + (d ? "okc" : "faint")) + '<div class="t"><b>' + it + "</b></div></div>"; }).join("") + "</div></div>";
    }).join("") + "</div>" + (cs.id === "C4" ? '<div style="margin-top:12px">' + ui.note("crit", "<b>Sign in cannot finish:</b> the anaesthesia consent is not signed, so “consent confirmed” stays open and the case cannot be called to theatre.") + "</div>" : ""), { icon: "shieldcheck", cls: "hl" });
    return h + '<div class="split wide"><div class="stack s16">' + who + '</div><div class="stack s16">' + info + cons + "</div></div>";
  });
  NB.act("ipwho", function (a) { var p = String(a).split("|"), cs = D.ot.cases.find(function (k) { return k.id === p[0]; }), m = Object.assign({}, NB.v("ipWho", {})); m[a] = !whoDone(cs, +p[1], +p[2]); NB.setUI({ ipWho: m }); });
  NB.act("ipconsent", function (k) {
    var m = Object.assign({}, NB.v("ipConsent", {})); m[k] = true; NB.state.ui.ipConsent = m;
    if (k === "C4_an") { var w = Object.assign({}, NB.v("ipWho", {})); w["C4|0|0"] = true; NB.state.ui.ipWho = w; }
    NB.toast("Consent signed on tablet · witness recorded · PDF saved to the record");
  });

  /* ---------------- Top-bar primary button ("Admit patient") for this role ---------------- */
  document.addEventListener("click", function (ev) {
    var el = ev.target.closest && ev.target.closest('.top [data-act="primary"]');
    if (el && NB.state.role === "ward") { ev.stopPropagation(); NB.open("admitpatient"); }
  }, true);
})();
