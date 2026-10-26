// OPS-047.1 morning rush: PERF_TENANTS x 3 receptionists, RATE check-ins/s in total (2/s per tenant = 100 at 50 tenants) for
// DURATION, each followed by board reads, a status transition and a checkout. Gates: check-in p95 < 300 ms, board < 200 ms,
// checkout < 500 ms, 0 duplicate tokens, 0 double check-ins (every check-in answered 201 once), 0 cross-tenant rows.
// Needs the OPS-046 seed (perf/data/seed.sql); CHARGE_CODE is a catalogue code present in every seeded tenant.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { BASE, TENANTS, RECEPTIONISTS, account, token, auth, uuid, need, summarize } from './lib.js';
import { setup as seedSetup, today } from './checkin.js';

const RATE = parseInt(need('RATE'), 10);
const CHARGE = __ENV.CHARGE_CODE || 'CONSULT';
const dup = new Counter('duplicate_tokens'), cross = new Counter('cross_tenant_rows'), doubles = new Counter('double_check_ins');
export const options = {
  setupTimeout: '10m', teardownTimeout: '10m',
  scenarios: { rush: { executor: 'ramping-arrival-rate', startRate: 1, timeUnit: '1s', preAllocatedVUs: TENANTS * RECEPTIONISTS, maxVUs: TENANTS * RECEPTIONISTS,
    stages: [{ target: RATE, duration: __ENV.RAMP || '5m' }, { target: RATE, duration: __ENV.DURATION || '30m' }] } },
  thresholds: { 'http_req_duration{name:rush}': ['p(95)<300'], 'http_req_duration{name:board}': ['p(95)<200'], 'http_req_duration{name:checkout}': ['p(95)<500'],
    'http_req_failed{name:rush}': ['rate<0.001'], duplicate_tokens: ['count==0'], double_check_ins: ['count==0'], cross_tenant_rows: ['count==0'] },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};
export const setup = seedSetup;

export default function (data) {
  const acc = account(exec.vu.idInTest), ten = data.tenants[acc.tenant - 1];
  const slot = exec.vu.iterationInScenario * RECEPTIONISTS + acc.r;
  const pick = slot < ten.appts.length ? { appt: ten.appts[slot] } : { walk: ten.walkIns[slot - ten.appts.length] };
  if (!pick.appt && !(pick.walk && ten.doctorId)) return; // synthetic patients used up
  const body = pick.appt ? { appointmentId: pick.appt.id, patientId: pick.appt.patientId, doctorId: pick.appt.doctorId, source: 'returning' }
    : { patientId: pick.walk, doctorId: ten.doctorId, source: 'walk_in', paymentMode: 'cash' };
  const h = auth(token(acc), { 'Content-Type': 'application/json', 'Idempotency-Key': uuid() });
  const r = http.post(`${BASE}/queue/check-in`, JSON.stringify(body), { ...h, tags: { name: 'rush' } });
  if (!check(r, { 'check-in 201': (x) => x.status === 201 })) return;
  const id = r.json('id');
  http.get(`${BASE}/queue?date=${today()}`, { ...auth(token(acc)), tags: { name: 'board' } });
  const t = (name, status, expected) => http.patch(`${BASE}/queue/${id}/status`, JSON.stringify({ status, expectedStatus: expected }),
    { ...auth(token(acc), { 'Content-Type': 'application/json' }), tags: { name } });
  t('transition', 'in_consultation', 'checked_in');
  http.post(`${BASE}/billing/checkout/${id}`, JSON.stringify({ lineItems: [{ chargeCode: CHARGE, quantity: 1 }] }),
    { ...auth(token(acc), { 'Content-Type': 'application/json', 'Idempotency-Key': uuid() }), tags: { name: 'checkout' } });
}

export function teardown(data) {
  for (const ten of data.tenants) {
    const entries = http.get(`${BASE}/queue?date=${today()}`, { ...auth(token(account(ten.k))), tags: { name: 'teardown' } }).json() || [];
    const tokens = new Set(), patients = new Set();
    let tid;
    for (const e of entries) {
      const k = `${e.doctorId}:${e.tokenNumber}`;
      if (tokens.has(k)) dup.add(1);
      tokens.add(k);
      if (e.patientId && patients.has(e.patientId)) doubles.add(1);
      patients.add(e.patientId);
      if (e.tenantId) { tid = tid || e.tenantId; if (e.tenantId !== tid) cross.add(1); } // one board = one tenant
    }
  }
}
export const handleSummary = (data) => summarize(data, 'rush');
