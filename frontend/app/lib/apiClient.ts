// FEF-002 (interim, FEF-009 moves tokens to httpOnly cookies): the clinic app's one refresh-aware fetch.
// Access tokens live 15 min; refresh tokens rotate, so two refreshes with the same token trip reuse
// detection and revoke the whole session family. Hence refresh is single-flight in a tab (one promise),
// across tabs (Web Locks, localStorage mutex fallback) and the new pair is announced on BroadcastChannel.
// React-free so it is unit-tested (tests/unit/apiClient.test.mjs). Never logs a token.
import { decodeJwtPayload } from "./jwt.ts";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const ACCESS_KEY = "nabd_access_token";

const REFRESH_KEY = "nabd_refresh_token";
const RETURN_KEY = "nabd_return_to";
const MUTEX_KEY = "nabd_refresh_mutex";
const DEFAULT_TTL_S = 15 * 60;

type Claims = { exp?: number; iat?: number };
function claims(token: string | null): Claims {
  return (token ? decodeJwtPayload<Claims>(token) : null) ?? {};
}

/** True once 80% of the token's lifetime has passed. An unreadable token is left to the reactive 401 path. */
export function needsRefresh(token: string | null, nowMs = Date.now()): boolean {
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
    if (e.data?.type === "refreshed") { schedule(); waiters.forEach((w) => w(true)); }
  };
  (channel as { unref?: () => void }).unref?.(); // Node (unit tests) only; browsers have no unref
  return channel;
}

/** Stores a freshly issued pair (login, refresh, PIN change) and arms the proactive refresh. */
export function storeTokens(pair: Pair): void {
  if (pair.accessToken) localStorage.setItem(ACCESS_KEY, pair.accessToken);
  if (pair.refreshToken) localStorage.setItem(REFRESH_KEY, pair.refreshToken);
  schedule();
}

async function callRefresh(): Promise<boolean> {
  const refreshToken = localStorage.getItem(REFRESH_KEY);
  if (!refreshToken) return false;
  const res = await fetch(`${API_BASE}/auth/refresh`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ refreshToken }),
  });
  if (!res.ok) return false;
  const pair: Pair = await res.json();
  if (!pair.accessToken) return false;
  storeTokens(pair);
  bc()?.postMessage({ type: "refreshed" });
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
  if (inflight) return inflight;
  const locked = async (): Promise<boolean> => {
    const now = localStorage.getItem(ACCESS_KEY);
    if (now && now !== stale && !needsRefresh(now)) return true;
    return callRefresh();
  };
  const run = typeof navigator !== "undefined" && navigator.locks
    ? navigator.locks.request("nabd-refresh", locked)
    : withMutex(locked);
  const announced = new Promise<boolean>((res) => { bc(); waiters.add(res); });
  inflight = Promise.race([run, announced]).finally(() => { inflight = null; waiters.clear(); });
  return inflight;
}

let timer: ReturnType<typeof setTimeout> | undefined;
function schedule(): void {
  if (typeof window === "undefined") return;
  clearTimeout(timer);
  const token = localStorage.getItem(ACCESS_KEY);
  const { exp, iat } = claims(token);
  if (!token || !exp) return;
  const start = iat ?? exp - DEFAULT_TTL_S;
  const wait = Math.max(0, (start + 0.8 * (exp - start)) * 1000 - Date.now());
  timer = setTimeout(() => {
    const t = localStorage.getItem(ACCESS_KEY);
    if (t) void refreshSession(t).then((ok) => { if (!ok) goLogin(); }).catch(() => { /* offline: next request retries */ });
  }, Math.min(wait, 2 ** 31 - 1));
  (timer as { unref?: () => void }).unref?.();
}
if (typeof window !== "undefined") {
  schedule();
  window.addEventListener("storage", (e) => { if (e.key === ACCESS_KEY) schedule(); });
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
