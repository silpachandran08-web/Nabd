// FEF-004: overlapping loads -> newest wins, superseded request aborted; ClinicNav badge fetch aborted on navigation.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3209 node s9-fef-004-latest-wins.mjs
// (Visibility pause/resume criteria 1-2 are covered by s8-rec8-visibility-polling.mjs.)
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3000';
const DAY = { date: '2026-10-18', today: true, timezone: 'Asia/Riyadh', holiday: null, doctors: [], sessions: [], counts: { appointments: 0, walkIns: 0, freeSlots: 0 } };
const entry = (patientId, id) => ({ id, appointmentId: null, patientId, doctorId: '11111111-1111-1111-1111-111111111111', departmentId: 'd1', queueDate: '2026-10-18', tokenNumber: 7, status: 'waiting', priority: false, priorityReason: null, createdAt: new Date().toISOString() });
const patient = (id, name) => ({ id, mrn: 'M-' + id, name, phone: '', dob: '1990-01-01', gender: 'f', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null });
const json = (route, body) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
// n-th call of a path gets reply[n]: {delay, body}; later calls reuse the last reply.
function seq(replies, onlyJson = false) {
  let n = 0;
  return async (route, req) => {
    // ClinicNav's badge fetch has no content-type; only the page's own authedFetch does. Keep the badge out of the sequence.
    if (onlyJson && !req.headers()['content-type']) return json(route, []);
    const r = replies[Math.min(n++, replies.length - 1)];
    if (r.delay) await sleep(r.delay);
    try { await json(route, r.body); } catch { /* request was aborted by the page */ }
  };
}
const setVis = (page, v) => page.evaluate((v) => {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => v });
  document.dispatchEvent(new Event('visibilitychange'));
}, v);
const hideShow = async (page) => { await setVis(page, 'hidden'); await setVis(page, 'visible'); };
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);

async function run(name, { url, perms, handlers, body, act, expect: exp, aborted: minAborted = 1 }) {
  const ctx = await launch({ viewport: { width: 390, height: 800 } });
  const { page, browser, log } = ctx;
  let aborted = 0;
  page.on('requestfailed', (r) => { if (/\/v1\/(queue|nursing\/worklist)/.test(r.url()) && /ERR_ABORTED|aborted/i.test(r.failure()?.errorText ?? '')) aborted++; });
  await seedClinic(page, perms);
  await mockApi(page, log, handlers);
  await page.goto(APP + url);
  await sleep(500);
  await act(page);
  await sleep(2800);
  const text = await page.locator('body').innerText();
  exp(text, check, name);
  check(`${name}: superseded request aborted`, aborted >= minAborted, `aborted=${aborted}`);
  check(`${name}: no error banner after abort`, !/Couldn't (reach|load)/.test(text));
  check(`${name}: no horizontal scroll at 390 px`, await noOverflow(page));
  const bad = [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|ERR_ABORTED|preloaded using link preload/.test(x))];
  check(`${name}: zero console/page errors`, bad.length === 0, bad.slice(0, 2).join(' | '));
  dumpLog(name, log);
  await browser.close();
}

// Arrivals: load #1 (page load) answers in 2 s with the OLD patient, load #2 (hide/show) answers at once with the NEW one.
await run('arrivals', {
  url: '/arrivals', perms: ['queue:view', 'patients:view'],
  handlers: [
    ['GET', /^\/schedule\/day/, { body: DAY }],
    ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5 } }],
    // FEF-015: the board and the sidebar badge now share one GET /queue (same query), so every /queue call is in the sequence.
    ['GET', /^\/queue(\?|$)/, seq([{ delay: 2000, body: [entry('p-old', 'q1')] }, { body: [entry('p-new', 'q2')] }])],
    ['GET', /^\/patients\/p-old/, { body: patient('p-old', 'Stale Person') }],
    ['GET', /^\/patients\/p-new/, { body: patient('p-new', 'Fresh Person') }],
    ['GET', /./, { body: [] }],
  ],
  act: (page) => hideShow(page),
  expect: (t, c, n) => { c(`${n}: shows newer data`, t.includes('Fresh Person')); c(`${n}: older response not rendered`, !t.includes('Stale Person')); },
});

// Nursing: worklist #1 slow+old, #2 fast+new.
const wl = (name) => ({ items: [{ entry: { ...entry('p', 'w'), status: 'vitals_pending' }, patient: { name, mrn: 'M1', allergies: [], chronicConditions: [] }, vitals: null }] });
await run('nursing', {
  url: '/nursing', perms: ['queue:view', 'nursing:view'],
  handlers: [
    ['GET', /^\/nursing\/worklist/, seq([{ delay: 2000, body: wl('Stale Nurse Patient') }, { body: wl('Fresh Nurse Patient') }])],
    ['GET', /./, { body: [] }],
  ],
  act: (page) => hideShow(page),
  expect: (t, c, n) => { c(`${n}: shows newer data`, t.includes('Fresh Nurse Patient')); c(`${n}: older response not rendered`, !t.includes('Stale Nurse Patient')); },
});

// Live queue board (Schedule > Live Queue): only the abort + no stale error is observable without knowing the board's markup.
await run('queue-board', {
  url: '/schedule', perms: ['queue:view'],
  handlers: [
    ['GET', /^\/schedule\/day/, { body: DAY }],
    ['GET', /^\/queue(\?|$)/, seq([{ delay: 2000, body: [entry('p-old', 'q1')] }, { body: [entry('p-new', 'q2')] }])],
    ['GET', /./, { body: [] }],
  ],
  act: async (page) => { await page.getByRole('button', { name: 'Live Queue' }).click(); await sleep(300); await hideShow(page); },
  expect: () => {},
});

let phase = 0;
// My Day: initial load fast; Refresh double-click -> 2nd refresh issued while the 1st (slow, stale) is in flight.
await run('my-day', {
  url: '/my-day', perms: ['clinical:edit', 'queue:view', 'patients:view'],
  handlers: [
    ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5, patientsAhead: 0, avgVisitMinutes: 10, basedOnHistory: false } }],
    ['GET', /^\/appointments/, { body: { data: [], page: { nextCursor: null, limit: 100 } } }],
    ['GET', /^\/queue\?doctorId/, async (route) => {
      // phase 0 = initial load; after the first click the next request is slow+stale, every later one fast+new
      const r = phase === 0 ? { body: [entry('p-first', 'q0')] } : phase++ === 1 ? { delay: 2000, body: [entry('p-old', 'q1')] } : { body: [entry('p-new', 'q2')] };
      if (r.delay) await sleep(r.delay);
      try { await json(route, r.body); } catch { /* aborted */ }
    }],
    ['GET', /^\/patients\/p-first/, { body: patient('p-first', 'First Load') }],
    ['GET', /^\/patients\/p-old/, { body: patient('p-old', 'Stale Person') }],
    ['GET', /^\/patients\/p-new/, { body: patient('p-new', 'Fresh Person') }],
    ['GET', /./, { body: [] }],
  ],
  act: async (page) => { phase = 1; const b = page.getByRole('button', { name: 'Refresh My Day' }); await b.click(); await sleep(150); await b.click(); },
  expect: (t, c, n) => { c(`${n}: last-issued response rendered`, t.includes('Fresh Person')); c(`${n}: older response not rendered`, !t.includes('Stale Person')); },
});

// ClinicNav badge fetch (reception): slow /queue is aborted when the user navigates away, and no error surfaces.
{
  const { page, browser, log } = await launch({ viewport: { width: 1280, height: 900 } });
  let aborted = 0, slow = false;
  page.on('requestfailed', (r) => { if (/\/v1\/queue/.test(r.url()) && /ERR_ABORTED|aborted/i.test(r.failure()?.errorText ?? '')) aborted++; });
  await seedClinic(page, ['queue:view', 'patients:view']);
  await mockApi(page, log, [
    ['GET', /^\/schedule\/day/, { body: DAY }],
    ['GET', /^\/queue(\?|$)/, async (route) => { if (slow) await sleep(3000); try { await json(route, []); } catch { /* aborted */ } }],
    ['GET', /./, { body: [] }],
  ]);
  await page.goto(APP + '/arrivals');
  await sleep(600);
  slow = true;
  const first = await page.evaluate(() => [...document.querySelectorAll('nav a[href]')].map((a) => a.getAttribute('href')).filter((h) => h && h !== '/arrivals' && h.startsWith('/') && h !== '/'));
  // /arrivals itself fetches /queue (reply #1, fast). Navigate to a page that makes the nav re-fetch (reply #2, slow), then away again.
  await page.evaluate((h) => document.querySelector(`nav a[href="${h}"]`).click(), first[0]);
  await sleep(500);
  await page.evaluate(() => document.querySelector('nav a[href="/arrivals"]')?.click());
  await sleep(1000);
  check('clinic-nav: badge fetch aborted on navigation', aborted >= 1, `aborted=${aborted} via ${first[0]}`);
  await sleep(3200);
  const bad = [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|ERR_ABORTED|preloaded using link preload/.test(x))];
  check('clinic-nav: zero console/page errors after slow badge resolves', bad.length === 0, bad.slice(0, 2).join(' | '));
  await browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
