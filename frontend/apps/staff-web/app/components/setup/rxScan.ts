// PHL-013: pure rules of the Rx QR scan (no React, so node:test covers them). The payload is the backend's RxQr format:
// NRX1.<tenant>.<rx>.<signedAt>.<validUntil>.<kid>.<regNo>.<sig>, ES256 (raw r||s) over the UTF-8 bytes before the last dot.
import type { RxVerification } from "@nabd/api-client/generated";

export type RxQrClaims = { tenantId: string; prescriptionId: string; signedAt: string; validUntil: string | null; kid: string; regNo: string | null };
export type Jwks = { keys: (JsonWebKey & { kid?: string })[] };
export type OfflineResult =
  | { status: "invalid"; reason: "unreadable" | "signature" | "unknown-key" | "other-tenant" }
  | { status: "valid" | "expired"; claims: RxQrClaims };
export type ScanStatus = RxVerification["status"] | OfflineResult["status"];

/** The browser's copy of GET /pharmacy/rx-verifications/keys, for a scan with no network. */
export const KEY_CACHE = "nabd_rx_keys";
export const PREFIX = "NRX1";

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/** base64url to bytes by hand: the browser decoder is reserved for jwt.ts, and this input is bytes, not text. */
function b64url(s: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]*$/.test(s) || s.length % 4 === 1) throw new Error("not base64url");
  const out = new Uint8Array(Math.floor((s.length * 6) / 8));
  let bits = 0, acc = 0, n = 0;
  for (const c of s) {
    acc = (acc << 6) | ALPHABET.indexOf(c);
    bits += 6;
    if (bits >= 8) { bits -= 8; out[n++] = (acc >> bits) & 0xff; }
  }
  return out;
}

function uuid(b: Uint8Array): string {
  if (b.length !== 16) throw new Error("not a uuid");
  const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

function text(s: string): string | null {
  return s === "" ? null : new TextDecoder().decode(b64url(s));
}

/** The claims, the signed text and the signature; null when the text is not an Rx QR. Nothing is verified here. */
export function parseRxQr(raw: string): { claims: RxQrClaims; head: string; sig: Uint8Array } | null {
  const payload = raw.trim();
  const f = payload.split(".");
  if (f.length !== 8 || f[0] !== PREFIX) return null;
  try {
    const kid = text(f[5]);
    if (!kid || !/^\d+$/.test(f[3]) || !/^\d*$/.test(f[4])) return null;
    const claims: RxQrClaims = {
      tenantId: uuid(b64url(f[1])), prescriptionId: uuid(b64url(f[2])),
      signedAt: new Date(Number(f[3]) * 1000).toISOString(), validUntil: f[4] === "" ? null : new Date(Number(f[4]) * 1000).toISOString(),
      kid, regNo: text(f[6]),
    };
    return { claims, head: payload.slice(0, payload.lastIndexOf(".")), sig: b64url(f[7]) };
  } catch {
    return null;
  }
}

/** Signature against the cached public keys, the clinic against the session's tenant (the signing key is platform-wide, so a
 * genuine signature alone does not make it ours) and expiry against `now`, all in the browser (WebCrypto ES256). */
export async function verifyOffline(raw: string, jwks: Jwks | null, tenantId: string | null, now: Date = new Date(), subtle: SubtleCrypto = crypto.subtle): Promise<OfflineResult> {
  const p = parseRxQr(raw);
  if (!p) return { status: "invalid", reason: "unreadable" };
  const jwk = jwks?.keys.find((k) => k.kid === p.claims.kid);
  if (!jwk) return { status: "invalid", reason: "unknown-key" };
  let ok = false;
  try {
    const key = await subtle.importKey("jwk", { kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y }, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    ok = await subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, p.sig as BufferSource, new TextEncoder().encode(p.head) as BufferSource);
  } catch {
    ok = false;
  }
  if (!ok) return { status: "invalid", reason: "signature" };
  if (!tenantId || p.claims.tenantId.toLowerCase() !== tenantId.toLowerCase()) return { status: "invalid", reason: "other-tenant" };
  const expired = p.claims.validUntil !== null && now.getTime() >= new Date(p.claims.validUntil).getTime();
  return { status: expired ? "expired" : "valid", claims: p.claims };
}

export function cacheKeys(jwks: Jwks): void {
  try { localStorage.setItem(KEY_CACHE, JSON.stringify(jwks)); } catch { /* private mode: offline scans then fall back to "no key" */ }
}

export function cachedKeys(): Jwks | null {
  try {
    const raw = localStorage.getItem(KEY_CACHE);
    const v = raw ? (JSON.parse(raw) as Jwks) : null;
    return v && Array.isArray(v.keys) ? v : null;
  } catch {
    return null;
  }
}

/** A call that never reached the server (fetch TypeError, timeout) or a browser that knows it is offline. */
export function isOffline(e: unknown): boolean {
  if (typeof navigator !== "undefined" && navigator.onLine === false) return true;
  return e instanceof TypeError || (e instanceof Error && e.name === "TimeoutError");
}

/** Card colour: green verified, amber expired, red refused. Dispensed is done, not a refusal. */
export function tone(status: ScanStatus): "ok" | "warn" | "danger" {
  return status === "valid" ? "ok" : status === "invalid" ? "danger" : "warn";
}

/** The one line the pharmacist reads first. */
export function headline(status: ScanStatus, reason: string | null | undefined, validUntil: string | null | undefined, dispensedAt?: string | null): string {
  const day = (iso: string) => new Date(iso).toLocaleDateString([], { dateStyle: "medium" });
  switch (status) {
    case "valid": return "Verified prescription";
    case "expired": return validUntil ? `Expired on ${day(validUntil)}` : "Expired";
    case "dispensed": return dispensedAt ? `Already fully dispensed on ${day(dispensedAt)}` : "Already fully dispensed";
    default:
      if (reason === "other-tenant") return "Not from this clinic";
      if (reason === "unknown-key") return "Cannot verify offline: no key cached for this prescription";
      if (reason === "unreadable") return "Not a Nabd prescription QR";
      if (reason === "not-found") return "No such prescription at this clinic";
      return "Signature invalid";
  }
}
