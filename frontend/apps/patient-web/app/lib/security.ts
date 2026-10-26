// FEF-061: nonce CSP and response headers for patient-web. Pure (no Next imports) so unit tests load it directly.
// Same shape as staff-web (FEF-010); differences: blob: frames for the document preview, no report-uri, enforced by default.

export function newNonce(): string {
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  return btoa(String.fromCharCode(...b));
}

/** API origin the browser calls; null when the API base is relative (same origin). */
export function apiOrigin(base: string | undefined): string | null {
  try { return new URL(base ?? "http://localhost:8080/v1").origin; } catch { return null; }
}

export function buildCsp(nonce: string, opts: { dev?: boolean; connect?: string | null } = {}): string {
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${opts.dev ? " 'unsafe-eval'" : ""}`,
    `style-src 'self' 'nonce-${nonce}'`,
    "style-src-attr 'unsafe-inline'",
    "img-src 'self' data: blob:",
    "font-src 'self'",
    "frame-src blob:", // the PDF preview is a blob: URL made from the authenticated download
    `connect-src 'self'${opts.connect ? " " + opts.connect : ""}`,
    "frame-ancestors 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    "object-src 'none'",
  ].join("; ");
}

/** Enforce unless NABD_CSP_MODE=report-only. */
export const cspHeaderName = (mode: string | undefined) => (mode === "report-only" ? "Content-Security-Policy-Report-Only" : "Content-Security-Policy");

/** NFR: no PHI in caches or referrers. Hashed /_next/static files carry no PHI and keep their immutable caching (see next.config.ts). */
export const NO_STORE = "no-store, max-age=0";
export const STATIC_HEADERS: Record<string, string> = {
  "Strict-Transport-Security": "max-age=63072000; includeSubDomains; preload",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "Cross-Origin-Opener-Policy": "same-origin",
  "X-Frame-Options": "DENY",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=()",
  "X-Robots-Tag": "noindex, nofollow",
};

/** The link id in the path is the only identifier in the URL: opaque, URL-safe, no meaning. */
export const LINK_ID = /^[A-Za-z0-9_-]{16,128}$/;
