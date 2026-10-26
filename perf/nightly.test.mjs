// OPS-047: node --test perf/nightly.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { check, slopePerHour } from './trend.mjs';
import { makeStub } from './wa-stub.mjs';

const line = (t, heap, pool = 10, outboxAge = 0) => `time="x" level=info msg="TREND ${JSON.stringify({ t, heap, pool, outboxAge }).replace(/"/g, '\\"')}"`;
const series = (f) => Array.from({ length: 20 }, (_, i) => line(i * 1800000, f(i)));

test('flat series pass, steady growth fails, too few samples fails', () => {
  assert.deepEqual(check(series((i) => 1e8 + (i % 2) * 1e6)), []);
  const grow = check(series((i) => 1e8 + i * 5e7));
  assert.equal(grow.length, 1);
  assert.match(grow[0], /^heap: grows/);
  assert.match(check(series(() => 1).slice(0, 3))[0], /only 3 samples/);
  assert.ok(slopePerHour([[0, 0], [3600000, 5]]) === 5);
});

test('wa stub: 200 ms ok answer, injected 5xx, counts a repeated accepted payload as a duplicate', async () => {
  const seq = [0.99, 0.99, 0.01, 0.99]; // fail only the third request
  const { server, stats } = makeStub({ latencyMs: 5, failPct: 5, random: () => seq.shift() });
  await new Promise((r) => server.listen(0, r));
  const url = `http://127.0.0.1:${server.address().port}`;
  const post = (b) => fetch(url, { method: 'POST', body: b }).then((r) => r.status);
  assert.deepEqual([await post('a'), await post('b'), await post('c'), await post('a')], [200, 200, 503, 200]);
  const s = await (await fetch(url + '/stats')).json();
  assert.deepEqual(s, { received: 4, ok: 3, failed: 1, duplicates: 1 });
  assert.equal(stats.duplicates, 1);
  server.close();
});
