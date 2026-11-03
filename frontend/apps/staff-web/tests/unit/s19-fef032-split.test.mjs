// FEF-032: the last god components are split. AC1 measured on the seven of doc 04 §1.3 (consult, nursing, patients,
// packages, setup, staff, login): every file <= 400 lines and no component / hook with more than 10 useState.
// AC2 (static half): /setup/charges is its own route whose import graph holds the charges tab only; the real chunk is
// checked by QA in the next build (.next/diagnostics/route-bundle-stats.json, scripts/perf-budget.mjs bundle).
// Also: 0 inline styles left in the split screens, old /setup?tab= links map onto the tab routes, the sidebar follows them.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { TABS, isSetupTab, isTabRoute, tabHref } from "../../app/(clinic)/setup/setupTabs.ts";
import { NAV, isActive } from "../../app/shell/navRegistry.ts";

const APP = fileURLToPath(new URL("../../app/", import.meta.url));
const walk = (d) => readdirSync(d).flatMap((n) => (statSync(join(d, n)).isDirectory() ? walk(join(d, n)) : [join(d, n)]));
const code = (d) => walk(join(APP, d)).filter((f) => /\.tsx?$/.test(f));
const lines = (f) => readFileSync(f, "utf8").split("\n").length;

/** Split a file into top-level function bodies (a component or a hook) and count `useState` calls in each (as FEF-031). */
function stateCountsPerFn(src) {
  const starts = [...src.matchAll(/^(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+(\w+)/gm)].map((m) => ({ name: m[1], at: m.index }));
  return starts.map((s, i) => [s.name, (src.slice(s.at, starts[i + 1]?.at ?? src.length).match(/\buseState\b\s*[<(]/g) ?? []).length]);
}

const TAB_ROUTES = TABS.map((t) => t.key).filter(isTabRoute);
const SETUP = "(clinic)/setup";
const SEVEN = {
  consult: [...code("(clinic)/consult"), ...code("components/consult")],
  nursing: [...code("(clinic)/nursing"), ...code("components/nursing")],
  patients: code("(clinic)/patients"),
  packages: code("(clinic)/packages"),
  setup: [...TAB_ROUTES.map((k) => join(APP, SETUP, k, "page.tsx")), ...["page.tsx", "layout.tsx", "setupTab.tsx", "setupTabs.ts"].map((f) => join(APP, SETUP, f))],
  staff: code("(clinic)/staff"),
  login: code("(auth)/login"),
};

test("AC1: the seven god components — no file over 400 lines, no component or hook over 10 useState", () => {
  for (const [name, files] of Object.entries(SEVEN)) {
    assert.ok(files.length > 0, `${name}: no files found`);
    for (const f of files) {
      assert.ok(lines(f) <= 400, `${name}: ${f} is ${lines(f)} lines`);
      for (const [fn, n] of stateCountsPerFn(readFileSync(f, "utf8"))) assert.ok(n <= 10, `${name}: ${f} ${fn} has ${n} useState`);
    }
  }
});

test("AC1: the old single pages are gone or thin", () => {
  for (const p of ["(clinic)/setup/page.tsx", "(clinic)/packages/page.tsx", "(clinic)/staff/page.tsx", "(auth)/login/page.tsx"]) assert.ok(lines(join(APP, p)) < 300, `${p} is ${lines(join(APP, p))} lines`);
  assert.doesNotMatch(readFileSync(join(APP, "(auth)/login/page.tsx"), "utf8"), /\buseState\b/, "login keeps its state in the reducer");
});

test("FEF-032.1: 0 inline styles in packages, setup tabs, staff and login", () => {
  const files = [...SEVEN.packages, ...SEVEN.setup, ...SEVEN.staff, ...SEVEN.login];
  assert.deepEqual(files.filter((f) => /\bstyle=\{/.test(readFileSync(f, "utf8"))).map((f) => f.replace(APP, "")), []);
});

/** Local (relative) import graph of one file: every .ts/.tsx/.css it reaches. Packages (@nabd/*, react, next) are not followed. */
function graph(entry) {
  const seen = new Set();
  const visit = (f) => {
    if (seen.has(f)) return;
    seen.add(f);
    if (!/\.tsx?$/.test(f)) return;
    for (const m of readFileSync(f, "utf8").matchAll(/^\s*import\s+(?:type\s+)?(?:[^"';]*?\s+from\s+)?["'](\.[^"']+)["']/gm)) {
      if (/^\s*import\s+type\b/.test(m[0])) continue; // erased at build time
      const base = resolve(dirname(f), m[1]);
      const hit = [base, `${base}.tsx`, `${base}.ts`, join(base, "index.ts")].find((p) => existsSync(p) && statSync(p).isFile());
      assert.ok(hit, `${f}: cannot resolve ${m[1]}`);
      visit(hit);
    }
  };
  visit(entry);
  return [...seen].map((f) => f.replace(APP, ""));
}

// Modules only one tab uses: none of them may reach the charges route.
const OTHER_TAB_MODULES = ["components/setup/PharmacyPanel.tsx", "components/setup/PatientImport.tsx", "components/setup/LicenceTable.tsx", "components/setup/RegionalCalendar.tsx",
  "components/setup/WhatsAppHealth.tsx", "components/setup/PrivacyNoticePanel.tsx", "components/setup/PaymentsCard.tsx", "components/setup/AbdmCard.tsx", "components/setup/PrintPaperPanel.tsx",
  "(clinic)/setup/WhatsAppTemplates.tsx", "(clinic)/setup/DisplayDevices.tsx", "(clinic)/setup/TimezoneSelect.tsx"];

test("AC2: every setup tab except Checklist / Departments is its own route segment under the shared setup layout", () => {
  assert.equal(TAB_ROUTES.length, 15);
  for (const k of TAB_ROUTES) assert.ok(existsSync(join(APP, SETUP, k, "page.tsx")), k);
  assert.ok(existsSync(join(APP, SETUP, "page.tsx")) && existsSync(join(APP, SETUP, "layout.tsx")));
  // the wizard (FIN-017 price step), Departments and Booking rules keep their own routes, and the layout leaves them unframed
  for (const p of ["wizard", "departments", "workflows"]) {
    assert.ok(existsSync(join(APP, SETUP, p, "page.tsx")), p);
    assert.equal(isTabRoute(p), false, p);
  }
  assert.match(readFileSync(join(APP, SETUP, "layout.tsx"), "utf8"), /if \(segment !== null && !isTabRoute\(segment\)\) return <>\{children\}<\/>;/);
  assert.match(readFileSync(join(APP, "(clinic)/setup/wizard/page.tsx"), "utf8"), /PriceTemplateStep/);
});

test("AC2: /setup/charges reaches only the charges tab; the shared frame reaches no tab", () => {
  const charges = graph(join(APP, SETUP, "charges/page.tsx"));
  assert.ok(charges.includes("components/setup/PricingPanel.tsx"), "the charges tab's own panel");
  for (const k of TAB_ROUTES.filter((x) => x !== "charges")) assert.ok(!charges.includes(`${SETUP}/${k}/page.tsx`), `charges reaches the ${k} tab`);
  for (const m of OTHER_TAB_MODULES) assert.ok(!charges.includes(m), `charges reaches ${m}`);
  for (const entry of ["layout.tsx", "page.tsx"]) {
    const g = graph(join(APP, SETUP, entry));
    for (const m of [...OTHER_TAB_MODULES, "components/setup/PricingPanel.tsx"]) assert.ok(!g.includes(m), `setup/${entry} reaches ${m}`);
    assert.ok(!g.some((f) => TAB_ROUTES.some((k) => f === `${SETUP}/${k}/page.tsx`)), `setup/${entry} imports a tab route`);
  }
  // no file anywhere imports a tab route (that would pull it into another chunk)
  const tabImport = new RegExp(`from ["'][^"']*/(${TAB_ROUTES.join("|")})/page["']`);
  for (const f of walk(APP).filter((x) => /\.tsx?$/.test(x))) assert.doesNotMatch(readFileSync(f, "utf8"), tabImport, f);
});

test("old /setup?tab=<key> links: each known tab maps to its route; unknown keys stay on the checklist", () => {
  assert.equal(tabHref("charges"), "/setup/charges");
  assert.equal(tabHref("licences"), "/setup/licences");
  assert.equal(tabHref("subscription"), "/setup/subscription");
  assert.equal(tabHref("checklist"), "/setup");
  assert.equal(tabHref("departments"), "/setup?tab=departments");
  assert.ok(isSetupTab("shifts") && isSetupTab("data") && !isSetupTab("nope") && !isSetupTab(null));
  const home = readFileSync(join(APP, SETUP, "page.tsx"), "utf8");
  assert.match(home, /router\.replace\(moved/, "/setup?tab=<key> is replaced by the tab route");
});

test("sidebar: Compliance & Audit is active on /setup/licences and on the old link; Clinic Setup is not", () => {
  const item = (id) => NAV.owner.find((i) => i.id === id);
  const setup = item("setup"), compliance = item("compliance");
  assert.equal(compliance.href, "/setup?tab=licences", "existing link kept");
  for (const [p, t] of [["/setup/licences", null], ["/setup", "licences"]]) {
    assert.equal(isActive(compliance, p, t), true, `${p} ${t}`);
    assert.equal(isActive(setup, p, t), false, `${p} ${t}`);
  }
  for (const [p, t] of [["/setup", null], ["/setup/charges", null], ["/setup/wizard", null], ["/setup", "departments"]]) {
    assert.equal(isActive(setup, p, t), true, `${p} ${t}`);
    assert.equal(isActive(compliance, p, t), false, `${p} ${t}`);
  }
  assert.equal(isActive(setup, "/setup/subscription", null), false, "Plan tab was never the Setup item");
});
