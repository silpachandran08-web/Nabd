// OPS-046.2: registry filters + search mix (OPS-008 read path) at RATE requests/s for DURATION.
// Gate: p95 < 300 ms and < 0.1% errors. Run through perf/run.sh registry (one command, synthetic credentials only).
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { BASE, account, token, auth, need, summarize } from './lib.js';

const RATE = parseInt(need('RATE'), 10); // requests/s; run.sh passes 2 x PEAK_RPS
export const options = {
  scenarios: { registry: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '30m',
    preAllocatedVUs: Math.max(10, RATE * 2), maxVUs: Math.max(50, RATE * 6) } },
  thresholds: { 'http_req_duration{name:registry}': ['p(95)<300'], 'http_req_failed{name:registry}': ['rate<0.001'] },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};

// weights from the reception screen: default list, chips, typed searches, and scrolling to page 2
const MIX = [['?filter=all', 30], ['?filter=recent', 8], ['?filter=followup', 8], ['?filter=balance', 8], ['?filter=package', 4],
  ['?filter=duplicate', 4], ['?filter=archived', 3], ['?q=a', 8], ['?q=ra', 7], ['?q=000123', 5], ['?q=T-3', 5], ['?q=zzqx', 2], ['page2', 8]];
const TOTAL = MIX.reduce((s, [, w]) => s + w, 0);
function pick() {
  let r = Math.random() * TOTAL;
  for (const [q, w] of MIX) if ((r -= w) < 0) return q;
  return MIX[0][0];
}

export default function () {
  const t = token(account(exec.vu.idInTest));
  let q = pick();
  if (q === 'page2') {
    const first = http.get(`${BASE}/patients/registry?limit=50`, { ...auth(t), tags: { name: 'registry' } });
    const cursor = first.status === 200 ? first.json('page.nextCursor') : null;
    if (!cursor) return;
    q = `?limit=50&cursor=${encodeURIComponent(cursor)}`;
  } else {
    q += '&limit=50';
  }
  const r = http.get(`${BASE}/patients/registry${q}`, { ...auth(t), tags: { name: 'registry' } });
  check(r, { 'registry 200': (x) => x.status === 200 });
}

export function handleSummary(data) {
  return summarize(data, 'registry');
}
