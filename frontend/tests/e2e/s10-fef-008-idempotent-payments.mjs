// FEF-008: idempotent payments with 'check status' recovery (visit checkout, OTC, package sale, check-in, walk-in registration).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3406 node tests/e2e/s10-fef-008-idempotent-payments.mjs
// The mock server dedupes by Idempotency-Key like the real one, so "one payment row" is asserted on server state.
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const Q = '33333333-3333-4333-8333-333333333333';
const PERMS = ['billing:view', 'billing:create', 'billing:edit', 'queue:view', 'queue:edit'];
const J = (r, status, body) => r.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const ctx = { queueEntryId: Q, patientName: 'Asha Verma', doctorName: 'Dr Rao', visitType: 'Walk-in', followUpEligible: false, currency: 'INR',
  charges: [{ id: 'c1', code: 'CONS', name: 'Consultation', category: 'Consultation', baseAmount: 500, followUpAmount: null, taxRatePercent: 0, stockQty: null }],
  pendingProcedures: [], prescribedItems: [], departmentId: 'dep-1', queueStatus: 'checkout_pending' };

/** behave(n, key) for the n-th payment POST: 'land-abort' | 'land-hang' | 'hang' | '503' | '422' | 'ok'. 'hang' = never reaches the server. */
function server({ behave = () => 'ok', invoice = true } = {}) {
  const st = { posts: [], payments: [], inv: invoice, headers: [] };
  const view = () => { const paid = st.payments.reduce((s, p) => s + p.amount, 0); return { id: 'inv-1', invoiceNumber: 'INV-0001', patientName: 'Asha Verma', doctorName: 'Dr Rao', currency: 'INR', subtotal: 500, discount: 0, tax: 0, roundOff: 0, total: 500,
    paid, balanceDue: 500 - paid, status: paid >= 500 ? 'paid' : 'unpaid', lineItems: [{ chargeCode: 'CONS', chargeName: 'Consultation', category: 'Consultation', quantity: 1, unitPrice: 500, taxRatePercent: 0, lineTotal: 500 }], payments: [...st.payments] }; };
  const land = (key, b) => { if (!st.payments.some((p) => p.idempotencyKey === key)) st.payments.push({ id: 'p' + (st.payments.length + 1), method: b.method, amount: b.amount, recordedAt: '2026-10-18T10:00:00Z', idempotencyKey: key }); };
  const h = [
    ['GET', /^\/billing\/checkout\/[^/]+$/, { body: ctx }],
    ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
    ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, (r) => (st.inv ? J(r, 200, view()) : J(r, 404, { title: 'Not found', detail: 'x' }))],
    ['GET', /^\/billing\/invoices\/inv-1$/, (r) => J(r, 200, view())],
    ['POST', /^\/billing\/checkout\/[^/]+$/, (r, req) => { st.headers.push(['checkout', req.headers()['idempotency-key']]); st.inv = true; return J(r, 200, view()); }],
    ['POST', /^\/billing\/invoices\/inv-1\/payments$/, async (r, req) => {
      const key = req.headers()['idempotency-key']; const b = JSON.parse(req.postData());
      st.posts.push({ key, b }); const mode = behave(st.posts.length, key, b);
      try {
        if (mode === 'hang') return await sleep(16000).then(() => r.abort('failed').catch(() => {}));
        if (mode === 'land-hang') { land(key, b); return await new Promise(() => {}); }
        if (mode === 'land-abort') { land(key, b); return r.abort('failed'); }
        if (mode === '503') return J(r, 503, { title: 'Unavailable', detail: 'Payment service unavailable' });
        if (mode === '422') return J(r, 422, { code: 'IDEMPOTENCY_KEY_REUSED', title: 'Unprocessable', detail: 'Key reused' });
        land(key, b); return J(r, 201, view());
      } catch { /* page aborted the request */ }
    }],
  ];
  return { st, h };
}
async function open(name, o, { perms = PERMS, viewport } = {}) {
  const s = await launch({ viewport }); await seedClinic(s.page, perms, { tenantRegion: 'IN' });
  const sv = server(o); await mockApi(s.page, s.log, sv.h);
  await s.page.goto(`${APP}/checkout/${Q}`); await s.page.getByText('INV-0001').waitFor();
  return { ...s, ...sv, name };
}
const fill = async (page) => { await page.getByLabel('Payment method 1').selectOption('upi'); await page.getByLabel('Amount 1').fill('500'); };
const collect = (page) => page.getByRole('button', { name: /^Collect/ }).click();
const status = (page) => page.getByTestId('pay-status');
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|ERR_|net::/.test(x))];
const hscroll = (page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);
const keys = (s) => s.st.posts.map((p) => p.key);
const intents = (page) => page.evaluate(() => Object.keys(sessionStorage).filter((k) => k.startsWith('nabd.pay.')));

try {
  // 1. Lost response (payment landed): 'Checking payment status…' -> 'Payment recorded'; one POST, header present.
  {
    const s = await open('lost', { behave: () => 'land-abort' }, { viewport: { width: 390, height: 800 } });
    await fill(s.page); await collect(s.page);
    await status(s.page).filter({ hasText: 'Payment recorded' }).waitFor();
    check('1 lost response: shows "Payment recorded" after the status check', true);
    check('1 exactly one POST, with an Idempotency-Key (uuid)', s.st.posts.length === 1 && /^[0-9a-f-]{36}$/.test(s.st.posts[0].key ?? ''), JSON.stringify(keys(s)));
    check('1 one payment row, balance reduced once', s.st.payments.length === 1 && /Payments/.test(await s.page.locator('main').innerText()));
    check('1 payment intent cleared after it is known recorded', (await intents(s.page)).length === 0);
    check('1 nothing in localStorage', await s.page.evaluate(() => Object.keys(localStorage).every((k) => !k.startsWith('nabd.pay'))));
    check('1 390px: no horizontal scroll', await hscroll(s.page));
    check('1 zero console/page errors', noise(s.log).length === 0, noise(s.log).join('|'));
    dumpLog('1', s.log); await s.browser.close();
  }
  // 2. Real 15 s timeout: 'Checking payment status…' -> 'Not recorded — retry'; retries x3 reuse ONE key; one payment.
  {
    const s = await open('timeout', { behave: (n) => (n === 1 ? 'hang' : n === 2 ? '503' : 'ok') });
    await fill(s.page); await collect(s.page);
    await status(s.page).filter({ hasText: 'Checking payment status…' }).waitFor({ timeout: 20000 });
    check('2 after the 15 s timeout the screen shows "Checking payment status…"', true);
    await status(s.page).filter({ hasText: 'Not recorded — retry' }).waitFor();
    check('2 invoice read back, nothing landed -> "Not recorded — retry"', true);
    check('2 amount/method kept for the retry', (await s.page.getByLabel('Amount 1').inputValue()) === '500');
    await collect(s.page); await status(s.page).filter({ hasText: 'Not recorded — retry' }).waitFor(); // 503, still not recorded
    check('2 5xx also goes to the status check', s.st.posts.length === 2 && /Payment service unavailable/.test(await s.page.locator('main').innerText()));
    await collect(s.page); await status(s.page).filter({ hasText: /^$|Payment recorded/ }).first().waitFor({ state: 'attached' }).catch(() => {});
    await s.page.waitForFunction(() => document.body.innerText.toLowerCase().includes('paid'));
    check('2 3 attempts used the SAME Idempotency-Key', s.st.posts.length === 3 && new Set(keys(s)).size === 1, JSON.stringify(keys(s)));
    check('2 exactly one payment row on the server', s.st.payments.length === 1);
    check('2 zero console/page errors', noise(s.log).length === 0, noise(s.log).join('|'));
    await s.browser.close();
  }
  // 3. Reload mid-timeout: intent + key restored, status checked automatically, nothing re-sent.
  for (const landed of [true, false]) {
    const s = await open('reload', { behave: (n) => (n === 1 ? (landed ? 'land-hang' : 'hang') : 'ok') });
    await fill(s.page); await collect(s.page);
    await s.page.getByTestId('pay-status').or(s.page.getByText('Recording…')).first().waitFor();
    await sleep(300);
    const k1 = keys(s)[0];
    check(`3 (${landed ? 'landed' : 'not landed'}) intent stored in sessionStorage before reload`, (await intents(s.page)).length === 1);
    await s.page.reload(); await s.page.getByText('INV-0001').waitFor();
    if (landed) {
      await status(s.page).filter({ hasText: 'Payment recorded' }).waitFor();
      check('3 landed: reload auto-check shows "Payment recorded", no second POST', s.st.posts.length === 1 && s.st.payments.length === 1);
    } else {
      await status(s.page).filter({ hasText: 'Not recorded — retry' }).waitFor();
      check('3 not landed: "Not recorded — retry", amount restored', (await s.page.getByLabel('Amount 1').inputValue()) === '500' && s.st.posts.length === 1);
      await collect(s.page); await s.page.waitForFunction(() => document.body.innerText.toLowerCase().includes('paid'));
      check('3 not landed: retry after reload reuses the original key, one payment', keys(s).length === 2 && keys(s)[1] === k1 && s.st.payments.length === 1, JSON.stringify(keys(s)));
    }
    check('3 zero page errors', s.log.pageErrors.length === 0);
    await s.browser.close();
  }
  // 4. Offline: refused with the message, nothing sent, nothing stored.
  {
    const s = await open('offline', {});
    await fill(s.page); await s.context.setOffline(true); await collect(s.page);
    await s.page.getByText('You are offline — payments cannot be queued').waitFor();
    check('4 offline: refused with "You are offline — payments cannot be queued"', true);
    check('4 offline: no POST sent, nothing stored', s.st.posts.length === 0 && (await intents(s.page)).length === 0);
    await s.context.setOffline(false); await s.browser.close();
  }
  // 5. Key reused with a different amount -> 422 -> clear message, fresh key next time.
  {
    const s = await open('reused', { behave: (n) => (n === 1 ? '422' : 'ok') });
    await fill(s.page); await collect(s.page);
    await s.page.getByText(/already started with a different amount/).waitFor();
    check('5 422 IDEMPOTENCY_KEY_REUSED shows a clear message', true);
    await collect(s.page); await s.page.waitForFunction(() => document.body.innerText.toLowerCase().includes('paid'));
    check('5 next attempt uses a new key', keys(s).length === 2 && keys(s)[0] !== keys(s)[1]);
    await s.browser.close();
  }
  // 6. First checkout (no invoice yet): bill POST and payment POST both carry a key.
  {
    const s = await open('first', { invoice: false });
    await s.page.getByText('Asha Verma').waitFor();
    await s.page.getByRole('button', { name: /Consultation.*500/ }).click();
    await s.page.getByLabel('Amount 1').fill('500'); await collect(s.page);
    await s.page.waitForFunction(() => document.body.innerText.toLowerCase().includes('paid'));
    check('6 visit checkout POST sends Idempotency-Key', !!s.st.headers.find((x) => x[0] === 'checkout')?.[1]);
    check('6 visit payment POST sends Idempotency-Key', !!s.st.posts[0]?.key);
    await s.browser.close();
  }
  // 7. Permission absent: no billing:edit -> no payment controls at all.
  {
    const s = await open('perm', {}, { perms: ['billing:view', 'queue:view'] });
    check('7 no billing:edit: payment controls absent', (await s.page.getByLabel('Amount 1').count()) === 0 && (await s.page.getByRole('button', { name: /^Collect/ }).count()) === 0);
    await s.browser.close();
  }
  // 8. OTC checkout + payment, package sale, walk-in registration + check-in: keys present; retry after a lost response reuses them.
  {
    const { browser, page, log } = await launch(); await seedClinic(page, ['billing:view', 'billing:create', 'billing:edit'], { tenantRegion: 'IN' });
    const sent = []; const pay = [];
    const inv = (paid) => ({ id: 'inv-9', invoiceNumber: 'INV-9', patientName: 'Counter', currency: 'INR', subtotal: 500, discount: 0, tax: 0, roundOff: 0, total: 500, paid, balanceDue: 500 - paid, status: paid ? 'paid' : 'unpaid',
      lineItems: [{ chargeCode: 'CONS', chargeName: 'Consultation', category: 'Consultation', quantity: 1, unitPrice: 500, taxRatePercent: 0 }], payments: pay });
    await mockApi(page, log, [
      ['GET', /^\/billing\/otc-charges/, { body: { currency: 'INR', charges: ctx.charges } }],
      ['POST', /^\/billing\/otc-checkout$/, (r, req) => { sent.push(['otc', req.headers()['idempotency-key']]); return J(r, 200, inv(0)); }],
      ['POST', /^\/billing\/invoices\/inv-9\/payments$/, (r, req) => { const k = req.headers()['idempotency-key']; sent.push(['otc-pay', k]); if (!pay.length) pay.push({ id: 'p', method: 'cash', amount: 500, idempotencyKey: k }); return J(r, 201, inv(500)); }],
    ]);
    await page.goto(APP + '/checkout/otc'); await page.getByRole('button', { name: /Consultation.*500/ }).click();
    await page.getByRole('button', { name: /Checkout|Generate|Create/i }).first().click();
    await page.getByText('INV-9').waitFor();
    await page.locator('form input[type=number]').fill('500'); await page.getByRole('button', { name: 'Record payment' }).click();
    await page.waitForFunction(() => document.body.innerText.toLowerCase().includes('paid'));
    check('8 OTC checkout POST sends Idempotency-Key', !!sent.find((x) => x[0] === 'otc')?.[1], JSON.stringify(sent));
    check('8 OTC payment POST sends Idempotency-Key', !!sent.find((x) => x[0] === 'otc-pay')?.[1]);
    check('8 OTC zero page errors', log.pageErrors.length === 0, log.pageErrors.join('|'));
    await browser.close();
  }
  {
    const { browser, page, log } = await launch(); await seedClinic(page, ['packages:view', 'packages:create'], { tenantRegion: 'IN' });
    const pk = { id: 'pk1', name: 'Smile Pack', packageType: 'single', speciality: null, description: null, status: 'on_sale', price: 900, taxInclusive: true, validityDays: 90, validityStarts: 'purchase', graceDays: 0, refundNote: null, listValue: 1000, saveAmount: 100, savePercent: 10, priceFloor: 0, belowFloor: false, eligibleDoctorIds: [], items: [], doctorLeaveWarning: null };
    const sells = []; let sold = null;
    await mockApi(page, log, [
      ['GET', /^\/packages\/instances/, { body: [] }], ['GET', /^\/packages\/refunds|expiring|liability/, { body: [] }], ['GET', /^\/packages\/settings/, { body: { priceFloorPercent: 0 } }],
      ['GET', /^\/packages(\?|$)/, { body: [pk] }],
      ['GET', /^\/patients\?q=/, { body: { data: [{ id: 'pt1', name: 'Zed Patient', phone: '+911', mrn: 'M1' }] } }],
      ['POST', /^\/packages\/sell$/, (r, req) => { const k = req.headers()['idempotency-key']; sells.push(k); if (sells.length === 1) { sold = k; return r.abort('failed'); } return J(r, 201, { id: 'in1', packageId: 'pk1', packageName: 'Smile Pack', patientId: 'pt1', patientName: 'Zed Patient', invoiceId: 'inv-5', invoiceNumber: 'INV-5', soldPrice: 900, soldTax: 0, validityStart: null, validityEnd: null, graceDays: 0, status: 'active', items: [], events: null }); }],
    ]);
    await page.goto(APP + '/packages'); await page.getByRole('button', { name: 'Sell package' }).click();
    await page.getByPlaceholder(/Search patient/).fill('Zed'); await page.getByRole('button', { name: /Zed Patient/ }).click();
    for (const pick of [/Continue/, /Continue/, /Continue/]) { if (pick.source === 'Continue' && (await page.getByRole('button', { name: /Smile Pack/ }).count())) await page.getByRole('button', { name: /Smile Pack/ }).click(); await page.getByRole('button', { name: pick }).click(); }
    await page.getByRole('button', { name: 'Cash' }).click(); await page.getByRole('button', { name: 'Sell package' }).click();
    await page.getByText(/re-sent safely/).waitFor();
    await page.getByRole('button', { name: 'Sell package' }).click(); await page.getByText(/Sold — invoice issued/).waitFor();
    check('8 package sale POST sends Idempotency-Key', !!sells[0]);
    check('8 package sale retry after a lost response reuses the same key', sells.length === 2 && sells[0] === sells[1], JSON.stringify(sells));
    check('8 package sale zero page errors', log.pageErrors.length === 0, log.pageErrors.join('|'));
    await browser.close();
  }
  {
    const { browser, page, log } = await launch({ viewport: { width: 390, height: 800 } });
    await seedClinic(page, ['queue:view', 'queue:create', 'patients:view', 'patients:create'], { tenantRegion: 'IN' });
    const regs = []; const cis = []; const queue = []; const patients = new Map();
    await mockApi(page, log, [
      ['GET', /^\/queue(\?.*)?$/, (r) => J(r, 200, queue)], ['GET', /^\/staff\/roster/, { body: [{ id: 'd1', name: 'Dr Who', departmentName: 'GP' }] }],
      ['GET', /^\/patients\?q=/, { body: { data: [] } }], ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5 } }], ['GET', /^\/doctors\/.*\/delay/, { body: [] }], ['GET', /^\/appointments\/callback-list/, { body: [] }],
      ['POST', /^\/patients$/, (r, req) => { const k = req.headers()['idempotency-key']; regs.push(k); if (!patients.has(k)) patients.set(k, 'p' + patients.size); return J(r, 201, { id: patients.get(k) }); }],
      ['POST', /^\/queue\/check-in$/, (r, req) => { const k = req.headers()['idempotency-key']; cis.push(k); if (cis.length === 1) { queue.push({ id: 'e1', patientId: 'p0', tokenNumber: 1 }); return r.abort('failed'); } return J(r, 201, { id: 'e1', tokenNumber: 1 }); }],
    ]);
    await page.goto(APP + '/arrivals?action=walkin');
    await page.locator('#patientSearch').fill('Zed Patient'); await page.getByRole('button', { name: /register a new patient/ }).click();
    await page.locator('#newName').fill('Zed Patient'); await page.locator('#newPhone').fill('+911234567'); await page.locator('#newDob').fill('1990-01-01');
    await page.locator('#doctor').selectOption('d1');
    await page.getByRole('button', { name: 'Check in', exact: true }).click(); await page.getByText(/Couldn't reach the server/).waitFor();
    await page.getByRole('button', { name: 'Check in', exact: true }).click(); await sleep(800);
    check('8 walk-in registration POST sends Idempotency-Key', !!regs[0]);
    check('8 check-in POST sends Idempotency-Key', !!cis[0]);
    check('8 check-in retry after a lost response reuses its key (one queue entry)', cis.length === 2 && cis[0] === cis[1] && queue.length === 1, JSON.stringify(cis));
    check('8 registration is not re-sent on retry (one patient)', regs.length === 1 && patients.size === 1, String(regs.length));
    check('8 390px: no horizontal scroll', await hscroll(page));
    check('8 walk-in zero page errors', log.pageErrors.length === 0, log.pageErrors.join('|'));
    await browser.close();
  }
} catch (e) { check('script completed', false, String(e).slice(0, 400)); }
const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
