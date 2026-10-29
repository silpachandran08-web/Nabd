// FEF-033: registration, check-in, booking and checkout forms: error on blur (aria-invalid + aria-describedby), submit focuses the first
// invalid field, phone stored as E.164 per market, age -> estimated DOB, beforeunload guard, server field errors on the same fields.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3812 node tests/e2e/s16-fef033-forms.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const DOC1 = '33333333-3333-3333-3333-333333333331', DOC2 = '33333333-3333-3333-3333-333333333332';
const RECEPTION = ['queue:view', 'queue:edit', 'queue:create', 'patients:view', 'patients:create'];
const json = (route, body, status = 200) => route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const problem = (errors) => ({ type: 'https://api.nabd.health/errors/validation', title: 'Bad request', status: 400, detail: 'Validation failed', errors });
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];

function deskHandlers(st, { post400 = null, estimates = true } = {}) {
  return [
    ['GET', /^\/queue\/wait-estimate/, estimates ? { body: { estimatedMinutes: 12 } } : { status: 404, body: {} }],
    ['GET', /^\/queue(\?|$)/, { body: [] }],
    ['POST', /^\/queue\/check-in$/, (route, req) => { st.checkIns.push(JSON.parse(req.postData())); json(route, { id: 'q1', tokenNumber: 7, status: 'checked_in' }, 201); }],
    ['POST', /^\/patients$/, (route, req) => { st.patients.push(JSON.parse(req.postData())); post400 ? json(route, problem(post400), 400) : json(route, { id: 'np1' }, 201); }],
    ['GET', /^\/staff\/roster/, { body: [{ id: DOC1, name: 'Dr Amal Nasser', departmentName: 'General' }, { id: DOC2, name: 'Dr Nour Aziz', departmentName: 'General' }] }],
    ['GET', /^\/doctors\/[^/]+\/delay/, { body: [] }],
    ['GET', /^\/appointments\/callback-list/, { body: [] }],
    ['GET', /^\/patients\?/, { body: { data: [{ id: 'p9', name: 'Layla Haddad', phone: '+919800000009', mrn: 'MRN-p9' }], page: { nextCursor: null, limit: 20 } } }],
    ['GET', /./, { body: [] }],
  ];
}
async function desk({ region = 'IN', viewport, handlers } = {}) {
  const s = await launch(viewport ? { viewport } : {});
  s.st = { checkIns: [], patients: [] };
  await seedClinic(s.page, RECEPTION, { tenantRegion: region });
  await mockApi(s.page, s.log, deskHandlers(s.st, handlers));
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' }); await sleep(600);
  return s;
}
const dlg = (page) => page.getByRole('dialog', { name: 'Register walk-in' });
const focusedId = (page) => page.evaluate(() => document.activeElement?.id ?? document.activeElement?.tagName);
async function errorOf(page, input) {
  const el = page.locator(input);
  const invalid = (await el.getAttribute('aria-invalid')) === 'true';
  const id = await el.getAttribute('aria-describedby');
  const text = id ? await page.locator('#' + id.split(' ')[0]).innerText().catch(() => '') : '';
  return { invalid, text };
}
const run = async (name, fn) => { try { await fn(); } catch (e) { check(name, false, String(e).split('\n')[0]); } };

await run('registration (India)', async () => {
  const s = await desk();
  const p = s.page;
  await p.keyboard.press('F2'); await dlg(p).waitFor();
  await p.locator('#newName').focus(); await p.keyboard.press('Tab');
  let e = await errorOf(p, '#newName');
  check('A1 blur on an empty name: aria-invalid + aria-describedby message', e.invalid && /required/i.test(e.text), JSON.stringify(e));
  await p.locator('#newPhone').fill('12345'); await p.keyboard.press('Tab');
  e = await errorOf(p, '#newPhone');
  check('A2 blur on a short phone: invalid with the phone message', e.invalid && /valid mobile number/i.test(e.text), JSON.stringify(e));
  await p.locator('#newName').fill('Layla Haddad');
  await p.keyboard.press('Control+Enter'); await sleep(300);
  check('A3 submit focuses the first invalid field (phone: name is now fine)', (await focusedId(p)) === 'newPhone', await focusedId(p));
  check('A4 nothing was sent while invalid', s.st.patients.length === 0 && s.st.checkIns.length === 0);
  await p.locator('#newPhone').fill('98450 12345');
  await p.locator('#newAge').fill('42');
  const dob = await p.locator('#newDob').inputValue();
  const year = await p.evaluate(() => new Date().getFullYear());
  check('A5 age 42 fills DOB as 1 January of the estimated year', dob === `${year - 42}-01-01`, dob);
  check('A6 the DOB is shown as estimated', await p.getByTestId('dob-estimated').isVisible());
  await p.keyboard.press('Control+Enter');
  await p.getByRole('dialog', { name: 'Checked in' }).waitFor({ timeout: 5000 });
  const body = s.st.patients[0];
  check('A7 India: "98450 12345" is stored as +919845012345', body?.phone === '+919845012345', body?.phone);
  check('A8 check-in followed with a doctor', s.st.checkIns.length === 1 && !!s.st.checkIns[0].doctorId);
  check('A9 registration: zero console errors', noise(s.log).length === 0, noise(s.log).join('|')); dumpLog('A', s.log);
  await s.browser.close();
});

await run('registration (UAE)', async () => {
  const s = await desk({ region: 'AE' });
  const p = s.page;
  await p.keyboard.press('F2'); await dlg(p).waitFor();
  await p.locator('#newName').fill('Omar Said'); await p.locator('#newPhone').fill('050 123 4567'); await p.locator('#newAge').fill('30');
  await p.keyboard.press('Control+Enter');
  await p.getByRole('dialog', { name: 'Checked in' }).waitFor({ timeout: 5000 });
  check('B1 UAE: "050 123 4567" is stored as +971501234567', s.st.patients[0]?.phone === '+971501234567', s.st.patients[0]?.phone);
  await s.browser.close();
  const t = await desk({ region: 'AE' });
  await t.page.keyboard.press('F2'); await dlg(t.page).waitFor();
  await t.page.locator('#newPhone').fill('98450 12345'); await t.page.keyboard.press('Tab');
  const e = await errorOf(t.page, '#newPhone');
  check('B2 UAE: an Indian-format number typed without +91 is refused', e.invalid, JSON.stringify(e));
  await t.browser.close();
});

await run('server field errors map to the same fields', async () => {
  const s = await desk({ handlers: { post400: [{ field: 'phone', message: 'This number belongs to another patient' }, { field: 'name', message: 'Name contains digits' }] } });
  const p = s.page;
  await p.keyboard.press('F2'); await dlg(p).waitFor();
  await p.locator('#newName').fill('Layla 7'); await p.locator('#newPhone').fill('98450 12345'); await p.locator('#newAge').fill('42');
  await p.keyboard.press('Control+Enter');
  await p.locator('#newPhone-err').waitFor({ timeout: 5000 });
  const ph = await errorOf(p, '#newPhone'), nm = await errorOf(p, '#newName');
  check('C1 server error on phone shows under the phone field', ph.invalid && /another patient/.test(ph.text), JSON.stringify(ph));
  check('C2 server error on name shows under the name field', nm.invalid && /contains digits/.test(nm.text), JSON.stringify(nm));
  await p.locator('#newPhone').fill('98450 12346');
  check('C3 editing a field clears its server error', (await p.locator('#newPhone').getAttribute('aria-invalid')) === null);
  await s.browser.close();
});

await run('check-in needs a doctor', async () => {
  const s = await desk({ handlers: { estimates: false } });
  const p = s.page;
  await p.keyboard.press('F3'); await dlg(p).waitFor();
  await p.locator('#patientSearch').fill('Layla'); await sleep(500);
  await p.keyboard.press('Enter');
  check('D1 no wait estimates and two doctors: no doctor is guessed', (await p.locator('#doctor').inputValue()) === '');
  await p.keyboard.press('Control+Enter'); await sleep(300);
  const e = await errorOf(p, '#doctor');
  check('D2 submit without a doctor: aria-invalid + message, focus on the doctor', e.invalid && /required/i.test(e.text) && (await focusedId(p)) === 'doctor', JSON.stringify(e) + (await focusedId(p)));
  await p.locator('#doctor').selectOption(DOC2);
  check('D3 choosing a doctor clears the error', (await p.locator('#doctor').getAttribute('aria-invalid')) === null);
  await s.browser.close();
});

await run('unsaved form guard', async () => {
  const s = await desk();
  const p = s.page; const seen = [];
  p.on('dialog', async (d) => { seen.push(d.type()); await d.accept(); });
  await p.keyboard.press('F2'); await dlg(p).waitFor();
  await p.locator('#newName').fill('Half typed');
  await p.close({ runBeforeUnload: true }); await sleep(300);
  check('E1 closing the tab with a dirty form asks the browser to confirm (beforeunload)', seen.includes('beforeunload'), seen.join(','));
  await s.browser.close();
  const c = await desk(); const seen2 = [];
  c.page.on('dialog', async (d) => { seen2.push(d.type()); await d.accept(); });
  await c.page.keyboard.press('F2'); await dlg(c.page).waitFor();
  await c.page.close({ runBeforeUnload: true }); await sleep(300);
  check('E2 a pristine form closes without a prompt', seen2.length === 0, seen2.join(','));
  await c.browser.close();
});

const SCHED = (day) => [
  ['GET', /^\/schedule\/day/, { body: day }], ['GET', /^\/appointments/, { body: { data: [], page: { nextCursor: null, limit: 100 } } }],
  ['GET', /^\/waitlist/, { body: [] }], ['GET', /^\/queue/, { body: [] }],
];
await run('booking', async () => {
  const D = '2026-10-27';
  const day = { date: D, today: true, timezone: 'Asia/Kolkata', holiday: null, sessions: [], counts: { appointments: 0, walkIns: 0, freeSlots: 3 },
    doctors: [{ id: DOC1, name: 'Dr Amal Nasser', department: 'General', onLeave: false, leaveReason: null, worksToday: true }] };
  const s = await launch(); const posts = [];
  await seedClinic(s.page, [...RECEPTION, 'patients:create'], { tenantRegion: 'AE' });
  await mockApi(s.page, s.log, [...SCHED(day), ['GET', /^\/doctors\/[^/]+\/availability/, { body: [`${D}T05:00:00Z`, `${D}T05:30:00Z`] }],
    ['GET', /^\/patients\?q=/, { body: { data: [], page: { nextCursor: null } } }],
    ['POST', /^\/patients$/, (r, req) => { posts.push(JSON.parse(req.postData())); json(r, { id: 'np2', name: 'Omar Said', phone: '+971501234567', mrn: 'MRN-2' }, 201); }],
    ['GET', /./, { body: [] }]]);
  await s.page.goto(`${APP}/schedule`, { waitUntil: 'networkidle' });
  await s.page.getByRole('button', { name: '+ New appointment' }).click();
  const d = s.page.getByRole('dialog', { name: 'New appointment' });
  await d.getByRole('button', { name: 'Book appointment' }).click(); await sleep(300);
  const pe = await errorOf(s.page, '#appt-patient'), te = await errorOf(s.page, '#appt-time');
  check('F1 booking with nothing chosen: patient and time are aria-invalid with messages', pe.invalid && te.invalid && pe.text.length > 0 && te.text.length > 0, JSON.stringify({ pe, te }));
  check('F2 submit focused the first invalid field (patient)', (await focusedId(s.page)) === 'appt-patient', await focusedId(s.page));
  check('F3 no booking request was sent', !s.log.requests.some((r) => r.method === 'POST' && r.path === '/appointments'));
  await d.locator('#appt-patient').fill('omar'); await sleep(700);
  await d.getByRole('button', { name: 'Register new patient' }).click();
  await d.getByLabel('Patient name').fill('Omar Said'); await d.getByLabel('Patient phone').fill('12345'); await d.getByLabel('Patient phone').blur();
  const bad = await errorOf(s.page, 'input[aria-label="Patient phone"]');
  check('F4 quick-register: a short phone is refused on blur', bad.invalid && /valid mobile/i.test(bad.text), JSON.stringify(bad));
  await d.getByLabel('Patient phone').fill('050 123 4567'); await d.getByLabel('Date of birth').fill('1990-01-01');
  await d.getByRole('button', { name: 'Save patient' }).click(); await sleep(600);
  check('F5 quick-register stores E.164 (UAE: +971501234567)', posts[0]?.phone === '+971501234567', posts[0]?.phone);
  check('F6 booking: zero console errors', noise(s.log).length === 0, noise(s.log).join('|'));
  await s.browser.close();
});

await run('checkout discount', async () => {
  const Q = '33333333-3333-4333-8333-333333333333';
  const CHARGES = [{ id: 'c1', code: 'CONS', name: 'Consultation', category: 'Consultation', baseAmount: 500, followUpAmount: null, taxRatePercent: 0, stockQty: null }];
  const ctx = { queueEntryId: Q, patientName: 'Asha Verma', doctorName: 'Dr Rao', visitType: 'Walk-in', followUpEligible: false, currency: 'INR', charges: CHARGES, pendingProcedures: [], prescribedItems: [], departmentId: 'd1', queueStatus: 'checkout_pending', suggestedLines: [] };
  const s = await launch(); const posts = []; const seen = [];
  s.page.on('dialog', async (d) => { seen.push(d.type()); await d.accept(); });
  await seedClinic(s.page, ['billing:view', 'billing:create', 'queue:view', 'queue:edit', 'billing.discount'], { tenantRegion: 'IN' });
  await mockApi(s.page, s.log, [['GET', /^\/billing\/checkout\/[^/]+$/, { body: ctx }], ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
    ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, { status: 404, body: { title: 'Not found', detail: 'x' } }],
    ['POST', /^\/billing\/checkout\/[^/]+$/, (r, req) => { posts.push(JSON.parse(req.postData())); json(r, { id: 'inv-1', invoiceNumber: 'INV-0001', patientName: 'Asha Verma', doctorName: 'Dr Rao', currency: 'INR', subtotal: 500, discount: 50, tax: 0, roundOff: 0, total: 450, paid: 0, balanceDue: 450, status: 'unpaid', payments: [], lineItems: [] }); }],
    ['GET', /./, { body: [] }]]);
  await s.page.goto(`${APP}/checkout/${Q}`);
  await s.page.getByText('Asha Verma').waitFor();
  await s.page.getByRole('button', { name: /Consultation.*500/ }).click();
  await s.page.getByLabel('Discount amount').fill('50');
  await s.page.getByRole('button', { name: 'Checkout', exact: true }).click(); await sleep(300);
  const re = await errorOf(s.page, '[aria-label="Discount reason"]');
  check('G1 discount without a reason: the reason select is aria-invalid with a message, focused', re.invalid && /reason/i.test(re.text) && (await s.page.evaluate(() => document.activeElement?.getAttribute('aria-label'))) === 'Discount reason', JSON.stringify(re));
  check('G2 nothing was billed', posts.length === 0);
  await s.page.getByLabel('Discount amount').fill('600');
  await s.page.getByLabel('Discount reason').selectOption({ index: 1 });
  await s.page.getByLabel('Discount amount').focus(); await s.page.keyboard.press('Tab'); await sleep(200);
  const ae = await errorOf(s.page, '[aria-label="Discount amount"]');
  check('G3 a discount above the subtotal is refused on blur', ae.invalid && /subtotal/i.test(ae.text), JSON.stringify(ae));
  await s.page.getByLabel('Discount amount').fill('50');
  await s.page.getByRole('button', { name: 'Checkout', exact: true }).click();
  await s.page.getByText('INV-0001').waitFor({ timeout: 5000 });
  check('G4 a valid discount goes through with its reason', posts[0]?.discounts?.[0]?.value === 50 && !!posts[0]?.discounts?.[0]?.reasonCode, JSON.stringify(posts[0]));
  check('G5 checkout: zero console errors', noise(s.log).length === 0, noise(s.log).join('|'));
  await s.browser.close();
});

await run('390px', async () => {
  const s = await desk({ viewport: { width: 390, height: 800 } });
  await s.page.keyboard.press('F2'); await dlg(s.page).waitFor();
  await s.page.locator('#newPhone').fill('12345'); await s.page.keyboard.press('Tab');
  check('H1 390px: no horizontal scroll with errors showing', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  await s.browser.close();
});

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
