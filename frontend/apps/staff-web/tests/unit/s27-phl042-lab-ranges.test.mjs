// PHL-042 reference ranges: the grid's rules (ages, limit order, band overlap), the body it sends, the server's refusals as
// the screen's own sentences, and the screen's wiring. `npm run -s test:unit`. Haemoglobin numbers are the story's examples;
// other limits are test data, not clinical ranges.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const m = await import("../../app/lib/labRanges.ts");
const src = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");
const messages = (lang) => JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${lang}/shell.json`, import.meta.url), "utf8")).lab;
const row = (over) => ({ ...m.emptyRow(), ...over });
const sent = (v) => JSON.parse(JSON.stringify(v)); // what goes on the wire: blank fields are left out
const child = row({ fromN: "0", toN: "12", low: "11.0", high: "14.5" });
const women = row({ sex: "F", fromN: "12", low: "12.0", high: "15.5" });
const men = row({ sex: "M", fromN: "12", low: "13.0", high: "17.0" });

test("ages: days, months and years go to whole days and come back as the unit the lab typed", () => {
  assert.equal(m.toDays(28, "d"), 28);
  assert.equal(m.toDays(1, "m"), 30);
  assert.equal(m.toDays(12, "y"), 4383);
  assert.deepEqual(m.ageParts(4383), { n: 12, unit: "y" });
  assert.deepEqual(m.ageParts(365), { n: 1, unit: "y" });
  assert.deepEqual(m.ageParts(61), { n: 2, unit: "m" });
  assert.deepEqual(m.ageParts(28), { n: 28, unit: "d" }, "a neonate band stays in days");
  assert.deepEqual(m.ageParts(0), { n: 0, unit: "d" });
  for (const n of [1, 2, 5, 12, 18, 65, 120]) assert.deepEqual(m.ageParts(m.toDays(n, "y")), { n, unit: "y" }, `${n} years`);
});

test("AC2 the child band and the adult bands of the story are a valid set and become the body the server takes", () => {
  assert.equal(m.rangesProblem([child, women, men]), null);
  assert.deepEqual(sent(m.bandOf(child)), { sex: "any", ageMinDays: 0, ageMaxDays: 4383, low: 11, high: 14.5 });
  assert.deepEqual(sent(m.bandOf(women)), { sex: "F", ageMinDays: 4383, low: 12, high: 15.5 }, "no upper age, and never a null: the contract has no nullable field");
  assert.equal(m.bandOf(row({ text: "  Negative " })).text, "Negative");
  assert.equal(m.bandOf(row({ low: "-2.5", high: "2.5" })).low, -2.5, "a limit may be below zero");
});

test("a stored set opens in the grid and goes back unchanged; a test without ranges starts with one blank band", () => {
  const stored = { version: 3, effectiveFrom: "2026-11-20T08:00:00Z", ranges: [
    { sex: "any", ageMinDays: 0, ageMaxDays: 28, low: 1, high: 2, criticalLow: 0.5, criticalHigh: 3, text: null },
    { sex: "F", ageMinDays: 28, ageMaxDays: 4383, low: 11, high: 14.5, criticalLow: null, criticalHigh: null, text: "note" },
    { sex: "M", ageMinDays: 4383, ageMaxDays: null, low: null, high: 17, criticalLow: null, criticalHigh: null, text: null } ] };
  const rows = m.rowsOf(stored);
  assert.deepEqual(rows[0], { sex: "any", fromN: "0", fromUnit: "d", toN: "28", toUnit: "d", low: "1", high: "2", criticalLow: "0.5", criticalHigh: "3", text: "" });
  assert.deepEqual([rows[1].fromN, rows[1].fromUnit, rows[1].toN, rows[1].toUnit], ["28", "d", "12", "y"]);
  assert.deepEqual([rows[2].fromN, rows[2].fromUnit, rows[2].toN], ["12", "y", ""]);
  assert.deepEqual(sent(rows.map(m.bandOf)), stored.ranges.map((b) => Object.fromEntries(Object.entries(b).filter(([, v]) => v !== null))));
  assert.equal(m.rangesProblem(rows), null);
  assert.deepEqual(m.rowsOf({ version: 0, ranges: [] }), [m.emptyRow()]);
});

test("band overlap is found before the request, and names both bands", () => {
  assert.deepEqual(m.rangesProblem([child, women, row({ sex: "F", fromN: "60", low: "11", high: "15" })]), { error: "overlap", row: 2, other: 3 });
  assert.deepEqual(m.rangesProblem([women, row({ fromN: "20", toN: "30", low: "1", high: "2" })]), { error: "overlap", row: 1, other: 2 }, "'any' covers women too");
  assert.deepEqual(m.rangesProblem([child, row({ sex: "M", fromN: "4382", fromUnit: "d", toN: "13", low: "1", high: "2" })]), { error: "overlap", row: 1, other: 2 }, "one shared day");
  assert.equal(m.rangesProblem([child, row({ sex: "M", fromN: "4383", fromUnit: "d", toN: "13", low: "1", high: "2" })]), null, "bands that meet do not overlap");
  assert.equal(m.rangesProblem([men, women]), null, "two sexes over the same ages");
  assert.equal(m.bandsOverlap(m.bandOf(men), m.bandOf(men)), true);
  assert.equal(m.bandsOverlap(m.bandOf(row({ fromN: "13", toN: "14" })), m.bandOf(child)), false, "order does not matter");
});

test("a row is checked: ages, numbers, the order of the limits, and a band with nothing in it", () => {
  const ok = { low: "1", high: "2" };
  assert.deepEqual(m.rangesProblem([]), { error: "none", row: 0 });
  for (const fromN of ["", "abc", "1.5", "-1", "123456"]) assert.deepEqual(m.rangesProblem([row({ ...ok, fromN })]), { error: "age", row: 1 }, fromN);
  assert.deepEqual(m.rangesProblem([row({ ...ok, toN: "x" })]), { error: "age", row: 1 });
  assert.deepEqual(m.rangesProblem([row({ ...ok, fromN: "200" })]), { error: "age", row: 1 }, "beyond 150 years");
  assert.deepEqual(m.rangesProblem([row({ ...ok, toN: "200" })]), { error: "age", row: 1 });
  assert.deepEqual(m.rangesProblem([child, row({ ...ok, sex: "M", fromN: "12", toN: "12" })]), { error: "ageOrder", row: 2 });
  assert.deepEqual(m.rangesProblem([row({ ...ok, fromN: "28", fromUnit: "d", toN: "7", toUnit: "d" })]), { error: "ageOrder", row: 1 });
  for (const low of ["1,5", "abc", "1e3", "1.1234567"]) assert.deepEqual(m.rangesProblem([row({ low, high: "2" })]), { error: "number", row: 1 }, low);
  assert.deepEqual(m.rangesProblem([row({ low: "15.5", high: "12.0" })]), { error: "order", row: 1 });
  assert.deepEqual(m.rangesProblem([row({ low: "12", high: "15", criticalLow: "12" })]), { error: "order", row: 1 }, "critical low equal to low");
  assert.deepEqual(m.rangesProblem([row({ low: "12", high: "15", criticalHigh: "15" })]), { error: "order", row: 1 }, "critical high equal to high");
  assert.deepEqual(m.rangesProblem([row({ high: "15", criticalLow: "16" })]), { error: "order", row: 1 });
  assert.deepEqual(m.rangesProblem([row({ criticalLow: "9", criticalHigh: "9" })]), { error: "order", row: 1 });
  assert.equal(m.rangesProblem([row({ low: "5", high: "5" })]), null, "low and high may be equal");
  assert.equal(m.rangesProblem([row({ low: "3.5", high: "5.1", criticalLow: "2.5", criticalHigh: "6.0" })]), null, "AC3: potassium with its critical limits");
  assert.equal(m.rangesProblem([row({ high: "200" })]), null, "one limit is enough");
  assert.equal(m.rangesProblem([row({ text: "Negative" })]), null, "a text range alone is enough");
  assert.deepEqual(m.rangesProblem([row({})]), { error: "empty", row: 1 });
  assert.deepEqual(m.rangesProblem([row({ text: "  " })]), { error: "empty", row: 1 });
});

test("the server's 422 answers become the screen's own sentences, never a code", () => {
  assert.deepEqual(m.serverProblem(422, { code: "OVERLAPPING_BANDS", bands: [0, 2] }), { error: "overlap", row: 1, other: 3 });
  assert.deepEqual(m.serverProblem(422, { code: "INVALID_BAND", band: 1 }), { error: "invalid", row: 2 });
  assert.equal(m.serverProblem(422, { code: "SOMETHING_ELSE" }), null);
  assert.equal(m.serverProblem(403, { code: "OVERLAPPING_BANDS", bands: [0, 1] }), null);
  assert.equal(m.serverProblem(422, { code: "OVERLAPPING_BANDS" }), null);
  const s = src("components/lab/LabRanges.tsx");
  assert.match(s, /serverProblem\(err\.status, err\.problem\)/);
  assert.match(s, /t\(`ranges\.errors\.\$\{p\.error\}`/);
});

test("the screen: the grid and every control that changes ranges are absent without lab:validate; loading, empty and error states exist", () => {
  const s = src("components/lab/LabRanges.tsx");
  assert.match(s, /const canEdit = can\("lab:validate"\)/);
  const [viewer, editor] = [s.slice(s.indexOf("{!canEdit ? ("), s.indexOf(") : (\n              <form")), s.slice(s.indexOf(") : (\n              <form"))];
  assert.ok(viewer.length > 100 && editor.length > 100, "the two branches were found");
  for (const control of ['t("ranges.add")', 't("ranges.save")', 't("ranges.remove")', "<input", "<select", "<form"]) {
    assert.ok(editor.includes(control), `${control} is in the lab:validate branch`);
    assert.ok(!viewer.includes(control), `${control} is absent for a viewer`);
  }
  assert.ok(!/disabled=\{!canEdit\}/.test(s), "absent, not disabled");
  for (const state of ["<Skeleton", "<EmptyState", "<ErrorState"]) assert.ok(s.includes(state), state);
  assert.match(s, /api\.get<RangeSet>\(`\/lab\/tests\/\$\{test\.id\}\/ranges`\)/);
  assert.match(s, /api\.put<RangeSet>\(`\/lab\/tests\/\$\{test\.id\}\/ranges`, \{ ranges: rows\.map\(bandOf\) \}\)/);
  assert.ok(s.indexOf("rangesProblem(rows)") < s.indexOf("api.put"), "the grid is checked before the request");
  assert.match(s, /formatClinicDate\(set\.effectiveFrom\)/, "the date is the clinic's, not the browser's");
  assert.ok(!/toLocale|toISOString/.test(s));
  // mounted from the catalogue for everyone who can read it (the page gate has checked lab:view and the lab module)
  const cat = src("components/lab/LabCatalogue.tsx");
  assert.match(cat, /\{ranges && <LabRanges test=\{ranges\} onClose=\{\(\) => setRanges\(null\)\} \/>\}/);
  const open = cat.split("\n").find((l) => l.includes('t("ranges.open")}</button>'));
  assert.ok(open && !open.includes("canEdit &&"), "reading the ranges does not need lab:catalogue");
});

test("every string of the range grid exists in English and Hindi", () => {
  const keys = (d, p = "") => Object.entries(d).flatMap(([k, v]) => (typeof v === "object" ? keys(v, `${p}${k}.`) : [`${p}${k}`]));
  const en = keys(messages("en")), hi = keys(messages("hi"));
  assert.deepEqual(hi.sort(), en.sort());
  const s = src("components/lab/LabRanges.tsx");
  const used = [...s.matchAll(/\bt\("([a-zA-Z.]+)"/g)].map((x) => x[1]);
  assert.ok(used.length > 15);
  for (const k of used) assert.ok(en.includes(k), `missing message shell.lab.${k}`);
  for (const e of ["none", "age", "ageOrder", "number", "order", "empty", "overlap", "invalid"]) assert.ok(en.includes(`ranges.errors.${e}`), e);
  for (const v of m.SEXES) assert.ok(en.includes(`ranges.sexOption.${v}`), v);
  for (const u of m.AGE_UNITS) { assert.ok(en.includes(`ranges.ageUnit.${u}`), u); assert.ok(en.includes(`ranges.age.${u}`), u); }
  for (const k of ["low", "high", "criticalLow", "criticalHigh", "from", "to", "fromUnit", "toUnit"]) assert.ok(en.includes(`ranges.${k}`), k);
  const hiRanges = messages("hi").ranges, enRanges = messages("en").ranges;
  for (const k of ["empty", "save", "add", "versionHint"]) assert.notEqual(hiRanges[k], enRanges[k], `${k} is translated`);
});
