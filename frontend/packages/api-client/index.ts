// FEF-007: the one typed API client (ADR-06). React-free and dependency-free so it is unit-tested with node:test.
// shortcut: no openapi-fetch / generated openapi.d.ts yet (offline sandbox, no new dependency); response types
// live in ./types.ts and are hand-patched until spec-first lands (T-089). Swap the transport, keep this surface.
// Never logs a token or a body.
import { uuidv7 } from "./uuid7.ts";

export { uuidv7 };

export type FieldError = { field: string; message: string };

/** Any non-2xx answer, parsed from Problem+JSON (RFC 7807). `problem` keeps extras such as `candidates`. */
export class ApiError extends Error {
  status: number; code: string | undefined; title: string; detail: string; errors: FieldError[]; problem: Record<string, unknown>;
  constructor(status: number, p: Record<string, unknown> = {}) {
    const detail = typeof p.detail === "string" ? p.detail : "";
    const title = typeof p.title === "string" ? p.title : "";
    super(detail || title || `HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.code = typeof p.code === "string" ? p.code : undefined;
    this.title = title;
    this.detail = detail;
    this.errors = Array.isArray(p.errors)
      ? (p.errors as Record<string, unknown>[]).filter((e) => e && typeof e.field === "string").map((e) => ({ field: String(e.field), message: String(e.message ?? "") }))
      : [];
    this.problem = p;
  }
  /** field -> first message, for mapping onto form inputs (aria-invalid). */
  fieldErrors(): Record<string, string> {
    const m: Record<string, string> = {};
    for (const e of this.errors) m[e.field] ??= e.message;
    return m;
  }
}

/** The request outlived `timeoutMs`. The server may still have acted: show "check status", never auto-retry. */
export class TimeoutError extends Error {
  timeoutMs: number;
  constructor(timeoutMs: number) { super(`Request timed out after ${timeoutMs} ms`); this.name = "TimeoutError"; this.timeoutMs = timeoutMs; }
}

export type ClientOptions = {
  baseUrl: string;
  getToken: () => string | null;
  /** Session is gone (refresh refused, or a 401 survived a refresh). */
  onAuthFailure: () => void;
  /** Refreshes the session; resolves true when getToken() now returns a new token. Called single-flight. */
  refresh?: (staleToken: string | null) => Promise<boolean>;
  /** True when the token is ageing and should be refreshed before the call. */
  shouldRefresh?: (token: string | null) => boolean;
  language?: () => string | undefined;
  /** Step-up middleware (FEF-018): a 403 "step-up-required" calls this; a returned token replays the same request ONCE
   * with X-Step-Up-Token (same Idempotency-Key). null = the user cancelled, the 403 is thrown as an ApiError. */
  stepUp?: (problem: Record<string, unknown>) => Promise<string | null>;
  timeoutMs?: number;
  retryDelayMs?: number;
  fetch?: typeof fetch;
};

/** `as: "blob" | "text"` returns the body as a Blob / string (file downloads, CSV); errors are still parsed as Problem+JSON. */
export type RequestOptions = {
  body?: unknown; signal?: AbortSignal; idempotencyKey?: string; as?: "blob" | "text"; headers?: Record<string, string>;
  /** A 401 is the answer (e.g. a wrong MFA code), not an expired session: no refresh, no sign-out. */
  keepSession?: boolean;
};

const MUTATING = new Set(["POST", "PUT", "PATCH"]);
const RETRY_STATUS = new Set([502, 503, 504]);
const MAX_RETRIES = 2;

export function createClient(o: ClientOptions) {
  const doFetch = o.fetch ?? ((...a: Parameters<typeof fetch>) => globalThis.fetch(...a));
  const timeoutMs = o.timeoutMs ?? 15000;
  let inflight: Promise<boolean> | null = null;
  const refreshOnce = (stale: string | null) => (inflight ??= o.refresh!(stale).finally(() => { inflight = null; }));

  async function attempt(method: string, url: string, token: string | null, opts: RequestOptions, key: string | undefined): Promise<Response> {
    const ctl = new AbortController();
    let timedOut = false;
    const t = setTimeout(() => { timedOut = true; ctl.abort(); }, timeoutMs);
    const onAbort = () => ctl.abort();
    if (opts.signal?.aborted) ctl.abort(); else opts.signal?.addEventListener("abort", onAbort, { once: true });
    const headers: Record<string, string> = { "X-Request-Id": crypto.randomUUID() };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (opts.body !== undefined) headers["Content-Type"] = "application/json";
    if (key) headers["Idempotency-Key"] = key;
    if (opts.headers) Object.assign(headers, opts.headers);
    const lang = o.language ? o.language() : typeof navigator !== "undefined" ? navigator.language : undefined;
    if (lang) headers["Accept-Language"] = lang;
    try {
      return await doFetch(url, { method, headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body), signal: ctl.signal });
    } catch (e) {
      if (timedOut) throw new TimeoutError(timeoutMs);
      throw e;
    } finally {
      clearTimeout(t);
      opts.signal?.removeEventListener("abort", onAbort);
    }
  }

  async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
    const url = `${o.baseUrl}${path}`;
    const mutating = MUTATING.has(method);
    const key = mutating ? opts.idempotencyKey ?? uuidv7() : undefined; // one key for every automatic retry of this call
    let token = o.getToken();
    if (token && o.refresh && o.shouldRefresh?.(token)) {
      try { if (await refreshOnce(token)) token = o.getToken(); } catch { /* try the old token */ }
    }
    let res: Response | undefined;
    for (let n = 0; ; n++) {
      try {
        res = await attempt(method, url, token, opts, key);
        if (res.status === 401 && o.refresh && token && !opts.keepSession) {
          if (await refreshOnce(token)) { token = o.getToken(); res = await attempt(method, url, token, opts, key); }
        }
        if (!(mutating && RETRY_STATUS.has(res.status) && n < MAX_RETRIES)) break;
      } catch (e) {
        // Network errors only; a timeout, a caller abort or a refresh failure is not retried.
        const net = e instanceof TypeError;
        if (!(mutating && net && n < MAX_RETRIES) || opts.signal?.aborted) throw e;
      }
      const base = o.retryDelayMs ?? 300;
      await new Promise((r) => setTimeout(r, base * 2 ** n * (0.5 + Math.random())));
    }
    if (res.status === 401 && !opts.keepSession) o.onAuthFailure();
    if (!res.ok) {
      const problem: Record<string, unknown> = (await res.json().catch(() => null)) ?? {};
      if (res.status === 403 && o.stepUp && !opts.headers?.["X-Step-Up-Token"] && String(problem.type ?? "").endsWith("/step-up-required")) {
        const t = await o.stepUp(problem);
        if (t) return request<T>(method, path, { ...opts, idempotencyKey: key, headers: { ...opts.headers, "X-Step-Up-Token": t } });
      }
      throw new ApiError(res.status, problem);
    }
    if (res.status === 204) return undefined as T;
    if (opts.as === "blob") return (await res.blob()) as T;
    if (opts.as === "text") return (await res.text()) as T;
    return (await res.json()) as T;
  }

  return {
    request,
    get: <T>(path: string, opts?: Omit<RequestOptions, "body">) => request<T>("GET", path, opts),
    post: <T>(path: string, body?: unknown, opts?: Omit<RequestOptions, "body">) => request<T>("POST", path, { ...opts, body }),
    put: <T>(path: string, body?: unknown, opts?: Omit<RequestOptions, "body">) => request<T>("PUT", path, { ...opts, body }),
    patch: <T>(path: string, body?: unknown, opts?: Omit<RequestOptions, "body">) => request<T>("PATCH", path, { ...opts, body }),
    delete: <T>(path: string, opts?: Omit<RequestOptions, "body">) => request<T>("DELETE", path, opts),
  };
}
export type ApiClient = ReturnType<typeof createClient>;

/** One Idempotency-Key per user intent: the same key while the same body is re-sent (user retry after an error),
 * a new one when the body changed. Call done(name) after success so the next action gets a fresh key. */
export function intentKeys() {
  const m = new Map<string, { sig: string; key: string }>();
  return {
    get(name: string, body: unknown): string {
      const sig = JSON.stringify(body);
      const cur = m.get(name);
      if (cur && cur.sig === sig) return cur.key;
      const key = uuidv7();
      m.set(name, { sig, key });
      return key;
    },
    done(name: string) { m.delete(name); },
  };
}
