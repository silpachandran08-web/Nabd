// S12 QA-6 (independent) FEF-015: Arrivals + Nursing on TanStack Query. Counts REAL browser network requests
// (page.on('request'), so aborted/superseded requests count too), tab hide/show, stale-time boundaries via a
// Date.now skew, optimistic "sent for vitals" with 409 / offline / 500 rollback, poll-during-mutation, corrected
// registry name on focus without reload, and the Nursing request budget at 40 and 100 entries.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3816 node tests/e2e/s12qa-fef015.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3000';
const DOCS = ['33333333-3333-3333-3333-333333333331', '33333333-3333-3333-3333-333333333332', '33333333-3333-3333-3333-333333333333'];
const RECEPTION = ['queue:view', 'queue:edit', 'queue:create', 'patients:view'];
const API_RE = /^(http:\/\/localhost:8080|https:\/\/nabd-f0zb\.onrender\.com)\/v1(\/[^?#]*)(\?[^#]*)?/;
const entry = (n, status, patientId = `p${n}`, doctorId = DOCS[0]) => ({
  id: `q${n}`, appointmentId: null, patientId, doctorId, departmentId: 'd1', queueDate: '2026-10-20', tokenNumber: n,
  status, priority: false, priorityReason: null, createdAt: new Date().toISOString(), source: 'walk_in',
});
const json = (route, body, status = 200) => route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const cleanConsole = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|ERR_ABORTED|ERR_INTERNET_DISCONNECTED|net::ERR/.test(x))];

/** Every request the browser issues to the API host (network level, includes cancelled ones). */
function netLog(page) {
  const net = [];
  page.on('request', (r) => {
    const m = r.url().match(API_RE);
    if (m && r.method() !== 'OPTIONS') net.push({ method: r.method(), path: m[2] + (m[3] ?? ''), t: Date.now() });
  });
  return net;
}
const n = (net, re, method = 'GET', since = 0) => net.filter((r) => r.t >= since && r.method === method && re.test(r.path)).length;

// Tab switch: hidden then visible, bubbling to window (TanStack focusManager listens on window).
const setVis = (page, v) => page.evaluate((v) => {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => v });
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => v === 'hidden' });
  document.dispatchEvent(new Event('visibilitychange', { bubbles: true }));
}, v);
const refocus = async (page) => { await setVis(page, 'hidden'); await sleep(50); await setVis(page, 'visible'); };

function arrivalsHandlers(state, extra = []) {
  return [
    ...extra,
    ['GET', /^\/queue(\?|$)/, (route) => json(route, state.queue)],
    ['GET', /^\/staff\/roster/, { body: state.roster ?? [{ id: DOCS[0], name: 'Dr Amal Nasser', departmentName: 'General' }] }],
    ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 10 } }],
    ['GET', /^\/doctors\/[^/]+\/delay/, { body: [] }],
    ['GET', /^\/appointments\/callback-list/, { body: [] }],
    ['GET', /^\/patients\/([^/?]+)$/, (route, req, m) => json(route, { id: m[1], name: state.names[m[1]] ?? `Patient ${m[1]}`, mrn: `MRN-${m[1]}` })],
    ['GET', /./, { body: [] }],
  ];
}

async function open(path, { perms = RECEPTION, state, extra = [], viewport, skew = false } = {}) {
  const ctx = await launch(viewport ? { viewport } : {});
  if (skew) await ctx.page.addInitScript(() => { const real = Date.now.bind(Date); window.__skew = 0; Date.now = () => real() + window.__skew; });
  await seedClinic(ctx.page, perms);
  ctx.net = netLog(ctx.page);
  ctx.docs = 0;
  ctx.page.on('request', (r) => { if (r.resourceType() === 'document') ctx.docs++; });
  await mockApi(ctx.page, ctx.log, state ? arrivalsHandlers(state, extra) : extra);
  await ctx.page.goto(APP + path);
  await ctx.page.waitForLoadState('networkidle').catch(() => {});
  await sleep(600);
  return ctx;
}

// ---------- AC1: one /queue for page + badge ----------
{
  const state = { queue: [entry(1, 'waiting'), entry(2, 'checked_in'), entry(3, 'waiting', 'p1'), entry(4, 'checkout_pending')], names: { p1: 'Layla Haddad' } };
  const { page, browser, log, net } = await open('/arrivals', { state });
  check('AC1 direct open: exactly 1 GET /queue on the network (board + badge)', n(net, /^\/queue$/) === 1, `x${n(net, /^\/queue$/)}`);
  check('AC1 badge shows 3 waiting from that response', /\b3\b/.test(await page.locator('nav a[href="/arrivals"]').first().innerText()));
  check('AC1 board shows 4 rows', (await page.locator('tbody tr').count()) === 4);
  check('AC1 patient shared by two rows fetched once', n(net, /^\/patients\/p1$/) === 1, `x${n(net, /^\/patients\/p1$/)}`);
  check('AC1 zero console errors', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  dumpLog('ac1', log);
  await browser.close();
}
{
  // 40 rows, 40 patients, 3 doctors: still one /queue; one request per patient, none repeated.
  const queue = Array.from({ length: 40 }, (_, i) => entry(i + 1, i % 3 ? 'waiting' : 'checked_in', `p${i + 1}`, DOCS[i % 3]));
  const roster = DOCS.map((id, i) => ({ id, name: `Dr ${i + 1}`, departmentName: 'General' }));
  const { browser, net, log } = await open('/arrivals', { state: { queue, names: {}, roster } });
  const per = {};
  for (const r of net) if (/^\/patients\/p\d+$/.test(r.path)) per[r.path] = (per[r.path] ?? 0) + 1;
  check('AC1 boundary 40 rows/3 doctors: exactly 1 GET /queue', n(net, /^\/queue$/) === 1, `x${n(net, /^\/queue$/)}`);
  check('AC1 boundary: each of 40 patients requested exactly once', Object.keys(per).length === 40 && Object.values(per).every((v) => v === 1), `${Object.keys(per).length} patients, max ${Math.max(...Object.values(per))}`);
  console.log(`  [info] arrivals 40 rows initial API requests: ${net.length} (wait-estimate x${n(net, /^\/queue\/wait-estimate/)}, delay x${n(net, /^\/doctors\//)})`);
  dumpLog('ac1-40', log);
  await browser.close();
}
{
  // Client-side navigation from another page to Arrivals via the sidebar: the badge already holds the query.
  const state = { queue: [entry(1, 'waiting')], names: { p1: 'Layla Haddad' } };
  let done = false;
  for (const from of ['/patients', '/schedule', '/billing', '/appointments']) {
    const nav = await open(from, { state });
    const { page, browser, net, log } = nav;
    const link = page.locator('nav a[href="/arrivals"]').first();
    if (!(await link.count())) { console.log(`  [info] ${from}: no sidebar link to /arrivals`); await browser.close(); continue; }
    const before = n(net, /^\/queue$/);
    const t0 = Date.now();
    await link.click();
    await page.waitForURL(/\/arrivals/);
    await page.waitForLoadState('networkidle').catch(() => {});
    await sleep(800);
    const during = n(net, /^\/queue$/, 'GET', t0);
    check(`AC1 sidebar navigation ${from} -> Arrivals: exactly 1 GET /queue for page + badge`, during === 1, `x${during} (badge before nav: ${before})`);
    check('AC1 sidebar navigation is client-side (no new document)', nav.docs === 1, `documents: ${nav.docs}`);
    dumpLog('ac1-nav', log);
    await browser.close();
    done = true;
    break;
  }
  if (!done) check('AC1 sidebar navigation: a start page with an Arrivals link was found', false);
}

// ---------- AC2 + AC4: focus refetches stale only; corrected name on focus without reload ----------
{
  const state = { queue: [entry(1, 'waiting'), entry(2, 'waiting')], names: { p1: 'Layla Haddad', p2: 'Omar Saleh' } };
  const ctx = await open('/arrivals', { state, skew: true });
  const { page, browser, net, log } = ctx;
  await page.evaluate(() => { window.__noReload = true; });
  const docs0 = ctx.docs;

  // Visible, no focus change, 2 s: nothing refetches (no focus storm).
  let t = Date.now(); await sleep(2000);
  check('AC2 steady visible tab: no extra requests within 2 s', net.filter((r) => r.t >= t).length === 0, `${net.filter((r) => r.t >= t).map((r) => r.path).join(',')}`);

  state.names.p1 = 'Layla Haddad-Corrected';
  t = Date.now(); await refocus(page); await sleep(1500);
  check('AC2 focus: stale /queue refetched exactly once', n(net, /^\/queue$/, 'GET', t) === 1, `+${n(net, /^\/queue$/, 'GET', t)}`);
  check('AC2 focus: stale patient p1 refetched exactly once', n(net, /^\/patients\/p1$/, 'GET', t) === 1, `+${n(net, /^\/patients\/p1$/, 'GET', t)}`);
  check('AC2 focus: stale wait-estimate refetched exactly once', n(net, /^\/queue\/wait-estimate/, 'GET', t) === 1, `+${n(net, /^\/queue\/wait-estimate/, 'GET', t)}`);
  check('AC2 focus: fresh roster (10 min) NOT refetched', n(net, /^\/staff\/roster/, 'GET', t) === 0, `+${n(net, /^\/staff\/roster/, 'GET', t)}`);
  check('AC2 focus: fresh callback list (5 min) NOT refetched', n(net, /^\/appointments\/callback-list/, 'GET', t) === 0);
  check('AC4 corrected registry name shows after focus', (await page.locator('main').innerText()).includes('Layla Haddad-Corrected'));
  check('AC4 no full reload (same document, window state kept)', ctx.docs === docs0 && await page.evaluate(() => window.__noReload === true));

  // Boundary: 6 min later the 5-min callback list is stale, the 10-min roster is not.
  await page.evaluate(() => { window.__skew = 6 * 60_000; });
  t = Date.now(); await refocus(page); await sleep(1500);
  check('AC2 boundary +6 min: callback list (5 min) now stale -> refetched once', n(net, /^\/appointments\/callback-list/, 'GET', t) === 1, `+${n(net, /^\/appointments\/callback-list/, 'GET', t)}`);
  check('AC2 boundary +6 min: roster (10 min) still fresh -> not refetched', n(net, /^\/staff\/roster/, 'GET', t) === 0, `+${n(net, /^\/staff\/roster/, 'GET', t)}`);
  // 11 min: the roster is stale too.
  await page.evaluate(() => { window.__skew = 11 * 60_000; });
  t = Date.now(); await refocus(page); await sleep(1500);
  check('AC2 boundary +11 min: roster now stale -> refetched exactly once', n(net, /^\/staff\/roster/, 'GET', t) === 1, `+${n(net, /^\/staff\/roster/, 'GET', t)}`);
  check('AC2 boundary +11 min: /queue still once per focus', n(net, /^\/queue$/, 'GET', t) === 1, `+${n(net, /^\/queue$/, 'GET', t)}`);

  // Hidden tab: polling pauses (POLL 15 s), no /queue while hidden for 17 s.
  await setVis(page, 'hidden');
  t = Date.now(); await sleep(17_000);
  check('AC2 hidden 17 s: 0 GET /queue (poll paused)', n(net, /^\/queue/, 'GET', t) === 0, `x${n(net, /^\/queue/, 'GET', t)}`);
  check('AC2 hidden 17 s: 0 patient refetches', n(net, /^\/patients\//, 'GET', t) === 0);
  t = Date.now(); await setVis(page, 'visible'); await sleep(1500);
  check('AC2 back visible after 17 s: /queue refetched once', n(net, /^\/queue$/, 'GET', t) === 1, `+${n(net, /^\/queue$/, 'GET', t)}`);
  check('AC2/AC4 zero console errors', cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  dumpLog('ac2', log);
  await browser.close();
}

// ---------- AC3: optimistic "sent for vitals" ----------
async function sendForVitals({ tag, status = 'waiting', answer, offline = false, viewport }) {
  const state = { queue: [entry(1, status)], names: { p1: 'Layla Haddad' } };
  let release; const gate = new Promise((r) => { release = r; });
  const sent = [];
  const ctx = await open('/arrivals', { state, viewport, extra: [
    ['PATCH', /^\/queue\/q1\/status$/, async (route, req) => {
      const body = JSON.parse(req.postData());
      sent.push(body);
      if (body.expectedStatus === 'checked_in') { state.queue = [{ ...state.queue[0], status: 'waiting' }]; return json(route, state.queue[0]); }
      await gate;
      const a = answer(state);
      return json(route, a.body, a.status);
    }],
  ] });
  const { page, context } = ctx;
  const row = page.locator('tbody tr').first();
  if (offline) await context.setOffline(true);
  const t0 = Date.now();
  await page.getByRole('button', { name: 'Send for vitals' }).first().click();
  let optimistic = false;
  try { await row.getByText('Vitals pending').waitFor({ timeout: 800 }); optimistic = true; } catch { /* checked below */ }
  check(`AC3 ${tag}: row shows Vitals pending immediately (<800 ms, before the server answers)`, optimistic, `${Date.now() - t0} ms`);
  return { ...ctx, row, sent, release, t0, state };
}

{ // 409 rollback + toast
  const r = await sendForVitals({ tag: '409', answer: () => ({ status: 409, body: { type: 'https://nabd/problems/stale-status', title: 'Visit already moved', detail: 'Visit already moved', currentStatus: 'in_consult' } }) });
  check('AC3 409: one PATCH, compare-and-set body', r.sent.length === 1 && r.sent[0].status === 'vitals_pending' && r.sent[0].expectedStatus === 'waiting', JSON.stringify(r.sent));
  check('AC3 409: no token number change while optimistic', (await r.row.innerText()).includes('1'));
  const q0 = n(r.net, /^\/queue$/);
  r.release();
  let toast = false;
  try { await r.page.getByRole('alert').filter({ hasText: /Already moved to in consult/i }).waitFor({ timeout: 3000 }); toast = true; } catch { /* */ }
  check('AC3 409: error toast (role=alert) says it was already moved', toast);
  let rolled = false;
  try { await r.row.getByText('Waiting').waitFor({ timeout: 3000 }); rolled = true; } catch { /* */ }
  check('AC3 409: row rolled back to Waiting', rolled && !(await r.row.getByText('Vitals pending').isVisible()));
  await sleep(600);
  check('AC3 409: queue re-read after the failed move', n(r.net, /^\/queue$/) > q0);
  check('AC3 409: zero console errors', cleanConsole(r.log).length === 0, cleanConsole(r.log).slice(0, 2).join(' | '));
  dumpLog('ac3-409', r.log);
  await r.browser.close();
}
{ // offline (navigator.onLine false): what the receptionist sees, then what happens when the connection returns
  const r = await sendForVitals({ tag: 'offline', offline: true, answer: () => ({ status: 409, body: { type: 'https://nabd/problems/stale-status', title: 'moved', currentStatus: 'in_consult' } }) });
  await sleep(6000);
  const alerts = (await r.page.getByRole('alert').allInnerTexts().catch(() => [])).join(' | ');
  check('AC3 offline: error/offline message shown within 6 s', /reach the server|connection|offline/i.test(alerts), `alerts: "${alerts}"; row: "${(await r.row.innerText()).replace(/\s+/g, ' ')}"`);
  check('AC3 offline: row rolled back to Waiting within 6 s (not left optimistic)', await r.row.getByText('Waiting').isVisible());
  check('AC3 offline: server received no PATCH while offline', r.sent.length === 0, `${r.sent.length}`);
  await r.context.setOffline(false);
  await r.page.evaluate(() => window.dispatchEvent(new Event('online')));
  await sleep(1500);
  console.log(`  [info] offline: PATCHes sent after the connection returned: ${r.sent.length}`);
  r.release();
  await sleep(1500);
  console.log(`  [info] offline: after reconnect + 409 -> row "${(await r.row.innerText()).replace(/\s+/g, ' ')}", alerts "${(await r.page.getByRole('alert').allInnerTexts()).join(' | ')}"`);
  dumpLog('ac3-offline', r.log);
  await r.browser.close();
}
{ // network failure while online (request aborted): client retries, then rollback + connection message
  const state = { queue: [entry(1, 'waiting')], names: { p1: 'Layla Haddad' } };
  let patches = 0;
  const ctx = await open('/arrivals', { state, extra: [['PATCH', /^\/queue\/q1\/status$/, (route) => { patches++; return route.abort('failed'); }]] });
  const row = ctx.page.locator('tbody tr').first();
  await ctx.page.getByRole('button', { name: 'Send for vitals' }).first().click();
  let msg = false;
  try { await ctx.page.getByRole('alert').filter({ hasText: /reach the server/i }).waitFor({ timeout: 6000 }); msg = true; } catch { /* */ }
  check('AC3 network error: connection message shown', msg);
  check('AC3 network error: row rolled back to Waiting', await row.getByText('Waiting').isVisible());
  check('AC3 network error: bounded retries (<= 3 PATCH attempts)', patches >= 1 && patches <= 3, `${patches}`);
  await ctx.browser.close();
}
{ // 500 rollback
  const r = await sendForVitals({ tag: '500', answer: () => ({ status: 500, body: { title: 'Internal error', detail: 'boom' } }) });
  r.release();
  let rolled = false;
  try { await r.row.getByText('Waiting').waitFor({ timeout: 4000 }); rolled = true; } catch { /* */ }
  check('AC3 500: row rolled back to Waiting', rolled);
  check('AC3 500: an error alert is shown', (await r.page.getByRole('alert').count()) > 0);
  await r.browser.close();
}
{ // success: stays Vitals pending; a focus reload and a 15 s poll tick during the in-flight move do not paint the old status back
  const r = await sendForVitals({ tag: 'success+poll', answer: (state) => { state.queue = [{ ...state.queue[0], status: 'vitals_pending' }]; return { status: 200, body: state.queue[0] }; } });
  const tq = Date.now();
  await refocus(r.page);           // focus reload while the PATCH is held (server still says "waiting")
  await sleep(12_000);             // < the client's 15 s timeout; a 15 s poll tick may land in here too
  check('AC3 focus/poll during in-flight move: row still Vitals pending after 12 s', await r.row.getByText('Vitals pending').isVisible());
  console.log(`  [info] GET /queue while mutation in flight 12 s (incl. one focus): ${n(r.net, /^\/queue$/, 'GET', tq)}`);
  r.release();
  await sleep(1500);
  check('AC3 success: row stays Vitals pending after the server confirms', await r.row.getByText('Vitals pending').isVisible());
  check('AC3 success: no error alert', (await r.page.getByRole('alert').filter({ hasText: /Couldn|Already|too long/ }).count()) === 0);
  await r.browser.close();
}
{ // server slower than the 15 s client timeout: rollback + "check status" message, no auto-retry
  const r = await sendForVitals({ tag: 'timeout', answer: () => ({ status: 200, body: {} }) });
  let msg = false;
  try { await r.page.getByRole('alert').filter({ hasText: /taking too long/i }).waitFor({ timeout: 18_000 }); msg = true; } catch { /* */ }
  check('AC3 timeout (>15 s): "taking too long, check status" message', msg);
  await sleep(500);
  check('AC3 timeout: row rolled back to the re-read server status (Waiting)', await r.row.getByText('Waiting').isVisible());
  check('AC3 timeout: not auto-retried (1 PATCH)', r.sent.length === 1, `${r.sent.length}`);
  r.release();
  await r.browser.close();
}
{ // checked_in row: two hops in order; the second 409 rolls back to the server truth
  const r = await sendForVitals({ tag: 'checked_in 2-hop', status: 'checked_in', answer: () => ({ status: 409, body: { type: 'https://nabd/problems/stale-status', title: 'moved', currentStatus: 'in_consult' } }) });
  await sleep(300);
  check('AC3 2-hop: PATCHes in order checked_in->waiting, waiting->vitals_pending', r.sent.length === 2 && r.sent[0].expectedStatus === 'checked_in' && r.sent[1].expectedStatus === 'waiting', JSON.stringify(r.sent));
  r.release();
  let ok = false;
  try { await r.page.getByRole('alert').filter({ hasText: /Already moved/i }).waitFor({ timeout: 3000 }); ok = true; } catch { /* */ }
  check('AC3 2-hop: 409 on hop 2 shows the toast', ok);
  await sleep(800);
  check('AC3 2-hop: row shows the re-read server status (Waiting), not Vitals pending', await r.row.getByText('Waiting').isVisible() && !(await r.row.getByText('Vitals pending').isVisible()));
  await r.browser.close();
}
{ // double click / 390 px
  const r = await sendForVitals({ tag: '390px double-click', viewport: { width: 390, height: 800 }, answer: () => ({ status: 409, body: { type: 'https://nabd/problems/stale-status', title: 'moved', currentStatus: 'in_consult' } }) });
  await r.page.getByRole('button', { name: 'Send for vitals' }).first().click({ timeout: 500 }).catch(() => {});
  await sleep(300);
  check('AC3 double click: still exactly one PATCH', r.sent.length === 1, `${r.sent.length}`);
  r.release();
  await sleep(1200);
  check('AC3 390 px: no horizontal scroll with the toast', await noOverflow(r.page));
  await r.browser.close();
}

// ---------- AC6: Nursing request budget ----------
async function nursing(count, perms = ['queue:view', 'queue:edit', 'nursing:view', 'nursing:edit', 'clinical:view', 'clinical:create', 'patients:view']) {
  const items = Array.from({ length: count }, (_, i) => ({
    entry: { ...entry(i + 1, i % 3 ? 'vitals_pending' : 'in_consult', `p${i + 1}`, DOCS[i % 3]), priority: i % 10 === 0, priorityReason: i % 10 === 0 ? 'Severe pain' : null },
    patient: { id: `p${i + 1}`, name: `Patient ${i + 1}`, mrn: `M${i + 1}`, dob: '1980-01-01', gender: 'female', allergies: [], chronicConditions: [] },
    vitals: i % 3 ? null : { recordedAt: new Date().toISOString(), abnormalFlags: [] },
  }));
  const ctx = await open('/nursing', { perms, extra: [
    ['GET', /^\/nursing\/worklist$/, { body: { items } }],
    ['GET', /^\/staff\/roster/, { body: DOCS.map((id, i) => ({ id, name: `Dr ${i + 1}` })) }],
    ['GET', /^\/queue\?priority=true$/, { body: items.filter((x) => x.entry.priority).map((x) => x.entry) }],
    ['GET', /./, { body: [] }],
  ] });
  await sleep(1200);
  return { ...ctx, items };
}
for (const count of [40, 100]) {
  const { page, browser, net, log } = await nursing(count);
  const total = net.length;
  check(`AC6 Nursing ${count} entries: <= 8 API requests on load (network level)`, total <= 8, `${total}: ${net.map((r) => r.path).join(', ')}`);
  check(`AC6 Nursing ${count}: no per-row /patients or /clinical/vitals requests`, n(net, /^\/(patients|clinical\/vitals)\//) === 0);
  check(`AC6 Nursing ${count}: rows rendered`, (await page.getByText(/^Patient \d+$/).count()) > 0, `${await page.getByText(/^Patient \d+$/).count()}`);
  const t = Date.now(); await refocus(page); await sleep(1500);
  const after = net.filter((r) => r.t >= t);
  check(`AC6 Nursing ${count}: a focus costs <= 8 requests (no per-row fan-out)`, after.length <= 8 && after.every((r) => !/^\/(patients|clinical\/vitals)\//.test(r.path)), `${after.length}: ${after.map((r) => r.path).join(', ')}`);
  check(`AC6 Nursing ${count}: zero console errors`, cleanConsole(log).length === 0, cleanConsole(log).slice(0, 2).join(' | '));
  dumpLog(`ac6-${count}`, log);
  await browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
