// FEF-020 E2E: one shared SSE stream through the real BFF (/api/events) into the real screens, against a mock Core
// that speaks the ACC-021 contract (snapshot | delta | heartbeat, Last-Event-ID resume, 403 TOPIC_FORBIDDEN).
// Cookie-mode build required (EventSource authenticates with the BFF cookie):
//   NEXT_PUBLIC_AUTH_MODE=cookie pnpm exec next build && NABD_CORE_URL=http://localhost:13710/v1 node_modules/.bin/next start -p 3710
//   NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3710 node tests/e2e/s13-fef020-live-sse.mjs
import http from 'node:http';
import { launch, clinicToken, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const CORE_PORT = Number(process.env.CORE_PORT ?? 13710);
const DOC = '33333333-3333-3333-3333-333333333331';
const RECEPTION = ['queue:view', 'queue:edit', 'queue:create', 'patients:view'];
const entry = (n, status = 'waiting') => ({ id: `q${n}`, appointmentId: null, patientId: `p${n}`, doctorId: DOC, departmentId: 'd1', queueDate: '2026-10-20', tokenNumber: n, status, priority: false, priorityReason: null, createdAt: new Date().toISOString(), source: 'walk_in' });

// ---- mock Core -----------------------------------------------------------------------------------
const core = { queue: [entry(1)], streams: new Set(), opened: [], seq: 0, log: [], refuse: false, queueGets: 0 };
const server = http.createServer(async (req, res) => {
  const path = req.url.replace(/^\/v1/, '');
  if (req.method === 'POST' && path === '/auth/login') {
    let body = ''; for await (const ch of req) body += ch;
    const perms = /nopriv/.test(body) ? ['patients:view'] : RECEPTION;
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ accessToken: clinicToken(perms), refreshToken: `rt-${crypto.randomUUID()}`, expiresIn: 3000, refreshExpiresIn: 2592000 }));
  }
  const send = (status, obj) => { res.writeHead(status, { 'content-type': status >= 400 ? 'application/problem+json' : 'application/json' }); res.end(JSON.stringify(obj)); };
  if (path.startsWith('/events')) {
    if (core.refuse) return send(503, { title: 'down' });
    const topics = new URL(req.url, 'http://x').searchParams.get('topics');
    if (!/queue/.test(topics ?? '')) return send(403, { code: 'TOPIC_FORBIDDEN', status: 403 });
    const last = req.headers['last-event-id'] ?? null;
    core.opened.push({ last, at: Date.now(), cookie: req.headers.cookie ?? null });
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
    const s = { res, req };
    core.streams.add(s);
    req.on('close', () => core.streams.delete(s));
    if (last) core.log.filter((e) => e.id > Number(last)).forEach((e) => res.write(`event: delta\nid: ${e.id}\ndata: ${JSON.stringify(e.data)}\n\n`));
    else res.write(`event: snapshot\nid: ${core.seq}\ndata: ${JSON.stringify({ items: [] })}\n\n`);
    const hb = setInterval(() => res.write('event: heartbeat\ndata: {}\n\n'), 25_000); req.on('close', () => clearInterval(hb));
    return;
  }
  if (path.startsWith('/queue/wait-estimate')) return send(200, { estimatedMinutes: 10 });
  if (path.startsWith('/queue')) { core.queueGets++; return send(200, core.queue); }
  if (path.startsWith('/staff/roster')) return send(200, [{ id: DOC, name: 'Dr Amal Nasser', departmentName: 'General' }]);
  if (/^\/doctors\/[^/]+\/delay/.test(path)) return send(200, []);
  if (path.startsWith('/setup/policies') || path.startsWith('/appointments')) return send(200, []);
  if (path.startsWith('/setup/profile') || path.startsWith('/schedule/day')) return send(200, { timezone: 'Asia/Kolkata', region: 'IN' });
  const p = path.match(/^\/patients\/([^/?]+)/);
  if (p) return send(200, { id: p[1], name: `Patient ${p[1]}`, mrn: `MRN-${p[1]}` });
  return send(404, { title: 'Not found' });
});
await new Promise((r) => server.listen(CORE_PORT, r));
const push = (type, data) => { const id = ++core.seq; const d = { type, ...data }; core.log.push({ id, data: d }); core.streams.forEach((s) => s.res.write(`event: delta\nid: ${id}\ndata: ${JSON.stringify(d)}\n\n`)); return id; };
const checkIn = (n) => { core.queue = [...core.queue, entry(n)]; return push('queue.entry.updated.v1', { id: `q${n}`, v: 1 }); };
const killStreams = () => core.streams.forEach((s) => { s.res.destroy(); });

async function desk(email = 'r@qa.test', viewport) {
  const ctx = await launch(viewport ? { viewport } : {});
  const { page } = ctx;
  await page.goto(`${APP}/login`, { waitUntil: 'networkidle' });
  await page.getByLabel('Clinic code').fill('qa'); await page.getByLabel('Email').fill(email);
  await page.getByLabel('PIN', { exact: true }).fill('123456');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 15000 });
  await page.waitForLoadState('networkidle').catch(() => {});
  return ctx;
}
const open = async (c, path = '/arrivals') => { await c.page.goto(APP + path); await c.page.waitForLoadState('networkidle').catch(() => {}); };
const rows = (page) => page.locator('tbody tr').count();
const pill = (page) => page.locator('[role=status]').filter({ hasText: /Live|Reconnecting|Online|Offline/ }).first().innerText().catch(() => '');
async function until(fn, ms) { const t0 = Date.now(); while (Date.now() - t0 < ms) { if (await fn()) return Date.now() - t0; await sleep(40); } return -1; }
const clean = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|ERR_ABORTED|EventSource|net::ERR/.test(x))];

const A = await desk(); const B = await desk();
try {
  // 1 two desks: a check-in on the core side reaches both within 2 s; the pill says Live
  await open(A); await open(B);
  check('1a desk A shows the first patient', (await rows(A.page)) === 1);
  check('1b pill reads Live on both desks', await until(async () => /^\s*Live/.test(await pill(A.page)) && /^\s*Live/.test(await pill(B.page)), 4000) >= 0, await pill(A.page));
  check('1c one EventSource per browser (2 browsers -> 2 streams)', core.streams.size === 2, `streams=${core.streams.size}`);
  check('1d the BFF carried the cookie token, the browser cookie never reached Core', core.opened.every((o) => !o.cookie));
  const lat = [];
  for (let i = 2; i <= 9; i++) {
    const t0 = Date.now(); checkIn(i);
    const ms = await until(async () => (await rows(B.page)) === i, 2500);
    lat.push(ms);
  }
  check('1e 8 check-ins each appear on the other desk in < 2 s', lat.every((x) => x >= 0 && x < 2000), `ms=${lat.join(',')}`);
  check('1f the other desk also updated (desk A)', (await rows(A.page)) === 9);
  const getsBefore = core.queueGets; await sleep(16_500);
  check('1g no 15 s polling while live (no /queue GET in 16.5 s)', core.queueGets === getsBefore, `+${core.queueGets - getsBefore}`);

  // 2 four tabs in one browser: exactly one connection; closing the leader re-elects within 3 s
  const tabs = [A.page]; for (let i = 0; i < 3; i++) { const p = await A.context.newPage(); tabs.push(p); await p.goto(APP + '/arrivals'); await p.waitForLoadState('networkidle').catch(() => {}); }
  await sleep(800);
  const mine = core.opened.length;
  check('2a 4 tabs of browser A + 1 tab of browser B hold exactly 2 connections', core.streams.size === 2, `streams=${core.streams.size}`);
  const leaderIdx = 0; const opened0 = core.opened.length;
  await tabs[leaderIdx].close();
  const reel = await until(async () => core.opened.length > opened0 && core.streams.size === 2, 3000);
  check('2b closing the leader tab re-elects a leader (new stream) within 3 s', reel >= 0, `${reel} ms`);
  checkIn(10);
  check('2c a follower tab still updates after re-election', (await until(async () => (await rows(tabs[2])) === 10, 2500)) >= 0);
  for (const p of tabs.slice(1)) await p.close();
  void mine;

  // 3 chaos: Core refuses the stream, A stays on screen; Reconnecting after 5 s; 60 s fallback poll; resume with Last-Event-ID
  const pageA = await A.context.newPage(); await pageA.goto(APP + '/arrivals'); await pageA.waitForLoadState('networkidle').catch(() => {});
  await until(async () => /^\s*Live/.test(await pill(pageA)), 4000);
  core.refuse = true; killStreams();
  const t0 = Date.now();
  check('3a pill is NOT Reconnecting yet at ~3 s', (await sleep(3000), !/Reconnecting/.test(await pill(pageA))));
  const rec = await until(async () => /Reconnecting/.test(await pill(pageA)), 6000);
  check('3b pill shows Reconnecting about 5 s after the drop', rec >= 0 && Date.now() - t0 >= 4500, `${Date.now() - t0} ms since the drop`);
  const lastSeen = core.seq;
  checkIn(11); checkIn(12); // missed while down
  const gets0 = core.queueGets;
  const fb = await until(async () => core.queueGets > gets0 && (await rows(pageA)) === 12, 70_000);
  check('3c the 60 s fallback poll refreshed the screen while disconnected', fb >= 0, `${fb} ms after the missed events`);
  const opened1 = core.opened.length;
  core.refuse = false;
  checkIn(13);
  const back = await until(async () => /^\s*Live/.test(await pill(pageA)), 45_000);
  check('3d the stream resumed and the pill is Live again', back >= 0, `${back} ms`);
  check('3e the resume sent Last-Event-ID (no gap)', core.opened.slice(opened1).some((o) => o.last !== null), JSON.stringify(core.opened.slice(opened1).map((o) => o.last)));
  check('3f the event missed during the outage arrived on resume', (await until(async () => (await rows(pageA)) === 13, 3000)) >= 0, `lastSeen=${lastSeen}`);

  // 4 permissions: no queue:view / nursing:view = no stream at all
  const N = await desk('nopriv@qa.test'); const before = core.opened.length;
  await open(N, '/patients'); await sleep(1500);
  check('4a a role with no live topic opens no stream', core.opened.length === before, `+${core.opened.length - before}`);
  await N.browser.close();

  // 5 phone width
  const P = await desk('r@qa.test', { width: 390, height: 800 });
  await open(P); await until(async () => /^\s*Live/.test(await pill(P.page)), 4000);
  check('5a 390px: no horizontal scroll with the Live pill', await P.page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth));
  check('5b 390px: pill visible', /^\s*Live/.test(await pill(P.page)));
  check('5c zero console errors (phone)', clean(P.log).length === 0, clean(P.log).slice(0, 2).join(' | '));
  await P.browser.close();
  check('6 zero console/page errors on the desks', clean(A.log).length === 0 && clean(B.log).length === 0, [...clean(A.log), ...clean(B.log)].slice(0, 3).join(' | '));
  dumpLog('A', A.log);
} finally {
  await A.browser.close().catch(() => {}); await B.browser.close().catch(() => {});
  killStreams(); server.close(); server.closeAllConnections?.();
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
