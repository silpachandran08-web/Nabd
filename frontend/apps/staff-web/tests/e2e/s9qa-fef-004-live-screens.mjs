// S9 QA (FEF-004, independent). NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3307 node s9qa-fef-004-live-screens.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const DAY = { date: '2026-10-18', today: true, timezone: 'Asia/Riyadh', holiday: null, doctors: [], sessions: [], counts: { appointments: 0, walkIns: 0, freeSlots: 0 } };
const DOC = '11111111-1111-1111-1111-111111111111';
const entry = (pid, id) => ({ id, appointmentId: null, patientId: pid, doctorId: DOC, departmentId: 'd1', queueDate: '2026-10-18', tokenNumber: 7, status: 'waiting', priority: false, priorityReason: null, createdAt: new Date().toISOString() });
const patient = (id, name) => ({ id, mrn: 'M-' + id, name, phone: '', dob: '1990-01-01', gender: 'f', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null });
const json = (route, body) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
const setVis = (page, v) => page.evaluate((v) => {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => v });
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => v === 'hidden' });
  document.dispatchEvent(new Event('visibilitychange'));
}, v);
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|ERR_ABORTED|preloaded using link preload/.test(x))];

// ---- A. hidden 60 s => zero requests of ANY kind (incl. nav badges, wait estimates, delays); visible => one reload; 15 s resumes
const SCREENS = [
  { name: 'arrivals', url: '/arrivals', perms: ['queue:view', 'patients:view'], primary: /^\/queue(\?|$)/ },
  { name: 'nursing', url: '/nursing', perms: ['queue:view', 'nursing:view'], primary: /^\/nursing\/worklist/ },
  { name: 'queue-board', url: '/schedule', perms: ['queue:view'], primary: /^\/queue(\?|$)/, open: (p) => p.getByRole('button', { name: 'Live Queue' }).click() },
];
for (const sc of SCREENS) {
  const { page, browser, log } = await launch();
  await seedClinic(page, sc.perms);
  await page.clock.install();
  await mockApi(page, log, [
    ['GET', /^\/schedule\/day/, { body: DAY }],
    ['GET', /^\/nursing\/worklist/, { body: { items: [] } }],
    ['GET', /^\/staff\/roster/, { body: [{ id: DOC, name: 'Dr A' }] }],
    ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5 } }],
    ['GET', /^\/doctors\/.*\/delay/, { body: [] }],
    ['GET', /^\/queue(\?|$)/, { body: [] }],
    ['GET', /./, { body: [] }],
  ]);
  await page.goto(APP + sc.url); if (sc.open) await sc.open(page);
  await sleep(500);
  await setVis(page, 'hidden'); await sleep(300);
  let b = log.requests.length;
  for (let i = 0; i < 6; i++) { await page.clock.runFor(10_000); await sleep(80); } // 60 s in 10 s steps
  await sleep(300);
  check(`A ${sc.name}: 0 API requests of any kind during 60 s hidden`, log.requests.length === b, `+${log.requests.length - b}: ${log.requests.slice(b).map((r) => r.path).join(',')}`);
  b = log.requests.filter((r) => sc.primary.test(r.path)).length;
  await setVis(page, 'visible'); await sleep(500);
  check(`A ${sc.name}: exactly one immediate reload on visible`, log.requests.filter((r) => sc.primary.test(r.path)).length === b + 1);
  b = log.requests.filter((r) => sc.primary.test(r.path)).length;
  await page.clock.runFor(15_000); await sleep(400);
  check(`A ${sc.name}: polling resumes at 15 s (+1)`, log.requests.filter((r) => sc.primary.test(r.path)).length === b + 1);
  // Boundary: hidden at 14.9 s into a cycle must not fire a request when the 15 s mark passes
  await page.clock.runFor(14_000); await setVis(page, 'hidden'); b = log.requests.length; await page.clock.runFor(5_000); await sleep(300);
  check(`A ${sc.name}: hide just before the 15 s mark => no request`, log.requests.length === b);
  check(`A ${sc.name}: no console/page errors`, errs(log).length === 0, errs(log).slice(0, 2).join('|'));
  await browser.close();
}

// ---- B. Arrivals: the wait-estimate of an OLDER load (slow) must not overwrite the newer estimate
{
  const { page, browser, log } = await launch({ viewport: { width: 1280, height: 900 } });
  let est = 0, aborted = 0;
  page.on('requestfailed', (r) => { if (/wait-estimate/.test(r.url()) && /ABORTED/i.test(r.failure()?.errorText ?? '')) aborted++; });
  await seedClinic(page, ['queue:view', 'patients:view']);
  await mockApi(page, log, [
    ['GET', /^\/schedule\/day/, { body: DAY }],
    ['GET', /^\/staff\/roster/, { body: [{ id: DOC, name: 'Dr A' }] }],
    ['GET', /^\/queue\/wait-estimate/, async (route) => { const n = est++; if (n === 0) await sleep(2500); try { await json(route, { estimatedMinutes: n === 0 ? 99 : 5 }); } catch { /* aborted */ } }],
    ['GET', /^\/doctors\/.*\/delay/, { body: [] }],
    ['GET', /^\/patients\/p1/, { body: patient('p1', 'Pat One') }],
    ['GET', /^\/queue(\?|$)/, { body: [entry('p1', 'q1')] }],
    ['GET', /./, { body: [] }],
  ]);
  await page.goto(APP + '/arrivals'); await sleep(700);
  await setVis(page, 'hidden'); await setVis(page, 'visible');
  await sleep(3500);
  const t = await page.locator('body').innerText();
  check('B arrivals: newer wait estimate (~5m) shown', /~5m wait/.test(t), t.match(/~\d+m wait/)?.[0]);
  check('B arrivals: older estimate (~99m) never shown', !/~99m/.test(t));
  check('B arrivals: older estimate request aborted', aborted >= 1, `aborted=${aborted}`);
  check('B arrivals: no console/page errors', errs(log).length === 0, errs(log).slice(0, 2).join('|'));
  await browser.close();
}

// ---- C. unmount: navigating away while a load is in flight => no setState-after-unmount errors, no stale write
{
  const { page, browser, log } = await launch();
  let slow = false;
  await seedClinic(page, ['queue:view', 'patients:view', 'nursing:view']);
  await mockApi(page, log, [
    ['GET', /^\/schedule\/day/, { body: DAY }],
    ['GET', /^\/nursing\/worklist/, async (route) => { if (slow) await sleep(1500); try { await json(route, { items: [] }); } catch { /* aborted */ } }],
    ['GET', /^\/patients\/registry/, { body: { rows: [], items: [], counts: { all: 0, archived: 0 }, total: 0 } }],
    ['GET', /./, { body: [] }],
  ]);
  await page.goto(APP + '/nursing'); await sleep(600);
  slow = true; await setVis(page, 'hidden'); await setVis(page, 'visible'); // starts a slow load
  await sleep(100);
  await page.keyboard.press('/'); await page.keyboard.type('abc'); await page.keyboard.press('Enter'); // TopBar search -> router.push('/patients?q=abc')
  await sleep(2500);
  check('C leaving /nursing mid-load: no console/page errors', errs(log).length === 0, errs(log).slice(0, 2).join('|'));
  check('C left the nursing page', new URL(page.url()).pathname !== '/nursing', page.url());
  await browser.close();
}

// ---- D. My Day: three rapid Refresh clicks, replies arrive in reverse order; last-issued wins; button not stuck
{
  const { page, browser, log } = await launch();
  let n = 0, aborted = 0;
  page.on('requestfailed', (r) => { if (/\/v1\/queue/.test(r.url()) && /ABORTED/i.test(r.failure()?.errorText ?? '')) aborted++; });
  await seedClinic(page, ['clinical:edit', 'queue:view', 'patients:view']);
  const plan = [{ d: 0, p: 'p0', name: 'Initial Load' }, { d: 2400, p: 'p1', name: 'Stale One' }, { d: 1200, p: 'p2', name: 'Stale Two' }, { d: 50, p: 'p3', name: 'Fresh Three' }];
  await mockApi(page, log, [
    ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5, patientsAhead: 0, avgVisitMinutes: 10, basedOnHistory: false } }],
    ['GET', /^\/appointments/, { body: { data: [], page: { nextCursor: null, limit: 100 } } }],
    ['GET', /^\/queue\?doctorId/, async (route) => { const r = plan[Math.min(n++, 3)]; if (r.d) await sleep(r.d); try { await json(route, [entry(r.p, 'q' + r.p)]); } catch { /* aborted */ } }],
    ...plan.map((r) => ['GET', new RegExp(`^/patients/${r.p}`), { body: patient(r.p, r.name) }]),
    ['GET', /./, { body: [] }],
  ]);
  await page.goto(APP + '/my-day'); await sleep(800);
  const btn = page.getByRole('button', { name: 'Refresh My Day' });
  await btn.click(); await sleep(100); await btn.click(); await sleep(100); await btn.click();
  await sleep(3200);
  const t = await page.locator('body').innerText();
  check('D my-day: last-issued response rendered', t.includes('Fresh Three'));
  check('D my-day: neither stale response rendered', !t.includes('Stale One') && !t.includes('Stale Two'));
  check('D my-day: superseded requests aborted (>=2) [story context/subtask 004.2, not acceptance #4]', aborted >= 2, `aborted=${aborted}`);
  check('D my-day: Refresh button usable again (not stuck loading)', await btn.isEnabled());
  check('D my-day: no console/page errors', errs(log).length === 0, errs(log).slice(0, 2).join('|'));
  await browser.close();
}

// ---- E. observation only (not a criterion): does the degraded-mode /health probe run in a hidden tab?
{
  const { page, browser, log } = await launch();
  await seedClinic(page, ['queue:view', 'patients:view']);
  await page.clock.install();
  await mockApi(page, log, [['GET', /^\/schedule\/day/, { body: DAY }], ['GET', /^\/health/, { body: {} }], ['GET', /^\/queue/, { status: 503, body: { title: 'down' } }], ['GET', /./, { status: 503, body: {} }]]);
  await page.goto(APP + '/arrivals'); await sleep(500);
  for (let i = 0; i < 4; i++) { await setVis(page, 'hidden'); await setVis(page, 'visible'); await sleep(150); } // accumulate failures
  await setVis(page, 'hidden'); await sleep(200);
  const b = log.requests.length;
  await page.clock.runFor(60_000); await sleep(400);
  const probes = log.requests.slice(b).filter((r) => /health/.test(r.path)).length;
  console.log(`OBSERVATION E: /health probes while hidden and degraded: ${probes} (other requests: ${log.requests.length - b - probes})`);
  await browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
