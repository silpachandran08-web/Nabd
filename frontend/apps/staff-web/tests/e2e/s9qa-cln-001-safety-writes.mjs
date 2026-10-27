// S9 QA CLN-001: pregnancy chip + NKA writes from the consult screen (mocked API).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3201 node s9qa-cln-001-safety-writes.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const ENTRY = 'e1', PAT = 'p1';
const entry = { id: ENTRY, appointmentId: null, patientId: PAT, doctorId: SELF, departmentId: 'd1', queueDate: '2026-10-18', tokenNumber: 3, status: 'in_consult', priority: false, priorityReason: null, createdAt: new Date().toISOString() };
const patient = { id: PAT, mrn: 'M-1', name: 'Fatima Test', phone: '', dob: '1998-03-04', gender: 'female', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null };
const problem500 = { type: 'internal', title: 'Server error', detail: 'Database unavailable' };

// mode: 'ok' | '500' | 'abort'
function handlers(state) {
  return [
    ['GET', /^\/queue(\?|$)/, { body: [entry] }],
    ['GET', /^\/patients\/p1/, { body: patient }],
    ['GET', /^\/departments\/d1\/flow/, { body: { steps: [] } }],
    ['GET', /^\/clinical\/patients\/p1\/pregnancy-status$/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state.preg) })],
    ['PUT', /^\/clinical\/patients\/p1\/pregnancy-status$/, (r, req) => {
      state.pregPuts.push(req.postData());
      if (state.mode === 'abort') return r.abort('failed');
      if (state.mode === '500') return r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify(problem500) });
      const b = JSON.parse(req.postData());
      state.preg = { status: b.status, expectedDeliveryDate: b.expectedDeliveryDate ?? null, recordedByName: 'Dr Rana', recordedAt: new Date().toISOString() };
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...state.preg, recordedBy: SELF }) });
    }],
    ['GET', /^\/clinical\/patients\/p1\/allergies\/status$/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state.nka) })],
    ['PUT', /^\/clinical\/patients\/p1\/allergies\/status$/, (r) => {
      state.nkaPuts++;
      if (state.mode === '500') return r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify(problem500) });
      state.nka = { state: 'none_documented', activeCount: 0, documentedByName: 'Nurse Noor', documentedAt: new Date().toISOString() };
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state.nka) });
    }],
    ['GET', /^\/clinical\/patients\/p1\/allergies$/, { body: [] }],
    ['GET', /^\/clinical\/patients\/p1\/conditions/, { body: [] }],
    ['GET', /^\/clinical\/patients\/p1\/timeline/, { body: { data: [] } }],
    ['GET', /^\/clinical\/patients\/p1\/prescriptions/, { body: [] }],
    ['GET', /^\/clinical\/vitals\//, { status: 404, body: {} }],
    ['GET', /^\/clinical\/notes\//, { status: 404, body: {} }],
    ['GET', /^\/clinical\/prescriptions\//, { status: 404, body: {} }],
    ['GET', /./, { body: [] }],
  ];
}
const fresh = (mode = 'ok') => ({ mode, preg: { status: 'not_documented', expectedDeliveryDate: null, recordedByName: null, recordedAt: null }, pregPuts: [], nkaPuts: 0,
  nka: { state: 'not_assessed', activeCount: 0, documentedByName: null, documentedAt: null } });
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const strip = (page) => page.getByLabel('Clinical safety');

async function open(perms, state, viewport = { width: 1280, height: 900 }) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, handlers(state));
  await s.page.goto(`${APP}/consult?entry=${ENTRY}&step=reason`, { waitUntil: 'networkidle' });
  await strip(s.page).waitFor({ timeout: 8000 });
  return s;
}
const PERMS = ['queue:view', 'queue:edit', 'patients:view', 'clinical:view', 'clinical:edit'];
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload|ERR_FAILED|net::/.test(x))];

async function pick(page, label) {
  await strip(page).getByRole('button', { name: 'Record' }).click();
  await page.getByLabel(label, { exact: false }).first().check();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
}

try {
  // 1 happy path: pregnant saves, chip shows saved state within 2 s
  let st = fresh(); let s = await open(PERMS, st, { width: 390, height: 800 });
  let t0 = Date.now();
  check('1a chip starts as "Not documented — ask"', /Not documented — ask/.test(await strip(s.page).innerText()));
  await pick(s.page, 'Pregnant');
  await strip(s.page).getByText(/^Pregnant/).waitFor({ timeout: 2000 }).then(() => check('1b saved state visible within 2 s', true, `${Date.now() - t0} ms`), () => check('1b saved state visible within 2 s', false));
  check('1c exactly one PUT with status pregnant', st.pregPuts.length === 1 && JSON.parse(st.pregPuts[0]).status === 'pregnant', st.pregPuts.join('|'));
  check('1d method was PUT to pregnancy-status', s.log.requests.some((r) => r.method === 'PUT' && /pregnancy-status$/.test(r.path)));
  check('1e 390px: no horizontal scroll', await noOverflow(s.page));
  check('1f zero console/page errors', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('happy', s.log); await s.browser.close();

  // 2 server 500: inline error, chip never shows saved state, reverts
  st = fresh('500'); s = await open(PERMS, st);
  await pick(s.page, 'Pregnant');
  await s.page.getByRole('alert').filter({ hasText: /Couldn't save/ }).waitFor({ timeout: 3000 }).then(() => check('2a inline error shown on 500', true), () => check('2a inline error shown on 500', false));
  let txt = await strip(s.page).innerText();
  check('2b error names the server problem and says nothing was recorded', /Database unavailable|Server error/.test(txt) && /Nothing was recorded/.test(txt), JSON.stringify(txt.slice(0, 300)));
  check('2c pill still "Not documented — ask" (no false saved state)', /Not documented — ask/.test(txt));
  await s.page.getByRole('button', { name: 'Cancel' }).click();
  txt = await strip(s.page).innerText();
  check('2d after Cancel the chip is the unchanged server state', /Not documented — ask/.test(txt) && !/Couldn't save/.test(txt));
  // retry succeeds after server recovers
  st.mode = 'ok';
  await strip(s.page).getByRole('button', { name: 'Record' }).click();
  await s.page.getByLabel('Lactating').check();
  await s.page.getByRole('button', { name: 'Save', exact: true }).click();
  await strip(s.page).getByText(/^Lactating/).waitFor({ timeout: 3000 }).then(() => check('2e retry after recovery saves', true), () => check('2e retry after recovery saves', false));
  check('2f zero console/page errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  // 3 network failure (request aborted)
  st = fresh('abort'); s = await open(PERMS, st);
  await pick(s.page, 'Pregnant');
  await s.page.getByRole('alert').filter({ hasText: /Couldn't save/ }).waitFor({ timeout: 4000 }).then(() => check('3a inline error on network failure', true), () => check('3a inline error on network failure', false));
  check('3b still not shown as saved', /Not documented — ask/.test(await strip(s.page).innerText()));
  await s.browser.close();

  // 4 permission absent: no Record control without clinical:edit
  st = fresh(); s = await open(['queue:view', 'queue:edit', 'patients:view', 'clinical:view'], st);
  check('4a no Record/Update button without clinical:edit', await strip(s.page).getByRole('button', { name: /^(Record|Update)$/ }).count() === 0);
  check('4b chip text still visible', /Not documented — ask/.test(await strip(s.page).innerText()));
  await s.browser.close();

  // 5 NKA: allergy register write fails -> error, status unchanged; then succeeds -> strip names who
  st = fresh('500'); s = await open(PERMS, st);
  check('5a strip says "None recorded · not yet asked" initially', /None recorded · not yet asked/.test(await strip(s.page).innerText()));
  await strip(s.page).getByRole('button', { name: 'Allergy register' }).click();
  await s.page.getByRole('button', { name: 'Document no known allergies' }).click();
  await s.page.getByText(/Database unavailable|Couldn't document the status/).first().waitFor({ timeout: 3000 }).then(() => check('5b NKA failure shows an error', true), () => check('5b NKA failure shows an error', false));
  check('5c register does not claim NKA documented after failure', !/No known allergies \(documented\)/.test(await s.page.locator('body').innerText()));
  st.mode = 'ok';
  await s.page.getByRole('button', { name: 'Document no known allergies' }).click();
  // FEF-039: the strip and the register now share one glossary term for NKA, so the sentence is on screen twice (the strip used to say "No known allergies · documented").
  await s.page.getByText('No known allergies (documented)').first().waitFor({ timeout: 3000 }).then(() => check('5d NKA saved after retry', true), () => check('5d NKA saved after retry', false));
  await s.browser.close();

  // 6 NKA already documented: strip names the documenter
  st = fresh(); st.nka = { state: 'none_documented', activeCount: 0, documentedByName: 'Nurse Noor', documentedAt: new Date().toISOString() };
  s = await open(PERMS, st, { width: 390, height: 800 });
  const stripTxt = await strip(s.page).innerText();
  check('6a strip shows NKA and documenter name', /No known allergies/.test(stripTxt) && /Nurse Noor/.test(stripTxt), JSON.stringify(stripTxt.slice(0, 200)));
  check('6b 390px no horizontal scroll', await noOverflow(s.page));
  await s.browser.close();
} catch (e) {
  check('script ran to completion', false, String(e).slice(0, 300));
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
