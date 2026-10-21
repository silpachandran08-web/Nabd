// OPS-009: GET /patients?q= mixed phone / MRN / name (English and Arabic) at RATE requests/s for DURATION.
// Gate: p95 < 300 ms and < 0.1% errors at 500k patients in the region. Not run in the sandbox (no k6, no 500k seed).
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { BASE, account, token, auth, need } from './lib.js';

const RATE = parseInt(need('RATE'), 10);
export const options = {
  scenarios: { search: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '30m',
    preAllocatedVUs: Math.max(10, RATE * 2), maxVUs: Math.max(50, RATE * 6) } },
  thresholds: { 'http_req_duration{name:search}': ['p(95)<300'], 'http_req_failed{name:search}': ['rate<0.001'] },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};

const MIX = [['98123', 30], ['9812345', 15], ['MRN-000123', 15], ['MRN-0001', 5], ['mohammed', 12], ['ahmed al', 6], ['محمد', 10], ['مُحَمَّد', 4], ['zzqx', 3]];
const TOTAL = MIX.reduce((s, [, w]) => s + w, 0);
function pick() {
  let r = Math.random() * TOTAL;
  for (const [q, w] of MIX) if ((r -= w) < 0) return q;
  return MIX[0][0];
}

export default function () {
  const t = token(account(exec.vu.idInTest));
  const r = http.get(`${BASE}/patients?limit=10&q=${encodeURIComponent(pick())}`, { ...auth(t), tags: { name: 'search' } });
  check(r, { 'search 200': (x) => x.status === 200 });
}
