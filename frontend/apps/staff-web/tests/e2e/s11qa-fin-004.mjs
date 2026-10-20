// Independent QA (S11 FIN-004 UI): approve dialog edge cases — 409 refresh to a zero amount, decimals sent exactly,
// same-user 403 surfaced, network failure keeps the dialog, double click does not double-submit.
// NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3613 node s11qa-fin-004.mjs
import { launch, seedClinic, mockApi, check, results, sleep, APP, CORS } from './qa-lib.mjs';

const refund = { id: 'r7', instanceId: 'i7', patientName: 'QA Refund', packageName: 'QA Pkg', reason: 'Relocated', usedListValue: 0,
  refundAmount: 1234.5, amountOwed: 0, status: 'pending', creditNoteNumber: null, createdAt: '2026-10-18T08:00:00Z', recomputedAmount: null };
const json = (route, status, body) => route.fulfill({ headers: CORS, status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|clinic-timezone-fallback|preloaded using link preload|ERR_FAILED|net::/.test(x))];

function handlers(o) {
  return [
    ['GET', /^\/packages\/refunds/, { body: [refund] }],
    ['GET', /^\/packages\/instances\/i7\/refund-preview/, { body: { paid: 1234.5, usedListValue: 1234.5 - o.preview, refundAmount: o.preview, amountOwed: 0, items: [] } }],
    ['POST', /^\/packages\/refunds\/r7\/approve/, (r, req) => o.approve(r, JSON.parse(req.postData() || '{}'))],
    ['GET', /^\/packages\/liability/, { body: {} }], ['GET', /^\/packages\/settings/, { body: { priceFloorPercent: 0 } }],
    ['GET', /^\/packages\/expiring-soon/, { body: [] }], ['GET', /^\/packages\/instances/, { body: [] }],
    ['GET', /^\/packages(\?|$)/, { body: [] }], ['GET', /./, { body: {} }],
  ];
}
const APPROVER = ['packages:view', 'packages:edit', 'packages:approve'];
async function open(o) {
  const s = await launch({ viewport: { width: 390, height: 800 } });
  await seedClinic(s.page, APPROVER);
  await mockApi(s.page, s.log, handlers(o));
  await s.page.goto(APP + '/packages', { waitUntil: 'networkidle' });
  await s.page.getByRole('button', { name: 'Refunds & adjustments' }).click();
  await s.page.getByRole('button', { name: 'Review' }).click();
  await s.page.getByRole('region', { name: 'Review refund' }).waitFor({ timeout: 5000 });
  return s;
}
const dialog = (s) => s.page.getByRole('region', { name: 'Review refund' });
const approves = (s) => s.log.requests.filter((r) => r.method === 'POST' && /\/approve$/.test(r.path));
let s;
try {
  // 1 decimals: the request-time amount 1234.5 is shown and sent exactly
  let sent = null;
  s = await open({ preview: 1234.5, approve: (r, b) => { sent = b; return json(r, 200, { ...refund, status: 'approved', creditNoteNumber: 'CN-000010', recomputedAmount: 1234.5 }); } });
  check('1a amount with decimals displayed', /1,?234\.50?/.test(await s.page.getByTestId('refund-current').innerText()), await s.page.getByTestId('refund-current').innerText());
  await s.page.getByRole('button', { name: /^Approve/ }).click(); await sleep(500);
  check('1b expectedAmount sent exactly 1234.5', sent?.expectedAmount === 1234.5, JSON.stringify(sent));
  await s.browser.close();

  // 2 409 amount-changed to 0 (every session used): dialog shows 0 and the second approve sends 0, not the stale amount
  const bodies = [];
  s = await open({ preview: 1234.5, approve: (r, b) => { bodies.push(b); return bodies.length === 1
    ? json(r, 409, { type: 'https://api.nabd.health/problems/amount-changed', title: 'Refund amount changed', detail: 'Sessions were used.', requestedAmount: 1234.5, currentAmount: 0, currentAmountOwed: 265.5 })
    : json(r, 200, { ...refund, status: 'approved', creditNoteNumber: 'CN-000011', recomputedAmount: 0 }); } });
  await s.page.getByRole('button', { name: /^Approve/ }).click(); await sleep(500);
  check('2a dialog stays open on 409', await dialog(s).count() === 1);
  check('2b current amount refreshed to 0', /(^|\D)0(\.00)?(\D|$)/.test(await s.page.getByTestId('refund-current').innerText()), await s.page.getByTestId('refund-current').innerText());
  check('2c amount-owed warning shown for 265.5', /265\.50?/.test(await dialog(s).innerText()));
  await s.page.getByRole('button', { name: /^Approve/ }).click(); await sleep(500);
  check('2d second approve sends expectedAmount 0', bodies[1] && bodies[1].expectedAmount === 0, JSON.stringify(bodies));
  await s.browser.close();

  // 3 maker approving own request: server 403 same-user detail is visible
  s = await open({ preview: 1234.5, approve: (r) => json(r, 403, { type: 'https://api.nabd.health/problems/same-user', title: 'Self-approval blocked', detail: 'A refund must be approved by someone other than whoever requested it.' }) });
  await s.page.getByRole('button', { name: /^Approve/ }).click(); await sleep(500);
  check('3 same-user refusal shown to the approver', /someone other than whoever requested it/.test(await s.page.locator('body').innerText()));
  await s.browser.close();

  // 4 network failure: dialog kept, no crash
  s = await open({ preview: 1234.5, approve: (r) => r.abort('failed') });
  await s.page.getByRole('button', { name: /^Approve/ }).click(); await sleep(800);
  check('4a dialog kept after a network failure', await dialog(s).count() === 1);
  check('4b no page errors', s.log.pageErrors.length === 0, s.log.pageErrors.join('|'));
  await s.browser.close();

  // 5 double click on Approve submits once
  s = await open({ preview: 1234.5, approve: async (r) => { await sleep(600); return json(r, 200, { ...refund, status: 'approved', creditNoteNumber: 'CN-000012', recomputedAmount: 1234.5 }); } });
  await s.page.getByRole('button', { name: /^Approve/ }).dblclick(); await sleep(1500);
  check('5 double click sends one approve request', approves(s).length === 1, `approve POSTs=${approves(s).length}`);
  check('5b zero console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();
  s = null;
} catch (e) { check('run', false, String(e).split('\n')[0]); }
finally { if (s) await s.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok); console.log(`${results.length - bad.length}/${results.length} passed`); process.exit(bad.length ? 1 : 0);
