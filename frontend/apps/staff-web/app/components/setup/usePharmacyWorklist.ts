"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { PharmacyWorklistEntry, PharmacyWorklistPage } from "@nabd/api-client/generated";
import { api, ApiError } from "../../lib/api";
import { useLiveRefresh } from "../../lib/live";
import { usePolling } from "../../lib/usePolling";
import { claimRefusal, MAX_OPEN_PAGES, OPEN_QUERY, READY_QUERY, WORKLIST_RESOURCE, type WorklistEntry } from "./worklistLogic";

export type Notice = { id: string; tone: "ok" | "warn"; text: string } | null;

/** PHL-012: the board's data. Live on the SSE topic pharmacy.worklist (a delta refetches, it carries ids only); polls every 10 s
 * while there is no stream (the bearer build, or a stream still connecting). A refetch keeps the last list on screen. */
export function usePharmacyWorklist() {
  const [entries, setEntries] = useState<WorklistEntry[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const seq = useRef(0);

  const load = useCallback(async () => {
    const my = ++seq.current;
    try {
      const open: PharmacyWorklistEntry[] = [];
      let cursor: string | undefined;
      for (let page = 0; page < MAX_OPEN_PAGES; page++) {
        const p = await api.get<PharmacyWorklistPage>(`/pharmacy/worklist?${OPEN_QUERY}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`);
        open.push(...(p.items ?? []));
        cursor = p.nextCursor;
        if (!cursor) break;
      }
      const ready = await api.get<PharmacyWorklistPage>(`/pharmacy/worklist?${READY_QUERY}`);
      if (my !== seq.current) return;
      setEntries([...open, ...(ready.items ?? [])] as WorklistEntry[]);
      setFailed(false);
    } catch (e) {
      if (my === seq.current && !(e instanceof ApiError && e.status === 401)) setFailed(true);
    }
  }, []);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);
  useLiveRefresh([WORKLIST_RESOURCE], load);
  usePolling(load, 10_000); // only while there is no live stream

  async function start(entry: WorklistEntry) {
    setBusy(entry.id);
    setNotice(null);
    try {
      await api.post(`/pharmacy/worklist/${entry.id}/claim`, {});
      setNotice({ id: entry.id, tone: "ok", text: "Started. It is yours." });
    } catch (e) {
      setNotice({ id: entry.id, tone: "warn", text: e instanceof ApiError ? claimRefusal(e) : "Couldn't reach the server. Nothing was changed; try again." });
    } finally {
      setBusy(null);
      void load();
    }
  }

  return { entries, failed, busy, notice, reload: load, start };
}
