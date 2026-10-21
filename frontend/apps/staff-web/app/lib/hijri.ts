/** REC-032: Hijri (Umm al-Qura) label for a "YYYY-MM-DD" date, e.g. "7 Jumada I 1448 AH". "" when the date is invalid. */
export function hijriLabel(iso: string | null | undefined): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso ?? "");
  if (!m) return "";
  const t = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]));
  if (t.getUTCMonth() !== +m[2] - 1 || t.getUTCDate() !== +m[3]) return ""; // rejects 2026-13-40 / 02-30 roll-over
  return new Intl.DateTimeFormat("en-GB-u-ca-islamic-umalqura", { day: "numeric", month: "long", year: "numeric", timeZone: "UTC" }).format(t);
}
