// T3 shared helpers: browser launch, session seeding, API mocking with request log.
import { chromium } from "/home/claude/ws/qa/node_modules/playwright/index.mjs";
export const APP = process.env.APP ?? 'http://localhost:3000';
export const API = 'http://localhost:8080/v1';
export const SHOTS = '/home/claude/ws/reports/shots-rec6';

const b64u = (o) => Buffer.from(JSON.stringify(o)).toString('base64').replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
export function jwt(claims) {
  return `${b64u({ alg: 'RS256', typ: 'JWT' })}.${b64u({ exp: Math.floor(Date.now() / 1000) + 3600, ...claims })}.sig`;
}
export function clinicToken(permissions, extra = {}) {
  return jwt({ sub: '11111111-1111-1111-1111-111111111111', tenantId: '22222222-2222-2222-2222-222222222222', permissions,
    tenantName: 'QA Clinic', tenantRegion: 'IN', staffName: 'Rana Reception', roleName: 'Custom', owner: false, ...extra });
}

export async function launch(opts = {}) {
  const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
  const context = await browser.newContext({ viewport: opts.viewport ?? { width: 1280, height: 900 }, timezoneId: opts.timezoneId ?? 'Asia/Riyadh' });
  const page = await context.newPage();
  const log = { requests: [], console: [], pageErrors: [], unmatched: [] };
  page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') log.console.push(`[${m.type()}] ${m.text()}`); });
  page.on('pageerror', (e) => log.pageErrors.push(String(e)));
  return { browser, context, page, log };
}

export async function seedClinic(page, permissions, extra) {
  const token = clinicToken(permissions, extra);
  await page.addInitScript((t) => { localStorage.setItem('nabd_access_token', t); localStorage.setItem('nabd_refresh_token', 'r1'); }, token);
}
export async function seedPlatform(page, access = 'pa1', refresh = 'pr1') {
  await page.addInitScript(([a, r]) => { if (!sessionStorage.getItem('__t3seeded')) { localStorage.setItem('nabd_platform_access_token', a); localStorage.setItem('nabd_platform_refresh_token', r); sessionStorage.setItem('__t3seeded', '1'); } }, [access, refresh]);
}

export const CORS = { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*', 'access-control-allow-methods': 'GET,POST,PATCH,PUT,DELETE,OPTIONS', 'access-control-expose-headers': '*' };
const wrap = (route) => ({ fulfill: (o) => route.fulfill({ ...o, headers: { ...CORS, ...(o.headers || {}) } }), abort: (e) => route.abort(e), request: () => route.request() });
/** handlers: array of [method, RegExp on path+query (after /v1), fn(route, req, match) | {status, body, delay}] */
export async function mockApi(page, log, handlers) {
  await page.route(/^(http:\/\/localhost:8080|https:\/\/nabd-f0zb\.onrender\.com)\/v1\//, async (route) => {
    const req = route.request();
    if (req.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS });
    const url = new URL(req.url());
    log.apiHost = url.host;
    const path = url.pathname.replace(/^\/v1/, '') + url.search;
    log.requests.push({ method: req.method(), path, body: req.postData(), auth: req.headers()['authorization'] ?? null });
    for (const [method, re, h] of handlers) {
      if (method !== '*' && method !== req.method()) continue;
      const m = path.match(re);
      if (!m) continue;
      if (typeof h === 'function') return h(wrap(route), req, m);
      if (h.abort) return route.abort('failed');
      if (h.delay) await new Promise((r) => setTimeout(r, h.delay));
      return route.fulfill({ headers: CORS, status: h.status ?? 200, contentType: h.status >= 400 ? 'application/problem+json' : 'application/json', body: h.body === undefined ? '' : JSON.stringify(h.body) });
    }
    if (req.method() === 'GET' && /^\/setup\/policies/.test(path)) return route.fulfill({ headers: CORS, status: 200, contentType: 'application/json', body: '[]' }); // FEF-005 idle-lock default
    if (req.method() === 'GET' && /^\/setup\/profile$|^\/schedule\/day$/.test(path)) return route.fulfill({ headers: CORS, status: 200, contentType: 'application/json', body: JSON.stringify({ timezone: 'Asia/Kolkata', region: 'IN' }) }); // FEF-011 clinic-zone default
    log.unmatched.push(`${req.method()} ${path}`);
    return route.fulfill({ headers: CORS, status: 404, contentType: 'application/problem+json', body: JSON.stringify({ type: 'not-found', title: 'Not found', detail: 'The requested resource was not found.' }) });
  });
}

export const results = [];
export function check(id, ok, note = '') {
  results.push({ id, ok, note });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${id}${note ? ' — ' + note : ''}`);
}
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export function dumpLog(tag, log) {
  if (log.pageErrors.length) console.log(`  [${tag}] pageErrors:`, log.pageErrors);
  const c = log.console.filter((x) => !/Failed to load resource/.test(x));
  if (c.length) console.log(`  [${tag}] console:`, c.slice(0, 8));
  if (log.unmatched.length) console.log(`  [${tag}] unmatched:`, [...new Set(log.unmatched)]);
}
