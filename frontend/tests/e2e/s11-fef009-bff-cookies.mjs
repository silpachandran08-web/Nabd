// FEF-009 E2E: httpOnly cookie sessions behind the same-origin BFF. Needs a cookie-mode build and the BFF pointed at
// the mock Core this script starts (port CORE_PORT, default 13505):
//   NEXT_PUBLIC_AUTH_MODE=cookie npx next build && NABD_CORE_URL=http://localhost:13505/v1 next start -p 3505
//   NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3505 node s11-fef009-bff-cookies.mjs
// The browser must never talk to Core (localhost:8080 / onrender) — every call goes through /api/*.
import http from 'node:http';
import { launch, check, results, dumpLog, APP } from './qa-lib.mjs';

const CORE_PORT = Number(process.env.CORE_PORT ?? 13505);
const PERMS = ['queue:view', 'patients:view'];
const b64u = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
const nowS = () => Math.floor(Date.now() / 1000);
// Test-time fake tokens (random signature), never a real key.
const mintAt = (exp = nowS() + 900) => `${b64u({ alg: 'RS256', typ: 'JWT' })}.${b64u({ sub: '11111111-1111-1111-1111-111111111111', tenantId: '22222222-2222-2222-2222-222222222222',
  permissions: PERMS, tenantName: 'QA Clinic', tenantRegion: 'IN', staffName: 'Rana Reception', roleName: 'Custom', owner: false, iat: exp - 900, exp })}.${crypto.randomUUID().replace(/-/g, '')}`;

// ---- mock Core -----------------------------------------------------------------------------------
const core = { at: null, rt: null, calls: [], refreshes: 0, refuseRefresh: false, issued: [] };
const issue = () => { core.at = mintAt(); core.rt = `rt-${crypto.randomUUID()}`; core.issued.push(core.at, core.rt); return { accessToken: core.at, refreshToken: core.rt, expiresIn: 900, refreshExpiresIn: 2592000 }; };
const server = http.createServer(async (req, res) => {
  let body = ''; for await (const ch of req) body += ch;
  const path = req.url.replace(/^\/v1/, '');
  core.calls.push({ method: req.method, path, auth: req.headers.authorization ?? null, cookie: req.headers.cookie ?? null, csrf: req.headers['x-csrf-token'] ?? null });
  const send = (status, obj) => { res.writeHead(status, { 'content-type': status >= 400 ? 'application/problem+json' : 'application/json' }); res.end(obj === undefined ? '' : JSON.stringify(obj)); };
  if (req.method === 'POST' && path === '/auth/login') return send(200, issue());
  if (req.method === 'POST' && path === '/auth/refresh') {
    core.refreshes++;
    await new Promise((r) => setTimeout(r, 40));
    if (core.refuseRefresh || JSON.parse(body).refreshToken !== core.rt) return send(401, { title: 'REFRESH_INVALID' });
    return send(200, issue());
  }
  if (req.method === 'POST' && path === '/auth/logout') { core.loggedOut = req.headers.authorization; return send(204); }
  if (path.startsWith('/setup/policies')) return send(200, []);
  if (path.startsWith('/queue')) return req.headers.authorization === `Bearer ${core.at}` ? send(200, []) : send(401, { title: 'Unauthorized' });
  return send(404, { title: 'Not found', detail: 'The requested resource was not found.' });
});
await new Promise((r) => server.listen(CORE_PORT, r));

const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
const jar = async (ctx) => Object.fromEntries((await ctx.cookies(APP)).map((c) => [c.name, c]));
const cookieHeader = async (ctx) => (await ctx.cookies(APP)).map((c) => `${c.name}=${c.value}`).join('; ');

async function signIn(s) {
  await s.page.goto(`${APP}/login`, { waitUntil: 'networkidle' });
  await s.page.getByLabel('Clinic code').fill('qa'); await s.page.getByLabel('Email').fill('r@qa.test');
  await s.page.getByLabel('PIN', { exact: true }).fill('123456');
  await s.page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await s.page.waitForURL(/\/arrivals/, { timeout: 10000 });
  await s.page.waitForLoadState('networkidle');
}

let s;
try {
  s = await launch();
  const toCore = [];
  s.page.on('request', (r) => { if (/localhost:8080|onrender\.com/.test(r.url())) toCore.push(r.url()); });
  let loginSetCookie = '';
  s.page.on('response', async (r) => { if (r.url().endsWith('/api/auth/login')) loginSetCookie = (await r.headerValue('set-cookie')) ?? ''; });

  // 1 sign in through the real login page
  await signIn(s);
  check('1a signed in through /api/auth/login and landed on /arrivals', new URL(s.page.url()).pathname === '/arrivals');
  check('1b the browser never called Core directly', toCore.length === 0, toCore.slice(0, 3).join(' '));
  for (let i = 0; i < 50 && !core.calls.some((c) => c.path.startsWith('/queue')); i++) await new Promise((r) => setTimeout(r, 100));
  check('1c the queue reached Core with the cookie token, via the BFF', core.calls.some((c) => c.path.startsWith('/queue') && c.auth === `Bearer ${core.at}`), core.calls.filter((c) => c.path.startsWith('/queue')).map((c) => String(c.auth).slice(0, 60)).join(' | '));
  check('1d Core never sees browser cookies or the CSRF header', core.calls.every((c) => !c.cookie && !c.csrf));

  // 2 XSS simulation: an injected script reads everything it can reach
  const loot = await s.page.evaluate(() => {
    const all = [document.cookie];
    for (const st of [localStorage, sessionStorage]) for (let i = 0; i < st.length; i++) all.push(`${st.key(i)}=${st.getItem(st.key(i))}`);
    return all.join('\n');
  });
  const leaked = core.issued.filter((t) => loot.includes(t) || loot.includes(t.split('.')[2] ?? '\u0000'));
  check('2a XSS: no access or refresh token (nor a token signature) in localStorage, sessionStorage or document.cookie', leaked.length === 0, `${leaked.length} leaked`);
  const jwtsWithSig = (loot.match(/[\w-]{10,}\.[\w-]{10,}\.[\w-]{10,}/g) ?? []).filter((t) => t.split('.')[2].length > 0);
  check('2b no signed JWT anywhere a script can read', jwtsWithSig.length === 0, jwtsWithSig.join(','));
  check('2c no refresh token key left in storage', await s.page.evaluate(() => localStorage.getItem('nabd_refresh_token') === null && !document.cookie.includes('nabd_rt') && !document.cookie.includes('nabd_at')));

  // 3 cookie attributes
  const c = await jar(s.context);
  const rt = c['__Host-nabd_rt'];
  check('3a __Host-nabd_rt is httpOnly, Secure, SameSite=Strict, Path=/', !!rt && rt.httpOnly && rt.secure && rt.sameSite === 'Strict' && rt.path === '/', JSON.stringify(rt && { h: rt.httpOnly, s: rt.secure, ss: rt.sameSite, p: rt.path }));
  check('3b __Host-nabd_rt Max-Age equals the refresh expiry (2592000 s)', /__Host-nabd_rt=[^;]+; Path=\/; Max-Age=2592000; Secure; SameSite=Strict; HttpOnly/.test(loginSetCookie) && Math.abs(rt.expires - (nowS() + 2592000)) < 120, `expires in ${Math.round(rt.expires - nowS())} s`);
  check('3c __Host-nabd_at httpOnly with Max-Age 900', c['__Host-nabd_at']?.httpOnly === true && /__Host-nabd_at=[^;]+; Path=\/; Max-Age=900;/.test(loginSetCookie));
  check('3d __Host-nabd_csrf readable by the page, Secure, Strict', c['__Host-nabd_csrf']?.httpOnly === false && c['__Host-nabd_csrf'].secure && c['__Host-nabd_csrf'].sameSite === 'Strict');

  // 4 CSRF matrix on every mutating verb: missing, wrong, foreign Origin -> 403 CSRF_FAILED, Core not called
  const before = core.calls.length;
  const matrix = [];
  for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) {
    const r = await s.page.evaluate(async ([m]) => {
      const a = await fetch('/api/v1/queue', { method: m, headers: { 'content-type': 'application/json' }, body: m === 'DELETE' ? undefined : '{}' });
      const b = await fetch('/api/v1/queue', { method: m, headers: { 'content-type': 'application/json', 'X-CSRF-Token': 'wrong' }, body: m === 'DELETE' ? undefined : '{}' });
      return [a.status, (await a.json()).code, b.status, (await b.json()).code];
    }, [method]);
    const tok = c['__Host-nabd_csrf'].value;
    const f = await fetch(`${APP}/api/v1/queue`, { method, headers: { origin: 'https://evil.example', cookie: await cookieHeader(s.context), 'x-csrf-token': tok, 'content-type': 'application/json' }, body: method === 'DELETE' ? undefined : '{}' });
    matrix.push([method, ...r, f.status, (await f.json()).code]);
  }
  check('4a missing / wrong token / foreign Origin -> 403 CSRF_FAILED on POST, PUT, PATCH, DELETE', matrix.every(([, s1, c1, s2, c2, s3, c3]) => s1 === 403 && c1 === 'CSRF_FAILED' && s2 === 403 && c2 === 'CSRF_FAILED' && s3 === 403 && c3 === 'CSRF_FAILED'), JSON.stringify(matrix));
  const leakedCalls = core.calls.slice(before).filter((c) => c.method !== 'GET'); // the arrivals screen keeps polling with GET meanwhile
  check('4b Core was not called for any refused request', leakedCalls.length === 0, leakedCalls.map((c) => c.method + ' ' + c.path).join(','));

  // 5 expired access cookie: one refresh for a burst, new cookies, data in the same response
  const oldRt = rt.value;
  await s.context.clearCookies({ name: '__Host-nabd_at' }); // expired: the browser dropped it at Max-Age=900
  const r0 = core.refreshes;
  const burst = await s.page.evaluate(() => Promise.all(Array.from({ length: 5 }, () => fetch('/api/v1/queue').then(async (r) => [r.status, await r.json(), r.headers.get('cache-control')]))));
  check('5a expired (dropped) access cookie: 5 parallel /api/v1/queue calls -> exactly one refresh at Core', core.refreshes - r0 === 1, `refreshes=${core.refreshes - r0}`);
  check('5b every call returned the data in the same response, Cache-Control no-store', burst.every(([st, b, cc]) => st === 200 && Array.isArray(b) && cc === 'no-store'), JSON.stringify(burst));
  const c2 = await jar(s.context);
  check('5c new cookies set (rotated refresh token, fresh access token)', c2['__Host-nabd_rt']?.value !== oldRt && c2['__Host-nabd_rt']?.value === core.rt && c2['__Host-nabd_at']?.value === core.at);
  const loot2 = await s.page.evaluate(() => [document.cookie, ...Object.keys(localStorage).map((k) => localStorage.getItem(k))].join('\n'));
  check('5d still no token readable by script after the refresh', !loot2.includes(core.at) && !loot2.includes(core.rt));

  // 6 390 px, console
  await s.page.setViewportSize({ width: 390, height: 800 });
  check('6a 390px /arrivals: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));

  // 7 sign-out: Core logout with the session token, three cookies cleared, drafts DB deleted
  await s.page.setViewportSize({ width: 1280, height: 900 });
  await s.page.evaluate(() => new Promise((r) => { const q = indexedDB.open('nabd-consult-drafts', 1); q.onupgradeneeded = () => q.result.createObjectStore('drafts', { keyPath: 'entryId' }); q.onsuccess = () => { q.result.close(); r(true); }; }));
  const sessionAt = core.at;
  await s.page.locator('button').filter({ hasText: 'Rana Reception' }).first().click();
  await s.page.getByRole('button', { name: 'Sign out' }).click();
  await s.page.waitForURL(/\/login/, { timeout: 5000 });
  check('7a POST /api/auth/logout reached Core /auth/logout with the session token', core.loggedOut === `Bearer ${sessionAt}`, String(core.loggedOut).slice(0, 20));
  const c3 = await jar(s.context);
  check('7b all three cookies cleared', !c3['__Host-nabd_rt'] && !c3['__Host-nabd_at'] && !c3['__Host-nabd_csrf'], Object.keys(c3).join(','));
  check('7c drafts database deleted, claims snapshot gone', !(await s.page.evaluate(async () => (await indexedDB.databases()).map((d) => d.name))).includes('nabd-consult-drafts') && await s.page.evaluate(() => !localStorage.getItem('nabd_access_token')));
  const afterOut = await s.page.evaluate(() => fetch('/api/session').then((r) => r.status));
  check('7d after sign-out GET /api/session -> 401', afterOut === 401);
  check('7e 390-free: login page has no horizontal scroll at 390px', await (async () => { await s.page.setViewportSize({ width: 390, height: 800 }); return s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth); })());
  check('7f zero console errors through sign-in, refresh and sign-out', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  // 8 boundary: refresh refused -> 401 SESSION_EXPIRED, cookies cleared, the screen sends the user to /login?returnTo
  s = await launch();
  await signIn(s);
  const expired = [];
  // read the BFF's answer before the page navigates away (a plain response listener loses the body on redirect)
  await s.page.route('**/api/v1/queue**', async (route) => { const r = await route.fetch(); if (r.status() === 401) expired.push((await r.json().catch(() => ({}))).code); await route.fulfill({ response: r }); });
  core.refuseRefresh = true;
  await s.context.clearCookies({ name: '__Host-nabd_at' }); // expired: the browser dropped it at Max-Age=900
  await s.page.reload(); // the screen's own queue load meets the dead session
  await s.page.waitForURL(/\/login\?returnTo=/, { timeout: 8000 });
  check('8a refresh refused -> the BFF answered 401 SESSION_EXPIRED', expired.includes('SESSION_EXPIRED'), expired.join(','));
  check('8b session cookies cleared by the BFF', !(await jar(s.context))['__Host-nabd_rt'] && !(await jar(s.context))['__Host-nabd_at']);
  check('8c the screen sends the user to /login?returnTo=/arrivals', new URL(s.page.url()).searchParams.get('returnTo') === '/arrivals', s.page.url());
  check('8d zero console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  core.refuseRefresh = false;
  await s.browser.close();
} catch (e) {
  check('script ran to completion', false, String(e).split('\n')[0]);
  if (s) { dumpLog('last', s.log); console.log('  url:', s.page.url(), '| core:', core.calls.map((c) => c.method + ' ' + c.path).slice(-8)); }
  await s?.browser.close().catch(() => {});
}
server.close();
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
