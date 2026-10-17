/* Specialty module: one Doctor persona whose home, quick actions and fields change by specialty (FRD "Doctor
   Specialization Profiles": general practice, dermatology, dentistry, paediatrics, ENT, orthopaedics/physio,
   obstetrics & gynaecology, homeopathy, Ayurveda), the owner-configurable specialty settings, the solo
   dermatologist's phone-first skin workspace, the recall / retention engine (PAT-20, DOC-07, GP-02) and teleconsult
   (PAT-16, gated by India's Telemedicine Practice Guidelines 2020 / GCC telehealth licensing).
   Screens: doctor.specialty, solo.specialty, doctor.recalls, doctor.tele.
   Overlays: procconsent (DOC-09 finger signature + signed PDF), toothedit, lesion, growthpoint, recallrule.
   Data lives under NB.D.specialty. Styles: one injected <style>, classes prefixed sp-, nb.css variables only. */
(function () {
  "use strict";
  var ui = NB.ui, L = NB.L, esc = ui.esc;
  var SD = NB.D.specialty = {};

  /* ================= Helpers ================= */
  var MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  var DOW = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  var TODAY = new Date(2026, 9, 17);
  function addD(d, n) { var x = new Date(d.getTime()); x.setDate(x.getDate() + n); return x; }
  function fd(d, noYear) { return ("0" + d.getDate()).slice(-2) + " " + MON[d.getMonth()] + (noYear ? "" : " " + d.getFullYear()); }
  function fdw(d) { return DOW[d.getDay()] + " " + fd(d, true); }
  function iso(d) { return d.getFullYear() + "-" + ("0" + (d.getMonth() + 1)).slice(-2) + "-" + ("0" + d.getDate()).slice(-2); }
  function fromIso(s) { var p = String(s || "").split("-"); return p.length === 3 ? new Date(+p[0], +p[1] - 1, +p[2]) : null; }
  function daysBetween(a, b) { return Math.round((b.getTime() - a.getTime()) / 86400000); }
  function n(x) { return '<span class="num ltr">' + x + "</span>"; }
  function lead(kind, icon) { return '<span class="pill ' + kind + ' sp-lead">' + ui.icon(icon, "sm") + "</span>"; }
  function chev() { return ui.icon("chevright", "sm faint flip"); }
  function v(k, d) { return NB.v(k, d); }
  function pnm(id) { return NB.pname(NB.pat(id)); }
  function attrs(o) {
    o = o || {};
    if (o.ov) return ' data-ov="' + o.ov + '"' + (o.arg != null ? ' data-arg="' + esc(o.arg) + '"' : "");
    if (o.go) return ' data-go="' + o.go + '"';
    if (o.set) return ' data-set="' + o.set[0] + '" data-val="' + esc(o.set[1]) + '"';
    return ' data-act="' + (o.act || "toast") + '"' + (o.arg != null ? ' data-arg="' + esc(o.arg) + '"' : "");
  }
  function card(title, body, o) { o = Object.assign({}, o || {}); o.cls = "sp-wl " + (o.cls || ""); return ui.card(title, body, o); }
  function sw(on) { return '<span class="sw ' + (on ? "on" : "") + '"></span>'; }
  // Patient context bar shown above each specialty chart.
  function ptBar(name, sub, pills, right) {
    return '<div class="sp-pt">' + ui.av(name, "lg") + '<div class="fl"><b>' + esc(name) + "</b><small>" + sub + "</small></div>" +
      (pills ? '<div class="chips">' + pills + "</div>" : "") + (right ? '<div class="btns">' + right + "</div>" : "") + "</div>";
  }
  // Quick action tiles (FRD: max 6 per profile).
  function qa(items) {
    return '<div class="sp-qa" role="toolbar">' + items.slice(0, 6).map(function (q) {
      return '<button class="sp-qb"' + attrs(q[4] || { act: "toast", arg: q[0] + " · opens in the consult" }) + ">" + ui.icon(q[3]) + "<span>" + esc(L(q[0], q[1], q[2])) + "</span></button>";
    }).join("") + "</div>";
  }
  function kpis(list) {
    return '<div class="grid g4 kpis">' + list.map(function (k) { return ui.kpi('<bdi class="num">' + k[0] + "</bdi>", L(k[1], k[2], k[3]), k[4] ? ui.icon(k[6] || "dot", "xs") + "<bdi>" + esc(k[4]) + "</bdi>" : "", k[5]); }).join("") + "</div>";
  }
  function sub(title, right) { return '<div class="sp-sh"><h3 class="h">' + title + "</h3>" + (right || "") + "</div>"; }
  function chip(on, label, o) { return '<button class="chip' + (on ? " on" : "") + '"' + attrs(o) + ">" + (on ? ui.icon("check", "xs") : "") + esc(label) + "</button>"; }
  function mktNote(india, gcc) { return NB.gcc() ? gcc : india; }
  function mobile() { return NB.device() === "mobile"; }

  // Normal distribution helpers (growth centiles).
  function ncdf(z) { var t = 1 / (1 + 0.2316419 * Math.abs(z)), d = 0.3989423 * Math.exp(-z * z / 2), p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274)))); return z > 0 ? 1 - p : p; }

  /* ================= Styles ================= */
  function mix(vr, p) { return "color-mix(in srgb,var(" + vr + ") " + p + "%,transparent)"; }
  var CSS = [
    /* specialty switcher (segmented) */
    ".sp-specbar{display:flex;flex-wrap:wrap;gap:4px;background:var(--s3);border-radius:14px;padding:4px}",
    ".frame.mobile .sp-specbar{flex-wrap:nowrap;overflow-x:auto;scrollbar-width:none}",
    ".sp-sb{flex:none;display:inline-flex;align-items:center;gap:8px;height:38px;padding:0 12px;border-radius:10px;border:0;background:transparent;color:var(--tx2);font:600 13px/1 var(--font);font-family:inherit;cursor:pointer;white-space:nowrap}",
    ".sp-sb:hover{color:var(--tx);background:var(--hover2)}",
    ".sp-sb.on{background:var(--acc-fill);color:var(--on-acc)}",
    ".sp-sb .ph{font:600 9.5px/16px var(--font);letter-spacing:.4px;text-transform:uppercase;padding:0 6px;border-radius:6px;background:var(--chip);color:var(--tx2)}",
    "[dir=rtl] .sp-sb .ph{letter-spacing:0}",
    ".sp-sb.on .ph{background:" + mix("--on-acc", 14) + ";color:var(--on-acc)}",
    ".sp-sb .mine{width:7px;height:7px;border-radius:99px;background:var(--ok);flex:none}",
    ".sp-sb.on .mine{background:var(--on-acc)}",
    /* profile strip + quick actions */
    ".sp-prof{display:flex;gap:14px;align-items:center;flex-wrap:wrap}",
    ".sp-prof .pi{width:48px;height:48px;border-radius:14px;display:grid;place-items:center;background:var(--acc-tint2);color:var(--acc);flex:none}",
    ".sp-prof .t{flex:1;min-width:220px}.sp-prof h2{margin:0;font:600 18px/24px var(--font)}",
    ".sp-prof p{margin:2px 0 0;font-size:12.5px;line-height:18px;color:var(--tx2)}",
    ".sp-qa{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:10px}",
    ".frame.tablet .sp-qa,.frame.mobile .sp-qa{grid-template-columns:repeat(3,minmax(0,1fr))}.frame.mobile .sp-qa{gap:8px}",
    "@media (max-width:1250px){.frame.desktop .sp-qa{grid-template-columns:repeat(3,minmax(0,1fr))}}",
    ".sp-qb{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;min-height:76px;padding:10px 8px;border-radius:var(--r-md);border:1px solid var(--b-def);background:var(--s2);color:var(--tx);font:600 12.5px/16px var(--font);font-family:inherit;cursor:pointer;text-align:center;min-width:0}",
    ".sp-qb .ico{color:var(--acc)}.sp-qb:hover{border-color:var(--acc-line);background:var(--acc-tint)}",
    ".frame.mobile .sp-qb{min-height:70px;font-size:11.5px;line-height:14px;padding:8px 4px}",
    /* layout */
    ".sp-split{display:grid;grid-template-columns:minmax(0,1fr) 330px;gap:20px;align-items:start}",
    ".frame.tablet .sp-split,.frame.mobile .sp-split{grid-template-columns:minmax(0,1fr)}",
    "@media (max-width:1180px){.frame.desktop .sp-split{grid-template-columns:minmax(0,1fr)}}",
    ".sp-g2{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;align-items:start}",
    ".sp-g3{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}",
    ".frame.mobile .sp-g2,.frame.mobile .sp-g3{grid-template-columns:minmax(0,1fr)}",
    "@media (max-width:760px){.sp-g2,.sp-g3{grid-template-columns:minmax(0,1fr)}}",
    ".sp-sh{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.sp-sh h3{flex:1;min-width:160px}",
    ".sp-lead{width:32px;height:32px;padding:0;justify-content:center}",
    ".sp-pt{display:flex;align-items:center;gap:12px;flex-wrap:wrap;padding:12px 14px;border:1px solid var(--b-sub);border-radius:var(--r-md);background:var(--s2)}",
    ".sp-pt .fl{min-width:160px}.sp-pt b{display:block;font-size:14.5px}.sp-pt small{display:block;color:var(--tx3);font-size:12px;line-height:16px}",
    ".sp-kv{display:grid;grid-template-columns:auto minmax(0,1fr);gap:7px 14px;font-size:13px;line-height:18px;margin:0}",
    ".sp-kv dt{color:var(--tx3)}.sp-kv dd{margin:0;min-width:0;font-weight:500}",
    ".sp-q{display:flex;flex-direction:column;gap:8px}",
    ".sp-qi{display:flex;gap:10px;align-items:center;padding:10px 12px;border-radius:var(--r-md);background:var(--s2);border:1px solid var(--b-sub);min-width:0}",
    ".sp-qi.click{cursor:pointer}.sp-qi.click:hover{border-color:var(--b-def)}.sp-qi.now{border-color:var(--acc-line);background:var(--acc-tint)}",
    ".sp-qi .tok{min-width:40px}.sp-qi .t{flex:1;min-width:0}",
    ".sp-qi .t b{display:block;font-weight:600;font-size:13.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
    ".sp-qi .t small{display:block;font-size:12px;color:var(--tx3);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
    ".sp-wrap{white-space:normal!important}",
    ".sp-tag{display:inline-flex;align-items:center;gap:4px;height:22px;padding:0 8px;border-radius:999px;font:500 11.5px/1 var(--font);background:var(--chip);color:var(--tx2);white-space:nowrap}",
    ".sp-tag .ico{width:12px;height:12px}",
    /* generic SVG */
    ".sp-svg{display:block;width:100%;height:auto;direction:ltr}",
    ".sp-svg text{font-family:var(--font);fill:var(--tx2);font-size:11px}",
    ".sp-svg .t3{fill:var(--tx3)}.sp-svg .t1{fill:var(--tx);font-weight:600}",
    ".sp-ax{stroke:var(--b-str);stroke-width:1;fill:none}.sp-gl{stroke:var(--div);stroke-width:1;fill:none}.sp-gl2{stroke:var(--b-sub);stroke-width:1;fill:none}",
    ".sp-box{border:1px solid var(--b-sub);border-radius:var(--r-lg);background:var(--sunk);padding:10px;min-width:0}",
    ".sp-leg{display:flex;flex-wrap:wrap;gap:6px 14px;font-size:12px;color:var(--tx2);align-items:center}",
    ".sp-leg span{display:inline-flex;align-items:center;gap:6px;white-space:nowrap}",
    ".sp-sw{width:14px;height:14px;border-radius:4px;border:1.5px solid var(--b-str);background:var(--s2);flex:none;display:inline-grid;place-items:center;font:700 8.5px/1 var(--font);color:var(--tx)}",
    ".sp-ln{width:18px;height:0;border-top:2px solid;flex:none}",
    /* tone helpers for swatches and SVG */
    ".sp-sw.caries{background:var(--crit-t);border-color:var(--crit)}.sp-sw.filled{background:var(--info-t);border-color:var(--info)}.sp-sw.rct{background:var(--sub-t);border-color:var(--sub)}",
    ".sp-sw.crown{background:var(--warn-t);border-color:var(--warn)}.sp-sw.implant{background:var(--sync-t);border-color:var(--sync)}.sp-sw.missing{background:transparent;border-style:dashed;border-color:var(--tx3)}",
    ".sp-sw.watch{background:var(--urg-t);border-color:var(--urg)}.sp-sw.healthy{background:var(--s2)}",
    /* dental chart */
    ".sp-chartw{direction:ltr;overflow-x:auto;border:1px solid var(--b-sub);border-radius:var(--r-lg);background:var(--sunk);padding:6px 8px}",
    ".frame.mobile .sp-chartw .sp-svg,.frame.tablet .sp-chartw .sp-svg{min-width:620px}",
    "@media (max-width:760px){.sp-chartw .sp-svg{min-width:620px}}",
    ".sp-t{cursor:pointer;outline:none}",
    ".sp-t .b{fill:var(--s2);stroke:var(--b-str);stroke-width:1.4}.sp-t .r{fill:var(--s1);stroke:var(--b-def);stroke-width:1.2}",
    ".sp-t:hover .b,.sp-t:focus .b{stroke:var(--acc);stroke-width:2}",
    ".sp-t text.n{font-size:11px;font-weight:600;fill:var(--tx2);text-anchor:middle}",
    ".sp-t text.c{font-size:10px;font-weight:700;text-anchor:middle;fill:var(--tx)}",
    ".sp-t.caries .b{fill:var(--crit-t);stroke:var(--crit)}.sp-t.caries text.c{fill:var(--crit)}",
    ".sp-t.filled .b{fill:var(--info-t);stroke:var(--info)}.sp-t.filled text.c{fill:var(--info)}",
    ".sp-t.rct .b{fill:var(--sub-t);stroke:var(--sub)}.sp-t.rct text.c{fill:var(--sub)}.sp-t.rct .r{stroke:var(--sub)}",
    ".sp-t.crown .b{fill:var(--warn-t);stroke:var(--warn)}.sp-t.crown text.c{fill:var(--warn)}",
    ".sp-t.implant .b{fill:var(--sync-t);stroke:var(--sync)}.sp-t.implant text.c{fill:var(--sync)}.sp-t.implant .r{stroke:var(--sync);stroke-dasharray:2 2}",
    ".sp-t.missing .b,.sp-t.missing .r{fill:none;stroke:var(--tx3);stroke-dasharray:3 3}.sp-t.missing text.c{fill:var(--tx3)}",
    ".sp-t.watch .b{fill:var(--urg-t);stroke:var(--urg)}.sp-t.watch text.c{fill:var(--urg)}",
    ".sp-t.sel .b{stroke:var(--acc);stroke-width:3}",
    ".sp-t .canal{stroke:var(--sub);stroke-width:2;fill:none;stroke-linecap:round}",
    ".sp-t .plan{fill:var(--acc)}.sp-t .plan2{fill:var(--bg-canvas);font-size:8px;font-weight:700;text-anchor:middle}",
    ".sp-mid{stroke:var(--b-str);stroke-dasharray:4 4}",
    ".sp-cond{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}",
    ".frame.mobile .sp-cond,.drawer .sp-cond{grid-template-columns:repeat(2,minmax(0,1fr))}",
    "@media (min-width:761px){.drawer .sp-cond{grid-template-columns:repeat(4,minmax(0,1fr))}}",
    ".sp-cb{display:flex;align-items:center;gap:8px;padding:10px;border-radius:var(--r-md);border:1px solid var(--b-def);background:var(--s2);cursor:pointer;font:600 12.5px/16px var(--font);font-family:inherit;color:var(--tx);text-align:start;min-width:0}",
    ".sp-cb.on{border-color:var(--acc);background:var(--acc-tint);box-shadow:inset 0 0 0 1px var(--acc)}",
    ".sp-surf{display:flex;gap:6px;flex-wrap:wrap}",
    ".sp-plan .ph{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:10px 0 6px}",
    ".sp-plan .ph b{flex:1;min-width:160px;font-size:13.5px}",
    ".sp-pi{display:grid;grid-template-columns:56px minmax(0,1fr) auto auto;gap:10px;align-items:center;padding:9px 0;border-top:1px solid var(--div);font-size:13px}",
    ".sp-pi .tn{font:600 12px/1 var(--font);color:var(--acc);font-variant-numeric:tabular-nums}",
    ".sp-pi small{display:block;color:var(--tx3);font-size:11.5px}",
    ".frame.mobile .sp-pi{grid-template-columns:44px minmax(0,1fr) auto}.frame.mobile .sp-pi .pill{grid-column:2/-1;justify-self:start}",
    ".sp-tot{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:6px 16px;font-size:13px;padding-top:10px;border-top:1px solid var(--b-def)}",
    ".sp-tot b{font-size:15px}",
    ".sp-xr{border-radius:var(--r-md);overflow:hidden;border:1px solid var(--b-def);background:var(--sunk)}",
    ".sp-xr .bone{fill:var(--s3);stroke:var(--b-str)}.sp-xr .canal{stroke:var(--tx3);stroke-width:2;fill:none}.sp-xr .lesn{fill:var(--sunk);stroke:var(--crit);stroke-dasharray:3 2}",
    /* body map */
    ".sp-bm svg{cursor:crosshair}",
    ".sp-bo{fill:var(--s3);stroke:var(--b-str);stroke-linecap:round;stroke-linejoin:round}",
    ".sp-bf{fill:var(--s3);stroke:var(--s3);stroke-linecap:round;stroke-linejoin:round}",
    ".sp-spine{stroke:var(--b-str);stroke-dasharray:3 4;fill:none}",
    ".sp-les{cursor:pointer}.sp-les circle{fill:var(--urg-t);stroke:var(--urg);stroke-width:2}",
    ".sp-les text{fill:var(--tx);font-size:9px;font-weight:700;text-anchor:middle}",
    ".sp-les.prev{cursor:default}.sp-les.prev circle{fill:none;stroke:var(--tx3);stroke-dasharray:3 2;stroke-width:1.5}",
    ".sp-les.new circle{stroke:var(--acc);fill:var(--acc-tint2)}",
    /* photos */
    ".sp-photo{position:relative;border-radius:var(--r-md);overflow:hidden;border:1px solid var(--b-def);background:var(--s2)}",
    ".sp-photo svg{display:block;width:100%;height:auto}",
    ".sp-face{fill:var(--s3);stroke:var(--b-str);stroke-width:1.5}.sp-fl{fill:none;stroke:var(--tx3);stroke-width:1.5;stroke-linecap:round}",
    ".sp-pap{fill:var(--crit-t);stroke:var(--crit);stroke-width:1}.sp-pih{fill:var(--warn-t);stroke:none}",
    ".sp-cap{position:absolute;top:8px;inset-inline-start:8px;display:inline-flex;gap:6px;align-items:center;background:var(--s1);border:1px solid var(--b-def);border-radius:8px;padding:3px 8px;font-size:11.5px;font-weight:600;color:var(--tx)}",
    ".sp-wm{position:absolute;bottom:8px;inset-inline-end:8px;font-size:10.5px;font-weight:600;color:var(--tx2);background:var(--s1);border:1px dashed var(--b-str);border-radius:6px;padding:2px 6px}",
    ".sp-cmp{position:relative;--pos:50%;direction:ltr;width:100%;max-width:560px;margin-inline:auto}",
    ".sp-range{display:block;max-width:560px;margin-inline:auto}",
    ".sp-sbs{max-width:760px}",
    ".sp-wl .li{flex-wrap:wrap}.sp-wl .li .t{min-width:150px}.sp-qi{flex-wrap:wrap}.sp-qi .t{min-width:140px}",
    ".sp-cmp .aft{position:absolute;inset:0;clip-path:inset(0 0 0 var(--pos))}",
    ".sp-cmp .hdl{position:absolute;top:0;bottom:0;left:var(--pos);width:2px;margin-left:-1px;background:var(--acc);pointer-events:none}",
    ".sp-cmp .hdl::after{content:'';position:absolute;top:50%;left:-9px;width:20px;height:20px;margin-top:-10px;border-radius:99px;background:var(--acc-fill);border:2px solid var(--bg-canvas)}",
    ".sp-range{width:100%;accent-color:var(--acc-fill)}",
    ".sp-thumbs{display:flex;gap:8px;overflow-x:auto;padding-bottom:2px}",
    ".sp-th{flex:none;width:92px;border-radius:10px;border:1px solid var(--b-def);background:var(--s2);padding:4px;cursor:pointer;font:600 11px/14px var(--font);font-family:inherit;color:var(--tx2);text-align:center}",
    ".sp-th svg:not(.ico){display:block;width:100%;height:auto;border-radius:6px;margin-bottom:3px}",
    ".sp-th.a{border-color:var(--info);box-shadow:inset 0 0 0 1px var(--info)}.sp-th.b{border-color:var(--acc);box-shadow:inset 0 0 0 1px var(--acc)}",
    /* patch test grid */
    ".sp-gr{min-width:46px;height:28px;border-radius:8px;border:1px solid var(--b-def);background:var(--s2);font:700 12px/1 var(--font);font-family:inherit;color:var(--tx2);cursor:pointer;display:inline-flex;align-items:center;justify-content:center;gap:3px;padding:0 6px}",
    ".sp-gr .ico{width:11px;height:11px}",
    ".sp-gr.q{color:var(--warn);background:var(--warn-t);border-color:" + mix("--warn", 45) + "}",
    ".sp-gr.p{color:var(--crit);background:var(--crit-t);border-color:" + mix("--crit", 45) + "}",
    ".sp-gr.ir{color:var(--info);background:var(--info-t);border-color:" + mix("--info", 45) + "}",
    ".sp-gr.pend{border-style:dashed;color:var(--tx3);background:transparent;cursor:default}",
    /* session dots / day tracker */
    ".sp-dots{display:inline-flex;gap:4px;align-items:center}.sp-dots i{width:10px;height:10px;border-radius:99px;background:var(--s3);border:1px solid var(--b-def)}",
    ".sp-dots i.d{background:var(--ok);border-color:var(--ok)}.sp-dots i.n{background:var(--acc-tint2);border-color:var(--acc)}",
    ".sp-days{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:6px}",
    ".sp-day{border-radius:10px;border:1px solid var(--b-def);background:var(--s2);padding:6px 4px;text-align:center;font-size:11px;line-height:14px;color:var(--tx2);min-width:0}",
    ".sp-day b{display:block;font-size:13px;color:var(--tx)}",
    ".sp-day.d{background:var(--ok-t);border-color:" + mix("--ok", 45) + "}.sp-day.t{background:var(--acc-tint);border-color:var(--acc)}.sp-day.m{border-style:dashed}",
    /* growth / audiogram / ROM charts */
    ".sp-cl{fill:none;stroke-width:1.5}.sp-cl.c50{stroke:var(--ok);stroke-width:2}.sp-cl.c15{stroke:var(--tx3);stroke-dasharray:4 3}.sp-cl.c3{stroke:var(--warn);stroke-dasharray:6 3}",
    ".sp-band{fill:var(--ok-t);stroke:none}",
    ".sp-pts{fill:none;stroke:var(--acc);stroke-width:2}.sp-pt0{fill:var(--acc-fill);stroke:var(--bg-canvas);stroke-width:1.5}.sp-pt0.now{stroke:var(--acc);stroke-width:3;fill:var(--bg-canvas)}",
    ".sp-ar{stroke:var(--crit);fill:none;stroke-width:2}.sp-al{stroke:var(--info);fill:none;stroke-width:2}.sp-al.ln{stroke-dasharray:5 3}",
    ".sp-ar.f{fill:var(--s1)}.sp-norm{fill:var(--ok-t)}",
    ".sp-svg text.sr{fill:var(--crit);font-weight:700;font-size:12px}.sp-svg text.sl{fill:var(--info);font-weight:700;font-size:12px}",
    ".sp-arc{fill:none;stroke:var(--s3);stroke-width:12;stroke-linecap:round}.sp-arc.v{stroke:var(--acc-fill)}.sp-arc.tg{stroke:var(--ok);stroke-width:2;stroke-dasharray:3 3}",
    /* pregnancy timeline */
    ".sp-preg{position:relative;padding:26px 0 76px}",
    ".sp-preg .trk{display:flex;height:14px;border-radius:99px;overflow:hidden;background:var(--s3)}",
    ".sp-preg .trk i{display:block;height:100%}.sp-preg .t1{background:var(--info-t);border-inline-end:2px solid var(--bg-canvas)}.sp-preg .t2{background:var(--acc-tint2);border-inline-end:2px solid var(--bg-canvas)}.sp-preg .t3{background:var(--sub-t)}",
    ".sp-preg .fill{position:absolute;top:26px;height:14px;inset-inline-start:0;border-radius:99px;background:var(--acc-grad);opacity:.9}",
    ".sp-preg .now{position:absolute;top:14px;width:2px;height:38px;background:var(--tx);margin-inline-start:-1px}",
    ".sp-preg .nowl{position:absolute;top:0;transform:translateX(-50%);font:600 11px/14px var(--font);white-space:nowrap;color:var(--tx)}",
    "[dir=rtl] .sp-preg .nowl{transform:translateX(50%)}",
    ".sp-preg .mk{position:absolute;top:44px;width:18px;height:18px;margin-inline-start:-9px;border-radius:6px;display:grid;place-items:center;border:1px solid var(--b-def);background:var(--s2);color:var(--tx3)}",
    ".sp-preg .mk .ico{width:11px;height:11px}.sp-preg .mk.d{background:var(--ok-t);border-color:var(--ok);color:var(--ok)}.sp-preg .mk.n{background:var(--acc-tint);border-color:var(--acc);color:var(--acc)}",
    ".sp-preg .wk{position:absolute;top:66px;font-size:10.5px;color:var(--tx3);transform:translateX(-50%);white-space:nowrap}",
    "[dir=rtl] .sp-preg .wk{transform:translateX(50%)}",
    ".sp-preg .tl{position:absolute;top:86px;font:600 10.5px/14px var(--font);letter-spacing:.4px;text-transform:uppercase;color:var(--tx3)}",
    /* prakriti */
    ".sp-vpk{display:flex;height:14px;border-radius:99px;overflow:hidden;background:var(--s3)}",
    ".sp-vpk i{display:block;height:100%}.sp-vpk .va{background:var(--info)}.sp-vpk .pi{background:var(--urg)}.sp-vpk .ka{background:var(--ok)}",
    ".sp-dot{width:10px;height:10px;border-radius:99px;flex:none;display:inline-block}.sp-dot.va{background:var(--info)}.sp-dot.pi{background:var(--urg)}.sp-dot.ka{background:var(--ok)}",
    ".sp-pq{display:grid;grid-template-columns:150px minmax(0,1fr);gap:8px 12px;align-items:center;padding:8px 0;border-top:1px solid var(--div);font-size:13px}",
    ".frame.mobile .sp-pq{grid-template-columns:minmax(0,1fr)}",
    "@media (max-width:760px){.sp-pq{grid-template-columns:minmax(0,1fr)}}",
    /* case-taking sections */
    ".sp-sec{border:1px solid var(--b-sub);border-radius:var(--r-md);background:var(--s2);overflow:hidden}",
    ".sp-sec>button{width:100%;display:flex;align-items:center;gap:10px;padding:12px 14px;background:none;border:0;color:var(--tx);font:600 13.5px/18px var(--font);font-family:inherit;cursor:pointer;text-align:start}",
    ".sp-sec>button .fl{min-width:0}.sp-sec .bd{padding:0 14px 14px;display:flex;flex-direction:column;gap:10px}",
    ".sp-sec.on{border-color:var(--acc-line)}",
    /* teleconsult */
    ".sp-stage{position:relative;aspect-ratio:16/9;border-radius:var(--r-lg);overflow:hidden;background:var(--sunk);border:1px solid var(--b-def)}",
    ".frame.mobile .sp-stage{aspect-ratio:3/4}",
    ".sp-stage .vt{position:absolute;inset:0;display:grid;place-items:center}",
    ".sp-stage .vt svg{position:absolute;inset:0;width:100%;height:100%}",
    ".sp-sil{fill:var(--s3);stroke:var(--b-def)}",
    ".sp-stage .who{position:absolute;bottom:12px;inset-inline-start:12px;display:flex;gap:6px;align-items:center;flex-wrap:wrap}",
    ".sp-stage .lbl{background:var(--s1);border:1px solid var(--b-def);border-radius:8px;padding:3px 8px;font-size:12px;font-weight:600;color:var(--tx)}",
    ".sp-stage .tbar{position:absolute;top:10px;inset-inline-start:10px;inset-inline-end:10px;display:flex;gap:6px;flex-wrap:wrap;align-items:center}",
    ".sp-pip{position:absolute;bottom:12px;inset-inline-end:12px;width:24%;min-width:96px;aspect-ratio:4/3;border-radius:12px;border:2px solid var(--b-str);background:var(--s2);overflow:hidden;display:grid;place-items:center}",
    ".frame.mobile .sp-pip{width:34%;bottom:60px}",
    ".sp-pip svg{position:absolute;inset:0;width:100%;height:100%}.sp-pip span{position:relative;font-size:11px;font-weight:600;color:var(--tx);background:var(--s1);border-radius:6px;padding:1px 6px;align-self:end;margin-bottom:6px}",
    ".sp-ctl{display:flex;gap:10px;justify-content:center;flex-wrap:wrap}",
    ".sp-ctl .ibtn{width:48px;height:48px;border-radius:999px}.sp-ctl .ibtn.off{background:var(--danger-t);color:var(--danger);border-color:" + mix("--danger", 40) + "}",
    ".sp-ctl .ibtn.end{background:var(--danger);color:var(--bg-canvas);border-color:transparent}",
    ".sp-meter{display:flex;gap:3px;align-items:flex-end;height:18px}.sp-meter i{width:4px;border-radius:2px;background:var(--ok)}.sp-meter i.o{background:var(--s3)}",
    ".sp-chat{display:flex;flex-direction:column;gap:8px;max-height:340px;overflow-y:auto}",
    ".sp-msg{border-radius:var(--r-md);padding:8px 11px;font-size:13px;line-height:18px;max-width:88%}",
    ".sp-msg.in{background:var(--s3);align-self:flex-start}.sp-msg.out{background:var(--acc-tint);border:1px solid var(--acc-line);align-self:flex-end}",
    ".sp-msg small{display:block;color:var(--tx3);font-size:11px;margin-top:3px}",
    ".sp-att{display:flex;gap:8px;align-items:center;border:1px solid var(--b-def);background:var(--s1);border-radius:10px;padding:7px 9px;margin-top:6px}",
    ".sp-att .t{min-width:0;flex:1}.sp-att b{display:block;font-size:12.5px}.sp-att small{margin:0}",
    /* consent + signature */
    ".sp-ctext{border:1px solid var(--b-sub);border-radius:var(--r-md);background:var(--s2);padding:14px;font-size:13px;line-height:20px;display:flex;flex-direction:column;gap:10px;max-height:330px;overflow-y:auto}",
    ".sp-ctext h4{margin:0;font:600 12px/16px var(--font);letter-spacing:.5px;text-transform:uppercase;color:var(--tx3)}",
    "[dir=rtl] .sp-ctext h4,.sp-ctext[dir=rtl] h4{letter-spacing:0}",
    ".sp-ctext ul{margin:0;padding-inline-start:18px;display:flex;flex-direction:column;gap:4px}",
    ".sp-ctext[dir=rtl]{font-family:var(--font-ar)}.sp-ctext[lang=hi]{font-family:var(--font-hi)}",
    ".sp-sigw{position:relative}",
    ".sp-sigc{display:block;width:100%;height:170px;border-radius:var(--r-md);border:1.5px dashed var(--b-str);background:var(--sunk);touch-action:none;cursor:crosshair}",
    ".sp-sigl{position:absolute;inset-inline-start:16px;inset-inline-end:16px;bottom:34px;border-top:1px solid var(--b-def);pointer-events:none}",
    ".sp-sigh{position:absolute;inset-inline-start:16px;bottom:12px;font-size:11.5px;color:var(--tx3);pointer-events:none}",
    ".sp-paper{background:var(--bg-canvas);border:1px solid var(--b-def);border-radius:10px;padding:18px;font-size:11.5px;line-height:17px;box-shadow:var(--shadow-md);display:flex;flex-direction:column;gap:10px;color:var(--tx)}",
    ".sp-paper .hd{display:flex;gap:10px;align-items:center;border-bottom:1px solid var(--b-def);padding-bottom:10px}",
    ".sp-paper .hd b{display:block;font-size:13px}.sp-paper small{color:var(--tx2)}",
    ".sp-paper svg{display:block;width:100%;max-width:260px;height:auto;border-bottom:1px solid var(--b-str)}",
    ".sp-paper .sig path{fill:none;stroke:var(--tx);stroke-width:2.2;stroke-linecap:round;stroke-linejoin:round}",
    ".sp-hash{font:500 10.5px/14px ui-monospace,Menlo,monospace;color:var(--tx2);word-break:break-all}",
    /* recall */
    ".sp-funnel{display:flex;flex-direction:column;gap:8px}",
    ".sp-fr{display:grid;grid-template-columns:110px minmax(0,1fr) 64px;gap:10px;align-items:center;font-size:12.5px}",
    ".sp-fr .bx{height:22px;border-radius:6px;background:var(--acc-tint2);border:1px solid var(--acc-line)}",
    ".sp-fr .bx.ok{background:var(--ok-t);border-color:" + mix("--ok", 45) + "}",
    ".sp-fr b{text-align:end;font-variant-numeric:tabular-nums}",
    ".sp-bub{background:var(--wa-out);color:var(--wa-tx);border-radius:10px;border-start-end-radius:2px;padding:8px 10px 6px;font-size:13px;line-height:19px;white-space:pre-line;max-width:320px}",
    ".sp-bub .wb{display:block;margin:8px -10px -6px;border-top:1px solid var(--wa-line);text-align:center;color:var(--wa-link);padding:7px;font-weight:600}",
    ".sp-wa{background:var(--wa-bg);border-radius:var(--r-md);padding:14px;display:flex;justify-content:flex-end}",
    /* settings */
    ".sp-set{display:grid;grid-template-columns:300px minmax(0,1fr);gap:20px;align-items:start}",
    ".frame.tablet .sp-set,.frame.mobile .sp-set{grid-template-columns:minmax(0,1fr)}",
    "@media (max-width:1180px){.frame.desktop .sp-set{grid-template-columns:minmax(0,1fr)}}",
    ".sp-sli{display:flex;gap:10px;align-items:center;padding:10px;border-radius:var(--r-md);cursor:pointer;border:1px solid transparent;min-width:0}",
    ".sp-sli:hover{background:var(--hover)}.sp-sli.on{background:var(--acc-tint);border-color:var(--acc-line)}",
    ".sp-sli .t{flex:1;min-width:0}.sp-sli b{display:block;font-size:13.5px;font-weight:600}.sp-sli small{display:block;font-size:11.5px;color:var(--tx3)}",
    ".sp-sli>.ico{color:var(--acc)}",
    ".sp-vx{display:inline-flex;align-items:center;gap:5px;height:24px;padding:0 8px;border-radius:8px;font:500 12px/1 var(--font);border:1px solid var(--b-def);background:var(--s2);color:var(--tx);white-space:nowrap}",
    ".sp-vx .ico{width:12px;height:12px}.sp-vx.ok .ico{color:var(--ok)}.sp-vx.warn{border-color:" + mix("--warn", 45) + ";background:var(--warn-t)}.sp-vx.warn .ico{color:var(--warn)}",
    ".sp-vx.crit{border-color:" + mix("--crit", 45) + ";background:var(--crit-t)}.sp-vx.crit .ico{color:var(--crit)}.sp-vx.mute{border-style:dashed;background:transparent;color:var(--tx2)}",
    ".sp-ain{width:52px;height:30px;border-radius:8px;border:1px solid var(--b-def);background:var(--s3);text-align:center;font-weight:600;font-variant-numeric:tabular-nums;color:var(--tx)}",
    ".sp-ain:focus{outline:2px solid var(--acc);outline-offset:1px}",
    ".sp-sigc{color:var(--tx)}",
    ".sp-wl .sp-kv + .note,.sp-wl .lst + .note,.sp-wl .tw + .note,.sp-wl .fgrid + .note,.sp-wl .sp-pt + .note,.sp-wl .sp-g2 + .note{margin-top:12px}",
    ".sp-wl span[data-toggle]{flex:none;display:inline-flex;cursor:pointer}",
    ".sp-qa.sp-qa3{grid-template-columns:repeat(3,minmax(0,1fr))}",
    ".drawer .sp-xr{max-width:260px}",
    ".sp-g3k{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}",
    ".sp-qi.sp-od{border-inline-start:3px solid var(--crit)}",
    ".sp-mute{opacity:.6}"
  ].join("\n");
  if (!document.getElementById("sp-css")) { var stEl = document.createElement("style"); stEl.id = "sp-css"; stEl.textContent = CSS; document.head.appendChild(stEl); }

  /* ================= Data ================= */
  // The 9 FRD profiles. phase = FRD staging; pkg = package-treatment support.
  SD.specs = [
    { id: "gp", icon: "steth", en: "General practice", ar: "الطب العام", hi: "सामान्य चिकित्सा", phase: "MVP", pk: "ok", pkg: "Light · recall plans", fr: "GP-01…07",
      layout: "Speed-first single column: waiting list on top, a minimal note in the centre, chronic trend mini-charts in the right rail. Built for 30–60 patients a day." },
    { id: "derm", icon: "sparkle", en: "Dermatology & cosmetology", ar: "الجلدية والتجميل", hi: "त्वचा और कॉस्मेटोलॉजी", phase: "MVP", pk: "ok", pkg: "Core at MVP", fr: "DERM-01…07",
      layout: "Today's list on the left, patient timeline with before/after photo slider and package progress in the centre, note and Rx on the right. On a phone, opening a patient goes straight to the photo comparison." },
    { id: "dent", icon: "tooth", en: "Dentistry", ar: "طب الأسنان", hi: "दंत चिकित्सा", phase: "MVP", pk: "ok", pkg: "Core at MVP", fr: "DENT-01…08",
      layout: "Today's list with chair status on the left; the interactive dental chart of the open patient is always visible; quick-note bar and attachments along the bottom." },
    { id: "ped", icon: "baby", en: "Paediatrics", ar: "طب الأطفال", hi: "बाल रोग", phase: "MVP", pk: "ok", pkg: "Vaccine series & wellness", fr: "PED-01…08",
      layout: "Today's list with age and vaccine-due badges; immunisation grid and WHO growth chart one tap apart; weight-based prescription panel." },
    { id: "ent", icon: "ear", en: "ENT", ar: "الأنف والأذن والحنجرة", hi: "नाक, कान, गला", phase: "Phase 2", pk: "info", pkg: "Light · hearing follow-ups", fr: "ENT-01…03",
      layout: "Today's list; visit note with an image viewer for endoscopy captures beside it; audiogram grid; prescription panel." },
    { id: "orth", icon: "bone", en: "Orthopaedics & physio", ar: "العظام والعلاج الطبيعي", hi: "हड्डी रोग और फ़िज़ियो", phase: "Phase 2", pk: "info", pkg: "Physio session blocks", fr: "ORTH-01…03",
      layout: "Today's list; X-ray viewer plus injury note in the centre; prescription and physio orders on the right." },
    { id: "gyn", icon: "heartpulse", en: "Obstetrics & gynaecology", ar: "النساء والولادة", hi: "स्त्री एवं प्रसूति रोग", phase: "Phase 2", pk: "info", pkg: "ANC & post-natal packages", fr: "GYN-01…03",
      layout: "Today's list with pregnancy badges; pregnancy timeline (LMP, EDD and visit schedule) in the centre; note and Rx with pregnancy-safety flags." },
    { id: "hom", icon: "flask", en: "Homeopathy", ar: "المعالجة المثلية", hi: "होम्योपैथी", phase: "AYUSH MVP", pk: "sub", pkg: "3/6-month chronic bundles", fr: "HOM-01…02",
      layout: "Today's list; long-form case record with scrolling sections that auto-save and resume; remedy and potency panel on the right." },
    { id: "ayu", icon: "leaf", en: "Ayurveda (AYUSH)", ar: "الأيورفيدا", hi: "आयुर्वेद (आयुष)", phase: "AYUSH MVP", pk: "sub", pkg: "Panchakarma day blocks", fr: "AYU-01…03",
      layout: "Today's list with therapy-session badges; visit note plus prakriti panel; herbal prescription and therapy orders." }
  ];
  function spec(id) { return SD.specs.find(function (s) { return s.id === id; }) || SD.specs[0]; }
  function sname(s) { return L(s.en, s.ar, s.hi); }

  // Settings: specialty fields (FRD col F) and templates (col G). r = required (cannot be switched off).
  SD.fields = {
    gp: [["vit", "BP, pulse, temperature, SpO2, weight", 1], ["sug", "Blood sugar (fasting / PP / random)"], ["chr", "Chronic condition tags and registers", 1], ["all", "Allergy list", 1], ["smk", "Smoking / lifestyle flag"], ["ref", "Referral details (specialty, hospital, reason)"]],
    derm: [["fitz", "Fitzpatrick skin type (I–VI)"], ["map", "Body-map lesion picker"], ["les", "Lesion size and description"], ["all", "Allergy history (drugs, cosmetics, metals, latex)", 1], ["cos", "Cosmetic procedure history"], ["patch", "Patch-test result grid"], ["proc", "Procedure record (peel type / laser settings)"], ["care", "Post-procedure care given"], ["photo", "Photo timeline (consent before first photo)", 1]],
    dent: [["chart", "Per-tooth status chart", 1], ["proc", "Procedure per tooth (date, material)"], ["shade", "Shade selection (VITA classical)"], ["perio", "Periodontal notes"], ["la", "Anaesthesia used (type, batch)", 1], ["ortho", "Orthodontic plan stage"], ["latex", "Anaesthesia / latex allergy", 1]],
    ped: [["wt", "Weight (kg, 2 decimals)", 1], ["ht", "Height / length"], ["hc", "Head circumference (under 2 y)"], ["bw", "Birth weight and gestational age"], ["feed", "Feeding type (breast / formula / mixed)"], ["dev", "Developmental milestones"], ["vax", "Vaccine doses with batch and site", 1], ["grd", "Guardian contacts (up to 2)", 1]],
    ent: [["tags", "Ear / nose / throat finding tags"], ["aud", "Audiogram values (air and bone)"], ["endo", "Endoscopy findings and media"], ["proc", "Procedure performed + consent", 1]],
    orth: [["site", "Injury site and side"], ["mech", "Mechanism of injury"], ["rom", "Range of motion (ROM)"], ["cast", "Cast / plaster dates (auto recall)"], ["physio", "Physio plan and exercises"]],
    gyn: [["lmp", "LMP / EDD", 1], ["gp", "Gravida / para"], ["ga", "Gestational age"], ["anc", "BP and weight per ANC visit"], ["usg", "Ultrasound findings summary"]],
    hom: [["cc", "Chief complaint with modalities"], ["men", "Mental generals"], ["phy", "Physical generals"], ["rem", "Remedy + potency + repetition", 1], ["con", "Constitution note"]],
    ayu: [["prak", "Prakriti (Vata / Pitta / Kapha)"], ["agni", "Agni / digestion note"], ["nadi", "Nadi / observation notes"], ["herb", "Herbal formulation, dose and anupana", 1], ["ther", "Therapy prescribed (abhyanga, shirodhara…)"]]
  };
  SD.templates = {
    gp: [["dm", "Quarterly diabetes review plan", 1], ["ahc", "Annual health check", 1], ["eld", "Elder-care follow-up plan"], ["fav", "Favourite Rx sets (viral fever, HTN starter)", 1]],
    derm: [["peel", "Chemical peel course (6 × 2 weeks)", 1], ["lhr", "Laser hair reduction (6–8 sessions)", 1], ["acne", "Acne treatment course", 1], ["prp", "PRP / hair-fall package", 1], ["btx", "Botulinum toxin (cosmetic)"], ["fill", "Dermal filler"], ["bright", "Skin-brightening package"]],
    dent: [["imp", "Implant 3-stage plan (placement · healing · crown)", 1], ["crn", "Crown / bridge multi-visit plan", 1], ["ortho", "Braces plan with monthly adjustments", 1], ["scal", "Scaling + whitening combo"], ["fluo", "Child fluoride programme"]],
    ped: [["vax", "Vaccination course (national schedule)", 1], ["nb", "Newborn first-90-days bundle"], ["well", "Annual child-wellness package"]],
    ent: [["syr", "Ear syringing template", 1], ["cau", "Nasal cautery template", 1], ["fb", "Foreign-body removal template", 1], ["spt", "Allergy skin-prick test series"]],
    orth: [["phy10", "Physio block · 10 sessions", 1], ["frx", "Post-fracture review plan", 1]],
    gyn: [["anc", "ANC package (monthly visits + scans)", 1], ["pnc", "Post-natal care package"]],
    hom: [["c3", "3-month chronic-case bundle", 1], ["c6", "6-month chronic-case bundle"]],
    ayu: [["pk7", "Panchakarma 7 days"], ["pk14", "Panchakarma 14 days", 1], ["pk21", "Panchakarma 21 days"], ["well", "Wellness package"]]
  };
  function fieldOn(s, f) { var d = (SD.fields[s] || []).find(function (x) { return x[0] === f; }); return d && d[2] ? true : v("spF_" + s + "_" + f, true); }

  /* ---- Dentistry ---- */
  SD.teeth = { 18: "missing", 16: "filled", 15: "caries", 24: "caries", 26: "crown", 36: "implant", 37: "filled", 38: "watch", 46: "caries", 47: "rct", 48: "missing" };
  SD.teethP = { 55: "caries", 64: "filled", 75: "caries", 85: "watch", 51: "missing" };
  SD.surf = { 15: "MOD", 24: "O", 46: "MOD", 16: "O", 37: "OB", 55: "OD", 64: "O", 75: "MO" };
  SD.toothNote = { 46: "Deep caries reaching pulp; tender on percussion; IOPA: periapical widening", 38: "Mesioangular impaction, partially erupted; pericoronitis × 2 this year", 36: "Implant placed Mar 2025 (Nobel 4.3 × 10 mm), crown fitted Jul 2025", 26: "PFM crown 2022", 47: "RCT 2021, obturation satisfactory", 18: "Extracted 2019", 48: "Extracted 2019", 15: "Proximal caries mesial + distal", 24: "Occlusal caries, enamel–dentine", 16: "Composite 2023", 37: "Composite 2024" };
  SD.conds = [["healthy", "Healthy", "سليم", "स्वस्थ", "", "ok"], ["caries", "Caries", "تسوّس", "कैविटी", "C", "crit"], ["filled", "Filled", "محشو", "भरा हुआ", "F", "info"], ["rct", "Root canal", "علاج عصب", "रूट कैनाल", "R", "sub"],
    ["crown", "Crown", "تاج", "क्राउन", "Cr", "warn"], ["implant", "Implant", "زرعة", "इम्प्लांट", "I", "sync"], ["missing", "Missing", "مفقود", "अनुपस्थित", "×", "mute"], ["watch", "Watch", "مراقبة", "निगरानी", "W", "urg"]];
  function cond(id) { return SD.conds.find(function (c) { return c[0] === id; }) || SD.conds[0]; }
  // Treatment plan: phases with items [tooth, procedure, fee (INR-scale), status, cosmetic?]
  SD.plan = [
    { id: "ph1", en: "Phase 1 · Disease control", st: ["ok", "Approved on WhatsApp · 15 Oct 18:22", "checkcircle"], items: [["46", "Root canal treatment (molar), 3 visits", 6500, "prog"], ["38", "Surgical extraction (impacted)", 4500, "appr"], ["All", "Full-mouth scaling and polishing", 1500, "done"]] },
    { id: "ph2", en: "Phase 2 · Restorative", st: ["warn", "Sent for approval · 15 Oct", "hourglass"], items: [["46", "Zirconia crown (after RCT)", 9000, "prop"], ["15", "Composite restoration MOD", 2500, "prop"], ["24", "Composite restoration O", 1800, "prop"]] },
    { id: "ph3", en: "Phase 3 · Maintenance & cosmetic", st: ["mute", "Optional · discuss at review", "dot"], items: [["All", "Recall scaling every 6 months (auto recall)", 1500, "prop"], ["All", "In-office whitening (cosmetic)", 12000, "opt", 1]] }
  ];
  SD.planSt = { done: ["ok", "Done", "checkcircle"], prog: ["acc", "In progress", "play"], appr: ["info", "Approved", "check"], prop: ["warn", "Proposed", "hourglass"], opt: ["mute", "Optional", "dot"] };
  SD.dentPresets = ["LA given: lignocaine 2% + adrenaline 1:80,000, 1.8 mL, IANB", "Rubber dam placed", "Access opened, pulp extirpated", "Working length by apex locator + IOPA", "Canals cleaned and shaped to F2", "Calcium hydroxide dressing, temporary seal", "Next: obturation in 7 days"];
  SD.lab = [["26", "PFM crown", "Smile Dental Lab", 10, 0, "warn", "Due back today"], ["Lower", "Complete denture (Mohammed Ali)", "Precision Dental Works", 3, -3, "crit", "Overdue 3 days"], ["11", "Night guard", "Smile Dental Lab", 9, 2, "ok", "Returned · fit check booked"]];

  /* ---- Dermatology ---- */
  SD.lesions = [
    { id: "L1", view: "f", x: 61, y: 27, site: "Right cheek", morph: "Papules and pustules", size: "2–4", count: 8, change: "Better", note: "Inflammatory acne, was 22 lesions on 14 Aug" },
    { id: "L2", view: "f", x: 70, y: 14, site: "Forehead", morph: "Open and closed comedones", size: "1–2", count: 14, change: "Better", note: "Comedonal, improving on tretinoin" },
    { id: "L3", view: "f", x: 79, y: 27, site: "Left cheek", morph: "Post-inflammatory hyperpigmentation", size: "3–6", count: 6, change: "Same", note: "PIH macules; strict sunscreen" },
    { id: "L4", view: "b", x: 62, y: 70, site: "Upper back, left", morph: "Papules and pustules", size: "2–5", count: 5, change: "Better", note: "Truncal acne" },
    { id: "L5", view: "f", x: 39, y: 92, site: "Right upper arm", morph: "Pigmented macule (naevus)", size: "6", count: 1, change: "New", note: "Dermoscopy: reticular network, symmetric. Recheck in 3 months (photo)" }
  ];
  SD.prevLes = [{ view: "f", x: 58, y: 30 }, { view: "f", x: 66, y: 12 }, { view: "f", x: 82, y: 30 }, { view: "f", x: 74, y: 40 }, { view: "b", x: 64, y: 66 }, { view: "b", x: 76, y: 78 }, { view: "f", x: 62, y: 64 }];
  SD.morph = ["Macule", "Papule", "Papules and pustules", "Plaque", "Nodule", "Vesicle", "Open and closed comedones", "Pigmented macule (naevus)", "Post-inflammatory hyperpigmentation", "Scar", "Ulcer"];
  SD.photos = [
    { id: "v1", d: "14 Jun 2026", lbl: "Visit 1", pap: 26, pih: 3, iga: 4, consent: "Clinical photo consent v2 · signed 14 Jun" },
    { id: "v2", d: "12 Jul 2026", lbl: "Visit 2", pap: 18, pih: 5, iga: 3 },
    { id: "v3", d: "16 Aug 2026", lbl: "Visit 3", pap: 11, pih: 6, iga: 2 },
    { id: "v4", d: "17 Oct 2026", lbl: "Today", pap: 4, pih: 4, iga: 1 }
  ];
  // Patch test (Indian Standard Series, CODFI): [id, allergen, conc/vehicle, d2 grade, relevance]
  SD.patch = [["ni", "Nickel sulfate", "5% pet", "++", "Current: imitation jewellery, jeans button"], ["cr", "Potassium dichromate", "0.5% pet", "−", ""], ["co", "Cobalt chloride", "1% pet", "?+", "Possible: co-sensitisation with nickel"],
    ["fm", "Fragrance mix I", "8% pet", "−", ""], ["ppd", "p-Phenylenediamine (PPD)", "1% pet", "−", ""], ["par", "Parthenium hysterophorus", "Acetone extract", "+", "Past: gardening, outdoor work"],
    ["col", "Colophony", "20% pet", "−", ""], ["thi", "Thiuram mix", "1% pet", "−", ""], ["bop", "Balsam of Peru", "25% pet", "−", ""], ["neo", "Neomycin sulfate", "20% pet", "−", ""],
    ["for", "Formaldehyde", "2% aq", "−", ""], ["mci", "MCI/MI (Kathon CG)", "0.02% aq", "IR", "Irritant pattern, not allergic"]];
  SD.grades = ["−", "?+", "+", "++", "+++", "IR"];
  SD.sessions = [
    { p: "p5", proc: "Laser hair reduction · diode 810 nm", area: "Beard line and neck", done: 3, of: 6, set: "24 J/cm² · 30 ms · 12 × 12 mm · contact cooling 5 °C", cons: "pend", key: "lhr", next: "Today · session 4" },
    { p: null, name: "Meena Joshi", proc: "Glycolic acid peel 35%", area: "Face · melasma", done: 3, of: 6, set: "Contact 3 min · endpoint mild erythema · neutralised", cons: "ok", key: "peel", next: "31 Oct · session 4" },
    { p: "p1", proc: "Salicylic acid peel 30%", area: "Face · acne", done: 1, of: 4, set: "2 coats · 3 min · pseudo-frost", cons: "pend", key: "sal", next: "Today · session 2" },
    { p: "p2", proc: "Glycolic peel (planned)", area: "Face · melasma", done: 0, of: 6, set: "Deferred: pregnant 14 weeks", cons: "block", key: "peel", next: "After delivery" }
  ];

  /* ---- Paediatrics (Zoya Khan, p4) ---- */
  SD.child = { dob: new Date(2020, 7, 17), sex: "F", bw: "2.9 kg", ga: "38 + 4 weeks", feed: "Breast-fed to 14 mo", guardian: "Imran Khan (father) · Sana Khan (mother)" };
  // WHO weight-for-age / height-for-age, girls (Child Growth Standards 0–5 y; Growth Reference 2007 5–10 y). Rows: [age months, P3, P15, P50, P85, P97]
  SD.wfa = [[0, 2.4, 2.8, 3.2, 3.7, 4.2], [6, 5.8, 6.5, 7.3, 8.2, 9.2], [12, 7.1, 7.9, 8.9, 10.0, 11.3], [18, 8.2, 9.1, 10.2, 11.5, 13.0], [24, 9.2, 10.3, 11.5, 12.9, 14.6], [36, 11.0, 12.4, 13.9, 15.7, 17.9], [48, 12.5, 14.2, 16.1, 18.3, 21.0], [60, 14.0, 16.0, 18.2, 20.8, 24.1], [72, 15.4, 17.6, 20.2, 23.2, 27.0], [84, 17.2, 19.4, 22.4, 25.9, 30.0]];
  SD.hfa = [[0, 45.6, 47.3, 49.1, 51.0, 52.7], [6, 61.5, 63.6, 65.7, 67.8, 70.0], [12, 69.2, 71.6, 74.0, 76.4, 78.9], [18, 75.2, 78.0, 80.7, 83.5, 86.2], [24, 80.3, 83.4, 86.4, 89.4, 92.5], [36, 88.3, 91.7, 95.1, 98.6, 102.0], [48, 95.0, 98.9, 102.7, 106.6, 110.5], [60, 101.1, 105.3, 109.4, 113.5, 117.7], [72, 106.4, 110.6, 115.1, 119.6, 124.0], [84, 112.4, 116.9, 121.7, 126.6, 131.1]];
  SD.gpts = [[0, 3.1, 49.0, "Birth"], [6, 7.2, 65.5, "6 mo"], [12, 8.8, 73.8, "12 mo"], [24, 11.3, 85.5, "2 y"], [36, 13.4, 94.2, "3 y"], [48, 14.6, 100.8, "4 y"], [60, 15.6, 106.6, "5 y"], [74, 16.4, 112.0, "Today"]];
  // Schedules: [age label, age in months (window end), [vaccines]]
  SD.vax = {
    iap: { name: "IAP ACVIP 2023 (private practice)", rows: [["Birth", 0, ["BCG", "OPV 0", "Hep B 1"]], ["6 weeks", 1.5, ["DTwP/DTaP 1", "IPV 1", "Hib 1", "Hep B 2", "Rotavirus 1", "PCV 1"]], ["10 weeks", 2.5, ["DTwP/DTaP 2", "IPV 2", "Hib 2", "Hep B 3", "Rotavirus 2", "PCV 2"]],
      ["14 weeks", 3.5, ["DTwP/DTaP 3", "IPV 3", "Hib 3", "Hep B 4", "Rotavirus 3", "PCV 3"]], ["6 months", 6, ["Influenza 1", "TCV"]], ["7 months", 7, ["Influenza 2"]], ["9 months", 9, ["MMR 1"]], ["12 months", 12, ["Hep A 1"]],
      ["15 months", 15, ["MMR 2", "Varicella 1", "PCV booster"]], ["16–18 months", 18, ["DTwP/DTaP B1", "IPV B1", "Hib B1"]], ["18–19 months", 19, ["Hep A 2", "Varicella 2"]], ["4–6 years", 72, ["DTwP/DTaP B2", "IPV B2", "MMR 3"]],
      ["Every year (Oct)", 74, ["Influenza (annual)"]], ["9–14 years", 108, ["HPV 1", "HPV 2"]], ["10–12 years", 120, ["Tdap"]]] },
    uip: { name: "UIP national schedule (India, free at government sites)", rows: [["Birth", 0, ["BCG", "OPV 0", "Hep B birth dose"]], ["6 weeks", 1.5, ["OPV 1", "Pentavalent 1", "Rotavirus 1", "fIPV 1", "PCV 1"]], ["10 weeks", 2.5, ["OPV 2", "Pentavalent 2", "Rotavirus 2"]],
      ["14 weeks", 3.5, ["OPV 3", "Pentavalent 3", "fIPV 2", "Rotavirus 3", "PCV 2"]], ["9–12 months", 12, ["MR 1", "JE 1 (endemic districts)", "PCV booster", "fIPV 3"]], ["16–24 months", 24, ["MR 2", "JE 2", "DPT booster 1", "OPV booster"]], ["5–6 years", 72, ["DPT booster 2"]],
      ["10 years", 120, ["Td"]], ["16 years", 192, ["Td"]]] },
    gcc: { rows: [["Birth", 0, ["BCG", "Hep B 1"]], ["2 months", 2, ["DTaP-IPV-Hib-HepB 1", "PCV 1", "Rotavirus 1"]], ["4 months", 4, ["DTaP-IPV-Hib-HepB 2", "PCV 2", "Rotavirus 2"]], ["6 months", 6, ["DTaP-IPV-Hib-HepB 3", "OPV", "Influenza 1"]],
      ["12 months", 12, ["MMR 1", "Varicella 1", "PCV booster", "MenACWY"]], ["18 months", 18, ["DTaP-IPV-Hib booster", "MMR 2", "Varicella 2", "Hep A 1"]], ["24 months", 24, ["Hep A 2"]], ["4–6 years", 72, ["DTaP-IPV booster"]],
      ["Every year (Oct)", 74, ["Influenza (annual)"]], ["11 years", 132, ["Tdap", "HPV (girls)"]]] }
  };
  SD.milestones = [["12 mo", "Stands alone, pincer grasp, 1–2 words", "ok"], ["18 mo", "Walks well, 10+ words, points to show", "ok"], ["2 y", "Runs, 2-word phrases, parallel play", "ok"], ["3 y", "Pedals tricycle, 3-word sentences, knows name and age", "ok"], ["4 y", "Hops, copies a cross, tells a story", "ok"], ["5 y", "Skips, copies a triangle, counts 10 objects", "ok"]];
  SD.ms6 = [["hop", "Hops on one foot 5+ times", true], ["tie", "Ties shoelaces / buttons independently", true], ["draw", "Draws a person with 6+ body parts", true], ["read", "Recognises letters, reads simple words", false], ["rule", "Follows game rules, takes turns", true], ["lr", "Knows left and right", false]];

  /* ---- ENT (Arjun Nair, p7) ---- */
  SD.freqs = [250, 500, 1000, 2000, 4000, 8000];
  SD.aud = { rAC: [40, 35, 30, 30, 25, 20], rBC: [5, 10, 10, 15, 10, null], lAC: [10, 10, 5, 10, 15, 15], lBC: [5, 5, 5, 5, 10, null] };
  SD.audPrev = { rAC: [20, 20, 15, 15, 15, 15] };

  /* ---- Orthopaedics & physio (Mohammed Ali, p8) ---- */
  SD.rom = [["Knee flexion", "0–135°", 135, 100, 122, "warn"], ["Knee extension", "0°", 0, -8, 0, "warn"], ["Hip flexion", "0–120°", 120, 105, 112, "ok"], ["Ankle dorsiflexion", "0–20°", 20, 12, 15, "warn"], ["Ankle plantarflexion", "0–50°", 50, 42, 45, "ok"]];
  SD.romTrend = [[1, 88], [2, 90], [3, 94], [4, 97], [5, 100]];
  SD.ex = [["Static quadriceps sets", "3 × 10 · hold 5 s", "Twice daily", "Week 1–6", "Press the knee into a rolled towel"], ["Straight-leg raise", "3 × 10", "Twice daily", "Week 1–6", "Tighten the thigh first, lift to 30 cm"],
    ["Heel slides (flexion)", "3 × 15", "Twice daily", "Week 1–4", "Slide to a gentle stretch, not pain"], ["Terminal knee extension · band", "3 × 12", "Daily", "Week 2–6", "Light resistance band"],
    ["Step-ups · 10 cm step", "2 × 10 each leg", "Daily", "Week 3–6", "Hold the rail"], ["Stationary cycling", "10 min · low resistance", "Daily", "Week 2–6", "Seat high, no pain above 3/10"]];
  SD.casts = [["Rohit S., 34 M", "Distal radius fracture (Colles), L", "Below-elbow cast 12 Sep", "24 Oct · 6 weeks", "warn"], ["Aisha P., 9 F", "Supracondylar fracture, R (Gartland I)", "Above-elbow back-slab 26 Sep", "20 Oct · 3–4 weeks", "warn"], ["Vinod K., 52 M", "Ankle sprain grade II, R", "Air-cast boot 06 Oct", "03 Nov · review", "ok"]];

  /* ---- Obstetrics (Ananya Rao, p2) ---- */
  SD.lmp = "2026-07-11";
  SD.anc = [[8, "Booking visit", "CBC, blood group & Rh (B+), HIV, HBsAg, VDRL, TSH, urine, RBS", "d"], [12, "NT scan + dual marker (11–13+6 wk)", "NT 1.4 mm · CRL 62 mm · low risk (1:4,800)", "d"], [14, "Today · ANC review", "BP 112/70 · weight 61.0 kg · start calcium + IFA", "n"],
    [20, "Anomaly scan (18–20 wk)", "Level II ultrasound", ""], [26, "OGTT 75 g (24–28 wk)", "Gestational diabetes screen", ""], [30, "ANC contact", "BP, weight, fundal height, urine protein", ""], [34, "Growth scan (32–34 wk)", "Fetal growth, liquor, placenta", ""],
    [36, "ANC contact", "Presentation, birth plan", ""], [38, "ANC contact", "", ""], [40, "ANC contact · EDD", "Plan for post-dates review at 41 weeks", ""]];
  SD.ancVisits = [["05 Sep", "8 + 0", "108/68", "58.0", "Booking; nausea mild"], ["05 Oct", "12 + 2", "110/70", "59.4", "NT scan normal; Td 1 given"], ["17 Oct", "14 + 0", "112/70", "61.0", "Melasma (seen by derm); no bleeding"]];

  /* ---- Homeopathy (Priya Sharma, p9) ---- */
  SD.hcase = [
    ["cc", "Chief complaint", "Migraine since age 22, left-sided, 2–3 attacks a month. Bursting, hammering pain over the left temple with zig-zag aura 20 min before.",
      [["Worse", "Sun exposure · reading · before menses · 10 am to 3 pm"], ["Better", "Sleep · firm pressure · cold bathing"], ["Concomitants", "Nausea · numb tingling lips before attack"]]],
    ["men", "Mental generals", "Reserved, dwells on a past grief (father, 2019); averse to consolation, weeps alone. Irritable before menses.", []],
    ["phy", "Physical generals", "", [["Thermal", "Hot patient, prefers open air"], ["Appetite / thirst", "Good · large quantities of water"], ["Craving / aversion", "Craves salt · averse to bread"], ["Sleep / dreams", "Unrefreshing · dreams of robbers"], ["Perspiration", "Scanty"]]],
    ["hist", "Past and family history", "Typhoid 2010. Mother: hypertension. Father: type 2 diabetes.", []],
    ["con", "Constitution note", "Lean, dry skin; cracked lower lip in the middle.", []]
  ];
  SD.reper = [["Natrum muriaticum", 14, 5], ["Sepia", 9, 4], ["Ignatia", 8, 4], ["Pulsatilla", 7, 3], ["Lachesis", 6, 3]];
  SD.remedies = ["Natrum muriaticum", "Sepia", "Ignatia amara", "Pulsatilla", "Belladonna", "Bryonia alba"];

  /* ---- Ayurveda (Fatima Sheikh, p6) ---- */
  SD.prak = [["frame", "Body frame", [["v", "Thin, light"], ["p", "Medium, muscular"], ["k", "Broad, heavy"]], "v"], ["skin", "Skin", [["v", "Dry, rough"], ["p", "Warm, oily, freckles"], ["k", "Thick, cool, smooth"]], "v"],
    ["app", "Appetite", [["v", "Irregular"], ["p", "Strong, sharp"], ["k", "Slow, steady"]], "p"], ["sleep", "Sleep", [["v", "Light, interrupted"], ["p", "Moderate"], ["k", "Deep, long"]], "v"],
    ["mind", "Temperament", [["v", "Quick, anxious"], ["p", "Focused, irritable"], ["k", "Calm, steady"]], "p"], ["wea", "Weather preference", [["v", "Dislikes cold, wind"], ["p", "Dislikes heat"], ["k", "Dislikes damp"]], "v"],
    ["spch", "Speech", [["v", "Fast, talkative"], ["p", "Sharp, precise"], ["k", "Slow, melodious"]], "k"], ["mem", "Memory", [["v", "Quick to learn, quick to forget"], ["p", "Sharp"], ["k", "Slow to learn, long retention"]], "p"]];
  SD.herb = [["Kanchanara guggulu", "2 tablets twice daily", "Warm water", "After food", "3 months"], ["Trikatu churna", "1 g twice daily", "Honey", "Before food", "6 weeks"], ["Triphala churna", "5 g at bedtime", "Warm water", "At bedtime", "3 months"]];
  SD.pk = [["Deepana-pachana", 1, 3], ["Snehapana (internal oleation)", 4, 7], ["Abhyanga + swedana", 8, 9], ["Virechana (purgation)", 10, 10], ["Samsarjana krama (diet)", 11, 14]];

  /* ---- Today's lists per specialty (rail) ---- */
  // [token, name, age/sex, reason, pill kind, pill text, pill icon, active?]
  SD.today = {
    gp: [["T-15", "Rakesh Menon", "58 M", "Fever 3 days · diabetic", "urg", "Urgent", "bolt", 1], ["T-18", "Fatima Sheikh", "29 F", "Thyroid review", "ok", "Checkout", "receipt"], ["T-20", "Mohammed Ali", "67 M", "BP review · BP 168/98", "warn", "Abnormal BP", "alertcircle"], ["T-23", "Imtiaz Ahmed", "61 M", "Diabetes review · overdue", "crit", "Overdue 15 d", "clockh"], ["T-25", "Geeta Pai", "71 F", "BP refill", "mute", "Booked", "calendar"]],
    derm: [["T-14", "Ananya Rao", "32 F", "Melasma · pregnant 14 wk", "acc", "In consult", "steth", 1], ["T-17", "Suresh Pillai", "45 M", "Laser hair reduction 4/6", "sub", "Package 4/6", "layers"], ["T-21", "Priya Sharma", "41 F", "Hair fall · new", "info", "New", "userplus"], ["T-24", "Deepa Krishnan", "27 F", "Patch-test reading D2", "warn", "48 h reading", "clockh"], ["T-26", "Kavya Reddy", "34 F", "Acne review · peel 2/4", "warn", "Photo due", "camera"]],
    dent: [["C1", "Rahul Verma", "49 M", "RCT 46 · visit 2", "acc", "Chair 1 · 18 min", "play", 1], ["C2", "Abdullah Al-Harbi", "52 M", "Scaling + review", "ok", "Chair 2 · 6 min", "play"], ["T-31", "Arjun Nair", "12 M", "Orthodontic adjustment", "sub", "Braces 7/18", "layers"], ["T-32", "Meena Joshi", "38 F", "Crown fit 26", "warn", "Lab due today", "box"], ["T-33", "Zoya Khan", "6 F", "Caries 55, 75 · fluoride", "info", "Child", "baby"]],
    ped: [["T-16", "Zoya Khan", "6 y 2 m", "Booster due · growth check", "crit", "Vaccine overdue", "syringe", 1], ["T-19", "Arjun Nair", "12 y", "Ear pain, right", "urg", "Urgent", "bolt"], ["T-27", "Aarav S.", "3 y 1 m", "Influenza (annual)", "warn", "Vaccine due", "syringe"], ["T-28", "Baby of Sana", "6 wk", "6-week vaccines + weight", "warn", "Vaccine due", "syringe"], ["T-29", "Ira M.", "14 mo", "Weight check", "info", "Growth watch", "ruler"]],
    ent: [["T-19", "Arjun Nair", "12 M", "Ear pain R · hearing", "acc", "Audiometry", "ear", 1], ["T-34", "Lakshmi N.", "59 F", "Nasal endoscopy · DNS", "info", "Endoscopy", "image"], ["T-35", "Vikas R.", "31 M", "Ear wax, left", "warn", "Consent pending", "pen"], ["T-36", "Sara T.", "8 F", "Tonsillitis recurrent", "mute", "Booked", "calendar"]],
    orth: [["T-20", "Mohammed Ali", "67 M", "Knee OA R · physio 6/10", "sub", "Physio 6/10", "layers", 1], ["T-37", "Rohit S.", "34 M", "Colles # L · cast review", "warn", "Cast off 24 Oct", "clockh"], ["T-38", "Aisha P.", "9 F", "Supracondylar # R", "info", "X-ray to review", "image"], ["T-39", "Vinod K.", "52 M", "Ankle sprain R", "mute", "Booked", "calendar"]],
    gyn: [["T-14", "Ananya Rao", "32 F", "ANC · 14 + 0 wk", "acc", "Pregnant · T2", "heartpulse", 1], ["T-40", "Nadia K.", "28 F", "ANC · 33 + 2 wk", "warn", "Growth scan due", "image"], ["T-41", "Shreya P.", "35 F", "Post-natal day 10", "ok", "PNC 1/3", "layers"], ["T-42", "Ritu M.", "41 F", "Menorrhagia · USG", "info", "USG to review", "file"]],
    hom: [["T-09", "Priya Sharma", "41 F", "Migraine · long case", "sub", "Case 3/5 saved", "file", 1], ["T-43", "Sunil D.", "48 M", "Eczema · remedy review", "info", "4-week review", "refresh"], ["T-44", "Kiran J.", "9 M", "Recurrent colds", "ok", "Follow-up", "calendar"], ["T-45", "Leela V.", "62 F", "Osteoarthritis · bundle 2/6", "sub", "Bundle 2/6", "layers"]],
    ayu: [["T-18", "Fatima Sheikh", "29 F", "Hypothyroid · panchakarma day 7", "sub", "Day 7 / 14", "layers", 1], ["T-46", "Ravi S.", "44 M", "Low back pain · kati basti", "info", "Therapy 3/7", "layers"], ["T-47", "Usha K.", "57 F", "Prakriti assessment", "warn", "New · long consult", "userplus"], ["T-48", "Anil G.", "50 M", "Acidity · herbal Rx review", "mute", "Follow-up", "calendar"]]
  };

  /* ---- Recall engine ---- */
  SD.reasons = [["chronic", "Chronic / periodic review", "مراجعة دورية", "नियमित समीक्षा", "refresh"], ["vaccine", "Vaccine due", "لقاح مستحق", "टीका देय", "syringe"], ["package", "Package session due", "جلسة باقة مستحقة", "पैकेज सत्र देय", "layers"],
    ["postproc", "Post-procedure check", "فحص بعد الإجراء", "प्रक्रिया-पश्चात जाँच", "steth"], ["lab", "Lab / scan repeat", "تكرار تحليل أو أشعة", "लैब / स्कैन दोहराएँ", "flask"]];
  // [id, patient id or name, age/sex, reason, what, due date, days overdue (negative = due in), attempts sent, last contact, source, phone lang]
  SD.recalls = [
    ["R1", "Venkatesh Rao", "64 M", "chronic", "Diabetes review + HbA1c (3-monthly)", "05 Sep", 42, 2, "2nd nudge read 26 Sep · no booking", "GP-02 register", "en"],
    ["R2", "p4", "", "vaccine", "DTaP-IPV booster 2 + MMR 3 (4–6 y)", "17 Aug", 61, 1, "Delivered 10 Oct · not read", "PED-01 schedule", "hi"],
    ["R3", "Imtiaz Ahmed", "61 M", "chronic", "Diabetes review (HbA1c overdue)", "02 Oct", 15, 2, "Replied “next week” · 09 Oct", "GP-02 register", "en"],
    ["R4", "p6", "", "lab", "TSH 6 weeks after dose change", "14 Oct", 3, 1, "Delivered 12 Oct", "DOC-07 order", "en"],
    ["R5", "Aarav S.", "3 y M", "vaccine", "Influenza (annual)", "15 Oct", 2, 1, "Read 14 Oct", "PED-01 schedule", "en"],
    ["R6", "Meena Joshi", "38 F", "package", "Glycolic peel session 4 of 6", "16 Oct", 1, 1, "Read 15 Oct", "Package PK-1182", "en"],
    ["R7", "p3", "", "chronic", "Pneumonia review + K+ recheck", "19 Oct", -2, 0, "Scheduled 18 Oct 10:00", "DOC-07 · review in 48 h", "en"],
    ["R8", "p1", "", "postproc", "Check 7 days after salicylic peel", "20 Oct", -3, 0, "Scheduled 19 Oct 10:00", "DERM-03 session", "en"],
    ["R9", "p5", "", "package", "Laser hair reduction session 5 of 6", "24 Oct", -7, 0, "Scheduled 22 Oct 10:00", "Package PK-1167", "en"],
    ["R10", "Rahul Verma", "49 M", "chronic", "Dental scaling (6-monthly)", "27 Oct", -10, 0, "Scheduled 20 Oct 10:00", "Dental recall rule", "en"],
    ["R11", "p2", "", "lab", "Anomaly scan 18–20 weeks", "14 Nov", -28, 0, "Scheduled 07 Nov 10:00", "GYN-01 ANC schedule", "en"],
    ["R12", "p10", "", "chronic", "Lipid profile + statin review", "21 Oct", -4, 0, "Scheduled 18 Oct 10:00", "DOC-07 order", "ar"]
  ];
  // Conversion by reason (last 30 days): [reason, recalled, booked, median days to book]
  SD.conv = [["chronic", 142, 92, 3], ["vaccine", 58, 47, 2], ["package", 96, 78, 1], ["postproc", 41, 29, 2], ["lab", 75, 38, 5]];
  // Rules: [id, reason, name, interval, when, template, max attempts, gap days, on]
  SD.rules = [
    ["dm", "chronic", "Diabetes review", "Every 3 months from last HbA1c", "7 days before due", "chronic_review_v3", 2, 3, true],
    ["htn", "chronic", "Hypertension review", "Every 3 months (monthly if uncontrolled)", "5 days before due", "chronic_review_v3", 2, 3, true],
    ["vax", "vaccine", "Vaccine due (national schedule)", "From child's date of birth", "3 days before + on due day", "vaccine_due_v2", 3, 4, true],
    ["pkg", "package", "Package session due", "Package template gap (e.g. 2 weeks)", "2 days before", "package_session_v4", 2, 2, true],
    ["post", "postproc", "Post-procedure check", "Peel / laser: 7 days · extraction: 7 days", "1 day before", "post_procedure_v1", 1, 0, true],
    ["lab", "lab", "Lab / scan repeat", "As ordered (HbA1c 3 mo, TSH 6 wk, ANC scans)", "5 days before", "lab_repeat_v2", 2, 4, true],
    ["scal", "chronic", "Dental scaling", "Every 6 months", "7 days before due", "dental_recall_v1", 2, 5, true],
    ["acne", "chronic", "Acne / skin review", "4 weeks after visit (photo review)", "3 days before", "skin_followup_v2", 2, 3, false]
  ];
  SD.tpl = {
    en: "Hello {name}, this is {clinic}. Your {what} with {doctor} is due on {due}. Tap below to pick a time that suits you.",
    hi: "नमस्ते {name}, {clinic} से संदेश। {doctor} के साथ आपकी {what} {due} को देय है। अपनी सुविधा का समय चुनने के लिए नीचे टैप करें।",
    ar: "مرحبًا {name}، معك {clinic}. موعد {what} مع {doctor} مستحق في {due}. اضغط أدناه لاختيار الوقت المناسب لك."
  };

  /* ---- Teleconsult ---- */
  // [id, time, patient id or name, visit type, mode, consent, device check, status, note]
  SD.tele = [
    ["V1", "10:30", "p9", "Follow-up", "Video", "ok", "ok", "done", "Migraine review · Rx shared"],
    ["V2", "11:00", "p6", "Follow-up", "Video", "ok", "ok", "lobby", "Thyroid panel review"],
    ["V3", "11:20", "Deepa Krishnan", "First consult", "Video", "pend", "pend", "booked", "Rash on arms, 1 week"],
    ["V4", "12:00", "p8", "Follow-up · caregiver joins", "Audio", "ok", "warn", "booked", "BP review · son joins from Dubai"],
    ["V5", "17:30", "p10", "Follow-up", "Video", "ok", "pend", "booked", "Lipid review"]
  ];

  /* ================= Renderers ================= */
  // Sparkline with optional target line. vals = [[label, value]]
  function spark(vals, o) {
    o = o || {}; var W = 260, H = 84, pad = 16, mn = o.min != null ? o.min : Math.min.apply(null, vals.map(function (x) { return x[1]; })), mx = o.max != null ? o.max : Math.max.apply(null, vals.map(function (x) { return x[1]; }));
    var sx = function (i) { return pad + i * (W - 2 * pad) / Math.max(1, vals.length - 1); }, sy = function (y) { return H - 18 - (y - mn) / Math.max(0.001, mx - mn) * (H - 32); };
    var s = '<svg class="sp-svg" viewBox="0 0 ' + W + " " + H + '" role="img" aria-label="' + esc(o.label || "trend") + '">';
    if (o.target != null) s += '<line class="sp-cl c3" x1="' + pad + '" x2="' + (W - pad) + '" y1="' + sy(o.target) + '" y2="' + sy(o.target) + '"/><text class="t3" x="' + (W - pad) + '" y="' + (sy(o.target) - 3) + '" text-anchor="end">' + esc(o.tl || "target") + "</text>";
    s += '<polyline class="sp-pts" points="' + vals.map(function (x, i) { return sx(i) + "," + sy(x[1]); }).join(" ") + '"/>';
    vals.forEach(function (x, i) { var last = i === vals.length - 1; s += '<circle class="sp-pt0' + (last ? " now" : "") + '" cx="' + sx(i) + '" cy="' + sy(x[1]) + '" r="' + (last ? 4.5 : 3.5) + '"/><text x="' + sx(i) + '" y="' + (H - 4) + '" text-anchor="middle" class="t3">' + esc(x[0]) + '</text><text x="' + sx(i) + '" y="' + (sy(x[1]) - 7) + '" text-anchor="middle"' + (last ? ' class="t1"' : "") + ">" + esc(x[2] || x[1]) + "</text>"; });
    return s + "</svg>";
  }

  /* ---- Dental chart ---- */
  function tType(t) { var d = t % 10, prim = Math.floor(t / 10) > 4; if (prim) return d >= 4 ? "m" : d === 3 ? "c" : d === 2 ? "l" : "i"; return d >= 6 ? "m" : d >= 4 ? "p" : d === 3 ? "c" : d === 2 ? "l" : "i"; }
  function univ(t) {
    var q = Math.floor(t / 10), d = t % 10, A = "ABCDEFGHIJKLMNOPQRST";
    return q === 1 ? 9 - d : q === 2 ? 8 + d : q === 3 ? 25 - d : q === 4 ? 24 + d : q === 5 ? A[5 - d] : q === 6 ? A[4 + d] : q === 7 ? A[15 - d] : A[14 + d];
  }
  function tLabel(t) { return v("spDentNum", "fdi") === "uni" ? univ(t) : t; }
  function teethMap(prim) { return Object.assign({}, prim ? SD.teethP : SD.teeth, v(prim ? "spTeethP" : "spTeeth", {})); }
  function planned() { var p = {}; SD.plan.forEach(function (ph) { ph.items.forEach(function (it) { if (+it[0]) p[it[0]] = 1; }); }); v("spPlanAdd", []).forEach(function (it) { p[it[0]] = 1; }); return p; }
  function dentChart(prim, sel) {
    var W = 580, gap = 4, mid = 16, map = teethMap(prim), pl = planned();
    var up = prim ? [55, 54, 53, 52, 51, 61, 62, 63, 64, 65] : [18, 17, 16, 15, 14, 13, 12, 11, 21, 22, 23, 24, 25, 26, 27, 28];
    var lo = prim ? [85, 84, 83, 82, 81, 71, 72, 73, 74, 75] : [48, 47, 46, 45, 44, 43, 42, 41, 31, 32, 33, 34, 35, 36, 37, 38];
    var WD = prim ? { m: 32, c: 24, l: 20, i: 22 } : { m: 34, p: 27, c: 24, l: 21, i: 23 };
    function tooth(t, x, w, upper) {
      var c = map[t] || "healthy", cd = cond(c), cy = upper ? 58 : 114, h = 28, ry = upper ? cy : cy + h, dr = upper ? -1 : 1, rl = 30, two = tType(t) === "m";
      var P = function (fx, fy) { return (x + w * fx).toFixed(1) + "," + (ry + dr * rl * fy).toFixed(1); };
      var root = two ? "M" + P(0.08, 0) + " L" + P(0.18, 1) + " L" + P(0.4, 1) + " L" + P(0.5, 0.45) + " L" + P(0.6, 1) + " L" + P(0.82, 1) + " L" + P(0.92, 0) + " Z"
        : "M" + P(0.1, 0) + " L" + P(0.38, 1) + " L" + P(0.62, 1) + " L" + P(0.9, 0) + " Z";
      var g = '<g class="sp-t ' + c + (sel == t ? " sel" : "") + '" data-ov="toothedit" data-arg="' + t + '" tabindex="0" role="button" aria-label="' + esc("Tooth " + tLabel(t) + ": " + cd[1]) + '">';
      g += '<path class="r" d="' + root + '"/>';
      if (c === "rct") g += two ? '<path class="canal" d="M' + P(0.3, 0.1) + " L" + P(0.29, 0.9) + " M" + P(0.7, 0.1) + " L" + P(0.71, 0.9) + '"/>' : '<path class="canal" d="M' + P(0.5, 0.1) + " L" + P(0.5, 0.9) + '"/>';
      if (c === "implant") g += [0.3, 0.55, 0.8].map(function (f) { return '<path class="canal" style="stroke:var(--sync)" d="M' + P(0.3, f) + " L" + P(0.7, f) + '"/>'; }).join("");
      g += '<rect class="b" x="' + x + '" y="' + cy + '" width="' + w + '" height="' + h + '" rx="7"/>';
      if (cd[4]) g += '<text class="c" x="' + (x + w / 2) + '" y="' + (cy + h / 2 + 3.5) + '">' + esc(cd[4]) + "</text>";
      if (pl[t]) g += '<circle class="plan" cx="' + (x + w - 4) + '" cy="' + (upper ? cy + 4 : cy + h - 4) + '" r="4.5"/>';
      g += '<text class="n" x="' + (x + w / 2) + '" y="' + (upper ? 14 : 194) + '">' + tLabel(t) + "</text>";
      return g + "<title>" + esc("Tooth " + tLabel(t) + " · " + cd[1] + (SD.toothNote[t] ? " · " + SD.toothNote[t] : "")) + "</title></g>";
    }
    function row(r, upper) {
      var ws = r.map(function (t) { return WD[tType(t)]; }), tot = ws.reduce(function (a, b) { return a + b; }, 0) + gap * (r.length - 2) + mid, x = (W - tot) / 2, o = "";
      r.forEach(function (t, i) { if (i === r.length / 2) x += mid - gap; o += tooth(t, x, ws[i], upper); x += ws[i] + gap; });
      return o;
    }
    var s = '<svg class="sp-svg" viewBox="0 0 ' + W + ' 204" role="group" aria-label="' + esc(prim ? "Primary dentition chart, 20 teeth" : "Adult dentition chart, 32 teeth") + '">';
    s += '<line class="sp-mid" x1="' + W / 2 + '" x2="' + W / 2 + '" y1="20" y2="184"/><line class="sp-gl2" x1="16" x2="' + (W - 16) + '" y1="100" y2="100"/>';
    s += '<text class="t1" x="10" y="104">R</text><text class="t1" x="' + (W - 10) + '" y="104" text-anchor="end">L</text>';
    s += '<text class="t3" x="' + (W / 2 + 8) + '" y="96">' + esc(L("Upper", "العلوي", "ऊपरी")) + '</text><text class="t3" x="' + (W / 2 + 8) + '" y="114">' + esc(L("Lower", "السفلي", "निचला")) + "</text>";
    return s + row(up, true) + row(lo, false) + "</svg>";
  }
  function dentLegend() {
    return '<div class="sp-leg">' + SD.conds.map(function (c) { return '<span><i class="sp-sw ' + c[0] + '">' + esc(c[4]) + "</i>" + esc(L(c[1], c[2], c[3])) + "</span>"; }).join("") +
      '<span><svg width="12" height="12" aria-hidden="true"><circle cx="6" cy="6" r="5" style="fill:var(--acc)"/></svg>' + esc(L("In treatment plan", "ضمن خطة العلاج", "उपचार योजना में")) + "</span></div>";
  }
  function xray(t, small) {
    return '<div class="sp-xr"><svg class="sp-svg" viewBox="0 0 120 90" role="img" aria-label="Placeholder IOPA radiograph"><rect x="0" y="0" width="120" height="90" style="fill:var(--sunk)"/>' +
      '<path class="bone" d="M30 8 h60 a8 8 0 0 1 8 8 v22 l-8 40 h-14 l-6 -30 l-6 30 h-14 l-8 -40 v-22 a8 8 0 0 1 8 -8z"/>' +
      '<path class="canal" d="M48 40 L44 76 M72 40 L76 76"/><ellipse class="lesn" cx="44" cy="80" rx="7" ry="5"/>' + (small ? "" : '<text x="6" y="84" class="t3">IOPA ' + esc(t) + "</text>") + "</svg></div>";
  }

  /* ---- Body map ---- */
  var BODY = { head: [70, 24, 15], segs: [[70, 36, 70, 50, 12], [52, 58, 38, 104, 13], [38, 104, 30, 150, 11], [88, 58, 102, 104, 13], [102, 104, 110, 150, 11], [60, 138, 57, 206, 18], [57, 206, 56, 266, 13], [80, 138, 83, 206, 18], [83, 206, 84, 266, 13]],
    hands: [[29, 158, 7], [111, 158, 7]], feet: [[53, 274], [87, 274]], torso: [47, 48, 46, 96] };
  function figure(ox, back) {
    var o = "", f = "", T = BODY.torso;
    function seg(s, w, cls) { return '<line class="' + cls + '" x1="' + (s[0] + ox) + '" y1="' + s[1] + '" x2="' + (s[2] + ox) + '" y2="' + s[3] + '" stroke-width="' + w + '"/>'; }
    BODY.segs.forEach(function (s) { o += seg(s, s[4] + 3, "sp-bo"); f += seg(s, s[4], "sp-bf"); });
    o += '<rect class="sp-bo" x="' + (T[0] + ox) + '" y="' + T[1] + '" width="' + T[2] + '" height="' + T[3] + '" rx="16" stroke-width="1.5"/>';
    f += '<rect class="sp-bf" x="' + (T[0] + ox + 1) + '" y="' + (T[1] + 1) + '" width="' + (T[2] - 2) + '" height="' + (T[3] - 2) + '" rx="15" stroke-width="0"/>';
    BODY.hands.forEach(function (h) { o += '<circle class="sp-bo" cx="' + (h[0] + ox) + '" cy="' + h[1] + '" r="' + (h[2] + 1.5) + '" stroke-width="0"/>'; f += '<circle class="sp-bf" cx="' + (h[0] + ox) + '" cy="' + h[1] + '" r="' + h[2] + '" stroke-width="0"/>'; });
    BODY.feet.forEach(function (h) { o += '<ellipse class="sp-bo" cx="' + (h[0] + ox) + '" cy="' + h[1] + '" rx="10" ry="5.5" stroke-width="0"/>'; f += '<ellipse class="sp-bf" cx="' + (h[0] + ox) + '" cy="' + h[1] + '" rx="8.5" ry="4" stroke-width="0"/>'; });
    o += '<circle class="sp-bo" cx="' + (BODY.head[0] + ox) + '" cy="' + BODY.head[1] + '" r="' + (BODY.head[2] + 1.5) + '" stroke-width="0"/>';
    f += '<circle class="sp-bf" cx="' + (BODY.head[0] + ox) + '" cy="' + BODY.head[1] + '" r="' + BODY.head[2] + '" stroke-width="0"/>';
    if (back) f += '<line class="sp-spine" x1="' + (70 + ox) + '" y1="52" x2="' + (70 + ox) + '" y2="136" stroke-width="1.2"/>';
    return o + f;
  }
  function distSeg(px, py, s) { var dx = s[2] - s[0], dy = s[3] - s[1], t = Math.max(0, Math.min(1, ((px - s[0]) * dx + (py - s[1]) * dy) / (dx * dx + dy * dy))); return Math.hypot(px - s[0] - t * dx, py - s[1] - t * dy); }
  function onBody(x, y) {
    var T = BODY.torso;
    if (Math.hypot(x - BODY.head[0], y - BODY.head[1]) <= BODY.head[2] + 2) return true;
    if (x >= T[0] - 1 && x <= T[0] + T[2] + 1 && y >= T[1] - 1 && y <= T[1] + T[3] + 1) return true;
    if (BODY.hands.some(function (h) { return Math.hypot(x - h[0], y - h[1]) <= h[2] + 2; })) return true;
    if (BODY.feet.some(function (h) { return Math.abs(x - h[0]) <= 10 && Math.abs(y - h[1]) <= 6; })) return true;
    return BODY.segs.some(function (s) { return distSeg(x, y, s) <= s[4] / 2 + 2; });
  }
  function siteName(view, x, y) {
    var right = view === "f" ? x < 70 : x > 70, side = right ? "Right " : "Left ", back = view === "b";
    if (Math.hypot(x - 70, y - 24) <= 17) return back ? "Scalp (occiput)" : y < 17 ? "Forehead" : Math.abs(x - 70) < 4 ? "Nose / upper lip" : side.toLowerCase().replace(/^./, function (c) { return c.toUpperCase(); }) + "cheek";
    if (y < 50 && Math.abs(x - 70) < 9) return back ? "Back of neck" : "Front of neck";
    if (x >= 46 && x <= 94 && y >= 48 && y <= 145) return back ? (y < 96 ? "Upper back, " : y < 128 ? "Lower back, " : "Buttock, ") + (right ? "right" : "left") : (y < 96 ? "Chest, " : "Abdomen, ") + (right ? "right" : "left");
    if ((x < 46 || x > 94) && y < 166) return side + (y < 104 ? "upper arm" : y < 150 ? "forearm" : "hand");
    return side + (y < 206 ? "thigh" : y < 268 ? (back ? "calf" : "shin") : "foot");
  }
  function lesions() { return SD.lesions.filter(function (l) { return !(v("spLesDel", {})[l.id]); }).map(function (l) { return Object.assign({}, l, (v("spLesEd", {})[l.id] || {})); }).concat(v("spLesNew", [])); }
  function bodyMap(compact) {
    var ls = lesions(), prev = v("spPrevLes", false), W = 300, H = 296;
    var s = '<svg class="sp-svg" viewBox="0 0 ' + W + " " + H + '" data-act="sp-mapadd" role="img" aria-label="Body map, front and back, with lesion markers">' + figure(0, false) + figure(150, true);
    s += '<text class="t3" x="70" y="292" text-anchor="middle">' + esc(L("Front", "أمام", "आगे")) + '</text><text class="t3" x="220" y="292" text-anchor="middle">' + esc(L("Back", "خلف", "पीछे")) + "</text>";
    s += '<text class="t1" x="8" y="128">R</text><text class="t1" x="132" y="128" text-anchor="end">L</text><text class="t1" x="158" y="128">L</text><text class="t1" x="292" y="128" text-anchor="end">R</text>';
    if (prev) SD.prevLes.forEach(function (p) { s += '<g class="sp-les prev"><circle cx="' + (p.x + (p.view === "b" ? 150 : 0)) + '" cy="' + p.y + '" r="6"/></g>'; });
    ls.forEach(function (l, i) {
      s += '<g class="sp-les' + (l.change === "New" ? " new" : "") + '" data-ov="lesion" data-arg="' + l.id + '" role="button" tabindex="0" aria-label="' + esc("Lesion " + (i + 1) + ": " + l.site) + '"><circle cx="' + (l.x + (l.view === "b" ? 150 : 0)) + '" cy="' + l.y + '" r="7.5"/><text x="' + (l.x + (l.view === "b" ? 150 : 0)) + '" y="' + (l.y + 3) + '">' + (i + 1) + "</text></g>";
    });
    return '<div class="sp-box sp-bm"' + (compact ? "" : ' style="max-width:420px;margin-inline:auto;width:100%"') + ">" + s + "</svg></div>";
  }

  /* ---- Photo placeholder (neutral SVG, never a real photo) ---- */
  function face(ph, small) {
    var pts = [[78, 66], [84, 74], [72, 76], [120, 66], [126, 76], [116, 74], [90, 42], [100, 38], [110, 44], [80, 86], [122, 86], [95, 46], [105, 48], [88, 60], [114, 60], [76, 58], [124, 58], [86, 92], [118, 92], [70, 70], [130, 70], [94, 36], [106, 36], [100, 96], [82, 50], [118, 50]];
    var pih = [[86, 70], [114, 70], [96, 88], [108, 86], [78, 80], [122, 80]];
    var s = '<svg viewBox="0 0 200 150" role="img" aria-label="' + esc("Placeholder face illustration for " + ph.lbl + ", " + ph.pap + " inflammatory lesions") + '"><rect width="200" height="150" style="fill:var(--s2)"/>' +
      '<path class="sp-face" d="M40 150 q8 -26 40 -32 l8 -10 h24 l8 10 q32 6 40 32z"/><ellipse class="sp-face" cx="100" cy="66" rx="38" ry="48"/>' +
      '<path class="sp-fl" d="M82 60 q6 -4 12 0 M106 60 q6 -4 12 0 M100 66 v14 q-3 3 -6 1 M90 96 q10 6 20 0"/>';
    pih.slice(0, ph.pih).forEach(function (p) { s += '<ellipse class="sp-pih" cx="' + p[0] + '" cy="' + p[1] + '" rx="5" ry="3.5"/>'; });
    pts.slice(0, ph.pap).forEach(function (p) { s += '<circle class="sp-pap" cx="' + p[0] + '" cy="' + p[1] + '" r="2.2"/>'; });
    return s + "</svg>";
  }
  function photo(ph, wm) { return '<div class="sp-photo">' + face(ph) + '<span class="sp-cap">' + esc(ph.lbl) + " · " + n(esc(ph.d)) + "</span>" + (wm ? '<span class="sp-wm">' + esc(L("Clinical use only", "للاستخدام السريري فقط", "केवल क्लिनिकल उपयोग")) + "</span>" : "") + "</div>"; }
  var cmpPos = 50;
  document.addEventListener("input", function (ev) {
    var t = ev.target; if (!t || !t.hasAttribute || !t.hasAttribute("data-sp-slide")) return;
    cmpPos = +t.value; var w = document.getElementById(t.getAttribute("data-sp-slide")); if (w) w.style.setProperty("--pos", cmpPos + "%");
  });

  /* ================= GP ================= */
  SD.reg = {
    dm: { en: "Diabetes", n: 186, cols: ["Last HbA1c", "Trend", "Due checks"], rows: [
      ["Venkatesh Rao", "64 M", "6.8%", "05 Jun", [7.4, 7.1, 6.8], "05 Sep", 42, [["HbA1c", "crit"], ["Eye exam", "warn"]]],
      ["Imtiaz Ahmed", "61 M", "9.1%", "02 Jul", [8.2, 8.8, 9.1], "02 Oct", 15, [["HbA1c", "crit"], ["UACR", "crit"], ["Foot exam", "warn"]]],
      ["Rakesh Menon", "58 M", "8.4%", "12 Oct", [7.1, 7.6, 8.4], "19 Oct", -2, [["UACR (14 mo)", "crit"], ["Eye exam", "warn"]]],
      ["Shabana Begum", "55 F", "10.6%", "12 Oct", [9.0, 9.8, 10.6], "24 Oct", -7, [["Post-DKA review", "warn"]]],
      ["Rahul Verma", "49 M", "7.8%", "11 Sep", [7.2, 7.5, 7.8], "11 Dec", -55, [["Lipids", "warn"]]],
      ["Lakshmi Narayan", "59 F", "7.2%", "20 Aug", [7.9, 7.5, 7.2], "20 Nov", -34, []]] },
    htn: { en: "Hypertension", n: 241, cols: ["Last BP", "Trend (systolic)", "Due checks"], rows: [
      ["Geeta Pai", "71 F", "150/84", "26 Sep", [142, 146, 150], "26 Sep", 21, [["BP check", "crit"], ["KFT + K+", "warn"]]],
      ["Rahul Verma", "49 M", "142/90", "11 Sep", [138, 140, 142], "11 Oct", 6, [["BP check", "crit"]]],
      ["Mohammed Ali", "67 M", "168/98", "Today", [150, 158, 168], "31 Oct", -14, [["KFT (eGFR 34)", "warn"], ["ECG", "warn"]]],
      ["Rakesh Menon", "58 M", "148/92", "Today", [136, 140, 148], "19 Oct", -2, [["K+ recheck", "crit"]]],
      ["Abdullah Al-Harbi", "52 M", "128/82", "30 Sep", [134, 130, 128], "30 Dec", -74, []]] },
    ast: { en: "Asthma", n: 64, cols: ["Last ACT score", "Trend", "Due checks"], rows: [
      ["Arjun Nair", "12 M", "ACT 18", "02 Aug", [15, 17, 18], "02 Nov", -16, [["Inhaler technique", "warn"], ["Influenza vaccine", "warn"]]],
      ["Sara T.", "8 F", "ACT 21", "14 Sep", [19, 20, 21], "14 Dec", -58, []]] },
    thy: { en: "Thyroid", n: 92, cols: ["Last TSH", "Trend", "Due checks"], rows: [
      ["Fatima Sheikh", "29 F", "6.8 mIU/L", "02 Sep", [3.1, 4.9, 6.8], "14 Oct", 3, [["TSH (6 wk after dose change)", "crit"]]],
      ["Ritu M.", "41 F", "2.1 mIU/L", "11 Jul", [2.6, 2.3, 2.1], "11 Jan", -86, []]] }
  };
  function gpWork(c) {
    var k = v("spGpReg", "dm"), R = SD.reg[k], mob = c.mobile;
    var seg = ui.seg("spGpReg", Object.keys(SD.reg).map(function (x) { return [x, L(SD.reg[x].en, { dm: "السكري", htn: "ضغط الدم", ast: "الربو", thy: "الغدة الدرقية" }[x], { dm: "मधुमेह", htn: "उच्च रक्तचाप", ast: "अस्थमा", thy: "थायरॉइड" }[x]), SD.reg[x].n]; }), "dm");
    var rows = R.rows.slice().sort(function (a, b) { return b[6] - a[6]; }).map(function (r) {
      var st = r[6] > 0 ? ui.pill("crit", "Overdue " + r[6] + " d", "clockh") : r[6] > -8 ? ui.pill("warn", "Due in " + -r[6] + " d", "hourglass") : ui.pill("ok", "On track", "checkcircle");
      var mini = '<svg class="sp-svg" viewBox="0 0 70 24" style="width:70px" aria-hidden="true"><polyline class="sp-pts" points="' + r[4].map(function (y, i) { var mn = Math.min.apply(null, r[4]), mx = Math.max.apply(null, r[4]); return (6 + i * 29) + "," + (20 - (y - mn) / Math.max(0.01, mx - mn) * 16); }).join(" ") + '"/></svg>';
      return { cls: r[6] > 0 ? "crit" : "", cells: [ui.cellp(esc(r[0]), esc(r[1])), ui.cellp(n(esc(r[2])), esc(r[3])), mini, '<div class="chips">' + (r[7].length ? r[7].map(function (d) { return ui.pill(d[1], d[0], d[1] === "crit" ? "alertfill" : "alertcircle"); }).join("") : ui.pill("ok", "None due")) + "</div>", st,
        ui.btn(L("Recall", "استدعاء", "रिकॉल"), { cls: "sm", act: "sp-recallone", arg: r[0], icon: "send" })] };
    });
    var tbl = ui.table([{ h: L("Patient", "المريض", "मरीज़") }, { h: R.cols[0] }, { h: R.cols[1] }, { h: R.cols[2] }, { h: L("Next review", "المراجعة التالية", "अगली समीक्षा") }, { h: "" }], rows, { min: 760 });
    var due = '<div class="chips">' + [["HbA1c due", 14, "crit"], ["UACR due", 31, "warn"], ["Eye exam due", 22, "warn"], ["Foot exam due", 9, "warn"], ["BP check due", 17, "crit"], ["K+ / KFT due", 11, "warn"]].map(function (d) { return ui.pill(d[2], d[0] + " · " + d[1], d[2] === "crit" ? "alertfill" : "alertcircle"); }).join("") + "</div>";
    var regCard = card(L("Chronic disease registers", "سجلات الأمراض المزمنة", "क्रॉनिक रोग रजिस्टर") + " · GP-02", '<div class="stack">' + seg + due + tbl +
      '<div class="row sb"><span class="small faint">Sorted by days overdue · overdue rows in red · targets: HbA1c &lt; 7% (ADA 2026' + (NB.gcc() ? "" : " / RSSDI") + "), BP &lt; 130/80 if tolerated (ESH 2023)</span>" +
      '<div class="btns">' + ui.btn(L("Add patient", "إضافة مريض", "मरीज़ जोड़ें"), { cls: "sm", act: "sp-regadd", icon: "userplus" }) + ui.btn("Excel / PDF", { cls: "sm ghost", act: "toast", arg: "Register exported (Excel + PDF) · " + R.en, icon: "download" }) + "</div></div></div>", { icon: "list" });
    var trend = card(L("Open patient · trends", "المريض الحالي · الاتجاهات", "खुला मरीज़ · रुझान") + " · GP-03", ptBar("Rakesh Menon", "58 M · T2DM since 2014 · HTN · CKD G3b", ui.pill("crit", "Penicillin · anaphylaxis", "alertfill")) +
      '<div class="sp-g2" style="margin-top:12px"><div class="stack s4"><span class="eyebrow">HbA1c %</span>' + spark([["Apr", 7.1], ["Jul", 7.6], ["Oct", 8.4]], { target: 7, tl: "target 7", min: 6, max: 9, label: "HbA1c trend" }) + "</div>" +
      '<div class="stack s4"><span class="eyebrow">' + L("Systolic BP", "الضغط الانقباضي", "सिस्टोलिक BP") + "</span>" + spark([["Jul", 136], ["Sep", 140], ["Today", 148]], { target: 130, tl: "target 130", min: 120, max: 155, label: "Systolic BP trend" }) + "</div></div>" +
      ui.note("warn", "Out-of-range today: <b>BP 148/92</b>, <b>RBS 228 mg/dL</b>, <b>K+ 6.1</b> (critical). Alert threshold for this patient: BP &gt; 160/100 or RBS &gt; 300."), { icon: "activity" });
    var quick = card(L("Quick consult mode", "وضع الاستشارة السريعة", "त्वरित परामर्श मोड") + " · GP-01", '<p class="small muted" style="margin:0 0 10px">Complaint tap-tags, one vitals row, searchable diagnosis and favourite Rx sets: a follow-up note + Rx in under 60 seconds. Closing auto-calls the next patient.</p><div class="chips">' +
      ["Fever", "Cough", "Sore throat", "Body ache", "Loose stools", "BP refill", "Sugar refill", "Headache"].map(function (t, i) { return chip(i < 2, t, { act: "toast", arg: t + " added to the note" }); }).join("") + "</div>" +
      '<div class="btns" style="margin-top:12px">' + ui.btn(L("Open quick consult", "فتح الاستشارة السريعة", "त्वरित परामर्श खोलें"), { cls: "pri", go: "doctor.consult", icon: "bolt" }) + ui.btn(L("Sick-leave certificate", "شهادة إجازة مرضية", "अवकाश प्रमाणपत्र"), { act: "toast", arg: "Sick-leave certificate PDF drafted · 2 days rest · edit before signing", icon: "file" }) + "</div>", { icon: "bolt" });
    return regCard + (mob ? trend + quick : '<div class="sp-g2">' + trend + quick + "</div>");
  }

  /* ================= Dermatology ================= */
  function dermTabs(prefix) {
    var t = [["photo", L("Photo timeline", "الصور عبر الزمن", "फ़ोटो टाइमलाइन"), "camera"]];
    if (fieldOn("derm", "map")) t.push(["map", L("Body map", "خريطة الجسم", "बॉडी मैप"), "user"]);
    if (fieldOn("derm", "patch")) t.push(["patch", L("Patch test", "اختبار الرقعة", "पैच टेस्ट"), "clockh"]);
    if (fieldOn("derm", "proc")) t.push(["proc", L("Procedures & packages", "الإجراءات والباقات", "प्रक्रियाएँ और पैकेज"), "layers"]);
    var cur = v("spDermTab", "photo"); if (!t.some(function (x) { return x[0] === cur; })) cur = "photo";
    return { html: ui.tabs("spDermTab", t, "photo"), cur: cur };
  }
  function dermPhotos(c, compact) {
    var A = SD.photos.find(function (p) { return p.id === v("spPhA", "v1"); }), B = SD.photos.find(function (p) { return p.id === v("spPhB", "v4"); }), mode = v("spPhMode", "slide");
    var mkt = !!v("spMktConsent", false);
    var thumbs = '<div class="sp-thumbs">' + SD.photos.map(function (p) { return '<button class="sp-th' + (p.id === A.id ? " a" : p.id === B.id ? " b" : "") + '" data-act="sp-ph" data-arg="' + p.id + '">' + face(p, true) + esc(p.lbl) + '<br><span class="num ltr">' + esc(p.d.slice(0, 6)) + "</span></button>"; }).join("") +
      '<button class="sp-th" data-act="sp-photo" style="display:grid;place-items:center;min-height:96px">' + ui.icon("camera") + esc(L("Capture", "التقاط", "कैप्चर")) + "</button></div>";
    var view = mode === "slide" ? '<div class="sp-cmp" id="sp-cmpw" style="--pos:' + cmpPos + '%">' + photo(A) + '<div class="aft">' + photo(B, !mkt) + '</div><div class="hdl"></div></div><input class="sp-range" type="range" min="0" max="100" value="' + cmpPos + '" data-sp-slide="sp-cmpw" aria-label="Compare before and after">'
      : '<div class="sp-g2 sp-sbs" style="gap:10px">' + photo(A) + photo(B, !mkt) + "</div>";
    var red = Math.round((1 - B.pap / A.pap) * 100);
    var metrics = '<div class="chips">' + ui.pill(red > 0 ? "ok" : "warn", "Inflammatory lesions " + A.pap + " → " + B.pap + (red > 0 ? " (−" + red + "%)" : ""), red > 0 ? "checkcircle" : "alertcircle") + ui.pill("info", "IGA " + A.iga + " → " + B.iga) + ui.pill(B.pih > A.pih ? "warn" : "ok", "PIH macules " + A.pih + " → " + B.pih) + "</div>";
    var consent = '<div class="lst">' + ui.li(lead("ok", "shieldcheck"), "Clinical photo consent · v2", "Signed on device 14 Jun 2026 · required before the first photo (DERM-01)", ui.pill("ok", "Signed")) +
      ui.li(lead(mkt ? "ok" : "perm", mkt ? "checkcircle" : "lock"), "Marketing / before-after use", mkt ? "Separate consent signed · exports carry a watermark" + (NB.gcc() ? " and an MOH advertising-rule flag" : "") : "Not given · photos watermarked “clinical use only”; sharing outside the record is blocked",
        '<button class="btn sm ghost" data-toggle="spMktConsent">' + (mkt ? L("Withdraw", "سحب", "वापस लें") : L("Record consent", "تسجيل الموافقة", "सहमति दर्ज करें")) + "</button>") + "</div>";
    var head = '<div class="row sb">' + ui.seg("spPhMode", [["slide", L("Slider", "منزلق", "स्लाइडर")], ["side", L("Side by side", "جنبًا إلى جنب", "साथ-साथ")]], "slide") +
      '<span class="small faint">' + esc(L("Tap a visit to compare (blue = before, cyan = after)", "اضغط على زيارة للمقارنة", "तुलना के लिए विज़िट टैप करें")) + "</span></div>";
    return '<div class="stack">' + head + thumbs + view + metrics + (compact ? "" : '<p class="tiny faint" style="margin:0">Neutral illustrations stand in for clinical photos. Real photos are stored encrypted, compressed and attached to the visit automatically.</p>') + consent + "</div>";
  }
  function dermMap(c, compact) {
    var ls = lesions(), fitz = v("spFitz", "IV");
    var list = '<div class="lst">' + ls.map(function (l, i) {
      var ch = { Better: ["ok", "checkcircle"], Same: ["mute", "dot"], Worse: ["crit", "alertfill"], New: ["acc", "plus"] }[l.change] || ["mute", "dot"];
      return ui.li('<span class="av">' + (i + 1) + "</span>", esc(l.site) + " · " + esc(l.morph), esc((l.count > 1 ? l.count + " lesions · " : "") + l.size + " mm" + (l.note ? " · " + l.note : "")), ui.pill(ch[0], l.change, ch[1]), { ov: "lesion", arg: l.id });
    }).join("") + "</div>";
    var top = '<div class="row sb">' + (fieldOn("derm", "fitz") ? '<div class="row"><span class="small muted">' + esc(L("Fitzpatrick skin type", "نوع البشرة (فيتزباتريك)", "फ़िट्ज़पैट्रिक त्वचा प्रकार")) + "</span>" + ui.seg("spFitz", ["I", "II", "III", "IV", "V", "VI"].map(function (x) { return [x, x]; }), "IV") + "</div>" : "") +
      '<div class="tog" data-toggle="spPrevLes" role="switch" aria-checked="' + !!v("spPrevLes", false) + '" tabindex="0" style="padding:0;border:0"><div class="t"><b class="small">' + esc(L("Show last visit (16 Aug)", "عرض الزيارة السابقة", "पिछली विज़िट दिखाएँ")) + "</b></div>" + sw(v("spPrevLes", false)) + "</div></div>";
    var hint = ui.note("info", "<b>Tap the outline</b> to mark a new lesion; the site is named for you. Tap a marker to edit size, morphology and change since last visit (DERM-07). Dashed rings show the previous visit.");
    if (compact) return '<div class="stack">' + top + bodyMap(true) + list + "</div>";
    return '<div class="stack">' + top + '<div class="sp-g2">' + bodyMap() + '<div class="stack">' + hint + list + (["V", "VI", "IV"].indexOf(fitz) >= 0 ? ui.note("warn", "Fitzpatrick " + fitz + ": higher risk of post-inflammatory hyperpigmentation after peels and lasers. Use test patches, lower fluence or longer pulse widths.") : "") + "</div></div></div>";
  }
  function gradeCls(g) { return g === "−" ? "" : g === "?+" ? "q" : g === "IR" ? "ir" : "p"; }
  function dermPatch(c, compact) {
    var four = v("spPatchD", "96") === "96", G = v("spPatchG", {}), A = new Date(2026, 9, 15, 10, 0);
    var rows = SD.patch.map(function (a) {
      var g2 = G[a[0] + ":2"] || a[3], g4 = G[a[0] + ":4"], pos = /\+/.test(g2) && g2 !== "?+";
      var gb = function (g, k) { var cls = gradeCls(g); return '<button class="sp-gr ' + cls + '" data-act="sp-grade" data-arg="' + a[0] + ":" + k + '" aria-label="' + esc(a[1] + " reading " + k + ": " + g) + '">' + (cls === "p" ? ui.icon("alertfill") : cls === "q" ? ui.icon("alertcircle") : "") + esc(g) + "</button>"; };
      return { cls: pos ? "crit" : "", cells: [ui.cellp(esc(a[1]), esc(a[2])), gb(g2, 2), g4 ? gb(g4, 4) : '<span class="sp-gr pend">' + ui.icon("hourglass") + (four ? "D4" : "D3") + "</span>", '<span class="small ' + (pos ? "" : "faint") + '">' + esc(a[4] || "—") + "</span>"] };
    });
    var tl = '<div class="tl">' + [["ok", "check", "Applied · D0", fdw(A) + " 10:00 · Indian Standard Series (CODFI), Finn chambers, upper back", ""],
      ["warn", "clockh", "Reading 1 · D2 (48 h)", fdw(addD(A, 2)) + " 10:00 · due now · patches removed, read after 30 min", ""],
      ["", "calendar", "Reading 2 · " + (four ? "D4 (96 h)" : "D3 (72 h)"), fdw(addD(A, four ? 4 : 3)) + " 10:00 · auto-booked · WhatsApp reminder the evening before", ""]].map(function (e) {
      return '<div class="ev"><span class="dotc ' + e[0] + '">' + ui.icon(e[1], "sm") + '</span><div class="bd"><b>' + esc(e[2]) + "</b><small>" + esc(e[3]) + "</small></div></div>"; }).join("") + "</div>";
    var tbl = ui.table([{ h: L("Allergen", "مسبب الحساسية", "एलर्जन") }, { h: "D2 · 48 h" }, { h: four ? "D4 · 96 h" : "D3 · 72 h" }, { h: L("Relevance", "الصلة", "प्रासंगिकता") }], rows, { min: 560 });
    var leg = '<p class="tiny faint" style="margin:0">ICDRG grading: − negative · ?+ doubtful · + weak (erythema, infiltration) · ++ strong (vesicles) · +++ extreme (bullae) · IR irritant. Tap a cell to change the grade.</p>';
    var pos = SD.patch.filter(function (a) { var g = G[a[0] + ":2"] || a[3]; return /\+/.test(g) && g !== "?+"; }).map(function (a) { return a[1]; });
    var head = ptBar("Deepa Krishnan", "27 F · suspected allergic contact dermatitis, arms · new patient (booking r1)", ui.pill("warn", "48 h reading due", "clockh"));
    var body = '<div class="stack">' + head + (compact ? "" : '<div class="sp-g2"><div>' + tl + "</div>" + ui.note(pos.length ? "warn" : "ok", pos.length ? "<b>Positive so far:</b> " + esc(pos.join(", ")) + ". On saving, positives go to the allergy record and show as warnings when prescribing and before procedures (DERM-04/05)." : "No positive reactions recorded yet.") + "</div>") +
      (compact ? tl : "") + tbl + leg + '<div class="btns">' + ui.btn(L("Save D2 reading", "حفظ قراءة اليوم الثاني", "D2 रीडिंग सेव करें"), { cls: "pri", act: "toast", arg: "D2 reading saved · " + (pos.length ? pos.length + " positive allergen(s) added to the allergy record" : "no positives"), icon: "check" }) +
      ui.btn(L("Schedule patch test", "جدولة اختبار", "पैच टेस्ट शेड्यूल"), { act: "toast", arg: "Patch test booked · readings auto-created at +48 h and +" + (four ? 96 : 72) + " h", icon: "calendar" }) + "</div></div>";
    return body;
  }
  function dermProc(c, compact) {
    var S = v("spSessDone", {});
    var rows = SD.sessions.map(function (s, i) {
      var nm = s.p ? pnm(s.p) : s.name, done = s.done + (S[i] ? 1 : 0), dots = '<span class="sp-dots" aria-label="' + done + " of " + s.of + ' sessions">' + Array.apply(null, Array(s.of)).map(function (_, j) { return '<i class="' + (j < done ? "d" : j === done && s.cons !== "block" ? "n" : "") + '"></i>'; }).join("") + "</span>";
      var signed = v("spSigned", {})[s.key + "|" + (s.p || s.name)];
      var cons = s.cons === "block" ? ui.pill("crit", "Blocked · pregnancy", "lock") : s.cons === "ok" || signed ? ui.pill("ok", "Consent signed", "shieldcheck") : '<button class="btn sm" data-ov="procconsent" data-arg="' + esc(s.key + "|" + (s.p || s.name)) + '">' + ui.icon("pen", "sm") + esc(L("Consent", "الموافقة", "सहमति")) + "</button>";
      return [ui.cellp(esc(nm), esc(s.area)), ui.cellp(esc(s.proc), esc(s.set)), dots + '<small class="faint" style="display:block">' + done + " / " + s.of + "</small>", cons, '<span class="small" style="white-space:nowrap">' + esc(s.next) + "</span>"];
    });
    var tbl = ui.table([{ h: L("Patient", "المريض", "मरीज़") }, { h: L("Procedure · settings", "الإجراء · الإعدادات", "प्रक्रिया · सेटिंग्स") }, { h: L("Sessions", "الجلسات", "सत्र") }, { h: L("Consent", "الموافقة", "सहमति") }, { h: L("Next", "التالي", "अगला") }], rows, { min: 900 });
    var sigLhr = v("spSigned", {})["lhr|p5"];
    var rec = card(L("Today's session record", "سجل جلسة اليوم", "आज के सत्र का रिकॉर्ड") + " · Suresh Pillai · LHR 4/6",
      '<div class="fgrid">' + ui.field("Device · wavelength", "Diode 810 nm", { options: ["Diode 810 nm", "Nd:YAG 1064 nm (long-pulse)", "Alexandrite 755 nm"] }) + ui.field("Fluence (J/cm²)", "24") + ui.field("Pulse width (ms)", "30") + ui.field("Spot size", "12 × 12 mm") +
      ui.field("Cooling", "Contact sapphire 5 °C", { options: ["Contact sapphire 5 °C", "Cryogen spray", "Air cooling"] }) + ui.field("Immediate response", "Perifollicular erythema and oedema (desired endpoint)", { options: ["Perifollicular erythema and oedema (desired endpoint)", "Excessive erythema · stopped", "Blanching · stopped"] }) + "</div>" +
      (fieldOn("derm", "care") ? '<div style="margin-top:8px">' + ui.toggle("spCare", "Post-procedure care explained and sent on WhatsApp", "Cool compress, SPF 50, no waxing or sun for 7 days", true) + "</div>" : "") +
      ui.note(sigLhr ? "ok" : "warn", sigLhr ? "Session consent signed " + sigLhr.at + " · PDF locked to this visit." : "Fitzpatrick IV: test patch done 18 Jul. <b>Session consent not yet signed</b>; the session cannot be saved until it is.") +
      '<div class="btns" style="margin-top:12px">' + (sigLhr ? "" : ui.btn(L("Open consent form", "فتح نموذج الموافقة", "सहमति फ़ॉर्म खोलें"), { ov: "procconsent", arg: "lhr|p5", icon: "pen" })) +
      ui.btn(L("Save session 4 · photo", "حفظ الجلسة ٤ · صورة", "सत्र 4 सेव करें · फ़ोटो"), { cls: sigLhr ? "pri" : "", act: "sp-sessave", arg: "0", icon: "check" }) + "</div>", { icon: "bolt" });
    return '<div class="stack s16">' + tbl + rec + "</div>";
  }
  function dermWork(c, compact) {
    var tb = dermTabs(), cur = tb.cur, p = NB.pat("p1");
    var head = cur === "patch" || cur === "proc" ? "" : ptBar(NB.pname(p), "34 F · acne vulgaris + PIH · visit 4 · isotretinoin 20 mg (month 4)", ui.pill("info", "Fitzpatrick " + v("spFitz", "IV")) + ui.pill("ok", "No known drug allergy", "checkcircle") + (compact ? "" : ui.pill("sub", "Peel 1/4", "layers")));
    var body = cur === "map" ? dermMap(c, compact) : cur === "patch" ? dermPatch(c, compact) : cur === "proc" ? dermProc(c, compact) : dermPhotos(c, compact);
    return '<div class="stack s16">' + tb.html + head + body + "</div>";
  }

  /* ================= Dentistry ================= */
  function dentPatient() { return NB.gcc() ? { name: NB.pname(NB.pat("p10")), sub: "52 M · " + NB.mkt().natId + " linked · Bupa Arabia (dental: cash)" } : { name: "Rahul Verma", sub: "49 M · walk-in 2024 · cash · prefers Hindi" }; }
  function dentWork(c) {
    var prim = v("spDentSet", "a") === "p", dp = dentPatient(), sel = NB.state.overlay === "toothedit" ? NB.state.ovArg : null;
    var bar = '<div class="row sb">' + ui.seg("spDentSet", [["a", L("Adult · 32", "بالغ · ٣٢", "वयस्क · 32")], ["p", L("Primary · 20", "لبني · ٢٠", "दूध के दाँत · 20")]], "a") +
      ui.seg("spDentNum", [["fdi", "FDI"], ["uni", "Universal"]], "fdi") + "</div>";
    var chart = card(L("Dental chart", "مخطط الأسنان", "डेंटल चार्ट") + " · DENT-01", '<div class="stack">' + bar + '<div class="sp-chartw">' + dentChart(prim, sel) + "</div>" + dentLegend() +
      '<p class="tiny faint" style="margin:0">' + esc(prim ? "Primary teeth shown for a child patient (Zoya Khan, 6 y): mixed dentition is charted on the same record." : "Dentist's view: the patient's right is on your left. Tap any tooth to set its condition or plan a procedure; switching numbering never changes the data.") + "</p></div>",
      { icon: "tooth", right: ui.pill("acc", "Chair 1 · 18 min", "play") });
    // treatment plan
    var adds = v("spPlanAdd", []), m = NB.mkt(), sub = 0, cos = 0;
    var phases = SD.plan.map(function (ph, pi) {
      var items = ph.items.concat(pi === 1 ? adds.map(function (a) { return [a[0], a[1], a[2], "prop"]; }) : []);
      return '<div class="ph"><b>' + esc(ph.en) + "</b>" + ui.pill(ph.st[0], ph.st[1], ph.st[2]) + "</div>" + items.map(function (it) {
        var s = SD.planSt[it[3]]; if (it[3] !== "opt") sub += it[2]; if (it[4]) cos += it[2];
        return '<div class="sp-pi"><span class="tn">' + (+it[0] ? n(tLabel(+it[0])) : esc(it[0])) + "</span><div>" + esc(it[1]) + "</div><b class=\"num\">" + NB.money(it[2]) + "</b>" + ui.pill(s[0], s[1], s[2]) + "</div>";
      }).join("");
    }).join("");
    var taxable = m.taxRate && NB.gcc() ? cos * m.taxRate / 100 : 0, paid = 4500;
    var tot = '<div class="sp-tot"><span class="muted">' + esc(L("Plan total (excluding optional)", "إجمالي الخطة (دون الاختياري)", "योजना कुल (वैकल्पिक छोड़कर)")) + "</span><b>" + NB.money(sub) + "</b>" +
      '<span class="muted">' + (NB.gcc() ? esc(m.tax + " " + m.taxRate + "% on cosmetic items only (whitening, if chosen)") : "GST: not applicable (healthcare services are exempt)") + "</span><span>" + (taxable ? NB.money(taxable) : "—") + "</span>" +
      '<span class="muted">' + esc(L("Paid so far", "المدفوع حتى الآن", "अब तक भुगतान")) + "</span><span>" + NB.money(paid) + '</span><span><b>' + esc(L("Balance", "المتبقي", "शेष")) + "</b></span><b>" + NB.money(sub - paid) + "</b></div>";
    var plan = card(L("Treatment plan & estimate", "خطة العلاج والتقدير", "उपचार योजना और अनुमान") + " · DENT-07", '<div class="sp-plan">' + phases + "</div>" + tot +
      '<div class="btns" style="margin-top:12px">' + ui.btn(L("Send on WhatsApp for approval", "إرسال للموافقة عبر واتساب", "व्हाट्सऐप पर मंज़ूरी भेजें"), { cls: "pri", act: "toast", arg: "Itemised plan sent to " + dp.name + " on WhatsApp · approval is timestamped and approved items become bookings", icon: "send" }) +
      ui.btn(L("Start procedure + consent", "بدء الإجراء + الموافقة", "प्रक्रिया + सहमति"), { ov: "procconsent", arg: "rct|dent", icon: "pen" }) + ui.btn(L("Print estimate", "طباعة التقدير", "अनुमान प्रिंट"), { cls: "ghost", act: "toast", arg: "Estimate PDF with clinic letterhead ready", icon: "printer" }) + "</div>", { icon: "clipboard" });
    // chair-side note + attachments + lab
    var notes = v("spDNote", [SD.dentPresets[0], SD.dentPresets[1]]);
    var note = card(L("Chair-side quick note", "ملاحظة سريعة بجانب الكرسي", "चेयर-साइड क्विक नोट") + " · " + L("tooth", "السن", "दाँत") + " " + tLabel(46),
      '<div class="chips">' + SD.dentPresets.map(function (t, i) { var on = notes.indexOf(t) >= 0; return chip(on, ["LA given", "Rubber dam", "Access opened", "Working length", "Cleaned & shaped", "Ca(OH)₂ dressing", "Next: obturation"][i], { act: "sp-dnote", arg: i }); }).join("") + "</div>" +
      '<ol class="small" style="margin:12px 0 0;padding-inline-start:18px;display:flex;flex-direction:column;gap:4px">' + notes.map(function (t) { return "<li>" + esc(t) + "</li>"; }).join("") + "</ol>" +
      '<div class="btns" style="margin-top:12px">' + ui.btn(L("Dictate", "إملاء صوتي", "बोलकर लिखें"), { cls: "sm", act: "toast", arg: "Voice-to-text listening (English + " + (NB.gcc() ? "Arabic" : "Hindi") + ")", icon: "mic" }) + ui.btn(L("Save to tooth + visit", "حفظ للسن والزيارة", "दाँत + विज़िट में सेव"), { cls: "sm pri", act: "toast", arg: "Chair-side note saved to tooth " + tLabel(46) + " and today's visit", icon: "check" }) + "</div>", { icon: "edit" });
    var att = card(L("X-rays & photos", "الأشعة والصور", "एक्स-रे और फ़ोटो") + " · DENT-02", '<div class="sp-g3k">' + [["IOPA 46", "Today 10:12"], ["OPG", "14 Sep"], ["Bitewing R", "14 Sep"]].map(function (x) { return '<div class="stack s4">' + xray(x[0], true) + '<span class="small"><b>' + esc(x[0]) + '</b> <span class="faint">' + esc(x[1]) + "</span></span></div>"; }).join("") + "</div>" +
      '<div class="btns" style="margin-top:10px">' + ui.btn(L("Attach X-ray / photo", "إرفاق أشعة / صورة", "एक्स-रे / फ़ोटो जोड़ें"), { cls: "sm", act: "toast", arg: "Sensor image pinned to tooth " + tLabel(46) + " · visible in the tooth history", icon: "upload" }) + "</div>", { icon: "image" });
    var lab = card(L("Lab work tracker", "متابعة أعمال المختبر", "लैब वर्क ट्रैकर") + " · DENT-05", '<div class="lst">' + SD.lab.map(function (l) {
      return ui.li(lead(l[5], "box"), esc((+l[0] ? L("Tooth", "السن", "दाँत") + " " + tLabel(+l[0]) : l[0]) + " · " + l[1]), esc(l[2] + " · sent " + fd(addD(TODAY, -l[3]), true)), ui.pill(l[5], l[6], l[5] === "crit" ? "alertfill" : l[5] === "ok" ? "checkcircle" : "clockh"));
    }).join("") + "</div>", { icon: "truck" });
    return ptBar(dp.name, dp.sub, ui.pill("ok", "No LA / latex allergy", "checkcircle") + ui.pill("info", "Shade A2")) + chart + plan + '<div class="sp-g2">' + note + '<div class="stack s16">' + att + lab + "</div></div>";
  }

  /* ================= Paediatrics ================= */
  var ZC = [-1.881, -1.036, 0, 1.036, 1.881];
  function interp(ref, age, k) { for (var i = 0; i < ref.length - 1; i++) if (age <= ref[i + 1][0]) { var a = ref[i], b = ref[i + 1], f = (age - a[0]) / (b[0] - a[0]); return a[k] + f * (b[k] - a[k]); } return ref[ref.length - 1][k]; }
  function centile(ref, age, val) {
    var c = [1, 2, 3, 4, 5].map(function (k) { return interp(ref, age, k); }), z = 0;
    if (val <= c[0]) z = ZC[0] - (c[0] - val) / (c[1] - c[0]) * (ZC[1] - ZC[0]);
    else if (val >= c[4]) z = ZC[4] + (val - c[4]) / (c[4] - c[3]) * (ZC[4] - ZC[3]);
    else for (var i = 0; i < 4; i++) if (val <= c[i + 1]) { z = ZC[i] + (val - c[i]) / (c[i + 1] - c[i]) * (ZC[i + 1] - ZC[i]); break; }
    return Math.max(0.1, Math.min(99.9, ncdf(z) * 100));
  }
  function cfmt(p) { return p < 1 ? "<1st" : p > 99 ? ">99th" : Math.round(p) + (/1$/.test(Math.round(p)) && Math.round(p) !== 11 ? "st" : /2$/.test(Math.round(p)) && Math.round(p) !== 12 ? "nd" : /3$/.test(Math.round(p)) && Math.round(p) !== 13 ? "rd" : "th"); }
  function gpoints() { var t = v("spGrowToday", null), p = SD.gpts.slice(); if (t) p[p.length - 1] = [74, t.w, t.h, "Today"]; return p; }
  function growthSvg(kind) {
    var hfa = kind === "hfa", ref = hfa ? SD.hfa : SD.wfa, col = hfa ? 2 : 1, W = 560, H = 300, l = 42, r = 46, t = 12, b = 30;
    var y0 = hfa ? 40 : 0, y1 = hfa ? 135 : 32, step = hfa ? 10 : 4;
    var X = function (m) { return l + m / 84 * (W - l - r); }, Y = function (vv) { return H - b - (vv - y0) / (y1 - y0) * (H - t - b); };
    var s = '<svg class="sp-svg" viewBox="0 0 ' + W + " " + H + '" role="img" aria-label="' + esc((hfa ? "Height" : "Weight") + "-for-age chart, girls, WHO centiles 3rd to 97th, with the child's measurements") + '">';
    for (var yy = y0; yy <= y1; yy += step) s += '<line class="sp-gl" x1="' + l + '" x2="' + (W - r) + '" y1="' + Y(yy) + '" y2="' + Y(yy) + '"/><text class="t3" x="' + (l - 6) + '" y="' + (Y(yy) + 3) + '" text-anchor="end">' + yy + "</text>";
    for (var m = 0; m <= 84; m += 12) s += '<line class="sp-gl" x1="' + X(m) + '" x2="' + X(m) + '" y1="' + t + '" y2="' + (H - b) + '"/><text class="t3" x="' + X(m) + '" y="' + (H - b + 14) + '" text-anchor="middle">' + (m ? m / 12 + " y" : "0") + "</text>";
    s += '<text class="t3" x="' + (l - 34) + '" y="' + (t + 4) + '">' + (hfa ? "cm" : "kg") + "</text>";
    var pts = function (k) { return ref.map(function (rw) { return X(rw[0]) + "," + Y(rw[k]); }); };
    s += '<polygon class="sp-band" points="' + pts(2).concat(pts(4).reverse()).join(" ") + '"/>';
    [[1, "c3", "3rd"], [2, "c15", "15th"], [3, "c50", "50th"], [4, "c15", "85th"], [5, "c3", "97th"]].forEach(function (c) {
      s += '<polyline class="sp-cl ' + c[1] + '" points="' + pts(c[0]).join(" ") + '"/><text class="t3" x="' + (W - r + 4) + '" y="' + (Y(ref[ref.length - 1][c[0]]) + 3) + '">' + c[2] + "</text>";
    });
    var gp = gpoints();
    s += '<polyline class="sp-pts" points="' + gp.map(function (p) { return X(p[0]) + "," + Y(p[col]); }).join(" ") + '"/>';
    gp.forEach(function (p, i) { var last = i === gp.length - 1; s += '<circle class="sp-pt0' + (last ? " now" : "") + '" cx="' + X(p[0]) + '" cy="' + Y(p[col]) + '" r="' + (last ? 5 : 3.5) + '"><title>' + esc(p[3] + ": " + p[col] + (hfa ? " cm" : " kg") + " · " + cfmt(centile(ref, p[0], p[col])) + " centile") + "</title></circle>"; });
    var lp = gp[gp.length - 1];
    s += '<text class="t1" x="' + (X(lp[0]) - 8) + '" y="' + (Y(lp[col]) + 18) + '" text-anchor="end">' + esc(lp[col] + (hfa ? " cm" : " kg") + " · " + cfmt(centile(ref, lp[0], lp[col]))) + "</text>";
    return s + "</svg>";
  }
  function vaxSched() {
    if (NB.gcc()) { var nm = { uae: "UAE National Immunisation Programme (MOHAP / DHA)", ksa: "MOH National Immunization Schedule (Saudi Arabia)", qatar: "MOPH National Immunization Schedule (Qatar)" }[NB.state.market]; return { key: "gcc", name: nm, rows: SD.vax.gcc.rows }; }
    var k = v("spVaxS", "iap"); return { key: k, name: SD.vax[k].name, rows: SD.vax[k].rows };
  }
  function vaxStatus(r) { return r[1] < 48 ? "given" : r[1] === 74 ? "due" : r[1] < 74 ? "overdue" : "upcoming"; }
  function pedWork(c) {
    var tab = v("spPedTab", "growth"), dob = SD.child.dob;
    var tabs = ui.tabs("spPedTab", [["growth", L("Growth (WHO)", "النمو (WHO)", "ग्रोथ (WHO)"), "ruler"], ["vax", L("Immunisation", "التطعيمات", "टीकाकरण"), "syringe"], ["dev", L("Milestones", "مراحل النمو", "विकास के पड़ाव"), "star"], ["dose", L("Weight-based dosing", "الجرعات حسب الوزن", "वज़न-आधारित खुराक"), "pill"]], "growth");
    var head = ptBar(NB.pname(NB.pat("p4")), "6 y 2 m · F · DOB " + fd(dob) + " · birth " + SD.child.bw + " at " + SD.child.ga + " · " + SD.child.guardian, ui.pill("warn", "Egg allergy · hives", "alertcircle") + ui.pill("crit", "Booster overdue", "syringe"));
    var body = "";
    if (tab === "growth") {
      var k = v("spGrowK", "wfa"), gp = gpoints(), last = gp[gp.length - 1], w3 = centile(SD.wfa, 36, 13.4), wn = centile(SD.wfa, last[0], last[1]), hn = centile(SD.hfa, last[0], last[2]), bmi = last[1] / Math.pow(last[2] / 100, 2);
      var rows = gp.slice().reverse().map(function (p) { return [esc(p[3]), n(p[1].toFixed(2) + " kg"), '<span class="small">' + cfmt(centile(SD.wfa, p[0], p[1])) + "</span>", n(p[2].toFixed(1) + " cm"), '<span class="small">' + cfmt(centile(SD.hfa, p[0], p[2])) + "</span>"]; });
      body = (wn < 15 && w3 - wn > 20 ? ui.note("crit", "<b>Growth faltering (PED-04):</b> weight has dropped across two centile lines since age 3 (≈" + cfmt(w3) + " → " + cfmt(wn) + "); height on the " + cfmt(hn) + ". BMI " + bmi.toFixed(1) + " kg/m². Ask about diet with egg allergy, check CBC, ferritin, TSH and coeliac serology; consider dietitian referral. Zoya is on the growth-watch list.") : "") +
        card(L("WHO growth chart", "مخطط النمو (WHO)", "WHO ग्रोथ चार्ट") + " · PED-03", '<div class="stack"><div class="row sb">' + ui.seg("spGrowK", [["wfa", L("Weight-for-age", "الوزن للعمر", "आयु के अनुसार वज़न")], ["hfa", L("Height-for-age", "الطول للعمر", "आयु के अनुसार लंबाई")]], "wfa") +
          '<span class="small faint">Girls · WHO Child Growth Standards 0–5 y, Growth Reference 2007 from 5 y</span></div><div class="sp-box">' + growthSvg(k) + "</div>" +
          '<div class="sp-leg"><span><i class="sp-ln" style="border-color:var(--ok)"></i>50th</span><span><i class="sp-ln" style="border-color:var(--tx3);border-top-style:dashed"></i>15th / 85th</span><span><i class="sp-ln" style="border-color:var(--warn);border-top-style:dashed"></i>3rd / 97th</span><span><i class="sp-ln" style="border-color:var(--acc)"></i>Zoya</span></div>' +
          '<div class="btns">' + ui.btn(L("Record growth", "تسجيل النمو", "ग्रोथ दर्ज करें"), { cls: "pri", ov: "growthpoint", icon: "ruler" }) + ui.btn(L("Share chart with parent", "مشاركة المخطط مع الوالد", "चार्ट अभिभावक को भेजें"), { act: "toast", arg: "Growth chart PDF sent to Imran Khan on WhatsApp", icon: "send" }) + "</div></div>", { icon: "ruler" }) +
        ui.table([{ h: L("Age", "العمر", "आयु") }, { h: L("Weight", "الوزن", "वज़न") }, { h: L("Centile", "المئين", "सेंटाइल") }, { h: L("Height", "الطول", "लंबाई") }, { h: L("Centile", "المئين", "सेंटाइल") }], rows, { min: 480 }) +
        '<p class="tiny faint" style="margin:0">Head circumference is plotted only under 2 years (hidden for this child). Centiles are approximate in the prototype.</p>';
    } else if (tab === "vax") {
      var S = vaxSched(), given = 0, odue = 0;
      var rows2 = S.rows.map(function (r, i) {
        var st = vaxStatus(r), dd = addD(dob, Math.round(r[1] * 30.44)), kind = { given: "ok", due: "warn", overdue: "crit", upcoming: "mute" }[st], ic = { given: "checkcircle", due: "clockh", overdue: "alertfill", upcoming: "calendar" }[st];
        if (st === "given") given += r[2].length; if (st === "overdue") odue += r[2].length;
        var lbl = { given: "Given", due: "Due now", overdue: "Overdue", upcoming: "Upcoming" }[st];
        var chips = '<div class="chips">' + r[2].map(function (x) { return '<span class="sp-vx ' + kind + '">' + ui.icon(ic) + esc(x) + "</span>"; }).join("") + "</div>";
        var info = st === "given" ? '<span class="small">' + fd(dd) + '</span><small class="faint" style="display:block">Batch ' + ("B" + (2400 + i * 37)) + " · " + (r[1] === 0 ? "BCG L upper arm ID · OPV oral" : r[1] < 12 ? (i % 2 ? "L thigh IM" : "R thigh IM") + (/Rota/.test(r[2].join()) ? " · rota oral" : "") : /MMR|Varic/.test(r[2].join()) ? "R upper arm SC" : "L deltoid IM") + "</small>"
          : st === "upcoming" ? '<span class="small">' + fd(dd) + "</span>" : '<span class="small">' + (st === "due" ? "Oct 2026 season" : fd(addD(dob, 48 * 30.44 | 0)) + " – " + fd(dd)) + "</span>";
        var act = st === "due" || st === "overdue" ? ui.btn(L("Record", "تسجيل", "दर्ज करें"), { cls: "sm" + (st === "overdue" ? " pri" : ""), ov: "clvaccine", arg: /MMR|DTa|DTw|DPT/.test(r[2].join()) ? (/MMR/.test(r[2].join()) ? "MMR" : "DTaP") : "MMR", icon: "syringe" }) : "";
        return { cls: st === "overdue" ? "crit" : "", cells: ['<b class="small">' + esc(r[0]) + "</b>", chips, info, ui.pill(kind, lbl, ic), act] };
      });
      body = '<div class="row sb">' + (NB.gcc() ? ui.pill("info", "Schedule set by market · " + NB.mkt().name, "globe") : ui.seg("spVaxS", [["iap", "IAP 2023"], ["uip", "UIP (national)"]], "iap")) + '<span class="small faint">' + esc(S.name) + " · generated from date of birth (PED-01)</span></div>" +
        '<div class="chips">' + ui.pill("ok", given + " doses given", "checkcircle") + ui.pill("crit", odue + " overdue", "alertfill") + ui.pill("warn", "Influenza due this season", "clockh") + "</div>" +
        ui.table([{ h: L("Age", "العمر", "आयु") }, { h: L("Vaccines", "اللقاحات", "टीके") }, { h: L("Date · batch · site", "التاريخ · التشغيلة · الموضع", "तारीख़ · बैच · स्थान") }, { h: L("Status", "الحالة", "स्थिति") }, { h: "" }], rows2, { min: 760 }) +
        ui.note("info", "Egg allergy (hives) is not a contraindication to MMR or influenza vaccine; give in clinic with the standard observation period. Recording a dose stores batch and site on the permanent record and creates the parent's WhatsApp reminder for the next dose (PED-02).");
    } else if (tab === "dev") {
      var ms = SD.ms6.map(function (x) { var on = v("spMs_" + x[0], x[2]); return '<div class="tog" data-toggle="spMs_' + x[0] + '" role="switch" aria-checked="' + on + '" tabindex="0"><div class="t"><b>' + esc(x[1]) + "</b><small>" + (on ? "Achieved" : "Not yet · discuss with parent") + "</small></div>" + sw(on) + "</div>"; }).join("");
      var notYet = SD.ms6.filter(function (x) { return !v("spMs_" + x[0], x[2]); }).length;
      body = '<div class="sp-g2">' + card(L("Milestones · 5–6 years", "مراحل النمو · ٥–٦ سنوات", "पड़ाव · 5–6 वर्ष") + " · PED-08", ms + (notYet >= 3 ? ui.note("warn", "3 or more milestones not yet met: a concern flag creates a follow-up recall in 3 months and suggests a developmental screen.") : '<p class="tiny faint" style="margin:10px 0 0">' + notYet + " not yet achieved: within normal variation at 6 y 2 m. Re-check at the next visit.</p>"), { icon: "star" }) +
        card(L("Milestone history", "سجل مراحل النمو", "पड़ाव इतिहास"), '<div class="tl">' + SD.milestones.map(function (m) { return '<div class="ev"><span class="dotc ok">' + ui.icon("check", "sm") + '</span><div class="bd"><b>' + esc(m[0]) + "</b><small>" + esc(m[1]) + "</small></div></div>"; }).join("") + "</div>", { icon: "history" }) + "</div>";
    } else {
      var wt = (v("spGrowToday", null) || { w: 16.4 }).w;
      var drugs = [["Paracetamol", 15, "every 6 h", "250 mg / 5 mL", 250 / 5, "max 60 mg/kg/day"], ["Ibuprofen", 10, "every 8 h with food", "100 mg / 5 mL", 100 / 5, "max 30 mg/kg/day · avoid if dehydrated"], ["Amoxicillin", 25, "twice daily (50 mg/kg/day)", "250 mg / 5 mL", 250 / 5, "max 1 g per dose"], ["Ondansetron", 0.15, "single dose", "2 mg / 5 mL", 2 / 5, "max 4 mg per dose"]];
      body = card(L("Weight-based dose calculator", "حاسبة الجرعة حسب الوزن", "वज़न-आधारित खुराक कैलकुलेटर") + " · PED-05", '<div class="row" style="margin-bottom:12px">' + ui.pill("ok", "Weight " + wt.toFixed(2) + " kg · measured today", "checkcircle") + '<span class="small faint">Prompts for a new weight if the last one is older than 3 months</span></div>' +
        ui.table([{ h: L("Medicine", "الدواء", "दवा") }, { h: "mg/kg" }, { h: L("Dose", "الجرعة", "खुराक") }, { h: L("Volume", "الحجم", "मात्रा") }, { h: L("Limit", "الحد", "सीमा") }], drugs.map(function (d) {
          var mg = Math.round(d[1] * wt * 10) / 10; return [ui.cellp(esc(d[0]), esc(d[3])), n(d[1]), ui.cellp(n(mg + " mg"), esc(d[2])), n((Math.round(mg / d[4] * 10) / 10) + " mL"), '<span class="small faint">' + esc(d[5]) + "</span>"];
        }), { min: 600 }) + '<p class="tiny faint" style="margin:10px 0 0">The suggested dose is always editable; the final choice is logged. Check the allergy list and the formulation in stock.</p>', { icon: "pill" });
    }
    return '<div class="stack s16">' + tabs + head + body + "</div>";
  }

  /* ================= ENT ================= */
  function audVal(k, i) { var o = v("spAudIn_" + k + ":" + i, null); return o != null && o !== "" && !isNaN(+o) ? Math.max(-10, Math.min(120, +o)) : SD.aud[k][i]; }
  function pta(k) { return Math.round((audVal(k, 1) + audVal(k, 2) + audVal(k, 3)) / 3 * 10) / 10; }
  function grade(p) { return p < 20 ? ["ok", "Normal"] : p < 35 ? ["warn", "Mild"] : p < 50 ? ["urg", "Moderate"] : p < 65 ? ["crit", "Moderately severe"] : ["crit", "Severe"]; }
  function audiogram() {
    var W = 520, H = 320, l = 46, r = 18, t = 34, b = 14, F = SD.freqs, prev = v("spAudPrev", false);
    var X = function (i) { return l + i * (W - l - r) / (F.length - 1); }, Y = function (db) { return t + (db + 10) / 130 * (H - t - b); };
    var s = '<svg class="sp-svg" viewBox="0 0 ' + W + " " + H + '" role="img" aria-label="Audiogram: right ear red circles, left ear blue crosses, bone conduction arrows">';
    s += '<rect class="sp-norm" x="' + l + '" y="' + Y(-10) + '" width="' + (W - l - r) + '" height="' + (Y(20) - Y(-10)) + '"/>';
    for (var db = -10; db <= 120; db += 10) s += '<line class="' + (db === 20 ? "sp-gl2" : "sp-gl") + '" x1="' + l + '" x2="' + (W - r) + '" y1="' + Y(db) + '" y2="' + Y(db) + '"/><text class="t3" x="' + (l - 6) + '" y="' + (Y(db) + 3) + '" text-anchor="end">' + db + "</text>";
    F.forEach(function (f, i) { s += '<line class="sp-gl" x1="' + X(i) + '" x2="' + X(i) + '" y1="' + t + '" y2="' + (H - b) + '"/><text class="t3" x="' + X(i) + '" y="' + (t - 10) + '" text-anchor="middle">' + (f >= 1000 ? f / 1000 + "k" : f) + "</text>"; });
    s += '<text class="t3" x="' + (l - 40) + '" y="' + (t - 10) + '">Hz</text><text class="t3" x="' + (l + 6) + '" y="' + (Y(20) - 4) + '">Normal (≤ 20 dB HL)</text>';
    if (prev) s += '<polyline class="sp-cl c15" points="' + SD.audPrev.rAC.map(function (vv, i) { return X(i) + "," + Y(vv); }).join(" ") + '"/>';
    s += '<polyline class="sp-ar" points="' + F.map(function (f, i) { return X(i) + "," + Y(audVal("rAC", i)); }).join(" ") + '"/>';
    s += '<polyline class="sp-al ln" points="' + F.map(function (f, i) { return X(i) + "," + Y(audVal("lAC", i)); }).join(" ") + '"/>';
    F.forEach(function (f, i) {
      var ry = Y(audVal("rAC", i)), ly = Y(audVal("lAC", i));
      s += '<circle class="sp-ar f" cx="' + X(i) + '" cy="' + ry + '" r="6"/>';
      s += '<path class="sp-al" d="M' + (X(i) - 5) + " " + (ly - 5) + " L" + (X(i) + 5) + " " + (ly + 5) + " M" + (X(i) + 5) + " " + (ly - 5) + " L" + (X(i) - 5) + " " + (ly + 5) + '"/>';
      if (SD.aud.rBC[i] != null) s += '<text class="sr" x="' + (X(i) - 14) + '" y="' + (Y(audVal("rBC", i)) + 4) + '" text-anchor="middle">&lt;</text>';
      if (SD.aud.lBC[i] != null) s += '<text class="sl" x="' + (X(i) + 14) + '" y="' + (Y(audVal("lBC", i)) + 4) + '" text-anchor="middle">&gt;</text>';
    });
    return s + "</svg>";
  }
  function tmSvg() {
    return '<svg class="sp-svg" viewBox="0 0 240 180" role="img" aria-label="Placeholder otoscopy illustration, right tympanic membrane"><rect width="240" height="180" style="fill:var(--sunk)"/><circle cx="120" cy="90" r="74" style="fill:var(--s1);stroke:var(--b-str)"/>' +
      '<ellipse cx="120" cy="90" rx="56" ry="60" style="fill:var(--s3);stroke:var(--b-str);stroke-width:1.5"/><path d="M118 42 L128 98" style="stroke:var(--tx3);stroke-width:4;stroke-linecap:round"/>' +
      '<path d="M76 108 Q120 98 164 108" style="fill:none;stroke:var(--info);stroke-width:1.5;stroke-dasharray:4 3"/><circle cx="96" cy="122" r="4" style="fill:none;stroke:var(--info)"/><circle cx="140" cy="126" r="3" style="fill:none;stroke:var(--info)"/>' +
      '<text x="12" y="170" class="t3">R TM · dull, retracted, fluid level</text></svg>';
  }
  function entWork(c) {
    var tab = v("spEntTab", "aud"), body = "";
    var tabs = ui.tabs("spEntTab", [["aud", L("Audiogram", "مخطط السمع", "ऑडियोग्राम"), "ear"], ["endo", L("Endoscopy", "التنظير", "एंडोस्कोपी"), "image"], ["proc", L("Procedures", "الإجراءات", "प्रक्रियाएँ"), "pen"]], "aud");
    var head = ptBar(NB.pname(NB.pat("p7")), "12 M · ear pain right 5 days, blocked feeling · asthma on salbutamol", ui.pill("ok", "No drug allergy", "checkcircle") + ui.pill("info", "Minor · guardian"));
    if (tab === "aud") {
      var pr = pta("rAC"), pl = pta("lAC"), gr = grade(pr), gl = grade(pl), abg = Math.round(pr - (audVal("rBC", 1) + audVal("rBC", 2) + audVal("rBC", 3)) / 3);
      var grid = '<div class="tw"><table class="t" style="min-width:560px"><thead><tr><th>dB HL</th>' + SD.freqs.map(function (f) { return "<th>" + f + "</th>"; }).join("") + "</tr></thead><tbody>" +
        [["rAC", "Right · air", "sr"], ["rBC", "Right · bone", "sr"], ["lAC", "Left · air", "sl"], ["lBC", "Left · bone", "sl"]].map(function (rw) {
          return "<tr><td><b class=\"small\">" + esc(rw[1]) + "</b></td>" + SD.freqs.map(function (f, i) { return "<td>" + (SD.aud[rw[0]][i] == null ? '<span class="faint">—</span>' : '<input class="sp-ain" aria-label="' + esc(rw[1] + " " + f + " Hz") + '" inputmode="numeric" data-change="spAudIn_' + rw[0] + ":" + i + '" value="' + audVal(rw[0], i) + '">') + "</td>"; }).join("") + "</tr>";
        }).join("") + "</tbody></table></div>";
      body = card(L("Pure-tone audiogram", "مخطط السمع النغمي", "प्योर-टोन ऑडियोग्राम") + " · ENT-02", '<div class="sp-box" style="max-width:640px;margin-inline:auto">' + audiogram() + "</div>" +
        '<div class="sp-leg" style="margin-top:10px"><span><b class="critc">O</b> Right air</span><span><b class="critc">&lt;</b> Right bone</span><span><b class="infoc">X</b> Left air</span><span><b class="infoc">&gt;</b> Left bone</span></div>' +
        '<div class="tog" data-toggle="spAudPrev" role="switch" aria-checked="' + !!v("spAudPrev", false) + '" tabindex="0"><div class="t"><b>Compare with 12 Jun 2026</b><small>Right air conduction then: PTA 17 dB (normal)</small></div>' + sw(v("spAudPrev", false)) + "</div>", { icon: "ear" }) +
        card(L("Summary", "الملخص", "सारांश"), '<dl class="sp-kv"><dt>PTA right</dt><dd>' + n(pr + " dB") + " " + ui.pill(gr[0], gr[1]) + "</dd><dt>PTA left</dt><dd>" + n(pl + " dB") + " " + ui.pill(gl[0], gl[1]) + "</dd><dt>Air–bone gap R</dt><dd>" + n(abg + " dB") + (abg >= 15 ? " · conductive" : "") + "</dd><dt>Tympanometry</dt><dd>R type B (flat) · L type A</dd></dl>" +
          ui.note("info", "Impression: right conductive hearing loss with flat tympanogram, in keeping with otitis media with effusion. Plan: autoinflation, review with repeat audiogram in 6–8 weeks (auto recall).") +
        '<p class="tiny faint" style="margin:10px 0 0">Grades: WHO World Report on Hearing 2021. PTA here is the 0.5 / 1 / 2 kHz average.</p>', { icon: "clipboard" }) +
        card(L("Grid entry · air and bone conduction", "إدخال القيم · هوائي وعظمي", "ग्रिड एंट्री · वायु और अस्थि चालन"), grid + '<p class="tiny faint" style="margin:8px 0 0">Change a value and press Enter: the audiogram redraws. Or attach the audiometer image instead.</p>', { icon: "edit" });
    } else if (tab === "endo") {
      var tags = [["Ear", ["TM dull, retracted (R)", "Fluid level / bubbles (R)", "Light reflex absent (R)", "TM normal (L)"]], ["Nose", ["DNS to left", "Inferior turbinate hypertrophy", "Mucopurulent discharge"]], ["Throat", ["Tonsils grade 2", "Post-nasal drip", "Adenoid facies"]]];
      var on = v("spEntTags", { "TM dull, retracted (R)": 1, "Fluid level / bubbles (R)": 1, "Light reflex absent (R)": 1, "TM normal (L)": 1, "Tonsils grade 2": 1 });
      body = '<div class="sp-g2">' + card(L("Otoendoscopy capture", "التقاط التنظير", "ओटोएंडोस्कोपी कैप्चर") + " · ENT-01", '<div class="sp-photo">' + tmSvg() + '<span class="sp-cap">R ear · 10:05</span></div><div class="sp-thumbs" style="margin-top:10px">' +
        ["R ear 10:05", "L ear 10:06", "Nose 10:08"].map(function (x, i) { return '<button class="sp-th' + (i === 0 ? " b" : "") + '" data-act="toast" data-arg="' + esc(x) + ' opened beside the note"><svg viewBox="0 0 90 60" aria-hidden="true"><rect width="90" height="60" style="fill:var(--sunk)"/><circle cx="45" cy="30" r="22" style="fill:var(--s3);stroke:var(--b-str)"/></svg>' + esc(x) + "</button>"; }).join("") + "</div>" +
        '<div class="btns" style="margin-top:10px">' + ui.btn(L("Capture image / 2-min video", "التقاط صورة / فيديو", "छवि / वीडियो कैप्चर"), { cls: "sm", act: "toast", arg: "Scope feed captured and attached to today's visit", icon: "camera" }) + "</div>", { icon: "image" }) +
        card(L("Finding tags", "وسوم النتائج", "फ़ाइंडिंग टैग"), tags.map(function (g) { return '<div class="stack s8" style="margin-bottom:12px"><span class="eyebrow">' + esc(g[0]) + '</span><div class="chips">' + g[1].map(function (tg) { return chip(!!on[tg], tg, { act: "sp-enttag", arg: tg }); }).join("") + "</div></div>"; }).join("") +
          '<dl class="sp-kv"><dt>Note</dt><dd>' + esc(Object.keys(on).filter(function (k) { return on[k]; }).join("; ") || "—") + "</dd></dl>", { icon: "list" }) + "</div>";
    } else {
      var pc = [["ear", "Ear wax removal (syringing / microsuction)", "Vikas R. · 31 M · left ear", "pend"], ["cau", "Nasal cautery (silver nitrate), Little's area", "Sara T. · 8 F · recurrent epistaxis", "ok"], ["fb", "Foreign-body removal, nose", "Template ready", "tmpl"]];
      body = card(L("Minor procedures + consent", "الإجراءات البسيطة + الموافقة", "छोटी प्रक्रियाएँ + सहमति") + " · ENT-03", '<div class="lst">' + pc.map(function (p) {
        var signed = v("spSigned", {})[p[0] + "|Vikas R."];
        return ui.li(lead(p[3] === "ok" || signed ? "ok" : p[3] === "pend" ? "warn" : "mute", "pen"), esc(p[1]), esc(p[2]), p[3] === "pend" && !signed ? ui.btn(L("Consent", "الموافقة", "सहमति"), { cls: "sm", ov: "procconsent", arg: "ear|Vikas R.", icon: "pen" }) : p[3] === "tmpl" ? ui.pill("mute", "Template") : ui.pill("ok", "Consent signed", "shieldcheck"));
      }).join("") + "</div>" + '<p class="tiny faint" style="margin:10px 0 0">Templates cover ear syringing, cautery and foreign-body removal. The note is completed by taps; consent is signed on the device before the procedure and stored to the visit (DOC-09).</p>', { icon: "pen" });
    }
    return '<div class="stack s16">' + tabs + head + body + "</div>";
  }

  /* ================= Orthopaedics & physio ================= */
  function romGauge(val, target, normal) {
    var cx = 110, cy = 104, R = 82, P = function (d) { var th = Math.PI - d / 150 * Math.PI; return (cx + R * Math.cos(th)).toFixed(1) + " " + (cy - R * Math.sin(th)).toFixed(1); };
    var arc = function (a, b2, cls) { return '<path class="sp-arc ' + cls + '" d="M' + P(a) + " A" + R + " " + R + " 0 0 1 " + P(b2) + '"/>'; };
    var tick = function (d, cls, lbl) { var th = Math.PI - d / 150 * Math.PI, x1 = cx + (R - 12) * Math.cos(th), y1 = cy - (R - 12) * Math.sin(th), x2 = cx + (R + 12) * Math.cos(th), y2 = cy - (R + 12) * Math.sin(th); return '<line x1="' + x1 + '" y1="' + y1 + '" x2="' + x2 + '" y2="' + y2 + '" style="stroke:var(--' + cls + ');stroke-width:2"/><text x="' + (cx + (R + 22) * Math.cos(th)) + '" y="' + (cy - (R + 22) * Math.sin(th) + 3) + '" text-anchor="middle" class="t3">' + lbl + "</text>"; };
    return '<svg class="sp-svg" viewBox="0 0 220 124" role="img" aria-label="' + esc("Knee flexion " + val + " degrees, target " + target + ", normal " + normal) + '">' + arc(0, 150, "") + arc(0, val, "v") + tick(target, "ok", target + "°") + tick(normal, "tx3", normal + "°") +
      '<text x="' + cx + '" y="' + (cy - 14) + '" text-anchor="middle" class="t1" style="font-size:24px">' + val + '°</text><text x="' + cx + '" y="' + (cy + 4) + '" text-anchor="middle" class="t3">R knee flexion</text></svg>';
  }
  function orthWork(c) {
    var tab = v("spOrthTab", "rom"), body = "", sess = 5 + (v("spPhysioDone", false) ? 1 : 0);
    var tabs = ui.tabs("spOrthTab", [["rom", L("Range of motion", "مدى الحركة", "गति की सीमा"), "ruler"], ["ex", L("Exercise plan", "خطة التمارين", "व्यायाम योजना"), "activity"], ["inj", L("Injury, X-ray & cast", "الإصابة والأشعة والجبيرة", "चोट, एक्स-रे और कास्ट"), "bone"]], "rom");
    var head = ptBar(NB.pname(NB.pat("p8")), "67 M · right knee osteoarthritis (Kellgren–Lawrence 3) · physio block 10 sessions", ui.pill("warn", "CKD 3 · avoid oral NSAIDs", "alertcircle") + ui.pill("sub", "Physio " + sess + "/10 done", "layers"));
    if (tab === "rom") {
      var rows = SD.rom.map(function (r) {
        var pctR = r[2] ? Math.max(0, Math.round(r[3] / r[2] * 100)) : r[3] === 0 ? 100 : Math.max(0, 100 + r[3] * 3), pctL = r[2] ? Math.round(r[4] / r[2] * 100) : r[4] === 0 ? 100 : 100 + r[4] * 3;
        return [ui.cellp(esc(r[0]), "Normal " + esc(r[1])), '<div class="stack s4"><span class="small">' + n(r[3] + "°") + " · " + pctR + "%</span>" + ui.bar(pctR, pctR < 85 ? "warn" : "ok") + "</div>", '<div class="stack s4"><span class="small">' + n(r[4] + "°") + " · " + pctL + "%</span>" + ui.bar(pctL, pctL < 85 ? "warn" : "ok") + "</div>", r[5] === "ok" ? ui.pill("ok", "Functional", "checkcircle") : ui.pill("warn", "Restricted", "alertcircle")];
      });
      body = '<div class="sp-g2">' + card(L("Knee flexion", "ثني الركبة", "घुटना मोड़ना"), '<div class="sp-box">' + romGauge(100, 115, 135) + "</div>" + '<div class="stack s4" style="margin-top:10px"><span class="eyebrow">' + L("Across physio sessions", "عبر جلسات العلاج", "फ़िज़ियो सत्रों में") + "</span>" + spark(SD.romTrend.map(function (x) { return ["S" + x[0], x[1], x[1] + "°"]; }), { target: 115, tl: "goal 115°", min: 80, max: 120, label: "Knee flexion over sessions" }) + "</div>", { icon: "ruler" }) +
        card(L("Outcome measures", "مقاييس النتائج", "परिणाम माप"), '<dl class="sp-kv"><dt>WOMAC (0–96)</dt><dd>' + n("52 → 38") + " " + ui.pill("ok", "Better", "checkcircle") + "</dd><dt>Pain NRS</dt><dd>" + n("6 → 4") + "</dd><dt>Timed Up and Go</dt><dd>" + n("14 s → 11 s") + "</dd><dt>Quadriceps power</dt><dd>MRC 4/5 right · 5/5 left</dd></dl>" +
          ui.note("info", "Goal by session 10: flexion ≥ 115°, no extension lag, stairs reciprocal with rail."), { icon: "chart" }) + "</div>" +
        ui.table([{ h: L("Movement", "الحركة", "गति") }, { h: L("Right (active)", "الأيمن (نشط)", "दायाँ (सक्रिय)") }, { h: L("Left (active)", "الأيسر (نشط)", "बायाँ (सक्रिय)") }, { h: "" }], rows, { min: 620 });
    } else if (tab === "ex") {
      body = card(L("Home exercise plan", "خطة التمارين المنزلية", "घरेलू व्यायाम योजना") + " · " + L("week 3", "الأسبوع ٣", "सप्ताह 3"), ui.table([{ h: L("Exercise", "التمرين", "व्यायाम") }, { h: L("Dose", "الجرعة", "मात्रा") }, { h: L("How often", "التكرار", "कितनी बार") }, { h: L("Phase", "المرحلة", "चरण") }], SD.ex.map(function (e) { return [ui.cellp(esc(e[0]), esc(e[4])), n(esc(e[1])), esc(e[2]), '<span class="small">' + esc(e[3]) + "</span>"]; }), { min: 620 }) +
        '<div class="btns" style="margin-top:12px">' + ui.btn(L("Send plan with videos on WhatsApp", "إرسال الخطة مع الفيديوهات", "वीडियो सहित योजना भेजें"), { cls: "pri", act: "toast", arg: "Exercise plan with 6 short videos sent to Mohammed Ali and his son (caregiver)", icon: "send" }) + ui.btn(L("Progress to week 4", "التقدم للأسبوع ٤", "सप्ताह 4 पर बढ़ाएँ"), { act: "toast", arg: "Plan progressed: step height 15 cm, band resistance up one level", icon: "arrowright" }) + "</div>", { icon: "activity" }) +
        card(L("Physio package", "باقة العلاج الطبيعي", "फ़िज़ियो पैकेज") + " · ORTH-03", '<div class="row sb"><span class="sp-dots" aria-label="' + sess + ' of 10 sessions">' + Array.apply(null, Array(10)).map(function (_, j) { return '<i class="' + (j < sess ? "d" : j === sess ? "n" : "") + '"></i>'; }).join("") + '</span><span class="small">' + sess + " / 10 · next " + (sess > 5 ? "Mon 19 Oct 10:30" : "today 11:30") + "</span></div>" +
          '<div class="btns" style="margin-top:12px">' + (v("spPhysioDone", false) ? ui.pill("ok", "Session 6 attended", "checkcircle") : ui.btn(L("Mark session 6 attended", "تسجيل حضور الجلسة ٦", "सत्र 6 उपस्थित"), { cls: "sm okb", act: "sp-physio", icon: "check" })) + "</div>", { icon: "layers" });
    } else {
      var xr = v("spXrRev", {});
      var xrs = [["Mohammed Ali", "Knee AP + lateral, weight-bearing, R", "KL grade 3, medial joint-space narrowing"], ["Aisha P.", "Elbow AP + lateral, R", "Posterior fat pad sign; anterior humeral line intact"], ["Rohit S.", "Wrist PA + lateral, L (in cast)", "Position maintained, early callus"]];
      body = '<div class="sp-g2">' + card(L("Fracture / injury note", "ملاحظة كسر / إصابة", "फ्रैक्चर / चोट नोट") + " · ORTH-02", '<div class="stack s8"><span class="eyebrow">' + L("Site and side", "الموضع والجهة", "स्थान और पक्ष") + '</span><div class="chips">' + ["Wrist", "Elbow", "Shoulder", "Knee", "Ankle", "Back"].map(function (x) { return chip(v("spInjSite", "Wrist") === x, x, { set: ["spInjSite", x] }); }).join("") + "</div>" +
        '<div class="chips">' + ["Left", "Right"].map(function (x) { return chip(v("spInjSide", "Left") === x, x, { set: ["spInjSide", x] }); }).join("") + "</div>" +
        '<div class="fgrid">' + ui.field("Mechanism", "Fall on outstretched hand", { options: ["Fall on outstretched hand", "Twisting injury", "Direct blow", "Road traffic accident", "Sports injury"] }) + ui.field("Diagnosis", "Distal radius fracture, extra-articular") +
        ui.field("Cast applied", "12 Sep 2026", { type: "text" }) + ui.field("Cast removal", "24 Oct 2026 · 6 weeks") + "</div>" + ui.note("ok", "Cast-removal date creates a recall automatically (PAT-20) with a reminder 2 days before.") + "</div>", { icon: "bone" }) +
        '<div class="stack s16">' + card(L("X-rays pending review", "أشعة بانتظار المراجعة", "समीक्षा हेतु एक्स-रे") + " · ORTH-01", '<div class="lst">' + xrs.map(function (x, i) { return ui.li(lead(xr[i] ? "ok" : "warn", "image"), esc(x[0] + " · " + x[1]), esc(x[2]), xr[i] ? ui.pill("ok", "Reviewed 10:2" + i, "checkcircle") : ui.btn(L("Mark reviewed", "تمت المراجعة", "समीक्षित"), { cls: "sm", act: "sp-xrrev", arg: i, icon: "check" })); }).join("") + "</div>", { icon: "image" }) +
        card(L("Cast / dressing removal due", "مواعيد فك الجبيرة", "कास्ट हटाने की तारीख़"), '<div class="lst">' + SD.casts.map(function (x) { return ui.li(lead(x[4], "clockh"), esc(x[0] + " · " + x[1]), esc(x[2]), ui.pill(x[4], x[3], "calendar")); }).join("") + "</div>", { icon: "calendar" }) + "</div></div>";
    }
    return '<div class="stack s16">' + tabs + head + body + "</div>";
  }

  /* ================= Obstetrics & gynaecology ================= */
  function preg() {
    var lmp = fromIso(v("spLmp", SD.lmp)) || fromIso(SD.lmp), cyc = +v("spCyc", "28"), edd = addD(lmp, 280 + (cyc - 28)), ga = daysBetween(lmp, TODAY) - (cyc - 28);
    return { lmp: lmp, edd: edd, ga: ga, w: Math.floor(ga / 7), d: ga % 7, tri: ga < 98 ? 1 : ga < 196 ? 2 : 3, left: daysBetween(TODAY, edd) };
  }
  function gynWork(c) {
    var P = preg(), ok = P.ga >= 0 && P.ga <= 44 * 7;
    var head = ptBar(NB.pname(NB.pat("p2")), "32 F · G2 P1 L1 · blood group B Rh-positive · previous normal delivery 2022", ui.pill("acc", "Pregnant · " + P.w + " + " + P.d + " wk", "heartpulse") + ui.pill("info", "Co-managed: dermatology (melasma)"));
    var calc = card(L("EDD calculator", "حاسبة موعد الولادة", "EDD कैलकुलेटर") + " · GYN-01", '<div class="fgrid"><div class="field"><label for="sp-lmp">LMP (first day of last period)</label><input id="sp-lmp" type="date" data-change="spLmp" value="' + iso(P.lmp) + '"></div>' +
      '<div class="field"><label for="sp-cyc">' + esc(L("Cycle length", "طول الدورة", "चक्र की अवधि")) + '</label><select id="sp-cyc" data-change="spCyc">' + ["26", "28", "30", "32", "35"].map(function (x) { return "<option" + (x === String(v("spCyc", "28")) ? " selected" : "") + ' value="' + x + '">' + x + " days</option>"; }).join("") + "</select></div></div>" +
      (ok ? '<dl class="sp-kv" style="margin-top:14px"><dt>EDD</dt><dd><b>' + n(fd(P.edd)) + "</b> · Naegele's rule" + (+v("spCyc", "28") !== 28 ? " adjusted for a " + v("spCyc", "28") + "-day cycle" : "") + "</dd><dt>" + esc(L("Gestational age", "عمر الحمل", "गर्भकालीन आयु")) + "</dt><dd>" + n(P.w + " weeks + " + P.d + " days") + "</dd><dt>" + esc(L("Trimester", "الثلث", "तिमाही")) + "</dt><dd>" + ["First", "Second", "Third"][P.tri - 1] + " · " + n(P.left + " days") + " to EDD</dd><dt>" + esc(L("Dating scan", "فحص التأريخ", "डेटिंग स्कैन")) + "</dt><dd>CRL 62 mm on 05 Oct = 12 + 4 wk; within 7 days of LMP dates, so the LMP EDD is kept</dd></dl>"
        : ui.note("warn", "That LMP gives a gestational age outside 0–44 weeks. Check the date.")), { icon: "calendar" });
    var marks = SD.anc.map(function (a) { var st = a[0] * 7 <= P.ga - 4 ? "d" : Math.abs(a[0] * 7 - P.ga) < 4 ? "n" : ""; return '<span class="mk ' + st + '" style="inset-inline-start:' + (a[0] / 40 * 100) + '%" title="' + esc("Week " + a[0] + ": " + a[1]) + '">' + ui.icon(st === "d" ? "check" : st === "n" ? "dot" : "calendar") + "</span>"; }).join("");
    var tl = '<div class="sp-preg" role="img" aria-label="' + esc("Pregnancy timeline, week " + P.w + " of 40") + '"><span class="tl" style="inset-inline-start:0">' + esc(L("1st trimester", "الثلث الأول", "पहली तिमाही")) + '</span><span class="tl" style="inset-inline-start:35%">' + esc(L("2nd", "الثاني", "दूसरी")) + '</span><span class="tl" style="inset-inline-start:70%">' + esc(L("3rd", "الثالث", "तीसरी")) + "</span>" +
      '<div class="trk"><i class="t1" style="width:35%"></i><i class="t2" style="width:35%"></i><i class="t3" style="width:30%"></i></div><div class="fill" style="width:' + Math.min(100, P.ga / 280 * 100) + '%"></div>' +
      '<div class="now" style="inset-inline-start:' + Math.min(100, P.ga / 280 * 100) + '%"></div><span class="nowl" style="inset-inline-start:' + Math.min(100, P.ga / 280 * 100) + '%">' + esc(L("Today", "اليوم", "आज")) + " · " + P.w + "+" + P.d + "</span>" + marks +
      [0, 12, 20, 28, 36, 40].map(function (w) { return '<span class="wk" style="inset-inline-start:' + (w / 40 * 100) + '%">' + w + "</span>"; }).join("") + "</div>";
    var rows = SD.anc.map(function (a) {
      var st = a[0] * 7 <= P.ga - 4 ? "d" : Math.abs(a[0] * 7 - P.ga) < 4 ? "n" : "", dt = addD(P.lmp, a[0] * 7);
      return { cls: st === "n" ? "sel" : "", cells: [n(a[0] + " wk"), ui.cellp(esc(a[1]), esc(a[2])), n(fd(dt)), st === "d" ? ui.pill("ok", "Done", "checkcircle") : st === "n" ? ui.pill("acc", "Today") : ui.pill("mute", "Recall set", "calendar")] };
    });
    var sched = card(L("Pregnancy timeline & ANC schedule", "مسار الحمل وجدول المتابعة", "गर्भावस्था टाइमलाइन और ANC शेड्यूल"), tl + ui.table([{ h: L("Week", "الأسبوع", "सप्ताह") }, { h: L("Contact / test", "الزيارة / الفحص", "विज़िट / जाँच") }, { h: L("Date", "التاريخ", "तारीख़") }, { h: L("Status", "الحالة", "स्थिति") }], rows, { min: 600 }) +
      '<p class="tiny faint" style="margin:10px 0 0">' + esc(mktNote("WHO 2016 ANC model (8 contacts). India: at least 4 ANC visits (MoHFW); free PMSMA check-up on the 9th of every month; Td 2 doses; IFA and calcium from 14 weeks. Each contact creates a recall.", "WHO 2016 ANC model (8 contacts) aligned to the " + NB.mkt().regulator + " antenatal pathway; Tdap at 27–36 weeks in every pregnancy. Each contact creates a recall.")) + "</p>", { icon: "heartpulse" });
    var visits = card(L("BP and weight per ANC visit", "الضغط والوزن لكل زيارة", "हर ANC विज़िट पर BP और वज़न"), ui.table([{ h: L("Date", "التاريخ", "तारीख़") }, { h: "GA" }, { h: "BP" }, { h: L("Weight", "الوزن", "वज़न") }, { h: L("Note", "ملاحظة", "नोट") }], SD.ancVisits.map(function (r) { return [n(r[0]), n(r[1]), n(r[2]), n(r[3] + " kg"), '<span class="small">' + esc(r[4]) + "</span>"]; }), { min: 540 }), { icon: "activity" });
    var safe = card(L("Pregnancy-safe prescribing", "وصف آمن أثناء الحمل", "गर्भावस्था-सुरक्षित पर्चा") + " · GYN-03", '<div class="lst">' +
      [["crit", "lock", "Isotretinoin 20 mg", "Contraindicated (teratogen) · blocked; dermatology notified"], ["crit", "lock", "Tretinoin / hydroquinone creams", "Avoid in pregnancy · blocked with override + reason"], ["ok", "checkcircle", "Azelaic acid 20%, sunscreen", "Compatible · offered as alternatives"], ["ok", "checkcircle", "Folic acid, iron-folic acid, calcium", "Continue"]].map(function (x) { return ui.li(lead(x[0], x[1]), esc(x[2]), esc(x[3])); }).join("") + "</div>", { icon: "shieldcheck" });
    return '<div class="stack s16">' + head + '<div class="sp-g2">' + calc + safe + "</div>" + sched + visits + "</div>";
  }

  /* ================= Homeopathy ================= */
  function homWork(c) {
    var open = v("spHsec", "cc"), pot = v("spPot", "200C"), rem = v("spRem", "Natrum muriaticum");
    var head = ptBar(NB.pname(NB.pat("p9")), "41 F · chronic migraine · first long consult (case-taking 3 of 5 sections saved)", ui.pill("sub", "Long case", "file") + ui.pill("ok", "Auto-saved 10:42", "cloudsync"));
    var secs = SD.hcase.map(function (s, i) {
      var on = open === s[0], done = i < 3;
      return '<div class="sp-sec' + (on ? " on" : "") + '"><button data-set="spHsec" data-val="' + (on ? "" : s[0]) + '" aria-expanded="' + on + '">' + ui.icon(done ? "checkcircle" : "circle", "sm " + (done ? "okc" : "faint")) + '<span class="fl">' + (i + 1) + ". " + esc(s[1]) + "</span>" + ui.icon(on ? "chevdown" : "chevright", "sm faint flip") + "</button>" +
        (on ? '<div class="bd">' + (s[2] ? '<p class="small" style="margin:0">' + esc(s[2]) + "</p>" : "") + (s[3].length ? '<dl class="sp-kv">' + s[3].map(function (k) { return "<dt>" + esc(k[0]) + "</dt><dd>" + esc(k[1]) + "</dd>"; }).join("") + "</dl>" : "") + (done ? "" : ui.field("Notes", "", { area: true, ph: "Continue case-taking… saves as you type" })) + "</div>" : "") + "</div>";
    }).join("");
    var cased = card(L("Case-taking", "أخذ الحالة", "केस-टेकिंग") + " · HOM-01", '<div class="stack s8">' + secs + '<p class="tiny faint" style="margin:4px 0 0">Every section auto-saves and can be resumed another day. Follow-up visits open a short version of this form.</p></div>', { icon: "file" });
    var mx = SD.reper[0][1];
    var rep = card(L("Repertorisation", "الريبرتوري", "रिपर्टराइज़ेशन"), '<div class="stack s8">' + SD.reper.map(function (r) { return '<div class="stack s4"><div class="row sb"><span class="small"><b>' + esc(r[0]) + '</b></span><span class="small faint">' + n("score " + r[1] + " · " + r[2] + " rubrics") + "</span></div>" + ui.bar(r[1] / mx * 100) + "</div>"; }).join("") + "</div>" +
      '<p class="tiny faint" style="margin:10px 0 0">Rubrics: head pain, bursting, sun aggravates; craving salt; consolation aggravates; grief, silent.</p>', { icon: "chart" });
    var rx = card(L("Remedy & potency", "العلاج والفاعلية", "रेमेडी और पोटेंसी") + " · HOM-02", '<div class="fgrid"><div class="field full"><label for="sp-rem">Remedy</label><select id="sp-rem" data-change="spRem">' + SD.remedies.map(function (r) { return "<option" + (r === rem ? " selected" : "") + ">" + esc(r) + "</option>"; }).join("") + "</select></div></div>" +
      '<div class="stack s8" style="margin-top:12px"><span class="eyebrow">' + L("Potency", "الفاعلية", "पोटेंसी") + "</span>" + ui.seg("spPot", [["30C", "30C"], ["200C", "200C"], ["1M", "1M"], ["LM1", "LM 1"]], "200C") +
      '<span class="eyebrow" style="margin-top:6px">' + L("Dosage form", "الشكل الدوائي", "खुराक रूप") + "</span>" + ui.seg("spForm", [["glob", "Globules #30"], ["pill", "Pills"], ["drop", "Dilution drops"]], "glob") + "</div>" +
      '<div class="fgrid" style="margin-top:12px">' + ui.field("Repetition", pot === "LM1" ? "Daily in water, 10 succussions" : "Single dose, then placebo", { options: ["Single dose, then placebo", "Once daily × 3 days", "Twice daily × 7 days", "Daily in water, 10 succussions", "SOS during attack"] }) + ui.field("Review", "4 weeks (14 Nov)", { options: ["2 weeks", "4 weeks (14 Nov)", "6 weeks", "8 weeks"] }) + "</div>" +
      '<dl class="sp-kv" style="margin-top:12px"><dt>Rx line</dt><dd>' + esc(rem + " " + pot + " · " + { glob: "globules #30, 4 globules", pill: "pills, 2", drop: "dilution, 2 drops" }[v("spForm", "glob")] + " · " + (pot === "LM1" ? "daily in water" : "single dose, then Sac lac 4 weeks")) + "</dd></dl>" +
      ui.note("warn", "Red flags that need conventional referral the same day: thunderclap headache, new neurological deficit, fever with neck stiffness, headache after 50 or worsening pattern.") +
      '<div class="btns" style="margin-top:12px">' + ui.btn(L("Prescribe on letterhead", "وصف على الترويسة", "लेटरहेड पर पर्चा"), { cls: "pri", act: "toast", arg: "Remedy Rx signed and sent on WhatsApp on clinic letterhead · follow-up recall in 4 weeks", icon: "send" }) + "</div>", { icon: "flask" });
    return '<div class="stack s16">' + head + '<div class="sp-g2">' + cased + '<div class="stack s16">' + rx + rep + "</div></div></div>";
  }

  /* ================= Ayurveda ================= */
  function prakriti() {
    var c = { v: 0, p: 0, k: 0 }; SD.prak.forEach(function (q) { c[v("spPk_" + q[0], q[3])]++; });
    var t = SD.prak.length, pc = { v: Math.round(c.v / t * 100), p: Math.round(c.p / t * 100), k: Math.round(c.k / t * 100) };
    var nm = { v: "Vata", p: "Pitta", k: "Kapha" }, ord = ["v", "p", "k"].sort(function (a, b) { return c[b] - c[a]; });
    return { pc: pc, label: nm[ord[0]] + (c[ord[1]] / t >= 0.25 ? "-" + nm[ord[1]] : "") };
  }
  function ayuWork(c) {
    var pr = prakriti(), day = 7;
    var head = ptBar(NB.pname(NB.pat("p6")), "29 F · hypothyroidism on levothyroxine 50 mcg · weight gain, fatigue · panchakarma block 14 days", ui.pill("warn", "Sulfonamide allergy", "alertcircle") + ui.pill("sub", "Day " + day + " / 14", "layers"));
    var qs = SD.prak.map(function (q) { var cur = v("spPk_" + q[0], q[3]); return '<div class="sp-pq"><span class="small"><b>' + esc(q[1]) + '</b></span><div class="chips">' + q[2].map(function (o) { return '<button class="chip' + (cur === o[0] ? " on" : "") + '" data-set="spPk_' + q[0] + '" data-val="' + o[0] + '"><i class="sp-dot ' + { v: "va", p: "pi", k: "ka" }[o[0]] + '"></i>' + esc(o[1]) + "</button>"; }).join("") + "</div></div>"; }).join("");
    var pk = card(L("Prakriti assessment", "تقييم البراكرتي", "प्रकृति आकलन") + " · AYU-01", '<div class="stack s8"><div class="row sb"><b style="font-size:16px">' + esc(pr.label) + '</b><span class="small faint">' + esc(L("Stored on the profile, shown at every visit", "يُحفظ في الملف ويظهر في كل زيارة", "प्रोफ़ाइल में सहेजा, हर विज़िट पर दिखेगा")) + "</span></div>" +
      '<div class="sp-vpk" role="img" aria-label="' + esc("Vata " + pr.pc.v + "%, Pitta " + pr.pc.p + "%, Kapha " + pr.pc.k + "%") + '"><i class="va" style="width:' + pr.pc.v + '%"></i><i class="pi" style="width:' + pr.pc.p + '%"></i><i class="ka" style="width:' + pr.pc.k + '%"></i></div>' +
      '<div class="sp-leg"><span><i class="sp-dot va"></i>Vata ' + pr.pc.v + '%</span><span><i class="sp-dot pi"></i>Pitta ' + pr.pc.p + '%</span><span><i class="sp-dot ka"></i>Kapha ' + pr.pc.k + "%</span></div></div>" + qs, { icon: "leaf" });
    var notes = card(L("Clinical assessment", "التقييم السريري", "नैदानिक आकलन"), '<dl class="sp-kv"><dt>Vikriti (current)</dt><dd>Kapha aggravated, with Vata</dd><dt>Agni</dt><dd>Manda (weak digestion), heaviness after meals</dd><dt>Koshta</dt><dd>Madhyama</dd><dt>Nadi</dt><dd>Kapha-Vata, slow</dd><dt>Jihva</dt><dd>Coated (ama)</dd></dl>', { icon: "clipboard" });
    var herb = card(L("Herbal prescription", "الوصفة العشبية", "हर्बल पर्चा") + " · AYU-03", ui.table([{ h: L("Formulation", "التركيبة", "योग") }, { h: L("Dose", "الجرعة", "मात्रा") }, { h: "Anupana" }, { h: L("Timing", "التوقيت", "समय") }, { h: L("Duration", "المدة", "अवधि") }], SD.herb.map(function (h) { return [esc(h[0]), esc(h[1]), esc(h[2]), esc(h[3]), esc(h[4])]; }), { min: 600 }) +
      ui.note("warn", "Continue levothyroxine 50 mcg on an empty stomach; keep herbal powders at least 4 hours apart from it. Recheck TSH in 6 weeks (recall set). Herbal Rx does not replace thyroid hormone.") +
      '<div class="btns" style="margin-top:12px">' + ui.btn(L("Sign herbal Rx", "توقيع الوصفة العشبية", "हर्बल Rx पर हस्ताक्षर"), { cls: "pri", act: "toast", arg: "Herbal Rx signed · printable and shared on WhatsApp", icon: "pen" }) + ui.btn(L("Edit formulary", "تعديل قائمة التركيبات", "फ़ॉर्मुलरी संपादित करें"), { cls: "ghost", act: "toast", arg: "Clinic formulation list opens in settings", icon: "edit" }) + "</div>", { icon: "leaf" });
    var days = '<div class="sp-days">' + Array.apply(null, Array(14)).map(function (_, i) { var d = i + 1, cls = d < day ? "d" : d === day ? "t" : ""; return '<div class="sp-day ' + cls + '"><b>' + d + "</b>" + (d < day ? "✓" : d === day ? L("Today", "اليوم", "आज") : DOW[addD(TODAY, d - day).getDay()]) + "</div>"; }).join("") + "</div>";
    var pkc = card(L("Panchakarma · 14-day block", "بانشاكارما · ١٤ يومًا", "पंचकर्म · 14 दिन") + " · AYU-02", days + '<div class="lst" style="margin-top:12px">' + SD.pk.map(function (p) { var st = p[2] < day ? "ok" : p[1] <= day ? "acc" : "mute"; return ui.li(lead(st, st === "ok" ? "check" : st === "acc" ? "play" : "calendar"), esc(p[0]), "Day " + p[1] + (p[2] !== p[1] ? "–" + p[2] : ""), ui.pill(st, st === "ok" ? "Done" : st === "acc" ? "Today" : "Planned")); }).join("") + "</div>" +
      '<p class="tiny faint" style="margin:10px 0 0">Per-day attendance, therapist notes and a WhatsApp reminder the evening before each therapy day.</p>', { icon: "calendar" });
    return '<div class="stack s16">' + head + pk + '<div class="sp-g2">' + notes + pkc + "</div>" + herb +
      ui.note("info", esc(mktNote("AYUSH profile: practitioners registered with NCISM (Ayurveda) or NCH (Homeopathy); AYUSH prescription fields are MVP for AYUSH tenants.", "In " + NB.mkt().name + ", Ayurveda and homeopathy are licensed as traditional, complementary and integrative medicine under " + NB.mkt().regulator + "; the profile is enabled only for licensed practitioners."))) + "</div>";
  }

  /* ================= Profiles: home KPIs, quick actions (max 6), home widgets ================= */
  var QC = ["Quick consult", "استشارة سريعة", "त्वरित परामर्श", "steth", { go: "doctor.consult" }], RX = ["e-Rx", "وصفة إلكترونية", "e-Rx", "pill", { go: "doctor.consult" }],
    FU = ["Follow-up order", "أمر متابعة", "फ़ॉलो-अप ऑर्डर", "refresh", { go: "doctor.recalls" }], ATT = ["Attach reports", "إرفاق التقارير", "रिपोर्ट जोड़ें", "upload", { act: "toast", arg: "Outside report attached to the visit (secure link sent to the patient)" }];
  function profile(id) {
    var P = {
      gp: { k: [["38 / 52", "Seen today", "تمت رؤيتهم اليوم", "आज देखे गए", "High-volume day · avg 6.8 min", "faint", "users"], ["7 · 24 min", "Waiting · longest wait", "في الانتظار · أطول انتظار", "प्रतीक्षा · सबसे लंबा", "Rakesh Menon (urgent)", "warn", "hourglass"],
          ["23", "Chronic patients overdue", "مرضى مزمنون متأخرون", "क्रॉनिक मरीज़ ओवरड्यू", "Diabetes 14 · BP 9", "crit", "clockh"], ["3", "Abnormal readings today", "قراءات غير طبيعية اليوم", "आज असामान्य रीडिंग", "1 critical (K+ 6.1)", "crit", "alertfill"]],
        q: [["Quick consult (< 60 s)", "استشارة سريعة", "त्वरित परामर्श", "bolt", { go: "doctor.consult" }], ["e-Rx favourites", "الوصفات المفضلة", "पसंदीदा e-Rx", "pill", { go: "doctor.rx" }], ["Referral letter", "خطاب إحالة", "रेफ़रल पत्र", "send", { ov: "referral", arg: "p3|ext" }],
          ["Chronic register entry", "إدخال في سجل مزمن", "क्रॉनिक रजिस्टर एंट्री", "list", { act: "sp-regadd" }], FU, ["Sick-leave certificate", "شهادة إجازة مرضية", "चिकित्सा अवकाश प्रमाणपत्र", "file", { act: "toast", arg: "Sick-leave certificate drafted from this visit · edit duration before signing" }]],
        n: [["warn", "alertcircle", "Abnormal readings before consults", "Rakesh RBS 228 · Mohammed Ali BP 168/98", { go: "doctor.results" }], ["info", "file", "2 letters drafted, not sent", "Referral to cardiology · sick-leave certificate"], ["acc", "refresh", "Recalls due this week · 12", "Auto-WhatsApp on · 2 for front-desk callback", { go: "doctor.recalls" }]] },
      derm: { k: [["18", "Today's patients", "مرضى اليوم", "आज के मरीज़", "4 package sessions · 1 patch reading", "faint", "users"], ["5", "Photos due today", "صور مستحقة اليوم", "आज फ़ोटो देय", "Progress photo at follow-up", "warn", "camera"],
          ["31", "Active packages", "باقات نشطة", "सक्रिय पैकेज", "9 sessions due this week", "faint", "layers"], ["2", "Consents pending", "موافقات معلقة", "लंबित सहमति", "Before today's procedures", "warn", "pen"]],
        q: [["Start consult", "ابدأ الاستشارة", "परामर्श शुरू करें", "steth", { go: "doctor.consult" }], ["Capture progress photo", "التقاط صورة متابعة", "प्रगति फ़ोटो लें", "camera", { act: "sp-photo" }], ["New package / session", "باقة أو جلسة جديدة", "नया पैकेज / सत्र", "layers", { set: ["spDermTab", "proc"] }],
          ["Schedule patch test", "جدولة اختبار الرقعة", "पैच टेस्ट शेड्यूल", "clockh", { set: ["spDermTab", "patch"] }], ["Open consent form", "فتح نموذج الموافقة", "सहमति फ़ॉर्म खोलें", "pen", { ov: "procconsent", arg: "lhr|p5" }], ["Write e-Rx", "كتابة وصفة", "e-Rx लिखें", "pill", { go: "doctor.consult" }]],
        n: [["warn", "clockh", "Patch-test readings", "Deepa Krishnan D2 (48 h) now · D4 Monday", { set: ["spDermTab", "patch"] }], ["crit", "alertfill", "Allergy / safety alert in the waiting room", "Ananya Rao: pregnant 14 wk · isotretinoin and peels blocked"], ["sub", "layers", "Package sessions this week · 9", "Suresh Pillai LHR 4/6 today · Meena Joshi peel 4/6 overdue 1 d", { set: ["spDermTab", "proc"] }]] },
      dent: { k: [["14", "Today's patients", "مرضى اليوم", "आज के मरीज़", "2 chairs · 5 seen", "faint", "users"], ["18 min", "In chair now · C1", "على الكرسي الآن", "अभी कुर्सी पर", (NB.gcc() ? dentPatient().name : "Rahul Verma") + " · RCT 46 visit 2", "faint", "play"],
          ["4", "Plans awaiting approval", "خطط بانتظار الموافقة", "मंज़ूरी हेतु योजनाएँ", "2 sent on WhatsApp today", "warn", "hourglass"], ["3", "Lab work due back", "أعمال مختبر مستحقة", "लैब वर्क वापसी देय", "1 overdue 3 days", "crit", "truck"]],
        q: [["Open dental chart", "فتح مخطط الأسنان", "डेंटल चार्ट खोलें", "tooth", { ov: "toothedit", arg: "46" }], ["Chair-side quick note", "ملاحظة سريعة", "चेयर-साइड नोट", "edit", { act: "sp-dnote", arg: "6" }], ["Attach X-ray / photo", "إرفاق أشعة / صورة", "एक्स-रे / फ़ोटो जोड़ें", "image", { act: "toast", arg: "Sensor image pinned to tooth 46" }],
          ["New treatment plan", "خطة علاج جديدة", "नई उपचार योजना", "clipboard", { act: "toast", arg: "New plan started from the crown / implant templates" }], ["Start procedure + consent", "بدء إجراء + موافقة", "प्रक्रिया + सहमति", "pen", { ov: "procconsent", arg: "rct|dent" }], RX],
        n: [["info", "image", "X-rays attached this week · 3 to review", "IOPA 46 today · OPG and bitewing 14 Sep"], ["sub", "layers", "Package balances · next stage due", "Implant stage 2 (2 patients) · braces adjustments 11"], ["warn", "hourglass", "Plans awaiting approval · 4", "Oldest sent 15 Oct · auto-nudge tomorrow"]] },
      ped: { k: [["24", "Today's patients", "مرضى اليوم", "आज के मरीज़", "Ages shown in months under 2 y", "faint", "users"], ["6", "Vaccinations due today", "تطعيمات مستحقة اليوم", "आज टीके देय", "Among booked and arrived", "warn", "syringe"],
          ["2", "Growth alerts", "تنبيهات النمو", "ग्रोथ अलर्ट", "Off the WHO curve", "crit", "ruler"], ["11", "Overdue vaccines", "لقاحات متأخرة", "ओवरड्यू टीके", "In the recall engine", "crit", "clockh"]],
        q: [["Record vaccine", "تسجيل لقاح", "टीका दर्ज करें", "syringe", { ov: "clvaccine", arg: "MMR" }], ["Record growth", "تسجيل النمو", "ग्रोथ दर्ज करें", "ruler", { ov: "growthpoint" }], QC,
          ["Weight-based e-Rx", "وصفة حسب الوزن", "वज़न-आधारित e-Rx", "pill", { set: ["spPedTab", "dose"] }], ["Message parent", "مراسلة الوالد", "अभिभावक को संदेश", "chat", { act: "toast", arg: "Visit summary + growth chart sent to Imran Khan (guardian) on WhatsApp" }], FU],
        n: [["crit", "ruler", "Growth-watch list · 2", "Zoya Khan crossed 2 centiles · Ira M. weight static", { set: ["spPedTab", "growth"] }], ["warn", "refresh", "Recalls this week · newborn and weight checks · 5", "Baby of Sana 6-week visit today", { go: "doctor.recalls" }], ["info", "tv", "Child-friendly token screen", "First name + initial and an animal avatar", { go: "reception.display" }]] },
      ent: { k: [["16", "Today's patients", "مرضى اليوم", "आज के मरीज़", "", "faint", "users"], ["4", "Procedures today", "إجراءات اليوم", "आज की प्रक्रियाएँ", "2 endoscopy · 2 audiometry", "faint", "image"],
          ["2", "Consents pending", "موافقات معلقة", "लंबित सहमति", "Ear wax removal, cautery", "warn", "pen"], ["7", "Hearing follow-ups due", "متابعات السمع", "श्रवण फ़ॉलो-अप", "Repeat audiogram", "faint", "ear"]],
        q: [QC, ["Attach endoscopy photo / video", "إرفاق صورة تنظير", "एंडोस्कोपी फ़ोटो जोड़ें", "camera", { set: ["spEntTab", "endo"] }], ["Audiogram entry", "إدخال مخطط السمع", "ऑडियोग्राम एंट्री", "ear", { set: ["spEntTab", "aud"] }], ["Procedure + consent", "إجراء + موافقة", "प्रक्रिया + सहमति", "pen", { ov: "procconsent", arg: "ear|Vikas R." }], RX, FU],
        n: [["warn", "pen", "Consent before ear wax removal", "Vikas R. · 31 M · left ear", { ov: "procconsent", arg: "ear|Vikas R." }], ["info", "refresh", "Allergy (SPT) series · 2 due", "Skin-prick test readings at 15 minutes"]] },
      orth: { k: [["20", "Today's patients", "مرضى اليوم", "आज के मरीज़", "", "faint", "users"], ["3", "X-rays pending review", "أشعة بانتظار المراجعة", "समीक्षा हेतु एक्स-रे", "Flag clears only on sign-off", "warn", "image"],
          ["4", "Cast removals this week", "فك الجبائر هذا الأسبوع", "इस सप्ताह कास्ट हटाना", "Auto recalls sent", "faint", "clockh"], ["11", "Physio sessions today", "جلسات علاج طبيعي اليوم", "आज फ़िज़ियो सत्र", "2 physiotherapists", "faint", "activity"]],
        q: [QC, ["Attach X-ray", "إرفاق أشعة", "एक्स-रे जोड़ें", "image", { set: ["spOrthTab", "inj"] }], ["Fracture / injury note", "ملاحظة كسر / إصابة", "फ्रैक्चर / चोट नोट", "bone", { set: ["spOrthTab", "inj"] }], ["Physio package", "باقة علاج طبيعي", "फ़िज़ियो पैकेज", "layers", { set: ["spOrthTab", "ex"] }], FU, RX],
        n: [["warn", "image", "Unreviewed X-rays stay here until signed off", "Knee, elbow and wrist films", { set: ["spOrthTab", "inj"] }]] },
      gyn: { k: [["15", "Today's patients", "مرضى اليوم", "आज के मरीज़", "", "faint", "users"], ["42", "Pregnant patients", "حوامل", "गर्भवती मरीज़", "T1 12 · T2 18 · T3 12", "faint", "heartpulse"],
          ["5", "ANC visits overdue", "زيارات متابعة متأخرة", "ANC विज़िट ओवरड्यू", "In the recall engine", "crit", "clockh"], ["3", "Ultrasound reports to review", "تقارير موجات للمراجعة", "समीक्षा हेतु USG रिपोर्ट", "Sign-off logged", "warn", "file"]],
        q: [QC, ["Pregnancy tracker entry", "إدخال متابعة الحمل", "गर्भावस्था ट्रैकर एंट्री", "heartpulse", { act: "toast", arg: "ANC entry: BP, weight, fundal height and urine protein" }], ["Attach ultrasound report", "إرفاق تقرير الموجات", "अल्ट्रासाउंड रिपोर्ट जोड़ें", "upload", { act: "toast", arg: "Scan report attached to the pregnancy timeline · pending your sign-off" }],
          ["ANC package", "باقة متابعة الحمل", "ANC पैकेज", "layers", { act: "toast", arg: "ANC package: monthly visits + 3 scans, recalls for each contact" }], ["Pregnancy-safe e-Rx", "وصفة آمنة للحمل", "गर्भावस्था-सुरक्षित e-Rx", "shieldcheck", { go: "doctor.consult" }], FU],
        n: [["warn", "image", "Nadia K. · 33 + 2 wk · growth scan due", "Recall sent · not booked"], ["info", "file", "USG to review · Ritu M.", "Fibroid 3.2 cm, intramural"]] },
      hom: { k: [["14", "Today's patients", "مرضى اليوم", "आज के मरीज़", "", "faint", "users"], ["9", "Remedy reviews due", "مراجعات العلاج المستحقة", "रेमेडी समीक्षा देय", "4-week cycle", "warn", "refresh"],
          ["3", "Long cases awaiting", "حالات طويلة معلقة", "लंबित लंबे केस", "Resume where you left off", "faint", "file"], ["22", "Chronic bundles active", "باقات مزمنة نشطة", "सक्रिय क्रॉनिक बंडल", "3- and 6-month", "faint", "layers"]],
        q: [QC, ["Full case-taking", "أخذ الحالة الكامل", "पूर्ण केस-टेकिंग", "file", { set: ["spHsec", "cc"] }], ["Remedy prescription", "وصفة العلاج", "रेमेडी प्रिस्क्रिप्शन", "flask", { act: "toast", arg: "Remedy Rx panel ready" }], FU, ATT, ["Message patient", "مراسلة المريض", "मरीज़ को संदेश", "chat", { act: "toast", arg: "WhatsApp message drafted" }]],
        n: [["sub", "file", "Priya Sharma · long case 3/5", "Resumes at physical generals"]] },
      ayu: { k: [["12", "Today's patients", "مرضى اليوم", "आज के मरीज़", "", "faint", "users"], ["9", "Therapy sessions today", "جلسات العلاج اليوم", "आज थेरेपी सत्र", "Panchakarma rooms 1–3", "faint", "leaf"],
          ["46", "Package days remaining", "أيام الباقات المتبقية", "पैकेज के शेष दिन", "Across 6 patients", "faint", "layers"], ["7", "Follow-ups due", "متابعات مستحقة", "फ़ॉलो-अप देय", "", "warn", "refresh"]],
        q: [QC, ["Prakriti assessment", "تقييم البراكرتي", "प्रकृति आकलन", "leaf", { act: "toast", arg: "Prakriti questionnaire opened (about 5 minutes by taps)" }], ["Therapy package", "باقة علاجية", "थेरेपी पैकेज", "layers", { act: "toast", arg: "Panchakarma 7 / 14 / 21-day templates" }], ["Herbal Rx", "وصفة عشبية", "हर्बल Rx", "pill", { act: "toast", arg: "Herbal Rx with dose, anupana and duration" }], FU, ATT],
        n: [["sub", "layers", "Therapy patients today · 9", "Abhyanga + swedana 6 · shirodhara 2 · basti 1"]] }
    };
    return P[id];
  }
  function rail(id) {
    var list = (SD.today[id] || []).slice(), pr = profile(id);
    if (id === "dent" && NB.gcc()) { list[0] = list[0].slice(); list[0][1] = dentPatient().name; list[0][2] = "52 M"; list[1] = list[1].slice(); list[1][1] = "Omar Haddad"; list[1][2] = "38 M"; }
    var today = card(L("Today", "اليوم", "आज"), '<div class="sp-q">' + list.map(function (r) {
      return '<div class="sp-qi' + (r[7] ? " now" : "") + '"><span class="tok">' + esc(r[0]) + '</span><div class="t"><b>' + esc(r[1]) + "</b><small>" + esc(r[2] + " · " + r[3]) + "</small></div>" + ui.pill(r[4], r[5], r[6]) + "</div>";
    }).join("") + "</div>", { icon: "users", right: ui.btn(L("My day", "يومي", "मेरा दिन"), { cls: "sm ghost", go: "doctor.myday" }) });
    var needs = pr.n.length ? card(L("Home widgets", "عناصر الشاشة الرئيسية", "होम विजेट"), '<div class="lst">' + pr.n.map(function (x) { return li2(lead(x[0], x[1]), esc(x[2]), esc(x[3]), x[4] ? chev() : "", x[4]); }).join("") + "</div>", { icon: "bell" }) : "";
    return today + needs;
  }
  function li2(ld, title, st, right, o) { return '<div class="li' + (o ? " click" : "") + '"' + (o ? attrs(o) : "") + ">" + ld + '<div class="t"><b>' + title + "</b>" + (st ? "<small>" + st + "</small>" : "") + "</div>" + (right || "") + "</div>"; }
  function workFor(id, c) {
    return { gp: gpWork, derm: function (cc) { return dermWork(cc, false); }, dent: dentWork, ped: pedWork, ent: entWork, orth: orthWork, gyn: gynWork, hom: homWork, ayu: ayuWork }[id](c);
  }

  /* ================= Specialty settings (owner-configurable) ================= */
  function enabledDef(id) { return ["hom", "ayu"].indexOf(id) < 0; }
  function settingsPanel(c) {
    var sel = v("spSetSel", v("spSpec", "gp")), s = spec(sel);
    var list = card(L("Specialties at this clinic", "التخصصات في هذه العيادة", "इस क्लिनिक की विशेषज्ञताएँ"), SD.specs.map(function (x) {
      var on = v("spOn_" + x.id, enabledDef(x.id));
      return '<div class="sp-sli' + (x.id === sel ? " on" : "") + '" data-set="spSetSel" data-val="' + x.id + '" role="button" tabindex="0">' + ui.icon(x.icon) + '<div class="t"><b>' + esc(sname(x)) + "</b><small>" + esc(x.phase + " · " + x.fr) + '</small></div><span data-toggle="spOn_' + x.id + '" role="switch" aria-checked="' + on + '" aria-label="' + esc("Enable " + x.en) + '">' + sw(on) + "</span></div>";
    }).join("") + '<p class="tiny faint" style="margin:10px 0 0">Homeopathy and Ayurveda switch on for AYUSH-registered practitioners only.</p>', { icon: "layers" });
    var fields = (SD.fields[sel] || []).map(function (f) {
      return f[2] ? '<div class="tog" style="cursor:default"><div class="t"><b>' + esc(f[1]) + "</b><small>Compliance or safety field · always on</small></div>" + ui.pill("perm", "Required") + "</div>" : ui.toggle("spF_" + sel + "_" + f[0], f[1], "", true);
    }).join("");
    var tpls = (SD.templates[sel] || []).map(function (t) { return ui.toggle("spT_" + sel + "_" + t[0], t[1], "", !!t[2]); }).join("");
    var extra = "";
    if (sel === "dent") extra = '<div class="stack s8"><span class="eyebrow">Tooth numbering (chosen once, switchable without data loss)</span>' + ui.seg("spDentNum", [["fdi", "FDI (ISO 3950)"], ["uni", "Universal (ADA)"]], "fdi") + "</div>";
    if (sel === "derm") extra = '<div class="stack s8"><span class="eyebrow">Patch-test second reading</span>' + ui.seg("spPatchD", [["72", "D3 · 72 h"], ["96", "D4 · 96 h"]], "96") + '<span class="eyebrow" style="margin-top:6px">Clinical photos</span>' + ui.pill("perm", "Consent before first photo · always on", "lock") + "</div>";
    if (sel === "ped") extra = '<div class="stack s8"><span class="eyebrow">Vaccine schedule</span>' + (NB.gcc() ? ui.pill("info", "National schedule · " + NB.mkt().name + " (set by market)", "globe") : ui.seg("spVaxS", [["iap", "IAP 2023"], ["uip", "UIP (national)"]], "iap")) + "</div>";
    if (sel === "gyn") extra = '<div class="stack s8"><span class="eyebrow">ANC model</span>' + ui.seg("spAncM", [["who", "WHO 8 contacts"], ["nat", NB.gcc() ? "National pathway" : "MoHFW 4 visits"]], "who") + "</div>";
    var right = card(esc(sname(s)) + " · " + L("fields", "الحقول", "फ़ील्ड"), (extra ? extra + '<div class="hr" style="margin:14px 0 4px"></div>' : "") + fields, { icon: s.icon, right: ui.pill(s.pk, s.phase) }) +
      card(L("Templates & packages", "القوالب والباقات", "टेम्पलेट और पैकेज"), tpls + '<p class="tiny faint" style="margin:10px 0 0">Package support: ' + esc(s.pkg) + ".</p>", { icon: "layers" }) +
      ui.note("info", "<b>Who can change this:</b> the owner or clinic admin sets the clinic defaults; each doctor can hide optional fields for themselves. Required compliance fields (consent, vaccine batch, anaesthetic batch, allergies) cannot be switched off. Every change is in the audit log.");
    return '<div class="sp-set">' + list + '<div class="stack s16">' + right + "</div></div>";
  }

  /* ================= doctor.specialty ================= */
  function specBar(cur) {
    return '<div class="sp-specbar" role="tablist" aria-label="' + esc(L("Specialty", "التخصص", "विशेषज्ञता")) + '">' + SD.specs.map(function (s) {
      var off = !v("spOn_" + s.id, enabledDef(s.id));
      return '<button class="sp-sb' + (s.id === cur ? " on" : "") + (off ? " sp-mute" : "") + '" role="tab" aria-selected="' + (s.id === cur) + '" data-set="spSpec" data-val="' + s.id + '">' + ui.icon(s.icon, "sm") + esc(sname(s)) + (s.id === "gp" ? '<i class="mine" title="Your profile"></i>' : "") +
        '<span class="ph">' + esc(s.phase === "MVP" ? "MVP" : s.phase === "Phase 2" ? "P2" : "AYUSH") + "</span></button>";
    }).join("") + "</div>";
  }
  NB.screen("doctor.specialty", function (c) {
    var tab = v("spTab", "work"), id = v("spSpec", "gp"), s = spec(id), pr = profile(id);
    var h = ui.header(L("Specialty workspace", "مساحة التخصص", "विशेषज्ञता कार्यक्षेत्र"), L("One doctor persona: home widgets, quick actions and fields follow the specialty profile", "شخصية طبيب واحدة: الشاشة الرئيسية والإجراءات والحقول تتبع ملف التخصص", "एक डॉक्टर: होम विजेट, क्विक एक्शन और फ़ील्ड विशेषज्ञता के अनुसार"),
      ui.tabs("spTab", [["work", L("Workspace", "مساحة العمل", "कार्यक्षेत्र"), "grid"], ["set", L("Specialty settings", "إعدادات التخصص", "विशेषज्ञता सेटिंग्स"), "sliders"]], "work"));
    if (tab === "set") return h + settingsPanel(c);
    var off = !v("spOn_" + id, enabledDef(id));
    var prof = '<div class="card hl"><div class="sp-prof"><span class="pi">' + ui.icon(s.icon, "lg") + '</span><div class="t"><div class="row"><h2>' + esc(sname(s)) + "</h2>" + ui.pill(s.pk, "FRD " + s.phase, s.pk === "ok" ? "checkcircle" : "calendar") + (id === "gp" ? ui.pill("acc", L("Your profile", "ملفك", "आपकी प्रोफ़ाइल")) : "") + "</div><p>" + esc(s.layout) + "</p></div>" +
      '<div class="stack s4" style="align-items:flex-end">' + ui.pill("sub", "Packages: " + s.pkg, "layers") + '<span class="tiny faint">' + esc(s.fr) + "</span></div></div></div>";
    var body = specBar(id) + (off ? ui.note("warn", "<b>" + esc(sname(s)) + " is switched off at this clinic.</b> You are previewing it. The owner can enable it in Specialty settings.") : "") + prof + kpis(pr.k) + qa(pr.q) +
      '<div class="sp-split"><div class="stack s16">' + workFor(id, c) + '</div><div class="stack s16">' + rail(id) + "</div></div>";
    return h + body;
  });

  /* ================= solo.specialty (phone-first dermatology) ================= */
  NB.screen("solo.specialty", function (c) {
    var mob = c.mobile, tb = dermTabs(), cur = tb.cur, pid = v("spSoloPt", "p1");
    var h = ui.header(L("Skin workspace", "مساحة الجلدية", "त्वचा कार्यक्षेत्र"), L("Photos, body map, patch tests and procedure sessions · built for one hand", "الصور وخريطة الجسم واختبارات الرقعة والجلسات · بيد واحدة", "फ़ोटो, बॉडी मैप, पैच टेस्ट और सत्र · एक हाथ से"));
    var k = '<div class="grid g4 kpis">' + [["5", L("Photos due", "صور مستحقة", "फ़ोटो देय"), "camera", "warn"], ["4", L("Sessions today", "جلسات اليوم", "आज के सत्र"), "layers", ""], ["1", L("Patch reading", "قراءة رقعة", "पैच रीडिंग"), "clockh", "warn"], ["2", L("Consents to sign", "موافقات للتوقيع", "सहमति बाकी"), "pen", "warn"]].map(function (x) {
      return ui.kpi(x[0], x[1], ui.icon(x[2], "xs") + (x[3] === "warn" ? L("Today", "اليوم", "आज") : L("Booked", "محجوز", "बुक")), x[3] || "faint"); }).join("") + "</div>";
    var quick = '<div class="sp-qa sp-qa3">' + [["Capture photo", "التقاط صورة", "फ़ोटो लें", "camera", { act: "sp-photo" }], ["Consent", "موافقة", "सहमति", "pen", { ov: "procconsent", arg: "sal|p1" }], ["Patch test", "اختبار الرقعة", "पैच टेस्ट", "clockh", { set: ["spDermTab", "patch"] }],
      ["Session", "جلسة", "सत्र", "layers", { set: ["spDermTab", "proc"] }], ["e-Rx", "وصفة", "e-Rx", "pill", { go: "solo.consult" }], ["Body map", "خريطة الجسم", "बॉडी मैप", "user", { set: ["spDermTab", "map"] }]].map(function (q) {
      return '<button class="sp-qb"' + attrs(q[4]) + ">" + ui.icon(q[3]) + "<span>" + esc(L(q[0], q[1], q[2])) + "</span></button>"; }).join("") + "</div>";
    var list = '<div class="sp-q">' + [["p1", "T-26", "Acne review · peel 2/4", "warn", "Photo due", "camera"], ["p5", "T-17", "Laser hair reduction 4/6", "sub", "Package 4/6", "layers"], ["p2", "T-14", "Melasma · pregnant 14 wk", "crit", "Teratogen block", "lock"], [null, "T-24", "Deepa Krishnan · patch test D2", "warn", "48 h reading", "clockh"]].map(function (r) {
      var on = r[0] === pid || (!r[0] && cur === "patch" && pid === "patch");
      return '<div class="sp-qi click' + (on ? " now" : "") + '" data-act="sp-soloopen" data-arg="' + (r[0] || "patch") + '" role="button" tabindex="0"><span class="tok">' + r[1] + '</span><div class="t"><b>' + esc(r[0] ? pnm(r[0]) : "Deepa Krishnan") + "</b><small>" + esc(r[0] ? r[2] : "27 F · " + r[2].split(" · ")[1]) + "</small></div>" + ui.pill(r[3], r[4], r[5]) + "</div>";
    }).join("") + "</div>";
    var work = '<div class="stack s16">' + tb.html + dermBody(c, cur, pid) + "</div>";
    if (mob) return h + list + quick + work + k;
    return h + k + '<div class="sp-split" style="grid-template-columns:minmax(0,1fr) 360px"><div class="card">' + work + '</div><div class="stack s16">' + card(L("Today", "اليوم", "आज"), list, { icon: "users" }) + card(L("Quick actions", "إجراءات سريعة", "क्विक एक्शन"), quick, { icon: "bolt" }) + "</div></div>";
  });
  function dermBody(c, cur, pid) {
    if (cur === "patch") return dermPatch(c, true);
    if (cur === "proc") return dermProc(c, true);
    var p = NB.pat(pid === "patch" ? "p1" : pid), preg = pid === "p2";
    var head = ptBar(NB.pname(p), p.age + " " + p.sex + " · " + (p.conditions[0] || "") + (preg ? " · pregnant 14 wk" : ""), preg ? ui.pill("crit", "Isotretinoin, peels blocked", "lock") : ui.pill("info", "Fitzpatrick " + v("spFitz", "IV")));
    return head + (cur === "map" ? dermMap(c, true) : dermPhotos(c, true));
  }
  NB.act("sp-soloopen", function (a) { if (a === "patch") NB.setUI({ spDermTab: "patch", spSoloPt: "patch" }); else NB.setUI({ spSoloPt: a, spDermTab: a === "p5" ? "proc" : "photo" }); });

  /* ================= doctor.recalls ================= */
  function recallRows() {
    var st = v("spRcSt", {});
    return SD.recalls.filter(function (r) { return st[r[0]] !== "nn"; }).map(function (r) {
      var isP = /^p\d+$/.test(r[1]), p = isP ? NB.pat(r[1]) : null;
      return { id: r[0], name: isP ? NB.pname(p) : r[1], as: isP ? p.age + " " + p.sex : r[2], reason: r[3], what: r[4], due: r[5], od: r[6], att: r[7], last: r[8], src: r[9], lang: isP ? p.lang : r[10], st: st[r[0]] };
    });
  }
  NB.screen("doctor.recalls", function (c) {
    var f = v("spRcF", "all"), rows = recallRows(), cnt = {}; rows.forEach(function (r) { cnt[r.reason] = (cnt[r.reason] || 0) + 1; });
    var tot = SD.conv.reduce(function (a, x) { return [a[0] + x[1], a[1] + x[2]]; }, [0, 0]), conv = Math.round(tot[1] / tot[0] * 100);
    var h = ui.header(L("Recalls & follow-ups", "الاستدعاء والمتابعة", "रिकॉल और फ़ॉलो-अप"), L("The retention engine: come-back rules per treatment send a WhatsApp with a one-tap booking link", "محرك الاحتفاظ: قواعد العودة لكل علاج ترسل رسالة واتساب برابط حجز", "रिटेंशन इंजन: हर उपचार के नियम व्हाट्सऐप पर बुकिंग लिंक भेजते हैं"),
      ui.btn(L("New rule", "قاعدة جديدة", "नया नियम"), { cls: "pri", ov: "recallrule", arg: "new", icon: "plus" }));
    h += kpis([[String(rows.filter(function (r) { return r.od >= -7; }).length), "Due this week", "مستحق هذا الأسبوع", "इस सप्ताह देय", rows.filter(function (r) { return r.od > 0; }).length + " overdue", "warn", "clockh"], ["18", "Sent today", "أُرسل اليوم", "आज भेजे", "16 delivered · 11 read", "faint", "send"],
      [conv + "%", "Recalled → booked (30 d)", "من الاستدعاء إلى الحجز", "रिकॉल → बुक (30 दिन)", tot[1] + " of " + tot[0] + " · up 6 pts", "ok", "checkcircle"], [NB.money(184000), "Visits recovered (30 d)", "زيارات مستعادة", "वापस आई विज़िट", "Consult + package value", "ok", "wallet"]]);
    var seg = ui.seg("spRcF", [["all", L("All", "الكل", "सभी"), rows.length]].concat(SD.reasons.map(function (r) { return [r[0], L(r[1], r[2], r[3]), cnt[r[0]] || 0]; })), "all");
    var list = rows.filter(function (r) { return f === "all" || r.reason === f; }).sort(function (a, b) { return b.od - a.od; }).map(function (r) {
      var rs = SD.reasons.find(function (x) { return x[0] === r.reason; }), cb = r.att >= 2 && r.od > 0 && !r.st;
      var state = r.st === "sent" ? ui.pill("sync", "Sent now · " + (r.att + 1) + "/" + 2) : r.st === "call" ? ui.pill("info", "Called · callback logged", "phone") : cb ? ui.pill("urg", "Front-desk callback", "phone") : r.od > 0 ? ui.pill("crit", "Overdue " + r.od + " d", "clockh") : r.od === 0 ? ui.pill("warn", "Due today", "clockh") : ui.pill("mute", "Due in " + -r.od + " d", "calendar");
      var acts = '<div class="btns" style="flex-wrap:nowrap">' + ui.btn(L("Send now", "إرسال الآن", "अभी भेजें"), { cls: "sm" + (r.od > 0 && !r.st ? " pri" : ""), act: "sp-rc", arg: r.id + "|sent", icon: "send" }) +
        '<button class="ibtn" style="width:32px;height:32px" data-act="sp-rc" data-arg="' + r.id + '|call" title="' + esc(L("Call", "اتصال", "कॉल")) + '">' + ui.icon("phone", "sm") + "</button>" +
        '<button class="ibtn" style="width:32px;height:32px" data-act="sp-rc" data-arg="' + r.id + '|nn" title="' + esc(L("Mark not needed", "غير مطلوب", "ज़रूरत नहीं")) + '">' + ui.icon("close", "sm") + "</button></div>";
      if (c.mobile) return '<div class="sp-qi' + (r.od > 0 ? " sp-od" : "") + '"><div class="t"><b>' + esc(r.name) + " · " + esc(r.as) + '</b><small class="sp-wrap">' + ui.icon(rs[4], "xs") + " " + esc(r.what) + '</small><small>' + esc("Due " + r.due + " · " + r.last) + '</small><div class="row" style="margin-top:8px">' + state + "</div></div>" + acts + "</div>";
      return { cls: r.od > 0 ? "crit" : "", cells: [ui.cellp(esc(r.name), esc(r.as + " · " + (r.lang === "ar" ? "Arabic" : r.lang === "hi" ? "Hindi" : "English"))), ui.cellp(ui.icon(rs[4], "xs") + " " + esc(r.what), esc(r.src)), ui.cellp(n(esc(r.due)), esc(r.att + " of 2 nudges")), '<span class="small">' + esc(r.last) + "</span>", state, acts] };
    });
    var dueCard = card(L("Due recalls", "الاستدعاءات المستحقة", "देय रिकॉल"), '<div class="stack">' + seg + (c.mobile ? '<div class="sp-q">' + (list.join("") || '<div class="empty">Nothing due for this reason.</div>') + "</div>" : ui.table([{ h: L("Patient", "المريض", "मरीज़") }, { h: L("Reason", "السبب", "कारण") }, { h: L("Due", "الاستحقاق", "देय") }, { h: L("Last contact", "آخر تواصل", "अंतिम संपर्क") }, { h: L("Status", "الحالة", "स्थिति") }, { h: "" }], list, { min: 900, empty: "Nothing due for this reason." })) +
      '<p class="tiny faint" style="margin:0">Ignored recalls get one second nudge, then rest; non-bookers move to the front-desk callback list (PAT-20). Doctors add recalls in one tap at consult close: 3 d · 1 w · 2 w · 1 m · custom (DOC-07).</p></div>', { icon: "refresh" });
    var rules = card(L("Auto-WhatsApp recall rules", "قواعد الاستدعاء التلقائي", "ऑटो-व्हाट्सऐप रिकॉल नियम"), '<div class="lst">' + SD.rules.map(function (r) {
      var on = v("spRule_" + r[0], r[8]), rs = SD.reasons.find(function (x) { return x[0] === r[1]; });
      return '<div class="li"><span class="pill ' + (on ? "acc" : "mute") + ' sp-lead">' + ui.icon(rs[4], "sm") + '</span><div class="t click" data-ov="recallrule" data-arg="' + r[0] + '" style="cursor:pointer"><b>' + esc(r[2]) + "</b><small>" + esc(r[4] + " · max " + r[6] + " · " + r[5]) + '</small></div><span data-toggle="spRule_' + r[0] + '" role="switch" aria-checked="' + on + '" aria-label="' + esc(r[2]) + '" style="cursor:pointer">' + sw(on) + "</span></div>";
    }).join("") + "</div>" + '<p class="tiny faint" style="margin:10px 0 0">Quiet hours ' + (NB.gcc() ? "22:00–08:00 and prayer-aware send times; Arabic wording by default" : "21:00–09:00; message in the patient's language (English, Hindi or regional)") + ". Utility templates approved by Meta.</p>", { icon: "sliders" });
    var mx = 412;
    var funnel = card(L("Conversion (last 30 days)", "التحويل (آخر ٣٠ يومًا)", "रूपांतरण (पिछले 30 दिन)"), '<div class="sp-funnel">' + [["Due", 412], ["Sent", 398], ["Delivered", 386], ["Read", 341], ["Booked", 284], ["Attended", 262]].map(function (x, i) {
      return '<div class="sp-fr"><span class="small">' + esc(x[0]) + '</span><div><div class="bx' + (i >= 4 ? " ok" : "") + '" style="width:' + Math.round(x[1] / mx * 100) + '%"></div></div><b class="small">' + x[1] + "</b></div>"; }).join("") + "</div>" +
      '<div class="hr" style="margin:14px 0"></div>' + ui.table([{ h: L("Reason", "السبب", "कारण") }, { h: L("Recalled", "استُدعي", "रिकॉल"), cls: "r" }, { h: L("Booked", "حجز", "बुक"), cls: "r" }, { h: "%", cls: "r" }, { h: L("Days to book", "أيام حتى الحجز", "बुक होने में दिन"), cls: "r" }], SD.conv.map(function (x) {
        var rs = SD.reasons.find(function (y) { return y[0] === x[0]; }), pc = Math.round(x[2] / x[1] * 100); return [ui.icon(rs[4], "xs") + " " + esc(L(rs[1], rs[2], rs[3])), n(x[1]), n(x[2]), '<b class="num ' + (pc >= 70 ? "okc" : pc < 55 ? "warnc" : "") + '">' + pc + "%</b>", n(x[3])]; }), { min: 520 }) +
      '<p class="tiny faint" style="margin:10px 0 0">By attempt: first message 48% · second nudge +14% · front-desk call +7%.</p>', { icon: "chart" });
    return h + dueCard + '<div class="sp-g2">' + rules + funnel + "</div>";
  });

  /* ================= doctor.tele ================= */
  function sil() { return '<svg viewBox="0 0 160 90" preserveAspectRatio="xMidYMax slice" aria-hidden="true"><rect width="160" height="90" style="fill:var(--sunk)"/><circle class="sp-sil" cx="80" cy="40" r="16"/><path class="sp-sil" d="M44 92 q4 -30 36 -30 q32 0 36 30z"/></svg>'; }
  NB.screen("doctor.tele", function (c) {
    var m = NB.mkt(), gcc = NB.gcc(), sel = v("spTv", "V2"), inCall = v("spTin", false), tv = SD.tele.find(function (x) { return x[0] === sel; }) || SD.tele[1];
    var tname = /^p\d+$/.test(tv[2]) ? pnm(tv[2]) : tv[2], tp = /^p\d+$/.test(tv[2]) ? NB.pat(tv[2]) : { age: 27, sex: "F" }, dr = NB.doc("d2");
    var h = ui.header(L("Teleconsult", "استشارة عن بعد", "टेली-परामर्श"), L("Video visits through a compliant partner, with consent, ID check and prescribing rules built in", "زيارات فيديو عبر شريك متوافق مع الموافقة والتحقق وقواعد الوصف", "अनुपालक पार्टनर से वीडियो विज़िट: सहमति, पहचान और पर्चे के नियम"),
      ui.pill("info", "FRD Phase 3 · partner deep-link", "link"));
    var visits = card(L("Today's video visits", "زيارات الفيديو اليوم", "आज की वीडियो विज़िट"), '<div class="sp-q">' + SD.tele.map(function (x) {
      var nm = /^p\d+$/.test(x[2]) ? pnm(x[2]) : x[2], s = { done: ["ok", "Completed", "checkcircle"], lobby: ["sync", "In lobby"], booked: ["mute", "Booked", "calendar"] }[x[7]];
      return '<div class="sp-qi click' + (x[0] === sel ? " now" : "") + '" data-act="sp-tv" data-arg="' + x[0] + '" role="button" tabindex="0"><span class="tok">' + x[1] + '</span><div class="t"><b>' + esc(nm) + "</b><small>" + esc(x[3] + " · " + x[4] + " · " + x[8]) + "</small></div>" + ui.pill(s[0], s[1], s[2]) + "</div>";
    }).join("") + "</div>", { icon: "calendar" });
    var first = tv[3] === "First consult";
    var consentOk = tv[5] === "ok" || v("spTcons_" + tv[0], false), idOk = v("spTid_" + tv[0], tv[5] === "ok");
    var checks = '<div class="lst">' + [["ok", "camera", "Camera", "Integrated HD webcam · 1080p", "Working"], ["ok", "mic", "Microphone", '<span class="sp-meter" aria-hidden="true"><i style="height:6px"></i><i style="height:12px"></i><i style="height:16px"></i><i style="height:10px"></i><i class="o" style="height:14px"></i><i class="o" style="height:8px"></i></span>', "Working"],
      ["ok", "monitor", "Speaker", "Test tone heard", "Working"], ["ok", "wifi", "Your network", "18 Mbps down · 6 up · 42 ms", "Good"], [tv[6] === "ok" ? "ok" : tv[6] === "warn" ? "warn" : "mute", "smartphone", "Patient device", tv[6] === "ok" ? "Android · joined the lobby 10:58 · 4G" : tv[6] === "warn" ? "Audio only · weak network" : "Check runs when the patient opens the link", tv[6] === "ok" ? "Ready" : tv[6] === "warn" ? "Fair" : "Pending"]].map(function (x) {
      return ui.li(lead(x[0], x[1] === "wifi" ? "globe" : x[1]), esc(x[2]), x[1] === "mic" ? x[3] : esc(x[3]), ui.pill(x[0], x[4], x[0] === "ok" ? "checkcircle" : x[0] === "warn" ? "alertcircle" : "hourglass"));
    }).join("") + "</div>";
    var gate = '<div class="lst">' + ui.li(lead("ok", "shielduser"), esc(NB.dname(dr) + " · Reg. " + dr.regNo), esc(gcc ? "Licence shown to the patient before the call (" + m.regulator + ")" : "Name and registration number shown to the patient before the call (TPG 2020)"), ui.pill("ok", "Shown")) +
      '<div class="li click" data-toggle="spTid_' + tv[0] + '">' + lead(idOk ? "ok" : "warn", "user") + '<div class="t"><b>' + esc(L("Patient identity verified", "تم التحقق من هوية المريض", "मरीज़ की पहचान सत्यापित")) + "</b><small>" + esc("Name, age, address" + (first ? " and ID (" + m.natId + ") for a first consult" : " · known patient")) + "</small></div>" + sw(idOk) + "</div>" +
      '<div class="li click" data-toggle="spTcons_' + tv[0] + '">' + lead(consentOk ? "ok" : "warn", "shieldcheck") + '<div class="t"><b>' + esc(L("Consent to teleconsult", "الموافقة على الاستشارة عن بعد", "टेली-परामर्श की सहमति")) + "</b><small>" + esc(tv[5] === "ok" ? "Explicit, via WhatsApp 10:52 (patient initiated)" : "Ask the patient to confirm on the link or record verbal consent") + "</small></div>" + sw(consentOk) + "</div>" +
      ui.li(lead("perm", "lock"), esc(L("Recording", "التسجيل", "रिकॉर्डिंग")), esc("Off · needs separate consent" + (gcc ? "; recordings stay in " + m.residency : "")), ui.pill("perm", "Off", "lock")) + "</div>";
    var stage;
    if (!inCall) {
      stage = card(L("Pre-call check", "فحص ما قبل المكالمة", "कॉल-पूर्व जाँच") + " · " + esc(tname), '<div class="sp-g2"><div class="stack s8"><span class="eyebrow">' + L("Devices", "الأجهزة", "डिवाइस") + "</span>" + checks + '</div><div class="stack s8"><span class="eyebrow">' + L("Before you start", "قبل البدء", "शुरू करने से पहले") + "</span>" + gate + "</div></div>" +
        (first ? ui.note("warn", gcc ? "First consult: video is required and the patient's identity must be verified against " + m.natId + "." : "First consult (TPG 2020): video is preferred; on audio or text you may prescribe only List O medicines. Verify identity before prescribing.") : "") +
        '<div class="btns" style="margin-top:12px">' + ui.btn(L("Start video call", "بدء مكالمة الفيديو", "वीडियो कॉल शुरू करें"), { cls: "pri lg", act: "sp-tstart", icon: "play" }) + ui.btn(L("Send join link again", "إعادة إرسال الرابط", "लिंक फिर भेजें"), { act: "toast", arg: "Join link re-sent on WhatsApp (valid 30 min)", icon: "send" }) + "</div>", { icon: "monitor" });
    } else {
      var mic = v("spTmic", true), cam = v("spTcam", true);
      stage = '<div class="stack s8"><div class="sp-stage"><div class="vt">' + sil() + "</div>" +
        '<div class="tbar">' + ui.pill("crit", "Live · 04:12", "dot") + ui.pill("perm", "Not recording", "lock") + '<span class="grow" style="flex:1"></span>' + ui.pill("ok", "HD · 42 ms", "checkcircle") + "</div>" +
        '<div class="who"><span class="lbl">' + esc(tname) + "</span></div>" +
        '<div class="sp-pip">' + (cam ? sil() : "") + "<span>" + esc(L("You", "أنت", "आप")) + (cam ? "" : " · " + L("camera off", "الكاميرا مغلقة", "कैमरा बंद")) + "</span></div></div>" +
        '<div class="row sb"><span class="small faint">' + esc(NB.dname(dr) + " · Reg. " + dr.regNo + " · shown to the patient") + '</span></div><div class="sp-ctl">' +
        '<button class="ibtn' + (mic ? "" : " off") + '" data-toggle="spTmic" title="' + esc(mic ? "Mute" : "Unmute") + '">' + ui.icon(mic ? "mic" : "mic") + "</button>" +
        '<button class="ibtn' + (cam ? "" : " off") + '" data-toggle="spTcam" title="Camera">' + ui.icon("camera") + "</button>" +
        '<button class="ibtn" data-act="toast" data-arg="Screen shared: TSH trend chart" title="Share screen">' + ui.icon("monitor") + "</button>" +
        '<button class="ibtn end" data-act="sp-tend" title="End call">' + ui.icon("close") + "</button></div></div>";
    }
    // chat and in-call sharing
    var shared = v("spTshare", []), rxBlocked = !v("spTrxFix", false);
    var chat = card(L("Chat & sharing", "الدردشة والمشاركة", "चैट और साझा करना"), '<div class="sp-chat">' +
      '<div class="sp-msg in">' + esc("Doctor, my TSH report from last week is attached. Also I am not sleeping well.") + '<div class="sp-att">' + ui.icon("file", "sm") + '<div class="t"><b>TSH 6.8 mIU/L · 02 Sep</b><small>PDF · 120 KB</small></div></div><small>' + esc(tname) + " · 10:59</small></div>" +
      '<div class="sp-msg out">' + esc("Thank you. I can see it. Let's review your dose.") + "<small>You · 11:01</small></div>" +
      shared.map(function (s) { return '<div class="sp-msg out">' + '<div class="sp-att" style="margin-top:0">' + ui.icon(s === "rx" ? "pill" : "flask", "sm") + '<div class="t"><b>' + esc(s === "rx" ? "e-Prescription · signed" : "Lab report · TSH trend") + "</b><small>" + esc(s === "rx" ? (gcc ? m.eRx : "Signed e-Rx + QR · Reg. " + dr.regNo) : "Shared in call") + "</small></div></div><small>You · 11:0" + (4 + shared.indexOf(s)) + "</small></div>"; }).join("") +
      "</div>" + '<div class="btns" style="margin-top:12px">' + ui.btn(L("Share Rx", "مشاركة الوصفة", "Rx साझा करें"), { cls: "sm" + (rxBlocked ? "" : " pri"), act: "sp-tshare", arg: "rx", icon: "pill" }) + ui.btn(L("Share report", "مشاركة التقرير", "रिपोर्ट साझा करें"), { cls: "sm", act: "sp-tshare", arg: "rep", icon: "flask" }) + "</div>", { icon: "chat" });
    var rxc = card(L("Teleconsult prescribing check", "فحص الوصف عن بعد", "टेली-परामर्श पर्चा जाँच"), '<div class="lst">' + [["ok", "Levothyroxine 75 mcg", gcc ? "Follow-up · allowed" : "List B (follow-up dose change) · allowed"], ["ok", "Paracetamol 650 mg SOS", gcc ? "Allowed" : "List O · allowed on any mode"],
      [rxBlocked ? "crit" : "mute", "Alprazolam 0.25 mg", gcc ? "Controlled drug · cannot be prescribed by telehealth" : "Psychotropic (NDPS Act) · prohibited on teleconsult"]].map(function (x) {
      return ui.li(lead(x[0], x[0] === "ok" ? "checkcircle" : "lock"), (x[0] === "mute" ? "<s>" + esc(x[1]) + "</s>" : esc(x[1])), esc(x[2]), x[0] === "crit" ? ui.btn(L("Remove", "إزالة", "हटाएँ"), { cls: "sm dng", act: "sp-trxfix" }) : x[0] === "mute" ? ui.pill("mute", "Removed") : ui.pill("ok", "OK"));
    }).join("") + "</div>" + (rxBlocked ? ui.note("crit", "The e-Rx cannot be shared until the prohibited medicine is removed. Suggest an in-person visit for insomnia.") : ui.note("ok", "Rx passes the teleconsult rules and can be shared in the call.")), { icon: "shieldcheck" });
    var notice = gcc ? card(L("Telehealth licensing", "ترخيص الرعاية الصحية عن بعد", "टेलीहेल्थ लाइसेंस") + " · " + esc(m.name), '<ul class="small" style="margin:0;padding-inline-start:18px;display:flex;flex-direction:column;gap:6px">' +
        ["The facility needs a telehealth licence from " + m.regulator + "; the practitioner's licence must be active and shown before the call.", "Consent before the consult; separate consent before any recording.", "Records and recordings are stored in " + m.residency + ".", "Controlled and narcotic medicines cannot be prescribed by telehealth; e-prescriptions go through " + m.eRx + ".", "Nabd connects through a licensed partner; the module stays off until the clinic's licence is verified."].map(function (x) { return '<li dir="auto">' + esc(x) + "</li>"; }).join("") + "</ul>", { icon: "scroll" })
      : card("Telemedicine Practice Guidelines 2020 · India", '<ul class="small" style="margin:0;padding-inline-start:18px;display:flex;flex-direction:column;gap:6px">' +
        ["Only registered medical practitioners; name and registration number are shown to the patient.", "Identify the patient (name, age, address; ID if needed) and record consent: implied when the patient starts the call, explicit when the doctor or a health worker does.", "First consult: video preferred. On audio or text, only List O medicines may be prescribed.", "List O: safe OTC-type medicines (e.g. paracetamol, ORS) on any mode · List A: first consult on video, or re-fills · List B: add-ons in follow-up of the same condition.", "Prohibited: Schedule X drugs and any narcotic or psychotropic substance under the NDPS Act.", "Keep the consult record, chat and prescriptions; the e-Rx carries the registration number and signature."].map(function (x) { return '<li dir="auto">' + esc(x) + "</li>"; }).join("") + "</ul>", { icon: "scroll" });
    var mob = c.mobile;
    return h + (mob ? stage + (inCall ? chat + rxc : "") + visits + notice : '<div class="sp-split" style="grid-template-columns:minmax(0,1fr) 360px"><div class="stack s16">' + stage + (inCall ? '<div class="sp-g2">' + chat + rxc + "</div>" : rxc) + '</div><div class="stack s16">' + visits + notice + "</div></div>");
  });
  NB.act("sp-tv", function (a) { NB.setUI({ spTv: a, spTin: false }); });
  NB.act("sp-tstart", function () {
    var tv = SD.tele.find(function (x) { return x[0] === v("spTv", "V2"); }) || SD.tele[1];
    if (!(tv[5] === "ok" || v("spTcons_" + tv[0], false))) { NB.toast("Record the patient's consent to teleconsult first"); return; }
    if (!v("spTid_" + tv[0], tv[5] === "ok")) { NB.toast("Verify the patient's identity first"); return; }
    NB.setUI({ spTin: true });
  });
  NB.act("sp-tend", function () { NB.state.ui.spTin = false; NB.toast("Call ended · 6 min · consult record, chat and shared files saved to the visit"); });
  NB.act("sp-tshare", function (a) {
    if (a === "rx" && !v("spTrxFix", false)) { NB.toast("Remove the prohibited medicine before sharing the e-Rx"); return; }
    if (!v("spTin", false)) { NB.toast("Start the call to share in the chat"); return; }
    var s = v("spTshare", []).slice(); if (s.indexOf(a) < 0) s.push(a); NB.state.ui.spTshare = s; NB.toast(a === "rx" ? "Signed e-Rx shared in the call and on WhatsApp" : "Report shared in the call");
  });
  NB.act("sp-trxfix", function () { NB.state.ui.spTrxFix = true; NB.toast("Alprazolam removed · suggest an in-person visit for sleep"); });

  /* ================= Procedure consent (DOC-09) ================= */
  // Each procedure: name, form id + version, risks [level c/u/r] and text per language (en / hi / ar).
  SD.procs = {
    lhr: { en: "Laser hair reduction (diode 810 nm)", ar: "إزالة الشعر بالليزر (دايود ٨١٠ نانومتر)", hi: "लेज़र हेयर रिडक्शन (डायोड 810 nm)", form: "DERM-LHR", ver: "3.2", lv: ["c", "c", "u", "u", "r", "r", "r"],
      t: { en: ["Laser light passes into the hair follicle to slow regrowth. Usually 6–8 sessions, 4–6 weeks apart. A test patch was done before the first session.", "Long-term reduction (not permanent removal) of unwanted hair in the treated area.",
          ["Redness and swelling around hair follicles for 1–2 days", "Stinging or heat during the session", "Darkening or lightening of the skin (more likely in darker skin), usually temporary", "Blisters or crusting", "Burns or scarring", "Paradoxical increase in hair growth", "Eye injury if the protective goggles are removed"],
          "Shaving, waxing, threading, creams, electrolysis, or no treatment.", "Avoid sun and heat for 1 week, use SPF 50, no waxing between sessions, apply the soothing cream given."],
        hi: ["लेज़र रोशनी बालों की जड़ (फ़ॉलिकल) तक जाकर बालों की दोबारा वृद्धि धीमी करती है। आमतौर पर 4–6 हफ़्ते के अंतर पर 6–8 सत्र लगते हैं। पहले सत्र से पहले टेस्ट पैच किया गया था।", "उपचारित हिस्से में अनचाहे बालों में लंबे समय तक कमी (स्थायी रूप से हटना नहीं)।",
          ["1–2 दिन तक लालिमा और फ़ॉलिकल के आसपास सूजन", "सत्र के दौरान चुभन या गर्मी", "त्वचा का रंग गहरा या हल्का होना (गहरी त्वचा में अधिक संभावना), आमतौर पर अस्थायी", "छाले या पपड़ी", "जलना या निशान", "उल्टा बालों का अधिक उगना", "सुरक्षा चश्मा हटाने पर आँख को चोट"],
          "शेविंग, वैक्सिंग, थ्रेडिंग, क्रीम, इलेक्ट्रोलिसिस, या कोई उपचार नहीं।", "1 हफ़्ते तक धूप और गर्मी से बचें, SPF 50 लगाएँ, सत्रों के बीच वैक्सिंग न करें, दी गई सुखदायक क्रीम लगाएँ।"],
        ar: ["يمر ضوء الليزر إلى بصيلة الشعرة لإبطاء نموّها من جديد. عادةً 6–8 جلسات بفاصل 4–6 أسابيع. أُجري اختبار على رقعة صغيرة قبل الجلسة الأولى.", "تقليل طويل الأمد (وليس إزالة دائمة) للشعر غير المرغوب في المنطقة المعالجة.",
          ["احمرار وتورم حول بصيلات الشعر لمدة 1–2 يوم", "لسعة أو حرارة أثناء الجلسة", "تغمّق أو تفتّح لون الجلد (أكثر احتمالًا في البشرة الداكنة)، غالبًا مؤقت", "بثور أو قشور", "حروق أو ندبات", "زيادة عكسية في نمو الشعر", "إصابة العين عند نزع النظارة الواقية"],
          "الحلاقة أو الشمع أو الخيط أو الكريمات أو التحليل الكهربائي، أو عدم العلاج.", "تجنّب الشمس والحرارة لمدة أسبوع، واستخدم واقي شمس SPF 50، ولا تستخدم الشمع بين الجلسات، وضع الكريم المهدئ الموصوف."] } },
    sal: { en: "Chemical peel (salicylic acid 30%)", ar: "تقشير كيميائي (حمض الساليسيليك ٣٠٪)", hi: "केमिकल पील (सैलिसिलिक एसिड 30%)", form: "DERM-PEEL", ver: "2.4", lv: ["c", "c", "u", "u", "r", "r"],
      t: { en: ["A salicylic acid solution is applied to the face for a few minutes to exfoliate the skin and unclog pores. Usually 4 sessions, 2 weeks apart.", "Fewer acne lesions and smoother skin; helps marks fade.",
          ["Stinging, redness and peeling for 3–5 days", "Dryness and tightness", "Darkening of the skin (post-inflammatory hyperpigmentation)", "Acne flare in the first 1–2 weeks", "Salicylate toxicity (ringing in the ears, nausea) with large areas", "Burns, crusting or scarring"],
          "Creams and gels alone, oral medicines, other peels, or no treatment.", "Do not pick peeling skin; use a gentle cleanser and moisturiser, SPF 50 daily, and stop retinoid creams for 5 days."],
        hi: ["चेहरे पर कुछ मिनटों के लिए सैलिसिलिक एसिड का घोल लगाया जाता है ताकि त्वचा की ऊपरी परत हटे और रोमछिद्र खुलें। आमतौर पर 2 हफ़्ते के अंतर पर 4 सत्र।", "मुहाँसे कम होते हैं, त्वचा चिकनी होती है और दाग हल्के पड़ते हैं।",
          ["3–5 दिन तक चुभन, लालिमा और छिलना", "रूखापन और खिंचाव", "त्वचा का गहरा होना (सूजन के बाद पिग्मेंटेशन)", "पहले 1–2 हफ़्ते में मुहाँसे बढ़ना", "बड़े हिस्से पर लगाने से सैलिसिलेट विषाक्तता (कानों में घंटी, मतली)", "जलना, पपड़ी या निशान"],
          "केवल क्रीम और जेल, खाने की दवाएँ, दूसरे पील, या कोई उपचार नहीं।", "छिलती त्वचा को न खींचें, हल्का क्लेंज़र और मॉइस्चराइज़र लगाएँ, रोज़ SPF 50 लगाएँ, 5 दिन रेटिनॉइड क्रीम बंद रखें।"],
        ar: ["يوضع محلول حمض الساليسيليك على الوجه لبضع دقائق لتقشير الجلد وفتح المسام. عادةً 4 جلسات بفاصل أسبوعين.", "تقليل حب الشباب وتحسين ملمس البشرة والمساعدة في تفتيح الآثار.",
          ["لسعة واحمرار وتقشّر لمدة 3–5 أيام", "جفاف وشد في الجلد", "تغمّق الجلد (فرط تصبغ بعد الالتهاب)", "زيادة حب الشباب في أول 1–2 أسبوع", "تسمم الساليسيلات (طنين، غثيان) عند المساحات الكبيرة", "حروق أو قشور أو ندبات"],
          "الكريمات والجل وحدها، أو الأدوية الفموية، أو تقشير آخر، أو عدم العلاج.", "لا تنزع الجلد المتقشر، واستخدم منظفًا ومرطبًا لطيفين، وواقي شمس SPF 50 يوميًا، وأوقف كريمات الريتينويد 5 أيام."] } },
    rct: { en: "Root canal treatment, tooth 46", ar: "علاج عصب السن ٤٦", hi: "रूट कैनाल उपचार, दाँत 46", form: "DENT-RCT", ver: "1.8", lv: ["c", "c", "u", "u", "r", "r", "r"],
      t: { en: ["The infected nerve (pulp) is removed under local anaesthesia; the canals are cleaned, shaped and sealed. Usually 2–3 visits, followed by a crown.", "Relieves pain and infection and saves the natural tooth.",
          ["Soreness for 2–3 days after each visit", "Numb lip and tongue for a few hours (anaesthetic)", "Flare-up of swelling needing antibiotics", "Tooth fracture if a crown is not placed", "An instrument breaking inside the canal", "Treatment failure needing re-treatment, surgery or extraction", "Prolonged numbness of lip or tongue (nerve injury)"],
          "Extraction (with or without an implant, bridge or denture), or no treatment (infection may spread).", "Do not chew on the tooth until the crown is fitted, take medicines as advised, come back if swelling increases."],
        hi: ["लोकल एनेस्थीसिया देकर संक्रमित नस (पल्प) निकाली जाती है; नलिकाओं को साफ़ करके भरा जाता है। आमतौर पर 2–3 विज़िट, फिर क्राउन।", "दर्द और संक्रमण से राहत, और प्राकृतिक दाँत बच जाता है।",
          ["हर विज़िट के बाद 2–3 दिन दर्द", "कुछ घंटों तक होंठ और जीभ सुन्न (एनेस्थीसिया)", "सूजन बढ़ना जिसके लिए एंटीबायोटिक लगे", "क्राउन न लगाने पर दाँत टूटना", "नलिका में उपकरण टूटना", "उपचार विफल होना: दोबारा उपचार, सर्जरी या दाँत निकालना", "होंठ या जीभ का लंबे समय तक सुन्न रहना (नस में चोट)"],
          "दाँत निकालना (इम्प्लांट, ब्रिज या डेन्चर के साथ या बिना), या कोई उपचार नहीं (संक्रमण फैल सकता है)।", "क्राउन लगने तक उस दाँत से न चबाएँ, बताई गई दवाएँ लें, सूजन बढ़े तो तुरंत आएँ।"],
        ar: ["يُزال العصب الملتهب (اللب) تحت التخدير الموضعي، وتُنظّف القنوات وتُشكّل ثم تُحشى. عادةً 2–3 زيارات يتبعها تاج.", "يخفف الألم والالتهاب ويحافظ على السن الطبيعي.",
          ["ألم لمدة 2–3 أيام بعد كل زيارة", "تنميل الشفة واللسان لبضع ساعات (التخدير)", "تفاقم التورم مما يستدعي مضادًا حيويًا", "كسر السن إذا لم يُركّب تاج", "انكسار أداة داخل القناة", "فشل العلاج والحاجة لإعادته أو جراحة أو خلع", "تنميل طويل للشفة أو اللسان (إصابة العصب)"],
          "خلع السن (مع أو بدون زرعة أو جسر أو طقم)، أو عدم العلاج (قد ينتشر الالتهاب).", "تجنّب المضغ على السن حتى تركيب التاج، وتناول الأدوية حسب الإرشاد، وراجع إذا زاد التورم."] } },
    ear: { en: "Ear wax removal (syringing / microsuction)", ar: "إزالة شمع الأذن (غسل / شفط دقيق)", hi: "कान का मैल निकालना (सिरिंजिंग / माइक्रोसक्शन)", form: "ENT-WAX", ver: "1.1", lv: ["c", "u", "u", "r", "r"],
      t: { en: ["Wax is removed from the ear canal with warm-water irrigation or microsuction after softening drops.", "Relieves blockage, fullness and hearing loss caused by wax.",
          ["Brief dizziness or discomfort", "Ringing in the ear (tinnitus), usually short-lived", "Outer ear infection (otitis externa)", "Perforation of the eardrum", "Temporary change in hearing"],
          "Softening drops alone, manual removal, or no treatment.", "Keep the ear dry for 2 days, do not use cotton buds, come back if there is pain, discharge or reduced hearing."],
        hi: ["कान को नरम करने वाली बूँदों के बाद गुनगुने पानी से धोकर या माइक्रोसक्शन से कान का मैल निकाला जाता है।", "मैल से होने वाला बंदपन, भारीपन और कम सुनाई देना ठीक होता है।",
          ["थोड़ी देर चक्कर या असुविधा", "कान में घंटी बजना, आमतौर पर थोड़े समय के लिए", "बाहरी कान में संक्रमण", "कान के पर्दे में छेद", "सुनने में अस्थायी बदलाव"],
          "केवल नरम करने वाली बूँदें, हाथ से निकालना, या कोई उपचार नहीं।", "2 दिन कान सूखा रखें, ईयरबड का उपयोग न करें, दर्द, स्राव या कम सुनाई दे तो आएँ।"],
        ar: ["يُزال الشمع من قناة الأذن بالغسل بالماء الدافئ أو الشفط الدقيق بعد قطرات التليين.", "يخفف الانسداد والامتلاء وضعف السمع الناتج عن الشمع.",
          ["دوخة أو انزعاج قصير", "طنين في الأذن غالبًا قصير المدة", "التهاب الأذن الخارجية", "ثقب طبلة الأذن", "تغيّر مؤقت في السمع"],
          "قطرات التليين وحدها أو الإزالة اليدوية أو عدم العلاج.", "حافظ على جفاف الأذن يومين، ولا تستخدم أعواد القطن، وراجع عند الألم أو الإفرازات أو ضعف السمع."] } }
  };
  SD.procs.peel = SD.procs.sal;
  var CH = { en: ["What it is", "Expected benefit", "Risks", "Alternatives", "Aftercare", "I confirm", ["Common", "Uncommon", "Rare"],
      ["The procedure, its risks and alternatives were explained to me in a language I understand.", "I had the chance to ask questions and they were answered.", "I can withdraw consent at any time before the procedure."]],
    hi: ["यह क्या है", "अपेक्षित लाभ", "जोखिम", "विकल्प", "बाद की देखभाल", "मैं पुष्टि करता/करती हूँ", ["आम", "कम आम", "दुर्लभ"],
      ["प्रक्रिया, उसके जोखिम और विकल्प मुझे मेरी समझ की भाषा में समझाए गए।", "मुझे प्रश्न पूछने का अवसर मिला और उनके उत्तर दिए गए।", "मैं प्रक्रिया से पहले कभी भी सहमति वापस ले सकता/सकती हूँ।"]],
    ar: ["ما هو الإجراء", "الفائدة المتوقعة", "المخاطر", "البدائل", "العناية بعد الإجراء", "أؤكد ما يلي", ["شائع", "غير شائع", "نادر"],
      ["شُرح لي الإجراء ومخاطره وبدائله بلغة أفهمها.", "أُتيحت لي فرصة طرح الأسئلة وتمت الإجابة عنها.", "يمكنني سحب موافقتي في أي وقت قبل الإجراء."]] };
  function consentWho(ref) {
    if (ref === "dent") { var dp = dentPatient(); return { name: dp.name, age: NB.gcc() ? 52 : 49, sex: "M", lang: NB.gcc() ? "ar" : "hi", key: "dent" }; }
    if (/^p\d+$/.test(ref)) { var p = NB.pat(ref); return { name: NB.pname(p), age: p.age, sex: p.sex, lang: p.lang || "en", key: ref }; }
    return { name: ref, age: 31, sex: "M", lang: NB.gcc() ? "ar" : "en", key: ref };
  }
  var SIG = {};
  function sigPaths(strokes, W, H) { return strokes.map(function (s) { return "M" + s.map(function (p) { return (p[0] * W).toFixed(1) + " " + (p[1] * H).toFixed(1); }).join(" L"); }).join(" "); }
  function fakeHash(s) { var h = [0x811c9dc5, 0x01000193, 0x5bd1e995, 0x27d4eb2d, 0x165667b1, 0x9e3779b1, 0x85ebca6b, 0xc2b2ae35]; for (var i = 0; i < s.length; i++) for (var j = 0; j < 8; j++) h[j] = Math.imul(h[j] ^ s.charCodeAt(i), 16777619 + j * 2) >>> 0; return h.map(function (x) { return ("0000000" + x.toString(16)).slice(-8); }).join(""); }
  NB.overlay("procconsent", function (arg) {
    var a = String(arg || "lhr|p5").split("|"), pr = SD.procs[a[0]] || SD.procs.lhr, who = consentWho(a[1] || "p5"), key = a[0] + "|" + (a[1] || "p5");
    var signed = v("spSigned", {})[key], lang = v("spCLang_" + key, who.lang === "ar" || who.lang === "hi" ? who.lang : NB.gcc() ? "ar" : "en"), T = pr.t[lang], C = CH[lang], minor = who.age < 18;
    var title = L("Procedure consent", "الموافقة على الإجراء", "प्रक्रिया सहमति") + " · " + esc(who.name);
    var meta = '<div class="sp-pt">' + lead("acc", "pen") + '<div class="fl"><b>' + esc(L(pr.en, pr.ar, pr.hi)) + "</b><small>" + esc(who.name + " · " + who.age + " " + who.sex + " · form " + pr.form + " v" + pr.ver + " · reviewed 01 Sep 2026") + "</small></div>" + (signed ? ui.pill("ok", "Signed · locked", "lock") : ui.pill("warn", "Not signed", "pen")) + "</div>";
    if (signed) {
      var strokes = SIG[key] || [];
      var paper = '<div class="sp-paper"><div class="hd">' + lead("acc", "logo") + '<div class="fl"><b>' + esc(NB.fac().name) + "</b><small>" + esc(NB.fac().area) + "</small></div><small>" + esc(pr.form + " v" + pr.ver) + "</small></div>" +
        "<b>" + esc(pr.t.en ? pr.en : "") + "</b><small>" + esc("Patient: " + who.name + ", " + who.age + " " + who.sex + " · language of explanation: " + { en: "English", hi: "Hindi", ar: "Arabic" }[lang] + " · explained by Dr. " + (a[0] === "rct" ? "Ahmed Siddiqui" : NB.state.role === "solo" ? "Meera Iyer" : "Vikram Shah")) + "</small>" +
        "<small>" + esc(T[2].length + " risks listed and acknowledged · 3 statements confirmed") + "</small>" +
        '<div class="sp-g2" style="gap:12px"><div><svg class="sig" viewBox="0 0 260 80" aria-label="Signature"><path d="' + sigPaths(strokes, 260, 80) + '"/></svg><small>' + esc((minor ? "Guardian" : "Patient") + " · " + signed.at) + '</small></div><div><b style="display:block;font-size:12px;margin-top:40px">' + esc(signed.wit) + "</b><small>Witness · confirmed with staff PIN</small></div></div>" +
        '<div class="sp-hash">SHA-256 ' + signed.hash + "</div></div>";
      return { kind: "drawer", size: "lg", title: title, body: meta + ui.note("ok", "<b>Signed and locked.</b> The PDF is stored against today's visit and cannot be edited. A change needs a new version; a withdrawal is recorded alongside, never replacing it (CMP-02).") + paper,
        foot: ui.btn(L("Send PDF on WhatsApp", "إرسال PDF عبر واتساب", "PDF व्हाट्सऐप पर भेजें"), { act: "toast", arg: "Signed consent PDF sent to " + who.name, icon: "send" }) + ui.btn(L("Download PDF", "تنزيل PDF", "PDF डाउनलोड"), { act: "toast", arg: "consent-" + pr.form.toLowerCase() + "-" + iso(TODAY) + ".pdf downloaded", icon: "download" }) + ui.btn(L("Done", "تم", "हो गया"), { cls: "pri", act: "close" }) };
    }
    var dir = lang === "ar" ? "rtl" : "ltr";
    var text = '<div class="sp-ctext" dir="' + dir + '" lang="' + lang + '"><h4>' + esc(C[0]) + "</h4><div>" + esc(T[0]) + "</div><h4>" + esc(C[1]) + "</h4><div>" + esc(T[1]) + "</div><h4>" + esc(C[2]) + "</h4><ul>" +
      T[2].map(function (r, i) { var lv = pr.lv[i], k = lv === "c" ? 0 : lv === "u" ? 1 : 2; return "<li><b>" + esc(C[6][k]) + ":</b> " + esc(r) + "</li>"; }).join("") + "</ul><h4>" + esc(C[3]) + "</h4><div>" + esc(T[3]) + "</div><h4>" + esc(C[4]) + "</h4><div>" + esc(T[4]) + "</div></div>";
    var stm = '<div class="stack s4" dir="' + dir + '" lang="' + lang + '"><span class="eyebrow">' + esc(C[5]) + "</span>" + C[7].map(function (s, i) { var on = v("spCst_" + key + "_" + i, false); return '<div class="tog" data-toggle="spCst_' + key + "_" + i + '" role="switch" aria-checked="' + on + '" tabindex="0"><div class="t"><b style="font-weight:500">' + esc(s) + "</b></div>" + sw(on) + "</div>"; }).join("") + "</div>";
    var langSeg = '<div class="row sb">' + ui.seg("spCLang_" + key, [["en", "English"], ["hi", "हिन्दी"], ["ar", "العربية"]], lang) + '<span class="small faint">' + esc(L("Shown in the patient's language", "تُعرض بلغة المريض", "मरीज़ की भाषा में")) + "</span></div>";
    var sig = '<div class="stack s8"><div class="row sb"><span class="eyebrow">' + esc(minor ? L("Guardian signature", "توقيع ولي الأمر", "अभिभावक के हस्ताक्षर") : L("Patient signature", "توقيع المريض", "मरीज़ के हस्ताक्षर")) + "</span>" +
      (minor ? ui.pill("info", "Minor · guardian signs") : "") + '<button class="btn sm ghost" data-act="sp-sigclear" data-arg="' + esc(key) + '">' + ui.icon("refresh", "sm") + esc(L("Clear", "مسح", "साफ़ करें")) + "</button></div>" +
      '<div class="sp-sigw"><canvas class="sp-sigc" id="sp-sigc" data-key="' + esc(key) + '" aria-label="Signature pad: sign with a finger, stylus or mouse"></canvas><span class="sp-sigl"></span><span class="sp-sigh" id="sp-sigst">' + esc((SIG[key] || []).length ? L("Signature captured", "تم التقاط التوقيع", "हस्ताक्षर कैप्चर") : L("Sign here with a finger or mouse", "وقّع هنا بإصبعك أو بالفأرة", "यहाँ उँगली या माउस से हस्ताक्षर करें")) + "</span></div></div>";
    var wit = '<div class="fgrid"><div class="field"><label for="sp-wit">' + esc(L("Witness", "الشاهد", "गवाह")) + '</label><select id="sp-wit" data-change="spWit_' + esc(key) + '">' + ["Anita D'Souza · OPD nurse", "Sneha Kulkarni · front desk", "Dr. Ahmed Siddiqui · dentist"].map(function (x) { return "<option" + (v("spWit_" + key, "Anita D'Souza · OPD nurse") === x ? " selected" : "") + ">" + esc(x) + "</option>"; }).join("") + "</select></div>" +
      ui.field(L("Interpreter (optional)", "مترجم (اختياري)", "दुभाषिया (वैकल्पिक)"), "", { ph: "Name, if used" }) + "</div>" + ui.toggle("spWpin_" + key, L("Witness confirms with staff PIN", "يؤكد الشاهد برمز الموظف", "गवाह स्टाफ़ PIN से पुष्टि करे"), "Recorded with time and device", false);
    return { kind: "drawer", size: "lg", title: title, body: meta + langSeg + text + stm + sig + wit,
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { cls: "ghost", act: "close" }) + ui.btn(L("Sign & generate PDF", "توقيع وإنشاء PDF", "हस्ताक्षर करें और PDF बनाएँ"), { cls: "pri", act: "sp-sign", arg: key, icon: "pen" }) };
  });
  NB.act("sp-sigclear", function (k) { SIG[k] = []; NB.render(); });
  NB.act("sp-sign", function (k) {
    if (!(SIG[k] || []).length) { NB.toast("Ask the patient to sign in the box first"); return; }
    for (var i = 0; i < 3; i++) if (!v("spCst_" + k + "_" + i, false)) { NB.toast("The patient must confirm all three statements"); return; }
    if (!v("spWpin_" + k, false)) { NB.toast("The witness must confirm with their staff PIN"); return; }
    var s = Object.assign({}, v("spSigned", {})), now = new Date();
    s[k] = { at: fd(TODAY) + " " + ("0" + now.getHours()).slice(-2) + ":" + ("0" + now.getMinutes()).slice(-2), wit: v("spWit_" + k, "Anita D'Souza · OPD nurse"), hash: fakeHash(k + JSON.stringify(SIG[k])) };
    NB.state.ui.spSigned = s; NB.toast("Consent signed · PDF generated and locked to the visit");
  });
  // Bind the signature canvas after each render (pointer events: finger, stylus or mouse).
  function bindSig() {
    var cv = document.getElementById("sp-sigc"); if (!cv) return;
    var key = cv.getAttribute("data-key"), strokes = SIG[key] = SIG[key] || [], r = cv.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
    if (!r.width) return;
    cv.width = Math.round(r.width * dpr); cv.height = Math.round(r.height * dpr);
    var g = cv.getContext("2d"); g.scale(dpr, dpr); g.strokeStyle = getComputedStyle(cv).color; g.lineWidth = 2.4; g.lineCap = "round"; g.lineJoin = "round";
    function P(p) { return [p[0] * r.width, p[1] * r.height]; }
    strokes.forEach(function (s) { g.beginPath(); var a = P(s[0]); g.moveTo(a[0], a[1]); if (s.length === 1) g.lineTo(a[0] + 0.1, a[1]); s.forEach(function (p) { var b = P(p); g.lineTo(b[0], b[1]); }); g.stroke(); });
    var cur = null;
    function pt(e) { var b = cv.getBoundingClientRect(); return [Math.max(0, Math.min(1, (e.clientX - b.left) / b.width)), Math.max(0, Math.min(1, (e.clientY - b.top) / b.height))]; }
    cv.addEventListener("pointerdown", function (e) { e.preventDefault(); try { cv.setPointerCapture(e.pointerId); } catch (x) {} cur = [pt(e)]; strokes.push(cur); var a = P(cur[0]); g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(a[0] + 0.1, a[1]); g.stroke(); });
    cv.addEventListener("pointermove", function (e) { if (!cur) return; var p = pt(e), q = cur[cur.length - 1]; cur.push(p); var a = P(q), b = P(p); g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.stroke(); });
    function end() { if (!cur) return; cur = null; var st = document.getElementById("sp-sigst"); if (st) st.textContent = L("Signature captured", "تم التقاط التوقيع", "हस्ताक्षर कैप्चर"); }
    cv.addEventListener("pointerup", end); cv.addEventListener("pointercancel", end); cv.addEventListener("pointerleave", end);
  }
  var prevAfter = NB.afterRender;
  NB.afterRender = function () { if (typeof prevAfter === "function") prevAfter(); bindSig(); };

  /* ================= Tooth editor ================= */
  NB.overlay("toothedit", function (arg) {
    var t = +arg || 46, prim = Math.floor(t / 10) > 4, map = teethMap(prim), cur = map[t] || "healthy", surf = v("spSurf_" + t, SD.surf[t] || "");
    var typ = { m: "Molar", p: "Premolar", c: "Canine", l: "Lateral incisor", i: "Central incisor" }[tType(t)], q = Math.floor(t / 10), side = (q === 1 || q === 4 || q === 5 || q === 8) ? "right" : "left", arch = (q === 1 || q === 2 || q === 5 || q === 6) ? "upper" : "lower";
    var conds = '<div class="sp-cond">' + SD.conds.map(function (c) { return '<button class="sp-cb' + (c[0] === cur ? " on" : "") + '" data-act="sp-tooth" data-arg="' + t + ":" + c[0] + '"><i class="sp-sw ' + c[0] + '">' + esc(c[4]) + "</i>" + esc(L(c[1], c[2], c[3])) + "</button>"; }).join("") + "</div>";
    var surfs = '<div class="stack s8"><span class="eyebrow">' + L("Surfaces", "الأسطح", "सतहें") + " (M · O/I · D · B/F · L/P)</span><div class=\"sp-surf\">" + ["M", tType(t) === "i" || tType(t) === "l" || tType(t) === "c" ? "I" : "O", "D", arch === "upper" ? "B" : "F", arch === "upper" ? "P" : "L"].map(function (s) {
      return chip(surf.indexOf(s) >= 0, s, { act: "sp-surf", arg: t + ":" + s }); }).join("") + "</div></div>";
    var procs = ["Composite restoration", "Root canal treatment", "Zirconia crown", "PFM crown", "Extraction", "Surgical extraction", "Implant (stage 1)", "Fissure sealant", "Pulpotomy (primary)", "Stainless-steel crown (primary)", "Scaling (quadrant)"];
    var hist = { 46: [["Today", "IOPA: periapical widening; RCT started, access opened"], ["14 Sep", "Bitewing: deep distal caries"], ["2023", "Composite O (failed)"]], 36: [["Jul 2025", "Implant crown fitted (zirconia)"], ["Mar 2025", "Implant placed, 4.3 × 10 mm"], ["2024", "Extraction, vertical root fracture"]] }[t] || [["14 Sep", "Charted at full-mouth exam"]];
    var body = '<div class="sp-pt">' + lead("acc", "tooth") + '<div class="fl"><b>' + esc(L("Tooth", "السن", "दाँत") + " " + t + " · " + typ) + "</b><small>" + esc("FDI " + t + " · Universal " + univ(t) + " · " + arch + " " + side + (prim ? " · primary" : "")) + "</small></div></div>" +
      (SD.toothNote[t] ? ui.note("info", esc(SD.toothNote[t])) : "") + '<span class="eyebrow">' + L("Condition", "الحالة", "स्थिति") + "</span>" + conds + surfs +
      '<div class="fgrid">' + ui.field("Planned procedure", t === 46 ? "Root canal treatment" : "Composite restoration", { options: procs, id: "sp-tproc" }) + ui.field("Material", "Bulk-fill composite", { options: ["Bulk-fill composite", "GIC", "Zirconia", "PFM", "Amalgam (legacy only)", "MTA"], id: "sp-tmat" }) +
      ui.field("Shade (VITA classical)", "A2", { options: ["A1", "A2", "A3", "A3.5", "B1", "B2", "C1", "D2"], id: "sp-tshade" }) + ui.field("Fee", "", { ph: "From price master", id: "sp-tfee" }) + "</div>" +
      '<div class="sp-g2" style="gap:12px">' + '<div class="stack s8"><span class="eyebrow">X-ray</span>' + xray(t) + "</div>" + '<div class="stack s8"><span class="eyebrow">' + L("Tooth history", "تاريخ السن", "दाँत का इतिहास") + " · DENT-08</span>" + '<div class="tl">' + hist.map(function (h) { return '<div class="ev"><span class="dotc">' + ui.icon("history", "sm") + '</span><div class="bd"><b>' + esc(h[0]) + "</b><small>" + esc(h[1]) + "</small></div></div>"; }).join("") + "</div></div></div>";
    return { kind: "drawer", size: "lg", title: L("Tooth", "السن", "दाँत") + " " + tLabel(t), body: body,
      foot: ui.btn(L("Close", "إغلاق", "बंद करें"), { cls: "ghost", act: "close" }) + ui.btn(L("Add to treatment plan", "إضافة إلى خطة العلاج", "उपचार योजना में जोड़ें"), { cls: "pri", act: "sp-planadd", arg: t, icon: "plus" }) };
  });
  NB.act("sp-tooth", function (a) { var p = a.split(":"), t = +p[0], prim = t > 50, k = prim ? "spTeethP" : "spTeeth", m = Object.assign({}, v(k, {})); m[t] = p[1]; NB.state.ui[k] = m; NB.toast("Tooth " + tLabel(t) + ": " + cond(p[1])[1]); });
  NB.act("sp-surf", function (a) { var p = a.split(":"), k = "spSurf_" + p[0], s = v(k, SD.surf[p[0]] || ""); s = s.indexOf(p[1]) >= 0 ? s.replace(p[1], "") : s + p[1]; var o = {}; o[k] = s; NB.setUI(o); });
  NB.act("sp-planadd", function (t) {
    var pe = document.getElementById("f-sp-tproc"), me = document.getElementById("f-sp-tmat"), pr = pe ? pe.value : "Composite restoration", fees = { "Composite restoration": 1800, "Root canal treatment": 6500, "Zirconia crown": 9000, "PFM crown": 6000, "Extraction": 1200, "Surgical extraction": 4500, "Implant (stage 1)": 30000, "Fissure sealant": 800, "Pulpotomy (primary)": 2500, "Stainless-steel crown (primary)": 3000, "Scaling (quadrant)": 600 };
    var arr = v("spPlanAdd", []).slice(); arr.push([String(t), pr + (me && /crown|restoration/i.test(pr) ? " · " + me.value : ""), fees[pr] || 1500]); NB.state.ui.spPlanAdd = arr; NB.state.overlay = null; NB.toast(pr + " for tooth " + tLabel(+t) + " added to Phase 2 of the plan");
  });
  NB.act("sp-dnote", function (i) { var arr = v("spDNote", [SD.dentPresets[0], SD.dentPresets[1]]).slice(), t = SD.dentPresets[+i]; var at = arr.indexOf(t); if (at >= 0) arr.splice(at, 1); else arr.push(t); NB.setUI({ spDNote: arr }); });

  /* ================= Lesion editor ================= */
  NB.overlay("lesion", function (arg) {
    var isNew = /^new:/.test(arg), l;
    if (isNew) { var p = arg.split(":"); l = { id: "new", view: p[1], x: +p[2], y: +p[3], site: siteName(p[1], +p[2], +p[3]), morph: "Papule", size: "", count: 1, change: "New", note: "" }; }
    else l = lesions().find(function (x) { return x.id === arg; }) || SD.lesions[0];
    var idx = lesions().findIndex(function (x) { return x.id === l.id; }) + 1;
    var ch = v("spLchg", l.change);
    var body = '<div class="sp-pt">' + lead(isNew ? "acc" : "urg", "user") + '<div class="fl"><b>' + esc(isNew ? L("New lesion", "آفة جديدة", "नया घाव") : L("Lesion", "آفة", "घाव") + " " + idx) + "</b><small>" + esc(l.site + " · " + (l.view === "b" ? "back" : "front") + " view") + "</small></div></div>" +
      '<div class="fgrid">' + ui.field("Site", l.site, { id: "sp-lsite" }) + ui.field("Morphology", l.morph, { options: SD.morph.indexOf(l.morph) >= 0 ? SD.morph : SD.morph.concat([l.morph]), id: "sp-lmorph" }) +
      ui.field("Size (mm)", l.size, { id: "sp-lsize", ph: "e.g. 4" }) + ui.field("Count", String(l.count), { id: "sp-lcount" }) + ui.field("Notes / dermoscopy", l.note, { area: true, full: true, id: "sp-lnote" }) + "</div>" +
      '<div class="stack s8"><span class="eyebrow">' + L("Change since last visit", "التغير منذ الزيارة السابقة", "पिछली विज़िट से बदलाव") + "</span>" + ui.seg("spLchg", [["New", "New"], ["Better", "Better"], ["Same", "Same"], ["Worse", "Worse"]], l.change) + "</div>" +
      (ch === "Worse" || /naevus/i.test(l.morph) ? ui.note("warn", "Pigmented or changing lesion: record dermoscopy (ABCD / 7-point checklist) and set a 3-month photo review.") : "") +
      '<div class="btns">' + ui.btn(L("Attach photo", "إرفاق صورة", "फ़ोटो जोड़ें"), { cls: "sm", act: "sp-photo", icon: "camera" }) + "</div>";
    return { kind: "drawer", title: esc(isNew ? L("Mark lesion", "تحديد آفة", "घाव चिह्नित करें") : l.site), body: body,
      foot: (isNew ? "" : ui.btn(L("Remove", "إزالة", "हटाएँ"), { cls: "dng", act: "sp-lesdel", arg: l.id, icon: "close" })) + ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { cls: "ghost", act: "close" }) + ui.btn(L("Save to visit", "حفظ في الزيارة", "विज़िट में सेव"), { cls: "pri", act: "sp-lessave", arg: isNew ? arg : l.id, icon: "check" }) };
  });
  NB.act("sp-mapadd", function (a, el, ev) {
    var svg = el.closest ? (el.tagName.toLowerCase() === "svg" ? el : el.closest("svg")) : el; if (!svg || !ev) return;
    var r = svg.getBoundingClientRect(), vb = svg.viewBox.baseVal, x = (ev.clientX - r.left) / r.width * vb.width, y = (ev.clientY - r.top) / r.height * vb.height;
    var view = x >= 150 ? "b" : "f", lx = view === "b" ? x - 150 : x;
    if (!onBody(lx, y)) { NB.toast("Tap on the body outline to mark a lesion"); return; }
    NB.state.ui.spLchg = "New"; NB.open("lesion", "new:" + view + ":" + Math.round(lx) + ":" + Math.round(y));
  });
  NB.act("sp-lessave", function (a) {
    var g = function (id) { var e = document.getElementById("f-" + id); return e ? e.value : ""; };
    var data = { site: g("sp-lsite"), morph: g("sp-lmorph"), size: g("sp-lsize"), count: +g("sp-lcount") || 1, note: g("sp-lnote"), change: v("spLchg", "New") };
    if (/^new:/.test(a)) { var p = a.split(":"), arr = v("spLesNew", []).slice(); arr.push(Object.assign({ id: "N" + (arr.length + 1), view: p[1], x: +p[2], y: +p[3] }, data)); NB.state.ui.spLesNew = arr; }
    else if (/^N/.test(a)) { NB.state.ui.spLesNew = v("spLesNew", []).map(function (l) { return l.id === a ? Object.assign({}, l, data) : l; }); }
    else { var ed = Object.assign({}, v("spLesEd", {})); ed[a] = data; NB.state.ui.spLesEd = ed; }
    delete NB.state.ui.spLchg; NB.state.overlay = null; NB.toast("Lesion saved to today's visit map · " + data.site);
  });
  NB.act("sp-lesdel", function (a) { if (/^N/.test(a)) NB.state.ui.spLesNew = v("spLesNew", []).filter(function (l) { return l.id !== a; }); else { var d = Object.assign({}, v("spLesDel", {})); d[a] = 1; NB.state.ui.spLesDel = d; } NB.state.overlay = null; NB.toast("Lesion removed from today's map"); });

  /* ================= Growth point ================= */
  NB.overlay("growthpoint", function () {
    var last = gpoints()[gpoints().length - 1];
    var body = '<div class="sp-pt">' + lead("acc", "ruler") + '<div class="fl"><b>' + esc(NB.pname(NB.pat("p4"))) + "</b><small>6 y 2 m · girl · plotted on WHO curves for age and sex</small></div></div>" +
      '<div class="fgrid">' + ui.field("Date", fd(TODAY), { id: "sp-gdate" }) + ui.field("Weight (kg, 2 decimals)", last[1].toFixed(2), { id: "sp-gw", type: "number" }) + ui.field("Height (cm)", last[2].toFixed(1), { id: "sp-gh", type: "number" }) +
      '<div class="field"><label for="f-sp-ghc">Head circumference</label><input id="f-sp-ghc" disabled value="" placeholder="Only under 2 years"></div>' + "</div>" +
      ui.note("info", "Weigh without shoes in light clothing on the calibrated scale; measure standing height without shoes. A drop across two centile lines raises a growth-faltering alert.");
    return { kind: "modal", title: L("Record growth", "تسجيل النمو", "ग्रोथ दर्ज करें"), body: body, foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { cls: "ghost", act: "close" }) + ui.btn(L("Plot and save", "رسم وحفظ", "प्लॉट करें और सेव करें"), { cls: "pri", act: "sp-growsave", icon: "check" }) };
  });
  NB.act("sp-growsave", function () {
    var w = parseFloat((document.getElementById("f-sp-gw") || {}).value), hh = parseFloat((document.getElementById("f-sp-gh") || {}).value);
    if (!(w > 5 && w < 60) || !(hh > 70 && hh < 160)) { NB.toast("Check the values: weight 5–60 kg, height 70–160 cm"); return; }
    NB.state.ui.spGrowToday = { w: Math.round(w * 100) / 100, h: Math.round(hh * 10) / 10 }; NB.state.ui.spPedTab = "growth"; NB.state.overlay = null;
    NB.toast("Plotted: weight " + cfmt(centile(SD.wfa, 74, w)) + " centile · height " + cfmt(centile(SD.hfa, 74, hh)) + " centile");
  });

  /* ================= Recall rule ================= */
  NB.overlay("recallrule", function (id) {
    var r = SD.rules.find(function (x) { return x[0] === id; }) || ["new", "chronic", "New recall rule", "Every 3 months", "7 days before due", "chronic_review_v3", 2, 3, true];
    var lang = v("spRrL", NB.gcc() ? "ar" : "en"), rs = SD.reasons.find(function (x) { return x[0] === r[1]; });
    var whatT = { en: r[2].toLowerCase(), hi: { chronic: "नियमित समीक्षा", vaccine: "टीका", package: "पैकेज सत्र", postproc: "प्रक्रिया के बाद की जाँच", lab: "जाँच" }[r[1]], ar: { chronic: "المراجعة الدورية", vaccine: "اللقاح", package: "جلسة الباقة", postproc: "فحص ما بعد الإجراء", lab: "الفحص" }[r[1]] }[lang];
    var msg = SD.tpl[lang].replace("{name}", lang === "ar" ? "عبدالله" : lang === "hi" ? "रवि जी" : "Ravi").replace("{clinic}", NB.fac().name).replace("{what}", whatT).replace("{doctor}", lang === "ar" ? "د. فيكرام شاه" : "Dr. Vikram Shah").replace("{due}", lang === "ar" ? "٢٤ أكتوبر" : "24 Oct");
    var bt = { en: "Book a time", hi: "समय बुक करें", ar: "احجز موعدًا" }[lang];
    var body = '<div class="sp-pt">' + lead("acc", rs[4]) + '<div class="fl"><b>' + esc(r[2]) + "</b><small>" + esc(L(rs[1], rs[2], rs[3]) + " · template " + r[5]) + "</small></div></div>" +
      '<div class="fgrid">' + ui.field("Rule name", r[2]) + ui.field("Trigger", r[3]) + ui.field("Send", r[4], { options: ["On the due date", "1 day before", "2 days before", "3 days before + on due day", "5 days before", "7 days before due"].concat([r[4]]).filter(function (x, i, a) { return a.indexOf(x) === i; }) }) +
      ui.field("WhatsApp template", r[5], { options: ["chronic_review_v3", "vaccine_due_v2", "package_session_v4", "post_procedure_v1", "lab_repeat_v2", "dental_recall_v1", "skin_followup_v2"] }) + "</div>" +
      '<div class="stack s8"><span class="eyebrow">' + L("Attempts", "المحاولات", "प्रयास") + "</span>" + ui.seg("spRrMax", [["1", "1"], ["2", "2"], ["3", "3"]], String(r[6])) + '<span class="small faint">' + esc("Gap between attempts: " + (r[7] || 3) + " days · after the last attempt, non-bookers go to the front-desk callback list") + "</span></div>" +
      '<div class="fgrid">' + ui.field("Quiet hours from", NB.gcc() ? "22:00" : "21:00", { options: ["20:00", "21:00", "22:00"] }) + ui.field("Quiet hours until", NB.gcc() ? "08:00" : "09:00", { options: ["07:00", "08:00", "09:00"] }) + "</div>" +
      (NB.gcc() ? ui.toggle("spRrPray", "Prayer-aware send times", "Holds messages around the five prayer times for " + NB.mkt().city + "; Friday sends after 14:00", true) : ui.toggle("spRrSun", "Skip Sunday mornings", "Holds messages until 11:00 on Sundays", true)) +
      ui.toggle("spRrSms", "SMS fallback", "If WhatsApp is not delivered within 4 hours", true) + ui.toggle("spRrCb", "Escalate to front-desk callback", "Patients who do not book after the last attempt", true) +
      '<div class="row sb"><span class="eyebrow">' + L("Preview", "معاينة", "पूर्वावलोकन") + "</span>" + ui.seg("spRrL", [["en", "EN"], ["hi", "हिं"], ["ar", "عربي"]], lang) + "</div>" +
      '<div class="sp-wa"><div class="sp-bub" dir="' + (lang === "ar" ? "rtl" : "ltr") + '" lang="' + lang + '">' + esc(msg) + '<span class="wb">' + esc(bt) + "</span></div></div>";
    return { kind: "drawer", title: L("Recall rule", "قاعدة الاستدعاء", "रिकॉल नियम"), body: body, foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { cls: "ghost", act: "close" }) + ui.btn(L("Save rule", "حفظ القاعدة", "नियम सेव करें"), { cls: "pri", act: "toast", arg: "Recall rule saved · applies to new and existing due dates", icon: "check" }) };
  });

  /* ================= Actions ================= */
  NB.act("sp-photo", function () { NB.toast(v("spMktConsent", false) ? "Camera opened · photo attached to today's visit (clinical + marketing consent on file)" : "Camera opened · photo attached to today's visit · clinical consent on file, marked “clinical use only”"); });
  NB.act("sp-ph", function (id) {
    var A = v("spPhA", "v1"), B = v("spPhB", "v4"), ix = function (x) { return SD.photos.findIndex(function (p) { return p.id === x; }); };
    if (id === A || id === B) return;
    if (ix(id) < ix(B)) NB.setUI({ spPhA: id }); else NB.setUI({ spPhB: id });
  });
  NB.act("sp-grade", function (a) {
    var p = a.split(":"), G = Object.assign({}, v("spPatchG", {})), base = SD.patch.find(function (x) { return x[0] === p[0]; }), cur = G[a] || (p[1] === "2" ? base[3] : "−"), i = SD.grades.indexOf(cur);
    G[a] = SD.grades[(i + 1) % SD.grades.length]; NB.setUI({ spPatchG: G });
  });
  NB.act("sp-sessave", function (i) {
    if (!v("spSigned", {})["lhr|p5"]) { NB.toast("Session consent must be signed before saving"); NB.open("procconsent", "lhr|p5"); return; }
    var S = Object.assign({}, v("spSessDone", {})); S[i] = 1; NB.state.ui.spSessDone = S; NB.toast("Session 4 saved with settings and photo · next session reminder set for 14 Nov");
  });
  NB.act("sp-regadd", function () { NB.toast("Rakesh Menon added to the CKD register from today's note · review every 3 months"); });
  NB.act("sp-recallone", function (nm) { NB.toast("Recall sent to " + nm + " on WhatsApp with a one-tap booking link"); });
  NB.act("sp-rc", function (a) {
    var p = a.split("|"), st = Object.assign({}, v("spRcSt", {})), r = SD.recalls.find(function (x) { return x[0] === p[0]; }), nm = /^p\d+$/.test(r[1]) ? pnm(r[1]) : r[1];
    st[p[0]] = p[1]; NB.state.ui.spRcSt = st;
    NB.toast(p[1] === "sent" ? "Recall sent to " + nm + " on WhatsApp now (quiet hours respected)" : p[1] === "call" ? "Calling " + nm + " from the clinic number · outcome logged" : nm + " marked not needed · reason recorded");
  });
  NB.act("sp-enttag", function (tg) { var o = Object.assign({}, v("spEntTags", { "TM dull, retracted (R)": 1, "Fluid level / bubbles (R)": 1, "Light reflex absent (R)": 1, "TM normal (L)": 1, "Tonsils grade 2": 1 })); o[tg] = !o[tg]; NB.setUI({ spEntTags: o }); });
  NB.act("sp-physio", function () { NB.state.ui.spPhysioDone = true; NB.toast("Session 6 of 10 attended · WhatsApp reminder set for session 7"); });
  NB.act("sp-xrrev", function (i) { var o = Object.assign({}, v("spXrRev", {})); o[i] = 1; NB.state.ui.spXrRev = o; NB.toast("X-ray marked reviewed · sign-off logged"); });
})();
