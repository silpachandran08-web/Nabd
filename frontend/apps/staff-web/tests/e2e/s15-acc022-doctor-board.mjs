// S15 ACC-022: the doctor-columns board on Arrivals (drag, M + column number, P priority, errors, permissions).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3805 node s15-acc022-doctor-board.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';
const A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', C = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const entry = (id, token, name, stage, extra = {}) => ({ id, patientId: 'p' + id, token, previousToken: null, displayName: name, mrn: 'MRN-' + id, stage, position: stage === 'in_consult' ? 0 : token, p50At: null, p80At: null, priority: false, flags: ['walk_in'], allergyFlag: false, vitalsRecorded: false, ...extra });
const mkBoard = () => ({ v: Date.now(), date: '2026-10-26', doctors: [
  { doctorId: A, name: 'Dr Asha Rao', state: 'in', delayMinutes: 15, nowServing: 1, entries: [entry('e1', 1, 'Anita R.', 'in_consult'), entry('e9', 9, 'Bilal K.', 'waiting'), entry('e10', 10, 'Chitra I.', 'checked_in')] },
  { doctorId: B, name: 'Dr Bob Lee', state: 'in', entries: [entry('e20', 4, 'Dev P.', 'waiting', { p50At: '2026-10-26T05:12:00Z', p80At: '2026-10-26T05:32:00Z' })] },
  { doctorId: C, name: 'Dr Cy Wong', state: 'out', entries: [] },
] });

async function run(tag, perms, { board, reassign, viewport } = {}, fn) {
  const { browser, page, log } = await launch({ viewport });
  const state = { board: board ?? mkBoard(), reassigns: [], boardCalls: 0, patientCalls: 0 };
  try {
    await seedClinic(page, perms);
    await mockApi(page, log, [
      ['GET', /^\/queue\/board/, (r) => { state.boardCalls++; return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state.board) }); }],
      ['POST', /^\/queue\/([^/]+)\/reassign/, (r, req, m) => {
        const b = JSON.parse(req.postData()); state.reassigns.push({ id: m[1], ...b });
        if (reassign) return r.fulfill(reassign);
        const from = state.board.doctors.find((d) => d.entries.some((e) => e.id === m[1])); const e = from.entries.find((x) => x.id === m[1]);
        from.entries = from.entries.filter((x) => x.id !== m[1]);
        const to = state.board.doctors.find((d) => d.doctorId === b.toDoctorId); const nt = Math.max(0, ...to.entries.map((x) => x.token)) + 1;
        to.entries.push({ ...e, previousToken: e.token, token: nt });
        return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ entry: { tokenNumber: nt }, previousToken: e.token }) });
      }],
      ['POST', /^\/queue\/([^/]+)\/reorder/, (r, req, m) => { state.priority = { id: m[1], ...JSON.parse(req.postData()) }; return r.fulfill({ status: 200, contentType: 'application/json', body: '{}' }); }],
      ['GET', /^\/queue/, { body: [] }],
      ['GET', /^\/(staff\/roster|appointments|doctors\/[^/]+\/delay|wait-estimate)/, { body: [] }],
    ]);
    await page.goto(APP + '/arrivals?view=doctors'); await sleep(2500);
    await fn({ page, log, state });
    check(`${tag}: zero console/page errors`, log.pageErrors.length === 0 && log.console.filter((x) => !(tag.startsWith('409') && /status of 409/.test(x)) && !(tag === 'error')).length === 0, JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const PERMS = ['queue:view', 'queue:edit', 'queue:create'];
const card = (page, id) => page.getByTestId('card-' + id);

await run('renders', PERMS, {}, async ({ page, state }) => {
  check('three doctor columns', (await page.locator('[data-testid^="col-"]').count()) === 3);
  check('one board request (no per-row patient fetches)', state.boardCalls === 1 && !(await page.evaluate(() => performance.getEntriesByType('resource').some((r) => /\/patients\//.test(r.name)))));
  const text = await page.getByTestId('doctor-board').innerText();
  check('tokens, short names, now serving and the delay are shown', /T-9/.test(text) && /Bilal K\./.test(text) && /Now T-1/.test(text) && /Running 15 min late/.test(text));
  check('ETA window shown only where the engine gave one', /\d\d:\d\d/.test(await page.getByTestId('col-2').innerText()) && !/\d\d:\d\d/.test(await page.getByTestId('col-1').innerText()));
  check('no full names or phones on the page', !/\+91/.test(text));
});
await run('drag to another doctor', PERMS, {}, async ({ page, state }) => {
  await card(page, 'e9').dragTo(page.getByTestId('col-2'));
  await sleep(500);
  check('drop opens the confirm dialog naming the doctors', /Move T-9 \(Bilal K\.\) from Dr Asha Rao to Dr Bob Lee/.test(await page.getByRole('dialog').innerText()));
  await page.getByRole('button', { name: 'Move', exact: true }).click(); await sleep(1500);
  const r = state.reassigns[0];
  check('POST /queue/e9/reassign with target, expected stage and a reason code', r && r.id === 'e9' && r.toDoctorId === B && r.expectedStatus === 'waiting' && /^[a-z_]+$/.test(r.reasonCode), JSON.stringify(r));
  check('notice shows the new token and the old one', /New token T-5, was T-9/.test(await page.getByTestId('board-notice').innerText()));
  check('board shows "T-5, was T-9" in column 2 after the reload', /T-5, was T-9/.test(await page.getByTestId('col-2').innerText()) && !/Bilal/.test(await page.getByTestId('col-1').innerText()));
});
await run('keyboard M then 2', PERMS, {}, async ({ page, state }) => {
  await card(page, 'e10').focus();
  await page.keyboard.press('m');
  check('M announces the move', /Moving T-10/.test(await page.getByTestId('board-notice').innerText()));
  await page.keyboard.press('2'); await sleep(400);
  check('digit 2 opens the confirm for column 2', /to Dr Bob Lee/.test(await page.getByRole('dialog').innerText()));
  await page.getByRole('button', { name: 'Move', exact: true }).click(); await sleep(1200);
  check('reassign sent to doctor B', state.reassigns[0]?.toDoctorId === B && state.reassigns[0]?.id === 'e10', JSON.stringify(state.reassigns));
});
await run('keyboard: own column and Escape', PERMS, {}, async ({ page, state }) => {
  await card(page, 'e10').focus(); await page.keyboard.press('m'); await page.keyboard.press('1'); await sleep(300);
  check('column 1 is the patient\'s own doctor: no dialog, a hint instead', (await page.getByRole('dialog').count()) === 0 && /another doctor/.test(await page.getByTestId('board-notice').innerText()));
  await page.keyboard.press('Escape'); await sleep(200);
  check('Escape cancels and sends nothing', state.reassigns.length === 0);
});
await run('in consult cannot move', PERMS, {}, async ({ page, state }) => {
  check('the consulting card is not draggable', (await card(page, 'e1').getAttribute('draggable')) !== 'true');
  await card(page, 'e1').focus(); await page.keyboard.press('m'); await sleep(200);
  check('M does nothing on it', !/Moving/.test(await page.getByTestId('board-notice').innerText()) && state.reassigns.length === 0);
});
await run('P opens priority', PERMS, {}, async ({ page }) => {
  await card(page, 'e9').focus(); await page.keyboard.press('p'); await sleep(400);
  check('priority dialog opens', /Mark priority/.test(await page.getByRole('dialog').innerText()));
});
await run('409 stale shows the server reason and reloads', PERMS, { reassign: { status: 409, contentType: 'application/problem+json', body: JSON.stringify({ type: 'stale-status', title: 'Visit already moved', detail: 'This visit is no longer at the stage your screen shows.', code: 'STALE_STATUS', currentStatus: 'in_consult' }) } }, async ({ page, state }) => {
  await card(page, 'e9').dragTo(page.getByTestId('col-2')); await sleep(400);
  await page.getByRole('button', { name: 'Move', exact: true }).click(); await sleep(1500);
  check('error text shown', /no longer at the stage/.test(await page.getByTestId('board-notice').innerText()));
  check('board reloaded after the failure', state.boardCalls >= 2);
});
await run('permission absent: view only', ['queue:view'], {}, async ({ page, state }) => {
  check('cards are not draggable and have no hint', (await card(page, 'e9').getAttribute('draggable')) !== 'true' && !/Drag a waiting patient/.test(await page.getByTestId('doctor-board').innerText()));
  await card(page, 'e9').focus(); await page.keyboard.press('m'); await sleep(200);
  check('M does nothing without queue:edit', (await page.getByRole('dialog').count()) === 0 && state.reassigns.length === 0);
});
await run('empty', PERMS, { board: { v: 1, date: '2026-10-26', doctors: [] } }, async ({ page }) => {
  check('empty state', /No doctors on the board/.test(await page.locator('main').innerText()));
});
await run('390px', PERMS, { viewport: { width: 390, height: 800 } }, async ({ page }) => {
  const m = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }));
  check('390px: no horizontal page scroll (columns scroll inside the board)', m.s <= m.c, JSON.stringify(m));
  check('390px: cards are at least 44px high', (await card(page, 'e9').boundingBox()).height >= 44);
});

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
