"use client";

import { useCallback, useRef } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { AppointmentPage as GAppointmentPage, AppointmentResponse, PageMeta, DoctorDelayResponse, DueConditionResponse, WaitEstimateResponse } from "@nabd/api-client/generated";
import type { PatientSummary as PatientDetail, QueueEntry } from "@nabd/domain";
import { can } from "../../lib/permissions";
import { api, ApiError } from "../../lib/api";
import { doctorQueueQuery, key } from "../../lib/query";
import { loadPatients, selfStaffId, type Need } from "../../lib/doctorQueries";
import { clinicTimeZoneReady, clinicToday } from "../../lib/clinicTime";
import { useLiveRefresh } from "../../lib/live";

// FEF-016: My Day on the shared query client. One query holds the composite day; the five reads run in parallel and
// the patient names/flags come from one batched call (<= 6 requests whatever the queue length).
export type Appointment = Need<AppointmentResponse, "id" | "patientId" | "doctorId" | "startTime" | "endTime" | "status" | "isFollowUp">;
export type AppointmentPage = Omit<GAppointmentPage, "data" | "page"> & { data: Appointment[]; page: Need<Omit<PageMeta, "nextCursor">, "limit"> & { nextCursor: string | null } };
export type DoctorDelay = Need<Omit<DoctorDelayResponse, "reason" | "clearedAt">, "id" | "doctorId" | "delayMinutes" | "announcedAt" | "active"> & { reason: string | null; clearedAt: string | null };
export type WaitEstimate = Need<WaitEstimateResponse, "estimatedMinutes" | "patientsAhead" | "avgVisitMinutes" | "basedOnHistory">;
export type DueCondition = Need<DueConditionResponse, "id" | "patientId" | "patientName" | "condition" | "reviewDueDate">;

/** A secondary read: loaded, or honestly unavailable (error / 403) so the widget says so instead of a number. */
export type Part<T> = { state: "ok"; data: T } | { state: "unavailable" };

export type DayRow = QueueEntry & { patientName: string | null; patient: PatientDetail | null };
export type MyDayData = {
  selfId: string;
  rows: DayRow[];
  appointments: Part<AppointmentPage>;
  delay: Part<DoctorDelay | null>;
  wait: Part<WaitEstimate>;
  due: Part<DueCondition[]> | null; // null = no clinical:view: the widget is absent
  patientNames: Record<string, string>;
};

const NO_SERVER = "Couldn't reach the server. Check your connection and try again.";

export function useMyDay() {
  const qc = useQueryClient();
  const selfId = selfStaffId();
  const forceFresh = useRef(false); // set by Refresh: the queue is read again even if the sidebar badge just read it
  const q = useQuery({
    queryKey: key("my-day", { doctorId: selfId }),
    enabled: !!selfId,
    staleTime: 0,
    queryFn: async ({ signal }): Promise<MyDayData> => {
      const part = async <T,>(path: string, pick: (j: unknown) => T): Promise<Part<T>> => {
        try { return { state: "ok", data: pick(await api.get<unknown>(path, { signal })) }; }
        catch (e) { if (signal.aborted) throw e; return { state: "unavailable" }; }
      };
      const queueStale = forceFresh.current ? 0 : 2000;
      forceFresh.current = false;
      const today = clinicToday(await clinicTimeZoneReady()); // the clinic's date, not the device's (FEF-011)
      const [entries, appointments, delay, wait, due] = await Promise.all([
        qc.fetchQuery({ ...doctorQueueQuery(selfId), staleTime: queueStale }) as Promise<QueueEntry[]>, // joins the sidebar badge's request
        part(`/appointments?doctorId=${selfId}&date=${today}&limit=100`, (j) => j as AppointmentPage),
        part(`/doctors/${selfId}/delay`, (j) => ((j as DoctorDelay[]).find((d) => d.active) ?? null)),
        part(`/queue/wait-estimate?doctorId=${selfId}`, (j) => j as WaitEstimate),
        can("clinical:view") ? part(`/clinical/conditions/due`, (j) => j as DueCondition[]) : Promise.resolve(null),
      ]);
      // Names/flags: one batched read for every distinct patient across the queue and today's appointments.
      // Without patients:view (or on a failed read) the row shows its token instead of a name.
      const ids = new Set<string>(entries.map((e) => e.patientId));
      if (appointments.state === "ok") appointments.data.data.forEach((a) => ids.add(a.patientId));
      const details = can("patients:view") ? await loadPatients(qc, ids, signal) : new Map<string, PatientDetail>();
      const patientNames: Record<string, string> = {};
      details.forEach((p, id) => { patientNames[id] = p.name; });
      return {
        selfId,
        rows: entries.map((e) => ({ ...e, patient: details.get(e.patientId) ?? null, patientName: details.get(e.patientId)?.name ?? null })),
        appointments, delay, wait, due, patientNames,
      };
    },
  });

  // The newest Refresh wins (FEF-004): whatever is still in flight, including the queue read, is cancelled first.
  const reload = useCallback(async () => {
    forceFresh.current = true;
    await Promise.all([qc.cancelQueries({ queryKey: key("my-day", { doctorId: selfId }) }), qc.cancelQueries({ queryKey: doctorQueueQuery(selfId).queryKey })]);
    await q.refetch();
  }, [qc, q, selfId]);
  useLiveRefresh(["queue", "roster", "doctor-delay", "wait-estimate"], reload); // FEF-020; the Refresh button stays as a manual fallback
  const error = q.error ? (q.error instanceof ApiError ? "Couldn't load today's queue. Try again." : NO_SERVER) : null;
  return { data: q.data ?? null, loading: q.isPending, error, reload };
}
