// ADM-049: the Sales, Redemption and Expiry-loss tabs of the package report in the reports library. Pure helpers run directly; the
// screen and the tab strip are checked as source (no browser here), and every code the backend sends is checked against en and hi.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { REPORT_CATALOG, defaultRange, presetRange, rangeProblem, reportHref, tabsOf, visibleReports } from "../../app/(clinic)/reports/libraryModel.ts";

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), "utf8");
const screen = read("../../app/(clinic)/reports/ReportsLibrary.tsx");
const tabStrip = read("../../app/(clinic)/reports/ReportTabs.tsx");
const java = read("../../../../../backend/src/main/java/com/nabd/hms/reports/PackageReports.java");
const en = JSON.parse(read("../../../../packages/i18n/messages/en/shell.json")).reportsLibrary;
const hi = JSON.parse(read("../../../../packages/i18n/messages/hi/shell.json")).reportsLibrary;
const def = (key) => REPORT_CATALOG.find((r) => r.key === key);
const TABS = ["package-liability", "package-sales", "package-redemption", "package-expiry-loss"];

test("the package report holds four tabs, whichever of them is open; other reports have none", () => {
  for (const key of TABS) assert.deepEqual(tabsOf(def(key), REPORT_CATALOG).map((r) => r.key), TABS, key);
  assert.deepEqual(tabsOf(def("takings"), REPORT_CATALOG), []);
  // the library list keeps one entry for the four
  const listed = REPORT_CATALOG.filter((r) => !r.tabOf).map((r) => r.key);
  assert.equal(listed.length, 10);
  assert.ok(listed.includes("package-liability"));
  for (const key of TABS.slice(1)) assert.ok(!listed.includes(key), key);
});

test("a role without packages:view gets neither the entry nor a tab (absent, not disabled)", () => {
  const catalog = visibleReports((p) => p !== "packages:view");
  for (const key of TABS) assert.ok(!catalog.some((r) => r.key === key), key);
  assert.deepEqual(tabsOf(def("takings"), catalog), []);
  for (const key of TABS) assert.equal(def(key).requires, "packages:view");
});

test("redemption opens on the last 12 months so sales old enough to judge are in range; the rest open on 30 days", () => {
  const now = new Date(2026, 10, 23); // Mon 23 Nov 2026, local
  assert.equal(def("package-redemption").defaultDays, 365);
  assert.deepEqual(defaultRange(def("package-redemption").defaultDays, now), { from: "2025-11-24", to: "2026-11-23" });
  assert.equal(rangeProblem("2025-11-24", "2026-11-23"), null); // inside the 366-day rule
  for (const key of ["package-sales", "package-expiry-loss", "package-liability", "takings"]) {
    assert.deepEqual(defaultRange(def(key).defaultDays, now), presetRange("d30", now), key);
  }
});

test("a tab link carries only the dates the user chose, so each tab falls back to its own default range", () => {
  assert.equal(reportHref("package-redemption", { from: undefined, to: undefined, branch: undefined }), "/reports?report=package-redemption");
  assert.equal(reportHref("package-sales", { from: "2026-10-01", to: "2026-10-31" }), "/reports?report=package-sales&from=2026-10-01&to=2026-10-31");
  assert.match(screen, /const chosen = \{ from: params\.get\("from"\) \?\? undefined, to: params\.get\("to"\) \?\? undefined, branch: params\.get\("branch"\) \?\? undefined \}/);
  assert.match(screen, /<ReportTabs tabs=\{tabsOf\(def, catalog\)\} current=\{def\.key\} filters=\{chosen\} \/>/);
  assert.equal(screen.match(/<ReportTabs /g).length, 1);
});

test("screen: tab reports are not library entries, the open tab is marked, and the strip fetches nothing itself", () => {
  assert.match(screen, /catalog\.filter\(\(r\) => r\.group === g && !r\.tabOf\)/);
  assert.match(screen, /r\.key === \(def\.tabOf \?\? def\.key\) \? s\.itemOn : s\.item/);
  assert.match(tabStrip, /if \(tabs\.length === 0\) return null/);
  assert.match(tabStrip, /aria-current=\{r\.key === current \? "page" : undefined\}/);
  assert.match(tabStrip, /aria-label=\{t\("tabsLabel"\)\}/);
  assert.doesNotMatch(tabStrip, /\bfetch\(|api\.|useQuery|disabled/);
  // the frame renders through the library's own loading, empty, error and export paths: the tabs add no second path
  assert.doesNotMatch(tabStrip, /exports|Skeleton/);
});

test("every report, tab, KPI, column and flag the package reports send has an English and a Hindi string", () => {
  const kpis = new Set([...java.matchAll(/new Kpi\("(\w+)"/g)].map((m) => m[1]));
  const colKeys = new Set();
  for (let at = java.indexOf("cols("); at >= 0; at = java.indexOf("cols(", at + 1)) {
    let depth = 0, end = at + 4;
    for (; end < java.length; end++) { if (java[end] === "(") depth++; if (java[end] === ")" && --depth === 0) break; }
    [...java.slice(at, end).matchAll(/"([^"]*)"/g)].map((m) => m[1]).forEach((l, i) => i % 3 === 0 && colKeys.add(l));
  }
  const flags = new Set([...java.matchAll(/"((?:low_|no_)?[a-z]+_(?:redemption|consent))"/g)].map((m) => m[1]));
  assert.equal(kpis.size, 12, [...kpis].join());
  assert.deepEqual([...flags].sort(), ["low_redemption", "marketing_consent", "no_marketing_consent"]);
  for (const c of ["package", "units", "salesValue", "desk", "whatsapp", "sessSold", "sessUsed", "redemption", "matureUnits", "lowUnits", "flag", "patient", "lapsedOn", "lost", "lostValue", "winBack"]) {
    assert.ok(colKeys.has(c), `column ${c}`);
  }
  for (const catalog of [en, hi]) {
    for (const key of TABS) {
      assert.ok(catalog[`report_${key}`], `report ${key}`);
      assert.ok(catalog[`tab_${key}`], `tab ${key}`);
    }
    assert.ok(catalog.tabsLabel);
    for (const k of kpis) assert.ok(catalog[`kpi_${k}`], `kpi ${k}`);
    for (const c of colKeys) assert.ok(catalog[`col_${c}`], `col ${c}`);
    for (const f of flags) assert.ok(catalog[`flag_${f}`], `flag ${f}`);
    for (const key of ["package-sales", "package-redemption"]) assert.ok(catalog[`chartTitle_${key}`], `chart ${key}`);
  }
  assert.equal(en.flag_low_redemption, "Low redemption"); // the acceptance criterion's wording
  for (const k of ["report_package-sales", "tab_package-redemption", "kpi_lapsedSessions", "col_lost", "flag_low_redemption", "flag_no_marketing_consent"]) {
    assert.notEqual(hi[k], en[k], k);
  }
  assert.match(screen, /c\.key === "flag" \|\| c\.key === "winBack"\) return tx\("flag", String\(v\), words\(v\)\)/);
});
