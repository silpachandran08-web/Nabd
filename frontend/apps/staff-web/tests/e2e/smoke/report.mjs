// FEF-050 results export in the format QA's release safety test report consumes (OPS-044): JUnit XML + timings JSON.
//   node report.mjs --results=<dir of results-*.json> --out=<dir> [--sha=SHA] [--release=TAG]
// Writes <out>/journeys-junit.xml, journeys-timings.json and journeys-summary.md. One testcase per case (journey x tenant x
// project x locale); the role-absence sweep cases (RA-J*) are in the same files, so one report carries both.
// JUnit properties per testcase: journey, project, locale, suite, sla_ms, actual_ms, sla_met, flaky, quarantined, hazard (one per linked hazard id).
// Exit 1 when a case failed and is not quarantined (the journey gate), 0 otherwise.
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const esc = (s) => String(s ?? '').replace(/[<>&"']/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' })[c]).replace(/[^\x09\x0a\x0d\x20-￿]/g, '');

/** The measured value the journey's SLA applies to (last attempt), or null when the case never got that far. */
export const actualMs = (r) => { const last = r.attempts?.at(-1); return last?.timings && r.budgetKey in last.timings ? last.timings[r.budgetKey] : null; };
export const slaMet = (r) => (r.status === 'skipped' ? null : actualMs(r) != null && actualMs(r) <= r.budgetMs && r.status !== 'failed');

export function toTimings(results, meta = {}) {
  const n = (s) => results.filter((r) => r.status === s).length;
  return {
    report: 'journey-timings', schema: 1, ...meta,
    summary: { cases: results.length, passed: n('passed'), flaky: n('flaky'), failed: n('failed'), skipped: n('skipped'), slaBreaches: results.filter((r) => slaMet(r) === false).length },
    cases: results.map((r) => ({ id: r.id, journey: r.journey, title: r.title, suite: r.suite, project: r.project, locale: r.locale, tenant: r.tenant, status: r.status, quarantined: !!r.quarantined,
      slaKey: r.budgetKey ?? null, slaMs: r.budgetMs ?? null, actualMs: actualMs(r), slaMet: slaMet(r), hazards: r.hazards ?? [], attempts: r.attempts?.length ?? 0 })),
  };
}

export function toJunit(results, meta = {}) {
  const byJourney = new Map();
  for (const r of results) byJourney.set(r.journey, [...(byJourney.get(r.journey) ?? []), r]);
  const suites = [...byJourney].map(([id, rs]) => {
    const failures = rs.filter((r) => r.status === 'failed').length, skipped = rs.filter((r) => r.status === 'skipped').length;
    const time = rs.reduce((s, r) => s + (r.attempts?.at(-1)?.ms ?? 0), 0) / 1000;
    const cases = rs.map((r) => {
      const last = r.attempts?.at(-1);
      const props = [['journey', r.journey], ['project', r.project], ['locale', r.locale], ['suite', r.suite], ['sla_ms', r.budgetMs], ['actual_ms', actualMs(r)], ['sla_met', slaMet(r)], ['flaky', r.status === 'flaky'], ['quarantined', !!r.quarantined],
        ...(r.hazards ?? []).map((h) => ['hazard', h])].filter(([, v]) => v !== null && v !== undefined);
      const body = r.status === 'failed' ? `<failure message="${esc((last?.error ?? 'failed').split('\n')[0].slice(0, 300))}">${esc(last?.error ?? '')}</failure>`
        : r.status === 'skipped' ? `<skipped message="${esc(r.reason ?? 'skipped')}"/>`
        : r.status === 'flaky' ? `<system-out>${esc('flaky: failed then passed on retry. ' + (r.attempts?.[0]?.error ?? ''))}</system-out>` : '';
      return `    <testcase classname="journeys.${esc(r.journey)}" name="${esc(r.id)}" time="${((last?.ms ?? 0) / 1000).toFixed(3)}">\n      <properties>${props.map(([k, v]) => `<property name="${k}" value="${esc(v)}"/>`).join('')}</properties>${body ? `\n      ${body}` : ''}\n    </testcase>`;
    });
    return `  <testsuite name="${esc(id)}" tests="${rs.length}" failures="${failures}" errors="0" skipped="${skipped}" time="${time.toFixed(3)}">\n${cases.join('\n')}\n  </testsuite>`;
  });
  const total = results.length, failed = results.filter((r) => r.status === 'failed').length;
  return `<?xml version="1.0" encoding="UTF-8"?>\n<testsuites name="journeys" tests="${total}" failures="${failed}" errors="0" release="${esc(meta.release ?? '')}" sha="${esc(meta.sha ?? '')}">\n${suites.join('\n')}\n</testsuites>\n`;
}

/** Markdown for the job summary: journey x SLA table plus every failure. */
export function toSummary(results) {
  const t = toTimings(results).summary;
  const lines = ['### Journeys J1-J15 and role-absence sweep', '',
    `${t.cases} cases: ${t.passed} passed, ${t.flaky} flaky, ${t.failed} failed, ${t.skipped} skipped. SLA breaches: ${t.slaBreaches}.`, '',
    '| Journey | Cases | Failed | Slowest (ms) | SLA (ms) |', '|---|---|---|---|---|'];
  const ids = [...new Set(results.map((r) => r.journey))];
  for (const id of ids) {
    const rs = results.filter((r) => r.journey === id);
    lines.push(`| ${id} | ${rs.length} | ${rs.filter((r) => r.status === 'failed').length} | ${Math.max(0, ...rs.map((r) => actualMs(r) ?? 0))} | ${rs[0].budgetMs ?? ''} |`);
  }
  const bad = results.filter((r) => r.status === 'failed');
  if (bad.length) lines.push('', '| Failed case | Owner | Error |', '|---|---|---|', ...bad.map((r) => `| ${r.id}${r.quarantined ? ' (quarantined)' : ''} | ${r.owner} | ${(r.attempts.at(-1)?.error ?? '').split('\n')[0].replace(/\|/g, '/').slice(0, 160)} |`));
  return lines.join('\n') + '\n';
}

export const loadResults = (dir) => readdirSync(dir).filter((f) => /^results-[\w-]+\.json$/.test(f)).flatMap((f) => JSON.parse(readFileSync(`${dir}/${f}`, 'utf8')).results);
export const gateFailures = (results) => results.filter((r) => r.status === 'failed' && !r.quarantined);

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const arg = (k, d) => process.argv.find((a) => a.startsWith(`--${k}=`))?.slice(k.length + 3) ?? d;
  const dir = arg('results'), out = arg('out', dir), meta = { sha: arg('sha', ''), release: arg('release', ''), generatedAt: new Date().toISOString() };
  const results = loadResults(dir);
  if (results.length === 0) { console.log('no results found'); process.exit(1); }
  mkdirSync(out, { recursive: true });
  writeFileSync(`${out}/journeys-junit.xml`, toJunit(results, meta));
  writeFileSync(`${out}/journeys-timings.json`, JSON.stringify(toTimings(results, meta), null, 1) + '\n');
  const md = toSummary(results);
  writeFileSync(`${out}/journeys-summary.md`, md);
  console.log(md);
  process.exit(gateFailures(results).length ? 1 : 0);
}
