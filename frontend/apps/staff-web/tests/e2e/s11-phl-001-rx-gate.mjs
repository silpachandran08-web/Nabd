// PHL-001: Rx-only items need a signed prescription link at the counter and at visit checkout (J10).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3515 node tests/e2e/s11-phl-001-rx-gate.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const Q = '33333333-3333-4333-8333-333333333333';
const PI = '44444444-4444-4444-8444-444444444444';
const PERMS = ['billing:view', 'billing:create', 'billing:edit', 'queue:view', 'queue:edit'];
// Fixture items: isRx is the clinic's own catalogue flag, not a clinical list.
const CHARGES = [
  { id: 'c1', code: 'PH-OTC', name: 'Plain Item', category: 'Pharmacy', baseAmount: 20, followUpAmount: null, taxRatePercent: 0, stockQty: 30, isRx: false },
  { id: 'c2', code: 'PH-RX', name: 'Fixture Drug', category: 'Pharmacy', baseAmount: 50, followUpAmount: null, taxRatePercent: 0, stockQty: 30, isRx: true },
  { id: 'c3', code: 'PH-RX2', name: 'Other Rx', category: 'Pharmacy', baseAmount: 40, followUpAmount: null, taxRatePercent: 0, stockQty: 30, isRx: true },
];
const RXLINE = { prescriptionItemId: PI, patientId: 'p1', patientName: 'Asha Verma', drugName: 'Fixture Drug', dosage: '1 tab', quantity: 10, remainingQty: 10, signedAt: '2026-10-19T08:00:00Z' };
const invoice = (lines) => ({ id: 'inv-1', invoiceNumber: 'INV-0001', patientName: 'Walk-in customer', currency: 'INR', subtotal: 20, discount: 0, tax: 0, roundOff: 0,
  total: 20, paid: 0, balanceDue: 20, status: 'unpaid', payments: [],
  lineItems: lines.map((l) => ({ chargeCode: l.chargeCode, chargeName: l.chargeCode, category: 'Pharmacy', quantity: l.quantity, unitPrice: 20, taxRatePercent: 0, lineTotal: 20 * l.quantity })) });
const problem = (r, status, type, detail) => r.fulfill({ status, contentType: 'application/problem+json', body: JSON.stringify({ type: `https://nabd.health/problems/${type}`, title: 'x', detail }) });

function api(o = {}) {
  const st = { posts: [], searches: [] };
  const h = [
    ['GET', /^\/billing\/otc-charges/, { body: { currency: 'INR', charges: CHARGES } }],
    ['POST', /^\/pharmacy\/rx-lines\/search/, (r, req) => {
      st.searches.push(JSON.parse(req.postData()));
      if (o.search500) return problem(r, 500, 'internal', 'x');
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(o.noLines ? [] : [RXLINE]) });
    }],
    ['POST', /^\/billing\/otc-checkout/, (r, req) => {
      const b = JSON.parse(req.postData()); st.posts.push(b);
      if (o.exhausted) return problem(r, 422, 'rx-quantity-exhausted', 'The linked prescription line for PH-RX has 0 left; this bill asks for 1.');
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(invoice(b.lineItems)) });
    }],
    ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, { status: 404, body: { title: 'Not found', detail: 'x' } }],
    ['GET', /^\/billing\/checkout\/[^/]+$/, { body: { queueEntryId: Q, patientName: 'Asha Verma', doctorName: 'Dr Rao', visitType: 'Walk-in', followUpEligible: false,
      currency: 'INR', charges: CHARGES, pendingProcedures: [], departmentId: 'd1', queueStatus: 'checkout_pending', suggestedLines: [],
      prescribedItems: [{ prescriptionItemId: PI, drugName: 'Fixture Drug', dosage: '1 tab', frequency: 'OD', duration: null, instructions: null, quantity: 10, remainingQty: 10 }] } }],
    ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
    ['POST', /^\/billing\/checkout\/[^/]+$/, (r, req) => {
      const b = JSON.parse(req.postData()); st.posts.push(b);
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...invoice(b.lineItems), patientName: 'Asha Verma' }) });
    }],
  ];
  return { st, h };
}
async function open(path, perms = PERMS, o = {}, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms, { tenantRegion: 'IN' });
  const a = api(o); await mockApi(s.page, s.log, a.h);
  await s.page.goto(`${APP}${path}`);
  return { ...s, ...a };
}
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
const checkoutBtn = (page) => page.getByRole('button', { name: 'Checkout', exact: true });

try {
  // 1. J10: a non-Rx counter sale completes with no prescription, well under 30 s.
  {
    const s = await open('/checkout/otc'); const { page } = s;
    const t0 = Date.now();
    await page.getByRole('button', { name: /Plain Item/ }).click();
    await checkoutBtn(page).click();
    await page.getByText('INV-0001').waitFor();
    const ms = Date.now() - t0;
    check('J10: non-Rx OTC sale completes < 30 s', ms < 30_000, `${ms} ms`);
    check('J10: non-Rx line sent without prescriptionItemId', !('prescriptionItemId' in s.st.posts[0].lineItems[0]), JSON.stringify(s.st.posts[0].lineItems[0]));
    check('J10: no console/page errors', noise(s.log).length === 0, noise(s.log).join('|'));
    dumpLog('j10', s.log); await s.browser.close();
  }
  // 2. Rx item: red chip, picker opens on add, Checkout off until linked; linking sends prescriptionItemId.
  {
    const s = await open('/checkout/otc'); const { page } = s;
    const rxButton = page.getByRole('button', { name: /Fixture Drug/ });
    await rxButton.waitFor();
    check('Rx chip on the Rx-only item', (await rxButton.innerText()).includes('Rx'));
    check('no Rx chip on the non-Rx item', !(await page.getByRole('button', { name: /Plain Item/ }).innerText()).match(/\bRx\b/));
    await rxButton.click();
    await page.getByRole('dialog', { name: /Link prescription/ }).waitFor();
    check('adding an Rx item opens the Link prescription picker', true);
    check('blocked: Checkout disabled while the Rx line is unlinked', await checkoutBtn(page).isDisabled());
    check('blocked: rule shown', (await page.getByTestId('rx-wait').innerText()).includes('signed prescription'));
    await page.getByLabel('Patient phone').fill('12');
    await page.getByRole('button', { name: 'Search' }).click();
    check('boundary: < 6 digits refused client-side, nothing sent', (await page.getByText(/at least 6 digits/).count()) === 1 && s.st.searches.length === 0);
    await page.getByLabel('Patient phone').fill('98765 43210');
    await page.getByRole('button', { name: 'Search' }).click();
    await page.getByRole('button', { name: /Fixture Drug.*10 left/ }).click();
    await page.getByTestId('rx-linked-PH-RX').waitFor();
    check('search sent in the body (no phone in URL)', s.st.searches[0]?.phone === '98765 43210' && !s.log.requests.some((r) => /98765/.test(r.path)));
    check('linked: Checkout enabled', !(await checkoutBtn(page).isDisabled()));
    await checkoutBtn(page).click();
    await page.getByText('INV-0001').waitFor();
    check('linked: POST carries prescriptionItemId', s.st.posts[0].lineItems[0].prescriptionItemId === PI, JSON.stringify(s.st.posts[0].lineItems[0]));
    check('Rx flow: no console/page errors', noise(s.log).length === 0, noise(s.log).join('|'));
    dumpLog('rx', s.log); await s.browser.close();
  }
  // 3. Empty and error states of the picker; server refusal names the rule.
  {
    const s = await open('/checkout/otc', PERMS, { noLines: true }); const { page } = s;
    await page.getByRole('button', { name: /Fixture Drug/ }).click();
    await page.getByLabel('Patient phone').fill('9876543210');
    await page.getByRole('button', { name: 'Search' }).click();
    await page.getByText(/No signed prescription with quantity left/).waitFor();
    check('picker empty state shown', true);
    await s.browser.close();
    const e = await open('/checkout/otc', PERMS, { search500: true });
    await e.page.getByRole('button', { name: /Fixture Drug/ }).click();
    await e.page.getByLabel('Patient phone').fill('9876543210');
    await e.page.getByRole('button', { name: 'Search' }).click();
    const err = e.page.getByRole('dialog').getByRole('alert');
    await err.waitFor();
    check('picker error state shown (no stack trace)', !(await err.innerText()).match(/Exception|at com\./), await err.innerText());
    await e.browser.close();
    const x = await open('/checkout/otc', PERMS, { exhausted: true });
    await x.page.getByRole('button', { name: /Fixture Drug/ }).click();
    await x.page.getByLabel('Patient phone').fill('9876543210');
    await x.page.getByRole('button', { name: 'Search' }).click();
    await x.page.getByRole('button', { name: /Fixture Drug.*10 left/ }).click();
    await checkoutBtn(x.page).click();
    await x.page.getByText(/no quantity left/).waitFor();
    check('422 rx-quantity-exhausted: rule named, still on the sale', (await checkoutBtn(x.page).count()) === 1);
    await x.browser.close();
  }
  // 4. Permission absent: no billing:create -> no Checkout and no picker controls.
  {
    const s = await open('/checkout/otc', ['billing:view']); const { page } = s;
    await page.waitForLoadState('networkidle');
    check('no billing:create: Checkout absent', (await checkoutBtn(page).count()) === 0);
    check('no billing:create: Link prescription absent', (await page.getByRole('button', { name: 'Link prescription' }).count()) === 0);
    await s.browser.close();
  }
  // 5. 390 px with the picker open: no horizontal page scroll.
  {
    const s = await open('/checkout/otc', PERMS, {}, { width: 390, height: 844 }); const { page } = s;
    await page.getByRole('button', { name: /Fixture Drug/ }).click();
    await page.getByLabel('Patient phone').fill('9876543210');
    await page.getByRole('button', { name: 'Search' }).click();
    await page.getByRole('button', { name: /Fixture Drug.*10 left/ }).waitFor();
    check('390px: no horizontal page scroll', await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
    await s.browser.close();
  }
  // 6. Visit checkout: an Rx item is pre-linked from the visit's signed Rx; an unmatched one waits for a choice; no unlink option.
  {
    const s = await open(`/checkout/${Q}`); const { page } = s;
    await page.getByText('Asha Verma').first().waitFor();
    await page.getByRole('button', { name: /Fixture Drug/ }).click();
    const sel = page.getByLabel('Prescription line for Fixture Drug');
    check('visit: Rx line pre-linked to the signed line', (await sel.inputValue()) === PI);
    check('visit: no unlink (empty) option once linked', (await sel.locator('option[value=""]').count()) === 0);
    await page.getByRole('button', { name: /Other Rx/ }).click();
    const sel2 = page.getByLabel('Prescription line for Other Rx');
    check('visit: unmatched Rx line waits for a choice', (await sel2.inputValue()) === '' && await checkoutBtn(page).isDisabled());
    await sel2.selectOption(PI);
    check('visit: Checkout enabled once every Rx line is linked', !(await checkoutBtn(page).isDisabled()));
    await checkoutBtn(page).click();
    await page.getByText('INV-0001').waitFor();
    check('visit: both Rx lines sent with prescriptionItemId', s.st.posts[0].lineItems.every((l) => l.prescriptionItemId === PI), JSON.stringify(s.st.posts[0].lineItems));
    check('visit: no console/page errors', noise(s.log).length === 0, noise(s.log).join('|'));
    dumpLog('visit', s.log); await s.browser.close();
  }
} catch (e) {
  check('script ran to completion', false, String(e).slice(0, 300));
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
