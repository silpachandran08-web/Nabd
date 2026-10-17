/* Front desk module: reception screens (arrivals board, booking requests, appointments, patients,
   self check-in & kiosk, waiting-room display, WhatsApp inbox) and their drawers:
   walk-in, check-in, delay broadcast, queue move, patient file, appointment, merge, templates. */
(function () {
  "use strict";
  var ui = NB.ui, L = NB.L, esc = ui.esc;

  /* =====================================================================
     Module styles. Prefixed fd-, built only from nb.css custom properties.
     Font families are never set here so Arabic / Devanagari inherit.
     ===================================================================== */
  (function () {
    if (document.getElementById("fd-css")) return;
    var s = document.createElement("style");
    s.id = "fd-css";
    s.textContent = [
      ".fd-tt{display:inline-flex;align-items:center;gap:6px;height:26px;padding:0 10px;border-radius:999px;background:var(--acc-tint);color:var(--acc-300);border:1px solid var(--acc-line);font-weight:600;font-size:11.5px;line-height:1;white-space:nowrap}",
      ".fd-lnk{cursor:pointer}.fd-lnk:hover{color:var(--acc-300);text-decoration:underline;text-underline-offset:3px}",
      ".fd-tokb{font-weight:700;font-size:18px;line-height:22px;color:var(--acc);direction:ltr;unicode-bidi:isolate;font-variant-numeric:tabular-nums;min-width:48px}",
      /* mobile card list */
      ".fd-mlist{display:flex;flex-direction:column;gap:10px;min-width:0}",
      ".fd-mc{background:var(--sheen),var(--s1);border:1px solid var(--b-sub);border-radius:var(--r-lg);padding:14px;display:flex;flex-direction:column;gap:10px;min-width:0}",
      ".fd-mc[data-ov]{cursor:pointer}.fd-mc.crit{box-shadow:inset 3px 0 0 var(--crit)}.fd-mc.urg{box-shadow:inset 3px 0 0 var(--urg)}",
      ".fd-mc-h{display:flex;align-items:flex-start;gap:10px;min-width:0}",
      ".fd-mc-h b{display:block;font-weight:600;font-size:14px;line-height:20px}.fd-mc-h small{display:block;color:var(--tx3);font-size:12px;line-height:16px}",
      ".fd-mc-m{display:flex;flex-wrap:wrap;gap:6px 14px;font-size:12px;color:var(--tx2);align-items:center}",
      ".fd-mc-m>span{display:inline-flex;align-items:center;gap:5px}",
      ".fd-mc-a{display:flex;gap:8px;flex-wrap:wrap}.fd-mc-a .btn{flex:1}",
      /* booking requests */
      ".fd-mode{display:flex;gap:16px;align-items:flex-start;flex-wrap:wrap}",
      ".fd-mode .fd-mi{width:44px;height:44px;border-radius:12px;background:var(--acc-tint2);color:var(--acc);display:grid;place-items:center;flex:none}",
      ".fd-mstats{display:flex;gap:8px 20px;flex-wrap:wrap;font-size:12px;color:var(--tx2)}.fd-mstats b{color:var(--tx);font-weight:600}",
      ".fd-req{display:grid;grid-template-columns:minmax(0,1.5fr) minmax(0,1fr) 170px;gap:16px 24px;align-items:start}",
      ".fd-req.crit{border-color:rgba(255,77,79,.4);box-shadow:inset 3px 0 0 var(--crit)}",
      ".fd-req-n{font-size:15px;font-weight:600;line-height:22px;display:block}",
      ".fd-quote{margin:8px 0 6px;font-size:13.5px;color:var(--tx);line-height:19px}",
      ".fd-req-slot b{display:block;font-size:15px;font-weight:600;margin-top:2px}.fd-req-slot small{display:block;color:var(--tx3);font-size:12px}",
      ".fd-sugg{margin-top:10px;padding:8px 10px;border-radius:10px;background:var(--acc-tint);border:1px solid var(--acc-line);font-size:12px;line-height:16px;color:var(--tx2)}",
      ".fd-sugg b{display:inline;color:var(--acc-300);font-size:12.5px}",
      ".fd-sla b{display:block;font-size:22px;line-height:28px;font-weight:600;font-variant-numeric:tabular-nums;margin:2px 0 6px}",
      ".fd-sla small{display:block;color:var(--tx3);font-size:11.5px;margin-top:6px;line-height:15px}",
      ".fd-sla.warn b{color:var(--warn)}.fd-sla.crit b{color:var(--crit)}",
      ".fd-req-act{grid-column:1/-1;display:flex;flex-wrap:wrap;gap:8px;align-items:center;border-top:1px solid var(--div);padding-top:14px}",
      ".fd-req-act .grow{flex:1}",
      ".frame.tablet .fd-req{grid-template-columns:minmax(0,1fr) minmax(0,1fr)}.frame.tablet .fd-sla{grid-column:1/-1}",
      ".frame.mobile .fd-req{grid-template-columns:minmax(0,1fr);gap:12px}",
      ".frame.mobile .fd-req-act .btn{flex:1}",
      /* calendar */
      ".fd-calw{overflow-x:auto;border:1px solid var(--b-sub);border-radius:var(--r-lg);background:var(--s1)}",
      ".fd-cal{display:grid;min-width:780px}",
      ".fd-cal>div{border-top:1px solid var(--div);border-inline-start:1px solid var(--div);padding:5px;min-width:0}",
      ".fd-cal>.fd-ch{background:var(--s3);padding:10px;border-top:0;display:flex;gap:8px;align-items:flex-start}",
      ".fd-ch b{display:block;font-size:12.5px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
      ".fd-ch small{display:block;color:var(--tx3);font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
      ".fd-cal>.fd-tm{font-size:11.5px;color:var(--tx3);font-variant-numeric:tabular-nums;padding:10px 8px;border-inline-start:0;direction:ltr;text-align:center}",
      ".fd-cal>.fd-tm.now{color:var(--acc);font-weight:600}",
      ".fd-cal>.fd-now{box-shadow:inset 0 2px 0 var(--acc)}",
      ".fd-sl{border-radius:10px;padding:6px 8px;min-height:54px;height:100%;display:flex;flex-direction:column;gap:2px;cursor:pointer;border:1px solid var(--b-sub);background:var(--s2);min-width:0;text-align:start;width:100%;color:var(--tx)}",
      ".fd-sl:hover{border-color:var(--b-str)}",
      ".fd-sl b{font-weight:500;font-size:12.5px;line-height:16px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;display:block}",
      ".fd-sl small{color:var(--tx3);font-size:10.5px;line-height:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;display:flex;align-items:center;gap:4px}",
      ".fd-sl .ico{width:12px;height:12px}",
      ".fd-sl.done{opacity:.5}.fd-sl.cons{border-color:var(--acc-line);background:var(--acc-tint)}.fd-sl.cons small{color:var(--acc-300)}",
      ".fd-sl.arr{border-color:rgba(88,178,245,.4)}.fd-sl.arr small{color:var(--info)}",
      ".fd-sl.ok small{color:var(--ok)}",
      ".fd-sl.risk{border-color:rgba(232,179,60,.5);border-style:dotted}.fd-sl.risk small{color:var(--warn)}",
      ".fd-sl.ns{border-color:rgba(255,138,61,.5);box-shadow:inset 3px 0 0 var(--urg)}.fd-sl.ns small{color:var(--urg)}",
      ".fd-sl.nsd{opacity:.6;text-decoration:line-through}",
      ".fd-sl.req{border-style:dashed;border-color:rgba(180,140,240,.5);background:var(--sub-t)}.fd-sl.req small{color:var(--sub)}",
      ".fd-sl.freed{border-style:dashed;border-color:rgba(232,179,60,.6);background:var(--warn-t)}.fd-sl.freed small{color:var(--warn)}",
      ".fd-sl.free{background:transparent;border-style:dashed;border-color:var(--b-def);color:var(--tx3);align-items:center;justify-content:center;flex-direction:row;gap:6px;font-size:12px}",
      ".fd-sl.free:hover{border-color:var(--acc);color:var(--acc)}",
      ".fd-sl.blk{background:repeating-linear-gradient(135deg,var(--s2) 0 6px,var(--s1) 6px 12px);color:var(--tx3);justify-content:center}",
      ".fd-sl.lv{background:repeating-linear-gradient(135deg,var(--sub-t) 0 6px,transparent 6px 12px);border-color:rgba(180,140,240,.3);color:var(--sub);justify-content:center}",
      ".fd-sl.blk b,.fd-sl.lv b{font-size:11.5px;color:inherit}",
      ".fd-legend{display:flex;flex-wrap:wrap;gap:6px 14px;font-size:11.5px;color:var(--tx2);align-items:center}",
      ".fd-legend i{display:inline-block;width:14px;height:14px;border-radius:4px;border:1px solid var(--b-def);vertical-align:-3px;margin-inline-end:6px;background:var(--s2)}",
      ".fd-legend i.cons{background:var(--acc-tint);border-color:var(--acc-line)}.fd-legend i.free{background:transparent;border-style:dashed}",
      ".fd-legend i.blk{background:repeating-linear-gradient(135deg,var(--s3) 0 3px,var(--s1) 3px 6px)}.fd-legend i.lv{background:repeating-linear-gradient(135deg,var(--sub-t) 0 3px,transparent 3px 6px);border-color:rgba(180,140,240,.4)}",
      ".fd-legend i.risk{border-color:var(--warn);border-style:dotted}.fd-legend i.ns{border-color:var(--urg)}.fd-legend i.req{border-color:var(--sub);border-style:dashed;background:var(--sub-t)}",
      ".fd-mslot{display:grid;grid-template-columns:52px minmax(0,1fr);gap:8px;align-items:stretch}",
      ".fd-mslot .fd-tm{font-size:12px;color:var(--tx3);padding-top:8px;font-variant-numeric:tabular-nums;direction:ltr}",
      ".fd-chipsx{display:flex;gap:6px;overflow-x:auto;padding-bottom:4px}",
      /* waitlist / offers */
      ".fd-offer{border:1px dashed rgba(232,179,60,.55);background:var(--warn-t);border-radius:var(--r-md);padding:12px;display:flex;flex-direction:column;gap:8px}",
      ".fd-offer b{font-size:13.5px;font-weight:600}",
      /* check-in */
      ".fd-meth{display:flex;flex-direction:column;gap:8px}.fd-meth .row b{font-size:14px;font-weight:600}",
      ".fd-meth .fd-big{font-size:24px;line-height:30px;font-weight:600;font-variant-numeric:tabular-nums}",
      ".fd-kwrap{display:flex;flex-direction:column;gap:12px;align-items:stretch}",
      ".fd-kiosk{background:var(--bg-root);border:10px solid var(--s3);border-radius:28px;padding:22px;display:flex;flex-direction:column;gap:14px;min-height:520px;width:100%;max-width:440px;margin:0 auto;box-shadow:0 18px 48px rgba(0,0,0,.45)}",
      ".fd-kiosk .fd-kh{display:flex;align-items:center;gap:10px}.fd-kiosk .fd-kh b{flex:1;font-size:14px;font-weight:600}",
      ".fd-ktitle{font-size:24px;line-height:31px;font-weight:600;margin:6px 0 0}.fd-ksub{color:var(--tx2);font-size:14px;margin:0}",
      ".fd-kbig{display:flex;align-items:center;gap:14px;padding:16px 18px;border-radius:var(--r-xl);background:var(--s2);border:1px solid var(--b-def);cursor:pointer;width:100%;text-align:start;color:var(--tx);min-height:74px}",
      ".fd-kbig b{display:block;font-size:17px;line-height:22px;font-weight:600}.fd-kbig small{display:block;font-size:12.5px;opacity:.75;line-height:17px}",
      ".fd-kbig.pri{background:var(--acc-grad);color:var(--on-acc);border-color:transparent;box-shadow:var(--acc-glow)}",
      ".fd-kbig.sel{border-color:var(--acc);background:var(--acc-tint)}.fd-kbig.dim{opacity:.55;cursor:default}",
      ".fd-kbig .ico{width:28px;height:28px}",
      ".fd-kphone{height:62px;border-radius:16px;background:var(--s1);border:1px solid var(--acc-line);display:flex;align-items:center;justify-content:center;font-size:26px;font-weight:600;letter-spacing:1px;direction:ltr;font-variant-numeric:tabular-nums}",
      ".fd-keypad{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;direction:ltr}",
      ".fd-keypad button{height:54px;border-radius:14px;background:var(--s2);border:1px solid var(--b-def);font-size:21px;font-weight:600;cursor:pointer;color:var(--tx)}",
      ".fd-ktok{font-size:76px;line-height:1;font-weight:700;color:var(--acc);text-align:center;direction:ltr;font-variant-numeric:tabular-nums;margin:6px 0}",
      ".fd-kfoot{margin-top:auto;display:flex;align-items:center;justify-content:space-between;gap:8px;color:var(--tx3);font-size:12px;flex-wrap:wrap}",
      ".fd-steps{display:flex;gap:6px;flex-wrap:wrap}",
      ".fd-poster{display:flex;gap:14px;align-items:center;padding:14px;border-radius:var(--r-md);background:var(--s2);border:1px solid var(--b-def)}",
      ".fd-qr{width:64px;height:64px;border-radius:10px;background:var(--tx);color:var(--bg-root);display:grid;place-items:center;flex:none}",
      ".fd-qr .ico{width:44px;height:44px;stroke-width:2}",
      /* waiting-room TV */
      ".fd-tv{flex:1;display:flex;flex-direction:column;gap:18px;padding:26px 30px;min-height:700px;background:radial-gradient(900px 420px at 0% 0%,var(--acc-tint),transparent 70%),var(--bg-root)}",
      ".fd-tv-h{display:flex;align-items:center;gap:16px;flex-wrap:wrap}",
      ".fd-tv-h .mark{width:48px;height:48px;border-radius:14px;background:var(--acc-grad);color:var(--on-acc);display:grid;place-items:center;flex:none}",
      ".fd-tv-h .fl b{display:block;font-size:22px;line-height:28px;font-weight:600}.fd-tv-h .fl small{display:block;color:var(--tx2);font-size:15px;line-height:20px}",
      ".fd-clock{text-align:end}.fd-clock b{display:block;font-size:40px;line-height:44px;font-weight:600;font-variant-numeric:tabular-nums;direction:ltr}.fd-clock small{color:var(--tx2);font-size:14px}",
      ".fd-l2{display:block;color:var(--tx2);font-size:.82em;line-height:1.3}",
      ".fd-tv .fd-l2[dir=rtl]{text-align:left}",
      ".fd-l2[lang=ar]{font-family:var(--font-ar)}.fd-l2[lang=hi]{font-family:var(--font-hi)}",
      ".fd-tv-call{display:flex;align-items:center;gap:24px;padding:18px 28px;border-radius:var(--r-2xl);background:var(--acc-grad);color:var(--on-acc);flex-wrap:wrap}",
      ".fd-tv-call .lbl{font-size:18px;font-weight:600;line-height:24px}.fd-tv-call .lbl .fd-l2{color:var(--on-acc);opacity:.8}",
      ".fd-tv-call .tk{font-size:64px;line-height:1;font-weight:700;direction:ltr;font-variant-numeric:tabular-nums;animation:fdBlink 2.4s ease-in-out infinite}",
      "@keyframes fdBlink{0%,100%{opacity:1}50%{opacity:.62}}",
      ".fd-tv-call .fd-l2{color:var(--on-acc);opacity:.75}",
      ".fd-tv-room .dn small{min-height:34px}",
      ".fd-tv-call .to{font-size:30px;font-weight:600;line-height:36px}",
      ".fd-tv-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:14px}",
      ".fd-tv-room{background:var(--sheen),var(--s1);border:1px solid var(--b-sub);border-radius:var(--r-xl);padding:18px;display:flex;flex-direction:column;gap:10px;min-width:0}",
      ".fd-tv-room.call{border-color:var(--acc);box-shadow:var(--acc-glow)}",
      ".fd-tv-room .rm{display:flex;align-items:center;justify-content:space-between;gap:8px;font-size:13px;font-weight:600;color:var(--tx2);text-transform:uppercase;letter-spacing:.6px}",
      ".fd-tv-room .dn{font-size:16px;font-weight:600;line-height:21px}.fd-tv-room .dn small{display:block;color:var(--tx3);font-size:12.5px;font-weight:500}",
      ".fd-tv-now{font-size:58px;line-height:1;font-weight:700;color:var(--acc);font-variant-numeric:tabular-nums;direction:ltr;unicode-bidi:isolate;margin:4px 0}",
      ".fd-tv-now.off{font-size:22px;line-height:30px;color:var(--tx2)}",
      ".fd-tv-lab{font-size:11.5px;font-weight:600;letter-spacing:.6px;text-transform:uppercase;color:var(--tx3)}",
      ".fd-tv-next{display:flex;gap:6px;flex-wrap:wrap}",
      ".fd-tv-next span{height:34px;padding:0 12px;border-radius:10px;background:var(--s3);display:inline-flex;align-items:center;font-size:18px;font-weight:600;font-variant-numeric:tabular-nums;direction:ltr;color:var(--tx)}",
      ".fd-tv-late{display:inline-flex;align-items:center;gap:6px;font-size:13px;color:var(--warn);font-weight:600}",
      ".fd-tv-row{display:flex;gap:14px;flex-wrap:wrap}",
      ".fd-tv-note{flex:1;min-width:260px;display:flex;gap:12px;align-items:center;padding:14px 18px;border-radius:var(--r-lg);border:1px solid rgba(232,179,60,.35);background:var(--warn-t);font-size:15px;line-height:21px}",
      ".fd-tv-note.info{border-color:rgba(88,178,245,.3);background:var(--info-t)}",
      ".fd-tv-note .ico{width:28px;height:28px;flex:none}",
      ".fd-tv-f{display:flex;gap:18px;align-items:center;flex-wrap:wrap;color:var(--tx3);font-size:13px;margin-top:auto}",
      ".fd-tv-f .fd-qr{width:56px;height:56px}.fd-tv-f .fd-qr .ico{width:38px;height:38px}",
      "@media (max-width:760px){.fd-tv{padding:16px;min-height:0;gap:14px}.fd-tv-now{font-size:44px}.fd-tv-call .tk{font-size:44px}.fd-tv-call{padding:14px 18px;gap:12px}.fd-clock b{font-size:28px;line-height:32px}.fd-tv-h .fl b{font-size:17px;line-height:22px}.fd-tv-h .mark{width:40px;height:40px}.fd-tv-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.fd-tv-room{padding:14px}.fd-tv-room .dn small{min-height:0}}",
      ".fd-dh b,.fd-dh small{display:block}",
      /* WhatsApp inbox */
      ".fd-inbox{display:grid;grid-template-columns:320px minmax(0,1fr);border:1px solid var(--b-sub);border-radius:var(--r-lg);overflow:hidden;background:var(--s1);height:760px}",
      ".frame.tablet .fd-inbox{grid-template-columns:280px minmax(0,1fr)}",
      ".frame.mobile .fd-inbox{grid-template-columns:minmax(0,1fr);height:auto;min-height:620px}",
      ".fd-tl{border-inline-end:1px solid var(--b-sub);display:flex;flex-direction:column;min-height:0;min-width:0}",
      ".fd-tl-h{padding:12px;border-bottom:1px solid var(--b-sub);display:flex;flex-direction:column;gap:10px}",
      ".fd-tl-list{overflow-y:auto;flex:1;min-height:0}",
      ".fd-th{display:flex;gap:10px;padding:12px 14px;border-bottom:1px solid var(--div);cursor:pointer;align-items:flex-start;min-width:0}",
      ".fd-th:hover{background:var(--s2)}.fd-th.on{background:var(--acc-tint);box-shadow:inset 3px 0 0 var(--acc)}",
      ".fd-th .fl b{display:block;font-size:13.5px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
      ".fd-th .fl small{display:block;font-size:12px;color:var(--tx3);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:1px}",
      ".fd-th .fd-thr{display:flex;flex-direction:column;align-items:flex-end;gap:5px;flex:none}",
      ".fd-th .fd-thr small{font-size:11px;color:var(--tx3)}",
      ".fd-th .pill{height:19px;font-size:9px;padding:0 6px}",
      ".fd-unr{min-width:20px;height:20px;border-radius:999px;background:var(--wa);color:var(--on-acc);font-size:11px;font-weight:700;line-height:20px;text-align:center;padding:0 6px}",
      ".fd-cv{display:flex;flex-direction:column;min-height:0;min-width:0}",
      ".fd-cv-h{display:flex;align-items:center;gap:10px;padding:10px 14px;border-bottom:1px solid var(--b-sub);flex-wrap:wrap}",
      ".fd-cv-h .fl{min-width:160px}.fd-cv-h .fl b{display:block;font-size:14.5px;font-weight:600}.fd-cv-h .fl small{display:block;color:var(--tx3);font-size:12px}",
      ".fd-ctx{display:flex;gap:8px;flex-wrap:wrap;padding:8px 14px;border-bottom:1px solid var(--div);align-items:center}",
      ".fd-botbar{padding:0 14px;border-bottom:1px solid var(--div)}.fd-botbar .tog{padding:8px 0}",
      ".fd-cv .wa{flex:1;min-height:240px}",
      ".frame.mobile .fd-cv .wa{min-height:360px}",
      ".fd-auth{display:flex;align-items:center;gap:4px;font-size:9.5px;font-weight:600;letter-spacing:.3px;text-transform:uppercase;line-height:13px;margin-bottom:2px;color:var(--tx3)}",
      ".fd-auth.bot{color:var(--sync)}.fd-auth.me{color:var(--acc-300)}.fd-auth.tpl{color:var(--sub)}",
      ".fd-tick{margin-inline-start:4px;letter-spacing:-2px}.fd-tick.read{color:var(--info)}.fd-tick.fail{color:var(--crit);letter-spacing:0;font-weight:700}",
      ".fd-wbtns{display:flex;flex-direction:column;gap:4px;max-width:82%;min-width:180px}",
      ".fd-wbtns.out{align-self:flex-end}.fd-wbtns.in{align-self:flex-start}",
      ".fd-wbtns span{background:var(--wa-in);border-radius:8px;text-align:center;color:var(--info);padding:8px 10px;font-size:13px;font-weight:500}",
      ".fd-wbtns.out span{background:var(--wa-out)}",
      ".fd-fail{align-self:flex-end;font-size:11.5px;color:var(--crit);display:flex;gap:10px;align-items:center}",
      ".fd-comp{padding:10px 14px;border-top:1px solid var(--b-sub);display:flex;flex-direction:column;gap:10px;background:var(--s1)}",
      ".fd-comp-in{display:flex;gap:8px;align-items:center}",
      ".fd-comp-in input{flex:1;min-width:0;height:42px;border-radius:999px;background:var(--s3);border:1px solid var(--b-def);padding:0 16px;outline:0}",
      ".fd-comp-in input:focus{border-color:var(--acc)}",
      ".fd-comp .chip{height:28px;font-size:12px}",
      ".fd-var{background:var(--acc-tint2);color:var(--acc-300);border-radius:6px;padding:0 4px;font-weight:600}",
      ".fd-prev{background:var(--wa-bg);border-radius:var(--r-md);padding:14px;display:flex;flex-direction:column;gap:6px}",
      ".fd-prev .bub{max-width:100%}",
      /* misc */
      ".fd-cmp{display:grid;grid-template-columns:120px minmax(0,1fr) minmax(0,1fr);gap:0;border:1px solid var(--b-sub);border-radius:var(--r-md);overflow:hidden;font-size:12.5px}",
      ".fd-cmp>div{padding:9px 10px;border-top:1px solid var(--div);min-width:0;overflow-wrap:anywhere}",
      ".fd-cmp>.h{background:var(--s3);font-weight:600;font-size:11px;letter-spacing:.5px;text-transform:uppercase;color:var(--tx3);border-top:0}",
      ".fd-cmp>.k{color:var(--tx3)}",
      ".frame.mobile .fd-cmp{grid-template-columns:90px minmax(0,1fr) minmax(0,1fr)}",
      ".fd-famcard .li .t b{white-space:normal}",
      ".fd-search{display:flex;align-items:center;gap:10px;height:46px;background:var(--s1);border:1px solid var(--b-def);border-radius:var(--r-md);padding:0 14px;flex:1;min-width:220px}",
      ".drawer .fd-search,.modal .fd-search{flex:none}",
      ".fd-search:focus-within{border-color:var(--acc);box-shadow:0 0 0 3px var(--acc-tint)}",
      ".fd-search input{flex:1;min-width:0;background:transparent;border:0;outline:0;font-size:14px}",
      ".fd-search .ico{color:var(--tx3)}",
      ".pill.fd-wrap{white-space:normal;height:auto;min-height:22px;padding:3px 9px;text-transform:none;letter-spacing:0;font-size:11.5px;line-height:15px;font-weight:500}",
      ".fd-big{font-size:28px;line-height:34px;font-weight:600;font-variant-numeric:tabular-nums}"
    ].join("\n");
    document.head.appendChild(s);
  })();

  /* =====================================================================
     Sample data for the front desk (extends the shared NB.D records).
     x-ids are people who are not in the shared patient master yet.
     ===================================================================== */
  var FD = NB.D.frontdesk = { people: {} };
  [["x1", "Deepa Krishnan", "ديبا كريشنان", 27, "F"], ["x2", "Rahul Verma", "راهول فيرما", 49, "M"], ["x3", "Aaron Thomas", "آرون توماس", 4, "M"],
    ["x4", "Imtiaz Ahmed", "امتياز أحمد", 61, "M"], ["x5", "Lakshmi Iyer", "لاكشمي آيير", 38, "F"], ["x6", "Kiran Joshi", "كيران جوشي", 7, "M"],
    ["x7", "Meera Joshi", "ميرا جوشي", 5, "F"], ["x8", "Ritu Agarwal", "ريتو أغاروال", 36, "F"], ["x9", "Vivek Menon", "فيفيك مينون", 44, "M"],
    ["x10", "Sameer Kulkarni", "سمير كولكارني", 31, "M"], ["x11", "Anjali Desai", "أنجالي ديساي", 26, "F"], ["x12", "Harish Gowda", "هاريش غودا", 55, "M"],
    ["x13", "Nikhil Rao", "نيخيل راو", 39, "M"], ["x14", "Pooja Hegde", "بوجا هيغدي", 29, "F"], ["x15", "Aisha Siddiqui", "عائشة صديقي", 33, "F"],
    ["x16", "Omar Farooq", "عمر فاروق", 47, "M"], ["x17", "Divya Shetty", "ديفيا شيتي", 24, "F"], ["x18", "Ravi Kumar", "رافي كومار", 62, "M"],
    ["x19", "Sunita Patil", "سونيتا باتيل", 58, "F"], ["x20", "Ayaan Khan", "أيان خان", 3, "M"], ["x21", "Myra Rao", "ميرا راو", 2, "F"],
    ["x22", "Joseph Mathew", "جوزيف ماثيو", 49, "M"], ["x23", "Anita Menon", "أنيتا مينون", 53, "F"], ["x24", "Rohan Menon", "روهان مينون", 19, "M"],
    ["x25", "Imran Khan", "عمران خان", 38, "M"], ["x26", "Lakshmi Nair", "لاكشمي ناير", 40, "F"], ["x27", "Faisal Ali", "فيصل علي", 36, "M"],
    ["x28", "Sara Thomas", "سارة توماس", 32, "F"], ["x29", "Neha Joshi", "نيها جوشي", 34, "F"], ["x30", "Mohd Ali", "محمد علي", 67, "M"],
    ["x31", "Shreya Bhat", "شريا بهات", 30, "F"], ["x32", "Farah Naaz", "فرح ناز", 27, "F"], ["x33", "Karthik Subramanian", "كارتيك سوبرامانيان", 41, "M"],
    ["x34", "Zainab Hussain", "زينب حسين", 35, "F"], ["x35", "Vihaan Shetty", "فيهان شيتي", 8, "M"], ["x36", "Swati Kamath", "سواتي كامات", 31, "F"],
    ["x37", "Nandini Rao", "نانديني راو", 30, "F"], ["x38", "Yusuf Hussain", "يوسف حسين", 6, "M"], ["x39", "Maryam Hussain", "مريم حسين", 4, "F"],
    ["x40", "Manoj Pai", "مانوج باي", 52, "M"], ["x41", "Rekha Iyengar", "ريكا آيينغار", 45, "F"]
  ].forEach(function (r, i) { FD.people[r[0]] = { id: r[0], name: r[1], ar: r[2], age: r[3], sex: r[4], lang: i % 3 === 1 ? "hi" : "en", phone: "9" + (8440 + i * 7) + " " + (10000 + (i * 7919) % 89999) }; });

  // Doctors' names and specialties for the bilingual lobby display.
  FD.docL2 = {
    d1: ["डॉ. मीरा अय्यर", "त्वचा रोग", "الجلدية"], d2: ["डॉ. विक्रम शाह", "सामान्य चिकित्सा", "الطب العام"], d3: ["डॉ. निशा भट", "बाल रोग", "طب الأطفال"],
    d4: ["डॉ. ख़ालिद अल-सईद", "हृदय रोग", "أمراض القلب"], d5: ["डॉ. फ़ातिमा रहमान", "स्त्री रोग", "النساء والولادة"]
  };

  // Day view: 20-minute slots from 09:00. Codes: d done · c in consult · a arrived · b confirmed · r unconfirmed (at risk)
  // n not arrived (no-show candidate) · q booking request pending · k cancelled (freed) · f free · w walk-in buffer · x:label blocked · l leave
  FD.times = ["09:00", "09:20", "09:40", "10:00", "10:20", "10:40", "11:00", "11:20", "11:40", "12:00", "12:20", "12:40"];
  FD.nowIdx = 3; // "now" is 10:05 in the sample day
  FD.cal = {
    d1: ["d:p1", "c:p2", "a:p5", "a:p9", "b:x17", "w", "r:x14", "b:x31", "q:x1", "b:x32", "f", "x:Photo follow-ups"],
    d2: ["d:p6", "n:x16", "a:p8", "b:x13", "r:x12", "w", "k:x9", "f", "b:x10", "q:x2", "f", "x:Lunch"],
    d3: ["d:x21", "a:p4", "a:p7", "b:x6", "b:x7", "f", "x:Well-baby clinic", "x:Well-baby clinic", "f", "b:x20", "r:x35", "f"],
    d4: ["d:x18", "d:x19", "x:Break · back 10:40", "x:Break · back 10:40", "x:Break · back 10:40", "b:p10", "b:x22", "f", "f", "b:x33", "f", "f"],
    d5: ["d:x15", "d:x11", "c:x36", "b:x26", "r:x23", "f", "l", "l", "l", "l", "l", "l"]
  };
  FD.slotNote = { "d3:3": "Siblings · back-to-back", "d3:4": "Siblings · back-to-back", "d2:1": "Booked 09:20 · not arrived", "d2:6": "Cancelled on WhatsApp 09:48" };

  FD.waitlist = [
    { id: "w1", who: "x5", want: "Any GP · today before 13:00", since: "08:10", src: "WhatsApp", reason: "Fever, body ache", match: true },
    { id: "w2", who: "x24", want: "Dr. Vikram Shah · today or tomorrow", since: "Yesterday 18:20", src: "Nabd Connect", reason: "Sports injury review", match: true },
    { id: "w3", who: "x3", want: "Dr. Nisha Bhat · earlier than 16:20", since: "10:02", src: "WhatsApp", reason: "Cough and fever (guardian: Sara)", match: false },
    { id: "w4", who: "x41", want: "Dermatology · next week, mornings", since: "15 Oct", src: "Desk", reason: "Mole check", match: false }
  ];

  // Extra fields for the shared booking requests, plus three more requests.
  FD.reqx = {
    r1: { ar: "ديبا كريشنان", sex: "F", type: "New consult", newPt: true, why: "New patient · new consult", alt: "Today 11:50", altWhy: "Dr. Meera has a free gap after T-21", sla: 15, modes: ["review", "hybrid"], lang: "English" },
    r2: { ar: "راهول فيرما", sex: "M", type: "Follow-up", why: "Follow-up, but Dr. Vikram's morning cap is full (12 of 12)", alt: "Today 11:20", altWhy: "Free slot; realistic with his 15-min delay", sla: 15, modes: ["review", "hybrid", "auto"], lang: "Hindi" },
    r3: { disp: "Sara Thomas", ar: "سارة توماس", sex: "F", forWhom: "Aaron, 4 · son", type: "New consult · child", newPt: true, why: "New patient · minor booked by a guardian", alt: "Today 12:40", altWhy: "Earlier sick-child slot with Dr. Nisha", sla: 15, modes: ["review", "hybrid"], lang: "English", age: 32 },
    r4: { ar: "امتياز أحمد", sex: "M", type: "New consult", newPt: true, triage: true, why: "Triage words in the reason: “chest discomfort”, age 61", alt: "Today 11:40", altWhy: "Dr. Khalid has a free slot today. Don't hold for tomorrow", sla: 10, modes: ["review", "hybrid", "auto"], lang: "English" }
  };
  FD.requests = [
    { id: "r5", name: "Nandini Rao", ar: "نانديني راو", sex: "F", phone: "99012 77310", d: "d1", slot: "Tomorrow 15:00", src: "Nabd Connect", reason: "Chemical peel (45 min)", new: false, age: 30, mins: 25,
      type: "Procedure", why: "Procedure · needs a 45-min block", alt: "Tomorrow 15:00–15:45", altWhy: "Block is free; Dr. Meera's procedure room available", sla: 60, modes: ["review", "hybrid"], lang: "English" },
    { id: "r6", name: "Zainab Hussain", ar: "زينب حسين", sex: "F", phone: "98804 61220", d: "d3", slot: "Today 12:00", src: "WhatsApp", reason: "Vaccination due, both children", new: false, age: 35, mins: 6, guardian: true,
      forWhom: "Yusuf, 6 & Maryam, 4", type: "Vaccination · 2 siblings", why: "Sibling booking needs 2 back-to-back slots", alt: "Today 12:40 + 13:00", altWhy: "Two adjacent slots are free after 12:20", sla: 15, modes: ["review", "hybrid"], lang: "Hindi" },
    { id: "r7", name: "Manoj Pai", ar: "مانوج باي", sex: "M", phone: "98455 30981", d: "d2", slot: "Tomorrow 09:20", src: "WhatsApp", reason: "BP and sugar review", new: false, age: 52, mins: 2,
      type: "Follow-up", why: "Clinic reviews every booking", alt: "Tomorrow 09:20", altWhy: "Requested slot is free", sla: 60, modes: ["review"], lang: "English" }
  ];

  FD.px = {
    p1: { dob: "12 Mar 1992", blood: "B+", natId: "7781", addr: "HSR Layout", last: "Today", lastDoc: "d1", visits: 6, dues: 0 },
    p2: { dob: "04 Jul 1994", blood: "O+", natId: "1123", addr: "Koramangala 4th Block", last: "Today", lastDoc: "d1", visits: 4, dues: 0 },
    p3: { dob: "21 Aug 1968", blood: "A+", natId: "3321", addr: "Jayanagar 9th Block", last: "29 Sep", lastDoc: "d2", visits: 14, dues: 0, fam: "F1" },
    p4: { dob: "02 Feb 2020", blood: "O+", natId: "—", addr: "BTM Layout", last: "18 Aug", lastDoc: "d3", visits: 9, dues: 0, fam: "F2", guardian: "x25" },
    p5: { dob: "15 Nov 1981", blood: "B-", natId: "0912", addr: "Indiranagar", last: "03 Oct", lastDoc: "d1", visits: 4, dues: 0, pkg: "Laser hair reduction · 3 of 6 used" },
    p6: { dob: "30 Jan 1997", blood: "AB+", natId: "5540", addr: "Ejipura", last: "Today", lastDoc: "d2", visits: 7, dues: 1200 },
    p7: { dob: "09 Jun 2014", blood: "A-", natId: "—", addr: "Domlur", last: "12 Sep", lastDoc: "d3", visits: 5, dues: 0, fam: "F3", guardian: "x26" },
    p8: { dob: "01 May 1959", blood: "O+", natId: "8817", addr: "Frazer Town", last: "26 Sep", lastDoc: "d2", visits: 22, dues: 0, fam: "F4", caregiver: "x27", dup: true },
    p9: { dob: "17 Dec 1984", blood: "B+", natId: "2290", addr: "Bellandur", last: "11 Jul", lastDoc: "d1", visits: 3, dues: 350 },
    p10: { dob: "08 Mar 1974", blood: "A+", natId: "4410", addr: "Olaya", last: "02 Sep", lastDoc: "d4", visits: 5, dues: 0 }
  };
  FD.families = {
    F1: { name: "Menon family", ar: "عائلة مينون", hi: "मेनन परिवार", head: "p3", members: [["p3", "Account holder"], ["x23", "Spouse"], ["x24", "Son"]] },
    F2: { name: "Khan family", ar: "عائلة خان", hi: "ख़ान परिवार", head: "x25", members: [["x25", "Account holder · guardian"], ["p4", "Daughter"], ["x20", "Son"]] },
    F3: { name: "Nair family", ar: "عائلة ناير", hi: "नायर परिवार", head: "x26", members: [["x26", "Account holder · guardian"], ["p7", "Son"]] },
    F4: { name: "Ali family", ar: "عائلة علي", hi: "अली परिवार", head: "p8", members: [["p8", "Account holder"], ["x27", "Caregiver · son abroad, scoped access"]] }
  };

  // Self check-ins that need a human (everything else confirms itself).
  FD.selfci = [
    { id: "ci1", who: "p9", tok: "T-21", via: "qr", time: "09:52", kind: "warn", issue: "Dues pending from last visit", amt: 350, fix: "Collect dues" },
    { id: "ci2", who: "p10", tok: "T-22", via: "wa", time: "09:58", kind: "urg", issue: "Outside geofence · 1.4 km away (limit 200 m)", fix: "Ask to tap again on arrival" },
    { id: "ci3", who: "x23", tok: "T-24", via: "kiosk", time: "10:01", kind: "info", issue: "Phone matches 3 family members. Kiosk picked Anita; confirm identity", fix: "Check year of birth" },
    { id: "ci4", who: "x31", tok: "T-25", via: "wa", time: "10:03", kind: "info", issue: "Arrived 57 min early for 11:00 with Dr. Meera", fix: "Offer the 10:40 walk-in buffer" },
    { id: "ci5", who: "x13", tok: "T-26", via: "geo", time: "10:04", kind: "warn", issue: "Insurance card expired 30 Sep · eligibility not confirmed", fix: "Re-check eligibility" }
  ];
  FD.autoci = [["p5", "qr", "09:31", "T-17"], ["p4", "wa", "09:24", "T-16"], ["p2", "qr", "09:12", "T-14"], ["p1", "wa", "08:55", "T-13"]];

  // WhatsApp threads. lastIn = minutes since the patient's last message (the 24 h customer-service window).
  FD.threads = [
    { id: "t1", who: "x1", phone: "98450 22110", lastIn: 3, unread: 2, mode: "human", wait: 3, ctx: ["bell", "Booking request · Today 11:40 · Dr. Meera Iyer", "reception.requests"], msgs: [
      { w: "pt", t: "Hi, I booked Dr. Meera for 11:40 today. Is it confirmed?", time: "10:01" },
      { w: "bot", t: "Thanks Deepa. Your request for Dr. Meera Iyer, today 11:40, is with our front desk. We'll confirm within 15 minutes.", time: "10:01", st: "read" },
      { w: "pt", t: "Also do I need to bring my old prescriptions?", time: "10:02" }] },
    { id: "t2", who: "x28", forWhom: "for Aaron, 4", phone: "98861 55102", lastIn: 12, unread: 1, mode: "human", wait: 12, handed: true, ctx: ["users", "Guardian · books for Aaron Thomas (4)", "reception.patients"], msgs: [
      { w: "pt", t: "Aaron has had 101°F fever since last night and a cough", time: "09:50" },
      { w: "bot", t: "Sorry to hear that. I can book Dr. Nisha Bhat (Paediatrics) for Aaron. Pick a time:", time: "09:50", st: "read", btns: ["Today 16:20", "Today 16:40", "Tomorrow 10:00"] },
      { w: "pt", t: "16:20. Can we come earlier if something opens up? He is quite uncomfortable", time: "09:53" },
      { w: "sys", t: "Assistant handed over to the front desk · patient asked for a person · 09:53" }] },
    { id: "t3", who: "x27", phone: "50 412 7781", intl: "+971", lastIn: 1335, unread: 0, mode: "human", wait: 0, ctx: ["shieldcheck", "Caregiver for Mohammed Ali · consent active until 31 Dec", "reception.patients"], msgs: [
      { w: "pt", t: "Assalamu alaikum. Can you send Dad's BP readings after today's visit? I'm in Dubai.", time: "Yesterday 11:50" },
      { w: "me", t: "Wa alaikum assalam. Yes, once Dr. Vikram signs today's note we'll send a secure link. Your father has approved sharing with you.", time: "Yesterday 12:02", st: "dlv" }] },
    { id: "t4", who: "p10", phone: "50 123 4567", lastIn: 25, unread: 0, mode: "bot", wait: 0, rtl: true, ctx: ["calendar", "Booked · 10:40 · Dr. Khalid Al-Saeed · token T-22", "reception.calendar"], msgs: [
      { w: "pt", t: "السلام عليكم، هل يمكن تقديم موعدي اليوم؟", time: "09:40", rtl: true },
      { w: "bot", t: "وعليكم السلام. موعدك مع د. خالد السعيد الساعة 10:40. الطبيب في استراحة حتى 10:40، لذلك لا يوجد موعد أبكر اليوم.", time: "09:40", st: "read", rtl: true, btns: ["إبقاء الموعد", "تغيير الموعد"] },
      { w: "pt", t: "إبقاء الموعد", time: "09:41", rtl: true },
      { w: "bot", t: "تم. سنرسل لك تنبيه «اخرج الآن» قبل دورك.", time: "09:41", st: "read", rtl: true }] },
    { id: "t5", who: "p9", phone: "98861 99001", lastIn: 40, unread: 0, mode: "human", wait: 0, ctx: ["wallet", "Dues ₹350 from 11 Jul visit", "reception.patients"], msgs: [
      { w: "pt", t: "Reached. Where do I go?", time: "09:25" },
      { w: "me", t: "Welcome Priya! You're checked in, token T-21. There's a small balance from your last visit; here's a secure payment link.", time: "09:26", st: "fail" }] },
    { id: "t6", who: "p1", phone: "98450 11223", lastIn: 1560, unread: 0, mode: "bot", wait: 0, ctx: ["calendar", "Next review due 31 Oct · Dr. Meera Iyer", "reception.calendar"], msgs: [
      { w: "pt", t: "Thank you, the cream is working", time: "Thu 08:10" },
      { w: "bot", t: "Glad to hear it, Kavya! Your next review is due on 31 Oct. We'll remind you a day before.", time: "Thu 08:10", st: "read" }] },
    { id: "t7", who: "p5", phone: "97400 55667", lastIn: 300, unread: 0, mode: "bot", wait: 0, optout: true, ctx: ["layers", "Package · laser 3 of 6", "reception.patients"], msgs: [
      { w: "tpl", tpl: "appt_reminder_24h", t: "Reminder: your laser session with Dr. Meera Iyer is tomorrow at 09:30. Reply with the buttons below.", time: "Fri 09:30", st: "read", btns: ["Confirm", "Reschedule"] },
      { w: "pt", t: "STOP", time: "05:05" },
      { w: "sys", t: "Patient opted out of WhatsApp messages · 05:05 · SMS reminders only" }] }
  ];

  FD.templates = [
    { id: "appt_confirm", name: "Appointment confirmed", cat: "Utility", langs: "EN · HI · AR", st: "ok",
      body: "Hi {{1}}, your appointment with {{2}} is confirmed for {{3}}. Your token and live queue link: {{4}}", vars: ["First name", "Doctor", "Date and time", "Queue link"], btns: ["Confirm", "Reschedule"] },
    { id: "reopen_chat", name: "Re-open conversation", cat: "Utility", langs: "EN · HI · AR", st: "ok",
      body: "Hi {{1}}, this is {{2}}. We have an update for you. Tap below to continue the chat.", vars: ["First name", "Clinic"], btns: ["Continue chat"] },
    { id: "running_late", name: "Doctor running late", cat: "Utility", langs: "EN · HI · AR", st: "ok",
      body: "{{1}} is running about {{2}} minutes late today. Your new estimated time is {{3}}. Sorry for the wait.", vars: ["Doctor", "Minutes", "New time range"], btns: ["I'll wait", "Reschedule", "Another doctor"] },
    { id: "document_ready", name: "Document ready (secure link)", cat: "Utility", langs: "EN · HI · AR", st: "ok",
      body: "Hi {{1}}, a new document from {{2}} is ready. Open it securely (OTP needed): {{3}}", vars: ["First name", "Clinic", "Secure link"], btns: [] },
    { id: "dues_reminder", name: "Balance due", cat: "Utility", langs: "EN · HI", st: "ok",
      body: "Hi {{1}}, a balance of {{2}} is pending at {{3}}. Pay securely here: {{4}}", vars: ["First name", "Amount", "Clinic", "Payment link"], btns: [] },
    { id: "festive_offer", name: "Diwali skin-care offer", cat: "Marketing", langs: "EN", st: "review",
      body: "Hi {{1}}, enjoy 15% off skin-care packages this festive season.", vars: ["First name"], btns: [] }
  ];
  FD.quick = ["Clinic timings", "Directions & parking", "Consultation fee", "Send live queue link", "Send payment link", "We'll call you back"];

  /* =====================================================================
     Helpers
     ===================================================================== */
  function who(id) { if (!id) return {}; return id.charAt(0) === "p" ? NB.pat(id) : (FD.people[id] || {}); }
  function nm(o) { return NB.rtl() && o.ar ? o.ar : (o.disp || o.name || ""); }
  function first(o) { return nm(o).split(" ")[0]; }
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function addMin(t, m) { var p = t.split(":"), x = +p[0] * 60 + +p[1] + m; return pad(Math.floor(x / 60) % 24) + ":" + pad(x % 60); }
  function phone(n, intl) {
    var m = NB.mkt(), s = String(n || "");
    if (!intl && NB.gcc() && !/^5\d /.test(s)) { var d = s.replace(/\D/g, ""); s = "5" + d.charAt(1) + " " + d.slice(-7, -4) + " " + d.slice(-4); }
    return '<span class="ltr num">' + (intl || m.phone) + " " + esc(s) + "</span>";
  }
  function tt(txt) { return '<span class="fd-tt">' + ui.icon("hourglass", "xs") + txt + "</span>"; }
  function ago(m) {
    if (m < 60) return L(m + " min ago", "منذ " + m + " د", m + " मिनट पहले");
    var h = Math.round(m / 60);
    return L(h + " h ago", "منذ " + h + " س", h + " घंटे पहले");
  }
  function srcIcon(s) { return { WhatsApp: "chat", Web: "globe", "Nabd Connect": "globe", Desk: "arrivals", App: "smartphone", Caregiver: "users", "Self check-in": "qr", Kiosk: "monitor" }[s] || "dot"; }
  function setMap(key, id, val) { var o = Object.assign({}, NB.v(key, {})); if (val == null) delete o[id]; else o[id] = val; NB.state.ui[key] = o; }
  function closeToast(msg) { NB.state.overlay = null; NB.toast(msg); }
  function mlist(items) { return '<div class="fd-mlist">' + items.join("") + "</div>"; }
  // Mobile card: o = {lead, title, sub, right, meta, actions, cls, ov, arg}
  function mcard(o) {
    var a = o.ov ? ' data-ov="' + o.ov + '" data-arg="' + esc(o.arg) + '"' : "";
    return '<div class="fd-mc ' + (o.cls || "") + '"' + a + '><div class="fd-mc-h">' + (o.lead || "") + '<div class="fl"><b>' + o.title + "</b>" + (o.sub ? "<small>" + o.sub + "</small>" : "") + "</div>" + (o.right || "") + "</div>" +
      (o.meta ? '<div class="fd-mc-m">' + o.meta + "</div>" : "") + (o.actions ? '<div class="fd-mc-a">' + o.actions + "</div>" : "") + "</div>";
  }
  function waIcon() { return ui.icon("chat", "xs"); }
  function qEntry(tok) { return NB.D.queue.find(function (x) { return x.tok === tok; }); }
  function qOfPatient(pid) { return NB.D.queue.find(function (x) { return x.p === pid; }); }
  function natLabel() { return NB.mkt().natId; }
  // Where a patient sits on today's calendar: [doctorId, slotIndex] or null.
  function calSlotOf(pid) {
    var r = null;
    Object.keys(FD.cal).forEach(function (dId) { FD.cal[dId].forEach(function (cd, i) { if (!r && cd.slice(2) === pid) r = [dId, i]; }); });
    return r;
  }
  function maskId(last4) { return last4 && last4 !== "—" ? '<span class="ltr num">•••• ' + esc(last4) + "</span>" : "—"; }

  var ST = { // queue status -> [pill kind, label]
    booked: ["mute", "Booked"], arrived: ["info", "Arrived"], vitals: ["ok", "Vitals done"], waiting: ["warn", "Waiting"],
    inconsult: ["acc", "In consultation"], checkout: ["info", "Checkout pending"], completed: ["ok", "Completed"]
  };
  NB.queueStatus = ST;
  function stLabel(k) {
    return {
      booked: L("Booked", "محجوز", "बुक"), arrived: L("Arrived", "وصل", "पहुँचे"), vitals: L("Vitals done", "العلامات الحيوية تمت", "वाइटल्स हो गए"),
      waiting: L("Waiting", "في الانتظار", "प्रतीक्षा में"), inconsult: L("In consultation", "في الاستشارة", "परामर्श में"),
      checkout: L("Checkout pending", "بانتظار الدفع", "चेकआउट बाकी"), completed: L("Completed", "مكتمل", "पूर्ण")
    }[k] || k;
  }
  function stPill(k) { return ui.pill(ST[k][0], stLabel(k)); }

  /* =====================================================================
     reception.arrivals · Today board
     ===================================================================== */
  function arrAct(x) {
    if (x.st === "checkout") return ui.btn(L("Checkout", "الدفع", "चेकआउट"), { cls: "sm pri", go: "billing.checkout", icon: "receipt" });
    if (x.st === "booked") return ui.btn(L("Check in", "تسجيل الحضور", "चेक-इन"), { cls: "sm", ov: "checkin", arg: x.tok, icon: "check" });
    if (x.st === "completed") return ui.btn(L("Invoice", "الفاتورة", "इनवॉइस"), { cls: "sm ghost", go: "billing.invoices", icon: "printer" });
    if (x.st === "inconsult") return ui.btn(L("File", "الملف", "फ़ाइल"), { cls: "sm ghost", ov: "patientfile", arg: x.p, icon: "file" });
    return ui.btn(L("Move", "نقل", "बदलें"), { cls: "sm", ov: "movequeue", arg: x.tok, icon: "arrowright" });
  }

  NB.screen("reception.arrivals", function (c) {
    var q = NB.D.queue, f = NB.v("arrF", "all");
    var pred = { all: function () { return true; }, waiting: function (x) { return x.st === "waiting" || x.st === "arrived" || x.st === "vitals"; },
      inconsult: function (x) { return x.st === "inconsult"; }, checkout: function (x) { return x.st === "checkout"; }, done: function (x) { return x.st === "completed"; } };
    var rows = q.filter(pred[f] || pred.all);
    var cnt = function (k) { return q.filter(pred[k]).length; };
    var h = ui.header(L("Today · Arrivals", "اليوم · الوصول", "आज · आगमन"), "Sat, 17 Oct 2026 · " + q.length + " " + L("on the board", "على اللوحة", "बोर्ड पर") + " · " + L("median wait", "متوسط الانتظار", "औसत प्रतीक्षा") + ' <b class="num">11 min</b>',
      ui.seg("arrF", [["all", L("All", "الكل", "सभी"), q.length], ["waiting", L("Waiting", "في الانتظار", "प्रतीक्षारत"), cnt("waiting")], ["inconsult", L("In consultation", "في الاستشارة", "परामर्श में"), cnt("inconsult")],
        ["checkout", L("Checkout", "الدفع", "चेकआउट"), cnt("checkout")], ["done", L("Completed", "مكتمل", "पूर्ण"), cnt("done")]]));
    h += '<div class="row sb"><div class="btns">' + ui.btn(L("Register walk-in", "تسجيل بدون موعد", "वॉक-इन दर्ज करें"), { cls: "pri", ov: "walkin", icon: "userplus" }) +
      ui.btn(L("Check in", "تسجيل الحضور", "चेक-इन"), { ov: "checkin", icon: "check" }) +
      ui.btn(L("Notify delay", "إشعار بالتأخير", "देरी सूचित करें"), { ov: "delay", icon: "clockh" }) +
      ui.btn(L("Waiting-room display", "شاشة الانتظار", "प्रतीक्षा स्क्रीन"), { go: "reception.display", icon: "tv" }) + "</div>" +
      tt(L("Walk-in < 45 s · returning < 15 s", "بدون موعد < 45 ث · عائد < 15 ث", "वॉक-इन < 45 से · पुराने मरीज़ < 15 से")) + "</div>";

    h += '<div class="grid g4 kpis">' +
      ui.kpi(String(cnt("waiting")), L("Waiting now", "في الانتظار الآن", "अभी प्रतीक्षा में"), ui.icon("bolt", "xs") + " 2 " + L("flagged urgent", "عاجل", "अत्यावश्यक"), "warnc") +
      ui.kpi('11 <small class="small muted">min</small>', L("Median wait today", "متوسط الانتظار اليوم", "आज औसत प्रतीक्षा"), ui.icon("checkcircle", "xs") + " " + L("target < 15 min", "الهدف < 15 د", "लक्ष्य < 15 मिनट"), "okc") +
      ui.kpi('38 <small class="small muted">s</small>', L("Walk-in registration", "تسجيل بدون موعد", "वॉक-इन पंजीकरण"), ui.icon("checkcircle", "xs") + " " + L("avg of last 10 · target < 45 s", "آخر 10 · الهدف < 45 ث", "पिछले 10 · लक्ष्य < 45 से"), "okc") +
      ui.kpi("62%", L("Checked themselves in", "سجّلوا حضورهم بأنفسهم", "स्वयं चेक-इन"), ui.icon("hourglass", "xs") + " " + L("≈ 46 min of desk time saved", "≈ 46 د وُفّرت", "≈ 46 मिनट बचे"), "faint") + "</div>";

    var list;
    if (c.mobile) {
      list = rows.length ? mlist(rows.map(function (x) {
        var p = NB.pat(x.p), d = NB.doc(x.d);
        return mcard({ cls: x.flag || "", lead: '<span class="fd-tokb">' + x.tok + "</span>", title: '<span class="fd-lnk" data-ov="patientfile" data-arg="' + x.p + '">' + esc(NB.pname(p)) + "</span>",
          sub: p.age + " " + p.sex + " · " + esc(x.reason), right: stPill(x.st),
          meta: "<span>" + ui.icon("steth", "xs") + esc(NB.dname(d)) + "</span><span>" + ui.icon(srcIcon(x.src), "xs") + esc(x.src) + "</span>" +
            (x.eta ? '<span class="ltr">' + ui.icon("hourglass", "xs") + "ETA " + x.eta + " min</span>" : "<span>" + esc(x.arr) + "</span>") + (x.flag === "crit" ? ui.pill("crit", L("Urgent", "عاجل", "अत्यावश्यक")) : ""),
          actions: arrAct(x) });
      })) : '<div class="card"><div class="empty">' + L("Nobody in this list right now.", "لا أحد في هذه القائمة الآن.", "अभी इस सूची में कोई नहीं।") + "</div></div>";
    } else {
      list = ui.table([{ h: L("Token", "الرقم", "टोकन") }, { h: L("Patient", "المريض", "मरीज़") }, { h: L("Doctor", "الطبيب", "डॉक्टर") },
        { h: L("Wait / ETA", "الانتظار", "प्रतीक्षा") }, { h: L("Status", "الحالة", "स्थिति") }, { h: "" }],
        rows.map(function (x) {
          var p = NB.pat(x.p), d = NB.doc(x.d);
          return { cls: x.flag || "", cells: ['<span class="tok">' + x.tok + "</span>",
            ui.cellp('<span class="fd-lnk" data-ov="patientfile" data-arg="' + x.p + '">' + esc(NB.pname(p)) + "</span>", p.age + " " + p.sex + " · " + esc(x.reason)),
            ui.cellp(esc(NB.dname(d)), '<span title="' + esc(x.src) + '">' + ui.icon(srcIcon(x.src), "xs") + "</span> " + esc(x.type)),
            ui.cellp(x.wait ? '<span class="num ltr">' + x.wait + " min</span>" : "—", x.eta ? '<span class="ltr">ETA ' + x.eta + " min</span>" : x.arr),
            stPill(x.st) + (x.flag === "crit" ? " " + ui.pill("crit", L("Urgent", "عاجل", "अत्यावश्यक")) : ""), arrAct(x)] };
        }), { min: 700, empty: L("Nobody in this list right now.", "لا أحد في هذه القائمة الآن.", "अभी इस सूची में कोई नहीं।") });
    }

    var needs = ui.card(L("Needs you", "يحتاج تدخلك", "आपकी ज़रूरत"), '<div class="lst">' + [
      ["crit", "alertfill", L("Triage flag on a booking request", "علامة فرز على طلب حجز", "बुकिंग अनुरोध पर ट्राइएज फ़्लैग"), "Imtiaz Ahmed · chest discomfort · 18 min", { go: "reception.requests" }],
      ["urg", "qr", L("Self check-in outside geofence", "تسجيل ذاتي خارج النطاق", "जियोफ़ेंस के बाहर चेक-इन"), nm(NB.pat("p10")) + " · 1.4 km", { go: "reception.checkin" }],
      ["warn", "wallet", L("Unpaid dues at check-in", "مستحقات غير مدفوعة", "बकाया राशि"), nm(NB.pat("p9")) + " · " + NB.money(350), { ov: "patientfile", arg: "p9" }],
      ["info", "calendar", L("2 unconfirmed for 10:20", "موعدان غير مؤكدين 10:20", "10:20 के 2 अपुष्ट"), L("Reminder sent T-2 h · no reply", "أُرسل تذكير", "रिमाइंडर भेजा"), { go: "reception.calendar" }]
    ].map(function (n) {
      return ui.li('<span class="pill ' + n[0] + '" style="height:32px;width:32px;padding:0;justify-content:center">' + ui.icon(n[1], "sm") + "</span>", n[2], n[3], ui.icon("chevright", "sm faint flip"), n[4]);
    }).join("") + "</div>", { icon: "bell", right: '<span class="pill warn">' + ui.icon("alertcircle") + "4</span>" });

    var rail = c.mobile ? "" : needs;
    rail += ui.card(L("Doctor queues", "طوابير الأطباء", "डॉक्टर क़तारें"), '<div class="lst">' + NB.D.doctors.map(function (d) {
      var n = q.filter(function (x) { return x.d === d.id && /waiting|arrived|vitals|booked/.test(x.st); }).length;
      return ui.li(ui.av(d.name), esc(NB.dname(d)), esc(d.spec) + " · " + n + " " + L("waiting", "ينتظرون", "प्रतीक्षा में") + " · avg " + d.avgMin + " min",
        d.status === "break" ? ui.pill("perm", L("On break", "استراحة", "ब्रेक पर")) : d.delay ? ui.pill("warn", "+" + d.delay + " min") : ui.pill("ok", L("On time", "في الموعد", "समय पर")),
        { ov: "delay", arg: d.id });
    }).join("") + "</div>", { icon: "steth" });

    rail += ui.card(L("ETA shown to patients", "الوقت المتوقع للمرضى", "मरीज़ों को ETA"), '<p class="small muted" style="margin:0 0 10px">Set by the owner in Workflows. Patients get a WhatsApp “leave home now” message when their ETA drops below their travel time.</p>' +
      '<div class="chips">' + ui.pill("acc", "Range ±10 min", "hourglass") + ui.pill("mute", "Re-estimated every check-in", "refresh") + "</div>", { icon: "hourglass" });

    return h + (c.mobile ? needs : "") + '<div class="split">' + list + '<div class="stack s16">' + rail + "</div></div>";
  });

  /* =====================================================================
     Walk-in registration (drawer)
     ===================================================================== */
  NB.overlay("walkin", function () {
    var m = NB.mkt(), gcc = NB.gcc(), fam = FD.families.F2;
    var langs = gcc ? ["Arabic", "English", "Hindi", "Urdu", "Malayalam", "Tagalog"] : ["English", "Hindi", "Kannada", "Tamil", "Malayalam"];
    var match = '<div class="card" style="padding:14px"><div class="ch" style="margin-bottom:8px"><span class="eyebrow">' + ui.icon("users", "sm") +
      L("2 people already on this number", "شخصان على هذا الرقم", "इस नंबर पर 2 लोग") + "</span>" + ui.pill("ok", L("Found in 0.2 s", "وُجد في 0.2 ث", "0.2 से में मिला"), "checkcircle") + '</div><div class="lst">' +
      fam.members.slice(0, 2).map(function (mm) {
        var o = who(mm[0]);
        return ui.li(ui.av(o.name), esc(nm(o)) + ' <span class="small faint">· ' + esc(mm[1]) + "</span>", o.age + " " + o.sex + " · " + esc(fam.name),
          ui.btn(L("Use", "استخدم", "चुनें"), { cls: "sm", act: "toast", arg: "Returning patient " + o.name + " · token T-23 issued in 9 s · WhatsApp sent", icon: "check" }));
      }).join("") + '</div><p class="small muted" style="margin:10px 0 0">' + L("Returning? Pick a name (< 15 s). New family member? Fill in below; the number links them to the family.", "مريض عائد؟ اختر الاسم. فرد جديد؟ أكمل أدناه.", "पुराने मरीज़? नाम चुनें। नया सदस्य? नीचे भरें।") + "</p></div>";
    return { title: L("Register walk-in", "تسجيل بدون موعد", "वॉक-इन दर्ज करें"), body:
      '<div class="row sb">' + tt(L("Target < 45 s new · < 15 s returning", "الهدف < 45 ث جديد · < 15 ث عائد", "लक्ष्य < 45 से नए · < 15 से पुराने")) +
        '<span class="small faint"><span class="kbd">Tab</span> ' + L("next", "التالي", "अगला") + ' · <span class="kbd">Ctrl Enter</span> ' + L("register", "تسجيل", "दर्ज करें") + "</span></div>" +
      '<div class="fgrid">' + ui.field(L("Mobile (type first)", "الجوال (أولاً)", "मोबाइल (पहले)"), m.phone + (gcc ? " 50 551 2290" : " 98451 44556"), { id: "wk-phone" }) +
        ui.field(m.natId + " (" + L("optional", "اختياري", "वैकल्पिक") + ")", "", { ph: gcc ? "Tap card on reader or type" : "Scan ABHA QR or type · Aadhaar never required", id: "wk-nid" }) + "</div>" + match +
      '<div class="fgrid">' + ui.field(L("Full name", "الاسم الكامل", "पूरा नाम"), "", { ph: gcc ? "e.g. Ayaan Khan" : "e.g. Ayaan Khan", id: "wk-name" }) +
        ui.field(L("Age or date of birth", "العمر أو تاريخ الميلاد", "उम्र या जन्मतिथि"), "", { ph: "3 y or 14/05/2023", id: "wk-age" }) +
        ui.field(L("Sex", "الجنس", "लिंग"), "Male", { options: ["Female", "Male", "Other"], id: "wk-sex" }) +
        ui.field(L("Relationship to account holder", "صلة القرابة", "खाताधारक से संबंध"), "Son", { options: ["Self", "Son", "Daughter", "Spouse", "Parent", "Other"], id: "wk-rel" }) +
        ui.field(L("Preferred language", "اللغة المفضلة", "पसंदीदा भाषा"), langs[0], { options: langs, id: "wk-lang" }) +
        ui.field(L("Visit type", "نوع الزيارة", "विज़िट प्रकार"), "New consult", { options: ["New consult", "Follow-up", "Vaccination", "Procedure"], id: "wk-type" }) +
        ui.field(L("Doctor", "الطبيب", "डॉक्टर"), "Next available · Dr. Nisha Bhat · ETA 15–25 min", { options: ["Next available · Dr. Nisha Bhat · ETA 15–25 min", "Dr. Meera Iyer · ETA 25–35 min", "Dr. Vikram Shah · ETA 35–50 min (running late)"], full: true, id: "wk-doc" }) +
        ui.field(L("Reason for visit", "سبب الزيارة", "आने का कारण"), "", { full: true, ph: "e.g. fever since yesterday", id: "wk-reason" }) + "</div>" +
      ui.note("ok", L("No duplicate found for this name and age.", "لا يوجد تكرار لهذا الاسم والعمر.", "इस नाम और उम्र का कोई डुप्लिकेट नहीं।") + ' <span class="faint">' + L("Checked as you type against 1.2 M records.", "يُفحص أثناء الكتابة.", "टाइप करते समय जाँचा गया।") + "</span>") +
      ui.note("info", L("Only the minimum is asked here. The patient completes address, allergies and insurance on WhatsApp while waiting.", "الحد الأدنى فقط هنا؛ يكمل المريض البقية عبر واتساب.", "यहाँ केवल न्यूनतम जानकारी; बाकी मरीज़ व्हाट्सऐप पर भरेंगे।")) +
      ui.toggle("wkUrgent", L("Mark urgent", "تحديد كعاجل", "अत्यावश्यक चिह्नित करें"), L("Moves the patient ahead and asks for a reason (audited). Others see a neutral “urgent case” note.", "يقدّم المريض ويطلب سببًا (مُدقق).", "मरीज़ को आगे करता है और कारण पूछता है।")) +
      ui.toggle("wkWa", L("Send token and live queue link on WhatsApp", "إرسال الرقم ورابط الطابور عبر واتساب", "टोकन और लाइव क़तार लिंक व्हाट्सऐप पर भेजें"), L("Patient can wait outside and come back when called.", "يمكن للمريض الانتظار خارجًا.", "मरीज़ बाहर इंतज़ार कर सकते हैं।"), true) +
      ui.toggle("wkPrint", L("Print token slip", "طباعة رقم الدور", "टोकन पर्ची प्रिंट करें"), L("For patients without a phone.", "للمرضى بدون هاتف.", "बिना फ़ोन वाले मरीज़ों के लिए।")),
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Register and issue token", "تسجيل وإصدار رقم", "दर्ज करें और टोकन दें"), { cls: "pri", act: "toast", arg: "Registered in 34 s · token T-23 · WhatsApp sent", icon: "check" }) };
  });

  /* =====================================================================
     reception.requests · Booking requests inbox
     ===================================================================== */
  function allRequests() {
    return NB.D.requests.map(function (r) { return Object.assign({}, r, FD.reqx[r.id] || {}); }).concat(FD.requests);
  }
  var MODES = {
    auto: { icon: "bolt", t: ["Auto-accept with caps", "قبول تلقائي بحدود", "सीमा के साथ ऑटो-स्वीकृति"],
      d: "Bookings confirm instantly up to 14 per doctor per session, keeping 1 walk-in slot an hour. Only over-cap and triage-flagged requests land here." },
    review: { icon: "eye", t: ["Review each booking", "مراجعة كل حجز", "हर बुकिंग की समीक्षा"],
      d: "Every request waits here. Patients are told “we'll confirm within 15 minutes”, so the reply clock matters." },
    hybrid: { icon: "sliders", t: ["Hybrid by visit type", "مختلط حسب نوع الزيارة", "विज़िट प्रकार के अनुसार मिश्रित"],
      d: "Follow-ups and vaccinations auto-accept (cap 12 per session). New patients, procedures, sibling bookings and triage flags come here." }
  };

  function reqCard(r, c) {
    var d = NB.doc(r.d), left = r.sla - r.mins, slaK = left <= 0 ? "crit" : left <= 5 ? "warn" : "";
    var badges = [];
    if (r.newPt || r.new) badges.push(ui.pill("info", L("New patient", "مريض جديد", "नया मरीज़"), "userplus"));
    else badges.push(ui.pill("mute", L("Returning", "عائد", "पुराने"), "history"));
    if (r.guardian) badges.push(ui.pill("acc", L("Guardian booking", "حجز من ولي الأمر", "अभिभावक बुकिंग"), "users"));
    badges.push(ui.pill("mute", r.type, r.type.indexOf("Procedure") === 0 ? "syringe" : "calendar"));
    if (r.triage) badges.push(ui.pill("crit", L("Clinical triage", "فرز سريري", "क्लिनिकल ट्राइएज")));
    var name = r.disp || r.name;
    var whoHtml = '<div class="row nw" style="align-items:flex-start">' + ui.av(name, "lg") + '<div class="fl"><b class="fd-req-n">' + esc(NB.rtl() && r.ar ? r.ar : name) + (r.forWhom ? ' <span class="small muted">· ' + L("for", "لـ", "के लिए") + " " + esc(r.forWhom) + "</span>" : "") + "</b>" +
      '<small class="small faint">' + (r.age ? r.age + " " + (r.sex || "") + " · " : "") + phone(r.phone) + " · " + ui.icon(srcIcon(r.src), "xs") + " " + esc(r.src) + " · " + ago(r.mins) + "</small></div></div>" +
      '<div class="chips" style="margin-top:10px">' + badges.join("") + "</div>" +
      '<p class="fd-quote">“' + esc(r.reason) + "”</p>" + '<small class="small faint">' + ui.icon("info", "xs") + " " + L("Held because", "معلّق لأن", "रोका गया क्योंकि") + ": " + esc(r.why) + "</small>";
    var slot = '<div class="fd-req-slot"><span class="eyebrow">' + L("Requested", "المطلوب", "अनुरोधित") + "</span><b>" + esc(r.slot) + "</b><small>" + esc(NB.dname(d)) + " · " + esc(d.spec) + "</small>" +
      '<div class="fd-sugg">' + ui.icon("sparkle", "xs") + " " + L("Suggested", "مقترح", "सुझाव") + ": <b>" + esc(r.alt) + "</b><br>" + esc(r.altWhy) + "</div></div>";
    var sla = '<div class="fd-sla ' + slaK + '"><span class="eyebrow">' + L("Reply within", "الرد خلال", "जवाब दें") + "</span><b>" +
      (left <= 0 ? L("Overdue " + (-left) + " min", "متأخر " + (-left) + " د", (-left) + " मिनट देर") : left + " min") + "</b>" + ui.bar(Math.min(100, r.mins / r.sla * 100), slaK || "ok") +
      "<small>SLA " + r.sla + " min · " + L("patient is waiting on " + r.src, "المريض ينتظر", "मरीज़ प्रतीक्षा में") + "</small></div>";
    var act = '<div class="fd-req-act">' + (r.triage ? ui.note("crit", "<b>" + L("Possible cardiac symptoms. Don't hold for tomorrow.", "أعراض قلبية محتملة. لا تؤجل للغد.", "संभावित हृदय लक्षण। कल तक न रोकें।") + "</b> " +
        L("Call now. If pain is happening at rest, send the urgent-care advice template.", "اتصل الآن. إذا كان الألم أثناء الراحة أرسل قالب الرعاية العاجلة.", "अभी कॉल करें। आराम में दर्द हो तो तत्काल देखभाल टेम्पलेट भेजें।")) +
        '<div class="btns" style="width:100%">' + ui.btn(L("Call patient", "اتصل بالمريض", "मरीज़ को कॉल करें"), { cls: "sm dng", act: "fd-req-call", arg: r.id, icon: "phone" }) +
        ui.btn(L("Send urgent-care advice", "إرسال نصيحة رعاية عاجلة", "तत्काल देखभाल सलाह भेजें"), { cls: "sm", act: "toast", arg: "Urgent-care advice template sent to " + name, icon: "send" }) + "</div>" : "") +
      '<span class="grow"></span>' +
      ui.btn(L("Decline", "رفض", "अस्वीकार"), { cls: "sm ghost", ov: "fd-decline", arg: r.id, icon: "close" }) +
      ui.btn(L("Propose another slot", "اقتراح موعد آخر", "दूसरा स्लॉट सुझाएँ"), { cls: "sm", ov: "fd-propose", arg: r.id, icon: "calendar" }) +
      ui.btn(L("Accept", "قبول", "स्वीकार"), { cls: "sm pri", act: "fd-req-accept", arg: r.id, icon: "check" }) + "</div>";
    return '<div class="card fd-req' + (r.triage ? " crit" : "") + '"><div>' + whoHtml + "</div>" + slot + sla + act + "</div>";
  }

  NB.screen("reception.requests", function (c) {
    var mode = NB.v("fdMode", "hybrid"), done = NB.v("fdReq", {}), M = MODES[mode];
    var all = allRequests().filter(function (r) { return r.modes.indexOf(mode) >= 0; });
    var pending = all.filter(function (r) { return !done[r.id]; }).sort(function (a, b) { return (b.triage ? 1 : 0) - (a.triage ? 1 : 0) || (a.sla - a.mins) - (b.sla - b.mins); });
    var handled = all.filter(function (r) { return done[r.id]; });
    var routine = pending.filter(function (r) { return !r.triage; });
    var oldest = pending.reduce(function (m, r) { return Math.max(m, r.mins); }, 0);

    var h = ui.header(L("Booking requests", "طلبات الحجز", "बुकिंग अनुरोध"), pending.length + " " + L("waiting for an answer", "بانتظار الرد", "जवाब की प्रतीक्षा में") + " · " + L("from WhatsApp, Nabd Connect and the web", "من واتساب ونبض كونكت والويب", "व्हाट्सऐप, नब्ज़ कनेक्ट और वेब से"),
      (routine.length ? ui.btn(L("Accept all routine", "قبول كل الروتينية", "सभी सामान्य स्वीकार करें") + " (" + routine.length + ")", { cls: "pri", act: "fd-req-bulk", icon: "check" }) : "") +
      ui.btn(L("Calendar", "التقويم", "कैलेंडर"), { go: "reception.calendar", icon: "calendar" }));

    h += '<div class="card hl"><div class="fd-mode"><span class="fd-mi">' + ui.icon(M.icon) + '</span><div class="fl" style="min-width:220px"><div class="eyebrow">' + L("Acceptance mode · set by the owner", "وضع القبول · يحدده المالك", "स्वीकृति मोड · मालिक द्वारा") + "</div>" +
      '<h2 class="h" style="margin:2px 0 4px">' + L(M.t[0], M.t[1], M.t[2]) + '</h2><p class="small muted" style="margin:0 0 10px">' + M.d + "</p>" +
      '<div class="fd-mstats"><span>' + L("Auto-accepted today", "قُبلت تلقائيًا اليوم", "आज ऑटो-स्वीकृत") + ' <b class="num">38</b></span><span>' + L("Median reply", "متوسط الرد", "औसत जवाब") + ' <b class="num">4 min</b></span><span>' +
      L("Desk time saved", "وقت موفر", "बचा समय") + ' <b class="num">≈ 1 h 35 min</b></span><span>' + L("SLA breaches this week", "تجاوزات هذا الأسبوع", "इस सप्ताह SLA उल्लंघन") + ' <b class="num">1</b></span></div></div>' +
      '<div class="stack s8" style="align-items:flex-start">' + ui.btn(L("Change in Workflows", "تغيير في سير العمل", "वर्कफ़्लो में बदलें"), { cls: "sm", go: "owner.workflows", icon: "sliders" }) +
      '<span class="small faint">' + L("Preview mode", "معاينة الوضع", "मोड पूर्वावलोकन") + "</span>" +
      ui.seg("fdMode", [["auto", L("Auto", "تلقائي", "ऑटो")], ["hybrid", L("Hybrid", "مختلط", "मिश्रित")], ["review", L("Review", "مراجعة", "समीक्षा")]], "hybrid") + "</div></div></div>";

    h += '<div class="grid g4 kpis">' + ui.kpi(String(pending.length), L("Waiting for you", "بانتظارك", "आपकी प्रतीक्षा में"), pending.some(function (r) { return r.triage; }) ? ui.icon("alertfill", "xs") + " 1 " + L("triage flag", "علامة فرز", "ट्राइएज फ़्लैग") : "", "critc") +
      ui.kpi(oldest + ' <small class="small muted">min</small>', L("Oldest request", "أقدم طلب", "सबसे पुराना अनुरोध"), L("SLA 15 min today · 60 min future", "15 د لليوم · 60 د للمستقبل", "आज 15 मिनट · आगे 60 मिनट"), "faint") +
      ui.kpi('4 <small class="small muted">min</small>', L("Median reply today", "متوسط الرد اليوم", "आज औसत जवाब"), ui.icon("checkcircle", "xs") + " " + L("patients hear back fast", "رد سريع", "तेज़ जवाब"), "okc") +
      ui.kpi(String(handled.length), L("Answered just now", "أُجيبت الآن", "अभी जवाब दिए"), L("undo for 5 min", "تراجع خلال 5 د", "5 मिनट तक वापस लें"), "faint") + "</div>";

    if (!pending.length) {
      h += '<div class="card"><div class="empty">' + ui.icon("checkcircle", "xl acc") + '<h3 class="h" style="margin-top:10px;color:var(--tx)">' + L("Inbox zero. Every patient has an answer.", "لا طلبات. كل مريض تلقّى ردًا.", "इनबॉक्स ख़ाली। हर मरीज़ को जवाब मिला।") + "</h3>" +
        L("New requests appear here instantly, with a sound on the desk PC.", "تظهر الطلبات الجديدة هنا فورًا.", "नए अनुरोध यहाँ तुरंत दिखेंगे।") + "</div></div>";
    } else h += '<div class="stack s16">' + pending.map(function (r) { return reqCard(r, c); }).join("") + "</div>";

    if (handled.length) {
      var lbl = { accepted: ["ok", L("Accepted · confirmation sent", "قُبل · أُرسل التأكيد", "स्वीकृत · पुष्टि भेजी")], proposed: ["info", L("Proposal sent · waiting for patient", "أُرسل الاقتراح", "प्रस्ताव भेजा")], declined: ["mute", L("Declined · reason sent", "رُفض · أُرسل السبب", "अस्वीकृत · कारण भेजा")] };
      h += ui.card(L("Answered just now", "أُجيبت الآن", "अभी जवाब दिए"), '<div class="lst">' + handled.map(function (r) {
        var st = lbl[String(done[r.id]).split("|")[0]] || lbl.accepted;
        return ui.li(ui.av(r.disp || r.name), esc(r.disp || r.name) + ' <span class="small faint">· ' + esc(r.slot) + "</span>", esc(NB.dname(NB.doc(r.d))) + " · " + esc(r.reason),
          '<div class="row nw">' + ui.pill(st[0], st[1]) + ui.btn(L("Undo", "تراجع", "वापस लें"), { cls: "sm ghost", act: "fd-req-undo", arg: r.id }) + "</div>");
      }).join("") + "</div>", { icon: "history" });
    }
    return h;
  });

  function findReq(id) { return allRequests().find(function (r) { return r.id === id; }) || allRequests()[0]; }
  NB.act("fd-req-accept", function (id) { var r = findReq(id); setMap("fdReq", id, "accepted"); closeToast("Accepted · " + (r.disp || r.name) + " gets confirmation, token and calendar link on " + r.src); });
  NB.act("fd-req-undo", function (id) { setMap("fdReq", id, null); NB.toast("Undone · request is back in the inbox"); });
  NB.act("fd-req-call", function (id) { var r = findReq(id); NB.toast("Calling " + (r.disp || r.name) + " · call logged against the request"); });
  NB.act("fd-req-bulk", function () {
    var mode = NB.v("fdMode", "hybrid"), done = NB.v("fdReq", {}), n = 0;
    allRequests().forEach(function (r) { if (r.modes.indexOf(mode) >= 0 && !done[r.id] && !r.triage) { setMap("fdReq", r.id, "accepted"); done = NB.v("fdReq", {}); n++; } });
    NB.toast(n + " requests accepted · confirmations sent · triage-flagged requests left for you");
  });
  NB.act("fd-req-decline", function (a) { var p = a.split("|"), r = findReq(p[0]); setMap("fdReq", p[0], "declined|" + p[1]); closeToast("Declined · " + (r.disp || r.name) + " told: " + p[1]); });
  NB.act("fd-req-propose", function (id) { var r = findReq(id); setMap("fdReq", id, "proposed"); closeToast("Proposed " + NB.v("fdProp", r.alt) + " to " + (r.disp || r.name) + " · they accept with one tap"); });

  NB.overlay("fd-decline", function (id) {
    var r = findReq(id), d = NB.doc(r.d);
    var reasons = [
      ["Doctor is fully booked", d.name + " is fully booked for that time. Tap below to see the next free slots.", "calendar"],
      ["Needs a different specialty", "This visit is better with another specialist. Tap below to book the right doctor.", "steth"],
      ["Doctor on leave that day", "The doctor is on leave that day. We've kept you on the waitlist and will message you first.", "moon"],
      ["Duplicate request", "We already have a booking for you at this time, so this one was closed.", "copy"],
      ["Please call the clinic", "We need a few details before booking. Please call us or tap “Call me back”.", "phone"]
    ];
    return { kind: "modal", title: L("Decline · one tap", "رفض · بنقرة واحدة", "अस्वीकार · एक टैप"), body:
      '<p class="small muted" style="margin:0">' + esc(r.disp || r.name) + " · " + esc(r.slot) + " · " + esc(NB.dname(d)) + ". " + L("Pick a reason; the patient gets a kind message with a next step, never a dead end.", "اختر سببًا؛ يتلقى المريض رسالة بخطوة تالية.", "कारण चुनें; मरीज़ को अगले कदम के साथ संदेश मिलेगा।") + "</p>" +
      '<div class="lst">' + reasons.map(function (x) {
        return ui.li('<span class="av">' + ui.icon(x[2], "sm") + "</span>", esc(x[0]), esc(x[1]), ui.icon("send", "sm faint flip"), { act: "fd-req-decline", arg: r.id + "|" + x[0] });
      }).join("") + "</div>" + ui.note("info", L("Declines are written to the audit log with your name and the reason.", "يُسجّل الرفض في سجل التدقيق.", "अस्वीकृति ऑडिट लॉग में दर्ज होती है।")),
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) };
  });

  NB.overlay("fd-propose", function (id) {
    var r = findReq(id), d = NB.doc(r.d);
    var day = r.slot.split(" ")[0];
    var opts = [r.alt, day + " 12:20", day + " 12:40", (day === "Today" ? "Tomorrow" : "Mon") + " 10:00"];
    var sel = NB.v("fdProp", r.alt); if (opts.indexOf(sel) < 0) sel = r.alt;
    return { title: L("Propose another slot", "اقتراح موعد آخر", "दूसरा स्लॉट सुझाएँ"), body:
      '<div class="kv"><dt>' + L("Patient", "المريض", "मरीज़") + "</dt><dd>" + esc(r.disp || r.name) + (r.forWhom ? " · " + esc(r.forWhom) : "") + "</dd><dt>" + L("Asked for", "طلب", "माँगा") + "</dt><dd>" + esc(r.slot) + " · " + esc(NB.dname(d)) + "</dd></div>" +
      '<div class="eyebrow">' + L("Free slots that fit", "مواعيد متاحة مناسبة", "उपयुक्त ख़ाली स्लॉट") + '</div><div class="stack s8">' + opts.map(function (o, i) {
        return ui.radio("fdProp", o, o + (i === 0 ? " · " + L("suggested", "مقترح", "सुझाव") : ""), i === 0 ? r.altWhy : i === 3 ? "Next day, first session" : "Same day, " + NB.dname(d), r.alt);
      }).join("") + "</div>" +
      '<div class="eyebrow">' + L("Patient sees", "يرى المريض", "मरीज़ देखेंगे") + '</div><div class="fd-prev"><div class="bub out">Hi ' + esc(first(r)) + ", " + esc(r.slot) + " is taken. Can you come " + esc(sel) + " with " + esc(d.name) + "?" +
      '<span class="ts">10:05 <span class="fd-tick">✓</span></span></div><div class="fd-wbtns out"><span>Yes, book ' + esc(sel.replace(/^(Today|Tomorrow|Mon) /, "")) + "</span><span>Show other times</span></div></div>" +
      ui.toggle("fdPropHold", L("Hold this slot for 15 minutes", "احجز الموعد 15 دقيقة", "15 मिनट के लिए स्लॉट रोकें"), L("Nobody else can book it while the patient decides.", "لا يمكن لغيره حجزه أثناء القرار.", "निर्णय तक कोई और बुक नहीं कर सकता।"), true),
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Send proposal", "إرسال الاقتراح", "प्रस्ताव भेजें"), { cls: "pri", act: "fd-req-propose", arg: r.id, icon: "send" }) };
  });

  /* =====================================================================
     reception.calendar · Appointments day view
     ===================================================================== */
  function slotOf(dId, i) {
    var code = FD.cal[dId][i], k = code.split(":")[0], a = code.slice(2);
    var key = dId + ":" + i;
    if (key === "d2:1" && NB.v("fdNoShow")) k = "N";
    if (key === "d2:6") { var o = NB.v("fdOffer", ""); if (o === "taken") { k = "b"; a = "x5"; } else if (o === "sent") k = "K"; }
    return { k: k, a: a, key: key, t: FD.times[i], note: FD.slotNote[key] };
  }
  function slotHtml(dId, i) {
    var s = slotOf(dId, i), o = who(s.a), n = esc(nm(o));
    var ov = ' data-ov="fd-appt" data-arg="' + s.key + '"';
    var sub = function (ic, txt) { return "<small>" + ui.icon(ic, "xs") + esc(txt) + "</small>"; };
    switch (s.k) {
      case "d": return '<button class="fd-sl done"' + ov + "><b>" + n + "</b>" + sub("checkcircle", L("Seen", "تمت", "देखा गया")) + "</button>";
      case "c": return '<button class="fd-sl cons"' + ov + "><b>" + n + "</b>" + sub("steth", L("In consultation", "في الاستشارة", "परामर्श में")) + "</button>";
      case "a": return '<button class="fd-sl arr"' + ov + "><b>" + n + "</b>" + sub("arrivals", L("Arrived · waiting", "وصل · ينتظر", "पहुँचे · प्रतीक्षा")) + "</button>";
      case "b": return '<button class="fd-sl ok"' + ov + "><b>" + n + "</b>" + sub("check", s.key === "d2:6" ? L("Booked from waitlist", "من قائمة الانتظار", "वेटलिस्ट से") : s.note || L("Confirmed", "مؤكد", "पुष्ट")) + "</button>";
      case "r": return '<button class="fd-sl risk"' + ov + "><b>" + n + "</b>" + sub("alertcircle", L("Unconfirmed · T-2 h sent", "غير مؤكد", "अपुष्ट")) + "</button>";
      case "n": return '<button class="fd-sl ns"' + ov + "><b>" + n + "</b>" + sub("bolt", L("Not arrived · +45 min", "لم يصل · +45 د", "नहीं आए · +45 मिनट")) + "</button>";
      case "N": return '<button class="fd-sl nsd"' + ov + "><b>" + n + "</b>" + sub("close", L("No-show · rebook sent", "لم يحضر", "नहीं आए")) + "</button>";
      case "q": return '<button class="fd-sl req" data-go="reception.requests"><b>' + n + "</b>" + sub("bell", L("Request · needs answer", "طلب بانتظار الرد", "अनुरोध · जवाब बाकी")) + "</button>";
      case "k": return '<button class="fd-sl freed"' + ov + "><b>" + L("Freed slot", "موعد متاح", "ख़ाली स्लॉट") + "</b>" + sub("refresh", L("Cancelled · offer waitlist", "أُلغي · اعرض", "रद्द · वेटलिस्ट को दें")) + "</button>";
      case "K": return '<button class="fd-sl freed"' + ov + "><b>" + L("Offer sent · 2", "عرض مرسل · 2", "ऑफ़र भेजा · 2") + "</b>" + sub("hourglass", L("First to accept wins", "الأسبق يفوز", "पहले स्वीकार करे वही")) + "</button>";
      case "f": return '<button class="fd-sl free"' + ov + ">" + ui.icon("plus", "sm") + L("Book", "احجز", "बुक") + "</button>";
      case "w": return '<div class="fd-sl blk"><b>' + L("Walk-in buffer", "احتياطي بدون موعد", "वॉक-इन बफ़र") + "</b></div>";
      case "x": return '<div class="fd-sl blk"><b>' + esc(s.a) + "</b></div>";
      case "l": return '<div class="fd-sl lv"><b>' + L("On leave", "في إجازة", "छुट्टी पर") + "</b></div>";
    }
    return "";
  }
  function calLegend() {
    return '<div class="fd-legend">' + [["", L("Confirmed", "مؤكد", "पुष्ट")], ["cons", L("In consultation", "في الاستشارة", "परामर्श में")], ["risk", L("Unconfirmed", "غير مؤكد", "अपुष्ट")],
      ["ns", L("Not arrived", "لم يصل", "नहीं आए")], ["req", L("Request pending", "طلب معلّق", "अनुरोध बाकी")], ["free", L("Free", "متاح", "ख़ाली")], ["blk", L("Blocked", "محجوب", "अवरुद्ध")], ["lv", L("Leave", "إجازة", "छुट्टी")]]
      .map(function (x) { return "<span><i class=\"" + x[0] + "\"></i>" + x[1] + "</span>"; }).join("") + "</div>";
  }
  function docHead(d, noAv) {
    var pill = d.status === "break" ? ui.pill("perm", L("Break", "استراحة", "ब्रेक")) : d.id === "d5" ? ui.pill("sub", L("Leave 11:00", "إجازة 11:00", "छुट्टी 11:00"), "moon") : d.delay ? ui.pill("warn", "+" + d.delay + " min") : ui.pill("ok", L("On time", "في الموعد", "समय पर"));
    return (noAv ? "" : ui.av(d.name)) + '<div class="fl fd-dh"><b>' + esc(NB.dname(d)) + "</b><small>" + esc(d.spec) + " · " + esc(d.room) + '</small><div style="margin-top:6px">' + pill + "</div></div>";
  }

  NB.screen("reception.calendar", function (c) {
    var docs = NB.D.doctors, offer = NB.v("fdOffer", "");
    var free = 0, booked = 0, risk = 0;
    docs.forEach(function (d) { FD.cal[d.id].forEach(function (x, i) { var k = slotOf(d.id, i).k; if (k === "f" || k === "k" || k === "K") free++; else if (/[dcabrnq]/.test(k)) booked++; if (k === "r") risk++; }); });
    var h = ui.header(L("Appointments", "المواعيد", "अपॉइंटमेंट"), "Sat, 17 Oct 2026 · " + booked + " " + L("booked", "محجوز", "बुक") + " · " + free + " " + L("free", "متاح", "ख़ाली") + " · " + risk + " " + L("unconfirmed", "غير مؤكد", "अपुष्ट"),
      '<div class="row nw">' + '<button class="ibtn" data-act="toast" data-arg="Friday 16 Oct" title="Previous day">' + ui.icon("chevleft", "sm flip") + "</button>" +
      ui.btn(L("Today", "اليوم", "आज"), { cls: "sm" }) + '<button class="ibtn" data-act="toast" data-arg="Sunday 18 Oct · clinic closed" title="Next day">' + ui.icon("chevright", "sm flip") + "</button></div>" +
      ui.btn(L("New appointment", "موعد جديد", "नया अपॉइंटमेंट"), { cls: "pri", ov: "fd-appt", arg: "d2:7", icon: "plus" }));

    // Delay banners for late doctors
    docs.filter(function (d) { return d.delay > 0; }).forEach(function (d) {
      var n = FD.cal[d.id].filter(function (x) { return /^[abr]:/.test(x); }).length + NB.D.queue.filter(function (x) { return x.d === d.id && /waiting|arrived/.test(x.st); }).length;
      h += '<div class="note warn">' + ui.icon("clockh") + '<div class="fl"><b>' + esc(NB.dname(d)) + " " + L("is running", "متأخر", "देरी से चल रहे हैं") + " " + d.delay + " min " + L("late", "", "") + ".</b> " +
        n + " " + L("patients told on WhatsApp with new ETAs and options (wait, reschedule, another doctor).", "مرضى أُبلغوا عبر واتساب بمواعيد جديدة.", "मरीज़ों को व्हाट्सऐप पर नया ETA भेजा गया।") +
        (d.id === "d2" ? " " + L("1 chose to reschedule.", "اختار واحد إعادة الجدولة.", "1 ने समय बदला।") : "") +
        '</div><div class="btns">' + ui.btn(L("Update delay", "تحديث التأخير", "देरी अपडेट करें"), { cls: "sm", ov: "delay", arg: d.id, icon: "clockh" }) + "</div></div>";
    });

    var grid;
    if (c.mobile) {
      var cd = NB.v("fdCalDoc", "d2"), d = NB.doc(cd);
      grid = '<div class="fd-chipsx">' + docs.map(function (x) {
        return '<button class="chip ' + (x.id === cd ? "on" : "") + '" data-set="fdCalDoc" data-val="' + x.id + '">' + esc(NB.dname(x).replace(/^(Dr\.|د\.)\s*/, "")) + (x.delay ? " · +" + x.delay : "") + "</button>";
      }).join("") + "</div>" + '<div class="card" style="padding:14px"><div class="row nw">' + docHead(d) + "</div></div>" +
        '<div class="stack s8">' + FD.times.map(function (t, i) {
          return '<div class="fd-mslot"><span class="fd-tm' + (i === FD.nowIdx ? " acc" : "") + '">' + t + "</span>" + slotHtml(cd, i) + "</div>";
        }).join("") + "</div>";
    } else {
      var cols = "56px repeat(" + docs.length + ", minmax(140px, 1fr))";
      grid = '<div class="fd-calw"><div class="fd-cal" style="grid-template-columns:' + cols + '"><div class="fd-ch"></div>' + docs.map(function (d) { return '<div class="fd-ch">' + docHead(d, true) + "</div>"; }).join("");
      FD.times.forEach(function (t, i) {
        var now = i === FD.nowIdx ? " fd-now" : "";
        grid += '<div class="fd-tm' + (i === FD.nowIdx ? " now fd-now" : "") + '">' + t + "</div>" + docs.map(function (d) { return '<div class="' + now + '">' + slotHtml(d.id, i) + "</div>"; }).join("");
      });
      grid += "</div></div>";
    }

    // Waitlist panel
    var matches = FD.waitlist.filter(function (w) { return w.match; });
    var offerBox = offer === "taken" ? ui.note("ok", "<b>" + esc(nm(who("x5"))) + " " + L("took 11:00 with Dr. Vikram Shah.", "حجزت 11:00 مع د. فيكرام.", "ने 11:00 डॉ. विक्रम के साथ लिया।") + "</b> " + L("Backfilled in 6 min. The other offer was withdrawn automatically.", "مُلئ خلال 6 د.", "6 मिनट में भरा।"))
      : '<div class="fd-offer"><div class="row sb"><b>' + ui.icon("refresh", "sm") + " 11:00 · " + esc(NB.dname(NB.doc("d2"))) + "</b>" +
        (offer === "sent" ? ui.pill("sync", L("Offer live · 12 min left", "العرض قائم · 12 د", "ऑफ़र चालू · 12 मिनट")) : ui.pill("warn", L("Freed 09:48", "متاح 09:48", "09:48 ख़ाली"))) + "</div>" +
        '<span class="small muted">' + (offer === "sent" ? L("Sent to " + matches.length + " matching patients on WhatsApp. First to tap “Book it” gets the slot; the others are told it's gone.", "أُرسل إلى المطابقين. الأسبق يحصل عليه.", "मिलते मरीज़ों को भेजा। पहले टैप करने वाले को मिलेगा।")
          : L(matches.length + " patients on the waitlist match this slot.", "مرضى مطابقون في قائمة الانتظار.", "मरीज़ इस स्लॉट से मेल खाते हैं।")) + "</span>" +
        '<div class="btns">' + (offer === "sent" ? ui.btn(L("Simulate: patient accepts", "محاكاة القبول", "सिमुलेट: स्वीकार"), { cls: "sm", act: "fd-offer", arg: "taken", icon: "play" })
          : ui.btn(L("Offer freed slot", "اعرض الموعد", "ख़ाली स्लॉट ऑफ़र करें"), { cls: "sm pri", act: "fd-offer", arg: "sent", icon: "send" })) + "</div></div>";
    var wl = ui.card(L("Waitlist", "قائمة الانتظار", "वेटलिस्ट"), offerBox + '<div class="lst" style="margin-top:10px">' + FD.waitlist.map(function (w) {
      var o = who(w.who), taken = offer === "taken" && w.id === "w1";
      return ui.li(ui.av(o.name), esc(nm(o)),
        (w.match ? '<span class="okc">' + ui.icon("checkcircle", "xs") + " " + L("Match", "مطابق", "मेल") + "</span> · " : "") + esc(w.want) + " · " + L("since", "منذ", "से") + " " + esc(w.since),
        taken ? ui.pill("ok", L("Booked", "محجوز", "बुक")) : ui.btn(L("Offer", "اعرض", "ऑफ़र"), { cls: "sm ghost", act: "toast", arg: "Offer sent to " + o.name + " on WhatsApp · 15-min window", icon: "send" }));
    }).join("") + '</div><p class="small faint" style="margin:10px 0 0">' + L("Only future slots are offered. Window 15 min; first to accept wins.", "تُعرض المواعيد المستقبلية فقط.", "केवल आगे के स्लॉट ऑफ़र होते हैं।") + "</p>", { icon: "hourglass", right: '<span class="pill mute">' + FD.waitlist.length + "</span>" });

    var ns = NB.v("fdNoShow") ? ui.note("ok", L("Omar Farooq marked no-show. Next patient pulled forward, 4 ETAs recomputed, a “sorry we missed you, rebook?” message sent.", "تم تسجيل عدم الحضور.", "नो-शो दर्ज। अगला मरीज़ आगे किया गया।"))
      : ui.card(L("No-show check", "فحص عدم الحضور", "नो-शो जाँच"), ui.li(ui.av("Omar Farooq"), esc(nm(who("x16"))), "09:20 · " + esc(NB.dname(NB.doc("d2"))) + " · " + L("grace 15 min passed", "انتهت المهلة", "15 मिनट की छूट ख़त्म"), ui.pill("urg", "+45 min")) +
        '<p class="small muted" style="margin:8px 0 10px">' + L("2nd missed visit this year. Owner rule: prepay after 3.", "الغياب الثاني هذا العام.", "इस साल दूसरी बार।") + '</p><div class="btns">' +
        ui.btn(L("Mark no-show", "تسجيل عدم الحضور", "नो-शो दर्ज करें"), { cls: "sm", act: "fd-noshow", icon: "close" }) + ui.btn(L("Call", "اتصال", "कॉल"), { cls: "sm ghost", act: "toast", arg: "Calling Omar Farooq…", icon: "phone" }) + "</div>", { icon: "alertcircle", cls: "warnb" });

    return h + calLegend() + '<div class="split">' + '<div class="stack s16">' + grid + "</div>" + '<div class="stack s16">' + wl + ns + "</div></div>";
  });

  NB.act("fd-offer", function (a) { NB.state.ui.fdOffer = a; NB.toast(a === "sent" ? "Offer sent to 2 waitlisted patients on WhatsApp · first to accept wins" : "Lakshmi Iyer accepted · 11:00 booked · other offer withdrawn"); });
  NB.act("fd-noshow", function () { NB.state.ui.fdNoShow = true; closeToast("No-show recorded · queue pulled forward · 4 ETAs recomputed in 2 s"); });

  // Appointment drawer: details, drag-free move, cancel, or a quick booking for a free slot.
  NB.overlay("fd-appt", function (arg) {
    var p = String(arg || "d2:7").split(":"), dId = p[0], i = +p[1] || 0, s = slotOf(dId, i), d = NB.doc(dId), o = who(s.a);
    var time = FD.times[i] + "–" + addMin(FD.times[i], 20);
    if (s.k === "f") {
      return { title: L("New appointment", "موعد جديد", "नया अपॉइंटमेंट") + " · " + FD.times[i], body:
        '<div class="row sb"><div class="row nw">' + ui.av(d.name) + '<div class="cellp"><b>' + esc(NB.dname(d)) + "</b><small>" + esc(d.spec) + " · " + time + "</small></div></div>" + tt("< 30 s") + "</div>" +
        '<div class="fgrid">' + ui.field(L("Mobile", "الجوال", "मोबाइल"), NB.mkt().phone + " ", { ph: "Type to look up family", id: "ap-ph" }) + ui.field(L("Patient", "المريض", "मरीज़"), "", { ph: "Picked from phone lookup", id: "ap-pt" }) +
        ui.field(L("Visit type", "نوع الزيارة", "विज़िट प्रकार"), "Follow-up", { options: ["Follow-up", "New consult", "Procedure (40 min)", "Vaccination"], id: "ap-ty" }) +
        ui.field(L("Booked via", "عبر", "माध्यम"), "Phone call", { options: ["Phone call", "At the desk", "WhatsApp"], id: "ap-src" }) + "</div>" +
        ui.toggle("apWa", L("Send confirmation with Confirm / Reschedule buttons", "إرسال تأكيد بأزرار", "पुष्टि बटनों के साथ भेजें"), L("Reminder T-24 h and T-2 h follow automatically.", "تذكير تلقائي قبل 24 و2 ساعة.", "24 और 2 घंटे पहले रिमाइंडर।"), true),
        foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Book and send", "احجز وأرسل", "बुक करें और भेजें"), { cls: "pri", act: "toast", arg: "Booked " + FD.times[i] + " with " + d.name + " · confirmation sent", icon: "check" }) };
    }
    if (s.k === "k" || s.k === "K") {
      return { title: L("Freed slot", "موعد متاح", "ख़ाली स्लॉट") + " · " + FD.times[i], body:
        ui.note("warn", "<b>" + esc(nm(who("x9"))) + " " + L("cancelled on WhatsApp at 09:48.", "ألغى عبر واتساب 09:48.", "ने 09:48 पर रद्द किया।") + "</b> " + L("The slot is " + time + " with " + d.name + ".", "", "")) +
        '<div class="eyebrow">' + L("Waitlist matches", "المطابقون", "वेटलिस्ट मेल") + '</div><div class="lst">' + FD.waitlist.filter(function (w) { return w.match; }).map(function (w) {
          var x = who(w.who); return ui.li(ui.av(x.name), esc(nm(x)), esc(w.want) + " · " + esc(w.reason), ui.pill("ok", L("Match", "مطابق", "मेल")));
        }).join("") + "</div>" + ui.note("info", L("Offer goes to everyone matching at once; the first “Book it” tap wins and the slot locks. Others are told politely it's gone.", "يُرسل العرض للجميع؛ الأسبق يفوز.", "सबको एक साथ ऑफ़र; पहले टैप वाला जीतेगा।")),
        foot: ui.btn(L("Close", "إغلاق", "बंद करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Offer to waitlist", "اعرض على قائمة الانتظار", "वेटलिस्ट को ऑफ़र करें"), { cls: "pri", act: "fd-offer-close", icon: "send" }) };
    }
    // Booked / arrived / done / no-show candidate
    var stTxt = { d: ["ok", "Seen"], c: ["acc", "In consultation"], a: ["info", "Arrived"], b: ["ok", "Confirmed"], r: ["warn", "Unconfirmed"], n: ["urg", "Not arrived"], N: ["mute", "No-show"] }[s.k] || ["mute", s.k];
    var freeSlots = [];
    NB.D.doctors.forEach(function (dd) { FD.cal[dd.id].forEach(function (x, j) { if (j > FD.nowIdx && slotOf(dd.id, j).k === "f" && (dd.spec === d.spec || dd.id === dId || freeSlots.length < 5)) freeSlots.push([dd.id + ":" + j, FD.times[j] + " · " + dd.name.replace("Dr. ", "Dr ")]); }); });
    freeSlots = freeSlots.slice(0, 6);
    var mv = NB.v("fdMvT", freeSlots[0] && freeSlots[0][0]);
    return { title: esc(nm(o)) + " · " + FD.times[i], body:
      '<div class="row nw">' + ui.av(o.name || "?", "lg") + '<div class="fl"><b style="font-size:15px">' + esc(nm(o)) + '</b><div class="small faint">' + (o.age != null ? o.age + " " + o.sex + " · " : "") + phone(o.phone) + "</div></div>" + ui.pill(stTxt[0], stTxt[1]) + "</div>" +
      '<dl class="kv"><dt>' + L("Doctor", "الطبيب", "डॉक्टर") + "</dt><dd>" + esc(NB.dname(d)) + "</dd><dt>" + L("Time", "الوقت", "समय") + '</dt><dd class="ltr num">' + time + "</dd><dt>" + L("Booked via", "عبر", "माध्यम") + "</dt><dd>WhatsApp · 14 Oct</dd><dt>" +
        L("Reminders", "التذكيرات", "रिमाइंडर") + "</dt><dd>" + (s.k === "r" ? "T-24 h · T-2 h " + L("sent, no reply", "بلا رد", "भेजे, जवाब नहीं") : "T-24 h " + L("confirmed", "مؤكد", "पुष्ट")) + "</dd></dl>" +
      (s.k === "n" ? ui.note("warn", L("Grace period passed. Marking no-show pulls the next patient forward and recomputes ETAs.", "انتهت المهلة.", "छूट ख़त्म।")) : "") +
      (s.note ? ui.note("info", esc(s.note)) : "") +
      '<div class="eyebrow">' + ui.icon("arrowright", "sm") + L("Move to another slot", "نقل إلى موعد آخر", "दूसरे स्लॉट पर ले जाएँ") + '</div><div class="chips">' + freeSlots.map(function (fs) {
        return '<button class="chip ' + (mv === fs[0] ? "on" : "") + '" data-set="fdMvT" data-val="' + fs[0] + '">' + esc(fs[1]) + "</button>";
      }).join("") + "</div>" + ui.toggle("fdMvAsk", L("Ask the patient first", "اسأل المريض أولاً", "पहले मरीज़ से पूछें"), L("They tap Accept on WhatsApp; the old slot stays held until they answer.", "يقبل عبر واتساب.", "वे व्हाट्सऐप पर स्वीकार करेंगे।"), true),
      foot: (s.k === "n" ? ui.btn(L("Mark no-show", "عدم الحضور", "नो-शो"), { cls: "dng", act: "fd-noshow", icon: "close" }) : "") +
        ui.btn(L("Cancel appointment", "إلغاء الموعد", "अपॉइंटमेंट रद्द करें"), { cls: "ghost", act: "toast", arg: "Cancelled · slot offered to the waitlist automatically", icon: "close" }) +
        (/[br]/.test(s.k) ? ui.btn(L("Check in", "تسجيل الحضور", "चेक-इन"), { ov: "checkin", arg: s.a, icon: "check" }) : "") +
        ui.btn(L("Move and notify", "انقل وأبلغ", "बदलें और सूचित करें"), { cls: "pri", act: "toast", arg: "Moved to " + ((freeSlots.find(function (x) { return x[0] === mv; }) || [0, ""])[1]) + " · patient asked on WhatsApp", icon: "send" }) };
  });
  NB.act("fd-offer-close", function () { NB.state.ui.fdOffer = "sent"; closeToast("Offer sent to 2 waitlisted patients · first to accept wins"); });

  /* =====================================================================
     reception.patients · Registry with families and duplicates
     ===================================================================== */
  function famOf(pid) { var x = FD.px[pid]; return x && x.fam ? FD.families[x.fam] : null; }
  function patFlags(p) {
    var x = FD.px[p.id] || {}, f = [];
    p.allergies.forEach(function (a) { f.push(ui.pill(a.sev === "Severe" ? "crit" : "warn", a.a)); });
    if (x.guardian) f.push(ui.pill("acc", L("Guardian", "ولي أمر", "अभिभावक") + " · " + first(who(x.guardian)), "users"));
    if (x.caregiver) f.push(ui.pill("acc", L("Caregiver", "مقدم رعاية", "देखभालकर्ता"), "users"));
    var cs = NB.D.consents.find(function (k) { return k.p === p.id && /pending|active/.test(k.st); });
    if (cs) f.push(cs.st === "active" ? ui.pill("ok", L("Sharing active", "مشاركة نشطة", "साझा सक्रिय"), "shieldcheck") : ui.pill("warn", L("Consent pending", "موافقة معلقة", "सहमति बाकी"), "shieldcheck"));
    if (p.ins) f.push(ui.pill("mute", p.ins.name, "card"));
    return f.join(" ");
  }

  NB.screen("reception.patients", function (c) {
    var q = String(NB.v("fdQ", "")).toLowerCase().trim(), f = NB.v("fdPF", "all"), grp = NB.v("fdPG", "list");
    var pats = NB.D.patients.filter(function (p) {
      var x = FD.px[p.id] || {};
      if (q && (p.name + " " + p.ar + " " + p.phone.replace(/\s/g, "") + " " + p.mrn).toLowerCase().indexOf(q.replace(/\s/g, "")) < 0 && p.name.toLowerCase().indexOf(q) < 0) return false;
      if (f === "family") return !!x.fam; if (f === "minor") return p.age < 18; if (f === "dues") return x.dues > 0; if (f === "ins") return !!p.ins; if (f === "allergy") return p.allergies.length > 0;
      return true;
    });
    var dues = NB.D.patients.filter(function (p) { return (FD.px[p.id] || {}).dues > 0; }).length;
    var h = ui.header(L("Patients", "المرضى", "मरीज़"), "18,240 " + L("records · search by name, phone, MRN or", "سجل · ابحث بالاسم أو الهاتف أو", "रिकॉर्ड · नाम, फ़ोन, MRN या") + " " + esc(natLabel()),
      NB.v("fdMerged") ? "" : ui.btn(L("1 merge suggestion", "اقتراح دمج", "1 मर्ज सुझाव"), { ov: "fd-merge", icon: "copy" }));
    h += '<div class="row"><label class="fd-search">' + ui.icon("search", "sm") + '<input id="fd-psearch" autocomplete="off" placeholder="' + esc(L("Type a name, phone or MRN…", "اكتب اسمًا أو هاتفًا…", "नाम, फ़ोन या MRN लिखें…")) + '" value="' + esc(NB.v("fdQ", "")) + '"></label>' +
      tt(L("Found in < 0.3 s", "النتائج < 0.3 ث", "< 0.3 से में")) + "</div>";
    h += '<div class="row sb">' + ui.seg("fdPF", [["all", L("All", "الكل", "सभी")], ["family", L("Families", "عائلات", "परिवार")], ["minor", L("Minors", "قاصرون", "नाबालिग")], ["allergy", L("Allergies", "حساسية", "एलर्जी")], ["dues", L("Dues", "مستحقات", "बकाया"), dues], ["ins", L("Insured", "مؤمن", "बीमित")]]) +
      ui.seg("fdPG", [["list", L("List", "قائمة", "सूची")], ["family", L("Group by family", "حسب العائلة", "परिवार अनुसार")]]) + "</div>";
    if (!NB.v("fdMerged")) h += '<div class="note warn">' + ui.icon("copy") + '<div class="fl"><b>' + L("Possible duplicate", "تكرار محتمل", "संभावित डुप्लिकेट") + ":</b> “Mohd Ali” (SUV-09877, 2023) " + L("and", "و", "और") + " “" + esc(nm(NB.pat("p8"))) + "” (SUV-10238). " +
      L("Same phone, same year of birth, name 86% similar.", "نفس الهاتف وسنة الميلاد.", "एक ही फ़ोन, जन्म वर्ष, नाम 86% समान।") + '</div><div class="btns">' + ui.btn(L("Review merge", "مراجعة الدمج", "मर्ज देखें"), { cls: "sm", ov: "fd-merge", icon: "copy" }) + "</div></div>";

    var body;
    if (!pats.length) {
      body = '<div class="card"><div class="empty">' + ui.icon("search", "xl") + '<h3 class="h" style="margin-top:10px;color:var(--tx)">' + L("No patient matches", "لا يوجد مريض مطابق", "कोई मरीज़ नहीं मिला") + " “" + esc(NB.v("fdQ", "")) + "”</h3>" +
        '<p style="margin:6px 0 14px">' + L("Check the spelling or search by phone. Still nothing? Register them in under 45 seconds.", "تحقق من الإملاء أو ابحث بالهاتف.", "वर्तनी जाँचें या फ़ोन से खोजें।") + "</p>" + ui.btn(L("Register as new", "تسجيل كجديد", "नया दर्ज करें"), { cls: "pri", ov: "walkin", icon: "userplus" }) + "</div></div>";
    } else if (grp === "family") {
      var fams = Object.keys(FD.families).map(function (k) { return [k, FD.families[k]]; }).filter(function (fx) { return fx[1].members.some(function (mm) { return pats.some(function (p) { return p.id === mm[0]; }); }); });
      var singles = pats.filter(function (p) { return !(FD.px[p.id] || {}).fam; });
      body = '<div class="grid g2">' + fams.map(function (fx) {
        var F = fx[1], head = who(F.head);
        return ui.card(L(F.name, F.ar, F.hi), '<div class="lst">' + F.members.map(function (mm) {
          var o = who(mm[0]), isP = mm[0].charAt(0) === "p";
          return ui.li(ui.av(o.name), esc(nm(o)) + (isP ? "" : ' <span class="pill mute" style="height:18px;font-size:9px">' + L("No visits yet", "بلا زيارات", "कोई विज़िट नहीं") + "</span>"), esc(mm[1]) + " · " + o.age + " " + o.sex,
            isP ? ui.icon("chevright", "sm faint flip") : "", isP ? { ov: "patientfile", arg: mm[0] } : {});
        }).join("") + "</div>", { icon: "users", cls: "fd-famcard", right: '<span class="small faint">' + phone(head.phone) + "</span>" });
      }).join("") + (singles.length ? ui.card(L("Individuals", "أفراد", "व्यक्तिगत"), '<div class="lst">' + singles.map(function (p) {
        return ui.li(ui.av(p.name), esc(nm(p)), p.age + " " + p.sex + " · " + esc(p.mrn), ui.icon("chevright", "sm faint flip"), { ov: "patientfile", arg: p.id });
      }).join("") + "</div>", { icon: "user" }) : "") + "</div>";
    } else if (c.mobile) {
      body = mlist(pats.map(function (p) {
        var x = FD.px[p.id] || {}, F = famOf(p.id);
        return mcard({ ov: "patientfile", arg: p.id, lead: ui.av(p.name), title: esc(nm(p)), sub: p.age + " " + p.sex + " · " + esc(p.mrn) + " · " + phone(p.phone),
          right: x.dues ? ui.pill("warn", NB.money(x.dues).replace(/<[^>]+>/g, ""), "wallet") : "",
          meta: (F ? "<span>" + ui.icon("users", "xs") + esc(L(F.name, F.ar, F.hi)) + "</span>" : "") + "<span>" + ui.icon("history", "xs") + L("Last visit", "آخر زيارة", "पिछली विज़िट") + " " + esc(x.last) + "</span>" + patFlags(p) });
      }));
    } else {
      body = ui.table([{ h: L("Patient", "المريض", "मरीज़") }, { h: L("Phone · family", "الهاتف · العائلة", "फ़ोन · परिवार") }, { h: natLabel() }, { h: L("Flags", "تنبيهات", "फ़्लैग") }, { h: L("Last visit", "آخر زيارة", "पिछली विज़िट") }, { h: L("Dues", "مستحقات", "बकाया"), cls: "r" }, { h: "" }],
        pats.map(function (p) {
          var x = FD.px[p.id] || {}, F = famOf(p.id);
          return { ov: "patientfile", arg: p.id, cells: [
            '<div class="row nw">' + ui.av(p.name) + ui.cellp(esc(nm(p)), p.age + " " + p.sex + " · " + esc(p.mrn)) + "</div>",
            ui.cellp(phone(p.phone), F ? ui.icon("users", "xs") + " " + esc(L(F.name, F.ar, F.hi)) + " · " + F.members.length : L("Individual", "فردي", "व्यक्तिगत")),
            maskId(x.natId), '<div class="chips" style="gap:4px;max-width:260px">' + (patFlags(p) || '<span class="faint small">—</span>') + "</div>",
            ui.cellp(esc(x.last), esc(NB.dname(NB.doc(x.lastDoc))) + " · " + x.visits + " " + L("visits", "زيارات", "विज़िट")),
            x.dues ? '<span class="warnc">' + NB.money(x.dues) + "</span>" : '<span class="faint">' + NB.money(0) + "</span>",
            ui.icon("chevright", "sm faint flip")] };
        }), { min: 900 });
    }
    return h + body;
  });

  // Live search on the patients screen.
  document.addEventListener("input", function (ev) {
    if (ev.target.id !== "fd-psearch") return;
    var pos = ev.target.selectionStart;
    NB.state.ui.fdQ = ev.target.value; NB.render();
    var el = document.getElementById("fd-psearch"); if (el) { el.focus(); try { el.setSelectionRange(pos, pos); } catch (e) {} }
  });

  NB.overlay("fd-merge", function () {
    var p = NB.pat("p8"), keep = NB.v("fdKeep", "a");
    var rows = [["Name", p.name, "Mohd Ali", "warn", "86%"], ["Phone", p.phone, p.phone, "ok", "Same"], ["Year of birth", "1959", "1959", "ok", "Same"], ["Sex", "M", "M", "ok", "Same"],
      [natLabel(), "•••• 8817", "—", "info", "One side"], ["Visits", "22", "2 (2023)", "info", "Combined"], ["Allergies", "Aspirin (mild)", "—", "info", "Kept"], ["Insurance", p.ins.name, "—", "info", "Kept"]];
    return { title: L("Review possible duplicate", "مراجعة التكرار", "डुप्लिकेट देखें"), size: "lg", body:
      ui.note("info", L("Records are merged into one. Visits, bills, documents and consents move to the record you keep. You can undo for 30 days.", "تُدمج السجلات في سجل واحد. يمكن التراجع خلال 30 يومًا.", "रिकॉर्ड एक में मिलेंगे। 30 दिन तक वापस ले सकते हैं।")) +
      '<div class="fd-cmp"><div class="h">' + L("Field", "الحقل", "फ़ील्ड") + '</div><div class="h">SUV-10238 · 2024</div><div class="h">SUV-09877 · 2023</div>' + rows.map(function (r) {
        return '<div class="k">' + esc(r[0]) + "</div><div>" + esc(r[1]) + "</div><div>" + esc(r[2]) + ' <span class="pill ' + r[3] + '" style="height:18px;font-size:9px">' + ui.icon(r[3] === "ok" ? "check" : r[3] === "warn" ? "alertcircle" : "info") + esc(r[4]) + "</span></div>";
      }).join("") + "</div>" + '<div class="eyebrow">' + L("Keep as the main record", "الاحتفاظ كسجل رئيسي", "मुख्य रिकॉर्ड रखें") + '</div><div class="grid g2">' +
      ui.radio("fdKeep", "a", "SUV-10238 · " + p.name, L("More complete · 22 visits · insurance on file", "أكثر اكتمالًا", "अधिक पूर्ण"), "a") + ui.radio("fdKeep", "b", "SUV-09877 · Mohd Ali", L("Older record · 2 visits", "سجل أقدم", "पुराना रिकॉर्ड"), "a") + "</div>" +
      ui.note("warn", L("Merging needs your PIN (step-up). It is written to the audit log with both record numbers.", "يتطلب الدمج رمزك الشخصي.", "मर्ज के लिए आपका PIN चाहिए।"), "lock"),
      foot: ui.btn(L("Not the same person", "ليس الشخص نفسه", "एक व्यक्ति नहीं"), { cls: "ghost", act: "fd-merge-no" }) + ui.btn(L("Merge records", "دمج السجلات", "रिकॉर्ड मर्ज करें"), { cls: "pri", act: "fd-merge-do", arg: keep, icon: "copy" }) };
  });
  NB.act("fd-merge-do", function () { NB.state.ui.fdMerged = true; closeToast("Merged into SUV-10238 · 2 visits moved · undo available for 30 days"); });
  NB.act("fd-merge-no", function () { NB.state.ui.fdMerged = true; closeToast("Marked as different people · we won't suggest this pair again"); });

  /* Patient file drawer */
  NB.overlay("patientfile", function (pid) {
    var p = NB.pat(pid || "p3"); if (!p.id) p = NB.pat("p3");
    var x = FD.px[p.id] || {}, F = famOf(p.id), m = NB.mkt(), tab = NB.v("pfTab", "overview"), qx = qOfPatient(p.id);
    var h = '<div class="row nw" style="align-items:flex-start">' + ui.av(p.name, "xl") + '<div class="fl"><h2 class="h" style="font-size:19px">' + esc(nm(p)) + "</h2>" +
      '<div class="small muted">' + p.age + " " + p.sex + " · " + esc(p.mrn) + " · " + phone(p.phone) + "</div>" +
      '<div class="small faint">' + esc(natLabel()) + " " + maskId(x.natId) + " · " + L("Speaks", "اللغة", "भाषा") + " " + (p.lang === "hi" ? "Hindi" : p.lang === "ar" ? "Arabic" : "English") + "</div></div>" +
      (qx ? '<div class="stack s4" style="align-items:flex-end"><span class="tok">' + qx.tok + "</span>" + stPill(qx.st) + "</div>" : "") + "</div>";
    h += '<div class="btns">' + ui.btn(L("Check in", "تسجيل الحضور", "चेक-इन"), { cls: "sm", ov: "checkin", arg: p.id, icon: "check" }) + ui.btn(L("Book", "احجز", "बुक"), { cls: "sm", go: "reception.calendar", icon: "calendar" }) +
      ui.btn("WhatsApp", { cls: "sm", go: "reception.messages", icon: "chat" }) + (x.dues ? ui.btn(L("Collect", "تحصيل", "वसूलें") + " " + NB.money(x.dues).replace(/<[^>]+>/g, ""), { cls: "sm pri", go: "billing.checkout", icon: "wallet" }) : "") + "</div>";
    h += p.allergies.length ? ui.note("crit", "<b>" + L("ALLERGIES", "حساسية", "एलर्जी") + ":</b> " + p.allergies.map(function (a) { return esc(a.a) + " (" + esc(a.sev) + " · " + esc(a.rx) + ")"; }).join(", "), "alertfill")
      : ui.note("ok", L("No known allergies · confirmed at last visit", "لا حساسية معروفة", "कोई ज्ञात एलर्जी नहीं"));
    if (x.guardian) h += ui.note("info", L("Minor. Guardian", "قاصر. ولي الأمر", "नाबालिग। अभिभावक") + " <b>" + esc(nm(who(x.guardian))) + "</b> " + L("consents, books and receives all messages.", "يوافق ويحجز ويستلم الرسائل.", "सहमति देते हैं और संदेश पाते हैं।"), "users");
    if (x.caregiver) h += ui.note("info", L("Caregiver", "مقدم الرعاية", "देखभालकर्ता") + " <b>" + esc(nm(who(x.caregiver))) + "</b> (" + L("son, Dubai", "ابن، دبي", "बेटा, दुबई") + ") " + L("can book and see reports. Scoped by consent until 31 Dec.", "يمكنه الحجز ورؤية التقارير.", "बुक कर सकते हैं और रिपोर्ट देख सकते हैं।"), "users");
    if (p.tags.length || p.conditions.length) h += '<div class="chips">' + p.conditions.map(function (k) { return ui.pill("mute", k, "heartpulse"); }).join("") + p.tags.map(function (k) { return ui.pill("info", k, "info"); }).join("") + "</div>";
    h += ui.tabs("pfTab", [["overview", L("Overview", "نظرة عامة", "ओवरव्यू"), "user"], ["visits", L("Visits", "الزيارات", "विज़िट"), "history"], ["bills", L("Bills", "الفواتير", "बिल"), "receipt"],
      ["docs", L("Documents", "المستندات", "दस्तावेज़"), "file"], ["consents", L("Consents", "الموافقات", "सहमतियाँ"), "shieldcheck"]]);

    if (tab === "overview") {
      h += '<div class="grid g2">' + ui.card(L("Demographics", "البيانات", "जनसांख्यिकी"), '<dl class="kv"><dt>' + L("Date of birth", "تاريخ الميلاد", "जन्मतिथि") + "</dt><dd>" + esc(x.dob) + "</dd><dt>" + L("Blood group", "فصيلة الدم", "रक्त समूह") + "</dt><dd>" + esc(x.blood || "—") + "</dd><dt>" +
        L("Address", "العنوان", "पता") + "</dt><dd>" + esc(x.addr || "—") + ", " + esc(m.city) + "</dd><dt>" + L("Registered", "سُجّل", "पंजीकृत") + "</dt><dd>Jun 2024 · WhatsApp</dd><dt>" + L("Visits", "الزيارات", "विज़िट") + "</dt><dd>" + x.visits + "</dd></dl>", { icon: "user" }) +
        ui.card(F ? L(F.name, F.ar, F.hi) : L("Family", "العائلة", "परिवार"), F ? '<div class="lst">' + F.members.map(function (mm) {
          var o = who(mm[0]), me = mm[0] === p.id;
          return ui.li(ui.av(o.name), esc(nm(o)) + (me ? ' <span class="small faint">· ' + L("this file", "هذا الملف", "यह फ़ाइल") + "</span>" : ""), esc(mm[1]) + " · " + o.age + " " + o.sex, "", !me && mm[0].charAt(0) === "p" ? { ov: "patientfile", arg: mm[0] } : {});
        }).join("") + "</div>" : '<p class="small muted" style="margin:0 0 10px">' + L("No family linked to this number.", "لا عائلة مرتبطة.", "इस नंबर से कोई परिवार जुड़ा नहीं।") + "</p>" + ui.btn(L("Link family member", "ربط فرد", "सदस्य जोड़ें"), { cls: "sm", act: "toast", arg: "Family linking opens with phone lookup", icon: "plus" }), { icon: "users" }) + "</div>";
      h += ui.card(L("Insurance & payment", "التأمين والدفع", "बीमा और भुगतान"), p.ins ? '<dl class="kv"><dt>' + L("Insurer", "المؤمن", "बीमाकर्ता") + "</dt><dd>" + esc(p.ins.name) + "</dd><dt>" + L("Policy", "الوثيقة", "पॉलिसी") + '</dt><dd class="ltr">' + esc(p.ins.pol) + "</dd><dt>TPA / " + L("payer", "الدافع", "भुगतानकर्ता") + "</dt><dd>" + esc(p.ins.tpa) + "</dd><dt>" +
        L("Eligibility", "الأهلية", "पात्रता") + "</dt><dd>" + ui.pill("ok", L("Active · checked 09:12", "نشط", "सक्रिय")) + "</dd></dl>"
        : '<p class="small muted" style="margin:0">' + L("Self-pay. Prefers", "دفع ذاتي. يفضل", "स्व-भुगतान। पसंद") + " " + (NB.gcc() ? "card / Apple Pay" : "UPI") + "." + (x.pkg ? " " + esc(x.pkg) + "." : "") + "</p>", { icon: "card" });
    } else if (tab === "visits") {
      var vs = [];
      if (qx) vs.push(["acc", "Today · " + qx.arr, qx.type + " · " + qx.reason, NB.dname(NB.doc(qx.d)) + " · " + stLabel(qx.st)]);
      vs.push(["ok", x.last === "Today" ? "02 Sep 2026" : x.last + " 2026", "Follow-up · " + (p.conditions[0] || "Review"), NB.dname(NB.doc(x.lastDoc)) + " · e-Rx sent"]);
      vs.push(["ok", "14 Jun 2026", (p.conditions[0] || "General") + " · consultation", NB.dname(NB.doc(x.lastDoc)) + " · lab ordered"]);
      vs.push(["", "Jun 2024", L("Registered", "سُجّل", "पंजीकृत"), L("Booked on WhatsApp · first visit", "حجز عبر واتساب", "व्हाट्सऐप पर बुक")]);
      h += '<div class="tl">' + vs.map(function (v) { return '<div class="ev"><span class="dotc ' + v[0] + '">' + ui.icon(v[0] === "acc" ? "steth" : v[0] === "ok" ? "check" : "userplus", "sm") + '</span><div class="bd"><b>' + esc(v[2]) + "</b><small>" + esc(v[1]) + " · " + esc(v[3]) + "</small></div></div>"; }).join("") + "</div>";
    } else if (tab === "bills") {
      var bills = [];
      if (x.dues) bills.push(["INV-26-0" + (300 + x.visits), "11 Jul", "Consultation + dressing", x.dues, "warn", L("Due", "مستحق", "बकाया")]);
      bills.push(["INV-26-0" + (412 + x.visits), x.last === "Today" ? "Today" : x.last, "Consultation", 800, "ok", (NB.gcc() ? "Card" : "UPI") + " · " + L("paid", "مدفوع", "भुगतान")]);
      bills.push(["PH-26-0" + (918 + x.visits), "14 Jun", "Pharmacy", 460, "ok", L("Paid", "مدفوع", "भुगतान")]);
      if (p.ins) bills.push(["CLM-26-0" + (77 + x.visits), "14 Jun", "Claim · " + p.ins.name, 2650, "info", L("Settled", "مُسوّى", "निपटाया")]);
      h += '<div class="lst">' + bills.map(function (b) {
        return ui.li('<span class="av">' + ui.icon("receipt", "sm") + "</span>", '<span class="ltr">' + b[0] + "</span> · " + esc(b[2]), esc(b[1]), '<div class="stack s4" style="align-items:flex-end">' + NB.money(b[3]) + ui.pill(b[4], b[5]) + "</div>");
      }).join("") + "</div>" + (x.dues ? ui.btn(L("Send payment link on WhatsApp", "إرسال رابط الدفع", "भुगतान लिंक भेजें"), { cls: "sm", act: "toast", arg: "Payment link sent · dues_reminder template", icon: "send" }) : "");
    } else if (tab === "docs") {
      var docs = [["pill", "e-Prescription", "QR · valid 30 days · " + (x.last === "Today" ? "today" : x.last)], ["flask", "Lab report", (p.conditions[0] ? "HbA1c / panel" : "CBC") + " · 14 Jun"],
        ["card", natLabel() + " scan", "Captured at the desk · masked"], ["file", "Privacy notice (" + m.privacyLaw + ")", "Accepted on WhatsApp · Jun 2024"]];
      if (p.ins) docs.push(["shieldcheck", "Insurance card", p.ins.name + " · front and back"]);
      if (x.guardian) docs.push(["users", "Guardian consent form", "Signed by " + who(x.guardian).name]);
      h += '<div class="lst">' + docs.map(function (dd) { return ui.li('<span class="av">' + ui.icon(dd[0], "sm") + "</span>", esc(dd[1]), esc(dd[2]), ui.btn(L("Send", "إرسال", "भेजें"), { cls: "sm ghost", act: "toast", arg: dd[1] + " sent as a secure link (OTP)", icon: "send" })); }).join("") + "</div>" +
        ui.note("info", L("Documents go out as secure links with an OTP, never as attachments in the chat.", "تُرسل المستندات كروابط آمنة.", "दस्तावेज़ सुरक्षित लिंक से जाते हैं।"), "lock");
    } else {
      var cs = NB.D.consents.filter(function (k) { return k.p === p.id; });
      var kinds = { active: ["ok", "Active"], "pending-doctor": ["warn", "Pending doctor"], expired: ["mute", "Expired"], denied: ["perm", "Denied"] };
      h += '<div class="lst">' + ui.li('<span class="av">' + ui.icon("chat", "sm") + "</span>", L("WhatsApp messages", "رسائل واتساب", "व्हाट्सऐप संदेश"), L("Appointment and queue updates · opted in Jun 2024", "تحديثات المواعيد", "अपॉइंटमेंट अपडेट"), ui.pill("ok", L("Opted in", "مشترك", "सहमत"))) +
        ui.li('<span class="av">' + ui.icon("scroll", "sm") + "</span>", L("Privacy notice", "إشعار الخصوصية", "गोपनीयता सूचना") + " · " + esc(m.privacyLaw), L("Accepted · purpose: care and billing", "مقبول", "स्वीकृत"), ui.pill("ok", L("Accepted", "مقبول", "स्वीकृत"))) +
        (x.guardian || x.caregiver ? ui.li('<span class="av">' + ui.icon("users", "sm") + "</span>", x.guardian ? L("Guardian access", "وصول ولي الأمر", "अभिभावक पहुँच") : L("Caregiver access", "وصول مقدم الرعاية", "देखभालकर्ता पहुँच"), esc(who(x.guardian || x.caregiver).name) + " · " + L("book, messages, reports", "حجز ورسائل وتقارير", "बुकिंग, संदेश, रिपोर्ट"), ui.pill("ok", L("Active", "نشط", "सक्रिय"))) : "") +
        cs.map(function (k) { var s = kinds[k.st] || ["mute", k.st]; return ui.li('<span class="av">' + ui.icon("share", "sm") + "</span>", esc(k.id) + " · " + esc(k.purpose), esc(k.from) + " → " + esc(k.to) + " · " + esc(k.until), ui.pill(s[0], s[1])); }).join("") + "</div>" +
        ui.note("info", L("Record sharing needs both the patient's and the treating doctor's approval and is always time-limited.", "المشاركة تتطلب موافقة المريض والطبيب.", "साझा करने के लिए मरीज़ और डॉक्टर दोनों की सहमति चाहिए।"), "shieldcheck");
    }
    return { title: L("Patient file", "ملف المريض", "मरीज़ फ़ाइल"), size: "lg", body: h,
      foot: ui.btn(L("Close", "إغلاق", "बंद करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Edit details", "تعديل", "विवरण बदलें"), { act: "toast", arg: "Edits are versioned and audited", icon: "edit" }) +
        ui.btn(L("Book appointment", "حجز موعد", "अपॉइंटमेंट बुक करें"), { cls: "pri", go: "reception.calendar", icon: "calendar" }) };
  });

  /* =====================================================================
     reception.checkin · Self check-in & kiosk
     ===================================================================== */
  var VIA = { qr: ["qr", "QR at door"], wa: ["chat", "WhatsApp “I'm here”"], kiosk: ["monitor", "Kiosk"], geo: ["map", "Geofence auto"] };

  function kiosk() {
    var st = NB.v("kStep", "home"), gcc = NB.gcc(), l2 = gcc ? "ar" : "hi";
    var t2 = function (en, ar, hi) { return '<span class="fd-l2" lang="' + l2 + '"' + (l2 === "ar" ? ' dir="rtl"' : "") + ">" + (l2 === "ar" ? ar : hi) + "</span>"; };
    var fam = FD.families.F2;
    var h = '<div class="fd-kh"><span class="logo"><span class="mark" style="width:30px;height:30px;border-radius:8px">' + ui.icon("logo", "sm") + "</span></span><b>" + esc(NB.fac().name) + "</b>" +
      '<span class="pill mute">' + (gcc ? "EN · عربي" : "EN · हिं") + "</span></div>";
    if (st === "home") {
      h += '<h3 class="fd-ktitle">Welcome! Check in here' + t2("", "أهلاً! سجّل حضورك هنا", "स्वागत है! यहाँ चेक-इन करें") + "</h3>" +
        '<button class="fd-kbig pri" data-set="kStep" data-val="done">' + ui.icon("qr") + "<span><b>Scan your booking QR" + "</b><small>From your WhatsApp confirmation</small></span></button>" +
        '<button class="fd-kbig" data-set="kStep" data-val="phone">' + ui.icon("phone") + "<span><b>Enter mobile number</b><small>" + (gcc ? "رقم الجوال" : "मोबाइल नंबर डालें") + "</small></span></button>" +
        '<button class="fd-kbig" data-act="toast" data-arg="Kiosk: walk-in lane · pick a doctor, get a token">' + ui.icon("userplus") + "<span><b>No booking? Join the walk-in line</b><small>" + (gcc ? "بدون موعد؟" : "बुकिंग नहीं है?") + "</small></span></button>";
    } else if (st === "phone") {
      h += '<h3 class="fd-ktitle">Your mobile number' + t2("", "رقم جوالك", "आपका मोबाइल नंबर") + "</h3>" +
        '<div class="fd-kphone">' + NB.mkt().phone + (gcc ? " 50 551 2290" : " 98451 44556") + "</div>" +
        '<div class="fd-keypad">' + ["1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓"].map(function (k) {
          return "<button" + (k === "✓" ? ' data-set="kStep" data-val="who" style="background:var(--acc-grad);color:var(--on-acc);border-color:transparent"' : "") + ">" + k + "</button>";
        }).join("") + "</div>";
    } else if (st === "who") {
      h += '<h3 class="fd-ktitle">Who is checking in?' + t2("", "من يسجّل حضوره؟", "कौन चेक-इन कर रहा है?") + "</h3>" +
        '<p class="fd-ksub">' + fam.members.length + " people on this number. Names are shortened for privacy.</p>" +
        fam.members.map(function (mm) {
          var o = who(mm[0]), short = o.name.split(" ")[0] + " " + o.name.split(" ")[1].charAt(0) + ".", yr = 2026 - o.age;
          var sub = mm[0] === "p4" ? "Already checked in ✓" : mm[0] === "x20" ? "12:00 · Dr. Nisha Bhat" : "No booking today";
          var cls = mm[0] === "x20" ? "fd-kbig sel" : mm[0] === "p4" ? "fd-kbig dim" : "fd-kbig";
          return '<button class="' + cls + '"' + (mm[0] === "x20" ? ' data-set="kStep" data-val="done"' : "") + ">" + ui.av(o.name, "lg") + "<span><b>" + esc(short) + " · born " + yr + "</b><small>" + esc(sub) + "</small></span></button>";
        }).join("");
    } else {
      h += '<div style="text-align:center"><h3 class="fd-ktitle">You\'re checked in' + t2("", "تم تسجيل حضورك", "आपका चेक-इन हो गया") + '</h3><div class="fd-ktok">T-27</div>' +
        '<p class="fd-ksub">Dr. Nisha Bhat · Room 2<br><b style="color:var(--tx)">About 20–30 min</b> · 3 ahead of you</p></div>' +
        ui.note("ok", "We sent your token and live queue link to WhatsApp. You can wait outside; we'll message you when it's nearly your turn.") +
        '<button class="fd-kbig" data-set="kStep" data-val="home" style="justify-content:center"><b>Done</b></button>';
    }
    h += '<div class="fd-kfoot"><span>' + ui.icon("lock", "xs") + " Tokens only on screen · no medical details</span><span>" + (st === "done" ? "Resets in 8 s" : "Need help? The desk is right here") + "</span></div>";
    return '<div class="fd-kiosk">' + h + "</div>";
  }

  NB.screen("reception.checkin", function (c) {
    var dec = NB.v("fdCi", {});
    var open = FD.selfci.filter(function (x) { return !dec[x.id]; });
    var h = ui.header(L("Self check-in & kiosk", "تسجيل الحضور الذاتي", "सेल्फ़ चेक-इन और कियोस्क"), L("Patients check themselves in; you handle only the exceptions.", "يسجّل المرضى حضورهم؛ وأنت تتعامل مع الاستثناءات فقط.", "मरीज़ ख़ुद चेक-इन करते हैं; आप सिर्फ़ अपवाद संभालें।"),
      ui.btn(L("Desk check-in", "تسجيل من المكتب", "डेस्क चेक-इन"), { ov: "checkin", icon: "check" }) + ui.btn(L("Change methods", "تغيير الطرق", "तरीके बदलें"), { go: "owner.workflows", icon: "sliders" }));
    var methods = [["qr", "QR at the door", "Printed poster · works with any camera", "41%", true], ["chat", "WhatsApp “I'm here”", "Button in the booking chat", "33%", true],
      ["map", "Geofence ≤ 200 m", "Confirms “I'm here” only near the clinic", "on", true], ["monitor", "Kiosk tablet", "Any tablet browser · no special hardware", "12%", NB.v("fdKioskOn", true)]];
    h += '<div class="grid g4">' + methods.map(function (mm) {
      return '<div class="card fd-meth"><div class="row sb"><span class="acc">' + ui.icon(mm[0], "lg") + "</span>" + (mm[4] ? ui.pill("ok", L("On", "مفعّل", "चालू")) : ui.pill("perm", L("Off", "متوقف", "बंद"))) + "</div>" +
        "<b>" + esc(mm[1]) + '</b><span class="small muted">' + esc(mm[2]) + "</span>" + (mm[3] !== "on" ? '<span class="small faint"><span class="num" style="color:var(--tx);font-weight:600">' + mm[3] + "</span> " + L("of today's check-ins", "من تسجيلات اليوم", "आज के चेक-इन में") + "</span>" : '<span class="small faint">' + L("Blocks check-ins from home", "يمنع التسجيل من المنزل", "घर से चेक-इन रोकता है") + "</span>") + "</div>";
    }).join("") + "</div>";
    h += '<div class="grid g4 kpis">' + ui.kpi('11 <small class="small muted">s</small>', L("Median self check-in", "متوسط التسجيل الذاتي", "औसत सेल्फ़ चेक-इन"), ui.icon("checkcircle", "xs") + " " + L("target < 15 s", "الهدف < 15 ث", "लक्ष्य < 15 से"), "okc") +
      ui.kpi("22 / 35", L("Self-checked today", "سجّلوا ذاتيًا اليوم", "आज सेल्फ़ चेक-इन"), "62%", "faint") + ui.kpi(String(open.length), L("Need you", "تحتاجك", "आपकी ज़रूरत"), L("everything else confirmed itself", "البقية تأكدت تلقائيًا", "बाकी अपने आप पुष्ट"), "faint") +
      ui.kpi("2", L("At the counter now", "عند المكتب الآن", "अभी काउंटर पर"), ui.icon("hourglass", "xs") + " ≈ 46 min " + L("desk time saved", "وقت موفر", "समय बचा"), "okc") + "</div>";

    var list = open.length ? '<div class="lst">' + open.map(function (x) {
      var o = who(x.who), v = VIA[x.via];
      var actions = '<div class="btns">' + ui.btn(L("Confirm", "تأكيد", "पुष्टि"), { cls: "sm pri", act: "fd-ci", arg: x.id + "|ok", icon: "check" }) +
        ui.btn(x.fix, { cls: "sm", act: x.id === "ci1" ? "fd-ci" : "fd-ci", arg: x.id + "|fix", icon: x.id === "ci1" ? "wallet" : x.id === "ci2" ? "send" : x.id === "ci5" ? "shieldcheck" : "info" }) +
        ui.btn(L("Not them", "ليس هو", "ये नहीं"), { cls: "sm ghost", act: "fd-ci", arg: x.id + "|no" }) + "</div>";
      return '<div class="li" style="flex-wrap:wrap;align-items:flex-start">' + ui.av(o.name) + '<div class="t" style="min-width:200px"><b>' + esc(nm(o)) + ' <span class="tok small">' + x.tok + "</span></b>" +
        "<small>" + ui.icon(v[0], "xs") + " " + esc(v[1]) + " · " + x.time + "</small>" + '<div style="margin-top:6px">' + ui.pill(x.kind, x.issue).replace('class="pill ', 'class="pill fd-wrap ') + "</div></div>" + actions + "</div>";
    }).join("") + "</div>" : '<div class="empty">' + ui.icon("checkcircle", "xl acc") + "<br>" + L("All self check-ins are confirmed.", "تم تأكيد كل التسجيلات.", "सभी सेल्फ़ चेक-इन पुष्ट।") + "</div>";
    var left = ui.card(L("Needs confirmation", "بحاجة لتأكيد", "पुष्टि चाहिए"), list, { icon: "bell", right: '<span class="pill sync"><span class="pulse"></span>' + L("Live", "مباشر", "लाइव") + "</span>" }) +
      ui.card(L("Confirmed automatically", "تأكدت تلقائيًا", "अपने आप पुष्ट"), '<div class="lst">' + FD.autoci.map(function (a) {
        var o = who(a[0]), v = VIA[a[1]]; return ui.li(ui.av(o.name), esc(nm(o)) + ' <span class="tok small">' + a[3] + "</span>", ui.icon(v[0], "xs") + " " + esc(v[1]) + " · " + a[2], ui.pill("ok", L("Token sent", "أُرسل الرقم", "टोकन भेजा")));
      }).join("") + FD.selfci.filter(function (x) { return dec[x.id]; }).map(function (x) {
        var o = who(x.who); return ui.li(ui.av(o.name), esc(nm(o)) + ' <span class="tok small">' + x.tok + "</span>", L("Resolved by Sneha", "حلّتها سنيها", "स्नेहा ने सुलझाया") + " · " + esc(dec[x.id]), ui.pill(dec[x.id] === "rejected" ? "mute" : "ok", dec[x.id]));
      }).join("") + '</div><p class="small faint" style="margin:10px 0 0">+ 14 ' + L("more today", "أخرى اليوم", "और आज") + "</p>", { icon: "checkcircle" });
    var right = '<div class="card"><div class="ch"><span class="eyebrow">' + ui.icon("monitor", "sm") + L("Kiosk preview", "معاينة الكشك", "कियोस्क पूर्वावलोकन") + "</span>" +
      ui.seg("kStep", [["home", "1 " + L("Start", "بدء", "शुरू")], ["phone", "2"], ["who", "3"], ["done", "4 " + L("Token", "الرقم", "टोकन")]], "home") + '</div><div class="fd-kwrap">' + kiosk() + "</div></div>" +
      ui.card(L("Door poster", "ملصق الباب", "दरवाज़े का पोस्टर"), '<div class="fd-poster"><span class="fd-qr">' + ui.icon("qr") + '</span><div class="fl"><b>' + L("Scan to check in", "امسح لتسجيل الحضور", "चेक-इन के लिए स्कैन करें") + '</b><div class="small muted">' +
        L("Opens WhatsApp with “I'm here”. Works only within 200 m.", "يفتح واتساب برسالة «وصلت».", "व्हाट्सऐप में “मैं आ गया” खुलता है।") + "</div></div></div>" + '<div class="btns" style="margin-top:12px">' + ui.btn(L("Print poster", "طباعة", "प्रिंट"), { cls: "sm", act: "toast", arg: "Poster sent to the desk printer", icon: "printer" }) + "</div>", { icon: "qr" });
    return h + '<div class="split wide"><div class="stack s16">' + left + '</div><div class="stack s16">' + right + "</div></div>";
  });
  NB.act("fd-ci", function (a) {
    var p = a.split("|"), x = FD.selfci.find(function (s) { return s.id === p[0]; }), o = who(x.who);
    var res = p[1] === "ok" ? "confirmed" : p[1] === "no" ? "rejected" : x.id === "ci1" ? "dues collected" : x.id === "ci2" ? "asked to retry" : x.id === "ci5" ? "eligibility ok" : "fixed";
    setMap("fdCi", x.id, res);
    NB.toast(o.name + " · " + res + (p[1] === "no" ? " · token withdrawn, desk will call them" : " · " + x.tok + " live on the board"));
  });

  /* Desk check-in drawer: find booking by phone or token, confirm identity, issue token, send WhatsApp. */
  NB.overlay("checkin", function (arg) {
    var q = NB.D.queue, x = null;
    if (arg && /^T-/.test(arg)) x = qEntry(arg);
    else if (arg && arg.charAt(0) === "p") x = qOfPatient(arg);
    var p = x ? NB.pat(x.p) : arg ? who(arg) : null;
    if (!p) { x = q.find(function (e) { return e.st === "booked"; }) || q[0]; p = NB.pat(x.p); }
    var cs = !x && calSlotOf(p.id), px = FD.px[p.id] || {}, d = x ? NB.doc(x.d) : NB.doc(cs ? cs[0] : "d3"), F = famOf(p.id), already = x && x.st !== "booked";
    var tok = x ? x.tok : "T-27";
    var h = '<div class="row sb">' + tt(L("< 15 s returning patient", "< 15 ث لمريض عائد", "< 15 से पुराने मरीज़")) + '<span class="small faint">' + L("Search by phone, token or name", "ابحث بالهاتف أو الرقم أو الاسم", "फ़ोन, टोकन या नाम से खोजें") + "</span></div>" +
      '<label class="fd-search">' + ui.icon("search", "sm") + '<input value="' + esc(NB.mkt().phone + " " + (p.phone || "")) + '" aria-label="Search booking"></label>';
    h += '<div class="card" style="padding:16px"><div class="row nw">' + ui.av(p.name, "lg") + '<div class="fl"><b style="font-size:15px">' + esc(nm(p)) + "</b>" + '<div class="small faint">' + (p.age != null ? p.age + " " + p.sex + " · " : "") + (p.mrn ? esc(p.mrn) + " · " : "") + phone(p.phone) + "</div></div>" +
      (x ? '<span class="tok" style="font-size:20px">' + tok + "</span>" : "") + "</div>" +
      '<dl class="kv" style="margin-top:14px"><dt>' + L("Booking", "الحجز", "बुकिंग") + "</dt><dd>" + (x ? esc(x.type) + " · " + esc(NB.dname(d)) : (cs ? FD.times[cs[1]] : "12:00") + " · " + esc(NB.dname(d))) + "</dd><dt>" + L("Booked via", "عبر", "माध्यम") + "</dt><dd>" + (x ? esc(x.src) : "WhatsApp") + "</dd><dt>" +
      L("Ask: year of birth", "اسأل: سنة الميلاد", "पूछें: जन्म वर्ष") + '</dt><dd class="num">' + (2026 - (p.age || 0)) + "</dd><dt>" + esc(natLabel()) + "</dt><dd>" + maskId(px.natId || "—") + "</dd><dt>" +
      L("ETA if checked in now", "الوقت المتوقع", "अभी चेक-इन पर ETA") + "</dt><dd>" + (x && x.eta ? '<span class="ltr">' + Math.max(0, x.eta - 5) + "–" + (x.eta + 5) + " min</span>" : "20–30 min") + (d.status === "break" ? " · " + L("doctor on break till 10:40", "الطبيب في استراحة", "डॉक्टर ब्रेक पर") : "") + "</dd></dl></div>";
    if (F) h += '<div class="small muted">' + ui.icon("users", "xs") + " " + L("Also on this number", "أيضًا على هذا الرقم", "इसी नंबर पर") + ": " + F.members.filter(function (mm) { return mm[0] !== p.id; }).map(function (mm) { return esc(nm(who(mm[0]))); }).join(", ") + "</div>";
    if (already) h += ui.note("ok", L("Already checked in at", "سُجّل حضوره الساعة", "पहले ही चेक-इन") + " " + esc(x.arr) + " · " + stLabel(x.st) + ". " + L("You can resend the token or move the patient.", "يمكنك إعادة الإرسال.", "आप टोकन दोबारा भेज सकते हैं।"));
    if (p.allergies && p.allergies.length) h += ui.note("crit", "<b>" + L("Allergy", "حساسية", "एलर्जी") + ":</b> " + p.allergies.map(function (a) { return esc(a.a); }).join(", ") + " · " + L("shown to nurse and doctor", "يظهر للتمريض والطبيب", "नर्स और डॉक्टर को दिखेगा"), "alertfill");
    if (px.dues) h += '<div class="note warn">' + ui.icon("wallet") + '<div class="fl">' + L("Dues from last visit", "مستحقات سابقة", "पिछली विज़िट का बकाया") + " <b>" + NB.money(px.dues) + "</b>. " + L("Collect now or send a link; check-in is not blocked.", "حصّل الآن أو أرسل رابطًا.", "अभी लें या लिंक भेजें।") + "</div>" +
      ui.btn(L("Add to bill", "أضف للفاتورة", "बिल में जोड़ें"), { cls: "sm", act: "toast", arg: "Dues added to today's bill" }) + "</div>";
    if (p.ins) h += ui.note("info", "<b>" + esc(p.ins.name) + "</b> · " + L("eligibility re-checked just now via", "تم التحقق من الأهلية عبر", "पात्रता जाँची गई") + " " + esc(NB.mkt().claims) + " · " + L("active, co-pay 20%", "نشط، مشاركة 20%", "सक्रिय, को-पे 20%"), "shieldcheck");
    h += ui.toggle("ciWa", L("Send token and live queue link on WhatsApp", "إرسال الرقم ورابط الطابور", "टोकन और लाइव क़तार लिंक भेजें"), L("In the patient's language. Includes a “leave now” alert if they step out.", "بلغة المريض.", "मरीज़ की भाषा में।"), true) +
      ui.toggle("ciPrint", L("Print token slip", "طباعة الرقم", "टोकन पर्ची प्रिंट करें"), "") +
      ui.toggle("ciCar", L("Patient will wait outside (car-wait)", "سينتظر في الخارج", "मरीज़ बाहर इंतज़ार करेंगे"), L("We'll call them in when 2 ahead.", "سنبلغه قبل دوره.", "2 आगे होने पर बुलाएँगे।"));
    return { title: L("Check in", "تسجيل الحضور", "चेक-इन"), body: h,
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + (already ? ui.btn(L("Resend token", "إعادة إرسال", "टोकन दोबारा भेजें"), { cls: "pri", act: "toast", arg: tok + " resent on WhatsApp", icon: "send" })
        : ui.btn(L("Check in · issue", "تسجيل · إصدار", "चेक-इन · जारी करें") + " " + tok, { cls: "pri", act: "fd-checkin-do", arg: x ? x.tok : "", icon: "check" })) };
  });
  NB.act("fd-checkin-do", function (tok) {
    var x = qEntry(tok);
    if (x) { x.st = "arrived"; x.arr = "10:05"; }
    closeToast("Checked in in 9 s · " + (tok || "T-27") + " issued · WhatsApp live-queue link sent");
  });

  /* =====================================================================
     Delay broadcast (drawer)
     ===================================================================== */
  NB.overlay("delay", function (arg) {
    if ((arg || "") !== NB.state.ui._fdDlyArg) { NB.state.ui._fdDlyArg = arg || ""; delete NB.state.ui.fdDlyDoc; delete NB.state.ui.fdDlyMin; }
    var dId = NB.v("fdDlyDoc", arg && NB.doc(arg).id ? arg : "d2"), d = NB.doc(dId), cur = d.delay || 0, min = +NB.v("fdDlyMin", cur ? Math.min(60, cur + 15) : 15);
    var aff = []; // [person, token, current ETA, new ETA]
    NB.D.queue.forEach(function (x) { if (x.d === dId && /waiting|arrived|vitals|booked/.test(x.st)) { var o = addMin("10:05", x.eta || 0); aff.push([x.p, x.tok, o, addMin(o, Math.max(0, min - cur))]); } });
    FD.cal[dId].forEach(function (cd, i) { if (i > FD.nowIdx && /^[br]:/.test(cd)) aff.push([cd.slice(2), "", addMin(FD.times[i], cur), addMin(FD.times[i], min)]); });
    var langs = {}; aff.forEach(function (a) { var l = (who(a[0]).lang || "en"); langs[l] = (langs[l] || 0) + 1; });
    var langTxt = Object.keys(langs).map(function (k) { return langs[k] + " " + ({ en: "English", hi: "Hindi", ar: "Arabic" }[k] || k); }).join(", ");
    var ex = aff[0] ? aff[0][3] : "10:35";
    var h = (cur ? ui.note("warn", NB.dname(d) + " " + L("is already", "متأخر بالفعل", "पहले से") + " +" + cur + " min. " + L("Pick the new total; only the difference shifts ETAs.", "اختر الإجمالي الجديد.", "नया कुल चुनें।"), "clockh") : "") + '<div class="row sb">' + tt(L("All notified < 60 s · 2 taps", "إبلاغ الجميع < 60 ث", "सभी को < 60 से में सूचना")) + '<span class="small faint">' + L("The doctor can't block this", "لا يمكن للطبيب منعه", "डॉक्टर इसे रोक नहीं सकते") + "</span></div>";
    h += '<div class="eyebrow">' + L("Doctor", "الطبيب", "डॉक्टर") + '</div><div class="stack s8">' + NB.D.doctors.map(function (x) {
      return ui.radio("fdDlyDoc", x.id, NB.dname(x) + " · " + x.room, x.spec + (x.delay ? " · now +" + x.delay + " min" : x.status === "break" ? " · on break" : " · on time"), dId);
    }).join("") + "</div>";
    h += '<div class="eyebrow">' + L("Running late by", "متأخر بمقدار", "देरी") + "</div>" + ui.seg("fdDlyMin", [["10", "10 min"], ["15", "15 min"], ["30", "30 min"], ["45", "45 min"], ["60", "60 min"]], String(min));
    h += ui.field(L("Reason (staff only; patients see a neutral message)", "السبب (للموظفين فقط)", "कारण (केवल स्टाफ़)"), "Emergency case in the room", { options: ["Emergency case in the room", "Doctor arriving late", "Procedure ran over", "Ward round", "Other"], id: "dl-reason" });
    h += '<div class="card" style="padding:14px"><div class="ch" style="margin-bottom:8px"><span class="eyebrow">' + ui.icon("users", "sm") + aff.length + " " + L("patients affected", "مرضى متأثرون", "मरीज़ प्रभावित") + '</span><span class="small faint">' + esc(langTxt) + "</span></div>" +
      '<div class="lst">' + aff.slice(0, 6).map(function (a) {
        var o = who(a[0]);
        return ui.li(ui.av(o.name), esc(nm(o)) + (a[1] ? ' <span class="tok small">' + a[1] + "</span>" : ""), a[1] ? L("In the queue", "في الطابور", "क़तार में") : L("Booked", "محجوز", "बुक"),
          '<span class="small ltr num"><s class="faint">' + a[2] + "</s> → <b>" + a[3] + "</b></span>");
      }).join("") + "</div></div>";
    h += '<div class="eyebrow">' + L("WhatsApp preview", "معاينة واتساب", "व्हाट्सऐप पूर्वावलोकन") + '</div><div class="fd-prev"><div class="bub out">' + esc(d.name) + " is running about " + min + " minutes late today. Your new estimated time is " + ex + "–" + addMin(ex, 10) + ". Sorry for the wait." +
      '<span class="ts">10:05 <span class="fd-tick read">✓✓</span></span></div><div class="fd-wbtns out"><span>I\'ll wait</span><span>Reschedule</span><span>See another doctor</span></div></div>';
    h += ui.toggle("dlShift", L("Shift everyone's ETA automatically", "تحديث الأوقات تلقائيًا", "सबका ETA अपने आप बदलें"), L("Queue, booking confirmations and “leave now” alerts move together.", "الطابور والتنبيهات تتحرك معًا.", "क़तार और अलर्ट साथ बदलेंगे।"), true) +
      ui.toggle("dlTv", L("Show on the waiting-room display", "عرض على شاشة الانتظار", "प्रतीक्षा स्क्रीन पर दिखाएँ"), L("Bilingual, no patient names.", "بلغتين، بدون أسماء.", "द्विभाषी, बिना नाम।"), true) +
      ui.toggle("dlRe", L("Re-notify if the delay grows by 10 min or more", "إعادة الإبلاغ إذا زاد التأخير", "देरी 10 मिनट बढ़े तो फिर सूचित करें"), "", true);
    return { title: L("Notify delay", "إشعار بالتأخير", "देरी सूचित करें"), body: h,
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Notify", "إبلاغ", "सूचित करें") + " " + aff.length + " " + L("patients", "مرضى", "मरीज़"), { cls: "pri", act: "fd-delay-send", arg: dId + "|" + min, icon: "send" }) };
  });
  NB.act("fd-delay-send", function (a) {
    var p = a.split("|"), d = NB.doc(p[0]); d.delay = +p[1];
    delete NB.state.ui.fdDlyDoc; delete NB.state.ui.fdDlyMin;
    closeToast(d.name + " +" + p[1] + " min · all affected patients notified in 41 s · ETAs shifted · lobby display updated");
  });

  /* =====================================================================
     Move a token to another doctor / position (drawer)
     ===================================================================== */
  NB.overlay("movequeue", function (tok) {
    var x = qEntry(tok) || qEntry("T-15"), p = NB.pat(x.p), cur = NB.doc(x.d);
    if (x.tok !== NB.state.ui._fdMvArg) { NB.state.ui._fdMvArg = x.tok; delete NB.state.ui.fdMvDoc; delete NB.state.ui.fdMvPos; }
    var target = NB.v("fdMvDoc", NB.D.doctors.find(function (d) { return d.id !== x.d && d.status !== "break"; }).id);
    var tq = function (d) { return NB.D.queue.filter(function (e) { return e.d === d.id && /waiting|arrived|vitals|booked/.test(e.st); }).length; };
    var td = NB.doc(target), pos = NB.v("fdMvPos", "order");
    var newEta = target === x.d ? (pos === "next" ? 5 : x.eta) : Math.max(4, tq(td) * td.avgMin + td.delay - (pos === "next" ? tq(td) * td.avgMin : 0));
    var h = '<div class="row nw"><span class="tok" style="font-size:22px">' + x.tok + '</span><div class="fl"><b>' + esc(nm(p)) + '</b><div class="small faint">' + esc(x.reason) + " · " + L("now with", "حاليًا مع", "अभी") + " " + esc(NB.dname(cur)) + " · ETA " + x.eta + " min</div></div>" + tt("< 10 s") + "</div>";
    h += '<div class="eyebrow">' + L("Move to", "نقل إلى", "यहाँ ले जाएँ") + '</div><div class="stack s8">' + NB.D.doctors.map(function (d) {
      var n = tq(d), same = d.spec === cur.spec;
      return ui.radio("fdMvDoc", d.id, NB.dname(d) + (d.id === x.d ? " · " + L("same doctor, new position", "نفس الطبيب", "वही डॉक्टर") : ""), d.spec + " · " + n + " " + L("waiting", "ينتظرون", "प्रतीक्षा में") + " · avg " + d.avgMin + " min" + (d.delay ? " · +" + d.delay + " late" : "") + (d.status === "break" ? " · on break" : "") + (same && d.id !== x.d ? " · same specialty" : ""), target);
    }).join("") + "</div>";
    h += '<div class="eyebrow">' + L("Position", "الموضع", "स्थान") + "</div>" + ui.seg("fdMvPos", [["order", L("Keep arrival order", "حسب الوصول", "आगमन क्रम")], ["next", L("Next in line", "التالي", "अगला")], ["end", L("End of queue", "آخر الطابور", "अंत में")]], "order");
    if (td.spec !== cur.spec) h += ui.note("warn", L("Different specialty. The receiving doctor gets a heads-up and can hand the patient back in one tap.", "تخصص مختلف؛ يُبلّغ الطبيب المستقبل.", "अलग विशेषज्ञता; डॉक्टर को सूचना मिलेगी।"));
    h += ui.field(L("Reason (audited)", "السبب (مُدقق)", "कारण (ऑडिट)"), "Balance load across doctors", { options: ["Balance load across doctors", "Doctor running late", "Patient asked for this doctor", "Booked with the wrong doctor", "Clinical priority (nurse request)"], full: true, id: "mv-reason" });
    h += '<div class="fd-prev"><div class="bub out">Your token ' + x.tok + " can see " + esc(td.name) + " in about " + newEta + " min instead of " + x.eta + " min. Tap Accept to move." + '<span class="ts">10:05 <span class="fd-tick">✓</span></span></div><div class="fd-wbtns out"><span>Accept</span><span>Keep my place</span></div></div>';
    h += ui.toggle("mvAsk", L("Ask the patient on WhatsApp first", "اسأل المريض أولاً", "पहले मरीज़ से पूछें"), L("If they don't answer in 3 min, the desk decides.", "إذا لم يرد خلال 3 د يقرر المكتب.", "3 मिनट में जवाब न मिले तो डेस्क तय करेगा।"), true) +
      ui.toggle("mvOthers", L("Tell others whose ETA changes", "أبلغ من تتغير أوقاتهم", "जिनका ETA बदले उन्हें बताएँ"), L("Neutral reason, no patient details.", "سبب محايد.", "तटस्थ कारण।"), true);
    return { title: L("Move in queue", "نقل في الطابور", "क़तार में बदलें"), body: h,
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Move", "نقل", "बदलें") + " " + x.tok, { cls: "pri", act: "fd-move-do", arg: x.tok + "|" + target + "|" + newEta, icon: "arrowright" }) };
  });
  NB.act("fd-move-do", function (a) {
    var p = a.split("|"), x = qEntry(p[0]), d = NB.doc(p[1]);
    if (x) { x.d = d.id; x.eta = +p[2]; }
    delete NB.state.ui.fdMvDoc; delete NB.state.ui.fdMvPos;
    closeToast(p[0] + " moved to " + d.name + " · new ETA " + p[2] + " min · patient asked on WhatsApp · logged");
  });

  /* =====================================================================
     reception.display · Waiting-room TV (1280 frame, no chrome)
     ===================================================================== */
  function clockNow() { var d = new Date(); return pad(d.getHours()) + ":" + pad(d.getMinutes()); }
  setInterval(function () { var el = document.getElementById("fd-clock"); if (el) el.textContent = clockNow(); }, 15000);

  NB.screen("reception.display", function (c) {
    var gcc = NB.gcc(), l2 = gcc ? "ar" : "hi", f = NB.fac();
    var bi = function (en, ar, hi) {
      var sec = l2 === "ar" ? ar : hi;
      return en + '<span class="fd-l2" lang="' + l2 + '"' + (l2 === "ar" ? ' dir="rtl"' : "") + ">" + sec + "</span>";
    };
    var rooms = [{ d: "d1", now: "T-14", next: ["T-17", "T-21"] }, { d: "d2", now: "T-12", next: ["T-15", "T-20"] }, { d: "d3", now: "T-16", next: ["T-19", "T-23"], call: true },
      { d: "d4", brk: "10:40", next: ["T-22"] }, { d: "d5", now: "T-11", next: ["T-24", "T-26"] }]
      .sort(function (a, b) { return parseInt(NB.doc(a.d).room.replace(/\D/g, ""), 10) - parseInt(NB.doc(b.d).room.replace(/\D/g, ""), 10); });
    var facL2 = gcc ? { f1: "مستوصف النور", f2: "مستشفى النور", f3: "استوديو د. خالد للأسنان" }[f.id] : { f1: "सुविधा मल्टी-स्पेशलिटी क्लिनिक", f2: "सुविधा अस्पताल", f3: "डॉ. मीरा स्किन क्लिनिक" }[f.id];
    var late = NB.D.doctors.filter(function (d) { return d.delay > 0; });
    var h = '<div class="fd-tv" dir="ltr">';
    h += '<div class="fd-tv-h"><span class="mark">' + ui.icon("logo", "lg") + '</span><div class="fl"><b>' + esc(f.name) + '</b><small><span lang="' + l2 + '"' + (l2 === "ar" ? ' dir="rtl" style="font-family:var(--font-ar)"' : ' style="font-family:var(--font-hi)"') + ">" + esc(facL2 || "") + "</span></small></div>" +
      '<div class="fd-clock"><b id="fd-clock">' + clockNow() + "</b><small>Sat 17 Oct · " + (gcc ? "السبت ١٧ أكتوبر" : "शनिवार 17 अक्टूबर") + "</small></div></div>";
    var call = rooms.find(function (r) { return r.call; }), cd = NB.doc(call.d);
    h += '<div class="fd-tv-call"><span class="lbl">' + bi("Now calling", "النداء الآن", "अभी बुला रहे हैं") + '</span><span class="tk">' + call.now + '</span><span class="to">→ ' + bi(esc(cd.room), cd.room.replace("Room", "غرفة"), cd.room.replace("Room", "कमरा")) + "</span></div>";
    h += '<div class="fd-tv-grid">' + rooms.map(function (r) {
      var d = NB.doc(r.d), dl = FD.docL2[r.d];
      return '<div class="fd-tv-room' + (r.call ? " call" : "") + '"><div class="rm"><span>' + esc(d.room) + "</span>" + (d.delay ? '<span class="fd-tv-late">' + ui.icon("clockh", "sm") + "+" + d.delay + " min</span>" : "") + "</div>" +
        '<div class="dn">' + esc(d.name) + '<small>' + esc(d.spec) + " · " + '<span lang="' + l2 + '"' + (l2 === "ar" ? ' dir="rtl" style="font-family:var(--font-ar)"' : ' style="font-family:var(--font-hi)"') + ">" + (l2 === "ar" ? esc(d.ar) + " · " + dl[2] : dl[0] + " · " + dl[1]) + "</span></small></div>" +
        '<div><div class="fd-tv-lab">' + bi("Now serving", "يُخدم الآن", "अभी") + "</div>" +
        (r.brk ? '<div class="fd-tv-now off">' + bi("Back at " + r.brk, "يعود " + r.brk, r.brk + " पर वापस") + "</div>" : '<div class="fd-tv-now">' + r.now + "</div>") + "</div>" +
        '<div><div class="fd-tv-lab" style="margin-bottom:6px">' + bi("Next", "التالي", "अगला") + '</div><div class="fd-tv-next">' + r.next.map(function (t) { return "<span>" + t + "</span>"; }).join("") + "</div></div></div>";
    }).join("") + "</div>";
    h += '<div class="fd-tv-row">' + late.map(function (d) {
      var dl = FD.docL2[d.id];
      return '<div class="fd-tv-note">' + ui.icon("clockh") + "<div>" + bi(esc(d.name) + " is running about " + d.delay + " minutes late. Thank you for your patience.",
        esc(d.ar) + " متأخر حوالي " + d.delay + " دقيقة. شكرًا لصبركم.", dl[0] + " लगभग " + d.delay + " मिनट देरी से हैं। धैर्य के लिए धन्यवाद।") + "</div></div>";
    }).join("") + '<div class="fd-tv-note info">' + ui.icon("pill") + "<div>" + bi("Pharmacy ready for pickup: <b>T-13 · T-18</b>", "الصيدلية جاهزة للاستلام: <b>T-13 · T-18</b>", "फ़ार्मेसी में तैयार: <b>T-13 · T-18</b>") + "</div></div></div>";
    h += '<div class="fd-tv-f"><span class="fd-qr">' + ui.icon("qr") + '</span><div class="fl" style="min-width:220px"><b style="color:var(--tx);font-size:15px">' + bi("Track your turn on WhatsApp. Wait outside if you like.", "تابع دورك عبر واتساب. يمكنك الانتظار خارجًا.", "व्हाट्सऐप पर अपनी बारी देखें। चाहें तो बाहर इंतज़ार करें।") + "</b></div>" +
      "<span>" + ui.icon("lock", "xs") + " Tokens only, never names</span>" + ui.pill("ok", "Live · updated 2 s ago", "checkcircle") +
      '<button class="btn sm ghost" data-go="reception.arrivals" title="Back to the front desk">' + ui.icon("chevleft", "sm flip") + "Desk</button></div>";
    return h + "</div>";
  }, { frame: "tv", chrome: false });

  /* =====================================================================
     reception.messages · WhatsApp inbox
     ===================================================================== */
  function session(t) {
    if (t.optout) return { k: "perm", txt: L("Opted out · SMS only", "ألغى الاشتراك", "ऑप्ट-आउट · केवल SMS"), icon: "lock", open: false, out: true };
    var left = 1440 - t.lastIn;
    if (left <= 0) return { k: "mute", txt: L("Window closed · template only", "النافذة مغلقة · قوالب فقط", "विंडो बंद · केवल टेम्पलेट"), icon: "lock", open: false };
    var hh = Math.floor(left / 60), mm = left % 60, txt = hh + " h " + pad(mm) + " m";
    if (left < 240) return { k: "warn", txt: L("Closes in", "تغلق خلال", "बंद होगी") + " " + txt, icon: "clockh", open: true };
    return { k: "ok", txt: L("Session open", "الجلسة مفتوحة", "सेशन खुला") + " · " + txt, icon: "checkcircle", open: true };
  }
  function ageChip(t) {
    if (t.optout) return "";
    if (!t.wait) return t.mode === "bot" ? ui.pill("sync", L("Bot", "آلي", "बॉट"), "bolt") : "";
    return ui.pill(t.wait >= 10 ? "urg" : "warn", t.wait + " min", t.wait >= 10 ? "bolt" : "clockh");
  }
  function tick(st) {
    if (!st) return "";
    if (st === "fail") return '<span class="fd-tick fail">!</span>';
    return '<span class="fd-tick' + (st === "read" ? " read" : "") + '">' + (st === "sent" ? "✓" : "✓✓") + "</span>";
  }
  function bubble(m) {
    if (m.w === "sys") return '<div class="wa-day">' + esc(m.t) + "</div>";
    var out = m.w !== "pt", dir = m.rtl ? ' dir="rtl"' : "";
    var auth = m.w === "bot" ? '<span class="fd-auth bot">' + ui.icon("bolt", "xs") + L("Nabd assistant · automated", "مساعد نبض · آلي", "नब्ज़ सहायक · स्वचालित") + "</span>"
      : m.w === "me" ? '<span class="fd-auth me">' + ui.icon("shielduser", "xs") + "Sneha · " + L("Reception", "الاستقبال", "रिसेप्शन") + "</span>"
      : m.w === "tpl" ? '<span class="fd-auth tpl">' + ui.icon("file", "xs") + "Template · " + esc(m.tpl) + "</span>" : "";
    var h = '<div class="bub ' + (out ? "out" : "in") + '"' + dir + ">" + auth + esc(m.t) + '<span class="ts">' + esc(m.time) + (out ? " " + tick(m.st) : "") + "</span></div>";
    if (m.btns && m.btns.length) h += '<div class="fd-wbtns ' + (out ? "out" : "in") + '"' + dir + ">" + m.btns.map(function (b) { return "<span>" + esc(b) + "</span>"; }).join("") + "</div>";
    if (m.st === "fail") h += '<div class="fd-fail">' + ui.icon("alertfill", "xs") + L("Not delivered", "لم يُسلّم", "नहीं पहुँचा") + ' <a class="fd-lnk" data-act="toast" data-arg="Retried · delivered">' + L("Retry", "إعادة", "फिर भेजें") + '</a><a class="fd-lnk" data-act="toast" data-arg="Sent as SMS with the same secure link">' + L("Send as SMS", "إرسال SMS", "SMS भेजें") + "</a></div>";
    return h;
  }

  NB.screen("reception.messages", function (c) {
    var th = FD.threads, f = NB.v("fdMF", "all"), curId = NB.v("fdThread", "t1"), mv = NB.v("fdMV", "list");
    var flt = { all: function () { return true; }, unread: function (t) { return t.unread > 0; }, reply: function (t) { return t.mode === "human" && (t.unread > 0 || t.wait > 0); }, bot: function (t) { return t.mode === "bot" && !t.optout; } };
    var list = th.filter(flt[f] || flt.all);
    var t = th.find(function (x) { return x.id === curId; }) || th[0];
    var unread = th.reduce(function (s, x) { return s + (x.unread ? 1 : 0); }, 0);
    var h = ui.header(L("WhatsApp inbox", "رسائل واتساب", "व्हाट्सऐप इनबॉक्स"), L("Clinic number", "رقم العيادة", "क्लिनिक नंबर") + " " + '<span class="ltr num">' + NB.mkt().phone + (NB.gcc() ? " 4 331 2200" : " 80 4719 2200") + "</span> · " + L("quality High", "الجودة عالية", "गुणवत्ता उच्च") + " · " + unread + " " + L("unread", "غير مقروءة", "अपठित"),
      ui.btn(L("Templates", "القوالب", "टेम्पलेट"), { ov: "fd-tpl", arg: t.id, icon: "file" }) + ui.btn(L("Notify delay", "إشعار بالتأخير", "देरी सूचित करें"), { ov: "delay", icon: "clockh" }));
    h += '<div class="row">' + tt(L("Median first reply 3 min", "متوسط أول رد 3 د", "पहला जवाब औसत 3 मिनट")) + ui.pill("sync", L("Assistant resolved 71% without staff", "المساعد حل 71%", "सहायक ने 71% सुलझाए"), "bolt") +
      ui.pill("warn", L("1 window closing", "نافذة تغلق", "1 विंडो बंद हो रही"), "clockh") + "</div>";

    var listPane = '<div class="fd-tl"><div class="fd-tl-h">' + ui.seg("fdMF", [["all", L("All", "الكل", "सभी")], ["unread", L("Unread", "غير مقروءة", "अपठित"), unread], ["reply", L("Needs reply", "بحاجة لرد", "जवाब चाहिए")], ["bot", L("Bot", "آلي", "बॉट")]]) + "</div>" +
      '<div class="fd-tl-list">' + (list.length ? list.map(function (x) {
        var o = who(x.who), last = x.msgs.filter(function (m) { return m.w !== "sys"; }).slice(-1)[0], ss = session(x);
        return '<div class="fd-th' + (x.id === t.id ? " on" : "") + '" data-act="fd-thread" data-arg="' + x.id + '">' + ui.av(o.name) +
          '<div class="fl"><b>' + esc(nm(o)) + (x.forWhom ? ' <span class="small faint">· ' + esc(x.forWhom) + "</span>" : "") + "</b><small" + (last.rtl ? ' dir="rtl"' : "") + ">" + (last.w === "pt" ? "" : ui.icon(last.w === "bot" ? "bolt" : "check", "xs") + " ") + esc(last.t) + "</small>" +
          '<div class="row" style="gap:4px;margin-top:5px">' + ui.pill(ss.k, ss.k === "ok" ? L("Open", "مفتوحة", "खुला") : ss.k === "warn" ? L("Closing", "تغلق", "बंद हो रहा") : ss.k === "perm" ? L("Opted out", "ألغى", "ऑप्ट-आउट") : L("Template only", "قوالب فقط", "केवल टेम्पलेट"), ss.icon) + ageChip(x) + "</div></div>" +
          '<div class="fd-thr"><small>' + esc(last.time.replace("Yesterday ", "Yest. ")) + "</small>" + (x.unread ? '<span class="fd-unr">' + x.unread + "</span>" : "") + "</div></div>";
      }).join("") : '<div class="empty">' + L("No conversations here.", "لا محادثات.", "कोई बातचीत नहीं।") + "</div>") + "</div></div>";

    var o = who(t.who), ss = session(t), botOn = NB.v("fdBot_" + t.id, t.mode === "bot");
    var conv = '<div class="fd-cv"><div class="fd-cv-h">' + (c.mobile ? '<button class="ibtn" data-set="fdMV" data-val="list" title="Back">' + ui.icon("chevleft", "sm flip") + "</button>" : "") + ui.av(o.name) +
      '<div class="fl"><b>' + esc(nm(o)) + (t.forWhom ? ' <span class="small faint">· ' + esc(t.forWhom) + "</span>" : "") + "</b><small>" + phone(t.phone, t.intl) + "</small></div>" + ui.pill(ss.k, ss.txt, ss.icon) +
      (t.who.charAt(0) === "p" ? ui.btn(L("Patient file", "ملف المريض", "मरीज़ फ़ाइल"), { cls: "sm ghost", ov: "patientfile", arg: t.who, icon: "file" }) : "") + "</div>";
    conv += '<div class="fd-ctx">' + (t.ctx ? '<button class="chip" data-go="' + t.ctx[2] + '">' + ui.icon(t.ctx[0], "sm") + esc(t.ctx[1]) + "</button>" : "") +
      (t.handed ? ui.pill("info", L("Handed over by assistant", "حوّلها المساعد", "सहायक ने सौंपा"), "users") : "") + "</div>";
    if (!t.optout) conv += '<div class="fd-botbar">' + ui.toggle("fdBot_" + t.id, botOn ? L("Assistant is replying in this chat", "المساعد يرد في هذه المحادثة", "सहायक इस चैट में जवाब दे रहा है") : L("You're handling this chat · assistant paused", "أنت تتولى المحادثة", "आप यह चैट संभाल रहे हैं"),
      botOn ? L("It stops the moment you type. Hand-overs are logged.", "يتوقف فور كتابتك.", "आपके टाइप करते ही रुक जाता है।") : L("Turn on to hand the chat back to the assistant.", "فعّل لإعادتها للمساعد.", "सहायक को लौटाने के लिए चालू करें।"), t.mode === "bot") + "</div>";
    conv += '<div class="wa"><div class="wa-b">' + '<div class="wa-day">' + L("Today", "اليوم", "आज") + "</div>" + t.msgs.map(bubble).join("") + "</div></div>";
    var comp = '<div class="fd-comp">';
    if (t.optout) comp += ui.note("info", L("This patient sent STOP. Only SMS reminders for booked visits go out; WhatsApp is blocked until they write again.", "أرسل المريض STOP.", "मरीज़ ने STOP भेजा।"), "lock");
    else if (!ss.open) comp += '<div class="note warn">' + ui.icon("lock") + '<div class="fl">' + L("The 24-hour window closed. WhatsApp only allows an approved template until the patient replies.", "أُغلقت نافذة 24 ساعة. يُسمح بقالب معتمد فقط.", "24 घंटे की विंडो बंद। केवल स्वीकृत टेम्पलेट।") + "</div>" +
      ui.btn(L("Choose template", "اختر قالبًا", "टेम्पलेट चुनें"), { cls: "sm pri", ov: "fd-tpl", arg: t.id, icon: "file" }) + "</div>";
    else {
      comp += '<div class="chips">' + FD.quick.map(function (qq) { return '<button class="chip" data-act="fd-wa-quick" data-arg="' + t.id + "|" + esc(qq) + '">' + esc(qq) + "</button>"; }).join("") + "</div>" +
        '<div class="fd-comp-in"><button class="ibtn" data-ov="fd-tpl" data-arg="' + t.id + '" title="Templates">' + ui.icon("file", "sm") + '</button><input id="fd-compose" placeholder="' + esc(L("Type a reply…", "اكتب ردًا…", "जवाब लिखें…")) + '" autocomplete="off">' +
        ui.btn(L("Send", "إرسال", "भेजें"), { cls: "pri", act: "fd-wa-send", arg: t.id, icon: "send" }) + "</div>" +
        '<span class="small faint">' + ui.icon("lock", "xs") + " " + L("Reports and results go as secure links with OTP, never in the message.", "التقارير كروابط آمنة فقط.", "रिपोर्ट केवल सुरक्षित लिंक से।") + "</span>";
    }
    conv += comp + "</div></div>";

    var inbox = '<div class="fd-inbox">' + (c.mobile ? (mv === "chat" ? conv : listPane) : listPane + conv) + "</div>";
    return h + inbox;
  });

  NB.act("fd-thread", function (id) {
    var t = FD.threads.find(function (x) { return x.id === id; }); if (t) t.unread = 0;
    NB.setUI({ fdThread: id, fdMV: "chat" });
  });
  function pushMsg(tid, m) {
    var t = FD.threads.find(function (x) { return x.id === tid; }); if (!t) return;
    t.msgs.push(m); t.wait = 0; t.unread = 0;
    if (m.w === "tpl") t.lastIn = Math.min(t.lastIn, 1500);
    NB.state.ui["fdBot_" + tid] = false;
  }
  NB.act("fd-wa-send", function (tid) {
    var el = document.getElementById("fd-compose"), v = el && el.value.trim();
    if (!v) { NB.toast("Type a message first, or pick a quick reply"); return; }
    pushMsg(tid, { w: "me", t: v, time: "10:05", st: "sent" }); NB.toast("Sent · assistant paused for this chat");
  });
  NB.act("fd-wa-quick", function (a) {
    var p = a.split("|"), txt = {
      "Clinic timings": "We're open Mon–Sat 09:00–20:00, Sunday closed.", "Directions & parking": "We're at " + NB.fac().area + ". Parking is in the basement; the lift goes to the 2nd floor.",
      "Consultation fee": "Consultation is " + NB.money(800).replace(/<[^>]+>/g, "") + "; follow-ups within 7 days are free.", "Send live queue link": "Here's your live queue link. We'll alert you when you're 2 away: nabd.link/q/T21",
      "Send payment link": "Here's a secure payment link: nabd.link/pay/8F2K", "We'll call you back": "Thanks! Someone from the desk will call you back within 15 minutes."
    }[p[1]] || p[1];
    pushMsg(p[0], { w: "me", t: txt, time: "10:05", st: "sent" }); NB.toast("Quick reply sent");
  });

  NB.overlay("fd-tpl", function (tid) {
    var t = FD.threads.find(function (x) { return x.id === tid; }) || FD.threads[0], o = who(t.who), sel = NB.v("fdTpl", session(t).open ? "appt_confirm" : "reopen_chat");
    var tp = FD.templates.find(function (x) { return x.id === sel; }) || FD.templates[0];
    var vals = { appt_confirm: [first(o), "Dr. Meera Iyer", "Sat 17 Oct, 11:40", "nabd.link/q/T28"], reopen_chat: [first(o), NB.fac().name], running_late: ["Dr. Vikram Shah", "15", "10:35–10:45"],
      document_ready: [first(o), NB.fac().name, "nabd.link/d/7QX2"], dues_reminder: [first(o), NB.money(350).replace(/<[^>]+>/g, ""), NB.fac().name, "nabd.link/pay/8F2K"], festive_offer: [first(o)] }[tp.id];
    var body = esc(tp.body).replace(/\{\{(\d)\}\}/g, function (m0, n) { return '<span class="fd-var">' + esc(vals[n - 1] || "{{" + n + "}}") + "</span>"; });
    var h = '<p class="small muted" style="margin:0">' + L("To", "إلى", "को") + " " + esc(nm(o)) + " · " + phone(t.phone, t.intl) + " · " + L("Only Meta-approved templates can start or re-open a conversation.", "القوالب المعتمدة فقط.", "केवल स्वीकृत टेम्पलेट।") + "</p>";
    h += '<div class="stack s8">' + FD.templates.map(function (x) {
      if (x.st !== "ok") return '<div class="radio" style="opacity:.55;cursor:not-allowed"><span class="dot"></span><div class="fl"><b>' + esc(x.name) + "</b><small>" + esc(x.cat) + " · " + esc(x.langs) + "</small></div>" + ui.pill("warn", L("In review", "قيد المراجعة", "समीक्षा में")) + "</div>";
      return ui.radio("fdTpl", x.id, x.name, x.cat + " · " + x.langs + " · " + x.id, sel);
    }).join("") + "</div>";
    h += '<div class="eyebrow">' + L("Fill the variables", "املأ المتغيرات", "वेरिएबल भरें") + '</div><div class="fgrid">' + tp.vars.map(function (v, i) { return ui.field("{{" + (i + 1) + "}} · " + v, vals[i] || "", { id: "tv-" + i }); }).join("") + "</div>";
    h += '<div class="eyebrow">' + L("Preview", "معاينة", "पूर्वावलोकन") + '</div><div class="fd-prev"><div class="bub out"><span class="fd-auth tpl">' + ui.icon("file", "xs") + "Template · " + esc(tp.id) + "</span>" + body + '<span class="ts">10:05 <span class="fd-tick">✓</span></span></div>' +
      (tp.btns.length ? '<div class="fd-wbtns out">' + tp.btns.map(function (b) { return "<span>" + esc(b) + "</span>"; }).join("") + "</div>" : "") + "</div>" +
      ui.note("ok", L("PHI check passed: no diagnosis, result or medicine names in the message.", "فحص الخصوصية ناجح.", "PHI जाँच पास।"), "shieldcheck");
    return { title: L("Send a template", "إرسال قالب", "टेम्पलेट भेजें"), body: h,
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Send template", "إرسال القالب", "टेम्पलेट भेजें"), { cls: "pri", act: "fd-tpl-send", arg: t.id + "|" + tp.id, icon: "send" }) };
  });
  NB.act("fd-tpl-send", function (a) {
    var p = a.split("|"), tp = FD.templates.find(function (x) { return x.id === p[1]; }), t = FD.threads.find(function (x) { return x.id === p[0]; }), o = who(t.who);
    var txt = tp.body.replace("{{1}}", first(o)).replace(/\{\{\d\}\}/g, "…");
    pushMsg(p[0], { w: "tpl", tpl: tp.id, t: txt, time: "10:05", st: "sent", btns: tp.btns });
    closeToast("Template " + tp.id + " sent · the chat re-opens when " + first(o) + " replies");
  });

  /* =====================================================================
     reception.billing · hand-off to the billing module
     ===================================================================== */
  NB.screen("reception.billing", function (c) {
    var pend = NB.D.queue.filter(function (x) { return x.st === "checkout"; });
    return ui.header(L("Checkout & billing", "الدفع والفواتير", "चेकआउट और बिलिंग"), L("Checkout runs in the billing workspace, with the same patient and visit.", "يتم الدفع في مساحة الفواتير.", "चेकआउट बिलिंग वर्कस्पेस में होता है।")) +
      '<div class="card hl"><div class="row"><span class="av lg">' + ui.icon("receipt") + '</span><div class="fl" style="min-width:200px"><h2 class="h">' + pend.length + " " + L("patient(s) ready for checkout", "مريض جاهز للدفع", "मरीज़ चेकआउट के लिए तैयार") + "</h2>" +
      '<p class="small muted" style="margin:2px 0 0">' + L("Consultation, pharmacy and lab items are already on one bill. Target: payment in under 30 s.", "كل البنود في فاتورة واحدة.", "सभी आइटम एक बिल पर। लक्ष्य: 30 से में भुगतान।") + "</p></div>" +
      ui.btn(L("Open checkout", "فتح الدفع", "चेकआउट खोलें"), { cls: "pri", go: "billing.checkout", icon: "arrowright" }) + "</div>" +
      (pend.length ? '<div class="lst" style="margin-top:14px">' + pend.map(function (x) {
        var p = NB.pat(x.p); return ui.li('<span class="tok">' + x.tok + "</span>", esc(nm(p)), esc(NB.dname(NB.doc(x.d))) + " · " + esc(x.reason), ui.pill("info", stLabel(x.st)), { go: "billing.checkout" });
      }).join("") + "</div>" : "") + "</div>" +
      '<div class="grid g2">' + ui.card(L("Invoices & receipts", "الفواتير والإيصالات", "इनवॉइस और रसीदें"), '<p class="small muted" style="margin:0 0 12px">' + L("Reprint or resend any invoice; tax follows the market (", "", "") + esc(NB.mkt().tax) + ").</p>" + ui.btn(L("Open invoices", "فتح الفواتير", "इनवॉइस खोलें"), { cls: "sm", go: "billing.invoices", icon: "file" }), { icon: "file" }) +
      ui.card(L("Day close", "إغلاق اليوم", "दिन समापन"), '<p class="small muted" style="margin:0 0 12px">' + L("Cash, card and UPI totals reconcile at the end of the day.", "مطابقة النقد والبطاقات.", "दिन के अंत में मिलान।") + "</p>" + ui.btn(L("Open day close", "فتح الإغلاق", "दिन समापन खोलें"), { cls: "sm", go: "billing.dayclose", icon: "wallet" }), { icon: "wallet" }) + "</div>";
  });
})();
