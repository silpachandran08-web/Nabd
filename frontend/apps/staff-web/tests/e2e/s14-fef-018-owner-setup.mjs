// FEF-018: owner setup, staff (step-up), reports, overview and the operator console on the shared client.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3813 node tests/e2e/s14-fef-018-owner-setup.mjs
import { launch, seedClinic, seedPlatform, mockApi, check, results, sleep, dumpLog } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3000';
const json = (route, body, status = 200) => route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: body === undefined ? '' : JSON.stringify(body) });
const count = (log, method, re) => log.requests.filter((r) => r.method === method && re.test(r.path)).length;
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const cleanConsole = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|ERR_ABORTED|ERR_FAILED|preloaded using link preload/.test(x))];
const OWNER = ['setup:view', 'setup:edit', 'staff:view', 'staff:edit', 'staff:delete', 'reports:view', 'billing:view', 'queue:view'];
const STEP = { type: 'https://nabd.example/problems/step-up-required', title: 'Step-up verification required', detail: 'This action requires a fresh MFA confirmation via X-Step-Up-Token.', status: 403 };

async function open(path, handlers, { perms = OWNER, viewport, platform = false, wait = 500, clinic = true } = {}) {
  const ctx = await launch(viewport ? { viewport } : {});
  ctx.headers = [];
  ctx.page.on('request', (r) => { if (/\/v1\//.test(r.url()) && r.method() !== 'OPTIONS') ctx.headers.push({ method: r.method(), url: r.url(), h: r.headers() }); });
  if (clinic) await seedClinic(ctx.page, perms, { owner: true });
  if (platform) await seedPlatform(ctx.page);
  // the owner sidebar's plan chip reads this on every page
  await mockApi(ctx.page, ctx.log, [['GET', /^\/setup\/subscription$/, { body: { plan: 'Pro', status: 'active', branchesUsed: 1, branchesLimit: 3 } }], ...handlers]);
  await ctx.page.goto(APP + path);
  await ctx.page.waitForLoadState('networkidle').catch(() => {});
  await sleep(wait);
  return ctx;
}

// ---------------- AC4: owner setting save, network fails -> the form keeps its values and shows a retry action
{
  const profile = { id: 't1', name: 'QA Clinic', region: 'IN', timezone: 'Asia/Kolkata', taxId: null, taxIdType: null, whatsappNumber: null, specialties: ['General'], status: 'active', setupCompletedAt: null };
  let mode = 'drop';
  const patches = [];
  const { page, browser, log, headers } = await open('/setup?tab=profile', [
    ['GET', /^\/setup\/profile$/, (route) => json(route, profile)],
    ['PATCH', /^\/setup\/profile$/, (route, req) => { patches.push({ body: JSON.parse(req.postData()), key: req.headers()['idempotency-key'] }); if (mode === 'drop') return route.abort('failed'); return json(route, { ...profile, name: patches.at(-1).body.name }); }],
    ['GET', /./, { body: [] }],
  ]);
  await page.locator('#clinic-name').waitFor({ timeout: 8000 }).catch(async () => { console.log('DEBUG body:', (await page.locator('body').innerText()).slice(0, 600), log.pageErrors, log.unmatched); });
  await page.fill('#clinic-name', 'Renamed Clinic');
  await page.getByRole('button', { name: 'Save profile' }).click();
  await page.getByRole('alert').filter({ hasText: /reach the server/i }).waitFor({ timeout: 25000 });
  check('AC4 profile: dropped network keeps the typed value in the form', (await page.inputValue('#clinic-name')) === 'Renamed Clinic');
  const retry = page.getByRole('button', { name: 'Try again' });
  check('AC4 profile: a "Try again" action is shown with the error', await retry.count() === 1);
  mode = 'ok';
  await retry.click();
  await page.getByText('Profile saved.').waitFor({ timeout: 5000 });
  check('AC4 profile: retry sends the same body (name kept)', patches.length >= 2 && patches.at(-1).body.name === 'Renamed Clinic');
  check('AC4 profile: the retry carries the same Idempotency-Key as the failed attempt', patches.length >= 2 && !!patches[0].key && patches.at(-1).key === patches[0].key, patches.map((p) => p.key).join(' / '));
  check('AC4 profile: error alert gone after success', await page.getByRole('alert').filter({ hasText: /reach the server/i }).count() === 0);
  check('requests carry X-Request-Id (shared client)', headers.filter((h) => h.method === 'PATCH' && /\/setup\/profile/.test(h.url)).length >= 2 && headers.filter((h) => h.method === 'PATCH' && /\/setup\/profile/.test(h.url)).every((h) => h.h['x-request-id'] && h.h['idempotency-key']));
  check('profile: zero console errors', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  dumpLog('profile', log);
  await browser.close();
}
{
  // other tabs: a failed save keeps the tab (and its fields) and offers Retry; the retry re-sends and reloads
  let fail = true;
  const sent = [];
  const { page, browser, log } = await open('/setup?tab=consent', [
    ['GET', /^\/setup\/consent-contact$/, (route) => json(route, { name: 'Dr A', email: 'a@x.test', phone: null })],
    ['PATCH', /^\/setup\/consent-contact$/, (route, req) => { sent.push(JSON.parse(req.postData())); if (fail) return json(route, { type: 'x', title: 'Unavailable', detail: 'Try later', status: 503 }, 503); return json(route, {}); }],
    ['GET', /./, { body: [] }],
  ]);
  await page.getByRole('textbox').first().fill('Dr B');
  await page.getByRole('button', { name: 'Save contact' }).click();
  const banner = page.getByRole('alert').filter({ has: page.getByRole('button', { name: 'Retry' }) });
  await banner.waitFor({ timeout: 20000 });
  check('AC4 consent: a failed save shows a Retry action and the tab is still on screen', await page.getByRole('button', { name: 'Save contact' }).count() === 1);
  check('AC4 consent: the typed value is still in the field', (await page.getByRole('textbox').first().inputValue()) === 'Dr B');
  fail = false;
  const before = sent.length;
  await banner.getByRole('button', { name: 'Retry' }).click();
  await page.getByRole('button', { name: 'Retry' }).waitFor({ state: 'detached', timeout: 8000 }).catch(() => {});
  check('AC4 consent: Retry re-sent the same body', sent.length > before && sent.at(-1).name === 'Dr B', `before=${before} after=${sent.length} last=${JSON.stringify(sent.at(-1))}`);
  check('AC4 consent: banner gone after success', await page.getByRole('button', { name: 'Retry' }).count() === 0);
  await browser.close();
}

// ---------------- setup states: loading error + retry, 390px, no horizontal scroll
{
  let fail = true;
  const { page, browser, log } = await open('/setup?tab=charges', [
    ['GET', /^\/setup\/charges$/, (route) => (fail ? json(route, { title: 'Boom', detail: 'x' }, 500) : json(route, []))],
    ['GET', /./, { body: [] }],
  ], { viewport: { width: 390, height: 800 } });
  check('setup: a 500 on load shows the error state with Retry', await page.getByRole('button', { name: 'Retry' }).count() === 1);
  check('setup: 390px no horizontal scroll (error state)', await noOverflow(page));
  fail = false;
  await page.getByRole('button', { name: 'Retry' }).click();
  await sleep(500);
  check('setup: Retry loads the tab', await page.getByRole('button', { name: 'Retry' }).count() === 0);
  check('setup: 390px no horizontal scroll (loaded)', await noOverflow(page));
  check('setup: zero console errors', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  await browser.close();
}

// ---------------- AC2: staff step-up: the original request is retried ONCE after step-up succeeds
const staff = { id: 's2', email: 'n@x.test', name: 'Nurse Nina', mobilePhone: '+910000000000', roleId: 'r1', status: 'active', scope: 'all_clinic_patients', lastSeenAt: null };
const staffHandlers = (state) => [
  ['GET', /^\/staff\?limit=50$/, (route) => json(route, { data: [staff], page: { nextCursor: null, limit: 50 } })],
  ['GET', /^\/roles$/, (route) => json(route, [{ id: 'r1', name: 'Nurse', builtIn: false, grants: [], mfaRequired: false }])],
  ['GET', /^\/staff\/clinician-review$/, (route) => json(route, [])],
  ['GET', /^\/departments$/, (route) => json(route, [])],
  ['GET', /^\/auth\/break-glass\/active$/, (route) => json(route, { items: [] })],
  ['POST', /^\/staff\/s2\/suspend$/, (route, req) => { state.suspends.push(req.headers()); return req.headers()['x-step-up-token'] === 'st-ok' ? route.fulfill({ status: 204 }) : json(route, STEP, 403); }],
  ['POST', /^\/staff\/s2\/mfa\/reset$/, (route, req) => { state.resets.push({ h: req.headers(), body: req.postData() }); return req.headers()['x-step-up-token'] === 'st-ok' ? route.fulfill({ status: 204 }) : json(route, STEP, 403); }],
  ['POST', /^\/auth\/mfa\/verify$/, (route, req) => { const code = JSON.parse(req.postData()).code; state.verifies.push(code); return code === '123456' ? json(route, { stepUpToken: 'st-ok' }) : json(route, { type: 'x', title: 'mfa-failed', detail: 'That code is not right.', status: 401 }, 401); }],
  ['POST', /^\/auth\/refresh$/, (route) => json(route, {}, 401)],
  ['GET', /./, { body: [] }],
];
{
  const state = { suspends: [], resets: [], verifies: [] };
  const { page, browser, log } = await open('/staff', staffHandlers(state));
  await page.getByRole('button', { name: 'Suspend' }).click();
  await page.getByLabel('Authentication code').waitFor({ timeout: 5000 });
  check('AC2 the first suspend call (no token) opened the step-up dialog', state.suspends.length === 1 && !state.suspends[0]['x-step-up-token']);
  await page.getByLabel('Authentication code').fill('111111');
  await page.getByRole('button', { name: 'Confirm & suspend' }).click();
  await page.getByRole('alert').filter({ hasText: 'not right' }).waitFor({ timeout: 5000 });
  check('AC2 wrong code: dialog stays open with the server message, nothing retried yet', state.suspends.length === 1 && state.verifies.length === 1);
  check('AC2 wrong code (401 mfa-failed) does not sign the user out (no /auth/refresh, still on /staff)', count(log, 'POST', /^\/auth\/refresh$/) === 0 && new URL(page.url()).pathname === '/staff');
  await page.getByLabel('Authentication code').fill('123456');
  await page.getByRole('button', { name: 'Confirm & suspend' }).click();
  await page.getByText('Suspended', { exact: true }).waitFor({ timeout: 5000 });
  check('AC2 after step-up the ORIGINAL request is retried exactly once, with X-Step-Up-Token', state.suspends.length === 2 && state.suspends[1]['x-step-up-token'] === 'st-ok', `calls=${state.suspends.length}`);
  check('AC2 the retry kept the Idempotency-Key of the first attempt', state.suspends[0]['idempotency-key'] === state.suspends[1]['idempotency-key']);
  check('AC2 dialog closed and the row shows suspended', await page.getByLabel('Authentication code').count() === 0);
  check('staff: zero console errors', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  dumpLog('staff', log);
  await browser.close();
}
{
  const state = { suspends: [], resets: [], verifies: [] };
  const { page, browser } = await open('/staff', staffHandlers(state));
  await page.getByRole('button', { name: 'Suspend' }).click();
  await page.getByLabel('Authentication code').waitFor({ timeout: 5000 });
  await page.getByRole('button', { name: 'Cancel' }).click();
  await sleep(400);
  check('AC2 Cancel: no second suspend request', state.suspends.length === 1, `calls=${state.suspends.length}`);
  check('AC2 Cancel: dialog closed', await page.getByLabel('Authentication code').count() === 0);
  check('AC2 Cancel: no error banner', await page.locator('[role=alert]').count() === 0, (await page.locator('[role=alert]').allInnerTexts()).join(' | '));
  await page.getByRole('button', { name: 'Reset authenticator' }).click();
  await page.getByLabel('Authentication code').waitFor({ timeout: 5000 });
  await page.getByLabel('Authentication code').fill('123456');
  await page.getByRole('button', { name: 'Confirm & reset' }).click();
  await page.getByText(/Authenticator reset/).waitFor({ timeout: 5000 });
  check('AC2 reset authenticator: the dialog opens first, then ONE call carries the token and the reason body', state.resets.length === 1 && state.resets[0].h['x-step-up-token'] === 'st-ok' && JSON.parse(state.resets[0].body).reasonCode === 'lost_device');
  await browser.close();
}
{
  // staff: without staff:delete / staff:edit the actions are absent (not disabled)
  const state = { suspends: [], resets: [], verifies: [] };
  const { page, browser } = await open('/staff', staffHandlers(state), { perms: ['staff:view'] });
  check('staff: Suspend and Reset authenticator are absent without staff:delete / staff:edit', await page.getByRole('button', { name: 'Suspend' }).count() === 0 && await page.getByRole('button', { name: 'Reset authenticator' }).count() === 0);
  await browser.close();
}
{
  // staff: 500 -> error state with Retry; 390px
  const { page, browser } = await open('/staff', [['GET', /^\/staff\?/, (route) => json(route, { title: 'Boom', detail: 'x' }, 500)], ['GET', /./, { body: [] }]], { viewport: { width: 390, height: 800 } });
  check('staff: a 500 shows the error state with Retry', await page.getByRole('button', { name: 'Retry' }).count() === 1);
  check('staff: 390px no horizontal scroll', await noOverflow(page));
  await browser.close();
}

// ---------------- reports / overview
{
  const money = { from: '2026-10-25', to: '2026-10-25', includesToday: true, currency: 'INR', taxLabel: 'GST', collected: 1200, billsRaised: 3, taxCollected: 100, noShows: 1, bookings: 5, waitingNow: 2, tender: [{ method: 'cash', amount: 1200, count: 3 }], topServices: [{ name: 'Consult', revenue: 1200 }] };
  const { page, browser, log } = await open('/reports', [
    ['GET', /^\/reports\/money\?/, (route) => json(route, money)],
    ['GET', /^\/reports\/sources\?/, (route) => json(route, { title: 'Boom', detail: 'x' }, 500)],
    ['GET', /^\/(reports|packages|billing)\//, (route) => json(route, { title: 'Not found', detail: 'x' }, 404)],
    ['GET', /./, { body: [] }],
  ], { viewport: { width: 390, height: 800 }, perms: [...OWNER] });
  await page.locator('main').waitFor({ timeout: 8000 }).catch(() => console.log('DEBUG reports body:', log.pageErrors));
  check('reports: money report renders even though another report (sources) failed', (await page.locator('main').innerText({ timeout: 3000 }).catch(() => '')).includes('1,200'));
  check('reports: 390px no horizontal scroll', await noOverflow(page));
  check('reports: zero console errors', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  await browser.close();
}
{
  let mode = 403;
  const { page, browser } = await open('/reports', [['GET', /^\/reports\/money\?/, (route) => json(route, { title: 'x', detail: 'x' }, mode)], ['GET', /./, { body: [] }]]);
  check('reports: 403 shows the no-access message', (await page.locator('main').innerText({ timeout: 5000 }).catch(() => '')).includes("doesn't have access to reports"));
  await browser.close();
  mode = 500;
  const b = await open('/reports', [['GET', /^\/reports\/money\?/, (route) => json(route, { title: 'x', detail: 'x' }, mode)], ['GET', /./, { body: [] }]]);
  check('reports: 500 shows the error state with Retry', await b.page.getByRole('button', { name: 'Retry' }).count() === 1);
  await b.browser.close();
}
{
  const { page, browser } = await open('/overview', [['GET', /^\/reports\/overview$/, (route) => json(route, { title: 'x', detail: 'x' }, 500)], ['GET', /./, { body: [] }]]);
  check('overview: 500 shows the error state with Retry', await page.getByRole('button', { name: 'Retry' }).count() === 1);
  await browser.close();
}

// ---------------- AC3: operator console refreshes through the shared client with operator keys only
const me = { id: 'op1', name: 'Op', email: 'op@x.test', role: 'super_admin', permissions: ['fleet:view', 'support_access:view'] };
const platformBase = (state) => [
  ['GET', /^\/platform\/auth\/me$/, (route) => json(route, me)],
  ['POST', /^\/platform\/auth\/refresh$/, (route, req) => { state.refreshBodies.push(JSON.parse(req.postData())); return state.refreshOk ? json(route, { accessToken: 'pa2', refreshToken: 'pr2' }) : json(route, { title: 'x', detail: 'x' }, 401); }],
  ['GET', /^\/platform\/tenants\/summary$/, (route) => json(route, {})],
  ['GET', /^\/platform\/support-access\/grants$/, (route) => json(route, [])],
  ['GET', /^\/platform\/tenants\?/, (route, req) => { state.tenantAuth.push(req.headers()['authorization']); return req.headers()['authorization'] === 'Bearer pa2' ? json(route, { data: [], page: { nextCursor: null, limit: 50 } }) : json(route, { title: 'expired', detail: 'x' }, 401); }],
  ['POST', /^\/auth\/refresh$/, (route) => json(route, {}, 401)],
  ['GET', /./, { body: [] }],
];
{
  const state = { refreshBodies: [], refreshOk: true, tenantAuth: [] };
  const { page, browser, log, headers } = await open('/platform/fleet', platformBase(state), { platform: true });
  check('AC3 expired operator token: refreshed once via POST /platform/auth/refresh with the operator refresh token', state.refreshBodies.length >= 1 && state.refreshBodies.every((b) => b.refreshToken === 'pr1'), JSON.stringify(state.refreshBodies));
  check('AC3 the request is replayed with the new operator token', state.tenantAuth.includes('Bearer pa2'));
  const keys = await page.evaluate(() => ({ a: localStorage.getItem('nabd_platform_access_token'), r: localStorage.getItem('nabd_platform_refresh_token'), ca: localStorage.getItem('nabd_access_token'), cr: localStorage.getItem('nabd_refresh_token') }));
  check('AC3 rotated operator pair stored under the operator keys', keys.a === 'pa2' && keys.r === 'pr2');
  check('AC3 clinic session keys untouched', keys.cr === 'r1' && !!keys.ca);
  check('AC3 no clinic refresh call and no request carried the clinic token', count(log, 'POST', /^\/auth\/refresh$/) === 0 && headers.every((h) => !/Bearer ey/.test(h.h.authorization ?? '')));
  check('operator fleet: zero console errors', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  await browser.close();
}
{
  const state = { refreshBodies: [], refreshOk: false, tenantAuth: [] };
  const { page, browser, log } = await open('/platform/fleet', platformBase(state), { platform: true, wait: 1500 });
  await page.waitForURL(/\/platform\/login\?expired=1/, { timeout: 8000 }).catch(() => {});
  check('AC3 refresh refused: operator is sent to login with the expired message', /\/platform\/login\?expired=1/.test(page.url()), page.url());
  const keys = await page.evaluate(() => ({ a: localStorage.getItem('nabd_platform_access_token'), r: localStorage.getItem('nabd_platform_refresh_token'), cr: localStorage.getItem('nabd_refresh_token') }));
  check('AC3 operator tokens cleared, clinic refresh token still there', keys.a === null && keys.r === null && keys.cr === 'r1');
  check('AC3 clinic cookies/keys never touched: no /auth/refresh call', count(log, 'POST', /^\/auth\/refresh$/) === 0);
  await page.getByText('Your session expired. Sign in again.').waitFor({ timeout: 3000 }).catch(() => {});
  check('AC3 login page shows the expired message', await page.getByText('Your session expired. Sign in again.').count() === 1);
  await browser.close();
}
{
  // operator login: wrong PIN is an answer, not a sign-out; 390px
  const { page, browser, log } = await open('/platform/login', [
    ['POST', /^\/platform\/auth\/login$/, (route) => json(route, { type: 'x', title: 'Unauthorized', detail: 'Invalid credentials.', status: 401 }, 401)],
    ['GET', /./, { body: [] }],
  ], { clinic: false, viewport: { width: 390, height: 800 } });
  await page.getByLabel(/email/i).fill('op@x.test');
  await page.getByLabel(/pin/i).first().fill('1234');
  await page.getByRole('button', { name: /sign in/i }).click();
  await page.getByRole('alert').filter({ hasText: 'Invalid credentials.' }).waitFor({ timeout: 5000 });
  check('operator login: wrong PIN shows the server message and stays on the login page', /\/platform\/login/.test(page.url()));
  check('operator login: 390px no horizontal scroll', await noOverflow(page));
  check('operator login: zero console errors', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  await browser.close();
}
{
  // owner login: 401 -> message, 423 -> blocked state; neither touches a clinic session
  let status = 401;
  const { page, browser, log } = await open('/owner/login', [
    ['POST', /^\/owners\/auth\/login$/, (route) => status === 401 ? json(route, { title: 'Unauthorized', detail: 'bad', status: 401 }, 401) : json(route, { title: 'Locked', detail: 'Too many failed attempts. Try again later.', status: 423 }, 423)],
    ['POST', /^\/auth\/refresh$/, (route) => json(route, {}, 401)],
    ['GET', /./, { body: [] }],
  ], { clinic: false });
  await page.getByLabel(/email/i).fill('o@x.test');
  await page.getByLabel(/pin/i).first().fill('1234');
  await page.getByRole('button', { name: /continue|sign in|log in/i }).first().click();
  await page.getByText('Email or PIN is incorrect.').waitFor({ timeout: 5000 });
  check('owner login: 401 -> "Email or PIN is incorrect."', true);
  check('owner login: no clinic refresh attempted', count(log, 'POST', /^\/auth\/refresh$/) === 0);
  status = 423;
  await page.getByLabel(/pin/i).first().fill('1234');
  await page.getByRole('button', { name: /continue|sign in|log in/i }).first().click();
  await page.getByText(/Too many failed attempts/).first().waitFor({ timeout: 5000 });
  check('owner login: 423 shows the blocked message', true);
  await browser.close();
}


// ---------------- J15: provisioning (operator) -> owner accepts invite -> picks the clinic -> setup wizard profile step
{
  const jobs = [];
  const op = await open('/platform/provisioning', [
    ['GET', /^\/platform\/auth\/me$/, (route) => json(route, { ...me, permissions: ['provisioning:view'] })],
    ['GET', /^\/platform\/regions$/, (route) => json(route, { items: [] })],
    ['GET', /^\/platform\/provisioning-jobs$/, (route) => json(route, jobs)],
    ['POST', /^\/platform\/provisioning-jobs$/, (route, req) => { const b = JSON.parse(req.postData()); jobs.push({ id: 'j1', tenantSlug: b.tenantSlug, tenantName: b.tenantName, region: b.region, status: 'queued', path: b.path, approvedAt: null, createdTenantId: null, steps: [], ownerInviteToken: null, ownerAccountInviteToken: null }); return json(route, jobs[0], 201); }],
    ['GET', /./, { body: [] }],
  ], { platform: true, clinic: false });
  await op.page.getByRole('button', { name: '+ New tenant' }).click();
  await op.page.fill('#tenantSlug', 'glow');
  await op.page.fill('#tenantName', 'Glow Clinic');
  await op.page.fill('#ownerName', 'Olivia Owner');
  await op.page.fill('#ownerEmail', 'olivia@glow.test');
  await op.page.fill('#ownerMobile', '+919999999999');
  await op.page.getByRole('button', { name: 'Provision tenant' }).click();
  await op.page.getByText('Glow Clinic').first().waitFor({ timeout: 5000 });
  check('J15 operator provisions a tenant through the operator client (POST then list reload)', count(op.log, 'POST', /^\/platform\/provisioning-jobs$/) === 1 && count(op.log, 'GET', /^\/platform\/provisioning-jobs$/) >= 2);
  check('J15 provisioning: zero console errors', cleanConsole(op.log).length === 0, cleanConsole(op.log).slice(0, 2).join(' | '));
  await op.browser.close();

  const pairToken = 'pending-owner-token';
  const clinicTokenValue = (await import('./qa-lib.mjs')).clinicToken(['setup:view', 'setup:edit', 'reports:view'], { owner: true });
  let saved = null;
  const ow = await open('/owner/accept-invite/inv-1', [
    ['POST', /^\/owners\/invitations\/inv-1\/accept$/, (route) => json(route, { pendingToken: pairToken, expiresIn: 300 })],
    ['GET', /^\/owners\/me\/workspaces$/, (route, req) => req.headers().authorization === `Bearer ${pairToken}` ? json(route, { brands: [{ id: 'b1', name: 'Glow', status: 'active', clinics: [{ id: 'c1', name: 'Glow Clinic', slug: 'glow', region: 'IN', status: 'active' }] }] }) : json(route, { title: 'no', detail: 'x' }, 401)],
    ['POST', /^\/owners\/workspaces\/select$/, (route, req) => req.headers().authorization === `Bearer ${pairToken}` ? json(route, { accessToken: clinicTokenValue, refreshToken: 'rt-new', expiresIn: 900 }) : json(route, { title: 'no', detail: 'x' }, 401)],
    ['GET', /^\/setup\/checklist$/, (route) => json(route, [{ step: 'profile', status: 'pending', skippedAt: null, doneAt: null }])],
    ['GET', /^\/setup\/profile$/, (route) => json(route, { name: 'Glow Clinic', region: 'IN', timezone: 'Asia/Kolkata', taxIdType: null, taxId: null, whatsappNumber: null, specialties: [] })],
    ['PATCH', /^\/setup\/profile$/, (route, req) => { saved = JSON.parse(req.postData()); return json(route, saved); }],
    ['POST', /^\/setup\/checklist\/profile\/complete$/, (route) => json(route, {})],
    ['GET', /./, { body: [] }],
  ], { clinic: false, wait: 300 });
  await ow.page.getByLabel('New PIN').fill('4821');
  await ow.page.getByLabel('Confirm PIN').fill('4821');
  await ow.page.getByRole('button', { name: /Set PIN/ }).click();
  await ow.page.waitForURL(/\/owner\/workspaces/, { timeout: 8000 });
  await ow.page.getByText('Glow Clinic').first().waitFor({ timeout: 5000 });
  check('J15 owner accepts the invite and lists clinics with the pending token as bearer', count(ow.log, 'GET', /^\/owners\/me\/workspaces$/) === 1);
  await ow.page.getByRole('button', { name: /enter|open/i }).first().click();
  await ow.page.waitForURL(/\/overview/, { timeout: 8000 });
  check('J15 owner enters the clinic: session stored, lands on Overview', !!(await ow.page.evaluate(() => localStorage.getItem('nabd_access_token'))));
  await ow.page.goto(APP + '/setup/wizard');
  await ow.page.waitForLoadState('networkidle').catch(() => {});
  await ow.page.getByLabel('Clinic name').fill('Glow Skin Clinic').catch(() => {});
  await ow.page.getByRole('button', { name: /save|next|continue/i }).first().click().catch(() => {});
  await sleep(800);
  check('J15 setup wizard profile step saves through the client (PATCH /setup/profile)', saved !== null && saved.name === 'Glow Skin Clinic', JSON.stringify(saved));
  check('J15 owner journey: zero console errors', cleanConsole(ow.log).length === 0, cleanConsole(ow.log).slice(0, 3).join(' | '));
  dumpLog('j15', ow.log);
  await ow.browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
