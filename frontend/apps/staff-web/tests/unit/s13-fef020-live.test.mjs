// FEF-020: the shared SSE stream (leader election, fan-out, backoff, resume, visibility pause), the cache applier
// (version guard, patch vs invalidate) and the BFF streaming proxy. Fakes only: no browser, no Core.
import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { createLiveStream, backoffMs, RECONNECT_BANNER_MS, FALLBACK_POLL_MS, HIDDEN_CLOSE_MS, IDLE_MS } from "../../../../packages/api-client/sse/stream.ts";
import { createApplier, resourcesFor, LIVE_RESOURCES } from "../../../../packages/api-client/sse/patch.ts";
import { events } from "../../app/lib/bffEvents.ts";

// ---- fakes ---------------------------------------------------------------------------------------
function world({ locks = true } = {}) {
  let now = 0, seq = 0;
  const timers = new Map();
  const w = {
    sources: [], // every EventSource ever opened
    advance(ms) {
      const end = now + ms;
      for (;;) {
        const next = [...timers.entries()].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
        if (!next) break;
        const [id, t] = next;
        now = t.at;
        if (t.every) t.at += t.every; else timers.delete(id);
        t.f();
      }
      now = end;
    },
  };
  const waiters = []; let holder = null;
  const grant = () => { if (holder || !waiters.length) return; const wt = waiters.shift(); holder = wt; wt.cb().finally(() => { holder = null; grant(); }); };
  const lockApi = locks ? { request: (_n, cb) => new Promise((res) => { waiters.push({ cb: () => cb().then(res) }); grant(); }) } : null;
  const channels = new Set();
  class BC { constructor() { this.onmessage = null; channels.add(this); } postMessage(m) { const c = structuredClone(m); for (const o of channels) if (o !== this) queueMicrotask(() => o.onmessage?.({ data: c })); } close() { channels.delete(this); } }
  class ES {
    constructor(url) { this.url = url; this.closed = false; this.h = {}; this.onopen = null; this.onerror = null; w.sources.push(this); }
    addEventListener(t, f) { this.h[t] = f; }
    close() { this.closed = true; }
    open() { this.onopen?.({}); }
    emit(event, data, id = "") { this.h[event]?.({ data: JSON.stringify(data), lastEventId: id }); }
    fail() { this.onerror?.({}); }
  }
  w.tab = (o = {}) => {
    const t = { hidden: false, visCb: null, msgs: [], statuses: [], polls: 0 };
    t.setHidden = (h) => { t.hidden = h; t.visCb?.(); };
    t.stream = createLiveStream({
      url: "/api/events?topics=queue", onMessage: (m) => t.msgs.push(m), onStatus: (s) => t.statuses.push(s), onFallbackPoll: () => t.polls++,
      deps: {
        EventSource: ES, locks: lockApi, BroadcastChannel: BC, isHidden: () => t.hidden, onVisibility: (cb) => { t.visCb = cb; return () => { t.visCb = null; }; },
        setTimeout: (f, ms) => { const id = ++seq; timers.set(id, { at: now + ms, f }); return id; }, clearTimeout: (id) => timers.delete(id),
        setInterval: (f, ms) => { const id = ++seq; timers.set(id, { at: now + ms, f, every: ms }); return id; }, clearInterval: (id) => timers.delete(id),
        random: o.random ?? (() => 0.5),
      },
    });
    return t;
  };
  // advance with a server heartbeat every 25 s, like the real stream
  w.beat = (ms) => { for (let left = ms; left > 0; left -= 25_000) { w.advance(Math.min(25_000, left)); w.open()[0]?.emit('heartbeat', {}); } };
  w.open = () => w.sources.filter((s) => !s.closed);
  w.flush = () => new Promise((r) => setImmediate(r));
  return w;
}

// ---- leader election and fan-out (AC2) -----------------------------------------------------------
test("4 tabs share exactly 1 EventSource; every tab receives every message", async () => {
  const w = world();
  const tabs = [w.tab(), w.tab(), w.tab(), w.tab()];
  await w.flush();
  assert.equal(w.sources.length, 1, "one connection for four tabs");
  w.sources[0].open();
  w.sources[0].emit("snapshot", { items: [] }, "1");
  w.sources[0].emit("delta", { type: "queue.entry.updated", id: "q1", v: 2 }, "2");
  await w.flush();
  for (const t of tabs) assert.deepEqual(t.msgs.map((m) => m.event), ["snapshot", "delta"]);
  assert.match(w.sources[0].url, /^\/api\/events\?topics=queue$/);
});

test("closing the leader re-elects a leader at once (< 3 s) and the others keep receiving", async () => {
  const w = world();
  const [a, b, c] = [w.tab(), w.tab(), w.tab()];
  await w.flush();
  w.sources[0].open();
  a.stream.stop(); // the leader tab closes
  await w.flush();
  w.advance(3000);
  assert.equal(w.open().length, 1, "exactly one open connection after re-election");
  assert.equal(w.sources.length, 2);
  w.sources[1].open();
  w.sources[1].emit("delta", { type: "queue.entry.updated", id: "q9", v: 1 }, "7");
  await w.flush();
  assert.equal(b.msgs.length, 1); assert.equal(c.msgs.length, 1);
  assert.equal(a.msgs.length, 0, "the closed tab hears nothing");
});

test("without Web Locks every tab keeps its own connection and nothing is delivered twice", async () => {
  const w = world({ locks: false });
  const [a, b] = [w.tab(), w.tab()];
  assert.equal(w.sources.length, 2);
  w.sources[0].open(); w.sources[0].emit("delta", { type: "queue.entry.updated", id: "q1", v: 1 });
  await w.flush();
  assert.equal(a.msgs.length, 1); assert.equal(b.msgs.length, 0);
});

test("a tab opened later gets the leader's status and last snapshot", async () => {
  const w = world();
  w.tab(); await w.flush();
  w.sources[0].open(); w.sources[0].emit("snapshot", { items: [1] }, "5");
  const late = w.tab(); await w.flush();
  assert.equal(w.sources.length, 1);
  assert.deepEqual(late.msgs.map((m) => m.event), ["snapshot"]);
  assert.ok(late.statuses.includes("live"));
});

// ---- drop, indicator, fallback, resume (AC3) -----------------------------------------------------
test("a dropped stream shows Reconnecting after 5 s, polls every 60 s, resumes with the last event id and stops polling", async () => {
  const w = world();
  const t = w.tab(); await w.flush();
  w.sources[0].open();
  w.sources[0].emit("delta", { type: "queue.entry.updated", id: "q1", v: 1 }, "41");
  w.sources[0].fail();
  w.advance(RECONNECT_BANNER_MS - 1);
  assert.ok(!t.statuses.includes("reconnecting"), "not yet at 4.999 s");
  assert.equal(w.sources.length, 2, "backoff reconnect already tried (1 s base)");
  w.sources[1].fail();
  w.advance(1);
  assert.equal(t.statuses.at(-1), "reconnecting");
  assert.equal(t.polls, 0);
  // keep failing; the 60 s poll runs while disconnected
  for (let i = 0; i < 70; i++) { w.advance(1000); const o = w.open()[0]; if (o) o.fail(); }
  assert.ok(t.polls >= 1 && t.polls <= 2, `polls=${t.polls}`);
  assert.ok(w.sources.every((s) => !/lastEventId/.test(s.url) || /lastEventId=41$/.test(s.url)), "every retry resumes from the last seen id");
  assert.match(w.sources.at(-1).url, /&lastEventId=41$/);
  // the stream comes back: Live, polling stops
  w.advance(40_000);
  const cur = w.open().at(-1) ?? w.sources.at(-1);
  if (cur.closed) w.advance(40_000);
  const live = w.open().at(-1);
  live.open();
  assert.equal(t.statuses.at(-1), "live");
  const polls = t.polls;
  w.advance(FALLBACK_POLL_MS * 2);
  assert.equal(t.polls, polls, "no polling once the stream is back");
});

test("backoff is exponential, capped at 30 s, with jitter between 50% and 100%", () => {
  assert.deepEqual([0, 1, 2, 3].map((n) => backoffMs(n, 1)), [1000, 2000, 4000, 8000]);
  assert.equal(backoffMs(20, 1), 30_000);
  assert.equal(backoffMs(3, 0), 4000);
  for (let i = 0; i < 100; i++) { const v = backoffMs(2, Math.random()); assert.ok(v >= 2000 && v <= 4000); }
});

test("silence for 60 s (no heartbeat) is treated as a dead stream and reconnects", async () => {
  const w = world();
  w.tab(); await w.flush();
  w.sources[0].open();
  w.advance(25_000); w.sources[0].emit("heartbeat", {}); // heartbeats keep it alive
  w.advance(IDLE_MS - 1); assert.equal(w.sources.length, 1);
  w.advance(1); assert.ok(w.sources[0].closed);
  w.advance(2000); assert.equal(w.sources.length, 2);
});

// ---- visibility (AC4) ----------------------------------------------------------------------------
test("a lone tab hidden for more than 5 minutes closes the stream; coming back reconnects (snapshot follows)", async () => {
  const w = world();
  const t = w.tab(); await w.flush();
  w.sources[0].open();
  t.setHidden(true);
  w.beat(HIDDEN_CLOSE_MS - 1000); w.advance(999); assert.equal(w.open().length, 1, "still open just before 5:00");
  w.advance(1);
  assert.equal(w.open().length, 0, "closed at 5:00");
  assert.equal(t.statuses.at(-1), "paused");
  w.advance(10 * 60_000); assert.equal(w.sources.length, 1, "no reconnect while hidden");
  t.setHidden(false);
  assert.equal(w.open().length, 1, "reconnects on return");
  w.open()[0].open(); w.open()[0].emit("snapshot", { items: [] }, "9");
  assert.equal(t.msgs.at(-1).event, "snapshot");
  assert.equal(t.statuses.at(-1), "live");
});

test("a hidden leader keeps the stream while another tab is visible; it closes only when all are hidden 5 min", async () => {
  const w = world();
  const [a, b] = [w.tab(), w.tab()]; await w.flush();
  w.sources[0].open();
  a.setHidden(true); w.beat(HIDDEN_CLOSE_MS + 1000); await w.flush();
  assert.equal(w.open().length, 1, "tab b is visible");
  b.setHidden(true); w.beat(HIDDEN_CLOSE_MS + 1000); await w.flush();
  assert.equal(w.open().length, 0);
  b.setHidden(false); await w.flush();
  assert.equal(w.open().length, 1, "a visible follower wakes the leader's stream");
});

// ---- cache applier (AC5) -------------------------------------------------------------------------
function cache(queue = [{ id: "q1", status: "waiting", tokenNumber: 1 }, { id: "q2", status: "waiting", tokenNumber: 2 }]) {
  const store = new Map([[JSON.stringify(["t", "t1", "queue", {}]), queue]]);
  const calls = { set: [], inv: [] };
  return {
    calls, store,
    getQueriesData: ({ queryKey }) => [...store].filter(([k]) => k.startsWith(JSON.stringify(queryKey).slice(0, -1))).map(([k, v]) => [JSON.parse(k), v]),
    setQueryData: (k, up) => { const key = JSON.stringify(k); store.set(key, up(store.get(key))); calls.set.push(k); },
    invalidateQueries: ({ queryKey }) => calls.inv.push(queryKey[2]),
  };
}
const prefix = (r) => ["t", "t1", r];
const delta = (data) => ({ event: "delta", data });

test("a delta with a newer v patches the cached row; a lower or equal v is ignored", () => {
  const c = cache(); const apply = createApplier(c, prefix);
  apply(delta({ type: "queue.entry.updated.v1", id: "q1", v: 5, status: "in_consult" }));
  assert.equal([...c.store.values()][0][0].status, "in_consult");
  assert.deepEqual(c.calls.inv, ["wait-estimate"], "patched queue is not refetched; the estimate is");
  const before = JSON.stringify([...c.store]); const n = c.calls.inv.length;
  assert.deepEqual(apply(delta({ type: "queue.entry.updated.v1", id: "q1", v: 4, status: "waiting" })), []);
  assert.deepEqual(apply(delta({ type: "queue.entry.updated.v1", id: "q1", v: 5, status: "waiting" })), []);
  assert.equal(JSON.stringify([...c.store]), before, "stale delta changed nothing");
  assert.equal(c.calls.inv.length, n);
  apply(delta({ type: "queue.entry.updated.v1", id: "q1", v: 6, status: "done" }));
  assert.equal([...c.store.values()][0][0].status, "done");
  apply(delta({ type: "queue.entry.updated.v1", id: "q2", v: 1, status: "in_consult" })); // another entity, own counter
  assert.equal([...c.store.values()][0][1].status, "in_consult");
});

test("an unknown row, or a delta with no field to patch, invalidates instead", () => {
  const c = cache(); const apply = createApplier(c, prefix);
  apply(delta({ type: "queue.entry.updated.v1", id: "new1", v: 1, status: "waiting" })); // a fresh check-in
  assert.deepEqual(c.calls.inv, ["queue", "wait-estimate"]);
  c.calls.inv.length = 0;
  apply(delta({ type: "queue.entry.updated.v1", id: "q1", v: 2 }));
  assert.deepEqual(c.calls.inv, ["queue", "wait-estimate"]);
  assert.equal(c.calls.set.length, 0);
});

test("per-type mapping: nursing, doctor status, unknown types; payload without v is always applied", () => {
  const c = cache(); const apply = createApplier(c, prefix);
  apply(delta({ type: "nursing.task.updated.v1", id: "n1", v: 1 }));
  assert.deepEqual(c.calls.inv, ["nursing"]);
  c.calls.inv.length = 0;
  apply(delta({ type: "doctor.status.changed.v1", doctorId: "d1", status: "busy", v: 1 }));
  assert.deepEqual(c.calls.inv, ["roster", "doctor-delay", "wait-estimate", "doctor-status"]);
  c.calls.inv.length = 0;
  assert.deepEqual(apply(delta({ type: "exceptions.something.v1", id: "x", v: 1 })), []);
  assert.deepEqual(resourcesFor("requests.created"), []);
  apply(delta({ type: "nursing.task.updated.v1", id: "n2" })); apply(delta({ type: "nursing.task.updated.v1", id: "n2" }));
  assert.deepEqual(c.calls.inv, ["nursing", "nursing"]);
});

test("a snapshot invalidates every live resource and resets the version guard", () => {
  const c = cache(); const apply = createApplier(c, prefix);
  apply(delta({ type: "queue.entry.updated.v1", id: "q1", v: 9, status: "done" }));
  c.calls.inv.length = 0;
  apply({ event: "snapshot", data: {} });
  assert.deepEqual(c.calls.inv, [...LIVE_RESOURCES]);
  apply(delta({ type: "queue.entry.updated.v1", id: "q1", v: 1, status: "waiting" })); // server restarted its counters
  assert.equal([...c.store.values()][0][0].status, "waiting");
});

// ---- AC6: read load, modelled ---------------------------------------------------------------------
test("5 desks over a 4 h morning: live reads drop by >= 70% against 15 s polling", () => {
  // Polling today: each of 5 desks reloads queue + wait-estimate + doctor-delay (3 reads) every 15 s.
  const desks = 5, hours = 4, pollReads = desks * 3 * (hours * 3600 / 15);
  // Live: a snapshot per desk on connect, then one refetch of the 2 queue resources per delta that reaches a desk
  // (busy clinic: 1 check-in/move per minute), nursing 1 per 2 min, plus the 60 s fallback never runs while connected.
  const deltas = hours * 60 * 1 + hours * 30;
  const liveReads = desks * (LIVE_RESOURCES.length) + desks * deltas * 2;
  assert.ok(liveReads <= pollReads * 0.3, `live ${liveReads} vs polling ${pollReads} (${Math.round((1 - liveReads / pollReads) * 100)}% fewer)`);
});

// ---- BFF streaming proxy (FEF-020.4) -------------------------------------------------------------
test("GET /api/events streams frames through unbuffered, maps ?lastEventId to the header and aborts Core when the browser leaves", async () => {
  let seen = null, closed = false, res1;
  const core = http.createServer((rq, rs) => {
    seen = { url: rq.url, last: rq.headers["last-event-id"], cookie: rq.headers.cookie, auth: rq.headers.authorization };
    rs.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache", "content-encoding": "identity" });
    rs.write("event: snapshot\nid: 1\ndata: {}\n\n");
    res1 = rs; rq.on("close", () => { closed = true; });
  });
  await new Promise((r) => core.listen(0, r));
  const deps = { core: `http://127.0.0.1:${core.address().port}/v1`, fetch: (u, i) => fetch(u, i), origins: [], log: () => {} };
  const ctl = new AbortController();
  const res = await events(new Request("http://app.test/api/events?topics=queue&lastEventId=41", { headers: { cookie: "a=b" }, signal: ctl.signal }), deps);
  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-type"), /text\/event-stream/);
  assert.equal(res.headers.get("cache-control"), "no-cache, no-transform");
  assert.equal(res.headers.get("x-accel-buffering"), "no");
  const reader = res.body.getReader();
  const first = new TextDecoder().decode((await reader.read()).value);
  assert.match(first, /event: snapshot/, "first frame arrives while Core's response is still open");
  assert.equal(seen.url, "/v1/events?topics=queue");
  assert.equal(seen.last, "41");
  assert.equal(seen.cookie, undefined, "browser cookies never reach Core");
  res1.write("event: delta\nid: 2\ndata: {\"type\":\"queue.entry.updated\",\"id\":\"q1\",\"v\":2}\n\n");
  assert.match(new TextDecoder().decode((await reader.read()).value), /event: delta/);
  ctl.abort(); await reader.cancel().catch(() => {});
  for (let i = 0; i < 50 && !closed; i++) await new Promise((r) => setTimeout(r, 20));
  assert.ok(closed, "Core saw the connection close");
  core.closeAllConnections(); core.close();
});

test("a Core refusal (403 TOPIC_FORBIDDEN) passes through as Problem+JSON", async () => {
  const deps = { core: "http://core.test/v1", log: () => {}, origins: [],
    fetch: async () => Response.json({ code: "TOPIC_FORBIDDEN", status: 403 }, { status: 403, headers: { "content-type": "application/problem+json" } }) };
  const res = await events(new Request("http://app.test/api/events?topics=nursing"), deps);
  assert.equal(res.status, 403);
  assert.equal((await res.json()).code, "TOPIC_FORBIDDEN");
  assert.equal(res.headers.get("x-accel-buffering"), null);
});

// ---- AC1 latency, synthetic probe against a mock Core (real EventSource, real BFF proxy) ----------
test("probe: 100 deltas reach the stream client with p95 < 2 s (mock Core; the real backend run waits on ACC-021)", async () => {
  const { execFileSync } = await import("node:child_process");
  const out = execFileSync(process.execPath, ["--experimental-eventsource", "--disable-warning=ExperimentalWarning", "--disable-warning=MODULE_TYPELESS_PACKAGE_JSON", new URL("../e2e/s13-fef020-probe.mjs", import.meta.url).pathname], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });
  assert.match(out, /samples=100 /);
  assert.match(out, /PASS probe/);
});
