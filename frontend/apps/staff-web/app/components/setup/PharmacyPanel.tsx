"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import styles from "../../setup/setup.module.css";
import ph from "./pharmacy.module.css";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import StockPill, { LOW_STOCK } from "../ui/StockPill";
import Link from "next/link";
import Dialog from "../ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";

// Fed only by /pharmacy/* so a pharmacy-only user never touches /setup/*, and a Setup user without
// pharmacy:view never sees this tab's 403 as a page-level error (PHA-002). It owns its own loading.
export type PharmacyMode = "external" | "hybrid" | "in_house";
type PharmacySettings = { mode: PharmacyMode };
type PharmacyItem = { id: string; code: string; name: string; isRx: boolean; hsnCode: string | null; price: number; taxRatePercent: number; stockQty: number; active: boolean };
type DispensingQueueEntry = {
  prescriptionId: string; queueEntryId: string; token?: string | number | null; patientName: string; doctorName: string; signedAt: string;
  items: { stockQty?: number | null; drugName: string; dosage: string | null; frequency: string | null; duration: string | null; instructions: string | null }[];
};
type AuthedFetch = (path: string, init?: RequestInit) => Promise<Response | null>;

export const MODE_LABEL: Record<PharmacyMode, string> = { external: "External", hybrid: "Hybrid", in_house: "In-house" };
export const MODE_DESC: Record<PharmacyMode, string> = {
  external: "The patient buys at any outside pharmacy. Default for every tenant, included in all plans, zero setup.",
  hybrid: "In-house cosmeceuticals, external antibiotics. Phase 2, paid tier, low setup.",
  in_house: "Clinics that sell or dispense at the desk. Phase 3, paid tier, highest setup.",
};
// Only what the API actually offers is ticked: there is no dispense endpoint yet (the queue below is
// read-only) and the in-house extras are later-phase.
const RULES: { label: string; hybrid: boolean; inHouse: boolean }[] = [
  { label: "One-tap dispense (not yet available)", hybrid: false, inHouse: false },
  { label: "Simple stock", hybrid: true, inHouse: true },
  { label: "Batch & expiry (not yet available)", hybrid: false, inHouse: false },
  { label: "Controlled-drug register (not yet available)", hybrid: false, inHouse: false },
  { label: "Stock alerts (not yet available)", hybrid: false, inHouse: false },
];

async function problemText(res: Response): Promise<string> {
  const p = await res.json().catch(() => null);
  return p?.detail || "Request failed";
}

function Mark({ on }: { on: boolean }) {
  return on
    ? <span className={ph.yes}><Icon name="check" size={14} label="Included" /></span>
    : <span className={ph.no}><Icon name="x" size={14} label="Not included" /></span>;
}

// PHA-006: a mode change needs a reason (3-200 chars, server-enforced too: 400 reason-required) and is audited.
// In-house is Phase 3 and not selectable (the server answers 400 mode-not-available).
export const MODE_REASON_MIN = 3;
export const MODE_REASON_MAX = 200;
export function ModeChangeDialog({ from, to, authedFetch, onClose, onSaved }: {
  from: PharmacyMode | null; to: PharmacyMode; authedFetch: AuthedFetch; onClose: () => void; onSaved: () => void;
}) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const r = reason.trim();
    if (r.length < MODE_REASON_MIN || r.length > MODE_REASON_MAX) { setError(`A reason is required (${MODE_REASON_MIN}-${MODE_REASON_MAX} characters).`); return; }
    setBusy(true);
    try {
      const res = await authedFetch("/pharmacy/settings", { method: "PATCH", body: JSON.stringify({ mode: to, reason: r }) });
      if (!res) return;
      if (!res.ok) { setError(await problemText(res)); return; }
      onSaved();
    } catch {
      setError("Couldn't reach the server. Nothing was changed; try again.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog title={`Switch pharmacy to ${MODE_LABEL[to]}`} onClose={onClose}>
      <form onSubmit={submit}>
        <p className={ph.modeDesc}>{from ? `Current mode: ${MODE_LABEL[from]}. ` : ""}{MODE_DESC[to]}</p>
        {from === "hybrid" && (
          <div className={ph.warn} role="note">
            <strong>Downgrade from Hybrid.</strong> Pharmacy items stop appearing at checkout, counter sales stop, and the item
            master and dispensing queue are hidden. Items and stock history are kept, not deleted, and come back if you switch to Hybrid again.
          </div>
        )}
        <div className={styles.field}>
          <label className={styles.label} htmlFor="pm-reason">Reason (required)</label>
          <input id="pm-reason" className={styles.input} autoFocus minLength={MODE_REASON_MIN} maxLength={MODE_REASON_MAX} required
            value={reason} onChange={(e) => { setReason(e.target.value); setError(null); }} aria-describedby="pm-reason-hint" />
          <div id="pm-reason-hint" className={ph.sub}>{MODE_REASON_MIN}-{MODE_REASON_MAX} characters. Recorded in the audit log with the old and new mode.</div>
        </div>
        {error && <div className={`${ph.notice} ${ph.noticeError}`} role="alert"><Icon name="alert" size={14} /> {error}</div>}
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.btnPrimary} disabled={busy}>{busy ? "Saving…" : from === "hybrid" ? "Confirm downgrade" : "Confirm switch"}</button>
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

export default function PharmacyPanel({ authedFetch }: { authedFetch: AuthedFetch }) {
  const canEdit = can("pharmacy:edit"); // the API gates every write behind pharmacy:edit; pharmacy:view is read-only
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [settings, setSettings] = useState<PharmacySettings | null>(null);
  const [items, setItems] = useState<PharmacyItem[]>([]);
  const [queue, setQueue] = useState<DispensingQueueEntry[]>([]);
  const [queueError, setQueueError] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ text: string; error?: boolean } | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [editing, setEditing] = useState<PharmacyItem | null>(null);
  // Stock changes (V2-004): +/- opens a reason dialog and posts a delta (or a counted total) to the stock-adjustments
  // endpoint; the server keeps an immutable movement row and an audit event. Never an absolute overwrite from a stale list.
  const [adjusting, setAdjusting] = useState<{ item: PharmacyItem; mode: "delta" | "total"; qty: string; reason: string; error: string | null } | null>(null);
  const [adjustBusy, setAdjustBusy] = useState(false);
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
  const [newItem, setNewItem] = useState<Partial<PharmacyItem>>({ isRx: false, stockQty: 0, taxRatePercent: 0 });

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      type R = { aborted: boolean; error: string | null; data: unknown };
      const get = async (path: string): Promise<R> => {
        const res = await authedFetch(path);
        if (!res) return { aborted: true, error: null, data: null };
        if (!res.ok) return { aborted: false, error: await problemText(res), data: null };
        return { aborted: false, error: null, data: await res.json() };
      };
      const [s, i, q] = await Promise.all([get("/pharmacy/settings"), get("/pharmacy/items"), get("/pharmacy/dispensing-queue")]);
      if (s.aborted || i.aborted || q.aborted) return;
      if (s.error) { setLoadError(s.error); return; }
      setSettings(s.data as PharmacySettings);
      if (i.error) { setLoadError(i.error); return; }
      setItems(i.data as PharmacyItem[]);
      if (q.error) setQueueError(q.error); else { setQueue(q.data as DispensingQueueEntry[]); setQueueError(null); }
    } catch {
      setLoadError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch]);

  useEffect(() => {
    void Promise.resolve().then(load);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const send = async (method: "POST" | "PATCH", path: string, body: unknown): Promise<boolean> => {
    try {
      const res = await authedFetch(path, { method, body: JSON.stringify(body) });
      if (!res) return false;
      if (!res.ok) { setNotice({ text: await problemText(res), error: true }); return false; }
      return true;
    } catch {
      setNotice({ text: "Couldn't reach the server. Check your connection and try again.", error: true });
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
  });

  const openAdjust = (item: PharmacyItem, direction: 1 | -1) => {
    setNotice(null);
    setAdjusting({ item, mode: "delta", qty: direction === 1 ? "1" : "-1", reason: "", error: null });
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
    if (reason.length < 3) { setAdjusting({ ...adjusting, error: "A reason is required (at least 3 characters)." }); reasonRef.current?.focus(); return; }
    setAdjustBusy(true);
    try {
      const res = await authedFetch(`/pharmacy/items/${adjusting.item.id}/stock-adjustments`, {
        method: "POST", body: JSON.stringify(delta ? { delta: qty, reason } : { setTo: qty, reason }),
      });
      if (!res) return;
      if (!res.ok) { setAdjusting({ ...adjusting, error: await problemText(res) }); return; }
      setNotice({ text: `Stock of ${adjusting.item.name} updated.` });
      setAdjusting(null);
      await load(); // the server's count is authoritative: a concurrent sale or second pharmacist shows up here
    } catch {
      setAdjusting({ ...adjusting, error: "Couldn't reach the server. Nothing was changed; try again." });
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
    if (await send("PATCH", `/pharmacy/items/${editing.id}`, itemBody(editing))) {
      setNotice({ text: `${editing.name} saved.` });
      setEditing(null);
      await load();
    }
  };

  const addItem = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newItem.name || newItem.price == null) return;
    setNotice(null);
    if (await send("POST", "/pharmacy/items", newItem)) {
      setNotice({ text: `${newItem.name} added.` });
      setNewItem({ isRx: false, stockQty: 0, taxRatePercent: 0 });
      await load();
    }
  };

  if (loading && !settings) return <div className={styles.card}><Skeleton rows={5} label="Loading pharmacy" /></div>;
  if (loadError || !settings) return <div className={styles.card}><ErrorState message={loadError ?? "Pharmacy settings are unavailable."} onRetry={load} /></div>;

  const mode = settings.mode;
  const hybrid = mode === "hybrid";
  const canWrite = hybrid && canEdit;

  return (
    <div className={styles.card}>
      <h2 className={styles.cardTitle}>Pharmacy</h2>
      {canEdit ? (
        <div className={styles.tabs} role="group" aria-label="Pharmacy mode">
          {(["external", "hybrid"] as const).map((m) => (
            <button key={m} type="button" aria-pressed={mode === m} className={mode === m ? styles.tabActive : styles.tab}
              onClick={() => { if (m !== mode) { setNotice(null); setSwitchTo(m); } }}>
              {MODE_LABEL[m]}
            </button>
          ))}
          <span className={mode === "in_house" ? styles.tabActive : ph.na}>{MODE_LABEL.in_house} · {mode === "in_house" ? "current" : "Not available yet"}</span>
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
          {!canEdit ? null : mode === "in_house" ? (
            <div className={styles.empty}>Batch &amp; expiry tracking, the controlled-drug register, stock alerts and FEFO suggestions are later-phase capabilities and aren&apos;t available yet. Switch to Hybrid to add or edit items; existing items stay visible below, read-only.</div>
          ) : (
            <>
              <form onSubmit={addItem}>
                <div className={ph.formRow}>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="ph-name">Name</label>
                    <input id="ph-name" className={styles.input} value={newItem.name ?? ""} onChange={(e) => setNewItem({ ...newItem, name: e.target.value })} />
                  </div>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="ph-price">Price</label>
                    <input id="ph-price" className={styles.input} type="number" step="0.01" min="0" value={newItem.price ?? ""} onChange={(e) => setNewItem({ ...newItem, price: Number(e.target.value) })} />
                  </div>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="ph-tax">Tax rate %</label>
                    <input id="ph-tax" className={styles.input} type="number" step="0.01" min="0" value={newItem.taxRatePercent ?? 0} onChange={(e) => setNewItem({ ...newItem, taxRatePercent: Number(e.target.value) })} />
                  </div>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="ph-stock">Stock qty</label>
                    <input id="ph-stock" className={styles.input} type="number" min="0" value={newItem.stockQty ?? 0} onChange={(e) => setNewItem({ ...newItem, stockQty: Number(e.target.value) })} />
                  </div>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="ph-hsn">HSN code</label>
                    <input id="ph-hsn" className={styles.input} value={newItem.hsnCode ?? ""} onChange={(e) => setNewItem({ ...newItem, hsnCode: e.target.value })} />
                  </div>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="ph-rx">Rx</label>
                    <select id="ph-rx" className={styles.select} value={newItem.isRx ? "yes" : "no"} onChange={(e) => setNewItem({ ...newItem, isRx: e.target.value === "yes" })}>
                      <option value="no">Non-Rx</option>
                      <option value="yes">Rx</option>
                    </select>
                  </div>
                </div>
                <div className={styles.actions}>
                  <button className={styles.btnPrimary} type="submit">Add item</button>
                </div>
              </form>
              <p className={styles.subtitle}>No batch, no expiry, no purchase orders in Hybrid — those are later-phase capabilities.</p>
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
                  <tr><th>Code</th><th>Name</th><th>Price</th><th>Tax %</th><th>Stock</th><th>Rx</th><th>HSN</th><th>Status</th>{canWrite && <th></th>}</tr>
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
                      <td><StatusPill tone={i.isRx ? "info" : "neutral"} label={i.isRx ? "Rx" : "Non-Rx"} icon={i.isRx ? "pulse" : "list"} /></td>
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

          <h2 className={styles.cardTitle} style={{ marginTop: "var(--nb-space-24)" }}>Dispensing queue</h2>
          <p className={styles.subtitle}>Signed prescriptions still ahead of checkout. Open a row to dispense on that patient&apos;s billing screen.</p>
          {queueError ? (
            <ErrorState message={queueError} onRetry={load} />
          ) : queue.length === 0 ? (
            <div className={styles.empty}>Nothing waiting to be dispensed.</div>
          ) : (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead><tr><th>Token</th><th>Patient</th><th>Doctor</th><th>Drugs</th><th>Signed at</th></tr></thead>
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
        </>
      )}

      {switchTo && <ModeChangeDialog from={mode} to={switchTo} authedFetch={authedFetch} onClose={closeSwitch} onSaved={() => void modeSaved()} />}

      {editing && (
        <Dialog title={`Edit ${editing.name}`} size="drawer" onClose={closeEdit}>
          <form onSubmit={saveEdit}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pe-name">Name</label>
              <input id="pe-name" className={styles.input} value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pe-price">Price</label>
              <input id="pe-price" className={styles.input} type="number" step="0.01" min="0" value={editing.price} onChange={(e) => setEditing({ ...editing, price: Number(e.target.value) })} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pe-tax">Tax rate %</label>
              <input id="pe-tax" className={styles.input} type="number" step="0.01" min="0" value={editing.taxRatePercent} onChange={(e) => setEditing({ ...editing, taxRatePercent: Number(e.target.value) })} />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pe-hsn">HSN code</label>
              <input id="pe-hsn" className={styles.input} value={editing.hsnCode ?? ""} onChange={(e) => setEditing({ ...editing, hsnCode: e.target.value })} />
            </div>
            <label className={ph.check}><input type="checkbox" checked={editing.isRx} onChange={(e) => setEditing({ ...editing, isRx: e.target.checked })} /> Prescription only (Rx)</label>
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
              <input id="pa-qty" className={styles.input} type="number" step="1" value={adjusting.qty} onChange={(e) => setAdjusting({ ...adjusting, qty: e.target.value, error: null })} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="pa-reason">Reason (required)</label>
              <input id="pa-reason" ref={reasonRef} className={styles.input} maxLength={500} placeholder="e.g. Supplier delivery, damaged, stock-take" value={adjusting.reason} onChange={(e) => setAdjusting({ ...adjusting, reason: e.target.value, error: null })} required />
            </div>
            {adjusting.error && <div className={`${ph.notice} ${ph.noticeError}`} role="alert"><Icon name="alert" size={14} /> {adjusting.error}</div>}
            <div className={styles.actions}>
              <button type="button" className={styles.btn} onClick={closeAdjust}>Cancel</button>
              <button type="submit" className={styles.btnPrimary} disabled={adjustBusy}>{adjustBusy ? "Saving…" : "Record change"}</button>
            </div>
          </form>
        </Dialog>
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
