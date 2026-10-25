// OPS-047.1 / OPS-067.1 same-doctor contention: VUS (20) virtual receptionists of ONE tenant check patients in on ONE doctor
// (per-doctor-per-day advisory lock, QueueRepository) and every check-in writes an audit row (one chain head per tenant).
// Gates: check-in p99 < 100 ms is the client-side proxy; the lock-wait p99 < 100 ms, chain-head wait p99 < 50 ms, no deadlock
// and cap never exceeded are read from the database: perf/run.sh samples pg_stat_activity waits and greps the Postgres log
// for "deadlock detected" (see perf/lock-waits.sql). 0 duplicate tokens is checked in teardown.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { BASE, TENANTS, account, token, auth, uuid, summarize } from './lib.js';
import { setup as seedSetup, today } from './checkin.js';

const VUS = parseInt(__ENV.VUS || '20', 10);
const dup = new Counter('duplicate_tokens');
export const options = {
  setupTimeout: '10m',
  scenarios: { contention: { executor: 'constant-vus', vus: VUS, duration: __ENV.DURATION || '5m' } },
  thresholds: { 'http_req_duration{name:contention}': ['p(99)<100'], 'http_req_failed{name:contention}': ['rate<0.001'], duplicate_tokens: ['count==0'] },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};
export function setup() {
  const d = seedSetup();
  return { ten: d.tenants[0] }; // tenant 1 only: everyone fights over its first doctor
}
export default function (data) {
  const ten = data.ten, k = exec.vu.idInTest - 1;
  const slot = exec.vu.iterationInScenario * VUS + k;
  const patientId = ten.walkIns[slot];
  if (!patientId || !ten.doctorId) return;
  const acc = account(1 + (k % 3) * TENANTS); // receptionist (k mod 3) of tenant 1: account() cycles tenants first
  const r = http.post(`${BASE}/queue/check-in`, JSON.stringify({ patientId, doctorId: ten.doctorId, source: 'walk_in', paymentMode: 'cash' }),
    { ...auth(token(acc), { 'Content-Type': 'application/json', 'Idempotency-Key': uuid() }), tags: { name: 'contention' }, responseCallback: http.expectedStatuses(201, 409) });
  check(r, { 'no 5xx': (x) => x.status < 500 });
}
export function teardown(data) {
  const entries = http.get(`${BASE}/queue?date=${today()}`, { ...auth(token(account(1))), tags: { name: 'teardown' } }).json() || [];
  const seen = new Set();
  for (const e of entries) { const key = `${e.doctorId}:${e.tokenNumber}`; if (seen.has(key)) dup.add(1); seen.add(key); }
}
export const handleSummary = (data) => summarize(data, 'contention');
