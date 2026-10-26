// FEF-028: pure board logic (buckets, tabs, labels, counts). No React, no imports of runtime code, so node can test it.
export type Bucket = "waiting" | "in_consult" | "checkout_pending" | "completed" | "other";
export type ArrivalsTab = "all" | Bucket;

export function bucketOf(status: string): Bucket {
  // NB-355: a tenant's configured visit flow can add billing_pending (pre-consultation limbo,
  // same rationale as checked_in/vitals_pending) and procedures_pending (post-consultation,
  // still with clinical/nursing staff, same rationale as in_consult), folded into the existing
  // buckets rather than growing the tab list.
  if (["checked_in", "waiting", "billing_pending", "vitals_pending", "vitals_done"].includes(status)) return "waiting";
  if (["in_consult", "procedures_pending"].includes(status)) return "in_consult";
  if (status === "checkout_pending") return "checkout_pending";
  if (status === "completed") return "completed";
  return "other";
}

export const TABS: { key: ArrivalsTab; label: string }[] = [
  { key: "all", label: "All" },
  { key: "waiting", label: "Waiting" },
  { key: "in_consult", label: "In consultation" },
  { key: "checkout_pending", label: "Checkout pending" },
  { key: "completed", label: "Completed" },
];
export const isArrivalsTab = (t: string | null): t is ArrivalsTab => TABS.some((x) => x.key === t);

// REC-011: the table's Visit column (QueueRepository's visit_type), same labels as /schedule.
export const VISIT_TYPE_LABEL: Record<string, string> = {
  appointment: "Appointment", follow_up: "Follow-up", walk_in: "Walk-in", transfer: "Transfer",
};

// PatientMatchCandidateResponse.matchScore is a 0-1 fraction; show it as a percentage.
export const matchPercent = (score: number): number => Math.round(Math.min(1, Math.max(0, score)) * 100);

export const waitMinutes = (createdAt: string, now: number): number => Math.max(0, Math.round((now - new Date(createdAt).getTime()) / 60000));

export function filterByTab<T extends { status: string }>(rows: T[], tab: ArrivalsTab): T[] {
  return tab === "all" ? rows : rows.filter((r) => bucketOf(r.status) === tab);
}

export function countsOf(rows: { status: string }[]): Record<string, number> {
  const counts: Record<string, number> = { all: rows.length };
  for (const b of ["waiting", "in_consult", "checkout_pending", "completed"] as Bucket[]) counts[b] = rows.filter((r) => bucketOf(r.status) === b).length;
  return counts;
}

/** REC-023: every active doctor stays on the rail; the busiest first, idle ones sink (stable for equal counts). */
export function doctorQueuesOf<S extends { id: string }>(
  staff: S[], rows: { doctorId?: string; status: string }[], estimates: Record<string, number | undefined>, delays: Record<string, { delayMinutes?: number; reason?: string } | null>,
) {
  return staff
    .map((s) => ({ ...s, waiting: rows.filter((r) => r.doctorId === s.id && bucketOf(r.status) === "waiting").length, estimatedMinutes: estimates[s.id], delay: delays[s.id] ?? null }))
    .sort((a, b) => b.waiting - a.waiting);
}
