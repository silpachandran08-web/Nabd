// S15 ACC-018: "Patient wait display" on the schedule (doctor, session, clinic default, exact-mode accuracy warning).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3805 node s15-acc018-eta-display.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';
const DOC = '11111111-1111-4111-8111-111111111111';
const HOURS = '22222222-2222-4222-8222-222222222222';
const day = { date: '2026-10-26', today: true, timezone: 'Asia/Kolkata', holiday: null, sessions: [], counts: { appointments: 0, walkIns: 0, freeSlots: 0 },
  doctors: [{ id: DOC, name: 'Dr Asha Rao', department: 'General', onLeave: false, leaveReason: null, worksToday: true }] };
const settings = (o = {}) => ({ etaDisplayMode: null, facilityDefault: 'range', effectiveMode: 'range', effectiveSource: 'default',
  sessions: [{ workingHoursId: HOURS, dayOfWeek: 1, startTime: '09:00:00', endTime: '13:00:00', etaDisplayMode: null }], ...o });
const accuracy = (pct) => ({ items: [{ clinicDate: '2026-10-25', doctorId: DOC, n: 40, maeMin: 6, p80HitRate: 0.7, within10Pct: pct }] });

async function run(tag, perms, { state, acc = { items: [] }, settingsReply, viewport }, fn) {
  const { browser, page, log } = await launch({ viewport });
  const puts = [];
  const cur = { s: state ?? settings() };
  try {
    await seedClinic(page, perms);
    await mockApi(page, log, [
      ['GET', /^\/schedule\/day/, { body: day }],
      ['GET', new RegExp(`^/doctors/${DOC}/queue-settings`), (r) => settingsReply ? r.fulfill(settingsReply) : r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(cur.s) })],
      ['PUT', new RegExp(`^/doctors/${DOC}/queue-settings`), (r, req) => {
        const b = JSON.parse(req.postData()); puts.push(b);
        cur.s = b.workingHoursId ? { ...cur.s, sessions: cur.s.sessions.map((x) => x.workingHoursId === b.workingHoursId ? { ...x, etaDisplayMode: b.etaDisplayMode } : x), effectiveMode: b.etaDisplayMode ?? cur.s.effectiveMode, effectiveSource: b.etaDisplayMode ? 'session' : cur.s.effectiveSource }
          : { ...cur.s, etaDisplayMode: b.etaDisplayMode, effectiveMode: b.etaDisplayMode ?? cur.s.facilityDefault, effectiveSource: b.etaDisplayMode ? 'doctor' : 'facility' };
        return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ etaDisplayMode: cur.s.effectiveMode, effectiveSource: cur.s.effectiveSource }) });
      }],
      ['PUT', /^\/queue-settings\/facility/, (r, req) => { const v = JSON.parse(req.postData()).etaDisplayMode; cur.s = { ...cur.s, facilityDefault: v, effectiveMode: cur.s.etaDisplayMode ?? v }; puts.push({ policy: v }); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 'p', policyKey: 'eta_display_mode', value: v, version: 2 }) }); }],
      ['GET', /^\/queue/, { body: [] }],
      ['GET', /^\/reports\/eta-accuracy/, { body: acc }],
      ['GET', /^\/(waitlist|staff\/roster|appointments|doctors\/[^/]+\/delay)/, { body: [] }],
    ]);
    await page.goto(APP + '/schedule'); await sleep(2500);
    await fn({ page, log, puts });
    check(`${tag}: zero console/page errors`, log.pageErrors.length === 0 && log.console.filter((x) => !(tag === 'error' && /status of 500/.test(x))).length === 0, JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const open = (page) => page.getByRole('button', { name: 'Patient wait display' }).click();
const dlg = (page) => page.getByRole('dialog', { name: 'Time shown to patients' });

await run('happy', ['queue:view', 'setup:edit', 'reports:view'], {}, async ({ page, puts }) => {
  await open(page); await sleep(800);
  check('default shown as range from the built-in default', /Time range \(default\)/.test(await page.getByTestId('eta-effective').innerText()) && /built-in default/.test(await page.getByTestId('eta-effective').innerText()));
  await dlg(page).getByLabel('This doctor').selectOption('delay_only'); await sleep(600);
  check('doctor mode saved as PUT {etaDisplayMode:delay_only}', puts.some((p) => p.etaDisplayMode === 'delay_only' && !p.workingHoursId), JSON.stringify(puts));
  check('effective line follows the doctor setting', /Delay notices only/.test(await page.getByTestId('eta-effective').innerText()) && /doctor/.test(await page.getByTestId('eta-effective').innerText()));
  await dlg(page).getByLabel(/Mon 09:00.13:00 session/).selectOption('hidden'); await sleep(600);
  check('session override saved with its working-hours id', puts.some((p) => p.etaDisplayMode === 'hidden' && p.workingHoursId === HOURS), JSON.stringify(puts));
  check('effective line names the session', /Hidden/.test(await page.getByTestId('eta-effective').innerText()) && /this session/.test(await page.getByTestId('eta-effective').innerText()));
  await dlg(page).getByLabel('Clinic default').selectOption('position'); await sleep(600);
  check('clinic default saved through PUT /queue-settings/facility', puts.some((p) => p.policy === 'position'), JSON.stringify(puts));
  await dlg(page).getByLabel('This doctor').selectOption(''); await sleep(600);
  check('"Use the clinic default" sends null', puts.some((p) => p.etaDisplayMode === null && !p.workingHoursId), JSON.stringify(puts));
});
await run('exact + 78% accuracy warns', ['queue:view', 'setup:edit', 'reports:view'], { state: settings({ etaDisplayMode: 'exact', effectiveMode: 'exact', effectiveSource: 'doctor' }), acc: accuracy(0.78) }, async ({ page }) => {
  await open(page); await sleep(800);
  check('owner sees "Exact times will often be wrong; range is safer"', (await page.getByTestId('eta-exact-warning').innerText()) === 'Exact times will often be wrong; range is safer');
});
await run('exact + 90% accuracy: no warning', ['queue:view', 'setup:edit', 'reports:view'], { state: settings({ etaDisplayMode: 'exact', effectiveMode: 'exact', effectiveSource: 'doctor' }), acc: accuracy(0.9) }, async ({ page }) => {
  await open(page); await sleep(800);
  check('no warning at 90%', (await page.getByTestId('eta-exact-warning').count()) === 0);
});
await run('permission absent', ['queue:view', 'queue:edit'], {}, async ({ page, log }) => {
  check('without setup:edit the button is absent', (await page.getByRole('button', { name: 'Patient wait display' }).count()) === 0);
  check('and no queue-settings call is made', !log.requests.some((r) => r.path.includes('queue-settings')));
});
await run('error', ['queue:view', 'setup:edit'], { settingsReply: { status: 500, contentType: 'application/problem+json', body: JSON.stringify({ title: 'x' }) } }, async ({ page }) => {
  await open(page); await sleep(1500);
  check('load failure shows a retry state', /Couldn.t load the display settings/.test(await dlg(page).innerText()));
});
await run('390px', ['queue:view', 'setup:edit'], { viewport: { width: 390, height: 800 } }, async ({ page }) => {
  await open(page); await sleep(800);
  const m = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }));
  check('390px: no horizontal scroll', m.s <= m.c, JSON.stringify(m));
  check('390px: selects are at least 44px high', (await dlg(page).getByLabel('This doctor').boundingBox()).height >= 36);
});

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
