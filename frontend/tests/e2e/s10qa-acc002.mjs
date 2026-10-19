// S10 QA ACC-002 (independent): stale screen recovery on /arrivals. NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3411 node s10qa-acc002.mjs
import { launch, seedClinic, mockApi, check, sleep, results } from './qa-lib.mjs';
const APP = process.env.APP ?? 'http://localhost:3411';
const DOC = '33333333-3333-3333-3333-333333333331';
const row = (status) => ({ id: '55555555-5555-5555-5555-555555555551', appointmentId: null, patientId: '44444444-4444-4444-4444-444444444441', doctorId: DOC,
  departmentId: '66666666-6666-6666-6666-666666666666', queueDate: '2026-10-19', tokenNumber: 1, status, priority: false, priorityReason: null, createdAt: '2026-10-19T05:00:00Z', source: 'walk_in' });
const J = (r, o) => r.fulfill({ status: o.status ?? 200, contentType: o.status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(o.body) });

async function scenario(id, { perms = ['queue:view', 'queue:edit', 'patients:view'], viewport, patch, delay = 0 }, fn) {
  const { browser, page, log } = await launch(viewport ? { viewport } : {});
  const sent = []; let lists = 0;
  try {
    await seedClinic(page, perms);
    await mockApi(page, log, [
      ['GET', /^\/queue(\?|$)/, (r) => { lists++; return J(r, { body: [row(sent.length ? 'vitals_pending' : 'waiting')] }); }],
      ['PATCH', /^\/queue\/[^/]+\/status/, async (r, req) => { sent.push(JSON.parse(req.postData())); if (delay) await sleep(delay); return J(r, patch); }],
      ['GET', /^\/appointments\/callback-list/, { body: [] }], ['GET', /^\/staff\/roster/, { body: [{ id: DOC, name: 'Dr Amal Nasser', departmentName: 'General' }] }],
      ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 10 } }], ['GET', /^\/patients\//, { body: { name: 'Test Patient', mrn: 'MRN1' } }], ['GET', /^\/doctors\/[^/]+\/delay/, { body: [] }],
    ]);
    await page.goto(APP + '/arrivals'); await page.waitForLoadState('networkidle').catch(() => {});
    await page.evaluate(() => { window.__noReload = true; });
    await fn({ page, log, sent, lists: () => lists });
    check(`${id} zero page errors / console errors`, log.pageErrors.length === 0 && log.console.every((x) => /Failed to load resource/.test(x)), JSON.stringify([...log.pageErrors, ...log.console]).slice(0, 300));
  } catch (e) { check(`${id} scenario ran`, false, String(e).split('\n')[0]); } finally { await browser.close(); }
}
const stale = { status: 409, body: { type: 'https://x/problems/stale-status', title: 'Visit already moved', detail: 'moved', code: 'STALE_STATUS', currentStatus: 'vitals_pending' } };

await scenario('Q1-stale', { patch: stale }, async ({ page, sent, lists }) => {
  const before = lists();
  const t0 = Date.now();
  await page.getByRole('button', { name: 'Send for vitals' }).first().click();
  let shown = null;
  try { shown = await page.getByText(/Already moved to/).first().innerText({ timeout: 1000 }); } catch { /* not shown in 1s */ }
  const dt = Date.now() - t0;
  check('Q1a AC4: "Already moved to <stage>" visible within 1 s of the click', !!shown && /vitals pending/i.test(shown) && dt <= 1000, `${dt}ms "${shown}"`);
  check('Q1b request carried expectedStatus=waiting and status=vitals_pending', sent[0]?.expectedStatus === 'waiting' && sent[0]?.status === 'vitals_pending', JSON.stringify(sent));
  await sleep(600);
  check('Q1c queue refetched after 409', lists() > before);
  check('Q1d no full page reload', await page.evaluate(() => window.__noReload === true));
  check('Q1e AC4: message names who moved it ("by <name>")', !!shown && /by\s+\S+/i.test(shown), `"${shown}"`);
});
await scenario('Q2-doubleTap', { patch: { body: row('vitals_pending') }, delay: 700 }, async ({ page, sent }) => {
  const b = page.getByRole('button', { name: 'Send for vitals' }).first();
  await b.click(); await b.click({ force: true, timeout: 500 }).catch(() => {});
  await sleep(1200);
  check('Q2 double tap sends exactly one PATCH', sent.length === 1, `sent=${sent.length}`);
});
await scenario('Q3-noPermission', { perms: ['queue:view', 'patients:view'], patch: stale }, async ({ page }) => {
  check('Q3 without queue:edit the move button is absent (not disabled)', (await page.getByRole('button', { name: 'Send for vitals' }).count()) === 0);
});
await scenario('Q4-billingReserved', { patch: { status: 403, body: { type: 'https://x/problems/completion-reserved-for-billing', title: 'Completion is reserved for billing', detail: 'Only checkout can complete a visit.', code: 'COMPLETION_RESERVED_FOR_BILLING' } } }, async ({ page }) => {
  await page.getByRole('button', { name: 'Send for vitals' }).first().click();
  await page.getByText(/Only checkout can complete/).first().waitFor({ timeout: 2000 });
  check('Q4 non-stale refusal shows the server detail', true);
});
await scenario('Q5-mobile390', { patch: stale, viewport: { width: 390, height: 800 } }, async ({ page }) => {
  await page.getByRole('button', { name: 'Send for vitals' }).first().click();
  await page.getByText(/Already moved to/).first().waitFor({ timeout: 2000 });
  check('Q5 390px: no horizontal scroll with the stale message', !(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)));
});
const bad = results.filter((r) => !r.ok); console.log(`${results.length - bad.length}/${results.length} passed`); process.exit(bad.length ? 1 : 0);
