"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { BookingRequestItem, BookingRequestStats } from "@nabd/api-client/generated";
import { api, ApiError, errorText } from "../../lib/api";
import { useLiveRefresh } from "../../lib/live";
import { usePolling } from "../../lib/usePolling";

export type RequestItem = BookingRequestItem & { appointmentId: string };
export type Notice = { tone: "ok" | "warn"; text: string } | null;

/** ACC-010: the open booking requests (GET /v1/booking-requests) with accept / suggest / decline. Optimistic: a decided row leaves the list at once and comes back if the server says no. */
export function useBookingRequests(limit = 50) {
  const [items, setItems] = useState<RequestItem[] | null>(null);
  const [stats, setStats] = useState<BookingRequestStats | null>(null);
  const [failed, setFailed] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);
  const seq = useRef(0);

  const load = useCallback(async () => {
    const my = ++seq.current;
    try {
      const page = await api.get<{ items?: RequestItem[]; stats?: BookingRequestStats }>(`/booking-requests?limit=${limit}`);
      if (my !== seq.current) return;
      setItems(page.items ?? []);
      setStats(page.stats ?? null);
      setFailed(false);
    } catch (e) {
      if (my === seq.current && !(e instanceof ApiError && e.status === 401)) setFailed(true);
    }
  }, [limit]);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);
  useLiveRefresh(["booking-requests"], load); // SSE `requests` topic: both screens show the final state at once
  usePolling(load, 15_000); // only while there is no live stream

  /** Runs one decision. onOk text on success; a 409 reloads the list and says who got there first. */
  async function decide(id: string, call: () => Promise<unknown>, okText: string, leaves: boolean): Promise<{ ok: boolean; alternative?: string }> {
    const before = items;
    if (leaves) setItems((cur) => (cur ?? []).filter((r) => r.appointmentId !== id));
    try {
      await call();
      setNotice({ tone: "ok", text: okText });
      void load();
      return { ok: true };
    } catch (e) {
      setItems(before); // roll back
      void load();
      if (e instanceof ApiError && e.status === 409) {
        const alternative = typeof e.problem.nearestAlternative === "string" ? e.problem.nearestAlternative : undefined;
        setNotice({ tone: "warn", text: e.code === "ALREADY_DECIDED" ? "Someone else already decided this request." : "That time is no longer free." });
        return { ok: false, alternative };
      }
      setNotice({ tone: "warn", text: errorText(e, "Couldn't save the decision. Nothing changed.") });
      return { ok: false };
    }
  }

  return {
    items, stats, failed, notice, setNotice, reload: load,
    accept: (id: string, slotStart?: string) => decide(id, () => api.post(`/booking-requests/${id}/accept`, slotStart ? { slotStart } : {}), "Accepted. The patient is being told.", true),
    suggest: (id: string, slots: string[]) => decide(id, () => api.post(`/booking-requests/${id}/suggest`, { slots }), "Other times sent to the patient.", false),
    decline: (id: string, reasonCode: string) => decide(id, () => api.post(`/booking-requests/${id}/decline`, { reasonCode }), "Declined. The patient gets a neutral message.", true),
  };
}
