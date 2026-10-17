/* Nabd prototype core: state, roles & navigation, screen registry, shell, overlays, actions.
   Modules call NB.screen(), NB.overlay(), NB.act() to add their screens. See prototype/README.md. */
window.NB = window.NB || {};
(function () {
  "use strict";

  /* ---------------- State ---------------- */
  var store = (function () { try { return window.localStorage; } catch (e) { return null; } })();
  function load(k, d) { try { var v = store && store.getItem("nb." + k); return v ? JSON.parse(v) : d; } catch (e) { return d; } }
  function save(k, v) { try { store && store.setItem("nb." + k, JSON.stringify(v)); } catch (e) {} }

  NB.state = {
    role: "map", view: "home", lang: load("lang", "en"), device: "auto", market: load("market", "india"),
    facType: load("facType", "poly"), theme: load("theme", "dark"), facility: 0, overlay: null, ovArg: null, palette: false, toast: null, ui: {}
  };
  NB.v = function (k, d) { return k in NB.state.ui ? NB.state.ui[k] : d; };
  NB.set = function (patch, ui) {
    if (ui) Object.assign(NB.state.ui, patch); else Object.assign(NB.state, patch);
    ["lang", "market", "facType", "theme"].forEach(function (k) { if (!ui && k in patch) save(k, patch[k]); });
    NB.render();
  };
  NB.setUI = function (patch) { NB.set(patch, true); };

  /* ---------------- i18n ---------------- */
  // L("English", "Arabic", "Hindi") picks the string for the current language (falls back to English).
  NB.L = function (en, ar, hi) { var l = NB.state.lang; return l === "ar" ? (ar || en) : l === "hi" ? (hi || en) : en; };
  NB.rtl = function () { return NB.state.lang === "ar"; };
  NB.pname = function (p) { return NB.rtl() && p.ar ? p.ar : p.name; };
  NB.dname = function (d) { return NB.rtl() && d.ar ? d.ar : d.name; };

  /* ---------------- Market & money ---------------- */
  NB.mkt = function () { return NB.MARKETS[NB.state.market] || NB.MARKETS.india; };
  NB.gcc = function () { return NB.state.market !== "india"; };
  // Amounts in sample data are INR-scale; convert for display in the chosen market.
  NB.money = function (inr, raw) {
    var m = NB.mkt(), v = raw ? inr : inr * m.fx;
    var s = v.toLocaleString("en-IN", { minimumFractionDigits: m.id === "india" ? 0 : 2, maximumFractionDigits: m.id === "india" ? 0 : 2 });
    return '<span class="num ltr">' + (m.id === "india" ? "₹" + s : s + " " + m.cur) + "</span>";
  };
  NB.facilities = function () { return NB.D.facilities[NB.state.market] || NB.D.facilities.india; };
  NB.fac = function () { return NB.facilities()[NB.state.facility] || NB.facilities()[0]; };

  /* ---------------- HTML helpers ---------------- */
  var ui = NB.ui = {};
  ui.esc = function (s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); };
  ui.icon = function (n, cls) { return '<svg class="ico ' + (cls || "") + '" aria-hidden="true"><use href="#i-' + n + '"></use></svg>'; };
  var PI = { crit: "alertfill", urg: "bolt", warn: "alertcircle", ok: "checkcircle", info: "info", sync: "cloudsync", perm: "lock", sub: "sparkle", acc: "dot", mute: "dot" };
  // Status pill: icon + text always (colour is never the only carrier of meaning).
  ui.pill = function (kind, text, icon) {
    var ic = icon === false ? "" : kind === "sync" ? '<span class="pulse"></span>' : ui.icon(icon || PI[kind] || "dot");
    return '<span class="pill ' + kind + '">' + ic + ui.esc(text) + "</span>";
  };
  ui.btn = function (label, o) {
    o = o || {};
    var attrs = (o.act ? ' data-act="' + o.act + '"' : "") + (o.arg != null ? ' data-arg="' + ui.esc(o.arg) + '"' : "") +
      (o.ov ? ' data-ov="' + o.ov + '"' : "") + (o.go ? ' data-go="' + o.go + '"' : "") + (o.disabled ? " disabled" : "") +
      (o.title ? ' title="' + ui.esc(o.title) + '"' : "");
    return '<button class="btn ' + (o.cls || "") + '"' + attrs + ">" + (o.icon ? ui.icon(o.icon, "sm") : "") + ui.esc(label) + "</button>";
  };
  ui.av = function (name, cls) {
    var n = String(name || "?").replace(/^(Dr\.|د\.)\s*/, "").split(/\s+/);
    return '<span class="av ' + (cls || "") + '">' + ui.esc((n[0] || "")[0] + ((n[1] || "")[0] || "")) + "</span>";
  };
  ui.kpi = function (v, label, delta, kind) {
    return '<div class="card kpi"><span class="l">' + ui.esc(label) + '</span><span class="v">' + v + "</span>" +
      (delta ? '<span class="d ' + (kind || "faint") + '">' + delta + "</span>" : "") + "</div>";
  };
  // Table: cols = [{h, cls}], rows = [[cell html...]] or [{cells, cls, act, arg}]
  ui.table = function (cols, rows, o) {
    o = o || {};
    var h = '<div class="tw"><table class="t"' + (o.min ? ' style="min-width:' + o.min + 'px"' : "") + "><thead><tr>" +
      cols.map(function (c) { return "<th" + (c.cls ? ' class="' + c.cls + '"' : "") + ">" + ui.esc(c.h || "") + "</th>"; }).join("") + "</tr></thead><tbody>";
    rows.forEach(function (r) {
      var cells = Array.isArray(r) ? r : r.cells;
      var tr = Array.isArray(r) ? "" : (r.cls ? ' class="' + r.cls + '"' : "") + (r.act ? ' data-act="' + r.act + '" data-arg="' + ui.esc(r.arg) + '" style="cursor:pointer"' : "") + (r.ov ? ' data-ov="' + r.ov + '" data-arg="' + ui.esc(r.arg) + '" style="cursor:pointer"' : "");
      h += "<tr" + tr + ">" + cells.map(function (c, i) { return "<td" + (cols[i] && cols[i].cls ? ' class="' + cols[i].cls + '"' : "") + ">" + c + "</td>"; }).join("") + "</tr>";
    });
    if (!rows.length) h += '<tr><td colspan="' + cols.length + '"><div class="empty">' + ui.esc(o.empty || "Nothing here yet.") + "</div></td></tr>";
    return h + "</tbody></table></div>";
  };
  ui.cellp = function (title, sub) { return '<div class="cellp"><b>' + title + "</b>" + (sub ? "<small>" + sub + "</small>" : "") + "</div>"; };
  // Segmented filter bound to a UI key: items = [[value, label, count?]]
  ui.seg = function (key, items, def) {
    var cur = NB.v(key, def != null ? def : items[0][0]);
    return '<div class="seg" role="tablist">' + items.map(function (it) {
      return '<button role="tab" class="' + (cur === it[0] ? "on" : "") + '" data-set="' + key + '" data-val="' + ui.esc(it[0]) + '">' + ui.esc(it[1]) +
        (it[2] != null ? '<span class="cnt">' + it[2] + "</span>" : "") + "</button>";
    }).join("") + "</div>";
  };
  ui.tabs = function (key, items, def) {
    var cur = NB.v(key, def != null ? def : items[0][0]);
    return '<div class="tabs" role="tablist">' + items.map(function (it) {
      return '<button role="tab" class="' + (cur === it[0] ? "on" : "") + '" data-set="' + key + '" data-val="' + ui.esc(it[0]) + '">' + (it[2] ? ui.icon(it[2], "sm") : "") + ui.esc(it[1]) + "</button>";
    }).join("") + "</div>";
  };
  // Toggle row bound to a UI key.
  ui.toggle = function (key, title, sub, def) {
    var on = NB.v(key, !!def);
    return '<div class="tog" data-toggle="' + key + '" role="switch" aria-checked="' + on + '" tabindex="0"><div class="t"><b>' + ui.esc(title) + "</b>" +
      (sub ? "<small>" + ui.esc(sub) + "</small>" : "") + '</div><span class="sw ' + (on ? "on" : "") + '"></span></div>';
  };
  // Radio card bound to a UI key.
  ui.radio = function (key, val, title, sub, def) {
    var on = NB.v(key, def) === val;
    return '<div class="radio ' + (on ? "on" : "") + '" data-set="' + key + '" data-val="' + ui.esc(val) + '" role="radio" aria-checked="' + on + '" tabindex="0"><span class="dot"></span><div class="fl"><b>' +
      ui.esc(title) + "</b>" + (sub ? "<small>" + ui.esc(sub) + "</small>" : "") + "</div></div>";
  };
  ui.field = function (label, value, o) {
    o = o || {};
    var id = "f-" + (o.id || label.toLowerCase().replace(/[^a-z0-9]+/g, "-"));
    var ctl = o.options ? '<select id="' + id + '">' + o.options.map(function (x) { return "<option" + (x === value ? " selected" : "") + ">" + ui.esc(x) + "</option>"; }).join("") + "</select>"
      : o.area ? '<textarea id="' + id + '" placeholder="' + ui.esc(o.ph || "") + '">' + ui.esc(value || "") + "</textarea>"
      : '<input id="' + id + '" value="' + ui.esc(value || "") + '" placeholder="' + ui.esc(o.ph || "") + '"' + (o.type ? ' type="' + o.type + '"' : "") + ">";
    return '<div class="field' + (o.full ? " full" : "") + '"><label for="' + id + '">' + ui.esc(label) + "</label>" + ctl + "</div>";
  };
  ui.note = function (kind, html, icon) { return '<div class="note ' + kind + '">' + ui.icon(icon || { info: "info", warn: "alertcircle", crit: "alertfill", ok: "checkcircle", ai: "sparkle" }[kind]) + "<div>" + html + "</div></div>"; };
  ui.header = function (title, sub, actions, eyebrow) {
    return '<div class="ph"><div class="tt">' + (eyebrow ? '<div class="eyebrow">' + eyebrow + "</div>" : "") + "<h1>" + title + "</h1>" + (sub ? "<p><bdi>" + sub + "</bdi></p>" : "") + "</div>" + (actions ? '<div class="btns">' + actions + "</div>" : "") + "</div>";
  };
  ui.card = function (title, body, o) {
    o = o || {};
    return '<div class="card ' + (o.cls || "") + '">' + (title ? '<div class="ch"><span class="eyebrow">' + (o.icon ? ui.icon(o.icon, "sm") : "") + title + "</span>" + (o.right || "") + "</div>" : "") + body + "</div>";
  };
  ui.li = function (lead, title, sub, right, o) {
    o = o || {};
    var a = o.act ? ' data-act="' + o.act + '" data-arg="' + ui.esc(o.arg) + '"' : o.ov ? ' data-ov="' + o.ov + '" data-arg="' + ui.esc(o.arg) + '"' : o.go ? ' data-go="' + o.go + '"' : "";
    return '<div class="li' + (a ? " click" : "") + '"' + a + ">" + (lead || "") + '<div class="t"><b>' + title + "</b>" + (sub ? "<small>" + sub + "</small>" : "") + "</div>" + (right || "") + "</div>";
  };
  ui.bar = function (pct, kind) { return '<div class="bar ' + (kind || "") + '"><i style="width:' + Math.max(0, Math.min(100, pct)) + '%"></i></div>'; };

  /* ---------------- Roles & navigation (the whole product map) ---------------- */
  // Each nav item: [view, icon, en, ar, hi, badge?]. Screens are registered as "<role>.<view>".
  NB.ROLES = {
    reception: { group: "Clinic team", icon: "arrivals", en: "Receptionist", ar: "الاستقبال", hi: "रिसेप्शन", person: "Sneha Kulkarni", title: "Front desk",
      blurb: "Runs the live queue, accepts bookings, registers walk-ins and checks patients out.",
      nav: [["arrivals", "arrivals", "Today · Arrivals", "اليوم · الوصول", "आज · आगमन", 9], ["requests", "bell", "Booking requests", "طلبات الحجز", "बुकिंग अनुरोध", 4],
        ["calendar", "calendar", "Appointments", "المواعيد", "अपॉइंटमेंट"], ["patients", "users", "Patients", "المرضى", "मरीज़"],
        ["checkin", "qr", "Self check-in", "تسجيل ذاتي", "सेल्फ़ चेक-इन"], ["display", "tv", "Waiting-room display", "شاشة الانتظار", "प्रतीक्षा स्क्रीन"],
        ["messages", "chat", "WhatsApp inbox", "رسائل واتساب", "व्हाट्सऐप इनबॉक्स", 3], ["billing", "receipt", "Checkout & billing", "الدفع والفواتير", "चेकआउट और बिलिंग"]] },
    doctor: { group: "Clinic team", icon: "steth", en: "Doctor", ar: "الطبيب", hi: "डॉक्टर", person: "Dr. Vikram Shah", title: "General Medicine",
      blurb: "Sees the day, consults with AI safety checks, prescribes in under 90 seconds, reviews results and record requests.",
      nav: [["myday", "sun", "My day", "يومي", "मेरा दिन"], ["consult", "steth", "Consultation", "الاستشارة", "परामर्श"], ["patients", "users", "My patients", "مرضاي", "मेरे मरीज़"],
        ["results", "flask", "Results inbox", "النتائج", "रिपोर्ट", 5], ["inpatients", "bed", "Ward rounds", "جولات الأجنحة", "वार्ड राउंड"],
        ["rx", "pill", "Rx favourites & templates", "الوصفات المفضلة", "पसंदीदा नुस्खे"], ["schedule", "calendar", "My schedule & leave", "جدولي", "मेरा शेड्यूल"],
        ["records", "share", "Record requests", "طلبات السجلات", "रिकॉर्ड अनुरोध", 1], ["specialty", "tooth", "Specialty workspace", "مساحة التخصص", "विशेषज्ञता कार्यक्षेत्र"],
        ["recalls", "refresh", "Recalls & follow-ups", "الاستدعاء والمتابعة", "रिकॉल और फ़ॉलो-अप", 12], ["tele", "monitor", "Teleconsult", "استشارة عن بعد", "टेली-परामर्श"]] },
    nurse: { group: "Clinic team", icon: "heartpulse", en: "Nurse / triage", ar: "التمريض", hi: "नर्स", person: "Anita D'Souza", title: "OPD nurse",
      blurb: "Triages arrivals, records vitals, runs nursing orders, vaccines and procedure sessions.",
      nav: [["triage", "heartpulse", "Triage & vitals", "الفرز والعلامات", "ट्राइएज और वाइटल्स", 3], ["orders", "clipboard", "Nursing orders", "أوامر التمريض", "नर्सिंग आदेश"],
        ["procedures", "syringe", "Procedures & vaccines", "الإجراءات واللقاحات", "प्रक्रियाएँ और टीके"], ["inbox", "chat", "Clinical inbox", "الوارد السريري", "क्लिनिकल इनबॉक्स"]] },
    solo: { group: "Clinic team", icon: "user", en: "Solo doctor", ar: "طبيب منفرد", hi: "एकल डॉक्टर", person: "Dr. Meera Iyer", title: "Owner · Dermatology",
      blurb: "One doctor, no front desk: auto-accepted bookings, self check-in, consult, Rx and payment from one screen or a phone.",
      nav: [["today", "sun", "Today", "اليوم", "आज"], ["consult", "steth", "Consultation", "الاستشارة", "परामर्श"], ["patients", "users", "Patients", "المرضى", "मरीज़"],
        ["money", "wallet", "Payments", "المدفوعات", "भुगतान"], ["specialty", "camera", "Skin workspace", "مساحة الجلدية", "त्वचा कार्यक्षेत्र"], ["setup", "sliders", "My clinic settings", "إعدادات عيادتي", "मेरी क्लिनिक सेटिंग्स"]] },
    ward: { group: "Hospital", icon: "bed", en: "Ward nurse (IPD)", ar: "تمريض الأجنحة", hi: "वार्ड नर्स", person: "Sr. Mary Joseph", title: "In-charge, General Ward A",
      blurb: "Bed board, admissions and transfers, medication administration, early-warning scores and discharge.",
      nav: [["beds", "bed", "Bed board", "لوحة الأسرّة", "बेड बोर्ड"], ["admit", "userplus", "Admissions & transfers", "الدخول والتحويل", "भर्ती और ट्रांसफ़र", 2],
        ["mar", "pill", "Medication rounds", "جولات الأدوية", "दवा राउंड", 6], ["discharge", "logout", "Discharge", "الخروج", "डिस्चार्ज", 1], ["ot", "activity", "OT schedule", "جدول العمليات", "ओटी शेड्यूल"]] },
    er: { group: "Hospital", icon: "bolt", en: "Emergency (ER)", ar: "الطوارئ", hi: "आपातकाल", person: "Dr. Sameer Kulkarni", title: "ER consultant on duty",
      blurb: "Ambulance pre-arrival, rapid triage (ESI), resuscitation bays, time-critical pathways (stroke, STEMI, sepsis), disposition and medico-legal cases.",
      nav: [["board", "activity", "ER board", "لوحة الطوارئ", "ईआर बोर्ड", 7], ["triage", "alertfill", "Rapid triage", "الفرز السريع", "त्वरित ट्राइएज"], ["pathways", "clockh", "Time-critical pathways", "المسارات الحرجة", "समय-संवेदी पाथवे"],
        ["ambulance", "truck", "Ambulance & pre-arrival", "الإسعاف", "एम्बुलेंस"], ["mlc", "scroll", "Medico-legal cases", "الحالات الطبية القانونية", "मेडिको-लीगल केस"]] },
    radiology: { group: "Hospital", icon: "image", en: "Radiology", ar: "الأشعة", hi: "रेडियोलॉजी", person: "Dr. Leena George", title: "Consultant radiologist",
      blurb: "Imaging orders, modality scheduling, PACS viewer hand-off, structured reporting, critical-finding alerts and report release.",
      nav: [["worklist", "list", "Imaging worklist", "قائمة الأشعة", "इमेजिंग वर्कलिस्ट", 9], ["schedule", "calendar", "Modality schedule", "جدول الأجهزة", "मोडैलिटी शेड्यूल"],
        ["report", "edit", "Reporting", "كتابة التقارير", "रिपोर्टिंग"], ["critical", "alertfill", "Critical findings", "النتائج الحرجة", "क्रिटिकल फ़ाइंडिंग", 1]] },
    hospops: { group: "Hospital", icon: "box", en: "Hospital services", ar: "خدمات المستشفى", hi: "अस्पताल सेवाएँ", person: "Ravi Menon", title: "Operations manager",
      blurb: "The back-of-house hospital modules: blood bank, CSSD, dietary, stores & procurement, medical records, duty rosters and ambulance fleet.",
      nav: [["bloodbank", "heartpulse", "Blood bank", "بنك الدم", "ब्लड बैंक"], ["cssd", "refresh", "CSSD sterilisation", "التعقيم المركزي", "सीएसएसडी"], ["dietary", "leaf", "Dietary & kitchen", "التغذية", "आहार और रसोई"],
        ["stores", "box", "Stores & procurement", "المخازن والمشتريات", "स्टोर और ख़रीद"], ["mrd", "file", "Medical records (MRD)", "السجلات الطبية", "मेडिकल रिकॉर्ड"],
        ["roster", "calendar", "Duty roster", "جدول المناوبات", "ड्यूटी रोस्टर"], ["fleet", "truck", "Ambulance fleet", "أسطول الإسعاف", "एम्बुलेंस बेड़ा"]] },
    pharmacist: { group: "Pharmacy & lab", icon: "pill", en: "Pharmacist", ar: "الصيدلي", hi: "फ़ार्मासिस्ट", person: "Rahul Joshi", title: "Pharmacist in charge",
      blurb: "Dispenses e-prescriptions with FEFO batches, sells over the counter, manages stock, expiry, purchases and statutory registers. Also sold standalone.",
      nav: [["dispense", "clipboard", "e-Rx queue", "طابور الوصفات", "ई-Rx क़तार", 4], ["pos", "cart", "Counter sale", "البيع المباشر", "काउंटर बिक्री"],
        ["stock", "box", "Stock & expiry", "المخزون والصلاحية", "स्टॉक और एक्सपायरी", 3], ["purchase", "truck", "Purchases & suppliers", "المشتريات والموردون", "ख़रीद और सप्लायर"],
        ["registers", "scroll", "Statutory registers", "السجلات النظامية", "वैधानिक रजिस्टर"], ["insights", "chart", "Sales & margins", "المبيعات والهوامش", "बिक्री और मार्जिन"]] },
    lab: { group: "Pharmacy & lab", icon: "flask", en: "Lab technician", ar: "فني المختبر", hi: "लैब तकनीशियन", person: "Kiran Rao", title: "Lab in charge",
      blurb: "Order worklist, barcode sample collection, result entry with reference ranges, validation, and report delivery to doctor and patient.",
      nav: [["worklist", "list", "Worklist", "قائمة العمل", "वर्कलिस्ट", 7], ["collect", "barcode", "Sample collection", "جمع العينات", "सैंपल कलेक्शन"],
        ["results", "edit", "Result entry", "إدخال النتائج", "रिज़ल्ट एंट्री"], ["validate", "shieldcheck", "Validate & release", "التحقق والإصدار", "सत्यापन और जारी", 2],
        ["catalog", "grid", "Tests, panels & QC", "الفحوصات والجودة", "टेस्ट और QC"]] },
    billing: { group: "Business", icon: "receipt", en: "Billing & insurance", ar: "الفواتير والتأمين", hi: "बिलिंग और बीमा", person: "Pooja Nair", title: "Billing desk",
      blurb: "Checkout, tax-correct invoices (GST / VAT / ZATCA), packages, insurance pre-authorisation and claims, refunds and day close.",
      nav: [["checkout", "receipt", "Checkout", "الدفع", "चेकआउट", 2], ["invoices", "file", "Invoices & receipts", "الفواتير", "इनवॉइस"],
        ["preauth", "shieldcheck", "Pre-authorisation", "الموافقة المسبقة", "प्री-ऑथ", 3], ["claims", "send", "Insurance claims", "مطالبات التأمين", "बीमा क्लेम"],
        ["packages", "layers", "Packages & memberships", "الباقات", "पैकेज"], ["dayclose", "wallet", "Day close", "إغلاق اليوم", "दिन समापन"]] },
    owner: { group: "Business", icon: "chart", en: "Owner / admin", ar: "المالك / المدير", hi: "मालिक / एडमिन", person: "Dr. Arvind Rao", title: "Owner, Suvidha group",
      blurb: "Sees every facility live, sets workflows and booking rules, privacy and sharing policy, staff access, integrations and plan.",
      nav: [["overview", "grid", "Overview", "نظرة عامة", "ओवरव्यू"], ["ops", "activity", "Live operations", "العمليات المباشرة", "लाइव ऑपरेशन"], ["finance", "banknote", "Revenue & dues", "الإيرادات", "राजस्व"],
        ["workflows", "sliders", "Workflows & booking rules", "سير العمل وقواعد الحجز", "वर्कफ़्लो और बुकिंग नियम"], ["privacy", "shieldcheck", "Privacy & data sharing", "الخصوصية ومشاركة البيانات", "गोपनीयता और डेटा साझा"],
        ["staff", "shielduser", "Staff, roles & departments", "الموظفون والأدوار", "स्टाफ़ और भूमिकाएँ"], ["integrations", "link", "Integrations", "التكاملات", "इंटीग्रेशन"],
        ["compliance", "scroll", "Audit & compliance", "التدقيق والامتثال", "ऑडिट और अनुपालन"], ["plan", "layers", "Plan & modules", "الخطة والوحدات", "प्लान और मॉड्यूल"],
        ["setup", "plussq", "Clinic setup & branches", "إعداد العيادة والفروع", "क्लिनिक सेटअप और शाखाएँ"], ["prices", "banknote", "Price master & fees", "قائمة الأسعار والرسوم", "मूल्य सूची और शुल्क"],
        ["reports", "chart", "Reports", "التقارير", "रिपोर्ट"], ["growth", "star", "Recalls, reviews & feedback", "الاستدعاء والتقييمات", "रिकॉल, रिव्यू और फ़ीडबैक"],
        ["bookingpage", "globe", "Booking page & widget", "صفحة الحجز", "बुकिंग पेज और विजेट"], ["payouts", "percent", "Doctor payouts & referrals", "مستحقات الأطباء والإحالات", "डॉक्टर भुगतान और रेफ़रल"],
        ["hr", "clockh", "Shifts, attendance & payroll", "المناوبات والحضور والرواتب", "शिफ़्ट, उपस्थिति और पेरोल"]] },
    patient: { group: "Patients", icon: "smartphone", en: "Patient app", ar: "تطبيق المريض", hi: "मरीज़ ऐप", person: "Rakesh Menon", title: "Patient · family of 3", device: "mobile",
      blurb: "Books in seconds, watches the live queue from home, keeps prescriptions, reports and bills, and controls who sees their records.",
      nav: [["home", "home", "Home", "الرئيسية", "होम"], ["book", "calendar", "Book", "احجز", "बुक"], ["records", "file", "Records", "السجلات", "रिकॉर्ड"],
        ["family", "users", "Family", "العائلة", "परिवार"], ["privacy", "shieldcheck", "Privacy", "الخصوصية", "गोपनीयता", 1], ["packages", "layers", "My packages", "باقاتي", "मेरे पैकेज"]] },
    whatsapp: { group: "Patients", icon: "chat", en: "Patient on WhatsApp", ar: "المريض عبر واتساب", hi: "व्हाट्सऐप पर मरीज़", person: "Kavya Reddy", title: "WhatsApp", device: "mobile", chrome: false,
      blurb: "The whole patient journey inside WhatsApp: book, live queue updates, leave-now alert, e-Rx, payment link and consent approval.",
      nav: [["chat", "chat", "Conversation", "المحادثة", "बातचीत"]] },
    connect: { group: "Patients", icon: "globe", en: "Nabd Connect (web)", ar: "نبض كونكت", hi: "नब्ज़ कनेक्ट", person: "Visitor", title: "Public booking site", chrome: false,
      blurb: "Public aggregator: find a doctor by specialty, area, language and live wait; book into the clinic's real queue.",
      nav: [["search", "search", "Find a doctor", "ابحث عن طبيب", "डॉक्टर खोजें"], ["clinic", "building", "Clinic page", "صفحة العيادة", "क्लिनिक पेज"],
        ["branded", "link", "Clinic's own booking site", "موقع الحجز الخاص بالعيادة", "क्लिनिक की अपनी बुकिंग साइट"], ["forclinics", "sparkle", "For clinics · free trial", "للعيادات · تجربة مجانية", "क्लिनिक के लिए · फ़्री ट्रायल"]] },
    platform: { group: "Nabd", icon: "lifebuoy", en: "Nabd platform team", ar: "فريق منصة نبض", hi: "नब्ज़ प्लेटफ़ॉर्म टीम", person: "Aisha Khan", title: "Platform operations",
      blurb: "Onboards clinics, watches tenant health and data residency per country, handles support with consented, time-boxed access.",
      nav: [["tenants", "building", "Clinics & tenants", "العيادات", "क्लिनिक"], ["onboard", "plussq", "Onboarding", "الإعداد", "ऑनबोर्डिंग"], ["regions", "globe", "Regions & residency", "المناطق والإقامة", "क्षेत्र"],
        ["health", "pulse", "Platform health", "صحة المنصة", "प्लेटफ़ॉर्म स्वास्थ्य"], ["support", "lifebuoy", "Support access", "وصول الدعم", "सपोर्ट एक्सेस"], ["revenue", "banknote", "Subscriptions", "الاشتراكات", "सब्सक्रिप्शन"]] }
  };
  NB.ROLE_ORDER = ["reception", "doctor", "nurse", "solo", "ward", "er", "radiology", "hospops", "pharmacist", "lab", "billing", "owner", "patient", "whatsapp", "connect", "platform"];

  /* ---------------- Registries ---------------- */
  var screens = {}, overlays = {}, actions = {};
  // NB.screen("reception.arrivals", function (ctx) { return html; }, { frame: "tv", chrome: false })
  NB.screen = function (id, fn, meta) { screens[id] = { fn: fn, meta: meta || {} }; };
  // NB.overlay("walkin", function (arg, ctx) { return { kind: "drawer"|"modal", size: "lg", title, body, foot }; })
  NB.overlay = function (id, fn) { overlays[id] = fn; };
  // NB.act("check-in", function (arg, el, ev) { ... })
  NB.act = function (id, fn) { actions[id] = fn; };
  NB.go = function (role, view) {
    var r = NB.ROLES[role];
    NB.state.role = role; NB.state.view = view || (r ? r.nav[0][0] : "home");
    NB.state.overlay = null; NB.state.palette = false;
    try { history.replaceState(null, "", "#" + role + "." + NB.state.view); } catch (e) {}
    NB.render();
    var c = document.querySelector(".content"); if (c) c.scrollTop = 0;
    window.scrollTo(0, 0);
  };
  NB.open = function (ov, arg) { NB.state.overlay = ov; NB.state.ovArg = arg; NB.render(); };
  NB.close = function () { NB.state.overlay = null; NB.state.ovArg = null; NB.render(); };
  var toastTimer;
  NB.toast = function (msg) { NB.state.toast = msg; NB.render(); clearTimeout(toastTimer); toastTimer = setTimeout(function () { NB.state.toast = null; NB.render(); }, 2600); };

  /* ---------------- Context passed to screens ---------------- */
  function ctx() {
    var s = NB.state;
    return { s: s, L: NB.L, ui: ui, m: NB.mkt(), D: NB.D, rtl: NB.rtl(), fac: NB.fac(), facType: s.facType, money: NB.money,
      mobile: deviceOf() === "mobile", role: NB.ROLES[s.role], v: NB.v };
  }
  function deviceOf() {
    var s = NB.state, r = NB.ROLES[s.role];
    if (window.innerWidth <= 760) return "mobile";
    if (s.device !== "auto") return s.device;
    return (r && r.device) || "desktop";
  }
  NB.device = deviceOf;

  /* ---------------- Prototype bar ---------------- */
  function pbar() {
    var s = NB.state, h = "";
    h += '<div class="brand">' + ui.icon("logo", "lg acc") + "nabd" + '<span class="tag">Prototype</span></div>';
    var opts = '<option value="map"' + (s.role === "map" ? " selected" : "") + ">Product map (start here)</option>";
    var groups = {};
    NB.ROLE_ORDER.forEach(function (k) { var r = NB.ROLES[k]; (groups[r.group] = groups[r.group] || []).push(k); });
    Object.keys(groups).forEach(function (g) {
      opts += '<optgroup label="' + g + '">' + groups[g].map(function (k) { return '<option value="' + k + '"' + (s.role === k ? " selected" : "") + ">" + NB.ROLES[k].en + "</option>"; }).join("") + "</optgroup>";
    });
    h += '<div class="grp"><span class="lbl">Viewing as</span><select id="pb-role" aria-label="Viewing as" data-change="role">' + opts + "</select></div>";
    h += '<div class="grp hide-m"><span class="lbl">Market</span><select id="pb-mkt" aria-label="Market" data-change="market">' +
      Object.keys(NB.MARKETS).map(function (k) { return '<option value="' + k + '"' + (s.market === k ? " selected" : "") + ">" + NB.MARKETS[k].name + "</option>"; }).join("") + "</select></div>";
    h += '<div class="grp hide-m"><span class="lbl">Facility</span><select id="pb-fac" aria-label="Facility model" data-change="facType">' +
      Object.keys(NB.FACILITY_TYPES).map(function (k) { return '<option value="' + k + '"' + (s.facType === k ? " selected" : "") + ">" + NB.FACILITY_TYPES[k].name + "</option>"; }).join("") + "</select></div>";
    h += '<div class="spacer"></div>';
    h += '<div class="grp hide-m"><span class="lbl">Device</span><div class="seg">' + [["auto", "Auto"], ["desktop", "Desktop"], ["tablet", "Tablet"], ["mobile", "Mobile"]].map(function (d) {
      return '<button class="' + (s.device === d[0] ? "on" : "") + '" data-act="device" data-arg="' + d[0] + '">' + d[1] + "</button>"; }).join("") + "</div></div>";
    h += '<div class="grp"><span class="lbl hide-m">Theme</span><div class="seg">' + [["dark", "Dark"], ["light", "Light"]].map(function (d) {
      return '<button class="' + (s.theme === d[0] ? "on" : "") + '" data-act="theme" data-arg="' + d[0] + '">' + d[1] + "</button>"; }).join("") + "</div></div>";
    h += '<div class="grp"><span class="lbl hide-m">Language</span><div class="seg">' + [["en", "EN"], ["hi", "हिं"], ["ar", "عربي"]].map(function (d) {
      return '<button class="' + (s.lang === d[0] ? "on" : "") + '" data-act="lang" data-arg="' + d[0] + '" lang="' + d[0] + '">' + d[1] + "</button>"; }).join("") + "</div></div>";
    h += '<button class="btn sm" data-act="palette" title="Jump to any screen (Ctrl K)">' + ui.icon("command", "sm") + "Jump to…</button>";
    return h;
  }

  /* ---------------- Shell ---------------- */
  function navLabel(n) { return NB.L(n[2], n[3], n[4]); }
  function sideNav(r) {
    var s = NB.state, f = NB.fac();
    var h = '<div class="logo"><span class="mark">' + ui.icon("logo") + '</span><div class="nl fac-txt"><b>nabd</b><small>' +
      (s.role === "pharmacist" ? NB.L("Pharmacy", "الصيدلية", "फ़ार्मेसी") : s.role === "lab" ? NB.L("Laboratory", "المختبر", "लैब") : NB.L("Clinic management", "إدارة العيادة", "क्लिनिक प्रबंधन")) + "</small></div></div>";
    if (s.role === "platform") h += '<div class="fac"><span class="fi">' + ui.icon("globe", "sm") + '</span><span class="fac-txt"><b>Nabd platform</b><small>All regions · ' + ui.esc(NB.mkt().name) + " focus</small></span></div>";
    else h += '<button class="fac" data-ov="facswitch" title="Switch facility">' + '<span class="fi">' + ui.icon("building", "sm") + '</span><span class="fac-txt"><b>' + ui.esc(f.name) +
      "</b><small>" + ui.esc(f.area) + "</small></span>" + ui.icon("chevdown", "sm faint") + "</button>";
    h += '<nav class="nav" aria-label="Main">';
    r.nav.forEach(function (n) {
      h += '<button class="ni ' + (s.view === n[0] ? "on" : "") + '" data-go="' + s.role + "." + n[0] + '" title="' + ui.esc(navLabel(n)) + '">' + ui.icon(n[1]) +
        '<span class="nl">' + ui.esc(navLabel(n)) + "</span>" + (n[5] ? '<span class="nb-badge">' + n[5] + "</span>" : "") + "</button>";
    });
    h += "</nav>";
    h += '<div class="me">' + ui.av(r.person) + '<div class="me-txt"><b>' + ui.esc(r.person) + "</b><small>" + ui.esc(NB.L(r.title, r.ar)) + "</small></div></div>";
    return '<aside class="side">' + h + "</aside>";
  }
  function topBar(r) {
    var h = '<div class="top">';
    var ph = NB.state.role === "patient" ? NB.L("Search doctors, clinics or your records", "ابحث عن طبيب أو عيادة أو سجلاتك", "डॉक्टर, क्लिनिक या अपने रिकॉर्ड खोजें")
      : NB.state.role === "pharmacist" ? NB.L("Search medicine, batch, barcode or Rx number", "ابحث عن دواء أو تشغيلة أو وصفة", "दवा, बैच या Rx खोजें")
      : NB.state.role === "platform" ? NB.L("Search clinics, tenants or tickets", "ابحث عن عيادة أو مستأجر أو تذكرة", "क्लिनिक या टिकट खोजें")
      : NB.L("Search patient by name, phone, " + NB.mkt().natId + " or token", "ابحث عن مريض بالاسم أو الهاتف أو الرقم", "नाम, फ़ोन या टोकन से खोजें");
    h += '<div class="search" data-act="palette" role="button" tabindex="0">' + ui.icon("search", "sm") + "<span>" + ph + '</span><span class="kbd">Ctrl K</span></div>';
    h += '<div class="grow"></div>';
    var primary = { reception: ["Register walk-in", "walkin", "userplus"], doctor: ["Call next patient", null, "play"], solo: ["Call next", null, "play"], pharmacist: ["New counter sale", null, "cart"], lab: ["Scan sample", null, "barcode"], billing: ["New invoice", null, "plus"], ward: ["Admit patient", null, "userplus"] }[NB.state.role];
    if (primary) h += primary[1] ? ui.btn(primary[0], { cls: "pri", ov: primary[1], icon: primary[2] }) : ui.btn(primary[0], { cls: "pri", act: "primary", icon: primary[2] });
    h += '<span class="sync"><i></i>' + NB.L("Synced", "متزامن", "सिंक") + "</span>";
    h += '<button class="ibtn" data-ov="notifications" title="Notifications">' + ui.icon("bell", "sm") + '<span class="dotn"></span></button>';
    return h + "</div>";
  }
  function mobileTop(r) {
    var f = NB.fac();
    return '<div class="notch"><span class="num">9:41</span><span>' + ui.icon("wifioff", "xs") + "</span></div>" +
      '<div class="mtop">' + ui.av(r.person) + "<b>" + ui.esc(NB.L(r.en, r.ar, r.hi)) + "</b>" +
      '<button class="ibtn" data-ov="facswitch" title="' + ui.esc(f.name) + '">' + ui.icon("building", "sm") + "</button>" +
      '<button class="ibtn" data-ov="notifications" title="Notifications">' + ui.icon("bell", "sm") + '<span class="dotn"></span></button></div>';
  }
  function bottomNav(r) {
    var s = NB.state, items = r.nav.length > 5 ? r.nav.slice(0, 4).concat([["__more", "dots", "More", "المزيد", "अधिक"]]) : r.nav;
    return '<nav class="bnav" aria-label="Main">' + items.map(function (n) {
      var on = s.view === n[0] || (n[0] === "__more" && r.nav.slice(4).some(function (x) { return x[0] === s.view; }));
      return '<button class="' + (on ? "on" : "") + '" ' + (n[0] === "__more" ? 'data-ov="morenav"' : 'data-go="' + s.role + "." + n[0] + '"') + ">" + ui.icon(n[1]) + "<span>" + ui.esc(navLabel(n)) + "</span></button>";
    }).join("") + "</nav>";
  }
  function placeholder(id) {
    return ui.header(NB.L("Coming together", "قيد البناء", "निर्माणाधीन"), "Screen <code>" + ui.esc(id) + "</code> is being built.") + ui.note("info", "This screen belongs to a module still being assembled.");
  }

  /* ---------------- Product map (landing) ---------------- */
  function productMap() {
    var h = '<div class="ph"><div class="tt"><div class="eyebrow">' + ui.icon("logo", "sm acc") + "Clickable prototype · end-product scope</div>" +
      "<h1>One platform from a solo doctor to a hospital chain</h1><p>Pick a person to see their working day. Every screen uses sample data. Switch market (India, UAE, Saudi Arabia, Qatar), facility model, device and language from the bar above.</p></div></div>";
    var groups = {};
    NB.ROLE_ORDER.forEach(function (k) { var r = NB.ROLES[k]; (groups[r.group] = groups[r.group] || []).push(k); });
    Object.keys(groups).forEach(function (g) {
      h += '<div class="stack s8"><div class="eyebrow">' + g + '</div><div class="grid g3">';
      groups[g].forEach(function (k) {
        var r = NB.ROLES[k];
        h += '<div class="card click" data-go="' + k + "." + r.nav[0][0] + '" role="link" tabindex="0"><div class="row nw">' +
          '<span class="av lg">' + ui.icon(r.icon) + '</span><div class="fl"><h3 class="h">' + ui.esc(r.en) + '</h3><span class="small faint">' + ui.esc(r.person) + " · " + ui.esc(r.title) + "</span></div>" + ui.icon("chevright", "sm faint flip") + "</div>" +
          '<p class="small muted" style="margin:12px 0">' + ui.esc(r.blurb) + '</p><div class="chips">' +
          r.nav.slice(0, 6).map(function (n) { return '<span class="pill mute" style="text-transform:none;letter-spacing:0">' + ui.esc(n[2]) + "</span>"; }).join("") + "</div></div>";
      });
      h += "</div></div>";
    });
    h += ui.card("What makes this product different", '<div class="grid g3">' + [
      ["hourglass", "Time is the design axis", "Live queue with a per-doctor ETA, a “leave home now” WhatsApp alert, and clinic-chosen ETA display (exact, range, position or hidden)."],
      ["shieldcheck", "Consent before sharing", "Records stay with each facility. Sharing needs patient and doctor approval, is time-limited, and every read is logged and visible to the patient."],
      ["sliders", "One product, every clinic size", "Solo mode, auto or manual booking acceptance, self or desk check-in, departments and cross-specialty access policies are settings, not separate products."],
      ["sparkle", "AI as a safety net", "Allergy, interaction, pregnancy and renal-dose checks explain themselves and are overridable in one tap with a reason. Nothing is prescribed automatically."],
      ["store", "Pharmacy and lab stand alone", "The same pharmacy and lab modules run inside a clinic or as separate products with their own login and billing."],
      ["globe", "Built per country", "Currency, tax (GST, VAT, ZATCA), national ID, health-information exchange, claims and data residency follow the market setting."]
    ].map(function (x) { return '<div class="stack s8"><span class="acc">' + ui.icon(x[0], "lg") + '</span><h3 class="h">' + x[1] + '</h3><p class="small muted" style="margin:0">' + x[2] + "</p></div>"; }).join("") + "</div>", { cls: "hl" });
    return h;
  }

  /* ---------------- Render ---------------- */
  NB.render = function () {
    var s = NB.state, root = document.getElementById("app"); if (!root) return;
    document.documentElement.lang = s.lang;
    document.documentElement.setAttribute("data-nb-theme", s.theme === "light" ? "light" : "dark");
    var bar = document.getElementById("pbar"); if (bar) bar.innerHTML = pbar();
    var c = ctx(), dev = deviceOf(), html = "";
    var dir = NB.rtl() ? "rtl" : "ltr";
    if (s.role === "map") {
      html = '<div class="stage"><div class="frame desktop" dir="' + dir + '" style="min-height:0"><div class="main"><div class="content">' + productMap() + "</div></div></div></div>";
    } else {
      var r = NB.ROLES[s.role], id = s.role + "." + s.view, sc = screens[id];
      var meta = (sc && sc.meta) || {};
      var body; try { body = sc ? sc.fn(c) : placeholder(id); } catch (e) { body = ui.note("crit", "Screen error in <code>" + ui.esc(id) + "</code>: " + ui.esc(e.message)); console.error(e); }
      var frameCls = meta.frame === "tv" ? "tv" : dev;
      var chrome = meta.chrome !== false && r.chrome !== false;
      var cap = ui.esc(r.en + " · " + navLabel(r.nav.find(function (n) { return n[0] === s.view; }) || ["", "", s.view]) + " · " + (frameCls === "tv" ? "TV 1280" : dev));
      html = '<div class="stage"><div class="stage-cap">' + cap + '</div><div class="frame ' + frameCls + (s.lang === "hi" ? " lang-hi" : "") + '" dir="' + dir + '">';
      if (!chrome) html += '<div class="main">' + (dev === "mobile" && frameCls !== "tv" && meta.notch !== false ? '<div class="notch"><span class="num">9:41</span><span></span></div>' : "") + body + "</div>";
      else if (dev === "mobile") html += '<div class="main">' + mobileTop(r) + '<div class="content">' + body + "</div>" + bottomNav(r) + "</div>";
      else html += sideNav(r) + '<div class="main">' + topBar(r) + '<div class="content">' + body + "</div></div>";
      html += "</div></div>";
    }
    // Overlays
    if (s.overlay) {
      var o = null;
      try { o = overlays[s.overlay] ? overlays[s.overlay](s.ovArg, c) : { title: s.overlay, body: placeholder("overlay " + s.overlay) }; } catch (e) { o = { title: "Error", body: ui.note("crit", ui.esc(e.message)) }; console.error(e); }
      if (o) {
        var kind = o.kind || "drawer";
        html += '<div class="scrim" data-act="close"></div><div class="' + (kind === "modal" ? "modal" : "drawer " + (o.size || "")) + '" role="dialog" aria-modal="true" aria-label="' + ui.esc(o.title) + '" dir="' + dir + '">' +
          '<div class="dh"><b>' + o.title + '</b><button class="ibtn" data-act="close" title="Close">' + ui.icon("close", "sm") + "</button></div>" +
          '<div class="db">' + o.body + "</div>" + (o.foot ? '<div class="df">' + o.foot + "</div>" : "") + "</div>";
      }
    }
    if (s.palette) html += paletteHtml();
    if (s.toast) html += '<div class="toast" role="status">' + ui.icon("checkcircle", "sm") + ui.esc(s.toast) + "</div>";
    var cont = root.querySelector(".content"), st = cont ? cont.scrollTop : 0;
    root.innerHTML = html;
    var cont2 = root.querySelector(".content"); if (cont2 && st) cont2.scrollTop = st;
    if (s.palette) { var pi = document.getElementById("pal-in"); if (pi) { pi.focus(); pi.value = NB.v("palq", ""); } }
    if (typeof NB.afterRender === "function") NB.afterRender();
  };

  /* ---------------- Command palette ---------------- */
  function paletteHtml() {
    var q = (NB.v("palq", "") || "").toLowerCase(), h = '<div class="scrim" data-act="palette-close"></div><div class="pal" role="dialog" aria-label="Jump to screen"><input id="pal-in" placeholder="Jump to any screen, e.g. “pharmacy stock”, “consent”, “claims”" autocomplete="off">';
    var rows = [];
    NB.ROLE_ORDER.forEach(function (k) { NB.ROLES[k].nav.forEach(function (n) { rows.push([k, n, NB.ROLES[k].en + " " + n[2]]); }); });
    rows = rows.filter(function (r) { return !q || r[2].toLowerCase().indexOf(q) >= 0; }).slice(0, 12);
    h += '<div class="pg">Screens</div>' + rows.map(function (r) {
      return '<div class="pr" data-go="' + r[0] + "." + r[1][0] + '">' + ui.icon(r[1][1], "sm") + "<span class='fl'>" + ui.esc(r[1][2]) + '</span><span class="small faint">' + ui.esc(NB.ROLES[r[0]].en) + "</span></div>";
    }).join("");
    if (!rows.length) h += '<div class="empty">No screen matches “' + ui.esc(q) + "”.</div>";
    return h + "</div>";
  }

  /* ---------------- Built-in overlays ---------------- */
  NB.overlay("facswitch", function () {
    var fs = NB.facilities();
    return { title: NB.L("Switch facility", "تبديل المنشأة", "सुविधा बदलें"), body:
      ui.note("info", "One login for every facility you work at. Records stay inside each facility; switching changes which facility's data you see.") +
      '<div class="lst">' + fs.map(function (f, i) {
        return ui.li('<span class="av">' + ui.icon("building", "sm") + "</span>", ui.esc(f.name), ui.esc(f.area + " · " + NB.FACILITY_TYPES[f.type].name),
          i === NB.state.facility ? ui.pill("acc", "Current") : ui.icon("chevright", "sm faint flip"), { act: "switchfac", arg: i });
      }).join("") + "</div>" };
  });
  NB.overlay("notifications", function () {
    return { title: NB.L("Notifications", "الإشعارات", "सूचनाएँ"), body: '<div class="lst">' + [
      ["crit", "alertfill", "Critical lab value", "Rakesh Menon · potassium 6.1 mmol/L · 2 min ago"],
      ["warn", "clockh", "Dr. Vikram Shah running 15 min late", "6 patients notified on WhatsApp · 5 min ago"],
      ["info", "share", "Record request needs your approval", "Suvidha Hospital → Mohammed Ali · 12 min ago"],
      ["sub", "box", "3 batches expire within 30 days", "Isotretinoin 20 mg, Metformin 500 mg, Atorvastatin 10 mg"],
      ["ok", "checkcircle", "Claim approved", "Star Health · ₹4,850 · 1 h ago"]
    ].map(function (n) { return ui.li('<span class="pill ' + n[0] + '" style="height:32px;width:32px;padding:0;justify-content:center">' + ui.icon(n[1], "sm") + "</span>", n[2], n[3]); }).join("") + "</div>" };
  });
  NB.overlay("morenav", function () {
    var r = NB.ROLES[NB.state.role];
    return { title: NB.L("More", "المزيد", "अधिक"), body: '<div class="lst">' + r.nav.map(function (n) {
      return ui.li('<span class="av">' + ui.icon(n[1], "sm") + "</span>", ui.esc(navLabel(n)), "", n[5] ? '<span class="nb-badge">' + n[5] + "</span>" : "", { go: NB.state.role + "." + n[0] });
    }).join("") + "</div>" };
  });

  /* ---------------- Built-in actions ---------------- */
  NB.act("close", function () { NB.close(); });
  NB.act("device", function (a) { NB.set({ device: a }); });
  NB.act("lang", function (a) { NB.set({ lang: a }); });
  NB.act("theme", function (a) { NB.set({ theme: a }); });
  NB.act("palette", function () { NB.state.ui.palq = ""; NB.set({ palette: true }); });
  NB.act("palette-close", function () { NB.set({ palette: false }); });
  NB.act("switchfac", function (a) { NB.state.facility = +a; NB.state.overlay = null; NB.toast("Switched to " + NB.fac().name); });
  NB.act("primary", function () { NB.toast("Done. In the product this runs the screen's main action."); });
  NB.act("toast", function (a) { NB.state.overlay = null; NB.toast(a); });

  /* ---------------- Event delegation ---------------- */
  document.addEventListener("click", function (ev) {
    var el = ev.target.closest("[data-act],[data-go],[data-ov],[data-set],[data-toggle]");
    if (!el) return;
    if (el.hasAttribute("data-go")) { var p = el.getAttribute("data-go").split("."); NB.go(p[0], p[1]); return; }
    if (el.hasAttribute("data-ov")) { NB.open(el.getAttribute("data-ov"), el.getAttribute("data-arg")); return; }
    if (el.hasAttribute("data-set")) { var o = {}; o[el.getAttribute("data-set")] = el.getAttribute("data-val"); NB.setUI(o); return; }
    if (el.hasAttribute("data-toggle")) { var k = el.getAttribute("data-toggle"), t = {}; t[k] = !NB.v(k, el.querySelector(".sw.on") !== null); NB.setUI(t); return; }
    var a = actions[el.getAttribute("data-act")];
    if (a) a(el.getAttribute("data-arg"), el, ev);
  });
  document.addEventListener("change", function (ev) {
    var el = ev.target, k = el.getAttribute && el.getAttribute("data-change");
    if (!k) return;
    if (k === "role") { if (el.value === "map") { NB.state.role = "map"; NB.state.view = "home"; try { history.replaceState(null, "", "#map"); } catch (e) {} NB.render(); } else NB.go(el.value); }
    else if (k === "market" || k === "facType") { var o = {}; o[k] = el.value; if (k === "market") o.facility = 0; NB.set(o); }
    else { var u = {}; u[k] = el.value; NB.setUI(u); }
  });
  document.addEventListener("input", function (ev) {
    if (ev.target.id === "pal-in") { NB.state.ui.palq = ev.target.value; var pos = ev.target.selectionStart; NB.render(); var pi = document.getElementById("pal-in"); if (pi) pi.setSelectionRange(pos, pos); }
  });
  document.addEventListener("keydown", function (ev) {
    if ((ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === "k") { ev.preventDefault(); NB.state.ui.palq = ""; NB.set({ palette: !NB.state.palette }); }
    else if (ev.key === "Escape") { if (NB.state.palette) NB.set({ palette: false }); else if (NB.state.overlay) NB.close(); }
    else if (ev.key === "Enter" && NB.state.palette) { var first = document.querySelector(".pal .pr"); if (first) first.click(); }
    else if ((ev.key === "Enter" || ev.key === " ") && ev.target.matches && ev.target.matches("[data-toggle],[data-set][role=radio],.card.click,[role=button]")) { ev.preventDefault(); ev.target.click(); }
  });
  window.addEventListener("resize", function () { clearTimeout(NB._rz); NB._rz = setTimeout(NB.render, 150); });

  NB.start = function () {
    var h = (location.hash || "").replace("#", "").split(".");
    if (h[0] && NB.ROLES[h[0]]) { NB.state.role = h[0]; NB.state.view = h[1] && NB.ROLES[h[0]].nav.some(function (n) { return n[0] === h[1]; }) ? h[1] : NB.ROLES[h[0]].nav[0][0]; }
    NB.render();
  };
})();
