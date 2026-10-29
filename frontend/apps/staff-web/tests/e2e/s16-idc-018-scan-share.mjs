// S16 IDC-018: arrivals show the "via ABHA" pill for a Scan & Share check-in and one consent prompt in the detail rail.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3809 node s16-idc-018-scan-share.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';
const now = new Date(Date.now() - 600000).toISOString();
const today = new Date().toISOString().slice(0, 10);
const q = (id, n, source) => ({ id, appointmentId: null, patientId: 'p' + n, doctorId: 'd1', departmentId: 'dep1', queueDate: today, tokenNumber: n, status: 'waiting', priority: false, priorityReason: null, createdAt: now, source });
const base = (needsConsent) => [
  ['GET', /^\/queue(\?.*)?$/, { body: [q('a1', 1, 'abdm_scan_share'), q('a2', 2, 'walk_in')] }],
  ['GET', /^\/staff\/roster/, { body: [{ id: 'd1', name: 'Dr Who', departmentName: 'GP' }] }],
  ['GET', /^\/patients\/p(\d)\/consents/, { body: { needsConsent } }],
  ['GET', /^\/patients\/p(\d)/, (r, req, m) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ name: 'Patient ' + m[1], mrn: 'M' + m[1], phone: '+9665' + m[1] }) })],
  ['GET', /^\/patients\?q=/, { body: { data: [] } }],
  ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5 } }],
  ['GET', /^\/doctors\/.*\/delay/, { body: [] }],
  ['GET', /^\/appointments\/callback-list/, { body: [] }],
];
async function run(tag, perms, needsConsent, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms); await mockApi(page, log, base(needsConsent));
    await page.goto(APP + '/arrivals'); await sleep(2000); await fn({ page, log });
    check(`${tag}: zero console/page errors`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0, JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const perms = ['queue:view', 'patients:view'];
await run('scan and share row', perms, true, async ({ page, log }) => {
  check('only the Scan & Share row has the pill', (await page.locator('tbody tr').nth(0).getByText('Via ABHA').count()) >= 1 && (await page.locator('tbody tr').nth(1).getByText('Via ABHA').count()) === 0);
  await page.locator('tbody tr').nth(0).locator('td').nth(1).click(); await sleep(900);
  check('consent prompt shown once', (await page.getByTestId('scan-share-consent').count()) === 1);
  check('prompt links to the record by MRN', (await page.getByTestId('scan-share-consent').getByRole('link').getAttribute('href')) === '/patients?q=M1');
  check('consent state was read for that patient', log.requests.some((r) => /^\/patients\/p1\/consents/.test(r.path)));
});
await run('consent already recorded', perms, false, async ({ page }) => {
  await page.locator('tbody tr').nth(0).locator('td').nth(1).click(); await sleep(900);
  check('no prompt when consent exists', (await page.getByTestId('scan-share-consent').count()) === 0);
});
await run('walk-in has no prompt', perms, true, async ({ page, log }) => {
  await page.locator('tbody tr').nth(1).locator('td').nth(1).click(); await sleep(900);
  check('walk-in: no prompt, consent not read', (await page.getByTestId('scan-share-consent').count()) === 0 && !log.requests.some((r) => /consents/.test(r.path)));
});
await run('no patients:view', ['queue:view'], true, async ({ page, log }) => {
  await page.locator('tbody tr').nth(0).locator('td').nth(1).click(); await sleep(900);
  check('permission absent: prompt absent and consent never requested', (await page.getByTestId('scan-share-consent').count()) === 0 && !log.requests.some((r) => /consents/.test(r.path)));
});
await run('390px', perms, true, async ({ page }) => {
  await page.locator('tbody tr').nth(0).locator('td').nth(1).click(); await sleep(900);
  const m = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }));
  check('390px: no horizontal scroll', m.s <= m.c, JSON.stringify(m));
}, { width: 390, height: 800 });
const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
