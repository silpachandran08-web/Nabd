// FEF-049 smoke harness: role sign-in fixtures, network helpers (request counting, fault injection), axe per step,
// timing budgets, sharding. Two targets: SMOKE_TARGET=mock (qa-lib API mocks, any built frontend) and
// SMOKE_TARGET=preview (a real OPS-036 preview: APP + SMOKE_API + SMOKE_LOGINS/SMOKE_FIXTURES files from the seed).
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { launch, seedClinic, mockApi } from '../qa-lib.mjs';
import { ROLES, permsFor, blockingViolations, applyKnown } from './core.mjs';
export { permsFor, axeReport, shard } from './core.mjs';

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
export async function signIn(page, role, tenant) {
  if (TARGET === 'mock') {
    return seedClinic(page, permsFor(role), { tenantRegion: tenant.region, tenantName: tenant.name, roleName: ROLES[role].name, staffName: `Synthetic ${ROLES[role].name}` });
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
export async function open({ viewport, mocks = [], faults = [] }) {
  const s = await launch({ viewport });
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
export const consoleNoise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|net::ERR_FAILED|preloaded using link preload/.test(x))];
