// FEF-008: payment intent persistence, key reuse, timeout -> status check, refusal paths, sign-out clearing.
import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";

const mem = () => { const m = new Map(); return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k), clear: () => m.clear(), key: (i) => [...m.keys()][i], get length() { return m.size; }, keys: () => [...m.keys()] }; };
const ss = mem();
// Object.keys(sessionStorage) must list entries like a real Storage.
globalThis.sessionStorage = new Proxy(ss, { ownKeys: () => ss.keys(), getOwnPropertyDescriptor: (t, k) => (ss.keys().includes(k) ? { enumerable: true, configurable: true, value: ss.getItem(k) } : undefined) });
globalThis.localStorage = mem();
const { keyFor, dropKey, clearIntents, pendingFor, recordPayment, checkStatus, submitKeyed, keyedPost, TimeoutError } = await import("../../app/lib/idempotency.ts");
const P = { invoiceId: "inv1", method: "upi", amountMinor: 50000, slot: 0 };
const json = (status, body) => new Response(JSON.stringify(body), { status });
beforeEach(() => { ss.clear(); Object.defineProperty(globalThis, "navigator", { configurable: true, value: { onLine: true } }); });

test("keyFor persists one key per scope until dropped", () => {
  const a = keyFor("s1"); assert.equal(keyFor("s1"), a); assert.notEqual(keyFor("s2"), a);
  dropKey("s1"); assert.notEqual(keyFor("s1"), a);
});
test("timeout -> checks invoice -> payment present = recorded, key cleared", async () => {
  const calls = [];
  const f = async (path, init) => {
    calls.push([path, init?.method ?? "GET", init?.headers?.["Idempotency-Key"]]);
    if (init?.method === "POST") throw new TimeoutError();
    return json(200, { payments: [{ idempotencyKey: calls[0][2] }] });
  };
  let checking = 0;
  const out = await recordPayment(f, P, () => checking++);
  assert.equal(out.kind, "recorded"); assert.equal(checking, 1);
  assert.equal(calls[1][0], "/billing/invoices/inv1"); assert.equal(pendingFor("inv1").length, 0);
});
test("timeout -> not on invoice -> notRecorded; retry reuses the SAME key; 3 retries one key", async () => {
  const keys = [];
  const f = async (path, init) => { if (init?.method === "POST") { keys.push(init.headers["Idempotency-Key"]); throw new Error("net"); } return json(200, { payments: [] }); };
  for (let i = 0; i < 3; i++) assert.equal((await recordPayment(f, P)).kind, "notRecorded");
  assert.equal(new Set(keys).size, 1);
  assert.equal(pendingFor("inv1").length, 1);
  assert.equal(pendingFor("inv1")[0].key, keys[0]); // restored after a reload
});
test("5xx and 409 IN_PROGRESS go to the status check", async () => {
  for (const [s, b] of [[503, {}], [409, { code: "IDEMPOTENCY_IN_PROGRESS" }]]) {
    ss.clear();
    const f = async (p, init) => (init?.method === "POST" ? json(s, b) : json(200, { payments: [] }));
    assert.equal((await recordPayment(f, P)).kind, "notRecorded");
  }
});
test("status check failing is 'unknown', key kept", async () => {
  const f = async (p, init) => { if (init?.method === "POST") throw new Error("net"); return json(500, {}); };
  assert.equal((await recordPayment(f, P)).kind, "unknown"); assert.equal(pendingFor("inv1").length, 1);
});
test("201 summary without payments[] reads the invoice back", async () => {
  let key;
  const f = async (p, init) => { if (init?.method === "POST") { key = init.headers["Idempotency-Key"]; return json(201, { paymentId: "x" }); } return json(200, { payments: [{ idempotencyKey: key }] }); };
  assert.equal((await recordPayment(f, P)).kind, "recorded");
});
test("422 KEY_REUSED -> clear message and a fresh key next time", async () => {
  let key;
  const f = async (p, init) => { key = init.headers["Idempotency-Key"]; return json(422, { code: "IDEMPOTENCY_KEY_REUSED" }); };
  const out = await recordPayment(f, P);
  assert.equal(out.kind, "rejected"); assert.match(out.message, /different amount/);
  await recordPayment(f, P); assert.notEqual(keyFor("pay.inv1.upi.50000.0"), key);
});
test("offline: refused, nothing sent, nothing stored", async () => {
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: { onLine: false } });
  let sent = 0;
  assert.equal((await recordPayment(async () => { sent++; return null; }, P)).kind, "offline");
  assert.equal(sent, 0); assert.equal(ss.length, 0);
});
test("identical rows get distinct keys via slot; same row after success gets a fresh one", async () => {
  assert.notEqual(keyFor("pay.inv1.cash.100.0"), keyFor("pay.inv1.cash.100.1"));
});
test("keyedPost aborts after the timeout with TimeoutError", async () => {
  const f = (p, init) => new Promise((_, rej) => init.signal.addEventListener("abort", () => rej(new Error("aborted"))));
  await assert.rejects(keyedPost(f, "/x", {}, "k", 20), TimeoutError);
});
test("submitKeyed keeps the key on 5xx, drops it on success", async () => {
  const seen = [];
  const f = (st) => async (p, init) => { seen.push(init.headers["Idempotency-Key"]); return json(st, {}); };
  await submitKeyed(f(503), "/queue/check-in", {}, "ci.a"); await submitKeyed(f(200), "/queue/check-in", {}, "ci.a");
  assert.equal(seen[0], seen[1]);
  await submitKeyed(f(200), "/queue/check-in", {}, "ci.a");
  assert.notEqual(seen[2], seen[1]);
});
test("clearIntents (sign-out) removes every payment intent, nothing in localStorage", () => {
  keyFor("pay.inv1.cash.1.0"); keyFor("co.q.x"); ss.setItem("other", "1");
  assert.equal(localStorage.length, 0);
  clearIntents(); assert.deepEqual(ss.keys(), ["other"]);
});
test("checkStatus on a reload finds the stored key", async () => {
  const key = keyFor("pay.inv1.upi.50000.0", { method: "upi", amountMinor: 50000 });
  const [i] = pendingFor("inv1");
  const out = await checkStatus(async () => json(200, { payments: [{ idempotencyKey: key }] }), "inv1", i.key, i.scope);
  assert.equal(out.kind, "recorded"); assert.equal(pendingFor("inv1").length, 0);
});
