// FEF-049 smoke runner. Runs the J1/J5/J6/J9 cases of one shard in Chromium, one retry each, axe on every step.
//   node run-smoke.mjs [--shard=1/4] [--retries=1] [--only=J9] [--break] [--out=dir]
// Env: APP, SMOKE_TARGET (mock|preview), SMOKE_API, SMOKE_LOGINS, SMOKE_FIXTURES, AXE_CORE_PATH, SMOKE_QUARANTINE (json from flaky.mjs).
// --break injects a server fault into each journey's key write (self-check: the suite must fail on a broken stack).
// Exit 1 when a case that is not quarantined failed. Writes <out>/results-<shard>.json for flaky.mjs.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { open, signIn, axeStep, axeReport, noHorizontalScroll, consoleNoise, shard, TARGET } from './harness.mjs';
import { CASES, BUDGET_KEY } from './journeys.mjs';

const arg = (k, d) => process.argv.find((a) => a.startsWith(`--${k}=`))?.split('=')[1] ?? (process.argv.includes(`--${k}`) ? true : d);
const [si, sn] = String(arg('shard', '1/1')).split('/').map(Number);
const retries = Number(arg('retries', 1));
const only = arg('only', null);
const out = arg('out', process.env.SMOKE_OUT ?? '/tmp/smoke-results');
const quarantined = new Set(process.env.SMOKE_QUARANTINE ? JSON.parse(readFileSync(process.env.SMOKE_QUARANTINE, 'utf8')).map((q) => q.id) : []);
const BREAK = {
  J1: [['POST', /^\/queue\/check-in$/, { status: 500, body: { title: 'Injected fault', detail: 'smoke self-check' } }]],
  J5: [['POST', /^\/clinical\/notes\/[^/]+\/sign$/, { status: 500, body: { title: 'Injected fault', detail: 'smoke self-check' } }]],
  J6: [['PATCH', /^\/clinical\/prescriptions\/[^/]+$/, { status: 500, body: { title: 'Injected fault', detail: 'smoke self-check' } }]],
  J9: [['POST', /^\/billing\/checkout\/[^/]+$/, { status: 500, body: { title: 'Injected fault', detail: 'smoke self-check' } }]],
};
const STEP_TIMEOUT = 15000;
mkdirSync(out, { recursive: true });

async function attemptCase(c, attempt) {
  const d = c.journey.prepare(c.tenant, c.project.key, attempt);
  const s = await open({ viewport: c.project.viewport, mocks: d.h, faults: arg('break', false) ? BREAK[c.journey.id] : [] });
  s.page.setDefaultTimeout(STEP_TIMEOUT);
  const axe = [];
  const t0 = performance.now();
  try {
    await signIn(s.page, c.journey.role, c.tenant);
    const timings = await c.journey.run({ page: s.page, d, tenant: c.tenant, attempt, axe: (step) => axeStep(s.page, step, axe) });
    const problems = [];
    const key = BUDGET_KEY[c.journey.id];
    if (!(timings[key] <= c.journey.budgetMs)) problems.push(`${key} ${timings[key]} ms over the ${c.journey.budgetMs} ms budget`);
    const a11y = axeReport(c.id, axe);
    if (a11y) problems.push(a11y);
    if (c.project.key === 'mobile' && !(await noHorizontalScroll(s.page))) problems.push('horizontal page scroll at 390px');
    const noise = consoleNoise(s.log);
    if (noise.length) problems.push(`console/page errors: ${noise.slice(0, 5).join(' | ')}`);
    return { ok: problems.length === 0, error: problems.join('\n') || null, timings, ms: Math.round(performance.now() - t0), axe };
  } catch (e) {
    await s.page.screenshot({ path: `${out}/${c.id.replace(/ /g, '-')}-${attempt}.png` }).catch(() => {});
    const noise = consoleNoise(s.log);
    const ctx = [noise.length ? `console/page errors: ${noise.slice(0, 3).join(' | ')}` : '', s.log.unmatched.length ? `unmocked: ${[...new Set(s.log.unmatched)].slice(0, 5).join(', ')}` : ''].filter(Boolean);
    return { ok: false, error: [String(e?.message ?? e).split('\n').slice(0, 4).join('\n'), ...ctx].join('\n'), ms: Math.round(performance.now() - t0), axe };
  } finally {
    await s.browser.close();
  }
}

const mine = shard(CASES.filter((c) => !only || c.journey.id === only), si, sn);
const started = performance.now();
const results = [];
for (const c of mine) {
  const attempts = [];
  for (let a = 1; a <= 1 + retries; a++) {
    const r = await attemptCase(c, a);
    attempts.push(r);
    if (r.ok) break;
  }
  const status = attempts.at(-1).ok ? (attempts.length > 1 ? 'flaky' : 'passed') : 'failed';
  const res = { id: c.id, journey: c.journey.id, owner: c.journey.owner, status, quarantined: quarantined.has(c.id), attempts: attempts.map(({ axe, ...r }) => ({ ...r, axe: axe.filter((e) => e.violations.length || e.known.length) })) };
  results.push(res);
  const last = attempts.at(-1);
  console.log(`${status.toUpperCase().padEnd(6)} ${c.id} (${c.journey.title})${last.timings ? ' ' + JSON.stringify(last.timings) : ''}${res.quarantined ? ' [quarantined]' : ''}`);
  const known = [...new Set(last.axe.flatMap((e) => e.known.map((v) => v.id)))];
  if (known.length) console.log(`  baselined axe findings (axe-known.json, not blocking): ${known.join(', ')}`);
  for (const a of attempts.filter((x) => !x.ok)) console.log(`  attempt failed: ${a.error.replace(/\n/g, '\n    ')}`);
}
const wallMs = Math.round(performance.now() - started);
writeFileSync(`${out}/results-${si}.json`, JSON.stringify({ shard: `${si}/${sn}`, target: TARGET, wallMs, results }, null, 1));
const blocking = results.filter((r) => r.status === 'failed' && !r.quarantined);
console.log(`\nshard ${si}/${sn}: ${results.length} cases, ${results.filter((r) => r.status === 'passed').length} passed, ${results.filter((r) => r.status === 'flaky').length} flaky, ${results.filter((r) => r.status === 'failed').length} failed (${blocking.length} blocking) in ${(wallMs / 1000).toFixed(1)} s`);
process.exit(blocking.length || mine.length === 0 ? 1 : 0);
