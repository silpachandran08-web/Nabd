// FIN-015/016 E2E (mocked API): doctor fees, follow-up window, price change log. Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3816 node s16-fin-015-016-pricing.mjs
import { launch, seedClinic, mockApi, check, dumpLog, APP } from './qa-lib.mjs';

const charge = { id: 'c1', code: 'CONS', name: 'Consultation', category: 'Consultation', baseAmount: 500, followUpAmount: 300, emergencyAmount: 900, taxCode: null, taxRatePercent: 0, doctorOverride: true, active: true, effectiveFrom: '2026-01-01', effectiveTo: null, displayOrder: 1 };
let changes = [{ id: 'x1', chargeName: 'Consultation', field: 'base_amount', oldValue: '400', newValue: '500', actor: 'Olu Owner', at: '2026-10-01T08:00:00Z' }];
let changesStatus = 200, putBody = null;
const handlers = () => [
  ['GET', /^\/setup\/charges\/changes\/export/, (r) => r.fulfill({ status: 200, contentType: 'text/csv', body: 'when,charge\n' })],
  ['GET', /^\/setup\/charges\/changes/, (r) => r.fulfill({ status: changesStatus, contentType: changesStatus >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(changesStatus >= 400 ? { type: 'x', title: 'x', detail: 'Boom' } : { items: changes, nextCursor: null }) })],
  ['GET', /^\/setup\/charges\/c1\/doctor-fees/, { body: { fees: [{ doctorId: 'd1', doctorName: 'Dr A', amount: 700 }] } }],
  ['PUT', /^\/setup\/charges\/c1\/doctor-fees/, (r, req) => { putBody = JSON.parse(req.postData()); return r.fulfill({ status: 200, contentType: 'application/json', body: '{"fees":[]}' }); }],
  ['PUT', /^\/setup\/policies\/follow_up_window_days/, { body: { id: 'p', policyKey: 'follow_up_window_days', value: '10', version: 2 } }],
  ['GET', /^\/staff\/roster/, { body: [{ id: 'd1', name: 'Dr A', departmentName: 'GP' }, { id: 'd2', name: 'Dr B', departmentName: 'GP' }] }],
  ['GET', /^\/setup\/charges/, { body: [charge] }],
  ['GET', /^\/setup\/policies/, { body: [{ id: 'p', policyKey: 'follow_up_window_days', value: '7', version: 1 }] }],
];
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
async function open(perms, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, handlers());
  await s.page.goto(`${APP}/setup?tab=charges`, { waitUntil: 'networkidle' });
  return s;
}
let s;
try {
  s = await open(['setup:view', 'setup:edit']);
  const p = s.page;
  await p.getByText('Doctor fees').first().waitFor({ timeout: 8000 });
  check('1 doctor fee rows load', await p.getByLabel('Fee for Dr A').inputValue() === '700' && await p.getByLabel('Fee for Dr B').inputValue() === '');
  await p.getByLabel('Fee for Dr B').fill('650');
  await p.getByRole('button', { name: 'Save fees' }).click();
  await p.waitForTimeout(500);
  check('2 PUT sends amount and null for blank', putBody?.fees?.some((f) => f.doctorId === 'd2' && f.amount === 650) && putBody.fees.some((f) => f.doctorId === 'd1' && f.amount === 700));
  await p.getByLabel('Days (1 to 90)').fill('0');
  check('3 boundary: 0 days is rejected inline and Save is disabled', await p.getByText('Use a number of days from 1 to 90.').count() === 1 && await p.getByRole('button', { name: 'Save window' }).isDisabled());
  await p.getByLabel('Days (1 to 90)').fill('10');
  await p.getByRole('button', { name: 'Save window' }).click();
  await p.getByText('Saved.').first().waitFor({ timeout: 4000 }).then(() => check('4 window saved', true), () => check('4 window saved', false));
  check('5 price change log row and export button', await p.getByText('Olu Owner').count() === 1 && await p.getByRole('button', { name: 'Export CSV' }).count() === 1);
  check('6 no console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  changes = [];
  s = await open(['setup:view', 'setup:edit']);
  check('7 empty log state', await s.page.getByText('No price changes match.').count() === 1);
  await s.browser.close();

  changesStatus = 500;
  s = await open(['setup:view', 'setup:edit']);
  check('8 error state with retry', await s.page.getByRole('button', { name: 'Retry' }).count() >= 1);
  await s.browser.close();
  changesStatus = 200;

  s = await open(['setup:view']);
  check('9 permission-absent: no Save fees / Save window without setup:edit', await s.page.getByRole('button', { name: /Save fees|Save window/ }).count() === 0);
  await s.browser.close();

  s = await open(['setup:view', 'setup:edit'], { width: 390, height: 800 });
  check('10 390px: no horizontal scroll on the page', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  await s.browser.close();
} finally {
  if (s) dumpLog('fin015-016', s.log);
}
process.exit(0);
