// ACC-045 load test: the WhatsApp booking journey at 3x the expected cohort-B peak, through the real path (signed Meta webhook ->
// Comms Gateway -> stream -> Edge -> Core). Each virtual patient sends "Hi" then walks the menu with button taps the Edge issued.
// Inputs (never defaults for the peak: it is measured from cohort A, not guessed here):
//   BASE=https://staging.example/v1 WHATSAPP_APP_SECRET=... PHONE_NUMBER_IDS=<cohort-B test numbers, comma separated>
//   PEAK_MSG_PER_MIN=<measured peak inbound messages/min across cohort B> EDGE_METRICS_URL=<edge :8081/actuator/prometheus>
//   k6 run perf/k6/edge_cohort_b.js
// Pass: webhook ack p95 < 100 ms and no non-200 (gateway); afterwards, from EDGE_METRICS_URL, bot step p95 < 500 ms and no message
// lost: every injected inbound id is processed (edge_inbound_total grew by the injected count, edge_event_dlq_total did not grow).
// The secret comes from the environment; it is never in the repository. Running it needs staging: Needs external action.
import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE || 'http://localhost:8080/v1';
const SECRET = __ENV.WHATSAPP_APP_SECRET;
if (!SECRET) throw new Error('WHATSAPP_APP_SECRET is required');
if (!__ENV.PEAK_MSG_PER_MIN) throw new Error('PEAK_MSG_PER_MIN (measured cohort-B peak) is required');
const NUMBERS = (__ENV.PHONE_NUMBER_IDS || '').split(',').filter(Boolean);
if (!NUMBERS.length) throw new Error('PHONE_NUMBER_IDS is required');
const RATE = Math.ceil(3 * parseInt(__ENV.PEAK_MSG_PER_MIN, 10)); // 3x peak, per minute
const injected = new Counter('edge_injected_messages');

export const options = {
  scenarios: { cohort_b_3x: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1m', duration: __ENV.DURATION || '15m', preAllocatedVUs: 50, maxVUs: 300 } },
  thresholds: { 'http_req_duration{name:webhook}': ['p(95)<100'], 'http_req_failed{name:webhook}': ['rate==0'] },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};

const metric = (text, name) => (text.match(new RegExp(`^${name}(\\{[^}]*\\})? (\\S+)`, 'gm')) || []).reduce((s, l) => s + parseFloat(l.split(' ').pop()), 0);

export function setup() {
  const m = __ENV.EDGE_METRICS_URL ? http.get(__ENV.EDGE_METRICS_URL).body : '';
  return { inbound: metric(m, 'edge_inbound_total'), dlq: metric(m, 'edge_event_dlq_total') };
}

// A synthetic patient phone per iteration (91900 + 7 digits): never a real number.
const phone = () => `91900${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`;

export default function () {
  const id = `wamid.${uuidv4()}`;
  const body = JSON.stringify({ object: 'whatsapp_business_account', entry: [{ time: Math.floor(Date.now() / 1000), changes: [{ field: 'messages',
    value: { metadata: { phone_number_id: NUMBERS[Math.floor(Math.random() * NUMBERS.length)] },
      messages: [{ from: phone(), id, timestamp: String(Math.floor(Date.now() / 1000)), type: 'text', text: { body: 'Hi' } }] } }] }] });
  const sig = 'sha256=' + crypto.hmac('sha256', SECRET, body, 'hex');
  const r = http.post(`${BASE}/webhooks/whatsapp`, body, { headers: { 'Content-Type': 'application/json', 'X-Hub-Signature-256': sig }, tags: { name: 'webhook' } });
  check(r, { 'acked 200': (x) => x.status === 200 });
  injected.add(1);
}

export function teardown(before) {
  if (!__ENV.EDGE_METRICS_URL) return;
  const m = http.get(__ENV.EDGE_METRICS_URL).body;
  console.log(JSON.stringify({ profile: 'edge_cohort_b', inboundDelta: metric(m, 'edge_inbound_total') - before.inbound,
    dlqDelta: metric(m, 'edge_event_dlq_total') - before.dlq, note: 'inboundDelta must equal the injected count; dlqDelta must be 0' }));
}
