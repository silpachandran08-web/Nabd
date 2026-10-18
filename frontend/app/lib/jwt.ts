/** The only place allowed to call atob (eslint no-restricted-globals). Decodes a JWT payload as
 * UTF-8 (atob alone yields a byte string, which garbles Arabic/Hindi/Kannada/Telugu names).
 * Moves to packages/domain in FEF-013. Returns null for a malformed token. */
export function decodeJwtPayload<T = Record<string, unknown>>(token: string): T | null {
  try {
    const b64 = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const bin = atob(b64.padEnd(b64.length + ((4 - (b64.length % 4)) % 4), "="));
    return JSON.parse(new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0))));
  } catch {
    return null;
  }
}
