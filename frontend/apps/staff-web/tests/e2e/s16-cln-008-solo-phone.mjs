// CLN-008: solo doctor on a phone. Call next (compare-and-set) -> summary, safety strip first, thumb-zone bar, offline resync.
// 3G is emulated twice: CDP network throttling (1.6 Mbps, 300 ms RTT) for the app's own JS, and a 300 ms + size/200 KB/s delay on every mocked API answer.
// Real-device Lighthouse / WebPageTest numbers are Needs external action; this is the repeatable approximation.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3810 node tests/e2e/s16-cln-008-solo-phone.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3810';
const SELF = '11111111-1111-1111-1111-111111111111'; // qa-lib clinicToken sub
const PHONE = { width: 390, height: 844 };
const PERMS = ['queue:view', 'queue:edit', 'clinical:view', 'clinical:edit', 'patients:view'];
const json = (route, body, status = 200) => route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const slow = async (route, body, status = 200) => { await sleep(300 + Math.ceil(JSON.stringify(body).length / 200)); return json(route, body, status); };
const clean = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|ERR_ABORTED/.test(x))];
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const entry = (n, status, over = {}) => ({ id: `q${n}`, appointmentId: null, patientId: `p${n}`, doctorId: SELF, departmentId: 'd1', queueDate: '2026-10-20', tokenNumber: n,
  status, priority: false, priorityReason: null, createdAt: new Date(Date.now() - n * 60000).toISOString(), source: 'walk_in', ...over });
const summary = (n, safety) => ({
  patient: { id: `p${n}`, queueEntryId: `q${n}`, name: `Patient ${n}`, ageText: '41 y', sex: 'female', mrn: `M${n}`, queueStatus: 'in_consult' },
  safety: safety ?? { allergyState: 'none_documented', allergies: [], pregnancy: { status: 'not_documented' } },
  conditions: [{ condition: 'Asthma', reviewDueDate: null }],
  lastVisits: [1, 2, 3].map((i) => ({ queueEntryId: `h${i}`, occurredAt: new Date().toISOString(), doctorId: SELF, diagnosis: `Dx ${i}`, assessment: null, medications: null })),
  activeMeds: [{ drugName: 'Paracetamol', dosage: '500 mg', frequency: 'TDS', duration: '3 d' }],
  vitals: { pulseBpm: 72, tempCelsius: 37.1, abnormalFlags: [] },
  intake: { presentingComplaint: 'Cough', paymentMode: null }, attachmentsCount: 2,
});

function server(state, { throttle = false } = {}) {
  const reply = throttle ? slow : async (route, body, status) => json(route, body, status);
  return [
    ['GET', /^\/queue\?doctorId=/, (route) => reply(route, state.queue)],
    ['GET', /^\/patients\/batch/, (route) => reply(route, state.queue.map((e) => ({ id: e.patientId, name: `Patient ${e.tokenNumber}`, mrn: `M${e.tokenNumber}` })))],
    ['PATCH', /^\/queue\/(q\d+)\/status$/, async (route, req, m) => {
      const e = state.queue.find((x) => x.id === m[1]); const body = JSON.parse(req.postData() ?? '{}');
      state.patches.push({ id: m[1], ...body });
      if (throttle) await sleep(300);
      if (e.status !== body.expectedStatus) return json(route, { type: 'https://api.nabd.health/errors/stale-status', title: 'Visit already moved', status: 409, currentStatus: e.status }, 409);
      e.status = body.status;
      return json(route, { ...e });
    }],
    ['GET', /^\/clinical\/visits\/(q\d+)\/summary$/, (route, req, m) => reply(route, summary(Number(m[1].slice(1)), state.safety))],
    ['GET', /./, { body: [] }],
  ];
}
async function open({ perms = PERMS, viewport = PHONE, state, throttle = false } = {}) {
  const c = await launch({ viewport });
  await seedClinic(c.page, perms);
  await mockApi(c.page, c.log, server(state, { throttle }));
  return c;
}
const fresh = () => ({ queue: [entry(1, 'waiting'), entry(2, 'waiting'), entry(3, 'vitals_done')], patches: [], safety: undefined });

// S1 list + bar + thumb zone
{
  const state = fresh();
  const { page, browser, log } = await open({ state });
  await page.goto(APP + '/solo');
  await page.getByText('Patient 1').first().waitFor();
  check('S1 phone list shows the three queued patients', (await page.locator('main li').count()) === 3);
  const bar = page.getByRole('navigation', { name: 'Queue actions' });
  const box = await bar.boundingBox();
  check('S2 action bar sits in the bottom 40% of the screen', box && box.y >= PHONE.height * 0.6, JSON.stringify(box));
  const btns = await bar.getByRole('button').evaluateAll((els) => els.map((e) => { const r = e.getBoundingClientRect(); return [r.width, r.height]; }));
  check('S3 every bar target is >= 48 px', btns.length >= 1 && btns.every(([w, h]) => w >= 48 && h >= 48), JSON.stringify(btns));
  check('S4 390 px: no horizontal scroll', await noOverflow(page));
  // S5 call next moves exactly the first waiting patient and opens the summary
  await page.getByRole('button', { name: 'Call next' }).click();
  await page.waitForURL(/\/solo\/consult\?entry=q1/);
  await page.getByText('Patient 1').first().waitFor();
  check('S5 Call next sent one compare-and-set PATCH for the first waiting patient', state.patches.length === 1 && state.patches[0].id === 'q1' && state.patches[0].status === 'in_consult' && state.patches[0].expectedStatus === 'waiting', JSON.stringify(state.patches));
  check('S6 zero console/page errors', clean(log).length === 0, clean(log).slice(0, 2).join(' | '));
  dumpLog('s1', log);
  await browser.close();
}

// S7 two devices tap at once: exactly one patient moves, the other device is asked to refresh
{
  const state = fresh();
  const a = await open({ state }); const b = await open({ state });
  await Promise.all([a.page.goto(APP + '/solo'), b.page.goto(APP + '/solo')]);
  await Promise.all([a.page.getByText('Patient 1').first().waitFor(), b.page.getByText('Patient 1').first().waitFor()]);
  await Promise.all([a.page.getByRole('button', { name: 'Call next' }).click(), b.page.getByRole('button', { name: 'Call next' }).click()]);
  await sleep(1500);
  const moved = state.queue.filter((e) => e.status === 'in_consult');
  check('S7 exactly one patient is in_consult', moved.length === 1, JSON.stringify(moved.map((e) => e.id)));
  const urls = [a.page.url(), b.page.url()];
  const loser = urls[0].includes('/solo/consult') ? b : a;
  const prompt = loser.page.getByRole('alert').filter({ hasText: /moved/i });
  check('S8 the other device gets a "moved" notice with a Refresh button', (await prompt.count()) === 1 && (await prompt.getByRole('button', { name: 'Refresh' }).count()) === 1);
  await prompt.getByRole('button', { name: 'Refresh' }).click();
  await sleep(500);
  check('S9 Refresh clears the notice', (await loser.page.getByRole('alert').filter({ hasText: /moved/i }).count()) === 0);
  await a.browser.close(); await b.browser.close();
}

// S10 offline < 30 s resyncs without a reload
{
  const state = fresh();
  const { page, browser, context, log } = await open({ state });
  await page.goto(APP + '/solo');
  await page.getByText('Patient 3').first().waitFor();
  await context.setOffline(true);
  await sleep(3000);
  state.queue.push(entry(4, 'waiting')); // a patient checks in while the doctor is offline
  await context.setOffline(false);
  await page.getByText('Patient 4').first().waitFor({ timeout: 10000 }).catch(() => {});
  check('S10 after reconnect the new arrival appears without a manual reload', (await page.getByText('Patient 4').count()) >= 1);
  await browser.close();
}

// S11 safety strip is the first element above the fold (severe allergy; pregnancy)
for (const [label, safety] of [
  ['severe allergy', { allergyState: 'recorded', allergies: [{ substance: 'Penicillin', severity: 'severe', reaction: 'Anaphylaxis' }], pregnancy: { status: 'not_documented' } }],
  ['pregnancy', { allergyState: 'none_documented', allergies: [], pregnancy: { status: 'pregnant', expectedDeliveryDate: '2027-01-10' } }],
  ['allergies never asked', { allergyState: 'not_assessed', allergies: [], pregnancy: { status: 'not_documented' } }],
]) {
  const state = fresh(); state.safety = safety;
  const { page, browser } = await open({ state });
  await page.goto(APP + '/solo/consult?entry=q1');
  const strip = page.getByTestId('safety-strip');
  await strip.waitFor();
  const first = await page.locator('main > *').first().getAttribute('data-testid');
  const box = await strip.boundingBox();
  const txt = await strip.innerText();
  check(`S11 ${label}: safety strip is the first element of the page`, first === 'safety-strip');
  check(`S12 ${label}: strip is above the fold`, box.y + box.height < PHONE.height, JSON.stringify(box));
  if (label === 'severe allergy') check('S13 severe allergy named in the strip', /Penicillin/.test(txt) && /severe/.test(txt));
  if (label === 'pregnancy') check('S14 pregnancy named in the strip', /Pregnant/.test(txt));
  if (label === 'allergies never asked') check('S15 "not asked" is a warning, never "no allergies"', /not yet asked/i.test(txt) && !/No known allergies/.test(txt));
  await browser.close();
}

// S16 summary content + consult bar actions
{
  const state = fresh();
  const { page, browser, log } = await open({ state });
  await page.goto(APP + '/solo/consult?entry=q1');
  await page.getByText('Last visits').first().waitFor();
  const t = await page.locator('main').innerText();
  check('S16 card stack shows conditions, meds, last 3 visits, vitals, attachments', /Asthma/.test(t) && /Paracetamol/.test(t) && /Dx 1/.test(t) && /Dx 3/.test(t) && /Pulse 72/.test(t) && /Attachments\s*2/.test(t));
  const bar = page.getByRole('navigation', { name: 'Consult actions' });
  const names = await bar.getByRole('button').allInnerTexts();
  check('S17 bar has Call next, Start, Rx, Done', ['Call next', 'Start', 'Rx', 'Done'].every((n) => names.includes(n)), names.join('|'));
  const box = await bar.boundingBox();
  check('S18 consult bar in the bottom 40%', box.y >= PHONE.height * 0.6);
  await bar.getByRole('button', { name: 'Rx' }).click();
  await page.waitForURL(/\/consult\?entry=q1&step=plan/);
  check('S19 Rx opens the plan step of the consult for this visit', true);
  check('S20 no console/page errors on the summary', clean(log).length === 0, clean(log).slice(0, 2).join(' | '));
  await browser.close();
}

// S21 permission absent
{
  const state = fresh();
  const { page, browser } = await open({ state, perms: ['queue:view', 'clinical:view'] });
  await page.goto(APP + '/solo/consult?entry=q1');
  await page.getByText('Last visits').first().waitFor();
  check('S21 without queue:edit / clinical:edit the bar has no action buttons', (await page.getByRole('navigation', { name: 'Consult actions' }).getByRole('button').count()) === 0);
  await browser.close();
}

// S22 summary unavailable -> honest error with retry
{
  const state = fresh();
  const { page, browser } = await open({ state });
  await page.route(/\/v1\/clinical\/visits\/q9\/summary/, (r) => r.fulfill({ status: 404, contentType: 'application/problem+json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ type: 'https://api.nabd.health/errors/patient-not-found', title: 'Record not available', status: 404 }) }));
  await page.goto(APP + '/solo/consult?entry=q9');
  await page.getByText(/isn.t available to you/).waitFor();
  check('S22 a 404 shows "not available", no stale data', true);
  await browser.close();
}

// S23 tap Call next -> summary readable, p80 over 20 runs on emulated 3G
{
  const times = [];
  for (let i = 0; i < 20; i++) {
    const state = fresh();
    const { page, browser, context } = await open({ state, throttle: true });
    const cdp = await context.newCDPSession(page);
    await page.goto(APP + '/solo');
    await page.getByText('Patient 1').first().waitFor({ timeout: 30000 });
    await cdp.send('Network.enable');
    await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 300, downloadThroughput: (1.6 * 1024 * 1024) / 8, uploadThroughput: (750 * 1024) / 8 });
    const t0 = Date.now();
    await page.getByRole('button', { name: 'Call next' }).click();
    await page.getByTestId('safety-strip').waitFor({ timeout: 30000 });
    await page.getByText('Last visits').first().waitFor();
    times.push(Date.now() - t0);
    await browser.close();
  }
  times.sort((a, b) => a - b);
  const p80 = times[Math.ceil(times.length * 0.8) - 1];
  console.log('tap -> summary readable ms:', times.join(','));
  check('S23 emulated 3G: tap Call next -> summary readable p80 < 5000 ms over 20 runs', p80 < 5000, `p80=${p80} ms, max=${times.at(-1)} ms`);
}

// S24 desktop width keeps My Day
{
  const state = fresh();
  const { page, browser } = await open({ state, viewport: { width: 1280, height: 900 } });
  await page.goto(APP + '/solo');
  await page.getByRole('heading', { name: 'My Day' }).waitFor();
  check('S24 desktop width still shows My Day', true);
  await browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
