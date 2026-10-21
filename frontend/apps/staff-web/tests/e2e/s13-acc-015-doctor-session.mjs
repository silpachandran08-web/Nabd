// S13 ACC-015: My Day session controls (Start/Pause/Resume/End) and Extend. Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3701 node s13-acc-015-doctor-session.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';
const SELF = '11111111-1111-1111-1111-111111111111';
const now = new Date().toISOString();
const inRoom = { id: 'e1', appointmentId: null, patientId: 'p1', doctorId: SELF, departmentId: 'dep1', queueDate: '2026-10-21', tokenNumber: 4, status: 'in_consult', priority: false, priorityReason: null, createdAt: now, source: 'walk_in' };
const base = (queue, session, extra = []) => [
  ...extra,
  ['GET', new RegExp(`^/doctors/${SELF}/session$`), session],
  ['GET', /^\/queue\?/, { body: queue }],
  ['GET', /^\/appointments\?/, { body: { data: [], page: { nextCursor: null, limit: 100 } } }],
  ['GET', /^\/doctors\/.*\/delay/, { body: [] }],
  ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5, patientsAhead: 0, avgVisitMinutes: 15, basedOnHistory: false } }],
  ['GET', /^\/patients\/p1/, { body: { name: 'Pat One', mrn: 'M1', phone: '+9665', allergies: [] } }],
];
async function run(tag, perms, handlers, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms); await mockApi(page, log, handlers);
    await page.goto(APP + '/my-day'); await sleep(2000); await fn({ page, log });
    check(`${tag} zero console/page errors`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0, JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const edit = ['queue:view', 'queue:edit', 'patients:view'];
const btn = (page, name) => page.getByRole('button', { name, exact: true });

let state = 'out';
const act = (route, req, m) => { const a = req.url().split('/session/')[1]; state = { start: 'in', pause: 'paused', resume: 'in', end: 'out' }[a]; return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessionId: 's1', state, since: now }) }); };
await run('happy', edit, base([inRoom], { body: { sessionId: null, state: 'out', since: null } }, [['POST', new RegExp(`^/doctors/${SELF}/session/`), act], ['POST', /^\/queue\/e1\/extend$/, { body: { queueEntryId: 'e1', extendedBy: 10 } }]]), async ({ page, log }) => {
  const sec = page.getByRole('region', { name: 'Session controls' });
  check('out: shows Out and only Start session', (await sec.innerText()).includes('Out') && (await btn(page, 'Start session').count()) === 1 && (await btn(page, 'Pause').count()) === 0);
  await btn(page, 'Start session').click(); await sleep(500);
  check('start: Doctor in, Pause and End offered, Start gone', (await sec.innerText()).includes('Doctor in') && (await btn(page, 'Pause').count()) === 1 && (await btn(page, 'End session').count()) === 1 && (await btn(page, 'Start session').count()) === 0);
  const startReq = log.requests.find((r) => r.method === 'POST' && /session\/start/.test(r.path));
  check('start: POST sent to /doctors/{self}/session/start', !!startReq);
  await btn(page, 'Pause').click(); await sleep(500);
  check('pause: Paused with Resume', (await sec.innerText()).includes('Paused') && (await btn(page, 'Resume').count()) === 1);
  await btn(page, 'Resume').click(); await sleep(400);
  await btn(page, 'End session').click(); await sleep(500);
  check('end: back to Out', (await sec.innerText()).includes('Out'));
  check('extend: 5, 10 and 15 offered on the consult in the room', (await btn(page, 'Extend 5 min').count()) === 1 && (await btn(page, 'Extend 10 min').count()) === 1 && (await btn(page, 'Extend 15 min').count()) === 1);
  await btn(page, 'Extend 10 min').click(); await sleep(500);
  const ext = log.requests.find((r) => r.method === 'POST' && /\/queue\/e1\/extend/.test(r.path));
  check('extend: POST body carries minutes 10', ext && JSON.parse(ext.body).minutes === 10, ext?.body);
});
await run('no consult -> no extend', edit, base([], { body: { sessionId: 's1', state: 'in', since: now } }), async ({ page }) => {
  check('empty: no Extend button without a consult in the room', (await page.getByRole('button', { name: /^Extend/ }).count()) === 0);
  check('in: Pause offered', (await btn(page, 'Pause').count()) === 1);
});
await run('permission absent', ['queue:view', 'patients:view'], base([inRoom], { body: { sessionId: 's1', state: 'in', since: now } }), async ({ page }) => {
  check('perm: no session controls at all without queue:edit (absent)', (await page.getByRole('region', { name: 'Session controls' }).count()) === 0 && (await page.getByRole('button', { name: /Pause|Extend|Start session/ }).count()) === 0);
});
await run('409 error', edit, base([], { body: { sessionId: 's1', state: 'in', since: now } }, [['POST', /\/session\/pause$/, { status: 409, body: { type: 'invalid-session-state', title: 'Invalid session state', detail: 'Cannot pause a session that is paused.', code: 'INVALID_SESSION_STATE' } }]]), async ({ page }) => {
  await btn(page, 'Pause').click(); await sleep(500);
  check('error: server detail shown in an alert', /Cannot pause a session that is paused/.test((await page.getByRole('alert').allInnerTexts()).join(' ')));
});
await run('session load error', edit, base([], { status: 500, body: { type: 'server-error', title: 'x' } }), async ({ page }) => {
  check('error: load failure says so', /Couldn.t load your session status/.test((await page.getByRole('alert').allInnerTexts()).join(' ')));
});
await run('390', edit, base([inRoom], { body: { sessionId: 's1', state: 'in', since: now } }), async ({ page }) => {
  const m = await page.evaluate(() => ({ dsw: document.documentElement.scrollWidth, dcw: document.documentElement.clientWidth }));
  check('390px: no horizontal page scroll', m.dsw <= m.dcw, JSON.stringify(m));
  const h = await btn(page, 'Pause').evaluate((e) => e.getBoundingClientRect().height);
  check('390px: control is at least 44px tall', h >= 44, String(h));
}, { width: 390, height: 800 });
const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
