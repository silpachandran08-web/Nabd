// S11 IDC-001: a gated clinical read (404 patient-not-found: consent withdrawn / outside own-patients scope) shows
// 'Record not available' with no patient details and no retry storm (mocked API).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3510 node s11-idc-001-record-unavailable.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const ENTRY = 'e1', PAT = 'p1';
const entry = { id: ENTRY, appointmentId: null, patientId: PAT, doctorId: SELF, departmentId: 'd1', queueDate: '2026-10-18', tokenNumber: 3, status: 'in_consult', priority: false, priorityReason: null, createdAt: new Date().toISOString() };
const patient = { id: PAT, mrn: 'M-77', name: 'Fatima Hidden', phone: '', dob: '1998-03-04', gender: 'female', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null };
const gated = { status: 404, body: { type: 'https://api.nabd.health/errors/patient-not-found', title: 'Record not available', detail: 'The requested patient record was not found.' } };
const plain404 = { status: 404, body: { type: 'https://api.nabd.health/errors/not-found', title: 'Not found', detail: 'The requested resource was not found.' } };

// mode: 'gated' (whole patient gated) | 'ok' (no note yet) | 'note500' | 'condGated' (one clinical part gated, note fine)
function handlers(mode) {
  const g = mode === 'gated';
  return [
    ['GET', /^\/queue(\?|$)/, { body: [entry] }],
    ['GET', /^\/patients\/p1/, g ? gated : { body: patient }],
    ['GET', /^\/departments\/d1\/flow/, { body: { steps: [] } }],
    ['GET', /^\/clinical\/notes\/e1$/, g ? gated : mode === 'note500' ? { status: 500, body: { type: 'internal', title: 'Server error' } } : plain404],
    ['GET', /^\/clinical\/patients\/p1\/conditions/, g || mode === 'condGated' ? gated : { body: [] }],
    ['GET', /^\/clinical\/patients\/p1\/pregnancy-status$/, g ? gated : { body: { status: 'not_documented', expectedDeliveryDate: null, recordedByName: null, recordedAt: null } }],
    ['GET', /^\/clinical\/patients\/p1\/allergies\/status$/, g ? gated : { body: { state: 'not_assessed', activeCount: 0, documentedByName: null, documentedAt: null } }],
    ['GET', /^\/clinical\/patients\/p1\/allergies$/, g ? gated : { body: [] }],
    ['GET', /^\/clinical\/patients\/p1\/timeline/, g ? gated : { body: { data: [] } }],
    ['GET', /^\/clinical\/patients\/p1\/prescriptions/, g ? gated : { body: [] }],
    ['GET', /^\/clinical\/vitals\//, g ? gated : plain404],
    ['GET', /^\/clinical\/prescriptions\//, g ? gated : plain404],
    ['GET', /./, { body: [] }],
  ];
}
const PERMS = ['queue:view', 'queue:edit', 'patients:view', 'clinical:view', 'clinical:edit'];
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload|ERR_FAILED|net::/.test(x))];
const count = (log, re) => log.requests.filter((r) => r.method === 'GET' && re.test(r.path)).length;

async function open(mode, viewport = { width: 1280, height: 900 }, perms = PERMS) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, handlers(mode));
  await s.page.goto(`${APP}/consult?entry=${ENTRY}&step=reason`, { waitUntil: 'networkidle' });
  return s;
}

try {
  // 1 gated patient at 390px: message, no PHI, no clinical editing, no retry storm
  let s = await open('gated', { width: 390, height: 800 });
  const msg = s.page.getByRole('alert').filter({ hasText: 'Record not available' });
  await msg.waitFor({ timeout: 8000 }).then(() => check('1a "Record not available" shown', true), () => check('1a "Record not available" shown', false));
  await sleep(3000); // a retry loop would show up as repeated reads in this window
  const text = await s.page.locator('main').innerText();
  check('1b no patient name, MRN or token on screen', !/Fatima|Hidden|M-77|Token #3/.test(text), JSON.stringify(text.slice(0, 200)));
  check('1c note read exactly once (no retry storm)', count(s.log, /^\/clinical\/notes\/e1$/) === 1, String(count(s.log, /^\/clinical\/notes\/e1$/)));
  check('1d each clinical part read at most once', ['timeline', 'allergies$', 'conditions', 'prescriptions$'].every((k) => count(s.log, new RegExp(`^/clinical/patients/p1/${k}`)) <= 1));
  check('1e prescription pad not loaded after the gate', count(s.log, /^\/clinical\/prescriptions\/e1/) === 0);
  check('1f no sign / save controls rendered', (await s.page.getByRole('button', { name: /Sign note|Save/ }).count()) === 0);
  check('1g Queue (back) control present', (await s.page.getByRole('button', { name: 'Queue' }).count()) === 1);
  check('1h 390px no horizontal scroll', await noOverflow(s.page));
  check('1i zero console/page errors', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('gated', s.log); await s.browser.close();

  // 2 happy path: a plain 404 on the note means "no note yet", the normal consult opens
  s = await open('ok');
  await s.page.getByLabel('Clinical safety').waitFor({ timeout: 8000 }).then(() => check('2a normal consult shell opens', true), () => check('2a normal consult shell opens', false));
  check('2b no "Record not available"', (await s.page.getByText('Record not available').count()) === 0);
  check('2c patient name shown for a visible patient', (await s.page.getByText('Fatima Hidden').count()) > 0);
  check('2d zero console/page errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  // 3 error: a 500 on the note is the existing load error, not the gated message
  s = await open('note500');
  await s.page.getByText(/Couldn.t load the note/).waitFor({ timeout: 8000 }).then(() => check('3a note load error shown', true), () => check('3a note load error shown', false));
  check('3b no "Record not available" on a server error', (await s.page.getByText('Record not available').count()) === 0);
  await s.browser.close();

  // 4 boundary: one clinical part gated while the note loads -> shown as "couldn't load", never as "None recorded"
  s = await open('condGated');
  const strip = s.page.getByLabel('Clinical safety');
  await strip.waitFor({ timeout: 8000 });
  await sleep(500);
  const st = await strip.innerText();
  check('4a gated conditions read shows "Couldn\'t load conditions"', /Couldn.t load conditions/.test(st), JSON.stringify(st.slice(0, 200)));
  check('4b and the chronic row is not the reassuring "None recorded"', !/CHRONIC\s*None recorded/i.test(st));
  await s.browser.close();

  // 5 permission-absent: without patients:view the patient lookup is never made, gated shell still shows the message only
  s = await open('gated', { width: 1280, height: 900 }, ['queue:view', 'clinical:view']);
  await s.page.getByText('Record not available').waitFor({ timeout: 8000 }).then(() => check('5a gated message without patients:view', true), () => check('5a gated message without patients:view', false));
  check('5b no /patients/p1 read without patients:view', count(s.log, /^\/patients\/p1/) === 0);
  check('5c no sign controls (clinical:edit absent)', (await s.page.getByRole('button', { name: /Sign note/ }).count()) === 0);
  await s.browser.close();
} catch (e) {
  check('script ran to completion', false, String(e).slice(0, 300));
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
