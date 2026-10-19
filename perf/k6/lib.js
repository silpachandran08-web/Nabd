// OPS-046: shared k6 helpers. Synthetic accounts only (perf/data/seed.sql): slug perf-<PERF_TAG>-NN,
// rcp1..3@<slug>.invalid, PIN from PERF_PIN in the runner's environment. Nothing secret lives in the repo.
import http from 'k6/http';
import { sleep } from 'k6';

export const BASE = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '') + '/v1';
export const TAG = __ENV.PERF_TAG || 's2';
export const TENANTS = parseInt(__ENV.PERF_TENANTS || '50', 10);
export const RECEPTIONISTS = parseInt(__ENV.PERF_RECEPTIONISTS || '3', 10);
const PIN = __ENV.PERF_PIN;

export function need(name) {
  if (!__ENV[name]) throw new Error(`${name} is required (see perf/run.sh)`);
  return __ENV[name];
}
export const slug = (k) => `perf-${TAG}-${String(k).padStart(2, '0')}`;
/** VU n -> its own receptionist account, spread over every tenant first. */
export function account(n) {
  const i = (n - 1) % (TENANTS * RECEPTIONISTS);
  const k = (i % TENANTS) + 1;
  const r = Math.floor(i / TENANTS);
  return { tenant: k, r, tenantSlug: slug(k), email: `rcp${r + 1}@${slug(k)}.invalid` };
}
export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

const sessions = {};
/** Logs in once per account per VU; re-logs in before the 15-minute access token expires. The API allows 20 logins
 *  per IP per minute (a security control), so a 429 waits for the window to roll over instead of failing the run. */
export function token(acc) {
  const s = sessions[acc.email];
  if (s && Date.now() < s.until) return s.access;
  for (let attempt = 0; attempt < 12; attempt++) {
    const r = http.post(`${BASE}/auth/login`, JSON.stringify({ tenantSlug: acc.tenantSlug, email: acc.email, pin: PIN }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' }, responseCallback: http.expectedStatuses(200, 429) });
    if (r.status === 200) {
      sessions[acc.email] = { access: r.json('accessToken'), until: Date.now() + 12 * 60 * 1000 };
      return sessions[acc.email].access;
    }
    if (r.status !== 429) throw new Error(`login ${acc.email} -> ${r.status}`);
    sleep(6 + Math.random() * 4);
  }
  throw new Error(`login ${acc.email} rate-limited`);
}
export const auth = (t, extra = {}) => ({ headers: { Authorization: `Bearer ${t}`, ...extra } });

/** p50/p95/p99 and error rate per tagged request name, the shape perf/report.mjs reads. */
export function summarize(data, profile) {
  const out = { profile, generatedAt: new Date().toISOString(), env: { base: BASE, tenants: TENANTS, rate: __ENV.RATE, duration: __ENV.DURATION }, endpoints: {} };
  for (const [key, m] of Object.entries(data.metrics)) {
    const hit = key.match(/^http_req_duration\{name:(.+)\}$/);
    if (hit && m.values) out.endpoints[hit[1]] = { p50: m.values['p(50)'], p95: m.values['p(95)'], p99: m.values['p(99)'], count: m.values.count };
  }
  const d = data.metrics.http_req_duration?.values ?? {};
  const failed = data.metrics[`http_req_failed{name:${profile}}`] ?? data.metrics.http_req_failed; // the gated endpoint, not setup/login
  out.overall = { p50: d['p(50)'], p95: d['p(95)'], p99: d['p(99)'], requests: data.metrics.http_reqs?.values?.count,
    errorRate: failed?.values?.rate };
  out.custom = Object.fromEntries(Object.entries(data.metrics).filter(([k]) => /^(duplicate_tokens|checkins|dropped_iterations)$/.test(k)).map(([k, m]) => [k, m.values]));
  out.thresholds = Object.fromEntries(Object.entries(data.metrics).filter(([, m]) => m.thresholds)
    .map(([k, m]) => [k, Object.fromEntries(Object.entries(m.thresholds).map(([t, v]) => [t, v.ok]))]));
  return { [`${__ENV.OUT || '.'}/${profile}-summary.json`]: JSON.stringify(out, null, 2), stdout: JSON.stringify(out.overall) + '\n' };
}
