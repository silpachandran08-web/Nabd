// FEF-061: small pure helpers shared by the link views.
export type T = (key: string, vars?: Record<string, string>) => string;

export const makeT = (messages: Record<string, string>): T => (key, vars) =>
  (messages[key] ?? key).replace(/\{(\w+)\}/g, (_, k: string) => vars?.[k] ?? "");

/** One date formatter for the whole app (patient locale, optional clinic time zone). */
export function formatWhen(iso: string, locale: string, opts: { time?: boolean; timeZone?: string } = {}): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: "medium", ...(opts.time ? { timeStyle: "short" } : {}), timeZone: opts.timeZone }).format(d);
  } catch { return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(d); }
}

/** UUID for Idempotency-Key; crypto.randomUUID needs a secure context, a plain-http preview does not have one. */
export function newKey(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
