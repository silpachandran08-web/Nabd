// Independent QA (S11 CLN-005 + CLN-004 UI): consult Sign control by signing authority, and the dictionary source line
// under EVERY Rx warning (mocked API).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3611 node tests/e2e/s11qa-cln-005.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const entry = { id: 'e1', appointmentId: null, patientId: 'p1', doctorId: SELF, departmentId: 'd1', queueDate: '2026-10-19', tokenNumber: 7, status: 'in_consult', priority: false, priorityReason: null, createdAt: new Date().toISOString() };
const patient = { id: 'p1', mrn: 'M-7', name: 'Qa Consult', phone: '', dob: '1979-02-02', gender: 'male', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null };
const W = (kind, message) => ({ kind, severity: 'severe', message, matched: kind, requiresDecision: true, resolved: false,
  reasonOptions: [{ code: 'no_suitable_alternative', label: 'No suitable alternative' }], decision: null });
const rx = (warnings) => ({ id: 'rx1', queueEntryId: 'e1', patientId: 'p1', doctorId: SELF, status: 'draft', createdAt: new Date().toISOString(), signedAt: null,
  items: [{ id: 'i1', drugName: 'Qa Drug', dosage: '1 tab', frequency: 'OD', duration: '3 days', instructions: null, displayOrder: 0, warnings }],
  unresolvedWarnings: warnings.length, warnings: [], allergySnapshot: null, safetyDictionaryVersion: null });
const PERMS = ['queue:view', 'queue:edit', 'patients:view', 'clinical:view', 'clinical:edit'];
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => x.startsWith('[error]') && !/Failed to load resource|preloaded using link preload|ERR_FAILED|net::/.test(x))];

async function consult({ authority = { canSign: true, reason: null }, warnings = [], dict = null, region = 'IN', viewport } = {}) {
  const s = await launch({ viewport });
  await seedClinic(s.page, PERMS, { staffName: 'Dr Qa', roleName: 'Doctor', tenantRegion: region });
  s.dictCalls = 0;
  await mockApi(s.page, s.log, [
    ['GET', /^\/queue(\?|$)/, { body: [entry] }],
    ['GET', /^\/patients\/p1/, { body: patient }],
    ['GET', /^\/departments\/d1\/flow/, { body: { steps: [] } }],
    ['GET', /^\/clinical\/safety-dictionary$/, (r) => { s.dictCalls++; return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(dict) }); }],
    ['GET', /^\/clinical\/prescriptions\/e1\/signing-authority$/, { body: authority }],
    ['GET', /^\/clinical\/prescriptions\/e1$/, { body: rx(warnings) }],
    ['GET', /^\/clinical\/patients\/p1\/allergies\/status$/, { body: { state: 'not_assessed', activeCount: 0 } }],
    ['GET', /^\/clinical\/patients\/p1\/pregnancy-status$/, { body: { status: 'not_documented' } }],
    ['GET', /^\/clinical\/patients\/p1\/(allergies|conditions|prescriptions)/, { body: [] }],
    ['GET', /^\/clinical\/patients\/p1\/timeline/, { body: { data: [] } }],
    ['GET', /^\/clinical\/(vitals|notes)\//, { status: 404, body: {} }],
    ['GET', /./, { body: [] }],
  ]);
  await s.page.goto(`${APP}/consult?entry=e1&step=plan`, { waitUntil: 'networkidle' });
  await s.page.getByRole('heading', { name: 'Prescription', exact: true }).waitFor({ timeout: 10000 });
  await s.page.waitForFunction(() => !/Checking signing rights/.test(document.body.innerText), null, { timeout: 5000 }).catch(() => {});
  return s;
}
const signBtn = (p) => p.getByRole('button', { name: /Review and sign|Resolve the warning first|^Sign/ });
const blocked = (p) => p.getByTestId('rx-sign-blocked');
const seen = [];

try {
  // A break-glass session: no Sign control and the emergency wording.
  let s = await consult({ authority: { canSign: false, reason: 'break-glass-cannot-sign' } });
  check('A1 break-glass: Sign control absent', await signBtn(s.page).count() === 0);
  check('A2 break-glass: says emergency access cannot sign', /Emergency access is read-only/.test(await blocked(s.page).innerText().catch(() => '')));
  seen.push(...errs(s.log)); await s.browser.close();

  // B nurse / non-visit doctor: absent + "Send to doctor to sign"; unknown future code falls back to the same safe wording.
  for (const reason of ['not-prescriber', 'some-new-code']) {
    s = await consult({ authority: { canSign: false, reason } });
    check(`B ${reason}: Sign absent`, await signBtn(s.page).count() === 0);
    check(`B ${reason}: "Send to doctor to sign"`, /Send to doctor to sign/.test(await blocked(s.page).innerText().catch(() => '')));
    seen.push(...errs(s.log)); await s.browser.close();
  }

  // C licensed visit doctor: Sign present, no blocking line.
  s = await consult({ authority: { canSign: true, reason: null } });
  check('C1 licensed: Sign control present', await signBtn(s.page).count() >= 1);
  check('C2 licensed: no blocking line', await blocked(s.page).count() === 0);
  seen.push(...errs(s.log)); await s.browser.close();

  // D CLN-004: two warnings, each carries the source line; KSA region label; one dictionary read.
  s = await consult({
    region: 'KSA', viewport: { width: 390, height: 844 },
    warnings: [W('allergy', 'Allergy: Penicillin (severe)'), W('controlled', 'SFDA-controlled substance: extra documentation required')],
    dict: { region: 'SA', version: 2, label: 'SA v2', approvedBy: 'QA CSO', approvedAt: '2026-10-01T00:00:00Z', source: 'Nabd CSO dictionary', controlledLabel: { en: 'SFDA-controlled substance', ar: 'x' } },
  });
  const lines = s.page.getByText('Source: Nabd CSO dictionary SA v2');
  await lines.first().waitFor({ timeout: 6000 }).catch(() => {});
  check('D1 source line under each of the two warnings', await lines.count() === 2, `count=${await lines.count()}`);
  check('D2 dictionary read once for both warnings', s.dictCalls === 1, `calls=${s.dictCalls}`);
  check('D3 390px: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth));
  seen.push(...errs(s.log)); dumpLog('D', s.log); await s.browser.close();

  check('Z zero console/page errors across runs', seen.length === 0, seen.slice(0, 3).join(' | '));
} catch (e) {
  check('script ran to completion', false, String(e).slice(0, 300));
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
