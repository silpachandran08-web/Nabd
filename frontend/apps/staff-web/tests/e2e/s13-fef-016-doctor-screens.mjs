// FEF-016: doctor screens on the shared client. My Day request budget (batched patients), prefetch of the next
// patient's visit, abort of a superseded visit's reads, note PATCH through the api client, My Schedule and Follow-ups.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3709 node tests/e2e/s13-fef-016-doctor-screens.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3000';
const DOC = '33333333-3333-3333-3333-333333333331';
const DOCTOR = ['queue:view', 'queue:edit', 'patients:view', 'clinical:view', 'clinical:edit'];
const NOW = new Date().toISOString();
const entry = (n, status, over = {}) => ({
  id: `q${n}`, appointmentId: null, patientId: `p${n}`, doctorId: DOC, departmentId: 'd1', queueDate: '2026-10-21', tokenNumber: n,
  status, priority: false, priorityReason: null, createdAt: NOW, source: 'walk_in', presentingComplaint: 'Cough', ...over,
});
const patient = (id) => ({ id, mrn: `M-${id}`, name: `Pat ${id}`, phone: '', dob: '1990-01-01', gender: 'female', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null });
const note = (n, text) => ({ id: `n${n}`, queueEntryId: `q${n}`, patientId: `p${n}`, doctorId: DOC, subjective: text, objective: '', assessment: '', plan: '', diagnosis: '', status: 'draft', signedAt: null, updatedAt: '2026-10-21T08:00:00Z' });
const json = (route, body, status = 200) => route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const dataReqs = (log) => log.requests.filter((r) => !/^\/(setup\/|schedule\/day)/.test(r.path));
const count = (log, re, method = 'GET') => log.requests.filter((r) => r.method === method && re.test(r.path)).length;
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const cleanConsole = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|ERR_ABORTED|preloaded using link preload/.test(x))];

function handlers(s) {
  return [
    ...(s.extra ?? []),
    ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 10, patientsAhead: 2, avgVisitMinutes: 12, basedOnHistory: true } }],
    ['GET', /^\/queue(\?|$)/, (route) => json(route, s.queue)],
    ['GET', /^\/appointments\?/, (route) => json(route, { data: s.appts ?? [], page: { nextCursor: null, limit: 100 } })],
    ['GET', /^\/appointments\/follow-ups/, (route) => json(route, { data: [], page: { nextCursor: null, limit: 20 } })],
    ['GET', /^\/doctors\/[^/]+\/delay/, { body: [] }],
    ['GET', /^\/clinical\/conditions\/due/, { body: [] }],
    ['GET', /^\/patients\/batch/, (route, req) => {
      const ids = new URL(req.url()).searchParams.get('ids').split(',');
      return json(route, ids.map(patient));
    }],
    ['GET', /^\/patients\/([^/?]+)$/, (route, req, m) => json(route, patient(m[1]))],
    ['GET', /^\/departments\/d1\/flow/, { body: [{ stepType: 'vitals' }, { stepType: 'consult' }] }],
    ['GET', /^\/clinical\/notes\/q(\d+)$/, (route, req, m) => s.notes ? s.notes(route, m[1]) : json(route, note(m[1], `note of q${m[1]}`))],
    ['GET', /^\/clinical\/(prescriptions|vitals)\//, { status: 404, body: {} }],
    ['PATCH', /^\/queue\/q\d+\/status$/, (route) => json(route, {})],
    ['GET', /./, { body: [] }],
  ];
}
async function open(path, s, { perms = DOCTOR, viewport, wait = 400 } = {}) {
  const ctx = await launch(viewport ? { viewport } : {});
  ctx.headers = [];
  ctx.page.on('request', (r) => { if (/\/v1\//.test(r.url()) && r.method() !== 'OPTIONS') ctx.headers.push({ method: r.method(), url: r.url(), h: r.headers() }); });
  ctx.aborted = [];
  ctx.page.on('requestfailed', (r) => { if (/ERR_ABORTED/.test(r.failure()?.errorText ?? '')) ctx.aborted.push(r.url()); });
  await seedClinic(ctx.page, perms, { sub: DOC });
  await mockApi(ctx.page, ctx.log, handlers(s));
  await ctx.page.goto(APP + path);
  await ctx.page.waitForLoadState('networkidle').catch(() => {});
  await sleep(wait);
  return ctx;
}

// ---- AC1: My Day with 30 queue entries and 40 appointments: <= 6 data requests, one batched patient call.
{
  const queue = Array.from({ length: 30 }, (_, i) => entry(i + 1, i === 0 ? 'vitals_done' : 'waiting'));
  const appts = Array.from({ length: 40 }, (_, i) => ({ id: `a${i + 1}`, patientId: `ap${i + 1}`, doctorId: DOC, startTime: NOW, endTime: NOW, status: 'booked', isFollowUp: false }));
  const { page, browser, log, headers } = await open('/my-day', { queue, appts }, { wait: 800 });
  const reqs = dataReqs(log);
  check('AC1 My Day (30 queue + 40 appointments) makes <= 6 data requests', reqs.length <= 6, `${reqs.length}: ${reqs.map((r) => r.path.slice(0, 40)).join(' | ')}`);
  const batch = log.requests.filter((r) => /^\/patients\/batch/.test(r.path));
  check('AC1 exactly one batched patient call carrying all 70 distinct patients', batch.length === 1 && decodeURIComponent(batch[0].path).split('ids=')[1].split(',').length === 70, `calls=${batch.length}`);
  check('AC1 zero per-patient GET /patients/{id}', count(log, /^\/patients\/[^/?]+$/) === 0);
  const text = await page.locator('main').innerText();
  check('AC1 names from the batch are shown (queue and appointments)', text.includes('Pat p2') && text.includes('Pat ap5'), '');
  check('AC1 requests go through the api client (X-Request-Id on every call)', headers.filter((h) => /\/(queue|appointments|patients)/.test(h.url)).every((h) => h.h['x-request-id']));
  check('390px-agnostic: desktop no horizontal scroll', await noOverflow(page));
  check('zero console/page errors (My Day)', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  dumpLog('myday', log);

  // ---- AC2: hover the next patient, the visit context is read ahead; Start consultation then renders from the cache.
  const notesBefore = count(log, /^\/clinical\/notes\/q1$/);
  check('prefetch: nothing read for q1 before pointing at it', notesBefore === 0, `x${notesBefore}`);
  await page.getByRole('link', { name: /Start consultation/ }).first().hover();
  await sleep(1200);
  check('AC2 hover reads note, prescription and signing authority ahead', count(log, /^\/clinical\/notes\/q1$/) === 1 && count(log, /^\/clinical\/prescriptions\/q1$/) === 1 && count(log, /^\/clinical\/prescriptions\/q1\/signing-authority$/) === 1);
  await browser.close();
}

// ---- AC2: end to end from My Day; slow notes (1.5 s) would show, the prefetched copy opens < 500 ms.
{
  const queue = [entry(1, 'vitals_done'), entry(2, 'waiting')];
  const slowNotes = async (route, n) => { await sleep(1500); try { await json(route, note(n, `note of q${n}`)); } catch { /* aborted */ } };
  const { page, browser, log } = await open('/my-day', { queue, notes: slowNotes }, { wait: 600 });
  const link = page.getByRole('link', { name: /Start consultation/ }).first();
  await link.focus(); // keyboard focus is enough (no mouse)
  await sleep(2200);
  const notesAfterWarm = count(log, /^\/clinical\/notes\/q1$/);
  await link.click();
  await page.getByRole('button', { name: 'Start consultation' }).waitFor({ timeout: 8000 });
  await sleep(300);
  const t0 = Date.now();
  await page.getByRole('button', { name: 'Start consultation' }).click();
  await page.getByRole('button', { name: 'Continue to Examine' }).click();
  await page.locator('textarea:not([disabled])').first().waitFor({ timeout: 5000 });
  const ms = Date.now() - t0;
  check('AC2 visit context renders < 500 ms from cache after Start consultation (notes endpoint is 1500 ms)', ms < 500, `${ms} ms`);
  check('AC2 the note was not read again when the visit opened', count(log, /^\/clinical\/notes\/q1$/) === notesAfterWarm && notesAfterWarm === 1, `x${count(log, /^\/clinical\/notes\/q1$/)}`);
  check('AC2 the opened note is the prefetched one', (await page.locator('textarea').first().inputValue()).includes('note of q1'));
  check('zero console/page errors (prefetch flow)', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  await browser.close();
}

// ---- Consult queue: hover / arrow key prefetch, one batched patient call, note PATCH through the client.
{
  const queue = [entry(1, 'in_consult'), entry(2, 'vitals_done'), entry(3, 'vitals_done')];
  const { page, browser, log, headers } = await open('/consult', { queue });
  check('consult queue: one batched patient call, no per-patient reads', count(log, /^\/patients\/batch/) === 1 && count(log, /^\/patients\/[^/?]+$/) === 0);
  check('consult queue: one flow lookup for the one department', count(log, /^\/departments\/d1\/flow/) === 1);
  const rows = page.locator('tbody tr');
  await rows.nth(1).hover();
  await sleep(500);
  check('hover on a row reads that visit ahead', count(log, /^\/clinical\/notes\/q2$/) === 1);
  await rows.nth(1).getByRole('button').focus();
  await page.keyboard.press('ArrowDown');
  await sleep(500);
  check('ArrowDown moves focus to the next row and reads that visit ahead', count(log, /^\/clinical\/notes\/q3$/) === 1 && (await page.evaluate(() => document.activeElement?.closest('tr')?.rowIndex)) === 3);

  // note PATCH via the api client: edit, then switch patient (saves first).
  await rows.nth(0).getByRole('button', { name: 'Resume' }).click();
  await page.getByRole('button', { name: 'Continue to Examine' }).click();
  const box = page.locator('textarea:not([disabled])').first();
  await box.waitFor({ timeout: 5000 });
  await box.fill('cough for three days');
  const patchSeen = [];
  await page.route(/\/v1\/clinical\/notes\/q1$/, async (route) => {
    if (route.request().method() !== 'PATCH') return route.fallback();
    patchSeen.push(route.request().postData());
    return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify(note(1, 'cough for three days')) });
  });
  await page.locator('aside[aria-label="Today\'s patients"] button').nth(1).click();
  await sleep(800);
  const patch = headers.find((h) => h.method === 'PATCH' && /\/clinical\/notes\/q1$/.test(h.url));
  check('autosave PATCH carries the edited text', patchSeen.length >= 1 && JSON.parse(patchSeen[0]).subjective === 'cough for three days', patchSeen[0]);
  check('autosave PATCH goes through the api client (Idempotency-Key + X-Request-Id + bearer)', !!patch && !!patch.h['idempotency-key'] && !!patch.h['x-request-id'] && /^Bearer /.test(patch.h.authorization ?? ''));
  check('zero console/page errors (consult)', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  await browser.close();
}

// ---- AC4: a slow /clinical/notes answer for a visit that is no longer open is aborted and never shown.
{
  const queue = [entry(1, 'in_consult'), entry(2, 'in_consult')];
  const notes = async (route, n) => {
    if (n === '1') { await sleep(3500); try { await json(route, note(1, 'LATE NOTE OF Q1')); } catch { /* aborted by the page */ } return; }
    return json(route, note(2, 'note of q2'));
  };
  const { page, browser, log, aborted } = await open('/consult', { queue, notes });
  await page.locator('tbody tr').nth(0).getByRole('button', { name: 'Resume' }).click();
  await sleep(400); // q1's note is now in flight (3.5 s)
  await page.locator('aside[aria-label="Today\'s patients"] button').nth(1).click();
  await page.getByRole('button', { name: 'Continue to Examine' }).click();
  await page.locator('textarea:not([disabled])').first().waitFor({ timeout: 5000 });
  check('AC4 the superseded visit\'s note request was cancelled (aborted in the browser)', aborted.some((u) => /\/clinical\/notes\/q1$/.test(u)), aborted.join(','));
  await sleep(3600);
  const body = await page.locator('main').innerText();
  const area = await page.locator('textarea').first().inputValue();
  check('AC4 the late answer is discarded: the open visit still shows its own note', area.includes('note of q2') && !body.includes('LATE NOTE OF Q1') && !area.includes('LATE'), area);
  check('AC4 no error is shown for the cancelled read', (await page.getByRole('alert').count()) === 0);
  await browser.close();
}

// ---- My Day states: permission-absent, empty, error, 390px.
{
  const { page, browser, log } = await open('/my-day', { queue: [entry(1, 'waiting')] }, { perms: ['queue:view'], viewport: { width: 390, height: 800 } });
  check('no patients:view: no patient read at all, token shown instead of a name', count(log, /^\/patients\//) === 0 && (await page.locator('main').innerText()).includes('T-1'));
  check('no clinical:view: chronic reviews absent and not requested', count(log, /^\/clinical\/conditions\/due/) === 0 && (await page.getByText('Chronic reviews due').count()) === 0);
  check('390px: no horizontal scroll', await noOverflow(page));
  await browser.close();
}
{
  const { page, browser } = await open('/my-day', { queue: [] });
  check('empty queue shows the empty state', await page.getByText('No patients in your queue today.').count() === 1);
  await browser.close();
}
{
  const { page, browser } = await open('/my-day', { queue: [], extra: [['GET', /^\/queue(\?|$)/, { status: 500, body: { title: 'x', detail: 'db down' } }]] });
  check('queue 500 shows the error state with retry', await page.getByText(/Couldn't load today's queue/).count() === 1 && await page.getByRole('button', { name: /retry|try again/i }).count() >= 1);
  await browser.close();
}

// ---- My Schedule on the client.
{
  const hours = [{ id: 'h1', dayOfWeek: 1, startTime: '09:00:00', endTime: '13:00:00', slotMinutes: 15, maxPatients: null }];
  const state = { queue: [], extra: [
    ['GET', /^\/doctors\/[^/]+\/working-hours/, (route) => json(route, hours)],
    ['GET', /^\/doctors\/[^/]+\/leave/, { body: [] }],
    ['POST', /^\/doctors\/[^/]+\/working-hours/, (route, req) => { hours.push({ id: 'h2', ...JSON.parse(req.postData()) }); return json(route, hours[1], 201); }],
  ] };
  const { page, browser, log, headers } = await open('/my-schedule', state, { viewport: { width: 390, height: 800 } });
  check('My Schedule: hours listed', (await page.locator('main').innerText()).includes('09:00'));
  check('My Schedule: 390px no horizontal scroll', await noOverflow(page));
  check('My Schedule: Apply leave present with queue:edit', await page.getByRole('button', { name: /Apply leave/ }).count() === 1);
  check('My Schedule: reads use the doctor id from the token', log.requests.some((r) => r.path === `/doctors/${DOC}/working-hours`) && log.requests.some((r) => r.path === `/doctors/${DOC}/leave`));
  check('My Schedule: reads go through the api client', headers.filter((h) => /working-hours|\/leave/.test(h.url)).every((h) => h.h['x-request-id']));
  check('zero console/page errors (My Schedule)', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  await browser.close();
}
{
  const { page, browser } = await open('/my-schedule', { queue: [], extra: [['GET', /^\/doctors\/[^/]+\/working-hours/, { status: 500, body: { title: 'x' } }], ['GET', /^\/doctors\/[^/]+\/leave/, { body: [] }]] }, { perms: ['queue:view'] });
  check('My Schedule: error state when hours fail', await page.getByText("Couldn't load your schedule.").count() === 1);
  check('My Schedule: Apply leave absent without queue:edit', await page.getByRole('button', { name: /Apply leave/ }).count() === 0);
  await browser.close();
}

// ---- Follow-ups read through the client.
{
  const { page, browser, log, headers } = await open('/follow-ups', { queue: [] });
  check('Follow-ups: list requested through the api client', headers.some((h) => /appointments\/follow-ups/.test(h.url) && h.h['x-request-id']));
  check('Follow-ups: zero console/page errors', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  await browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
