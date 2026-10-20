// Independent QA (S11 PHL-001): Rx-only items at the counter and at visit checkout (mocked API).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3611 node tests/e2e/s11qa-phl-001.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const Q = '55555555-5555-4555-8555-555555555555';
const PI = '66666666-6666-4666-8666-666666666666';
const PERMS = ['billing:view', 'billing:create', 'billing:edit', 'queue:view', 'queue:edit'];
const CHARGES = [
  { id: 'c1', code: 'QA-PLAIN', name: 'QA Plain', category: 'Pharmacy', baseAmount: 15, followUpAmount: null, taxRatePercent: 0, stockQty: 9, isRx: false },
  { id: 'c2', code: 'QA-RX', name: 'QA Rxonly', category: 'Pharmacy', baseAmount: 60, followUpAmount: null, taxRatePercent: 0, stockQty: 9, isRx: true },
];
const LINE = { prescriptionItemId: PI, patientId: 'p9', patientName: 'Qa Patient', drugName: 'QA Rxonly', dosage: '1 tab', quantity: 5, remainingQty: 5, signedAt: '2026-10-19T08:00:00Z' };
const inv = (lines) => ({ id: 'inv-9', invoiceNumber: 'INV-0099', patientName: 'Walk-in customer', currency: 'INR', subtotal: 15, discount: 0, tax: 0, roundOff: 0,
  total: 15, paid: 0, balanceDue: 15, status: 'unpaid', payments: [],
  lineItems: lines.map((l) => ({ chargeCode: l.chargeCode, chargeName: l.chargeCode, category: 'Pharmacy', quantity: l.quantity, unitPrice: 15, taxRatePercent: 0, lineTotal: 15 * l.quantity })) });
const problem = (r, type, detail) => r.fulfill({ status: 422, contentType: 'application/problem+json',
  body: JSON.stringify({ type: `https://nabd.health/problems/${type}`, title: 'Refused', detail, chargeCode: 'QA-RX' }) });

function handlers(st, o = {}) {
  return [
    ['GET', /^\/billing\/otc-charges/, { body: { currency: 'INR', charges: CHARGES } }],
    ['POST', /^\/pharmacy\/rx-lines\/search/, { body: [LINE] }],
    ['POST', /^\/billing\/otc-checkout/, (r, req) => {
      const b = JSON.parse(req.postData()); st.posts.push(b);
      if (o.otc422) return problem(r, o.otc422, 'The linked prescription line for QA-RX was refused.');
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(inv(b.lineItems)) });
    }],
    ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, { status: 404, body: { title: 'Not found', detail: 'x' } }],
    ['GET', /^\/billing\/checkout\/[^/]+$/, { body: { queueEntryId: Q, patientName: 'Qa Patient', doctorName: 'Dr Qa', visitType: 'Walk-in', followUpEligible: false,
      currency: 'INR', charges: CHARGES, pendingProcedures: [], departmentId: 'd1', queueStatus: 'checkout_pending', suggestedLines: [],
      prescribedItems: o.noRx ? [] : [{ prescriptionItemId: PI, drugName: 'QA Rxonly', dosage: '1 tab', frequency: 'OD', duration: null, instructions: null, quantity: 5, remainingQty: 5 }] } }],
    ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
    ['POST', /^\/billing\/checkout\/[^/]+$/, (r, req) => { st.posts.push(JSON.parse(req.postData())); return problem(r, 'rx-quantity-exhausted', 'x'); }],
  ];
}
async function open(path, o = {}, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, PERMS, { tenantRegion: 'IN' });
  const st = { posts: [] };
  await mockApi(s.page, s.log, handlers(st, o));
  await s.page.goto(`${APP}${path}`);
  return { ...s, st };
}
// errors only: FEF-011 logs an intended [warning] clinic-timezone-fallback when /setup/profile is not mocked
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => x.startsWith('[error]') && !/Failed to load resource|status of 4\d\d/.test(x))];
const checkout = (p) => p.getByRole('button', { name: 'Checkout', exact: true });
const errorsSeen = [];

try {
  // 1. Blocked state: Rx chip, picker on add; closing the picker without linking keeps the sale blocked and nothing is sent.
  {
    const s = await open('/checkout/otc'); const { page } = s;
    const rx = page.getByRole('button', { name: /QA Rxonly/ });
    await rx.waitFor();
    check('1a Rx-only item shows the Rx chip', /\bRx\b/.test(await rx.innerText()));
    await page.getByRole('button', { name: /QA Plain/ }).click();
    await rx.click();
    const dlg = page.getByRole('dialog', { name: /Link prescription/ });
    await dlg.waitFor();
    await page.keyboard.press('Escape');
    await dlg.waitFor({ state: 'hidden', timeout: 3000 }).catch(() => {});
    if (await dlg.count()) await dlg.getByRole('button', { name: /Cancel|Close/ }).first().click().catch(() => {});
    check('1b unlinked Rx line: Checkout disabled (whole sale blocked)', await checkout(page).isDisabled());
    await checkout(page).click({ force: true, timeout: 1000 }).catch(() => {});
    check('1c nothing posted while blocked', s.st.posts.length === 0);
    // remove the Rx line: the plain item alone may be sold
    const rows = page.getByRole('row').filter({ hasText: 'QA Rxonly' });
    await rows.getByRole('button', { name: 'Remove' }).click();
    check('1d Rx line removed: Checkout enabled for the non-Rx sale', !(await checkout(page).isDisabled()));
    await checkout(page).click();
    await page.getByText('INV-0099').waitFor({ timeout: 5000 });
    check('1e non-Rx sale sent without any Rx line', s.st.posts[0]?.lineItems.length === 1 && s.st.posts[0].lineItems[0].chargeCode === 'QA-PLAIN', JSON.stringify(s.st.posts[0]));
    errorsSeen.push(...noise(s.log)); dumpLog('1', s.log); await s.browser.close();
  }
  // 2. Server refusal after linking names the rule, never the stack trace in the problem detail.
  for (const [type, words] of [['rx-patient-mismatch', /different patient/], ['rx-not-signed', /draft|sign/]]) {
    const s = await open('/checkout/otc', { otc422: type }); const { page } = s;
    await page.getByRole('button', { name: /QA Rxonly/ }).click();
    await page.getByLabel('Patient phone').fill('9876500000');
    await page.getByRole('button', { name: 'Search' }).click();
    await page.getByRole('button', { name: /QA Rxonly.*5 left/ }).click();
    await checkout(page).click();
    await page.waitForTimeout(800);
    const body = await page.locator('body').innerText();
    check(`2 ${type}: rule named on screen`, words.test(body));
    check(`2 ${type}: no stack trace shown`, !/Exception|RxGate\.java|at com\./.test(body));
    check(`2 ${type}: no invoice shown`, !/INV-0099/.test(body));
    errorsSeen.push(...noise(s.log)); await s.browser.close();
  }
  // 3. Visit checkout without a signed Rx line for the item: no way to bill it (no empty/unlink option chosen silently).
  {
    const s = await open(`/checkout/${Q}`, { noRx: true }); const { page } = s;
    await page.getByText('Qa Patient').first().waitFor();
    await page.getByRole('button', { name: /QA Rxonly/ }).click();
    await page.waitForTimeout(300);
    check('3 visit, no signed Rx: Checkout disabled for the Rx line', await checkout(page).isDisabled());
    errorsSeen.push(...noise(s.log)); await s.browser.close();
  }
  // 4. 390px with an Rx line on the bill: no horizontal scroll.
  {
    const s = await open('/checkout/otc', {}, { width: 390, height: 844 }); const { page } = s;
    await page.getByRole('button', { name: /QA Rxonly/ }).click();
    await page.getByRole('dialog').waitFor();
    check('4 390px: no horizontal page scroll', await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
    errorsSeen.push(...noise(s.log)); await s.browser.close();
  }
  check('5 zero console/page errors across runs', errorsSeen.length === 0, errorsSeen.slice(0, 3).join(' | '));
} catch (e) {
  check('script ran to completion', false, String(e).slice(0, 300));
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
