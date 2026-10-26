// CLN-020 / CLN-021: the drug typeahead answers from the on-device index (no request per keystroke, < 100 ms), falls back to
// the server for a query the index lacks, revalidates the stored copy with If-None-Match, shows the 'Uncoded' badge, and the
// India print reads generic first with the brand in brackets only when one was picked.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3813 node s15-cln020-021-drug-typeahead.mjs
import zlib from 'node:zlib';
import { launch, seedClinic, mockApi, check, results, dumpLog, CORS, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const ENTRY = 'e1', PAT = 'p1';
const entry = { id: ENTRY, appointmentId: null, patientId: PAT, doctorId: SELF, departmentId: 'd1', queueDate: '2026-10-19', tokenNumber: 3, status: 'in_consult', priority: false, priorityReason: null, createdAt: new Date().toISOString() };
const patient = { id: PAT, mrn: 'M-1', name: 'Omar Test', phone: '', dob: '1980-03-04', gender: 'male', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null };
const draftRx = { id: 'rx1', queueEntryId: ENTRY, patientId: PAT, doctorId: SELF, status: 'draft', createdAt: new Date().toISOString(), signedAt: null,
  items: [{ id: 'i1', drugName: '', dosage: '', frequency: '', duration: '', instructions: null, warnings: [] }], unresolvedWarnings: 0, warnings: [], allergySnapshot: null };
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload|ERR_FAILED|net::/.test(x))];
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const PERMS = ['queue:view', 'queue:edit', 'patients:view', 'clinical:view', 'clinical:edit'];

const row = (id, display, generic, brand, strength, form, sched = []) => [id, display, generic, brand, strength, form, sched];
const REV = 'rev-1';
const INDEX = zlib.gzipSync(JSON.stringify({ v: 1, master: 1, rev: REV, items: [
  row('in-mox', 'Amoxicillin 500 mg capsule (Mox)', 'Amoxicillin', 'Mox', '500 mg', 'capsule'),
  row('in-amoxil', 'Amoxicillin 500 mg capsule (Amoxil)', 'Amoxicillin', 'Amoxil', '500 mg', 'capsule', ['H']),
] }));

function handlers(state) {
  return [
    ['GET', /^\/clinical\/drugs\/client-index/, (r, req) => {
      state.indexCalls.push(req.headers()['if-none-match'] ?? null);
      if (state.indexStatus === 500) return r.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"title":"x"}' });
      if (req.headers()['if-none-match'] === `"${REV}"`) return r.fulfill({ status: 304, body: '' });
      return r.fulfill({ status: 200, contentType: 'application/octet-stream', headers: { ETag: `"${REV}"` }, body: INDEX });
    }],
    ['GET', /^\/clinical\/drugs\/search/, (r, req) => {
      state.searchCalls.push(decodeURIComponent(new URL(req.url()).searchParams.get('q')));
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ name: 'Kadha syrup', source: 'history', code: null, inStock: null, prescriptionOnly: null }]) });
    }],
    ['GET', /^\/queue(\?|$)/, { body: [entry] }],
    ['GET', /^\/patients\/p1/, { body: patient }],
    ['GET', /^\/departments\/d1\/flow/, { body: { steps: [] } }],
    ['GET', /^\/clinical\/prescriptions\/e1\/signing-authority$/, { body: { canSign: true, reason: null } }],
    ['GET', /^\/clinical\/prescriptions\/e1$/, { body: draftRx }],
    ['GET', /^\/clinical\/patients\/p1\/allergies\/status$/, { body: { state: 'not_assessed', activeCount: 0 } }],
    ['GET', /^\/clinical\/patients\/p1\/pregnancy-status$/, { body: { status: 'not_documented' } }],
    ['GET', /^\/clinical\/patients\/p1\/(allergies|conditions|prescriptions)/, { body: [] }],
    ['GET', /^\/clinical\/patients\/p1\/timeline/, { body: { data: [] } }],
    ['GET', /^\/clinical\/vitals\//, { status: 404, body: {} }],
    ['GET', /^\/clinical\/notes\//, { status: 404, body: {} }],
    ['GET', /./, { body: [] }],
  ];
}


const instrument = (page) => page.evaluate(() => {
  const w = window; w.__t = { input: 0, shown: 0 };
  document.addEventListener('input', () => { w.__t.input = performance.now(); w.__t.shown = 0; }, true);
  new MutationObserver(() => { if (!w.__t.shown && document.querySelector('[role="listbox"] [role="option"]')) w.__t.shown = performance.now(); }).observe(document.body, { childList: true, subtree: true });
});

try {
  const state = { indexCalls: [], searchCalls: [], indexStatus: 200 };
  let s = await launch({ viewport: { width: 390, height: 844 } });
  await seedClinic(s.page, PERMS, { staffName: 'Dr Test', roleName: 'Doctor' });
  await mockApi(s.page, s.log, handlers(state));
  await s.page.goto(`${APP}/consult?entry=${ENTRY}&step=plan`, { waitUntil: 'networkidle' });
  await s.page.getByRole('heading', { name: 'Prescription', exact: true }).waitFor({ timeout: 10000 });
  const generic = s.page.getByLabel('Generic name').first();
  await generic.focus();
  await s.page.waitForTimeout(800); // first focus loads the index (one request)
  check('1a one index request on first focus, without If-None-Match', state.indexCalls.length === 1 && state.indexCalls[0] === null, JSON.stringify(state.indexCalls));
  await instrument(s.page);
  await generic.fill('amo'); // one input event: the list opens from closed, so the DOM change is the answer
  await s.page.getByRole('listbox').getByRole('option').first().waitFor({ timeout: 3000 });
  const t = await s.page.evaluate(() => window.__t);
  check('1b results shown from the device index in < 100 ms', t.shown > 0 && t.shown - t.input < 100, `${(t.shown - t.input).toFixed(1)} ms`);
  check('1c no /drugs/search request for a query the index answers', state.searchCalls.length === 0, state.searchCalls.join(','));
  const opts = await s.page.getByRole('listbox').getByRole('option').allInnerTexts();
  check('1d generic first, the most used product first', /^Amoxicillin 500 mg capsule \(Mox\)/.test(opts[0] ?? '') && /\(Amoxil\)/.test(opts[1] ?? ''), JSON.stringify(opts));
  await s.page.getByRole('listbox').getByRole('option').first().click();
  check('1e picking fills the generic line and the brand', await generic.inputValue() === 'Amoxicillin 500 mg capsule' && await s.page.getByLabel(/^Brand/).first().inputValue() === 'Mox');
  check('1f a coded line shows no Uncoded badge', await s.page.getByTestId('uncoded-badge').count() === 0);
  await generic.pressSequentially('x', { delay: 10 });
  check('1g editing the generic text makes the line Uncoded again', await s.page.getByTestId('uncoded-badge').count() === 1);
  // not in the index -> server fallback
  await generic.fill('');
  await generic.pressSequentially('kadha', { delay: 20 });
  await s.page.getByRole('listbox').getByRole('option').first().waitFor({ timeout: 3000 });
  check('2a a query the index lacks falls back to the server once debounced', state.searchCalls.includes('kadha'), state.searchCalls.join(','));
  check('2b server rows still pick as free text', /Kadha syrup/.test((await s.page.getByRole('listbox').getByRole('option').allInnerTexts())[0] ?? ''));
  check('3a 390px: no horizontal scroll', await noOverflow(s.page));
  check('3b zero console/page errors', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('first', s.log);

  // reload: stored copy answers at once, revalidated with If-None-Match, 304 keeps it
  state.searchCalls.length = 0;
  await s.page.reload({ waitUntil: 'networkidle' });
  await s.page.getByRole('heading', { name: 'Prescription', exact: true }).waitFor({ timeout: 10000 });
  await s.page.getByLabel('Generic name').first().focus();
  await s.page.waitForTimeout(800);
  check('4a reload revalidates with If-None-Match', state.indexCalls.length === 2 && state.indexCalls[1] === `"${REV}"`, JSON.stringify(state.indexCalls));
  await s.page.getByLabel('Generic name').first().pressSequentially('amo', { delay: 20 });
  await s.page.getByRole('listbox').getByRole('option').first().waitFor({ timeout: 3000 });
  check('4b after a 304 the stored copy still answers locally', state.searchCalls.length === 0);
  await s.browser.close();

  // index endpoint failing: typing still works through the server
  const bad = { indexCalls: [], searchCalls: [], indexStatus: 500 };
  s = await launch();
  await seedClinic(s.page, PERMS, { staffName: 'Dr Test', roleName: 'Doctor' });
  await mockApi(s.page, s.log, handlers(bad));
  await s.page.goto(`${APP}/consult?entry=${ENTRY}&step=plan`, { waitUntil: 'networkidle' });
  await s.page.getByRole('heading', { name: 'Prescription', exact: true }).waitFor({ timeout: 10000 });
  const g2 = s.page.getByLabel('Generic name').first();
  await g2.focus(); await s.page.waitForTimeout(500);
  await g2.pressSequentially('kadha', { delay: 20 });
  await s.page.getByRole('listbox').getByRole('option').first().waitFor({ timeout: 3000 }).then(() => check('5a index error: the server search still answers', true), () => check('5a index error: the server search still answers', false));
  check('5b index error is not a page error', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  // print: generic first (India), brand in brackets only if chosen; other regions keep the old layout
  const signed = (items) => ({ ...draftRx, status: 'signed', signedAt: new Date().toISOString(), items, allergySnapshot: { state: 'none_documented', allergies: [], documentedBy: SELF, documentedByName: 'Dr Test', documentedAt: new Date().toISOString() } });
  const printHandlers = (genericFirst, items) => [
    ['GET', /^\/clinical\/prescriptions\/e1\/print-header$/, { body: { prescriptionId: 'rx1', signedAt: new Date().toISOString(), clinicName: 'QA Clinic', clinicRegion: 'IN', clinicPhone: null, prescriberId: SELF, prescriberName: 'Dr Test', professionalRegistrationNo: 'KMC 1', genericFirst } }],
    ['GET', /^\/clinical\/prescriptions\/e1$/, { body: signed(items) }],
    ['GET', /^\/patients\/p1/, { body: patient }],
    ['GET', /./, { body: [] }],
  ];
  const lines = [{ id: 'a', drugName: 'Amoxicillin 500 mg capsule', brandName: 'Mox', drugId: 'in-mox', coded: true, warnings: [] }, { id: 'b', drugName: 'Amoxicillin 500 mg capsule', brandName: null, drugId: 'in-mox', coded: true, warnings: [] }];
  s = await launch();
  await seedClinic(s.page, PERMS);
  await mockApi(s.page, s.log, printHandlers(true, lines));
  await s.page.goto(`${APP}/consult/rx/${ENTRY}/print`, { waitUntil: 'networkidle' });
  await s.page.getByText('Medicines', { exact: true }).first().waitFor({ timeout: 10000 });
  const printed = await s.page.getByTestId('med-line').allInnerTexts();
  check('6a India print with a brand: "Amoxicillin 500 mg capsule (Mox)"', printed[0] === 'Amoxicillin 500 mg capsule (Mox)', JSON.stringify(printed));
  check('6b India print without a brand: no brackets', printed[1] === 'Amoxicillin 500 mg capsule', JSON.stringify(printed));
  check('6c zero console/page errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();
  s = await launch();
  await seedClinic(s.page, PERMS);
  await mockApi(s.page, s.log, printHandlers(false, lines));
  await s.page.goto(`${APP}/consult/rx/${ENTRY}/print`, { waitUntil: 'networkidle' });
  await s.page.getByText('Medicines', { exact: true }).first().waitFor({ timeout: 10000 });
  check('7a other regions: old layout (Brand line, no med-line)', await s.page.getByTestId('med-line').count() === 0 && /Brand\s*Mox/.test(await s.page.locator('body').innerText()));
  await s.browser.close();
} catch (e) {
  check('script ran to the end', false, String(e));
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
