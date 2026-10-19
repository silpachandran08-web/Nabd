// ACC-002: stale-screen helper. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { moveBody, staleMessage, moveErrorMessage } from "../../app/lib/queueMove.ts";

test("body carries expectedStatus", () => {
  assert.deepEqual(JSON.parse(moveBody("vitals_pending", "waiting")), { status: "vitals_pending", expectedStatus: "waiting" });
});

test("stale problem becomes 'Already moved to <stage>'", () => {
  const m = staleMessage({ type: "https://x/problems/stale-status", currentStatus: "vitals_pending" });
  assert.match(m, /^Already moved to vitals pending/);
});

test("other problems are not stale", () => {
  assert.equal(staleMessage({ type: "https://x/problems/illegal-transition" }), null);
  assert.equal(staleMessage(null), null);
});

test("moveErrorMessage prefers stale, then detail, then fallback", async () => {
  const mk = (b) => ({ json: async () => { if (b === undefined) throw new Error("x"); return b; } });
  assert.match(await moveErrorMessage(mk({ type: "t/stale-status", currentStatus: "waiting" }), "f"), /Already moved to waiting/);
  assert.equal(await moveErrorMessage(mk({ detail: "nope" }), "f"), "nope");
  assert.equal(await moveErrorMessage(mk(undefined), "f"), "f");
});
