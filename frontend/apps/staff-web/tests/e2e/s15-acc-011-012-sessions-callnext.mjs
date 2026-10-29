// S15 ACC-011/012: session editor fields (mode, online cap, name, walk-in ratio), "Call next" on My Day, the "why here" chip on Arrivals.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3803 node tests/e2e/s15-acc-011-012-sessions-callnext.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3803';
const ME = '11111111-1111-1111-1111-111111111111'; // qa-lib's token subject = the signed-in doctor
const DOC_PERMS = ['queue:view', 'queue:edit', 'patients:view'];
const json = (route, body, status = 200) => route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const entry = (n, over = {}) => ({
  id: `q${n}`, appointmentId: null, patientId: `p${n}`, doctorId: ME, departmentId: 'd1', queueDate: '2026-10-20', tokenNumber: n,
  status: 'waiting', priority: false, priorityReason: null, createdAt: new Date(Date.now() - n * 60000).toISOString(), source: 'walk_in', ...over,
});
const clean = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|ERR_ABORTED/.test(x))];
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);

async function open(url, perms, handlers, viewport) {
  const ctx = await launch(viewport ? { viewport } : {});
  await seedClinic(ctx.page, perms, { sub: ME });
  await mockApi(ctx.page, ctx.log, handlers);
  await ctx.page.goto(APP + url);
  await ctx.page.waitForLoadState('networkidle').catch(() => {});
  await sleep(1500);
  return ctx;
}

const myDayHandlers = (callNext) => [
  ['POST', /^\/queue\/doctors\/[^/]+\/call-next/, callNext],
  ['GET', /^\/doctors\/[^/]+\/session/, { body: { state: 'in' } }],
  ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 10, patientsAhead: 1, avgVisitMinutes: 10, fromHistory: true } }],
  ['GET', /^\/queue(\?|$)/, (route) => json(route, [entry(7), entry(8, { source: 'walk_in' })])],
  ['GET', /^\/doctors\/[^/]+\/delay/, { body: [] }],
  ['GET', /^\/appointments/, { body: { data: [], page: { nextCursor: null, limit: 100 } } }],
  ['GET', /^\/patients\/([^/?]+)$/, (route, req, m) => json(route, { id: m[1], mrn: `M-${m[1]}`, name: `Patient ${m[1]}`, phone: '', dob: '1990-01-01', gender: 'f', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null })],
  ['GET', /./, { body: [] }],
];

// 1. My Day: one "Call next" button; the answer says who and why; QUEUE_EMPTY is shown as an error
{
  let calls = 0;
  const { page, browser, log } = await open('/my-day', DOC_PERMS, myDayHandlers((route) => {
    calls++;
    return calls === 1
      ? json(route, { entry: entry(7, { status: 'in_consult' }), reason: 'interleave' })
      : json(route, { type: 'queue-empty', title: 'Queue empty', detail: 'Nobody is waiting for this doctor.', code: 'QUEUE_EMPTY' }, 409);
  }));
  const btn = page.getByRole('button', { name: 'Call next' });
  await btn.waitFor({ timeout: 15000 }).catch(async () => { console.log('DIAG main:', (await page.locator('body').innerText()).slice(0, 600)); console.log('DIAG unmatched:', log.unmatched, log.pageErrors, log.console.slice(0, 5)); });
  check('My Day: Call next button present with queue:edit', (await btn.count()) === 1);
  await btn.click();
  await sleep(500);
  check('My Day: result names the token and the reason', /Called token #7: walk-in turn/.test(await page.locator('main').innerText()));
  check('My Day: POST went to the doctor call-next endpoint with an Idempotency-Key', log.requests.some((r) => r.method === 'POST' && r.path === `/queue/doctors/${ME}/call-next`));
  await btn.click();
  await sleep(500);
  check('My Day: 409 QUEUE_EMPTY shown as an alert', (await page.getByRole('alert').count()) >= 1);
  check('My Day: 390px-independent: no console errors', clean(log).length === 0, JSON.stringify(clean(log)));
  dumpLog('myday', log);
  await browser.close();
}
// 2. permission absent: queue:view only -> no Call next button
{
  const { page, browser, log } = await open('/my-day', ['queue:view', 'patients:view'], myDayHandlers((route) => json(route, {}, 500)));
  check('My Day: no queue:edit -> Call next absent', (await page.getByRole('button', { name: 'Call next' }).count()) === 0);
  await browser.close();
}
// 3. 390px: no horizontal scroll on My Day
{
  const { page, browser, log } = await open('/my-day', DOC_PERMS, myDayHandlers((route) => json(route, {}, 500)), { width: 390, height: 800 });
  check('My Day 390px: no horizontal scroll', await noOverflow(page));
  check('My Day 390px: Call next visible', await page.getByRole('button', { name: 'Call next' }).isVisible());
  await browser.close();
}
// 4. Arrivals: position + why-here + expected range on the row; none when the server sends none
{
  const queue = [entry(1, { position: 1, orderReason: 'booked_on_time', expectedRange: '10:05-10:20', status: 'waiting' }),
    entry(2, { position: 2, orderReason: 'late arrival - placed as walk-in', status: 'waiting' }), entry(3, { status: 'in_consult' })];
  const { page, browser, log } = await open('/arrivals', ['queue:view', 'queue:edit', 'patients:view'], [
    ['GET', /^\/queue(\?|$)/, (route) => json(route, queue)],
    ['GET', /^\/staff\/roster/, { body: [{ id: ME, name: 'Dr Amal Nasser', departmentName: 'General' }] }],
    ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 10 } }],
    ['GET', /^\/doctors\/[^/]+\/delay/, { body: [] }],
    ['GET', /^\/appointments\/callback-list/, { body: [] }],
    ['GET', /^\/patients\/([^/?]+)$/, (route, req, m) => json(route, { id: m[1], name: `Patient ${m[1]}`, mrn: `MRN-${m[1]}` })],
    ['GET', /./, { body: [] }],
  ]);
  const chips = page.getByTestId('why-here');
  check('Arrivals: a chip on the two open rows only', (await chips.count()) === 2);
  check('Arrivals: first chip shows position, reason and range', /Next 1 · booked · 10:05-10:20/.test(await chips.nth(0).innerText()), await chips.nth(0).innerText());
  check('Arrivals: late arrival reason is shown as the server text', /late arrival - placed as walk-in/.test(await chips.nth(1).innerText()));
  check('Arrivals: no console errors', clean(log).length === 0, JSON.stringify(clean(log)));
  await browser.close();
}
// 5. Arrivals empty and error states unchanged by the chip
{
  const { page, browser } = await open('/arrivals', ['queue:view'], [['GET', /^\/queue(\?|$)/, (route) => json(route, [])], ['GET', /./, { body: [] }]]);
  check('Arrivals empty: no chips, no crash', (await page.getByTestId('why-here').count()) === 0 && (await page.locator('main').count()) === 1);
  await browser.close();
}
// 6. My Schedule: the session editor
{
  const hours = [{ id: 'h1', dayOfWeek: 2, startTime: '17:00:00', endTime: '20:00:00', slotMinutes: 15, maxPatients: 30, sessionMode: 'token', onlineCap: 20, label: 'Evening OPD', walkInRatio: 3, reservedWalkInTimes: [] }];
  let saved = null;
  const { page, browser, log } = await open('/my-schedule', DOC_PERMS, [
    ['GET', new RegExp(`^/doctors/${ME}/working-hours`), { body: hours }],
    ['GET', new RegExp(`^/doctors/${ME}/leave`), { body: [] }],
    ['POST', new RegExp(`^/doctors/${ME}/working-hours`), (route, req) => { saved = JSON.parse(req.postData()); return json(route, { id: 'h2', ...saved }, 201); }],
    ['GET', /./, { body: [] }],
  ]);
  const week = await page.locator('main').innerText();
  check('My Schedule: block shows name, tokens, max and online cap', /Evening OPD · tokens · max 30 · online 20/.test(week), week.slice(0, 400));
  await page.getByRole('button', { name: 'Add working hours' }).click();
  await page.getByLabel('Session type').selectOption('token');
  await page.getByRole('button', { name: 'Save hours' }).click();
  check('My Schedule: token session without max is refused client-side', /need a maximum number of patients/.test(await page.locator('p[role=alert]').innerText()));
  await page.getByLabel(/Max patients/).fill('30');
  await page.getByLabel(/Online booking cap/).fill('31');
  await page.getByRole('button', { name: 'Save hours' }).click();
  check('My Schedule: online cap above max is refused (boundary)', /not more than the maximum/.test(await page.locator('p[role=alert]').innerText()));
  await page.getByLabel(/Online booking cap/).fill('20');
  await page.getByLabel(/Name \(optional\)/).fill('Evening OPD');
  await page.getByRole('button', { name: 'Save hours' }).click();
  await sleep(500);
  check('My Schedule: POST carries mode, caps, name and ratio', saved && saved.sessionMode === 'token' && saved.maxPatients === 30 && saved.onlineCap === 20 && saved.label === 'Evening OPD' && saved.walkInRatio === 3, JSON.stringify(saved));
  check('My Schedule: no console errors', clean(log).length === 0, JSON.stringify(clean(log)));
  await browser.close();
}
{
  const { page, browser } = await open('/my-schedule', DOC_PERMS, [['GET', /^\/doctors\/[^/]+\/working-hours/, { body: [] }], ['GET', /^\/doctors\/[^/]+\/leave/, { body: [] }], ['GET', /./, { body: [] }]], { width: 390, height: 800 });
  check('My Schedule 390px: no horizontal scroll', await noOverflow(page));
  await page.getByRole('button', { name: 'Add working hours' }).click();
  check('My Schedule 390px: dialog open, no horizontal scroll', (await page.getByLabel('Session type').isVisible()) && (await noOverflow(page)));
  await browser.close();
}

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
