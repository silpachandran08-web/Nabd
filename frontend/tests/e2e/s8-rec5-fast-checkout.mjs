// REC-044 fast checkout: single-screen Collect + split tender + balance due. Mocked API (no backend).
// Run against a production server: APP=http://localhost:3105 node tests/e2e/s8-rec5-fast-checkout.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP, SHOTS } from './s8-rec5-lib.mjs';

const Q = '33333333-3333-4333-8333-333333333333';
const PERMS = ['billing:view', 'billing:create', 'billing:edit', 'queue:view', 'queue:edit'];
const CHARGES = [
  { id: 'c1', code: 'CONS', name: 'Consultation', category: 'Consultation', baseAmount: 500, followUpAmount: null, taxRatePercent: 0, stockQty: null },
  { id: 'c2', code: 'DRESS', name: 'Dressing', category: 'Procedure', baseAmount: 120, followUpAmount: null, taxRatePercent: 5, stockQty: null },
];
const ctx = (over = {}) => ({ queueEntryId: Q, patientName: 'Asha Verma', doctorName: 'Dr Rao', visitType: 'Walk-in', followUpEligible: false,
  currency: 'INR', charges: CHARGES, pendingProcedures: [], prescribedItems: [], departmentId: 'dept-1', queueStatus: 'checkout_pending', ...over });

/** Stateful fake of the billing endpoints. total for CONS+DRESS = 500 + 120 + 6 tax = 626. */
function billing(o = {}) {
  const st = { invoice: null, posts: { checkout: 0, payments: [] } };
  const mk = () => { const paid = st.invoice.payments.reduce((s, p) => s + p.amount, 0); return { ...st.invoice, paid, balanceDue: st.invoice.total - paid, status: paid >= st.invoice.total ? 'paid' : paid > 0 ? 'partial' : 'unpaid' }; };
  const h = [
    ['GET', /^\/billing\/checkout\/[^/]+$/, o.context ?? { body: ctx(o.ctx) }],
    ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
    ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, (r) => st.invoice ? r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mk()) }) : r.fulfill({ status: 404, contentType: 'application/problem+json', body: '{"title":"Not found","detail":"x"}' })],
    // FEF-008: the status check after a lost response reads the invoice by id.
    ['GET', /^\/billing\/invoices\/inv-1$/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mk()) })],
    ['POST', /^\/billing\/checkout\/[^/]+$/, async (r, req) => {
      st.posts.checkout++;
      if (o.delay) await new Promise((x) => setTimeout(x, o.delay));
      if (o.checkout500) return r.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"title":"Error","detail":"Invoice service unavailable"}' });
      st.invoice = { id: 'inv-1', invoiceNumber: 'INV-0001', patientName: 'Asha Verma', doctorName: 'Dr Rao', currency: 'INR', subtotal: 620, discount: 0, tax: 6, roundOff: 0, total: 626,
        lineItems: JSON.parse(req.postData()).lineItems.map((l) => ({ ...l, lineTotal: l.unitPrice * l.quantity })), payments: [] };
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mk()) });
    }],
    ['POST', /^\/billing\/invoices\/inv-1\/payments$/, async (r, req) => {
      const b = JSON.parse(req.postData());
      st.posts.payments.push(b);
      if (o.delay) await new Promise((x) => setTimeout(x, o.delay));
      const n = st.posts.payments.length;
      if (o.failPaymentOnce === n && !st.failed) { st.failed = true; return r.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"title":"Error","detail":"Payment gateway down"}' }); }
      st.invoice.payments.push({ id: `p${n}`, method: b.method, amount: b.amount, recordedAt: '2026-10-18T10:00:00Z', idempotencyKey: req.headers()['idempotency-key'] });
      if (o.landThenAbort === n && !st.aborted) { st.aborted = true; return r.abort('failed'); }
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mk()) });
    }],
  ];
  return { st, h };
}

async function open(name, o = {}, perms = PERMS, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms, { tenantRegion: 'IN' });
  const b = billing(o);
  await mockApi(s.page, s.log, b.h);
  await s.page.goto(`${APP}/checkout/${Q}`);
  return { ...s, ...b, name };
}
const addBoth = async (page) => { await page.getByRole('button', { name: /Consultation.*500/ }).click(); await page.getByRole('button', { name: /Dressing/ }).click(); };
const text = (page, sel) => page.locator(sel).first().innerText();
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
const hscroll = (page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);

try {
  // 1. Happy path: one screen, split tender cash + card, balance due live, Collect bills then pays in order.
  {
    const s = await open('happy');
    const { page } = s;
    await page.getByText('Asha Verma').waitFor();
    await addBoth(page);
    check('happy: balance due equals total before any tender', (await text(page, '[data-testid=balance-due]')).includes('626'));
    await page.getByLabel('Amount 1').fill('400');
    check('happy: balance due drops live to 226', (await text(page, '[data-testid=balance-due]')).includes('226'));
    await page.getByRole('button', { name: '+ Split payment' }).click();
    await page.getByRole('button', { name: 'Rest' }).last().click();
    check('happy: Rest fills the remaining 226.00', (await page.getByLabel('Amount 2').inputValue()) === '226.00');
    check('happy: balance due is 0 when fully tendered', (await text(page, '[data-testid=balance-due]')).includes('0'));
    const bar = page.getByRole('button', { name: /^Collect/ });
    check('happy: sticky bar button reads Collect 626', /Collect.*626/.test(await bar.innerText()));
    await page.screenshot({ path: `${SHOTS}/happy-before.png` });
    await bar.click();
    await page.getByText('INV-0001').waitFor();
    await page.waitForFunction(() => document.body.innerText.includes('Balance due'));
    check('happy: exactly one bill POST', s.st.posts.checkout === 1);
    check('happy: payments sent in order cash 400, card 226', JSON.stringify(s.st.posts.payments) === JSON.stringify([{ method: 'cash', amount: 400 }, { method: 'card', amount: 226 }]), JSON.stringify(s.st.posts.payments));
    check('happy: invoice shows paid and both payments', /paid/i.test(await page.locator('main').innerText()) && /Cash/.test(await page.locator('main').innerText()) && /Card/.test(await page.locator('main').innerText()));
    check('happy: no payment form once paid', (await page.getByLabel('Amount 1').count()) === 0);
    await page.screenshot({ path: `${SHOTS}/happy-after.png` });
    check('happy: no console/page errors', noise(s.log).length === 0, noise(s.log).join('|'));
    dumpLog('happy', s.log); await s.browser.close();
  }

  // 2. Double click on Collect cannot bill or charge twice (slow server).
  {
    const s = await open('double', { delay: 400 });
    const { page } = s;
    await page.getByText('Asha Verma').waitFor();
    await addBoth(page);
    await page.getByLabel('Amount 1').fill('626');
    const btn = page.getByRole('button', { name: /^Collect/ });
    await btn.dblclick();
    await btn.click({ force: true }).catch(() => {});
    await page.getByText('INV-0001').waitFor();
    await page.waitForTimeout(1200);
    check('double-click: one bill, one payment', s.st.posts.checkout === 1 && s.st.posts.payments.length === 1, `bill=${s.st.posts.checkout} pay=${s.st.posts.payments.length}`);
    await s.browser.close();
  }

  // 3. Payment 2 fails (500): invoice exists, payment 1 stays recorded, retry sends only payment 2.
  {
    const s = await open('fail', { failPaymentOnce: 2 });
    const { page } = s;
    await page.getByText('Asha Verma').waitFor();
    await addBoth(page);
    await page.getByLabel('Amount 1').fill('400');
    await page.getByRole('button', { name: '+ Split payment' }).click();
    await page.getByLabel('Amount 2').fill('226');
    await page.getByRole('button', { name: /^Collect/ }).click();
    await page.getByText('Payment gateway down').waitFor();
    check('partial failure: server error detail shown', true);
    check('partial failure: invoice view with 226 left, first payment kept', (await page.locator('main').innerText()).includes('INV-0001') && s.st.invoice.payments.length === 1);
    check('partial failure: paid row blanked, failed row kept', (await page.getByLabel('Amount 1').inputValue()) === '' && (await page.getByLabel('Amount 2').inputValue()) === '226');
    await page.getByRole('button', { name: /^Collect/ }).click();
    await page.waitForFunction(() => document.body.innerText.toLowerCase().includes('paid'));
    const sent = s.st.posts.payments.map((p) => `${p.method}:${p.amount}`).join(',');
    check('retry: cash was sent once, card twice, bill once', sent === 'cash:400,card:226,card:226' && s.st.posts.checkout === 1, sent);
    check('retry: server holds exactly 2 payments totalling 626', s.st.invoice.payments.length === 2 && s.st.invoice.payments.reduce((a, p) => a + p.amount, 0) === 626);
    check('partial failure: no page errors', s.log.pageErrors.length === 0);
    await s.browser.close();
  }

  // 4. Unknown outcome: payment lands but the response is lost. Page re-reads the invoice, no second charge.
  {
    const s = await open('lost', { landThenAbort: 1 });
    const { page } = s;
    await page.getByText('Asha Verma').waitFor();
    await addBoth(page);
    await page.getByLabel('Amount 1').fill('626');
    await page.getByRole('button', { name: /^Collect/ }).click();
    await page.getByText('INV-0001').waitFor();
    await page.waitForFunction(() => document.body.innerText.toLowerCase().includes('paid'));
    check('lost response: payment sent once, treated as recorded', s.st.posts.payments.length === 1 && s.st.invoice.payments.length === 1);
    await s.browser.close();
  }

  // 5. Boundary: over-balance and 3-decimal amounts are refused before anything is sent; pay-later partial works.
  {
    const s = await open('bounds');
    const { page } = s;
    await page.getByText('Asha Verma').waitFor();
    await addBoth(page);
    await page.getByLabel('Amount 1').fill('626.01');
    await page.getByRole('button', { name: /^Collect/ }).click();
    await page.getByText(/more than the balance due/).waitFor();
    check('boundary: 626.01 against 626 refused, nothing sent', s.st.posts.checkout === 0 && s.st.posts.payments.length === 0);
    await page.getByLabel('Amount 1').fill('10.123');
    await page.getByRole('button', { name: /^(Collect|Checkout)/ }).click();
    await page.getByText(/at most 2 decimals/).waitFor();
    check('boundary: 3 decimals refused, nothing sent', s.st.posts.checkout === 0);
    await page.getByLabel('Amount 1').fill('100');
    await page.getByRole('button', { name: /^Collect/ }).click();
    await page.getByText('INV-0001').waitFor();
    await page.waitForFunction(() => document.body.innerText.toLowerCase().includes('part-paid'));
    check('boundary: partial 100 leaves 526 balance due, status part-paid', (await page.locator('main').innerText()).includes('526') && s.st.posts.payments.length === 1);
    await s.browser.close();
  }

  // 6. Permission absent: no billing:edit -> no payment UI, no balance due; bill still works. No discount permission -> no discount field.
  {
    const s = await open('noedit', {}, ['billing:view', 'billing:create', 'queue:view']);
    const { page } = s;
    await page.getByText('Asha Verma').waitFor();
    await addBoth(page);
    check('no billing:edit: tender rows and balance due absent', (await page.getByLabel('Amount 1').count()) === 0 && (await page.getByTestId('balance-due').count()) === 0 && (await page.getByText('+ Split payment').count()) === 0);
    check('no billing.discount: discount input absent', (await page.getByText('Discount').count()) === 0);
    await page.getByRole('button', { name: 'Checkout', exact: true }).click();
    await page.getByText('INV-0001').waitFor();
    check('no billing:edit: bill sent, no payment sent, no payment form after', s.st.posts.checkout === 1 && s.st.posts.payments.length === 0 && (await page.getByLabel('Amount 1').count()) === 0);
    await s.browser.close();
  }
  {
    const s = await open('noview', {}, ['billing:view', 'queue:view']);
    await s.page.getByText('Asha Verma').waitFor();
    check('no billing:create: picker, Collect bar and tender rows absent, honest empty note shown', (await s.page.getByRole('button', { name: /Consultation.*500/ }).count()) === 0 && (await s.page.getByRole('button', { name: /^Collect|^Checkout/ }).count()) === 0 && (await s.page.getByLabel('Amount 1').count()) === 0 && (await s.page.getByText('No invoice has been generated').count()) === 1);
    await s.browser.close();
  }

  // 7. Empty state: nothing picked -> bill button disabled, hint shown, no tender sent.
  {
    const s = await open('empty');
    await s.page.getByText('Asha Verma').waitFor();
    check('empty: hint shown and Checkout disabled', (await s.page.getByText('Pick a charge from the list').count()) === 1 && (await s.page.getByRole('button', { name: 'Checkout', exact: true }).isDisabled()));
    await s.browser.close();
  }

  // 8. Error states: context 500, context 403, bill 500.
  {
    const s = await open('ctx500', { context: { status: 500, body: { title: 'Error', detail: 'x' } } });
    await s.page.getByText(/Couldn't load checkout details/).waitFor();
    check('error: context 500 shows message, no crash', noise(s.log).length === 0, noise(s.log).join('|'));
    await s.browser.close();
  }
  {
    const s = await open('ctx403', { context: { status: 403, body: { title: 'Forbidden', detail: 'x' } } });
    await s.page.getByText(/doesn.t have access to checkout/).waitFor();
    check('error: context 403 shows no-access message', true);
    await s.browser.close();
  }
  {
    const s = await open('bill500', { checkout500: true });
    await s.page.getByText('Asha Verma').waitFor();
    await addBoth(s.page);
    await s.page.getByLabel('Amount 1').fill('626');
    await s.page.getByRole('button', { name: /^Collect/ }).click();
    await s.page.getByText('Invoice service unavailable').waitFor();
    check('error: bill 500 shows message, no payment attempted, items kept', s.st.posts.payments.length === 0 && (await s.page.getByLabel('Amount 1').inputValue()) === '626');
    await s.browser.close();
  }

  // 9. 390px: no horizontal page scroll, sticky Collect bar in view with a 44px button, in picker and invoice states.
  {
    const s = await open('m390', { failPaymentOnce: 1 }, PERMS, { width: 390, height: 800 });
    const { page } = s;
    await page.getByText('Asha Verma').waitFor();
    await addBoth(page);
    await page.getByLabel('Amount 1').fill('626');
    check('390: no horizontal page scroll (picker)', await hscroll(page));
    const box = await page.getByRole('button', { name: /^Collect/ }).boundingBox();
    check('390: Collect button in viewport and >= 44px tall', box && box.height >= 44 && box.y + box.height <= 800 && box.x >= 0 && box.x + box.width <= 390, JSON.stringify(box));
    const top = await page.evaluate(() => { const b = [...document.querySelectorAll('button')].find((x) => /^Collect/.test(x.innerText)).getBoundingClientRect(); const e = document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2); return e && e.closest('button') && /^Collect/.test(e.closest('button').innerText); });
    check('390: Collect button is not covered by the bottom nav (hit-test)', top === true);
    const rowBoxes = await page.locator('select, input[aria-label^=Amount]').evaluateAll((els) => els.map((e) => e.getBoundingClientRect().height));
    check('390: tender controls >= 44px', rowBoxes.length > 0 && rowBoxes.every((h) => h >= 44), rowBoxes.join(','));
    await page.screenshot({ path: `${SHOTS}/m390-picker.png` });
    await page.getByRole('button', { name: /^Collect/ }).click();
    await page.getByText('Payment gateway down').waitFor();
    check('390: no horizontal page scroll (invoice + error)', await hscroll(page));
    await page.screenshot({ path: `${SHOTS}/m390-invoice.png` });
    check('390: no console/page errors', noise(s.log).length === 0, noise(s.log).join('|'));
    await s.browser.close();
  }
  for (const w of [1440, 1280]) {
    const s = await open('wide' + w, {}, PERMS, { width: w, height: 900 });
    await s.page.getByText('Asha Verma').waitFor();
    await addBoth(s.page);
    await s.page.getByLabel('Amount 1').fill('626');
    const inView = await s.page.evaluate(() => { const b = [...document.querySelectorAll('button')].find((x) => /^Collect/.test(x.innerText)).getBoundingClientRect(); const a = document.querySelector('input[aria-label="Amount 1"]').getBoundingClientRect(); return b.right <= innerWidth && a.right <= innerWidth; });
    check(`${w}: no horizontal scroll, tender amount and Collect inside the viewport`, (await hscroll(s.page)) && inView);
    if (w === 1440) await s.page.screenshot({ path: `${SHOTS}/w1440.png` });
    await s.browser.close();
  }
  {
    const s = await open('m1024', {}, PERMS, { width: 1024, height: 800 });
    await s.page.getByText('Asha Verma').waitFor();
    await addBoth(s.page);
    check('1024: no horizontal page scroll', await hscroll(s.page));
    await s.browser.close();
  }
} catch (e) {
  check('script ran to completion', false, String(e).split('\n')[0]);
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
