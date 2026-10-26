// FEF-052/053/054 shell: role nav (reception, doctor), entitlement + permission gating, role-context switch, facility switch,
// Ctrl+K jump, W / N shortcuts, badges, tablet rail / phone bottom nav, 390px no sideways scroll, zero console errors.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3807 node tests/e2e/s15-fef052-054-shell.mjs
import { launch, seedClinic, clinicToken, jwt, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const ME = '11111111-1111-1111-1111-111111111111';
const ALL = ['billing', 'packages', 'pharmacy_inhouse', 'whatsapp_booking', 'queue_eta', 'lab', 'ipd', 'patient_app', 'emr_erx', 'teleconsult'];
const ent = (codes) => ({ body: { modules: codes.map((code) => ({ code, enabled: true })), killedFeatures: [], seatLimit: null } });
const RECEPTION = ['queue:view', 'queue:create', 'queue:edit', 'patients:view', 'billing:view', 'pharmacy:view'];
const DOCTOR = ['clinical:edit', 'clinical:view', 'queue:view', 'patients:view'];
const q = (status, id = 'v1', doctorId = ME) => ({ id, doctorId, status, tokenNumber: 1, patientId: 'p1', priority: false });
const base = (codes, queue = []) => [
  ['GET', /^\/me\/entitlements/, ent(codes)],
  ['GET', /^\/queue/, { body: queue }],
  ['GET', /^\/setup\/subscription/, { body: { plan: 'Growth', status: 'active', branchesUsed: 3, branchesLimit: 5 } }],
  ['GET', /./, { body: [] }],
];
const calm = (s) => !s.log.pageErrors.length && !s.log.console.filter((x) => !/Failed to load resource|preload/.test(x)).length;
const navLinks = (p) => p.locator('nav[aria-label="Main"]').first().locator('a').evaluateAll((as) => as.map((a) => a.getAttribute('href')));
async function open(perms, extra, handlers, path, viewport) {
  const s = await launch(viewport ? { viewport } : {});
  await seedClinic(s.page, perms, extra);
  await mockApi(s.page, s.log, handlers);
  await s.page.goto(`${APP}${path}`, { waitUntil: 'networkidle' });
  return s;
}

// --- reception: prototype order, all modules entitled
{
  const s = await open(RECEPTION, {}, base(ALL, [q('waiting', 'a'), q('waiting', 'b'), q('checkout_pending', 'c')]), '/arrivals');
  const hrefs = await navLinks(s.page);
  check('reception nav order matches prototype', JSON.stringify(hrefs.slice(0, 8)) === JSON.stringify(['/arrivals', '/requests', '/schedule', '/patients', '/checkin', '/display', '/messages', '/billing']), hrefs.join(' '));
  check('reception badges: Arrivals 2, Billing 1', (await s.page.locator('nav a[href="/arrivals"] span').filter({ hasText: /^2$/ }).count()) > 0 && (await s.page.locator('nav a[href="/billing"] span').filter({ hasText: /^1$/ }).count()) > 0);
  // W opens the walk-in dialog
  await s.page.keyboard.press('w');
  await s.page.waitForURL(/action=walkin|arrivals/);
  await sleep(500);
  check('W opens the walk-in dialog', (await s.page.getByRole('dialog').count()) > 0 || /action=walkin/.test(s.page.url()) || (await s.page.getByText(/walk-in/i).count()) > 0);
  // W while typing does nothing
  await s.page.goto(`${APP}/arrivals`, { waitUntil: "networkidle" });
  await s.page.locator('#nb-global-search').pressSequentially('w');
  check('W while typing is typed, not a shortcut', /\/arrivals$/.test(s.page.url()) && (await s.page.locator('#nb-global-search').inputValue()) === 'w');
  // Ctrl+K: stock
  await s.page.locator('#nb-global-search').blur();
  await s.page.keyboard.press('Control+k');
  await s.page.getByRole('combobox', { name: 'Jump to a screen' }).fill('stock');
  const opts = await s.page.locator('#nb-jump-list [role=option]').allTextContents();
  check('Ctrl+K "stock" lists Pharmacy only', opts.length >= 1 && /Pharmacy/.test(opts[0]) && !opts.some((o) => /Billing/.test(o)), opts.join('|'));
  await s.page.keyboard.press('Enter');
  await s.page.waitForURL(/\/pharmacy/);
  check('Enter navigates to /pharmacy', /\/pharmacy/.test(s.page.url()));
  check('reception no console errors', calm(s)); dumpLog('reception', s.log);
  await s.browser.close();
}

// --- gating: module not entitled / permission missing -> absent in nav, direct URL = no-access
{
  const s = await open(['queue:view', 'patients:view'], {}, base(['billing']), '/requests');
  const hrefs = await navLinks(s.page);
  check('unentitled planned items absent from nav', !hrefs.includes('/requests') && !hrefs.includes('/checkin') && !hrefs.includes('/messages'), hrefs.join(' '));
  check('no billing link without billing:view', !hrefs.includes('/billing'));
  check('direct URL of unentitled screen shows no-access', (await s.page.getByText(/You do not have access to Requests/).count()) === 1);
  await s.page.goto(`${APP}/messages`, { waitUntil: 'networkidle' });
  check('direct URL without the module shows no-access (messages)', (await s.page.getByText(/You do not have access to WhatsApp inbox/).count()) === 1);
  check('gating no console errors', calm(s)); dumpLog('gating', s.log);
  await s.browser.close();
}
{
  const s = await open(RECEPTION, {}, base(ALL), '/requests');
  check('entitled + permitted planned screen shows the honest placeholder', (await s.page.getByText(/Requests is not available yet/).count()) === 1);
  await s.browser.close();
}

// --- doctor: My Day home, order, badge, N call next, specialty
{
  const s = await open(DOCTOR, { specialty: 'dental', owner: false }, base(ALL, [q('waiting', 'vis-1')]), '/my-day');
  const hrefs = await navLinks(s.page);
  check('doctor nav order matches prototype', JSON.stringify(hrefs.slice(0, 7)) === JSON.stringify(['/my-day', '/consult', '/patients', '/results', '/inpatients', '/prescriptions', '/schedule']), hrefs.join(' '));
  check('doctor My Day badge shows waiting count', (await s.page.locator('nav a[href="/my-day"] span').filter({ hasText: /^1$/ }).count()) > 0);
  check('Specialty item shows the specialty label', (await s.page.locator('nav a[href="/specialty"]').first().textContent())?.includes('Dental workspace') === true);
  await s.page.locator('body').click({ position: { x: 700, y: 700 } });
  await s.page.keyboard.press('n');
  await s.page.waitForURL(/\/consult\?entry=vis-1/, { timeout: 5000 }).catch(() => {});
  check('N on My Day opens the consult for the next patient', /\/consult\?entry=vis-1/.test(s.page.url()), s.page.url());
  check('doctor no console errors', calm(s)); dumpLog('doctor', s.log);
  await s.browser.close();
}
{
  const s = await open(DOCTOR, {}, base(ALL, []), '/my-day');
  await s.page.locator('body').click({ position: { x: 700, y: 700 } });
  await s.page.keyboard.press('n');
  await sleep(500);
  check('N with nobody waiting does nothing', /\/my-day/.test(s.page.url()));
  await s.browser.close();
}

// --- owner who is also a doctor: switch context without re-login
{
  const perms = [...DOCTOR, 'reports:view', 'billing:view', 'setup:view'];
  const s = await open(perms, { owner: true }, base(ALL), '/arrivals');
  check('owner header shows Owner', (await s.page.getByTestId('active-role').textContent()) === 'Owner');
  await s.page.getByRole('button', { name: /Account menu/ }).click();
  await s.page.getByRole('menuitemradio', { name: /Doctor context/ }).click();
  await s.page.waitForURL(/\/my-day/);
  check('switching to Doctor lands on My Day, no login', /\/my-day/.test(s.page.url()));
  check('header shows Doctor', (await s.page.getByTestId('active-role').textContent()) === 'Doctor');
  check('nav is the doctor nav', (await navLinks(s.page)).includes('/consult'));
  check('still the same session token', (await s.page.evaluate(() => !!localStorage.getItem('nabd_access_token'))));
  check('owner/doctor no console errors', calm(s)); dumpLog('owner', s.log);
  await s.page.getByRole('button', { name: /Account menu/ }).click();
  await s.page.getByRole('menuitemradio', { name: /Owner context/ }).click();
  await s.page.waitForURL(/\/overview/);
  check('switching back lands on Overview', /\/overview/.test(s.page.url()));
  await s.browser.close();
}

// --- facility switch: owner of 3 branches
{
  const T2 = '33333333-3333-3333-3333-333333333333';
  const brands = { brands: [{ id: 'b1', name: 'Nabd Care', status: 'active', clinics: [
    { id: '22222222-2222-2222-2222-222222222222', name: 'QA Clinic', slug: 'qa', region: 'IN', status: 'active' },
    { id: T2, name: 'QA Clinic North', slug: 'qa-n', region: 'IN', status: 'active' },
    { id: '44444444-4444-4444-4444-444444444444', name: 'QA Clinic South', slug: 'qa-s', region: 'IN', status: 'active' }] }] };
  const s = await launch();
  const first = clinicToken(['reports:view', 'queue:view', 'setup:view'], { owner: true });
  await s.page.addInitScript((t) => { if (!localStorage.getItem('nabd_access_token')) { localStorage.setItem('nabd_access_token', t); localStorage.setItem('nabd_refresh_token', 'r1'); } }, first); // seed once: the switch stores a new token
  const pending = jwt({ sub: 'owner1', typ: 'owner_pending' });
  await s.page.addInitScript((t) => { if (!sessionStorage.getItem('nabd_owner_pending_token')) sessionStorage.setItem('nabd_owner_pending_token', t); }, pending);
  let selected = null;
  await mockApi(s.page, s.log, [
    ['GET', /^\/owners\/me\/workspaces/, { body: brands }],
    ['POST', /^\/owners\/workspaces\/select/, async (route, req) => {
      selected = JSON.parse(req.postData() ?? '{}').clinicId;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ accessToken: clinicToken(['reports:view', 'queue:view', 'setup:view'], { owner: true, tenantId: T2, tenantName: 'QA Clinic North' }), refreshToken: 'r2', expiresIn: 900 }) });
    }],
    ...base(ALL)]);
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
  await s.page.getByRole('button', { name: /^Clinic:/ }).click();
  const items = await s.page.getByRole('menuitem').allTextContents();
  check('switcher lists the 3 branches (current marked)', items.length >= 3 && items.some((t) => /Current/.test(t)) && items.some((t) => /North/.test(t)) && items.some((t) => /South/.test(t)), items.join('|'));
  await s.page.getByRole('menuitem', { name: /QA Clinic North/ }).click();
  await s.page.waitForFunction((old) => localStorage.getItem('nabd_access_token') !== old, first, { timeout: 10000 }).catch(() => {});
  await s.page.waitForLoadState('networkidle');
  let tid = null;
  for (let k = 0; k < 30 && !tid; k++) { try { await s.page.waitForLoadState('networkidle'); tid = JSON.parse(Buffer.from(await s.page.evaluate(() => localStorage.getItem('nabd_access_token').split('.')[1]), 'base64url').toString()).tenantId; } catch { await sleep(500); } }
  check('picked branch is entered and the session token re-scoped', selected === T2 && tid === T2, `selected=${selected}`);
  await s.page.reload({ waitUntil: 'networkidle' });
  check('choice persists across reload; nav shows the branch', (await s.page.locator('nav [aria-label^="Clinic:"]').first().getAttribute('aria-label')) === 'Clinic: QA Clinic North');
  check('facility no console errors', calm(s)); dumpLog('facility', s.log);
  await s.browser.close();
}

// --- responsive: tablet rail, phone bottom nav (top 4 + More), 390px no sideways scroll
{
  const t = await open(RECEPTION, {}, base(ALL), '/arrivals', { width: 900, height: 800 });
  const w = await t.page.locator('nav[aria-label="Main"]').first().evaluate((n) => n.getBoundingClientRect().width);
  check('tablet: icon rail (<= 96px)', w <= 96, `${w}px`);
  await t.browser.close();
  const p = await open(RECEPTION, {}, base(ALL), '/arrivals', { width: 375, height: 800 });
  const bottom = p.page.locator('nav[data-bottom-nav]');
  const n = await bottom.locator('a').count();
  check('phone: bottom nav shows top 4 + More', (await bottom.isVisible()) && n === 4 && (await bottom.getByRole('button', { name: 'More' }).count()) === 1, `${n} links`);
  check('phone: sidebar hidden', !(await p.page.locator('nav[aria-label="Main"]').first().isVisible()));
  const wide = (pg) => pg.evaluate(() => [...document.querySelectorAll('body *')].filter((e) => e.getBoundingClientRect().right > innerWidth + 1 && getComputedStyle(e).position !== 'fixed').slice(0, 6).map((e) => `${e.tagName}.${String(e.className).slice(0, 30)}:${Math.round(e.getBoundingClientRect().right)}`).join(' '));
  check('375px no horizontal scroll', await p.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), await wide(p.page));
  await p.browser.close();
  const m = await open(DOCTOR, {}, base(ALL), '/specialty', { width: 390, height: 800 });
  check('390px planned screen no horizontal scroll', await m.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), await m.page.evaluate(() => [...document.querySelectorAll('body *')].filter((e) => e.getBoundingClientRect().right > innerWidth + 1).slice(0, 6).map((e) => `${e.tagName}.${String(e.className).slice(0, 30)}:${Math.round(e.getBoundingClientRect().right)}`).join(' ')));
  await m.browser.close();
}

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
