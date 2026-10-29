"use client";

import { useState } from "react";
import styles from "../../(clinic)/checkout/checkout.module.css";
import { api, errorText } from "../../lib/api";
import type { RxLine } from "@nabd/api-client/types";

// PHL-001: counter "Link prescription" picker. Finds the patient's signed lines with quantity left by phone
// (POST, so the number is never in a URL). Rx QR lookup waits on a QR format; outside prescriptions are PHL-020.
export default function RxLinkPicker({ itemName, onPick, onCancel }: { itemName: string; onPick: (line: RxLine) => void; onCancel: () => void }) {
  const [phone, setPhone] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lines, setLines] = useState<RxLine[] | null>(null);

  async function search(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (phone.replace(/\D/g, "").length < 6) {
      setError("Enter at least 6 digits of the patient's phone number.");
      return;
    }
    setLoading(true);
    try {
      setLines(await api.post<RxLine[]>("/pharmacy/rx-lines/search", { phone }));
    } catch (err) {
      setLines(null);
      setError(errorText(err, "Couldn't search prescriptions. Try again."));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className={styles.rxPicker} role="dialog" aria-label={`Link prescription for ${itemName}`}>
      <div className={styles.cardTitle}>Link prescription · {itemName}</div>
      <form onSubmit={search} className={styles.rxPickerRow}>
        <input aria-label="Patient phone" className={styles.rxPickerInput} inputMode="tel" placeholder="Patient phone"
          value={phone} onChange={(e) => setPhone(e.target.value)} />
        <button type="submit" className={styles.rxPickerBtn} disabled={loading}>{loading ? "Searching…" : "Search"}</button>
        <button type="button" className={styles.rxPickerBtn} onClick={onCancel}>Cancel</button>
      </form>
      {error && <div className={styles.errorState} role="alert">{error}</div>}
      {lines && lines.length === 0 && <div className={styles.muted}>No signed prescription with quantity left for this number.</div>}
      {lines && lines.map((l) => (
        <button key={l.prescriptionItemId} type="button" className={styles.chargeItem} onClick={() => onPick(l)}>
          <span className={styles.chargeName}>{l.drugName}{l.dosage ? ` · ${l.dosage}` : ""}</span>
          <span className={styles.muted}>{l.patientName} · {l.remainingQty} left</span>
        </button>
      ))}
    </div>
  );
}
