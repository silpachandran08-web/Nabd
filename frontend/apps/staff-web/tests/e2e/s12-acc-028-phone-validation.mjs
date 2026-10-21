// ACC-028: registration shows the server's phone field error (with the clinic country hint) under the Mobile input.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3701 node s12-acc-028-phone-validation.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3000';
const registry = (rows) => ({ status: 200, body: { total: rows.length, counts: { all: rows.length }, rows, matchedTotal: rows.length, hasMore: false } });
const base = [
  ['GET', /^\/patients\/registry/, registry([])],
  ['GET', /^\/patients\/guardian-reviews-due/, { status: 200, body: [] }],
];
const phoneProblem = { status: 400, body: { type: 'https://api.nabd.health/errors/validation-failed', title: 'Validation failed', code: 'INVALID_PHONE',
  detail: 'phone: must be a valid phone number for AE, or start with + and the country code',
  errors: [{ field: 'phone', message: 'must be a valid phone number for AE, or start with + and the country code' }] } };
const detail = { id: 'p2', mrn: 'MRN-2', name: 'New Patient', phone: '+971501234567', dob: '1990-01-01', gender: 'male', status: 'active', allergies: [],
  chronicConditions: [], activePackages: 0, lastVisitAt: null, isMinor: false, guardianId: null, guardianName: null, guardianConsentGrantedAt: null };
const RW = ['patients:view', 'patients:create'];

async function run(tag, perms, extra, fn, { viewport } = {}) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms);
    await mockApi(page, log, [...extra, ...base]);
    await page.goto(APP + '/patients'); await sleep(1500);
    await fn(page, log);
    dumpLog(tag, log);
    check(`${tag}-clean`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0, JSON.stringify(log.console.concat(log.pageErrors)));
  } finally { await browser.close(); }
}
const fill = async (page, phone) => {
  await page.getByRole('button', { name: /Register patient/ }).first().click();
  const d = page.getByRole('dialog');
  await d.getByLabel('Full name').fill('New Patient');
  await d.getByLabel('Mobile').fill(phone);
  await d.getByLabel('Date of birth').fill('1990-01-01');
  await d.getByLabel('Sex').selectOption('male');
  return d;
};
const posts = (log) => log.requests.filter((r) => r.method === 'POST' && r.path === '/patients');

// 1 invalid phone -> field error under Mobile, with country hint, aria-invalid; typing clears it
await run('F1', RW, [['POST', /^\/patients$/, phoneProblem]], async (page, log) => {
  const d = await fill(page, 'abc');
  await d.getByRole('button', { name: 'Register patient' }).click(); await sleep(1000);
  const input = d.getByLabel('Mobile');
  const err = d.locator('#reg-phone-error');
  check('F1-field-error', (await err.count()) === 1 && /valid phone number for AE/.test(await err.innerText()), await err.count() ? await err.innerText() : 'none');
  check('F1-aria', (await input.getAttribute('aria-invalid')) === 'true' && (await input.getAttribute('aria-describedby')) === 'reg-phone-error');
  check('F1-no-generic-banner', (await d.getByRole('alert').count()) === 1, `alerts=${await d.getByRole('alert').count()}`);
  check('F1-input-kept', (await input.inputValue()) === 'abc' && (await d.getByLabel('Full name').inputValue()) === 'New Patient');
  check('F1-one-post', posts(log).length === 1);
  await input.fill('050 123 4567');
  check('F1-cleared-on-edit', (await err.count()) === 0 && (await input.getAttribute('aria-invalid')) === null);
});

// 2 happy path: valid number posts as typed (server normalises), dialog closes, no field error
await run('H1', RW, [['POST', /^\/patients$/, { status: 201, body: { id: 'p2' } }], ['GET', /^\/patients\/p2$/, { status: 200, body: detail }]], async (page, log) => {
  const d = await fill(page, '050 123 4567');
  await d.getByRole('button', { name: 'Register patient' }).click(); await sleep(1200);
  const b = JSON.parse(posts(log)[0]?.body ?? '{}');
  check('H1-body', b.phone === '050 123 4567', JSON.stringify(b));
  check('H1-no-field-error', (await page.locator('#reg-phone-error').count()) === 0 && (await page.getByRole('dialog', { name: 'Register patient' }).count()) === 0);
});

// 3 other 400 (not a phone field) -> generic banner, no phone field error
await run('E1', RW, [['POST', /^\/patients$/, { status: 400, body: { title: 'Validation failed', detail: 'dob: must be a past date', errors: [{ field: 'dob', message: 'must be a past date' }] } }]], async (page) => {
  const d = await fill(page, '050 123 4567');
  await d.getByRole('button', { name: 'Register patient' }).click(); await sleep(1000);
  check('E1-banner', /dob: must be a past date/.test(await d.getByRole('alert').innerText()) && (await d.locator('#reg-phone-error').count()) === 0);
});

// 4 server error -> generic banner
await run('E2', RW, [['POST', /^\/patients$/, { status: 500, body: { title: 'Server error', detail: 'Something broke' } }]], async (page) => {
  const d = await fill(page, '050 123 4567');
  await d.getByRole('button', { name: 'Register patient' }).click(); await sleep(1000);
  check('E2-500', (await d.getByRole('alert').innerText()).includes('Something broke') && (await d.locator('#reg-phone-error').count()) === 0);
});

// 5 boundary: phone error after the duplicate review ("Different person") returns to the form with the field error
await run('B1', RW, [['POST', /^\/patients$/, (route, req) => {
  const body = JSON.parse(req.postData() ?? '{}');
  return body.confirmedNotDuplicate
    ? route.fulfill({ status: 400, contentType: 'application/problem+json', body: JSON.stringify(phoneProblem.body) })
    : route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ candidates: [{ patientId: 'p9', name: 'Same Person', phone: '+971501234567', matchScore: 1 }] }) });
}]], async (page) => {
  const d = await fill(page, '050 123 4567');
  await d.getByRole('button', { name: 'Register patient' }).click(); await sleep(1000);
  await d.getByRole('button', { name: /Different person/ }).click(); await sleep(1000);
  check('B1-back-to-form-with-error', (await d.locator('#reg-phone-error').count()) === 1 && (await d.getByLabel('Mobile').count()) === 1);
});

// 6 permission absent: no register control
await run('P1', ['patients:view'], [], async (page) => {
  check('P1-absent', (await page.getByRole('button', { name: /Register patient/ }).count()) === 0);
});

// 7 390px: field error shown without horizontal scroll
await run('R1', RW, [['POST', /^\/patients$/, phoneProblem]], async (page) => {
  const d = await fill(page, 'abc');
  await d.getByRole('button', { name: 'Register patient' }).click(); await sleep(1000);
  const scroll = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check('R1-390-no-hscroll', scroll <= 0 && (await d.locator('#reg-phone-error').isVisible()), `overflow=${scroll}`);
}, { viewport: { width: 390, height: 844 } });

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
