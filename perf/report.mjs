// OPS-046.4: turns a k6 summary (perf/k6/lib.js summarize) plus optional DB samples into <profile>-report.json/.html,
// checks the gate (k6 thresholds, DB CPU < 70%) and the stored baseline (+10% p95 budget), optionally saves a new baseline.
// node perf/report.mjs <summary.json> [--samples db.csv] [--baseline perf/baseline/<p>.json] [--save-baseline] [--out dir]
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';

/** Regressions: every endpoint (and overall) p95 more than `budget` above the baseline's. */
export function compare(baseline, current, budget = 0.1) {
  const out = [];
  const pairs = [['overall', baseline.overall, current.overall], ...Object.entries(baseline.endpoints || {}).map(([k, b]) => [k, b, (current.endpoints || {})[k]])];
  for (const [name, b, c] of pairs) {
    if (!b || b.p95 == null) continue;
    if (!c || c.p95 == null) { out.push(`${name}: missing in this run`); continue; }
    if (c.p95 > b.p95 * (1 + budget)) out.push(`${name}: p95 ${c.p95.toFixed(1)} ms > baseline ${b.p95.toFixed(1)} ms + ${budget * 100}%`);
  }
  return out;
}

/** CSV "epoch,cpu_pct,active_conns,total_conns" (blank = not measured) -> max/avg per column. */
export function samples(csv) {
  const rows = csv.trim().split('\n').filter(Boolean).map((l) => l.split(','));
  const col = (i) => rows.map((r) => parseFloat(r[i])).filter((v) => !Number.isNaN(v));
  const stat = (v) => (v.length ? { max: Math.max(...v), avg: v.reduce((a, b) => a + b, 0) / v.length, n: v.length } : null);
  return { dbCpuPct: stat(col(1)), dbActiveConnections: stat(col(2)), dbTotalConnections: stat(col(3)) };
}

export function gate(summary, db) {
  const fails = [];
  const o = summary.overall || {};
  if (!summary.endpoints || !summary.endpoints[summary.profile]) fails.push(`no ${summary.profile} requests measured (aborted run?)`);
  if (o.errorRate == null || o.errorRate >= 0.001) fails.push(`error rate ${o.errorRate == null ? 'unknown' : (o.errorRate * 100).toFixed(3) + '%'} >= 0.1%`);
  for (const [metric, ths] of Object.entries(summary.thresholds || {})) for (const [t, ok] of Object.entries(ths)) if (!ok) fails.push(`${metric} ${t}`);
  if (db?.dbCpuPct && db.dbCpuPct.max >= 70) fails.push(`DB CPU max ${db.dbCpuPct.max.toFixed(1)}% >= 70%`);
  return fails;
}

const esc = (s) => String(s).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' })[c]);
const ms = (v) => (v == null ? '—' : v.toFixed(1));
function html(r) {
  const rows = Object.entries({ overall: r.summary.overall, ...r.summary.endpoints })
    .map(([k, v]) => `<tr><td>${esc(k)}</td><td>${ms(v.p50)}</td><td>${ms(v.p95)}</td><td>${ms(v.p99)}</td><td>${v.count ?? v.requests ?? ''}</td></tr>`).join('');
  const db = Object.entries(r.db || {}).map(([k, v]) => `<tr><td>${esc(k)}</td><td>${v ? `max ${v.max.toFixed(1)} / avg ${v.avg.toFixed(1)}` : 'not measured'}</td></tr>`).join('');
  return `<!doctype html><meta charset="utf-8"><title>${esc(r.summary.profile)} perf report</title>
<style>body{font:14px system-ui;margin:24px}table{border-collapse:collapse;margin-block:12px}td,th{border:1px solid #999;padding:4px 10px;text-align:end}td:first-child{text-align:start}</style>
<h1>${esc(r.summary.profile)}: ${r.pass ? 'PASS' : 'FAIL'}</h1><p>${esc(r.summary.generatedAt)} · ${esc(JSON.stringify(r.summary.env))}</p>
<table><tr><th>endpoint</th><th>p50 ms</th><th>p95 ms</th><th>p99 ms</th><th>requests</th></tr>${rows}</table>
<p>error rate ${((r.summary.overall.errorRate || 0) * 100).toFixed(3)}% · ${esc(JSON.stringify(r.summary.custom || {}))}</p>
<table><tr><th>database</th><th>value</th></tr>${db}</table>
<p>gate failures: ${esc(r.gateFailures.join('; ') || 'none')}</p><p>baseline regressions: ${esc(r.regressions.join('; ') || 'none')}</p>`;
}

if (process.argv[1] && basename(process.argv[1]) === 'report.mjs') {
  const args = process.argv.slice(2);
  const opt = (k) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : undefined; };
  const summary = JSON.parse(readFileSync(args[0], 'utf8'));
  const db = opt('--samples') && existsSync(opt('--samples')) ? samples(readFileSync(opt('--samples'), 'utf8')) : null;
  const basePath = opt('--baseline');
  const baseline = basePath && existsSync(basePath) ? JSON.parse(readFileSync(basePath, 'utf8')) : null;
  const report = { summary, db, gateFailures: gate(summary, db), regressions: baseline ? compare(baseline, summary) : [], baseline: baseline ? basePath : 'none stored yet' };
  report.pass = report.gateFailures.length === 0 && report.regressions.length === 0;
  const out = opt('--out') || dirname(args[0]);
  writeFileSync(join(out, `${summary.profile}-report.json`), JSON.stringify(report, null, 2));
  writeFileSync(join(out, `${summary.profile}-report.html`), html(report));
  if (args.includes('--save-baseline') && report.gateFailures.length === 0) writeFileSync(basePath, JSON.stringify({ ...summary, db }, null, 2));
  console.log(`${summary.profile}: ${report.pass ? 'PASS' : 'FAIL'} p95=${ms(summary.overall.p95)} ms errors=${((summary.overall.errorRate || 0) * 100).toFixed(3)}%`);
  for (const f of [...report.gateFailures, ...report.regressions]) console.log('  ' + f);
  process.exit(report.pass ? 0 : 1);
}
