// FEF-020: one shared SSE connection per browser. React-free so it runs under node:test with fakes.
// A Web Locks leader owns the EventSource and fans every message out over BroadcastChannel('nabd-sse');
// without Web Locks every tab keeps its own connection (no channel, so nothing is delivered twice).
// Contract (ACC-021, GET /v1/events through the BFF): events snapshot | delta | heartbeat, data {type,id,v,...},
// the SSE `id:` is the resume cursor (sent back as Last-Event-ID, see lastEventId below).

export type LiveMessage = { event: "snapshot" | "delta"; data: Record<string, unknown> };
/** off = no stream wanted; paused = hidden tab closed the stream on purpose (not a fault). */
export type LiveStatus = "connecting" | "live" | "reconnecting" | "paused";

type Timer = ReturnType<typeof setTimeout>;
type ES = { close(): void; onopen: ((e: unknown) => void) | null; onerror: ((e: unknown) => void) | null; addEventListener(t: string, h: (e: { data: string; lastEventId: string }) => void): void };
type Chan = { postMessage(m: unknown): void; close(): void; onmessage: ((e: { data: unknown }) => void) | null };
export type StreamDeps = {
  EventSource: new (url: string, init?: { withCredentials: boolean }) => ES;
  locks: { request(name: string, cb: () => Promise<unknown>): Promise<unknown> } | null;
  BroadcastChannel: new (name: string) => Chan;
  isHidden(): boolean;
  onVisibility(cb: () => void): () => void;
  setTimeout(f: () => void, ms: number): Timer;
  clearTimeout(t: Timer | undefined): void;
  setInterval(f: () => void, ms: number): Timer;
  clearInterval(t: Timer | undefined): void;
  random(): number;
};

export const RECONNECT_BANNER_MS = 5_000; // AC3: indicator after 5 s without a reconnect
export const FALLBACK_POLL_MS = 60_000;
export const HIDDEN_CLOSE_MS = 5 * 60_000; // AC4
export const IDLE_MS = 60_000; // heartbeat is 25 s: 60 s of silence = dead stream
const BACKOFF_BASE = 1_000;
const BACKOFF_MAX = 30_000;

export const backoffMs = (attempt: number, random: number) => Math.min(BACKOFF_MAX, BACKOFF_BASE * 2 ** attempt) * (0.5 + random * 0.5);

function browserDeps(): StreamDeps {
  return {
    EventSource: EventSource as unknown as StreamDeps["EventSource"],
    locks: typeof navigator !== "undefined" && navigator.locks ? { request: (n, cb) => navigator.locks.request(n, cb) } : null,
    BroadcastChannel: BroadcastChannel as unknown as StreamDeps["BroadcastChannel"],
    isHidden: () => document.visibilityState === "hidden",
    onVisibility: (cb) => { document.addEventListener("visibilitychange", cb); return () => document.removeEventListener("visibilitychange", cb); },
    setTimeout: (f, ms) => setTimeout(f, ms), clearTimeout: (t) => clearTimeout(t),
    setInterval: (f, ms) => setInterval(f, ms), clearInterval: (t) => clearInterval(t),
    random: Math.random,
  };
}

type Wire =
  | { k: "msg"; m: LiveMessage } | { k: "status"; s: LiveStatus }
  | { k: "hello"; tab: string } | { k: "vis"; tab: string; idle: boolean } | { k: "bye"; tab: string };

export function createLiveStream(o: {
  url: string; // e.g. /api/events?topics=queue,nursing,doctor-status
  onMessage(m: LiveMessage): void;
  onStatus(s: LiveStatus): void;
  onFallbackPoll(): void; // the caller invalidates its live queries
  deps?: Partial<StreamDeps>;
}): { stop(): void } {
  const d: StreamDeps = o.deps?.EventSource ? (o.deps as StreamDeps) : { ...browserDeps(), ...o.deps }; // tests pass a full set
  const tab = `${d.random().toString(36).slice(2)}${Date.now().toString(36)}`;
  const chan = d.locks ? new d.BroadcastChannel("nabd-sse") : null;
  let status: LiveStatus = "connecting";
  let stopped = false, leader = false, paused = false;
  let es: ES | null = null;
  let lastId = "";
  let lastSnapshot: LiveMessage | null = null;
  let attempt = 0;
  let retryT: Timer | undefined, bannerT: Timer | undefined, idleT: Timer | undefined, pollT: Timer | undefined, hiddenT: Timer | undefined;
  let release: (() => void) | null = null;
  const peers = new Map<string, boolean>(); // other tabs -> hidden for more than 5 min
  let selfIdle = false;

  const send = (w: Wire) => chan?.postMessage(w);
  function setStatus(s: LiveStatus, broadcast = true) {
    if (s === status) return;
    status = s;
    if (s === "reconnecting") pollT ??= d.setInterval(o.onFallbackPoll, FALLBACK_POLL_MS);
    else { d.clearInterval(pollT); pollT = undefined; }
    o.onStatus(s);
    if (broadcast && leader) send({ k: "status", s });
  }
  const deliver = (m: LiveMessage) => { if (m.event === "snapshot") lastSnapshot = m; o.onMessage(m); if (leader) send({ k: "msg", m }); };

  function armIdle() {
    d.clearTimeout(idleT);
    idleT = d.setTimeout(() => { if (es) dropped(); }, IDLE_MS);
  }
  function dropped() {
    es?.close(); es = null;
    d.clearTimeout(idleT);
    if (stopped || paused) return;
    bannerT ??= d.setTimeout(() => { bannerT = undefined; if (!es) setStatus("reconnecting"); }, RECONNECT_BANNER_MS);
    d.clearTimeout(retryT);
    retryT = d.setTimeout(connect, backoffMs(attempt++, d.random()));
  }
  function connect() {
    if (stopped || paused || es) return;
    const u = o.url + (lastId ? `${o.url.includes("?") ? "&" : "?"}lastEventId=${encodeURIComponent(lastId)}` : "");
    const src = new d.EventSource(u, { withCredentials: true });
    es = src;
    src.onopen = () => { attempt = 0; d.clearTimeout(bannerT); bannerT = undefined; setStatus("live"); armIdle(); };
    src.onerror = () => { if (es === src) dropped(); };
    for (const event of ["snapshot", "delta", "heartbeat"] as const) {
      src.addEventListener(event, (e) => {
        if (es !== src) return;
        armIdle();
        if (e.lastEventId) lastId = e.lastEventId;
        if (event === "heartbeat") return;
        try { deliver({ event, data: JSON.parse(e.data) }); } catch { /* a malformed frame is skipped, the stream goes on */ }
      });
    }
  }
  const becomeLeader = () => { leader = true; paused = false; attempt = 0; connect(); send({ k: "status", s: status }); };

  // Pause: every known tab has been hidden for more than 5 minutes (a lone hidden tab is the common case).
  function evaluatePause() {
    if (!leader) return;
    const allIdle = selfIdle && [...peers.values()].every(Boolean);
    if (allIdle && !paused) {
      paused = true;
      es?.close(); es = null;
      for (const t of [retryT, bannerT, idleT]) d.clearTimeout(t);
      retryT = bannerT = idleT = undefined;
      setStatus("paused");
    } else if (!allIdle && paused) {
      paused = false;
      setStatus("connecting");
      connect(); // the new connection opens with a snapshot, which the app applies
    }
  }
  function setSelfIdle(v: boolean) { selfIdle = v; send({ k: "vis", tab, idle: v }); evaluatePause(); }
  function onVisibility() {
    d.clearTimeout(hiddenT); hiddenT = undefined;
    if (d.isHidden()) hiddenT = d.setTimeout(() => setSelfIdle(true), HIDDEN_CLOSE_MS);
    else if (selfIdle) setSelfIdle(false);
  }
  const offVis = d.onVisibility(onVisibility);
  if (d.isHidden()) onVisibility();

  if (chan) {
    chan.onmessage = ({ data }) => {
      const w = data as Wire;
      if (w.k === "msg") { if (!leader) o.onMessage(w.m); }
      else if (w.k === "status") { if (!leader) { setStatus(w.s, false); } }
      else if (w.k === "hello") {
        peers.set(w.tab, false);
        if (leader) { send({ k: "status", s: status }); if (lastSnapshot) send({ k: "msg", m: lastSnapshot }); send({ k: "vis", tab, idle: selfIdle }); }
      } else if (w.k === "vis") { peers.set(w.tab, w.idle); evaluatePause(); }
      else if (w.k === "bye") { peers.delete(w.tab); evaluatePause(); }
    };
    send({ k: "hello", tab });
    void d.locks!.request("nabd-sse-leader", () => new Promise<void>((res) => { release = res; if (stopped) res(); else becomeLeader(); }));
  } else becomeLeader();

  return {
    stop() {
      if (stopped) return;
      stopped = true;
      for (const t of [retryT, bannerT, idleT, hiddenT]) d.clearTimeout(t);
      d.clearInterval(pollT);
      es?.close(); es = null;
      offVis();
      send({ k: "bye", tab });
      release?.();
      if (chan) chan.onmessage = null;
      chan?.close();
    },
  };
}
