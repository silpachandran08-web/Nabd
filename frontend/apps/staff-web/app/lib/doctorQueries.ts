// FEF-016: the doctor screens' server state on the shared query client (FEF-015): batched patient summaries,
// the signed-in doctor's id, and the visit context (note, prescription, signing authority) that is prefetched
// when the doctor points at the next patient.
import type { QueryClient } from "@tanstack/react-query";
import type { PatientSummary } from "@nabd/domain";
import { api, ApiError } from "./api";
import { decodeAccessToken } from "./session";
import { key } from "./query";

/** Required fields of a generated type (springdoc marks none required; the server always sends these). */
export type Need<T, K extends keyof T> = Omit<T, K> & Required<Pick<T, K>>;

/** The signed-in staff member's id (the JWT subject), or "" when signed out. */
export function selfStaffId(): string {
  const token = typeof window === "undefined" ? null : localStorage.getItem("nabd_access_token");
  return String((token && decodeAccessToken(token)?.sub) ?? "");
}

const BATCH_MAX = 100; // GET /patients/batch limit
const FRESH_MS = 30_000;
export const patientKey = (id: string) => key("patient", { id });

const isFresh = (qc: QueryClient, k: ReturnType<typeof key>, ms = FRESH_MS) => {
  const s = qc.getQueryState(k);
  return s?.status === "success" && Date.now() - s.dataUpdatedAt < ms;
};

/** Patient summaries for many ids: ids already cached are not asked for again, the rest go in one GET per 100
 * (/patients/batch) and are written into the per-patient cache that Arrivals shares. If the batch route fails or answers
 * nothing (an older backend) each patient is read on its own. Unknown or unreadable ids are absent from the map. */
export async function loadPatients(qc: QueryClient, ids: Iterable<string>, signal?: AbortSignal): Promise<Map<string, PatientSummary>> {
  const out = new Map<string, PatientSummary>();
  const need: string[] = [];
  for (const id of new Set(ids)) {
    if (isFresh(qc, patientKey(id))) out.set(id, qc.getQueryData<PatientSummary>(patientKey(id))!);
    else need.push(id);
  }
  for (let i = 0; i < need.length; i += BATCH_MAX) {
    const chunk = need.slice(i, i + BATCH_MAX);
    let got: PatientSummary[] | null = null;
    try {
      const r = await api.get<unknown>(`/patients/batch?ids=${chunk.join(",")}`, { signal });
      got = Array.isArray(r) && r.length > 0 ? (r as PatientSummary[]) : null; // empty for a non-empty ask: the route may be missing (older backend), so ask one by one
    } catch (e) {
      if (signal?.aborted) throw e;
      if (e instanceof ApiError && e.status === 403) continue; // no patients:view: names stay unknown
    }
    if (!got) {
      got = (await Promise.all(chunk.map((id) => api.get<PatientSummary>(`/patients/${id}`, { signal }).catch((e) => { if (signal?.aborted) throw e; return null; })))).filter((p): p is PatientSummary => !!p);
    }
    for (const p of got) { qc.setQueryData(patientKey(p.id), p); out.set(p.id, p); }
  }
  return out;
}

/** A status + body read that never throws on an HTTP error, cached while the data is fresh. Only a 200 is kept. */
export type Read<T> = { status: number; body: T | null; problem: Record<string, unknown> | null };
export const visitReadKey = (what: "note" | "rx" | "sign-authority", entryId: string) => key("visit-" + what, { entryId });
const PATH = { note: "/clinical/notes/", rx: "/clinical/prescriptions/", "sign-authority": "/clinical/prescriptions/" } as const;

export function visitRead<T>(qc: QueryClient, what: "note" | "rx" | "sign-authority", entryId: string, freshMs = 0): Promise<Read<T>> {
  const path = PATH[what] + entryId + (what === "sign-authority" ? "/signing-authority" : "");
  return qc.fetchQuery({
    queryKey: visitReadKey(what, entryId),
    staleTime: freshMs,
    queryFn: async ({ signal }): Promise<Read<T>> => {
      try { return { status: 200, body: await api.get<T>(path, { signal }), problem: null }; }
      catch (e) { if (e instanceof ApiError) return { status: e.status, body: null, problem: e.problem }; throw e; }
    },
  }).then((r) => { if (r.status !== 200) qc.removeQueries({ queryKey: visitReadKey(what, entryId), exact: true }); return r; });
}

/** Warm the cache for a visit the doctor is about to open (hover / focus / arrow key). Errors are silent: opening reads again. */
export function prefetchVisit(qc: QueryClient, entryId: string, patientId: string, clinical: boolean): void {
  void loadPatients(qc, [patientId]).catch(() => undefined);
  if (!clinical) return;
  for (const what of ["note", "rx", "sign-authority"] as const) void visitRead(qc, what, entryId, FRESH_MS).catch(() => undefined);
}

/** Any write on a visit makes the cached note / prescription / signing authority out of date. */
export const dropVisitReads = (qc: QueryClient) => qc.removeQueries({ predicate: (q) => String(q.queryKey[2]).startsWith("visit-") });

/** True when a prefetched copy of this visit's note is still fresh (open renders from it, no wait). */
export const visitIsWarm = (qc: QueryClient, entryId: string) => isFresh(qc, visitReadKey("note", entryId));
export { FRESH_MS };
