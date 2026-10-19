// FEF-049 flaky detection and quarantine. A case that failed and then passed on retry is "flaky"; 3 flaky runs
// within 5 days auto-quarantine it with its journey's owner: it still runs and is reported, but no longer blocks.
//   node flaky.mjs --results=<dir of results-*.json> --history=<flaky-history.json> --out=<dir> [--now=ISO]
// Writes the updated history, <out>/quarantine.json (read by run-smoke via SMOKE_QUARANTINE) and <out>/report.md.
// Exit 1 when a non-quarantined case failed (the stage 7 gate).
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const WINDOW_DAYS = 5, THRESHOLD = 3, KEEP_DAYS = 30;
const DAY = 86400000;

/** Appends this run's flaky cases to the history and drops entries older than KEEP_DAYS. */
export function record(history, results, now) {
  const t = Date.parse(now);
  return [...history, ...results.filter((r) => r.status === 'flaky').map((r) => ({ id: r.id, owner: r.owner, at: now }))]
    .filter((h) => t - Date.parse(h.at) <= KEEP_DAYS * DAY);
}

/** Cases with >= THRESHOLD flaky occurrences in the last WINDOW_DAYS, with owner and first occurrence in the window. */
export function quarantine(history, now) {
  const t = Date.parse(now);
  const by = new Map();
  for (const h of history) {
    const age = t - Date.parse(h.at);
    if (age < 0 || age > WINDOW_DAYS * DAY) continue;
    const q = by.get(h.id) ?? { id: h.id, owner: h.owner, occurrences: 0, since: h.at };
    q.occurrences++; if (h.at < q.since) q.since = h.at;
    by.set(h.id, q);
  }
  return [...by.values()].filter((q) => q.occurrences >= THRESHOLD).sort((a, b) => a.id.localeCompare(b.id));
}

/** Gate: failures that are not quarantined. */
export const blocking = (results, quarantined) => results.filter((r) => r.status === 'failed' && !quarantined.some((q) => q.id === r.id));

export function report(results, quarantined, wallMs) {
  const n = (s) => results.filter((r) => r.status === s).length;
  const lines = ['<!-- smoke-report -->', '### Stage 7 smoke (J1, J5, J6, J9)', '',
    `${results.length} cases: ${n('passed')} passed, ${n('flaky')} flaky, ${n('failed')} failed. Slowest shard ${(wallMs / 1000).toFixed(1)} s (budget 480 s).`, ''];
  const failed = results.filter((r) => r.status !== 'passed');
  if (failed.length) {
    lines.push('| Case | Status | Owner | Last error |', '|---|---|---|---|');
    for (const r of failed) lines.push(`| ${r.id} | ${r.status}${quarantined.some((q) => q.id === r.id) ? ' (quarantined)' : ''} | ${r.owner} | ${(r.attempts.find((a) => !a.ok)?.error ?? '').split('\n')[0].replace(/\|/g, '/').slice(0, 160)} |`);
    lines.push('');
  }
  if (quarantined.length) {
    lines.push('**Quarantined** (3 or more flaky runs in 5 days; fix or delete, owner on the hook):', '');
    for (const q of quarantined) lines.push(`- ${q.id}: ${q.occurrences} flaky runs since ${q.since.slice(0, 10)}, owner ${q.owner}`);
  }
  return lines.join('\n') + '\n';
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const arg = (k, d) => process.argv.find((a) => a.startsWith(`--${k}=`))?.slice(k.length + 3) ?? d;
  const dir = arg('results'), histFile = arg('history'), out = arg('out', dir), now = arg('now', new Date().toISOString());
  const runs = readdirSync(dir).filter((f) => /^results-\d+\.json$/.test(f)).map((f) => JSON.parse(readFileSync(`${dir}/${f}`, 'utf8')));
  const results = runs.flatMap((r) => r.results);
  const wallMs = Math.max(0, ...runs.map((r) => r.wallMs));
  const history = record(existsSync(histFile) ? JSON.parse(readFileSync(histFile, 'utf8')) : [], results, now);
  const q = quarantine(history, now);
  mkdirSync(out, { recursive: true });
  writeFileSync(histFile, JSON.stringify(history, null, 1));
  writeFileSync(`${out}/quarantine.json`, JSON.stringify(q, null, 1));
  const md = report(results, q, wallMs);
  writeFileSync(`${out}/report.md`, md);
  console.log(md);
  const b = blocking(results, q);
  if (runs.length === 0) { console.log('no shard results found'); process.exit(1); }
  process.exit(b.length ? 1 : 0);
}
