// S12 QA-2, PLT-007 browser checks (independent tester). AC3 "open tabs are signed out within 60 s" plus the story's
// UI note (clear memory state and IndexedDB drafts, show "Your access changed, please sign in again").
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3812 node tests/e2e/s12qa-plt-007.mjs
import { launch, clinicToken, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const PERMS = ['queue:view', 'queue:edit'];
const json = (status, body) => ({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
const seedOnce = (page) => page.addInitScript((t) => { if (!sessionStorage.getItem('__qa2')) { localStorage.setItem('nabd_access_token', t); localStorage.setItem('nabd_refresh_token', 'r1'); sessionStorage.setItem('__qa2', '1'); } }, clinicToken(PERMS));
const onLogin = (page) => new URL(page.url()).pathname === '/login';
const seedDraftDb = (page) => page.evaluate(() => new Promise((res) => {
  const r = indexedDB.open('nabd-consult-drafts', 1);
  r.onupgradeneeded = () => r.result.createObjectStore('drafts', { keyPath: 'entryId' });
  r.onsuccess = () => { const db = r.result; const tx = db.transaction('drafts', 'readwrite'); tx.objectStore('drafts').put({ entryId: 'qa2-e1', iv: new Uint8Array(12), ct: new ArrayBuffer(8) }); tx.oncomplete = () => { db.close(); res(true); }; };
  r.onerror = () => res(false);
}));
const dbNames = (page) => page.evaluate(async () => (await indexedDB.databases()).map((d) => d.name));

let s;
try {
  // 1. role changed (access 401, refresh 401): signed out by the next probe, within 60 s of the revocation
  s = await launch();
  const st = { revoked: false, refresh: 0 };
  await seedOnce(s.page);
  await mockApi(s.page, s.log, [
    ['GET', /^\/auth\/sessions/, (r) => r.fulfill(st.revoked ? json(401, { type: 'x/unauthorized', title: 'Unauthorized' }) : json(200, []))],
    ['POST', /^\/auth\/refresh/, (r) => { st.refresh++; return r.fulfill(json(401, { type: 'https://api.nabd.health/problems/REFRESH_REUSED', title: 'Session ended' })); }],
    ['GET', /./, { status: 200, body: [] }],
  ]);
  await s.page.clock.install();
  await s.page.goto(`${APP}/arrivals?tab=waiting`, { waitUntil: 'networkidle' });
  check('draft database seeded before revocation', await seedDraftDb(s.page));
  st.revoked = true; // owner changes the receptionist's role at t=0
  await s.page.clock.fastForward(30 * 1000);
  await s.page.waitForTimeout(200);
  const at30 = onLogin(s.page);
  await s.page.clock.fastForward(31 * 1000); // t = 61 s
  await s.page.waitForURL(/\/login/, { timeout: 5000 }).catch(() => {});
  check('role change: tab is on /login at most 61 s after revocation', onLogin(s.page), `url=${s.page.url()} (at 30 s on login: ${at30})`);
  const u = new URL(s.page.url());
  check('returnTo keeps the page and its query', u.searchParams.get('returnTo') === '/arrivals?tab=waiting', s.page.url());
  check('refresh was attempted before signing out', st.refresh >= 1, `refresh=${st.refresh}`);
  const tokens = await s.page.evaluate(() => [localStorage.getItem('nabd_access_token'), localStorage.getItem('nabd_refresh_token')]);
  check('tokens cleared from storage', tokens[0] === null && tokens[1] === null, JSON.stringify(tokens));
  await s.page.waitForTimeout(300);
  check('IndexedDB consult drafts database deleted', !(await dbNames(s.page)).includes('nabd-consult-drafts'), JSON.stringify(await dbNames(s.page)));
  const text = await s.page.locator('body').innerText();
  check('UI note: login says "Your access changed, please sign in again"', /access changed/i.test(text), text.replace(/\s+/g, ' ').slice(0, 160));
  check('zero console/page errors', noise(s.log).length === 0, JSON.stringify(noise(s.log)));
  dumpLog('revoked', s.log);
  await s.browser.close();

  // 2. negative: a 401 that refresh repairs (access token merely expired) must NOT sign the user out
  s = await launch({ viewport: { width: 390, height: 844 } });
  const st2 = { probes: 0, refresh: 0 };
  await seedOnce(s.page);
  await mockApi(s.page, s.log, [
    ['GET', /^\/auth\/sessions/, (r) => { st2.probes++; return r.fulfill(st2.probes === 2 ? json(401, { type: 'x/unauthorized', title: 'Unauthorized' }) : json(200, [])); }],
    ['POST', /^\/auth\/refresh/, (r) => { st2.refresh++; return r.fulfill(json(200, { accessToken: clinicToken(PERMS, { sid: 'rotated' }), refreshToken: 'r2' })); }],
    ['GET', /./, { status: 200, body: [] }],
  ]);
  await s.page.clock.install();
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
  for (let i = 0; i < 3; i++) { await s.page.clock.fastForward(61 * 1000); await s.page.waitForTimeout(200); }
  check('expired-but-refreshable session stays signed in', !onLogin(s.page) && st2.refresh >= 1, `url=${s.page.url()} probes=${st2.probes} refresh=${st2.refresh}`);
  check('rotated refresh token stored', (await s.page.evaluate(() => localStorage.getItem('nabd_refresh_token'))) === 'r2');
  check('390px: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  check('negative case: zero console/page errors', noise(s.log).length === 0, JSON.stringify(noise(s.log)));
  await s.browser.close();
} catch (e) {
  check('script ran to completion', false, String(e).split('\n')[0]);
  if (s) { dumpLog('last', s.log); console.log('  url:', s.page.url()); }
  await s?.browser.close().catch(() => {});
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
