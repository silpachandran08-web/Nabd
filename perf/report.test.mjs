// OPS-046: node --test perf/report.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compare, samples, gate } from './report.mjs';

const run = (p95, ep = {}) => ({ overall: { p95 }, endpoints: ep });
const ok = { profile: 'registry', endpoints: { registry: { p95: 100 } }, overall: { errorRate: 0 }, thresholds: {} };
test('within the +10% budget passes, beyond it fails per endpoint', () => {
  const base = run(100, { registry: { p95: 200 } });
  assert.deepEqual(compare(base, run(110, { registry: { p95: 220 } })), []);
  assert.equal(compare(base, run(111, { registry: { p95: 200 } })).length, 1);
  assert.match(compare(base, run(100, { registry: { p95: 221 } }))[0], /^registry: p95 221\.0 ms > baseline 200\.0 ms/);
  assert.match(compare(base, run(100, {}))[0], /registry: missing/);
});
test('db samples: blanks are "not measured", CPU >= 70% fails the gate', () => {
  const db = samples('1,40,3,10\n2,75.5,8,10\n3,,9,10\n');
  assert.equal(db.dbCpuPct.max, 75.5);
  assert.equal(db.dbActiveConnections.max, 9);
  assert.deepEqual(gate(ok, db), ['DB CPU max 75.5% >= 70%']);
  assert.equal(samples('1,,,\n').dbCpuPct, null);
});
test('a failed k6 threshold fails the gate', () => {
  assert.deepEqual(gate({ ...ok, thresholds: { 'http_req_duration{name:registry}': { 'p(95)<300': false } } }, null), ['http_req_duration{name:registry} p(95)<300']);
});
test('an aborted run (no requests of the profile, 100% errors) fails the gate', () => {
  const fails = gate({ profile: 'checkin', endpoints: {}, overall: { errorRate: 1 }, thresholds: {} }, null);
  assert.equal(fails.length, 2);
  assert.match(fails[0], /no checkin requests/);
  assert.match(fails[1], /error rate 100\.000% >= 0\.1%/);
});
