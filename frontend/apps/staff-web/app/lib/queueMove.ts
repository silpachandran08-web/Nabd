// ACC-002: queue moves are compare-and-set. Send the status the screen saw; a 409 STALE_STATUS names the real one.
export const moveBody = (status: string, expectedStatus: string): string => JSON.stringify({ status, expectedStatus });

const stage = (s: string): string => s.replace(/_/g, " ");

/** "Already moved to <stage>" for a stale-status problem, else null. Staff name is not in the API yet. */
export function staleMessage(p: { type?: string; currentStatus?: string } | null | undefined): string | null {
  if (!p || !p.type?.endsWith("stale-status") || !p.currentStatus) return null;
  return `Already moved to ${stage(p.currentStatus)}. Showing the latest.`;
}

/** Error text for a failed move response (stale -> friendly line; otherwise the Problem detail). */
export async function moveErrorMessage(res: Response, fallback: string): Promise<string> {
  const p = await res.json().catch(() => null);
  return staleMessage(p) ?? p?.detail ?? p?.title ?? fallback;
}
