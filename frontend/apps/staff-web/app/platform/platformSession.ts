// One place for the operator-console's authed fetch + 401 handling (PLT-037). It used to be
// copy-pasted into billing, plans, provisioning, access, support and audit, and only some copies
// cleared the session on 401. A 401 now tries POST /platform/auth/refresh once (single-flight, so
// parallel requests share one refresh) and replays the request; only if that fails is the session
// cleared and the operator sent to login.
// Location: kept beside the operator console (app/platform/) rather than app/lib/, which holds the
// clinic session (lib/session.ts). Every consumer lives under app/platform/, the two sessions use
// different tokens and refresh endpoints, and colocating keeps a clinic page from importing it by mistake.
// Token storage keys appear only in this file; login, logout and the nav go through the helpers below.
// PLT-008: kept free of React/Next imports so the 401 handling is unit-tested (tests/unit/platformSession.test.mjs).

// Same value as surfaces.ts API_BASE; read directly (as lib/session.ts does) so node can load this file in unit tests.
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const ACCESS_KEY = "nabd_platform_access_token";
const REFRESH_KEY = "nabd_platform_refresh_token";

let refreshing: Promise<boolean> | null = null;

function refreshSession(): Promise<boolean> {
  if (refreshing) return refreshing;
  refreshing = (async () => {
    const refreshToken = localStorage.getItem(REFRESH_KEY);
    if (!refreshToken) return false;
    try {
      const res = await fetch(`${API_BASE}/platform/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken }),
      });
      if (!res.ok) return false;
      const pair: { accessToken?: string; refreshToken?: string } = await res.json();
      if (!pair.accessToken) return false;
      localStorage.setItem(ACCESS_KEY, pair.accessToken);
      if (pair.refreshToken) localStorage.setItem(REFRESH_KEY, pair.refreshToken);
      return true;
    } catch {
      return false;
    } finally {
      // Let the next 401 (minutes later) trigger a fresh refresh.
      setTimeout(() => { refreshing = null; }, 0);
    }
  })();
  return refreshing;
}

// Set when a 401 ended the session, so every redirect (including later calls that find no token)
// lands on the login page with the "expired" message; a fresh sign-in resets it.
let expired = false;

export const SESSION_EXPIRED_MESSAGE = "Your session expired. Sign in again.";

/** Where to send the operator when platformFetch returns null. */
export function platformLoginPath(): string {
  return expired ? "/platform/login?expired=1" : "/platform/login";
}

export function clearPlatformSession() {
  localStorage.removeItem(ACCESS_KEY);
  localStorage.removeItem(REFRESH_KEY);
}

export function hasPlatformSession(): boolean {
  return !!localStorage.getItem(ACCESS_KEY);
}

/** Login and MFA verify hand their token pair here, so the storage keys live in this file only. */
export function storePlatformSession(pair: { accessToken: string; refreshToken: string }) {
  expired = false;
  localStorage.setItem(ACCESS_KEY, pair.accessToken);
  localStorage.setItem(REFRESH_KEY, pair.refreshToken);
}

/** Ends the server session (best effort) and clears the local one. The caller routes to login. */
export async function platformLogout(): Promise<void> {
  const token = localStorage.getItem(ACCESS_KEY);
  if (token) await send("/platform/auth/logout", { method: "POST" }, token).catch(() => {});
  clearPlatformSession();
}

function send(path: string, init: RequestInit | undefined, token: string) {
  const headers = new Headers(init?.headers);
  headers.set("Authorization", `Bearer ${token}`);
  if (typeof init?.body === "string" && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  return fetch(`${API_BASE}${path}`, { ...init, headers });
}

/** Authed fetch. Resolves to null when there is no session or a 401 survives one refresh (the session
 * is then cleared and marked expired); the caller routes to platformLoginPath(). Never called by the
 * login page, so an expired session cannot loop. */
export async function platformFetch(path: string, init?: RequestInit): Promise<Response | null> {
  const token = localStorage.getItem(ACCESS_KEY);
  if (!token) return null;
  let res = await send(path, init, token);
  if (res.status === 401) {
    if (await refreshSession()) {
      res = await send(path, init, localStorage.getItem(ACCESS_KEY) ?? "");
    }
    if (res.status === 401) {
      clearPlatformSession();
      expired = true;
      return null;
    }
  }
  return res;
}
