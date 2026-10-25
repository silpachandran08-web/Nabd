// FEF-007: the clinic app's configured API client. Refresh stays in apiClient.ts (FEF-002); this wires it in.
import { createClient, ApiError, TimeoutError } from "@nabd/api-client";
import type { Problem } from "@nabd/api-client/types";
import { goLogin, needsRefresh, refreshSession } from "./apiClient.ts";

export { ApiError, TimeoutError };
export { intentKeys } from "@nabd/api-client";

// FEF-018: the staff page registers its step-up dialog here; any call answering "step-up-required" then waits for it.
let stepUpHandler: ((problem: Record<string, unknown>) => Promise<string | null>) | null = null;
export const setStepUpHandler = (h: typeof stepUpHandler) => { stepUpHandler = h; };

export const api = createClient({
  baseUrl: process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1",
  getToken: () => localStorage.getItem("nabd_access_token"),
  onAuthFailure: goLogin,
  refresh: refreshSession,
  shouldRefresh: needsRefresh,
  stepUp: (problem) => (stepUpHandler ? stepUpHandler(problem) : Promise.resolve(null)),
});

/** Calls made before sign-in (owner portal, invite, MFA enrolment): no token, no refresh, a 401 is just the answer. */
export const publicApi = createClient({
  baseUrl: process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1",
  getToken: () => null,
  onAuthFailure: () => {},
  timeoutMs: 20_000, // LIVE-01: a cold-starting host gets 20 s before the sign-in screens give up
});

// ACC-003: a second check-in of the same appointment names the token already issued.
function alreadyCheckedIn(p: { code?: unknown; tokenNumber?: unknown; checkedInAt?: unknown } | null | undefined): string | null {
  if (p?.code !== "ALREADY_CHECKED_IN" || p.tokenNumber == null) return null;
  const at = typeof p.checkedInAt === "string" ? new Date(p.checkedInAt).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" }) : "";
  return `Already checked in as T-${p.tokenNumber}${at ? ` at ${at}` : ""}.`;
}

/** What a form shows for a failed save: the server's own reason when it gave one (any status), else errorText. */
export function reasonText(e: unknown, fallback: string): string {
  return e instanceof ApiError && e.detail ? e.detail : errorText(e, fallback);
}

/** User-facing text for a failed call. A timeout means the server may still have acted: say so. */
export function errorText(e: unknown, fallback: string): string {
  if (e instanceof TimeoutError) return "The server is taking too long. Check status before trying again.";
  if (e instanceof ApiError) return alreadyCheckedIn(e.problem) ?? (e.status >= 500 ? "The server had a problem and couldn't finish this. Try again in a moment; if it keeps happening, tell your clinic admin." : e.detail || e.title || fallback);
  return "Couldn't reach the server. Check your connection and try again.";
}

// For the arrivals screen's remaining raw-Response calls (moves to api.* with the rest of that page).
/** Maps a failed response to something the front desk can act on (a bare 500 says nothing). */
export async function problemMessage(res: Response, fallback: string): Promise<string> {
  if (res.status >= 500) return "The server had a problem and couldn't finish this. Try again in a moment; if it keeps happening, tell your clinic admin.";
  const p: (Problem & { code?: string; tokenNumber?: number; checkedInAt?: string }) | null = await res.json().catch(() => null);
  const dup = alreadyCheckedIn(p);
  if (dup) return dup;
  // S7-B02: a 403 carries the server's reason (e.g. vitals-correction-forbidden); generic only when it has none.
  if (res.status === 403) return p?.detail || "Your role isn't allowed to do this.";
  return p?.detail || p?.title || fallback;
}
