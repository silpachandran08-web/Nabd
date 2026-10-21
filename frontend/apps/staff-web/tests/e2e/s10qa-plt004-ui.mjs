// S10 QA PLT-004 (independent) UI: recovery-code entry on the MFA step, owner "Reset authenticator" with step-up.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3413 node s10qa-plt004-ui.mjs
import { launch, seedClinic, mockApi, jwt, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload|status of 4\d\d/.test(x))];
const noOverflow = (p) => p.evaluate(() => document.documentElement.scrollWidth <= innerWidth);
const goodTokens = () => ({ accessToken: jwt({ sub: '11111111-1111-1111-1111-111111111111', tenantId: '22222222-2222-2222-2222-222222222222', permissions: ['queue:view'], tenantName: 'QA', staffName: 'Dr Q', roleName: 'Doctor', owner: false }), refreshToken: 'r1', expiresIn: 900 });
const problem = (status, type, title) => (r) => r.fulfill({ status, contentType: 'application/problem+json', body: JSON.stringify({ type: `https://api.nabd.health/errors/${type}`, title, status, detail: title, ...(status === 423 ? { retryAfterSeconds: 120 } : {}) }) });

async function toMfa(s) {
  await s.page.goto(`${APP}/login`, { waitUntil: 'domcontentloaded' });
  await s.page.getByLabel('Clinic code').fill('qa'); await s.page.getByLabel('Email').fill('d@qa.test');
  await s.page.getByLabel('PIN', { exact: true }).fill('123456');
  await s.page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await s.page.getByRole('button', { name: 'Use a recovery code' }).waitFor({ timeout: 6000 });
}
let s;
try {
  // ---- U1 recovery code on the MFA step
  s = await launch({ viewport: { width: 390, height: 800 } });
  let mode = 'ok';
  await mockApi(s.page, s.log, [
    ['POST', /^\/auth\/login/, { body: { challengeId: 'chal.jwt.x', method: 'totp', expiresIn: 300 } }],
    ['POST', /^\/auth\/mfa\/recovery-verify/, (r) => mode === 'ok' ? r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...goodTokens(), remainingCodes: 7 }) }) : mode === 'invalid' ? problem(401, 'recovery-code-invalid', 'That recovery code is not valid.')(r) : problem(423, 'account-locked', 'Too many failed attempts. Try again later.')(r)],
    ['POST', /^\/auth\/mfa\/verify/, { body: goodTokens() }],
    ['GET', /./, { body: [] }],
  ]);
  await toMfa(s);
  check('U1.1 default MFA step is the authenticator code (6 digits) with a toggle', await s.page.getByLabel('Authentication code').count() === 1);
  await s.page.getByRole('button', { name: 'Use a recovery code' }).click();
  check('U1.2 toggle shows a labelled "Recovery code" field and a way back', await s.page.getByLabel('Recovery code').count() === 1 && await s.page.getByRole('button', { name: 'Use authenticator code' }).count() === 1);
  const submit = s.page.locator('form button[type=submit]').last();
  await s.page.getByLabel('Recovery code').fill('ABC');
  check('U1.3 submit disabled for a too-short code', await submit.isDisabled());
  mode = 'invalid';
  await s.page.getByLabel('Recovery code').fill('  WRONG-CODE-1  ');
  await submit.click();
  await s.page.getByText('not valid', { exact: false }).first().waitFor({ timeout: 4000 });
  const sent = JSON.parse(s.log.requests.find((r) => r.path === '/auth/mfa/recovery-verify').body);
  check('U1.4 invalid code: error shown, still on /login, field kept for retry', new URL(s.page.url()).pathname === '/login' && await s.page.getByLabel('Recovery code').count() === 1);
  check('U1.5 request carries challengeToken + trimmed recoveryCode (no TOTP endpoint used)', sent.challengeToken === 'chal.jwt.x' && sent.recoveryCode === 'WRONG-CODE-1' && !s.log.requests.some((r) => r.path === '/auth/mfa/verify'));
  mode = 'locked';
  await submit.click();
  await sleep(700);
  const body = await s.page.locator('body').innerText();
  check('U1.6 423 locked: a locked message that differs from the invalid-code message', /locked|too many|try again/i.test(body) && !/is not valid/.test((await s.page.getByRole('alert').allInnerTexts()).join('|')), (await s.page.getByRole('alert').allInnerTexts()).join('|'));
  check('U1.7 390px: no horizontal scroll on the recovery step', await noOverflow(s.page));
  mode = 'ok';
  await s.page.getByLabel('Recovery code').fill('GOODCODE12');
  await submit.click();
  await s.page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 8000 });
  check('U1.8 valid recovery code signs in and leaves /login', true, new URL(s.page.url()).pathname);
  check('U1.9 recovery code never appears in URL, storage or console', !s.page.url().includes('GOODCODE') && await s.page.evaluate(() => !JSON.stringify({ ...localStorage, ...sessionStorage }).includes('GOODCODE')) && !s.log.console.join(' ').includes('GOODCODE'));
  check('U1.10 zero page errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  // ---- U2 staff list reset
  const person = (id, name, status = 'active') => ({ id, email: `${id}@x.com`, name, mobilePhone: '+910', roleId: 'r1', status, scope: 'all_clinic_patients', emailVerified: true, mobileVerified: true, fieldGrants: [], departmentId: null, lastSeenAt: null, professionalRegistrationNo: null, loginMethod: 'pin', loginFactors: ['pin'] });
  const staffH = (extra = []) => [
    ['GET', /^\/staff\?/, { body: { data: [person('d1', 'Dr Lost Phone')], page: { nextCursor: null, limit: 25 } } }],
    ['GET', /^\/roles/, { body: [{ id: 'r1', name: 'Doctor', builtIn: false, grants: [], mfaRequired: false }] }], ['GET', /^\/departments/, { body: [] }], ['GET', /^\/auth\/break-glass\/active/, { body: [] }],
    ...extra, ['GET', /./, { body: [] }]];
  // permission-absent
  s = await launch();
  await seedClinic(s.page, ['staff:view']);
  await mockApi(s.page, s.log, staffH());
  await s.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  await s.page.getByText('Dr Lost Phone').first().waitFor({ timeout: 6000 });
  check('U2.1 without staff:edit the "Reset authenticator" control is ABSENT (not disabled)', await s.page.getByRole('button', { name: 'Reset authenticator' }).count() === 0 && !(await s.page.locator('body').innerText()).includes('Reset authenticator'));
  await s.browser.close();
  // wrong step-up code, then success
  s = await launch();
  await seedClinic(s.page, ['staff:view', 'staff:edit']);
  let verifyOk = false, resetStatus = 204;
  await mockApi(s.page, s.log, staffH([
    ['POST', /^\/auth\/mfa\/verify/, (r) => verifyOk ? r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ stepUpToken: 'su-1', expiresIn: 300 }) }) : problem(401, 'mfa-failed', 'MFA verification failed')(r)],
    ['POST', /^\/staff\/d1\/mfa\/reset/, (r, req) => resetStatus === 204 ? r.fulfill({ status: 204, body: '' }) : problem(resetStatus, resetStatus === 404 ? 'not-found' : 'forbidden', resetStatus === 404 ? 'Staff member not found' : 'Forbidden')(r)],
  ]));
  await s.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  const btn = s.page.getByRole('button', { name: 'Reset authenticator' });
  await btn.first().waitFor({ timeout: 6000 });
  await btn.first().focus();
  check('U2.2 control is keyboard focusable and >= 44px', await btn.first().evaluate((e) => document.activeElement === e && e.getBoundingClientRect().height >= 44), `${await btn.first().evaluate((e) => e.getBoundingClientRect().height)}px`);
  await s.page.keyboard.press('Enter');
  await s.page.locator('#stepUpCode').waitFor({ timeout: 3000 });
  check('U2.3 step-up prompt appears (code required before anything is sent)', !s.log.requests.some((r) => r.path.includes('/mfa/reset')));
  await s.page.getByRole('button', { name: 'Cancel' }).click();
  check('U2.4 Cancel closes the prompt, nothing sent', await s.page.locator('#stepUpCode').count() === 0 && !s.log.requests.some((r) => r.path.includes('/mfa/reset')));
  await btn.first().click();
  await s.page.locator('#stepUpCode').fill('000000');
  await s.page.getByRole('button', { name: 'Confirm & reset' }).click();
  await s.page.getByRole('alert').first().waitFor({ timeout: 3000 });
  check('U2.5 wrong step-up code: error shown, reset NOT sent', !s.log.requests.some((r) => r.path.includes('/mfa/reset')) && await s.page.locator('#stepUpCode').count() === 1, (await s.page.getByRole('alert').allInnerTexts()).join('|'));
  verifyOk = true; resetStatus = 404;
  await s.page.locator('#stepUpCode').fill('123456');
  await s.page.getByRole('button', { name: 'Confirm & reset' }).click();
  await sleep(900);
  const afterErr = await s.page.locator('body').innerText();
  check('U2.6 server error on reset (404): not shown as success', !afterErr.includes('Authenticator reset.'));
  verifyOk = true; resetStatus = 204;
  if (await s.page.locator('#stepUpCode').count() === 0) { await btn.first().click(); }
  await s.page.locator('#stepUpCode').fill('123456');
  await s.page.getByRole('button', { name: 'Confirm & reset' }).click();
  await s.page.getByText('Authenticator reset.').waitFor({ timeout: 4000 });
  const post = s.log.requests.filter((r) => r.path === '/staff/d1/mfa/reset').pop();
  check('U2.7 success: step-up exchanged first, then POST with reasonCode in {lost_device,left_clinic,other}', !!post && ['lost_device', 'left_clinic', 'other'].includes(JSON.parse(post.body).reasonCode));
  check('U2.8 zero page errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();
  s = null;
} catch (e) {
  check('script ran to completion', false, String(e).split('\n').slice(0, 3).join(' / '));
  if (s) { dumpLog('last', s.log); console.log('  url:', s.page.url()); }
  await s?.browser.close().catch(() => {});
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
