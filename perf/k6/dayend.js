// OPS-047.2 day-end: every tenant closes its day (POST /billing/day-close) while check-ins/payments keep arriving at RATE/s.
// Gate: day-close p95 < 1 s (DAYEND_P95_MS), no 5xx on either flow. CLOSER_PREFIX is the seeded account that holds billing:approve.
import http from 'k6/http';
import { check } from 'k6';
import { BASE, TENANTS, slug, token, auth, uuid, need, summarize } from './lib.js';
import rush from './rush.js';
import { setup as seedSetup } from './checkin.js';

const RATE = parseInt(need('RATE'), 10), DUR = __ENV.DURATION || '10m';
export const options = {
  setupTimeout: '10m',
  scenarios: {
    payments: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DUR, preAllocatedVUs: 150, maxVUs: 150, exec: 'payments' },
    close: { executor: 'per-vu-iterations', vus: TENANTS, iterations: 1, startTime: __ENV.CLOSE_AT || '7m', exec: 'close' }, // 7m = 70% of the default 10m
  },
  thresholds: { 'http_req_duration{name:dayclose}': [`p(95)<${__ENV.DAYEND_P95_MS || 1000}`], 'http_req_failed{name:dayclose}': ['rate<0.001'] },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};
export const setup = seedSetup;
export const payments = (d) => rush(d);
export function close() {
  const k = ((__VU - 1) % TENANTS) + 1, p = __ENV.CLOSER_PREFIX || 'rcp1';
  const acc = { tenantSlug: slug(k), email: `${p}@${slug(k)}.invalid` };
  const r = http.post(`${BASE}/billing/day-close`, JSON.stringify({ countedCash: 0, note: 'perf day-end' }),
    { ...auth(token(acc), { 'Content-Type': 'application/json', 'Idempotency-Key': uuid() }), tags: { name: 'dayclose' }, responseCallback: http.expectedStatuses(200, 409) });
  check(r, { 'day-close not 5xx': (x) => x.status < 500 });
}
export const handleSummary = (data) => summarize(data, 'dayclose', 'dayend');
