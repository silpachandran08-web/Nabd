// FEF-049: pure parts of the smoke harness (flaky quarantine, sharding, axe gating, role permissions).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { record, quarantine, blocking, report, THRESHOLD, WINDOW_DAYS } from '../e2e/smoke/flaky.mjs';
import { readFileSync } from 'node:fs';
import { shard, blockingViolations, axeReport, permsFor, ROLES, applyKnown } from '../e2e/smoke/core.mjs';

const NOW = '2026-10-19T12:00:00.000Z';
const daysAgo = (d) => new Date(Date.parse(NOW) - d * 86400000).toISOString();
const flakyRun = (id) => ({ id, owner: '@nabd/engineering', status: 'flaky', attempts: [{ ok: false, error: 'timeout' }, { ok: true }] });

test('a case flaky 3 times within 5 days is quarantined with its owner', () => {
  const h = [daysAgo(4.5), daysAgo(2), daysAgo(1)].map((at) => ({ id: 'J9 IN desktop', owner: '@nabd/engineering', at }));
  const q = quarantine(h, NOW);
  assert.equal(q.length, 1);
  assert.deepEqual({ id: q[0].id, owner: q[0].owner, occurrences: q[0].occurrences }, { id: 'J9 IN desktop', owner: '@nabd/engineering', occurrences: 3 });
});

test('boundary: 2 occurrences, or the third one older than 5 days, is not quarantined', () => {
  assert.equal(THRESHOLD, 3); assert.equal(WINDOW_DAYS, 5);
  const two = [daysAgo(1), daysAgo(2)].map((at) => ({ id: 'J1 IN mobile', owner: 'o', at }));
  assert.deepEqual(quarantine(two, NOW), []);
  const old = [...two, { id: 'J1 IN mobile', owner: 'o', at: daysAgo(5.1) }];
  assert.deepEqual(quarantine(old, NOW), []);
});

test('record appends only flaky results and prunes history older than 30 days', () => {
  const h = record([{ id: 'x', owner: 'o', at: daysAgo(31) }, { id: 'y', owner: 'o', at: daysAgo(3) }],
    [flakyRun('J5 KSA desktop'), { id: 'J6 IN desktop', status: 'passed', attempts: [{ ok: true }] }, { id: 'J6 IN mobile', status: 'failed', attempts: [{ ok: false }] }], NOW);
  assert.deepEqual(h.map((e) => e.id), ['y', 'J5 KSA desktop']);
});

test('third flaky run in this very run quarantines the case; quarantined failures do not block the gate', () => {
  const prior = [daysAgo(1), daysAgo(2)].map((at) => ({ id: 'J5 KSA desktop', owner: '@nabd/clinical-owners', at }));
  const q = quarantine(record(prior, [flakyRun('J5 KSA desktop')], NOW), NOW);
  assert.equal(q[0]?.id, 'J5 KSA desktop');
  const results = [{ id: 'J5 KSA desktop', status: 'failed', owner: 'x', attempts: [{ ok: false, error: 'e' }] }, { id: 'J9 IN desktop', status: 'failed', owner: 'y', attempts: [{ ok: false, error: 'boom' }] }];
  assert.deepEqual(blocking(results, q).map((r) => r.id), ['J9 IN desktop']);
  const md = report(results, q, 123000);
  assert.match(md, /J5 KSA desktop \| failed \(quarantined\)/);
  assert.match(md, /Quarantined/);
  assert.match(md, /123\.0 s \(budget 480 s\)/);
});

test('flaky results are reported as flaky, not as passed', () => {
  assert.match(report([flakyRun('J1 KSA mobile')], [], 1000), /0 passed, 1 flaky, 0 failed/);
});

test('shards are disjoint, cover every case and stay balanced', () => {
  const cases = Array.from({ length: 16 }, (_, i) => i);
  const parts = [1, 2, 3, 4].map((i) => shard(cases, i, 4));
  assert.deepEqual(parts.flat().sort((a, b) => a - b), cases);
  assert.ok(parts.every((p) => p.length === 4));
  assert.throws(() => shard(cases, 0, 4));
  assert.throws(() => shard(cases, 5, 4));
});

test('only critical and serious axe violations block, with a readable report', () => {
  const v = [
    { id: 'color-contrast', impact: 'serious', help: 'Elements must meet contrast', helpUrl: 'https://dequeuniversity.com/rules/axe/4.10/color-contrast', nodes: [{ target: ['button.x'] }] },
    { id: 'region', impact: 'moderate', help: 'Content in landmarks', helpUrl: 'u', nodes: [{ target: ['div'] }] },
    { id: 'button-name', impact: 'critical', help: 'Buttons must have discernible text', helpUrl: 'u2', nodes: [{ target: ['#a'] }, { target: ['#b'] }] },
  ];
  const b = blockingViolations(v);
  assert.deepEqual(b.map((x) => x.id), ['color-contrast', 'button-name']);
  const txt = axeReport('J9 IN desktop', [{ step: 'paid', url: '/checkout/q', violations: b }, { step: 'open', url: '/checkout/q', violations: [] }]);
  assert.match(txt, /J9 IN desktop · step "paid" \(\/checkout\/q\): 2 critical\/serious axe violation/);
  assert.match(txt, /\[serious\] color-contrast: Elements must meet contrast\n\s+button\.x/);
  assert.doesNotMatch(txt, /region|step "open"/);
  assert.equal(axeReport('x', [{ step: 's', url: '/', violations: [] }]), '');
});

test('role permissions follow the RBAC matrix (receptionist has no clinical access; doctor cannot create billing)', () => {
  const rec = permsFor('receptionist'), doc = permsFor('doctor');
  assert.ok(rec.includes('queue:create') && rec.includes('billing:create') && !rec.some((p) => p.startsWith('clinical:')));
  assert.ok(doc.includes('clinical:edit') && doc.includes('billing:view') && !doc.includes('billing:create'));
  assert.ok(permsFor('owner').includes('clinical:refundDiscount') && ROLES.owner.builtIn);
  assert.throws(() => permsFor('janitor'));
});

test('a known pre-existing violation stops blocking only until it expires', () => {
  const v = [{ id: 'color-contrast', impact: 'serious', help: 'h', helpUrl: 'u', nodes: ['.flags > .pill_sync (ratio 3.9)', 'button.other (ratio 2.1)'] }];
  const known = [{ rule: 'color-contrast', selector: 'pill_sync', owner: '@nabd/engineering', added: '2026-10-19', expires: '2026-11-18' }];
  const live = applyKnown(v, known, '2026-10-20');
  assert.deepEqual(live.blocking[0].nodes, ['button.other (ratio 2.1)']);
  assert.deepEqual(live.known[0].nodes, ['.flags > .pill_sync (ratio 3.9)']);
  assert.equal(applyKnown(v, known, '2026-11-19').blocking[0].nodes.length, 2);   // expired: blocks again
  assert.equal(applyKnown(v, [{ ...known[0], rule: 'button-name' }], '2026-10-20').blocking[0].nodes.length, 2);  // other rule: no effect
});

test('axe-known.json entries have an owner, a reason and expire within 30 days', () => {
  const { entries } = JSON.parse(readFileSync(new URL('../e2e/smoke/axe-known.json', import.meta.url)));
  for (const e of entries) {
    assert.ok(e.rule && e.selector && e.owner?.startsWith('@') && e.reason, JSON.stringify(e));
    assert.ok((Date.parse(e.expires) - Date.parse(e.added)) / 86400000 <= 30, `${e.rule} ${e.selector} expires too late`);
  }
});
