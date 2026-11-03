"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import styles from "../../(clinic)/setup/setup.module.css";
import ph from "./pharmacy.module.css";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import StockPill, { LOW_STOCK } from "../ui/StockPill";
import Link from "next/link";
import Dialog from "../ui/Dialog";
import BatchAdjustDialog from "./BatchAdjustDialog";
import WorklistBoard from "./WorklistBoard";
import { REASONS, type ReasonCode } from "../../lib/stockTake";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { api, ApiError } from "../../lib/api";
import { ErrorSummary, FieldError, useFormErrors } from "../../lib/formErrors";
import type { Full } from "../../lib/gen";
import { classBadge, registerBadge, type ScheduleClass } from "../../lib/pharmacyRules";
import type { DispensingQueueEntryResponse, DispensingQueueItem, PharmacyItemResponse, PharmacySettingsResponse } from "@nabd/api-client/generated";

// Fed only by /pharmacy/* so a pharmacy-only user never touches /setup/*, and a Setup user without
// pharmacy:view never sees this tab's 403 as a page-level error (PHA-002). It owns its own loading.
export type PharmacyMode = "external" | "hybrid" | "in_house";
type PharmacySettings = Omit<Full<PharmacySettingsResponse>, "mode"> & { mode: PharmacyMode };
// PHL-002/004 fields (also in the generated type once regenerated): class, register badge, generic name, dispensed-here flag.
type PharmacyItem = Full<PharmacyItemResponse, "hsnCode"> & { scheduleClass: string; register?: string | null; genericName?: string | null; inHouse: boolean };
type DispensingQueueEntry = Omit<Full<DispensingQueueEntryResponse>, "items" | "token"> & {
  token?: number | null; items: Full<DispensingQueueItem, "stockQty" | "dosage" | "frequency" | "duration" | "instructions">[];
};

/** PHL-012: In-house pharmacists work the live worklist instead of the read-only dispensing queue. */
const boardMode = (mode: PharmacyMode) => mode === "in_house" && can("pharmacy:dispense");

const MODE_RANK: Record<PharmacyMode, number> = { external: 0, hybrid: 1, in_house: 2 };
export const MODE_LABEL: Record<PharmacyMode, string> = { external: "External", hybrid: "Hybrid", in_house: "In-house" };
export const MODE_DESC: Record<PharmacyMode, string> = {
  external: "The patient buys at any outside pharmacy. Default for every tenant, included in all plans, zero setup.",
  hybrid: "A small-shelf item master with simple stock and one-tap dispense at checkout. No batches or FEFO.",
  in_house: "Full inventory: batch and expiry, FEFO, registers and a dispensing queue. Needs a plan that includes it.",
};
// Only what the API actually offers is ticked: there is no dispense endpoint yet (the queue below is
// read-only) and the in-house extras are later-phase.
const RULES: { label: string; hybrid: boolean; inHouse: boolean }[] = [
  { label: "One-tap dispense at checkout", hybrid: true, inHouse: true },
  { label: "Simple stock", hybrid: true, inHouse: true },
  { label: "Batch & expiry (not yet available)", hybrid: false, inHouse: false },
  { label: "Controlled-drug register (not yet available)", hybrid: false, inHouse: false },
  { label: "Stock alerts (not yet available)", hybrid: false, inHouse: false },
];

/** The server's reason for a refusal, or the offline line when there was no answer. */
const problemText = (e: unknown): string =>
  e instanceof ApiError ? e.detail || "Request failed" : "Couldn't reach the server. Check your connection and try again.";

function Mark({ on }: { on: boolean }) {
  return on
    ? <span className={ph.yes}><Icon name="check" size={14} label="Included" /></span>
    : <span className={ph.no}><Icon name="x" size={14} label="Not included" /></span>;
}

// PHA-006: a mode change needs a reason (3-200 chars, server-enforced too: 400 reason-required) and is audited.
// In-house is Phase 3 and not selectable (the server answers 400 mode-not-available).
export const MODE_REASON_MIN = 3;
export const MODE_REASON_MAX = 200;
export function ModeChangeDialog({ from, to, onClose, onSaved, onLocked }: {
  from: PharmacyMode | null; to: PharmacyMode; onClose: () => void; onSaved: () => void; onLocked?: (text: string) => void;
}) {
  const [reason, setReason] = useState("");
  const fe = useFormErrors("pm", { reason: "Reason", mode: "Mode" });
  const [busy, setBusy] = useState(false);
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const r = reason.trim();
    if (r.length < MODE_REASON_MIN || r.length > MODE_REASON_MAX) { fe.set(`A reason is required (${MODE_REASON_MIN}-${MODE_REASON_MAX} characters).`); return; }
    if (busy) return;
    setBusy(true);
    fe.clear();
    try {
      await api.patch("/pharmacy/settings", { mode: to, reason: r });
      onSaved();
    } catch (err) {
      if (err instanceof ApiError && err.status === 402) onLocked?.(err.detail || "This mode is not part of your plan.");
      if (err instanceof ApiError) fe.fail(err, "Request failed");
      else fe.set("Couldn't reach the server. Nothing was changed; try again.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog title={`Switch pharmacy to ${MODE_LABEL[to]}`} onClose={onClose}>
      <form onSubmit={submit}>
        <p className={ph.modeDesc}>{from ? `Current mode: ${MODE_LABEL[from]}. ` : ""}{MODE_DESC[to]}</p>
        <div className={ph.warn} role="note">
          <strong>Nothing is deleted.</strong> Items, stock and dispense history are kept. Screens this mode does not use are hidden, and come back if you switch again.
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="pm-reason">Reason (required)</label>
          <input id="pm-reason" className={styles.input} autoFocus minLength={MODE_REASON_MIN} maxLength={MODE_REASON_MAX} required
            value={reason} {...fe.attrs("reason")} onChange={(e) => { setReason(e.target.value); fe.clear(); }} aria-describedby={fe.fields.reason ? "pm-reason-error pm-reason-hint" : "pm-reason-hint"} />
          <FieldError fe={fe} name="reason" className={ph.sub} />
          <div id="pm-reason-hint" className={ph.sub}>{MODE_REASON_MIN}-{MODE_REASON_MAX} characters. Recorded in the audit log with the old and new mode.</div>
        </div>
        <ErrorSummary fe={fe} className={`${ph.notice} ${ph.noticeError}`} />
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.btnPrimary} disabled={busy}>{busy ? "Saving…" : from && MODE_RANK[to] < MODE_RANK[from] ? "Confirm downgrade" : "Confirm switch"}</button>
        </div>
      </form>
    </Dialog>
  );
}

export function HardRuleCard() {
  return (
    <div className={ph.rule}>
      <div className={ph.ruleHead}>The hard rule</div>
      <p className={ph.ruleBody}>In External mode, every inventory and dispensing screen is completely absent — not greyed, not an empty state, genuinely absent.</p>
      <table className={ph.ruleTable}>
        <thead><tr><th scope="col">Capability</th><th scope="col">External</th><th scope="col">Hybrid</th><th scope="col">In-house</th></tr></thead>
        <tbody>
          {RULES.map((r) => (
            <tr key={r.label}>
              <td>{r.label}</td>
              <td><Mark on={false} /></td>
              <td><Mark on={r.hybrid} /></td>
              <td><Mark on={r.inHouse} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function PharmacyPanel() {
  const canEdit = can("pharmacy:edit"); // the API gates every write behind pharmacy:edit; pharmacy:view is read-only
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [settings, setSettings] = useState<PharmacySettings | null>(null);
  const [items, setItems] = useState<PharmacyItem[]>([]);
  const [queue, setQueue] = useState<DispensingQueueEntry[]>([]);
  const [classes, setClasses] = useState<ScheduleClass[]>([]);
  const [locked, setLocked] = useState<string | null>(null); // 402 entitlement-required text for the In-house card
  const [queueError, setQueueError] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ text: string; error?: boolean } | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [editing, setEditing] = useState<PharmacyItem | null>(null);
  // Stock changes (V2-004): +/- opens a reason dialog and posts a delta (or a counted total) to the stock-adjustments
  // endpoint; the server keeps an immutable movement row and an audit event. Never an absolute overwrite from a stale list.
  const [adjusting, setAdjusting] = useState<{ item: PharmacyItem; mode: "delta" | "total"; qty: string; reasonCode: ReasonCode | ""; reason: string; error: string | null } | null>(null);
  const [adjustBusy, setAdjustBusy] = useState(false);
  // PHL-010: a controlled item (a class with a register) changes only by batch, with a second sign-off
  const [batchAdjust, setBatchAdjust] = useState<PharmacyItem | null>(null);
  const closeBatchAdjust = useCallback(() => setBatchAdjust(null), [setBatchAdjust]);
  const [deactivating, setDeactivating] = useState<{ item: PharmacyItem; reason: string; error: string | null } | null>(null);
  const [deactivateBusy, setDeactivateBusy] = useState(false);
  // Stable identities: the shared Dialog re-runs its focus effect whenever onClose changes, which would pull focus
  // out of the field being typed in (and make Space close the dialog).
  const closeEdit = useCallback(() => setEditing(null), [setEditing]);
  const closeAdjust = useCallback(() => setAdjusting(null), [setAdjusting]);
  const closeDeactivate = useCallback(() => setDeactivating(null), [setDeactivating]);
  const [switchTo, setSwitchTo] = useState<PharmacyMode | null>(null);
  const closeSwitch = useCallback(() => setSwitchTo(null), [setSwitchTo]);
  const reasonRef = useRef<HTMLInputElement>(null);
  const [newItem, setNewItem] = useState<Partial<PharmacyItem>>({ isRx: false, stockQty: 0, taxRatePercent: 0, scheduleClass: "OTC", inHouse: true });

  const afe = useFormErrors("ph", { name: "Name", price: "Price", taxRatePercent: "Tax rate", stockQty: "Stock qty", hsnCode: "HSN code", isRx: "Rx" });
  const efe = useFormErrors("pe", { name: "Name", price: "Price", taxRatePercent: "Tax rate", hsnCode: "HSN code", isRx: "Rx" });
  const jfe = useFormErrors("pa", { delta: "Change", setTo: "Counted total", reasonCode: "Reason", reason: "Note" });

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    const s = await Promise.allSettled([api.get<PharmacySettings>("/pharmacy/settings")]).then((r) => r[0]);
    if (s.status === "rejected") setLoadError(problemText(s.reason));
    else if (s.value.mode === "external") {
      // External: no item master, queue or classes are fetched at all (the server answers 403 pharmacy-mode-external).
      setSettings(s.value); setItems([]); setQueue([]); setClasses([]);
    } else {
      setSettings(s.value);
      const [i, q, c] = await Promise.allSettled([
        api.get<PharmacyItem[]>("/pharmacy/items"),
        boardMode(s.value.mode) ? Promise.resolve([]) : api.get<DispensingQueueEntry[]>("/pharmacy/dispensing-queue"), // PHL-012: the worklist replaces it
        api.get<ScheduleClass[]>("/pharmacy/schedule-classes"),
      ]);
      if (i.status === "rejected") setLoadError(problemText(i.reason));
      else {
        setItems(i.value);
        if (q.status === "rejected") setQueueError(problemText(q.reason)); else { setQueue(q.value); setQueueError(null); }
        setClasses(c.status === "fulfilled" && Array.isArray(c.value) ? c.value : []);
      }
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    void Promise.resolve().then(load);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** A write to /pharmacy/*; a refusal goes to `fe` (field errors beside the inputs) or, with none given, to the page notice. */
  const send = async (method: "POST" | "PATCH", path: string, body: unknown, fe?: ReturnType<typeof useFormErrors>): Promise<boolean> => {
    fe?.clear();
    try {
      await (method === "POST" ? api.post(path, body) : api.patch(path, body));
      return true;
    } catch (err) {
      if (fe) fe.fail(err, "Request failed"); else setNotice({ text: problemText(err), error: true });
      return false;
    }
  };

  const modeSaved = async () => {
    if (switchTo) setNotice({ text: `Pharmacy mode saved: ${MODE_LABEL[switchTo]}.` });
    setSwitchTo(null);
    await load();
  };

  // PATCH edits name, price, tax, Rx flag and HSN only. Stock is never sent here: it changes through the stock
  // adjustment endpoint, so an edit made from a stale list cannot overwrite a newer count. Code and active are
  // not part of the item body (codes are generated; activation has its own endpoint).
  const itemBody = (i: PharmacyItem) => ({
    name: i.name, isRx: i.isRx, hsnCode: i.hsnCode || null, price: i.price, taxRatePercent: i.taxRatePercent,
    scheduleClass: i.scheduleClass, genericName: i.genericName || null, inHouse: i.inHouse,
    priceInclusiveOfTax: i.priceInclusiveOfTax ?? null, zeroRated: !!i.zeroRated, mrp: i.mrp ?? null,
  });

  const openAdjust = (item: PharmacyItem, direction: 1 | -1) => {
    setNotice(null);
    if (item.register) { setBatchAdjust(item); return; }
    setAdjusting({ item, mode: "delta", qty: direction === 1 ? "1" : "-1", reasonCode: "", reason: "", error: null });
  };

  const submitAdjust = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!adjusting) return;
    const qty = Number(adjusting.qty);
    const reason = adjusting.reason.trim();
    const delta = adjusting.mode === "delta";
    if (adjusting.qty.trim() === "" || !Number.isInteger(qty)) { setAdjusting({ ...adjusting, error: "Enter a whole number." }); return; }
    if (delta && qty === 0) { setAdjusting({ ...adjusting, error: "Enter a change other than 0." }); return; }
    if (!delta && qty < 0) { setAdjusting({ ...adjusting, error: "A counted total cannot be negative." }); return; }
    if (delta && adjusting.item.stockQty + qty < 0) { setAdjusting({ ...adjusting, error: `Only ${adjusting.item.stockQty} in stock, so it cannot go down by ${-qty}.` }); return; }
    // PHL-010: the reason comes from the fixed list; the note is required for "Other"
    if (!adjusting.reasonCode) { setAdjusting({ ...adjusting, error: "Choose a reason." }); return; }
    if (adjusting.reasonCode === "other" && reason === "") { setAdjusting({ ...adjusting, error: "Reason \"Other\" needs a note." }); reasonRef.current?.focus(); return; }
    if (adjustBusy) return;
    setAdjustBusy(true);
    jfe.clear();
    try {
      const why = { reasonCode: adjusting.reasonCode, reason: reason || undefined };
      await api.post(`/pharmacy/items/${adjusting.item.id}/stock-adjustments`, delta ? { delta: qty, ...why } : { setTo: qty, ...why });
      setNotice({ text: `Stock of ${adjusting.item.name} updated.` });
      setAdjusting(null);
      await load(); // the server's count is authoritative: a concurrent sale or second pharmacist shows up here
    } catch (err) {
      if (err instanceof ApiError) jfe.fail(err, "Request failed");
      else setAdjusting({ ...adjusting, error: "Couldn't reach the server. Nothing was changed; try again." });
    } finally {
      setAdjustBusy(false);
    }
  };

  const setActive = async (item: PharmacyItem, active: boolean, reason?: string): Promise<boolean> => {
    setBusyId(item.id);
    setNotice(null);
    const ok = await send("PATCH", `/pharmacy/items/${item.id}/status`, { active, reason: reason || undefined });
    if (ok) { setNotice({ text: `${item.name} ${active ? "activated" : "deactivated"}.` }); await load(); }
    setBusyId(null);
    return ok;
  };

  const submitDeactivate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!deactivating) return;
    setDeactivateBusy(true);
    const ok = await setActive(deactivating.item, false, deactivating.reason.trim());
    setDeactivateBusy(false);
    if (ok) setDeactivating(null);
    else setDeactivating({ ...deactivating, error: "Couldn't deactivate the item. Nothing was changed." });
  };

  const saveEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editing) return;
    if (!editing.name.trim() || !(editing.price >= 0)) return;
    if (await send("PATCH", `/pharmacy/items/${editing.id}`, itemBody(editing), efe)) {
      setNotice({ text: `${editing.name} saved.` });
      setEditing(null);
      await load();
    }
  };

  const addItem = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newItem.name || newItem.price == null) return;
    setNotice(null);
    if (await send("POST", "/pharmacy/items", { ...newItem, isRx: classes.find((c) => c.code === newItem.scheduleClass)?.requiresRx ?? false }, afe)) {
      setNotice({ text: `${newItem.name} added.` });
      setNewItem({ isRx: false, stockQty: 0, taxRatePercent: 0, scheduleClass: "OTC", inHouse: true });
      await load();
    }
  };

  if (loading && !settings) return <div className={styles.card}><Skeleton rows={5} label="Loading pharmacy" /></div>;
  if (loadError || !settings) return <div className={styles.card}><ErrorState message={loadError ?? "Pharmacy settings are unavailable."} onRetry={load} /></div>;

  const mode = settings.mode;
  const canWrite = mode !== "external" && canEdit; // Hybrid and In-house both manage items

  return (
    <div className={styles.card}>
      <h2 className={styles.cardTitle}>Pharmacy</h2>
      {canEdit ? (
        <div className={ph.modeCards} role="group" aria-label="Pharmacy mode">
          {(["external", "hybrid", "in_house"] as const).map((m) => (
            <button key={m} type="button" aria-pressed={mode === m} data-testid={`mode-card-${m}`} className={`${ph.modeCard} ${mode === m ? ph.modeCardOn : ""}`}
              onClick={() => { if (m !== mode) { setNotice(null); setSwitchTo(m); } }}>
              <strong>{MODE_LABEL[m]}{mode === m ? " · current" : ""}</strong>
              <span>{MODE_DESC[m]}</span>
              {m === "in_house" && locked && <span className={ph.lockNote} role="note">{locked}</span>}
            </button>
          ))}
        </div>
      ) : (
        // pharmacy:view only: the mode is information, not a control (PHA-016).
        <p className={styles.subtitle}>Pharmacy mode: <strong>{MODE_LABEL[mode]}</strong></p>
      )}
      <p className={ph.modeDesc}>{MODE_DESC[mode]}</p>
      {notice && (
        <div className={`${ph.notice} ${notice.error ? ph.noticeError : ""}`} role={notice.error ? "alert" : "status"}>
          <Icon name={notice.error ? "alert" : "check"} size={14} /> {notice.text}
        </div>
      )}
      <HardRuleCard />

      {mode === "external" ? (
        <p className={styles.subtitle}>Item master and dispensing queue are not shown in External mode.{canEdit ? " Switch to Hybrid to add items." : ""}</p>
      ) : (
        <>
          {!canEdit ? null : (
            <>
              <form onSubmit={addItem}>
                <div className={ph.formRow}>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="ph-name">Name</label>
                    <input id="ph-name" className={styles.input} value={newItem.name ?? ""} {...afe.attrs("name")} onChange={(e) => { afe.clearField("name"); setNewItem({ ...newItem, name: e.target.value }); }} />
                    <FieldError fe={afe} name="name" className={ph.sub} />
                  </div>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="ph-price">Price</label>
                    <input id="ph-price" className={styles.input} type="number" step="0.01" min="0" value={newItem.price ?? ""} {...afe.attrs("price")} onChange={(e) => { afe.clearField("price"); setNewItem({ ...newItem, price: Number(e.target.value) }); }} />
                    <FieldError fe={afe} name="price" className={ph.sub} />
                  </div>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="ph-tax">Tax rate %</label>
                    <input id="ph-tax" className={styles.input} type="number" step="0.01" min="0" value={newItem.taxRatePercent ?? 0} {...afe.attrs("taxRatePercent")} onChange={(e) => { afe.clearField("taxRatePercent"); setNewItem({ ...newItem, taxRatePercent: Number(e.target.value) }); }} />
                    <FieldError fe={afe} name="taxRatePercent" className={ph.sub} />
                  </div>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="ph-stock">Stock qty</label>
                    <input id="ph-stock" className={styles.input} type="number" min="0" value={newItem.stockQty ?? 0} {...afe.attrs("stockQty")} onChange={(e) => { afe.clearField("stockQty"); setNewItem({ ...newItem, stockQty: Number(e.target.value) }); }} />
                    <FieldError fe={afe} name="stockQty" className={ph.sub} />
                  </div>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="ph-hsn">HSN code</label>
                    <input id="ph-hsn" className={styles.input} value={newItem.hsnCode ?? ""} {...afe.attrs("hsnCode")} onChange={(e) => { afe.clearField("hsnCode"); setNewItem({ ...newItem, hsnCode: e.target.value }); }} />
                    <FieldError fe={afe} name="hsnCode" className={ph.sub} />
                  </div>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="ph-class">Schedule class</label>
                    <select id="ph-class" className={styles.select} value={newItem.scheduleClass ?? "OTC"} onChange={(e) => setNewItem({ ...newItem, scheduleClass: e.target.value })}>
                      {(classes.length ? classes : [{ code: "OTC", label: "Over the counter" }]).map((c) => <option key={c.code} value={c.code}>{c.label}</option>)}
                    </select>
                  </div>
                  <div className={styles.field}>
                    <label className={ph.check} htmlFor="ph-inhouse"><input id="ph-inhouse" type="checkbox" checked={newItem.inHouse !== false} onChange={(e) => setNewItem({ ...newItem, inHouse: e.target.checked })} /> Dispensed here</label>
                  </div>
                </div>
                <ErrorSummary fe={afe} className={`${ph.notice} ${ph.noticeError}`} />
                <div className={styles.actions}>
                  <button className={styles.btnPrimary} type="submit">Add item</button>
                </div>
              </form>
              <p className={styles.subtitle}>Batch, expiry and purchase orders arrive with the In-house pharmacy stories; stock here is a simple count.</p>
            </>
          )}

          {items.length === 0 ? (
            <EmptyState
              title="No pharmacy items yet"
              hint={canWrite ? "Add your first item above — name and price are enough to start." : canEdit ? "Switch to Hybrid mode to add the first item." : "No items have been added."}
              icon="package"
            />
          ) : (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr><th scope="col">Code</th><th scope="col">Name</th><th scope="col">Price</th><th scope="col">Tax %</th><th scope="col">Stock</th><th scope="col">Class</th><th scope="col">HSN</th><th scope="col">Status</th>{canWrite && <th scope="col"></th>}</tr>
                </thead>
                <tbody>
                  {items.map((i) => (
                    <tr key={i.id}>
                      <td>{i.code}</td>
                      <td>{i.name}</td>
                      <td>{i.price}</td>
                      <td>{i.taxRatePercent}</td>
                      <td>
                        <div className={ph.stepper}>
                          {canWrite && <button type="button" className={ph.stepBtn} aria-label={`Decrease stock of ${i.name}`} disabled={busyId === i.id || i.stockQty <= 0} onClick={() => openAdjust(i, -1)}>−</button>}
                          <span className={ph.qty}>{i.stockQty}</span>
                          {canWrite && <button type="button" className={ph.stepBtn} aria-label={`Increase stock of ${i.name}`} disabled={busyId === i.id} onClick={() => openAdjust(i, 1)}>+</button>}
                          {i.stockQty === 0 ? <StatusPill tone="danger" label="Out of stock" /> : i.stockQty <= LOW_STOCK ? <StatusPill tone="warn" label="Low" /> : null}
                        </div>
                      </td>
                      <td>
                        <StatusPill tone={i.isRx ? "info" : "neutral"} label={classBadge(i.scheduleClass)} icon={i.isRx ? "pulse" : "list"} />
                        {registerBadge(i.register) && <> <StatusPill tone="warn" label={registerBadge(i.register) as string} /></>}
                        {i.inHouse === false && <> <StatusPill tone="neutral" label="Buy outside" /></>}
                      </td>
                      <td>{i.hsnCode ?? "—"}</td>
                      <td><StatusPill tone={i.active ? "ok" : "neutral"} label={i.active ? "Active" : "Inactive"} icon={i.active ? "check" : "x"} /></td>
                      {canWrite && (
                        <td>
                          <button type="button" className={styles.smallBtn} onClick={() => setEditing(i)}>Edit</button>{" "}
                          {i.active
                            ? <button type="button" className={styles.smallBtn} disabled={busyId === i.id} onClick={() => setDeactivating({ item: i, reason: "", error: null })}>Deactivate</button>
                            : <button type="button" className={styles.smallBtn} disabled={busyId === i.id} onClick={() => void setActive(i, true)}>Activate</button>}
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {canWrite && <p>{mode === "hybrid" && <><Link href="/pharmacy/receipt" className={styles.btn}>Enter supplier bill</Link>{" "}</>}<Link href="/pharmacy/stock-take" className={styles.btn}>Stock take</Link></p>}
          {boardMode(mode) ? <WorklistBoard /> : <>
          <h2 className={styles.cardTitle} style={{ marginTop: "var(--nb-space-24)" }}>Dispensing queue</h2>
          <p className={styles.subtitle}>Signed prescriptions still ahead of checkout. Open a row to dispense on that patient&apos;s billing screen.</p>
          {queueError ? (
            <ErrorState message={queueError} onRetry={load} />
          ) : queue.length === 0 ? (
            <div className={styles.empty}>Nothing waiting to be dispensed.</div>
          ) : (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead><tr><th scope="col">Token</th><th scope="col">Patient</th><th scope="col">Doctor</th><th scope="col">Drugs</th><th scope="col">Signed at</th></tr></thead>
                <tbody>
                  {queue.map((q) => (
                    <tr key={q.prescriptionId}>
                      <td>{q.token != null ? `T-${q.token}` : "—"}</td>
                      <td><Link href={`/checkout/${q.queueEntryId}`}>{q.patientName}</Link></td>
                      <td>{q.doctorName}</td>
                      <td>
                        {q.items.map((d, n) => {
                          const sub = [d.dosage, d.frequency, d.duration].filter(Boolean).join(" · ");
                          return (
                            <div key={n} style={{ marginBottom: n < q.items.length - 1 ? "var(--nb-space-8)" : 0 }}>
                              <div>{d.drugName} <StockPill qty={d.stockQty} /></div>
                              {sub && <div className={ph.sub}>{sub}</div>}
                              {d.instructions && (
                                <details className={ph.instr}>
                                  <summary>Instructions</summary>
                                  <p>{d.instructions}</p>
                                </details>
                              )}
                            </div>
                          );
                        })}
                      </td>
                      <td>{new Date(q.signedAt).toLocaleTimeString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          </>}
        </>
      )}

      {switchTo && <ModeChangeDialog from={mode} to={switchTo} onClose={closeSwitch} onSaved={() => void modeSaved()} onLocked={setLocked} />}

      {editing && (
        <Dialog title={`Edit ${editing.name}`} size="drawer" onClose={closeEdit}>
          <form onSubmit={saveEdit}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pe-name">Name</label>
              <input id="pe-name" className={styles.input} value={editing.name} {...efe.attrs("name")} onChange={(e) => { efe.clearField("name"); setEditing({ ...editing, name: e.target.value }); }} required />
              <FieldError fe={efe} name="name" className={ph.sub} />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pe-price">Price</label>
              <input id="pe-price" className={styles.input} type="number" step="0.01" min="0" value={editing.price} {...efe.attrs("price")} onChange={(e) => { efe.clearField("price"); setEditing({ ...editing, price: Number(e.target.value) }); }} required />
              <FieldError fe={efe} name="price" className={ph.sub} />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pe-tax">Tax rate %</label>
              <input id="pe-tax" className={styles.input} type="number" step="0.01" min="0" value={editing.taxRatePercent} {...efe.attrs("taxRatePercent")} onChange={(e) => { efe.clearField("taxRatePercent"); setEditing({ ...editing, taxRatePercent: Number(e.target.value) }); }} />
              <FieldError fe={efe} name="taxRatePercent" className={ph.sub} />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pe-mrp">MRP</label>
              <input id="pe-mrp" className={styles.input} type="number" step="0.01" min="0" value={editing.mrp ?? ""} onChange={(e) => setEditing({ ...editing, mrp: (e.target.value === "" ? null : Number(e.target.value)) as number })} />
            </div>
            <div className={styles.field}>
              <label><input type="checkbox" checked={!!editing.zeroRated} onChange={(e) => setEditing({ ...editing, zeroRated: e.target.checked })} /> Zero-rated (0% tax, noted on the invoice)</label>
            </div>
            <div className={styles.field}>
              <label><input type="checkbox" checked={editing.priceInclusiveOfTax ?? false} onChange={(e) => setEditing({ ...editing, priceInclusiveOfTax: e.target.checked })} /> Price includes tax</label>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pe-hsn">HSN code</label>
              <input id="pe-hsn" className={styles.input} value={editing.hsnCode ?? ""} {...efe.attrs("hsnCode")} onChange={(e) => { efe.clearField("hsnCode"); setEditing({ ...editing, hsnCode: e.target.value }); }} />
              <FieldError fe={efe} name="hsnCode" className={ph.sub} />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pe-class">Schedule class</label>
              <select id="pe-class" className={styles.select} value={editing.scheduleClass} onChange={(e) => setEditing({ ...editing, scheduleClass: e.target.value, isRx: classes.find((c) => c.code === e.target.value)?.requiresRx ?? editing.isRx })}>
                {(classes.some((c) => c.code === editing.scheduleClass) ? classes : [...classes, { code: editing.scheduleClass, label: editing.scheduleClass }]).map((c) => <option key={c.code} value={c.code}>{c.label}</option>)}
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pe-generic">Generic name</label>
              <input id="pe-generic" className={styles.input} maxLength={200} value={editing.genericName ?? ""} onChange={(e) => setEditing({ ...editing, genericName: e.target.value })} />
            </div>
            <label className={ph.check}><input type="checkbox" checked={editing.inHouse !== false} onChange={(e) => setEditing({ ...editing, inHouse: e.target.checked })} /> Dispensed here (untick for &quot;buy outside&quot;)</label>
            <ErrorSummary fe={efe} className={`${ph.notice} ${ph.noticeError}`} />
            <div className={styles.actions}>
              <button type="button" className={styles.btn} onClick={closeEdit}>Cancel</button>
              <button type="submit" className={styles.btnPrimary}>Save item</button>
            </div>
          </form>
        </Dialog>
      )}

      {adjusting && (
        <Dialog title={`Change stock · ${adjusting.item.name}`} size="drawer" onClose={closeAdjust}>
          <form onSubmit={submitAdjust}>
            <p className={styles.subtitle} style={{ marginBlockEnd: "var(--nb-space-16)" }}>Current stock: {adjusting.item.stockQty}. Every change is logged with who, when and why, and cannot be edited afterwards.</p>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pa-mode">Type of change</label>
              <select id="pa-mode" className={styles.select} value={adjusting.mode} onChange={(e) => setAdjusting({ ...adjusting, mode: e.target.value as "delta" | "total", qty: e.target.value === "total" ? String(adjusting.item.stockQty) : "1", error: null })}>
                <option value="delta">Add or remove units (+/−)</option>
                <option value="total">Set to a counted total (stock-take)</option>
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pa-qty">{adjusting.mode === "delta" ? "Change (negative to remove)" : "Counted total"}</label>
              <input id="pa-qty" className={styles.input} type="number" step="1" value={adjusting.qty} {...jfe.attrs(adjusting.mode === "delta" ? "delta" : "setTo")} onChange={(e) => { jfe.clear(); setAdjusting({ ...adjusting, qty: e.target.value, error: null }); }} required />
              <FieldError fe={jfe} name={adjusting.mode === "delta" ? "delta" : "setTo"} className={ph.sub} />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pa-code">Reason</label>
              <select id="pa-code" className={styles.select} value={adjusting.reasonCode} {...jfe.attrs("reasonCode")} onChange={(e) => { jfe.clearField("reasonCode"); setAdjusting({ ...adjusting, reasonCode: e.target.value as ReasonCode, error: null }); }} required>
                <option value="">Choose…</option>
                {REASONS.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
              </select>
              <FieldError fe={jfe} name="reasonCode" className={ph.sub} />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pa-reason">Note{adjusting.reasonCode === "other" ? " (required)" : " (optional)"}</label>
              <input id="pa-reason" ref={reasonRef} className={styles.input} maxLength={500} value={adjusting.reason} {...jfe.attrs("reason")} onChange={(e) => { jfe.clearField("reason"); setAdjusting({ ...adjusting, reason: e.target.value, error: null }); }} />
              <FieldError fe={jfe} name="reason" className={ph.sub} />
            </div>
            {adjusting.error && <div className={`${ph.notice} ${ph.noticeError}`} role="alert"><Icon name="alert" size={14} /> {adjusting.error}</div>}
            <ErrorSummary fe={jfe} className={`${ph.notice} ${ph.noticeError}`} />
            <div className={styles.actions}>
              <button type="button" className={styles.btn} onClick={closeAdjust}>Cancel</button>
              <button type="submit" className={styles.btnPrimary} disabled={adjustBusy}>{adjustBusy ? "Saving…" : "Record change"}</button>
            </div>
          </form>
        </Dialog>
      )}

      {batchAdjust && (
        <BatchAdjustDialog item={batchAdjust} controlled={!!batchAdjust.register} onClose={closeBatchAdjust}
          onSaved={(text) => { setBatchAdjust(null); setNotice({ text }); void load(); }} />
      )}

      {deactivating && (
        <Dialog title={`Deactivate ${deactivating.item.name}`} size="drawer" onClose={closeDeactivate}>
          <form onSubmit={submitDeactivate}>
            <p className={styles.subtitle} style={{ marginBlockEnd: "var(--nb-space-16)" }}>A deactivated item no longer appears at checkout. Its history and stock count stay, and you can activate it again at any time.</p>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pd-reason">Reason (optional)</label>
              <input id="pd-reason" className={styles.input} maxLength={500} value={deactivating.reason} onChange={(e) => setDeactivating({ ...deactivating, reason: e.target.value, error: null })} />
            </div>
            {deactivating.error && <div className={`${ph.notice} ${ph.noticeError}`} role="alert"><Icon name="alert" size={14} /> {deactivating.error}</div>}
            <div className={styles.actions}>
              <button type="button" className={styles.btn} onClick={closeDeactivate}>Cancel</button>
              <button type="submit" className={styles.btnPrimary} disabled={deactivateBusy}>{deactivateBusy ? "Saving…" : "Deactivate"}</button>
            </div>
          </form>
        </Dialog>
      )}
    </div>
  );
}
