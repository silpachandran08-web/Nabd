// Independent QA (S12 CARRY-12): rendered contrast of the six baselined classes measured in the browser (both colour schemes),
// full axe on consult + staff screens, keyboard drug picker, one clinician control per staff row for owner / manager / viewer.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3811 node tests/e2e/s12qa-carry-12.mjs
import { open, signIn, axeStep, consoleNoise } from './smoke/harness.mjs';
import { JOURNEYS, TENANTS } from './smoke/journeys.mjs';
import { ConsultPage } from './smoke/pages.mjs';
import { launch, seedClinic, mockApi, check, results, APP } from './qa-lib.mjs';

const CLASSES = ['pill_sync', 'pill_danger', 'safetyLabel', 'safetyValue', 'noticeDanger', 'warnBody'];
const SUGGEST = [
  { name: 'Qadrug 100 mg', source: 'pharmacy', inStock: true, prescriptionOnly: true, code: 'Q1' },
  { name: 'Qadrug 200 mg', source: 'pharmacy', inStock: true, prescriptionOnly: true, code: 'Q2' },
];
const drugSearch = ['GET', /^\/clinical\/drugs\/search\?q=([^&]*)/, (r, _req, m) => r.fulfill({ status: 200, contentType: 'application/json',
  body: JSON.stringify(SUGGEST.filter((x) => x.name.toLowerCase().startsWith(decodeURIComponent(m[1]).toLowerCase()))) })];

/** Min WCAG ratio per class: text colour (alpha-blended) against the composited ancestor backgrounds. */
const measure = (page) => page.evaluate((classes) => {
  const parse = (c) => { const m = c.match(/[\d.]+/g)?.map(Number) ?? [0, 0, 0]; return [m[0], m[1], m[2], m.length > 3 ? m[3] : 1]; };
  const blend = (top, bot) => { const a = top[3]; return [0, 1, 2].map((i) => top[i] * a + bot[i] * (1 - a)).concat(1); };
  const lum = (c) => { const [r, g, b] = c.slice(0, 3).map((v) => v / 255).map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4)); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
  const bgOf = (el) => {
    const layers = [];
    for (let n = el; n; n = n.parentElement) { const c = parse(getComputedStyle(n).backgroundColor); if (c[3] > 0) layers.push(c); if (c[3] >= 1) break; }
    let bg = [255, 255, 255, 1];
    if (layers.length && layers[layers.length - 1][3] >= 1) bg = layers.pop();
    for (let i = layers.length - 1; i >= 0; i--) bg = blend(layers[i], bg);
    return bg;
  };
  const out = {};
  for (const cls of classes) {
    for (const root of document.querySelectorAll(`[class*="${cls}"]`)) {
      const texts = [root, ...root.querySelectorAll('*')].filter((e) => [...e.childNodes].some((t) => t.nodeType === 3 && t.textContent.trim()) && e.getClientRects().length);
      for (const e of texts) {
        const bg = bgOf(e); const fg = blend(parse(getComputedStyle(e).color), bg);
        const [a, b] = [lum(fg), lum(bg)].sort((x, y) => y - x);
        const r = (a + 0.05) / (b + 0.05);
        if (!(cls in out) || r < out[cls].ratio) out[cls] = { ratio: Math.round(r * 100) / 100, text: e.textContent.trim().slice(0, 30) };
      }
    }
  }
  return out;
}, CLASSES);
const fullAxe = async (page, label) => { await axeStep(page, label, []); return page.evaluate(() => window.axe.run(document, { resultTypes: ['violations'] })); };
const ids = (r) => r.violations.map((v) => `${v.id}(${v.impact}):${v.nodes.length}` + (['critical', 'serious'].includes(v.impact) ? ` [${v.nodes.slice(0, 2).map((n) => n.target.join(' ') + ' ' + (n.any[0]?.message ?? '')).join(' ; ')}]` : '')).join(' ');

let s;
try {
  // 1. consult screen: allergy strip + Rx allergy warning, both colour schemes
  const tenant = TENANTS[0];
  const d = JOURNEYS.find((j) => j.id === 'J6').prepare(tenant, 'desktop', 1);
  s = await open({ viewport: { width: 1280, height: 900 }, mocks: [drugSearch, ...d.h] });
  await signIn(s.page, 'doctor', tenant);
  const c = new ConsultPage(s.page);
  await c.open(d.entryId);
  await c.prescribe('Synthamoxil');
  await c.rxSave(); await c.allergyWarning().waitFor();
  const seen = new Set();
  for (const scheme of ['dark', 'light']) {
    await s.page.emulateMedia({ colorScheme: scheme });
    const m = await measure(s.page);
    Object.keys(m).forEach((k) => seen.add(k));
    const low = Object.entries(m).filter(([, v]) => v.ratio < 4.5);
    check(`K1 ${scheme}: every rendered text of the baselined classes is >= 4.5:1`, low.length === 0, JSON.stringify(m));
    const ax = await fullAxe(s.page, `consult ${scheme}`);
    check(`K2 ${scheme}: axe (all impacts) has no color-contrast violation on consult`, !ax.violations.some((v) => v.id === 'color-contrast'), ids(ax));
    check(`K3 ${scheme}: axe has no critical/serious violation on consult`, !ax.violations.some((v) => ['critical', 'serious'].includes(v.impact)), ids(ax));
  }
  check('K4 measured at least the safety strip, danger notice and warning card classes', ['safetyLabel', 'safetyValue', 'warnBody'].every((k) => seen.has(k)), [...seen].join(','));
  const danger = await s.page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--nb-danger-500').trim());
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(danger.slice(i, i + 2), 16));
  check('K5 danger token stays red (R dominant, hue within red family)', r > 200 && r - g > 100 && r - b > 80, danger);

  // 2. drug picker keyboard flow (independent)
  const field = s.page.locator('#rx-drug-0');
  await field.fill(''); await field.pressSequentially('qad'); await s.page.waitForTimeout(700);
  const list = s.page.getByRole('listbox');
  await list.waitFor({ timeout: 5000 });
  check('D1 combobox role, aria-expanded=true and aria-controls points at the listbox', await field.getAttribute('role') === 'combobox'
    && await field.getAttribute('aria-expanded') === 'true' && await field.getAttribute('aria-controls') === await list.getAttribute('id'));
  check('D2 no active option before arrowing (Enter must not pick blindly)', !(await field.getAttribute('aria-activedescendant')));
  await field.press('ArrowDown');
  const first = list.getByRole('option').first();
  check('D3 ArrowDown activates option 1 (aria-activedescendant + aria-selected)', await field.getAttribute('aria-activedescendant') === await first.getAttribute('id') && await first.getAttribute('aria-selected') === 'true');
  await field.press('ArrowDown');
  const ids2 = await list.getByRole('option').evaluateAll((els) => els.map((e) => e.id));
  check('D4 option ids are unique and non-empty', new Set(ids2).size === ids2.length && ids2.every(Boolean), ids2.join(','));
  await field.press('Enter');
  check('D5 Enter selects option 2 and closes the listbox', await field.inputValue() === 'Qadrug 200 mg' && await s.page.locator('[role=listbox]').count() === 0
    && await field.getAttribute('aria-expanded') === 'false', await field.inputValue());
  check('D6 focus stays on the drug field after selection', await s.page.evaluate(() => document.activeElement?.id) === 'rx-drug-0');
  await field.fill(''); await field.pressSequentially('zzq'); await s.page.waitForTimeout(700);
  check('D7 no suggestions: no element with role=listbox, aria-expanded=false', await s.page.locator('[role=listbox]').count() === 0 && await field.getAttribute('aria-expanded') === 'false');
  const ax2 = await s.page.evaluate(() => window.axe.run(document, { runOnly: ['aria-required-children', 'aria-valid-attr-value'] }));
  check('D8 axe aria-required-children / aria-valid-attr-value clean with no suggestions', ax2.violations.length === 0, ids(ax2));
  check('D9 zero console errors on consult', consoleNoise(s.log).length === 0, consoleNoise(s.log).join(' | '));
  await s.browser.close(); s = null;

  // 3. staff page: one clinician control per row; owner, non-owner manager with staff:edit, viewer
  const people = [
    { id: 's1', email: 'a@x.test', name: 'Dr Lina', mobilePhone: '+971501111111', roleId: 'r1', status: 'active', scope: 'all_clinic_patients', lastSeenAt: null, professionalRegistrationNo: 'DHA-1', clinician: true },
    { id: 's2', email: 'b@x.test', name: 'Sam Desk', mobilePhone: '+971502222222', roleId: 'r1', status: 'active', scope: 'all_clinic_patients', lastSeenAt: null, professionalRegistrationNo: null, clinician: false },
    { id: 's3', email: 'c@x.test', name: 'Dr Old', mobilePhone: '+971503333333', roleId: 'r1', status: 'active', scope: 'all_clinic_patients', lastSeenAt: null, professionalRegistrationNo: null, clinician: true },
  ];
  const staff = async (perms, extra) => {
    const x = await launch();
    await seedClinic(x.page, perms, extra);
    await mockApi(x.page, x.log, [
      ['GET', /^\/staff\?/, { body: { data: people, page: { nextCursor: null, limit: 50 } } }],
      ['GET', /^\/staff\/clinician-review/, { body: [] }],
      ['GET', /^\/roles$/, { body: [{ id: 'r1', name: 'Doctor', builtIn: false, grants: [], mfaRequired: false }] }],
      ['GET', /^\/staff\/s\d\/clinical-licence$/, { body: { isClinician: false, licenceExpiresOn: null, licenceOverrideUntil: null } }],
      ['GET', /./, { body: [] }],
    ]);
    await x.page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
    return x;
  };
  const perRow = async (page) => { const rows = page.locator('table').first().locator('tbody tr'); const out = [];
    for (let i = 0; i < await rows.count(); i++) out.push(await rows.nth(i).getByRole('button', { name: /clinician|Clinician:/i }).count()); return out; };
  for (const [who, perms, extra] of [['owner', ['staff:view', 'staff:create', 'staff:edit', 'staff:delete'], { owner: true, roleName: 'Owner' }],
    ['manager (staff:edit, not owner)', ['staff:view', 'staff:edit'], { owner: false, roleName: 'Clinic Manager' }]]) {
    s = await staff(perms, extra);
    const n = await perRow(s.page);
    check(`S1 ${who}: exactly one clinician control in every row (incl. a clinician with no reg. no.)`, n.length === 3 && n.every((k) => k === 1), JSON.stringify(n));
    check(`S2 ${who}: no aria-pressed toggle left on the row`, await s.page.locator('table').first().locator('button[aria-pressed]').count() === 0);
    const ax = await fullAxe(s.page, `staff ${who}`);
    check(`S3 ${who}: axe has no critical/serious violation on the staff page`, !ax.violations.some((v) => ['critical', 'serious'].includes(v.impact)), ids(ax));
    const btn = s.page.locator('table').first().locator('tbody tr').nth(1).getByRole('button', { name: /clinician/i });
    await btn.focus(); await s.page.keyboard.press('Enter');
    const dlg = s.page.getByRole('dialog');
    const opened = await dlg.waitFor({ timeout: 4000 }).then(() => true, () => false);
    check(`S4 ${who}: the control opens the licence dialog from the keyboard`, opened);
    if (opened) {
      const box = await btn.boundingBox();
      check(`S5 ${who}: control target is >= 44px high (ui note)`, (box?.height ?? 0) >= 44, `height=${box?.height}`);
      await s.page.keyboard.press('Escape');
    }
    check(`S6 ${who}: zero console errors`, consoleNoise(s.log).length === 0, consoleNoise(s.log).join(' | '));
    await s.browser.close(); s = null;
  }
  s = await staff(['staff:view'], { owner: false, roleName: 'Front desk' });
  check('S7 viewer (staff:view only): no clinician / reg. no. control anywhere', await s.page.getByRole('button', { name: /clinician|reg\. no\./i }).count() === 0);
  const axv = await fullAxe(s.page, 'staff viewer');
  check('S8 viewer: axe has no critical/serious violation', !axv.violations.some((v) => ['critical', 'serious'].includes(v.impact)), ids(axv));
} catch (e) { check('run', false, String(e).split('\n').slice(0, 3).join(' ')); }
finally { if (s) await s.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok);
console.log(`${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
