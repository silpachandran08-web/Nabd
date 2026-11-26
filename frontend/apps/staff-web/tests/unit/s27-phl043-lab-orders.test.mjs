// PHL-043 lab orders from the consult: the card's mode rules, typeahead, order bodies, sheet form and the wiring. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const m = await import("../../app/lib/labOrders.ts");
const src = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");
const msgs = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/en/consult.json", import.meta.url), "utf8")).labTests;
const ent = (lab) => ({ modules: [{ code: "lab", enabled: lab }, { code: "billing", enabled: true }], killedFeatures: [], seatLimit: null });
const DOCTOR = ["lab:order", "lab:view", "clinical:edit"];

const tests = [
  { id: "t-crp", code: "CRP", name: "C-reactive protein", price: 500, active: true },
  { id: "t-chol", code: "CHOL", name: "Cholesterol, total", price: 200, active: true },
  { id: "t-old", code: "LIPX", name: "Lipase (retired)", price: 300, active: false },
];
const panels = [{ id: "p-lipid", code: "LIPID", name: "Lipid profile", price: 700, active: true, tests: [{ id: "t-chol" }, { id: "t-hdl" }] },
  { id: "p-off", code: "LIVER", name: "Liver panel", price: 900, active: false, tests: [{ id: "t-alt" }] }];
const index = m.catalogueIndex(tests, panels);

test("AC6: the card is the full order form with the lab module, only the outside-lab sheet without it, absent when the user has neither grant", () => {
  assert.equal(m.testsMode(ent(true), true, DOCTOR), "orders");
  assert.equal(m.testsMode(ent(false), true, DOCTOR), "external", "no lab module: sheet only");
  assert.equal(m.testsMode(null, true, DOCTOR), "unknown", "a failed entitlements call is shown with a manual retry");
  assert.equal(m.testsMode(null, false, DOCTOR), "wait", "nothing mounted before the first answer");
  assert.equal(m.testsMode(ent(true), true, ["patients:view"]), "none", "no grant, no card (absent, not disabled)");
  assert.equal(m.testsMode(ent(true), true, ["clinical:edit"]), "none", "lab module on but no lab:order: no order form and no outside sheet either");
  assert.equal(m.testsMode(ent(false), true, ["lab:order", "lab:view"]), "none", "the sheet is for whoever writes the visit (clinical:edit)");
  assert.equal(m.testsMode(null, false, ["patients:view"]), "none");
});

test("AC1: typing 'lip' offers the Lipid profile first; inactive tests and panels are not in the index", () => {
  assert.deepEqual(index.map((o) => o.code).sort(), ["CHOL", "CRP", "LIPID"]);
  assert.equal(m.searchCatalogue(index, "lip")[0].name, "Lipid profile");
  assert.equal(m.searchCatalogue(index, "LIP")[0].code, "LIPID", "case does not matter");
  assert.equal(m.searchCatalogue(index, "react")[0].code, "CRP", "a word of the name matches");
  assert.deepEqual(m.searchCatalogue(index, "  "), []);
  assert.deepEqual(m.searchCatalogue(index, "lipase"), [], "a retired test cannot be found");
});

test("typeahead over a 3000-entry catalogue answers in well under 100 ms (client index)", () => {
  const big = Array.from({ length: 3000 }, (_, i) => ({ kind: "test", id: `t${i}`, code: `C${i}`, name: `Test number ${i} serum`, price: 10, testIds: [`t${i}`] }));
  const t0 = performance.now();
  for (let i = 0; i < 20; i++) m.searchCatalogue(big, "serum 29");
  assert.ok((performance.now() - t0) / 20 < 100);
});

test("a test already in the order (alone or inside a panel) is refused before the server is asked", () => {
  const lipid = index.find((o) => o.code === "LIPID"), chol = index.find((o) => o.code === "CHOL"), crp = index.find((o) => o.code === "CRP");
  assert.equal(m.clash([lipid], chol).code, "LIPID");
  assert.equal(m.clash([chol], lipid).code, "CHOL");
  assert.equal(m.clash([lipid], crp), null);
});

test("the order body: a panel is sent as panelId, a test as testId; fasting and the note only when set", () => {
  const f = { ...m.emptyOrder(), picked: index.filter((o) => o.code !== "CHOL"), priority: "stat" };
  const body = m.orderBody(f, "pat-1", "visit-1");
  assert.deepEqual(body.items, [{ panelId: "p-lipid" }, { testId: "t-crp" }]);
  assert.deepEqual({ ...body, items: undefined }, { patientId: "pat-1", queueEntryId: "visit-1", items: undefined, priority: "stat", destination: { kind: "inhouse" } });
  const full = m.orderBody({ ...f, fasting: true, fastingHours: "8", note: " recheck ", destination: "external" }, "p", "v");
  assert.equal(full.fastingHours, 8); assert.equal(full.clinicalNote, "recheck"); assert.deepEqual(full.destination, { kind: "external" });
  assert.equal(m.orderTotal(f.picked), 1200);
  assert.equal(m.orderTotal([{ price: 0.1 }, { price: 0.2 }]), 0.3, "no float drift on the total");
});

test("the order form is checked before sending: items, fasting hours 1 to 24, note length", () => {
  const ok = { ...m.emptyOrder(), picked: [index[0]] };
  assert.equal(m.orderError(m.emptyOrder()), "items");
  assert.equal(m.orderError(ok), null);
  assert.equal(m.orderError({ ...ok, fasting: true, fastingHours: "" }), "fasting");
  assert.equal(m.orderError({ ...ok, fasting: true, fastingHours: "25" }), "fasting");
  assert.equal(m.orderError({ ...ok, fasting: true, fastingHours: "0" }), "fasting");
  assert.equal(m.orderError({ ...ok, fasting: true, fastingHours: "12" }), null);
  assert.equal(m.orderError({ ...ok, note: "x".repeat(501) }), "note");
});

test("AC2: orders are shown STAT first, then urgent, then routine, oldest first inside a priority", () => {
  const o = (id, priority, orderedAt) => ({ id, priority, orderedAt });
  assert.deepEqual(m.byPriority([o("r1", "routine", "2026-11-23T08:00Z"), o("s2", "stat", "2026-11-23T09:00Z"), o("u1", "urgent", "2026-11-23T07:00Z"), o("s1", "stat", "2026-11-23T08:30Z")]).map((x) => x.id),
    ["s1", "s2", "u1", "r1"]);
});

test("AC4: the doctor is told what happened to the fasting notice (sent, no consent, no approved template); none when there is no fasting", () => {
  assert.equal(m.fastingMessage("requested"), "fastingSent");
  assert.equal(m.fastingMessage("blocked"), "fastingBlocked");
  assert.equal(m.fastingMessage("template_missing"), "fastingNoTemplate");
  assert.equal(m.fastingMessage(null), null);
});

test("AC3/AC6: the outside-lab sheet form: one test per line, at most 30, 120 characters each", () => {
  assert.deepEqual(m.sheetTests("CBC\n\n  Lipid profile \n"), ["CBC", "Lipid profile"]);
  assert.equal(m.sheetError(m.emptySheet()), "tests");
  assert.equal(m.sheetError({ ...m.emptySheet(), tests: Array.from({ length: 31 }, (_, i) => `T${i}`).join("\n") }), "tests");
  assert.equal(m.sheetError({ ...m.emptySheet(), tests: "x".repeat(121) }), "testLength");
  assert.equal(m.sheetError({ ...m.emptySheet(), tests: "CBC", fasting: true, fastingHours: "abc" }), "fasting");
  assert.deepEqual(m.sheetBody({ ...m.emptySheet(), tests: "CBC\nESR", labName: " Metro Lab ", fasting: true, fastingHours: "10" }),
    { tests: ["CBC", "ESR"], priority: "routine", labName: "Metro Lab", fastingHours: 10 });
});

test("AC5: a cancel needs a reason of at most 200 characters", () => {
  assert.ok(!m.cancelReasonOk("  ")); assert.ok(m.cancelReasonOk("Patient declined")); assert.ok(!m.cancelReasonOk("x".repeat(201)));
});

test("the card is mounted by one line in the tools column and follows the screen rules", () => {
  assert.match(src("components/consult/orders/ToolsAside.tsx"), /<PanelBoundary panel="labTests"><LabTestsCard key=\{entry\.id\} entry=\{entry\} \/><\/PanelBoundary>/);
  const card = src("components/consult/LabTestsCard.tsx");
  const s = ["LabTestsCard", "LabOrderPanel", "LabOrderList", "LabExternalSheet", "LabTestsParts"].map((f) => src(`components/consult/${f}.tsx`)).join("\n");
  assert.ok(!/authedFetch|API_BASE|await res\.json\(\)/.test(s), "calls go through lib/api");
  assert.ok(!/disabled=\{!?can\(/.test(s) && !/toLocale|toISOString/.test(s));
  // nothing that fetches is mounted before the entitlements answer: the fetching component is rendered only in mode "orders"
  assert.match(card, /\{mode === "orders" && <OrderPanel entry=\{entry\} \/>\}/);
  assert.match(card, /\{mode === "wait" && <Skeleton/); assert.match(card, /\{mode === "unknown" && <ErrorState[^>]*onRetry=\{retry\}/);
  assert.ok(!/api\.|useEffect/.test(card), "the card itself requests nothing: only the components it mounts after the answer do");
  assert.match(s, /e\.ctrlKey \|\| e\.metaKey\) && e\.key === "Enter"/, "Ctrl+Enter orders");
  assert.match(s, /idempotencyKey: keys\.current\.get\("place", body\)/); assert.match(s, /keys\.current\.done\("place"\)/);
  for (const state of ["<Skeleton", "<ErrorState"]) assert.ok(s.split(state).length > 3, state);
  assert.match(s, /t\("noOrders"\)/, "an empty state for a visit without orders");
  assert.match(s, /o\.cancellable && cancelling !== o\.id/, "cancel is offered only for an uncollected order");
});

test("every message key the card uses exists in the en catalogue", () => {
  const s = ["LabTestsCard", "LabOrderPanel", "LabOrderList", "LabExternalSheet", "LabTestsParts"].map((f) => src(`components/consult/${f}.tsx`)).join("\n");
  const get = (k) => k.split(".").reduce((a, p) => a?.[p], msgs);
  const used = [...s.matchAll(/\bt\("([A-Za-z.]+)"/g)].map((x) => x[1]);
  assert.ok(used.length > 40);
  for (const k of used) assert.equal(typeof get(k), "string", `labTests.${k}`);
  for (const p of m.PRIORITIES) assert.ok(get(`priorities.${p}`)); for (const d of m.DESTINATIONS) assert.ok(get(`destinations.${d}`));
  for (const k of ["test", "panel"]) assert.ok(get(`kind.${k}`));
  for (const k of ["items", "fasting", "note"]) assert.ok(get(`errors.${k}`)); for (const k of ["tests", "testLength", "fasting", "note"]) assert.ok(get(`sheetErrors.${k}`));
  for (const k of ["ordered", "partially_collected", "collected", "in_progress", "partially_resulted", "resulted", "validated", "released", "cancelled"]) assert.ok(get(`status.${k}`));
  for (const k of ["fastingSent", "fastingBlocked", "fastingNoTemplate"]) assert.ok(get(k));
});
