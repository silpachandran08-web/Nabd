// Independent QA (S11) for OPS-008 staff-web: cursor paging with no client-side filtering.
// NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3614 node tests/e2e/s11qa-ops008.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, sleep, APP } from './qa-lib.mjs';

const RECEPTION = ['patients:view', 'patients:create', 'patients:edit', 'queue:view', 'queue:create', 'billing:view'];
const row = (id, name, extra = {}) => ({ id, mrn: 'MRN-' + id, name, phone: '+911234567890', age: 30, gender: 'male', minor: false, lastVisit: null,
  nextAppointment: null, nextDoctorName: null, nextDoctorId: null, nextAppointmentId: null, nextAppointmentToday: false, packageSessionsUsed: null,
  packageSessionsTotal: null, condition: null, followUpDue: false, allergy: null, balanceDue: false, duplicate: false, archived: false,
  queueToken: null, queueStatus: null, tags: [], ...extra });
const counts = { all: 4, recent: 0, followup: 0, package: 0, balance: 0, duplicate: 2, archived: 0 };
const pg = (rows, nextCursor, matchedTotal = 4) => ({ total: 4, counts, rows, matchedTotal, hasMore: !!nextCursor, page: { nextCursor, limit: 200 } });
const json = (route, body) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });

async function open(handler, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, RECEPTION);
  await mockApi(s.page, s.log, [['GET', /^\/setup\/profile/, { body: { timezone: 'Asia/Kolkata', region: 'IN' } }], ['GET', /^\/patients\/guardian-reviews-due/, { body: [] }], ['GET', /^\/queue/, { body: [] }], ['GET', /^\/patients\/registry/, handler]]);
  await s.page.goto(APP + '/patients', { waitUntil: 'domcontentloaded' });
  return s;
}
const calls = (s) => s.log.requests.filter((r) => r.path.startsWith('/patients/registry')).map((r) => new URL('http://x' + r.path).searchParams);
const rows = (s) => s.page.locator('tbody tr').allInnerTexts();
async function finish(s, tag) {
  const e = [...s.log.pageErrors, ...s.log.console.filter((x) => x.startsWith('[error]') && !/Failed to load resource/.test(x)), ...s.log.unmatched.map((u) => 'unmocked ' + u)];
  check(`${tag}: zero console/page errors`, e.length === 0, e.slice(0, 2).join(' | '));
  if (e.length) dumpLog(tag, s.log);
  await s.browser.close();
}
async function run(name, fn) {
  try { await fn(); } catch (e) { check(name, false, String(e).split('\n')[0]); }
}

// Q1. A search after page 2 starts again from page 1: q sent, cursor dropped, rows replaced by the server's answer.
await run('Q1', async () => {
  const s = await open(async (route, req) => {
    const p = new URL(req.url()).searchParams;
    if (p.get('q')) return json(route, pg([row('s1', 'Zoya Search')], null, 1));
    if (p.get('cursor') === 'C1') return json(route, pg([row('p3', 'Chitra Rao'), row('p4', 'Dev Iyer')], null));
    return json(route, pg([row('p1', 'Asha Nair'), row('p2', 'Bilal Khan')], 'C1'));
  });
  await s.page.getByText('Dev Iyer').waitFor({ timeout: 10000 });
  check('Q1: page 2 appended (4 rows)', (await rows(s)).length === 4);
  await s.page.getByLabel('Search patients').fill('zoya');
  await s.page.getByText('Zoya Search').waitFor({ timeout: 8000 });
  await sleep(300);
  const last = calls(s).at(-1);
  check('Q1: search request carries q and no cursor', last.get('q') === 'zoya' && !last.has('cursor'), last.toString());
  check('Q1: list shows only the server result', (await rows(s)).length === 1);
  await finish(s, 'Q1');
});

// Q2. No client-side filtering: under the duplicate chip the UI shows exactly what the server returned,
// including a row whose duplicate flag is false.
await run('Q2', async () => {
  const s = await open(async (route, req) => {
    const p = new URL(req.url()).searchParams;
    if (p.get('filter') === 'duplicate') return json(route, pg([row('d1', 'Dup One', { duplicate: true }), row('d2', 'Server Says So', { duplicate: false })], null, 2));
    return json(route, pg([row('p1', 'Asha Nair')], null, 1));
  });
  await s.page.getByText('Asha Nair').waitFor({ timeout: 10000 });
  await s.page.getByRole('tab', { name: /Duplicate review/ }).click();
  await s.page.getByText('Dup One').waitFor({ timeout: 5000 });
  const r = await rows(s);
  check('Q2: both server rows shown (no client filter on the flag)', r.length === 2 && r.some((x) => x.includes('Server Says So')), String(r.length));
  check('Q2: request carries filter=duplicate', calls(s).some((p) => p.get('filter') === 'duplicate'));
  await finish(s, 'Q2');
});

// Q3. Changing filter while page 2 is in flight: the late page-2 answer is dropped, not appended to the new list.
await run('Q3', async () => {
  const s = await open(async (route, req) => {
    const p = new URL(req.url()).searchParams;
    if (p.get('cursor') === 'C1') { await sleep(2500); return json(route, pg([row('late', 'Late Page Two')], null)); }
    if (p.get('filter') === 'duplicate') return json(route, pg([row('d1', 'Dup One', { duplicate: true })], null, 1));
    return json(route, pg([row('p1', 'Asha Nair'), row('p2', 'Bilal Khan')], 'C1'));
  });
  await s.page.getByText('Bilal Khan').waitFor({ timeout: 10000 });
  for (let i = 0; i < 20 && !calls(s).some((p) => p.get('cursor') === 'C1'); i++) await sleep(100);
  check('Q3: next page requested by infinite scroll with cursor C1', calls(s).some((p) => p.get('cursor') === 'C1' && p.get('filter') === 'all'));
  await s.page.getByRole('tab', { name: /Duplicate review/ }).click();
  await s.page.getByText('Dup One').waitFor({ timeout: 5000 });
  await sleep(3000);
  const r = await rows(s);
  check('Q3: stale page 2 not appended to the duplicate list', r.length === 1 && !r.some((x) => x.includes('Late Page Two')), r.join(' / '));
  await finish(s, 'Q3');
});

// Q4. 390 px, long list footer: no horizontal page scroll
await run('Q4', async () => {
  const many = Array.from({ length: 30 }, (_, i) => row('m' + i, 'Patient Number ' + i));
  const s = await open(async (route) => json(route, pg(many, null, 30)), { width: 390, height: 844 });
  await s.page.getByText('Patient Number 0', { exact: true }).waitFor({ timeout: 10000 });
  check('Q4: 390px no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth));
  await s.browser.close();
});

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
