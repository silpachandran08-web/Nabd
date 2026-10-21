// FEF-028: Arrivals as a feature folder. Pure logic (reducer, schema, board logic) and the structural budget (AC1).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { arrivalsFiles } from "./arrivalsSource.mjs";

const base = "../../app/components/arrivals/";
const { walkInReducer, initialWalkIn } = await import(base + "walk-in/walkInReducer.ts");
const { walkInProblem, registerBody, checkInBody } = await import(base + "walk-in/walkInSchema.ts");
const L = await import(base + "board/boardLogic.ts");

const P = { id: "p1", name: "Layla", phone: "555", mrn: "M1" };
const run = (...actions) => actions.reduce(walkInReducer, initialWalkIn);

test("AC1: arrivals/page.tsx is under 300 lines and no component file has more than 8 useState", () => {
  for (const f of arrivalsFiles()) {
    const s = readFileSync(f, "utf8");
    const n = (s.match(/\buseState\b\s*[<(]/g) ?? []).length;
    assert.ok(n <= 8, `${f}: ${n} useState`);
    if (f.endsWith("arrivals/page.tsx")) assert.ok(s.split("\n").length < 300, `page is ${s.split("\n").length} lines`);
  }
});

test("reducer: typing in the search resets selection, new-patient mode and duplicates; short query clears results", () => {
  let s = run({ type: "results", results: [P] }, { type: "select", patient: P });
  assert.equal(s.selected.id, "p1"); assert.deepEqual(s.results, []);
  s = run({ type: "results", results: [P] }, { type: "newMode" }, { type: "query", value: "L" });
  assert.equal(s.newMode, false); assert.deepEqual(s.results, []);
  s = run({ type: "results", results: [P] }, { type: "query", value: "La" });
  assert.equal(s.results.length, 1, "results are replaced by the debounced search, not cleared per keystroke");
});

test("reducer: a field edit clears field errors; duplicates are sorted by score; using a candidate selects it and clears the list", () => {
  let s = run({ type: "error", message: "x", fieldErrs: { name: "bad" } }, { type: "field", field: "name", value: "A" });
  assert.deepEqual(s.fieldErrs, {}); assert.equal(s.name, "A"); assert.equal(s.error, "x");
  s = run({ type: "duplicates", message: "m", candidates: [{ patientId: "a", matchScore: 0.5 }, { patientId: "b", matchScore: 0.9 }] });
  assert.deepEqual(s.candidates.map((c) => c.patientId), ["b", "a"]);
  s = walkInReducer(s, { type: "useCandidate", patient: P });
  assert.equal(s.selected.id, "p1"); assert.deepEqual(s.candidates, []); assert.equal(s.error, null);
});

test("reducer: guardianClear resets consent and channel; an error action without fieldErrs clears them", () => {
  let s = run({ type: "guardianPick", patient: P }, { type: "guardianConsent", value: true }, { type: "guardianChannel", value: "phone" }, { type: "guardianClear" });
  assert.equal(s.guardian, null); assert.equal(s.guardianConsent, false); assert.equal(s.guardianChannel, "");
  s = run({ type: "error", message: "a", fieldErrs: { dob: "x" } }, { type: "error", message: null });
  assert.deepEqual(s.fieldErrs, {});
});

test("schema: checks run in the page's order with the page's messages", () => {
  const s0 = initialWalkIn;
  assert.equal(walkInProblem(s0, false, false), "Pick a doctor.");
  const d = { ...s0, doctorId: "d1" };
  assert.equal(walkInProblem(d, true, false), null, "an existing patient needs only a doctor");
  assert.match(walkInProblem(d, false, false), /name, phone and date of birth/);
  const n = { ...d, name: "A", phone: "1", dob: "2020-01-01" };
  assert.match(walkInProblem(n, false, true), /guardian is required/);
  assert.match(walkInProblem({ ...n, guardian: P }, false, true), /guardian's consent/);
  assert.equal(walkInProblem({ ...n, guardian: P, guardianConsent: true, guardianChannel: "phone" }, false, true), null);
  assert.equal(walkInProblem(n, false, false), null);
});

test("schema: request bodies", () => {
  const s = { ...initialWalkIn, name: "A", phone: "1", dob: "2020-01-01", doctorId: "d1", complaint: "  cough ", guardian: P, guardianChannel: "phone" };
  assert.deepEqual(registerBody(s, false, false), { name: "A", phone: "1", dob: "2020-01-01", gender: "male", guardianId: null });
  const minor = registerBody(s, true, true);
  assert.equal(minor.guardianId, "p1"); assert.deepEqual(minor.guardianConsent, { confirmed: true, channel: "phone" }); assert.equal(minor.confirmedNotDuplicate, true);
  assert.deepEqual(checkInBody(s, "p9"), { patientId: "p9", doctorId: "d1", presentingComplaint: "cough", paymentMode: "cash" });
  assert.equal(checkInBody({ ...s, complaint: "  " }, "p9").presentingComplaint, null);
});

test("board logic: buckets, tabs, counts, wait minutes, match percent, doctor queue order", () => {
  assert.equal(L.bucketOf("vitals_pending"), "waiting"); assert.equal(L.bucketOf("procedures_pending"), "in_consult");
  assert.equal(L.bucketOf("no_show"), "other"); assert.equal(L.bucketOf("mystery"), "other");
  assert.ok(L.isArrivalsTab("completed")); assert.ok(!L.isArrivalsTab("nope")); assert.ok(!L.isArrivalsTab(null));
  const rows = [{ status: "waiting", doctorId: "a" }, { status: "checked_in", doctorId: "a" }, { status: "completed", doctorId: "b" }, { status: "no_show" }];
  assert.deepEqual(L.countsOf(rows), { all: 4, waiting: 2, in_consult: 0, checkout_pending: 0, completed: 1 });
  assert.equal(L.filterByTab(rows, "all").length, 4); assert.equal(L.filterByTab(rows, "waiting").length, 2);
  assert.equal(L.waitMinutes(new Date(0).toISOString(), 125_000), 2); assert.equal(L.waitMinutes(new Date(10_000).toISOString(), 0), 0);
  assert.equal(L.matchPercent(1.4), 100); assert.equal(L.matchPercent(-1), 0); assert.equal(L.matchPercent(0.856), 86);
  const q = L.doctorQueuesOf([{ id: "b" }, { id: "a" }], rows, { a: 7 }, { b: { delayMinutes: 5 } });
  assert.deepEqual(q.map((d) => [d.id, d.waiting, d.estimatedMinutes, d.delay?.delayMinutes ?? null]), [["a", 2, 7, null], ["b", 0, undefined, 5]]);
});
