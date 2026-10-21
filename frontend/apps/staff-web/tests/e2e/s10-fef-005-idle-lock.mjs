// FEF-005 E2E: persistent + cross-tab idle lock, inert/dialog semantics, per-tenant timeout + 30 s warning, sign-out deletes drafts DB.
//   NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3404 node s10-fef-005-idle-lock.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const DOC = ['queue:view', 'patients:view', 'clinical:edit', 'setup:view'];
const NURSE = ['queue:view', 'nursing:view'];
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
const ok = (body) => ({ status: 200, body });
const policy = (v) => ['GET', /^\/setup\/policies/, ok(v == null ? [] : [{ id: '1', policyKey: 'session.idle_lock_minutes', value: String(v), version: 1 }])];
const base = (v) => [
  policy(v),
  ['POST', /^\/auth\/refresh/, ok({ accessToken: 'x', refreshToken: 'y', expiresIn: 900 })],
  ['POST', /^\/auth\/unlock/, ok({})],
  ['POST', /^\/auth\/logout/, { status: 204 }],
  ['GET', /^\/queue/, ok([])],
];
const dialog = (p) => p.getByRole('dialog', { name: 'Screen locked' });
const mkDb = (p) => p.evaluate(() => new Promise((r) => { const q = indexedDB.open('nabd-consult-drafts', 1); q.onupgradeneeded = () => q.result.createObjectStore('drafts', { keyPath: 'entryId' }); q.onsuccess = () => { q.result.close(); r(true); }; }));
const dbs = (p) => p.evaluate(async () => (await indexedDB.databases()).map((d) => d.name));

let s;
try {
  // 1 Ctrl+L: dialog semantics, labelled PIN, root inert, focus trapped
  s = await launch();
  await seedClinic(s.page, DOC);
  await mockApi(s.page, s.log, base(null));
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
  await s.page.keyboard.press('Control+l');
  await dialog(s.page).waitFor({ timeout: 3000 });
  check('1a lock dialog role=dialog aria-modal, named "Screen locked"', (await dialog(s.page).getAttribute('aria-modal')) === 'true');
  check('1b PIN input has a label', await s.page.getByLabel('PIN').count() === 1);
  check('1c app root is inert', await s.page.locator('#app-root').evaluate((e) => e.inert));
  let inside = true;
  for (let i = 0; i < 8; i++) { await s.page.keyboard.press(i % 2 ? 'Shift+Tab' : 'Tab'); inside &&= await s.page.evaluate(() => !!document.activeElement?.closest('[role=dialog]')); }
  for (let i = 0; i < 8; i++) { await s.page.keyboard.press('Tab'); inside &&= await s.page.evaluate(() => !!document.activeElement?.closest('[role=dialog]')); }
  check('1d focus never leaves the dialog over 16 Tab presses', inside);
  check('1e lock persisted in session+localStorage', await s.page.evaluate(() => !!sessionStorage.getItem('nabd_locked_at') && !!localStorage.getItem('nabd_locked_at')));

  // 2 reload while locked: overlay first, no page, no PHI request
  const before = s.log.requests.filter((r) => r.path.startsWith('/queue')).length;
  const t0 = Date.now();
  await s.page.reload({ waitUntil: 'domcontentloaded' });
  await dialog(s.page).waitFor({ timeout: 3000 });
  const ms = Date.now() - t0;
  await s.page.waitForTimeout(800);
  check('2a F5 while locked shows the overlay', true, `${ms} ms incl. navigation`);
  check('2b page tree not rendered behind the lock (no nav, no content)', await s.page.locator('#app-root').evaluate((e) => e.childElementCount === 0));
  check('2c no PHI request fired after reload', s.log.requests.filter((r) => r.path.startsWith('/queue')).length === before);

  // 3 new tab sees the lock; cross-tab lock/unlock within 1 s
  const tabB = await s.context.newPage();
  await tabB.goto(`${APP}/arrivals`, { waitUntil: 'domcontentloaded' });
  await dialog(tabB).waitFor({ timeout: 3000 });
  check('3a new tab opens locked', true);
  await s.page.getByLabel('PIN').fill('123456');
  await s.page.getByRole('button', { name: 'Unlock' }).click();
  await dialog(s.page).waitFor({ state: 'detached', timeout: 5000 });
  await dialog(tabB).waitFor({ state: 'detached', timeout: 1000 });
  check('3b unlocking one tab unlocks the other within 1 s', true);
  await tabB.waitForSelector('#app-root > *', { timeout: 3000 });
  check('3c the page renders in the tab that opened locked', true);
  await s.page.bringToFront();
  await s.page.keyboard.press('Control+l');
  await dialog(tabB).waitFor({ timeout: 1000 });
  check('3d locking one tab locks the other within 1 s', true);
  check('3e zero console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  // 4 clinic timeout 10 min: warning at 9m30s, key cancels, lock at 10m
  s = await launch();
  await seedClinic(s.page, DOC);
  await mockApi(s.page, s.log, base(10));
  await s.page.clock.install();
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
  await s.page.clock.fastForward(9 * 60_000 + 29_000);
  check('4a no warning at 9m29s', await s.page.getByText(/Screen locks in/).count() === 0);
  await s.page.clock.fastForward(1_000);
  await s.page.getByText(/Screen locks in 30 s/).waitFor({ timeout: 3000 });
  check('4b 30 s warning at 9m30s', true);
  await s.page.keyboard.press('a');
  check('4c any key cancels the warning', await s.page.getByText(/Screen locks in/).count() === 0 && await dialog(s.page).count() === 0);
  await s.page.clock.fastForward(10 * 60_000 + 1_000);
  await dialog(s.page).waitFor({ timeout: 3000 });
  check('4d locks after the 10 min timeout', true);
  await s.browser.close();

  // 5 no policy access / no policy -> 5 min default
  s = await launch();
  await seedClinic(s.page, NURSE);
  await mockApi(s.page, s.log, [['GET', /^\/setup\/policies/, { status: 403, body: { title: 'Forbidden' } }], ...base(null).slice(1)]);
  await s.page.clock.install();
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
  await s.page.clock.fastForward(4 * 60_000 + 40_000);
  await s.page.getByText(/Screen locks in/).waitFor({ timeout: 3000 });
  await s.page.clock.fastForward(30_000);
  await dialog(s.page).waitFor({ timeout: 3000 });
  check('5 policy unreadable (403) falls back to 5 min', true);
  await s.browser.close();

  // 6 sign out from the lock screen deletes the drafts DB
  s = await launch();
  await seedClinic(s.page, DOC);
  await mockApi(s.page, s.log, base(null));
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
  await mkDb(s.page);
  check('6a drafts DB exists before sign-out', (await dbs(s.page)).includes('nabd-consult-drafts'));
  await s.page.keyboard.press('Control+l');
  await s.page.getByRole('button', { name: 'Not you? Sign out' }).click();
  await s.page.waitForURL(/\/login/, { timeout: 5000 });
  check('6b lock-screen sign-out: drafts DB gone', !(await dbs(s.page)).includes('nabd-consult-drafts'), (await dbs(s.page)).join(','));
  check('6c lock flag cleared, tokens gone', await s.page.evaluate(() => !localStorage.getItem('nabd_locked_at') && !localStorage.getItem('nabd_access_token')));
  await s.browser.close();

  // 7 menu sign-out deletes the drafts DB
  s = await launch();
  await seedClinic(s.page, DOC);
  await mockApi(s.page, s.log, base(null));
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
  await mkDb(s.page);
  await s.page.locator('button').filter({ hasText: 'Rana Reception' }).first().click();
  await s.page.getByRole('button', { name: 'Sign out' }).click();
  await s.page.waitForURL(/\/login/, { timeout: 5000 });
  check('7 menu sign-out: drafts DB gone', !(await dbs(s.page)).includes('nabd-consult-drafts'));
  await s.browser.close();

  // 8 390 px: locked, no horizontal scroll
  s = await launch({ viewport: { width: 390, height: 800 } });
  await seedClinic(s.page, DOC);
  await mockApi(s.page, s.log, base(null));
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
  await s.page.keyboard.press('Control+l');
  await dialog(s.page).waitFor({ timeout: 3000 });
  check('8a 390px: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  const h = await s.page.getByRole('button', { name: 'Unlock' }).evaluate((e) => e.getBoundingClientRect().height);
  check('8b 44px touch targets', h >= 44, `${h}`);
  await s.browser.close();
  s = null;
} catch (e) {
  check('script ran to completion', false, String(e).split('\n')[0]);
  if (s) { dumpLog('last', s.log); console.log('  url:', s.page.url()); }
  await s?.browser.close().catch(() => {});
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
