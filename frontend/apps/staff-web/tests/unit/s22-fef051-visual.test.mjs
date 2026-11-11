// FEF-051: pure parts of the visual-regression harness: screen inventory (every page is a screen or excluded with a reason),
// the matrix, and PR selection (feature folder -> its screens; packages/tokens, packages/ui -> the full matrix). No browser.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { SCREENS, screensOf, routesOf, EXCLUDED, STAFF_LOCALES, PATIENT_LOCALES, THEMES, STAFF_DEVICES, PATIENT_DEVICES, DEVICES } from '../e2e/visual/screens.mjs';
import { selectScreens } from '../e2e/visual/select.mjs';

const ids = (sel) => [...sel.ids].sort();
const staff = screensOf('staff');

test('inventory: every staff page.tsx is a screen or excluded with a reason, every patient page is a screen', () => {
  const routes = routesOf('staff').map((r) => r.route);
  for (const r of routes) assert.ok(staff.some((s) => s.route === r) || EXCLUDED[r], `${r} is neither snapshotted nor excluded`);
  for (const r of Object.keys(EXCLUDED)) assert.ok(routes.includes(r), `stale exclusion ${r}`);
  assert.equal(screensOf('patient').length, routesOf('patient').length);
  assert.equal(new Set(SCREENS.map((s) => s.id)).size, SCREENS.length, 'screen ids are unique (they are snapshot paths)');
  for (const s of SCREENS) assert.ok(!/[[\]]/.test(s.url) && s.url.startsWith('/'), `${s.id} url ${s.url} still has a dynamic segment`);
});

test('inventory: key screens, roles and the journeys whose mocks they reuse', () => {
  const by = Object.fromEntries(SCREENS.map((s) => [s.id, s]));
  assert.equal(by['staff/arrivals'].journey, 'J2');
  assert.equal(by['staff/consult'].url, '/consult?entry=e1&step=reason');
  assert.equal(by['staff/checkout/queueEntryId'].journey, 'J9');
  assert.equal(by['staff/login'].role, 'anonymous');
  assert.equal(by['staff/platform/health'].role, 'operator');
  assert.equal(by['staff/my-day'].role, 'doctor');
  assert.equal(by['staff/overview'].role, 'owner');
  assert.ok(by['staff/ui-gallery'], 'component gallery (packages/ui level) is a screen');
  assert.ok(!by['staff/qa-throw'] && !by['staff/slug']);
  assert.equal(by['patient/l/linkId'].role, 'anonymous');
});

test('matrix: en + hi on PRs (ar opt-in), both themes, 3 staff devices; patient in every patient locale on phone and desktop', () => {
  assert.deepEqual(STAFF_LOCALES, ['en', 'hi']);
  assert.deepEqual(THEMES, ['light', 'dark']);
  assert.deepEqual(STAFF_DEVICES, ['desktop', 'tablet', 'phone']);
  assert.deepEqual(PATIENT_DEVICES, ['phone', 'desktop']);
  assert.deepEqual(PATIENT_LOCALES, ['en', 'hi', 'kn', 'te']);
  assert.equal(DEVICES.phone.width, 375);
});

test('selection: a PR changing features/arrivals snapshots the arrivals screens only', () => {
  const sel = selectScreens(['frontend/apps/staff-web/app/(clinic)/arrivals/ArrivalsBoard.tsx']);
  assert.equal(sel.mode, 'some');
  assert.deepEqual(ids(sel), ['staff/arrivals']);
  assert.deepEqual(ids(selectScreens(['frontend/apps/staff-web/app/(clinic)/setup/charges/page.tsx'])), ['staff/setup/charges']);
  assert.ok(ids(selectScreens(['frontend/apps/staff-web/app/(clinic)/setup/layout.tsx'])).length > 10); // a section layout: every /setup screen
});

test('selection: tokens, ui, i18n, shared shell code and the harness run the full matrix', () => {
  for (const f of ['frontend/packages/tokens/src/colors.json', 'frontend/packages/ui/src/Button.tsx', 'frontend/packages/i18n/messages/hi/arrivals.json',
    'frontend/apps/staff-web/app/lib/session.ts', 'frontend/apps/staff-web/tests/e2e/visual/vr.mjs']) {
    const sel = selectScreens([f]);
    assert.equal(sel.mode, 'all', f);
    assert.equal(sel.ids.length, SCREENS.length, f);
  }
});

test('selection: a shared component folder selects the screens that name it; patient-web stays in patient-web; other paths select nothing', () => {
  const consult = ids(selectScreens(['frontend/apps/staff-web/app/components/consult/Panel.tsx']));
  assert.ok(consult.includes('staff/consult') && consult.includes('staff/solo/consult') && consult.every((i) => i.includes('consult')), consult.join());
  const patient = selectScreens(['frontend/apps/patient-web/app/components/LinkFlow.tsx']);
  assert.ok(patient.mode === 'some' && patient.ids.every((i) => i.startsWith('patient/')) && patient.ids.length === screensOf('patient').length);
  assert.equal(selectScreens(['backend/src/main/java/X.java', 'frontend/apps/staff-web/tests/unit/x.test.mjs', '']).mode, 'none');
});
