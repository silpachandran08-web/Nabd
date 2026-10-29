// ADM-018: tile arithmetic and the two row predicates.
import { test } from "node:test";
import assert from "node:assert/strict";
const { staffKpis, needsMfaReminder, isSeatLimit } = await import("../../app/(clinic)/staff/staffKpis.ts");

const a = (loginMethod, status = "active", loginFactors = []) => ({ status, loginMethod, loginFactors });
test("counts active, pending and MFA coverage over people who need or have an authenticator", () => {
  const k = staffKpis([a("authenticator", "active", ["authenticator"]), a("authenticator_setup_required"), a("authenticator_setup_required"), a("pin"), a("invite_pending", "invited"), a("pin", "suspended")], { seatsUsed: 5, seatsLimit: 8 });
  assert.equal(k.active, 4);
  assert.equal(k.pendingInvites, 1);
  assert.equal(k.mfaPercent, 33);
  assert.equal(k.mfaMissing, 2);
  assert.equal(k.seatsLeft, 3);
  assert.equal(k.seatsText, "5 of 8");
});
test("nobody needing MFA gives no percentage, unlimited seats have no 'left'", () => {
  const k = staffKpis([a("pin")], { seatsUsed: 1, seatsLimit: null });
  assert.equal(k.mfaPercent, null);
  assert.equal(k.seatsLeft, null);
  assert.equal(k.seatsText, "1 used");
  assert.equal(staffKpis([], null).seatsText, null);
});
test("seats never go below zero", () => assert.equal(staffKpis([], { seatsUsed: 9, seatsLimit: 7 }).seatsLeft, 0));
test("reminder only for an active person with setup pending", () => {
  assert.equal(needsMfaReminder(a("authenticator_setup_required")), true);
  assert.equal(needsMfaReminder(a("authenticator_setup_required", "suspended")), false);
  assert.equal(needsMfaReminder(a("authenticator", "active", ["authenticator"])), false);
  assert.equal(needsMfaReminder(a("pin")), false);
});
test("seat-limit problem is recognised by title or code", () => {
  assert.equal(isSeatLimit({ title: "SEAT_LIMIT_REACHED" }), true);
  assert.equal(isSeatLimit({ code: "SEAT_LIMIT_REACHED" }), true);
  assert.equal(isSeatLimit({ title: "Conflict" }), false);
  assert.equal(isSeatLimit(null), false);
});
