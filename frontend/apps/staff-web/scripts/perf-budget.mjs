#!/usr/bin/env node
// OPS-039: front-end performance budget gate (FRD NFR-02: LCP < 3 s on 3G, initial JS+CSS < 1.5 MB compressed per route).
//   node scripts/perf-budget.mjs bundle [--next .next] [--rc lighthouserc.json] [--out report.md]
//   node scripts/perf-budget.mjs lhci   [--dir .lighthouseci] [--rc lighthouserc.json] [--out report.md]
//   node scripts/perf-budget.mjs rum    [--rc lighthouserc.json] < log lines of the last 7 days
// Budgets live in lighthouserc.json (`nabdBudgets`), next to the Lighthouse assertions. Exit 1 on any breach.
import { readFileSync, writeFileSync, readdirSync, existsSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const kb = (n) => `${(n / 1024).toFixed(0)} KB`;
export const median = (xs) => { const s = [...xs].sort((a, b) => a - b); const m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };
export const p95 = (xs) => { const s = [...xs].sort((a, b) => a - b); return s.length ? s[Math.min(s.length - 1, Math.ceil(0.95 * s.length) - 1)] : null; };

/** files: {route: [{file, bytes}]} (bytes already compressed). Breach = sum over budget; lists the 3 largest chunks. */
export function checkBundles(files, budgets) {
  const breaches = [], rows = [];
  for (const [route, list] of Object.entries(files)) {
    const total = list.reduce((a, f) => a + f.bytes, 0);
    const top = [...list].sort((a, b) => b.bytes - a.bytes).slice(0, 3).map((f) => `${f.file} (${kb(f.bytes)})`);
    rows.push({ route, total });
    if (total > budgets.bundleBytes) breaches.push({ route, metric: "initial JS+CSS (compressed)", value: kb(total), budget: kb(budgets.bundleBytes), top });
  }
  return { rows, breaches };
}

/** runs: {route: [{lcp, chunks:[{url, bytes}]}]}. Gate on the MEDIAN of the runs (3 per route). */
export function checkLcp(runs, budgets) {
  const breaches = [], rows = [];
  for (const [route, rs] of Object.entries(runs)) {
    const lcp = median(rs.map((r) => r.lcp));
    rows.push({ route, lcp, n: rs.length });
    if (lcp >= budgets.lcpMs) {
      const worst = [...rs].sort((a, b) => b.lcp - a.lcp)[0];
      const top = [...worst.chunks].sort((a, b) => b.bytes - a.bytes).slice(0, 3).map((c) => `${c.url} (${kb(c.bytes)})`);
      breaches.push({ route, metric: `LCP median of ${rs.length}`, value: `${Math.round(lcp)} ms`, budget: `< ${budgets.lcpMs} ms`, top });
    }
  }
  return { rows, breaches };
}

export function breachMarkdown(title, { breaches }) {
  if (!breaches.length) return `### ${title}: within budget\n`;
  const lines = [`### ${title}: BUDGET BREACH`, "", "| Route | Metric | Value | Budget | Largest chunks |", "|---|---|---|---|---|"];
  for (const b of breaches) lines.push(`| ${b.route} | ${b.metric} | ${b.value} | ${b.budget} | ${b.top.join("<br>")} |`);
  return lines.join("\n") + "\n";
}

/** Log lines written by ClientTelemetryController: `client telemetry kind=vital metric=LCP route=/x value=123.4 ...` */
export function rumP95(lines) {
  const by = {};
  for (const l of lines) {
    const m = /kind=vital metric=LCP route=(\S+) value=([\d.]+)/.exec(l);
    if (m) (by[m[1].replace(/\/[0-9a-f-]{8,}(?=\/|$)/g, "/:id")] ??= []).push(Number(m[2]));
  }
  return Object.fromEntries(Object.entries(by).map(([r, v]) => [r, { n: v.length, p95: p95(v) }]));
}

export function rumMarkdown(rum, budgets) {
  const lines = ["### Production LCP p95 (7 days) vs CI budget", "", "| Route | Samples | p95 LCP | CI budget |", "|---|---|---|---|"];
  for (const r of budgets.routes) {
    const v = rum[r] ?? { n: 0, p95: null };
    lines.push(`| ${r} | ${v.n} | ${v.p95 == null ? "no data" : Math.round(v.p95) + " ms"}${v.p95 >= budgets.lcpMs ? " (over)" : ""} | ${budgets.lcpMs} ms |`);
  }
  return lines.join("\n") + "\n";
}

// ---- adapters (file system) ----
const arg = (name, d) => { const i = process.argv.indexOf(`--${name}`); return i > 0 ? process.argv[i + 1] : d; };

export function loadBudgets(rcPath) {
  const b = JSON.parse(readFileSync(rcPath, "utf8")).nabdBudgets;
  return { lcpMs: b.lcpMs, bundleBytes: b.bundleKB * 1024, routes: b.routes };
}

/** Initial JS+CSS of a route, gzip size. JS = Next's own first-load list (.next/diagnostics/route-bundle-stats.json),
 * CSS = stylesheets named in the route's client-reference manifest. A route with no chunks is an error, never a pass. */
export function readBundles(nextDir, routes) {
  const stats = JSON.parse(readFileSync(join(nextDir, "diagnostics/route-bundle-stats.json"), "utf8"));
  const out = {};
  for (const route of routes) {
    const js = stats.find((s) => s.route === route)?.firstLoadChunkPaths ?? [];
    const mf = join(nextDir, "server/app", route, "page_client-reference-manifest.js");
    const css = existsSync(mf) ? [...readFileSync(mf, "utf8").matchAll(/static\/chunks\/[^"\\]+\.css/g)].map((m) => ".next/" + m[0]) : [];
    const files = [...new Set([...js, ...css])];
    if (!files.length) throw new Error(`no bundle data for route ${route}`);
    out[route] = files.map((f) => ({ file: f, bytes: gzipSync(readFileSync(join(nextDir, "..", f))).length }));
  }
  return out;
}

export function readLhr(dir, routes) {
  const out = Object.fromEntries(routes.map((r) => [r, []]));
  for (const f of readdirSync(dir).filter((n) => /^lhr-.*\.json$/.test(n))) {
    const j = JSON.parse(readFileSync(join(dir, f), "utf8"));
    const route = new URL(j.finalUrl ?? j.requestedUrl).pathname.replace(/(.)\/$/, "$1");
    const key = routes.find((r) => r === route) ?? routes.find((r) => new URL(j.requestedUrl).pathname === r);
    if (!key) continue;
    const chunks = (j.audits["network-requests"]?.details?.items ?? [])
      .filter((i) => /Script|Stylesheet/.test(i.resourceType ?? ""))
      .map((i) => ({ url: new URL(i.url).pathname, bytes: i.transferSize ?? 0 }));
    out[key].push({ lcp: j.audits["largest-contentful-paint"].numericValue, chunks });
  }
  return out;
}

async function main() {
  const [cmd] = process.argv.slice(2);
  const budgets = loadBudgets(arg("rc", "lighthouserc.json"));
  let report, failed = false;
  if (cmd === "bundle") {
    const r = checkBundles(readBundles(arg("next", ".next"), budgets.routes), budgets);
    report = breachMarkdown("Bundle size", r); failed = r.breaches.length > 0;
  } else if (cmd === "lhci") {
    const runs = readLhr(arg("dir", ".lighthouseci"), budgets.routes);
    const missing = budgets.routes.filter((r) => !runs[r].length);
    if (missing.length) { console.error(`no Lighthouse run for: ${missing.join(", ")}`); process.exit(1); }
    const r = checkLcp(runs, budgets);
    report = breachMarkdown("Lighthouse LCP (3G, Moto G class)", r); failed = r.breaches.length > 0;
  } else if (cmd === "rum") {
    report = rumMarkdown(rumP95(readFileSync(0, "utf8").split("\n")), budgets);
  } else { console.error("usage: perf-budget.mjs bundle|lhci|rum"); process.exit(2); }
  console.log(report);
  if (arg("out")) writeFileSync(arg("out"), report);
  process.exit(failed ? 1 : 0);
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
