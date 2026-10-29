// PHL-018: checkout pre-fills the visit's in-house Rx lines; a short line needs "Dispense anyway" + reason.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3820 node tests/e2e/s16-phl-018-rx-prefill.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const Q = '33333333-3333-4333-8333-333333333333';
const CH = (code, name) => ({ id: code, code, name, category: 'Pharmacy', baseAmount: 20, followUpAmount: null, taxRatePercent: 5, stockQty: null });
const ctx = { queueEntryId: Q, patientName: 'Asha Verma', doctorName: 'Dr Rao', visitType: 'Walk-in', followUpEligible: false, currency: 'INR',
  charges: [CH('PCM', 'Paracetamol'), CH('AMX', 'Amoxicillin')], pendingProcedures: [], prescribedItems: [], departmentId: 'd1', queueStatus: 'checkout_pending', suggestedLines: [] };
const PREFILL = { lines: [
  { chargeCode: 'PCM', chargeName: 'Paracetamol', qty: 2, prescriptionItemId: 'p1', onHand: 10 },
  { chargeCode: 'AMX', chargeName: 'Amoxicillin', qty: 5, prescriptionItemId: 'p2', onHand: 1 }] };
try {
  const s = await launch(); const { page } = s;
  await seedClinic(page, ['billing:view', 'billing:create', 'queue:view', 'queue:edit'], { tenantRegion: 'IN' });
  const posts = [];
  await mockApi(page, s.log, [
    ['GET', /^\/billing\/checkout\/[^/]+\/prefill$/, { body: PREFILL }],
    ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, { status: 404, body: { title: 'Not found', detail: 'x' } }],
    ['GET', /^\/billing\/checkout\/[^/]+$/, { body: ctx }],
    ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
    ['POST', /^\/billing\/checkout\/[^/]+$/, (r, req) => { posts.push(JSON.parse(req.postData())); return r.fulfill({ status: 422, contentType: 'application/problem+json', body: JSON.stringify({ title: 'stop', detail: 'stop' }) }); }],
  ]);
  await page.goto(`${APP}/checkout/${Q}`);
  await page.getByTestId('rx-prefill').waitFor();
  check('covered line pre-ticked', await page.getByLabel(/Paracetamol × 2/).isChecked());
  check('short line unticked and disabled', !(await page.getByLabel(/Amoxicillin × 5/).isChecked()) && await page.getByLabel(/Amoxicillin × 5/).isDisabled());
  await page.getByRole('button', { name: 'Dispense anyway…' }).click();
  check('reason too short keeps the confirm disabled', await page.getByRole('button', { name: 'Dispense anyway', exact: true }).isDisabled());
  await page.getByLabel(/Why is the shelf short/).fill('count is off');
  await page.getByRole('button', { name: 'Dispense anyway', exact: true }).click();
  check('short line now ticked-able with reason shown', (await page.getByText(/Dispensing anyway: count is off/).count()) === 1);
  await page.getByLabel(/Amoxicillin × 5/).check();
  await page.getByRole('button', { name: 'Checkout', exact: true }).click();
  await page.waitForTimeout(500);
  const lines = posts[0]?.lineItems ?? [];
  check('POST carries shortfallReason only on the short line', lines.find((l) => l.chargeCode === 'AMX')?.shortfallReason === 'count is off' && !lines.find((l) => l.chargeCode === 'PCM')?.shortfallReason, JSON.stringify(lines));
  check('no page errors', s.log.pageErrors.length === 0);
  dumpLog('rx', s.log); await s.browser.close();
} catch (e) { check('script ran to completion', false, String(e).slice(0, 300)); }
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
