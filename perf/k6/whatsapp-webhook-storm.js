// COM-001 (QA 4.4 webhook storm): signed Meta webhooks at RATE/s (default 200) with 20% exact duplicates and status callbacks
// arriving read-before-delivered. Gate: ack p95 < 100 ms, no non-200. Run:
//   BASE=https://staging.example/v1 WHATSAPP_APP_SECRET=... RATE=200 k6 run perf/k6/whatsapp-webhook-storm.js
// The secret comes from the environment; it is never in the repository. Afterwards check processing lag p95 < 2 s
// (gauge whatsapp.inbound.oldest.age.seconds) and that whatsapp_messages statuses only moved forward.
import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE || 'http://localhost:8080/v1';
const SECRET = __ENV.WHATSAPP_APP_SECRET;
if (!SECRET) throw new Error('WHATSAPP_APP_SECRET is required');
const RATE = parseInt(__ENV.RATE || '200', 10);

export const options = {
  scenarios: { storm: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '60s', preAllocatedVUs: 100, maxVUs: 400 } },
  thresholds: { 'http_req_duration{name:webhook}': ['p(95)<100'], 'http_req_failed{name:webhook}': ['rate==0'] },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};

const status = (id, s) => JSON.stringify({ object: 'whatsapp_business_account', entry: [{ changes: [{ field: 'messages',
  value: { metadata: { phone_number_id: '109876543210' }, statuses: [{ id, status: s, timestamp: String(Math.floor(Date.now() / 1000)) }] } }] }] });
const message = (id) => JSON.stringify({ object: 'whatsapp_business_account', entry: [{ time: Math.floor(Date.now() / 1000), changes: [{ field: 'messages',
  value: { metadata: { phone_number_id: '109876543210' }, messages: [{ from: '919000000099', id, timestamp: '1700000000', type: 'text', text: { body: 'hello' } }] } }] }] });

export default function () {
  const n = __ITER % 10;
  const id = 'wamid.STORM' + uuidv4();
  let body;
  if (n === 9 || n === 4) body = globalThis.last || message(id); // 20% duplicates of the previous body of this VU
  else if (n % 3 === 0) body = status(id, 'read');              // read first ...
  else if (n % 3 === 1) body = status(id, 'delivered');         // ... delivered after it
  else body = message(id);
  globalThis.last = body;
  const sig = 'sha256=' + crypto.hmac('sha256', SECRET, body, 'hex');
  const res = http.post(`${BASE}/webhooks/whatsapp`, body, { headers: { 'Content-Type': 'application/json', 'X-Hub-Signature-256': sig }, tags: { name: 'webhook' } });
  check(res, { 'acked 200': (r) => r.status === 200 });
}
