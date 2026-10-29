// FEF-050 / OPS-044: pure parts of the nightly journeys: SLA matrix, result export (JUnit + timings), role grants. No browser.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { SLA, JOURNEY_IDS, DEVICE_JOURNEYS, HI_JOURNEYS, DEVICES } from '../e2e/smoke/matrix.mjs';
import { toJunit, toTimings, toSummary, gateFailures, actualMs, slaMet } from '../e2e/smoke/report.mjs';
import { permsFor, PRESET_SUPPLEMENT, NO_GRANTS, ROLES } from '../e2e/smoke/core.mjs';

test('J1-J15 each have the time target of the journey definition (doc 05)', () => {
  assert.deepEqual(JOURNEY_IDS, Array.from({ length: 15 }, (_, i) => `J${i + 1}`));
  const sec = { J1: 45, J2: 20, J3: 30, J4: 30, J5: 2, J6: 0.5, J7: 10, J8: 60, J9: 60, J10: 30, J11: 90, J12: 120, J13: 60, J14: 10, J15: 120 };
  for (const [id, s] of Object.entries(sec)) assert.equal(SLA[id], s * 1000, id);
});

test('devices: J1, J2, J5, J8, J9 on tablet and phone; J1, J5, J9 in Hindi; desktop is 1366 wide', () => {
  assert.deepEqual(DEVICE_JOURNEYS, ['J1', 'J2', 'J5', 'J8', 'J9']);
  assert.deepEqual(HI_JOURNEYS, ['J1', 'J5', 'J9']);
  assert.equal(DEVICES.desktop.width, 1366); assert.equal(DEVICES.tablet.width, 768); assert.equal(DEVICES.phone.width, 375);
});

const r = (o) => ({ id: 'J10 IN desktop', journey: 'J10', title: 'OTC', suite: 'nightly', project: 'desktop', locale: 'en', tenant: 'IN', owner: '@nabd/engineering', budgetMs: 30000, budgetKey: 'otcMs', hazards: ['H-04'], quarantined: false,
  status: 'passed', attempts: [{ ok: true, ms: 2100, timings: { otcMs: 900 } }], ...o });

test('timings JSON: SLA, actual value, breach and hazard links per case', () => {
  const t = toTimings([r(), r({ id: 'J10 IN phone', attempts: [{ ok: true, ms: 1, timings: { otcMs: 31000 } }] }), r({ id: 'J2 IN desktop', status: 'skipped', attempts: [] })], { sha: 'abc' });
  assert.equal(t.sha, 'abc');
  assert.deepEqual(t.summary, { cases: 3, passed: 2, flaky: 0, failed: 0, skipped: 1, slaBreaches: 1 });
  assert.equal(t.cases[0].actualMs, 900); assert.equal(t.cases[0].slaMet, true); assert.deepEqual(t.cases[0].hazards, ['H-04']);
  assert.equal(t.cases[1].slaMet, false); assert.equal(t.cases[2].slaMet, null);
  assert.equal(actualMs(r({ attempts: [{ ok: false, error: 'x' }], status: 'failed' })), null);
  assert.equal(slaMet(r({ status: 'failed', attempts: [{ ok: false, ms: 5, timings: { otcMs: 1 } }] })), false);
});

test('JUnit: failure, skipped, flaky and hazard properties; XML is escaped', () => {
  const x = toJunit([r(), r({ id: 'J10 KSA desktop', status: 'failed', attempts: [{ ok: false, ms: 10, error: 'bad <b> & "q"\nsecond line' }] }),
    r({ id: 'J10 IN tablet', status: 'skipped', reason: 'no fixtures', attempts: [] }), r({ id: 'J10 IN phone', status: 'flaky', attempts: [{ ok: false, error: 'e1' }, { ok: true, ms: 5, timings: { otcMs: 1 } }] })], { sha: 'abc', release: 'v1.2.3' });
  assert.match(x, /<testsuites name="journeys" tests="4" failures="1" errors="0" release="v1.2.3" sha="abc">/);
  assert.match(x, /<testsuite name="J10" tests="4" failures="1" errors="0" skipped="1"/);
  assert.match(x, /<property name="hazard" value="H-04"\/>/);
  assert.match(x, /<failure message="bad &lt;b&gt; &amp; &quot;q&quot;">/);
  assert.match(x, /<skipped message="no fixtures"\/>/);
  assert.match(x, /<property name="flaky" value="true"\/>/);
  assert.equal((x.match(/<testcase /g) ?? []).length, 4);
});

test('gate: a failed journey blocks, a quarantined failure or a skipped case does not; summary lists the failure', () => {
  const rs = [r({ status: 'failed', attempts: [{ ok: false, ms: 1, error: 'boom' }] }), r({ id: 'q', status: 'failed', quarantined: true, attempts: [{ ok: false, error: 'x' }] }), r({ id: 's', status: 'skipped', attempts: [] })];
  assert.deepEqual(gateFailures(rs).map((x) => x.id), ['J10 IN desktop']);
  assert.match(toSummary(rs), /\| J10 IN desktop \| @nabd\/engineering \| boom \|/);
  assert.deepEqual(gateFailures([r()]), []);
});

test('role-absence sweep roles: every RBAC role plus a role with no grants', () => {
  assert.deepEqual(permsFor(NO_GRANTS), []);
  assert.deepEqual(Object.keys(ROLES).filter((k) => !k.startsWith('_')).sort(), ['doctor', 'front_desk_vitals', 'nurse', 'owner', 'receptionist']);
});

test('nursing / dental grants added to mock sign-in equal the ones in RolePresets.java', () => {
  const java = readFileSync(new URL('../../../../../backend/src/main/java/com/nabd/hms/staff/presets/RolePresets.java', import.meta.url), 'utf8');
  const FLAGS = ['view', 'create', 'edit', 'delete', 'approve', 'refundDiscount', 'export'];
  for (const [role, preset] of [['doctor', 'DOCTOR'], ['nurse', 'NURSE']]) {
    const block = java.slice(java.indexOf(`Preset ${preset} =`), java.indexOf('));', java.indexOf(`Preset ${preset} =`)));
    for (const [mod, flags] of Object.entries(PRESET_SUPPLEMENT[role])) {
      const row = new RegExp(`g\\("${mod}", "([YPN-]{7})"\\)`).exec(block)?.[1];
      assert.ok(row, `${preset} ${mod}`);
      assert.deepEqual(FLAGS.filter((_, i) => row[i] === 'Y'), flags, `${role} ${mod}`);
    }
  }
  assert.ok(permsFor('nurse').includes('nursing:edit') && !permsFor('receptionist').some((p) => p.startsWith('nursing:')));
});
