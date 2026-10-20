// CARRY-12 E2E: drug picker without an empty listbox (keyboard flow intact), consult contrast clean in axe, and one
// clinician control per staff row (the CLN-005 dialog). Reuses the FEF-049 smoke harness (mocks, axe, sign-in).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3714 node s12-carry-a11y.mjs
import { open, signIn, axeStep, noHorizontalScroll, consoleNoise } from './smoke/harness.mjs';
import { JOURNEYS, TENANTS } from './smoke/journeys.mjs';
import { ConsultPage } from './smoke/pages.mjs';
import { launch, seedClinic, mockApi, check, results, APP } from './qa-lib.mjs';

const SUGGEST = [
  { name: 'Synthocillin 250 mg', source: 'pharmacy', inStock: true, prescriptionOnly: true, code: 'S1' },
  { name: 'Synthocillin 500 mg', source: 'pharmacy', inStock: false, prescriptionOnly: true, code: 'S2' },
  { name: 'Synthoprofen 200 mg', source: 'history', code: null },
];
const drugSearch = ['GET', /^\/clinical\/drugs\/search\?q=([^&]*)/, (r, _req, m) => {
  const q = decodeURIComponent(m[1]).toLowerCase();
  if (q === 'errx') return r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ title: 'x', detail: 'boom' }) });
  return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(SUGGEST.filter((x) => x.name.toLowerCase().startsWith(q))) });
}];
const j6 = JOURNEYS.find((j) => j.id === 'J6');
const blockingIds = (e) => e.violations.map((v) => `${v.id}: ${v.nodes.join(', ')}`);

async function consult(viewport) {
  const tenant = TENANTS[0];
  const d = j6.prepare(tenant, 'desktop', 1);
  const s = await open({ viewport, mocks: [drugSearch, ...d.h] });
  await signIn(s.page, 'doctor', tenant);
  const c = new ConsultPage(s.page);
  await c.open(d.entryId);
  await c.step('Plan');
  return { s, c, field: s.page.locator('#rx-drug-0') };
}
const attr = (loc, a) => loc.getAttribute(a);
const typeIn = async (field, text) => { await field.fill(''); await field.pressSequentially(text); await field.page().waitForTimeout(600); };

let s;
try {
  // A. drug picker
  let c, field;
  ({ s, c, field } = await consult({ width: 1280, height: 900 }));
  await typeIn(field, 'zz');
  await s.page.getByText(/No match in your pharmacy list/).first().waitFor({ timeout: 5000 });
  check('A1 no suggestions: no role=listbox in the DOM', await s.page.locator('[role=listbox]').count() === 0);
  check('A2 no suggestions: combobox aria-expanded=false', await attr(field, 'aria-expanded') === 'false');
  check('A3 no suggestions: the "no match" note is still shown and announced', /No match/.test(await s.page.getByRole('status').filter({ hasText: /No match/ }).first().innerText().catch(() => '')));
  let ax = await axeStep(s.page, 'no suggestions', []);
  check('A4 axe: no aria-required-children and no blocking violation with no suggestions', ax.violations.length === 0 && ax.known.length === 0, blockingIds(ax).join(' | '));

  await typeIn(field, 'synth');
  const list = s.page.getByRole('listbox');
  await list.waitFor({ timeout: 5000 });
  check('A5 suggestions: listbox with 3 options, aria-expanded=true', await list.getByRole('option').count() === 3 && await attr(field, 'aria-expanded') === 'true');
  await field.press('ArrowDown'); await field.press('ArrowDown');
  const second = list.getByRole('option').nth(1);
  check('A6 ArrowDown x2: aria-activedescendant is option 2 and it is aria-selected',
    await attr(field, 'aria-activedescendant') === await attr(second, 'id') && await attr(second, 'aria-selected') === 'true');
  await field.press('ArrowUp'); await field.press('ArrowUp');
  check('A7 ArrowUp wraps to the last option', await attr(field, 'aria-activedescendant') === await attr(list.getByRole('option').nth(2), 'id'));
  ax = await axeStep(s.page, 'suggestions open', []);
  check('A8 axe clean with the listbox open', ax.violations.length === 0, blockingIds(ax).join(' | '));
  await field.press('ArrowDown'); // wraps to option 1
  await field.press('Enter');
  check('A9 Enter picks the active option, listbox closes', await field.inputValue() === 'Synthocillin 250 mg' && await s.page.locator('[role=listbox]').count() === 0 && await attr(field, 'aria-expanded') === 'false');
  await typeIn(field, 'synth'); await list.waitFor();
  await field.press('Escape');
  check('A10 Escape closes the listbox and keeps the typed text', await s.page.locator('[role=listbox]').count() === 0 && await field.inputValue() === 'synth');
  await typeIn(field, 'errx');
  await s.page.getByText(/Suggestions are unavailable/).first().waitFor({ timeout: 5000 });
  check('A11 search error: note shown, no listbox, aria-expanded=false', await s.page.locator('[role=listbox]').count() === 0 && await attr(field, 'aria-expanded') === 'false');
  await typeIn(field, 's');
  check('A12 boundary: 1 character does not search or show a popup', await s.page.locator('[role=listbox]').count() === 0 && await attr(field, 'aria-expanded') === 'false');

  // B. consult safety colours: allergy strip, danger pill, Rx allergy warning, light and dark colour schemes
  await typeIn(field, 'Synthamoxil');
  await field.press('Escape');
  await c.rxSave(); await c.allergyWarning().waitFor();
  for (const scheme of ['dark', 'light']) {
    await s.page.emulateMedia({ colorScheme: scheme });
    ax = await axeStep(s.page, `allergy warning ${scheme}`, []);
    const contrast = ax.violations.filter((v) => v.id === 'color-contrast');
    check(`B1 ${scheme} scheme: no color-contrast violation on the consult screen (safety strip, pills, Rx warning)`, contrast.length === 0, blockingIds({ violations: contrast }).join(' | '));
    check(`B2 ${scheme} scheme: no blocking axe violation at all`, ax.violations.length === 0, blockingIds(ax).join(' | '));
  }
  check('B3 zero console errors (desktop consult)', consoleNoise(s.log).length === 0, consoleNoise(s.log).join(' | '));
  await s.browser.close();

  ({ s, field } = await consult({ width: 390, height: 844 }));
  await typeIn(field, 'synth'); await s.page.getByRole('listbox').waitFor();
  check('A13 390px: no horizontal page scroll with suggestions open', await noHorizontalScroll(s.page));
  check('A14 390px: zero console errors', consoleNoise(s.log).length === 0, consoleNoise(s.log).join(' | '));
  await s.browser.close();

  // C. staff page: exactly one clinician control per row
  const st = { calls: [], licence: { isClinician: false, licenceExpiresOn: null, licenceOverrideUntil: null } };
  const people = [
    { id: 's1', email: 'a@x.test', name: 'Dr Asha', mobilePhone: '+910000000001', roleId: 'r1', status: 'active', scope: 'all_clinic_patients', lastSeenAt: null, professionalRegistrationNo: 'KMC-1', clinician: true },
    { id: 's2', email: 'b@x.test', name: 'Bala Front', mobilePhone: '+910000000002', roleId: 'r1', status: 'active', scope: 'all_clinic_patients', lastSeenAt: null, professionalRegistrationNo: null, clinician: false },
  ];
  const staffMocks = (o = {}) => [
    ['GET', /^\/staff\?/, { body: { data: people, page: { nextCursor: null, limit: 50 } } }],
    ['GET', /^\/staff\/clinician-review/, { body: [] }],
    ['GET', /^\/roles$/, { body: [{ id: 'r1', name: 'Doctor', builtIn: false, grants: [], mfaRequired: false }] }],
    ['GET', /^\/staff\/s2\/clinical-licence$/, { body: st.licence }],
    ['PATCH', /^\/staff\/s2$/, (r, req) => { st.calls.push('PATCH ' + req.postData()); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...people[1], professionalRegistrationNo: JSON.parse(req.postData()).professionalRegistrationNo }) }); }],
    ['PUT', /^\/staff\/s2\/clinical-licence$/, (r, req) => { st.calls.push('PUT ' + req.postData());
      return o.putFail ? r.fulfill({ status: 422, contentType: 'application/problem+json', body: JSON.stringify({ title: 'x', detail: 'A registration number is required for a clinician.' }) })
        : r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...st.licence, isClinician: true }) }); }],
    ['GET', /./, { body: [] }],
  ];
  const staffPage = async (perms, extra, o, viewport) => {
    const x = await launch({ viewport });
    await seedClinic(x.page, perms, extra);
    await mockApi(x.page, x.log, staffMocks(o));
    await x.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
    return x;
  };
  const OWNER = ['staff:view', 'staff:create', 'staff:edit', 'staff:delete'];
  s = await staffPage(OWNER, { owner: true, roleName: 'Owner' });
  const rows = s.page.locator('table').first().locator('tbody tr');
  const perRow = [];
  for (let i = 0; i < await rows.count(); i++) perRow.push(await rows.nth(i).getByRole('button', { name: /clinician/i }).count());
  check('C1 owner: exactly one clinician control in every staff row', perRow.length === 2 && perRow.every((n) => n === 1), JSON.stringify(perRow));
  check('C2 the separate Clinician: on/off toggle is gone', await s.page.getByRole('button', { name: /Clinician: (on|off)/ }).count() === 0);
  await rows.nth(1).getByRole('button', { name: 'Add reg. no. & clinician' }).click();
  await s.page.getByLabel(/Registered clinician/).check();
  await s.page.getByRole('button', { name: 'Save', exact: true }).click();
  check('C3 licence rule kept: clinician without a number is refused, nothing sent',
    /Add the registration number before/.test(await s.page.getByRole('alert').first().innerText().catch(() => '')) && st.calls.length === 0);
  await s.page.getByLabel(/Medical registration number/).fill('KMC 777');
  await s.page.getByRole('button', { name: 'Save', exact: true }).click();
  await s.page.getByRole('dialog').waitFor({ state: 'detached', timeout: 5000 }).catch(() => {});
  check('C4 saved number then licence (PATCH, PUT isClinician=true)', st.calls.length === 2 && st.calls[0].startsWith('PATCH') && /"isClinician":true/.test(st.calls[1]), st.calls.join(' | '));
  await s.page.waitForTimeout(300);
  const cell = await rows.nth(1).locator('td').first().innerText();
  check('C5 the row shows the Clinician pill after saving', /Clinician/i.test(cell), cell.replace(/\s+/g, ' '));
  check('C6 zero console errors (staff)', consoleNoise(s.log).length === 0, consoleNoise(s.log).join(' | '));
  await s.browser.close();

  st.calls = [];
  s = await staffPage(OWNER, { owner: true, roleName: 'Owner' }, { putFail: true });
  await s.page.locator('table').first().locator('tbody tr').nth(1).getByRole('button', { name: /clinician/i }).click();
  await s.page.getByLabel(/Registered clinician/).check();
  await s.page.getByLabel(/Medical registration number/).fill('KMC 778');
  await s.page.getByRole('button', { name: 'Save', exact: true }).click();
  await s.page.getByText('A registration number is required for a clinician.').waitFor({ timeout: 5000 }).then(() => check('C7 server refusal detail shown in the dialog', true), () => check('C7 server refusal detail shown in the dialog', false));
  await s.browser.close();

  s = await staffPage(['staff:view'], { owner: false });
  check('C8 permission absent (staff:view only): no clinician control at all', await s.page.getByRole('button', { name: /clinician|reg\. no\./i }).count() === 0);
  await s.browser.close();

  s = await staffPage(OWNER, { owner: true, roleName: 'Owner' }, {}, { width: 390, height: 844 });
  check('C9 390px staff: no horizontal page scroll', await noHorizontalScroll(s.page));
} catch (e) { check('run', false, String(e).split('\n').slice(0, 3).join(' ')); }
finally { if (s) await s.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok);
console.log(`${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
