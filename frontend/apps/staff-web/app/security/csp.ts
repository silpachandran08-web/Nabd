// FEF-010: per-request nonce CSP and the other security headers, applied by proxy.ts. Pure: no Next imports, so unit tests load it directly.

export const REPORT_PATH = "/api/csp-report";

/** 128-bit random nonce, base64. Web Crypto, so it runs in both proxy runtimes. */
export function newNonce(): string {
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  return btoa(String.fromCharCode(...b));
}

/** API origin the browser calls directly; null when the API is same-origin (relative base). */
export function apiOrigin(base: string | undefined): string | null {
  try { return new URL(base ?? "http://localhost:8080/v1").origin; } catch { return null; }
}

export function buildCsp(nonce: string, opts: { dev?: boolean; connect?: string | null } = {}): string {
  return [
    "default-src 'self'",
    // React needs eval only in `next dev` for its error overlay; never in a production build.
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${opts.dev ? " 'unsafe-eval'" : ""}`,
    `style-src 'self' 'nonce-${nonce}'`,
    // React style={{}} props are server-rendered as style="" attributes; they cannot run script.
    "style-src-attr 'unsafe-inline'",
    "img-src 'self' data: blob:",
    "font-src 'self'",
    // shortcut: the API is still cross-origin (Render) until the FEF-009 BFF serves /api/v1; then drop the extra origin.
    `connect-src 'self'${opts.connect ? " " + opts.connect : ""}`,
    "frame-ancestors 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    "object-src 'none'",
    `report-uri ${REPORT_PATH}`,
    "report-to csp",
  ].join("; ");
}

/** Enforce only when NABD_CSP_MODE=enforce; anything else stays report-only (pilot week, AC-2). */
export function cspHeaderName(mode: string | undefined): string {
  return mode === "enforce" ? "Content-Security-Policy" : "Content-Security-Policy-Report-Only";
}

export const STATIC_HEADERS: Record<string, string> = {
  "Strict-Transport-Security": "max-age=63072000; includeSubDomains; preload",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "same-origin",
  "Cross-Origin-Opener-Policy": "same-origin",
  "X-Frame-Options": "DENY",
  "Permissions-Policy": "camera=(self), microphone=(), geolocation=(), payment=()",
  "Reporting-Endpoints": `csp="${REPORT_PATH}"`,
};
