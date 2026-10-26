// S15 IDC-004 / IDC-007 / IDC-008 (UI): itemised consent under the registration form, assisted consent, child has no marketing toggle.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3817 node tests/e2e/s15-idc-004-consent-capture.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
const registry = { body: { total: 0, counts: { all: 0 }, rows: [], matchedTotal: 0, hasMore: false } };
const STATE = { purposes: {}, needsReack: false, processable: false, needsConsent: true, emergencyTreatmentBasis: false, notices: { en: '1', hi: '1' } };
const PERMS = ['patients:view', 'patients:create', 'patients:edit'];

async function open(perms, consentPost, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, [
    ['POST', /^\/patients$/, { status: 201, body: { id: 'p1', mrn: 'M1' } }],
    ['GET', /^\/patients\/p1\/consents$/, { body: STATE }],
    ['POST', /^\/patients\/p1\/consents$/, consentPost ?? { status: 201, body: { consents: [] } }],
    ['GET', /^\/staff\/roster/, { body: [{ id: 'w2', name: 'Dr Second', departmentName: 'GP' }] }],
    ['GET', /^\/patients\/registry/, registry], ['GET', /^\/patients\/guardian-reviews-due/, { body: [] }],
    ['GET', /^\/patients\/p1(\?.*)?$/, { status: 404, body: { type: 'not-found', title: 'x' } }],
    ['GET', /^\/patients\?/, { body: { data: [], page: { nextCursor: null, limit: 6 } } }],
  ]);
  await s.page.goto(`${APP}/patients`); await sleep(1200);
  await s.page.getByRole('button', { name: /Register patient/ }).first().click(); await sleep(400);
  return s;
}
const fill = async (page, dob = '1985-05-05') => {
  await page.getByLabel('Full name').fill('Test Patient');
  await page.getByLabel('Mobile').fill('+919876543210');
  await page.getByLabel('Date of birth').fill(dob);
  await page.getByLabel('Sex').selectOption('female');
};
const posts = (log, re) => log.requests.filter((r) => r.method === 'POST' && re.test(r.path));
let s;
try {
  // 1 happy path: processing + WhatsApp -> one POST /consents after registration, DESK, current notice version
  s = await open(PERMS);
  await fill(s.page);
  check('C1 consent block present with three toggles, optional ones off', (await s.page.getByRole('group', { name: /Consent/ }).getByRole('checkbox').count()) === 3
    && !(await s.page.getByLabel(/offers and news/).isChecked()));
  check('C2 optional purposes disabled until processing is ticked', await s.page.getByLabel(/WhatsApp/).isDisabled());
  await s.page.getByLabel(/Keep my health record/).check();
  await s.page.getByLabel(/WhatsApp/).check();
  const t0 = Date.now();
  await s.page.getByRole('button', { name: 'Register patient', exact: true }).last().click(); await sleep(800);
  const cp = posts(s.log, /^\/patients\/p1\/consents$/);
  const body = cp[0] ? JSON.parse(cp[0].body) : {};
  check('C3 one consent POST after the patient POST', cp.length === 1 && s.log.requests.findIndex((r) => r.path === '/patients') < s.log.requests.findIndex((r) => r.path === '/patients/p1/consents'), JSON.stringify(s.log.requests.map((r) => r.method + r.path)));
  check('C4 body: processing+messaging granted, marketing not, notice v1, DESK', body.noticeVersion === '1' && body.channel === 'DESK' && body.decisions?.find((d) => d.purpose === 'processing').granted === true
    && body.decisions.find((d) => d.purpose === 'messaging').granted === true && body.decisions.find((d) => d.purpose === 'marketing').granted === false, cp[0]?.body);
  check('C5 registration + consent click-to-done well under 20 s', Date.now() - t0 < 20000, `${Date.now() - t0} ms`);
  check('C6 zero console errors', noise(s.log).length === 0, JSON.stringify(noise(s.log)));
  dumpLog('c', s.log); await s.browser.close(); s = null;

  // 2 permission absent: patients:create without patients:edit -> no consent block, no consent request
  s = await open(['patients:view', 'patients:create']);
  await fill(s.page);
  check('P1 no consent block without patients:edit', (await s.page.getByRole('group', { name: /Consent/ }).count()) === 0);
  await s.page.getByRole('button', { name: 'Register patient', exact: true }).last().click(); await sleep(600);
  check('P2 no consent request made', posts(s.log, /consents/).length === 0);
  await s.browser.close(); s = null;

  // 3 a child: no marketing toggle (IDC-008)
  s = await open(PERMS);
  await fill(s.page, new Date(Date.now() - 6 * 365 * 864e5).toISOString().slice(0, 10)); await sleep(300);
  check('K1 a child is not offered the marketing toggle', (await s.page.getByLabel(/offers and news/).count()) === 0 && (await s.page.getByRole('group', { name: /Consent/ }).count()) === 1);
  await s.browser.close(); s = null;

  // 4 assisted: language, explainer states, channel ASSISTED + language hi in the request
  s = await open(PERMS, undefined, { width: 390, height: 844 });
  await fill(s.page);
  await s.page.getByRole('button', { name: 'Assisted consent' }).click(); await sleep(500);
  const dlg = s.page.getByRole('dialog', { name: 'Assisted consent' });
  check('A1 English explainer text shown large', /health record/.test(await dlg.innerText()));
  await dlg.getByRole('button', { name: 'Hindi' }).click(); await sleep(500);
  check('A2 a language without a recorded explainer says so (no invented content)', /not available yet/.test(await dlg.innerText()));
  check('A3 witness picker offered from the roster', (await dlg.getByLabel(/Second witness/).count()) === 1);
  await dlg.getByLabel(/Second witness/).selectOption('w2');
  await dlg.getByLabel(/Keep my health record/).check();
  check('A4 390px no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  await dlg.getByRole('button', { name: 'Use these answers' }).click(); await sleep(300);
  await s.page.getByRole('button', { name: 'Register patient', exact: true }).last().click(); await sleep(800);
  const ab = JSON.parse(posts(s.log, /consents/)[0]?.body ?? '{}');
  check('A5 request is ASSISTED, hi, with the witness', ab.channel === 'ASSISTED' && ab.language === 'hi' && ab.witnessStaffIds?.[0] === 'w2', JSON.stringify(ab));
  await s.browser.close(); s = null;

  // 5 error: the consent POST is refused -> message shown, dialog stays, retry sends only the consent
  s = await open(PERMS, { status: 422, body: { type: 'https://x/problems/second-witness-required', title: 'Second witness required', detail: 'x' } });
  await fill(s.page);
  await s.page.getByLabel(/Keep my health record/).check();
  await s.page.getByRole('button', { name: 'Register patient', exact: true }).last().click(); await sleep(800);
  check('X1 refusal text shown and the dialog stays open', (await s.page.getByRole('dialog', { name: 'Register patient' }).count()) === 1 && /second staff member/.test(await s.page.getByRole('dialog').innerText()));
  await s.page.getByRole('button', { name: 'Register patient', exact: true }).last().click(); await sleep(800);
  check('X2 retry does not register a second patient', posts(s.log, /^\/patients$/).length === 1 && posts(s.log, /consents/).length === 2, JSON.stringify(s.log.requests.map((r) => r.method + r.path)));
  await s.browser.close(); s = null;
} catch (e) {
  console.log('SCRIPT ERROR', e);
  results.push({ id: 'script', ok: false });
} finally {
  if (s) await s.browser.close();
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
