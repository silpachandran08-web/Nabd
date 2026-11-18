// PHL-008 stock alerts: tab/badge rules and the screen's wiring (gates, states, absent controls). `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const s = await import("../../app/lib/stockAlerts.ts");
const src = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");

test("three tabs and the four acknowledge actions the API accepts", () => {
  assert.deepEqual(s.TABS.map((t) => t.id), ["expiry", "low", "dead"]);
  assert.deepEqual(s.ACTIONS.map((a) => a.id), ["returned", "discounted", "quarantined", "none"]);
  for (const t of ["expired", "expiry_30", "expiry_60", "expiry_90", "low_stock", "dead_stock"]) assert.ok(s.TYPE_LABEL[t], t);
  for (const a of ["quarantine", "discount", "return_to_supplier"]) assert.ok(s.SUGGESTED_LABEL[a], a);
});

test("nav badge = every open alert; nothing shown when unknown or zero", () => {
  assert.equal(s.badgeCount({ expiry: 2, low: 1, dead: 3 }), 6);
  assert.equal(s.badgeCount({ expiry: 0, low: 0, dead: 0 }), undefined);
  assert.equal(s.badgeCount(undefined), undefined);
  assert.equal(s.badgeCount(null), undefined);
});

test("days to expiry read as words", () => {
  assert.equal(s.daysText(-1), "Expired 1 day ago");
  assert.equal(s.daysText(-5), "Expired 5 days ago");
  assert.equal(s.daysText(0), "Expires today");
  assert.equal(s.daysText(12), "In 12 days");
  assert.equal(s.daysText(null), "");
});

test("bulk acknowledge keeps only the failed ids ticked", () => {
  assert.deepEqual(s.remaining(["a", "b", "c"], ["a", "c"]), ["b"]);
  assert.deepEqual(s.remaining(["a"], []), ["a"]);
});

test("screen: pharmacy:view gate, acknowledge absent without pharmacy:edit, loading/empty/error states, CSV through the api client", () => {
  const page = src("(clinic)/pharmacy/insights/page.tsx");
  assert.match(page, /<AccessGate permission="pharmacy:view"/);
  assert.doesNotMatch(page, /PlannedScreen/);
  const ui = src("components/setup/StockAlerts.tsx");
  assert.match(ui, /const canAck = can\("pharmacy:edit"\)/);
  assert.match(ui, /\{canAck && \(/, "the acknowledge bar renders only for pharmacy:edit");
  assert.doesNotMatch(ui, /disabled=\{!canAck/, "absent, not disabled");
  for (const st of ["ErrorState", "Skeleton", "EmptyState"]) assert.match(ui, new RegExp(`<${st}`), st);
  assert.match(ui, /format=csv`, \{ as: "text" \}/);
  assert.doesNotMatch(ui, /\bfetch\(/);
  const css = src("components/setup/stockAlerts.module.css");
  assert.match(css, /min-block-size: 44px/);
  assert.doesNotMatch(css, /margin-(left|right)|padding-(left|right)|\bleft:|\bright:/, "logical properties only");
});

test("pharmacist badge reads the alert counts only for pharmacy:view holders", () => {
  const hook = src("shell/useNavBadges.ts");
  assert.match(hook, /stockAlertsQuery\(\), enabled: role === "pharmacist" && perms\.includes\("pharmacy:view"\)/);
  assert.match(hook, /"stock-alerts": badgeCount\(stockAlerts\?\.counts\)/);
});
