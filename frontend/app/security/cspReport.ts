// FEF-010: CSP violation reports reduced to what is safe to log. No query strings, fragments, IDs, script samples or document content.

export const MAX_REPORT_BYTES = 8 * 1024;

export type Violation = { directive: string; blocked: string; document: string; source: string; disposition: string };

/** origin + path with any segment holding a digit replaced by ":id" (route template); keywords like "inline"/"eval" pass through. */
export function scrubUrl(raw: unknown): string {
  if (typeof raw !== "string" || raw === "") return "";
  if (/^[a-z-]{1,20}$/i.test(raw)) return raw; // inline, eval, wasm-eval, self ...
  try {
    const u = new URL(raw);
    if (u.protocol !== "http:" && u.protocol !== "https:") return u.protocol.replace(":", ""); // data:, blob: carry content
    const path = u.pathname.split("/").map((s) => (/\d/.test(s) ? ":id" : s)).join("/");
    return (u.origin + path).slice(0, 200);
  } catch {
    return "invalid";
  }
}

const short = (v: unknown) => (typeof v === "string" ? v.slice(0, 64) : "");

/** Accepts Reporting API (application/reports+json, an array) and legacy report-uri ({"csp-report": {...}}) bodies. */
export function toViolations(body: unknown): Violation[] {
  const items: Record<string, unknown>[] = [];
  if (Array.isArray(body)) {
    for (const r of body) if (r && typeof r === "object" && (r as { type?: unknown }).type === "csp-violation" && typeof (r as { body?: unknown }).body === "object" && (r as { body?: unknown }).body) items.push((r as { body: Record<string, unknown> }).body);
  } else if (body && typeof body === "object" && typeof (body as Record<string, unknown>)["csp-report"] === "object") {
    const c = (body as Record<string, Record<string, unknown>>)["csp-report"];
    if (c) items.push({ effectiveDirective: c["effective-directive"] ?? c["violated-directive"], blockedURL: c["blocked-uri"], documentURL: c["document-uri"], sourceFile: c["source-file"], disposition: c["disposition"] });
  }
  return items.slice(0, 20).map((b) => ({
    directive: short(b.effectiveDirective),
    blocked: scrubUrl(b.blockedURL),
    document: scrubUrl(b.documentURL),
    source: scrubUrl(b.sourceFile),
    disposition: short(b.disposition),
  }));
}

// shortcut: in-memory per-instance limiter; move to the edge/WAF rate limit when the BFF runs on more than one instance.
const WINDOW_MS = 60_000;
const MAX_PER_WINDOW = 60;
const hits = new Map<string, { start: number; n: number }>();
export function allow(key: string, now = Date.now()): boolean {
  const h = hits.get(key);
  if (!h || now - h.start >= WINDOW_MS) {
    if (hits.size > 10_000) hits.clear(); // bound memory under a spray of spoofed keys
    hits.set(key, { start: now, n: 1 });
    return true;
  }
  return ++h.n <= MAX_PER_WINDOW;
}
