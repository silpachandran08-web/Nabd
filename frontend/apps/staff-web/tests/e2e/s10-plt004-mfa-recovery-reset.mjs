// PLT-004 E2E: recovery-code entry on the MFA step; owner "Reset authenticator" with step-up on the staff list.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3416 node s10-plt004-mfa-recovery-reset.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const ROLE = { id: 'r1', name: 'Doctor', builtIn: false, grants: [], mfaRequired: false };
const person = (id, name, status = 'active') => ({ id, email: `${id}@x.com`, name, mobilePhone: '+910', roleId: 'r1', status, scope: 'all_clinic_patients', emailVerified: true, mobileVerified: true, fieldGrants: [], departmentId: null, lastSeenAt: null });
const staffHandlers = (list, extra = []) => [
  ['GET', /^\/staff\?/, { body: { data: list, page: { nextCursor: null, limit: 50 } } }],
  ['GET', /^\/roles/, { body: [ROLE] }], ['GET', /^\/departments/, { body: [] }], ['GET', /^\/auth\/break-glass\/active/, { body: [] }],
  ['POST', /^\/auth\/mfa\/verify/, { body: { stepUpToken: 'su-token', expiresIn: 300 } }], ...extra];
const clean = (s) => !s.log.pageErrors.length && !s.log.console.filter((x) => !/Failed to load resource|preload/.test(x)).length;
const noOverflow = (p) => p.evaluate(() => document.documentElement.scrollWidth <= innerWidth);

// 1. owner resets a doctor's authenticator (step-up), 390px
{
  const s = await launch({ viewport: { width: 390, height: 800 } });
  await seedClinic(s.page, ['staff:view', 'staff:edit']);
  await mockApi(s.page, s.log, staffHandlers([person('d1', 'Dr Lost Phone'), person('i1', 'Invited One', 'invited')],
    [['POST', /^\/staff\/d1\/mfa\/reset/, { status: 204 }]]));
  await s.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  const btns = s.page.getByRole('button', { name: 'Reset authenticator' });
  check('1 reset button only on the active row', (await btns.count()) === 1);
  check('1 no suspend button without staff:delete', (await s.page.getByRole('button', { name: 'Suspend' }).count()) === 0);
  await btns.first().click();
  check('1 step-up prompt names the reset', (await s.page.locator('body').innerText()).includes('Resetting an authenticator'));
  await s.page.locator('#stepUpCode').fill('123456');
  await s.page.getByRole('button', { name: 'Confirm & reset' }).click();
  await sleep(600);
  const post = s.log.requests.find((r) => r.path === '/staff/d1/mfa/reset');
  check('1 reset POSTed with reasonCode', !!post && JSON.parse(post.body).reasonCode === 'lost_device');
  check('1 step-up code exchanged first', s.log.requests.some((r) => r.path === '/auth/mfa/verify'));
  check('1 success notice shown, prompt closed', (await s.page.getByText('Authenticator reset.').count()) === 1 && (await s.page.locator('#stepUpCode').count()) === 0);
  check('1 390px no horizontal scroll', await noOverflow(s.page));
  check('1 zero console errors', clean(s)); dumpLog('1', s.log); await s.browser.close();
}
// 2. no staff:edit -> control absent
{
  const s = await launch();
  await seedClinic(s.page, ['staff:view']);
  await mockApi(s.page, s.log, staffHandlers([person('d1', 'Dr Lost Phone')]));
  await s.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  check('2 listed', (await s.page.locator('body').innerText()).includes('Dr Lost Phone'));
  check('2 reset control absent without staff:edit', (await s.page.getByRole('button', { name: 'Reset authenticator' }).count()) === 0);
  check('2 zero console errors', clean(s)); await s.browser.close();
}
// 3. server refuses (403) -> error visible in the prompt, row unchanged; empty list state
{
  const s = await launch();
  await seedClinic(s.page, ['staff:view', 'staff:edit']);
  await mockApi(s.page, s.log, staffHandlers([person('d1', 'Dr Lost Phone')],
    [['POST', /^\/staff\/d1\/mfa\/reset/, { status: 403, body: { type: 'https://x/errors/forbidden', title: 'Forbidden', detail: 'Not allowed to reset this account.' } }]]));
  await s.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  await s.page.getByRole('button', { name: 'Reset authenticator' }).click();
  await s.page.locator('#stepUpCode').fill('123456');
  await s.page.getByRole('button', { name: 'Confirm & reset' }).click();
  await sleep(600);
  check('3 error shown in prompt', (await s.page.getByText('Not allowed to reset this account.').count()) === 1);
  check('3 prompt stays open for retry', (await s.page.locator('#stepUpCode').count()) === 1);
  check('3 zero console errors', clean(s)); await s.browser.close();
}
// 4. login: recovery code on the MFA step
async function toMfaStep(s, recoveryReply) {
  await mockApi(s.page, s.log, [
    ['POST', /^\/auth\/login/, { body: { challengeId: 'chal-1', method: 'totp', expiresIn: 300 } }],
    ['POST', /^\/auth\/mfa\/recovery-verify/, recoveryReply]]);
  await s.page.goto(`${APP}/login`, { waitUntil: 'networkidle' });
  await s.page.locator('#tenantSlug').fill('demo');
  await s.page.locator('#email').fill('doc@x.com');
  await s.page.locator('#pin').fill('123456');
  await s.page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await s.page.locator('#code').waitFor();
}
{
  const s = await launch({ viewport: { width: 390, height: 800 } });
  await toMfaStep(s, { status: 401, body: { type: 'https://x/errors/recovery-code-invalid', title: 'Recovery code invalid', detail: 'That recovery code is not valid.' } });
  check('4 recovery toggle offered', (await s.page.getByRole('button', { name: 'Use a recovery code' }).count()) === 1);
  await s.page.getByRole('button', { name: 'Use a recovery code' }).click();
  check('4 input switches to recovery code', (await s.page.getByLabel('Recovery code').count()) === 1);
  await s.page.locator('#code').fill('ABCDE12345');
  await s.page.getByRole('button', { name: 'Verify', exact: true }).click();
  await sleep(500);
  const post = s.log.requests.find((r) => r.path === '/auth/mfa/recovery-verify');
  check('4 posts challengeToken + recoveryCode', !!post && JSON.parse(post.body).challengeToken === 'chal-1' && JSON.parse(post.body).recoveryCode === 'ABCDE12345');
  check('4 invalid code shows the server message', (await s.page.getByText('That recovery code is not valid.').count()) === 1);
  await s.page.getByRole('button', { name: 'Use authenticator code' }).click();
  check('4 can go back to the 6-digit code', (await s.page.getByLabel('Authentication code').count()) === 1);
  check('4 390px no horizontal scroll', await noOverflow(s.page));
  check('4 zero console errors', clean(s)); dumpLog('4', s.log); await s.browser.close();
}
process.exit(results.every((r) => r.ok) ? 0 : 1);
