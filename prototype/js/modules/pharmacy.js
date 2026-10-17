/* Pharmacy module (role: pharmacist). Runs inside a clinic/hospital or as a standalone chemist shop
   (toggle stored in NB.v("phStandalone")). Screens: e-Rx queue, counter sale (POS), stock & expiry,
   purchases & GRN, statutory registers, sales & margins. Overlays: dispenserx, scanrx, attachrx, batch,
   phadjust, phexpret, grn, phreturn. All data is fictional and lives under NB.D.pharmacy. */
(function () {
  "use strict";
  var ui = NB.ui, L = NB.L, esc = ui.esc;

  /* ---------------- Styles (ph- prefix, nb.css variables only) ---------------- */
  (function () {
    if (document.getElementById("ph-css")) return;
    var s = document.createElement("style"); s.id = "ph-css";
    s.textContent = [
      ".ph-mode{display:flex;align-items:center;gap:12px 16px;flex-wrap:wrap;padding:12px 16px;border:1px solid var(--b-sub);border-radius:var(--r-lg);background:var(--sheen),var(--s1)}",
      ".ph-mode.sa{border-color:rgba(88,178,245,.35)}",
      ".ph-mi{width:36px;height:36px;border-radius:10px;display:grid;place-items:center;background:var(--acc-tint);color:var(--acc);flex:none}",
      ".ph-mode.sa .ph-mi{background:var(--info-t);color:var(--info)}",
      ".ph-mode .t{flex:1;min-width:220px}.ph-mode .t b{font-size:13.5px;font-weight:600}.ph-mode .t small{display:block;color:var(--tx3);font-size:12px;line-height:17px;margin-top:2px}",
      ".ph-sw{display:inline-flex;align-items:center;gap:10px;cursor:pointer;font-size:12.5px;font-weight:600;color:var(--tx2);padding:6px 10px;border-radius:999px;border:1px solid var(--b-def);background:var(--s2)}",
      ".ph-sw:hover{color:var(--tx)}",
      ".ph-pos{display:grid;grid-template-columns:minmax(0,1fr) 340px;gap:20px;align-items:start}",
      ".ph-tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px}",
      ".ph-tile{display:flex;flex-direction:column;gap:6px;text-align:start;padding:12px;border-radius:var(--r-md);border:1px solid var(--b-sub);background:var(--s2);cursor:pointer;min-width:0;min-height:96px}",
      ".ph-tile:hover{border-color:var(--acc-line)}.ph-tile b{font-size:13px;font-weight:500;line-height:17px}.ph-tile small{color:var(--tx3);font-size:11.5px}",
      ".ph-tile .row{margin-top:auto}.ph-tile .row b{white-space:nowrap}",
      ".ph-scan{display:flex;align-items:center;gap:10px;height:48px;padding:0 6px 0 14px;border-radius:var(--r-md);background:var(--s3);border:1px solid var(--acc-line);box-shadow:0 0 0 3px var(--acc-tint)}",
      "[dir=rtl] .ph-scan{padding:0 14px 0 6px}",
      ".ph-scan input{flex:1;min-width:0;background:transparent;border:0;outline:0;font-size:14px}",
      ".ph-cl{display:grid;grid-template-columns:minmax(0,1fr) auto auto;gap:6px 12px;align-items:center;padding:12px 0;border-bottom:1px solid var(--div)}",
      ".ph-cl:last-child{border-bottom:0}.ph-cl .nm b{display:block;font-size:13.5px;font-weight:500}.ph-cl .nm small{display:block;color:var(--tx3);font-size:11.5px}",
      ".ph-cl .bl{grid-column:1/-1}",
      ".ph-cl .ph-step{justify-self:start}.ph-step{display:inline-flex;align-items:center;border:1px solid var(--b-def);border-radius:10px;overflow:hidden;background:var(--s2)}",
      ".ph-step button{width:32px;height:32px;border:0;background:transparent;cursor:pointer;color:var(--tx2);font-size:16px}.ph-step button:hover{background:var(--s3);color:var(--tx)}",
      ".ph-step span{min-width:34px;text-align:center;font-weight:600;font-variant-numeric:tabular-nums}",
      ".ph-sum{display:grid;grid-template-columns:1fr auto;gap:6px 12px;font-size:13px}.ph-sum dt{color:var(--tx3)}.ph-sum dd{margin:0;text-align:end}",
      ".ph-sum .tot{font:600 22px/30px var(--font);color:var(--tx)}",
      ".ph-qr{background:var(--tx);border-radius:10px;padding:8px;display:inline-block;line-height:0}.ph-qr svg{display:block}.ph-qr rect{fill:var(--bg-root)}",
      ".ph-label{background:var(--tx);color:var(--bg-root);border-radius:10px;padding:12px 14px;font-size:12px;line-height:17px;min-width:0}",
      ".ph-label b{display:block;font-size:13px}.ph-label .ln{border-top:1px dashed rgba(0,0,0,.25);margin-top:6px;padding-top:6px}.ph-label .sig{font-size:14px;font-weight:600;margin:4px 0}",
      ".ph-label .mt{color:var(--tx4);font-size:11px}",
      ".ph-item{border:1px solid var(--b-sub);border-radius:var(--r-md);padding:12px 14px;background:var(--s2);display:flex;flex-direction:column;gap:10px;min-width:0}",
      ".ph-item.bad{border-color:rgba(255,77,79,.4)}",
      ".ph-item .hd{display:flex;gap:10px;align-items:flex-start}.ph-item .hd b{display:block;font-size:13.5px;font-weight:600}.ph-item .hd small{display:block;color:var(--tx3);font-size:12px}",
      ".ph-bt{display:flex;flex-wrap:wrap;gap:6px}",
      ".ph-bchip{display:inline-flex;gap:8px;align-items:center;font-size:12px;padding:5px 10px;border-radius:999px;background:var(--s3);border:1px solid var(--b-def)}",
      ".ph-bchip.pick{border-color:var(--acc-line);background:var(--acc-tint);color:var(--acc-300)}",
      ".ph-ck{display:flex;gap:10px;align-items:flex-start;padding:9px 0;border-bottom:1px solid var(--div);font-size:13px}.ph-ck:last-child{border-bottom:0}",
      ".ph-ck .ico{margin-top:1px}.ph-ck b{font-weight:500;display:block}.ph-ck small{color:var(--tx3);display:block;font-size:12px}",
      ".ph-photo{height:150px;border-radius:var(--r-md);border:1px dashed var(--b-str);background:repeating-linear-gradient(135deg,var(--s2) 0 10px,var(--s3) 10px 20px);display:grid;place-items:center;color:var(--tx3);text-align:center;font-size:12px}",
      ".ph-cam{position:relative;height:220px;border-radius:var(--r-lg);background:var(--sunk);display:grid;place-items:center;overflow:hidden;border:1px solid var(--b-def)}",
      ".ph-cam::before{content:'';position:absolute;width:150px;height:150px;border:2px solid var(--acc);border-radius:14px;box-shadow:0 0 0 999px rgba(0,0,0,.35)}",
      ".ph-chart{width:100%;height:auto;display:block}",
      ".ph-chart .bx{fill:var(--tx4)}.ph-chart .bh{fill:var(--acc)}.ph-chart text{fill:var(--tx3);font:500 10px var(--font)}.ph-chart .gl{stroke:var(--div)}",
      ".ph-flow{display:flex;gap:6px;flex-wrap:wrap;align-items:center;font-size:12px;color:var(--tx3)}",
      ".ph-flow .s{display:inline-flex;align-items:center;gap:6px;padding:5px 10px;border-radius:999px;border:1px solid var(--b-def)}",
      ".ph-flow .s.done{color:var(--ok);border-color:rgba(52,211,153,.38)}.ph-flow .s.cur{color:var(--acc);border-color:var(--acc-line);background:var(--acc-tint)}",
      ".ph-in{width:100%;min-width:72px;height:32px;border-radius:8px;border:1px solid var(--b-def);background:var(--s3);padding:0 8px;font-size:12.5px}",
      ".ph-in.bad{border-color:rgba(232,179,60,.6)}",
      ".ph-rxc{display:flex;flex-direction:column;gap:8px;padding:14px;border-radius:var(--r-lg);border:1px solid var(--b-sub);background:var(--s1);cursor:pointer}",
      ".ph-pay{display:flex;flex-direction:column;align-items:center;gap:8px;padding:14px;border-radius:var(--r-md);background:var(--s2);border:1px solid var(--b-sub);text-align:center}",
      ".frame.mobile .ph-pos{grid-template-columns:minmax(0,1fr)}",
      ".frame.mobile .ph-tiles{grid-template-columns:repeat(2,minmax(0,1fr))}",
      "@media (max-width:1180px){.ph-pos{grid-template-columns:minmax(0,1fr) 300px}.ph-tiles{grid-template-columns:repeat(auto-fill,minmax(128px,1fr))}.ph-cl .nm{grid-column:1/-1}}",
      "@media (max-width:760px){.ph-pos{grid-template-columns:minmax(0,1fr)}.ph-tiles{grid-template-columns:repeat(2,minmax(0,1fr))}.ph-mode .t{min-width:0;flex-basis:100%}}"
    ].join("\n");
    document.head.appendChild(s);
  })();

  /* ---------------- Dates & helpers ---------------- */
  var TODAY = new Date(2026, 9, 17);
  var MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  function pd(iso) { var p = iso.split("-"); return new Date(+p[0], +p[1] - 1, +p[2]); }
  function dl(iso) { return Math.round((pd(iso) - TODAY) / 864e5); }
  function fd(iso, my) { var d = pd(iso); return (my ? "" : d.getDate() + " ") + MON[d.getMonth()] + " " + d.getFullYear(); }
  function n(x) { return '<span class="num ltr">' + x + "</span>"; }
  function expPill(iso) {
    var d = dl(iso);
    if (d < 0) return ui.pill("crit", L("Expired", "منتهي", "एक्सपायर्ड"));
    if (d <= 30) return ui.pill("urg", d + " " + L("days left", "يوم متبقٍ", "दिन बाकी"), "hourglass");
    if (d <= 60) return ui.pill("warn", d + " " + L("days left", "يوم متبقٍ", "दिन बाकी"), "hourglass");
    if (d <= 90) return ui.pill("info", d + " " + L("days left", "يوم متبقٍ", "दिन बाकी"), "hourglass");
    return '<span class="small faint">' + L("OK", "سليم", "ठीक") + "</span>";
  }
  function india() { return NB.state.market === "india"; }
  function standalone() { return !!NB.v("phStandalone", false); }
  function money(v) { return NB.money(v); }
  function fmt(v) { return NB.money(v); }
  // Unit prices and purchase rates need paise / fils.
  function fmt2(v) { var m = NB.mkt(), x = v * m.fx, t = x.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 }); return '<span class="num ltr">' + (m.id === "india" ? "₹" + t : t + " " + m.cur) + "</span>"; }
  function packPrice(id) { var d = drug(id), per = d.unit === "tab" || d.unit === "cap" ? 10 : 1; return fmt(mrpOf(id) * per) + (per > 1 ? '<span class="faint"> / 10</span>' : ""); }
  function pct(v) { return '<span class="num ltr">' + v + "%</span>"; }

  /* ---------------- Data (NB.D.pharmacy) ---------------- */
  var P = NB.D.pharmacy = {
    // Extra items beyond the shared drug master. cat drives margin reports; gst is the India HSN rate.
    items: [
      { id: "x1", name: "ORS powder 21 g sachet", gen: "Oral rehydration salts", cls: "Electrolyte", sched: "OTC", price: 20, unit: "sachet", cat: "otc", hsn: "3004", bc: "8901030712345" },
      { id: "x2", name: "Vitamin D3 60,000 IU capsule", gen: "Cholecalciferol", cls: "Vitamin", sched: "OTC", price: 27, unit: "cap", cat: "otc", hsn: "3004" },
      { id: "x3", name: "Glucometer test strips (50)", gen: "Blood glucose strips", cls: "Device", sched: "OTC", price: 890, unit: "pack", cat: "dev", hsn: "3822", bc: "8906001234567" },
      { id: "x4", name: "Pantoprazole 40 mg tablet", gen: "Pantoprazole", cls: "PPI", sched: "H", price: 7.4, unit: "tab", cat: "acute", hsn: "3004" },
      { id: "x5", name: "Insulin glargine 100 IU/ml pen", gen: "Insulin glargine", cls: "Insulin", sched: "H", price: 720, unit: "pen", cat: "chronic", hsn: "3004", cold: true },
      { id: "x6", name: "Tramadol 50 mg capsule", gen: "Tramadol", cls: "Opioid analgesic", sched: "H1", price: 5.9, unit: "cap", cat: "ctrl", hsn: "3004", ctrl: true },
      { id: "x7", name: "Codeine + chlorphen. syrup 100 ml", gen: "Codeine phosphate", cls: "Opioid antitussive", sched: "H1", price: 108, unit: "btl", cat: "ctrl", hsn: "3004", ctrl: true },
      { id: "x8", name: "Morphine sulphate 10 mg tablet", gen: "Morphine", cls: "Opioid", sched: "NDPS", price: 11, unit: "tab", cat: "ctrl", hsn: "3004", ctrl: true },
      { id: "x9", name: "Sunscreen SPF 50 gel 50 g", gen: "Sunscreen", cls: "Cosmetic", sched: "OTC", price: 495, unit: "tube", cat: "derma", hsn: "3304", gst: 18 },
      { id: "x10", name: "Paracetamol 250 mg/5 ml syrup 60 ml", gen: "Paracetamol", cls: "Analgesic", sched: "OTC", price: 34, unit: "btl", cat: "otc", hsn: "3004" },
      { id: "x11", name: "Amoxicillin 250 mg/5 ml susp. 60 ml", gen: "Amoxicillin", cls: "Penicillin", sched: "H", price: 58, unit: "btl", cat: "acute", hsn: "3004" },
      { id: "x12", name: "Montelukast 10 mg tablet", gen: "Montelukast", cls: "LTRA", sched: "H", price: 9.4, unit: "tab", cat: "chronic", hsn: "3004" },
      { id: "x13", name: "Dextromethorphan syrup 100 ml", gen: "Dextromethorphan", cls: "Antitussive", sched: "OTC", price: 89, unit: "btl", cat: "otc", hsn: "3004" },
      { id: "x14", name: "Hand sanitiser 500 ml", gen: "Ethanol 70%", cls: "Hygiene", sched: "OTC", price: 225, unit: "btl", cat: "otc", hsn: "3808", gst: 18 },
      { id: "x16", name: "Atorvastatin 10 mg tablet (generic)", gen: "Atorvastatin", cls: "Statin", sched: "H", price: 1.9, unit: "tab", cat: "chronic", hsn: "3004" }
    ],
    cat: { m1: "acute", m2: "acute", m3: "otc", m4: "chronic", m5: "chronic", m6: "derma", m7: "otc", m8: "chronic", m9: "otc", m10: "ctrl", m11: "chronic", m12: "chronic" },
    batches: [
      { drug: "m6", batch: "ISO2507", exp: "2027-06-30", qty: 60, loc: "H1-02", mrp: 42 },
      { drug: "m2", batch: "AZI2505", exp: "2027-04-30", qty: 45, loc: "A-04", mrp: 23.5 },
      { drug: "m5", batch: "AML2503", exp: "2027-03-31", qty: 150, loc: "C-01", mrp: 3.6 },
      { drug: "m7", batch: "CTZ2411", exp: "2026-12-31", qty: 200, loc: "B-03", mrp: 1.8 },
      { drug: "m8", batch: "THY2506", exp: "2027-05-31", qty: 30, loc: "C-03", mrp: 2.2 },
      { drug: "m9", batch: "IBU2502", exp: "2027-01-31", qty: 140, loc: "B-02", mrp: 2.7 },
      { drug: "x16", batch: "ATG2506", exp: "2027-08-31", qty: 300, loc: "C-05", mrp: 2.1 },
      { drug: "x1", batch: "ORS2509", exp: "2028-03-31", qty: 180, loc: "B-05", mrp: 22 },
      { drug: "x2", batch: "VD32504", exp: "2027-04-30", qty: 40, loc: "W-01", mrp: 30 },
      { drug: "x3", batch: "GLS2507", exp: "2027-07-31", qty: 12, loc: "D-01", mrp: 950 },
      { drug: "x4", batch: "PAN2412", exp: "2026-11-15", qty: 90, loc: "A-06", mrp: 8.2 },
      { drug: "x5", batch: "INS2510", exp: "2027-04-30", qty: 6, loc: "FRIDGE-1", mrp: 780 },
      { drug: "x5", batch: "INS2409", exp: "2026-10-10", qty: 2, loc: "FRIDGE-1", mrp: 780 },
      { drug: "x6", batch: "TRM2502", exp: "2027-02-28", qty: 40, loc: "H1-03", mrp: 6.6 },
      { drug: "x7", batch: "COD2501", exp: "2027-01-31", qty: 14, loc: "H1-04", mrp: 118 },
      { drug: "x8", batch: "MOR2503", exp: "2027-03-31", qty: 50, loc: "CD-SAFE", mrp: 12 },
      { drug: "x9", batch: "SUN2505", exp: "2027-05-31", qty: 8, loc: "W-04", mrp: 545 },
      { drug: "x10", batch: "PCS2508", exp: "2027-08-31", qty: 35, loc: "B-06", mrp: 38 },
      { drug: "x11", batch: "AMS2502", exp: "2026-12-20", qty: 18, loc: "A-05", mrp: 64 },
      { drug: "x12", batch: "MON2504", exp: "2027-04-30", qty: 6, loc: "A-07", mrp: 10.5 },
      { drug: "x13", batch: "DXM2403", exp: "2026-09-30", qty: 12, loc: "B-07", mrp: 98 },
      { drug: "x14", batch: "HSN2406", exp: "2027-06-30", qty: 24, loc: "W-02", mrp: 250 }
    ],
    reorder: { m1: 150, m2: 40, m3: 300, m4: 200, m5: 100, m6: 30, m7: 100, m8: 100, m9: 100, m10: 30, m11: 20, m12: 120, x1: 100, x2: 20, x3: 10, x4: 60, x5: 10, x6: 20, x7: 10, x8: 20, x9: 5, x10: 20, x11: 15, x12: 30, x13: 10, x14: 10, x16: 100 },
    velocity: { m1: 6.2, m2: 2.4, m3: 31, m4: 9.5, m5: 5.1, m6: 1.1, m7: 7.4, m8: 3.2, m9: 4.8, m10: 0.9, m11: 0.8, m12: 4.4, x1: 6.5, x2: 0.4, x3: 0.6, x4: 3.9, x5: 0.6, x6: 0.5, x7: 0.2, x8: 0.7, x9: 0.05, x10: 1.6, x11: 0.9, x12: 1.4, x13: 0, x14: 0.02, x16: 6.8 },
    lastSold: { x9: 112, x14: 140, x2: 96, x13: 61 },
    generic: { m12: "x16" },
    loyalty: { p1: 420, p3: 1180, p8: 640, p9: 210 }
  };

  // Label sig text in the patient's language.
  var SIG = {
    bd: ["1-0-1", "1 tablet twice a day, after food", "قرص واحد مرتين يومياً بعد الأكل", "1 गोली दिन में दो बार, खाने के बाद"],
    odn: ["0-0-1", "1 tablet at night", "قرص واحد ليلاً", "1 गोली रात को"],
    odm: ["1-0-0", "1 tablet every morning, after breakfast", "قرص واحد كل صباح بعد الإفطار", "1 गोली रोज़ सुबह, नाश्ते के बाद"],
    odd: ["0-0-1", "1 capsule daily with dinner", "كبسولة واحدة يومياً مع العشاء", "1 कैप्सूल रोज़ रात के खाने के साथ"],
    sos: ["SOS", "1 tablet if fever, max 4 a day, 6 h apart", "قرص عند الحمّى، 4 كحد أقصى يومياً، بفاصل 6 ساعات", "बुख़ार हो तो 1 गोली, दिन में अधिकतम 4, 6 घंटे के अंतर पर"],
    tds: ["1-1-1", "1 capsule three times a day, after food", "كبسولة ثلاث مرات يومياً بعد الأكل", "1 कैप्सूल दिन में तीन बार, खाने के बाद"],
    ac: ["1-0-0", "1 tablet on an empty stomach, 30 min before breakfast", "قرص على معدة فارغة قبل الإفطار بـ30 دقيقة", "1 गोली ख़ाली पेट, नाश्ते से 30 मिनट पहले"],
    syr: ["5 ml × 3", "5 ml three times a day, after food", "5 مل ثلاث مرات يومياً بعد الأكل", "5 ml दिन में तीन बार, खाने के बाद"],
    syrsos: ["5 ml SOS", "5 ml if fever, max 4 doses a day", "5 مل عند الحمّى، 4 جرعات كحد أقصى يومياً", "बुख़ार हो तो 5 ml, दिन में अधिकतम 4 बार"],
    inh: ["2 puffs SOS", "2 puffs when breathless, max 8 puffs a day", "بختان عند ضيق النفس، 8 بخات كحد أقصى يومياً", "साँस फूलने पर 2 पफ़, दिन में अधिकतम 8"],
    app: ["Apply", "Apply every morning, 15 min before going out", "يوضع كل صباح قبل الخروج بـ15 دقيقة", "हर सुबह बाहर जाने से 15 मिनट पहले लगाएँ"]
  };
  // Counselling points by generic, in en / ar / hi.
  var COUNSEL = {
    Isotretinoin: ["Avoid pregnancy: use contraception during treatment and for 1 month after. Do not donate blood. Lips and skin get dry; use the sunscreen.", "تجنّبي الحمل: استخدمي وسيلة منع حمل أثناء العلاج ولمدة شهر بعده. لا تتبرعي بالدم. استخدمي واقي الشمس.", "गर्भधारण से बचें: इलाज के दौरान और 1 महीने बाद तक गर्भनिरोधक लें। रक्तदान न करें। सनस्क्रीन लगाएँ।"],
    Metformin: ["Take with food. Tell your doctor if you have persistent vomiting or stomach pain.", "يؤخذ مع الطعام. أخبر طبيبك إذا استمر القيء أو ألم المعدة.", "खाने के साथ लें। लगातार उल्टी या पेट दर्द हो तो डॉक्टर को बताएँ।"],
    Azithromycin: ["Finish all 3 days even if you feel better.", "أكمل الأيام الثلاثة حتى لو شعرت بتحسن.", "ठीक लगे तब भी पूरे 3 दिन दवा लें।"],
    Amoxicillin: ["Finish the full course. Stop and get help at once if a rash or breathing difficulty appears.", "أكمل العلاج كاملاً. توقف واطلب المساعدة فوراً عند ظهور طفح أو ضيق تنفس.", "पूरा कोर्स करें। चकत्ते या साँस में तकलीफ़ हो तो तुरंत दवा रोकें और मदद लें।"],
    Paracetamol: ["Do not take with other paracetamol products. No more than the dose on the label.", "لا تتناوله مع منتجات أخرى تحتوي على الباراسيتامول.", "पैरासिटामोल वाली दूसरी दवा साथ में न लें।"],
    Levothyroxine: ["Empty stomach, 30 min before breakfast. Keep 4 hours away from calcium or iron.", "على معدة فارغة قبل الإفطار بـ30 دقيقة. بعيداً عن الكالسيوم والحديد 4 ساعات.", "ख़ाली पेट, नाश्ते से 30 मिनट पहले। कैल्शियम या आयरन से 4 घंटे दूर रखें।"],
    Atorvastatin: ["Report unexplained muscle pain or weakness.", "أبلغ عن أي ألم عضلي غير مبرر.", "बिना कारण मांसपेशियों में दर्द हो तो बताएँ।"],
    Amlodipine: ["May cause ankle swelling. Do not stop suddenly.", "قد يسبب تورم الكاحل. لا تتوقف فجأة.", "टखनों में सूजन हो सकती है। अचानक बंद न करें।"],
    Pantoprazole: ["Take 30 minutes before breakfast.", "يؤخذ قبل الإفطار بـ30 دقيقة.", "नाश्ते से 30 मिनट पहले लें।"],
    Ibuprofen: ["Take after food. Avoid if you have stomach ulcers or kidney disease.", "بعد الأكل. تجنّبه عند وجود قرحة أو مرض كلوي.", "खाने के बाद लें। अल्सर या किडनी रोग हो तो न लें।"],
    Sunscreen: ["Reapply every 3 hours outdoors.", "أعد وضعه كل 3 ساعات في الخارج.", "बाहर हों तो हर 3 घंटे में दोबारा लगाएँ।"]
  };

  function drug(id) { var d = NB.drug(id); return d && d.id ? d : (P.items.find(function (x) { return x.id === id; }) || { id: id, name: id, sched: "OTC" }); }
  function allDrugs() { return NB.D.drugs.concat(P.items); }
  function cat(d) { return d.cat || P.cat[d.id] || "otc"; }
  function batches() { return NB.D.batches.concat(P.batches); }
  function batchesOf(id) { return batches().filter(function (b) { return b.drug === id; }).sort(function (a, b) { return a.exp < b.exp ? -1 : 1; }); }
  function stockOf(id) { return batchesOf(id).filter(function (b) { return dl(b.exp) >= 0; }).reduce(function (s, b) { return s + b.qty; }, 0); }
  function mrpOf(id) { var b = batchesOf(id)[0]; return b ? b.mrp : Math.round(drug(id).price * 115) / 100; }
  function costOf(b) { var d = drug(b.drug); return b.mrp * (cat(d) === "otc" || cat(d) === "derma" ? 0.74 : d.id === "x16" ? 0.45 : 0.7); }
  function rxOnly(d) { return d.sched && d.sched !== "OTC"; }
  // FEFO allocation: earliest expiry first, skipping expired; an override batch is moved to the front.
  function fefo(id, qty, override) {
    var bs = batchesOf(id).filter(function (b) { return dl(b.exp) >= 0 && b.qty > 0; });
    if (override) bs.sort(function (a, b) { return a.batch === override ? -1 : b.batch === override ? 1 : 0; });
    var need = qty, picks = [];
    bs.forEach(function (b) { if (need <= 0) return; var t = Math.min(need, b.qty); picks.push({ b: b, q: t }); need -= t; });
    return { picks: picks, short: need, got: qty - need, all: bs };
  }
  function schedPill(d) {
    var s = d.sched;
    if (!india()) return s === "OTC" ? ui.pill("mute", "OTC", false) : d.ctrl || s === "H1" && d.cls === "Benzodiazepine" ? ui.pill("urg", L("Controlled", "مراقَب", "नियंत्रित"), "lock") : ui.pill("info", L("Rx only", "بوصفة فقط", "केवल Rx"), "file");
    if (s === "OTC") return ui.pill("mute", "OTC", false);
    if (s === "H1") return ui.pill("warn", "Sch H1", "scroll");
    if (s === "NDPS" || s === "X") return ui.pill("crit", s === "X" ? "Sch X" : "NDPS", "lock");
    return ui.pill("info", "Sch H", "file");
  }
  // Tax: India MRP is GST-inclusive (most medicines 5% since GST 2.0); GCC prices are VAT-exclusive.
  function taxRate(d) {
    var m = NB.state.market;
    if (m === "india") return d.gst != null ? d.gst : 5;
    if (m === "qatar") return 0;
    if (m === "uae") return rxOnly(d) ? 0 : 5;
    return rxOnly(d) ? 0 : 15;
  }
  // lines: [{d, q, price}] -> bill
  function bill(lines, discPct) {
    var g = 0, tax = 0, taxable = 0, zero = 0;
    lines.forEach(function (l) {
      var amt = l.q * l.price * (1 - (discPct || 0) / 100), r = taxRate(l.d);
      if (india()) { var tx = amt / (1 + r / 100); taxable += tx; tax += amt - tx; g += amt; }
      else { taxable += amt; tax += amt * r / 100; g += amt * (1 + r / 100); if (!r) zero += amt; }
    });
    var gross = lines.reduce(function (s, l) { return s + l.q * l.price; }, 0);
    return { gross: gross, disc: gross * (discPct || 0) / 100, taxable: taxable, tax: tax, total: g, zero: zero };
  }
  function taxRows(b) {
    var m = NB.mkt();
    if (india()) return "<dt>" + L("Taxable value", "القيمة الخاضعة", "कर योग्य मूल्य") + "</dt><dd>" + fmt(b.taxable) + "</dd><dt>CGST</dt><dd>" + fmt(b.tax / 2) + "</dd><dt>SGST</dt><dd>" + fmt(b.tax / 2) + "</dd>";
    if (!m.taxRate) return "<dt>" + esc(m.tax) + "</dt><dd>" + fmt(0) + "</dd>";
    return "<dt>" + L("Subtotal", "المجموع الفرعي", "उप-योग") + "</dt><dd>" + fmt(b.taxable) + "</dd><dt>" + esc(m.tax) + (b.zero ? " · " + L("Rx lines zero-rated", "أدوية الوصفة بنسبة صفر", "Rx शून्य दर") : "") + "</dt><dd>" + fmt(b.tax) + "</dd>";
  }
  function storeInfo() {
    return { india: { name: "Gupta Medical Store", area: "Aliganj, Lucknow", lic: "DL 20B/21B: UP-LKO-118204 · GSTIN 09AAKPG4412M1Z3" },
      uae: { name: "Al Noor Community Pharmacy", area: "Al Barsha, Dubai", lic: "DHA pharmacy licence PH-2291-04 · TRN 100392817400003" },
      ksa: { name: "Nakheel Pharmacy", area: "Olaya, Riyadh", lic: "MOH licence 1-2938-114 · VAT 310294817600003" },
      qatar: { name: "Al Shifa Pharmacy", area: "Al Sadd, Doha", lic: "MOPH pharmacy licence QP-0442" } }[NB.state.market];
  }
  function mid(k) { var m = NB.state.market; return m === "uae" ? "784-" + k.slice(0, 4) + "-•••••••-" + k.slice(4) : m === "ksa" ? "1•••••" + k.slice(1, 4) + k.slice(4) : "2" + k.slice(2, 4) + "•••••" + k.slice(4); }
  function regPrefix() { return { india: "KMC-", uae: "DHA-P-", ksa: "SCFHS-", qatar: "QCHP-" }[NB.state.market]; }

  /* ---------------- e-Rx queue data ---------------- */
  function rxList() {
    var g = NB.gcc(), rp = regPrefix(), m = NB.state.market;
    var list = [
      { id: "ERX-4102", src: "inhouse", p: "p3", d: "d2", issued: "17 Oct · 10:02", valid: "2026-11-16", abxValid: "2026-10-22", st: "new", wait: 6,
        items: [{ m: "m4", q: 60, sig: "bd", days: 30 }, { m: "m5", q: 30, sig: "odn", days: 30 }, { m: "m2", q: 3, sig: "odm", days: 3, abx: true }, { m: "m3", q: 10, sig: "sos", days: 5 }] },
      { id: "ERX-4099", src: "inhouse", p: "p1", d: "d1", issued: "17 Oct · 09:41", valid: "2026-11-16", st: "picking", wait: 9,
        items: [{ m: "m6", q: 30, sig: "odd", days: 30 }, { m: "x9", q: 1, sig: "app", days: 30 }] },
      { id: g ? "NRX-55213" : "NRX-88213", src: "qr", ext: g ? { name: "Omar Saleh", age: 44, sex: "M", phone: "50 412 7781", lang: "ar" } : { name: "Deepak Verma", age: 44, sex: "M", phone: "99011 23456", lang: "en" },
        doc: g ? { name: "Dr. Sameer Haddad", clinic: "Al Safa Medical Centre", reg: rp + "30311" } : { name: "Dr. Anil Kumar", clinic: "Apollo Clinic, HSR Layout", reg: "KMC-30311" },
        issued: "13 Oct · 18:40", valid: "2026-11-12", abxValid: "2026-10-18", st: "new", wait: 3,
        items: [{ m: "m1", q: 15, sig: "tds", days: 5, abx: true }, { m: "x4", q: 10, sig: "ac", days: 10 }] },
      { id: "WA-PH-0311", src: "photo", p: "p4", guardian: "Imran Khan", doc: { name: "Dr. R. Srinivas", clinic: g ? "Little Stars Clinic" : "Child Care Clinic, BTM Layout", reg: rp + "41290", hand: true },
        issued: "16 Oct (paper)", valid: "2026-11-15", abxValid: "2026-10-21", st: "verify", wait: 0, pickup: "18:00",
        items: [{ m: "x10", q: 1, sig: "syrsos", days: 3 }, { m: "x11", q: 1, sig: "syr", days: 5, abx: true }] },
      { id: "ERX-4097", src: "inhouse", p: "p8", d: "d2", issued: "17 Oct · 09:12", valid: "2026-11-16", st: "ready", wait: 0, notified: "10:05",
        items: [{ m: "m5", q: 30, sig: "odn", days: 30 }, { m: "m12", q: 30, sig: "odn", days: 30, brand: "Lipivas 10" }] },
      { id: "ERX-4088", src: "inhouse", p: "p6", d: "d2", issued: "14 Oct · 11:30", valid: "2027-01-12", st: "partial", wait: 0, balance: "30 tabs due with today's delivery",
        items: [{ m: "m8", q: 90, sig: "ac", days: 90, given: 60 }] },
      { id: g ? "NRX-54990" : "NRX-87990", src: "qr", ext: g ? { name: "Rania Mostafa", age: 51, sex: "F", phone: "55 019 2231", lang: "ar" } : { name: "Sunita Devi", age: 51, sex: "F", phone: "98860 14412", lang: "hi" },
        doc: g ? { name: "Dr. Huda Karim", clinic: "Family Care Clinic", reg: rp + "22871" } : { name: "Dr. Kiran Hegde", clinic: "Sagar Clinic, Jayanagar", reg: "KMC-22871" },
        issued: "06 Oct · 17:15", valid: "2026-11-05", abxValid: "2026-10-11", st: "expired", wait: 2,
        items: [{ m: "m2", q: 3, sig: "odm", days: 3, abx: true }] },
      { id: "ERX-4090", src: "inhouse", p: "p7", d: "d3", issued: "17 Oct · 09:05", valid: "2026-11-16", st: "collected", wait: 0,
        items: [{ m: "m11", q: 1, sig: "inh", days: 30 }, { m: "m7", q: 5, sig: "odn", days: 5 }] }
    ];
    // Network e-Rx from Nabd clinics: patients who chose this pharmacy (shown in both modes).
    list.splice(3, 0, { id: g ? "NRX-55241" : "NRX-88241", src: "qr", net: true, p: "p9", doc: { name: "Dr. Meera Iyer", clinic: g ? "Al Noor Polyclinic" : "Suvidha Multi-Speciality Clinic", reg: g ? rp + "48211" : "KMC-48211" },
      issued: "17 Oct · 10:12", valid: "2026-11-16", st: "new", wait: 1, items: [{ m: "m9", q: 10, sig: "sos", days: 5 }, { m: "x4", q: 10, sig: "ac", days: 10 }] });
    if (m === "ksa") list.splice(1, 0, { id: "WSF-2210-77841", src: "national", p: "p10", doc: { name: "Dr. Faisal Al-Qahtani", clinic: "Al Yasmin Medical Complex", reg: "SCFHS-18833" },
      issued: "17 Oct · 09:55", valid: "2026-11-16", st: "new", wait: 4, ins: "Bupa Arabia · NPHIES eligibility approved · co-pay 20%",
      items: [{ m: "m12", q: 30, sig: "odn", days: 30 }, { m: "m5", q: 30, sig: "odn", days: 30 }] });
    if (m === "uae" || m === "qatar") list.splice(1, 0, { id: m === "uae" ? "DHA-ERX-77120" : "MOPH-RX-30172", src: "national", ext: { name: "Layla Hassan", age: 38, sex: "F", phone: m === "uae" ? "52 771 0934" : "3312 4410", lang: "ar" },
      doc: { name: "Dr. Fatima Rahman", clinic: m === "uae" ? "Al Noor Polyclinic" : "Doha Women's Clinic", reg: rp + "77120" }, issued: "17 Oct · 09:50", valid: "2026-11-16", st: "new", wait: 5,
      ins: m === "uae" ? "Daman Enhanced · approval received via eClaimLink · co-pay 10%" : "QLM · approved · co-pay 0%", items: [{ m: "m8", q: 30, sig: "ac", days: 30 }, { m: "m7", q: 10, sig: "odn", days: 10 }] });
    if (standalone()) list = list.filter(function (r) { return r.src !== "inhouse"; }).concat([
      { id: g ? "NRX-55102" : "NRX-87102", src: "qr", net: true, p: "p6", refill: "Refill 2 of 3", doc: { name: "Dr. Vikram Shah", clinic: g ? "Al Noor Polyclinic" : "Suvidha Multi-Speciality Clinic", reg: g ? rp + "39102" : "KMC-39102" },
        issued: "18 Sep · 11:30", valid: "2026-12-17", st: "ready", wait: 0, notified: "09:30", items: [{ m: "m8", q: 30, sig: "ac", days: 30 }] }]);
    return list;
  }
  function rxById(id) { return rxList().find(function (r) { return r.id === id; }) || rxList()[0]; }
  function rxPat(r) {
    if (r.p) { var p = NB.pat(r.p); return { name: NB.pname(p), age: p.age, sex: p.sex, phone: p.phone, lang: p.lang, allergies: p.allergies || [], tags: p.tags || [], ins: p.ins, mrn: p.mrn, known: true }; }
    var e = r.ext; return { name: e.name, age: e.age, sex: e.sex, phone: e.phone, lang: e.lang, allergies: null, tags: [], ins: null, known: false };
  }
  function rxDoc(r) { if (r.d) { var d = NB.doc(r.d); return { name: NB.dname(d), clinic: standalone() ? NB.fac().name : L("In-house", "داخلي", "इन-हाउस") + " · " + d.spec, reg: NB.gcc() ? regPrefix() + d.regNo.split("-").pop() : d.regNo }; } return r.doc; }
  var RXST = {
    "new": ["info", "New", "جديدة", "नई", "plus"], verify: ["warn", "To verify", "للتحقق", "सत्यापन बाकी", "eye"], picking: ["sync", "Picking", "قيد التجهيز", "पिकिंग", null],
    ready: ["ok", "Ready for pickup", "جاهزة للاستلام", "पिकअप के लिए तैयार", "checkcircle"], partial: ["warn", "Partially dispensed", "صرف جزئي", "आंशिक रूप से दिया", "hourglass"],
    collected: ["mute", "Collected", "تم الاستلام", "ले गए", "check"], expired: ["crit", "Rx expired", "وصفة منتهية", "Rx की अवधि ख़त्म", "alertfill"]
  };
  function rxPill(st) { var s = RXST[st]; return ui.pill(s[0], L(s[1], s[2], s[3]), s[4] || undefined); }
  function srcLabel(r) {
    var m = NB.state.market;
    return { inhouse: [L("In-house e-Rx", "وصفة داخلية", "इन-हाउस e-Rx"), "steth"], qr: [r.net ? L("Nabd network QR", "رمز شبكة نبض", "नब्ज़ नेटवर्क QR") : L("Signed e-Rx QR", "رمز وصفة موقّعة", "साइन्ड e-Rx QR"), "qr"],
      photo: [L("WhatsApp photo", "صورة واتساب", "व्हाट्सऐप फ़ोटो"), "camera"], national: [m === "ksa" ? "Wasfaty" : m === "uae" ? "DHA e-Prescription" : "MOPH e-Rx", "globe"] }[r.src];
  }

  /* ---------------- Mode bar (standalone vs in-clinic) ---------------- */
  function modeBar() {
    var sa = standalone(), ft = NB.state.facType, st = storeInfo(), f = NB.fac();
    var kind = sa ? L("Standalone pharmacy", "صيدلية مستقلة", "स्टैंडअलोन फ़ार्मेसी") : ft === "hospital" ? L("Hospital pharmacy", "صيدلية المستشفى", "अस्पताल फ़ार्मेसी")
      : ft === "solo" ? L("Clinic dispensary", "صيدلية العيادة", "क्लिनिक डिस्पेंसरी") : L("In-clinic pharmacy", "صيدلية داخل العيادة", "क्लिनिक फ़ार्मेसी");
    var title = sa ? esc(st.name) + ' <span class="faint">· ' + esc(st.area) + "</span>" : esc(f.name) + ' <span class="faint">· ' + esc(NB.FACILITY_TYPES[ft].name) + "</span>";
    var sub = sa ? L("Own licence and billing. Prescriptions arrive as Nabd network QR scans, WhatsApp photos or paper; no clinic feed.", "ترخيص وفوترة مستقلة. تصل الوصفات عبر رمز شبكة نبض أو صور واتساب أو ورقية.", "अपना लाइसेंस और बिलिंग। पर्चे नब्ज़ नेटवर्क QR, व्हाट्सऐप फ़ोटो या काग़ज़ से आते हैं।") + " · " + esc(st.lic)
      : L("Live e-Rx feed from this facility's doctors; bills can join the patient's single visit checkout.", "وصفات مباشرة من أطباء المنشأة؛ يمكن ضم الفاتورة إلى دفع الزيارة.", "इस सुविधा के डॉक्टरों से लाइव e-Rx; बिल विज़िट चेकआउट में जुड़ सकता है।") + (ft === "hospital" ? " " + L("Ward indents included.", "تشمل طلبات الأجنحة.", "वार्ड इंडेंट शामिल।") : "");
    var on = sa;
    return '<div class="ph-mode' + (sa ? " sa" : "") + '"><span class="ph-mi">' + ui.icon(sa ? "store" : "building") + '</span><div class="t"><div class="row">' +
      ui.pill(sa ? "info" : "acc", kind, sa ? "store" : "building") + "<b>" + title + "</b></div><small>" + sub + "</small></div>" +
      '<div class="ph-sw" data-toggle="phStandalone" role="switch" aria-checked="' + on + '" tabindex="0"><span>' + L("Run as standalone pharmacy", "تشغيل كصيدلية مستقلة", "स्टैंडअलोन फ़ार्मेसी के रूप में चलाएँ") + '</span><span class="sw ' + (on ? "on" : "") + '"></span></div></div>';
  }

  /* ================= SCREEN: e-Rx queue ================= */
  NB.screen("pharmacist.dispense", function (c) {
    var list = rxList(), f = NB.v("phQ", "open"), sa = standalone();
    var groups = { open: function (r) { return /new|verify|picking/.test(r.st); }, ready: function (r) { return r.st === "ready"; }, partial: function (r) { return r.st === "partial"; },
      done: function (r) { return r.st === "collected"; }, blocked: function (r) { return r.st === "expired"; }, all: function () { return true; } };
    var cnt = function (k) { return list.filter(groups[k]).length; };
    var rows = list.filter(groups[f] || groups.all);
    var h = modeBar();
    h += ui.header(L("e-Rx queue", "طابور الوصفات", "ई-Rx क़तार"), "Sat, 17 Oct 2026 · " + list.length + " " + L("prescriptions today · median Rx-to-bill", "وصفة اليوم · متوسط الصرف", "पर्चे आज · औसत Rx-से-बिल") + " <b class=\"num\">18 s</b>",
      ui.btn(L("Scan e-Rx QR", "مسح رمز الوصفة", "e-Rx QR स्कैन करें"), { cls: "pri", ov: "scanrx", icon: "qr" }) + ui.btn(L("Paper Rx photo", "صورة وصفة ورقية", "काग़ज़ी Rx फ़ोटो"), { ov: "scanrx", arg: "photo", icon: "camera" }));
    h += '<div class="grid g4 kpis">' + ui.kpi(n(cnt("open")), L("Waiting to be dispensed", "بانتظار الصرف", "देना बाकी"), ui.icon("clockh", "xs") + " " + L("longest wait 9 min", "أطول انتظار 9 د", "सबसे लंबा 9 मिनट"), "warnc") +
      ui.kpi(n(cnt("ready")), L("Ready for pickup", "جاهزة للاستلام", "पिकअप के लिए तैयार"), ui.icon("chat", "xs") + " " + L("WhatsApp sent", "أُرسل واتساب", "व्हाट्सऐप भेजा"), "okc") +
      ui.kpi(n(cnt("partial")), L("Balance owed to patients", "كميات متبقية للمرضى", "मरीज़ों का बकाया"), L("auto-notify when stock arrives", "إشعار تلقائي عند وصول المخزون", "स्टॉक आने पर सूचना"), "faint") +
      ui.kpi(n(cnt("blocked") + 2), L("Expired Rx refused today", "وصفات منتهية مرفوضة", "आज लौटाए एक्सपायर्ड Rx"), L("antibiotic validity 5 days", "صلاحية المضاد الحيوي 5 أيام", "एंटीबायोटिक वैधता 5 दिन"), "faint") + "</div>";
    h += '<div class="row sb">' + ui.seg("phQ", [["open", L("To dispense", "للصرف", "देना है"), cnt("open")], ["ready", L("Ready", "جاهزة", "तैयार"), cnt("ready")], ["partial", L("Partial", "جزئي", "आंशिक"), cnt("partial")],
      ["blocked", L("Expired", "منتهية", "एक्सपायर्ड"), cnt("blocked")], ["done", L("Collected", "مستلمة", "ले गए"), cnt("done")], ["all", L("All", "الكل", "सभी"), list.length]], "open") + "</div>";

    var body;
    if (c.mobile) {
      body = '<div class="stack">' + (rows.length ? rows.map(function (r) {
        var p = rxPat(r), s = srcLabel(r);
        return '<div class="ph-rxc" data-ov="dispenserx" data-arg="' + r.id + '"><div class="row sb nw"><span class="tok">' + r.id + "</span>" + rxPill(r.st) + '</div><div class="cellp"><b>' + esc(p.name) + "</b><small>" +
          ui.icon(s[1], "xs") + " " + esc(s[0]) + " · " + r.items.length + " " + L("items", "أصناف", "आइटम") + (r.wait ? " · " + r.wait + " min" : "") + "</small></div></div>";
      }).join("") : '<div class="empty">' + L("Nothing here.", "لا شيء هنا.", "यहाँ कुछ नहीं।") + "</div>") + "</div>";
    } else {
      body = ui.table([{ h: "Rx" }, { h: L("Patient", "المريض", "मरीज़") }, { h: L("Source & prescriber", "المصدر والطبيب", "स्रोत और डॉक्टर") }, { h: L("Items", "الأصناف", "आइटम") },
        { h: L("Rx validity", "صلاحية الوصفة", "Rx वैधता") }, { h: L("Status", "الحالة", "स्थिति") }],
        rows.map(function (r) {
          var p = rxPat(r), d = rxDoc(r), s = srcLabel(r);
          var vd = r.abxValid ? dl(r.abxValid) : dl(r.valid);
          var val = r.st === "expired" ? ui.pill("crit", L("Expired", "منتهية", "एक्सपायर्ड") + " " + fd(r.abxValid).slice(0, 6)) : vd <= 2 ? ui.pill("urg", vd + " " + L("day left", "يوم متبقٍ", "दिन बाकी"), "hourglass")
            : '<span class="small muted">' + L("to", "حتى", "तक") + " " + n(fd(r.abxValid || r.valid)) + "</span>";
          var ctl = r.items.some(function (it) { var dd = drug(it.m); return dd.sched === "H1" || dd.ctrl; });
          return { cls: r.st === "expired" ? "crit" : r.st === "verify" ? "urg" : "", ov: "dispenserx", arg: r.id, cells: [ui.cellp('<span class="tok">' + r.id + "</span>", r.wait ? ui.icon("clockh", "xs") + " " + n(r.wait + " min") : ""),
            ui.cellp(esc(p.name), (p.age ? p.age + " " + p.sex : "") + (r.guardian ? " · " + L("via", "عبر", "द्वारा") + " " + esc(r.guardian) : "") + (p.allergies && p.allergies.length ? ' · <span class="critc">' + ui.icon("alertfill", "xs") + " " + esc(p.allergies[0].a) + "</span>" : "")),
            ui.cellp(ui.icon(s[1], "xs") + " " + esc(s[0]), esc(d.name)),
            ui.cellp(n(r.items.length) + " " + L("items", "أصناف", "आइटम") + (ctl ? " " + ui.icon("lock", "xs") : ""), esc(drug(r.items[0].m).gen || drug(r.items[0].m).name) + (r.items.length > 1 ? " +" + (r.items.length - 1) : "")),
            val, rxPill(r.st)] };
        }), { min: 760, empty: L("Nothing in this list.", "لا شيء هنا.", "इस सूची में कुछ नहीं।") });
    }

    var feed = sa ? ui.card(L("Nabd network", "شبكة نبض", "नब्ज़ नेटवर्क"), '<p class="small muted" style="margin:0 0 10px">' + L("14 clinics nearby use Nabd. When a patient picks your pharmacy, their signed e-Rx arrives here; any patient can also show the QR at the counter.", "14 عيادة قريبة تستخدم نبض. عندما يختار المريض صيدليتك تصل الوصفة هنا.", "पास के 14 क्लिनिक नब्ज़ इस्तेमाल करते हैं। मरीज़ आपकी फ़ार्मेसी चुने तो e-Rx यहाँ आता है।") + "</p>" +
        '<div class="lst">' + ui.li('<span class="av">' + ui.icon("qr", "sm") + "</span>", L("QR scans today", "مسح الرموز اليوم", "आज QR स्कैन"), L("31 verified · 2 refused (expired)", "31 موثقة · 2 مرفوضة", "31 सत्यापित · 2 अस्वीकृत"), n("33")) +
        ui.li('<span class="av">' + ui.icon("camera", "sm") + "</span>", L("WhatsApp photo Rx", "وصفات بالصور", "व्हाट्सऐप फ़ोटो Rx"), L("Verify against the paper at pickup", "تحقق من الورقة عند الاستلام", "पिकअप पर काग़ज़ से मिलाएँ"), ui.pill("warn", "1 " + L("to verify", "للتحقق", "सत्यापन"))) +
        ui.li('<span class="av">' + ui.icon("bell", "sm") + "</span>", L("Refill reminders due", "تذكير بإعادة الصرف", "रीफ़िल रिमाइंडर"), L("Chronic patients, sent 3 days before", "مرضى مزمنون، قبل 3 أيام", "पुराने मरीज़, 3 दिन पहले"), n("12")) + "</div>", { icon: "globe" })
      : ui.card(L("In-house e-Rx feed", "وصفات داخلية مباشرة", "इन-हाउस e-Rx फ़ीड"), '<div class="lst">' + NB.D.doctors.slice(0, 4).map(function (d, i) {
        return ui.li(ui.av(d.name), esc(NB.dname(d)), esc(d.spec) + " · " + [3, 6, 4, 1][i] + " " + L("Rx today", "وصفات اليوم", "Rx आज"), i === 1 ? ui.pill("sync", L("Writing Rx", "يكتب وصفة", "Rx लिख रहे")) : "");
      }).join("") + "</div>" + (NB.state.facType === "hospital" ? ui.note("info", L("3 ward indents pending (General Ward A, ICU). Open them from Hospital pharmacy → Indents.", "3 طلبات أجنحة معلقة.", "3 वार्ड इंडेंट बाकी।")) : ""), { icon: "steth", right: ui.pill("ok", L("Live", "مباشر", "लाइव"), "dot") });
    var ready = ui.card(L("Ready for pickup", "جاهزة للاستلام", "पिकअप के लिए तैयार"), '<div class="lst">' + list.filter(groups.ready).map(function (r) {
      var p = rxPat(r);
      return ui.li('<span class="av">' + ui.icon("box", "sm") + "</span>", esc(p.name), r.id + " · " + L("WhatsApp sent", "أُرسل واتساب", "व्हाट्सऐप भेजा") + " " + (r.notified || ""), ui.btn(L("Remind", "تذكير", "याद दिलाएँ"), { cls: "sm ghost", act: "toast", arg: "Pickup reminder sent on WhatsApp", icon: "chat" }));
    }).join("") + "</div>", { icon: "checkcircle" });
    var nat = NB.state.market === "ksa" ? ui.card("Wasfaty & SFDA RSD", ui.note("ok", "Wasfaty connected · 18 dispenses synced today · last sync 10:24") + '<p class="small muted" style="margin:10px 0 0">Every dispense of a serialised pack posts an RSD event (GTIN + serial) to SFDA within a minute.</p>', { icon: "cloudsync" })
      : NB.gcc() ? ui.card(L("Insurance at dispense", "التأمين عند الصرف", "बीमा"), '<div class="lst">' + ui.li('<span class="av">' + ui.icon("shieldcheck", "sm") + "</span>", L("Approvals pending", "موافقات معلقة", "बाकी अनुमोदन"), L("Median payer wait 4 min", "متوسط الانتظار 4 د", "औसत 4 मिनट"), n("2")) + "</div>", { icon: "shieldcheck" }) : "";
    return h + '<div class="split">' + body + '<div class="stack s16">' + feed + ready + nat + "</div></div>";
  });

  /* ---------------- Dispense drawer ---------------- */
  function check(kind, title, sub) {
    var ic = { ok: ["checkcircle", "okc"], warn: ["alertcircle", "warnc"], crit: ["alertfill", "critc"], info: ["info", "infoc"] }[kind];
    return '<div class="ph-ck">' + ui.icon(ic[0], "sm " + ic[1]) + "<div><b>" + title + "</b>" + (sub ? "<small>" + sub + "</small>" : "") + "</div></div>";
  }
  function allergyCheck(p, items) {
    if (!p.allergies) return check("warn", L("No allergy record for this patient", "لا يوجد سجل حساسية", "एलर्जी रिकॉर्ड नहीं"), L("External patient: ask and record allergies before handing over.", "مريض خارجي: اسأل وسجّل الحساسية.", "बाहरी मरीज़: एलर्जी पूछें और दर्ज करें।")) +
      ui.toggle("phAlAsk", L("Patient confirms no known drug allergies", "يؤكد المريض عدم وجود حساسية", "मरीज़ ने बताया कोई एलर्जी नहीं"), L("Saved to their Nabd profile", "يُحفظ في ملفه", "उनकी प्रोफ़ाइल में सेव"));
    var out = "";
    if (!p.allergies.length) out += check("ok", L("No known drug allergies", "لا حساسية معروفة", "कोई ज्ञात एलर्जी नहीं"), L("Re-checked against all items", "تم التحقق من كل الأصناف", "सभी आइटम से जाँचा"));
    p.allergies.forEach(function (a) {
      var hit = items.filter(function (it) { var d = drug(it.m); return d.cls === a.a || (a.a === "Aspirin" && d.cls === "NSAID") || (a.a === "Sulfonamides" && /sulfa/i.test(d.gen || "")); });
      if (hit.length) out += check("crit", esc(a.a) + " " + L("allergy matches", "حساسية تطابق", "एलर्जी मेल खाती") + " " + esc(drug(hit[0].m).name), esc(a.sev + " · " + a.rx) + " · " + L("call the prescriber before dispensing", "اتصل بالطبيب قبل الصرف", "देने से पहले डॉक्टर को कॉल करें"));
      else out += check("ok", esc(a.a) + " " + L("allergy: no conflict", "حساسية: لا تعارض", "एलर्जी: कोई टकराव नहीं"), items.map(function (it) { return esc(drug(it.m).gen || drug(it.m).name) + " (" + esc(drug(it.m).cls) + ")"; }).join(", "));
    });
    var preg = p.tags.some(function (t) { return /Pregnant/.test(t); });
    items.forEach(function (it) {
      var d = drug(it.m);
      if (d.preg === "X" && (preg || (p.sex === "F" && p.age >= 15 && p.age <= 49))) out += check(preg ? "crit" : "warn", esc(d.gen) + " · " + L("pregnancy category X", "فئة الحمل X", "गर्भावस्था श्रेणी X"),
        preg ? L("Patient is pregnant. Do not dispense; contact prescriber.", "المريضة حامل. لا تصرف.", "मरीज़ गर्भवती है। न दें।") : L("Woman of child-bearing age: confirm negative pregnancy test and contraception counselling.", "امرأة في سن الإنجاب: تأكد من اختبار الحمل والاستشارة.", "प्रजनन आयु की महिला: प्रेगनेंसी टेस्ट और गर्भनिरोध परामर्श की पुष्टि करें।"));
    });
    return out;
  }

  NB.overlay("dispenserx", function (id, c) {
    var r = rxById(id), p = rxPat(r), d = rxDoc(r), s = srcLabel(r), m = NB.mkt(), sa = standalone();
    var lang = NB.v("phLbl_" + r.id, p.lang === "hi" || p.lang === "ar" ? p.lang : "en");
    var expired = r.st === "expired", verifyNeeded = r.src === "photo" && !NB.v("phVer_" + r.id, false);
    var body = "";
    // Patient strip
    body += '<div class="row nw">' + ui.av(p.name, "lg") + '<div class="fl"><b style="font-size:15px">' + esc(p.name) + '</b><div class="small faint">' + (p.age ? p.age + " " + p.sex + " · " : "") + '<span class="ltr">' + m.phone + " " + esc(p.phone) + "</span>" + (p.mrn ? " · " + p.mrn : "") +
      (r.guardian ? " · " + L("guardian", "ولي الأمر", "अभिभावक") + " " + esc(r.guardian) : "") + '</div><div class="chips" style="margin-top:6px">' + rxPill(r.st) + ui.pill("mute", s[0], s[1]) +
      (p.allergies && p.allergies.length ? p.allergies.map(function (a) { return ui.pill("crit", L("Allergy", "حساسية", "एलर्जी") + ": " + a.a); }).join("") : "") +
      p.tags.map(function (t) { return ui.pill("mute", t, false); }).join("") + (r.refill ? ui.pill("acc", r.refill, "refresh") : "") + "</div></div></div>";
    // Steps
    var step = expired ? 0 : verifyNeeded ? 0 : r.st === "ready" ? 4 : r.st === "picking" ? 1 : 1;
    body += '<div class="ph-flow">' + [L("Verify", "تحقق", "सत्यापन"), L("Pick (FEFO)", "تجهيز", "पिक (FEFO)"), L("Safety check", "فحص السلامة", "सुरक्षा जाँच"), L("Label & counsel", "الملصق والإرشاد", "लेबल और परामर्श"), L("Bill & hand over", "الفاتورة والتسليم", "बिल और सौंपना")].map(function (x, i) {
      return '<span class="s ' + (i < step ? "done" : i === step ? "cur" : "") + '">' + (i < step ? ui.icon("check", "xs") : n(i + 1)) + " " + x + "</span>";
    }).join("") + "</div>";

    // 1. Verify
    var v = "";
    if (r.src === "inhouse") v += check("ok", L("Prescriber", "الطبيب", "डॉक्टर") + ": " + esc(d.name) + " · " + n(esc(d.reg)), L("Registration active with the state medical council · in-house", "تسجيل فعّال", "स्टेट मेडिकल काउंसिल में पंजीकरण सक्रिय")) +
      check("ok", L("Digitally signed in Nabd", "موقّعة رقمياً في نبض", "नब्ज़ में डिजिटल हस्ताक्षर"), L("Issued", "صدرت", "जारी") + " " + r.issued + " · " + L("tamper-evident, cannot be edited after signing", "لا يمكن تعديلها بعد التوقيع", "हस्ताक्षर के बाद बदला नहीं जा सकता"));
    if (r.src === "qr" || r.src === "national") v += check("ok", (r.src === "national" ? L("Fetched from", "جُلبت من", "से लाया गया") + " " + s[0] + " " + L("with patient OTP", "برمز المريض", "मरीज़ OTP से") : L("QR scanned · signature valid", "تم مسح الرمز · التوقيع صالح", "QR स्कैन · हस्ताक्षर मान्य")), esc(d.clinic) + " · " + L("issued", "صدرت", "जारी") + " " + r.issued) +
      check("ok", L("Prescriber registration verified", "تم التحقق من تسجيل الطبيب", "डॉक्टर पंजीकरण सत्यापित"), esc(d.name) + " · " + n(esc(d.reg)) + " · " + esc(m.regulator));
    if (r.src === "photo") {
      v += '<div class="grid g2"><div class="ph-photo"><div>' + ui.icon("image", "lg") + "<br>" + L("Photo from WhatsApp · 10:21", "صورة من واتساب · 10:21", "व्हाट्सऐप से फ़ोटो · 10:21") + '<br><span class="tiny">' + L("handwritten · 1 page", "مكتوبة بخط اليد", "हस्तलिखित · 1 पेज") + "</span></div></div><div>" +
        check("warn", L("Prescriber reg. no. read from photo", "رقم التسجيل من الصورة", "फ़ोटो से पंजीकरण संख्या"), esc(d.name) + " · " + n(esc(d.reg)) + " · " + L("council lookup: active", "فعّال", "सक्रिय")) +
        check("warn", L("Signature and stamp visible", "التوقيع والختم ظاهران", "हस्ताक्षर और मुहर दिखते हैं"), L("Confirm against the original at pickup", "طابق الأصل عند الاستلام", "पिकअप पर मूल से मिलाएँ")) +
        check("ok", L("Dated 16 Oct: within validity", "مؤرخة 16 أكتوبر: صالحة", "16 अक्टूबर: वैध"), L("Antibiotic lines valid to", "المضاد الحيوي صالح حتى", "एंटीबायोटिक वैध") + " " + fd(r.abxValid)) + "</div></div>" +
        ui.note("ai", L("Items were read from the photo and need your check: Paracetamol 250 mg/5 ml syrup, Amoxicillin 250 mg/5 ml suspension. Nothing is dispensed until you confirm.", "تمت قراءة الأصناف من الصورة وتحتاج تأكيدك.", "आइटम फ़ोटो से पढ़े गए हैं, आपकी जाँच ज़रूरी है।")) +
        ui.toggle("phVer_" + r.id, L("I have verified this prescription", "تحققت من هذه الوصفة", "मैंने यह पर्चा सत्यापित किया"), L("Your name and time are recorded; the paper original must be seen at pickup (Schedule H).", "يُسجّل اسمك والوقت؛ يجب رؤية الأصل.", "आपका नाम और समय दर्ज होगा; मूल पर्चा पिकअप पर देखना ज़रूरी।"));
    }
    if (expired) v += ui.note("crit", "<b>" + L("Prescription expired on", "انتهت الوصفة في", "पर्चे की अवधि ख़त्म") + " " + fd(r.abxValid) + ".</b> " + L("Antibiotic prescriptions are valid for 5 days. Do not dispense; ask the patient for a fresh prescription. The refusal is logged with a reason.", "وصفات المضادات الحيوية صالحة 5 أيام. لا تصرف.", "एंटीबायोटिक पर्चा 5 दिन वैध। न दें; नया पर्चा माँगें। मना करना कारण सहित दर्ज होता है।"));
    else if (r.abxValid && dl(r.abxValid) <= 2) v += check("warn", L("Antibiotic validity ends", "تنتهي صلاحية المضاد الحيوي", "एंटीबायोटिक वैधता ख़त्म") + " " + fd(r.abxValid), L("Dispense today; the QR will be refused after that date.", "اصرف اليوم.", "आज दें; उसके बाद QR अस्वीकार होगा।"));
    else v += check("ok", L("Valid until", "صالحة حتى", "वैध") + " " + fd(r.valid) + (r.abxValid ? " · " + L("antibiotic lines to", "المضاد الحيوي حتى", "एंटीबायोटिक") + " " + fd(r.abxValid) : ""), r.refill ? r.refill + " · " + L("next refill allowed from 16 Nov", "إعادة الصرف التالية من 16 نوفمبر", "अगला रीफ़िल 16 नवंबर से") : L("Single dispense; balance quantities stay open until filled.", "صرف واحد؛ الكميات المتبقية تبقى مفتوحة.", "एक बार; बाक़ी मात्रा खुली रहती है।"));
    if (r.ins) v += check("ok", L("Insurance", "التأمين", "बीमा"), esc(r.ins));
    body += ui.card(L("1 · Verify prescription", "1 · التحقق من الوصفة", "1 · पर्चा सत्यापन"), v, { icon: "shieldcheck" });

    // 2. Pick with FEFO
    var lines = [], partial = false, items = "", picked = [];
    r.items.forEach(function (it, i) {
      var subKey = "phSub_" + r.id + "_" + i, sub = it.brand && P.generic[it.m] && NB.v(subKey, false);
      var dd = drug(sub ? P.generic[it.m] : it.m), need = it.q - (it.given || 0);
      var ov = NB.v("phBo_" + r.id + "_" + i, null), al = fefo(dd.id, need, ov);
      var first = al.picks[0], warnExp = first && dl(first.b.exp) < it.days;
      var alt = warnExp ? al.all.filter(function (b) { return dl(b.exp) >= it.days && b.qty >= need; })[0] : null;
      if (al.short > 0) partial = true;
      picked[i] = al;
      if (!expired) al.picks.forEach(function (pk) { lines.push({ d: dd, q: pk.q, price: pk.b.mrp }); });
      var sg = SIG[it.sig];
      items += '<div class="ph-item' + (expired ? " bad" : "") + '"><div class="hd"><div class="fl"><b>' + esc(dd.name) + "</b><small>" + esc(sg[0]) + " · " + esc(L(sg[1], sg[2], sg[3])) + " · " + it.days + " " + L("days", "يوم", "दिन") +
        (it.brand ? " · " + L("prescribed as", "موصوف باسم", "लिखा गया") + " " + esc(it.brand) : "") + '</small></div><div class="stack s4" style="align-items:flex-end"><b class="num ltr">× ' + need + "</b>" + schedPill(dd) + "</div></div>";
      if (expired) items += '<div class="small critc">' + ui.icon("lock", "xs") + " " + L("Blocked: prescription expired", "محظور: الوصفة منتهية", "रोका गया: पर्चा एक्सपायर्ड") + "</div>";
      else {
        items += '<div class="ph-bt">' + al.picks.map(function (pk) {
          return '<span class="ph-bchip pick">' + ui.icon("box", "xs") + '<b class="num ltr">' + pk.b.batch + '</b><span class="ltr">' + L("exp", "انتهاء", "एक्सपायरी") + " " + fd(pk.b.exp, true) + '</span><span class="ltr">' + pk.b.loc + '</span><span class="num ltr">× ' + pk.q + "</span></span>";
        }).join("") + (al.all.length > al.picks.length ? '<span class="ph-bchip">' + (al.all.length - al.picks.length) + " " + L("more batch", "دفعة أخرى", "और बैच") + "</span>" : "") + "</div>";
        if (warnExp) items += ui.note("warn", "<b>" + L("Batch", "الدفعة", "बैच") + " " + first.b.batch + " " + L("expires in", "تنتهي خلال", "एक्सपायर होगा") + " " + dl(first.b.exp) + " " + L("days", "يوم", "दिन") + "</b>, " + L("before the", "قبل نهاية", "") + " " + it.days + L("-day course ends.", " يوماً من العلاج.", "-दिन के कोर्स से पहले।") +
          (alt ? '<div style="margin-top:8px">' + ui.btn(L("Use", "استخدم", "इस्तेमाल करें") + " " + alt.batch + " · " + fd(alt.exp, true), { cls: "sm", act: "ph-batch", arg: r.id + "|" + i + "|" + alt.batch, icon: "refresh" }) + "</div>" : ""));
        if (ov) items += '<div class="small okc">' + ui.icon("checkcircle", "xs") + " " + L("FEFO override recorded with reason: batch would expire mid-course", "سُجّل التجاوز مع السبب", "FEFO ओवरराइड कारण सहित दर्ज") + "</div>";
        if (al.short > 0) items += ui.note("warn", "<b>" + L("Only", "فقط", "केवल") + " " + al.got + " " + L("in stock.", "في المخزون.", "स्टॉक में।") + "</b> " + L("Dispense", "اصرف", "अभी दें") + " " + al.got + " " + L("now; balance", "الآن؛ المتبقي", "; बाक़ी") + " " + al.short + " " + L("is owed and the patient gets a WhatsApp when it arrives (PO-2614, due tomorrow 11:00).", "مستحق وسيصل إشعار واتساب عند وصوله.", "बकाया है; आने पर व्हाट्सऐप जाएगा (PO-2614, कल 11:00)।"));
        if (it.given) items += '<div class="small muted">' + ui.icon("history", "xs") + " " + it.given + " " + L("given on 14 Oct · balance", "صُرفت في 14 أكتوبر · المتبقي", "14 अक्टूबर को दिए · बाक़ी") + " " + need + " · " + esc(r.balance || "") + "</div>";
        if (it.brand && P.generic[it.m]) {
          var gd = drug(P.generic[it.m]), save = (mrpOf(it.m) - mrpOf(gd.id)) * it.q;
          items += ui.toggle(subKey, L("Substitute with generic", "استبدال بدواء جنيس", "जेनेरिक से बदलें") + ": " + gd.name, L("Same salt and strength · saves the patient", "نفس المادة والتركيز · يوفر", "वही साल्ट और ताक़त · मरीज़ की बचत") + " " + NB.money(save).replace(/<[^>]+>/g, "") + " · " + L("patient consent recorded", "تُسجّل موافقة المريض", "मरीज़ की सहमति दर्ज"));
          if (sub) items += '<div class="small okc">' + ui.icon("checkcircle", "xs") + " " + L("Consent: verbal, witnessed by R. Joshi · 10:31 · prescriber notified", "موافقة شفهية · أُبلغ الطبيب", "मौखिक सहमति · डॉक्टर को सूचना") + "</div>";
        }
      }
      items += "</div>";
    });
    body += ui.card(L("2 · Pick (first expiry, first out)", "2 · التجهيز (الأقرب انتهاءً أولاً)", "2 · पिक (FEFO)"), '<div class="stack">' + items + "</div>", { icon: "box", right: '<span class="small faint">' + L("Scan each pack to confirm", "امسح كل عبوة", "हर पैक स्कैन करें") + "</span>" });

    // 3. Safety re-check
    body += ui.card(L("3 · Safety re-check", "3 · إعادة فحص السلامة", "3 · सुरक्षा पुनः जाँच"), allergyCheck(p, r.items), { icon: "shieldcheck" });

    // 4. Label & counselling
    var li = { en: 1, ar: 2, hi: 3 }[lang];
    var labels = r.items.slice(0, 2).map(function (it, i) {
      var dd = drug(it.m), sg = SIG[it.sig], co = COUNSEL[dd.gen] || COUNSEL[dd.cls];
      var st = storeInfo();
      return '<div class="ph-label" dir="' + (lang === "ar" ? "rtl" : "ltr") + '"><div class="mt">' + esc(sa ? st.name : NB.fac().name) + " · " + r.id + "</div><b>" + esc(p.name) + '</b><div class="ln"><b>' + esc(dd.name) + '</b><div class="sig">' + esc(sg[li]) + "</div>" +
        (co ? "<div>" + esc(co[li === 1 ? 0 : li === 2 ? 1 : 2]) + "</div>" : "") + '</div><div class="ln mt ltr">Qty ' + (picked[i] && !expired ? picked[i].got : it.q) + " · " + (picked[i] && picked[i].picks[0] ? "B. " + picked[i].picks[0].b.batch + " · Exp " + fd(picked[i].picks[0].b.exp, true) : "") + (dd.cold ? " · 2–8°C" : " · <25°C") + "</div></div>";
    }).join("");
    body += ui.card(L("4 · Label & counselling", "4 · الملصق والإرشاد", "4 · लेबल और परामर्श"), '<div class="row sb" style="margin-bottom:12px"><span class="small muted">' + L("Printed in the patient's language; also sent on WhatsApp with a voice note.", "تطبع بلغة المريض وتُرسل عبر واتساب مع رسالة صوتية.", "मरीज़ की भाषा में छपता है; व्हाट्सऐप पर वॉइस नोट के साथ भी।") + "</span>" +
      ui.seg("phLbl_" + r.id, [["en", "English"], ["hi", "हिन्दी"], ["ar", "العربية"]], lang) + '</div><div class="grid g2">' + labels + "</div>" +
      ui.toggle("phCounsel_" + r.id, L("Counselled at the counter", "تم الإرشاد عند الكاونتر", "काउंटर पर परामर्श दिया"), L("Required for Schedule H1 and pregnancy-category X items", "مطلوب لأصناف H1 وفئة الحمل X", "H1 और श्रेणी X दवाओं के लिए ज़रूरी"), r.st === "ready"), { icon: "translate" });

    // 5. Bill
    var disc = r.src === "inhouse" ? 5 : 0, b = bill(lines, disc);
    var nat = NB.state.market === "ksa" ? check(r.src === "national" ? "info" : "ok", "Wasfaty dispense sync", r.src === "national" ? "Will post the dispense to Wasfaty on confirm · prescription WSF-2210-77841" : "Not a Wasfaty prescription; local record only") +
        check("warn", "SFDA RSD · scan GS1 DataMatrix", "1 of 2 packs scanned (GTIN 06285074001234 · SN 7731…). Dispense event posts within 1 min.")
      : NB.state.market === "uae" ? check("info", "DHA e-Prescription", L("Dispense posted to the eRx hub; claim lines carry Dubai Drug Codes.", "يُرسل الصرف إلى منصة الوصفات.", ""))
      : NB.state.market === "qatar" ? check("info", "MOPH e-Rx", "Dispense status updated on the national e-Rx record.")
      : check("info", "ABDM", L("Dispense record linked to the patient's ABHA when they have consented.", "", "मरीज़ की सहमति हो तो डिस्पेंस रिकॉर्ड ABHA से जुड़ता है।"));
    var billHtml = expired ? '<div class="empty">' + L("Nothing to bill.", "لا شيء للفوترة.", "बिल करने को कुछ नहीं।") + "</div>"
      : '<dl class="ph-sum"><dt>' + lines.length + " " + L("lines", "بنود", "लाइन") + (india() ? " · MRP" : "") + "</dt><dd>" + fmt(b.gross) + "</dd>" + (disc ? "<dt>" + L("Clinic patient discount", "خصم مرضى العيادة", "क्लिनिक मरीज़ छूट") + " " + disc + "%</dt><dd>− " + fmt(b.disc) + "</dd>" : "") + taxRows(b) +
        (r.ins ? "<dt>" + L("Insurer pays", "تدفع شركة التأمين", "बीमा देगा") + "</dt><dd>− " + fmt(b.total * 0.8) + "</dd>" : "") + '<dt style="align-self:center">' + L("Patient pays", "يدفع المريض", "मरीज़ देगा") + '</dt><dd class="tot">' + fmt(r.ins ? b.total * 0.2 : b.total) + "</dd></dl>" +
        '<div class="hr" style="margin:12px 0"></div>' + nat +
        (!sa && r.src === "inhouse" ? ui.toggle("phAddVisit_" + r.id, L("Add to the visit's single checkout", "أضف إلى دفع الزيارة", "विज़िट चेकआउट में जोड़ें"), L("Patient pays once at the front desk", "يدفع المريض مرة واحدة", "मरीज़ एक बार फ़्रंट डेस्क पर भुगतान करे"), true) : "");
    body += ui.card(L("5 · Bill", "5 · الفاتورة", "5 · बिल") + " · " + esc(m.tax), billHtml, { icon: "receipt" });

    var foot = ui.btn(L("Close", "إغلاق", "बंद करें"), { act: "close", cls: "ghost" });
    if (expired) foot += ui.btn(L("Message prescriber", "راسل الطبيب", "डॉक्टर को संदेश"), { act: "toast", arg: "Request for a fresh prescription sent to " + d.name, icon: "chat" }) + ui.btn(L("Log refusal", "سجّل الرفض", "अस्वीकृति दर्ज करें"), { cls: "dng", act: "toast", arg: "Refusal logged: prescription expired", icon: "stop" });
    else if (r.st === "ready") foot += ui.btn(L("Hand over & close", "سلّم وأغلق", "सौंपें और बंद करें"), { cls: "pri", act: "toast", arg: r.id + " collected · OTP verified", icon: "check" });
    else {
      foot += ui.btn(L("Save, keep picking", "احفظ وتابع", "सेव करें"), { act: "toast", arg: r.id + " saved as picking" });
      foot += ui.btn(verifyNeeded ? L("Verify first", "تحقق أولاً", "पहले सत्यापित करें") : partial ? L("Dispense available, keep balance", "اصرف المتاح واحتفظ بالمتبقي", "उपलब्ध दें, बाक़ी रखें") : L("Dispense & bill", "اصرف وأصدر الفاتورة", "दें और बिल करें"),
        { cls: "pri", act: "ph-dispense", arg: r.id, icon: "check", disabled: verifyNeeded });
    }
    return { kind: "drawer", size: "lg", title: L("Dispense", "صرف", "डिस्पेंस") + ' <span class="tok">' + r.id + "</span>", body: body, foot: foot };
  });
  NB.act("ph-batch", function (a) { var p = a.split("|"), o = {}; o["phBo_" + p[0] + "_" + p[1]] = p[2]; NB.setUI(o); });
  NB.act("ph-dispense", function (id) {
    var r = rxById(id), h1 = r.items.some(function (it) { var d = drug(it.m); return d.sched === "H1" || d.ctrl; });
    NB.state.overlay = null;
    NB.toast(id + " " + L("dispensed · labels printed · bill on WhatsApp", "صُرفت · طُبعت الملصقات", "दिया गया · लेबल छपे · बिल व्हाट्सऐप पर") + (h1 ? " · " + (india() ? "H1 register updated" : "controlled register updated") : "") + (NB.state.market === "ksa" ? " · Wasfaty synced" : ""));
  });

  NB.overlay("scanrx", function (mode) {
    var photo = mode === "photo", g = NB.gcc();
    return { kind: "modal", title: photo ? L("Paper prescription photo", "صورة وصفة ورقية", "काग़ज़ी पर्चे की फ़ोटो") : L("Scan e-Rx QR", "مسح رمز الوصفة", "e-Rx QR स्कैन करें"), body:
      '<div class="ph-cam">' + (photo ? ui.icon("camera", "xl faint") : '<span class="ph-qr">' + qrSvg(7, 110) + "</span>") + "</div>" +
      '<p class="small muted" style="margin:0">' + (photo ? L("Photograph the whole prescription with the doctor's name, registration number, signature and date in frame. It is attached to the bill and the register entry.", "صوّر الوصفة كاملة مع اسم الطبيب ورقمه وتوقيعه والتاريخ.", "पूरा पर्चा फ़ोटो करें: डॉक्टर का नाम, पंजीकरण संख्या, हस्ताक्षर और तारीख़ दिखें।")
        : L("Point the camera at the QR on the patient's phone or printout. The signature, prescriber registration and expiry are checked before anything is shown.", "وجّه الكاميرا نحو الرمز. يتم التحقق من التوقيع وتسجيل الطبيب والصلاحية أولاً.", "मरीज़ के फ़ोन या प्रिंट पर QR की ओर कैमरा करें। हस्ताक्षर, पंजीकरण और वैधता पहले जाँचे जाते हैं।")) + "</p>" +
      ui.field(L("Or type the Rx code", "أو اكتب رمز الوصفة", "या Rx कोड लिखें"), "", { ph: g ? "NRX-5xxxx" : "NRX-8xxxx" }),
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(photo ? L("Use sample photo", "استخدم صورة نموذجية", "नमूना फ़ोटो") : L("Simulate scan", "محاكاة المسح", "स्कैन सिमुलेट करें"), { cls: "pri", act: "ph-open", arg: photo ? "WA-PH-0311" : (g ? "NRX-55213" : "NRX-88213"), icon: photo ? "camera" : "qr" }) };
  });
  NB.act("ph-open", function (id) { NB.open("dispenserx", id); });

  // Deterministic fake QR / payment code (visual only).
  function qrSvg(seed, size) {
    var N = 25, cs = size / N, h = '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + " " + size + '" aria-hidden="true">', x = seed * 9301 + 49297;
    function fin(ox, oy) { return '<rect x="' + ox * cs + '" y="' + oy * cs + '" width="' + 7 * cs + '" height="' + cs + '"/><rect x="' + ox * cs + '" y="' + (oy + 6) * cs + '" width="' + 7 * cs + '" height="' + cs + '"/><rect x="' + ox * cs + '" y="' + oy * cs + '" width="' + cs + '" height="' + 7 * cs + '"/><rect x="' + (ox + 6) * cs + '" y="' + oy * cs + '" width="' + cs + '" height="' + 7 * cs + '"/><rect x="' + (ox + 2) * cs + '" y="' + (oy + 2) * cs + '" width="' + 3 * cs + '" height="' + 3 * cs + '"/>'; }
    for (var i = 0; i < N; i++) for (var j = 0; j < N; j++) {
      if ((i < 8 && j < 8) || (i > N - 9 && j < 8) || (i < 8 && j > N - 9)) continue;
      x = (x * 9301 + 49297) % 233280; if (x / 233280 > 0.52) h += '<rect x="' + (i * cs).toFixed(1) + '" y="' + (j * cs).toFixed(1) + '" width="' + cs.toFixed(1) + '" height="' + cs.toFixed(1) + '"/>';
    }
    return h + fin(0, 0) + fin(N - 7, 0) + fin(0, N - 7) + "</svg>";
  }
  NB.phQr = qrSvg;

  /* ================= SCREEN: Counter sale (POS) ================= */
  function cart() { if (!NB.state.ui.phCart) NB.state.ui.phCart = [{ id: "m3", q: 15 }, { id: "m7", q: 10 }, { id: "x1", q: 4 }, { id: "m2", q: 3 }]; return NB.state.ui.phCart; }
  NB.act("ph-add", function (id) { var cx = cart(), e = cx.find(function (x) { return x.id === id; }); if (e) e.q += 1; else cx.push({ id: id, q: drug(id).unit === "tab" || drug(id).unit === "cap" ? 10 : 1 }); NB.render(); });
  NB.act("ph-qty", function (a) { var p = a.split("|"), cx = cart(), e = cx[+p[0]]; if (!e) return; e.q = Math.max(1, e.q + +p[1]); NB.render(); });
  NB.act("ph-rm", function (i) { cart().splice(+i, 1); NB.render(); });
  NB.act("ph-clear", function () { NB.state.ui.phCart = []; NB.state.ui.phRx = null; NB.render(); });
  NB.act("ph-scanbc", function () { var cx = cart(); if (!cx.some(function (x) { return x.id === "x3"; })) cx.push({ id: "x3", q: 1 }); NB.toast("Scanned 8906001234567 · Glucometer test strips (50)"); });
  NB.act("ph-attach-do", function (id) { NB.state.ui.phRx = id; NB.close(); });
  NB.act("ph-sale", function () {
    var off = NB.v("phOffline", false);
    NB.toast(off ? L("Saved offline as PH-OFF-0008 · will sync when online", "حُفظت دون اتصال", "ऑफ़लाइन सेव PH-OFF-0008") : (india() ? "GST invoice PH/26-27/004812" : "Tax invoice PH-26-004812") + " · " + L("sent on WhatsApp", "أُرسلت عبر واتساب", "व्हाट्सऐप पर भेजा"));
    NB.state.ui.phCart = []; NB.state.ui.phRx = null;
  });

  NB.screen("pharmacist.pos", function (c) {
    var cx = cart(), rxAtt = NB.v("phRx", null), off = NB.v("phOffline", false), g = NB.gcc(), m = NB.mkt();
    var custId = NB.v("phCust", "p1"), cust = custId === "walk" ? null : NB.pat(custId);
    var h = modeBar() + ui.header(L("Counter sale", "البيع المباشر", "काउंटर बिक्री"), L("Over the counter and walk-in prescriptions · bill #", "بيع مباشر ووصفات · فاتورة #", "OTC और वॉक-इन पर्चे · बिल #") + ' <span class="num ltr">004812</span>',
      (off ? ui.pill("warn", L("Offline · 3 bills queued", "دون اتصال · 3 فواتير بالانتظار", "ऑफ़लाइन · 3 बिल क़तार में"), "wifioff") : ui.pill("ok", L("Online · synced", "متصل · متزامن", "ऑनलाइन · सिंक"), "cloudsync")) +
      ui.btn(off ? L("Back online", "عودة الاتصال", "ऑनलाइन करें") : L("Simulate outage", "محاكاة انقطاع", "आउटेज सिमुलेट करें"), { cls: "sm ghost", act: "ph-off", icon: off ? "refresh" : "wifioff" }) +
      ui.btn(L("Returns", "المرتجعات", "रिटर्न"), { cls: "sm", ov: "phreturn", icon: "history" }) + ui.btn(L("Held bills", "فواتير معلقة", "होल्ड बिल") + " · 2", { cls: "sm", act: "toast", arg: "2 held bills: Ravi (10:12), walk-in (10:20)", icon: "pause" }));
    if (off) h += ui.note("warn", "<b>" + L("You are offline.", "أنت غير متصل.", "आप ऑफ़लाइन हैं।") + "</b> " + L("Bills are saved on this device with a reserved invoice series (PH-OFF-0007 to 0050) and sync when the connection returns. Stock is checked against this device's last sync. UPI static QR and cash work; card works on the terminal's own SIM.", "تُحفظ الفواتير على الجهاز بسلسلة محجوزة وتُزامن عند عودة الاتصال.", "बिल इस डिवाइस पर आरक्षित सीरीज़ में सेव होते हैं और कनेक्शन लौटने पर सिंक होते हैं।"));

    // Left: search, quick tiles, cart
    var left = '<div class="ph-scan">' + ui.icon("barcode", "acc") + '<input aria-label="Search" placeholder="' + esc(L("Scan barcode, or type brand, salt or bin", "امسح الباركود أو اكتب الاسم", "बारकोड स्कैन करें या नाम/साल्ट लिखें")) + '">' +
      ui.btn(L("Scan", "مسح", "स्कैन"), { cls: "sm pri", act: "ph-scanbc", icon: "barcode" }) + "</div>";
    var quick = ["m3", "m7", "x1", "m9", "x10", "x2", "x14", "x9", "x3", "x6"];
    left += ui.card(L("Quick picks", "اختيارات سريعة", "जल्दी चुनें"), '<div class="ph-tiles">' + quick.map(function (id) {
      var d = drug(id), stk = stockOf(id);
      return '<button class="ph-tile" data-act="ph-add" data-arg="' + id + '"><b>' + esc(d.name) + "</b><small>" + L("Stock", "المخزون", "स्टॉक") + " " + n(stk) + " · " + esc(batchesOf(id).filter(function (b) { return dl(b.exp) >= 0; })[0] ? batchesOf(id).filter(function (b) { return dl(b.exp) >= 0; })[0].loc : "") + '</small><span class="row sb nw"><b class="small">' + packPrice(id) + "</b>" + schedPill(d) + "</span></button>";
    }).join("") + "</div>", { icon: "bolt" });
    var blocked = 0, lines = [];
    var cartHtml = cx.length ? cx.map(function (e, i) {
      var d = drug(e.id), al = fefo(e.id, e.q), b = al.picks[0], needRx = rxOnly(d), ok = !needRx || rxAtt;
      if (!ok) blocked++;
      lines.push({ d: d, q: e.q, price: mrpOf(e.id) });
      return '<div class="ph-cl"><div class="nm"><b>' + esc(d.name) + "</b><small>" + (b ? "B. " + b.b.batch + " · " + L("exp", "انتهاء", "एक्सपा.") + " " + fd(b.b.exp, true) + " · " + b.b.loc : L("Out of stock", "نفد", "स्टॉक ख़त्म")) + " · " + fmt2(mrpOf(e.id)) + "/" + esc(d.unit) + "</small></div>" +
        '<div class="ph-step"><button data-act="ph-qty" data-arg="' + i + '|-1" aria-label="Less">−</button><span>' + e.q + '</span><button data-act="ph-qty" data-arg="' + i + '|1" aria-label="More">+</button></div>' +
        '<div class="row nw"><b class="num">' + fmt(e.q * mrpOf(e.id)) + '</b><button class="ibtn" style="width:32px;height:32px" data-act="ph-rm" data-arg="' + i + '" title="Remove">' + ui.icon("close", "sm") + "</button></div>" +
        (needRx ? '<div class="bl row">' + schedPill(d) + (ok ? ui.pill("ok", L("Rx attached", "الوصفة مرفقة", "Rx जुड़ा") + " · " + rxAtt) + (d.sched === "H1" || d.ctrl ? '<span class="small faint">' + (india() ? L("Will be entered in the H1 register", "", "H1 रजिस्टर में दर्ज होगा") : L("Will be entered in the controlled-drug register", "سيُسجّل في سجل الأدوية المراقبة", "")) + "</span>" : "")
          : ui.pill("crit", L("Prescription required", "يتطلب وصفة", "पर्चा ज़रूरी"), "lock") + ui.btn(L("Attach Rx", "أرفق وصفة", "Rx जोड़ें"), { cls: "sm", ov: "attachrx", icon: "link" })) + "</div>" : "") + "</div>";
    }).join("") : '<div class="empty">' + L("Cart is empty. Scan a barcode or tap a quick pick.", "السلة فارغة.", "कार्ट ख़ाली है।") + "</div>";
    left += ui.card(L("Cart", "السلة", "कार्ट") + " · " + cx.length, cartHtml + (blocked ? ui.note("crit", "<b>" + blocked + " " + L("item needs a prescription.", "صنف يحتاج وصفة.", "आइटम को पर्चा चाहिए।") + "</b> " + (india() ? L("Schedule H/H1 drugs can only be sold against a registered practitioner's prescription (Drugs & Cosmetics Rules, rule 65). Attach an e-Rx, scan a QR or photograph the paper Rx.", "", "शेड्यूल H/H1 दवाएँ केवल पंजीकृत डॉक्टर के पर्चे पर (नियम 65)। e-Rx जोड़ें, QR स्कैन करें या काग़ज़ी पर्चे की फ़ोटो लें।")
      : L("Prescription-only medicines need a valid prescription linked to the sale.", "الأدوية التي تتطلب وصفة تحتاج وصفة صالحة مرتبطة بالبيع.", ""))) : ""), { icon: "cart", right: cx.length ? ui.btn(L("Clear", "مسح", "साफ़ करें"), { cls: "sm ghost", act: "ph-clear" }) : "" });

    // Right: customer, totals, payment
    var pts = cust ? P.loyalty[custId] || 0 : 0, redeem = cust && NB.v("phRedeem", false);
    var b = bill(lines, 0), red = redeem ? Math.min(pts, 200) / 10 : 0, pay = b.total - red;
    var right = ui.card(L("Customer", "العميل", "ग्राहक"), '<div class="field"><label>' + L("Mobile", "الجوال", "मोबाइल") + '</label><input value="' + esc(m.phone + " " + (cust ? cust.phone : "")) + '" aria-label="Mobile"></div>' +
      '<div class="chips" style="margin-top:10px">' + [["p1", NB.pname(NB.pat("p1"))], ["p3", NB.pname(NB.pat("p3"))], ["walk", L("Walk-in", "بدون حساب", "वॉक-इन")]].map(function (x) {
        return '<button class="chip ' + (custId === x[0] ? "on" : "") + '" data-set="phCust" data-val="' + x[0] + '">' + esc(x[1]) + "</button>"; }).join("") + "</div>" +
      (cust ? '<div class="lst" style="margin-top:8px">' + ui.li('<span class="av">' + ui.icon("star", "sm") + "</span>", n(pts) + " " + L("points", "نقطة", "पॉइंट") + " · " + (pts > 1000 ? "Gold" : "Silver"),
        L("1 point per", "نقطة لكل", "1 पॉइंट प्रति") + " " + NB.money(10).replace(/<[^>]+>/g, "") + (cust.allergies && cust.allergies.length ? " · " + L("Allergy", "حساسية", "एलर्जी") + ": " + esc(cust.allergies[0].a) : ""), "") + "</div>" +
        ui.toggle("phRedeem", L("Redeem 200 points", "استبدال 200 نقطة", "200 पॉइंट भुनाएँ"), "= " + NB.money(20).replace(/<[^>]+>/g, "") + " " + L("off this bill", "خصم", "छूट")) : '<p class="small faint" style="margin:10px 0 0">' + L("No phone: no loyalty, receipt printed only.", "بدون هاتف: لا نقاط.", "फ़ोन नहीं: कोई पॉइंट नहीं।") + "</p>"), { icon: "user" });
    var payOpts = NB.state.market === "india" ? [["upi", "UPI QR"], ["card", L("Card", "بطاقة", "कार्ड")], ["cash", L("Cash", "نقداً", "नक़द")], ["split", L("Split", "تقسيم", "स्प्लिट")]]
      : NB.state.market === "ksa" ? [["mada", "mada"], ["card", L("Card", "بطاقة")], ["cash", L("Cash", "نقداً")], ["split", L("Split", "تقسيم")]]
      : [["card", L("Card", "بطاقة")], ["wallet", "Apple Pay"], ["cash", L("Cash", "نقداً")], ["split", L("Split", "تقسيم")]];
    var pm = NB.v("phPay", payOpts[0][0]); if (!payOpts.some(function (x) { return x[0] === pm; })) pm = payOpts[0][0];
    var payBox = pm === "upi" ? '<div class="ph-pay"><span class="ph-qr">' + qrSvg(3, 120) + '</span><b class="num">' + fmt(pay) + '</b><small class="faint">' + L("Scan with any UPI app · confirms automatically", "", "किसी भी UPI ऐप से स्कैन करें · अपने-आप कन्फ़र्म") + "</small>" + ui.pill("sync", L("Waiting for payment", "بانتظار الدفع", "भुगतान की प्रतीक्षा")) + "</div>"
      : pm === "cash" ? '<div class="fgrid">' + ui.field(L("Tendered", "المدفوع", "दिया"), NB.money(Math.ceil(pay / 100) * 100).replace(/<[^>]+>/g, "")) + ui.field(L("Change", "الباقي", "वापसी"), NB.money(Math.ceil(pay / 100) * 100 - pay).replace(/<[^>]+>/g, "")) + "</div>"
      : pm === "split" ? '<div class="fgrid">' + ui.field(NB.state.market === "india" ? "UPI" : L("Card", "بطاقة"), NB.money(Math.round(pay * 0.6)).replace(/<[^>]+>/g, "")) + ui.field(L("Cash", "نقداً", "नक़द"), NB.money(pay - Math.round(pay * 0.6)).replace(/<[^>]+>/g, "")) + "</div>"
      : '<div class="ph-pay">' + ui.icon(pm === "wallet" ? "smartphone" : "card", "xl acc") + "<b>" + L("Terminal 2 ready", "الجهاز 2 جاهز", "टर्मिनल 2 तैयार") + '</b><small class="faint">' + L("Tap, insert or swipe", "انقر أو أدخل البطاقة", "टैप, इन्सर्ट या स्वाइप") + "</small></div>";
    right += ui.card(L("Bill", "الفاتورة", "बिल"), '<dl class="ph-sum"><dt>' + lines.length + " " + L("items", "أصناف", "आइटम") + (india() ? " · MRP " + L("incl. GST", "", "GST सहित") : "") + "</dt><dd>" + fmt(b.gross) + "</dd>" + taxRows(b) +
      (red ? "<dt>" + L("Points redeemed", "نقاط مستبدلة", "पॉइंट भुनाए") + "</dt><dd>− " + fmt(red) + "</dd>" : "") + "<dt>" + L("Round off", "تقريب", "राउंड ऑफ़") + "</dt><dd>" + fmt(Math.round(pay) - pay) + '</dd><dt style="align-self:center">' + L("Total", "الإجمالي", "कुल") + '</dt><dd class="tot">' + fmt(Math.round(pay)) + "</dd></dl>" +
      '<div class="hr" style="margin:14px 0"></div>' + ui.seg("phPay", payOpts, pm) + '<div style="margin-top:12px">' + payBox + "</div>" +
      '<div style="margin-top:14px">' + ui.btn(blocked ? L("Attach Rx to continue", "أرفق وصفة للمتابعة", "जारी रखने के लिए Rx जोड़ें") : L("Complete sale", "إتمام البيع", "बिक्री पूरी करें") + " · " + NB.money(Math.round(pay)).replace(/<[^>]+>/g, ""), { cls: "pri lg blk", act: "ph-sale", icon: "check", disabled: !!blocked || !cx.length }) + "</div>" +
      '<p class="tiny faint" style="margin:10px 0 0">' + (india() ? L("GST invoice with HSN per line. B2B sales above the threshold get an IRN e-invoice.", "", "हर लाइन पर HSN के साथ GST इनवॉइस।") : NB.state.market === "ksa" ? "ZATCA Fatoora phase 2: simplified tax invoice with QR, reported within 24 h." : esc(m.taxNote)) + "</p>", { icon: "receipt" });
    return h + '<div class="ph-pos"><div class="stack s16">' + left + '</div><div class="stack s16">' + right + "</div></div>";
  });
  NB.act("ph-off", function () { NB.setUI({ phOffline: !NB.v("phOffline", false) }); });

  NB.overlay("attachrx", function () {
    var list = rxList().filter(function (r) { return /new|picking|verify/.test(r.st); });
    return { kind: "modal", title: L("Attach a prescription", "إرفاق وصفة", "पर्चा जोड़ें"), body:
      '<div class="grid g2">' + ui.btn(L("Scan e-Rx QR", "مسح رمز الوصفة", "e-Rx QR स्कैन"), { cls: "blk", act: "ph-attach-do", arg: NB.gcc() ? "NRX-55213" : "NRX-88213", icon: "qr" }) + ui.btn(L("Photo of paper Rx", "صورة وصفة ورقية", "काग़ज़ी Rx फ़ोटो"), { cls: "blk", act: "ph-attach-do", arg: "PAPER-0192", icon: "camera" }) + "</div>" +
      '<div class="eyebrow">' + L("Or pick from today's prescriptions", "أو اختر من وصفات اليوم", "या आज के पर्चों से चुनें") + '</div><div class="lst">' + list.map(function (r) {
        var p = rxPat(r); return ui.li('<span class="av">' + ui.icon(srcLabel(r)[1], "sm") + "</span>", esc(p.name), r.id + " · " + esc(rxDoc(r).name), ui.icon("chevright", "sm faint flip"), { act: "ph-attach-do", arg: r.id });
      }).join("") + "</div>" + ui.note("info", L("Paper Rx: record the doctor's name and registration number. For H1 items the copy is kept with the register entry.", "سجّل اسم الطبيب ورقم تسجيله.", "काग़ज़ी Rx: डॉक्टर का नाम और पंजीकरण संख्या दर्ज करें।")),
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) };
  });

  NB.overlay("phreturn", function () {
    var reason = NB.v("phRetR", "unopened");
    return { kind: "drawer", title: L("Sales return", "مرتجع مبيعات", "बिक्री रिटर्न"), body:
      '<div class="fgrid">' + ui.field(L("Bill number or phone", "رقم الفاتورة أو الهاتف", "बिल नंबर या फ़ोन"), india() ? "PH/26-27/004790" : "PH-26-004790") + ui.field(L("Bill date", "تاريخ الفاتورة", "बिल तारीख़"), "16 Oct 2026 · 18:22") + "</div>" +
      ui.table([{ h: L("Item", "الصنف", "आइटम") }, { h: L("Sold", "مباع", "बेचा"), cls: "r" }, { h: L("Return", "إرجاع", "रिटर्न"), cls: "r" }, { h: "" }], [
        [ui.cellp("Cetirizine 10 mg tablet", "CTZ2411 · exp Dec 2026"), n(10), n(10), ui.pill("ok", L("Returnable", "قابل للإرجاع", "लौटाने योग्य"))],
        [ui.cellp("Paracetamol 650 mg tablet", "PCM2501 · exp Jan 2027"), n(15), n(0), ui.pill("mute", L("Kept", "محتفظ", "रखा"), false)],
        [ui.cellp("Insulin glargine pen", "INS2510 · cold chain"), n(1), n(0), ui.pill("perm", L("Not returnable", "غير قابل للإرجاع", "वापसी नहीं"))]
      ], { min: 480 }) +
      '<div class="stack s8">' + ui.radio("phRetR", "unopened", L("Unopened, sealed strip", "عبوة مغلقة", "बंद, सील्ड स्ट्रिप"), L("Goes back to sellable stock in its original batch", "يعود للمخزون", "मूल बैच में वापस स्टॉक में"), "unopened") +
      ui.radio("phRetR", "wrong", L("Wrong item dispensed", "صنف خاطئ", "ग़लत आइटम दिया"), L("Logged as a dispensing incident for review", "يُسجّل كحادث صرف", "डिस्पेंसिंग घटना के रूप में दर्ज"), "unopened") +
      ui.radio("phRetR", "damaged", L("Damaged or near expiry", "تالف أو قريب الانتهاء", "क्षतिग्रस्त या एक्सपायरी के क़रीब"), L("Moved to quarantine for supplier return", "يُنقل للحجر", "सप्लायर रिटर्न के लिए क्वारंटीन"), "unopened") + "</div>" +
      ui.note("info", L("Cold-chain items, opened packs and Schedule H1 / controlled drugs cannot be taken back. Refunds go to the original payment method; a credit note is issued.", "لا تُرجع أصناف السلسلة الباردة والمفتوحة والمراقبة.", "कोल्ड-चेन, खुले पैक और H1/नियंत्रित दवाएँ वापस नहीं होतीं। क्रेडिट नोट जारी होता है।")) +
      '<dl class="ph-sum"><dt>' + L("Refund", "المبلغ المسترد", "रिफ़ंड") + " · " + (india() ? "UPI" : L("Card", "بطاقة")) + '</dt><dd class="tot">' + fmt(18) + "</dd></dl>",
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Issue credit note", "إصدار إشعار دائن", "क्रेडिट नोट जारी करें") + (reason === "wrong" ? " + " + L("incident", "حادث", "घटना") : ""), { cls: "pri", act: "toast", arg: "Credit note CN-0217 issued · refund sent", icon: "check" }) };
  });

  /* ================= SCREEN: Stock & expiry ================= */
  function stockRows() {
    return allDrugs().filter(function (d) { return batchesOf(d.id).length && (d.id !== "x8" || NB.state.facType === "hospital"); }).map(function (d) {
      var bs = batchesOf(d.id), live = bs.filter(function (b) { return dl(b.exp) >= 0; }), q = live.reduce(function (s, b) { return s + b.qty; }, 0);
      var cost = live.reduce(function (s, b) { return s + b.qty * costOf(b); }, 0), mrp = live.reduce(function (s, b) { return s + b.qty * b.mrp; }, 0);
      return { d: d, bs: bs, q: q, cost: cost, mrp: mrp, ro: P.reorder[d.id] || 0, vel: P.velocity[d.id] || 0, last: P.lastSold[d.id] || 0, near: live.length ? dl(live[0].exp) : 999 };
    });
  }
  NB.screen("pharmacist.stock", function (c) {
    var rows = stockRows(), tab = NB.v("phSt", "near"), win = +NB.v("phWin", "90");
    var allB = batches().filter(function (b) { return b.drug !== "x8" || NB.state.facType === "hospital"; });
    var near = allB.filter(function (b) { var d = dl(b.exp); return d >= 0 && d <= win; }).sort(function (a, b) { return a.exp < b.exp ? -1 : 1; });
    var expd = allB.filter(function (b) { return dl(b.exp) < 0; });
    var below = rows.filter(function (r) { return r.q < r.ro; }), dead = rows.filter(function (r) { return r.last >= 60; });
    var val = rows.reduce(function (s, r) { return s + r.cost; }, 0), valM = rows.reduce(function (s, r) { return s + r.mrp; }, 0);
    var nearVal = allB.filter(function (b) { var d = dl(b.exp); return d >= 0 && d <= 90; }).reduce(function (s, b) { return s + b.qty * costOf(b); }, 0);
    var h = modeBar() + ui.header(L("Stock & expiry", "المخزون والصلاحية", "स्टॉक और एक्सपायरी"), rows.length + " SKUs · " + allB.length + " " + L("batches · valued at weighted average cost", "دفعة · بمتوسط التكلفة المرجح", "बैच · भारित औसत लागत पर"),
      ui.btn(L("Stock adjustment", "تسوية مخزون", "स्टॉक समायोजन"), { ov: "phadjust", icon: "sliders" }) + ui.btn(L("Return to supplier", "إرجاع للمورد", "सप्लायर को वापसी"), { ov: "phexpret", icon: "truck" }) + ui.btn(L("Export", "تصدير", "एक्सपोर्ट"), { cls: "ghost", act: "toast", arg: "Stock statement exported (CSV)", icon: "download" }));
    h += '<div class="grid g4 kpis">' + ui.kpi(fmt(val), L("Stock value at cost", "قيمة المخزون بالتكلفة", "लागत पर स्टॉक मूल्य"), L("MRP value", "بسعر البيع", "MRP मूल्य") + " " + NB.money(valM).replace(/<[^>]+>/g, ""), "faint") +
      ui.kpi(fmt(nearVal), L("Expiring within 90 days", "ينتهي خلال 90 يوماً", "90 दिन में एक्सपायर"), ui.icon("alertcircle", "xs") + " " + allB.filter(function (b) { var d = dl(b.exp); return d >= 0 && d <= 30; }).length + " " + L("batches within 30 days", "دفعات خلال 30 يوماً", "बैच 30 दिन में"), "warnc") +
      ui.kpi(n(below.length), L("Below reorder level", "تحت حد إعادة الطلب", "रीऑर्डर स्तर से नीचे"), L("draft PO ready", "أمر شراء جاهز", "PO ड्राफ़्ट तैयार"), "faint") +
      ui.kpi(n(expd.length), L("Expired batches in quarantine", "دفعات منتهية في الحجر", "क्वारंटीन में एक्सपायर्ड बैच"), L("blocked from sale", "محظورة من البيع", "बिक्री से रोके गए"), "critc") + "</div>";
    h += ui.tabs("phSt", [["near", L("Near expiry", "قرب الانتهاء", "एक्सपायरी क़रीब"), "hourglass"], ["expired", L("Expired", "منتهية", "एक्सपायर्ड"), "alertfill"], ["reorder", L("Below reorder", "تحت الحد", "रीऑर्डर से नीचे"), "truck"],
      ["dead", L("Dead stock", "مخزون راكد", "डेड स्टॉक"), "pause"], ["all", L("All stock", "كل المخزون", "पूरा स्टॉक"), "box"], ["value", L("Valuation", "التقييم", "मूल्यांकन"), "chart"]], "near");
    var bRow = function (b, act) {
      var d = drug(b.drug);
      return { ov: "batch", arg: b.drug, cls: dl(b.exp) < 0 ? "crit" : dl(b.exp) <= 30 ? "urg" : "", cells: [ui.cellp(esc(d.name), esc(d.gen || "") + " · " + esc(b.loc)), '<span class="num ltr">' + b.batch + "</span>", n(fd(b.exp, true)), expPill(b.exp), n(b.qty), fmt(b.qty * costOf(b)), act || ""] };
    };
    var bCols = [{ h: L("Item", "الصنف", "आइटम") }, { h: L("Batch", "الدفعة", "बैच") }, { h: L("Expiry", "الانتهاء", "एक्सपायरी") }, { h: L("Left", "المتبقي", "बाकी") }, { h: L("Qty", "الكمية", "मात्रा"), cls: "r" }, { h: L("Cost value", "قيمة التكلفة", "लागत मूल्य"), cls: "r" }, { h: "" }];
    var body = "";
    if (tab === "near") {
      body = '<div class="row sb">' + ui.seg("phWin", [["30", "≤ 30 " + L("days", "يوم", "दिन")], ["60", "≤ 60"], ["90", "≤ 90"]], "90") + '<span class="small faint">' + L("Suggested action per batch: return while the supplier still accepts, or move to the front shelf and discount.", "الإجراء المقترح لكل دفعة.", "हर बैच के लिए सुझाया कदम।") + "</span></div>" +
        ui.table(bCols, near.map(function (b) {
          var d = dl(b.exp), v = P.velocity[b.drug] || 0, sell = v * d;
          var act = sell >= b.qty ? ui.pill("ok", L("Will sell through", "سيُباع", "बिक जाएगा"), "check") : d <= 60 ? ui.btn(L("Return", "إرجاع", "वापसी"), { cls: "sm", ov: "phexpret", icon: "truck" }) : ui.btn(L("Front shelf + 10%", "واجهة + 10%", "आगे रखें + 10%"), { cls: "sm ghost", act: "toast", arg: "Moved to front shelf with 10% markdown", icon: "percent" });
          return bRow(b, act);
        }), { min: 820, empty: L("Nothing expires in this window.", "لا شيء ينتهي.", "इस अवधि में कुछ एक्सपायर नहीं।") });
    } else if (tab === "expired") {
      body = ui.note("crit", L("Expired batches are blocked at billing and dispensing and sit in the quarantine bin until returned or destroyed with a record.", "الدفعات المنتهية محظورة ومعزولة.", "एक्सपायर्ड बैच बिलिंग में रोके जाते हैं और क्वारंटीन में रहते हैं।")) +
        ui.table(bCols, expd.map(function (b) { return bRow(b, ui.btn(L("Return / destroy", "إرجاع / إتلاف", "वापसी / नष्ट"), { cls: "sm dng", ov: "phexpret", icon: "truck" })); }), { min: 820 });
    } else if (tab === "reorder") {
      body = ui.table([{ h: L("Item", "الصنف", "आइटम") }, { h: L("In stock", "المخزون", "स्टॉक"), cls: "r" }, { h: L("Reorder level", "حد الطلب", "रीऑर्डर स्तर"), cls: "r" }, { h: L("Sells / day", "مبيعات/يوم", "बिक्री/दिन"), cls: "r" }, { h: L("Days of cover", "أيام التغطية", "कवर दिन"), cls: "r" }, { h: "" }],
        below.map(function (r) { var cov = r.vel ? Math.floor(r.q / r.vel) : 99; return { ov: "batch", arg: r.d.id, cls: cov <= 3 ? "crit" : "", cells: [ui.cellp(esc(r.d.name), esc(r.d.gen || "")), n(r.q), n(r.ro), n(r.vel), cov <= 3 ? ui.pill("crit", cov + " " + L("days", "يوم", "दिन")) : n(cov), ui.btn(L("Add to PO", "أضف لأمر الشراء", "PO में जोड़ें"), { cls: "sm", go: "pharmacist.purchase", icon: "plus" })] }; }), { min: 720 });
    } else if (tab === "dead") {
      body = ui.note("info", L("No sale in 60+ days. Move them, swap with the distributor, or transfer to another branch before they expire.", "لا بيع منذ 60+ يوماً.", "60+ दिन से कोई बिक्री नहीं।")) +
        ui.table([{ h: L("Item", "الصنف", "आइटम") }, { h: L("Qty", "الكمية", "मात्रा"), cls: "r" }, { h: L("Last sold", "آخر بيع", "आख़िरी बिक्री") }, { h: L("Cost value", "قيمة التكلفة", "लागत मूल्य"), cls: "r" }, { h: "" }],
          dead.map(function (r) { return { ov: "batch", arg: r.d.id, cells: [ui.cellp(esc(r.d.name), esc(r.d.cls)), n(r.q), ui.pill(r.last > 90 ? "warn" : "mute", r.last + " " + L("days ago", "يوماً مضت", "दिन पहले"), "clockh"), fmt(r.cost), ui.btn(L("Swap with distributor", "استبدال مع الموزع", "डिस्ट्रीब्यूटर से बदलें"), { cls: "sm ghost", act: "toast", arg: "Swap request sent", icon: "refresh" })] }; }), { min: 640 });
    } else if (tab === "all") {
      body = ui.table([{ h: L("Item", "الصنف", "आइटम") }, { h: L("Schedule", "الجدول", "शेड्यूल") }, { h: L("Batches", "الدفعات", "बैच"), cls: "r" }, { h: L("Qty", "الكمية", "मात्रा"), cls: "r" }, { h: L("Reorder", "إعادة الطلب", "रीऑर्डर"), cls: "r" }, { h: L("Nearest expiry", "أقرب انتهاء", "निकटतम एक्सपायरी") }, { h: L("Value (cost)", "القيمة", "मूल्य"), cls: "r" }],
        rows.map(function (r) { return { ov: "batch", arg: r.d.id, cells: [ui.cellp(esc(r.d.name), esc(r.d.gen || "") + (r.d.cold ? " · 2–8°C" : "")), schedPill(r.d), n(r.bs.length), r.q < r.ro ? '<b class="warnc num">' + r.q + "</b>" : n(r.q), n(r.ro), r.near < 999 ? expPill(r.bs.filter(function (b) { return dl(b.exp) >= 0; })[0].exp) : "—", fmt(r.cost)] }; }), { min: 860 });
    } else {
      var cats = [["chronic", L("Chronic Rx", "أدوية مزمنة", "क्रॉनिक Rx")], ["acute", L("Acute Rx & antibiotics", "أدوية حادة ومضادات", "एक्यूट Rx")], ["otc", L("OTC & wellness", "بدون وصفة", "OTC और वेलनेस")], ["derma", L("Derma & cosmetics", "جلدية وتجميل", "डर्मा")], ["dev", L("Devices & strips", "أجهزة وشرائط", "डिवाइस")], ["ctrl", L("Controlled", "مراقبة", "नियंत्रित")]];
      body = ui.table([{ h: L("Category", "الفئة", "श्रेणी") }, { h: "SKUs", cls: "r" }, { h: L("Cost value", "قيمة التكلفة", "लागत मूल्य"), cls: "r" }, { h: L("MRP value", "قيمة البيع", "MRP मूल्य"), cls: "r" }, { h: L("Potential margin", "الهامش المحتمل", "संभावित मार्जिन"), cls: "r" }, { h: L("Share", "الحصة", "हिस्सा") }],
        cats.map(function (k) {
          var rs = rows.filter(function (r) { return cat(r.d) === k[0]; }), cv = rs.reduce(function (s, r) { return s + r.cost; }, 0), mv = rs.reduce(function (s, r) { return s + r.mrp; }, 0);
          return [esc(k[1]), n(rs.length), fmt(cv), fmt(mv), mv ? pct(Math.round((1 - cv / mv) * 100)) : "—", '<div style="min-width:90px">' + ui.bar(val ? cv / val * 100 : 0) + "</div>"];
        }).concat([["<b>" + L("Total", "الإجمالي", "कुल") + "</b>", n(rows.length), "<b>" + fmt(val) + "</b>", "<b>" + fmt(valM) + "</b>", pct(Math.round((1 - val / valM) * 100)), ""]]), { min: 720 }) +
        '<p class="small faint" style="margin:0">' + L("Method: weighted average cost per batch, excluding expired and quarantined stock.", "الطريقة: متوسط التكلفة المرجح.", "विधि: भारित औसत लागत।") + "</p>";
    }
    return h + body;
  });

  NB.overlay("batch", function (id) {
    var d = drug(id), bs = batchesOf(id), q = stockOf(id);
    return { kind: "drawer", size: "lg", title: esc(d.name), body:
      '<div class="chips">' + schedPill(d) + ui.pill("mute", d.cls || "—", false) + (d.cold ? ui.pill("info", "2–8°C", "thermo") : "") + ui.pill("mute", "HSN " + (d.hsn || "3004"), false) + ui.pill("mute", (india() ? "GST " : NB.mkt().tax + " ") + taxRate(d) + "%", false) + "</div>" +
      '<div class="grid g3">' + ui.kpi(n(q), L("Sellable units", "وحدات قابلة للبيع", "बिकने योग्य")) + ui.kpi(n(P.reorder[id] || 0), L("Reorder level", "حد الطلب", "रीऑर्डर स्तर")) + ui.kpi(n(P.velocity[id] || 0), L("Sold per day (30 d)", "مبيع يومياً", "रोज़ बिक्री")) + "</div>" +
      ui.table([{ h: L("Batch", "الدفعة", "बैच") }, { h: L("Expiry", "الانتهاء", "एक्सपायरी") }, { h: L("Qty", "الكمية", "मात्रा"), cls: "r" }, { h: "MRP", cls: "r" }, { h: L("Cost", "التكلفة", "लागत"), cls: "r" }, { h: L("Bin", "الموقع", "बिन") }, { h: "" }],
        bs.map(function (b, i) { return { cls: dl(b.exp) < 0 ? "crit" : "", cells: ['<span class="num ltr">' + b.batch + "</span>" + (i === bs.findIndex(function (x) { return dl(x.exp) >= 0; }) ? " " + ui.pill("acc", L("Next out", "التالي", "अगला"), "arrowright") : ""), n(fd(b.exp, true)) + " " + expPill(b.exp), n(b.qty), fmt2(b.mrp), fmt2(costOf(b)), esc(b.loc), ui.btn(L("Adjust", "تسوية", "समायोजन"), { cls: "sm ghost", ov: "phadjust", arg: b.batch })] }; }), { min: 600 }) +
      ui.card(L("Movements", "الحركات", "मूवमेंट"), '<div class="tl">' + [
        ["ok", "arrowright", L("Dispensed", "صُرف", "दिया गया") + " −10 · ERX-4085", "17 Oct 09:31 · R. Joshi"], ["acc", "truck", L("Received", "استلام", "प्राप्त") + " +200 · GRN-0981", "11 Oct 15:05 · " + supp()[0].name],
        ["warn", "sliders", L("Adjusted", "تسوية", "समायोजन") + " −2 · " + L("breakage", "كسر", "टूट-फूट"), "08 Oct 18:40 · " + L("approved by", "باعتماد", "अनुमोदन") + " A. Verma"], ["ok", "cart", L("Counter sale", "بيع مباشر", "काउंटर बिक्री") + " −15", "07 Oct 12:02"]
      ].map(function (e) { return '<div class="ev"><span class="dotc ' + e[0] + '">' + ui.icon(e[1], "sm") + '</span><div class="bd"><b>' + e[2] + "</b><small>" + esc(e[3]) + "</small></div></div>"; }).join("") + "</div>", { icon: "history" }),
      foot: ui.btn(L("Close", "إغلاق", "बंद करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Add to purchase order", "أضف لأمر الشراء", "PO में जोड़ें"), { cls: "pri", act: "toast", arg: d.name + " added to draft PO", icon: "plus" }) };
  });

  NB.overlay("phadjust", function (arg) {
    var r = NB.v("phAdjR", "count"), b = batches().find(function (x) { return x.batch === arg; }) || batches().find(function (x) { return x.batch === "COD2501"; }), d = drug(b.drug), ctl = d.sched === "H1" || d.ctrl;
    return { kind: "drawer", title: L("Stock adjustment", "تسوية المخزون", "स्टॉक समायोजन"), body:
      '<div class="fgrid">' + ui.field(L("Item", "الصنف", "आइटम"), d.name, { full: true }) + ui.field(L("Batch", "الدفعة", "बैच"), b.batch + " · exp " + fd(b.exp, true)) + ui.field(L("Book quantity", "الكمية الدفترية", "बुक मात्रा"), String(b.qty)) +
      ui.field(L("Physical count", "العد الفعلي", "भौतिक गिनती"), String(b.qty - 1)) + ui.field(L("Difference", "الفرق", "अंतर"), "−1") + "</div>" +
      '<div class="eyebrow">' + L("Reason", "السبب", "कारण") + '</div><div class="stack s8">' + [["count", L("Count correction (physical audit)", "تصحيح العد", "गिनती सुधार")], ["break", L("Breakage or damage", "كسر أو تلف", "टूट-फूट")], ["exp", L("Expired write-off", "شطب منتهي", "एक्सपायर्ड राइट-ऑफ़")],
        ["loss", L("Theft or loss", "سرقة أو فقد", "चोरी या नुक़सान")], ["sample", L("Free sample / scheme received", "عينة مجانية", "फ़्री सैंपल / स्कीम")]].map(function (x) { return ui.radio("phAdjR", x[0], x[1], "", "count"); }).join("") + "</div>" +
      ui.field(L("Note", "ملاحظة", "नोट"), "", { area: true, ph: L("What happened?", "ماذا حدث؟", "क्या हुआ?") }) +
      (ctl ? ui.note("warn", "<b>" + L("Controlled item.", "صنف مراقب.", "नियंत्रित आइटम।") + "</b> " + L("A second pharmacist must approve; the adjustment is written to the register with both names and cannot be deleted.", "يجب أن يعتمد صيدلي ثانٍ؛ ويُسجّل في السجل.", "दूसरे फ़ार्मासिस्ट की मंज़ूरी ज़रूरी; रजिस्टर में दोनों नाम के साथ दर्ज।") + (r === "loss" ? " " + L("Loss of a controlled drug must also be reported to the regulator.", "يجب إبلاغ الجهة التنظيمية.", "नियामक को भी सूचित करें।") : "")) : ""),
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(ctl ? L("Send for second approval", "أرسل للاعتماد الثاني", "दूसरी मंज़ूरी के लिए भेजें") : L("Save adjustment", "حفظ التسوية", "समायोजन सेव करें"), { cls: "pri", act: "toast", arg: ctl ? "Sent to A. Verma for approval" : "Adjustment saved · audit trail updated", icon: "check" }) };
  });

  function supp() {
    return NB.gcc() ? [
      { id: "s1", name: "Gulf Medical Distribution LLC", terms: 60, lead: "1 day", fill: 96, due: 41200, gst: "TRN 100219876500003" },
      { id: "s2", name: "Najm Pharma Trading", terms: 45, lead: "2 days", fill: 91, due: 18750, gst: "TRN 100388122300003" },
      { id: "s3", name: "Al Waha Medical Supplies", terms: 30, lead: "3 days", fill: 88, due: 6400, gst: "TRN 100511290400003" },
      { id: "s4", name: "Cityline Generics", terms: 30, lead: "1 day", fill: 94, due: 0, gst: "TRN 100672001900003" }]
      : [{ id: "s1", name: "Shree Balaji Pharma Distributors", terms: 21, lead: "Same day", fill: 96, due: 41200, gst: "GSTIN 29ABHFS2231K1ZQ" },
        { id: "s2", name: "MedLink Healthcare Agencies", terms: 30, lead: "1 day", fill: 91, due: 18750, gst: "GSTIN 29AAKCM8810P1Z2" },
        { id: "s3", name: "Kumar Surgicals & Devices", terms: 15, lead: "2 days", fill: 88, due: 6400, gst: "GSTIN 29AFLPK4410E1ZT" },
        { id: "s4", name: "Arogya Generics (PMBI stockist)", terms: 7, lead: "2 days", fill: 94, due: 0, gst: "GSTIN 29AAUFA0091H1Z9" }];
  }

  NB.overlay("phexpret", function () {
    var s = supp()[0], list = batches().filter(function (b) { var d = dl(b.exp); return d <= 60 && b.drug !== "x8"; }).sort(function (a, b) { return a.exp < b.exp ? -1 : 1; });
    var tot = list.reduce(function (t, b) { return t + b.qty * costOf(b); }, 0);
    return { kind: "drawer", size: "lg", title: L("Expiry return to supplier", "إرجاع المنتهي للمورد", "सप्लायर को एक्सपायरी वापसी"), body:
      '<div class="ph-flow"><span class="s done">' + ui.icon("check", "xs") + " " + L("Pick batches", "اختر الدفعات", "बैच चुनें") + '</span><span class="s cur">2 ' + L("Debit note", "إشعار مدين", "डेबिट नोट") + '</span><span class="s">3 ' + L("Distributor pickup", "استلام الموزع", "डिस्ट्रीब्यूटर पिकअप") + '</span><span class="s">4 ' + L("Credit note received", "استلام إشعار دائن", "क्रेडिट नोट प्राप्त") + "</span></div>" +
      '<div class="fgrid">' + ui.field(L("Supplier", "المورد", "सप्लायर"), s.name, { options: supp().map(function (x) { return x.name; }) }) + ui.field(L("Debit note", "إشعار مدين", "डेबिट नोट"), "DN-0142 · 17 Oct 2026") + "</div>" +
      ui.note("info", esc(s.name) + ": " + L("accepts returns up to 60 days before expiry and expired stock within 30 days, at 100% of purchase rate less 2% handling.", "يقبل الإرجاع حتى 60 يوماً قبل الانتهاء.", "एक्सपायरी से 60 दिन पहले तक और एक्सपायर्ड 30 दिन तक, ख़रीद दर पर 2% हैंडलिंग काटकर।")) +
      ui.table([{ h: L("Item", "الصنف", "आइटम") }, { h: L("Batch", "الدفعة", "बैच") }, { h: L("Expiry", "الانتهاء", "एक्सपायरी") }, { h: L("Qty", "الكمية", "मात्रा"), cls: "r" }, { h: L("Value", "القيمة", "मूल्य"), cls: "r" }, { h: "" }],
        list.map(function (b) { var d = drug(b.drug), ctl = d.sched === "H1" || d.ctrl; return [ui.cellp(esc(d.name), esc(b.loc)), '<span class="num ltr">' + b.batch + "</span>", expPill(b.exp), n(b.qty), fmt(b.qty * costOf(b)), ctl ? ui.pill("warn", L("Register entry", "قيد في السجل", "रजिस्टर एंट्री"), "scroll") : ui.pill("ok", L("Included", "مشمول", "शामिल"), "check")]; }), { min: 620 }) +
      '<dl class="ph-sum"><dt>' + L("Debit note value", "قيمة الإشعار", "डेबिट नोट मूल्य") + "</dt><dd>" + fmt(tot) + "</dd><dt>" + L("Handling 2%", "رسوم 2%", "हैंडलिंग 2%") + "</dt><dd>− " + fmt(tot * 0.02) + '</dd><dt style="align-self:center">' + L("Expected credit", "الرصيد المتوقع", "अपेक्षित क्रेडिट") + '</dt><dd class="tot">' + fmt(tot * 0.98) + "</dd></dl>",
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Print & send debit note", "اطبع وأرسل الإشعار", "डेबिट नोट प्रिंट करें और भेजें"), { cls: "pri", act: "toast", arg: "DN-0142 sent to " + s.name + " on WhatsApp · stock moved to returns bin", icon: "send" }) };
  });

  /* ================= SCREEN: Purchases & suppliers ================= */
  NB.screen("pharmacist.purchase", function (c) {
    var tab = NB.v("phPu", "suggest"), S = supp(), rows = stockRows();
    var h = modeBar() + ui.header(L("Purchases & suppliers", "المشتريات والموردون", "ख़रीद और सप्लायर"), L("Orders are suggested from reorder levels and 30-day sales velocity; deliveries are received against the PO with batch, expiry and MRP.", "تُقترح الطلبات من حدود إعادة الطلب وسرعة البيع.", "रीऑर्डर स्तर और 30-दिन की बिक्री से सुझाए गए ऑर्डर।"),
      ui.btn(L("Receive delivery (GRN)", "استلام شحنة", "डिलीवरी प्राप्त करें (GRN)"), { cls: "pri", ov: "grn", arg: "PO-2614", icon: "truck" }) + ui.btn(L("New PO", "أمر شراء جديد", "नया PO"), { act: "toast", arg: "Blank PO created", icon: "plus" }));
    h += ui.tabs("phPu", [["suggest", L("Suggested order", "الطلب المقترح", "सुझाया ऑर्डर"), "sparkle"], ["po", L("Purchase orders", "أوامر الشراء", "ख़रीद ऑर्डर"), "file"], ["grn", L("Goods receipt", "استلام البضائع", "माल प्राप्ति"), "truck"],
      ["sup", L("Suppliers", "الموردون", "सप्लायर"), "building"], ["pay", L("Payables", "المستحقات", "देय"), "wallet"]], "suggest");
    var b = "";
    if (tab === "suggest") {
      var sug = rows.filter(function (r) { return r.q < r.ro * 1.2 && r.vel > 0.3; }).map(function (r) {
        var lead = 2, target = Math.ceil(r.vel * 21 + r.ro * 0.5), qty = Math.max(0, target - r.q), step = r.vel < 2 ? 5 : 10; qty = Math.ceil(qty / step) * step;
        return { r: r, qty: qty, sup: S[{ x3: 2, x16: 3, x5: 1, m11: 1, x12: 1, m8: 3 }[r.d.id] || 0], rate: (batchesOf(r.d.id)[0] ? costOf(batchesOf(r.d.id)[0]) : r.d.price * 0.7), lead: lead };
      }).filter(function (x) { return x.qty > 0; });
      var tot = sug.reduce(function (s, x) { return s + x.qty * x.rate; }, 0);
      b = ui.note("ai", L("Suggested quantity = 21 days of average sales plus half the reorder level as safety stock, minus what is on hand. Seasonal lift for fever and cough lines is applied (+15% in October).", "الكمية المقترحة = مبيعات 21 يوماً + مخزون أمان.", "सुझाई मात्रा = 21 दिन की औसत बिक्री + सुरक्षा स्टॉक − मौजूदा स्टॉक।")) +
        ui.table([{ h: L("Item", "الصنف", "आइटम") }, { h: L("Stock", "المخزون", "स्टॉक"), cls: "r" }, { h: L("Reorder", "الحد", "रीऑर्डर"), cls: "r" }, { h: L("Sells/day", "يومياً", "रोज़"), cls: "r" }, { h: L("Order qty", "كمية الطلب", "ऑर्डर मात्रा"), cls: "r" }, { h: L("Best supplier", "أفضل مورد", "बेहतर सप्लायर") }, { h: L("Rate", "السعر", "दर"), cls: "r" }, { h: L("Value", "القيمة", "मूल्य"), cls: "r" }],
          sug.map(function (x) { return [ui.cellp(esc(x.r.d.name), x.r.vel ? Math.floor(x.r.q / x.r.vel) + " " + L("days of cover", "أيام تغطية", "दिन कवर") : ""), x.r.q < x.r.ro ? '<b class="warnc num">' + x.r.q + "</b>" : n(x.r.q), n(x.r.ro), n(x.r.vel), '<input class="ph-in num" value="' + x.qty + '" aria-label="Order quantity" style="max-width:80px">',
            ui.cellp(esc(x.sup.name), x.sup.id === "s1" ? L("scheme 10+1", "عرض 10+1", "स्कीम 10+1") : L("lead", "توريد", "लीड") + " " + esc(x.sup.lead)), fmt2(x.rate), fmt(x.qty * x.rate)]; }), { min: 960 }) +
        '<div class="row sb"><span class="muted">' + L("Total", "الإجمالي", "कुल") + " <b>" + fmt(tot) + "</b> · " + L("split into", "مقسمة إلى", "में बँटा") + " " + Array.from(new Set(sug.map(function (x) { return x.sup.id; }))).length + " " + L("POs by supplier", "أوامر حسب المورد", "सप्लायर-वार PO") + '</span><div class="btns">' +
        ui.btn(L("Save draft", "حفظ مسودة", "ड्राफ़्ट सेव"), { cls: "ghost", act: "toast", arg: "Draft saved" }) + ui.btn(L("Create POs and send on WhatsApp", "إنشاء وإرسال", "PO बनाएँ और व्हाट्सऐप पर भेजें"), { cls: "pri", act: "toast", arg: "POs created and sent to suppliers", icon: "send" }) + "</div></div>";
    } else if (tab === "po") {
      var POST = { draft: ["mute", L("Draft", "مسودة", "ड्राफ़्ट"), "edit"], sent: ["info", L("Sent", "مرسل", "भेजा"), "send"], part: ["warn", L("Partly received", "استلام جزئي", "आंशिक प्राप्त"), "hourglass"], recv: ["ok", L("Received", "مستلم", "प्राप्त"), "checkcircle"], closed: ["mute", L("Closed", "مغلق", "बंद"), "check"] };
      b = ui.table([{ h: "PO" }, { h: L("Supplier", "المورد", "सप्लायर") }, { h: L("Lines", "البنود", "लाइन"), cls: "r" }, { h: L("Value", "القيمة", "मूल्य"), cls: "r" }, { h: L("Expected", "المتوقع", "अपेक्षित") }, { h: L("Status", "الحالة", "स्थिति") }, { h: "" }],
        [["PO-2614", S[0], 6, 18400, "18 Oct · 11:00", "sent"], ["PO-2613", S[1], 4, 9250, L("Today", "اليوم", "आज") + " · 16:00", "part"], ["PO-2611", S[2], 2, 21300, "16 Oct", "recv"], ["PO-2609", S[3], 9, 6120, "14 Oct", "closed"], ["PO-2615", S[0], 3, 4800, "—", "draft"]].map(function (p) {
          var s = POST[p[5]];
          return [ '<span class="tok">' + p[0] + "</span>", ui.cellp(esc(p[1].name), L("credit", "ائتمان", "क्रेडिट") + " " + p[1].terms + " " + L("days", "يوم", "दिन")), n(p[2]), fmt(p[3]), n(p[4]), ui.pill(s[0], s[1], s[2]),
            p[5] === "sent" || p[5] === "part" ? ui.btn(L("Receive", "استلام", "प्राप्त करें"), { cls: "sm pri", ov: "grn", arg: p[0], icon: "truck" }) : ui.btn(L("Open", "فتح", "खोलें"), { cls: "sm ghost", act: "toast", arg: p[0] + " opened" }) ];
        }), { min: 820 });
    } else if (tab === "grn") {
      b = ui.note("ai", L("Photograph or upload the supplier invoice: lines, batches, expiry, MRP and rates are read and matched to the PO. You confirm each line.", "صوّر فاتورة المورد: تُقرأ البنود وتُطابق مع أمر الشراء.", "सप्लायर इनवॉइस की फ़ोटो लें: लाइन, बैच, एक्सपायरी, MRP पढ़कर PO से मिलाए जाते हैं।")) +
        ui.table([{ h: "GRN" }, { h: L("Supplier invoice", "فاتورة المورد", "सप्लायर इनवॉइस") }, { h: "PO" }, { h: L("Received", "الاستلام", "प्राप्त") }, { h: L("3-way match", "مطابقة ثلاثية", "3-तरफ़ा मिलान") }, { h: L("Value", "القيمة", "मूल्य"), cls: "r" }],
          [["GRN-0984", "SB/2627/8812", "PO-2613", L("Today", "اليوم", "आज") + " 10:15", ui.pill("warn", L("Rate differs on 1 line", "فرق سعر في بند", "1 लाइन पर दर अंतर")), 9050], ["GRN-0981", "KS/1104", "PO-2611", "16 Oct 15:05", ui.pill("ok", L("Matched", "مطابق", "मेल खाता")), 21300], ["GRN-0977", "AG/0931", "PO-2609", "14 Oct 12:40", ui.pill("ok", L("Matched", "مطابق", "मेल खाता")), 6120]].map(function (g) {
            return { ov: "grn", arg: g[2], cells: ['<span class="tok">' + g[0] + "</span>", '<span class="num ltr">' + g[1] + "</span>", g[2], g[3], g[4], fmt(g[5])] };
          }), { min: 760 });
    } else if (tab === "sup") {
      b = '<div class="grid g2">' + S.map(function (s) {
        return ui.card(esc(s.name), '<dl class="kv"><dt>' + L("Credit terms", "شروط الائتمان", "क्रेडिट शर्तें") + "</dt><dd>" + s.terms + " " + L("days", "يوم", "दिन") + "</dd><dt>" + L("Lead time", "مدة التوريد", "लीड टाइम") + "</dt><dd>" + esc(s.lead) + "</dd><dt>" + L("Fill rate (90 d)", "نسبة التلبية", "फ़िल रेट") + "</dt><dd>" + pct(s.fill) + "</dd><dt>" +
          L("Outstanding", "المستحق", "बकाया") + "</dt><dd>" + fmt(s.due) + "</dd><dt>" + L("Tax ID", "الرقم الضريبي", "टैक्स आईडी") + '</dt><dd class="ltr small">' + esc(s.gst) + "</dd></dl>" + '<div style="margin-top:10px">' + ui.bar(s.fill, s.fill < 90 ? "warn" : "ok") + "</div>", { icon: "truck", right: ui.btn(L("Order", "طلب", "ऑर्डर"), { cls: "sm ghost", act: "toast", arg: "New PO for " + s.name, icon: "plus" }) });
      }).join("") + "</div>";
    } else {
      b = '<div class="grid g3">' + ui.kpi(fmt(66350), L("Total payable", "إجمالي المستحق", "कुल देय")) + ui.kpi(fmt(18750), L("Due in 7 days", "مستحق خلال 7 أيام", "7 दिन में देय"), "", "warnc") + ui.kpi(fmt(6400), L("Overdue", "متأخر", "अतिदेय"), ui.icon("alertcircle", "xs") + " " + L("3 days", "3 أيام", "3 दिन"), "critc") + "</div>" +
        ui.table([{ h: L("Supplier", "المورد", "सप्लायर") }, { h: L("Invoice", "الفاتورة", "इनवॉइस") }, { h: L("Invoice date", "التاريخ", "तारीख़") }, { h: L("Due", "الاستحقاق", "देय") }, { h: L("Amount", "المبلغ", "राशि"), cls: "r" }, { h: "" }],
          [[S[2], "KS/1098", "29 Sep", ui.pill("crit", L("Overdue 3 d", "متأخر 3 أيام", "3 दिन अतिदेय")), 6400], [S[1], "MH/77120", "26 Sep", ui.pill("warn", L("Due in 4 d", "خلال 4 أيام", "4 दिन में")), 18750], [S[0], "SB/2627/8790", "07 Oct", ui.pill("info", L("Due 28 Oct", "28 أكتوبر", "28 अक्टूबर"), "calendar"), 22800], [S[0], "SB/2627/8812", "17 Oct", ui.pill("mute", L("Due 07 Nov", "7 نوفمبر", "07 नवंबर"), "calendar"), 18400]].map(function (x) {
            return [esc(x[0].name), '<span class="num ltr">' + x[1] + "</span>", n(x[2]), x[3], fmt(x[4]), ui.btn(L("Pay", "ادفع", "भुगतान"), { cls: "sm", act: "toast", arg: "Payment of " + NB.money(x[4]).replace(/<[^>]+>/g, "") + " scheduled", icon: "wallet" })];
          }), { min: 720 });
    }
    return h + b;
  });

  NB.overlay("grn", function (po) {
    var S = supp(), lines = [
      { m: "m4", ord: 300, rec: 300, free: 30, batch: "MET2509", exp: "2028-08-31", mrp: 2.1, oldMrp: 2.0, rate: 1.32 },
      { m: "m8", ord: 200, rec: 180, free: 0, batch: "THY2508", exp: "2027-03-31", mrp: 2.2, rate: 1.48 },
      { m: "m11", ord: 20, rec: 20, free: 0, batch: "SAL2507", exp: "2027-01-31", mrp: 160, rate: 108, rateDiff: 112 },
      { m: "x12", ord: 60, rec: 60, free: 6, batch: "MON2509", exp: "2028-07-31", mrp: 10.5, rate: 6.4 },
      { m: "m10", ord: 30, rec: 30, free: 0, batch: "ALP2509", exp: "2028-06-30", mrp: 5.4, rate: 3.5 }
    ];
    var inv = lines.reduce(function (s, l) { return s + l.rec * (l.rateDiff || l.rate); }, 0), poV = lines.reduce(function (s, l) { return s + l.ord * l.rate; }, 0), grnV = lines.reduce(function (s, l) { return s + l.rec * l.rate; }, 0);
    return { kind: "drawer", size: "lg", title: L("Goods receipt", "استلام البضائع", "माल प्राप्ति") + ' <span class="tok">GRN-0985</span>', body:
      '<div class="fgrid">' + ui.field(L("Supplier", "المورد", "सप्लायर"), S[0].name) + ui.field(L("Against PO", "مقابل أمر الشراء", "PO के विरुद्ध"), po || "PO-2614") + ui.field(L("Supplier invoice no.", "رقم فاتورة المورد", "सप्लायर इनवॉइस नं."), "SB/2627/8820") + ui.field(L("Invoice date", "تاريخ الفاتورة", "इनवॉइस तारीख़"), "17 Oct 2026") + "</div>" +
      ui.table([{ h: L("Item", "الصنف", "आइटम") }, { h: L("Ord / rec / free", "مطلوب/مستلم/مجاني", "ऑर्डर/प्राप्त/फ़्री") }, { h: L("Batch", "الدفعة", "बैच") }, { h: L("Expiry", "الانتهاء", "एक्सपायरी") }, { h: "MRP" }, { h: L("Rate", "السعر", "दर") }, { h: L("Check", "الفحص", "जाँच") }],
        lines.map(function (l) {
          var d = drug(l.m), mo = Math.round(dl(l.exp) / 30), flags = [];
          if (mo < 6) flags.push(ui.pill("warn", L("Short expiry", "انتهاء قريب", "कम एक्सपायरी") + " " + mo + " mo"));
          if (l.oldMrp) flags.push(ui.pill("info", "MRP " + l.oldMrp.toFixed(2) + " → " + l.mrp.toFixed(2), "info"));
          if (l.rec < l.ord) flags.push(ui.pill("warn", L("Short by", "ناقص", "कम") + " " + (l.ord - l.rec)));
          if (l.rateDiff) flags.push(ui.pill("urg", L("Invoice rate", "سعر الفاتورة", "इनवॉइस दर") + " " + l.rateDiff + " ≠ PO " + l.rate));
          if (d.sched === "H1") flags.push(ui.pill("warn", L("Register", "السجل", "रजिस्टर"), "scroll"));
          if (!flags.length) flags.push(ui.pill("ok", "OK", "check"));
          return [ui.cellp(esc(d.name), "HSN " + (d.hsn || "3004") + " · " + (india() ? "GST" : NB.mkt().tax) + " " + taxRate(d) + "%"), '<span class="num ltr">' + l.ord + " / " + l.rec + " / " + l.free + "</span>",
            '<input class="ph-in ltr" value="' + l.batch + '" aria-label="Batch">', '<input class="ph-in ltr' + (mo < 6 ? " bad" : "") + '" value="' + fd(l.exp, true) + '" aria-label="Expiry">', '<input class="ph-in num ltr" value="' + l.mrp.toFixed(2) + '" aria-label="MRP" style="max-width:80px">',
            '<input class="ph-in num ltr' + (l.rateDiff ? " bad" : "") + '" value="' + (l.rateDiff || l.rate).toFixed(2) + '" aria-label="Rate" style="max-width:80px">', '<div class="chips">' + flags.join("") + "</div>"];
        }), { min: 980 }) +
      ui.card(L("Invoice matching", "مطابقة الفاتورة", "इनवॉइस मिलान"), '<dl class="ph-sum"><dt>' + L("PO value", "قيمة أمر الشراء", "PO मूल्य") + "</dt><dd>" + fmt(poV) + "</dd><dt>" + L("Received (at PO rate)", "المستلم بسعر الأمر", "प्राप्त (PO दर पर)") + "</dt><dd>" + fmt(grnV) + "</dd><dt>" + L("Supplier invoice", "فاتورة المورد", "सप्लायर इनवॉइस") + "</dt><dd>" + fmt(inv) + "</dd><dt>" +
        L("Difference", "الفرق", "अंतर") + '</dt><dd class="warnc">' + fmt(inv - grnV) + "</dd></dl>" + ui.note("warn", L("Salbutamol inhaler billed above the agreed rate, and 20 Levothyroxine short. Accept the receipt and raise a debit note for the difference, or hold the invoice.", "سعر أعلى من المتفق ونقص 20 وحدة.", "सैल्बुटामोल इनहेलर तय दर से ऊपर, और 20 लेवोथायरॉक्सिन कम। डेबिट नोट बनाएँ या इनवॉइस रोकें।")) +
        '<div class="btns" style="margin-top:10px">' + ui.btn(L("Raise debit note", "إصدار إشعار مدين", "डेबिट नोट बनाएँ"), { cls: "sm", act: "toast", arg: "Debit note DN-0143 drafted", icon: "file" }) + "</div>", { icon: "scale" }) +
      ui.note("info", L("On posting: batches become sellable (FEFO), patients owed a balance get a WhatsApp, H1 items are added to the register's receipt column, and the invoice joins payables with its due date.", "عند الترحيل: تصبح الدفعات قابلة للبيع ويُبلغ المرضى.", "पोस्ट करने पर: बैच बिक्री योग्य, बकाया मरीज़ों को व्हाट्सऐप, H1 रजिस्टर अपडेट, इनवॉइस देय में।")),
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Hold invoice", "تعليق الفاتورة", "इनवॉइस होल्ड करें"), { act: "toast", arg: "Invoice held for rate dispute" }) + ui.btn(L("Post GRN", "ترحيل الاستلام", "GRN पोस्ट करें"), { cls: "pri", act: "toast", arg: "GRN-0985 posted · 2 patients notified of balance ready", icon: "check" }) };
  });

  /* ================= SCREEN: Statutory registers ================= */
  NB.screen("pharmacist.registers", function (c) {
    var ind = india(), mk = NB.state.market, hosp = NB.state.facType === "hospital" && !standalone();
    var tabs = ind ? [["h1", "Schedule H1", "scroll"], ["x", "Schedule X", "lock"], ["ndps", L("NDPS (narcotics)", "", "NDPS (नारकोटिक्स)"), "alertcircle"], ["recon", L("Balance check", "", "बैलेंस जाँच"), "scale"]]
      : [["cd", mk === "ksa" ? L("Narcotic & psychotropic register", "سجل المخدرات والمؤثرات العقلية") : L("Controlled drugs register", "سجل الأدوية المراقبة"), "scroll"], ["recon", L("Balance check", "مطابقة الأرصدة"), "scale"]];
    var tab = NB.v("phReg", tabs[0][0]); if (!tabs.some(function (t) { return t[0] === tab; })) tab = tabs[0][0];
    var regName = ind ? L("Statutory registers", "", "वैधानिक रजिस्टर") : L("Controlled-drug registers", "سجلات الأدوية المراقبة");
    var sub = ind ? L("Drugs & Cosmetics Rules: Schedule H1 entries kept 3 years and open to inspection. Every entry is written automatically when an H1 item is dispensed or sold.", "", "ड्रग्स एंड कॉस्मेटिक्स नियम: H1 एंट्री 3 साल तक, निरीक्षण के लिए उपलब्ध। हर H1 बिक्री पर अपने-आप दर्ज।")
      : mk === "ksa" ? "SFDA narcotic and psychotropic controls; every dispense also reported to RSD track & trace." : mk === "uae" ? L("Controlled and semi-controlled medicines (MOHAP / DHA). Entries are written automatically at dispense.", "الأدوية المراقبة وشبه المراقبة. تُسجل تلقائياً عند الصرف.") : L("MOPH controlled-medicine rules. Entries are written automatically at dispense.", "قواعد وزارة الصحة العامة للأدوية المراقبة.");
    var h = modeBar() + ui.header(regName, sub, ui.btn(L("Export for inspector (PDF)", "تصدير للمفتش", "निरीक्षक के लिए एक्सपोर्ट (PDF)"), { cls: "pri", act: "toast", arg: "Register exported: 1 Jul – 17 Oct 2026 · signed PDF", icon: "download" }) + ui.btn("CSV", { act: "toast", arg: "CSV exported", icon: "download" }));
    h += ui.tabs("phReg", tabs.map(function (t) { return [t[0], t[1], t[2]]; }), tabs[0][0]);
    var g = NB.gcc(), nid = NB.mkt().natId;
    var ent = [
      ["17 Oct 09:15", g ? "NRX-55102" : "NRX-88102", [g ? "Yousef Nasser" : "Ravi Shankar", g ? "Al Barsha · 50 221 7781" : "BTM Layout · 99001 33221", g ? mid("19817") : ""], [g ? "Dr. Sameer Haddad" : "Dr. Anil Kumar", regPrefix() + "30311"], "m10", 15, "ALP2503"],
      ["16 Oct 20:05", "ERX-4063", [NB.pname(NB.pat("p1")), "Koramangala · 98450 11223", g ? mid("19902") : ""], ["Dr. Meera Iyer", g ? regPrefix() + "48211" : "KMC-48211"], "m6", 30, "ISO2412"],
      ["16 Oct 19:20", "ERX-4071", [NB.pname(NB.pat("p8")), "HSR Layout · 98800 88990", g ? mid("19591") : ""], ["Dr. Vikram Shah", g ? regPrefix() + "39102" : "KMC-39102"], "x6", 10, "TRM2502"],
      ["16 Oct 12:05", g ? "NRX-54711" : "NRX-87711", [g ? "Mariam Saeed" : "Leela Krishnan", g ? "Jumeirah · 55 310 2290" : "Jayanagar · 98450 77812", g ? mid("19724") : ""], [g ? "Dr. Huda Karim" : "Dr. Sunil Rao", regPrefix() + "27731"], "x7", 1, "COD2501"],
      ["15 Oct 18:48", "ERX-4040", [NB.pname(NB.pat("p9")), "Indiranagar · 98861 99001", g ? mid("19859") : ""], ["Dr. Vikram Shah", g ? regPrefix() + "39102" : "KMC-39102"], "m10", 10, "ALP2503"],
      ["14 Oct 11:02", g ? "NRX-54608" : "NRX-87608", [g ? "Hassan Ali" : "Joseph D'Costa", g ? "Karama · 52 118 0042" : "Ejipura · 97400 11872", g ? mid("19683") : ""], [g ? "Dr. Sameer Haddad" : "Dr. Anil Kumar", regPrefix() + "30311"], "x7", 1, "COD2501"]
    ];
    var b = "";
    if (tab === "h1" || tab === "cd") {
      var rows = ent.filter(function (e) { return tab === "h1" || drug(e[4]).ctrl || drug(e[4]).cls === "Benzodiazepine"; });
      b = ui.table([{ h: L("Date & time", "التاريخ والوقت", "तारीख़ और समय") }, { h: "Rx" }, { h: L("Patient", "المريض", "मरीज़") }, { h: L("Prescriber", "الطبيب", "डॉक्टर") }, { h: L("Drug", "الدواء", "दवा") }, { h: L("Qty", "الكمية", "मात्रा"), cls: "r" }, { h: L("Batch", "الدفعة", "बैच") }, { h: L("Pharmacist", "الصيدلي", "फ़ार्मासिस्ट") }],
        rows.map(function (e) {
          return [n(e[0]), '<span class="tok">' + e[1] + "</span>", ui.cellp(esc(e[2][0]), esc(g ? nid + " " + e[2][2] : e[2][1])), ui.cellp(esc(e[3][0]), '<span class="ltr">' + esc(e[3][1]) + "</span>"), ui.cellp(esc(drug(e[4]).name), schedPill(drug(e[4]))), n(e[5]), '<span class="num ltr">' + e[6] + "</span>",
            ui.cellp("R. Joshi", ui.icon("pen", "xs") + " " + L("e-signed", "موقّع إلكترونياً", "ई-हस्ताक्षर"))];
        }), { min: 1000 }) +
        '<div class="row"><span class="pill ok">' + ui.icon("lock") + L("Append-only", "إضافة فقط", "केवल जोड़ें") + '</span><span class="small faint">' + L("Entries cannot be edited or deleted. A correction creates a linked amendment with reason, both visible to the inspector. Retained", "لا يمكن تعديل القيود أو حذفها. التصحيح يُنشئ تعديلاً مرتبطاً. مدة الحفظ", "एंट्री बदली या हटाई नहीं जा सकती। सुधार कारण सहित जुड़ी एंट्री बनाता है। अवधि") + " " + (ind ? L("3 years.", "", "3 साल।") : L("as required by the regulator.", "حسب متطلبات الجهة التنظيمية.")) + "</span></div>";
    } else if (tab === "x") {
      b = ui.note("info", L("Schedule X drugs need a prescription in duplicate; one copy is kept for 2 years. No Schedule X items are stocked at this pharmacy, so the register is empty.", "", "शेड्यूल X दवाओं के लिए दो प्रतियों में पर्चा; एक प्रति 2 साल रखें। यहाँ कोई शेड्यूल X आइटम स्टॉक में नहीं।")) + '<div class="empty">' + L("No entries.", "لا قيود.", "कोई एंट्री नहीं।") + "</div>";
    } else if (tab === "ndps") {
      b = hosp ? ui.table([{ h: L("Date", "التاريخ", "तारीख़") }, { h: L("Indent / Rx", "الطلب", "इंडेंट / Rx") }, { h: L("Ward / patient", "الجناح / المريض", "वार्ड / मरीज़") }, { h: L("Drug", "الدواء", "दवा") }, { h: L("Issued", "صُرف", "जारी"), cls: "r" }, { h: L("Balance", "الرصيد", "बैलेंस"), cls: "r" }, { h: L("Witness", "الشاهد", "गवाह") }],
        [["17 Oct 07:10", "IND-1192", "ICU-1 · Venkatesh Rao", "x8", 4, 50], ["16 Oct 21:40", "IND-1188", "A-02 · Lakshmi Devi", "x8", 2, 54], ["16 Oct 08:05", "IND-1181", "ICU-2 · Meenakshi P.", "x8", 4, 56]].map(function (e) {
          return [n(e[0]), '<span class="tok">' + e[1] + "</span>", esc(e[2]), ui.cellp(esc(drug(e[3]).name), schedPill(drug(e[3]))), n(e[4]), n(e[5]), "Sr. Mary Joseph"]; }), { min: 820 })
        : ui.note("warn", L("No NDPS licence on file for this pharmacy, so narcotic items cannot be purchased or sold. Hospital pharmacies with a licence keep the NDPS register here (with ward indents and witness signatures).", "", "इस फ़ार्मेसी के पास NDPS लाइसेंस नहीं है। लाइसेंस वाली अस्पताल फ़ार्मेसी यहाँ NDPS रजिस्टर रखती है।"));
    } else {
      var rc = [["m10", 75, 0, 25, 50, 50], ["x6", 50, 0, 10, 40, 40], ["x7", 16, 0, 2, 14, 13]];
      if (hosp) rc.push(["x8", 60, 0, 10, 50, 50]);
      b = ui.table([{ h: L("Drug", "الدواء", "दवा") }, { h: L("Opening (1 Oct)", "الرصيد الافتتاحي", "शुरुआती"), cls: "r" }, { h: L("Received", "المستلم", "प्राप्त"), cls: "r" }, { h: L("Issued", "المصروف", "जारी"), cls: "r" }, { h: L("Book balance", "الرصيد الدفتري", "बुक बैलेंस"), cls: "r" }, { h: L("Physical count", "العد الفعلي", "भौतिक गिनती"), cls: "r" }, { h: L("Status", "الحالة", "स्थिति") }],
        rc.map(function (r) { var ok = r[4] === r[5]; return { cls: ok ? "" : "crit", cells: [ui.cellp(esc(drug(r[0]).name), schedPill(drug(r[0]))), n(r[1]), n(r[2]), n(r[3]), n(r[4]), n(r[5]), ok ? ui.pill("ok", L("Matches", "مطابق", "मेल खाता")) : ui.pill("crit", L("Short by", "ناقص", "कम") + " " + (r[4] - r[5]))] }; }), { min: 760 }) +
        ui.note("crit", "<b>" + L("Codeine syrup: 1 bottle unaccounted for.", "شراب الكودايين: زجاجة غير محسوبة.", "कोडीन सिरप: 1 बोतल का हिसाब नहीं।") + "</b> " + L("Investigate within 24 hours: check today's sales, returns and breakage. If not found, file a loss report; a second pharmacist must sign the adjustment.", "تحقق خلال 24 ساعة؛ وإن لم توجد قدّم بلاغ فقد.", "24 घंटे में जाँचें; न मिले तो नुक़सान रिपोर्ट दर्ज करें।") +
          '<div class="btns" style="margin-top:8px">' + ui.btn(L("Start investigation", "بدء التحقيق", "जाँच शुरू करें"), { cls: "sm", act: "toast", arg: "Investigation opened · owner notified", icon: "search" }) + ui.btn(L("Adjust with approval", "تسوية باعتماد", "मंज़ूरी के साथ समायोजन"), { cls: "sm ghost", ov: "phadjust", arg: "COD2501", icon: "sliders" }) + "</div>");
    }
    var side = ui.card(L("Compliance", "الامتثال", "अनुपालन"), '<div class="lst">' +
      ui.li('<span class="av">' + ui.icon("shieldcheck", "sm") + "</span>", L("Licence on file", "الترخيص مسجل", "लाइसेंस दर्ज"), esc(standalone() ? storeInfo().lic.split(" · ")[0] : (ind ? "DL 20B/21B: KA-B12-204511" : "Facility pharmacy licence")), ui.pill("ok", L("Valid", "صالح", "वैध"))) +
      ui.li('<span class="av">' + ui.icon("user", "sm") + "</span>", L("Pharmacist in charge", "الصيدلي المسؤول", "प्रभारी फ़ार्मासिस्ट"), "Rahul Joshi · " + (ind ? "KSPC 61203" : regPrefix().replace("-P-", "-") + "PH-2210"), ui.pill("ok", L("On duty", "مناوب", "ड्यूटी पर"))) +
      ui.li('<span class="av">' + ui.icon("history", "sm") + "</span>", L("Last inspection", "آخر تفتيش", "पिछला निरीक्षण"), "12 Mar 2026 · " + L("no observations", "بلا ملاحظات", "कोई टिप्पणी नहीं"), "") + "</div>", { icon: "scroll" });
    return h + '<div class="split">' + '<div class="stack s16">' + b + "</div>" + side + "</div>";
  });

  /* ================= SCREEN: Sales & margins ================= */
  function barChart(vals, labels, hi) {
    var w = 640, h = 180, pb = 22, pt = 16, max = Math.max.apply(null, vals) * 1.1, bw = w / vals.length;
    var s = '<svg class="ph-chart" viewBox="0 0 ' + w + " " + h + '" direction="ltr" role="img" aria-label="Daily sales, last 14 days">';
    [0.5, 1].forEach(function (f) { var y = pt + (h - pb - pt) * (1 - f); s += '<line class="gl" x1="0" x2="' + w + '" y1="' + y + '" y2="' + y + '"/>'; });
    vals.forEach(function (v, i) {
      var bh = (h - pb - pt) * v / max, x = i * bw + bw * 0.18, y = h - pb - bh;
      s += '<rect class="' + (i === hi ? "bh" : "bx") + '" x="' + x.toFixed(1) + '" y="' + y.toFixed(1) + '" width="' + (bw * 0.64).toFixed(1) + '" height="' + bh.toFixed(1) + '" rx="3"><title>' + labels[i] + ": " + NB.money(v).replace(/<[^>]+>/g, "") + "</title></rect>";
      if (i % 2 === 1 || i === hi) s += '<text x="' + (i * bw + bw / 2).toFixed(1) + '" y="' + (h - 6) + '" text-anchor="middle">' + labels[i] + "</text>";
      if (i === hi) s += '<text x="' + (i === vals.length - 1 ? w : i * bw + bw / 2).toFixed(1) + '" y="' + (y - 5).toFixed(1) + '" text-anchor="' + (i === vals.length - 1 ? "end" : "middle") + '" style="fill:var(--tx)">' + NB.money(v).replace(/<[^>]+>/g, "") + "</text>";
    });
    return s + "</svg>";
  }
  NB.screen("pharmacist.insights", function (c) {
    var per = NB.v("phPer", "30"), k = per === "1" ? 1 / 30 : per === "7" ? 7 / 30 : 1, sa = standalone(), mk = NB.state.market;
    var sales = 1242000 * k, margin = 22.4, bills = Math.round(7480 * k);
    var days = [38200, 41800, 36900, 44100, 47600, 52300, 31800, 39400, 42700, 40100, 45900, 49800, 55200, 33400], lbl = ["4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17"];
    var h = modeBar() + ui.header(L("Sales & margins", "المبيعات والهوامش", "बिक्री और मार्जिन"), L("Net of returns · margin on weighted average cost", "صافي المرتجعات · الهامش على متوسط التكلفة", "रिटर्न घटाकर · भारित औसत लागत पर मार्जिन"),
      ui.seg("phPer", [["1", L("Today", "اليوم", "आज")], ["7", L("7 days", "7 أيام", "7 दिन")], ["30", L("30 days", "30 يوماً", "30 दिन")]], "30"));
    h += '<div class="grid g4 kpis">' + ui.kpi(fmt(sales), L("Net sales", "صافي المبيعات", "शुद्ध बिक्री"), ui.icon("arrowright", "xs") + " +8.2% " + L("vs previous", "مقارنة بالسابق", "पिछले से"), "okc") +
      ui.kpi(pct(margin), L("Gross margin", "الهامش الإجمالي", "सकल मार्जिन"), "+1.1 pt · " + L("generics mix", "مزيج الأدوية الجنيسة", "जेनेरिक मिक्स"), "okc") + ui.kpi(n(bills.toLocaleString("en-IN")), L("Bills", "الفواتير", "बिल"), L("avg", "متوسط", "औसत") + " " + NB.money(sales / bills).replace(/<[^>]+>/g, ""), "faint") +
      ui.kpi(fmt(4680 * k * 30 / 30), L("Lost sales (stock-outs)", "مبيعات ضائعة", "खोई बिक्री (स्टॉक-आउट)"), ui.icon("alertcircle", "xs") + " " + L("Metformin, Salbutamol", "ميتفورمين، سالبوتامول", "मेटफ़ॉर्मिन, सैल्बुटामोल"), "warnc") + "</div>";
    var chart = ui.card(L("Daily sales · last 14 days", "المبيعات اليومية · 14 يوماً", "रोज़ की बिक्री · 14 दिन"), barChart(days, lbl, 13) + '<p class="tiny faint" style="margin:6px 0 0">Oct 2026 · ' + L("today so far (till 10:30) highlighted", "اليوم حتى الآن", "आज अब तक (10:30 तक)") + "</p>", { icon: "chart" });
    var cats = [["chronic", L("Chronic Rx", "أدوية مزمنة", "क्रॉनिक Rx"), 41, 19.5], ["acute", L("Acute Rx & antibiotics", "أدوية حادة", "एक्यूट Rx"), 22, 21.8], ["otc", L("OTC & wellness", "بدون وصفة", "OTC और वेलनेस"), 17, 27.4], ["derma", L("Derma & cosmetics", "جلدية وتجميل", "डर्मा और कॉस्मेटिक"), 9, 31.2], ["dev", L("Devices & strips", "أجهزة", "डिवाइस और स्ट्रिप"), 7, 14.6], ["ctrl", india() ? L("Controlled (H1)", "", "नियंत्रित (H1)") : L("Controlled", "أدوية مراقبة"), 4, 18.2]];
    var marg = ui.card(L("Margin by category", "الهامش حسب الفئة", "श्रेणी अनुसार मार्जिन"), ui.table([{ h: L("Category", "الفئة", "श्रेणी") }, { h: L("Sales share", "حصة المبيعات", "बिक्री हिस्सा") }, { h: L("Sales", "المبيعات", "बिक्री"), cls: "r" }, { h: L("Margin", "الهامش", "मार्जिन"), cls: "r" }],
      cats.map(function (x) { return [esc(x[1]), '<div class="row nw"><div class="fl" style="min-width:60px">' + ui.bar(x[2] * 2) + '</div><span class="small num">' + x[2] + "%</span></div>", fmt(sales * x[2] / 100), pct(x[3])]; }), { min: 520 }) + '<p class="small muted" style="margin:12px 0 0">' + L("Generics across all categories: 18% of sales at 48% margin.", "الأدوية الجنيسة: 18% من المبيعات بهامش 48%.", "सभी श्रेणियों में जेनेरिक: 18% बिक्री, 48% मार्जिन।") + "</p>", { icon: "percent" });
    var top = ui.card(L("Top movers", "الأكثر مبيعاً", "टॉप मूवर्स"), '<div class="lst">' + [["m3", 930, 12], ["m4", 285, 4], ["x16", 204, 38], ["m7", 222, -3], ["x1", 195, 21], ["m12", 132, -18]].map(function (t, i) {
      var d = drug(t[0]);
      return ui.li('<span class="av">' + n(i + 1) + "</span>", esc(d.name), n(Math.round(t[1] * k)) + " " + esc(d.unit) + " · " + fmt(t[1] * k * mrpOf(t[0])), ui.pill(t[2] >= 0 ? "ok" : "warn", (t[2] >= 0 ? "+" : "") + t[2] + "%", t[2] >= 0 ? "arrowright" : "alertcircle"));
    }).join("") + '</div><p class="tiny faint" style="margin:8px 0 0">' + L("Generic atorvastatin is replacing the brand after substitution consent at the counter.", "الأتورفاستاتين الجنيس يحل محل العلامة التجارية.", "काउंटर पर सहमति के बाद जेनेरिक एटोरवास्टेटिन ब्रांड की जगह ले रहा है।") + "</p>", { icon: "star" });
    var tax;
    if (mk === "india") {
      var sl = [["3004", L("Medicaments", "", "दवाएँ"), 5, 0.905], ["3822", L("Diagnostic strips", "", "डायग्नोस्टिक स्ट्रिप"), 5, 0.04], ["3304", L("Sunscreen & cosmetics", "", "सनस्क्रीन और कॉस्मेटिक"), 18, 0.035], ["3808", L("Sanitiser", "", "सैनिटाइज़र"), 18, 0.01], ["3004", L("Life-saving (nil rated)", "", "जीवनरक्षक (शून्य दर)"), 0, 0.01]];
      tax = ui.card(L("GST summary (HSN-wise)", "", "GST सारांश (HSN-वार)"), ui.table([{ h: "HSN" }, { h: L("Description", "", "विवरण") }, { h: L("Rate", "", "दर"), cls: "r" }, { h: L("Taxable", "", "कर योग्य"), cls: "r" }, { h: "CGST", cls: "r" }, { h: "SGST", cls: "r" }],
        sl.map(function (x) { var t = sales * x[3] / (1 + x[2] / 100); return ['<span class="num">' + x[0] + "</span>", esc(x[1]), pct(x[2]), fmt(t), fmt(t * x[2] / 200), fmt(t * x[2] / 200)]; }), { min: 600 }) +
        '<div class="row" style="margin-top:12px">' + ui.pill("ok", L("GSTR-1 ready", "", "GSTR-1 तैयार"), "checkcircle") + ui.pill("ok", "GSTR-3B " + L("ready", "", "तैयार"), "checkcircle") + '<span class="small faint">' + L("Intra-state sales: CGST + SGST. B2B invoices to clinics carry IRN when over the e-invoice threshold.", "", "राज्य के भीतर बिक्री: CGST + SGST।") + "</span></div>", { icon: "receipt" });
    } else if (mk === "qatar") {
      tax = ui.card(L("Tax summary", "ملخص الضريبة"), ui.note("info", L("Qatar has no VAT today. Invoices still carry the pharmacy's licence and are kept for audit; the report switches on if VAT is introduced.", "لا توجد ضريبة قيمة مضافة في قطر حالياً.")), { icon: "receipt" });
    } else {
      var std = mk === "ksa" ? 15 : 5, zr = 0.71;
      tax = ui.card(L("VAT summary", "ملخص ضريبة القيمة المضافة"), ui.table([{ h: L("Supply", "التوريد") }, { h: L("Rate", "النسبة"), cls: "r" }, { h: L("Taxable value", "القيمة الخاضعة"), cls: "r" }, { h: "VAT", cls: "r" }],
        [[L("Standard-rated (OTC, cosmetics, devices)", "خاضع للنسبة الأساسية"), std, sales * (1 - zr) / (1 + std / 100)], [L("Zero-rated qualifying medicines", "أدوية مؤهلة بنسبة صفر"), 0, sales * zr]].map(function (x) { return [esc(x[0]), pct(x[1]), fmt(x[2]), fmt(x[2] * x[1] / 100)]; }), { min: 480 }) +
        '<div class="row" style="margin-top:12px">' + (mk === "ksa" ? ui.pill("ok", "ZATCA · " + bills.toLocaleString("en-IN") + " invoices reported", "checkcircle") : ui.pill("ok", L("VAT 201 return ready", "إقرار VAT 201 جاهز"), "checkcircle")) + '<span class="small faint">' + esc(NB.mkt().taxNote) + "</span></div>", { icon: "receipt" });
    }
    var shareRows = sa ? [[L("Walk-in OTC", "بيع مباشر", "वॉक-इन OTC"), 46], [L("Nabd network e-Rx (QR)", "وصفات شبكة نبض", "नब्ज़ नेटवर्क e-Rx"), 27], [L("Paper / WhatsApp photo Rx", "وصفات ورقية/صور", "काग़ज़ / फ़ोटो Rx"), 19], [L("Refills & home delivery", "إعادة صرف وتوصيل", "रीफ़िल और होम डिलीवरी"), 8]]
      : [[L("In-house e-Rx (this facility)", "وصفات داخلية", "इन-हाउस e-Rx"), 58], [L("Walk-in OTC", "بيع مباشر", "वॉक-इन OTC"), 24], [L("External Rx (QR, paper)", "وصفات خارجية", "बाहरी Rx"), 13], [L("Refills", "إعادة صرف", "रीफ़िल"), 5]];
    var share = ui.card(sa ? L("Where sales come from · standalone", "مصادر المبيعات · مستقلة", "बिक्री कहाँ से · स्टैंडअलोन") : L("Where sales come from · in-clinic", "مصادر المبيعات · داخل العيادة", "बिक्री कहाँ से · क्लिनिक"), '<div class="stack">' + shareRows.map(function (x) {
      return '<div><div class="row sb small"><span>' + esc(x[0]) + '</span><b class="num">' + x[1] + "%</b></div>" + ui.bar(x[1]) + "</div>";
    }).join("") + "</div>" + '<p class="small muted" style="margin:12px 0 0">' + (sa ? L("Network e-Rx share grew from 18% to 27% since joining Nabd; those patients return for refills 2.1× more often.", "زادت حصة وصفات الشبكة من 18% إلى 27%.", "नब्ज़ से जुड़ने के बाद नेटवर्क e-Rx 18% से 27% हुआ।")
      : L("Capture rate: 81% of prescriptions written in this facility were filled here (patients may choose any pharmacy).", "نسبة الالتقاط: 81% من الوصفات صُرفت هنا.", "कैप्चर रेट: इस सुविधा में लिखे 81% पर्चे यहीं भरे गए (मरीज़ कोई भी फ़ार्मेसी चुन सकते हैं)।")) + "</p>", { icon: sa ? "store" : "building" });
    return h + '<div class="grid g2">' + chart + share + "</div>" + '<div class="grid g2">' + marg + top + "</div>" + tax;
  });
})();
