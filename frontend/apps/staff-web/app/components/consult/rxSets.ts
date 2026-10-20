"use client";

import { useCallback, useEffect, useState } from "react";
import type { AuthedFetch, PrescriptionItem, Problem } from "./shared";

// DOC-035. Mirrors GET/POST/PATCH/DELETE /v1/clinical/rx-sets (clinical/dto/FavouriteRxSet*.java).
export type RxSetScope = "mine" | "shared" | "standard";
export type RxSetItem = {
  drugName: string; dosage: string | null; frequency: string | null; duration: string | null; instructions: string | null;
  brandName: string | null; sigPattern: string | null; route: string | null; quantity: number | null; quantityUnit: string | null;
  /** true = not in the active pharmacy item master; null = no master to compare with (unknown, never "fine") */
  stale: boolean | null;
};
export type RxSet = {
  id: string; doctorId: string | null; ownerName: string | null; name: string; scope: RxSetScope; shared: boolean;
  createdAt: string; updatedAt: string; lastUsedAt: string | null;
  staleCheckAvailable: boolean; staleItemCount: number; canEdit: boolean; items: RxSetItem[];
};
export type RxSetWriteItem = Pick<PrescriptionItem, "drugName" | "dosage" | "frequency" | "duration" | "instructions" | "brandName" | "sigPattern" | "route" | "quantity" | "quantityUnit">;
export type RxSetResult = { ok: true } | { ok: false; message: string };

export const SCOPE_LABEL: Record<RxSetScope, string> = { mine: "Mine", shared: "Shared with clinic", standard: "Clinic standard" };

const blank = (s: string | null | undefined) => (s && s.trim() ? s.trim() : null);

/** What a set may carry: the structured pad fields only. Never warnings, overrides or decisions: a set is a template, and
 * applying it re-runs the safety checks on the server like any hand-typed line. */
type LooseItem = {
  drugName: string; dosage?: string | null; frequency?: string | null; duration?: string | null; instructions?: string | null;
  brandName?: string | null; sigPattern?: string | null; route?: string | null; quantity?: number | null; quantityUnit?: string | null;
};
export function toWriteItem(i: LooseItem): RxSetWriteItem {
  return {
    drugName: i.drugName.trim(), dosage: blank(i.dosage) ?? "", frequency: blank(i.frequency) ?? "", duration: blank(i.duration) ?? "",
    instructions: blank(i.instructions) ?? "", brandName: blank(i.brandName), sigPattern: blank(i.sigPattern), route: blank(i.route),
    quantity: i.quantity ?? null, quantityUnit: blank(i.quantityUnit),
  };
}

export function medCountLabel(n: number): string {
  return `${n} ${n === 1 ? "med" : "meds"}`;
}

export function lastUsedLabel(iso: string | null): string {
  if (!iso) return "Never used";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "Never used" : `Last used ${d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" })}`;
}

export function itemLine(i: RxSetItem): string {
  return i.brandName ? `${i.drugName} (${i.brandName})` : i.drugName;
}

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
