// REC-037: National ID on register (write-only API), address, guardian for minors still work; drawer is honest that address/ID are not returned.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules node s8-rec10-registration-fields.mjs   (app on http://localhost:3110)
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3000';
const SHOTS = '/home/claude/ws/reports/shots-rec10';
const row = { id: 'p1', mrn: 'MRN-1', name: 'Asha Rao', phone: '+919876543210', age: 30, gender: 'female', minor: false, lastVisit: null, nextAppointment: null,
  nextDoctorName: null, nextDoctorId: null, nextAppointmentId: null, nextAppointmentToday: false, packageSessionsUsed: null, packageSessionsTotal: null,
  condition: null, followUpDue: false, allergy: null, balanceDue: false, duplicate: false, archived: false, queueToken: null, queueStatus: null, tags: [] };
const registry = (rows) => ({ status: 200, body: { total: rows.length, counts: { all: rows.length }, rows, matchedTotal: rows.length, hasMore: false } });
const detail = (id) => ({ id, mrn: 'MRN-2', name: 'New Patient', phone: '+919811111111', dob: '1990-01-01', gender: 'male', status: 'active', allergies: [],
  chronicConditions: [], activePackages: 0, lastVisitAt: null, isMinor: false, guardianId: null, guardianName: null, guardianConsentGrantedAt: null });
const base = (rows = [row]) => [
  ['GET', /^\/patients\/registry/, registry(rows)],
  ['GET', /^\/patients\/guardian-reviews-due/, { status: 200, body: [] }],
];

async function run(tag, perms, extra, fn, { viewport, rows } = {}) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms);
    await mockApi(page, log, [...extra, ...base(rows)]);
    await page.goto(APP + '/patients'); await sleep(1500);
    await fn(page, log);
    dumpLog(tag, log);
    check(`${tag}-clean`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0, JSON.stringify(log.console.concat(log.pageErrors)));
  } finally { await browser.close(); }
}
const fill = async (page, name, dob, extra = {}) => {
  await page.getByRole('button', { name: /Register patient/ }).first().click();
  const d = page.getByRole('dialog');
  await d.getByLabel('Full name').fill(name);
  await d.getByLabel('Mobile').fill('+919811111111');
  await d.getByLabel('Date of birth').fill(dob);
  await d.getByLabel('Sex').selectOption('male');
  if (extra.address) await d.getByLabel(/^Address/).fill(extra.address);
  if (extra.nid) await d.getByLabel(/National ID/).fill(extra.nid);
  return d;
};
const posts = (log) => log.requests.filter((r) => r.method === 'POST' && r.path === '/patients').map((r) => JSON.parse(r.body));
const created = { status: 201, body: { id: 'p2' } };
const RW = ['patients:view', 'patients:create'];

// 1 happy path: adult with address + national ID
await run('H1', RW, [['POST', /^\/patients$/, created], ['GET', /^\/patients\/p2$/, { status: 200, body: detail('p2') }]], async (page, log) => {
  const d = await fill(page, 'New Patient', '1990-01-01', { address: ' 12 Hill Rd ', nid: ' 1234-5678 ' });
  await page.screenshot({ path: SHOTS + '/register-1440.png' });
  await d.getByRole('button', { name: 'Register patient' }).click(); await sleep(1200);
  const b = posts(log)[0];
  check('H1-body', b?.nationalId === '1234-5678' && b?.address === '12 Hill Rd' && b?.guardianId === null && !('guardianConsent' in b), JSON.stringify(b));
  const t = await page.locator('body').innerText();
  check('H1-drawer-honest', /Address and national ID/i.test(t) && /Not available yet/.test(t) && !/1234-5678/.test(t));
  await page.screenshot({ path: SHOTS + '/drawer-1440.png' });
});

// 2 minor: guardian + consent + national ID together
await run('M1', RW, [['GET', /^\/patients\?q=/, { status: 200, body: { data: [{ id: 'g1', name: 'Mum Rao', phone: '+919822222222' }] } }],
  ['POST', /^\/patients$/, created], ['GET', /^\/patients\/p2$/, { status: 200, body: detail('p2') }]], async (page, log) => {
  const d = await fill(page, 'Kid Rao', '2020-05-05', { nid: 'KID-9' });
  const submit = d.getByRole('button', { name: 'Register patient' });
  check('M1-blocked-without-guardian', await submit.isDisabled());
  await d.getByPlaceholder(/Search the guardian/).fill('Mum'); await sleep(900);
  await d.getByRole('button', { name: /Mum Rao/ }).click();
  await d.getByRole('checkbox').check();
  await d.getByLabel('How was consent given?').selectOption('in_person');
  await submit.click(); await sleep(1200);
  const b = posts(log)[0];
  check('M1-body', b?.guardianId === 'g1' && b?.guardianConsent?.confirmed === true && b?.guardianConsent?.channel === 'in_person' && b?.nationalId === 'KID-9', JSON.stringify(b));
});

// 3 boundary: blank national ID is omitted, not sent as "" (server rejects blank)
await run('B1', RW, [['POST', /^\/patients$/, created], ['GET', /^\/patients\/p2$/, { status: 200, body: detail('p2') }]], async (page, log) => {
  const d = await fill(page, 'No ID', '1985-03-03', { nid: '   ' });
  await d.getByRole('button', { name: 'Register patient' }).click(); await sleep(1000);
  const b = posts(log)[0];
  check('B1-omitted', b && !('nationalId' in b) && b.address === null, JSON.stringify(b));
});

// 4 permission absent: view only -> no register control at all
await run('P1', ['patients:view'], [], async (page) => {
  check('P1-absent', (await page.getByRole('button', { name: /Register patient/ }).count()) === 0 && (await page.getByText('Asha Rao').count()) > 0);
});

// 5 empty state
await run('E1', RW, [], async (page) => {
  check('E1-empty', (await page.getByText('No patients here.').count()) === 1);
}, { rows: [] });

// 6 error states: registry 500, registry 403, register POST 500 keeps dialog + input
{
  const { browser, page, log } = await launch();
  try {
    await seedClinic(page, RW);
    await mockApi(page, log, [['GET', /^\/patients\/registry/, { status: 500, body: { title: 'x' } }], ['GET', /^\/patients\/guardian-reviews-due/, { status: 200, body: [] }]]);
    await page.goto(APP + '/patients'); await sleep(1500);
    check('X1-registry-500', (await page.getByText(/Couldn.t load patients/).count()) > 0);
  } finally { await browser.close(); }
}
{
  const { browser, page, log } = await launch();
  try {
    await seedClinic(page, RW);
    await mockApi(page, log, [['GET', /^\/patients\/registry/, { status: 403, body: { title: 'Forbidden' } }], ['GET', /^\/patients\/guardian-reviews-due/, { status: 403, body: {} }]]);
    await page.goto(APP + '/patients'); await sleep(1500);
    check('X2-registry-403', (await page.getByText(/doesn.t have access to patient records/).count()) > 0 && (await page.getByRole('button', { name: /Register patient/ }).count()) === 0);
  } finally { await browser.close(); }
}
await run('X3', RW, [['POST', /^\/patients$/, { status: 500, body: { title: 'Server error', detail: 'Something broke' } }]], async (page, log) => {
  const d = await fill(page, 'Err Case', '1990-01-01', { nid: '777' });
  await d.getByRole('button', { name: 'Register patient' }).click(); await sleep(1000);
  check('X3-post-500', (await d.getByRole('alert').innerText()).includes('Something broke') && (await d.getByLabel(/National ID/).inputValue()) === '777');
});

// 7 390px: no horizontal page scroll, with the dialog open and with the minor guardian block
await run('R1', RW, [['GET', /^\/patients\?q=/, { status: 200, body: { data: [] } }]], async (page) => {
  const d = await fill(page, 'Kid Rao', '2020-05-05', { address: 'A very long address line that should wrap or scroll inside the field only', nid: 'ID-1' });
  const scroll = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  const h = await d.getByLabel(/National ID/).evaluate((el) => el.getBoundingClientRect().height);
  check('R1-390-no-hscroll', scroll <= 0 && h >= 40, `overflow=${scroll} inputHeight=${h}`);
  await page.screenshot({ path: SHOTS + '/register-390.png' });
}, { viewport: { width: 390, height: 844 } });
await run('R2', RW, [], async (page) => {
  const d = await fill(page, 'Wide', '1990-01-01', { nid: 'ID-2' });
  check('R2-1024-visible', await d.getByLabel(/National ID/).isVisible());
}, { viewport: { width: 1024, height: 768 } });

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
