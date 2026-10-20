// REC-032 E2E: Hijri date beside the schedule date pickers. Run against `next start -p 3103`.
import { APP, SHOTS, launch, seedClinic, mockApi, check, results, dumpLog } from './s8-rec3-lib.mjs';

const FULL = ['queue:view', 'queue:create', 'queue:edit', 'patients:view'];
const DOC = 'd0000000-0000-0000-0000-000000000001';
const addDay = (iso, n) => new Date(Date.parse(iso + 'T00:00:00Z') + n * 864e5).toISOString().slice(0, 10);
const booking = { appointmentId: 'a1', queueEntryId: null, patientId: 'p1', patientName: 'Hana Test', visitType: 'appointment', time: '09:00', token: null, status: 'scheduled' };
const dayFor = (date) => ({
  date, today: date === '2026-10-18', timezone: 'Asia/Riyadh', holiday: null,
  doctors: [{ id: DOC, name: 'Dr Salem', department: 'General', onLeave: false, leaveReason: null, worksToday: true }],
  sessions: [{ label: 'Morning', start: '09:00', end: '10:00', stepMinutes: 30, rows: [
    { time: '09:00', cells: [{ doctorId: DOC, kind: 'booked', slotStart: null, bookings: [booking] }] },
    { time: '09:30', cells: [{ doctorId: DOC, kind: 'available', slotStart: '2026-10-18T06:30:00Z', bookings: [] }] }] }],
  counts: { appointments: 1, walkIns: 0, freeSlots: 1 },
});
const dayHandler = (route, req) => {
  const date = new URL(req.url()).searchParams.get('date') ?? '2026-10-18';
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(dayFor(date)) });
};
const base = (extra = []) => [
  ['GET', /^\/schedule\/day/, dayHandler],
  ['GET', /^\/doctors\/[^/]+\/availability/, { body: ['2026-10-18T06:30:00Z', '2026-10-18T07:00:00Z'] }],
  ['GET', /^\/queue/, { body: [] }],
  ...extra,
];
const hij = (page) => page.getByTestId('hijri-date');
async function open(opts, perms, handlers, vp) {
  const s = await launch({ viewport: vp ?? { width: 1440, height: 900 } });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, handlers);
  await s.page.goto(`${APP}/schedule`);
  return s;
}

// 1. happy path: toolbar shows Hijri, follows prev/next/picker, month boundary
{
  const s = await open({}, FULL, base());
  const { page, log } = s;
  await hij(page).first().waitFor();
  check('happy: toolbar shows Hijri date for the loaded day', (await hij(page).first().innerText()) === '7 Jumada I 1448 AH', await hij(page).first().innerText());
  await page.getByRole('button', { name: 'Next day' }).click();
  await page.waitForFunction(() => document.querySelector('[data-testid=hijri-date]')?.textContent === '8 Jumada I 1448 AH');
  check('happy: next day advances the Hijri date', true);
  await page.getByLabel('Pick date').fill('2026-10-11');
  await page.waitForFunction(() => document.querySelector('[data-testid=hijri-date]')?.textContent === '30 Rabiʻ II 1448 AH');
  await page.getByRole('button', { name: 'Next day' }).click();
  await page.waitForFunction(() => document.querySelector('[data-testid=hijri-date]')?.textContent === '1 Jumada I 1448 AH');
  check('boundary: 30 Rabi II rolls to 1 Jumada I across the month change', true);
  await page.screenshot({ path: `${SHOTS}/toolbar-1440.png` });

  // New appointment dialog dual date, follows its own picker
  await page.getByRole('button', { name: '+ New appointment' }).click();
  const dlg = page.getByRole('dialog');
  await dlg.getByTestId('hijri-date').waitFor();
  check('dialog: new-appointment date shows Hijri', (await dlg.getByTestId('hijri-date').innerText()) === '1 Jumada I 1448 AH', await dlg.getByTestId('hijri-date').innerText());
  await dlg.getByLabel('Date').fill('2026-11-11');
  await page.waitForFunction(() => document.querySelector('[role=dialog] [data-testid=hijri-date]')?.textContent === '1 Jumada II 1448 AH');
  check('dialog: Hijri follows the dialog picker', true);
  await page.screenshot({ path: `${SHOTS}/new-dialog-1440.png` });
  await page.keyboard.press('Escape');

  // Move dialog
  await page.getByRole('button', { name: /Hana Test/ }).click();
  await page.getByRole('button', { name: 'Move', exact: true }).click();
  const mv = page.getByRole('dialog').last();
  await mv.getByTestId('hijri-date').waitFor();
  check('dialog: move-appointment date shows Hijri', (await mv.getByTestId('hijri-date').innerText()) === '1 Jumada I 1448 AH', await mv.getByTestId('hijri-date').innerText());
  await page.screenshot({ path: `${SHOTS}/move-dialog-1440.png` });
  check('happy: no console/page errors', log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0); dumpLog('happy', log);
  await s.browser.close();
}

// 2. permission-absent: without queue:create the booking dialog (and its Hijri) is absent; picker Hijri still shown
{
  const s = await open({}, ['queue:view', 'patients:view'], base());
  const { page, log } = s;
  await hij(page).first().waitFor();
  check('perm: no New appointment button without queue:create', (await page.getByRole('button', { name: '+ New appointment' }).count()) === 0);
  check('perm: no Move/booking dialog Hijri node without queue:edit', (await page.getByRole('dialog').count()) === 0);
  check('perm: no console/page errors', log.pageErrors.length === 0); dumpLog('perm', log);
  await s.browser.close();
}

// 3. 403: no access, no date picker, no Hijri
{
  const s = await open({}, FULL, [['GET', /^\/schedule\/day/, { status: 403, body: { title: 'Forbidden' } }]]);
  const { page, log } = s;
  await page.getByText("You don't have access to the schedule.").waitFor();
  check('403: access message, no Hijri, no date picker', (await hij(page).count()) === 0 && (await page.getByLabel('Pick date').count()) === 0);
  check('403: no page errors', log.pageErrors.length === 0); dumpLog('403', log);
  await s.browser.close();
}

// 4. 500: error state, no Hijri; then recovers on retry
{
  let fail = true;
  const s = await open({}, FULL, [['GET', /^\/schedule\/day/, (route, req) => fail ? route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"title":"boom"}' }) : dayHandler(route, req)]]);
  const { page, log } = s;
  await page.getByText("Couldn't load the schedule. Try again.").waitFor();
  check('500: error shown, Hijri not rendered', (await hij(page).count()) === 0);
  fail = false;
  await page.getByRole('button', { name: /retry|try again/i }).first().click();
  await hij(page).first().waitFor();
  check('500: retry recovers and Hijri appears', true);
  check('500: no page errors', log.pageErrors.length === 0); dumpLog('500', log);
  await s.browser.close();
}

// 5. empty: clinic with no doctors still shows Hijri beside the picker
{
  const s = await open({}, FULL, [['GET', /^\/schedule\/day/, (route, req) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...dayFor('2026-10-18'), doctors: [], sessions: [], counts: { appointments: 0, walkIns: 0, freeSlots: 0 } }) })]]);
  const { page, log } = s;
  await hij(page).first().waitFor();
  check('empty: no doctors, Hijri still shown, no New appointment button', (await hij(page).first().innerText()) === '7 Jumada I 1448 AH' && (await page.getByRole('button', { name: '+ New appointment' }).count()) === 0);
  check('empty: no page errors', log.pageErrors.length === 0); dumpLog('empty', log);
  await s.browser.close();
}

// 6. 390px: no horizontal page scroll, Hijri visible; 1024 too
for (const w of [390, 1024]) {
  const s = await open({}, FULL, base(), { width: w, height: 800 });
  const { page, log } = s;
  await hij(page).first().waitFor();
  const o = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth }));
  check(`${w}px: no horizontal page scroll`, o.sw <= o.cw, `${o.sw}/${o.cw}`);
  check(`${w}px: Hijri visible in viewport`, await hij(page).first().isVisible());
  await page.screenshot({ path: `${SHOTS}/toolbar-${w}.png` });
  if (w === 390) {
    await page.getByRole('button', { name: '+ New appointment' }).click();
    const o2 = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth }));
    check('390px: new-appointment dialog no horizontal scroll', o2.sw <= o2.cw, `${o2.sw}/${o2.cw}`);
    await page.screenshot({ path: `${SHOTS}/new-dialog-390.png` });
  }
  check(`${w}px: no page/console errors`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0); dumpLog(String(w), log);
  await s.browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
