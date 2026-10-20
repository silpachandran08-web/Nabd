// FEF-011: dates and times follow the clinic timezone, not the device clock.
// NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3507 node tests/e2e/s11-fef-011-clinic-timezone.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const DOCTOR = ['clinical:edit', 'clinical:view', 'queue:view', 'patients:view']; // no setup:view, like most doctors
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => /^\[error\]/.test(x) && !/Failed to load resource|ERR_ABORTED|preloaded using link preload/.test(x))];
const appt = (startTime) => ({ id: 'a1', patientId: 'p1', doctorId: SELF, startTime, endTime: startTime, status: 'scheduled', isFollowUp: false });
const patient = { id: 'p1', mrn: 'M-1', name: 'Huda Saleh', phone: '', dob: '1990-01-01', gender: 'f', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null };

async function myDay({ nowUtc, profile, day, viewport, region = 'IN' }) {
  const { page, browser, log } = await launch({ timezoneId: 'Asia/Kolkata', viewport });
  await page.clock.setFixedTime(new Date(nowUtc));
  await seedClinic(page, DOCTOR, { tenantRegion: region, staffName: 'Dr Amal', roleName: 'Doctor' });
  await mockApi(page, log, [
    ['GET', /^\/setup\/profile/, profile],
    ['GET', /^\/schedule\/day/, day],
    ['GET', /^\/appointments\?/, { body: { data: [appt('2026-03-10T18:30:00Z')], page: { nextCursor: null, limit: 100 } } }],
    ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 0, patientsAhead: 0, avgVisitMinutes: 10, basedOnHistory: false } }],
    ['GET', /^\/doctors\/.*\/delay/, { body: [] }],
    ['GET', /^\/clinical\/conditions\/due/, { body: [] }],
    ['GET', /^\/patients\/p1/, { body: patient }],
    ['GET', /^\/queue(\?|$)/, { body: [] }],
  ]);
  await page.goto(APP + '/my-day');
  await page.getByRole('heading', { name: 'My Day' }).waitFor({ timeout: 15000 });
  await page.waitForFunction(() => /Huda Saleh|No appointments/.test(document.body.innerText), null, { timeout: 15000 }).catch(() => {});
  await sleep(300);
  return { page, browser, log, apptDate: log.requests.find((r) => /^\/appointments\?/.test(r.path))?.path.match(/date=([\d-]+)/)?.[1] };
}

// A. AC1 literal: device Kolkata 23:50 (22:20 Dubai), clinic Dubai via /schedule/day (doctor has no setup:view)
{
  const r = await myDay({ nowUtc: '2026-03-10T18:20:00Z', profile: { status: 403, body: { type: 'forbidden' } }, day: { body: { timezone: 'Asia/Dubai' } } });
  const text = await r.page.locator('main').innerText();
  check('A AC1: appointments requested for the Dubai date 2026-03-10', r.apptDate === '2026-03-10', r.apptDate);
  check('A AC1: header shows Tuesday 10 March', /Tuesday 10 March/.test(text));
  check('A: appointment time shown in Dubai (22:30), not Kolkata (00:00)', /22:30/.test(text) && !/\b00:00\b/.test(text));
  check('A: zone read from /setup/profile then /schedule/day', r.log.requests.some((q) => q.path.startsWith('/setup/profile')) && r.log.requests.some((q) => q.path.startsWith('/schedule/day')));
  check('A: no fallback warning when the zone is configured', !r.log.console.some((c) => /clinic-timezone-fallback/.test(c)));
  check('A: zero console errors', errs(r.log).length === 0, errs(r.log).slice(0, 2).join('|'));
  dumpLog('A', r.log);
  await r.browser.close();
}

// B. Boundary: device already past midnight (00:15 Kolkata, 11 Mar) while Dubai is 22:45 on 10 Mar
{
  const r = await myDay({ nowUtc: '2026-03-10T18:45:00Z', profile: { body: { timezone: 'Asia/Dubai', region: 'IN' } }, day: { body: { timezone: 'Asia/Dubai' } } });
  const text = await r.page.locator('main').innerText();
  check('B: device on 11 Mar, list is still the Dubai date 2026-03-10', r.apptDate === '2026-03-10', r.apptDate);
  check('B: header still Tuesday 10 March', /Tuesday 10 March/.test(text));
  check('B: owner-style profile read is enough (no /schedule/day call)', !r.log.requests.some((q) => q.path.startsWith('/schedule/day')));
  await r.browser.close();
}

// C. AC4: tenants.timezone null -> region default (KSA -> Riyadh), one warning across navigations
{
  const r = await myDay({ nowUtc: '2026-03-10T21:15:00Z', region: 'KSA', profile: { body: { timezone: null, region: 'KSA' } }, day: { body: { timezone: 'UTC' } } });
  check('C AC4: null timezone uses the region default (Riyadh 00:15 -> 2026-03-11)', r.apptDate === '2026-03-11', r.apptDate);
  await r.page.getByRole('button', { name: 'Refresh My Day' }).click(); await sleep(500);
  await r.page.evaluate(() => { history.pushState({}, '', '/my-day?x=1'); }); await sleep(300);
  const warns = r.log.console.filter((c) => /clinic-timezone-fallback/.test(c));
  check('C AC4: the fallback warning is logged exactly once', warns.length === 1, String(warns.length));
  check('C: zero console errors', errs(r.log).length === 0, errs(r.log).slice(0, 2).join('|'));
  await r.browser.close();
}

// D. Error: zone lookups fail -> region default, My Day still loads (no blank page, no errors)
{
  const r = await myDay({ nowUtc: '2026-03-10T18:45:00Z', region: 'IN', profile: { status: 500, body: { type: 'x' } }, day: { status: 500, body: { type: 'x' } } });
  check('D: lookup failure falls back to Kolkata (00:15 -> 2026-03-11)', r.apptDate === '2026-03-11', r.apptDate);
  check('D: My Day rendered', await r.page.getByRole('heading', { name: 'My Day' }).isVisible());
  check('D: zero console errors', errs(r.log).length === 0, errs(r.log).slice(0, 2).join('|'));
  await r.browser.close();
}

// E. 390 px: no horizontal scroll
{
  const r = await myDay({ nowUtc: '2026-03-10T18:20:00Z', viewport: { width: 390, height: 844 }, profile: { status: 403, body: {} }, day: { body: { timezone: 'Asia/Dubai' } } });
  const over = await r.page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check('E: 390px has no horizontal scroll', over <= 0, `overflow=${over}px`);
  await r.browser.close();
}

// F. AC2: DocumentSheet uses the session zone (Riyadh), not the region map (header region IN would give 11 Mar)
{
  const { page, browser, log } = await launch({ timezoneId: 'Asia/Kolkata' });
  await seedClinic(page, ['clinical:view', 'clinical:edit'], { tenantRegion: 'IN' });
  await mockApi(page, log, [
    ['GET', /^\/setup\/profile/, { status: 403, body: {} }],
    ['GET', /^\/schedule\/day/, { status: 403, body: {} }],
    ['GET', /^\/clinical\/documents\/d1/, { body: { id: 'd1', type: 'certificate', status: 'issued', issuedAt: '2026-03-10T20:00:00Z', body: { diagnosis: 'Rest advised' },
      header: { clinic: { name: 'Riyadh Clinic', region: 'IN' }, patient: { name: 'Huda Saleh', dob: '1990-01-01' }, doctor: { name: 'Dr Amal' }, visit: { date: '2026-03-10' } } } }],
  ]);
  await page.goto(APP + '/documents/d1/print');
  await page.getByText('Riyadh Clinic').waitFor({ timeout: 15000 }).catch(() => {});
  await sleep(300);
  // no zone lookups allowed -> region default IN = Kolkata: the sheet must show 11 Mar (Kolkata 01:30)
  const kolkata = await page.locator('article').innerText().catch(() => '');
  check('F: without a configured zone the sheet follows the session fallback (Kolkata, 11 Mar 2026)', /11 Mar 2026/.test(kolkata), kolkata.match(/\d{2} \w{3} \d{4}/g)?.join(','));
  check('F: calendar dob printed as-is (01 Jan 1990)', /01 Jan 1990/.test(kolkata));
  await browser.close();
}
{
  const { page, browser, log } = await launch({ timezoneId: 'Asia/Kolkata' });
  await seedClinic(page, ['clinical:view', 'clinical:edit', 'setup:view'], { tenantRegion: 'IN' });
  await mockApi(page, log, [
    ['GET', /^\/setup\/profile/, { body: { timezone: 'Asia/Riyadh', region: 'IN' } }],
    ['GET', /^\/clinical\/documents\/d1/, { body: { id: 'd1', type: 'certificate', status: 'issued', issuedAt: '2026-03-10T20:00:00Z', body: { diagnosis: 'Rest advised' },
      header: { clinic: { name: 'Riyadh Clinic', region: 'IN' }, patient: { name: 'Huda Saleh', dob: '1990-01-01' }, doctor: { name: 'Dr Amal' }, visit: { date: '2026-03-10' } } } }],
  ]);
  await page.goto(APP + '/documents/d1/print');
  await page.waitForFunction(() => /10 Mar 2026/.test(document.querySelector('article')?.innerText ?? ''), null, { timeout: 15000 }).catch(() => {});
  const t = await page.locator('article').innerText().catch(() => '');
  check('F AC2: Riyadh clinic (session zone) prints 10 Mar 2026 even though header region is IN', /Date\s*10 Mar 2026/i.test(t) && !/11 Mar 2026/.test(t), t.match(/\d{2} \w{3} \d{4}/g)?.join(','));
  check('F: zero console errors', errs(log).length === 0, errs(log).slice(0, 2).join('|'));
  dumpLog('F', log);
  await browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
