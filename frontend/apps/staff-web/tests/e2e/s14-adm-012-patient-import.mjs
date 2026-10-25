// S14 ADM-012: owner patient import (upload, dry-run tiles, mapping, duplicate drawer, commit, progress). Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3806 node s14-adm-012-patient-import.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';
const HEADERS = ['Patient Name', 'Mobile No', 'DOB', 'Gender', 'UHID', 'Allergy'];
const COLS = [['name', 'Patient Name'], ['mobile', 'Mobile No'], ['dob', 'DOB'], ['sex', 'Gender'], ['oldRecordNo', 'UHID'], ['allergies', 'Allergy']].map(([target, source]) => ({ source, target }));
const dryRun = (diff, columns = COLS) => ({ id: 'j1', importType: 'patients', fileName: 'p.csv', status: 'dry_run', errorMessage: null, rowsTotal: diff.total, rowsDone: 0, diff, mapping: { headers: HEADERS, columns, dateFormat: 'dd/MM/yyyy' } });
const FILE = { name: 'patients.csv', mimeType: 'text/csv', buffer: Buffer.from('Patient Name,Mobile No,DOB,Gender\nA,9876500001,01/01/1990,M\n') };
const base = (extra = []) => [
  ...extra,
  ['GET', /^\/setup\/import-jobs$/, { body: [] }],
  ['GET', /^\/setup\/export-jobs$/, { body: [] }],
  ['GET', /^\/setup\/(checklist|charges|holidays|shifts|licences)/, { body: [] }],
  ['GET', /^\/setup\/subscription/, { body: { planName: 'x', branchesUsed: 1, branchesLimit: 1, seatsUsed: 1, seatsLimit: 1 } }],
];
async function run(tag, perms, handlers, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms); await mockApi(page, log, handlers);
    await page.goto(APP + '/setup?tab=data'); await sleep(1500); await fn({ page, log });
    check(`${tag} zero console/page errors`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0, JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const edit = ['setup:view', 'setup:edit'];
const btn = (page, name) => page.getByRole('button', { name, exact: true });
const bodyText = (page) => page.locator('main').innerText();

// happy path: validating -> dry run -> duplicates drawer -> commit -> progress -> done
let phase = 'validating', imp = 0, commitBody = null;
await run('happy', edit, base([
  ['POST', /^\/setup\/import-uploads\?fileName=patients\.csv&format=csv$/, { status: 201, body: { uploadId: 'u1', fileName: 'patients.csv', sourceFormat: 'csv', sizeBytes: 60 } }],
  ['POST', /^\/setup\/import-jobs$/, { status: 201, body: { id: 'j1', status: 'validating' } }],
  ['GET', /^\/setup\/import-jobs\/j1$/, (route) => { let b; if (phase === 'validating') { phase = 'dry'; b = { id: 'j1', status: 'validating' }; } else if (phase === 'dry') b = dryRun({ ready: 3, duplicates: 2, errors: 1, total: 6 }); else { imp++; b = imp < 2 ? { id: 'j1', status: 'importing', rowsTotal: 5, rowsDone: 2 } : { id: 'j1', status: 'done', diff: { created: 4, merged: 1, skipped: 0, errors: 1 } }; } return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(b) }); }],
  ['GET', /^\/setup\/import-jobs\/j1\/duplicates/, { body: { items: [{ row: 3, reason: 'DUP_REGISTRY', name: 'Asha Rao', mobileEnding: '0001', dob: '1990-01-01', decision: null }, { row: 5, reason: 'DUP_FILE', name: 'Ravi K', mobileEnding: '0002', dob: '1985-08-15', decision: null }], nextAfterRow: null } }],
  ['GET', /^\/setup\/import-jobs\/j1\/errors\.csv$/, { body: undefined }],
  ['POST', /^\/setup\/import-jobs\/j1\/commit$/, (route, req) => { commitBody = req.postData(); phase = 'importing'; return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ status: 'importing' }) }); }],
]), async ({ page, log }) => {
  check('data tab shows the import panel with the three tabs', (await page.getByRole('tab', { name: 'Excel / CSV' }).count()) === 1 && (await page.getByRole('tab', { name: 'Old software' }).count()) === 1 && (await page.getByRole('tab', { name: 'Paper' }).count()) === 1);
  await page.getByLabel('Patient file').setInputFiles(FILE);
  await page.locator('[aria-label="Dry-run result"]').waitFor({ timeout: 8000 }).catch(() => {});
  await sleep(300);
  const up = log.requests.find((r) => r.method === 'POST' && /import-uploads/.test(r.path));
  check('upload sent to /setup/import-uploads with the format', !!up);
  const job = log.requests.find((r) => r.method === 'POST' && /^\/setup\/import-jobs$/.test(r.path));
  check('job created with uploadId and sourceFormat', job && JSON.parse(job.body).uploadId === 'u1' && JSON.parse(job.body).sourceFormat === 'csv', job?.body);
  const t = await bodyText(page);
  check('four count tiles: ready 3, duplicates 2, errors 1, rows 6', /3\s*Ready to create/.test(t) && /2\s*Possible duplicates/.test(t) && /1\s*Errors/.test(t) && /6\s*Rows in file/.test(t), t.slice(0, 400));
  check('mapping table preselects the suggested columns', (await page.getByLabel('Column for Name').inputValue()) === 'Patient Name' && (await page.getByLabel('Column for Mobile').inputValue()) === 'Mobile No');
  check('error report download offered', (await btn(page, 'Download error report (CSV)').count()) === 1);
  check('import button counts only ready rows before any decision', (await btn(page, 'Import 3 patients').count()) === 1);
  await btn(page, 'Review 2 possible duplicates').click(); await sleep(400);
  const dlg = page.getByRole('dialog', { name: 'Possible duplicates' });
  check('drawer lists both rows with reason', (await dlg.innerText()).includes('Row 3: Asha Rao') && (await dlg.innerText()).includes('Already a patient') && (await dlg.innerText()).includes('Repeated in this file'));
  check('default action is Skip', (await page.getByLabel('Action for row 3').inputValue()) === 'skip');
  await page.getByLabel('Action for row 3').selectOption('merge');
  await page.getByLabel('Action for row 5').selectOption('create');
  await btn(page, 'Done').click(); await sleep(300);
  check('import button now counts the chosen duplicates (3 + 2)', (await btn(page, 'Import 5 patients').count()) === 1);
  await btn(page, 'Import 5 patients').click(); await sleep(600);
  const c = commitBody ? JSON.parse(commitBody) : null;
  check('commit body carries the decisions', c && c.duplicateDecisions.length === 2 && c.duplicateDecisions.some((d) => d.row === 3 && d.action === 'merge') && c.duplicateDecisions.some((d) => d.row === 5 && d.action === 'create'), commitBody);
  const prog = await bodyText(page);
  check('progress text while importing, tells the owner they can keep working', /Importing \d+ of \d+ patients/.test(prog) && /keep working/.test(prog), prog.slice(0, 300));
  await sleep(6000);
  const done = await bodyText(page);
  check('done: created 4, merged 1', /4\s*Created/.test(done) && /1\s*Merged/.test(done), done.slice(0, 300));
});

await run('permission absent', ['setup:view'], base(), async ({ page }) => {
  check('without setup:edit the import panel is absent', (await page.getByLabel('Patient file').count()) === 0 && (await page.getByRole('tab', { name: 'Excel / CSV' }).count()) === 0);
  check('the export side still renders', /Start export job/.test(await bodyText(page)));
});

await run('DPA gate error', edit, base([['POST', /import-uploads/, { status: 403, body: { type: 'https://x/DPA_REQUIRED', title: 'Data processing agreement required', detail: 'Sign it.' } }]]), async ({ page }) => {
  await page.getByLabel('Patient file').setInputFiles(FILE); await sleep(700);
  check('403 DPA_REQUIRED shows an alert in plain words', /data processing agreement/i.test((await page.getByRole('alert').allInnerTexts()).join(' ')));
});

await run('failed job', edit, base([
  ['POST', /import-uploads/, { status: 201, body: { uploadId: 'u1' } }], ['POST', /^\/setup\/import-jobs$/, { status: 201, body: { id: 'j1', status: 'validating' } }],
  ['GET', /^\/setup\/import-jobs\/j1$/, { body: { id: 'j1', status: 'failed', errorMessage: 'NO_ROWS' } }],
]), async ({ page }) => {
  await page.getByLabel('Patient file').setInputFiles(FILE); await sleep(2800);
  check('failed job maps the code to a sentence', /no patient rows/.test((await page.getByRole('alert').allInnerTexts()).join(' ')));
});

await run('empty file result', edit, base([
  ['POST', /import-uploads/, { status: 201, body: { uploadId: 'u1' } }], ['POST', /^\/setup\/import-jobs$/, { status: 201, body: dryRun({ ready: 0, duplicates: 0, errors: 0, total: 0 }) }],
]), async ({ page }) => {
  await page.getByLabel('Patient file').setInputFiles(FILE); await sleep(1200);
  check('empty state when the file has no rows', /No patient rows found/.test(await bodyText(page)) && (await page.getByRole('button', { name: /^Import \d+/ }).count()) === 0);
});

let putBody = null;
await run('mapping needed', edit, base([
  ['POST', /import-uploads/, { status: 201, body: { uploadId: 'u1' } }],
  ['POST', /^\/setup\/import-jobs$/, { status: 201, body: dryRun({ ready: 0, duplicates: 0, errors: 0, total: 4, needsMapping: true }, []) }],
  ['PUT', /^\/setup\/import-jobs\/j1\/mapping$/, (route, req) => { putBody = req.postData(); return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ diff: { ready: 4, duplicates: 0, errors: 0, total: 4 }, sample: [{ name: 'Asha', mobile: '98765' }] }) }); }],
]), async ({ page }) => {
  await page.getByLabel('Patient file').setInputFiles(FILE); await sleep(1200);
  check('required columns unmapped: import disabled and the apply button disabled', await btn(page, 'Import 0 patients').isDisabled() && await btn(page, 'Apply and check again').isDisabled());
  check('the missing fields are named', /Map: Name, Mobile, Date of birth, Sex/.test(await bodyText(page)));
  await page.getByLabel('Column for Name').selectOption('Patient Name');
  await page.getByLabel('Column for Mobile').selectOption('Mobile No');
  await page.getByLabel('Column for Date of birth').selectOption('DOB');
  await page.getByLabel('Column for Sex').selectOption('Gender');
  await page.locator('#imp-datefmt').selectOption('MM/dd/yyyy');
  await btn(page, 'Apply and check again').click(); await sleep(700);
  const p = putBody ? JSON.parse(putBody) : null;
  check('PUT mapping carries columns and the date format', p && p.dateFormat === 'MM/dd/yyyy' && p.columns.length === 4 && p.columns.some((c) => c.target === 'sex' && c.source === 'Gender'), putBody);
  check('after the re-check the ready count and a sample row show', /4\s*Ready to create/.test(await bodyText(page)) && /Sample row/.test(await page.locator('main').innerHTML()));
  check('import enabled once the required fields are mapped', await btn(page, 'Import 4 patients').isEnabled());
});

await run('old software and paper panes', edit, base(), async ({ page }) => {
  await page.getByRole('tab', { name: 'Old software' }).click();
  check('old software pane explains export then upload', /Export your patient list/.test(await bodyText(page)));
  await page.getByRole('tab', { name: 'Paper' }).click();
  check('paper pane offers the template', (await btn(page, 'Download template (CSV)').count()) === 1);
});

await run('390', edit, base([
  ['POST', /import-uploads/, { status: 201, body: { uploadId: 'u1' } }], ['POST', /^\/setup\/import-jobs$/, { status: 201, body: dryRun({ ready: 3, duplicates: 2, errors: 1, total: 6 }) }],
]), async ({ page }) => {
  await page.getByLabel('Patient file').setInputFiles(FILE); await sleep(1200);
  const m = await page.evaluate(() => ({ dsw: document.documentElement.scrollWidth, dcw: document.documentElement.clientWidth }));
  check('390px: no horizontal page scroll', m.dsw <= m.dcw, JSON.stringify(m));
  const h = await btn(page, 'Import 3 patients').evaluate((e) => e.getBoundingClientRect().height);
  const hs = await page.getByLabel('Column for Name').evaluate((e) => e.getBoundingClientRect().height);
  check('390px: buttons and selects are at least 44px tall', h >= 44 && hs >= 44, `${h} ${hs}`);
}, { width: 390, height: 900 });

// AC5: an imported allergy is shown as "doctor to confirm" in the consult's allergy register
{
  const SELF = '11111111-1111-1111-1111-111111111111';
  const entry = { id: 'e1', appointmentId: null, patientId: 'p1', doctorId: SELF, departmentId: 'd1', queueDate: '2026-10-19', tokenNumber: 3, status: 'in_consult', priority: false, priorityReason: null, createdAt: new Date().toISOString(), source: 'walk_in' };
  const patient = { id: 'p1', mrn: 'M-1', name: 'Omar Test', phone: '', dob: '1980-03-04', gender: 'male', status: 'active', allergies: ['Penicillin'], chronicConditions: [], activePackages: 0, lastVisitAt: null };
  const allergies = [{ id: 'a1', patientId: 'p1', substance: 'Penicillin', severity: 'moderate', reaction: null, active: true, category: null, source: 'outside_record', recordedBy: SELF, recordedByName: 'Rana', recordedAt: new Date().toISOString(), updatedAt: null, unconfirmed: true },
    { id: 'a2', patientId: 'p1', substance: 'Latex', severity: 'mild', reaction: null, active: true, category: null, source: 'clinician', recordedBy: SELF, recordedByName: 'Rana', recordedAt: new Date().toISOString(), updatedAt: null, unconfirmed: false }];
  const { browser, page, log } = await launch();
  try {
    await seedClinic(page, ['queue:view', 'queue:edit', 'patients:view', 'clinical:view', 'clinical:edit'], { staffName: 'Dr Test', roleName: 'Doctor' });
    await mockApi(page, log, [
      ['GET', /^\/queue(\?|$)/, { body: [entry] }], ['GET', /^\/patients\/p1/, { body: patient }], ['GET', /^\/departments\/d1\/flow/, { body: { steps: [] } }],
      ['GET', /^\/clinical\/patients\/p1\/allergies\/status$/, { body: { state: 'recorded', activeCount: 2 } }],
      ['GET', /^\/clinical\/patients\/p1\/pregnancy-status$/, { body: { status: 'not_documented' } }],
      ['GET', /^\/clinical\/patients\/p1\/allergies/, { body: allergies }],
      ['GET', /^\/clinical\/patients\/p1\/(conditions|prescriptions)/, { body: [] }], ['GET', /^\/clinical\/patients\/p1\/timeline/, { body: { data: [] } }],
      ['GET', /^\/clinical\/(vitals|notes|prescriptions)\//, { status: 404, body: {} }], ['GET', /./, { body: [] }],
    ]);
    await page.goto(`${APP}/consult?entry=e1&step=reason`, { waitUntil: 'networkidle' });
    await btn(page, 'Allergy register').click(); await sleep(800);
    const dlg = (await page.getByRole('dialog').allInnerTexts()).join(' ');
    check('consult: the imported allergy carries "Imported, doctor to confirm"', /Penicillin[\s\S]*Imported, doctor to confirm/.test(dlg), dlg.slice(0, 300));
    check('consult: a clinician-entered allergy does not', (dlg.match(/doctor to confirm/g) ?? []).length === 1, dlg.slice(0, 300));
    check('consult: zero console/page errors', log.pageErrors.length === 0, JSON.stringify(log.pageErrors));
  } finally { dumpLog('consult', log); await browser.close(); }
}

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
