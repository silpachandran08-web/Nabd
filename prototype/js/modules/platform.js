/* Platform module (role "platform", Nabd's own operations team): clinics & tenants, onboarding jobs,
   regions & data residency, platform health (SLOs, error budgets, queue lag, WhatsApp delivery),
   support access that needs the clinic owner's approval, and subscriptions. Platform staff never see
   patient records. Overlays: tenant, provision, supportreq, supportnew. Sample data: NB.D.platform.
   Chart helpers come from the admin module (NB.adCharts), which index.html loads first. */
(function () {
  "use strict";
  var ui = NB.ui, L = NB.L;

  /* ================= Styles (prefix pf-, nb.css tokens only) ================= */
  var CSS = [
    ".pf-reg{display:flex;flex-direction:column;gap:12px}",
    ".pf-rh{display:flex;align-items:center;gap:12px}",
    ".pf-flag{width:40px;height:40px;border-radius:12px;display:grid;place-items:center;background:var(--s3);font:700 13px/1 var(--font);color:var(--acc-300);flex:none;letter-spacing:.5px}",
    ".pf-stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}",
    ".pf-stats div{background:var(--s2);border:1px solid var(--b-sub);border-radius:var(--r-md);padding:10px;min-width:0}",
    ".pf-stats b{display:block;font:600 18px/24px var(--font);font-variant-numeric:tabular-nums}",
    ".pf-stats span{display:block;font-size:11px;color:var(--tx3);line-height:14px}",
    ".pf-steps{display:flex;flex-direction:column}",
    ".pf-step{display:flex;gap:12px;align-items:flex-start;padding:12px 0;border-bottom:1px solid var(--div)}",
    ".pf-step:last-child{border-bottom:0}",
    ".pf-step .t{flex:1;min-width:0}.pf-step .t b{display:block;font-size:13.5px;font-weight:500}.pf-step .t small{display:block;font-size:12px;color:var(--tx3);line-height:16px}",
    ".pf-sn{width:28px;height:28px;border-radius:99px;display:grid;place-items:center;background:var(--s3);color:var(--tx2);font:600 12px/1 var(--font);flex:none}",
    ".pf-sn.ok{background:var(--ok-t);color:var(--ok)}.pf-sn.run{background:var(--acc-tint2);color:var(--acc)}.pf-sn.bad{background:var(--crit-t);color:var(--crit)}.pf-sn.wait{background:var(--warn-t);color:var(--warn)}",
    ".pf-job{cursor:pointer;border-radius:var(--r-md);padding:12px;border:1px solid transparent;display:flex;flex-direction:column;gap:8px}",
    ".pf-job:hover{background:var(--s2)}.pf-job.on{background:var(--acc-tint);border-color:var(--acc-line)}",
    ".pf-slo{display:grid;grid-template-columns:minmax(0,1.6fr) 90px 90px minmax(120px,1fr) 130px;gap:10px 16px;align-items:center;font-size:13px;padding:12px 0;border-bottom:1px solid var(--div)}",
    ".pf-slo:last-child{border-bottom:0}.pf-slo>:last-child{justify-self:end}",
    ".pf-slo.h{font:600 10.5px/14px var(--font);letter-spacing:.6px;text-transform:uppercase;color:var(--tx3);padding-top:0}",
    ".pf-mob{display:none}.frame.mobile .pf-mob,.frame.tablet .pf-mob{display:block}",
    ".frame.mobile .pf-slo,.frame.tablet .pf-slo{grid-template-columns:minmax(0,1fr) auto}.frame.mobile .pf-slo .pf-hide,.frame.tablet .pf-slo .pf-hide{display:none}",
    ".pf-rec{display:inline-flex;align-items:center;gap:6px;font-size:11.5px;color:var(--crit)}",
    ".pf-rec i{width:8px;height:8px;border-radius:99px;background:var(--crit);animation:nbPulse 1.6s infinite}",
    ".pf-lock{display:flex;gap:10px;align-items:center;padding:12px 14px;border-radius:var(--r-md);border:1px dashed var(--b-str);background:var(--perm-t);font-size:12.5px;color:var(--tx2)}",
    "@media (max-width:760px){.pf-stats{grid-template-columns:repeat(2,minmax(0,1fr))}.pf-slo{grid-template-columns:minmax(0,1fr) auto}.pf-slo .pf-hide{display:none}.pf-mob{display:block}}"
  ].join("\n");
  (function () {
    if (document.getElementById("pf-css")) return;
    var s = document.createElement("style"); s.id = "pf-css"; s.textContent = CSS; document.head.appendChild(s);
  })();

  var C = NB.adCharts || {};
  function num(x) { return '<span class="num ltr" style="white-space:nowrap">' + x + "</span>"; }
  function usd(v) { return num("$" + (v >= 1e6 ? (v / 1e6).toFixed(2) + "M" : v >= 1e3 ? (v / 1e3).toFixed(1) + "K" : v)); }
  var RG = { india: ["IN", "India"], uae: ["AE", "UAE"], ksa: ["SA", "Saudi Arabia"], qatar: ["QA", "Qatar"] };
  function regPill(r) { return '<span class="pill mute" style="text-transform:none;letter-spacing:0">' + ui.icon("globe") + RG[r][0] + " · " + ({ india: "Mumbai", uae: "UAE", ksa: "Riyadh", qatar: "Doha" })[r] + "</span>"; }
  function healthPill(h) { return h >= 85 ? ui.pill("ok", "Healthy " + h) : h >= 70 ? ui.pill("warn", "Watch " + h) : ui.pill("crit", "At risk " + h); }
  var TST = { active: ["ok", "Active"], trial: ["info", "Trial"], onboarding: ["sync", "Onboarding"], readonly: ["warn", "Read-only · payment due"] };
  function kv(rows) { return C.kv ? C.kv(rows) : ""; }

  /* ================= Sample data (NB.D.platform) ================= */
  var P = NB.D.platform = {
    tenants: [
      { id: "T-0412", n: "Suvidha Health group", city: "Bengaluru", type: "Hospital group", plan: "Hospital", r: "india", fac: 3, users: 142, v30: 21480, h: 92, mfa: 94, last: "Now", st: "active", mrr: 655, since: "Mar 2023", issue: "" },
      { id: "T-0588", n: "Dr. Arjun Rao Clinic", city: "Whitefield, Bengaluru", type: "Solo", plan: "Pro", r: "india", fac: 1, users: 2, v30: 920, h: 98, mfa: 100, last: "2 min", st: "active", mrr: 16, since: "Jan 2026", issue: "" },
      { id: "T-0601", n: "Little Steps Paediatrics", city: "Kukatpally, Hyderabad", type: "Clinic", plan: "Clinic", r: "india", fac: 1, users: 9, v30: 2410, h: 88, mfa: 89, last: "Now", st: "active", mrr: 61, since: "Feb 2026", issue: "" },
      { id: "T-0533", n: "Patil Clinic & Pharmacy", city: "Nashik", type: "Clinic + pharmacy", plan: "Clinic", r: "india", fac: 2, users: 6, v30: 1840, h: 71, mfa: 67, last: "14 min", st: "active", mrr: 48, since: "Sep 2025", issue: "Offline sync backlog 212 bills" },
      { id: "T-0470", n: "Indore Diagnostics", city: "Indore + 6 collection centres", type: "Lab", plan: "Lab", r: "india", fac: 7, users: 24, v30: 6120, h: 95, mfa: 92, last: "Now", st: "active", mrr: 88, since: "Jul 2025", issue: "" },
      { id: "T-0619", n: "Sai Ram Medicals", city: "Andheri, Mumbai", type: "Pharmacy", plan: "Pharmacy", r: "india", fac: 1, users: 3, v30: 0, h: 99, mfa: 100, last: "1 min", st: "trial", mrr: 0, since: "Oct 2026", issue: "" },
      { id: "T-0391", n: "Kochi Heart Institute", city: "Kochi", type: "Hospital", plan: "Hospital", r: "india", fac: 1, users: 61, v30: 5230, h: 80, mfa: 88, last: "Now", st: "readonly", mrr: 410, since: "Nov 2024", issue: "Invoice 41 days overdue" },
      { id: "T-1104", n: "Al Noor Medical group", city: "Dubai", type: "Polyclinic group", plan: "Hospital", r: "uae", fac: 3, users: 118, v30: 16420, h: 90, mfa: 97, last: "Now", st: "active", mrr: 1840, since: "May 2026", issue: "" },
      { id: "T-1121", n: "Smile Studio JLT", city: "JLT, Dubai", type: "Dental", plan: "Pro", r: "uae", fac: 1, users: 5, v30: 610, h: 97, mfa: 100, last: "8 min", st: "active", mrr: 85, since: "Jun 2026", issue: "" },
      { id: "T-2017", n: "Riyadh Care Polyclinic", city: "Olaya, Riyadh", type: "Polyclinic", plan: "Clinic", r: "ksa", fac: 1, users: 42, v30: 7310, h: 64, mfa: 95, last: "Now", st: "active", mrr: 930, since: "Aug 2026", issue: "NPHIES claim errors since 08:00" },
      { id: "T-2024", n: "Nouf Women's Clinic", city: "Dammam", type: "Clinic", plan: "Clinic", r: "ksa", fac: 1, users: 11, v30: 1980, h: 93, mfa: 100, last: "3 min", st: "trial", mrr: 0, since: "Oct 2026", issue: "" },
      { id: "T-3006", n: "Doha Family Medical Centre", city: "Al Sadd, Doha", type: "Polyclinic", plan: "Clinic", r: "qatar", fac: 1, users: 28, v30: 0, h: 86, mfa: 100, last: "Today", st: "onboarding", mrr: 0, since: "Oct 2026", issue: "" }
    ],
    jobs: [
      { id: "J-1182", t: "T-3006", n: "Doha Family Medical Centre", r: "qatar", started: "12 Oct", st: [2, 2, 2, 2, 2, 2, 1, 0, 0, 0], note: "WhatsApp display name in Meta review (usually under 24 h)" },
      { id: "J-1179", t: "T-2024", n: "Nouf Women's Clinic", r: "ksa", started: "09 Oct", st: [2, 2, 2, 2, 2, 2, 2, 3, 0, 0], note: "NPHIES: provider licence number does not match the CCHI record" },
      { id: "J-1185", t: "—", n: "Gulf Dental Care", r: "uae", started: "Today", st: [2, 4, 0, 0, 0, 0, 0, 0, 0, 0], note: "Waiting for sales to confirm the UAE region (cannot change later)" },
      { id: "J-1176", t: "T-0622", n: "Shree Clinic, Pune", r: "india", started: "02 Oct", st: [2, 2, 2, 2, 2, 2, 2, 2, 2, 2], note: "Live since 10 Oct · 1,240 patients imported" }
    ],
    steps: [
      ["Contract & KYC", { india: "GSTIN, clinic registration, owner ID", uae: "Trade licence, DHA facility licence", ksa: "CR, MOH licence, VAT number", qatar: "CR, MOPH licence" }],
      ["Region pinned", "Data region chosen once; cannot change later"],
      ["Tenant, keys & backups", "Per-tenant key in the region's KMS · backups scheduled"],
      ["Facilities & departments", "Rooms, doctors, services and price list"],
      ["Owner invite & MFA", "Single-use link (72 h) · owner enrols MFA before first use"],
      ["Workflow preset", "Solo / clinic / polyclinic / hospital, then adjusted with the owner"],
      ["WhatsApp number", "Embedded signup, display-name approval, templates in local languages"],
      ["Health-exchange registration", { india: "HFR facility + HPR doctors (ABDM)", uae: "NABIDH / Malaffi connection", ksa: "NPHIES provider onboarding, Wasfaty, ZATCA CSID", qatar: "MOPH HIE and QCHP checks" }],
      ["Data import", "Patients, drug master and opening stock from CSV, checked before load"],
      ["Training & go-live", "30-minute desk training, first-day support on WhatsApp"]
    ],
    regions: [
      { r: "india", cloud: "Mumbai (primary) · Hyderabad (DR)", t: 311, f: 446, p95: 142, up: "99.98", bk: "02:00 IST", kms: "01 Oct", st: "ok", note: "CERT-In logs kept 180 days in India", lat: [150, 148, 146, 151, 147, 143, 145, 144, 142, 146, 143, 141, 144, 142] },
      { r: "uae", cloud: "UAE in-country (2 zones)", t: 58, f: 92, p95: 128, up: "99.99", bk: "02:00 GST", kms: "01 Oct", st: "ok", note: "Federal Law 2/2019 Art. 13: no copy leaves the UAE", lat: [131, 130, 129, 133, 128, 127, 129, 130, 128, 126, 129, 128, 127, 128] },
      { r: "ksa", cloud: "Riyadh (in-Kingdom, NCA-approved) · Jeddah (DR)", t: 41, f: 57, p95: 171, up: "99.94", bk: "02:00 AST", kms: "01 Oct", st: "warn", note: "NPHIES gateway latency raised p95 since 08:00", lat: [150, 152, 149, 151, 155, 153, 150, 152, 154, 151, 158, 163, 168, 171] },
      { r: "qatar", cloud: "Doha (2 zones)", t: 18, f: 21, p95: 139, up: "99.97", bk: "02:00 AST", kms: "01 Oct", st: "ok", note: "Data-processing permit filed with NCSA", lat: [140, 141, 139, 142, 140, 138, 139, 141, 140, 139, 138, 140, 139, 139] }
    ],
    slo: [
      ["API availability", "≥ 99.9%", "99.96%", 64, "ok"], ["Queue actions p95", "< 300 ms", "182 ms", 88, "ok"], ["Booking confirmation p95", "< 3 s", "1.4 s", 91, "ok"],
      ["WhatsApp delivery rate", "≥ 98%", "98.7%", 41, "warn"], ["ETA recompute lag", "< 10 s", "3.2 s", 95, "ok"], ["AI safety check p95", "< 1 s", "640 ms", 79, "ok"],
      ["Consent request → access p95", "< 2 min", "1 min 42 s", 72, "ok"], ["Claims gateway success (KSA)", "≥ 99%", "96.2%", 8, "crit"]
    ],
    support: [
      { id: "SA-2207", t: "T-2017", by: "Aisha Khan", scope: "Configuration + redacted queue view", why: "NPHIES claims rejected since 08:00 (BV-00027)", dur: "60 min", st: "pending", ap: "Sent to clinic owner on WhatsApp and app · 09:51", ticket: "TK-88412" },
      { id: "SA-2205", t: "T-0533", by: "Rohit Menon", scope: "Configuration only", why: "Offline sync backlog in pharmacy billing", dur: "60 min", st: "active", ap: "Approved by Dr. Mahesh Patil · 09:12", left: "10 min left", ticket: "TK-88409" },
      { id: "SA-2201", t: "T-1104", by: "Aisha Khan", scope: "Screen-share with the clinic", why: "Walk-in ratio not applied at Al Barsha", dur: "30 min", st: "denied", ap: "Declined by owner: “Fixed it ourselves, thanks”", ticket: "TK-88380" },
      { id: "SA-2198", t: "T-0412", by: "Rohit Menon", scope: "Redacted patient view (no clinical fields)", why: "Duplicate patient merge failed", dur: "60 min", st: "done", ap: "Approved by Dr. Arvind Rao · 15 Oct 16:02", used: "Used 38 min · recording kept 1 year", ticket: "TK-88351" },
      { id: "SA-2193", t: "T-1121", by: "Farah Saleh", scope: "Configuration only", why: "Template language fallback", dur: "30 min", st: "expired", ap: "No answer within 24 h", ticket: "TK-88322" }
    ],
    tickets: [
      { id: "TK-88412", t: "T-2017", s: "Claims rejected: NPHIES error BV-00027", p: "P1", sla: "22 min left", st: "open", who: "Aisha Khan" },
      { id: "TK-88409", t: "T-0533", s: "Pharmacy bills not syncing after power cut", p: "P2", sla: "2 h 10 min left", st: "open", who: "Rohit Menon" },
      { id: "TK-88402", t: "T-3006", s: "WhatsApp display name rejected", p: "P3", sla: "Tomorrow 12:00", st: "waiting", who: "Farah Saleh" },
      { id: "TK-88398", t: "T-0601", s: "How do we set the walk-in ratio?", p: "P4", sla: "Met", st: "solved", who: "Bot + Farah Saleh" }
    ],
    mrr: [118, 124, 131, 137, 142, 149, 155, 161, 166, 172, 179, 186.4],
    plans: [["Free / Solo starter", 96, 0], ["Pro (per doctor)", 132, 21.6], ["Clinic", 104, 49.8], ["Pharmacy", 41, 9.2], ["Lab", 23, 11.4], ["Hospital", 32, 94.4]]
  };
  function tn(id) { return P.tenants.find(function (t) { return t.id === id; }) || { n: id, r: "india" }; }

  /* ================= platform.tenants ================= */
  NB.screen("platform.tenants", function (c) {
    var f = NB.v("pfReg", "all"), hf = NB.v("pfHealth", "all");
    var rows = P.tenants.filter(function (t) { return (f === "all" || t.r === f) && (hf === "all" || (hf === "risk" ? t.h < 85 || t.st === "readonly" : t.st === hf)); });
    var cnt = function (r) { return P.tenants.filter(function (t) { return t.r === r; }).length; };
    var h = ui.header(L("Clinics & tenants", "العيادات والمستأجرون", "क्लिनिक और टेनेंट"), "428 tenants · 611 facilities · 2,940 practitioners · showing " + rows.length + " sample rows",
      ui.btn(L("Provision a tenant", "إنشاء مستأجر", "टेनेंट बनाएँ"), { cls: "pri", icon: "plus", ov: "provision" }));
    h += '<div class="pf-lock">' + ui.icon("lock", "sm") + "<span>Platform staff never open patient records. Usage, health and configuration only; support access to a clinic needs the owner's approval, is time-boxed and recorded.</span></div>";
    h += '<div class="ad-tiles">' + C.tile("Active tenants", "428", "", { delta: "+19 this month", dk: "ok", spark: C.wig(428, 60, 2, 1.2) }) +
      C.tile("Visits yesterday", "38,210", "", { delta: "+4.1% wk/wk", dk: "ok", spark: C.wig(38, 3, 1.4, 0.8) }) +
      C.tile("WhatsApp msgs / day", "412K", "", { delta: "98.7% delivered", dk: "warn", spark: C.wig(412, 40, 12, 1.6) }) +
      C.tile("Tenants at risk", "3", "", { delta: "Health under 75", dk: "crit" }) +
      C.tile("Trials", "27", "", { delta: "9 days to 1st booking", dk: "info" }) + "</div>";
    h += '<div class="row sb">' + ui.seg("pfReg", [["all", "All regions", P.tenants.length], ["india", "India", cnt("india")], ["uae", "UAE", cnt("uae")], ["ksa", "Saudi Arabia", cnt("ksa")], ["qatar", "Qatar", cnt("qatar")]]) +
      ui.seg("pfHealth", [["all", "Any status"], ["risk", "Needs attention"], ["trial", "Trial"], ["onboarding", "Onboarding"]]) + "</div>";
    h += ui.table([{ h: "Tenant" }, { h: "Plan" }, { h: "Region" }, { h: "Facilities · users", cls: "r" }, { h: "Visits · 30 d", cls: "r" }, { h: "Health" }, { h: "MFA" }, { h: "Status" }],
      rows.map(function (t) {
        var s = TST[t.st];
        return { ov: "tenant", arg: t.id, cls: t.h < 70 ? "crit" : t.h < 85 || t.st === "readonly" ? "urg" : "", cells: [ui.cellp(ui.esc(t.n), ui.esc(t.id + " · " + t.type + " · " + t.city)),
          ui.esc(t.plan), regPill(t.r), num(t.fac + " · " + t.users), num(t.v30 ? t.v30.toLocaleString("en-IN") : "—"),
          healthPill(t.h) + (t.issue ? '<div class="tiny faint" style="margin-top:4px;white-space:nowrap">' + ui.esc(t.issue) + "</div>" : ""),
          t.mfa < 90 ? ui.pill("warn", t.mfa + "%") : num(t.mfa + "%"), ui.pill(s[0], s[1])] };
      }), { min: 1020, empty: "No tenants match these filters." });
    return h;
  });
  NB.overlay("tenant", function (id) {
    var t = tn(id), s = TST[t.st], m = NB.MARKETS[t.r];
    var mods = { Hospital: ["Clinic", "Pharmacy", "Lab", "IPD", "Insurance", "Patient app", "AI safety"], Clinic: ["Clinic", "Insurance", "Patient app", "AI safety"], Pro: ["Clinic", "Patient app", "AI safety"], Lab: ["Lab", "Patient app"], Pharmacy: ["Pharmacy"] }[t.plan] || ["Clinic"];
    var sup = P.support.filter(function (x) { return x.t === t.id; });
    return { kind: "drawer", size: "lg", title: t.n, body:
      '<div class="row">' + ui.pill(s[0], s[1]) + healthPill(t.h) + regPill(t.r) + "</div>" +
      (t.issue ? ui.note(t.h < 70 ? "crit" : "warn", ui.esc(t.issue)) : "") +
      ui.card("", kv([["Tenant ID", '<span class="tok">' + t.id + "</span>"], ["Type", ui.esc(t.type)], ["Plan", ui.esc(t.plan) + " · " + usd(t.mrr) + " MRR"], ["Customer since", ui.esc(t.since)],
        ["Data region", ui.esc(m.residency) + " " + ui.pill("perm", "Pinned")], ["Privacy law", ui.esc(m.privacyLaw)], ["Facilities / users", num(t.fac + " / " + t.users)], ["Owner sign-in", "MFA enforced · " + t.mfa + "% of staff enrolled"]])) +
      '<div class="stack s8"><b>Modules</b><div class="chips">' + mods.map(function (x) { return ui.pill("acc", x, "check"); }).join("") + "</div></div>" +
      '<div class="stack s8"><b>Health signals</b><div class="pf-stats"><div><b>' + (t.h < 70 ? "4.1%" : "0.2%") + '</b><span>API errors (24 h)</span></div><div><b>' + (/sync/.test(t.issue) ? "212" : "0") + '</b><span>Offline items waiting to sync</span></div><div><b>' + (t.r === "ksa" ? "97.6%" : "99.1%") + "</b><span>WhatsApp delivered</span></div></div></div>" +
      '<div class="stack s8"><b>Support access history</b>' + (sup.length ? '<div class="lst">' + sup.map(function (x) { return ui.li('<span class="av">' + ui.icon("lifebuoy", "sm") + "</span>", x.id + " · " + ui.esc(x.scope), ui.esc(x.ap), supPill(x)); }).join("") + "</div>" : '<div class="empty">No support access in the last 90 days.</div>') + "</div>" +
      '<div class="pf-lock">' + ui.icon("lock", "sm") + "<span>Patient data is not visible here. Suspending or offboarding needs a second operator's approval and a reason, and the owner is notified.</span></div>",
      foot: ui.btn("Request support access", { cls: "ghost", icon: "key", ov: "supportnew", arg: t.id }) + ui.btn("Change plan", { act: "toast", arg: "Plan change drafted · owner confirms in the app" }) +
        ui.btn(t.st === "readonly" ? "Restore access" : "Suspend…", { cls: t.st === "readonly" ? "okb" : "dng", act: "toast", arg: t.st === "readonly" ? "Restore requested · needs a second operator" : "Suspension needs a reason and a second operator's approval" }) };
  });

  /* ================= platform.onboard ================= */
  var SS = { 0: ["", "Not started", "mute"], 1: ["run", "Running", "sync"], 2: ["ok", "Done", "ok"], 3: ["bad", "Failed", "crit"], 4: ["wait", "Waiting for input", "warn"] };
  function jobState(j) { return j.st.indexOf(3) >= 0 ? 3 : j.st.indexOf(4) >= 0 ? 4 : j.st.indexOf(1) >= 0 ? 1 : j.st.every(function (x) { return x === 2; }) ? 2 : 0; }
  NB.screen("platform.onboard", function (c) {
    var sel = NB.v("pfJob", "J-1179"), job = P.jobs.find(function (j) { return j.id === sel; }) || P.jobs[0];
    var h = ui.header(L("Onboarding", "الإعداد", "ऑनबोर्डिंग"), "Each new clinic is a provisioning job with steps · median time to go-live 2.5 days · target under 1 day for solo clinics",
      ui.btn(L("Provision a tenant", "إنشاء مستأجر", "टेनेंट बनाएँ"), { cls: "pri", icon: "plus", ov: "provision" }));
    var list = ui.card(L("Jobs", "المهام", "जॉब"), '<div class="stack s4">' + P.jobs.map(function (j) {
      var st = jobState(j), done = j.st.filter(function (x) { return x === 2; }).length;
      return '<div class="pf-job' + (j.id === job.id ? " on" : "") + '" data-set="pfJob" data-val="' + j.id + '" role="button" tabindex="0"><div class="row sb nw"><b class="small" style="min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + ui.esc(j.n) + "</b>" + ui.pill(SS[st][2], SS[st][1]) + "</div>" +
        '<span class="tiny faint">' + j.id + " · " + RG[j.r][1] + " · started " + j.started + " · " + done + "/10 steps</span>" +
        '<div class="bar' + (st === 3 ? " crit" : st === 2 ? " ok" : "") + '"><i style="width:' + done * 10 + '%"></i></div></div>';
    }).join("") + "</div>", { icon: "list" });
    var m = NB.MARKETS[job.r];
    var steps = '<div class="pf-steps">' + P.steps.map(function (s, i) {
      var st = job.st[i], d = typeof s[1] === "string" ? s[1] : s[1][job.r];
      if (i === 1) d = m.residency + " · " + d;
      var act = st === 3 ? ui.btn("Retry", { cls: "sm", icon: "refresh", act: "pfretry", arg: job.id + "|" + i }) : st === 4 ? ui.btn("Confirm", { cls: "sm pri", icon: "check", act: "pfretry", arg: job.id + "|" + i }) : "";
      return '<div class="pf-step"><span class="pf-sn ' + SS[st][0] + '">' + (st === 2 ? ui.icon("check", "sm") : st === 3 ? ui.icon("alertfill", "sm") : i + 1) + '</span><div class="t"><b>' + ui.esc(s[0]) + "</b><small>" + ui.esc(d) + "</small>" +
        (st === 3 || st === 4 || st === 1 ? '<small class="' + (st === 3 ? "critc" : st === 4 ? "warnc" : "acc") + '" style="margin-top:4px">' + ui.esc(job.note) + "</small>" : "") + "</div>" + ui.pill(SS[st][2], SS[st][1]) + act + "</div>";
    }).join("") + "</div>";
    var right = ui.card(ui.esc(job.n) + " · " + job.id, '<div class="row" style="margin-bottom:8px">' + regPill(job.r) + ui.pill("mute", "Tenant " + job.t, false) + "</div>" + steps, { icon: "plussq" });
    return h + '<div class="split l">' + list + right + "</div>";
  });
  NB.act("pfretry", function (a) {
    var p = a.split("|"), j = P.jobs.find(function (x) { return x.id === p[0]; }), i = +p[1];
    if (j) { j.st[i] = 2; if (i + 1 < j.st.length && j.st[i + 1] === 0) j.st[i + 1] = 1; j.note = "Step " + (i + 2) + " running"; }
    NB.toast(p[1] === "1" ? "Region confirmed and pinned · it cannot be changed later" : "Step retried and passed · next step started");
  });
  NB.overlay("provision", function () {
    var mk = NB.v("pvMkt", NB.state.market), m = NB.MARKETS[mk];
    return { kind: "modal", title: L("Provision a tenant", "إنشاء مستأجر", "टेनेंट बनाएँ"), body:
      '<div class="fgrid">' + ui.field("Clinic or group name", "", { ph: "e.g. Gulf Dental Care", full: true }) +
      '<div class="field"><label for="f-pvm">Country</label><select id="f-pvm" data-change="pvMkt">' + Object.keys(NB.MARKETS).map(function (k) { return '<option value="' + k + '"' + (k === mk ? " selected" : "") + ">" + NB.MARKETS[k].name + "</option>"; }).join("") + "</select></div>" +
      ui.field("Plan", "Clinic", { options: ["Free / Solo starter", "Pro", "Clinic", "Pharmacy", "Lab", "Hospital"] }) + ui.field("Owner mobile", m.phone + " ", { full: true }) + "</div>" +
      '<div class="stack s8"><span class="small faint">Facility model (sets the workflow preset)</span>' + ui.seg("pvModel", [["solo", "Solo"], ["clinic", "Clinic"], ["poly", "Polyclinic"], ["hospital", "Hospital"]], "clinic") + "</div>" +
      ui.note("warn", "Data will live in the <b>" + ui.esc(m.residency) + "</b> under " + ui.esc(m.privacyLaw) + ". The region is pinned when the job starts and <b>cannot be changed later</b>."),
      foot: ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn("Start provisioning", { cls: "pri", icon: "play", act: "toast", arg: "Job J-1186 started in " + m.name + " · owner invite goes out when facilities are set" }) };
  });

  /* ================= platform.regions ================= */
  NB.screen("platform.regions", function (c) {
    var h = ui.header(L("Regions & residency", "المناطق وإقامة البيانات", "क्षेत्र और रेज़िडेंसी"), "One stack per country · tenant data never leaves its region · residency is pinned at onboarding",
      ui.pill("ok", "All backups verified 02:00"));
    h += '<div class="grid g2">' + P.regions.map(function (r) {
      var m = NB.MARKETS[r.r], cur = NB.state.market === r.r;
      return '<div class="card pf-reg' + (cur ? " hl" : "") + '"><div class="pf-rh"><span class="pf-flag">' + RG[r.r][0] + '</span><div class="fl"><h3 class="h">' + ui.esc(RG[r.r][1]) + (cur ? ' <span class="small faint">· selected market</span>' : "") +
        '</h3><span class="small faint">' + ui.esc(r.cloud) + "</span></div>" + (r.st === "ok" ? ui.pill("ok", "Healthy") : ui.pill("warn", "Degraded")) + "</div>" +
        '<div class="pf-stats"><div><b>' + r.t + "</b><span>Tenants</span></div><div><b>" + r.f + "</b><span>Facilities</span></div><div><b>" + r.up + "%</b><span>Uptime · 30 d</span></div></div>" +
        '<div class="stack s4"><div class="row sb"><span class="small faint">API p95 · last 14 days</span><b class="small num">' + r.p95 + " ms</b></div>" + C.spark(r.lat) + '<span class="tiny faint">Target p95 under 300 ms</span>' + "</div>" +
        kv([["Residency", ui.esc(m.residency)], ["Law", ui.esc(m.privacyLaw)], ["Last backup", ui.esc(r.bk) + " · restore-tested weekly"], ["KMS key rotation", ui.esc(r.kms)], ["Recovery target", "RPO 5 min · RTO 1 h"]]) +
        '<p class="small ' + (r.st === "ok" ? "faint" : "warnc") + '" style="margin:0">' + ui.icon(r.st === "ok" ? "info" : "alertcircle", "xs") + " " + ui.esc(r.note) + "</p></div>";
    }).join("") + "</div>";
    h += '<div class="grid g2">' + ui.card("Global login router", '<p class="small muted" style="margin:0 0 12px">Holds only a hash of each login identifier and its home region, so sign-in reaches the right country. No names, no health data.</p>' +
      kv([["Entries", num("1.92M")], ["p95 lookup", num("18 ms")], ["Health data stored", "None"]]), { icon: "key" }) +
      ui.card("Cross-border transfers", '<p class="small muted" style="margin:0 0 12px">Blocked by default. A patient who moves country starts a new record; sharing across borders needs a per-corridor legal design.</p>' +
        kv([["Transfers this month", num("0")], ["Attempts blocked", num("3") + ' <span class="small faint">(Dubai → Riyadh referrals)</span>'], ["Exceptions approved", num("0")]]), { icon: "globe" }) + "</div>";
    return h;
  });

  /* ================= platform.health ================= */
  NB.screen("platform.health", function (c) {
    var lag = C.wig(1.4, -0.2, 0.5, 1.7, 24).map(function (v, i) { return i === 9 || i === 10 ? v + 4.2 : Math.max(0.4, v); });
    var h = ui.header(L("Platform health", "صحة المنصة", "प्लेटफ़ॉर्म स्वास्थ्य"), "Service levels over the last 30 days · error budgets · live queues · " + num("10:02") + " AST/IST",
      ui.pill("warn", "1 incident open") + ui.btn("Status page", { icon: "globe", act: "toast", arg: "Public status page: 1 incident (KSA claims gateway) · monitoring" }));
    var slo = '<div class="pf-slo h"><span>Objective</span><span class="pf-hide">Target</span><span class="pf-hide">Actual</span><span class="pf-hide">Error budget left</span><span>Status</span></div>' + P.slo.map(function (s) {
      return '<div class="pf-slo"><div class="stack s4"><b style="font-weight:500">' + ui.esc(s[0]) + '</b><span class="tiny faint pf-mob">' + ui.esc(s[2] + " · target " + s[1] + " · " + s[3] + "% budget left") + '</span></div><span class="pf-hide muted num">' + ui.esc(s[1]) + '</span><b class="pf-hide num">' + ui.esc(s[2]) + "</b>" +
        '<div class="pf-hide stack s4">' + ui.bar(s[3], s[4] === "ok" ? "" : s[4]) + '<span class="tiny faint">' + s[3] + "% left</span></div>" +
        ui.pill(s[4], s[4] === "ok" ? "Meeting" : s[4] === "warn" ? "Burning fast" : "Breached") + "</div>";
    }).join("");
    h += ui.card(L("Service levels", "مستويات الخدمة", "सेवा स्तर"), slo, { icon: "pulse" });
    var hrs = []; for (var i = 0; i < 24; i++) hrs.push(i % 6 === 0 ? String(i).padStart(2, "0") + ":00" : "");
    hrs[23] = "now";
    h += '<div class="grid g2">' + ui.card(L("Event queue lag · 24 h", "تأخر طابور الأحداث", "इवेंट क़तार लैग"),
      C.lineChart(lag.map(function (v) { return Math.round(v * 10) / 10; }), { min: 0, max: 10, unit: " s", labels: hrs.filter(function (x) { return x; }), thr: { v: 5, label: "Alert at 5 s" }, h: 150, aria: "Outbox queue lag over 24 hours" }) +
      '<p class="small faint" style="margin:10px 0 0">Outbox to WhatsApp, ETA and consent workers. The spike at 09:00–10:00 was KSA claim retries.</p>', { icon: "activity", right: ui.pill("ok", "1.1 s now") }) +
      ui.card(L("WhatsApp delivery by market", "تسليم واتساب حسب السوق", "बाज़ार अनुसार व्हाट्सऐप डिलीवरी"),
        C.hbars([["India", 99.1, num("99.1%")], ["UAE", 98.4, num("98.4%")], ["Saudi Arabia", 97.6, '<span class="warnc">' + num("97.6%") + "</span>"], ["Qatar", 98.9, num("98.9%")]].map(function (r) { return [r[0], r[1] - 95, r[2]]; })) +
        '<p class="small faint" style="margin:12px 0 0">Delivered of sent, utility and authentication templates, 24 h. Bars start at 95%. KSA is low because 2 tenants have a medium quality rating; SMS fallback covered 1,310 messages.</p>', { icon: "chat" }) + "</div>";
    var inc = [["crit", "INC-P-077 · KSA claims gateway timeouts", "Since 08:04 · 6 tenants · retries queued, no data loss", "Monitoring"], ["ok", "INC-P-075 · Template rejected after Meta policy update", "15 Oct · fixed in 2 h · 0 patient messages lost", "Resolved"], ["ok", "INC-P-072 · ETA worker restart (UAE)", "11 Oct · 4 min · ETAs held at last value", "Resolved"]];
    var jobs = [["Consent expiry job", "Every minute · last 10:02 · 3 expired, 0 failed", "ok"], ["Audit chain verification", "Nightly 02:00 · 428 tenants verified · external checkpoint signed", "ok"], ["Backup restore test", "Weekly · Sunday · all regions passed", "ok"], ["Certificate renewals", "Wasfaty client cert renews in 9 days", "warn"]];
    h += '<div class="grid g2">' + ui.card("Incidents", '<div class="lst">' + inc.map(function (x) { return ui.li('<span class="pill ' + x[0] + '" style="height:32px;width:32px;padding:0;justify-content:center">' + ui.icon(x[0] === "crit" ? "alertfill" : "checkcircle", "sm") + "</span>", ui.esc(x[1]), ui.esc(x[2]), ui.pill(x[0] === "crit" ? "warn" : "ok", x[3])); }).join("") + "</div>", { icon: "alert" }) +
      ui.card("Background jobs", '<div class="lst">' + jobs.map(function (x) { return ui.li('<span class="av">' + ui.icon("refresh", "sm") + "</span>", ui.esc(x[0]), ui.esc(x[1]), ui.pill(x[2], x[2] === "ok" ? "OK" : "Due soon")); }).join("") + "</div>", { icon: "clockh" }) + "</div>";
    return h;
  });

  /* ================= platform.support ================= */
  function supPill(x) { return { pending: ui.pill("warn", "Waiting for owner"), active: '<span class="pill crit"><span class="pulse"></span>Recording · ' + ui.esc(x.left || "") + "</span>", denied: ui.pill("perm", "Declined"), done: ui.pill("ok", "Completed"), expired: ui.pill("mute", "Expired") }[x.st]; }
  NB.screen("platform.support", function (c) {
    var tab = NB.v("pfSupTab", "access");
    var h = ui.header(L("Support access", "وصول الدعم", "सपोर्ट एक्सेस"), "Access to a clinic needs its owner's approval, lasts at most 60 minutes, is recorded and shows up in the clinic's own audit log",
      ui.btn("Request access", { cls: "pri", icon: "key", ov: "supportnew" }));
    h += ui.tabs("pfSupTab", [["access", "Access requests", "key"], ["tickets", "Tickets", "lifebuoy"]]);
    if (tab === "access") {
      h += '<div class="ad-tiles">' + C.tile("Pending owner approval", "1", "", { delta: "Median approval 6 min", dk: "warn" }) + C.tile("Active sessions", "1", "", { delta: "Recorded", dk: "crit" }) +
        C.tile("Approved this month", "41", "", { delta: "of 47 requested", dk: "ok" }) + C.tile("Clinical fields shown", "0", "", { delta: "Never returned to support", dk: "ok" }) + "</div>";
      h += ui.table([{ h: "Request" }, { h: "Clinic" }, { h: "Operator" }, { h: "Scope & reason" }, { h: "Window" }, { h: "Owner approval" }, { h: "Status" }],
        P.support.map(function (x) {
          var t = tn(x.t);
          return { ov: "supportreq", arg: x.id, cells: [ui.cellp('<span class="tok">' + x.id + "</span>", x.ticket), ui.cellp(ui.esc(t.n), RG[t.r][1]), ui.esc(x.by),
            '<div class="stack s4" style="min-width:220px;max-width:300px"><span class="small">' + ui.esc(x.scope) + '</span><span class="tiny faint">' + ui.esc(x.why) + "</span></div>",
            num(x.dur), '<span class="small muted">' + ui.esc(x.ap) + "</span>", supPill(x)] };
        }), { min: 1060 });
    } else {
      var PR = { P1: "crit", P2: "urg", P3: "info", P4: "mute" }, ST = { open: ["warn", "Open"], waiting: ["info", "Waiting on clinic"], solved: ["ok", "Solved"] };
      h += ui.table([{ h: "Ticket" }, { h: "Clinic" }, { h: "Subject" }, { h: "Priority" }, { h: "SLA" }, { h: "Assignee" }, { h: "Status" }],
        P.tickets.map(function (k) {
          var t = tn(k.t);
          return [ '<span class="tok">' + k.id + "</span>", ui.cellp(ui.esc(t.n), RG[t.r][1]), ui.esc(k.s), ui.pill(PR[k.p], k.p), k.p === "P1" ? ui.pill("crit", k.sla, "clockh") : ui.esc(k.sla), ui.esc(k.who), ui.pill(ST[k.st][0], ST[k.st][1])];
        }), { min: 960 });
    }
    return h;
  });
  NB.overlay("supportreq", function (id) {
    var x = P.support.find(function (q) { return q.id === id; }) || P.support[0], t = tn(x.t);
    var ev = [["acc", "send", "Requested by " + x.by, x.why], [x.st === "pending" ? "" : x.st === "denied" || x.st === "expired" ? "crit" : "ok", "user", "Clinic owner", x.ap]];
    if (x.st === "active" || x.st === "done") ev.push(["ok", "play", "Session started", "Recorded · visible in the clinic's audit log"]);
    if (x.st === "done") ev.push(["ok", "check", "Session ended", x.used]);
    return { kind: "drawer", title: "Support access " + x.id, body:
      '<div class="row">' + supPill(x) + regPill(t.r) + "</div>" +
      kv([["Clinic", ui.esc(t.n)], ["Ticket", '<span class="tok">' + x.ticket + "</span>"], ["Scope", ui.esc(x.scope)], ["Window", ui.esc(x.dur) + " · ends automatically"], ["Clinical fields", "Never shown"]]) +
      '<div class="tl">' + ev.map(function (e) { return '<div class="ev"><span class="dotc ' + e[0] + '">' + ui.icon(e[1], "sm") + '</span><div class="bd"><b>' + ui.esc(e[2]) + "</b><small>" + ui.esc(e[3]) + "</small></div></div>"; }).join("") + "</div>" +
      (x.st === "active" ? ui.note("warn", "This session is being recorded. The owner can end it at any time from their phone.") : ""),
      foot: ui.btn("Close", { cls: "ghost", act: "close" }) + (x.st === "active" ? ui.btn("End session now", { cls: "dng", icon: "stop", act: "pfend", arg: x.id }) : x.st === "pending" ? ui.btn("Send reminder", { icon: "bell", act: "toast", arg: "Owner reminded on WhatsApp" }) : "") };
  });
  NB.act("pfend", function (id) { var x = P.support.find(function (q) { return q.id === id; }); if (x) { x.st = "done"; x.used = "Ended early by operator · recording kept 1 year"; } NB.state.overlay = null; NB.toast("Session ended · clinic owner notified"); });
  NB.overlay("supportnew", function (tid) {
    var t = tn(tid || "T-2017");
    return { kind: "modal", title: "Request support access", body:
      '<div class="fgrid">' + ui.field("Clinic", t.n, { options: P.tenants.map(function (x) { return x.n; }), full: true }) + ui.field("Ticket", "TK-88412") + ui.field("Reason (owner sees this)", "Claims rejected since 08:00", {}) + "</div>" +
      '<div class="stack s8">' + ui.radio("snScope", "config", "Configuration only", "Settings, workflows, integrations. No patient data at all.", "config") +
      ui.radio("snScope", "redacted", "Redacted patient view", "Names and MRNs only to fix a record problem. Clinical fields are never returned.", "config") +
      ui.radio("snScope", "share", "Screen-share with the clinic", "The clinic drives; we watch and guide.", "config") + "</div>" +
      '<div class="stack s8"><span class="small faint">How long</span>' + ui.seg("snDur", [["15", "15 min"], ["30", "30 min"], ["60", "60 min"]], "30") + "</div>" +
      ui.note("info", "The owner gets a WhatsApp and app request with your name, reason and scope. Access starts only after they approve, ends on its own and is recorded."),
      foot: ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn("Send to owner", { cls: "pri", icon: "send", act: "toast", arg: "Request sent to the owner of " + t.n + " · you will be notified when they answer" }) };
  });

  /* ================= platform.revenue ================= */
  NB.screen("platform.revenue", function (c) {
    var months = ["Nov", "Dec", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct"];
    var h = ui.header(L("Subscriptions", "الاشتراكات", "सब्सक्रिप्शन"), "Reported in USD · billed locally in INR, AED, SAR and QAR with local tax · WhatsApp pass-through shown separately",
      ui.btn("Export", { icon: "download", act: "toast", arg: "Subscription export ready (no patient data)" }));
    h += '<div class="ad-tiles">' + C.tile("MRR", "$186.4K", "", { delta: "+3.8% vs September", dk: "ok", spark: P.mrr }) + C.tile("ARR run-rate", "$2.24M", "", { delta: "+58% year on year", dk: "ok" }) +
      C.tile("Net revenue retention", "112", "%", { delta: "Expansion from modules", dk: "ok" }) + C.tile("Logo churn", "1.6", "% / mo", { delta: "Mostly free solo clinics", dk: "warn" }) +
      C.tile("ARPA (paying)", "$560", "", { delta: "333 paying tenants", dk: "mute" }) + C.tile("WhatsApp pass-through", "$11.2K", "", { delta: "At Meta rates + 15%", dk: "mute" }) + "</div>";
    h += '<div class="grid g2">' + ui.card(L("MRR · last 12 months", "الإيراد الشهري المتكرر", "MRR · 12 महीने"),
      C.cols(P.mrr, { max: 200, unit: "K", labels: months, h: 160, title: function (v, i) { return months[i] + ": $" + v + "K"; }, aria: "Monthly recurring revenue for 12 months" }), { icon: "chart" }) +
      ui.card(L("Plan mix", "مزيج الخطط", "प्लान मिक्स"), C.hbars(P.plans.map(function (p) { return [p[0], p[2] || 0.4, num(p[1] + " tenants") + ' <span class="faint small">· $' + p[2] + "K</span>"]; })) +
        '<p class="small faint" style="margin:12px 0 0">Bar length is MRR. Hospitals are 7% of tenants and half of revenue.</p>', { icon: "layers" }) + "</div>";
    h += '<div class="split wide"><div class="stack s8"><h2 class="h">By market</h2>' + ui.table([{ h: "Market" }, { h: "Tenants", cls: "r" }, { h: "MRR (local)", cls: "r" }, { h: "MRR (USD)", cls: "r" }, { h: "Growth", cls: "r" }, { h: "Tax on subscription" }],
      [["india", 311, "₹71.2L", 85400, "+2.9%"], ["uae", 58, "196,100 AED", 53400, "+4.6%"], ["ksa", 41, "142,300 SAR", 37900, "+6.1%"], ["qatar", 18, "35,700 QAR", 9700, "+3.2%"]].map(function (r) {
        var m = NB.MARKETS[r[0]];
        return [ui.cellp(RG[r[0]][1], ui.esc(m.residency)), num(r[1]), num(r[2]), usd(r[3]), '<span class="okc">' + num(r[4]) + "</span>", ui.esc(m.taxRate ? m.tax + " " + m.taxRate + "%" : m.tax)];
      }), { min: 680 }) + "</div>" +
      ui.card("Movements · October", '<div class="lst">' + [["plus", "New", "+$9.8K", "22 tenants · 4 in KSA"], ["arrowright", "Expansion", "+$4.1K", "Modules added: AI safety (31), Pharmacy (6)"], ["refresh", "Contraction", "−$1.2K", "Doctors removed after season"], ["logout", "Churn", "−$2.7K", "7 tenants · 5 free solo, 2 paid"]].map(function (x) {
        return ui.li('<span class="av">' + ui.icon(x[0], "sm") + "</span>", x[1] + " · " + num(x[2]), ui.esc(x[3]));
      }).join("") + "</div>", { icon: "activity" }) + "</div>";
    return h;
  });
})();
