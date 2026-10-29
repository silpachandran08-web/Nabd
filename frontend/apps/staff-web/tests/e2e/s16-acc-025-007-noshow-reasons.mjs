// ACC-025 / ACC-007 E2E (mocked API): cancel with the coded reason list, mark no-show, undo, late arrival, permission-absent, empty, error, 390px.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3801 node s16-acc-025-007-noshow-reasons.mjs
import { launch, seedClinic, mockApi, check, sleep, results, APP } from './qa-lib.mjs';

const REASONS = { items: [
  { code: 'schedule_clash', label: 'Schedule clash', requiresNote: false }, { code: 'illness', label: 'Illness', requiresNote: false },
  { code: 'other', label: 'Other', requiresNote: true }] };
const bk = (id, time, status) => ({ appointmentId: id, queueEntryId: null, patientId: 'p' + id, patientName: 'Pat ' + id, visitType: 'appointment', time, token: null, status });
const day = { date: '2026-10-19', today: true, timezone: 'Asia/Riyadh', holiday: null,
  doctors: [{ id: 'd1', name: 'Dr Who', department: 'GP', onLeave: false, leaveReason: null, worksToday: true }],
  sessions: [{ label: 'Morning', start: '09:00', end: '10:00', stepMinutes: 15, rows: [
    { time: '09:00', cells: [{ doctorId: 'd1', kind: 'booked', slotStart: '2026-10-19T09:00:00+03:00', bookings: [bk('S', '09:00', 'scheduled')] }] },
    { time: '09:15', cells: [{ doctorId: 'd1', kind: 'booked', slotStart: '2026-10-19T09:15:00+03:00', bookings: [bk('N', '09:15', 'no_show')] }] }] }],
  counts: { appointments: 2, walkIns: 0, freeSlots: 0 } };
const base = (extra = []) => [
  ...extra,
  ['GET', /^\/schedule\/day/, { body: day }], ['GET', /^\/queue(\?.*)?$/, { body: [] }], ['GET', /^\/waitlist/, { body: [] }],
  ['GET', /^\/schedule\/week/, { body: { start: '2026-10-19', today: '2026-10-19', days: [] } }]];
const reasons = (body = REASONS, status = 200) => ['GET', /^\/reason-codes\?kind=(cancel|no_show)/, { status, body }];
const ok = (path, body = {}) => ['POST', path, { body }];

async function scenario(id, perms, handlers, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms); await mockApi(page, log, handlers);
    await page.goto(APP + '/schedule', { waitUntil: 'networkidle' }); await fn({ page, log });
    check(`${id}: zero page errors / console errors`, log.pageErrors.length === 0 && log.console.every((x) => /Failed to load resource/.test(x)), JSON.stringify([...log.pageErrors, ...log.console]).slice(0, 300));
  } catch (e) { check(`${id}: scenario ran`, false, String(e).split('\n')[0]); } finally { await browser.close(); }
}
const open = async (page, name) => { await page.getByRole('button', { name: new RegExp(name) }).first().click(); await sleep(400); };
const posts = (log, re) => log.requests.filter((r) => r.method === 'POST' && re.test(r.path));
const EDIT = ['queue:view', 'queue:edit', 'queue:create', 'patients:view'];

await scenario('cancel', EDIT, base([reasons(), ok(/^\/appointments\/S\/cancel/, { id: 'S', status: 'cancelled', cancelReasonCode: 'illness' })]), async ({ page, log }) => {
  await open(page, 'Pat S');
  await page.getByRole('button', { name: 'Cancel appointment' }).click(); await sleep(400);
  const dlg = page.getByRole('dialog');
  check('cancel: reasons come from the coded list, in order', (await dlg.getByRole('radio').count()) === 3 && /Schedule clash[\s\S]*Illness[\s\S]*Other/.test(await dlg.innerText()));
  check('cancel: submit disabled until a reason is picked', await dlg.getByRole('button', { name: 'Select a reason' }).isDisabled());
  await dlg.getByLabel('Other').check(); await sleep(150);
  check('cancel: other shows a required note and stays disabled without it', (await dlg.getByRole('textbox').count()) === 1 && await dlg.getByRole('button', { name: 'Select a reason' }).isDisabled());
  await dlg.getByLabel('Illness').check(); await sleep(150);
  check('cancel: a code without a note hides the note field', (await dlg.getByRole('textbox').count()) === 0);
  await dlg.getByRole('button', { name: 'Cancel appointment' }).click(); await sleep(600);
  const p = posts(log, /\/cancel$/);
  check('cancel: POST carries reasonCode only', p.length === 1 && p[0].body === '{"reasonCode":"illness"}', p[0]?.body);
  check('cancel: confirmation names the recorded reason', /Cancellation reason: Illness/.test(await page.getByRole('dialog').innerText()));
});
await scenario('cancel-other', EDIT, base([reasons(), ok(/^\/appointments\/S\/cancel/, { id: 'S', status: 'cancelled' })]), async ({ page, log }) => {
  await open(page, 'Pat S'); await page.getByRole('button', { name: 'Cancel appointment' }).click(); await sleep(300);
  const dlg = page.getByRole('dialog'); await dlg.getByLabel('Other').check();
  await dlg.getByRole('textbox').fill('moved abroad'); await sleep(100);
  await dlg.getByRole('button', { name: 'Cancel appointment' }).click(); await sleep(600);
  check('cancel-other: POST carries the code and the note', posts(log, /\/cancel$/)[0]?.body === '{"reasonCode":"other","note":"moved abroad"}', posts(log, /\/cancel$/)[0]?.body);
});
await scenario('cancel-error', EDIT, base([reasons(), ['POST', /^\/appointments\/S\/cancel/, { status: 409, body: { type: 'x', title: 'Appointment already arrived', detail: 'The patient has checked in.', code: 'APPOINTMENT_ARRIVED' } }]]), async ({ page }) => {
  await open(page, 'Pat S'); await page.getByRole('button', { name: 'Cancel appointment' }).click(); await sleep(300);
  await page.getByRole('dialog').getByLabel('Illness').check(); await page.getByRole('dialog').getByRole('button', { name: 'Cancel appointment' }).click(); await sleep(600);
  check('cancel-error: the refusal is shown, dialog stays open', /checked in|already arrived/i.test((await page.getByRole('alert').allInnerTexts()).join(' ')));
});
await scenario('reasons-empty', EDIT, base([reasons({ items: [] })]), async ({ page }) => {
  await open(page, 'Pat S'); await page.getByRole('button', { name: 'Cancel appointment' }).click(); await sleep(400);
  check('reasons-empty: says no reasons are set up and cannot submit', /No reasons are set up/.test(await page.getByRole('dialog').innerText()) && await page.getByRole('dialog').getByRole('button', { name: 'Select a reason' }).isDisabled());
});
await scenario('reasons-error', EDIT, base([reasons({ title: 'x', detail: 'boom' }, 500)]), async ({ page }) => {
  await open(page, 'Pat S'); await page.getByRole('button', { name: 'Cancel appointment' }).click(); await sleep(600);
  check('reasons-error: shows a retry', (await page.getByRole('dialog').getByRole('button', { name: 'Retry' }).count()) === 1);
});

await scenario('no-show', EDIT, base([reasons(), ok(/^\/appointments\/S\/no-show$/, { id: 'S', status: 'no_show' })]), async ({ page, log }) => {
  await open(page, 'Pat S');
  await page.getByRole('button', { name: 'Mark no-show' }).click(); await sleep(400);
  const dlg = page.getByRole('dialog');
  check('no-show: reason optional, no note field even for other', (await dlg.getByRole('radio').count()) === 3 && (await dlg.getByLabel('Other').check(), (await dlg.getByRole('textbox').count()) === 0));
  await dlg.getByLabel('Illness').check();
  await dlg.getByRole('button', { name: 'Mark no-show' }).click(); await sleep(600);
  const p = posts(log, /\/no-show$/);
  check('no-show: POST carries reasonCode', p.length === 1 && p[0].body === '{"reasonCode":"illness"}', p[0]?.body);
});
await scenario('no-show-not-due', EDIT, base([reasons(), ['POST', /^\/appointments\/S\/no-show$/, { status: 409, body: { type: 'x', title: 'Not yet due', detail: 'The appointment time has not passed yet.', code: 'NOT_YET_DUE' } }]]), async ({ page, log }) => {
  await open(page, 'Pat S'); await page.getByRole('button', { name: 'Mark no-show' }).click(); await sleep(400);
  await page.getByRole('dialog').getByRole('button', { name: 'Mark no-show' }).click(); await sleep(600);
  check('no-show-not-due: sent without a reason when none picked', posts(log, /no-show$/)[0]?.body === '{}');
  check('no-show-not-due: the refusal is shown', /not passed yet/i.test((await page.getByRole('alert').allInnerTexts()).join(' ')));
});
await scenario('undo-and-late', EDIT, base([ok(/^\/appointments\/N\/no-show\/undo/, { id: 'N', status: 'scheduled' }), ok(/^\/queue\/late-arrival/, { id: 'e9', tokenNumber: 14, source: 'walk_in' })]), async ({ page, log }) => {
  await open(page, 'Pat N');
  check('no-show booking offers Undo and Late arrival, not Cancel/Move/Mark', (await page.getByRole('button', { name: 'Undo no-show' }).count()) === 1 && (await page.getByRole('button', { name: 'Late arrival' }).count()) === 1
    && (await page.getByRole('button', { name: /Cancel appointment|^Move$|Mark no-show/ }).count()) === 0);
  await page.getByRole('button', { name: 'Late arrival' }).click(); await sleep(300);
  await page.getByRole('dialog').getByRole('button', { name: 'Check in as walk-in' }).click(); await sleep(600);
  const l = posts(log, /late-arrival$/);
  check('late arrival: POST carries the appointment id', l.length === 1 && l[0].body === '{"appointmentId":"N"}', l[0]?.body);
  check('late arrival: shows the walk-in token', /T-14/.test(await page.getByRole('dialog').innerText()));
  await page.getByRole('dialog').getByRole('button', { name: 'Close' }).last().click(); await sleep(400);
  await open(page, 'Pat N'); await page.getByRole('button', { name: 'Undo no-show' }).click(); await sleep(300);
  await page.getByRole('dialog').getByRole('button', { name: 'Undo no-show' }).click(); await sleep(600);
  check('undo: POST sent', posts(log, /no-show\/undo$/).length === 1);
});
await scenario('undo-expired', EDIT, base([['POST', /^\/appointments\/N\/no-show\/undo/, { status: 409, body: { type: 'x', title: 'Undo window passed', detail: 'A no-show can be undone only on the clinic day it was marked.', code: 'UNDO_WINDOW_PASSED' } }]]), async ({ page }) => {
  await open(page, 'Pat N'); await page.getByRole('button', { name: 'Undo no-show' }).click(); await sleep(300);
  await page.getByRole('dialog').getByRole('button', { name: 'Undo no-show' }).click(); await sleep(600);
  check('undo-expired: the refusal is shown', /clinic day it was marked/.test((await page.getByRole('alert').allInnerTexts()).join(' ')));
});
await scenario('permission-absent', ['queue:view', 'patients:view'], base(), async ({ page }) => {
  await open(page, 'Pat S');
  check('view only: Mark no-show / Cancel / Move absent', (await page.getByRole('button', { name: /Mark no-show|Cancel appointment|^Move$/ }).count()) === 0);
  await page.keyboard.press('Escape'); await sleep(300);
  await open(page, 'Pat N');
  check('view only: Undo / Late arrival absent', (await page.getByRole('button', { name: /Undo no-show|Late arrival/ }).count()) === 0);
});
await scenario('edit-without-create', ['queue:view', 'queue:edit', 'patients:view'], base(), async ({ page }) => {
  await open(page, 'Pat N');
  check('queue:edit without queue:create: Undo yes, Late arrival absent', (await page.getByRole('button', { name: 'Undo no-show' }).count()) === 1 && (await page.getByRole('button', { name: 'Late arrival' }).count()) === 0);
});
await scenario('mobile390', EDIT, base([reasons()]), async ({ page }) => {
  await open(page, 'Pat S'); await page.getByRole('button', { name: 'Cancel appointment' }).click(); await sleep(500);
  check('390px: no horizontal scroll with the cancel dialog open', !(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)));
  const small = await page.getByRole('dialog').getByRole('radio').evaluateAll((els) => els.map((e) => e.closest('label').getBoundingClientRect().height));
  check('390px: reason rows are at least 44px tall', small.length > 0 && small.every((h) => h >= 43.5), JSON.stringify(small));
}, { width: 390, height: 844 });

const bad = results.filter((r) => !r.ok); console.log(`${results.length - bad.length}/${results.length} passed`); process.exit(bad.length ? 1 : 0);
