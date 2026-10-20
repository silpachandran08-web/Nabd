// FIN-001: payment UI sends an Idempotency-Key, shows the server's overpayment message with the balance, refreshes balance from the server.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3407 node tests/e2e/s10-fin-001-payments.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const Q = '33333333-3333-4333-8333-333333333333';
const PERMS = ['billing:view', 'billing:create', 'billing:edit', 'queue:view', 'queue:edit'];
const ctx = { queueEntryId: Q, patientName: 'Asha Verma', doctorName: 'Dr Rao', visitType: 'Walk-in', followUpEligible: false, currency: 'INR',
  charges: [], pendingProcedures: [], prescribedItems: [], departmentId: 'dept-1', queueStatus: 'checkout_pending' };
const mkInv = (paid) => ({ id: 'inv-1', invoiceNumber: 'INV-0001', patientName: 'Asha Verma', doctorName: 'Dr Rao', currency: 'INR', subtotal: 1400, discount: 0, tax: 0,
  roundOff: 0, total: 1400, paid, balanceDue: 1400 - paid, status: paid >= 1400 ? 'paid' : 'partial', lineItems: [], payments: paid ? [{ id: 'p0', method: 'cash', amount: paid, recordedAt: '2026-10-18T10:00:00Z' }] : [] });
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];

async function open(perms, viewport, over = {}) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms, { tenantRegion: 'IN' });
  const st = { paid: over.paid ?? 200, posts: [] };
  await mockApi(s.page, s.log, [
    ['GET', /^\/billing\/checkout\/[^/]+$/, { body: ctx }],
    ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
    ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mkInv(st.paid)) })],
    ['POST', /^\/billing\/invoices\/inv-1\/payments$/, (r, req) => {
      const b = JSON.parse(req.postData());
      st.posts.push({ b, key: req.headers()['idempotency-key'] });
      if (over.overpay) { st.paid = 1300; return r.fulfill({ status: 422, contentType: 'application/problem+json', body: JSON.stringify({ type: 'overpayment', title: 'Payment is more than the balance', detail: 'The balance due is 100.00; a payment can\'t be more than that.', balance: 100 }) }); }
      st.paid += b.amount;
      return r.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(mkInv(st.paid)) });
    }],
  ]);
  await s.page.goto(`${APP}/checkout/${Q}`);
  return { ...s, st };
}

try {
  { // happy: interim-paid invoice grew to 1400, balance 1200 collectable, key sent
    const { page, st, log, browser } = await open(PERMS);
    await page.getByLabel('Amount 1').fill('1200');
    await page.getByRole('button', { name: /^Collect|Record/ }).first().click();
    await page.waitForFunction(() => document.querySelector('main')?.innerText.includes('Paid') || true);
    await page.waitForTimeout(500);
    check('happy: one payment of 1200 posted', st.posts.length === 1 && st.posts[0].b.amount === 1200, JSON.stringify(st.posts));
    check('happy: Idempotency-Key header present', typeof st.posts[0]?.key === 'string' && st.posts[0].key.length >= 8);
    check('happy: no console errors', noise(log).length === 0, JSON.stringify(noise(log)));
    dumpLog('happy', log); await browser.close();
  }
  { // overpayment: server 422 detail shown inline, balance refreshed from the server
    const { page, st, log, browser } = await open(PERMS, undefined, { overpay: true });
    await page.getByLabel('Amount 1').fill('1200');
    await page.getByRole('button', { name: /^Collect|Record/ }).first().click();
    await page.getByText(/balance due is 100\.00/).waitFor({ timeout: 5000 });
    check('overpay: server message with balance shown inline', true);
    const refreshed = await page.waitForFunction(() => /Balance due\s*[^\d]*100(\.00)?\b/.test(document.querySelector('main').innerText.replace(/\s+/g, ' ')), null, { timeout: 5000 }).then(() => true, () => false);
    check('overpay: displayed balance refreshed to 100', refreshed);
    check('overpay: one POST only (no silent retry)', st.posts.length === 1);
    await browser.close();
  }
  { // permission absent: no tender UI without billing:edit
    const { page, browser } = await open(['billing:view', 'queue:view']);
    await page.waitForTimeout(800);
    check('no billing:edit: amount field absent', (await page.getByLabel('Amount 1').count()) === 0);
    await browser.close();
  }
  { // 390px: no horizontal scroll
    const { page, browser } = await open(PERMS, { width: 390, height: 800 });
    await page.getByLabel('Amount 1').waitFor();
    check('390: no horizontal scroll', await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
    await browser.close();
  }
} catch (e) { check('script ran without throwing', false, String(e)); }
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
