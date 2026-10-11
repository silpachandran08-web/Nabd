"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "../../setup/setup.module.css";
import ph from "./pharmacy.module.css";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import Dialog from "../ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";

// Fed only by /pharmacy/* so a pharmacy-only user never touches /setup/*, and a Setup user without
// pharmacy:view never sees this tab's 403 as a page-level error (PHA-002). It owns its own loading.
export type PharmacyMode = "external" | "hybrid" | "in_house";
type PharmacySettings = { mode: PharmacyMode };
type PharmacyItem = { id: string; code: string; name: string; isRx: boolean; hsnCode: string | null; price: number; taxRatePercent: number; stockQty: number; active: boolean };
type DispensingQueueEntry = {
  prescriptionId: string; queueEntryId: string; patientName: string; doctorName: string; signedAt: string;
  items: { drugName: string; dosage: string | null; frequency: string | null; duration: string | null; instructions: string | null }[];
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
const LOW_STOCK = 8;

async function problemText(res: Response): Promise<string> {
  const p = await res.json().catch(() => null);
  return p?.detail || "Request failed";
}

function Mark({ on }: { on: boolean }) {
  return on
    ? <span className={ph.yes}><Icon name="check" size={14} label="Included" /></span>
    : <span className={ph.no}><Icon name="x" size={14} label="Not included" /></span>;
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

  const changeMode = async (mode: PharmacyMode) => {
    if (mode === settings?.mode) return;
    setNotice(null);
    if (await send("PATCH", "/pharmacy/settings", { mode })) {
      setNotice({ text: `Pharmacy mode saved: ${MODE_LABEL[mode]}.` });
      await load();
    }
  };

  // PATCH replaces the whole item, so every field comes from the loaded row. The API's item body has
  // no code or active field (codes are generated; there is no deactivate), so neither is sent.
  const itemBody = (i: PharmacyItem, over: Partial<PharmacyItem>) => {
    const m = { ...i, ...over };
    return { name: m.name, isRx: m.isRx, hsnCode: m.hsnCode || null, price: m.price, taxRatePercent: m.taxRatePercent, stockQty: m.stockQty };
  };

  const stepStock = async (i: PharmacyItem, delta: number) => {
    const stockQty = Math.max(0, i.stockQty + delta);
    if (stockQty === i.stockQty) return;
    setBusyId(i.id);
    setNotice(null);
    // Read-modify-write on the loaded row: reload afterwards so a concurrent sale is never hidden.
    if (await send("PATCH", `/pharmacy/items/${i.id}`, itemBody(i, { stockQty }))) await load();
    setBusyId(null);
  };

  const saveEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editing) return;
    if (!editing.name.trim() || !(editing.price >= 0)) return;
    if (await send("PATCH", `/pharmacy/items/${editing.id}`, itemBody(editing, {}))) {
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
      <div className={styles.tabs} role="group" aria-label="Pharmacy mode">
        {(["external", "hybrid", "in_house"] as const).filter((m) => canEdit || m === mode).map((m) => (
          <button key={m} type="button" aria-pressed={mode === m} className={mode === m ? styles.tabActive : styles.tab} onClick={() => changeMode(m)}>
            {MODE_LABEL[m]}
          </button>
        ))}
      </div>
      <p className={ph.modeDesc}>{MODE_DESC[mode]}</p>
      {notice && (
        <div className={`${ph.notice} ${notice.error ? ph.noticeError : ""}`} role={notice.error ? "alert" : "status"}>
          <Icon name={notice.error ? "alert" : "check"} size={14} /> {notice.text}
        </div>
      )}
      <HardRuleCard />

      {mode === "external" ? (
        <p className={styles.subtitle}>Item master and dispensing queue are not shown in External mode. Switch to Hybrid to add items.</p>
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
                          {canWrite && <button type="button" className={ph.stepBtn} aria-label={`Decrease stock of ${i.name}`} disabled={busyId === i.id || i.stockQty <= 0} onClick={() => stepStock(i, -1)}>−</button>}
                          <span className={ph.qty}>{i.stockQty}</span>
                          {canWrite && <button type="button" className={ph.stepBtn} aria-label={`Increase stock of ${i.name}`} disabled={busyId === i.id} onClick={() => stepStock(i, 1)}>+</button>}
                          {i.stockQty === 0 ? <StatusPill tone="danger" label="Out of stock" /> : i.stockQty <= LOW_STOCK ? <StatusPill tone="warn" label="Low" /> : null}
                        </div>
                      </td>
                      <td><StatusPill tone={i.isRx ? "info" : "neutral"} label={i.isRx ? "Rx" : "Non-Rx"} icon={i.isRx ? "pulse" : "list"} /></td>
                      <td>{i.hsnCode ?? "—"}</td>
                      <td><StatusPill tone={i.active ? "ok" : "neutral"} label={i.active ? "Active" : "Inactive"} icon={i.active ? "check" : "x"} /></td>
                      {canWrite && (
                        <td>
                          <button type="button" className={styles.smallBtn} onClick={() => setEditing(i)}>Edit</button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <h2 className={styles.cardTitle} style={{ marginTop: "var(--nb-space-24)" }}>Dispensing queue</h2>
          <p className={styles.subtitle}>Signed prescriptions still ahead of checkout. This list is read-only; dispensing happens on that patient&apos;s billing screen.</p>
          {queueError ? (
            <ErrorState message={queueError} onRetry={load} />
          ) : queue.length === 0 ? (
            <div className={styles.empty}>Nothing waiting to be dispensed.</div>
          ) : (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead><tr><th>Patient</th><th>Doctor</th><th>Drugs</th><th>Signed at</th></tr></thead>
                <tbody>
                  {queue.map((q) => (
                    <tr key={q.prescriptionId}>
                      <td>{q.patientName}</td>
                      <td>{q.doctorName}</td>
                      <td>
                        {q.items.map((d, n) => {
                          const sub = [d.dosage, d.frequency, d.duration].filter(Boolean).join(" · ");
                          return (
                            <div key={n} style={{ marginBottom: n < q.items.length - 1 ? "var(--nb-space-8)" : 0 }}>
                              <div>{d.drugName}</div>
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

      {editing && (
        <Dialog title={`Edit ${editing.name}`} size="drawer" onClose={() => setEditing(null)}>
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
              <button type="button" className={styles.btn} onClick={() => setEditing(null)}>Cancel</button>
              <button type="submit" className={styles.btnPrimary}>Save item</button>
            </div>
          </form>
        </Dialog>
      )}
    </div>
  );
}
