/** The only place allowed to call atob (eslint no-restricted-globals). Decodes a JWT payload as
 * UTF-8 (atob alone yields a byte string, which garbles Arabic/Hindi/Kannada/Telugu names).
 * Moves to packages/domain in FEF-013. Returns null for a malformed token. */
export function decodeJwtPayload<T = Record<string, unknown>>(token: string): T | null {
  try {
    return JSON.parse(new TextDecoder().decode(base64UrlBytes(token.split(".")[1])));
  } catch {
    return null;
  }
}

/** base64url (padded or not) -> bytes; throws on malformed input. FEF-048 also uses it for the VAPID public key. */
export function base64UrlBytes(s: string): Uint8Array<ArrayBuffer> {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(b64.padEnd(b64.length + ((4 - (b64.length % 4)) % 4), "="));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}
