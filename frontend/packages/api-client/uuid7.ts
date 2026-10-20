/** RFC 9562 UUID v7: 48-bit unix ms, version 7, variant 10, 74 random bits. */
export function uuidv7(now = Date.now()): string {
  const b = crypto.getRandomValues(new Uint8Array(16));
  for (let i = 5, t = now; i >= 0; i--, t = Math.floor(t / 256)) b[i] = t % 256;
  b[6] = (b[6] & 0x0f) | 0x70;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
