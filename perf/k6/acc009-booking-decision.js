// ACC-009: 50 bookings per minute for one tenant through the internal booking-request path; gate p95 < 2 s end to end inside Core.
// Needs: BASE (Core URL), TENANT_ID, DOCTOR_ID, INTERNAL_TOKEN (aud=internal service token naming the tenant), PATIENT_REF, FIRST_SLOT (ISO, on the doctor's grid).
// Not run in the sprint sandbox (k6 is not installed); Acc009BookingRoutingApiTest has a sequential stand-in with the same p95 gate.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const need = (k) => { if (!__ENV[k]) throw new Error(`set ${k}`); return __ENV[k]; };
export const options = {
  scenarios: { booking: { executor: 'constant-arrival-rate', rate: 50, timeUnit: '1m', duration: '5m', preAllocatedVUs: 10, maxVUs: 20 } },
  thresholds: { 'http_req_duration{name:booking}': ['p(95)<2000'], 'http_req_failed{name:booking}': ['rate<0.001'] },
};

export default function () {
  const slot = new Date(Date.parse(need('FIRST_SLOT')) + exec.scenario.iterationInTest * 15 * 60000).toISOString();
  const res = http.post(`${need('BASE')}/internal/v1/tenants/${need('TENANT_ID')}/booking-requests`,
    JSON.stringify({ patientRef: need('PATIENT_REF'), doctorId: need('DOCTOR_ID'), slotStart: slot, channel: 'whatsapp' }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${need('INTERNAL_TOKEN')}`, 'Idempotency-Key': crypto.randomUUID() }, tags: { name: 'booking' } });
  check(res, { created: (r) => r.status === 201 });
}
