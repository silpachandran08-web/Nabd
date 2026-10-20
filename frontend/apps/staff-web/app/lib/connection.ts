// WB-FEF-021: connection status for the top bar, derived from the app's real API calls.
// There is no offline write queue and no health endpoint today, so this only reports what was
// observed: browser offline, repeated API failures, slow answers and the last good answer time.
export const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
export const FAIL_LIMIT = 3; // consecutive 5xx / network failures before "Reconnecting"
export const SLOW_MS = 5000;
export const PROBE_MS = 10_000;

export type ApiEvent = { ok: boolean; slow?: boolean };

/** Wraps window.fetch to report API-bound calls. 4xx is a working server; 5xx or a network error is a failure. Returns an undo. */
export function watchApiFetch(report: (e: ApiEvent) => void): () => void {
  const orig = window.fetch;
  window.fetch = async (input, init) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    if (!url.startsWith(API_BASE)) return orig(input, init);
    const slowTimer = setTimeout(() => report({ ok: true, slow: true }), SLOW_MS);
    const started = Date.now();
    try {
      const res = await orig(input, init);
      report({ ok: res.status < 500, slow: Date.now() - started > SLOW_MS });
      return res;
    } catch (e) {
      if (!(e instanceof DOMException && e.name === "AbortError")) report({ ok: false });
      throw e;
    } finally {
      clearTimeout(slowTimer);
    }
  };
  return () => { window.fetch = orig; };
}

/** Next state after one observed call. ok:true && slow:true while pending only flags slowness, it never clears failures. */
export function nextConn(s: { fails: number; slow: boolean; lastOkAt: number | null }, e: ApiEvent, now = Date.now()) {
  if (!e.ok) return { ...s, fails: s.fails + 1 };
  if (e.slow) return { ...s, slow: true };
  return { fails: 0, slow: false, lastOkAt: now };
}
