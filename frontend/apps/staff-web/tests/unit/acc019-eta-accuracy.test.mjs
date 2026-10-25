// ACC-019: the owner tile's per-day share, low-confidence flag and sparkline.
import { test } from "node:test";
import assert from "node:assert/strict";
const { dailyShare, sparkline, MIN_VISITS } = await import("../../app/lib/etaAccuracy.ts");

const row = (clinicDate, n, within10Pct, extra = {}) => ({ clinicDate, doctorId: "d" + n, n, maeMin: 3, p80HitRate: 0.8, within10Pct, ...extra });

test("share is weighted by visits across doctors and sorted oldest first", () => {
  const days = dailyShare([row("2026-10-02", 10, 0.5), row("2026-10-01", 30, 1), row("2026-10-01", 10, 0.6)]);
  assert.deepEqual(days.map((d) => d.date), ["2026-10-01", "2026-10-02"]);
  assert.equal(days[0].pct, Math.round(((30 * 1 + 10 * 0.6) / 40) * 100)); // 90
  assert.equal(days[0].n, 40);
});
test("boundary: 29 visits is low confidence, 30 is not; the server flag also counts", () => {
  assert.equal(MIN_VISITS, 30);
  assert.equal(dailyShare([row("2026-10-01", 29, 1)])[0].low, true);
  assert.equal(dailyShare([row("2026-10-01", 30, 1)])[0].low, false);
  assert.equal(dailyShare([row("2026-10-01", 40, 1, { lowConfidence: true })])[0].low, true);
});
test("a day without within10Pct has no figure (not 0) and leaves a gap in the sparkline", () => {
  const days = dailyShare([row("2026-10-01", 40, 1), row("2026-10-02", 40, undefined), row("2026-10-03", 40, 0)]);
  assert.equal(days[1].pct, null);
  assert.equal(sparkline(days, 100, 40), "0.0,0.0 100.0,40.0");
});
test("empty input gives no days", () => {
  assert.deepEqual(dailyShare([]), []);
  assert.equal(sparkline([], 100, 40), "");
});
