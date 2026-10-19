// FEF-002 E2E: silent refresh, returnTo, idle-lock unlock messages. Run:
//   NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3207 node s9-fef-002-silent-refresh.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, jwt, APP } from './qa-lib.mjs';

const DOC = ['queue:view', 'patients:view', 'clinical:edit'];
const fresh = (tag) => jwt({ sub: '11111111-1111-1111-1111-111111111111', permissions: DOC, tag, iat: Math.floor(Date.now() / 1000) });
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
const json = (status, body, headers) => ({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body), headers });

async function arrivalsSession({ refresh }) {
  const s = await launch();
  await seedClinic(s.page, DOC);
  const NEW = fresh('new');
  s.refreshCalls = 0;
  s.NEW = NEW;
  await mockApi(s.page, s.log, [
    ['POST', /^\/auth\/refresh/, (r) => { s.refreshCalls++; return r.fulfill(refresh(NEW)); }],
    ['GET', /^\/queue/, (r, req) => r.fulfill(req.headers()['authorization'] === `Bearer ${NEW}` ? json(200, []) : json(401, { title: 'Unauthorized' }))],
  ]);
  return s;
}

let s;
try {
  // 1 reactive: stale token -> 401 -> exactly one refresh -> retried with the new token, user stays put
  s = await arrivalsSession({ refresh: (NEW) => json(200, { accessToken: NEW, refreshToken: 'r2', expiresIn: 900 }) });
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
  const queueCalls = s.log.requests.filter((r) => r.path.startsWith('/queue'));
  check('1a one refresh call for a 401 burst', s.refreshCalls === 1, `refresh=${s.refreshCalls}`);
  check('1b request retried with the rotated token, no redirect', queueCalls.some((r) => r.auth === `Bearer ${s.NEW}`) && new URL(s.page.url()).pathname === '/arrivals', s.page.url());
  check('1c rotated refresh token stored, no console errors', (await s.page.evaluate(() => localStorage.getItem('nabd_refresh_token'))) === 'r2' && errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  // 2 refresh rejected -> /login?returnTo=<path>, notice shown, then login returns to the page; evil returnTo ignored
  s = await arrivalsSession({ refresh: () => json(401, { title: 'REFRESH_INVALID' }) });
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
  await s.page.waitForURL(/\/login\?returnTo=/, { timeout: 5000 });
  check('2a refresh failure lands on /login?returnTo=%2Farrivals', new URL(s.page.url()).searchParams.get('returnTo') === '/arrivals', s.page.url());
  check('2b "Signed out" notice with draft message', (await s.page.getByRole('status').filter({ hasText: 'Your draft is saved' }).count()) === 1);
  check('2b2 exactly one refresh before the redirect (no retry loop)', s.refreshCalls === 1, `refresh=${s.refreshCalls}`);
  await s.page.setViewportSize({ width: 390, height: 800 });
  check('2c 390px: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  const loginHandlers = (tok) => [['POST', /^\/auth\/login/, { status: 200, body: { accessToken: tok, refreshToken: 'r9', expiresIn: 900 } }], ['GET', /^\/queue/, { status: 200, body: [] }]];
  await mockApi(s.page, s.log, loginHandlers(fresh('login')));
  const signIn = async () => {
    await s.page.getByLabel('Clinic code').fill('qa'); await s.page.getByLabel('Email').fill('d@qa.test');
    await s.page.getByLabel('PIN', { exact: true }).fill('123456'); await s.page.getByRole('button', { name: 'Sign in', exact: true }).click();
  };
  await signIn();
  await s.page.waitForURL(/\/arrivals$/, { timeout: 8000 });
  check('2d after login the user is back on the same page', new URL(s.page.url()).pathname === '/arrivals');
  for (const evil of ['https%3A%2F%2Fevil.example', '%2F%2Fevil.example']) {
    await s.page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
    await s.page.goto(`${APP}/login?returnTo=${evil}`, { waitUntil: 'networkidle' });
    await signIn();
    await s.page.waitForURL((u) => u.pathname !== '/login', { timeout: 8000 });
    check(`2e evil returnTo ${decodeURIComponent(evil)} -> role landing page, same origin`, new URL(s.page.url()).origin === new URL(APP).origin && !s.page.url().includes('evil'), s.page.url());
  }
  await s.browser.close();

  // 3 idle lock: refresh first, three distinct messages
  for (const [name, refresh, unlock, expect] of [
    ['correct PIN unlocks', json(200, { accessToken: fresh('u'), refreshToken: 'r3', expiresIn: 900 }), { status: 204 }, null],
    ['wrong PIN', json(200, { accessToken: fresh('u'), refreshToken: 'r3', expiresIn: 900 }), json(401, { title: 'Invalid credentials' }), 'Incorrect PIN'],
    ['expired session', json(401, { title: 'REFRESH_INVALID' }), { status: 204 }, 'Session expired, sign in again'],
    ['rate limited', json(200, { accessToken: fresh('u'), refreshToken: 'r3', expiresIn: 900 }), json(429, { title: 'Too many' }, { 'retry-after': '30' }), 'Too many attempts, wait 30 s'],
  ]) {
    s = await launch();
    await seedClinic(s.page, DOC);
    const order = [];
    await mockApi(s.page, s.log, [
      ['POST', /^\/auth\/refresh/, (r) => { order.push('refresh'); return r.fulfill(refresh); }],
      ['POST', /^\/auth\/unlock/, (r) => { order.push('unlock'); return r.fulfill(unlock); }],
      ['GET', /^\/queue/, { status: 200, body: [] }],
    ]);
    await s.page.clock.install();
    await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
    await s.page.clock.fastForward(6 * 60 * 1000);
    await s.page.getByText('Screen locked').waitFor({ timeout: 5000 });
    await s.page.locator('input[type=password]').fill('1234');
    await s.page.getByRole('button', { name: 'Unlock' }).click();
    if (expect) {
      await s.page.getByRole('alert').filter({ hasText: expect }).waitFor({ timeout: 5000 });
      check(`3 ${name}: "${expect}" in an alert (aria-live) region`, true);
    } else {
      await s.page.getByText('Screen locked').waitFor({ state: 'detached', timeout: 5000 });
      check(`3 ${name}: lock dismissed`, true);
    }
    check(`3 ${name}: refresh is called before unlock`, order[0] === 'refresh' && (order[1] === 'unlock' || name === 'expired session'), order.join('>'));
    check(`3 ${name}: no console errors`, errs(s.log).length === 0, errs(s.log).join('|'));
    await s.browser.close();
  }
} catch (e) {
  check('script ran to completion', false, String(e).split('\n')[0]);
  if (s) { dumpLog('last', s.log); console.log('  url:', s.page.url(), '| alert:', await s.page.getByRole('alert').allTextContents().catch(() => '?'), '| reqs:', s.log.requests.map((r) => r.method + ' ' + r.path).slice(-6)); }
  await s?.browser.close().catch(() => {});
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
