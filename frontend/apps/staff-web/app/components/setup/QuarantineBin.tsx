"use client";

import { useCallback, useEffect, useState } from "react";
import { PrintHeader, PrintSheet } from "@nabd/ui";
import { formatNumber } from "@nabd/i18n";
import styles from "../../(clinic)/setup/setup.module.css";
import ph from "./pharmacy.module.css";
import sa from "./stockAlerts.module.css";
import Dialog from "../ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import ApproverStep, { type Approval } from "./ApproverStep";
import { api, errorText } from "../../lib/api";
import { can } from "../../lib/permissions";
import { getIdentity } from "../../lib/session";
import { usePrintPaper } from "../../lib/usePrintPaper";
import {
  QUARANTINE_REASONS, WRITE_OFF_METHODS, linesValue, moveLines, needsWitness, supplierIdOf, supplierProblem,
  type MoveLine, type QuarantineReason, type QuarantinedBatch, type WriteOffMethod,
} from "../../lib/quarantine";

type Bin = { items: QuarantinedBatch[]; totalValue: number };
type Valuation = { asOf: string; saleableValue: number; quarantinedValue: number; totalValue: number };
type NoteLine = { batchId: string; itemName: string; batchNo: string; expiryDate?: string | null; qty: number; rate: number; value: number };
type ReturnNote = { returnId: string; debitNoteNumber: string; supplierName: string; value: number; reason?: string | null; createdByName?: string | null; createdAt: string; lines: NoteLine[] };
type StockItem = { itemId: string; name: string };
type Batch = { batchId: string; batchNo?: string | null; expiryDate?: string | null; qtyOnHand: number; status: string };
type Move = { chosen: QuarantinedBatch[]; lines: MoveLine[] };

const FIX2 = { min: 2, max: 2 };
const money = (n: number) => formatNumber(n, FIX2);

/** PHL-009: the quarantine bin. Expired batches arrive here by the nightly pass, damaged ones by hand; from here stock goes
 * back to the supplier (a printable return note) or is written off with a record. Return and quarantine need pharmacy:edit,
 * a write-off needs pharmacy:adjust_approve as well; without them the controls are absent. */
export default function QuarantineBin() {
  const canEdit = can("pharmacy:edit");
  const canWriteOff = canEdit && can("pharmacy:adjust_approve");
  const [bin, setBin] = useState<Bin | null>(null);
  const [valuation, setValuation] = useState<Valuation | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [qty, setQty] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [returning, setReturning] = useState<Move | null>(null);
  const [writingOff, setWritingOff] = useState<Move | null>(null);
  const [quarantining, setQuarantining] = useState(false);
  const [note, setNote] = useState<ReturnNote | null>(null);

  // a reload after an action keeps the current rows on screen (no skeleton swap)
  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const [b, v] = await Promise.all([api.get<Bin>("/pharmacy/quarantine"), api.get<Valuation>("/pharmacy/reports/valuation")]);
      setBin(b); setValuation(v);
      setSelected((s) => s.filter((id) => b.items.some((i) => i.batchId === id)));
    } catch (e) { setLoadError(errorText(e, "Couldn't load the quarantine bin.")); }
  }, []);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  const items = bin?.items ?? [];
  const toggle = (id: string) => { setProblem(null); setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id])); };

  const start = (kind: "return" | "writeOff") => {
    const chosen = items.filter((i) => selected.includes(i.batchId));
    const { lines, problem: p } = moveLines(chosen, qty);
    const stop = p ?? (kind === "return" ? supplierProblem(chosen) : null);
    setNotice(null); setProblem(stop);
    if (stop) return;
    if (kind === "return") setReturning({ chosen, lines }); else setWritingOff({ chosen, lines });
  };
  const done = (text: string) => { setReturning(null); setWritingOff(null); setQuarantining(false); setQty({}); setNotice(text); void load(); };

  return (
    <section className={sa.section} aria-labelledby="qb-title">
      <h2 id="qb-title" className={styles.cardTitle}>Quarantine</h2>
      {valuation && (
        <p className={styles.subtitle} data-testid="qb-valuation">
          Stock value at purchase rate: saleable {money(valuation.saleableValue)}, quarantined {money(valuation.quarantinedValue)}.
        </p>
      )}
      {notice && <div className={ph.notice} role="status">{notice}</div>}
      {problem && <div className={`${ph.notice} ${ph.noticeError}`} role="alert">{problem}</div>}
      {canEdit && (
        <div className={sa.bar}>
          <button type="button" className={`${styles.btn} ${sa.target}`} onClick={() => { setProblem(null); setNotice(null); setQuarantining(true); }}>Quarantine a batch</button>
          {items.length > 0 && <button type="button" className={`${styles.btnPrimary} ${sa.target}`} onClick={() => start("return")}>Return to supplier</button>}
          {canWriteOff && items.length > 0 && <button type="button" className={`${styles.btn} ${sa.target}`} onClick={() => start("writeOff")}>Write off</button>}
        </div>
      )}
      {loadError && !bin ? <ErrorState message={loadError} onRetry={load} />
        : !bin ? <Skeleton rows={3} label="Loading the quarantine bin" />
        : items.length === 0 ? <EmptyState icon="package" title="Nothing is in quarantine." hint="Expired batches are moved here every night; damaged stock can be quarantined by hand." />
        : (
          <>
            {loadError && <div className={`${ph.notice} ${ph.noticeError}`} role="alert">{loadError}</div>}
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    {canEdit && <th scope="col"><span className="srOnly">Select</span></th>}
                    <th scope="col">Item</th><th scope="col">Batch</th><th scope="col">Expiry</th>
                    <th scope="col" className={sa.num}>In quarantine</th><th scope="col" className={sa.num}>Purchase rate</th><th scope="col" className={sa.num}>Value</th>
                    {canEdit && <th scope="col">Quantity to move</th>}
                  </tr>
                </thead>
                <tbody>
                  {items.map((b) => (
                    <tr key={b.batchId}>
                      {canEdit && <td><input className={sa.target} type="checkbox" aria-label={`Select ${b.itemName} ${b.batchNo}`} checked={selected.includes(b.batchId)} onChange={() => toggle(b.batchId)} /></td>}
                      <td>{b.itemName}{b.controlled && <div className={ph.sub}>Controlled: write-off needs a second staff member</div>}</td>
                      <td>{b.batchNo}</td>
                      <td>{b.expiryDate ?? ""}</td>
                      <td className={sa.num}>{formatNumber(b.qtyOnHand)}</td>
                      <td className={sa.num}>{b.purchaseRate == null ? "" : money(b.purchaseRate)}</td>
                      <td className={sa.num}>{money(b.value)}</td>
                      {canEdit && (
                        <td>
                          <input className={`${styles.input} ${sa.target}`} inputMode="numeric" aria-label={`Quantity of ${b.itemName} ${b.batchNo} to move`} placeholder={String(b.qtyOnHand)}
                            value={qty[b.batchId] ?? ""} onChange={(e) => { setProblem(null); setQty({ ...qty, [b.batchId]: e.target.value }); }} />
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      {returning && <ReturnDialog move={returning} onClose={() => setReturning(null)} onDone={(n) => { setNote(n); done(`Return note ${n.debitNoteNumber} created.`); }} />}
      {writingOff && <WriteOffDialog move={writingOff} onClose={() => setWritingOff(null)} onDone={done} />}
      {quarantining && <QuarantineDialog onClose={() => setQuarantining(false)} onDone={done} />}
      {note && <ReturnNoteDialog note={note} onClose={() => setNote(null)} />}
    </section>
  );
}

function ReturnDialog({ move, onClose, onDone }: { move: Move; onClose: () => void; onDone: (note: ReturnNote) => void }) {
  const [supplierName, setSupplierName] = useState("");
  const [reason, setReason] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (supplierName.trim() === "") { setProblem("Enter the supplier's name."); return; }
    setBusy(true); setProblem(null);
    try {
      onDone(await api.post<ReturnNote>("/pharmacy/supplier-returns", {
        supplierId: supplierIdOf(move.chosen), supplierName: supplierName.trim(), reason: reason.trim() || undefined, lines: move.lines,
      }));
    } catch (err) { setProblem(errorText(err, "Couldn't create the return note. Nothing was changed.")); } finally { setBusy(false); }
  };

  return (
    <Dialog title="Return to supplier" size="drawer" onClose={onClose}>
      <form onSubmit={submit}>
        <p className={styles.subtitle}>
          {move.lines.length} {move.lines.length === 1 ? "batch" : "batches"}, value {money(linesValue(move.chosen, move.lines))} at purchase rate. The stock leaves quarantine and a numbered return note is created.
        </p>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="qr-supplier">Supplier</label>
          <input id="qr-supplier" className={styles.input} maxLength={200} value={supplierName} onChange={(e) => setSupplierName(e.target.value)} required />
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="qr-reason">Reason (optional)</label>
          <input id="qr-reason" className={styles.input} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
        {problem && <div className={`${ph.notice} ${ph.noticeError}`} role="alert">{problem}</div>}
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.btnPrimary} disabled={busy}>{busy ? "Saving…" : "Create return note"}</button>
        </div>
      </form>
    </Dialog>
  );
}

function WriteOffDialog({ move, onClose, onDone }: { move: Move; onClose: () => void; onDone: (text: string) => void }) {
  const { chosen } = move;
  const [method, setMethod] = useState<WriteOffMethod | "">("");
  const [noteText, setNoteText] = useState("");
  const [approval, setApproval] = useState<Approval>({ approverStaffId: "", approverPin: "" });
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (!method) { setProblem("Choose how the stock was written off."); return; }
    if (needsWitness(chosen) && (!approval.approverStaffId || !approval.approverPin)) { setProblem("The second staff member must choose their name and enter their PIN."); return; }
    setBusy(true); setProblem(null);
    try {
      await api.post("/pharmacy/write-offs", {
        method, note: noteText.trim() || undefined, lines: move.lines,
        ...(needsWitness(chosen) ? { witnessStaffId: approval.approverStaffId, witnessPin: approval.approverPin } : {}),
      });
      onDone(`${move.lines.length} ${move.lines.length === 1 ? "batch" : "batches"} written off.`);
    } catch (err) {
      setProblem(errorText(err, "Couldn't write the stock off. Nothing was changed."));
      setApproval({ ...approval, approverPin: "" });
    } finally { setBusy(false); }
  };

  return (
    <Dialog title="Write off" size="drawer" onClose={onClose}>
      <form onSubmit={submit}>
        <p className={styles.subtitle}>
          {move.lines.length} {move.lines.length === 1 ? "batch" : "batches"}, value {money(linesValue(chosen, move.lines))} at purchase rate. Every write-off is a permanent record with who, when and how.
        </p>
        <div className={ph.formRow}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="qw-method">Method</label>
            <select id="qw-method" className={styles.select} value={method} onChange={(e) => setMethod(e.target.value as WriteOffMethod | "")}>
              <option value="">Choose…</option>
              {WRITE_OFF_METHODS.map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}
            </select>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="qw-note">Note (optional)</label>
            <input id="qw-note" className={styles.input} maxLength={500} value={noteText} onChange={(e) => setNoteText(e.target.value)} />
          </div>
        </div>
        {needsWitness(chosen) && <ApproverStep idPrefix="qw" value={approval} onChange={setApproval} />}
        {problem && <div className={`${ph.notice} ${ph.noticeError}`} role="alert">{problem}</div>}
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.btnPrimary} disabled={busy}>{busy ? "Saving…" : "Write off"}</button>
        </div>
      </form>
    </Dialog>
  );
}

/** Pick an item by name, then one of its batches on sale: no ids are typed. */
function QuarantineDialog({ onClose, onDone }: { onClose: () => void; onDone: (text: string) => void }) {
  const [q, setQ] = useState("");
  const [found, setFound] = useState<StockItem[] | null>(null);
  const [itemId, setItemId] = useState("");
  const [batches, setBatches] = useState<Batch[] | null>(null);
  const [batchId, setBatchId] = useState("");
  const [reason, setReason] = useState<QuarantineReason | "">("");
  const [noteText, setNoteText] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const search = async () => {
    if (busy || q.trim() === "") return;
    setBusy(true); setProblem(null); setItemId(""); setBatches(null); setBatchId("");
    try { setFound((await api.get<{ items: StockItem[] }>(`/pharmacy/stock?limit=20&q=${encodeURIComponent(q.trim())}`)).items); }
    catch (e) { setProblem(errorText(e, "Couldn't search the items.")); } finally { setBusy(false); }
  };
  const pickItem = async (id: string) => {
    setItemId(id); setBatches(null); setBatchId(""); setProblem(null);
    if (!id) return;
    try { setBatches((await api.get<Batch[]>(`/pharmacy/items/${id}/batches`)).filter((b) => b.status === "active")); }
    catch (e) { setProblem(errorText(e, "Couldn't load the batches.")); }
  };
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (!batchId) { setProblem("Search for the item and choose the batch."); return; }
    if (!reason) { setProblem("Choose a reason."); return; }
    if (reason === "other" && noteText.trim() === "") { setProblem("Reason \"Other\" needs a note."); return; }
    setBusy(true); setProblem(null);
    try {
      await api.post(`/pharmacy/batches/${batchId}/quarantine`, { reason, note: noteText.trim() || undefined });
      onDone("Batch moved to quarantine. It can no longer be sold or dispensed.");
    } catch (err) { setProblem(errorText(err, "Couldn't quarantine the batch. Nothing was changed.")); } finally { setBusy(false); }
  };

  return (
    <Dialog title="Quarantine a batch" size="drawer" onClose={onClose}>
      <form onSubmit={submit}>
        <p className={styles.subtitle}>A quarantined batch is blocked at billing and dispensing until it is returned or written off.</p>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="qq-search">Item name</label>
          <div className={ph.formRow}>
            <input id="qq-search" className={styles.input} value={q} onChange={(e) => setQ(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); void search(); } }} />
            <button type="button" className={`${styles.btn} ${sa.target}`} disabled={busy} onClick={() => void search()}>Search</button>
          </div>
        </div>
        {found && (found.length === 0 ? <p className={ph.sub} role="status">No item matches that name.</p> : (
          <div className={styles.field}>
            <label className={styles.label} htmlFor="qq-item">Item</label>
            <select id="qq-item" className={styles.select} value={itemId} onChange={(e) => void pickItem(e.target.value)}>
              <option value="">Choose…</option>
              {found.map((i) => <option key={i.itemId} value={i.itemId}>{i.name}</option>)}
            </select>
          </div>
        ))}
        {batches && (batches.length === 0 ? <p className={ph.sub} role="status">This item has no batch on sale.</p> : (
          <div className={styles.field}>
            <label className={styles.label} htmlFor="qq-batch">Batch</label>
            <select id="qq-batch" className={styles.select} value={batchId} onChange={(e) => setBatchId(e.target.value)}>
              <option value="">Choose…</option>
              {batches.map((b) => <option key={b.batchId} value={b.batchId}>{[b.batchNo, b.expiryDate].filter(Boolean).join(" · ") || "Stock"} — {b.qtyOnHand} on hand</option>)}
            </select>
          </div>
        ))}
        <div className={ph.formRow}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="qq-reason">Reason</label>
            <select id="qq-reason" className={styles.select} value={reason} onChange={(e) => setReason(e.target.value as QuarantineReason | "")}>
              <option value="">Choose…</option>
              {QUARANTINE_REASONS.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}
            </select>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="qq-note">Note{reason === "other" ? " (required)" : " (optional)"}</label>
            <input id="qq-note" className={styles.input} maxLength={500} value={noteText} onChange={(e) => setNoteText(e.target.value)} />
          </div>
        </div>
        {problem && <div className={`${ph.notice} ${ph.noticeError}`} role="alert">{problem}</div>}
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.btnPrimary} disabled={busy}>{busy ? "Working…" : "Move to quarantine"}</button>
        </div>
      </form>
    </Dialog>
  );
}

/** The return note on paper: batch, quantity, purchase rate and value per line (print kit: only the sheet prints). */
function ReturnNoteDialog({ note, onClose }: { note: ReturnNote; onClose: () => void }) {
  const paper = usePrintPaper("document");
  return (
    <Dialog title={`Return note ${note.debitNoteNumber}`} size="lg" onClose={onClose}>
      <PrintSheet paper={paper} label="Supplier return note">
        <PrintHeader clinic={getIdentity()?.tenantName ?? ""} />
        <p><strong>Supplier return note (debit note) {note.debitNoteNumber}</strong></p>
        <p>Date: {note.createdAt.slice(0, 10)}<br />Supplier: <bdi>{note.supplierName}</bdi>{note.reason ? <><br />Reason: {note.reason}</> : null}</p>
        <table className="nbp-totals">
          <thead>
            <tr><th scope="col">Item</th><th scope="col">Batch</th><th scope="col">Expiry</th><th scope="col">Qty</th><th scope="col">Rate</th><th scope="col">Value</th></tr>
          </thead>
          <tbody>
            {note.lines.map((l) => (
              <tr key={l.batchId}><td>{l.itemName}</td><td>{l.batchNo}</td><td>{l.expiryDate ?? ""}</td><td>{formatNumber(l.qty)}</td><td>{money(l.rate)}</td><td>{money(l.value)}</td></tr>
            ))}
            <tr className="nbp-strong"><td colSpan={5}>Total</td><td>{money(note.value)}</td></tr>
          </tbody>
        </table>
        {note.createdByName && <p className="nbp-foot">Returned by: {note.createdByName}</p>}
      </PrintSheet>
      <div className={styles.actions}>
        <button type="button" className={styles.btn} onClick={onClose}>Close</button>
        <button type="button" className={styles.btnPrimary} onClick={() => window.print()}>Print</button>
      </div>
    </Dialog>
  );
}
