// FEF-013: queue status and visit source vocabularies, one place for every app.
// QueueStatus mirrors the queue_entries status values the backend uses (QueueService / DepartmentService);
// VisitSource mirrors queue_entries_source_check (CheckInRequest.source plus internal_transfer for department moves).
export const QUEUE_STATUSES = [
  "checked_in", "waiting", "billing_pending", "vitals_pending", "vitals_done", "in_consult",
  "procedures_pending", "checkout_pending", "completed", "no_show", "transferred_out",
] as const;
export type QueueStatus = (typeof QUEUE_STATUSES)[number];

export const VISIT_SOURCES = ["walk_in", "referral", "online", "social_media", "returning", "other", "internal_transfer", "abdm_scan_share"] as const;
export type VisitSource = (typeof VISIT_SOURCES)[number];

// Indexed with the raw API string (an unknown source falls back to the code at the call site); `satisfies` keeps it complete.
export const VISIT_LABEL: Record<string, string> = {
  walk_in: "Walk-in", referral: "Referral", online: "Online", social_media: "Social media",
  returning: "Returning", other: "Other", internal_transfer: "Internal transfer", abdm_scan_share: "via ABHA",
} satisfies Record<VisitSource, string>;

// readonly string[] so a raw API status can be looked up; `satisfies` keeps the entries valid statuses.
export const WAITING_STATUSES: readonly string[] = ["checked_in", "waiting", "vitals_pending", "vitals_done"] satisfies QueueStatus[];
export const DONE_STATUSES: readonly string[] = ["checkout_pending", "completed", "no_show"] satisfies QueueStatus[];
