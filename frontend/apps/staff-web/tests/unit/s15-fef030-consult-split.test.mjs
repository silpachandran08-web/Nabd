// FEF-030: the consult workspace split. Structure gates (page < 300 LOC, <= 10 useState per file, 0 inline styles, no raw fetch)
// and the pure modules (workflow reducer, close-out checks). The autosave rules are characterised in the browser:
// tests/e2e/s15-fef-030-autosave-characterisation.mjs. `pnpm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { APP, consultFiles, consultPage, consultSource } from "./consultSource.mjs";
import { initialWorkflow, isStep, STEP_IDS, workflowReducer } from "../../app/components/consult/workflow/consultWorkflow.ts";
import { closeView } from "../../app/components/consult/closeout/closeLogic.ts";
import { sameFields, toFields } from "../../app/components/consult/note/noteLogic.ts";
import { openWarningCount, padItems } from "../../app/components/consult/rx/rxLogic.ts";

const lines = (f) => readFileSync(f, "utf8").split("\n").length;
const walk = (d) => readdirSync(d).flatMap((n) => (statSync(join(d, n)).isDirectory() ? walk(join(d, n)) : [join(d, n)]));

test("AC1: the consult page is under 300 lines", () => {
  assert.ok(lines(consultPage()) < 300, `page is ${lines(consultPage())} lines`);
});

test("AC1: no consult file has more than 10 useState hooks", () => {
  const files = [...consultFiles(), ...walk(join(APP, "components/consult")).filter((f) => /\.tsx?$/.test(f))];
  for (const f of new Set(files)) {
    const n = (readFileSync(f, "utf8").match(/\buseState\b\s*[<(]/g) ?? []).length;
    assert.ok(n <= 10, `${f}: ${n} useState`);
  }
});

test("AC5: 0 inline styles remain in the consult page and components/consult", () => {
  const files = [consultPage(), ...walk(join(APP, "components/consult")).filter((f) => /\.tsx$/.test(f))];
  const hits = files.filter((f) => /\bstyle=\{/.test(readFileSync(f, "utf8")));
  assert.deepEqual(hits, []);
});

test("the split feature folders exist and the old single page is gone", () => {
  for (const d of ["queue", "note", "rx", "orders", "timeline", "closeout", "specialty", "workflow", "visit"]) assert.ok(existsSync(join(APP, "components/consult", d)), d);
  assert.ok(!existsSync(join(APP, "(clinic)/consult-legacy")), "temporary baseline copy removed");
  assert.ok(!existsSync(join(APP, "consult")), "no app/consult left outside the (clinic) group");
});

test("AC4: every panel that can fail alone sits in a PanelBoundary, the note fields and patient banner do not", () => {
  const ws = readFileSync(join(APP, "components/consult/visit/VisitWorkspace.tsx"), "utf8");
  assert.match(ws, /<PanelBoundary panel="rx">[\s\S]*<RxPanel/);
  for (const p of ["timeline", "closeout", "safety", "reason"]) assert.match(ws, new RegExp(`<PanelBoundary panel="${p}">`), p);
  assert.ok(!/<PanelBoundary[^>]*>\s*<(VisitStrip|AssessStep|ExamineStep|PlanField)/.test(ws), "banner and note fields stay outside the boundaries");
  assert.match(readFileSync(join(APP, "components/consult/orders/ToolsAside.tsx"), "utf8"), /PanelBoundary panel="orders"/);
});

test("the consult feature makes no raw fetch / token decode / API_BASE", () => {
  for (const f of consultFiles()) {
    const s = readFileSync(f, "utf8").replace(/\/\/.*$/gm, "");
    assert.ok(!/(^|[^.\w])fetch\(|API_BASE|atob\(|decodeAccessToken/.test(s), f);
  }
});

test("workflow reducer: steps, close acknowledgement and the panels on top of the rail", () => {
  assert.deepEqual([...STEP_IDS], ["reason", "examine", "assess", "plan", "close"]);
  assert.ok(isStep("plan") && !isStep("nope") && !isStep(null));
  let s = initialWorkflow("plan");
  assert.equal(s.step, "plan");
  s = workflowReducer(s, { type: "goto", step: "close" });
  s = workflowReducer(s, { type: "closeAck", value: true });
  s = workflowReducer(s, { type: "amend", open: true });
  s = workflowReducer(s, { type: "dental", open: true });
  s = workflowReducer(s, { type: "allergies", open: true });
  assert.deepEqual(s, { step: "close", closeAck: true, showAmend: true, showDental: true, showAllergies: true });
  assert.equal(workflowReducer(s, { type: "goto", step: "close" }), s, "same step: same state object");
  assert.equal(initialWorkflow().step, "reason");
});

test("close-out checks (DOC-029): diagnosis, empty note, unsigned prescription, hand-off states", () => {
  const blank = { subjective: "", objective: "", assessment: "", plan: "", diagnosis: "" };
  const drug = [{ drugName: "Paracetamol", dosage: "", frequency: "", duration: "", instructions: "" }];
  let v = closeView({ note: blank, noteStatus: "draft", rxItems: [], rxStatus: "draft", entryStatus: "in_consult" });
  assert.deepEqual(v.issues, ["No diagnosis is entered.", "The note is empty."]);
  assert.ok(!v.hasDrugs && !v.handoffPending && !v.closedOut);
  v = closeView({ note: { ...blank, diagnosis: "Flu", plan: "rest" }, noteStatus: "draft", rxItems: drug, rxStatus: "draft", entryStatus: "in_consult" });
  assert.deepEqual(v.issues, ["The prescription is not signed, so the patient would leave without it."]);
  v = closeView({ note: blank, noteStatus: "signed", rxItems: drug, rxStatus: "signed", entryStatus: "in_consult" });
  assert.deepEqual(v.issues, []);
  assert.ok(v.handoffPending && !v.closedOut);
  v = closeView({ note: blank, noteStatus: "signed", rxItems: [], rxStatus: "draft", entryStatus: "checkout_pending" });
  assert.ok(v.closedOut && !v.handoffPending);
});

test("note and prescription helpers", () => {
  const n = { id: "n", queueEntryId: "e", patientId: "p", doctorId: "d", status: "draft", updatedAt: "t", subjective: "S", objective: null, assessment: null, plan: "P", diagnosis: null, signedAt: null };
  assert.deepEqual(toFields(n), { subjective: "S", objective: "", assessment: "", plan: "P", diagnosis: "" });
  assert.ok(sameFields(toFields(n), toFields(n)) && !sameFields(toFields(n), { ...toFields(n), plan: "x" }));
  assert.equal(padItems({ id: "r", status: "draft", signedAt: null, items: [] }).length, 1, "an empty prescription still gives one blank line");
  const w = { kind: "allergy", requiresDecision: true, resolved: false };
  assert.equal(openWarningCount([{ drugName: "a", warnings: [w, { ...w, resolved: true }] }, { drugName: "b" }]), 1);
  assert.equal(padItems({ id: "r", status: "draft", signedAt: null, items: [{ drugName: "a", dosage: null, frequency: null, duration: null, instructions: null }] })[0].dosage, "");
});

test("the seven autosave rules are named where they live", () => {
  const src = readFileSync(join(APP, "components/consult/note/useNoteEditor.ts"), "utf8");
  for (const r of ["rule 1", "rule 2", "rule 3", "Rule 4", "rule 5", "rule 6", "rule 7"]) assert.match(src, new RegExp(r), r);
  assert.ok(consultSource().includes("deleteDraft"));
});
