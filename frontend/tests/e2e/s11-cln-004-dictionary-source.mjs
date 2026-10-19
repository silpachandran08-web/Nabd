// S11 CLN-004: "Source: <dictionary> <version>" under each Rx warning, read once per session (mocked API).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3501 node s11-cln-004-dictionary-source.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const ENTRY = 'e1', PAT = 'p1';
const entry = { id: ENTRY, appointmentId: null, patientId: PAT, doctorId: SELF, departmentId: 'd1', queueDate: '2026-10-18', tokenNumber: 3, status: 'in_consult', priority: false, priorityReason: null, createdAt: new Date().toISOString() };
const patient = { id: PAT, mrn: 'M-1', name: 'Asha Test', phone: '', dob: '1998-03-04', gender: 'female', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null };
const warning = { kind: 'pregnancy', severity: 'caution', message: 'Caution: avoid in pregnancy/lactation for patients of childbearing potential', matched: 'pregnancy/lactation',
  requiresDecision: true, resolved: false, reasonOptions: [{ code: 'benefit_outweighs_risk', label: 'Clinical benefit outweighs risk' }], decision: null };
const item = (warnings) => ({ id: 'i1', drugName: 'Isotroin 20mg', dosage: '20mg', frequency: 'OD', duration: '30 days', instructions: '', displayOrder: 0, warnings });
const rx = (warnings) => ({ id: 'rx1', queueEntryId: ENTRY, patientId: PAT, status: 'draft', signedAt: null, items: [item(warnings)], unresolvedWarnings: warnings.length, warnings: [], allergySnapshot: null, safetyDictionaryVersion: null });
const DICT = { region: 'IN', version: 1, label: 'IN v1', approvedBy: 'CSO', approvedAt: '2026-10-01T00:00:00Z', source: 'Nabd CSO dictionary', controlledLabel: { en: 'Schedule H1 controlled substance' } };

function handlers(state) {
  return [
    ['GET', /^\/queue(\?|$)/, { body: [entry] }],
    ['GET', /^\/patients\/p1/, { body: patient }],
    ['GET', /^\/departments\/d1\/flow/, { body: { steps: [] } }],
    ['GET', /^\/clinical\/safety-dictionary$/, (r) => {
      state.dictCalls++;
      if (state.dict === 500) return r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ type: 'internal', title: 'Server error', detail: 'x' }) });
      if (state.dict === 403) return r.fulfill({ status: 403, contentType: 'application/problem+json', body: JSON.stringify({ type: 'forbidden', title: 'Forbidden', detail: 'x' }) });
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(DICT) });
    }],
    ['GET', /^\/clinical\/patients\/p1\/pregnancy-status$/, { body: { status: 'not_documented', expectedDeliveryDate: null, recordedByName: null, recordedAt: null } }],
    ['GET', /^\/clinical\/patients\/p1\/allergies\/status$/, { body: { state: 'not_assessed', activeCount: 0, documentedByName: null, documentedAt: null } }],
    ['GET', /^\/clinical\/patients\/p1\/allergies$/, { body: [] }],
    ['GET', /^\/clinical\/patients\/p1\/conditions/, { body: [] }],
    ['GET', /^\/clinical\/patients\/p1\/timeline/, { body: { data: [] } }],
    ['GET', /^\/clinical\/patients\/p1\/prescriptions/, { body: [] }],
    ['GET', /^\/clinical\/vitals\//, { status: 404, body: {} }],
    ['GET', /^\/clinical\/notes\//, { status: 404, body: {} }],
    ['GET', /^\/clinical\/prescriptions\/e1$/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rx(state.warnings)) })],
    ['GET', /./, { body: [] }],
  ];
}
const PERMS = ['queue:view', 'queue:edit', 'patients:view', 'clinical:view', 'clinical:edit'];
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload|ERR_FAILED|net::/.test(x))];
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);

async function open(state, viewport = { width: 1280, height: 900 }) {
  const s = await launch({ viewport });
  await seedClinic(s.page, PERMS);
  await mockApi(s.page, s.log, handlers(state));
  await s.page.goto(`${APP}/consult?entry=${ENTRY}&step=plan`, { waitUntil: 'networkidle' });
  return s;
}
const card = (page) => page.locator('[role="alert"], [role="status"]').filter({ hasText: 'Pregnancy / lactation warning' }).first();

try {
  // 1 happy path at 390px: the source line sits inside the warning card; one read per session across step changes
  let st = { dict: 200, warnings: [warning], dictCalls: 0 };
  let s = await open(st, { width: 390, height: 800 });
  await card(s.page).waitFor({ timeout: 8000 });
  await card(s.page).getByText('Source: Nabd CSO dictionary IN v1').waitFor({ timeout: 4000 })
    .then(() => true, () => false).then(async (ok) => check('1a source line shown under the warning', ok, ok ? '' : (await card(s.page).innerText()).slice(0, 300)));
  await s.page.getByRole('button', { name: /Reason/ }).first().click().catch(() => {});
  await s.page.getByRole('button', { name: /Plan/ }).first().click().catch(() => {});
  await card(s.page).waitFor({ timeout: 4000 });
  check('1b dictionary read exactly once', st.dictCalls === 1, `calls=${st.dictCalls}`);
  check('1c 390px: no horizontal scroll', await noOverflow(s.page));
  check('1d zero console/page errors', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('happy', s.log); await s.browser.close();

  // 2 server error: the warning still shows, the source line says unavailable (never a made-up version)
  st = { dict: 500, warnings: [warning], dictCalls: 0 };
  s = await open(st);
  await card(s.page).waitFor({ timeout: 8000 });
  let txt = await card(s.page).innerText();
  check('2a warning still visible on dictionary error', /avoid in pregnancy/.test(txt));
  check('2b source line says unavailable', /Source: safety dictionary version unavailable/.test(txt) && !/IN v/.test(txt), JSON.stringify(txt.slice(0, 300)));
  check('2c zero page errors', s.log.pageErrors.length === 0, s.log.pageErrors.join('|'));
  await s.browser.close();

  // 3 no permission to read it (403): same unavailable line, no extra controls
  st = { dict: 403, warnings: [warning], dictCalls: 0 };
  s = await open(st);
  await card(s.page).waitFor({ timeout: 8000 });
  txt = await card(s.page).innerText();
  check('3a 403 -> unavailable line, no version shown', /unavailable/.test(txt) && !/Nabd CSO dictionary/.test(txt));
  await s.browser.close();

  // 4 empty: a line without warnings renders no source line at all
  st = { dict: 200, warnings: [], dictCalls: 0 };
  s = await open(st);
  await s.page.getByText('Prescription', { exact: false }).first().waitFor({ timeout: 8000 });
  await s.page.waitForTimeout(500);
  check('4a no warnings -> no source line', await s.page.getByText(/^Source: /).count() === 0);
  await s.browser.close();
} catch (e) {
  check('script ran to completion', false, String(e).slice(0, 300));
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
