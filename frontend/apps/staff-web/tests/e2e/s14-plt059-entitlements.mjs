// S14 PLT-059 / ADM-034 staff-web: plan-driven nav, kill banner, seat meter and module list on the plan tab, plan editor, operator feature switches.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3807 node s14-plt059-entitlements.mjs
import { launch, seedClinic, seedPlatform, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const MODS = ['billing', 'packages', 'pharmacy_inhouse', 'pharmacy_external', 'lab'];
const ent = (enabled, killed = []) => ({ body: { modules: MODS.map((code) => ({ code, enabled: enabled.includes(code) })), killedFeatures: killed, seatLimit: 5 } });
const clinicPerms = ['queue:view', 'patients:view', 'pharmacy:view', 'packages:view', 'billing:view'];
const base = (e, extra = []) => [...extra, ['GET', /^\/me\/entitlements$/, e], ['GET', /^\/setup\/profile$/, { body: { timezone: 'Asia/Kolkata', region: 'IN' } }], ['GET', /^\/queue/, { body: [] }], ['GET', /^\/appointments\?/, { body: { data: [], page: { nextCursor: null, limit: 100 } } }], ['GET', /^\/doctors\/.*\/delay/, { body: [] }], ['GET', /^\/patients/, { body: { data: [], page: { nextCursor: null, limit: 25 } } }]];
const clean = (log) => log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0;
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const navLinks = async (page) => (await page.locator('nav a').allInnerTexts()).join(' | ');

async function clinic(tag, perms, handlers, url, fn, viewport, extra) {
  const { browser, page, log } = await launch({ viewport });
  try { await seedClinic(page, perms, extra); await mockApi(page, log, handlers); await page.goto(APP + url); await sleep(2000); await fn({ page, log }); check(`${tag} zero console errors`, clean(log), JSON.stringify([...log.pageErrors, ...log.console])); }
  finally { dumpLog(tag, log); await browser.close(); }
}

await clinic('plan lacks pharmacy and packages', clinicPerms, base(ent(['billing'], [])), '/account', async ({ page }) => {
  const nav = await navLinks(page);
  check('nav: Billing kept, Pharmacy and Treatment Packages absent (not disabled)', /Billing/.test(nav) && !/Pharmacy/.test(nav) && !/Packages/.test(nav) && /Patients/.test(nav), nav);
  check('no kill banner when nothing is killed', (await page.getByRole('status').filter({ hasText: 'switched off' }).count()) === 0);
});
await clinic('plan includes them', clinicPerms, base(ent(['billing', 'packages', 'pharmacy_external'])), '/account', async ({ page }) => {
  const nav = await navLinks(page);
  check('nav: Pharmacy and Packages present when in plan', /Pharmacy/.test(nav) && /Packages/.test(nav), nav);
});
await clinic('kill switch banner', clinicPerms, base(ent(['billing', 'packages', 'pharmacy_inhouse'], ['whatsapp_bot'])), '/account', async ({ page }) => {
  const t = (await page.getByRole('status').allInnerTexts()).join(' ');
  check('banner names the WhatsApp assistant and says other features keep working', /WhatsApp assistant is switched off/.test(t) && /Other features keep working/.test(t), t);
});
await clinic('entitlements call fails', clinicPerms, base({ status: 500, body: { type: 'server-error', title: 'x' } }), '/account', async ({ page }) => {
  const nav = await navLinks(page);
  check('error: nothing hidden, no banner (server still enforces)', /Pharmacy/.test(nav) && /Packages/.test(nav) && (await page.getByRole('status').filter({ hasText: 'switched off' }).count()) === 0, nav);
});
const sub = (extra) => ({ body: { plan: 'Clinic Plus', status: 'active', activePatientsUsed: 10, activePatientsLimit: 500, whatsappMessagesUsed: 0, whatsappMessagesLimit: 3000, branchesUsed: 1, branchesLimit: 3, ...extra } });
const setupBase = (s) => [...base(ent(['billing'])), ['GET', /^\/setup\/subscription$/, s], ['GET', /^\/setup\//, { body: [] }]];
await clinic('plan tab', ['setup:view', 'queue:view'], setupBase(sub({ seatsUsed: 3, seatsLimit: 5, killedFeatures: ['whatsapp_bot'], modules: [{ code: 'billing', enabled: true }, { code: 'lab', enabled: false }, { code: 'whatsapp_bot', enabled: true }] })), '/setup?tab=subscription', async ({ page }) => {
  check('seat meter reads 3 of 5', (await page.getByTestId('seat-meter').innerText()).includes('3 of 5 seats used'));
  const list = await page.getByTestId('module-list').innerText();
  check('modules: included / not in plan / switched off', /billing: included/.test(list) && /lab: not in your plan/.test(list) && /whatsapp bot: switched off by Nabd/.test(list), list);
}, undefined, { owner: true });
await clinic('plan tab empty (no subscription yet)', ['setup:view', 'queue:view'], setupBase(sub({ plan: null, seatsUsed: 1, seatsLimit: null, killedFeatures: [], modules: [] })), '/setup?tab=subscription', async ({ page }) => {
  check('unlimited seats wording and no plan', (await page.getByTestId('seat-meter').innerText()).includes('no limit') && (await page.getByText('No plan assigned yet').count()) === 1);
}, undefined, { owner: true });
await clinic('390px', clinicPerms, base(ent(['billing'], ['whatsapp_bot'])), '/account', async ({ page }) => {
  check('390px: no horizontal scroll with the banner', await noOverflow(page));
}, { width: 390, height: 800 });

async function platform(tag, role, handlers, url, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedPlatform(page);
    await mockApi(page, log, [['GET', /^\/platform\/auth\/me$/, { body: { id: 'o1', name: 'Op', email: 'op@nabd.internal', role, permissions: [], mfaEnabled: true } }], ...handlers]);
    await page.goto(APP + url); await sleep(2000); await fn({ page, log });
    check(`${tag} zero console errors`, clean(log), JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const TENANT = '33333333-3333-3333-3333-333333333333';
let put = null;
const features = (rows) => [['GET', new RegExp(`^/platform/tenants/${TENANT}/features$`), { body: rows }],
  ['PUT', new RegExp(`^/platform/tenants/${TENANT}/features/`), (route, req) => { put = { path: req.url(), body: JSON.parse(req.postData()) }; return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }); }]];
await platform('switches super_admin', 'super_admin', features([{ code: 'whatsapp_bot', state: 'kill', reason: 'loop', setAt: new Date().toISOString(), expiresAt: null }]), `/platform/fleet/${TENANT}/features`, async ({ page }) => {
  check('override listed with its state', (await page.getByTestId('override-whatsapp_bot').innerText()).includes('kill'));
  await page.locator('#feature').selectOption('pharmacy_inhouse');
  await page.getByRole('button', { name: 'Save switch' }).click(); await sleep(300);
  check('reason missing -> nothing sent', put === null);
  await page.locator('#reason').fill('Recall of batch');
  await page.getByRole('button', { name: 'Save switch' }).click(); await sleep(600);
  check('PUT to the feature path with state and reason', put && put.path.endsWith('/features/pharmacy_inhouse') && put.body.state === 'kill' && put.body.reason === 'Recall of batch', JSON.stringify(put));
});
await platform('switches empty', 'super_admin', features([]), `/platform/fleet/${TENANT}/features`, async ({ page }) => {
  check('empty state says the clinic follows its plan', (await page.getByText('This clinic follows its plan.').count()) === 1);
});
await platform('switches billing role', 'billing', features([]), `/platform/fleet/${TENANT}/features`, async ({ page }) => {
  check('form absent for a role without tenant_detail:edit', (await page.getByRole('button', { name: 'Save switch' }).count()) === 0);
});
await platform('switches error', 'super_admin', [['GET', new RegExp(`^/platform/tenants/${TENANT}/features$`), { status: 500, body: { type: 'server-error', title: 'x' } }]], `/platform/fleet/${TENANT}/features`, async ({ page }) => {
  check('error state with retry', /Couldn.t load the feature switches/.test((await page.locator('main').innerText())));
});
await platform('plan editor', 'billing', [['GET', /^\/platform\/plans$/, { body: [{ id: 'p1', code: 'plus', name: 'Clinic Plus', monthlyPriceCents: 150000, currency: 'AED', seatLimit: 15, active: true, version: 2, pricingBasis: 'per_doctor', branchLimit: 3, modules: ['billing', 'hr'] }] }]], '/platform/plans', async ({ page }) => {
  check('currency offers AED and QAR', (await page.locator('#currency option').allInnerTexts()).join() === 'INR,SAR,AED,QAR');
  check('module checklist lists the 14 closed-list modules', (await page.getByTestId('module-checklist').locator('input[type=checkbox]').count()) === 14);
  check('row shows modules count and version', /2\s+v2/.test((await page.locator('tbody tr').first().innerText()).replace(/\s+/g, ' ')), await page.locator('tbody tr').first().innerText());
  await page.getByRole('button', { name: 'Edit' }).click();
  check('edit preloads the plan\'s modules', (await page.getByTestId('module-checklist').locator('input:checked').count()) === 2);
});
const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
