// S16 IDC-011: the schedule card shows "Booked by <relation>" for a booking a family member made. Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3807 node s16-idc-011-booked-by-chip.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';
const DOC = '11111111-1111-4111-8111-111111111111';
const booking = (id, name, rel) => ({ appointmentId: id, queueEntryId: null, patientId: id, patientName: name, visitType: 'appointment', time: '09:00', token: null, status: 'scheduled', actedByRelation: rel });
const day = (bookings) => ({ date: '2026-10-26', today: true, timezone: 'Asia/Kolkata', holiday: null, counts: { appointments: bookings.length, walkIns: 0, freeSlots: 0 },
  doctors: [{ id: DOC, name: 'Dr Asha Rao', department: 'General', onLeave: false, leaveReason: null, worksToday: true }],
  sessions: [{ label: 'Morning', start: '09:00', end: '12:00', stepMinutes: 30, rows: [{ time: '09:00', cells: [{ doctorId: DOC, kind: 'booked', slotStart: '2026-10-26T03:30:00Z', bookings }] }] }] });

async function run(tag, bookings, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, ['queue:view', 'queue:create', 'patients:view']);
    await mockApi(page, log, [
      ['GET', /^\/schedule\/day/, { body: day(bookings) }],
      ['GET', /^\/(queue|waitlist|staff\/roster|appointments|doctors\/[^/]+\/delay)/, { body: [] }],
    ]);
    await page.goto(APP + '/schedule'); await sleep(2500);
    await fn({ page });
    check(`${tag}: zero console/page errors`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0, JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}

await run('happy', [booking('a1', 'Ravi Kumar', 'daughter')], async ({ page }) => {
  check('card says Booked by daughter', /Booked by daughter/.test(await page.locator('body').innerText()));
  await page.getByRole('button', { name: /Ravi Kumar/ }).click(); await sleep(500);
  check('detail dialog has a Booked by row', /Booked by[\s\S]*daughter/.test(await page.getByRole('dialog').innerText()));
});
await run('own booking: no chip', [booking('a2', 'Asha Self', null)], async ({ page }) => {
  check('no Booked by text', !/Booked by/.test(await page.locator('body').innerText()));
});
await run('relation with underscore reads as words', [booking('a3', 'Old Man', 'grand_child')], async ({ page }) => {
  check('grand child', /Booked by grand child/.test(await page.locator('body').innerText()));
});
await run('390px', [booking('a1', 'Ravi Kumar', 'daughter')], async ({ page }) => {
  const m = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }));
  check('390px: no horizontal scroll', m.s <= m.c, JSON.stringify(m));
}, { width: 390, height: 800 });

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
