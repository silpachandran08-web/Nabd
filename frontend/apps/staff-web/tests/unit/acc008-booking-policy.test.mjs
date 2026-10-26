// ACC-008 / ACC-013: the client-side policy rules, the unpublished-changes test and the journey preview.
import { test } from "node:test";
import assert from "node:assert/strict";
const { defaultPolicy, samePolicy, policyProblem, journey, setIn, pointerLabel, showValue } = await import("../../app/lib/bookingPolicy.ts");

const solo = () => {
  const p = defaultPolicy();
  p.preset = "solo"; p.solo = true; p.checkIn.modes = ["door_qr"]; p.workflow = { vitalsEnabled: false, triageEnabled: false };
  p.eta = { displayMode: "range", leaveHomeMarginMinutes: 20 };
  for (const k of Object.keys(p.visitTypes)) p.visitTypes[k] = { acceptance: "auto", dailyCapPerDoctor: 30, reviewerRole: "doctor" };
  return p;
};

test("the solo journey has 0 staff touches before the consult", () => {
  for (const v of ["new", "followUp", "procedure", "teleconsult"]) assert.equal(journey(solo(), v).touchesBeforeConsult, 0);
});

test("review, desk check-in, triage and vitals each add one touch, with the role named", () => {
  const p = defaultPolicy();
  p.visitTypes.new.acceptance = "review";
  p.checkIn.modes = ["desk"];
  const j = journey(p, "new");
  assert.equal(j.touchesBeforeConsult, 4);
  assert.deepEqual(j.steps.filter((s) => s.touch).map((s) => s.touch), ["reception", "reception", "nurse", "nurse"]);
});

test("hybrid reviews only patients new to the doctor", () => {
  const p = defaultPolicy();
  p.visitTypes.new.acceptance = "hybrid"; p.visitTypes.followUp.acceptance = "hybrid";
  p.workflow = { vitalsEnabled: false, triageEnabled: false }; p.checkIn.modes = ["door_qr"];
  assert.equal(journey(p, "new").touchesBeforeConsult, 1);
  assert.equal(journey(p, "followUp").touchesBeforeConsult, 0);
});

test("solo forces the doctor as reviewer in the preview", () => {
  const p = solo(); p.visitTypes.new = { acceptance: "review", dailyCapPerDoctor: 10, reviewerRole: "reception" };
  assert.equal(journey(p, "new").steps[0].touch, "doctor");
});

test("policyProblem mirrors the server rules", () => {
  assert.equal(policyProblem(defaultPolicy()), null);
  assert.equal(policyProblem(solo()), null);
  const a = solo(); a.checkIn.modes = ["desk"];
  assert.match(policyProblem(a), /reception-only/);
  const b = defaultPolicy(); b.checkIn.modes = [];
  assert.match(policyProblem(b), /at least one/);
  const c = defaultPolicy(); c.eta.displayMode = "queue_position"; c.eta.leaveHomeMarginMinutes = 10;
  assert.match(policyProblem(c), /leave-home/);
  const d = defaultPolicy(); d.visitTypes.followUp.dailyCapPerDoctor = 201;
  assert.match(policyProblem(d), /Follow-up.*1 to 200/);
  const e = defaultPolicy(); e.visitTypes.new.dailyCapPerDoctor = 1.5;
  assert.match(policyProblem(e), /New patient/);
});

test("samePolicy ignores the server-owned customised label and spots a real change", () => {
  const a = defaultPolicy(); const b = { ...defaultPolicy(), presetCustomised: true };
  assert.equal(samePolicy(a, b), true);
  const c = setIn(defaultPolicy(), ["visitTypes", "followUp", "dailyCapPerDoctor"], 40);
  assert.equal(samePolicy(a, c), false);
  assert.equal(c.visitTypes.followUp.dailyCapPerDoctor, 40);
  assert.equal(a.visitTypes.followUp.dailyCapPerDoctor, 200); // setIn does not mutate
});

test("labels for the diff and error lists", () => {
  assert.equal(pointerLabel("/visitTypes/followUp/dailyCapPerDoctor"), "Follow-up: daily cap");
  assert.equal(pointerLabel("/walkIn/interleave"), "walk-in interleave");
  assert.equal(showValue(true), "on"); assert.equal(showValue(["a", "b"]), "a, b"); assert.equal(showValue(null), "none");
});
