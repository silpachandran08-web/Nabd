// FEF-055: nurse shell: triage-first home, nav order, permission + entitlement gating (absent; direct URL = no-access), urgent triage badge
// (icon + text), desktop sidebar / tablet rail / phone bottom nav. Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3812 node tests/e2e/s16-fef055-nurse-shell.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const NURSE = ['nursing:view', 'nursing:create', 'nursing:edit', 'queue:view', 'clinical:view', 'clinical:create', 'packages:view', 'patients:view'];
const ent = (codes) => ({ body: { modules: codes.map((code) => ({ code, enabled: true })), killedFeatures: [], seatLimit: null } });
const flagged = [
  { id: 'v1', status: 'waiting', priority: true, priorityFlaggedAt: '2026-10-27T05:00:00Z', tokenNumber: 1, patientId: 'p1' },
  { id: 'v2', status: 'waiting', priority: true, priorityFlaggedAt: '2026-10-27T05:05:00Z', priorityAcknowledgedAt: '2026-10-27T05:06:00Z', tokenNumber: 2, patientId: 'p2' },
  { id: 'v3', status: 'waiting', priority: true, priorityFlaggedAt: '2026-10-27T05:07:00Z', tokenNumber: 3, patientId: 'p3' },
];
const base = (codes, queuePriority = flagged) => [
  ['GET', /^\/me\/entitlements/, ent(codes)],
  ['GET', /^\/queue\?priority=true/, { body: queuePriority }],
  ['GET', /^\/queue/, { body: [] }],
  ['GET', /^\/nursing\/administration-orders/, { body: [{ id: 'o1', status: 'not_started', drugName: 'Ceftriaxone', patientName: 'A' }] }],
  ['GET', /./, { body: [] }],
];
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preload/.test(x))];
async function open(perms, handlers, path = '/nursing', viewport) {
  const s = await launch(viewport ? { viewport } : {});
  await seedClinic(s.page, perms, { staffName: 'Nina Nurse', roleName: 'Nurse' });
  await mockApi(s.page, s.log, handlers);
  await s.page.goto(`${APP}${path}`, { waitUntil: 'networkidle' }); await sleep(500);
  return s;
}
const hrefs = (p) => p.locator('nav[aria-label="Main"]').first().locator('a').evaluateAll((as) => as.map((a) => a.getAttribute('href')));
const run = async (name, fn) => { try { await fn(); } catch (e) { check(name, false, String(e).split('\n')[0]); } };

await run('desktop', async () => {
  const s = await open(NURSE, base(['lab', 'packages']));
  const h = await hrefs(s.page);
  check('A1 nav order: triage, orders, procedures, vaccines, inbox, then priority / packages / activity',
    JSON.stringify(h) === JSON.stringify(['/nursing', '/nursing?tab=administration', '/nursing?tab=procedures', '/nursing?tab=vaccines', '/inbox', '/nursing?tab=priority', '/packages', '/nursing?tab=activity']), h.join(' '));
  check('A2 sign-in lands on triage (the role home)', /\/nursing$/.test(s.page.url()));
  const triage = s.page.locator('nav a[href="/nursing"]').first();
  const urgent = triage.locator('[data-urgent-badge]');
  const text = (await urgent.innerText()).replace(/\s+/g, ' ');
  check('A3 Triage badge counts only unacknowledged flags (2 of 3) and says "urgent"', /2/.test(text) && /urgent/i.test(text), text);
  check('A4 the urgent badge has an icon, not colour alone', (await urgent.locator('svg').count()) === 1);
  check('A5 the orders badge counts not-started orders', (await s.page.locator('nav a[href="/nursing?tab=administration"] span').filter({ hasText: /^1$/ }).count()) > 0);
  check('A6 triage is the active item', (await triage.getAttribute('aria-current')) === 'page');
  await s.page.goto(`${APP}/nursing?tab=vaccines`, { waitUntil: 'networkidle' });
  check('A7 on the vaccines tab, Vaccines is active and Triage is not', (await s.page.locator('nav a[href="/nursing?tab=vaccines"]').first().getAttribute('aria-current')) === 'page' && (await s.page.locator('nav a[href="/nursing"]').first().getAttribute('aria-current')) === null);
  s.log.requests.length = 0;
  await s.page.goto(`${APP}/inbox`, { waitUntil: 'networkidle' }); await sleep(500);
  check('A8 the entitled inbox route shows its skeleton, not a no-access state', /not available yet/i.test(await s.page.locator('main').innerText()));
  const paths = [...new Set(s.log.requests.map((r) => r.path.split('/today')[0]))];
  check('A9 on a screen with no data of its own the shell fetches only entitlements, setup and badge counts', paths.every((x) => /^\/(me\/entitlements|setup\/|queue\?priority=true|nursing\/administration-orders)/.test(x)), paths.join(' '));
  check('A10 zero console errors', noise(s.log).length === 0, noise(s.log).join('|')); dumpLog('A', s.log);
  await s.browser.close();
});

await run('gating', async () => {
  const s = await open(NURSE, base(['billing']), '/inbox');
  check('B1 module not entitled: inbox absent from nav', !(await hrefs(s.page)).includes('/inbox'));
  check('B2 direct URL shows the no-access state', /do not have access to Clinical Inbox/i.test(await s.page.locator('main').innerText()));
  await s.browser.close();
  const t = await open(['nursing:view', 'queue:view'], base(['lab', 'packages']), '/inbox');
  const h = await hrefs(t.page);
  check('B3 permission missing: inbox and packages absent from nav', !h.includes('/inbox') && !h.includes('/packages'), h.join(' '));
  check('B4 permission missing: direct URL = no-access', /do not have access/i.test(await t.page.locator('main').innerText()));
  await t.browser.close();
  const n = await open(NURSE, base(['lab', 'packages'], []));
  check('B5 no unacknowledged flag: no urgent badge at all', (await n.page.locator('[data-urgent-badge]').count()) === 0);
  await n.browser.close();
});

await run('tablet and phone', async () => {
  const t = await open(NURSE, base(['lab', 'packages']), '/nursing', { width: 900, height: 800 });
  const w = await t.page.locator('nav[aria-label="Main"]').first().evaluate((n) => n.getBoundingClientRect().width);
  check('C1 tablet (900px): icon rail', w < 120, `${w}px`);
  const u = t.page.locator('[data-urgent-badge]').first();
  check('C2 tablet: the urgent badge keeps its icon and the word for screen readers', (await u.locator('svg').count()) === 1 && /urgent/i.test(await u.evaluate((e) => e.textContent)));
  await t.browser.close();
  const m = await open(NURSE, base(['lab', 'packages']), '/nursing', { width: 375, height: 800 });
  const bottom = m.page.locator('nav[data-bottom-nav]');
  const links = await bottom.locator('> a').count();
  check('C3 phone (375px): bottom nav with the top 4 + More', links === 4 && (await bottom.getByRole('button', { name: /more/i }).count()) === 1, `${links} links`);
  check('C4 phone: the urgent badge is in the bottom nav on Triage', (await bottom.locator('[data-urgent-badge]').count()) === 1);
  check('C5 phone: no horizontal scroll', await m.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  await m.browser.close();
  const p = await open(NURSE, base(['lab', 'packages']), '/nursing', { width: 390, height: 800 });
  check('C6 390px: no horizontal scroll', await p.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  check('C7 phone: zero console errors', noise(p.log).length === 0, noise(p.log).join('|'));
  await p.browser.close();
});

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
