// S16 ADM-032: licence register - days left, reminders sent, hidden-from-booking, owner override (step-up) with a 30-day cap.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3809 node s16-adm-032-licence-register.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';
const day = (n) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);
const L = (o) => ({ id: 'l1', licenceType: 'clinician', holderId: 's1', holderName: 'Dr Who', number: 'N1', issuingBody: 'DHA', expiryDate: day(-2), region: 'AE', status: 'expired', bookable: false, remindersSent: [60, 30, 7], overrideUntil: null, ...o });
const lic = [L({}), L({ id: 'l2', licenceType: 'facility', holderId: null, holderName: null, number: 'F1', expiryDate: day(20), status: 'expiring_soon', bookable: null, remindersSent: [60] })];
const base = (list, extra = []) => [...extra,
  ['GET', /^\/setup\/licences/, { body: list }],
  ['GET', /^\/staff(\?.*)?$/, { body: [{ id: 's1', name: 'Dr Who', role: 'Doctor', status: 'active' }] }],
  ['GET', /^\/setup\/(profile|subscription|checklist)/, { body: {} }],
  ['GET', /^\/compliance\/health/, { body: { items: [] } }]];
async function run(tag, perms, handlers, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms); await mockApi(page, log, handlers);
    await page.goto(APP + '/setup?tab=licences'); await sleep(2500); await fn({ page, log });
    check(`${tag}: zero console/page errors`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0, JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const stepUp = ['POST', /^\/auth\/mfa\/verify/, { body: { stepUpToken: 'su-token' } }];
await run('register', ['setup:view', 'setup:edit'], base(lic), async ({ page }) => {
  const t = await page.locator('table').first().innerText();
  check('expired row first (soonest expiry), days ago shown', t.indexOf('Dr Who') < t.indexOf('F1') && /2 days ago/.test(t), t);
  check('reminders sent shown', /60/.test(t) && /30/.test(t));
  check('hidden from booking marker on the lapsed doctor only', (await page.getByTestId('hidden-from-booking').count()) === 1);
});
await run('override happy', ['setup:view', 'setup:edit'], base(lic, [stepUp, ['POST', /^\/setup\/licences\/l1\/override/, { body: L({ bookable: true, overrideUntil: day(7) }) }]]), async ({ page, log }) => {
  await page.getByRole('button', { name: /Allow booking/ }).first().click(); await sleep(300);
  await page.getByLabel('Reason').fill('renewal submitted');
  await page.getByLabel('Authenticator code').fill('123456');
  await page.getByRole('button', { name: 'Allow booking' }).last().click(); await sleep(900);
  const r = log.requests.find((x) => x.method === 'POST' && /override/.test(x.path));
  check('override posted with reason and 7 days', !!r && /renewal submitted/.test(r.body ?? '') && (r.body ?? '').includes(day(7)), JSON.stringify(r));
  check('step-up token sent', !!r && JSON.stringify(r.headers ?? {}).includes('su-token'), JSON.stringify(r?.headers));
});
await run('override too long (422)', ['setup:view', 'setup:edit'], base(lic, [stepUp, ['POST', /^\/setup\/licences\/l1\/override/, { status: 422, body: { type: 'override-too-long', title: 'x', code: 'OVERRIDE_TOO_LONG' } }]]), async ({ page }) => {
  await page.getByRole('button', { name: /Allow booking/ }).first().click(); await sleep(300);
  await page.getByLabel('Reason').fill('renewal submitted'); await page.getByLabel('Authenticator code').fill('123456');
  await page.getByRole('button', { name: 'Allow booking' }).last().click(); await sleep(900);
  check('422 explains the 30-day cap and keeps the form', /at most 30 days/.test(await page.locator('main').innerText()) && (await page.getByLabel('Reason').inputValue()) === 'renewal submitted');
});
await run('no setup:edit', ['setup:view'], base(lic), async ({ page }) => {
  check('permission absent: no override control (not disabled)', (await page.getByRole('button', { name: /Allow booking/ }).count()) === 0);
});
await run('390px', ['setup:view', 'setup:edit'], base(lic), async ({ page }) => {
  const m = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }));
  check('390px: page has no horizontal scroll (table scrolls inside its wrapper)', m.s <= m.c, JSON.stringify(m));
}, { width: 390, height: 800 });
const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
