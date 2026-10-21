// PLT-007: open tabs are signed out within 60 s once the session is revoked (staff suspended, role changed,
// clinic suspended, 12 h cap). The backend answers 401 on the access token and 401 TENANT_INACTIVE / SESSION_MAX_AGE
// on refresh; the 60 s probe in lib/apiClient signs the tab out, and the other tabs follow via the storage event.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3708 node tests/e2e/s12-plt-007-forced-signout.mjs
import { launch, clinicToken, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const PERMS = ['queue:view', 'queue:edit', 'patients:view'];
const json = (status, body) => ({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
// seeded once per tab (qa-lib seedClinic re-seeds on every navigation, which would hide the sign-out)
const seedOnce = (page) => page.addInitScript((t) => { if (!sessionStorage.getItem('__seeded')) { localStorage.setItem('nabd_access_token', t); localStorage.setItem('nabd_refresh_token', 'r1'); sessionStorage.setItem('__seeded', '1'); } }, clinicToken(PERMS));
const onLogin = (page) => new URL(page.url()).pathname === '/login';

function handlers(st) {
  return [
    ['GET', /^\/auth\/sessions/, (r) => { st.probes++; return r.fulfill(st.revoked ? json(401, { type: 'x/unauthorized', title: 'Unauthorized' }) : json(200, [])); }],
    ['POST', /^\/auth\/refresh/, (r) => { st.refreshes++; return r.fulfill(json(401, { type: `https://api.nabd.health/problems/${st.code}`, title: 'Clinic not active' })); }],
    ['GET', /./, { status: 200, body: [] }],
  ];
}

let s;
try {
  for (const code of ['TENANT_INACTIVE', 'SESSION_MAX_AGE']) {
    s = await launch();
    const st = { revoked: false, probes: 0, refreshes: 0, code };
    await seedOnce(s.page);
    await mockApi(s.page, s.log, handlers(st));
    await s.page.clock.install();
    await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
    await s.page.clock.fastForward(61 * 1000);
    await s.page.waitForTimeout(300);
    check(`${code}: live session stays signed in after a 60 s probe`, !onLogin(s.page) && st.probes >= 1, `probes=${st.probes} url=${s.page.url()}`);

    // a second tab of the same browser profile
    const tabB = await s.context.newPage();
    await mockApi(tabB, s.log, handlers(st));
    tabB.on('pageerror', (e) => s.log.pageErrors.push('tabB: ' + String(e)));
    await tabB.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });

    st.revoked = true; // owner suspends / changes role / platform suspends the clinic
    await s.page.clock.fastForward(61 * 1000);
    await s.page.waitForURL(/\/login/, { timeout: 5000 });
    const u = new URL(s.page.url());
    check(`${code}: idle tab signed out within 60 s, back to /login with returnTo`, u.pathname === '/login' && u.searchParams.get('returnTo') === '/arrivals', s.page.url());
    check(`${code}: refresh was tried once and refused`, st.refreshes >= 1);
    const tokens = await s.page.evaluate(() => [localStorage.getItem('nabd_access_token'), localStorage.getItem('nabd_refresh_token')]);
    check(`${code}: tokens cleared`, tokens[0] === null && tokens[1] === null, JSON.stringify(tokens));
    await tabB.waitForURL(/\/login/, { timeout: 5000 }).catch(() => {});
    check(`${code}: the other open tab is signed out too (storage event)`, onLogin(tabB), tabB.url());
    check(`${code}: zero console/page errors`, noise(s.log).length === 0, JSON.stringify(noise(s.log)));
    dumpLog(code, s.log);
    await s.browser.close();
  }

  // offline / 5xx never signs out
  s = await launch({ viewport: { width: 390, height: 844 } });
  await seedOnce(s.page);
  await mockApi(s.page, s.log, [['GET', /^\/auth\/sessions/, json(503, { title: 'Unavailable' })], ['GET', /./, { status: 200, body: [] }]]);
  await s.page.clock.install();
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
  await s.page.clock.fastForward(61 * 1000);
  await s.page.waitForTimeout(300);
  check('5xx probe: stays signed in', !onLogin(s.page), s.page.url());
  check('390px: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  await s.browser.close();

  // signed-out tab on /login: no probe traffic
  s = await launch({ viewport: { width: 390, height: 844 } });
  await mockApi(s.page, s.log, [['GET', /./, { status: 200, body: [] }]]);
  await s.page.clock.install();
  await s.page.goto(`${APP}/login`, { waitUntil: 'networkidle' });
  await s.page.clock.fastForward(61 * 1000);
  check('signed out: no /auth/sessions probe', !s.log.requests.some((r) => r.path.startsWith('/auth/sessions')));
  check('login 390px: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  check('login: zero console/page errors', noise(s.log).length === 0, JSON.stringify(noise(s.log)));
  await s.browser.close();
} catch (e) {
  check('script ran to completion', false, String(e).split('\n')[0]);
  if (s) { dumpLog('last', s.log); console.log('  url:', s.page.url()); }
  await s?.browser.close().catch(() => {});
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
