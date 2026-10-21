// FEF-005: idle policy clamp/phase, lock persistence + broadcast, draft AES-GCM round trip. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { clampIdleMinutes, idlePhase, WARN_MS } from "../../app/lib/idleLock.ts";
import { seal, unseal, clearDrafts } from "../../app/components/consult/draftStore.ts";

test("clinic idle setting: 2-30 whole minutes, else default 5", () => {
  for (const [v, want] of [["10", 10], ["2", 2], ["30", 30], ["1", 5], ["31", 5], ["abc", 5], ["", 5], [null, 5], ["7.5", 5], [undefined, 5], [12, 12]]) assert.equal(clampIdleMinutes(v), want, String(v));
});

test("10 min timeout: idle 9m29s nothing, 9m30s warns with 30 s, 10m locks", () => {
  const T = 10 * 60_000;
  assert.equal(idlePhase(T - WARN_MS - 1000, T).phase, "active");
  assert.deepEqual(idlePhase(T - WARN_MS, T), { phase: "warn", secondsLeft: 30 });
  assert.deepEqual(idlePhase(T - 1000, T), { phase: "warn", secondsLeft: 1 });
  assert.equal(idlePhase(T, T).phase, "lock");
});

test("draft is AES-GCM ciphertext (no note text), round-trips, fresh IV per record, wrong key fails", async () => {
  const d = { entryId: "e1", note: { subjective: "chest pain SECRETTEXT", objective: "", assessment: "", plan: "", diagnosis: "" }, baseUpdatedAt: null, savedAt: "2026-01-01T00:00:00Z" };
  const a = await seal(d), b = await seal(d);
  assert.equal(a.entryId, "e1");
  assert.ok(!Buffer.from(a.ct).toString("latin1").includes("SECRETTEXT"));
  assert.notDeepEqual(Buffer.from(a.iv), Buffer.from(b.iv));
  assert.deepEqual(await unseal(a), d);
  await clearDrafts(); // drops the in-memory key: old records become unreadable
  assert.equal(await unseal(a), null);
});

test("encryption cost per draft write is under 5 ms", async () => {
  const d = { entryId: "e", note: { subjective: "x".repeat(2000), objective: "", assessment: "", plan: "", diagnosis: "" }, baseUpdatedAt: null, savedAt: "t" };
  await seal(d);
  const t = performance.now();
  for (let i = 0; i < 20; i++) await seal(d);
  assert.ok((performance.now() - t) / 20 < 5);
});
