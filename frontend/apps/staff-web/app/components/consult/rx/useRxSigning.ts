"use client";

// FEF-030: signing a prescription and recording a warning decision (override), moved out of consult/page.tsx.
import { useState } from "react";
import type { AuthedFetch, Prescription, Problem, RxWarning } from "../shared";
import type { RxPad } from "./useRxPad";

export function useRxSigning(authedFetch: AuthedFetch, entryId: string, pad: RxPad, reloadCtx: () => void, setNotice: (n: string | null) => void) {
  const [showSignConfirm, setShowSignConfirm] = useState(false);
  const [signAck, setSignAck] = useState(false);
  const [signing, setSigning] = useState(false);
  const [signError, setSignError] = useState<string | null>(null);
  const [override, setOverride] = useState<{ index: number; warning: RxWarning } | null>(null);
  const [overrideError, setOverrideError] = useState<string | null>(null);

  // Step 1 of signing: save (so the server re-checks allergy/pregnancy/controlled rules), then ask
  // the doctor to confirm what is being signed. A save that the server rejects stays on the pad.
  async function reviewAndSign() {
    if (!pad.rxItems.some((i) => i.drugName.trim() !== "")) {
      pad.setRxError("Add at least one drug before signing.");
      return;
    }
    const saved = await pad.saveRx();
    if (!saved) return;
    if (saved.items.some((i) => (i.warnings ?? []).some((w) => w.requiresDecision && !w.resolved))) {
      return; // the warning cards and the "Resolve the warning first" button now show what is open
    }
    setSignAck(false);
    setSignError(null);
    setShowSignConfirm(true);
  }

  async function confirmSignRx() {
    setSigning(true);
    setSignError(null);
    try {
      const res = await authedFetch(`/clinical/prescriptions/${entryId}/sign`, { method: "POST" });
      if (!res) return;
      if (!res.ok) {
        const pr: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't sign the prescription." }));
        setSignError(pr.detail || "Couldn't sign the prescription.");
        // The server re-checks safety at signing (e.g. an allergy recorded since the last save): show its current view.
        const fresh = await authedFetch(`/clinical/prescriptions/${entryId}`);
        if (fresh?.ok) pad.applySaved(await fresh.json() as Prescription);
        return;
      }
      pad.setRxStatus("signed");
      setShowSignConfirm(false);
      setNotice("Prescription signed.");
      reloadCtx();
    } catch {
      setSignError("Couldn't reach the server. The prescription was not signed; try again.");
    } finally {
      setSigning(false);
    }
  }

  async function confirmOverride(reasonCode: string, note: string) {
    if (!override) return;
    setOverrideError(null);
    const saved = await pad.saveRx({ index: override.index, kind: override.warning.kind, reasonCode, note });
    if (!saved) { setOverrideError("The decision was not recorded. Nothing was changed; try again."); return; }
    setOverride(null);
    setNotice("Decision recorded and audited.");
  }

  function askOverride(index: number, warning: RxWarning) { setOverrideError(null); setOverride({ index, warning }); }

  return {
    showSignConfirm, signAck, signing, signError, override, overrideError,
    setShowSignConfirm, setSignAck, setOverride, reviewAndSign, confirmSignRx, confirmOverride, askOverride,
  };
}
export type RxSigning = ReturnType<typeof useRxSigning>;
