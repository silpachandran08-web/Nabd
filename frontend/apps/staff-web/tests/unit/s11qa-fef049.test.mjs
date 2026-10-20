// Independent QA (S11 FEF-049): flaky/quarantine gate through the real CLI, budgets, sharding, axe baseline policy.
// Pure Node (no browser): journeys.mjs is read as text because it imports Playwright through qa-lib.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { quarantine, record, blocking } from '../e2e/smoke/flaky.mjs';
import { shard, applyKnown, blockingViolations, axeReport } from '../e2e/smoke/core.mjs';

const FLAKY = new URL('../e2e/smoke/flaky.mjs', import.meta.url).pathname;
const DAY = 86400000;
const NOW = '2026-11-10T12:00:00.000Z';
const ago = (d) => new Date(Date.parse(NOW) - d * DAY).toISOString();
const res = (id, status) => ({ id, journey: id.split(' ')[0], owner: '@nabd/engineering', status, quarantined: false,
  attempts: status === 'passed' ? [{ ok: true }] : status === 'flaky' ? [{ ok: false, error: 'timeout | x' }, { ok: true }] : [{ ok: false, error: 'boom' }, { ok: false, error: 'boom' }] });

function cli(results, history, now = NOW) {
  const d = mkdtempSync(join(tmpdir(), 's11qa-flaky-'));
  results.forEach((r, i) => writeFileSync(join(d, `results-${i + 1}.json`), JSON.stringify({ shard: `${i + 1}/4`, wallMs: 1000 * (i + 1), results: r })));
  const hist = join(d, 'history.json');
  if (history) writeFileSync(hist, JSON.stringify(history));
  const p = spawnSync(process.execPath, [FLAKY, `--results=${d}`, `--history=${hist}`, `--out=${d}/out`, `--now=${now}`], { encoding: 'utf8' });
  return { rc: p.status, out: p.stdout, dir: d, history: JSON.parse(readFileSync(hist, 'utf8')),
    quarantine: JSON.parse(readFileSync(join(d, 'out/quarantine.json'), 'utf8')), report: readFileSync(join(d, 'out/report.md'), 'utf8') };
}

test('AC3: fail-then-pass is reported flaky, does not block, and is written to history with its owner', () => {
  const r = cli([[res('J1 IN desktop', 'passed'), res('J9 IN mobile', 'flaky')]], []);
  assert.equal(r.rc, 0);
  assert.match(r.report, /2 cases: 1 passed, 1 flaky, 0 failed/);
  assert.match(r.report, /\| J9 IN mobile \| flaky \| @nabd\/engineering \| timeout \/ x \|/);
  assert.deepEqual(r.history.map((h) => [h.id, h.owner, h.at]), [['J9 IN mobile', '@nabd/engineering', NOW]]);
});

test('AC3: third flaky occurrence within 5 days quarantines the case, then its failure stops blocking', () => {
  const hist = [ago(4), ago(1)].map((at) => ({ id: 'J6 KSA desktop', owner: '@nabd/clinical-owners', at }));
  const r = cli([[res('J6 KSA desktop', 'flaky')]], hist);
  assert.equal(r.quarantine.length, 1);
  assert.deepEqual([r.quarantine[0].id, r.quarantine[0].owner, r.quarantine[0].occurrences], ['J6 KSA desktop', '@nabd/clinical-owners', 3]);
  assert.match(r.report, /\*\*Quarantined\*\*/);
  const after = cli([[res('J6 KSA desktop', 'failed')]], r.history, new Date(Date.parse(NOW) + 3600000).toISOString());
  assert.equal(after.rc, 0, 'a quarantined failure is reported but does not block');
  assert.match(after.report, /failed \(quarantined\)/);
});

test('AC3 boundary: exactly 5 days old still counts, older does not; 2 occurrences never quarantine', () => {
  const at = (days) => ({ id: 'J1 IN desktop', owner: 'o', at: ago(days) });
  assert.equal(quarantine([at(5), at(2), at(0)], NOW).length, 1);
  assert.equal(quarantine([at(5.001), at(2), at(0)], NOW).length, 0);
  assert.equal(quarantine([at(1), at(0)], NOW).length, 0);
  assert.equal(quarantine([at(1), at(0), { ...at(0), id: 'J1 IN mobile' }], NOW).length, 0, 'counted per case, not per journey');
});

test('AC3: a real failure blocks; no shard results at all also blocks', () => {
  assert.equal(cli([[res('J5 IN desktop', 'failed')], [res('J1 IN desktop', 'passed')]], []).rc, 1);
  const d = mkdtempSync(join(tmpdir(), 's11qa-empty-'));
  const p = spawnSync(process.execPath, [FLAKY, `--results=${d}`, `--history=${d}/h.json`, `--out=${d}/o`, `--now=${NOW}`], { encoding: 'utf8' });
  assert.equal(p.status, 1);
});

test('AC3: history keeps 30 days only and quarantine ignores future-dated entries', () => {
  const h = record([{ id: 'a', owner: 'o', at: ago(31) }, { id: 'b', owner: 'o', at: ago(29) }], [], NOW);
  assert.deepEqual(h.map((x) => x.id), ['b']);
  assert.equal(quarantine([1, 2, 3].map(() => ({ id: 'x', owner: 'o', at: ago(-1) })), NOW).length, 0);
  assert.equal(blocking([res('x', 'failed')], [{ id: 'x' }]).length, 0);
});

test('AC4: 16 cases split into 4 disjoint shards covering all; bad shard spec throws', () => {
  const cases = Array.from({ length: 16 }, (_, i) => i);
  const parts = [1, 2, 3, 4].map((i) => shard(cases, i, 4));
  assert.deepEqual(parts.map((p) => p.length), [4, 4, 4, 4]);
  assert.deepEqual(parts.flat().sort((a, b) => a - b), cases);
  assert.throws(() => shard(cases, 0, 4)); assert.throws(() => shard(cases, 5, 4));
});

test('AC1: journey time budgets are the story numbers (J1 45 s, J5 2 s, J6 500 ms, J9 60 s) and 2 tenants x 2 viewports', () => {
  const src = readFileSync(new URL('../e2e/smoke/journeys.mjs', import.meta.url), 'utf8');
  const budget = (id) => Number(new RegExp(`id: '${id}'[^}]*?budgetMs: (\\d+)`, 's').exec(src)?.[1]);
  assert.deepEqual(['J1', 'J5', 'J6', 'J9'].map(budget), [45000, 2000, 500, 60000]);
  assert.match(src, /key: 'IN'/); assert.match(src, /key: 'KSA'/); assert.match(src, /width: 390/);
  const runner = readFileSync(new URL('../e2e/smoke/run-smoke.mjs', import.meta.url), 'utf8');
  assert.match(runner, /timings\[key\] <= c\.journey\.budgetMs/, 'a missing timing (undefined) must fail the budget check');
});

test('AC2: only critical/serious block; report names rule, impact, node and help link', () => {
  const v = [{ id: 'color-contrast', impact: 'serious', help: 'Contrast', helpUrl: 'https://dequeuniversity.com/rules/axe/4.10/color-contrast',
    nodes: [{ target: ['.btn'], any: [{ message: 'ratio 3.1' }] }] }, { id: 'region', impact: 'moderate', help: 'r', helpUrl: 'u', nodes: [{ target: ['main'] }] }];
  const b = blockingViolations(v);
  assert.deepEqual(b.map((x) => x.id), ['color-contrast']);
  const rep = axeReport('J1 IN desktop', [{ step: 'arrivals', url: '/arrivals', violations: b }]);
  for (const s of ['J1 IN desktop', 'arrivals', '[serious] color-contrast', '.btn (ratio 3.1)', 'https://dequeuniversity.com']) assert.ok(rep.includes(s), s);
  assert.equal(axeReport('x', [{ step: 's', url: '/', violations: [] }]), '');
});

test('AC2: a baselined finding blocks again the day after it expires; other nodes of the same rule still block', () => {
  const v = [{ id: 'color-contrast', impact: 'serious', help: 'h', helpUrl: 'u', nodes: ['.pill_sync_x', '.new_button'] }];
  const known = [{ rule: 'color-contrast', selector: 'pill_sync', expires: '2026-11-18' }];
  const before = applyKnown(v, known, '2026-11-18');
  assert.deepEqual(before.blocking[0].nodes, ['.new_button']); assert.deepEqual(before.known[0].nodes, ['.pill_sync_x']);
  assert.deepEqual(applyKnown(v, known, '2026-11-19').blocking[0].nodes, ['.pill_sync_x', '.new_button']);
});

test('AC2: committed axe baseline entries have owner, reason and expire at most 30 days after they were added', () => {
  const { entries } = JSON.parse(readFileSync(new URL('../e2e/smoke/axe-known.json', import.meta.url), 'utf8'));
  for (const e of entries) {
    assert.ok(e.owner && e.reason && e.rule && e.selector, JSON.stringify(e));
    assert.ok((Date.parse(e.expires) - Date.parse(e.added)) / DAY <= 30, `${e.selector} expires too late`);
  }
});

test('AC2 policy "expires <= 30 days" is enforced by the gate, not only by review', { todo: 'QA-5 finding F5 (FEF-049): applyKnown honours any expiry date' }, () => {
  const v = [{ id: 'color-contrast', impact: 'serious', help: 'h', helpUrl: 'u', nodes: ['.x'] }];
  const r = applyKnown(v, [{ rule: 'color-contrast', selector: 'x', added: '2026-11-01', expires: '2099-01-01' }], '2026-11-10');
  assert.equal(r.blocking.length, 1, 'an entry parked for 70 years must not silence a serious violation');
});

test('workflow selfcheck asserts the broken run fails and the healthy one passes', () => {
  const y = readFileSync(new URL('../../../../../.github/workflows/e2e-smoke.yml', import.meta.url), 'utf8');
  assert.match(y, /if node frontend\/apps\/staff-web\/tests\/e2e\/smoke\/run-smoke\.mjs --only=J9 --break[^\n]*; then echo "smoke MISSED[^\n]*exit 1; fi/);
  assert.ok(existsSync(new URL('../e2e/smoke/run-smoke.mjs', import.meta.url)));
});
