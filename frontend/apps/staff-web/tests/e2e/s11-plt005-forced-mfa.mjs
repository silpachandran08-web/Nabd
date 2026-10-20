// PLT-005 E2E: owner first-login enrolment, owner TOTP challenge before workspaces, operator forced enrolment,
// staff-list MFA badge, fleet privileged-MFA %. API mocked.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3516 node s11-plt005-forced-mfa.mjs
import { launch, seedClinic, seedPlatform, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const clean = (s) => !s.log.pageErrors.length && !s.log.console.filter((x) => !/Failed to load resource|preload/.test(x)).length;
const noOverflow = (p) => p.evaluate(() => document.documentElement.scrollWidth <= innerWidth);
const body = (s, path) => { const r = s.log.requests.find((x) => x.path === path); return r ? JSON.parse(r.body || '{}') : null; };
const workspacesMock = ['GET', /^\/owners\/me\/workspaces/, { body: { brands: [] } }];

// 1. unenrolled owner: PIN -> setup token -> enrol -> recovery codes -> workspaces (390px)
{
  const s = await launch({ viewport: { width: 390, height: 800 } });
  await mockApi(s.page, s.log, [
    ['POST', /^\/owners\/auth\/login/, { body: { setupToken: 'owner-setup', expiresIn: 300 } }],
    ['POST', /^\/owners\/auth\/mfa\/enroll/, { body: { secretBase32: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Nabd:o@x.com?secret=JBSWY3DPEHPK3PXP' } }],
    ['POST', /^\/owners\/auth\/mfa\/confirm/, { body: { recoveryCodes: ['AAAA111111', 'BBBB222222'], workspaceToken: 'ws-1', expiresIn: 300 } }],
    workspacesMock]);
  await s.page.goto(`${APP}/owner/login`, { waitUntil: 'networkidle' });
  await s.page.locator('#email').fill('o@x.com');
  await s.page.locator('#pin').fill('1234');
  await s.page.getByRole('button', { name: 'Sign in' }).click();
  await s.page.getByTestId('mfa-secret').waitFor({ timeout: 5000 });
  check('1 setup token routed into enrolment, secret shown', (await s.page.getByTestId('mfa-secret').innerText()) === 'JBSWY3DPEHPK3PXP');
  check('1 enrol sent the setup token in the body', body(s, '/owners/auth/mfa/enroll')?.setupToken === 'owner-setup');
  check('1 no workspace token stored before the second factor', (await s.page.evaluate(() => sessionStorage.getItem('nabd_owner_pending_token'))) === null);
  check('1 390px no horizontal scroll (enrolment)', await noOverflow(s.page));
  await s.page.locator('#setupCode').fill('123456');
  await s.page.getByRole('button', { name: 'Confirm & enable' }).click();
  await s.page.getByTestId('recovery-codes').waitFor({ timeout: 5000 });
  check('1 confirm sent setupToken + code', JSON.stringify(body(s, '/owners/auth/mfa/confirm')) === JSON.stringify({ setupToken: 'owner-setup', code: '123456' }));
  check('1 recovery codes shown once', (await s.page.getByTestId('recovery-codes').innerText()).includes('AAAA111111'));
  await s.page.getByRole('button', { name: /saved them/ }).click();
  await s.page.waitForURL(/\/owner\/workspaces/, { timeout: 5000 });
  check('1 workspace token stored only after confirm', (await s.page.evaluate(() => sessionStorage.getItem('nabd_owner_pending_token'))) === 'ws-1');
  check('1 zero console errors', clean(s)); dumpLog('1', s.log); await s.browser.close();
}
// 2. enrolled owner: wrong code (401) shows error, right code -> workspaces; recovery-code path available
{
  const s = await launch();
  let verifyCalls = 0;
  await mockApi(s.page, s.log, [
    ['POST', /^\/owners\/auth\/login/, { body: { challengeId: 'owner-challenge', method: 'totp', expiresIn: 300 } }],
    ['POST', /^\/owners\/auth\/mfa\/verify/, (route) => (++verifyCalls === 1
      ? route.fulfill({ status: 401, contentType: 'application/problem+json', body: JSON.stringify({ type: 'x/mfa-failed', title: 'MFA verification failed', detail: 'no' }) })
      : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ workspaceToken: 'ws-2', expiresIn: 300 }) }))],
    workspacesMock]);
  await s.page.goto(`${APP}/owner/login`, { waitUntil: 'networkidle' });
  await s.page.locator('#email').fill('o@x.com');
  await s.page.locator('#pin').fill('1234');
  await s.page.getByRole('button', { name: 'Sign in' }).click();
  await s.page.locator('#code').waitFor({ timeout: 5000 });
  check('2 challenge step shown, no workspace token yet', (await s.page.evaluate(() => sessionStorage.getItem('nabd_owner_pending_token'))) === null);
  check('2 recovery-code option offered', (await s.page.getByRole('button', { name: 'Use a recovery code' }).count()) === 1);
  await s.page.locator('#code').fill('000000');
  await s.page.getByRole('button', { name: 'Verify' }).click();
  await sleep(500);
  check('2 wrong code shows an error and stays on the step', (await s.page.locator('main [role=alert]').first().innerText()).includes("didn't match") && /owner\/login/.test(s.page.url()));
  await s.page.locator('#code').fill('123456');
  await s.page.getByRole('button', { name: 'Verify' }).click();
  await s.page.waitForURL(/\/owner\/workspaces/, { timeout: 5000 });
  check('2 verify posted challengeToken + code', body(s, '/owners/auth/mfa/verify')?.challengeToken === 'owner-challenge');
  check('2 workspace token stored after verify', (await s.page.evaluate(() => sessionStorage.getItem('nabd_owner_pending_token'))) === 'ws-2');
  check('2 zero console errors (401 resource line excluded)', !s.log.pageErrors.length); dumpLog('2', s.log); await s.browser.close();
}
// 3. enrolment endpoint error -> visible error with retry (error state)
{
  const s = await launch();
  await mockApi(s.page, s.log, [
    ['POST', /^\/owners\/auth\/login/, { body: { setupToken: 'expired', expiresIn: 300 } }],
    ['POST', /^\/owners\/auth\/mfa\/enroll/, { status: 401, body: { type: 'x/token-type-not-allowed', title: 'Token type not allowed', detail: 'This action needs the MFA setup token from owner sign-in.' } }]]);
  await s.page.goto(`${APP}/owner/login`, { waitUntil: 'networkidle' });
  await s.page.locator('#email').fill('o@x.com');
  await s.page.locator('#pin').fill('1234');
  await s.page.getByRole('button', { name: 'Sign in' }).click();
  await sleep(800);
  check('3 enrol failure shown', (await s.page.locator('main [role=alert]').first().innerText()).includes('MFA setup token'));
  check('3 retry + start over offered', (await s.page.getByRole('button', { name: 'Retry' }).count()) === 1 && (await s.page.getByRole('button', { name: 'Start over' }).count()) === 1);
  await s.browser.close();
}
// 4. operator without MFA: forced enrolment, then back to sign-in
{
  const s = await launch({ viewport: { width: 390, height: 800 } });
  await mockApi(s.page, s.log, [
    ['POST', /^\/platform\/auth\/login/, { body: { setupToken: 'op-setup', expiresIn: 300 } }],
    ['POST', /^\/platform\/auth\/mfa\/enroll/, { body: { secretBase32: 'OPSECRET', otpauthUri: 'otpauth://totp/x' } }],
    ['POST', /^\/platform\/auth\/mfa\/confirm/, { status: 204 }]]);
  await s.page.goto(`${APP}/platform/login`, { waitUntil: 'networkidle' });
  await s.page.locator('#email').fill('sa@nabd.test');
  await s.page.locator('#pin').fill('1234');
  await s.page.getByRole('button', { name: 'Sign in' }).click();
  await s.page.getByTestId('mfa-secret').waitFor({ timeout: 5000 });
  check('4 operator routed into enrolment', (await s.page.getByTestId('mfa-secret').innerText()) === 'OPSECRET');
  check('4 no platform session stored', (await s.page.evaluate(() => localStorage.getItem('nabd_platform_access_token'))) === null);
  await s.page.locator('#setupCode').fill('654321');
  await s.page.getByRole('button', { name: 'Confirm & enable' }).click();
  await s.page.getByText('Authenticator set up. Sign in again').waitFor({ timeout: 5000 });
  check('4 after confirm the operator signs in again', (await s.page.locator('#pin').count()) === 1);
  check('4 390px no horizontal scroll', await noOverflow(s.page));
  check('4 zero console errors', clean(s)); dumpLog('4', s.log); await s.browser.close();
}
// 5. staff list MFA column (badge per row) at 390px; empty list
{
  const person = (id, name, loginMethod, loginFactors) => ({ id, email: `${id}@x.com`, name, mobilePhone: null, roleId: 'r1', status: 'active', scope: 'all_clinic_patients',
    emailVerified: true, mobileVerified: true, fieldGrants: [], departmentId: null, lastSeenAt: null, loginMethod, loginFactors });
  const handlers = (list) => [['GET', /^\/staff\?/, { body: { data: list, page: { nextCursor: null, limit: 50 } } }],
    ['GET', /^\/roles/, { body: [{ id: 'r1', name: 'Cashier', builtIn: false, grants: [], mfaRequired: true }] }],
    ['GET', /^\/departments/, { body: [] }], ['GET', /^\/auth\/break-glass\/active/, { body: [] }]];
  const s = await launch({ viewport: { width: 390, height: 800 } });
  await seedClinic(s.page, ['staff:view']);
  await mockApi(s.page, s.log, handlers([person('a', 'Enrolled', 'authenticator', ['pin', 'authenticator']),
    person('b', 'Needs setup', 'authenticator_setup_required', ['pin']), person('c', 'Pin only', 'pin', ['pin'])]));
  await s.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  const badges = await s.page.getByTestId('mfa-status').allInnerTexts();
  check('5 every row shows an MFA status', badges.length === 3, JSON.stringify(badges));
  check('5 TOTP / Setup required / None', badges.map((b) => b.trim()).join('|') === 'TOTP|Setup required|None', JSON.stringify(badges));
  check('5 MFA column header present', (await s.page.getByRole('columnheader', { name: 'MFA' }).count()) === 1);
  check('5 390px no horizontal page scroll', await noOverflow(s.page));
  check('5 zero console errors', clean(s)); dumpLog('5', s.log); await s.browser.close();
}
// 6. fleet: privileged-MFA % column; 100% ok, partial warn, none shows a dash
{
  const t = (id, pct, users, enrolled) => ({ id, slug: id, name: `Clinic ${id}`, region: 'IN', status: 'active', brandName: null, ownerName: null, ownerEmail: null,
    createdAt: '2026-01-01T00:00:00Z', privilegedUsers: users, privilegedMfaEnrolled: enrolled, privilegedMfaPercent: pct });
  const s = await launch();
  await seedPlatform(s.page);
  await mockApi(s.page, s.log, [
    ['GET', /^\/platform\/tenants\/summary/, { body: { total: 3, live: 3, trialing: 0, byStatus: { active: 3 }, regions: ['IN'] } }],
    ['GET', /^\/platform\/tenants/, { body: { data: [t('a', 100, 2, 2), t('b', 66, 3, 2), t('c', null, 0, 0)], page: { nextCursor: null, limit: 50 } } }],
    ['GET', /^\/platform\/auth\/me/, { body: { id: 'op', name: 'Op', email: 'op@x', role: 'super_admin', permissions: ['clinics_fleet:view'] } }],
    ['GET', /^\/platform\/support-access\/grants/, { body: [] }]]);
  await s.page.goto(`${APP}/platform/fleet`, { waitUntil: 'networkidle' });
  const cells = (await s.page.getByTestId('mfa-percent').allInnerTexts()).map((x) => x.trim());
  check('6 fleet shows % of privileged users enrolled', cells.join('|') === '100%|66%|—', JSON.stringify(cells));
  check('6 header names the column', (await s.page.getByRole('columnheader', { name: 'Privileged MFA' }).count()) === 1);
  check('6 zero console errors', clean(s)); dumpLog('6', s.log); await s.browser.close();
}
process.exit(results.every((r) => r.ok) ? 0 : 1);
