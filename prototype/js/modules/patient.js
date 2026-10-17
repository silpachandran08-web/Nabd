/* Patient module: the patient app (home with live visit, booking, records, family, privacy),
   the patient's WhatsApp conversation (5 scenarios) and Nabd Connect, the public booking site.
   Design rules for this module: plain language, big touch targets (>= 44px), every patient-facing
   string through L(), and no health details in WhatsApp message bodies (links + OTP instead). */
(function () {
  "use strict";
  var ui = NB.ui, L = NB.L;

  /* ---------------- Styles (pt- prefix, nb.css variables only) ---------------- */
  var css = [
    ".pt-col{width:100%;max-width:430px;margin-inline:auto;display:flex;flex-direction:column;gap:16px;min-width:0}",
    ".frame.mobile .pt-col{max-width:none}",
    ".pt-hello{display:flex;align-items:center;gap:12px}",
    ".pt-hello h1{margin:0;font:600 21px/28px var(--font);letter-spacing:-.3px}",
    "[dir=rtl] .pt-hello h1{font-family:var(--font-ar);letter-spacing:0}",
    ".lang-hi .pt-hello h1{font-family:var(--font-hi)}",
    ".pt-hello p{margin:0;color:var(--tx2);font-size:13px}",
    ".pt-fam{display:flex;gap:6px;overflow-x:auto;padding:4px 2px 2px}",
    ".pt-mem{flex:none;display:flex;flex-direction:column;align-items:center;gap:6px;width:62px;min-height:44px;background:none;border:0;color:var(--tx2);cursor:pointer;font:500 11.5px/14px inherit;font-family:inherit;padding:0}",
    ".pt-mem .av{width:48px;height:48px;font-size:15px;transition:box-shadow var(--dur)}",
    ".pt-mem.on{color:var(--tx)}",
    ".pt-mem.on .av{box-shadow:0 0 0 2px var(--bg-canvas),0 0 0 4px var(--acc)}",
    ".pt-mem .av.add{background:var(--s2);color:var(--tx2);border:1px dashed var(--b-str)}",
    ".pt-mem span.n{max-width:62px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
    ".pt-live{display:flex;flex-direction:column;gap:14px}",
    ".pt-tokrow{display:flex;align-items:center;gap:12px}",
    ".pt-tok{font:700 30px/34px var(--font);font-variant-numeric:tabular-nums;color:var(--acc);direction:ltr;unicode-bidi:isolate;letter-spacing:-.5px}",
    ".pt-tokl{font:600 10.5px/14px var(--font);letter-spacing:.8px;text-transform:uppercase;color:var(--tx3)}",
    "[dir=rtl] .pt-tokl{letter-spacing:0}",
    ".pt-eta{display:flex;align-items:center;gap:16px}",
    ".pt-eta .eta-ring{width:118px;height:118px}",
    ".pt-eta .eta-ring b{font-size:24px;line-height:28px;direction:ltr;unicode-bidi:isolate}",
    ".pt-eta .eta-ring small{display:block}",
    ".pt-etat{flex:1;min-width:0;display:flex;flex-direction:column;gap:4px}",
    ".pt-etat .big{font:600 17px/23px var(--font)}",
    ".pt-leave{display:flex;align-items:center;gap:12px;padding:12px 14px;border-radius:var(--r-md);background:var(--acc-tint);border:1px solid var(--acc-line)}",
    ".pt-leave .ic{width:40px;height:40px;border-radius:999px;background:var(--acc-grad);color:var(--on-acc);display:grid;place-items:center;flex:none}",
    ".pt-leave b{display:block;font-size:15px;line-height:20px}",
    ".pt-leave small{display:block;color:var(--tx2);font-size:12px;line-height:16px}",
    ".pt-leave.ok{background:var(--ok-t);border-color:rgba(52,211,153,.4)}",
    ".pt-leave.ok .ic{background:var(--ok)}",
    ".pt-2{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}",
    ".pt-big{height:auto;min-height:48px;font-size:14.5px;width:100%;white-space:normal;line-height:18px;padding:6px 12px}",
    ".pt-col .btn{height:auto;min-height:44px}",
    ".pt-col .btn.sm{min-height:40px;padding:0 14px}",
    ".pt-lane .tk{flex:1 1 0;min-width:0;height:52px;font-size:13px}",
    ".pt-lane .tk small{display:block;font:600 9px/11px var(--font);letter-spacing:.4px;text-transform:uppercase;margin-top:3px}",
    ".pt-lane .tk{display:flex;flex-direction:column;align-items:center;justify-content:center}",
    ".pt-qa{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}",
    ".pt-qa button{display:flex;flex-direction:column;align-items:center;gap:6px;background:none;border:0;color:var(--tx2);cursor:pointer;font:500 11.5px/14px inherit;font-family:inherit;padding:4px 0;min-height:44px;text-align:center}",
    ".pt-qa .ic{width:52px;height:52px;border-radius:16px;background:var(--s2);border:1px solid var(--b-sub);display:grid;place-items:center;color:var(--acc);position:relative}",
    ".pt-qa button:hover .ic{border-color:var(--acc-line)}",
    ".pt-dotc{position:absolute;top:-4px;inset-inline-end:-4px;min-width:18px;height:18px;border-radius:999px;background:var(--crit);color:var(--tx);font:600 10.5px/18px var(--font);padding:0 5px}",
    ".pt-sec{display:flex;align-items:center;gap:8px;margin-bottom:-4px}",
    ".pt-sec h2{margin:0;flex:1;font:600 15px/22px var(--font)}",
    ".pt-sec .btn{height:36px}",
    ".pt-ib{width:44px;height:44px;padding:0}",
    ".pt-it{display:flex;align-items:center;gap:12px;padding:12px 0;border-bottom:1px solid var(--div);min-width:0;min-height:56px}",
    ".pt-it:last-child{border-bottom:0;padding-bottom:0}.pt-it:first-child{padding-top:0}",
    ".pt-it.click{cursor:pointer}",
    ".pt-it .t{flex:1;min-width:0}",
    ".pt-it .t>b{display:block;font-size:14px;font-weight:500;line-height:20px}",
    ".pt-it .t>small{display:block;font-size:12px;line-height:17px;color:var(--tx3)}",
    ".pt-it .t>small b{color:var(--tx2);font-weight:500}",
    ".pt-ic{width:40px;height:40px;border-radius:12px;background:var(--s3);display:grid;place-items:center;color:var(--tx2);flex:none}",
    ".pt-ic.acc{background:var(--acc-tint2);color:var(--acc)}.pt-ic.ok{background:var(--ok-t);color:var(--ok)}.pt-ic.warn{background:var(--warn-t);color:var(--warn)}.pt-ic.info{background:var(--info-t);color:var(--info)}.pt-ic.sub{background:var(--sub-t);color:var(--sub)}.pt-ic.crit{background:var(--crit-t);color:var(--crit)}",
    ".pt-req{border-color:rgba(232,179,60,.45);background:linear-gradient(180deg,rgba(232,179,60,.08),rgba(232,179,60,0)),var(--s1)}",
    ".pt-count{display:flex;align-items:center;gap:8px;font-size:12px;color:var(--tx2)}",
    ".pt-count .bar{flex:1;height:6px}",
    /* booking */
    ".pt-taps{display:inline-flex;align-items:center;gap:6px;height:28px;padding:0 10px;border-radius:999px;background:var(--s2);border:1px solid var(--b-def);font:600 11.5px/1 var(--font);color:var(--tx2)}",
    ".pt-step{display:flex;flex-direction:column;gap:12px}",
    ".pt-sh{display:flex;align-items:center;gap:10px}",
    ".pt-sh .no{width:26px;height:26px;border-radius:999px;background:var(--acc-fill);color:var(--on-acc);display:grid;place-items:center;font:700 12.5px/1 var(--font);flex:none}",
    ".pt-sh .no.done{background:var(--ok-t);color:var(--ok)}",
    ".pt-sh h3{margin:0;flex:1;font:600 15px/21px var(--font)}",
    ".pt-done{display:flex;align-items:center;gap:12px;padding:10px 14px;border-radius:var(--r-md);background:var(--s1);border:1px solid var(--b-sub);min-height:56px}",
    ".pt-done .t{flex:1;min-width:0}.pt-done .t small{display:block;color:var(--tx3);font-size:11.5px}.pt-done .t b{display:block;font-weight:500;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
    ".pt-chips{display:flex;flex-wrap:wrap;gap:8px}",
    ".pt-chip{display:inline-flex;align-items:center;gap:8px;min-height:44px;padding:0 14px;border-radius:14px;background:var(--s2);border:1px solid var(--b-def);font:500 13.5px/1.2 inherit;font-family:inherit;color:var(--tx);cursor:pointer;text-align:start}",
    ".pt-chip .ico{color:var(--acc)}",
    ".pt-needs{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}",
    ".pt-needs .pt-chip{min-height:60px;padding:8px 12px}",
    ".pt-chip.on{background:var(--acc-tint);border-color:var(--acc-line);color:var(--acc-300)}",
    ".pt-chip small{display:block;color:var(--tx3);font-size:11px;line-height:14px}",
    ".pt-opt{display:flex;flex-direction:column;gap:12px}",
    ".pt-opth{display:flex;align-items:flex-start;gap:12px}",
    ".pt-opth .t{flex:1;min-width:0}.pt-opth .t b{display:block;font-size:14.5px;font-weight:600}.pt-opth .t small{display:block;color:var(--tx3);font-size:12px;line-height:17px}",
    ".pt-wait{display:flex;flex-direction:column;align-items:flex-end;gap:2px;flex:none;text-align:end}",
    ".pt-wait b{font:600 16px/20px var(--font);font-variant-numeric:tabular-nums}",
    ".pt-wait small{font-size:10.5px;color:var(--tx3);text-transform:uppercase;letter-spacing:.5px}",
    "[dir=rtl] .pt-wait small{letter-spacing:0}",
    ".pt-slots{display:flex;flex-wrap:wrap;gap:8px}",
    ".pt-slot{min-width:84px;min-height:52px;padding:6px 10px;border-radius:12px;background:var(--s2);border:1px solid var(--b-def);cursor:pointer;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;font-family:inherit;color:var(--tx)}",
    ".pt-slot b{font:600 14px/18px var(--font);font-variant-numeric:tabular-nums}",
    ".pt-slot small{font-size:10.5px;line-height:13px;color:var(--tx3)}",
    ".pt-slot.hint{border-color:var(--acc-line)}",
    ".pt-slot .h{font:600 9.5px/12px var(--font);letter-spacing:.4px;text-transform:uppercase;color:var(--acc)}",
    "[dir=rtl] .pt-slot .h{letter-spacing:0}",
    ".pt-slot.on{background:var(--acc-grad);border-color:transparent;color:var(--on-acc)}",
    ".pt-slot.on small,.pt-slot.on .h{color:var(--on-acc)}",
    ".pt-res{display:flex;flex-direction:column;align-items:center;text-align:center;gap:10px;padding:8px 0}",
    ".pt-res .ic{width:64px;height:64px;border-radius:999px;display:grid;place-items:center}",
    ".pt-res .ic.ok{background:var(--ok-t);color:var(--ok)}.pt-res .ic.warn{background:var(--warn-t);color:var(--warn)}",
    ".pt-res h2{margin:0;font:600 19px/26px var(--font)}",
    /* records */
    ".pt-fach{display:flex;align-items:center;gap:8px;font:600 11.5px/16px var(--font);color:var(--tx2);padding:4px 2px}",
    ".pt-fach .ico{color:var(--tx3)}",
    ".pt-fach b{color:var(--tx);font-weight:600}",
    ".pt-rng{position:relative;height:8px;border-radius:99px;background:var(--s3);margin:8px 0 4px}",
    ".pt-rng .ok{position:absolute;top:0;bottom:0;background:var(--ok-t);border:1px solid rgba(52,211,153,.45);border-radius:99px}",
    ".pt-rng .mk{position:absolute;top:50%;width:14px;height:14px;margin-top:-7px;margin-inline-start:-7px;border-radius:99px;background:var(--tx);border:3px solid var(--s1);box-shadow:0 0 0 1px var(--b-str)}",
    ".pt-rng .mk.hi{background:var(--warn)}",
    ".pt-qr{display:grid;place-items:center;padding:14px;background:var(--tx);border-radius:var(--r-lg);width:max-content;margin-inline:auto}",
    ".pt-qr svg{display:block}",
    ".pt-idc{display:flex;align-items:center;gap:12px}",
    /* family */
    ".pt-cap{display:grid;grid-template-columns:minmax(0,1fr) repeat(6,34px);align-items:center;gap:6px 2px;font-size:12.5px}",
    ".pt-cap .h{display:grid;place-items:center;color:var(--tx3)}",
    ".pt-cap .c{display:grid;place-items:center;height:30px}",
    ".pt-cap .c.y{color:var(--ok)}.pt-cap .c.n{color:var(--tx4)}",
    ".pt-cap .nm{min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-weight:500}",
    ".pt-cap .nm small{display:block;color:var(--tx3);font-weight:400;font-size:11px}",
    ".pt-cap .hr{grid-column:1/-1}",
    ".pt-legend{display:flex;flex-wrap:wrap;gap:4px 12px;font-size:11px;color:var(--tx3)}",
    ".pt-legend span{display:inline-flex;align-items:center;gap:4px}",
    /* WhatsApp */
    ".pt-wa-shell{display:flex;flex-direction:column;flex:1 1 0;min-height:0;width:100%;max-width:430px;margin-inline:auto}",
    ".frame.desktop .pt-wa-shell,.frame.tablet .pt-wa-shell{border-inline:1px solid var(--b-sub)}",
    ".pt-scn{display:flex;gap:3px;overflow-x:auto;padding:8px 8px 6px;background:var(--bg-nav);flex:none}",
    ".pt-scn button{flex:none;display:inline-flex;align-items:center;gap:6px;height:36px;padding:0 8px;border-radius:999px;border:1px solid var(--b-def);background:var(--s2);color:var(--tx2);font:600 11.5px/1 inherit;font-family:inherit;cursor:pointer;white-space:nowrap}",
    ".pt-scn button i{font-style:normal;width:18px;height:18px;border-radius:99px;background:var(--s3);display:grid;place-items:center;font-size:10.5px}",
    ".pt-scn button.on{background:var(--acc-fill);border-color:transparent;color:var(--on-acc)}",
    ".pt-scn button.on i{background:rgba(4,35,31,.2)}",
    ".pt-scm{display:flex;align-items:center;gap:8px;padding:2px 12px 8px;background:var(--bg-nav);font-size:11.5px;color:var(--tx3);flex:none;border-bottom:1px solid var(--b-sub)}",
    ".pt-scm .fl{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
    ".pt-scm b{color:var(--tx2);font-weight:600}",
    ".pt-scm .okc b{color:var(--ok)}",
    ".pt-scm button{background:none;border:0;color:var(--acc);font:600 11.5px/1 inherit;font-family:inherit;cursor:pointer;display:inline-flex;align-items:center;gap:4px;min-height:32px;padding:0 4px}",
    ".pt-wa-h .av{background:var(--acc-grad);color:var(--on-acc)}",
    ".pt-vf{display:inline-grid;place-items:center;width:15px;height:15px;border-radius:99px;background:var(--wa);color:var(--wa-bg);vertical-align:-2px;margin-inline-start:4px}",
    ".pt-vf .ico{width:10px;height:10px;stroke-width:3}",
    ".pt-wa-h .fl b{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
    ".pt-wa-h .hb{background:none;border:0;color:var(--tx2);display:grid;place-items:center;width:36px;height:36px;cursor:pointer;flex:none}",
    ".pt-b{padding-bottom:6px}",
    ".pt-ts{display:block;text-align:end;font-size:10.5px;line-height:13px;color:var(--tx3);margin-top:2px;white-space:nowrap}",
    ".bub.out .pt-ts{color:var(--tx2)}",
    ".pt-tick{color:var(--info);letter-spacing:-3px;margin-inline-start:4px}",
    ".pt-b .wbtn{margin:8px -10px -6px;display:flex;align-items:center;justify-content:center;gap:6px;color:var(--info);min-height:40px;padding:0 9px}",
    ".pt-b .wbtn+.wbtn{margin-top:6px}",
    ".pt-b .tt{display:block;font-weight:600;margin-bottom:2px}",
    ".pt-lk{display:flex;align-items:center;gap:8px;margin:6px 0 2px;padding:8px 10px;border-radius:8px;background:var(--wa-bg);font-size:12px;line-height:16px;white-space:normal}",
    ".pt-lk .ico{color:var(--wa)}",
    ".pt-lk small{display:block;color:var(--tx3);font-size:11px}",
    ".pt-code{font:700 20px/26px var(--font);letter-spacing:3px;direction:ltr;unicode-bidi:isolate;font-variant-numeric:tabular-nums}",
    ".pt-wrb{display:flex;flex-direction:column;gap:4px;align-self:flex-start;width:82%;margin-top:-2px}",
    ".pt-wrb button{min-height:44px;background:var(--wa-in);border:0;border-radius:10px;color:var(--info);font:500 14px/18px inherit;font-family:inherit;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:6px;padding:6px 10px}",
    ".pt-wrb button:hover{filter:brightness(1.15)}",
    ".pt-wl{align-self:flex-start;width:88%;background:var(--wa-in);border-radius:12px;overflow:hidden;margin-top:-2px}",
    ".pt-wl .hd{padding:10px 14px;font-size:12px;color:var(--tx3);border-bottom:1px solid var(--b-sub)}",
    ".pt-wl button{display:flex;align-items:center;gap:12px;width:100%;min-height:56px;padding:8px 14px;background:none;border:0;border-bottom:1px solid var(--b-sub);color:var(--tx);cursor:pointer;text-align:start;font-family:inherit}",
    ".pt-wl button:last-child{border-bottom:0}",
    ".pt-wl button .t{flex:1;min-width:0}.pt-wl button b{display:block;font-size:14px;font-weight:500}.pt-wl button small{display:block;font-size:12px;color:var(--tx3);line-height:16px}",
    ".pt-wl .rd{width:18px;height:18px;border-radius:99px;border:2px solid var(--tx3);flex:none}",
    ".pt-wa-b{background:var(--wa-bg)}",
    ".pt-wa-end{align-self:center;font-size:11.5px;color:var(--tx3);padding:8px 12px;text-align:center}",
    ".pt-wa-in .ib{width:40px;height:40px;border-radius:99px;display:grid;place-items:center;background:var(--wa);color:var(--wa-bg);flex:none}",
    ".pt-wa-in .ico{flex:none}",
    /* Connect */
    ".pt-cx{display:flex;flex-direction:column;min-width:0;width:100%}",
    ".pt-cxn{display:flex;align-items:center;gap:12px;padding:12px 24px;border-bottom:1px solid var(--b-sub);background:var(--bg-nav);position:sticky;top:0;z-index:2}",
    ".pt-cxn .logo{padding:0}",
    ".pt-cxn .sp{flex:1}",
    ".pt-cxw{width:100%;max-width:1180px;margin-inline:auto;padding:24px;display:flex;flex-direction:column;gap:20px;min-width:0}",
    ".pt-hero{display:flex;flex-direction:column;gap:14px;padding:24px;border-radius:var(--r-xl);border:1px solid var(--acc-line);background:linear-gradient(135deg,rgba(46,211,224,.12),rgba(62,224,196,.03) 60%),var(--s1)}",
    ".pt-hero h1{margin:0;font:600 28px/36px var(--font);letter-spacing:-.4px;text-wrap:balance}",
    "[dir=rtl] .pt-hero h1{font-family:var(--font-ar);letter-spacing:0}",
    ".lang-hi .pt-hero h1{font-family:var(--font-hi)}",
    ".pt-hero p{margin:0;color:var(--tx2)}",
    ".pt-sbar{display:grid;grid-template-columns:minmax(0,1.4fr) minmax(0,1fr) auto;gap:10px;align-items:stretch}",
    ".pt-sbar .field{min-height:52px;justify-content:center}",
    ".pt-sbar .btn{height:auto;min-height:52px}",
    ".pt-cxg{display:grid;grid-template-columns:250px minmax(0,1fr);gap:24px;align-items:start}",
    ".pt-flt{display:flex;flex-direction:column;gap:16px}",
    ".pt-flt .fh{font:600 10.5px/14px var(--font);letter-spacing:.9px;text-transform:uppercase;color:var(--tx3);margin-bottom:8px}",
    "[dir=rtl] .pt-flt .fh{letter-spacing:0}",
    ".pt-flt .chip{min-height:36px}",
    ".pt-rc{display:grid;grid-template-columns:auto minmax(0,1fr) 200px;gap:14px 16px;align-items:start}",
    ".pt-rc .av{width:56px;height:56px;font-size:17px}",
    ".pt-rc h3{margin:0;font:600 16px/22px var(--font)}",
    ".pt-rc .meta{color:var(--tx2);font-size:12.5px;line-height:18px}",
    ".pt-rc .pt-rside{display:flex;flex-direction:column;gap:6px;align-items:stretch;border-inline-start:1px solid var(--div);padding-inline-start:16px}",
    ".pt-rc .acts{grid-column:2/-1;display:flex;flex-wrap:wrap;gap:8px}",
    ".pt-rc .acts .btn{min-height:44px}",
    ".pt-live-w{display:flex;align-items:center;gap:8px;font:600 20px/24px var(--font);font-variant-numeric:tabular-nums}",
    ".pt-live-w .pulse{width:8px;height:8px;color:var(--ok)}",
    ".pt-wab{background:var(--wa);color:var(--on-acc);border-color:transparent}",
    ".pt-wab:hover{color:var(--on-acc);filter:brightness(1.06)}",
    ".pt-stars{color:var(--warn);font-size:12.5px;white-space:nowrap}",
    ".pt-map{border-radius:var(--r-lg);overflow:hidden;border:1px solid var(--b-sub);background:var(--sunk)}",
    ".pt-map svg{display:block;width:100%;height:auto}",
    ".pt-tim{display:grid;grid-template-columns:auto 1fr;gap:6px 16px;font-size:13px}",
    ".pt-tim dt{color:var(--tx3)}.pt-tim dd{margin:0;text-align:end}",
    ".pt-qs{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px}",
    ".pt-qd{padding:12px 14px;border-radius:var(--r-md);background:var(--s2);border:1px solid var(--b-sub);display:flex;flex-direction:column;gap:6px}",
    ".frame.mobile .pt-cxn,.frame.tablet .pt-cxn{padding:10px 16px}",
    ".frame.mobile .pt-cxw{padding:16px;gap:16px}",
    ".frame.mobile .pt-hero{padding:18px}",
    ".frame.mobile .pt-hero h1{font-size:22px;line-height:29px}",
    ".frame.mobile .pt-sbar,.frame.tablet .pt-sbar{grid-template-columns:minmax(0,1fr)}",
    ".frame.mobile .pt-cxg,.frame.tablet .pt-cxg{grid-template-columns:minmax(0,1fr)}",
    ".frame.mobile .pt-flt{flex-direction:row;overflow-x:auto;gap:8px}",
    ".frame.mobile .pt-flt .fh{display:none}",
    ".frame.mobile .pt-flt>div{flex:none}",
    ".frame.mobile .pt-flt .chips{flex-wrap:nowrap}",
    ".frame.mobile .pt-rc{grid-template-columns:auto minmax(0,1fr)}",
    ".frame.mobile .pt-rc .pt-rside{grid-column:1/-1;border-inline-start:0;padding-inline-start:0;border-top:1px solid var(--div);padding-top:12px;flex-direction:row;flex-wrap:wrap;align-items:center;justify-content:space-between}",
    ".frame.mobile .pt-rc .acts{grid-column:1/-1}",
    ".frame.mobile .pt-rc .acts .btn{flex:1}",
    ".frame.mobile .pt-cxn .hide-s{display:none}",
    "@media (max-width:760px){.pt-wa-shell{max-width:none}}"
  ].join("\n");
  (function inject() {
    if (document.getElementById("pt-style")) return;
    var st = document.createElement("style"); st.id = "pt-style"; st.textContent = css; (document.body || document.head).appendChild(st); // after nb.css so equal-specificity rules win
  })();

  /* ---------------- Helpers ---------------- */
  var esc = ui.esc;
  function t(en, ar, hi) { return esc(L(en, ar, hi)); }
  function nm(x) { return '<span class="num ltr">' + x + "</span>"; }
  function F(i) { var fs = NB.facilities(); return fs[i] || fs[0]; }
  function fshort(i) {
    var g = NB.gcc();
    var a = g ? [["Al Noor Polyclinic", "مجمع النور الطبي"], ["Al Noor Hospital", "مستشفى النور"], ["Dr. Khalid Dental Studio", "عيادة د. خالد للأسنان"]]
      : [["Suvidha Clinic", "عيادة سوفيدها", "सुविधा क्लिनिक"], ["Suvidha Hospital", "مستشفى سوفيدها", "सुविधा हॉस्पिटल"], ["Dr. Meera's Skin Clinic", "عيادة د. ميرا للجلدية", "डॉ. मीरा स्किन क्लिनिक"]];
    var x = a[i] || a[0];
    return L(x[0], x[1], x[2]);
  }
  function fname(i) { return NB.rtl() ? fshort(i) : F(i).name; }
  function dn(id) { return esc(NB.dname(NB.doc(id))); }
  function payWay() { return NB.gcc() ? L("Card / Apple Pay", "بطاقة / Apple Pay", "कार्ड / Apple Pay") : "UPI"; }
  function idRail() {
    var m = NB.mkt();
    return { india: ["ABHA number", "رقم ABHA", "ABHA नंबर", "Aadhaar OTP or the ABHA app"], uae: ["Emirates ID", "الهوية الإماراتية", "एमिरेट्स आईडी", "UAE PASS"],
      ksa: ["National ID / Iqama", "الهوية الوطنية / الإقامة", "नेशनल आईडी / इक़ामा", "Nafath"], qatar: ["QID", "البطاقة القطرية", "QID", "Tawtheeq (NAS)"] }[m.id] || ["ID", "الهوية", "आईडी", "OTP"];
  }
  function spec(s) {
    var M = { "General Medicine": ["General physician", "طبيب عام", "सामान्य चिकित्सक"], "Dermatology": ["Skin & hair", "الجلدية والشعر", "त्वचा और बाल"],
      "Paediatrics": ["Child health", "طب الأطفال", "बाल रोग"], "Cardiology": ["Heart", "القلب", "हृदय रोग"], "Obstetrics & Gynaecology": ["Women's health", "صحة المرأة", "महिला स्वास्थ्य"] }[s];
    return M ? L(M[0], M[1], M[2]) : s;
  }
  function ico(n, c) { return '<span class="pt-ic ' + (c || "") + '">' + ui.icon(n, "sm") + "</span>"; }
  function it(lead, title, sub, right, o) {
    o = o || {};
    var a = o.act ? ' data-act="' + o.act + '"' + (o.arg != null ? ' data-arg="' + esc(o.arg) + '"' : "") : o.ov ? ' data-ov="' + o.ov + '"' + (o.arg != null ? ' data-arg="' + esc(o.arg) + '"' : "") : o.go ? ' data-go="' + o.go + '"' : "";
    return '<div class="pt-it' + (a ? " click" : "") + '"' + a + (a ? ' role="button" tabindex="0"' : "") + ">" + (lead || "") + '<div class="t"><b>' + title + "</b>" + (sub ? "<small>" + sub + "</small>" : "") + "</div>" + (right || "") + "</div>";
  }
  function sec(title, right) { return '<div class="pt-sec"><h2>' + title + "</h2>" + (right || "") + "</div>"; }
  function chev() { return ui.icon("chevright", "sm faint flip"); }
  // Deterministic QR-like pattern (illustrative only).
  function qr(seed, px) {
    var n = 25, s = 0, d = "";
    for (var i = 0; i < seed.length; i++) s = (s * 31 + seed.charCodeAt(i)) >>> 0;
    function rnd() { s = (s * 1103515245 + 12345) >>> 0; return (s >>> 16) & 1; }
    function finder(x, y) { return "M" + x + " " + y + "h7v7h-7zM" + (x + 1) + " " + (y + 1) + "v5h5v-5zM" + (x + 2) + " " + (y + 2) + "h3v3h-3z"; }
    for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) {
      var inF = (x < 8 && y < 8) || (x > n - 9 && y < 8) || (x < 8 && y > n - 9);
      if (!inF && rnd()) d += "M" + x + " " + y + "h1v1h-1z";
    }
    d += finder(0, 0) + finder(n - 7, 0) + finder(0, n - 7);
    return '<div class="pt-qr"><svg width="' + px + '" height="' + px + '" viewBox="0 0 ' + n + " " + n + '" shape-rendering="crispEdges" role="img" aria-label="QR code"><path fill-rule="evenodd" fill="var(--bg-root)" d="' + d + '"/></svg></div>';
  }
  function mapSvg(h) {
    return '<div class="pt-map"><svg viewBox="0 0 400 ' + h + '" preserveAspectRatio="xMidYMid slice" aria-hidden="true">' +
      '<rect width="400" height="' + h + '" fill="var(--sunk)"/>' +
      '<path d="M0 ' + (h * 0.3) + ' L400 ' + (h * 0.42) + ' M0 ' + (h * 0.78) + ' L400 ' + (h * 0.66) + ' M120 0 L150 ' + h + ' M290 0 L270 ' + h + '" stroke="var(--s3)" stroke-width="14" fill="none"/>' +
      '<path d="M0 ' + (h * 0.55) + ' C120 ' + (h * 0.5) + ' 220 ' + (h * 0.62) + ' 400 ' + (h * 0.5) + '" stroke="var(--b-str)" stroke-width="6" fill="none"/>' +
      '<rect x="170" y="' + (h * 0.12) + '" width="60" height="40" rx="6" fill="var(--s2)"/><rect x="40" y="' + (h * 0.58) + '" width="60" height="34" rx="6" fill="var(--s2)"/><rect x="310" y="' + (h * 0.72) + '" width="54" height="30" rx="6" fill="var(--s2)"/>' +
      '<path d="M60 ' + (h * 0.85) + ' Q140 ' + (h * 0.55) + ' 205 ' + (h * 0.5) + '" stroke="var(--acc)" stroke-width="4" stroke-dasharray="2 8" stroke-linecap="round" fill="none"/>' +
      '<circle cx="60" cy="' + (h * 0.85) + '" r="7" fill="var(--info)" stroke="var(--tx)" stroke-width="2"/>' +
      '<path d="M205 ' + (h * 0.5) + ' c-12 -16 -18 -24 -18 -32 a18 18 0 1 1 36 0 c0 8 -6 16 -18 32z" fill="var(--acc)"/><circle cx="205" cy="' + (h * 0.5 - 32) + '" r="6" fill="var(--on-acc)"/>' +
      "</svg></div>";
  }

  /* ---------------- Sample data for the patient side ---------------- */
  NB.D.patientapp = {
    members: [
      { id: "me", p: "p3", name: "Rakesh Menon", ar: "راكيش مينون", rel: ["You", "أنت", "आप"], age: 58 },
      { id: "latha", name: "Latha Menon", ar: "لاثا مينون", rel: ["Wife", "الزوجة", "पत्नी"], age: 54 },
      { id: "meenu", name: "Meenu Menon", ar: "مينو مينون", rel: ["Daughter", "الابنة", "बेटी"], age: 15, minor: true },
      { id: "amma", name: "Kamala Menon", ar: "كامالا مينون", rel: ["Mother", "الأم", "माँ"], age: 81 }
    ],
    // Live visit: Rakesh, booked with the doctor who is running late today (data: d2 delay 15).
    visit: { tok: "T-24", d: "d2", f: 0, travel: 12, lane: ["T-20", "T-21", "T-22", "T-23", "T-24", "T-25", "T-26"] },
    upcoming: [
      { who: "amma", d: "d2", f: 0, when: ["Mon 19 Oct · Lab 08:00 → doctor 08:40", "الاثنين 19 أكتوبر · المختبر 08:00 ← الطبيب 08:40", "सोम 19 अक्टूबर · लैब 08:00 → डॉक्टर 08:40"], note: ["Fasting blood test first, so she doesn't wait hungry", "تحليل الدم صائمًا أولًا حتى لا تنتظر جائعة", "पहले खाली पेट ब्लड टेस्ट, ताकि भूखे इंतज़ार न करना पड़े"], st: "ok" },
      { who: "meenu", d: "d3", f: 0, when: ["Sat 24 Oct · 16:20", "السبت 24 أكتوبر · 16:20", "शनि 24 अक्टूबर · 16:20"], note: ["Vaccine due · booked by you as guardian", "موعد لقاح · حجزته بصفتك وليّ الأمر", "टीका बाकी · आपने अभिभावक के रूप में बुक किया"], st: "ok" },
      { who: "me", d: "d4", f: 1, when: ["Tue 3 Nov · 11:00", "الثلاثاء 3 نوفمبر · 11:00", "मंगल 3 नवंबर · 11:00"], note: ["Waiting for the hospital to accept", "بانتظار قبول المستشفى", "अस्पताल की मंज़ूरी का इंतज़ार"], st: "warn" }
    ],
    rx: [
      { id: "RX-88412", date: "17 Oct 2026", d: "d2", f: 0, until: "16 Nov 2026", left: 30, total: 30, refills: 2, st: "active",
        items: [["m4", "1-0-1", "30 days", 60, "After food"], ["m5", "0-0-1", "30 days", 30, ""]] },
      { id: "RX-80211", date: "02 Aug 2026", d: "d4", f: 1, until: "31 Oct 2026", left: 14, total: 90, refills: 0, st: "active",
        items: [["m12", "0-0-1", "90 days", 90, "At night"]] },
      { id: "RX-87120", date: "12 Sep 2026", d: "d2", f: 0, until: "19 Sep 2026", left: 0, total: 7, refills: 0, st: "expired",
        items: [["m3", "1-1-1 if fever", "5 days", 15, ""]] }
    ],
    labs: [
      { id: "LAB-5521", date: "15 Oct 2026", f: 0, name: ["HbA1c & fasting sugar", "السكر التراكمي والسكر الصائم", "HbA1c और फ़ास्टिंग शुगर"], by: "d2", st: "released",
        rows: [["HbA1c", 7.4, "%", 4.0, 5.6, 3, 10], ["Fasting glucose", 138, "mg/dL", 70, 100, 50, 200]] },
      { id: "LAB-5522", date: "15 Oct 2026", f: 0, name: ["Kidney function", "وظائف الكلى", "किडनी फ़ंक्शन"], by: "d2", st: "released",
        rows: [["Creatinine", 1.1, "mg/dL", 0.7, 1.3, 0.3, 2.0], ["Urea", 32, "mg/dL", 15, 40, 5, 70]] },
      { id: "LAB-5530", date: "17 Oct 2026", f: 0, name: ["Lipid profile", "دهون الدم", "लिपिड प्रोफ़ाइल"], by: "d2", st: "processing", rows: [] },
      { id: "LAB-4410", date: "02 Aug 2026", f: 1, name: ["ECG & echo report", "تخطيط وصدى القلب", "ईसीजी और इको रिपोर्ट"], by: "d4", st: "released", rows: [] }
    ],
    bills: [
      { id: "INV-2026-04412", date: "17 Oct 2026", f: 0, what: ["Consultation", "استشارة", "परामर्श"], amt: 600, st: "due" },
      { id: "INV-2026-04388", date: "15 Oct 2026", f: 0, what: ["Lab tests (3)", "تحاليل (3)", "लैब टेस्ट (3)"], amt: 1900, st: "paid", how: "UPI" },
      { id: "INV-2026-03120", date: "02 Aug 2026", f: 1, what: ["Cardiology consult + ECG", "استشارة قلب + تخطيط", "हृदय परामर्श + ईसीजी"], amt: 2450, st: "ins", how: "Star Health" }
    ],
    docs: [
      { f: 1, n: ["Discharge note · day care", "ملخص الخروج · رعاية يومية", "डिस्चार्ज नोट · डे केयर"], d: "02 Aug 2026", ic: "file" },
      { f: 0, n: ["Referral letter to the hospital", "خطاب تحويل إلى المستشفى", "अस्पताल के लिए रेफ़रल पत्र"], d: "28 Jul 2026", ic: "send" },
      { f: 0, n: ["Insurance card", "بطاقة التأمين", "बीमा कार्ड"], d: "12 Jan 2026", ic: "card" },
      { f: 0, n: ["Fitness certificate", "شهادة لياقة", "फ़िटनेस प्रमाणपत्र"], d: "05 Mar 2026", ic: "scroll" }
    ],
    vax: [
      { n: ["Influenza (flu) 2026", "الإنفلونزا 2026", "इन्फ़्लुएंज़ा (फ़्लू) 2026"], st: "due", d: ["Due now · yearly", "مستحق الآن · سنويًا", "अभी देय · हर साल"] },
      { n: ["Pneumococcal (PCV20)", "المكورات الرئوية (PCV20)", "न्यूमोकोकल (PCV20)"], st: "due", d: ["Recommended at 60 · ask your doctor", "يوصى به عند 60 · اسأل طبيبك", "60 पर सुझाया · डॉक्टर से पूछें"] },
      { n: ["Influenza (flu) 2025", "الإنفلونزا 2025", "इन्फ़्लुएंज़ा (फ़्लू) 2025"], st: "done", d: ["Given 14 Oct 2025", "أُعطي 14 أكتوبر 2025", "14 अक्टूबर 2025 को लगा"], f: 0 },
      { n: ["Tetanus, diphtheria (Td)", "الكزاز والدفتيريا (Td)", "टेटनस, डिप्थीरिया (Td)"], st: "done", d: ["Given 03 Jun 2021 · next 2031", "أُعطي 03 يونيو 2021 · التالي 2031", "03 जून 2021 को लगा · अगला 2031"], f: 0 }
    ],
    visits: [
      { date: "17 Oct 2026", f: 0, d: "d2", what: ["Follow-up visit", "زيارة متابعة", "फ़ॉलो-अप विज़िट"], live: true },
      { date: "12 Sep 2026", f: 0, d: "d2", what: ["Visit · fever", "زيارة · حمى", "विज़िट · बुखार"] },
      { date: "02 Aug 2026", f: 1, d: "d4", what: ["Heart check-up · day care", "فحص القلب · رعاية يومية", "हृदय जाँच · डे केयर"] },
      { date: "28 Jul 2026", f: 0, d: "d2", what: ["Visit · referred to hospital", "زيارة · تحويل للمستشفى", "विज़िट · अस्पताल रेफ़र"] }
    ],
    // People who can see Rakesh's records or whom Rakesh acts for.
    access: [
      { id: "latha", name: "Latha Menon", ar: "لاثا مينون", role: ["Family · wife", "العائلة · الزوجة", "परिवार · पत्नी"], where: "Bengaluru", until: ["No end date", "بلا تاريخ انتهاء", "कोई समाप्ति नहीं"], cap: [1, 1, 1, 0, 1, 0] },
      { id: "nikhil", name: "Nikhil Menon", ar: "نيخيل مينون", role: ["Caregiver · son in Dubai", "مقدّم رعاية · الابن في دبي", "देखभालकर्ता · दुबई में बेटा"], where: "+971", until: ["Ends 31 Mar 2027", "تنتهي 31 مارس 2027", "31 मार्च 2027 तक"], cap: [1, 1, 1, 1, 1, 0], abroad: true },
      { id: "sunitha", name: "Sunitha K.", ar: "سونيتا ك.", role: ["Home nurse · for Amma", "ممرضة منزلية · لأمي", "होम नर्स · माँ के लिए"], where: "Thrissur", until: ["Invite sent · 90 days", "أُرسلت الدعوة · 90 يومًا", "निमंत्रण भेजा · 90 दिन"], cap: [1, 1, 0, 0, 0, 0], pending: true }
    ],
    consentReq: { id: "C-2052", f: 1, from: 0, d: "d4", cats: [["Visits", "الزيارات", "विज़िट"], ["Prescriptions", "الوصفات", "पर्चे"], ["Lab results", "نتائج المختبر", "लैब रिपोर्ट"]],
      range: ["Last 12 months", "آخر 12 شهرًا", "पिछले 12 महीने"], purpose: ["Continuity of care for your heart check-up", "استمرارية الرعاية لفحص القلب", "आपकी हृदय जाँच के लिए निरंतर देखभाल"], mins: 18 },
    history: [
      { who: "Dr. Vikram Shah", f: 0, what: ["viewed lab results shared by Apollo Clinic", "اطّلع على نتائج المختبر من عيادة أبولو", "अपोलो क्लिनिक से साझा लैब रिपोर्ट देखी"], when: "16 Oct · 16:02", ic: "eye", c: "acc", tag: "C-2038" },
      { who: "Nikhil Menon", f: -1, what: ["opened your prescription in the app (caregiver)", "فتح وصفتك في التطبيق (مقدّم رعاية)", "ऐप में आपका पर्चा खोला (देखभालकर्ता)"], when: "15 Oct · 21:14", ic: "eye", c: "", tag: "" },
      { who: "Dr. Vikram Shah", f: 0, what: ["viewed discharge summary shared by Apollo Clinic", "اطّلع على ملخص الخروج من عيادة أبولو", "अपोलो क्लिनिक से साझा डिस्चार्ज सारांश देखा"], when: "14 Oct · 16:40", ic: "eye", c: "acc", tag: "C-2038" },
      { who: "You", f: -1, what: ["approved sharing with Suvidha Clinic (app, fingerprint)", "وافقت على المشاركة مع العيادة (التطبيق، البصمة)", "क्लिनिक के साथ साझा करने की मंज़ूरी दी (ऐप, फ़िंगरप्रिंट)"], when: "14 Oct · 14:02", ic: "shieldcheck", c: "ok", tag: "C-2038" },
      { who: "You", f: -1, what: ["declined a request from Kaya Skin Clinic", "رفضت طلبًا من عيادة كايا", "काया स्किन क्लिनिक का अनुरोध अस्वीकार किया"], when: "28 Sep · 10:15", ic: "close", c: "crit", tag: "C-2029" }
    ],
    // Booking options per need: doctor x facility, live wait, slots [time, predicted wait min].
    needs: [
      { id: "gm", ic: "steth", spec: "General Medicine", sub: ["Fever, BP, sugar, cough", "حمى، ضغط، سكر، سعال", "बुखार, बीपी, शुगर, खाँसी"] },
      { id: "derm", ic: "sparkle", spec: "Dermatology", sub: ["Skin, hair, nails", "الجلد والشعر والأظافر", "त्वचा, बाल, नाखून"] },
      { id: "paed", ic: "baby", spec: "Paediatrics", sub: ["Children up to 16", "الأطفال حتى 16 سنة", "16 साल तक के बच्चे"] },
      { id: "card", ic: "heartpulse", spec: "Cardiology", sub: ["Heart, chest pain, ECG", "القلب، ألم الصدر، تخطيط", "दिल, सीने में दर्द, ईसीजी"] },
      { id: "gyn", ic: "user", spec: "Obstetrics & Gynaecology", sub: ["Pregnancy, periods", "الحمل والدورة", "गर्भावस्था, पीरियड्स"] }
    ],
    options: {
      gm: [{ d: "d2", f: 0, wait: "22–30", km: "3.2", fee: 600, auto: true, slots: [["10:40", 24], ["11:20", 18], ["14:20", 4], ["16:00", 9]] },
        { d: "d2", f: 1, wait: "35–50", km: "9.8", fee: 900, auto: false, slots: [["12:30", 15], ["17:00", 6]] }],
      derm: [{ d: "d1", f: 0, wait: "9–14", km: "3.2", fee: 700, auto: true, slots: [["11:40", 12], ["12:10", 8], ["15:30", 3]] },
        { d: "d1", f: 2, wait: "5–10", km: "5.1", fee: 800, auto: true, slots: [["17:30", 4], ["18:10", 6]] }],
      paed: [{ d: "d3", f: 0, wait: "14–20", km: "3.2", fee: 650, auto: true, slots: [["11:00", 18], ["15:00", 5], ["16:20", 7]] }],
      card: [{ d: "d4", f: 1, wait: "40–55", km: "9.8", fee: 1200, auto: false, slots: [["Tue 11:00", 10], ["Wed 09:30", 6]] }],
      gyn: [{ d: "d5", f: 0, wait: "15–22", km: "3.2", fee: 800, auto: true, slots: [["12:40", 14], ["16:40", 5]] }]
    }
  };
  var PA = NB.D.patientapp;
  function mem(id) { return PA.members.find(function (m) { return m.id === id; }) || PA.members[0]; }
  function mname(m) { return NB.rtl() && m.ar ? m.ar : m.name; }
  function first(m) { return mname(m).split(" ")[0]; }
  function rel(m) { return L(m.rel[0], m.rel[1], m.rel[2]); }
  function L3(a) { return a ? L(a[0], a[1], a[2]) : ""; }

  /* ---------------- Patient app: Home ---------------- */
  function famRow(key, opts) {
    opts = opts || {};
    var cur = NB.v(key, "me");
    return '<div class="pt-fam" role="tablist" aria-label="' + t("Family members", "أفراد العائلة", "परिवार के सदस्य") + '">' + PA.members.map(function (m) {
      return '<button class="pt-mem ' + (cur === m.id ? "on" : "") + '" role="tab" aria-selected="' + (cur === m.id) + '" data-set="' + key + '" data-val="' + m.id + '">' + ui.av(m.name) +
        '<span class="n">' + esc(m.id === "me" ? L("Me", "أنا", "मैं") : first(m)) + "</span></button>";
    }).join("") + (opts.add !== false ? '<button class="pt-mem" data-go="patient.family"><span class="av add">' + ui.icon("plus", "sm") + '</span><span class="n">' + t("Add", "إضافة", "जोड़ें") + "</span></button>" : "") + "</div>";
  }

  function liveCard() {
    var V = PA.visit, d = NB.doc(V.d), st = NB.v("ptVisit", "booked");
    var meIdx = V.lane.indexOf(V.tok);
    var nowIdx = st === "booked" ? 1 : st === "arrived" ? 2 : 3;
    var ahead = meIdx - nowIdx; // tokens before me, including the one in the room
    var eta = st === "booked" ? [18, 26] : st === "arrived" ? [8, 14] : [0, 4];
    var range = eta[0] + "–" + eta[1];
    var leaveIn = eta[0] - V.travel;
    var start = st === "booked" ? nowIdx : nowIdx - 1;
    var lane = '<div class="lane pt-lane" aria-label="' + t("Queue", "الطابور", "क़तार") + '">' + V.lane.map(function (tk, i) {
      if (i < start || i > start + 4) return "";
      var cls = i < nowIdx ? "done" : i === nowIdx ? "now" : tk === V.tok ? "me" : "";
      var lab = i === nowIdx ? L("In room", "بالداخل", "अंदर") : tk === V.tok ? L("You", "أنت", "आप") : "";
      return '<div class="tk ' + cls + '"><span class="ltr">' + tk + "</span>" + (lab ? "<small>" + esc(lab) + "</small>" : "") + "</div>";
    }).join("") + "</div>";
    var h = '<div class="card hl pt-live">';
    h += '<div class="pt-tokrow"><div class="fl"><div class="pt-tokl">' + t("Your token · today", "رقم دورك · اليوم", "आपका टोकन · आज") + '</div><div class="pt-tok">' + V.tok + '</div></div><div style="text-align:end;min-width:0"><b style="display:block;font-size:14px">' + dn(V.d) + '</b><span class="small faint">' + esc(spec(d.spec)) + " · " + esc(fshort(V.f)) + "</span></div></div>";
    if (st === "next") {
      h += '<div class="pt-leave ok"><span class="ic">' + ui.icon("bell") + "</span><div><b>" + t("You're next — please go to " + d.room, "أنت التالي — تفضّل إلى " + "الغرفة " + d.room.replace(/\D/g, ""), "अब आपकी बारी — कृपया " + d.room.replace("Room", "कमरा") + " में जाएँ") + "</b><small>" + t("Show token " + V.tok + " at the door. No name is called out.", "أظهر الرقم " + V.tok + " عند الباب. لا يُنادى بالاسم.", "दरवाज़े पर टोकन " + V.tok + " दिखाएँ। नाम नहीं पुकारा जाएगा।") + "</small></div></div>";
    } else {
      h += '<div class="pt-eta"><div class="eta-ring" style="--p:' + (st === "booked" ? 38 : 72) + '%"><div><b>' + range + "</b><small>" + t("min to your turn", "دقيقة لدورك", "मिनट में बारी") + '</small></div></div><div class="pt-etat">' +
        '<span class="big">' + (ahead === 1 ? t("1 person ahead of you", "شخص واحد قبلك", "आपसे पहले 1 व्यक्ति") : esc(L(ahead + " people ahead of you", ahead + " أشخاص قبلك", "आपसे पहले " + ahead + " लोग"))) + "</span>" +
        '<span class="small muted">' + t("Expected", "المتوقع", "अनुमानित") + " " + nm(st === "booked" ? "09:59–10:07" : "10:06–10:12") + "</span>" +
        '<span class="small faint">' + t("Range, because every consult takes a different time", "نطاق تقريبي لأن مدة كل استشارة تختلف", "अनुमान दायरे में, क्योंकि हर परामर्श का समय अलग होता है") + "</span></div></div>";
      h += lane;
      if (st === "booked") {
        h += '<div class="pt-leave"><span class="ic">' + ui.icon("home") + "</span><div><b>" + esc(L("Leave home in " + leaveIn + " min", "اخرج من المنزل خلال " + leaveIn + " دقائق", leaveIn + " मिनट में घर से निकलें")) + "</b><small>" +
          esc(L("About " + V.travel + " min by car · we'll message you on WhatsApp too", "حوالي " + V.travel + " دقيقة بالسيارة · سنرسل لك على واتساب أيضًا", "कार से लगभग " + V.travel + " मिनट · व्हाट्सऐप पर भी बताएँगे")) + "</small></div></div>";
      } else {
        h += '<div class="pt-leave ok"><span class="ic">' + ui.icon("check") + "</span><div><b>" + t("Checked in at 09:58", "تم تسجيل حضورك 09:58", "09:58 पर चेक-इन हो गया") + "</b><small>" + t("Wait where you like — outside, in the car, at the café. We'll call you.", "انتظر حيث تشاء — في الخارج أو السيارة. سنخبرك.", "जहाँ चाहें इंतज़ार करें — बाहर, कार में, कैफ़े में। हम बुला लेंगे।") + "</small></div></div>";
      }
    }
    if (d.delay) h += ui.note("warn", "<b>" + esc(L(NB.dname(d) + " is running " + d.delay + " min late today.", NB.dname(d) + " متأخر " + d.delay + " دقيقة اليوم.", NB.dname(d) + " आज " + d.delay + " मिनट देरी से चल रहे हैं।")) + "</b> " + t("Your time above already includes this.", "الوقت أعلاه يشمل هذا التأخير.", "ऊपर का समय इसमें शामिल है।"), "clockh");
    h += '<div class="pt-2">' + (st === "booked" ? ui.btn(L("I'm here", "وصلت", "मैं पहुँच गया"), { cls: "pri pt-big", act: "ptCheckin", icon: "check" }) : st === "arrived" ? ui.btn(L("Show next update", "عرض التحديث التالي", "अगला अपडेट"), { cls: "pt-big", act: "ptVisitNext", icon: "refresh" }) : ui.btn(L("Start over (demo)", "إعادة (عرض)", "फिर से (डेमो)"), { cls: "pt-big", act: "ptVisitReset", icon: "refresh" })) +
      ui.btn(L("Directions", "الاتجاهات", "रास्ता"), { cls: "pt-big", ov: "ptdirections", icon: "map" }) + "</div>";
    return h + "</div>";
  }

  NB.screen("patient.home", function (c) {
    var who = NB.v("ptMember", "me"), m = mem(who), h = '<div class="pt-col">';
    h += '<div class="pt-hello"><div class="fl"><p>' + t("Saturday, 17 October", "السبت، 17 أكتوبر", "शनिवार, 17 अक्टूबर") + "</p><h1>" + esc(L("Good morning, Rakesh", "صباح الخير يا راكيش", "सुप्रभात, राकेश")) + "</h1></div>" + "</div>";
    h += famRow("ptMember");
    if (who === "me") h += liveCard();
    else {
      var up = PA.upcoming.filter(function (u) { return u.who === who; });
      h += '<div class="card hl"><div class="pt-tokrow"><div class="fl"><div class="pt-tokl">' + esc(L("Managing for " + first(m), "تدير حساب " + first(m), first(m) + " के लिए")) + '</div><b style="font-size:17px">' + esc(mname(m)) + '</b><div class="small faint">' + esc(rel(m)) + " · " + nm(m.age) + " " + t("yrs", "سنة", "वर्ष") + "</div></div>" + ui.av(m.name, "lg") + "</div>" +
        (m.minor ? ui.note("info", t("You are Meenu's guardian until she turns 18. Some teen health topics stay private to her and her doctor.", "أنت ولي أمر مينو حتى تبلغ 18. بعض المواضيع الصحية للمراهقين تبقى خاصة بها وبطبيبها.", "18 साल की होने तक आप मीनू के अभिभावक हैं। किशोर स्वास्थ्य के कुछ विषय उसके और डॉक्टर के बीच निजी रहते हैं।"), "shielduser") : "") +
        (who === "amma" ? ui.note("info", t("Amma gets reminders as Malayalam voice messages. Nikhil (Dubai) is also told when she checks in.", "تتلقى أمي التذكيرات برسائل صوتية بالمالايالامية، ويُبلَّغ نيخيل (دبي) عند وصولها.", "माँ को रिमाइंडर मलयालम वॉइस मैसेज में मिलते हैं। उनके चेक-इन पर निखिल (दुबई) को भी सूचना जाती है।"), "mic") : "") +
        (up.length ? "" : '<p class="small muted" style="margin:10px 0 0">' + t("No visit today.", "لا توجد زيارة اليوم.", "आज कोई विज़िट नहीं।") + "</p>") +
        '<div class="pt-2" style="margin-top:12px">' + ui.btn(L("Book for " + first(m), "احجز لـ " + first(m), first(m) + " के लिए बुक करें"), { cls: "pri pt-big", act: "ptBookFor", arg: who, icon: "calendar" }) + ui.btn(L("Records", "السجلات", "रिकॉर्ड"), { cls: "pt-big", go: "patient.records", icon: "file" }) + "</div></div>";
    }
    // Pending consent request
    var rq = NB.v("ptReq", "pending");
    if (rq === "pending") {
      var R = PA.consentReq;
      h += '<div class="card pt-req">' + it(ico("shieldcheck", "warn"), esc(L(fshort(R.f) + " asks to see your records", fshort(R.f) + " يطلب الاطلاع على سجلاتك", fshort(R.f) + " आपके रिकॉर्ड देखना चाहता है")),
        esc(L("Nothing is shared until you approve · " + R.mins + " min ago", "لن يُشارك شيء دون موافقتك · قبل " + R.mins + " دقيقة", "आपकी मंज़ूरी के बिना कुछ साझा नहीं होगा · " + R.mins + " मिनट पहले")),
        ui.btn(L("Review", "مراجعة", "देखें"), { cls: "sm pri", ov: "consentask", arg: R.id }), {}) + "</div>";
    }
    // Quick actions
    h += '<div class="pt-qa">' + [
      ["calendar", L("Book a visit", "احجز زيارة", "विज़िट बुक करें"), 'data-go="patient.book"'],
      ["qr", L("My e-Rx", "وصفاتي", "मेरे पर्चे"), 'data-ov="rxqr" data-arg="RX-88412"'],
      ["flask", L("Reports", "التقارير", "रिपोर्ट"), 'data-act="ptRecTab" data-arg="labs"'],
      ["wallet", L("Pay bill", "ادفع الفاتورة", "बिल भरें"), 'data-ov="ptpay" data-arg="INV-2026-04412"', 1]
    ].map(function (q) { return "<button " + q[2] + '><span class="ic">' + ui.icon(q[0]) + (q[3] ? '<span class="pt-dotc">' + q[3] + "</span>" : "") + "</span>" + esc(q[1]) + "</button>"; }).join("") + "</div>";
    // Upcoming
    var ups = PA.upcoming.filter(function (u) { return who === "me" || u.who === who; });
    if (ups.length) {
      h += sec(t("Coming up", "القادم", "आगे की विज़िट"), ui.btn(L("Book", "احجز", "बुक"), { cls: "sm", go: "patient.book", icon: "plus" }));
      h += '<div class="card">' + ups.map(function (u) {
        var m2 = mem(u.who);
        return it(ui.av(m2.name), esc(L3(u.when)), esc((u.who === "me" ? L("You", "أنت", "आप") : first(m2)) + " · " + NB.dname(NB.doc(u.d)) + " · " + fshort(u.f)) + "<br>" + esc(L3(u.note)),
          u.st === "warn" ? ui.pill("warn", L("Pending", "معلّق", "लंबित")) : ui.pill("ok", L("Confirmed", "مؤكد", "पक्का")));
      }).join("") + "</div>";
    }
    // Active prescriptions with expiry countdown
    if (who === "me") {
      h += sec(t("Active prescriptions", "الوصفات السارية", "चालू पर्चे"), ui.btn(L("All", "الكل", "सभी"), { cls: "sm ghost", act: "ptRecTab", arg: "rx" }));
      h += '<div class="card">' + PA.rx.filter(function (r) { return r.st === "active"; }).map(function (r) {
        var names = r.items.map(function (x) { return NB.drug(x[0]).gen; }).join(" + ");
        var warn = r.left <= 14;
        return '<div class="pt-it click" data-ov="rxqr" data-arg="' + r.id + '" role="button" tabindex="0">' + ico("pill", warn ? "warn" : "acc") + '<div class="t"><b>' + esc(names) + "</b><small>" + dn(r.d) + " · " + esc(fshort(r.f)) + '</small><div class="pt-count" style="margin-top:6px">' + ui.bar(100 * r.left / r.total, warn ? "warn" : "") +
          "<span>" + esc(L(r.left + " days left", "متبقٍ " + r.left + " يومًا", r.left + " दिन बाकी")) + "</span></div></div>" + chev() + "</div>";
      }).join("") + "</div>";
    }
    h += ui.note("info", t("Your records stay with each hospital. You decide who sees them, and for how long.", "تبقى سجلاتك لدى كل مستشفى. أنت تقرر من يراها ولأي مدة.", "आपके रिकॉर्ड हर अस्पताल के पास ही रहते हैं। कौन, कितने समय तक देखे — यह आप तय करते हैं।"), "lock");
    return h + "</div>";
  });

  NB.act("ptCheckin", function () { NB.state.ui.ptVisit = "arrived"; NB.toast(L("Checked in · the front desk can see you're here", "تم تسجيل الحضور · الاستقبال يعلم بوصولك", "चेक-इन हो गया · रिसेप्शन को पता है कि आप आ गए")); });
  NB.act("ptVisitNext", function () { NB.setUI({ ptVisit: "next" }); });
  NB.act("ptVisitReset", function () { NB.setUI({ ptVisit: "booked" }); });
  NB.act("ptRecTab", function (a) { NB.state.ui.ptRec = a; NB.go("patient", "records"); });
  NB.act("ptBookFor", function (a) { Object.assign(NB.state.ui, { bkFor: a, bkNeed: null, bkOpt: null, bkSlot: null, bkDone: false }); NB.go("patient", "book"); });

  NB.overlay("ptdirections", function () {
    var V = PA.visit;
    return { kind: "modal", title: t("Directions", "الاتجاهات", "रास्ता"), body: mapSvg(200) +
      it(ico("building", "acc"), esc(fname(V.f)), esc(F(V.f).area), "") +
      '<div class="pt-2">' + ui.kpi(nm(V.travel + " " + L("min", "د", "मि")), L("By car now", "بالسيارة الآن", "अभी कार से")) + ui.kpi(nm("3.2 km"), L("Distance", "المسافة", "दूरी")) + "</div>" +
      ui.note("info", t("Parking at the back gate. Lift to the 1st floor, then follow the signs to the doctor rooms.", "موقف السيارات عند البوابة الخلفية. المصعد إلى الطابق الأول ثم اتبع اللافتات.", "पार्किंग पीछे वाले गेट पर। लिफ़्ट से पहली मंज़िल, फिर संकेतों के अनुसार।")),
      foot: ui.btn(L("Close", "إغلاق", "बंद करें"), { act: "close", cls: "ghost" }) + ui.btn(L("Open in Maps", "افتح في الخرائط", "मैप्स में खोलें"), { cls: "pri", act: "toast", arg: L("Opening maps…", "جارٍ فتح الخرائط…", "मैप्स खुल रहा है…"), icon: "map" }) };
  });

  NB.overlay("ptnotif", function () {
    return { title: t("Notifications", "الإشعارات", "सूचनाएँ"), body: '<div class="card">' + [
      ["clockh", "warn", L("Your doctor is running 15 min late", "طبيبك متأخر 15 دقيقة", "आपके डॉक्टर 15 मिनट देरी से"), L("Your new time is on the home screen · 5 min ago", "الوقت الجديد في الصفحة الرئيسية · قبل 5 دقائق", "नया समय होम स्क्रीन पर · 5 मिनट पहले")],
      ["shieldcheck", "warn", L("A hospital asks to see your records", "مستشفى يطلب الاطلاع على سجلاتك", "एक अस्पताल आपके रिकॉर्ड देखना चाहता है"), L("Review in Privacy · 18 min ago", "راجع في الخصوصية · قبل 18 دقيقة", "गोपनीयता में देखें · 18 मिनट पहले")],
      ["flask", "ok", L("2 lab reports are ready", "تقريران جاهزان", "2 लैब रिपोर्ट तैयार"), L("Released by your doctor · 2 days ago", "أصدرها طبيبك · قبل يومين", "आपके डॉक्टर ने जारी कीं · 2 दिन पहले")],
      ["users", "info", L("Nikhil opened your prescription", "فتح نيخيل وصفتك", "निखिल ने आपका पर्चा खोला"), L("Caregiver access · 2 days ago", "وصول مقدّم الرعاية · قبل يومين", "देखभालकर्ता एक्सेस · 2 दिन पहले")]
    ].map(function (n) { return it(ico(n[0], n[1]), esc(n[2]), esc(n[3]), ""); }).join("") + "</div>" +
      ui.note("info", t("Lock-screen previews never show health details.", "معاينات شاشة القفل لا تعرض أي تفاصيل صحية.", "लॉक-स्क्रीन पर स्वास्थ्य जानकारी कभी नहीं दिखती।"), "lock") };
  });

  NB.overlay("ptpay", function (id) {
    var b = PA.bills.find(function (x) { return x.id === id; }) || PA.bills[0];
    var paid = NB.v("ptPaid_" + b.id, false) || b.st !== "due";
    return { kind: "modal", title: t("Pay bill", "دفع الفاتورة", "बिल भुगतान"), body:
      '<div class="pt-res"><span class="pt-ic acc" style="width:56px;height:56px;border-radius:999px">' + ui.icon("receipt", "lg") + '</span><div class="pt-tok" style="color:var(--tx)">' + NB.money(b.amt) + '</div><span class="small muted">' + esc(L3(b.what)) + " · " + esc(fname(b.f)) + " · " + nm(b.date) + "</span></div>" +
      '<div class="stack s8">' + ui.radio("ptPayWay", "now", L("Pay now with " + payWay(), "ادفع الآن عبر " + payWay(), payWay() + " से अभी भुगतान करें"), L("Receipt arrives in the app and on WhatsApp", "يصلك الإيصال في التطبيق وعلى واتساب", "रसीद ऐप और व्हाट्सऐप पर मिलेगी"), "now") +
      ui.radio("ptPayWay", "nik", L("Ask Nikhil to pay", "اطلب من نيخيل الدفع", "निखिल से भुगतान करवाएँ"), L("He gets a payment link in Dubai · card", "يصله رابط دفع في دبي · بطاقة", "उन्हें दुबई में पेमेंट लिंक मिलेगा · कार्ड"), "now") +
      ui.radio("ptPayWay", "desk", L("Pay at the counter", "ادفع عند المكتب", "काउंटर पर भुगतान"), L("Cash or card at the clinic", "نقدًا أو بالبطاقة في العيادة", "क्लिनिक पर नकद या कार्ड"), "now") + "</div>" +
      (paid ? ui.note("ok", t("Paid. Thank you!", "تم الدفع. شكرًا!", "भुगतान हो गया। धन्यवाद!")) : ""),
      foot: ui.btn(L("Later", "لاحقًا", "बाद में"), { act: "close", cls: "ghost" }) + ui.btn(L("Pay", "ادفع", "भुगतान करें") + " " + (NB.mkt().id === "india" ? "₹" + b.amt : ""), { cls: "pri", act: "ptPay", arg: b.id, icon: "wallet" }) };
  });
  NB.act("ptPay", function (id) {
    var w = NB.v("ptPayWay", "now"); NB.state.ui["ptPaid_" + id] = true; NB.state.overlay = null;
    NB.toast(w === "nik" ? L("Payment link sent to Nikhil", "أُرسل رابط الدفع إلى نيخيل", "निखिल को पेमेंट लिंक भेजा") : w === "desk" ? L("Okay — pay at the counter", "حسنًا — ادفع عند المكتب", "ठीक है — काउंटर पर भुगतान करें") : L("Paid · receipt sent", "تم الدفع · أُرسل الإيصال", "भुगतान हो गया · रसीद भेजी"));
  });

  /* ---------------- Patient app: Book (<= 4 taps) ---------------- */
  NB.screen("patient.book", function (c) {
    var who = NB.v("bkFor", "me"), need = NB.v("bkNeed", null), opt = NB.v("bkOpt", null), slot = NB.v("bkSlot", null), done = NB.v("bkDone", false);
    var taps = (who !== "me" ? 1 : 0) + (need ? 1 : 0) + (slot ? 1 : 0) + (done ? 1 : 0);
    var m = mem(who), h = '<div class="pt-col">';
    h += '<div class="pt-hello"><div class="fl"><h1>' + t("Book a visit", "احجز زيارة", "विज़िट बुक करें") + "</h1><p>" + t("Real waiting times, live from the clinic", "أوقات انتظار حقيقية مباشرة من العيادة", "क्लिनिक से असली, लाइव इंतज़ार समय") + '</p></div><span class="pt-taps">' + ui.icon("bolt", "xs") + esc(L(taps + (taps === 1 ? " tap" : " taps"), taps + " نقرات", taps + " टैप")) + "</span></div>";
    if (done) {
      var o = PA.options[need][opt], d = NB.doc(o.d);
      h += '<div class="card pt-res">' + (o.auto ? '<span class="ic ok">' + ui.icon("checkcircle", "xl") + "</span><h2>" + t("You're booked", "تم الحجز", "बुकिंग पक्की") + "</h2>" +
        '<div class="pt-tokl">' + t("Token", "رقم الدور", "टोकन") + '</div><div class="pt-tok">T-26</div>' +
        '<p class="small muted" style="margin:0">' + esc(L("The clinic accepts bookings automatically. We'll message " + (who === "me" ? "you" : first(m)) + " on WhatsApp when it's time to leave home.", "العيادة تقبل الحجوزات تلقائيًا. سنرسل على واتساب عندما يحين وقت الخروج من المنزل.", "क्लिनिक बुकिंग अपने-आप स्वीकार करता है। घर से निकलने का समय होने पर व्हाट्सऐप पर बताएँगे।")) + "</p>"
        : '<span class="ic warn">' + ui.icon("hourglass", "xl") + "</span><h2>" + t("Request sent", "تم إرسال الطلب", "अनुरोध भेजा गया") + "</h2>" + ui.pill("warn", L("Pending clinic approval", "بانتظار موافقة العيادة", "क्लिनिक की मंज़ूरी बाकी")) +
        '<p class="small muted" style="margin:0">' + esc(L(fshort(o.f) + " checks each booking. Usually within 15 minutes — you'll get a WhatsApp message either way.", fshort(o.f) + " يراجع كل حجز، عادةً خلال 15 دقيقة. ستصلك رسالة واتساب في الحالتين.", fshort(o.f) + " हर बुकिंग जाँचता है। आमतौर पर 15 मिनट में — दोनों ही हालात में व्हाट्सऐप संदेश मिलेगा।")) + "</p>") + "</div>";
      h += '<div class="card"><dl class="kv">' +
        "<dt>" + t("For", "لـ", "किसके लिए") + "</dt><dd>" + esc(mname(m)) + "</dd>" +
        "<dt>" + t("Doctor", "الطبيب", "डॉक्टर") + "</dt><dd>" + dn(o.d) + "</dd>" +
        "<dt>" + t("Where", "المكان", "कहाँ") + "</dt><dd>" + esc(fname(o.f)) + "</dd>" +
        "<dt>" + t("When", "الموعد", "कब") + "</dt><dd>" + nm(slot) + "</dd>" +
        "<dt>" + t("Expected wait there", "الانتظار المتوقع هناك", "वहाँ अनुमानित इंतज़ार") + "</dt><dd>" + nm(o.slots.find(function (s) { return s[0] === slot; })[1] + " " + L("min", "د", "मि")) + "</dd>" +
        "<dt>" + t("Fee", "الرسوم", "फ़ीस") + "</dt><dd>" + NB.money(o.fee) + " · " + esc(NB.v("bkPay", "clinic") === "now" ? L("paid", "مدفوع", "भुगतान हो गया") : L("pay at clinic", "الدفع في العيادة", "क्लिनिक पर भुगतान")) + "</dd></dl></div>";
      h += '<div class="pt-2">' + ui.btn(L("Add to calendar", "أضف للتقويم", "कैलेंडर में जोड़ें"), { cls: "pt-big", act: "toast", arg: L("Added to calendar", "أُضيف إلى التقويم", "कैलेंडर में जोड़ा"), icon: "calendar" }) + ui.btn(L("Done", "تم", "हो गया"), { cls: "pri pt-big", act: "ptBookReset", arg: "home", icon: "home" }) + "</div>";
      return h + "</div>";
    }
    // Step 1: who
    h += '<div class="pt-step"><div class="pt-sh"><span class="no done">' + ui.icon("check", "xs") + "</span><h3>" + t("Who is this for?", "لمن هذه الزيارة؟", "यह विज़िट किसके लिए है?") + "</h3></div>" + famRow("bkFor", { add: false }) +
      (m.minor ? '<span class="small faint">' + t("Booking as Meenu's guardian.", "تحجز بصفتك ولي أمر مينو.", "मीनू के अभिभावक के रूप में बुकिंग।") + "</span>" : "") + "</div>";
    // Step 2: need
    if (!need) {
      h += '<div class="pt-step"><div class="pt-sh"><span class="no">2</span><h3>' + t("What do you need?", "ماذا تحتاج؟", "आपको क्या चाहिए?") + "</h3></div>";
      h += '<div class="pt-done click" data-act="ptBookNeed" data-arg="gm:0" role="button" tabindex="0" style="cursor:pointer">' + ui.av(NB.doc("d2").name) + '<div class="t"><small>' + t("Book again with your doctor", "احجز مجددًا مع طبيبك", "अपने डॉक्टर के साथ फिर बुक करें") + "</small><b>" + dn("d2") + " · " + esc(fshort(0)) + "</b></div>" + chev() + "</div>";
      h += '<div class="pt-needs">' + PA.needs.map(function (n) {
        return '<button class="pt-chip" data-act="ptBookNeed" data-arg="' + n.id + '">' + ui.icon(n.ic, "sm") + "<span>" + esc(spec(n.spec)) + "<small>" + esc(L3(n.sub)) + "</small></span></button>";
      }).join("") + "</div></div>";
      return h + "</div>";
    }
    var nd = PA.needs.find(function (n) { return n.id === need; });
    h += '<div class="pt-done">' + ico(nd.ic, "acc") + '<div class="t"><small>' + t("Need", "الحاجة", "ज़रूरत") + "</small><b>" + esc(spec(nd.spec)) + "</b></div>" + ui.btn(L("Change", "تغيير", "बदलें"), { cls: "sm ghost", act: "ptBookChange", arg: "need" }) + "</div>";
    // Step 3: clinic + slot
    if (!slot) {
      h += '<div class="pt-step"><div class="pt-sh"><span class="no">3</span><h3>' + t("Pick a clinic and time", "اختر العيادة والوقت", "क्लिनिक और समय चुनें") + "</h3></div>";
      h += PA.options[need].map(function (o, i) {
        var d = NB.doc(o.d), minW = Math.min.apply(null, o.slots.map(function (s) { return s[1]; }));
        return '<div class="card pt-opt"><div class="pt-opth">' + ui.av(d.name, "lg") + '<div class="t"><b>' + dn(o.d) + "</b><small>" + esc(fname(o.f)) + " · " + nm(o.km + " km") + " · " + NB.money(o.fee) + "</small>" +
          '<div class="chips" style="margin-top:6px">' + (o.auto ? ui.pill("ok", L("Instant confirm", "تأكيد فوري", "तुरंत पक्का")) : ui.pill("warn", L("Clinic approves", "تتطلب موافقة", "क्लिनिक मंज़ूरी")))+ (d.delay ? ui.pill("warn", L("+" + d.delay + " min late", "متأخر " + d.delay + " د", d.delay + " मि देरी"), "clockh") : "") + "</div></div>" +
          '<div class="pt-wait"><small>' + t("Wait now", "الانتظار الآن", "अभी इंतज़ार") + "</small><b>" + nm(o.wait) + '</b><small>' + t("min", "دقيقة", "मिनट") + "</small></div></div>" +
          '<div class="pt-slots">' + o.slots.map(function (s, k) {
            var hint = k === 0 && i === 0 ? L("Earliest", "الأقرب", "सबसे जल्दी") : s[1] === minW ? L("Least wait", "أقل انتظار", "सबसे कम इंतज़ार") : "";
            return '<button class="pt-slot' + (hint ? " hint" : "") + '" data-act="ptBookSlot" data-arg="' + i + "|" + esc(s[0]) + '">' + (hint ? '<span class="h">' + esc(hint) + "</span>" : "") + "<b class=\"ltr\">" + esc(s[0]) + "</b><small>~" + s[1] + " " + t("min wait", "د انتظار", "मि इंतज़ार") + "</small></button>";
          }).join("") + '<button class="pt-slot" data-ov="slotpick" data-arg="' + need + "|" + i + '"><b>' + ui.icon("calendar", "sm") + "</b><small>" + t("Other days", "أيام أخرى", "दूसरे दिन") + "</small></button></div></div>";
      }).join("") + "</div>";
      return h + "</div>";
    }
    var o2 = PA.options[need][opt];
    h += '<div class="pt-done">' + ui.av(NB.doc(o2.d).name) + '<div class="t"><small>' + esc(fname(o2.f)) + "</small><b>" + dn(o2.d) + " · " + nm(slot) + "</b></div>" + ui.btn(L("Change", "تغيير", "बदلें"), { cls: "sm ghost", act: "ptBookChange", arg: "slot" }) + "</div>";
    // Step 4: reason + pay + confirm
    var rs = NB.v("bkReason", "");
    h += '<div class="pt-step"><div class="pt-sh"><span class="no">4</span><h3>' + t("Confirm", "التأكيد", "पुष्टि करें") + "</h3></div>";
    h += '<div class="card stack"><span class="small muted">' + t("Reason (optional) — helps the doctor prepare", "السبب (اختياري) — يساعد الطبيب على الاستعداد", "कारण (वैकल्पिक) — डॉक्टर को तैयारी में मदद") + '</span><div class="pt-chips">' + [
      ["fu", L("Follow-up", "متابعة", "फ़ॉलो-अप")], ["new", L("New problem", "مشكلة جديدة", "नई समस्या")], ["rep", L("Show reports", "عرض التقارير", "रिपोर्ट दिखाना")], ["fever", L("Fever / cold", "حمى / زكام", "बुखार / ज़ुकाम")]
    ].map(function (r) { return '<button class="pt-chip ' + (rs === r[0] ? "on" : "") + '" data-set="bkReason" data-val="' + r[0] + '">' + esc(r[1]) + "</button>"; }).join("") +
      '<button class="pt-chip" data-act="toast" data-arg="' + esc(L("Recording… (voice note goes to your doctor only)", "جارٍ التسجيل… (الملاحظة الصوتية لطبيبك فقط)", "रिकॉर्डिंग… (वॉइस नोट सिर्फ़ डॉक्टर को)")) + '">' + ui.icon("mic", "sm") + t("Voice note", "ملاحظة صوتية", "वॉइस नोट") + "</button></div>" +
      '<span class="small muted" style="margin-top:4px">' + t("Payment (optional)", "الدفع (اختياري)", "भुगतान (वैकल्पिक)") + "</span>" +
      ui.seg("bkPay", [["clinic", L("Pay at clinic", "الدفع في العيادة", "क्लिनिक पर")], ["now", L("Pay now", "ادفع الآن", "अभी भरें") + " · " + payWay()]], "clinic") + "</div>";
    h += ui.btn((o2.auto ? L("Confirm booking", "تأكيد الحجز", "बुकिंग पक्की करें") : L("Send request", "إرسال الطلب", "अनुरोध भेजें")) + " · " + (NB.mkt().id === "india" ? "₹" + o2.fee : ""), { cls: "pri lg blk", act: "ptBookConfirm", icon: "check" });
    h += '<span class="small faint" style="text-align:center">' + (o2.auto ? t("This clinic confirms instantly.", "هذه العيادة تؤكد فورًا.", "यह क्लिनिक तुरंत पक्का करता है।") : t("This hospital reviews each booking before confirming.", "يراجع هذا المستشفى كل حجز قبل التأكيد.", "यह अस्पताल पक्का करने से पहले हर बुकिंग जाँचता है।")) + "</span></div>";
    return h + "</div>";
  });
  NB.act("ptBookNeed", function (a) {
    var p = a.split(":"); var u = { bkNeed: p[0], bkSlot: null, bkOpt: null };
    NB.setUI(u);
  });
  NB.act("ptBookSlot", function (a) { var p = a.split("|"); NB.setUI({ bkOpt: +p[0], bkSlot: p[1] }); });
  NB.act("ptBookChange", function (a) { NB.setUI(a === "need" ? { bkNeed: null, bkOpt: null, bkSlot: null } : { bkOpt: null, bkSlot: null }); });
  NB.act("ptBookConfirm", function () { NB.setUI({ bkDone: true }); });
  NB.act("ptBookReset", function (a) {
    Object.assign(NB.state.ui, { bkFor: "me", bkNeed: null, bkOpt: null, bkSlot: null, bkDone: false, bkReason: "" });
    if (a === "home") NB.go("patient", "home"); else NB.render();
  });

  NB.overlay("slotpick", function (arg) {
    var p = String(arg || "gm|0").split("|"), o = PA.options[p[0]][+p[1]], day = NB.v("spDay", "mon");
    var days = [["tue", L("Tomorrow", "غدًا", "कल")], ["mon", L("Mon 19", "الاثنين 19", "सोम 19")], ["tue2", L("Tue 20", "الثلاثاء 20", "मंगल 20")], ["wed", L("Wed 21", "الأربعاء 21", "बुध 21")]];
    var sl = { tue: [["09:00", 3], ["09:20", 5], ["10:40", 14], ["12:00", 20], ["16:00", 6]], mon: [["08:40", 2], ["09:00", 4], ["11:20", 16], ["17:30", 7]], tue2: [["09:40", 6], ["12:20", 18], ["18:00", 5]], wed: [["10:00", 8], ["15:20", 4]] }[day] || [];
    var minW = Math.min.apply(null, sl.map(function (s) { return s[1]; }));
    return { title: esc(NB.dname(NB.doc(o.d))) + " · " + esc(fshort(o.f)), body:
      ui.seg("spDay", days, "mon") +
      '<div class="pt-slots">' + sl.map(function (s, k) {
        var hint = k === 0 ? L("Earliest", "الأقرب", "सबसे जल्दी") : s[1] === minW ? L("Least wait", "أقل انتظار", "सबसे कम इंतज़ार") : "";
        return '<button class="pt-slot' + (hint ? " hint" : "") + '" data-act="ptSlotPick" data-arg="' + p[1] + "|" + esc(days.find(function (x) { return x[0] === day; })[1] + " " + s[0]) + '">' + (hint ? '<span class="h">' + esc(hint) + "</span>" : "") + '<b class="ltr">' + s[0] + "</b><small>~" + s[1] + " " + t("min wait", "د انتظار", "मि इंतज़ार") + "</small></button>";
      }).join("") + "</div>" +
      ui.note("info", t("Predicted wait comes from how long this doctor's visits really take at that hour. Early morning slots usually wait least.", "الانتظار المتوقع مبني على المدة الفعلية لزيارات هذا الطبيب في تلك الساعة. الصباح الباكر عادةً أقل انتظارًا.", "अनुमानित इंतज़ार इस डॉक्टर की उस समय की असली विज़िट अवधि पर आधारित है। सुबह जल्दी आमतौर पर सबसे कम इंतज़ार।"), "hourglass") };
  });
  NB.act("ptSlotPick", function (a) {
    var p = a.split("|");
    // Add the picked slot to the option so the summary can show its predicted wait.
    var o = PA.options[NB.v("bkNeed", "gm")][+p[0]]; if (!o.slots.some(function (s) { return s[0] === p[1]; })) o.slots.push([p[1], 4]);
    NB.state.overlay = null; NB.setUI({ bkOpt: +p[0], bkSlot: p[1] });
  });

  /* ---------------- Patient app: Records ---------------- */
  function byFac(items, render) {
    var groups = {};
    items.forEach(function (x) { (groups[x.f] = groups[x.f] || []).push(x); });
    return Object.keys(groups).map(function (f) {
      return '<div class="stack s8"><div class="pt-fach">' + ui.icon("building", "sm") + "<span>" + t("Held by", "محفوظة لدى", "रखे गए हैं:") + " <b>" + esc(fname(+f)) + "</b></span></div>" +
        '<div class="card">' + groups[f].map(render).join("") + "</div></div>";
    }).join("");
  }
  NB.screen("patient.records", function (c) {
    var tab = NB.v("ptRec", "visits"), rail = idRail(), linked = NB.v("ptIdLinked", NB.mkt().id === "india");
    var h = '<div class="pt-col"><div class="pt-hello"><div class="fl"><h1>' + t("My records", "سجلاتي", "मेरे रिकॉर्ड") + "</h1><p>" + t("Rakesh Menon · from every clinic you visited", "راكيش مينون · من كل عيادة زرتها", "राकेश मेनन · हर क्लिनिक से जहाँ आप गए") + "</p></div></div>";
    h += '<div class="card pt-idc">' + ico("fingerprint", linked ? "ok" : "warn") + '<div class="fl"><b style="display:block;font-size:14px">' + esc(L(rail[0], rail[1], rail[2])) + "</b>" +
      '<span class="small faint">' + (linked ? (NB.mkt().id === "india" ? nm("91-4410-2287-0033") + " · " + t("linked", "مرتبط", "लिंक है") : t("Verified · linked", "موثّق · مرتبط", "सत्यापित · लिंक")) : esc(L("Link with " + rail[3] + " to bring in records from other hospitals", "اربط عبر " + rail[3] + " لجلب سجلاتك من مستشفيات أخرى", rail[3] + " से लिंक करें ताकि दूसरे अस्पतालों के रिकॉर्ड भी आएँ"))) + "</span></div>" +
      (linked ? ui.pill("ok", L("Linked", "مرتبط", "लिंक")) : ui.btn(L("Link", "ربط", "लिंक करें"), { cls: "sm pri", ov: "ptlinkid" })) + "</div>";
    h += ui.tabs("ptRec", [["visits", L("Visits", "الزيارات", "विज़िट"), "steth"], ["rx", L("Prescriptions", "الوصفات", "पर्चे"), "pill"], ["labs", L("Lab reports", "التحاليل", "लैब रिपोर्ट"), "flask"],
      ["bills", L("Bills", "الفواتير", "बिल"), "receipt"], ["docs", L("Documents", "المستندات", "दस्तावेज़"), "file"], ["vax", L("Vaccines", "اللقاحات", "टीके"), "syringe"]], "visits");
    if (tab === "visits") {
      h += byFac(PA.visits, function (v) {
        return it(ico("steth", v.live ? "acc" : ""), esc(L3(v.what)), nm(v.date) + " · " + dn(v.d), v.live ? ui.pill("acc", L("Today", "اليوم", "आज")) : chev(), { act: "toast", arg: L("Visit summary opens here", "يُفتح ملخص الزيارة هنا", "विज़िट सारांश यहाँ खुलता है") });
      });
    } else if (tab === "rx") {
      h += ui.note("info", t("Show the QR at any pharmacy, or send it to the one you choose. The clinic's own pharmacy is chosen by default.", "أظهر رمز QR في أي صيدلية أو أرسله للصيدلية التي تختارها. صيدلية العيادة هي الافتراضية.", "QR किसी भी फ़ार्मेसी पर दिखाएँ, या अपनी चुनी फ़ार्मेसी को भेजें। क्लिनिक की फ़ार्मेसी पहले से चुनी होती है।"), "qr");
      h += byFac(PA.rx, function (r) {
        var names = r.items.map(function (x) { return NB.drug(x[0]).gen; }).join(" + ");
        var right = r.st === "expired" ? ui.pill("perm", L("Expired", "منتهية", "समाप्त")) : r.left <= 14 ? ui.pill("warn", L(r.left + " days left", "متبقٍ " + r.left + " يومًا", r.left + " दिन बाकी"), "clockh") : ui.pill("ok", L("Valid", "سارية", "मान्य"));
        return it(ico("pill", r.st === "expired" ? "" : "acc"), esc(names), nm(r.id) + " · " + dn(r.d) + "<br>" + esc(L("Valid until ", "سارية حتى ", "मान्य: ")) + nm(r.until), right, { ov: "rxqr", arg: r.id });
      });
    } else if (tab === "labs") {
      h += byFac(PA.labs, function (l) {
        var flag = l.rows.some(function (r) { return r[1] > r[4] || r[1] < r[3]; });
        return it(ico("flask", l.st === "processing" ? "" : flag ? "warn" : "ok"), esc(L3(l.name)), nm(l.date) + " · " + dn(l.by),
          l.st === "processing" ? ui.pill("sync", L("Processing", "قيد المعالجة", "प्रोसेस में")) : flag ? ui.pill("warn", L("See values", "راجع القيم", "मान देखें")) : ui.pill("ok", L("Released", "صدر", "जारी")),
          l.st === "processing" ? { act: "toast", arg: L("Your doctor releases this report after checking it", "يصدر طبيبك التقرير بعد مراجعته", "डॉक्टर जाँच के बाद रिपोर्ट जारी करेंगे") } : { ov: "ptlab", arg: l.id });
      });
    } else if (tab === "bills") {
      h += byFac(PA.bills, function (b) {
        var paid = NB.v("ptPaid_" + b.id, false);
        var st = b.st === "due" && !paid ? ui.btn(L("Pay", "ادفع", "भरें"), { cls: "sm pri", ov: "ptpay", arg: b.id }) : b.st === "ins" ? ui.pill("info", L("Insurance", "تأمين", "बीमा"), "shieldcheck") : ui.pill("ok", L("Paid", "مدفوع", "भुगतान"));
        return it(ico("receipt", b.st === "due" && !paid ? "warn" : ""), esc(L3(b.what)) + " · " + NB.money(b.amt), nm(b.id) + " · " + nm(b.date) + (b.how ? " · " + esc(b.how) : ""), st);
      });
      h += ui.note("info", esc(L(NB.mkt().tax + " receipts download as PDF. Family members you allow can pay for you.", "إيصالات " + NB.mkt().tax + " تُنزّل كملف PDF. يمكن لأفراد العائلة المسموح لهم الدفع عنك.", NB.mkt().tax + " रसीदें PDF में डाउनलोड होती हैं। जिन्हें आप अनुमति दें, वे आपकी ओर से भुगतान कर सकते हैं।")), "receipt");
    } else if (tab === "docs") {
      h += byFac(PA.docs, function (d) { return it(ico(d.ic), esc(L3(d.n)), nm(d.d), ui.icon("download", "sm faint"), { act: "toast", arg: L("Opening secure document…", "جارٍ فتح المستند الآمن…", "सुरक्षित दस्तावेज़ खुल रहा है…") }); });
      h += ui.btn(L("Add a document", "أضف مستندًا", "दस्तावेज़ जोड़ें"), { cls: "blk", act: "toast", arg: L("Camera opens · photos stay in your private folder", "تفتح الكاميرا · الصور تبقى في مجلدك الخاص", "कैमरा खुलेगा · फ़ोटो आपके निजी फ़ोल्डर में रहेंगी"), icon: "camera" });
    } else {
      h += '<div class="card">' + PA.vax.map(function (v) {
        return it(ico("syringe", v.st === "due" ? "warn" : "ok"), esc(L3(v.n)), esc(L3(v.d)) + (v.f != null ? " · " + esc(fshort(v.f)) : ""),
          v.st === "due" ? ui.btn(L("Book", "احجز", "बुक"), { cls: "sm", go: "patient.book" }) : ui.pill("ok", L("Done", "تم", "हो गया")));
      }).join("") + "</div>";
    }
    h += ui.btn(L("Share my records with a doctor", "شارك سجلاتي مع طبيب", "अपने रिकॉर्ड डॉक्टर से साझा करें"), { cls: "blk lg", ov: "ptshare", icon: "share" });
    return h + "</div>";
  });

  NB.overlay("rxqr", function (id) {
    var r = PA.rx.find(function (x) { return x.id === id; }) || PA.rx[0], d = NB.doc(r.d), exp = r.st === "expired", ph = NB.v("rxPh_" + r.id, "clinic");
    var body = (exp ? ui.note("warn", t("This prescription has expired. Pharmacies can't fill it. Ask your doctor for a new one.", "انتهت صلاحية هذه الوصفة ولا يمكن صرفها. اطلب وصفة جديدة من طبيبك.", "यह पर्चा समाप्त हो गया है। फ़ार्मेसी इसे नहीं देगी। डॉक्टर से नया पर्चा लें।")) : "") +
      (exp ? "" : qr(r.id, 168)) +
      '<div style="text-align:center"><div class="pt-tok" style="font-size:20px;line-height:26px">' + r.id + '</div><span class="small faint">' + esc(NB.mkt().eRx) + "</span></div>" +
      (exp ? "" : '<div class="pt-count">' + ui.bar(100 * r.left / r.total, r.left <= 14 ? "warn" : "ok") + "<span>" + esc(L("Valid until ", "سارية حتى ", "मान्य: ")) + nm(r.until) + " · " + esc(L(r.left + " days left", "متبقٍ " + r.left + " يومًا", r.left + " दिन बाकी")) + "</span></div>") +
      '<div class="card">' + r.items.map(function (x) {
        var g = NB.drug(x[0]);
        return it(ico("pill", "acc"), esc(g.name), '<span class="ltr">' + esc(x[1]) + "</span> · " + esc(x[2]) + (x[4] ? " · " + esc(x[4]) : ""), '<span class="small muted num">×' + x[3] + "</span>");
      }).join("") + "</div>" +
      '<dl class="kv"><dt>' + t("Doctor", "الطبيب", "डॉक्टर") + "</dt><dd>" + esc(NB.dname(d)) + " · " + nm(d.regNo) + "</dd><dt>" + t("Written at", "صدرت في", "जारी") + "</dt><dd>" + esc(fname(r.f)) + "</dd><dt>" + t("Date", "التاريخ", "तारीख") + "</dt><dd>" + nm(r.date) + "</dd>" +
      "<dt>" + t("Allergy noted", "حساسية مسجلة", "एलर्जी दर्ज") + "</dt><dd>" + t("Penicillin", "البنسلين", "पेनिसिलिन") + "</dd></dl>";
    if (!exp) {
      body += '<div class="stack s8"><b style="font-size:14px">' + t("Where do you want to collect it?", "أين تريد استلامها؟", "दवा कहाँ से लेंगे?") + "</b>" +
        ui.radio("rxPh_" + r.id, "clinic", L("In-clinic pharmacy · " + fshort(r.f), "صيدلية العيادة · " + fshort(r.f), "क्लिनिक फ़ार्मेसी · " + fshort(r.f)), L("Ready in about 10 min · default", "جاهزة خلال 10 دقائق تقريبًا · افتراضي", "लगभग 10 मिनट में तैयार · डिफ़ॉल्ट"), "clinic") +
        ui.radio("rxPh_" + r.id, "near", NB.gcc() ? L("Life Pharmacy · 1.2 km", "صيدلية لايف · 1.2 كم") : L("MedPlus · Koramangala · 1.2 km", "ميدبلس · 1.2 كم", "मेडप्लस · कोरमंगला · 1.2 किमी"), L("Home delivery available", "التوصيل للمنزل متاح", "होम डिलीवरी उपलब्ध"), "clinic") +
        ui.radio("rxPh_" + r.id, "any", L("Any pharmacy — I'll show the QR", "أي صيدلية — سأُظهر الرمز", "कोई भी फ़ार्मेसी — मैं QR दिखाऊँगा"), L("Works at every pharmacy that reads " + NB.mkt().eRx, "يعمل في كل صيدلية تقرأ " + NB.mkt().eRx, NB.mkt().eRx + " पढ़ने वाली हर फ़ार्मेसी पर"), "clinic") + "</div>" +
        (r.refills ? ui.note("ok", esc(L(r.refills + " refills left. Ask for a refill and we'll tell you when it's ready.", "متبقٍ " + r.refills + " إعادة صرف. اطلب وسنخبرك عندما تجهز.", r.refills + " रीफ़िल बाकी। रीफ़िल माँगें, तैयार होने पर बताएँगे।")), "refresh") : "");
    }
    return { size: "", title: t("e-Prescription", "الوصفة الإلكترونية", "ई-प्रिस्क्रिप्शन"), body: body,
      foot: exp ? ui.btn(L("Ask for a new one", "اطلب وصفة جديدة", "नया पर्चा माँगें"), { cls: "pri", act: "toast", arg: L("Request sent to your doctor", "أُرسل الطلب إلى طبيبك", "डॉक्टर को अनुरोध भेजा"), icon: "send" })
        : ui.btn(L("PDF", "PDF", "PDF"), { cls: "ghost", act: "toast", arg: L("Downloading signed PDF", "جارٍ تنزيل PDF الموقّع", "हस्ताक्षरित PDF डाउनलोड हो रहा है"), icon: "download" }) +
          (r.refills ? ui.btn(L("Refill", "إعادة صرف", "रीफ़िल"), { act: "toast", arg: L("Refill requested · we'll message you", "طُلبت إعادة الصرف · سنرسل لك", "रीफ़िल माँगा · हम संदेश भेजेंगे"), icon: "refresh" }) : "") +
          ui.btn(L("Send to pharmacy", "أرسل للصيدلية", "फ़ार्मेसी को भेजें"), { cls: "pri", act: "toast", arg: ph === "any" ? L("Show this QR at the counter", "أظهر الرمز عند المكتب", "काउंटर पर यह QR दिखाएँ") : L("Sent · you'll get a message when it's ready", "أُرسلت · ستصلك رسالة عند الجاهزية", "भेजा गया · तैयार होने पर संदेश मिलेगा"), icon: "send" }) };
  });

  NB.overlay("ptlab", function (id) {
    var l = PA.labs.find(function (x) { return x.id === id; }) || PA.labs[0];
    var body = '<div class="pt-fach">' + ui.icon("building", "sm") + "<span>" + t("Held by", "محفوظة لدى", "रखे गए हैं:") + " <b>" + esc(fname(l.f)) + "</b> · " + nm(l.date) + "</span></div>";
    body += ui.note("info", esc(L("Released to you by " + NB.dname(NB.doc(l.by)) + ". Ask your doctor what these numbers mean for you.", "أصدره لك " + NB.dname(NB.doc(l.by)) + ". اسأل طبيبك عن معنى هذه الأرقام لك.", NB.dname(NB.doc(l.by)) + " ने आपके लिए जारी किया। इन संख्याओं का मतलब अपने डॉक्टर से पूछें।")), "info");
    if (!l.rows.length) body += '<div class="card">' + it(ico("file", "acc"), t("Report (PDF)", "التقرير (PDF)", "रिपोर्ट (PDF)"), t("Signed by the hospital", "موقّع من المستشفى", "अस्पताल द्वारा हस्ताक्षरित"), ui.icon("download", "sm faint"), { act: "toast", arg: L("Opening report…", "جارٍ فتح التقرير…", "रिपोर्ट खुल रही है…") }) + "</div>";
    body += l.rows.map(function (r) {
      var lo = r[5], hi = r[6], pos = function (v) { return Math.max(0, Math.min(100, 100 * (v - lo) / (hi - lo))); };
      var high = r[1] > r[4], low = r[1] < r[3];
      return '<div class="card"><div class="row sb nw"><b style="font-size:14px">' + esc(r[0]) + '</b><span class="num ltr" style="font:600 18px/24px var(--font)">' + r[1] + ' <small class="faint" style="font-size:12px">' + esc(r[2]) + "</small></span></div>" +
        '<div class="pt-rng"><span class="ok" style="inset-inline-start:' + pos(r[3]) + "%;width:" + (pos(r[4]) - pos(r[3])) + '%"></span><span class="mk' + (high || low ? " hi" : "") + '" style="inset-inline-start:' + pos(r[1]) + '%"></span></div>' +
        '<div class="row sb small faint"><span>' + t("Normal range", "النطاق الطبيعي", "सामान्य सीमा") + " " + nm(r[3] + "–" + r[4]) + "</span>" + (high ? ui.pill("warn", L("Above range", "أعلى من الطبيعي", "सीमा से ऊपर"), "alertcircle") : low ? ui.pill("warn", L("Below range", "أقل من الطبيعي", "सीमा से नीचे")) : ui.pill("ok", L("In range", "طبيعي", "सामान्य"))) + "</div></div>";
    }).join("");
    return { title: esc(L3(l.name)), body: body, foot: ui.btn(L("Share", "مشاركة", "साझा करें"), { ov: "ptshare", icon: "share" }) + ui.btn(L("Download PDF", "تنزيل PDF", "PDF डाउनलोड"), { cls: "pri", act: "toast", arg: L("Downloading signed report", "جارٍ تنزيل التقرير الموقّع", "हस्ताक्षरित रिपोर्ट डाउनलोड हो रही है"), icon: "download" }) };
  });

  NB.overlay("ptlinkid", function () {
    var r = idRail();
    return { kind: "modal", title: esc(L("Link your " + r[0], "اربط " + r[1], r[2] + " लिंक करें")), body:
      ui.note("info", esc(L("Linking lets hospitals you approve find your records. It never shares anything by itself.", "الربط يتيح للمستشفيات التي توافق عليها إيجاد سجلاتك، ولا يشارك أي شيء من تلقاء نفسه.", "लिंक करने से जिन अस्पतालों को आप मंज़ूरी दें, वे रिकॉर्ड ढूँढ सकते हैं। यह अपने-आप कुछ साझा नहीं करता।")), "shieldcheck") +
      ui.field(L(r[0], r[1], r[2]), "", { ph: NB.mkt().id === "india" ? "91-xxxx-xxxx-xxxx" : "784-xxxx-xxxxxxx-x" }) +
      '<span class="small muted">' + esc(L("You'll confirm with " + r[3] + ".", "ستؤكد عبر " + r[3] + ".", r[3] + " से पुष्टि करेंगे।")) + "</span>",
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द"), { act: "close", cls: "ghost" }) + ui.btn(L("Continue", "متابعة", "आगे बढ़ें"), { cls: "pri", act: "ptLinkId" }) };
  });
  NB.act("ptLinkId", function () { NB.state.ui.ptIdLinked = true; NB.state.overlay = null; NB.toast(L("Linked and verified", "تم الربط والتحقق", "लिंक और सत्यापित")); });

  NB.overlay("ptshare", function () {
    return { title: t("Share my records", "مشاركة سجلاتي", "मेरे रिकॉर्ड साझा करें"), body:
      ui.note("info", t("You choose who, what and for how long. Sharing ends by itself. You can stop it any time.", "أنت تختار مع من وماذا ولأي مدة. تنتهي المشاركة تلقائيًا ويمكنك إيقافها في أي وقت.", "किसे, क्या और कितने समय के लिए — आप तय करते हैं। साझा करना अपने-आप ख़त्म होता है, कभी भी रोक सकते हैं।"), "shieldcheck") +
      ui.field(L("Doctor or hospital", "الطبيب أو المستشفى", "डॉक्टर या अस्पताल"), NB.dname(NB.doc("d4")) + " · " + fshort(1), { full: true }) +
      '<div class="card stack s4">' + ui.toggle("shV", L("Visits", "الزيارات", "विज़िट"), "", true) + ui.toggle("shR", L("Prescriptions", "الوصفات", "पर्चे"), "", true) + ui.toggle("shL", L("Lab reports", "التحاليل", "लैब रिपोर्ट"), "", true) + ui.toggle("shD", L("Documents", "المستندات", "दस्तावेज़"), "") + "</div>" +
      '<span class="small muted">' + t("From", "من", "कब से") + "</span>" + ui.seg("shRange", [["6m", L("Last 6 months", "آخر 6 أشهر", "पिछले 6 महीने")], ["12m", L("12 months", "12 شهرًا", "12 महीने")], ["all", L("Everything", "الكل", "सब")]], "6m") +
      '<span class="small muted">' + t("For how long", "لأي مدة", "कितने समय के लिए") + "</span>" + ui.seg("shLen", [["1d", L("24 hours", "24 ساعة", "24 घंटे")], ["7d", L("7 days", "7 أيام", "7 दिन")], ["30d", L("30 days", "30 يومًا", "30 दिन")]], "7d"),
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द"), { act: "close", cls: "ghost" }) + ui.btn(L("Share with fingerprint", "شارك بالبصمة", "फ़िंगरप्रिंट से साझा करें"), { cls: "pri", act: "toast", arg: L("Shared · the doctor is notified · ends automatically", "تمت المشاركة · أُبلغ الطبيب · تنتهي تلقائيًا", "साझा किया · डॉक्टर को सूचना · अपने-आप ख़त्म"), icon: "fingerprint" }) };
  });

  /* ---------------- Patient app: Family ---------------- */
  NB.screen("patient.family", function (c) {
    var h = '<div class="pt-col"><div class="pt-hello"><div class="fl"><h1>' + t("Family & caregivers", "العائلة ومقدّمو الرعاية", "परिवार और देखभालकर्ता") + "</h1><p>" + t("One phone number, the whole family. Each person's records stay their own.", "رقم هاتف واحد للعائلة كلها، وسجلات كل شخص تبقى خاصة به.", "एक फ़ोन नंबर, पूरा परिवार। हर व्यक्ति के रिकॉर्ड उसके अपने रहते हैं।") + "</p></div></div>";
    h += sec(t("People you look after", "من تعتني بهم", "जिनकी आप देखभाल करते हैं"));
    h += '<div class="card">' + PA.members.filter(function (m) { return m.id !== "me"; }).map(function (m) {
      var role = m.id === "meenu" ? ui.pill("info", L("Guardian", "ولي أمر", "अभिभावक"), "shielduser") : m.id === "amma" ? ui.pill("sub", L("POA", "وكالة", "POA"), "handshake") : ui.pill("mute", L("Family", "عائلة", "परिवार"));
      var sub = m.id === "meenu" ? L("Until she turns 18 · 12 Mar 2029", "حتى تبلغ 18 · 12 مارس 2029", "18 साल होने तक · 12 मार्च 2029") : m.id === "amma" ? L("Verified at the clinic desk · can approve sharing", "موثّق في مكتب العيادة · يمكنك الموافقة على المشاركة", "क्लिनिक डेस्क पर सत्यापित · साझा करने की मंज़ूरी दे सकते हैं") : L("Adult · she shares appointments with you", "بالغة · تشاركك مواعيدها", "वयस्क · वे आपसे अपॉइंटमेंट साझा करती हैं");
      return it(ui.av(m.name, "lg"), esc(mname(m)), esc(rel(m)) + ", " + nm(m.age) + "<br>" + esc(sub), role, { ov: "ptmember", arg: m.id });
    }).join("") + "</div>";
    h += sec(t("Who can see your records", "من يمكنه رؤية سجلاتك", "आपके रिकॉर्ड कौन देख सकता है"), ui.btn(L("Invite", "دعوة", "आमंत्रित करें"), { cls: "sm pri", ov: "invitecaregiver", icon: "userplus" }));
    h += '<div class="card">' + PA.access.map(function (a) {
      var gone = NB.v("ptAccOff_" + a.id, false);
      return it(ui.av(a.name, "lg"), esc(NB.rtl() ? a.ar : a.name) + (a.abroad ? ' <span class="pill mute" style="height:20px">' + ui.icon("globe") + "UAE</span>" : ""), esc(L3(a.role)) + "<br>" + (gone ? t("Access removed", "أُزيل الوصول", "एक्सेस हटाया गया") : esc(L3(a.until))),
        gone ? ui.pill("perm", L("Removed", "أُزيل", "हटाया")) : a.pending ? ui.pill("warn", L("Invited", "مدعو", "आमंत्रित")) : chev(), { ov: "ptmember", arg: a.id });
    }).join("") + "</div>";
    // Capability matrix
    var cols = [["calendar", L("Book", "حجز", "बुक")], ["steth", L("Visits", "زيارات", "विज़िट")], ["pill", L("Prescriptions", "وصفات", "पर्चे")], ["flask", L("Lab results", "تحاليل", "लैब")], ["wallet", L("Pay bills", "دفع", "भुगतान")], ["shieldcheck", L("Approve sharing", "موافقة على المشاركة", "साझा करने की मंज़ूरी")]];
    h += sec(t("What each person can do", "ما يستطيع كل شخص فعله", "कौन क्या कर सकता है"));
    h += '<div class="card"><div class="pt-cap"><span></span>' + cols.map(function (c2) { return '<span class="h" title="' + esc(c2[1]) + '">' + ui.icon(c2[0], "sm") + "</span>"; }).join("") + '<hr class="hr">' +
      [{ n: L("You", "أنت", "आप"), s: "", cap: [1, 1, 1, 1, 1, 1] }].concat(PA.access.map(function (a) { return { n: NB.rtl() ? a.ar : a.name, s: L3(a.role).split(" · ")[0], cap: a.cap }; })).map(function (r) {
        return '<span class="nm">' + esc(r.n) + (r.s ? "<small>" + esc(r.s) + "</small>" : "") + "</span>" + r.cap.map(function (y, i) { return '<span class="c ' + (y ? "y" : "n") + '" aria-label="' + esc(cols[i][1] + ": " + (y ? L("yes", "نعم", "हाँ") : L("no", "لا", "नहीं"))) + '">' + ui.icon(y ? "checkcircle" : "lock", "sm") + "</span>"; }).join("");
      }).join("") + "</div>" +
      '<div class="pt-legend" style="margin-top:12px">' + cols.map(function (c2) { return "<span>" + ui.icon(c2[0], "xs") + esc(c2[1]) + "</span>"; }).join("") + "</div></div>";
    h += ui.note("info", t("Caregivers never see visit notes unless you allow it. Only you — or someone with power of attorney — can approve sharing with a hospital.", "لا يرى مقدّمو الرعاية ملاحظات الزيارة إلا بإذنك. أنت فقط — أو من يحمل وكالة — يمكنه الموافقة على المشاركة مع مستشفى.", "देखभालकर्ता आपकी अनुमति के बिना विज़िट नोट्स नहीं देखते। अस्पताल से साझा करने की मंज़ूरी सिर्फ़ आप — या पावर ऑफ़ अटॉर्नी वाले — दे सकते हैं।"), "lock");
    h += ui.btn(L("Invite a caregiver abroad", "ادعُ مقدّم رعاية في الخارج", "विदेश में रहने वाले देखभालकर्ता को आमंत्रित करें"), { cls: "blk lg", ov: "invitecaregiver", icon: "globe" });
    return h + "</div>";
  });

  NB.overlay("ptmember", function (id) {
    var a = PA.access.find(function (x) { return x.id === id; }), m = PA.members.find(function (x) { return x.id === id; });
    var name = a ? (NB.rtl() ? a.ar : a.name) : mname(m);
    var body = '<div class="pt-res">' + ui.av(a ? a.name : m.name, "xl") + "<h2>" + esc(name) + '</h2><span class="small muted">' + esc(a ? L3(a.role) : rel(m)) + "</span></div>";
    if (a) {
      var caps = [["Book appointments", "حجز المواعيد", "अपॉइंटमेंट बुक करना"], ["See visits", "رؤية الزيارات", "विज़िट देखना"], ["See prescriptions", "رؤية الوصفات", "पर्चे देखना"], ["See lab results", "رؤية نتائج المختبر", "लैब रिपोर्ट देखना"], ["Pay bills", "دفع الفواتير", "बिल भरना"]];
      body += '<div class="card stack s4">' + caps.map(function (c2, i) { return ui.toggle("cap_" + id + "_" + i, L(c2[0], c2[1], c2[2]), "", !!a.cap[i]); }).join("") +
        '<div class="tog" style="cursor:default"><div class="t"><b>' + t("Approve sharing with hospitals", "الموافقة على المشاركة مع المستشفيات", "अस्पतालों से साझा करने की मंज़ूरी") + "</b><small>" + t("Only you or a verified power of attorney", "أنت فقط أو وكيل موثّق", "सिर्फ़ आप या सत्यापित पावर ऑफ़ अटॉर्नी") + "</small></div>" + ui.icon("lock", "sm faint") + "</div></div>" +
        '<span class="small muted">' + t("Access ends", "ينتهي الوصول", "एक्सेस ख़त्म") + "</span>" + ui.seg("capEnd_" + id, [["3m", L("3 months", "3 أشهر", "3 महीने")], ["6m", L("6 months", "6 أشهر", "6 महीने")], ["1y", L("1 year", "سنة", "1 साल")], ["never", L("When I stop it", "عندما أوقفه", "जब मैं रोकूँ")]], "6m") +
        (a.abroad ? ui.note("info", t("Nikhil is told within 5 minutes when you check in, get a prescription or a result — as a secure link, never the details in the message.", "يُبلَّغ نيخيل خلال 5 دقائق عند وصولك أو صدور وصفة أو نتيجة — برابط آمن دون تفاصيل في الرسالة.", "चेक-इन, पर्चा या रिपोर्ट आने पर निखिल को 5 मिनट में सुरक्षित लिंक मिलता है — संदेश में विवरण नहीं।"), "bell") : "");
    } else if (m.id === "meenu") {
      body += ui.note("info", t("As guardian you can book, see her records and approve sharing until 12 Mar 2029. At 18 she gets her own login and your access ends automatically.", "بصفتك ولي الأمر يمكنك الحجز ورؤية سجلاتها والموافقة على المشاركة حتى 12 مارس 2029. عند 18 تحصل على حسابها وينتهي وصولك تلقائيًا.", "अभिभावक के रूप में आप 12 मार्च 2029 तक बुक कर सकते हैं, रिकॉर्ड देख सकते हैं और साझा करने की मंज़ूरी दे सकते हैं। 18 पर उसका अपना लॉगिन बनेगा और आपका एक्सेस अपने-आप ख़त्म होगा।"), "shielduser") +
        ui.note("warn", t("Some teen health topics are confidential between Meenu and her doctor.", "بعض المواضيع الصحية للمراهقين سرية بين مينو وطبيبها.", "किशोर स्वास्थ्य के कुछ विषय मीनू और उसके डॉक्टर के बीच गोपनीय हैं।"), "lock");
    } else if (m.id === "amma") {
      body += ui.note("info", t("Power of attorney verified by Suvidha Clinic on 4 Jul 2026. You can book, see records, pay and approve sharing for Amma.", "وكالة موثّقة من العيادة في 4 يوليو 2026. يمكنك الحجز ورؤية السجلات والدفع والموافقة على المشاركة لأمك.", "पावर ऑफ़ अटॉर्नी 4 जुलाई 2026 को क्लिनिक ने सत्यापित की। आप माँ के लिए बुक, रिकॉर्ड देखना, भुगतान और साझा करने की मंज़ूरी दे सकते हैं।"), "handshake") +
        ui.toggle("ammaVoice", L("Voice reminders in Malayalam", "تذكيرات صوتية بالمالايالامية", "मलयालम में वॉइस रिमाइंडर"), L("She can't read SMS — reminders are spoken", "لا تقرأ الرسائل — التذكيرات صوتية", "वे SMS नहीं पढ़ पातीं — रिमाइंडर बोलकर"), true) +
        ui.toggle("ammaAssist", L("Assisted consent at the clinic", "موافقة بمساعدة في العيادة", "क्लिनिक पर सहायता से सहमति"), L("A staff member explains and witnesses", "يشرح موظف ويشهد", "स्टाफ़ समझाता है और गवाह रहता है"), true);
    } else {
      body += ui.note("info", t("Latha is an adult with her own records. She chose to let you book and see her appointments.", "لاثا بالغة ولها سجلاتها. اختارت أن تسمح لك بالحجز ورؤية مواعيدها.", "लता वयस्क हैं और उनके अपने रिकॉर्ड हैं। उन्होंने आपको बुक करने और अपॉइंटमेंट देखने की अनुमति दी है।"));
    }
    return { title: esc(name), body: body, foot: (a ? ui.btn(L("Remove access", "إزالة الوصول", "एक्सेस हटाएँ"), { cls: "dng", act: "ptAccOff", arg: id, icon: "close" }) : "") + ui.btn(L("Save", "حفظ", "सहेजें"), { cls: "pri", act: "toast", arg: L("Saved · they're notified", "تم الحفظ · أُبلغوا", "सहेजा · उन्हें सूचना दी गई"), icon: "check" }) };
  });
  NB.act("ptAccOff", function (id) { NB.state.ui["ptAccOff_" + id] = true; NB.state.overlay = null; NB.toast(L("Access removed — effective now", "أُزيل الوصول فورًا", "एक्सेस तुरंत हटाया गया")); });

  NB.overlay("invitecaregiver", function () {
    var cc = NB.v("invCc", "+971");
    return { title: t("Invite a caregiver", "دعوة مقدّم رعاية", "देखभालकर्ता को आमंत्रित करें"), body:
      ui.note("info", t("Perfect for family abroad. They get a WhatsApp invite and confirm with a code. You choose exactly what they see.", "مناسب للعائلة في الخارج. تصلهم دعوة واتساب ويؤكدون برمز. أنت تختار ما يرونه بالضبط.", "विदेश में रहने वाले परिवार के लिए। उन्हें व्हाट्सऐप निमंत्रण मिलेगा और कोड से पुष्टि करेंगे। वे क्या देखें, यह आप तय करते हैं।"), "globe") +
      '<div class="fgrid">' + ui.field(L("Name", "الاسم", "नाम"), "Nikhil Menon") + ui.field(L("Relationship", "الصلة", "रिश्ता"), L("Son", "ابن", "बेटा"), { options: [L("Son", "ابن", "बेटा"), L("Daughter", "ابنة", "बेटी"), L("Spouse", "زوج/زوجة", "पति/पत्नी"), L("Sibling", "أخ/أخت", "भाई/बहन"), L("Home nurse", "ممرض منزلي", "होम नर्स")] }) +
      ui.field(L("Country code", "رمز الدولة", "देश कोड"), cc, { options: ["+971", "+966", "+974", "+91", "+44", "+1"] }) + ui.field(L("WhatsApp number", "رقم واتساب", "व्हाट्सऐप नंबर"), "50 218 4417", { type: "tel" }) + "</div>" +
      '<span class="small muted">' + t("Caring for", "يعتني بـ", "किसकी देखभाल") + "</span>" + ui.seg("invFor", [["me", L("Me", "أنا", "मैं")], ["amma", L("Amma", "أمي", "माँ")], ["both", L("Both", "كلانا", "दोनों")]], "both") +
      '<div class="card stack s4">' + ui.toggle("invA", L("Appointments & live queue", "المواعيد والطابور المباشر", "अपॉइंटमेंट और लाइव क़तार"), L("Book, reschedule, get “leave now” alerts", "حجز وتعديل وتنبيهات «اخرج الآن»", "बुक, बदलाव, “अभी निकलें” अलर्ट"), true) +
      ui.toggle("invR", L("Prescriptions", "الوصفات", "पर्चे"), L("Notified within 5 min of a new one", "إشعار خلال 5 دقائق من صدورها", "नया पर्चा आने के 5 मिनट में सूचना"), true) +
      ui.toggle("invL", L("Lab results", "نتائج المختبر", "लैब रिपोर्ट"), L("After the doctor releases them", "بعد أن يصدرها الطبيب", "डॉक्टर के जारी करने के बाद"), true) +
      ui.toggle("invP", L("Pay bills from abroad", "دفع الفواتير من الخارج", "विदेश से बिल भरना"), L("Card, " + (NB.gcc() ? "Apple Pay" : "UPI or international card"), "بطاقة أو Apple Pay", "कार्ड, UPI या अंतरराष्ट्रीय कार्ड"), true) +
      ui.toggle("invN", L("Visit notes", "ملاحظات الزيارة", "विज़िट नोट्स"), L("Off by default — more private", "مغلق افتراضيًا — خصوصية أكبر", "डिफ़ॉल्ट बंद — ज़्यादा निजी"), false) + "</div>" +
      '<span class="small muted">' + t("Access ends", "ينتهي الوصول", "एक्सेस ख़त्म") + "</span>" + ui.seg("invEnd", [["3m", L("3 months", "3 أشهر", "3 महीने")], ["6m", L("6 months", "6 أشهر", "6 महीने")], ["1y", L("1 year", "سنة", "1 साल")], ["never", L("When I stop it", "عندما أوقفه", "जब मैं रोकूँ")]], "6m") +
      '<span class="small muted">' + t("They'll receive", "سيصلهم", "उन्हें मिलेगा") + '</span><div class="wa" style="border-radius:var(--r-md);padding:12px;flex:none"><div class="bub in pt-b" style="max-width:100%">' +
      esc(L("Rakesh Menon invited you to help with his family's care on Nabd. Open the link to accept — you'll confirm with a code.", "دعاك راكيش مينون للمساعدة في رعاية عائلته على نبض. افتح الرابط للقبول — ستؤكد برمز.", "राकेश मेनन ने आपको Nabd पर परिवार की देखभाल में मदद के लिए आमंत्रित किया है। स्वीकार करने के लिए लिंक खोलें — कोड से पुष्टि करेंगे।")) +
      '<span class="pt-lk">' + ui.icon("lock", "sm") + '<span><span class="ltr">nabd.link/i/7KQ2</span><small>' + t("No health details in this message", "لا تفاصيل صحية في هذه الرسالة", "इस संदेश में स्वास्थ्य जानकारी नहीं") + '</small></span></span><span class="pt-ts">09:41</span></div></div>',
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द"), { act: "close", cls: "ghost" }) + ui.btn(L("Send invite on WhatsApp", "أرسل الدعوة عبر واتساب", "व्हाट्सऐप पर निमंत्रण भेजें"), { cls: "pri", act: "toast", arg: L("Invite sent · we'll tell you when they accept", "أُرسلت الدعوة · سنخبرك عند القبول", "निमंत्रण भेजा · स्वीकार होने पर बताएँगे"), icon: "send" }) };
  });

  /* ---------------- Patient app: Privacy ---------------- */
  function activeShares() {
    var c1 = NB.D.consents.find(function (x) { return x.id === "C-2038"; });
    return [
      { id: c1.id, to: fshort(0), from: L("Apollo Clinic, HSR Layout", "عيادة أبولو، HSR", "अपोलो क्लिनिक, HSR लेआउट"), cats: c1.data, until: c1.until, left: L("4 days left", "متبقٍ 4 أيام", "4 दिन बाकी"), pct: 57, reads: c1.reads, by: c1.by },
      { id: "C-2044", to: NB.dname(NB.doc("d4")) + " · " + fshort(1), from: fshort(0), cats: ["Lab results"], until: "18 Oct 2026, 10:00", left: L("23 hours left", "متبقٍ 23 ساعة", "23 घंटे बाकी"), pct: 96, reads: 1, by: L("You", "أنت", "आप") }
    ];
  }
  NB.screen("patient.privacy", function (c) {
    var R = PA.consentReq, rq = NB.v("ptReq", "pending");
    var h = '<div class="pt-col"><div class="pt-hello"><div class="fl"><h1>' + t("Privacy & sharing", "الخصوصية والمشاركة", "गोपनीयता और साझा करना") + "</h1><p>" + t("Records stay with each hospital. Nothing moves without your OK.", "تبقى السجلات لدى كل مستشفى. لا شيء ينتقل دون موافقتك.", "रिकॉर्ड हर अस्पताल के पास रहते हैं। आपकी मंज़ूरी के बिना कुछ नहीं जाता।") + "</p></div></div>";
    h += sec(t("Requests", "الطلبات", "अनुरोध") + (rq === "pending" ? ' <span class="nb-badge alert">1</span>' : ""));
    if (rq === "pending") {
      h += '<div class="card pt-req stack">' + it(ui.av(NB.doc(R.d).name, "lg"), esc(NB.dname(NB.doc(R.d))) + " · " + esc(fshort(R.f)), esc(L("Asks to see records held by ", "يطلب الاطلاع على السجلات لدى ", "इनके पास रखे रिकॉर्ड देखना चाहते हैं: ")) + "<b>" + esc(fshort(R.from)) + "</b>", "") +
        '<dl class="kv"><dt>' + t("What", "ماذا", "क्या") + "</dt><dd>" + esc(R.cats.map(L3).join(", ")) + "</dd><dt>" + t("From", "الفترة", "अवधि") + "</dt><dd>" + esc(L3(R.range)) + "</dd><dt>" + t("For", "لمدة", "कितने समय") + "</dt><dd>" + t("7 days", "7 أيام", "7 दिन") + "</dd><dt>" + t("Why", "السبب", "क्यों") + "</dt><dd>" + esc(L3(R.purpose)) + "</dd></dl>" +
        '<div class="pt-2">' + ui.btn(L("Deny", "رفض", "मना करें"), { cls: "pt-big", act: "ptConsent", arg: "denied", icon: "close" }) + ui.btn(L("Review & approve", "مراجعة وموافقة", "देखें और मंज़ूर करें"), { cls: "pri pt-big", ov: "consentask", arg: R.id, icon: "shieldcheck" }) + "</div></div>";
    } else {
      h += '<div class="card">' + it(ico(rq === "approved" ? "shieldcheck" : "close", rq === "approved" ? "ok" : "crit"), esc(fshort(R.f)) + " · " + esc(rq === "approved" ? L("approved by you", "وافقت عليه", "आपने मंज़ूर किया") : L("declined", "مرفوض", "अस्वीकृत")),
        rq === "approved" ? t("Now waiting for your doctor at the clinic to check it. Access starts after that.", "بانتظار مراجعة طبيبك في العيادة. يبدأ الوصول بعد ذلك.", "अब क्लिनिक में आपके डॉक्टर की जाँच बाकी है। उसके बाद एक्सेस शुरू होगा।") : t("Nothing was shared. The hospital has been told.", "لم يُشارك شيء. أُبلغ المستشفى.", "कुछ साझा नहीं हुआ। अस्पताल को बता दिया गया।"),
        rq === "approved" ? ui.pill("warn", L("Doctor check", "مراجعة الطبيب", "डॉक्टर जाँच"), "hourglass") : ui.pill("perm", L("Closed", "مغلق", "बंद"))) + "</div>";
    }
    h += sec(t("Active shares", "مشاركات نشطة", "चालू साझेदारी"));
    h += activeShares().map(function (s) {
      var rv = NB.v("ptRev_" + s.id, false);
      return '<div class="card stack s8">' + it(ico("share", rv ? "" : "acc"), esc(s.from) + " → " + esc(s.to), esc(s.cats.map(function (x) { return { Encounters: L("Visits", "الزيارات", "विज़िट"), Prescriptions: L("Prescriptions", "الوصفات", "पर्चे"), "Lab results": L("Lab results", "نتائج المختبر", "लैब रिपोर्ट"), "Discharge summary": L("Discharge summary", "ملخص الخروج", "डिस्चार्ज सारांश") }[x] || x; }).join(", ")),
        rv ? ui.pill("perm", L("Stopped", "أُوقفت", "रोकी गई")) : ui.pill("ok", L("Active", "نشطة", "चालू"))) +
        (rv ? '<span class="small muted">' + esc(L("Access ended just now. Viewed " + s.reads + " times while it was open.", "انتهى الوصول الآن. شوهدت " + s.reads + " مرات أثناء فتحها.", "एक्सेस अभी ख़त्म हुआ। खुले रहने के दौरान " + s.reads + " बार देखा गया।")) + "</span>"
          : '<div class="pt-count">' + ui.bar(100 - s.pct, s.pct > 90 ? "warn" : "") + "<span>" + esc(s.left) + "</span></div>" +
            '<div class="row sb"><span class="small faint">' + esc(L("Ends ", "تنتهي ", "ख़त्म: ")) + nm(s.until) + " · " + esc(L(s.reads + (s.reads === 1 ? " view" : " views"), s.reads + " مشاهدات", s.reads + " बार देखा")) + " · " + nm(s.id) + "</span>" + ui.btn(L("Stop sharing", "أوقف المشاركة", "साझा करना रोकें"), { cls: "sm dng", ov: "ptrevoke", arg: s.id }) + "</div>") + "</div>";
    }).join("");
    h += sec(t("Who viewed my records", "من اطّلع على سجلاتي", "मेरे रिकॉर्ड किसने देखे"));
    h += '<div class="card"><div class="tl">' + PA.history.map(function (e) {
      var who = e.who === "You" ? L("You", "أنت", "आप") : e.who.indexOf("Dr.") === 0 ? NB.dname(NB.D.doctors.find(function (d) { return d.name === e.who; }) || { name: e.who }) : (NB.rtl() ? (PA.access.find(function (a) { return a.name === e.who; }) || {}).ar || e.who : e.who);
      return '<div class="ev"><span class="dotc ' + e.c + '">' + ui.icon(e.ic, "sm") + '</span><div class="bd"><b>' + esc(who) + (e.f >= 0 ? ' <span class="faint" style="font-weight:400">· ' + esc(fshort(e.f)) + "</span>" : "") + "</b><small>" + esc(L3(e.what)) + "</small><small>" + nm(e.when) + (e.tag ? " · " + nm(e.tag) : "") + "</small></div></div>";
    }).join("") + "</div></div>";
    h += sec(t("Your data", "بياناتك", "आपका डेटा"));
    h += '<div class="card">' + it(ico("download", "acc"), t("Download my data", "تنزيل بياناتي", "मेरा डेटा डाउनलोड करें"), t("A copy of everything, from every hospital", "نسخة من كل شيء من كل مستشفى", "हर अस्पताल से सब कुछ की एक कॉपी"), chev(), { ov: "ptdownload" }) +
      it(ico("fingerprint"), esc(L(idRail()[0], idRail()[1], idRail()[2])), esc(L("Linked identity", "الهوية المرتبطة", "लिंक की गई पहचान")), chev(), { act: "ptRecTab", arg: "visits" }) +
      "</div>";
    h += '<div class="card stack s4">' + ui.toggle("ptLockSafe", L("Lock-screen safe messages", "رسائل آمنة على شاشة القفل", "लॉक-स्क्रीन सुरक्षित संदेश"), L("WhatsApp and notifications never include health details", "واتساب والإشعارات لا تتضمن تفاصيل صحية أبدًا", "व्हाट्सऐप और सूचनाओं में स्वास्थ्य जानकारी कभी नहीं"), true) +
      ui.toggle("ptEmerg", L("Emergency access", "الوصول في الطوارئ", "आपातकालीन एक्सेस"), L("A doctor may open your records in an emergency. You'll be told and it's reviewed.", "قد يفتح طبيب سجلاتك في الطوارئ، وستُبلَّغ وتُراجع.", "आपात स्थिति में डॉक्टर रिकॉर्ड खोल सकते हैं। आपको बताया जाएगा और समीक्षा होगी।"), true) + "</div>";
    h += '<p class="small faint" style="margin:0;text-align:center">' + esc(L("Protected under " + NB.mkt().privacyLaw + " · data kept in " + NB.mkt().residency, "محمية بموجب " + NB.mkt().privacyLaw + " · البيانات محفوظة في " + NB.mkt().residency, NB.mkt().privacyLaw + " के तहत सुरक्षित · डेटा " + NB.mkt().residency + " में")) + "</p>";
    return h + "</div>";
  });

  NB.overlay("consentask", function () {
    var R = PA.consentReq, d = NB.doc(R.d);
    return { title: t("Record request", "طلب سجلات", "रिकॉर्ड अनुरोध"), body:
      '<div class="pt-res">' + ui.av(d.name, "xl") + "<h2>" + esc(NB.dname(d)) + '</h2><span class="small muted">' + esc(fname(R.f)) + " · " + nm(d.regNo) + "</span>" + ui.pill("ok", L("Verified doctor", "طبيب موثّق", "सत्यापित डॉक्टर"), "shieldcheck") + "</div>" +
      '<div class="card stack s8"><div class="pt-fach">' + ui.icon("building", "sm") + "<span>" + t("Records held by", "السجلات لدى", "रिकॉर्ड इनके पास:") + " <b>" + esc(fname(R.from)) + "</b></span></div>" +
      '<span class="small muted">' + t("Untick anything you don't want to share", "ألغِ تحديد ما لا تريد مشاركته", "जो साझा नहीं करना, उसे हटा दें") + "</span>" +
      R.cats.map(function (c2, i) { return ui.toggle("caCat" + i, L3(c2), "", true); }).join("") + "</div>" +
      '<dl class="kv"><dt>' + t("Dates", "الفترة", "अवधि") + "</dt><dd>" + esc(L3(R.range)) + "</dd><dt>" + t("Why", "السبب", "क्यों") + "</dt><dd>" + esc(L3(R.purpose)) + "</dd></dl>" +
      '<span class="small muted">' + t("How long can they see it?", "لأي مدة يمكنهم رؤيتها؟", "वे कितने समय तक देख सकते हैं?") + "</span>" + ui.seg("caLen", [["1d", L("24 hours", "24 ساعة", "24 घंटे")], ["7d", L("7 days", "7 أيام", "7 दिन")], ["30d", L("30 days", "30 يومًا", "30 दिन")]], "7d") +
      ui.note("info", t("After you approve, your doctor at the clinic also checks it. Access ends by itself, every view is recorded, and you can stop it any time.", "بعد موافقتك يراجع طبيبك في العيادة الطلب أيضًا. ينتهي الوصول تلقائيًا، وتُسجَّل كل مشاهدة، ويمكنك إيقافه في أي وقت.", "आपकी मंज़ूरी के बाद क्लिनिक में आपके डॉक्टर भी जाँचते हैं। एक्सेस अपने-आप ख़त्म होता है, हर बार देखना दर्ज होता है, और आप कभी भी रोक सकते हैं।"), "shieldcheck"),
      foot: ui.btn(L("Deny", "رفض", "मना करें"), { cls: "dng", act: "ptConsent", arg: "denied", icon: "close" }) + ui.btn(L("Approve with fingerprint", "وافق بالبصمة", "फ़िंगरप्रिंट से मंज़ूर करें"), { cls: "pri", act: "ptConsent", arg: "approved", icon: "fingerprint" }) };
  });
  NB.act("ptConsent", function (a) {
    NB.state.ui.ptReq = a; NB.state.overlay = null;
    NB.toast(a === "approved" ? L("Approved · your doctor at the clinic checks it next", "تمت الموافقة · يراجعها طبيبك في العيادة", "मंज़ूर · अब क्लिनिक में आपके डॉक्टर जाँचेंगे") : L("Declined · nothing was shared", "مرفوض · لم يُشارك شيء", "अस्वीकृत · कुछ साझा नहीं हुआ"));
  });
  NB.overlay("ptrevoke", function (id) {
    var s = activeShares().find(function (x) { return x.id === id; }) || activeShares()[0];
    return { kind: "modal", title: t("Stop sharing?", "إيقاف المشاركة؟", "साझा करना रोकें?"), body:
      "<p style=\"margin:0\">" + esc(L(s.to + " will lose access right away. Copies they opened are logged; they can ask you again later.", "سيفقد " + s.to + " الوصول فورًا. ما فتحوه مسجّل، ويمكنهم الطلب مجددًا لاحقًا.", s.to + " का एक्सेस तुरंत ख़त्म होगा। उन्होंने जो खोला वह दर्ज है; वे बाद में फिर माँग सकते हैं।")) + "</p>" +
      '<dl class="kv"><dt>' + t("Viewed", "شوهدت", "देखा गया") + "</dt><dd>" + esc(L(s.reads + " times", s.reads + " مرات", s.reads + " बार")) + "</dd><dt>" + t("Consent", "الموافقة", "सहमति") + "</dt><dd>" + nm(s.id) + "</dd></dl>",
      foot: ui.btn(L("Keep", "إبقاء", "रहने दें"), { act: "close", cls: "ghost" }) + ui.btn(L("Stop sharing", "أوقف المشاركة", "साझा करना रोकें"), { cls: "dng", act: "ptRevoke", arg: s.id, icon: "stop" }) };
  });
  NB.act("ptRevoke", function (id) { NB.state.ui["ptRev_" + id] = true; NB.state.overlay = null; NB.toast(L("Sharing stopped · effective now", "أُوقفت المشاركة فورًا", "साझा करना तुरंत रोका गया")); });
  NB.overlay("ptdownload", function () {
    return { title: t("Download my data", "تنزيل بياناتي", "मेरा डेटा डाउनलोड करें"), body:
      ui.note("info", esc(L("This is your right under " + NB.mkt().privacyLaw + ". It doesn't need a doctor's approval.", "هذا حقك بموجب " + NB.mkt().privacyLaw + "، ولا يحتاج موافقة طبيب.", NB.mkt().privacyLaw + " के तहत यह आपका अधिकार है। इसके लिए डॉक्टर की मंज़ूरी नहीं चाहिए।")), "shieldcheck") +
      '<div class="card stack s4">' + [0, 1].map(function (i) { return ui.toggle("dl" + i, fname(i), L("Visits, prescriptions, reports, bills", "الزيارات والوصفات والتقارير والفواتير", "विज़िट, पर्चे, रिपोर्ट, बिल"), true); }).join("") + "</div>" +
      '<span class="small muted">' + t("Format", "الصيغة", "फ़ॉर्मैट") + "</span>" + ui.seg("dlFmt", [["pdf", L("Readable PDF", "PDF مقروء", "पढ़ने योग्य PDF")], ["fhir", L("Health data file (FHIR)", "ملف بيانات صحية (FHIR)", "हेल्थ डेटा फ़ाइल (FHIR)")]], "pdf") +
      ui.note("info", t("We'll send a secure link to the app. It opens with a one-time code and expires in 24 hours.", "سنرسل رابطًا آمنًا إلى التطبيق يُفتح برمز لمرة واحدة وينتهي خلال 24 ساعة.", "हम ऐप में सुरक्षित लिंक भेजेंगे। यह एक बार के कोड से खुलेगा और 24 घंटे में ख़त्म होगा।"), "lock"),
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द"), { act: "close", cls: "ghost" }) + ui.btn(L("Prepare my download", "جهّز التنزيل", "डाउनलोड तैयार करें"), { cls: "pri", act: "toast", arg: L("Preparing · ready in a few minutes", "جارٍ التجهيز · جاهز خلال دقائق", "तैयार हो रहा है · कुछ मिनट में"), icon: "download" }) };
  });

  /* ---------------- WhatsApp conversation ---------------- */
  var WA_CLINIC = function () { return fshort(0); };
  function bub(html, at, o) {
    o = o || {};
    return '<div class="bub in pt-b">' + html + '<span class="pt-ts">' + at + "</span>" + (o.btns || []).map(function (b) {
      return '<span class="wbtn" role="button" tabindex="0"' + (b[2] ? " " + b[2] : ' data-act="toast" data-arg="' + esc(L("Opens a secure page — the code is checked first", "تفتح صفحة آمنة — يُتحقق من الرمز أولًا", "सुरक्षित पेज खुलता है — पहले कोड जाँचा जाता है")) + '"') + ">" + ui.icon(b[0], "sm") + esc(b[1]) + "</span>";
    }).join("") + "</div>";
  }
  function lk(path, note) { return '<span class="pt-lk">' + ui.icon("lock", "sm") + '<span><span class="ltr">' + path + "</span><small>" + esc(note) + "</small></span></span>"; }
  function code(at) {
    return bub('<span class="pt-code">482 913</span>' + esc(L(" is your verification code. For your security, do not share this code.", " هو رمز التحقق. لأمانك، لا تشارك هذا الرمز.", " आपका सत्यापन कोड है। सुरक्षा के लिए यह कोड किसी से साझा न करें।")), at, { btns: [["copy", L("Copy code", "نسخ الرمز", "कोड कॉपी करें"), 'data-act="toast" data-arg="' + esc(L("Code copied", "تم نسخ الرمز", "कोड कॉपी हुआ")) + '"']] });
  }

  var SCN = {
    book: { n: 1, label: ["Book", "حجز", "बुकिंग"], me: ["Kavya Reddy · patient", "كافيا ريدي · مريضة", "काव्या रेड्डी · मरीज़"], pre: ["Hi", "مرحبًا", "नमस्ते"],
      stage: function (p) {
        var k = p.length, d1 = NB.doc("d1"), pick = p[2] ? NB.doc(p[2] === "any" ? "d3" : p[2]) : d1, slot = p[3] === "b" ? "14:20" : p[3] === "c" ? L("Tomorrow 09:30", "غدًا 09:30", "कल 09:30") : "11:40";
        if (k === 0) return { at: "09:40", bot: [bub(esc(L("Hello! This is " + WA_CLINIC() + "'s booking assistant. I can book visits, tell you your queue status and send your documents securely.", "مرحبًا! هذا مساعد الحجز في " + WA_CLINIC() + ". أستطيع حجز الزيارات وإخبارك بدورك وإرسال مستنداتك بأمان.", "नमस्ते! यह " + WA_CLINIC() + " का बुकिंग सहायक है। मैं विज़िट बुक कर सकता हूँ, आपकी क़तार की स्थिति बता सकता हूँ और दस्तावेज़ सुरक्षित भेज सकता हूँ।")) + "\n\n" + esc(L("Choose your language:", "اختر لغتك:", "अपनी भाषा चुनें:")), "09:40")],
          replies: [["en", "English"], ["hi", "हिंदी"], ["ar", "العربية"]] };
        if (k === 1) return { at: "09:40", bot: [bub(esc(L("Welcome back, Kavya. Who is this visit for?", "أهلًا بعودتك يا كافيا. لمن هذه الزيارة؟", "फिर से स्वागत है, काव्या। यह विज़िट किसके लिए है?")), "09:40")],
          replies: [["me", L("Me (Kavya)", "أنا (كافيا)", "मैं (काव्या)")], ["kid", L("My son Aarav, 6", "ابني آراف، 6", "मेरा बेटा आरव, 6")], ["new", L("Someone else", "شخص آخر", "कोई और")]] };
        if (k === 2) return { at: "09:41", bot: [bub(esc(L("Which doctor? The wait right now is shown for each.", "أي طبيب؟ يظهر وقت الانتظار الحالي لكل طبيب.", "कौन से डॉक्टर? हर एक का अभी का इंतज़ार दिखाया गया है।")), "09:41", { btns: [["list", L("Choose a doctor", "اختر طبيبًا", "डॉक्टर चुनें"), 'aria-hidden="true"']] })],
          list: { title: L("Doctors today", "أطباء اليوم", "आज के डॉक्टर"), rows: [
            ["d1", NB.dname(d1), spec(d1.spec) + " · " + L("wait now ~12 min", "الانتظار الآن ~12 د", "अभी इंतज़ार ~12 मि")],
            ["d2", NB.dname(NB.doc("d2")), spec(NB.doc("d2").spec) + " · " + L("running 15 min late", "متأخر 15 د", "15 मि देरी से")],
            ["d3", NB.dname(NB.doc("d3")), spec(NB.doc("d3").spec) + " · " + L("wait now ~17 min", "الانتظار الآن ~17 د", "अभी इंतज़ार ~17 मि")],
            ["any", L("First available doctor", "أول طبيب متاح", "पहले उपलब्ध डॉक्टर"), L("Shortest wait", "أقصر انتظار", "सबसे कम इंतज़ार")]] } };
        if (k === 3) return { at: "09:41", bot: [bub(esc(L(NB.dname(pick) + " · today. Pick a time — expected wait at the clinic is shown.", NB.dname(pick) + " · اليوم. اختر وقتًا — يظهر الانتظار المتوقع في العيادة.", NB.dname(pick) + " · आज। समय चुनें — क्लिनिक पर अनुमानित इंतज़ार दिखाया गया है।")), "09:41")],
          replies: [["a", L("11:40 · earliest", "11:40 · الأقرب", "11:40 · सबसे जल्दी")], ["b", L("14:20 · least wait (~5 min)", "14:20 · أقل انتظار (~5 د)", "14:20 · सबसे कम इंतज़ार (~5 मि)")], ["c", L("Tomorrow 09:30", "غدًا 09:30", "कल 09:30")]] };
        if (k === 4) return { at: "09:41", bot: [bub('<span class="tt">' + t("Please confirm", "يرجى التأكيد", "कृपया पुष्टि करें") + "</span>" + esc(L("Doctor", "الطبيب", "डॉक्टर")) + ": " + esc(NB.dname(pick)) + "\n" + esc(L("When", "الموعد", "कब")) + ": " + '<span class="ltr">' + esc(slot) + "</span>\n" + esc(L("Where", "المكان", "कहाँ")) + ": " + esc(fname(0)) + "\n" + esc(L("Fee", "الرسوم", "फ़ीस")) + ": " + NB.money(700) + " · " + esc(L("pay at clinic or online", "الدفع في العيادة أو عبر الإنترنت", "क्लिनिक पर या ऑनलाइन")), "09:41")],
          replies: [["ok", L("Confirm", "تأكيد", "पक्का करें")], ["back", L("Change time", "تغيير الوقت", "समय बदलें")]] };
        return { at: "09:41", end: true, bot: [bub('<span class="tt">' + t("Booked ✓", "تم الحجز ✓", "बुकिंग पक्की ✓") + "</span>" + esc(L("Token", "رقم الدور", "टोकन")) + ' <b class="ltr">T-26</b> · <span class="ltr">' + esc(slot) + "</span>\n" + esc(L("We'll message you when it's time to leave home, so you don't wait at the clinic.", "سنرسل لك عندما يحين وقت الخروج من المنزل، حتى لا تنتظر في العيادة.", "घर से निकलने का समय होने पर हम संदेश भेजेंगे, ताकि क्लिनिक पर इंतज़ार न करना पड़े।")), "09:41", { btns: [["calendar", L("Add to calendar", "أضف إلى التقويم", "कैलेंडर में जोड़ें"), 'data-act="toast" data-arg="' + esc(L("Added to calendar", "أُضيف إلى التقويم", "कैलेंडर में जोड़ा")) + '"'], ["map", L("Directions", "الاتجاهات", "रास्ता"), 'data-act="toast" data-arg="' + esc(L("Opening maps…", "جارٍ فتح الخرائط…", "मैप्स खुल रहा है…")) + '"']] }),
          bub(esc(L("Want the doctor to know why you're coming? Use the private form — your answer isn't kept in this chat.", "تريد أن يعرف الطبيب سبب زيارتك؟ استخدم النموذج الخاص — إجابتك لا تُحفظ في هذه المحادثة.", "डॉक्टर को आने का कारण बताना है? निजी फ़ॉर्म का उपयोग करें — आपका जवाब इस चैट में नहीं रहता।")) + lk("nabd.link/f/K2Q9", L("Secure form · opens with a code", "نموذج آمن · يُفتح برمز", "सुरक्षित फ़ॉर्म · कोड से खुलता है")), "09:42", { btns: [["edit", L("Add reason privately", "أضف السبب بخصوصية", "कारण निजी तौर पर जोड़ें")]] })] };
      } },
    queue: { n: 2, label: ["Queue", "الطابور", "क़तार"], me: ["Kavya Reddy · booked", "كافيا ريدي · محجوز", "काव्या रेड्डी · बुक"],
      stage: function (p) {
        var k = p.length, d = NB.doc("d2"), D = NB.dname(d), room = d.room;
        var roomL = L(room, "الغرفة " + room.replace(/\D/g, ""), room.replace("Room", "कमरा"));
        if (k === 0) return { at: "09:30", bot: [bub('<span class="tt">' + t("Token T-24 confirmed", "تم تأكيد الرقم T-24", "टोकन T-24 पक्का") + "</span>" + esc(L(D + " · you are 6th in line. Expected time 10:35–10:50.", D + " · ترتيبك السادس. الوقت المتوقع 10:35–10:50.", D + " · आप लाइन में 6वें हैं। अनुमानित समय 10:35–10:50।")) + "\n" + esc(L("Stay home — we'll tell you when to leave.", "ابقَ في المنزل — سنخبرك متى تخرج.", "घर पर रहें — निकलने का समय हम बताएँगे।")), "09:30", { btns: [["activity", L("Track live", "تتبّع مباشر", "लाइव देखें")]] })],
          replies: [["ok", L("Got it", "تمام", "ठीक है")], ["late", L("I'll be a bit late", "سأتأخر قليلًا", "मैं थोड़ा देर से आऊँगा")]] };
        if (k === 1) return { at: "09:52", bot: (p[0] === "late" ? [bub(esc(L("No problem — your place is kept for up to 20 minutes after your time.", "لا مشكلة — يُحفظ دورك حتى 20 دقيقة بعد موعدك.", "कोई बात नहीं — आपकी जगह आपके समय के 20 मिनट बाद तक रखी जाएगी।")), "09:31")] : []).concat([bub('<span class="tt">' + t("Update on your visit", "تحديث على زيارتك", "आपकी विज़िट की जानकारी") + "</span>" + esc(L(D + " is running about 15 min late today. Sorry for the wait.", D + " متأخر نحو 15 دقيقة اليوم. نعتذر عن الانتظار.", D + " आज लगभग 15 मिनट देरी से चल रहे हैं। इंतज़ार के लिए खेद है।")) + "\n" + esc(L("New expected time 10:50–11:05 · you are 5th.", "الوقت المتوقع الجديد 10:50–11:05 · ترتيبك الخامس.", "नया अनुमानित समय 10:50–11:05 · आप 5वें हैं।")), "09:52")]),
          replies: [["wait", L("I'll wait", "سأنتظر", "मैं इंतज़ार करूँगा")], ["resch", L("Reschedule", "تغيير الموعد", "समय बदलें")], ["other", L("See another doctor", "طبيب آخر", "दूसरे डॉक्टर")]] };
        if (k === 2) return { at: "10:31", bot: (p[1] !== "wait" ? [bub(esc(L("Here are your options. Your place today stays until you choose.", "إليك الخيارات. يبقى دورك اليوم محفوظًا حتى تختار.", "ये रहे विकल्प। चुनने तक आज की आपकी जगह बनी रहेगी।")) + lk("nabd.link/r/T24", L("Pick a new time or doctor", "اختر وقتًا أو طبيبًا آخر", "नया समय या डॉक्टर चुनें")), "09:53"), '<div class="wa-day">' + t("Kept today's place", "أبقيت دور اليوم", "आज की जगह रखी") + "</div>"] : []).concat([bub('<span class="tt">' + t("Time to leave home", "حان وقت الخروج من المنزل", "घर से निकलने का समय") + "</span>" + esc(L("About 12 min by car. You are 3rd · expected 10:50–10:58.", "حوالي 12 دقيقة بالسيارة. ترتيبك الثالث · المتوقع 10:50–10:58.", "कार से लगभग 12 मिनट। आप तीसरे हैं · अनुमानित 10:50–10:58।")), "10:31", { btns: [["map", L("Directions", "الاتجاهات", "रास्ता"), 'data-act="toast" data-arg="' + esc(L("Opening maps…", "جارٍ فتح الخرائط…", "मैप्स खुल रहा है…")) + '"']] })]),
          replies: [["way", L("On my way", "في الطريق", "रास्ते में हूँ")]] };
        if (k === 3) return { at: "10:44", bot: [bub(esc(L("Tap “I'm here” when you reach the clinic — no need to queue at the desk.", "اضغط «وصلت» عند وصولك إلى العيادة — لا حاجة للوقوف عند الاستقبال.", "क्लिनिक पहुँचकर “मैं पहुँच गया” दबाएँ — डेस्क पर लाइन की ज़रूरत नहीं।")), "10:44")],
          replies: [["here", L("I'm here", "وصلت", "मैं पहुँच गया")]] };
        if (k === 4) return { at: "10:45", bot: [bub('<span class="tt">' + t("Checked in ✓", "تم تسجيل الحضور ✓", "चेक-इन हो गया ✓") + "</span>" + esc(L("You are 2nd. Wait wherever is comfortable — outside, in the car. We'll message you.", "ترتيبك الثاني. انتظر حيث ترتاح — في الخارج أو في السيارة. سنرسل لك.", "आप दूसरे हैं। जहाँ आराम हो वहाँ इंतज़ार करें — बाहर, कार में। हम संदेश भेजेंगे।")), "10:45")],
          replies: [["ok2", L("Okay", "حسنًا", "ठीक है")]] };
        return { at: "10:55", end: true, bot: [bub('<span class="tt">' + esc(L("You're next — please come to " + roomL, "أنت التالي — تفضّل إلى " + roomL, "अब आपकी बारी — कृपया " + roomL + " में आएँ")) + "</span>" + esc(L("Show token T-24 at the door. Your name is not called out.", "أظهر الرقم T-24 عند الباب. لن يُنادى باسمك.", "दरवाज़े पर टोकन T-24 दिखाएँ। आपका नाम नहीं पुकारा जाएगा।")), "10:55")] };
      } },
    after: { n: 3, label: ["After visit", "بعد الزيارة", "बाद में"], me: ["Kavya Reddy · visit done", "كافيا ريدي · انتهت الزيارة", "काव्या रेड्डी · विज़िट पूरी"],
      stage: function (p) {
        var k = p.length, D = NB.dname(NB.doc("d1"));
        if (k === 0) return { at: "11:58", bot: [bub(esc(L("Thank you for visiting " + WA_CLINIC() + " today.", "شكرًا لزيارتك " + WA_CLINIC() + " اليوم.", "आज " + WA_CLINIC() + " आने के लिए धन्यवाद।")) + "\n" + esc(L("Your prescription from " + D + " is ready. For your privacy, details are only shown on the secure page.", "وصفتك من " + D + " جاهزة. لخصوصيتك، تظهر التفاصيل فقط في الصفحة الآمنة.", D + " का आपका पर्चा तैयार है। आपकी निजता के लिए विवरण सिर्फ़ सुरक्षित पेज पर दिखते हैं।")) + lk("nabd.link/rx/88412", L("Opens with a one-time code", "يُفتح برمز لمرة واحدة", "एक बार के कोड से खुलता है")), "11:58", { btns: [["file", L("View prescription", "عرض الوصفة", "पर्चा देखें")]] })],
          replies: [["open", L("Open securely", "افتح بأمان", "सुरक्षित रूप से खोलें")]] };
        if (k === 1) return { at: "11:59", bot: [code("11:59"), bub(esc(L("Prescription opened on your phone ✓", "فُتحت الوصفة على هاتفك ✓", "आपके फ़ोन पर पर्चा खुला ✓")) + "\n" + esc(L("It will be ready at the clinic pharmacy (your choice). You can change the pharmacy on the page.", "ستكون جاهزة في صيدلية العيادة (اختيارك). يمكنك تغيير الصيدلية من الصفحة.", "यह क्लिनिक फ़ार्मेसी पर तैयार होगा (आपकी पसंद)। पेज पर फ़ार्मेसी बदल सकते हैं।")), "11:59", { btns: [["qr", L("Show QR", "عرض الرمز", "QR दिखाएँ"), 'data-ov="rxqr" data-arg="RX-88412"']] })],
          replies: [["pay", L("Pay my bill", "ادفع فاتورتي", "मेरा बिल भरें")]] };
        if (k === 2) return { at: "12:00", bot: [bub('<span class="tt">' + t("Your bill", "فاتورتك", "आपका बिल") + "</span>" + '<span class="ltr">INV-2026-04415</span> · ' + NB.money(700) + "\n" + esc(L("Pay securely by " + payWay() + ". The receipt comes here.", "ادفع بأمان عبر " + payWay() + ". يصلك الإيصال هنا.", payWay() + " से सुरक्षित भुगतान करें। रसीद यहीं आएगी।")), "12:00", { btns: [["wallet", L("Pay", "ادفع", "भुगतान करें")]] })],
          replies: [["paid", L("Pay with " + payWay(), "ادفع عبر " + payWay(), payWay() + " से भरें")]] };
        if (k === 3) return { at: "12:01", bot: [bub('<span class="tt">' + t("Payment received ✓", "تم استلام الدفع ✓", "भुगतान मिल गया ✓") + "</span>" + lk("nabd.link/rc/04415", L("Receipt (" + NB.mkt().tax + ")", "إيصال (" + NB.mkt().tax + ")", "रसीद (" + NB.mkt().tax + ")")), "12:01"), bub(esc(L("How was your visit today?", "كيف كانت زيارتك اليوم؟", "आज की विज़िट कैसी रही?")), "12:01")],
          replies: [["5", L("Great", "ممتازة", "बहुत अच्छी")], ["3", L("Okay", "مقبولة", "ठीक-ठाक")], ["1", L("Not good", "سيئة", "अच्छी नहीं")]] };
        if (k === 4) return { at: "12:02", bot: [bub(esc(p[3] === "1" ? L("Sorry to hear that. The clinic manager will call you today.", "نأسف لذلك. سيتصل بك مدير العيادة اليوم.", "यह सुनकर खेद है। क्लिनिक मैनेजर आज आपको फ़ोन करेंगे।") : L("Thank you! It helps the clinic.", "شكرًا لك! هذا يساعد العيادة.", "धन्यवाद! इससे क्लिनिक को मदद मिलती है।")), "12:02"),
          bub('<span class="tt">' + t("Follow-up", "المتابعة", "फ़ॉलो-अप") + "</span>" + esc(L(D + " would like to see you again in 4 weeks. Shall we book Sat 14 Nov, 10:20?", D + " يود رؤيتك بعد 4 أسابيع. هل نحجز السبت 14 نوفمبر 10:20؟", D + " 4 हफ़्ते बाद फिर मिलना चाहेंगे। शनि 14 नवंबर, 10:20 बुक करें?")), "12:02")],
          replies: [["fu", L("Book 14 Nov, 10:20", "احجز 14 نوفمبر 10:20", "14 नवंबर, 10:20 बुक करें")], ["later", L("Remind me later", "ذكّرني لاحقًا", "बाद में याद दिलाएँ")]] };
        return { at: "12:02", end: true, bot: [bub(esc(p[4] === "fu" ? L("Booked ✓ Sat 14 Nov, 10:20. We'll remind you the day before.", "تم الحجز ✓ السبت 14 نوفمبر 10:20. سنذكّرك قبلها بيوم.", "बुकिंग पक्की ✓ शनि 14 नवंबर, 10:20। एक दिन पहले याद दिलाएँगे।") : L("Okay — we'll remind you on 10 Nov with one-tap booking.", "حسنًا — سنذكّرك في 10 نوفمبر مع حجز بنقرة واحدة.", "ठीक है — 10 नवंबर को एक-टैप बुकिंग के साथ याद दिलाएँगे।")), "12:02")] };
      } },
    consent: { n: 4, label: ["Consent", "الموافقة", "सहमति"], me: ["Rakesh Menon · patient", "راكيش مينون · مريض", "राकेश मेनन · मरीज़"],
      stage: function (p) {
        var k = p.length, last = p[k - 1], D = NB.dname(NB.doc("d4"));
        var ask = function (at) { return { at: at, bot: [bub('<span class="tt">' + t("Record request", "طلب سجلات", "रिकॉर्ड अनुरोध") + "</span>" + esc(L(fshort(1) + " asks to see your records held by " + fshort(0) + ".", fshort(1) + " يطلب الاطلاع على سجلاتك لدى " + fshort(0) + ".", fshort(1) + " आपके " + fshort(0) + " में रखे रिकॉर्ड देखना चाहता है।")) + "\n" +
          esc(L("Asked by: ", "مقدّم الطلب: ", "किसने माँगा: ")) + esc(D) + "\n" + esc(L("Records: visits, prescriptions, lab results · last 12 months", "السجلات: الزيارات والوصفات ونتائج المختبر · آخر 12 شهرًا", "रिकॉर्ड: विज़िट, पर्चे, लैब रिपोर्ट · पिछले 12 महीने")) + "\n" + esc(L("Access for: 7 days", "مدة الوصول: 7 أيام", "एक्सेस: 7 दिन")) + "\n\n" + esc(L("Nothing is shared unless you approve.", "لن يُشارك شيء دون موافقتك.", "आपकी मंज़ूरी के बिना कुछ साझा नहीं होगा।")), at)],
          replies: [["rev", L("Review & approve", "مراجعة وموافقة", "देखें और मंज़ूर करें")], ["no", L("Decline", "رفض", "मना करें")], ["who", L("Who is asking?", "من يطلب؟", "कौन माँग रहा है?")]] }; };
        if (k === 0) return ask("14:02");
        if (last === "who") return { at: "14:03", bot: [bub(esc(L(D + " is a registered doctor (" + NB.doc("d4").regNo + ") at " + fname(1) + ", " + F(1).area + ". Nabd checked this request is real.", D + " طبيب مسجّل (" + NB.doc("d4").regNo + ") في " + fname(1) + ". تحقّقت نبض من صحة الطلب.", D + " " + fname(1) + ", " + F(1).area + " में पंजीकृत डॉक्टर (" + NB.doc("d4").regNo + ") हैं। Nabd ने जाँचा कि अनुरोध असली है।")) + "\n" + esc(L("We will never ask for bank details.", "لن نطلب أبدًا بيانات بنكية.", "हम कभी बैंक विवरण नहीं माँगेंगे।")), "14:03")],
          replies: [["rev", L("Review & approve", "مراجعة وموافقة", "देखें और मंज़ूर करें")], ["no", L("Decline", "رفض", "मना करें")]] };
        if (last === "rev") return { at: "14:04", bot: [bub(esc(L("Open the secure page to see the details and confirm it's you with the code below.", "افتح الصفحة الآمنة لرؤية التفاصيل وأكّد هويتك بالرمز أدناه.", "विवरण देखने के लिए सुरक्षित पेज खोलें और नीचे दिए कोड से पुष्टि करें कि यह आप हैं।")) + lk("nabd.link/c/2052", L("Approve with code · you can untick items", "وافق بالرمز · يمكنك إلغاء بعض العناصر", "कोड से मंज़ूर करें · कुछ चीज़ें हटा सकते हैं")), "14:04"), code("14:04")],
          replies: [["ok", L("Approve with code", "وافق بالرمز", "कोड से मंज़ूर करें")], ["no", L("Decline", "رفض", "मना करें")]] };
        if (last === "ok") return { at: "14:05", bot: [bub('<span class="tt">' + t("Approved ✓", "تمت الموافقة ✓", "मंज़ूर ✓") + "</span>" + esc(L("Your doctor at " + fshort(0) + " also checks the request before anything is shared. Access ends Sat 24 Oct, 14:05. Every view is recorded.", "يراجع طبيبك في " + fshort(0) + " الطلب أيضًا قبل مشاركة أي شيء. ينتهي الوصول السبت 24 أكتوبر 14:05. تُسجَّل كل مشاهدة.", fshort(0) + " में आपके डॉक्टर भी साझा करने से पहले जाँचेंगे। एक्सेस शनि 24 अक्टूबर, 14:05 को ख़त्म होगा। हर बार देखना दर्ज होगा।")), "14:05", { btns: [["history", L("See who viewed", "من اطّلع", "किसने देखा"), 'data-go="patient.privacy"']] })],
          replies: [["stop", L("Stop sharing", "أوقف المشاركة", "साझा करना रोकें")], ["fine", L("Thanks", "شكرًا", "धन्यवाद")]] };
        if (last === "stop") return { at: "14:20", end: true, bot: [bub('<span class="tt">' + t("Sharing stopped ✓", "أُوقفت المشاركة ✓", "साझा करना रोका गया ✓") + "</span>" + esc(L(fshort(1) + " can no longer see your records. They viewed them 0 times.", "لم يعد بإمكان " + fshort(1) + " رؤية سجلاتك. شاهدوها 0 مرة.", fshort(1) + " अब आपके रिकॉर्ड नहीं देख सकता। उन्होंने 0 बार देखा।")), "14:20")] };
        if (last === "fine") return { at: "14:05", end: true, bot: [bub(esc(L("You're welcome. Reply STOP SHARING any time to end access.", "على الرحب. أرسل «أوقف المشاركة» في أي وقت لإنهاء الوصول.", "आपका स्वागत है। एक्सेस ख़त्म करने के लिए कभी भी STOP SHARING भेजें।")), "14:05")] };
        return { at: "14:04", end: true, bot: [bub('<span class="tt">' + t("Declined ✓", "تم الرفض ✓", "अस्वीकृत ✓") + "</span>" + esc(L("Nothing was shared. " + fshort(1) + " has been told. You can still share later from the app.", "لم يُشارك شيء. أُبلغ " + fshort(1) + ". يمكنك المشاركة لاحقًا من التطبيق.", "कुछ साझा नहीं हुआ। " + fshort(1) + " को बता दिया गया। बाद में ऐप से साझा कर सकते हैं।")), "14:04")] };
      } },
    care: { n: 5, label: ["Caregiver", "للوالد", "देखभाल"], me: ["Faisal Ali · son in Dubai, for his father", "فيصل علي · الابن في دبي، لوالده", "फ़ैसल अली · दुबई में बेटा, पिता के लिए"], pre: ["Hi, I need an appointment for my father", "مرحبًا، أحتاج موعدًا لوالدي", "नमस्ते, पिताजी के लिए अपॉइंटमेंट चाहिए"],
      stage: function (p) {
        var k = p.length, dad = NB.pat("p8"), DAD = NB.pname(dad), d = p[1] ? NB.doc(p[1] === "any" ? "d2" : p[1]) : NB.doc("d2");
        var feeI = 600;
        if (k === 0) return { at: "07:12", bot: [bub(esc(L("Hello Faisal. You're a linked caregiver for " + DAD + " (father). Who is this visit for?", "مرحبًا فيصل. أنت مقدّم رعاية مرتبط لـ " + DAD + " (الوالد). لمن هذه الزيارة؟", "नमस्ते फ़ैसल। आप " + DAD + " (पिता) के जुड़े देखभालकर्ता हैं। यह विज़िट किसके लिए है?")), "07:12")],
          replies: [["dad", L("Abba · " + DAD + ", " + dad.age, "أبي · " + DAD + "، " + dad.age, "अब्बा · " + DAD + ", " + dad.age)], ["me", L("Myself", "لنفسي", "मेरे लिए")]] };
        if (k === 1) return { at: "07:12", bot: [bub(esc(L("Which doctor?", "أي طبيب؟", "कौन से डॉक्टर?")), "07:12", { btns: [["list", L("Choose a doctor", "اختر طبيبًا", "डॉक्टर चुनें"), 'aria-hidden="true"']] })],
          list: { title: L("Doctors", "الأطباء", "डॉक्टर"), rows: [["d2", NB.dname(NB.doc("d2")), L("His regular doctor · " + spec("General Medicine"), "طبيبه المعتاد · " + spec("General Medicine"), "उनके नियमित डॉक्टर · " + spec("General Medicine"))],
            ["d4", NB.dname(NB.doc("d4")), spec("Cardiology") + " · " + fshort(1)], ["any", L("First available", "أول متاح", "पहले उपलब्ध"), L("Shortest wait", "أقصر انتظار", "सबसे कम इंतज़ार")]] } };
        if (k === 2) return { at: "07:13", bot: [bub(esc(L("His doctor ordered a blood test for this visit. Do the test first so he doesn't sit fasting for hours:", "طلب طبيبه تحليل دم لهذه الزيارة. لنجرِ التحليل أولًا حتى لا يبقى صائمًا لساعات:", "उनके डॉक्टर ने इस विज़िट के लिए ब्लड टेस्ट लिखा है। पहले टेस्ट करवाएँ ताकि वे घंटों भूखे न बैठें:")) + "\n" + "<b>" + esc(L("Lab 08:00 → Doctor 08:40", "المختبر 08:00 ← الطبيب 08:40", "लैब 08:00 → डॉक्टर 08:40")) + "</b> · " + esc(L("Mon 19 Oct", "الاثنين 19 أكتوبر", "सोम 19 अक्टूबर")), "07:13")],
          replies: [["seq", L("Book lab 08:00 → doctor 08:40", "احجز المختبر 08:00 ← الطبيب 08:40", "लैब 08:00 → डॉक्टर 08:40 बुक करें")], ["other", L("Another day", "يوم آخر", "दूसरा दिन")]] };
        if (k === 3) return { at: "07:13", bot: [bub('<span class="tt">' + esc(L("Booked for " + DAD + " ✓", "تم الحجز لـ " + DAD + " ✓", DAD + " के लिए बुकिंग पक्की ✓")) + "</span>" + esc(L(p[2] === "other" ? "Tue 20 Oct" : "Mon 19 Oct", p[2] === "other" ? "الثلاثاء 20 أكتوبر" : "الاثنين 19 أكتوبر", p[2] === "other" ? "मंगल 20 अक्टूबर" : "सोम 19 अक्टूबर")) + " · " + esc(L("Lab 08:00 → ", "المختبر 08:00 ← ", "लैब 08:00 → ")) + esc(NB.dname(d)) + " 08:40\n" + esc(L("We've also sent him a reminder as a Malayalam voice message.", "أرسلنا له أيضًا تذكيرًا برسالة صوتية بالمالايالامية.", "उन्हें मलयालम वॉइस मैसेज में रिमाइंडर भी भेजा है।")), "07:13"),
          bub(esc(L("Fee", "الرسوم", "फ़ीस")) + " " + NB.money(feeI) + ". " + esc(L("You can pay now from the UAE with a card.", "يمكنك الدفع الآن من الإمارات بالبطاقة.", "आप अभी UAE से कार्ड द्वारा भुगतान कर सकते हैं।")), "07:14", { btns: [["wallet", L("Pay", "ادفع", "भुगतान करें")]] })],
          replies: [["pay", L("Pay now", "ادفع الآن", "अभी भरें")], ["desk", L("He'll pay at the clinic", "سيدفع في العيادة", "वे क्लिनिक पर भरेंगे")]] };
        return { at: "07:15", end: true, bot: [bub(esc(p[3] === "pay" ? L("Paid ✓ Receipt sent to you.", "تم الدفع ✓ أُرسل لك الإيصال.", "भुगतान हो गया ✓ रसीद आपको भेजी।") : L("Okay — he can pay at the counter.", "حسنًا — يمكنه الدفع عند المكتب.", "ठीक है — वे काउंटर पर भर सकते हैं।")) + "\n" + esc(L("You'll get a message when Abba checks in, when his prescription is ready and when results are released — as secure links only.", "ستصلك رسالة عند وصول أبيك وعند جاهزية وصفته وصدور النتائج — كروابط آمنة فقط.", "अब्बा के चेक-इन, पर्चा तैयार होने और रिपोर्ट जारी होने पर आपको संदेश मिलेगा — सिर्फ़ सुरक्षित लिंक के रूप में।")), "07:15"),
          bub(esc(L("Your caregiver access: appointments, prescriptions, lab results, bills · until 31 Mar 2027. Abba chose this.", "صلاحياتك كمقدّم رعاية: المواعيد والوصفات والنتائج والفواتير · حتى 31 مارس 2027. والدك اختار ذلك.", "आपका देखभालकर्ता एक्सेस: अपॉइंटमेंट, पर्चे, लैब रिपोर्ट, बिल · 31 मार्च 2027 तक। यह अब्बा ने चुना है।")), "07:15")] };
      } }
  };
  var SCN_ORDER = ["book", "queue", "after", "consent", "care"];

  NB.screen("whatsapp.chat", function (c) {
    var cur = NB.v("waScn", "book"), sc = SCN[cur] || SCN.book, ch = NB.v("waCh", []);
    var h = '<div class="pt-wa-shell">';
    h += '<div class="pt-scn" role="tablist" aria-label="' + t("Scenarios", "السيناريوهات", "परिदृश्य") + '">' + SCN_ORDER.map(function (k) {
      var s = SCN[k];
      return '<button role="tab" class="' + (k === cur ? "on" : "") + '" aria-selected="' + (k === cur) + '" data-act="ptWaScn" data-arg="' + k + '">' + esc(L3(s.label)) + "</button>";
    }).join("") + "</div>";
    // Build conversation
    var body = '<div class="wa-day">' + t("Today", "اليوم", "आज") + "</div>", done = false, lastAt = "09:40";
    if (sc.pre) body += '<div class="bub out pt-b">' + esc(L3(sc.pre)) + '<span class="pt-ts">' + (cur === "care" ? "07:11" : "09:39") + '<span class="pt-tick">✓✓</span></span></div>';
    for (var k = 0; k <= ch.length; k++) {
      var st = sc.stage(ch.slice(0, k));
      if (!st) break;
      body += st.bot.join("");
      lastAt = st.at;
      if (k < ch.length) {
        var opts = (st.replies || []).concat(st.list ? st.list.rows : []);
        var picked = opts.find(function (r) { return r[0] === ch[k]; });
        body += '<div class="bub out pt-b">' + esc(picked ? picked[1] : ch[k]) + '<span class="pt-ts">' + st.at + '<span class="pt-tick">✓✓</span></span></div>';
      } else if (st.end) { done = true; }
      else if (st.list) {
        body += '<div class="pt-wl"><div class="hd">' + esc(st.list.title) + "</div>" + st.list.rows.map(function (r) {
          return '<button data-act="ptWa" data-arg="' + esc(r[0]) + '"><div class="t"><b>' + esc(r[1]) + "</b><small>" + esc(r[2]) + '</small></div><span class="rd"></span></button>';
        }).join("") + "</div>";
      } else if (st.replies) {
        body += '<div class="pt-wrb">' + st.replies.map(function (r) { return '<button data-act="ptWa" data-arg="' + esc(r[0]) + '">' + ui.icon("arrowright", "xs flip") + esc(r[1]) + "</button>"; }).join("") + "</div>";
      }
    }
    if (done) body += '<div class="pt-wa-end">' + ui.icon("lock", "xs") + " " + t("No health details were sent in this chat — only links that open with a code.", "لم تُرسل أي تفاصيل صحية في هذه المحادثة — روابط تُفتح برمز فقط.", "इस चैट में कोई स्वास्थ्य जानकारी नहीं भेजी गई — सिर्फ़ कोड से खुलने वाले लिंक।") + "</div>";
    // Tap counter / context line
    var taps = ch.length + (done ? 0 : 0), secs = 6 + taps * 8;
    var meta = cur === "book" ? (done ? '<span class="fl okc">' + ui.icon("checkcircle", "xs") + " <b>" + esc(L("Booked in " + taps + " taps · " + secs + " s", "حُجز في " + taps + " نقرات · " + secs + " ث", taps + " टैप में बुक · " + secs + " सेकंड")) + "</b> · " + t("target under 60 s", "الهدف أقل من 60 ث", "लक्ष्य 60 सेकंड से कम") + "</span>"
      : '<span class="fl"><b>' + esc(L("Tap " + taps + " · 0:" + (secs < 10 ? "0" : "") + secs, "نقرة " + taps + " · 0:" + (secs < 10 ? "0" : "") + secs, "टैप " + taps + " · 0:" + (secs < 10 ? "0" : "") + secs)) + "</b> · " + t("buttons only, no typing", "أزرار فقط بلا كتابة", "सिर्फ़ बटन, टाइपिंग नहीं") + "</span>")
      : '<span class="fl">' + ui.icon("user", "xs") + " " + esc(L3(sc.me)) + "</span>";
    h += '<div class="pt-scm">' + meta + '<button data-act="ptWaScn" data-arg="' + cur + '">' + ui.icon("refresh", "xs") + t("Restart", "إعادة", "फिर से") + "</button></div>";
    h += '<div class="wa">';
    h += '<div class="wa-h pt-wa-h"><button class="hb" title="' + t("Back", "رجوع", "वापस") + '">' + ui.icon("chevleft", "sm flip") + "</button>" + '<span class="av">' + ui.icon("logo", "sm") + '</span><div class="fl" style="min-width:0"><b>' + esc(WA_CLINIC()) + '<span class="pt-vf" title="' + t("Verified business", "نشاط تجاري موثّق", "सत्यापित व्यवसाय") + '">' + ui.icon("check") + "</span></b><small>" + t("Official business account", "حساب أعمال رسمي", "आधिकारिक बिज़नेस अकाउंट") + "</small></div>" +
      '<button class="hb" title="' + t("Call", "اتصال", "कॉल") + '">' + ui.icon("phone", "sm") + '</button><button class="hb" title="' + t("More", "المزيد", "अधिक") + '">' + ui.icon("dots", "sm") + "</button></div>";
    h += '<div class="wa-b pt-wa-b" id="pt-wa-b">' + body + "</div>";
    h += '<div class="wa-in pt-wa-in">' + ui.icon("plus", "sm faint") + "<span>" + t("Message", "رسالة", "संदेश") + '</span><span class="ib">' + ui.icon("mic", "sm") + "</span></div>";
    h += "</div></div>";
    return h;
  });
  NB.act("ptWaScn", function (a) { NB.setUI({ waScn: a, waCh: [] }); });
  NB.act("ptWa", function (a) {
    var ch = NB.v("waCh", []).slice(), cur = NB.v("waScn", "book");
    if (a === "back") { ch.pop(); NB.setUI({ waCh: ch }); return; }
    ch.push(a);
    NB.state.ui.waCh = ch;
    if (cur === "book" && ch.length === 1 && /^(en|hi|ar)$/.test(a)) { NB.set({ lang: a }); return; }
    NB.render();
  });
  // Keep the chat scrolled to the latest message (chain any earlier hook).
  var prevAfter = NB.afterRender;
  NB.afterRender = function () {
    if (typeof prevAfter === "function") prevAfter();
    var b = document.getElementById("pt-wa-b"); if (b) b.scrollTop = b.scrollHeight;
  };

  /* ---------------- Nabd Connect (public web) ---------------- */
  function cxData() {
    var g = NB.gcc(), fs = NB.facilities();
    var other = g ? [["City Care Clinic", "Deira, Dubai"], ["Marina Family Clinic", "Dubai Marina"]] : [["Sanjeevani Clinic", "HSR Layout, Bengaluru"], ["Arogya Family Clinic", "BTM Layout, Bengaluru"]];
    return [
      { id: 0, d: "d2", name: NB.dname(NB.doc("d2")), spec: "General Medicine", clinic: fs[0].name, area: fs[0].area, km: 3.2, wait: [22, 30], next: "10:40", fee: 600, rating: 4.7, rev: 1284, langs: ["EN", "HI", "KN"], ins: ["Star Health", "PM-JAY"], today: true, gender: "m", live: true, delay: 15 },
      { id: 1, d: "d1", name: NB.dname(NB.doc("d1")), spec: "Dermatology", clinic: fs[0].name, area: fs[0].area, km: 3.2, wait: [9, 14], next: "11:40", fee: 700, rating: 4.8, rev: 932, langs: ["EN", "TA", "HI"], ins: ["Star Health"], today: true, gender: "f", live: true },
      { id: 2, d: "d3", name: NB.dname(NB.doc("d3")), spec: "Paediatrics", clinic: fs[0].name, area: fs[0].area, km: 3.2, wait: [14, 20], next: "11:00", fee: 650, rating: 4.9, rev: 2210, langs: ["EN", "KN", "HI"], ins: ["Star Health", "HDFC Ergo"], today: true, gender: "f", live: true },
      { id: 3, d: "d5", name: NB.dname(NB.doc("d5")), spec: "Obstetrics & Gynaecology", clinic: fs[0].name, area: fs[0].area, km: 3.2, wait: [15, 22], next: "12:40", fee: 800, rating: 4.8, rev: 640, langs: ["EN", "HI", "AR"], ins: ["HDFC Ergo", "Bupa Arabia"], today: true, gender: "f", live: true },
      { id: 4, d: "d4", name: NB.dname(NB.doc("d4")), spec: "Cardiology", clinic: fs[1].name, area: fs[1].area, km: 9.8, wait: [40, 55], next: L("Tue 11:00", "الثلاثاء 11:00", "मंगल 11:00"), fee: 1200, rating: 4.6, rev: 410, langs: ["EN", "AR"], ins: ["Star Health", "Bupa Arabia", "PM-JAY"], today: false, gender: "m", live: true },
      { id: 5, d: "d1", name: NB.dname(NB.doc("d1")), spec: "Dermatology", clinic: fs[2].name, area: fs[2].area, km: 5.1, wait: [5, 10], next: "17:30", fee: 800, rating: 4.9, rev: 388, langs: ["EN", "TA"], ins: [], today: true, gender: "f", live: true },
      { id: 6, d: null, name: g ? "Dr. Omar Haddad" : "Dr. Suresh Gowda", spec: "General Medicine", clinic: other[0][0], area: other[0][1], km: 2.1, wait: null, next: "12:15", fee: 500, rating: 4.4, rev: 205, langs: g ? ["AR", "EN"] : ["KN", "EN"], ins: ["PM-JAY"], today: true, gender: "m", live: false },
      { id: 7, d: null, name: g ? "Dr. Layla Nasser" : "Dr. Revathi Iyer", spec: "Paediatrics", clinic: other[1][0], area: other[1][1], km: 4.4, wait: null, next: L("Tomorrow 09:00", "غدًا 09:00", "कल 09:00"), fee: 550, rating: 4.5, rev: 162, langs: g ? ["AR", "EN"] : ["TA", "EN"], ins: ["HDFC Ergo"], today: false, gender: "f", live: false }
    ];
  }
  var LANGN = { EN: "English", HI: "हिंदी", KN: "ಕನ್ನಡ", TA: "தமிழ்", AR: "العربية" };
  function cxNav() {
    return '<div class="pt-cxn"><div class="logo"><span class="mark">' + ui.icon("logo") + '</span><div><b>nabd connect</b><small>' + t("Find a doctor · see the real wait", "ابحث عن طبيب · شاهد الانتظار الحقيقي", "डॉक्टर खोजें · असली इंतज़ार देखें") + '</small></div></div><div class="sp"></div>' +
      ui.btn(L("For clinics", "للعيادات", "क्लिनिक के लिए"), { cls: "ghost hide-s", act: "toast", arg: L("Clinics control their own listing", "العيادات تتحكم في صفحاتها", "क्लिनिक अपनी लिस्टिंग ख़ुद नियंत्रित करते हैं") }) +
      ui.btn(L("My bookings", "حجوزاتي", "मेरी बुकिंग"), { cls: "sm", go: "patient.home", icon: "user" }) + "</div>";
  }
  function waitBlock(r) {
    if (!r.live) return '<span class="small faint">' + t("Live wait not shared", "الانتظار المباشر غير متاح", "लाइव इंतज़ार साझा नहीं") + "</span>";
    return '<div><span class="small faint">' + t("Wait now", "الانتظار الآن", "अभी इंतज़ार") + '</span><div class="pt-live-w"><span class="pulse"></span><span class="ltr">' + r.wait[0] + "–" + r.wait[1] + "</span><small class=\"small faint\" style=\"font-weight:500\">" + t("min", "د", "मि") + "</small></div></div>";
  }
  function resultCard(r) {
    var h = '<div class="card pt-rc">' + ui.av(r.name) + '<div class="stack s4" style="min-width:0"><h3>' + esc(r.name) + '</h3><span class="meta">' + esc(spec(r.spec)) + "</span>" +
      '<span class="meta"><a data-act="ptCxClinic" data-arg="' + r.id + '" href="javascript:void 0">' + esc(r.clinic) + "</a> · " + esc(r.area) + " · " + nm(r.km + " km") + "</span>" +
      '<div class="chips" style="margin-top:4px">' + '<span class="pt-stars">★ <span class="num">' + r.rating + '</span> <span class="faint">(' + nm(r.rev.toLocaleString("en-IN")) + ")</span></span>" +
      r.langs.map(function (l) { return '<span class="pill mute" style="text-transform:none;letter-spacing:0">' + esc(LANGN[l] || l) + "</span>"; }).join("") +
      (r.delay ? ui.pill("warn", L("+" + r.delay + " min late", "متأخر " + r.delay + " د", r.delay + " मि देरी"), "clockh") : "") + "</div></div>" +
      '<div class="pt-rside">' + waitBlock(r) + '<div><span class="small faint">' + t("Next slot", "أقرب موعد", "अगला स्लॉट") + '</span><div style="font-weight:600" class="ltr">' + esc(r.next) + '</div></div><div><span class="small faint">' + t("Fee", "الرسوم", "फ़ीस") + "</span><div style=\"font-weight:600\">" + NB.money(r.fee) + "</div></div></div>" +
      '<div class="acts">' + '<button class="btn pt-wab" data-act="ptCxWa" data-arg="' + r.id + '">' + ui.icon("chat", "sm") + t("Book on WhatsApp", "احجز عبر واتساب", "व्हाट्सऐप पर बुक करें") + "</button>" +
      ui.btn(L("Book here", "احجز هنا", "यहाँ बुक करें"), { cls: "pri", ov: "cxbook", arg: r.id, icon: "calendar" }) + ui.btn(L("Clinic", "العيادة", "क्लिनिक"), { cls: "ghost", act: "ptCxClinic", arg: r.id, icon: "building" }) + "</div></div>";
    return h;
  }
  NB.screen("connect.search", function (c) {
    var sort = NB.v("cxSort", "wait"), sp = NB.v("cxSpec", "all"), today = NB.v("cxToday", false), lang = NB.v("cxLang", "all"), ins = NB.v("cxIns", "all"), fem = NB.v("cxFem", false);
    var rows = cxData().filter(function (r) {
      return (sp === "all" || r.spec === sp) && (!today || r.today) && (lang === "all" || r.langs.indexOf(lang) >= 0) && (ins === "all" || r.ins.indexOf(ins) >= 0) && (!fem || r.gender === "f");
    });
    var key = { wait: function (r) { return r.wait ? r.wait[0] : 999; }, dist: function (r) { return r.km; }, rating: function (r) { return -r.rating; }, fee: function (r) { return r.fee; } }[sort];
    rows.sort(function (a, b) { return key(a) - key(b); });
    var specs = [["all", L("All", "الكل", "सभी")]].concat(PA.needs.map(function (n) { return [n.spec, spec(n.spec)]; }));
    var h = '<div class="pt-cx">' + cxNav() + '<div class="pt-cxw">';
    h += '<div class="pt-hero"><h1>' + t("See a doctor without the waiting-room guesswork", "زُر الطبيب دون تخمين وقت الانتظار", "वेटिंग रूम में बिना अंदाज़े के डॉक्टर से मिलें") + "</h1><p>" + t("Live waits come straight from each clinic's queue. Book here or on WhatsApp — it goes into the clinic's real queue.", "أوقات الانتظار المباشرة تأتي من طابور كل عيادة. احجز هنا أو عبر واتساب — يدخل حجزك طابور العيادة الحقيقي.", "लाइव इंतज़ार सीधे हर क्लिनिक की क़तार से आता है। यहाँ या व्हाट्सऐप पर बुक करें — बुकिंग क्लिनिक की असली क़तार में जाती है।") + "</p>" +
      '<div class="pt-sbar">' + ui.field(L("Specialty, symptom or doctor", "التخصص أو العَرَض أو الطبيب", "विशेषज्ञता, लक्षण या डॉक्टर"), "", { ph: L("e.g. fever, skin rash, child's cough", "مثال: حمى، طفح جلدي، سعال طفل", "जैसे बुखार, त्वचा पर दाने, बच्चे की खाँसी"), id: "cxq" }) +
      ui.field(L("Area", "المنطقة", "इलाका"), NB.gcc() ? "Al Barsha, Dubai" : "Koramangala, Bengaluru", { id: "cxa" }) + ui.btn(L("Search", "بحث", "खोजें"), { cls: "pri lg", act: "toast", arg: L(rows.length + " doctors near you", rows.length + " أطباء بالقرب منك", "आपके पास " + rows.length + " डॉक्टर"), icon: "search" }) + "</div>" +
      '<div class="chips">' + [["fever", L("Fever", "حمى", "बुखार"), "General Medicine"], ["skin", L("Skin rash", "طفح جلدي", "त्वचा पर दाने"), "Dermatology"], ["kid", L("Child's cough", "سعال طفل", "बच्चे की खाँसी"), "Paediatrics"], ["preg", L("Pregnancy check", "فحص حمل", "गर्भावस्था जाँच"), "Obstetrics & Gynaecology"], ["chest", L("Chest pain", "ألم الصدر", "सीने में दर्द"), "Cardiology"]].map(function (s) {
        return '<button class="chip ' + (sp === s[2] ? "on" : "") + '" data-set="cxSpec" data-val="' + esc(s[2]) + '">' + esc(s[1]) + "</button>";
      }).join("") + "</div></div>";
    h += '<div class="pt-cxg"><aside class="pt-flt">' +
      "<div><div class=\"fh\">" + t("Specialty", "التخصص", "विशेषज्ञता") + '</div><div class="chips">' + specs.map(function (s) { return '<button class="chip ' + (sp === s[0] ? "on" : "") + '" data-set="cxSpec" data-val="' + esc(s[0]) + '">' + esc(s[1]) + "</button>"; }).join("") + "</div></div>" +
      '<div><div class="fh">' + t("Availability", "التوفر", "उपलब्धता") + '</div><div class="chips"><button class="chip ' + (today ? "on" : "") + '" data-toggle="cxToday">' + ui.icon("sun", "sm") + t("Available today", "متاح اليوم", "आज उपलब्ध") + '</button><button class="chip ' + (fem ? "on" : "") + '" data-toggle="cxFem">' + ui.icon("user", "sm") + t("Female doctor", "طبيبة", "महिला डॉक्टर") + "</button></div></div>" +
      '<div><div class="fh">' + t("Language", "اللغة", "भाषा") + '</div><div class="chips">' + ["all", "EN", "HI", "KN", "TA", "AR"].map(function (l) { return '<button class="chip ' + (lang === l ? "on" : "") + '" data-set="cxLang" data-val="' + l + '">' + esc(l === "all" ? L("Any", "أي لغة", "कोई भी") : LANGN[l]) + "</button>"; }).join("") + "</div></div>" +
      '<div><div class="fh">' + t("Insurance accepted", "التأمين المقبول", "स्वीकृत बीमा") + '</div><div class="chips">' + ["all"].concat(NB.gcc() ? ["Bupa Arabia"] : ["Star Health", "HDFC Ergo", "PM-JAY"]).map(function (i) { return '<button class="chip ' + (ins === i ? "on" : "") + '" data-set="cxIns" data-val="' + esc(i) + '">' + esc(i === "all" ? L("Any / self-pay", "أي / دفع ذاتي", "कोई भी / स्वयं भुगतान") : i) + "</button>"; }).join("") + "</div></div>" +
      "</aside><div class=\"stack s16\">" +
      '<div class="row sb"><b style="font-size:15px">' + esc(L(rows.length + " doctors near you", rows.length + " أطباء بالقرب منك", "आपके पास " + rows.length + " डॉक्टर")) + "</b>" +
      '<div class="row nw"><span class="small faint">' + t("Sort", "ترتيب", "क्रम") + "</span>" + ui.seg("cxSort", [["wait", L("Live wait", "الانتظار المباشر", "लाइव इंतज़ार")], ["dist", L("Distance", "المسافة", "दूरी")], ["rating", L("Rating", "التقييم", "रेटिंग")], ["fee", L("Fee", "الرسوم", "फ़ीस")]], "wait") + "</div></div>" +
      (rows.length ? rows.map(resultCard).join("") : '<div class="card empty">' + t("No doctor matches these filters. Try removing one.", "لا يوجد طبيب يطابق هذه الفلاتر. جرّب إزالة أحدها.", "इन फ़िल्टर से कोई डॉक्टर नहीं मिला। एक फ़िल्टर हटाकर देखें।") + "</div>") +
      ui.note("info", t("Clinics decide what appears here. Waits are estimates shown as a range and update every few minutes. We never show ads from other clinics on a clinic's page.", "تقرر العيادات ما يظهر هنا. أوقات الانتظار تقديرية وتُعرض كنطاق وتتحدّث كل بضع دقائق. لا نعرض إعلانات عيادات أخرى في صفحة أي عيادة.", "यहाँ क्या दिखे, यह क्लिनिक तय करते हैं। इंतज़ार अनुमान है, दायरे में दिखता है और हर कुछ मिनट में अपडेट होता है। किसी क्लिनिक के पेज पर दूसरे क्लिनिक के विज्ञापन नहीं दिखते।"), "info") +
      "</div></div></div></div>";
    return h;
  });
  NB.act("ptCxClinic", function (a) {
    var r = cxData()[+a] || cxData()[0];
    NB.state.ui.cxClinic = r.live ? (r.clinic === NB.facilities()[1].name ? 1 : r.clinic === NB.facilities()[2].name ? 2 : 0) : 0;
    NB.go("connect", "clinic");
  });
  NB.act("ptCxWa", function () { Object.assign(NB.state.ui, { waScn: "book", waCh: [] }); NB.go("whatsapp", "chat"); });

  NB.overlay("cxbook", function (id) {
    var r = cxData()[+id] || cxData()[0], s = NB.v("cxSlot", r.next);
    var slots = [r.next, "12:20", "14:40", "16:00"];
    return { title: t("Book a visit", "احجز زيارة", "विज़िट बुक करें"), body:
      it(ui.av(r.name, "lg"), esc(r.name), esc(spec(r.spec)) + " · " + esc(r.clinic), "") +
      '<span class="small muted">' + t("Choose a time", "اختر وقتًا", "समय चुनें") + '</span><div class="pt-slots">' + slots.map(function (x, i) {
        return '<button class="pt-slot ' + (s === x ? "on" : i === 0 ? "hint" : "") + '" data-set="cxSlot" data-val="' + esc(x) + '">' + (i === 0 ? '<span class="h">' + t("Earliest", "الأقرب", "सबसे जल्दी") + "</span>" : i === 2 ? '<span class="h">' + t("Least wait", "أقل انتظار", "सबसे कम इंतज़ार") + "</span>" : "") + '<b class="ltr">' + esc(x) + "</b><small>~" + [12, 15, 4, 8][i] + " " + t("min wait", "د انتظار", "मि इंतज़ार") + "</small></button>";
      }).join("") + "</div>" +
      '<span class="small muted">' + t("For", "لمن", "किसके लिए") + "</span>" + ui.seg("cxFor", [["me", L("Myself", "لنفسي", "मेरे लिए")], ["fam", L("Family member", "فرد من العائلة", "परिवार का सदस्य")]], "me") +
      '<div class="fgrid">' + ui.field(L("Your name", "اسمك", "आपका नाम"), "", { ph: L("As on your ID", "كما في الهوية", "आईडी के अनुसार") }) + ui.field(L("Mobile (WhatsApp)", "الجوال (واتساب)", "मोबाइल (व्हाट्सऐप)"), NB.mkt().phone + " ", { type: "tel" }) + "</div>" +
      ui.note("info", t("We'll send a one-time code to confirm your number. Your token and live updates come on WhatsApp.", "سنرسل رمزًا لمرة واحدة لتأكيد رقمك. يصلك رقم الدور والتحديثات على واتساب.", "नंबर की पुष्टि के लिए एक बार का कोड भेजेंगे। टोकन और लाइव अपडेट व्हाट्सऐप पर आएँगे।"), "lock"),
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द"), { act: "close", cls: "ghost" }) + ui.btn(L("Send code & book", "أرسل الرمز واحجز", "कोड भेजें और बुक करें"), { cls: "pri", act: "toast", arg: L("Code sent · booking held for 5 minutes", "أُرسل الرمز · الحجز محفوظ 5 دقائق", "कोड भेजा · बुकिंग 5 मिनट के लिए रोकी गई"), icon: "send" }) };
  });

  NB.screen("connect.clinic", function (c) {
    var fi = NB.v("cxClinic", 0), f = F(fi), rows = cxData().filter(function (r) { return r.live && r.clinic === f.name; });
    var qd = { d1: ["T-17", 2, "9–14"], d2: ["T-15", 3, "22–30"], d3: ["T-16", 2, "14–20"], d4: ["—", 1, "40–55"], d5: ["T-11", 1, "15–22"] };
    var h = '<div class="pt-cx">' + cxNav() + '<div class="pt-cxw">';
    h += '<div><button class="btn ghost sm" data-go="connect.search">' + ui.icon("chevleft", "sm flip") + t("All results", "كل النتائج", "सभी परिणाम") + "</button></div>";
    h += '<div class="pt-hero"><div class="row nw" style="align-items:flex-start"><span class="av xl">' + ui.icon("building", "lg") + '</span><div class="fl"><h1 style="font-size:24px;line-height:31px">' + esc(f.name) + '</h1><p>' + esc(f.area) + " · " + esc(NB.FACILITY_TYPES[f.type].name) + "</p>" +
      '<div class="chips" style="margin-top:8px">' + ui.pill("ok", L("Open now · until 21:00", "مفتوح الآن · حتى 21:00", "अभी खुला · 21:00 तक")) + ui.pill("acc", L("Live queue on Nabd", "طابور مباشر على نبض", "Nabd पर लाइव क़तार"), "activity") + '<span class="pt-stars">★ <span class="num">4.7</span> <span class="faint">(' + nm("3,180") + ")</span></span></div></div></div>" +
      '<div class="btns"><button class="btn pt-wab lg" data-act="ptCxWa">' + ui.icon("chat", "sm") + t("Book on WhatsApp", "احجز عبر واتساب", "व्हाट्सऐप पर बुक करें") + "</button>" + ui.btn(L("Book here", "احجز هنا", "यहाँ बुक करें"), { cls: "pri lg", ov: "cxbook", arg: rows[0] ? rows[0].id : 0, icon: "calendar" }) + ui.btn(L("Directions", "الاتجاهات", "रास्ता"), { cls: "lg", act: "toast", arg: L("Opening maps…", "جارٍ فتح الخرائط…", "मैप्स खुल रहा है…"), icon: "map" }) + "</div></div>";
    h += ui.card(t("Live queue right now", "الطابور الآن", "अभी लाइव क़तार"), '<div class="pt-qs">' + rows.map(function (r) {
      var q = qd[r.d] || ["—", 0, "—"], d = NB.doc(r.d);
      return '<div class="pt-qd"><div class="row nw">' + ui.av(d.name) + '<div class="fl"><b style="display:block;font-size:13.5px">' + esc(r.name) + '</b><span class="small faint">' + esc(spec(r.spec)) + "</span></div></div>" +
        '<div class="row sb"><span class="small muted">' + t("Now seeing", "يُعاين الآن", "अभी देख रहे") + ' <span class="tok">' + q[0] + "</span></span>" + (d.status === "break" ? ui.pill("perm", L("On break", "استراحة", "ब्रेक पर")) : d.delay ? ui.pill("warn", L("+" + d.delay + " min", "+" + d.delay + " د", "+" + d.delay + " मि"), "clockh") : ui.pill("ok", L("On time", "في الموعد", "समय पर"))) + "</div>" +
        '<div class="row sb"><span class="small muted">' + esc(L(q[1] + " waiting", q[1] + " ينتظرون", q[1] + " इंतज़ार में")) + "</span><b class=\"num ltr\">" + q[2] + " " + t("min", "د", "मि") + "</b></div></div>";
    }).join("") + "</div>", { icon: "activity", right: '<span class="pill sync"><span class="pulse"></span>' + t("Live", "مباشر", "लाइव") + "</span>" });
    h += '<div class="grid g2">' + ui.card(t("Doctors", "الأطباء", "डॉक्टर"), '<div class="lst">' + rows.map(function (r) {
      return ui.li(ui.av(NB.doc(r.d).name), esc(r.name), esc(spec(r.spec)) + " · " + esc(L("next ", "التالي ", "अगला ")) + '<span class="ltr">' + esc(r.next) + "</span> · " + NB.money(r.fee), ui.btn(L("Book", "احجز", "बुक"), { cls: "sm pri", ov: "cxbook", arg: r.id }));
    }).join("") + "</div>", { icon: "steth" }) +
      ui.card(t("Timings", "المواعيد", "समय"), '<dl class="pt-tim">' + [[L("Mon – Fri", "الاثنين – الجمعة", "सोम – शुक्र"), "08:00–13:00 · 17:00–21:00"], [L("Saturday", "السبت", "शनिवार"), "08:00–14:00"], [L("Sunday", "الأحد", "रविवार"), "09:00–12:00"]].map(function (x) { return "<dt>" + esc(x[0]) + '</dt><dd class="num ltr">' + x[1] + "</dd>"; }).join("") + "</dl>" +
        '<hr class="hr" style="margin:14px 0">' + '<div class="eyebrow" style="margin-bottom:10px">' + t("Services", "الخدمات", "सेवाएँ") + '</div><div class="chips">' + [L("General OPD", "العيادات العامة", "जनरल ओपीडी"), L("Child vaccines", "تطعيمات الأطفال", "बच्चों के टीके"), L("Skin & laser", "الجلدية والليزر", "त्वचा और लेज़र"), L("In-house lab", "مختبر داخلي", "इन-हाउस लैब"), L("Pharmacy", "صيدلية", "फ़ार्मेसी"), L("Antenatal care", "رعاية الحمل", "प्रसवपूर्व देखभाल")].map(function (s) { return '<span class="pill mute" style="text-transform:none;letter-spacing:0">' + esc(s) + "</span>"; }).join("") + "</div>" +
        '<hr class="hr" style="margin:14px 0">' + '<div class="eyebrow" style="margin-bottom:10px">' + t("Insurance accepted", "التأمين المقبول", "स्वीकृत बीमा") + '</div><div class="chips">' + (NB.gcc() ? ["Bupa Arabia", "Daman", "AXA"] : ["Star Health", "HDFC Ergo", "PM-JAY"]).map(function (s) { return '<span class="pill info" style="text-transform:none;letter-spacing:0">' + ui.icon("shieldcheck") + esc(s) + "</span>"; }).join("") + "</div>", { icon: "clockh" }) + "</div>";
    h += '<div class="grid g2">' + ui.card(t("Location", "الموقع", "स्थान"), mapSvg(180) + '<p class="small muted" style="margin:10px 0 0">' + esc(f.area) + " · " + t("Parking at the back gate · lift to the 1st floor", "موقف عند البوابة الخلفية · مصعد إلى الطابق الأول", "पार्किंग पीछे वाले गेट पर · पहली मंज़िल तक लिफ़्ट") + "</p>", { icon: "map" }) +
      ui.card(t("Reviews", "التقييمات", "समीक्षाएँ"), '<div class="lst">' + [
        ["Ananya R.", 5, L("Got the “leave home now” message and walked straight in. 6 minutes in the waiting room.", "وصلتني رسالة «اخرج الآن» ودخلت مباشرة. 6 دقائق فقط في الانتظار.", "“अभी निकलें” संदेश मिला और सीधे अंदर गई। वेटिंग रूम में सिर्फ़ 6 मिनट।"), "12 Oct"],
        ["Mohammed A.", 5, L("My son booked for me from Dubai. The staff were kind.", "حجز لي ابني من دبي. كان الطاقم لطيفًا.", "मेरे बेटे ने दुबई से बुक किया। स्टाफ़ बहुत अच्छा था।"), "08 Oct"],
        ["Priya S.", 4, L("Doctor was late but they told us early, so I waited at home.", "تأخر الطبيب لكنهم أخبرونا مبكرًا فانتظرت في المنزل.", "डॉक्टर देर से थे पर पहले ही बता दिया, तो मैंने घर पर इंतज़ार किया।"), "02 Oct"]
      ].map(function (r) { return it(ui.av(r[0]), esc(r[0]) + ' <span class="pt-stars">' + "★★★★★".slice(0, r[1]) + '</span> <span class="small faint num">' + r[3] + "</span>", esc(r[2]), ""); }).join("") + "</div>" +
        '<span class="small faint">' + t("Only patients with a completed visit can review.", "فقط المرضى الذين أتموا زيارة يمكنهم التقييم.", "सिर्फ़ पूरी विज़िट वाले मरीज़ ही समीक्षा कर सकते हैं।") + "</span>", { icon: "star" }) + "</div>";
    return h + "</div></div>";
  });
})();
