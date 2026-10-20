// LIVE-03: the one last-visit formatter for the patient list and the patient drawer. Accepts the
// registry's calendar date ("2026-10-17") or an ISO instant (the drawer's lastVisitAt); a date is
// formatted as-is (UTC, so it never shifts a day), an instant in the viewer's local time.
export function formatLastVisit(value: string | null | undefined): string {
  if (!value) return "No visits yet";
  const dateOnly = /^\d{4}-\d{2}-\d{2}$/.test(value);
  const d = new Date(dateOnly ? `${value}T00:00:00Z` : value);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric", ...(dateOnly ? { timeZone: "UTC" } : {}) });
}
