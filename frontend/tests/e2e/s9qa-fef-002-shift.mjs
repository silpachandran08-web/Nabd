// S9 QA (FEF-002, independent): simulated 8 h shift over 3 tabs with a 60 s access TTL and single-use rotating refresh tokens,
// a two-tab simultaneous expiry, and deep-link returnTo. NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3307 node s9qa-fef-002-shift.mjs
import { chromium } from '/home/claude/ws/qa/node_modules/playwright/index.mjs';
import { jwt, check, results, sleep, APP, CORS } from './qa-lib.mjs';

const DOC = ['queue:view', 'patients:view', 'clinical:edit'];
const DAY = { date: '2026-10-18', today: true, timezone: 'Asia/Riyadh', holiday: null, doctors: [], sessions: [], counts: { appointments: 0, walkIns: 0, freeSlots: 0 } };
const errs = (a) => a.filter((x) => !/Failed to load resource|preloaded using link preload/.test(x));
const mk = (exp, tag) => jwt({ sub: '11111111-1111-1111-1111-111111111111', tenantId: '22222222-2222-2222-2222-222222222222', permissions: DOC, tenantName: 'QA', staffName: 'Dr Q', roleName: 'Doctor', owner: false, iat: exp - 60, exp, tag });

async function world({ ttl = 60, pages = 3 }) {
  const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
  const ctx = await browser.newContext({ viewport: { width: 1000, height: 800 } });
  const w = { browser, ctx, log: [], forensic: [], pages: [], consoleErr: [], refreshCalls: 0, reuse: 0, revoked: false, used: new Set(), valid: new Set(['r0']), n: 0, q401: 0, q200: 0, queueReqs: 0 };
  const t0 = Math.floor(Date.now() / 1000);
  await ctx.clock.install({ time: t0 * 1000 });
  // Forensics: real-time ordered log of refresh-token storage reads/writes and lock grants, per tab.
  await ctx.exposeBinding('qaLog', (src, msg) => { w.forensic.push(`${(Date.now() % 100000)}ms tab=${w.pages.indexOf(src.page)} ${msg}`); });
  await ctx.addInitScript(() => {
    const g = Storage.prototype.getItem, st = Storage.prototype.setItem;
    Storage.prototype.getItem = function (k) { const v = g.call(this, k); if (k === 'nabd_refresh_token') window.qaLog('GET rt=' + v); return v; };
    Storage.prototype.setItem = function (k, v) { if (k === 'nabd_refresh_token') window.qaLog('SET rt=' + v); return st.call(this, k, v); };
    const lr = navigator.locks.request.bind(navigator.locks);
    navigator.locks.request = (n, fn) => lr(n, async (l) => { window.qaLog('LOCK granted'); try { return await fn(l); } finally { window.qaLog('LOCK released'); } });
  });
  await ctx.addInitScript(([a]) => { if (!localStorage.getItem('nabd_access_token')) { localStorage.setItem('nabd_access_token', a); localStorage.setItem('nabd_refresh_token', 'r0'); } }, [mk(t0 + ttl, 'seed')]);
  const fakeNow = async () => (await w.pages[0].evaluate(() => Date.now())) / 1000;
  await ctx.route(/^(http:\/\/localhost:8080|https:\/\/nabd-f0zb\.onrender\.com)\/v1\//, async (route) => {
    const req = route.request();
    const u = new URL(req.url()); const path = u.pathname.replace(/^\/v1/, '');
    const send = (status, body) => route.fulfill({ status, headers: CORS, contentType: 'application/json', body: JSON.stringify(body) });
    if (req.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS });
    if (path === '/auth/refresh') {
      w.refreshCalls++;
      const rt = JSON.parse(req.postData()).refreshToken;
      w.forensic.push(`${(Date.now() % 100000)}ms SERVER got refresh(${rt}) used=${w.used.has(rt)}`);
      w.log.push(`t=${Math.round(await fakeNow() - t0)}s tab=${w.pages.indexOf(req.frame().page())} refresh(${rt}) used=${w.used.has(rt)} valid=${[...w.valid]}`);
      if (w.used.has(rt)) { w.reuse++; w.revoked = true; }
      if (w.revoked || !w.valid.has(rt)) return send(401, { title: 'REFRESH_INVALID' });
      w.used.add(rt); w.valid.delete(rt); const n = ++w.n; w.valid.add('r' + n);
      const now = Math.floor(await fakeNow());
      return send(200, { accessToken: mk(now + ttl, 'fresh' + n), refreshToken: 'r' + n, expiresIn: ttl });
    }
    if (path.startsWith('/queue')) {
      w.queueReqs++;
      const tok = (req.headers()['authorization'] ?? '').slice(7);
      let ok = false;
      try { const c = JSON.parse(Buffer.from(tok.split('.')[1], 'base64url').toString()); ok = !w.revoked && c.exp > (await fakeNow()); } catch { /* bad token */ }
      if (ok) w.q200++; else w.q401++;
      return ok ? send(200, []) : send(401, { title: 'Unauthorized' });
    }
    if (path.startsWith('/schedule/day')) return send(200, DAY);
    return send(200, []);
  });
  for (let i = 0; i < pages; i++) {
    const p = await ctx.newPage(); w.pages.push(p);
    p.on('console', (m) => { if (m.type() === 'error') w.consoleErr.push(m.text()); }); p.on('pageerror', (e) => w.consoleErr.push(String(e)));
    await p.goto(APP + '/arrivals'); await sleep(300);
  }
  return w;
}
const onLogin = (w) => w.pages.filter((p) => new URL(p.url()).pathname.startsWith('/login')).length;

try {
  // ---- 1. 8 h shift, N tabs, 60 s TTL, activity every 1-5 min per tab (fake clock; polling does the requests)
  for (const tabs of [1, 3]) {
    const w = await world({ pages: tabs });
    let minutes = 0, seed = 7; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
    while (minutes < 480) {
      const step = 1 + Math.floor(rnd() * 5); // 1-5 min
      await w.ctx.clock.runFor(step * 60_000); minutes += step;
      await sleep(120);
      if (onLogin(w) || w.revoked) break;
    }
    if (w.reuse) console.log('  refresh log (tail):\n  ' + w.log.slice(-8).join('\n  ') + '\n  forensic (tail):\n  ' + w.forensic.slice(-30).join('\n  '));
    check(`1 8 h simulated shift, ${tabs} tab(s): 0 redirects to /login`, onLogin(w) === 0, `minutes=${minutes} urls=${w.pages.map((p) => new URL(p.url()).pathname)}`);
    check(`1 ${tabs} tab(s): 0 reuse-detected revocations`, w.reuse === 0 && !w.revoked, `reuse=${w.reuse}`);
    check(`1 ${tabs} tab(s): shift ran the full 480 min and refreshed repeatedly`, minutes >= 480 && w.refreshCalls > 50, `minutes=${minutes} refreshCalls=${w.refreshCalls} queueReqs=${w.queueReqs} q401=${w.q401}`);
    // refresh load: not more than ~1 refresh per TTL window overall (single-flight across tabs)
    check(`1 ${tabs} tab(s): refreshes <= 1 per 48 s window`, w.refreshCalls <= Math.ceil((minutes * 60) / 48) + 2, `${w.refreshCalls} for ${minutes} min`);
    check(`1 ${tabs} tab(s): no console errors`, errs(w.consoleErr).length === 0, errs(w.consoleErr).slice(0, 3).join('|'));
    await w.browser.close();
  }
  // ---- 2. two tabs whose tokens expire together: one refresh in total (real Web Locks + BroadcastChannel)
  {
    const w = await world({ pages: 2 });
    await w.ctx.clock.fastForward(50_000); // both tabs past 80% of the 60 s lifetime at the same moment (timers fire together)
    await sleep(1500);
    check('2 two tabs expiring together: exactly 1 refresh call', w.refreshCalls === 1, `refreshCalls=${w.refreshCalls}`);
    const toks = await Promise.all(w.pages.map((p) => p.evaluate(() => localStorage.getItem('nabd_access_token'))));
    check('2 both tabs end with the same rotated token (shared storage), not on /login', toks[0] === toks[1] && onLogin(w) === 0);
    await w.browser.close();
  }
  // ---- 3. refresh revoked on a deep link with query => /login?returnTo=<path+search>, then back to exactly that page
  {
    const w = await world({ pages: 1 });
    const p = w.pages[0];
    w.revoked = true;
    await p.goto(APP + '/arrivals?tab=waiting');
    await p.waitForURL(/\/login/, { timeout: 8000 });
    const rt = new URL(p.url()).searchParams.get('returnTo');
    check('3 returnTo carries path + query', rt === '/arrivals?tab=waiting', String(rt));
    await p.route(/\/v1\/auth\/login/, (r) => r.fulfill({ status: 200, headers: CORS, contentType: 'application/json', body: JSON.stringify({ accessToken: mk(Math.floor(Date.now() / 1000) + 900, 'login'), refreshToken: 'rL', expiresIn: 900 }) }));
    w.revoked = false; w.valid.add('rL');
    await p.getByLabel('Clinic code').fill('qa'); await p.getByLabel('Email').fill('d@qa.test');
    await p.getByLabel('PIN', { exact: true }).fill('123456'); await p.getByRole('button', { name: 'Sign in', exact: true }).click();
    await p.waitForURL((u) => u.pathname === '/arrivals', { timeout: 8000 });
    check('3 after login lands on the same page + query', new URL(p.url()).pathname + new URL(p.url()).search === '/arrivals?tab=waiting', p.url());
    check('3 login page 390px: no horizontal scroll', await (async () => { await p.goto(APP + '/login?returnTo=%2Farrivals'); await p.setViewportSize({ width: 390, height: 800 }); return p.evaluate(() => document.documentElement.scrollWidth <= innerWidth); })());
    await w.browser.close();
  }
  // ---- 4. hostile returnTo shapes through the real login page
  {
    const w = await world({ pages: 1 });
    const p = w.pages[0];
    await p.route(/\/v1\/auth\/login/, (r) => r.fulfill({ status: 200, headers: CORS, contentType: 'application/json', body: JSON.stringify({ accessToken: mk(Math.floor(Date.now() / 1000) + 900, 'login'), refreshToken: 'rL', expiresIn: 900 }) }));
    for (const evil of ['https://evil.example', '//evil.example', '/\\evil.example', 'javascript:alert(1)', '///evil.example/x']) {
      await p.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
      await p.goto(APP + '/login?returnTo=' + encodeURIComponent(evil)).catch(async () => { await sleep(500); await p.goto(APP + '/login?returnTo=' + encodeURIComponent(evil)); });
      await p.getByLabel('Clinic code').fill('qa'); await p.getByLabel('Email').fill('d@qa.test');
      await p.getByLabel('PIN', { exact: true }).fill('123456'); await p.getByRole('button', { name: 'Sign in', exact: true }).click();
      await p.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 8000 });
      const u = new URL(p.url());
      check(`4 returnTo ${JSON.stringify(evil)} -> same-origin landing`, u.origin === new URL(APP).origin && !/evil/.test(u.href), u.href);
    }
    await w.browser.close();
  }
} catch (e) { check('script ran to completion', false, String(e).split('\n').slice(0, 2).join(' ')); }
const failed = results.filter((r) => !r.ok); console.log(`\n${results.length - failed.length}/${results.length} passed`); process.exit(failed.length ? 1 : 0);
