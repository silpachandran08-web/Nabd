"use client";

// FEF-030: the prescription pad's draft (lines, status, who may sign) and its save call, moved out of consult/page.tsx.
// FEF-035: the pad autosaves like the note: an encrypted local draft 800 ms after an edit, the server PATCH when typing
// pauses (app/lib/autosave.ts), a local draft kept while offline or after a failed save, and a conflict dialog instead of a
// silent overwrite when the server copy moved since this device last synced (the prescription carries no updatedAt, so the
// lines themselves are compared). shortcut: two devices that read the same server copy and save within the same second
// still race (no server-side version check); upgrade when the PATCH takes an If-Match.
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { createAutosave } from "../../../lib/autosave";
import { deleteDraft, writeDraft } from "../draftStore";
import { LOCAL_DRAFT_MS } from "../note/noteLogic";
import type { AuthedFetch, Prescription, PrescriptionItem, Problem } from "../shared";
import type { SignAuthority } from "../signAuthority";
import type { SaveState } from "../visit/types";
import { BLANK_RX_ITEM, padItems, rxFingerprint } from "./rxLogic";

export type RxOverrideInput = { index: number; kind: string; reasonCode: string; note: string };
export type RxConflict = { mine: PrescriptionItem[]; server: PrescriptionItem[] };
/** What the local draft holds: the lines and the fingerprint of the server copy they were based on. */
export type RxDraftData = { items: PrescriptionItem[]; base: string };

export function useRxPad(authedFetch: AuthedFetch, entryId: string) {
  const t = useTranslations("consult.useRxPad");
  const [rxItems, setRxItems] = useState<PrescriptionItem[]>([]);
  const [rxStatus, setRxStatus] = useState<"draft" | "signed">("draft");
  const [signAuthority, setSignAuthority] = useState<SignAuthority | null | "error">(null); // CLN-005
  const [rxSaving, setRxSaving] = useState(false);
  const [rxError, setRxError] = useState<string | null>(null);
  const [rxDirty, setRxDirty] = useState(false); // edits since the last save: the warnings shown are then out of date
  const [rxSaveState, setRxSaveState] = useState<SaveState>("idle");
  const [rxSavedAt, setRxSavedAt] = useState<Date | null>(null);
  const [rxConflict, setRxConflict] = useState<RxConflict | null>(null);

  const itemsRef = useRef(rxItems);
  const dirtyRef = useRef(rxDirty);
  const statusRef = useRef(rxStatus);
  const conflictRef = useRef(rxConflict);
  const baseRef = useRef<string>(rxFingerprint([])); // fingerprint of the server copy this device last synced with
  const localTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => { itemsRef.current = rxItems; dirtyRef.current = rxDirty; statusRef.current = rxStatus; conflictRef.current = rxConflict; }, [rxItems, rxDirty, rxStatus, rxConflict]);

  const localDraft = (items: PrescriptionItem[]) => writeDraft({ entryId, section: "rx", data: { items, base: baseRef.current } satisfies RxDraftData, baseUpdatedAt: null, savedAt: new Date().toISOString() });
  const autosaveRef = useRef<ReturnType<typeof createAutosave> | null>(null);
  function touched() {
    setRxDirty(true);
    autosaveRef.current?.touch();
    if (localTimerRef.current) clearTimeout(localTimerRef.current);
    localTimerRef.current = setTimeout(() => { if (dirtyRef.current) void localDraft(itemsRef.current); }, LOCAL_DRAFT_MS);
  }

  // Editing a line drops its warnings from view (they were computed for the old text); the server
  // recomputes them on the next save, and signing is checked again on the server regardless.
  // DOC-033: the structured pad (RxPad) edits several fields at once; the name fields keep that rule.
  function patchRxItem(index: number, patch: Partial<PrescriptionItem>) {
    touched();
    setRxItems((prev) => prev.map((item, i) => (i === index ? { ...item, ...patch, ...("drugName" in patch || "brandName" in patch ? { warnings: undefined } : {}) } : item)));
  }
  function addRxItem() { touched(); setRxItems((prev) => [...prev, { ...BLANK_RX_ITEM }]); }
  function removeRxItem(index: number) { touched(); setRxItems((prev) => prev.filter((_, i) => i !== index)); }
  /** A prescription the server just returned replaces the pad (save, favourite set applied, signing refused). */
  function applySaved(rx: Prescription) {
    const items = padItems(rx);
    baseRef.current = rxFingerprint(items);
    setRxItems(items);
    setRxDirty(false);
    void deleteDraft(entryId, "rx");
  }

  // Saves the pad and returns the saved prescription (with the server's current warnings), or null.
  // Only the fields the API accepts are sent; a recorded decision is carried by the server unless a new
  // one is passed in `overrides` (keyed by item index). `auto`: a timer save, which stays quiet about being offline.
  async function saveRx(overrides?: RxOverrideInput, auto = false): Promise<Prescription | null> {
    if (conflictRef.current || statusRef.current === "signed") return null;
    const sent = itemsRef.current;
    if (!navigator.onLine) {
      if (!auto) setRxError(t("offline"));
      setRxSaveState("offline");
      await localDraft(sent);
      return null;
    }
    setRxSaving(true);
    setRxSaveState("saving");
    setRxError(null);
    try {
      // Compare with the server copy first: another device may have saved since this one last synced.
      const g = await authedFetch(`/clinical/prescriptions/${entryId}`);
      if (g?.status === 200) {
        const server: Prescription = await g.json();
        const serverItems = padItems(server);
        const serverFp = rxFingerprint(serverItems);
        if (server.status === "signed") { setRxStatus("signed"); applySaved(server); setRxSaveState("idle"); return null; }
        if (serverFp !== baseRef.current && serverFp !== rxFingerprint(sent)) {
          setRxConflict({ mine: sent, server: serverItems });
          setRxSaveState("error");
          await localDraft(sent);
          return null;
        }
        baseRef.current = serverFp;
      } else if (g && g.status !== 404) {
        setRxSaveState("error");
        await localDraft(sent);
        return null;
      }
      const items = sent
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
        const pr: Problem = await res?.json().catch(() => ({ title: "Error", detail: t("saveFailed") }));
        setRxError(pr?.detail || t("saveFailed"));
        setRxSaveState("error");
        await localDraft(sent);
        return null;
      }
      const rx: Prescription = await res.json();
      if (itemsRef.current === sent) applySaved(rx); // typed meanwhile: keep the newer lines and the draft, the next tick saves them
      else baseRef.current = rxFingerprint(padItems(rx));
      setRxSavedAt(new Date());
      setRxSaveState("saved");
      return rx;
    } catch {
      setRxError(t("unreachable"));
      setRxSaveState(navigator.onLine ? "error" : "offline");
      await localDraft(sent);
      return null;
    } finally {
      setRxSaving(false);
    }
  }

  const saveRef = useRef(saveRx);
  useEffect(() => { saveRef.current = saveRx; });
  useEffect(() => {
    const saver = createAutosave(() => {
      if (!dirtyRef.current) return;
      void saveRef.current(undefined, true).finally(() => { if (dirtyRef.current) saver.touch(); });
    });
    autosaveRef.current = saver;
    return () => { saver.cancel(); autosaveRef.current = null; if (localTimerRef.current) clearTimeout(localTimerRef.current); };
  }, []);

  /** The doctor chose one version in the conflict dialog; "mine" is saved on the next tick, "server" replaces the pad. */
  function resolveRxConflict(keep: "mine" | "server") {
    const c = conflictRef.current;
    if (!c) return;
    setRxConflict(null);
    conflictRef.current = null;
    baseRef.current = rxFingerprint(c.server);
    if (keep === "server") { setRxItems(c.server); setRxDirty(false); setRxSaveState("idle"); void deleteDraft(entryId, "rx"); return; }
    setRxItems(c.mine);
    touched();
  }

  return {
    rxItems, rxStatus, signAuthority, rxSaving, rxError, rxDirty, rxSaveState, rxSavedAt, rxConflict,
    patchRxItem, addRxItem, removeRxItem, applySaved, saveRx, setRxError, setRxStatus, resolveRxConflict,
    /** For the visit loader: the server copy, or a restored local draft / conflict on top of it. */
    load: {
      setRxItems, setRxStatus, setSignAuthority,
      setServer: (items: PrescriptionItem[]) => { baseRef.current = rxFingerprint(items); setRxItems(items); },
      restore: (items: PrescriptionItem[]) => { setRxItems(items); touched(); },
      setConflict: (c: RxConflict) => { conflictRef.current = c; setRxConflict(c); setRxSaveState("error"); },
    },
  };
}
export type RxPad = ReturnType<typeof useRxPad>;
