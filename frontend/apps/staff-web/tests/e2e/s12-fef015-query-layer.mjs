// FEF-015: Arrivals + Nursing on TanStack Query. Request dedupe with the sidebar badge, focus refetch of stale
// queries only, optimistic "sent for vitals" with rollback on 409, corrected name on focus, Nursing request budget.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3717 node tests/e2e/s12-fef015-query-layer.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3000';
const DOC = '33333333-3333-3333-3333-333333333331';
const RECEPTION = ['queue:view', 'queue:edit', 'queue:create', 'patients:view'];
const entry = (n, status, patientId = `p${n}`) => ({
  id: `q${n}`, appointmentId: null, patientId, doctorId: DOC, departmentId: 'd1', queueDate: '2026-10-20', tokenNumber: n,
  status, priority: false, priorityReason: null, createdAt: new Date().toISOString(), source: 'walk_in',
});
const json = (route, body, status = 200) => route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const count = (log, re, method = 'GET') => log.requests.filter((r) => r.method === method && re.test(r.path)).length;
// A real tab switch: visibilitychange bubbles from document to window (TanStack's focusManager listens on window).
const setVis = (page, v) => page.evaluate((v) => {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => v });
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => v === 'hidden' });
  document.dispatchEvent(new Event('visibilitychange', { bubbles: true }));
}, v);
const refocus = async (page) => { await setVis(page, 'hidden'); await setVis(page, 'visible'); };
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const cleanConsole = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|ERR_ABORTED/.test(x))];

function arrivalsHandlers(state, extra = []) {
  return [
    ...extra,
    ['GET', /^\/queue(\?|$)/, (route) => json(route, state.queue)],
    ['GET', /^\/staff\/roster/, { body: [{ id: DOC, name: 'Dr Amal Nasser', departmentName: 'General' }] }],
    ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 10 } }],
    ['GET', /^\/doctors\/[^/]+\/delay/, { body: [] }],
    ['GET', /^\/appointments\/callback-list/, { body: [] }],
    ['GET', /^\/patients\/([^/?]+)$/, (route, req, m) => json(route, { id: m[1], name: state.names[m[1]] ?? 'Someone', mrn: `MRN-${m[1]}` })],
    ['GET', /./, { body: [] }],
  ];
}

async function openArrivals({ perms = RECEPTION, state, extra, viewport } = {}) {
  const ctx = await launch(viewport ? { viewport } : {});
  await seedClinic(ctx.page, perms);
  await mockApi(ctx.page, ctx.log, arrivalsHandlers(state, extra));
  await ctx.page.goto(APP + '/arrivals');
  await ctx.page.waitForLoadState('networkidle').catch(() => {});
  await sleep(500);
  return ctx;
}

// AC1 + AC2 + AC4: one /queue for board + badge; focus refetches stale queries once, fresh ones not; corrected name.
{
  // Two rows share one patient: one /patients/{id} for both (query cache dedupe).
  const state = { queue: [entry(1, 'waiting'), entry(2, 'checked_in'), entry(3, 'waiting', 'p1'), entry(4, 'checkout_pending')], names: { p1: 'Layla Haddad', p2: 'Omar Saleh', p4: 'Nour Aziz' } };
  const { page, browser, log } = await openArrivals({ state });
  await page.evaluate(() => { window.__noReload = true; });
  check('AC1 one GET /queue for Arrivals + sidebar badge', count(log, /^\/queue$/) === 1, `GET /queue x${count(log, /^\/queue$/)}`);
  const badge = await page.locator('nav a[href="/arrivals"]').first().innerText();
  check('AC1 badge reads the shared data (3 waiting)', /\b3\b/.test(badge), JSON.stringify(badge));
  check('AC1 board rendered from the same response', (await page.locator('tbody tr').count()) === 4);
  check('dedupe: one /patients/p1 for two rows', count(log, /^\/patients\/p1$/) === 1, `x${count(log, /^\/patients\/p1$/)}`);
  check('no per-row patient request repeats on load', count(log, /^\/patients\//) === 3, `x${count(log, /^\/patients\//)}`);

  const before = { queue: count(log, /^\/queue$/), roster: count(log, /^\/staff\/roster/), cb: count(log, /^\/appointments\/callback-list/), p1: count(log, /^\/patients\/p1$/), est: count(log, /^\/queue\/wait-estimate/) };
  state.names.p1 = 'Layla Haddad-Corrected';
  await refocus(page);
  await sleep(1200);
  const d = (re, k) => count(log, re) - before[k];
  check('AC2 stale /queue refetched exactly once on focus', d(/^\/queue$/, 'queue') === 1, `+${d(/^\/queue$/, 'queue')}`);
  check('AC2 stale patient query refetched exactly once', d(/^\/patients\/p1$/, 'p1') === 1, `+${d(/^\/patients\/p1$/, 'p1')}`);
  check('AC2 stale wait estimate refetched once', d(/^\/queue\/wait-estimate/, 'est') === 1, `+${d(/^\/queue\/wait-estimate/, 'est')}`);
  check('AC2 fresh roster (10 min) not refetched', d(/^\/staff\/roster/, 'roster') === 0, `+${d(/^\/staff\/roster/, 'roster')}`);
  check('AC2 fresh callback list not refetched', d(/^\/appointments\/callback-list/, 'cb') === 0, `+${d(/^\/appointments\/callback-list/, 'cb')}`);
  const text = await page.locator('main').innerText();
  check('AC4 corrected registry name shows after focus', text.includes('Layla Haddad-Corrected'));
  check('AC4 without a full reload', await page.evaluate(() => window.__noReload === true));
  check('zero console/page errors (AC1-4)', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  dumpLog('ac1-4', log);
  await browser.close();
}

// AC3: optimistic "sent for vitals"; 409 rolls the row back and shows the error toast.
async function optimistic(tag, viewport) {
  const state = { queue: [entry(1, 'waiting')], names: { p1: 'Layla Haddad' } };
  let release;
  const gate = new Promise((r) => { release = r; });
  const sent = [];
  const ctx = await openArrivals({ state, viewport, extra: [
    ['PATCH', /^\/queue\/q1\/status$/, async (route, req) => {
      sent.push(JSON.parse(req.postData()));
      await gate;
      return json(route, { type: 'https://nabd/problems/stale-status', title: 'Visit already moved', detail: 'Visit already moved', currentStatus: 'in_consult' }, 409);
    }],
  ] });
  const { page, browser, log } = ctx;
  const row = page.locator('tbody tr').first();
  const t0 = Date.now();
  await page.getByRole('button', { name: 'Send for vitals' }).first().click();
  await row.getByText('Vitals pending').waitFor({ timeout: 1000 });
  check(`AC3 ${tag}: row shows Vitals pending while the PATCH is in flight`, sent.length === 1, `${Date.now() - t0} ms, ${sent.length} PATCH`);
  check(`AC3 ${tag}: move is compare-and-set (expectedStatus waiting)`, sent[0]?.status === 'vitals_pending' && sent[0]?.expectedStatus === 'waiting', JSON.stringify(sent[0]));
  const queueBefore = count(log, /^\/queue$/);
  release();
  await page.getByRole('alert').filter({ hasText: /Already moved to in consult/ }).waitFor({ timeout: 2000 });
  check(`AC3 ${tag}: error toast on 409`, true);
  await row.getByText('Waiting').waitFor({ timeout: 2000 });
  check(`AC3 ${tag}: row rolled back to Waiting`, !(await row.getByText('Vitals pending').isVisible()));
  await sleep(400);
  check(`AC3 ${tag}: queue re-read after the move`, count(log, /^\/queue$/) > queueBefore);
  if (viewport) check(`AC3 ${tag}: no horizontal scroll at 390 px`, await noOverflow(page));
  check(`AC3 ${tag}: zero console/page errors`, cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  dumpLog(`ac3-${tag}`, log);
  await browser.close();
}
await optimistic('desktop');
await optimistic('390px', { width: 390, height: 800 });

// Permission absent: no queue:view -> no /queue (board or badge), the no-access state, Send for vitals absent.
{
  const { page, browser, log } = await openArrivals({ perms: ['patients:view'], state: { queue: [entry(1, 'waiting')], names: {} } });
  check('perm-absent: no /queue request', count(log, /^\/queue/) === 0, `x${count(log, /^\/queue/)}`);
  check('perm-absent: no-access state', await page.getByText('No access to the arrivals board').isVisible());
  await browser.close();
  const r2 = await openArrivals({ perms: ['queue:view'], state: { queue: [entry(1, 'waiting')], names: {} } });
  check('perm-absent: no queue:edit -> Send for vitals absent', (await r2.page.getByRole('button', { name: 'Send for vitals' }).count()) === 0);
  check('perm-absent: no patients:view -> no /patients requests', count(r2.log, /^\/patients/) === 0);
  await r2.browser.close();
}

// Empty and error states.
{
  let r = await openArrivals({ state: { queue: [], names: {} } });
  check('empty: "No patients here yet"', await r.page.getByText('No patients here yet').isVisible());
  await r.browser.close();
  r = await openArrivals({ state: { queue: [], names: {} }, extra: [['GET', /^\/queue$/, { status: 500, body: { title: 'boom' } }]] });
  check("error: 500 shows \"Couldn't load today's arrivals\"", await r.page.getByText("Couldn't load today's arrivals").isVisible());
  await r.browser.close();
}

// AC6: Nursing with 40 worklist entries loads in <= 8 requests.
{
  const { page, browser, log } = await launch({ viewport: { width: 390, height: 800 } });
  await seedClinic(page, ['queue:view', 'nursing:view', 'clinical:view', 'clinical:create']);
  const items = Array.from({ length: 40 }, (_, i) => ({
    entry: { ...entry(i + 1, i < 25 ? 'vitals_pending' : 'in_consult'), priority: i % 10 === 0, priorityReason: i % 10 === 0 ? 'Severe pain' : null },
    patient: { name: `Patient ${i + 1}`, mrn: `M${i + 1}`, dob: '1980-01-01', gender: 'f', allergies: [], chronicConditions: [] },
    vitals: i < 25 ? null : { recordedAt: new Date().toISOString(), abnormalFlags: [] },
  }));
  await mockApi(page, log, [
    ['GET', /^\/nursing\/worklist$/, { body: { items } }],
    ['GET', /^\/staff\/roster/, { body: [{ id: DOC, name: 'Dr Amal Nasser' }] }],
    ['GET', /^\/queue\?priority=true$/, { body: items.filter((x) => x.entry.priority).map((x) => x.entry) }],
    ['GET', /./, { body: [] }],
  ]);
  await page.goto(APP + '/nursing');
  await page.waitForLoadState('networkidle').catch(() => {});
  await sleep(1500);
  const n = log.requests.length;
  check('AC6 nursing with 40 entries: <= 8 requests', n <= 8, `${n}: ${log.requests.map((r) => r.path).join(', ')}`);
  check('AC6 no per-row /patients or /clinical/vitals requests', count(log, /^\/(patients|clinical\/vitals)\//) === 0);
  check('AC6 administration orders fetched once (page + badge share it)', count(log, /^\/nursing\/administration-orders\/today/) === 1);
  check('AC6 all 25 due rows rendered', (await page.getByText(/^Patient \d+$/).count()) >= 25, `${await page.getByText(/^Patient \d+$/).count()}`);
  check('nursing: no horizontal scroll at 390 px', await noOverflow(page));
  check('nursing: zero console/page errors', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  dumpLog('ac6', log);
  await browser.close();
}

// Nursing permission absent: nursing:view without queue:view -> no worklist request, no-access section.
{
  const { page, browser, log } = await launch();
  await seedClinic(page, ['nursing:view']);
  await mockApi(page, log, [['GET', /./, { body: [] }]]);
  await page.goto(APP + '/nursing');
  await sleep(1500);
  check('nursing perm-absent: no /nursing/worklist request', count(log, /^\/nursing\/worklist/) === 0);
  check('nursing perm-absent: no-access state', await page.getByText('No access to the patient queue').first().isVisible());
  await browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
