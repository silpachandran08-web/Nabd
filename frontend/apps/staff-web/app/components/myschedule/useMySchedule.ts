"use client";

import { useCallback } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { AffectedAppointment, Booking, Cell, Doctor, DoctorLeaveResponse, ScheduleDayResponse, Session, WorkingHoursResponse, WorkingHoursWriteRequest, DoctorLeaveWriteRequest } from "@nabd/api-client/generated";
import { api, errorText } from "../../lib/api";
import { key, prefix } from "../../lib/query";
import { selfStaffId, type Need } from "../../lib/doctorQueries";

export const WEEKDAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
export const hhmm = (t: string) => t.slice(0, 5);

// FEF-016: types are the generated ones with the always-present fields required. dayOfWeek is 0 = Sunday .. 6 = Saturday.
export type WorkingHours = Need<Omit<WorkingHoursResponse, "maxPatients">, "id" | "dayOfWeek" | "startTime" | "endTime" | "slotMinutes"> & { maxPatients: number | null };
export type Leave = Need<Omit<DoctorLeaveResponse, "reason">, "id" | "dateFrom" | "dateTo" | "affectedPackagePatients"> & { reason: string | null };
export type WorkingHoursInput = Need<Omit<WorkingHoursWriteRequest, "maxPatients">, "dayOfWeek" | "startTime" | "endTime" | "slotMinutes"> & { maxPatients: number | null };
/** ACC-006: a booking a leave block covers, with the nearest free times. patientId only links to the patient page (no names here). */
export type AffectedBooking = Need<Omit<AffectedAppointment, "offerStatus">, "appointmentId" | "patientId" | "start" | "channelConsent" | "alternatives"> & { offerStatus: string | null };
export type LeaveInput = Need<Omit<DoctorLeaveWriteRequest, "reason">, "dateFrom" | "dateTo"> & { reason: string | null };
export type DayCell = Need<Omit<Cell, "slotStart" | "bookings">, "doctorId" | "kind"> & {
  slotStart: string | null;
  bookings: (Need<Omit<Booking, "appointmentId" | "patientName">, "visitType" | "time"> & { appointmentId: string | null; patientName: string | null })[];
};
export type DayGrid = Need<Omit<ScheduleDayResponse, "holiday" | "doctors" | "sessions">, "date" | "timezone"> & {
  holiday: string | null;
  doctors: (Need<Omit<Doctor, "leaveReason">, "id" | "name" | "onLeave" | "worksToday"> & { leaveReason: string | null })[];
  sessions: (Need<Omit<Session, "rows">, "label" | "start" | "end"> & { rows: { time: string; cells: DayCell[] }[] })[];
};

export const selfIdFromToken = selfStaffId;

export function isoDate(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** The signed-in doctor's own hours and leave. The doctor id is the JWT subject (the staff id), as My Day does. */
export function useMySchedule() {
  const qc = useQueryClient();
  const selfId = selfStaffId();
  const base = `/doctors/${encodeURIComponent(selfId)}`;
  const hoursQ = useQuery({ queryKey: key("my-schedule", { part: "hours", selfId }), enabled: !!selfId, queryFn: ({ signal }) => api.get<WorkingHours[]>(`${base}/working-hours`, { signal }) });
  const leaveQ = useQuery({ queryKey: key("my-schedule", { part: "leave", selfId }), enabled: !!selfId, queryFn: ({ signal }) => api.get<Leave[]>(`${base}/leave`, { signal }) });
  const refresh = useCallback(() => qc.invalidateQueries({ queryKey: prefix("my-schedule") }), [qc]);

  /** Runs a write; returns an error message, or null on success (then reloads). */
  const run = useCallback(async <T,>(f: () => Promise<T>, fallback: string): Promise<{ error: string } | { ok: T }> => {
    try { const ok = await f(); await refresh(); return { ok }; }
    catch (e) { return { error: errorText(e, fallback) }; }
  }, [refresh]);
  const msg = async (f: () => Promise<unknown>, fallback: string) => { const r = await run(f, fallback); return "error" in r ? r.error : null; };

  const addHours = (input: WorkingHoursInput) => msg(() => api.post(`${base}/working-hours`, input), "Couldn't save the working hours.");
  // DOC-R7: PUT/DELETE /doctors/{id}/working-hours/{hoursId}. 400 hours-overlap / 403 detail is shown as-is.
  const updateHours = (hoursId: string, input: WorkingHoursInput) => msg(() => api.put(`${base}/working-hours/${encodeURIComponent(hoursId)}`, input), "Couldn't save the working hours.");
  const deleteHours = (hoursId: string) => msg(() => api.delete(`${base}/working-hours/${encodeURIComponent(hoursId)}`), "Couldn't remove the working hours.");
  const addLeave = async (input: LeaveInput): Promise<{ error: string } | { affected: string[]; leaveId: string | null; bookings: number }> => {
    const r = await run(() => api.post<Leave>(`${base}/leave`, input), "Couldn't record the leave.");
    return "error" in r ? r : { affected: r.ok?.affectedPackagePatients ?? [], leaveId: r.ok?.id ?? null, bookings: r.ok?.affectedAppointments?.length ?? 0 };
  };
  // ACC-006: bookings the leave covers (with alternatives), and the one-tap offer. No free text anywhere on this path.
  const affectedBookings = (leaveId: string) => api.get<{ items: AffectedBooking[] }>(`${base}/leave/${encodeURIComponent(leaveId)}/affected-appointments`).then((r) => r.items);
  const offerAlternatives = (appointmentId: string) => msg(() => api.post(`/appointments/${encodeURIComponent(appointmentId)}/offer-alternatives`, {}), "Couldn't send the offer.");

  const failed = hoursQ.isError || leaveQ.isError;
  return {
    selfId,
    hours: Array.isArray(hoursQ.data) ? hoursQ.data : [],
    leave: Array.isArray(leaveQ.data) ? leaveQ.data : [],
    loading: hoursQ.isPending || leaveQ.isPending,
    error: failed ? "Couldn't load your schedule." : null,
    reload: refresh, addHours, updateHours, deleteHours, addLeave, affectedBookings, offerAlternatives,
  };
}

/** One day of the clinic grid (GET /schedule/day); the page picks this doctor's column out of it. */
export function useDayGrid(date: string, enabled: boolean) {
  const q = useQuery({ queryKey: key("schedule-day", { date }), enabled, queryFn: ({ signal }) => api.get<DayGrid>(`/schedule/day?date=${encodeURIComponent(date)}`, { signal }) });
  return { grid: q.data ?? null, loading: enabled && q.isFetching && !q.data, error: q.isError ? "Couldn't load this day." : null, reload: () => void q.refetch() };
}
