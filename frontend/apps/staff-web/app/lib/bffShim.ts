// FEF-009 dual mode. NEXT_PUBLIC_AUTH_MODE=cookie (build time) routes every Core call through the same-origin BFF
// (lib/bff.ts): `${API_BASE}/auth/*` -> /api/auth/*, other `${API_BASE}/*` -> /api/v1/*, with the CSRF header on
// mutations. Unset (default) = today's bearer mode, nothing here runs. Screens keep calling fetch(`${API_BASE}...`);
// the platform console stays bearer (its own cookies are a later step).
import { API_BASE } from "./connection.ts";

export const COOKIE_AUTH = process.env.NEXT_PUBLIC_AUTH_MODE === "cookie";
const CSRF = "__Host-nabd_csrf";
const SESSION_HEADER = "x-nabd-session";
const MUTATING = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/** BFF URL for a Core URL, or null when the call is not ours to reroute. */
export function bffUrl(url: string, base = API_BASE): string | null {
  if (!url.startsWith(base + "/") || url.startsWith(base + "/platform/")) return null;
  const rest = url.slice(base.length);
  return rest.startsWith("/auth/") ? "/api" + rest : "/api/v1" + rest;
}

export function csrfToken(): string | null {
  const hit = document.cookie.split("; ").find((c) => c.startsWith(CSRF + "="));
  return hit ? hit.slice(CSRF.length + 1) : null;
}

let installed = false;
export function installBffShim(): void {
  if (!COOKIE_AUTH || installed || typeof window === "undefined") return;
  installed = true;
  const orig = window.fetch.bind(window);
  window.fetch = async (input, init) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    const to = bffUrl(url);
    if (!to) return orig(input, init);
    const req = input instanceof Request ? input : undefined;
    const headers = new Headers(init?.headers ?? req?.headers);
    if (MUTATING.has((init?.method ?? req?.method ?? "GET").toUpperCase())) {
      if (!csrfToken()) await orig("/api/session").catch(() => null); // sets the double-submit cookie
      const t = csrfToken();
      if (t) headers.set("X-CSRF-Token", t);
    }
    const res = await orig(req ? new Request(to, req) : to, { ...init, headers, credentials: "same-origin" });
    const fresh = res.headers.get(SESSION_HEADER); // the BFF refreshed: keep the claims snapshot current
    if (fresh) try { localStorage.setItem("nabd_access_token", fresh); } catch { /* storage blocked: claims refresh next time */ }
    return res;
  };
}
installBffShim();
