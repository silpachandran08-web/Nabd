// S11 QA (independent) FEF-009 + FEF-010 together: the merged proxy.ts nonce CSP in ENFORCE mode on top of the cookie-mode BFF.
// Build once with NEXT_PUBLIC_AUTH_MODE=cookie, then:
//   NABD_CSP_MODE=enforce NABD_CORE_URL=http://localhost:13612/v1 next start -p 3612 > $SERVER_LOG
//   NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3612 SERVER_LOG=... node tests/e2e/s11qa-fef009-fef010.mjs
// The mock Core below is what the BFF (server side) talks to; the browser must never reach Core itself.
import http from 'node:http';
import { readFileSync } from 'node:fs';
import { launch, check, results, dumpLog, sleep, APP } from './qa-lib.mjs';

const CORE_PORT = Number(process.env.CORE_PORT ?? 13612);
const b64u = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
const nowS = () => Math.floor(Date.now() / 1000);
// test-time fake JWTs with a random "signature"; never a real key
const mintAt = () => `${b64u({ alg: 'RS256', typ: 'JWT' })}.${b64u({ sub: '11111111-1111-1111-1111-111111111111', tenantId: '22222222-2222-2222-2222-222222222222',
  permissions: ['queue:view', 'patients:view'], tenantName: 'QA Clinic', tenantRegion: 'IN', staffName: 'Qa Two', roleName: 'Custom', owner: false, iat: nowS(), exp: nowS() + 900 })}.${crypto.randomUUID().replace(/-/g, '')}`;

const core = { at: null, rt: null, calls: [], refreshes: 0, issued: [], loggedOut: null };
const issue = () => { core.at = mintAt(); core.rt = `rt-${crypto.randomUUID()}`; core.issued.push(core.at, core.rt); return { accessToken: core.at, refreshToken: core.rt, expiresIn: 900, refreshExpiresIn: 2592000 }; };
const server = http.createServer(async (req, res) => {
  let body = ''; for await (const ch of req) body += ch;
  const path = req.url.replace(/^\/v1/, '');
  core.calls.push({ method: req.method, path, auth: req.headers.authorization ?? null, cookie: req.headers.cookie ?? null });
  const send = (s, o) => { res.writeHead(s, { 'content-type': s >= 400 ? 'application/problem+json' : 'application/json' }); res.end(o === undefined ? '' : JSON.stringify(o)); };
  if (req.method === 'POST' && path === '/auth/login') return send(200, issue());
  if (req.method === 'POST' && path === '/auth/refresh') { core.refreshes++; await sleep(60); return JSON.parse(body).refreshToken === core.rt ? send(200, issue()) : send(401, { title: 'REFRESH_INVALID' }); }
  if (req.method === 'POST' && path === '/auth/logout') { core.loggedOut = req.headers.authorization; return send(204); }
  if (path.startsWith('/setup/policies')) return send(200, []);
  if (path.startsWith('/queue')) return req.headers.authorization === `Bearer ${core.at}` ? send(200, []) : send(401, { title: 'Unauthorized' });
  return send(404, { title: 'Not found', detail: 'The requested resource was not found.' });
});
await new Promise((r) => server.listen(CORE_PORT, r));

const serverLog = () => { try { return readFileSync(process.env.SERVER_LOG, 'utf8'); } catch { return ''; } };
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
const watchCsp = (page) => page.addInitScript(() => { window.__csp = []; document.addEventListener('securitypolicyviolation', (e) => window.__csp.push({ d: e.effectiveDirective, b: e.blockedURI, disp: e.disposition })); });
const jar = async (ctx) => Object.fromEntries((await ctx.cookies(APP)).map((c) => [c.name, c]));
const cookieHeader = async (ctx) => (await ctx.cookies(APP)).map((c) => `${c.name}=${c.value}`).join('; ');
const directive = (csp, name) => (csp.split(';').map((d) => d.trim()).find((d) => d.startsWith(name + ' ')) ?? '');

let s;
try {
  // ── FEF-010 headers on HTML and on BFF responses ──
  const pages = ['/login', '/arrivals', '/patients', '/platform/login'];
  const nonces = new Set();
  for (const p of pages) {
    for (let i = 0; i < 2; i++) {
      const r = await fetch(APP + p, { redirect: 'manual' });
      const csp = r.headers.get('content-security-policy') ?? '';
      const n = csp.match(/'nonce-([^']+)'/)?.[1];
      if (n) nonces.add(n);
      if (i === 0) {
        check(`H1 ${p}: enforced CSP (not report-only) with a nonce`, !!n && !r.headers.get('content-security-policy-report-only'), csp.slice(0, 60));
        check(`H2 ${p}: script-src strict (nonce + strict-dynamic, no unsafe-inline/eval)`, /'strict-dynamic'/.test(directive(csp, 'script-src')) && !/unsafe-(inline|eval)/.test(directive(csp, 'script-src')), directive(csp, 'script-src'));
        check(`H3 ${p}: frame-ancestors 'none', object-src 'none', base-uri 'none', connect-src 'self'`, /frame-ancestors 'none'/.test(csp) && /object-src 'none'/.test(csp) && /base-uri 'none'/.test(csp) && /connect-src 'self'/.test(csp));
        const h = (k) => r.headers.get(k) ?? '';
        check(`H4 ${p}: HSTS 2y+preload, nosniff, Referrer same-origin, COOP, Permissions-Policy`,
          /max-age=63072000; includeSubDomains; preload/.test(h('strict-transport-security')) && h('x-content-type-options') === 'nosniff' && h('referrer-policy') === 'same-origin'
          && h('cross-origin-opener-policy') === 'same-origin' && /camera=\(self\)/.test(h('permissions-policy')) && /microphone=\(\)/.test(h('permissions-policy')) && /payment=\(\)/.test(h('permissions-policy')));
      }
    }
  }
  check('H5 nonce is fresh on every one of the 8 HTML responses', nonces.size === pages.length * 2, `${nonces.size} distinct`);
  const sess = await fetch(APP + '/api/session');
  check('H6 BFF /api/session: 401 NO_SESSION, Cache-Control no-store, nosniff + HSTS still on the BFF response', sess.status === 401 && sess.headers.get('cache-control') === 'no-store'
    && sess.headers.get('x-content-type-options') === 'nosniff' && !!sess.headers.get('strict-transport-security'), `${sess.status} ${sess.headers.get('cache-control')}`);

  // ── FEF-009 sign-in through the BFF while the enforced CSP is active ──
  s = await launch();
  await watchCsp(s.page);
  const toCore = [];
  s.page.on('request', (r) => { if (/localhost:8080|onrender\.com|localhost:13612/.test(r.url())) toCore.push(r.url()); });
  let loginSetCookie = '';
  let loginBody = '';
  s.page.on('response', async (r) => { if (r.url().endsWith('/api/auth/login')) { loginSetCookie = (await r.headerValue('set-cookie')) ?? ''; loginBody = await r.text().catch(() => ''); } });
  await s.page.goto(`${APP}/login`, { waitUntil: 'networkidle' });
  const docNonce = (await s.page.evaluate(() => [...document.scripts].map((x) => x.nonce)));
  check('C1 every script on /login carries the nonce (hydration under enforce)', docNonce.length > 0 && docNonce.every((n) => n && n.length > 10), `${docNonce.length} scripts`);
  await s.page.getByLabel('Clinic code').fill('qa'); await s.page.getByLabel('Email').fill('q2@qa.test');
  await s.page.getByLabel('PIN', { exact: true }).fill('123456');
  await s.page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await s.page.waitForURL(/\/arrivals/, { timeout: 15000 });
  await s.page.waitForLoadState('networkidle');
  for (let i = 0; i < 50 && !core.calls.some((c) => c.path.startsWith('/queue')); i++) await sleep(100);
  check('C2 signed in under enforced CSP and landed on /arrivals', new URL(s.page.url()).pathname === '/arrivals');
  check('C3 the queue was loaded via the same-origin BFF with the cookie token', core.calls.some((c) => c.path.startsWith('/queue') && c.auth === `Bearer ${core.at}`));
  check('C4 the browser never contacted Core directly', toCore.length === 0, toCore.slice(0, 2).join(' '));
  const v1 = await s.page.evaluate(() => window.__csp);
  check('C5 zero CSP violations through login + first screen (BFF calls pass connect-src self)', v1.length === 0, JSON.stringify(v1));
  check('C6 zero console/page errors', noise(s.log).length === 0, noise(s.log).join(' | '));
  check('C7 login response body carries no access/refresh token', !core.issued.some((t) => loginBody.includes(t)) && !/refreshToken/.test(loginBody), loginBody.slice(0, 80));

  // ── XSS: injected markup is blocked by the CSP, and even script that does run finds no token ──
  let dialogs = 0; s.page.on('dialog', (d) => { dialogs++; d.dismiss(); });
  await s.page.evaluate(() => { const d = document.createElement('div'); d.innerHTML = '<img src=x onerror="window.__stolen=document.cookie+JSON.stringify(localStorage)"><script>alert(1)</script>'; document.body.appendChild(d); });
  await sleep(500);
  check('X1 injected inline handler blocked (did not run), no alert', (await s.page.evaluate(() => window.__stolen)) === undefined && dialogs === 0);
  const v2 = await s.page.evaluate(() => window.__csp);
  check('X2 the browser raised an enforce script-src violation for it', v2.some((x) => x.d.startsWith('script-src') && x.disp === 'enforce'), JSON.stringify(v2));
  const loot = await s.page.evaluate(() => { const a = [document.cookie]; for (const st of [localStorage, sessionStorage]) for (let i = 0; i < st.length; i++) a.push(st.getItem(st.key(i))); return a.join('\n'); });
  const leaked = core.issued.filter((t) => loot.includes(t) || (t.split('.')[2] && loot.includes(t.split('.')[2])));
  check('X3 script-readable storage (document.cookie, localStorage, sessionStorage) holds no access/refresh token', leaked.length === 0, `${leaked.length} leaked`);

  // ── cookie attributes ──
  const c = await jar(s.context);
  const rt = c['__Host-nabd_rt'];
  check('K1 __Host-nabd_rt httpOnly + Secure + SameSite=Strict + Path=/', !!rt && rt.httpOnly && rt.secure && rt.sameSite === 'Strict' && rt.path === '/');
  check('K2 __Host-nabd_rt Max-Age = refresh expiry (2592000)', /__Host-nabd_rt=[^;]+; Path=\/; Max-Age=2592000; Secure; SameSite=Strict; HttpOnly/.test(loginSetCookie));
  check('K3 __Host-nabd_at httpOnly; __Host-nabd_csrf readable but Strict', c['__Host-nabd_at']?.httpOnly === true && c['__Host-nabd_csrf']?.httpOnly === false && c['__Host-nabd_csrf']?.sameSite === 'Strict');

  // ── CSRF matrix (server-side fetch so Origin can be absent/forged) ──
  const ck = await cookieHeader(s.context);
  const tok = c['__Host-nabd_csrf'].value;
  const before = core.calls.filter((x) => x.method !== 'GET').length;
  const cases = [];
  for (const m of ['POST', 'PUT', 'PATCH', 'DELETE']) {
    const b = m === 'DELETE' ? undefined : '{}';
    const variants = {
      missingToken: { origin: APP, cookie: ck },
      wrongToken: { origin: APP, cookie: ck, 'x-csrf-token': tok + 'x' },
      foreignOrigin: { origin: 'https://evil.example', cookie: ck, 'x-csrf-token': tok },
      noOrigin: { cookie: ck, 'x-csrf-token': tok },
      nullOrigin: { origin: 'null', cookie: ck, 'x-csrf-token': tok },
    };
    for (const [name, h] of Object.entries(variants)) {
      const r = await fetch(`${APP}/api/v1/queue/x`, { method: m, headers: { 'content-type': 'application/json', ...h }, body: b });
      cases.push([m, name, r.status, (await r.json().catch(() => ({}))).code]);
    }
  }
  check('F1 every verb x {missing, wrong, foreign Origin, no Origin, null Origin} -> 403 CSRF_FAILED', cases.every((x) => x[2] === 403 && x[3] === 'CSRF_FAILED'), JSON.stringify(cases.filter((x) => x[2] !== 403)));
  check('F2 Core was never called for a refused mutation', core.calls.filter((x) => x.method !== 'GET').length === before);
  const good = await fetch(`${APP}/api/v1/queue/x`, { method: 'POST', headers: { 'content-type': 'application/json', origin: APP, cookie: ck, 'x-csrf-token': tok }, body: '{}' });
  check('F3 control: matching token + own Origin passes the BFF to Core', good.status !== 403 && core.calls.some((x) => x.method === 'POST' && x.path === '/queue/x'), String(good.status));
  const lo = await fetch(`${APP}/api/auth/logout`, { method: 'POST', headers: { origin: 'https://evil.example', cookie: ck, 'x-csrf-token': tok } });
  check('F4 cross-site logout refused (403) and the session survives', lo.status === 403 && core.loggedOut === null);
  check('F5 Core never received browser cookies', core.calls.every((x) => !x.cookie));

  // ── expired access cookie: single-flight refresh, data in the same response ──
  const oldRt = rt.value;
  await s.context.clearCookies({ name: '__Host-nabd_at' });
  const r0 = core.refreshes;
  const burst = await s.page.evaluate(() => Promise.all(Array.from({ length: 8 }, () => fetch('/api/v1/queue').then(async (r) => [r.status, Array.isArray(await r.json()), r.headers.get('cache-control')]))));
  check('R1 8 parallel calls with an expired access cookie -> exactly one refresh at Core', core.refreshes - r0 === 1, `refreshes=${core.refreshes - r0}`);
  check('R2 all 8 returned data in the same response with no-store', burst.every(([st, arr, cc]) => st === 200 && arr && cc === 'no-store'), JSON.stringify(burst));
  const c2 = await jar(s.context);
  check('R3 rotated cookies set', c2['__Host-nabd_rt']?.value === core.rt && c2['__Host-nabd_rt']?.value !== oldRt && c2['__Host-nabd_at']?.value === core.at);
  check('R4 no CSP violation from the refresh path either', (await s.page.evaluate(() => window.__csp)).filter((x) => !x.d.startsWith('script-src')).length === 0);

  // ── 390 px ──
  await s.page.setViewportSize({ width: 390, height: 800 });
  check('M1 390px /arrivals: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await s.page.setViewportSize({ width: 1280, height: 900 });

  // ── sign-out ──
  await s.page.evaluate(() => new Promise((r) => { const q = indexedDB.open('nabd-consult-drafts', 1); q.onupgradeneeded = () => q.result.createObjectStore('drafts', { keyPath: 'entryId' }); q.onsuccess = () => { q.result.close(); r(true); }; }));
  const sessionAt = core.at;
  await s.page.locator('button').filter({ hasText: 'Qa Two' }).first().click();
  await s.page.getByRole('button', { name: 'Sign out' }).click();
  await s.page.waitForURL(/\/login/, { timeout: 8000 });
  check('L1 Core /auth/logout called with the session token', core.loggedOut === `Bearer ${sessionAt}`);
  const c3 = await jar(s.context);
  check('L2 all three cookies cleared', !c3['__Host-nabd_rt'] && !c3['__Host-nabd_at'] && !c3['__Host-nabd_csrf'], Object.keys(c3).join(','));
  check('L3 drafts IndexedDB deleted', !(await s.page.evaluate(async () => (await indexedDB.databases()).map((d) => d.name))).includes('nabd-consult-drafts'));
  const v3 = (await s.page.evaluate(() => window.__csp)).filter((x) => x.b !== 'inline');
  check('L5 zero first-party CSP violations end to end', v3.length === 0, JSON.stringify(v3));
  dumpLog('flow', s.log);
  await s.browser.close(); s = null;

  // ── control for X1: the same injection with CSP bypassed DOES run, so X1 tells blocked from inert ──
  {
    const { chromium } = await import('/home/claude/ws/qa/node_modules/playwright/index.mjs');
    const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
    const pg = await (await b.newContext({ bypassCSP: true })).newPage();
    await pg.goto(APP + '/login');
    await pg.evaluate(() => { const d = document.createElement('div'); d.innerHTML = '<img src=x onerror="window.__stolen=1">'; document.body.appendChild(d); });
    await sleep(500);
    check('X0 control: without CSP the injected handler executes', (await pg.evaluate(() => window.__stolen)) === 1);
    await b.close();
  }

  // ── framing ──
  s = await launch();
  await s.page.route('http://attacker.test/**', (r) => r.fulfill({ contentType: 'text/html', body: `<iframe src="${APP}/login" width="600" height="400"></iframe>` }));
  await s.page.goto('http://attacker.test/'); await sleep(2000);
  const f = s.page.frames().find((x) => x !== s.page.mainFrame());
  const framed = f ? await f.evaluate(() => !!document.querySelector('form')).catch(() => false) : false;
  check('I1 cross-origin iframe of /login refused', !framed, f ? f.url() : 'no frame');
  await s.browser.close(); s = null;

  // ── CSP report sink ──
  const rep = { 'csp-report': { 'document-uri': `${APP}/patients/123?q=Asha+Rao#frag`, 'violated-directive': 'script-src-elem', 'effective-directive': 'script-src-elem', 'blocked-uri': 'https://evil.example/x.js?token=abc', 'script-sample': 'alert(document.cookie)' } };
  const ok = await fetch(`${APP}/api/csp-report`, { method: 'POST', headers: { 'content-type': 'application/csp-report' }, body: JSON.stringify(rep) });
  check('S1 legacy CSP report accepted with 204', ok.status === 204, String(ok.status));
  const big = await fetch(`${APP}/api/csp-report`, { method: 'POST', headers: { 'content-type': 'application/reports+json' }, body: JSON.stringify([{ type: 'csp-violation', body: { documentURL: APP + '/x', pad: 'a'.repeat(9000) } }]) });
  check('S2 report over 8 KB -> 413', big.status === 413, String(big.status));
  let line = '';
  for (let i = 0; i < 20 && !line; i++) { line = serverLog().split('\n').find((l) => l.includes('csp.violation') && l.includes('script-src-elem')) ?? ''; if (!line) await sleep(250); }
  check('S3 report logged without query string, fragment, patient id, token or sample', !!line && !/Asha|token=|#frag|alert|\/123/.test(line), line || 'no line in server log');
} catch (e) {
  check('script ran to completion', false, String(e).split('\n')[0]);
  if (s) { dumpLog('last', s.log); console.log('  url:', s.page.url()); }
} finally {
  await s?.browser.close().catch(() => {});
  server.close();
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
