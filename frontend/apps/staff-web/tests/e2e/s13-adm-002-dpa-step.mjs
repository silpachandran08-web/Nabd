// S13 ADM-002: the DPA step of the setup wizard (mocked API): blockers, OTP signing, PDF download, wrong code,
// permission-absent controls, load error, re-accept banner on the overview, 390px, zero console errors.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3703 node s13-adm-002-dpa-step.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const V = '2026-10-v1';
const html = '<h2>Data Processing Agreement</h2><p>Between QA Clinic (the data fiduciary) and Nabd (the data processor).</p><ul><li>Render: API hosting</li></ul>';
const demo = { version: V, marketCode: 'IN', html, accepted: null, dataGate: 'demo_only', blockers: ['DPA_REQUIRED', 'PLAN_REQUIRED'], reacceptDueBy: null };
const signedNoPlan = { ...demo, accepted: { version: V, at: '2026-10-21T10:00:00Z', by: 'Owner One', method: 'otp', acceptanceId: 'a1' }, blockers: ['PLAN_REQUIRED'] };
const liveOld = { ...demo, dataGate: 'live', blockers: [], accepted: { version: '2026-01-v0', at: '2026-02-01T10:00:00Z', by: 'Owner One', method: 'paper', acceptanceId: 'a0' }, reacceptDueBy: '2026-11-20T00:00:00Z' };
const done = ['welcome', 'profile', 'tax', 'doctors', 'departments', 'schedule', 'charges', 'pharmacy', 'whatsapp', 'go_live'].map((step) => ({ step, status: 'done', skippedAt: null, doneAt: null }));
const overview = { date: '2026-10-21', visits: { total: 0, completed: 0, inFlow: 0 }, collections: { collected: 0, invoices: 0, pendingInvoices: 0 }, checkout: { pending: 0, outstanding: 0 },
  packages: { soldToday: 0, sessionsOwed: 0 }, paymentSplit: [], dayClose: { unbilledConsultations: 0, pendingCheckouts: 0, closed: false }, activeStaff: [], staff: { active: 1, suspended: 0, invited: 0, roles: 1 } };
const problem = (type, detail, status) => ({ status, body: { type: `https://api.nabd.health/errors/${type}`, title: type, detail } });

const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload|ERR_FAILED|net::/.test(x))];

function handlers(state) {
  return [
    ['GET', /^\/setup\/checklist/, { body: done }],
    ['GET', /^\/setup\/dpa$/, (route) => route.fulfill({ status: state.getStatus ?? 200, contentType: state.getStatus >= 400 ? 'application/problem+json' : 'application/json',
      headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify(state.getStatus >= 400 ? { type: 'x', title: 'x', detail: 'boom' } : state.status) })],
    ['POST', /^\/setup\/dpa\/otp$/, { body: { otpChallengeId: 'c1', expiresAt: '2026-10-21T10:05:00Z' } }],
    ['POST', /^\/setup\/dpa\/accept$/, (route, req) => {
      const b = JSON.parse(req.postData() ?? '{}');
      if (b.otp !== '123456') return route.fulfill({ status: 400, contentType: 'application/problem+json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ type: 'https://api.nabd.health/errors/OTP_INVALID', title: 'x', detail: 'The code is wrong or expired. Request a new one.' }) });
      state.status = signedNoPlan;
      return route.fulfill({ status: 201, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ acceptanceId: 'a1', dataGate: 'demo_only', pdfUrl: '/v1/setup/dpa/acceptances/a1/pdf', blockers: ['PLAN_REQUIRED'] }) });
    }],
    ['GET', /^\/setup\/dpa\/acceptances\/a1\/pdf$/, (route) => route.fulfill({ status: 200, contentType: 'application/pdf', headers: { 'access-control-allow-origin': '*' }, body: '%PDF-1.4' })],
    ['GET', /^\/reports\/overview/, { body: overview }],
    ['GET', /./, { body: [] }],
  ];
}

async function open(perms, state, path = '/setup/wizard', viewport = { width: 1280, height: 900 }) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, handlers(state));
  await s.page.goto(`${APP}${path}`, { waitUntil: 'networkidle' });
  if (path === '/setup/wizard') await s.page.getByRole('button', { name: 'DPA', exact: true }).click(); // ADM-007: the DPA panel moved from Go live to its own step
  return s;
}

try {
  // 1 owner/editor flow: blockers, full text on demand, OTP, wrong code, right code, signed state, download
  let state = { status: demo };
  let s = await open(['setup:view', 'setup:edit'], state);
  await s.page.getByText('Choose a plan').waitFor({ timeout: 8000 }).then(() => check('1a blockers listed (sign + plan)', true), () => check('1a blockers listed (sign + plan)', false));
  check('1b both blockers visible', (await s.page.getByText('Sign the data processing agreement').count()) >= 1);
  check('1c full text collapsed until opened', !(await s.page.getByText('Render: API hosting').isVisible()));
  await s.page.getByText('Read the full agreement').click();
  check('1d full text shows sub-processors', await s.page.getByText('Render: API hosting').isVisible());
  await s.page.getByRole('button', { name: 'Send code to my mobile' }).click();
  const otp = s.page.getByLabel('6-digit code');
  await otp.waitFor({ timeout: 5000 });
  const sign = s.page.getByRole('button', { name: 'Sign agreement' });
  check('1e sign disabled until 6 digits', await sign.isDisabled());
  await otp.fill('12345a'); // letters are stripped
  check('1f non-digits stripped', (await otp.inputValue()) === '12345');
  await otp.fill('654321');
  await sign.click();
  await s.page.getByRole('alert').filter({ hasText: 'wrong or expired' }).waitFor({ timeout: 5000 }).then(() => check('1g wrong code shows the server message', true), () => check('1g wrong code shows the server message', false));
  await otp.fill('123456');
  await sign.click();
  await s.page.getByText('Agreement signed.').waitFor({ timeout: 5000 }).then(() => check('1h signed confirmation', true), () => check('1h signed confirmation', false));
  check('1i plan is the remaining blocker', (await s.page.getByText('Choose a plan').count()) >= 1 && (await s.page.getByText('Sign the data processing agreement').count()) === 0);
  const acc = s.log.requests.find((r) => r.method === 'POST' && r.path === '/setup/dpa/accept' && JSON.parse(r.body).otp === '123456');
  check('1j accept body has version, challenge, otp', !!acc && JSON.parse(acc.body).version === V && JSON.parse(acc.body).otpChallengeId === 'c1');
  check('1k signing controls gone once signed', (await s.page.getByRole('button', { name: 'Send code to my mobile' }).count()) === 0);
  const dl = s.page.waitForEvent('download', { timeout: 5000 });
  await s.page.getByRole('button', { name: 'Download signed PDF' }).click();
  check('1l signed PDF downloads', (await dl).suggestedFilename() === 'nabd-dpa.pdf');
  check('1m auth header on pdf fetch', s.log.requests.some((r) => /acceptances\/a1\/pdf/.test(r.path) && r.auth));
  check('1n zero console/page errors', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('owner', s.log); await s.browser.close();

  // 2 no setup:edit: status readable, signing controls ABSENT
  s = await open(['setup:view'], { status: demo });
  await s.page.getByText('Choose a plan').waitFor({ timeout: 8000 });
  check('2a read-only user sees the status', (await s.page.getByText('Read the full agreement').count()) === 1);
  check('2b no send-code / sign controls', (await s.page.getByRole('button', { name: /Send code|Sign agreement/ }).count()) === 0 && (await s.page.getByLabel('6-digit code').count()) === 0);
  await s.browser.close();

  // 3 load error then retry works
  state = { status: demo, getStatus: 500 };
  s = await open(['setup:view', 'setup:edit'], state);
  await s.page.getByRole('alert').filter({ hasText: 'boom' }).waitFor({ timeout: 8000 }).then(() => check('3a load error shown', true), () => check('3a load error shown', false));
  state.getStatus = 200;
  await s.page.getByRole('button', { name: 'Retry' }).click();
  await s.page.getByText('Choose a plan').waitFor({ timeout: 5000 }).then(() => check('3b retry loads the agreement', true), () => check('3b retry loads the agreement', false));
  await s.browser.close();

  // 4 live tenant with an older DPA: non-blocking banner on the overview, nothing blocked; absent without setup:view
  s = await open(['setup:view', 'reports:view'], { status: liveOld }, '/overview');
  await s.page.getByText('Please accept it by 2026-11-20').waitFor({ timeout: 8000 }).then(() => check('4a banner with due date', true), () => check('4a banner with due date', false));
  check('4b banner is a status, not a modal', (await s.page.getByRole('dialog').count()) === 0 && (await s.page.getByRole('status').filter({ hasText: 'Nothing is blocked' }).count()) === 1);
  check('4c overview still renders', (await s.page.getByText('Today at a glance').count()) === 1);
  await s.browser.close();
  s = await open(['reports:view'], { status: liveOld }, '/overview');
  await s.page.getByText('Today at a glance').waitFor({ timeout: 8000 });
  check('4d no banner and no /setup/dpa call without setup:view', (await s.page.getByText('Nothing is blocked').count()) === 0 && !s.log.requests.some((r) => r.path === '/setup/dpa'));
  await s.browser.close();

  // 5 live and current: success line, no banner; 390px
  s = await open(['setup:view', 'setup:edit'], { status: { ...liveOld, accepted: { ...liveOld.accepted, version: V }, reacceptDueBy: null } }, '/setup/wizard', { width: 390, height: 800 });
  await s.page.getByText('Data agreement in place').waitFor({ timeout: 8000 }).then(() => check('5a live state', true), () => check('5a live state', false));
  check('5b no banner when current', (await s.page.getByText('Nothing is blocked').count()) === 0);
  await s.page.getByText('Read the full agreement').click();
  check('5c 390px no horizontal scroll', await noOverflow(s.page));
  check('5d zero console/page errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();
} catch (e) {
  check('script error', false, String(e));
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
