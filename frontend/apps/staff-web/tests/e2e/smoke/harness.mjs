// FEF-049 smoke harness: role sign-in fixtures, network helpers (request counting, fault injection), axe per step,
// timing budgets, sharding. Two targets: SMOKE_TARGET=mock (qa-lib API mocks, any built frontend) and
// SMOKE_TARGET=preview (a real OPS-036 preview: APP + SMOKE_API + SMOKE_LOGINS/SMOKE_FIXTURES files from the seed).
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { launch, seedClinic, seedPlatform, mockApi } from '../qa-lib.mjs';
import { permsFor, roleName, blockingViolations, applyKnown } from './core.mjs';
export { permsFor, axeReport, shard, NO_GRANTS } from './core.mjs';

export const TARGET = process.env.SMOKE_TARGET ?? 'mock';
export const APP = process.env.APP ?? 'http://localhost:3000';
export const API_BASE = process.env.SMOKE_API ?? 'http://localhost:8080/v1';
const readJson = (env) => (process.env[env] ? JSON.parse(readFileSync(process.env[env], 'utf8')) : null);

/** Real login against the preview (the same /auth/login call the login page makes); creds come from the seed's logins file.
 * One login per role and tenant per shard: logging in for every case trips the login rate limit (429). */
const sessions = new Map();
export function login(role, tenantKey) {
  const k = `${role}@${tenantKey}`;
  if (!sessions.has(k)) sessions.set(k, loginOnce(role, tenantKey).catch((e) => { sessions.delete(k); throw e; }));
  return sessions.get(k);
}
async function loginOnce(role, tenantKey) {
  const t = readJson('SMOKE_LOGINS')?.tenants?.[tenantKey];
  const cred = t?.staff?.[role];
  if (!cred) throw new Error(`no ${role} login for tenant ${tenantKey} in SMOKE_LOGINS`);
  let res;
  for (let i = 0; i < 3; i++) { // the API allows 20 logins per IP per minute; back off instead of failing the case
    res = await fetch(`${API_BASE}/auth/login`, { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ tenantSlug: t.slug, email: cred.email, pin: cred.pin }) });
    if (res.status !== 429) break;
    await new Promise((r) => setTimeout(r, 20000));
  }
  if (!res.ok) throw new Error(`login ${role}@${tenantKey} -> ${res.status}`);
  return res.json();
}

/** Signs the page in as `role` of `tenant` before any app script runs. */
export async function signIn(page, role, tenant, extraPerms = []) {
  if (role === 'operator') return seedPlatform(page); // FEF-050 J15: the platform console (mock target only)
  if (TARGET === 'mock') {
    return seedClinic(page, [...permsFor(role), ...extraPerms], { tenantRegion: tenant.region, tenantName: tenant.name, roleName: roleName(role), staffName: `Synthetic ${roleName(role)}` });
  }
  const t = await login(role, tenant.key);
  await page.addInitScript(([a, r]) => { localStorage.setItem('nabd_access_token', a); localStorage.setItem('nabd_refresh_token', r); }, [t.accessToken, t.refreshToken]);
}

/** Preview data the seed wrote for the journeys (entry ids, doctor ids, charge names), per tenant. */
export const previewFixtures = (tenantKey) => readJson('SMOKE_FIXTURES')?.tenants?.[tenantKey] ?? null;

/**
 * Opens a browser for one case. `mocks` are qa-lib handlers (mock target only); `faults` are handlers of the same
 * shape injected in front of the mocks, or in front of the real API on a preview (fault injection).
 */
export async function open({ viewport, mocks = [], faults = [], locale = 'en', clock = false }) {
  const s = await launch({ viewport });
  if (locale !== 'en') await s.context.addCookies([{ name: 'nabd_locale', value: locale, url: APP }]); // FEF-036 cookie
  if (process.env.SMOKE_LOWEND) await (await s.context.newCDPSession(s.page)).send('Emulation.setCPUThrottlingRate', { rate: Number(process.env.SMOKE_LOWEND) || 4 }); // FEF-050 low-end Android stand-in (chromium)
  if (clock) await s.page.clock.install({ time: new Date() }); // FEF-050: fake timers that still run; journeys fastForward them
  if (TARGET === 'mock') await mockApi(s.page, s.log, [...faults, ...mocks]);
  else {
    s.page.on('request', (r) => { if (r.url().startsWith(API_BASE)) s.log.requests.push({ method: r.method(), path: r.url().slice(API_BASE.length) }); });
    if (faults.length) await s.page.route((u) => u.href.startsWith(API_BASE), async (route) => {
      const req = route.request(); const path = req.url().slice(API_BASE.length);
      const f = faults.find(([m, re]) => (m === '*' || m === req.method()) && re.test(path));
      if (!f) return route.continue();
      const h = f[2];
      if (h.abort) return route.abort('failed');
      return route.fulfill({ status: h.status ?? 500, contentType: 'application/problem+json', body: JSON.stringify(h.body ?? { title: 'Injected fault' }) });
    });
  }
  // FEF-050 J12 (maker-checker): a second person in the same browser, same mock back end and log.
  s.second = async (role, tenant, extraPerms = []) => {
    const ctx = await s.browser.newContext({ viewport: viewport ?? { width: 1280, height: 900 }, timezoneId: 'Asia/Riyadh' });
    const page = await ctx.newPage();
    page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') s.log.console.push(`[${m.type()}] ${m.text()}`); });
    page.on('pageerror', (e) => s.log.pageErrors.push(String(e)));
    await mockApi(page, s.log, [...faults, ...mocks]);
    await signIn(page, role, tenant, extraPerms);
    return page;
  };
  s.count = (method, re) => s.log.requests.filter((r) => (method === '*' || r.method === method) && re.test(r.path)).length;
  return s;
}

// ---- axe --------------------------------------------------------------------------------------
let axeSource = null;
const AXE_KNOWN = JSON.parse(readFileSync(new URL('./axe-known.json', import.meta.url))).entries;
function axe() {
  if (axeSource) return axeSource;
  const path = process.env.AXE_CORE_PATH || createRequire(import.meta.url).resolve('axe-core/axe.min.js');
  return (axeSource = readFileSync(path, 'utf8'));
}
/** Runs axe on the current page (page.evaluate is not subject to the app's CSP) and records the step. */
export async function axeStep(page, step, record) {
  await page.evaluate(axe());
  const r = await page.evaluate(() => window.axe.run(document, { resultTypes: ['violations'] }));
  const { blocking, known } = applyKnown(blockingViolations(r.violations), AXE_KNOWN, new Date().toISOString().slice(0, 10));
  const entry = { step, url: page.url().replace(/^https?:\/\/[^/]+/, ''), violations: blocking, known };
  record.push(entry);
  return entry;
}

// ---- layout, console ---------------------------------------------------------------
export const noHorizontalScroll = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
/** Up to 4 elements whose right edge is past the viewport (for the failure message). */
export const scrollOffenders = (page) => page.evaluate(() => [...document.querySelectorAll('body *')].filter((e) => e.getBoundingClientRect().right > document.documentElement.clientWidth + 1 && getComputedStyle(e).position !== 'fixed')
  .slice(0, 4).map((e) => `${e.tagName.toLowerCase()}${e.className && typeof e.className === 'string' ? '.' + e.className.split(' ')[0] : ''} right=${Math.round(e.getBoundingClientRect().right)}`));
export const consoleNoise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|net::ERR_FAILED|preloaded using link preload/.test(x))];

// ---- catalogue strings (FEF-050 hi project): page objects look labels up instead of hard-coding English ----
const MSG = new Map();
/** msg('hi', 'arrivals.walkIn.title', { name }) -> the catalogue string of packages/i18n/messages/<locale>/<file>.json. */
export function msg(locale, key, vars = {}) {
  const [file, ...path] = key.split('.');
  const id = `${locale}/${file}`;
  if (!MSG.has(id)) MSG.set(id, JSON.parse(readFileSync(new URL(`../../../../../packages/i18n/messages/${id}.json`, import.meta.url), 'utf8')));
  const v = path.reduce((o, k) => o?.[k], MSG.get(id));
  if (typeof v !== 'string') throw new Error(`no catalogue string ${locale}:${key}`);
  return v.replace(/\{(\w+)\}/g, (_, n) => String(vars[n] ?? `{${n}}`));
}

/** Visible text or attributes inside `main` and dialogs that still read as plain English (>= 3 ASCII letters), minus data values in `allow`. */
export function untranslated(page, allow) {
  return page.evaluate((allow) => {
    const out = [];
    const PROPER = /\b(PIN|UPI|OTP|WhatsApp|SMS|Nabd|MRN|SpO2|BP|ID|mmHg|bpm|kg|cm)\b/g; // abbreviations are the same in every language
    const ok = (s) => { const t = s.trim().replace(PROPER, ''); return !/[A-Za-z]{3,}/.test(t) || allow.some((n) => t.includes(n)) || /^(MRN-|https?:|#?\d)/.test(t); };
    for (const root of document.querySelectorAll('main, [role=dialog]')) {
      const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
      for (let n; (n = w.nextNode());) {
        const el = n.parentElement; if (!el) continue;
        const cs = getComputedStyle(el); if (cs.display === 'none' || cs.visibility === 'hidden') continue;
        if (!ok(n.nodeValue)) out.push(n.nodeValue.trim());
      }
      for (const el of root.querySelectorAll('[aria-label],[placeholder],[title]')) for (const a of ['aria-label', 'placeholder', 'title']) { const v = el.getAttribute(a); if (v && !ok(v)) out.push(`${a}=${v}`); }
    }
    return [...new Set(out)];
  }, allow);
}
