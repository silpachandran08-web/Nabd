"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { WAITING_STATUSES } from "@nabd/domain";
import { api, ApiError } from "../../lib/api";
import { staleMessage } from "../../lib/queueMove";
import { visitSummaryQuery } from "./useVisitSummary";
import type { SoloRow } from "./useSoloQueue";

/** The first waiting patient in queue order, or null. */
export const nextWaiting = (rows: readonly SoloRow[]): SoloRow | null => rows.find((r) => WAITING_STATUSES.includes(r.status)) ?? null;

/**
 * CLN-008: "Call next" = PATCH /queue/{id}/status to in_consult with expectedStatus (ACC-002 compare-and-set). Two devices of the
 * same doctor tapping at once: the first move wins, the second gets 409 stale-status and a "refresh" prompt, never a second patient.
 * The summary is requested in parallel with the move, so on a slow network the doctor waits for the slower of the two, not both.
 */
export function useCallNext(next: SoloRow | null, refresh: () => unknown) {
  const router = useRouter();
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [stale, setStale] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function callNext() {
    if (!next || busy) return;
    setBusy(true); setStale(null); setError(null);
    void qc.prefetchQuery(visitSummaryQuery(next.id));
    try {
      await api.patch(`/queue/${next.id}/status`, { status: "in_consult", expectedStatus: next.status });
      void refresh();
      router.push(`/solo/consult?entry=${encodeURIComponent(next.id)}`);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) setStale(staleMessage(e.problem) ?? "Someone else already moved this patient.");
      else setError(e instanceof ApiError && e.detail ? e.detail : "Couldn't call the next patient. Try again.");
    } finally {
      setBusy(false);
    }
  }
  return { callNext, busy, stale, error, clear: () => { setStale(null); setError(null); } };
}
