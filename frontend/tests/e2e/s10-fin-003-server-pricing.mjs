// FIN-003: checkout sends codes only; override/discount controls absent without billing.discount; reason picker; server price shown.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3408 node tests/e2e/s10-fin-003-server-pricing.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const Q = '33333333-3333-4333-8333-333333333333';
const BASE = ['billing:view', 'billing:create', 'queue:view', 'queue:edit'];
const CHARGES = [{ id: 'c1', code: 'CONS', name: 'Consultation', category: 'Consultation', baseAmount: 500, followUpAmount: null, taxRatePercent: 0, stockQty: null }];
const ctx = { queueEntryId: Q, patientName: 'Asha Verma', doctorName: 'Dr Rao', visitType: 'Walk-in', followUpEligible: false, currency: 'INR',
  charges: CHARGES, pendingProcedures: [], prescribedItems: [], departmentId: 'd1', queueStatus: 'checkout_pending', suggestedLines: [] };

function api(o = {}) {
  const st = { posts: [] };
  const h = [
    ['GET', /^\/billing\/checkout\/[^/]+$/, o.context ?? { body: ctx }],
    ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
    ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, { status: 404, body: { title: 'Not found', detail: 'x' } }],
    ['POST', /^\/billing\/checkout\/[^/]+$/, (r, req) => {
      const b = JSON.parse(req.postData()); st.posts.push(b);
      if (o.post422) return r.fulfill({ status: 422, contentType: 'application/problem+json', body: JSON.stringify({ type: 'unknown-charge', title: 'Unknown charge', detail: "'CONS' is not an active charge in the price list." }) });
      const l = b.lineItems[0]; const ov = l.override;
      const unit = ov ? ov.unitPrice : 500; const disc = (b.discounts ?? []).reduce((s, d) => s + d.value, 0);
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 'inv-1', invoiceNumber: 'INV-0001', patientName: 'Asha Verma', doctorName: 'Dr Rao', currency: 'INR',
        subtotal: unit * l.quantity, discount: disc, tax: 0, roundOff: 0, total: unit * l.quantity - disc, paid: 0, balanceDue: unit * l.quantity - disc, status: 'unpaid', payments: [],
        lineItems: [{ chargeCode: 'CONS', chargeName: 'Consultation', category: 'Consultation', quantity: l.quantity, unitPrice: unit, listUnitPrice: 500, tier: 'normal', overrideReason: ov ? ov.reasonCode : null, taxRatePercent: 0, lineTotal: unit * l.quantity }] }) });
    }],
  ];
  return { st, h };
}
async function open(perms, o = {}, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms, { tenantRegion: 'IN' });
  const a = api(o); await mockApi(s.page, s.log, a.h);
  await s.page.goto(`${APP}/checkout/${Q}`);
  return { ...s, ...a };
}
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];

try {
  // 1. J9: reception (no billing.discount) -> codes only, no override/discount controls, price shown comes from the server.
  {
    const s = await open(BASE); const { page } = s;
    await page.getByText('Asha Verma').waitFor();
    await page.getByRole('button', { name: /Consultation.*500/ }).click();
    check('reception: Override price control absent', (await page.getByRole('button', { name: 'Override price' }).count()) === 0);
    check('reception: discount input and reason absent', (await page.getByLabel('Discount amount').count()) === 0 && (await page.getByLabel('Discount reason').count()) === 0);
    await page.getByRole('button', { name: 'Checkout', exact: true }).click();
    await page.getByText('INV-0001').waitFor();
    const line = s.st.posts[0].lineItems[0];
    check('reception: line sent as chargeCode + quantity only', JSON.stringify(Object.keys(line).sort()) === '["chargeCode","quantity"]', JSON.stringify(line));
    check('reception: no discounts / discount key sent', !('discounts' in s.st.posts[0]) && !('discount' in s.st.posts[0]));
    check('reception: invoice shows the server price 500', (await page.locator('main').innerText()).includes('500'));
    check('reception: no console/page errors', noise(s.log).length === 0, noise(s.log).join('|'));
    dumpLog('reception', s.log); await s.browser.close();
  }
  // 2. billing.discount: override needs a reason, is sent as override{}, invoice shows list price + reason.
  {
    const s = await open([...BASE, 'billing.discount']); const { page } = s;
    await page.getByText('Asha Verma').waitFor();
    await page.getByRole('button', { name: /Consultation.*500/ }).click();
    await page.getByRole('button', { name: 'Override price' }).click();
    await page.getByLabel('Override unit price').fill('300');
    check('override: Apply disabled until a reason is picked', await page.getByRole('button', { name: 'Apply', exact: true }).isDisabled());
    await page.getByLabel('Override reason').selectOption('goodwill');
    await page.getByRole('button', { name: 'Apply', exact: true }).click();
    check('override: row shows 300 and the reason', (await page.locator('main').innerText()).includes('Override: Goodwill'));
    // discount without reason is refused client-side
    await page.getByLabel('Discount amount').fill('50');
    await page.getByRole('button', { name: 'Checkout', exact: true }).click();
    await page.getByText('Pick a reason for the discount.').waitFor();
    check('discount: no reason -> nothing sent', s.st.posts.length === 0);
    await page.getByLabel('Discount reason').selectOption('staff');
    await page.getByRole('button', { name: 'Checkout', exact: true }).click();
    await page.getByText('INV-0001').waitFor();
    const b = s.st.posts[0];
    check('override: sent as override{unitPrice,reasonCode}', JSON.stringify(b.lineItems[0].override) === '{"unitPrice":300,"reasonCode":"goodwill"}', JSON.stringify(b.lineItems[0]));
    check('discount: sent as discounts[] row with reason', JSON.stringify(b.discounts) === '[{"kind":"amount","value":50,"reasonCode":"staff"}]', JSON.stringify(b.discounts));
    const txt = await page.locator('main').innerText();
    check('invoice shows list price 500 and the reason', /List .*500/.test(txt) && txt.includes('Goodwill'));
    check('override: no console/page errors', noise(s.log).length === 0, noise(s.log).join('|'));
    await s.browser.close();
  }
  // 3. Server 422 is shown to the user.
  {
    const s = await open(BASE, { post422: true }); const { page } = s;
    await page.getByText('Asha Verma').waitFor();
    await page.getByRole('button', { name: /Consultation.*500/ }).click();
    await page.getByRole('button', { name: 'Checkout', exact: true }).click();
    await page.getByText(/not an active charge in the price list/).waitFor();
    check('422: server detail shown, stays on the picker', (await page.getByRole('button', { name: 'Checkout', exact: true }).count()) === 1);
    await s.browser.close();
  }
  // 4. Empty + error states.
  {
    const s = await open(BASE); await s.page.getByText('Asha Verma').waitFor();
    check('empty: hint shown, Checkout disabled', (await s.page.getByText('Pick a charge from the list').count()) === 1 && await s.page.getByRole('button', { name: 'Checkout', exact: true }).isDisabled());
    await s.browser.close();
    const e = await open(BASE, { context: { status: 500, body: { title: 'Error', detail: 'x' } } });
    await e.page.getByText(/Couldn't load checkout details/).waitFor();
    check('error: context 500 message shown', true);
    await e.browser.close();
  }
  // 5. 390px: no horizontal scroll, with the override editor open.
  {
    const s = await open([...BASE, 'billing.discount'], {}, { width: 390, height: 844 }); const { page } = s;
    await page.getByText('Asha Verma').waitFor();
    await page.getByRole('button', { name: /Consultation.*500/ }).click();
    await page.getByRole('button', { name: 'Override price' }).click();
    check('390px: no horizontal page scroll', await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
    await s.browser.close();
  }
} catch (e) {
  check('script ran to completion', false, String(e).slice(0, 300));
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
