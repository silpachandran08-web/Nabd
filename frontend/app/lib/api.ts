// FEF-007: the clinic app's configured API client. Refresh stays in apiClient.ts (FEF-002); this wires it in.
import { createClient, ApiError, TimeoutError } from "../../packages/api-client/index.ts";
import type { Problem } from "../../packages/api-client/types.ts";
import { goLogin, needsRefresh, refreshSession } from "./apiClient.ts";

export { ApiError, TimeoutError };
export { intentKeys } from "../../packages/api-client/index.ts";

export const api = createClient({
  baseUrl: process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1",
  getToken: () => localStorage.getItem("nabd_access_token"),
  onAuthFailure: goLogin,
  refresh: refreshSession,
  shouldRefresh: needsRefresh,
});

/** User-facing text for a failed call. A timeout means the server may still have acted: say so. */
export function errorText(e: unknown, fallback: string): string {
  if (e instanceof TimeoutError) return "The server is taking too long. Check status before trying again.";
  if (e instanceof ApiError) return e.status >= 500 ? "The server had a problem and couldn't finish this. Try again in a moment; if it keeps happening, tell your clinic admin." : e.detail || e.title || fallback;
  return "Couldn't reach the server. Check your connection and try again.";
}

// For the arrivals screen's remaining raw-Response calls (moves to api.* with the rest of that page).
/** Maps a failed response to something the front desk can act on (a bare 500 says nothing). */
export async function problemMessage(res: Response, fallback: string): Promise<string> {
  if (res.status >= 500) return "The server had a problem and couldn't finish this. Try again in a moment; if it keeps happening, tell your clinic admin.";
  const p: Problem | null = await res.json().catch(() => null);
  // S7-B02: a 403 carries the server's reason (e.g. vitals-correction-forbidden); generic only when it has none.
  if (res.status === 403) return p?.detail || "Your role isn't allowed to do this.";
  return p?.detail || p?.title || fallback;
}
