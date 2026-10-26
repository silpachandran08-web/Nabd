// S15 FEF-030: the split consult workspace keeps behaving: panel-level error boundary (a failing Rx panel leaves the patient banner and the
// note editor working, the IndexedDB draft intact), deep links ?entry=&step=, sign + send to checkout, 390px, zero console errors.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3806 node s15-fef-030-consult-panels.mjs
import { launch, seedClinic, mockApi, check, results, sleep, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const PERMS = ['queue:view', 'queue:edit', 'patients:view', 'clinical:view', 'clinical:edit'];
const entry = { id: 'e1', appointmentId: null, patientId: 'p1', doctorId: SELF, departmentId: 'd1', queueDate: '2026-10-19', tokenNumber: 3, status: 'in_consult', priority: false, priorityReason: null, createdAt: new Date().toISOString() };
const patient = { id: 'p1', mrn: 'M-1', name: 'Test Patient', phone: '', dob: '1990-01-01', gender: 'female', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null };
const note = (o = {}) => ({ id: 'n1', queueEntryId: 'e1', patientId: 'p1', doctorId: SELF, subjective: 'S0', objective: 'O', assessment: 'A', plan: 'P', diagnosis: 'Flu', status: 'draft', signedAt: null, updatedAt: '2026-10-19T08:00:00Z', signedBy: null, ...o });
const rxOk = { id: 'r1', status: 'draft', signedAt: null, items: [{ drugName: 'Paracetamol', dosage: '500 mg', frequency: 'TDS', duration: '3 days', instructions: '', warnings: [] }] };
// A malformed warnings value (an object where the API promises a list) makes the Rx panel throw while rendering.
const rxBad = { ...rxOk, items: [{ ...rxOk.items[0], warnings: { broken: true } }] };
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload|ERR_FAILED|net::|status of 40[34]|The above error occurred|Error boundary|React will try to recreate|\.filter is not a function|An error occurred in the/.test(x))];
const consoleErrors = [];
const until = async (fn, ms = 6000) => { const t = Date.now(); while (Date.now() - t < ms) { if (await fn()) return true; await sleep(150); } return false; };
const drafts = (p) => p.evaluate(() => new Promise((res) => {
  const q = indexedDB.open('nabd-consult-drafts', 1);
  q.onupgradeneeded = () => q.result.createObjectStore('drafts', { keyPath: 'entryId' });
  q.onsuccess = () => { const c = q.result.transaction('drafts').objectStore('drafts').count(); c.onsuccess = () => { q.result.close(); res(c.result); }; };
  q.onerror = () => res(-1);
}));

async function open(url, { rx = rxOk, viewport, status = 'in_consult', reports = [] } = {}) {
  const s = await launch(viewport ? { viewport } : {});
  await seedClinic(s.page, PERMS);
  s.patches = []; s.calls = [];
  await mockApi(s.page, s.log, [
    ['GET', /^\/queue(\?|$)/, { status: 200, body: [{ ...entry, status }] }],
    ['GET', /^\/patients\/p1/, { status: 200, body: patient }],
    ['GET', /^\/departments\/d1\/flow/, { status: 200, body: { steps: [] } }],
    ['PATCH', /^\/clinical\/notes\/e1$/, (r, req) => { s.patches.push(JSON.parse(req.postData() || '{}')); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(note({ ...JSON.parse(req.postData() || '{}'), updatedAt: '2026-10-19T08:05:00Z' })) }); }],
    ['POST', /^\/clinical\/notes\/e1\/sign$/, (r) => { s.calls.push('sign'); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(note({ status: 'signed', signedAt: '2026-10-19T09:00:00Z' })) }); }],
    ['PATCH', /^\/queue\/e1\/status$/, (r, req) => { s.calls.push('status:' + JSON.parse(req.postData() || '{}').status); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...entry, status: 'checkout_pending' }) }); }],
    ['GET', /^\/clinical\/notes\/e1$/, { status: 200, body: note() }],
    ['GET', /^\/clinical\/prescriptions\/e1\/signing-authority$/, { status: 200, body: { canSign: true, reason: null } }],
    ['GET', /^\/clinical\/prescriptions\/e1$/, { status: 200, body: rx }],
    ['GET', /^\/clinical\/(vitals|prescriptions)\//, { status: 404, body: {} }],
    ['GET', /./, { status: 200, body: [] }],
  ]);
  await s.page.route(/\/v1\/client-telemetry$/, (r) => { reports.push(JSON.parse(r.request().postData() || '{}')); return r.fulfill({ status: 202, body: '' }); });
  await s.page.goto(APP + url, { waitUntil: 'networkidle' });
  return s;
}
let s;
try {
  // 1. Healthy workspace, deep link into the Plan step.
  s = await open('/consult?entry=e1&step=plan');
  await s.page.locator('#plan').waitFor({ timeout: 10000 });
  check('D1 deep link opens the Plan step', await s.page.getByRole('button', { name: /4\. Plan/ }).getAttribute('aria-current') === 'step');
  check('D2 prescription card shows the saved line', await s.page.getByText('Paracetamol').count() >= 1);
  await s.page.getByRole('button', { name: /2\. Examine/ }).click();
  check('D3 URL mirrors the step', new URL(s.page.url()).searchParams.get('step') === 'examine' && new URL(s.page.url()).searchParams.get('entry') === 'e1');
  await s.page.getByRole('button', { name: 'Queue', exact: true }).click();
  await s.page.getByRole('heading', { name: 'Consultation Workspace' }).waitFor({ timeout: 5000 });
  check('D4 back to the queue strips ?entry= and ?step=', !new URL(s.page.url()).searchParams.has('entry') && !new URL(s.page.url()).searchParams.has('step'), s.page.url());
  consoleErrors.push(...errs(s.log)); await s.browser.close();

  // 2. Sign the note and send to checkout (the J5 path).
  s = await open('/consult?entry=e1&step=close', { rx: { ...rxOk, status: 'signed', signedAt: '2026-10-19T08:30:00Z' } }); // an unsigned Rx would block the button until acknowledged
  const sign = s.page.getByRole('button', { name: 'Sign note and send to checkout' });
  await sign.waitFor({ timeout: 10000 });
  await sign.click();
  check('S1 sign then queue move to checkout_pending', await until(() => s.calls.join(',') === 'sign,status:checkout_pending'), s.calls.join(','));
  check('S2 "Sent to checkout." shown', await until(async () => (await s.page.getByText('Sent to checkout.').count()) > 0));
  consoleErrors.push(...errs(s.log)); await s.browser.close();

  // 3. A throwing Rx panel: the panel fails alone.
  const reports = [];
  s = await open('/consult?entry=e1&step=plan', { rx: rxBad, reports });
  await s.page.getByTestId('panel-error-rx').waitFor({ timeout: 10000 });
  check('P1 Rx panel shows "This panel failed" with Retry', await s.page.getByTestId('panel-error-rx').getByText('This panel failed').count() === 1 && await s.page.getByTestId('panel-error-rx').getByRole('button', { name: 'Retry' }).count() === 1);
  check('P2 patient banner still visible', await s.page.getByTestId('patient-banner').getByText('Test Patient').count() === 1);
  check('P3 note editor still works: type in the plan field', await (async () => { await s.page.locator('#plan').fill('new plan'); return (await s.page.locator('#plan').inputValue()) === 'new plan'; })());
  check('P4 local draft written for the typed text (IndexedDB intact)', await until(async () => (await drafts(s.page)) === 1, 4000));
  await s.page.getByRole('button', { name: 'Save draft' }).click();
  check('P5 note saves while the Rx panel is down (PATCH sent)', await until(() => s.patches.length === 1) && s.patches[0].plan === 'new plan');
  check('P6 other panels render (step rail, timeline, summary)', await s.page.getByRole('navigation', { name: 'Consultation steps' }).count() === 1 && await s.page.getByLabel('Prescription summary').count() === 1);
  await s.page.getByTestId('panel-error-rx').getByRole('button', { name: 'Retry' }).click();
  await sleep(300);
  check('P7 Retry re-renders; the same bad data fails again without a crash loop', await s.page.getByTestId('panel-error-rx').count() === 1 && await s.page.getByTestId('patient-banner').count() === 1);
  await sleep(400);
  const rep = reports.find((r) => /^boundary\.consultrx\./.test(r.metric));
  check('P8 telemetry: panel, error class, route template, release; no patient data', !!rep && rep.route === '/consult' && typeof rep.release === 'string' && rep.message === '' && !/Test Patient|M-1|Paracetamol/.test(JSON.stringify(rep)), JSON.stringify(rep));
  consoleErrors.push(...errs(s.log)); await s.browser.close();

  // 4. 390px: no horizontal page scroll on the queue and on the workspace.
  s = await open('/consult', { viewport: { width: 390, height: 844 } });
  await s.page.getByText('Test Patient').first().waitFor({ timeout: 10000 });
  let over = await s.page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check('R1 390px queue: no horizontal scroll', over <= 0, `overflow=${over}`);
  await s.page.goto(APP + '/consult?entry=e1&step=plan', { waitUntil: 'networkidle' });
  await s.page.locator('#plan').waitFor({ timeout: 10000 });
  over = await s.page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check('R2 390px workspace: no horizontal scroll', over <= 0, `overflow=${over}`);
  consoleErrors.push(...errs(s.log)); await s.browser.close();
  s = null;
} catch (e) {
  check('script ran to completion', false, String(e).split('\n').slice(0, 3).join(' / '));
  await s?.browser.close().catch(() => {});
}
check('C1 zero unexpected console errors', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
