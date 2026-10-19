// S11 CLN-006: signed note is read-only for everyone but the visit doctor / approver; Amend needs a 5..500 char reason.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3503 node s11-cln-006-note-signing.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const OTHER_DOC = '33333333-3333-3333-3333-333333333333';
const DOC = ['queue:view', 'queue:edit', 'patients:view', 'clinical:view', 'clinical:edit'];
const NURSE = ['queue:view', 'patients:view', 'clinical:view', 'clinical:edit'];
const APPROVER = [...NURSE, 'clinical:approve'];
const entry = (doctorId, status = 'in_consult') => ({ id: 'e1', appointmentId: null, patientId: 'p1', doctorId, departmentId: 'd1', queueDate: '2026-10-19', tokenNumber: 3, status, priority: false, priorityReason: null, createdAt: new Date().toISOString() });
const patient = { id: 'p1', mrn: 'M-1', name: 'Test Patient', phone: '', dob: '1990-01-01', gender: 'female', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null };
const note = (status) => ({ id: 'n1', queueEntryId: 'e1', patientId: 'p1', doctorId: SELF, subjective: 'S', objective: 'O', assessment: 'A', plan: 'P', diagnosis: 'D', status, signedAt: status === 'signed' ? new Date().toISOString() : null, updatedAt: new Date().toISOString(), signedBy: status === 'signed' ? SELF : null });
const ok = (body) => ({ status: 200, body });

async function open(perms, { doctorId = SELF, status = 'signed', amend = null, amendments = [], viewport } = {}) {
  const s = await launch(viewport ? { viewport } : {});
  await seedClinic(s.page, perms);
  s.amendments = [...amendments];
  await mockApi(s.page, s.log, [
    // Another doctor's signed visit is only reachable as a checkout_pending record ("Open visit record").
    ['GET', /^\/queue(\?|$)/, ok([entry(doctorId, doctorId !== SELF && status === 'signed' ? 'checkout_pending' : 'in_consult')])],
    ['GET', /^\/patients\/p1/, ok(patient)],
    ['GET', /^\/departments\/d1\/flow/, ok({ steps: [] })],
    ['GET', /^\/clinical\/notes\/e1\/amendments/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(s.amendments) })],
    ['POST', /^\/clinical\/notes\/e1\/amendments/, (r, req) => {
      s.posted = JSON.parse(req.postData());
      if (amend) return r.fulfill({ status: amend.status, contentType: 'application/problem+json', body: JSON.stringify(amend.body) });
      const a = { id: 'a' + (s.amendments.length + 1), amendedBy: SELF, reason: s.posted.reason, diagnosis: s.posted.diagnosis ?? null, createdAt: new Date().toISOString() };
      s.amendments.push(a);
      return r.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(a) });
    }],
    ['GET', /^\/clinical\/notes\/e1/, ok(note(status))],
    ['GET', /^\/clinical\/(vitals|prescriptions)\//, { status: 404, body: {} }],
    ['GET', /./, ok([])],
  ]);
  await s.page.goto(APP + '/consult?entry=e1&step=assess', { waitUntil: 'networkidle' });
  if (doctorId !== SELF) {
    const openRecord = s.page.getByRole('button', { name: 'Open visit record' });
    if (status === 'draft') return s; // an in-consult visit of another doctor never opens the workspace
    await openRecord.click({ timeout: 10000 });
    await s.page.getByRole('button', { name: 'Assess' }).first().click({ timeout: 5000 }).catch(() => {});
  }
  await s.page.locator('#assessment').waitFor({ timeout: 10000 });
  await sleep(500);
  return s;
}
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload|ERR_FAILED|net::|status of 40[34]/.test(x))];
const amendBtn = (p) => p.getByRole('button', { name: 'Amend note' });
const consoleErrors = [];

let s;
try {
  // Happy path: visit doctor amends a signed note with a valid reason.
  s = await open(DOC);
  check('H1 signed banner shown', await s.page.getByText('This note is finalised.').count() === 1);
  check('H2 SOAP fields read-only after sign', await s.page.locator('#assessment').isDisabled());
  check('E1 empty: no amendment history card when none exist', await s.page.getByText('Amendment history').count() === 0);
  check('H3 visit doctor sees "Amend note"', await amendBtn(s.page).count() === 1);
  await amendBtn(s.page).click();
  const save = s.page.getByRole('button', { name: 'Save amendment' });
  await s.page.locator('#amendReason').fill('four');
  check('B1 boundary: 4-char reason keeps Save disabled', await save.isDisabled());
  await s.page.locator('#amendReason').fill('Typo!');
  check('B2 boundary: 5-char reason enables Save', await save.isEnabled());
  check('B3 reason input capped at 500 chars', (await s.page.locator('#amendReason').getAttribute('maxlength')) === '500');
  await s.page.locator('#amendDiagnosis').fill('Influenza');
  await save.click();
  await s.page.getByText('Amendment history').waitFor({ timeout: 4000 });
  check('H4 POST /amendments carried reason and diagnosis', s.posted?.reason === 'Typo!' && s.posted?.diagnosis === 'Influenza', JSON.stringify(s.posted));
  check('H5 amendment listed after save', await s.page.getByText('Typo! (diagnosis: Influenza)').count() === 1);
  const idem = s.log.requests.find((r) => r.method === 'POST' && /amendments/.test(r.path));
  check('H6 amendment POST was sent', !!idem);
  consoleErrors.push(...errs(s.log));
  await s.browser.close();

  // Permission-absent: a nurse with clinical:edit on another doctor's visit sees the note read-only, no Amend.
  s = await open(NURSE, { doctorId: OTHER_DOC, amendments: [{ id: 'a1', amendedBy: SELF, reason: 'Earlier fix', diagnosis: null, createdAt: new Date().toISOString() }] });
  check('P1 nurse: signed banner shown', await s.page.getByText('This note is finalised.').count() === 1);
  check('P2 nurse: "Amend note" absent (not disabled)', await amendBtn(s.page).count() === 0);
  check('P3 nurse: amendment form absent', await s.page.locator('#amendReason').count() === 0);
  check('P4 nurse: SOAP fields read-only', await s.page.locator('#assessment').isDisabled());
  await s.page.getByText('Earlier fix').waitFor({ timeout: 4000 }).catch(() => {});
  check('P5 nurse: amendment history still readable', await s.page.getByText('Earlier fix').count() >= 1,
    s.log.requests.filter((r) => /clinical\/notes/.test(r.path)).map((r) => r.method + ' ' + r.path).join(', '));
  consoleErrors.push(...errs(s.log));
  await s.browser.close();

  // Permission-absent on a draft: the nurse gets no "Sign note and send to checkout" button.
  s = await open(NURSE, { doctorId: OTHER_DOC, status: 'draft' });
  await sleep(800);
  check('P6 nurse on draft: sign button absent', await s.page.getByRole('button', { name: 'Sign note and send to checkout' }).count() === 0);
  consoleErrors.push(...errs(s.log));
  await s.browser.close();
  s = await open(DOC, { status: 'draft' });
  await s.page.goto(APP + '/consult?entry=e1&step=close', { waitUntil: 'networkidle' });
  await s.page.getByRole('button', { name: 'Sign note and send to checkout' }).waitFor({ timeout: 6000 }).catch(() => {});
  check('P7 visit doctor on draft: sign button present', await s.page.getByRole('button', { name: 'Sign note and send to checkout' }).count() === 1);
  consoleErrors.push(...errs(s.log));
  await s.browser.close();

  // Approver (clinical:approve) on another doctor's visit may amend.
  s = await open(APPROVER, { doctorId: OTHER_DOC });
  check('P8 approver: "Amend note" present', await amendBtn(s.page).count() === 1);
  consoleErrors.push(...errs(s.log));
  await s.browser.close();

  // Error: server refuses the amendment -> its detail is shown, form stays open.
  s = await open(DOC, { amend: { status: 403, body: { type: 'not-prescriber', title: 'Visit doctor only', detail: 'Only the doctor on this visit can amend this note.' } } });
  await amendBtn(s.page).click();
  await s.page.locator('#amendReason').fill('Wrong dose');
  await s.page.getByRole('button', { name: 'Save amendment' }).click();
  const alert = s.page.getByRole('alert').filter({ hasText: 'Only the doctor on this visit can amend this note.' });
  await alert.waitFor({ timeout: 4000 }).catch(() => {});
  check('X1 error: server detail shown as alert', await alert.count() === 1);
  check('X2 error: form kept open with the typed reason', (await s.page.locator('#amendReason').inputValue()) === 'Wrong dose');
  consoleErrors.push(...errs(s.log));
  await s.browser.close();

  // 390px: no horizontal page scroll with the amendment form open.
  s = await open(DOC, { viewport: { width: 390, height: 844 } });
  await amendBtn(s.page).click();
  await sleep(300);
  const overflow = await s.page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check('R1 390px: no horizontal scroll', overflow <= 0, `overflow=${overflow}px`);
  consoleErrors.push(...errs(s.log));
  await s.browser.close();
  s = null;

  check('C1 zero console errors across all runs', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));
} catch (e) {
  check('script ran to completion', false, String(e).split('\n').slice(0, 3).join(' / '));
  if (s) { dumpLog('last', s.log); console.log('  url:', s.page.url()); }
  await s?.browser.close().catch(() => {});
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
