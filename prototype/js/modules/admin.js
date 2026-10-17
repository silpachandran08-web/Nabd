/* Admin module (role "owner"): multi-facility overview with time KPIs, live operations, revenue & dues,
   workflows & booking rules with a live patient-journey preview, privacy & data sharing (intra-hospital
   policy, consent ledger, notice, residency, data requests), staff & roles, integrations, audit &
   compliance, plan & modules. Overlays: consentdetail, revoke, bgreview, dsr, rolematrix, auditentry,
   intg, invite, delegate, noticever. Extra sample data lives under NB.D.admin. */
(function () {
  "use strict";
  var ui = NB.ui, L = NB.L;

  /* ================= Styles (prefix ad-, nb.css tokens only) ================= */
  var CSS = [
    ".ad-tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(172px,1fr));gap:14px}",
    ".frame.mobile .ad-tiles{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}",
    ".ad-tile{display:flex;flex-direction:column;gap:6px;padding:16px}",
    ".frame.mobile .ad-tile{padding:12px}",
    ".ad-tl{font-size:12px;color:var(--tx2);line-height:16px;min-height:32px}",
    ".ad-tv{display:flex;align-items:baseline;gap:5px;flex-wrap:wrap}",
    ".ad-tv b{font:600 26px/32px var(--font);letter-spacing:-.4px}",
    ".frame.mobile .ad-tv b{font-size:21px;line-height:26px}",
    ".ad-tv span{font-size:12px;color:var(--tx3)}",
    ".ad-tt{font-size:11px;color:var(--tx3);line-height:14px}",
    ".ad-tile .pill{align-self:flex-start;max-width:100%;height:auto;min-height:22px;white-space:normal;padding:3px 8px;line-height:14px}",
    ".ad-kv{margin:0}.ad-nw{white-space:nowrap}",
    ".ad-spark{display:block;width:100%;height:30px;overflow:visible}",
    ".ad-sl{fill:none;stroke:var(--acc);stroke-width:2;vector-effect:non-scaling-stroke;stroke-linejoin:round;stroke-linecap:round}",
    ".ad-sa{fill:var(--acc-tint);stroke:none}",
    ".ad-st{stroke:var(--warn);stroke-width:1;vector-effect:non-scaling-stroke;opacity:.7}",
    ".ad-ch{display:grid;grid-template-columns:auto minmax(0,1fr);gap:6px 8px}",
    ".ad-chy{display:flex;flex-direction:column;justify-content:space-between;font-size:10.5px;line-height:12px;color:var(--tx3);text-align:end;font-variant-numeric:tabular-nums;margin:-6px 0}",
    ".ad-chp{position:relative;min-width:0;border-bottom:1px solid var(--b-def)}",
    ".ad-chp svg{position:absolute;inset:0;width:100%;height:100%;overflow:visible}",
    ".ad-g{stroke:var(--div);stroke-width:1;vector-effect:non-scaling-stroke}",
    ".ad-b{fill:var(--acc)}.ad-b.lo{fill:var(--acc-tint2)}.ad-b.off{fill:var(--s3)}.ad-b:hover{fill:var(--acc-300)}",
    ".ad-l{fill:none;stroke:var(--acc);stroke-width:2;vector-effect:non-scaling-stroke;stroke-linejoin:round;stroke-linecap:round}",
    ".ad-l.alt{stroke:var(--tx3);stroke-width:1.5}",
    ".ad-a{fill:var(--acc-tint);stroke:none}",
    ".ad-thr{position:absolute;inset-inline:0;border-top:1px solid var(--warn);pointer-events:none}",
    ".ad-thr span{position:absolute;right:0;bottom:2px;font-size:10.5px;line-height:14px;color:var(--warn);background:var(--s1);padding:0 4px;border-radius:4px;white-space:nowrap}",
    ".ad-dot{position:absolute;width:10px;height:10px;border-radius:99px;background:var(--acc);box-shadow:0 0 0 2px var(--s1);transform:translate(-50%,50%)}",
    ".ad-chx{grid-column:2;display:flex;justify-content:space-between;font-size:10.5px;color:var(--tx3);font-variant-numeric:tabular-nums;gap:2px}",
    ".ad-chx.eq span{flex:1;text-align:center;min-width:0;overflow:hidden}",
    ".ad-legend{display:flex;flex-wrap:wrap;gap:6px 14px;font-size:11.5px;color:var(--tx2)}",
    ".ad-legend i{display:inline-block;width:10px;height:10px;border-radius:3px;margin-inline-end:6px;vertical-align:-1px}",
    ".ad-hb{display:grid;grid-template-columns:minmax(90px,170px) minmax(0,1fr) auto;gap:8px 12px;align-items:center;font-size:13px}",
    ".frame.mobile .ad-hb{grid-template-columns:minmax(0,1fr) auto}.frame.mobile .ad-hb .ad-hbt{grid-column:1/-1;order:3;margin-top:-4px}",
    ".ad-hbl{min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:var(--tx2)}",
    ".ad-hbt{height:10px;border-radius:99px;background:var(--s3);overflow:hidden}",
    ".ad-hbt i{display:block;height:100%;border-radius:99px;background:var(--acc)}",
    ".ad-hbv{text-align:end;font-weight:600;white-space:nowrap}",
    ".ad-stk{display:flex;height:22px;border-radius:6px;overflow:hidden;gap:2px;background:var(--s1)}",
    ".ad-stk i{display:block;height:100%;min-width:2px}",
    ".ad-c1{background:var(--acc)}.ad-c2{background:var(--sync)}.ad-c3{background:var(--warn)}.ad-c4{background:var(--mint)}.ad-c5{background:var(--sub)}",
    ".ad-srow{display:grid;grid-template-columns:minmax(110px,190px) minmax(0,1fr) 64px;gap:12px;align-items:center;font-size:13px}",
    ".frame.mobile .ad-srow{grid-template-columns:minmax(0,1fr) 56px}.frame.mobile .ad-srow .ad-stk{grid-column:1/-1;order:3}",
    ".ad-jstrip{display:flex;flex-wrap:wrap;gap:6px;align-items:center}",
    ".ad-jc{display:inline-flex;align-items:center;gap:6px;height:30px;padding:0 10px;border-radius:999px;border:1px solid var(--b-def);background:var(--s2);font-size:12px;font-weight:500;white-space:nowrap}",
    ".ad-jc.auto{border-color:var(--acc-line);background:var(--acc-tint);color:var(--acc-300)}",
    ".ad-jc.pt{border-style:dashed}",
    ".ad-jarr{color:var(--tx4)}",
    ".ad-jv .bd{display:flex;flex-direction:column;gap:4px}",
    ".ad-jv .ev.skip{opacity:.45}.ad-jv .ev.skip .bd b{text-decoration:line-through}",
    ".ad-msg{display:inline-flex;gap:6px;align-items:flex-start;font-size:11.5px;line-height:15px;color:var(--tx2);background:var(--s2);border:1px solid var(--b-sub);border-radius:8px;padding:5px 8px;max-width:100%}",
    ".ad-msg .ico{color:var(--wa);margin-top:1px}",
    ".ad-sticky{position:sticky;top:0}",
    ".frame.tablet .ad-sticky,.frame.mobile .ad-sticky{position:static}",
    ".ad-mx{display:inline-flex;align-items:center;justify-content:center;gap:4px;min-width:72px;height:26px;padding:0 8px;border-radius:999px;font:600 11px/1 var(--font);border:1px solid var(--b-def);background:var(--s2);color:var(--tx2);cursor:pointer;white-space:nowrap}",
    ".ad-mx .ico{width:12px;height:12px}",
    ".ad-mx.allow{color:var(--ok);background:var(--ok-t);border-color:rgba(52,211,153,.38)}",
    ".ad-mx.deny{color:var(--danger);background:var(--danger-t);border-color:rgba(240,69,92,.35)}",
    ".ad-mx.own{color:var(--acc);background:var(--acc-tint);border-color:var(--acc-line);cursor:default}",
    ".ad-mx.lock{border-style:dashed;cursor:not-allowed;color:var(--tx3);background:var(--perm-t)}",
    ".ad-pm{display:inline-flex;align-items:center;gap:4px;font-size:11.5px;font-weight:600;white-space:nowrap}",
    ".ad-pm.full{color:var(--ok)}.ad-pm.edit{color:var(--acc)}.ad-pm.view{color:var(--info)}.ad-pm.none{color:var(--tx4)}.ad-pm.lock{color:var(--tx3)}",
    ".ad-intg{display:flex;flex-direction:column;gap:10px}",
    ".ad-intg .ad-ih{display:flex;gap:12px;align-items:flex-start}",
    ".ad-ii{width:40px;height:40px;border-radius:12px;display:grid;place-items:center;background:var(--s3);color:var(--acc);flex:none}",
    ".ad-ii.ad-wa{color:var(--wa)}",
    ".ad-meta{display:flex;flex-wrap:wrap;gap:4px 12px;font-size:11.5px;color:var(--tx3)}",
    ".ad-steps{display:flex;flex-direction:column;gap:0}",
    ".ad-chk{display:flex;gap:12px;align-items:flex-start;padding:12px 0;border-bottom:1px solid var(--div)}",
    ".ad-chk:last-child{border-bottom:0}",
    ".ad-chk .t{flex:1;min-width:0}.ad-chk .t b{display:block;font-size:13.5px;font-weight:500}.ad-chk .t small{display:block;font-size:12px;color:var(--tx3);line-height:16px}",
    ".ad-mod{display:flex;flex-direction:column;gap:8px}",
    ".ad-mod .tog{padding:0;border:0}",
    ".ad-hash{font:500 11.5px/16px ui-monospace,SFMono-Regular,Menlo,monospace;color:var(--tx3);word-break:break-all;direction:ltr;unicode-bidi:isolate}",
    ".ad-notice{background:var(--s2);border:1px solid var(--b-sub);border-radius:var(--r-md);padding:14px;font-size:12.5px;line-height:19px;color:var(--tx2);max-height:220px;overflow-y:auto}",
    ".ad-bar-foot{display:flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:space-between;padding:14px 16px;border:1px solid var(--b-def);border-radius:var(--r-lg);background:var(--s2)}",
    ".ad-sim{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}",
    ".frame.mobile .ad-sim{grid-template-columns:minmax(0,1fr)}",
    ".ad-field select{background:transparent;border:0;outline:0;font-size:14px;font-weight:500;padding:0;width:100%;min-width:0}",
    ".ad-rail-h{display:flex;align-items:center;gap:8px;justify-content:space-between;flex-wrap:wrap}",
    "@media (max-width:760px){.ad-tiles{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.ad-hb{grid-template-columns:minmax(0,1fr) auto}.ad-hb .ad-hbt{grid-column:1/-1;order:3;margin-top:-4px}.ad-srow{grid-template-columns:minmax(0,1fr) 56px}.ad-srow .ad-stk{grid-column:1/-1;order:3}.ad-sim{grid-template-columns:minmax(0,1fr)}.ad-sticky{position:static}.ad-tv b{font-size:21px;line-height:26px}}"
  ].join("\n");
  (function () {
    if (document.getElementById("ad-css")) return;
    var s = document.createElement("style"); s.id = "ad-css"; s.textContent = CSS; document.head.appendChild(s);
  })();

  /* ================= Helpers ================= */
  function num(x) { return '<span class="num ltr ad-nw">' + x + "</span>"; }
  function facs() { return NB.facilities(); }
  function short(f) { return f.area.split(",")[0]; }
  function mk() { return NB.mkt(); }
  function mid() { return NB.state.market; }
  function pick(o) { return o[mid()] || o.india; }
  function fmt1(x) { return (Math.round(x * 10) / 10).toFixed(1); }
  function rnd(x) { return String(Math.round(x)); }
  // Compact money: ₹18.4L / ₹1.13 Cr in India, 81.2K AED in the GCC.
  function cm(inr) {
    var m = mk(), v = inr * m.fx, s;
    if (m.id === "india") { s = v >= 1e7 ? (v / 1e7).toFixed(2) + " Cr" : v >= 1e5 ? (v / 1e5).toFixed(1) + "L" : v >= 1e3 ? (v / 1e3).toFixed(1) + "K" : Math.round(v); return '<span class="num ltr">₹' + s + "</span>"; }
    s = v >= 1e6 ? (v / 1e6).toFixed(2) + "M" : v >= 1e3 ? (v / 1e3).toFixed(1) + "K" : v.toFixed(0);
    return '<span class="num ltr">' + s + " " + m.cur + "</span>";
  }
  // Deterministic gentle series for sparklines: base, trend over the window, wiggle amplitude.
  function wig(base, trend, amp, seed, n) {
    n = n || 14; var out = [];
    for (var i = 0; i < n; i++) out.push(base - trend * (n - 1 - i) / (n - 1) + amp * Math.sin(seed * (i + 1) * 1.7) * (i === n - 1 ? 0 : 1));
    return out;
  }
  function kv(rows) { return '<dl class="kv ad-kv">' + rows.map(function (r) { return "<dt>" + r[0] + "</dt><dd>" + r[1] + "</dd>"; }).join("") + "</dl>"; }
  function selField(label, key, opts, def) {
    var cur = NB.v(key, def);
    return '<div class="field ad-field"><label>' + ui.esc(label) + '</label><select data-change="' + key + '" aria-label="' + ui.esc(label) + '">' +
      opts.map(function (o) { return '<option value="' + ui.esc(o[0]) + '"' + (o[0] === cur ? " selected" : "") + ">" + ui.esc(o[1]) + "</option>"; }).join("") + "</select></div>";
  }

  /* ---------- Inline SVG charts (theme tokens; time axes never mirror) ---------- */
  function spark(vals, o) {
    o = o || {};
    var w = 120, h = 30, mn = Math.min.apply(null, vals), mx = Math.max.apply(null, vals);
    if (o.thr != null) { mn = Math.min(mn, o.thr); mx = Math.max(mx, o.thr); }
    var r = (mx - mn) || 1, step = w / (vals.length - 1);
    var y = function (v) { return (h - 2 - ((v - mn) / r) * (h - 4)).toFixed(1); };
    var d = vals.map(function (v, i) { return (i ? "L" : "M") + (i * step).toFixed(1) + " " + y(v); }).join(" ");
    return '<svg class="ad-spark" viewBox="0 0 ' + w + " " + h + '" preserveAspectRatio="none" aria-hidden="true">' +
      '<path class="ad-sa" d="' + d + " L" + w + " " + h + " L0 " + h + ' Z"/>' +
      (o.thr != null ? '<line class="ad-st" x1="0" x2="' + w + '" y1="' + y(o.thr) + '" y2="' + y(o.thr) + '"/>' : "") +
      '<path class="ad-sl" d="' + d + '"/></svg>';
  }
  function axis(min, max, unit) { var mid = (min + max) / 2; return '<div class="ad-chy"><span>' + max + unit + "</span><span>" + (mid % 1 ? mid.toFixed(1) : mid) + unit + "</span><span>" + min + unit + "</span></div>"; }
  function grid() { return '<line class="ad-g" x1="0" x2="100" y1="0" y2="0"/><line class="ad-g" x1="0" x2="100" y1="50" y2="50"/>'; }
  // Column chart. o: {max, labels, thr:{v,label}, h, unit, title(i), cls(i)}
  function cols(vals, o) {
    o = o || {}; var max = o.max || Math.max.apply(null, vals), n = vals.length, bw = 100 / n, gap = Math.min(bw * 0.28, 2.4);
    var bars = vals.map(function (v, i) {
      var hh = Math.max(0, v / max * 100);
      return '<rect class="ad-b ' + (o.cls ? o.cls(v, i) : "") + '" x="' + (i * bw + gap / 2).toFixed(2) + '" y="' + (100 - hh).toFixed(2) + '" width="' + (bw - gap).toFixed(2) + '" height="' + hh.toFixed(2) + '"><title>' + ui.esc(o.title ? o.title(v, i) : v) + "</title></rect>";
    }).join("");
    var thr = o.thr ? '<i class="ad-thr" style="bottom:' + (o.thr.v / max * 100).toFixed(1) + '%"><span>' + ui.esc(o.thr.label) + "</span></i>" : "";
    return '<div class="ad-ch" dir="ltr" role="img" aria-label="' + ui.esc(o.aria || "Column chart") + '">' + axis(0, max, o.unit || "") +
      '<div class="ad-chp" style="height:' + (o.h || 140) + 'px"><svg viewBox="0 0 100 100" preserveAspectRatio="none">' + grid() + bars + "</svg>" + thr + "</div>" +
      (function () { var lb = o.labels || [], dense = lb.length === n && lb.every(function (l) { return l; }); lb = dense ? lb : lb.filter(function (l) { return l; });
        return '<div class="ad-chx' + (dense ? " eq" : "") + '">' + lb.map(function (l) { return "<span>" + ui.esc(l) + "</span>"; }).join("") + "</div></div>"; })();
  }
  // Line chart with area wash. o: {min, max, labels, thr, h, unit, alt (second series, muted)}
  function lineChart(vals, o) {
    o = o || {}; var min = o.min != null ? o.min : Math.min.apply(null, vals), max = o.max != null ? o.max : Math.max.apply(null, vals), n = vals.length, r = (max - min) || 1;
    var x = function (i) { return (i / (n - 1) * 100).toFixed(2); }, y = function (v) { return (100 - (v - min) / r * 100).toFixed(2); };
    var d = vals.map(function (v, i) { return (i ? "L" : "M") + x(i) + " " + y(v); }).join(" ");
    var alt = o.alt ? '<path class="ad-l alt" d="' + o.alt.map(function (v, i) { return (i ? "L" : "M") + x(i) + " " + y(v); }).join(" ") + '"/>' : "";
    var last = vals[n - 1];
    return '<div class="ad-ch" dir="ltr" role="img" aria-label="' + ui.esc(o.aria || "Line chart") + '">' + axis(min, max, o.unit || "") +
      '<div class="ad-chp" style="height:' + (o.h || 140) + 'px"><svg viewBox="0 0 100 100" preserveAspectRatio="none">' + grid() +
      '<path class="ad-a" d="' + d + " L100 100 L0 100 Z" + '"/>' + alt + '<path class="ad-l" d="' + d + '"/></svg>' +
      (o.thr ? '<i class="ad-thr" style="bottom:' + ((o.thr.v - min) / r * 100).toFixed(1) + '%"><span>' + ui.esc(o.thr.label) + "</span></i>" : "") +
      '<span class="ad-dot" title="' + ui.esc(String(Math.round(last)) + (o.unit || "")) + '" style="left:100%;bottom:' + ((last - min) / r * 100).toFixed(1) + '%"></span></div>' +
      '<div class="ad-chx">' + (o.labels || []).map(function (l) { return "<span>" + ui.esc(l) + "</span>"; }).join("") + "</div></div>";
  }
  // Horizontal bars (HTML): rows = [[label, value, display]]
  function hbars(rows) {
    var max = Math.max.apply(null, rows.map(function (r) { return r[1]; })) || 1;
    return '<div class="ad-hb">' + rows.map(function (r) {
      return '<span class="ad-hbl" title="' + ui.esc(r[0]) + '">' + ui.esc(r[0]) + '</span><span class="ad-hbt"><i style="width:' + (r[1] / max * 100).toFixed(1) + '%"></i></span><span class="ad-hbv">' + r[2] + "</span>";
    }).join("") + "</div>";
  }
  function tile(label, val, unit, o) {
    o = o || {};
    return '<div class="card ad-tile"><div class="ad-tl">' + ui.esc(label) + '</div><div class="ad-tv"><b class="num ltr">' + val + "</b>" + (unit ? "<span>" + ui.esc(unit) + "</span>" : "") + "</div>" +
      (o.delta ? ui.pill(o.dk || "mute", o.delta) : "") + (o.spark ? spark(o.spark, { thr: o.thr }) : "") + (o.target ? '<div class="ad-tt">' + ui.esc(o.target) + "</div>" : "") + "</div>";
  }

  // Shared with the platform module (loaded after this file).
  NB.adCharts = { spark: spark, cols: cols, lineChart: lineChart, hbars: hbars, tile: tile, kv: kv, wig: wig };

  /* ================= Sample data (NB.D.admin) ================= */
  var A = NB.D.admin = {
    // Per facility (index matches NB.facilities()): today's time KPIs, 14-day series, hourly waits, journey split (minutes).
    F: [
      { visits: 214, docs: 9, wait: 11, d2d: 18, consult: 9.4, eta: 86, resp: 4.2, noshow: 6.8, rev: 412000, occ: null, nps: 71, waitingNow: 9,
        tw: [14, 13, 13, 12, 13, 12, 11, 12, 11, 11, 12, 11, 10, 11], te: [80, 81, 82, 81, 83, 84, 83, 85, 84, 86, 85, 87, 86, 86],
        hr: [6, 9, 12, 14, 13, 11, 9, 8, 10, 13, 12, 9, 7], split: [2.5, 4, 11.5, 9.4, 5],
        dl: { wait: -1.4, d2d: -2, consult: 0.3, eta: 3, resp: -1.1, noshow: -0.6 } },
      { visits: 486, docs: 23, wait: 17, d2d: 27, consult: 11.8, eta: 79, resp: 9.5, noshow: 9.4, rev: 1286000, occ: 84, nps: 64, waitingNow: 24,
        tw: [15, 14, 15, 16, 15, 14, 16, 17, 18, 17, 18, 17, 18, 17], te: [74, 75, 73, 76, 77, 76, 78, 77, 79, 78, 80, 79, 78, 79],
        hr: [9, 14, 19, 22, 21, 18, 15, 13, 16, 19, 17, 14, 10], split: [5, 7, 15, 11.8, 9],
        dl: { wait: 3, d2d: 4, consult: 0.6, eta: -1, resp: 0.8, noshow: 0.9 } },
      { visits: 38, docs: 1, wait: 8, d2d: 12, consult: 10.6, eta: 91, resp: 0.2, noshow: 4.1, rev: 58400, occ: null, nps: 82, waitingNow: 3,
        tw: [9, 9, 8, 9, 8, 8, 9, 8, 7, 8, 8, 8, 8, 8], te: [88, 89, 90, 89, 91, 90, 92, 91, 90, 92, 91, 92, 91, 91],
        hr: [0, 0, 6, 9, 10, 7, 0, 0, 0, 8, 10, 9, 6], split: [0.5, 0, 8, 10.6, 1.5],
        dl: { wait: -0.5, d2d: -1, consult: 0.2, eta: 1, resp: 0, noshow: -0.3 } }
    ],
    punct: [["d1", 96, 0], ["d2", 71, 15], ["d3", 90, 0], ["d4", 82, 0], ["d5", 88, 5]],
    // Live ops: doctors at the other facilities (facility 0 comes from the live queue in data.js).
    opsDocs: [
      { f: 1, n: "Dr. Khalid Al-Saeed", spec: "Cardiology", st: "break", wait: 5, delay: 0, longest: 22, next: "Back 10:15" },
      { f: 1, n: "Dr. Sameer Kulkarni", spec: "Orthopaedics", st: "in", wait: 7, delay: 25, longest: 41, next: "11:05" },
      { f: 1, n: "Dr. Anjali Menon", spec: "ENT", st: "in", wait: 4, delay: 0, longest: 14, next: "10:20" },
      { f: 1, n: "Dr. Rajesh Gupta", spec: "Emergency", st: "in", wait: 2, delay: 0, longest: 6, next: "Triage" },
      { f: 2, n: "solo", spec: "", st: "in", wait: 3, delay: 0, longest: 9, next: "10:12" }
    ],
    pharm: [{ f: 0, rx: 6, wait: 9, target: 8, staff: 2 }, { f: 1, rx: 11, wait: 14, target: 8, staff: 1 }],
    lab: [{ f: 0, pending: 18, breach: 1, items: ["CRP · 134 min (TAT 120)"] }, { f: 1, pending: 42, breach: 3, items: ["HbA1c · 196 min (TAT 180)", "Lipid profile · 262 min (TAT 240)", "Kidney function · 205 min (TAT 180)"] }],
    // Finance (INR scale, month to date).
    svc: [["Consultations", 2140000], ["Procedures", 1180000], ["Pharmacy", 1690000], ["Lab & imaging", 960000], ["Inpatient (IPD)", 4820000], ["Packages", 530000]],
    byDoc: [["Dr. Khalid Al-Saeed", 1820000, 412], ["Dr. Sameer Kulkarni", 1240000, 388], ["Dr. Vikram Shah", 980000, 702], ["Dr. Meera Iyer", 860000, 611], ["Dr. Fatima Rahman", 790000, 344], ["Dr. Nisha Bhat", 610000, 529], ["Dr. Anjali Menon", 480000, 301]],
    payerShare: [41, 27, 13, 11, 8],
    aging: [[1, 640000, 410000, 180000, 90000, 6.2, 38], [2, 520000, 260000, 120000, 40000, 8.9, 26], [3, 310000, 220000, 160000, 120000, 3.1, 19], [4, 180000, 60000, 0, 0, 2.4, 7]],
    dues: [["p3", 4850, "Co-pay after insurance", 3], ["p8", 1200, "Pharmacy credit", 9], ["p5", 12000, "Package balance (sessions 4–6)", 21], ["p6", 2300, "Lab · thyroid panel", 12], ["p10", 3900, "Estimate overrun · cardiology", 34]],
    pkgs: [["Laser hair reduction × 6", 48, 132, 390000, 6], ["Antenatal care (OPD)", 22, 140, 620000, 2], ["Diabetes care · annual", 61, 244, 440000, 9], ["Physiotherapy × 10", 35, 118, 110000, 4]],
    rev30: wig(3.6, 0.5, 0.45, 1.3, 30),
    // Privacy
    depts: ["Medicine", "Child Health", "Heart Centre", "Skin & Hair", "Women's Health", "Emergency", "Psychiatry"],
    dmx: { "2|5": "allow", "5|0": "allow", "5|1": "allow", "5|2": "allow", "5|3": "allow", "5|4": "allow", "3|4": "deny" },
    sens: [
      ["Mental health", "Psychiatry dept · ICD-10 F-codes", "brain"], ["HIV & sexually transmitted infections", "B20–B24, Z21 · sexual-health clinic", "shieldcheck"],
      ["Reproductive health", "O / Z3x codes · IVF & fertility", "baby"], ["Substance use", "F10–F19", "pill"], ["Genetic tests", "Genetic panel orders", "flask"], ["Sexual violence", "Marked by author", "lock"]
    ],
    bg: [
      { id: "BG-118", who: "Dr. Rajesh Gupta", role: "Emergency physician", f: 1, p: "p8", when: "Today 03:12", reason: "Brought in unconscious, suspected stroke — needs allergies and current medicines", scope: "Emergency dataset (allergies, active meds, conditions, last 3 encounters)", dur: "60 min", reads: 4, st: "due", due: "Review due in 61 h" },
      { id: "BG-114", who: "Anita D'Souza", role: "Nurse in-charge", f: 0, p: "p3", when: "14 Oct 22:40", reason: "Hypoglycaemia, patient confused, family not reachable", scope: "Medication list and allergies", dur: "30 min", reads: 2, st: "ok", due: "Justified · reviewed by privacy officer 15 Oct" },
      { id: "BG-109", who: "Dr. Sameer Kulkarni", role: "Orthopaedics", f: 1, p: "p9", when: "09 Oct 11:05", reason: "“Checking history before surgery”", scope: "Psychiatry note (mental health)", dur: "15 min", reads: 1, st: "bad", due: "Unjustified · incident INC-031 opened, staff counselled" }
    ],
    moreConsents: [
      { id: "C-2044", p: "p10", from: "Manipal Hospital, Old Airport Road", to: "Suvidha Hospital", by: "Dr. Khalid Al-Saeed", purpose: "Cardiology referral — prior angiogram", data: ["Imaging reports", "Discharge summary"], range: "Since Jan 2024", st: "pending-patient", pt: "WhatsApp request sent · 09:58", dr: "—", until: "—", reads: 0 },
      { id: "C-2036", p: "p7", from: "Suvidha Multi-Speciality Clinic", to: "Suvidha Hospital", by: "Dr. Anjali Menon", purpose: "Referral within group — ENT", data: ["Encounters", "Allergies", "Prescriptions"], range: "Last 6 months", st: "active", pt: "Guardian approved at desk (signature) · 16 Oct", dr: "Approved by Dr. Nisha Bhat · 16 Oct", until: "15 Nov 2026, 23:59", reads: 1 },
      { id: "C-2027", p: "p8", from: "Suvidha Multi-Speciality Clinic", to: "Suvidha Hospital (Emergency)", by: "Dr. Rajesh Gupta", purpose: "Break-glass — emergency care", data: ["Allergies", "Active medicines", "Conditions", "Last 3 encounters"], range: "Emergency dataset", st: "btg", pt: "Notified on WhatsApp · 03:13", dr: "Skipped (emergency) · review due", until: "Ended 04:12", reads: 4 }
    ],
    dsr: [
      { id: "DSR-0192", p: "p3", type: "Export · copy of my records", ch: "WhatsApp", recv: "16 Oct", due: "15 Nov", st: "prog", step: 2, who: "Medical records" },
      { id: "DSR-0190", p: "p9", type: "Correction · wrong date of birth", ch: "Patient app", recv: "14 Oct", due: "13 Nov", st: "wait", step: 3, who: "Dr. Meera Iyer" },
      { id: "DSR-0188", p: "p8", type: "Access history · who viewed my record", ch: "WhatsApp (son, caregiver)", recv: "12 Oct", due: "11 Nov", st: "done", step: 5, who: "Automatic" },
      { id: "DSR-0187", p: "p1", type: "Erasure · marketing and app data", ch: "Web", recv: "10 Oct", due: "09 Nov", st: "part", step: 5, who: "Privacy officer" }
    ],
    // Staff
    staff: [
      { n: "Dr. Arvind Rao", role: "admin", t: "Owner", dept: "Management", f: [0, 1, 2], mfa: "passkey", last: "Now" },
      { n: "Dr. Vikram Shah", role: "doctor", t: "Consultant physician", dept: "Medicine", f: [0, 1], mfa: "totp", last: "2 min ago" },
      { n: "Dr. Meera Iyer", role: "doctor", t: "Consultant dermatologist", dept: "Skin & Hair", f: [0, 2], mfa: "passkey", last: "6 min ago" },
      { n: "Dr. Nisha Bhat", role: "doctor", t: "Paediatrician", dept: "Child Health", f: [0], mfa: "totp", last: "1 min ago" },
      { n: "Dr. Khalid Al-Saeed", role: "doctor", t: "Visiting cardiologist", dept: "Heart Centre", f: [0, 1], mfa: "none", last: "Yesterday" },
      { n: "Dr. Fatima Rahman", role: "doctor", t: "Obstetrician", dept: "Women's Health", f: [0], mfa: "totp", last: "14 min ago" },
      { n: "Sneha Kulkarni", role: "reception", t: "Front desk lead", dept: "Front desk", f: [0], mfa: "pin", last: "Now" },
      { n: "Anita D'Souza", role: "nurse", t: "OPD nurse", dept: "Nursing", f: [0], mfa: "totp", last: "4 min ago" },
      { n: "Sr. Mary Joseph", role: "nurse", t: "Ward in-charge", dept: "General Ward A", f: [1], mfa: "totp", last: "Now" },
      { n: "Rahul Joshi", role: "pharmacist", t: "Pharmacist in charge", dept: "Pharmacy", f: [0, 1], mfa: "totp", last: "Now" },
      { n: "Kiran Rao", role: "lab", t: "Lab in charge", dept: "Laboratory", f: [0, 1], mfa: "totp", last: "8 min ago" },
      { n: "Pooja Nair", role: "billing", t: "Billing desk · refund approver", dept: "Billing", f: [0], mfa: "none", last: "Now" },
      { n: "Deepa Menon", role: "billing", t: "Insurance desk", dept: "Billing", f: [1], mfa: "totp", last: "22 min ago" }
    ],
    invites: [
      { to: "dr.sana.qureshi@gmail.com", role: "Doctor · Skin & Hair", f: 0, sent: "16 Oct 18:20", exp: "Expires in 52 h", st: "pending" },
      { to: "98450 77812", role: "Receptionist", f: 1, sent: "13 Oct 10:05", exp: "Expired 16 Oct", st: "expired" },
      { to: "ravi.lab@suvidhahealth.in", role: "Lab technician", f: 1, sent: "15 Oct 09:40", exp: "Accepted · MFA enrolled", st: "done" }
    ],
    deleg: [
      { from: "Dr. Khalid Al-Saeed", to: "Dr. Vikram Shah", what: "Approve record-sharing requests for Heart Centre records", win: "15 Oct → 22 Oct", st: "active", why: "Leave cover" },
      { from: "Pooja Nair", to: "Deepa Menon", what: "Refund approval up to " + "₹5,000", win: "Today 14:00 → 20:00", st: "active", why: "Half-day leave" },
      { from: "Dr. Arvind Rao", to: "Farhan Siddiqui", what: "Publish workflow changes (Whitefield)", win: "01 Oct → 08 Oct", st: "ended", why: "Owner travelling" }
    ],
    // Audit (who viewed which record, exports, break-glass, sharing, settings).
    audit: [
      { id: "A-88412", t: "Today 10:04", who: "Dr. Vikram Shah", role: "Doctor", k: "view", act: "Viewed record", obj: "Rakesh Menon · timeline, labs", f: 0, why: "Treating · queue T-15", dev: "Desk PC 3 · 10.4.1.23" },
      { id: "A-88409", t: "Today 10:01", who: "Sneha Kulkarni", role: "Receptionist", k: "view", act: "Viewed demographics", obj: "Kavya Reddy · contact, visits", f: 0, why: "Check-in", dev: "Front desk 1" },
      { id: "A-88402", t: "Today 09:58", who: "Pooja Nair", role: "Billing", k: "export", act: "Exported report", obj: "Day-close (CSV · 214 rows · no clinical fields)", f: 0, why: "Accounts reconciliation", dev: "Billing PC" },
      { id: "A-88391", t: "Today 09:41", who: "Exchange service", role: "System", k: "share", act: "Disclosed under consent", obj: "Rakesh Menon · lab results → Apollo Clinic, HSR Layout", f: 0, why: "Consent C-2038 · read 3 of 5", dev: "Nabd exchange" },
      { id: "A-88377", t: "Today 03:12", who: "Dr. Rajesh Gupta", role: "Emergency physician", k: "bg", act: "Break-glass opened", obj: "Mohammed Ali · emergency dataset", f: 1, why: "Unconscious, suspected stroke", dev: "ED workstation 2" },
      { id: "A-88310", t: "Yesterday 18:22", who: "Dr. Arvind Rao", role: "Owner", k: "settings", act: "Changed workflow", obj: "Follow-up acceptance: Review each → Auto-accept (cap 40/day)", f: 0, why: "Policy change · TOTP step-up", dev: "iPhone · Owner app" },
      { id: "A-88295", t: "Yesterday 17:05", who: "Anita D'Souza", role: "Nurse", k: "view", act: "Viewed record", obj: "Zoya Khan · vitals, allergies", f: 0, why: "Care team · triage", dev: "Triage tablet" },
      { id: "A-88216", t: "16 Oct 16:40", who: "Farhan Siddiqui", role: "Privacy officer", k: "settings", act: "Changed role", obj: "Deepa Menon: Billing → Billing + refund approver", f: 1, why: "Grants ⊆ own · sessions revoked", dev: "Admin PC" },
      { id: "A-88190", t: "16 Oct 12:11", who: "Kiran Rao", role: "Lab", k: "export", act: "Shared report", obj: "Rakesh Menon · HbA1c PDF → patient WhatsApp (expiring link)", f: 0, why: "Result release", dev: "Lab PC 1" },
      { id: "A-88102", t: "16 Oct 09:30", who: "Sneha Kulkarni", role: "Receptionist", k: "view", act: "Bulk views flagged", obj: "84 patient records in 10 minutes", f: 0, why: "Read-volume alert · explained: reminder-call list", dev: "Front desk 1", flag: true }
    ],
    wfVer: [14, 9, 6], wfPub: {}
  };
  A.F.forEach(function (f, i) {
    f.sd2d = wig(f.d2d, -f.dl.d2d, 0.8, 1.1 + i); f.scon = wig(f.consult, -f.dl.consult, 0.3, 2.1 + i); f.sresp = wig(Math.max(f.resp, 0.3), -f.dl.resp, Math.max(f.resp, 0.3) * 0.12, 0.7 + i);
    f.sns = wig(f.noshow, -f.dl.noshow, 0.5, 1.9 + i); f.srev = wig(f.rev, f.rev * 0.08, f.rev * 0.07, 0.9 + i); f.snps = wig(f.nps, 3, 1.5, 1.4 + i);
  });
  A.occ14 = wig(84, 4, 2.5, 1.2);

  function payers() {
    return pick({
      india: ["Self-pay (cash / UPI)", "Star Health · MediAssist TPA", "HDFC Ergo · FHPL", "PM-JAY", "Corporate (Infosys, Wipro)"],
      uae: ["Self-pay", "Daman", "AXA Gulf · NextCare", "Neuron TPA", "Corporate"],
      ksa: ["Self-pay", "Bupa Arabia", "Tawuniya", "MedGulf", "MOH referral"],
      qatar: ["Self-pay", "QLM", "Allianz Care", "Bupa Global", "Corporate"]
    });
  }
  function privacyOfficer() { return NB.gcc() ? { n: "Layla Haddad", mail: "dpo@alnoorhealth.ae", ph: mk().phone + " 4 555 0192" } : { n: "Farhan Siddiqui", mail: "dpo@suvidhahealth.in", ph: "+91 80 4718 2200" }; }
  function soloDoc() { return NB.gcc() ? { n: "Dr. Khalid Al-Mansouri", spec: "Dentistry" } : { n: "Dr. Meera Iyer", spec: "Dermatology" }; }
  function regName() { return pick({ india: "HPR / Karnataka Medical Council", uae: "DHA Sheryan", ksa: "SCFHS", qatar: "QCHP (MOPH)" }); }
  // Consents in sample data are written for the India group; map facility names to the selected market.
  var EXT = {
    "Apollo Clinic, HSR Layout": { uae: "Mediclinic Parkview", ksa: "Dr. Sulaiman Al Habib, Olaya", qatar: "Al Ahli Hospital" },
    "Cloudnine Hospital": { uae: "Latifa Hospital", ksa: "Dallah Hospital", qatar: "Sidra Medicine" },
    "Kaya Skin Clinic": { uae: "Kaya Skin Clinic, Jumeirah", ksa: "Derma Clinic, Riyadh", qatar: "Kaya Skin Clinic, Doha" },
    "Manipal Hospital, Old Airport Road": { uae: "Rashid Hospital", ksa: "King Fahd Medical City", qatar: "Hamad General Hospital" }
  };
  function fx(s) {
    var fs = facs(); s = String(s);
    s = s.replace("Suvidha Multi-Speciality Clinic", fs[0].name).replace("Suvidha Hospital", fs[1].name);
    Object.keys(EXT).forEach(function (k) { if (s.indexOf(k) >= 0 && NB.gcc()) s = s.replace(k, EXT[k][mid()] || k); });
    return s;
  }
  function ours(s) { var fs = facs(); s = fx(s); return s.indexOf(fs[0].name) >= 0 || s.indexOf(fs[1].name) >= 0 || s.indexOf(fs[2].name) >= 0; }
  function consents() { return NB.D.consents.concat(A.moreConsents); }
  function dirOf(c) { var a = ours(c.from), b = ours(c.to); return a && b ? "group" : a ? "out" : "in"; }
  var CST = {
    "pending-patient": ["warn", "Waiting for patient"], "pending-doctor": ["warn", "Waiting for doctor"], active: ["ok", "Active"], expired: ["mute", "Expired"],
    denied: ["perm", "Declined"], revoked: ["perm", "Revoked"], btg: ["crit", "Break-glass"]
  };
  var ROLES = [["reception", "Reception"], ["doctor", "Doctor"], ["nurse", "Nurse"], ["pharmacist", "Pharmacist"], ["lab", "Lab"], ["billing", "Billing"], ["admin", "Admin"]];
  function roleName(r) { var x = ROLES.find(function (q) { return q[0] === r; }); return x ? x[1] : r; }

  /* ================= owner.overview ================= */
  var TG = { wait: 15, d2d: 20, eta: 80, resp: 5, noshow: 8 };
  function metrics(sel, rg) {
    var F = A.F, list = sel === "all" ? F : [F[+sel]], tot = list.reduce(function (s, f) { return s + f.visits; }, 0);
    var w = function (k) { return list.reduce(function (s, f) { return s + f[k] * f.visits; }, 0) / tot; };
    var wa = function (k) { return list[0][k].map(function (_, i) { return list.reduce(function (s, f) { return s + f[k][i] * f.visits; }, 0) / tot; }); };
    var sa = function (k) { return list[0][k].map(function (_, i) { return list.reduce(function (s, f) { return s + f[k][i]; }, 0); }); };
    var adj = { today: 0, d7: 0.8, d30: 1.3 }[rg] || 0, mul = { today: 1, d7: 6.8, d30: 29 }[rg] || 1;
    var M = { visits: Math.round(tot * mul), wait: w("wait") + adj, d2d: w("d2d") + adj * 1.2, consult: w("consult") + adj * 0.2, eta: w("eta") - adj * 1.6, resp: w("resp") + adj * 0.3,
      noshow: w("noshow") + adj * 0.4, rev: list.reduce(function (s, f) { return s + f.rev; }, 0) * mul, nps: w("nps"), docs: list.reduce(function (s, f) { return s + f.docs; }, 0),
      waitingNow: list.reduce(function (s, f) { return s + f.waitingNow; }, 0), occ: list.some(function (f) { return f.occ; }) ? 84 : null,
      tw: wa("tw"), te: wa("te"), hr: wa("hr"), split: wa("split"), sd2d: wa("sd2d"), scon: wa("scon"), sresp: wa("sresp"), sns: wa("sns"), srev: sa("srev"), snps: wa("snps") };
    var dl = {}; ["wait", "d2d", "consult", "eta", "resp", "noshow"].forEach(function (k) { dl[k] = list.reduce(function (s, f) { return s + f.dl[k] * f.visits; }, 0) / tot; });
    M.dl = dl; return M;
  }
  function respTxt(m) { return m < 1 ? [rnd(m * 60), "s"] : [fmt1(m), "min"]; }
  function dtxt(v, unit, lowerGood) {
    var good = lowerGood ? v <= 0 : v >= 0, s = (v > 0 ? "+" : v < 0 ? "−" : "±") + fmt1(Math.abs(v)).replace(/\.0$/, "") + " " + unit + " vs last week";
    return [s, Math.abs(v) < 0.05 ? "mute" : good ? "ok" : "warn"];
  }

  NB.screen("owner.overview", function (c) {
    var fs = facs(), sel = NB.v("ovFac", "all"), rg = NB.v("ovRange", "today"), M = metrics(sel, rg), m = c.m;
    var rgTxt = { today: "Today so far", d7: "Last 7 days", d30: "Last 30 days" }[rg];
    var h = ui.header(L("Overview", "نظرة عامة", "ओवरव्यू"),
      ui.esc(rgTxt) + " · " + (sel === "all" ? fs.length + " facilities" : ui.esc(fs[+sel].name)) + " · Sat, 17 Oct 2026, " + num("10:02") + " · " + ui.esc(m.name),
      ui.seg("ovRange", [["today", L("Today", "اليوم", "आज")], ["d7", L("7 days", "٧ أيام", "7 दिन")], ["d30", L("30 days", "٣٠ يومًا", "30 दिन")]]) +
      ui.btn(L("Daily WhatsApp summary", "ملخص يومي عبر واتساب", "दैनिक व्हाट्सऐप सारांश"), { icon: "chat", act: "toast", arg: "Daily summary goes to Dr. Arvind Rao on WhatsApp at 08:00 (no patient names)" }));
    h += ui.seg("ovFac", [["all", L("All facilities", "كل المنشآت", "सभी सुविधाएँ"), fs.length]].concat(fs.map(function (f, i) { return [String(i), short(f) + " · " + NB.FACILITY_TYPES[f.type].name]; })));

    var dw = dtxt(M.dl.wait, "min", true), dd = dtxt(M.dl.d2d, "min", true), dc = dtxt(M.dl.consult, "min", true), de = dtxt(M.dl.eta, "pts", false),
      dr = dtxt(M.dl.resp, "min", true), dn = dtxt(M.dl.noshow, "pts", true), rt = respTxt(M.resp);
    dc[1] = "mute";
    h += '<div class="stack s8"><div class="eyebrow">' + ui.icon("hourglass", "sm") + L("Time · what patients feel", "الوقت · ما يشعر به المرضى", "समय · मरीज़ क्या महसूस करते हैं") + '</div><div class="ad-tiles">' +
      tile(L("Median wait", "متوسط الانتظار", "औसत प्रतीक्षा"), rnd(M.wait), "min", { delta: dw[0], dk: dw[1], spark: M.tw, thr: TG.wait, target: "Target under 15 min in the waiting room" }) +
      tile(L("Door to doctor", "من الباب إلى الطبيب", "दरवाज़े से डॉक्टर तक"), rnd(M.d2d), "min", { delta: dd[0], dk: dd[1], spark: M.sd2d, target: "Arrival to consult start · target under 20" }) +
      tile(L("Consult time", "مدة الاستشارة", "परामर्श समय"), fmt1(M.consult), "min", { delta: dc[0], dk: dc[1], spark: M.scon, target: "Median, all visit types" }) +
      tile(L("ETA accuracy", "دقة الوقت المتوقع", "ETA सटीकता"), rnd(M.eta), "%", { delta: de[0], dk: de[1], spark: M.te, thr: TG.eta, target: "Consults starting within ±10 min of the ETA we showed · target 80%" }) +
      tile(L("Booking response", "سرعة الرد على الحجز", "बुकिंग जवाब"), rt[0], rt[1], { delta: dr[0], dk: dr[1], spark: M.sresp, target: "Request to confirmation, median · target under 5 min" }) +
      tile(L("No-show rate", "نسبة عدم الحضور", "नो-शो दर"), fmt1(M.noshow), "%", { delta: dn[0], dk: dn[1], spark: M.sns, target: "Booked but not arrived after grace · target under 8%" }) +
      "</div></div>";

    var occTile = M.occ != null ? tile(L("Bed occupancy", "إشغال الأسرّة", "बेड ऑक्यूपेंसी"), M.occ, "%", { delta: "36 of 43 beds", dk: "info", spark: A.occ14, target: "IPD, " + fs[1].name })
      : tile(L("Bed occupancy", "إشغال الأسرّة", "बेड ऑक्यूपेंसी"), "—", "", { delta: "No inpatient beds", dk: "mute", target: "Outpatient facility" });
    h += '<div class="stack s8"><div class="eyebrow">' + ui.icon("banknote", "sm") + L("Business", "الأعمال", "व्यवसाय") + '</div><div class="ad-tiles">' +
      tile(L("Revenue", "الإيرادات", "राजस्व"), cm(M.rev).replace(/<[^>]+>/g, ""), "", { delta: "+6.2% vs same period", dk: "ok", spark: M.srev, target: "Net of " + m.tax + " · " + (sel === "all" ? "all facilities" : short(fs[+sel])) }) +
      occTile +
      tile("NPS", rnd(M.nps), "", { delta: "+3 vs last month", dk: "ok", spark: M.snps, target: "WhatsApp survey after checkout · 1,204 replies" }) +
      tile(L("Visits", "الزيارات", "विज़िट"), M.visits.toLocaleString("en-IN"), "", { delta: M.docs + " doctors on duty", dk: "mute", target: "Booked, walk-in and teleconsult" }) +
      tile(L("Waiting now", "ينتظرون الآن", "अभी प्रतीक्षारत"), String(M.waitingNow), "", { delta: "Live", dk: "sync", target: "Across the selected facilities" }) +
      tile(L("Dues outstanding", "مستحقات قائمة", "बकाया"), cm(sel === "all" ? 3144000 : [890000, 2190000, 64000][+sel]).replace(/<[^>]+>/g, ""), "", { delta: "Patients + insurers", dk: "warn", target: "See Revenue & dues" }) +
      "</div></div>";

    var hrs = ["8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20"];
    var days = ["4 Oct", "", "", "", "", "", "11 Oct", "", "", "", "", "", "", "17 Oct"].filter(function (x) { return x; });
    h += '<div class="grid g2">' +
      ui.card(L("Waiting time by hour · today", "الانتظار حسب الساعة · اليوم", "घंटे के अनुसार प्रतीक्षा · आज"),
        cols(M.hr.map(Math.round), { max: 25, unit: "", labels: hrs, thr: { v: 15, label: "Target 15 min" }, h: 150, aria: "Median wait by hour today",
          cls: function (v) { return v === 0 ? "off" : ""; }, title: function (v, i) { return hrs[i] + ":00 · " + (v ? v + " min median wait" : "closed"); } }) +
        '<p class="small faint" style="margin:10px 0 0">Minutes, median per hour. Peaks at 10–11 and 17:00 match doctor start delays, not arrivals.</p>', { icon: "clockh", right: ui.pill("info", "Minutes") }) +
      ui.card(L("ETA accuracy · last 14 days", "دقة الوقت المتوقع · ١٤ يومًا", "ETA सटीकता · 14 दिन"),
        lineChart(M.te, { min: 60, max: 100, unit: "%", labels: days, thr: { v: 80, label: "Target 80%" }, h: 150, aria: "ETA accuracy over 14 days" }) +
        '<p class="small faint" style="margin:10px 0 0">Share of consults that started within ±10 minutes of the time we showed the patient. Range mode is used where accuracy is below target.</p>', { icon: "hourglass", right: ui.pill("ok", Math.round(M.te[13]) + "% today") }) +
      "</div>";

    // Where the time goes
    var segs = [["Arrival → check-in", "ad-c1"], ["Check-in → vitals", "ad-c2"], ["Waiting for doctor", "ad-c3"], ["Consultation", "ad-c4"], ["Checkout & pharmacy", "ad-c5"]];
    var rows = [["all", L("All facilities", "كل المنشآت", "सभी सुविधाएँ"), metrics("all", rg).split]].concat(fs.map(function (f, i) { return [String(i), short(f), A.F[i].split]; }));
    var maxT = Math.max.apply(null, rows.map(function (r) { return r[2].reduce(function (a, b) { return a + b; }, 0); }));
    var split = '<div class="stack s8">' + rows.map(function (r) {
      var t = r[2].reduce(function (a, b) { return a + b; }, 0);
      return '<div class="ad-srow"><span class="small' + (r[0] === sel ? " acc" : " muted") + '">' + ui.esc(r[1]) + '</span><div class="ad-stk" style="width:' + (t / maxT * 100).toFixed(1) + '%">' +
        r[2].map(function (v, i) { return v ? '<i class="' + segs[i][1] + '" style="flex:' + v + '" title="' + ui.esc(segs[i][0] + ": " + fmt1(v) + " min") + '"></i>' : ""; }).join("") +
        '</div><b class="num ltr small" style="text-align:end">' + rnd(t) + " min</b></div>";
    }).join("") + '</div><div class="ad-legend" style="margin-top:14px">' + segs.map(function (s) { return '<span><i class="' + s[1] + '"></i>' + s[0] + "</span>"; }).join("") + "</div>";
    var punct = '<div class="lst">' + A.punct.map(function (p) {
      var d = NB.doc(p[0]);
      return ui.li(ui.av(d.name), ui.esc(NB.dname(d)), ui.esc(d.spec) + " · starts on time " + p[1] + "% of sessions",
        p[2] ? ui.pill("warn", "+" + p[2] + " min today") : ui.pill("ok", "On time"));
    }).join("") + "</div>";
    h += '<div class="split wide">' + ui.card(L("Where the patient's time goes", "أين يذهب وقت المريض", "मरीज़ का समय कहाँ जाता है"), split, { icon: "activity", right: '<span class="small faint">Median minutes in clinic</span>' }) +
      ui.card(L("Doctor punctuality", "التزام الأطباء بالمواعيد", "डॉक्टर समयपालन"), punct, { icon: "steth" }) + "</div>";

    // Facility comparison
    var cell = function (v, txt, bad) { return bad ? ui.pill("warn", txt) : num(txt); };
    var table = ui.table([{ h: L("Facility", "المنشأة", "सुविधा") }, { h: "Visits", cls: "r" }, { h: "Median wait" }, { h: "Door → doctor" }, { h: "Consult" }, { h: "ETA ±10 min" },
      { h: "Booking response" }, { h: "No-show" }, { h: "Revenue", cls: "r" }, { h: "NPS", cls: "r" }, { h: "Status" }],
      fs.map(function (f, i) {
        var F = A.F[i], off = (F.wait > TG.wait) + (F.d2d > TG.d2d) + (F.eta < TG.eta) + (F.resp > TG.resp) + (F.noshow > TG.noshow), r = respTxt(F.resp);
        return { cls: String(i) === sel ? "sel" : "", act: "ovfac", arg: i, cells: [ui.cellp(ui.esc(f.name), ui.esc(f.area + " · " + NB.FACILITY_TYPES[f.type].name)), num(F.visits),
          cell(F.wait, F.wait + " min", F.wait > TG.wait), cell(F.d2d, F.d2d + " min", F.d2d > TG.d2d), num(fmt1(F.consult) + " min"),
          '<div style="min-width:90px">' + cell(F.eta, F.eta + "%", F.eta < TG.eta) + ui.bar(F.eta, F.eta < TG.eta ? "warn" : "") + "</div>",
          cell(F.resp, r[0] + " " + r[1] + (F.resp < 1 ? " (auto)" : ""), F.resp > TG.resp), cell(F.noshow, fmt1(F.noshow) + "%", F.noshow > TG.noshow), cm(F.rev), num(F.nps),
          off ? ui.pill("warn", off + " off target") : ui.pill("ok", "On target")] };
      }), { min: 1060 });
    h += '<div class="stack s8"><div class="row sb"><h2 class="h">' + L("Facilities compared", "مقارنة المنشآت", "सुविधाओं की तुलना") + '</h2><span class="small faint">Today · select a row to focus the dashboard · figures are aggregated, never patient-level</span></div>' + table + "</div>";
    h += ui.note("info", "<b>What changed this week:</b> waits at " + ui.esc(short(fs[1])) + " are up 3 min since Monday because orthopaedics OPD started 25 min late on 4 of 5 days. " +
      '<a href="#" data-go="owner.ops">See live operations</a> or <a href="#" data-go="owner.workflows">switch Whitefield to range ETAs</a>.');
    return h;
  });
  NB.act("ovfac", function (a) { NB.setUI({ ovFac: String(a) }); });

  /* ================= owner.ops ================= */
  function opsDocs() {
    var fs = facs(), q = NB.D.queue, rows = [];
    ["d1", "d2", "d3", "d5"].forEach(function (id) {
      var d = NB.doc(id), mine = q.filter(function (x) { return x.d === id; });
      var waiting = mine.filter(function (x) { return /waiting|arrived|vitals/.test(x.st); });
      rows.push({ f: 0, n: NB.dname(d), spec: d.spec, st: d.status === "break" ? "break" : "in", wait: waiting.length, delay: d.delay,
        longest: waiting.reduce(function (s, x) { return Math.max(s, x.wait); }, 0), next: mine.some(function (x) { return x.st === "inconsult"; }) ? "In consult" : "Ready", room: d.room });
    });
    A.opsDocs.forEach(function (x) { var y = Object.assign({}, x); if (y.n === "solo") { var s = soloDoc(); y.n = s.n; y.spec = s.spec; } rows.push(y); });
    return rows.map(function (r) { r.fac = fs[r.f]; return r; });
  }
  NB.screen("owner.ops", function (c) {
    var fs = facs(), sel = NB.v("opsFac", "all"), docs = opsDocs().filter(function (r) { return sel === "all" || String(r.f) === sel; });
    var h = ui.header(L("Live operations", "العمليات المباشرة", "लाइव ऑपरेशन"), "Updated " + num("10:02:14") + " · refreshes every 15 s · " + docs.length + " doctors in session",
      ui.pill("sync", "Live") + ui.btn(L("Broadcast delay", "بث إشعار تأخير", "देरी प्रसारित करें"), { icon: "clockh", act: "toast", arg: "Delay notice sent to 7 patients of Dr. Sameer Kulkarni with options: wait, reschedule, other doctor" }));
    h += ui.seg("opsFac", [["all", "All facilities", fs.length]].concat(fs.map(function (f, i) { return [String(i), short(f)]; })));
    var alerts = [
      ["crit", "flask", "Lab turnaround breached · 3 samples", 1, "HbA1c 196 min (TAT 180) · Lipid 262 min (240) · Kidney function 205 min (180)", ["Open lab worklist", { go: "lab.worklist", icon: "list" }]],
      ["warn", "clockh", "Dr. Sameer Kulkarni running 25 min late", 1, "7 waiting · all told on WhatsApp at 09:48 · 2 chose to reschedule, 1 moved to Dr. Anjali Menon", ["Rebalance queue", { act: "toast", arg: "Offered 3 patients a switch to Dr. Anjali Menon (ENT triage first) — awaiting replies", icon: "users" }]],
      ["warn", "pill", "Pharmacy wait 14 min (target 8)", 1, "11 e-prescriptions waiting · 1 pharmacist on the counter", ["Call second pharmacist", { act: "toast", arg: "Rahul Joshi asked to open counter 2 at " + fs[1].name, icon: "phone" }]],
      ["warn", "clockh", "Dr. Vikram Shah running 15 min late", 0, "6 patients notified · ETAs recomputed · no one has left", ["View arrivals", { go: "reception.arrivals", icon: "arrivals" }]],
      ["info", "bed", "ICU 2 of 3 beds occupied · 1 paediatric bed reserved", 1, "Reserved for OPD token T-19 (ear pain, possible admission) · A-04 ready at 10:40", ["Bed board", { go: "ward.beds", icon: "bed" }]],
      ["info", "bell", "2 booking requests waiting over 10 min", 0, "Review mode for new patients · designated reviewer: reception", ["Open requests", { go: "reception.requests", icon: "bell" }]]
    ].filter(function (a) { return sel === "all" || String(a[3]) === sel; });
    h += ui.card(L("Alerts", "تنبيهات", "अलर्ट") + ' <span class="nb-badge">' + alerts.length + "</span>", '<div class="lst">' + alerts.map(function (a) {
      return ui.li('<span class="pill ' + a[0] + '" style="height:32px;width:32px;padding:0;justify-content:center">' + ui.icon(a[1], "sm") + "</span>",
        ui.esc(a[2]) + ' <span class="faint small">· ' + ui.esc(short(fs[a[3]])) + "</span>", ui.esc(a[4]), ui.btn(a[5][0], Object.assign({ cls: "sm" }, a[5][1])));
    }).join("") + (alerts.length ? "" : '<div class="empty">No alerts for this facility right now.</div>') + "</div>", { icon: "bell", cls: alerts.some(function (a) { return a[0] === "crit"; }) ? "crit" : "" });

    var st = function (r) { return r.st === "break" ? ui.pill("perm", "On break") : r.delay >= 20 ? ui.pill("crit", "+" + r.delay + " min late") : r.delay ? ui.pill("warn", "+" + r.delay + " min") : ui.pill("ok", "On time"); };
    h += '<div class="stack s8"><h2 class="h">' + L("Queues by doctor", "الطوابير حسب الطبيب", "डॉक्टर के अनुसार क़तार") + "</h2>" + ui.table(
      [{ h: "Doctor" }, { h: "Facility" }, { h: "Waiting", cls: "r" }, { h: "Longest wait" }, { h: "Next" }, { h: "Status" }, { h: "" }],
      docs.map(function (r) {
        return { cls: r.delay >= 20 ? "urg" : "", cells: [ui.cellp(ui.esc(r.n), ui.esc(r.spec + (r.room ? " · " + r.room : ""))), ui.cellp(ui.esc(short(r.fac)), ui.esc(NB.FACILITY_TYPES[r.fac.type].name)),
          num(r.wait), r.longest > 30 ? ui.pill("warn", r.longest + " min") : num(r.longest + " min"), ui.esc(r.next), st(r),
          r.delay ? ui.btn("Notify", { cls: "sm", act: "toast", arg: "Delay notice re-sent to " + r.wait + " patients of " + r.n, icon: "send" }) : ""] };
      }), { min: 820 }) + "</div>";

    // Beds, pharmacy, lab
    var bedRows = NB.D.beds.map(function (w) {
      var occ = w.beds.filter(function (b) { return b.st === "occ"; }).length, res = w.beds.filter(function (b) { return b.st === "reserved"; }).length, cl = w.beds.filter(function (b) { return b.st === "clean"; }).length;
      return [w.ward, occ / w.beds.length * 100, num(occ + "/" + w.beds.length) + (res ? ' <span class="faint small">+' + res + " reserved</span>" : "") + (cl ? ' <span class="faint small">+' + cl + " cleaning</span>" : "")];
    });
    var beds = (sel === "all" || sel === "1") ? ui.card(L("Beds · " + short(fs[1]), "الأسرّة", "बेड"), hbars(bedRows) + '<p class="small faint" style="margin:12px 0 0">2 discharges planned before 12:00 · average length of stay 3.4 days</p>', { icon: "bed", right: ui.btn("Bed board", { cls: "sm ghost", go: "ward.beds" }) })
      : ui.card(L("Beds", "الأسرّة", "बेड"), '<div class="empty">' + ui.esc(short(fs[+sel])) + " has no inpatient beds.</div>", { icon: "bed" });
    var ph = A.pharm.filter(function (p) { return sel === "all" || String(p.f) === sel; });
    var pharm = ui.card(L("Pharmacy wait", "انتظار الصيدلية", "फ़ार्मेसी प्रतीक्षा"), ph.length ? '<div class="stack">' + ph.map(function (p) {
      return '<div class="stack s4"><div class="row sb"><span class="small">' + ui.esc(short(fs[p.f])) + " · " + p.rx + " e-Rx waiting</span>" + (p.wait > p.target ? ui.pill(p.wait > p.target * 1.5 ? "crit" : "warn", p.wait + " min") : ui.pill("ok", p.wait + " min")) + "</div>" +
        ui.bar(p.wait / 20 * 100, p.wait > p.target * 1.5 ? "crit" : p.wait > p.target ? "warn" : "ok") + '<span class="tiny faint">Target ' + p.target + " min · " + p.staff + " on counter</span></div>";
    }).join("") + "</div>" : '<div class="empty">No pharmacy at this facility.</div>', { icon: "pill" });
    var lb = A.lab.filter(function (p) { return sel === "all" || String(p.f) === sel; });
    var lab = ui.card(L("Lab turnaround", "زمن إنجاز المختبر", "लैब TAT"), lb.length ? '<div class="stack">' + lb.map(function (p) {
      return '<div class="stack s4"><div class="row sb"><span class="small">' + ui.esc(short(fs[p.f])) + " · " + p.pending + " samples pending</span>" + (p.breach ? ui.pill(p.breach > 1 ? "crit" : "warn", p.breach + " over TAT") : ui.pill("ok", "Within TAT")) + "</div>" +
        p.items.map(function (x) { return '<span class="tiny muted">' + ui.icon("alertcircle", "xs warnc") + " " + ui.esc(x) + "</span>"; }).join("") + "</div>";
    }).join("") + "</div>" : '<div class="empty">No lab at this facility.</div>', { icon: "flask" });
    h += '<div class="grid g3">' + beds + pharm + lab + "</div>";
    return h;
  });

  /* ================= owner.finance ================= */
  NB.screen("owner.finance", function (c) {
    var m = c.m, fs = facs(), rg = NB.v("finRange", "mtd"), mul = { mtd: 1, q: 3.2, ytd: 11.4 }[rg], by = NB.v("finBy", "service"), P = payers();
    var total = A.svc.reduce(function (s, x) { return s + x[1]; }, 0) * mul;
    var h = ui.header(L("Revenue & dues", "الإيرادات والمستحقات", "राजस्व और बकाया"),
      { mtd: "1–17 Oct 2026", q: "Jul–Oct 2026 (quarter to date)", ytd: "Apr 2026 – today (financial year)" }[rg] + " · net of " + m.tax + " · " + ui.esc(m.einv),
      ui.seg("finRange", [["mtd", "Month"], ["q", "Quarter"], ["ytd", "Year"]]) + ui.btn("Export for accounts", { icon: "download", act: "toast", arg: "Revenue export prepared for " + (m.id === "india" ? "Tally" : "Zoho Books") + " (audited, no clinical fields)" }));
    h += '<div class="ad-tiles">' +
      tile("Revenue", cm(total).replace(/<[^>]+>/g, ""), "", { delta: "+6.2% vs last period", dk: "ok", spark: A.rev30.slice(16), target: "All facilities, all services" }) +
      tile("Collected", cm(total * 0.908).replace(/<[^>]+>/g, ""), "", { delta: "90.8% of billed", dk: "ok", target: "Cash, UPI/cards, insurance settled" }) +
      tile("Patient dues", cm(384000 * Math.sqrt(mul)).replace(/<[^>]+>/g, ""), "", { delta: "61 patients", dk: "warn", target: "Reminders go by WhatsApp with a payment link" }) +
      tile("Insurance outstanding", cm(2760000 * Math.sqrt(mul)).replace(/<[^>]+>/g, ""), "", { delta: "7.1% avg rejection", dk: "warn", target: m.claims }) +
      tile("Package liability", cm(1560000).replace(/<[^>]+>/g, ""), "", { delta: "634 sessions owed", dk: "info", target: "Paid in advance, not yet delivered" }) + "</div>";

    var rows = by === "service" ? A.svc.map(function (x) { return [x[0], x[1], cm(x[1] * mul)]; })
      : by === "doctor" ? A.byDoc.map(function (x) { return [x[0], x[1], cm(x[1] * mul) + ' <span class="faint small">· ' + Math.round(x[2] * mul) + " visits</span>"]; })
      : by === "payer" ? P.map(function (p, i) { return [p, A.payerShare[i], cm(total * A.payerShare[i] / 100) + ' <span class="faint small">· ' + A.payerShare[i] + "%</span>"]; })
      : fs.map(function (f, i) { var v = [3210000, 7600000, 520000][i]; return [short(f), v, cm(v * mul)]; });
    var bars = A.rev30.map(function (v, i) { return i % 7 === 5 ? v * 0.55 : v; });
    h += '<div class="grid g2">' +
      ui.card(L("Revenue by", "الإيرادات حسب", "राजस्व") + "…", ui.tabs("finBy", [["service", "Service"], ["doctor", "Doctor"], ["payer", "Payer"], ["facility", "Facility"]]) + '<div style="margin-top:16px">' + hbars(rows) + "</div>", { icon: "chart" }) +
      ui.card(L("Daily revenue · last 30 days", "الإيراد اليومي · ٣٠ يومًا", "दैनिक राजस्व · 30 दिन"),
        cols(bars.map(function (v) { return Math.round(v * 10) / 10; }), { max: 5, unit: "", h: 170, labels: ["18 Sep", "", "", "", "", "", "", "", "", "", "", "", "", "", "2 Oct", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "17 Oct"],
          title: function (v) { return (m.id === "india" ? "₹" + v + "L" : (v * 1e5 * m.fx / 1000).toFixed(1) + "K " + m.cur); }, aria: "Daily revenue for 30 days", cls: function (v, i) { return i % 7 === 5 ? "lo" : ""; } }) +
        '<p class="small faint" style="margin:10px 0 0">' + (m.id === "india" ? "Lakh ₹ per day." : "Scaled per day in " + m.cur + ".") + " Lighter bars are " + (NB.gcc() ? "Fridays" : "Sundays") + " (OPD closed, IPD and pharmacy only).</p>", { icon: "activity" }) + "</div>";

    var aging = ui.table([{ h: "Payer" }, { h: "0–30 days", cls: "r" }, { h: "31–60", cls: "r" }, { h: "61–90", cls: "r" }, { h: "90+", cls: "r" }, { h: "Rejection", cls: "r" }, { h: "Claims open", cls: "r" }],
      A.aging.map(function (a) { return [ui.cellp(ui.esc(P[a[0]]), ui.esc(m.claims)), cm(a[1]), cm(a[2]), cm(a[3]), a[4] ? '<span class="warnc">' + cm(a[4]) + "</span>" : "—", a[5] > 6 ? ui.pill("warn", a[5] + "%") : num(a[5] + "%"), num(a[6])]; }), { min: 720 });
    var dues = '<div class="lst">' + A.dues.map(function (d) {
      var p = NB.pat(d[0]);
      return ui.li(ui.av(p.name), ui.esc(NB.pname(p)) + " · " + NB.money(d[1]), ui.esc(d[2]) + " · " + d[3] + " days", d[3] > 30 ? ui.pill("warn", "Overdue") : ui.btn("Send link", { cls: "sm", act: "toast", arg: "Payment link sent to " + p.name + " on WhatsApp", icon: "send" }));
    }).join("") + "</div>";
    h += '<div class="split wide"><div class="stack s8"><h2 class="h">' + L("Insurance receivables", "مستحقات التأمين", "बीमा प्राप्य") + "</h2>" + aging + "</div>" + ui.card(L("Patient dues", "مستحقات المرضى", "मरीज़ बकाया"), dues, { icon: "wallet" }) + "</div>";
    h += '<div class="stack s8"><div class="row sb"><h2 class="h">' + L("Package liability", "التزامات الباقات", "पैकेज देनदारी") + '</h2><span class="small faint">Revenue is recognised as sessions are delivered</span></div>' +
      ui.table([{ h: "Package" }, { h: "Active", cls: "r" }, { h: "Sessions owed", cls: "r" }, { h: "Unearned value", cls: "r" }, { h: "Expiring in 30 days" }],
        A.pkgs.map(function (p) { return [ui.esc(p[0]), num(p[1]), num(p[2]), cm(p[3]), p[4] > 5 ? ui.pill("warn", p[4] + " packages") : ui.pill("mute", p[4] + " packages")]; }), { min: 640 }) + "</div>";
    return h;
  });

  /* ================= owner.workflows (key) ================= */
  var VT = [["new", "New patient"], ["fu", "Follow-up"], ["walk", "Walk-in"], ["proc", "Procedure / package"], ["tele", "Teleconsult"]];
  var REVIEWERS = [["reception", "Reception"], ["doctor", "Doctor"], ["nurse", "Nurse"], ["manager", "Practice manager"], ["callcentre", "Call centre"]];
  var PRESETS = {
    solo: { f: { solo: true, checkin: "qr", eta: "range", leave: "20", walk: "1:3", grace: "10", automark: true, pull: true, deposit: false },
      v: { accept: "auto", reviewer: "doctor", cap: "30", triage: false, vitals: false, checkout: "after" } },
    clinic: { f: { solo: false, checkin: "wa", eta: "range", leave: "20", walk: "1:3", grace: "15", automark: true, pull: true, deposit: false },
      v: { accept: "hybrid", reviewer: "reception", cap: "40", triage: false, vitals: false, checkout: "after" }, vt: { new: { vitals: true } } },
    poly: { f: { solo: false, checkin: "wa", eta: "range", leave: "20", walk: "1:3", grace: "15", automark: true, pull: true, deposit: false },
      v: { accept: "hybrid", reviewer: "reception", cap: "40", triage: false, vitals: true, checkout: "after" }, vt: { proc: { checkout: "before" }, tele: { checkout: "before", vitals: false } } },
    hospital: { f: { solo: false, checkin: "desk", eta: "position", leave: "30", walk: "1:2", grace: "20", automark: true, pull: true, deposit: true },
      v: { accept: "review", reviewer: "callcentre", cap: "none", triage: true, vitals: true, checkout: "before" }, vt: { fu: { accept: "hybrid", triage: false }, tele: { vitals: false, triage: false } } }
  };
  function presetOf(fi) { var t = facs()[fi].type; return t === "solo" ? "solo" : t === "hospital" ? "hospital" : "poly"; }
  function W(fi, k) { return NB.v("wf" + fi + "_" + k, PRESETS[presetOf(fi)].f[k]); }
  function WV(fi, vt, k) {
    var P = PRESETS[presetOf(fi)], d = P.vt && P.vt[vt] && P.vt[vt][k] != null ? P.vt[vt][k] : P.v[k];
    return NB.v("wf" + fi + "_" + vt + "_" + k, d);
  }
  function wfSnapshot(fi) { var o = {}; Object.keys(NB.state.ui).forEach(function (k) { if (k.indexOf("wf" + fi + "_") === 0) o[k] = NB.state.ui[k]; }); return JSON.stringify(o); }
  function curWfFac() { return NB.v("wfFac", NB.state.facType === "solo" ? "2" : String(NB.state.facility)); }
  var ETA_TXT = {
    exact: ["Exact time", "“Your consult starts at 10:42”", "Best when ETA accuracy is above 90%."],
    range: ["Range (±10 min)", "“Expected 10:35 – 10:55”", "Honest when consult times vary. Recommended."],
    position: ["Position in line only", "“You are 3rd in line”", "No clock time, so no broken promises."],
    delay: ["Delay notices only", "“Dr. Shah is running 15 min late”", "Patients see nothing unless the doctor is late."],
    hidden: ["Hidden", "Patients see their booking time only", "No ETA, no leave-home alert."]
  };
  var CHECKIN = {
    qr: ["Self check-in by QR", "Patient scans the door QR; no desk needed", "qr"], wa: ["WhatsApp “I'm here”", "One tap on the reminder message", "chat"],
    geo: ["Automatic (geofence ≤ 200 m)", "Checked in when the phone arrives; patient confirms", "map"], desk: ["Reception only", "Front desk checks every patient in", "arrivals"]
  };
  function journey(fi, vt) {
    var solo = W(fi, "solo"), acc = WV(fi, vt, "accept"), rev = solo ? "doctor" : WV(fi, vt, "reviewer"), cap = WV(fi, vt, "cap"),
      ci = W(fi, "checkin"), eta = W(fi, "eta"), leave = W(fi, "leave"), tri = WV(fi, vt, "triage"), vit = WV(fi, vt, "vitals"), co = WV(fi, vt, "checkout");
    if (solo && ci === "desk") ci = "qr";
    var F = A.F[fi], S = [], tele = vt === "tele", walk = vt === "walk";
    var revName = (REVIEWERS.find(function (r) { return r[0] === rev; }) || REVIEWERS[0])[1];
    var auto = acc === "auto" || (acc === "hybrid" && (vt === "fu" || vt === "proc"));
    var etaMsg = { exact: "Your consult starts at 10:42", range: "Expected 10:35–10:55 · you are 3rd", position: "You are 3rd in line", delay: "Only if late: “Doctor running 15 min late”", hidden: "" }[eta];
    if (walk) S.push({ ic: "qr", t: "Walks in, gets a token", who: solo ? "Patient scans the door QR" : "Door QR or reception", min: 1, k: "pt", msg: "Token T-23 · live queue link" });
    else {
      S.push({ ic: tele ? "monitor" : "chat", t: tele ? "Books a video consult" : "Books on WhatsApp, app or web", who: "Patient · about 60 s", min: 0, k: "pt" });
      S.push(auto ? { ic: "bolt", t: "Confirmed automatically", who: "Nabd · " + (cap === "none" ? "no daily cap" : "until " + cap + " bookings/day, then " + (solo ? "doctor" : revName.toLowerCase()) + " reviews"), min: 0, k: "auto", msg: "Booking confirmed · token and add-to-calendar" }
        : { ic: "clipboard", t: "Reviewed and accepted", who: revName + " · " + (acc === "hybrid" ? "new patients are reviewed" : "every booking reviewed") + " · median 4 min", min: 0, k: "staff", msg: "Request received · we confirm within 10 min" });
    }
    if (!walk) {
      if (eta === "hidden") S.push({ ic: "hourglass", t: "No ETA shown", who: "Patient sees the booking time only", min: 0, k: "auto", skip: true, why: "ETA display is hidden" });
      else S.push({ ic: "hourglass", t: "Live time on WhatsApp", who: ETA_TXT[eta][0] + (eta !== "delay" && !tele ? " · leave-home alert " + leave + " min before" : ""), min: 0, k: "auto", msg: etaMsg + (eta !== "delay" && !tele ? " · “Leave home now”" : "") });
    }
    if (tele) S.push({ ic: "smartphone", t: "Joins from the link", who: "No check-in needed", min: 0, k: "pt" });
    else if (!walk) S.push(solo && W(fi, "checkin") === "desk" ? { ic: "qr", t: "Self check-in by QR", who: "Reception check-in is hidden in solo mode", min: 0.3, k: "pt" }
      : { ic: CHECKIN[ci][2], t: CHECKIN[ci][0], who: CHECKIN[ci][1], min: ci === "desk" ? 2.5 : ci === "geo" ? 0 : 0.3, k: ci === "desk" ? "staff" : "pt", msg: ci === "desk" ? "" : "Checked in · you are 3rd" });
    if (!tele && co === "before") S.push(solo ? { ic: "wallet", t: "Pays before consult", who: "UPI / card link on WhatsApp", min: 1, k: "pt" } : { ic: "receipt", t: "Pays before consult", who: "Billing desk · " + mk().tax, min: 3, k: "staff" });
    if (tele && co === "before") S.push({ ic: "wallet", t: "Prepays by link", who: "Payment link with the booking", min: 0, k: "pt" });
    if (solo && !tele) S.push({ ic: "arrivals", t: "Reception steps", who: "Hidden in solo mode: the doctor runs queue and billing", min: 0, k: "staff", skip: true, why: "solo" });
    if (!tele) {
      if (tri && !solo) S.push({ ic: "heartpulse", t: "Triage", who: "Nurse · priority and red flags", min: 3, k: "staff" });
      else S.push({ ic: "heartpulse", t: "Triage", who: solo ? "Off in solo mode" : "Off for this visit type", min: 0, k: "staff", skip: true });
      if (vit && !solo) S.push({ ic: "thermo", t: "Nurse vitals", who: "BP, pulse, SpO₂, weight → doctor's screen", min: 4, k: "staff" });
      else S.push({ ic: "thermo", t: "Nurse vitals", who: solo ? "Off in solo mode (doctor records if needed)" : "Off for this visit type", min: 0, k: "staff", skip: true });
      S.push({ ic: "hourglass", t: "Waits", who: "Walk-ins " + (W(fi, "walk") === "none" ? "not accepted" : W(fi, "walk") === "slots" ? "only in reserved slots" : "interleaved " + W(fi, "walk") + " with appointments"), min: Math.max(2, F.wait - (vit ? 2 : 0) - (tri ? 1 : 0)), k: "pt", msg: "Your turn · " + (solo ? "come in" : "Room 3") });
    }
    S.push({ ic: "steth", t: tele ? "Video consult" : "Consultation", who: solo ? "Doctor · queue, notes, e-Rx in one screen" : "Doctor · e-Rx with safety checks", min: F.consult, k: "staff" });
    if (co !== "before") S.push(solo ? { ic: "wallet", t: "Doctor takes payment", who: "UPI QR / card at the end of the consult", min: 1, k: "staff", msg: "Receipt and e-Rx on WhatsApp" }
      : { ic: "receipt", t: "Checkout", who: "Billing desk · " + mk().tax + " invoice", min: 4, k: "staff", msg: "Receipt and e-Rx on WhatsApp" });
    else S.push({ ic: "send", t: "e-Rx and receipt sent", who: "WhatsApp, secure expiring link", min: 0, k: "auto", msg: "Prescription ready · QR for any pharmacy" });
    return S;
  }
  function journeyStrip(S) {
    return '<div class="ad-jstrip">' + S.filter(function (s) { return !s.skip; }).map(function (s, i) {
      return (i ? '<span class="ad-jarr">' + ui.icon("chevright", "sm flip") + "</span>" : "") + '<span class="ad-jc ' + (s.k === "auto" ? "auto" : s.k === "pt" ? "pt" : "") + '">' + ui.icon(s.ic, "sm") + ui.esc(s.t) + "</span>";
    }).join("") + "</div>";
  }
  function journeyCard(fi, vt) {
    var S = journey(fi, vt), on = S.filter(function (s) { return !s.skip; }), inClinic = 0, d2d = 0, seen = false;
    on.forEach(function (s) { inClinic += s.min; if (s.ic === "steth") seen = true; if (!seen) d2d += s.min; });
    var body = '<div class="row" style="margin-bottom:14px;gap:8px">' + ui.pill("acc", Math.round(inClinic) + " min in clinic") + (vt !== "tele" ? ui.pill("mute", "Door to doctor ~" + Math.round(d2d) + " min") : "") +
      ui.pill("info", on.filter(function (s) { return s.k === "staff"; }).length + " staff touches") + "</div>";
    body += '<div class="tl ad-jv">' + S.map(function (s) {
      return '<div class="ev' + (s.skip ? " skip" : "") + '"><span class="dotc ' + (s.skip ? "" : s.k === "auto" ? "acc" : s.k === "pt" ? "" : "ok") + '">' + ui.icon(s.ic, "sm") + '</span><div class="bd"><b>' + ui.esc(s.t) +
        (s.min && !s.skip ? ' <span class="faint small num ltr">· ' + (s.min < 1 ? Math.round(s.min * 60) + " s" : Math.round(s.min) + " min") + "</span>" : "") + "</b><small>" + ui.esc(s.who) + "</small>" +
        (s.msg && !s.skip ? '<span class="ad-msg">' + ui.icon("chat", "xs") + ui.esc(s.msg) + "</span>" : "") + "</div></div>";
    }).join("") + "</div>";
    body += '<div class="ad-legend" style="margin-top:4px"><span><i style="background:var(--acc)"></i>Automatic</span><span><i style="background:var(--ok)"></i>Staff step</span><span><i style="background:var(--s3)"></i>Patient does it</span></div>';
    return ui.card(L("Patient journey preview", "معاينة رحلة المريض", "मरीज़ यात्रा पूर्वावलोकन"), body, { icon: "map", cls: "hl ad-sticky", right: ui.pill("sync", "Live preview") });
  }

  NB.screen("owner.workflows", function (c) {
    var fs = facs(), fi = +curWfFac(), f = fs[fi], vt = NB.v("wfVt", "new"), vtName = VT.find(function (x) { return x[0] === vt; })[1];
    var solo = W(fi, "solo"), k = function (x) { return "wf" + fi + "_" + x; }, kv2 = function (x) { return "wf" + fi + "_" + vt + "_" + x; };
    var dirty = wfSnapshot(fi) !== (A.wfPub[fi] || "{}");
    var h = ui.header(L("Workflows & booking rules", "سير العمل وقواعد الحجز", "वर्कफ़्लो और बुकिंग नियम"),
      "Per facility and visit type. Changes apply from the next booking; every change is audited with before and after values.",
      ui.btn("Change history", { icon: "history", go: "owner.compliance", cls: "ghost" }) + ui.btn(L("Publish", "نشر", "प्रकाशित करें") + " v" + (A.wfVer[fi] + 1), { cls: "pri", icon: "check", act: "wfpublish", disabled: !dirty }));
    h += '<div class="row sb">' + ui.seg("wfFac", fs.map(function (x, i) { return [String(i), short(x) + " · " + NB.FACILITY_TYPES[x.type].name]; }), curWfFac()) +
      (dirty ? ui.pill("warn", "Unpublished changes") : ui.pill("ok", "Published · v" + A.wfVer[fi])) + "</div>";
    h += ui.card(L("Start from a preset", "ابدأ من إعداد جاهز", "प्रीसेट से शुरू करें"), '<div class="row" style="gap:8px">' + [["solo", "Solo doctor", "user"], ["clinic", "Specialty clinic", "steth"], ["poly", "Polyclinic", "building"], ["hospital", "Hospital", "bed"]].map(function (p) {
      return ui.btn(p[1], { cls: "sm" + (presetOf(fi) === p[0] ? " okb" : ""), icon: p[2], act: "wfpreset", arg: p[0] });
    }).join("") + '<span class="small faint">Presets fill every setting below for ' + ui.esc(f.name) + "; you can then adjust anything.</span></div>", { icon: "layers" });
    h += '<div class="stack s8"><div class="eyebrow">' + L("Resulting journey · ", "الرحلة الناتجة · ", "परिणामी यात्रा · ") + ui.esc(vtName) + "</div>" + journeyStrip(journey(fi, vt)) + "</div>";
    h += ui.tabs("wfVt", VT.map(function (x) { return [x[0], x[1], { new: "userplus", fu: "refresh", walk: "arrivals", proc: "layers", tele: "monitor" }[x[0]]]; }));

    var L1 = ui.card(L("Solo-doctor mode", "وضع الطبيب المنفرد", "एकल-डॉक्टर मोड") + " · " + ui.esc(short(f)),
      ui.toggle(k("solo"), "Run this facility without a front desk", "Hides reception steps. The doctor gets the queue, consult and billing on one screen or a phone; check-in is self-service.", PRESETS[presetOf(fi)].f.solo) +
      (solo ? ui.note("info", "Reviewer is the doctor, triage and nurse vitals are off, and the doctor takes payment at the end of the consult (UPI QR / card).") : ""), { icon: "user", cls: solo ? "hl" : "" });

    var acc = WV(fi, vt, "accept");
    var accCard = vt === "walk" ? ui.card(L("Booking acceptance", "قبول الحجز", "बुकिंग स्वीकृति") + " · " + vtName, ui.note("info", "Walk-ins do not book. They get a token from the door QR" + (solo ? "" : " or reception") + " and join the queue under the interleave rule below."), { icon: "bell" })
      : ui.card(L("Booking acceptance", "قبول الحجز", "बुकिंग स्वीकृति") + " · " + vtName, '<div class="stack s8">' +
        ui.radio(kv2("accept"), "auto", "Auto-accept with a daily cap", "Confirmed instantly while slots and the cap allow. Over the cap, bookings go to review.", acc) +
        ui.radio(kv2("accept"), "review", "Review each booking", "A designated role accepts, suggests another slot or declines. Patients are told within 10 min.", acc) +
        ui.radio(kv2("accept"), "hybrid", "Hybrid", "Auto for returning patients and follow-ups; review for patients new to the doctor.", acc) + "</div>" +
        '<div class="stack s16" style="margin-top:14px"><div class="stack s8"><span class="small faint">Who reviews</span>' + (solo ? ui.pill("acc", "Doctor (solo mode)") : ui.seg(kv2("reviewer"), REVIEWERS, WV(fi, vt, "reviewer"))) + "</div>" +
        '<div class="stack s8"><span class="small faint">Daily auto-accept cap per doctor</span>' + ui.seg(kv2("cap"), [["20", "20"], ["30", "30"], ["40", "40"], ["60", "60"], ["none", "No cap"]], WV(fi, vt, "cap")) + "</div></div>", { icon: "bell" });

    var ci = W(fi, "checkin");
    var ciCard = ui.card(L("Arrival & check-in", "الوصول وتسجيل الحضور", "आगमन और चेक-इन"), '<div class="grid g2" style="gap:8px">' + Object.keys(CHECKIN).map(function (x) {
      return x === "desk" && solo ? '<div class="radio" style="opacity:.5;cursor:not-allowed"><span class="dot"></span><div class="fl"><b>Reception only</b><small>Not available in solo mode</small></div></div>' : ui.radio(k("checkin"), x, CHECKIN[x][0], CHECKIN[x][1], ci);
    }).join("") + "</div>" + '<p class="small faint" style="margin:10px 0 0">Applies to every visit type at ' + ui.esc(short(f)) + ". Reception can always override and check a patient in.</p>", { icon: "qr" });

    var eta = W(fi, "eta");
    var etaCard = ui.card(L("Time shown to patients", "الوقت المعروض للمرضى", "मरीज़ों को दिखाया गया समय"), '<div class="stack s8">' + Object.keys(ETA_TXT).map(function (x) {
      return ui.radio(k("eta"), x, ETA_TXT[x][0] + " — " + ETA_TXT[x][1], ETA_TXT[x][2], eta);
    }).join("") + "</div>" + '<div class="stack s8" style="margin-top:14px"><span class="small faint">“Leave home now” alert: send when the ETA is within the patient’s travel time plus</span>' +
      (eta === "hidden" || eta === "delay" ? ui.pill("perm", "Off with this display mode") : ui.seg(k("leave"), [["10", "10 min"], ["15", "15 min"], ["20", "20 min"], ["30", "30 min"]], W(fi, "leave"))) +
      '<span class="tiny faint">ETA accuracy here is ' + A.F[fi].eta + "% within ±10 min." + (A.F[fi].eta < 85 && eta === "exact" ? " Exact times will often be wrong; range is safer." : "") + "</span></div>", { icon: "hourglass" });

    var qCard = ui.card(L("Queue rules", "قواعد الطابور", "क़तार नियम"), '<div class="stack s8"><span class="small faint">Walk-ins interleaved with appointments</span>' +
      ui.seg(k("walk"), [["1:2", "1 per 2"], ["1:3", "1 per 3"], ["1:4", "1 per 4"], ["slots", "Reserved slots only"], ["none", "No walk-ins"]], W(fi, "walk")) + "</div>" +
      '<div class="stack s8" style="margin-top:14px"><span class="small faint">No-show grace period after the booked time</span>' + ui.seg(k("grace"), [["10", "10 min"], ["15", "15 min"], ["20", "20 min"]], W(fi, "grace")) + "</div>" +
      '<div style="margin-top:6px">' + ui.toggle(k("automark"), "Mark no-show automatically after the grace period", "Patient gets a WhatsApp rebook link; staff can undo.", PRESETS[presetOf(fi)].f.automark) +
      ui.toggle(k("pull"), "Pull the next patient forward and recompute ETAs", "Everyone behind is told their new time.", PRESETS[presetOf(fi)].f.pull) +
      ui.toggle(k("deposit"), "Ask for a deposit after 2 no-shows in 90 days", "Off by default. Waived for minors, seniors and insured patients.", PRESETS[presetOf(fi)].f.deposit) + "</div>", { icon: "list" });

    var tele = vt === "tele";
    var clin = ui.card(L("Clinical steps", "الخطوات السريرية", "क्लिनिकल चरण") + " · " + vtName,
      (solo ? ui.note("info", "Triage and nurse vitals are off in solo mode.") : tele ? ui.note("info", "Teleconsults skip triage and nurse vitals; patients can share home readings before the call.") :
        ui.toggle(kv2("triage"), "Triage step", "Nurse sets priority and checks red flags before the queue.", WV(fi, vt, "triage")) +
        ui.toggle(kv2("vitals"), "Nurse vitals step", "BP, pulse, SpO₂, temperature, weight go straight to the doctor's screen.", WV(fi, vt, "vitals"))) +
      '<div class="stack s8" style="margin-top:12px"><span class="small faint">Checkout</span><div class="grid g2" style="gap:8px">' +
      ui.radio(kv2("checkout"), "before", tele ? "Prepay by link" : "Before the consult", tele ? "Booking is held until paid." : "Pay or verify insurance first; suits procedures and cash hospitals.", WV(fi, vt, "checkout")) +
      ui.radio(kv2("checkout"), "after", "After the consult", solo ? "Doctor takes payment at the end." : "Bill includes what the doctor ordered.", WV(fi, vt, "checkout")) + "</div></div>", { icon: "heartpulse" });

    var glance = ui.table([{ h: "Visit type" }, { h: "Acceptance" }, { h: "Reviewer" }, { h: "Cap" }, { h: "Triage" }, { h: "Vitals" }, { h: "Checkout" }],
      VT.map(function (x) {
        var a = WV(fi, x[0], "accept"), on = function (b) { return b && !solo && x[0] !== "tele" ? ui.pill("ok", "On") : ui.pill("mute", "Off"); };
        return { cls: x[0] === vt ? "sel" : "", act: "wfvt", arg: x[0], cells: [ui.esc(x[1]), x[0] === "walk" ? '<span class="faint">Token</span>' : ui.pill(a === "auto" ? "acc" : a === "hybrid" ? "info" : "warn", { auto: "Auto", review: "Review", hybrid: "Hybrid" }[a]),
          x[0] === "walk" || a === "auto" ? "—" : solo ? "Doctor" : ui.esc((REVIEWERS.find(function (r) { return r[0] === WV(fi, x[0], "reviewer"); }) || [0, "—"])[1]),
          x[0] === "walk" ? "—" : num(WV(fi, x[0], "cap") === "none" ? "—" : WV(fi, x[0], "cap")), on(WV(fi, x[0], "triage")), on(WV(fi, x[0], "vitals")), WV(fi, x[0], "checkout") === "before" ? "Before" : "After"] };
      }), { min: 640 });

    var left = '<div class="stack s16">' + L1 + accCard + ciCard + etaCard + qCard + clin +
      '<div class="stack s8"><h2 class="h">' + L("All visit types at a glance", "كل أنواع الزيارات", "सभी विज़िट प्रकार") + " · " + ui.esc(short(f)) + "</h2>" + glance + "</div>" +
      '<div class="ad-bar-foot"><span class="small muted">' + ui.icon("shieldcheck", "sm acc") + " Publishing needs your TOTP step-up and is written to the audit log. Patients already booked keep the rules they booked under.</span>" +
      '<div class="btns">' + ui.btn("Discard", { cls: "ghost", act: "wfdiscard", disabled: !dirty }) + ui.btn("Publish v" + (A.wfVer[fi] + 1), { cls: "pri", icon: "check", act: "wfpublish", disabled: !dirty }) + "</div></div></div>";
    return h + '<div class="split wide">' + left + '<div class="stack s16">' + journeyCard(fi, vt) + "</div></div>";
  });
  NB.act("wfvt", function (a) { NB.setUI({ wfVt: a }); });
  NB.act("wfpreset", function (p) {
    var fi = curWfFac(), P = PRESETS[p], o = {};
    Object.keys(P.f).forEach(function (k) { o["wf" + fi + "_" + k] = P.f[k]; });
    VT.forEach(function (x) { Object.keys(P.v).forEach(function (k) { o["wf" + fi + "_" + x[0] + "_" + k] = P.vt && P.vt[x[0]] && P.vt[x[0]][k] != null ? P.vt[x[0]][k] : P.v[k]; }); });
    NB.setUI(o); NB.toast(({ solo: "Solo doctor", clinic: "Specialty clinic", poly: "Polyclinic", hospital: "Hospital" })[p] + " preset applied to " + facs()[+fi].name + " · review and publish");
  });
  NB.act("wfpublish", function () {
    var fi = +curWfFac(); A.wfVer[fi]++; A.wfPub[fi] = wfSnapshot(fi);
    NB.toast("Workflow v" + A.wfVer[fi] + " published for " + facs()[fi].name + " · effective from the next booking · audited");
  });
  NB.act("wfdiscard", function () {
    var fi = curWfFac(), pub = JSON.parse(A.wfPub[fi] || "{}");
    Object.keys(NB.state.ui).forEach(function (k) { if (k.indexOf("wf" + fi + "_") === 0) delete NB.state.ui[k]; });
    Object.assign(NB.state.ui, pub); NB.toast("Changes discarded");
  });

  /* ================= owner.privacy (key) ================= */
  var SIM_STAFF = [
    { id: "s1", n: "Dr. Vikram Shah · Medicine", dept: 0, role: "doctor" }, { id: "s2", n: "Dr. Nisha Bhat · Child Health", dept: 1, role: "doctor" },
    { id: "s3", n: "Dr. Khalid Al-Saeed · Heart Centre", dept: 2, role: "doctor" }, { id: "s8", n: "Dr. Rajesh Gupta · Emergency", dept: 5, role: "doctor" },
    { id: "s4", n: "Anita D'Souza · OPD nurse", dept: 0, role: "nurse" }, { id: "s5", n: "Sneha Kulkarni · Reception", dept: -1, role: "reception" }, { id: "s6", n: "Pooja Nair · Billing", dept: -1, role: "billing" }
  ];
  var SIM_REC = [
    { id: "r1", n: "Rakesh Menon · Medicine consult, 17 Oct", dept: 0, team: ["s1", "s4"] },
    { id: "r2", n: "Rakesh Menon · Psychiatry note", dept: 6, sens: "Mental health", team: ["s7"] },
    { id: "r3", n: "Ananya Rao · antenatal visit", dept: 4, sens: "Reproductive health", team: [] },
    { id: "r4", n: "Zoya Khan · Child Health visit, referred to Heart Centre", dept: 1, team: ["s2", "s4"], ref: [2] },
    { id: "r5", n: "Mohammed Ali · Heart Centre echo report", dept: 2, team: ["s3"] }
  ];
  function decide(st, rc, mode) {
    if (st.role === "reception" || st.role === "billing") return [false, "Platform floor: reception and billing roles never see clinical notes. They see demographics, queue and invoices only."];
    var team = rc.team.indexOf(st.id) >= 0;
    if (rc.sens) return team ? [true, "Care-team member · " + rc.sens + " is visible to the author and care team"] : [false, rc.sens + " is a sensitive category: author and care team only. This floor cannot be loosened. Break-glass is possible and is reviewed."];
    if (team) return [true, "Care-team member for this encounter"];
    var ov = A.dmx[st.dept + "|" + rc.dept];
    if (ov === "deny") return [false, "Department rule: " + A.depts[st.dept] + " may not see " + A.depts[rc.dept]];
    if (ov === "allow") return [true, "Department rule: " + A.depts[st.dept] + " may always see " + A.depts[rc.dept]];
    if (mode === "all") return [true, "Policy “All departments”: any clinician here can see non-sensitive records"];
    if (mode === "dept") {
      if (st.dept === rc.dept) return [true, "Same department"];
      if (rc.ref && rc.ref.indexOf(st.dept) >= 0) return [true, "Referred to the viewer's department · this encounter only"];
      return [false, "Policy “Same department + referred”: different department and not referred"];
    }
    return [false, "Policy “Care team only”: the viewer is not on this encounter's care team"];
  }
  function cstPill(c) { var s = CST[c.st] || ["mute", c.st]; return ui.pill(s[0], s[1]); }
  function dirPill(d) { return d === "out" ? ui.pill("info", "We share", "upload") : d === "in" ? ui.pill("sub", "We requested", "download") : ui.pill("acc", "Within group", "share"); }

  NB.screen("owner.privacy", function (c) {
    var tab = NB.v("pvTab", "inside"), m = c.m, fs = facs();
    var h = ui.header(L("Privacy & data sharing", "الخصوصية ومشاركة البيانات", "गोपनीयता और डेटा साझा"),
      "Records stay with the facility that wrote them. Sharing needs the patient and the doctor, is time-limited and every read is logged. " + ui.esc(m.privacyLaw) + ".",
      ui.pill("perm", "Policy v7 · TOTP to change"));
    h += ui.tabs("pvTab", [["inside", L("Inside the hospital", "داخل المستشفى", "अस्पताल के अंदर"), "building"], ["sharing", L("Sharing with other facilities", "المشاركة مع منشآت أخرى", "अन्य सुविधाओं से साझा"), "share"],
      ["notice", L("Notice, residency & retention", "الإشعار والإقامة والاحتفاظ", "सूचना, रेज़िडेंसी, रिटेंशन"), "file"], ["requests", L("Patient data requests", "طلبات بيانات المرضى", "मरीज़ डेटा अनुरोध"), "users"]]);
    if (tab === "inside") h += privInside(c);
    else if (tab === "sharing") h += privSharing(c);
    else if (tab === "notice") h += privNotice(c);
    else h += privRequests(c);
    return h;
  });

  function privInside(c) {
    var mode = NB.v("pvVis", "dept"), D = A.depts;
    var vis = ui.card(L("Who can see a record inside the facility", "من يرى السجل داخل المنشأة", "सुविधा के अंदर रिकॉर्ड कौन देख सकता है"), '<div class="stack s8">' +
      ui.radio("pvVis", "all", "All departments", "Any clinician at the facility can open non-sensitive records. Example: the ENT doctor can read last month's cardiology notes.", "dept") +
      ui.radio("pvVis", "dept", "Same department + referred (default)", "Clinicians see their own department's records, plus a referred encounter when a patient is sent to them. Example: Heart Centre sees the Child Health visit that referred Zoya.", "dept") +
      ui.radio("pvVis", "care", "Care team only", "Only the doctor, nurses and others who worked on the encounter. Example: a second physician in Medicine cannot open Rakesh's notes without being added.", "dept") +
      "</div>" + '<p class="small faint" style="margin:12px 0 0">' + ui.icon("lock", "xs") + " Floors that no facility setting can switch off: sensitive categories stay with the author and care team; reception and billing never see clinical notes; HIE opt-outs are honoured.</p>", { icon: "eye" });

    var head = '<tr><th>Viewer ↓ · Source →</th>' + D.map(function (d, j) { return "<th>" + ui.esc(d) + (j === 6 ? " " + ui.icon("lock", "xs") : "") + "</th>"; }).join("") + "</tr>";
    var body = D.map(function (d, i) {
      return "<tr><td><b class=\"small\">" + ui.esc(d) + "</b></td>" + D.map(function (s, j) {
        if (i === j) return '<td><span class="ad-mx own">' + ui.icon("check") + "Own</span></td>";
        if (j === 6) return '<td><span class="ad-mx lock" title="Platform floor: mental-health records stay with author and care team">' + ui.icon("lock") + "Floor</span></td>";
        var v = A.dmx[i + "|" + j] || "def";
        return '<td><button class="ad-mx ' + (v === "def" ? "" : v) + '" data-act="pvcell" data-arg="' + i + "|" + j + '" title="Click to cycle Default → Allow → Deny">' +
          (v === "allow" ? ui.icon("checkcircle") + "Allow" : v === "deny" ? ui.icon("close") + "Deny" : "Default") + "</button></td>";
      }).join("") + "</tr>";
    }).join("");
    var mx = ui.card(L("Department rules", "قواعد الأقسام", "विभाग नियम"), '<p class="small muted" style="margin:0 0 12px">Exceptions to the policy above. “Default” follows the policy; click a cell to allow or deny. Example: Emergency may always see every department.</p>' +
      '<div class="tw"><table class="t" style="min-width:860px"><thead>' + head + "</thead><tbody>" + body + "</tbody></table></div>", { icon: "grid" });

    var sens = ui.card(L("Sensitive categories · always restricted", "فئات حساسة · مقيدة دائمًا", "संवेदनशील श्रेणियाँ · हमेशा प्रतिबंधित"), '<div class="lst">' + A.sens.map(function (s, i) {
      var key = "pvSens" + i;
      return ui.li('<span class="av">' + ui.icon(s[2], "sm") + "</span>", ui.esc(s[0]), "Auto-labelled from " + ui.esc(s[1]),
        '<div class="row nw" style="gap:6px">' + ui.seg(key, [["team", "Author + care team"], ["author", "Author only"]], i === 0 ? "team" : i === 5 ? "author" : "team") + "</div>");
    }).join("") + "</div>" + ui.note("info", "You can tighten a category to <b>Author only</b>. You cannot loosen it: the platform floor is author and care team, and guardians never see a teenager's reproductive or mental-health records."), { icon: "lock", right: ui.pill("perm", "Platform floor") });

    var bgRoles = [["pvBgDoc", "Doctors", true], ["pvBgEd", "Emergency doctors", true], ["pvBgNurse", "Nurse in-charge", true], ["pvBgRes", "Resident doctors", false]];
    var bgEvents = '<div class="lst">' + A.bg.map(function (b) {
      var p = NB.pat(b.p);
      return ui.li('<span class="pill ' + (b.st === "due" ? "warn" : b.st === "ok" ? "ok" : "crit") + '" style="height:32px;width:32px;padding:0;justify-content:center">' + ui.icon(b.st === "bad" ? "alertfill" : "unlock", "sm") + "</span>",
        ui.esc(b.who) + " → " + ui.esc(NB.pname(p)), ui.esc(b.when + " · " + b.scope), b.st === "due" ? ui.pill("warn", "Review due") : b.st === "ok" ? ui.pill("ok", "Justified") : ui.pill("crit", "Unjustified"), { ov: "bgreview", arg: b.id });
    }).join("") + "</div>";
    var bg = ui.card(L("Break-glass (emergency access)", "كسر الزجاج (وصول طارئ)", "ब्रेक-ग्लास (आपात पहुँच)"),
      '<div class="grid g2"><div><span class="small faint">Who may use it</span>' + bgRoles.map(function (r) { return ui.toggle(r[0], r[1], "", r[2]); }).join("") + "</div>" +
      '<div class="stack s8"><span class="small faint">Access window</span>' + ui.seg("pvBgWin", [["30", "30 min"], ["60", "60 min"], ["120", "2 h"], ["240", "4 h"]], "60") +
      '<span class="small faint" style="margin-top:6px">Post-hoc review by</span>' + ui.seg("pvBgRev", [["po", "Privacy officer"], ["md", "Medical director"], ["both", "Both"]], "po") +
      '<span class="small faint" style="margin-top:6px">Review within</span>' + ui.seg("pvBgDue", [["24", "24 h"], ["72", "72 h"], ["168", "7 days"]], "72") +
      '<div class="chips" style="margin-top:6px">' + ui.pill("perm", "Reason required") + ui.pill("perm", "TOTP step-up") + ui.pill("perm", "One patient only") + "</div></div></div>" +
      ui.toggle("pvBgNotify", "Tell the patient on WhatsApp when it happens", "“Emergency access by " + c.fac.name + " at 03:12.” Also shown in their access history.", true) +
      '<div class="stack s8" style="margin-top:8px"><div class="ad-rail-h"><b class="small">Recent events</b><span class="small faint">Select to review</span></div>' + bgEvents + "</div>", { icon: "unlock", cls: "warnb" });

    var sS = NB.v("pvSimS", "s3"), sR = NB.v("pvSimR", "r4");
    var st = SIM_STAFF.find(function (x) { return x.id === sS; }) || SIM_STAFF[0], rc = SIM_REC.find(function (x) { return x.id === sR; }) || SIM_REC[0], res = decide(st, rc, mode);
    var sim = ui.card(L("“Who can see this?” simulator", "محاكي «من يرى هذا؟»", "“यह कौन देख सकता है?” सिम्युलेटर"), '<div class="ad-sim">' +
      selField("Staff member", "pvSimS", SIM_STAFF.map(function (x) { return [x.id, x.n]; }), "s3") + selField("Record", "pvSimR", SIM_REC.map(function (x) { return [x.id, x.n]; }), "r4") + "</div>" +
      '<div style="margin-top:12px">' + ui.note(res[0] ? "ok" : "crit", "<b>" + (res[0] ? "Permit" : "Deny") + ".</b> " + ui.esc(res[1]), res[0] ? "checkcircle" : "lock") + "</div>" +
      '<div class="lst" style="margin-top:12px">' + SIM_REC.map(function (r) { var d = decide(st, r, mode); return ui.li('<span class="av">' + ui.icon(r.sens ? "lock" : "file", "sm") + "</span>", ui.esc(r.n), ui.esc(d[1]), ui.pill(d[0] ? "ok" : "crit", d[0] ? "Permit" : "Deny", d[0] ? "checkcircle" : "lock"), { act: "pvsimr", arg: r.id }); }).join("") + "</div>" +
      '<p class="small faint" style="margin:10px 0 0">Runs the same policy engine the product uses, with the settings on this page (including unpublished changes).</p>', { icon: "search" });
    return '<div class="grid g2">' + vis + sim + "</div>" + mx + '<div class="grid g2">' + sens + bg + "</div>";
  }
  NB.act("pvsimr", function (a) { NB.setUI({ pvSimR: a }); });
  NB.act("pvcell", function (a) { var v = A.dmx[a] || "def"; var n = v === "def" ? "allow" : v === "allow" ? "deny" : "def"; if (n === "def") delete A.dmx[a]; else A.dmx[a] = n; NB.render(); });

  function privSharing(c) {
    var m = c.m, dir = NB.v("pvDir", "all"), all = consents();
    var list = all.filter(function (x) { return dir === "all" || dirOf(x) === dir; });
    var cnt = function (d) { return all.filter(function (x) { return dirOf(x) === d; }).length; };
    var act = all.filter(function (x) { return x.st === "active"; }).length, pend = all.filter(function (x) { return /pending/.test(x.st); }).length;
    var h = '<div class="ad-tiles">' + tile("Active shares", String(act), "", { delta: "All time-limited", dk: "ok" }) + tile("Waiting for approval", String(pend), "", { delta: "SLA 4 working hours", dk: "warn" }) +
      tile("Reads this month", "38", "", { delta: "Every read logged", dk: "mute" }) + tile("Request → access", "1:42", "min", { delta: "Median, auto-release path", dk: "ok" }) + "</div>";
    h += (m.id === "india" ? ui.note("info", "For patients with an ABHA number, <b>ABDM's HIE-CM is the consent manager of record</b>. Nabd mirrors the ABDM artefact and still asks the treating doctor before releasing data as HIP.")
      : m.id === "uae" ? ui.note("info", "NABIDH opt-outs are honoured, even for emergency access. Nabd sharing sits on top of NABIDH and Malaffi and never overrides an HIE opt-out.")
        : ui.note("info", "Health data is sensitive personal data under " + ui.esc(m.privacyLaw) + ": sharing needs explicit patient approval and never leaves the " + ui.esc(m.residency) + "."));
    var appr = function (txt, who, st) {
      if (txt === "—" && st === "denied") return '<div class="small" style="white-space:nowrap"><span class="faint">' + who + " —</span></div>";
      var k = /Declined/.test(txt) ? ["critc", "close", "Declined"] : /Skipped/.test(txt) ? ["critc", "unlock", "Skipped"] : txt === "—" || /sent|Waiting/.test(txt) ? ["warnc", "hourglass", "Waiting"] : ["okc", "check", "Approved"];
      return '<div class="small" title="' + ui.esc(txt) + '" style="white-space:nowrap"><span class="faint">' + who + ' </span><span class="' + k[0] + '">' + ui.icon(k[1], "xs") + " " + k[2] + "</span></div>";
    };
    var rows = list.map(function (x) {
      var p = NB.pat(x.p), d = dirOf(x);
      var acts = (x.st === "active" || /pending/.test(x.st)) ? ui.btn("Revoke", { cls: "sm dng", ov: "revoke", arg: x.id }) : ui.btn("View", { cls: "sm", ov: "consentdetail", arg: x.id });
      return { ov: "consentdetail", arg: x.id, cls: x.st === "btg" ? "crit" : "", cells: [ui.cellp('<span class="tok">' + x.id + "</span>", ui.esc(NB.pname(p))),
        '<div class="stack s4" style="min-width:200px;max-width:260px"><div>' + dirPill(d) + '</div><span class="small faint">' + ui.esc(fx(x.from)) + " → " + ui.esc(fx(x.to)) + "</span></div>",
        '<div class="stack s4" style="min-width:200px;max-width:280px"><span class="small">' + ui.esc(x.purpose) + '</span><span class="tiny faint">' + ui.esc(x.data.join(", ") + " · " + x.range) + "</span></div>",
        '<div class="stack s4">' + appr(x.pt, "Patient", x.st) + appr(x.dr, "Doctor", x.st) + "</div>",
        ui.cellp(ui.esc(x.until), x.reads + " read" + (x.reads === 1 ? "" : "s")), cstPill(x), acts] };
    });
    h += '<div class="stack s8"><div class="row sb"><h2 class="h">' + L("Consent requests", "طلبات الموافقة", "सहमति अनुरोध") + "</h2>" +
      ui.seg("pvDir", [["all", "All", all.length], ["in", "We requested", cnt("in")], ["out", "We share", cnt("out")], ["group", "Within group", cnt("group")]]) + "</div>" +
      ui.table([{ h: "Consent" }, { h: "Direction" }, { h: "Purpose & scope" }, { h: "Approvals" }, { h: "Valid until" }, { h: "Status" }, { h: "" }], rows, { min: 1040 }) + "</div>";

    var chain = NB.v("pvChain", "none");
    h += '<div class="grid g2">' + ui.card(L("Sharing within your group", "المشاركة داخل مجموعتك", "आपके समूह के भीतर साझा"),
      '<p class="small muted" style="margin:0 0 12px">' + facs().map(function (f) { return ui.esc(short(f)); }).join(", ") + " are separate facilities. Being in the same group does not skip consent.</p>" +
      '<div class="stack s8">' + ui.radio("pvChain", "none", "No group directory", "Each facility registers patients on its own. Records move only with consent.", "none") +
      ui.radio("pvChain", "demo", "Shared patient directory (demographics only)", "Facilities can look up a patient's name, phone and MRN at sibling facilities to avoid re-registration. Disclosed in the notice; patients can opt out; every lookup is logged. Clinical data still needs consent.", "none") + "</div>" +
      (chain === "demo" ? ui.note("warn", "Turning this on adds a line to your privacy notice. Patients will be asked to acknowledge it at their next visit.") : ""), { icon: "building" }) +
      ui.card(L("Defaults for new requests", "الإعدادات الافتراضية للطلبات", "नए अनुरोधों के डिफ़ॉल्ट"), '<div class="stack s8"><span class="small faint">Default validity</span>' + ui.seg("pvValid", [["7", "7 days"], ["14", "14 days"], ["30", "30 days"], ["90", "90 days"]], "14") +
        '<span class="small faint" style="margin-top:6px">Maximum reads per consent</span>' + ui.seg("pvReads", [["3", "3"], ["5", "5"], ["10", "10"], ["none", "Unlimited within window"]], "5") +
        '<span class="small faint" style="margin-top:6px">Doctor approval SLA, then escalate to ' + ui.esc(privacyOfficer().n) + " (privacy officer)</span>" + ui.seg("pvSla", [["60", "1 h"], ["240", "4 working h"], ["480", "1 day"]], "240") + "</div>" +
        ui.toggle("pvAuto", "Auto-release referrals inside the group", "When the referring doctor signs the referral and the patient approves, no second doctor approval is needed.", true) +
        ui.toggle("pvStore", "Allow receiving facilities to keep a copy", "Off: they get a read-only view that disappears at expiry.", false), { icon: "sliders" }) + "</div>";
    return h;
  }

  function privNotice(c) {
    var m = c.m, po = privacyOfficer(), langs = NB.gcc() ? "English, Arabic" : "English, Hindi, Kannada";
    var ret = pick({
      india: [["Clinical records (OPD)", "10 years after last visit", "IMC Regulations 2002 require ≥ 3 years; NABH expects longer"], ["Inpatient & medico-legal records", "Permanent", "State rules and MLC practice"], ["Access & audit logs", "3 years (1 year hot)", "DPDP Rule 6: at least 1 year"], ["Consent records", "Life of record + 1 year", "DPDP Act s.6"], ["WhatsApp & marketing data", "Until consent withdrawn", "Erased with 48 h notice (DPDP Rule 8)"]],
      uae: [["Clinical records", "25 years after last procedure", "Federal Law 2/2019, Art. 20"], ["Access & audit logs", "25 years (2 years hot)", "ADHICS · aligned to records"], ["Consent records", "Life of record", "Federal Law 2/2019"], ["WhatsApp & marketing data", "Until consent withdrawn", "PDPL"]],
      ksa: [["Clinical records", "25 years (confirm MOH period)", "MOH policy · default set by Nabd"], ["Access & audit logs", "25 years (2 years hot)", "NCA ECC logging"], ["Consent records", "Life of record", "PDPL"], ["WhatsApp & marketing data", "Until consent withdrawn", "PDPL"]],
      qatar: [["Clinical records", "25 years (confirm MOPH period)", "Default set by Nabd"], ["Access & audit logs", "25 years (2 years hot)", "NCSA guidance"], ["Consent records", "Life of record", "Law No. 13 of 2016"], ["WhatsApp & marketing data", "Until consent withdrawn", "Law No. 13 of 2016"]]
    });
    var notice = ui.card(L("Privacy notice", "إشعار الخصوصية", "गोपनीयता सूचना"),
      '<div class="row sb" style="margin-bottom:12px">' + ui.cellp("Version 3.2 · published 02 Sep 2026", ui.esc(langs) + " · acknowledged by 91% of active patients") + ui.btn("Publish new version", { cls: "sm", ov: "noticever", icon: "upload" }) + "</div>" +
      '<div class="ad-notice">' + ui.esc(c.fac.name) + " (“we”) keeps your health records to treat you. We share them with another hospital or clinic only when you and your doctor approve, for a limited time, and you can see every time they were opened. " +
      "We send booking and queue messages on WhatsApp; you can stop them any time. Your data is stored in the " + ui.esc(m.residency) + " and never leaves it. You can ask for a copy, a correction or deletion of data we are not required to keep. " +
      "Contact our privacy officer " + ui.esc(po.n) + " at " + ui.esc(po.mail) + ". " + ui.esc(m.privacyLaw) + " applies.</div>" +
      '<div class="lst" style="margin-top:12px">' + [["3.2", "02 Sep 2026", "Added WhatsApp access history and group directory wording", "Current"], ["3.1", "14 Apr 2026", "Added AI safety checks (in-region, clinician decides)", "Superseded"], ["3.0", "05 Jan 2026", "Rewritten for " + m.privacyLaw, "Superseded"]].map(function (v) {
        return ui.li('<span class="av">' + ui.icon("file", "sm") + "</span>", "v" + v[0] + " · " + v[1], ui.esc(v[2]), v[3] === "Current" ? ui.pill("ok", "Current") : ui.pill("mute", v[3]));
      }).join("") + "</div>", { icon: "file" });
    var dpo = ui.card(L("Privacy officer (DPO)", "مسؤول حماية البيانات", "डेटा सुरक्षा अधिकारी"), kv([["Name", ui.esc(po.n)], ["Email", '<span class="ltr">' + ui.esc(po.mail) + "</span>"], ["Phone", '<span class="num ltr">' + ui.esc(po.ph) + "</span>"],
      ["Shown on", "Notice, WhatsApp profile, receipts"], ["Grievance reply", NB.gcc() ? "Within 30 days" : "Within 30 days (DPDP Rule 9 contact published)"]]) +
      '<div class="btns" style="margin-top:14px">' + ui.btn("Change", { cls: "sm", act: "toast", arg: "Privacy officer change needs the new officer to accept and enrol MFA" }) + "</div>", { icon: "shielduser" });
    var res = ui.card(L("Data residency", "إقامة البيانات", "डेटा रेज़िडेंसी"), kv([["Region", "<b>" + ui.esc(m.residency) + "</b>"], ["Backups", "Second zone in the same country, encrypted"], ["Encryption keys", "Per-tenant key in the region's KMS"],
      ["AI safety checks", "Run in-region"], ["Cross-border transfers", "None · blocked by default"]]) + '<div class="chips" style="margin-top:14px">' + ui.pill("perm", "Pinned at onboarding · cannot change") + ui.pill("ok", "Last backup 02:00 today") + "</div>", { icon: "globe" });
    var retention = '<div class="stack s8"><div class="row sb"><h2 class="h">' + L("Retention", "الاحتفاظ", "रिटेंशन") + "</h2>" + ui.toggle("pvHold", "Legal hold (pause all deletion)", "", false).replace('class="tog"', 'class="tog" style="border:0;padding:0;min-width:260px"') + "</div>" +
      ui.table([{ h: "Data" }, { h: "Kept for" }, { h: "Basis" }, { h: "At the end" }], ret.map(function (r) { return [ui.esc(r[0]), "<b>" + ui.esc(r[1]) + "</b>", '<span class="small muted">' + ui.esc(r[2]) + "</span>", /Permanent/.test(r[1]) ? "—" : /consent/.test(r[1]) ? "Erased" : "Archived, then erased with notice"]; }), { min: 700 }) + "</div>";
    return '<div class="split wide">' + notice + '<div class="stack s16">' + dpo + res + "</div></div>" + retention;
  }

  function privRequests(c) {
    var ST2 = { prog: ["info", "In progress"], wait: ["warn", "Waiting for doctor"], done: ["ok", "Delivered"], part: ["ok", "Done · clinical kept"] };
    var h = '<div class="ad-tiles">' + tile("Open requests", "2", "", { delta: "None overdue", dk: "ok" }) + tile("Median time to close", "4.5", "days", { delta: "Statutory limit 30 days", dk: "mute" }) +
      tile("Access-history views", "126", "", { delta: "Patients checking who saw their record", dk: "mute" }) + "</div>";
    h += ui.note("info", "Patients can ask on WhatsApp, in the app or at the desk. Identity is verified first (" + ui.esc(c.m.natId) + " or OTP). Erasure never removes records we must keep by law; the patient is told what was kept and why.");
    h += ui.table([{ h: "Request" }, { h: "Patient" }, { h: "Type" }, { h: "Channel" }, { h: "Received" }, { h: "Due" }, { h: "With" }, { h: "Status" }],
      A.dsr.map(function (d) { var p = NB.pat(d.p), s = ST2[d.st]; return { ov: "dsr", arg: d.id, cells: ['<span class="tok">' + d.id + "</span>", ui.cellp(ui.esc(NB.pname(p)), ui.esc(p.mrn)), ui.esc(d.type), ui.esc(d.ch), ui.esc(d.recv), ui.esc(d.due), ui.esc(d.who), ui.pill(s[0], s[1])] }; }), { min: 900 });
    return h;
  }

  NB.overlay("consentdetail", function (id) {
    var x = consents().find(function (q) { return q.id === id; }); if (!x) return { title: "Consent", body: ui.note("warn", "Not found") };
    var p = NB.pat(x.p), m = NB.mkt(), d = dirOf(x), live = x.st === "active" || /pending/.test(x.st);
    var purpose = x.st === "btg" ? "BTG · emergency" : /Referral/.test(x.purpose) ? "CAREMGT · referral" : "CAREMGT · continuity of care";
    var ev = [["acc", "send", "Requested by " + x.by, fx(x.to) + " · purpose: " + x.purpose],
      [/Declined/.test(x.pt) ? "crit" : x.pt === "—" || /sent/.test(x.pt) ? "" : "ok", "user", "Patient" + (p.age < 18 ? " (guardian)" : ""), x.pt],
      [/Waiting/.test(x.dr) || x.dr === "—" ? "" : /Skipped/.test(x.dr) ? "crit" : "ok", "steth", "Providing doctor", x.dr === "—" ? "Not reached yet" : x.dr]];
    if (x.st === "active" || x.st === "expired" || x.st === "btg") ev.push(["ok", "shieldcheck", "Active · signed", "Valid until " + x.until + " · max 5 reads"]);
    if (x.st === "expired") ev.push(["", "clockh", "Expired automatically", x.until + " · viewer access closed"]);
    if (x.st === "revoked") ev.push(["crit", "close", "Revoked", x.until]);
    var reads = x.reads ? '<div class="lst">' + Array.apply(null, { length: Math.min(x.reads, 4) }).map(function (_, i) {
      return ui.li('<span class="av">' + ui.icon("eye", "sm") + "</span>", ["Dr. Vikram Shah", "Dr. Vikram Shah", "Anita D'Souza (nurse)", "Dr. Rajesh Gupta"][i] + " · " + fx(x.to), x.data[i % x.data.length] + " · " + ["16 Oct 11:02", "15 Oct 17:40", "15 Oct 09:15", "Today 03:14"][i], ui.pill("mute", "Read " + (i + 1)));
    }).join("") + "</div>" : '<div class="empty">No reads yet.</div>';
    return { kind: "drawer", size: "lg", title: L("Consent ", "موافقة ", "सहमति ") + x.id, body:
      '<div class="row sb">' + '<div class="row">' + ui.av(p.name, "lg") + ui.cellp(ui.esc(NB.pname(p)), ui.esc(p.mrn + " · " + p.age + " " + p.sex)) + "</div>" + '<div class="row">' + dirPill(d) + cstPill(x) + "</div></div>" +
      (x.st === "btg" ? ui.note("crit", "Break-glass: both approvals were skipped for an emergency. The patient was notified and a post-hoc review is due within 72 h.") : "") +
      ui.card("", kv([["From (holds the record)", ui.esc(fx(x.from))], ["To (requesting)", ui.esc(fx(x.to))], ["Requested by", ui.esc(x.by)], ["Purpose code", ui.esc(purpose)], ["Data categories", x.data.map(function (q) { return '<span class="pill mute" style="margin:2px">' + ui.esc(q) + "</span>"; }).join("")],
        ["Record date range", ui.esc(x.range)], ["Valid until", ui.esc(x.until)], ["Reads", num(x.reads + " of 5")], ["Copy allowed", "No · view only"], ["Sensitive categories", "Excluded unless named"]])) +
      '<div class="stack s8"><b>Approval trail</b><div class="tl">' + ev.map(function (e) { return '<div class="ev"><span class="dotc ' + e[0] + '">' + ui.icon(e[1], "sm") + '</span><div class="bd"><b>' + ui.esc(e[2]) + "</b><small>" + ui.esc(e[3]) + "</small></div></div>"; }).join("") + "</div></div>" +
      '<div class="stack s8"><b>Access log</b>' + reads + "</div>" +
      '<div class="stack s4"><b class="small">Integrity</b><span class="ad-hash">JWS ES256 · key ' + ui.esc(m.flag) + '-kms-2026-10 · sha256 3f9a6c0e…b71dc21e · chain #' + (x.reads + 4) + "</span>" +
      (m.id === "india" ? '<span class="small faint">Mirrors ABDM consent artefact · HIE-CM ref 7b1e…44c0</span>' : "") + "</div>",
      foot: ui.btn("Download record", { cls: "ghost", icon: "download", act: "toast", arg: "Consent record " + x.id + " exported (PDF + JSON, signed)" }) + (live ? ui.btn("Revoke", { cls: "dng", icon: "close", ov: "revoke", arg: x.id }) : "") + ui.btn("Close", { act: "close" }) };
  });
  NB.overlay("revoke", function (id) {
    var x = consents().find(function (q) { return q.id === id; }) || {}, p = NB.pat(x.p);
    return { kind: "modal", title: L("Revoke consent ", "إلغاء الموافقة ", "सहमति रद्द करें ") + id, body:
      '<p class="small muted" style="margin:0">' + ui.esc(fx(x.to)) + " loses access to " + ui.esc(NB.pname(p)) + "'s records on its next request. Any cached copy is destroyed and the patient is told on WhatsApp.</p>" +
      '<div class="stack s8">' + [["patient", "Patient asked us (phone or desk)"], ["mistake", "Shared by mistake"], ["ended", "Care episode has ended"], ["other", "Other reason"]].map(function (r) { return ui.radio("rvWhy", r[0], r[1], "", "patient"); }).join("") + "</div>" +
      ui.field("Note for the audit log", "", { area: true, ph: "Optional" }) + ui.note("warn", "This cannot be undone. A new request would need fresh approval from the patient and the doctor."),
      foot: ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn("Revoke now", { cls: "dng", icon: "close", act: "dorevoke", arg: id }) };
  });
  NB.act("dorevoke", function (id) {
    var x = consents().find(function (q) { return q.id === id; });
    if (x) { x.st = "revoked"; x.until = "Revoked 17 Oct 10:04 by Dr. Arvind Rao"; }
    NB.state.overlay = null; NB.toast("Consent " + id + " revoked · access closed · patient notified on WhatsApp");
  });
  NB.overlay("bgreview", function (id) {
    var b = A.bg.find(function (q) { return q.id === id; }) || A.bg[0], p = NB.pat(b.p);
    return { kind: "drawer", title: "Break-glass " + b.id, body:
      (b.st === "due" ? ui.note("warn", ui.esc(b.due) + ". The patient has been notified and sees the outcome of this review.") : ui.note(b.st === "ok" ? "ok" : "crit", ui.esc(b.due))) +
      kv([["Who", ui.esc(b.who + " · " + b.role)], ["Facility", ui.esc(facs()[b.f].name)], ["Patient", ui.esc(NB.pname(p) + " · " + p.mrn)], ["When", ui.esc(b.when)], ["Window", ui.esc(b.dur)], ["Records opened", num(b.reads)], ["Scope", ui.esc(b.scope)]]) +
      ui.card("Reason given", '<p style="margin:0">' + ui.esc(b.reason) + "</p>") +
      (b.st === "due" ? '<div class="stack s8"><b>Your review</b>' + ui.radio("bgOut", "ok", "Justified", "Emergency care needed the information.", "ok") + ui.radio("bgOut", "bad", "Not justified", "Opens an incident and flags the staff member.", "ok") + ui.radio("bgOut", "esc", "Escalate", "Send to the medical director.", "ok") + ui.field("Review note", "", { area: true }) + "</div>" : ""),
      foot: b.st === "due" ? ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn("Submit review", { cls: "pri", icon: "check", act: "bgdone", arg: b.id }) : ui.btn("Close", { act: "close" }) };
  });
  NB.act("bgdone", function (id) {
    var b = A.bg.find(function (q) { return q.id === id; }), o = NB.v("bgOut", "ok");
    if (b) { b.st = o === "bad" ? "bad" : "ok"; b.due = (o === "bad" ? "Unjustified · incident opened" : o === "esc" ? "Escalated to medical director" : "Justified") + " · reviewed by Dr. Arvind Rao, 17 Oct"; }
    NB.state.overlay = null; NB.toast("Review saved · outcome shown in the patient's access history");
  });
  NB.overlay("dsr", function (id) {
    var d = A.dsr.find(function (q) { return q.id === id; }) || A.dsr[0], p = NB.pat(d.p);
    var steps = [["Identity verified", "OTP + " + NB.mkt().natId], ["Request scoped", d.type], ["Records compiled", "All facilities in the group"], ["Reviewed", "Privacy officer · third-party information removed"], ["Delivered", "Secure link on WhatsApp, expires in 7 days"]];
    return { kind: "drawer", title: "Data request " + d.id, body:
      '<div class="row">' + ui.av(p.name, "lg") + ui.cellp(ui.esc(NB.pname(p)), ui.esc(p.mrn + " · via " + d.ch)) + "</div>" +
      kv([["Type", ui.esc(d.type)], ["Received", ui.esc(d.recv)], ["Statutory due date", ui.esc(d.due)], ["Assigned to", ui.esc(d.who)]]) +
      '<div class="tl">' + steps.map(function (s, i) { var done = i < d.step, now = i === d.step; return '<div class="ev"><span class="dotc ' + (done ? "ok" : now ? "acc" : "") + '">' + ui.icon(done ? "check" : now ? "hourglass" : "circle", "sm") + '</span><div class="bd"><b>' + s[0] + "</b><small>" + ui.esc(s[1]) + "</small></div></div>"; }).join("") + "</div>" +
      (d.st === "part" ? ui.note("info", "App and marketing data erased. Clinical records are kept for the legal retention period; the patient was told which and why.") : ""),
      foot: ui.btn("Close", { cls: "ghost", act: "close" }) + (d.step < 5 ? ui.btn("Move to next step", { cls: "pri", act: "dsrnext", arg: d.id, icon: "arrowright" }) : "") };
  });
  NB.act("dsrnext", function (id) { var d = A.dsr.find(function (q) { return q.id === id; }); if (d) { d.step++; if (d.step >= 5) d.st = "done"; } NB.render(); });
  NB.overlay("noticever", function () {
    return { kind: "modal", title: "Publish privacy notice v3.3", body:
      ui.field("What changed (shown to patients)", "Added the shared patient directory across our facilities and how to opt out.", { area: true }) +
      '<div class="stack s8">' + ui.toggle("nvAck", "Ask patients to acknowledge at their next visit", "WhatsApp message with the summary; desk tablet as fallback.", true) +
      ui.toggle("nvLang", "All languages ready (" + (NB.gcc() ? "EN, AR" : "EN, HI, KN") + ")", "Reviewed by a person, not machine translation.", true) + "</div>",
      foot: ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn("Publish v3.3", { cls: "pri", icon: "upload", act: "toast", arg: "Privacy notice v3.3 published · 0 of 18,240 patients acknowledged so far" }) };
  });

  /* ================= owner.staff ================= */
  var MFA = { passkey: ["ok", "Passkey"], totp: ["ok", "Authenticator"], pin: ["mute", "PIN + trusted device"], none: ["crit", "Not enrolled"] };
  var MODS = [["Patients & registration", "patients"], ["Queue & bookings", "queue"], ["Clinical notes", "notes"], ["Prescriptions", "rx"], ["Lab results", "labr"], ["Pharmacy dispensing", "disp"],
    ["Billing & invoices", "bill"], ["Refunds & discounts", "refund"], ["Reports", "reports"], ["Settings & workflows", "settings"], ["Staff & roles", "staff"], ["Audit export", "audit"], ["Break-glass", "bg"]];
  // Permission levels per module for each role: F full, E edit, V view, N none, X locked by platform floor
  var PERM = {
    reception: "EEXNNNVNNNNNN", doctor: "VEFFFNVNVNNNU", nurse: "VEEVVNNNNNNNU", pharmacist: "VNXVNFVNVNNNN", lab: "VNXNFNNNVNNNN", billing: "VVXNNNFEVNNNN", admin: "FFNNNNFFFFFFN"
  };
  var PL = { F: ["full", "Full", "checkcircle"], E: ["edit", "Edit", "edit"], V: ["view", "View", "eye"], N: ["none", "None", "dot"], U: ["edit", "Can use", "unlock"], X: ["lock", "Floor", "lock"] };
  function permCell(c) { var p = PL[c]; return '<span class="ad-pm ' + p[0] + '">' + ui.icon(p[2], "xs") + p[1] + "</span>"; }
  function mfaPill(s) { var x = s.mfa === "none" && (s.role === "admin" || s.role === "billing" || s.role === "doctor") ? ["crit", "Required · not set"] : MFA[s.mfa]; return ui.pill(x[0], x[1], x[0] === "ok" ? "fingerprint" : null); }

  NB.screen("owner.staff", function (c) {
    var tab = NB.v("stTab", "people"), fs = facs();
    var h = ui.header(L("Staff, roles & departments", "الموظفون والأدوار والأقسام", "स्टाफ़, भूमिकाएँ और विभाग"), A.staff.length + " people shown of 142 · " + fs.length + " facilities · one login per person, access per facility",
      ui.btn(L("Invite staff", "دعوة موظف", "स्टाफ़ आमंत्रित करें"), { cls: "pri", icon: "userplus", ov: "invite" }) + ui.btn("New delegation", { icon: "key", ov: "delegate" }));
    h += ui.tabs("stTab", [["people", "People", "users"], ["roles", "Roles & permissions", "shielduser"], ["depts", "Departments", "building"], ["multi", "Multi-facility doctors", "share"], ["access", "Invites & delegations", "key"]]);
    if (tab === "people") {
      var rf = NB.v("stRole", "all"), rows = A.staff.filter(function (s) { return rf === "all" || s.role === rf; });
      var noMfa = A.staff.filter(function (s) { return s.mfa === "none"; }).length;
      h += '<div class="ad-tiles">' + tile("Active staff", "142", "", { delta: "23 doctors · 31 nurses", dk: "mute" }) + tile("MFA coverage", "94", "%", { delta: noMfa + " required accounts missing", dk: "crit" }) +
        tile("Invites pending", "1", "", { delta: "Links expire in 72 h", dk: "warn" }) + tile("Delegations active", "2", "", { delta: "Auto-expire ≤ 30 days", dk: "info" }) + "</div>";
      h += ui.note("warn", "<b>MFA is mandatory</b> for the owner, admins, doctors and anyone who can approve refunds or discounts. Dr. Khalid Al-Saeed and Pooja Nair must enrol before 20 Oct or their access pauses. " +
        '<a href="#" data-act="toast" data-arg="Reminder sent on WhatsApp and email with an enrolment link">Send reminder</a>');
      h += '<div class="row sb">' + ui.seg("stRole", [["all", "All"]].concat(ROLES)) + "</div>";
      h += ui.table([{ h: "Name" }, { h: "Role" }, { h: "Department" }, { h: "Facilities" }, { h: "Sign-in" }, { h: "Last active" }, { h: "" }],
        rows.map(function (s) {
          return [ui.cellp(ui.esc(s.n), ui.esc(s.t)), ui.pill("mute", roleName(s.role), false), ui.esc(s.dept), '<div class="chips" style="gap:4px">' + s.f.map(function (i) { return '<span class="pill mute" style="text-transform:none;letter-spacing:0">' + ui.esc(short(fs[i])) + "</span>"; }).join("") + "</div>",
            mfaPill(s), ui.esc(s.last), ui.btn("Permissions", { cls: "sm ghost", ov: "rolematrix", arg: s.role })];
        }), { min: 960 });
    } else if (tab === "roles") {
      h += ui.note("info", "Roles are coarse: they decide which modules a person can open. Which patient's records they can read is decided by the privacy policy. Nobody can grant more than they hold, and every change is audited with before and after values.");
      h += ui.table([{ h: "Module" }].concat(ROLES.map(function (r) { return { h: r[1] }; })),
        MODS.map(function (mo, i) { return ["<b class=\"small\">" + ui.esc(mo[0]) + "</b>"].concat(ROLES.map(function (r) { return permCell(PERM[r[0]][i]); })); })
          .concat([['<span class="small faint">Open role</span>'].concat(ROLES.map(function (r) { return ui.btn("Edit", { cls: "sm ghost", ov: "rolematrix", arg: r[0], icon: "edit" }); }))]), { min: 980 });
      h += '<div class="ad-legend">' + ["F", "E", "V", "N", "X"].map(function (k) { return permCell(k); }).join("") + '<span class="faint">“Floor” = platform rule a facility cannot change (reception, billing, pharmacy and lab never read clinical notes).</span></div>';
    } else if (tab === "depts") {
      var D = [["Medicine", "Dr. Vikram Shah", 6, 9, ["Heart Centre", "Emergency"], false], ["Child Health", "Dr. Nisha Bhat", 3, 5, ["Heart Centre", "ENT"], false], ["Heart Centre", "Dr. Khalid Al-Saeed", 4, 7, ["Medicine"], false],
        ["Skin & Hair", "Dr. Meera Iyer", 2, 3, ["Medicine"], false], ["Women's Health", "Dr. Fatima Rahman", 3, 6, ["Medicine", "Child Health"], true], ["Emergency", "Dr. Rajesh Gupta", 5, 14, ["Every department"], false], ["Psychiatry", "Dr. Leena Thomas", 2, 2, ["Medicine"], true]];
      h += '<div class="grid g3">' + D.map(function (d) {
        return ui.card(ui.esc(d[0]), kv([["Head", ui.esc(d[1])], ["Doctors", num(d[2])], ["Other staff", num(d[3])]]) + '<div class="stack s4" style="margin-top:12px"><span class="small faint">Can refer to</span><div class="chips">' +
          d[4].map(function (x) { return '<span class="pill mute" style="text-transform:none;letter-spacing:0">' + ui.esc(x) + "</span>"; }).join("") + "</div></div>" + (d[5] ? '<div style="margin-top:12px">' + ui.pill("perm", "Sensitive records auto-labelled") + "</div>" : ""),
          { icon: "building", right: ui.btn("Edit", { cls: "sm ghost", act: "toast", arg: d[0] + ": edit head, staff and referral routes" }) });
      }).join("") + "</div>";
    } else if (tab === "multi") {
      var reg = regName();
      var M = [
        ["Dr. Khalid Al-Saeed", "Cardiology", [[1, "Consultant cardiologist", "Mar 2023", "—", "active"], [0, "Visiting consultant (Tue, Thu)", "Jan 2025", "—", "active"]], "+1 outside your group (busy blocks only)"],
        ["Dr. Vikram Shah", "General Medicine", [[0, "Consultant physician", "Jun 2020", "—", "active"], [1, "Ward rounds (fixed term)", "Jun 2025", "31 Dec 2026", "active"]], ""],
        [soloDoc().n, soloDoc().spec, [[2, "Owner-doctor", "Aug 2019", "—", "active"], [0, "Consultant (Mon, Wed, Fri)", "Jul 2021", "—", "active"]], ""],
        ["Dr. Rohan Das", "Dermatology", [[1, "Consultant", "Feb 2022", "30 Sep 2026", "ended"]], "Sessions revoked; the records he wrote stay with the facility"]
      ];
      h += ui.note("info", "One person, one login. Each facility keeps its own role, permissions and department for the doctor. Other hospitals the doctor works at appear only as private “busy” blocks so schedules never clash.");
      h += ui.table([{ h: "Doctor" }, { h: "Registration (" + reg + ")" }, { h: "Affiliations" }, { h: "Dates" }, { h: "Status" }],
        M.map(function (d, i) {
          return [ui.cellp(ui.esc(d[0]), ui.esc(d[1])), ui.cellp('<span class="num ltr">' + ["48211", "39102", "51877", "40166"][i].replace(/^/, NB.gcc() ? ({ uae: "DHA-P-", ksa: "SCFHS-", qatar: "QCHP-" })[mid()] : "HPR-71-") + "</span>", "Verified " + ["12 Jan 2026", "02 Mar 2026", "30 Apr 2026", "11 Feb 2025"][i]),
            '<div class="stack s4">' + d[2].map(function (a) { return '<span class="small">' + ui.icon("building", "xs") + " " + ui.esc(short(facs()[a[0]])) + " · " + ui.esc(a[1]) + "</span>"; }).join("") + (d[3] ? '<span class="tiny faint">' + ui.esc(d[3]) + "</span>" : "") + "</div>",
            '<div class="stack s4">' + d[2].map(function (a) { return '<span class="small num ltr">' + a[2] + " → " + a[3] + "</span>"; }).join("") + "</div>",
            '<div class="stack s4">' + d[2].map(function (a) { return "<div>" + (a[4] === "active" ? ui.pill("ok", "Active") : ui.pill("perm", "Ended")) + "</div>"; }).join("") + "</div>"];
        }), { min: 900 });
    } else {
      h += '<div class="grid g2">' + ui.card("Invites", '<div class="lst">' + A.invites.map(function (v) {
        return ui.li('<span class="av">' + ui.icon(v.st === "done" ? "check" : "send", "sm") + "</span>", ui.esc(v.to), ui.esc(v.role + " · " + short(facs()[v.f]) + " · sent " + v.sent + " · " + v.exp),
          v.st === "pending" ? ui.pill("warn", "Pending") : v.st === "expired" ? ui.btn("Resend", { cls: "sm", act: "toast", arg: "New single-use invite sent (72 h)", icon: "refresh" }) : ui.pill("ok", "Joined"));
      }).join("") + "</div>" + '<p class="small faint" style="margin:12px 0 0">Invite links are single-use and expire in 72 hours. The person must set up MFA before first use if their role requires it.</p>', { icon: "userplus", right: ui.btn("Invite", { cls: "sm", ov: "invite" }) }) +
        ui.card("Delegations", '<div class="lst">' + A.deleg.map(function (d) {
          return ui.li('<span class="av">' + ui.icon("key", "sm") + "</span>", ui.esc(d.from) + " → " + ui.esc(d.to), ui.esc(d.what + " · " + d.win + " · " + d.why), d.st === "active" ? ui.pill("info", "Active") : ui.pill("mute", "Ended"));
        }).join("") + "</div>" + '<p class="small faint" style="margin:12px 0 0">Delegations hand over specific authority for leave cover, never more than the giver holds, and end on their own (maximum 30 days).</p>', { icon: "key", right: ui.btn("New", { cls: "sm", ov: "delegate" }) }) + "</div>";
    }
    return h;
  });
  NB.overlay("rolematrix", function (role) {
    role = role || "doctor"; var acts = ["View", "Create", "Edit", "Approve", "Export"];
    var lvl = function (c) { return { F: [1, 1, 1, 1, 1], E: [1, 1, 1, 0, 0], V: [1, 0, 0, 0, 0], N: [0, 0, 0, 0, 0], U: [1, 1, 0, 0, 0], X: [0, 0, 0, 0, 0] }[c]; };
    return { kind: "drawer", size: "lg", title: "Role · " + roleName(role), body:
      ui.seg("rmRole", ROLES, role).replace(/data-set="rmRole"/g, 'data-act="rmrole"').replace(/data-val=/g, "data-arg=") +
      ui.table([{ h: "Module" }].concat(acts.map(function (a) { return { h: a }; })), MODS.map(function (mo, i) {
        var c = PERM[role][i];
        return ["<b class=\"small\">" + ui.esc(mo[0]) + "</b>" + (c === "X" ? ' <span class="pill perm" style="margin-inline-start:6px">' + ui.icon("lock") + "Floor</span>" : "")].concat(lvl(c).map(function (v) {
          return c === "X" ? '<span class="faint">—</span>' : v ? '<span class="okc">' + ui.icon("check", "sm") + "</span>" : '<span class="faint">' + ui.icon("dot", "sm") + "</span>";
        }));
      }), { min: 560 }) +
      ui.toggle("rm_mfa_" + role, "Require MFA", role === "admin" || role === "billing" || role === "doctor" ? "Always on for this role (holds approvals or clinical write access)." : "Recommended.", true) +
      (role === "doctor" ? ui.toggle("rm_own", "Own patients only", "Doctors see patients they have treated or are booked with, within the privacy policy.", false) : "") +
      ui.toggle("rm_fin_" + role, "Hide financial fields", "Removed in the API response, not just hidden on screen.", role !== "billing" && role !== "admin"),
      foot: ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn("Save role", { cls: "pri", icon: "check", act: "toast", arg: roleName(role) + " role saved · audited · sessions of affected staff refreshed" }) };
  });
  NB.act("rmrole", function (r) { NB.state.ovArg = r; NB.render(); });
  NB.overlay("invite", function () {
    var fs = facs();
    return { kind: "modal", title: L("Invite staff", "دعوة موظف", "स्टाफ़ आमंत्रित करें"), body: '<div class="fgrid">' +
      ui.field("Mobile or email", "", { ph: NB.mkt().phone + " … or name@clinic.com", full: true }) + ui.field("Role", "Doctor", { options: ROLES.map(function (r) { return r[1]; }) }) +
      ui.field("Department", "Medicine", { options: A.depts }) + ui.field("Facility", fs[0].name, { options: fs.map(function (f) { return f.name; }), full: true }) + "</div>" +
      ui.toggle("invMfa", "Require MFA before first sign-in", "", true) + ui.note("info", "If this doctor already uses Nabd elsewhere, they accept with their existing login and this facility is added as an affiliation."),
      foot: ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn("Send invite", { cls: "pri", icon: "send", act: "toast", arg: "Invite sent · single-use link valid for 72 h" }) };
  });
  NB.overlay("delegate", function () {
    return { kind: "modal", title: "New delegation", body: '<div class="fgrid">' + ui.field("From", "Dr. Khalid Al-Saeed", { options: A.staff.map(function (s) { return s.n; }) }) +
      ui.field("To", "Dr. Vikram Shah", { options: A.staff.map(function (s) { return s.n; }) }) +
      ui.field("Authority", "Approve record-sharing requests", { options: ["Approve record-sharing requests", "Approve refunds and discounts", "Review booking requests", "Publish workflow changes"], full: true }) +
      ui.field("Starts", "17 Oct 2026") + ui.field("Ends (max 30 days)", "24 Oct 2026") + "</div>" + ui.note("info", "The receiver cannot get more than the giver holds. Both are notified; it ends on its own."),
      foot: ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn("Create · TOTP", { cls: "pri", icon: "key", act: "toast", arg: "Delegation active until 24 Oct · audited" }) };
  });

  /* ================= owner.integrations ================= */
  function integrations() {
    var m = mk(), id = m.id, langs = NB.gcc() ? "EN · AR" : "EN · HI · KN";
    var I = [
      { id: "wa", ic: "chat", wa: true, n: "WhatsApp Business", d: "Clinic number " + m.phone + (id === "india" ? " 80 4718 2200" : " 4 555 0192") + " · bookings, live queue, e-Rx, payment links, consent approvals", st: "ok", s: "Connected · quality High", sync: "Live · 2 s ago", meta: ["18 of 20 templates approved", "Tier 10K/day", langs] }
    ];
    if (id === "india") I.push(
      { id: "abha", ic: "fingerprint", n: "ABDM · ABHA (M1)", d: "Create and verify ABHA at registration; facility in HFR, doctors in HPR", st: "ok", s: "Live", sync: "4 min ago", meta: ["HFR IN2910000123", "9 of 11 doctors in HPR", "62% patients linked"] },
      { id: "hiecm", ic: "share", n: "ABDM HIE-CM · HIP / HIU (M2, M3)", d: "Share care contexts on consent through the national consent manager", st: "warn", s: "HIU in sandbox", sync: "M2 live · 11 min ago", meta: ["Care contexts linked 14,820", "M3 certification booked 28 Oct"] },
      { id: "claims", ic: "send", n: "NHCX & TPA portals", d: "Pre-auth and claims for Star Health, HDFC Ergo, PM-JAY", st: "ok", s: "Connected", sync: "26 min ago", meta: ["38 claims open", "First-pass 91%"] },
      { id: "einv", ic: "receipt", n: "GST e-invoice (IRP)", d: "IRN and QR on B2B invoices above threshold", st: "ok", s: "Connected", sync: "1 h ago", meta: ["B2B only", "GSTIN 29ABCDE1234F1Z5"] });
    if (id === "uae") I.push(
      { id: "nabidh", ic: "share", n: "NABIDH (Dubai)", d: "HL7 feeds of encounters, results and Rx; patient opt-out honoured", st: "ok", s: "Connected", sync: "3 min ago", meta: ["Facility licence DHA-F-0012345", "Opt-outs mirrored: 41"] },
      { id: "malaffi", ic: "share", n: "Malaffi (Abu Dhabi)", d: "Required for Abu Dhabi facilities", st: "mute", s: "Not needed", sync: "—", meta: ["No Abu Dhabi facility yet"] },
      { id: "claims", ic: "send", n: "DHA eClaimLink", d: "Eligibility, prior authorisation and claims (DHPO)", st: "ok", s: "Connected", sync: "18 min ago", meta: ["26 claims open", "First-pass 89%"] },
      { id: "eid", ic: "fingerprint", n: "UAE PASS / Emirates ID reader", d: "Verified identity for patients and sharing", st: "ok", s: "Connected", sync: "Today", meta: ["2 card readers"] });
    if (id === "ksa") I.push(
      { id: "nphies", ic: "send", n: "NPHIES", d: "Eligibility, pre-authorisation and claims with every insurer", st: "ok", s: "Connected", sync: "6 min ago", meta: ["Bupa, Tawuniya, MedGulf", "First-pass 92%"] },
      { id: "wasfaty", ic: "pill", n: "Wasfaty", d: "MOH e-prescriptions dispensed at any pharmacy", st: "warn", s: "Certificate renews in 9 days", sync: "1 h ago", meta: ["412 e-Rx this month"] },
      { id: "zatca", ic: "receipt", n: "ZATCA Fatoora phase 2", d: "Invoice clearance and QR; CSID onboarded per device", st: "ok", s: "Clearing", sync: "Live", meta: ["3 CSIDs active", "Wave deadline 1 Feb 2027"] },
      { id: "nafath", ic: "fingerprint", n: "Nafath", d: "Verified identity for patients and consent approvals", st: "ok", s: "Connected", sync: "Today", meta: ["SCFHS licence checks daily"] });
    if (id === "qatar") I.push(
      { id: "moph", ic: "share", n: "MOPH health information exchange", d: "National record sharing and e-Rx", st: "info", s: "Testing", sync: "Sandbox", meta: ["Go-live planned Dec 2026"] },
      { id: "qchp", ic: "shieldcheck", n: "QCHP licence verification", d: "Daily check of every practitioner's licence", st: "ok", s: "Connected", sync: "Today", meta: ["24 practitioners verified"] },
      { id: "claims", ic: "send", n: "Insurer portals (QLM, Allianz)", d: "Pre-auth and claims", st: "warn", s: "Manual upload", sync: "Yesterday", meta: ["API under negotiation"] });
    I.push(
      { id: "pay", ic: "card", n: pick({ india: "Razorpay · UPI, cards, payment links", uae: "Network International (N-Genius)", ksa: "HyperPay · mada, Apple Pay", qatar: "QPay via CyberSource" }), d: "Payments at the desk, by link on WhatsApp and in the app", st: "ok", s: "Connected", sync: "Live", meta: ["Settles T+1", "Refunds need approval"] },
      { id: "sms", ic: "phone", n: pick({ india: "SMS fallback (DLT registered)", uae: "SMS fallback · Unifonic", ksa: "SMS fallback · Unifonic", qatar: "SMS fallback · Ooredoo" }), d: "For patients without WhatsApp; OTP backup", st: "ok", s: "Connected", sync: "12 min ago", meta: id === "india" ? ["DLT entity 1101…442", "14 templates"] : ["Sender ID approved"] },
      { id: "lis", ic: "flask", n: "Lab analysers", d: "Results flow from instruments into result entry (HL7 / ASTM via the lab bridge)", st: "crit", s: "1 analyser not sending", sync: "Chemistry: 42 min ago", meta: ["Sysmex XN-550 · OK", "Roche cobas c 311 · no results 42 min"] },
      { id: "acct", ic: "banknote", n: id === "india" ? "Tally Prime" : "Zoho Books", d: "Daily sales, receipts and " + m.tax + " ledgers pushed after day close", st: "ok", s: "Connected", sync: "Yesterday 22:10", meta: ["Day close auto-posted"] }
    );
    return I;
  }
  NB.screen("owner.integrations", function (c) {
    var I = integrations(), bad = I.filter(function (x) { return x.st === "crit" || x.st === "warn"; }).length;
    var h = ui.header(L("Integrations", "التكاملات", "इंटीग्रेशन"), ui.esc(c.m.name) + " · " + I.length + " connections · " + ui.esc(c.m.hie) + " · " + ui.esc(c.m.claims),
      ui.btn("Browse more", { icon: "plus", act: "toast", arg: "Integration catalogue: PACS, HRMS, CCTV-free kiosks and more" }));
    if (bad) h += ui.note("warn", bad + " connection" + (bad > 1 ? "s need" : " needs") + " attention. Lab analyser messages are buffered on the bridge and will replay when it reconnects.");
    h += '<div class="grid g3">' + I.map(function (x) {
      return '<div class="card ad-intg"><div class="ad-ih"><span class="ad-ii' + (x.wa ? " ad-wa" : "") + '">' + ui.icon(x.ic) + '</span><div class="fl"><h3 class="h">' + ui.esc(x.n) + '</h3><p class="small muted" style="margin:4px 0 0">' + ui.esc(x.d) + "</p></div></div>" +
        '<div class="row sb">' + ui.pill(x.st, x.s) + '<span class="tiny faint">' + ui.icon("refresh", "xs") + " " + ui.esc(x.sync) + "</span></div>" +
        '<div class="ad-meta">' + x.meta.map(function (q) { return "<span>" + ui.esc(q) + "</span>"; }).join("") + "</div>" +
        '<div class="btns">' + ui.btn(x.st === "mute" ? "Set up" : "Configure", { cls: "sm", ov: "intg", arg: x.id }) + (x.st === "crit" ? ui.btn("Run test", { cls: "sm ghost", act: "toast", arg: "Test message sent to the analyser bridge · waiting for ACK", icon: "play" }) : "") + "</div></div>";
    }).join("") + "</div>";
    return h;
  });
  NB.overlay("intg", function (id) {
    var x = integrations().find(function (q) { return q.id === id; }) || integrations()[0], gcc = NB.gcc();
    var body = '<div class="row">' + '<span class="ad-ii' + (x.wa ? " ad-wa" : "") + '">' + ui.icon(x.ic) + "</span>" + ui.cellp(ui.esc(x.n), ui.esc(x.d)) + "</div>" + '<div class="row">' + ui.pill(x.st, x.s) + '<span class="small faint">Last sync ' + ui.esc(x.sync) + "</span></div>";
    if (id === "wa") {
      var T = [["booking_confirmed", "Utility", "ok", "Approved"], ["queue_eta_update", "Utility", "ok", "Approved"], ["leave_home_now", "Utility", "ok", "Approved"], ["doctor_delay_notice", "Utility", "ok", "Approved"],
        ["consent_approval_request", "Utility + OTP button", "ok", "Approved"], ["login_otp", "Authentication", "ok", "Approved"], ["erx_ready_secure_link", "Utility", "ok", "Approved"], ["payment_link", "Utility", "ok", "Approved"],
        ["follow_up_reminder_v2", "Utility", "warn", "In review"], ["health_camp_invite", "Marketing", "perm", "Off for clinical number"]];
      body += kv([["Display name", ui.esc(facs()[0].name)], ["Number", '<span class="num ltr">' + ui.esc(x.d.split(" · ")[0].replace("Clinic number ", "")) + "</span>"], ["Opt-in recorded", "Per number, at booking"], ["Notification bodies", "No patient health data · lock-screen safe"]]) +
        '<div class="stack s8"><b>Message templates</b>' + ui.table([{ h: "Template" }, { h: "Category" }, { h: "Languages" }, { h: "Status" }], T.map(function (t) { return ['<span class="small ltr">' + t[0] + "</span>", ui.esc(t[1]), gcc ? "EN, AR" : "EN, HI, KN", ui.pill(t[2], t[3])]; }), { min: 520 }) + "</div>" +
        ui.note("info", "Clinical messages use utility templates only. Replies inside the 24-hour service window are free; marketing templates are off on this number.");
    } else {
      body += kv([["Environment", x.st === "info" ? "Sandbox" : "Production"], ["Credentials", "Stored in the region's key vault · rotated 01 Oct"], ["Data sent", id === "lis" ? "Orders out, results in (no demographics beyond MRN)" : "Only what the integration needs"], ["Errors (24 h)", x.st === "crit" ? '<span class="critc">17</span>' : "0"]]) +
        '<div class="stack s8"><b>Recent activity</b><div class="lst">' + [["10:01", x.st === "crit" ? "No ACK from cobas c 311 (timeout 30 s)" : "Sync completed"], ["09:46", "Sync completed"], ["09:31", "Sync completed"]].map(function (r) {
          return ui.li('<span class="av">' + ui.icon(/No ACK/.test(r[1]) ? "alertcircle" : "check", "sm") + "</span>", ui.esc(r[1]), '<span class="num ltr">' + r[0] + "</span>");
        }).join("") + "</div></div>" + ui.toggle("ig_" + id, "Enabled", "Turning this off stops outgoing data immediately.", x.st !== "mute");
    }
    return { kind: "drawer", size: "lg", title: x.n, body: body, foot: ui.btn("Close", { cls: "ghost", act: "close" }) + ui.btn("Test connection", { icon: "play", act: "toast", arg: x.n + ": test passed in 640 ms" }) };
  });

  /* ================= owner.compliance ================= */
  NB.screen("owner.compliance", function (c) {
    var tab = NB.v("cpTab", "audit"), m = c.m;
    var h = ui.header(L("Audit & compliance", "التدقيق والامتثال", "ऑडिट और अनुपालन"), "Every record view, export, share, break-glass and settings change is logged and hash-chained · " + ui.esc(m.privacyLaw),
      ui.pill("ok", "Chain verified 02:00", "shieldcheck") + ui.btn("Export (TOTP)", { icon: "download", act: "toast", arg: "Audit export queued · you will get a secure link · the export itself is audited" }));
    h += ui.tabs("cpTab", [["audit", "Audit log", "search"], ["check", "Compliance checklist", "checkcircle"], ["breach", "Breach response", "alert"]]);
    if (tab === "audit") {
      var k = NB.v("cpK", "all"), q = String(NB.v("cpQ", "") || "").toLowerCase();
      var rows = A.audit.filter(function (a) { return (k === "all" || a.k === k) && (!q || (a.who + " " + a.obj + " " + a.act + " " + a.why).toLowerCase().indexOf(q) >= 0); });
      h += ui.note("warn", "<b>Read-volume alert (resolved):</b> Sneha Kulkarni opened 84 records in 10 minutes on 16 Oct. She explained it was the reminder-call list; confirmed by Farhan Siddiqui.");
      h += '<div class="row sb"><div class="field" style="flex:1;min-width:220px;max-width:420px"><label for="cp-q">Search by staff, patient, action or reason</label><input id="cp-q" data-change="cpQ" value="' + ui.esc(NB.v("cpQ", "")) + '" placeholder="e.g. Rakesh, export, Gupta"></div>' +
        ui.seg("cpK", [["all", "All"], ["view", "Record views"], ["export", "Exports"], ["share", "Sharing"], ["bg", "Break-glass"], ["settings", "Settings"]]) + "</div>";
      h += ui.table([{ h: "When" }, { h: "Who" }, { h: "Action" }, { h: "Record / object" }, { h: "Facility" }, { h: "Reason / basis" }, { h: "Chain" }],
        rows.map(function (a) {
          var kp = { view: ["mute", "View", "eye"], export: ["info", "Export", "download"], share: ["acc", "Share", "share"], bg: ["crit", "Break-glass", "unlock"], settings: ["sub", "Settings", "sliders"] }[a.k];
          return { ov: "auditentry", arg: a.id, cls: a.k === "bg" ? "crit" : a.flag ? "urg" : "", cells: ['<span class="small num">' + ui.esc(a.t) + "</span>", ui.cellp(ui.esc(a.who), ui.esc(a.role)), ui.pill(kp[0], kp[1], kp[2]) + '<div class="small faint" style="margin-top:4px">' + ui.esc(a.act) + "</div>",
            '<span class="small">' + ui.esc(fx(a.obj)) + "</span>", ui.esc(short(facs()[a.f])), '<span class="small muted">' + ui.esc(a.why) + "</span>", '<span class="okc">' + ui.icon("check", "sm") + "</span>"] };
        }), { min: 1080, empty: "No audit entries match." });
    } else if (tab === "check") {
      var CL = pick({
        india: [["Privacy notice in every patient language, versioned", "done", "DPDP s.5"], ["Consent recorded with the notice version", "done", "DPDP s.6"], ["Privacy officer / grievance contact published", "done", "DPDP Rule 9"],
          ["Access logs kept ≥ 1 year, including record views", "done", "DPDP Rule 6"], ["Breach: CERT-In within 6 h; Board and patients without delay, detail in 72 h", "prog", "CERT-In · DPDP s.8(6) · Board template pending"],
          ["Patient rights: access, correction, erasure, grievance", "done", "DPDP s.11–13"], ["Guardian consent before sharing a child's records", "done", "DPDP Rule 10"],
          ["Consent-manager integration (Rule 4 from 13 Nov 2026)", "prog", "API built, certification pending"], ["ABDM: HFR, HPR, ABHA (M1), HIP (M2)", "prog", "2 doctors not yet in HPR"], ["Clinical records kept ≥ 3 years (set to 10)", "done", "IMC Regulations 2002"]],
        uae: [["Health data stored only in the UAE", "done", "Federal Law 2/2019, Art. 13"], ["Records kept ≥ 25 years from last procedure", "done", "Art. 20"], ["NABIDH connected; opt-out honoured, even in emergencies", "done", "DHA NABIDH policy"],
          ["Malaffi onboarding", "na", "No Abu Dhabi facility"], ["ADHICS / DHA cyber controls self-assessment", "prog", "62 of 71 controls"], ["Every practitioner licence verified in Sheryan", "gap", "1 licence expires 30 Oct"],
          ["Breach: notify DHA and the UAE Data Office without delay", "prog", "Runbook drilled; Arabic template pending"], ["Privacy notice in English and Arabic", "done", "PDPL"]],
        ksa: [["Hosting in-Kingdom; NCA ECC/CCC controls", "done", "NCA"], ["Explicit consent for health data", "done", "PDPL Art. 5–6"], ["SDAIA breach notification within 72 h", "prog", "Template in review"],
          ["Record of processing activities", "prog", "Draft"], ["SCFHS licences verified", "done", "Daily check"], ["NPHIES connected", "done", "Eligibility, pre-auth, claims"], ["ZATCA Fatoora phase 2", "prog", "Wave deadline 1 Feb 2027"], ["Retention period confirmed with counsel", "gap", "Default 25 years set"]],
        qatar: [["Permit to process special-nature (health) data", "prog", "Law No. 13 of 2016 · filed"], ["In-country hosting", "done", "Qatar region"], ["QCHP licences verified", "done", "Daily check"],
          ["MOPH e-Rx and HIE obligations confirmed", "gap", "Awaiting MOPH guidance"], ["Breach notification to NCSA and individuals", "prog", "Runbook drilled"], ["Privacy notice in English and Arabic", "done", "Law No. 13"]]
      });
      var S = { done: ["ok", "Done"], prog: ["warn", "In progress"], gap: ["crit", "Gap"], na: ["mute", "Not applicable"] };
      var done = CL.filter(function (x) { return x[1] === "done"; }).length;
      h += '<div class="split"><div class="card"><div class="ch"><span class="eyebrow">' + ui.icon("checkcircle", "sm") + ui.esc(m.privacyLaw) + " · " + ui.esc(m.name) + "</span>" + ui.pill("info", done + " of " + CL.length + " done") + "</div>" +
        '<div class="ad-steps">' + CL.map(function (x) { return '<div class="ad-chk">' + ui.icon(x[1] === "done" ? "checkcircle" : x[1] === "gap" ? "alertfill" : x[1] === "na" ? "dot" : "hourglass", x[1] === "done" ? "okc" : x[1] === "gap" ? "critc" : x[1] === "na" ? "faint" : "warnc") +
          '<div class="t"><b>' + ui.esc(x[0]) + "</b><small>" + ui.esc(x[2]) + "</small></div>" + ui.pill(S[x[1]][0], S[x[1]][1]) + "</div>"; }).join("") + "</div></div>" +
        '<div class="stack s16">' + ui.card("Readiness", '<div class="stack s8"><b class="num" style="font-size:28px;line-height:34px">' + Math.round(done / CL.length * 100) + '%</b>' + ui.bar(done / CL.length * 100) + '<span class="small faint">Switch the market in the top bar to see another country\'s list.</span></div>', { icon: "chart" }) +
        ui.card("Evidence pack", '<p class="small muted" style="margin:0 0 12px">Policies, audit samples, access reviews and drill reports in one signed download for auditors.</p>' + ui.btn("Generate pack", { cls: "sm", icon: "download", act: "toast", arg: "Evidence pack is being prepared · secure link in about 2 minutes" }), { icon: "file" }) + "</div></div>";
    } else {
      var clock = pick({ india: "CERT-In within 6 h · Data Protection Board and patients without delay · detailed report in 72 h", uae: "DHA and the UAE Data Office without delay", ksa: "SDAIA within 72 h · patients without undue delay", qatar: "NCSA and patients as soon as possible" });
      var steps = [["Detect & triage", "On-call within 15 min · severity set", "ok"], ["Contain", "Revoke sessions, rotate keys, freeze exports", "ok"], ["Assess", "Who, which data, how many people · pulled from the audit chain", "ok"],
        ["Notify regulator", clock, NB.gcc() ? "warn" : "ok"], ["Notify patients", "Plain-language WhatsApp + SMS in " + (NB.gcc() ? "English and Arabic" : "English, Hindi, Kannada"), "ok"], ["Review & lessons", "Within 14 days · actions tracked", "ok"]];
      h += '<div class="split"><div class="card"><div class="ch"><span class="eyebrow">' + ui.icon("alert", "sm") + "Runbook · strictest clock applies</span>" + ui.pill("ok", "Drilled 12 Sep") + "</div>" +
        '<div class="tl">' + steps.map(function (s, i) { return '<div class="ev"><span class="dotc ' + (s[2] === "ok" ? "ok" : "warn") + '"><b class="small">' + (i + 1) + '</b></span><div class="bd"><b>' + ui.esc(s[0]) + "</b><small>" + ui.esc(s[1]) + "</small></div>" + (s[2] === "ok" ? ui.pill("ok", "Ready") : ui.pill("warn", "Template pending")) + "</div>"; }).join("") + "</div></div>" +
        '<div class="stack s16">' + ui.card("Last drill", kv([["Date", "12 Sep 2026 (tabletop)"], ["Time to contain", num("42 min")], ["Regulator draft ready", num("3 h 10 min")], ["Next drill", "12 Dec 2026"]]), { icon: "history" }) +
        ui.card("Who is on call", '<div class="lst">' + ui.li(ui.av(privacyOfficer().n), ui.esc(privacyOfficer().n), "Privacy officer · incident lead", ui.pill("ok", "On call")) + ui.li(ui.av("Nabd Security"), "Nabd security team", "Platform side · 24×7", ui.pill("ok", "On call")) + "</div>", { icon: "phone" }) +
        ui.card("Open incidents", ui.li('<span class="pill crit" style="height:32px;width:32px;padding:0;justify-content:center">' + ui.icon("alertfill", "sm") + "</span>", "INC-031 · unjustified break-glass", "1 patient · internal misuse · patient informed · not reportable", ui.pill("warn", "Closing")), { icon: "alert" }) + "</div></div>";
    }
    return h;
  });
  NB.overlay("auditentry", function (id) {
    var a = A.audit.find(function (q) { return q.id === id; }) || A.audit[0];
    var diff = a.k === "settings" ? '<div class="grid g2"><div class="card" style="padding:12px"><span class="eyebrow">Before</span><p class="small" style="margin:6px 0 0">' + ui.esc(a.obj.split(":")[1] ? a.obj.split(":")[1].split("→")[0] : "—") + '</p></div><div class="card" style="padding:12px"><span class="eyebrow">After</span><p class="small" style="margin:6px 0 0">' + ui.esc(a.obj.split("→")[1] || "—") + "</p></div></div>" : "";
    return { kind: "drawer", title: "Audit entry " + a.id, body:
      kv([["When", ui.esc(a.t) + " · " + ui.esc(mk().tz)], ["Who", ui.esc(a.who + " · " + a.role)], ["Action", ui.esc(a.act)], ["Record / object", ui.esc(fx(a.obj))], ["Facility", ui.esc(facs()[a.f].name)], ["Reason / basis", ui.esc(a.why)], ["Device", ui.esc(a.dev)]]) + diff +
      '<div class="stack s4"><b class="small">Tamper evidence</b><span class="ad-hash">row sha256 9c41e0…7f2a · prev 51b8d3…0e94 · chain ' + ui.esc(facs()[a.f].name) + " #" + a.id.slice(2) + "</span>" + ui.pill("ok", "Verified", "shieldcheck") + "</div>" +
      (a.k === "view" || a.k === "share" || a.k === "bg" ? ui.note("info", "This entry also appears in the patient's own access history.") : ""),
      foot: ui.btn("Close", { act: "close" }) };
  });

  /* ================= owner.plan ================= */
  NB.screen("owner.plan", function (c) {
    var m = c.m, id = m.id;
    var P = { india: [1299, 2999, 999, 1499, 4999, 1999, 0, 499], uae: [249, 499, 249, 299, 999, 399, 0, 99], ksa: [299, 599, 299, 349, 1199, 449, 0, 119], qatar: [249, 499, 249, 299, 999, 399, 0, 99] }[id] || [];
    var raw = function (v) { return NB.money(v, true); };
    var mods = [["clinic", "Clinic", "steth", "Queue with ETAs, WhatsApp booking, EMR, e-Rx", P[0], "per doctor", true], ["pharmacy", "Pharmacy", "pill", "Dispensing, stock, expiry, registers", P[2], "per counter", true],
      ["lab", "Lab", "flask", "Orders, samples, analysers, reports", P[3], "per lab", true], ["ipd", "Inpatient (IPD)", "bed", "Beds, admissions, medication rounds, discharge", P[4], "per 50 beds", true],
      ["ins", "Insurance & claims", "shieldcheck", m.claims, P[5], "per facility", true], ["app", "Patient app", "smartphone", "Records, family, privacy controls, payments", P[6], "included", true], ["ai", "AI safety net", "sparkle", "Allergy, interaction, pregnancy, renal checks (in-region)", P[7], "per doctor", true]];
    var h = ui.header(L("Plan & modules", "الخطة والوحدات", "प्लान और मॉड्यूल"), "Billed monthly in " + m.curCode + " · " + (m.taxRate ? m.tax + " " + m.taxRate + "% on the subscription" : m.tax),
      ui.btn("Talk to us", { icon: "chat", act: "toast", arg: "Your account manager will reply on WhatsApp within an hour" }));
    h += '<div class="split wide">' + ui.card(L("Current plan", "الخطة الحالية", "वर्तमान प्लान"), '<div class="row sb"><div><h2 class="h" style="font-size:20px">Hospital · group</h2><span class="small faint">3 facilities · 23 doctors · 43 beds · since Mar 2023</span></div>' + ui.pill("ok", "Active") + "</div>" +
      '<div style="margin-top:16px">' + kv([["Next invoice", "01 Nov 2026"], ["Estimated amount", raw(Math.round((P[0] * 23 + P[2] * 2 + P[3] * 2 + P[4] + P[5] * 3) * (1 + m.taxRate / 100)))], ["Payment", pick({ india: "UPI AutoPay · HDFC", uae: "Card ending 4417", ksa: "mada ending 0921", qatar: "Card ending 6630" })], ["Data region", ui.esc(m.residency)]]) + "</div>", { icon: "layers", cls: "hl" }) +
      ui.card(L("This month's usage", "استخدام هذا الشهر", "इस महीने का उपयोग"), '<div class="stack">' + [["Visits", 9842, 12000, ""], ["WhatsApp utility messages", 18420, 30000, "pass-through at Meta rates"], ["AI safety checks", 6120, 10000, "AI module trial"], ["Storage", 182, 500, "GB"]].map(function (u) {
        return '<div class="stack s4"><div class="row sb"><span class="small">' + ui.esc(u[0]) + '</span><span class="small num ltr">' + u[1].toLocaleString("en-IN") + " / " + u[2].toLocaleString("en-IN") + (u[3] === "GB" ? " GB" : "") + "</span></div>" + ui.bar(u[1] / u[2] * 100, u[1] / u[2] > 0.85 ? "warn" : "") + (u[3] && u[3] !== "GB" ? '<span class="tiny faint">' + ui.esc(u[3]) + "</span>" : "") + "</div>";
      }).join("") + "</div>", { icon: "activity" }) + "</div>";
    h += '<div class="stack s8"><h2 class="h">' + L("Modules", "الوحدات", "मॉड्यूल") + '</h2><div class="grid g3">' + mods.map(function (x) {
      var on = NB.v("pl_" + x[0], x[6]);
      return '<div class="card ad-mod' + (on ? "" : "") + '"><div class="row nw"><span class="ad-ii">' + ui.icon(x[2]) + '</span><div class="fl"><h3 class="h">' + ui.esc(x[1]) + '</h3><span class="small faint">' + ui.esc(x[3]) + "</span></div></div>" +
        '<div class="row sb"><span class="small">' + (x[4] ? raw(x[4]) + " " + ui.esc(x[5]) : ui.esc("Included")) + "</span>" + (x[0] === "ai" && on ? ui.pill("sub", "Trial to 31 Oct") : on ? ui.pill("ok", "On") : ui.pill("mute", "Off")) + "</div>" +
        ui.toggle("pl_" + x[0], on ? "Enabled" : "Disabled", x[0] === "clinic" ? "Core module" : "", x[6]) + "</div>";
    }).join("") + "</div></div>";
    var inv = [["NB-" + m.flag + "-2610", "Oct 2026", "Upcoming", "mute"], ["NB-" + m.flag + "-2609", "Sep 2026", "Paid", "ok"], ["NB-" + m.flag + "-2608", "Aug 2026", "Paid", "ok"], ["NB-" + m.flag + "-2607", "Jul 2026", "Paid", "ok"]];
    var base = P[0] * 23 + P[2] * 2 + P[3] * 2 + P[4] + P[5] * 3;
    h += '<div class="stack s8"><h2 class="h">' + L("Invoices", "الفواتير", "इनवॉइस") + "</h2>" + ui.table([{ h: "Invoice" }, { h: "Period" }, { h: "Subscription", cls: "r" }, { h: "WhatsApp (pass-through)", cls: "r" }, { h: m.tax, cls: "r" }, { h: "Total", cls: "r" }, { h: "Status" }, { h: "" }],
      inv.map(function (r, i) {
        var wa = [0, 2110, 1980, 1870][i] * (id === "india" ? 1 : 0.06), sub = base - i * P[0], tax = Math.round((sub + wa) * m.taxRate) / 100;
        return ['<span class="tok">' + r[0] + "</span>", r[1], raw(sub), raw(Math.round(wa)), raw(tax), "<b>" + raw(Math.round(sub + wa + tax)) + "</b>", ui.pill(r[3], r[2]), i ? ui.btn("PDF", { cls: "sm ghost", icon: "download", act: "toast", arg: r[0] + " downloaded" + (id === "ksa" ? " (ZATCA QR included)" : "") }) : ""];
      }), { min: 820 }) + "</div>";
    return h;
  });
})();
