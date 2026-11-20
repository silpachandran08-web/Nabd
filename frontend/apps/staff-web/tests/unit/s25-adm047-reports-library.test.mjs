// ADM-047: the reports library screen. Pure helpers (libraryModel.ts) run directly; the screen itself is checked as source (no
// browser here) and against the backend registry so a report added on one side without the other fails.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { GROUPS, MAX_DAYS, REPORT_CATALOG, decodeBase64, framePath, presetRange, rangeProblem, reportHref, visibleReports, words } from "../../app/(clinic)/reports/libraryModel.ts";

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), "utf8");
const screen = read("../../app/(clinic)/reports/ReportsLibrary.tsx");
const java = read("../../../../../backend/src/main/java/com/nabd/hms/reports/ReportLibraryService.java");
const en = JSON.parse(read("../../../../packages/i18n/messages/en/shell.json")).reportsLibrary;
const hi = JSON.parse(read("../../../../packages/i18n/messages/hi/shell.json")).reportsLibrary;

test("AC1: Money, Patients and Team hold the spec's reports, in order", () => {
  const by = (g) => REPORT_CATALOG.filter((r) => r.group === g).map((r) => r.key);
  assert.deepEqual(GROUPS, ["money", "patients", "team"]);
  assert.deepEqual(by("money"), ["leakage", "takings", "package-liability", "tax-summary"]);
  assert.deepEqual(by("patients"), ["patient-sources", "patient-retention", "no-show-watch"]);
  assert.deepEqual(by("team"), ["staff-activity", "doctor-productivity", "doctor-on-time"]);
  assert.equal(new Set(REPORT_CATALOG.map((r) => r.key)).size, 10);
});

test("the catalogue matches the backend registry (keys, groups, required grant)", () => {
  const reg = [...java.matchAll(/reg\("([a-z-]+)", "([a-z]+)", "[^"]+",\s*"(?:[^"\\]|\\.)*"(?:\s*\+[^;]*?)?,\s*(null|"[a-z:]+")\)/g)].map((m) => [m[1], m[2], m[3] === "null" ? undefined : m[3].slice(1, -1)]);
  assert.deepEqual(reg, REPORT_CATALOG.map((r) => [r.key, r.group, r.requires]));
});

test("a report behind a grant the caller lacks is absent, not disabled", () => {
  assert.equal(visibleReports(() => true).length, 10);
  const keys = visibleReports((p) => p !== "packages:view").map((r) => r.key);
  assert.equal(keys.length, 9);
  assert.ok(!keys.includes("package-liability"));
});

test("range presets and the 366-day rule", () => {
  const now = new Date(2026, 10, 18); // Wed 18 Nov 2026, local
  assert.deepEqual(presetRange("d7", now), { from: "2026-11-12", to: "2026-11-18" });
  assert.deepEqual(presetRange("d30", now), { from: "2026-10-20", to: "2026-11-18" });
  assert.deepEqual(presetRange("month", now), { from: "2026-11-01", to: "2026-11-18" });
  assert.equal(MAX_DAYS, 366);
  assert.equal(rangeProblem("2026-01-01", "2026-12-31"), null);
  assert.equal(rangeProblem("2024-01-01", "2024-12-31"), null); // 366 days in a leap year
  assert.equal(rangeProblem("2025-01-01", "2026-01-02"), "long"); // 367
  assert.equal(rangeProblem("2026-02-02", "2026-02-01"), "order");
  assert.equal(rangeProblem("", "2026-02-01"), "invalid");
});

test("deep links and API paths carry the report key with the same filters", () => {
  assert.equal(reportHref("leakage", { from: "2026-11-01", to: "2026-11-18", branch: "b1" }), "/reports?report=leakage&from=2026-11-01&to=2026-11-18&branch=b1");
  assert.equal(reportHref("takings"), "/reports?report=takings");
  assert.equal(framePath({ key: "takings", from: "2026-11-01", to: "2026-11-18" }), "/reports/takings?from=2026-11-01&to=2026-11-18");
  assert.equal(framePath({ key: "takings", from: "a", to: "b", cursor: "x y", branch: "b1", threshold: "200" }), "/reports/takings?from=a&to=b&branch=b1&cursor=x+y&threshold=200");
});

test("export answer decodes to the file bytes; server codes read as words", () => {
  assert.deepEqual([...new Uint8Array(decodeBase64(Buffer.from([0x50, 0x4b, 0x03, 0x04, 0xff]).toString("base64")))], [0x50, 0x4b, 0x03, 0x04, 0xff]);
  assert.equal(words("checked_in"), "checked in");
});

test("AC4 (screen): one audited export path, absent without reports:export; the kit's client CSV is off", () => {
  assert.match(screen, /from "@nabd\/ui"/);
  assert.match(screen, /csv=\{false\}/);
  assert.match(screen, /can\("reports:export"\)/);
  assert.match(screen, /`\/reports\/\$\{def!\.key\}\/exports`/);
  assert.doesNotMatch(screen, /\bfetch\(|authedFetch|API_BASE|disabled=\{!can/);
});

test("AC2 (screen): a KPI opens the rows behind it, 50 more at a time, and a refetch keeps the report on screen", () => {
  assert.match(screen, /onClick=\{openRows\}/);
  assert.match(screen, /fetchNextPage/);
  assert.match(screen, /getNextPageParam: \(last\) => last\.nextCursor/);
  assert.match(screen, /placeholderData: keepPreviousData/);
  assert.match(screen, /q\.isPending && <Skeleton/); // the skeleton only on the first load
});

test("every report, KPI and column the backend can send has an English and a Hindi string", () => {
  const kpis = new Set([...java.matchAll(/new Kpi\("(\w+)"/g)].map((m) => m[1]));
  const colKeys = new Set();
  for (let at = java.indexOf("cols("); at >= 0; at = java.indexOf("cols(", at + 1)) {
    if (java[at - 1] === " " && java.slice(at - 12, at).includes("static List")) continue; // the helper's own signature
    let depth = 0, end = at + 4;
    for (; end < java.length; end++) { if (java[end] === "(") depth++; if (java[end] === ")" && --depth === 0) break; }
    const lits = [...java.slice(at, end).matchAll(/"([^"]*)"/g)].map((m) => m[1]);
    lits.forEach((l, i) => i % 3 === 0 && colKeys.add(l));
  }
  assert.ok(kpis.size >= 25 && colKeys.size >= 25, `${kpis.size} kpis, ${colKeys.size} columns`);
  for (const catalog of [en, hi]) {
    for (const r of REPORT_CATALOG) assert.ok(catalog[`report_${r.key}`], r.key);
    for (const k of kpis) assert.ok(catalog[`kpi_${k}`], `kpi ${k}`);
    for (const c of colKeys) assert.ok(catalog[`col_${c}`], `col ${c}`);
    for (const k of ["table", "csv", "loading", "empty", "error", "retry", "noPermission", "other", "tooMany", "allZero", "singlePoint", "keys", "noValue"]) assert.ok(catalog[`chart_${k}`], k);
  }
  assert.deepEqual(Object.keys(hi).sort(), Object.keys(en).sort());
  assert.notEqual(hi.report_takings, en.report_takings);
});
