// S8 REC6: S7-B02 (403 detail on arrivals vitals save) + S7-B04 (44px row actions at 390-543). Run: node s8-rec6-arrivals.mjs (server on :3106)
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP, SHOTS } from './s8-rec6-lib.mjs';
const now = new Date(Date.now() - 900000).toISOString();
const q = (id, status, n) => ({ id, appointmentId: null, patientId: 'p' + n, doctorId: 'd1', departmentId: 'dep1', queueDate: '2026-10-18', tokenNumber: n, status, priority: false, priorityReason: null, createdAt: now, source: 'walk_in' });
const queue = [q('a1', 'waiting', 1), q('a2', 'vitals_pending', 2), q('a3', 'checkout_pending', 3)];
const base = (rows = queue) => [
  ['GET', /^\/queue(\?.*)?$/, rows === 'err' ? { status: 500, body: { type: 'server-error', title: 'Internal Server Error' } } : { body: rows }],
  ['GET', /^\/staff\/roster/, { body: [{ id: 'd1', name: 'Dr Who', departmentName: 'GP' }] }],
  ['GET', /^\/patients\/p(\d)/, (r, req, m) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ name: 'Patient ' + m[1], mrn: 'M' + m[1], phone: '+9665' + m[1] }) })],
  ['GET', /^\/patients\?q=/, { body: { data: [] } }],
  ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5 } }],
  ['GET', /^\/doctors\/.*\/delay/, { body: [] }],
  ['GET', /^\/appointments\/callback-list/, { body: [] }],
];
async function run(tag, perms, fn, { viewport, rows, extra = [] } = {}) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms); await mockApi(page, log, [...extra, ...base(rows)]);
    await page.goto(APP + '/arrivals'); await sleep(1800); await fn({ page, log });
    check(`${tag} zero console/page errors`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0, JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const create = ['queue:view', 'clinical:create'];
const saveVitals = async (page) => {
  await page.getByRole('button', { name: /Record vitals/ }).click(); await sleep(300);
  await page.getByLabel(/Pulse/i).first().fill('70');
  await page.getByRole('button', { name: /Save vitals/ }).click(); await sleep(800);
};
const alertText = async (page) => (await page.getByRole('alert').allInnerTexts()).join(' | ');
const patch = (status, body) => [['PATCH', /^\/clinical\/vitals\//, { status, body }]];

// B02
await run('B02 403 with detail', create, async ({ page }) => {
  await saveVitals(page);
  const t = await alertText(page);
  check('B02 server detail shown', /needs clinical:edit/.test(t) && !/isn't allowed/.test(t), t);
  check('B02 dialog and typed value kept', (await page.getByLabel(/Pulse/i).first().inputValue()) === '70');
  await page.screenshot({ path: SHOTS + '/b02-detail.png' });
}, { extra: patch(403, { type: 'vitals-correction-forbidden', title: 'Forbidden', detail: 'Correcting a recorded vitals value needs clinical:edit.' }) });
await run('B02 403 no detail', create, async ({ page }) => {
  await saveVitals(page);
  check('B02 boundary: 403 without detail falls back to generic', /isn't allowed to do this/.test(await alertText(page)), await alertText(page));
}, { extra: patch(403, { type: 'forbidden', title: 'Forbidden' }) });
await run('B02 500', create, async ({ page }) => {
  await saveVitals(page);
  check('B02 500 keeps server-problem message', /server had a problem/.test(await alertText(page)), await alertText(page));
}, { extra: patch(500, { type: 'server-error', title: 'Internal Server Error', detail: 'stack trace here' }) });
await run('B02 400 detail', create, async ({ page }) => {
  await saveVitals(page);
  check('B02 400 still shows detail', /Pulse out of range/.test(await alertText(page)), await alertText(page));
}, { extra: patch(400, { type: 'validation', title: 'Bad Request', detail: 'Pulse out of range.' }) });
await run('B02 success', create, async ({ page, log }) => {
  await saveVitals(page);
  check('B02 happy path: PATCH sent, dialog closed', log.requests.some((r) => r.method === 'PATCH' && /vitals\/a2/.test(r.path)) && (await page.getByRole('button', { name: /Save vitals/ }).count()) === 0);
}, { extra: patch(200, { abnormalFlags: [] }) });

// permission absent, empty, error
await run('perm', ['queue:view', 'clinical:view'], async ({ page }) => {
  check('perm: Record vitals absent (not disabled)', (await page.getByRole('button', { name: /Record vitals/ }).count()) === 0 && (await page.locator('button[disabled]').filter({ hasText: /vitals/i }).count()) === 0);
});
await run('empty', create, async ({ page }) => {
  check('empty: no rows, no row buttons', (await page.getByRole('button', { name: /Record vitals|Send for vitals/ }).count()) === 0 && (await page.locator('main').innerText()).trim().length > 0, (await page.locator('main').innerText()).slice(0, 200).replace(/\n/g, ' | '));
}, { rows: [] });
await run('error', create, async ({ page }) => {
  check('error: visible error state, no row buttons', (await page.getByRole('alert').count()) + (await page.getByText(/couldn.t|problem|failed|try again/i).count()) > 0 && (await page.getByRole('button', { name: /Record vitals/ }).count()) === 0);
}, { rows: 'err' });

// B04
const full = ['queue:view', 'queue:create', 'queue:edit', 'patients:view', 'billing:view', 'billing:create', 'clinical:create'];
for (const w of [390, 430, 480, 543, 544, 1280]) {
  await run(`B04 w${w}`, full, async ({ page }) => {
    const m = await page.evaluate(() => ({
      dsw: document.documentElement.scrollWidth, dcw: document.documentElement.clientWidth,
      hs: [...document.querySelectorAll('main table button, main [class*="actionBtn"]')].filter((b) => b.className.includes('actionBtn') && b.offsetParent).map((b) => ({ t: b.innerText.trim(), h: Math.round(b.getBoundingClientRect().height) })),
    }));
    check(`B04 w${w} found row action buttons`, m.hs.length >= 3, JSON.stringify(m.hs));
    if (w <= 543) check(`B04 w${w} every row action >= 44px`, m.hs.every((b) => b.h >= 44), JSON.stringify(m.hs));
    else check(`B04 w${w} desktop unchanged (compact, <44px)`, m.hs.every((b) => b.h < 44), JSON.stringify(m.hs));
    check(`B04 w${w} no horizontal page scroll`, m.dsw <= m.dcw, `${m.dsw}/${m.dcw}`);
    if (w === 390 || w === 543) await page.screenshot({ path: `${SHOTS}/b04-${w}.png` });
  }, { viewport: { width: w, height: 900 } });
}
const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
