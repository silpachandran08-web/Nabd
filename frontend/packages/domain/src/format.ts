// FEF-013: small display helpers every screen used its own copy of.

/** Whole years from `dob` ("YYYY-MM-DD") to `today` (birthday not yet reached counts one less); NaN for an unparseable dob.
 * Pure calendar arithmetic, never `new Date(dob)` (which is UTC midnight and shifts a day in negative-offset zones).
 * `today` is a "YYYY-MM-DD" (pass clinicToday() for the clinic day) or a Date read on its own wall clock. */
export function age(dob: string, today: Date | string = new Date()): number {
  const ymd = (s: string) => /^(\d{4})-(\d{2})-(\d{2})/.exec(s)?.slice(1).map(Number) ?? [NaN, NaN, NaN];
  const [y, m, d] = ymd(dob);
  const [ty, tm, td] = typeof today === "string" ? ymd(today) : [today.getFullYear(), today.getMonth() + 1, today.getDate()];
  return ty - y - (tm < m || (tm === m && td < d) ? 1 : 0);
}

/** Up to two upper-case initials ("asha  rao" -> "AR"); "" for a blank name. */
export function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase() ?? "").join("");
}

/** Whole minutes from `iso` to `now` (never negative); null when absent or unparseable. */
export function minutesSince(iso: string | undefined | null, now: number): number | null {
  if (!iso) return null;
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return null;
  return Math.max(0, Math.round((now - t) / 60000));
}
