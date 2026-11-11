// COM-013: plain-language rejection reasons and the owner's attention banner. Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { attention, pendingChange, reasonHelp } from "../../app/(clinic)/setup/templateStatus.ts";

const v = (version, status, extra = {}) => ({ id: "v" + version, version, status, rejectionReason: null, active: false, headerText: "Clinic", body: "b", createdAt: "2026-11-10T10:00:00Z", ...extra });
const tpl = (extra = {}) => ({ id: "t1", name: "visit", key: "visit", source: "custom", language: "en", status: "approved", rejectionReason: null,
  category: "UTILITY", requestedCategory: "UTILITY", categoryChangedAt: null, pausedUntil: null, version: 1, activeVersion: 1,
  versions: [v(1, "approved", { active: true })], ...extra });
const label = (t) => t.name;

test("a known Meta code reads in plain words with a fix; an unknown one is shown as sent", () => {
  const h = reasonHelp("INCORRECT_CATEGORY");
  assert.match(h.text, /category is wrong/);
  assert.match(h.fix, /Marketing/);
  assert.equal(reasonHelp("SOMETHING_NEW").text, "SOMETHING_NEW");
  assert.equal(reasonHelp(null), null);
});

test("the newer version in review or rejected is the pending change; the active version keeps its own status", () => {
  const t = tpl({ versions: [v(2, "rejected", { rejectionReason: "PROMOTIONAL" }), v(1, "approved", { active: true })] });
  assert.equal(pendingChange(t).version, 2);
  assert.equal(pendingChange(tpl()), null);
  const alerts = attention([t], label);
  assert.equal(alerts.length, 1);
  assert.match(alerts[0].text, /rejected the latest version/);
});

test("pause, disable and a move to Marketing each raise one alert; a clinic-chosen Marketing template does not", () => {
  const now = Date.parse("2026-11-10T10:00:00Z");
  assert.match(attention([tpl({ status: "paused", pausedUntil: "2026-11-10T13:00:00Z" })], label, now)[0].text, /paused this template until about/);
  assert.match(attention([tpl({ status: "disabled" })], label)[0].text, /disabled/);
  assert.match(attention([tpl({ category: "MARKETING", categoryChangedAt: "2026-11-10T10:00:00Z" })], label)[0].text, /marketing messages/);
  assert.equal(attention([tpl({ category: "MARKETING", requestedCategory: "MARKETING" })], label).length, 0);
  assert.equal(attention([tpl()], label).length, 0);
});
