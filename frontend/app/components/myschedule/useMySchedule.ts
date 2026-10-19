"use client";

import { decodeJwtPayload } from "../../lib/jwt";
import { useCallback, useEffect, useState } from "react";
import { apiFetch, goLogin } from "../../lib/apiClient";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

export const WEEKDAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
export const hhmm = (t: string) => t.slice(0, 5);

// DTOs mirror the backend records exactly (queue/dto/WorkingHoursResponse, DoctorLeaveResponse,
// ScheduleDayResponse). dayOfWeek is 0 = Sunday .. 6 = Saturday.
export type WorkingHours = { id: string; dayOfWeek: number; startTime: string; endTime: string; slotMinutes: number; maxPatients: number | null };
export type Leave = { id: string; dateFrom: string; dateTo: string; reason: string | null; affectedPackagePatients: string[] };
export type WorkingHoursInput = { dayOfWeek: number; startTime: string; endTime: string; slotMinutes: number; maxPatients: number | null };
export type LeaveInput = { dateFrom: string; dateTo: string; reason: string | null };
export type DayCell = { doctorId: string; kind: string; slotStart: string | null; bookings: { appointmentId: string | null; patientName: string | null; visitType: string; time: string }[] };
export type DayGrid = {
  date: string;
  holiday: string | null;
  timezone: string;
  doctors: { id: string; name: string; onLeave: boolean; leaveReason: string | null; worksToday: boolean }[];
  sessions: { label: string; start: string; end: string; rows: { time: string; cells: DayCell[] }[] }[];
};

export function selfIdFromToken(token: string): string {
  return String(decodeJwtPayload<{ sub?: string }>(token)?.sub ?? "");
}

export function isoDate(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function auth(): { token: string; selfId: string } | null {
  const token = localStorage.getItem("nabd_access_token");
  const selfId = token ? selfIdFromToken(token) : "";
  if (!token || !selfId) {
    goLogin();
    return null;
  }
  return { token, selfId };
}

async function call(path: string, token: string, init?: RequestInit): Promise<Response | null> {
  try {
    const res = await apiFetch(path, { ...init, headers: { Authorization: `Bearer ${token}`, ...(init?.body ? { "Content-Type": "application/json" } : {}) } });
    if (res.status === 401) {
      localStorage.removeItem("nabd_access_token");
      goLogin();
      return null;
    }
    return res;
  } catch {
    return null;
  }
}

async function problem(res: Response | null, fallback: string): Promise<string> {
  if (!res) return "Couldn't reach the server. Check your connection and try again.";
  const p: { detail?: string } = await res.json().catch(() => ({}));
  return p.detail || fallback;
}

/** The signed-in doctor's own hours and leave. The doctor id is the JWT subject (the staff id), as My Day does. */
export function useMySchedule() {
  const [selfId, setSelfId] = useState("");
  const [hours, setHours] = useState<WorkingHours[]>([]);
  const [leave, setLeave] = useState<Leave[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    const a = auth();
    if (!a) return;
    setSelfId(a.selfId);
    const [h, l] = await Promise.all([
      call(`/doctors/${encodeURIComponent(a.selfId)}/working-hours`, a.token),
      call(`/doctors/${encodeURIComponent(a.selfId)}/leave`, a.token),
    ]);
    try {
      if (!h?.ok || !l?.ok) throw new Error("load");
      const hj: WorkingHours[] = await h.json();
      const lj: Leave[] = await l.json();
      setHours(Array.isArray(hj) ? hj : []);
      setLeave(Array.isArray(lj) ? lj : []);
    } catch {
      setError("Couldn't load your schedule.");
    }
    setLoading(false);
  }, []);

  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => { void load(); }, [load]);

  /** Returns an error message, or null on success (then reloads). */
  const addHours = useCallback(async (input: WorkingHoursInput): Promise<string | null> => {
    const a = auth();
    if (!a) return "Signed out.";
    const res = await call(`/doctors/${encodeURIComponent(a.selfId)}/working-hours`, a.token, { method: "POST", body: JSON.stringify(input) });
    if (!res?.ok) return problem(res, "Couldn't save the working hours.");
    await load();
    return null;
  }, [load]);

  // DOC-R7: PUT/DELETE /doctors/{id}/working-hours/{hoursId}. 400 hours-overlap / 403 detail is shown as-is.
  const updateHours = useCallback(async (hoursId: string, input: WorkingHoursInput): Promise<string | null> => {
    const a = auth();
    if (!a) return "Signed out.";
    const res = await call(`/doctors/${encodeURIComponent(a.selfId)}/working-hours/${encodeURIComponent(hoursId)}`, a.token, { method: "PUT", body: JSON.stringify(input) });
    if (!res?.ok) return problem(res, "Couldn't save the working hours.");
    await load();
    return null;
  }, [load]);

  const deleteHours = useCallback(async (hoursId: string): Promise<string | null> => {
    const a = auth();
    if (!a) return "Signed out.";
    const res = await call(`/doctors/${encodeURIComponent(a.selfId)}/working-hours/${encodeURIComponent(hoursId)}`, a.token, { method: "DELETE" });
    if (!res?.ok) return problem(res, "Couldn't remove the working hours.");
    await load();
    return null;
  }, [load]);

  const addLeave = useCallback(async (input: LeaveInput): Promise<{ error: string } | { affected: string[] }> => {
    const a = auth();
    if (!a) return { error: "Signed out." };
    const res = await call(`/doctors/${encodeURIComponent(a.selfId)}/leave`, a.token, { method: "POST", body: JSON.stringify(input) });
    if (!res?.ok) return { error: await problem(res, "Couldn't record the leave.") };
    const created: Leave = await res.json().catch(() => ({ id: "", dateFrom: "", dateTo: "", reason: null, affectedPackagePatients: [] }));
    await load();
    return { affected: created.affectedPackagePatients ?? [] };
  }, [load]);

  return { selfId, hours, leave, loading, error, reload: load, addHours, updateHours, deleteHours, addLeave };
}

/** One day of the clinic grid (GET /schedule/day); the page picks this doctor's column out of it. */
export function useDayGrid(date: string, enabled: boolean) {
  const [grid, setGrid] = useState<DayGrid | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    const a = auth();
    if (!a) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setError(null);
    void call(`/schedule/day?date=${encodeURIComponent(date)}`, a.token).then(async (res) => {
      if (cancelled) return;
      try {
        if (!res?.ok) throw new Error("load");
        const j: DayGrid = await res.json();
        if (!cancelled) setGrid(j);
      } catch {
        if (!cancelled) { setGrid(null); setError("Couldn't load this day."); }
      }
      if (!cancelled) setLoading(false);
    });
    return () => { cancelled = true; };
  }, [date, enabled, tick]);

  return { grid, loading, error, reload: () => setTick((t) => t + 1) };
}
