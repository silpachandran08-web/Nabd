/* Hospital module: the standard hospital departments beyond OPD and IPD.
   Emergency (role "er"): ER board, rapid ESI triage with stroke / STEMI / sepsis screeners, time-critical pathway clocks,
     ambulance pre-arrival and the medico-legal case register.
   Radiology (role "radiology"): imaging worklist with contrast / renal / pregnancy / PCPNDT checks, modality schedule,
     structured reporting with a PACS hand-off placeholder, and the critical-findings communication log.
   Hospital services (role "hospops"): blood bank, CSSD, dietary, central stores & procurement, medical records (MRD),
     duty roster and ambulance fleet.
   Data lives in NB.D.hospital and reuses NB.D.patients / doctors / beds. Regulatory specifics are examples per market
   and are labelled as such on screen. Styles: one injected <style>, classes prefixed hs-, nb.css tokens only. */
(function () {
  "use strict";
  var ui = NB.ui, L = NB.L, esc = ui.esc;

  /* ---------------- Styles (hs- prefix, nb.css variables only) ---------------- */
  function mix(v, p) { return "color-mix(in srgb,var(" + v + ") " + p + "%,transparent)"; }
  var TONES = { crit: "--crit", urg: "--urg", warn: "--warn", ok: "--ok", info: "--info", sync: "--sync", sub: "--sub", acc: "--acc" };
  var CSS = [
    /* ESI badge */
    ".hs-esi{display:inline-flex;align-items:center;gap:4px;height:22px;padding:0 8px;border-radius:999px;font:600 10.5px/1 var(--font);border:1px solid;white-space:nowrap;flex:none}",
    ".hs-esi .ico{width:12px;height:12px}",
    ".hs-esi.e1{color:var(--crit);background:var(--crit-t);border-color:" + mix("--crit", 48) + "}",
    ".hs-esi.e2{color:var(--urg);background:var(--urg-t);border-color:" + mix("--urg", 48) + "}",
    ".hs-esi.e3{color:var(--warn);background:var(--warn-t);border-color:" + mix("--warn", 48) + ";border-style:dotted}",
    ".hs-esi.e4{color:var(--ok);background:var(--ok-t);border-color:" + mix("--ok", 45) + "}",
    ".hs-esi.e5{color:var(--info);background:var(--info-t);border-color:" + mix("--info", 45) + "}",
    /* ER bays */
    ".hs-zh{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:12px}.hs-zh h2{flex:1;min-width:150px}.hs-zh .bar{width:120px}",
    ".hs-bays{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:10px}",
    ".frame.mobile .hs-bays{grid-template-columns:minmax(0,1fr)}",
    ".hs-bay{display:flex;flex-direction:column;gap:6px;padding:12px;border-radius:var(--r-md);border:1px solid var(--b-sub);border-inline-start-width:4px;background:var(--s2);cursor:pointer;min-width:0;text-align:start;color:var(--tx)}",
    ".hs-bay:hover{background:var(--s3)}",
    ".hs-bay.e1{border-inline-start-color:var(--crit);border-color:" + mix("--crit", 40) + ";border-inline-start-color:var(--crit)}",
    ".hs-bay.e2{border-inline-start-color:var(--urg)}.hs-bay.e3{border-inline-start-color:var(--warn)}.hs-bay.e4{border-inline-start-color:var(--ok)}.hs-bay.e5{border-inline-start-color:var(--info)}",
    ".hs-bay.empty{border-style:dashed;border-inline-start-width:1px;background:transparent;cursor:default;justify-content:flex-start}",
    ".hs-bay.empty:hover{background:transparent}",
    ".hs-bay.pre{border-style:dashed;border-color:" + mix("--urg", 55) + ";border-inline-start-width:1px;background:var(--urg-t)}",
    ".hs-bn{font:600 13px/18px var(--font);font-variant-numeric:tabular-nums;color:var(--tx2);white-space:nowrap}",
    ".hs-pn{font-weight:600;font-size:14px;line-height:19px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
    ".hs-bay small{display:block;color:var(--tx3);font-size:11.5px;line-height:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}",
    ".hs-meta{display:flex;flex-wrap:wrap;gap:4px 10px;font-size:11.5px;line-height:15px;color:var(--tx2)}",
    ".hs-meta span{display:inline-flex;align-items:center;gap:4px;white-space:nowrap;min-width:0}.hs-meta .ico{width:13px;height:13px}",
    ".hs-chips{display:flex;flex-wrap:wrap;gap:4px}",
    ".hs-tid{height:4px;border-radius:99px;background:var(--s3);overflow:hidden;margin-top:2px}.hs-tid i{display:block;height:100%;border-radius:99px;background:var(--acc)}",
    ".hs-tid.warn i{background:var(--warn)}.hs-tid.crit i{background:var(--crit)}",
    ".hs-leg{display:flex;flex-wrap:wrap;gap:6px;align-items:center}",
    /* pathway clocks */
    ".hs-clock{width:116px;height:116px;border-radius:999px;display:grid;place-items:center;background:conic-gradient(var(--hc,var(--acc)) var(--p,0%),var(--s3) 0);position:relative;flex:none}",
    ".hs-clock::after{content:'';position:absolute;inset:9px;border-radius:999px;background:var(--s1)}",
    ".hs-clock div{position:relative;z-index:1;text-align:center}.hs-clock b{display:block;font:600 24px/28px var(--font);font-variant-numeric:tabular-nums}",
    ".hs-clock small{display:block;color:var(--tx3);font-size:10.5px;line-height:13px}",
    ".hs-clock.ok{--hc:var(--ok)}.hs-clock.warn{--hc:var(--warn)}.hs-clock.crit{--hc:var(--crit)}",
    ".hs-pw{display:flex;gap:16px;align-items:center;flex-wrap:wrap}.hs-pw .fl{min-width:180px}",
    /* checklists */
    ".hs-ck{display:flex;flex-direction:column}",
    ".hs-ci{display:flex;gap:10px;align-items:flex-start;padding:10px 0;border-bottom:1px solid var(--div);min-width:0}.hs-ci:last-child{border-bottom:0}",
    ".hs-ci.click{cursor:pointer}.hs-ci.click:hover b{color:var(--acc)}",
    ".hs-ci .t{flex:1;min-width:0}.hs-ci b{display:block;font-size:13.5px;font-weight:500;line-height:19px}.hs-ci small{display:block;color:var(--tx3);font-size:12px;line-height:16px}",
    ".hs-ci>.ico{margin-top:1px}",
    ".hs-tm{font:600 12px/19px var(--font);font-variant-numeric:tabular-nums;color:var(--tx2);white-space:nowrap}",
    ".hs-tm.over{color:var(--crit)}.hs-tm.due{color:var(--warn)}",
    ".hs-cb{width:20px;height:20px;border-radius:6px;border:2px solid var(--tx3);flex:none;display:grid;place-items:center;margin-top:0}",
    ".hs-cb .ico{width:13px;height:13px;stroke-width:3}",
    ".hs-cb.on{background:var(--ok);border-color:var(--ok);color:var(--bg-canvas)}",
    /* vitals */
    ".hs-vit{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}",
    ".frame.mobile .hs-vit{grid-template-columns:repeat(2,minmax(0,1fr))}",
    ".hs-v{background:var(--s2);border:1px solid var(--b-sub);border-radius:var(--r-md);padding:9px 12px;min-width:0}",
    ".hs-v label{display:block;font-size:11.5px;color:var(--tx3);line-height:15px}",
    ".hs-v b{display:block;font:600 18px/24px var(--font);font-variant-numeric:tabular-nums;white-space:nowrap}",
    ".hs-v small{display:block;font-size:11px;line-height:14px;color:var(--tx3)}",
    ".hs-v.dz{border-color:" + mix("--urg", 55) + ";background:var(--urg-t)}.hs-v.dz small{color:var(--urg);font-weight:600}",
    /* ESI result */
    ".hs-esibig{display:flex;align-items:center;gap:14px;padding:14px;border-radius:var(--r-md);border:1px solid}",
    ".hs-esibig .n{font:700 36px/1 var(--font);font-variant-numeric:tabular-nums;flex:none}",
    ".hs-esibig b{display:block;font-size:15px}.hs-esibig small{display:block;font-size:12px;color:var(--tx2);line-height:16px}",
    ".hs-esibig.e1{color:var(--crit);background:var(--crit-t);border-color:" + mix("--crit", 45) + "}",
    ".hs-esibig.e2{color:var(--urg);background:var(--urg-t);border-color:" + mix("--urg", 45) + "}",
    ".hs-esibig.e3{color:var(--warn);background:var(--warn-t);border-color:" + mix("--warn", 45) + "}",
    ".hs-esibig.e4{color:var(--ok);background:var(--ok-t);border-color:" + mix("--ok", 45) + "}",
    ".hs-esibig.e5{color:var(--info);background:var(--info-t);border-color:" + mix("--info", 45) + "}",
    ".hs-esibig .fl b{color:var(--tx)}",
    /* generic tone boxes */
    ".hs-box{border:1px solid var(--b-def);border-radius:var(--r-md);padding:12px 14px;background:var(--s2);display:flex;flex-direction:column;gap:8px;min-width:0}",
    ".hs-box.sel{border-color:var(--acc-line);background:var(--acc-tint)}",
    ".hs-box.crit{border-color:" + mix("--crit", 45) + ";background:var(--crit-t)}",
    ".hs-box.urg{border-color:" + mix("--urg", 45) + ";background:var(--urg-t)}",
    ".hs-box.warn{border-color:" + mix("--warn", 45) + ";background:var(--warn-t)}",
    ".hs-box.ok{border-color:" + mix("--ok", 40) + ";background:var(--ok-t)}",
    ".hs-box h3{margin:0;font:600 14px/20px var(--font);display:flex;align-items:center;gap:8px}",
    /* key/value */
    ".hs-kv{display:grid;grid-template-columns:auto minmax(0,1fr);gap:7px 14px;font-size:13px;line-height:18px;margin:0}",
    ".hs-kv dt{color:var(--tx3)}.hs-kv dd{margin:0;min-width:0;font-weight:500}",
    ".hs-atm{display:grid;grid-template-columns:24px minmax(0,1fr);gap:6px 10px;font-size:13px;line-height:18px;margin:0}",
    ".hs-atm dt{color:var(--acc);font-weight:700}.hs-atm dd{margin:0;min-width:0}",
    /* ETA chip */
    ".hs-eta{display:flex;flex-direction:column;align-items:center;justify-content:center;min-width:78px;padding:8px 10px;border-radius:var(--r-md);background:var(--urg-t);color:var(--urg);border:1px solid " + mix("--urg", 45) + ";flex:none;text-align:center}",
    ".hs-eta b{font:700 22px/26px var(--font);font-variant-numeric:tabular-nums}.hs-eta small{font-size:10.5px;line-height:13px;font-weight:600}",
    ".hs-eta.info{background:var(--info-t);color:var(--info);border-color:" + mix("--info", 45) + "}",
    /* modality schedule */
    ".hs-sch{overflow-x:auto;border:1px solid var(--b-sub);border-radius:var(--r-lg);background:var(--s1)}",
    ".hs-schin{min-width:1060px;padding:12px 14px 14px}",
    ".hs-schr{display:grid;grid-template-columns:160px minmax(0,1fr);gap:10px;align-items:stretch}.hs-schr+.hs-schr{margin-top:8px}",
    ".hs-schl{display:flex;flex-direction:column;justify-content:center;font-size:13px;font-weight:600;min-width:0;line-height:17px}.hs-schl small{color:var(--tx3);font-weight:400;font-size:11px;line-height:14px}",
    ".hs-hrs{display:grid;grid-template-columns:repeat(12,minmax(0,1fr));font:600 10.5px/14px var(--font);color:var(--tx3);font-variant-numeric:tabular-nums}",
    ".hs-hrs span{border-inline-start:1px solid var(--b-def);padding-inline-start:4px}",
    ".hs-lane{position:relative;display:grid;grid-template-columns:repeat(72,minmax(0,1fr));min-height:58px;border-radius:var(--r-md);background-color:var(--sunk);background-image:linear-gradient(90deg,var(--div) 1px,transparent 1px);background-size:calc(100% / 12) 100%}",
    "[dir=rtl] .hs-lane{background-image:linear-gradient(270deg,var(--div) 1px,transparent 1px)}",
    ".hs-slot{grid-row:1;margin:4px 2px;border-radius:10px;border:1px solid var(--b-def);background:var(--s2);padding:5px 8px;text-align:start;cursor:pointer;min-width:0;overflow:hidden;font:inherit;color:var(--tx);display:flex;flex-direction:column;gap:1px}",
    ".hs-slot b{font-size:12px;line-height:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-weight:600}.hs-slot small{font-size:10.5px;line-height:13px;color:var(--tx3);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
    ".hs-slot b,.hs-slot small{unicode-bidi:plaintext}",
    ".hs-slot.done{background:transparent;border-style:dashed}",
    ".hs-slot.live{background:var(--acc-tint2);border-color:var(--acc)}",
    ".hs-slot.stat{border-inline-start:3px solid var(--crit)}",
    ".hs-slot.down{background:var(--perm-t);border-style:dashed;border-color:var(--b-str);cursor:default}",
    ".hs-slot.free{background:transparent;border-style:dashed;border-color:var(--acc-line);color:var(--acc);align-items:center;justify-content:center}",
    ".hs-slot.free b{color:var(--acc)}",
    ".hs-now{position:absolute;top:-4px;bottom:-4px;width:2px;background:var(--crit);border-radius:2px;pointer-events:none;z-index:1}",
    ".hs-now::before{content:'';position:absolute;top:-3px;inset-inline-start:-3px;width:8px;height:8px;border-radius:99px;background:var(--crit)}",
    /* reporting + PACS */
    ".hs-rep{display:grid;grid-template-columns:270px minmax(0,1fr);gap:20px;align-items:start}",
    ".frame.tablet .hs-rep,.frame.mobile .hs-rep{grid-template-columns:minmax(0,1fr)}",
    ".hs-rv{display:grid;grid-template-columns:minmax(0,1fr);gap:16px;align-items:start}",
    ".frame.tablet .hs-rv,.frame.mobile .hs-rv{grid-template-columns:minmax(0,1fr)}",
    ".hs-pacs{background:var(--sunk);border:1px solid var(--b-def);border-radius:var(--r-lg);overflow:hidden;color:var(--tx3);display:flex;flex-direction:column;min-width:0}",
    ".hs-pacs svg{display:block;width:100%;height:auto}",
    ".hs-pacs .ln{fill:none;stroke:var(--b-str);stroke-width:1.5}.hs-pacs .ln2{fill:none;stroke:var(--tx4);stroke-width:1.5;stroke-dasharray:4 5}",
    ".hs-pacs .fil{fill:var(--s2);stroke:var(--b-str);stroke-width:1.5}.hs-pacs .ax{stroke:var(--acc-line);stroke-width:1}",
    ".hs-pacs text{fill:var(--tx2);font:500 12px var(--font)}.hs-pacs text.c{fill:var(--tx);font-weight:600;font-size:14px}",
    ".hs-pbar{display:flex;align-items:center;gap:8px;padding:8px 10px;border-bottom:1px solid var(--b-sub);flex-wrap:wrap;background:var(--s1);color:var(--tx2);font-size:12px}",
    ".hs-pbar .fl{min-width:120px}",
    ".hs-thumbs{display:flex;gap:6px;padding:8px 10px;border-top:1px solid var(--b-sub);overflow-x:auto;background:var(--s1)}",
    ".hs-thumbs span{flex:none;width:58px;height:44px;border-radius:6px;border:1px solid var(--b-def);background:var(--s2);display:grid;place-items:center;font:600 10px/12px var(--font);color:var(--tx2);text-align:center}",
    ".hs-thumbs span.on{border-color:var(--acc);color:var(--acc)}",
    ".hs-sf{display:grid;grid-template-columns:170px minmax(0,1fr);gap:0;font-size:13px;line-height:18px;margin:0;border:1px solid var(--b-sub);border-radius:var(--r-md);overflow:hidden}",
    ".hs-sf dt{color:var(--tx3);padding:9px 12px;background:var(--s2);border-top:1px solid var(--div)}.hs-sf dd{margin:0;padding:9px 12px;border-top:1px solid var(--div);min-width:0}",
    ".hs-sf dt:first-of-type,.hs-sf dd:first-of-type{border-top:0}",
    ".frame.mobile .hs-sf{grid-template-columns:minmax(0,1fr)}.frame.mobile .hs-sf dd{border-top:0;padding-top:0;background:var(--s2)}",
    ".hs-hl{background:var(--warn-t);color:var(--tx);border-radius:4px;padding:0 3px;font-weight:600}",
    /* blood bank */
    ".hs-u{display:inline-flex;align-items:center;gap:4px;font-weight:600;font-variant-numeric:tabular-nums;padding:2px 8px;border-radius:8px;min-width:34px;justify-content:center;border:1px solid transparent}",
    ".hs-u .ico{width:12px;height:12px}",
    ".hs-u.low{color:var(--warn);background:var(--warn-t);border-color:" + mix("--warn", 40) + "}.hs-u.out{color:var(--crit);background:var(--crit-t);border-color:" + mix("--crit", 40) + "}",
    ".hs-grp{display:inline-grid;place-items:center;min-width:40px;height:26px;padding:0 6px;border-radius:8px;background:var(--crit-t);color:var(--crit);font:700 12.5px/1 var(--font);border:1px solid " + mix("--crit", 35) + ";direction:ltr;unicode-bidi:isolate;flex:none}",
    ".hs-2p{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}",
    ".hs-who{border:1px dashed var(--b-str);border-radius:var(--r-md);padding:12px;display:flex;gap:10px;align-items:center;min-width:0;cursor:pointer;background:var(--s2)}",
    ".hs-who .t{flex:1;min-width:0}.hs-who b{display:block;font-size:13.5px}.hs-who small{display:block;color:var(--tx3);font-size:12px;line-height:16px}",
    ".hs-who.ok{border-style:solid;border-color:" + mix("--ok", 50) + ";background:var(--ok-t)}",
    /* CSSD kanban */
    ".hs-kan{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px}",
    ".frame.tablet .hs-kan{grid-template-columns:repeat(3,minmax(0,1fr))}.frame.mobile .hs-kan{grid-template-columns:minmax(0,1fr)}",
    "@media (max-width:1250px){.frame.desktop .hs-kan{grid-template-columns:repeat(3,minmax(0,1fr))}}",
    ".hs-col{background:var(--s1);border:1px solid var(--b-sub);border-radius:var(--r-lg);padding:12px;display:flex;flex-direction:column;gap:8px;min-width:0}",
    ".hs-colh{display:flex;align-items:center;gap:8px;font-weight:600;font-size:13px;line-height:18px}.hs-colh .fl{min-width:0}",
    ".hs-colh .ico{color:var(--acc)}",
    ".hs-set{background:var(--s2);border:1px solid var(--b-sub);border-radius:var(--r-md);padding:10px;display:flex;flex-direction:column;gap:4px;min-width:0}",
    ".hs-set b{font-size:13px;font-weight:600;line-height:17px;white-space:nowrap}.hs-set small{font-size:11.5px;line-height:15px;color:var(--tx3)}",
    ".hs-set.q{border-color:" + mix("--crit", 50) + ";background:var(--crit-t)}",
    /* flow (PR to PO to GRN) */
    ".hs-flow{display:flex;align-items:center;gap:4px;flex-wrap:wrap}",
    ".hs-fs{display:inline-flex;align-items:center;gap:4px;height:24px;padding:0 8px;border-radius:999px;font:600 11px/1 var(--font);border:1px dashed var(--b-str);color:var(--tx2);background:transparent;white-space:nowrap}",
    ".hs-fs .ico{width:12px;height:12px}",
    ".hs-fs.done{color:var(--ok);background:var(--ok-t);border:1px solid " + mix("--ok", 40) + "}",
    ".hs-fs.cur{color:var(--acc);background:var(--acc-tint);border:1px solid var(--acc-line)}",
    ".hs-fs.bad{color:var(--crit);background:var(--crit-t);border:1px solid " + mix("--crit", 40) + "}",
    ".hs-flow>.ico{width:12px;height:12px;color:var(--tx3)}",
    /* roster */
    ".hs-ros{overflow-x:auto;border:1px solid var(--b-sub);border-radius:var(--r-lg);background:var(--s1)}",
    ".hs-ros table{width:100%;border-collapse:collapse;min-width:820px}",
    ".hs-ros th,.hs-ros td{padding:8px 6px;border-top:1px solid var(--div);text-align:center;font-size:12.5px;line-height:16px}",
    ".hs-ros th{background:var(--s3);font:600 10.5px/14px var(--font);letter-spacing:.4px;text-transform:uppercase;color:var(--tx3);border-top:0;white-space:nowrap}",
    "[dir=rtl] .hs-ros th{letter-spacing:0}",
    ".hs-ros td.n,.hs-ros th.n{text-align:start;padding-inline-start:14px;white-space:nowrap}",
    ".hs-ros td.n b{display:block;font-weight:500;font-size:13px}.hs-ros td.n small{display:block;color:var(--tx3);font-size:11px}",
    ".hs-ros .today{background:var(--acc-tint)}.hs-ros th.today{color:var(--acc)}",
    ".hs-ros tr.cov td{background:var(--s2);font-weight:600}",
    ".hs-ros tr.sec td{background:var(--s2);text-align:start;padding-inline-start:14px;font:600 11px/14px var(--font);letter-spacing:.5px;text-transform:uppercase;color:var(--tx2)}",
    ".hs-sh{display:inline-grid;place-items:center;min-width:36px;height:26px;padding:0 6px;border-radius:8px;font:700 11.5px/1 var(--font);border:1px solid;cursor:pointer;background:none;font-family:inherit}",
    ".hs-sh.M{color:var(--info);background:var(--info-t);border-color:" + mix("--info", 40) + "}",
    ".hs-sh.E{color:var(--sync);background:var(--sync-t);border-color:" + mix("--sync", 40) + "}",
    ".hs-sh.N{color:var(--sub);background:var(--sub-t);border-color:" + mix("--sub", 40) + "}",
    ".hs-sh.OC{color:var(--urg);background:var(--urg-t);border-color:" + mix("--urg", 40) + "}",
    ".hs-sh.L{color:var(--warn);background:var(--warn-t);border-color:" + mix("--warn", 45) + ";border-style:dotted}",
    ".hs-sh.OFF{color:var(--tx3);border-color:var(--b-def);border-style:dashed}",
    ".hs-sh.SW{outline:2px solid var(--acc);outline-offset:1px}",
    ".hs-cov{display:inline-flex;align-items:center;gap:3px;font-variant-numeric:tabular-nums;white-space:nowrap}.hs-cov .ico{width:12px;height:12px}",
    ".hs-cov.gap{color:var(--crit)}.hs-cov.ok{color:var(--ok)}",
    /* map placeholder */
    ".hs-map{align-self:start;border-radius:var(--r-lg);border:1px solid var(--b-def);background:var(--sunk);overflow:hidden;color:var(--tx3);position:relative}",
    ".hs-map svg{display:block;width:100%;height:auto}",
    ".hs-map .rd{fill:none;stroke:var(--b-str);stroke-width:7;stroke-linecap:round}.hs-map .rd2{fill:none;stroke:var(--b-def);stroke-width:3;stroke-linecap:round}",
    ".hs-map .blk{fill:var(--s1);stroke:var(--b-sub)}.hs-map .hos{fill:var(--acc-tint2);stroke:var(--acc);stroke-width:1.5}",
    ".hs-map .mk{stroke:var(--sunk);stroke-width:2}.hs-map .mk.ok{fill:var(--ok)}.hs-map .mk.urg{fill:var(--urg)}.hs-map .mk.info{fill:var(--info)}.hs-map .mk.mute{fill:var(--tx3)}.hs-map .mk.crit{fill:var(--crit)}",
    ".hs-map text{fill:var(--tx);font:600 11px var(--font)}.hs-map text.lb{fill:var(--tx2);font-weight:500}",
    ".hs-map .cap{position:absolute;inset-inline-start:10px;bottom:8px;font-size:11px;color:var(--tx2);background:var(--s1);border:1px solid var(--b-sub);border-radius:8px;padding:3px 8px}",
    /* misc */
    ".hs-tray{display:flex;gap:6px;flex-wrap:wrap}",
    ".hs-mini{font-size:12px;color:var(--tx2);line-height:16px}",
    ".hs-strip{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px}",
    ".hs-num{font-variant-numeric:tabular-nums;direction:ltr;unicode-bidi:isolate;white-space:nowrap}",
    ".hs-row-a{cursor:pointer}",
    "@media (max-width:760px){.hs-2p,.hs-vit{grid-template-columns:minmax(0,1fr)}.hs-sf{grid-template-columns:minmax(0,1fr)}.hs-sf dd{border-top:0;padding-top:0;background:var(--s2)}.hs-zh .bar{width:100%}}",
    "@media (max-width:760px){.drawer .hs-vit{grid-template-columns:repeat(2,minmax(0,1fr))}}"
  ].join("\n");
  if (!document.getElementById("hs-css")) { var st = document.createElement("style"); st.id = "hs-css"; st.textContent = CSS; document.head.appendChild(st); }

  /* ---------------- Helpers ---------------- */
  var NOW = 10 * 60 + 20; // the prototype's "now" is 10:20 today
  function tm(s) { var p = String(s).split(":"); return +p[0] * 60 + +p[1]; }
  function hhmm(m) { m = ((m % 1440) + 1440) % 1440; var h = Math.floor(m / 60), r = m % 60; return (h < 10 ? "0" : "") + h + ":" + (r < 10 ? "0" : "") + r; }
  function since(s) { return Math.max(0, NOW - tm(s)); }
  function dur(m) { m = Math.max(0, Math.round(m)); var h = Math.floor(m / 60), r = m % 60; return h ? h + " h " + (r < 10 ? "0" : "") + r + " min" : r + " min"; }
  function n(x) { return '<span class="num ltr">' + x + "</span>"; }
  function avi(icon, cls) { return '<span class="av ' + (cls || "") + '">' + ui.icon(icon, "sm") + "</span>"; }
  function mk() { return NB.state.market; }
  function MV(o) { return o[mk()] != null ? o[mk()] : o.india; }
  function H() { return NB.D.hospital; }
  function pnm(x) { if (x.pid) { var p = NB.pat(x.pid); return NB.pname(p); } return x.name; }
  function pobj(x) { return x.pid ? NB.pat(x.pid) : x; }
  function chk(key, def) { return !!NB.v(key, !!def); }
  // Checkbox row: key toggles in UI state (default from data).
  function ckRow(key, def, title, sub, right) {
    var on = chk(key, def);
    return '<div class="hs-ci click" data-act="hsck" data-arg="' + key + "|" + (def ? 1 : 0) + '" role="checkbox" aria-checked="' + on + '" tabindex="0"><span class="hs-cb' + (on ? " on" : "") + '">' + (on ? ui.icon("check") : "") + '</span><div class="t"><b>' + title + "</b>" + (sub ? "<small>" + sub + "</small>" : "") + "</div>" + (right || "") + "</div>";
  }
  function stRow(kind, title, sub, right) {
    var ic = { ok: ["checkcircle", "okc"], warn: ["alertcircle", "warnc"], crit: ["alertfill", "critc"], info: ["info", "infoc"], mute: ["circle", "faint"], urg: ["bolt", "warnc"] }[kind] || ["dot", "faint"];
    return '<div class="hs-ci">' + ui.icon(ic[0], "sm " + ic[1]) + '<div class="t"><b>' + title + "</b>" + (sub ? "<small>" + sub + "</small>" : "") + "</div>" + (right || "") + "</div>";
  }
  function exampleNote(extra) {
    return ui.note("info", "<b>" + esc(NB.mkt().name) + ":</b> regulatory names, forms and timelines on this screen are examples and are confirmed per facility licence and local counsel before go-live." + (extra ? " " + extra : ""));
  }
  NB.act("hsck", function (a) { var p = String(a).split("|"), o = {}; o[p[0]] = !NB.v(p[0], p[1] === "1"); NB.setUI(o); });
  NB.act("hsgo", function (a) { var p = String(a).split("|"); if (p[1]) NB.state.ui[p[1]] = p[2]; var r = p[0].split("."); NB.go(r[0], r[1]); });
  NB.act("hsdone", function (a) { var p = String(a).split("|"), m = Object.assign({}, NB.v("hsDone", {})); m[p[0]] = p[2] || true; NB.state.ui.hsDone = m; if (p[3] !== "keep") NB.state.overlay = null; NB.toast(p[1] || "Done"); });
  function isDone(k) { return NB.v("hsDone", {})[k]; }

  /* ---------------- Market-specific facts (examples) ---------------- */
  var REG = {
    india: { ems: "108 (state EMS) · 102 (maternal & child)", mlc: "Medico-legal case (MLC)", mlcShort: "MLC", police: "Police intimation (station diary entry)", rad: "AERB licence for X-ray / CT · PCPNDT registration for ultrasound",
      pcpndt: true, bb: "Blood-centre licence under the Drugs & Cosmetics Rules · NBTC guidelines · e-RaktKosh stock reporting", hv: "Haemovigilance Programme of India (HvPI)",
      birth: "Civil Registration System (RBD Act, 1969) · report within 21 days", death: "CRS death report + MCCD Form 4 (institutional deaths)", icd: "ICD-10 (WHO)",
      ret: [["Inpatient records", "At least 3 years from discharge (NMC / IMC regulations)"], ["Medico-legal records", "Until the case is closed, then per policy"], ["PCPNDT forms & images", "2 years, or until a court case ends"], ["Birth & death registers", "Permanent"]],
      reqSla: "72 h for inpatient record copies (regulation)", diet: "Veg · Jain · non-veg preferences", ramadan: false },
    uae: { ems: "998 (Ambulance)", mlc: "Police case register", mlcShort: "Police case", police: "Police notification", rad: "FANR licence (radiation sources)", pcpndt: false,
      bb: "DHA / DoH blood-bank standards (AABB-aligned)", hv: "Authority haemovigilance reporting", birth: "Birth notification to MOHAP / DHA systems", death: "Death notification and cause-of-death certificate", icd: "ICD-10-CM",
      ret: [["Health records", "At least 25 years from the last encounter (Federal Law 2/2019)"], ["Police-case records", "Until the case is closed, then 25 years"], ["Birth & death registers", "Permanent"]],
      reqSla: "per authority policy", diet: "Halal kitchen · Ramadan fasting schedule", ramadan: true },
    ksa: { ems: "997 (Saudi Red Crescent)", mlc: "Police case register", mlcShort: "Police case", police: "Police notification", rad: "NRRC licence (radiation)", pcpndt: false,
      bb: "MOH / SFDA blood-bank standards", hv: "MOH haemovigilance reporting", birth: "Birth notification (MOH → Civil Affairs)", death: "Death notification (MOH → Civil Affairs)", icd: "ICD-10-AM",
      ret: [["Health records", "Per MOH retention schedule (example: 10 years or more)"], ["Police-case records", "Until the case is closed"], ["Birth & death registers", "Permanent"]],
      reqSla: "per MOH policy", diet: "Halal kitchen · Ramadan fasting schedule", ramadan: true },
    qatar: { ems: "999 (HMC Ambulance Service)", mlc: "Police case register", mlcShort: "Police case", police: "Police notification", rad: "Radiation licence (MoECC)", pcpndt: false,
      bb: "MOPH blood-transfusion standards", hv: "MOPH haemovigilance reporting", birth: "MOPH birth notification", death: "MOPH death notification", icd: "ICD-10-AM (example)",
      ret: [["Health records", "Per MOPH retention policy (example)"], ["Police-case records", "Until the case is closed"], ["Birth & death registers", "Permanent"]],
      reqSla: "per MOPH policy", diet: "Halal kitchen · Ramadan fasting schedule", ramadan: true }
  };
  function R() { return REG[mk()] || REG.india; }

  /* ---------------- ESI ---------------- */
  var ESI = { 1: ["Resuscitation", "إنعاش", "पुनर्जीवन", "alertfill"], 2: ["Emergent", "طارئ", "आपात", "bolt"], 3: ["Urgent", "عاجل", "तत्काल", "alertcircle"], 4: ["Less urgent", "أقل إلحاحًا", "कम तत्काल", "checkcircle"], 5: ["Non-urgent", "غير عاجل", "ग़ैर-तत्काल", "info"] };
  function esiPill(l, short) { var e = ESI[l]; return '<span class="hs-esi e' + l + '" dir="auto">' + ui.icon(e[3]) + "ESI " + l + (short ? "" : " · " + L(e[0], e[1], e[2])) + "</span>"; }

  /* ================= Sample data (NB.D.hospital) ================= */
  NB.D.hospital = {
    er: {
      zones: [["resus", "Resuscitation", "إنعاش", "पुनर्जीवन", ["R1", "R2", "R3"]], ["acute", "Acute (majors)", "الحالات الحادة", "एक्यूट", ["A1", "A2", "A3", "A4", "A5", "A6"]],
        ["obs", "Observation", "الملاحظة", "ऑब्ज़र्वेशन", ["O1", "O2", "O3", "O4"]], ["fast", "Fast track (minors)", "المسار السريع", "फ़ास्ट ट्रैक", ["F1", "F2", "F3"]]],
      bayNote: { A3: ["clean", "Cleaning · ready 10:30"], A5: ["pre", "Pre-allocated · AMB 108 child, ETA 14 min"], O3: ["pre", "Pre-allocated · transfer-in chest pain, ETA 25 min"] },
      pts: [
        { id: "E1", name: "Unknown male (Trauma 0417)", age: "~30", sex: "M", esi: 1, bay: "R1", arr: "10:06", mode: "Ambulance · 108", cc: "Road traffic collision, motorcycle · GCS 9, hypotensive", doc: "Dr. Sameer Kulkarni", nurse: "Sr. Rekha Pillai",
          pend: [["FAST ultrasound", "image", "done"], ["CT trauma series", "image"], ["Crossmatch 4 PRBC + 4 FFP", "heartpulse"], ["ABG", "flask", "done"]], mlc: "MLC-2026-0417", path: "MTP · massive transfusion protocol", disp: null,
          vit: { hr: 128, sbp: 86, dbp: 52, rr: 26, spo2: 92, t: 36.1, gcs: 9, pain: "—" }, allergy: null, temp: true },
        { id: "E2", pid: "p3", age: 58, sex: "M", esi: 2, bay: "R2", arr: "09:58", mode: "Walk-in", cc: "Central chest pain 40 min, sweating · ST elevation V1–V4", doc: "Dr. Khalid Al-Saeed", nurse: "Br. Joel Mathew",
          pend: [["Troponin I (hs)", "flask"], ["Cath lab ready", "heartpulse"], ["ECG 12-lead", "activity", "done"]], path: "STEMI · door-to-balloon", disp: ["admit", "Cath lab → CCU"],
          vit: { hr: 104, sbp: 148, dbp: 92, rr: 22, spo2: 95, t: 36.8, gcs: 15, pain: "8/10" }, allergy: "Penicillin (anaphylaxis)" },
        { id: "E3", pid: "p8", age: 67, sex: "M", esi: 2, bay: "R3", arr: "10:02", mode: "Ambulance · own fleet", cc: "Left-sided weakness, slurred speech · last known well 08:50", doc: "Dr. Ayesha Siddiqui", nurse: "Sr. Anjali Rao",
          pend: [["CT brain + CTA", "image"], ["Creatinine / eGFR", "flask", "done"], ["NIHSS", "clipboard", "done"]], path: "Stroke · door-to-needle", disp: null,
          vit: { hr: 92, sbp: 176, dbp: 98, rr: 18, spo2: 96, t: 36.9, gcs: 14, pain: "2/10" }, allergy: "Aspirin (gastric upset)" },
        { id: "E4", name: "Lakshmi Narayan", ar: "لاكشمي نارايان", age: 74, sex: "F", esi: 2, bay: "A1", arr: "09:40", mode: "Walk-in · family", cc: "Fever, confusion, burning urine · BP 92/56", doc: "Dr. Rohit Menon", nurse: "Sr. Rekha Pillai",
          pend: [["Blood cultures × 2", "flask", "done"], ["Repeat lactate 11:52", "flask"], ["USG KUB", "image"]], path: "Sepsis · hour-1 bundle", disp: ["admit", "ICU-3 requested"],
          vit: { hr: 116, sbp: 94, dbp: 56, rr: 24, spo2: 93, t: 38.9, gcs: 14, pain: "4/10" }, allergy: null },
        { id: "E5", pid: "p7", age: 12, sex: "M", esi: 3, bay: "A2", arr: "09:15", mode: "Walk-in · mother", cc: "Acute asthma, moderate · 3 nebulisations", doc: "Dr. Nisha Bhat", nurse: "Br. Joel Mathew",
          pend: [["Reassess after neb 3", "heartpulse"], ["Chest X-ray", "image", "done"]], disp: ["discharge", "If SpO₂ ≥ 94% at 10:45"],
          vit: { hr: 112, sbp: 108, dbp: 66, rr: 26, spo2: 94, t: 37.2, gcs: 15, pain: "—" }, allergy: null },
        { id: "E6", name: "Imran Qureshi", ar: "عمران قريشي", age: 36, sex: "M", esi: 3, bay: "A4", arr: "08:52", mode: "Police vehicle", cc: "Assault · 4 cm scalp laceration, alleged blunt injury", doc: "Dr. Rohit Menon", nurse: "Sr. Anjali Rao",
          pend: [["CT head (NICE rules)", "image", "done"], ["Injury certificate", "file"]], mlc: "MLC-2026-0416", disp: ["discharge", "After suturing and certificate"],
          vit: { hr: 88, sbp: 132, dbp: 84, rr: 16, spo2: 99, t: 36.7, gcs: 15, pain: "5/10" }, allergy: null },
        { id: "E7", pid: "p6", age: 29, sex: "F", esi: 3, bay: "A6", arr: "09:30", mode: "Walk-in", cc: "Right lower abdominal pain 12 h, vomiting", doc: "Dr. Ayesha Siddiqui", nurse: "Sr. Anjali Rao",
          pend: [["Urine β-hCG", "flask"], ["CT abdomen (contrast)", "image"], ["Surgical review", "steth"]], disp: null,
          vit: { hr: 98, sbp: 118, dbp: 74, rr: 18, spo2: 99, t: 37.9, gcs: 15, pain: "7/10" }, allergy: "Sulfonamides (rash)" },
        { id: "E8", name: "Venu Gopal", ar: "فينو غوبال", age: 44, sex: "M", esi: 3, bay: "O1", arr: "07:05", mode: "Ambulance · 108", cc: "Fall under influence of alcohol · minor head injury, observation", doc: "Dr. Rohit Menon", nurse: "Br. Joel Mathew",
          pend: [["Neuro obs 2-hourly", "clipboard"]], mlc: "MLC-2026-0415", disp: ["lama", "Wants to leave · risks explained"],
          vit: { hr: 84, sbp: 128, dbp: 80, rr: 16, spo2: 98, t: 36.6, gcs: 15, pain: "3/10" }, allergy: null },
        { id: "E9", name: "Ramesh Kumar", ar: "راميش كومار", age: 52, sex: "M", esi: 3, bay: "O2", arr: "06:10", mode: "Walk-in", cc: "Chest pain, low-risk HEART score 3 · serial troponin", doc: "Dr. Sameer Kulkarni", nurse: "Sr. Rekha Pillai",
          pend: [["Troponin 3 h", "flask", "done"]], disp: ["transfer", "Cardiology OPD tomorrow · stable"],
          vit: { hr: 76, sbp: 134, dbp: 82, rr: 15, spo2: 98, t: 36.7, gcs: 15, pain: "0/10" }, allergy: null },
        { id: "E10", pid: "p9", age: 41, sex: "F", esi: 4, bay: "F1", arr: "09:48", mode: "Walk-in", cc: "Migraine, usual pattern, no red flags", doc: "Dr. Rohit Menon", nurse: "Sr. Anjali Rao",
          pend: [], disp: ["discharge", "Analgesia given · discharge 10:40"],
          vit: { hr: 78, sbp: 122, dbp: 78, rr: 14, spo2: 99, t: 36.6, gcs: 15, pain: "6/10" }, allergy: null },
        { id: "E11", pid: "p5", age: 45, sex: "M", esi: 5, bay: "F2", arr: "10:05", mode: "Walk-in", cc: "Superficial burn, left hand (kitchen) · dressing", doc: "—", nurse: "Br. Joel Mathew",
          pend: [], disp: null, vit: { hr: 80, sbp: 126, dbp: 80, rr: 14, spo2: 99, t: 36.7, gcs: 15, pain: "3/10" }, allergy: null }
      ],
      waiting: [["Nasreen Begum", "63 F", "Slurred speech since 09:55", "10:17", "stroke"], ["Thomas Varghese", "71 M", "Fever, confusion, burning urine", "10:14", "sepsis"], ["Abdullah Al-Harbi", "52 M", "Chest tightness 30 min", "10:19", "chest"]],
      tcases: {
        chest: { name: "Abdullah Al-Harbi", pid: "p10", age: 52, sex: "M", arr: "10:19", mode: "Walk-in", cc: "Chest tightness 30 min, radiating to left arm, sweating", vit: { hr: 104, sbp: 150, dbp: 92, rr: 20, spo2: 95, t: 36.9, gcs: 15, pain: 7, glu: 9.1 },
          flags: [2, 4], res: "2", chest: true, befast: [0, 0, 0, 0, 0], inf: false },
        stroke: { name: "Nasreen Begum", age: 63, sex: "F", arr: "10:17", mode: "Walk-in · son", cc: "Sudden slurred speech and right arm weakness since 09:55", vit: { hr: 88, sbp: 192, dbp: 104, rr: 18, spo2: 97, t: 36.8, gcs: 14, pain: 1, glu: 7.4 },
          flags: [2], res: "2", chest: false, befast: [0, 0, 1, 1, 1], inf: false },
        sepsis: { name: "Thomas Varghese", age: 71, sex: "M", arr: "10:14", mode: "Walk-in · daughter", cc: "Fever since yesterday, new confusion this morning, burning urine", vit: { hr: 118, sbp: 94, dbp: 58, rr: 24, spo2: 93, t: 38.9, gcs: 13, pain: 3, glu: 11.2 },
          flags: [2, 3], res: "2", chest: false, befast: [0, 0, 0, 0, 0], inf: true },
        minor: { name: "Kavya Reddy", pid: "p1", age: 34, sex: "F", arr: "10:12", mode: "Walk-in", cc: "2 cm cut on left index finger, kitchen knife, bleeding controlled", vit: { hr: 82, sbp: 118, dbp: 76, rr: 14, spo2: 99, t: 36.6, gcs: 15, pain: 3, glu: 5.6 },
          flags: [], res: "1", chest: false, befast: [0, 0, 0, 0, 0], inf: false }
      },
      paths: [
        { id: "stemi", pt: "E2", t: ["Door-to-balloon · STEMI", "من الباب إلى البالون", "डोर-टू-बैलून"], door: "09:58", target: 90, tLbl: "Target ≤ 90 min door-to-balloon (≤ 10 min door-to-ECG)", icon: "heartpulse",
          steps: [["12-lead ECG", "10:04", 10, "Within 10 min of arrival"], ["STEMI confirmed by ER consultant", "10:05", 15, "Anterior ST elevation V1–V4"], ["Cath lab activated (one call)", "10:07", 20, "Interventional cardiologist and team paged"],
            ["Aspirin 300 mg + ticagrelor 180 mg loading", "10:09", 25, "Penicillin allergy not relevant · no antiplatelet allergy"], ["Heparin bolus", "10:12", 30, "70 U/kg"], ["Consent for primary PCI", null, 35, "Wife on the way · patient has capacity"],
            ["Patient on cath table", null, 60, "Cath lab ETA 10:30"], ["Wire crossing (balloon time)", null, 90, ""]] },
        { id: "stroke", pt: "E3", t: ["Door-to-needle · acute stroke", "من الباب إلى الإبرة", "डोर-टू-नीडल"], door: "10:02", target: 60, tLbl: "Target ≤ 60 min door-to-needle (stretch goal ≤ 45) · thrombolysis window to 13:20", icon: "brain",
          steps: [["Stroke team alerted (pre-alert from ambulance)", "09:58", 0, "Code stroke called before arrival"], ["Glucose checked", "10:04", 5, "7.8 mmol/L"], ["NIHSS", "10:09", 10, "Score 9"], ["CT brain done", "10:14", 20, "Door-to-CT 12 min"],
            ["CT read: haemorrhage excluded", null, 30, "Radiologist reporting now"], ["BP below 185/110 before lysis", "10:16", 35, "176/98"], ["Consent and eligibility checklist", null, 40, "Aspirin intolerance noted · no anticoagulant"],
            ["Thrombolysis bolus", null, 60, "Due by 11:02"]] },
        { id: "sepsis", pt: "E4", t: ["Sepsis hour-1 bundle", "حزمة الإنتان", "सेप्सिस बंडल"], door: "09:40", target: 60, tLbl: "Time zero = triage 09:40 · bundle due 10:40 (Surviving Sepsis Campaign)", icon: "thermo",
          steps: [["Lactate measured", "09:52", 60, "3.6 mmol/L"], ["Blood cultures before antibiotics", "09:55", 60, "2 sets, 2 sites"], ["Broad-spectrum antibiotics", "10:12", 60, "Piperacillin-tazobactam 4.5 g IV · no penicillin allergy"],
            ["30 mL/kg crystalloid (hypotension or lactate ≥ 4)", null, 60, "1,200 of 1,800 mL running"], ["Vasopressor if MAP < 65 after fluids", null, 60, "MAP 69 now · reassess at 10:40"], ["Repeat lactate if first > 2", null, 132, "2 h after the first lactate"]] }
      ],
      amb: [
        { id: "AMB-07", src: "own", kind: "ALS", eta: 6, crew: "Paramedic Suresh B. · EMT Kiran V. · driver Mahesh", call: "Motorcycle vs truck, NH-75 · 1 adult", bay: "A2",
          atm: [["A", "Male, about 45 years"], ["T", "Injury 09:52 · on scene 10:03"], ["M", "Thrown 5 m, helmet on, high-energy"], ["I", "Deformed right thigh (femur?), abdominal guarding, abrasions"], ["S", "GCS 12 (E3 V4 M5), HR 118, BP 100/64, RR 24, SpO₂ 94% on O₂"], ["T", "Pelvic binder, traction splint, 2 large-bore IV, 500 mL saline, O₂ 15 L"]],
          vit: [["10:05", 122, "96/60", 26, 92, 11], ["10:15", 118, "100/64", 24, 94, 12]], team: "Trauma team" },
        { id: "108-KA-4412", src: "ems", kind: "BLS", eta: 14, crew: "State EMS crew", call: "8-year-old girl, febrile seizure at home", bay: "A5",
          atm: [["A", "Female, 8 years · mother with her"], ["T", "Seizure 09:58, lasted 4 min"], ["M", "Fever 2 days"], ["I", "Post-ictal, no injury"], ["S", "GCS 13 improving, HR 128, T 39.4 °C, SpO₂ 97% air, glucose 6.1"], ["T", "Recovery position, paracetamol not given"]],
          vit: [["10:12", 128, "100/62", 26, 97, 13]], team: "Paediatrics" },
        { id: "TRF-0921", src: "transfer", kind: "ALS", eta: 25, crew: "Polyclinic transfer · nurse escort", call: "Inter-facility transfer: 61 M, chest pain, ECG ST depression", bay: "O3",
          atm: [["A", "Male, 61 years · diabetic"], ["T", "Pain since 08:30"], ["M", "Referred from OPD after ECG"], ["I", "ST depression V4–V6 (ECG shared in Nabd)"], ["S", "HR 92, BP 142/88, SpO₂ 97%, pain 4/10"], ["T", "Aspirin 300 mg given 09:40, GTN spray"]],
          vit: [["09:50", 92, "142/88", 18, 97, 15]], team: "Cardiology" }
      ],
      mlc: [
        { no: "MLC-2026-0417", er: "E1", when: "Today 10:06", type: "Road traffic collision", pol: ["pending", "Whitefield PS informed by phone 10:12 · diary entry no. awaited"], cert: "draft", consent: "Implied consent (unconscious) · documented by 2 doctors",
          custody: [["Clothing and helmet", "Bagged, sealed HS-8812", "With ER in-charge"], ["Blood for alcohol", "Collected 10:15, sealed HS-8813", "Awaiting police handover"]], brought: "108 ambulance" },
        { no: "MLC-2026-0416", er: "E6", when: "Today 08:52", type: "Assault", pol: ["done", "HAL PS · HC 2231 Ravi · 09:20 · entry 114/2026"], cert: "pending", consent: "Written consent for examination and photographs",
          custody: [["Photographs of injury", "4 images, hashed in record", "Digital · audit-logged"]], brought: "Police vehicle" },
        { no: "MLC-2026-0415", er: "E8", when: "Today 07:05", type: "Fall · alcohol intoxication", pol: ["done", "Whitefield PS · 07:30 · entry 109/2026"], cert: "issued", consent: "Verbal consent, patient intoxicated · re-consented at 09:00",
          custody: [["Blood for alcohol", "Sealed HS-8807", "Handed to PC 4412 at 08:05 · signed"]], brought: "108 ambulance" },
        { no: "MLC-2026-0412", er: null, name: "Shalini M.", age: "23 F", when: "16 Oct 21:40", type: "Poisoning (organophosphate)", pol: ["done", "Mahadevapura PS · 22:05 · entry 98/2026"], cert: "issued", consent: "Relative consent (father) · patient intubated",
          custody: [["Gastric lavage sample", "Sealed HS-8790", "Handed to ASI Kumar 22:40 · signed"], ["Container brought by family", "Sealed HS-8791", "Handed to ASI Kumar 22:40 · signed"]], brought: "Family · now in ICU" },
        { no: "MLC-2026-0409", er: null, name: "Child, 4 y (guardian present)", age: "4 F", when: "15 Oct 18:10", type: "Scald burns 8% TBSA", pol: ["done", "Child-protection officer and police informed 18:40"], cert: "issued", consent: "Guardian consent · social worker present",
          custody: [], brought: "Parents" }
      ]
    },
    rad: {
      orders: [
        { id: "RAD-3101", er: "E3", pid: "p8", mod: "CT", study: "CT brain (plain) + CT angiography head & neck", prio: "stat", src: "ER R3", ref: "Dr. Ayesha Siddiqui", at: "10:06", st: "acquired", contrast: "iodinated", egfr: 38, preg: "na", note: "Code stroke · read plain CT first" },
        { id: "RAD-3102", er: "E1", name: "Unknown male (Trauma 0417)", age: 30, sex: "M", mod: "CT", study: "CT trauma series (head, C-spine, chest, abdomen, pelvis)", prio: "stat", src: "ER R1", ref: "Dr. Sameer Kulkarni", at: "10:14", st: "ordered", contrast: "iodinated", egfr: null, preg: "na", note: "Life-threatening: do not wait for creatinine" },
        { id: "RAD-3103", er: "E2", pid: "p3", mod: "XR", study: "Chest X-ray (portable AP)", prio: "stat", src: "ER R2", ref: "Dr. Khalid Al-Saeed", at: "10:03", st: "reported", contrast: "none", egfr: null, preg: "na" },
        { id: "RAD-3104", er: "E7", pid: "p6", mod: "CT", study: "CT abdomen & pelvis with IV contrast", prio: "urgent", src: "ER A6", ref: "Dr. Ayesha Siddiqui", at: "09:52", st: "ordered", contrast: "iodinated", egfr: 104, preg: "pending", note: "Query appendicitis · USG first if pregnant" },
        { id: "RAD-3105", er: "E4", name: "Lakshmi Narayan", age: 74, sex: "F", mod: "USG", study: "USG kidneys, ureters & bladder", prio: "urgent", src: "ER A1", ref: "Dr. Rohit Menon", at: "09:58", st: "scheduled", contrast: "none", egfr: 41, preg: "na", slot: "10:40 · USG-1" },
        { id: "RAD-3106", pid: "p2", mod: "USG", study: "Obstetric USG · dating & viability (14 wk)", prio: "routine", src: "OPD Room 5", ref: "Dr. Fatima Rahman", at: "08:40", st: "arrived", contrast: "none", egfr: null, preg: "pregnant", obst: true, slot: "10:30 · USG-2" },
        { id: "RAD-3107", pid: "p10", mod: "MRI", study: "MRI lumbar spine (plain)", prio: "routine", src: "OPD Room 6", ref: "Dr. Khalid Al-Saeed", at: "Yesterday", st: "scheduled", contrast: "none", egfr: null, preg: "na", mri: "Screening form done · no implants", slot: "11:30 · MRI" },
        { id: "RAD-3108", pid: "p9", mod: "MRI", study: "MRI brain with gadolinium", prio: "routine", src: "OPD Room 1", ref: "Dr. Vikram Shah", at: "Yesterday", st: "scheduled", contrast: "gadolinium", egfr: 96, preg: "neg", mri: "Screening form pending", slot: "13:00 · MRI" },
        { id: "RAD-3109", er: "E5", pid: "p7", mod: "XR", study: "Chest X-ray PA (paediatric protocol)", prio: "urgent", src: "ER A2", ref: "Dr. Nisha Bhat", at: "09:25", st: "reported", contrast: "none", egfr: null, preg: "na" },
        { id: "RAD-3110", name: "Ramesh Gowda", age: 63, sex: "M", mod: "XR", study: "Chest X-ray (portable) · pneumonia follow-up", prio: "routine", src: "Ward A-01", ref: "Dr. Vikram Shah", at: "07:30", st: "acquired", contrast: "none", egfr: null, preg: "na" },
        { id: "RAD-3111", name: "Venkatesh Rao", age: 68, sex: "M", mod: "XR", study: "Chest X-ray (portable) · post-CABG day 2", prio: "urgent", src: "ICU-1", ref: "Dr. Khalid Al-Saeed", at: "08:10", st: "acquired", contrast: "none", egfr: 55, preg: "na" }
      ],
      sched: [
        ["CT-1", "128-slice CT", [["08:00", 30, "done", "Joseph Mathew", "CT leg angio"], ["08:45", 30, "done", "OPD · Priya D.", "CT sinus"], ["09:30", 15, "done", "Ramesh Kumar", "CT coronary calcium"], ["10:10", 15, "done", "Mohammed Ali", "CT brain + CTA", 1], ["10:25", 30, "live", "Trauma 0417", "CT trauma series", 1], ["11:00", 30, "next", "Fatima Sheikh", "CT abdomen (β-hCG?)"], ["12:30", 30, "next", "OPD", "CT chest HRCT"]]],
        ["MRI", "1.5 T", [["08:00", 60, "done", "OPD", "MRI knee"], ["09:00", 60, "done", "IPD Ward A", "MRI brain"], ["10:00", 30, "down", "Helium & coil QA", ""], ["11:30", 45, "next", "Abdullah Al-Harbi", "MRI L-spine"], ["13:00", 45, "next", "Priya Sharma", "MRI brain + gado"]]],
        ["USG-1", "General ultrasound", [["08:30", 30, "done", "OPD", "USG abdomen"], ["09:15", 30, "done", "Ward A", "USG DVT screen"], ["10:00", 20, "live", "OPD", "USG thyroid"], ["10:40", 30, "next", "Lakshmi Narayan", "USG KUB", 1], ["11:30", 30, "next", "OPD", "USG abdomen"]]],
        ["USG-2", "Obstetric · PCPNDT-registered", [["09:00", 30, "done", "OPD", "Anomaly scan 20 wk"], ["10:30", 30, "next", "Ananya Rao", "Dating & viability"], ["12:00", 30, "next", "OPD", "Growth scan 32 wk"]]],
        ["XR-DR", "Digital X-ray room", [["08:00", 120, "done", "14 studies", "Walk-in OPD and ER"], ["10:00", 120, "live", "Walk-in queue · 3 waiting", "OPD and ER"], ["12:00", 120, "next", "Walk-in", "OPD and ER"]]],
        ["XR-P", "Portable X-ray", [["07:30", 60, "done", "Wards & ICU round", "6 studies"], ["10:00", 15, "done", "Rakesh Menon", "CXR ER R2", 1], ["13:30", 45, "next", "ICU round", "Ventilated patients"]]]
      ],
      crit: [
        { t: "10:16", id: "RAD-3101", pid: "p8", study: "CT angiography head", finding: "Occlusion of left M1 segment (MCA) · no haemorrhage", cat: 1, to: "Dr. Ayesha Siddiqui (ER)", via: "Phone ext 2201", rb: true, mins: 2, by: "Dr. Leena George", st: "closed" },
        { t: "09:05", id: "RAD-3092", name: "Venkatesh Rao", study: "Chest X-ray (portable)", finding: "New right apical pneumothorax, 2 cm rim", cat: 1, to: "Dr. Khalid Al-Saeed (ICU)", via: "Phone · mobile", rb: true, mins: 6, by: "Dr. Leena George", st: "closed" },
        { t: "08:20", id: "RAD-3088", name: "OPD · Priya D.", study: "CT sinus", finding: "Incidental 1.8 cm left adrenal nodule", cat: 3, to: "Dr. Vikram Shah", via: "Nabd inbox (non-urgent)", rb: false, mins: 0, by: "Dr. Imran Shaikh", st: "ack" },
        { t: "10:18", id: "RAD-3103", pid: "p3", study: "Chest X-ray (portable AP)", finding: "Widened mediastinum 8.5 cm · query aortic dissection before anticoagulation", cat: 1, to: "Dr. Khalid Al-Saeed (ER)", via: "Calling…", rb: false, mins: 2, by: "Dr. Leena George", st: "open" },
        { t: "16 Oct 22:40", id: "RAD-3071", name: "Shalini M.", study: "Chest X-ray", finding: "Aspiration pneumonia, right lower lobe", cat: 2, to: "ICU registrar on call", via: "Phone", rb: true, mins: 24, by: "Dr. Imran Shaikh", st: "closed" }
      ]
    },
    bb: {
      groups: ["O+", "O-", "A+", "A-", "B+", "B-", "AB+", "AB-"],
      comps: [["prbc", "PRBC", 42], ["ffp", "FFP", 365], ["plt", "Platelets", 5], ["cryo", "Cryo", 365]],
      inv: { "O+": [28, 34, 6, 10], "O-": [3, 6, 1, 2], "A+": [22, 26, 5, 8], "A-": [4, 5, 0, 2], "B+": [30, 29, 7, 9], "B-": [2, 4, 1, 1], "AB+": [8, 12, 2, 4], "AB-": [1, 3, 0, 1] },
      min: [6, 6, 2, 2], minNeg: [4, 4, 1, 1],
      exp: [["O+", "Platelets (RDP)", 3, "Tomorrow 08:00"], ["B+", "PRBC", 2, "19 Oct"], ["A+", "Platelets (RDP)", 2, "18 Oct 20:00"], ["O-", "PRBC", 1, "21 Oct"]],
      req: [
        { id: "XM-881", er: "E1", name: "Unknown male (Trauma 0417)", loc: "ER R1", grp: "O- → B+ (typed 10:22)", comp: "MTP pack 1: 4 PRBC + 4 FFP + 1 SDP", urg: "emergency", st: "issue", note: "2 units O-negative issued uncrossmatched 10:10 · signed by Dr. Sameer Kulkarni" },
        { id: "XM-880", name: "Venkatesh Rao", loc: "ICU-1", grp: "B+", comp: "2 PRBC", urg: "urgent", st: "ready", note: "Crossmatch compatible (AHG) 09:40 · Hb 7.4" },
        { id: "XM-879", name: "Lakshmi Devi", loc: "Ward A-02", grp: "A+", comp: "1 PRBC", urg: "routine", st: "workup", note: "Antibody screen positive · identification panel running · expect 13:00" },
        { id: "XM-878", name: "George Thomas", loc: "OT-2 · TKR tomorrow", grp: "O+", comp: "Group & save", urg: "planned", st: "done", note: "Sample valid 72 h · electronic issue eligible" },
        { id: "XM-877", pid: "p8", loc: "ER R3", grp: "AB+", comp: "Group & save (pre-thrombolysis)", urg: "urgent", st: "sample", note: "Sample received 10:18" }
      ],
      donors: [
        ["DN-2214", "Arvind Kulkarni", "O-", "Repeat voluntary", "14 Jun 2026", "14.6", "ok", "Eligible · recall sent for O-negative appeal"],
        ["DN-2213", "Sneha Iyer", "A+", "Voluntary · camp", "02 Oct 2026", "12.9", "warn", "Deferred until 30 Jan 2027 (interval for women, example: 4 months)"],
        ["DN-2212", "Faisal Khan", "B+", "Replacement (for XM-879)", "Today 09:10", "13.8", "ok", "Collected · TTI screening running"],
        ["DN-2211", "Rohit Das", "O+", "Voluntary", "Today 08:40", "11.9", "warn", "Deferred · Hb below 12.5 g/dL · iron advice given"],
        ["DN-2209", "Name withheld", "A-", "Voluntary · camp", "11 Oct 2026", "14.1", "crit", "TTI reactive on screening · unit discarded · donor called for confidential counselling"]
      ],
      tti: ["HIV 1 & 2", "HBsAg", "HCV", "Syphilis", "Malaria"],
      reactions: [
        ["TR-031", "Today 06:40", "Meenakshi P. · ICU-2", "PRBC · B+ · bag 26B-4412", "Febrile non-haemolytic (38.6 °C, +1.4)", "Transfusion stopped, bag returned, DAT negative, cultures sent", "Reported"],
        ["TR-030", "14 Oct 15:10", "Ramesh Gowda · A-01", "FFP · A+", "Allergic, mild urticaria", "Antihistamine, transfusion resumed slowly", "Reported"],
        ["TR-029", "09 Oct 22:20", "Lakshmi Devi · A-02", "PRBC · A+", "Suspected TACO (dyspnoea, SpO₂ 88%)", "Stopped, furosemide, CXR; root-cause: rate 250 mL/h in CHF", "Investigation open"]
      ]
    },
    cssd: {
      stages: [["decon", "Decontamination", "إزالة التلوث", "डीकंटैमिनेशन", "refresh"], ["pack", "Inspect & pack", "الفحص والتغليف", "जाँच और पैक", "box"], ["steril", "Sterilising", "التعقيم", "स्टरलाइज़ेशन", "thermo"], ["store", "Sterile store", "المخزن المعقم", "स्टराइल स्टोर", "shieldcheck"], ["issued", "Issued", "صُرف", "जारी", "send"]],
      sets: [
        ["LAP-03", "Laparotomy major set", "OT-1", "decon", "Received 10:05 · washer-disinfector cycle 2"], ["ORT-04", "Ortho basic set", "OT-2", "decon", "Received 09:50 · manual pre-clean"],
        ["CSR-02", "Caesarean set", "Labour room", "pack", "Count 46/46 · 1 scissor blunt → replaced"], ["DRS-11", "Dressing set × 12", "Ward A", "pack", "Packing · Class 4 indicator"],
        ["ENT-01", "Tonsillectomy set", "OT-3", "steril", "Autoclave 1 · load A1-0413"], ["LAP-01", "Laparoscopy instruments", "OT-1", "steril", "Plasma (H₂O₂) · load P-0102"], ["CVC-05", "Central line tray × 6", "ICU", "steril", "Autoclave 1 · load A1-0413"],
        ["ORT-01", "Ortho major set", "OT-2", "store", "Exp 15 Nov · load A1-0410"], ["SUT-08", "Suture set × 20", "ER", "store", "Exp 10 Nov · load A1-0409"], ["ORT-02", "Ortho basic set", "Store", "store", "QUARANTINED · load A2-0412", "q"],
        ["DRS-09", "Dressing set × 12", "Ward A", "issued", "Issued 16 Oct 18:30 · load A2-0412", "q"], ["CSR-01", "Caesarean set", "Labour room", "issued", "Issued 07:30 · load A1-0411"], ["SUT-07", "Suture set × 20", "ER", "issued", "Issued 08:00 · load A1-0411"]
      ],
      cycles: [
        { id: "A1-0413", unit: "Autoclave 1 · 134 °C prevacuum", start: "10:02", end: "10:58 (est.)", bd: "pass", ci: "pending", bi: "pending", op: "Lata M.", st: "running", items: 9 },
        { id: "P-0102", unit: "Plasma (H₂O₂) · low temperature", start: "09:40", end: "10:35 (est.)", bd: "na", ci: "pending", bi: "pending", op: "Lata M.", st: "running", items: 4 },
        { id: "A1-0411", unit: "Autoclave 1 · 134 °C prevacuum", start: "07:20", end: "08:16", bd: "pass", ci: "pass", bi: "pass", op: "Ravi S.", st: "released", items: 12 },
        { id: "ETO-0088", unit: "ETO · 55 °C", start: "16 Oct 20:00", end: "Aeration to 17 Oct 20:00", bd: "na", ci: "pass", bi: "pending", op: "Ravi S.", st: "aeration", items: 7 },
        { id: "A2-0412", unit: "Autoclave 2 · 134 °C prevacuum", start: "16 Oct 15:20", end: "16 Oct 16:14", bd: "pass", ci: "pass", bi: "fail", op: "Joseph K.", st: "recall", items: 8 }
      ],
      recall: [
        ["ORT-02", "Ortho basic set", "Sterile store", "Not used", "Quarantined 09:42", "ok"],
        ["LAP-02", "Laparoscopy accessory set", "Sterile store", "Not used", "Quarantined 09:42", "ok"],
        ["DRS-09", "Dressing set × 12", "Ward A", "10 unused, 2 used: A-05 Joseph Mathew 16 Oct 19:10, A-06 Shabana Begum 07:15", "10 retrieved · 2 patients flagged", "warn"],
        ["ORT-03", "Ortho major set", "OT-2", "Used 16 Oct 17:10 · TKR, George Thomas (case postponed to tomorrow) → not opened", "Retrieved 09:55", "ok"],
        ["SUT-06", "Suture set × 20", "ER", "18 unused, 2 used today 08:30 (Imran Qureshi · scalp)", "Retrieval in progress", "crit"]
      ]
    },
    diet: {
      orders: {
        "A-01": ["Soft, high-protein", "Regular texture · soft", null, "Veg", "Pneumonia · encourage oral fluids 2 L"], "A-02": ["Cardiac · 2 g sodium", "Regular", null, "Non-veg", "Fluid restriction 1.2 L/day"],
        "A-05": ["Regular", "Regular", null, "Veg · Jain", "No onion, garlic, root vegetables"], "A-06": ["Diabetic · 1,600 kcal", "Regular", null, "Non-veg", "Carb-counted · insulin with meals"],
        "P-01": ["Paediatric soft", "Soft & bite-sized", "Egg", "Veg", "Small frequent feeds · mother stays"], "ICU-1": ["Clear liquids → soft from 12:00", "Liquid (thin)", null, "Veg", "Post-op day 2 · advance as tolerated"],
        "ICU-2": ["Enteral feed · 1.2 kcal/mL", "NG tube 50 mL/h", null, "—", "Dietitian review 11:00 · residuals 4-hourly"]
      },
      extra: [["A-08", "Fatima Sheikh (pre-op)", "Nil by mouth from 06:00", "NBM", "Sulfonamides", "—", "Appendicectomy if CT confirms"]],
      rounds: [["Breakfast", "07:30", "done", "18/18 served · 2 refused (counselled)"], ["Mid-morning", "10:30", "live", "Tray trolley leaving kitchen 10:25"], ["Lunch", "12:30", "next", "Production 11:15 · 21 trays"], ["Evening snack", "16:00", "next", ""], ["Dinner", "19:00", "next", ""]],
      prod: [["Regular", 6], ["Diabetic", 4], ["Cardiac / low-salt", 3], ["Renal", 2], ["Soft / paediatric", 3], ["Liquid / enteral", 2], ["NBM", 1]]
    },
    stores: {
      indents: [
        { id: "IND-5521", dept: "OT complex", by: "Sr. Grace (OT in-charge)", at: "Today 08:10", urg: true, st: "approved", lines: [["Surgical gloves 7.0 (pairs)", 200, 200, 640], ["Polyglactin 2-0 suture", 48, 48, 30], ["Disposable drape set", 40, 40, 85], ["Electrosurgical pencil", 20, 20, 12]] },
        { id: "IND-5520", dept: "ICU", by: "Br. Anil (ICU)", at: "Today 07:45", urg: true, st: "partial", lines: [["Central line kit, triple lumen", 10, 10, 14], ["ET tube 7.5 mm", 20, 20, 6], ["ABG syringe", 100, 100, 240]] },
        { id: "IND-5518", dept: "Emergency", by: "Sr. Rekha Pillai", at: "Today 09:30", urg: false, st: "pending", lines: [["Cervical collar, adult", 10, 0, 22], ["SAM splint", 12, 0, 9], ["Trauma dressing 20 × 20 cm", 40, 0, 120]] },
        { id: "IND-5519", dept: "General Ward A", by: "Sr. Mary Joseph", at: "16 Oct 17:00", urg: false, st: "issued", lines: [["Linen set (sheet, draw sheet, pillow cover)", 60, 60, 310], ["IV cannula 20 G", 200, 200, 900], ["Urine bag 2 L", 50, 50, 160]] },
        { id: "IND-5517", dept: "Housekeeping", by: "Supervisor Rajan", at: "16 Oct 12:20", urg: false, st: "issued", lines: [["Sodium hypochlorite 5 L", 20, 20, 44]] }
      ],
      procure: [
        ["PR-2214", "ET tube 7.5 mm × 200", "ICU shortfall (IND-5520)", "Medline Healthcare", 26000, "pr", "Awaiting HOD approval"],
        ["PR-2210", "Surgical gloves (sizes 6.5–8) × 5,000 pairs", "Reorder level", "Kanam Latex", 112500, "po", "PO-7781 sent 15 Oct · delivery 18 Oct"],
        ["PR-2207", "Disposable drape sets × 400", "Reorder level", "3M Health Care", 184000, "grn", "GRN-4410 · 380 received, 20 short · debit note raised"],
        ["PR-2203", "Linen sets × 300", "Annual contract call-off", "Sri Lakshmi Textiles", 96000, "paid", "Invoice matched (3-way) · paid 12 Oct"]
      ],
      stock: [
        ["Surgical gloves 7.0 (pairs)", "Central store", 640, 800, 2200, "ok"], ["ET tube 7.5 mm", "Central store", 6, 30, 45, "crit"], ["Central line kit, triple lumen", "Central store", 14, 10, 18, "ok"],
        ["Cervical collar, adult", "Central store", 22, 10, 12, "ok"], ["SAM splint", "Central store", 9, 10, 14, "warn"], ["Trauma dressing 20 × 20 cm", "ER sub-store", 18, 25, 60, "warn"],
        ["Disposable drape set", "OT sub-store", 85, 60, 240, "ok"], ["IV cannula 20 G", "Ward A sub-store", 900, 300, 820, "over"], ["Sodium hypochlorite 5 L", "Central store", 44, 20, 38, "ok"], ["Hip prosthesis (consignment)", "OT implant store", 4, 3, 2, "ok"]
      ]
    },
    mrd: {
      ds: [
        ["IP-26-1188", "Joseph Mathew", "Ward A · A-05", "Today (planned)", "Dr. Vikram Shah", "draft", 0, "pending", "L03.1 Cellulitis of lower limb"],
        ["IP-26-1181", "Ramaswamy K.", "ICU-3 · died", "15 Oct 23:40", "Dr. Vikram Shah", "signed", 2, "query", "A41.9 Sepsis · J18.9 Pneumonia · N18.3 CKD"],
        ["IP-26-1176", "Arun Prakash", "Ward A · A-03", "15 Oct", "Dr. Vikram Shah", "pending", 2, "pending", "—"],
        ["IP-26-1172", "Baby of Shilpa Rao", "Maternity", "16 Oct", "Dr. Fatima Rahman", "signed", 1, "coded", "Z38.0 Singleton, born in hospital"],
        ["IP-26-1170", "Shilpa Rao", "Maternity", "16 Oct", "Dr. Fatima Rahman", "signed", 1, "coded", "O80 Spontaneous vaginal delivery"],
        ["IP-26-1166", "Mariam Joseph", "Paediatric · P-02", "14 Oct", "Dr. Nisha Bhat", "pending", 3, "pending", "—"]
      ],
      req: [
        { id: "MRQ-301", who: "Patient (self)", whoI: "user", name: "Rakesh Menon", pid: "p3", what: "Certified copy of full IPD file, Aug 2026 admission", consent: "Identity verified (OTP + ID)", fee: 500, due: "Mon 19 Oct", st: "preparing" },
        { id: "MRQ-300", who: "Insurer · Star Health (TPA MediAssist)", whoI: "shieldcheck", name: "Rakesh Menon", pid: "p3", what: "Discharge summary, bills, investigation reports for claim CL-55120", consent: "Patient consent on claim form", fee: 0, due: "Tue 20 Oct", st: "review" },
        { id: "MRQ-299", who: "Court · City Civil Court summons", whoI: "scale", name: "Imran Qureshi", what: "Certified copy of MLC-2026-0416 file and injury certificate", consent: "Court order attached · legal review", fee: 0, due: "Hearing 02 Nov", st: "legal" },
        { id: "MRQ-298", who: "Police · Whitefield PS (written request)", whoI: "shielduser", name: "Unknown male (Trauma 0417)", what: "Copy of MLC register entry and wound certificate", consent: "Official letter required · not yet received", fee: 0, due: "On receipt", st: "blocked" },
        { id: "MRQ-297", who: "Another hospital · Apollo Clinic, HSR", whoI: "building", name: "Rakesh Menon", pid: "p3", what: "Lab results and discharge summary (continuity of care)", consent: "Shared through Nabd consent C-2038 · patient and doctor approved", fee: 0, due: "Done", st: "released" },
        { id: "MRQ-296", who: "Relative · son (power of attorney)", whoI: "users", name: "Mohammed Ali", pid: "p8", what: "OPD records 2025–2026", consent: "Authorisation letter uploaded · patient has capacity → patient OTP requested", fee: 300, due: "Wed 21 Oct", st: "consent" }
      ],
      births: [
        ["B-2026-0311", "Baby of Shilpa Rao", "16 Oct 04:12", "M", "3.1 kg", "Live birth · vaginal", "Dr. Fatima Rahman", "submitted", "CRS reg. no. pending print"],
        ["B-2026-0310", "Baby of Ayesha Begum", "15 Oct 22:47", "F", "2.6 kg", "Live birth · LSCS", "Dr. Fatima Rahman", "due", "Due by 05 Nov (21 days)"],
        ["B-2026-0309", "Baby of Divya N.", "13 Oct 11:05", "F", "3.4 kg", "Live birth · vaginal", "Dr. Fatima Rahman", "registered", "Certificate issued to parents"],
        ["B-2026-0308", "Baby of Meera K.", "11 Oct 02:30", "M", "1.9 kg", "Live birth · preterm 34 wk", "Dr. Fatima Rahman", "registered", "NICU · certificate issued"]
      ],
      deaths: [
        ["D-2026-0089", "Brought dead · male ~50 y", "17 Oct 05:20", "Unknown (police case)", "—", "police", "Body to mortuary · post-mortem requested by police · no cause-of-death certificate"],
        ["D-2026-0088", "Ramaswamy K., 81 M", "15 Oct 23:40", "Ia Septic shock · Ib Pneumonia · II CKD stage 4", "Dr. Vikram Shah", "submitted", "Cause-of-death certificate issued · reported 16 Oct"],
        ["D-2026-0086", "Sarojini P., 77 F", "11 Oct 14:15", "Ia Acute MI · II Type 2 diabetes", "Dr. Khalid Al-Saeed", "registered", "Death certificate issued to family"]
      ]
    },
    roster: {
      days: [["Mon", "12"], ["Tue", "13"], ["Wed", "14"], ["Thu", "15"], ["Fri", "16"], ["Sat", "17"], ["Sun", "18"]], today: 5,
      depts: [["er", "Emergency"], ["icu", "ICU"], ["wa", "General Ward A"]],
      staff: [
        ["er", "Sr. Rekha Pillai", "RN · ER in-charge", ["M", "M", "M", "OFF", "M", "M", "E"]], ["er", "Br. Joel Mathew", "RN · ER", ["E", "E", "OFF", "N", "N", "M", "OFF"]],
        ["er", "Sr. Anjali Rao", "RN · ER", ["N", "N", "OFF", "E", "E", "M", "L"]], ["er", "Sr. Divya K.", "RN · ER", ["OFF", "M", "E", "E", "OFF", "E", "E"]],
        ["er", "Br. Faisal N.", "RN · ER", ["M", "OFF", "N", "N", "M", "N", "N"]], ["er", "Sr. Teena M.", "RN · ER", ["E", "E", "M", "M", "E", "E", "M"]],
        ["er", "Br. Akash P.", "RN · ER (float)", ["N", "OFF", "E", "M", "N", "N", "OFF"]],
        ["icu", "Br. Anil Varghese", "RN · ICU in-charge", ["M", "M", "M", "M", "OFF", "OFF", "M"]], ["icu", "Sr. Bindu S.", "RN · ICU", ["N", "N", "N", "OFF", "OFF", "M", "M"]],
        ["icu", "Sr. Nimmi J.", "RN · ICU", ["E", "E", "OFF", "N", "N", "N", "OFF"]], ["icu", "Br. Rahul T.", "RN · ICU", ["OFF", "M", "E", "E", "M", "E", "N"]],
        ["wa", "Sr. Mary Joseph", "RN · Ward A in-charge", ["M", "M", "M", "M", "M", "OFF", "OFF"]], ["wa", "Sr. Lissy A.", "RN · Ward A", ["E", "OFF", "E", "E", "N", "N", "N"]],
        ["wa", "Br. Vinod K.", "RN · Ward A", ["N", "N", "OFF", "OFF", "E", "M", "M"]], ["wa", "Sr. Priya L.", "RN · Ward A", ["OFF", "E", "N", "N", "OFF", "E", "E"]]
      ],
      need: { er: [2, 2, 2], icu: [1, 1, 1], wa: [1, 1, 1] },
      swaps: [
        { id: "SW-41", a: "Sr. Rekha Pillai", b: "Sr. Teena M.", give: "Sun 18 · Evening", take: "Mon 19 · Morning", reason: "Family function", ok: false, rule: "Sr. Teena would work Sun Morning then Sun Evening: 16 h continuous (limit 12 h)" },
        { id: "SW-40", a: "Sr. Divya K.", b: "Br. Akash P.", give: "Sun 18 · Evening", take: "Tue 20 · Morning", reason: "Exam", ok: true, rule: "Rest ≥ 11 h, skill mix and weekly hours all pass" }
      ],
      leave: [["Sr. Anjali Rao", "Sun 18 – Tue 20", "Annual leave", "approved"], ["Br. Rahul T.", "Wed 21", "Sick leave (certificate)", "approved"], ["Sr. Lissy A.", "26 – 30 Oct", "Annual leave", "pending"]],
      oncall: [["Emergency medicine", "Dr. Sameer Kulkarni", "In house until 20:00", "2201"], ["Cardiology (cath lab)", "Dr. Khalid Al-Saeed", "On call from home · 20 min", "2240"], ["Neurology (stroke)", "Dr. Arjun Hegde", "On call · tele-stroke", "2255"],
        ["General surgery", "Dr. Kavitha Rao", "In house", "2262"], ["Anaesthesia", "Dr. Rahul Bose", "In house", "2270"], ["Radiology", "Dr. Leena George", "In house until 17:00, then tele-radiology", "2290"], ["Paediatrics", "Dr. Nisha Bhat", "On call · 15 min", "2281"]]
    },
    fleet: {
      units: [
        { id: "AMB-07", kind: "ALS", st: "transport", where: "NH-75 near Hoodi junction → ER", crew: "Paramedic Suresh B., EMT Kiran V., driver Mahesh", o2: 70, fuel: 55, x: 300, y: 110 },
        { id: "AMB-03", kind: "ALS", st: "scene", where: "Brookefield, 2.8 km · fall from height", crew: "Paramedic Nisha R., EMT Ajay, driver Prakash", o2: 85, fuel: 62, x: 120, y: 200 },
        { id: "AMB-02", kind: "BLS", st: "available", where: "Hospital base, bay 2", crew: "EMT Rafiq, driver Sunil", o2: 100, fuel: 90, x: 210, y: 142 },
        { id: "AMB-06", kind: "Neonatal", st: "available", where: "Hospital base, bay 3", crew: "NICU nurse on call, driver Vasu", o2: 95, fuel: 80, x: 258, y: 142 },
        { id: "AMB-04", kind: "PTS", st: "transfer", where: "To Suvidha Clinic, Koramangala · discharge drop", crew: "Driver Anand, attendant", o2: 60, fuel: 40, x: 420, y: 230 },
        { id: "AMB-05", kind: "ALS", st: "maint", where: "Workshop · O₂ regulator & AC", crew: "—", o2: 0, fuel: 30, x: 60, y: 70 }
      ],
      log: [
        ["CALL-1186", "10:16", "AMB-03", "Fall from height, adult · Brookefield", "P1", ["10:17", "10:24", "—", "—"], "On scene · pre-alert expected"],
        ["CALL-1185", "09:58", "AMB-07", "RTA, motorcycle · NH-75", "P1", ["09:59", "10:03", "10:12", "—"], "Transporting · ETA 6 min"],
        ["CALL-1184", "09:20", "AMB-04", "Discharge drop · Koramangala", "P3", ["09:40", "—", "—", "—"], "Patient transport"],
        ["CALL-1183", "08:42", "AMB-02", "Stroke symptoms, 67 M · home", "P1", ["08:43", "08:52", "09:05", "10:02"], "Handed over in ER R3 (Mohammed Ali)"],
        ["CALL-1182", "06:55", "AMB-07", "Chest pain · Marathahalli", "P1", ["06:56", "07:08", "07:20", "07:41"], "Handed over · ER"]
      ],
      maint: [["AMB-05", "O₂ regulator leak & AC", "In workshop · back 18 Oct", "crit"], ["AMB-03", "Service at 60,000 km", "Due in 1,200 km", "warn"], ["AMB-07", "Defibrillator daily test", "Passed 07:00", "ok"],
        ["AMB-02", "Fitness certificate / permit", "Valid to 12 Jan 2027", "ok"], ["AMB-04", "Insurance renewal", "Due 31 Oct", "warn"], ["All", "Oxygen cylinders (D-type)", "14 full · 6 empty · refill order raised", "info"]]
    }
  };
  var D = NB.D.hospital;
  function erPt(id) { return D.er.pts.find(function (p) { return p.id === id; }); }

  /* =====================================================================
     EMERGENCY (role "er")
     ===================================================================== */
  var DISP = { admit: ["acc", "Admit", "إدخال", "भर्ती", "bed"], discharge: ["ok", "Discharge", "خروج", "डिस्चार्ज", "logout"], transfer: ["info", "Transfer", "تحويل", "ट्रांसफ़र", "arrowright"], lama: ["crit", "LAMA", "خروج ضد المشورة", "LAMA", "alertfill"] };
  function dispPill(d) { if (!d) return ui.pill("mute", L("Disposition open", "القرار مفتوح", "निर्णय बाक़ी"), "hourglass"); var x = DISP[d[0]]; return ui.pill(x[0], L(x[1], x[2], x[3]), x[4]); }
  function erDisp(p) { var o = NB.v("hsDispo", {})[p.id]; return o ? [o, "Set " + hhmm(NOW)] : p.disp; }
  function tidBar(m) { return '<div class="hs-tid ' + (m >= 240 ? "crit" : m >= 180 ? "warn" : "") + '"><i style="width:' + Math.min(100, m / 360 * 100) + '%"></i></div>'; }
  function short(dn) { return String(dn || "").replace(/^Dr\.\s*/, "Dr "); }

  function bayTile(p) {
    var t = since(p.arr), dp = erDisp(p), res = p.pend.filter(function (x) { return x[2] === "done"; }).length, pend = p.pend.filter(function (x) { return x[2] !== "done"; });
    return '<div class="hs-bay e' + p.esi + '" data-ov="erpatient" data-arg="' + p.id + '" role="button" tabindex="0">' +
      '<div class="row sb nw"><span class="hs-bn ltr">' + p.bay + "</span>" + esiPill(p.esi, true) + "</div>" +
      '<div class="hs-pn" dir="auto">' + esc(pnm(p)) + "</div><small dir=\"auto\">" + esc(p.age + " " + p.sex + " · " + p.cc) + "</small>" +
      '<div class="hs-meta"><span class="' + (t >= 240 ? "critc" : t >= 180 ? "warnc" : "") + '">' + ui.icon("clockh") + '<span class="num">' + dur(t) + "</span></span><span>" + ui.icon("steth") + esc(short(p.doc)) + "</span><span>" + ui.icon("heartpulse") + esc(p.nurse.replace(/^(Sr\.|Br\.)\s*/, "")) + "</span></div>" +
      '<div class="hs-chips">' + (p.path ? ui.pill("crit", p.path.split(" · ")[0], "bolt") : "") + (p.mlc ? ui.pill("sub", R().mlcShort, "scroll") : "") + (p.allergy ? ui.pill("crit", L("Allergy", "حساسية", "एलर्जी"), "alertfill") : "") +
      (pend.length ? ui.pill("warn", pend.length + " " + L("pending", "معلّق", "बाक़ी"), "hourglass") : "") + (res ? ui.pill("ok", res + " " + L("ready", "جاهز", "तैयार"), "checkcircle") : "") + "</div>" +
      '<div class="row sb nw">' + dispPill(dp) + "</div>" + tidBar(t) + "</div>";
  }
  function emptyTile(b) {
    var nt = D.er.bayNote[b];
    if (nt && nt[0] === "pre") return '<div class="hs-bay pre" data-go="er.ambulance" role="button" tabindex="0"><div class="row sb nw"><span class="hs-bn ltr">' + b + "</span>" + ui.pill("urg", L("Pre-allocated", "محجوز مسبقًا", "पूर्व-आवंटित"), "truck") + '</div><small dir="auto">' + esc(nt[1]) + "</small></div>";
    if (nt && nt[0] === "clean") return '<div class="hs-bay empty"><div class="row sb nw"><span class="hs-bn ltr">' + b + "</span>" + ui.pill("warn", L("Cleaning", "تنظيف", "सफ़ाई"), "sparkle") + '</div><small dir="auto">' + esc(nt[1]) + "</small></div>";
    return '<div class="hs-bay empty"><div class="row sb nw"><span class="hs-bn ltr">' + b + "</span>" + ui.pill("ok", L("Free", "متاح", "खाली"), "check") + '</div><small dir="auto">' + L("Ready for next patient", "جاهز للمريض التالي", "अगले मरीज़ के लिए तैयार") + "</small></div>";
  }
  function esiLegend() { return '<div class="hs-leg">' + [1, 2, 3, 4, 5].map(function (l) { return esiPill(l); }).join("") + "</div>"; }

  NB.screen("er.board", function (c) {
    var E = D.er, z = NB.v("hsZone", "all"), byBay = {};
    E.pts.forEach(function (p) { byBay[p.bay] = p; });
    var tids = E.pts.map(function (p) { return since(p.arr); }), longest = Math.max.apply(null, tids);
    var lp = E.pts[tids.indexOf(longest)];
    var boarding = E.pts.filter(function (p) { var d = erDisp(p); return d && d[0] === "admit"; }).length;
    var h = ui.header(L("ER board", "لوحة الطوارئ", "ईआर बोर्ड"), E.pts.length + " patients in department · " + E.waiting.length + " waiting for triage · " + E.amb.length + " ambulances inbound · live, updated " + hhmm(NOW),
      ui.btn(L("Ambulances", "الإسعاف", "एम्बुलेंस"), { go: "er.ambulance", icon: "truck" }) + ui.btn(L("Triage next", "فرز التالي", "अगला ट्राइएज"), { cls: "pri", go: "er.triage", icon: "alertfill" }));
    h += '<div class="grid g4 kpis">' +
      ui.kpi(n(E.pts.length + "/" + 16), L("Bays occupied", "الأسرّة المشغولة", "भरे बे"), "3 pre-allocated or cleaning", "faint") +
      ui.kpi(n("9 min"), L("Door-to-doctor (median today)", "من الباب إلى الطبيب", "डोर-टू-डॉक्टर"), ui.icon("checkcircle", "xs") + " Target ≤ 15 min", "okc") +
      ui.kpi(n(dur(longest)), L("Longest stay", "أطول إقامة", "सबसे लंबा ठहराव"), esc(lp.bay + " · " + pnm(lp)) + " · over 4 h target", "critc") +
      ui.kpi(n(boarding), L("Admitted, waiting for a bed", "بانتظار سرير", "बेड की प्रतीक्षा"), "ICU-3 contested · see Admissions", "warnc") + "</div>";
    h += ui.note("crit", "<b>" + L("Inbound", "قادم", "आ रहा है") + ":</b> AMB-07 trauma, ETA 6 min, GCS 12 · R1–R3 full → suggest <b>A2</b> as resus overflow once Arjun Nair moves to F3, or R3 when Mohammed Ali goes to CT. " +
      '<a href="#" data-go="er.ambulance">' + L("Open pre-arrival", "فتح ما قبل الوصول", "प्री-अराइवल खोलें") + "</a>");
    var counts = {}; E.zones.forEach(function (zn) { counts[zn[0]] = zn[4].filter(function (b) { return byBay[b]; }).length; });
    h += '<div class="row sb">' + ui.seg("hsZone", [["all", L("All zones", "كل المناطق", "सभी ज़ोन"), E.pts.length]].concat(E.zones.map(function (zn) { return [zn[0], L(zn[1], zn[2], zn[3]).split(" (")[0], counts[zn[0]]]; }))) + esiLegend() + "</div>";
    E.zones.forEach(function (zn) {
      if (z !== "all" && z !== zn[0]) return;
      var o = counts[zn[0]], tot = zn[4].length;
      h += ui.card("", '<div class="hs-zh"><h2 class="h">' + L(zn[1], zn[2], zn[3]) + '</h2><span class="small muted">' + n(o + "/" + tot) + " " + L("occupied", "مشغول", "भरे") + "</span>" + ui.bar(o / tot * 100, o / tot >= 1 ? "crit" : o / tot > 0.8 ? "warn" : "") + "</div>" +
        '<div class="hs-bays">' + zn[4].map(function (b) { return byBay[b] ? bayTile(byBay[b]) : emptyTile(b); }).join("") + "</div>");
    });
    var wt = ui.card(L("Waiting for triage", "بانتظار الفرز", "ट्राइएज की प्रतीक्षा"), '<div class="lst">' + E.waiting.map(function (w) {
      return ui.li(ui.av(w[0]), esc(w[0]) + ' <span class="small faint">' + esc(w[1]) + "</span>", esc(w[2]) + " · " + L("arrived", "وصل", "आए") + " " + n(w[3]) + " (" + dur(since(w[3])) + ")",
        ui.btn(L("Triage", "فرز", "ट्राइएज"), { cls: "sm pri", act: "hsgo", arg: "er.triage|hsTc|" + w[4], icon: "alertfill" }));
    }).join("") + "</div>", { icon: "hourglass", right: ui.pill("ok", L("Longest wait 6 min", "أطول انتظار ٦ دقائق", "सबसे लंबा इंतज़ार 6 मिनट"), "clockh") });
    var dispo = ui.card(L("Disposition & flow", "القرار والتدفق", "निर्णय और प्रवाह"), '<div class="lst">' +
      ["admit", "discharge", "transfer", "lama"].map(function (k) {
        var list = E.pts.filter(function (p) { var d = erDisp(p); return d && d[0] === k; });
        return ui.li(dispPill([k]), list.length + " " + (list.length === 1 ? L("patient", "مريض", "मरीज़") : L("patients", "مرضى", "मरीज़")), esc(list.map(function (p) { return p.bay + " " + pnm(p).split(" (")[0]; }).join(" · ") || "—"), "");
      }).join("") + ui.li(dispPill(null), E.pts.filter(function (p) { return !erDisp(p); }).length + " " + L("patients", "مرضى", "मरीज़"), L("Work-up in progress", "التقييم جارٍ", "जाँच जारी"), "") +
      "</div>" + '<div class="small faint" style="margin-top:8px">' + L("Today: 41 seen · 11 admitted (27%) · 1 left without being seen · 1 LAMA", "اليوم: ٤١ مريضًا", "आज: 41 मरीज़ देखे") + "</div>", { icon: "logout" });
    return h + '<div class="grid g2">' + wt + dispo + "</div>";
  });

  /* ---- ER patient drawer ---- */
  NB.overlay("erpatient", function (id) {
    var p = erPt(id); if (!p) return { title: id, body: "" };
    var po = pobj(p), t = since(p.arr), dsel = NB.v("hsDsel_" + id, (erDisp(p) || ["admit"])[0]);
    var body = '<div class="row nw">' + ui.av(pnm(p), "lg") + '<div class="fl"><h2 class="h" dir="auto">' + esc(pnm(p)) + '</h2><div class="small muted">' + esc(p.age + " " + p.sex) + " · " + (po.mrn ? esc(po.mrn) : p.temp ? L("Temporary ER ID", "معرّف طوارئ مؤقت", "अस्थायी ईआर आईडी") + " ER-0417" : "ER no. ER-26-" + (1180 + parseInt(p.id.slice(1), 10))) + " · " + L("Bay", "السرير", "बे") + " " + n(p.bay) + " · " + esc(p.mode) + "</div></div>" + esiPill(p.esi) + "</div>";
    if (p.allergy) body += ui.note("crit", "<b>" + L("Allergy", "حساسية", "एलर्जी") + ":</b> " + esc(p.allergy));
    if (p.temp) body += ui.note("warn", "Unidentified patient: temporary ID and wristband issued. Merge to the real record when identified (police and family informed).");
    if (p.mlc) body += ui.note("info", "<b>" + esc(R().mlc) + " " + esc(p.mlc) + "</b> · " + esc(R().police) + ". " + '<a href="#" data-act="hsgo" data-arg="er.mlc|hsMlc|' + p.mlc + '">' + L("Open register", "فتح السجل", "रजिस्टर खोलें") + "</a>");
    body += '<div class="hs-kv" style="grid-template-columns:auto minmax(0,1fr)"><dt>' + L("Complaint", "الشكوى", "शिकायत") + "</dt><dd dir=\"auto\">" + esc(p.cc) + "</dd><dt>" + L("Arrived", "الوصول", "आगमन") + "</dt><dd>" + n(p.arr) + " · " + n(dur(t)) + " " + L("in department", "في القسم", "विभाग में") + "</dd><dt>" + L("Team", "الفريق", "टीम") + "</dt><dd>" + esc(p.doc + " · " + p.nurse) + "</dd>" + (p.path ? "<dt>" + L("Pathway", "المسار", "पाथवे") + "</dt><dd>" + ui.pill("crit", p.path, "bolt") + "</dd>" : "") + "</div>";
    var v = p.vit;
    body += ui.card(L("Latest vitals", "آخر العلامات الحيوية", "ताज़ा वाइटल्स") + " · " + hhmm(NOW - 6), '<div class="hs-vit">' + [
      ["HR", v.hr, "/min", v.hr > 100], ["BP", v.sbp + "/" + v.dbp, "mmHg", v.sbp < 90], ["RR", v.rr, "/min", v.rr > 20], ["SpO₂", v.spo2 + "%", "", v.spo2 < 92],
      ["Temp", v.t.toFixed(1), "°C", v.t >= 38.3], ["GCS", v.gcs, "/15", v.gcs < 15], ["Pain", v.pain, "", false], ["ESI", p.esi, ESI[p.esi][0], false]
    ].map(function (x) { return '<div class="hs-v' + (x[3] ? " dz" : "") + '"><label>' + x[0] + '</label><b class="ltr">' + x[1] + "</b><small>" + (x[3] ? ui.icon("alertcircle", "xs") + " " : "") + esc(x[2] || "") + "</small></div>"; }).join("") + "</div>", { icon: "heartpulse" });
    body += ui.card(L("Orders & results", "الطلبات والنتائج", "ऑर्डर और रिज़ल्ट"), p.pend.length ? '<div class="hs-ck">' + p.pend.map(function (x) {
      return stRow(x[2] === "done" ? "ok" : "warn", esc(x[0]), x[2] === "done" ? L("Result ready · reviewed", "النتيجة جاهزة", "रिज़ल्ट तैयार") : L("Pending", "معلّق", "बाक़ी"), x[1] === "image" ? ui.btn(L("Imaging", "الأشعة", "इमेजिंग"), { cls: "sm ghost", go: "radiology.worklist", icon: "image" }) : "");
    }).join("") + "</div>" : '<div class="empty">' + L("No pending orders", "لا طلبات معلّقة", "कोई ऑर्डर बाक़ी नहीं") + "</div>", { icon: "clipboard" });
    // disposition
    var dbody = '<div class="seg" role="tablist">' + ["admit", "discharge", "transfer", "lama"].map(function (k) {
      return '<button class="' + (dsel === k ? "on" : "") + '" data-set="hsDsel_' + id + '" data-val="' + k + '">' + ui.icon(DISP[k][4], "sm") + L(DISP[k][1], DISP[k][2], DISP[k][3]) + "</button>"; }).join("") + "</div>";
    if (dsel === "admit") dbody += '<div class="fgrid" style="margin-top:12px">' + ui.field("Admit to", p.esi <= 2 ? "ICU" : "General Ward A", { id: "hs-adw", options: ["ICU", "CCU", "General Ward A", "Paediatric Ward", "Observation (ER)"] }) + ui.field("Admitting consultant", p.doc, { id: "hs-adc" }) + ui.field("Reason", p.cc, { id: "hs-adr", full: true }) + "</div>" +
      '<div class="small faint" style="margin-top:8px">' + L("Creates a bed request on the ward Admissions screen with ESI, isolation needs and payer.", "ينشئ طلب سرير", "बेड अनुरोध बनता है") + "</div>";
    else if (dsel === "discharge") dbody += '<div class="hs-ck" style="margin-top:8px">' + ckRow("hsDc1_" + id, true, "ER discharge summary signed", "Diagnosis, treatment given, results") + ckRow("hsDc2_" + id, false, "Prescription and return precautions", "Sent on WhatsApp in " + (po.lang === "hi" ? "Hindi" : po.lang === "ar" ? "Arabic" : "English")) + ckRow("hsDc3_" + id, false, "Follow-up booked", "OPD or teleconsult") + ckRow("hsDc4_" + id, false, "Bill settled or insurer notified", "") + "</div>";
    else if (dsel === "transfer") dbody += '<div class="fgrid" style="margin-top:12px">' + ui.field("Receiving facility", NB.facilities()[1].name === NB.fac().name ? NB.facilities()[0].name : NB.facilities()[1].name, { id: "hs-trf" }) + ui.field("Accepting doctor", "Dr. on call (accepted 10:18)", { id: "hs-tra" }) +
      ui.field("Reason", "Higher level of care / specialty not available", { id: "hs-trr", options: ["Higher level of care / specialty not available", "Patient / family request", "Insurance network", "Bed not available"] }) + ui.field("Ambulance", "AMB-02 (BLS) · available", { id: "hs-trv", options: ["AMB-02 (BLS) · available", "AMB-06 (Neonatal) · available", "External: " + R().ems.split(" ·")[0]] }) + "</div>" +
      ui.note("info", "Transfer note, vitals and results are shared with the receiving facility through a time-limited Nabd consent.");
    else dbody += ui.note("crit", "<b>Leaving against medical advice.</b> Explain the specific risks in the patient's language, confirm capacity, offer alternatives, and record who witnessed. The patient can return at any time.") +
      '<div class="hs-ck">' + ckRow("hsLm1_" + id, true, "Capacity assessed", "Alert, oriented, understands and can weigh the risks") + ckRow("hsLm2_" + id, false, "Specific risks explained", "Delayed bleed after head injury, intoxication") +
      ckRow("hsLm3_" + id, false, "LAMA form signed by patient", "Or refused to sign: two staff witness") + ckRow("hsLm4_" + id, false, "Witness signature", "Br. Joel Mathew") + ckRow("hsLm5_" + id, !!p.mlc, "Police informed (medico-legal case)", p.mlc ? esc(p.mlc) : "Not an MLC") + "</div>";
    body += ui.card(L("Disposition", "القرار", "निर्णय"), dbody, { icon: "logout", right: dispPill(erDisp(p)) });
    return { kind: "drawer", size: "lg", title: L("ER patient", "مريض الطوارئ", "ईआर मरीज़") + " · " + p.bay, body: body,
      foot: ui.btn(L("Order tests", "طلب فحوصات", "टेस्ट ऑर्डर"), { act: "toast", arg: "Order set opened for " + p.bay, icon: "flask" }) +
        ui.btn(L("Confirm disposition", "تأكيد القرار", "निर्णय पुष्टि करें"), { cls: dsel === "lama" ? "dng" : "pri", act: "hsdispo", arg: id + "|" + dsel, icon: DISP[dsel][4] }) };
  });
  NB.act("hsdispo", function (a) {
    var p = a.split("|"), m = Object.assign({}, NB.v("hsDispo", {})); m[p[0]] = p[1]; NB.state.ui.hsDispo = m; NB.state.overlay = null;
    var x = erPt(p[0]);
    NB.toast({ admit: "Bed request sent to ward for " + x.bay, discharge: x.bay + " discharged · summary sent on WhatsApp", transfer: "Transfer booked · receiving facility notified", lama: "LAMA recorded with witness · " + x.bay + " closed" }[p[1]]);
  });

  /* ---- er.triage ---- */
  var RF = [["Needs an immediate life-saving intervention", "Airway, breathing support, defibrillation, emergency drugs", 1], ["Unresponsive, or GCS 8 or less", "Pain-responsive only or unresponsive", 1],
    ["High-risk situation", "Possible ACS, stroke signs, suspected sepsis, ectopic, major trauma mechanism", 2], ["New confusion, lethargy or disorientation", "Acute change from baseline", 2], ["Severe pain or distress", "Pain 7/10 or more, or severe psychological distress", 2]];
  function triageCalc(cs, key) {
    var f = RF.map(function (r, i) { return chk("hsRf_" + key + "_" + i, cs.flags.indexOf(i) >= 0); });
    var res = NB.v("hsRes_" + key, cs.res), v = cs.vit;
    var dz = []; if (v.hr > 100) dz.push("HR " + v.hr); if (v.rr > 20) dz.push("RR " + v.rr); if (v.spo2 < 92) dz.push("SpO₂ " + v.spo2 + "%");
    var lvl, trail = [];
    if (f[0] || f[1]) { lvl = 1; trail.push(["A", "Requires immediate life-saving intervention", "yes"]); }
    else {
      trail.push(["A", "Requires immediate life-saving intervention", "no"]);
      if (f[2] || f[3] || f[4]) { lvl = 2; trail.push(["B", "High-risk, confused, or severe pain/distress", "yes"]); }
      else {
        trail.push(["B", "High-risk, confused, or severe pain/distress", "no"]);
        trail.push(["C", "Resources needed", res === "0" ? "none" : res === "1" ? "one" : "two or more"]);
        if (res === "0") lvl = 5; else if (res === "1") lvl = 4; else { lvl = 3; trail.push(["D", "Danger-zone vitals", dz.length ? "yes → consider ESI 2" : "no"]); if (dz.length) lvl = 2; }
      }
    }
    var q = (v.rr >= 22 ? 1 : 0) + (v.sbp <= 100 ? 1 : 0) + (v.gcs < 15 ? 1 : 0);
    return { lvl: lvl, trail: trail, dz: dz, q: q, f: f, res: res };
  }
  NB.screen("er.triage", function (c) {
    var key = NB.v("hsTc", "stroke"), cs = D.er.tcases[key], r = triageCalc(cs, key), v = cs.vit, nm = cs.pid ? NB.pname(NB.pat(cs.pid)) : cs.name;
    var h = ui.header(L("Rapid triage", "الفرز السريع", "त्वरित ट्राइएज"), "Emergency Severity Index (ESI v4) · target: triage within 10 min of arrival · scale is configurable (ESI, CTAS or MTS)",
      ui.btn(L("ER board", "لوحة الطوارئ", "ईआर बोर्ड"), { go: "er.board", icon: "activity" }));
    h += '<div class="row sb">' + ui.seg("hsTc", [["stroke", "Nasreen B. · speech"], ["sepsis", "Thomas V. · fever"], ["chest", "Abdullah A. · chest"], ["minor", "Kavya R. · cut finger"]], "stroke") +
      '<span class="small faint">' + L("Sample arrivals waiting for triage", "حالات نموذجية بانتظار الفرز", "ट्राइएज की प्रतीक्षा में नमूना मरीज़") + "</span></div>";
    var who = ui.card("", '<div class="row nw">' + ui.av(nm, "lg") + '<div class="fl"><h2 class="h" dir="auto">' + esc(nm) + ' <span class="small muted">' + cs.age + " " + cs.sex + '</span></h2><div class="small muted" dir="auto">' + esc(cs.cc) + '</div><div class="small faint">' + L("Arrived", "الوصول", "आगमन") + " " + n(cs.arr) + " · " + esc(cs.mode) + " · " + L("waiting", "ينتظر", "प्रतीक्षा") + " " + n(dur(since(cs.arr))) + "</div></div></div>" +
      (cs.pid && NB.pat(cs.pid).allergies && NB.pat(cs.pid).allergies.length ? ui.note("crit", "<b>Allergy on record:</b> " + esc(NB.pat(cs.pid).allergies.map(function (a) { return a.a + " (" + a.rx + ")"; }).join(", "))) : ""));
    var vit = ui.card(L("Vitals", "العلامات الحيوية", "वाइटल्स"), '<div class="hs-vit">' + [
      ["HR", v.hr, "/min", v.hr > 100], ["BP", v.sbp + "/" + v.dbp, "mmHg", v.sbp <= 100 || v.sbp >= 180], ["RR", v.rr, "/min", v.rr > 20], ["SpO₂", v.spo2 + "%", "room air", v.spo2 < 92],
      ["Temp", v.t.toFixed(1), "°C", v.t >= 38.3], ["GCS", v.gcs, "/15", v.gcs < 15], ["Pain", v.pain + "/10", "", v.pain >= 7], ["Glucose", v.glu, "mmol/L", v.glu < 4 || v.glu > 15]
    ].map(function (x) { return '<div class="hs-v' + (x[3] ? " dz" : "") + '"><label>' + x[0] + '</label><b class="ltr">' + x[1] + "</b><small>" + (x[3] ? ui.icon("alertcircle", "xs") + " " + L("Abnormal", "غير طبيعي", "असामान्य") : esc(x[2])) + "</small></div>"; }).join("") + "</div>" +
      '<div class="small faint" style="margin-top:10px">' + ui.icon("bolt", "xs") + " Captured from the bedside monitor at 10:19 · edit if manual</div>", { icon: "heartpulse" });
    var flags = ui.card(L("Red flags", "علامات الخطر", "रेड फ़्लैग"), '<div class="hs-ck">' + RF.map(function (x, i) { return ckRow("hsRf_" + key + "_" + i, cs.flags.indexOf(i) >= 0, x[0] + ' <span class="small faint">→ ESI ' + x[2] + "</span>", x[1]); }).join("") + "</div>" +
      '<div class="row sb" style="margin-top:12px"><span class="small muted">' + L("Expected resources (labs, imaging, IV fluids, specialist, procedure)", "الموارد المتوقعة", "अपेक्षित संसाधन") + "</span>" + ui.seg("hsRes_" + key, [["0", L("None", "لا شيء", "कोई नहीं")], ["1", L("One", "واحد", "एक")], ["2", L("Two or more", "اثنان أو أكثر", "दो या अधिक")]], cs.res) + "</div>", { icon: "alertfill" });
    // screeners
    var bf = ["Balance", "Eyes", "Face droop", "Arm weakness", "Speech"], bfPos = cs.befast.filter(Boolean).length;
    var scr = [];
    scr.push('<div class="hs-box ' + (bfPos ? "crit" : "") + '"><h3>' + ui.icon("brain", "sm") + "Stroke · BE-FAST</h3>" + '<div class="hs-chips">' + bf.map(function (x, i) { return cs.befast[i] ? ui.pill("crit", x, "alertfill") : ui.pill("mute", x, "check"); }).join("") + "</div>" +
      (bfPos ? '<div class="small">' + bfPos + " positive · onset 09:55 (" + dur(since("09:55")) + " ago) · within the thrombolysis window</div>" + ui.btn(L("Activate code stroke", "تفعيل رمز السكتة", "कोड स्ट्रोक सक्रिय करें"), { cls: "sm dng", act: "hspath", arg: "stroke", icon: "bolt" }) : '<div class="small muted">' + L("No stroke signs", "لا علامات سكتة", "स्ट्रोक के लक्षण नहीं") + "</div>") + "</div>");
    scr.push('<div class="hs-box ' + (cs.chest ? "crit" : "") + '"><h3>' + ui.icon("heartpulse", "sm") + "STEMI · chest pain</h3>" +
      (cs.chest ? '<div class="small">Chest pain with sweating, age over 30 → <b>12-lead ECG within 10 min</b> (due ' + hhmm(tm(cs.arr) + 10) + ")</div>" + '<div class="btns">' + ui.btn("ECG now", { cls: "sm dng", act: "toast", arg: "ECG requested · technician to triage room 1", icon: "activity" }) + ui.btn("Start STEMI pathway", { cls: "sm", act: "hspath", arg: "stemi", icon: "bolt" }) + "</div>" : '<div class="small muted">' + L("No chest pain", "لا ألم صدري", "सीने में दर्द नहीं") + "</div>") + "</div>");
    var sep = r.q >= 2 && cs.inf;
    scr.push('<div class="hs-box ' + (sep ? "crit" : r.q ? "warn" : "") + '"><h3>' + ui.icon("thermo", "sm") + "Sepsis · qSOFA " + n(r.q + "/3") + "</h3>" +
      '<div class="hs-chips">' + [["RR ≥ 22", v.rr >= 22], ["SBP ≤ 100", v.sbp <= 100], ["Altered mentation", v.gcs < 15], ["Suspected infection", cs.inf]].map(function (x) { return x[1] ? ui.pill("crit", x[0], "alertfill") : ui.pill("mute", x[0], "check"); }).join("") + "</div>" +
      (sep ? '<div class="small">qSOFA 2 or more with suspected infection → start the hour-1 bundle now (time zero ' + cs.arr + ")</div>" + ui.btn(L("Start sepsis bundle", "بدء حزمة الإنتان", "सेप्सिस बंडल शुरू करें"), { cls: "sm dng", act: "hspath", arg: "sepsis", icon: "bolt" }) : '<div class="small muted">' + L("Screen negative · re-screen if vitals change", "الفحص سلبي", "स्क्रीन नेगेटिव") + "</div>") + "</div>");
    var screeners = ui.card(L("Screeners · auto-suggested", "الفحوصات المقترحة تلقائيًا", "स्क्रीनर · स्वतः सुझाए गए"), '<div class="stack">' + scr.join("") + "</div>", { icon: "sparkle" });
    var res = ui.card(L("Triage result", "نتيجة الفرز", "ट्राइएज परिणाम"), '<div class="hs-esibig e' + r.lvl + '"><span class="n ltr">' + r.lvl + '</span><div class="fl"><b>ESI ' + r.lvl + " · " + L(ESI[r.lvl][0], ESI[r.lvl][1], ESI[r.lvl][2]) + "</b><small>" +
      ["", "Immediate: resuscitation bay, physician at bedside now", "Within 10 min: acute bay, physician alerted", "Within 30 min: acute bay", "Within 60 min: fast track", "Within 120 min: fast track or OPD referral"][r.lvl] + "</small></div></div>" +
      '<div class="hs-ck" style="margin-top:8px">' + r.trail.map(function (x) { return stRow(x[2].indexOf("yes") === 0 ? "warn" : "mute", "<span class=\"ltr\">" + x[0] + "</span> · " + x[1], esc(x[2])); }).join("") + "</div>" +
      (r.dz.length ? ui.note("warn", "<b>Danger-zone vitals:</b> " + esc(r.dz.join(", ")) + ".") : "") +
      '<div class="fgrid" style="margin-top:12px">' + ui.field(L("Assign bay", "تخصيص سرير", "बे असाइन करें"), r.lvl === 1 ? "R3" : r.lvl === 2 ? "A3 · 10:30" : r.lvl === 3 ? "O4" : "F3", { id: "hs-tbay", options: ["R3", "A3 · 10:30", "O4", "F3", "Waiting area"] }) +
      ui.field(L("Triage nurse", "ممرضة الفرز", "ट्राइएज नर्स"), "Sr. Teena M.", { id: "hs-tnur" }) + "</div>" +
      '<div class="btns" style="margin-top:12px">' + ui.btn(L("Save triage", "حفظ الفرز", "ट्राइएज सहेजें"), { cls: "pri", act: "toast", arg: "Triaged ESI " + r.lvl + " · bay assigned · ER doctor notified", icon: "check" }) + ui.btn(L("Re-triage", "إعادة الفرز", "पुनः ट्राइएज"), { cls: "ghost", act: "toast", arg: "Re-triage timer set for 30 min", icon: "clockh" }) + "</div>",
      { icon: "shieldcheck", cls: r.lvl <= 2 ? "crit" : "" });
    return h + '<div class="split wide"><div class="stack s16">' + who + vit + flags + '</div><div class="stack s16">' + res + screeners + "</div></div>";
  });
  NB.act("hspath", function (k) { NB.state.ui.hsPath = k; NB.go("er", "pathways"); NB.toast({ stroke: "Code stroke called · stroke team, CT and pharmacy paged", stemi: "STEMI pathway started · cath lab team paged", sepsis: "Sepsis bundle started · time zero recorded" }[k]); });

  /* ---- er.pathways ---- */
  function pathClock(pw) {
    var el = since(pw.door), pct = Math.min(100, el / pw.target * 100), cls = el > pw.target ? "crit" : pct >= 75 ? "warn" : "ok";
    return '<div class="hs-clock ' + cls + '" style="--p:' + pct.toFixed(1) + '%"><div><b class="ltr">' + el + '</b><small>' + L("of", "من", "में से") + " " + pw.target + " min</small></div></div>";
  }
  NB.screen("er.pathways", function (c) {
    var sel = NB.v("hsPath", "all");
    var h = ui.header(L("Time-critical pathways", "المسارات الحرجة زمنيًا", "समय-संवेदी पाथवे"), "Live clocks from door time · each step is time-stamped by the person who does it · breaches are reviewed weekly");
    h += '<div class="row sb">' + ui.seg("hsPath", [["all", L("All active", "كل النشطة", "सभी सक्रिय"), 3], ["stemi", "STEMI"], ["stroke", L("Stroke", "السكتة", "स्ट्रोक")], ["sepsis", L("Sepsis", "الإنتان", "सेप्सिस")]], "all") +
      '<span class="small faint">' + L("Targets are examples from international guidance; each hospital sets its own.", "الأهداف أمثلة", "लक्ष्य उदाहरण हैं") + "</span></div>";
    var cards = D.er.paths.filter(function (pw) { return sel === "all" || sel === pw.id; }).map(function (pw) {
      var p = erPt(pw.pt), d0 = tm(pw.door), doneN = 0;
      var steps = pw.steps.map(function (s, i) {
        var k = "hsPs_" + pw.id + "_" + i, done = s[1] || NB.v(k, false), when = s[1] || (NB.v(k, false) ? hhmm(NOW) : null), due = d0 + s[2];
        if (done) doneN++;
        var tmCls = done ? "" : NOW > due ? "over" : due - NOW <= 10 ? "due" : "";
        var right = '<span class="hs-tm ' + tmCls + '">' + (done ? n(when) + (tm(when) - d0 < 0 ? " · " + L("pre-arrival", "قبل الوصول", "आगमन से पहले") : " · +" + (tm(when) - d0) + " min") : (NOW > due ? L("Overdue", "متأخر", "देर") + " " : L("Due", "مستحق", "देय") + " ") + n(hhmm(due))) + "</span>";
        if (s[1]) return '<div class="hs-ci"><span class="hs-cb on">' + ui.icon("check") + '</span><div class="t"><b>' + esc(s[0]) + "</b>" + (s[3] ? "<small>" + esc(s[3]) + "</small>" : "") + "</div>" + right + "</div>";
        return ckRow(k, false, esc(s[0]), esc(s[3]), right);
      }).join("");
      var el = since(pw.door);
      return ui.card("", '<div class="hs-pw">' + pathClock(pw) + '<div class="fl"><div class="eyebrow">' + ui.icon(pw.icon, "sm") + L(pw.t[0], pw.t[1], pw.t[2]) + '</div><h2 class="h" dir="auto" style="margin-top:4px">' + esc(pnm(p)) + ' <span class="small muted">' + esc(p.age + " " + p.sex) + " · " + p.bay + "</span></h2>" +
        '<div class="small muted">' + L("Door", "الباب", "डोर") + " " + n(pw.door) + " · " + esc(pw.tLbl) + "</div>" +
        '<div class="row nw" style="margin-top:8px"><div class="fl">' + ui.bar(doneN / pw.steps.length * 100, el > pw.target ? "crit" : "") + '</div><span class="small muted">' + n(doneN + "/" + pw.steps.length) + " " + L("steps", "خطوات", "चरण") + "</span></div></div></div>" +
        '<div class="hs-ck" style="margin-top:12px">' + steps + "</div>" +
        '<div class="btns" style="margin-top:10px">' + ui.btn(L("Patient", "المريض", "मरीज़"), { cls: "sm", ov: "erpatient", arg: p.id, icon: "user" }) + ui.btn(L("Page team", "استدعاء الفريق", "टीम को पेज करें"), { cls: "sm", act: "toast", arg: "Team paged for " + pw.id.toUpperCase() + " pathway", icon: "bell" }) + "</div>", { cls: el > pw.target * 0.75 ? "warnb" : "" });
    });
    var perf = ui.card(L("Last 30 days", "آخر ٣٠ يومًا", "पिछले 30 दिन"), ui.table([{ h: "Pathway" }, { h: "Cases", cls: "r" }, { h: "Median", cls: "r" }, { h: "Within target", cls: "r" }], [
      ["Door-to-ECG (chest pain)", n(84), n("7 min"), ui.pill("ok", "92%")], ["Door-to-balloon (STEMI)", n(9), n("78 min"), ui.pill("warn", "78%", "alertcircle")],
      ["Door-to-needle (stroke)", n(6), n("52 min"), ui.pill("warn", "67%", "alertcircle")], ["Sepsis antibiotics ≤ 1 h", n(31), n("48 min"), ui.pill("ok", "84%")]], { min: 460 }), { icon: "chart" });
    return h + '<div class="grid ' + (sel === "all" ? "g3" : "g2") + '">' + cards.join("") + (sel === "all" ? "" : perf) + "</div>" + (sel === "all" ? perf : "");
  });

  /* ---- er.ambulance ---- */
  NB.screen("er.ambulance", function (c) {
    var h = ui.header(L("Ambulance & pre-arrival", "الإسعاف وما قبل الوصول", "एम्बुलेंस और प्री-अराइवल"), "Pre-alerts from our fleet, " + esc(R().ems) + " and transfers in · ATMIST handover · bays held before the door opens",
      ui.btn(L("Fleet", "الأسطول", "बेड़ा"), { go: "hospops.fleet", icon: "truck" }) + ui.btn(L("Log phone pre-alert", "تسجيل إنذار هاتفي", "फ़ोन प्री-अलर्ट"), { cls: "pri", ov: "dispatch", arg: "prealert", icon: "phone" }));
    var bays = ["A2", "A3", "A5", "O3", "O4", "R3 (after CT)"];
    h += D.er.amb.map(function (a) {
      var ack = isDone("amb_" + a.id), bay = NB.v("hsAmbBay_" + a.id, a.bay);
      var srcP = a.src === "own" ? ui.pill("acc", L("Own fleet", "أسطولنا", "अपना बेड़ा"), "truck") : a.src === "ems" ? ui.pill("info", R().ems.split(" ·")[0].split(" (")[0] + " EMS", "phone") : ui.pill("sync", L("Transfer in", "تحويل وارد", "ट्रांसफ़र इन"), "arrowright");
      var vt = ui.table([{ h: "Time" }, { h: "HR", cls: "r" }, { h: "BP", cls: "r" }, { h: "RR", cls: "r" }, { h: "SpO₂", cls: "r" }, { h: "GCS", cls: "r" }], a.vit.map(function (v) {
        return [n(v[0]), '<span class="num' + (v[1] > 110 ? " critc" : "") + '">' + v[1] + "</span>", n(v[2]), '<span class="num' + (v[3] > 22 ? " warnc" : "") + '">' + v[3] + "</span>", '<span class="num' + (v[4] < 94 ? " warnc" : "") + '">' + v[4] + "%</span>", '<span class="num' + (v[5] < 13 ? " warnc" : "") + '">' + v[5] + "</span>"];
      }), { min: 380 });
      return ui.card("", '<div class="row nw" style="align-items:flex-start"><div class="hs-eta' + (a.eta > 15 ? " info" : "") + '"><b class="ltr">' + a.eta + "</b><small>" + L("min ETA", "دقيقة", "मिनट") + '</small></div><div class="fl"><div class="row"><h2 class="h ltr">' + esc(a.src === "ems" ? MV({ india: "108-KA-4412", uae: "DCAS-4412", ksa: "SRCA-4412", qatar: "HMC-4412" }) : a.id) + "</h2>" + srcP + ui.pill("mute", a.kind, "truck") + (ack ? ui.pill("ok", L("Acknowledged", "تم الاستلام", "स्वीकृत"), "checkcircle") : ui.pill("urg", L("New", "جديد", "नया"), "bell")) + "</div>" +
        '<div class="small muted" style="margin-top:4px" dir="auto">' + esc(a.call) + '</div><div class="small faint">' + ui.icon("users", "xs") + " " + esc(a.crew) + "</div></div></div>" +
        '<div class="grid g2" style="margin-top:14px"><div class="stack s8"><div class="eyebrow">ATMIST ' + L("handover", "التسليم", "हैंडओवर") + '</div><dl class="hs-atm">' + a.atm.map(function (x) { return "<dt class=\"ltr\">" + x[0] + "</dt><dd dir=\"auto\">" + esc(x[1]) + "</dd>"; }).join("") + "</dl></div>" +
        '<div class="stack s8"><div class="eyebrow">' + L("Crew vitals", "علامات الطاقم", "क्रू वाइटल्स") + "</div>" + vt + '<div class="eyebrow" style="margin-top:6px">' + L("Pre-allocate bay", "تخصيص سرير مسبق", "बे पूर्व-आवंटित करें") + "</div>" + ui.seg("hsAmbBay_" + a.id, bays.map(function (b) { return [b, b]; }), a.bay) + "</div></div>" +
        '<div class="btns" style="margin-top:14px">' + (ack ? "" : ui.btn(L("Acknowledge & hold bay", "استلام وحجز السرير", "स्वीकार करें और बे रोकें"), { cls: "pri sm", act: "hsdone", arg: "amb_" + a.id + "|" + a.id + " acknowledged · bay " + bay + " held · crew sees it on their tablet", icon: "check" })) +
        ui.btn(L("Pre-alert", "إنذار مسبق", "प्री-अलर्ट") + " " + a.team, { cls: "sm", act: "toast", arg: a.team + " paged to bay " + bay + " for " + a.id, icon: "bell" }) +
        (a.id === "AMB-07" ? ui.btn(L("Activate MTP", "تفعيل نقل الدم الشامل", "MTP सक्रिय करें"), { cls: "sm dng", act: "toast", arg: "Massive transfusion pack 1 requested from blood bank", icon: "heartpulse" }) : "") + "</div>", { cls: a.eta <= 10 ? "crit" : "" });
    }).join("");
    h += exampleNote("Emergency numbers shown for this market: " + esc(R().ems) + ".");
    return h;
  });

  /* ---- er.mlc ---- */
  var CERT = { draft: ["warn", "Draft", "pen"], pending: ["urg", "To issue", "hourglass"], issued: ["ok", "Issued", "checkcircle"] };
  NB.screen("er.mlc", function (c) {
    var sel = NB.v("hsMlc", D.er.mlc[0].no), rows = D.er.mlc.map(function (m) {
      var p = m.er ? erPt(m.er) : null, nm = p ? pnm(p) : m.name;
      var pol = m.pol[0] === "done" || isDone("pol_" + m.no) ? ui.pill("ok", L("Informed", "تم الإبلاغ", "सूचित"), "checkcircle") : ui.pill("crit", L("Entry no. awaited", "بانتظار الرقم", "एंट्री नंबर बाक़ी"), "alertfill");
      var ct = CERT[isDone("cert_" + m.no) ? "issued" : m.cert];
      return { cells: ['<span class="tok">' + m.no + "</span>", ui.cellp(esc(nm), esc((p ? p.age + " " + p.sex : m.age) + " · " + m.type)), esc(m.when), pol, ui.pill(ct[0], ct[1], ct[2]), m.custody.length ? n(m.custody.length) + " " + (m.custody.length === 1 ? L("item", "عنصر", "आइटम") : L("items", "عناصر", "आइटम")) : '<span class="faint">—</span>'], ov: "mlcdetail", arg: m.no, cls: sel === m.no ? "sel" : "" };
    });
    var h = ui.header(esc(R().mlc) + " " + L("register", "السجل", "रजिस्टर"), "Police intimation, consent, injury certificates and chain of custody in one place · every entry is time-stamped and tamper-evident",
      ui.btn(L("Print register", "طباعة السجل", "रजिस्टर प्रिंट"), { act: "toast", arg: "Register for October exported (PDF, signed)", icon: "printer" }) + ui.btn(L("New entry", "قيد جديد", "नई एंट्री"), { cls: "pri", act: "toast", arg: "New entry: pick the ER patient first", icon: "plus" }));
    h += '<div class="grid g4 kpis">' + ui.kpi(n(3), L("Open today", "مفتوحة اليوم", "आज खुले"), "2 in department", "faint") + ui.kpi(n(1), L("Police entry no. awaited", "بانتظار رقم الشرطة", "पुलिस एंट्री बाक़ी"), "MLC-2026-0417 · chase at 10:42", "critc") +
      ui.kpi(n(2), L("Certificates to issue", "شهادات للإصدار", "प्रमाणपत्र बाक़ी"), "Within 24 h of examination (policy)", "warnc") + ui.kpi(n(3), L("Sealed items held", "عناصر مختومة", "सील आइटम"), "Locked cupboard ER-2 · key log", "faint") + "</div>";
    h += ui.table([{ h: "No." }, { h: "Patient" }, { h: "When" }, { h: R().police }, { h: "Injury certificate" }, { h: "Custody" }], rows, { min: 820 });
    h += '<div class="grid g2">' + ui.card(L("Rules built in", "قواعد مدمجة", "अंतर्निहित नियम"), '<div class="hs-ck">' +
      stRow("ok", "Emergency care never waits for police or paperwork", "The register runs alongside treatment") + stRow("ok", "Unconscious patients: implied consent recorded by two doctors", "Converted to informed consent when the patient regains capacity") +
      stRow("ok", "Sealed samples carry seal numbers and a signed handover", "Every hand-to-hand transfer is logged with name, badge, time") + stRow("info", "Brought-dead and suspicious deaths: body to mortuary, post-mortem on police requisition", "Cause-of-death certificate is not issued by the hospital") + "</div>", { icon: "shieldcheck" }) +
      ui.card(L("Typical MLC triggers", "الحالات الشائعة", "सामान्य एमएलसी"), '<div class="chips">' + ["Road traffic collision", "Assault", "Burns", "Poisoning", "Fall from height", "Suspected abuse", "Unknown / unconscious", "Firearm or stab injury", "Sexual assault (dedicated protocol)", "Brought dead"].map(function (x) { return ui.pill("mute", x, "dot"); }).join("") + "</div>", { icon: "scroll" }) + "</div>";
    h += exampleNote();
    return h;
  });
  NB.overlay("mlcdetail", function (no) {
    var m = D.er.mlc.find(function (x) { return x.no === no; }); if (!m) return { title: no, body: "" };
    var p = m.er ? erPt(m.er) : null, nm = p ? pnm(p) : m.name, polDone = m.pol[0] === "done" || isDone("pol_" + no), ct = CERT[isDone("cert_" + no) ? "issued" : m.cert];
    var body = '<div class="row nw">' + ui.av(nm, "lg") + '<div class="fl"><h2 class="h" dir="auto">' + esc(nm) + '</h2><div class="small muted">' + esc((p ? p.age + " " + p.sex : m.age) + " · " + m.type + " · " + m.when + " · " + m.brought) + "</div></div>" + ui.pill("sub", no, "scroll") + "</div>";
    body += ui.card(esc(R().police), '<div class="hs-ck">' + stRow(polDone ? "ok" : "crit", esc(m.pol[1]), polDone ? "Recorded" : "Call again at 10:42 if no entry number · escalate to duty officer") + "</div>" +
      (polDone ? "" : '<div class="fgrid" style="margin-top:10px">' + ui.field("Station diary / entry no.", "", { id: "hs-gd", ph: "e.g. 121/2026" }) + ui.field("Officer & badge", "", { id: "hs-off", ph: "Name, badge" }) + "</div>"), { icon: "shielduser", right: polDone ? ui.pill("ok", "Done") : ui.pill("crit", "Pending", "alertfill") });
    body += ui.card(L("Consent", "الموافقة", "सहमति"), '<div class="small" dir="auto">' + esc(m.consent) + "</div>", { icon: "pen" });
    body += ui.card(L("Injury certificate", "شهادة الإصابة", "चोट प्रमाणपत्र"), '<dl class="hs-kv"><dt>Nature of injuries</dt><dd>' + (m.type === "Assault" ? "Lacerated wound 4 × 0.5 cm, left parietal scalp, bone deep: no · CT head normal" : "As per examination notes") + "</dd><dt>Opinion</dt><dd>" + (m.type === "Assault" ? "Simple · caused by blunt object · age of injury about 2 h" : "Pending investigations") + "</dd><dt>Examined by</dt><dd>" + esc(p ? p.doc : "Duty doctor") + "</dd></dl>",
      { icon: "file", right: ui.pill(ct[0], ct[1], ct[2]) });
    body += ui.card(L("Chain of custody", "سلسلة الحيازة", "कस्टडी चेन"), m.custody.length ? '<div class="tl">' + m.custody.map(function (x) {
      var handed = /signed/.test(x[2]); return '<div class="ev"><span class="dotc ' + (handed ? "ok" : "warn") + '">' + ui.icon(handed ? "check" : "lock", "sm") + '</span><div class="bd"><b>' + esc(x[0]) + "</b><small>" + esc(x[1]) + " · " + esc(x[2]) + "</small></div></div>"; }).join("") + "</div>" : '<div class="empty">No items held</div>', { icon: "lock" });
    return { kind: "drawer", size: "lg", title: esc(R().mlcShort) + " · " + no, body: body,
      foot: (polDone ? "" : ui.btn("Save police details", { act: "hsdone", arg: "pol_" + no + "|Police intimation recorded for " + no, icon: "check" })) + ui.btn("Hand over sealed item", { act: "toast", arg: "Handover form: scan seal, officer signs on tablet", icon: "lock" }) +
        (ct[1] === "Issued" ? ui.btn("Print certificate", { act: "toast", arg: "Certificate printed with QR verification", icon: "printer" }) : ui.btn("Sign & issue certificate", { cls: "pri", act: "hsdone", arg: "cert_" + no + "|Injury certificate signed and issued for " + no, icon: "pen" })) };
  });

  /* =====================================================================
     RADIOLOGY (role "radiology")
     ===================================================================== */
  var MOD = { XR: ["X-ray", "أشعة سينية", "एक्स-रे"], CT: ["CT", "مقطعية", "सीटी"], MRI: ["MRI", "رنين", "एमआरआई"], USG: ["Ultrasound", "موجات صوتية", "अल्ट्रासाउंड"] };
  var PRIO = { stat: ["crit", "STAT", "alertfill", 0], urgent: ["urg", "Urgent", "bolt", 1], routine: ["mute", "Routine", "calendar", 2] };
  var RST = { ordered: ["mute", "Ordered", "list"], scheduled: ["info", "Scheduled", "calendar"], arrived: ["acc", "Arrived", "arrivals"], scanning: ["sync", "Scanning", "camera"], acquired: ["warn", "To report", "image"], reported: ["ok", "Reported", "checkcircle"] };
  function rOrd(id) { return D.rad.orders.find(function (o) { return o.id === id; }); }
  function rSt(o) { var s = NB.v("hsRst", {})[o.id] || o.st; return s; }
  function radChecks(o) {
    var p = pobj(o), out = [];
    if (o.contrast !== "none") {
      var cname = o.contrast === "gadolinium" ? "gadolinium" : "iodinated contrast";
      if (o.egfr == null) out.push(["warn", "eGFR unknown", "flask", "No creatinine on record. " + (o.prio === "stat" ? "Life-threatening: proceed, send creatinine with the scan." : "Request creatinine before " + cname + ".")]);
      else if (o.egfr < 30) out.push(["crit", "eGFR " + o.egfr, "alertfill", "Below 30: radiologist approval needed for " + cname + "."]);
      else if (o.egfr < 45) out.push(["warn", "eGFR " + o.egfr + " · caution", "alertcircle", "30–44: hydrate, lowest diagnostic dose, hold metformin per policy, repeat creatinine at 48 h."]);
      else out.push(["ok", "eGFR " + o.egfr, "checkcircle", "Renal function adequate for " + cname + "."]);
      var al = (p.allergies || []).filter(function (a) { return /contrast|iod|gado/i.test(a.a); });
      out.push(al.length ? ["crit", "Contrast allergy", "alertfill", al[0].a + " · premedication protocol"] : ["ok", "No contrast allergy", "checkcircle", "Allergy list checked" + ((p.allergies || []).length ? " (" + p.allergies.map(function (a) { return a.a; }).join(", ") + ": not contrast-related)" : "")]);
    }
    if (o.preg === "pending") out.push(["warn", "β-hCG pending", "alertcircle", "Female of reproductive age: urine β-hCG before ionising radiation or contrast."]);
    if (o.preg === "neg") out.push(["ok", "β-hCG negative", "checkcircle", "Checked today 08:30."]);
    if (o.preg === "pregnant") out.push(["info", "Pregnant · 14 wk", "baby", "Obstetric protocol."]);
    if (o.obst && R().pcpndt) { var f = isDone("pcp_" + o.id); out.push([f ? "ok" : "sub", "PCPNDT Form F", "scroll", f ? "Form F complete and signed" : "Form F required before the scan (India)"]); }
    if (o.mri) out.push([/pending/.test(o.mri) ? "warn" : "ok", "MRI safety", "shieldcheck", o.mri]);
    if (o.mod !== "USG" && o.mod !== "MRI" && pobj(o).age < 16) out.push(["info", "Paediatric dose", "user", "Child-size protocol (ALARA)."]);
    if (!out.length) out.push(["mute", "No checks needed", "check", ""]);
    return out;
  }
  function chkPills(o) { return '<div class="hs-chips">' + radChecks(o).map(function (c) { return ui.pill(c[0], c[1], c[2]); }).join("") + "</div>"; }

  NB.screen("radiology.worklist", function (c) {
    var f = NB.v("hsMod", "all"), sf = NB.v("hsRstF", "open");
    var all = D.rad.orders.slice().sort(function (a, b) { return PRIO[a.prio][3] - PRIO[b.prio][3]; });
    var cnt = function (m) { return all.filter(function (o) { return m === "all" || o.mod === m; }).length; };
    var list = all.filter(function (o) { return (f === "all" || o.mod === f) && (sf === "all" || (sf === "open" ? rSt(o) !== "reported" : rSt(o) === "acquired")); });
    var h = ui.header(L("Imaging worklist", "قائمة الأشعة", "इमेजिंग वर्कलिस्ट"), all.length + " orders today · STAT first · safety checks run when the order is placed · " + esc(R().rad),
      ui.btn(L("Modality schedule", "جدول الأجهزة", "मोडैलिटी शेड्यूल"), { go: "radiology.schedule", icon: "calendar" }) + ui.btn(L("Report next", "التقرير التالي", "अगली रिपोर्ट"), { cls: "pri", act: "hsgo", arg: "radiology.report|hsRep|RAD-3101", icon: "edit" }));
    h += '<div class="grid g4 kpis">' + ui.kpi(n(all.filter(function (o) { return o.prio === "stat" && rSt(o) !== "reported"; }).length), L("STAT open", "عاجل مفتوح", "STAT खुले"), "Code stroke CTA + trauma CT", "critc") +
      ui.kpi(n(all.filter(function (o) { return rSt(o) === "acquired"; }).length), L("Waiting to be reported", "بانتظار التقرير", "रिपोर्ट की प्रतीक्षा"), "Oldest 2 h 50 min (ward CXR)", "warnc") +
      ui.kpi(n("34 min"), L("ER CT order-to-report (median)", "زمن تقرير الطوارئ", "ईआर सीटी TAT"), ui.icon("checkcircle", "xs") + " Target ≤ 60 min", "okc") + ui.kpi(n(3), L("Critical findings today", "نتائج حرجة اليوم", "आज क्रिटिकल"), "1 call still open", "critc") + "</div>";
    h += '<div class="row sb">' + ui.seg("hsMod", [["all", L("All", "الكل", "सभी"), cnt("all")], ["XR", L(MOD.XR[0], MOD.XR[1], MOD.XR[2]), cnt("XR")], ["CT", "CT", cnt("CT")], ["MRI", "MRI", cnt("MRI")], ["USG", L("USG", "سونار", "यूएसजी"), cnt("USG")]]) +
      ui.seg("hsRstF", [["open", L("Open", "مفتوح", "खुले")], ["rep", L("To report", "للتقرير", "रिपोर्ट हेतु")], ["all", L("All", "الكل", "सभी")]], "open") + "</div>";
    var rows = list.map(function (o) {
      var pr = PRIO[o.prio], s = RST[rSt(o)], p = pobj(o);
      return { cells: [ui.pill(pr[0], pr[1], pr[2]), ui.cellp('<span dir="auto">' + esc(pnm(o)) + "</span>", esc((p.age || o.age) + " " + (p.sex || o.sex) + " · " + o.src)),
        ui.cellp(esc(o.study), esc(L(MOD[o.mod][0], MOD[o.mod][1], MOD[o.mod][2]) + " · " + o.ref)), n(o.at) + (o.slot ? '<div class="small faint">' + esc(o.slot) + "</div>" : ""), ui.pill(s[0], s[1], s[2]), chkPills(o)], ov: "imgorder", arg: o.id, cls: o.prio === "stat" ? "crit" : o.prio === "urgent" ? "urg" : "" };
    });
    h += ui.table([{ h: "Priority" }, { h: "Patient" }, { h: "Study" }, { h: "Ordered" }, { h: "Status" }, { h: "Safety checks" }], rows, { min: 1000, empty: "No orders match" });
    if (R().pcpndt) h += ui.note("warn", "<b>PCPNDT (India):</b> obstetric ultrasound runs only on machines registered under the Act (USG-2), needs a completed Form F for every scan, and the sex of the foetus is never recorded or disclosed. Forms are filed in the monthly report to the Appropriate Authority (example workflow).");
    else h += ui.note("info", "Radiation safety for " + esc(NB.mkt().name) + ": " + esc(R().rad) + " (example). Dose records are kept per study for audit.");
    return h;
  });
  NB.overlay("imgorder", function (id) {
    var o = rOrd(id); if (!o) return { title: id, body: "" };
    var p = pobj(o), pr = PRIO[o.prio], s = RST[rSt(o)];
    var body = '<div class="row nw">' + ui.av(pnm(o), "lg") + '<div class="fl"><h2 class="h" dir="auto">' + esc(pnm(o)) + '</h2><div class="small muted">' + esc((p.age || o.age) + " " + (p.sex || o.sex) + (p.mrn ? " · " + p.mrn : "") + " · " + o.src) + '</div><div class="chips" style="margin-top:6px">' + ui.pill(pr[0], pr[1], pr[2]) + ui.pill(s[0], s[1], s[2]) + "</div></div></div>";
    body += ui.card(L("Order", "الطلب", "ऑर्डर"), '<dl class="hs-kv"><dt>Study</dt><dd>' + esc(o.study) + "</dd><dt>Modality</dt><dd>" + esc(MOD[o.mod][0]) + "</dd><dt>Referrer</dt><dd>" + esc(o.ref) + "</dd><dt>Ordered</dt><dd>" + n(o.at) + "</dd><dt>Contrast</dt><dd>" + esc(o.contrast === "none" ? "None" : o.contrast === "gadolinium" ? "Gadolinium (MRI)" : "Iodinated (IV)") + "</dd>" + (o.note ? "<dt>Clinical note</dt><dd>" + esc(o.note) + "</dd>" : "") + (o.slot ? "<dt>Slot</dt><dd>" + esc(o.slot) + "</dd>" : "") + "</dl>", { icon: "clipboard" });
    body += ui.card(L("Safety checks", "فحوصات السلامة", "सुरक्षा जाँच"), '<div class="hs-ck">' + radChecks(o).map(function (c) { return stRow(c[0] === "sub" ? "warn" : c[0], esc(c[1]), esc(c[3])); }).join("") + "</div>", { icon: "shieldcheck" });
    if (o.obst && R().pcpndt) {
      body += ui.card("PCPNDT · Form F", '<div class="hs-ck">' + ckRow("hsPf1_" + id, true, "Referral slip with indication attached", "Dating & viability · referred by " + esc(o.ref)) + ckRow("hsPf2_" + id, true, "Patient identity and declaration (section B)", "Signed on tablet 10:12") +
        ckRow("hsPf3_" + id, false, "Doctor's declaration: sex of the foetus neither detected nor disclosed", "Signed by the performing radiologist after the scan") + ckRow("hsPf4_" + id, false, "Entered in the monthly report to the Appropriate Authority", "Auto-filed on 5th of next month") + "</div>" +
        ui.note("info", "Machine USG-2 is registered under the PCPNDT Act. Images and forms are kept for the statutory period (example: 2 years)."), { icon: "scroll", right: ui.pill("sub", "India", "globe") });
    }
    if (o.mod === "CT" && o.contrast !== "none") body += ui.card(L("Protocol", "البروتوكول", "प्रोटोकॉल"), '<div class="small">' + (o.id === "RAD-3101" ? "Plain CT brain → CTA arch to vertex · 60 mL iohexol 350 at 4 mL/s · eGFR 38: proceed (stroke benefit outweighs risk), IV fluids after, creatinine at 48 h." : o.id === "RAD-3102" ? "Trauma whole-body: head and C-spine plain, chest/abdomen/pelvis portal-venous · 100 mL iohexol 350 · do not delay for creatinine." : "Portal-venous phase · 90 mL iohexol 300 · β-hCG must be negative first; if positive → USG / MRI instead.") + "</div>", { icon: "sliders" });
    var st = rSt(o);
    var foot = ui.btn(L("Schedule", "جدولة", "शेड्यूल"), { go: "radiology.schedule", icon: "calendar" });
    if (st === "acquired" || o.obst) foot += ui.btn(L("Open report", "فتح التقرير", "रिपोर्ट खोलें"), { cls: "pri", act: "hsgo", arg: "radiology.report|hsRep|" + id, icon: "edit" });
    else if (st === "reported") foot += ui.btn(L("View report", "عرض التقرير", "रिपोर्ट देखें"), { cls: "pri", act: "toast", arg: "Signed report opened (read-only)", icon: "file" });
    else foot += ui.btn(st === "arrived" || st === "scheduled" ? L("Start scan", "بدء الفحص", "स्कैन शुरू") : L("Mark arrived", "تسجيل الوصول", "आगमन दर्ज"), { cls: "pri", act: "hsrst", arg: id + "|" + (st === "arrived" || st === "scheduled" ? "scanning" : "arrived"), icon: "play" });
    return { kind: "drawer", size: "lg", title: id + " · " + esc(o.study.split(" (")[0]), body: body, foot: foot };
  });
  NB.act("hsrst", function (a) { var p = a.split("|"), m = Object.assign({}, NB.v("hsRst", {})); m[p[0]] = p[1]; NB.state.ui.hsRst = m; NB.state.overlay = null; NB.toast(p[0] + " · " + RST[p[1]][1]); });

  /* ---- radiology.schedule ---- */
  var T0 = 8 * 60, T1 = 14 * 60;
  NB.screen("radiology.schedule", function (c) {
    var h = ui.header(L("Modality schedule", "جدول الأجهزة", "मोडैलिटी शेड्यूल"), "Today 08:00–14:00 · STAT studies jump the queue and the next slots move automatically · patients get a WhatsApp update when their slot moves",
      ui.btn(L("Worklist", "قائمة العمل", "वर्कलिस्ट"), { go: "radiology.worklist", icon: "list" }) + ui.btn(L("Book slot", "حجز موعد", "स्लॉट बुक करें"), { cls: "pri", act: "toast", arg: "Pick an order from the worklist, then a free slot", icon: "plus" }));
    h += '<div class="grid g4 kpis">' + ui.kpi(n("82%"), L("CT utilisation", "استخدام المقطعية", "सीटी उपयोग"), "Next free 11:30", "faint") + ui.kpi(n("91%"), L("MRI utilisation", "استخدام الرنين", "एमआरआई उपयोग"), "QA 10:00–10:30 · next free 14:15", "faint") +
      ui.kpi(n(3), L("Waiting at X-ray", "بانتظار الأشعة", "एक्स-रे पर प्रतीक्षा"), "Walk-in · 12 min wait", "faint") + ui.kpi(n("1"), L("No-shows today", "لم يحضروا", "आज नो-शो"), "Slot back-filled from waitlist", "okc") + "</div>";
    if (c.mobile) {
      var mSel = NB.v("hsSchM", "CT-1"), row = D.rad.sched.find(function (r) { return r[0] === mSel; }) || D.rad.sched[0];
      h += ui.seg("hsSchM", D.rad.sched.map(function (r) { return [r[0], r[0]]; }), "CT-1");
      h += ui.card(esc(row[0] + " · " + row[1]), '<div class="lst">' + row[2].map(function (s) {
        var k = s[2] === "done" ? ["mute", "Done", "check"] : s[2] === "live" ? ["acc", "Now", "play"] : s[2] === "down" ? ["perm", "Downtime", "lock"] : ["info", "Next", "clockh"];
        return ui.li('<span class="av">' + n(s[0].slice(0, 5)) + "</span>", esc(s[3]) + (s[5] ? " " + ui.pill("crit", "STAT", "alertfill") : ""), esc(s[4] + " · " + s[1] + " min"), ui.pill(k[0], k[1], k[2]));
      }).join("") + "</div>", { icon: "calendar" });
      return h;
    }
    var hrs = '<div class="hs-schr"><div></div><div class="hs-hrs">' + Array.apply(null, Array(12)).map(function (_, i) { return '<span class="ltr">' + hhmm(T0 + i * 30) + "</span>"; }).join("") + "</div></div>";
    var nowPct = (NOW - T0) / (T1 - T0) * 100;
    var lanes = D.rad.sched.map(function (r) {
      var blocks = r[2].map(function (s) {
        var st0 = Math.max(T0, tm(s[0])), en = Math.min(T1, tm(s[0]) + s[1]); if (en <= st0) return "";
        var col = Math.round((st0 - T0) / 5) + 1, span = Math.max(1, Math.round((en - st0) / 5));
        var cls = s[2] === "done" ? "done" : s[2] === "live" ? "live" : s[2] === "down" ? "down" : "";
        return '<button class="hs-slot ' + cls + (s[5] ? " stat" : "") + '" style="grid-column:' + col + " / span " + span + '" ' + (s[2] === "down" ? "" : 'data-act="toast" data-arg="' + esc(s[3] + " · " + s[4] + " · " + s[0]) + '"') + ' title="' + esc(s[0] + " " + s[3] + " · " + s[4]) + '"><b>' + esc(s[2] === "down" ? s[3] : s[3]) + "</b><small>" + esc(s[2] === "down" ? "Downtime · " + s[0] : s[4]) + "</small></button>";
      }).join("");
      return '<div class="hs-schr"><div class="hs-schl">' + esc(r[0]) + "<small>" + esc(r[1]) + '</small></div><div class="hs-lane">' + blocks + '<span class="hs-now" style="inset-inline-start:' + nowPct.toFixed(2) + '%"></span></div></div>';
    }).join("");
    h += '<div class="hs-sch"><div class="hs-schin">' + hrs + lanes + "</div></div>";
    h += '<div class="row hs-leg">' + ui.pill("acc", L("In progress", "جارٍ", "जारी"), "play") + ui.pill("info", L("Booked", "محجوز", "बुक"), "calendar") + ui.pill("mute", L("Done", "تم", "पूर्ण"), "check") + ui.pill("crit", "STAT", "alertfill") + ui.pill("perm", L("Downtime / QA", "صيانة", "डाउनटाइम"), "lock") + '<span class="small faint">' + L("Red line = now", "الخط الأحمر = الآن", "लाल रेखा = अभी") + " " + n(hhmm(NOW)) + "</span></div>";
    var unb = D.rad.orders.filter(function (o) { return rSt(o) === "ordered"; });
    h += '<div class="grid g2">' + ui.card(L("To schedule", "للجدولة", "शेड्यूल हेतु"), '<div class="lst">' + unb.map(function (o) {
      return ui.li(ui.pill(PRIO[o.prio][0], PRIO[o.prio][1], PRIO[o.prio][2]), esc(o.study), esc(pnm(o) + " · " + o.src), ui.btn(o.prio === "stat" ? L("Insert now", "إدراج الآن", "अभी डालें") : L("Book", "حجز", "बुक"), { cls: "sm" + (o.prio === "stat" ? " pri" : ""), act: "toast", arg: o.id + " booked on " + (o.mod === "CT" ? "CT-1" : o.mod) + (o.prio === "stat" ? " · next slots shifted, 2 patients notified" : ""), icon: "calendar" }));
    }).join("") + "</div>", { icon: "list" }) +
      ui.card(L("Equipment & compliance", "الأجهزة والامتثال", "उपकरण और अनुपालन"), '<div class="hs-ck">' + stRow("ok", "CT-1 daily QA passed 07:40", "Water phantom, noise and uniformity") + stRow("warn", "MRI helium & coil QA 10:00–10:30", "Vendor engineer on site") +
        stRow("ok", "Lead aprons inspection", "Last 02 Oct · next 02 Jan") + stRow(R().pcpndt ? "info" : "ok", R().pcpndt ? "USG-2 PCPNDT registration valid to 31 Mar 2027" : "Radiation licence valid", esc(R().rad)) + "</div>", { icon: "settings" }) + "</div>";
    return h;
  });

  /* ---- radiology.report ---- */
  var TPL = {
    ctstroke: { name: "CT brain stroke + CTA", prior: ["CT brain · 12 Mar 2025 · group hospital", "Small chronic lacune in the right basal ganglia. No other abnormality."],
      f: [["Haemorrhage", "None"], ["Early ischaemic change", "Subtle loss of grey-white differentiation, left insula and lentiform nucleus"], ["ASPECTS", "8"], ["Hyperdense vessel", "Left MCA (M1)"],
        ["CT angiography", "Occlusion of the left M1 segment; moderate collaterals"], ["Carotids", "Less than 50% stenosis at both bifurcations"], ["Comparison", "Right basal ganglia lacune <span class=\"hs-hl\">unchanged</span> since 12 Mar 2025"],
        ["Impression", "Acute left MCA territory ischaemia with left M1 occlusion. No haemorrhage. Discussed with the stroke team for thrombolysis and thrombectomy."]], crit: true, kind: "ct" },
    cxr: { name: "Chest X-ray", prior: ["Chest X-ray · 14 Oct 2026 · this hospital", "Right lower zone consolidation."],
      f: [["Lines & tubes", "None"], ["Lungs", "Right lower zone consolidation, <span class=\"hs-hl\">partially resolved</span> since 14 Oct"], ["Pleura", "Small right costophrenic blunting"], ["Heart & mediastinum", "Normal size and contour"], ["Bones & soft tissue", "Unremarkable"],
        ["Impression", "Improving right lower lobe pneumonia. Small right effusion."]], crit: false, kind: "xr" },
    cxricu: { name: "Chest X-ray (ICU)", prior: ["Chest X-ray · today 09:05 · this hospital", "New right apical pneumothorax, 2 cm rim (critical, communicated)."],
      f: [["Lines & tubes", "ET tube 4 cm above carina · right IJ line tip in SVC · right intercostal drain in situ · sternal wires"], ["Lungs", "Right pneumothorax <span class=\"hs-hl\">resolved</span> after drain · bibasal atelectasis"], ["Pleura", "Small left effusion"], ["Heart & mediastinum", "Post-sternotomy, expected appearance"],
        ["Impression", "Pneumothorax resolved with drain in place. Expected post-CABG changes."]], crit: false, kind: "xr" },
    obus: { name: "Obstetric ultrasound (dating)", prior: ["Early pregnancy scan · 02 Sep 2026", "Single intrauterine gestational sac, yolk sac seen."],
      f: [["Pregnancy", "Single live intrauterine"], ["Crown-rump length", "78 mm = 13 weeks 6 days"], ["Fetal heart", "156 bpm, regular"], ["Placenta", "Posterior, clear of the internal os"], ["Liquor", "Adequate"], ["EDD by scan", "23 Apr 2027"],
        ["Impression", "Single live intrauterine pregnancy, 13 weeks 6 days, consistent with dates."]], crit: false, kind: "us" }
  };
  function tplFor(o) { return o.id === "RAD-3101" ? "ctstroke" : o.obst ? "obus" : o.id === "RAD-3111" ? "cxricu" : "cxr"; }
  function pacsSvg(kind, o, prior) {
    var shape = kind === "ct" ? '<ellipse class="fil" cx="200" cy="150" rx="92" ry="112"/><ellipse class="ln2" cx="200" cy="150" rx="74" ry="94"/><path class="ln" d="M200 46 V254"/><path class="ln2" d="M150 120 q50 30 100 0"/>'
      : kind === "us" ? '<path class="fil" d="M200 40 L320 230 A150 150 0 0 1 80 230 Z"/><ellipse class="ln2" cx="200" cy="170" rx="44" ry="30"/><circle class="ln" cx="182" cy="166" r="12"/>'
      : '<path class="fil" d="M120 70 C80 110 80 220 110 250 C140 262 175 250 178 230 L178 80 C170 64 140 58 120 70 Z"/><path class="fil" d="M280 70 C320 110 320 220 290 250 C260 262 225 250 222 230 L222 80 C230 64 260 58 280 70 Z"/><path class="ln" d="M200 40 V270"/>';
    var ini = esc(pnm(o).split(" ").map(function (w) { return w[0]; }).join("").slice(0, 3));
    return '<svg viewBox="0 0 400 300" role="img" aria-label="Image placeholder: images stay in PACS"><path class="ax" d="M0 150 H400 M200 0 V300"/>' + shape +
      '<text x="12" y="22">' + ini + " · " + esc(o.id) + '</text><text x="388" y="22" text-anchor="end">' + (kind === "ct" ? "W 80 L 40" : kind === "us" ? "B-mode · 4 MHz" : "AP · 2.5 mAs") + "</text>" +
      '<text x="12" y="288">' + (prior ? "Prior · " : "") + (kind === "ct" ? "Se 3 · Im 12/48" : "Im 1/" + (kind === "us" ? "24" : "1")) + '</text><text x="388" y="288" text-anchor="end">' + L("Placeholder · no image data", "عنصر نائب", "प्लेसहोल्डर") + "</text>" +
      '<text class="c" x="200" y="156" text-anchor="middle">' + (prior ? L("Prior study", "الدراسة السابقة", "पिछली स्टडी") : L("Images open in PACS", "الصور تُفتح في PACS", "इमेज PACS में खुलती हैं")) + "</text></svg>";
  }
  NB.screen("radiology.report", function (c) {
    var list = D.rad.orders.filter(function (o) { return rSt(o) === "acquired" || o.obst || rSt(o) === "reported" && o.id === "RAD-3103"; });
    var sel = NB.v("hsRep", "RAD-3101"), o = rOrd(sel) || list[0], tk = tplFor(o), t = TPL[tk], cmp = NB.v("hsCmp", true), signed = isDone("rep_" + o.id) || rSt(o) === "reported";
    var h = ui.header(L("Reporting", "كتابة التقارير", "रिपोर्टिंग"), "Structured templates · comparison with prior · images stay in PACS (zero-footprint viewer) · signed report goes to the doctor's results inbox",
      ui.btn(L("Critical findings", "النتائج الحرجة", "क्रिटिकल फ़ाइंडिंग"), { go: "radiology.critical", icon: "alertfill" }));
    var left = ui.card(L("To report", "للتقرير", "रिपोर्ट हेतु"), '<div class="lst">' + list.map(function (x) {
      var on = x.id === o.id, s = isDone("rep_" + x.id) || rSt(x) === "reported" ? RST.reported : RST[rSt(x) === "acquired" ? "acquired" : "arrived"];
      return '<div class="li click" data-set="hsRep" data-val="' + x.id + '" style="' + (on ? "background:var(--acc-tint);padding-inline:8px" : "padding-inline:8px") + '">' + ui.pill(PRIO[x.prio][0], PRIO[x.prio][1], PRIO[x.prio][2]) + '<div class="t"><b dir="auto">' + esc(pnm(x)) + "</b><small>" + esc(x.study) + "</small></div>" + ui.icon(s[2], "sm " + (s[0] === "ok" ? "okc" : "warnc")) + "</div>";
    }).join("") + "</div>", { icon: "list" });
    var pr = PRIO[o.prio], p = pobj(o);
    var viewer = '<div class="hs-pacs"><div class="hs-pbar"><span class="fl"><b style="color:var(--tx)">' + esc(o.study) + "</b></span>" + ui.btn(L("Compare prior", "مقارنة السابقة", "पिछली से तुलना"), { cls: "sm" + (cmp ? " pri" : ""), act: "hsset", arg: "hsCmp|" + (cmp ? "" : "1"), icon: "layers" }) +
      ui.btn(L("Open in PACS", "فتح في PACS", "PACS में खोलें"), { cls: "sm", act: "toast", arg: "Opening " + o.id + " in the PACS viewer (study UID 1.2.840.113619.2." + o.id.slice(4) + ")", icon: "monitor" }) + "</div>" +
      (cmp ? '<div class="grid g2" style="gap:1px;background:var(--b-sub)"><div style="background:var(--sunk)">' + pacsSvg(t.kind, o) + '</div><div style="background:var(--sunk)">' + pacsSvg(t.kind, o, true) + "</div></div>" : pacsSvg(t.kind, o)) +
      '<div class="hs-thumbs">' + (t.kind === "ct" ? ["Scout", "Axial 5 mm", "Axial 1 mm", "CTA MIP", "Sag MPR"] : t.kind === "us" ? ["CRL", "FHR M-mode", "Placenta", "Cervix"] : ["AP", "Prior 14 Oct"]).map(function (s, i) { return '<span class="' + (i === (t.kind === "ct" ? 1 : 0) ? "on" : "") + '">' + esc(s) + "</span>"; }).join("") + "</div></div>";
    var prior = ui.note("info", "<b>" + L("Prior", "السابق", "पिछली") + ":</b> " + esc(t.prior[0]) + " — " + esc(t.prior[1]));
    var form = '<dl class="hs-sf">' + t.f.map(function (x) { return "<dt>" + esc(x[0]) + "</dt><dd" + (x[0] === "Impression" ? ' style="font-weight:600"' : "") + ">" + x[1] + "</dd>"; }).join("") + "</dl>";
    var pc = o.obst && R().pcpndt ? ckRow("hsPcpDecl_" + o.id, false, "PCPNDT declaration: I have neither detected nor disclosed the sex of the foetus", "Required on every obstetric report in India (example wording); the template has no field for sex") : "";
    var rel = '<div class="hs-ck">' + pc + ckRow("hsRelDoc_" + o.id, true, L("Release to referring doctor", "إرسال للطبيب المحيل", "रेफ़र करने वाले डॉक्टर को भेजें"), esc(o.ref) + " · results inbox + push") +
      ckRow("hsRelPt_" + o.id, false, L("Release to patient app", "إرسال لتطبيق المريض", "मरीज़ ऐप पर भेजें"), "After the doctor reviews, or automatically after 72 h (owner policy)") +
      ckRow("hsRelCrit_" + o.id, t.crit, L("Critical finding · closed-loop call", "نتيجة حرجة", "क्रिटिकल फ़ाइंडिंग"), t.crit ? "Logged 10:16 · read-back by Dr. Ayesha Siddiqui" : "Not critical") + "</div>";
    var rep = ui.card(L("Structured report", "تقرير منظم", "संरचित रिपोर्ट") + " · " + esc(t.name), '<div class="row" style="margin-bottom:12px">' + ui.av(pnm(o)) + '<div class="fl"><b dir="auto">' + esc(pnm(o)) + '</b><div class="small faint">' + esc((p.age || o.age) + " " + (p.sex || o.sex) + " · " + o.src + " · " + o.ref) + "</div></div>" + ui.pill(pr[0], pr[1], pr[2]) + (signed ? ui.pill("ok", L("Signed", "موقّع", "हस्ताक्षरित"), "checkcircle") : ui.pill("warn", L("Draft", "مسودة", "ड्राफ़्ट"), "pen")) + "</div>" +
      prior + '<div style="margin-top:12px">' + form + "</div>" + '<div class="eyebrow" style="margin:16px 0 4px">' + L("Release", "الإصدار", "जारी करना") + "</div>" + rel +
      '<div class="btns" style="margin-top:12px">' + ui.btn(L("Save draft", "حفظ المسودة", "ड्राफ़्ट सहेजें"), { act: "toast", arg: "Draft saved", icon: "check" }) +
      (signed ? ui.btn(L("Addendum", "ملحق", "परिशिष्ट"), { act: "toast", arg: "Addendum opened · original stays visible", icon: "plus" }) : ui.btn(L("Sign & release", "توقيع وإصدار", "हस्ताक्षर करें और जारी करें"), { cls: "pri", act: "hsdone", arg: "rep_" + o.id + "|" + o.id + " signed · released to " + o.ref + "|1|keep", icon: "pen" })) +
      ui.btn(L("Log critical finding", "تسجيل نتيجة حرجة", "क्रिटिकल दर्ज करें"), { cls: "dng", ov: "critcall", arg: o.id, icon: "alertfill" }) + "</div>", { icon: "edit" });
    return h + '<div class="hs-rep">' + left + '<div class="hs-rv"><div class="stack s16">' + viewer + ui.note("ai", "Draft assist: findings are pre-filled from the template and the prior report. Every line is the radiologist's to accept or edit; nothing is signed automatically.") + "</div>" + rep + "</div></div>";
  });
  NB.act("hsset", function (a) { var p = String(a).split("|"), o = {}; o[p[0]] = p[1] === "" ? false : p[1]; NB.setUI(o); });

  /* ---- radiology.critical ---- */
  var CAT = { 1: ["crit", "Category 1 · ≤ 30 min", "alertfill"], 2: ["urg", "Category 2 · ≤ 4 h", "bolt"], 3: ["info", "Category 3 · ≤ 3 days", "info"] };
  NB.screen("radiology.critical", function (c) {
    var log = D.rad.crit, open = log.filter(function (x) { return x.st === "open" && !isDone("crit_" + x.id); });
    var h = ui.header(L("Critical findings", "النتائج الحرجة", "क्रिटिकल फ़ाइंडिंग"), "Closed-loop communication: call, read-back, document · unacknowledged calls escalate automatically · targets are an example hospital policy",
      ui.btn(L("Log a finding", "تسجيل نتيجة", "फ़ाइंडिंग दर्ज करें"), { cls: "pri", ov: "critcall", arg: "new", icon: "plus" }));
    h += '<div class="grid g4 kpis">' + ui.kpi(n(open.length), L("Open calls", "مكالمات مفتوحة", "खुली कॉल"), open.length ? "Escalates at 10:33" : "None", open.length ? "critc" : "okc") +
      ui.kpi(n("4 min"), L("Median time to communicate (Cat 1)", "متوسط زمن الإبلاغ", "संचार का औसत समय"), ui.icon("checkcircle", "xs") + " Target ≤ 30 min", "okc") + ui.kpi(n("100%"), L("Read-back documented", "التأكيد بالإعادة", "रीड-बैक दर्ज"), "Last 30 days, Cat 1", "okc") + ui.kpi(n(0), L("Escalations this week", "تصعيدات هذا الأسبوع", "इस सप्ताह एस्केलेशन"), "", "faint") + "</div>";
    h += open.map(function (x) {
      return ui.card("", '<div class="fl"><div class="row">' + ui.pill(CAT[x.cat][0], CAT[x.cat][1], CAT[x.cat][2]) + ui.pill("urg", L("Calling", "جارٍ الاتصال", "कॉल जारी") + " · " + dur(since(x.t)), "phone") + '</div><h2 class="h" style="margin-top:8px" dir="auto">' + esc(x.finding) + '</h2><div class="small muted">' + esc(pnm(x) + " · " + x.study + " · " + x.id + " · found " + x.t + " by " + x.by) + "</div></div>" +
        '<div class="hs-ck" style="margin-top:10px">' + stRow("warn", "Call 1 · " + esc(x.to) + " · 10:18", "No answer · paged") + stRow("mute", "Call 2 due 10:23", "Then escalate to ER consultant in charge at 10:33") + "</div>" +
        '<div class="btns" style="margin-top:10px">' + ui.btn(L("Log call & read-back", "تسجيل المكالمة والتأكيد", "कॉल और रीड-बैक दर्ज"), { cls: "pri", ov: "critcall", arg: x.id, icon: "phone" }) + ui.btn(L("Escalate now", "تصعيد الآن", "अभी एस्केलेट"), { cls: "dng", act: "toast", arg: "Escalated to Dr. Sameer Kulkarni (ER consultant) · ext 2201", icon: "bell" }) + "</div>", { cls: "crit" });
    }).join("");
    h += ui.table([{ h: "Time" }, { h: "Patient · study" }, { h: "Finding" }, { h: "Category" }, { h: "Communicated to" }, { h: "Read-back" }, { h: "Time to call", cls: "r" }], log.map(function (x) {
      var cl = x.st === "open" && !isDone("crit_" + x.id);
      return [n(x.t), ui.cellp('<span dir="auto">' + esc(pnm(x)) + "</span>", esc(x.study)), '<span dir="auto">' + esc(x.finding) + "</span>", ui.pill(CAT[x.cat][0], "Cat " + x.cat, CAT[x.cat][2]), ui.cellp(esc(x.to), esc(x.via)),
        cl ? ui.pill("crit", L("Pending", "معلّق", "बाक़ी"), "hourglass") : x.rb || isDone("crit_" + x.id) ? ui.pill("ok", L("Confirmed", "مؤكد", "पुष्टि"), "checkcircle") : ui.pill("mute", L("Not required", "غير مطلوب", "आवश्यक नहीं"), "dot"), '<span class="num">' + (cl ? dur(since(x.t)) : x.mins + " min") + "</span>"];
    }), { min: 980 });
    return h;
  });
  NB.overlay("critcall", function (id) {
    var x = D.rad.crit.find(function (k) { return k.id === id; }), o = rOrd(id);
    var fnd = x ? x.finding : o ? "" : "", who = x ? x.to : o ? o.ref : "";
    var body = (x || o ? ui.note("crit", "<b>" + esc(pnm(x || o)) + "</b> · " + esc((x || o).study) + (fnd ? "<br>" + esc(fnd) : "")) : "") +
      '<div class="fgrid">' + ui.field("Finding", fnd, { id: "hs-cf", full: true, area: true, ph: "Describe the finding as it will be read back" }) + ui.field("Category", "Category 1 · immediately life-threatening", { id: "hs-cc", options: ["Category 1 · immediately life-threatening", "Category 2 · urgent", "Category 3 · significant unexpected"] }) +
      ui.field("Communicated to", who, { id: "hs-ct" }) + ui.field("Method", "Phone (direct)", { id: "hs-cm", options: ["Phone (direct)", "Phone (via operator)", "In person", "Secure message (Cat 3 only)"] }) + ui.field("Time", hhmm(NOW), { id: "hs-ctm" }) + "</div>" +
      '<div class="hs-ck">' + ckRow("hsCr1_" + id, false, "Receiver identity confirmed", "Name, role, and that they are responsible for the patient") + ckRow("hsCr2_" + id, false, "Receiver read the finding back", "Verbatim, including side and size") + ckRow("hsCr3_" + id, false, "Action agreed", "e.g. hold anticoagulation, urgent CT aortogram") + ckRow("hsCr4_" + id, true, "Addendum added to the report", "Time, receiver and read-back are written into the signed report") + "</div>";
    return { kind: "modal", title: L("Critical finding · closed loop", "نتيجة حرجة", "क्रिटिकल फ़ाइंडिंग"), body: body,
      foot: ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn(L("Close the loop", "إغلاق الحلقة", "लूप बंद करें"), { cls: "pri", act: "hsdone", arg: "crit_" + id + "|Critical finding communicated with read-back · logged at " + hhmm(NOW), icon: "check" }) };
  });

  /* =====================================================================
     HOSPITAL SERVICES (role "hospops")
     ===================================================================== */

  /* ---- hospops.bloodbank ---- */
  var XST = { issue: ["crit", "MTP · issue now", "alertfill"], ready: ["ok", "Ready to issue", "checkcircle"], workup: ["warn", "Antibody work-up", "flask"], done: ["mute", "Group & save valid", "check"], sample: ["info", "Sample received", "flask"], issued: ["acc", "Issued", "send"] };
  function grp(g) { return '<span class="hs-grp">' + esc(g) + "</span>"; }
  function bbReq(id) { return D.bb.req.find(function (r) { return r.id === id; }); }
  NB.screen("hospops.bloodbank", function (c) {
    var B = D.bb, tab = NB.v("hsBbTab", "stock");
    var tot = B.groups.reduce(function (a, g) { return a + B.inv[g][0]; }, 0);
    var h = ui.header(L("Blood bank", "بنك الدم", "ब्लड बैंक"), esc(R().bb) + " (example)",
      ui.btn(L("Record donation", "تسجيل تبرع", "दान दर्ज करें"), { act: "toast", arg: "Donor questionnaire opened on the counter tablet", icon: "userplus" }) + ui.btn(L("Issue blood", "صرف الدم", "रक्त जारी करें"), { cls: "pri", ov: "issueblood", arg: "XM-881", icon: "send" }));
    h += '<div class="grid g4 kpis">' + ui.kpi(n(tot), L("PRBC units in stock", "وحدات كريات حمراء", "PRBC यूनिट"), "All groups · 2–6 °C", "faint") + ui.kpi(n(B.inv["O-"][0]), L("O-negative PRBC", "O سالب", "O-नेगेटिव"), "Below minimum 4 · donor recall sent", "critc") +
      ui.kpi(n(8), L("Expiring in 72 h", "تنتهي خلال ٧٢ ساعة", "72 घंटे में एक्सपायर"), "Offer to ICU / OT first (FEFO)", "warnc") + ui.kpi(n(B.req.filter(function (r) { return r.st !== "done"; }).length), L("Open requests", "طلبات مفتوحة", "खुले अनुरोध"), "1 massive transfusion", "critc") + "</div>";
    h += ui.tabs("hsBbTab", [["stock", L("Stock & requests", "المخزون والطلبات", "स्टॉक और अनुरोध"), "box"], ["donors", L("Donors", "المتبرعون", "दाता"), "users"], ["react", L("Transfusion reactions", "تفاعلات النقل", "ट्रांसफ़्यूज़न रिएक्शन"), "alertcircle"]], "stock");
    if (tab === "stock") {
      h += ui.note("crit", "<b>Massive transfusion protocol active · ER R1 (Trauma 0417).</b> 2 units O-negative issued uncrossmatched at 10:10. Patient typed B+ at 10:22 → switch to group-specific. Pack 1 ready: 4 PRBC, 4 FFP, 1 apheresis platelet. " + ui.btn("Issue pack 1", { cls: "sm dng", ov: "issueblood", arg: "XM-881", icon: "send" }));
      var rows = B.groups.map(function (g) {
        var neg = g.indexOf("-") > 0, mins = neg ? B.minNeg : B.min;
        return [grp(g)].concat(B.inv[g].map(function (u, i) { var cls = u === 0 ? "out" : u < mins[i] ? "low" : ""; return '<span class="hs-u ' + cls + '">' + (cls ? ui.icon(cls === "out" ? "alertfill" : "alertcircle") : "") + u + "</span>"; }));
      });
      var inv = ui.card(L("Inventory by group & component", "المخزون حسب الفصيلة والمكوّن", "ग्रुप और कंपोनेंट अनुसार स्टॉक"), ui.table([{ h: "Group" }].concat(B.comps.map(function (cp) { return { h: cp[1] + " · " + cp[2] + " d", cls: "r" }; })), rows, { min: 460 }) +
        '<div class="row hs-leg" style="margin-top:10px"><span class="hs-u low">' + ui.icon("alertcircle") + "Low</span><span class=\"hs-u out\">" + ui.icon("alertfill") + "Out</span><span class=\"small faint\">Minimum levels set per group · shelf life shown per component</span></div>", { icon: "heartpulse" });
      var exp = ui.card(L("Expiring soon", "تنتهي قريبًا", "जल्द एक्सपायर"), '<div class="lst">' + B.exp.map(function (e) { return ui.li(grp(e[0]), esc(e[1]) + " · " + n(e[2]) + (e[2] === 1 ? " unit" : " units"), L("Expires", "تنتهي", "एक्सपायर") + " " + esc(e[3]), ui.btn("Offer", { cls: "sm", act: "toast", arg: "Offered to ICU and OT · FEFO", icon: "send" })); }).join("") + "</div>", { icon: "clockh" });
      var req = ui.card(L("Crossmatch & issue requests", "طلبات التوافق والصرف", "क्रॉसमैच और इश्यू अनुरोध"), '<div class="lst">' + B.req.map(function (r) {
        var st = isDone("iss_" + r.id) ? XST.issued : XST[r.st], g0 = r.grp.split(" ")[0];
        var btn = r.st === "issue" || r.st === "ready" ? (isDone("iss_" + r.id) ? "" : ui.btn(L("Issue", "صرف", "जारी"), { cls: "sm " + (r.st === "issue" ? "dng" : "pri"), ov: "issueblood", arg: r.id, icon: "send" })) : r.st === "sample" ? ui.btn(L("Start grouping", "بدء التصنيف", "ग्रुपिंग शुरू"), { cls: "sm", act: "toast", arg: "Grouping and antibody screen started for " + r.id, icon: "flask" }) : "";
        return ui.li(grp(g0 === "O-" ? "B+" : g0), '<span dir="auto">' + esc(r.pid ? NB.pname(NB.pat(r.pid)) : r.name) + '</span> <span class="small faint">· ' + esc(r.loc) + "</span>", esc(r.comp + " · " + r.note), '<div class="row nw">' + ui.pill(st[0], st[1], st[2]) + btn + "</div>");
      }).join("") + "</div>", { icon: "flask" });
      h += '<div class="split wide"><div class="stack s16">' + inv + req + '</div><div class="stack s16">' + exp + ui.card(L("Safety rules", "قواعد السلامة", "सुरक्षा नियम"), '<div class="hs-ck">' +
        stRow("ok", "Two-person check at issue and at the bedside", "Bag, label and wristband scanned; no override") + stRow("ok", "Electronic issue only with two concordant groups", "Current sample + historical group") + stRow("ok", "Start within 30 min, finish within 4 h", "Unit returned if not started in time") + stRow("info", "Reactions reported to " + esc(R().hv), "Example national programme") + "</div>", { icon: "shieldcheck" }) + "</div></div>";
    } else if (tab === "donors") {
      h += ui.table([{ h: "Donor" }, { h: "Name" }, { h: "Group" }, { h: "Type" }, { h: "Last donation" }, { h: "Hb g/dL", cls: "r" }, { h: "Status" }], B.donors.map(function (d) {
        var st = d[6] === "ok" ? ui.pill("ok", "Eligible", "checkcircle") : d[6] === "warn" ? ui.pill("warn", "Deferred", "clockh") : ui.pill("crit", "Confidential", "lock");
        return [n(d[0]), ui.cellp(esc(d[1]), esc(d[7])), grp(d[2]), esc(d[3]), esc(d[4]), '<span class="num' + (parseFloat(d[5]) < 12.5 ? " warnc" : "") + '">' + d[5] + "</span>", st];
      }), { min: 900 });
      h += '<div class="grid g2">' + ui.card(L("Today's collections · TTI screening", "فحص الأمراض المنقولة", "आज के संग्रह · TTI स्क्रीनिंग"), '<div class="lst">' + [["DN-2212 · B+ · bag 26B-4480", "running"], ["DN-2210 · O+ · bag 26B-4478", "clear"]].map(function (x) {
        return ui.li(avi("flask"), esc(x[0]), "", '<div class="hs-chips">' + B.tti.map(function (t) { return x[1] === "clear" ? ui.pill("ok", t, "check") : ui.pill("info", t, "hourglass"); }).join("") + "</div>");
      }).join("") + "</div>" + '<div class="small faint" style="margin-top:8px">Units stay in quarantine stock until all five screens are non-reactive.</div>', { icon: "shieldcheck" }) +
        ui.card(L("Donation drives", "حملات التبرع", "रक्तदान शिविर"), '<div class="lst">' + ui.li(avi("calendar"), "Voluntary camp · Tech park, Whitefield", "22 Oct · 09:00–15:00 · 60 donors registered", ui.pill("info", "Planned", "calendar")) + ui.li(avi("bell"), "O-negative recall", "18 eligible donors messaged on WhatsApp · 4 confirmed for tomorrow", ui.pill("ok", "Sent", "send")) + "</div>", { icon: "users" }) + "</div>";
      h += ui.note("info", "Donor eligibility (age, weight, Hb, interval between donations, deferral reasons) follows the configured national rules; values shown are examples. Reactive results are visible only to the medical officer and are never shown on shared screens.");
    } else {
      h += ui.table([{ h: "Report" }, { h: "When" }, { h: "Patient" }, { h: "Unit" }, { h: "Reaction" }, { h: "Action taken" }, { h: "Haemovigilance" }], B.reactions.map(function (r) {
        return [n(r[0]), esc(r[1]), esc(r[2]), esc(r[3]), '<span dir="auto">' + esc(r[4]) + "</span>", '<span class="small">' + esc(r[5]) + "</span>", r[6] === "Reported" ? ui.pill("ok", "Reported", "send") : ui.pill("warn", "Investigation open", "hourglass")];
      }), { min: 1000 });
      h += ui.note("info", "Suspected reactions: stop the transfusion, keep the line open, return the bag and a fresh sample to the blood bank. Serious reactions are reported to " + esc(R().hv) + " (example).");
    }
    return h;
  });
  NB.overlay("issueblood", function (id) {
    var r = bbReq(id) || D.bb.req[0], k = "iss_" + r.id, w1 = chk("hsIw1_" + r.id, false), w2 = chk("hsIw2_" + r.id, false);
    var units = r.id === "XM-881" ? [["26B-4471", "PRBC", "B+", "02 Nov", "Immediate-spin compatible"], ["26B-4472", "PRBC", "B+", "02 Nov", "Immediate-spin compatible"], ["26B-4473", "PRBC", "B+", "04 Nov", "Immediate-spin compatible"], ["26B-4474", "PRBC", "B+", "04 Nov", "Immediate-spin compatible"],
      ["26F-1120", "FFP", "B+", "Use by 15:20", "Group-compatible"], ["26F-1121", "FFP", "B+", "Use by 15:20", "Group-compatible"], ["26F-1122", "FFP", "B+", "Use by 15:20", "Group-compatible"], ["26F-1123", "FFP", "B+", "Use by 15:20", "Group-compatible"], ["26S-0311", "SDP", "B+", "19 Oct", "Group-compatible"]]
      : [["26B-4412", "PRBC", "B+", "29 Oct", "AHG crossmatch compatible"], ["26B-4418", "PRBC", "B+", "30 Oct", "AHG crossmatch compatible"]];
    var body = '<div class="row nw">' + ui.av(r.name, "lg") + '<div class="fl"><h2 class="h" dir="auto">' + esc(r.pid ? NB.pname(NB.pat(r.pid)) : r.name) + '</h2><div class="small muted">' + esc(r.loc + " · " + r.id + " · " + r.comp) + "</div></div>" + ui.pill(XST[r.st][0], XST[r.st][1], XST[r.st][2]) + "</div>";
    if (r.id === "XM-881") body += ui.note("warn", "Emergency: patient identified only by temporary ID <b>ER-0417</b>. Wristband and sample carry the same temporary ID.");
    body += ui.card(L("Units", "الوحدات", "यूनिट"), ui.table([{ h: "Bag" }, { h: "Component" }, { h: "Group" }, { h: "Expiry" }, { h: "Compatibility" }], units.map(function (u) { return ['<span class="hs-num">' + esc(u[0]) + "</span>", esc(u[1]), grp(u[2]), esc(u[3]), ui.pill("ok", u[4], "check")]; }), { min: 520 }), { icon: "heartpulse" });
    body += '<div class="hs-2p">' + '<div class="hs-who' + (w1 ? " ok" : "") + '" data-act="hsck" data-arg="hsIw1_' + r.id + '|0" role="checkbox" aria-checked="' + w1 + '" tabindex="0">' + ui.icon(w1 ? "checkcircle" : "fingerprint", w1 ? "okc" : "") + '<div class="t"><b>' + L("Issuer", "المُصدِر", "जारीकर्ता") + "</b><small>" + (w1 ? "Kiran Rao · blood bank technician · badge scanned 10:24" : "Scan badge · blood bank technician") + "</small></div></div>" +
      '<div class="hs-who' + (w2 ? " ok" : "") + '" data-act="hsck" data-arg="hsIw2_' + r.id + '|0" role="checkbox" aria-checked="' + w2 + '" tabindex="0">' + ui.icon(w2 ? "checkcircle" : "fingerprint", w2 ? "okc" : "") + '<div class="t"><b>' + L("Collector", "المستلِم", "प्राप्तकर्ता") + "</b><small>" + (w2 ? "Br. Joel Mathew · ER nurse · badge scanned 10:25" : "Scan badge · nurse or porter collecting") + "</small></div></div></div>";
    body += ui.card(L("Checks read aloud by both", "يتحقق كلاهما بصوت عالٍ", "दोनों ज़ोर से जाँचें"), '<div class="hs-ck">' + ckRow("hsIc1_" + r.id, true, "Patient identity on request, sample and label match", "Name / temporary ID, " + NB.mkt().natId + " or MRN, date of birth") +
      ckRow("hsIc2_" + r.id, true, "Bag group and Rh match the compatibility label", "") + ckRow("hsIc3_" + r.id, false, "Bag numbers match the issue slip", units.length + " units") + ckRow("hsIc4_" + r.id, false, "Expiry date and time valid", "") +
      ckRow("hsIc5_" + r.id, false, "Visual inspection: no clots, haemolysis, leaks or discoloration", "") + ckRow("hsIc6_" + r.id, false, "Validated transport box, 2–6 °C for red cells", "Platelets travel at room temperature, never in the cold box") + "</div>", { icon: "shieldcheck" });
    body += ui.note("info", "At the bedside two nurses repeat the check against the wristband. Start within 30 min of issue; vitals at start, 15 min and end; finish within 4 h.");
    var ok = w1 && w2;
    return { kind: "drawer", size: "lg", title: L("Issue blood", "صرف الدم", "रक्त जारी करें") + " · " + r.id, body: body,
      foot: ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn(ok ? L("Issue units", "صرف الوحدات", "यूनिट जारी करें") : L("Two-person check needed", "يلزم تحقق شخصين", "दो-व्यक्ति जाँच आवश्यक"), { cls: "pri", disabled: !ok, act: "hsdone", arg: k + "|" + units.length + " units issued at " + hhmm(NOW) + " · two-person check recorded", icon: "send" }) };
  });

  /* ---- hospops.cssd ---- */
  var IND = { pass: ["ok", "Pass", "checkcircle"], pending: ["info", "Pending", "hourglass"], fail: ["crit", "Fail", "alertfill"], na: ["mute", "n/a", "dot"] };
  var CYS = { running: ["sync", "Running", "refresh"], released: ["ok", "Released", "checkcircle"], aeration: ["info", "Aerating", "clockh"], recall: ["crit", "Recall", "alertfill"] };
  NB.screen("hospops.cssd", function (c) {
    var C = D.cssd, recallDone = isDone("recall_A2-0412");
    var h = ui.header(L("CSSD sterilisation", "التعقيم المركزي", "सीएसएसडी स्टरलाइज़ेशन"), "Every instrument set is barcoded from decontamination to the patient · loads are released only on indicator results",
      ui.btn(L("Scan set", "مسح طقم", "सेट स्कैन करें"), { act: "toast", arg: "Scanner ready: scan a tray barcode", icon: "barcode" }) + ui.btn(L("Start a load", "بدء دورة", "लोड शुरू करें"), { cls: "pri", ov: "cssdload", arg: "new", icon: "play" }));
    h += ui.note(recallDone ? "warn" : "crit", "<b>Load A2-0412 failed its biological indicator</b> (Autoclave 2, 16 Oct 15:20; read 09:40 today). 5 items traced; 3 patients were exposed. " + (recallDone ? "Recall actions recorded; infection control is following up." : "Autoclave 2 is out of service until re-validated.") + " " + ui.btn(L("Open recall", "فتح الاستدعاء", "रिकॉल खोलें"), { cls: "sm " + (recallDone ? "" : "dng"), ov: "cssdload", arg: "A2-0412", icon: "alertfill" }));
    h += '<div class="grid g4 kpis">' + ui.kpi(n(C.sets.filter(function (s) { return s[3] !== "issued"; }).length), L("Sets in process or stored", "أطقم قيد المعالجة", "प्रोसेस में सेट"), "Barcode-tracked", "faint") + ui.kpi(n(5), L("Loads today", "دورات اليوم", "आज के लोड"), "Autoclave 1 · plasma · ETO", "faint") +
      ui.kpi(n(3), L("Biological indicators pending", "مؤشرات حيوية معلّقة", "BI बाक़ी"), "Rapid read 3 h · ETO 48 h", "warnc") + ui.kpi(n("14/16"), L("Sets ready for tomorrow's OT list", "أطقم جاهزة لغرفة العمليات", "कल की ओटी सूची हेतु"), "2 in sterilisation", "okc") + "</div>";
    h += '<div class="hs-kan">' + C.stages.map(function (sg) {
      var items = C.sets.filter(function (s) { return s[3] === sg[0]; });
      return '<div class="hs-col"><div class="hs-colh">' + ui.icon(sg[4], "sm") + '<span class="fl">' + L(sg[1], sg[2], sg[3]) + '</span><span class="nb-badge">' + items.length + "</span></div>" + items.map(function (s) {
        return '<div class="hs-set' + (s[5] === "q" ? " q" : "") + '"><div class="row sb" style="gap:4px 8px"><b class="ltr">' + esc(s[0]) + "</b>" + (s[5] === "q" ? ui.pill("crit", L("Recall", "استدعاء", "रिकॉल"), "alertfill") : ui.pill("mute", s[2], "building")) + "</div><small>" + esc(s[1]) + "</small><small>" + esc(s[4]) + "</small></div>";
      }).join("") + "</div>";
    }).join("") + "</div>";
    h += ui.card(L("Cycle log", "سجل الدورات", "साइकिल लॉग"), ui.table([{ h: "Load" }, { h: "Steriliser · cycle" }, { h: "Start → end" }, { h: "Bowie-Dick" }, { h: "Chemical" }, { h: "Biological" }, { h: "Items", cls: "r" }, { h: "Status" }], C.cycles.map(function (y) {
      var p = function (k) { var x = IND[k]; return ui.pill(x[0], x[1], x[2]); }, s = CYS[y.st];
      return { cells: ['<span class="tok">' + y.id + "</span>", ui.cellp(esc(y.unit), "Operator " + esc(y.op)), '<span class="small">' + esc(y.start) + " → " + esc(y.end) + "</span>", p(y.bd), p(y.ci), p(y.bi), n(y.items), ui.pill(s[0], s[1], s[2])], ov: "cssdload", arg: y.id, cls: y.st === "recall" ? "crit" : "" };
    }), { min: 980 }) + '<div class="small faint" style="margin-top:10px">Bowie-Dick (air-removal) test runs daily before the first prevacuum load · chemical indicator in every pack · biological indicator in every load with implants and at least daily (example policy).</div>', { icon: "thermo" });
    return h;
  });
  NB.overlay("cssdload", function (id) {
    var C = D.cssd;
    if (id === "new") return { kind: "modal", title: L("Start a load", "بدء دورة", "लोड शुरू करें"), body: '<div class="fgrid">' + ui.field("Steriliser", "Autoclave 1 · 134 °C prevacuum", { id: "hs-st", options: ["Autoclave 1 · 134 °C prevacuum", "Autoclave 2 · out of service (failed BI)", "Plasma (H₂O₂)", "ETO · 55 °C"] }) + ui.field("Load no.", "A1-0414", { id: "hs-ln" }) + "</div>" +
      '<div class="hs-ck">' + ckRow("hsNl1", true, "Bowie-Dick test passed today", "07:00 · Autoclave 1") + ckRow("hsNl2", false, "Biological indicator placed", "Required: load contains an implant tray") + ckRow("hsNl3", false, "Scan each set into the load", "0 sets scanned") + "</div>",
      foot: ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn("Start cycle", { cls: "pri", act: "toast", arg: "Load A1-0414 started · parameters recorded from the steriliser", icon: "play" }) };
    var y = C.cycles.find(function (k) { return k.id === id; }) || C.cycles[0], s = CYS[y.st], fail = y.st === "recall";
    var body = '<div class="row nw">' + avi("thermo", "lg") + '<div class="fl"><h2 class="h ltr">' + esc(y.id) + '</h2><div class="small muted">' + esc(y.unit + " · " + y.start + " → " + y.end + " · operator " + y.op) + "</div></div>" + ui.pill(s[0], s[1], s[2]) + "</div>";
    body += ui.card(L("Cycle parameters", "معاملات الدورة", "साइकिल पैरामीटर"), '<dl class="hs-kv"><dt>Exposure</dt><dd>' + (y.unit.indexOf("134") >= 0 ? "134 °C for 4 min · 2.1 bar · 3 vacuum pulses" : y.unit.indexOf("ETO") >= 0 ? "55 °C · 60% RH · 4 h exposure · 12 h aeration" : "H₂O₂ plasma · 2 cycles · 47 min") + "</dd><dt>Printout</dt><dd>Captured from the steriliser · signed by operator</dd><dt>Bowie-Dick</dt><dd>" + IND[y.bd][1] + "</dd><dt>Chemical indicator</dt><dd>" + IND[y.ci][1] + "</dd><dt>Biological indicator</dt><dd>" + (fail ? '<b class="critc">Positive growth at 3 h read · 09:40</b>' : IND[y.bi][1]) + "</dd></dl>", { icon: "sliders" });
    if (fail) {
      var done = isDone("recall_" + id);
      body += ui.note("crit", "<b>Recall:</b> treat every item from this load as non-sterile. Trace, retrieve, quarantine and reprocess; inform infection control and the treating doctors of patients already exposed.");
      body += ui.card(L("Traced items", "العناصر المتتبَّعة", "ट्रेस किए गए आइटम"), '<div class="hs-ck">' + C.recall.map(function (r) { return stRow(r[5], esc(r[0] + " · " + r[1]) + ' <span class="small faint">' + esc(r[2]) + "</span>", esc(r[3]), '<span class="small ' + (r[5] === "crit" ? "critc" : "muted") + '">' + esc(r[4]) + "</span>"); }).join("") + "</div>", { icon: "list" });
      body += ui.card(L("Recall actions", "إجراءات الاستدعاء", "रिकॉल कार्रवाई"), '<div class="hs-ck">' + ckRow("hsRc1", true, "Autoclave 2 taken out of service", "Engineer called · 3 consecutive passing BI runs needed") + ckRow("hsRc2", true, "Unused items quarantined", "Red tag, separate shelf") +
        ckRow("hsRc3", false, "Infection control notified", "Ms. Asha K., ICN") + ckRow("hsRc4", false, "Treating doctors informed for 3 exposed patients", "A-05, A-06 (dressing set) · Imran Qureshi (suture set, ER)") + ckRow("hsRc5", false, "Incident report filed", "Linked to the quality module") + "</div>", { icon: "clipboard" });
      return { kind: "drawer", size: "lg", title: L("Load", "الدورة", "लोड") + " " + id + " · " + L("recall", "استدعاء", "रिकॉल"), body: body,
        foot: ui.btn("Print recall list", { act: "toast", arg: "Recall list printed", icon: "printer" }) + (done ? "" : ui.btn(L("Confirm recall actions", "تأكيد إجراءات الاستدعاء", "रिकॉल कार्रवाई पुष्टि करें"), { cls: "dng", act: "hsdone", arg: "recall_" + id + "|Recall recorded · infection control and doctors notified", icon: "check" })) };
    }
    var items = C.sets.filter(function (x) { return x[4].indexOf(id) >= 0; });
    body += ui.card(L("Contents", "المحتويات", "सामग्री"), items.length ? '<div class="lst">' + items.map(function (x) { return ui.li(avi("box"), esc(x[0] + " · " + x[1]), esc(x[2]), ui.pill("mute", x[3], "dot")); }).join("") + "</div>" : '<div class="small muted">' + n(y.items) + " sets · list from steriliser scan</div>", { icon: "box" });
    var canRel = y.bi === "pass" || (y.bi === "pending" && y.ci === "pass");
    return { kind: "drawer", title: L("Load", "الدورة", "लोड") + " " + id, body: body,
      foot: y.st === "released" ? ui.btn("Close", { cls: "ghost", act: "close" }) : ui.btn(L("Release load", "إطلاق الدورة", "लोड रिलीज़ करें"), { cls: "pri", disabled: !canRel, act: "toast", arg: "Load " + id + " released to sterile store", icon: "check" }) };
  });

  /* ---- hospops.dietary ---- */
  function prefFor(p) { if (!NB.gcc()) return p; return p === "Non-veg" ? "Halal" : p === "Veg · Jain" || p === "Veg" ? "Vegetarian" : p; }
  function dietRows() {
    var out = [];
    NB.D.beds.forEach(function (w) { w.beds.forEach(function (b) { var d = D.diet.orders[b.no]; if (b.st === "occ" && d) out.push({ bed: b.no, ward: w.ward, name: b.p, age: b.age, dx: b.dx, diet: d[0], tex: d[1], al: d[2], pref: d[3], note: d[4] }); }); });
    D.diet.extra.forEach(function (x) { out.push({ bed: x[0], ward: "General Ward A", name: x[1], diet: x[2], tex: x[3], al: x[4], pref: x[5], note: x[6], nbm: true }); });
    return out;
  }
  NB.screen("hospops.dietary", function (c) {
    var rows = dietRows(), ram = R().ramadan && NB.v("hsRam", true);
    var h = ui.header(L("Dietary & kitchen", "التغذية والمطبخ", "आहार और रसोई"), "Diet orders come from admission and ward orders · the kitchen sees counts and labels, the ward sees what each bed gets · " + esc(R().diet),
      ui.btn(L("Print tray labels", "طباعة ملصقات الصواني", "ट्रे लेबल प्रिंट"), { act: "toast", arg: "21 tray labels printed with bed, diet and allergy", icon: "printer" }) + ui.btn(L("Diet order", "طلب غذاء", "आहार ऑर्डर"), { cls: "pri", ov: "dietorder", arg: "A-02", icon: "plus" }));
    h += '<div class="grid g4 kpis">' + ui.kpi(n(21), L("Inpatients on diet orders", "مرضى بطلبات غذاء", "आहार ऑर्डर वाले मरीज़"), "All wards", "faint") + ui.kpi(n(13), L("Therapeutic diets", "أنظمة علاجية", "चिकित्सीय आहार"), "Diabetic, cardiac, renal, enteral", "faint") +
      ui.kpi(n(2), L("Allergy flags", "تنبيهات حساسية", "एलर्जी फ़्लैग"), "Egg · sulfonamide (drug, not food)", "critc") + ui.kpi(n(1), L("Nil by mouth", "صيام قبل العملية", "निल बाय माउथ"), "A-08 pre-op since 06:00", "warnc") + "</div>";
    if (R().ramadan) h += ui.card("", ui.toggle("hsRam", "Ramadan schedule", "Suhoor 03:45 and iftar at sunset (17:42) for patients the doctor has cleared to fast; insulin and drug timings follow the meal plan.", true), { cls: ram ? "hl" : "" });
    var tbl = ui.table([{ h: "Bed" }, { h: "Patient" }, { h: "Diet · texture" }, { h: "Notes" }, { h: "Allergy" }, { h: "Preference" }, { h: "Next meal" }], rows.map(function (r) {
      var nm = r.nbm ? ui.pill("warn", "NBM", "stop") : /Enteral/.test(r.diet) ? ui.pill("info", "Feed running", "refresh") : ram && r.pref !== "—" && r.bed === "A-06" ? ui.pill("sub", "Iftar 17:42", "moon") : ui.pill("acc", "Mid-morning 10:30", "clockh");
      return { cells: ['<span class="tok">' + r.bed + "</span>", ui.cellp('<span dir="auto">' + esc(r.name) + "</span>", esc(r.ward)), ui.cellp(esc(r.diet), esc(r.tex)), '<span class="small">' + esc(r.note) + "</span>", r.al ? ui.pill("crit", r.al, "alertfill") : '<span class="faint">' + L("None", "لا يوجد", "कोई नहीं") + "</span>", esc(prefFor(r.pref)), nm], ov: "dietorder", arg: r.bed, cls: r.al ? "crit" : "" };
    }), { min: 980 });
    var rounds = ui.card(L("Meal rounds", "جولات الوجبات", "भोजन राउंड"), '<div class="tl">' + (ram ? [["Suhoor", "03:45", "done", "6 fasting patients served"]] : []).concat(D.diet.rounds).map(function (r) {
      return '<div class="ev"><span class="dotc ' + (r[2] === "done" ? "ok" : r[2] === "live" ? "acc" : "") + '">' + ui.icon(r[2] === "done" ? "check" : r[2] === "live" ? "truck" : "clockh", "sm") + '</span><div class="bd"><b>' + esc(r[0]) + " · " + n(r[1]) + "</b><small>" + esc(r[3] || "") + "</small></div></div>";
    }).join("") + (ram ? '<div class="ev"><span class="dotc">' + ui.icon("moon", "sm") + '</span><div class="bd"><b>Iftar · ' + n("17:42") + "</b><small>Dates, water, soup first · 6 trays</small></div></div>" : "") + "</div>", { icon: "clockh" });
    var max = Math.max.apply(null, D.diet.prod.map(function (p) { return p[1]; }));
    var prod = ui.card(L("Kitchen production · lunch", "إنتاج المطبخ · الغداء", "रसोई उत्पादन · दोपहर"), '<div class="stack s8">' + D.diet.prod.map(function (p) { return '<div class="row nw"><span class="fl small">' + esc(p[0]) + '</span><span class="num small" style="width:24px;text-align:end">' + p[1] + '</span><div style="width:45%">' + ui.bar(p[1] / max * 100) + "</div></div>"; }).join("") + "</div>" +
      '<div class="small faint" style="margin-top:10px">Allergy trays are plated separately, sealed and labelled in red.</div>', { icon: "leaf" });
    return h + tbl + '<div class="grid g2">' + rounds + prod + "</div>";
  });
  NB.overlay("dietorder", function (bed) {
    var r = dietRows().find(function (x) { return x.bed === bed; }) || dietRows()[0];
    var body = '<div class="row nw">' + ui.av(r.name, "lg") + '<div class="fl"><h2 class="h" dir="auto">' + esc(r.name) + '</h2><div class="small muted">' + esc(r.bed + " · " + r.ward + (r.dx ? " · " + r.dx : "")) + "</div></div></div>";
    if (r.al) body += ui.note("crit", "<b>Allergy:</b> " + esc(r.al) + (r.al === "Egg" ? " · egg-free tray, kitchen alerted" : " (drug allergy, shown for completeness)"));
    body += '<div class="fgrid">' + ui.field("Diet", r.diet, { id: "hs-dt", options: [r.diet, "Regular", "Soft", "Diabetic · 1,600 kcal", "Diabetic · 1,800 kcal", "Cardiac · 2 g sodium", "Renal · low K, low phosphate", "Clear liquids", "Full liquids", "Nil by mouth", "Enteral feed"] }) +
      ui.field("Texture (IDDSI level, example)", r.tex, { id: "hs-tx", options: [r.tex, "Regular (7)", "Soft & bite-sized (6)", "Minced & moist (5)", "Pureed (4)", "Liquid (thin, 0)"] }) +
      ui.field("Fluid restriction", r.bed === "A-02" ? "1.2 L/day" : "None", { id: "hs-fr" }) + ui.field("Preference", prefFor(r.pref), { id: "hs-pf" }) + ui.field("Notes for kitchen", r.note, { id: "hs-nt", full: true, area: true }) + "</div>";
    return { kind: "drawer", title: L("Diet order", "طلب الغذاء", "आहार ऑर्डर") + " · " + bed, body: body,
      foot: ui.btn(L("Ask dietitian", "طلب أخصائي تغذية", "डायटीशियन से पूछें"), { act: "toast", arg: "Dietitian review requested for " + bed, icon: "chat" }) + ui.btn(L("Save & notify kitchen", "حفظ وإبلاغ المطبخ", "सहेजें और रसोई को बताएँ"), { cls: "pri", act: "hsdone", arg: "diet_" + bed + "|Diet order updated · kitchen notified for the next meal", icon: "check" }) };
  });

  /* ---- hospops.stores ---- */
  var IST = { pending: ["warn", "Awaiting approval", "hourglass"], approved: ["acc", "Approved · to issue", "check"], partial: ["urg", "Partly issued", "alertcircle"], issued: ["ok", "Issued", "checkcircle"] };
  var FLOW = [["pr", "PR"], ["ap", "Approved"], ["po", "PO"], ["grn", "GRN"], ["inv", "Invoice match"], ["paid", "Paid"]];
  NB.screen("hospops.stores", function (c) {
    var S = D.stores, tab = NB.v("hsStTab", "indents");
    var h = ui.header(L("Stores & procurement", "المخازن والمشتريات", "स्टोर और ख़रीद"), "Central and sub-stores for consumables, linen, implants and housekeeping · medicines stay in the Pharmacy module",
      ui.btn(L("New purchase requisition", "طلب شراء جديد", "नई ख़रीद माँग"), { act: "toast", arg: "Purchase requisition form opened", icon: "cart" }) + ui.btn(L("New indent", "طلب صرف جديد", "नया इंडेंट"), { cls: "pri", ov: "indent", arg: "new", icon: "plus" }));
    h += '<div class="grid g4 kpis">' + ui.kpi(n(S.indents.filter(function (i) { return i.st !== "issued" && !isDone("ind_" + i.id); }).length), L("Open indents", "طلبات مفتوحة", "खुले इंडेंट"), "2 urgent (OT, ICU)", "warnc") +
      ui.kpi(n(S.stock.filter(function (s) { return s[5] === "crit" || s[5] === "warn"; }).length), L("Below reorder level", "تحت حد إعادة الطلب", "रीऑर्डर स्तर से नीचे"), "ET tube 7.5 critical", "critc") +
      ui.kpi(n(1), L("POs awaiting delivery", "أوامر بانتظار التسليم", "डिलीवरी बाक़ी PO"), "Gloves · due 18 Oct", "faint") + ui.kpi(NB.money(418500), L("Spend this month", "إنفاق هذا الشهر", "इस माह ख़र्च"), "Excl. " + esc(NB.mkt().tax) + " · 62% of budget", "faint") + "</div>";
    h += ui.tabs("hsStTab", [["indents", L("Department indents", "طلبات الأقسام", "विभागीय इंडेंट"), "clipboard"], ["purchase", L("Purchase: PR → PO → GRN", "الشراء", "ख़रीद"), "truck"], ["stock", L("Stock by store", "المخزون حسب المخزن", "स्टोर अनुसार स्टॉक"), "box"]], "indents");
    if (tab === "indents") {
      h += ui.table([{ h: "Indent" }, { h: "Department" }, { h: "Raised" }, { h: "Items", cls: "r" }, { h: "Status" }], S.indents.map(function (i) {
        var st = isDone("ind_" + i.id) ? IST.issued : IST[i.st];
        return { cells: ['<span class="tok">' + i.id + "</span>" + (i.urg ? " " + ui.pill("urg", "Urgent", "bolt") : ""), ui.cellp(esc(i.dept), esc(i.by)), esc(i.at), n(i.lines.length), ui.pill(st[0], st[1], st[2])], ov: "indent", arg: i.id, cls: i.urg && st !== IST.issued ? "urg" : "" };
      }), { min: 720 });
      h += ui.note("info", "Indents are approved by the department head, then issued from the central store against stock. Sub-stores (OT, ICU, ER, wards) keep par levels and are topped up automatically each night.");
    } else if (tab === "purchase") {
      h += ui.table([{ h: "Requisition" }, { h: "Items" }, { h: "Vendor" }, { h: "Value (excl. " + NB.mkt().tax + ")", cls: "r" }, { h: "Progress" }, { h: "Note" }], S.procure.map(function (p) {
        var idx = { pr: 0, po: 2, grn: 3, paid: 5 }[p[5]];
        var flow = '<div class="hs-flow">' + FLOW.map(function (f, i) { var cls = i < idx || p[5] === "paid" ? "done" : i === idx ? (p[5] === "grn" ? "bad" : "cur") : ""; return (i ? ui.icon("chevright", "xs flip") : "") + '<span class="hs-fs ' + cls + '">' + (cls === "done" ? ui.icon("check") : cls === "bad" ? ui.icon("alertcircle") : "") + esc(f[1]) + "</span>"; }).join("") + "</div>";
        return [ui.cellp('<span class="tok">' + p[0] + "</span>", esc(p[2])), esc(p[1]), esc(p[3]), NB.money(p[4]), flow, '<span class="small">' + esc(p[6]) + "</span>"];
      }), { min: 1180 });
      h += ui.note("info", "Three-way match: purchase order, goods received note and supplier invoice must agree before payment. " + esc(NB.mkt().tax) + " input credit is captured on the GRN" + (NB.gcc() ? "" : " with the supplier GSTIN") + ".");
    } else {
      var stf = NB.v("hsStore", "all"), stores = ["Central store", "OT sub-store", "ER sub-store", "Ward A sub-store", "OT implant store"];
      h += ui.seg("hsStore", [["all", L("All stores", "كل المخازن", "सभी स्टोर")]].concat(stores.map(function (s) { return [s, s]; })), "all");
      h += ui.table([{ h: "Item" }, { h: "Store" }, { h: "On hand", cls: "r" }, { h: "Reorder level", cls: "r" }, { h: "Used, 30 days", cls: "r" }, { h: "Days of cover", cls: "r" }, { h: "Status" }, { h: "" }], S.stock.filter(function (s) { return stf === "all" || s[1] === stf; }).map(function (s) {
        var cover = s[4] ? Math.round(s[2] / (s[4] / 30)) : 99, pl = { ok: ui.pill("ok", "OK", "check"), warn: ui.pill("warn", "Reorder", "alertcircle"), crit: ui.pill("crit", "Critical", "alertfill"), over: ui.pill("info", "Overstock", "layers") }[s[5]];
        return [esc(s[0]), esc(s[1]), n(s[2]), n(s[3]), n(s[4]), '<span class="num' + (cover < 7 ? " critc" : "") + '">' + cover + "</span>", pl, s[5] === "warn" || s[5] === "crit" ? ui.btn("Reorder", { cls: "sm", act: "toast", arg: "Purchase requisition raised for " + s[0], icon: "cart" }) : ""];
      }), { min: 900 });
    }
    return h;
  });
  NB.overlay("indent", function (id) {
    if (id === "new") return { kind: "drawer", title: L("New indent", "طلب صرف جديد", "नया इंडेंट"), body: '<div class="fgrid">' + ui.field("Department", "Emergency", { id: "hs-id", options: ["Emergency", "ICU", "OT complex", "General Ward A", "Paediatric Ward", "Housekeeping"] }) + ui.field("Needed by", "Today 14:00", { id: "hs-nb" }) + "</div>" +
      ui.table([{ h: "Item" }, { h: "Qty", cls: "r" }, { h: "In central store", cls: "r" }], [["Cervical collar, adult", n(10), n(22)], ["SAM splint", n(12), '<span class="num warnc">9</span>'], ["+ Add item (scan or search)", "", ""]], { min: 360 }) +
      ui.note("info", "Par-level suggestion: ER sub-store is below par for 3 items; they are pre-filled."), foot: ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn("Send for approval", { cls: "pri", act: "toast", arg: "Indent sent to ER head for approval", icon: "send" }) };
    var i = D.stores.indents.find(function (x) { return x.id === id; }), st = isDone("ind_" + id) ? IST.issued : IST[i.st];
    var short = 0;
    var rows = i.lines.map(function (l) {
      var issue = i.st === "pending" ? 0 : Math.min(l[2], l[3]); if (l[2] > l[3]) short++;
      return [esc(l[0]), n(l[1]), n(l[2]), '<span class="num' + (l[3] < l[2] ? " critc" : "") + '">' + l[3] + "</span>", '<span class="num">' + (i.st === "issued" ? l[2] : issue) + "</span>"];
    });
    var body = '<div class="row nw">' + avi("clipboard", "lg") + '<div class="fl"><h2 class="h">' + esc(i.dept) + '</h2><div class="small muted">' + esc(i.id + " · " + i.by + " · " + i.at) + "</div></div>" + ui.pill(st[0], st[1], st[2]) + "</div>" +
      ui.table([{ h: "Item" }, { h: "Requested", cls: "r" }, { h: "Approved", cls: "r" }, { h: "In stock", cls: "r" }, { h: "Issue now", cls: "r" }], rows, { min: 520 });
    if (short) body += ui.note("warn", short + " item" + (short > 1 ? "s" : "") + " short in the central store. Issue what is available and raise a purchase requisition for the rest; the department sees the back-order on its indent.");
    if (i.st === "pending") body += ui.note("info", "Waiting for the department head's approval (" + esc(i.dept) + "). Stores can see it but cannot issue yet.");
    var foot = i.st === "issued" || isDone("ind_" + id) ? ui.btn("Print issue slip", { act: "toast", arg: "Issue slip printed", icon: "printer" }) :
      i.st === "pending" ? ui.btn("Remind approver", { act: "toast", arg: "Reminder sent to the department head", icon: "bell" }) :
      (short ? ui.btn("Raise PR for shortfall", { act: "toast", arg: "PR-2215 raised for the shortfall", icon: "cart" }) : "") + ui.btn(L("Issue", "صرف", "जारी करें"), { cls: "pri", act: "hsdone", arg: "ind_" + id + "|" + id + " issued · stock moved to " + i.dept, icon: "send" });
    return { kind: "drawer", size: "lg", title: L("Indent", "طلب صرف", "इंडेंट") + " " + id, body: body, foot: foot };
  });

  /* ---- hospops.mrd ---- */
  var DSS = { draft: ["warn", "Draft by resident", "pen"], pending: ["urg", "Awaiting consultant", "hourglass"], signed: ["ok", "Signed", "checkcircle"] };
  var COD = { pending: ["mute", "Not coded", "dot"], query: ["warn", "Query to doctor", "chat"], coded: ["ok", "Coded", "checkcircle"] };
  var RQS = { preparing: ["info", "Preparing copy", "file"], review: ["acc", "MRD review", "eye"], legal: ["sub", "Legal review", "scale"], blocked: ["crit", "Document missing", "lock"], released: ["ok", "Released", "checkcircle"], consent: ["warn", "Consent pending", "hourglass"] };
  var REGS = { submitted: ["info", "Submitted", "send"], due: ["warn", "Due", "clockh"], registered: ["ok", "Registered", "checkcircle"], police: ["sub", "Police case", "shielduser"] };
  NB.screen("hospops.mrd", function (c) {
    var M = D.mrd, tab = NB.v("hsMrdTab", "ds");
    var h = ui.header(L("Medical records (MRD)", "السجلات الطبية", "मेडिकल रिकॉर्ड"), "Discharge summaries, clinical coding (" + esc(R().icd) + "), release of records and statutory birth & death registers",
      ui.btn(L("Record requests", "طلبات السجلات", "रिकॉर्ड अनुरोध"), { act: "hsset", arg: "hsMrdTab|req", icon: "share" }) + ui.btn(L("Log a request", "تسجيل طلب", "अनुरोध दर्ज करें"), { cls: "pri", ov: "mrdrequest", arg: "MRQ-296", icon: "plus" }));
    h += '<div class="grid g4 kpis">' + ui.kpi(n(M.ds.filter(function (d) { return d[5] !== "signed"; }).length), L("Summaries not signed", "ملخصات غير موقعة", "बिना हस्ताक्षर सारांश"), "1 older than 48 h", "warnc") +
      ui.kpi(n(M.ds.filter(function (d) { return d[7] !== "coded"; }).length), L("Not yet coded", "غير مرمّزة", "कोडिंग बाक़ी"), "1 query to doctor", "warnc") + ui.kpi(n(M.req.filter(function (r) { return r.st !== "released"; }).length), L("Open record requests", "طلبات مفتوحة", "खुले अनुरोध"), "Response time: " + esc(R().reqSla), "faint") +
      ui.kpi(n(1), L("Birth reports due", "تقارير ولادة مستحقة", "जन्म रिपोर्ट देय"), esc(R().birth.split(" · ")[1] || "per authority"), "warnc") + "</div>";
    h += ui.tabs("hsMrdTab", [["ds", L("Summaries & coding", "الملخصات والترميز", "सारांश और कोडिंग"), "file"], ["req", L("Record requests", "طلبات السجلات", "रिकॉर्ड अनुरोध"), "share"], ["reg", L("Birth & death registers", "سجلات المواليد والوفيات", "जन्म और मृत्यु रजिस्टर"), "scroll"], ["ret", L("Retention", "الاحتفاظ", "प्रतिधारण"), "lock"]], "ds");
    if (tab === "ds") {
      h += ui.table([{ h: "IP no." }, { h: "Patient" }, { h: "Discharged" }, { h: "Consultant" }, { h: "Summary" }, { h: "Days", cls: "r" }, { h: "Coding · " + R().icd }], M.ds.map(function (d) {
        var s = isDone("ds_" + d[0]) ? DSS.signed : DSS[d[5]], cd = COD[d[7]];
        return [n(d[0]), ui.cellp(esc(d[1]), esc(d[2])), esc(d[3]), esc(d[4]), ui.pill(s[0], s[1], s[2]) + (d[5] === "pending" && !isDone("ds_" + d[0]) ? " " + ui.btn("Remind", { cls: "sm ghost", act: "hsdone", arg: "ds_" + d[0] + "|Reminder sent to " + d[4] + "|1|keep", icon: "bell" }) : ""), '<span class="num' + (d[6] >= 2 && d[5] !== "signed" ? " critc" : "") + '">' + d[6] + "</span>",
          ui.cellp(ui.pill(cd[0], cd[1], cd[2]), esc(d[8]))];
      }), { min: 1000 });
      h += '<div class="grid g2">' + ui.card(L("Coding query", "استفسار الترميز", "कोडिंग प्रश्न"), '<div class="small" style="margin-bottom:10px"><b>IP-26-1181 · Ramaswamy K.</b> · The summary says “sepsis” but the culture grew <i>Klebsiella pneumoniae</i>. Can the principal diagnosis be coded as A41.5 (sepsis due to other Gram-negative organisms) instead of A41.9?</div>' +
        '<div class="btns">' + ui.btn("Send to Dr. Vikram Shah", { cls: "sm pri", act: "toast", arg: "Query sent to the doctor's inbox", icon: "send" }) + ui.btn("Code as written", { cls: "sm", act: "toast", arg: "Coded A41.9 as written", icon: "check" }) + "</div>", { icon: "chat" }) +
        ui.card(L("Discharge summary rules", "قواعد ملخص الخروج", "डिस्चार्ज सारांश नियम"), '<div class="hs-ck">' + stRow("ok", "Summary handed to the patient at discharge", "Also in the patient app and on WhatsApp") + stRow("ok", "Consultant signature within 24 h", "Escalates to the medical superintendent after 48 h") + stRow("info", "Coded within 3 working days", "Needed for insurance claims and statistics") + "</div>", { icon: "shieldcheck" }) + "</div>";
    } else if (tab === "req") {
      h += ui.table([{ h: "Request" }, { h: "Requested by" }, { h: "Patient" }, { h: "Scope" }, { h: "Consent / basis" }, { h: "Fee", cls: "r" }, { h: "Due" }, { h: "Status" }], M.req.map(function (r) {
        var s = isDone("mrq_" + r.id) ? RQS.released : RQS[r.st];
        return { cells: ['<span class="tok">' + r.id + "</span>", '<div class="row nw">' + ui.icon(r.whoI, "sm faint") + "<span>" + esc(r.who) + "</span></div>", '<span dir="auto">' + esc(r.pid ? NB.pname(NB.pat(r.pid)) : r.name) + "</span>", '<span class="small">' + esc(r.what) + "</span>", '<span class="small">' + esc(r.consent) + "</span>", r.fee ? NB.money(r.fee) : '<span class="faint">' + L("No fee", "بدون رسوم", "शुल्क नहीं") + "</span>", esc(r.due), ui.pill(s[0], s[1], s[2])], ov: "mrdrequest", arg: r.id, cls: r.st === "blocked" ? "crit" : "" };
      }), { min: 1180 });
      h += ui.note("info", "Patients can also download their own records in the app. Requests from insurers, courts and police are released only with consent or a valid legal instrument, are limited to what is asked for, and every release is logged and visible to the patient.");
    } else if (tab === "reg") {
      var birth = ui.card(L("Birth register", "سجل المواليد", "जन्म रजिस्टर") + " · " + esc(R().birth), ui.table([{ h: "Entry" }, { h: "Child" }, { h: "Born" }, { h: "Sex" }, { h: "Weight", cls: "r" }, { h: "Type" }, { h: "Statutory report" }], M.births.map(function (b) {
        var s = isDone("birth_" + b[0]) ? REGS.submitted : REGS[b[7]];
        return [n(b[0]), ui.cellp(esc(b[1]), esc(b[6])), esc(b[2]), esc(b[3]), n(b[4]), esc(b[5]), ui.cellp(ui.pill(s[0], s[1], s[2]), esc(b[8])) + (b[7] === "due" && !isDone("birth_" + b[0]) ? " " + ui.btn("Submit", { cls: "sm pri", act: "hsdone", arg: "birth_" + b[0] + "|Birth report submitted for " + b[1] + "|1|keep", icon: "send" }) : "")];
      }), { min: 900 }), { icon: "baby" });
      var death = ui.card(L("Death register", "سجل الوفيات", "मृत्यु रजिस्टर") + " · " + esc(R().death), ui.table([{ h: "Entry" }, { h: "Deceased" }, { h: "Date & time" }, { h: "Cause of death (I a–b, II)" }, { h: "Certified by" }, { h: "Statutory report" }], M.deaths.map(function (d) {
        var s = REGS[d[5]];
        return [n(d[0]), esc(d[1]), esc(d[2]), '<span class="small">' + esc(d[3]) + "</span>", esc(d[4]), ui.cellp(ui.pill(s[0], s[1], s[2]), esc(d[6]))];
      }), { min: 900 }), { icon: "file" });
      h += birth + death + exampleNote("Cause of death follows the WHO international certificate format (part I chain, part II contributing).");
    } else {
      h += ui.card(L("Retention schedule", "جدول الاحتفاظ", "प्रतिधारण अनुसूची") + " · " + esc(NB.mkt().name), ui.table([{ h: "Record type" }, { h: "Keep for" }], R().ret.map(function (x) { return [esc(x[0]), esc(x[1])]; }), { min: 420 }) +
        '<div class="small faint" style="margin-top:10px">Records past retention are flagged for review, never deleted automatically. Records under legal hold (court cases, police cases, complaints) are excluded from purge.</div>', { icon: "lock" });
      h += exampleNote();
    }
    return h;
  });
  NB.overlay("mrdrequest", function (id) {
    var r = D.mrd.req.find(function (x) { return x.id === id; }) || D.mrd.req[0], s = isDone("mrq_" + r.id) ? RQS.released : RQS[r.st], nm = r.pid ? NB.pname(NB.pat(r.pid)) : r.name;
    var legal = /Court|Police/.test(r.who);
    var body = '<div class="row nw">' + avi(r.whoI, "lg") + '<div class="fl"><h2 class="h">' + esc(r.who) + '</h2><div class="small muted">' + esc(r.id) + " · " + L("for", "لـ", "के लिए") + ' <span dir="auto">' + esc(nm) + "</span></div></div>" + ui.pill(s[0], s[1], s[2]) + "</div>";
    body += ui.card(L("Request", "الطلب", "अनुरोध"), '<dl class="hs-kv"><dt>Scope</dt><dd>' + esc(r.what) + "</dd><dt>Basis</dt><dd>" + esc(r.consent) + "</dd><dt>Due</dt><dd>" + esc(r.due) + " · " + esc(R().reqSla) + "</dd><dt>Fee</dt><dd>" + (r.fee ? NB.money(r.fee) + " · certified copy" : "No fee") + "</dd></dl>", { icon: "clipboard" });
    body += ui.card(L("Checks before release", "تحققات قبل الإصدار", "जारी करने से पहले जाँच"), '<div class="hs-ck">' +
      ckRow("hsMq1_" + id, !/Relative|Police/.test(r.who), "Requester identity verified", r.who.indexOf("Patient") === 0 ? "OTP to registered phone + ID" : "Letterhead, authorisation and contact verified") +
      ckRow("hsMq2_" + id, r.st !== "consent" && r.st !== "blocked", legal ? "Valid legal instrument on file" : "Patient consent or authorisation on file", esc(r.consent)) +
      ckRow("hsMq3_" + id, true, "Scope limited to what is asked", "Only the listed documents and dates") + ckRow("hsMq4_" + id, false, "Sensitive sections reviewed", "Third-party information, mental health and HIV status released only if specifically covered") +
      (legal ? ckRow("hsMq5_" + id, r.st === "legal", "Legal / medical superintendent sign-off", "Certified copy · original stays in MRD") : "") + "</div>", { icon: "shieldcheck" });
    body += '<div class="fgrid">' + ui.field("Delivery", r.who.indexOf("Patient") === 0 ? "Secure link in the patient app" : legal ? "Certified paper copy, hand delivered" : "Secure link (expires in 7 days)", { id: "hs-dl", options: ["Secure link in the patient app", "Secure link (expires in 7 days)", "Certified paper copy, hand delivered", "Registered post"] }) + ui.field("Notes", "", { id: "hs-mn", ph: "Visible in the audit log" }) + "</div>";
    body += ui.note("info", "Every release is written to the audit log and appears in the patient's “who saw my records” list.");
    var foot = ui.btn(L("Decline with reason", "رفض مع السبب", "कारण सहित अस्वीकार"), { cls: "ghost", act: "toast", arg: "Declined · requester informed with reason", icon: "close" });
    foot += r.st === "consent" ? ui.btn(L("Request patient consent", "طلب موافقة المريض", "मरीज़ की सहमति माँगें"), { cls: "pri", act: "toast", arg: "Consent request sent to the patient on WhatsApp", icon: "send" }) :
      r.st === "blocked" ? ui.btn(L("Ask for official letter", "طلب خطاب رسمي", "आधिकारिक पत्र माँगें"), { cls: "pri", act: "toast", arg: "Letter template sent to the police station", icon: "send" }) :
      r.st === "released" || isDone("mrq_" + r.id) ? ui.btn("Close", { act: "close" }) : ui.btn(L("Release", "إصدار", "जारी करें"), { cls: "pri", act: "hsdone", arg: "mrq_" + r.id + "|" + r.id + " released · logged and visible to the patient", icon: "send" });
    return { kind: "drawer", size: "lg", title: L("Record request", "طلب سجل", "रिकॉर्ड अनुरोध") + " " + r.id, body: body, foot: foot };
  });

  /* ---- hospops.roster ---- */
  var SH = { M: ["Morning 07–15", "صباحي", "सुबह"], E: ["Evening 15–23", "مسائي", "शाम"], N: ["Night 23–07", "ليلي", "रात"], OC: ["On call", "مناوب", "ऑन-कॉल"], L: ["Leave", "إجازة", "छुट्टी"], OFF: ["Off", "راحة", "छुट्टी"] };
  // Effective shift after approved swaps: returns [code, changedBySwap]
  function effShift(nm, di, code) {
    if (isDone("swap_SW-40") && di === 6) { if (nm === "Sr. Divya K.") return ["OFF", true]; if (nm === "Br. Akash P.") return ["E", true]; }
    return [code, false];
  }
  NB.screen("hospops.roster", function (c) {
    var RS = D.roster, dep = NB.v("hsDep", "er"), staff = RS.staff.filter(function (s) { return s[0] === dep; }), need = RS.need[dep];
    var h = ui.header(L("Duty roster", "جدول المناوبات", "ड्यूटी रोस्टर"), "Nursing · week of 12–18 Oct · shifts M 07–15, E 15–23, N 23–07 (pattern is configurable, e.g. 2 × 12 h) · published to staff phones",
      ui.btn(L("Auto-fill gaps", "ملء الفجوات تلقائيًا", "गैप स्वतः भरें"), { act: "toast", arg: "Suggested: float nurse Br. Akash P. for Sun Morning (ER) · within hours limit", icon: "sparkle" }) + ui.btn(L("Publish next week", "نشر الأسبوع القادم", "अगला सप्ताह प्रकाशित करें"), { cls: "pri", act: "toast", arg: "Roster for 19–25 Oct published · 48 staff notified", icon: "send" }));
    var gaps = 0, cov = ["M", "E", "N"].map(function (sh, si) {
      return RS.days.map(function (d, di) { var cnt = staff.filter(function (s) { return effShift(s[1], di, s[3][di])[0] === sh; }).length; if (cnt < need[si]) gaps++; return cnt; });
    });
    h += '<div class="grid g4 kpis">' + ui.kpi(n(gaps), L("Coverage gaps this week", "فجوات التغطية", "कवरेज गैप"), gaps ? cov.map(function (row, si) { return row.map(function (cn, di) { return cn < need[si] ? RS.days[di][0] + " " + ["M", "E", "N"][si] : ""; }).filter(Boolean).join(", "); }).filter(Boolean).join(", ") : L("Fully covered", "مغطى بالكامل", "पूरा कवरेज"), gaps ? "critc" : "okc") + ui.kpi(n(RS.swaps.filter(function (s) { return !isDone("swap_" + s.id); }).length), L("Swap requests", "طلبات التبديل", "स्वैप अनुरोध"), RS.swaps.some(function (s) { return !s.ok && !isDone("swap_" + s.id); }) ? "1 breaks a duty-hours rule" : "", "warnc") +
      ui.kpi(n(1), L("Leave to approve", "إجازات للموافقة", "छुट्टी स्वीकृति"), "Sr. Lissy A. · 26–30 Oct", "faint") + ui.kpi(n("14 h"), L("Overtime this week", "ساعات إضافية", "इस सप्ताह ओवरटाइम"), "ER 10 h · ICU 4 h", "faint") + "</div>";
    h += '<div class="row sb">' + ui.seg("hsDep", RS.depts.map(function (d) { return [d[0], d[1]]; }), "er") + '<div class="hs-leg">' + ["M", "E", "N", "L", "OFF"].map(function (k) { return '<span class="hs-sh ' + k + '" style="cursor:default">' + k + '</span><span class="small muted">' + L(SH[k][0], SH[k][1], SH[k][2]) + "</span>"; }).join("") + "</div></div>";
    var th = '<tr><th class="n">' + L("Staff", "الموظف", "स्टाफ़") + "</th>" + RS.days.map(function (d, i) { return '<th class="' + (i === RS.today ? "today" : "") + '">' + d[0] + " " + d[1] + "</th>"; }).join("") + "</tr>";
    var body = staff.map(function (s) {
      return '<tr><td class="n"><b>' + esc(s[1]) + "</b><small>" + esc(s[2]) + "</small></td>" + s[3].map(function (code, di) {
        var e = effShift(s[1], di, code), cd = e[0];
        return '<td class="' + (di === RS.today ? "today" : "") + '"><button class="hs-sh ' + cd + (e[1] ? " SW" : "") + '" data-act="toast" data-arg="' + esc(s[1] + " · " + RS.days[di][0] + " " + RS.days[di][1] + " · " + SH[cd][0] + (e[1] ? " (swap SW-40)" : "")) + '" title="' + esc(SH[cd][0]) + '">' + cd + "</button></td>";
      }).join("") + "</tr>";
    }).join("");
    body += '<tr class="sec"><td colspan="8">' + L("Coverage (scheduled / required)", "التغطية", "कवरेज") + "</td></tr>";
    body += ["M", "E", "N"].map(function (sh, si) {
      return '<tr class="cov"><td class="n">' + L(SH[sh][0], SH[sh][1], SH[sh][2]) + "</td>" + cov[si].map(function (cnt, di) {
        var gap = cnt < need[si]; return '<td class="' + (di === RS.today ? "today" : "") + '"><span class="hs-cov ' + (gap ? "gap" : "ok") + '">' + ui.icon(gap ? "alertfill" : "check") + cnt + "/" + need[si] + "</span></td>";
      }).join("") + "</tr>";
    }).join("");
    h += '<div class="hs-ros"><table><thead>' + th + "</thead><tbody>" + body + "</tbody></table></div>";
    var swaps = ui.card(L("Swap requests", "طلبات التبديل", "स्वैप अनुरोध"), '<div class="lst">' + RS.swaps.map(function (s) {
      var dn = isDone("swap_" + s.id);
      return ui.li(avi("refresh"), esc(s.a + " ↔ " + s.b), esc(s.give + " for " + s.take + " · " + s.reason), dn ? ui.pill("ok", "Approved", "checkcircle") : s.ok ? ui.pill("ok", "Rules pass", "check") : ui.pill("crit", "Rule breach", "alertfill"), { ov: "shiftswap", arg: s.id });
    }).join("") + "</div>", { icon: "refresh" });
    var leave = ui.card(L("Leave", "الإجازات", "छुट्टी"), '<div class="lst">' + RS.leave.map(function (l) { return ui.li(ui.av(l[0]), esc(l[0]), esc(l[1] + " · " + l[2]), l[3] === "approved" ? ui.pill("ok", "Approved", "checkcircle") : ui.btn("Approve", { cls: "sm", act: "toast", arg: "Leave approved · roster gap check: covered by float pool", icon: "check" })); }).join("") + "</div>", { icon: "calendar" });
    var oc = ui.card(L("On call today", "المناوبون اليوم", "आज ऑन-कॉल"), '<div class="lst">' + RS.oncall.map(function (o) { return ui.li(ui.av(o[1]), esc(o[0]), esc(o[1] + " · " + o[2]), '<span class="pill mute">' + ui.icon("phone") + '<span class="ltr">' + o[3] + "</span></span>"); }).join("") + "</div>", { icon: "phone" });
    return h + '<div class="grid g3">' + swaps + leave + oc + "</div>";
  });
  NB.overlay("shiftswap", function (id) {
    var s = D.roster.swaps.find(function (x) { return x.id === id; }) || D.roster.swaps[0];
    var rules = s.ok ? [["ok", "Rest between shifts ≥ 11 h", "Both staff"], ["ok", "Maximum continuous duty 12 h", ""], ["ok", "Weekly hours ≤ 48", "Divya 40 h · Akash 44 h"], ["ok", "Skill mix: one ACLS-certified nurse per ER shift", "Kept on both days"], ["ok", "No overtime cost", ""]]
      : [["crit", "Maximum continuous duty 12 h", s.rule], ["ok", "Weekly hours ≤ 48", "Rekha 40 h · Teena 48 h"], ["warn", "Skill mix: one ACLS-certified nurse per ER shift", "Sun Evening would lose the ER in-charge"], ["ok", "Rest between shifts ≥ 11 h for Sr. Rekha", ""]];
    var body = '<div class="hs-2p"><div class="hs-box"><div class="eyebrow">' + L("Gives away", "يتنازل عن", "देता है") + '</div><b dir="auto">' + esc(s.a) + '</b><span class="small muted">' + esc(s.give) + '</span></div><div class="hs-box"><div class="eyebrow">' + L("Takes", "يأخذ", "लेता है") + '</div><b dir="auto">' + esc(s.b) + '</b><span class="small muted">' + esc(s.take) + "</span></div></div>" +
      '<div class="small muted">' + L("Reason", "السبب", "कारण") + ": " + esc(s.reason) + "</div>" +
      ui.card(L("Rule check", "فحص القواعد", "नियम जाँच"), '<div class="hs-ck">' + rules.map(function (r) { return stRow(r[0], esc(r[1]), esc(r[2])); }).join("") + "</div>", { icon: "shieldcheck" }) +
      (s.ok ? "" : ui.note("ai", "Suggestion: Br. Joel Mathew is off on Sun 18 and ACLS-certified; giving the shift to him passes every rule."));
    return { kind: "modal", title: L("Shift swap", "تبديل المناوبة", "शिफ़्ट स्वैप") + " · " + s.id, body: body,
      foot: ui.btn(L("Decline", "رفض", "अस्वीकार"), { cls: "ghost", act: "toast", arg: "Swap declined · both staff notified with the rule", icon: "close" }) + ui.btn(L("Approve", "موافقة", "स्वीकृत करें"), { cls: "pri", disabled: !s.ok, act: "hsdone", arg: "swap_" + s.id + "|Swap approved · roster updated and both phones notified", icon: "check" }) };
  });

  /* ---- hospops.fleet ---- */
  var UST = { transport: ["urg", "Transporting", "truck"], scene: ["crit", "At scene", "alertfill"], available: ["ok", "Available", "checkcircle"], transfer: ["info", "Patient transport", "arrowright"], maint: ["mute", "Maintenance", "settings"] };
  function fleetMap(units) {
    var tone = { transport: "urg", scene: "crit", available: "ok", transfer: "info", maint: "mute" };
    return '<div class="hs-map"><svg viewBox="0 0 480 280" role="img" aria-label="Map placeholder with ambulance positions">' +
      '<rect class="blk" x="20" y="20" width="120" height="80" rx="8"/><rect class="blk" x="160" y="20" width="140" height="70" rx="8"/><rect class="blk" x="330" y="20" width="130" height="90" rx="8"/><rect class="blk" x="20" y="130" width="90" height="120" rx="8"/><rect class="blk" x="300" y="150" width="70" height="100" rx="8"/><rect class="blk" x="390" y="140" width="70" height="60" rx="8"/>' +
      '<path class="rd" d="M0 115 H480"/><path class="rd" d="M150 0 V280"/><path class="rd2" d="M150 210 C220 200 260 250 480 240"/><path class="rd2" d="M320 0 C310 60 330 120 380 130"/>' +
      '<rect class="hos" x="200" y="128" width="70" height="46" rx="8"/><text x="235" y="166" text-anchor="middle">' + L("Hospital", "المستشفى", "अस्पताल") + "</text>" +
      units.map(function (u) { return '<circle class="mk ' + tone[u.st] + '" cx="' + u.x + '" cy="' + u.y + '" r="8"/><text class="lb" x="' + (u.x + 12) + '" y="' + (u.y + 4) + '">' + esc(u.id.replace("AMB-", "")) + "</text>"; }).join("") +
      '</svg><span class="cap">' + L("Map placeholder · live GPS from the vehicle tablet", "عنصر نائب للخريطة", "मैप प्लेसहोल्डर") + "</span></div>";
  }
  NB.screen("hospops.fleet", function (c) {
    var F = D.fleet;
    var h = ui.header(L("Ambulance fleet", "أسطول الإسعاف", "एम्बुलेंस बेड़ा"), "Hospital-owned ambulances · public emergency calls go to " + esc(R().ems) + " · our crews pre-alert the ER from the vehicle tablet",
      ui.btn(L("ER pre-arrivals", "ما قبل الوصول", "ईआर प्री-अराइवल"), { go: "er.ambulance", icon: "bolt" }) + ui.btn(L("New dispatch", "إرسال جديد", "नया डिस्पैच"), { cls: "pri", ov: "dispatch", arg: "new", icon: "plus" }));
    var av = F.units.filter(function (u) { return u.st === "available"; }).length;
    h += '<div class="grid g4 kpis">' + ui.kpi(n(av + "/" + F.units.length), L("Available now", "متاح الآن", "अभी उपलब्ध"), "BLS + neonatal at base", av ? "okc" : "critc") + ui.kpi(n(3), L("On a mission", "في مهمة", "मिशन पर"), "2 emergency · 1 transport", "faint") +
      ui.kpi(n("9 min"), L("P1 response (median today)", "زمن الاستجابة", "P1 रिस्पॉन्स"), "Call to on-scene · target example ≤ 15 min", "okc") + ui.kpi(n(1), L("Off road", "خارج الخدمة", "सेवा से बाहर"), "AMB-05 · back 18 Oct", "warnc") + "</div>";
    var list = ui.card(L("Units", "الوحدات", "यूनिट"), '<div class="lst">' + F.units.map(function (u) {
      var s = UST[u.st];
      return ui.li(avi("truck"), '<span class="ltr">' + esc(u.id) + "</span> · " + esc(u.kind), esc(u.where) + " · " + esc(u.crew), '<div class="stack s4" style="align-items:flex-end">' + ui.pill(s[0], s[1], s[2]) + '<span class="small faint">O₂ ' + n(u.o2 + "%") + " · " + L("fuel", "وقود", "ईंधन") + " " + n(u.fuel + "%") + "</span></div>");
    }).join("") + "</div>", { icon: "truck" });
    h += '<div class="grid g2">' + fleetMap(F.units) + list + "</div>";
    h += ui.card(L("Dispatch log · today", "سجل الإرسال", "डिस्पैच लॉग"), ui.table([{ h: "Call" }, { h: "Time" }, { h: "Unit" }, { h: "Nature" }, { h: "Priority" }, { h: "Dispatched → scene → left → hospital" }, { h: "Outcome" }], F.log.map(function (l) {
      return ['<span class="tok">' + l[0] + "</span>", n(l[1]), '<span class="tok">' + l[2] + "</span>", '<span dir="auto">' + esc(l[3]) + "</span>", l[4] === "P1" ? ui.pill("crit", "P1", "alertfill") : l[4] === "P2" ? ui.pill("urg", "P2", "bolt") : ui.pill("mute", "P3", "calendar"), '<span class="num small ltr" style="white-space:nowrap">' + l[5].join(" → ") + "</span>", '<span class="small">' + esc(l[6]) + "</span>"];
    }), { min: 1000 }), { icon: "list" });
    h += ui.card(L("Maintenance & readiness", "الصيانة والجاهزية", "रखरखाव और तैयारी"), '<div class="grid g2" style="gap:0 24px"><div class="hs-ck">' + F.maint.slice(0, 3).map(function (m) { return stRow(m[3], esc(m[0] + " · " + m[1]), esc(m[2])); }).join("") + '</div><div class="hs-ck">' + F.maint.slice(3).map(function (m) { return stRow(m[3], esc(m[0] + " · " + m[1]), esc(m[2])); }).join("") + "</div></div>", { icon: "settings" });
    return h + exampleNote("Emergency numbers: " + esc(R().ems) + ".");
  });
  NB.overlay("dispatch", function (arg) {
    var pre = arg === "prealert";
    if (pre) return { kind: "modal", title: L("Phone pre-alert", "إنذار هاتفي مسبق", "फ़ोन प्री-अलर्ट"), body: '<div class="fgrid">' + ui.field("From", R().ems.split(" ·")[0] + " control room", { id: "hs-pf" }) + ui.field("Vehicle", "", { id: "hs-pv", ph: "Registration or call sign" }) + ui.field("ETA (min)", "12", { id: "hs-pe" }) + ui.field("Bay to hold", "A3", { id: "hs-pb", options: ["A3", "A5", "O4", "R3 (after CT)"] }) + "</div>" +
      '<div class="small muted">ATMIST</div><div class="fgrid">' + ["Age / sex", "Time of onset", "Mechanism / complaint", "Injuries / findings", "Signs (vitals)", "Treatment given"].map(function (x, i) { return ui.field(x, "", { id: "hs-at" + i }); }).join("") + "</div>",
      foot: ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn("Save pre-alert", { cls: "pri", act: "toast", arg: "Pre-alert added to the ER board · bay held", icon: "check" }) };
    var p = NB.v("hsDp", "P1");
    var body = '<div class="fgrid">' + ui.field("Caller", "Meena R. (daughter)", { id: "hs-dc" }) + ui.field("Phone", NB.mkt().phone + " 98450 77812", { id: "hs-dph" }) + ui.field("Pickup location", "Prestige Shantiniketan, Tower 4, flat 1102", { id: "hs-dl", full: true }) + ui.field("Complaint", "Elderly man collapsed, breathing, not talking", { id: "hs-dcm", full: true }) + "</div>" +
      '<div class="row sb"><span class="small muted">' + L("Priority", "الأولوية", "प्राथमिकता") + "</span>" + ui.seg("hsDp", [["P1", "P1 · life-threatening"], ["P2", "P2 · urgent"], ["P3", "P3 · transport"]], "P1") + "</div>" +
      ui.card(L("Suggested unit", "الوحدة المقترحة", "सुझाई गई यूनिट"), '<div class="lst">' + ui.li(avi("truck"), "AMB-02 · BLS · at base", "2.4 km · ETA 7 min · EMT Rafiq, driver Sunil", ui.pill("ok", L("Nearest", "الأقرب", "निकटतम"), "check")) +
        (p === "P1" ? ui.li(avi("bolt"), "ALS intercept: AMB-03 after current scene", "Clears in about 15 min · or request " + R().ems.split(" ·")[0], ui.pill("warn", "Backup", "alertcircle")) : "") + "</div>", { icon: "map" }) +
      '<div class="hs-ck">' + ckRow("hsDpre", p === "P1", L("Pre-alert the ER on dispatch", "إنذار الطوارئ عند الإرسال", "डिस्पैच पर ईआर को प्री-अलर्ट"), "Appears on the ER ambulance screen with ETA") + ckRow("hsDcpr", p === "P1", "Give CPR instructions to the caller", "Scripted, in the caller's language") + "</div>";
    return { kind: "drawer", title: L("New dispatch", "إرسال جديد", "नया डिस्पैच"), body: body,
      foot: ui.btn("Cancel", { cls: "ghost", act: "close" }) + ui.btn(L("Dispatch AMB-02", "إرسال AMB-02", "AMB-02 भेजें"), { cls: "pri", act: "toast", arg: "AMB-02 dispatched · crew tablet and ER notified", icon: "send" }) };
  });
})();
