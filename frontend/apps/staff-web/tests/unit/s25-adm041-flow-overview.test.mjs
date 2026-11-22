// ADM-041: tile text, delta tone, report links and chart inputs of the owner flow overview.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const m = await import("../../app/(clinic)/overview/flowOverviewModel.ts");
const { REPORT_CATALOG } = await import("../../app/(clinic)/reports/libraryModel.ts");

const tile = (o) => ({ code: "wait", value: 12, unit: "min", delta: -4, target: 15, lowerIsBetter: true, n: 40, trend: [20, 18, 12], ...o });

test("AC5: a falling wait is down + better, a rising ETA accuracy is up + better, and the comparison is named", () => {
  const w = m.deltaOf(tile(), "d7");
  assert.deepEqual([w.direction, w.tone], ["down", "better"]);
  assert.equal(w.text, "4 min faster vs the previous 7 days");
  const e = m.deltaOf(tile({ code: "eta_accuracy", unit: "pct", delta: 3.2, lowerIsBetter: false, value: 82 }), "today");
  assert.deepEqual([e.direction, e.tone], ["up", "better"]);
  assert.equal(e.text, "3.2 pts higher vs the same day last week");
});
test("AC5: tone follows lowerIsBetter, not the sign; consult time has no good direction", () => {
  assert.equal(m.deltaOf(tile({ delta: 3 }), "d30").tone, "worse");
  assert.equal(m.deltaOf(tile({ lowerIsBetter: false, delta: -2, unit: "pct" }), "d30").tone, "worse");
  assert.equal(m.deltaOf(tile({ code: "consult", lowerIsBetter: null, delta: 5 }), "d7").tone, "neutral");
  assert.equal(m.deltaOf(tile({ delta: 0 }), "d7").direction, "flat");
});
test("no delta without a value or without a previous period (never 0)", () => {
  assert.equal(m.deltaOf(tile({ delta: null }), "d7"), undefined);
  assert.equal(m.deltaOf(tile({ value: null, delta: 1 }), "d7"), undefined);
  assert.equal(m.valueText(tile({ value: null }), String), "No data");
});
test("value text per unit; money goes through the formatter", () => {
  assert.equal(m.valueText(tile({ value: 12.34 }), String), "12.3 min");
  assert.equal(m.valueText(tile({ unit: "pct", value: 7.5 }), String), "7.5%");
  assert.equal(m.valueText(tile({ unit: "money", value: 1200 }), (n) => `INR ${n}`), "INR 1200");
});
test("target: over target for lower-is-better, under for higher-is-better, none without one", () => {
  assert.equal(m.targetMissed(tile({ value: 16 })), true);
  assert.equal(m.targetMissed(tile({ value: 15 })), false);
  assert.equal(m.targetMissed(tile({ unit: "pct", lowerIsBetter: false, target: 80, value: 79 })), true);
  assert.equal(m.targetMissed(tile({ target: null })), null);
  assert.equal(m.targetText(tile()), "Target under 15 min");
});
test("AC3: a tile with a report links to it on the same branch and dates; a tile without one has no link (T1-BUG-2)", () => {
  const o = { from: "2026-11-14", to: "2026-11-20", facilityId: "f-1" };
  const all = [...m.TIME_TILES, ...m.BUSINESS_TILES, "punctuality"];
  for (const code of all) {
    const href = m.reportHref(code, o);
    if (!(code in m.REPORT_KEYS)) { assert.equal(href, null, code); continue; }
    const url = new URL("https://x" + href);
    assert.equal(url.pathname, "/reports");
    assert.equal(url.searchParams.get("report"), m.REPORT_KEYS[code]);
    assert.equal(url.searchParams.get("from"), o.from);
    assert.equal(url.searchParams.get("to"), o.to);
    assert.equal(url.searchParams.get("branch"), "f-1"); // the library reads `branch`, not `facility`
    assert.equal(url.searchParams.get("facility"), null);
  }
  assert.deepEqual(all.filter((c) => !(c in m.REPORT_KEYS)), ["wait", "door_to_doctor", "consult", "eta_accuracy", "booking_response"]);
});
test("T1-BUG-2: every tile report key exists in the reports library catalogue", () => {
  const keys = new Set(REPORT_CATALOG.map((r) => r.key));
  for (const [code, key] of Object.entries(m.REPORT_KEYS)) assert.ok(keys.has(key), `${code} -> ${key}`);
});
test("T1-BUG-2: an unknown report key shows 'doesn't exist', not the first report", () => {
  const src = readFileSync(new URL("../../app/(clinic)/reports/ReportsLibrary.tsx", import.meta.url), "utf8");
  assert.match(src, /const missing = wanted !== null && catalog\.length > 0 && !catalog\.some/);
  assert.match(src, /if \(missing\) return[^\n]*notFound/);
});
test("chart inputs: hour labels, ETA gaps stay gaps, unmeasured stages are left out", () => {
  assert.deepEqual(m.waitByHourSeries([{ hour: 9, minutes: 8.5 }, { hour: 10, minutes: 12 }]), { categories: ["09:00", "10:00"], values: [8.5, 12] });
  assert.deepEqual(m.etaSeries([{ day: "2026-11-19", pct: null, n: 0 }, { day: "2026-11-20", pct: 81, n: 40 }]).values, [null, 81]);
  assert.deepEqual(m.splitRows({ checkinToVitals: null, waiting: 9, consult: 11, checkout: null }).map((r) => r.label), ["Waiting for the doctor", "Consultation"]);
});
test("the section renders no control for a role without reports:view and keeps figures on refetch", () => {
  const src = readFileSync(new URL("../../app/(clinic)/overview/FlowOverview.tsx", import.meta.url), "utf8");
  assert.match(src, /can\("reports:view"\)[^\n]*return null/);
  assert.match(src, /s\.kind === "ready" \? s :/);
  assert.match(src, /<Link[^>]*reportHref/); // StatTile renders no control: the link is the tap target
});
