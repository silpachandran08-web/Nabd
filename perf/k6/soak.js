// OPS-047.3 soak: the rush mix at 30% of peak for 8 h (weekly). k6 only drives load; the trend checks (heap, Hikari pool,
// outbox lag) read the API's Prometheus endpoint every METRICS_EVERY s and are judged by perf/trend.mjs (slope per hour).
import http from 'k6/http';
import { need, summarize } from './lib.js';
import rush, { setup as rushSetup } from './rush.js';

const RATE = Math.max(1, Math.round(parseInt(need('RATE'), 10) * 0.3));
export const options = {
  setupTimeout: '10m',
  scenarios: { soak: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '8h', preAllocatedVUs: 150, maxVUs: 150 },
    probe: { executor: 'constant-arrival-rate', rate: 1, timeUnit: __ENV.METRICS_EVERY ? `${__ENV.METRICS_EVERY}s` : '60s', duration: __ENV.DURATION || '8h', preAllocatedVUs: 1, exec: 'probe' } },
  thresholds: { 'http_req_failed{name:rush}': ['rate<0.001'] },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};
export const setup = rushSetup;
export default (d) => rush(d);
/** Prints one JSON line per scrape ("TREND {...}"); perf/trend.mjs reads them from the k6 log. */
export function probe() {
  const url = __ENV.METRICS_URL; // actuator port, never routed by the ingress: run from inside the cell
  if (!url) return;
  const text = http.get(url, { tags: { name: 'metrics' } }).body || '';
  const pick = (re) => { const m = text.match(re); return m ? parseFloat(m[1]) : null; };
  console.log('TREND ' + JSON.stringify({ t: Date.now(), heap: pick(/^jvm_memory_used_bytes\{[^}]*area="heap"[^}]*\} (\S+)/m),
    pool: pick(/^hikaricp_connections_active\{[^}]*\} (\S+)/m), outboxAge: pick(/^outbox_oldest_pending_age_seconds\{[^}]*\} (\S+)/m) }));
}
export const handleSummary = (data) => summarize(data, 'rush', 'soak');
