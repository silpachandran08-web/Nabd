"use client";

// FEF-030: the prescription pad's draft (lines, status, who may sign) and its save call, moved out of consult/page.tsx.
import { useState } from "react";
import type { AuthedFetch, Prescription, PrescriptionItem, Problem } from "../shared";
import type { SignAuthority } from "../signAuthority";
import { BLANK_RX_ITEM, padItems } from "./rxLogic";

export type RxOverrideInput = { index: number; kind: string; reasonCode: string; note: string };

export function useRxPad(authedFetch: AuthedFetch, entryId: string) {
  const [rxItems, setRxItems] = useState<PrescriptionItem[]>([]);
  const [rxStatus, setRxStatus] = useState<"draft" | "signed">("draft");
  const [signAuthority, setSignAuthority] = useState<SignAuthority | null | "error">(null); // CLN-005
  const [rxSaving, setRxSaving] = useState(false);
  const [rxError, setRxError] = useState<string | null>(null);
  const [rxDirty, setRxDirty] = useState(false); // edits since the last save: the warnings shown are then out of date

  // Editing a line drops its warnings from view (they were computed for the old text); the server
  // recomputes them on the next save, and signing is checked again on the server regardless.
  // DOC-033: the structured pad (RxPad) edits several fields at once; the name fields keep that rule.
  function patchRxItem(index: number, patch: Partial<PrescriptionItem>) {
    setRxDirty(true);
    setRxItems((prev) => prev.map((item, i) => (i === index ? { ...item, ...patch, ...("drugName" in patch || "brandName" in patch ? { warnings: undefined } : {}) } : item)));
  }
  function addRxItem() { setRxDirty(true); setRxItems((prev) => [...prev, { ...BLANK_RX_ITEM }]); }
  function removeRxItem(index: number) { setRxDirty(true); setRxItems((prev) => prev.filter((_, i) => i !== index)); }
  /** A prescription the server just returned replaces the pad (save, favourite set applied, signing refused). */
  function applySaved(rx: Prescription) { setRxItems(padItems(rx)); setRxDirty(false); }

  // Saves the pad and returns the saved prescription (with the server's current warnings), or null.
  // Only the fields the API accepts are sent; a recorded decision is carried by the server unless a new
  // one is passed in `overrides` (keyed by item index).
  async function saveRx(overrides?: RxOverrideInput): Promise<Prescription | null> {
    if (!navigator.onLine) {
      setRxError("You're offline. The prescription can't be saved, checked or signed until you reconnect; your changes stay on this screen.");
      return null;
    }
    setRxSaving(true);
    setRxError(null);
    try {
      const items = rxItems
        .map((i, index) => ({ i, index }))
        .filter(({ i }) => i.drugName.trim() !== "")
        .map(({ i, index }) => ({
          drugName: i.drugName, dosage: i.dosage, frequency: i.frequency, duration: i.duration, instructions: i.instructions,
          brandName: i.brandName?.trim() || undefined, sigPattern: i.sigPattern || undefined, route: i.route || undefined,
          quantity: i.quantity ?? undefined, quantityUnit: i.quantityUnit?.trim() || undefined, drugId: i.drugId || undefined,
          overrides: overrides && overrides.index === index ? [{ kind: overrides.kind, reasonCode: overrides.reasonCode, note: overrides.note || undefined }] : undefined,
        }));
      const res = await authedFetch(`/clinical/prescriptions/${entryId}`, { method: "PATCH", body: JSON.stringify({ items }) });
      if (!res?.ok) {
        const pr: Problem = await res?.json().catch(() => ({ title: "Error", detail: "Couldn't save prescription." }));
        setRxError(pr?.detail || "Couldn't save prescription.");
        return null;
      }
      const rx: Prescription = await res.json();
      applySaved(rx);
      return rx;
    } catch {
      setRxError("Couldn't reach the server. Check your connection and try again.");
      return null;
    } finally {
      setRxSaving(false);
    }
  }

  return {
    rxItems, rxStatus, signAuthority, rxSaving, rxError, rxDirty,
    patchRxItem, addRxItem, removeRxItem, applySaved, saveRx, setRxError, setRxStatus,
    /** For the visit loader. */
    load: { setRxItems, setRxStatus, setSignAuthority },
  };
}
export type RxPad = ReturnType<typeof useRxPad>;
