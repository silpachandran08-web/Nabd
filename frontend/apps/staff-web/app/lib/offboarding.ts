// ADM-016: the owner's "Leaving Nabd?" card. Contract: GET /v1/setup/offboarding -> { request, recentExportId };
// POST /v1/setup/offboarding (X-Step-Up-Token, target "offboarding") { exportId? | waiveExport, reason } -> 202 { requestId, status,
// coolingOffEndsAt }; POST /v1/setup/offboarding/{id}/cancel (X-Step-Up-Token, target "offboarding-cancel:{id}") -> { status: "cancelled" }.
// After the cooling-off the clinic is erased and everyone is signed out; the signed certificate link goes to the owner's email.

export const REQUEST_TARGET = "offboarding";
/** A cancel confirmation is bound to the one request it cancels. */
export const cancelTarget = (requestId: string) => `offboarding-cancel:${requestId}`;

export type OffboardingRequest = { requestId: string; status: string; requestedAt: string; coolingOffEndsAt: string; certificateDueBy: string; waived: boolean; exportId: string | null };
export type OffboardingStatus = { request: OffboardingRequest | null; recentExportId: string | null };

/** A recent full export opens the gate; without one the owner must tick the waiver. */
export const requestBody = (reason: string, recentExportId: string | null, waive: boolean) =>
  recentExportId ? { exportId: recentExportId, reason: reason.trim() } : { waiveExport: waive, reason: reason.trim() };

export const canSubmit = (reason: string, code: string, recentExportId: string | null, waive: boolean) =>
  reason.trim().length > 0 && code.length === 6 && (recentExportId !== null || waive);

/** A request that still holds the clinic: cancellable while cooling off, final once erasing. */
export const isOpen = (r: OffboardingRequest | null | undefined) => r?.status === "cooling_off" || r?.status === "erasing";

/** Time left before the erasure starts, for the countdown. */
export function coolingOffLeft(endsAt: string, now: number): string {
  const ms = new Date(endsAt).getTime() - now;
  if (!(ms > 0)) return "ended";
  const hours = Math.floor(ms / 3_600_000);
  const days = Math.floor(hours / 24);
  const plural = (n: number, unit: string) => `${n} ${unit}${n === 1 ? "" : "s"}`;
  if (days > 0) return hours % 24 ? `${plural(days, "day")} ${plural(hours % 24, "hour")}` : plural(days, "day");
  return hours > 0 ? plural(hours, "hour") : "less than an hour";
}

const problem = (e: unknown) => (e && typeof e === "object" && "status" in e ? (e as { status: number; code?: string }) : null);

export function requestError(e: unknown): string {
  const p = problem(e);
  if (!p) return "Couldn't reach the server. Nothing was requested.";
  if (p.code === "EXPORT_REQUIRED") return "Download a full export made in the last 14 days first, or tick the box to go ahead without one.";
  if (p.code === "STEP_UP_REQUIRED" || p.status === 401) return "The authenticator code was not accepted. Enter a fresh code.";
  if (p.status === 403) return "Only the clinic owner can request deletion.";
  return "Couldn't request the deletion. Try again.";
}

export function cancelError(e: unknown): string {
  const p = problem(e);
  if (!p) return "Couldn't reach the server. The deletion was not cancelled.";
  if (p.code === "PAST_COOLING_OFF") return "The 7-day cooling-off period has ended. The deletion can no longer be cancelled.";
  if (p.code === "STEP_UP_REQUIRED" || p.status === 401) return "The authenticator code was not accepted. Enter a fresh code.";
  if (p.status === 403) return "Only the clinic owner can cancel the deletion.";
  return "Couldn't cancel the deletion. Try again.";
}
