"use client";

import { useCallback, useEffect, useState } from "react";
import { can } from "../../lib/permissions";
import type { PatientDetail, QueueEntry } from "../consult/shared";
import { apiFetch, goLogin } from "../../lib/apiClient";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

// DTOs mirror the backend records exactly (queue/dto/*, clinical/dto/DueConditionResponse).
export type Appointment = { id: string; patientId: string; doctorId: string; startTime: string; endTime: string; status: string; isFollowUp: boolean };
export type AppointmentPage = { data: Appointment[]; page: { nextCursor: string | null; limit: number } };
export type DoctorDelay = { id: string; doctorId: string; delayMinutes: number; reason: string | null; announcedAt: string; clearedAt: string | null; active: boolean };
export type WaitEstimate = { estimatedMinutes: number; patientsAhead: number; avgVisitMinutes: number; basedOnHistory: boolean };
export type DueCondition = { id: string; patientId: string; patientName: string; condition: string; reviewDueDate: string };

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

function selfIdFromToken(token: string): string {
  try {
    const b64 = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    return String(JSON.parse(atob(b64.padEnd(b64.length + ((4 - (b64.length % 4)) % 4), "="))).sub ?? "");
  } catch {
    return "";
  }
}

function localDate(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

export function useMyDay() {
  const [data, setData] = useState<MyDayData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    const token = localStorage.getItem("nabd_access_token");
    const selfId = token ? selfIdFromToken(token) : "";
    if (!token || !selfId) {
      goLogin();
      return;
    }
    const get = async (path: string): Promise<Response | null> => {
      try {
        const res = await apiFetch(path, { headers: { Authorization: `Bearer ${token}` } });
        if (res.status === 401) {
          localStorage.removeItem("nabd_access_token");
          goLogin();
          return null;
        }
        return res;
      } catch {
        return null;
      }
    };
    const part = async <T,>(path: string, pick: (j: unknown) => T): Promise<Part<T>> => {
      const res = await get(path);
      if (!res?.ok) return { state: "unavailable" };
      try {
        return { state: "ok", data: pick(await res.json()) };
      } catch {
        return { state: "unavailable" };
      }
    };
    try {
      const wantDue = can("clinical:view");
      const [queueRes, appointments, delay, wait, due] = await Promise.all([
        get(`/queue?doctorId=${selfId}`),
        part(`/appointments?doctorId=${selfId}&date=${localDate()}&limit=100`, (j) => j as AppointmentPage),
        part(`/doctors/${selfId}/delay`, (j) => ((j as DoctorDelay[]).find((d) => d.active) ?? null)),
        part(`/queue/wait-estimate?doctorId=${selfId}`, (j) => j as WaitEstimate),
        wantDue ? part(`/clinical/conditions/due`, (j) => j as DueCondition[]) : Promise.resolve(null),
      ]);
      if (!queueRes) { setError("Couldn't reach the server. Check your connection and try again."); return; }
      if (!queueRes.ok) { setError("Couldn't load today's queue. Try again."); return; }
      const entries: QueueEntry[] = await queueRes.json();

      // Names/flags: one patient read per distinct patient across the queue and today's appointments.
      // Without patients:view (or on a failed read) the row shows its token instead of a name.
      const patientIds = new Set<string>(entries.map((e) => e.patientId));
      if (appointments.state === "ok") appointments.data.data.forEach((a) => patientIds.add(a.patientId));
      const details = new Map<string, PatientDetail | null>();
      if (can("patients:view")) {
        await Promise.all([...patientIds].map(async (id) => {
          const r = await get(`/patients/${id}`);
          details.set(id, r?.ok ? ((await r.json()) as PatientDetail) : null);
        }));
      }
      const patientNames: Record<string, string> = {};
      details.forEach((p, id) => { if (p) patientNames[id] = p.name; });
      setData({
        selfId,
        rows: entries.map((e) => ({ ...e, patient: details.get(e.patientId) ?? null, patientName: details.get(e.patientId)?.name ?? null })),
        appointments, delay, wait, due, patientNames,
      });
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);
  return { data, loading, error, reload: load };
}
