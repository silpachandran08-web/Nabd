// ACC-005 / ACC-006 E2E: My Schedule shows an overnight block as "20:00–01:00 (+1)", a part-day leave sends its window,
// and the saved leave lists the affected bookings with a one-tap offer (no text input on the path).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3802 node s16-acc-005-006-schedule.mjs
import { launch, seedClinic, mockApi, check, results, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const json = (r, body, status = 200) => r.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
let st;
const handlers = () => [
  ['GET', new RegExp(`^/doctors/${SELF}/working-hours`), (r) => json(r, [{ id: 'h1', dayOfWeek: 2, startTime: '20:00:00', endTime: '01:00:00', endsNextDay: true, slotMinutes: 15, maxPatients: 30 }])],
  ['GET', new RegExp(`^/doctors/${SELF}/leave/l1/affected-appointments`), (r) => json(r, { items: st.items })],
  ['GET', new RegExp(`^/doctors/${SELF}/leave`), (r) => json(r, [])],
  ['POST', new RegExp(`^/doctors/${SELF}/leave`), (r, req) => {
    st.leaveBody = JSON.parse(req.postData());
    return json(r, { id: 'l1', dateFrom: st.leaveBody.dateFrom, dateTo: st.leaveBody.dateTo, reason: null, affectedPackagePatients: [], startTime: st.leaveBody.startTime, endTime: st.leaveBody.endTime, affectedAppointments: [{ id: 'a1', start: '2026-03-11T10:00:00Z' }, { id: 'a2', start: '2026-03-11T10:15:00Z' }] }, 201);
  }],
  ['POST', /^\/appointments\/(a1|a2)\/offer-alternatives/, (r, req, m) => {
    st.offered.push(m[1]);
    st.items = st.items.map((i) => i.appointmentId === m[1] ? { ...i, offerStatus: 'sent' } : i);
    return json(r, { offerId: 'o-' + m[1], slots: [], expiresAt: '2026-03-11T12:00:00Z' }, 202);
  }],
];
const item = (id, consent) => ({ appointmentId: id, patientId: 'p-' + id, patientRef: 'ref-' + id, start: id === 'a1' ? '2026-03-11T10:00:00Z' : '2026-03-11T10:15:00Z', channelConsent: consent, offerStatus: null,
  alternatives: ['2026-03-11T10:30:00Z', '2026-03-11T10:45:00Z', '2026-03-11T11:00:00Z'] });
let s;
try {
  st = { offered: [], items: [item('a1', true), item('a2', false)] };
  s = await launch();
  await seedClinic(s.page, ['queue:view', 'queue:edit']);
  await mockApi(s.page, s.log, handlers());
  await s.page.goto(`${APP}/my-schedule`, { waitUntil: 'networkidle' });
  check('1 overnight block shows (+1)', /20:00–01:00 \(\+1\)/.test(await s.page.locator('body').innerText()));
  await s.page.getByRole('button', { name: 'Apply leave' }).click();
  await s.page.getByLabel('Only part of the day').check();
  await s.page.getByRole('button', { name: 'Record leave' }).click();
  await s.page.getByText('2 bookings affected').waitFor({ timeout: 5000 });
  check('2 part-day leave sends its window', st.leaveBody?.startTime === '14:00' && st.leaveBody?.endTime === '17:00', JSON.stringify(st.leaveBody));
  const dlg = s.page.getByRole('dialog');
  check('3 the affected panel has no text input', await dlg.locator('input[type=text], input:not([type]), textarea, select').count() === 0);
  check('4 a patient without consent gets a Call patient link, not an offer button', await dlg.getByRole('link', { name: 'Call patient' }).count() === 1);
  await dlg.getByRole('button', { name: 'Offer alternatives', exact: true }).click();
  await dlg.getByText('Offer sent').waitFor({ timeout: 5000 });
  check('5 one tap sends the offer and the row shows the status', st.offered.join() === 'a1');
} catch (e) { check('run', false, String(e)); }
finally { if (s) await s.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok);
console.log(`${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
