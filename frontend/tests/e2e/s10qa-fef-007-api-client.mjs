// S10 independent QA for FEF-007 (shared API client) through the real screens. Mocked API.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3412 node tests/e2e/s10qa-fef-007-api-client.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, clinicToken, APP } from './qa-lib.mjs';

const Q = '33333333-3333-4333-8333-333333333333';
const UUID7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const J = (r, status, body) => r.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload/.test(x))];
const hscroll = (page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);
const PERMS = ['billing:view', 'billing:create', 'billing:edit', 'queue:view', 'queue:edit'];
const CH = [{ id: 'c1', code: 'CONS', name: 'Consultation', category: 'Consultation', baseAmount: 500, followUpAmount: null, taxRatePercent: 0, stockQty: null }];
const ctx = { queueEntryId: Q, patientName: 'Asha Verma', doctorName: 'Dr Rao', visitType: 'Walk-in', followUpEligible: false, currency: 'INR', charges: CH, pendingProcedures: [], prescribedItems: [], departmentId: 'dept-1', queueStatus: 'checkout_pending' };
const invoice = (payments = []) => { const paid = payments.reduce((a, p) => a + p.amount, 0); return { id: 'inv-1', invoiceNumber: 'INV-0001', patientName: 'Asha Verma', currency: 'INR', subtotal: 500, discount: 0, tax: 0, roundOff: 0, total: 500, paid, balanceDue: 500 - paid, status: paid >= 500 ? 'paid' : 'partial', lineItems: [{ chargeCode: 'CONS', chargeName: 'Consultation', category: 'Consultation', quantity: 1, unitPrice: 500, taxRatePercent: 0, lineTotal: 500 }], payments }; };

async function run(tag, perms, url, handlers, fn, o = {}) {
  const s = await launch({ viewport: o.viewport });
  try {
    if (o.token) await s.page.addInitScript((t) => { if (!sessionStorage.getItem('__seeded')) { sessionStorage.setItem('__seeded', '1'); localStorage.setItem('nabd_access_token', t); localStorage.setItem('nabd_refresh_token', 'r1'); } }, o.token);
    else await seedClinic(s.page, perms, { tenantRegion: 'IN' });
    await mockApi(s.page, s.log, handlers);
    await s.page.goto(APP + url);
    await fn(s);
    if (!o.allowNoise) check(`${tag}: zero console/page errors`, noise(s.log).length === 0, JSON.stringify(noise(s.log)));
  } catch (e) {
    check(`${tag}: scenario ran to the end`, false, String(e).split('\n')[0]);
  } finally { dumpLog(tag, s.log); await s.browser.close(); }
}

// ---- AC2: checkout bill + payment: headers, retry on 503 with the same key, new action -> new key ----------------
{
  const st = { reqs: [], bill: [], pay: [], pays: [], seenBill: false };
  const h = [
    ['GET', /^\/billing\/checkout\/[^/]+$/, { body: ctx }],
    ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
    ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, (r) => (st.seenBill ? J(r, 200, invoice(st.pays)) : J(r, 404, { title: 'Not found', detail: 'x' }))],
    ['POST', /^\/billing\/checkout\/[^/]+$/, (r, req) => { st.seenBill = true; st.bill.push(req.headers()); return J(r, 200, invoice()); }],
    ['POST', /^\/billing\/invoices\/inv-1\/payments$/, (r, req) => {
      const b = JSON.parse(req.postData());
      st.pay.push({ key: req.headers()['idempotency-key'], amount: b.amount });
      if (st.pay.length === 1) return J(r, 503, { title: 'Unavailable', detail: 'busy' });
      st.pays.push({ id: `p${st.pays.length}`, method: b.method, amount: b.amount, recordedAt: 'x' });
      return J(r, 201, invoice(st.pays));
    }],
  ];
  await run('checkout', PERMS, `/checkout/${Q}`, h, async ({ page, log }) => {
    await page.getByText('Asha Verma').waitFor();
    await page.getByRole('button', { name: /Consultation.*500/ }).click();
    await page.getByLabel('Amount 1').fill('200');
    await page.getByRole('button', { name: /^Collect/ }).click();
    await page.getByText('INV-0001').waitFor();
    for (let i = 0; i < 60 && st.pays.length < 1; i++) await sleep(100);
    const b0 = st.bill[0] ?? {};
    check('AC2: bill POST carries a UUIDv7 Idempotency-Key', UUID7.test(b0['idempotency-key'] ?? ''), JSON.stringify(b0['idempotency-key']));
    check('headers: X-Request-Id (UUID), Accept-Language and bearer are on the request', /^[0-9a-f-]{36}$/.test(b0['x-request-id'] ?? '') && !!b0['accept-language'] && /^Bearer /.test(b0['authorization'] ?? ''));
    check('AC2: the 503 on the payment was retried automatically with the SAME key', st.pay.length === 2 && st.pay[0].key === st.pay[1].key && UUID7.test(st.pay[0].key), JSON.stringify(st.pay));
    check('AC2: the retried payment recorded once', st.pays.length === 1);
    await page.getByLabel('Amount 1').fill('300');
    await page.getByRole('button', { name: /^Collect/ }).click();
    for (let i = 0; i < 40 && st.pay.length < 3; i++) await sleep(100);
    check('AC2: the next user action gets a different key', st.pay.length === 3 && st.pay[2].key !== st.pay[0].key, JSON.stringify(st.pay));
    check('layout: no horizontal scroll at 1280', await hscroll(page));
  });
}

// ---- AC5: a hung bill POST ends in a visible message after ~15 s and is not auto-retried ----------------------------
{
  const st = { bill: 0 };
  const h = [
    ['GET', /^\/billing\/checkout\/[^/]+$/, { body: ctx }],
    ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
    ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, (r) => J(r, 404, { title: 'Not found', detail: 'x' })],
    ['POST', /^\/billing\/checkout\/[^/]+$/, () => { st.bill++; return new Promise(() => {}); }],
  ];
  await run('timeout', PERMS, `/checkout/${Q}`, h, async ({ page }) => {
    await page.getByText('Asha Verma').waitFor();
    await page.getByRole('button', { name: /Consultation.*500/ }).click();
    const t0 = Date.now();
    await page.getByRole('button', { name: /^Checkout$|^Collect/ }).last().click();
    const msg = page.locator('[class*="errorState"]').first();
    await msg.waitFor({ timeout: 22000 });
    const dt = Date.now() - t0;
    check('AC5: a request over 15 s ends in a visible error (not an endless spinner)', dt >= 14000 && dt <= 21000, `${dt} ms: ${(await msg.innerText()).slice(0, 120)}`);
    check('AC5: the message tells the user to check status / reload rather than blindly retry', /status|reload|check|confirm/i.test(await msg.innerText()), await msg.innerText());
    await sleep(1500);
    check('AC5: a timeout is not retried automatically (one POST)', st.bill === 1, `POSTs=${st.bill}`);
    check('AC5: the button is usable again after the timeout', await page.getByRole('button', { name: /^Checkout$|^Collect/ }).last().isEnabled());
  });
}

// ---- AC4: walk-in form: Problem errors[] -> aria-invalid on the matching input -------------------------------------
{
  const roster = [['GET', /^\/queue(\?.*)?$/, { body: [] }], ['GET', /^\/staff\/roster/, { body: [{ id: 'd1', name: 'Dr Who', departmentName: 'GP' }] }],
    ['GET', /^\/patients\?q=/, { body: { data: [] } }], ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5 } }],
    ['GET', /^\/doctors\/.*\/delay/, { body: [] }], ['GET', /^\/appointments\/callback-list/, { body: [] }]];
  const h = [['POST', /^\/patients$/, (r) => J(r, 400, { title: 'Validation failed', status: 400, detail: 'name: required; phone: invalid', errors: [{ field: 'name', message: 'Name is required' }, { field: 'phone', message: 'Enter a valid phone number' }] })], ...roster];
  await run('walk-in field errors', ['queue:view', 'queue:create', 'patients:view', 'patients:create'], '/arrivals', h, async ({ page }) => {
    await sleep(1200);
    await page.getByRole('button', { name: /Walk-in/ }).first().click();
    await page.getByLabel('Patient', { exact: true }).fill('Zed Nobody');
    await page.getByRole('button', { name: /No match/ }).click();
    await page.locator('#newName').fill('Zed Nobody');
    await page.locator('#newPhone').fill('abc');
    await page.locator('#newDob').fill('1990-01-01');
    await page.getByLabel(/Doctor/i).first().selectOption('d1');
    await page.getByRole('button', { name: 'Check in', exact: true }).last().click();
    await page.locator('#newPhone[aria-invalid="true"]').waitFor({ timeout: 4000 });
    check('AC4: phone error -> aria-invalid=true on #newPhone', true);
    check('AC4: name error -> aria-invalid=true on #newName', (await page.locator('#newName[aria-invalid="true"]').count()) === 1);
    check('AC4: a field without an error is not marked (dob)', (await page.locator('#newDob[aria-invalid="true"]').count()) === 0);
    const desc = await page.locator('#newPhone').getAttribute('aria-describedby');
    check('AC4: the message text is reachable via aria-describedby', !!desc && /valid phone/i.test(await page.locator(`#${desc}`).innerText()), String(desc));
    check('layout: no horizontal scroll with the drawer open', await hscroll(page));
  });
}

// ---- AC1: single-flight refresh in the browser (expired token, three parallel GETs) --------------------------------------
{
  const fresh = clinicToken(PERMS, { tenantRegion: 'IN' });
  const expired = clinicToken(PERMS, { tenantRegion: 'IN', exp: Math.floor(Date.now() / 1000) - 60 });
  const st = { refresh: 0, withOld: 0, withNew: 0 };
  const authed = (req) => { const a = req.headers()['authorization'] ?? ''; if (a === `Bearer ${expired}`) st.withOld++; else if (a === `Bearer ${fresh}`) st.withNew++; return a === `Bearer ${fresh}`; };
  const guard = (body) => (r, req) => (authed(req) ? J(r, 200, body) : J(r, 401, { title: 'Unauthorized', detail: 'expired' }));
  const h = [
    ['POST', /^\/auth\/refresh$/, async (r) => { st.refresh++; await sleep(150); return J(r, 200, { accessToken: fresh, refreshToken: 'r2' }); }],
    ['GET', /^\/billing\/checkout\/[^/]+$/, guard(ctx)],
    ['GET', /^\/departments\/[^/]+\/flow/, guard([])],
    ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, (r, req) => (authed(req) ? J(r, 404, { title: 'Not found', detail: 'x' }) : J(r, 401, { title: 'Unauthorized' }))],
  ];
  await run('refresh', PERMS, `/checkout/${Q}`, h, async ({ page }) => {
    await page.getByText('Asha Verma').waitFor({ timeout: 6000 });
    check('AC1: an expired token is refreshed exactly once however many requests wait', st.refresh === 1, `refresh calls=${st.refresh}`);
    check('AC1: the page then loads with the new token', st.withNew >= 2, `withNew=${st.withNew} withOld=${st.withOld}`);
    const stored = await page.evaluate(() => localStorage.getItem('nabd_refresh_token'));
    check('AC1: the rotated refresh token is stored', stored === 'r2', String(stored));
  }, { token: expired, allowNoise: true });
}
{
  const expired = clinicToken(PERMS, { tenantRegion: 'IN', exp: Math.floor(Date.now() / 1000) - 60 });
  const h = [['POST', /^\/auth\/refresh$/, (r) => J(r, 401, { title: 'Unauthorized', detail: 'refresh revoked' })], ['*', /.*/, (r) => J(r, 401, { title: 'Unauthorized' })]];
  await run('auth failure', PERMS, `/checkout/${Q}`, h, async ({ page }) => {
    await page.waitForURL(/\/login/, { timeout: 6000 });
    check('AC1: refresh refused -> onAuthFailure sends the user to /login', /\/login/.test(page.url()), page.url());
    check('AC1: tokens are cleared after the failed refresh', (await page.evaluate(() => localStorage.getItem('nabd_access_token'))) === null);
  }, { token: expired, allowNoise: true });
}

// ---- permission-absent / error / empty states of the migrated checkout ---------------------------------------------------------
await run('403', PERMS, `/checkout/${Q}`, [['GET', /^\/billing\/checkout\/[^/]+$/, (r) => J(r, 403, { title: 'Forbidden', detail: 'no' })]], async ({ page }) => {
  check('states: 403 shows the no-access state', await page.getByText(/doesn.t have access/).first().waitFor({ timeout: 4000 }).then(() => true, () => false));
});
await run('500', PERMS, `/checkout/${Q}`, [['GET', /^\/billing\/checkout\/[^/]+$/, (r) => J(r, 500, { title: 'Error' })]], async ({ page }) => {
  check('states: a 500 on load shows the error state', await page.getByText(/Couldn.t load checkout/).first().waitFor({ timeout: 4000 }).then(() => true, () => false));
});
await run('view-only', ['billing:view'], `/checkout/${Q}`, [
  ['GET', /^\/billing\/checkout\/[^/]+$/, { body: ctx }], ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
  ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, (r) => J(r, 200, invoice([]))]], async ({ page }) => {
  await page.getByText('INV-0001').waitFor();
  check('permissions: without billing:edit the Collect/payment controls are absent (not disabled)', (await page.getByLabel('Amount 1').count()) === 0 && (await page.getByRole('button', { name: /^Collect/ }).count()) === 0);
  check('permissions: without billing:create there is no "Add more charges"', (await page.getByRole('button', { name: /Add more charges/ }).count()) === 0);
});
await run('390px', PERMS, `/checkout/${Q}`, [
  ['GET', /^\/billing\/checkout\/[^/]+$/, { body: ctx }], ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
  ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, (r) => J(r, 404, { title: 'Not found', detail: 'x' })]], async ({ page }) => {
  await page.getByText('Asha Verma').waitFor();
  check('layout: checkout has no horizontal scroll at 390px', await hscroll(page));
}, { viewport: { width: 390, height: 800 } });

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed; ${failed.length} failed`);
process.exit(failed.length ? 1 : 0);
