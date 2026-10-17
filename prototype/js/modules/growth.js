/* Growth & business module (role "owner" + public "connect").
   Owner: clinic setup wizard, branches (multi-branch lite), regional calendar, compliance health card,
   price master & fees, report library, recalls / reviews / referrals / feedback / WhatsApp quality,
   own booking page & widget, doctor payouts & referrals, shifts / attendance / payroll export.
   Public: the clinic's own branded booking site and Nabd's "for clinics" trial page.
   Overlays: branchadd, pricechange, reportview, grievance, payoutstmt, exportall.
   Sample data lives under NB.D.growth. Charts reuse NB.adCharts (admin.js). */
(function () {
  "use strict";
  var ui = NB.ui, L = NB.L, esc = ui.esc;

  /* ================= Styles (prefix gr-, nb.css tokens only) ================= */
  var CSS = [
    ".gr-wz{display:grid;grid-template-columns:300px minmax(0,1fr);gap:20px;align-items:start}",
    ".frame.tablet .gr-wz,.frame.mobile .gr-wz{grid-template-columns:minmax(0,1fr)}",
    ".gr-steps{display:flex;flex-direction:column;gap:2px}",
    ".gr-step{display:flex;align-items:center;gap:12px;padding:9px 10px;border-radius:var(--r-md);border:1px solid transparent;background:none;width:100%;text-align:start;cursor:pointer;color:var(--tx);font:inherit}",
    ".gr-step:hover{background:var(--hover)}",
    ".gr-step.on{background:var(--acc-tint);border-color:var(--acc-line)}",
    ".gr-step .t{flex:1;min-width:0}.gr-step .t b{display:block;font-size:13px;font-weight:500;line-height:18px}.gr-step .t small{display:block;font-size:11.5px;color:var(--tx3);line-height:15px}",
    ".gr-sn{width:28px;height:28px;border-radius:99px;display:grid;place-items:center;flex:none;font:600 12px/1 var(--font);border:1px solid var(--b-def);background:var(--s3);color:var(--tx2)}",
    ".gr-sn.done{background:var(--ok-t);border-color:var(--ok);color:var(--ok)}",
    ".gr-sn.cur{background:var(--acc-fill);border-color:var(--acc-fill);color:var(--on-acc)}",
    ".gr-sn.warn{background:var(--warn-t);border-color:var(--warn);color:var(--warn)}",
    ".gr-sn .ico{width:14px;height:14px}",
    ".gr-presets{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}",
    ".frame.mobile .gr-presets{grid-template-columns:minmax(0,1fr)}",
    ".gr-preset{display:flex;flex-direction:column;gap:10px;padding:14px;border:1px solid var(--b-def);border-radius:var(--r-md);background:var(--s2);cursor:pointer;min-width:0}",
    ".gr-preset.on{border-color:var(--acc);background:var(--acc-tint)}",
    ".gr-preset .hd{display:flex;gap:10px;align-items:flex-start}",
    ".gr-preset .hd b{display:block;font-size:13.5px}.gr-preset .hd small{display:block;color:var(--tx2);font-size:12px;line-height:16px}",
    ".gr-on{display:flex;flex-direction:column;gap:4px;font-size:12px;color:var(--tx2);line-height:16px}",
    ".gr-on span{display:flex;gap:6px;align-items:flex-start}.gr-on .ico{width:13px;height:13px;margin-top:1px;flex:none}",
    ".gr-ii{width:36px;height:36px;border-radius:10px;display:grid;place-items:center;background:var(--s3);color:var(--acc);flex:none}",
    ".gr-ii.ok{background:var(--ok-t);color:var(--ok)}.gr-ii.warn{background:var(--warn-t);color:var(--warn)}.gr-ii.crit{background:var(--crit-t);color:var(--crit)}.gr-ii.info{background:var(--info-t);color:var(--info)}.gr-ii.sub{background:var(--sub-t);color:var(--sub)}.gr-ii.wa{color:var(--wa)}",
    ".gr-hc{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:12px}",
    ".frame.mobile .gr-hc{grid-template-columns:minmax(0,1fr)}",
    ".gr-hct{display:flex;gap:12px;align-items:flex-start;padding:14px;border-radius:var(--r-md);border:1px solid var(--b-sub);background:var(--s2);min-width:0}",
    ".gr-hct .t{flex:1;min-width:0;display:flex;flex-direction:column;gap:4px}.gr-hct .t b{font-size:13.5px;font-weight:600}.gr-hct .t small{font-size:12px;color:var(--tx2);line-height:16px}",
    ".gr-tl{width:30px;height:30px;border-radius:99px;display:grid;place-items:center;flex:none;border:2px solid}",
    ".gr-tl .ico{width:15px;height:15px}",
    ".gr-tl.ok{color:var(--ok);background:var(--ok-t);border-color:var(--ok)}.gr-tl.warn{color:var(--warn);background:var(--warn-t);border-color:var(--warn)}.gr-tl.crit{color:var(--crit);background:var(--crit-t);border-color:var(--crit)}",
    ".gr-strip{position:relative;height:40px;border-radius:10px;background:var(--s3);overflow:hidden}",
    ".gr-strip i{position:absolute;top:0;bottom:0;display:block}",
    ".gr-strip .op{background:var(--acc-tint2);border-inline:1px solid var(--acc-line)}",
    ".gr-strip .pr{background:repeating-linear-gradient(135deg,var(--warn-t) 0 6px,transparent 6px 10px);border-inline:2px solid var(--warn)}",
    ".gr-strip .rm{background:repeating-linear-gradient(135deg,var(--sub-t) 0 6px,transparent 6px 10px);border-inline:2px solid var(--sub)}",
    ".gr-sx{position:relative;height:16px;font-size:10.5px;color:var(--tx3);font-variant-numeric:tabular-nums}",
    ".gr-sx span{position:absolute;transform:translateX(-50%);white-space:nowrap}",
    ".gr-leg{display:flex;flex-wrap:wrap;gap:6px 16px;font-size:11.5px;color:var(--tx2)}",
    ".gr-leg i{display:inline-block;width:14px;height:10px;border-radius:3px;margin-inline-end:6px;vertical-align:-1px}",
    ".gr-leg .op{background:var(--acc-tint2);border:1px solid var(--acc-line)}.gr-leg .pr{background:var(--warn-t);border:1px solid var(--warn)}.gr-leg .rm{background:var(--sub-t);border:1px solid var(--sub)}",
    ".gr-bil{display:grid;grid-template-columns:repeat(auto-fill,minmax(112px,1fr));gap:8px}",
    ".gr-bb{display:flex;flex-direction:column;gap:2px;padding:10px;border-radius:10px;border:1px solid var(--b-def);background:var(--s2);font-size:12.5px;font-weight:600;min-width:0;text-align:start;cursor:pointer;color:var(--tx)}",
    ".gr-bb small{font-weight:500;color:var(--tx2);font-size:11.5px}",
    ".gr-bb.off{border-style:dashed;color:var(--tx3)}",
    ".gr-mono{font:500 12px/18px ui-monospace,SFMono-Regular,Menlo,monospace;direction:ltr;unicode-bidi:isolate}",
    ".gr-code{font:500 12px/19px ui-monospace,SFMono-Regular,Menlo,monospace;background:var(--sunk);border:1px solid var(--b-def);border-radius:var(--r-md);padding:12px 14px;color:var(--tx);white-space:pre-wrap;word-break:break-all;direction:ltr;text-align:left;margin:0}",
    ".gr-sw{display:flex;flex-wrap:wrap;gap:10px}",
    ".gr-swb{display:flex;align-items:center;gap:8px;height:36px;padding:0 12px 0 6px;border-radius:999px;border:1px solid var(--b-def);background:var(--s2);cursor:pointer;font:500 12.5px/1 var(--font);color:var(--tx)}",
    "[dir=rtl] .gr-swb{padding:0 6px 0 12px;font-family:var(--font-ar)}",
    ".gr-swb i{width:24px;height:24px;border-radius:99px;background:var(--gr-b);display:block}",
    ".gr-swb.on{border-color:var(--gr-b);box-shadow:0 0 0 2px var(--gr-b)}",
    ".gr-b-acc{--gr-b:var(--acc)}.gr-b-info{--gr-b:var(--info)}.gr-b-sync{--gr-b:var(--sync)}.gr-b-sub{--gr-b:var(--sub)}.gr-b-ok{--gr-b:var(--ok)}.gr-b-urg{--gr-b:var(--urg)}",
    ".gr-bbtn{background:var(--gr-b);color:var(--bg-canvas);border-color:transparent}",
    ".gr-bbtn:hover{color:var(--bg-canvas);filter:brightness(1.06)}",
    ".gr-bt{color:var(--gr-b)}",
    ".gr-bmark{width:40px;height:40px;border-radius:12px;display:grid;place-items:center;background:var(--gr-b);color:var(--bg-canvas);font:700 16px/1 var(--font);flex:none}",
    ".gr-bmark.sm{width:28px;height:28px;border-radius:8px;font-size:12px}",
    ".gr-phone{border:6px solid var(--bezel);border-radius:28px;background:var(--bg-canvas);overflow:hidden;max-width:320px;width:100%;margin-inline:auto;display:flex;flex-direction:column}",
    ".gr-phone .bar{height:4px;background:var(--gr-b)}",
    ".gr-phone .hd{display:flex;align-items:center;gap:10px;padding:12px 14px;border-bottom:1px solid var(--b-sub)}",
    ".gr-phone .hd b{display:block;font-size:13px}.gr-phone .hd small{display:block;font-size:11px;color:var(--tx3)}",
    ".gr-phone .bd{padding:12px 14px;display:flex;flex-direction:column;gap:10px}",
    ".gr-phone .dr{display:flex;align-items:center;gap:8px;padding:8px;border:1px solid var(--b-sub);border-radius:10px;background:var(--s1)}",
    ".gr-phone .dr .t{flex:1;min-width:0}.gr-phone .dr .t b{display:block;font-size:12px}.gr-phone .dr .t small{display:block;font-size:10.5px;color:var(--tx2)}",
    ".gr-phone .ft{font-size:10.5px;color:var(--tx3);text-align:center;padding:8px;border-top:1px solid var(--b-sub)}",
    ".gr-pb{font-size:11px;font-weight:600;height:26px;padding:0 10px;border-radius:8px;display:inline-flex;align-items:center}",
    ".gr-qr{width:132px;height:132px;flex:none;background:var(--s1);border:1px solid var(--b-def);border-radius:10px;padding:8px}",
    ".gr-qr svg{width:100%;height:100%;display:block}.gr-qr rect{fill:var(--tx)}",
    ".gr-fn{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}",
    ".frame.mobile .gr-fn{grid-template-columns:repeat(2,minmax(0,1fr))}",
    ".gr-fs{border-radius:var(--r-md);border:1px solid var(--b-sub);background:var(--s2);padding:12px;display:flex;flex-direction:column;gap:6px;min-width:0}",
    ".gr-fs b{font:600 22px/28px var(--font);font-variant-numeric:tabular-nums}",
    ".gr-fs span{font-size:12px;color:var(--tx2);line-height:16px}",
    ".gr-rlib{display:flex;flex-direction:column;gap:2px}",
    ".gr-rg{font:600 10.5px/14px var(--font);letter-spacing:.8px;text-transform:uppercase;color:var(--tx3);padding:12px 10px 4px}",
    "[dir=rtl] .gr-rg{letter-spacing:0}",
    ".gr-ri{display:flex;align-items:center;gap:10px;padding:9px 10px;border-radius:var(--r-md);border:1px solid transparent;background:none;width:100%;text-align:start;cursor:pointer;color:var(--tx);font:inherit;min-width:0}",
    ".gr-ri:hover{background:var(--hover)}.gr-ri.on{background:var(--acc-tint);border-color:var(--acc-line)}",
    ".gr-ri .t{flex:1;min-width:0}.gr-ri .t b{display:block;font-size:13px;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.gr-ri .t small{display:block;font-size:11.5px;color:var(--tx3);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
    ".gr-rule{display:flex;gap:8px;align-items:flex-start;font-size:12.5px;line-height:18px;color:var(--tx2);background:var(--s2);border:1px dashed var(--b-str);border-radius:var(--r-md);padding:10px 12px}",
    ".gr-rule .ico{color:var(--acc);margin-top:1px;flex:none}",
    ".gr-rule code{font:500 12px/18px ui-monospace,SFMono-Regular,Menlo,monospace;color:var(--tx)}",
    ".gr-deliv{display:flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:space-between;padding:12px 14px;border:1px solid var(--b-def);border-radius:var(--r-md);background:var(--s2)}",
    ".gr-rost{display:grid;grid-template-columns:minmax(150px,1.3fr) repeat(7,minmax(84px,1fr));min-width:860px}",
    ".gr-rost>div{padding:10px;border-top:1px solid var(--div);font-size:12.5px;min-width:0}",
    ".gr-rost .h{background:var(--s3);font:600 10.5px/14px var(--font);letter-spacing:.6px;text-transform:uppercase;color:var(--tx3);border-top:0}",
    "[dir=rtl] .gr-rost .h{letter-spacing:0}",
    ".gr-sh{display:inline-flex;flex-direction:column;gap:1px;padding:5px 8px;border-radius:8px;background:var(--acc-tint);border:1px solid var(--acc-line);font-size:11.5px;font-weight:600;color:var(--tx);line-height:15px;white-space:nowrap}",
    ".gr-sh.split{background:var(--info-t);border-color:var(--info)}.gr-sh.off{background:transparent;border:1px dashed var(--b-str);color:var(--tx3);font-weight:500}.gr-sh.lv{background:var(--sub-t);border-color:var(--sub)}.gr-sh.rm{background:var(--sub-t);border-color:var(--sub)}",
    ".gr-site{display:flex;flex-direction:column;min-width:0;width:100%}",
    ".gr-snav{display:flex;align-items:center;gap:12px;padding:12px 24px;border-bottom:1px solid var(--b-sub);background:var(--bg-nav);flex-wrap:wrap}",
    ".gr-snav .sp{flex:1}",
    ".gr-snav .logo{padding:0}",
    ".gr-snav .nm b{display:block;font-size:15px;line-height:19px}.gr-snav .nm small{display:block;font-size:11.5px;color:var(--tx3)}",
    ".gr-sbody{width:100%;max-width:1160px;margin-inline:auto;padding:24px;display:flex;flex-direction:column;gap:20px;min-width:0}",
    ".frame.mobile .gr-snav{padding:10px 16px}.frame.mobile .gr-sbody{padding:16px;gap:16px}",
    ".frame.mobile .gr-snav .hide-s{display:none}",
    ".gr-hero{display:flex;flex-direction:column;gap:14px;padding:28px;border-radius:var(--r-xl);border:1px solid var(--b-def);background:var(--s1);position:relative;overflow:hidden}",
    ".gr-hero.br{border-top:4px solid var(--gr-b)}",
    ".gr-hero h1{margin:0;font:600 30px/38px var(--font);letter-spacing:-.4px;text-wrap:balance}",
    "[dir=rtl] .gr-hero h1{font-family:var(--font-ar);letter-spacing:0}",
    ".gr-hero p{margin:0;color:var(--tx2);max-width:680px}",
    ".frame.mobile .gr-hero{padding:18px}.frame.mobile .gr-hero h1{font-size:22px;line-height:29px}",
    ".gr-hero.acc{border-color:var(--acc-line);background:linear-gradient(135deg,rgba(46,211,224,.12),rgba(62,224,196,.03) 60%),var(--s1)}",
    ".gr-vp{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}",
    ".frame.tablet .gr-vp{grid-template-columns:repeat(2,minmax(0,1fr))}.frame.mobile .gr-vp{grid-template-columns:minmax(0,1fr)}",
    ".gr-vp .card{display:flex;flex-direction:column;gap:6px}",
    ".gr-big{font:600 28px/34px var(--font);font-variant-numeric:tabular-nums;letter-spacing:-.4px}",
    ".gr-plans{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;align-items:stretch}",
    ".frame.tablet .gr-plans,.frame.mobile .gr-plans{grid-template-columns:minmax(0,1fr)}",
    ".gr-plan{display:flex;flex-direction:column;gap:12px}",
    ".gr-plan.hl{border-color:var(--acc)}",
    ".gr-plan .pr{display:flex;align-items:baseline;gap:6px;flex-wrap:wrap}.gr-plan .pr b{font:600 28px/34px var(--font)}.gr-plan .pr span{color:var(--tx2);font-size:12.5px}",
    ".gr-ul{display:flex;flex-direction:column;gap:6px;font-size:13px;color:var(--tx2);margin:0;padding:0;list-style:none}",
    ".gr-ul li{display:flex;gap:8px;align-items:flex-start}.gr-ul .ico{color:var(--ok);width:15px;height:15px;margin-top:2px;flex:none}",
    ".gr-tst{display:flex;flex-direction:column;gap:10px;border-style:dashed}",
    ".gr-ph{border:1px dashed var(--b-str);border-radius:var(--r-md);padding:12px;color:var(--tx2);font-size:12.5px;line-height:18px}",
    ".gr-kv{margin:0}",
    ".gr-stk{display:flex;height:14px;border-radius:6px;overflow:hidden;gap:2px;background:var(--s1)}.gr-stk i{display:block;height:100%}",
    ".gr-stk .p,.gr-leg .p{background:var(--ok)}.gr-stk .n,.gr-leg .n{background:var(--warn)}.gr-stk .d,.gr-leg .d{background:var(--crit)}",
    ".gr-mini{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}",
    ".frame.mobile .gr-mini{grid-template-columns:repeat(2,minmax(0,1fr))}",
    ".gr-mini .card{padding:14px;display:flex;flex-direction:column;gap:4px}",
    ".gr-mini .card b{font:600 22px/28px var(--font);font-variant-numeric:tabular-nums}",
    ".gr-mini .card span{font-size:12px;color:var(--tx2);line-height:16px}",
    ".gr-cmp{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}",
    ".frame.mobile .gr-cmp{grid-template-columns:minmax(0,1fr)}",
    ".gr-prog{display:flex;flex-direction:column;gap:6px}",
    ".gr-jobs{display:flex;flex-direction:column;gap:0}",
    ".gr-cert{border:1px solid var(--ok);border-radius:var(--r-lg);padding:18px;background:var(--ok-t);display:flex;flex-direction:column;gap:10px}",
    ".gr-cert h3{margin:0;font:600 15px/22px var(--font)}",
    ".gr-cks{display:flex;flex-direction:column;gap:0}",
    ".gr-ck{display:flex;gap:10px;align-items:center;padding:10px 0;border-bottom:1px solid var(--div);font-size:13px;cursor:pointer}",
    ".gr-ck:last-child{border-bottom:0}",
    ".gr-cb{width:18px;height:18px;border-radius:5px;border:2px solid var(--tx3);flex:none;display:grid;place-items:center}",
    ".gr-cb.on{background:var(--acc-fill);border-color:var(--acc-fill);color:var(--on-acc)}",
    ".gr-cb .ico{width:12px;height:12px;stroke-width:3}",
    ".gr-ck .t{flex:1;min-width:0}.gr-ck .t small{display:block;color:var(--tx3);font-size:11.5px}",
    ".gr-bsel{display:flex;align-items:center;gap:8px;flex-wrap:wrap}",
    ".gr-field select{background:transparent;border:0;outline:0;font-size:14px;font-weight:500;padding:0;width:100%;min-width:0}",
    ".gr-stars{color:var(--warn);white-space:nowrap;letter-spacing:1px}",
    ".gr-quote{font-size:13px;line-height:19px;color:var(--tx);margin:0}",
    ".gr-nw{white-space:nowrap}",
    ".gr-wrap{white-space:normal !important}",
    ".gr-foot{display:flex;flex-wrap:wrap;gap:8px;justify-content:space-between;align-items:center;border-top:1px solid var(--div);padding-top:14px;margin-top:4px}",
    ".gr-sfoot{border-top:1px solid var(--b-sub);padding:18px 24px;display:flex;flex-wrap:wrap;gap:8px 18px;align-items:center;justify-content:center;font-size:12px;color:var(--tx2)}",
    ".gr-sfoot a{color:var(--tx2);text-decoration:underline}",
    ".gr-pw{display:inline-flex;align-items:center;gap:6px;color:var(--tx2)}",
    ".gr-slots{display:flex;flex-wrap:wrap;gap:6px}",
    ".gr-slot{height:30px;padding:0 10px;border-radius:8px;border:1px solid var(--b-def);background:var(--s2);font:600 12px/28px var(--font);color:var(--tx);cursor:pointer;font-variant-numeric:tabular-nums}",
    ".gr-live{display:inline-flex;align-items:center;gap:6px;font-weight:600;font-variant-numeric:tabular-nums}",
    ".gr-live .pulse{color:var(--ok)}",
    "@media (max-width:760px){.gr-wz{grid-template-columns:minmax(0,1fr)}.gr-presets,.gr-cmp,.gr-hc{grid-template-columns:minmax(0,1fr)}.gr-mini,.gr-fn{grid-template-columns:repeat(2,minmax(0,1fr))}.gr-vp,.gr-plans{grid-template-columns:minmax(0,1fr)}.gr-snav{padding:10px 16px}.gr-sbody{padding:16px;gap:16px}.gr-snav .hide-s{display:none}.gr-hero{padding:18px}.gr-hero h1{font-size:22px;line-height:29px}}"
  ].join("\n");
  (function () {
    if (document.getElementById("gr-css")) return;
    var s = document.createElement("style"); s.id = "gr-css"; s.textContent = CSS; document.head.appendChild(s);
  })();

  /* ================= Helpers ================= */
  function mk() { return NB.mkt(); }
  function mid() { return NB.state.market; }
  function pick(o) { return o[mid()] || o.india; }
  function gcc() { return NB.gcc(); }
  function num(x) { return '<span class="num ltr">' + x + "</span>"; }
  function M(inr) { return NB.money(inr); }
  function RAW(v) { return NB.money(v, true); }
  function nf(n) { return Number(n).toLocaleString("en-IN"); }
  // Compact money: ₹18.4L / ₹1.13 Cr in India, 81.2K AED in the GCC.
  function cm(inr) {
    var m = mk(), v = inr * m.fx, s;
    if (m.id === "india") { s = v >= 1e7 ? (v / 1e7).toFixed(2) + " Cr" : v >= 1e5 ? (v / 1e5).toFixed(1) + "L" : v >= 1e3 ? (v / 1e3).toFixed(1) + "K" : Math.round(v); return '<span class="num ltr">₹' + s + "</span>"; }
    s = v >= 1e6 ? (v / 1e6).toFixed(2) + "M" : v >= 1e3 ? (v / 1e3).toFixed(1) + "K" : v.toFixed(0);
    return '<span class="num ltr">' + s + " " + m.cur + "</span>";
  }
  function CH() { return NB.adCharts || { cols: function () { return ""; }, hbars: function () { return ""; }, lineChart: function () { return ""; } }; }
  function kv(rows) { return '<dl class="kv gr-kv">' + rows.map(function (r) { return "<dt>" + r[0] + "</dt><dd>" + r[1] + "</dd>"; }).join("") + "</dl>"; }
  function facs() { return NB.facilities(); }
  function short(f) { return f.area.split(",")[0]; }
  function dn(id) { return NB.dname(NB.doc(id)); }
  function ii(icon, cls) { return '<span class="gr-ii ' + (cls || "") + '">' + ui.icon(icon, "sm") + "</span>"; }
  function sel(label, key, opts, def) {
    var cur = NB.v(key, def);
    return '<div class="field gr-field"><label>' + esc(label) + '</label><select data-change="' + key + '" aria-label="' + esc(label) + '">' +
      opts.map(function (o) { return '<option value="' + esc(o[0]) + '"' + (o[0] === cur ? " selected" : "") + ">" + esc(o[1]) + "</option>"; }).join("") + "</select></div>";
  }
  function tl(kind) { return '<span class="gr-tl ' + kind + '">' + ui.icon(kind === "ok" ? "check" : kind === "warn" ? "alertcircle" : "alertfill") + "</span>"; }
  function tlText(kind) { return kind === "ok" ? L("Green", "أخضر", "हरा") : kind === "warn" ? L("Amber", "أصفر", "पीला") : L("Red", "أحمر", "लाल"); }
  function ck(key, title, sub, def) {
    var on = NB.v(key, def);
    return '<div class="gr-ck" data-toggle="' + key + '" role="checkbox" aria-checked="' + !!on + '" tabindex="0"><span class="gr-cb' + (on ? " on" : "") + '">' + (on ? ui.icon("check") : "") + '</span><div class="t">' + esc(title) + (sub ? "<small>" + esc(sub) + "</small>" : "") + '</div><span class="sw ' + (on ? "on" : "") + '" style="display:none"></span></div>';
  }
  function gd(iso) { var d = new Date(iso + "T09:00:00"); return d.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" }); }
  function hijri(iso) {
    var d = new Date(iso + "T09:00:00");
    try { return new Intl.DateTimeFormat((NB.rtl() ? "ar-SA" : "en") + "-u-ca-islamic-umalqura", { day: "numeric", month: "long", year: "numeric" }).format(d); }
    catch (e) { return "1448 AH"; }
  }
  function brandOf() { return NB.v("grBrand", "info"); }
  function clinicName() { return facs()[0].name; }
  function slug() { return pick({ india: "suvidha", uae: "alnoor-dubai", ksa: "alnoor-riyadh", qatar: "alnoor-doha" }); }
  function ownDomain() { return pick({ india: "book.suvidhaclinic.in", uae: "book.alnoorclinic.ae", ksa: "book.alnoormedical.sa", qatar: "book.alnoorclinic.qa" }); }
  function waNum(i) { return pick({ india: ["+91 80 4718 2200", "+91 80 4718 2211", "+91 80 4718 2200"], uae: ["+971 4 555 0140", "+971 2 555 0177", "+971 4 555 0140"], ksa: ["+966 11 555 0140", "+966 11 555 0177", "+966 12 555 0193"], qatar: ["+974 4455 0140", "+974 4455 0177", "+974 4455 0140"] })[i]; }
  function owner() { return NB.ROLES.owner.person; }
  function dpo() { return gcc() ? { n: "Layla Haddad", mail: "dpo@alnoorhealth." + pick({ uae: "ae", ksa: "sa", qatar: "qa" }) } : { n: "Farhan Siddiqui", mail: "dpo@suvidhahealth.in" }; }

  /* ---------- Tax per charge class and market ---------- */
  var TAX = {
    india: { svc: [0, "Exempt · SAC 9993"], cos: [18, "GST 18% · SAC 999722"], med: [12, "GST 12% · HSN 3004"], cons: [12, "GST 12% · HSN 3005"] },
    uae: { svc: [0, "VAT 0% · zero-rated healthcare"], cos: [5, "VAT 5% · standard"], med: [0, "VAT 0% · qualifying medicine"], cons: [5, "VAT 5% · standard"] },
    ksa: { svc: [15, "VAT 15% · nationality rule"], cos: [15, "VAT 15% · all patients"], med: [0, "VAT 0% · zero-rated medicine"], cons: [15, "VAT 15% · standard"] },
    qatar: { svc: [0, "No VAT"], cos: [0, "No VAT"], med: [0, "No VAT"], cons: [0, "No VAT"] }
  };
  function tax(cls) { return (TAX[mid()] || TAX.india)[cls === "cert" ? "svc" : cls] || [0, "—"]; }
  function taxPill(cls) { var t = tax(cls); return ui.pill(t[0] ? "info" : "mute", t[1], t[0] ? "percent" : "dot"); }

  /* ================= Sample data (NB.D.growth) ================= */
  var G = NB.D.growth = {
    // Price master (INR-scale amounts; NB.money converts per market). fu/em blank = falls back to base.
    heads: [
      { id: "CON", en: "Consultation", ar: "استشارة", cat: "Consultation", base: 700, fu: 400, em: 1200, tx: "svc", ov: { d1: 800, d4: 1200, d5: 900 }, pkg: false, act: true },
      { id: "TEL", en: "Teleconsultation", ar: "استشارة عن بعد", cat: "Consultation", base: 500, fu: 300, em: null, tx: "svc", ov: {}, pkg: false, act: true },
      { id: "DRS", en: "Wound dressing", ar: "تضميد جرح", cat: "Procedure", base: 250, fu: null, em: 400, tx: "svc", ov: {}, pkg: false, act: true },
      { id: "INJ", en: "Injection (administration)", ar: "حقنة", cat: "Service", base: 150, fu: null, em: 250, tx: "svc", ov: {}, pkg: false, act: true },
      { id: "NEB", en: "Nebulisation", ar: "جلسة بخار", cat: "Service", base: 200, fu: null, em: 300, tx: "svc", ov: {}, pkg: true, act: true },
      { id: "ECG", en: "ECG (12-lead)", ar: "تخطيط القلب", cat: "Service", base: 300, fu: null, em: 450, tx: "svc", ov: {}, pkg: false, act: true },
      { id: "PEEL", en: "Chemical peel", ar: "تقشير كيميائي", cat: "Procedure", base: 2500, fu: null, em: null, tx: "cos", ov: {}, pkg: true, act: true },
      { id: "LHR", en: "Laser hair reduction · session", ar: "إزالة الشعر بالليزر", cat: "Procedure", base: 3500, fu: null, em: null, tx: "cos", ov: {}, pkg: true, act: true },
      { id: "SCL", en: "Scaling & polishing", ar: "تنظيف الأسنان", cat: "Procedure", base: 1200, fu: null, em: null, tx: "svc", ov: {}, pkg: true, act: true },
      { id: "VAC", en: "Vaccination (administration)", ar: "إعطاء لقاح", cat: "Service", base: 250, fu: null, em: null, tx: "svc", ov: {}, pkg: true, act: true },
      { id: "CERT", en: "Medical / sick-leave certificate", ar: "شهادة طبية", cat: "Certificate", base: 300, fu: null, em: null, tx: "cert", ov: {}, pkg: false, act: true },
      { id: "FIT", en: "Fitness certificate", ar: "شهادة لياقة", cat: "Certificate", base: 500, fu: null, em: null, tx: "cert", ov: {}, pkg: false, act: false },
      { id: "KIT", en: "Dressing kit", ar: "عدة تضميد", cat: "Consumable", base: 120, fu: null, em: null, tx: "cons", ov: {}, pkg: false, act: true },
      { id: "SPF", en: "Sunscreen SPF 50 · 50 g", ar: "واقي شمس", cat: "Consumable", base: 650, fu: null, em: null, tx: "cos", ov: {}, pkg: false, act: true }
    ],
    starter: {
      derm: [["Consultation", "Consultation", 800, "svc"], ["Follow-up consult", "Consultation", 500, "svc"], ["Chemical peel", "Procedure", 2500, "cos"], ["Laser hair reduction · session", "Procedure", 3500, "cos"], ["PRP (hair)", "Procedure", 6000, "cos"], ["Acne extraction", "Procedure", 1500, "svc"], ["Cryotherapy (wart)", "Procedure", 1200, "svc"], ["Patch test", "Service", 1800, "svc"], ["Medical certificate", "Certificate", 300, "cert"], ["Sunscreen SPF 50", "Consumable", 650, "cos"]],
      dental: [["Consultation", "Consultation", 500, "svc"], ["Follow-up consult", "Consultation", 300, "svc"], ["Scaling & polishing", "Procedure", 1200, "svc"], ["Composite filling", "Procedure", 1500, "svc"], ["Extraction (simple)", "Procedure", 1000, "svc"], ["IOPA X-ray", "Service", 250, "svc"], ["Root canal (molar)", "Procedure", 6500, "svc"], ["Crown (PFM)", "Procedure", 7000, "svc"], ["Teeth whitening", "Procedure", 8000, "cos"], ["Mouthwash 250 ml", "Consumable", 220, "cons"]],
      gp: [["Consultation", "Consultation", 500, "svc"], ["Follow-up consult", "Consultation", 300, "svc"], ["Injection (administration)", "Service", 150, "svc"], ["Wound dressing", "Procedure", 250, "svc"], ["Nebulisation", "Service", 200, "svc"], ["ECG (12-lead)", "Service", 300, "svc"], ["BP & sugar check", "Service", 100, "svc"], ["Medical certificate", "Certificate", 300, "cert"], ["Dressing kit", "Consumable", 120, "cons"]],
      paed: [["Consultation", "Consultation", 600, "svc"], ["Follow-up consult", "Consultation", 350, "svc"], ["Vaccination (administration)", "Service", 250, "svc"], ["Nebulisation", "Service", 200, "svc"], ["Growth & development check", "Service", 400, "svc"], ["Newborn visit", "Consultation", 700, "svc"], ["Ear wax removal", "Procedure", 400, "svc"], ["Medical certificate", "Certificate", 300, "cert"]]
    },
    log: [
      { t: "Today 09:12", who: "owner", item: "Consultation · Dr. Khalid Al-Saeed (override)", f: "Normal", o: 1100, n: 1200, eff: "From the next bill" },
      { t: "Yesterday 18:40", who: "owner", item: "Follow-up window (clinic-wide)", f: "Days", o: "14 days", n: "7 days", eff: "From the next bill" },
      { t: "15 Oct 11:02", who: "owner", item: "Laser hair reduction · session", f: "Normal", o: 3200, n: 3500, eff: "From 16 Oct 00:00" },
      { t: "14 Oct 16:25", who: "Pooja Nair", item: "Wound dressing", f: "Emergency", o: null, n: 400, eff: "From the next bill · owner PIN" },
      { t: "12 Oct 10:05", who: "owner", item: "Fitness certificate", f: "Active", o: "Active", n: "Inactive", eff: "Hidden from billing buttons" },
      { t: "02 Oct 15:40", who: "Setup wizard", item: "Dermatology starter list", f: "Loaded", o: "—", n: "10 charge heads", eff: "Placeholder amounts" }
    ],
    // Report library: code = already served by the backend /reports API.
    reports: [
      { id: "leak", grp: "Money", ic: "alertcircle", en: "Billing leakage", ar: "تسرب الفوترة", hi: "बिलिंग लीकेज", sub: "Done but not billed", api: "GET /reports/billing-leakage" },
      { id: "money", grp: "Money", ic: "wallet", en: "Daily money", ar: "المال اليومي", hi: "दैनिक पैसा", sub: "Collections by payment type", api: "GET /reports/daily-money" },
      { id: "pkg", grp: "Money", ic: "layers", en: "Package liability", ar: "التزامات الباقات", hi: "पैकेज देनदारी", sub: "Sessions still owed to patients" },
      { id: "tax", grp: "Money", ic: "percent", en: "Tax return summary", ar: "ملخص الإقرار الضريبي", hi: "टैक्स रिटर्न सारांश", sub: "GST / VAT filing figures" },
      { id: "src", grp: "Patients", ic: "share", en: "Where patients come from", ar: "مصادر المرضى", hi: "मरीज़ कहाँ से आते हैं", sub: "Bookings by channel", api: "GET /reports/sources" },
      { id: "ret", grp: "Patients", ic: "refresh", en: "Retention & repeat visits", ar: "الاحتفاظ والزيارات المتكررة", hi: "रिटेंशन और दोबारा विज़िट", sub: "Aggregate only", api: "GET /reports/retention" },
      { id: "nsr", grp: "Patients", ic: "alert", en: "No-show risk", ar: "خطر عدم الحضور", hi: "नो-शो जोखिम", sub: "Booked patients with past no-shows", api: "GET /reports/no-show-risk" },
      { id: "staff", grp: "Team", ic: "users", en: "Staff performance", ar: "أداء الموظفين", hi: "स्टाफ़ प्रदर्शन", sub: "Desk, billing and nursing", api: "GET /reports/staff-performance" },
      { id: "prod", grp: "Team", ic: "steth", en: "Doctor productivity", ar: "إنتاجية الأطباء", hi: "डॉक्टर उत्पादकता", sub: "Patients, minutes, revenue per hour" },
      { id: "punct", grp: "Team", ic: "clockh", en: "Doctor punctuality", ar: "التزام الأطباء بالمواعيد", hi: "डॉक्टर समय-पालन", sub: "Late starts and overruns", api: "GET /reports/doctor-punctuality" }
    ],
    recalls: [
      { r: "Dental scaling · every 6 months", d: "d3", kind: "utility", due: 64, sent: 52, booked: 21, st: "on" },
      { r: "Acne review · 4 weeks after starting treatment", d: "d1", kind: "utility", due: 38, sent: 35, booked: 19, st: "on" },
      { r: "HbA1c & sugar review · every 3 months", d: "d2", kind: "utility", due: 41, sent: 39, booked: 14, st: "on" },
      { r: "Child vaccine due (national schedule)", d: "d3", kind: "utility", due: 27, sent: 27, booked: 22, st: "on" },
      { r: "BP review · monthly", d: "d2", kind: "utility", due: 33, sent: 30, booked: 9, st: "on" },
      { r: "Not seen in 12 months · win-back", d: null, kind: "marketing", due: 210, sent: 0, booked: 0, st: "paused" }
    ],
    reviews: [
      { n: "Ananya R.", s: 5, t: "Got the leave-home-now message and walked straight in. Six minutes in the waiting room.", w: "Today", rep: false },
      { n: "Imran K.", s: 2, t: "Billing desk was slow and I was charged twice for the nebulisation, refunded later.", w: "Yesterday", rep: false },
      { n: "Mohammed A.", s: 5, t: "My son booked for me from Dubai. Staff were kind and explained everything.", w: "14 Oct", rep: true },
      { n: "Priya S.", s: 4, t: "Doctor was late but they told us early, so I waited at home.", w: "12 Oct", rep: true }
    ],
    referrers: [
      { n: "Kavya Reddy", p: "p1", ref: 6, vis: 5, rw: 5, st: "ok" },
      { n: "Rakesh Menon", p: "p3", ref: 4, vis: 3, rw: 3, st: "ok" },
      { n: "Fatima Sheikh", p: "p6", ref: 3, vis: 1, rw: 1, st: "wait" },
      { n: "Suresh Pillai", p: "p5", ref: 2, vis: 2, rw: 1, st: "cap" }
    ],
    complaints: [
      { id: "GRV-0412", p: "p9", cat: "Waiting time", ch: "WhatsApp", recv: "Today 09:20", sla: "Acknowledge in 3 h", st: "new", lvl: 1, who: "Front desk lead", txt: "Waited 50 minutes after my booked slot and nobody told me the doctor was running late." },
      { id: "GRV-0409", p: "p8", cat: "Billing", ch: "Patient app (son, caregiver)", recv: "15 Oct", sla: "Resolve by 22 Oct", st: "prog", lvl: 2, who: "Clinic manager", txt: "Charged a full consultation for a follow-up five days after the first visit with the same doctor." },
      { id: "GRV-0405", p: "p6", cat: "Privacy", ch: "Email", recv: "12 Oct", sla: "Clinic target 7 days", st: "esc", lvl: 3, who: "Grievance officer", txt: "I received a lab report link on WhatsApp that belonged to another patient." },
      { id: "GRV-0398", p: "p3", cat: "Staff behaviour", ch: "Phone", recv: "08 Oct", sla: "Resolved in 2 days", st: "done", lvl: 1, who: "Front desk lead", txt: "Receptionist was rude when I asked about my report." },
      { id: "GRV-0391", p: "p1", cat: "Cleanliness", ch: "Feedback form", recv: "05 Oct", sla: "Resolved in 1 day", st: "done", lvl: 1, who: "Operations", txt: "Washroom on the first floor had no soap." }
    ],
    templates: [
      ["appt_reminder_24h", "Utility", "EN · HI · AR", "ok", "High"], ["queue_leave_now", "Utility", "EN · HI · AR", "ok", "High"], ["rx_ready_secure_link", "Utility", "EN · HI · AR", "ok", "High"],
      ["recall_due_booking", "Utility", "EN · HI · AR", "ok", "Medium"], ["review_request", "Marketing", "EN · HI · AR", "warn", "Medium"], ["winback_12m", "Marketing", "EN · AR", "crit", "Low"], ["login_otp", "Authentication", "EN · HI · AR", "ok", "High"]
    ],
    // Doctor revenue share. model: sal | pct | fixed | mix. rates on consult fees (c) and procedures (p).
    share: [
      { d: "d2", type: "Employed", model: "sal", desc: "Salary through payroll · no fee share", c: 0, p: 0, tds: false },
      { d: "d1", type: "Partner", model: "pct", desc: "60% of consult fees · 50% of procedures", c: 0.6, p: 0.5, tds: true },
      { d: "d3", type: "Employed + incentive", model: "pct", desc: "10% of consult fees above 300 visits a month", c: 0.1, p: 0, tds: false },
      { d: "d4", type: "Visiting consultant", model: "mix", desc: "70% of consult fees · " + "fixed per echo", c: 0.7, p: 0, per: 2500, tds: true },
      { d: "d5", type: "Visiting consultant", model: "fixed", desc: "Fixed per OPD session (3 a week)", c: 0, p: 0, sess: 6000, tds: true }
    ],
    stm: { d1: [182, 425000, 41, 214000], d3: [348, 211000, 6, 9000], d4: [96, 115200, 22, 0], d5: [6, 0, 0, 0], d2: [402, 241000, 18, 22000] },
    staff: [
      { n: "Sneha Kulkarni", r: "Receptionist", sh: ["09–14", "17–21"], m: "Desk PIN", in: "08:56", st: "ok", h: 8.5, d: 24, ot: 6, lv: 1, sal: 28000, adv: 0 },
      { n: "Anita D'Souza", r: "OPD nurse", sh: ["08–16"], m: "App (geo-fenced)", in: "07:58", st: "ok", h: 8, d: 25, ot: 2, lv: 0, sal: 34000, adv: 2000 },
      { n: "Pooja Nair", r: "Billing", sh: ["10–19"], m: "App (geo-fenced)", in: "10:14", st: "late", h: 7.6, d: 23, ot: 0, lv: 2, sal: 30000, adv: 0 },
      { n: "Rahul Joshi", r: "Pharmacist", sh: ["09–13", "16–20"], m: "Desk PIN", in: "—", st: "leave", h: 0, d: 22, ot: 0, lv: 3, sal: 36000, adv: 0 },
      { n: "Kiran Rao", r: "Lab technician", sh: ["07–15"], m: "App (geo-fenced)", in: "06:52", st: "ok", h: 8.2, d: 25, ot: 4, lv: 0, sal: 32000, adv: 0 },
      { n: "Lakshmamma", r: "Housekeeping", sh: ["07–15"], m: "Desk PIN", in: "—", st: "absent", h: 0, d: 21, ot: 0, lv: 1, sal: 16000, adv: 1500 }
    ],
    leave: [
      { n: "Rahul Joshi", t: "Casual leave", from: "17 Oct", to: "19 Oct", days: 3, st: "ok", note: "Cover: pharmacy relief (agency)" },
      { n: "Dr. Nisha Bhat", t: "Conference leave", from: "28 Oct", to: "30 Oct", days: 3, st: "wait", note: "38 bookings affected · patients will be offered Dr. Vikram Shah or a new date" },
      { n: "Pooja Nair", t: "Half day", from: "21 Oct", to: "21 Oct", days: 0.5, st: "wait", note: "Refund approval delegated to Deepa Menon" },
      { n: "Anita D'Souza", t: "Sick leave", from: "09 Oct", to: "09 Oct", days: 1, st: "ok", note: "Certificate uploaded" }
    ]
  };
  function head(id) { return G.heads.find(function (h) { return h.id === id; }) || G.heads[0]; }
  function hname(h) { return NB.rtl() && h.ar ? h.ar : h.en; }

  /* ---------- Market-specific licence register (facility + people) ---------- */
  function licences() {
    var fs = facs();
    return pick({
      india: [
        { w: fs[0].name, k: "Clinical establishment registration (KPME)", no: "KPME/BLR/2019/04417", by: "Karnataka health dept.", exp: "2026-11-07", d: 21, kind: "fac" },
        { w: dn("d4"), k: "Medical registration", no: "KMC-22918", by: "Karnataka Medical Council", exp: "2026-11-30", d: 44, kind: "doc", doc: "d4" },
        { w: fs[0].name, k: "Bio-medical waste authorisation", no: "KSPCB/BMW/88120", by: "KSPCB", exp: "2027-03-31", d: 165, kind: "fac" },
        { w: fs[0].name, k: "Fire safety NOC", no: "KSFES/NOC/2024/1182", by: "Karnataka Fire & Emergency", exp: "2026-10-24", d: 7, kind: "fac" },
        { w: "Rahul Joshi", k: "Pharmacist registration", no: "KSPC-31877", by: "Karnataka State Pharmacy Council", exp: "2027-12-31", d: 440, kind: "staff" },
        { w: dn("d1"), k: "Medical registration", no: "KMC-48211", by: "Karnataka Medical Council", exp: "2028-06-30", d: 622, kind: "doc", doc: "d1" }
      ],
      uae: [
        { w: fs[0].name, k: "Health facility licence", no: "DHA-F-0021774", by: "DHA", exp: "2026-11-07", d: 21, kind: "fac" },
        { w: dn("d4"), k: "Professional licence (Sheryan)", no: "DHA-P-22918", by: "DHA", exp: "2026-10-30", d: 13, kind: "doc", doc: "d4" },
        { w: fs[0].name, k: "Trade licence", no: "DED-1129044", by: "Dubai Economy & Tourism", exp: "2027-02-14", d: 120, kind: "fac" },
        { w: fs[0].name, k: "Civil defence certificate", no: "DCD-55120", by: "Dubai Civil Defence", exp: "2026-10-24", d: 7, kind: "fac" },
        { w: dn("d5"), k: "Professional licence (Sheryan)", no: "DHA-P-77120", by: "DHA", exp: "2027-09-30", d: 348, kind: "doc", doc: "d5" }
      ],
      ksa: [
        { w: fs[0].name, k: "MOH private facility licence", no: "MOH-RYD-1440-2271", by: "Ministry of Health", exp: "2026-11-07", d: 21, kind: "fac" },
        { w: dn("d4"), k: "SCFHS professional registration", no: "SCFHS-22918", by: "SCFHS", exp: "2026-11-30", d: 44, kind: "doc", doc: "d4" },
        { w: fs[0].name, k: "Commercial registration (CR)", no: "1010554120", by: "Ministry of Commerce", exp: "2027-01-12", d: 87, kind: "fac" },
        { w: fs[0].name, k: "Civil defence permit (Salamah)", no: "SLM-881204", by: "Civil Defence", exp: "2026-10-24", d: 7, kind: "fac" },
        { w: fs[0].name, k: "Municipality licence (Balady)", no: "BLD-40012988", by: "Balady", exp: "2027-05-03", d: 198, kind: "fac" }
      ],
      qatar: [
        { w: fs[0].name, k: "MOPH facility licence", no: "MOPH-HF-1192", by: "MOPH", exp: "2026-11-07", d: 21, kind: "fac" },
        { w: dn("d4"), k: "QCHP practitioner licence", no: "QCHP-22918", by: "QCHP", exp: "2026-11-30", d: 44, kind: "doc", doc: "d4" },
        { w: fs[0].name, k: "Commercial registration", no: "CR-118204", by: "MOCI", exp: "2027-03-01", d: 135, kind: "fac" },
        { w: fs[0].name, k: "Civil defence certificate", no: "QCD-77120", by: "Civil Defence", exp: "2026-10-24", d: 7, kind: "fac" }
      ]
    });
  }
  function daysPill(d) { return d <= 7 ? ui.pill("crit", d + " days left", "alertfill") : d <= 30 ? ui.pill("warn", d + " days left", "clockh") : d <= 60 ? ui.pill("info", d + " days left", "clockh") : ui.pill("ok", "Valid", "checkcircle"); }
  function remPill(d) { var sent = [60, 30, 7].filter(function (x) { return d <= x; }); return '<span class="small muted gr-nw">' + (sent.length ? "Sent at " + sent.join(" · ") + " days" : "Next at 60 days") + "</span>"; }

  /* ================= owner.setup ================= */
  function steps() {
    var taxSt = mid() === "ksa" ? "warn" : "done";
    return [
      { id: "profile", ic: "building", en: "Clinic profile & licences", ar: "ملف العيادة والتراخيص", hi: "क्लिनिक प्रोफ़ाइल और लाइसेंस", st: "done", sub: "Name, address, facility licence" },
      { id: "preset", ic: "sliders", en: "Clinic type & specialty", ar: "نوع العيادة والتخصص", hi: "क्लिनिक प्रकार और विशेषज्ञता", st: "done", sub: "Turns the right modules on" },
      { id: "doctors", ic: "steth", en: "Doctors & timings", ar: "الأطباء والمواعيد", hi: "डॉक्टर और समय", st: "done", sub: "Weekly templates, split shifts" },
      { id: "prices", ic: "banknote", en: "Price list", ar: "قائمة الأسعار", hi: "मूल्य सूची", st: "done", sub: "From a specialty starter list" },
      { id: "wa", ic: "chat", en: "WhatsApp number", ar: "رقم واتساب", hi: "व्हाट्सऐप नंबर", st: "done", sub: "Own number or shared Nabd number" },
      { id: "tax", ic: "percent", en: "Tax registration", ar: "التسجيل الضريبي", hi: "टैक्स पंजीकरण", st: taxSt, sub: pick({ india: "GSTIN (only if registered)", uae: "VAT TRN", ksa: "VAT TRN · ZATCA onboarding", qatar: "Nothing to register" }) },
      { id: "dpa", ic: "scroll", en: "Data Processing Agreement", ar: "اتفاقية معالجة البيانات", hi: "डेटा प्रोसेसिंग अनुबंध", st: "done", sub: "Signed before real patient data" },
      { id: "import", ic: "upload", en: "Import patients", ar: "استيراد المرضى", hi: "मरीज़ इम्पोर्ट", st: "cur", sub: "Excel, CSV or old software" },
      { id: "golive", ic: "play", en: "Go-live check", ar: "فحص الإطلاق", hi: "गो-लाइव जाँच", st: "todo", sub: "Blocks only on true blockers" }
    ];
  }
  var PRESETS = [
    { id: "solo", ic: "user", en: "Solo doctor", ar: "طبيب منفرد", hi: "एकल डॉक्टर", sub: "One doctor, no front desk, often from a phone",
      on: ["Bookings auto-accepted within your rules", "Self check-in by QR or a WhatsApp “I've arrived”", "Consult, Rx and payment on one screen", "Phone-first layout"], off: "Departments, front-desk roles, IPD" },
    { id: "single", ic: "steth", en: "Single-specialty clinic", ar: "عيادة تخصص واحد", hi: "एकल-विशेषज्ञता क्लिनिक", sub: "2–5 doctors, one receptionist",
      on: ["Front-desk queue with per-doctor ETAs", "Specialty workspace (e.g. skin photos, dental chart)", "Packages and session tracking", "Specialty recall rules"], off: "Departments, IPD, insurance desk" },
    { id: "multi", ic: "grid", en: "Multi-specialty / polyclinic", ar: "متعدد التخصصات", hi: "बहु-विशेषज्ञता / पॉलीक्लिनिक", sub: "Departments, several queues",
      on: ["Departments and cross-department access policy", "Several queues and a waiting-room TV", "Hybrid pharmacy and in-house lab (optional modules)", "Insurance desk and claim-pack export"], off: "IPD, OT, ER" },
    { id: "hospital", ic: "bed", en: "Hospital", ar: "مستشفى", hi: "अस्पताल", sub: "OPD + IPD, wards, OT, ER",
      on: ["Beds, admissions, ward rounds, discharge", "ER, OT schedule and radiology", "Pre-authorisation and claims", "Duty roster and multi-facility dashboard"], off: "White-glove setup included" }
  ];
  function presetDef() { return { solo: "solo", clinic: "single", poly: "multi", hospital: "hospital" }[NB.state.facType] || "multi"; }
  var SPECS = [["derm", "Dermatology"], ["dental", "Dental"], ["gp", "General physician"], ["paed", "Paediatrics"]];

  function stepBody(i, c) {
    var m = c.m, s = steps()[i], fs = facs(), h = "";
    if (s.id === "profile") {
      var L0 = licences()[0];
      h += '<div class="fgrid">' + ui.field("Clinic name", fs[0].name, { id: "gr-cn" }) + ui.field("Area and city", fs[0].area, { id: "gr-ca" }) +
        ui.field("Clinic phone", waNum(0), { id: "gr-cp", type: "tel" }) + ui.field("Email for invoices and alerts", gcc() ? "admin@alnoorhealth." + pick({ uae: "ae", ksa: "sa", qatar: "qa" }) : "admin@suvidhahealth.in", { id: "gr-ce" }) +
        ui.field(L0.k, L0.no, { id: "gr-cl" }) + ui.field("Licence expiry", gd(L0.exp), { id: "gr-cx" }) + "</div>";
      h += ui.note("info", "Licence reminders go to the owner on WhatsApp and email at <b>60, 30 and 7 days</b> before expiry. Doctors' registrations are collected in step 3. See the full register under <b>Compliance health</b>.");
      h += '<div class="lst">' + licences().slice(0, 3).map(function (x) { return ui.li(ii(x.kind === "doc" ? "steth" : "scroll"), esc(x.k), esc(x.w) + ' · <span class="ltr">' + esc(x.no) + "</span>", daysPill(x.d)); }).join("") + "</div>";
    } else if (s.id === "preset") {
      var cur = NB.v("grPreset", presetDef());
      h += '<div class="gr-presets" role="radiogroup">' + PRESETS.map(function (p) {
        var on = cur === p.id;
        return '<div class="gr-preset' + (on ? " on" : "") + '" data-set="grPreset" data-val="' + p.id + '" role="radio" aria-checked="' + on + '" tabindex="0"><div class="hd">' + ii(p.ic, on ? "" : "") +
          '<div class="fl"><b>' + esc(L(p.en, p.ar, p.hi)) + "</b><small>" + esc(p.sub) + "</small></div>" + (on ? ui.pill("acc", "Selected", "check") : "") + '</div><div class="gr-on">' +
          p.on.map(function (x) { return "<span>" + ui.icon("check", "okc") + esc(x) + "</span>"; }).join("") + "<span>" + ui.icon("dot", "faint") + '<span class="faint">' + esc((p.id === "hospital" ? "" : "Off: ") + p.off) + "</span></span></div></div>";
      }).join("") + "</div>";
      h += '<div class="stack s8"><b class="small">Main specialty · loads a starter price list and recall rules</b>' + ui.seg("grSpec", SPECS.map(function (x) { return [x[0], x[1]]; }), "derm") + "</div>";
      h += ui.note("info", "Everything a preset turns on is an ordinary setting you can change later. Changing preset never deletes data.");
    } else if (s.id === "doctors") {
      var docs = ["d1", "d2", "d3", "d4", "d5"].map(NB.doc);
      var pray = gcc();
      h += ui.table([{ h: "Doctor" }, { h: "Weekly template" }, { h: "Slot" }, { h: "Registration" }, { h: pray ? "Prayer breaks" : "Breaks" }],
        docs.map(function (d, i) {
          var tmpl = [["Mon–Sat", "10:00–14:00 · 17:00–20:30"], ["Mon–Sat", "09:00–13:00 · 17:00–21:00"], ["Mon–Fri", "09:30–13:30"], ["Tue, Thu", "11:00–15:00 (visiting)"], ["Mon, Wed, Sat", "16:00–20:00 (visiting)"]][i];
          return [ui.cellp(esc(NB.dname(d)), esc(d.spec)), ui.cellp('<span class="ltr">' + esc(tmpl[1]) + "</span>", esc(tmpl[0])), num(d.avgMin + 3 + " min"), '<span class="gr-mono">' + esc(d.regNo) + "</span>",
            pray ? ui.pill("ok", "Auto from prayer times", "check") : ui.pill("mute", "Lunch 13:30–14:00")];
        }), { min: 760 });
      h += ui.note("info", "Split shifts are normal: one template can hold a morning and an evening session. Date exceptions (leave, conferences) are added from each doctor's phone and patients are told automatically.");
    } else if (s.id === "prices") {
      var sp = NB.v("grSpec", "derm"), list = G.starter[sp];
      h += '<div class="row sb"><span class="small muted">Starter list for <b>' + esc(SPECS.find(function (x) { return x[0] === sp; })[1]) + "</b> · " + list.length + " charge heads · country-correct tax defaults</span>" + ui.seg("grSpec", SPECS.map(function (x) { return [x[0], x[1]]; }), "derm") + "</div>";
      h += ui.table([{ h: "Charge head" }, { h: "Category" }, { h: "Placeholder", cls: "r" }, { h: "Tax default" }],
        list.map(function (r) { return [esc(r[0]), esc(r[1]), M(r[2]), taxPill(r[3])]; }), { min: 600 });
      h += ui.note("warn", "Amounts are <b>placeholders</b>. Edit them before go-live. Follow-up, emergency and per-doctor prices are set in the price master.");
      h += '<div class="btns">' + ui.btn("Open price master", { cls: "sm", icon: "banknote", go: "owner.prices" }) + "</div>";
    } else if (s.id === "wa") {
      var wm = NB.v("grWaMode", "own");
      h += '<div class="gr-cmp">' + ui.radio("grWaMode", "own", "Clinic's own WhatsApp number", "Your name shows in chat. Meta business verification takes 2–5 days; we do it for you on white-glove.", "own") +
        ui.radio("grWaMode", "shared", "Shared Nabd number (start today)", "Messages read “" + clinicName() + " via Nabd”. Move to your own number any time; patients are told once.", "own") + "</div>";
      h += '<div class="lst">' + (wm === "own" ? [
        ["Meta business verification", "Approved 09 Oct", "ok"], ["Display name “" + clinicName() + "”", "Approved", "ok"], ["Number " + waNum(0), "Connected via embedded sign-up", "ok"],
        ["Message templates", "9 of 9 approved in " + (gcc() ? "English and Arabic" : "English, Hindi and Kannada"), "ok"], ["Quality rating", "High · limit 10,000 patients a day", "ok"]
      ] : [["Shared number " + pick({ india: "+91 80 6900 1100", uae: "+971 4 600 1100", ksa: "+966 11 600 1100", qatar: "+974 4400 1100" }), "Live now", "ok"], ["Sender line", "“" + clinicName() + " via Nabd”", "ok"], ["Move to own number", "Not started", "info"]]).map(function (x) {
        return ui.li(ii(x[2] === "ok" ? "checkcircle" : "info", x[2] === "ok" ? "ok" : "info"), esc(x[0]), esc(x[1]), x[2] === "ok" ? ui.pill("ok", "Done") : ui.pill("info", "Optional"));
      }).join("") + "</div>";
      h += ui.note("info", "Going live needs a working WhatsApp sender. Either option works; the clinic's own number builds the brand and keeps its own quality rating.");
    } else if (s.id === "tax") {
      h += pick({
        india: ui.toggle("grGstReg", "This clinic is registered for GST", "Most consultations are GST-exempt. Register only if taxable sales (cosmetic services, products) cross the threshold.", true) +
          (NB.v("grGstReg", true) ? '<div class="fgrid">' + ui.field("GSTIN", "29AAKCS4417M1Z6", { id: "gr-gst" }) + ui.field("Legal name", "Suvidha Health Pvt Ltd", { id: "gr-gln" }) + ui.field("Place of supply", "29 · Karnataka", { id: "gr-pos" }) + ui.field("Invoice series", "KOR/26-27/0001", { id: "gr-ser" }) + "</div>" +
            ui.note("ok", "GSTIN format checked. Consults default to <b>Exempt · SAC 9993</b>; cosmetic and aesthetic services to <b>18%</b> via the guided picker. No IRP e-invoicing needed below the ₹5 crore threshold.") :
            ui.note("info", "Receipts will say “Not registered under GST”. You can add a GSTIN later; past receipts are not changed.")),
        uae: '<div class="fgrid">' + ui.field("VAT TRN (15 digits)", "100234567800003", { id: "gr-trn" }) + ui.field("Legal name", "Al Noor Medical Services LLC", { id: "gr-ln" }) + "</div>" +
          ui.note("ok", "TRN verified. Basic and preventive healthcare is <b>zero-rated</b>; cosmetic services and retail products carry <b>5% VAT</b>. UAE e-invoicing (2027) will switch on from this screen."),
        ksa: '<div class="fgrid">' + ui.field("VAT TRN (15 digits, starts and ends with 3)", "310245678900003", { id: "gr-trn" }) + ui.field("Legal name (Arabic)", "مجمع النور الطبي", { id: "gr-ln" }) + ui.field("CR number", "1010554120", { id: "gr-cr" }) + ui.field("ZATCA wave", "Wave 25 · integrate by 1 Feb 2027", { id: "gr-wave" }) + "</div>" +
          ui.note("warn", "<b>ZATCA Fatoora phase 2 onboarding pending.</b> Simplified invoices already carry the phase-1 QR on every sale. Generate the OTP in the Fatoora portal and paste it here to get the production CSID before your wave date.") +
          ui.note("info", "VAT follows the patient's nationality from National ID / Iqama: <b>Saudi nationals</b> — VAT on private healthcare is borne by the state (shown as 0% with the required wording); <b>non-Saudi</b> — 15%."),
        qatar: ui.note("ok", "Qatar has no VAT today, so there is nothing to register. Receipts show your commercial registration number. If VAT is introduced, this step reopens.")
      });
    } else if (s.id === "dpa") {
      h += '<div class="gr-cert"><div class="row sb"><h3>' + ui.icon("shieldcheck", "okc") + " Data Processing Agreement · signed</h3>" + ui.pill("ok", "Version DPA-" + mk().flag + "-1.3") + "</div>" +
        kv([["Signed by", esc(owner()) + " · owner"], ["How", gcc() ? "OTP to " + esc(mk().phone) + " number + Nafath / UAE Pass" : "OTP + Aadhaar eSign"], ["When", "14 Oct 2026, 10:42 " + esc(mk().tz)], ["Your role", gcc() ? "Controller" : "Data fiduciary (controller)"], ["Nabd's role", "Processor"], ["Data region", esc(mk().residency)]]) + "</div>";
      h += '<div class="stack s8"><b class="small">Sub-processors (published list)</b><div class="lst">' + [
        ["Cloud hosting", mk().residency, "cloudsync"], ["WhatsApp Business Platform", "Meta · messages only, no records", "chat"], [gcc() ? "SMS fallback" : "SMS fallback (DLT-registered)", gcc() ? "Local SMS provider" : "DLT-registered SMS provider", "phone"], ["Payments", pick({ india: "Razorpay (UPI, cards)", uae: "Network International", ksa: "HyperPay (mada, Apple Pay, STC Pay)", qatar: "QPay / cards" }), "card"]
      ].map(function (x) { return ui.li(ii(x[2]), esc(x[0]), esc(x[1]), ""); }).join("") + "</div></div>";
      h += ui.note("info", "Until this is signed the account runs on the <b>demo clinic</b> with synthetic patients: importing or booking real patients is blocked. Export and deletion terms are in clause 9.");
      h += '<div class="btns">' + ui.btn("Download signed PDF", { cls: "sm", icon: "download", act: "toast", arg: "DPA-" + mk().flag + "-1.3 signed copy downloaded" }) + "</div>";
    } else if (s.id === "import") {
      var src = NB.v("grImpSrc", "excel");
      h += ui.note("ok", "Unlocked because the Data Processing Agreement was signed on 14 Oct.");
      h += ui.seg("grImpSrc", [["excel", "Excel / CSV"], ["soft", "Old clinic software"], ["paper", "Paper records · quick entry"]], "excel");
      if (src === "paper") {
        h += '<div class="fgrid">' + ui.field("Patient name", "", { ph: "As on the paper card", id: "gr-pn" }) + ui.field("Mobile", mk().phone + " ", { id: "gr-pm", type: "tel" }) + ui.field("Year of birth", "", { ph: "e.g. 1987", id: "gr-py" }) + ui.field("Last visit and doctor", "", { ph: "e.g. 12 Aug · Dr. Shah", id: "gr-pl" }) + "</div>" +
          ui.note("info", "Quick-entry mode: Tab moves to the next field, Enter saves and starts the next card. Duplicate check by mobile runs as you type.");
      } else {
        h += '<div class="gr-mini">' + [["1,184", "rows in “patients_2026.xlsx”"], ["1,162", "ready to import"], ["14", "possible duplicates · merge or skip"], ["8", "errors · mobile missing"]].map(function (x, j) {
          return '<div class="card"><b class="' + (j === 2 ? "warnc" : j === 3 ? "critc" : "") + '">' + x[0] + "</b><span>" + esc(x[1]) + "</span></div>";
        }).join("") + "</div>";
        h += ui.table([{ h: "Column in your file" }, { h: "Nabd field" }, { h: "Sample" }, { h: "Check" }], [
          ["Name", "Full name", "Kavya Reddy", ui.pill("ok", "Mapped")], ["Mobile No", "Mobile (WhatsApp)", '<span class="ltr">98450 11223</span>', ui.pill("ok", "Mapped")], ["DOB", "Date of birth", "14/03/1992", ui.pill("ok", "dd/mm/yyyy")],
          ["Sex", "Sex", "F", ui.pill("ok", "Mapped")], [src === "soft" ? "PatientID" : "File No", "Old record number", "OP-2231", ui.pill("ok", "Kept for reference")], ["Allergy", "Allergies (free text)", "Penicillin", ui.pill("warn", "Doctor to confirm", "alertcircle")],
          ["Remarks", "—", "Paid 500 adv", ui.pill("mute", "Not imported", "dot")]
        ], { min: 640 });
        h += '<div class="btns">' + ui.btn("Import 1,162 patients", { cls: "pri", icon: "upload", act: "toast", arg: "Importing 1,162 patients · about 3 minutes · you can keep working" }) + ui.btn("Review 14 duplicates", { icon: "users", act: "toast", arg: "Duplicates are matched on mobile + date of birth · nothing is merged without your tap" }) + ui.btn("Download error report", { cls: "ghost", icon: "download", act: "toast", arg: "8 rows with errors downloaded as CSV" }) + "</div>";
        if (src === "soft") h += ui.note("info", pick({ india: "Supported exports: Practo Ray, Clinicea, Lybrate Pro and any CSV. Visits and bills import as read-only history.", uae: "Supported exports: Clinicea, Medas and any CSV. Visits and bills import as read-only history.", ksa: "Supported exports: Clinicea, Medas and any CSV. Arabic names are kept as entered. Visits and bills import as read-only history.", qatar: "Supported exports: Clinicea, Medas and any CSV. Visits and bills import as read-only history." }));
      }
    } else if (s.id === "golive") {
      var blk = [["At least one doctor with a schedule", true], ["Price list saved", true], ["WhatsApp sender working", true]];
      var nice = [["Patients imported", false, "Can import after go-live"], ["Staff invited (4 of 6)", false, "Invites expire in 72 h"], ["Receipt printer test", true, ""], ["Waiting-room TV link opened", false, "Optional"], [pick({ india: "GSTIN added", uae: "VAT TRN added", ksa: "ZATCA phase-2 CSID", qatar: "Commercial registration on receipts" }), mid() !== "ksa", mid() === "ksa" ? "Phase-1 QR invoices work meanwhile" : ""]];
      h += '<div class="stack s8"><b class="small">Blockers · go-live waits only for these</b><div class="lst">' + blk.map(function (x) { return ui.li(ii("checkcircle", "ok"), esc(x[0]), "", ui.pill("ok", "Ready")); }).join("") + "</div></div>";
      h += '<div class="stack s8"><b class="small">Good to have · never blocks</b><div class="lst">' + nice.map(function (x) { return ui.li(ii(x[1] ? "checkcircle" : "dot", x[1] ? "ok" : ""), esc(x[0]), esc(x[2] || ""), x[1] ? ui.pill("ok", "Done") : ui.pill("mute", "Later")); }).join("") + "</div></div>";
      h += '<div class="btns">' + ui.btn("Go live now", { cls: "pri", icon: "play", act: "toast", arg: "You're live · the booking link and QR poster are ready" }) + ui.btn("Do a test booking first", { icon: "chat", go: "whatsapp.chat" }) + "</div>";
    }
    return h;
  }
  function wizard(c) {
    var S = steps(), cur = Math.max(0, Math.min(S.length - 1, +NB.v("grStep", 7))), done = S.filter(function (s) { return s.st === "done"; }).length;
    var left = ui.card(L("Setup progress", "تقدم الإعداد", "सेटअप प्रगति"), '<div class="gr-prog"><div class="row sb"><b class="num">' + done + " of " + S.length + ' done</b><span class="small muted">38 min spent · target under 60</span></div>' + ui.bar(done / S.length * 100) + "</div>" +
      '<div class="gr-steps" style="margin-top:14px">' + S.map(function (s, i) {
        var cls = i === cur ? "cur" : s.st === "done" ? "done" : s.st === "warn" ? "warn" : "";
        var mark = s.st === "done" && i !== cur ? ui.icon("check") : s.st === "warn" && i !== cur ? ui.icon("alertcircle") : String(i + 1);
        return '<button class="gr-step' + (i === cur ? " on" : "") + '" data-set="grStep" data-val="' + i + '"><span class="gr-sn ' + cls + '">' + mark + '</span><span class="t"><b>' + esc(L(s.en, s.ar, s.hi)) + "</b><small>" + esc(s.sub) + "</small></span></button>";
      }).join("") + "</div>", { icon: "list" });
    var s = S[cur];
    var stPill = s.st === "done" ? ui.pill("ok", "Done") : s.st === "warn" ? ui.pill("warn", "Needs attention") : s.st === "cur" ? ui.pill("acc", "In progress", "play") : ui.pill("mute", "Not started");
    var body = '<div class="card"><div class="ch"><span class="eyebrow">' + ui.icon(s.ic, "sm") + "Step " + (cur + 1) + " of " + S.length + "</span>" + stPill + "</div>" +
      '<h2 class="h" style="margin-bottom:14px">' + esc(L(s.en, s.ar, s.hi)) + '</h2><div class="stack s16">' + stepBody(cur, c) + "</div>" +
      '<div class="gr-foot"><div class="btns">' + (cur > 0 ? ui.btn("Back", { cls: "ghost", icon: "chevleft", act: "grStep", arg: cur - 1 }) : "") + "</div><div class=\"btns\">" +
      (cur < S.length - 1 ? ui.btn("Skip for now", { cls: "ghost", act: "grStep", arg: cur + 1 }) + ui.btn("Save & continue", { cls: "pri", icon: "check", act: "grStep", arg: cur + 1 }) : "") + "</div></div></div>";
    var help = '<div class="grid g2">' + ui.card("Prefer we set it up?", '<p class="small muted" style="margin:0 0 12px">White-glove setup: a call and screen-share, your price list and doctors entered for you, and Meta verification done. ' +
      RAW(pick({ india: 4999, uae: 499, ksa: 499, qatar: 499 })) + " one-time, waived on annual plans.</p>" + ui.btn("Book a slot", { cls: "sm", icon: "calendar", act: "grWhite" }), { icon: "handshake" }) +
      ui.card("Your data, no lock-in", '<p class="small muted" style="margin:0 0 12px">Export everything (patients, bookings, bills, consents, files) any time in Excel/CSV, without asking support. Delete your account and get a deletion certificate.</p>' +
        ui.btn("Export or delete data", { cls: "sm", icon: "download", ov: "exportall" }), { icon: "shieldcheck" }) + "</div>";
    return '<div class="gr-wz">' + left + '<div class="stack s16">' + body + help + "</div></div>";
  }
  NB.act("grStep", function (a) { NB.setUI({ grStep: String(a) }); var cn = document.querySelector(".content"); if (cn) cn.scrollTop = 0; });
  NB.act("grWhite", function () { NB.toast("White-glove setup: slots open tomorrow 11:00 and 15:30 · confirm on WhatsApp"); });

  function branches(c) {
    var fs = facs(), F = (NB.D.admin && NB.D.admin.F) || [{ visits: 214, rev: 412000 }, { visits: 486, rev: 1286000 }, { visits: 38, rev: 58400 }];
    var fy = mid() === "india" ? "/26-27/" : "-2026-";
    var pre = function (f) { return f.area.replace(/[^A-Za-z]/g, "").slice(0, 3).toUpperCase(); };
    var tot = F.reduce(function (s, f) { return s + f.visits; }, 0), rev = F.reduce(function (s, f) { return s + f.rev; }, 0);
    var h = ui.note("info", "<b>Multi-branch lite</b> is for owners with 2–3 branches: one login, one dashboard, one patient identity — but each branch keeps its own schedules, fees, invoice series and WhatsApp routing. No org tree to manage.");
    h += '<div class="gr-mini">' + [[fs.length, "branches · one login"], [nf(tot), "visits today, all branches"], [cm(rev), "billed today, all branches"], ["312", "patients linked across branches"]].map(function (x) { return '<div class="card"><b>' + x[0] + "</b><span>" + esc(x[1]) + "</span></div>"; }).join("") + "</div>";
    h += '<div class="stack s8"><div class="row sb"><h2 class="h">' + L("Branches", "الفروع", "शाखाएँ") + "</h2>" + ui.btn(L("Add a branch", "إضافة فرع", "शाखा जोड़ें"), { cls: "pri sm", icon: "plus", ov: "branchadd" }) + "</div>" +
      ui.table([{ h: "Branch" }, { h: "Doctors" }, { h: "Today", cls: "r" }, { h: "Fees" }, { h: "Invoice series" }, { h: "WhatsApp routing" }, { h: "Status" }],
        fs.map(function (f, i) {
          return [ui.cellp(esc(f.name), esc(f.area) + " · " + esc(NB.FACILITY_TYPES[f.type].name)), num([9, 23, 1][i] || 1),
            '<div class="cellp"><b class="num">' + nf(F[i] ? F[i].visits : 0) + "</b><small>" + (F[i] ? cm(F[i].rev) : "") + "</small></div>",
            '<span class="small">' + (["Group price list", "Own list · 6 overrides", "Group list · 2 overrides"][i] || "Group price list") + "</span>",
            '<span class="gr-mono">' + pre(f) + fy + "0001</span>",
            ui.cellp('<span class="ltr">' + esc(waNum(i)) + "</span>", i === 2 ? "Shared with " + esc(short(fs[0])) + " · asks which branch" : "Own number"),
            ui.pill("ok", "Live")];
        }), { min: 980 }) + "</div>";
    h += '<div class="grid g2">' + ui.card(L("One patient across branches", "مريض واحد عبر الفروع", "सभी शाखाओं में एक मरीज़"), '<p class="small muted" style="margin:0 0 12px">Records are never merged silently. A link is suggested when mobile and date of birth match; the patient confirms by OTP, and the link is audited and can be undone.</p><div class="lst">' + [
      ["p1", short(fs[0]) + " ↔ " + short(fs[1]), "Mobile + date of birth match", "ok", "Linked · patient OTP 14 Oct"],
      ["p3", short(fs[0]) + " ↔ " + short(fs[1]), "Mobile + date of birth match", "warn", "Waiting for patient OTP"],
      ["p9", short(fs[0]) + " ↔ " + short(fs[2]), "Mobile matches, date of birth differs", "info", "Front desk to check"]
    ].map(function (x) {
      var p = NB.pat(x[0]);
      return ui.li(ui.av(p.name), esc(NB.pname(p)) + ' <span class="small faint">· ' + esc(x[1]) + "</span>", esc(x[2]), ui.pill(x[3], x[4]));
    }).join("") + "</div>", { icon: "link" }) +
      ui.card(L("Combined dashboard · today", "لوحة موحدة · اليوم", "संयुक्त डैशबोर्ड · आज"), CH().hbars(fs.map(function (f, i) { return [short(f), F[i] ? F[i].visits : 0, nf(F[i] ? F[i].visits : 0) + " visits"]; })) +
        '<hr class="hr" style="margin:14px 0">' + kv([["Combined visits", num(nf(tot))], ["Combined billed", cm(rev)], ["Patients seen at 2+ branches this month", num("41")]]) +
        '<div class="btns" style="margin-top:12px">' + ui.btn("Open overview", { cls: "sm", icon: "grid", go: "owner.overview" }) + "</div>", { icon: "chart" }) + "</div>";
    h += ui.note("info", "Adding a branch reuses the setup wizard: copy doctors, price list, templates and policies from an existing branch, then change only what differs. Typical time: <b>under 15 minutes</b>.");
    return h;
  }

  function calendar(c) {
    var h = "", m = c.m;
    if (gcc()) {
      var P = pick({ uae: { city: "Dubai", meth: "UAE (IACAD) method", t: [["Fajr", "04:58"], ["Dhuhr", "12:08"], ["Asr", "15:28"], ["Maghrib", "17:57"], ["Isha", "19:11"]], wk: "Saturday–Sunday weekend for most private clinics · Friday Jumu'ah break 12:15–13:30" },
        ksa: { city: "Riyadh", meth: "Umm al-Qura", t: [["Fajr", "04:38"], ["Dhuhr", "11:42"], ["Asr", "15:03"], ["Maghrib", "17:31"], ["Isha", "19:01"]], wk: "Friday–Saturday weekend · Friday opens after Jumu'ah (16:30)" },
        qatar: { city: "Doha", meth: "Qatar Awqaf", t: [["Fajr", "04:14"], ["Dhuhr", "11:26"], ["Asr", "14:47"], ["Maghrib", "17:14"], ["Isha", "18:44"]], wk: "Friday–Saturday weekend · Friday Jumu'ah break 11:00–13:00" } });
      var pr = NB.v("grPray", true), hj = NB.v("grHijri", true), rm = NB.v("grRamPrev", "") === "1";
      var toMin = function (s) { var a = s.split(":"); return +a[0] * 60 + +a[1]; };
      var x0 = 8 * 60, x1 = 24 * 60, pct = function (t) { return ((t - x0) / (x1 - x0) * 100).toFixed(2); };
      var open = rm ? [["10:00", "16:00"], ["21:00", "24:00"]] : [["09:00", "13:00"], ["16:30", "22:00"]];
      var blocks = rm ? [["17:31", "20:30", "rm"]] : [];
      if (pr) P.t.slice(1).forEach(function (t) { var s = toMin(t[1]); blocks.push([t[1], Math.floor((s + 20) / 60) + ":" + ("0" + (s + 20) % 60).slice(-2), "pr"]); });
      var strip = '<div class="stack s4" dir="ltr"><div class="gr-strip" role="img" aria-label="Clinic hours with prayer and Ramadan blocks">' +
        open.map(function (o) { return '<i class="op" style="left:' + pct(toMin(o[0])) + "%;width:" + (pct(toMin(o[1])) - pct(toMin(o[0]))).toFixed(2) + '%"></i>'; }).join("") +
        blocks.map(function (b) { return '<i class="' + b[2] + '" title="' + b[0] + "–" + b[1] + '" style="left:' + pct(toMin(b[0])) + "%;width:" + Math.max(0.8, pct(toMin(b[1])) - pct(toMin(b[0]))).toFixed(2) + '%"></i>'; }).join("") +
        '</div><div class="gr-sx">' + ["08:00", "12:00", "16:00", "20:00", "24:00"].map(function (t) { return '<span style="left:' + Math.min(97, Math.max(3, pct(toMin(t)))) + '%">' + t + "</span>"; }).join("") + "</div></div>";
      h += '<div class="grid g2">' + ui.card(L("Hijri dates", "التاريخ الهجري", "हिजरी तारीख़"), ui.toggle("grHijri", "Show Hijri beside Gregorian", "On the schedule, invoices, package expiry messages and the owner dashboard", true) +
        (hj ? '<div class="stack s8" style="margin-top:12px">' + [["2026-10-17", "Today"], ["2026-11-07", "Facility licence expiry"], ["2027-02-08", "Ramadan expected to start"]].map(function (d) {
          return '<div class="row sb"><span class="small muted">' + esc(d[1]) + '</span><span class="small"><b class="num">' + esc(gd(d[0])) + '</b> · <bdi>' + esc(hijri(d[0])) + "</bdi></span></div>";
        }).join("") + "</div>" : ""), { icon: "calendar" }) +
        ui.card(L("Prayer-time blocks", "أوقات الصلاة", "नमाज़ समय"), ui.toggle("grPray", "Block prayer times on every doctor's schedule", "20 minutes from each adhan · " + P.meth + " · " + P.city, true) +
          '<div class="chips" style="margin-top:12px">' + P.t.map(function (t) { return '<span class="pill ' + (pr && t[0] !== "Fajr" ? "warn" : "mute") + '" style="text-transform:none;letter-spacing:0">' + ui.icon("clockh") + esc(t[0]) + ' <span class="num ltr">' + t[1] + "</span></span>"; }).join("") + "</div>" +
          '<p class="small muted" style="margin:10px 0 0">Times update daily. Reminders and recall messages are never sent inside a prayer window.</p>', { icon: "clockh" }) + "</div>";
      h += ui.card(L("Today's schedule · Dr. Khalid Al-Saeed", "جدول اليوم", "आज का शेड्यूल"), strip + '<div class="gr-leg" style="margin-top:8px"><span><i class="op"></i>Open for bookings</span><span><i class="pr"></i>Prayer block</span><span><i class="rm"></i>Iftar break (Ramadan)</span></div>', { icon: "activity", right: ui.seg("grRamPrev", [["", "Normal days"], ["1", "Preview Ramadan"]], "") });
      h += ui.card(L("Ramadan hours", "ساعات رمضان", "रमज़ान के घंटे"), ui.toggle("grRam", "Switch to the Ramadan roster automatically", "Expected 8 Feb – 9 Mar 2027 (1448 AH), confirmed by moon sighting. Every doctor and staff template switches the night before.", false) +
        kv([["Ramadan clinic hours", '<span class="num ltr">10:00–16:00 · 21:00–00:00</span>'], ["Iftar break", "Maghrib until 20:30, blocked"], ["Staff shifts", "Ramadan template (6 hours) · see Shifts"], ["Package validity", "Auto-pauses on Eid closure days"], ["Weekend", esc(P.wk)]]) +
        '<div class="btns" style="margin-top:12px">' + ui.btn("Edit Ramadan template", { cls: "sm", icon: "edit", act: "toast", arg: "Ramadan template opened for all doctors · changes apply from 8 Feb 2027" }) + "</div>", { icon: "moon" });
      h += ui.note("info", "Switch the market to India in the top bar to see the regional holiday calendar.");
    } else {
      var hol = [["2026-10-20", "Vijayadashami (Dasara)", "Karnataka", "closed"], ["2026-11-01", "Kannada Rajyotsava", "Karnataka", "short"], ["2026-11-08", "Naraka Chaturdashi (Deepavali)", "National", "closed"], ["2026-11-10", "Balipadyami", "Karnataka", "short"],
        ["2026-11-27", "Kanakadasa Jayanti", "Karnataka", "open"], ["2026-12-25", "Christmas", "National", "closed"], ["2027-01-15", "Makara Sankranti", "Karnataka", "short"], ["2027-01-26", "Republic Day", "National", "closed"]];
      var ST = { closed: ["crit", "Closed", "stop"], short: ["warn", "Short hours 09:00–13:00", "clockh"], open: ["ok", "Open as usual", "check"] };
      h += '<div class="split">' + '<div class="stack s8"><div class="row sb"><h2 class="h">' + L("Regional holidays", "العطل الإقليمية", "क्षेत्रीय छुट्टियाँ") + "</h2>" + sel("State template", "grState", [["ka", "Karnataka"], ["mh", "Maharashtra"], ["tn", "Tamil Nadu"], ["kl", "Kerala"], ["dl", "Delhi"]], "ka") + "</div>" +
        ui.table([{ h: "Date" }, { h: "Holiday" }, { h: "List" }, { h: "Clinic" }, { h: "Bookings" }], hol.map(function (x, i) {
          var st = ST[x[3]];
          return ['<span class="num gr-nw">' + esc(gd(x[0])) + "</span>", esc(x[1]), '<span class="small muted">' + esc(x[2]) + "</span>", ui.pill(st[0], st[1], st[2]),
            x[3] === "open" ? '<span class="small faint">—</span>' : '<span class="small">' + [12, 0, 18, 4, 0, 0, 0, 0][i] + " to move</span>"];
        }), { min: 720 }) + "</div>" +
        '<div class="stack s16">' + ui.card("What happens on a closed day", '<div class="gr-on"><span>' + ui.icon("check", "okc") + "Booking slots disappear 30 days ahead</span><span>" + ui.icon("check", "okc") + "Patients already booked are told 7 days before and offered the next slot</span><span>" + ui.icon("check", "okc") + "Package validity does not run on closed days</span><span>" + ui.icon("check", "okc") + "Recall messages wait until the clinic reopens</span></div>", { icon: "info" }) +
        ui.card("Quiet hours", ui.toggle("grQuiet", "No patient messages 21:00–09:00", "Except queue updates on the day of the visit and OTPs", true), { icon: "moon" }) + "</div></div>";
      h += ui.note("info", "Holiday lists come from each state government's gazette and are re-checked every December. Switch the market to Saudi Arabia, UAE or Qatar in the top bar to see Hijri dates, prayer-time blocks and Ramadan hours.");
    }
    return h;
  }

  function health(c) {
    var m = c.m, LC = licences(), soon = LC.filter(function (x) { return x.d <= 30; });
    var cards = [
      { k: soon.some(function (x) { return x.d <= 7; }) ? "crit" : soon.length ? "warn" : "ok", t: "Licences", s: soon.length + " expiring within 30 days · reminders sent", ic: "scroll" },
      { k: mid() === "ksa" ? "warn" : "ok", t: "Tax & registrations", s: pick({ india: "GSTIN active · invoice series per branch", uae: "VAT TRN verified", ksa: "ZATCA phase-2 onboarding due 1 Feb 2027", qatar: "No VAT · CR on receipts" }), ic: "percent" },
      { k: "ok", t: "Data Processing Agreement", s: "Signed 14 Oct 2026 · version DPA-" + m.flag + "-1.3", ic: "file" },
      { k: "ok", t: "Data residency", s: "All records in " + m.residency, ic: "globe" },
      { k: "ok", t: "Retention", s: pick({ india: "Market default: clinical records 3 years after last visit (not editable)", uae: "Market default: 25 years from last procedure", ksa: "Market default: 10 years (counsel confirmed)", qatar: "Market default: 10 years" }), ic: "history" },
      { k: "ok", t: "Consent coverage", s: "98% of active patients have the current notice version", ic: "shieldcheck" },
      { k: "warn", t: "Open patient requests", s: "2 requests · nearest due in 6 days", ic: "users" },
      { k: "warn", t: "Messaging templates", s: "1 marketing template paused by quality throttle", ic: "chat" },
      { k: "ok", t: "Last breach drill", s: "12 Sep 2026 · next 12 Dec", ic: "alert" }
    ];
    var red = cards.filter(function (x) { return x.k === "crit"; }).length, amb = cards.filter(function (x) { return x.k === "warn"; }).length;
    var h = '<div class="row sb"><span class="small muted">One card, no legal jargon · ' + esc(m.privacyLaw) + "</span><div class=\"chips\">" + ui.pill(red ? "crit" : "ok", red + " red", red ? "alertfill" : "check") + ui.pill(amb ? "warn" : "ok", amb + " amber") + ui.pill("ok", (cards.length - red - amb) + " green", "check") + "</div></div>";
    h += '<div class="gr-hc">' + cards.map(function (x) {
      return '<div class="gr-hct">' + tl(x.k) + '<div class="t"><b>' + esc(x.t) + "</b><small>" + esc(x.s) + "</small>" + '<span><span class="pill ' + x.k + '">' + ui.icon(x.k === "ok" ? "checkcircle" : x.k === "warn" ? "alertcircle" : "alertfill") + esc(tlText(x.k)) + "</span></span></div></div>";
    }).join("") + "</div>";
    h += '<div class="stack s8"><div class="row sb"><h2 class="h">' + L("Licence expiry reminders", "تذكيرات انتهاء التراخيص", "लाइसेंस समाप्ति रिमाइंडर") + "</h2>" + ui.btn("Add licence", { cls: "sm", icon: "plus", act: "toast", arg: "Add a licence: number, authority, expiry and a photo of the certificate" }) + "</div>" +
      ui.table([{ h: "Licence" }, { h: "Holder" }, { h: "Number" }, { h: "Expiry" }, { h: "Status" }, { h: "Reminders" }, { h: "If it lapses" }], LC.slice().sort(function (a, b) { return a.d - b.d; }).map(function (x) {
        return { cls: x.d <= 7 ? "crit" : x.d <= 30 ? "urg" : "", cells: [ui.cellp(esc(x.k), esc(x.by)), esc(x.w), '<span class="gr-mono">' + esc(x.no) + "</span>", '<div class="cellp"><b class="num">' + esc(gd(x.exp)) + "</b>" + (gcc() ? "<small><bdi>" + esc(hijri(x.exp)) + "</bdi></small>" : "") + "</div>", daysPill(x.d), remPill(x.d),
          x.kind === "doc" ? ui.pill("sub", "Auto-hide from online booking", "eye") : '<span class="small muted">Owner alert only</span>'] };
      }), { min: 1060 }) + "</div>";
    h += '<div class="grid g2">' + ui.card("Grievance & privacy contact", kv([["Name", esc(dpo().n)], ["Email", '<span class="ltr">' + esc(dpo().mail) + "</span>"], ["Published in", "Privacy notice, booking page footer, every invoice"], ["Law", esc(m.privacyLaw)]]) +
      '<div class="btns" style="margin-top:12px">' + ui.btn("Grievance queue", { cls: "sm", icon: "chat", act: "grGo", arg: "feedback" }) + "</div>", { icon: "shielduser" }) +
      ui.card("Export & delete", '<p class="small muted" style="margin:0 0 12px">Self-serve full export in Excel/CSV and a deletion certificate after you leave. No support ticket needed.</p>' + ui.btn("Export or delete all data", { cls: "sm", icon: "download", ov: "exportall" }), { icon: "download" }) + "</div>";
    return h;
  }
  NB.act("grGo", function (a) { NB.state.ui.grGrTab = a; NB.go("owner", "growth"); });

  NB.screen("owner.setup", function (c) {
    var tab = NB.v("grSetTab", "wizard");
    var h = ui.header(L("Clinic setup & branches", "إعداد العيادة والفروع", "क्लिनिक सेटअप और शाखाएँ"), "Guided setup a non-IT owner finishes in under an hour · every step skippable and resumable · " + esc(c.m.name),
      ui.btn(L("Export or delete data", "تصدير البيانات أو حذفها", "डेटा एक्सपोर्ट या डिलीट"), { icon: "download", ov: "exportall" }) + ui.btn(L("Add a branch", "إضافة فرع", "शाखा जोड़ें"), { cls: "pri", icon: "plus", ov: "branchadd" }));
    h += ui.tabs("grSetTab", [["wizard", L("Setup wizard", "معالج الإعداد", "सेटअप विज़ार्ड"), "plussq"], ["branches", L("Branches", "الفروع", "शाखाएँ"), "building"], ["calendar", L("Regional calendar", "التقويم الإقليمي", "क्षेत्रीय कैलेंडर"), "calendar"], ["health", L("Compliance health", "صحة الامتثال", "अनुपालन स्वास्थ्य"), "shieldcheck"]]);
    h += tab === "branches" ? branches(c) : tab === "calendar" ? calendar(c) : tab === "health" ? health(c) : wizard(c);
    return h;
  });

  /* ---------- Overlay: add a branch (< 15 min) ---------- */
  NB.overlay("branchadd", function (arg, c) {
    var st = +NB.v("grBrStep", 0), fs = facs(), S = ["Branch details", "Copy from", "Schedules", "Fees", "Invoice series", "WhatsApp routing"];
    var h = '<div class="row sb"><span class="small muted">Step ' + (st + 1) + " of " + S.length + " · " + esc(S[st]) + '</span><span class="small muted">' + ui.icon("clockh", "xs") + " about " + (14 - st * 2) + " min left</span></div>" + ui.bar((st + 1) / S.length * 100);
    if (st === 0) h += '<div class="fgrid">' + ui.field("Branch name", gcc() ? "Al Noor Clinic · " + pick({ uae: "Mirdif", ksa: "Al Malqa", qatar: "Al Wakrah" }) : "Suvidha Clinic · HSR Layout", { id: "gr-bn" }) +
      ui.field("Area and city", pick({ india: "HSR Layout, Bengaluru", uae: "Mirdif, Dubai", ksa: "Al Malqa, Riyadh", qatar: "Al Wakrah" }), { id: "gr-ba" }) + ui.field("Facility licence number", "", { ph: pick({ india: "KPME registration", uae: "DHA facility licence", ksa: "MOH licence", qatar: "MOPH licence" }), id: "gr-bl" }) + ui.field("Licence expiry", "", { ph: "dd mmm yyyy", id: "gr-be" }) + "</div>";
    if (st === 1) h += '<span class="small muted">Copy from</span>' + ui.seg("grBrFrom", fs.map(function (f, i) { return [String(i), short(f)]; }), "0") + '<div class="gr-cks">' +
      ck("grCpDoc", "Doctors who will also work here", "Each doctor keeps one login and one record of their own", true) + ck("grCpPrice", "Price list", "Branch can override any line later", true) + ck("grCpTpl", "WhatsApp templates and clinic tone", "", true) + ck("grCpPol", "Booking rules and policies", "Cancellation window, reminders, acceptance mode", true) + ck("grCpStaff", "Front-desk staff", "Usually new people · invite them in the last step", false) + "</div>";
    if (st === 2) h += ui.table([{ h: "Doctor" }, { h: "Days" }, { h: "Timings" }], [["d1", "Tue, Thu", "17:00–21:00"], ["d2", "Mon, Wed, Fri", "09:00–13:00"]].map(function (r) { return [esc(dn(r[0])), r[1], '<span class="num ltr">' + r[2] + "</span>"]; }), { min: 420 }) +
      ui.note("info", "A doctor who works at two branches can never be double-booked: the schedules are checked against each other." + (gcc() ? " Prayer blocks and the Ramadan template come across automatically." : ""));
    if (st === 3) h += ui.radio("grBrFee", "same", "Use the group price list", "One list for every branch; simplest to run", "own") + ui.radio("grBrFee", "own", "Branch overrides", "Start from the group list and change only the lines that differ, e.g. a higher consult fee", "own") +
      (NB.v("grBrFee", "own") === "own" ? ui.table([{ h: "Charge head" }, { h: "Group", cls: "r" }, { h: "This branch", cls: "r" }], [["Consultation", 700, 800], ["Follow-up", 400, 450]].map(function (r) { return [r[0], M(r[1]), "<b>" + M(r[2]) + "</b>"]; }), { min: 380 }) : "");
    if (st === 4) h += '<div class="fgrid">' + ui.field("Invoice prefix", mid() === "india" ? "HSR/26-27/" : pick({ uae: "MIR-2026-", ksa: "MLQ-2026-", qatar: "WAK-2026-" }), { id: "gr-bp" }) + ui.field("Starts at", "0001", { id: "gr-bs" }) + "</div>" +
      ui.note("info", pick({ india: "Each branch gets its own sequential GST invoice series under the same GSTIN (Karnataka). A branch in another state needs its own GSTIN.", uae: "Each branch numbers its invoices separately under the group VAT TRN.", ksa: "Each branch has its own ZATCA invoice counter and device (EGS) under the group VAT TRN.", qatar: "Each branch numbers its receipts separately." }));
    if (st === 5) h += ui.radio("grBrWa", "shared", "Share the main WhatsApp number", "Patients pick the branch when they book; replies route to that branch's desk", "shared") + ui.radio("grBrWa", "own", "Give this branch its own number", "Its own display name and quality rating; takes 2–5 days for Meta approval", "shared") +
      ui.note("ok", "Ready. The branch appears in your facility switcher, the combined dashboard and the booking page.");
    return { kind: "drawer", title: L("Add a branch", "إضافة فرع", "शाखा जोड़ें"), body: '<div class="stack s16">' + h + "</div>",
      foot: (st > 0 ? ui.btn("Back", { cls: "ghost", act: "grBr", arg: st - 1 }) : ui.btn("Cancel", { cls: "ghost", act: "close" })) +
        (st < S.length - 1 ? ui.btn("Continue", { cls: "pri", icon: "chevright", act: "grBr", arg: st + 1 }) : ui.btn("Create branch", { cls: "pri", icon: "check", act: "grBrDone" })) };
  });
  NB.act("grBr", function (a) { NB.setUI({ grBrStep: String(a) }); });
  NB.act("grBrDone", function () { NB.state.ui.grBrStep = "0"; NB.state.overlay = null; NB.toast("Branch created in 12 min · invite its front desk next"); });

  /* ---------- Overlay: full export & delete (no lock-in) ---------- */
  NB.overlay("exportall", function (arg, c) {
    var st = NB.v("grExp", "setup"), m = c.m, fmt = NB.v("grExpFmt", "xlsx");
    var h = "";
    if (st === "setup") {
      h += ui.note("info", "Everything you put into Nabd is yours. Export it any time, as often as you like, without contacting support.");
      h += '<div class="stack s8"><b class="small">What to export</b><div class="gr-cks">' +
        ck("grE1", "Patients and family links", "18,240 people", true) + ck("grE2", "Appointments and queue history", "212,904 rows", true) + ck("grE3", "Visits, notes, prescriptions and results", "Clinical records", true) +
        ck("grE4", "Invoices, payments, refunds and day-close", "Tax-ready", true) + ck("grE5", "Consents, privacy notice versions and audit log", "Evidence for regulators", true) + ck("grE6", "Photos and attached files", "2.1 GB", true) + "</div></div>";
      h += '<div class="stack s8"><b class="small">Format</b>' + ui.seg("grExpFmt", [["xlsx", "Excel (.xlsx)"], ["csv", "CSV (zip)"], ["fhir", "FHIR R4 JSON"], ["pdf", "PDF per patient"]], "xlsx") + "</div>";
      h += kv([["Branches", "All " + facs().length], ["Estimated time", "about 14 minutes"], ["Download link", "Sent to the owner by WhatsApp and email · OTP to open · expires in 72 h"], ["Logged", "The export itself is written to the audit log"]]);
    } else if (st === "running") {
      h += '<div class="stack s8"><div class="row sb"><b>Export EXP-2026-1017-04</b>' + ui.pill("sync", "Preparing") + "</div>" + ui.bar(62) + '<span class="small muted">62% · about 5 minutes left · you can close this and keep working</span></div>';
      h += '<div class="tl">' + [["Patients", "18,240 rows", "ok"], ["Appointments", "212,904 rows", "ok"], ["Clinical records", "in progress", "warn"], ["Invoices & payments", "waiting", ""], ["Consents & audit", "waiting", ""], ["Files", "waiting", ""]].map(function (x) {
        return '<div class="ev"><span class="dotc ' + x[2] + '">' + ui.icon(x[2] === "ok" ? "check" : x[2] === "warn" ? "hourglass" : "dot", "sm") + '</span><div class="bd"><b>' + esc(x[0]) + "</b><small>" + esc(x[1]) + "</small></div></div>";
      }).join("") + "</div>";
    } else if (st === "ready") {
      h += ui.note("ok", "<b>Export ready.</b> " + { xlsx: "11 Excel workbooks", csv: "CSV zip (24 files)", fhir: "FHIR R4 bundle (NDJSON)", pdf: "18,240 patient PDFs" }[fmt] + " + files, 2.6 GB, SHA-256 checksum included.");
      h += kv([["Link", '<span class="gr-mono">nabd.health/x/EXP-1017-04</span>'], ["Expires", "20 Oct 2026, 14:10 (72 h)"], ["Opens with", "OTP to " + esc(owner())], ["Downloaded", "Not yet"]]);
      h += '<div class="btns">' + ui.btn("Download", { cls: "pri", icon: "download", act: "toast", arg: "Download started · 2.6 GB" }) + ui.btn("Copy checksum", { icon: "copy", act: "toast", arg: "SHA-256 copied" }) + "</div>";
      h += '<div class="card warnb" style="padding:16px"><div class="stack s8"><b>Leaving Nabd?</b><span class="small muted">After you have downloaded your export you can ask us to delete the account. Data is erased within 30 days and you get a signed deletion certificate.</span>' +
        ui.note("warn", "Records the law makes you keep are handed to you in the export, not kept by us: " + pick({ india: "tax invoices 8 years (GST Act s.36)", uae: "tax records 5 years and medical records 25 years", ksa: "tax invoices 6 years (VAT law) and medical records per MOH", qatar: "medical records per MOPH" }) + ".") +
        '<div class="btns">' + ui.btn("Request deletion", { cls: "dng sm", icon: "stop", act: "grExp", arg: "deleted" }) + "</div></div></div>";
    } else {
      h += '<div class="gr-cert"><div class="row sb"><h3>' + ui.icon("shieldcheck", "okc") + " Deletion certificate</h3>" + ui.pill("ok", "Signed") + "</div>" +
        kv([["Certificate", '<span class="gr-mono">DEL-' + m.flag + "-2026-00418</span>"], ["Tenant", esc(clinicName()) + " + " + (facs().length - 1) + " branches"], ["Live data erased", "17 Nov 2026 (within 30 days)"], ["Backups purged", "22 Dec 2026 (35-day backup cycle)"],
          ["Messaging copies", "WhatsApp media links revoked"], ["Kept", "Nothing. Your export holds the records you must retain."], ["Region", esc(m.residency)], ["Signed by", "Nabd Data Protection Officer"]]) + "</div>";
      h += ui.note("info", "The certificate is sent to the owner's email and WhatsApp and can be shown to an auditor or the " + esc(m.regulator.split(" / ")[0]) + ".");
    }
    var foot = st === "setup" ? ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn("Start export", { cls: "pri", icon: "download", act: "grExp", arg: "running" }) :
      st === "running" ? ui.btn("Close", { cls: "ghost", act: "close" }) + ui.btn("Skip ahead (demo)", { act: "grExp", arg: "ready" }) :
      st === "ready" ? ui.btn("New export", { cls: "ghost", act: "grExp", arg: "setup" }) + ui.btn("Close", { act: "close" }) :
      ui.btn("Download certificate (PDF)", { icon: "download", act: "toast", arg: "Deletion certificate downloaded" }) + ui.btn("Close", { act: "grExpReset" });
    return { kind: "drawer", size: "lg", title: L("Export or delete all data", "تصدير كل البيانات أو حذفها", "सारा डेटा एक्सपोर्ट या डिलीट करें"), body: h, foot: foot };
  });
  NB.act("grExp", function (a) { NB.setUI({ grExp: a }); });
  NB.act("grExpReset", function () { NB.state.ui.grExp = "setup"; NB.close(); });

  /* ================= owner.prices ================= */
  var CATS = ["Consultation", "Procedure", "Service", "Certificate", "Consumable"];
  function tierCell(v, base) { return v == null ? '<span class="small muted">= normal</span>' : M(v); }
  function priceList(c) {
    var cat = NB.v("grPrCat", "all"), rows = G.heads.filter(function (h) { return cat === "all" || h.cat === cat; });
    var act = G.heads.filter(function (h) { return h.act; });
    var h = ui.card(L("Billing screen preview", "معاينة شاشة الفوترة", "बिलिंग स्क्रीन प्रीव्यू"), '<div class="gr-bil">' + act.map(function (x) {
      return '<button class="gr-bb" data-ov="pricechange" data-arg="' + x.id + '"><span>' + esc(x.id) + "</span><small>" + M(x.base) + "</small></button>";
    }).join("") + "</div>" + '<p class="small muted" style="margin:12px 0 0">This is exactly what the receptionist taps at checkout, in this order (most used first). Inactive heads disappear here but stay on past bills.</p>', { icon: "receipt", right: ui.pill("ok", "Live for new bills") });
    h += '<div class="row sb">' + ui.seg("grPrCat", [["all", "All", G.heads.length]].concat(CATS.map(function (k) { return [k, k, G.heads.filter(function (x) { return x.cat === k; }).length]; })), "all") +
      ui.btn("Add charge head", { cls: "sm", icon: "plus", ov: "pricechange", arg: "new" }) + "</div>";
    h += ui.table([{ h: "Charge head" }, { h: "Category" }, { h: "Normal", cls: "r" }, { h: "Follow-up", cls: "r" }, { h: "Emergency", cls: "r" }, { h: "Per doctor" }, { h: "Tax" }, { h: "Package" }, { h: "Status" }, { h: "" }],
      rows.map(function (x) {
        var nov = Object.keys(x.ov).length;
        return { ov: "pricechange", arg: x.id, cells: [ui.cellp(esc(x.en), '<span class="gr-mono">' + esc(x.id) + "</span>" + (gcc() ? " · <bdi>" + esc(x.ar) + "</bdi>" : "")), esc(x.cat), M(x.base),
          x.cat === "Consultation" ? tierCell(x.fu) : '<span class="small muted">n/a</span>', tierCell(x.em),
          x.cat !== "Consultation" ? '<span class="small muted">n/a</span>' : nov ? ui.pill("info", nov + " overrides", "users") : '<span class="small muted">None</span>',
          taxPill(x.tx), x.pkg ? ui.pill("acc", "Eligible", "layers") : '<span class="small muted">—</span>', x.act ? ui.pill("ok", "Active") : ui.pill("mute", "Inactive", "pause"),
          ui.btn("Edit", { cls: "sm ghost", icon: "edit", ov: "pricechange", arg: x.id })] };
      }), { min: 1180 });
    var con = head("CON");
    h += '<div class="grid g2">' + ui.card(L("Per-doctor consultation fees", "رسوم الاستشارة لكل طبيب", "डॉक्टर-वार परामर्श शुल्क"), ui.table([{ h: "Doctor" }, { h: "Normal", cls: "r" }, { h: "Follow-up", cls: "r" }, { h: "Emergency", cls: "r" }],
      ["d1", "d2", "d3", "d4", "d5"].map(function (d) {
        var o = con.ov[d];
        return [ui.cellp(esc(dn(d)), esc(NB.doc(d).spec)), o ? "<b>" + M(o) + "</b>" : '<span class="small muted">' + M(con.base) + " (default)</span>", M(o ? Math.round(o * 0.57) : con.fu), M(o ? Math.round(o * 1.6) : con.em)];
      }), { min: 520 }) + '<p class="small muted" style="margin:10px 0 0">Blank override = clinic default, in every case. Applied automatically at checkout.</p>', { icon: "users" }) +
      ui.card(L("Price rules in force", "قواعد التسعير المطبقة", "लागू मूल्य नियम"), kv([["Follow-up window", "<b>" + NB.v("grFuWin", "7") + " days</b> · same doctor · Consultation only"], ["Free follow-up", NB.v("grFreeFu", true) ? "First review for the same complaint" : "Off"], ["Emergency tier", "Suggested when check-in is flagged urgent · receptionist confirms"],
        ["After-hours tier", NB.v("grAfter", true) ? "Suggested after 21:00 and on " + (gcc() ? "Fridays" : "Sundays") : "Off"], ["Tax", esc(mk().tax) + (mk().taxRate ? " · " + mk().taxRate + "% standard" : "")]]) +
        '<div class="btns" style="margin-top:12px">' + ui.btn("Change rules", { cls: "sm", icon: "sliders", act: "grPrTab", arg: "rules" }) + "</div>", { icon: "sliders" }) + "</div>";
    return h;
  }
  NB.act("grPrTab", function (a) { NB.setUI({ grPrTab: a }); });
  function rules(c) {
    var win = +NB.v("grFuWin", "7"), free = NB.v("grFreeFu", true);
    var h = '<div class="grid g2">' + ui.card(L("Follow-up window", "فترة المتابعة", "फ़ॉलो-अप विंडो"), '<div class="stack s8"><span class="small muted">Same patient, same doctor, Consultation category, inside this many days → follow-up price. Different doctor or outside the window → normal price.</span>' +
      ui.seg("grFuWin", [["7", "7 days (default)"], ["10", "10 days"], ["15", "15 days"], ["30", "30 days"]], "7") + "</div>" +
      ui.toggle("grFreeFu", "First follow-up free for the same complaint", "Bills at zero with reason “free review”; still counted in reports", true) +
      ui.toggle("grFreeProc", "Post-procedure review free for 14 days", "For procedures like extraction, dressing and peels", true) +
      ui.toggle("grFuCap", "Only one free follow-up per episode", "The second review inside the window uses the follow-up price", true), { icon: "refresh" }) +
      ui.card(L("Emergency & after-hours", "الطوارئ وبعد الدوام", "आपातकाल और देर के घंटे"), ui.toggle("grEmSug", "Suggest the emergency price when check-in is flagged urgent", "A suggestion only: the receptionist confirms before billing", true) +
        ui.toggle("grAfter", "Suggest an after-hours price", "After 21:00 and on " + (gcc() ? "Fridays" : "Sundays") + " · uses the emergency tier", true) +
        ui.toggle("grDisc", "Receptionist may discount up to 10%", "Above 10% needs the owner's PIN; every discount needs a reason and feeds billing-leakage alerts", true), { icon: "bolt" }) + "</div>";
    var cases = [["Same doctor, 5 days after last visit", 5, true, false], ["Same doctor, " + (win + 2) + " days after last visit", win + 2, true, false], ["Different doctor, 3 days after last visit", 3, false, false], ["New complaint, urgent flag at check-in", 40, true, true], ["Same doctor, " + (win - 1) + " days, second review in this episode", win - 1, true, false, true]];
    h += ui.card(L("Check the rules", "اختبر القواعد", "नियम जाँचें"), ui.table([{ h: "Scenario" }, { h: "Price suggested" }, { h: "Amount", cls: "r" }, { h: "Why" }], cases.map(function (x) {
      var con = head("CON"), fu = x[1] <= win && x[2], em = x[3];
      var lab = em ? ["urg", "Emergency (confirm)", "bolt"] : fu ? (free && !x[4] ? ["ok", "Free review", "check"] : ["info", "Follow-up", "refresh"]) : ["mute", "Normal", "dot"];
      var amt = em ? con.em : fu ? (free && !x[4] ? 0 : con.fu) : con.base;
      var why = em ? "Urgent flag · receptionist must confirm" : fu ? "Inside " + win + "-day window, same doctor" + (x[4] ? " · free review already used" : "") : x[2] ? "Outside the " + win + "-day window" : "Different doctor → normal price";
      return [esc(x[0]), ui.pill(lab[0], lab[1], lab[2]), M(amt), '<span class="small muted">' + esc(why) + "</span>"];
    }), { min: 720 }) + '<p class="small muted" style="margin:10px 0 0">Change the window above and the table updates. These are the acceptance cases the billing engine is tested against.</p>', { icon: "checkcircle" });
    return h;
  }
  function taxTab(c) {
    var m = c.m, h = "";
    var groups = [["svc", "Consultations & clinical services"], ["cos", "Cosmetic & aesthetic services, retail skincare"], ["med", "Medicines"], ["cons", "Consumables & devices"]];
    h += '<div class="grid g2">' + ui.card(L("Tax per line", "الضريبة لكل بند", "हर लाइन पर टैक्स"), '<div class="lst">' + groups.map(function (g) { var t = tax(g[0]); return ui.li(ii(t[0] ? "percent" : "check", t[0] ? "info" : "ok"), esc(g[1]), esc(t[1]), num(t[0] + "%")); }).join("") + "</div>" +
      '<p class="small muted" style="margin:10px 0 0">Bill totals are always built up from line items; nobody edits tax at bill level.</p>', { icon: "percent" });
    if (mid() === "ksa") {
      var inv = function (who, nat, rate) {
        var lines = [["Consultation", 700, rate], ["ECG (12-lead)", 300, rate], ["Sunscreen SPF 50", 650, 15]], sub = 0, vat = 0;
        lines.forEach(function (l) { sub += l[1]; vat += l[1] * l[2] / 100; });
        return '<div class="card" style="padding:14px"><div class="row sb"><b class="small">' + esc(who) + "</b>" + ui.pill(nat ? "ok" : "info", nat ? "Saudi national" : "Non-Saudi (Iqama)", "user") + "</div>" +
          ui.table([{ h: "Line" }, { h: "Amount", cls: "r" }, { h: "VAT", cls: "r" }], lines.map(function (l) { return [esc(l[0]), M(l[1]), num(l[2] + "%")]; }), { min: 300 }) +
          '<div style="margin-top:10px">' + kv([["VAT", M(Math.round(vat))], ["Total", "<b>" + M(Math.round(sub + vat)) + "</b>"]]) + "</div>" +
          (nat ? '<p class="small muted" style="margin:8px 0 0">Invoice wording: “VAT on healthcare services for Saudi citizens is borne by the state.” National ID proof kept with the invoice.</p>' : "") + "</div>";
      };
      h += ui.card(L("Nationality-based VAT (KSA)", "ضريبة القيمة المضافة حسب الجنسية", "राष्ट्रीयता-आधारित वैट"), '<div class="stack s8"><span class="small muted">Nationality comes from the National ID or Iqama captured at registration. Cosmetic services and retail products are 15% for everyone.</span>' +
        inv("Abdullah Al-Harbi · NID 10•••••342", true, 0) + inv("Kavya Reddy · Iqama 24•••••871", false, 15) + "</div>", { icon: "shieldcheck" }) + "</div>";
    } else {
      h += ui.card(pick({ india: "Guided GST picker", uae: "Zero-rated or standard?", qatar: "No VAT in Qatar" }), pick({
        india: '<div class="lst">' + [["Treatment of illness or injury", "Exempt (SAC 9993)", "ok"], ["Reconstructive procedure after accident or illness", "Exempt (SAC 9993)", "ok"], ["Cosmetic or aesthetic only (peel, laser hair, whitening)", "18% (SAC 999722)", "info"], ["Medicines sold", "5% or 12% by HSN", "info"], ["Skincare product", "18%", "info"]].map(function (x) { return ui.li(ii(x[2] === "ok" ? "check" : "percent", x[2]), esc(x[0]), "", ui.pill(x[2], x[1])); }).join("") + "</div>" +
          '<p class="small muted" style="margin:10px 0 0">When a new charge head is added the picker asks “Is it to treat an illness or injury?” and sets the rate. Get a written GST opinion for mixed packages.</p>',
        uae: '<div class="lst">' + [["Preventive and basic healthcare", "0% zero-rated", "ok"], ["Medicines on the MOHAP list", "0% zero-rated", "ok"], ["Cosmetic services (not medically necessary)", "5% standard", "info"], ["Retail products", "5% standard", "info"]].map(function (x) { return ui.li(ii(x[2] === "ok" ? "check" : "percent", x[2]), esc(x[0]), "", ui.pill(x[2], x[1])); }).join("") + "</div>",
        qatar: '<p class="small muted" style="margin:0">Receipts show no tax line. If Qatar introduces VAT, rates will be set here per charge head and only new bills will change.</p>'
      }), { icon: "info" }) + "</div>";
    }
    return h;
  }
  function packagesTab(c) {
    var P = [["Acne Clear", "6 acne sessions + 2 chemical peels + 1 sunscreen", 6 * 1500 + 2 * 2500 + 650, 11999, 8, 120, "All branches"], ["Laser hair · full face × 6", "6 laser sessions", 6 * 3500, 17999, 6, 180, short(facs()[0]) + " only"],
      ["Dental clean & whiten", "Scaling + whitening + 1 review", 1200 + 8000 + 500, 7999, 3, 90, "All branches"], ["Physio × 10", "10 physiotherapy sessions", 10 * 600, 4999, 10, 60, "All branches"]];
    var pkgTax = pick({ india: "GST on advance receipt flagged · components taxed at their own rate", uae: "VAT at sale, per component", ksa: "Full VAT invoiced at sale (ZATCA advance-payment rule)", qatar: "No VAT" });
    var h = ui.table([{ h: "Package" }, { h: "List value", cls: "r" }, { h: "Package price", cls: "r" }, { h: "Per session", cls: "r" }, { h: "Saving" }, { h: "Validity" }, { h: "Where" }],
      P.map(function (p) { return [ui.cellp(esc(p[0]), esc(p[1])), M(p[2]), "<b>" + M(p[3]) + "</b>", M(Math.round(p[3] / p[4])), ui.pill("ok", Math.round((1 - p[3] / p[2]) * 100) + "% off"), num(p[5] + " days"), esc(p[6])]; }), { min: 900 });
    h += '<div class="grid g2">' + ui.card("Package rules", kv([["Tax", esc(pkgTax)], ["Cancellation", "Used sessions at full single price, rest refunded · no fee"], ["Expiry", "Warnings at 30 / 14 / 7 days · paid extension offered first"], gcc() ? ["Ramadan", "Validity can pause on Eid closure days"] : ["Closed days", "Validity does not run on holidays"]]), { icon: "layers" }) +
      ui.card("Procedure prices", '<div class="lst">' + G.heads.filter(function (x) { return x.cat === "Procedure"; }).map(function (x) { return ui.li(ii("activity"), esc(x.en), taxPill(x.tx), M(x.base), { ov: "pricechange", arg: x.id }); }).join("") + "</div>", { icon: "activity" }) + "</div>";
    h += '<div class="btns">' + ui.btn("Open package sales", { cls: "sm", icon: "layers", go: "billing.packages" }) + "</div>";
    return h;
  }
  function logTab() {
    var ow = owner();
    return ui.note("info", "Price changes apply to new bills only. Issued bills and invoices never change. Every edit is kept here and can be exported.") +
      ui.table([{ h: "When" }, { h: "Who" }, { h: "Charge head / setting" }, { h: "Field" }, { h: "Old → new" }, { h: "Applies" }], G.log.map(function (x) {
        var f = function (v) { return v == null ? '<span class="small muted">blank</span>' : typeof v === "number" ? M(v) : esc(v); };
        return ['<span class="small num gr-nw">' + esc(x.t) + "</span>", esc(x.who === "owner" ? ow : x.who), esc(x.item), esc(x.f), '<span class="gr-nw">' + f(x.o) + " → <b>" + f(x.n) + "</b></span>", '<span class="small muted">' + esc(x.eff) + "</span>"];
      }), { min: 920 }) + '<div class="btns">' + ui.btn("Export change log (CSV)", { cls: "sm", icon: "download", act: "toast", arg: "Price change log exported · 6 rows" }) + "</div>";
  }
  function starterTab() {
    var sp = NB.v("grSpec", "derm"), list = G.starter[sp];
    return '<div class="row sb"><span class="small muted">' + list.length + " charge heads with placeholder amounts and " + esc(mk().name) + " tax defaults</span>" + ui.seg("grSpec", SPECS.map(function (x) { return [x[0], x[1]]; }), "derm") + "</div>" +
      ui.table([{ h: "Charge head" }, { h: "Category" }, { h: "Placeholder", cls: "r" }, { h: "Tax default" }, { h: "Already in your list" }], list.map(function (r) {
        var have = G.heads.some(function (x) { return x.en.split(" ·")[0].toLowerCase() === r[0].toLowerCase(); });
        return [esc(r[0]), esc(r[1]), M(r[2]), taxPill(r[3]), have ? ui.pill("ok", "Yes") : ui.pill("mute", "Will be added", "plus")];
      }), { min: 720 }) +
      '<div class="btns">' + ui.btn("Add missing heads to my list", { cls: "pri sm", icon: "plus", act: "toast", arg: "Missing heads added as inactive drafts · set amounts before switching them on" }) + "</div>";
  }
  NB.screen("owner.prices", function (c) {
    var tab = NB.v("grPrTab", "list"), m = c.m;
    var h = ui.header(L("Price master & fees", "قائمة الأسعار والرسوم", "मूल्य सूची और शुल्क"), "One list for every charge · " + esc(m.tax) + (m.taxRate ? " " + m.taxRate + "%" : "") + " · " + esc(m.taxNote),
      ui.btn(L("Change log", "سجل التغييرات", "बदलाव लॉग"), { icon: "history", act: "grPrTab", arg: "log" }) + ui.btn(L("Add charge head", "إضافة بند", "शुल्क जोड़ें"), { cls: "pri", icon: "plus", ov: "pricechange", arg: "new" }));
    h += ui.tabs("grPrTab", [["list", L("Price list", "قائمة الأسعار", "मूल्य सूची"), "list"], ["rules", L("Follow-up & tiers", "المتابعة والفئات", "फ़ॉलो-अप और टियर"), "sliders"], ["tax", L("Tax", "الضريبة", "टैक्स"), "percent"],
      ["packages", L("Procedures & packages", "الإجراءات والباقات", "प्रक्रियाएँ और पैकेज"), "layers"], ["log", L("Change log", "سجل التغييرات", "बदलाव लॉग"), "history"], ["starter", L("Starter lists", "القوائم الجاهزة", "स्टार्टर सूचियाँ"), "sparkle"]]);
    h += tab === "rules" ? rules(c) : tab === "tax" ? taxTab(c) : tab === "packages" ? packagesTab(c) : tab === "log" ? logTab() : tab === "starter" ? starterTab() : priceList(c);
    return h;
  });

  NB.overlay("pricechange", function (id, c) {
    var isNew = id === "new", x = isNew ? { id: "", en: "", ar: "", cat: "Procedure", base: null, fu: null, em: null, tx: "svc", ov: {}, pkg: false, act: true } : head(id);
    var h = "";
    h += '<div class="fgrid">' + ui.field("Name", x.en, { id: "gr-pn", ph: "e.g. Wart removal (cryo)" }) + ui.field("Short label (billing button)", x.id, { id: "gr-pl", ph: "max 6 letters" }) +
      (gcc() ? ui.field("Arabic label", x.ar, { id: "gr-par" }) : "") + ui.field("Category", x.cat, { id: "gr-pc", options: CATS }) + "</div>";
    h += '<div class="stack s8"><b class="small">Prices (' + esc(mk().curCode) + ")</b>" + '<div class="fgrid">' + ui.field("Normal", x.base != null ? String(Math.round(x.base * mk().fx * 100) / 100) : "", { id: "gr-pb", type: "text" }) +
      (x.cat === "Consultation" ? ui.field("Follow-up (blank = normal)", x.fu != null ? String(Math.round(x.fu * mk().fx * 100) / 100) : "", { id: "gr-pf" }) : "") +
      ui.field("Emergency (blank = normal)", x.em != null ? String(Math.round(x.em * mk().fx * 100) / 100) : "", { id: "gr-pe" }) + "</div></div>";
    if (x.cat === "Consultation") h += '<div class="stack s8"><b class="small">Per-doctor normal fee (blank = clinic default)</b><div class="fgrid">' + ["d1", "d2", "d3", "d4", "d5"].map(function (d) {
      return ui.field(dn(d), x.ov[d] ? String(Math.round(x.ov[d] * mk().fx * 100) / 100) : "", { id: "gr-o" + d, ph: "default" });
    }).join("") + "</div></div>";
    h += '<div class="stack s8"><b class="small">Tax</b>' + ui.seg("grPcTax", [["svc", tax("svc")[1]], ["cos", tax("cos")[1]], ["cons", tax("cons")[1]]], x.tx === "cert" ? "svc" : x.tx) + "</div>";
    h += ui.toggle("grPcPkg_" + (x.id || "new"), "Package-eligible", "Can be bundled in packages with its own session price", x.pkg) + ui.toggle("grPcAct_" + (x.id || "new"), "Active on the billing screen", "Inactive heads stay on past bills and reports", x.act);
    h += '<div class="fgrid">' + ui.field("Applies from", "Next bill", { id: "gr-pw", options: ["Next bill", "Tomorrow 00:00", "1 Nov 2026"] }) + ui.field("Reason (kept in the change log)", "", { id: "gr-pr", ph: "e.g. annual revision" }) + "</div>";
    h += ui.note("info", "Issued bills and invoices never change. The change log records who, when, old value and new value.");
    if (!isNew) h += '<div class="stack s8"><b class="small">History for ' + esc(x.en) + '</b><div class="lst">' + G.log.filter(function (l) { return l.item.indexOf(x.en.split(" ·")[0]) === 0; }).concat([{ t: "02 Oct 15:40", who: "Setup wizard", f: "Created", o: null, n: x.base }]).map(function (l) {
      return ui.li(ii("history"), esc(l.t) + " · " + esc(l.who === "owner" ? owner() : l.who), esc(l.f) + ": " + (l.o == null ? "blank" : typeof l.o === "number" ? M(l.o) : esc(l.o)) + " → " + (typeof l.n === "number" ? M(l.n) : esc(l.n)), "");
    }).join("") + "</div></div>";
    return { kind: "drawer", title: isNew ? L("New charge head", "بند جديد", "नया शुल्क") : L("Edit", "تعديل", "संपादित करें") + " · " + esc(x.en), body: h,
      foot: ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn("Save · owner PIN", { cls: "pri", icon: "lock", act: "toast", arg: isNew ? "Charge head added · appears on the billing screen for new bills" : "Saved · applies to new bills · logged in the change log" }) };
  });

  /* ================= owner.reports ================= */
  function rp(id) { return G.reports.find(function (r) { return r.id === id; }) || G.reports[0]; }
  function rname(r) { return L(r.en, r.ar, r.hi); }
  function payTypes() { return pick({ india: [["UPI", 52], ["Cash", 27], ["Card", 14], ["Insurance / TPA", 7]], uae: [["Card", 48], ["Apple Pay", 21], ["Insurance", 22], ["Cash", 9]], ksa: [["mada", 46], ["Apple Pay", 17], ["STC Pay", 9], ["Cash", 12], ["Insurance (NPHIES)", 16]], qatar: [["Card", 55], ["Cash", 18], ["Insurance", 27]] }); }
  // Returns {kpis:[[v,label]], rule, chart, cols, rows, min}
  function repData(id, c) {
    var W = CH(), days = ["04", "05", "06", "07", "08", "09", "10", "11", "12", "13", "14", "15", "16", "17"], lab = days.map(function (d, i) { return i % 3 === 0 || i === 13 ? d + " Oct" : ""; });
    var thr = mid() === "india" ? 200 : 200;
    if (id === "leak") {
      var E = [["p5", "LHR", "Laser hair reduction · session", 3500, "Today 09:48", "d1"], ["p3", "DRS", "Wound dressing", 250, "Today 09:31", "d2"], ["p7", "NEB", "Nebulisation", 200, "Yesterday 17:12", "d3"], ["p2", "PEEL", "Chemical peel", 2500, "15 Oct 12:05", "d1"], ["p8", "ECG", "ECG (12-lead)", 300, "14 Oct 10:40", "d2"], ["p4", "VAC", "Vaccination (administration)", 250, "13 Oct 11:15", "d3"]];
      return { kpis: [[E.length, "items done but not billed"], [M(E.reduce(function (s, e) { return s + e[3]; }, 0)), "value at risk"], [M(18400), "recovered this month"], ["3", "linked to one doctor"]],
        rule: "Procedure orders marked done with no bill line, amount ≥ " + M(thr) + ", last 7 days. Rule shown as the API returns it.",
        chart: W.cols([2, 0, 1, 3, 1, 0, 2, 4, 1, 2, 0, 1, 2, 2], { labels: lab, h: 120, aria: "Unbilled items per day", title: function (v, i) { return days[i] + " Oct · " + v + " items"; } }),
        cols: [{ h: "Patient" }, { h: "Charge" }, { h: "Amount", cls: "r" }, { h: "Completed" }, { h: "Doctor" }, { h: "" }],
        rows: E.map(function (e) { return [esc(NB.pname(NB.pat(e[0]))), ui.cellp(esc(e[2]), '<span class="gr-mono">' + e[1] + "</span>"), M(e[3]), '<span class="small num">' + esc(e[4]) + "</span>", esc(dn(e[5])), ui.btn("Bill now", { cls: "sm", act: "toast", arg: "Added to " + NB.pname(NB.pat(e[0])) + "'s bill · patient gets the receipt on WhatsApp" })]; }), min: 820 };
    }
    if (id === "money") {
      var pt = payTypes(), tot = 412000;
      return { kpis: [[cm(tot), "collected today"], ["214", "bills raised"], [M(9800), "refunds"], [M(23400), "dues outstanding"]], rule: "Collections by payment type from closed and open bills today; matches day close to the last " + (mid() === "india" ? "paisa" : "halala") + ".",
        chart: W.hbars(pt.map(function (p) { return [p[0], p[1], cm(tot * p[1] / 100)]; })),
        cols: [{ h: "Payment type" }, { h: "Bills", cls: "r" }, { h: "Amount", cls: "r" }, { h: "Share", cls: "r" }], rows: pt.map(function (p) { return [esc(p[0]), num(Math.round(214 * p[1] / 100)), cm(tot * p[1] / 100), num(p[1] + "%")]; }), min: 520 };
    }
    if (id === "pkg") {
      var P = [["Acne Clear", 48, 11999, 8, 5.2], ["Laser hair · full face × 6", 31, 17999, 6, 3.4], ["Dental clean & whiten", 22, 7999, 3, 1.6], ["Physio × 10", 35, 4999, 10, 6.1]];
      var owed = P.reduce(function (s, p) { return s + p[1] * (p[3] - p[4]) * p[2] / p[3]; }, 0);
      return { kpis: [[P.reduce(function (s, p) { return s + p[1]; }, 0), "active packages"], [cm(owed), "service still owed"], ["17", "expire in 30 days"], [cm(64000), "lapsed this quarter"]],
        rule: "Money already collected for sessions not yet delivered = service the clinic still owes. Valued at package price per session.",
        chart: W.hbars(P.map(function (p) { return [p[0], p[1] * (p[3] - p[4]) * p[2] / p[3], cm(p[1] * (p[3] - p[4]) * p[2] / p[3])]; })),
        cols: [{ h: "Package" }, { h: "Active", cls: "r" }, { h: "Sold value", cls: "r" }, { h: "Sessions left (avg)", cls: "r" }, { h: "Owed", cls: "r" }, { h: "Expiry risk" }],
        rows: P.map(function (p, i) { return [esc(p[0]), num(p[1]), cm(p[1] * p[2]), num((p[3] - p[4]).toFixed(1)), "<b>" + cm(p[1] * (p[3] - p[4]) * p[2] / p[3]) + "</b>", [ui.pill("warn", "6 in 30 days"), ui.pill("warn", "5 in 30 days"), ui.pill("ok", "Low"), ui.pill("info", "6 in 30 days")][i]]; }), min: 820 };
    }
    if (id === "tax") {
      var T = pick({
        india: { k: "GSTR-1 · October 2026 (to date)", cols: ["Rate", "Taxable value", "CGST", "SGST", "Lines"], rows: [["Exempt (SAC 9993)", 3820000, 0, 0, 5120], ["5%", 210000, 5250, 5250, 410], ["12%", 640000, 38400, 38400, 1290], ["18%", 1260000, 113400, 113400, 860]] },
        uae: { k: "VAT 201 · Q4 2026 (to date)", cols: ["Box", "Amount", "VAT", "", "Lines"], rows: [["1a Standard-rated supplies (5%)", 1260000, 63000, null, 860], ["4 Zero-rated (healthcare, medicines)", 4460000, 0, null, 6410], ["5 Exempt", 0, 0, null, 0]] },
        ksa: { k: "VAT return · Q4 2026 (to date)", cols: ["Line", "Amount", "VAT", "", "Invoices"], rows: [["Standard-rated sales (15%)", 2100000, 315000, null, 2140], ["Citizens' private healthcare (state-borne)", 1720000, 258000, null, 2310], ["Zero-rated (medicines)", 640000, 0, null, 1290]] },
        qatar: { k: "Sales summary · October 2026", cols: ["Category", "Sales", "VAT", "", "Receipts"], rows: [["Consultations & services", 3820000, 0, null, 5120], ["Procedures", 1260000, 0, null, 860], ["Medicines & products", 850000, 0, null, 1700]] }
      });
      return { kpis: [[cm(T.rows.reduce(function (s, r) { return s + r[1]; }, 0)), "sales in period"], [cm(T.rows.reduce(function (s, r) { return s + r[2] + (r[3] || 0); }, 0)), mid() === "qatar" ? "no VAT due" : "tax collected"], ["0", "invoices with tax mismatches"], [pick({ india: "11 Nov", uae: "28 Jan", ksa: "31 Jan", qatar: "—" }), "filing due"]],
        rule: T.k + " · built from invoice lines, reconciled with day close" + (mid() === "ksa" ? " · nationality from ID / Iqama" : ""),
        chart: W.hbars(T.rows.map(function (r) { return [r[0], r[1], cm(r[1])]; })),
        cols: T.cols.filter(function (x) { return x !== ""; }).map(function (x, i) { return { h: x, cls: i ? "r" : "" }; }),
        rows: T.rows.map(function (r) { return [esc(r[0]), cm(r[1]), cm(r[2])].concat(r[3] != null ? [cm(r[3])] : []).concat([num(nf(r[4]))]); }), min: 640 };
    }
    if (id === "src") {
      var S = [["WhatsApp", 1840, 1702, 1290000], ["Walk-in", 1210, 1210, 720000], ["Own booking page & widget", 640, 582, 452000], ["Phone call", 410, 361, 240000], ["Nabd Connect", 380, 341, 276000], ["Google “Book”", 210, 188, 151000], ["Patient referral", 96, 91, 72000]];
      return { kpis: [[nf(4786), "bookings, last 30 days"], ["38%", "came through WhatsApp"], ["91%", "show-up rate"], [cm(3201000), "billed"]], rule: "Bookings, attendance and revenue grouped by booking source. Attribution uses the source recorded at booking.",
        chart: W.hbars(S.map(function (s) { return [s[0], s[1], nf(s[1])]; })),
        cols: [{ h: "Source" }, { h: "Bookings", cls: "r" }, { h: "Attended", cls: "r" }, { h: "Show-up", cls: "r" }, { h: "Revenue", cls: "r" }],
        rows: S.map(function (s) { return [esc(s[0]), num(nf(s[1])), num(nf(s[2])), num(Math.round(s[2] / s[1] * 100) + "%"), cm(s[3])]; }), min: 620 };
    }
    if (id === "ret") {
      var rr = [36.1, 37.0, 37.4, 38.2, 38.0, 39.1, 39.6, 40.2, 40.9, 41.3, 41.8, 42.2];
      return { kpis: [[nf(4812), "patients, last 12 months"], [nf(2031), "came back at least once"], ["42.2%", "repeat rate"], ["2.3", "visits per patient"]], rule: "Aggregate only: no individual patient list is returned by this report.",
        chart: W.lineChart(rr, { min: 30, max: 45, unit: "%", labels: ["Nov", "Feb", "May", "Aug", "Oct"], h: 130, aria: "Repeat rate over 12 months" }),
        cols: [{ h: "Measure" }, { h: "Last 12 months", cls: "r" }, { h: "Previous 12", cls: "r" }], rows: [["Repeat rate", num("42.2%"), num("35.4%")], ["Visits per patient", num("2.3"), num("2.0")], ["Revenue per returning patient", M(3900), M(3350)], ["Bookings from recall messages", num("1,184"), num("410")]], min: 520 };
    }
    if (id === "nsr") {
      var N = [["p8", 3, "Tomorrow 10:20", "d2"], ["p9", 2, "Mon 11:40", "d1"], ["p5", 2, "Tue 17:30", "d1"], ["p7", 2, "Wed 16:00", "d3"]];
      return { kpis: [[N.length, "booked patients flagged"], ["6.8%", "no-show rate, 30 days"], ["−1.4 pts", "since reminders went 2-step"], ["Off", "no-show deposits (default)"]],
        rule: "Rule: patient booked in the next 7 days AND 2 or more no-shows in the last 12 months. Shown as the API returns it; no hidden model.",
        chart: "", cols: [{ h: "Patient" }, { h: "Past no-shows", cls: "r" }, { h: "Next booking" }, { h: "Doctor" }, { h: "Suggested" }],
        rows: N.map(function (n) { return [esc(NB.pname(NB.pat(n[0]))), num(n[1]), '<span class="num">' + esc(n[2]) + "</span>", esc(dn(n[3])), n[1] > 2 ? ui.pill("warn", "Call to confirm", "phone") : ui.pill("info", "Extra WhatsApp confirm", "chat")]; }), min: 680 };
    }
    if (id === "staff") {
      var ST = [["Sneha Kulkarni", "Reception", 412, "38 s", 0, "—"], ["Pooja Nair", "Billing", 398, "26 s", 6, M(2100)], ["Anita D'Souza", "Nurse", 286, "2.4 min", 0, "—"], ["Deepa Menon", "Insurance desk", 74, "4.1 min", 1, M(400)]];
      return { kpis: [["4", "front-line staff"], ["31 s", "median check-in"], ["24 s", "median checkout"], ["7", "discounts given"]], rule: "Scope: owner and admins see everyone; each staff member sees only their own row.",
        chart: W.hbars(ST.map(function (s) { return [s[0], s[2], nf(s[2]) + " patients"]; })),
        cols: [{ h: "Staff" }, { h: "Role" }, { h: "Patients handled", cls: "r" }, { h: "Median time", cls: "r" }, { h: "Discounts", cls: "r" }, { h: "Discount value", cls: "r" }],
        rows: ST.map(function (s) { return [esc(s[0]), esc(s[1]), num(s[2]), num(s[3]), num(s[4]), s[5]]; }), min: 700 };
    }
    if (id === "prod") {
      var D = [["d2", 702, 7.4, 86, 980000], ["d1", 611, 9.1, 82, 860000], ["d3", 529, 10.6, 79, 610000], ["d4", 412, 13.8, 74, 1820000], ["d5", 344, 12.2, 77, 790000]];
      return { kpis: [["2,598", "patients seen, 30 days"], ["10.1 min", "average consult"], ["80%", "slot utilisation"], ["61%", "follow-ups that booked"]], rule: "Consult minutes from start/close taps; utilisation = booked and walk-in minutes ÷ scheduled minutes.",
        chart: W.cols(D.map(function (d) { return d[4] / (d[1] * d[2] / 60); }), { labels: D.map(function (d) { return NB.doc(d[0]).name.replace("Dr. ", "").split(" ")[0]; }), h: 120, aria: "Revenue per consult hour", title: function (v, i) { return dn(D[i][0]) + " · " + Math.round(v * mk().fx) + " per hour"; } }),
        cols: [{ h: "Doctor" }, { h: "Patients", cls: "r" }, { h: "Avg consult", cls: "r" }, { h: "Utilisation", cls: "r" }, { h: "Revenue", cls: "r" }, { h: "Per consult hour", cls: "r" }],
        rows: D.map(function (d) { return [ui.cellp(esc(dn(d[0])), esc(NB.doc(d[0]).spec)), num(d[1]), num(d[2] + " min"), num(d[3] + "%"), cm(d[4]), cm(d[4] / (d[1] * d[2] / 60))]; }), min: 760 };
    }
    var PU = [["d1", 96, 1, 0, 2], ["d2", 71, 9, 15, 6], ["d3", 90, 2, 4, 1], ["d4", 82, 3, 11, 4], ["d5", 88, 2, 6, 3]];
    return { kpis: [["85%", "sessions started on time"], ["17", "late starts, 30 days"], ["9 min", "average lateness"], ["16", "sessions overran"]], rule: "On time = first patient called within 5 minutes of the session start.",
      chart: W.hbars(PU.map(function (p) { return [dn(p[0]), p[1], p[1] + "% on time"]; })),
      cols: [{ h: "Doctor" }, { h: "On time", cls: "r" }, { h: "Late starts", cls: "r" }, { h: "Avg late", cls: "r" }, { h: "Overruns", cls: "r" }],
      rows: PU.map(function (p) { return [esc(dn(p[0])), num(p[1] + "%"), num(p[2]), num(p[3] + " min"), num(p[4])]; }), min: 600 };
  }
  function delivery(id) {
    var r = rp(id);
    return '<div class="gr-deliv"><div class="stack s4"><b class="small">' + ui.icon("send", "sm") + " Scheduled delivery</b><span class=\"small muted\">" + esc({ leak: "Daily 21:30", money: "Daily 21:30 with day close", tax: "Monthly on the 3rd", nsr: "Daily 18:00" }[id] || "Weekly, Monday 08:00") +
      " · WhatsApp PDF to " + esc(owner()) + " · email to accountant</span></div>" + '<div class="btns">' + ui.btn("Schedule", { cls: "sm", icon: "calendar", ov: "reportview", arg: id }) +
      ui.btn("Excel", { cls: "sm ghost", icon: "download", act: "toast", arg: rname(r) + " exported to Excel" }) + ui.btn("CSV", { cls: "sm ghost", icon: "download", act: "toast", arg: rname(r) + " exported to CSV" }) + ui.btn("PDF", { cls: "sm ghost", icon: "printer", act: "toast", arg: rname(r) + " PDF ready" }) + "</div></div>";
  }
  NB.screen("owner.reports", function (c) {
    var id = NB.v("grRep", "leak"), r = rp(id), d = repData(id, c), fs = facs();
    var h = ui.header(L("Reports", "التقارير", "रिपोर्ट"), "Straight from live records · every number opens the rows behind it · export to Excel or CSV",
      ui.btn(L("Export everything", "تصدير الكل", "सब एक्सपोर्ट करें"), { icon: "download", ov: "exportall" }));
    var groups = ["Money", "Patients", "Team"];
    var lib = '<div class="card" style="padding:12px"><div class="gr-rlib">' + groups.map(function (g) {
      return '<div class="gr-rg">' + esc({ Money: L("Money", "المال", "पैसा"), Patients: L("Patients", "المرضى", "मरीज़"), Team: L("Team", "الفريق", "टीम") }[g]) + "</div>" + G.reports.filter(function (x) { return x.grp === g; }).map(function (x) {
        return '<button class="gr-ri' + (x.id === id ? " on" : "") + '" data-set="grRep" data-val="' + x.id + '">' + ii(x.ic) + '<span class="t"><b>' + esc(rname(x)) + "</b><small>" + esc(x.sub) + "</small></span>" + (x.api ? "" : ui.pill("sub", "New", "sparkle")) + "</button>";
      }).join("");
    }).join("") + "</div></div>";
    var body = '<div class="stack s16"><div class="card"><div class="ch"><span class="eyebrow">' + ui.icon(r.ic, "sm") + esc(r.grp) + "</span>" + (r.api ? ui.pill("ok", "In product", "checkcircle") : ui.pill("sub", "New report", "sparkle")) + "</div>" +
      '<div class="row sb" style="margin-bottom:14px"><h2 class="h" style="font-size:19px">' + esc(rname(r)) + '</h2><div class="row">' + ui.seg("grRg", [["d7", "7 days"], ["d30", "30 days"], ["m", "This month"]], "d30") + "</div></div>" +
      '<div class="row" style="margin-bottom:14px">' + sel("Branch", "grRb", [["all", "All branches"]].concat(fs.map(function (f, i) { return [String(i), short(f)]; })), "all") + "</div>" +
      '<div class="gr-mini">' + d.kpis.map(function (k) { return '<div class="card"><b>' + k[0] + "</b><span>" + esc(k[1]) + "</span></div>"; }).join("") + "</div>" +
      '<div class="gr-rule" style="margin-top:14px">' + ui.icon("info", "sm") + "<span>" + d.rule + (r.api ? ' · <code class="ltr">' + esc(r.api) + "</code>" : "") + "</span></div>" +
      (d.chart ? '<div style="margin-top:16px">' + d.chart + "</div>" : "") + "</div>" +
      ui.table(d.cols, d.rows.slice(0, 6), { min: d.min }) + delivery(id) +
      '<div class="btns">' + ui.btn("Open full report", { icon: "panel", ov: "reportview", arg: id }) + "</div></div>";
    h += '<div class="split l">' + lib + body + "</div>";
    return h;
  });
  NB.overlay("reportview", function (id, c) {
    var r = rp(id), d = repData(id, c);
    var freq = NB.v("grRf", "weekly");
    var h = '<div class="gr-mini">' + d.kpis.map(function (k) { return '<div class="card"><b>' + k[0] + "</b><span>" + esc(k[1]) + "</span></div>"; }).join("") + "</div>" +
      '<div class="gr-rule">' + ui.icon("info", "sm") + "<span>" + d.rule + "</span></div>" + (d.chart || "") + ui.table(d.cols, d.rows, { min: d.min });
    h += '<div class="card"><div class="ch"><span class="eyebrow">' + ui.icon("send", "sm") + "Schedule & deliver</span></div><div class=\"stack s16\">" +
      ui.seg("grRf", [["daily", "Daily"], ["weekly", "Weekly"], ["monthly", "Monthly"], ["off", "Off"]], "weekly") +
      '<div class="fgrid">' + ui.field("Time", freq === "daily" ? "21:30" : "08:00", { id: "gr-rt" }) + ui.field(freq === "monthly" ? "Day of month" : "Day", freq === "monthly" ? "3" : freq === "daily" ? "Every day" : "Monday", { id: "gr-rd" }) + "</div>" +
      '<div class="gr-cks">' + ck("grRw", "WhatsApp PDF to the owner", owner() + " · " + mk().phone + " •••• 2200", true) + ck("grRe", "Email Excel to the accountant", gcc() ? "accounts@alnoorhealth.com" : "ca.ramesh@ksassociates.in", true) + ck("grRm", "Email to branch managers", "Each sees only their branch", false) + "</div>" +
      ui.note("info", "Reports with patient names go only to staff whose role allows it; the accountant copy has names masked. Every export is written to the audit log.") + "</div></div>";
    return { kind: "drawer", size: "lg", title: esc(rname(r)), body: h,
      foot: ui.btn("Excel", { cls: "ghost", icon: "download", act: "toast", arg: "Exported to Excel" }) + ui.btn("CSV", { cls: "ghost", icon: "download", act: "toast", arg: "Exported to CSV" }) + ui.btn("Save schedule", { cls: "pri", icon: "check", act: "toast", arg: "Schedule saved · next delivery " + (freq === "daily" ? "today 21:30" : freq === "monthly" ? "3 Nov 08:00" : "Monday 08:00") }) };
  });

  /* ================= owner.growth ================= */
  function recallsTab(c) {
    var R = G.recalls, sent = R.reduce(function (s, r) { return s + r.sent; }, 0), bk = R.reduce(function (s, r) { return s + r.booked; }, 0);
    var h = '<div class="gr-mini">' + [[nf(R.reduce(function (s, r) { return s + r.due; }, 0)), "patients due this week"], [nf(sent), "recall messages sent"], [nf(bk) + " · " + Math.round(bk / sent * 100) + "%", "booked from the message"], [cm(bk * 820), "visits recovered (est.)"]].map(function (x) { return '<div class="card"><b>' + x[0] + "</b><span>" + esc(x[1]) + "</span></div>"; }).join("") + "</div>";
    h += '<div class="stack s8"><div class="row sb"><h2 class="h">' + L("Recall & retention campaigns", "حملات الاستدعاء والاحتفاظ", "रिकॉल और रिटेंशन अभियान") + "</h2>" + ui.btn(L("Open doctor recalls", "فتح استدعاءات الطبيب", "डॉक्टर रिकॉल खोलें"), { cls: "sm", icon: "refresh", go: "doctor.recalls" }) + "</div>" +
      ui.table([{ h: "Rule" }, { h: "Owner" }, { h: "Type" }, { h: "Due", cls: "r" }, { h: "Sent", cls: "r" }, { h: "Booked", cls: "r" }, { h: "Conversion" }, { h: "Status" }], R.map(function (r) {
        var cv = r.sent ? Math.round(r.booked / r.sent * 100) : 0;
        return [esc(r.r), r.d ? esc(dn(r.d)) : '<span class="small muted">Clinic</span>', r.kind === "utility" ? ui.pill("info", "Care reminder") : ui.pill("sub", "Marketing", "sparkle"), num(r.due), num(r.sent), num(r.booked),
          r.sent ? '<div class="stack s4" style="min-width:90px"><span class="small num">' + cv + "%</span>" + ui.bar(cv, cv >= 40 ? "ok" : "") + "</div>" : '<span class="small muted">—</span>',
          r.st === "on" ? ui.pill("ok", "Running", "play") : ui.pill("warn", "Paused · quality throttle", "pause")];
      }), { min: 1040 }) + "</div>";
    h += '<div class="grid g2">' + ui.note("info", "Care reminders go to patients under their treatment consent. The win-back campaign is marketing: it only reaches the <b>118 of 210</b> patients who said yes to marketing messages, and stops on one STOP reply.") +
      ui.note("info", gcc() ? "Messages never go out inside a prayer window, and are held during Eid closure days." : "Quiet hours 21:00–09:00. SMS fallback uses DLT-registered templates; promotional SMS is scrubbed against the DND registry.") + "</div>";
    return h;
  }
  function reviewsTab(c) {
    var gate = NB.v("grGate", "all");
    var h = '<div class="grid g2">' + ui.card(L("Google review requests", "طلبات تقييم جوجل", "गूगल रिव्यू अनुरोध"), ui.toggle("grRevOn", "Ask after the visit", "WhatsApp message 2 hours after checkout, once per patient every 6 months", true) +
      '<div class="stack s8" style="margin-top:12px"><b class="small">Who gets the Google link</b>' + ui.radio("grGate", "all", "Everyone, equally (recommended)", "Policy-safe: every patient gets the same thank-you and Google review link", "all") +
      ui.radio("grGate", "gate", "Rating gate", "Ask for a 1–5 rating first; 4–5 get the Google link, 1–3 go to a private alert for the clinic", "all") + "</div>" +
      (gate === "gate" ? ui.note("warn", "<b>Disclosed risk:</b> Google's review policy does not allow selectively asking only happy patients. Gated requests can lead to reviews being removed. Low ratings still reach you privately either way.") : ""), { icon: "star" }) +
      ui.card("Last 30 days", '<div class="gr-fn">' + [["620", "requests sent"], ["540", "opened"], [gate === "gate" ? "310" : "—", gate === "gate" ? "rated in chat" : "rating step off"], ["128", "Google reviews posted"]].map(function (x) { return '<div class="gr-fs"><b>' + x[0] + "</b><span>" + esc(x[1]) + "</span></div>"; }).join("") + "</div>" +
        '<hr class="hr" style="margin:14px 0">' + kv([["Google rating", '<span class="gr-stars">★</span> <b class="num">4.7</b> <span class="small muted">(1,284 reviews · was 4.4 in June)</span>'], ["Replied within 48 h", num("92%")], ["Private feedback captured", num("34")]]), { icon: "chart" }) + "</div>";
    h += '<div class="stack s8"><h2 class="h">' + L("Recent reviews · reply tracking", "أحدث التقييمات", "हाल की समीक्षाएँ") + "</h2>" + ui.table([{ h: "Reviewer" }, { h: "Rating" }, { h: "Review" }, { h: "When" }, { h: "Reply" }], G.reviews.map(function (r) {
      return [esc(r.n), '<span class="gr-stars">' + "★★★★★".slice(0, r.s) + '</span><span class="small muted num"> ' + r.s + "/5</span>", '<span class="small gr-wrap">' + esc(r.t) + "</span>", '<span class="small num gr-nw">' + esc(r.w) + "</span>",
        r.rep ? ui.pill("ok", "Replied") : '<div class="btns">' + ui.pill(r.s <= 2 ? "urg" : "warn", r.s <= 2 ? "Reply today" : "Not replied", r.s <= 2 ? "bolt" : "clockh") + ui.btn("Draft reply", { cls: "sm", icon: "sparkle", act: "toast", arg: "Draft reply ready to edit · nothing is posted without you" }) + "</div>"];
    }), { min: 900 }) + "</div>";
    return h;
  }
  function referralsTab(c) {
    var rw = NB.v("grRw", "disc");
    var h = '<div class="grid g2">' + ui.card(L("Referral rewards", "مكافآت الإحالة", "रेफ़रल रिवॉर्ड"), ui.toggle("grRefOn", "Give patients a personal referral link", "Shared from WhatsApp; the friend books through it", true) +
      '<div class="stack s8" style="margin-top:12px"><b class="small">Reward when the friend completes a first visit</b>' + ui.seg("grRw", [["disc", M(200) + " off next visit"], ["addon", "Free add-on"], ["wallet", "Wallet credit"]].map(function (x) { return [x[0], x[1].replace(/<[^>]+>/g, "")]; }), "disc") + "</div>" +
      kv([["Who can earn", "Patients only · never staff or doctors"], ["Cap", "5 rewards per patient per month"], ["Cash payouts", "Never · credit is used at this clinic"], ["Expiry", "90 days"]]), { icon: "handshake" }) +
      ui.card("Programme results · 30 days", '<div class="gr-fn">' + [["214", "links shared"], ["61", "friends booked"], ["52", "first visits done"], [cm(52 * 900), "new-patient revenue"]].map(function (x) { return '<div class="gr-fs"><b>' + x[0] + "</b><span>" + esc(x[1]) + "</span></div>"; }).join("") + "</div>" +
        ui.note("info", gcc() ? "Healthcare promotions may need an advertising permit from the health regulator. Keep rewards as service credit, not cash." : "Rewards are patient-to-patient. Doctor-to-doctor referral fees are not rewards: they are tracked separately under Doctor payouts with a compliance review."), { icon: "chart" }) + "</div>";
    h += ui.table([{ h: "Patient" }, { h: "Friends referred", cls: "r" }, { h: "First visits", cls: "r" }, { h: "Rewards earned", cls: "r" }, { h: "Status" }], G.referrers.map(function (r) {
      var st = { ok: ["ok", "Rewards issued"], wait: ["warn", "2 visits pending"], cap: ["info", "Monthly cap reached"] }[r.st];
      return [esc(NB.pname(NB.pat(r.p))), num(r.ref), num(r.vis), num(r.rw), ui.pill(st[0], st[1])];
    }), { min: 620 });
    return h;
  }
  var CST = { new: ["urg", "New", "bolt"], prog: ["info", "In progress", "hourglass"], esc: ["crit", "Escalated", "alertfill"], done: ["ok", "Resolved", "checkcircle"] };
  function feedbackTab(c) {
    var m = c.m, D = dpo();
    var h = '<div class="grid g3">' + ui.card("Patient NPS · 30 days", '<div class="row nw"><b class="gr-big">64</b><span class="small muted">from 1,120 replies · ' + ui.pill("ok", "+5 vs last month") + "</span></div>" +
      '<div class="gr-stk" style="margin-top:12px" role="img" aria-label="Promoters 71%, passives 22%, detractors 7%"><i class="p" style="width:71%"></i><i class="n" style="width:22%"></i><i class="d" style="width:7%"></i></div>' +
      '<div class="gr-leg" style="margin-top:8px"><span><i class="p"></i>Promoters 71%</span><span><i class="n"></i>Passives 22%</span><span><i class="d"></i>Detractors 7%</span></div>', { icon: "star" }) +
      ui.card("What patients raise", CH().hbars([["Waiting time", 38, "38"], ["Billing", 17, "17"], ["Staff behaviour", 9, "9"], ["Cleanliness", 7, "7"], ["Doctor communication", 6, "6"], ["Privacy", 2, "2"]]), { icon: "chart" }) +
      ui.card("Grievance officer", kv([["Name", esc(D.n)], ["Contact", '<span class="ltr">' + esc(D.mail) + "</span>"], ["Law", esc(m.privacyLaw)], ["Statutory limit", gcc() ? "Respond without undue delay" : "Resolve within 90 days (DPDP)"], ["Clinic target", "Acknowledge 24 h · resolve 7 days"]]), { icon: "shielduser" }) + "</div>";
    h += '<div class="stack s8"><div class="row sb"><h2 class="h">' + L("Complaints & grievances", "الشكاوى والتظلمات", "शिकायतें") + "</h2>" + ui.seg("grCq", [["open", "Open", G.complaints.filter(function (x) { return x.st !== "done"; }).length], ["all", "All", G.complaints.length]], "open") + "</div>";
    var rows = G.complaints.filter(function (x) { return NB.v("grCq", "open") === "all" || x.st !== "done"; });
    h += ui.table([{ h: "Ref" }, { h: "Patient" }, { h: "Category" }, { h: "Channel" }, { h: "Received" }, { h: "SLA" }, { h: "Level" }, { h: "Status" }], rows.map(function (x) {
      var s = CST[x.st];
      return { ov: "grievance", arg: x.id, cls: x.st === "esc" ? "crit" : x.st === "new" ? "urg" : "", cells: ['<span class="tok">' + x.id + "</span>", esc(NB.pname(NB.pat(x.p))), esc(x.cat), '<span class="small">' + esc(x.ch) + "</span>", '<span class="small num gr-nw">' + esc(x.recv) + "</span>", '<span class="small">' + esc(x.sla) + "</span>",
        '<span class="small gr-nw">L' + x.lvl + " · " + esc(x.who) + "</span>", ui.pill(s[0], s[1], s[2])] };
    }), { min: 1060 }) + "</div>";
    h += ui.note("info", "Escalation: <b>L1</b> front desk (24 h) → <b>L2</b> clinic manager (3 days) → <b>L3</b> owner or grievance officer. Privacy complaints go straight to L3. Patients can always reach the " + esc(m.regulator.split(" / ")[0]) + " as well; the reply letter says how.");
    return h;
  }
  function waTab(c) {
    var thr = NB.v("grThr", true);
    var nums = facs().map(function (f, i) { return [f, waNum(i), i === 1 ? "Medium" : "High", i === 1 ? "warn" : "ok", i === 1 ? "1,000 / day" : "10,000 / day"]; }).filter(function (x, i) { return i < 2; });
    var h = '<div class="grid g2">' + nums.map(function (n) {
      return ui.card(esc(short(n[0])) + " · " + '<span class="ltr">' + esc(n[1]) + "</span>", '<div class="row sb"><div class="row nw">' + ii("chat", "wa") + '<div><b class="small">Quality rating</b><div>' + ui.pill(n[3], n[2], n[3] === "ok" ? "checkcircle" : "alertcircle") + '</div></div></div><div class="stack s4" style="text-align:end"><span class="small muted">Messaging limit</span><b class="num">' + n[4] + "</b></div></div>" +
        '<div style="margin-top:12px">' + CH().lineChart(n[3] === "ok" ? [0.4, 0.5, 0.4, 0.3, 0.5, 0.4, 0.3] : [0.5, 0.6, 0.9, 1.7, 2.1, 1.4, 1.1], { min: 0, max: 2.5, unit: "%", thr: { v: 1.5, label: "throttle at 1.5%" }, labels: ["11 Oct", "14 Oct", "17 Oct"], h: 90, aria: "Block and report rate, 7 days" }) + "</div>" +
        '<span class="small muted">Block + report rate, last 7 days</span>', { icon: "activity" });
    }).join("") + "</div>";
    h += '<div class="grid g2">' + ui.card(L("Auto-throttle", "الإبطاء التلقائي", "ऑटो-थ्रॉटल"), ui.toggle("grThr", "Protect the number automatically", "Act before Meta restricts it", true) +
      '<div class="tl" style="margin-top:12px">' + [["High", "Everything sends: care reminders, recalls, reviews, campaigns", "ok"], ["Medium", "Marketing pauses (win-back, review requests). Queue updates, reminders and OTPs keep flowing", "warn"], ["Low", "Only patient-started chats, queue updates and OTPs. Owner alerted", "crit"]].map(function (x) {
        return '<div class="ev"><span class="dotc ' + x[2] + '">' + ui.icon(x[2] === "ok" ? "check" : x[2] === "warn" ? "pause" : "stop", "sm") + '</span><div class="bd"><b>' + esc(x[0]) + "</b><small>" + esc(x[1]) + "</small></div></div>";
      }).join("") + "</div>" + (thr ? "" : ui.note("warn", "With auto-throttle off, a bad campaign can get the number restricted for every patient message, including queue updates.")), { icon: "shieldcheck" }) +
      ui.card("What happened", '<div class="lst">' + [["14 Oct 19:10", "Whitefield number dropped to Medium after the win-back campaign (2.1% blocks)", "warn"], ["14 Oct 19:11", "Marketing paused automatically · 3 templates held", "info"], ["16 Oct 08:00", "Block rate back under 1.5% · marketing stays paused 24 h more", "ok"], ["17 Oct 08:00", "Review requests resume tomorrow 10:00 if the rate holds", "info"]].map(function (x) {
        return ui.li(ii(x[2] === "ok" ? "checkcircle" : x[2] === "warn" ? "alertcircle" : "info", x[2]), esc(x[1]), '<span class="num">' + esc(x[0]) + "</span>", "");
      }).join("") + "</div>", { icon: "history" }) + "</div>";
    h += '<div class="stack s8"><h2 class="h">' + L("Message templates", "قوالب الرسائل", "मैसेज टेम्पलेट") + "</h2>" + ui.table([{ h: "Template" }, { h: "Category" }, { h: "Languages" }, { h: "Status" }, { h: "Quality" }], G.templates.map(function (t) {
      var st = { ok: ["ok", "Approved", "checkcircle"], warn: ["warn", "Paused (throttle)", "pause"], crit: ["crit", "Paused by Meta", "alertfill"] }[t[3]];
      return ['<span class="gr-mono">' + esc(t[0]) + "</span>", t[1] === "Marketing" ? ui.pill("sub", t[1], "sparkle") : t[1] === "Authentication" ? ui.pill("perm", t[1], "lock") : ui.pill("info", t[1]), esc(t[2]), ui.pill(st[0], st[1], st[2]), ui.pill(t[4] === "High" ? "ok" : t[4] === "Medium" ? "warn" : "crit", t[4])];
    }), { min: 760 }) + "</div>";
    h += ui.note("info", "Burst lanes: OTPs and queue updates always go first, then reminders, then everything else. Meta templates are managed by Nabd; clinics never need to open Business Manager.");
    return h;
  }
  NB.screen("owner.growth", function (c) {
    var tab = NB.v("grGrTab", "recalls");
    var h = ui.header(L("Recalls, reviews & feedback", "الاستدعاء والتقييمات والملاحظات", "रिकॉल, रिव्यू और फ़ीडबैक"), "Bring patients back, earn reviews fairly, hear complaints early and keep the WhatsApp number healthy",
      ui.btn(L("Doctor recalls", "استدعاءات الطبيب", "डॉक्टर रिकॉल"), { icon: "refresh", go: "doctor.recalls" }));
    h += ui.tabs("grGrTab", [["recalls", L("Recalls & retention", "الاستدعاء والاحتفاظ", "रिकॉल और रिटेंशन"), "refresh"], ["reviews", L("Google reviews", "تقييمات جوجل", "गूगल रिव्यू"), "star"], ["referrals", L("Referral rewards", "مكافآت الإحالة", "रेफ़रल रिवॉर्ड"), "handshake"],
      ["feedback", L("Feedback & grievances", "الملاحظات والتظلمات", "फ़ीडबैक और शिकायतें"), "chat"], ["wa", L("WhatsApp quality", "جودة واتساب", "व्हाट्सऐप गुणवत्ता"), "shieldcheck"]]);
    h += tab === "reviews" ? reviewsTab(c) : tab === "referrals" ? referralsTab(c) : tab === "feedback" ? feedbackTab(c) : tab === "wa" ? waTab(c) : recallsTab(c);
    return h;
  });
  NB.overlay("grievance", function (id, c) {
    var x = G.complaints.find(function (q) { return q.id === id; }) || G.complaints[0], p = NB.pat(x.p), s = CST[x.st], m = c.m;
    var tlv = [["Received via " + x.ch, x.recv, "acc"], ["Acknowledged on WhatsApp (auto)", x.st === "new" ? "Pending" : x.recv + " + 12 min", x.st === "new" ? "" : "ok"], ["Assigned to L1 · front desk lead", x.st === "new" ? "Pending" : "Same day", x.st === "new" ? "" : "ok"]];
    if (x.lvl >= 2) tlv.push(["Escalated to L2 · clinic manager", "SLA missed at L1", "warn"]);
    if (x.lvl >= 3) tlv.push(["Escalated to L3 · grievance officer " + dpo().n, "Privacy category routes straight here", "crit"]);
    if (x.st === "done") tlv.push(["Resolved · patient confirmed", x.sla, "ok"]);
    var h = ui.li(ui.av(p.name, "lg"), esc(NB.pname(p)), esc(p.mrn) + " · " + esc(x.cat) + " · " + esc(x.ch), ui.pill(s[0], s[1], s[2])) +
      '<div class="card" style="padding:14px"><p class="gr-quote">“' + esc(x.txt) + "”</p></div>" +
      kv([["SLA", esc(x.sla)], ["Level", "L" + x.lvl + " · " + esc(x.who)], ["Category", esc(x.cat)], ["Privacy law", x.cat === "Privacy" ? esc(m.privacyLaw) + " grievance · logged for the regulator" : "Not a privacy matter"]]) +
      '<div class="tl">' + tlv.map(function (t) { return '<div class="ev"><span class="dotc ' + t[2] + '">' + ui.icon(t[2] === "ok" ? "check" : t[2] === "warn" ? "alertcircle" : t[2] === "crit" ? "alertfill" : "dot", "sm") + '</span><div class="bd"><b>' + esc(t[0]) + "</b><small>" + esc(t[1]) + "</small></div></div>"; }).join("") + "</div>";
    if (x.cat === "Privacy") h += ui.note("crit", "Possible personal-data breach: the wrong patient's link was sent. The link was revoked at 12 Oct 18:04. Assess under the breach runbook in Audit & compliance; the grievance officer decides whether it is reportable.");
    if (x.cat === "Billing") h += ui.note("info", "Price check: last visit with the same doctor was 5 days earlier, inside the 7-day follow-up window. The follow-up price should have applied. Refund the difference of " + M(head("CON").base - head("CON").fu) + ".");
    h += '<div class="fgrid">' + ui.field("Reply to patient (WhatsApp)", x.st === "done" ? "" : "We're sorry about this. " + (x.cat === "Billing" ? "We've refunded the difference to your original payment method." : "We're looking into it and will update you within 24 hours."), { area: true, full: true, id: "gr-gr" }) +
      ui.field("Root cause", "", { ph: "e.g. follow-up rule not applied by the desk", id: "gr-rc" }) + ui.field("Outcome", "Refund / apology / process change", { options: ["Refund / apology / process change", "Apology", "No fault found", "Referred to regulator"], id: "gr-out" }) + "</div>";
    return { kind: "drawer", title: x.id + " · " + esc(x.cat), body: h,
      foot: ui.btn("Escalate", { cls: "ghost", icon: "arrowright", act: "toast", arg: x.id + " escalated to the next level · patient told who is handling it" }) + ui.btn("Send reply", { icon: "send", act: "toast", arg: "Reply sent on WhatsApp" }) + ui.btn("Mark resolved", { cls: "pri", icon: "check", act: "toast", arg: x.id + " resolved · patient asked to confirm" }) };
  });

  /* ================= owner.bookingpage ================= */
  var BRANDS = [["acc", "Teal"], ["info", "Ocean"], ["sync", "Indigo"], ["sub", "Plum"], ["ok", "Forest"], ["urg", "Terracotta"]];
  function qr(seed) {
    var n = 25, cells = "", r = seed || 7;
    var fin = function (x, y) { return (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7); };
    var finOn = function (x, y) { var fx = x >= n - 7 ? x - (n - 7) : x, fy = y >= n - 7 ? y - (n - 7) : y; return fx === 0 || fx === 6 || fy === 0 || fy === 6 || (fx >= 2 && fx <= 4 && fy >= 2 && fy <= 4); };
    for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) {
      var on; if (fin(x, y)) on = finOn(x, y); else { r = (r * 9301 + 49297) % 233280; on = r / 233280 > 0.52; }
      if (on) cells += '<rect x="' + x + '" y="' + y + '" width="1" height="1"/>';
    }
    return '<div class="gr-qr" role="img" aria-label="QR code to the booking page"><svg viewBox="0 0 ' + n + " " + n + '" shape-rendering="crispEdges">' + cells + "</svg></div>";
  }
  function phonePreview(b) {
    var docs = ["d2", "d1", "d3"], w = [[22, 30], [9, 14], [14, 20]];
    return '<div class="gr-phone gr-b-' + b + '"><div class="bar"></div><div class="hd"><span class="gr-bmark sm">' + esc(clinicName().slice(0, 1)) + '</span><div class="fl"><b>' + esc(clinicName()) + "</b><small>" + esc(facs()[0].area) + "</small></div></div>" +
      '<div class="bd"><b style="font-size:14px">Book a visit</b>' + docs.map(function (d, i) {
        return '<div class="dr">' + ui.av(NB.doc(d).name) + '<div class="t"><b>' + esc(dn(d)) + "</b><small>" + esc(NB.doc(d).spec) + ' · wait <span class="ltr">' + w[i][0] + "–" + w[i][1] + '</span> min</small></div><span class="gr-pb gr-bbtn">Book</span></div>';
      }).join("") + '</div><div class="ft">Powered by nabd</div></div>';
  }
  NB.screen("owner.bookingpage", function (c) {
    var b = brandOf(), wm = NB.v("grWaMode", "own"), useDom = NB.v("grDomR", "dom") === "dom";
    var url = useDom ? ownDomain() : slug() + ".nabd.health";
    var h = ui.header(L("Booking page & widget", "صفحة الحجز والأداة", "बुकिंग पेज और विजेट"), "Your own booking channel, in your name · bookings land in the same live queue · no commission on your own channels",
      ui.btn(L("Copy link", "نسخ الرابط", "लिंक कॉपी करें"), { icon: "copy", act: "toast", arg: "Copied https://" + url }) + ui.btn(L("Open live page", "فتح الصفحة", "लाइव पेज खोलें"), { cls: "pri", icon: "globe", go: "connect.branded" }));
    h += '<div class="split wide"><div class="stack s16">';
    h += ui.card(L("Address", "العنوان", "पता"), ui.radio("grDomR", "sub", "Free Nabd subdomain", slug() + ".nabd.health · live now", useDom ? "dom" : "sub") +
      '<div style="height:8px"></div>' + ui.radio("grDomR", "dom", "Your own domain", ownDomain() + " · CNAME to booking.nabd.health", useDom ? "dom" : "sub") +
      '<div class="row" style="margin-top:12px">' + ui.pill("ok", "DNS verified", "checkcircle") + ui.pill("ok", "HTTPS certificate active", "lock") + '<span class="small muted">Checked 2 h ago</span></div>', { icon: "globe" });
    h += ui.card(L("Look", "المظهر", "रूप"), '<div class="stack s16"><div class="row nw"><span class="gr-bmark gr-b-' + b + '">' + esc(clinicName().slice(0, 1)) + '</span><div class="fl"><b class="small">Logo</b><div class="small muted">suvidha-logo.svg · 18 KB · shown on the page, receipts and WhatsApp</div></div>' + ui.btn("Replace", { cls: "sm ghost", icon: "upload", act: "toast", arg: "Upload a PNG or SVG logo" }) + "</div>" +
      '<div class="stack s8"><b class="small">Colour · accessible presets only</b><div class="gr-sw">' + BRANDS.map(function (x) {
        return '<button class="gr-swb gr-b-' + x[0] + (b === x[0] ? " on" : "") + '" data-set="grBrand" data-val="' + x[0] + '" aria-pressed="' + (b === x[0]) + '"><i></i>' + esc(x[1]) + "</button>";
      }).join("") + '</div><span class="small muted">Every preset passes WCAG AA for text and buttons in light and dark mode, so the page stays readable for older patients. Free colour picking is not offered.</span></div>' +
      ui.toggle("grShowWait", "Show the live wait per doctor", "As a range, e.g. 15–20 min; updates every 2 minutes", true) + ui.toggle("grShowFee", "Show consultation fees", "", true) +
      '<div class="stack s8"><b class="small">Languages</b><div class="chips">' + (gcc() ? ["العربية", "English", "اردو"] : ["English", "हिंदी", "ಕನ್ನಡ", "தமிழ்"]).map(function (x, i) { return ui.pill(i < 2 ? "acc" : "mute", x, i < 2 ? "check" : "plus"); }).join("") + "</div></div></div>", { icon: "edit" });
    h += ui.card(L("Website widget", "أداة الموقع", "वेबसाइट विजेट"), '<p class="small muted" style="margin:0 0 10px">Paste once into your website. It shows a “Book a visit” button that opens the booking flow in your colours.</p>' +
      '<pre class="gr-code">' + esc('<script src="https://book.nabd.health/w.js"\n  data-clinic="' + slug() + '" data-lang="auto" data-colour="' + b + '" async></script>') + "</pre>" +
      '<div class="btns" style="margin-top:10px">' + ui.btn("Copy code", { cls: "sm", icon: "copy", act: "toast", arg: "Widget code copied" }) + ui.btn("WordPress plug-in", { cls: "sm ghost", icon: "download", act: "toast", arg: "Plug-in downloaded" }) + "</div>", { icon: "monitor", right: ui.pill("ok", "Seen on suvidhaclinic.in", "checkcircle") });
    h += '<div class="grid g2">' + ui.card("Google Business “Book” button", '<div class="stack s8">' + ui.pill("ok", "Linked to Google Business Profile", "checkcircle") + '<span class="small muted">The “Book online” link on Google Search and Maps opens your page with source tag <span class="gr-mono">utm_source=google</span>.</span>' + ui.btn("Re-check", { cls: "sm ghost", icon: "refresh", act: "toast", arg: "Google Business link is working" }) + "</div>", { icon: "map" }) +
      ui.card("QR poster", '<div class="row nw" style="align-items:flex-start">' + qr(11) + '<div class="stack s8"><span class="small muted">For the reception desk, lift and pharmacy counter. Scanning opens WhatsApp booking or this page.</span>' + ui.btn("Print A4", { cls: "sm", icon: "printer", act: "toast", arg: "A4 poster sent to the printer" }) + ui.btn("Print A5 table tent", { cls: "sm ghost", icon: "printer", act: "toast", arg: "A5 table tent sent to the printer" }) + "</div></div>", { icon: "qr" }) + "</div>";
    h += ui.card(L("WhatsApp number on the page", "رقم واتساب في الصفحة", "पेज पर व्हाट्सऐप नंबर"), '<div class="gr-cmp">' + ui.radio("grWaMode", "own", "Clinic's own number · " + waNum(0), "Your name and logo in chat; your own quality rating", "own") + ui.radio("grWaMode", "shared", "Shared Nabd number", "Shows “" + clinicName() + " via Nabd”; nothing to set up", "own") + "</div>" +
      '<p class="small muted" style="margin:10px 0 0">' + (wm === "own" ? "Patients who tap “Book on WhatsApp” chat with your number." : "Switching to your own number later keeps every conversation.") + "</p>", { icon: "chat" });
    h += "</div><div class=\"stack s16\">" + ui.card(L("Preview", "معاينة", "प्रीव्यू"), phonePreview(b) + '<div class="btns" style="margin-top:12px;justify-content:center">' + ui.btn("Open full page", { cls: "sm", icon: "globe", go: "connect.branded" }) + "</div>", { icon: "smartphone" });
    var S = [["Own booking page", 382], ["Website widget", 258], ["Google “Book”", 210], ["QR poster", 96], ["WhatsApp (own number)", 1840], ["Nabd Connect listing", 380]];
    h += ui.card(L("Where bookings come from · 30 days", "مصادر الحجوزات", "बुकिंग कहाँ से आती हैं"), CH().hbars(S.map(function (s) { return [s[0], s[1], nf(s[1])]; })) +
      '<p class="small muted" style="margin:12px 0 0">Own channels (everything except Nabd Connect) never pay a commission. Nabd Connect is an optional free listing.</p>' +
      '<div class="btns" style="margin-top:10px">' + ui.btn("Source report", { cls: "sm ghost", icon: "chart", act: "grRepGo", arg: "src" }) + "</div>", { icon: "chart" }) + "</div></div>";
    return h;
  });
  NB.act("grRepGo", function (a) { NB.state.ui.grRep = a; NB.go("owner", "reports"); });

  /* ================= owner.payouts ================= */
  function stmt(d) {
    var r = G.share.find(function (x) { return x.d === d; }) || G.share[0], s = G.stm[d] || [0, 0, 0, 0];
    var gross = s[1] + s[3], share = 0, lines = [];
    if (r.model === "pct" || r.model === "mix") {
      var base = d === "d3" ? Math.max(0, s[1] * (s[0] - 300) / s[0]) : s[1];
      lines.push(["Consult fees" + (d === "d3" ? " above 300 visits" : ""), base, Math.round(r.c * 100) + "%", base * r.c]);
      if (r.p) lines.push(["Procedures", s[3], Math.round(r.p * 100) + "%", s[3] * r.p]);
    }
    if (r.model === "mix") lines.push(["Echo (per procedure)", s[2], "× " + RAW(Math.round(r.per * mk().fx)).replace(/<[^>]+>/g, ""), s[2] * r.per]);
    if (r.model === "fixed") lines.push(["OPD sessions", s[0], "× " + RAW(Math.round(r.sess * mk().fx)).replace(/<[^>]+>/g, ""), s[0] * r.sess]);
    lines.forEach(function (l) { share += l[3]; });
    var tds = r.tds && mid() === "india" ? Math.round(share * 0.1) : 0, adv = d === "d4" ? 5000 : 0;
    return { r: r, s: s, gross: gross, share: Math.round(share), tds: tds, adv: adv, net: Math.round(share - tds - adv), lines: lines };
  }
  NB.screen("owner.payouts", function (c) {
    var tab = NB.v("grPoTab", "rules"), m = c.m;
    var h = ui.header(L("Doctor payouts & referrals", "مستحقات الأطباء والإحالات", "डॉक्टर भुगतान और रेफ़रल"), "Revenue share for partners and visiting consultants, payout statements, and referral tracking",
      ui.btn(L("Export to accounts", "تصدير للحسابات", "अकाउंट्स में एक्सपोर्ट"), { icon: "download", act: "toast", arg: pick({ india: "Payout journal exported for Tally (XML)", uae: "Payout journal exported (CSV) · bank file ready", ksa: "Payout journal exported (CSV) · SARIE bank file ready", qatar: "Payout journal exported (CSV)" }) }));
    h += ui.tabs("grPoTab", [["rules", L("Share rules", "قواعد التقاسم", "शेयर नियम"), "percent"], ["stm", L("Statements", "الكشوف", "स्टेटमेंट"), "file"], ["ref", L("Referring doctors", "الأطباء المحيلون", "रेफ़र करने वाले डॉक्टर"), "share"]]);
    if (tab === "rules") {
      h += ui.table([{ h: "Doctor" }, { h: "Arrangement" }, { h: "Rule" }, { h: "Withholding" }, { h: "Cycle" }, { h: "" }], G.share.map(function (r) {
        var md = { sal: ["mute", "Salary"], pct: ["info", "% of fee"], mix: ["sub", "% + per procedure"], fixed: ["acc", "Fixed per session"] }[r.model];
        return [ui.cellp(esc(dn(r.d)), esc(NB.doc(r.d).spec)), esc(r.type), '<div class="stack s4">' + ui.pill(md[0], md[1]) + '<span class="small muted">' + esc(r.desc) + (r.per ? " " + M(r.per) : r.sess ? " · " + M(r.sess) + " each" : "") + "</span></div>",
          r.tds && mid() === "india" ? ui.pill("info", "TDS 10% · s.194J") : '<span class="small muted">' + (mid() === "india" ? "Through payroll" : "None") + "</span>", r.model === "sal" ? "Monthly payroll" : "Monthly, 5th", ui.btn("Edit", { cls: "sm ghost", icon: "edit", act: "toast", arg: "Rule changes apply from the next cycle and are logged" })];
      }), { min: 960 });
      h += '<div class="grid g2">' + ui.card("How the share is calculated", '<div class="gr-cks">' + ck("grPoColl", "On amounts collected, not just billed", "Unpaid dues are shared when they are paid", true) + ck("grPoRef", "Refunds reverse the share in the same cycle", "", true) + ck("grPoPkg", "Package money is shared when the session is delivered", "Not at the time of sale", true) + ck("grPoTax", "Exclude " + m.tax + " and discounts the doctor approved", "", true) + "</div>", { icon: "sliders" }) +
        ui.card("This cycle · 1–15 Oct", kv([["Doctors on share", num(G.share.filter(function (r) { return r.model !== "sal"; }).length)], ["Total share", M(["d1", "d3", "d4", "d5"].reduce(function (s, d) { return s + stmt(d).share; }, 0))], ["Withholding", mid() === "india" ? M(["d1", "d3", "d4", "d5"].reduce(function (s, d) { return s + stmt(d).tds; }, 0)) : "None"], ["Statements due", "5 Nov"]]), { icon: "wallet" }) + "</div>";
    } else if (tab === "stm") {
      var ds = ["d1", "d3", "d4", "d5"], sts = { d1: ["ok", "Approved"], d3: ["warn", "Draft"], d4: ["warn", "Doctor querying"], d5: ["info", "Paid 05 Oct"] };
      h += '<div class="row sb">' + ui.seg("grPoPer", [["oct1", "1–15 Oct"], ["sep", "September"], ["aug", "August"]], "oct1") + ui.btn("Approve all drafts", { cls: "sm pri", icon: "check", act: "toast", arg: "Drafts approved · statements sent to doctors on WhatsApp" }) + "</div>";
      h += ui.table([{ h: "Doctor" }, { h: "Visits", cls: "r" }, { h: "Procedures", cls: "r" }, { h: "Attributable", cls: "r" }, { h: "Share", cls: "r" }, { h: "Deductions", cls: "r" }, { h: "Net payable", cls: "r" }, { h: "Status" }], ds.map(function (d) {
        var s = stmt(d);
        return { ov: "payoutstmt", arg: d, cells: [ui.cellp(esc(dn(d)), esc(s.r.type)), num(s.s[0]), num(s.s[2]), M(s.gross), M(s.share), s.tds + s.adv ? "−" + M(s.tds + s.adv) : '<span class="small muted">—</span>', "<b>" + M(s.net) + "</b>", ui.pill(sts[d][0], sts[d][1])] };
      }), { min: 940 });
      h += ui.note("info", "Statements show every visit and procedure behind the number. Doctors can query a line from WhatsApp; the owner sees the query here.");
    } else {
      var RD = pick({ india: [["Dr. Anil Kumar", "Apollo Clinic, HSR Layout", 14, 22, 186000], ["Dr. Revathi Iyer", "Arogya Family Clinic", 9, 11, 64000], ["Dr. Suresh Gowda", "Sanjeevani Clinic", 6, 9, 92000]],
        uae: [["Dr. Omar Haddad", "City Care Clinic", 14, 22, 186000], ["Dr. Layla Nasser", "Marina Family Clinic", 9, 11, 64000], ["Dr. Sami Aziz", "Deira Medical Centre", 6, 9, 92000]],
        ksa: [["Dr. Omar Haddad", "Dallah Clinics, Olaya", 14, 22, 186000], ["Dr. Layla Nasser", "Al Rawdah Family Clinic", 9, 11, 64000], ["Dr. Sami Aziz", "Al Malqa Medical Centre", 6, 9, 92000]],
        qatar: [["Dr. Omar Haddad", "Al Sadd Medical Centre", 14, 22, 186000], ["Dr. Layla Nasser", "West Bay Family Clinic", 9, 11, 64000], ["Dr. Sami Aziz", "Al Wakrah Clinic", 6, 9, 92000]] });
      h += ui.note("warn", "<b>Referral tracking only; payouts need compliance review.</b> " + pick({ india: "NMC's professional-conduct rules forbid fee-splitting and commissions for referring patients. Nabd records who referred whom so you can thank and report back; any payment to a referring doctor is locked until your compliance adviser approves the arrangement.", uae: "DHA / DoH rules prohibit paying for patient referrals. Nabd records referrals for reporting back; payments are locked until a compliance review approves an arrangement.", ksa: "MOH and SCFHS ethics rules prohibit paying for patient referrals. Nabd records referrals for reporting back; payments are locked until a compliance review approves an arrangement.", qatar: "MOPH and QCHP ethics rules prohibit paying for patient referrals. Nabd records referrals for reporting back; payments are locked until a compliance review approves an arrangement." }));
      h += ui.table([{ h: "Referring doctor" }, { h: "Clinic" }, { h: "Patients", cls: "r" }, { h: "Visits", cls: "r" }, { h: "Billed", cls: "r" }, { h: "Report back" }, { h: "Payout" }], RD.map(function (r, i) {
        return [esc(r[0]), esc(r[1]), num(r[2]), num(r[3]), cm(r[4]), i === 1 ? ui.pill("warn", "2 letters due", "clockh") : ui.pill("ok", "Up to date"), ui.pill("perm", "Locked · compliance review", "lock")];
      }), { min: 860 });
      h += '<div class="btns">' + ui.btn("Send report-back letters", { cls: "sm", icon: "send", act: "toast", arg: "Consult summaries sent to referring doctors where the patient consented" }) + ui.btn("Request compliance review", { cls: "sm ghost", icon: "shieldcheck", act: "toast", arg: "Review request sent to your compliance adviser with the arrangement details" }) + "</div>";
    }
    return h;
  });
  NB.overlay("payoutstmt", function (d, c) {
    var s = stmt(d || "d1"), m = c.m;
    var h = ui.li(ui.av(NB.doc(d).name, "lg"), esc(dn(d)), esc(s.r.type) + " · " + esc(s.r.desc), ui.pill("info", "1–15 Oct 2026"));
    h += ui.table([{ h: "Line" }, { h: "Base", cls: "r" }, { h: "Rate" }, { h: "Share", cls: "r" }], s.lines.map(function (l) { return [esc(l[0]), typeof l[1] === "number" && l[1] > 100 ? M(l[1]) : num(l[1]), '<span class="num ltr">' + esc(l[2]) + "</span>", M(l[3])]; }), { min: 460 });
    h += kv([["Share", M(s.share)]].concat(s.tds ? [["TDS 10% (s.194J)", "−" + M(s.tds)]] : []).concat(s.adv ? [["Advance recovered", "−" + M(s.adv)]] : []).concat([["Net payable", "<b>" + M(s.net) + "</b>"], ["Pay to", pick({ india: "HDFC •••• 4417 · NEFT", uae: "Emirates NBD •••• 2210", ksa: "Al Rajhi •••• 0921 · SARIE", qatar: "QNB •••• 6630" })], ["Due", "5 Nov 2026"]]));
    if (d === "d4") h += ui.note("warn", "Dr. Khalid queried 2 echo procedures on 13 Oct that were billed under Dr. Vikram Shah. Re-attribute or reply before approving.");
    h += ui.note("info", "Every line links to the visit or procedure it came from. Refunds in this cycle are already netted off.");
    return { kind: "drawer", title: "Payout statement · " + esc(dn(d)), body: h,
      foot: ui.btn("PDF", { cls: "ghost", icon: "download", act: "toast", arg: "Statement PDF downloaded" }) + ui.btn("Send to doctor", { icon: "send", act: "toast", arg: "Statement sent to " + dn(d) + " on WhatsApp" }) + ui.btn("Approve", { cls: "pri", icon: "check", act: "toast", arg: "Approved · added to the " + (mid() === "india" ? "NEFT" : "bank") + " file for 5 Nov" }) };
  });

  /* ================= owner.hr ================= */
  NB.screen("owner.hr", function (c) {
    var tab = NB.v("grHrTab", "shifts"), m = c.m, S = G.staff;
    var h = ui.header(L("Shifts, attendance & payroll", "المناوبات والحضور والرواتب", "शिफ़्ट, उपस्थिति और पेरोल"), "For small teams: split shifts, simple check-in, leave, and a payroll file for your accountant · no payroll engine",
      ui.btn(L("Export payroll", "تصدير الرواتب", "पेरोल एक्सपोर्ट"), { cls: "pri", icon: "download", act: "grHrTab", arg: "payroll" }));
    h += ui.tabs("grHrTab", [["shifts", L("Shifts", "المناوبات", "शिफ़्ट"), "calendar"], ["att", L("Attendance", "الحضور", "उपस्थिति"), "clockh"], ["leave", L("Leave", "الإجازات", "छुट्टी"), "sun"], ["payroll", L("Payroll export", "تصدير الرواتب", "पेरोल एक्सपोर्ट"), "download"], ["lic", L("Staff licences", "تراخيص الموظفين", "स्टाफ़ लाइसेंस"), "scroll"]]);
    if (tab === "shifts") {
      var days = gcc() ? ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"] : ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
      var off = gcc() ? [5] : [6];
      h += '<div class="row sb"><span class="small muted">Week of 19 Oct · ' + (gcc() ? "weekend " + (mid() === "uae" ? "Sat–Sun" : "Fri–Sat") : "Sunday closed") + "</span>" + (gcc() ? ui.seg("grHrRam", [["", "Normal roster"], ["1", "Ramadan roster"]], "") : "") + "</div>";
      var ram = gcc() && NB.v("grHrRam", "") === "1";
      h += '<div class="tw"><div class="gr-rost"><div class="h">Staff</div>' + days.map(function (d) { return '<div class="h">' + d + "</div>"; }).join("") + S.map(function (s, si) {
        return '<div><b style="display:block;font-size:13px">' + esc(s.n) + '</b><span class="small muted">' + esc(s.r) + "</span></div>" + days.map(function (d, di) {
          if (off.indexOf(di) >= 0 || (s.n === "Rahul Joshi" && di < 3)) return '<div><span class="gr-sh ' + (s.n === "Rahul Joshi" && di < 3 ? "lv" : "off") + '">' + (s.n === "Rahul Joshi" && di < 3 ? "Leave" : "Off") + "</span></div>";
          var sh = ram ? ["10–16"] : s.sh;
          return '<div><span class="gr-sh ' + (ram ? "rm" : sh.length > 1 ? "split" : "") + '"><span class="ltr">' + sh.join("</span><span class=\"ltr\">") + "</span></span></div>";
        }).join("");
      }).join("") + "</div></div>";
      h += '<div class="gr-leg"><span><i class="op"></i>Single shift</span><span><i style="background:var(--info-t);border:1px solid var(--info)"></i>Split shift</span><span><i class="rm"></i>Leave / Ramadan</span></div>';
      h += ui.note("info", "Split shifts (e.g. 09–14 and 17–21) are normal here and count as one working day. Shift changes are sent to the staff member on WhatsApp." + (gcc() ? " The Ramadan roster switches on with the clinic's Ramadan hours." : ""));
    } else if (tab === "att") {
      var AT = { ok: ["ok", "On time", "checkcircle"], late: ["warn", "Late 14 min", "clockh"], leave: ["sub", "On leave", "sun"], absent: ["crit", "Absent", "alertfill"] };
      h += '<div class="gr-mini">' + [[S.filter(function (s) { return s.st === "ok" || s.st === "late"; }).length + " / " + S.length, "checked in today"], ["1", "late"], ["1", "on leave"], ["1", "absent · not informed"]].map(function (x) { return '<div class="card"><b>' + x[0] + "</b><span>" + esc(x[1]) + "</span></div>"; }).join("") + "</div>";
      h += ui.table([{ h: "Staff" }, { h: "Shift" }, { h: "Check-in" }, { h: "Method" }, { h: "Status" }, { h: "Hours today", cls: "r" }], S.map(function (s) {
        var a = AT[s.st];
        return [ui.cellp(esc(s.n), esc(s.r)), '<span class="num ltr">' + esc(s.sh.join(" · ")) + "</span>", '<span class="num">' + esc(s.in) + "</span>", '<span class="small">' + esc(s.m) + "</span>", ui.pill(a[0], a[1], a[2]), num(s.h ? s.h.toFixed(1) : "—")];
      }), { min: 820 });
      h += '<div class="grid g3">' + [["smartphone", "Staff app", "Check in from the phone inside the clinic's geo-fence; works on 3G", "ok", "In use"], ["key", "Desk PIN", "4-digit PIN on the reception PC for staff without smartphones", "ok", "In use"], ["fingerprint", "Biometric device", "Optional connector for an existing fingerprint reader; not needed", "perm", "Not connected"]].map(function (x) {
        return ui.card(x[1], '<div class="row nw" style="align-items:flex-start">' + ii(x[0], x[3] === "ok" ? "ok" : "") + '<span class="small muted fl">' + esc(x[2]) + "</span></div>" + '<div style="margin-top:10px">' + ui.pill(x[3], x[4], x[3] === "perm" ? "lock" : "checkcircle") + "</div>");
      }).join("") + "</div>";
    } else if (tab === "leave") {
      h += ui.table([{ h: "Staff" }, { h: "Type" }, { h: "From" }, { h: "To" }, { h: "Days", cls: "r" }, { h: "Impact" }, { h: "Status" }], G.leave.map(function (l) {
        return [esc(l.n), esc(l.t), '<span class="num">' + esc(l.from) + "</span>", '<span class="num">' + esc(l.to) + "</span>", num(l.days), '<span class="small gr-wrap">' + esc(l.note) + "</span>",
          l.st === "ok" ? ui.pill("ok", "Approved") : '<div class="btns">' + ui.btn("Approve", { cls: "sm okb", icon: "check", act: "toast", arg: "Leave approved · " + (l.n.indexOf("Dr.") === 0 ? "38 patients offered another doctor or date on WhatsApp" : "roster updated") }) + ui.btn("Decline", { cls: "sm ghost", act: "toast", arg: "Declined with a note to " + l.n }) + "</div>"];
      }), { min: 960 });
      h += ui.card("Leave balances · 2026", CH().hbars(S.slice(0, 5).map(function (s, i) { var left = [9, 11, 7, 4, 12][i]; return [s.n, left, left + " of 12 days left"]; })), { icon: "sun" });
    } else if (tab === "payroll") {
      var fmt = pick({ india: [["csv", "CSV for accountant"], ["tally", "Tally (XML)"], ["zoho", "Zoho Payroll"]], uae: [["csv", "CSV for accountant"], ["wps", "WPS SIF file"]], ksa: [["csv", "CSV for accountant"], ["mudad", "Mudad WPS file"]], qatar: [["csv", "CSV for accountant"], ["wps", "WPS (QCB) file"]] });
      h += '<div class="row sb"><div class="row">' + ui.seg("grPrMo", [["oct", "October 2026"], ["sep", "September 2026"]], "sep") + "</div>" + ui.seg("grPrFmt", fmt, "csv") + "</div>";
      h += ui.table([{ h: "Staff" }, { h: "Role" }, { h: "Days worked", cls: "r" }, { h: "Hours", cls: "r" }, { h: "Overtime h", cls: "r" }, { h: "Leave", cls: "r" }, { h: "Salary", cls: "r" }, { h: "Advance / deduction", cls: "r" }], S.map(function (s) {
        return [esc(s.n), esc(s.r), num(s.d), num(Math.round(s.d * (s.sh.length > 1 ? 8.5 : 8))), num(s.ot), num(s.lv), M(s.sal), s.adv ? "−" + M(s.adv) : '<span class="small muted">—</span>'];
      }), { min: 900 });
      h += ui.note("info", pick({
        india: "One click gives your accountant a monthly file: days, hours, overtime, leave, salary and advances. PF, ESI and TDS are worked out by your accountant or payroll provider; Nabd does not run payroll.",
        uae: "The WPS SIF file is formatted for your bank or exchange house under MOHRE rules. Nabd exports hours and fields; salary processing stays with your payroll provider.",
        ksa: "GOSI contributions are calculated by your accountant from the contribution base; this export carries the fields they need. The Mudad WPS file format is available for upload.",
        qatar: "The WPS file follows the Qatar Central Bank format. Nabd exports hours and fields; salary processing stays with your bank or payroll provider."
      }));
      h += '<div class="btns">' + ui.btn("Export " + fmt.find(function (f) { return f[0] === NB.v("grPrFmt", "csv"); })[1], { cls: "pri", icon: "download", act: "toast", arg: "Payroll export for September ready · sent to your accountant's email" }) + ui.btn("Lock September", { icon: "lock", act: "toast", arg: "September attendance locked · changes now need owner approval" }) + "</div>";
    } else {
      var SL = licences().filter(function (x) { return x.kind !== "fac"; }).concat([{ w: "Anita D'Souza", k: gcc() ? "Nursing licence" : "Nursing registration", no: gcc() ? pick({ uae: "DHA-N-55102", ksa: "SCFHS-N-55102", qatar: "QCHP-N-55102" }) : "KNC-55102", by: gcc() ? m.regulator.split(" / ")[0] : "Karnataka Nursing Council", exp: "2027-04-30", d: 195, kind: "staff" }, { w: "Kiran Rao", k: "Lab technologist registration", no: gcc() ? "LT-31008" : "KPMC-LT-31008", by: gcc() ? m.regulator.split(" / ")[0] : "Karnataka Paramedical Board", exp: "2026-12-05", d: 49, kind: "staff" }]);
      h += ui.table([{ h: "Person" }, { h: "Licence" }, { h: "Number" }, { h: "Expiry" }, { h: "Status" }, { h: "Reminders" }, { h: "When it lapses" }], SL.sort(function (a, b) { return a.d - b.d; }).map(function (x) {
        return [esc(x.w), ui.cellp(esc(x.k), esc(x.by)), '<span class="gr-mono">' + esc(x.no) + "</span>", '<span class="num gr-nw">' + esc(gd(x.exp)) + "</span>", daysPill(x.d), remPill(x.d), x.kind === "doc" ? ui.pill("sub", "Hidden from online booking", "eye") : '<span class="small muted">Owner alert</span>'];
      }), { min: 1000 });
      h += ui.note("info", "Reminders go to the person and the owner at 60, 30 and 7 days. When a doctor's licence lapses they are hidden from online booking until a new expiry date is entered.");
    }
    return h;
  });

  /* ================= connect.branded (clinic's own booking site) ================= */
  NB.screen("connect.branded", function (c) {
    var b = brandOf(), f = facs()[0], g = gcc();
    var D = [["d2", 0, [22, 30], "10:40", 600], ["d1", 1, [9, 14], "11:40", 700], ["d3", 2, [14, 20], "11:00", 650], ["d5", 3, [15, 22], "12:40", 800]];
    var h = '<div class="gr-site gr-b-' + b + '"><div class="gr-snav"><span class="gr-bmark">' + esc(f.name.slice(0, 1)) + '</span><div class="nm"><b>' + esc(f.name) + "</b><small>" + esc(f.area) + '</small></div><div class="sp"></div>' +
      ui.btn(L("Call", "اتصال", "कॉल करें"), { cls: "ghost hide-s", icon: "phone", act: "toast", arg: waNum(0) }) +
      '<button class="btn sm" data-act="ptCxWa">' + ui.icon("chat", "sm") + esc(L("WhatsApp", "واتساب", "व्हाट्सऐप")) + "</button></div>";
    h += '<div class="gr-sbody">';
    h += '<div class="gr-hero br"><div class="eyebrow">' + ui.icon("lock", "sm") + '<span class="ltr" style="text-transform:none;letter-spacing:0">' + esc(ownDomain()) + "</span></div><h1>" + esc(L("Book a visit at " + f.name, "احجز زيارة في " + f.name, f.name + " में विज़िट बुक करें")) + "</h1><p>" +
      esc(L("See how long each doctor is running right now, pick a time, and get a WhatsApp message when it's time to leave home.", "شاهد مدة انتظار كل طبيب الآن، واختر وقتًا، وستصلك رسالة واتساب عندما يحين وقت الخروج من المنزل.", "देखें कि अभी हर डॉक्टर कितनी देर से चल रहे हैं, समय चुनें, और घर से निकलने का समय होने पर व्हाट्सऐप संदेश पाएँ।")) + "</p>" +
      '<div class="chips">' + ui.pill("ok", L("Open now · until 21:00", "مفتوح الآن · حتى 21:00", "अभी खुला · 21:00 तक")) + '<span class="pill mute" style="text-transform:none;letter-spacing:0"><span class="gr-stars">★</span> 4.7 · 1,284 Google reviews</span></div>' +
      '<div class="btns"><button class="btn lg gr-bbtn" data-ov="cxbook" data-arg="0">' + ui.icon("calendar", "sm") + esc(L("Book an appointment", "احجز موعدًا", "अपॉइंटमेंट बुक करें")) + "</button>" +
      '<button class="btn lg" data-act="ptCxWa">' + ui.icon("chat", "sm") + esc(L("Book on WhatsApp", "احجز عبر واتساب", "व्हाट्सऐप पर बुक करें")) + "</button></div></div>";
    h += ui.card(L("Doctors · live wait", "الأطباء · الانتظار المباشر", "डॉक्टर · लाइव इंतज़ार"), '<div class="lst">' + D.map(function (d) {
      var doc = NB.doc(d[0]);
      return ui.li(ui.av(doc.name, "lg"), esc(dn(d[0])), esc(doc.spec) + " · " + esc(L("next", "التالي", "अगला")) + ' <span class="ltr">' + d[3] + "</span> · " + M(d[4]),
        '<div class="row nw"><span class="gr-live small"><span class="pulse"></span><span class="ltr">' + d[2][0] + "–" + d[2][1] + "</span> " + esc(L("min", "د", "मि")) + '</span><button class="btn sm gr-bbtn" data-ov="cxbook" data-arg="' + d[1] + '">' + esc(L("Book", "احجز", "बुक")) + "</button></div>");
    }).join("") + '</div><p class="small muted" style="margin:10px 0 0">' + esc(L("Waits come from the clinic's live queue and update every 2 minutes.", "أوقات الانتظار من طابور العيادة المباشر وتتحدث كل دقيقتين.", "इंतज़ार क्लिनिक की लाइव क़तार से आता है और हर 2 मिनट में अपडेट होता है।")) + "</p>", { icon: "activity", right: '<span class="pill sync"><span class="pulse"></span>' + esc(L("Live", "مباشر", "लाइव")) + "</span>" });
    h += '<div class="grid g2">' + ui.card(L("How your visit works", "كيف تتم زيارتك", "आपकी विज़िट कैसे होगी"), '<div class="tl">' + [
      ["calendar", L("Pick a doctor and time", "اختر الطبيب والوقت", "डॉक्टर और समय चुनें"), L("Or ask for the first free doctor", "أو اطلب أول طبيب متاح", "या पहले ख़ाली डॉक्टर को चुनें")],
      ["lock", L("Confirm with a code on WhatsApp", "أكد برمز عبر واتساب", "व्हाट्सऐप कोड से पुष्टि करें"), L("No account or password", "بدون حساب أو كلمة مرور", "कोई अकाउंट या पासवर्ड नहीं")],
      ["hourglass", L("Get a “leave home now” message", "تصلك رسالة «اخرج الآن»", "“अभी निकलें” संदेश पाएँ"), L("Timed to the doctor's real queue", "حسب طابور الطبيب الفعلي", "डॉक्टर की असली क़तार के हिसाब से")],
      ["qr", L("Show the QR at the desk", "اعرض الرمز عند الاستقبال", "डेस्क पर QR दिखाएँ"), L("Or tap “I've arrived” in the chat", "أو اضغط «وصلت» في المحادثة", "या चैट में “मैं पहुँच गया” दबाएँ")]
    ].map(function (x) { return '<div class="ev"><span class="dotc acc">' + ui.icon(x[0], "sm") + '</span><div class="bd"><b>' + esc(x[1]) + "</b><small>" + esc(x[2]) + "</small></div></div>"; }).join("") + "</div>", { icon: "info" }) +
      ui.card(L("Timings & location", "المواعيد والموقع", "समय और स्थान"), kv(g ? [[L("Sat – Thu", "السبت – الخميس", "शनि – गुरु"), '<span class="num ltr">09:00–13:00 · 16:30–22:00</span>'], [L("Friday", "الجمعة", "शुक्रवार"), '<span class="num ltr">16:30–22:00</span>'], [L("Prayer times", "أوقات الصلاة", "नमाज़"), L("No appointments during prayers", "لا مواعيد أثناء الصلاة", "नमाज़ के समय अपॉइंटमेंट नहीं")]] :
        [[L("Mon – Sat", "الاثنين – السبت", "सोम – शनि"), '<span class="num ltr">08:00–13:00 · 17:00–21:00</span>'], [L("Sunday", "الأحد", "रविवार"), '<span class="num ltr">09:00–12:00</span>']]) +
        '<hr class="hr" style="margin:14px 0"><div class="row nw">' + ii("map") + '<span class="small muted fl">' + esc(f.area) + " · " + esc(L("Parking at the back gate", "موقف عند البوابة الخلفية", "पीछे वाले गेट पर पार्किंग")) + "</span>" + ui.btn(L("Directions", "الاتجاهات", "रास्ता"), { cls: "sm ghost", icon: "map", act: "toast", arg: L("Opening maps…", "جارٍ فتح الخرائط…", "मैप्स खुल रहा है…") }) + "</div>", { icon: "clockh" }) + "</div>";
    h += "</div>";
    h += '<div class="gr-sfoot"><span>© 2026 ' + esc(f.name) + '</span><a href="javascript:void 0" data-act="toast" data-arg="Privacy notice v3 · ' + esc(mk().privacyLaw) + '">' + esc(L("Privacy notice", "إشعار الخصوصية", "गोपनीयता सूचना")) + "</a>" +
      '<a href="javascript:void 0" data-act="toast" data-arg="' + esc(dpo().n + " · " + dpo().mail) + '">' + esc(L("Grievance officer", "مسؤول التظلمات", "शिकायत अधिकारी")) + "</a>" +
      '<span class="gr-pw">' + ui.icon("logo", "sm") + esc(L("Powered by nabd", "مدعوم من نبض", "nabd द्वारा संचालित")) + "</span></div></div>";
    return h;
  });

  /* ================= connect.forclinics (Nabd sales & trial page) ================= */
  function plans() {
    var P = pick({ india: [0, 1499, 2499, 4999], uae: [0, 449, 799, 499], ksa: [0, 499, 899, 499], qatar: [0, 449, 799, 499] });
    return { p: P, tax: pick({ india: "+ 18% GST", uae: "+ 5% VAT", ksa: "+ 15% VAT", qatar: "No VAT" }) };
  }
  NB.screen("connect.forclinics", function (c) {
    var m = c.m, P = plans(), spec = NB.v("grTrSpec", "derm");
    var h = '<div class="gr-site"><div class="gr-snav"><div class="logo"><span class="mark">' + ui.icon("logo") + '</span><div class="nm"><b>nabd</b><small>' + esc(L("for clinics", "للعيادات", "क्लिनिक के लिए")) + '</small></div></div><div class="sp"></div>' +
      ui.btn(L("Pricing", "الأسعار", "क़ीमत"), { cls: "ghost hide-s", act: "toast", arg: "Scroll to pricing" }) + ui.btn(L("Log in", "دخول", "लॉग इन"), { cls: "ghost hide-s", go: "owner.overview" }) + ui.btn(L("Start free trial", "ابدأ التجربة", "फ़्री ट्रायल शुरू करें"), { cls: "pri sm", act: "grTrial" }) + "</div>";
    h += '<div class="gr-sbody">';
    h += '<div class="gr-hero acc"><div class="eyebrow">' + ui.icon("sparkle", "sm acc") + esc(L("For clinics in " + m.name, "للعيادات في " + ({ uae: "الإمارات", ksa: "السعودية", qatar: "قطر" }[m.id] || "الهند"), m.name + " के क्लिनिकों के लिए")) + "</div><h1>" +
      esc(L("Give every patient a time, not a token. Get two hours of your day back.", "امنح كل مريض موعدًا لا رقم انتظار، واستعد ساعتين من يومك.", "हर मरीज़ को टोकन नहीं, समय दें। अपने दिन के दो घंटे वापस पाएँ।")) + "</h1><p>" +
      esc(L("Bookings and reminders on WhatsApp, a live queue patients can watch from home, prescriptions in under 90 seconds and a 30-second checkout. Set up in under an hour.", "حجوزات وتذكيرات عبر واتساب، وطابور مباشر يتابعه المريض من بيته، ووصفة في أقل من 90 ثانية، ودفع في 30 ثانية. الإعداد في أقل من ساعة.", "व्हाट्सऐप पर बुकिंग और रिमाइंडर, घर से देखी जा सकने वाली लाइव क़तार, 90 सेकंड में नुस्ख़ा और 30 सेकंड में चेकआउट। एक घंटे से कम में सेटअप।")) + "</p>" +
      '<div class="btns">' + ui.btn(L("Start free trial", "ابدأ التجربة المجانية", "फ़्री ट्रायल शुरू करें"), { cls: "pri lg", icon: "play", act: "grTrial" }) + ui.btn(L("Book a white-glove setup", "احجز إعدادًا كاملًا", "व्हाइट-ग्लव सेटअप बुक करें"), { cls: "lg", icon: "handshake", act: "grWhite" }) + "</div>" +
      '<span class="small muted">' + esc("14-day trial · no card needed · your data stays in " + m.residency) + "</span></div>";
    h += '<div class="stack s8"><h2 class="h">' + esc(L("Time saved, measured", "الوقت الموفّر", "बचाया गया समय")) + '</h2><div class="gr-vp">' + [
      ["90 min", "a day of front-desk phone calls replaced by WhatsApp booking and reminders", "chat"], ["−35%", "time patients spend in the waiting room, thanks to live ETAs and “leave now” alerts", "hourglass"],
      ["< 90 s", "to write a prescription with favourites and safety checks", "pill"], ["< 30 s", "to bill a standard visit: charges are picked, tax is automatic", "receipt"]
    ].map(function (x) { return '<div class="card"><span class="acc">' + ui.icon(x[2], "lg") + '</span><span class="gr-big">' + esc(x[0]) + '</span><span class="small muted">' + esc(x[1]) + "</span></div>"; }).join("") + '</div><span class="small muted">Averages from pilot clinics (sample figures in this prototype).</span></div>';
    var feat = [["WhatsApp booking & reminders", "Self check-in by QR", "Live queue page for patients", "1 doctor"],
      ["Everything in Free", "Unlimited doctors at one clinic", "EMR, e-Rx, billing with " + m.tax, "Recalls and Google review requests", "Price master, reports, data export"],
      ["Everything in Clinic", "Packages and session tracking", "2–3 branches, one login", "Own booking page and widget", "Shifts, attendance and payroll export"]];
    h += '<div class="stack s8"><h2 class="h">' + esc(L("Simple pricing, per clinic", "أسعار بسيطة لكل عيادة", "हर क्लिनिक के लिए आसान क़ीमत")) + '</h2><div class="gr-plans">' + [["Free", P.p[0], 0], ["Clinic", P.p[1], 1], ["Clinic Plus", P.p[2], 2]].map(function (x) {
      return '<div class="card gr-plan' + (x[2] === 1 ? " hl" : "") + '"><div class="row sb"><h3 class="h">' + esc(x[0]) + "</h3>" + (x[2] === 1 ? ui.pill("acc", "Most clinics", "star") : "") + '</div><div class="pr"><b>' + RAW(x[1]) + "</b><span>" + esc(x[1] ? "per clinic / month " + P.tax : "forever") + "</span></div>" +
        '<ul class="gr-ul">' + feat[x[2]].map(function (f) { return "<li>" + ui.icon("check") + esc(f) + "</li>"; }).join("") + "</ul>" + ui.btn(x[2] ? "Start free trial" : "Start free", { cls: x[2] === 1 ? "pri blk" : "blk", act: "grTrial" }) + "</div>";
    }).join("") + "</div>" +
      ui.note("info", "Never a per-booking commission. Each plan includes about 2,000 WhatsApp conversations a month; above that, Meta's price is passed through at cost and shown on your bill. Pay yearly and get two months free plus white-glove setup. Hospitals and groups: talk to us.") + "</div>";
    h += '<div class="grid g2">' + ui.card(L("Start your free trial", "ابدأ تجربتك المجانية", "अपना फ़्री ट्रायल शुरू करें"), '<div class="fgrid">' + ui.field("Clinic name", "", { ph: gcc() ? "e.g. Al Noor Dental" : "e.g. Smile Dental Care", id: "gr-tc" }) + ui.field("Your mobile (WhatsApp)", m.phone + " ", { type: "tel", id: "gr-tm" }) +
      sel("Specialty", "grTrSpec", SPECS.concat([["other", "Other"]]), "derm") + ui.field("City", m.city, { id: "gr-tci" }) + "</div>" +
      '<div class="btns" style="margin-top:14px">' + ui.btn("Send code & open demo clinic", { cls: "pri", icon: "send", act: "grTrial" }) + "</div>" +
      '<p class="small muted" style="margin:10px 0 0">By continuing you accept the terms. Real patient data can be added only after you sign the Data Processing Agreement inside the product.</p>', { icon: "play" }) +
      ui.card(L("What you'll see in 5 minutes", "ما ستراه خلال 5 دقائق", "5 मिनट में क्या दिखेगा"), '<div class="lst">' + [
        ["users", "A demo clinic with sample patients", "120 synthetic patients, 3 doctors and today's queue already running"], ["banknote", "Your specialty's price list", (SPECS.find(function (x) { return x[0] === spec; }) || ["", "General"])[1] + " starter list with " + m.tax + " defaults"],
        ["chat", "WhatsApp booking you can try", "Book yourself from your own phone and watch the token appear"], ["refresh", "One tap to start for real", "Clear the demo data, sign the DPA, connect your number"]
      ].map(function (x) { return ui.li(ii(x[0]), esc(x[1]), esc(x[2]), ""); }).join("") + "</div>", { icon: "sparkle" }) + "</div>";
    h += ui.card(L("White-glove setup", "إعداد كامل نيابة عنك", "व्हाइट-ग्लव सेटअप"), '<div class="row sb" style="align-items:flex-start"><div class="stack s8 fl" style="min-width:240px"><span class="small muted">We set the clinic up with you on a call: doctors and timings, price list, WhatsApp verification with Meta, old patient list imported, staff invited. Usually done in one sitting.</span>' +
      '<div class="chips">' + ["Price list entered", "Meta verification done for you", "Excel import", "Staff trained"].map(function (x) { return ui.pill("ok", x, "check"); }).join("") + "</div></div>" +
      '<div class="stack s8" style="align-items:flex-end"><div class="gr-plan"><div class="pr"><b>' + RAW(P.p[3]) + "</b><span>one-time · waived on annual plans</span></div></div>" + ui.btn("Book a slot", { cls: "pri", icon: "calendar", act: "grWhite" }) + "</div></div>", { icon: "handshake" });
    h += '<div class="stack s8"><h2 class="h">' + esc(L("What clinics say", "ماذا تقول العيادات", "क्लिनिक क्या कहते हैं")) + '</h2><div class="grid g3">' + [1, 2, 3].map(function (i) {
      return '<div class="card gr-tst"><span class="pill mute">' + ui.icon("chat") + "Testimonial placeholder</span>" + '<div class="gr-ph">Quote from pilot clinic ' + i + ", published only with the clinic's written consent.</div>" + '<div class="row nw">' + ui.av("Clinic " + i) + '<div class="fl"><b class="small">Clinic owner</b><div class="small muted">Specialty · City</div></div></div></div>';
    }).join("") + "</div></div>";
    h += ui.card(L("Questions", "أسئلة", "सवाल"), kv([["Can I take my data out?", "Yes, everything, any time, in Excel/CSV, from settings. No lock-in."], ["Where is patient data stored?", esc(m.residency) + ", never outside the country."], ["Do you charge per booking?", "No. One flat price per clinic."], ["I have two branches", "Clinic Plus covers 2–3 branches with one login."], ["Who owns the WhatsApp number?", "You do. Use your own number, or start on ours and move later."]]), { icon: "info" });
    h += '</div><div class="gr-sfoot"><span>© 2026 Nabd Health</span><a href="javascript:void 0" data-act="toast" data-arg="Data Processing Agreement and sub-processor list">DPA &amp; sub-processors</a><a href="javascript:void 0" data-go="connect.search">Find a doctor</a></div></div>';
    return h;
  });
  NB.act("grTrial", function () { NB.state.ui.grStep = "0"; NB.state.ui.grSetTab = "wizard"; NB.go("owner", "setup"); NB.toast("Trial started · demo clinic loaded with sample patients"); });
})();
