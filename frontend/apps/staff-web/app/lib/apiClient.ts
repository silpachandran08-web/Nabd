// FEF-002 (interim, FEF-009 moves tokens to httpOnly cookies): the clinic app's one refresh-aware fetch.
// Access tokens live 15 min; refresh tokens rotate, so two refreshes with the same token trip reuse
// detection and revoke the whole session family. Hence refresh is single-flight in a tab (one promise),
// across tabs (Web Locks, localStorage mutex fallback) and the new pair is announced on BroadcastChannel.
// React-free so it is unit-tested (tests/unit/apiClient.test.mjs). Never logs a token.
import { decodeJwtPayload } from "./jwt.ts";
import { clearLocalSessionData } from "./idleLock.ts";
import { COOKIE_AUTH } from "./bffShim.ts";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const ACCESS_KEY = "nabd_access_token";

const REFRESH_KEY = "nabd_refresh_token";
const RETURN_KEY = "nabd_return_to";
const MUTEX_KEY = "nabd_refresh_mutex";
const DEFAULT_TTL_S = 15 * 60;

/** A page-level fetch bound to the session: null when the session ended (the page is already redirecting). */
export type AuthedFetch = (path: string, init?: RequestInit) => Promise<Response | null>;

type Claims = { exp?: number; iat?: number };
function claims(token: string | null): Claims {
  return (token ? decodeJwtPayload<Claims>(token) : null) ?? {};
}

/** True once 80% of the token's lifetime has passed. An unreadable token is left to the reactive 401 path. */
export function needsRefresh(token: string | null, nowMs = Date.now()): boolean {
  if (COOKIE_AUTH) return false; // FEF-009: the BFF refreshes server-side
  const { exp, iat } = claims(token);
  if (!exp) return false;
  const start = iat ?? exp - DEFAULT_TTL_S;
  return nowMs >= (start + 0.8 * (exp - start)) * 1000;
}

/** Same-origin path only: starts with a single "/", no backslash, never the login page itself. */
export function safeReturnTo(p: string | null | undefined): string | null {
  if (!p || p[0] !== "/" || p[1] === "/" || p.includes("\\") || /[\u0000-\u001f]/.test(p)) return null;
  return p === "/login" || p.startsWith("/login?") || p.startsWith("/login/") ? null : p;
}

/** Login page: the validated returnTo (query param first, then sessionStorage), consumed once. */
export function takeReturnTo(): string | null {
  const q = new URLSearchParams(window.location.search).get("returnTo");
  const stored = sessionStorage.getItem(RETURN_KEY);
  sessionStorage.removeItem(RETURN_KEY);
  return safeReturnTo(q) ?? safeReturnTo(stored);
}

let leaving = false;
/** Session is gone: clear tokens, remember where the user was, go to /login once (6 parallel 401s = 1 redirect). */
export function goLogin(): void {
  localStorage.removeItem(ACCESS_KEY);
  localStorage.removeItem(REFRESH_KEY);
  void idbPair(null);
  void clearLocalSessionData();
  if (leaving) return;
  const here = window.location.pathname + window.location.search;
  if (window.location.pathname.startsWith("/login")) return;
  leaving = true;
  const to = safeReturnTo(here);
  if (to) sessionStorage.setItem(RETURN_KEY, to);
  window.location.replace(to ? `/login?returnTo=${encodeURIComponent(to)}` : "/login");
}

// ---- refresh -------------------------------------------------------------------------------------
type Pair = { accessToken?: string; refreshToken?: string };
let channel: BroadcastChannel | null = null;
const waiters = new Set<(ok: boolean) => void>();
function bc(): BroadcastChannel | null {
  if (channel || typeof BroadcastChannel === "undefined") return channel;
  channel = new BroadcastChannel("nabd-auth");
  channel.onmessage = (e) => {
    if (e.data?.type === "refreshed") {
      // The announce carries the pair for a tab waiting on it: its own localStorage read may still lag behind the writer's.
      const { accessToken: a, refreshToken: r } = e.data as Pair;
      if (waiters.size && a && r && (claims(a).exp ?? 0) > (claims(localStorage.getItem(ACCESS_KEY)).exp ?? 0)) { localStorage.setItem(ACCESS_KEY, a); localStorage.setItem(REFRESH_KEY, r); }
      schedule(); waiters.forEach((w) => w(true));
    }
  };
  (channel as { unref?: () => void }).unref?.(); // Node (unit tests) only; browsers have no unref
  return channel;
}

// IndexedDB mirror of the last rotated pair. localStorage reads taken right after a cross-tab lock hand-off
// can still be stale (the tab that rotated has stored, this one has not seen it yet); IndexedDB is
// transactional, so the lock holder writes it before releasing and the next holder reads the truth.
// Best effort: without IndexedDB (or on error) the localStorage value is used as before.
function idbPair(write: Pair | null | undefined): Promise<Pair | null> {
  return new Promise((resolve) => {
    if (typeof indexedDB === "undefined") return resolve(null);
    const open = indexedDB.open("nabd-auth", 1);
    open.onupgradeneeded = () => open.result.createObjectStore("kv");
    open.onerror = () => resolve(null);
    open.onsuccess = () => {
      const db = open.result;
      const tx = db.transaction("kv", "readwrite");
      const st = tx.objectStore("kv");
      const req = write === undefined ? st.get("pair") : write ? st.put(write, "pair") : st.delete("pair");
      tx.oncomplete = () => { db.close(); resolve(write === undefined ? (req.result ?? null) : null); };
      tx.onerror = tx.onabort = () => { db.close(); resolve(null); };
    };
  });
}

/** Stores a freshly issued pair (login, refresh, PIN change) and arms the proactive refresh. */
export function storeTokens(pair: Pair): void {
  if (pair.accessToken) localStorage.setItem(ACCESS_KEY, pair.accessToken);
  if (pair.refreshToken) localStorage.setItem(REFRESH_KEY, pair.refreshToken);
  schedule();
}

/** After the lock: the newer of localStorage and the IndexedDB mirror, written back to localStorage. Only while a
 * session exists in localStorage, so a stale mirror can never resurrect a logged-out session. */
async function freshestRefreshToken(): Promise<string | null> {
  const mine = localStorage.getItem(REFRESH_KEY);
  if (!mine) return null;
  const m = await idbPair(undefined);
  const exp = (t?: string | null) => claims(t ?? null).exp ?? 0;
  if (m?.accessToken && m.refreshToken && exp(m.accessToken) > exp(localStorage.getItem(ACCESS_KEY))) {
    localStorage.setItem(ACCESS_KEY, m.accessToken);
    localStorage.setItem(REFRESH_KEY, m.refreshToken);
    return m.refreshToken;
  }
  return mine;
}

async function callRefresh(): Promise<boolean> {
  const refreshToken = localStorage.getItem(REFRESH_KEY); // already synced with the mirror by the lock holder
  if (!refreshToken) return false;
  const res = await fetch(`${API_BASE}/auth/refresh`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ refreshToken }),
  });
  if (res.status === 401 || res.status === 403) { // only this ends the session
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
    await idbPair(null);
    return false;
  }
  if (!res.ok) throw new Error(`refresh failed: ${res.status}`); // 5xx/429: keep tokens, caller sees a failed request
  const pair: Pair = await res.json();
  if (!pair.accessToken) throw new Error("refresh failed: no token");
  storeTokens(pair);
  await idbPair(pair); // before the lock is released, so the next holder never reads the used token
  bc()?.postMessage({ type: "refreshed", accessToken: pair.accessToken, refreshToken: pair.refreshToken });
  return true;
}

// shortcut: fallback mutex polls localStorage (not atomic across tabs); only for browsers without Web Locks.
async function withMutex<T>(fn: () => Promise<T>): Promise<T> {
  const me = `${Math.random()}:`;
  const deadline = Date.now() + 5000;
  for (;;) {
    const cur = localStorage.getItem(MUTEX_KEY);
    if (!cur || Date.now() - Number(cur.split(":")[1]) > 2000 || Date.now() > deadline) {
      localStorage.setItem(MUTEX_KEY, me + Date.now());
      await new Promise((r) => setTimeout(r, 20));
      if (localStorage.getItem(MUTEX_KEY)?.startsWith(me) || Date.now() > deadline) break;
    }
    await new Promise((r) => setTimeout(r, 50));
  }
  try { return await fn(); } finally {
    if (localStorage.getItem(MUTEX_KEY)?.startsWith(me)) localStorage.removeItem(MUTEX_KEY);
  }
}

let inflight: Promise<boolean> | null = null;
/** Refreshes the session once however many callers/tabs ask. `stale` is the access token the caller
 * just saw rejected (or ageing): if storage already holds a different, fresh one, another tab did it. */
export function refreshSession(stale: string | null): Promise<boolean> {
  if (COOKIE_AUTH) return cookieSession(); // FEF-009: GET /api/session refreshes via the cookie if it can
  if (inflight) return inflight;
  const locked = async (): Promise<boolean> => {
    if (localStorage.getItem(REFRESH_KEY)) await freshestRefreshToken(); // adopt a pair another tab rotated moments ago
    const now = localStorage.getItem(ACCESS_KEY);
    if (now && now !== stale && !needsRefresh(now)) { schedule(); return true; } // adopt its pair
    return callRefresh();
  };
  const run = typeof navigator !== "undefined" && navigator.locks
    ? navigator.locks.request("nabd-refresh", locked)
    : withMutex(locked);
  const announced = new Promise<boolean>((res) => { bc(); waiters.add(res); });
  inflight = Promise.race([run, announced]).finally(() => { inflight = null; waiters.clear(); });
  return inflight;
}

async function cookieSession(): Promise<boolean> {
  const res = await fetch("/api/session", { cache: "no-store" });
  if (res.status === 401) return false;
  if (!res.ok) throw new Error(`session check failed: ${res.status}`);
  const s: { accessToken?: string } = await res.json();
  if (s.accessToken) localStorage.setItem(ACCESS_KEY, s.accessToken); // claims snapshot, not a token
  return true;
}

let timer: ReturnType<typeof setTimeout> | undefined;
function schedule(): void {
  if (typeof window === "undefined" || COOKIE_AUTH) return;
  clearTimeout(timer);
  const token = localStorage.getItem(ACCESS_KEY);
  const { exp, iat } = claims(token);
  if (!token || !exp) return;
  const start = iat ?? exp - DEFAULT_TTL_S;
  const wait = Math.max(0, (start + 0.8 * (exp - start)) * 1000 - Date.now());
  timer = setTimeout(() => {
    // The token this timer was set for, not whatever storage holds now: if another tab already rotated it, that is "adopt", not "stale".
    if (localStorage.getItem(ACCESS_KEY)) void refreshSession(token).then((ok) => { if (!ok) goLogin(); }).catch(() => { /* offline: next request retries */ });
  }, Math.min(wait, 2 ** 31 - 1));
  (timer as { unref?: () => void }).unref?.();
}
/** PLT-007: another tab dropped the session (logout, revoked refresh) -> this tab signs out too. */
export function onStorage(e: { key: string | null; newValue: string | null }): void {
  if (e.key !== ACCESS_KEY) return;
  if (e.newValue === null) goLogin(); else schedule();
}

/** PLT-007: revocation (suspension, role change, clinic suspended, 12 h cap) reaches an idle tab within 60 s. */
export const PROBE_MS = 60_000;
export async function probeSession(): Promise<void> {
  if (!localStorage.getItem(ACCESS_KEY)) return;
  try { if ((await apiFetch("/auth/sessions")).status === 401) goLogin(); } catch { /* offline: try again next tick */ }
}

if (typeof window !== "undefined") {
  schedule();
  window.addEventListener("storage", onStorage);
  (setInterval(() => void probeSession(), PROBE_MS) as { unref?: () => void }).unref?.();
}

/** RFC 9562 UUIDv7: 48-bit ms timestamp, then random. */
export function uuidv7(nowMs = Date.now()): string {
  const b = crypto.getRandomValues(new Uint8Array(16));
  for (let i = 0; i < 6; i++) b[i] = Math.floor(nowMs / 2 ** (8 * (5 - i))) & 0xff;
  b[6] = (b[6] & 0x0f) | 0x70;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

// ---- fetch ---------------------------------------------------------------------------------------
function send(path: string, init: RequestInit | undefined, token: string | null): Promise<Response> {
  const headers = new Headers(init?.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return fetch(`${API_BASE}${path}`, { ...init, headers });
}

/** fetch(API_BASE + path) with the session token; refreshes first when the token is ageing and once on
 * a 401, then replays the request. A 401 that survives refresh is returned (tokens already cleared) so
 * the caller's existing 401 branch runs goLogin(). Network errors reject, as plain fetch does. */
export async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  // PLT-026: one key per user action, kept across the 401 replay below. A caller that retries after a network
  // error passes its own Idempotency-Key header so the server sees the same action.
  if (init?.method?.toUpperCase() === "POST" && !new Headers(init.headers).has("Idempotency-Key")) {
    const headers = new Headers(init.headers);
    headers.set("Idempotency-Key", uuidv7());
    init = { ...init, headers };
  }
  let token = localStorage.getItem(ACCESS_KEY);
  if (token && needsRefresh(token)) {
    try { if (await refreshSession(token)) token = localStorage.getItem(ACCESS_KEY); } catch { /* try the old token */ }
  }
  const res = await send(path, init, token);
  if (res.status !== 401 || !token) return res;
  if (await refreshSession(token)) return send(path, init, localStorage.getItem(ACCESS_KEY));
  localStorage.removeItem(ACCESS_KEY);
  localStorage.removeItem(REFRESH_KEY);
  return res;
}

/** Idle-lock unlock outcome. Call it after a successful refresh: the backend answers a wrong PIN with 401
 * as well as an expired token, so 401 means "wrong PIN" only because the token is known fresh. */
export function unlockMessage(status: number, retryAfter: string | null, sessionGone: boolean): string {
  if (sessionGone) return "Session expired, sign in again";
  if (status === 429) {
    const s = Math.max(1, Math.ceil(Number(retryAfter)) || 1);
    return `Too many attempts, wait ${s} s`;
  }
  return "Incorrect PIN";
}
