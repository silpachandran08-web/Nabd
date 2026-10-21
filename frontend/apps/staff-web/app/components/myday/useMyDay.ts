"use client";

import { useCallback } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { AppointmentPage as GAppointmentPage, AppointmentResponse, PageMeta, DoctorDelayResponse, DueConditionResponse, WaitEstimateResponse } from "@nabd/api-client/generated";
import type { PatientSummary as PatientDetail, QueueEntry } from "@nabd/domain";
import { can } from "../../lib/permissions";
import { api, ApiError } from "../../lib/api";
import { key } from "../../lib/query";
import { loadPatients, selfStaffId, type Need } from "../../lib/doctorQueries";
import { clinicTimeZoneReady, clinicToday } from "../../lib/clinicTime";

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
  const q = useQuery({
    queryKey: key("my-day", { doctorId: selfId }),
    enabled: !!selfId,
    staleTime: 0,
    queryFn: async ({ signal }): Promise<MyDayData> => {
      const part = async <T,>(path: string, pick: (j: unknown) => T): Promise<Part<T>> => {
        try { return { state: "ok", data: pick(await api.get<unknown>(path, { signal })) }; }
        catch (e) { if (signal.aborted) throw e; return { state: "unavailable" }; }
      };
      const today = clinicToday(await clinicTimeZoneReady()); // the clinic's date, not the device's (FEF-011)
      const [entries, appointments, delay, wait, due] = await Promise.all([
        api.get<QueueEntry[]>(`/queue?doctorId=${selfId}`, { signal }),
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

  const reload = useCallback(async () => { await qc.cancelQueries({ queryKey: key("my-day", { doctorId: selfId }) }); await q.refetch(); }, [qc, q, selfId]);
  const error = q.error ? (q.error instanceof ApiError ? "Couldn't load today's queue. Try again." : NO_SERVER) : null;
  return { data: q.data ?? null, loading: q.isPending, error, reload };
}
