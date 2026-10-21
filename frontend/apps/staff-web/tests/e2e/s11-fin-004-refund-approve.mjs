// S11 FIN-004: approve dialog shows request-time vs current amount, sends expectedAmount, handles 409 amount-changed, reject path.
// NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3509 node s11-fin-004-refund-approve.mjs
import { launch, seedClinic, mockApi, check, results, sleep, APP, CORS } from './qa-lib.mjs';

const refund = { id: 'r1', instanceId: 'i1', patientName: 'Refund Patient', packageName: 'Pkg A', reason: 'Relocated', usedListValue: 0,
  refundAmount: 2700, amountOwed: 0, status: 'pending', creditNoteNumber: null, createdAt: '2026-10-18T08:00:00Z', recomputedAmount: null };
const json = (route, status, body) => route.fulfill({ headers: CORS, status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload/.test(x))];

// opts: refunds list, preview amount, approve(route, body) handler, reject handler
function handlers(o) {
  return [
    ['GET', /^\/packages\/refunds/, (r) => json(r, 200, o.refunds ?? [refund])],
    ['GET', /^\/packages\/instances\/i1\/refund-preview/, (r) => o.previewFail ? json(r, 500, { title: 'x', detail: 'Boom' })
      : json(r, 200, { paid: 2700, usedListValue: 2700 - o.preview, refundAmount: o.preview, amountOwed: 0, items: [] })],
    ['POST', /^\/packages\/refunds\/r1\/approve/, (r, req) => o.approve(r, JSON.parse(req.postData() || '{}'))],
    ['POST', /^\/packages\/refunds\/r1\/reject/, (r, req) => o.reject ? o.reject(r, JSON.parse(req.postData() || '{}')) : json(r, 200, { ...refund, status: 'rejected' })],
    ['GET', /^\/packages\/liability/, { body: {} }], ['GET', /^\/packages\/settings/, { body: { priceFloorPercent: 0 } }],
    ['GET', /^\/packages\/expiring-soon/, { body: [] }], ['GET', /^\/packages\/instances/, { body: [] }],
    ['GET', /^\/packages(\?|$)/, { body: [] }], ['GET', /./, { body: {} }],
  ];
}
async function open(perms, o, viewport) {
  const s = await launch({ viewport }); await seedClinic(s.page, perms); await mockApi(s.page, s.log, handlers(o));
  await s.page.goto(APP + '/packages', { waitUntil: 'networkidle' });
  await s.page.getByRole('button', { name: 'Refunds & adjustments' }).click();
  return s;
}
const APPROVER = ['packages:view', 'packages:edit', 'packages:approve'];
const text = (s, id) => s.page.getByTestId(id).innerText();
let s;
try {
  // 1 happy path, amount unchanged: shows both amounts, sends expectedAmount
  let sent = null;
  s = await open(APPROVER, { preview: 2700, approve: (r, b) => { sent = b; return json(r, 200, { ...refund, status: 'approved', creditNoteNumber: 'CN-000001', recomputedAmount: 2700 }); } }, { width: 390, height: 800 });
  await s.page.getByRole('button', { name: 'Review' }).click();
  await s.page.getByRole('region', { name: 'Review refund' }).waitFor({ timeout: 5000 });
  check('1a dialog shows amount at request', /2,?700/.test(await text(s, 'refund-requested')));
  check('1b dialog shows amount now', /2,?700/.test(await text(s, 'refund-current')));
  check('1c no "amount changed" warning when unchanged', await s.page.getByRole('alert').filter({ hasText: 'amount changed' }).count() === 0);
  const approveBtn = s.page.getByRole('button', { name: /^Approve/ });
  check('1d approve button >= 44px tall', (await approveBtn.boundingBox()).height >= 44);
  check('1e no horizontal scroll at 390px', await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await approveBtn.click(); await sleep(500);
  check('1f approve sent expectedAmount = current amount', sent && sent.expectedAmount === 2700, JSON.stringify(sent));
  check('1g dialog closed after success', await s.page.getByRole('region', { name: 'Review refund' }).count() === 0);
  check('1h zero console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  // 2 changed between request and now: preview already differs -> warning + new amount sent
  sent = null;
  s = await open(APPROVER, { preview: 1700, approve: (r, b) => { sent = b; return json(r, 200, { ...refund, status: 'approved', creditNoteNumber: 'CN-000002', recomputedAmount: 1700 }); } });
  await s.page.getByRole('button', { name: 'Review' }).click();
  await s.page.getByRole('region', { name: 'Review refund' }).waitFor({ timeout: 5000 });
  check('2a old vs new shown', /2,?700/.test(await text(s, 'refund-requested')) && /1,?700/.test(await text(s, 'refund-current')));
  check('2b changed-amount warning shown', await s.page.getByRole('alert').filter({ hasText: 'amount changed' }).count() === 1);
  await s.page.getByRole('button', { name: /^Approve/ }).click(); await sleep(500);
  check('2c approve sends the new amount', sent && sent.expectedAmount === 1700, JSON.stringify(sent));
  await s.browser.close();

  // 3 409 amount-changed at approve time: dialog refreshes to the server's current amount, stays open, second click sends it
  const bodies = [];
  s = await open(APPROVER, { preview: 2700, approve: (r, b) => { bodies.push(b); return bodies.length === 1
    ? json(r, 409, { type: 'https://api.nabd.health/problems/amount-changed', title: 'Refund amount changed', detail: 'Sessions were used.', requestedAmount: 2700, currentAmount: 1700, currentAmountOwed: 0 })
    : json(r, 200, { ...refund, status: 'approved', creditNoteNumber: 'CN-000003', recomputedAmount: 1700 }); } });
  await s.page.getByRole('button', { name: 'Review' }).click();
  await s.page.getByRole('button', { name: /^Approve/ }).click(); await sleep(500);
  check('3a dialog still open after 409', await s.page.getByRole('region', { name: 'Review refund' }).count() === 1);
  check('3b current amount refreshed to 1,700', /1,?700/.test(await text(s, 'refund-current')));
  check('3c changed warning shown', await s.page.getByRole('alert').filter({ hasText: 'amount changed' }).count() === 1);
  await s.page.getByRole('button', { name: /^Approve/ }).click(); await sleep(500);
  check('3d second approve sends refreshed amount', bodies[1]?.expectedAmount === 1700, JSON.stringify(bodies));
  check('3e zero console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  // 4 reject path: reason required, then sent
  let rejected = null;
  s = await open(APPROVER, { preview: 2700, approve: (r) => json(r, 500, {}), reject: (r, b) => { rejected = b; return json(r, 200, { ...refund, status: 'rejected' }); } });
  await s.page.getByRole('button', { name: 'Review' }).click();
  await s.page.getByRole('button', { name: 'Reject' }).click(); await sleep(300);
  check('4a reject without reason refused client-side', rejected === null && /reason to reject/i.test(await s.page.locator('body').innerText()));
  await s.page.getByLabel('Reason to reject').fill('Patient stays');
  await s.page.getByRole('button', { name: 'Reject' }).click(); await sleep(500);
  check('4b reject sent with reason', rejected?.reason === 'Patient stays', JSON.stringify(rejected));
  await s.browser.close();

  // 5 already decided (409) surfaces the server detail
  s = await open(APPROVER, { preview: 2700, approve: (r) => json(r, 409, { type: 'https://api.nabd.health/problems/already-decided', title: 'Already decided', detail: 'This refund has already been approved or rejected.' }) });
  await s.page.getByRole('button', { name: 'Review' }).click();
  await s.page.getByRole('button', { name: /^Approve/ }).click(); await sleep(500);
  check('5 already-decided detail shown', /already been approved or rejected/.test(await s.page.locator('body').innerText()));
  await s.browser.close();

  // 6 preview error: no dialog, visible error
  s = await open(APPROVER, { preview: 0, previewFail: true, approve: (r) => json(r, 500, {}) });
  await s.page.getByRole('button', { name: 'Review' }).click(); await sleep(500);
  check('6 preview failure shows error, no dialog', await s.page.getByRole('region', { name: 'Review refund' }).count() === 0
    && /could not load the current refund amount/i.test(await s.page.locator('body').innerText()));
  await s.browser.close();

  // 7 permission absent: no Review button for a desk role
  s = await open(['packages:view', 'packages:edit'], { preview: 2700, approve: (r) => json(r, 500, {}) });
  check('7 no Review/Approve/Reject without packages:approve', await s.page.getByRole('button', { name: /Review|Approve|Reject/ }).count() === 0);
  await s.browser.close();

  // 8 empty list
  s = await open(APPROVER, { refunds: [], preview: 0, approve: (r) => json(r, 500, {}) });
  check('8 empty state', /No refund requests\./.test(await s.page.locator('body').innerText()));
  await s.browser.close();
} catch (e) { check('run', false, String(e).split('\n')[0]); console.log('  body:', JSON.stringify((await s?.page.locator('body').innerText().catch(() => '')).slice(0, 600))); await s?.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok); console.log(`${results.length - bad.length}/${results.length} passed`); process.exit(bad.length ? 1 : 0);
