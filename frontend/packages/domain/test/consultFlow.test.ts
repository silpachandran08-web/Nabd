// Unit checks for the consult workspace rules in src/consultFlow.ts (DOC-019 start eligibility, DOC-025 timeline /
// next patient, DOC-038 follow-up state, DOC-044 labels). FEF-013: the 8 cases of the old scripts/test-consult-flow.mjs, on Vitest.
import { test } from "vitest";
import assert from "node:assert/strict";
import {
  buildTimeline, nextPatient, queueStatusLabel, statusBeforeConsult, statusSequence, upcomingFollowUp, NOTE_FIELD_LABEL,
  type QueueRowLite, type TimelineInput,
} from "../src/consultFlow.ts";

test("default pipeline starts the consult from vitals_done", () => {
  assert.equal(statusBeforeConsult(statusSequence([])), "vitals_done");
});
test("a pipeline without vitals starts the consult from waiting", () => {
  assert.equal(statusBeforeConsult(statusSequence(["consultation"])), "waiting");
});
test("billing before consultation starts from billing_pending", () => {
  assert.equal(statusBeforeConsult(statusSequence(["billing", "consultation"])), "billing_pending");
});
test("a pipeline without consultation has no start status", () => {
  assert.equal(statusSequence(["procedures"]).includes("in_consult"), false);
  assert.equal(statusBeforeConsult(statusSequence(["procedures"])), null);
});

test("status labels replace every underscore and use the shared wording", () => {
  assert.equal(queueStatusLabel("checkout_pending"), "Checkout pending");
  assert.equal(queueStatusLabel("vitals_done"), "Ready for doctor");
  assert.equal(queueStatusLabel("some_new_state_x"), "Some new state x");
  assert.equal(NOTE_FIELD_LABEL.objective, "Examination (objective)");
});

test("upcoming follow-up: soonest scheduled follow-up with this doctor, in the future", () => {
  const now = Date.parse("2026-10-14T08:00:00Z");
  const list = [
    { id: "past", patientId: "p", doctorId: "d", startTime: "2026-10-13T08:00:00Z", status: "scheduled", isFollowUp: true },
    { id: "other-doc", patientId: "p", doctorId: "x", startTime: "2026-10-15T08:00:00Z", status: "scheduled", isFollowUp: true },
    { id: "not-fu", patientId: "p", doctorId: "d", startTime: "2026-10-15T08:00:00Z", status: "scheduled", isFollowUp: false },
    { id: "cancelled", patientId: "p", doctorId: "d", startTime: "2026-10-16T08:00:00Z", status: "cancelled", isFollowUp: true },
    { id: "later", patientId: "p", doctorId: "d", startTime: "2026-11-14T08:00:00Z", status: "scheduled", isFollowUp: true },
    { id: "soon", patientId: "p", doctorId: "d", startTime: "2026-10-21T08:00:00Z", status: "scheduled", isFollowUp: true },
  ];
  assert.equal(upcomingFollowUp(list, "d", now)?.id, "soon");
  assert.equal(upcomingFollowUp([], "d", now), null);
});

test("next patient prefers a visit already in consultation, then the lowest startable token", () => {
  const rows = [
    { id: "a", doctorId: "me", status: "vitals_done", tokenNumber: 7 },
    { id: "b", doctorId: "me", status: "vitals_done", tokenNumber: 3 },
    { id: "c", doctorId: "other", status: "in_consult", tokenNumber: 1 },
    { id: "cur", doctorId: "me", status: "checkout_pending", tokenNumber: 2 },
  ];
  const startable = (r: QueueRowLite) => r.status === "vitals_done";
  assert.equal(nextPatient(rows, "cur", "me", startable)?.id, "b");
  assert.equal(nextPatient([...rows, { id: "d", doctorId: "me", status: "in_consult", tokenNumber: 9 }], "cur", "me", startable)?.id, "d");
  assert.equal(nextPatient(rows.filter((r) => r.doctorId !== "me" || r.id === "cur"), "cur", "me", startable), null);
});

test("timeline only lists what is recorded", () => {
  const base: TimelineInput = { checkedInAt: "2026-10-14T05:00:00Z", vitals: { status: "none" }, status: "in_consult", draft: { savedAt: null, pendingOnDevice: false }, signedAt: null, noteSigned: false, amendments: [] };
  assert.deepEqual(buildTimeline(base).map((e) => e.id), ["in", "vitals", "consult"]);
  const offline = buildTimeline({ ...base, draft: { savedAt: null, pendingOnDevice: true } });
  assert.equal(offline.find((e) => e.id === "draft")?.sub, "on this device · waiting to sync");
  const closed = buildTimeline({ ...base, vitals: { status: "ok", summary: "BP 120/80 mmHg" }, status: "checkout_pending", noteSigned: true, signedAt: "2026-10-14T05:30:00Z",
    draft: { savedAt: "2026-10-14T05:20:00Z", pendingOnDevice: false }, amendments: [{ id: "1", createdAt: "2026-10-14T06:00:00Z", reason: "lab result" }] });
  assert.deepEqual(closed.map((e) => e.id), ["in", "vitals", "consult", "signed", "checkout", "am-1"]);
});
