// OPS-047.3: soak trend check. Reads "TREND {json}" lines from the k6 log (soak.js probe) and fails when heap, pool or outbox
// age grows steadily: least-squares slope per hour above MAX_GROWTH (default 5%) of the series mean. Needs >= 8 samples.
// node perf/trend.mjs <k6.log>   (exit 1 on an upward trend)
import { readFileSync } from 'node:fs';

export function slopePerHour(points) { // points: [[ms, value]]
  const n = points.length, mx = points.reduce((s, p) => s + p[0], 0) / n, my = points.reduce((s, p) => s + p[1], 0) / n;
  const num = points.reduce((s, p) => s + (p[0] - mx) * (p[1] - my), 0), den = points.reduce((s, p) => s + (p[0] - mx) ** 2, 0);
  return den === 0 ? 0 : (num / den) * 3600000;
}

export function check(lines, maxGrowth = 0.05) {
  const rows = lines.filter((l) => l.includes('TREND {')).map((l) => JSON.parse(l.slice(l.indexOf('TREND {') + 6).replace(/"?$/, '').replace(/\\"/g, '"')));
  const fails = [];
  for (const k of ['heap', 'pool', 'outboxAge']) {
    const pts = rows.filter((r) => r[k] != null).map((r) => [r.t, r[k]]);
    if (pts.length < 8) { fails.push(`${k}: only ${pts.length} samples (need 8)`); continue; }
    const mean = pts.reduce((s, p) => s + p[1], 0) / pts.length;
    const growth = mean === 0 ? slopePerHour(pts) : slopePerHour(pts) / mean;
    if (growth > maxGrowth) fails.push(`${k}: grows ${(growth * 100).toFixed(1)}%/h of its mean (limit ${maxGrowth * 100}%)`);
  }
  return fails;
}

if (process.argv[1]?.endsWith('trend.mjs')) {
  const fails = check(readFileSync(process.argv[2], 'utf8').split('\n'), +(process.env.MAX_GROWTH ?? 0.05));
  console.log(fails.length ? 'soak trend FAIL\n  ' + fails.join('\n  ') : 'soak trend PASS');
  process.exit(fails.length ? 1 : 0);
}
