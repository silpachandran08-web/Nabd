// OPS-046.3: walk-in and appointment check-in across PERF_TENANTS tenants x 3 receptionists, ramping to RATE check-ins/s.
// Gate: p95 < 300 ms, < 0.1% errors, 0 duplicate tokens (teardown re-reads every tenant's queue for today).
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { BASE, TENANTS, RECEPTIONISTS, account, token, auth, uuid, need, summarize } from './lib.js';

const RATE = parseInt(need('RATE'), 10);
const duplicates = new Counter('duplicate_tokens');
const checkins = new Counter('checkins');
export const options = {
  setupTimeout: '10m',
  teardownTimeout: '10m',
  // one VU per receptionist account, so no two VUs ever check in the same patient (dropped_iterations shows if 150 is too few)
  scenarios: { checkin: { executor: 'ramping-arrival-rate', startRate: 1, timeUnit: '1s', preAllocatedVUs: TENANTS * RECEPTIONISTS, maxVUs: TENANTS * RECEPTIONISTS,
    stages: [{ target: RATE, duration: __ENV.RAMP || '5m' }, { target: RATE, duration: __ENV.DURATION || '30m' }] } },
  thresholds: { 'http_req_duration{name:checkin}': ['p(95)<300'], 'http_req_failed{name:checkin}': ['rate<0.001'], duplicate_tokens: ['count==0'] },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};

const today = () => new Date(Date.now() + 330 * 60000).toISOString().slice(0, 10); // seed tenants are Asia/Kolkata

/** Per tenant: today's bookings (appointment check-ins) and registry patients without one (walk-ins). */
export function setup() {
  const tenants = [];
  for (let k = 1; k <= TENANTS; k++) {
    const t = token(account(k)); // VU-less: receptionist 1 of tenant k
    const appts = http.get(`${BASE}/appointments?date=${today()}&limit=100`, { ...auth(t), tags: { name: 'setup' } }).json('data') || [];
    const booked = new Set(appts.map((a) => a.patientId));
    const walkIns = [];
    let cursor = null;
    for (let p = 0; p < 3; p++) {
      const r = http.get(`${BASE}/patients/registry?limit=200${cursor ? '&cursor=' + encodeURIComponent(cursor) : ''}`, { ...auth(t), tags: { name: 'setup' } });
      if (r.status !== 200) break;
      for (const row of r.json('rows')) if (!booked.has(row.id) && row.queueToken == null) walkIns.push(row.id);
      cursor = r.json('page.nextCursor');
      if (!cursor) break;
    }
    tenants.push({ k, appts: appts.filter((a) => a.status === 'scheduled').map((a) => ({ id: a.id, patientId: a.patientId, doctorId: a.doctorId })),
      walkIns, doctorId: appts[0] && appts[0].doctorId });
  }
  return { tenants };
}

export default function (data) {
  const acc = account(exec.vu.idInTest);
  const ten = data.tenants[acc.tenant - 1];
  const slot = exec.vu.iterationInScenario * RECEPTIONISTS + acc.r; // receptionist r takes every 3rd booking / patient
  let body;
  if (slot < ten.appts.length) {
    const a = ten.appts[slot];
    body = { appointmentId: a.id, patientId: a.patientId, doctorId: a.doctorId, source: 'returning' };
  } else if (slot - ten.appts.length < ten.walkIns.length && ten.doctorId) {
    body = { patientId: ten.walkIns[slot - ten.appts.length], doctorId: ten.doctorId, source: 'walk_in', paymentMode: 'cash' };
  } else {
    return; // this receptionist's synthetic patients are used up; seed more patients or shorten DURATION
  }
  const r = http.post(`${BASE}/queue/check-in`, JSON.stringify(body),
    { ...auth(token(acc), { 'Content-Type': 'application/json', 'Idempotency-Key': uuid() }), tags: { name: 'checkin' } });
  if (check(r, { 'check-in 201': (x) => x.status === 201 })) checkins.add(1);
}

export function teardown(data) {
  for (const ten of data.tenants) {
    const entries = http.get(`${BASE}/queue?date=${today()}`, { ...auth(token(account(ten.k))), tags: { name: 'teardown' } }).json() || [];
    const seen = new Set();
    for (const e of entries) {
      const key = `${e.doctorId}:${e.tokenNumber}`;
      if (seen.has(key)) duplicates.add(1, { tenant: String(ten.k) });
      seen.add(key);
    }
  }
}

export function handleSummary(data) {
  return summarize(data, 'checkin');
}
