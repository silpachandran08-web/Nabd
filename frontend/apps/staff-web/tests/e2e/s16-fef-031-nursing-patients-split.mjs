// FEF-031: nursing worklist + vitals capture (J8) and the patient drawer after the split into feature folders.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3810 node tests/e2e/s16-fef-031-nursing-patients-split.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3810';
const DOC = '33333333-3333-3333-3333-333333333331';
const json = (route, body, status = 200) => route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const clean = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|ERR_ABORTED/.test(x))];
const entry = (n, status) => ({ id: `q${n}`, appointmentId: null, patientId: `p${n}`, doctorId: DOC, departmentId: 'd1', queueDate: '2026-10-20', tokenNumber: n,
  status, priority: false, priorityReason: null, createdAt: new Date().toISOString(), source: 'walk_in' });
const worklist = Array.from({ length: 40 }, (_, i) => ({
  entry: entry(i + 1, i < 30 ? 'vitals_pending' : 'vitals_done'),
  patient: { name: `Patient ${i + 1}`, mrn: `M${i + 1}`, dob: '1980-01-01', gender: 'f', allergies: [], chronicConditions: [] },
  vitals: i < 30 ? null : { recordedAt: new Date().toISOString(), abnormalFlags: [] },
}));

// ---- Nursing: J8 capture, implausible rejected inline, saved < 60 s
{
  const { page, browser, log } = await launch();
  await seedClinic(page, ['queue:view', 'nursing:view', 'clinical:view', 'clinical:create']);
  let patched = null;
  await mockApi(page, log, [
    ['GET', /^\/nursing\/worklist$/, { body: { items: worklist } }],
    ['GET', /^\/staff\/roster/, { body: [{ id: DOC, name: 'Dr Amal Nasser' }] }],
    ['GET', /^\/clinical\/vitals\/patient\//, { body: { items: [] } }],
    ['PATCH', /^\/clinical\/vitals\/q1$/, (route, req) => { patched = JSON.parse(req.postData() ?? '{}'); return json(route, { id: 'v1', queueEntryId: 'q1', abnormalFlags: [] }); }],
    ['GET', /./, { body: [] }],
  ]);
  await page.goto(APP + '/nursing');
  await page.getByText('Patient 1', { exact: true }).first().waitFor();
  check('N1 40-row worklist renders in one worklist call', log.requests.filter((r) => r.path === '/nursing/worklist').length === 1);
  const t0 = Date.now();
  await page.locator('td button', { hasText: 'Capture vitals' }).first().click();
  const dlg = page.getByRole('dialog');
  await dlg.waitFor();
  check('N2 capture dialog names the patient', /Patient 1/.test(await dlg.innerText()));
  await dlg.getByLabel(/^Pulse/).fill('900');
  await dlg.getByRole('button', { name: /Save and mark ready/ }).click();
  check('N3 implausible pulse rejected inline, nothing sent', (await dlg.locator('[role=alert]').allInnerTexts()).join(' ').length > 0 && patched === null);
  await dlg.getByLabel(/^Pulse/).fill('72');
  await dlg.getByLabel(/^Temp/).fill('36.8');
  await dlg.getByRole('button', { name: /Save and mark ready/ }).click();
  await dlg.waitFor({ state: 'detached' });
  check('N4 valid vitals saved (PATCH body has pulse 72) in < 60 s', patched?.pulseBpm === 72 && Date.now() - t0 < 60000, `${Date.now() - t0} ms`);
  for (const tab of ['priority', 'administration', 'procedures', 'activity']) {
    await page.goto(APP + '/nursing?tab=' + tab);
    await sleep(500);
    check(`N5 tab ${tab} renders without error`, await page.locator('main').first().isVisible() && !(await page.getByText(/Something went wrong|failed/i).count()));
  }
  check('N6 zero console/page errors', clean(log).length === 0, clean(log).slice(0, 2).join(' | '));
  dumpLog('nursing', log);
  await browser.close();
}

// ---- Nursing: permission absent + 390 px
{
  const { page, browser, log } = await launch({ viewport: { width: 390, height: 800 } });
  await seedClinic(page, ['queue:view', 'nursing:view']);
  await mockApi(page, log, [['GET', /^\/nursing\/worklist$/, { body: { items: worklist } }], ['GET', /^\/staff\/roster/, { body: [] }], ['GET', /./, { body: [] }]]);
  await page.goto(APP + '/nursing');
  await page.getByText('Patient 1', { exact: true }).first().waitFor();
  check('N7 without clinical:create the Capture vitals control is absent', (await page.getByRole('button', { name: /Capture vitals/ }).count()) === 0);
  check('N8 390 px: no horizontal scroll', await noOverflow(page));
  await browser.close();
}

// ---- Patients: registry -> drawer, sections load and fail independently
const row = { id: 'p1', name: 'Asha Verma', mrn: 'M1', phone: '+91 98765 43210', age: 41, gender: 'female', lastVisit: null, queueToken: null, archived: false };
const detail = { id: 'p1', name: 'Asha Verma', mrn: 'M1', dob: '1985-01-01', gender: 'female', phone: '+91 98765 43210', allergies: [], activePackages: 0 };
function patientHandlers(extra = []) {
  return [
    ...extra,
    ['GET', /^\/patients\/registry/, { body: { rows: [row], counts: { all: 1, archived: 0 }, page: { nextCursor: null } } }],
    ['GET', /^\/patients\/guardian-reviews/, { body: [] }],
    ['GET', /^\/patients\/p1$/, { body: detail }],
    ['GET', /^\/clinical\/patients\/p1\/conditions/, { body: [{ id: 'c1', condition: 'Asthma', status: 'active', reviewDueDate: null }] }],
    ['GET', /^\/clinical\/patients\/p1\/prescriptions/, { body: [] }],
    ['GET', /^\/clinical\/patients\/p1\/timeline/, { body: { data: [], page: { nextCursor: null } } }],
    ['GET', /./, { body: [] }],
  ];
}
{
  const { page, browser, log } = await launch();
  await seedClinic(page, ['patients:view', 'clinical:view', 'clinical:edit', 'queue:view']);
  await mockApi(page, log, patientHandlers([
    ['GET', /^\/clinical\/patients\/p1\/allergies/, (route) => json(route, { title: 'boom' }, 500)],
  ]));
  await page.goto(APP + '/patients');
  await page.getByText('Asha Verma').first().click();
  const dlg = page.getByRole('dialog');
  await dlg.getByText('Chronic conditions').waitFor();
  await dlg.getByText('Asthma').waitFor();
  const txt = await dlg.innerText();
  check('P1 allergy register failed on its own: its error shown', /Couldn.t load the allergy register/.test(txt));
  check('P2 conditions still loaded next to the failed register', /Asthma/.test(txt));
  check('P3 medicines + timeline sections show their own empty states', /None recorded/.test(txt) && /No completed visits yet/.test(txt));
  check('P4 the banner does not claim "no known allergies" for a failed register', !/No known allergies/.test(txt) && /couldn.t be confirmed/.test(txt));
  check('P5 each section made its own request', ['conditions', 'prescriptions', 'timeline', 'allergies'].every((s) => log.requests.some((r) => r.path.includes('/clinical/patients/p1/' + s))));
  check('P6 zero console/page errors', clean(log).length === 0, clean(log).slice(0, 2).join(' | '));
  dumpLog('patients', log);
  await browser.close();
}
{
  // a slow timeline does not hold the other sections back
  const { page, browser, log } = await launch();
  await seedClinic(page, ['patients:view', 'clinical:view']);
  await mockApi(page, log, patientHandlers([
    ['GET', /^\/clinical\/patients\/p1\/allergies\/status/, { body: { state: 'none_documented', documentedByName: 'Dr A' } }],
    ['GET', /^\/clinical\/patients\/p1\/allergies/, { body: [] }],
    ['GET', /^\/clinical\/patients\/p1\/timeline/, { delay: 2500, body: { data: [], page: { nextCursor: null } } }],
  ]));
  await page.goto(APP + '/patients');
  await page.getByText('Asha Verma').first().click();
  const dlg = page.getByRole('dialog');
  await dlg.getByText('Asthma').waitFor();
  check('P7 conditions visible while the timeline is still loading', (await dlg.getByLabel('Loading timeline').count()) === 1);
  await dlg.getByText('No completed visits yet').waitFor({ timeout: 8000 });
  check('P8 documented-none banner shown', /No known allergies/.test(await dlg.innerText()));
  await browser.close();
}
{
  // permission absent: no clinical:view -> no clinical requests, honest message; 390 px
  const { page, browser, log } = await launch({ viewport: { width: 390, height: 800 } });
  await seedClinic(page, ['patients:view']);
  await mockApi(page, log, patientHandlers());
  await page.goto(APP + '/patients');
  await page.getByText('Asha Verma').first().click();
  const dlg = page.getByRole('dialog');
  await dlg.getByText(/Clinical records aren.t available/).waitFor();
  check('P9 without clinical:view no clinical request is made', !log.requests.some((r) => r.path.startsWith('/clinical/')));
  check('P10 390 px: no horizontal scroll', await noOverflow(page));
  await browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
