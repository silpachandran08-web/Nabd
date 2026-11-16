// PLT-013: pure helpers for the owner's break-glass review (unit-tested in tests/unit/s23-plt013-break-glass-review.test.mjs).
export type Grant = {
  grantId: string; staff: { id: string; name: string }; reasonCode: string; reasonText: string | null; deviceLabel: string | null;
  startedAt: string; endedAt: string; readsCount: number; outcome: "justified" | "not_justified" | null; reviewedAt: string | null;
};

const DAY_MS = 24 * 60 * 60 * 1000;

/** Unreviewed grants that ended 24 h ago or more, or that the server already sent a reminder for. */
export function overdueGrants(items: Grant[], reminded: Set<string>, now: number): Grant[] {
  return items.filter((g) => !g.outcome && (reminded.has(g.grantId) || now - Date.parse(g.endedAt) >= DAY_MS));
}

/** i18n key (common.breakGlassReview.*) for a failed review save, by HTTP status (0 = no answer). */
export function reviewProblem(status: number): "alreadyReviewed" | "noteRequired" | "saveError" {
  return status === 409 ? "alreadyReviewed" : status === 400 ? "noteRequired" : "saveError";
}
