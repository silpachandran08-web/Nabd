"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { QueueEntry } from "@nabd/domain";
import { ApiError } from "../../lib/api";
import { loadPatients, selfStaffId } from "../../lib/doctorQueries";
import { useLiveRefresh } from "../../lib/live";
import { doctorQueueQuery, key } from "../../lib/query";

export type SoloRow = QueueEntry & { patientName: string | null };

/**
 * CLN-008: the signed-in doctor's queue for the phone list. Server state only (TanStack): SSE messages invalidate it
 * (FEF-020) and TanStack refetches it when the connection returns (refetchOnReconnect), so a short offline gap
 * resyncs without a manual reload. Names come from one batched patient read.
 */
export function useSoloQueue() {
  const qc = useQueryClient();
  const selfId = selfStaffId();
  const q = useQuery({ ...doctorQueueQuery(selfId), enabled: !!selfId });
  useLiveRefresh(["queue"], () => q.refetch());
  const entries = (q.data ?? []) as QueueEntry[];
  const ids = [...new Set(entries.map((e) => e.patientId))].sort();
  const names = useQuery({
    queryKey: key("solo-names", { ids: ids.join(",") }), enabled: ids.length > 0,
    queryFn: async ({ signal }) => { const m = await loadPatients(qc, ids, signal); return Object.fromEntries([...m].map(([id, p]) => [id, p.name])); },
  });
  const rows: SoloRow[] = entries.map((e) => ({ ...e, patientName: names.data?.[e.patientId] ?? null }));
  const error = q.error ? (q.error instanceof ApiError ? "Couldn't load today's queue. Try again." : "Couldn't reach the server. Check your connection and try again.") : null;
  return { rows, loading: q.isPending && !!selfId, error, refetch: () => q.refetch(), selfId };
}
