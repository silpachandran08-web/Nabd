// OPS-039: budget fixtures. A breach must fail (CLI exit 1) and the report must name route, metric, budget, largest chunks.
import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { checkBundles, checkLcp, breachMarkdown, rumP95, rumMarkdown, median } from "../../scripts/perf-budget.mjs";

const budgets = { lcpMs: 3000, bundleBytes: 1536 * 1024, routes: ["/login", "/arrivals"] };
const lh = (lcp, chunks = []) => ({ lcp, chunks });

test("median of 3 runs decides LCP: one slow outlier passes, two slow runs fail", () => {
  assert.equal(median([1, 9, 2]), 2);
  assert.equal(checkLcp({ "/login": [lh(2500), lh(2600), lh(9000)] }, budgets).breaches.length, 0);
  assert.equal(checkLcp({ "/login": [lh(3100), lh(3200), lh(1000)] }, budgets).breaches.length, 1);
});

test("LCP exactly at the budget breaches (strictly < 3 s)", () => {
  assert.equal(checkLcp({ "/a": [lh(3000), lh(3000), lh(3000)] }, budgets).breaches.length, 1);
});

test("bundle: over 1.5 MB breaches, exactly 1.5 MB passes", () => {
  const f = (b) => ({ "/arrivals": [{ file: "static/chunks/a.js", bytes: b }] });
  assert.equal(checkBundles(f(1536 * 1024), budgets).breaches.length, 0);
  assert.equal(checkBundles(f(1536 * 1024 + 1), budgets).breaches.length, 1);
});

test("breach report names route, metric, budget and the 3 largest chunks", () => {
  const files = { "/arrivals": ["a", "b", "c", "d"].map((n, i) => ({ file: `static/chunks/${n}.js`, bytes: (i + 1) * 500 * 1024 })) };
  const md = breachMarkdown("Bundle size", checkBundles(files, budgets));
  for (const s of ["/arrivals", "initial JS+CSS", "1536 KB", "d.js (2000 KB)", "c.js (1500 KB)", "b.js (1000 KB)"]) assert.ok(md.includes(s), s);
  assert.ok(!md.includes("a.js"));
});

test("RUM: p95 per route next to the CI budget", () => {
  const lines = Array.from({ length: 20 }, (_, i) => `INFO client telemetry kind=vital metric=LCP route=/arrivals value=${(i + 1) * 100} stackHash=`);
  lines.push("kind=error metric=x route=/arrivals value=null", "kind=vital metric=CLS route=/arrivals value=0.1");
  const rum = rumP95(lines);
  assert.deepEqual(rum["/arrivals"], { n: 20, p95: 1900 });
  const md = rumMarkdown(rum, budgets);
  assert.match(md, /\| \/arrivals \| 20 \| 1900 ms \| 3000 ms \|/);
  assert.match(md, /\| \/login \| 0 \| no data/);
});

test("CLI fails (exit 1) on a lighthouse fixture over budget and passes under it", () => {
  const dir = mkdtempSync(join(tmpdir(), "lhci-"));
  const rc = join(dir, "rc.json");
  writeFileSync(rc, JSON.stringify({ nabdBudgets: { lcpMs: 3000, bundleKB: 1536, routes: ["/login"] } }));
  const run = (lcp) => {
    for (let i = 0; i < 3; i++) writeFileSync(join(dir, `lhr-${i}.json`), JSON.stringify({ requestedUrl: "http://x/login", finalUrl: "http://x/login",
      audits: { "largest-contentful-paint": { numericValue: lcp }, "network-requests": { details: { items: [{ url: "http://x/_next/static/chunks/big.js", resourceType: "Script", transferSize: 900000 }] } } } }));
    return spawnSync("node", [new URL("../../scripts/perf-budget.mjs", import.meta.url).pathname, "lhci", "--dir", dir, "--rc", rc, "--out", join(dir, "r.md")], { encoding: "utf8" });
  };
  assert.equal(run(2000).status, 0);
  const bad = run(4000);
  assert.equal(bad.status, 1);
  assert.match(readFileSync(join(dir, "r.md"), "utf8"), /\/login.*LCP median of 3.*4000 ms.*big\.js/);
});
