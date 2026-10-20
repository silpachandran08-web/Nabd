// Independent QA (S12 ACC-028): phone field error with the clinic country hint on registration (AE / SA hints), staff invite
// shows the server's phone refusal, the data-quality list screen (ui spec), permission-absent, 390px.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3811 node tests/e2e/s12qa-acc-028.mjs
import { launch, seedClinic, mockApi, check, results, sleep, APP } from './qa-lib.mjs';

const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
const phone400 = (cc, field = 'phone') => ({ status: 400, body: { type: 'https://api.nabd.health/errors/validation-failed', title: 'Validation failed', code: 'INVALID_PHONE',
  detail: `${field}: must be a valid phone number for ${cc}, or start with + and the country code`,
  errors: [{ field, message: `must be a valid phone number for ${cc}, or start with + and the country code` }] } });
const registry = { status: 200, body: { total: 0, counts: { all: 0 }, rows: [], matchedTotal: 0, hasMore: false } };
const base = [['GET', /^\/patients\/registry/, registry], ['GET', /^\/patients\/guardian-reviews-due/, { body: [] }],
  ['GET', /^\/patients\/data-quality/, { body: { items: [{ patientId: 'p9', displayName: 'Legacy Bad', rawPhone: '050-12' }], nextCursor: null } }]];

async function patients(perms, region, mocks, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms, { tenantRegion: region });
  await mockApi(s.page, s.log, [['GET', /^\/setup\/profile$/, { body: { timezone: region === 'AE' ? 'Asia/Dubai' : 'Asia/Riyadh', region } }], ...mocks, ...base]);
  await s.page.goto(`${APP}/patients`); await sleep(1500);
  return s;
}
const fill = async (page, phone) => {
  await page.getByRole('button', { name: /Register patient/ }).first().click();
  const d = page.getByRole('dialog');
  await d.getByLabel('Full name').fill('Qa Person');
  await d.getByLabel('Mobile').fill(phone);
  await d.getByLabel('Date of birth').fill('1990-01-01');
  await d.getByLabel('Sex').selectOption('male');
  await d.getByRole('button', { name: 'Register patient' }).click(); await sleep(900);
  return d;
};

let s;
try {
  for (const [region, cc] of [['AE', 'AE'], ['KSA', 'SA']]) {
    s = await patients(['patients:view', 'patients:create', 'patients:edit'], region, [['POST', /^\/patients$/, phone400(cc)]]);
    const d = await fill(s.page, 'abc');
    const err = d.locator('#reg-phone-error');
    const txt = await err.innerText().catch(() => '');
    check(`P1 ${region}: inline error under Mobile names the clinic country (${cc})`, new RegExp(`for ${cc}`).test(txt), txt);
    check(`P2 ${region}: Mobile is aria-invalid and described by the error`, await d.getByLabel('Mobile').getAttribute('aria-invalid') === 'true'
      && await d.getByLabel('Mobile').getAttribute('aria-describedby') === 'reg-phone-error');
    check(`P3 ${region}: dialog stays open with the typed values`, await d.getByLabel('Full name').inputValue() === 'Qa Person');
    check(`P4 ${region}: no console errors`, noise(s.log).filter((x) => !/400/.test(x)).length === 0, noise(s.log).join(' | '));
    await s.browser.close(); s = null;
  }

  // ui spec: data-quality list for the desk (patients:edit) — look for any entry point on the patients screen
  s = await patients(['patients:view', 'patients:create', 'patients:edit'], 'AE', []);
  const dqLink = s.page.getByRole('link', { name: /data quality|fix phone|invalid phone|numbers to fix/i })
    .or(s.page.getByRole('button', { name: /data quality|fix phone|invalid phone|numbers to fix/i })).or(s.page.getByRole('tab', { name: /data quality|phone/i }));
  const found = await dqLink.count();
  const called = s.log.requests.some((r) => r.path.startsWith('/patients/data-quality'));
  check('P5 ui: reception.patients offers the phone data-quality list (entry point or list fetched)', found > 0 || called, `entry points=${found}, data-quality fetched=${called}`);
  await s.browser.close(); s = null;

  // staff invite: the server's phone refusal is shown and names the country
  s = await launch();
  await seedClinic(s.page, ['staff:view', 'staff:create', 'staff:edit'], { tenantRegion: 'AE', owner: true, roleName: 'Owner' });
  const invites = [];
  await mockApi(s.page, s.log, [
    ['GET', /^\/setup\/profile$/, { body: { timezone: 'Asia/Dubai', region: 'AE' } }],
    ['GET', /^\/staff\?/, { body: { data: [], page: { nextCursor: null, limit: 50 } } }],
    ['GET', /^\/staff\/clinician-review/, { body: [] }],
    ['GET', /^\/roles$/, { body: [{ id: 'r1', name: 'Desk', builtIn: false, grants: [], mfaRequired: false }] }],
    ['POST', /^\/staff$/, (r, req) => { invites.push(req.postData()); return r.fulfill({ status: 400, contentType: 'application/problem+json', body: JSON.stringify(phone400('AE', 'mobilePhone').body) }); }],
    ['GET', /./, { body: [] }],
  ]);
  await s.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  await s.page.getByRole('button', { name: /Invite staff/ }).first().click();
  await s.page.locator('#inviteName').fill('New Desk');
  await s.page.locator('#inviteEmail').fill('desk@x.test');
  await s.page.locator('#invitePhone').fill('abc');
  await s.page.locator('#inviteRole').selectOption('r1');
  await s.page.getByRole('dialog').getByRole('button', { name: 'Invite staff' }).click(); await sleep(800);
  const alert = await s.page.getByRole('dialog').getByRole('alert').innerText().catch(() => '');
  check('P6 staff invite: phone refusal shown with the AE hint, dialog kept open', /valid phone number for AE/.test(alert) && invites.length === 1, alert);
  await s.browser.close(); s = null;

  // permission absent: no patients:create -> no Register control at all
  s = await patients(['patients:view'], 'AE', []);
  check('P7 patients:view only: Register patient control absent', await s.page.getByRole('button', { name: /Register patient/ }).count() === 0);
  await s.browser.close(); s = null;

  s = await patients(['patients:view', 'patients:create'], 'AE', [['POST', /^\/patients$/, phone400('AE')]], { width: 390, height: 844 });
  await fill(s.page, 'abc');
  check('P8 390px: field error visible without horizontal scroll', await s.page.locator('#reg-phone-error').isVisible()
    && await s.page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth));
} catch (e) { check('run', false, String(e).split('\n').slice(0, 3).join(' ')); }
finally { if (s) await s.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok);
console.log(`${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
