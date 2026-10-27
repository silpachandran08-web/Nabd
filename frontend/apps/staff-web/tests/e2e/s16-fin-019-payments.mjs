// FIN-019 E2E (mocked API): the Payments card. Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3816 node s16-fin-019-payments.mjs
import { launch, seedClinic, mockApi, check, dumpLog, APP } from './qa-lib.mjs';

let current = { connected: false, account: null }, posted = null, currentStatus = 200;
const handlers = () => [
  ['POST', /^\/auth\/mfa\/verify/, { body: { stepUpToken: 'su' } }],
  ['GET', /^\/billing\/gateway-accounts\/current/, (r) => r.fulfill({ status: currentStatus, contentType: currentStatus >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(currentStatus >= 400 ? { type: 'x', title: 'x', detail: 'Down' } : current) })],
  ['POST', /^\/billing\/gateway-accounts$/, (r, req) => { posted = { body: req.postData(), su: req.headers()['x-step-up-token'] }; current = { connected: true, account: { accountId: 'a1', provider: 'razorpay', status: 'connected', connectedAt: '2026-10-01T00:00:00Z', lastTestAt: null } }; return r.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(current.account) }); }],
  ['GET', /^\/settings\/integrations\/abdm/, { body: { enabled: false, hfrVerified: false, hfrId: null } }],
];
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
async function open(perms, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, handlers());
  await s.page.goto(`${APP}/setup?tab=integrations`, { waitUntil: 'networkidle' });
  return s;
}
let s;
try {
  s = await open(['setup:view', 'setup:edit', 'billing:view']);
  const p = s.page;
  await p.getByText('Not connected.').waitFor({ timeout: 8000 });
  check('1 empty: not connected, connect form shown', await p.getByRole('button', { name: 'Connect Razorpay' }).isDisabled());
  await p.getByLabel('Key id').fill('rzp_test_x'); await p.getByLabel('Key secret').fill('s'); await p.locator('#pay-code').fill('123456');
  await p.getByRole('button', { name: 'Connect Razorpay' }).click();
  await p.getByText('Razorpay is connected.').waitFor({ timeout: 5000 }).then(() => check('2 happy: connected, step-up header sent', posted?.su === 'su'), () => check('2 happy', false));
  check('3 secret never shown after connect', !(await p.locator('body').innerText()).includes('rzp_test_x') && await p.locator('input[type=password]').count() === 0);
  check('4 no console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  s = await open(['setup:view', 'billing:view']);
  check('5 permission-absent: no connect/disconnect controls', await s.page.getByRole('button', { name: /Connect Razorpay|Disconnect|Send test payment/ }).count() === 0);
  await s.browser.close();

  current = { connected: false, account: null }; currentStatus = 500;
  s = await open(['setup:view', 'setup:edit']);
  check('6 error state with retry', await s.page.getByRole('button', { name: 'Retry' }).count() >= 1);
  await s.browser.close(); currentStatus = 200;

  s = await open(['setup:view', 'setup:edit'], { width: 390, height: 800 });
  await s.page.getByText('Not connected.').waitFor({ timeout: 8000 });
  check('7 390px: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  await s.browser.close();
} finally { if (s) dumpLog('fin019', s.log); }
process.exit(0);
