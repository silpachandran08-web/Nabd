// KNOWN-DEFECT EXPECTATION (known-defect-FEF-008): the Sprint-10 FEF-008 payment-status UX was never delivered (board: FEF-008
// blocked), so the FEF-008 checks here are EXPECTED TO FAIL until that story ships. Do not "fix" them by editing assertions.
// S10 independent QA for FEF-008 (payment status UX) + the "every money POST carries Idempotency-Key" criterion. Mocked API.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3412 node tests/e2e/s10qa-fef-008-payment-status.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

let step = '';
const Q = '33333333-3333-4333-8333-333333333333';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const J = (r, status, body) => r.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload/.test(x))];
const PERMS = ['billing:view', 'billing:create', 'billing:edit', 'queue:view', 'queue:edit'];
const ctx = { queueEntryId: Q, patientName: 'Asha Verma', doctorName: 'Dr Rao', visitType: 'Walk-in', followUpEligible: false, currency: 'INR', charges: [], pendingProcedures: [], prescribedItems: [], departmentId: 'dept-1', queueStatus: 'checkout_pending' };

/** Server model: one open invoice of 500. mode: 'ok' | 'hang-landed' | 'hang-lost' | 'drop' | 'reused'. */
function server(mode) {
  const st = { mode, posts: [], invGets: [], coGets: 0, payments: [] };
  const inv = () => { const paid = st.payments.reduce((a, p) => a + p.amount, 0); return { id: 'inv-1', invoiceNumber: 'INV-0001', patientName: 'Asha Verma', doctorName: 'Dr Rao', currency: 'INR', subtotal: 500, discount: 0, tax: 0, roundOff: 0, total: 500, paid, balanceDue: 500 - paid, status: paid >= 500 ? 'paid' : paid > 0 ? 'partial' : 'unpaid', lineItems: [{ chargeCode: 'CONS', chargeName: 'Consultation', category: 'Consultation', quantity: 1, unitPrice: 500, taxRatePercent: 0, lineTotal: 500 }], payments: st.payments }; };
  st.inv = inv;
  st.h = [
    ['GET', /^\/billing\/checkout\/[^/]+$/, { body: ctx }],
    ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
    ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, (r) => { st.coGets++; return J(r, 200, inv()); }],
    ['GET', /^\/billing\/invoices\/inv-1$/, (r) => { st.invGets.push(Date.now()); return J(r, 200, inv()); }],
    ['POST', /^\/billing\/invoices\/inv-1\/payments$/, (r, req) => {
      const b = JSON.parse(req.postData());
      const key = req.headers()['idempotency-key'];
      st.posts.push({ key, amount: b.amount, at: Date.now() });
      const dupe = st.payments.find((p) => p.idempotencyKey === key);
      const land = () => { if (!dupe) st.payments.push({ id: `p${st.payments.length + 1}`, method: b.method, amount: b.amount, recordedAt: '2026-10-19T10:00:00Z', idempotencyKey: key }); };
      if (st.mode === 'reused') return J(r, 422, { type: 'https://api.nabd.health/errors/idempotency-key-reused', title: 'Idempotency-Key reused', status: 422, code: 'IDEMPOTENCY_KEY_REUSED', detail: 'This Idempotency-Key was already used with a different request.' });
      if (st.mode === 'hang-landed' && st.posts.length === 1) { land(); return new Promise(() => {}); } // server acted, answer never arrives
      if (st.mode === 'hang-lost' && st.posts.length === 1) return new Promise(() => {});          // request never processed
      if (st.mode === 'drop' && st.posts.length === 1) { land(); return r.abort(); }
      land();
      return J(r, 201, inv());
    }],
  ];
  return st;
}

async function run(tag, mode, fn, o = {}) {
  const s = await launch({ viewport: o.viewport });
  const st = server(mode);
  try {
    await seedClinic(s.page, PERMS, { tenantRegion: 'IN' });
    await mockApi(s.page, s.log, st.h);
    await s.page.goto(APP + `/checkout/${Q}`);
    await s.page.getByText('INV-0001').waitFor();
    await fn(s, st);
    check(`${tag}: zero page errors (no unhandled rejection) and no console errors`, noise(s.log).length === 0, JSON.stringify(noise(s.log)));
  } catch (e) {
    check(`${tag}: scenario ran to the end`, false, String(e).split('\n')[0]);
  } finally { dumpLog(tag, s.log); await s.browser.close(); }
}
const collect = async (page, amt = '500') => { await page.getByLabel('Amount 1').fill(amt); await page.getByRole('button', { name: /^Collect/ }).click(); };
const seen = async (page, re, ms = 3000) => page.getByText(re).first().waitFor({ timeout: ms }).then(() => true, () => false);

// ---- AC1a: timeout, payment had landed -----------------------------------------------------------
await run('FEF008-AC1 timeout/landed', 'hang-landed', async ({ page }, st) => {
  await collect(page);
  const t0 = Date.now();
  const checking = await seen(page, /Checking payment status/, 25000);
  check('AC1: after the 15 s timeout the screen shows "Checking payment status…"', checking);
  check('AC1: the timeout fires at ~15 s (not before 14 s)', checking && Date.now() - t0 >= 14000, `${Date.now() - t0} ms`);
  const rec = await seen(page, /Payment recorded/, 8000);
  check('AC1: status check finds the payment -> "Payment recorded"', rec);
  check('AC1: the check read GET /billing/invoices/{id}', st.invGets.length >= 1, `invoice GETs=${st.invGets.length}, checkout/invoice GETs=${st.coGets}`);
  check('AC1: nothing was re-sent while checking (exactly one POST)', st.posts.length === 1, `posts=${st.posts.length}`);
  check('AC1: a receipt action is offered with "Payment recorded"', (await page.getByRole('button', { name: /receipt/i }).count()) + (await page.getByRole('link', { name: /receipt/i }).count()) > 0);
}, {});

// ---- AC1b: timeout, payment lost -> "Not recorded — retry" reuses the key ---------------------------
await run('FEF008-AC1 timeout/lost', 'hang-lost', async ({ page }, st) => {
  await collect(page);
  check('AC1: "Checking payment status…" appears', await seen(page, /Checking payment status/, 25000));
  const nr = await seen(page, /Not recorded/, 8000);
  check('AC1: nothing landed -> "Not recorded — retry"', nr);
  const retry = page.getByRole('button', { name: /retry/i });
  check('AC1: a Retry control is offered', (await retry.count()) > 0);
  if ((await retry.count()) > 0) await retry.first().click();
  for (let i = 0; i < 30 && st.posts.length < 2; i++) await sleep(100);
  check('AC1: the retry re-sends the SAME Idempotency-Key', st.posts.length === 2 && st.posts[0].key === st.posts[1].key && UUID.test(st.posts[0].key), JSON.stringify(st.posts));
  check('AC1: exactly one payment exists afterwards', st.payments.length === 1);
});

// ---- AC2 client side: the same key sent 3 times -> one row (server dedupes) -------------------------------
await run('FEF008-AC2 triple send', 'ok', async ({ page }, st) => {
  await collect(page, '200');
  for (let i = 0; i < 30 && st.posts.length < 1; i++) await sleep(100);
  check('AC2: first POST carries a UUID Idempotency-Key', st.posts.length === 1 && UUID.test(st.posts[0].key), JSON.stringify(st.posts));
});

// ---- AC3: reload mid-timeout restores the intent + key and checks status automatically ------------------
await run('FEF008-AC3 reload/landed', 'hang-landed', async ({ page }, st) => {
  await collect(page);
  for (let i = 0; i < 30 && st.posts.length < 1; i++) await sleep(100);
  const keyBefore = st.posts[0]?.key;
  const stored = await page.evaluate(() => ({ s: JSON.stringify({ ...sessionStorage }), l: JSON.stringify({ ...localStorage }) }));
  check('AC3: the pending intent is kept in sessionStorage (holding the key)', !!keyBefore && stored.s.includes(keyBefore), stored.s.slice(0, 200));
  check('NFR: the intent is NOT in localStorage', !!keyBefore && !stored.l.includes(keyBefore));
  const before = st.invGets.length;
  await page.reload();
  await page.getByText('INV-0001').waitFor();
  const rec = await seen(page, /Payment recorded/, 6000);
  check('AC3: after reload the status is checked automatically -> "Payment recorded"', rec);
  check('AC3: the automatic check called GET /billing/invoices/{id}', st.invGets.length > before, `before=${before} after=${st.invGets.length}`);
  check('AC3: reload did not send a second payment', st.posts.length === 1, `posts=${st.posts.length}`);
});
await run('FEF008-AC3 reload/lost', 'hang-lost', async ({ page }, st) => {
  await collect(page);
  for (let i = 0; i < 30 && st.posts.length < 1; i++) await sleep(100);
  await page.reload();
  await page.getByText('INV-0001').waitFor();
  const nr = await seen(page, /Not recorded/, 6000);
  check('AC3: after reload with nothing recorded -> "Not recorded — retry" is restored', nr);
  const retry = page.getByRole('button', { name: /retry/i });
  if ((await retry.count()) > 0) await retry.first().click();
  for (let i = 0; i < 30 && st.posts.length < 2; i++) await sleep(100);
  check('AC3: the retry after reload re-uses the key from before the reload', st.posts.length === 2 && st.posts[0].key === st.posts[1].key, JSON.stringify(st.posts));
});

// ---- network drop (connection reset) -> checks status, shows recorded, no double charge ---------------------
await run('FEF008-drop', 'drop', async ({ page }, st) => {
  await collect(page);
  const rec = await seen(page, /Payment recorded/, 8000);
  check('network drop after the server acted: the screen ends on "Payment recorded"', rec);
  check('network drop: exactly one payment, and any automatic resend used the same key', st.payments.length === 1 && new Set(st.posts.map((p) => p.key)).size === 1, `payments=${st.payments.length} posts=${st.posts.length}`);
});

// ---- AC5: offline refusal ---------------------------------------------------------------------------
await run('FEF008-AC5 offline', 'ok', async ({ page, context }, st) => {
  await page.getByLabel('Amount 1').fill('500');
  await context.setOffline(true);
  await page.getByRole('button', { name: /^Collect/ }).click();
  const msg = await seen(page, /You are offline — payments cannot be queued/, 3000);
  check('AC5: offline press -> "You are offline — payments cannot be queued"', msg);
  check('AC5: no payment request was made', st.posts.length === 0);
  const stored = await page.evaluate(() => JSON.stringify({ ...sessionStorage, ...localStorage }));
  check('AC5: nothing about the payment was stored', !/nabd\.pay|idempot|"amount"/i.test(stored), stored.slice(0, 200));
  await context.setOffline(false);
});

// ---- AC6: key reused with a different amount -> 422 -> clear message -----------------------------------
await run('FEF008-AC6 key reused', 'reused', async ({ page }, st) => {
  await collect(page, '300');
  const clear = await seen(page, /different|already|started with/i, 4000);
  check('AC6: 422 IDEMPOTENCY_KEY_REUSED shows a clear message', clear);
  await sleep(600);
  check('AC6: no automatic retry after the 422', st.posts.length === 1, `posts=${st.posts.length}`);
});

// ---- 390px ------------------------------------------------------------------------------------------------
await run('FEF008-390 timeout', 'hang-lost', async ({ page }, st) => {
  await collect(page);
  const checking = await seen(page, /Checking payment status/, 25000);
  check('390px: the "Checking payment status…" state is shown', checking);
  check('390px: no horizontal scroll', await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
}, { viewport: { width: 390, height: 800 } });

// ---- AC7: Idempotency-Key on checkout POST, OTC sale + OTC payment ----------------------------------------------
{
  const s = await launch({});
  const st = { bill: [], otc: [], otcPay: [] };
  const CH = [{ id: 'c1', code: 'CONS', name: 'Consultation', category: 'Consultation', baseAmount: 500, followUpAmount: null, taxRatePercent: 0, stockQty: null }];
  const invoice = (paid) => ({ id: 'inv-9', invoiceNumber: 'INV-0009', patientName: 'Walk-in customer', currency: 'INR', subtotal: 500, discount: 0, tax: 0, roundOff: 0, total: 500, paid, balanceDue: 500 - paid, status: paid ? 'paid' : 'unpaid', lineItems: [{ chargeCode: 'CONS', chargeName: 'Consultation', category: 'Consultation', quantity: 1, unitPrice: 500, taxRatePercent: 0, lineTotal: 500 }], payments: paid ? [{ id: 'p1', method: 'cash', amount: 500, recordedAt: 'x' }] : [] });
  try {
    await seedClinic(s.page, PERMS, { tenantRegion: 'IN' });
    await mockApi(s.page, s.log, [
      ['GET', /^\/billing\/otc-charges$/, { body: { currency: 'INR', charges: CH } }],
      ['POST', /^\/billing\/otc-checkout$/, (r, req) => { st.otc.push(req.headers()['idempotency-key']); return J(r, 200, invoice(0)); }],
      ['POST', /^\/billing\/invoices\/inv-9\/payments$/, (r, req) => { st.otcPay.push(req.headers()['idempotency-key']); return J(r, 201, invoice(500)); }],
      ['GET', /^\/billing\/checkout\/[^/]+$/, { body: { ...ctx, charges: CH } }],
      ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
      ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, (r) => J(r, 404, { title: 'Not found', detail: 'x' })],
      ['POST', /^\/billing\/checkout\/[^/]+$/, (r, req) => { st.bill.push(req.headers()['idempotency-key']); return J(r, 200, { ...invoice(0), id: 'inv-9' }); }],
    ]);
    await s.page.goto(APP + '/checkout/otc');
    step = 'otc: pick charge';
    await s.page.getByRole('button', { name: /Consultation.*500/ }).click();
    step = 'otc: press checkout';
    await s.page.getByRole('button', { name: /Checkout|Generate|Create|Collect/ }).last().click();
    await s.page.getByText('INV-0009').waitFor({ timeout: 5000 });
    check('AC7: OTC checkout POST carries an Idempotency-Key', st.otc.length === 1 && UUID.test(st.otc[0]), JSON.stringify(st.otc));
    await s.page.getByPlaceholder('Amount').fill('500');
    await s.page.getByRole('button', { name: 'Record payment' }).click();
    await s.page.getByText(/paid/i).first().waitFor({ timeout: 5000 });
    check('AC7: OTC payment POST carries an Idempotency-Key', st.otcPay.length === 1 && UUID.test(st.otcPay[0]), JSON.stringify(st.otcPay));
    check('AC7: OTC sale and OTC payment use different keys', st.otc[0] !== st.otcPay[0]);
    step = 'visit checkout';
    await s.page.goto(APP + `/checkout/${Q}`);
    await s.page.getByText('Asha Verma').waitFor();
    await s.page.getByRole('button', { name: /Consultation.*500/ }).click();
    await s.page.getByRole('button', { name: /^Checkout$|^Collect/ }).last().click();
    await s.page.getByText('INV-0009').waitFor({ timeout: 5000 });
    check('AC7: visit checkout POST carries an Idempotency-Key', st.bill.length === 1 && UUID.test(st.bill[0]), JSON.stringify(st.bill));
    check('AC7: zero page/console errors on the three flows', noise(s.log).length === 0, JSON.stringify(noise(s.log)));
  } catch (e) {
    await s.page.screenshot({ path: `${process.env.SHOTS ?? '/tmp'}/ac7.png` }).catch(() => {});
    check('AC7: scenario ran to the end', false, `${step}: ${String(e).split('\n')[0]}`);
  } finally { dumpLog('AC7', s.log); await s.browser.close(); }
}

// ---- AC4: walk-in registration + check-in keys; retry after a network failure sends the same key ---------------
{
  const s = await launch({});
  const st = { reg: [], ci: [], n: 0 };
  const roster = [['GET', /^\/queue(\?.*)?$/, { body: [] }], ['GET', /^\/staff\/roster/, { body: [{ id: 'd1', name: 'Dr Who', departmentName: 'GP' }] }],
    ['GET', /^\/patients\?q=/, { body: { data: [] } }], ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5 } }],
    ['GET', /^\/doctors\/.*\/delay/, { body: [] }], ['GET', /^\/appointments\/callback-list/, { body: [] }]];
  try {
    await seedClinic(s.page, ['queue:view', 'queue:create', 'patients:view', 'patients:create'], { tenantRegion: 'IN' });
    await mockApi(s.page, s.log, [
      ['POST', /^\/patients$/, (r, req) => { st.reg.push(req.headers()['idempotency-key']); return J(r, 201, { id: 'p9' }); }],
      ['POST', /^\/queue\/check-in$/, (r, req) => { st.ci.push(req.headers()['idempotency-key']); st.n++; return st.n === 1 ? r.abort() : J(r, 201, { id: 'e9', tokenNumber: 7 }); }],
      ...roster]);
    step = 'arrivals';
    await s.page.goto(APP + '/arrivals');
    await sleep(1200);
    step = 'open walk-in';
    await s.page.getByRole('button', { name: /Walk-in/ }).first().click();
    step = 'fill form';
    await s.page.getByLabel('Patient', { exact: true }).fill('Zed Nobody');
    await s.page.getByRole('button', { name: /No match/ }).click();
    await s.page.locator('#newName').fill('Zed Nobody');
    await s.page.locator('#newPhone').fill('+919876543210');
    await s.page.locator('#newDob').fill('1990-01-01');
    await s.page.getByLabel(/Doctor/i).first().selectOption('d1');
    step = 'submit (first check-in POST is dropped, the client resends it)';
    await s.page.getByRole('button', { name: 'Check in', exact: true }).last().click();
    step = 'wait checked in';
    await s.page.getByText('Checked in').waitFor({ timeout: 5000 });
    check('AC4: walk-in registration POST has an Idempotency-Key', st.reg.length >= 1 && UUID.test(st.reg[0]), JSON.stringify(st.reg));
    check('AC4: a retry after a dropped check-in never registers a second patient', st.reg.length === 1, `register POSTs=${st.reg.length}`);
    check('AC4: the check-in retry re-sends the same Idempotency-Key', st.ci.length === 2 && st.ci[0] === st.ci[1] && UUID.test(st.ci[0]), JSON.stringify(st.ci));
    check('AC4: zero page/console errors', noise(s.log).length === 0, JSON.stringify(noise(s.log)));
  } catch (e) {
    await s.page.screenshot({ path: `${process.env.SHOTS ?? '/tmp'}/ac4.png` }).catch(() => {});
    check('AC4: scenario ran to the end', false, `${step}: ${String(e).split('\n')[0]}`);
  } finally { dumpLog('AC4', s.log); await s.browser.close(); }
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed; ${failed.length} failed`);
process.exit(failed.length ? 1 : 0);
