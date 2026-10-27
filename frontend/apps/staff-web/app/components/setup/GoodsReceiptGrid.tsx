"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import styles from "../../(clinic)/setup/setup.module.css";
import ph from "./pharmacy.module.css";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { api, ApiError, errorText, intentKeys } from "../../lib/api";
import { clinicToday } from "../../lib/clinicTime";
import { useClinicTimeZone } from "../../lib/useClinicTimeZone";
import { formatNumber } from "@nabd/i18n";
import { billTotal, effectiveRate, emptyRow, isBlank, rowProblem, toBody, type ReceiptRow } from "../../lib/goodsReceipt";

const FIX2 = { min: 2, max: 2 };
type Item = { id: string; name: string; code: string; active: boolean };
type Posted = { receiptId: string; subtotal: number; batches: { batchNo: string; effectivePurchaseRate: number; unitsReceived: number; stockAfter: number }[] };

const COLS: { key: keyof ReceiptRow; label: string; mode?: "decimal" | "numeric"; ph?: string }[] = [
  { key: "batchNo", label: "Batch" }, { key: "expiry", label: "Expiry", ph: "MM/YY" }, { key: "mrp", label: "MRP", mode: "decimal" },
  { key: "rate", label: "Rate", mode: "decimal" }, { key: "qty", label: "Qty", mode: "numeric" }, { key: "freeQty", label: "Free", mode: "numeric" },
  { key: "taxRate", label: "Tax %", mode: "decimal" },
];

/** PHL-007: supplier bill entry. Tab runs across item / batch / expiry (MM/YY) / MRP / rate / qty / free / tax; Enter on the last cell adds a row. */
export default function GoodsReceiptGrid() {
  const tz = useClinicTimeZone();
  const [items, setItems] = useState<Item[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [supplier, setSupplier] = useState("");
  const [bill, setBill] = useState("");
  const [billDate, setBillDate] = useState("");
  const [rows, setRows] = useState<ReceiptRow[]>([emptyRow()]);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [posted, setPosted] = useState<Posted | null>(null);
  const keys = useRef(intentKeys());
  const lock = useRef(false);

  const load = useCallback(async () => {
    setLoadError(null);
    try { setItems((await api.get<Item[]>("/pharmacy/items")).filter((i) => i.active)); }
    catch (e) { setLoadError(errorText(e, "Couldn't load the item list.")); }
  }, []);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  const set = (i: number, k: keyof ReceiptRow, v: string) => {
    setProblem(null);
    setRows((prev) => {
      const next = prev.map((r, n) => (n === i ? { ...r, [k]: v } : r));
      return isBlank(next[next.length - 1]) ? next : [...next, emptyRow()]; // the grid always ends with one blank row
    });
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (lock.current) return;
    const today = clinicToday(tz);
    const filled = rows.filter((r) => !isBlank(r));
    if (supplier.trim() === "" || bill.trim() === "") { setProblem("Enter the supplier and the bill number."); return; }
    if (filled.length === 0) { setProblem("Add at least one line."); return; }
    for (let n = 0; n < rows.length; n++) {
      const p = isBlank(rows[n]) ? null : rowProblem(rows[n], today);
      if (p) { setProblem(`Line ${n + 1}: ${p}`); return; }
    }
    lock.current = true; setBusy(true); setProblem(null);
    try {
      const body = toBody(supplier, bill, billDate || today, rows);
      setPosted(await api.post<Posted>("/pharmacy/receipts", body, { idempotencyKey: keys.current.get("grn", body) }));
      keys.current.done("grn");
    } catch (err) {
      const dup = err instanceof ApiError && (err.code === "duplicate-supplier-bill" || String(err.problem?.type ?? "").endsWith("duplicate-supplier-bill"));
      setProblem(dup ? "This supplier bill was already posted. Check the bill number." : errorText(err, "Couldn't post the bill. Nothing was saved."));
    } finally { lock.current = false; setBusy(false); }
  };

  if (loadError) return <ErrorState message={loadError} onRetry={load} />;
  if (!items) return <Skeleton rows={4} label="Loading items" />;
  if (items.length === 0) return <EmptyState title="No pharmacy items yet" hint="Add the items you stock first, then enter supplier bills." icon="package" action={<Link href="/pharmacy">Go to Pharmacy</Link>} />;
  if (posted) {
    return (
      <div role="status" data-testid="grn-done">
        <p>Bill posted: {posted.batches.length} {posted.batches.length === 1 ? "line" : "lines"}, total {formatNumber(posted.subtotal, FIX2)}.</p>
        <button type="button" className={styles.btnPrimary} onClick={() => { setPosted(null); setRows([emptyRow()]); setBill(""); }}>Enter another bill</button>{" "}
        <Link href="/pharmacy">Back to Pharmacy</Link>
      </div>
    );
  }
  return (
    <form onSubmit={submit} aria-label="Supplier bill">
      <div className={ph.formRow}>
        <div className={styles.field}><label className={styles.label} htmlFor="grn-supplier">Supplier</label>
          <input id="grn-supplier" className={styles.input} maxLength={200} value={supplier} onChange={(e) => setSupplier(e.target.value)} /></div>
        <div className={styles.field}><label className={styles.label} htmlFor="grn-bill">Bill number</label>
          <input id="grn-bill" className={styles.input} maxLength={60} value={bill} onChange={(e) => setBill(e.target.value)} /></div>
        <div className={styles.field}><label className={styles.label} htmlFor="grn-date">Bill date</label>
          <input id="grn-date" className={styles.input} type="date" value={billDate || clinicToday(tz)} onChange={(e) => setBillDate(e.target.value)} /></div>
      </div>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead><tr><th scope="col">#</th><th scope="col">Item</th>{COLS.map((c) => <th key={c.key} scope="col">{c.label}</th>)}<th scope="col">Eff. rate</th><th scope="col">Total</th></tr></thead>
          <tbody>
            {rows.map((r, i) => {
              const q = Number(r.qty), f = Number(r.freeQty || 0), rt = Number(r.rate);
              const eff = q > 0 && Number.isFinite(rt) && r.rate !== "" ? formatNumber(effectiveRate(rt, q, Number.isFinite(f) ? f : 0), FIX2) : "";
              return (
                <tr key={i}>
                  <td>{i + 1}</td>
                  <td><select aria-label={`Line ${i + 1} item`} className={styles.select} value={r.itemId} onChange={(e) => set(i, "itemId", e.target.value)}>
                    <option value="">Item…</option>{items.map((it) => <option key={it.id} value={it.id}>{it.name}</option>)}</select></td>
                  {COLS.map((c) => (
                    <td key={c.key}><input aria-label={`Line ${i + 1} ${c.label}`} className={styles.input} style={{ minInlineSize: c.key === "batchNo" ? 96 : 64 }}
                      inputMode={c.mode} placeholder={c.ph} value={r[c.key]} onChange={(e) => set(i, c.key, e.target.value)}
                      onKeyDown={(e) => { if (e.key === "Enter" && c.key === "taxRate") { e.preventDefault(); (document.querySelector(`[aria-label="Line ${i + 2} Batch"]`) as HTMLElement | null)?.focus(); } }} /></td>
                  ))}
                  <td>{eff}</td>
                  <td>{isBlank(r) ? "" : formatNumber(Number(r.rate) * Number(r.qty) || 0, FIX2)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p aria-live="polite" data-testid="grn-total"><strong>Bill total {formatNumber(billTotal(rows), FIX2)}</strong> (before tax)</p>
      {problem && <div className={`${ph.notice} ${ph.noticeError}`} role="alert">{problem}</div>}
      <div className={styles.actions}>
        <Link href="/pharmacy" className={styles.btn}>Cancel</Link>
        <button type="submit" className={styles.btnPrimary} disabled={busy}>{busy ? "Posting…" : "Post bill"}</button>
      </div>
    </form>
  );
}
