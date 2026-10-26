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

// FEF-018: the operator console's client is a second instance of the shared one (packages/api-client) with its own
// token keys and its own refresh endpoint; it never reads or writes the clinic session. Same value as lib/api.ts.
import { createClient, type ApiClient } from "@nabd/api-client";

const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const ACCESS_KEY = "nabd_platform_access_token";
const REFRESH_KEY = "nabd_platform_refresh_token";

const refreshClient = createClient({ baseUrl: BASE_URL, getToken: () => null, onAuthFailure: () => {} });

/** POST /platform/auth/refresh once (the client shares one call between parallel 401s) and keep the rotated pair. */
async function refreshSession(): Promise<boolean> {
  const refreshToken = localStorage.getItem(REFRESH_KEY);
  if (!refreshToken) return false;
  try {
    const pair = await refreshClient.post<{ accessToken?: string; refreshToken?: string }>("/platform/auth/refresh", { refreshToken }, { keepSession: true });
    if (!pair.accessToken) return false;
    localStorage.setItem(ACCESS_KEY, pair.accessToken);
    if (pair.refreshToken) localStorage.setItem(REFRESH_KEY, pair.refreshToken);
    return true;
  } catch {
    return false;
  }
}

// Set when a 401 ended the session, so every redirect (including later calls that find no token)
// lands on the login page with the "expired" message; a fresh sign-in resets it.
let expired = false;

export const SESSION_EXPIRED_MESSAGE = "Your session expired. Sign in again.";

/** Where to send the operator once the session is gone. */
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
  if (token) await platformApi.post("/platform/auth/logout", undefined, { keepSession: true }).catch(() => {});
  clearPlatformSession();
}

/** The operator console's API client. A 401 that survives one refresh clears the operator session, marks it expired and
 * sends the operator to login (the call then throws ApiError 401). Never used by the login page, so no loop. */
export const platformApi: ApiClient = createClient({
  baseUrl: BASE_URL,
  getToken: () => localStorage.getItem(ACCESS_KEY),
  refresh: refreshSession,
  onAuthFailure: () => {
    expired = expired || hasPlatformSession(); // a visitor who never signed in gets the plain login, not "expired"
    clearPlatformSession();
    if (typeof window !== "undefined" && !window.location.pathname.startsWith("/platform/login")) window.location.replace(platformLoginPath());
  },
});
