// S15 FEF-029: route groups, error boundaries, not-found inside the shell, signed-in landing from "/", boundary telemetry.
// Needs the server started with NABD_QA_THROW=1 (the forced-throw routes answer 404 otherwise).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3806 node s15-fef-029-route-groups.mjs
import { launch, seedClinic, seedPlatform, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const NURSE = ['nursing:view', 'nursing:edit', 'queue:view', 'patients:view', 'clinical:view'];
const FRONT = ['queue:view', 'patients:view'];
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload|ERR_FAILED|net::|status of 40[34]|qa forced render error|The above error occurred|Error boundary|An error occurred in the|React will try to recreate/.test(x))];
const consoleErrors = [];
const nav = (p) => p.locator('nav[aria-label], aside nav, nav').count();

async function session(perms, extra) {
  const s = await launch(extra?.viewport ? { viewport: extra.viewport } : {});
  if (perms) await seedClinic(s.page, perms, extra?.claims);
  s.reports = []; s.urls = [];
  await mockApi(s.page, s.log, [['GET', /./, { status: 200, body: [] }]]);
  // Registered after mockApi, so it runs first: keeps the telemetry body for the assertions.
  await s.page.route(/\/v1\/client-telemetry$/, (r) => { s.reports.push(JSON.parse(r.request().postData() || '{}')); return r.fulfill({ status: 202, body: '' }); });
  s.page.on('framenavigated', (f) => { if (f === s.page.mainFrame()) s.urls.push(new URL(f.url()).pathname); });
  return s;
}
try {
  // 1. Forced render error in the clinic group: panel message + Retry, shell (nav + top bar) stays, report is scrubbed.
  let s = await session(FRONT);
  await s.page.goto(APP + '/qa-throw', { waitUntil: 'networkidle' });
  check('B1 clinic: "This panel failed" with a Retry button', await s.page.getByText('This panel failed').count() === 1 && await s.page.getByRole('button', { name: 'Retry' }).count() === 1);
  check('B2 clinic: nav and top bar still on screen', await s.page.locator('nav[aria-label="Main"]').count() > 0 && await s.page.getByRole('link', { name: /Today|Arrivals/ }).count() > 0);
  await sleep(500);
  const rep = s.reports.find((r) => /^boundary\.clinic\./.test(r.metric));
  check('B3 telemetry: boundary report sent', !!rep, JSON.stringify(s.reports));
  check('B4 telemetry: route template, error name, release', rep?.route === '/qa-throw' && rep?.metric === 'boundary.clinic.TypeError' && typeof rep?.release === 'string' && rep.release.length > 0, JSON.stringify(rep));
  check('B5 telemetry: no message text, no identifiers', rep?.message === '' && !/qa forced|Asha|MRN/.test(JSON.stringify(rep)));
  await s.page.getByRole('button', { name: 'Retry' }).click(); await sleep(400);
  check('B6 Retry re-renders (still failing here, no crash loop, page alive)', await s.page.getByText('This panel failed').count() === 1);
  consoleErrors.push(...errs(s.log)); await s.browser.close();

  // 2. Unknown URL: not-found inside the clinic shell with a link home.
  s = await session(FRONT);
  await s.page.goto(APP + '/no-such-page', { waitUntil: 'networkidle' });
  check('N1 unknown URL: "Page not found" shown', await s.page.getByText('Page not found').count() === 1);
  check('N2 not-found sits inside the clinic shell (nav present)', await s.page.getByRole('link', { name: /Today|Arrivals/ }).count() > 0);
  const home = s.page.getByRole('link', { name: 'Go home' });
  check('N3 link home present', await home.count() === 1 && (await home.getAttribute('href')) === '/');
  await s.page.goto(APP + '/patients/zzz/no/such', { waitUntil: 'networkidle' });
  check('N4 deep unknown URL also not-found in shell', await s.page.getByText('Page not found').count() >= 1);
  consoleErrors.push(...errs(s.log)); await s.browser.close();

  // 3. Signed-in nurse at "/" lands on Nursing without passing through /login.
  s = await session(NURSE);
  await s.page.goto(APP + '/', { waitUntil: 'networkidle' });
  await s.page.waitForURL(/\/nursing/, { timeout: 8000 }).catch(() => {});
  check('L1 nurse at / lands on /nursing', new URL(s.page.url()).pathname === '/nursing', s.page.url());
  check('L2 never visited /login on the way', !s.urls.includes('/login'), s.urls.join(' > '));
  consoleErrors.push(...errs(s.log)); await s.browser.close();
  s = await session(null);
  await s.page.goto(APP + '/', { waitUntil: 'networkidle' });
  await s.page.waitForURL(/\/login/, { timeout: 8000 }).catch(() => {});
  check('L3 signed-out at / goes to /login', new URL(s.page.url()).pathname === '/login', s.page.url());
  consoleErrors.push(...errs(s.log)); await s.browser.close();

  // 4. The other groups: own boundary, no clinic shell.
  s = await session(null);
  await s.page.goto(APP + '/login/qa-throw', { waitUntil: 'networkidle' });
  check('G1 auth: boundary, no clinic nav', await s.page.getByText('This panel failed').count() === 1 && await s.page.getByRole('link', { name: /Today|Arrivals/ }).count() === 0);
  await s.page.goto(APP + '/owner/qa-throw', { waitUntil: 'networkidle' });
  check('G2 owner: boundary', await s.page.getByText('This panel failed').count() === 1);
  await s.page.goto(APP + '/owner/nothing-here', { waitUntil: 'networkidle' });
  check('G3 owner unknown URL: not-found without clinic nav', await s.page.getByText('Page not found').count() === 1 && await s.page.getByRole('link', { name: /Today|Arrivals/ }).count() === 0);
  consoleErrors.push(...errs(s.log)); await s.browser.close();
  s = await session(null);
  await seedPlatform(s.page);
  await s.page.goto(APP + '/platform/qa-throw', { waitUntil: 'networkidle' });
  check('G4 platform: boundary inside the platform layout', await s.page.getByText('This panel failed').count() === 1);
  await s.page.goto(APP + '/platform/nothing-here', { waitUntil: 'networkidle' });
  check('G5 platform unknown URL: not-found', await s.page.getByText('Page not found').count() === 1);
  check('G6 platform: no client telemetry sent with an operator session', s.reports.length === 0, JSON.stringify(s.reports));
  consoleErrors.push(...errs(s.log)); await s.browser.close();

  // 5. Login page still works without the clinic shell (providers, title, skip link) and fits 390px.
  s = await session(null, { viewport: { width: 390, height: 844 } });
  await s.page.goto(APP + '/login', { waitUntil: 'networkidle' });
  check('P1 login renders (PIN field present)', await s.page.locator('input').count() > 0);
  check('P2 login title', /Sign in/.test(await s.page.title()), await s.page.title());
  const over = await s.page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check('P3 390px: no horizontal scroll', over <= 0, `overflow=${over}`);
  await s.page.goto(APP + '/no-such-page', { waitUntil: 'networkidle' });
  consoleErrors.length += 0;
  const over2 = await s.page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check('P4 390px not-found: no horizontal scroll', over2 <= 0, `overflow=${over2}`);
  consoleErrors.push(...errs(s.log)); await s.browser.close();
} catch (e) {
  check('script ran to completion', false, String(e).split('\n').slice(0, 3).join(' / '));
}
check('C1 zero unexpected console errors', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
