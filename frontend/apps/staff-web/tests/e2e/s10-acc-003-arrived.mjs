// S10 ACC-003: 409 ALREADY_CHECKED_IN message on Arrivals; 'Arrived' + no cancel/move on the calendar. Run: APP=http://localhost:3402 node s10-acc-003-arrived.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';
const startTime = new Date().toISOString();
const entry = { id: 'e7', appointmentId: 'ap1', patientId: 'p1', doctorId: 'd1', departmentId: 'dep1', queueDate: '2026-10-19', tokenNumber: 7, status: 'checked_in', priority: false, priorityReason: null, createdAt: startTime, source: 'walk_in', appointmentStatus: 'arrived' };
const arrivalsBase = (checkInHandler, appts = [{ id: 'ap1', patientId: 'p1', doctorId: 'd1', startTime, status: 'scheduled' }]) => [
  ['POST', /^\/queue\/check-in/, checkInHandler],
  ['GET', /^\/queue(\?.*)?$/, { body: [] }],
  ['GET', /^\/staff\/roster/, { body: [{ id: 'd1', name: 'Dr Who', departmentName: 'GP' }] }],
  ['GET', /^\/appointments\?/, { body: { data: appts, page: { nextCursor: null, limit: 100 } } }],
  ['GET', /^\/patients\/p1/, { body: { name: 'Pat One', mrn: 'M1', phone: '+9665' } }],
  ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5 } }],
  ['GET', /^\/doctors\/.*\/delay/, { body: [] }],
  ['GET', /^\/appointments\/callback-list/, { body: [] }],
];
async function run(tag, path, perms, handlers, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms); await mockApi(page, log, handlers);
    await page.goto(APP + path); await sleep(1800); await fn({ page, log });
    check(`${tag} zero console/page errors`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0, JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const openRowCheckIn = async (page) => {
  await page.getByRole('button', { name: 'Check in', exact: true }).first().click(); await sleep(600);
  await page.getByRole('button', { name: 'Check in', exact: true }).last().click(); await sleep(400); // row action -> check-in dialog
  await page.getByRole('button', { name: /^Check in$/ }).last().click(); await sleep(900); // submit
};
const alertText = async (page) => (await page.getByRole('alert').allInnerTexts()).join(' | ');
const arr = ['queue:view', 'queue:create', 'patients:view'];

await run('201', '/arrivals', arr, arrivalsBase({ status: 201, body: entry }), async ({ page, log }) => {
  await openRowCheckIn(page);
  check('201: POST check-in carries appointmentId', log.requests.some((r) => r.method === 'POST' && /check-in/.test(r.path) && /"appointmentId":"ap1"/.test(r.body ?? '')));
  check('201: token slip shown', (await page.getByText('#7').count()) > 0);
});
await run('409', '/arrivals', arr, arrivalsBase({ status: 409, body: { type: 'already-checked-in', title: 'Already checked in', detail: 'This appointment has already been checked in.', code: 'ALREADY_CHECKED_IN', tokenNumber: 12, queueEntryId: 'e1', checkedInAt: startTime } }), async ({ page }) => {
  await openRowCheckIn(page);
  const t = await alertText(page);
  check('409: names the existing token and time', /Already checked in as T-12 at \d\d:\d\d/.test(t), t);
});
await run('409 other', '/arrivals', arr, arrivalsBase({ status: 409, body: { type: 'session-full', title: 'Session is full', detail: 'This session has reached its patient cap.' } }), async ({ page }) => {
  await openRowCheckIn(page);
  check('409 without ALREADY_CHECKED_IN keeps server detail', /patient cap/.test(await alertText(page)), await alertText(page));
});
await run('arrived not offered', '/arrivals', arr, arrivalsBase({ status: 201, body: entry }, [{ id: 'ap1', patientId: 'p1', doctorId: 'd1', startTime, status: 'arrived' }]), async ({ page }) => {
  await page.getByRole('button', { name: 'Check in', exact: true }).first().click(); await sleep(600);
  check('empty: an arrived appointment is not offered for check-in', (await page.getByText(/No scheduled appointments are waiting/).count()) > 0);
});

// calendar
const bk = (id, time, status) => ({ appointmentId: id, queueEntryId: null, patientId: 'p' + id, patientName: 'Pat ' + id, visitType: 'appointment', time, token: status === 'scheduled' ? null : 3, status });
const day = {
  date: '2026-10-19', today: true, timezone: 'Asia/Riyadh', holiday: null,
  doctors: [{ id: 'd1', name: 'Dr Who', department: 'GP', onLeave: false, leaveReason: null, worksToday: true }],
  sessions: [{ label: 'Morning', start: '09:00', end: '10:00', stepMinutes: 15, rows: [
    { time: '09:00', cells: [{ doctorId: 'd1', kind: 'booked', slotStart: '2026-10-19T09:00:00+03:00', bookings: [bk('A', '09:00', 'checked_in')] }] },
    { time: '09:15', cells: [{ doctorId: 'd1', kind: 'booked', slotStart: '2026-10-19T09:15:00+03:00', bookings: [bk('S', '09:15', 'scheduled')] }] }] }],
  counts: { appointments: 2, walkIns: 0, freeSlots: 0 },
};
const cal = (d = day) => [['GET', /^\/schedule\/day/, d.err ? { status: 500, body: { type: 'server-error', title: 'x' } } : { body: d }], ['GET', /^\/queue(\?.*)?$/, { body: [] }], ['GET', /^\/waitlist/, { body: [] }], ['GET', /^\/schedule\/week/, { body: { start: '2026-10-19', today: '2026-10-19', days: [] } }]];
const edit = ['queue:view', 'queue:edit', 'queue:create', 'patients:view'];
const dialogRows = async (page, name) => { await page.getByRole('button', { name: new RegExp(name) }).first().click(); await sleep(400); return (await page.getByRole('dialog').innerText()); };
await run('calendar arrived', '/schedule', edit, cal(), async ({ page }) => {
  const t = await dialogRows(page, 'Pat A');
  check('calendar: arrived booking shows Arrived', /Arrived/.test(t), t.replace(/\n/g, ' | '));
  check('calendar: Move/Cancel hidden for arrived (absent)', (await page.getByRole('button', { name: /Cancel appointment|^Move$/ }).count()) === 0);
  await page.keyboard.press('Escape'); await sleep(300);
  const s = await dialogRows(page, 'Pat S');
  check('calendar: scheduled booking still offers Move and Cancel', /Not arrived/.test(s) && (await page.getByRole('button', { name: 'Cancel appointment' }).count()) === 1 && (await page.getByRole('button', { name: 'Move', exact: true }).count()) === 1, s.replace(/\n/g, ' | '));
});
await run('calendar no edit', '/schedule', ['queue:view'], cal(), async ({ page }) => {
  await dialogRows(page, 'Pat S');
  check('perm: without queue:edit no Move/Cancel for scheduled either', (await page.getByRole('button', { name: /Cancel appointment|^Move$/ }).count()) === 0);
});
await run('calendar error', '/schedule', edit, cal({ err: true }), async ({ page }) => {
  check('error: visible error state', (await page.getByText(/Couldn.t load the schedule/).count()) > 0);
});
await run('calendar 390', '/schedule', edit, cal(), async ({ page }) => {
  const m = await page.evaluate(() => ({ dsw: document.documentElement.scrollWidth, dcw: document.documentElement.clientWidth }));
  check('390px: no horizontal page scroll', m.dsw <= m.dcw, JSON.stringify(m));
}, { width: 390, height: 844 });
await run('arrivals 390', '/arrivals', arr, arrivalsBase({ status: 201, body: entry }), async ({ page }) => {
  const m = await page.evaluate(() => ({ dsw: document.documentElement.scrollWidth, dcw: document.documentElement.clientWidth }));
  check('390px arrivals: no horizontal page scroll', m.dsw <= m.dcw, JSON.stringify(m));
}, { width: 390, height: 844 });
const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
