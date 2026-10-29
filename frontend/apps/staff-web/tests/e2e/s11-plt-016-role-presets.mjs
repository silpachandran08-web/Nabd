// PLT-016 E2E: preset badge + Reset to preset (Owner only), Clinician pill, clinician backfill review list.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3517 node s11-plt-016-role-presets.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const g = (module, on) => ({ module, view: on, create: on, edit: on, delete: false, approve: false, refundDiscount: false, export: false });
const roles = () => [
  { id: 'r-owner', name: 'Owner', builtIn: true, grants: [g('staff', true)], mfaRequired: false, presetCode: 'owner', presetVersion: 1 },
  { id: 'r-doc', name: 'Consultant', builtIn: false, grants: [g('clinical', true)], mfaRequired: false, presetCode: 'doctor', presetVersion: 1 },
  { id: 'r-custom', name: 'Billing Clerk', builtIn: false, grants: [g('billing', true)], mfaRequired: false, presetCode: null, presetVersion: null },
];
const staff = () => [
  { id: 's1', email: 'asha@x.test', name: 'Dr Asha', mobilePhone: '+910000000001', roleId: 'r-doc', status: 'active', scope: 'all_clinic_patients', lastSeenAt: null, professionalRegistrationNo: 'KMC-1', clinician: true },
  { id: 's2', email: 'bala@x.test', name: 'Bala Front', mobilePhone: '+910000000002', roleId: 'r-custom', status: 'active', scope: 'all_clinic_patients', lastSeenAt: null, professionalRegistrationNo: null, clinician: false },
];
let opts;
const handlers = () => [
  ['GET', /^\/staff\?/, opts.staffFail ? { status: 500, body: { title: 'x', detail: 'boom' } } : { body: { data: opts.staff ?? staff(), page: { nextCursor: null, limit: 50 } } }],
  ['GET', /^\/staff\/clinician-review/, opts.reviewFail ? { status: 500, body: { title: 'x', detail: 'boom' } }
    : { body: [{ staffId: 's2', name: 'Bala Front', roleName: 'Billing Clerk', reason: 'registration_without_doctor_role', clinician: true }] }],
  ['PATCH', /^\/staff\/s2$/, (r) => { opts.patched = JSON.parse(r.request().postData()); r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...staff()[1], clinician: opts.patched.clinician }) }); }],
  ['PATCH', /^\/staff\/s1$/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...staff()[0], clinician: false }) })],
  ['GET', /^\/roles$/, { body: roles() }],
  ['POST', /^\/roles\/r-doc\/reset-to-preset$/, (r) => {
    opts.resetHeaders = r.request().headers();
    r.fulfill({ status: opts.resetStatus ?? 200, contentType: 'application/json',
      body: JSON.stringify(opts.resetStatus ? { title: 'x', detail: 'This is a custom role; only preset roles can be reset.' } : { ...roles()[1], name: 'Doctor', presetVersion: 1 }) });
  }],
  ['GET', /^\/departments/, { body: [] }],
  ['GET', /^\/auth\/break-glass\/active/, { body: { items: [] } }],
];
const OWNER_PERMS = ['staff:view', 'staff:create', 'staff:edit', 'staff:delete'];
async function open(perms, extra, o = {}, viewport) {
  opts = o;
  const s = await launch({ viewport });
  await seedClinic(s.page, perms, extra);
  await mockApi(s.page, s.log, handlers());
  await s.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  await s.page.getByRole('tab', { name: 'Roles & permissions' }).click().catch(() => {}); // ADM-018: presets moved to their own tab
  return s;
}
const body = (p) => p.locator('body').innerText();
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
let s;
try {
  // happy path: owner
  s = await open(OWNER_PERMS, { owner: true, roleName: 'Owner' });
  const presetList = s.page.getByRole('complementary', { name: 'Role presets' });
  const t = await presetList.innerText();
  check('1 preset role shows a Preset badge, custom shows Custom, owner Built-in', /Consultant[\s\S]*Preset/i.test(t) && /Billing Clerk[\s\S]*Custom/i.test(t) && /Owner[\s\S]*Built-in/i.test(t), t.replace(/\s+/g, ' ').slice(0, 300));
  const resetBtns = s.page.getByRole('button', { name: 'Reset to preset' });
  check('2 exactly one Reset to preset (the editable preset, not custom/built-in)', await resetBtns.count() === 1);
  await resetBtns.click();
  await s.page.getByText('Doctor is back to its preset.').waitFor({ timeout: 5000 }).then(() => check('3 reset confirms and the list shows the preset name again', true), () => check('3 reset confirmation', false));
  check('4 reset sent an Idempotency-Key', /^[0-9a-f-]{36}$/.test(opts.resetHeaders?.['idempotency-key'] ?? ''));
  await s.page.getByRole('tab', { name: 'People' }).click(); // ADM-018
  // CARRY-12: the separate Clinician: on/off toggle was merged into the CLN-005 dialog (one clinician control per row).
  check('5 staff row shows Clinician pill and the single clinician control for registered doctor', /Dr Asha[\s\S]*Clinician/.test(await body(s.page)) && await s.page.getByRole('button', { name: 'Reg. no. & clinician', exact: true }).count() === 1);
  check('6 no separate Clinician toggle on any row', await s.page.getByRole('button', { name: /Clinician: (on|off)/ }).count() === 0);
  const review = s.page.getByRole('table', { name: 'Clinicians to confirm' });
  check('7 review list shows the ambiguous row with a readable reason', /Bala Front[\s\S]*registration number but no doctor role/.test(await review.innerText()));
  await review.getByRole('button', { name: 'Not a clinician' }).click();
  await s.page.waitForTimeout(500);
  check('8 confirming sends PATCH clinician=false and removes the row', opts.patched?.clinician === false && await s.page.getByRole('table', { name: 'Clinicians to confirm' }).count() === 0);
  check('9 no console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('owner', s.log);
  await s.browser.close();

  // error on reset (409) is shown, nothing else breaks
  s = await open(OWNER_PERMS, { owner: true }, { resetStatus: 409 });
  await s.page.getByRole('button', { name: 'Reset to preset' }).click();
  await s.page.getByText('only preset roles can be reset').waitFor({ timeout: 5000 }).then(() => check('10 reset error detail shown', true), () => check('10 reset error detail shown', false));
  await s.browser.close();

  // permission-absent: staff:view only, not owner
  s = await open(['staff:view'], { owner: false });
  check('11 non-owner viewer: no Reset to preset, no Clinician toggle, no confirm buttons',
    await s.page.getByRole('button', { name: /Reset to preset|Clinician: |Not a clinician/ }).count() === 0);
  await s.browser.close();
  s = await open(OWNER_PERMS, { owner: false });
  check('12 staff:edit but not the Owner: no Reset to preset', await s.page.getByRole('button', { name: 'Reset to preset' }).count() === 0);
  await s.browser.close();

  // review list failure and empty staff
  s = await open(OWNER_PERMS, { owner: true }, { reviewFail: true });
  await s.page.getByRole('tab', { name: 'People' }).click(); // ADM-018
  check('13 review list failure is visible', /Couldn.t load the clinicians to confirm/.test(await body(s.page)));
  await s.browser.close();
  s = await open(OWNER_PERMS, { owner: true }, { staff: [] });
  check('14 empty staff state', /No staff yet/.test(await body(s.page)));
  await s.browser.close();

  // 390px
  s = await open(OWNER_PERMS, { owner: true }, {}, { width: 390, height: 800 });
  check('15 390px: no horizontal page scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  check('16 390px: no console errors', errs(s.log).length === 0, errs(s.log).join('|'));
} catch (e) { check('run', false, String(e)); }
finally { if (s) await s.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok);
console.log(`${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
