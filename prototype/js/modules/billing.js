/* Billing & insurance module (role "billing"): checkout with market-correct invoices (India GST, UAE VAT + TRN,
   KSA ZATCA simplified tax invoice with QR, Qatar no VAT), invoices & refunds, insurance pre-authorisation, claims
   pipeline, packages & memberships, day close.
   The prototype deliberately shows the CORRECT behaviour for known backend defects:
   tax is computed after discount (QA-13), an interim payment re-opens when later charges raise the total (QA-10),
   payments are idempotent (QA-11), and there is only ever one refund per invoice or package (QA-15). */
(function () {
  "use strict";
  var ui = NB.ui, L = NB.L;

  /* ---------------- Styles (bl- prefix, nb.css tokens only) ---------------- */
  var CSS = [
    ".bl-cos{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:10px}",
    ".bl-co{display:flex;gap:12px;align-items:center;padding:12px 14px;border:1px solid var(--b-def);border-radius:var(--r-md);background:var(--s1);cursor:pointer;min-width:0;text-align:start;color:inherit;font:inherit;width:100%}",
    ".bl-co:hover{border-color:var(--b-str)}",
    ".bl-co.on{border-color:var(--acc-line);background:var(--acc-tint)}",
    ".bl-co .t{flex:1;min-width:0}.bl-co .t b{display:block;font-size:13.5px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
    ".bl-co .t small{display:block;color:var(--tx3);font-size:11.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
    ".bl-sum{display:flex;flex-direction:column;gap:8px;font-size:13px}",
    ".bl-sum .r{display:flex;justify-content:space-between;gap:12px;align-items:baseline}",
    ".bl-sum .r>span:first-child{color:var(--tx2);min-width:0}",
    ".bl-sum .r.sub{font-size:12px}.bl-sum .r.sub>span:first-child{color:var(--tx3);padding-inline-start:12px}",
    ".bl-sum .r.tot{font-size:18px;font-weight:600;padding-top:10px;border-top:1px solid var(--div)}.bl-sum .r.tot>span:first-child{color:var(--tx)}",
    ".bl-sum .r.ins>span:last-child{color:var(--info)}.bl-sum .r.neg>span:last-child{color:var(--ok)}",
    ".bl-ct .cellp b,.bl-ct .cellp small{white-space:normal}.bl-ct td:first-child{min-width:200px}.bl-ct td:not(:first-child){white-space:nowrap}",
    ".bl-nw td.r{white-space:nowrap}",
    ".bl-strike{text-decoration:line-through;color:var(--tx3)}",
    ".bl-pay{display:grid;grid-template-columns:auto minmax(0,1fr);gap:16px;align-items:center}",
    ".bl-qr{background:var(--tx);color:var(--bg-root);padding:8px;border-radius:var(--r-sm);display:inline-block;line-height:0;justify-self:start;width:max-content}",
    ".bl-qr svg{display:block}",
    ".bl-paper{background:var(--tx);color:var(--bg-root);border-radius:var(--r-md);padding:22px;font-size:12px;line-height:17px;min-width:0}",
    ".bl-paper h2{margin:0;font:700 17px/22px var(--font);color:var(--bg-root)}",
    "[dir=rtl] .bl-paper h2{font-family:var(--font-ar)}",
    ".bl-paper .mut{color:var(--tx4)}.bl-paper b{font-weight:600}",
    ".bl-paper .hd{display:flex;flex-wrap:wrap;justify-content:space-between;gap:12px 20px;padding-bottom:14px;border-bottom:2px solid var(--bg-root)}",
    ".bl-paper .hd>div{min-width:0}",
    ".bl-paper .two{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px 20px;padding:12px 0}",
    ".bl-paper .scr{overflow-x:auto;margin:4px 0 10px}",
    ".bl-paper table{width:100%;border-collapse:collapse;min-width:600px}",
    ".bl-paper th{font:600 9.5px/13px var(--font);text-transform:uppercase;letter-spacing:.3px;color:var(--tx4);text-align:start;padding:7px 6px;border-bottom:1.5px solid var(--bg-root);vertical-align:bottom}",
    "[dir=rtl] .bl-paper th{letter-spacing:0;font-family:var(--font-ar)}",
    ".bl-paper td{padding:7px 6px;border-bottom:1px solid var(--tx2);vertical-align:top}",
    ".bl-paper .r{text-align:end;white-space:nowrap}",
    ".bl-paper small{display:block;color:var(--tx4);font-size:10.5px;line-height:14px}",
    ".bl-paper .tots{margin-inline-start:auto;max-width:360px;display:flex;flex-direction:column;gap:4px}",
    ".bl-paper .tots .r2{display:flex;justify-content:space-between;gap:16px}",
    ".bl-paper .tots .r2.big{font-size:14px;font-weight:700;border-top:1.5px solid var(--bg-root);padding-top:6px;margin-top:2px}",
    ".bl-paper .ft{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;justify-content:space-between;border-top:1px solid var(--tx2);margin-top:14px;padding-top:12px}",
    ".bl-paper .ft .fx{flex:1;min-width:200px}",
    ".bl-paper .stamp{display:inline-flex;align-items:center;gap:6px;border:1.5px solid var(--acc-600);color:var(--on-acc);background:var(--acc-300);border-radius:999px;padding:2px 10px;font-weight:600;font-size:11px}",
    ".bl-paper .stamp.red{border-color:var(--danger);background:var(--tx);color:var(--danger)}",
    ".bl-pipe{display:grid;grid-template-columns:repeat(auto-fill,minmax(104px,1fr));gap:8px}",
    ".bl-st{border:1px solid var(--b-def);background:var(--s1);border-radius:var(--r-md);padding:10px 12px;cursor:pointer;text-align:start;color:inherit;font:inherit;display:flex;flex-direction:column;gap:4px;min-width:0}",
    ".bl-st:hover{border-color:var(--b-str)}.bl-st.on{border-color:var(--acc-line);background:var(--acc-tint)}",
    ".bl-st b{font:600 20px/24px var(--font);font-variant-numeric:tabular-nums}.bl-st small{color:var(--tx3);font-size:11.5px}",
    ".bl-age{display:flex;flex-direction:column;gap:10px}",
    ".bl-age .ar{display:grid;grid-template-columns:72px minmax(0,1fr) auto;gap:10px;align-items:center;font-size:12.5px}",
    ".bl-ck{display:flex;flex-direction:column}",
    ".bl-ck .ci{display:flex;gap:10px;align-items:flex-start;padding:10px 0;border-bottom:1px solid var(--div)}.bl-ck .ci:last-child{border-bottom:0}",
    ".bl-ck .ci .ico{margin-top:1px}.bl-ck .ci .t{flex:1;min-width:0}.bl-ck .ci b{display:block;font-size:13px;font-weight:500}.bl-ck .ci small{display:block;color:var(--tx3);font-size:12px;line-height:16px}",
    ".bl-den{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));column-gap:28px;row-gap:2px}",
    ".bl-den .dr{display:grid;grid-template-columns:minmax(64px,1fr) auto minmax(80px,auto);gap:10px;align-items:center;font-size:13px;padding:4px 0;border-bottom:1px solid var(--div)}",
    ".bl-step{display:inline-flex;align-items:center;gap:4px}",
    ".bl-step button{width:28px;height:28px;border-radius:8px;border:1px solid var(--b-def);background:var(--s2);color:var(--tx);cursor:pointer;font:600 15px/1 var(--font)}",
    ".bl-step span{min-width:30px;text-align:center;font-variant-numeric:tabular-nums;font-weight:600}",
    ".bl-wa{background:var(--wa-bg);border-radius:var(--r-md);padding:12px;display:flex;flex-direction:column;gap:6px}",
    ".bl-wa .bub{max-width:100%}",
    ".bl-doc{display:flex;gap:8px;align-items:center;padding:8px 0;border-bottom:1px solid var(--div);font-size:13px}.bl-doc:last-child{border-bottom:0}",
    ".bl-doc span.fl{min-width:0}",
    "@media (max-width:760px){.bl-paper{padding:14px}.bl-paper .two{grid-template-columns:minmax(0,1fr)}.bl-pay{grid-template-columns:minmax(0,1fr)}}",
    ".frame.mobile .bl-pay{grid-template-columns:minmax(0,1fr)}",
    ".bl-plk{background:none;border:0;padding:4px 6px;margin:-4px -6px;border-radius:8px;color:inherit;font:inherit;text-align:start;cursor:pointer}.bl-plk:hover{background:var(--hover2)}.bl-plk .cellp b{color:var(--acc)}",
    ".bl-pkt td:first-child{min-width:180px;max-width:240px}.bl-pkt td:first-child .cellp small,.bl-pkt td:first-child .cellp b{white-space:normal}"
  ].join("\n");
  if (!document.getElementById("bl-css")) { var st = document.createElement("style"); st.id = "bl-css"; st.textContent = CSS; document.head.appendChild(st); }

  /* ---------------- Money helpers ---------------- */
  function r2(v) { return Math.round((v + (v >= 0 ? 1 : -1) * 1e-9) * 100) / 100; }
  function cv(inr) { return r2(inr * NB.mkt().fx); }           // sample data is INR-scale: convert to market currency
  // Format an amount already in market currency. dec=2 forces two decimals (documents); otherwise India drops .00.
  function fm(v, dec) {
    var m = NB.mkt(), neg = v < -0.004, a = Math.abs(v);
    var d = dec === 2 || m.id !== "india" || Math.abs(a - Math.round(a)) > 0.004 ? 2 : 0;
    var s = a.toLocaleString(m.id === "india" ? "en-IN" : "en-US", { minimumFractionDigits: d, maximumFractionDigits: d });
    return '<span class="num ltr">' + (neg ? "−" : "") + (m.id === "india" ? "₹" + s : s + " " + m.cur) + "</span>";
  }
  function mi() { return NB.state.market; }
  function hash(s) { var h = 2166136261; s = String(s); for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return h >>> 0; }

  // QR-like SVG placeholder (deterministic per seed): finder patterns, timing lines and pseudo-random modules.
  function qrSvg(seed, px) {
    var n = 29, r = hash(seed) || 7, out = "";
    function rnd() { r ^= r << 13; r >>>= 0; r ^= r >>> 17; r ^= r << 5; r >>>= 0; return r / 4294967296; }
    function inF(x, y) { return (x < 8 && y < 8) || (x > n - 9 && y < 8) || (x < 8 && y > n - 9); }
    for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) {
      if (inF(x, y)) continue;
      var on = (y === 6 || x === 6) ? (x + y) % 2 === 0 : rnd() > 0.52;
      if (on) out += '<rect x="' + x + '" y="' + y + '" width="1" height="1"/>';
    }
    [[0, 0], [n - 7, 0], [0, n - 7]].forEach(function (f) {
      out += '<rect x="' + f[0] + '" y="' + f[1] + '" width="7" height="7"/><rect x="' + (f[0] + 1) + '" y="' + (f[1] + 1) + '" width="5" height="5" style="fill:var(--tx)"/><rect x="' + (f[0] + 2) + '" y="' + (f[1] + 2) + '" width="3" height="3"/>';
    });
    return '<svg viewBox="-1 -1 ' + (n + 2) + " " + (n + 2) + '" width="' + px + '" height="' + px + '" fill="currentColor" shape-rendering="crispEdges" role="img" aria-label="QR code">' + out + "</svg>";
  }

  // Indian amount in words (for GST invoices).
  function words(num) {
    var a = ["", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"];
    var b = ["", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"];
    function two(n) { return n < 20 ? a[n] : b[Math.floor(n / 10)] + (n % 10 ? " " + a[n % 10] : ""); }
    function three(n) { return (n >= 100 ? a[Math.floor(n / 100)] + " Hundred" + (n % 100 ? " " : "") : "") + (n % 100 ? two(n % 100) : ""); }
    var n = Math.round(num), s = "";
    if (n === 0) return "Zero";
    var cr = Math.floor(n / 1e7); n %= 1e7; var lk = Math.floor(n / 1e5); n %= 1e5; var th = Math.floor(n / 1e3); n %= 1e3;
    if (cr) s += three(cr) + " Crore "; if (lk) s += two(lk) + " Lakh "; if (th) s += two(th) + " Thousand "; if (n) s += three(n);
    return s.trim();
  }

  /* ---------------- Market facts used by billing ---------------- */
  var SUP = {
    india: { n: "Suvidha Healthcare Pvt. Ltd.", br: "Suvidha Multi-Speciality Clinic", addr: "80 Feet Rd, Koramangala 4th Block, Bengaluru, Karnataka 560034", tax: "GSTIN 29AAKCS4821M1Z7", extra: "State: Karnataka · Code 29 · CIN U85110KA2019PTC124518", ser: "SUV/KA/26-27/" },
    uae: { n: "Al Noor Polyclinic L.L.C.", ar: "عيادة النور التخصصية ذ.م.م", addr: "Al Barsha 1, Dubai, United Arab Emirates", tax: "TRN 100384729100003", extra: "DHA facility licence 4471-0921", ser: "ANP-DXB-2026-" },
    ksa: { n: "Al Noor Medical Company", ar: "شركة النور الطبية", addr: "Olaya St, Al Olaya, Riyadh 12213, Saudi Arabia", tax: "VAT 310482736500003", extra: "CR 1010987654 · MOH licence 1402-77", ser: "ANR-RUH-2026-" },
    qatar: { n: "Al Noor Polyclinic W.L.L.", ar: "عيادة النور ذ.م.م", addr: "Al Sadd St, Doha, Qatar", tax: "CR 148820", extra: "MOPH licence PHC-3391", ser: "ANP-DOH-2026-" }
  };
  // Payers per market; records point at a payer by index so the same patient maps to a local insurer in each market.
  var PAYERS = {
    india: [["Star Health", "MediAssist (TPA)"], ["HDFC Ergo", "FHPL (TPA)"], ["PM-JAY", "NHA · TMS portal"], ["Niva Bupa", "In-house claims"], ["ICICI Lombard", "In-house · NHCX"]],
    uae: [["Daman", "Direct"], ["AXA Gulf (GIG)", "NAS"], ["Thiqa", "Daman"], ["Bupa Global", "Direct"], ["Sukoon", "Nextcare"]],
    ksa: [["Tawuniya", "NPHIES"], ["MedGulf", "NPHIES"], ["Malath", "NPHIES"], ["Bupa Arabia", "NPHIES"], ["Al Rajhi Takaful", "NPHIES"]],
    qatar: [["QLM", "Insurer portal"], ["Qatar Insurance (QIC)", "Insurer portal"], ["Seib", "Insurer portal"], ["Bupa Global", "Insurer portal"], ["AXA", "Insurer portal"]]
  };
  var COPAY = { india: [20, null], uae: [20, 50], ksa: [20, 75], qatar: [10, null] }; // OPD co-pay %, cap (market currency)
  var CH = { india: "TPA portal / NHCX", uae: "DHA eClaimLink (DHPO)", ksa: "NPHIES", qatar: "Insurer portal" };
  var CODES = { india: "ICD-10 · package / procedure codes", uae: "ICD-10-CM · CPT · DDC drug codes", ksa: "ICD-10-AM · SBS (ACHI) · SFDA drug codes", qatar: "ICD-10-CM · CPT" };
  function payerOf(x) {
    if (x.ins == null) return null;
    var P = PAYERS[mi()] || PAYERS.india, b = P[x.ins % P.length], cp = COPAY[mi()] || COPAY.india;
    return { n: b[0], via: b[1], copay: x.copay != null ? x.copay : cp[0], cap: cp[1], ch: CH[mi()] };
  }
  function payerName(i) { var P = PAYERS[mi()] || PAYERS.india, b = P[i % P.length]; return b[0] + (b[1] && b[1] !== "Direct" && b[1] !== "NPHIES" && b[1] !== "Insurer portal" ? " via " + b[1] : ""); }

  // Line tax by market and charge category. cat: svc (clinical service) · lab · room · ipdrx (medicines inside IPD treatment)
  // · rx (OPD pharmacy medicine) · otc (OTC medicine / supplement) · cos (cosmetic service or product).
  function taxOf(l, x) {
    var m = mi();
    if (m === "qatar") return { rate: 0, lbl: L("No VAT", "بدون ضريبة", "VAT नहीं") };
    if (m === "ksa") return x.saudi && l.cat !== "cos" && l.cat !== "otc" ? { rate: 0, lbl: L("0% · Saudi national", "0% · مواطن سعودي", "0% · सऊदी नागरिक") } : { rate: 15, lbl: "" };
    if (m === "uae") return l.cat === "cos" || l.cat === "otc" ? { rate: 5, lbl: "" } : { rate: 0, lbl: L("Zero-rated", "خاضع لنسبة الصفر", "शून्य-दर") };
    if (l.cat === "room") return l.r > 5000 ? { rate: 5, lbl: "" } : { rate: 0, lbl: L("Exempt", "معفى", "छूट") };
    if (l.cat === "rx" || l.cat === "otc") return { rate: 5, lbl: "" };
    if (l.cat === "cos") return { rate: 18, lbl: "" };
    return { rate: 0, lbl: L("Exempt", "معفى", "छूट") };
  }
  function discOf(x) { return x.id ? +NB.v("blDisc_" + x.id, x.disc || 0) : (x.disc || 0); }

  // Totals. Order matters: gross → discount (self-pay lines only) → taxable value → tax → insurer share → advance → round-off.
  function calc(x) {
    var m = NB.mkt(), pay = payerOf(x), pct = discOf(x);
    var T = { rows: [], gross: 0, pkg: 0, disc: 0, taxable: 0, tax: 0, total: 0, ins: 0, cov: 0, naive: 0, byRate: {}, pay: pay, pct: pct };
    x.lines.forEach(function (l) {
      var rate = cv(l.r), gross = r2(rate * l.q), pk = !!l.pkg, cov = !!(pay && l.cov);
      var disc = !pk && !cov && pct ? r2(gross * pct / 100) : 0;
      var taxable = pk ? 0 : r2(gross - disc), tr = taxOf(l, x), tax = r2(taxable * tr.rate / 100);
      var total = r2(taxable + tax), ins = cov ? r2(total * (100 - pay.copay) / 100) : 0;
      T.rows.push({ l: l, rate: rate, gross: gross, disc: disc, taxable: taxable, tr: tr, tax: tax, total: total, cov: cov, ins: ins, pk: pk });
      if (pk) T.pkg += gross; else T.gross += gross;
      T.disc += disc; T.taxable += taxable; T.tax += tax; T.total += total; T.ins += ins; if (cov) T.cov += total;
      if (!pk && disc) T.naive += r2(gross * tr.rate / 100) - tax;
      var k = tr.rate; T.byRate[k] = T.byRate[k] || { taxable: 0, tax: 0 }; T.byRate[k].taxable += taxable; T.byRate[k].tax += tax;
    });
    ["gross", "pkg", "disc", "taxable", "tax", "total", "ins", "cov", "naive"].forEach(function (k) { T[k] = r2(T[k]); });
    T.copay = r2(T.cov - T.ins); T.capped = false;
    if (pay && pay.cap != null && T.copay > pay.cap) { T.ins = r2(T.ins + T.copay - pay.cap); T.copay = pay.cap; T.capped = true; }
    T.pat = r2(T.total - T.ins);
    T.adv = x.adv ? Math.min(cv(x.adv), T.pat) : 0; T.advLeft = x.adv ? r2(cv(x.adv) - T.adv) : 0;
    T.due = r2(T.pat - T.adv);
    T.ro = m.id === "india" ? r2(Math.round(T.due) - T.due) : 0;
    T.payable = r2(T.due + T.ro);
    return T;
  }

  /* ---------------- Sample data (NB.D.billing) ---------------- */
  var D = NB.D.billing = {
    checkouts: [
      { id: "T-18", kind: "opd", p: "p6", d: "d2", no: "004183", visit: "OPD · Thyroid panel review", ready: "Ready since 09:58", ins: 1, wallet: 500, lines: [
        { d: "Follow-up consultation · General Medicine", cat: "svc", q: 1, r: 600, hsn: "999312", code: "99213", cov: true },
        { d: "TSH (thyroid stimulating hormone)", cat: "lab", q: 1, r: 450, hsn: "999316", code: "84443", cov: true },
        { d: "Complete blood count", cat: "lab", q: 1, r: 350, hsn: "999316", code: "85025", cov: true },
        { d: "Levothyroxine 50 mcg tablet · batch LEV2504", cat: "rx", q: 30, r: 1.9, hsn: "3004", code: "Rx", cov: true },
        { d: "Vitamin D3 60,000 IU sachet (OTC)", cat: "otc", q: 4, r: 38, hsn: "3004", code: "OTC" }] },
      { id: "A-05", kind: "ipd", name: "Joseph Mathew", ar: "جوزيف ماثيو", age: 49, sex: "M", mrn: "SUV-IP-2293", phone: "98470 11902", lang: "Malayalam", d: "d2", no: "004184",
        visit: "IPD final bill · Cellulitis, left leg · 3 nights · bed A-05", ready: "Discharge decision 08:30", adv: 20000, lines: [
        { d: "Room rent · General ward, twin sharing (per night)", cat: "room", q: 3, r: 3500, hsn: "999311" },
        { d: "Nursing care & monitoring (per day)", cat: "svc", q: 3, r: 800, hsn: "999311" },
        { d: "Consultant visits · Dr. Vikram Shah", cat: "svc", q: 5, r: 700, hsn: "999312" },
        { d: "Ceftriaxone 1 g injection", cat: "ipdrx", q: 6, r: 95, hsn: "3004" },
        { d: "IV fluids, cannula & consumables", cat: "ipdrx", q: 1, r: 1850, hsn: "3004" },
        { d: "Wound dressing (procedure)", cat: "svc", q: 3, r: 450, hsn: "999312" },
        { d: "Venous Doppler, left leg", cat: "svc", q: 1, r: 2200, hsn: "999316" },
        { d: "Lab: CBC ×2, CRP ×2, blood culture", cat: "lab", q: 1, r: 2900, hsn: "999316" }] },
      { id: "T-17", kind: "pkg", p: "p5", d: "d1", no: "004185", visit: "Laser session 4 of 6 · in treatment room", ready: "Session ends ~10:35", disc: 10, discWhy: "Package member perk (10% on products)", lines: [
        { d: "Laser hair reduction · session 4 of 6", cat: "cos", q: 1, r: 3500, hsn: "999722", pkg: "PKG-0193" },
        { d: "Dermatology review (included in package)", cat: "svc", q: 1, r: 500, hsn: "999312", pkg: "PKG-0193" },
        { d: "Post-laser soothing gel 50 g", cat: "cos", q: 1, r: 420, hsn: "3304" },
        { d: "Sunscreen SPF 50, 60 ml", cat: "cos", q: 1, r: 650, hsn: "3304" }] }
    ],
    invoices: [
      { no: "004182", p: "p9", t: "Today 09:41", d: "d1", st: "paid", mode: "Card", lines: [{ d: "Consultation · Dermatology", cat: "svc", q: 1, r: 700, hsn: "999312" }, { d: "Minoxidil 5% solution 60 ml", cat: "rx", q: 1, r: 640, hsn: "3004" }] },
      { no: "004181", p: "p1", t: "Today 08:58", d: "d1", st: "paid", mode: "UPI", lines: [{ d: "Follow-up consultation · Dermatology", cat: "svc", q: 1, r: 500, hsn: "999312" }, { d: "Isotretinoin 20 mg capsule", cat: "rx", q: 30, r: 38, hsn: "3004" }] },
      { no: "004180", p: "p4", t: "Today 09:46", d: "d3", st: "due", mode: "Payment link sent 09:50", note: "Guardian Imran Khan pays via link", lines: [{ d: "DTaP-IPV booster vaccine", cat: "rx", q: 1, r: 1250, hsn: "3002" }, { d: "Vaccine administration", cat: "svc", q: 1, r: 200, hsn: "999312" }] },
      { no: "IP-000318", name: "Lakshmi Devi", bed: "A-02", t: "Today 07:00", d: "d4", st: "due", kind: "ipd", adv: 25000, mode: "Deposit top-up requested", note: "Interim bill #2 · running IPD bill", lines: [
        { d: "Room rent · General ward (per night)", cat: "room", q: 5, r: 3500, hsn: "999311" }, { d: "Nursing care (per day)", cat: "svc", q: 5, r: 800, hsn: "999311" },
        { d: "Consultant visits · Dr. Khalid Al-Saeed", cat: "svc", q: 6, r: 900, hsn: "999312" }, { d: "Medicines & consumables", cat: "ipdrx", q: 1, r: 6400, hsn: "3004" }, { d: "Labs & 2D echo", cat: "lab", q: 1, r: 5600, hsn: "999316" }] },
      { no: "004176", p: "p8", t: "16 Oct 17:40", d: "d2", st: "partial", interim: 700, mode: "Cash (interim)", note: "Echo added after interim payment", lines: [
        { d: "Consultation · General Medicine", cat: "svc", q: 1, r: 700, hsn: "999312" }, { d: "ECG, 12-lead", cat: "svc", q: 1, r: 400, hsn: "999316" }, { d: "2D echocardiogram (added 18:10)", cat: "svc", q: 1, r: 2200, hsn: "999316" }] },
      { no: "004171", p: "p3", t: "16 Oct 12:15", d: "d2", st: "paid", ins: 0, mode: "Co-pay by card", claim: "CLM-8836", lines: [
        { d: "Consultation · General Medicine", cat: "svc", q: 1, r: 700, hsn: "999312", cov: true }, { d: "HbA1c", cat: "lab", q: 1, r: 550, hsn: "999316", cov: true },
        { d: "Kidney function test", cat: "lab", q: 1, r: 700, hsn: "999316", cov: true }, { d: "Metformin 500 mg tablet", cat: "rx", q: 60, r: 1.8, hsn: "3004", cov: true }] },
      { no: "004166", p: "p9", t: "15 Oct 11:05", d: "d1", st: "refunded", mode: "UPI", cn: "CN-0042", note: "Procedure cancelled · full refund", lines: [
        { d: "Greater occipital nerve block", cat: "svc", q: 1, r: 1800, hsn: "999312" }] },
      { no: "CN-0042", kind: "cn", ref: "004166", p: "p9", t: "15 Oct 16:30", st: "cn", mode: "Refund to UPI", lines: [{ d: "Greater occipital nerve block", cat: "svc", q: 1, r: 1800, hsn: "999312" }] },
      { no: "004163", p: "p7", t: "14 Oct 18:20", d: "d3", st: "paid", mode: "Card", lines: [{ d: "Consultation · Paediatrics", cat: "svc", q: 1, r: 600, hsn: "999312" }, { d: "Nebulisation", cat: "svc", q: 1, r: 250, hsn: "999312" }, { d: "Salbutamol 100 mcg inhaler", cat: "rx", q: 1, r: 145, hsn: "3004" }] },
      { no: "004158", p: "p10", t: "14 Oct 10:10", d: "d4", st: "paid", ins: 3, saudi: true, mode: "Co-pay · card", claim: "CLM-8822", lines: [
        { d: "Consultation · Cardiology", cat: "svc", q: 1, r: 1200, hsn: "999312", cov: true }, { d: "Lipid profile", cat: "lab", q: 1, r: 650, hsn: "999316", cov: true }, { d: "Atorvastatin 10 mg tablet", cat: "rx", q: 30, r: 5.6, hsn: "3004", cov: true }] },
      { no: "004151", p: "p5", t: "02 Oct 11:30", d: "d1", st: "paid", mode: "UPI", note: "Package sale", lines: [{ d: "Laser hair reduction package · 6 sessions", cat: "cos", q: 1, r: 18000, hsn: "999722" }] }
    ],
    preauths: [
      { id: "PA-2219", name: "Rakesh Menon", age: 58, sex: "M", ins: 0, for: "Admission · fever with uncontrolled diabetes, ?pyelonephritis", days: 4, ward: "General Ward A", est: 62000, st: "submitted", sub: "Today 10:02", el: 18, sla: 60, docs: [1, 1, 1, 1, 0], dx: "N10 · E11.65" },
      { id: "PA-2214", name: "Ramesh Gowda", age: 63, sex: "M", ins: 0, for: "Admission · community-acquired pneumonia", days: 5, ward: "A-01", est: 58000, st: "query", sub: "Today 08:40", el: 100, sla: 60, docs: [1, 1, 1, 0, 0], dx: "J18.9", query: "Insurer asks for the chest X-ray report and the SpO₂ chart from admission." },
      { id: "PA-2216", name: "Arjun Nair", age: 12, sex: "M", ins: 4, for: "Admission · suspected acute mastoiditis, IV antibiotics", days: 3, ward: "Paediatric Ward", est: 48000, st: "draft", docs: [1, 0, 1, 0, 0], dx: "H70.0", missing: "Guardian's policy card and ENT consult note" },
      { id: "PA-2211", name: "Lakshmi Devi", age: 71, sex: "F", ins: 2, for: "Enhancement · CHF exacerbation, 3 more days", days: 3, ward: "A-02", est: 45000, appr: 30000, st: "partial", sub: "Today 07:15", resp: "42 min", docs: [1, 1, 1, 1, 1], dx: "I50.9", reason: "Package rate cap for medical management applies" },
      { id: "PA-2207", name: "Venkatesh Rao", age: 68, sex: "M", ins: 0, for: "Planned surgery · CABG ×3", days: 7, ward: "ICU-1", est: 385000, appr: 350000, st: "approved", sub: "14 Oct 11:20", resp: "52 min", docs: [1, 1, 1, 1, 1], dx: "I25.10" },
      { id: "PA-2203", name: "Neha Gupta", age: 29, sex: "F", ins: 1, for: "Elective · laparoscopic cholecystectomy (tomorrow)", days: 2, ward: "General Ward A", est: 95000, appr: 95000, st: "approved", sub: "15 Oct 16:05", resp: "38 min", docs: [1, 1, 1, 1, 1], dx: "K80.20" },
      { id: "PA-2201", name: "Meenakshi P.", age: 59, sex: "F", ins: 4, for: "ICU · sepsis", days: 6, ward: "ICU-2", est: 210000, st: "denied", sub: "11 Oct 22:40", resp: "2 h 10 min", docs: [1, 1, 1, 1, 1], dx: "A41.9", reason: "Pre-existing condition inside the policy's waiting period" }
    ],
    claims: [
      { id: "CLM-8841", name: "Fatima Sheikh", ins: 1, svc: "OPD", dos: "Today", amt: 1243, st: "draft", age: 0, ref: "INV 004183" },
      { id: "CLM-8836", name: "Rakesh Menon", ins: 0, svc: "OPD", dos: "16 Oct", amt: 1563, st: "submitted", age: 1, ref: "INV 004171" },
      { id: "CLM-8829", name: "Venkatesh Rao", ins: 0, svc: "IPD", dos: "14–21 Oct", amt: 352000, st: "review", age: 2, ref: "PA-2207 · interim" },
      { id: "CLM-8822", name: "Abdullah Al-Harbi", ins: 3, svc: "OPD", dos: "14 Oct", amt: 2004, appr: 2004, st: "approved", age: 3, ref: "INV 004158" },
      { id: "CLM-8817", name: "Mohammed Ali", ins: 2, svc: "IPD", dos: "02–04 Oct", amt: 18500, appr: 15200, st: "partial", age: 9, ref: "IP-000291", rej: "price" },
      { id: "CLM-8810", name: "Lakshmi Devi", ins: 2, svc: "IPD", dos: "18–22 Sep", amt: 41000, st: "rejected", age: 12, ref: "IP-000262", rej: "auth" },
      { id: "CLM-8803", name: "Ananya Rao", ins: 3, svc: "OPD", dos: "03 Oct", amt: 1250, st: "rejected", age: 14, ref: "INV 004118", rej: "cover" },
      { id: "CLM-8799", name: "Zoya Khan", ins: 1, svc: "OPD", dos: "02 Oct", amt: 950, st: "rejected", age: 15, ref: "INV 004109", rej: "dx" },
      { id: "CLM-8790", name: "Arjun Nair", ins: 4, svc: "OPD", dos: "28 Sep", amt: 1400, st: "resubmitted", age: 19, ref: "INV 004081", rej: "dx" },
      { id: "CLM-8774", name: "Fatima Sheikh", ins: 1, svc: "OPD", dos: "22 Sep", amt: 1320, appr: 1320, st: "paid", age: 25, ref: "RA-55120" },
      { id: "CLM-8768", name: "Rakesh Menon", ins: 0, svc: "IPD", dos: "18–21 Aug", amt: 64000, appr: 61500, st: "paid", age: 40, ref: "RA-54881" },
      { id: "CLM-8741", name: "Mohammed Ali", ins: 2, svc: "OPD", dos: "24 Aug", amt: 2600, st: "review", age: 54, ref: "INV 003902" },
      { id: "CLM-8720", name: "Lakshmi Devi", ins: 2, svc: "IPD", dos: "08–11 Aug", amt: 38200, st: "submitted", age: 68, ref: "IP-000214" },
      { id: "CLM-8690", name: "Priya Sharma", ins: 3, svc: "OPD", dos: "12 Jul", amt: 1900, st: "rejected", age: 97, ref: "INV 003611", rej: "late" }
    ],
    packages: [
      { id: "PKG-0193", p: "p5", plan: "Laser hair reduction · 6 sessions", cat: "cos", sold: "02 Oct 2026", price: 18000, used: 3, total: 6, single: 3500, exp: "02 Apr 2027", st: "active", note: "Session 4 in progress" },
      { id: "PKG-0187", p: "p2", plan: "Melasma peel series · 4 sessions", cat: "cos", sold: "14 Sep 2026", price: 10000, used: 2, total: 4, single: 3000, exp: "14 Jan 2027", st: "active" },
      { id: "PKG-0211", p: "p3", plan: "Physiotherapy · 10 sessions", cat: "svc", sold: "20 Jul 2026", price: 7500, used: 6, total: 10, single: 900, exp: "14 Nov 2026", st: "active", note: "Session 7 booked" },
      { id: "PKG-0181", p: "p1", plan: "Hair PRP · 4 sessions", cat: "cos", sold: "10 Aug 2026", price: 14000, used: 2, total: 4, single: 4000, exp: "10 Feb 2027", st: "default" },
      { id: "PKG-0162", p: "p6", plan: "Melasma peel series · 4 sessions", cat: "cos", sold: "14 Jul 2026", price: 10000, used: 1, total: 4, single: 3000, exp: "14 Jan 2027", st: "active", note: "2 gifted to sister" },
      { id: "MEM-0041", p: "p3", plan: "Diabetes care plan · 4 reviews + 4 HbA1c", cat: "svc", sold: "01 Apr 2026", price: 7200, used: 4, total: 8, single: 1100, exp: "31 Mar 2027", st: "active" },
      { id: "MEM-0038", p: "p8", plan: "Senior care membership · annual, unlimited GP visits", cat: "svc", sold: "01 Jan 2026", price: 6000, time: true, mUsed: 9.5, used: 7, exp: "31 Dec 2026", st: "active" },
      { id: "PKG-0169", p: "p9", plan: "Physiotherapy · 10 sessions", cat: "svc", sold: "20 Jul 2026", price: 6500, used: 6, total: 10, single: 800, exp: "20 Oct 2026", st: "expiring" },
      { id: "PKG-0176", p: "p1", plan: "Acne clear programme · 5 sessions", cat: "cos", sold: "10 Jun 2026", price: 9000, used: 5, total: 5, single: 2200, exp: "10 Dec 2026", st: "completed" },
      { id: "PKG-0151", p: "p6", plan: "Executive health check (annual)", cat: "svc", sold: "05 Sep 2026", price: 4500, used: 0, total: 1, single: 4500, exp: "05 Sep 2027", st: "refpend", rf: "RF-0012" },
      { id: "PKG-0144", p: "p7", plan: "Asthma care · 6 visits", cat: "svc", sold: "01 Mar 2026", price: 4200, used: 2, total: 6, single: 900, exp: "01 Mar 2027", st: "refunded", cn: "CN-0039" }
    ],
    // Day-close sample (INR-scale): payment-mode totals and drawer facts.
    day: { float: 2000, cashIn: 18450, cashRefund: 0, petty: 250, short: 200,
      modes: [["m1", 38, 42300, 42300], ["card", 11, 21600, 19350], ["cash", 14, 18450, null], ["link", 3, 6200, 6200], ["adv", 2, 20000, 20000]],
      ins: 14600, insN: 5, disc: 1240, discN: 3 }
  };

  /* ---------------- Shared lookups ---------------- */
  function coP(x) { // patient for a checkout/invoice record
    if (x.p) { var p = NB.pat(x.p); return { name: NB.pname(p), en: p.name, age: p.age, sex: p.sex, mrn: p.mrn, phone: p.phone, lang: p.lang }; }
    return { name: NB.rtl() && x.ar ? x.ar : x.name, en: x.name, age: x.age, sex: x.sex, mrn: x.mrn || (x.bed ? "IPD · bed " + x.bed : ""), phone: x.phone || "", lang: x.lang };
  }
  function coById(id) { return D.checkouts.find(function (c) { return c.id === id; }); }
  function invByNo(no) { return D.invoices.find(function (c) { return c.no === no; }); }
  function pmList() {
    return { india: [["upi", "UPI QR", "qr"], ["card", "Card", "card"], ["cash", "Cash", "banknote"], ["split", "Split", "layers"], ["adv", "Advance / wallet", "wallet"]],
      uae: [["card", "Card / Apple Pay", "card"], ["link", "Payment link", "link"], ["cash", "Cash", "banknote"], ["split", "Split", "layers"], ["adv", "Advance", "wallet"]],
      ksa: [["mada", "mada", "card"], ["card", "Card / Apple Pay", "card"], ["cash", "Cash", "banknote"], ["split", "Split", "layers"], ["adv", "Advance", "wallet"]],
      qatar: [["card", "Card (NAPS)", "card"], ["link", "Payment link", "link"], ["cash", "Cash", "banknote"], ["split", "Split", "layers"], ["adv", "Advance", "wallet"]] }[mi()];
  }
  function m1Label() { return { india: "UPI", uae: "Apple Pay / wallets", ksa: "mada", qatar: "Debit (NAPS)" }[mi()]; }
  function terminal() { return { india: "Pine Labs POS #2", uae: "Network International POS #2", ksa: "Geidea POS #2 (mada)", qatar: "QNB POS #2" }[mi()]; }
  function invNo(n) { return n.indexOf("CN") === 0 || n.indexOf("IP-") === 0 ? n : SUP[mi()].ser + n; }
  function statusPill(st) {
    var S = { paid: ["ok", L("Paid", "مدفوعة", "भुगतान हुआ"), "checkcircle"], partial: ["warn", L("Partially paid", "مدفوعة جزئياً", "आंशिक भुगतान"), "alertcircle"], due: ["urg", L("Due", "مستحقة", "बकाया"), "clockh"],
      refunded: ["perm", L("Refunded", "مستردة", "रिफ़ंड"), "refresh"], cn: ["sub", L("Credit note", "إشعار دائن", "क्रेडिट नोट"), "file"] }[st] || ["mute", st];
    return ui.pill(S[0], S[1], S[2]);
  }

  /* ================= billing.checkout ================= */
  NB.screen("billing.checkout", function (c) {
    var m = c.m, sel = NB.v("blCo", D.checkouts[0].id), x = coById(sel) || D.checkouts[0], P = coP(x), T = calc(x), pay = T.pay, done = NB.v("blDone_" + x.id);
    var h = ui.header(L("Checkout", "الدفع", "चेकआउट"),
      L("2 ready · 1 in session · invoices follow the " + m.name + " rules (" + (m.id === "india" ? "GST, HSN/SAC" : m.id === "ksa" ? "VAT 15%, ZATCA Fatoora phase 2" : m.id === "uae" ? "VAT 5%, TRN" : "no VAT") + ")",
        "جاهز ٢ · قواعد " + m.name, "2 तैयार · " + m.name + " के नियम"),
      ui.btn(L("New invoice", "فاتورة جديدة", "नया इनवॉइस"), { ov: "newinv", icon: "plus" }) + ui.btn(L("Invoices", "الفواتير", "इनवॉइस"), { go: "billing.invoices", icon: "file" }));

    h += '<div class="bl-cos">' + D.checkouts.map(function (k) {
      var kp = coP(k), kt = calc(k), dn = NB.v("blDone_" + k.id);
      var tag = dn ? ui.pill("ok", L("Paid", "مدفوعة", "भुगतान")) : k.kind === "ipd" ? ui.pill("info", "IPD", "bed") : k.kind === "pkg" ? ui.pill("sub", L("Package", "باقة", "पैकेज"), "layers") : k.ins != null ? ui.pill("acc", L("Insured", "مؤمَّن", "बीमित"), "shieldcheck") : "";
      return '<button class="bl-co' + (k.id === x.id ? " on" : "") + '" data-set="blCo" data-val="' + k.id + '">' + ui.av(kp.en) + '<span class="t"><b>' + ui.esc(kp.name) + '</b><small><span class="tok">' + k.id + "</span> · " + ui.esc(k.ready) + '</small><span class="row nw sb" style="margin-top:4px"><small>' + L("Pays", "يدفع", "देय") + " " + fm(kt.payable) + "</small>" + tag + "</span></span></button>";
    }).join("") + "</div>";

    // Patient & payer strip
    var dr = NB.doc(x.d);
    var strip = '<div class="row sb"><div class="row nw" style="min-width:0">' + ui.av(P.en, "lg") + '<div class="fl"><h2 class="h">' + ui.esc(P.name) + '</h2><div class="small muted">' + P.age + " " + P.sex + " · " + ui.esc(P.mrn) + " · " + ui.esc(x.visit) + " · " + ui.esc(NB.dname(dr)) + "</div></div></div>" +
      '<div class="chips">' + (pay ? ui.pill("ok", L("Eligibility verified 09:36", "تم التحقق من الأهلية", "पात्रता सत्यापित"), "shieldcheck") + ui.pill("info", pay.n + " · " + pay.copay + "% co-pay", "shielduser") : ui.pill("mute", L("Self-pay", "دفع ذاتي", "स्व-भुगतान"), "wallet")) +
      (x.kind === "pkg" ? ui.pill("sub", "PKG-0193 · 3/6 used", "layers") : "") + (x.adv ? ui.pill("acc", L("Advance ", "دفعة مقدمة ", "अग्रिम ") + "" + NB.money(x.adv).replace(/<[^>]+>/g, ""), "wallet") : "") + "</div></div>";
    if (pay) strip += '<div class="small faint" style="margin-top:10px">' + ui.icon("info", "xs") + " " + ui.esc(pay.n + (pay.via && pay.via !== "NPHIES" ? " via " + pay.via : "") + " · " + pay.ch + " · member active · OPD co-pay " + pay.copay + "%" + (pay.cap != null ? " (max " + pay.cap + " " + m.cur + " per visit)" : "") + ". Pharmacy OTC items are not covered.") + "</div>";

    // Charges table
    var tx = m.id === "india" ? "GST" : m.tax;
    var rows = T.rows.map(function (r) {
      var l = r.l, sub = (m.id === "india" ? "HSN/SAC " + l.hsn : l.code ? "Code " + l.code : "") + (r.pk ? " · " + L("Redeemed from ", "مستردة من ", "से रिडीम ") + l.pkg : "");
      var taxCell = r.pk ? '<span class="faint">' + L("Prepaid", "مدفوعة مسبقاً", "प्रीपेड") + "</span>" : r.tr.rate ? fm(r.tax, 2) + '<div class="tiny faint num">' + r.tr.rate + "%</div>" : '<span class="faint">' + ui.esc(r.tr.lbl || "0%") + "</span>";
      var payer = r.pk ? '<span class="subc">' + ui.icon("layers", "xs") + " " + L("Package", "باقة", "पैकेज") + "</span>" : r.cov ? '<span class="infoc">' + ui.icon("shielduser", "xs") + " " + L("Insurer ", "التأمين ", "बीमा ") + (100 - pay.copay) + "%</span>" : '<span>' + ui.icon("user", "xs") + " " + L("Patient", "المريض", "मरीज़") + "</span>";
      return { cells: [ui.cellp(ui.esc(l.d), ui.esc(sub) + " · " + payer), '<span class="num ltr" style="white-space:nowrap">' + (l.q > 1 ? l.q + " × " : "") + fm(r.rate, 2) + "</span>",
        r.disc ? '<span class="okc">−' + fm(r.disc, 2) + "</span>" : r.cov ? '<span class="faint small">' + L("Tariff", "تعرفة", "टैरिफ़") + "</span>" : '<span class="faint">—</span>',
        r.pk ? '<span class="bl-strike">' + fm(r.gross, 2) + "</span>" : fm(r.taxable, 2), taxCell, fm(r.total, 2)] };
    });
    var table = ui.table([{ h: L("Charge", "البند", "शुल्क") }, { h: L("Qty × rate", "الكمية × السعر", "मात्रा × दर"), cls: "r" }, { h: L("Discount", "الخصم", "छूट"), cls: "r" },
      { h: L("Taxable", "الخاضع", "कर योग्य"), cls: "r" }, { h: tx, cls: "r" }, { h: L("Total", "الإجمالي", "कुल"), cls: "r" }], rows, { min: 660 });

    // Discount
    var pct = discOf(x), lim = 10, needAppr = pct > lim, appr = NB.v("blAppr_" + x.id);
    var discBody = '<div class="row sb"><div class="stack s4"><b class="small">' + L("Discount on self-pay lines", "خصم على البنود ذاتية الدفع", "स्व-भुगतान पंक्तियों पर छूट") + '</b><span class="small faint">' +
      L("Your limit: 10% (billing.discount). Insurer lines follow the payer tariff; package lines are prepaid.", "حدك: 10٪", "आपकी सीमा: 10%") + "</span></div>" +
      ui.seg("blDisc_" + x.id, [["0", "0%"], ["5", "5%"], ["10", "10%"], ["15", "15%"]], String(x.disc || 0)) + "</div>";
    if (pct) discBody += '<div class="fgrid" style="margin-top:12px">' + ui.field(L("Reason (audited)", "السبب (مدقق)", "कारण (ऑडिट)"), NB.v("blDiscWhy_" + x.id, x.discWhy || "Senior citizen"), { id: "bl-dw", options: ["Senior citizen", "Package member perk (10% on products)", "Staff family", "Service delay goodwill", "Financial hardship (owner approval)"] }) +
      ui.field(L("Approved by", "اعتمده", "स्वीकृत"), needAppr ? (appr ? "Dr. Arvind Rao (owner) · OTP 4471" : "Waiting for owner approval") : "Pooja Nair (within limit)", { id: "bl-da" }) + "</div>";
    if (needAppr && !appr) discBody += '<div style="margin-top:12px">' + ui.note("warn", "<b>15% is above your 10% limit.</b> Approval request sent to Dr. Arvind Rao on WhatsApp. Collection is held until the owner approves; nobody can bypass this by editing a line price.") +
      '<div class="btns" style="margin-top:8px">' + ui.btn("Simulate owner approval", { cls: "sm", act: "blapprove", arg: x.id, icon: "check" }) + "</div></div>";
    if (T.disc && T.naive) discBody += '<div style="margin-top:12px">' + ui.note("ok", "<b>" + L("Tax is calculated after the discount.", "تُحسب الضريبة بعد الخصم.", "कर छूट के बाद लगाया जाता है।") + "</b> " + tx + " " + fm(T.tax, 2) + " on a taxable value of " + fm(T.taxable, 2) + ". Charging it on the pre-discount amount would over-collect " + fm(T.naive, 2) + ".") + "</div>";
    else if (T.disc) discBody += '<div style="margin-top:12px">' + ui.note("info", "Discount " + fm(T.disc, 2) + " reduces the taxable value. These lines carry 0% " + tx + " in " + m.name + ", so tax does not change.") + "</div>";

    var left = ui.card("", strip) + '<div class="bl-ct">' + table + "</div>" + ui.card(L("Discount", "الخصم", "छूट"), discBody, { icon: "percent" });

    // Summary
    var sum = '<div class="bl-sum">' + '<div class="r"><span>' + L("Charges", "الرسوم", "शुल्क") + "</span>" + fm(T.gross, 2) + "</div>";
    if (T.pkg) sum += '<div class="r sub"><span>' + L("Package redeemed (not billed)", "مستردة من الباقة", "पैकेज से (बिल नहीं)") + '</span><span class="bl-strike">' + fm(T.pkg, 2) + "</span></div>";
    if (T.disc) sum += '<div class="r neg"><span>' + L("Discount", "الخصم", "छूट") + " " + pct + "%</span><span>−" + fm(T.disc, 2) + "</span></div>";
    sum += '<div class="r"><span>' + L("Taxable value", "القيمة الخاضعة", "कर योग्य मूल्य") + "</span>" + fm(T.taxable, 2) + "</div>";
    Object.keys(T.byRate).sort(function (a, b) { return a - b; }).forEach(function (k) {
      var g = T.byRate[k]; if (!+k) return;
      if (m.id === "india") { var half = r2(g.tax / 2); sum += '<div class="r sub"><span>CGST ' + (k / 2) + "% · SGST " + (k / 2) + "%</span><span>" + fm(half, 2) + " + " + fm(r2(g.tax - half), 2) + "</span></div>"; }
      else sum += '<div class="r sub"><span>VAT ' + k + "% " + L("on", "على", "पर") + " " + fm(r2(g.taxable), 2).replace("num ltr", "num ltr faint") + "</span>" + fm(r2(g.tax), 2) + "</div>";
    });
    if (T.byRate[0] && T.byRate[0].taxable) sum += '<div class="r sub"><span>' + (m.id === "india" ? "Exempt supplies (healthcare)" : m.id === "qatar" ? "No VAT" : m.id === "uae" ? "Zero-rated healthcare" : "0% VAT") + "</span>" + fm(r2(T.byRate[0].taxable), 2) + "</div>";
    sum += '<div class="r"><span>' + L("Total tax", "إجمالي الضريبة", "कुल कर") + "</span>" + fm(T.tax, 2) + "</div>";
    sum += '<div class="r" style="font-weight:600"><span style="color:var(--tx)">' + L("Invoice total", "إجمالي الفاتورة", "इनवॉइस कुल") + "</span>" + fm(T.total, 2) + "</div>";
    if (pay) sum += '<div class="r ins"><span>' + L("Insurer pays", "يدفع التأمين", "बीमा देगा") + " · " + ui.esc(pay.n) + "</span><span>−" + fm(T.ins, 2) + "</span></div>" +
      '<div class="r sub"><span>' + L("Co-pay", "نسبة التحمل", "को-पे") + " " + pay.copay + "%" + (T.capped ? " (capped)" : "") + " + " + L("non-covered items", "غير مغطى", "गैर-कवर") + "</span>" + fm(T.pat, 2) + "</div>";
    if (T.adv) sum += '<div class="r neg"><span>' + L("Advance adjusted", "خصم الدفعة المقدمة", "अग्रिम समायोजित") + "</span><span>−" + fm(T.adv, 2) + "</span></div>";
    if (T.ro) sum += '<div class="r sub"><span>' + L("Round off", "تقريب", "राउंड ऑफ़") + "</span>" + fm(T.ro, 2) + "</div>";
    sum += '<div class="r tot"><span>' + L("Patient pays", "يدفع المريض", "मरीज़ देगा") + "</span>" + fm(T.payable, 2) + "</div></div>";
    if (pay) sum += '<div class="small faint" style="margin-top:10px">' + ui.icon("send", "xs") + " " + L("Insurer share goes on claim ", "حصة التأمين في المطالبة ", "बीमा हिस्सा क्लेम ") + '<a data-go="billing.claims">CLM-8841</a> · ' + ui.esc(pay.ch) + "</div>";

    // Payment
    var pms = pmList(), pm = NB.v("blPm_" + mi(), pms[0][0]); if (!pms.some(function (q) { return q[0] === pm; })) pm = pms[0][0];
    var amt = T.payable, pb = "";
    if (pm === "upi") pb = '<div class="bl-pay"><span class="bl-qr">' + qrSvg("upi" + x.id + amt, 112) + '</span><div class="stack s8"><b>' + L("Scan with any UPI app", "امسح بأي تطبيق UPI", "किसी भी UPI ऐप से स्कैन करें") + "</b>" +
      '<span class="small faint ltr" style="word-break:break-all">upi://pay?pa=suvidha.kmg@icici&amp;am=' + amt.toFixed(2) + "&amp;tr=" + x.no + "</span>" + ui.pill("sync", L("Waiting for payment", "بانتظار الدفع", "भुगतान की प्रतीक्षा")) + '<span class="tiny faint">Confirms automatically via the bank webhook</span>' + "</div></div>";
    else if (pm === "card" || pm === "mada") pb = ui.note("info", "Amount " + fm(amt) + " pushed to <b>" + terminal() + "</b>. No re-typing; the approval code comes back automatically.", "card");
    else if (pm === "link") pb = ui.note("info", "Secure payment link (OTP-protected, no health details in the message) goes to " + m.phone + " " + ui.esc(P.phone) + " on WhatsApp. Invoice stays <b>due</b> until the gateway confirms.", "link");
    else if (pm === "cash") { var ten = Math.ceil(amt / (m.id === "india" ? 500 : 50)) * (m.id === "india" ? 500 : 50); pb = '<div class="fgrid">' + ui.field(L("Cash tendered", "النقد المستلم", "नकद प्राप्त"), String(ten), { id: "bl-ten" }) + ui.field(L("Change to return", "الباقي", "वापसी"), (ten - amt).toFixed(2), { id: "bl-chg" }) + "</div>"; }
    else if (pm === "split") { var a1 = r2(Math.round(amt * 0.6)), a2 = r2(amt - a1); pb = '<div class="fgrid">' + ui.field(pms[0][1], a1.toFixed(2), { id: "bl-s1" }) + ui.field(L("Cash", "نقد", "नकद"), a2.toFixed(2), { id: "bl-s2" }) + "</div>" + '<p class="small faint" style="margin:8px 0 0">Both legs post to one invoice under one payment ID; a retry never doubles a leg.</p>'; }
    else if (pm === "adv") { var wal = cv(x.wallet || 0), use = Math.min(wal, amt); pb = ui.note(wal ? "ok" : "warn", wal ? "Wallet balance " + fm(wal) + " applied. Remaining " + fm(r2(amt - use)) + " by " + pms[0][1] + "." : (x.adv ? "IPD advance already adjusted above (" + fm(T.adv) + ")." + (T.advLeft ? " Unused advance " + fm(T.advLeft) + " will be refunded." : " Remaining " + fm(amt) + " by " + pms[0][1] + ".") : "No advance or wallet balance for this patient."), "wallet"); }
    var payCard = ui.card(L("Payment", "الدفع", "भुगतान"), '<div class="stack">' + '<div class="seg" role="tablist">' + pms.map(function (q) {
        return '<button role="tab" class="' + (q[0] === pm ? "on" : "") + '" data-set="blPm_' + mi() + '" data-val="' + q[0] + '">' + ui.icon(q[2], "xs") + ui.esc(q[1]) + "</button>"; }).join("") + "</div>" + pb +
      ui.toggle("blWa", L("Send receipt on WhatsApp", "إرسال الإيصال عبر واتساب", "व्हाट्सऐप पर रसीद भेजें"), "Secure link to the invoice PDF · " + m.phone + " " + P.phone, true) +
      ui.toggle("blEm", L("Also email the invoice", "إرسال بالبريد أيضاً", "ईमेल भी करें"), "", false) + "</div>", { icon: "wallet" });

    var blocked = needAppr && !appr;
    var actions = done ? ui.note("ok", "<b>" + L("Paid", "مدفوعة", "भुगतान हुआ") + " · " + invNo(x.no) + "</b><br>Payment ID <span class=\"ltr\">pay_" + x.no + "_01</span> · receipt sent on WhatsApp" + (pay ? " · claim CLM-8841 drafted" : "") + ". Tapping collect again is safe: the same payment is returned, nothing is charged twice.") +
      '<div class="btns">' + ui.btn(L("View invoice", "عرض الفاتورة", "इनवॉइस देखें"), { ov: "invoice", arg: x.id, icon: "file" }) + ui.btn(L("Collect again (safe)", "تحصيل مرة أخرى", "फिर से"), { act: "blcollect", arg: x.id, icon: "refresh" }) + ui.btn(L("Next patient", "المريض التالي", "अगला मरीज़"), { act: "blnext", arg: x.id, cls: "pri", icon: "arrowright" }) + "</div>"
      : '<div class="btns">' + ui.btn(L("Preview invoice", "معاينة الفاتورة", "इनवॉइस देखें"), { ov: "invoice", arg: x.id, icon: "file" }) +
        ui.btn(L("Collect", "تحصيل", "प्राप्त करें") + " " + fm(T.payable).replace(/<[^>]+>/g, ""), { cls: "pri lg", act: "blcollect", arg: x.id, icon: "check", disabled: blocked }) + "</div>" +
        (x.kind === "pkg" ? '<p class="small faint" style="margin:0">Session is still running: you can pre-bill now; the package session is only consumed when the doctor closes the visit.</p>' : "");
    var right = ui.card(L("Summary", "الملخص", "सारांश"), sum, { icon: "receipt", cls: "hl" }) + payCard + '<div class="stack s8">' + actions + "</div>";
    return h + '<div class="split wide">' + '<div class="stack s16">' + left + "</div>" + '<div class="stack s16">' + right + "</div></div>";
  });

  NB.act("blapprove", function (id) { NB.state.ui["blAppr_" + id] = true; NB.toast("Owner approved 15% discount · audited with reason"); });
  NB.act("blcollect", function (id) {
    var x = coById(id); if (!x) return;
    if (NB.v("blDone_" + id)) { NB.toast("Already recorded as pay_" + x.no + "_01 · duplicate tap ignored, nothing charged twice"); return; }
    NB.state.ui["blDone_" + id] = true; NB.state.overlay = null;
    NB.toast("Paid " + fm(calc(x).payable).replace(/<[^>]+>/g, "") + " · " + invNo(x.no) + " · receipt sent on WhatsApp");
  });
  NB.act("blnext", function (id) { var i = D.checkouts.findIndex(function (k) { return k.id === id; }); NB.setUI({ blCo: D.checkouts[(i + 1) % D.checkouts.length].id }); });

  /* ---------------- Invoice document (per market) ---------------- */
  function docHtml(x, T, o) {
    var m = NB.mkt(), id = m.id, S = SUP[id], P = coP(x), pay = T.pay, isCn = !!o.cn;
    var allEx = T.rows.every(function (r) { return r.pk || !r.tr.rate; }), anyEx = T.rows.some(function (r) { return !r.pk && !r.tr.rate; });
    var title, sub = "";
    if (isCn) { title = id === "india" ? "Credit Note" : id === "qatar" ? "Credit Note · إشعار دائن" : "Tax Credit Note · إشعار دائن ضريبي"; sub = "Against " + invNo(o.ref); }
    else if (id === "india") title = allEx ? "Bill of Supply" : anyEx ? "Invoice-cum-Bill of Supply" : "Tax Invoice";
    else if (id === "ksa") { title = "Simplified Tax Invoice · فاتورة ضريبية مبسطة"; sub = "B2C · reported to ZATCA (Fatoora) · phase 2"; }
    else if (id === "uae") title = "Tax Invoice · فاتورة ضريبية";
    else title = "Invoice · فاتورة";
    var h = '<div class="bl-paper">';
    h += '<div class="hd"><div><h2>' + ui.esc(S.br || S.n) + "</h2>" + (S.ar ? '<div dir="rtl" style="font-family:var(--font-ar)">' + S.ar + "</div>" : "") + (S.br ? "<div>" + S.n + "</div>" : "") +
      '<div class="mut">' + ui.esc(S.addr) + "</div><div><b>" + S.tax + '</b></div><div class="mut">' + S.extra + "</div></div>" +
      '<div style="text-align:end"><h2>' + title + "</h2>" + (sub ? '<div class="mut">' + sub + "</div>" : "") + "<div>No. <b class=\"ltr\">" + invNo(o.no) + "</b></div><div class=\"ltr\">" + o.when + "</div>" +
      (o.paid ? '<div style="margin-top:6px"><span class="stamp">' + ui.icon("checkcircle", "xs") + "PAID · " + ui.esc(o.paid) + "</span></div>" : isCn ? '<div style="margin-top:6px"><span class="stamp red">' + ui.icon("refresh", "xs") + "REFUND</span></div>" : "") + "</div></div>";
    h += '<div class="two"><div><div class="mut">' + (id === "india" ? "Billed to" : "Customer · العميل") + "</div><b>" + ui.esc(P.en) + "</b> · " + (P.age || "") + " " + (P.sex || "") + "<div>MRN " + ui.esc(P.mrn) + "</div>" +
      (id === "india" ? '<div class="mut">ABHA 91-44**-****-2290 · ' + m.phone + " " + ui.esc(P.phone) + "</div>" : '<div class="mut">' + m.natId + " ••••" + String(hash(P.en)).slice(-4) + (id === "ksa" ? " · " + (x.saudi ? "Saudi national" : "Non-Saudi resident") : "") + "</div>") + "</div>" +
      "<div>" + (id === "india" ? '<div class="mut">Place of supply</div><b>Karnataka (29)</b> · intra-state<div class="mut">Reverse charge: No</div>' : '<div class="mut">Currency · العملة</div><b>' + m.curCode + "</b>" + (id === "ksa" ? '<div class="mut">Supply date · تاريخ التوريد</div><b class="ltr">2026-10-' + ((o.when.match(/^(\d\d) Oct/) || [0, "17"])[1]) + "</b>" : "")) +
      (pay ? '<div class="mut" style="margin-top:4px">Payer</div><b>' + ui.esc(pay.n) + "</b> · co-pay " + pay.copay + "%" : "") + "</div></div>";

    var cols, ar = id !== "india";
    if (id === "india") cols = ["#", "Description", "HSN/SAC", "Qty", "Rate", "Disc.", "Taxable", "CGST", "SGST", "Amount"];
    else if (id === "qatar") cols = ["Description<br>الوصف", "Qty<br>الكمية", "Unit price<br>السعر", "Discount<br>الخصم", "Amount<br>المبلغ"];
    else cols = ["Description<br>الوصف", "Qty<br>الكمية", "Unit price<br>سعر الوحدة", "Discount<br>الخصم", "Taxable<br>الخاضع للضريبة", "VAT %<br>النسبة", "VAT<br>الضريبة", "Total incl. VAT<br>الإجمالي"];
    var sign = isCn ? -1 : 1;
    var body = T.rows.map(function (r, i) {
      var l = r.l, d = ui.esc(l.d) + (r.pk ? "<small>Redeemed from " + l.pkg + " · prepaid, tax charged at sale</small>" : "") + (r.cov ? "<small>Covered · insurer " + (100 - pay.copay) + "%</small>" : "");
      var disc = r.disc ? fm(-sign * r.disc, 2) : "—";
      if (id === "india") {
        var cg = r2(r.tax / 2), sg = r2(r.tax - cg), half = r.tr.rate / 2;
        return "<tr><td>" + (i + 1) + '</td><td style="min-width:150px">' + d + "</td><td>" + l.hsn + '</td><td class="r">' + l.q + '</td><td class="r">' + fm(r.rate, 2) + '</td><td class="r">' + disc + '</td><td class="r">' + fm(sign * r.taxable, 2) + "</td>" +
          '<td class="r">' + (r.pk ? "—" : r.tr.rate ? half + "%<small>" + fm(sign * cg, 2) + "</small>" : r.pk ? "—" : "Exempt") + '</td><td class="r">' + (r.pk ? "—" : r.tr.rate ? half + "%<small>" + fm(sign * sg, 2) + "</small>" : r.pk ? "—" : "Exempt") + '</td><td class="r"><b>' + fm(sign * r.total, 2) + "</b></td></tr>";
      }
      if (id === "qatar") return '<tr><td style="min-width:150px">' + d + '</td><td class="r">' + l.q + '</td><td class="r">' + fm(r.rate, 2) + '</td><td class="r">' + disc + '</td><td class="r"><b>' + fm(sign * r.total, 2) + "</b></td></tr>";
      return '<tr><td style="min-width:150px">' + d + '</td><td class="r">' + l.q + '</td><td class="r">' + fm(r.rate, 2) + '</td><td class="r">' + disc + '</td><td class="r">' + fm(sign * r.taxable, 2) + '</td><td class="r">' + (r.pk ? "—" : r.tr.rate + "%" + (r.tr.lbl ? "<small>" + ui.esc(r.tr.lbl) + "</small>" : "")) + '</td><td class="r">' + fm(sign * r.tax, 2) + '</td><td class="r"><b>' + fm(sign * r.total, 2) + "</b></td></tr>";
    }).join("");
    h += '<div class="scr"><table><thead><tr>' + cols.map(function (c, i) { return "<th" + (i >= (id === "india" ? 3 : 1) && !(id === "india" && i === 2) ? ' class="r"' : "") + ">" + c + "</th>"; }).join("") + "</tr></thead><tbody>" + body + "</tbody></table></div>";

    // Totals
    var t = '<div class="tots">';
    function row(a, b, big) { t += '<div class="r2' + (big ? " big" : "") + '"><span>' + a + '</span><span style="white-space:nowrap">' + b + "</span></div>"; }
    if (id === "india") {
      row("Taxable value", fm(sign * T.taxable, 2));
      if (T.disc) row("Discount (already deducted in lines)", "−" + fm(T.disc, 2));
      var cgT = 0, sgT = 0; T.rows.forEach(function (r) { var cg = r2(r.tax / 2); cgT += cg; sgT += r2(r.tax - cg); });
      row("CGST", fm(sign * r2(cgT), 2)); row("SGST", fm(sign * r2(sgT), 2));
      row("<b>Invoice total</b>", "<b>" + fm(sign * T.total, 2) + "</b>");
      if (!isCn && pay) row("Less: insurer share (" + ui.esc(pay.n) + ")", "−" + fm(T.ins, 2));
      if (!isCn && T.adv) row("Less: advance received", "−" + fm(T.adv, 2));
      if (!isCn && T.ro) row("Round off", fm(T.ro, 2));
      row(isCn ? "Refund amount" : "Amount payable", fm(isCn ? -T.payable : T.payable, 2), true);
    } else if (id === "qatar") {
      row("Subtotal · المجموع", fm(sign * T.gross, 2)); if (T.disc) row("Discount · الخصم", "−" + fm(T.disc, 2));
      row("<b>Total · الإجمالي</b>", "<b>" + fm(sign * T.total, 2) + "</b>");
      if (!isCn && pay) row("Less insurer share · حصة التأمين", "−" + fm(T.ins, 2));
      if (!isCn && T.adv) row("Less advance · دفعة مقدمة", "−" + fm(T.adv, 2));
      row(isCn ? "Refund · المسترد" : "Amount due · المستحق", fm(isCn ? -T.payable : T.payable, 2), true);
    } else {
      row("Total excl. VAT · الإجمالي غير شامل الضريبة", fm(sign * T.taxable, 2));
      if (T.disc) row("Discount, already deducted · الخصم", "−" + fm(T.disc, 2));
      Object.keys(T.byRate).forEach(function (k) { if (T.byRate[k].taxable || +k) row("VAT " + k + "% · ضريبة القيمة المضافة " + k + "٪", fm(sign * r2(T.byRate[k].tax), 2)); });
      row("<b>Total incl. VAT · الإجمالي شامل الضريبة</b>", "<b>" + fm(sign * T.total, 2) + "</b>");
      if (!isCn && pay) row("Less insurer share · حصة شركة التأمين", "−" + fm(T.ins, 2));
      if (!isCn && T.adv) row("Less advance · الدفعة المقدمة", "−" + fm(T.adv, 2));
      row(isCn ? "Refund · المبلغ المسترد" : "Patient pays · المبلغ المستحق على المريض", fm(isCn ? -T.payable : T.payable, 2), true);
    }
    t += "</div>";
    h += t;
    if (id === "india") h += '<div style="margin-top:8px"><span class="mut">Amount in words:</span> <b>Rupees ' + words(isCn ? T.payable : T.payable) + " Only</b></div>";

    // Footer per market
    var ft = '<div class="ft"><div class="fx">';
    if (id === "india") {
      ft += (anyEx ? "<div>Exempt: healthcare services by a clinical establishment — Notification 12/2017-Central Tax (Rate), Sr. No. 74. Medicines supplied as part of in-patient treatment form a composite exempt supply.</div>" : "") +
        "<div class=\"mut\">Tax charged on the value after discount. Invoice series is gapless per GSTIN and financial year (" + S.ser + "). B2C supply: e-invoice IRN not required.</div>" +
        (isCn ? "<div class=\"mut\">Credit note under Sec. 34 CGST Act; output tax reversed in GSTR-1 for October 2026.</div>" : "");
    } else if (id === "ksa") {
      ft += "<div><b>ZATCA phase 2</b> · UUID <span class=\"ltr\">" + ("8e3c" + hash(o.no).toString(16)).slice(0, 12) + "-…</span> · ICV " + parseInt(String(o.no).replace(/\D/g, ""), 10) + " · previous-invoice hash chained · cryptographic stamp applied</div>" +
        '<div class="mut">Reported to Fatoora within 24 h (simplified invoice). The insurer share is billed on a standard tax invoice that is cleared before it is sent.' + (x.saudi ? " Healthcare to Saudi citizens: VAT borne by the State (0%)." : " VAT 15% applies to non-Saudi patients.") + "</div>" +
        '<div class="mut">QR (TLV, base64): 1 seller name · 2 VAT number · 3 timestamp · 4 total incl. VAT · 5 VAT total · 6 invoice hash · 7 ECDSA signature · 8 public key · 9 stamp signature</div>';
    } else if (id === "uae") {
      ft += "<div>Amounts in AED. Zero-rated: qualifying preventive and basic healthcare and listed medicines. Standard-rated 5%: cosmetic and elective services and non-listed products.</div>" +
        '<div class="mut">Supplier TRN shown above. UAE e-invoicing (Peppol, 5-corner model) from 2027: the same data is ready to send through an accredited service provider.</div>';
    } else ft += "<div>No VAT is levied in Qatar. Amounts in QAR.</div><div class=\"mut\">Insurer share is submitted on the insurer portal with the claim form.</div>";
    ft += '</div><div style="text-align:center">' + (id === "ksa" ? '<span class="bl-qr" style="padding:0">' + qrSvg("zatca" + o.no + T.total, 120) + "</span><div class=\"mut\">Scan to verify (ZATCA)</div>" :
      '<span class="bl-qr" style="padding:0">' + qrSvg("verify" + o.no, 84) + '</span><div class="mut">' + (id === "india" ? "Verify invoice" : "Verify · تحقق") + "</div>") + "</div></div>";
    h += ft + "</div>";
    return h;
  }

  NB.overlay("invoice", function (arg) {
    var m = NB.mkt(), x = coById(arg), o;
    if (x) { var done = NB.v("blDone_" + x.id); o = { no: x.no, when: "17 Oct 2026 " + (done ? "10:24:31" : "— draft —"), paid: done ? pmList()[0][1] : "" }; }
    else {
      x = invByNo(arg) || D.invoices[0];
      if (x.kind === "cn") { o = { no: x.no, when: x.t.replace("Today", "17 Oct 2026") , cn: true, ref: x.ref }; }
      else { var s = invRow(x); o = { no: x.no, when: x.t.replace("Today", "17 Oct 2026").replace(/^(\d\d) Oct/, "$1 Oct 2026"), paid: s.st === "paid" ? x.mode : "" }; }
    }
    var T = calc(x);
    var foot = ui.btn(L("Close", "إغلاق", "बंद करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Download PDF", "تنزيل PDF", "PDF डाउनलोड"), { act: "toast", arg: "PDF ready · bilingual layout", icon: "download" }) +
      ui.btn(L("Print", "طباعة", "प्रिंट"), { act: "toast", arg: "Sent to front-desk printer", icon: "printer" }) + ui.btn(L("Send on WhatsApp", "إرسال عبر واتساب", "व्हाट्सऐप पर भेजें"), { cls: "pri", act: "toast", arg: "Secure invoice link sent on WhatsApp", icon: "chat" });
    var hint = ui.note("info", "<b>" + m.name + "</b> invoice rules: " + (m.id === "india" ? "GSTIN, HSN/SAC per line, CGST + SGST for intra-state supply, exempt healthcare shown as bill of supply." : m.id === "ksa" ? "simplified tax invoice in Arabic and English, VAT 15% (0% for Saudi nationals on healthcare), phase 2 QR with cryptographic stamp, reported to ZATCA." : m.id === "uae" ? "tax invoice with TRN, VAT 5% on standard-rated lines, zero-rated healthcare shown at 0%." : "no VAT; invoice in Arabic and English.") + " Switch the market in the top bar to compare.");
    return { kind: "drawer", size: "lg", title: (o.cn ? L("Credit note", "إشعار دائن", "क्रेडिट नोट") : L("Invoice preview", "معاينة الفاتورة", "इनवॉइस पूर्वावलोकन")) + " · " + invNo(o.no), body: hint + docHtml(x, T, o), foot: foot };
  });

  /* ================= billing.invoices ================= */
  function invRow(inv) {
    var T = calc(inv), pays = NB.v("blPay", {}), refs = NB.v("blRef", {}), st = inv.st, paid = 0;
    if (inv.kind === "cn") return { T: T, st: "cn", paid: -T.payable, bal: 0 };
    if (st === "paid" || st === "refunded") paid = T.payable; else if (st === "partial") paid = cv(inv.interim);
    if (pays[inv.no]) { paid = T.payable; st = "paid"; }
    if (refs[inv.no]) st = "refunded";
    return { T: T, st: st, paid: paid, bal: st === "refunded" ? 0 : r2(T.payable - paid) };
  }
  function allInvoices() {
    var refs = NB.v("blRef", {}), extra = [];
    Object.keys(refs).forEach(function (no) { var o = invByNo(no); if (o) extra.push({ no: refs[no], kind: "cn", ref: no, p: o.p, name: o.name, t: "Today 10:30", st: "cn", mode: "Refund to " + o.mode, lines: o.lines, ins: o.ins, saudi: o.saudi }); });
    return extra.concat(D.invoices);
  }
  NB.screen("billing.invoices", function (c) {
    var m = c.m, f = NB.v("blInvF", "all"), pf = NB.v("blInvPayer", "All payers"), per = NB.v("blInvPer", "Last 30 days");
    var list = allInvoices().map(function (i) { return { i: i, s: invRow(i) }; });
    var cnt = function (k) { return list.filter(function (r) { return k === "all" || r.s.st === k; }).length; };
    var rows = list.filter(function (r) { return f === "all" || r.s.st === f; })
      .filter(function (r) { return pf === "All payers" || (pf === "Insurance" ? r.i.ins != null : pf === "IPD" ? r.i.kind === "ipd" : r.i.ins == null && r.i.kind !== "ipd"); })
      .filter(function (r) { return per !== "Today" || /^Today/.test(r.i.t); });
    var today = list.filter(function (r) { return /^Today/.test(r.i.t) && r.i.kind !== "cn"; });
    var billed = today.reduce(function (a, r) { return a + r.s.T.total; }, 0), coll = today.reduce(function (a, r) { return a + Math.max(0, r.s.paid); }, 0);
    var outst = list.reduce(function (a, r) { return a + (r.s.st === "due" || r.s.st === "partial" ? r.s.bal : 0); }, 0);
    var refd = list.filter(function (r) { return r.s.st === "cn"; }).reduce(function (a, r) { return a + r.s.T.payable; }, 0);
    var h = ui.header(L("Invoices & receipts", "الفواتير والإيصالات", "इनवॉइस और रसीदें"), "Gapless series " + SUP[m.id].ser + "… · payments are idempotent · one refund per invoice, issued as a credit note",
      ui.btn(L("Export", "تصدير", "एक्सपोर्ट"), { act: "toast", arg: m.id === "india" ? "GSTR-1 B2C summary exported for Oct 2026" : m.id === "ksa" ? "ZATCA reporting log exported" : "VAT summary exported", icon: "download" }) + ui.btn(L("New invoice", "فاتورة جديدة", "नया इनवॉइस"), { cls: "pri", ov: "newinv", icon: "plus" }));
    h += '<div class="grid g4 kpis">' + ui.kpi(fm(billed), L("Billed today", "فواتير اليوم", "आज बिल किया")) + ui.kpi(fm(coll), L("Collected today", "المحصل اليوم", "आज प्राप्त"), "Patient share; insurer share sits in claims", "faint") +
      ui.kpi(fm(outst), L("Outstanding", "المستحق", "बकाया"), "Due + partially paid", "warnc") + ui.kpi(fm(refd), L("Refunded this week", "المسترد هذا الأسبوع", "इस सप्ताह रिफ़ंड"), "Credit notes only", "faint") + "</div>";
    h += '<div class="row sb">' + ui.seg("blInvF", [["all", L("All", "الكل", "सभी"), cnt("all")], ["paid", L("Paid", "مدفوعة", "भुगतान"), cnt("paid")], ["partial", L("Partially paid", "جزئية", "आंशिक"), cnt("partial")],
      ["due", L("Due", "مستحقة", "बकाया"), cnt("due")], ["refunded", L("Refunded", "مستردة", "रिफ़ंड"), cnt("refunded")], ["cn", L("Credit notes", "إشعارات دائنة", "क्रेडिट नोट"), cnt("cn")]]) +
      '<div class="row">' + '<div class="field" style="min-width:150px;padding:4px 10px"><select data-change="blInvPayer" aria-label="Payer">' + ["All payers", "Self-pay", "Insurance", "IPD"].map(function (o) { return "<option" + (o === pf ? " selected" : "") + ">" + o + "</option>"; }).join("") + "</select></div>" +
      '<div class="field" style="min-width:140px;padding:4px 10px"><select data-change="blInvPer" aria-label="Period">' + ["Today", "Last 30 days"].map(function (o) { return "<option" + (o === per ? " selected" : "") + ">" + o + "</option>"; }).join("") + "</select></div></div></div>";
    var table = ui.table([{ h: L("Invoice", "الفاتورة", "इनवॉइस") }, { h: L("Patient", "المريض", "मरीज़") }, { h: L("Total", "الإجمالي", "कुल"), cls: "r" }, { h: L("Paid", "المدفوع", "भुगतान"), cls: "r" }, { h: L("Balance", "الرصيد", "शेष"), cls: "r" },
      { h: L("Payer / mode", "الدافع", "भुगतानकर्ता") }, { h: L("Status", "الحالة", "स्थिति") }, { h: "" }],
      rows.map(function (r) {
        var i = r.i, s = r.s, P = coP(i), pay = s.T.pay;
        var act = s.st === "due" || s.st === "partial" ? ui.btn(L("Record payment", "تسجيل دفعة", "भुगतान दर्ज"), { cls: "sm pri", ov: "payment", arg: i.no, icon: "wallet" })
          : s.st === "paid" ? ui.btn(L("Refund", "استرداد", "रिफ़ंड"), { cls: "sm", ov: "refund", arg: i.no, icon: "refresh" }) : "";
        act += " " + ui.btn("", { cls: "sm ghost", ov: "invoice", arg: i.no, icon: "eye", title: "View" });
        return { cls: s.st === "partial" ? "urg" : "", cells: [ui.cellp('<span class="ltr">' + invNo(i.no) + "</span>", ui.esc(i.t) + (i.kind === "ipd" ? " · IPD" : "")),
          ui.cellp(ui.esc(P.name), ui.esc(i.note || i.lines.map(function (l) { return l.d; }).slice(0, 2).join(", "))),
          fm(i.kind === "cn" ? -s.T.payable : s.T.total), s.paid ? fm(s.paid) : '<span class="faint">—</span>', s.bal ? '<b class="warnc">' + fm(s.bal) + "</b>" : '<span class="faint">—</span>',
          ui.cellp(pay ? ui.esc(pay.n) : i.kind === "ipd" ? "IPD running bill" : L("Self-pay", "دفع ذاتي", "स्व-भुगतान"), ui.esc(i.mode || "") + (pay ? " · insurer " + fm(s.T.ins).replace(/<[^>]+>/g, "") : "") + (i.cn && s.st === "refunded" ? " · " + i.cn : "")),
          statusPill(s.st), '<div class="row nw">' + act + "</div>"] };
      }), { min: 1000, empty: "No invoices match these filters." });
    var rail = ui.card(L("Why balances stay correct", "لماذا تبقى الأرصدة صحيحة", "बैलेंस सही क्यों रहते हैं"), '<div class="grid g4 bl-ck">' + [
      ["checkcircle", "okc", "Interim payments re-open", "004176: ₹700 paid at consult, echo added later → back to partially paid with the balance collectable."],
      ["checkcircle", "okc", "One tap = one payment", "Every payment carries a payment ID; retries and double taps return the first receipt."],
      ["checkcircle", "okc", "One refund per invoice", "A refund issues one credit note. A second request is blocked, not queued."],
      ["checkcircle", "okc", "Tax after discount", "Discounts reduce the taxable value on the invoice line before " + (m.id === "india" ? "GST" : "VAT") + " is charged."]
    ].map(function (x) { return '<div class="ci">' + ui.icon(x[0], "sm " + x[1]) + '<div class="t"><b>' + x[2] + "</b><small>" + x[3] + "</small></div></div>"; }).join("") + "</div>", { icon: "shieldcheck" });
    return h + '<div class="bl-nw">' + table + "</div>" + rail;
  });

  NB.overlay("payment", function (no) {
    var i = invByNo(no) || D.invoices[0], s = invRow(i), P = coP(i), pms = pmList(), rec = NB.v("blPay", {})[no];
    var tl = i.no === "004176" ? ui.card(L("What happened on this invoice", "ما حدث في هذه الفاتورة", "इस इनवॉइस पर क्या हुआ"), '<div class="tl">' + [
      ["ok", "check", "17:40 · Consultation billed", fm(cv(700), 2) + " · invoice opened"], ["ok", "wallet", "17:42 · Interim payment " + fm(cv(700), 2), "Cash · pay_004176_01 · status: paid"],
      ["warn", "plus", "18:10 · 2D echo + ECG added", "Total rose to " + fm(s.T.total, 2) + " · status automatically back to partially paid"], ["acc", "clockh", "Now · collect balance", fm(s.bal, 2) + " is collectable"]
    ].map(function (e) { return '<div class="ev"><span class="dotc ' + e[0] + '">' + ui.icon(e[1], "sm") + '</span><div class="bd"><b>' + e[2] + "</b><small>" + e[3] + "</small></div></div>"; }).join("") + "</div>", { icon: "history" }) : "";
    var body = '<div class="row sb"><div>' + ui.cellp(ui.esc(P.name), '<span class="ltr">' + invNo(i.no) + "</span> · " + ui.esc(i.t)) + "</div>" + statusPill(s.st) + "</div>" +
      '<div class="bl-sum"><div class="r"><span>Invoice total</span>' + fm(s.T.total, 2) + '</div><div class="r"><span>Paid so far</span>' + fm(s.paid, 2) + "</div>" + (s.T.adv ? '<div class="r"><span>Advance held</span>' + fm(s.T.adv, 2) + "</div>" : "") + '<div class="r tot"><span>Balance</span>' + fm(s.bal, 2) + "</div></div>" + tl +
      '<div class="fgrid">' + ui.field("Amount", s.bal.toFixed(2), { id: "bl-pamt" }) + ui.field("Method", pms[0][1], { id: "bl-pm", options: pms.map(function (q) { return q[1]; }) }) +
      ui.field("Reference (UTR / approval code)", "", { id: "bl-pref", ph: "Auto-filled when the gateway confirms" }) + ui.field("Payment ID", "pay_" + i.no + "_02", { id: "bl-pid" }) + "</div>" +
      ui.note(rec ? "ok" : "info", rec ? "Already recorded as pay_" + i.no + "_02. Recording again returns the same receipt and does not charge twice." : "This payment ID is fixed when the drawer opens. If the network drops or someone taps twice, the server returns the first result instead of posting a second payment.");
    return { kind: "drawer", title: L("Record payment", "تسجيل دفعة", "भुगतान दर्ज करें"), body: body,
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Record payment", "تسجيل الدفعة", "भुगतान दर्ज करें") + " " + fm(s.bal).replace(/<[^>]+>/g, ""), { cls: "pri", act: "blpay", arg: i.no, icon: "check" }) };
  });
  NB.act("blpay", function (no) {
    var map = Object.assign({}, NB.v("blPay", {})), i = invByNo(no);
    if (map[no]) { NB.state.overlay = null; NB.toast("Duplicate ignored · pay_" + no + "_02 already recorded"); return; }
    map[no] = true; NB.state.ui.blPay = map; NB.state.overlay = null;
    NB.toast("Payment recorded · " + invNo(no) + " is now paid · receipt sent on WhatsApp");
  });

  NB.overlay("refund", function (no) {
    var i = invByNo(no) || D.invoices[0], s = invRow(i), P = coP(i), refs = NB.v("blRef", {}), exists = i.cn || refs[no];
    var m = NB.mkt();
    var body = '<div class="row sb">' + ui.cellp(ui.esc(P.name), '<span class="ltr">' + invNo(i.no) + "</span> · " + ui.esc(i.t) + " · " + ui.esc(i.mode || "")) + statusPill(s.st) + "</div>";
    if (exists) body += ui.note("crit", "<b>A refund already exists for this invoice</b> (" + exists + "). Only one refund per invoice: a second request is blocked so two approvers can never issue two credit notes for the same money.");
    else {
      var kind = NB.v("blRefK", "full"), amt = kind === "full" ? s.paid : r2(Math.min(s.paid, s.T.rows[s.T.rows.length - 1].total));
      body += '<div class="stack s8">' + ui.radio("blRefK", "full", "Full refund · " + fm(s.paid).replace(/<[^>]+>/g, ""), "Reverses every line and its tax", "full") +
        ui.radio("blRefK", "line", "One line · " + i.lines[i.lines.length - 1].d, "Partial credit note for this line only", "full") + "</div>" +
        '<div class="fgrid">' + ui.field("Reason", "Service not delivered", { id: "bl-rr", options: ["Service not delivered", "Billed in error", "Patient complaint (goodwill)", "Duplicate charge"] }) +
        ui.field("Refund to", "Original method · " + (i.mode || "UPI"), { id: "bl-rt", options: ["Original method · " + (i.mode || "UPI"), "Patient wallet (credit)", "Cash (needs owner approval)"] }) +
        ui.field("Requested by", "Pooja Nair", { id: "bl-rq" }) + ui.field("Approver", "Dr. Arvind Rao (owner)", { id: "bl-ap", options: ["Dr. Arvind Rao (owner)", "Ravi Menon (finance manager)"] }) + "</div>" +
        '<div class="bl-sum"><div class="r"><span>Refund</span>' + fm(amt, 2) + '</div><div class="r sub"><span>Tax reversed on the credit note</span>' + fm(r2(kind === "full" ? s.T.tax : s.T.rows[s.T.rows.length - 1].tax), 2) + "</div></div>" +
        ui.note("info", "Creates credit note <b>CN-0043</b> linked to " + invNo(i.no) + (m.id === "india" ? "; GST is reversed in this month's GSTR-1." : m.id === "ksa" ? "; the credit note is reported to ZATCA referencing the original invoice." : m.id === "uae" ? "; VAT is adjusted in this period's return." : ".") + " The requester cannot approve their own refund. Day close shows it as a refund, not a negative sale.");
    }
    return { kind: "drawer", title: L("Refund & credit note", "استرداد وإشعار دائن", "रिफ़ंड और क्रेडिट नोट"), body: body,
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Send for approval", "إرسال للموافقة", "स्वीकृति के लिए भेजें"), { cls: "pri", act: "blrefund", arg: i.no, icon: "send", disabled: !!exists }) };
  });
  NB.act("blrefund", function (no) {
    var refs = Object.assign({}, NB.v("blRef", {})), i = invByNo(no);
    if (refs[no] || (i && i.cn)) { NB.state.overlay = null; NB.toast("Blocked · this invoice already has a refund (" + (refs[no] || i.cn) + ")"); return; }
    refs[no] = "CN-00" + (43 + Object.keys(refs).length); NB.state.ui.blRef = refs; NB.state.overlay = null;
    NB.toast("Approved by Dr. Arvind Rao · credit note " + refs[no] + " issued · refund to original method");
  });

  NB.overlay("newinv", function () {
    var m = NB.mkt();
    return { kind: "drawer", title: L("New invoice", "فاتورة جديدة", "नया इनवॉइस"), body:
      ui.note("info", "Charges are priced from the catalogue or the payer price list. Changing a price is a discount and needs the discount permission with a reason.") +
      '<div class="fgrid">' + ui.field("Patient", "", { ph: "Name, phone, MRN or " + m.natId, full: true }) +
        ui.field("Invoice type", "OPD visit", { options: ["OPD visit", "IPD interim bill", "IPD final bill", "Pharmacy counter sale", "Package / membership sale", "Advance deposit"] }) +
        ui.field("Doctor", "Dr. Vikram Shah", { options: NB.D.doctors.map(function (d) { return d.name; }) }) + "</div>" +
      '<div class="stack s8"><span class="eyebrow">Quick add</span><div class="chips">' + ["Consultation", "Follow-up", "Dressing", "ECG", "CBC", "HbA1c", "Lipid profile", "Nebulisation", "Vaccine administration"].map(function (x) {
        return '<span class="chip" data-act="toast" data-arg="' + x + ' added at catalogue price">' + ui.icon("plus", "xs") + x + "</span>"; }).join("") + "</div></div>",
      foot: ui.btn("Cancel", { act: "close", cls: "ghost" }) + ui.btn("Create draft invoice", { cls: "pri", act: "toast", arg: "Draft invoice created · number assigned when issued", icon: "check" }) };
  });

  /* ================= billing.preauth ================= */
  var PAS = { draft: ["mute", "Draft", "edit"], submitted: ["sync", "Awaiting insurer"], query: ["warn", "Query raised", "alertcircle"], approved: ["ok", "Approved"], partial: ["info", "Partially approved", "alertcircle"], denied: ["crit", "Denied", "alertfill"] };
  var PADOCS = ["Doctor's admission note & plan", "Photo ID + policy card", "Cost estimate (signed)", "Investigation reports", "Consent & declaration"];
  NB.screen("billing.preauth", function (c) {
    var m = c.m, f = NB.v("blPaF", "open"), list = D.preauths;
    var pred = { open: function (x) { return /draft|submitted|query/.test(x.st); }, decided: function (x) { return /approved|partial|denied/.test(x.st); }, all: function () { return true; } };
    var rows = list.filter(pred[f]);
    var h = ui.header(L("Pre-authorisation", "الموافقة المسبقة", "प्री-ऑथराइज़ेशन"), "Cashless and planned admissions · channel: " + CH[m.id] + (m.id === "india" ? " · IRDAI target: decision within 1 hour, discharge approval within 3 hours" : " · payer contract SLA tracked per request"),
      ui.btn(L("New request", "طلب جديد", "नया अनुरोध"), { cls: "pri", ov: "preauth", arg: "new", icon: "plus" }));
    var open = list.filter(pred.open), awaiting = open.reduce(function (a, x) { return a + x.est; }, 0);
    h += '<div class="grid g4 kpis">' + ui.kpi(String(open.length), L("Open requests", "طلبات مفتوحة", "खुले अनुरोध"), "1 over SLA", "warnc") + ui.kpi("44 min", L("Median insurer response", "متوسط رد شركة التأمين", "औसत बीमा जवाब"), "Last 30 days", "faint") +
      ui.kpi("86%", L("Approved first time", "موافقة من أول مرة", "पहली बार में स्वीकृत"), "↑ 4 pts with the document checklist", "okc") + ui.kpi(NB.money(awaiting), L("Estimate awaiting decision", "مبالغ بانتظار القرار", "निर्णय प्रतीक्षित"), "", "faint") + "</div>";
    h += "<div>" + ui.seg("blPaF", [["open", L("Open", "مفتوحة", "खुले"), open.length], ["decided", L("Decided", "تم البت", "निर्णीत"), list.filter(pred.decided).length], ["all", L("All", "الكل", "सभी"), list.length]], "open") + "</div>";
    var table = ui.table([{ h: L("Request", "الطلب", "अनुरोध") }, { h: L("Patient", "المريض", "मरीज़") }, { h: L("Payer", "الدافع", "बीमाकर्ता") }, { h: L("Estimate", "التقدير", "अनुमान"), cls: "r" }, { h: L("Approved", "المعتمد", "स्वीकृत"), cls: "r" },
      { h: L("Insurer response", "رد شركة التأمين", "बीमा जवाब") }, { h: L("Documents", "المستندات", "दस्तावेज़") }, { h: L("Status", "الحالة", "स्थिति") }],
      rows.map(function (x) {
        var s = PAS[x.st], docs = x.docs.filter(Boolean).length;
        var resp = x.st === "submitted" || x.st === "query" ? '<div class="stack s4" style="min-width:130px"><span class="small ' + (x.el > x.sla ? "critc" : "") + '"><span class="num">' + x.el + "</span> / " + x.sla + " min" + (x.el > x.sla ? " · over SLA" : "") + "</span>" + ui.bar(x.el / x.sla * 100, x.el > x.sla ? "crit" : x.el > x.sla * 0.7 ? "warn" : "") + "</div>"
          : x.resp ? '<span class="small">' + x.resp + "</span>" : '<span class="faint small">Not sent</span>';
        return { ov: "preauth", arg: x.id, cls: x.st === "query" ? "urg" : x.st === "denied" ? "crit" : "", cells: [ui.cellp('<span class="ltr">' + x.id + "</span>", ui.esc(x.sub || "Draft")), ui.cellp(ui.esc(x.name), x.age + " " + x.sex + " · " + ui.esc(x.for)),
          ui.cellp(ui.esc(payerName(x.ins)), ui.esc(CH[m.id])), NB.money(x.est), x.appr ? NB.money(x.appr) : '<span class="faint">—</span>', resp,
          '<span class="small ' + (docs < 5 ? "warnc" : "okc") + '">' + docs + "/5</span>", ui.pill(s[0], s[1], s[2])] };
      }), { min: 1040 });
    var rail = ui.card(L("Needs you now", "يحتاجك الآن", "अभी ध्यान दें"), '<div class="lst">' +
      ui.li('<span class="pill warn" style="height:32px;width:32px;padding:0;justify-content:center">' + ui.icon("alertcircle", "sm") + "</span>", "PA-2214 · query from insurer", "Chest X-ray report + SpO₂ chart · 40 min over SLA", ui.icon("chevright", "sm faint flip"), { ov: "preauth", arg: "PA-2214" }) +
      ui.li('<span class="pill mute" style="height:32px;width:32px;padding:0;justify-content:center">' + ui.icon("edit", "sm") + "</span>", "PA-2216 · draft, 2 documents missing", "Guardian's policy card, ENT note", ui.icon("chevright", "sm faint flip"), { ov: "preauth", arg: "PA-2216" }) +
      ui.li('<span class="pill info" style="height:32px;width:32px;padding:0;justify-content:center">' + ui.icon("alertcircle", "sm") + "</span>", "PA-2211 · partially approved", "Tell the family about the shortfall before day 6", ui.icon("chevright", "sm faint flip"), { ov: "preauth", arg: "PA-2211" }) + "</div>", { icon: "bell" }) +
      "" + ui.card(L("Asked for at order time", "يُطلب عند الطلب", "ऑर्डर के समय"), '<p class="small muted" style="margin:0">When a doctor orders an admission, surgery or a high-cost scan for an insured patient, Nabd drafts the pre-auth with the note, estimate and reports attached, so it is never requested after the service.</p>', { icon: "sparkle" });
    return h + '<div class="bl-nw">' + table + "</div>" + '<div class="grid g2">' + rail + "</div>";
  });
  NB.overlay("preauth", function (id) {
    var m = NB.mkt(), x = D.preauths.find(function (p) { return p.id === id; });
    if (!x) x = { id: "PA-2220", name: "", age: "", sex: "", ins: 0, for: "", days: 3, est: 0, st: "draft", docs: [0, 0, 0, 0, 0], dx: "" };
    var s = PAS[x.st], est = x.est || 0;
    var parts = [["Room & nursing", 0.22], ["Doctor & specialist fees", 0.18], ["OT / procedure & anaesthesia", x.for.indexOf("surgery") >= 0 || x.for.indexOf("Elective") >= 0 ? 0.25 : 0.06], ["Medicines & consumables", 0.2], ["Investigations", 0.12]];
    var ps = parts.reduce(function (a, p) { return a + p[1]; }, 0);
    var body = '<div class="row sb">' + ui.cellp(ui.esc(x.name || "New patient") + (x.age ? " · " + x.age + " " + x.sex : ""), ui.esc(x.for || "Choose admission or procedure")) + ui.pill(s[0], s[1], s[2]) + "</div>";
    if (x.query) body += ui.note("warn", "<b>Insurer query:</b> " + ui.esc(x.query) + " Reply within the SLA to keep the cashless request alive.");
    if (x.st === "denied") body += ui.note("crit", "<b>Denied:</b> " + ui.esc(x.reason) + ". Options: appeal with the treating doctor's letter, or move to self-pay with a signed estimate and deposit (link to billing).");
    if (x.st === "partial") body += ui.note("info", "<b>Approved " + NB.money(x.appr).replace(/<[^>]+>/g, "") + " of " + NB.money(x.est).replace(/<[^>]+>/g, "") + "</b> · " + ui.esc(x.reason) + ". The shortfall is shown to the family on WhatsApp in their language before it is billed.");
    body += '<div class="fgrid">' + ui.field("Payer", payerName(x.ins), { id: "pa-pay" }) + ui.field("Channel", CH[m.id], { id: "pa-ch" }) + ui.field("Diagnosis (" + (m.id === "ksa" ? "ICD-10-AM" : m.id === "india" ? "ICD-10" : "ICD-10-CM") + ")", x.dx, { id: "pa-dx" }) + ui.field("Expected stay", x.days + " days", { id: "pa-days" }) + "</div>";
    body += ui.card(L("Estimate", "التقدير", "अनुमान"), '<div class="bl-sum">' + parts.map(function (p) { return '<div class="r"><span>' + p[0] + "</span>" + NB.money(Math.round(est * p[1] / ps)) + "</div>"; }).join("") +
      '<div class="r tot"><span>Estimate</span>' + NB.money(est) + "</div></div>" + '<p class="small faint" style="margin:8px 0 0">Priced from the ' + ui.esc(payerName(x.ins).split(" via")[0]) + " price list. A copy goes to the patient with the expected co-pay.</p>", { icon: "receipt" });
    body += ui.card(L("Documents", "المستندات", "दस्तावेज़"), PADOCS.map(function (d, i) {
      return '<div class="bl-doc">' + (x.docs[i] ? ui.icon("checkcircle", "sm okc") : ui.icon("alertcircle", "sm warnc")) + '<span class="fl">' + d + "</span>" + (x.docs[i] ? ui.pill("ok", "Attached") : ui.btn("Attach", { cls: "sm", act: "toast", arg: d + " attached from the record" })) + "</div>"; }).join(""), { icon: "file" });
    body += ui.card(L("Timeline", "الجدول الزمني", "टाइमलाइन"), '<div class="tl">' + [
      ["ok", "edit", "Drafted from the admission order", "Dr. Vikram Shah · note, estimate and reports attached automatically"],
      x.sub ? ["acc", "send", "Submitted · " + x.sub, CH[m.id] + " · acknowledgement received"] : ["", "clockh", "Not submitted yet", "Missing documents block submission"],
      x.query ? ["warn", "alertcircle", "Query raised by insurer", ui.esc(x.query)] : x.resp ? [x.st === "denied" ? "crit" : "ok", x.st === "denied" ? "close" : "check", "Decision in " + x.resp, s[1]] : ["", "hourglass", "Waiting for insurer", x.el ? x.el + " min so far" : ""]
    ].map(function (e) { return '<div class="ev"><span class="dotc ' + e[0] + '">' + ui.icon(e[1], "sm") + '</span><div class="bd"><b>' + e[2] + "</b><small>" + e[3] + "</small></div></div>"; }).join("") + "</div>", { icon: "history" });
    var main = x.st === "draft" ? ui.btn("Submit to " + CH[m.id].split(" ")[0], { cls: "pri", act: "toast", arg: x.id + " submitted · insurer response timer started", icon: "send", disabled: x.docs.filter(Boolean).length < 4 })
      : x.st === "query" ? ui.btn("Reply with documents", { cls: "pri", act: "toast", arg: "Reply sent with X-ray report and SpO₂ chart", icon: "send" })
      : x.st === "denied" ? ui.btn("Convert to self-pay estimate", { cls: "pri", act: "toast", arg: "Self-pay estimate sent to family on WhatsApp", icon: "wallet" })
      : ui.btn("Request enhancement", { cls: "pri", act: "toast", arg: "Enhancement request drafted", icon: "plus" });
    return { kind: "drawer", size: "lg", title: L("Pre-authorisation", "الموافقة المسبقة", "प्री-ऑथ") + " · " + x.id, body: body, foot: ui.btn("Close", { act: "close", cls: "ghost" }) + main };
  });

  /* ================= billing.claims ================= */
  var CLS = { draft: ["mute", "Draft", "edit"], submitted: ["sync", "Submitted"], review: ["info", "In review", "hourglass"], approved: ["ok", "Approved"], partial: ["warn", "Partial", "alertcircle"],
    rejected: ["crit", "Rejected", "alertfill"], resubmitted: ["acc", "Resubmitted", "refresh"], paid: ["ok", "Paid", "banknote"] };
  var CLORDER = ["draft", "submitted", "review", "approved", "partial", "rejected", "resubmitted", "paid"];
  function rejInfo(k) {
    var uae = mi() === "uae";
    return { dx: [uae ? "CODE-010" : "", "Diagnosis missing or does not support the service", "The note says “Z23 encounter for immunisation”; the claim had no code. Ask the doctor to confirm the suggested code in one tap, then resubmit."],
      auth: [uae ? "AUTH-001" : "", "Prior authorisation required but not attached", "Approved request PA-2188 exists for this stay. Attach its reference and resubmit within the payer window."],
      cover: [uae ? "MNEC-004" : "", "Service not covered under the plan (cosmetic)", "Exclusion is valid. Convert the line to self-pay and send the patient a payment link; do not resubmit."],
      price: [uae ? "PRCE-001" : "", "Amount above contracted price", "Reprice from the 2026 payer price list. The difference is a contractual write-off, not billable to the patient."],
      late: [uae ? "TIME-001" : "", "Submitted after the time limit", "Outside the submission window. Appeal only with a documented reason; otherwise write off and fix the same-day submission rule."] }[k] || ["", "", ""];
  }
  NB.screen("billing.claims", function (c) {
    var m = c.m, f = NB.v("blClSt", "all"), list = D.claims;
    var rows = list.filter(function (x) { return f === "all" || x.st === f; });
    var h = ui.header(L("Insurance claims", "مطالبات التأمين", "बीमा क्लेम"), "Channel: <b>" + CH[m.id] + "</b> · coding: " + CODES[m.id],
      ui.btn(L("Import remittance", "استيراد التحويل", "रेमिटेंस आयात"), { act: "toast", arg: "Remittance file matched: 6 claims reconciled automatically", icon: "upload" }) + ui.btn(L("Submit ready claims", "إرسال المطالبات الجاهزة", "तैयार क्लेम भेजें"), { cls: "pri", act: "toast", arg: "2 scrubbed claims submitted to " + CH[m.id], icon: "send" }));
    h += '<div class="grid g4 kpis">' + ui.kpi("91.4%", L("First-pass acceptance", "القبول من أول مرة", "पहली बार स्वीकृति"), "Target ≥ 90%", "okc") + ui.kpi("96%", L("Submitted same day", "أُرسلت في نفس اليوم", "उसी दिन भेजे"), "Target ≥ 95%", "okc") +
      ui.kpi("38 days", L("A/R days", "أيام الذمم", "A/R दिन"), "Target < 45", "faint") + ui.kpi(NB.money(list.filter(function (x) { return x.st === "rejected"; }).reduce(function (a, x) { return a + x.amt; }, 0)), L("Rejected, to fix", "مرفوضة للإصلاح", "ठीक करने हेतु अस्वीकृत"), "4 claims", "critc") + "</div>";
    h += '<div class="bl-pipe" role="tablist">' + '<button class="bl-st' + (f === "all" ? " on" : "") + '" data-set="blClSt" data-val="all"><small>' + L("All claims", "كل المطالبات", "सभी क्लेम") + "</small><b>" + list.length + "</b><small>" + NB.money(list.reduce(function (a, x) { return a + x.amt; }, 0)) + "</small></button>" +
      CLORDER.map(function (k) { var xs = list.filter(function (x) { return x.st === k; }), s = CLS[k];
        return '<button class="bl-st' + (f === k ? " on" : "") + '" data-set="blClSt" data-val="' + k + '">' + ui.pill(s[0], s[1], s[2]) + "<b>" + xs.length + "</b><small>" + NB.money(xs.reduce(function (a, x) { return a + x.amt; }, 0)) + "</small></button>"; }).join("") + "</div>";
    var table = ui.table([{ h: L("Claim", "المطالبة", "क्लेम") }, { h: L("Patient", "المريض", "मरीज़") }, { h: L("Payer", "الدافع", "बीमाकर्ता") }, { h: L("Claimed", "المطالب به", "दावा"), cls: "r" }, { h: L("Approved", "المعتمد", "स्वीकृत"), cls: "r" }, { h: L("Age", "العمر", "उम्र"), cls: "r" }, { h: L("Status", "الحالة", "स्थिति") }],
      rows.map(function (x) { var s = CLS[x.st], ri = x.rej ? rejInfo(x.rej) : null;
        return { ov: "claim", arg: x.id, cls: x.st === "rejected" ? "crit" : "", cells: [ui.cellp('<span class="ltr">' + x.id + "</span>", x.svc + " · " + ui.esc(x.dos)), ui.cellp(ui.esc(x.name), ui.esc(x.ref)),
          ui.esc(payerName(x.ins)), NB.money(x.amt), x.appr ? NB.money(x.appr) : '<span class="faint">—</span>', '<span style="white-space:nowrap" class="num ltr ' + (x.age > 60 ? "critc" : x.age > 30 ? "warnc" : "") + '">' + x.age + " d</span>",
          ui.pill(s[0], s[1], s[2]) + (ri && x.st !== "paid" ? '<div class="tiny faint" style="margin-top:4px;max-width:220px;white-space:normal">' + (ri[0] ? ri[0] + " · " : "") + ui.esc(ri[1]) + "</div>" : "")] }; }), { min: 900, empty: "No claims at this stage." });
    var open = list.filter(function (x) { return x.st !== "paid"; });
    var bk = [["0–30 d", 0, 30], ["31–60 d", 31, 60], ["61–90 d", 61, 90], ["90+ d", 91, 9999]].map(function (b) { return [b[0], open.filter(function (x) { return x.age >= b[1] && x.age <= b[2]; }).reduce(function (a, x) { return a + x.amt; }, 0)]; });
    var mx = Math.max.apply(null, bk.map(function (b) { return b[1]; })) || 1;
    var age = ui.card(L("Ageing (unpaid)", "أعمار الذمم", "एजिंग (अदत्त)"), '<div class="bl-age">' + bk.map(function (b, i) { return '<div class="ar"><span class="small muted">' + b[0] + "</span>" + ui.bar(b[1] / mx * 100, i === 3 ? "crit" : i === 2 ? "warn" : "") + '<span class="small">' + NB.money(b[1]) + "</span></div>"; }).join("") + "</div>" +
      '<p class="small faint" style="margin:12px 0 0">Claims past 60 days are escalated to the payer relationship manager automatically.</p>', { icon: "hourglass" });
    var chan = ui.card(L("Channel", "القناة", "चैनल"), '<div class="kv"><dt>Exchange</dt><dd>' + CH[m.id] + "</dd><dt>Format</dt><dd>" + (m.id === "ksa" ? "FHIR R4 (Saudi IG)" : m.id === "uae" ? "DHPO XML" : m.id === "india" ? "Portal upload · NHCX FHIR" : "Portal / PDF") + "</dd><dt>Coding</dt><dd>" + CODES[m.id] + "</dd><dt>Last sync</dt><dd>10:12 · OK</dd></div>", { icon: "link" });
    // Rejection work queue
    var rq = list.filter(function (x) { return x.st === "rejected" || x.st === "partial"; }).map(function (x) {
      var ri = rejInfo(x.rej);
      return '<div class="ci">' + ui.icon(x.st === "partial" ? "alertcircle" : "alertfill", "sm " + (x.st === "partial" ? "warnc" : "critc")) + '<div class="t"><b>' + x.id + " · " + ui.esc(x.name) + " · " + NB.money(x.amt) + "</b><small>" + (ri[0] ? '<span class="ltr">' + ri[0] + "</span> · " : "") + ui.esc(ri[1]) + "</small>" +
        '<small style="color:var(--tx2);margin-top:4px">' + ui.icon("sparkle", "xs subc") + " " + ui.esc(ri[2]) + "</small></div>" + ui.btn(x.rej === "cover" ? "Bill patient" : x.rej === "price" ? "Accept & write off" : x.rej === "late" ? "Review" : "Fix & resubmit", { cls: "sm", ov: "claim", arg: x.id }) + "</div>";
    }).join("");
    var rej = ui.card(L("Rejections to fix", "الرفض المطلوب إصلاحه", "ठीक करने योग्य अस्वीकृतियाँ"), '<div class="bl-ck">' + rq + "</div>", { icon: "alertcircle", cls: "warnb" });
    // Claim-ready checklist (scrubber) for today's draft
    var ckItems = [["ok", "Eligibility verified", "09:36 · member active · " + payerName(1)], ["ok", "Member ID and policy valid", "HE-55120 · OPD benefit available"],
      ["warn", "Diagnosis coded and confirmed", "E03.9 hypothyroidism suggested from the note · waiting for Dr. Vikram Shah to confirm"], ["ok", "Codes match the documentation", "Consult, TSH and CBC are in the signed note and orders"],
      ["ok", "Prices from the payer price list", payerName(1).split(" via")[0] + " tariff 2026 applied"], ["ok", "Pre-authorisation", "Not required for this OPD service"],
      ["warn", "Documents attached", "Invoice, Rx attached · TSH report attaches automatically when released (14:00)"], ["ok", "Within submission window and not a duplicate", "Same-day submission"]];
    var ready = ckItems.filter(function (i) { return i[0] === "ok"; }).length;
    var ck = ui.card(L("Claim-ready check · CLM-8841", "فحص جاهزية المطالبة", "क्लेम-तैयार जाँच"), '<div class="row sb" style="margin-bottom:6px"><span class="small muted">Fatima Sheikh · OPD today · ' + NB.money(1243) + "</span>" + ui.pill(ready === ckItems.length ? "ok" : "warn", ready + "/" + ckItems.length + " passed") + "</div>" +
      '<div class="bl-ck">' + ckItems.map(function (i) { return '<div class="ci">' + ui.icon(i[0] === "ok" ? "checkcircle" : "alertcircle", "sm " + (i[0] === "ok" ? "okc" : "warnc")) + '<div class="t"><b>' + i[1] + "</b><small>" + i[2] + "</small></div></div>"; }).join("") + "</div>" +
      '<div class="btns" style="margin-top:10px">' + ui.btn("Ask doctor to confirm code", { cls: "sm", act: "toast", arg: "Sent to Dr. Vikram Shah · one-tap confirm", icon: "steth" }) + ui.btn("Submit when ready", { cls: "sm pri", act: "toast", arg: "CLM-8841 will auto-submit when all checks pass", icon: "send" }) + "</div>", { icon: "shieldcheck", cls: "hl" });
    return h + '<div class="bl-nw">' + table + "</div>" + '<div class="grid g2">' + rej + ck + "</div>" + '<div class="grid g2">' + age + chan + "</div>";
  });
  NB.overlay("claim", function (id) {
    var m = NB.mkt(), x = D.claims.find(function (k) { return k.id === id; }) || D.claims[0], s = CLS[x.st], ri = x.rej ? rejInfo(x.rej) : null;
    var idx = CLORDER.indexOf(x.st);
    var steps = [["edit", "Created from invoice", x.ref], ["shieldcheck", "Scrubbed", "8/8 checks"], ["send", "Submitted", CH[m.id] + " · transmission " + ("TX" + hash(x.id)).slice(0, 10)], ["hourglass", "Adjudication", ""], ["banknote", "Remittance", ""]];
    var reached = { draft: 1, submitted: 3, review: 4, approved: 4, partial: 4, rejected: 4, resubmitted: 3, paid: 5 }[x.st];
    var body = '<div class="row sb">' + ui.cellp(ui.esc(x.name) + " · " + x.svc, ui.esc(payerName(x.ins)) + " · " + ui.esc(x.dos)) + ui.pill(s[0], s[1], s[2]) + "</div>" +
      '<div class="grid g2">' + ui.kpi(NB.money(x.amt), "Claimed") + ui.kpi(x.appr ? NB.money(x.appr) : "—", x.st === "paid" ? "Paid" : "Approved") + "</div>";
    if (ri) body += ui.note(x.st === "partial" ? "warn" : "crit", "<b>" + (ri[0] ? ri[0] + " · " : "") + ui.esc(ri[1]) + "</b><br>" + ui.icon("sparkle", "xs") + " Suggested fix: " + ui.esc(ri[2]));
    body += ui.card(L("Coding", "الترميز", "कोडिंग"), '<div class="kv"><dt>Diagnosis</dt><dd>' + (m.id === "ksa" ? "ICD-10-AM" : m.id === "india" ? "ICD-10" : "ICD-10-CM") + " " + (x.rej === "dx" ? '<span class="warnc">missing → Z23 suggested</span>' : x.svc === "IPD" ? "I50.9" : "E03.9") + "</dd><dt>Services</dt><dd>" +
      (m.id === "ksa" ? "SBS codes mapped (3/3)" : m.id === "india" ? (x.ins === 2 ? "PM-JAY HBP package code" : "Hospital tariff codes") : "CPT 99213 · 84443 · 85025") + "</dd><dt>Medicines</dt><dd>" + (m.id === "uae" ? "DDC codes" : m.id === "ksa" ? "SFDA registration no." : "Brand + batch") + "</dd><dt>Reference</dt><dd>" + ui.esc(x.ref) + "</dd></div>", { icon: "list" });
    body += ui.card(L("Status", "الحالة", "स्थिति"), '<div class="tl">' + steps.map(function (e, i) {
      var cls = i < reached ? (i === 3 && x.st === "rejected" ? "crit" : "ok") : i === reached ? "acc" : "";
      var sub = i === 3 ? (x.st === "review" ? "In review · day " + x.age : x.st === "rejected" ? "Rejected" : x.st === "partial" ? "Partially approved" : i < reached ? "Approved" : "") : i === 4 && x.st === "paid" ? ui.esc(x.ref) + " · auto-reconciled" : e[2];
      return '<div class="ev"><span class="dotc ' + cls + '">' + ui.icon(e[0], "sm") + '</span><div class="bd"><b>' + e[1] + "</b><small>" + sub + "</small></div></div>"; }).join("") + "</div>", { icon: "history" });
    var main = x.st === "draft" ? ui.btn("Submit", { cls: "pri", act: "toast", arg: x.id + " submitted to " + CH[m.id], icon: "send" })
      : x.st === "rejected" ? ui.btn(x.rej === "cover" ? "Convert to self-pay" : "Fix & resubmit", { cls: "pri", act: "toast", arg: x.rej === "cover" ? "Line moved to self-pay · payment link sent" : x.id + " corrected and resubmitted", icon: x.rej === "cover" ? "wallet" : "refresh" })
      : x.st === "partial" ? ui.btn("Accept & write off difference", { cls: "pri", act: "toast", arg: "Contractual write-off posted", icon: "check" }) : "";
    return { kind: "drawer", title: L("Claim", "المطالبة", "क्लेम") + " · " + x.id, body: body, foot: ui.btn("Close", { act: "close", cls: "ghost" }) + main };
  });

  /* ================= billing.packages ================= */
  // Payment plan per sold package (FRD PKG-06/07, PR-03..05). inst: [amount, due, status, note]; status paid | due | sched | failed.
  var PKX = {
    "PKG-0193": { plan: "inst", inst: [[4500, "02 Oct 2026", "paid", "UPI AutoPay"], [4500, "01 Nov 2026", "sched", "AutoPay · reminder 29 Oct"], [4500, "01 Dec 2026", "sched", ""], [4500, "31 Dec 2026", "sched", ""]] },
    "PKG-0211": { plan: "inst", inst: [[2500, "20 Jul 2026", "paid", "UPI"], [2500, "20 Aug 2026", "paid", "UPI AutoPay"], [2500, "20 Oct 2026", "due", "Reminder sent 17 Oct · WhatsApp"]] },
    "PKG-0169": { plan: "inst", inst: [[2200, "20 Jul 2026", "paid", "Card"], [2150, "20 Aug 2026", "paid", "Card on file"], [2150, "15 Oct 2026", "failed", "Collection failed · retry 1 of 2 on 18 Oct"]] },
    "PKG-0181": { plan: "inst", inst: [[3500, "10 Aug 2026", "paid", "UPI"], [3500, "10 Sep 2026", "failed", "Mandate failed · retried 11 Sep"], [3500, "10 Oct 2026", "failed", "Mandate failed · retried 11 Oct"], [3500, "10 Nov 2026", "sched", ""]] },
    "PKG-0187": { plan: "pps", per: 2500 },
    "PKG-0162": { plan: "full", moved: { to: "Ayesha Sheikh", rel: "sister", n: 2, on: "12 Oct 2026" } }
  };
  function pkx(k) { return PKX[k.id] || { plan: "full" }; }
  function pkPaidV(k) { var x = pkx(k); return x.plan === "inst" ? x.inst.reduce(function (a, i) { return a + (i[2] === "paid" ? i[0] : 0); }, 0) : x.plan === "pps" ? k.used * x.per : k.price; }
  function pkLiab(k) {
    if (k.st === "refunded" || k.st === "completed") return 0;
    if (k.time) return Math.round(k.price * (12 - k.mUsed) / 12);
    if (pkx(k).plan === "pps") return 0;  // pay-per-session: nothing is prepaid beyond the visit
    return Math.round(k.price / k.total * (k.total - k.used));
  }
  function planCell(k) {
    var x = pkx(k);
    if (x.plan === "pps") return ui.cellp(L("Pay per session", "الدفع لكل جلسة", "हर सत्र भुगतान"), fm(cv(x.per)).replace(/<[^>]+>/g, "") + " × " + k.total + " · discount locked");
    if (x.plan !== "inst") return ui.cellp(L("Full", "كامل", "पूरा"), k.time ? "Annual" : "Paid at sale");
    var paid = x.inst.filter(function (i) { return i[2] === "paid"; }).length, failed = x.inst.filter(function (i) { return i[2] === "failed"; }).length, nx = x.inst.find(function (i) { return i[2] !== "paid"; });
    return ui.cellp(L("Instalments ", "أقساط ", "किस्तें ") + paid + "/" + x.inst.length, failed ? '<span class="critc">' + failed + " failed" + (failed >= 2 ? " · bookings blocked" : " · retrying") + "</span>" : nx ? "Next " + nx[1].slice(0, 6) : "All paid");
  }
  var PKS = { active: ["ok", "Active"], expiring: ["warn", "Expiring soon", "clockh"], completed: ["mute", "Completed", "check"], refpend: ["sync", "Refund pending"], refunded: ["perm", "Refunded", "refresh"], "default": ["crit", "Payment default", "alertfill"] };
  NB.screen("billing.packages", function (c) {
    var m = c.m, f = NB.v("blPkF", "all"), list = D.packages, tab = NB.v("blPkTab", "list");
    var liab = list.reduce(function (a, k) { return a + pkLiab(k); }, 0);
    var h = ui.header(L("Packages & memberships", "الباقات والعضويات", "पैकेज और सदस्यता"), "Prepaid sessions and memberships · unused value is a liability until it is used, expires or is refunded",
      ui.btn(L("Sell package", "بيع باقة", "पैकेज बेचें"), { cls: "pri", ov: "pksell", icon: "plus" }));
    h += '<div class="grid g4 kpis">' + ui.kpi(String(list.filter(function (k) { return /active|expiring|default/.test(k.st); }).length), L("Active", "نشطة", "सक्रिय"), "Packages + memberships", "faint") +
      ui.kpi(NB.money(liab), L("Unearned liability", "الالتزام غير المكتسب", "अनर्जित देनदारी"), "Recognised as sessions are used", "warnc") + ui.kpi("42", L("Sessions redeemed this month", "الجلسات المستخدمة هذا الشهر", "इस माह सत्र उपयोग"), "↑ 8 vs September", "okc") +
      ui.kpi(NB.money(2500 + 4500 + 2150), L("Instalments due in 30 days", "أقساط مستحقة خلال 30 يومًا", "30 दिन में देय किस्तें"), "1 overdue · 1 in default", "critc") + "</div>";
    h += ui.tabs("blPkTab", [["list", L("Packages", "الباقات", "पैकेज"), "layers"], ["inst", L("Instalments & pay-per-session", "الأقساط والدفع لكل جلسة", "किस्तें और हर-सत्र भुगतान"), "calendar"], ["moves", L("Transfers & upgrades", "النقل والترقية", "ट्रांसफ़र और अपग्रेड"), "share"], ["tax", L("Tax split", "توزيع الضريبة", "टैक्स विभाजन"), "receipt"]], "list");
    if (tab === "inst") return h + pkInstTab(m, list);
    if (tab === "moves") return h + pkMovesTab(m, list);
    if (tab === "tax") return h + pkTaxTab(m);
    var rows = list.filter(function (k) { return f === "all" || k.st === f || (f === "refund" && /ref/.test(k.st)) || (f === "active" && k.st === "default"); });
    h += "<div>" + ui.seg("blPkF", [["all", "All", list.length], ["active", "Active"], ["expiring", "Expiring"], ["completed", "Completed"], ["refund", "Refunds"]], "all") + "</div>";
    var table = ui.table([{ h: L("Package", "الباقة", "पैकेज") }, { h: L("Patient", "المريض", "मरीज़") }, { h: L("Price", "السعر", "मूल्य"), cls: "r" }, { h: L("Payment plan", "خطة الدفع", "भुगतान योजना") }, { h: L("Used · expiry", "المستخدم · الانتهاء", "उपयोग · समाप्ति") }, { h: L("Liability", "الالتزام", "देनदारी"), cls: "r" }, { h: L("Status", "الحالة", "स्थिति") }, { h: "" }],
      rows.map(function (k) { var P = NB.pat(k.p), s = PKS[k.st], pctU = k.time ? k.mUsed / 12 * 100 : k.used / k.total * 100;
        var act = /active|expiring|default/.test(k.st) ? ui.btn("Refund preview", { cls: "sm", ov: "pkrefund", arg: k.id, icon: "refresh" }) : k.st === "refpend" ? ui.btn("View request", { cls: "sm", ov: "pkrefund", arg: k.id }) : "";
        return { cls: k.st === "expiring" ? "urg" : k.st === "default" ? "crit" : "", cells: [ui.cellp('<span class="ltr">' + k.id + "</span>", ui.esc(k.plan)), ui.cellp(ui.esc(NB.pname(P)), "Sold " + k.sold), NB.money(k.price),
          '<button class="bl-plk" data-ov="pkplan" data-arg="' + k.id + '">' + planCell(k) + "</button>",
          '<div class="stack s4" style="min-width:120px"><span class="small">' + (k.time ? '<span class="num">' + k.used + "</span> visits · " + k.mUsed + "/12 months" : '<span class="num">' + k.used + "/" + k.total + "</span> sessions") + (k.note ? " · " + k.note : "") + "</span>" + ui.bar(pctU, pctU >= 100 ? "ok" : "") + '<span class="tiny ' + (k.st === "expiring" ? "warnc" : "faint") + '">' + L("Expires ", "ينتهي ", "समाप्ति ") + k.exp + "</span></div>", pkLiab(k) ? NB.money(pkLiab(k)) : '<span class="faint">—</span>', ui.pill(s[0], s[1], s[2]) + (k.cn ? '<div class="tiny faint">' + k.cn + "</div>" : k.rf ? '<div class="tiny faint">' + k.rf + "</div>" : ""), act] }; }), { min: 1040 });
    var cat = ui.card(L("Catalogue", "الكتالوج", "कैटलॉग"), '<div class="lst">' + [["Laser hair reduction · 6", 18000, 21000, 14, "cos"], ["Melasma peel series · 4", 10000, 12000, 9, "cos"], ["Physiotherapy · 10", 6500, 9000, 22, "svc"], ["Diabetes care plan · 12 months", 7200, 7600, 31, "svc"], ["Senior care membership", 6000, 0, 48, "svc"]].map(function (p) {
      var tr = taxOf({ cat: p[4], r: p[1] }, {}); return ui.li('<span class="av">' + ui.icon("layers", "sm") + "</span>", p[0], (p[2] ? '<span class="bl-strike">' + fm(cv(p[2])) + "</span> → " : "") + NB.money(p[1]) + (p[2] ? " · save " + Math.round(100 * (p[2] - p[1]) / p[2]) + "%" : "") + " · " + (tr.rate ? (m.id === "india" ? "GST " : "VAT ") + tr.rate + "%" : tr.lbl) + " · " + p[3] + " sold", ""); }).join("") + "</div>", { icon: "layers" });
    var rules = ui.card(L("Refund rules", "قواعد الاسترداد", "रिफ़ंड नियम"), '<div class="bl-ck">' + [["One refund per package", "A pending request blocks a second one."], ["Redemption is frozen while a refund is pending", "No session can be used between request and approval."],
      ["Used sessions at the full single-session price", "Refund = paid − used × single price. No cancellation or processing fee by default."], ["Shortfall is shown, not hidden", "If the patient paid less than they used (instalments), the preview says “patient owes” and open instalments are cancelled."],
      ["Requester never approves", "Owner or finance approves; a credit note reverses the tax, refund goes to source."]].map(function (r) {
      return '<div class="ci">' + ui.icon("checkcircle", "sm okc") + '<div class="t"><b>' + r[0] + "</b><small>" + r[1] + "</small></div></div>"; }).join("") + "</div>", { icon: "shieldcheck" });
    return h + '<div class="bl-nw bl-pkt">' + table + "</div>" + '<div class="grid g2">' + cat + rules + "</div>";
  });

  /* ---- Instalments & pay-per-session ---- */
  function instPill(st) {
    return st === "paid" ? ui.pill("ok", "Paid") : st === "due" ? ui.pill("warn", "Due in 3 days", "clockh") : st === "failed" ? ui.pill("crit", "Failed", "alertfill") : ui.pill("mute", "Scheduled", "calendar");
  }
  function autoName() { return { india: "UPI AutoPay (e-mandate)", uae: "Card on file (tokenised)", ksa: "mada / card on file", qatar: "Card on file (NAPS)" }[mi()]; }
  function pkInstTab(m, list) {
    var rows = [];
    list.forEach(function (k) { var x = pkx(k); if (x.plan !== "inst") return; x.inst.forEach(function (i, n) { if (i[2] === "paid" && n < x.inst.length - 2) return; rows.push({ k: k, i: i, n: n + 1, of: x.inst.length, fails: x.inst.filter(function (z) { return z[2] === "failed"; }).length }); }); });
    var ord = { failed: 0, due: 1, sched: 2, paid: 3 };
    rows.sort(function (a, b) { return ord[a.i[2]] - ord[b.i[2]]; });
    var h = '<div class="grid g4 kpis">' + ui.kpi(NB.money(2500), "Due in 7 days", "1 instalment · reminder sent", "warnc") + ui.kpi(NB.money(2150 + 3500 + 3500), "Overdue", "3 failed collections", "critc") +
      ui.kpi(NB.money(4500 + 2500 + 2150), "Collected this month", "AutoPay 2 · desk 1", "okc") + ui.kpi("1", "Bookings blocked", "PKG-0181 · 2 failed instalments", "critc") + "</div>";
    h += '<div class="bl-nw">' + ui.table([{ h: "Package" }, { h: "Patient" }, { h: "Instalment" }, { h: "Due" }, { h: "Amount", cls: "r" }, { h: "Collection" }, { h: "Status" }, { h: "" }], rows.map(function (r) {
      var P = NB.pat(r.k.p), blocked = r.fails >= 2;
      var act = r.i[2] === "failed" ? (blocked ? ui.btn("Override block", { cls: "sm", ov: "pkplan", arg: r.k.id, icon: "unlock" }) : ui.btn("Send pay link", { cls: "sm", act: "toast", arg: "Pay link sent to " + NB.pname(P) + " on WhatsApp", icon: "send" }))
        : r.i[2] === "due" || r.i[2] === "sched" ? ui.btn("Remind now", { cls: "sm ghost", act: "toast", arg: "Reminder sent on WhatsApp (SMS fallback if undelivered)", icon: "bell" }) : "";
      return { cls: r.i[2] === "failed" ? "crit" : r.i[2] === "due" ? "urg" : "", cells: [ui.cellp('<span class="ltr">' + r.k.id + "</span>", ui.esc(r.k.plan)), ui.esc(NB.pname(P)), '<span class="num">' + r.n + " of " + r.of + "</span>", '<span class="small">' + r.i[1] + "</span>", fm(cv(r.i[0])),
        ui.cellp(ui.esc(r.i[2] === "paid" ? r.i[3] : autoName()), ui.esc(r.i[2] === "paid" ? "Receipt sent" : r.i[3] || "Reminder 3 days before + on the day")), instPill(r.i[2]) + (blocked && r.i[2] === "failed" ? '<div class="tiny critc">Bookings blocked</div>' : ""), act] };
    }), { min: 1060 }) + "</div>";
    var pps = list.filter(function (k) { return pkx(k).plan === "pps"; }).map(function (k) {
      var x = pkx(k), P = NB.pat(k.p);
      return ui.li('<span class="av">' + ui.icon("wallet", "sm") + "</span>", ui.esc(NB.pname(P)) + " · " + ui.esc(k.plan), "Session " + (k.used + 1) + " of " + k.total + " at " + fm(cv(x.per)).replace(/<[^>]+>/g, "") + " (list " + fm(cv(k.single)).replace(/<[^>]+>/g, "") + ") · discount locked while all sessions are completed · collected " + fm(cv(pkPaidV(k))).replace(/<[^>]+>/g, "") + " so far", ui.pill("sub", "Per session", "wallet"));
    }).join("");
    var rules = '<div class="bl-ck">' + [["Schedule shown before the patient agrees", "2–4 instalments on fixed dates, generated at sale and sent on WhatsApp for approval."], ["Auto-reminder", "WhatsApp 3 days before and on the due date" + (m.id === "india" ? "; RBI pre-debit notice 24 h before each UPI AutoPay debit" : "") + "; SMS fallback if WhatsApp isn't delivered."],
      ["Retry, then block", "A failed collection retries once next day. After 2 failed instalments new session bookings are blocked; the owner can override with a reason."], ["Clinic credit, not a loan", "No lender, no interest. The patient can pay early from WhatsApp at any time."]].map(function (r) {
      return '<div class="ci">' + ui.icon("checkcircle", "sm okc") + '<div class="t"><b>' + r[0] + "</b><small>" + r[1] + "</small></div></div>"; }).join("") + "</div>";
    return h + '<div class="grid g2">' + ui.card("Pay-per-session packages", '<div class="lst">' + pps + "</div>" + '<p class="small faint" style="margin:10px 0 0">Nothing is prepaid, so there is no liability. If the patient stops early the discount is not clawed back.</p>', { icon: "wallet" }) + ui.card("Collection rules", rules, { icon: "shieldcheck" }) + "</div>";
  }

  /* ---- Transfers, gifting, upgrade / downgrade ---- */
  var UPG = {
    "MEM-0041": { up: ["Diabetes care plan plus · 12", 12, 9900], down: ["Diabetes care lite · 2 reviews", 2, 2000] },
    "PKG-0211": { up: ["Physiotherapy · 15 sessions", 15, 10500], down: ["Physiotherapy · 5 sessions", 5, 4000] },
    "PKG-0193": { up: ["Laser hair reduction · 10", 10, 27000], down: ["Laser touch-up · 4", 4, 13000] }
  };
  function pkMovesTab(m, list) {
    var tr = [
      ["PKG-0162", "Fatima Sheikh", "Ayesha Sheikh · sister", "2 sessions", "12 Oct 2026", ui.pill("ok", "Completed"), "OTP by owner 11:02 · new card PKG-0162-T"],
      ["PKG-0211", "Rakesh Menon", "Latha Menon · wife", "2 sessions", "17 Oct 2026", ui.pill("warn", "Awaiting recipient", "hourglass"), "OTP approved · Latha accepts on her phone"],
      ["PKG-0193", "Suresh Pillai", "—", "—", "—", ui.pill("mute", "0 of 1 used"), "Transfer still available"]
    ];
    var h = ui.card("Transfers & gifts", ui.table([{ h: "Package" }, { h: "From" }, { h: "To (family-linked)" }, { h: "Moved" }, { h: "Date" }, { h: "Status" }, { h: "Audit" }], tr.map(function (r) {
      return ['<span class="ltr">' + r[0] + "</span>", ui.esc(r[1]), ui.esc(r[2]), r[3], '<span class="small">' + r[4] + "</span>", r[5], '<span class="small faint">' + ui.esc(r[6]) + "</span>"]; }), { min: 860 }) +
      '<p class="small faint" style="margin:10px 0 0">Max 1 transfer per package · only to a family-linked member or a gift recipient · the owner approves by OTP · expiry never resets · recipient gets their own card' + (m.id === "ksa" ? " (Arabic gift card available)" : "") + ".</p>", { icon: "share" });
    var pid = NB.v("blUpPk", "MEM-0041"), dir = NB.v("blUpDir", "up"), k = D.packages.find(function (x) { return x.id === pid; }), U = UPG[pid], tgt = U[dir];
    var paid = pkPaidV(k), unused = k.total - k.used, credit = Math.round(unused / k.total * k.price), open = k.price - paid, net = credit - open, diff = tgt[2] - net;
    var docs = { india: "GST credit note on the old invoice + new tax invoice", uae: "VAT credit note + new tax invoice", ksa: "ZATCA credit note referencing the original + new e-invoice with QR", qatar: "Credit note + new invoice (no VAT)" }[m.id];
    var calcH = '<div class="row" style="flex-wrap:wrap;gap:8px">' + ui.seg("blUpPk", Object.keys(UPG).map(function (id) { return [id, id]; }), "MEM-0041") + ui.seg("blUpDir", [["up", "Upgrade"], ["down", "Downgrade"]], "up") + "</div>" +
      '<p class="small muted" style="margin:10px 0">' + ui.esc(NB.pname(NB.pat(k.p))) + " · " + ui.esc(k.plan) + " → <b>" + ui.esc(tgt[0]) + "</b></p>" +
      '<div class="bl-sum"><div class="r"><span>Unused sessions</span><span class="num">' + unused + " / " + k.total + "</span></div>" +
      '<div class="r"><span>Credit = ' + unused + " ÷ " + k.total + " × package price " + fm(cv(k.price)).replace(/<[^>]+>/g, "") + "</span>" + fm(cv(credit), 2) + "</div>" +
      (open ? '<div class="r sub"><span>Less open instalments (cancelled, netted here)</span><span>−' + fm(cv(open), 2) + "</span></div>" : "") +
      '<div class="r"><span>New package · ' + tgt[1] + " sessions</span>" + fm(cv(tgt[2]), 2) + "</div>" +
      '<div class="r tot"><span>' + (diff >= 0 ? "Patient pays the difference" : "Refund due to patient") + "</span>" + fm(cv(Math.abs(diff)), 2) + "</div></div>" +
      ui.note("info", "Old instance closes as <b>Replaced</b> (history kept for audit); a new instance starts with its own validity. Documents: " + docs + "." + (diff < 0 ? " Downgrade refunds follow the package refund rules (no fee, refund to source)." : "") + " The patient sees this math on WhatsApp and approves before anything is charged.") +
      '<div class="btns" style="margin-top:12px">' + ui.btn("Send to patient for approval", { cls: "pri", act: "toast", arg: "Sent on WhatsApp · the patient approves with a code", icon: "send" }) + "</div>";
    return h + '<div class="grid g2">' + ui.card("Upgrade / downgrade · pro-rated", calcH, { icon: "layers" }) +
      ui.card("How the credit works", '<div class="bl-ck">' + [["Credit = unused ÷ total × package price", "Unused sessions are valued at the package rate, so the patient keeps their discount on what they didn't use."], ["Open instalments are netted", "Unpaid instalments are cancelled and subtracted from the credit, so nobody pays twice or gets credit for money not paid."], ["Downgrade = refund of the difference", "If the new package costs less than the credit, the difference is refunded to source with a credit note."], ["Expiry", "The new package gets its own validity from today; a transfer never resets expiry."]].map(function (r) {
        return '<div class="ci">' + ui.icon("checkcircle", "sm okc") + '<div class="t"><b>' + r[0] + "</b><small>" + r[1] + "</small></div></div>"; }).join("") + "</div>", { icon: "info" }) + "</div>";
  }

  /* ---- Per-component tax split (FRD PKG-09, PT-02, WE-01 / WE-02) ---- */
  function pkTaxTab(m) {
    var ksa = m.id === "ksa", raw = ksa;
    // [name, cat, qty, unit list price (tax-inclusive), type]
    var P = ksa ? { name: "Clean & Bright combo (dental)", price: 1150, comps: [["Scaling & polishing", "svc", 1, 300, "Service"], ["Whitening session", "cos", 1, 900, "Service"], ["Take-home whitening kit", "otc", 1, 250, "Product"]] }
      : { name: "Acne Clear package (dermatology)", price: 12999, comps: [["Acne treatment session", "svc", 6, 2000, "Service"], ["Chemical peel", "cos", 2, 1500, "Service"], ["Medicated cream", "otc", 1, 800, "Product"]] };
    var C = function (v) { return raw ? v : cv(v); }, price = C(P.price), sum = P.comps.reduce(function (a, x) { return a + C(x[3]) * x[2]; }, 0), left = price, tot = { tax: 0, net: 0 };
    // Tax is rounded once on the whole bundle; the rounding difference lands on the last taxed line, so totals match the e-invoice.
    var exact = P.comps.reduce(function (a, x) { var rt = taxOf({ cat: x[1], r: x[3] }, {}).rate; return a + price * (C(x[3]) * x[2] / sum) * rt / (100 + rt); }, 0), taxLeft = r2(exact);
    var lastTaxed = P.comps.reduce(function (a, x, i) { return taxOf({ cat: x[1], r: x[3] }, {}).rate ? i : a; }, -1);
    var rows = P.comps.map(function (x, i) {
      var list = r2(C(x[3]) * x[2]), share = list / sum, alloc = i === P.comps.length - 1 ? r2(left) : r2(price * share); left = r2(left - alloc);
      var tr = taxOf({ cat: x[1], r: x[3] }, {}), tax = i === lastTaxed ? r2(taxLeft) : r2(alloc - alloc / (1 + tr.rate / 100)), net = r2(alloc - tax); taxLeft = r2(taxLeft - tax); tot.tax += tax; tot.net += net;
      return [ui.cellp(ui.esc(x[0]), m.id === "india" ? (x[4] === "Product" ? "HSN 3004" : x[1] === "cos" ? "SAC 999722" : "SAC 999312") : ui.esc(x[4])), ui.pill(x[4] === "Product" ? "sub" : "info", x[4], x[4] === "Product" ? "box" : "steth"), '<span class="num">' + x[2] + "</span>", fm(list, 2), '<span class="num">' + (share * 100).toFixed(1) + "%</span>", fm(alloc, 2),
        tr.rate ? '<span class="num">' + tr.rate + "%</span>" : '<span class="small faint">' + ui.esc(tr.lbl || "0%") + "</span>", fm(net, 2), fm(tax, 2)];
    });
    rows.push({ cls: "sel", cells: ["<b>Package total</b>", "", "", fm(sum, 2), '<span class="num">100%</span>', "<b>" + fm(price, 2) + "</b>", "", "<b>" + fm(r2(tot.net), 2) + "</b>", "<b>" + fm(r2(tot.tax), 2) + "</b>"] });
    var save = sum - price;
    var h = ui.card(ui.esc(P.name) + " · " + (ksa ? "VAT-inclusive (KSA)" : m.id === "india" ? "GST-inclusive" : m.id === "uae" ? "VAT-inclusive" : "no VAT"),
      '<p class="small muted" style="margin:0 0 12px">Bought separately <span class="bl-strike">' + fm(sum) + "</span> → package " + fm(price) + " · you save " + fm(save) + " (" + Math.round(100 * save / sum) + "%) — printed on the receipt too.</p>" +
      ui.table([{ h: "Component" }, { h: "Type" }, { h: "Qty" }, { h: "List value", cls: "r" }, { h: "Share", cls: "r" }, { h: "Allocated", cls: "r" }, { h: "Tax" }, { h: "Taxable value", cls: "r" }, { h: "Tax amount", cls: "r" }], rows, { min: 980 }) +
      '<p class="small faint" style="margin:10px 0 0">The bundle price is split across components at the list-price ratio; each line keeps its own tax tag. ' +
      (m.id === "india" ? "Clinical services stay GST-exempt; the cosmetic peel (18%) and the cream (product, 5%) carry GST on their own lines." : ksa ? "All components carry 15% VAT: ZATCA e-invoice shows net " + fm(r2(tot.net), 2).replace(/<[^>]+>/g, "") + " + VAT " + fm(r2(tot.tax), 2).replace(/<[^>]+>/g, "") + " with QR." : m.id === "uae" ? "Qualifying healthcare is zero-rated; the cosmetic service and the product carry 5% VAT." : "No VAT in Qatar; lines are still split for reporting.") + "</p>", { icon: "receipt" });
    h += '<div class="grid g2">' + ui.card("On refund or upgrade", '<div class="bl-ck">' + [["Used components at list price", "A used peel is valued at its own list price, not the bundle rate."], ["Tax reversed line by line", "The credit note reverses tax on each returned component at that line's rate."], ["Products handed over are used", "A take-home product counts as consumed once handed over at the desk."]].map(function (r) {
      return '<div class="ci">' + ui.icon("checkcircle", "sm okc") + '<div class="t"><b>' + r[0] + "</b><small>" + r[1] + "</small></div></div>"; }).join("") + "</div>", { icon: "refresh" }) +
      ui.card("Price floor", '<p class="small muted" style="margin:0">The owner can override a package price, but never below the floor (' + fm(Math.round(price * 0.85)) + " for this package). Overrides below it are blocked with a clear message.</p>", { icon: "lock" }) + "</div>";
    return h;
  }

  /* ---- Overlays: payment plan, sell package, refund preview ---- */
  NB.overlay("pkplan", function (id) {
    var k = D.packages.find(function (p) { return p.id === id; }) || D.packages[0], x = pkx(k), P = NB.pat(k.p), ovr = NB.v("blOvr_" + k.id, false);
    var body = '<div class="row sb">' + ui.cellp(ui.esc(NB.pname(P)) + " · " + ui.esc(k.id), ui.esc(k.plan)) + ui.pill(PKS[k.st][0], PKS[k.st][1], PKS[k.st][2]) + "</div>";
    if (x.plan === "inst") {
      var fails = x.inst.filter(function (i) { return i[2] === "failed"; }).length;
      if (fails >= 2) body += ui.note(ovr ? "ok" : "crit", ovr ? "<b>Owner override active.</b> One session may be booked while the patient settles. Logged with reason." : "<b>Payment default.</b> 2 instalments failed, so new session bookings are blocked. The patient can pay early from WhatsApp, or the owner can override with a reason.");
      body += ui.table([{ h: "#" }, { h: "Due" }, { h: "Amount", cls: "r" }, { h: "Collection" }, { h: "Status" }], x.inst.map(function (i, n) { return ['<span class="num">' + (n + 1) + "</span>", i[1], fm(cv(i[0])), '<span class="small">' + ui.esc(i[3] || autoName()) + "</span>", instPill(i[2])]; }), { min: 520 }) +
        '<div class="bl-sum"><div class="r"><span>Paid so far</span>' + fm(cv(pkPaidV(k)), 2) + '</div><div class="r"><span>Still to collect</span>' + fm(cv(k.price - pkPaidV(k)), 2) + "</div></div>" +
        ui.note("info", "Collected by " + autoName() + ". Reminders on WhatsApp 3 days before and on the day; SMS fallback if WhatsApp isn't delivered.");
    } else if (x.plan === "pps") body += ui.note("info", "Pay per session: " + fm(cv(x.per)).replace(/<[^>]+>/g, "") + " at each visit instead of " + fm(cv(k.single)).replace(/<[^>]+>/g, "") + ". Discount locked while all " + k.total + " sessions are completed; no clawback if the patient stops early.");
    else body += ui.note("ok", "Paid in full at sale" + (x.moved ? ". " + x.moved.n + " sessions transferred to " + x.moved.to + " (" + x.moved.rel + ") on " + x.moved.on + "; expiry unchanged." : "."));
    return { kind: "drawer", title: "Payment plan · " + k.id, body: body, foot: ui.btn("Close", { act: "close", cls: "ghost" }) +
      (x.plan === "inst" && x.inst.filter(function (i) { return i[2] === "failed"; }).length >= 2 && !ovr ? ui.btn("Owner override (1 session)", { act: "blovr", arg: k.id, icon: "unlock" }) : "") +
      (x.plan === "inst" ? ui.btn("Send pay link", { cls: "pri", act: "toast", arg: "Pay link sent on WhatsApp", icon: "send" }) : "") };
  });
  NB.act("blovr", function (id) { NB.state.ui["blOvr_" + id] = true; NB.toast("Override logged by Dr. Arvind Rao · reason: patient paying at next visit"); });

  NB.overlay("pksell", function () {
    var m = NB.mkt(), plan = NB.v("blSellPlan", "inst"), n = +NB.v("blSellN", "4"), price = 18000, list = 21000, floor = 15300;
    var amt = Math.floor(price / n), last = price - amt * (n - 1), days = [0, 30, 60, 90];
    var dates = ["17 Oct 2026", "16 Nov 2026", "16 Dec 2026", "15 Jan 2027"];
    var body = ui.field("Package", "Laser hair reduction · 6 sessions", { id: "pks-p", options: ["Laser hair reduction · 6 sessions", "Melasma peel series · 4 sessions", "Physiotherapy · 10 sessions", "Diabetes care plan · 12 months"] }) +
      '<p class="small muted" style="margin:0">Bought separately <span class="bl-strike">' + fm(cv(list)) + "</span> → package " + fm(cv(price)) + " · save " + Math.round(100 * (list - price) / list) + "% · floor " + fm(cv(floor)) + " (overrides below are blocked)</p>" +
      '<span class="small muted">How will the patient pay?</span><div class="stack s8">' + ui.radio("blSellPlan", "full", "Full payment now", "Package activates only when the payment succeeds", "inst") +
      ui.radio("blSellPlan", "inst", "Instalments (2–4)", "Fixed dates, auto-collected by " + autoName() + ", schedule shown before the patient agrees", "inst") +
      ui.radio("blSellPlan", "pps", "Pay per session", "Session price " + fm(cv(3000)).replace(/<[^>]+>/g, "") + " instead of " + fm(cv(3500)).replace(/<[^>]+>/g, "") + "; discount locked while all sessions are completed", "inst") + "</div>";
    if (plan === "inst") body += ui.seg("blSellN", [["2", "2 instalments"], ["3", "3 instalments"], ["4", "4 instalments"]], "4") +
      ui.table([{ h: "#" }, { h: "Day" }, { h: "Date" }, { h: "Amount", cls: "r" }], days.slice(0, n).map(function (d, i) { return ['<span class="num">' + (i + 1) + "</span>", "Day " + d, dates[i], fm(cv(i === n - 1 ? last : amt), 2)]; }), { min: 420 }) +
      ui.note("info", "This schedule goes to the patient on WhatsApp before they agree. Reminders 3 days before each date" + (m.id === "india" ? "; RBI pre-debit notice 24 h before each AutoPay debit" : "") + ". 2 failed instalments block new bookings (owner can override). Clinic credit — no lender.");
    return { kind: "drawer", title: L("Sell package", "بيع باقة", "पैकेज बेचें"), body: body,
      foot: ui.btn("Cancel", { act: "close", cls: "ghost" }) + ui.btn(plan === "full" ? "Take payment" : plan === "inst" ? "Send schedule for approval" : "Create package", { cls: "pri", act: "toast", arg: plan === "inst" ? "Schedule sent on WhatsApp · package activates after the first instalment" : "Package created · digital card sent on WhatsApp", icon: "send" }) };
  });

  NB.overlay("pkrefund", function (id) {
    var m = NB.mkt(), k = D.packages.find(function (p) { return p.id === id; }) || D.packages[0], P = NB.pat(k.p), req = NB.v("blPkReq", {}), x = pkx(k);
    var pending = k.st === "refpend" || req[k.id];
    // FRD PKG-08 / PR-06: used sessions at the FULL single-session price; no cancellation or processing fee by default; tax reversed.
    var paid = pkPaidV(k), pps = x.plan === "pps";
    var usedVal = k.time ? Math.round(k.price * k.mUsed / 12) : pps ? paid : k.used * k.single, net = paid - usedVal, owes = net < 0;
    var tr = taxOf({ cat: k.cat, r: k.price }, {}), taxBack = owes ? 0 : cv(net) * tr.rate / 100;
    var openInst = x.plan === "inst" ? x.inst.filter(function (i) { return i[2] !== "paid"; }) : [];
    var body = '<div class="row sb">' + ui.cellp(ui.esc(NB.pname(P)) + " · " + ui.esc(k.id), ui.esc(k.plan)) + ui.pill(PKS[k.st][0], PKS[k.st][1], PKS[k.st][2]) + "</div>";
    if (pending) body += ui.note("warn", "<b>Refund " + (k.rf || "RF-0013") + " is waiting for approval.</b> Redemption is frozen on this package and a second refund cannot be requested. The amount below is recalculated at the moment of approval.");
    body += '<div class="bl-sum"><div class="r"><span>' + (x.plan === "full" ? "Package price (taxable value)" : "Paid so far (taxable value)") + "</span>" + fm(cv(paid), 2) + "</div>" +
      '<div class="r sub"><span>' + (k.time ? "Time used · " + k.mUsed + " of 12 months" : pps ? "Sessions used · " + k.used + " paid per visit (no clawback)" : "Sessions used · " + k.used + " × single-session price " + fm(cv(k.single)).replace(/<[^>]+>/g, "")) + "</span><span>−" + fm(cv(usedVal), 2) + "</span></div>" +
      '<div class="r sub"><span>Processing fee</span><span>None (clinic policy)</span></div>' +
      '<div class="r"><span>' + (owes ? "Shortfall" : "Refundable value") + "</span>" + fm(cv(Math.abs(net)), 2) + "</div>" +
      (owes ? "" : tr.rate ? '<div class="r sub"><span>' + (m.id === "india" ? "GST" : "VAT") + " " + tr.rate + "% reversed on credit note</span>" + fm(r2(taxBack), 2) + "</div>" : '<div class="r sub"><span>' + ui.esc(tr.lbl || "No tax") + "</span>" + fm(0, 2) + "</div>") +
      '<div class="r tot"><span>' + (owes ? "Patient owes" : "Refund to patient") + "</span>" + fm(r2(cv(Math.abs(net)) + taxBack), 2) + "</div></div>" +
      (openInst.length ? ui.note(owes ? "warn" : "info", openInst.length + " open instalment" + (openInst.length > 1 ? "s" : "") + " (" + fm(cv(openInst.reduce(function (a, i) { return a + i[0]; }, 0))).replace(/<[^>]+>/g, "") + ") will be cancelled and the " + autoName() + " stopped." + (owes ? " The package closes only when the shortfall is settled or written off by a manager with a reason code." : "")) : "") +
      '<div class="fgrid">' + ui.field("Reason", "Patient relocating", { id: "pk-r", options: ["Patient relocating", "Medical reason (doctor's note)", "Dissatisfied with results", "Duplicate purchase"] }) + ui.field("Refund to", "Original method (" + m1Label() + ")", { id: "pk-t", options: ["Original method (" + m1Label() + ")", "Patient wallet (credit)"] }) + "</div>" +
      ui.note("info", "Unused liability on this package today: <b>" + NB.money(pkLiab(k)).replace(/<[^>]+>/g, "") + "</b>. The difference between liability and refund is recognised as revenue when the credit note is issued.");
    return { kind: "drawer", title: L("Package refund preview", "معاينة استرداد الباقة", "पैकेज रिफ़ंड पूर्वावलोकन"), body: body,
      foot: ui.btn("Close", { act: "close", cls: "ghost" }) + ui.btn(pending ? "Refund already requested" : owes ? "Request settlement (owner approves)" : "Request refund (owner approves)", { cls: "pri", act: "blpkrefund", arg: k.id, icon: "send", disabled: !!pending }) };
  });
  NB.act("blpkrefund", function (id) {
    var req = Object.assign({}, NB.v("blPkReq", {})), k = D.packages.find(function (p) { return p.id === id; });
    if (req[id] || (k && k.st === "refpend")) { NB.state.overlay = null; NB.toast("Blocked · a refund is already pending for " + id); return; }
    req[id] = true; NB.state.ui.blPkReq = req; NB.state.overlay = null; NB.toast("Refund RF-0013 requested · redemption frozen on " + id + " until approval");
  });

  /* ================= billing.dayclose ================= */
  var DEN = { india: [500, 200, 100, 50, 20, 10, 5, 2, 1], uae: [1000, 500, 200, 100, 50, 20, 10, 5, 1], ksa: [500, 200, 100, 50, 20, 10, 5, 1], qatar: [500, 200, 100, 50, 10, 5, 1] };
  var TOL = { india: 100, uae: 10, ksa: 10, qatar: 10 };
  function denCounts() {
    var k = mi(), saved = NB.v("blDen", {})[k]; if (saved) return saved;
    var d = D.day, target = Math.round(cv(d.float + d.cashIn - d.cashRefund - d.petty - d.short)), out = {};
    var share = [0.42, 0.26, 0.15, 0.08, 0.05, 0.03, 0.005, 0.002, 0.001];  // a realistic drawer mix, then greedy for the rest
    DEN[k].forEach(function (v, i) { out[v] = Math.floor(target * (share[i] || 0) / v); });
    var left = target - DEN[k].reduce(function (a, v) { return a + v * out[v]; }, 0);
    DEN[k].forEach(function (v) { var n = Math.floor(left / v); out[v] += n; left -= n * v; });
    return out;
  }
  NB.screen("billing.dayclose", function (c) {
    var m = c.m, d = D.day, locked = NB.v("blLocked"), counts = denCounts();
    var expected = cv(d.float + d.cashIn - d.cashRefund - d.petty);
    var counted = DEN[m.id].reduce(function (a, v) { return a + v * (counts[v] || 0); }, 0);
    var variance = r2(counted - expected), tol = TOL[m.id], over = Math.abs(variance) > tol, why = NB.v("blVarWhy", "");
    var pendingOk = NB.v("blCarry") || NB.v("blDone_T-18");
    var tot = d.modes.reduce(function (a, x) { return a + x[2]; }, 0);
    var h = ui.header(L("Day close", "إغلاق اليوم", "दिन समापन"), "Sat, 17 Oct 2026 · " + ui.esc(c.fac.name) + " · cashier Pooja Nair",
      locked ? ui.pill("perm", L("Day locked", "اليوم مقفل", "दिन लॉक"), "lock") : ui.pill("ok", L("Day open", "اليوم مفتوح", "दिन खुला"), "unlock"));
    h += '<div class="grid g4 kpis">' + ui.kpi(NB.money(tot), L("Collected today", "المحصل اليوم", "आज प्राप्त"), d.modes.reduce(function (a, x) { return a + x[1]; }, 0) + " payments", "faint") +
      ui.kpi(NB.money(d.ins), L("Insurance receivable", "مستحق التأمين", "बीमा प्राप्य"), d.insN + " claims · not cash", "faint") + ui.kpi(NB.money(d.disc), L("Discounts given", "الخصومات", "छूट दी"), d.discN + " · all within limits", "faint") +
      ui.kpi(fm(variance), L("Cash variance", "فرق النقد", "नकद अंतर"), over ? (why ? "Reason: " + ui.esc(why) : "Above tolerance " + fm(tol).replace(/<[^>]+>/g, "") + " · reason needed") : "Within tolerance " + fm(tol).replace(/<[^>]+>/g, ""), over ? "critc" : "okc") + "</div>";

    var checks = [
      ["ok", "All completed visits are billed", "0 visits without an invoice · a visit cannot complete without one"],
      pendingOk ? ["ok", "Open checkouts", "T-18 Fatima Sheikh " + (NB.v("blDone_T-18") ? "paid" : "moved to payment link (carried forward)")] : ["crit", "1 checkout still open", "T-18 Fatima Sheikh · finish it or send a payment link and carry it forward"],
      ["ok", m1Label() + " matched to gateway", "38 of 38 transactions auto-matched"],
      ["warn", "Card batch: 1 transaction settles tomorrow", fm(cv(2250)).replace(/<[^>]+>/g, "") + " · approval code 448120 · shown as in transit, not as a variance"],
      ["ok", "Refunds and credit notes", "0 today · day close shows refunds separately from sales"],
      ["ok", "IPD deposits reconciled", "2 deposits · " + fm(cv(20000)).replace(/<[^>]+>/g, "") + " held against running bills"]
    ];
    var s1 = ui.card("1 · " + L("Before you close", "قبل الإغلاق", "बंद करने से पहले"), '<div class="bl-ck">' + checks.map(function (x) {
      return '<div class="ci">' + ui.icon(x[0] === "ok" ? "checkcircle" : x[0] === "warn" ? "alertcircle" : "alertfill", "sm " + (x[0] === "ok" ? "okc" : x[0] === "warn" ? "warnc" : "critc")) + '<div class="t"><b>' + x[1] + "</b><small>" + x[2] + "</small></div>" +
        (x[0] === "crit" ? ui.btn("Send link & carry forward", { cls: "sm", act: "blcarry", icon: "link" }) : "") + "</div>"; }).join("") + "</div>", { icon: "clipboard" });

    var modeName = { m1: m1Label(), card: "Card", cash: "Cash", link: "Payment links", adv: "IPD advance deposits" };
    var s2 = ui.card("2 · " + L("Payment-mode reconciliation", "مطابقة طرق الدفع", "भुगतान-मोड मिलान"), ui.table([{ h: "Mode" }, { h: "Txns", cls: "r" }, { h: "System", cls: "r" }, { h: "Settled / counted", cls: "r" }, { h: "Difference", cls: "r" }, { h: "" }],
      d.modes.map(function (x) {
        var sys = cv(x[2]), got = x[0] === "cash" ? r2(counted - cv(d.float) + cv(d.petty)) : cv(x[3]), diff = r2(got - sys);
        var pill = x[0] === "card" ? ui.pill("info", "In transit", "clockh") : Math.abs(diff) < 0.01 ? ui.pill("ok", "Matched") : Math.abs(diff) <= tol ? ui.pill("ok", "Within tolerance") : ui.pill("crit", "Variance", "alertfill");
        return [ui.cellp(modeName[x[0]], x[0] === "m1" ? "Gateway settlement" : x[0] === "card" ? terminal() + " batch #2291" : x[0] === "cash" ? "Drawer count (step 3)" : x[0] === "link" ? "Gateway" : "UPI + cash at admission"),
          '<span class="num">' + x[1] + "</span>", fm(sys), fm(got), Math.abs(diff) < 0.01 ? '<span class="faint">—</span>' : '<b class="' + (x[0] === "card" ? "infoc" : "critc") + '">' + fm(diff) + "</b>", pill];
      }), { min: 640 }), { icon: "scale", cls: "flush" }).replace('<div class="ch">', '<div class="ch" style="padding:16px 20px 0">');

    var dens = DEN[m.id].map(function (v) {
      return '<div class="dr"><span><b class="num ltr">' + (m.id === "india" ? "₹" + v : v + " " + m.cur) + "</b> " + (v < (m.id === "india" ? 10 : 5) ? '<span class="faint small">coin</span>' : "") + "</span>" +
        '<span class="bl-step">' + (locked ? "" : '<button data-act="blden" data-arg="' + v + '|-1" aria-label="Fewer">−</button>') + "<span>" + (counts[v] || 0) + "</span>" + (locked ? "" : '<button data-act="blden" data-arg="' + v + '|1" aria-label="More">+</button>') + "</span>" +
        '<span style="text-align:end">' + fm(v * (counts[v] || 0)) + "</span></div>"; }).join("");
    var s3 = ui.card("3 · " + L("Cash drawer count", "عد صندوق النقد", "कैश ड्रॉअर गिनती"), '<div class="bl-den">' + dens + "</div>" +
      '<div class="bl-sum" style="margin-top:12px"><div class="r"><span>Opening float</span>' + fm(cv(d.float)) + '</div><div class="r"><span>+ Cash received</span>' + fm(cv(d.cashIn)) + '</div><div class="r"><span>− Cash refunds</span>' + fm(cv(d.cashRefund)) + "</div>" +
      '<div class="r"><span>− Petty cash (voucher PC-118, courier)</span>' + fm(cv(d.petty)) + '</div><div class="r" style="font-weight:600"><span style="color:var(--tx)">Expected in drawer</span>' + fm(expected) + "</div>" +
      '<div class="r" style="font-weight:600"><span style="color:var(--tx)">Counted</span>' + fm(counted) + '</div><div class="r tot"><span>Variance</span><span class="' + (over ? "critc" : "okc") + '">' + fm(variance) + "</span></div></div>", { icon: "banknote" });

    var lockBody;
    if (locked) lockBody = ui.note("ok", "<b>Day locked at 21:04 by Pooja Nair.</b> No payment, refund or invoice can be posted to 17 Oct any more. Summary sent to Dr. Arvind Rao on WhatsApp.", "lock") +
      '<div class="btns">' + ui.btn(L("Request re-open", "طلب إعادة الفتح", "फिर खोलने का अनुरोध"), { ov: "reopen", icon: "unlock" }) + ui.btn("Download Z-report", { act: "toast", arg: "Z-report PDF ready", icon: "download" }) + "</div>";
    else {
      lockBody = (over ? '<div class="field"><label for="bl-vw">Variance reason (required above tolerance)</label><select id="bl-vw" data-change="blVarWhy"><option value="">Choose a reason…</option>' +
        ["Change given short", "Counting error, recounted", "Unrecorded cash refund", "Petty cash without voucher", "Other (add note)"].map(function (o) { return "<option" + (o === why ? " selected" : "") + ">" + o + "</option>"; }).join("") + "</select></div>" : ui.note("ok", "Cash is within tolerance. No reason needed.")) +
        (!pendingOk ? ui.note("crit", "Close step 1 first: one checkout is still open.") : "") +
        '<div class="btns">' + ui.btn(L("Lock day", "قفل اليوم", "दिन लॉक करें"), { cls: "pri lg", act: "bllock", icon: "lock", disabled: !pendingOk || (over && !why) }) + "</div>" +
        '<p class="small faint" style="margin:0">Locking freezes 17 Oct. Re-opening needs owner approval, works only for today, and the day must be closed again before midnight.</p>';
    }
    var s4 = ui.card("4 · " + L("Variance & lock", "الفرق والقفل", "अंतर और लॉक"), '<div class="stack">' + lockBody + "</div>", { icon: "lock", cls: locked ? "" : "hl" });
    var msg = "<b>" + ui.esc(c.fac.name) + " · Day close 17 Oct</b>\nCollected " + NB.money(tot) + " (" + d.modes.reduce(function (a, x) { return a + x[1]; }, 0) + " payments)\n" +
      d.modes.map(function (x) { return modeName[x[0]] + " " + NB.money(x[2]); }).join(" · ") + "\nInsurance receivable " + NB.money(d.ins) + " (" + d.insN + " claims, same day)\nDiscounts " + NB.money(d.disc) + " · refunds " + NB.money(0) +
      "\nCash variance " + fm(variance) + (why ? " · " + ui.esc(why) : "") + "\n" + (locked ? "Locked by Pooja Nair 21:04" : "Not locked yet");
    var s5 = ui.card(L("Summary to owner on WhatsApp", "ملخص للمالك عبر واتساب", "मालिक को व्हाट्सऐप सारांश"), '<div class="bl-wa"><div class="bub out">' + msg + '<span class="ts">' + (locked ? "21:04 ✓✓" : "preview") + "</span></div></div>" +
      '<p class="small faint" style="margin:10px 0 0">Sent automatically when the day is locked. No patient names or health details in the message.</p>', { icon: "chat" });
    return h + '<div class="split wide"><div class="stack s16">' + s1 + s2 + s3 + '</div><div class="stack s16">' + s4 + s5 + "</div></div>";
  });
  NB.act("blden", function (a) {
    var p = String(a).split("|"), k = mi(), all = Object.assign({}, NB.v("blDen", {})), cur = Object.assign({}, denCounts());
    cur[p[0]] = Math.max(0, (cur[p[0]] || 0) + (+p[1])); all[k] = cur; NB.setUI({ blDen: all });
  });
  NB.act("blcarry", function () { NB.state.ui.blCarry = true; NB.toast("Payment link sent to Fatima Sheikh · checkout carried forward to tomorrow's dues"); });
  NB.act("bllock", function () { NB.state.ui.blLocked = true; NB.toast("Day locked · summary sent to Dr. Arvind Rao on WhatsApp"); });
  NB.overlay("reopen", function () {
    return { kind: "modal", title: L("Request re-open", "طلب إعادة فتح اليوم", "फिर खोलने का अनुरोध"), body:
      ui.note("warn", "Only today (17 Oct) can be re-opened. Every change while re-opened is logged, and the day must be closed again before midnight.") +
      ui.field("Reason", "Late card payment to post", { options: ["Late card payment to post", "Refund approved after close", "Correction to a payment mode"], full: true }) +
      ui.field("Approver", "Dr. Arvind Rao (owner)", { options: ["Dr. Arvind Rao (owner)"], full: true }),
      foot: ui.btn("Cancel", { act: "close", cls: "ghost" }) + ui.btn("Send for approval", { cls: "pri", act: "toast", arg: "Re-open request sent to Dr. Arvind Rao on WhatsApp", icon: "send" }) };
  });

  /* ---------------- Top-bar primary button ("New invoice") for this role ---------------- */
  document.addEventListener("click", function (ev) {
    var el = ev.target.closest && ev.target.closest('.top [data-act="primary"]');
    if (el && NB.state.role === "billing") { ev.stopPropagation(); NB.open("newinv"); }
  }, true);

  // Shared helpers other modules may use (e.g. IPD deposit and payer names).
  NB.billing = { calc: calc, payerName: payerName, fm: fm, cv: cv, channel: function () { return CH[mi()]; }, qr: qrSvg };
})();
