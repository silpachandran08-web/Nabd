// S15 IDC-017: ABHA step after registration, QR prefill, decline, linked-elsewhere, permission gating. Run: APP=http://localhost:3818 node s15-idc017-abha.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';
const qr = JSON.stringify({ name: 'Test Person', dob: '02-01-1990', gender: 'F', mobile: '9000000000' });
const base = (statusEnabled, abha) => [
  ['GET', /^\/abha\/status/, { body: { enabled: statusEnabled } }],
  ['GET', /^\/patients(\?.*)?$/, { body: { data: [], page: { nextCursor: null, limit: 25 } } }],
  ['POST', /^\/patients$/, { status: 201, body: { id: 'p9' } }],
  ...abha,
];
async function run(tag, perms, handlers, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms); await mockApi(page, log, handlers);
    await page.goto(APP + '/patients'); await sleep(1500);
    await page.getByRole('button', { name: /Register patient|New patient|Register/ }).first().click(); await sleep(500);
    await fn({ page, log });
    check(`${tag} no page errors`, log.pageErrors.length === 0, JSON.stringify(log.pageErrors));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const fill = async (page) => {
  await page.locator('#reg-name').fill('Test Person'); await page.locator('#reg-phone').fill('+919000000000');
  await page.locator('#reg-dob').fill('1990-01-02'); await page.locator('#reg-gender').selectOption('female');
  await page.getByRole('button', { name: 'Register patient' }).last().click(); await sleep(800);
};
const perms = ['patients:view', 'patients:create', 'patients:edit'];
const ok = [['POST', /^\/patients\/p9\/abha\/verify/, (route, req) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(/"init"/.test(req.postData() ?? '') ? { txnId: 't1' } : { verified: true, abhaLast4: '1234', abhaAddress: 'tp.person@abdm' }) })]];

await run('verify', perms, base(true, ok), async ({ page, log }) => {
  await page.locator('#reg-abha-qr').fill(qr); await sleep(200);
  check('QR prefills name/dob/sex/mobile', (await page.locator('#reg-name').inputValue()) === 'Test Person' && (await page.locator('#reg-dob').inputValue()) === '1990-01-02' && (await page.locator('#reg-gender').inputValue()) === 'female' && (await page.locator('#reg-phone').inputValue()) === '+919000000000');
  await page.getByRole('button', { name: 'Register patient' }).last().click(); await sleep(800);
  await page.locator('#abha-value').fill('12-3456-7890-1234');
  await page.getByRole('button', { name: 'Send OTP' }).click(); await sleep(500);
  await page.locator('#abha-otp').fill('123456');
  await page.getByRole('button', { name: 'Confirm OTP' }).click(); await sleep(500);
  check('verified shows masked number only', (await page.getByText('XX-XXXX-XXXX-1234').count()) > 0 && (await page.getByText('12345678901234').count()) === 0);
});
await run('decline', perms, base(true, ok), async ({ page, log }) => {
  await fill(page);
  await page.getByRole('button', { name: 'Skip ABHA' }).click(); await sleep(500);
  check('declined: nothing sent to ABHA endpoints', !log.requests.some((r) => /abha\/(verify|enrol)/.test(r.path)));
});
await run('linked elsewhere', perms, base(true, [['POST', /^\/patients\/p9\/abha\/verify/, { status: 409, body: { type: 'abha-linked-elsewhere', title: 'Linked', existingPatient: { id: 'p1', name: 'Other Person', mrn: 'M1' } } }]]), async ({ page }) => {
  await fill(page);
  await page.locator('#abha-value').fill('asha.k@abdm'); await page.getByRole('button', { name: 'Send OTP' }).click(); await sleep(500);
  check('existing patient shown with Open record', (await page.getByText('Other Person').count()) > 0 && (await page.getByRole('button', { name: 'Open record' }).count()) > 0);
});
await run('phone width', perms, base(true, ok), async ({ page }) => {
  await fill(page);
  check('390px: ABHA panel shown, no horizontal scroll', (await page.locator('#abha-value').count()) > 0 && (await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)));
}, { width: 390, height: 844 });
await run('abdm off', perms, base(false, []), async ({ page }) => {
  check('no ABHA QR field when ABDM is off', (await page.locator('#reg-abha-qr').count()) === 0);
});
await run('no edit permission', ['patients:view', 'patients:create'], base(true, []), async ({ page, log }) => {
  check('absent without patients:edit', (await page.locator('#reg-abha-qr').count()) === 0 && !log.requests.some((r) => /abha\/status/.test(r.path)));
});
console.log(results.filter((r) => !r.ok).length ? 'FAILED' : 'ALL PASS', results.length);
process.exit(results.some((r) => !r.ok) ? 1 : 0);
