// FEF-013: small display helpers every screen used its own copy of.

/** Whole years since `dob` (birthday not yet reached this year counts one less). */
export function age(dob: string, now: Date = new Date()): number {
  const d = new Date(dob);
  let a = now.getFullYear() - d.getFullYear();
  if (now.getMonth() < d.getMonth() || (now.getMonth() === d.getMonth() && now.getDate() < d.getDate())) a--;
  return a;
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
