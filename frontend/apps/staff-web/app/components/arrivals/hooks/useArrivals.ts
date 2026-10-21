"use client";

// FEF-028: all of the Arrivals board's server state in one hook (queries from FEF-015; SSE joins here with FEF-020).
// GET /v1/queue, /v1/staff/roster (queue:view, so Reception can populate the doctor picker), /v1/patients/{id}
// (one cached query per patient, shared by the board, the detail rail and the check-in picker).
import { useCallback, useMemo, useState } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { STALE } from "@nabd/api-client/queries";
import type { CallbackEntryResponse, DoctorDelayResponse, PatientDetailResponse, WaitEstimateResponse } from "@nabd/api-client/generated";
import { can } from "../../../lib/permissions";
import { getIdentity } from "../../../lib/session";
import { usePolling } from "../../../lib/usePolling";
import { useHasMounted } from "../../../lib/useHasMounted";
import { getter, key, live, prefix, queueQuery, useReload } from "../../../lib/query";
import { staleMessage } from "../../../lib/queueMove";
import { api, ApiError } from "../../../lib/api";
import { failText } from "../failText";
import { doctorQueuesOf } from "../board/boardLogic";
import { NO_GRANTS, type Entry, type Grants, type Row, type Staff } from "../types";

const POLL_MS = 15_000;
const QUEUE_MOVE = ["queue-move"];

function grantsNow(): Grants {
  return {
    queueCreate: can("queue:create"), queueEdit: can("queue:edit"),
    // Checkout opens GET /billing/checkout (billing:view) to bill (POST, billing:create) or take payment (billing:edit).
    checkout: can("billing:view") && (can("billing:create") || can("billing:edit")),
    // REC-060: first vitals for a vitals_pending visit need clinical:create or clinical:edit.
    vitalsRecord: can("clinical:create") || can("clinical:edit"),
    patientsView: can("patients:view"), patientsCreate: can("patients:create"),
  };
}

export const patientDetailQuery = (id: string) => ({ queryKey: key("patient", { id }), queryFn: getter<PatientDetailResponse>(`/patients/${id}`), staleTime: STALE.patient });

export function useArrivals() {
  const qc = useQueryClient();
  // Permissions live in the browser (localStorage token): read only after hydration so the server render and the
  // first client render match. Controls stay absent until known, never disabled.
  const mounted = useHasMounted();
  const g = mounted ? grantsNow() : NO_GRANTS;
  const grants = useMemo(() => g, [g.queueCreate, g.queueEdit, g.checkout, g.vitalsRecord, g.patientsView, g.patientsCreate]); // eslint-disable-line react-hooks/exhaustive-deps -- stable identity while the six flags are unchanged
  const queueView = mounted && can("queue:view"); // LIVE-09: no requests that can only 403
  const region = mounted ? getIdentity()?.tenantRegion : undefined;
  const [actionError, setActionError] = useState<string | null>(null);

  // One GET /queue, shared with the sidebar badge (same key). Polled every 15 s while visible and reloaded
  // once on return to the tab; a reload cancels a slower one still in flight (FEF-004).
  const queueQ = useQuery({ ...queueQuery(), enabled: queueView });
  const rosterQ = useQuery({ queryKey: key("roster"), queryFn: getter<Staff[]>("/staff/roster"), staleTime: STALE.roster, enabled: queueView });
  const callbackQ = useQuery({ queryKey: key("callback-list"), queryFn: getter<CallbackEntryResponse[]>("/appointments/callback-list"), staleTime: STALE.callbacks, enabled: queueView });
  // Array guards: a malformed body shows as an empty board, never a crashed page.
  const entries = (Array.isArray(queueQ.data) ? queueQ.data : []) as Entry[];
  const staff = Array.isArray(rosterQ.data) ? rosterQ.data : [];
  const patientIds = [...new Set(entries.map((e) => e.patientId))];
  // Names come from the patient cache: a 15 s poll asks for none it already has; a focus revalidates them (AC4).
  const patientQs = useQueries({ queries: patientIds.map((id) => ({ ...patientDetailQuery(id), enabled: grants.patientsView })) });
  const patientIdx = new Map(patientIds.map((id, i) => [id, i]));
  const rows: Row[] = entries.map((e) => {
    const p = patientQs[patientIdx.get(e.patientId) ?? -1];
    return { ...e, patientName: p?.data?.name ?? (p?.isPending && grants.patientsView ? "…" : "Unknown patient"), patientMrn: p?.data?.mrn ?? "" };
  });
  // NB-101/NB-100: per-doctor wait estimate and any active delay, for the sidebar.
  const doctorIds = staff.map((s) => s.id);
  const estimateQs = useQueries({ queries: doctorIds.map((id) => ({
    queryKey: key("wait-estimate", { doctorId: id }), queryFn: getter<WaitEstimateResponse>(`/queue/wait-estimate?doctorId=${id}`), ...live,
  })) });
  const delayQs = useQueries({ queries: doctorIds.map((id) => ({
    queryKey: key("doctor-delay", { doctorId: id }), queryFn: getter<DoctorDelayResponse[]>(`/doctors/${id}/delay`), ...live,
  })) });
  const estimates: Record<string, number | undefined> = Object.fromEntries(doctorIds.map((id, i) => [id, estimateQs[i]?.data?.estimatedMinutes]));
  const delays: Record<string, { delayMinutes?: number; reason?: string } | null> = Object.fromEntries(doctorIds.map((id, i) => {
    const active = Array.isArray(delayQs[i]?.data) ? delayQs[i].data.find((h) => h.active) : undefined;
    return [id, active ? { delayMinutes: active.delayMinutes, reason: active.reason } : null];
  }));
  const doctorQueues = doctorQueuesOf(staff, rows, estimates, delays);
  const callbackList = Array.isArray(callbackQ.data) ? callbackQ.data : [];

  const qErr = queueQ.error;
  const forbidden = mounted && (!can("queue:view") || (qErr instanceof ApiError && qErr.status === 403));
  const loading = !forbidden && queueQ.isPending;
  const error = !qErr || forbidden ? null : qErr instanceof ApiError ? "Couldn't load today's arrivals. Try again." : "Couldn't reach the server. Check your connection and try again.";
  // Board, wait estimates and delays reload together (poll + return to the tab), newest wins (FEF-004).
  // shortcut: still 2 requests per doctor per poll; GET /queue/board (T-094) folds them into one.
  const reload = useReload("queue", "wait-estimate", "doctor-delay");
  usePolling(reload, POLL_MS); // NB-095: live-updates via polling; the SSE stream (ACC-021 / FEF-020) replaces it.
  // Every write changes the queue: re-read it (and the badge with it).
  const invalidateQueue = useCallback(() => qc.invalidateQueries({ queryKey: prefix("queue") }), [qc]);
  const refetchRoster = rosterQ.refetch;
  const retryAll = () => { void reload(); if (rosterQ.isError) void refetchRoster(); };

  // QueueService's state machine only allows one hop at a time (checked_in -> waiting -> vitals_pending); this one
  // button walks both hops (or just the second, if already "waiting"). The row shows "Vitals pending" at once; a
  // failed hop (409 stale-status, ACC-002) rolls it back and says why; the queue is always re-read after.
  const sendForVitals = useMutation({
    mutationKey: QUEUE_MOVE,
    mutationFn: async (row: Row) => {
      if (row.status === "checked_in") await api.patch(`/queue/${row.id}/status`, { status: "waiting", expectedStatus: "checked_in" });
      await api.patch(`/queue/${row.id}/status`, { status: "vitals_pending", expectedStatus: "waiting" });
    },
    onMutate: async (row) => {
      setActionError(null);
      const queueKey = queueQuery().queryKey;
      await qc.cancelQueries({ queryKey: queueKey });
      const before = qc.getQueryData(queueKey);
      qc.setQueryData(queueKey, (old) => old?.map((e) => (e.id === row.id ? { ...e, status: "vitals_pending" } : e)));
      return { before };
    },
    onError: (err, _row, ctx) => {
      qc.setQueryData(queueQuery().queryKey, ctx?.before);
      setActionError((err instanceof ApiError && staleMessage(err.problem)) || failText(err, "Couldn't send the patient for vitals."));
    },
    onSettled: () => invalidateQueue(),
  });
  const { mutate: sendRow } = sendForVitals;

  const clearDelay = useCallback(async (doctorId: string) => {
    try {
      await api.post(`/doctors/${doctorId}/delay/clear`);
      void qc.invalidateQueries({ queryKey: prefix("doctor-delay") });
      void qc.invalidateQueries({ queryKey: prefix("wait-estimate") });
    } catch (err) {
      setActionError(failText(err, "Couldn't clear the delay."));
    }
  }, [qc]);

  return {
    mounted, grants, region, forbidden, loading, error, rows, staff, doctorQueues, callbackList,
    rosterFailed: rosterQ.isError, refetchRoster, retryAll, invalidateQueue, reload,
    sendRow, sendingId: sendForVitals.isPending ? sendForVitals.variables?.id : undefined, clearDelay, actionError, setActionError,
  };
}
