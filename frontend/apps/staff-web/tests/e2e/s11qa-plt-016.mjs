// Independent QA (S11 PLT-016 UI): all six presets listed with badges; Reset to preset on every editable preset and never on the
// built-in Owner or a custom role; absent for a non-Owner; reset error keeps the page usable; 390px.
// NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3613 node s11qa-plt-016.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const g = (module, on) => ({ module, view: on, create: on, edit: on, delete: false, approve: false, refundDiscount: false, export: false });
const presets = [['owner', 'Owner', true], ['owner_doctor', 'Owner-doctor', false], ['doctor', 'Doctor', false], ['nurse', 'Nurse', false],
  ['receptionist', 'Receptionist', false], ['frontdesk_vitals', 'Front-desk + Vitals', false]];
const roles = () => [...presets.map(([code, name, builtIn]) => ({ id: `r-${code}`, name, builtIn, grants: [g('staff', true)], mfaRequired: false, presetCode: code, presetVersion: 1 })),
  { id: 'r-custom', name: 'Lab Clerk', builtIn: false, grants: [g('billing', true)], mfaRequired: false, presetCode: null, presetVersion: null }];
const staff = [{ id: 's9', email: 'n@x.test', name: 'Nurse Noreg', mobilePhone: '+910000000009', roleId: 'r-nurse', status: 'active', scope: 'all_clinic_patients', lastSeenAt: null, professionalRegistrationNo: null, clinician: false }];
let resets = [];
const handlers = (o) => [
  ['GET', /^\/staff\?/, { body: { data: staff, page: { nextCursor: null, limit: 50 } } }],
  ['GET', /^\/staff\/clinician-review/, { body: [] }],
  ['GET', /^\/roles$/, { body: roles() }],
  ['POST', /^\/roles\/([^/]+)\/reset-to-preset$/, (r, req, m) => { resets.push(m[1]); r.fulfill({ status: o.resetStatus ?? 200, contentType: 'application/json',
    body: JSON.stringify(o.resetStatus ? { type: 'https://api.nabd.health/problems/owner-only', title: 'OWNER_ONLY', detail: 'Only the Owner can reset a role to its preset.' } : roles().find((x) => x.id === m[1])) }); }],
  ['GET', /^\/departments/, { body: [] }],
  ['GET', /^\/auth\/break-glass\/active/, { body: { items: [] } }],
];
const OWNER_PERMS = ['staff:view', 'staff:create', 'staff:edit', 'staff:delete'];
async function open(perms, extra, o = {}, viewport) {
  resets = [];
  const s = await launch({ viewport });
  await seedClinic(s.page, perms, extra);
  await mockApi(s.page, s.log, handlers(o));
  await s.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  return s;
}
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|clinic-timezone-fallback|was preloaded using link preload/.test(x))];
let s;
try {
  s = await open(OWNER_PERMS, { owner: true, roleName: 'Owner' });
  const list = await s.page.getByRole('complementary', { name: 'Role presets' }).innerText();
  check('1 all six preset names listed', presets.every(([, name]) => list.includes(name)), list.replace(/\s+/g, ' ').slice(0, 300));
  const btns = s.page.getByRole('button', { name: 'Reset to preset' });
  check('2 five Reset buttons (every preset except the built-in Owner; none for the custom role)', await btns.count() === 5, String(await btns.count()));
  await btns.first().click();
  await s.page.waitForTimeout(600);
  check('3 the reset targeted a non-owner preset role', resets.length === 1 && resets[0] !== 'r-owner' && resets[0] !== 'r-custom', resets.join(','));
  check('4 no Clinician toggle for staff without a registration number', await s.page.getByRole('button', { name: /Clinician: (on|off)/ }).count() === 0);
  check('5 zero console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('owner', s.log);
  await s.browser.close();

  s = await open(OWNER_PERMS, { owner: false, roleName: 'Owner-doctor' });
  check('6 Owner-doctor (not the built-in Owner) with staff:edit: no Reset to preset', await s.page.getByRole('button', { name: 'Reset to preset' }).count() === 0);
  await s.browser.close();

  s = await open(OWNER_PERMS, { owner: true }, { resetStatus: 403 }, { width: 390, height: 800 });
  await s.page.getByRole('button', { name: 'Reset to preset' }).first().click();
  await s.page.getByText('Only the Owner can reset').waitFor({ timeout: 5000 }).then(() => check('7 server refusal detail shown', true), () => check('7 server refusal detail shown', false));
  check('8 390px no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  await s.browser.close();
  s = null;
} catch (e) { check('run', false, String(e)); }
finally { if (s) await s.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok);
console.log(`${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
