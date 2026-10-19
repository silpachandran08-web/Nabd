// ACC-002: a stale arrivals screen gets 409 STALE_STATUS and recovers without a reload.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3401 node tests/e2e/s10-acc-002-stale-queue-move.mjs
import { launch, seedClinic, mockApi, check, sleep, results } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3000';
const DOC = '33333333-3333-3333-3333-333333333331';
const row = (status) => ({
  id: '55555555-5555-5555-5555-555555555551', appointmentId: null, patientId: '44444444-4444-4444-4444-444444444441', doctorId: DOC,
  departmentId: '66666666-6666-6666-6666-666666666666', queueDate: '2026-10-18', tokenNumber: 1, status, priority: false, priorityReason: null,
  createdAt: '2026-10-18T05:00:00Z', source: 'walk_in',
});
const problem = (res, extra = {}) => ({ status: res, body: { type: 'https://x/problems/stale-status', title: 'Visit already moved', detail: 'no', ...extra } });

async function run(tag, { patch, serverStatusAfter = 'vitals_pending', viewport }) {
  const { browser, page, log } = await launch(viewport ? { viewport } : {});
  const sent = [];
  let listCalls = 0;
  try {
    await seedClinic(page, ['queue:view', 'queue:edit', 'patients:view']);
    await mockApi(page, log, [
      ['GET', /^\/queue(\?|$)/, (route) => { listCalls++; return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' },
        body: JSON.stringify([row(sent.length === 0 ? 'waiting' : serverStatusAfter)]) }); }],
      ['PATCH', /^\/queue\/[^/]+\/status/, (route, req) => { sent.push(JSON.parse(req.postData())); return route.fulfill({ ...patch, contentType: 'application/problem+json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify(patch.body) }); }],
      ['GET', /^\/appointments\/callback-list/, { body: [] }],
      ['GET', /^\/staff\/roster/, { body: [{ id: DOC, name: 'Dr Amal Nasser', departmentName: 'General' }] }],
      ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 10 } }],
      ['GET', /^\/patients\//, { body: { name: 'Test Patient', mrn: 'MRN1' } }],
      ['GET', /^\/doctors\/[^/]+\/delay/, { body: [] }],
    ]);
    await page.goto(APP + '/arrivals');
    await page.waitForLoadState('networkidle').catch(() => {});
    await page.evaluate(() => { window.__noReload = true; });
    const before = listCalls;
    await page.getByRole('button', { name: 'Send for vitals' }).first().click();
    return { page, log, sent, refetched: () => listCalls > before, finish: async () => browser.close() };
  } catch (e) { await browser.close(); throw e; }
}

// 1. stale: 409 -> friendly message within 1s, refetch, no reload, expectedStatus sent
{
  const r = await run('stale', { patch: problem(409, { currentStatus: 'vitals_pending' }) });
  await r.page.getByText(/Already moved to vitals pending/).waitFor({ timeout: 1000 }); // throws if not within 1 s of the click
  check('S1 message within 1 s', true);
  check('S2 request carried expectedStatus waiting', r.sent[0]?.expectedStatus === 'waiting' && r.sent[0]?.status === 'vitals_pending', JSON.stringify(r.sent));
  await sleep(500);
  check('S3 queue refetched', r.refetched());
  check('S4 no full reload', await r.page.evaluate(() => window.__noReload === true));
  check('S5 row now shows the real stage', /vitals/i.test(await r.page.locator('body').innerText()));
  check('S6 no console/page errors', r.log.pageErrors.length === 0 && r.log.console.every((x) => /Failed to load resource/.test(x)), JSON.stringify([...r.log.console, ...r.log.pageErrors]));
  await r.finish();
}
// 2. error: a non-stale failure shows the server detail
{
  const r = await run('err', { patch: { status: 400, body: { type: 'https://x/problems/illegal-transition', title: 'Illegal', detail: 'Cannot move from waiting to x.' } }, serverStatusAfter: 'waiting' });
  await r.page.getByText('Cannot move from waiting to x.').waitFor({ timeout: 2000 });
  check('E1 other errors keep their detail', true);
  await r.finish();
}
// 3. 390px: stale message, no horizontal scroll
{
  const r = await run('mobile', { patch: problem(409, { currentStatus: 'vitals_pending' }), viewport: { width: 390, height: 800 } });
  await r.page.getByText(/Already moved to vitals pending/).waitFor({ timeout: 2000 });
  check('M1 390px no horizontal scroll', !(await r.page.evaluate(() => document.documentElement.scrollWidth > innerWidth)));
  await r.finish();
}
const failed = results.filter((x) => !x.ok);
console.log(failed.length ? `FAILED ${failed.length}` : `ALL ${results.length} PASSED`);
process.exit(failed.length ? 1 : 0);
