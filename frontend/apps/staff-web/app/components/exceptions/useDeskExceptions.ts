"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError, errorText } from "../../lib/api";
import { useLiveRefresh } from "../../lib/live";
import { usePolling } from "../../lib/usePolling";
import type { DeskItem } from "./exceptionsLogic";

export type Notice = { tone: "ok" | "warn"; text: string } | null;

/** ACC-032: the open exceptions (GET /front-desk/exceptions), live on the `exceptions` topic, with resolve / snooze / confirm arrival. A closed row leaves at once and comes back if the server says no. */
export function useDeskExceptions(limit = 50) {
  const [items, setItems] = useState<DeskItem[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const seq = useRef(0);
  const router = useRouter();

  const load = useCallback(async () => {
    const my = ++seq.current;
    try {
      const page = await api.get<{ items?: DeskItem[] }>(`/front-desk/exceptions?limit=${limit}`);
      if (my !== seq.current) return;
      setItems(page.items ?? []);
      setFailed(false);
    } catch (e) {
      if (my === seq.current && !(e instanceof ApiError && e.status === 401)) setFailed(true);
    }
  }, [limit]);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);
  useLiveRefresh(["desk-exceptions"], load); // SSE `exceptions` topic: an item opens or closes on every screen at once
  usePolling(load, 15_000); // only while there is no live stream

  async function run(item: DeskItem, call: () => Promise<unknown>, okText: string): Promise<boolean> {
    const before = items;
    setBusy(item.id);
    setItems((cur) => (cur ?? []).filter((i) => i.id !== item.id));
    try {
      await call();
      setNotice({ tone: "ok", text: okText });
      void load();
      return true;
    } catch (e) {
      setItems(before);
      void load();
      setNotice({ tone: "warn", text: e instanceof ApiError && e.code === "ALREADY_RESOLVED" ? "Someone else already dealt with this." : errorText(e, "Couldn't save. Nothing changed.") });
      return false;
    } finally {
      setBusy(null);
    }
  }

  /** Call / Message: opens the patient's file (their phone and the message thread are there); the item stays until the booking is confirmed. */
  async function openPatient(item: DeskItem) {
    try {
      const appt = await api.get<{ patientId: string }>(`/appointments/${item.refId}`);
      router.push(`/patients?open=${encodeURIComponent(appt.patientId)}`);
    } catch (e) {
      setNotice({ tone: "warn", text: errorText(e, "Couldn't open the patient.") });
    }
  }

  return {
    items, failed, openPatient, notice, busy, setNotice, reload: load,
    resolve: (item: DeskItem) => run(item, () => api.post(`/front-desk/exceptions/${item.id}/resolve`, {}), "Done."),
    snooze: (item: DeskItem) => run(item, () => api.post(`/front-desk/exceptions/${item.id}/snooze`, { snoozeMinutes: 30 }), "Snoozed for 30 minutes."),
    /** One tap: the same desk check-in as the arrivals dialog (arrived status, token, date check). The item closes itself. */
    confirmArrival: (item: DeskItem) => run(item, async () => {
      const appt = await api.get<{ id: string; patientId: string; doctorId: string }>(`/appointments/${item.params.appointmentId ?? item.refId}`);
      await api.post("/queue/check-in", { appointmentId: appt.id, patientId: appt.patientId, doctorId: appt.doctorId });
    }, "Checked in."),
  };
}
