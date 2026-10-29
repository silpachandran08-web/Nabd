"use client";

// FEF-030: the doctor's queue for the consult screen (list, scope, filter, who may start which visit), moved out of consult/page.tsx.
import { useCallback, useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { STALE } from "@nabd/api-client/queries";
import { DEFAULT_START_STATUS, statusBeforeConsult, statusSequence } from "@nabd/domain";
import { api, ApiError } from "../../../lib/api";
import { can } from "../../../lib/permissions";
import { doctorQueueQuery, key, queueQuery } from "../../../lib/query";
import { loadPatients, prefetchVisit, selfStaffId } from "../../../lib/doctorQueries";
import type { PatientDetail, QueueEntry, Row } from "../shared";

export type StatusFilter = "all" | "waiting" | "in_consult" | "done";

export function useConsultQueue() {
  const qc = useQueryClient();
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [scope, setScope] = useState<"mine" | "all">("mine");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [selfId, setSelfId] = useState("");
  const [now, setNow] = useState(0);
  // departmentId -> the status a visit must be in before it can move to in_consult (that
  // department's configured pipeline; a department without vitals starts from "waiting").
  const [startStatus, setStartStatus] = useState<Record<string, string | null>>({});

  const load = useCallback(async (which: "mine" | "all" = "mine") => {
    setLoading(true);
    setError(null);
    setForbidden(false);
    const self = selfStaffId();
    if (!self) return; // signed out: the shell sends the user to sign-in
    setSelfId(self);
    // LIVE-09: no queue:view, no request; same "no access" state the 403 used to produce.
    if (!can("queue:view")) { setForbidden(true); setLoading(false); return; }
    try {
      const entries = await qc.fetchQuery(which === "mine" ? { ...doctorQueueQuery(self), staleTime: 2000 } : queueQuery()) as QueueEntry[];
      // One batched read for every distinct patient (the detail carries the allergy, chronic-condition and package
      // flags shown in the list); patients already in the cache (prefetched, or read by My Day) are not asked for again.
      const details = can("patients:view") ? await loadPatients(qc, entries.map((e) => e.patientId)) : new Map<string, PatientDetail>();
      setRows(entries.map((e): Row => {
        const patient = details.get(e.patientId) ?? null;
        return { ...e, patientName: patient?.name ?? "Unknown patient", patient };
      }));
      // One flow lookup per department, so "Start consultation" is offered exactly where the server
      // will accept the move. An unreadable flow falls back to the default pipeline (vitals first).
      const depts = [...new Set(entries.map((e) => e.departmentId))];
      const flows = await Promise.all(depts.map(async (d) => {
        try {
          const steps = await qc.fetchQuery({ queryKey: key("dept-flow", { departmentId: d }), staleTime: STALE.departments, queryFn: ({ signal }) => api.get<{ stepType: string }[]>(`/departments/${d}/flow`, { signal }) });
          return [d, statusBeforeConsult(statusSequence(steps.map((x) => x.stepType)))] as const;
        } catch {
          return [d, DEFAULT_START_STATUS] as const;
        }
      }));
      setStartStatus(Object.fromEntries(flows));
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) return;
      if (e instanceof ApiError && e.status === 403) setForbidden(true);
      else if (e instanceof ApiError) setError("Couldn't load today's queue. Try again.");
      else setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
      setNow(Date.now());
    }
  }, [qc]);

  // Wait times re-render once a minute.
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(id);
  }, []);

  function changeScope(next: "mine" | "all") {
    setScope(next);
    void load(next);
  }

  // Who may start which visit: the visit doctor, from the status just before in_consult.
  const canStartRow = (r: Row) => {
    const from = r.departmentId in startStatus ? startStatus[r.departmentId] : DEFAULT_START_STATUS;
    return r.doctorId === selfId && from !== null && r.status === from && can("queue:edit");
  };

  // FEF-016: point at (hover / focus / arrow key) a visit the doctor can start or resume and its context is read ahead.
  const warm = (r: Row) => { if (r.doctorId === selfId) prefetchVisit(qc, r.id, r.patientId, can("clinical:view")); };

  return { rows, setRows, loading, error, forbidden, scope, statusFilter, setStatusFilter, selfId, now, load, changeScope, canStartRow, warm };
}
export type ConsultQueue = ReturnType<typeof useConsultQueue>;
