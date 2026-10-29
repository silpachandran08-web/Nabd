"use client";

import styles from "../../(clinic)/checkout/checkout.module.css";
import { PAYMENT_METHODS, paymentMethodLabel } from "@nabd/domain";
import { tenderTotalMinor, type Tender } from "./tender";

// Split tender: one row per method. Presentational; the page owns the rows and the collecting.
export default function TenderRows({ rows, onChange, region, balanceMinor, disabled }: {
  rows: Tender[]; onChange: (rows: Tender[]) => void; region?: string | null; balanceMinor: number; disabled: boolean;
}) {
  const set = (id: number, patch: Partial<Tender>) => onChange(rows.map((r) => (r.id === id ? { ...r, ...patch } : r)));
  const rest = (id: number) => balanceMinor - tenderTotalMinor(rows.filter((r) => r.id !== id)); // what is left for this row
  const nextId = rows.reduce((m, r) => Math.max(m, r.id), 0) + 1;
  return (
    <div className={styles.tenders}>
      <h2 className={styles.cardTitle}>Payment</h2>
      {rows.map((r, i) => (
        <div key={r.id} className={styles.tenderRow}>
          <select className={styles.tenderSelect} aria-label={`Payment method ${i + 1}`} value={r.method} disabled={disabled}
            onChange={(e) => set(r.id, { method: e.target.value })}>
            {PAYMENT_METHODS.map((m) => <option key={m} value={m}>{paymentMethodLabel(m, region)}</option>)}
          </select>
          <input className={styles.tenderAmount} type="text" inputMode="decimal" placeholder="Amount" aria-label={`Amount ${i + 1}`}
            value={r.amount} disabled={disabled} onChange={(e) => set(r.id, { amount: e.target.value })} />
          <button type="button" className={styles.tenderFill} disabled={disabled || rest(r.id) <= 0}
            onClick={() => set(r.id, { amount: (rest(r.id) / 100).toFixed(2) })}>Rest</button>
          {rows.length > 1 && (
            <button type="button" className={styles.tenderRemove} aria-label={`Remove payment ${i + 1}`} disabled={disabled}
              onClick={() => onChange(rows.filter((x) => x.id !== r.id))}>×</button>
          )}
        </div>
      ))}
      <button type="button" className={styles.backBtn} disabled={disabled} onClick={() => onChange([...rows, { id: nextId, method: "card", amount: "" }])}>
        + Split payment
      </button>
    </div>
  );
}
