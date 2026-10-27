// S16 FEF-058 owner shell: sixteen areas in five sections (prototype order inside each), permission + entitlement gating (absent; direct URL = no-access),
// the Plan screen's upgrade option, badges, tablet rail / phone bottom nav (top 4 + More), chain owner "All facilities", 390px, zero console errors.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3813 node tests/e2e/s16-fef-058-owner-shell.mjs
import { launch, seedClinic, clinicToken, jwt, mockApi, check, results, dumpLog, sleep, APP } from './qa-lib.mjs';

const MODULES = ['queue_eta', 'whatsapp_booking', 'emr_erx', 'billing', 'pharmacy_external', 'pharmacy_inhouse', 'lab', 'packages', 'branded_site', 'hr', 'insurance', 'ipd', 'patient_app', 'ai_safety'];
const ent = (codes) => ({ body: { modules: MODULES.map((code) => ({ code, enabled: codes.includes(code) })), killedFeatures: [], seatLimit: null } });
const OWNER = ['reports:view', 'queue:view', 'billing:view', 'packages:view', 'staff:view', 'setup:view', 'compliance:view'];
const q = (status, id) => ({ id, doctorId: null, status, tokenNumber: 1, patientId: 'p1', priority: false });
const base = (codes, queue = []) => [
  ['GET', /^\/me\/entitlements/, ent(codes)],
  ['GET', /^\/queue/, { body: queue }],
  ['GET', /^\/setup\/subscription/, { body: { plan: 'Growth', status: 'active', branchesUsed: 3, branchesLimit: 5 } }],
  ['GET', /./, { body: [] }],
];
const calm = (s) => !s.log.pageErrors.length && !s.log.console.filter((x) => !/Failed to load resource|preload/.test(x)).length;
const side = (p) => p.locator('nav[aria-label="Main"]').first();
const hrefsOf = (p) => side(p).locator('a').evaluateAll((as) => as.map((a) => a.getAttribute('href')));
async function open(perms, handlers, path, viewport) {
  const s = await launch(viewport ? { viewport } : {});
  await seedClinic(s.page, perms, { owner: true });
  await mockApi(s.page, s.log, handlers);
  await s.page.goto(`${APP}${path}`, { waitUntil: 'networkidle' });
  return s;
}
const EXPECT = ['/overview', '/schedule', '/workflows', '/integrations', '/setup', '/reports', '/staff', '/hr', '/billing', '/plan', '/packages', '/payouts', '/growth', '/booking-page', '/compliance', '/setup?tab=licences'];

// 1. all modules + grants: order, sections, labels
{
  const s = await open(OWNER, base(MODULES, [q('waiting', 'a'), q('waiting', 'b'), q('checkout_pending', 'c')]), '/overview');
  const hrefs = await hrefsOf(s.page);
  check('1a owner nav: sixteen areas in section order, prototype order inside each section', JSON.stringify(hrefs) === JSON.stringify(EXPECT), hrefs.join(' '));
  const heads = await side(s.page).locator('[class*=sectionLabel]').allTextContents();
  check('1b five section headings in order', JSON.stringify(heads) === JSON.stringify(['Run the clinic', 'People & access', 'Money', 'Growth', 'Compliance']), heads.join('|'));
  check('1c lands on the Overview home (first item is current)', (await side(s.page).locator('a[aria-current=page]').getAttribute('href')) === '/overview');
  check('1d badges: Clinic Operations 2 waiting, Billing 1 checkout', (await side(s.page).locator('a[href="/schedule"] span').filter({ hasText: /^2$/ }).count()) > 0 && (await side(s.page).locator('a[href="/billing"] span').filter({ hasText: /^1$/ }).count()) > 0);
  await s.page.goto(`${APP}/workflows`, { waitUntil: 'networkidle' });
  check('1e a built-later area opens its skeleton for an entitled owner', (await s.page.getByText('Workflows is not available yet.').count()) === 1);
  for (const p of ['/integrations', '/growth', '/booking-page', '/payouts', '/hr']) { await s.page.goto(`${APP}${p}`, { waitUntil: 'networkidle' }); check(`1f ${p} skeleton renders`, (await s.page.getByText(/is not available yet\./).count()) === 1); }
  await s.page.goto(`${APP}/plan`, { waitUntil: 'networkidle' });
  check('1g Plan with every module: nothing to upgrade', (await s.page.getByText('Every area is included in your plan.').count()) === 1);
  check('1h zero console errors', calm(s)); dumpLog('owner-all', s.log);
  await s.browser.close();
}

// 2. HR not on the plan: item absent, direct URL = no access, Plan lists the upgrade option
{
  const s = await open(OWNER, base(MODULES.filter((m) => m !== 'hr')), '/overview');
  const hrefs = await hrefsOf(s.page);
  check('2a HR absent from the nav, fifteen items remain', !hrefs.includes('/hr') && hrefs.length === 15, hrefs.join(' '));
  await s.page.goto(`${APP}/hr`, { waitUntil: 'networkidle' });
  check('2b direct /hr shows the no-access state', (await s.page.getByText('You do not have access to HR & Payroll.').count()) === 1);
  await s.page.goto(`${APP}/plan`, { waitUntil: 'networkidle' });
  const opt = s.page.getByTestId('upgrade-hr');
  check('2c Plan shows HR & Payroll as an upgrade option', (await opt.count()) === 1 && /HR and payroll/.test(await opt.innerText()), await s.page.locator('main').innerText());
  check('2d only HR is offered', (await s.page.locator('[data-testid^=upgrade-]').count()) === 1);
  check('2e zero console errors', calm(s)); await s.browser.close();
}

// 3. permission missing: absent, and direct URL = no access
{
  const s = await open(OWNER.filter((p) => p !== 'billing:view'), base(MODULES), '/overview');
  const hrefs = await hrefsOf(s.page);
  check('3a without billing:view Billing and Payouts are absent', !hrefs.includes('/billing') && !hrefs.includes('/payouts'), hrefs.join(' '));
  await s.page.goto(`${APP}/payouts`, { waitUntil: 'networkidle' });
  check('3b direct /payouts shows the no-access state', (await s.page.getByText('You do not have access to Payouts.').count()) === 1);
  await s.browser.close();
}

// 4. empty state: Plan with no entitlements loaded is calm
{
  const s = await open(OWNER, [['GET', /^\/me\/entitlements/, { status: 500, body: { title: 'x' } }], ['GET', /./, { body: [] }]], '/plan');
  check('4a Plan with entitlements unavailable explains it, no crash', (await s.page.getByText('Plan details are not available right now.').count()) === 1);
  await s.browser.close();
}

// 5. responsive: tablet icon rail, phone bottom nav top 4 + More, 375 / 390 no sideways scroll
{
  const t = await open(OWNER, base(MODULES), '/overview', { width: 900, height: 800 });
  const w = await side(t.page).evaluate((n) => n.getBoundingClientRect().width);
  check('5a tablet: icon rail (<= 96px) with sixteen icons', w <= 96 && (await side(t.page).locator('a').count()) === 16, `${w}px`);
  check('5b tablet: section headings collapse to dividers (no readable text)', (await side(t.page).locator('[class*=sectionLabel]').evaluateAll((els) => els.every((e) => e.getBoundingClientRect().height <= 2))));
  await t.browser.close();
  for (const width of [375, 390]) {
    const p = await open(OWNER, base(MODULES), '/overview', { width, height: 800 });
    const bottom = p.page.locator('nav[data-bottom-nav]');
    const n = await bottom.locator('a').count();
    check(`5c ${width}: bottom nav shows top 4 + More`, (await bottom.isVisible()) && n === 4 && (await bottom.getByRole('button', { name: 'More' }).count()) === 1, `${n} links`);
    check(`5d ${width}: sidebar hidden`, !(await side(p.page).isVisible()));
    await bottom.getByRole('button', { name: 'More' }).click();
    const more = await p.page.getByRole('menu').getByRole('menuitem').allTextContents();
    check(`5e ${width}: More lists the other twelve areas`, [ 'Reports', 'HR & Payroll', 'Plan & Modules', 'Payouts', 'Compliance & Audit', 'Clinic Setup'].every((l) => more.some((m) => m.includes(l))), more.join('|'));
    check(`5f ${width}: no horizontal scroll`, await p.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await p.browser.close();
  }
}

// 6. chain owner with 3 facilities: All facilities (read-only) and each facility; a single-facility owner is not offered it
{
  const brands = (n) => ({ brands: [{ id: 'b1', name: 'Nabd Care', status: 'active', clinics: [
    { id: '22222222-2222-2222-2222-222222222222', name: 'QA Clinic', slug: 'qa', region: 'IN', status: 'active' },
    { id: '33333333-3333-3333-3333-333333333333', name: 'QA Clinic North', slug: 'qa-n', region: 'IN', status: 'active' },
    { id: '44444444-4444-4444-4444-444444444444', name: 'QA Clinic South', slug: 'qa-s', region: 'IN', status: 'active' }].slice(0, n) }] });
  for (const n of [3, 1]) {
    const s = await launch();
    await s.page.addInitScript((t) => { localStorage.setItem('nabd_access_token', t); localStorage.setItem('nabd_refresh_token', 'r1'); }, clinicToken(OWNER, { owner: true }));
    await s.page.addInitScript((t) => { sessionStorage.setItem('nabd_owner_pending_token', t); }, jwt({ sub: 'owner1', typ: 'owner_pending' }));
    await mockApi(s.page, s.log, [['GET', /^\/owners\/me\/workspaces/, { body: brands(n) }], ...base(MODULES)]);
    await s.page.goto(`${APP}/overview`, { waitUntil: 'networkidle' });
    await s.page.getByRole('button', { name: /^Clinic:/ }).click();
    const all = s.page.getByRole('menuitemradio', { name: /All facilities/ });
    if (n === 1) { check('6a single facility: no "All facilities" option', (await all.count()) === 0); await s.browser.close(); continue; }
    const items = await s.page.getByRole('menuitem').allTextContents();
    check('6b chain owner: All facilities plus each of the three facilities', (await all.count()) === 1 && items.some((t) => /North/.test(t)) && items.some((t) => /South/.test(t)) && items.some((t) => /Current/.test(t)), items.join('|'));
    await all.click();
    await s.page.getByRole('heading', { name: 'All facilities' }).waitFor({ timeout: 5000 });
    check('6c Overview becomes the read-only aggregate: note, facility list, no write control', (await s.page.getByText(/Read-only view across your facilities/).count()) === 1 && (await s.page.getByRole('list', { name: 'Facilities' }).getByRole('listitem').count()) === 3 && (await s.page.locator('main button').count()) === 1);
    check('6d switcher button says All facilities', /All facilities/.test((await s.page.locator('nav [aria-label^="Clinic:"]').first().getAttribute('aria-label')) ?? ''));
    await s.page.reload({ waitUntil: 'networkidle' });
    check('6e the scope survives a reload', (await s.page.getByRole('heading', { name: 'All facilities' }).count()) === 1);
    await s.page.getByRole('button', { name: 'Back to this facility' }).click();
    await sleep(300);
    check('6f back to this facility restores the Overview', (await s.page.getByRole('heading', { name: 'All facilities' }).count()) === 0);
    check('6g zero console errors', calm(s)); dumpLog('chain', s.log);
    await s.browser.close();
  }
}

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
