// ADM-004 E2E: trial chip in the top bar, "Start for real" dialog (OTP, background purge poll), permission-absent, error, 390px.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3814 node s16-adm-004-start-for-real.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

let o;
const trial = (over = {}) => ({ status: 'trialing', endsAt: '2026-11-05T00:00:00Z', daysLeft: 9, blockers: ['dpa', 'plan'], conversion: null, ...over });
const handlers = () => [
  ['GET', /^\/setup\/trial$/, (r) => {
    if (o.trialFail) return r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ title: 'x' }) });
    o.polls = (o.polls ?? 0) + (o.converted ? 1 : 0);
    const body = !o.converted ? trial(o.trial) : o.polls < 2
      ? trial({ conversion: { jobId: 'j1', status: 'running', syntheticRowsDeleted: 5 } })
      : trial({ status: 'active', daysLeft: null, conversion: { jobId: 'j1', status: o.failJob ? 'failed' : 'done', syntheticRowsDeleted: 9 } });
    r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  }],
  ['POST', /^\/setup\/trial\/convert\/otp$/, (r) => { o.otpAsked = true; r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ otpChallengeId: 'c1' }) }); }],
  ['POST', /^\/setup\/trial\/convert$/, (r) => {
    o.convertBody = JSON.parse(r.request().postData()); o.convertHeaders = r.request().headers();
    if (o.convertFail) return r.fulfill({ status: 400, contentType: 'application/problem+json', body: JSON.stringify({ title: 'x', detail: 'That code is not right.' }) });
    o.converted = true; r.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ conversionJobId: 'j1' }) });
  }],
  ['GET', /^\/setup\/profile$/, { body: { name: 'QA Clinic', region: 'IN', timezone: 'Asia/Kolkata', taxIdType: null, taxId: null, whatsappNumber: null, specialties: [] } }],
  ['GET', /^\/setup\/subscription$/, { status: 404, body: { title: 'x' } }],
  ['GET', /^\/setup\/checklist/, { body: [] }],
  ['GET', /^\/setup\/dpa$/, { status: 404, body: { title: 'x' } }],
  ['GET', /^\/setup/, { body: {} }],
];
async function open(perms, extra, opts = {}, viewport) {
  o = opts;
  const s = await launch({ viewport });
  await seedClinic(s.page, perms, extra);
  await mockApi(s.page, s.log, handlers());
  await s.page.goto(`${APP}/setup`, { waitUntil: 'networkidle' });
  return s;
}
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
let s;
try {
  s = await open(['setup:view', 'setup:edit'], { owner: true, roleName: 'Owner' });
  const chip = s.page.getByRole('button', { name: 'Trial: 9 days left' });
  check('1 owner sees the trial chip with days left', await chip.count() === 1);
  await chip.click();
  const dlg = s.page.getByRole('dialog', { name: 'Start for real' });
  const t = await dlg.innerText();
  check('2 dialog lists what is kept and what is deleted', /Kept[\s\S]*Clinic profile[\s\S]*Deleted[\s\S]*Demo patients/.test(t));
  check('3 delete button is not offered before a code', await dlg.getByRole('button', { name: /Delete demo data/ }).count() === 0);
  await dlg.getByRole('button', { name: 'Send code to my mobile' }).click();
  const code = dlg.getByLabel('6-digit code');
  await code.fill('12345');
  check('4 button stays disabled for a 5 digit code', await dlg.getByRole('button', { name: /Delete demo data/ }).isDisabled());
  await code.fill('123456');
  await dlg.getByRole('button', { name: /Delete demo data/ }).click();
  await s.page.waitForURL(/\/setup\/wizard/, { timeout: 15000 }).then(() => check('5 after the purge finishes the owner lands in the wizard', true), () => check('5 lands in wizard', false));
  check('6 convert call carried confirm, challenge, otp and an Idempotency-Key', o.convertBody?.confirm === true && o.convertBody?.otpChallengeId === 'c1' && o.convertBody?.otp === '123456' && /^[0-9a-f-]{36}$/.test(o.convertHeaders?.['idempotency-key'] ?? ''));
  check('7 no console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('owner', s.log);
  await s.browser.close();

  s = await open(['setup:view', 'setup:edit'], { owner: true }, { convertFail: true });
  await s.page.getByRole('button', { name: /Trial:/ }).click();
  const d2 = s.page.getByRole('dialog', { name: 'Start for real' });
  await d2.getByRole('button', { name: 'Send code to my mobile' }).click();
  await d2.getByLabel('6-digit code').fill('000000');
  await d2.getByRole('button', { name: /Delete demo data/ }).click();
  await d2.getByText('That code is not right.').waitFor({ timeout: 5000 }).then(() => check('8 wrong code shows the server reason and the dialog stays', true), () => check('8 wrong code reason', false));
  await s.browser.close();

  s = await open(['setup:view'], { owner: false });
  check('9 non-owner sees the chip as text, no button', await s.page.getByText('Trial: 9 days left').count() === 1 && await s.page.getByRole('button', { name: /Trial:/ }).count() === 0);
  await s.browser.close();

  s = await open(['patients:view'], { owner: false });
  check('10 without setup:view no chip and no trial call', await s.page.getByText(/Trial:/).count() === 0 && !s.log.requests.some((r) => r.path === '/setup/trial'));
  await s.browser.close();

  s = await open(['setup:view', 'setup:edit'], { owner: true }, { trial: { status: 'active', daysLeft: null } });
  check('11 active clinic: no chip', await s.page.getByText(/Trial/).count() === 0);
  await s.browser.close();

  s = await open(['setup:view', 'setup:edit'], { owner: true }, { trialFail: true });
  check('12 trial read fails: no chip, page still works', await s.page.getByText(/Trial:/).count() === 0);
  await s.browser.close();

  s = await open(['setup:view', 'setup:edit'], { owner: true }, {}, { width: 390, height: 800 });
  check('13 390px: no horizontal page scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  check('14 390px: no console errors', errs(s.log).length === 0, errs(s.log).join('|'));
} catch (e) { check('run', false, String(e)); }
finally { if (s) await s.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok);
console.log(`${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
