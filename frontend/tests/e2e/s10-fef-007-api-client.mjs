// FEF-007: checkout, check-in and walk-in registration through the shared API client (mocked API).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3405 node tests/e2e/s10-fef-007-api-client.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const UUID7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const Q = '33333333-3333-4333-8333-333333333333';
const J = (r, status, body) => r.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const hdr = (req) => ({ key: req.headers()['idempotency-key'], rid: req.headers()['x-request-id'], lang: req.headers()['accept-language'] });
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
const hscroll = (page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);

const CHARGES = [{ id: 'c1', code: 'CONS', name: 'Consultation', category: 'Consultation', baseAmount: 500, followUpAmount: null, taxRatePercent: 0, stockQty: null }];
const ctx = { queueEntryId: Q, patientName: 'Asha Verma', doctorName: 'Dr Rao', visitType: 'Walk-in', followUpEligible: false, currency: 'INR', charges: CHARGES, pendingProcedures: [], prescribedItems: [], departmentId: 'dept-1', queueStatus: 'checkout_pending' };

async function run(tag, perms, url, handlers, fn, viewport) {
  const s = await launch({ viewport });
  try {
    await seedClinic(s.page, perms, { tenantRegion: 'IN' });
    await mockApi(s.page, s.log, handlers);
    await s.page.goto(APP + url);
    await fn(s);
    check(`${tag}: zero console/page errors`, noise(s.log).length === 0, JSON.stringify(noise(s.log)));
  } finally { dumpLog(tag, s.log); await s.browser.close(); }
}

// ---- checkout -------------------------------------------------------------------------------
function billing({ failFirstPayment = 0, payStatus = 200 } = {}) {
  const st = { bill: [], pay: [], invoice: null };
  const inv = () => { const paid = st.invoice.payments.reduce((a, p) => a + p.amount, 0); return { ...st.invoice, paid, balanceDue: 500 - paid, status: paid >= 500 ? 'paid' : 'partial' }; };
  const h = [
    ['GET', /^\/billing\/checkout\/[^/]+$/, { body: ctx }],
    ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
    ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, (r) => (st.invoice ? J(r, 200, inv()) : J(r, 404, { title: 'Not found', detail: 'x' }))],
    ['POST', /^\/billing\/checkout\/[^/]+$/, (r, req) => {
      st.bill.push(hdr(req));
      st.invoice = { id: 'inv-1', invoiceNumber: 'INV-0001', patientName: 'Asha Verma', doctorName: 'Dr Rao', currency: 'INR', subtotal: 500, discount: 0, tax: 0, roundOff: 0, total: 500, lineItems: JSON.parse(req.postData()).lineItems, payments: [] };
      return J(r, 200, inv());
    }],
    ['POST', /^\/billing\/invoices\/inv-1\/payments$/, (r, req) => {
      const b = JSON.parse(req.postData());
      st.pay.push({ ...hdr(req), amount: b.amount });
      if (st.pay.length <= failFirstPayment) return J(r, 503, { title: 'Unavailable', detail: 'busy', code: 'DB_UNAVAILABLE' });
      if (payStatus >= 400) return J(r, payStatus, { title: 'Refused', detail: 'Gateway refused the payment.' });
      st.invoice.payments.push({ id: `p${st.pay.length}`, method: b.method, amount: b.amount, recordedAt: '2026-10-19T10:00:00Z' });
      return J(r, 200, inv());
    }],
  ];
  return { st, h };
}
const PERMS = ['billing:view', 'billing:create', 'billing:edit', 'queue:view', 'queue:edit'];

{
  const b = billing({ failFirstPayment: 1 });
  await run('checkout retry', PERMS, `/checkout/${Q}`, b.h, async ({ page }) => {
    await page.getByText('Asha Verma').waitFor();
    await page.getByRole('button', { name: /Consultation.*500/ }).click();
    await page.getByLabel('Amount 1').fill('200');
    await page.getByRole('button', { name: /^Collect/ }).click();
    await page.getByText('INV-0001').waitFor();
    for (let i = 0; i < 50 && b.st.invoice.payments.length < 1; i++) await sleep(100); // the retry waits 150-450 ms
    check('checkout: bill POST carries a UUID v7 Idempotency-Key, X-Request-Id and Accept-Language', b.st.bill.length === 1 && UUID7.test(b.st.bill[0].key) && /^[0-9a-f-]{36}$/.test(b.st.bill[0].rid) && !!b.st.bill[0].lang, JSON.stringify(b.st.bill));
    check('checkout: 503 on the payment is retried automatically with the SAME Idempotency-Key', b.st.pay.length === 2 && b.st.pay[0].key === b.st.pay[1].key && UUID7.test(b.st.pay[0].key), JSON.stringify(b.st.pay));
    check('checkout: the retried payment landed once (invoice shows 200 paid)', b.st.invoice.payments.length === 1);
    await page.getByLabel('Amount 1').fill('300');
    await page.getByRole('button', { name: /^Collect|Pay|Record/ }).first().click();
    await page.waitForFunction(() => /paid/i.test(document.body.innerText));
    check('checkout: a new user action gets a different key', b.st.pay.length === 3 && b.st.pay[2].key !== b.st.pay[0].key, JSON.stringify(b.st.pay));
    check('checkout: no horizontal scroll at 1280', await hscroll(page));
  });
}
{
  const b = billing({ payStatus: 422 });
  await run('checkout refusal', PERMS, `/checkout/${Q}`, b.h, async ({ page }) => {
    await page.getByText('Asha Verma').waitFor();
    await page.getByRole('button', { name: /Consultation.*500/ }).click();
    await page.getByLabel('Amount 1').fill('500');
    await page.getByRole('button', { name: /^Collect/ }).click();
    await page.getByText('Gateway refused the payment.').waitFor();
    check('checkout: 422 Problem detail shown, no automatic retry', b.st.pay.length === 1);
    await page.getByRole('button', { name: /^Collect|Pay|Record/ }).first().click();
    await sleep(500);
    check('checkout: user retry of the same payment reuses the same key', b.st.pay.length === 2 && b.st.pay[0].key === b.st.pay[1].key, JSON.stringify(b.st.pay));
  }, { width: 390, height: 800 });
}
await run('checkout 390px', PERMS, `/checkout/${Q}`, billing().h, async ({ page }) => {
  await page.getByText('Asha Verma').waitFor();
  check('checkout: no horizontal scroll at 390', await hscroll(page));
}, { width: 390, height: 800 });
await run('checkout error', PERMS, `/checkout/${Q}`, [['GET', /^\/billing\/checkout\/[^/]+$/, (r) => J(r, 500, { title: 'Error' })]], async ({ page }) => {
  await page.getByText(/Couldn.t load checkout/).waitFor();
  check('checkout: load failure shows the error state', true);
});
await run('checkout 403', PERMS, `/checkout/${Q}`, [['GET', /^\/billing\/checkout\/[^/]+$/, (r) => J(r, 403, { title: 'Forbidden', detail: 'no' })]], async ({ page }) => {
  await page.getByText(/doesn.t have access/).waitFor();
  check('checkout: 403 shows the no-access state', true);
});

// ---- walk-in registration + check-in ---------------------------------------------------------
const roster = [['GET', /^\/queue(\?.*)?$/, { body: [] }], ['GET', /^\/staff\/roster/, { body: [{ id: 'd1', name: 'Dr Who', departmentName: 'GP' }] }],
  ['GET', /^\/patients\?q=/, { body: { data: [] } }], ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5 } }],
  ['GET', /^\/doctors\/.*\/delay/, { body: [] }], ['GET', /^\/appointments\/callback-list/, { body: [] }]];
const WALK = ['queue:view', 'queue:create', 'patients:view', 'patients:create'];
async function openWalkin(page) {
  await page.getByRole('button', { name: /Walk-in/ }).first().click();
  await page.getByLabel('Patient', { exact: true }).fill('Zed Nobody');
  await page.getByRole('button', { name: /No match/ }).click();
  await page.locator('#newName').fill('Zed Nobody');
  await page.locator('#newPhone').fill('abc');
  await page.locator('#newDob').fill('1990-01-01');
  await page.getByLabel(/Doctor/i).first().selectOption('d1');
}
{
  const st = { reg: [], ci: [], regN: 0, ciN: 0 };
  const h = [
    ['POST', /^\/patients$/, (r, req) => {
      st.reg.push(hdr(req)); st.regN++;
      if (st.regN === 1) return J(r, 400, { title: 'Validation failed', detail: 'phone: invalid', errors: [{ field: 'phone', message: 'Enter a valid phone number' }] });
      return J(r, 201, { id: 'p9' });
    }],
    ['POST', /^\/queue\/check-in$/, (r, req) => { st.ci.push(hdr(req)); st.ciN++; return st.ciN === 1 ? J(r, 422, { title: 'Refused', detail: 'Doctor is off today.' }) : J(r, 201, { id: 'e9', tokenNumber: 7 }); }],
    ...roster,
  ];
  await run('walk-in', WALK, '/arrivals', h, async ({ page }) => {
    await sleep(1200);
    await openWalkin(page);
    await page.getByRole('button', { name: 'Check in', exact: true }).last().click();
    await page.locator('#newPhone[aria-invalid="true"]').waitFor();
    check('walk-in: Problem errors[] maps to the matching input (phone aria-invalid=true)', true);
    check('walk-in: other inputs are not marked invalid', (await page.locator('#newName[aria-invalid="true"]').count()) === 0 && (await page.locator('#newDob[aria-invalid="true"]').count()) === 0);
    check('walk-in: field message is linked with aria-describedby', (await page.locator('#newPhone').getAttribute('aria-describedby')) === 'newPhone-err' && /valid phone/.test(await page.locator('#newPhone-err').innerText()));
    await page.locator('#newPhone').fill('+919876543210');
    check('walk-in: editing clears the invalid mark', (await page.locator('#newPhone[aria-invalid="true"]').count()) === 0);
    await page.getByRole('button', { name: 'Check in', exact: true }).last().click();
    await page.getByText('Doctor is off today.').waitFor();
    check('walk-in: patient registered once, check-in refusal detail shown', st.reg.length === 2 && st.ci.length === 1);
    await page.getByRole('button', { name: 'Check in', exact: true }).last().click();
    await page.getByText('Checked in').waitFor();
    check('walk-in: retry after refusal does not register the patient again (registeredRef kept)', st.reg.length === 2, `register POSTs=${st.reg.length}`);
    check('walk-in: check-in retry (same body) reuses its Idempotency-Key', st.ci.length === 2 && st.ci[0].key === st.ci[1].key && UUID7.test(st.ci[0].key), JSON.stringify(st.ci));
    check('walk-in: register key differs from check-in key', st.reg[1].key !== st.ci[0].key && UUID7.test(st.reg[1].key));
    check('walk-in: token slip shows #7', /7/.test(await page.getByRole('dialog').innerText()));
  });
}
{
  const h = [['POST', /^\/patients$/, (r) => J(r, 409, { title: 'Duplicate', candidates: [{ patientId: 'p1', name: 'Zed Nobody Sr', phone: '+9112', matchScore: 0.9 }] })], ...roster];
  await run('walk-in duplicate', WALK, '/arrivals', h, async ({ page }) => {
    await sleep(1200);
    await openWalkin(page);
    await page.getByRole('button', { name: 'Check in', exact: true }).last().click();
    await page.getByText('Zed Nobody Sr').waitFor();
    check('walk-in: 409 candidates from the Problem body are listed', true);
  });
}
await run('walk-in permission', ['queue:view'], '/arrivals', roster, async ({ page }) => {
  await sleep(1200);
  check('walk-in: button absent without queue:create', (await page.getByRole('button', { name: /Walk-in/ }).count()) === 0);
});
await run('walk-in 390px', WALK, '/arrivals', roster, async ({ page }) => {
  await sleep(1200);
  await openWalkin(page);
  check('walk-in: no horizontal scroll at 390 with the drawer open', await hscroll(page));
}, { width: 390, height: 800 });

// ---- CheckInDialog (appointment check-in) ----------------------------------------------------
{
  const st = { ci: [] };
  const h = [
    ['GET', /^\/appointments\?date=/, { body: { data: [{ id: 'ap1', patientId: 'p1', doctorId: 'd1', startTime: '2026-10-19T09:00:00Z', status: 'scheduled' }] } }],
    ['GET', /^\/patients\/p1$/, { body: { name: 'Mina Booked' } }],
    ['POST', /^\/queue\/check-in$/, (r, req) => { st.ci.push({ ...hdr(req), body: JSON.parse(req.postData()) }); return J(r, 201, { id: 'e1', tokenNumber: 3 }); }],
    ...roster,
  ];
  await run('check-in dialog', [...WALK, 'billing:view'], '/arrivals', h, async ({ page }) => {
    await sleep(1200);
    await page.getByRole('button', { name: 'Check in', exact: true }).first().click();
    await page.getByText('Mina Booked').waitFor();
    await page.getByRole('button', { name: 'Check in', exact: true }).last().click();
    await page.getByRole('dialog').getByRole('button', { name: 'Check in', exact: true }).click();
    await page.getByText('Checked in').waitFor();
    check('check-in: POST /queue/check-in carries appointmentId and a UUID v7 Idempotency-Key', st.ci.length === 1 && st.ci[0].body.appointmentId === 'ap1' && UUID7.test(st.ci[0].key), JSON.stringify(st.ci));
    await page.getByRole('dialog').getByText(/5 min/).waitFor(); // the slip's position/wait reads are async (skeleton first)
    check('check-in: token slip shows queue position and wait via the client', /5 min/.test(await page.getByRole('dialog').innerText()));
  });
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
