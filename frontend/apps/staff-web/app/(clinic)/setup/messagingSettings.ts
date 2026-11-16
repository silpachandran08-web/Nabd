// COM-024: the clinic's messaging settings (GET/PUT /v1/setup/messaging-settings). Pure: unit-tested directly.
export const METHODS = ["umm_al_qura", "qatar", "dubai", "mwl"] as const;
export type Method = (typeof METHODS)[number];
export type MessagingSettings = {
  quietHours: { enabled: boolean; start: string; end: string };
  prayerWindows: { enabled: boolean; method: Method; bufferMinutes: number; fridayBlock: { start: string; end: string }; latitude: number | null; longitude: number | null };
};

/** The same checks as the server (422 INVALID_WINDOW), so the owner sees the reason before saving; the i18n key of the first problem, or null. */
export function invalidReason(s: MessagingSettings): string | null {
  const q = s.quietHours, p = s.prayerWindows;
  if (q.start === q.end) return "sameQuiet";
  if (p.fridayBlock.start >= p.fridayBlock.end) return "friday";
  if (!Number.isInteger(p.bufferMinutes) || p.bufferMinutes < 0 || p.bufferMinutes > 60) return "buffer";
  if ((p.latitude == null) !== (p.longitude == null)) return "coords";
  if (p.latitude != null && (Math.abs(p.latitude) > 66 || Math.abs(p.longitude ?? 0) > 180)) return "coords";
  if (p.enabled && p.latitude == null) return "needCoords";
  return null;
}

/** "" in a number field means not set. */
export const toCoord = (v: string): number | null => (v.trim() === "" || Number.isNaN(Number(v)) ? null : Number(v));
