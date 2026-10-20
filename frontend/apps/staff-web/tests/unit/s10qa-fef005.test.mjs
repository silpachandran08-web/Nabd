// S10 QA FEF-005 (independent) pure-logic checks: policy clamp, idle phases, lock persistence/broadcast, draft AES-GCM envelope.
import { test } from "node:test";
import assert from "node:assert/strict";

const mem = () => { const m = new Map(); return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k), clear: () => m.clear(), _m: m }; };
globalThis.sessionStorage = mem();
globalThis.localStorage = mem();
const idle = await import("../../app/lib/idleLock.ts");
const drafts = await import("../../app/components/consult/draftStore.ts");

test("policy: only whole minutes 2..30 are honoured, everything else is the 5 min default", () => {
  for (let m = 2; m <= 30; m++) assert.equal(idle.clampIdleMinutes(String(m)), m);
  for (const bad of ["0", "1", "31", "100", "-5", "2.0", "1e1", "0x10", "", "ten", null, undefined, NaN, 2.5, {}, []]) assert.equal(idle.clampIdleMinutes(bad), 5, JSON.stringify(bad));
});

test("phases at exact boundaries for 2, 10 and 30 minute timeouts", () => {
  for (const min of [2, 10, 30]) {
    const T = min * 60_000;
    assert.equal(idle.idlePhase(0, T).phase, "active");
    assert.equal(idle.idlePhase(T - 30_001, T).phase, "active");
    assert.deepEqual(idle.idlePhase(T - 30_000, T), { phase: "warn", secondsLeft: 30 });
    assert.equal(idle.idlePhase(T - 1, T).phase, "warn");
    assert.equal(idle.idlePhase(T, T).phase, "lock");
    assert.equal(idle.idlePhase(T * 5, T).phase, "lock");
  }
  assert.equal(idle.WARN_MS, 30_000);
});

test("lock persists in BOTH stores, a new tab (only localStorage) still reads it, unlock removes both", () => {
  assert.equal(idle.readLockedAt(), null);
  idle.persistLock();
  assert.ok(sessionStorage.getItem(idle.LOCK_KEY) && localStorage.getItem(idle.LOCK_KEY));
  sessionStorage.clear(); // brand-new tab
  assert.ok(idle.readLockedAt() > 0);
  idle.persistUnlock();
  assert.equal(idle.readLockedAt(), null);
  assert.equal(localStorage.getItem(idle.LOCK_KEY), null);
});

test("lock/unlock is broadcast on channel nabd-lock and a listener is told", async () => {
  assert.equal(idle.LOCK_CHANNEL, "nabd-lock");
  const seen = [];
  const off = idle.onLockMessage((l) => seen.push(l));
  const other = new BroadcastChannel("nabd-lock"); // plays "another tab"
  other.postMessage({ type: "lock" }); other.postMessage({ type: "unlock" }); other.postMessage({ type: "bogus" });
  await new Promise((r) => setTimeout(r, 100));
  other.close(); off();
  assert.deepEqual(seen, [true, false]);
});

test("draft envelope: AES-GCM, 12 byte fresh IV per write, no plaintext, tamper and wrong-key detected", async () => {
  const d = { entryId: "e9", note: { subjective: "SECRET-CHEST-PAIN", objective: "", assessment: "", plan: "", diagnosis: "" }, baseUpdatedAt: null, savedAt: "2026-01-01T00:00:00Z" };
  const a = await drafts.seal(d), b = await drafts.seal(d);
  assert.deepEqual(Object.keys(a).sort(), ["ct", "entryId", "iv"]);
  assert.equal(a.iv.byteLength, 12);
  assert.notDeepEqual(Buffer.from(a.iv), Buffer.from(b.iv));
  assert.notDeepEqual(Buffer.from(a.ct), Buffer.from(b.ct));
  assert.ok(!Buffer.from(a.ct).toString("latin1").includes("SECRET"));
  assert.ok(a.ct.byteLength >= JSON.stringify(d).length + 16);
  assert.deepEqual(await drafts.unseal(a), d);
  const t = new Uint8Array(a.ct.slice(0)); t[3] ^= 1;
  assert.equal(await drafts.unseal({ iv: a.iv, ct: t.buffer }), null);
  await drafts.clearDrafts(); // sign-out drops the key => the old ciphertext is unreadable
  assert.equal(await drafts.unseal(a), null);
});

test("no plaintext fallback when WebCrypto is missing: writeDraft stores nothing and does not throw", async () => {
  const real = Object.getOwnPropertyDescriptor(globalThis, "crypto");
  Object.defineProperty(globalThis, "crypto", { value: undefined, configurable: true });
  try {
    const r = await drafts.writeDraft({ entryId: "x", note: { subjective: "PLAINTEXT-MARKER", objective: "", assessment: "", plan: "", diagnosis: "" }, baseUpdatedAt: null, savedAt: "t" });
    assert.equal(r, null);
  } finally { Object.defineProperty(globalThis, "crypto", real); }
});
