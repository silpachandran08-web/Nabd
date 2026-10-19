// S10 QA ACC-003 (independent): check-in once, Arrived badge, J2 stopwatch (mocked API). NODE_PATH=... APP=http://localhost:3411 node s10qa-acc003.mjs
import { launch, seedClinic, mockApi, check, sleep, results } from './qa-lib.mjs';
const APP = process.env.APP ?? 'http://localhost:3411';
const startTime = new Date().toISOString();
const entry = { id: 'e7', appointmentId: 'ap1', patientId: 'p1', doctorId: 'd1', departmentId: 'dep1', queueDate: '2026-10-19', tokenNumber: 7, status: 'checked_in', priority: false, priorityReason: null, createdAt: startTime, source: 'walk_in', appointmentStatus: 'arrived' };
const base = (checkIn, appts) => [
  ['POST', /^\/queue\/check-in/, checkIn], ['GET', /^\/queue(\?.*)?$/, { body: [] }], ['GET', /^\/staff\/roster/, { body: [{ id: 'd1', name: 'Dr Who', departmentName: 'GP' }] }],
  ['GET', /^\/appointments\?/, { body: { data: appts ?? [{ id: 'ap1', patientId: 'p1', doctorId: 'd1', startTime, status: 'scheduled' }], page: { nextCursor: null, limit: 100 } } }],
  ['GET', /^\/patients\/p1/, { body: { name: 'Pat One', mrn: 'M1', phone: '+9665' } }], ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5 } }],
  ['GET', /^\/doctors\/.*\/delay/, { body: [] }], ['GET', /^\/appointments\/callback-list/, { body: [] }]];
async function scenario(id, path, perms, handlers, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms); await mockApi(page, log, handlers);
    const t0 = Date.now(); await page.goto(APP + path); await fn({ page, log, t0 });
    check(`${id} zero page errors / console errors`, log.pageErrors.length === 0 && log.console.every((x) => /Failed to load resource/.test(x)), JSON.stringify([...log.pageErrors, ...log.console]).slice(0, 300));
  } catch (e) { check(`${id} scenario ran`, false, String(e).split('\n')[0]); } finally { await browser.close(); }
}
const arr = ['queue:view', 'queue:create', 'patients:view'];
const submitFromPicker = async (page) => {
  await page.getByRole('button', { name: 'Check in', exact: true }).first().click(); await sleep(600);   // quick action -> today's appointments picker
  await page.getByRole('button', { name: 'Check in', exact: true }).last().click(); await sleep(400);    // row action -> check-in dialog
  await page.getByRole('button', { name: /^Check in$/ }).last().click(); await sleep(900);               // submit
};
const alerts = async (page) => (await page.getByRole('alert').allInnerTexts()).join(' | ');

await scenario('J2', '/arrivals', arr, base({ status: 201, body: entry }), async ({ page, log, t0 }) => {
  await submitFromPicker(page);
  await page.getByText('#7').first().waitFor({ timeout: 5000 });
  const dt = Date.now() - t0;
  check('J2 (mocked API, UI-only stopwatch): load page -> token slip shown in < 20 s', dt < 20000, `${dt}ms`);
  const post = log.requests.find((r) => r.method === 'POST' && /check-in/.test(r.path));
  check('J2 check-in POST carries appointmentId', /"appointmentId":"ap1"/.test(post?.body ?? ''));
});
await scenario('409', '/arrivals', arr, base({ status: 409, body: { type: 'https://x/problems/already-checked-in', title: 'Already checked in', detail: 'This appointment has already been checked in.', code: 'ALREADY_CHECKED_IN', tokenNumber: 12, queueEntryId: 'e1', checkedInAt: startTime, checkedInBy: 'Sneha' } }), async ({ page }) => {
  await submitFromPicker(page); await sleep(900);
  const t = await alerts(page);
  check('409a shows the existing token (T-12) not a raw error', /T-12/.test(t), t);
  check('409b story UI note: names who checked in ("by <name>")', /by\s+\S+/i.test(t), t);
});
await scenario('arrived-not-offered', '/arrivals', arr, base({ status: 201, body: entry }, [{ id: 'ap1', patientId: 'p1', doctorId: 'd1', startTime, status: 'arrived' }]), async ({ page }) => {
  await page.getByRole('button', { name: 'Check in', exact: true }).first().click(); await sleep(700);
  check('empty: an arrived appointment is not offered for check-in again', (await page.getByRole('dialog').getByRole('button', { name: 'Check in', exact: true }).count()) === 0);
});
const bk = (id, time, status) => ({ appointmentId: id, queueEntryId: null, patientId: 'p' + id, patientName: 'Pat ' + id, visitType: 'appointment', time, token: status === 'scheduled' ? null : 3, status });
const day = { date: '2026-10-19', today: true, timezone: 'Asia/Riyadh', holiday: null,
  doctors: [{ id: 'd1', name: 'Dr Who', department: 'GP', onLeave: false, leaveReason: null, worksToday: true }],
  sessions: [{ label: 'Morning', start: '09:00', end: '10:00', stepMinutes: 15, rows: [
    { time: '09:00', cells: [{ doctorId: 'd1', kind: 'booked', slotStart: '2026-10-19T09:00:00+03:00', bookings: [bk('A', '09:00', 'checked_in')] }] },
    { time: '09:15', cells: [{ doctorId: 'd1', kind: 'booked', slotStart: '2026-10-19T09:15:00+03:00', bookings: [bk('S', '09:15', 'scheduled')] }] }] }],
  counts: { appointments: 2, walkIns: 0, freeSlots: 0 } };
const cal = [['GET', /^\/schedule\/day/, { body: day }], ['GET', /^\/queue(\?.*)?$/, { body: [] }], ['GET', /^\/waitlist/, { body: [] }], ['GET', /^\/schedule\/week/, { body: { start: '2026-10-19', today: '2026-10-19', days: [] } }]];
const edit = ['queue:view', 'queue:edit', 'queue:create', 'patients:view'];
await scenario('calendar', '/schedule', edit, cal, async ({ page }) => {
  await page.getByRole('button', { name: /Pat A/ }).first().click(); await sleep(500);
  const t = await page.getByRole('dialog').innerText();
  check('cal a: arrived booking opens showing "Arrived"', /Arrived/.test(t), t.replace(/\n/g, ' | '));
  check('cal b: Cancel/Move absent for the arrived booking', (await page.getByRole('button', { name: /Cancel appointment|^Move$/ }).count()) === 0);
  await page.keyboard.press('Escape'); await sleep(300);
  await page.getByRole('button', { name: /Pat S/ }).first().click(); await sleep(500);
  check('cal c: scheduled booking still offers Cancel', (await page.getByRole('button', { name: 'Cancel appointment' }).count()) === 1);
});
await scenario('mobile390', '/arrivals', arr, base({ status: 201, body: entry }), async ({ page }) => {
  check('390px arrivals: no horizontal scroll', !(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)));
}, { width: 390, height: 844 });
const bad = results.filter((r) => !r.ok); console.log(`${results.length - bad.length}/${results.length} passed`); process.exit(bad.length ? 1 : 0);
