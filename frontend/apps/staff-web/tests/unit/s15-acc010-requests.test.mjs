// ACC-010: pure logic of the booking-requests inbox (SLA text, clinic-zone time inputs, keyboard map, live refresh mapping).
import { test } from "node:test";
import assert from "node:assert/strict";
import { slaState, zonedToInstant, instantToZoned, keyAction, reasonLabel, DECLINE_REASONS } from "../../app/components/requests/requestsLogic.ts";
import { resourcesFor } from "../../../../packages/api-client/sse/patch.ts";

const T0 = Date.parse("2026-11-02T10:00:00Z");

test("SLA countdown reads minutes left, under a minute, and overdue", () => {
  assert.deepEqual(slaState("2026-11-02T10:08:30Z", T0), { overdue: false, label: "8 min left" });
  assert.deepEqual(slaState("2026-11-02T10:00:20Z", T0), { overdue: false, label: "under 1 min left" });
  assert.deepEqual(slaState("2026-11-02T09:57:00Z", T0), { overdue: true, label: "Overdue 3 min" });
  assert.deepEqual(slaState(null, T0), { overdue: false, label: "" });
  assert.deepEqual(slaState("garbage", T0), { overdue: false, label: "" });
});

test("a time typed for the clinic is read in the clinic zone, not the browser's", () => {
  assert.equal(zonedToInstant("2026-11-02T10:15", "Asia/Kolkata"), "2026-11-02T04:45:00.000Z");
  assert.equal(zonedToInstant("2026-11-02T10:15", "Asia/Riyadh"), "2026-11-02T07:15:00.000Z");
  assert.equal(zonedToInstant("2026-07-01T09:00", "Europe/London"), "2026-07-01T08:00:00.000Z"); // summer time
  assert.equal(zonedToInstant("2026-01-01T09:00", "Europe/London"), "2026-01-01T09:00:00.000Z");
  assert.equal(zonedToInstant("not a time", "Asia/Kolkata"), null);
});

test("an instant fills the time input in the clinic zone and round-trips", () => {
  assert.equal(instantToZoned("2026-11-02T04:45:00Z", "Asia/Kolkata"), "2026-11-02T10:15");
  for (const tz of ["Asia/Kolkata", "Asia/Riyadh", "Europe/London", "America/New_York"]) {
    assert.equal(zonedToInstant(instantToZoned("2026-03-29T01:30:00Z", tz), tz), "2026-03-29T01:30:00.000Z", tz);
  }
});

test("J K A S D drive the inbox, but never while typing, with a modifier or with a dialog open", () => {
  const k = (key, extra = {}) => ({ key, target: { tagName: "BODY" }, ...extra });
  assert.equal(keyAction(k("j"), false), "next");
  assert.equal(keyAction(k("K"), false), "prev");
  assert.equal(keyAction(k("a"), false), "accept");
  assert.equal(keyAction(k("s"), false), "suggest");
  assert.equal(keyAction(k("d"), false), "decline");
  assert.equal(keyAction(k("x"), false), null);
  assert.equal(keyAction(k("a", { ctrlKey: true }), false), null);
  assert.equal(keyAction(k("a", { metaKey: true }), false), null);
  assert.equal(keyAction({ key: "a", target: { tagName: "INPUT" } }, false), null);
  assert.equal(keyAction({ key: "d", target: { tagName: "SELECT" } }, false), null);
  assert.equal(keyAction({ key: "a", target: { tagName: "DIV", isContentEditable: true } }, false), null);
  assert.equal(keyAction(k("a"), true), null);
});

test("labels: every review reason has words, an unknown one still reads, decline reasons are administrative codes", () => {
  for (const c of ["new_patient", "over_cap", "triage_flag", "procedure", "group", "policy_review"]) assert.notEqual(reasonLabel(c), "Needs review", c);
  assert.equal(reasonLabel("something_new"), "Needs review");
  assert.equal(reasonLabel(null), "Needs review");
  for (const d of DECLINE_REASONS) assert.match(d.code, /^[a-z0-9_]{3,40}$/);
});

test("a booking event makes the requests inbox stale (live refresh), other scheduling events leave queue screens alone", () => {
  assert.deepEqual(resourcesFor("scheduling.booking.routed.v1"), ["booking-requests"]);
  assert.deepEqual(resourcesFor("scheduling.appointment.booked.v1"), ["booking-requests"]);
  assert.deepEqual(resourcesFor("requests.created"), []);
});
