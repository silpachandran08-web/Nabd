// CLN-005: Sign is offered only to a registered clinician (consult), and the owner's staff dialog marks clinicians.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3502 node s11-cln-005-licensed-signing.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const ENTRY = 'e1', PAT = 'p1', STAFF_ID = '33333333-3333-3333-3333-333333333333';
const entry = { id: ENTRY, appointmentId: null, patientId: PAT, doctorId: SELF, departmentId: 'd1', queueDate: '2026-10-19', tokenNumber: 3, status: 'in_consult', priority: false, priorityReason: null, createdAt: new Date().toISOString() };
const patient = { id: PAT, mrn: 'M-1', name: 'Omar Test', phone: '', dob: '1980-03-04', gender: 'male', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null };
const draftRx = { id: 'rx1', queueEntryId: ENTRY, patientId: PAT, doctorId: SELF, status: 'draft', createdAt: new Date().toISOString(), signedAt: null,
  items: [{ id: 'i1', drugName: 'Paracetamol', dosage: '500mg', frequency: 'TDS', duration: '3 days', instructions: null, warnings: [] }], unresolvedWarnings: 0, warnings: [], allergySnapshot: null };
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload|ERR_FAILED|net::/.test(x))];
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const PERMS = ['queue:view', 'queue:edit', 'patients:view', 'clinical:view', 'clinical:edit'];

function consultHandlers(authority) {
  return [
    ['GET', /^\/queue(\?|$)/, { body: [entry] }],
    ['GET', /^\/patients\/p1/, { body: patient }],
    ['GET', /^\/departments\/d1\/flow/, { body: { steps: [] } }],
    ['GET', /^\/clinical\/prescriptions\/e1\/signing-authority$/, typeof authority === 'number' ? { status: authority, body: { type: 'internal', title: 'Server error', detail: 'Down' } } : { body: authority }],
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

async function consult(authority, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, PERMS, { staffName: 'Dr Test', roleName: 'Doctor' });
  await mockApi(s.page, s.log, consultHandlers(authority));
  await s.page.goto(`${APP}/consult?entry=${ENTRY}&step=plan`, { waitUntil: 'networkidle' });
  await s.page.getByRole('heading', { name: 'Prescription', exact: true }).waitFor({ timeout: 10000 });
  await s.page.waitForFunction(() => !/Checking signing rights/.test(document.body.innerText), null, { timeout: 5000 }).catch(() => {});
  return s;
}

const signBtn = (page) => page.getByRole('button', { name: /Review and sign|Resolve the warning first/ });
const blocked = (page) => page.getByTestId('rx-sign-blocked');

try {
  // 1 happy path: a licensed visit doctor sees Review and sign, no blocking line
  let s = await consult({ canSign: true, reason: null });
  check('1a licensed doctor: Review and sign shown', await signBtn(s.page).count() === 1);
  check('1b no "cannot sign" line', await blocked(s.page).count() === 0);
  check('1c signing-authority was asked for this visit', s.log.requests.some((r) => r.method === 'GET' && r.path === '/clinical/prescriptions/e1/signing-authority'));
  check('1d zero console/page errors', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('happy', s.log); await s.browser.close();

  // 2 not a registered clinician: control ABSENT (not disabled) and the reason is shown
  s = await consult({ canSign: false, reason: 'not-licensed-clinician' }, { width: 390, height: 844 });
  check('2a unlicensed: no Sign button at all', await signBtn(s.page).count() === 0);
  check('2b explains "Needs a registered clinician"', /Needs a registered clinician/.test(await blocked(s.page).innerText().catch(() => '')));
  check('2c 390px: no horizontal scroll', await noOverflow(s.page));
  check('2d zero console/page errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  // 3 boundary: licence expired
  s = await consult({ canSign: false, reason: 'licence-expired' });
  check('3a expired: no Sign button', await signBtn(s.page).count() === 0);
  check('3b says the registration has expired', /registration has expired/.test(await blocked(s.page).innerText().catch(() => '')));
  await s.browser.close();

  // 4 error: the authority check fails -> no Sign button, a visible failure (never a silent default)
  s = await consult(500);
  check('4a check failed: no Sign button', await signBtn(s.page).count() === 0);
  check('4b failure is visible', /Couldn't check signing rights/.test(await blocked(s.page).innerText().catch(() => '')));
  await s.browser.close();

  // 5 not the prescriber: nurse-style wording
  s = await consult({ canSign: false, reason: 'not-prescriber' });
  check('5a not prescriber: "Send to doctor to sign"', /Send to doctor to sign/.test(await blocked(s.page).innerText().catch(() => '')));
  await s.browser.close();

  // 6 owner staff dialog: clinician toggle needs a registration number; order is PATCH number, then PUT licence
  const staff = [{ id: STAFF_ID, email: 'dr@x.test', name: 'Dr Lina', mobilePhone: '+919800000001', roleId: 'r1', status: 'active', scope: 'all_clinic_patients', lastSeenAt: null, professionalRegistrationNo: null }];
  const state = { licence: { staffId: STAFF_ID, isClinician: false, hasRegistrationNo: false, licenceExpiresOn: null, licenceOverrideUntil: null }, calls: [], licenceStatus: 200 };
  const staffHandlers = [
    ['GET', /^\/staff\?limit=50$/, { body: { data: staff, page: { nextCursor: null, limit: 50 } } }],
    ['GET', /^\/roles$/, { body: [{ id: 'r1', name: 'Doctor', builtIn: false, grants: [], mfaRequired: false }] }],
    ['GET', /^\/staff\/[^/]+\/clinical-licence$/, (r) => r.fulfill({ status: state.licenceStatus, contentType: 'application/json', body: JSON.stringify(state.licence) })],
    ['PATCH', /^\/staff\/[^/]+$/, (r, req) => { state.calls.push('PATCH ' + req.postData()); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...staff[0], professionalRegistrationNo: JSON.parse(req.postData()).professionalRegistrationNo }) }); }],
    ['PUT', /^\/staff\/[^/]+\/clinical-licence$/, (r, req) => { state.calls.push('PUT ' + req.postData()); state.licence = { ...state.licence, ...JSON.parse(req.postData()) }; return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state.licence) }); }],
    ['GET', /./, { body: [] }],
  ];
  const ownerPerms = ['staff:view', 'staff:edit', 'staff:create', 'clinical:view', 'clinical:approve'];
  s = await launch({ viewport: { width: 390, height: 844 } });
  await seedClinic(s.page, ownerPerms, { owner: true, roleName: 'Owner' });
  await mockApi(s.page, s.log, staffHandlers);
  await s.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  await s.page.getByRole('button', { name: 'Add reg. no.' }).first().click();
  const toggle = s.page.getByLabel(/Registered clinician/);
  await toggle.waitFor({ timeout: 5000 }).then(() => check('6a dialog shows the clinician toggle once loaded', true), () => check('6a dialog shows the clinician toggle once loaded', false));
  await toggle.check();
  await s.page.getByRole('button', { name: 'Save', exact: true }).click();
  check('6b clinician without a number is refused in the form', /Add the registration number before/.test(await s.page.getByRole('alert').first().innerText().catch(() => '')));
  check('6c nothing was sent', state.calls.length === 0, state.calls.join('|'));
  await s.page.getByLabel(/Medical registration number/).fill('KMC 12345');
  await s.page.getByLabel(/Registration valid until/).fill('2027-12-31');
  await s.page.getByRole('button', { name: 'Save', exact: true }).click();
  await s.page.getByLabel(/Registered clinician/).waitFor({ state: 'detached', timeout: 4000 }).catch(() => {});
  check('6d number saved first, then the licence', state.calls.length === 2 && state.calls[0].startsWith('PATCH') && state.calls[1].startsWith('PUT'), state.calls.join('|'));
  check('6e PUT carries isClinician and the expiry', /"isClinician":true/.test(state.calls[1] ?? '') && /2027-12-31/.test(state.calls[1] ?? ''));
  check('6f 390px: no horizontal scroll', await noOverflow(s.page));
  check('6g zero console/page errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  // 7 licence load fails: visible error, the toggle is absent, the number can still be edited
  state.licenceStatus = 500; state.calls = [];
  s = await launch();
  await seedClinic(s.page, ownerPerms, { owner: true, roleName: 'Owner' });
  await mockApi(s.page, s.log, staffHandlers);
  await s.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  await s.page.getByRole('button', { name: 'Add reg. no.' }).first().click();
  await s.page.getByText(/Couldn't load the clinician status/).waitFor({ timeout: 5000 }).then(() => check('7a load failure shown', true), () => check('7a load failure shown', false));
  check('7b no clinician toggle when status is unknown', await s.page.getByLabel(/Registered clinician/).count() === 0);
  await s.browser.close();

  // 8 permission absent: without staff:edit there is no reg. no. / licence control at all
  s = await launch();
  await seedClinic(s.page, ['staff:view'], {});
  await mockApi(s.page, s.log, staffHandlers);
  await s.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  await s.page.getByText('Dr Lina').first().waitFor({ timeout: 5000 }).catch(() => {});
  check('8a staff list rendered', await s.page.getByText('Dr Lina').count() > 0);
  check('8b no reg. no. / licence button without staff:edit', await s.page.getByRole('button', { name: /reg\. no\./i }).count() === 0);
  await s.browser.close();

  // 9 empty: no staff -> no dialog entry point, nothing crashes
  s = await launch();
  await seedClinic(s.page, ownerPerms, { owner: true });
  await mockApi(s.page, s.log, [['GET', /^\/staff\?limit=50$/, { body: { data: [], page: { nextCursor: null, limit: 50 } } }], ['GET', /./, { body: [] }]]);
  await s.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  check('9a empty staff list: no licence button', await s.page.getByRole('button', { name: /reg\. no\./i }).count() === 0);
  check('9b zero page errors', s.log.pageErrors.length === 0, s.log.pageErrors.join('|'));
  await s.browser.close();
} catch (e) {
  check('script ran to completion', false, String(e).slice(0, 300));
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
