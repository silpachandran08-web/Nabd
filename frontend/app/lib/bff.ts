// FEF-009 (ADR-06): the staff-web BFF. Next route handlers under /api/* call these; the browser never sees a
// Core token. Refresh token in __Host-nabd_rt, access token in __Host-nabd_at (both httpOnly), CSRF double-submit
// cookie __Host-nabd_csrf echoed as X-CSRF-Token plus an Origin check. Web-standard Request/Response only, so it is
// unit-tested with node:test (tests/unit/s11-fef009-bff.test.mjs). Never logs a body, a cookie or a token.

import { decodeJwtPayload } from "./jwt.ts";

export const AT = "__Host-nabd_at";
export const RT = "__Host-nabd_rt";
export const CSRF = "__Host-nabd_csrf";
export const SESSION_HEADER = "x-nabd-session"; // fresh claims snapshot after a BFF-side refresh

// The browser keeps a claims-only snapshot (payload of the access token, NO signature, alg "none") so the existing
// permission/landing code keeps decoding it. It authenticates nothing: the BFF drops it and uses the cookie.
const CLAIMS_HEAD = "eyJhbGciOiJub25lIiwidHlwIjoibmFiZC1jbGFpbXMifQ"; // {"alg":"none","typ":"nabd-claims"}
const DEFAULT_REFRESH_S = 30 * 24 * 3600; // Core before refreshExpiresIn existed (app.auth.refresh-token-ttl-days)
const REFRESH_EARLY_S = 30;
const GRACE_MS = 10_000;
const SAFE = new Set(["GET", "HEAD", "OPTIONS"]);
const DROP_REQ = new Set(["connection", "keep-alive", "proxy-authorization", "proxy-connection", "te", "trailer",
  "transfer-encoding", "upgrade", "host", "cookie", "content-length", "origin", "referer", "authorization", "x-csrf-token"]);
const DROP_RES = new Set(["connection", "keep-alive", "proxy-authenticate", "te", "trailer", "transfer-encoding",
  "upgrade", "set-cookie", "content-length", "content-encoding", "cache-control", "access-control-allow-origin",
  "access-control-allow-credentials", "access-control-allow-headers", "access-control-allow-methods", "access-control-expose-headers"]);

export type Deps = { core: string; fetch: typeof fetch; origins?: string[]; log?: (line: string) => void };
type Pair = { accessToken: string; refreshToken: string; expiresIn?: number; refreshExpiresIn?: number };
type Refreshed = { ok: true; pair: Pair } | { ok: false; status: number };

export function defaultDeps(): Deps {
  return {
    core: (process.env.NABD_CORE_URL ?? process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1").replace(/\/$/, ""),
    fetch: (...a) => globalThis.fetch(...a),
    origins: process.env.NABD_BFF_ORIGINS?.split(",").map((s) => s.trim()).filter(Boolean),
    log: (l) => console.log(l),
  };
}

// ---- cookies -------------------------------------------------------------------------------------
export function parseCookies(header: string | null): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of (header ?? "").split(";")) {
    const i = part.indexOf("=");
    if (i > 0) out[part.slice(0, i).trim()] = part.slice(i + 1).trim();
  }
  return out;
}
const cookie = (name: string, value: string, maxAge: number, httpOnly = true) =>
  `${name}=${value}; Path=/; Max-Age=${Math.max(0, Math.floor(maxAge))}; Secure; SameSite=Strict${httpOnly ? "; HttpOnly" : ""}`;
const clearCookies = () => [cookie(AT, "", 0), cookie(RT, "", 0), cookie(CSRF, "", 0, false)];
function randomToken(): string {
  const b = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function sessionCookies(pair: Pair, csrf: string | undefined): string[] {
  const rtAge = pair.refreshExpiresIn ?? DEFAULT_REFRESH_S;
  return [cookie(AT, pair.accessToken, pair.expiresIn ?? 900), cookie(RT, pair.refreshToken, rtAge), cookie(CSRF, csrf ?? randomToken(), rtAge, false)];
}

// ---- claims --------------------------------------------------------------------------------------
const payload = (token: string | undefined) => (token ? decodeJwtPayload<Record<string, unknown>>(token) : null);
export const claimsToken = (accessToken: string) => `${CLAIMS_HEAD}.${accessToken.split(".")[1] ?? ""}.`;
const isClaimsAuth = (h: string | null) => !!h && h.startsWith(`Bearer ${CLAIMS_HEAD}.`);
function sessionBody(accessToken: string) {
  const c = payload(accessToken) ?? {};
  return { staffId: c.sub, name: c.staffName, tenantId: c.tenantId, tenantName: c.tenantName, permissions: c.permissions ?? [], exp: c.exp, accessToken: claimsToken(accessToken) };
}
function expiring(at: string | undefined, nowS: number): boolean {
  const exp = payload(at)?.exp;
  return typeof exp !== "number" || exp - REFRESH_EARLY_S <= nowS;
}

// ---- problems ------------------------------------------------------------------------------------
function problem(status: number, code: string, detail: string, cookies: string[] = []): Response {
  const h = new Headers({ "content-type": "application/problem+json", "cache-control": "no-store" });
  cookies.forEach((c) => h.append("set-cookie", c));
  return new Response(JSON.stringify({ type: `https://nabd.health/problems/${code.toLowerCase()}`, title: code, status, code, detail }), { status, headers: h });
}

// ---- CSRF ----------------------------------------------------------------------------------------
function sameish(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}
function selfOrigin(req: Request): string {
  const u = new URL(req.url);
  const host = req.headers.get("x-forwarded-host") ?? req.headers.get("host") ?? u.host;
  const proto = req.headers.get("x-forwarded-proto") ?? u.protocol.replace(":", "");
  return `${proto}://${host}`;
}
/** Origin must be ours; the token must match the cookie unless `tokenOptional` (pre-session auth steps with no session cookie). */
export function csrfOk(req: Request, deps: Deps, tokenOptional = false): boolean {
  const origin = req.headers.get("origin");
  if (!origin || !(deps.origins?.length ? deps.origins : [selfOrigin(req)]).includes(origin)) return false;
  const jar = parseCookies(req.headers.get("cookie"));
  if (tokenOptional && !jar[RT] && !jar[AT]) return true;
  const sent = req.headers.get("x-csrf-token");
  return !!sent && !!jar[CSRF] && sameish(sent, jar[CSRF]);
}

// ---- refresh (single-flight per session) ---------------------------------------------------------
// Keyed by the refresh token (one per session). The result stays for GRACE_MS so a request that left the browser
// with the old cookie just before rotation gets the new pair instead of tripping Core's reuse detection.
// shortcut: per-process map; with more than one BFF replica use sticky sessions (or a shared store) before scaling out.
const flights = new Map<string, Promise<Refreshed>>();
export function refreshOnce(rt: string, deps: Deps, fwd: Headers): Promise<Refreshed> {
  const hit = flights.get(rt);
  if (hit) return hit;
  const p = (async (): Promise<Refreshed> => {
    const h = new Headers({ "content-type": "application/json" });
    for (const k of ["x-forwarded-for", "user-agent", "x-request-id"]) { const v = fwd.get(k); if (v) h.set(k, v); }
    const res = await deps.fetch(`${deps.core}/auth/refresh`, { method: "POST", headers: h, body: JSON.stringify({ refreshToken: rt }) });
    if (!res.ok) return { ok: false, status: res.status };
    const pair = (await res.json()) as Pair;
    return pair.accessToken && pair.refreshToken ? { ok: true, pair } : { ok: false, status: 502 };
  })();
  flights.set(rt, p);
  const drop = () => { const t = setTimeout(() => flights.delete(rt), GRACE_MS); (t as { unref?: () => void }).unref?.(); };
  p.then(drop, () => flights.delete(rt));
  return p;
}

type Access = { at?: string; cookies: string[]; refreshed: boolean; fail?: Response };
async function access(req: Request, deps: Deps): Promise<Access> {
  const jar = parseCookies(req.headers.get("cookie"));
  if (!jar[RT] || !expiring(jar[AT], Date.now() / 1000)) return { at: jar[AT], cookies: [], refreshed: false };
  let r: Refreshed;
  try { r = await refreshOnce(jar[RT], deps, req.headers); } catch { r = { ok: false, status: 503 }; }
  if (r.ok) return { at: r.pair.accessToken, cookies: sessionCookies(r.pair, jar[CSRF]), refreshed: true };
  if (r.status === 401 || r.status === 403) return { cookies: [], refreshed: false, fail: problem(401, "SESSION_EXPIRED", "Your session has ended. Sign in again.", clearCookies()) };
  return { cookies: [], refreshed: false, fail: problem(503, "SESSION_REFRESH_UNAVAILABLE", "Couldn't renew your session right now. Try again in a moment.") };
}

// ---- proxy ---------------------------------------------------------------------------------------
/** Proxies one request to Core `corePath` (e.g. "/queue?x=1"). `authStep`: an /api/auth/* call (pre-session steps
 * pass with Origin alone, and cookie auth is attached only when the client asked for an authenticated call). */
export async function proxy(req: Request, corePath: string, deps: Deps = defaultDeps(), authStep = false): Promise<Response> {
  const started = Date.now();
  const reqId = req.headers.get("x-request-id") ?? crypto.randomUUID();
  const template = authStep ? "/api/auth/*" : "/api/v1/*";
  const done = (res: Response) => { deps.log?.(`bff ${req.method} ${template} ${res.status} ${Date.now() - started}ms req=${reqId}`); return res; };
  if (!SAFE.has(req.method) && !csrfOk(req, deps, authStep)) return done(problem(403, "CSRF_FAILED", "This request didn't come from a Nabd page. Reload and try again."));

  const clientAuth = req.headers.get("authorization");
  const useCookie = clientAuth ? isClaimsAuth(clientAuth) : !authStep;
  const a: Access = useCookie ? await access(req, deps) : { cookies: [], refreshed: false };
  if (a.fail) return done(a.fail);

  const headers = new Headers();
  req.headers.forEach((v, k) => { if (!DROP_REQ.has(k)) headers.set(k, v); });
  headers.set("x-request-id", reqId);
  const auth = useCookie ? (a.at ? `Bearer ${a.at}` : null) : clientAuth;
  if (auth) headers.set("authorization", auth);

  let res: Response;
  try {
    res = await deps.fetch(`${deps.core}${corePath}`, {
      method: req.method, headers, redirect: "manual",
      body: SAFE.has(req.method) ? undefined : req.body, duplex: "half",
    } as RequestInit);
  } catch {
    return done(problem(502, "CORE_UNREACHABLE", "Couldn't reach the server. Check your connection and try again."));
  }

  const out = new Headers();
  res.headers.forEach((v, k) => { if (!DROP_RES.has(k)) out.set(k, v); });
  out.set("cache-control", "no-store");
  const cookies = [...a.cookies];
  let body: BodyInit | null = res.body;
  // Any token pair Core issues (login, MFA, OTP, refresh, invite accept, workspace select, break-glass) becomes cookies.
  if (res.ok && !corePath.startsWith("/platform/") && (res.headers.get("content-type") ?? "").includes("json")) {
    const text = await res.text();
    body = text;
    let j: Record<string, unknown> | null = null;
    try { j = JSON.parse(text); } catch { /* not JSON after all: pass through */ }
    if (j && typeof j.accessToken === "string" && typeof j.refreshToken === "string") {
      const pair = j as unknown as Pair;
      cookies.length = 0;
      cookies.push(...sessionCookies(pair, undefined));
      const { accessToken: _a, refreshToken: _r, ...rest } = j;
      void _a; void _r;
      body = JSON.stringify({ ...rest, ...sessionBody(pair.accessToken) });
      out.set(SESSION_HEADER, claimsToken(pair.accessToken));
    }
  }
  if (a.refreshed && a.at && !out.has(SESSION_HEADER)) out.set(SESSION_HEADER, claimsToken(a.at));
  cookies.forEach((c) => out.append("set-cookie", c));
  return done(new Response(res.status === 204 || res.status === 304 ? null : body, { status: res.status, statusText: res.statusText, headers: out }));
}

/** GET /api/session: the current session's claims (refreshing first when the access cookie is gone or ageing). */
export async function session(req: Request, deps: Deps = defaultDeps()): Promise<Response> {
  const jar = parseCookies(req.headers.get("cookie"));
  const csrf = jar[CSRF] ? [] : [cookie(CSRF, randomToken(), DEFAULT_REFRESH_S, false)]; // bootstraps double-submit
  const a = await access(req, deps);
  if (a.fail) return a.fail;
  if (!a.at) return problem(401, "NO_SESSION", "You're not signed in.", csrf);
  const h = new Headers({ "content-type": "application/json", "cache-control": "no-store" });
  [...csrf, ...a.cookies].forEach((c) => h.append("set-cookie", c));
  return new Response(JSON.stringify(sessionBody(a.at)), { status: 200, headers: h });
}

/** POST /api/auth/logout: CSRF-checked; Core revokes the session family; all three cookies cleared either way. */
export async function logout(req: Request, deps: Deps = defaultDeps()): Promise<Response> {
  if (!csrfOk(req, deps)) return problem(403, "CSRF_FAILED", "This request didn't come from a Nabd page. Reload and try again.");
  const a = await access(req, deps);
  if (a.at) {
    try {
      await deps.fetch(`${deps.core}/auth/logout`, { method: "POST", headers: { authorization: `Bearer ${a.at}`, "x-request-id": req.headers.get("x-request-id") ?? crypto.randomUUID() } });
    } catch { /* cookies go regardless; the session then dies at its own expiry */ }
  }
  const h = new Headers({ "cache-control": "no-store" });
  clearCookies().forEach((c) => h.append("set-cookie", c));
  return new Response(null, { status: 204, headers: h });
}

/** /api/auth/refresh: rotates with the cookie (the client has no refresh token to send). */
export async function refreshStep(req: Request, deps: Deps = defaultDeps()): Promise<Response> {
  if (!csrfOk(req, deps)) return problem(403, "CSRF_FAILED", "This request didn't come from a Nabd page. Reload and try again.");
  const jar = parseCookies(req.headers.get("cookie"));
  if (!jar[RT]) return problem(401, "NO_SESSION", "You're not signed in.");
  const r = await refreshOnce(jar[RT], deps, req.headers).catch((): Refreshed => ({ ok: false, status: 503 }));
  if (!r.ok) return r.status === 401 || r.status === 403 ? problem(401, "SESSION_EXPIRED", "Your session has ended. Sign in again.", clearCookies()) : problem(503, "SESSION_REFRESH_UNAVAILABLE", "Couldn't renew your session right now.");
  const h = new Headers({ "content-type": "application/json", "cache-control": "no-store", [SESSION_HEADER]: claimsToken(r.pair.accessToken) });
  sessionCookies(r.pair, jar[CSRF]).forEach((c) => h.append("set-cookie", c));
  return new Response(JSON.stringify({ expiresIn: r.pair.expiresIn, ...sessionBody(r.pair.accessToken) }), { status: 200, headers: h });
}

/** Core path for an incoming BFF URL: /api/v1/x?y -> /x?y, /api/auth/x -> /auth/x. */
export function corePathOf(req: Request, prefix: "/api/v1" | "/api"): string {
  const u = new URL(req.url);
  return u.pathname.slice(prefix.length) + u.search;
}
