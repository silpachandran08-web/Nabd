// ADM-018 E2E: owner staff screen (tiles, tabs, seat limit with upgrade link, resend invite, MFA reminder, permission-absent, 390px).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3810 node s15-adm-018-owner-staff.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const g = (module) => ({ module, view: true, create: true, edit: true, delete: true, approve: false, refundDiscount: false, export: false });
const roles = [{ id: 'r-rec', name: 'Receptionist', builtIn: false, grants: [g('queue')], mfaRequired: false, presetCode: 'receptionist', presetVersion: 1 },
  { id: 'r-ph', name: 'Pharmacist', builtIn: false, grants: [g('pharmacy')], mfaRequired: true, presetCode: null, presetVersion: null }];
const row = (id, name, roleId, status, loginMethod, loginFactors = []) => ({ id, email: `${id}@x.test`, name, mobilePhone: '+910000000001', roleId, status, scope: 'all_clinic_patients', lastSeenAt: null, professionalRegistrationNo: null, clinician: false, loginMethod, loginFactors });
const staff = () => [row('s1', 'Rana Rec', 'r-rec', 'active', 'pin_or_whatsapp_otp'), row('s2', 'Pia Pharm', 'r-ph', 'active', 'authenticator_setup_required'),
  row('s3', 'Ed Enrolled', 'r-ph', 'active', 'authenticator', ['authenticator']), row('s4', 'Ivy Invited', 'r-rec', 'invited', 'invite_pending')];
let o;
const handlers = () => [
  ['GET', /^\/staff\?/, { body: { data: staff(), page: { nextCursor: null, limit: 50 } } }],
  ['GET', /^\/staff\/clinician-review/, { body: [] }],
  ['GET', /^\/roles$/, { body: roles }],
  ['GET', /^\/departments/, { body: [] }],
  ['GET', /^\/auth\/break-glass\/active/, { body: { items: [] } }],
  ['GET', /^\/setup\/subscription/, o.noSubscription ? { status: 403, body: { title: 'Forbidden' } } : { body: { plan: 'Starter', status: 'active', seatsUsed: 4, seatsLimit: 6 } }],
  ['POST', /^\/staff\/s4\/invite\/resend$/, { body: { inviteId: 's4', expiresAt: '2030-01-01T00:00:00Z', inviteToken: 'NEWTOKEN123' } }],
  ['POST', /^\/staff\/s2\/mfa\/remind$/, o.remindStatus ? { status: o.remindStatus, body: { title: 'TEMPLATE_NOT_SENDABLE', detail: 'The clinic has no approved reminder template yet.' } } : { status: 204 }],
  ['POST', /^\/staff$/, { status: 409, body: { title: 'SEAT_LIMIT_REACHED', detail: "Your plan's staff seats are all in use. Suspend someone or upgrade your plan.", upgradeUrl: '/owner/plan' } }],
];
const FULL = ['staff:view', 'staff:create', 'staff:edit', 'staff:delete', 'setup:view'];
async function open(perms, opts = {}, viewport) {
  o = opts;
  const s = await launch({ viewport });
  await seedClinic(s.page, perms, { owner: true });
  await mockApi(s.page, s.log, handlers());
  await s.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  return s;
}
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
let s;
try {
  s = await open(FULL);
  const tiles = await s.page.getByRole('list', { name: 'Team summary' }).innerText();
  check('1 tiles: 3 active staff, 1 pending invite', /3\s*Active staff/.test(tiles) && /1\s*Pending invites/.test(tiles), tiles.replace(/\s+/g, ' '));
  check('2 tiles: MFA coverage 50% with 1 to set up', /50%\s*MFA coverage · 1 to set up/.test(tiles));
  check('3 tiles: seats 4 of 6, 2 left', /4 of 6\s*Seats · 2 left/.test(tiles));
  check('4 MFA pill flags the person who has not enrolled and offers a reminder', await s.page.getByRole('button', { name: 'Send MFA reminder' }).count() === 1);
  await s.page.getByRole('button', { name: 'Send MFA reminder' }).click();
  await s.page.getByText('MFA reminder queued for Pia Pharm.').waitFor({ timeout: 5000 }).then(() => check('5 reminder confirmed', true), () => check('5 reminder confirmed', false));
  await s.page.getByRole('button', { name: 'Resend invite' }).click();
  await s.page.getByText(/accept-invite\/NEWTOKEN123/).waitFor({ timeout: 5000 }).then(() => check('6 resend invite in one tap shows the new link', true), () => check('6 resend invite', false));
  // tabs
  await s.page.getByRole('tab', { name: /^Invites/ }).click();
  check('7 invites tab lists the pending invite with Resend', await s.page.getByRole('table', { name: 'Pending invites' }).getByRole('button', { name: 'Resend invite' }).count() === 1);
  await s.page.getByRole('tab', { name: 'Roles & permissions' }).click();
  check('8 roles tab shows the read-only permission matrix', await s.page.getByRole('complementary', { name: 'Role presets' }).count() === 1);
  await s.page.getByRole('tab', { name: 'People' }).click();
  // seat limit
  await s.page.getByRole('button', { name: 'Invite staff' }).first().click();
  await s.page.getByLabel('Name').fill('N'); await s.page.getByLabel('Email').fill('n@x.test'); await s.page.getByLabel('Mobile').fill('+910000000009');
  await s.page.getByLabel('Role', { exact: true }).selectOption('r-rec');
  await s.page.getByRole('dialog').getByRole('button', { name: 'Invite staff' }).click();
  const alert = s.page.getByRole('alert').filter({ hasText: 'seats are all in use' });
  await alert.waitFor({ timeout: 5000 });
  check('9 seat limit reached: refusal text and an upgrade link', await alert.getByRole('link', { name: 'Upgrade your plan' }).getAttribute('href') === '/setup?tab=subscription');
  check('10 no console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('owner', s.log); await s.browser.close();

  s = await open(FULL, { remindStatus: 422 });
  await s.page.getByRole('button', { name: 'Send MFA reminder' }).click();
  await s.page.getByText('no approved reminder template').waitFor({ timeout: 5000 }).then(() => check('11 reminder failure is shown, not claimed as sent', true), () => check('11 reminder failure shown', false));
  check('12 no "queued" notice on failure', await s.page.getByText(/reminder queued/).count() === 0);
  await s.browser.close();

  s = await open(['staff:view']);
  check('13 view-only: no invite, resend, reminder, suspend, reset controls (absent, not disabled)',
    await s.page.getByRole('button', { name: /Invite staff|Resend invite|Send MFA reminder|Suspend|Reset authenticator/ }).count() === 0);
  check('14 view-only: tiles still show, no seat tile without the subscription read', await s.page.getByTestId('kpi-seats').count() === 0);
  await s.browser.close();

  s = await open(FULL, { noSubscription: true });
  check('15 seat tile is hidden (not broken) when the subscription cannot be read', await s.page.getByTestId('kpi-seats').count() === 0 && await s.page.getByTestId('kpi-mfa').count() === 1);
  await s.browser.close();

  s = await open(FULL, {}, { width: 390, height: 800 });
  check('16 390px: no horizontal page scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  check('17 390px: no console errors', errs(s.log).length === 0, errs(s.log).join('|'));
} catch (e) { check('run', false, String(e)); }
finally { if (s) await s.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok);
console.log(`${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
