// Independent QA (S11) for FEF-011: My Day "today" follows the clinic zone; null tenants.timezone -> region default.
// NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3614 node tests/e2e/s11qa-fef011.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const DOCTOR = ['clinical:edit', 'clinical:view', 'queue:view', 'patients:view'];
const DOCTOR_WITH_SETUP = [...DOCTOR, 'setup:view'];
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => /^\[error\]/.test(x) && !/Failed to load resource|ERR_ABORTED|preloaded using link preload/.test(x))];

async function myDay({ nowUtc, perms, profile, day, region, deviceTz = 'Asia/Kolkata' }) {
  const { page, browser, log } = await launch({ timezoneId: deviceTz });
  await page.clock.setFixedTime(new Date(nowUtc));
  await seedClinic(page, perms, { tenantRegion: region, staffName: 'Dr Qa', roleName: 'Doctor' });
  await mockApi(page, log, [
    ['GET', /^\/setup\/profile/, profile],
    ['GET', /^\/schedule\/day/, day],
    ['GET', /^\/appointments\?/, { body: { data: [], page: { nextCursor: null, limit: 100 } } }],
    ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 0, patientsAhead: 0, avgVisitMinutes: 10, basedOnHistory: false } }],
    ['GET', /^\/doctors\/.*\/delay/, { body: [] }],
    ['GET', /^\/clinical\/conditions\/due/, { body: [] }],
    ['GET', /^\/queue(\?|$)/, { body: [] }],
  ]);
  await page.goto(APP + '/my-day');
  await page.getByRole('heading', { name: 'My Day' }).waitFor({ timeout: 15000 });
  for (let i = 0; i < 30 && !log.requests.some((r) => /^\/appointments\?/.test(r.path)); i++) await sleep(200);
  await sleep(300);
  const apptDate = log.requests.find((r) => /^\/appointments\?/.test(r.path))?.path.match(/date=([\d-]+)/)?.[1];
  return { page, browser, log, apptDate, text: await page.locator('main').innerText() };
}

async function run(name, fn) {
  try { await fn(); } catch (e) { check(name, false, String(e).split('\n')[0]); }
}

// Q1. AC1 via /setup/profile (doctor who has setup:view): device Kolkata 23:50, clinic Dubai 22:20 -> 10 Mar
await run('Q1', async () => {
  const r = await myDay({ nowUtc: '2026-03-10T18:20:00Z', perms: DOCTOR_WITH_SETUP, region: 'IN',
    profile: { body: { timezone: 'Asia/Dubai', region: 'IN' } }, day: { body: { timezone: 'Asia/Dubai' } } });
  check('Q1 AC1: list requested for the Dubai date 2026-03-10', r.apptDate === '2026-03-10', r.apptDate);
  check('Q1 AC1: header shows Tuesday 10 March', /Tuesday 10 March/.test(r.text));
  check('Q1: zero console errors', errs(r.log).length === 0, errs(r.log).slice(0, 2).join('|'));
  await r.browser.close();
});

// Q2. Mirror: device in Dubai (behind), clinic Kolkata already past midnight -> 11 Mar
await run('Q2', async () => {
  const r = await myDay({ nowUtc: '2026-03-10T18:40:00Z', perms: DOCTOR_WITH_SETUP, region: 'IN', deviceTz: 'Asia/Dubai',
    profile: { body: { timezone: 'Asia/Kolkata', region: 'IN' } }, day: { body: { timezone: 'Asia/Kolkata' } } });
  check('Q2: Dubai device at 22:40, Kolkata clinic at 00:10 -> 2026-03-11', r.apptDate === '2026-03-11', r.apptDate);
  check('Q2: header shows Wednesday 11 March', /Wednesday 11 March/.test(r.text));
  await r.browser.close();
});

// Q3. AC4 + (c): tenants.timezone NULL in a KSA clinic at 22:30Z (01:30 Riyadh on 11 Mar).
// Owner-style read (/setup/profile returns timezone null) -> region default Riyadh.
await run('Q3', async () => {
  const r = await myDay({ nowUtc: '2026-03-10T22:30:00Z', perms: DOCTOR_WITH_SETUP, region: 'KSA',
    profile: { body: { timezone: null, region: 'KSA' } }, day: { body: { timezone: 'UTC' } } });
  check('Q3 AC4: null zone via /setup/profile -> Riyadh date 2026-03-11', r.apptDate === '2026-03-11', r.apptDate);
  check('Q3 AC4: fallback warning logged once', r.log.console.filter((c) => /clinic-timezone-fallback/.test(c)).length === 1);
  await r.browser.close();
});

// Q4. Same clinic, a doctor WITHOUT setup:view: /setup/profile 403 and /schedule/day answers what the merged backend
// really sends for a null zone: 500 (ClinicClock NPE, S11QaClinicClockTest.nullTimezoneTenantGetsAClinicDayInsteadOfA500).
// AC4: the shell still falls back to the region default, so this doctor sees 2026-03-11 too.
await run('Q4', async () => {
  const r = await myDay({ nowUtc: '2026-03-10T22:30:00Z', perms: DOCTOR, region: 'KSA',
    profile: { status: 403, body: { type: 'forbidden' } }, day: { status: 500, body: { type: 'internal' } } });
  check('Q4 AC4: doctor without setup:view in a null-zone clinic also gets the Riyadh date 2026-03-11', r.apptDate === '2026-03-11', String(r.apptDate));
  check('Q4: zero console errors (besides the mocked 500)', errs(r.log).filter((x) => !/status of 500/.test(x)).length === 0, errs(r.log).slice(0, 2).join('|'));
  dumpLog('Q4', r.log);
  await r.browser.close();
});

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
