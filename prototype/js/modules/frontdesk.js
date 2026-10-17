/* Front desk module: reception screens (arrivals board, booking requests, appointments, patients,
   self check-in, waiting-room display, WhatsApp inbox) and the walk-in registration drawer. */
(function () {
  "use strict";
  var ui = NB.ui, L = NB.L;

  var ST = { // queue status -> [pill kind, label]
    booked: ["mute", "Booked"], arrived: ["info", "Arrived"], vitals: ["ok", "Vitals done"], waiting: ["warn", "Waiting"],
    inconsult: ["acc", "In consultation"], checkout: ["info", "Checkout pending"], completed: ["ok", "Completed"]
  };
  NB.queueStatus = ST;

  NB.screen("reception.arrivals", function (c) {
    var q = NB.D.queue, f = NB.v("arrF", "all");
    var pred = { all: function () { return true; }, waiting: function (x) { return x.st === "waiting" || x.st === "arrived" || x.st === "vitals"; },
      inconsult: function (x) { return x.st === "inconsult"; }, checkout: function (x) { return x.st === "checkout"; }, done: function (x) { return x.st === "completed"; } };
    var rows = q.filter(pred[f] || pred.all);
    var cnt = function (k) { return q.filter(pred[k]).length; };
    var h = ui.header(L("Today · Arrivals", "اليوم · الوصول", "आज · आगमन"), "Sat, 17 Oct 2026 · " + q.length + " on the board · median wait <b class=\"num\">11 min</b>",
      ui.seg("arrF", [["all", L("All", "الكل", "सभी"), q.length], ["waiting", L("Waiting", "في الانتظار", "प्रतीक्षारत"), cnt("waiting")], ["inconsult", L("In consultation", "في الاستشارة", "परामर्श में"), cnt("inconsult")],
        ["checkout", L("Checkout", "الدفع", "चेकआउट"), cnt("checkout")], ["done", L("Completed", "مكتمل", "पूर्ण"), cnt("done")]]));
    h += '<div class="btns">' + ui.btn(L("Register walk-in", "تسجيل بدون موعد", "वॉक-इन दर्ज करें"), { cls: "pri", ov: "walkin", icon: "userplus" }) +
      ui.btn(L("Check in", "تسجيل الحضور", "चेक-इन"), { ov: "checkin", icon: "check" }) + ui.btn(L("Issue token", "إصدار رقم", "टोकन जारी करें"), { act: "toast", arg: "Token T-23 issued and sent on WhatsApp", icon: "qr" }) +
      ui.btn(L("Notify delay", "إشعار بالتأخير", "देरी सूचित करें"), { ov: "delay", icon: "clockh" }) + ui.btn(L("Waiting-room display", "شاشة الانتظار", "प्रतीक्षा स्क्रीन"), { go: "reception.display", icon: "tv" }) + "</div>";

    var table = ui.table([{ h: L("Token", "الرقم", "टोकन") }, { h: L("Patient", "المريض", "मरीज़") }, { h: L("Visit", "الزيارة", "विज़िट") }, { h: L("Doctor", "الطبيب", "डॉक्टर") },
      { h: L("Wait / ETA", "الانتظار", "प्रतीक्षा") }, { h: L("Status", "الحالة", "स्थिति") }, { h: "" }],
      rows.map(function (x) {
        var p = NB.pat(x.p), d = NB.doc(x.d), s = ST[x.st];
        var act = x.st === "checkout" ? ui.btn("Checkout", { cls: "sm pri", go: "billing.checkout", icon: "receipt" })
          : x.st === "booked" ? ui.btn("Check in", { cls: "sm", act: "toast", arg: p.name + " checked in", icon: "check" })
          : x.st === "completed" ? ui.btn("Invoice", { cls: "sm", go: "billing.invoices", icon: "printer" })
          : ui.btn("Move", { cls: "sm", ov: "movequeue", arg: x.tok, icon: "arrowright" });
        return { cls: x.flag || "", cells: ['<span class="tok">' + x.tok + "</span>", ui.cellp(ui.esc(NB.pname(p)), p.age + " " + p.sex + " · " + ui.esc(x.reason)),
          ui.cellp(ui.esc(x.type), ui.icon(x.src === "WhatsApp" ? "chat" : x.src === "Desk" ? "arrivals" : "globe", "xs") + " " + ui.esc(x.src)),
          ui.cellp(ui.esc(NB.dname(d)), ui.esc(d.spec)),
          ui.cellp(x.wait ? '<span class="num ltr">' + x.wait + " min</span>" : "—", x.eta ? '<span class="ltr">ETA ' + x.eta + " min</span>" : x.arr),
          ui.pill(s[0], s[1]) + (x.flag === "crit" ? " " + ui.pill("crit", "Urgent") : ""), act] };
      }), { min: 780 });

    var rail = ui.card(L("Doctor queues", "طوابير الأطباء", "डॉक्टर क़तारें"), '<div class="lst">' + NB.D.doctors.slice(0, 4).map(function (d) {
      var n = q.filter(function (x) { return x.d === d.id && /waiting|arrived|vitals|booked/.test(x.st); }).length;
      return ui.li(ui.av(d.name), ui.esc(NB.dname(d)), ui.esc(d.spec) + " · " + n + " waiting · avg " + d.avgMin + " min",
        d.status === "break" ? ui.pill("perm", "On break") : d.delay ? ui.pill("warn", "+" + d.delay + " min") : ui.pill("ok", "On time"));
    }).join("") + "</div>", { icon: "steth" }) +
      ui.card(L("ETA shown to patients", "الوقت المتوقع للمرضى", "मरीज़ों को ETA"), '<p class="small muted" style="margin:0 0 10px">Set by the owner in Workflows. Patients get a WhatsApp “leave home now” message when their ETA drops below their travel time.</p>' +
        '<div class="chips"><span class="pill acc">Range ±10 min</span><span class="pill mute">Re-estimated every check-in</span></div>', { icon: "hourglass" });

    return h + '<div class="split">' + table + '<div class="stack s16">' + rail + "</div></div>";
  });

  NB.overlay("walkin", function () {
    var m = NB.mkt();
    return { title: L("Register walk-in", "تسجيل بدون موعد", "वॉक-इन दर्ज करें"), body:
      ui.note("info", "Type a phone number first: returning patients and family members fill in automatically.") +
      '<div class="fgrid">' + ui.field("Mobile", m.phone + " ", { ph: "98xxx xxxxx" }) + ui.field(m.natId + " (optional)", "", { ph: "Scan or type" }) +
      ui.field("Full name", "") + ui.field("Age / date of birth", "") + ui.field("Sex", "Female", { options: ["Female", "Male", "Other"] }) +
      ui.field("Preferred language", "English", { options: ["English", "Hindi", "Kannada", "Arabic", "Malayalam"] }) +
      ui.field("Doctor", "Next available (Dr. Nisha Bhat, ETA 17 min)", { options: ["Next available (Dr. Nisha Bhat, ETA 17 min)", "Dr. Meera Iyer · ETA 27 min", "Dr. Vikram Shah · ETA 41 min"], full: true }) +
      ui.field("Reason for visit", "", { full: true, ph: "e.g. fever since yesterday" }) + "</div>" +
      ui.toggle("wkUrgent", "Mark urgent", "Moves the patient ahead and asks for a reason (audited).") +
      ui.toggle("wkWa", "Send token and live queue link on WhatsApp", "Patient can wait outside and come back when called.", true),
      foot: ui.btn(L("Cancel", "إلغاء", "रद्द करें"), { act: "close", cls: "ghost" }) + ui.btn("Register and issue token", { cls: "pri", act: "toast", arg: "Registered · token T-23 · WhatsApp sent", icon: "check" }) };
  });
})();
