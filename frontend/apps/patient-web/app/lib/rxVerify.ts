// CLN-023: the pure part of the public Rx verify page (no React, so node:test covers it). The QR is <patient-web>/rx/<token>; the token
// is the signed NRX1 payload NRX1.<tenant>.<rx>.<signedAt>.<validUntil>.<kid>.<regNo>.<sig>, ES256 (raw r||s) over the UTF-8 bytes
// before the last dot. With no network the page checks the signature itself against the published keys (GET /public/rx/keys, cached
// in this browser): authenticity and validity only, since dispensing and revocation live on the server.

export type RxStatus = "valid" | "expired" | "dispensed" | "revoked";
export type RxVerification = {
  status: RxStatus;
  doctor: { name: string | null; registrationNo: string | null; council: string | null };
  issuedAt: string; validUntil: string | null; lineCount: number; dispensedAt: string | null;
};
export type Jwks = { keys: (JsonWebKey & { kid?: string })[] };
export type OfflineCheck =
  | { ok: false }
  | { ok: true; expired: boolean; issuedAt: string; validUntil: string | null; registrationNo: string | null };

export const KEY_CACHE = "nabd_rx_public_keys";
/** What the route accepts as a token (anything else reads as unknown without a call). */
export const TOKEN = /^NRX1(\.[A-Za-z0-9_-]*){7}$/;
export const MAX_TOKEN = 1000;

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

function b64url(s: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]*$/.test(s) || s.length % 4 === 1) throw new Error("not base64url");
  const out = new Uint8Array(Math.floor((s.length * 6) / 8));
  let bits = 0, acc = 0, n = 0;
  for (const c of s) {
    acc = ((acc << 6) | ALPHABET.indexOf(c)) & 0xffffff;
    bits += 6;
    if (bits >= 8) { bits -= 8; out[n++] = (acc >> bits) & 0xff; }
  }
  return out;
}

export function isToken(t: string | null | undefined): t is string {
  return typeof t === "string" && t.length <= MAX_TOKEN && TOKEN.test(t);
}

/** Signature against the cached keys and validity against `now`, in the browser (WebCrypto ES256). */
export async function verifyOffline(token: string, jwks: Jwks | null, now: Date = new Date(), subtle: SubtleCrypto = crypto.subtle): Promise<OfflineCheck> {
  if (!isToken(token)) return { ok: false };
  const f = token.split(".");
  try {
    const kid = new TextDecoder().decode(b64url(f[5]));
    const jwk = jwks?.keys.find((k) => k.kid === kid);
    if (!jwk || !/^\d+$/.test(f[3]) || !/^\d*$/.test(f[4])) return { ok: false };
    const key = await subtle.importKey("jwk", { kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y }, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    const head = token.slice(0, token.lastIndexOf("."));
    const ok = await subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, b64url(f[7]) as BufferSource, new TextEncoder().encode(head) as BufferSource);
    if (!ok) return { ok: false };
    const validUntil = f[4] === "" ? null : new Date(Number(f[4]) * 1000).toISOString();
    return {
      ok: true, issuedAt: new Date(Number(f[3]) * 1000).toISOString(), validUntil,
      expired: validUntil !== null && now.getTime() >= new Date(validUntil).getTime(),
      registrationNo: f[6] === "" ? null : new TextDecoder().decode(b64url(f[6])),
    };
  } catch {
    return { ok: false };
  }
}

export function cacheKeys(jwks: Jwks): void {
  try { localStorage.setItem(KEY_CACHE, JSON.stringify(jwks)); } catch { /* private mode: an offline check then has no key */ }
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

/** Badge colour: green valid, amber expired or dispensed (done, not forged), red revoked. */
export function tone(s: RxStatus): "ok" | "warn" | "alert" {
  return s === "valid" ? "ok" : s === "revoked" ? "alert" : "warn";
}
