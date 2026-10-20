"use client";

// DOC-035 saved prescription sets: the data hook shared by the consult panel and the Templates screen (FEF-013 moved it out of
// components/consult so neither feature imports the other). The pure rules live in @nabd/domain (rxSets.ts).
import { useCallback, useEffect, useState } from "react";
import type { Problem } from "@nabd/api-client/types";
import type { RxSet, RxSetResult, RxSetScope, RxSetWriteItem } from "@nabd/domain";
import type { AuthedFetch } from "./apiClient";

async function failure(res: Response | null, fallback: string): Promise<RxSetResult> {
  if (!res) return { ok: false, message: fallback };
  const pr: Problem | null = await res.json().catch(() => null);
  return { ok: false, message: pr?.detail || fallback };
}

export function useRxSets(authedFetch: AuthedFetch) {
  const [sets, setSets] = useState<RxSet[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      const res = await authedFetch("/clinical/rx-sets");
      if (!res) return;
      if (!res.ok) { setError(res.status === 403 ? "Your role can't read saved sets." : "Couldn't load saved sets."); return; }
      setSets(await res.json());
      setError(null);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    }
  }, [authedFetch]);

  useEffect(() => {
    const t = setTimeout(() => void reload(), 0);
    return () => clearTimeout(t);
  }, [reload]);

  const create = useCallback(async (name: string, scope: RxSetScope, items: RxSetWriteItem[]): Promise<RxSetResult> => {
    try {
      const res = await authedFetch("/clinical/rx-sets", { method: "POST", body: JSON.stringify({ name, scope, items }) });
      if (!res?.ok) return await failure(res, "Couldn't save the set.");
      await reload();
      return { ok: true };
    } catch { return { ok: false, message: "Couldn't reach the server." }; }
  }, [authedFetch, reload]);

  const update = useCallback(async (id: string, body: { name?: string; scope?: RxSetScope; items?: RxSetWriteItem[] }): Promise<RxSetResult> => {
    try {
      const res = await authedFetch(`/clinical/rx-sets/${id}`, { method: "PATCH", body: JSON.stringify(body) });
      if (!res?.ok) return await failure(res, "Couldn't update the set.");
      await reload();
      return { ok: true };
    } catch { return { ok: false, message: "Couldn't reach the server." }; }
  }, [authedFetch, reload]);

  const remove = useCallback(async (id: string): Promise<RxSetResult> => {
    try {
      const res = await authedFetch(`/clinical/rx-sets/${id}`, { method: "DELETE" });
      if (!res?.ok) return await failure(res, "Couldn't delete the set.");
      await reload();
      return { ok: true };
    } catch { return { ok: false, message: "Couldn't reach the server." }; }
  }, [authedFetch, reload]);

  return { sets, error, reload, create, update, remove };
}
