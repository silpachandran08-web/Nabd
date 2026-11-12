// FEF-051 screen inventory and matrix (pure: no browser, unit-tested in tests/unit). Every page.tsx of staff-web and patient-web is a
// screen unless EXCLUDED says why, so a new page is snapshotted without editing this file (and fails until its baseline is approved).
import { readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

export const FRONTEND = fileURLToPath(new URL('../../../../../', import.meta.url)); // frontend/
export const APPS = { staff: 'apps/staff-web/app', patient: 'apps/patient-web/app' };

export const THEMES = ['light', 'dark'];
export const DEVICES = { desktop: { width: 1366, height: 768 }, tablet: { width: 768, height: 1024 }, phone: { width: 375, height: 812 } }; // smoke/matrix.mjs
export const STAFF_DEVICES = ['desktop', 'tablet', 'phone'];
export const PATIENT_DEVICES = ['phone', 'desktop'];
// PR runs: en + hi (ar is Phase 4). Nightly passes VR_LOCALES=en,hi,ar (the ar catalogue already ships).
export const STAFF_LOCALES = ['en', 'hi'];
export const PATIENT_LOCALES = ['en', 'hi', 'kn', 'te']; // patient-web PATIENT_LOCALES (app/lib/messages.ts)

/** Route -> reason it is not snapshotted. */
export const EXCLUDED = {
  '/qa-throw': 'test-only error trigger', '/login/qa-throw': 'test-only error trigger', '/owner/qa-throw': 'test-only error trigger', '/platform/qa-throw': 'test-only error trigger',
  '/[...slug]': 'catch-all for unbuilt areas: one skeleton, already covered by the named built-later screens', '/owner/[...slug]': 'catch-all', '/platform/[...slug]': 'catch-all',
};
/** Dynamic segments -> the synthetic id the mocks answer for. */
const PARAMS = { '[token]': 'vrtoken0000000000000001', '[linkId]': 'vrlink00000000000000001', '[id]': 'inv-1', '[tenantId]': 'tenant-1', '[queueEntryId]': '33333333-3333-4333-8333-333333333333' };
/** Screens a journey already has seeded mocks for (smoke/nightly.mjs): the snapshot reuses that journey's back end and role. */
export const JOURNEY_OF = { '/arrivals': 'J2', '/schedule': 'J3', '/consult': 'J5', '/nursing': 'J8', '/checkout/[queueEntryId]': 'J9', '/checkout/otc': 'J10', '/packages': 'J12', '/billing': 'J13', '/platform/provisioning': 'J15' };
const QUERY = { '/consult': '?entry=e1&step=reason' };
const ANONYMOUS = /^\/(login|accept-invite|checkin|display|owner\/login|owner\/accept-invite|platform\/login)(\/|$)|^\/$/;
const DOCTOR = /^\/(consult|my-day|my-schedule|solo|encounters|prescriptions|results|records|follow-ups|tele|specialty)(\/|$)/;
const NURSE = /^\/(nursing|inpatients)(\/|$)/;
const RECEPTION = /^\/(arrivals|checkout)(\/|$)/;

function pages(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...pages(p));
    else if (name === 'page.tsx') out.push(p);
  }
  return out;
}

/** app dir -> [{ route, dir }]: route groups "(x)" removed, dir = the source folder relative to frontend/ (for PR selection). */
export function routesOf(app) {
  const root = join(FRONTEND, APPS[app]);
  return pages(root).map((f) => {
    const rel = relative(root, f).split(sep).slice(0, -1);
    return { route: '/' + rel.filter((s) => !/^\(.*\)$/.test(s)).join('/'), dir: [APPS[app], ...rel].join('/') };
  }).sort((a, b) => a.route.localeCompare(b.route));
}

function roleOf(route, app) {
  if (app === 'patient' || ANONYMOUS.test(route)) return 'anonymous';
  if (route.startsWith('/platform')) return 'operator';
  if (DOCTOR.test(route)) return 'doctor';
  if (NURSE.test(route)) return 'nurse';
  if (RECEPTION.test(route)) return 'receptionist';
  return 'owner';
}

export function screensOf(app) {
  return routesOf(app).filter((r) => !(app === 'staff' && EXCLUDED[r.route])).map(({ route, dir }) => ({
    id: `${app}${route === '/' ? '/home' : route}`.replace(/\[|\]|\.\.\./g, ''), app, route, dir,
    url: route.split('/').map((s) => PARAMS[s] ?? s).join('/') + (QUERY[route] ?? ''),
    role: roleOf(route, app), journey: app === 'staff' ? JOURNEY_OF[route] ?? null : null,
  }));
}

export const SCREENS = [...screensOf('staff'), ...screensOf('patient')];

/** T1-BUG-5: what the error boundaries render (RouteError, consult PanelBoundary, global-error). Also covers anonymous and /platform
 * pages, which send no client telemetry. */
export const CRASH_SELECTOR = '[data-testid^="route-error-"], [data-testid^="panel-error-"], body > main[role="alert"]';

/** T1-BUG-5: every error boundary (global-error, RouteError, PanelBoundary) reports itself through OPS-028 client telemetry with a
 * metric "boundary.<group>.<...>". Such a request means the screen crashed: the harness fails it instead of keeping the crash as a
 * baseline. Returns the metric, or null for any other request. */
export function boundaryCrash(method, url, postData) {
  if (method !== 'POST' || !/\/client-telemetry$/.test(new URL(url).pathname)) return null;
  try { const m = JSON.parse(postData ?? '{}').metric; return typeof m === 'string' && m.startsWith('boundary.') ? m : null; } catch { return null; }
}
