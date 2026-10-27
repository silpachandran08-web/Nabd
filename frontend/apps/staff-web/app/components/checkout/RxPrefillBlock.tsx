"use client";

import { useState } from "react";
import styles from "../../(clinic)/checkout/checkout.module.css";
import StatusPill from "../ui/StatusPill";
import { isShort, reasonOk, REASON_MIN, type PrefillLine } from "./rxPrefill";

/**
 * PHL-018: the visit's in-house Rx items above the charge list, ticked and ready (one invoice with the consultation). A line the shelf
 * cannot cover is not ticked: "Dispense anyway" asks for a reason, the bill then completes and the missing units go to the next stock count.
 */
export default function RxPrefillBlock({ lines, ticked, reasons, onToggle, onReason }: {
  lines: PrefillLine[]; ticked: Set<string>; reasons: Record<string, string>;
  onToggle: (l: PrefillLine, on: boolean) => void; onReason: (l: PrefillLine, reason: string | null) => void;
}) {
  const [asking, setAsking] = useState<string | null>(null);
  const [text, setText] = useState("");
  if (lines.length === 0) return null;
  return (
    <section className={styles.card} data-testid="rx-prefill" aria-label="Prescription items to dispense">
      <h2 className={styles.cardTitle}>Prescription · dispense here</h2>
      {lines.map((l) => {
        const short = isShort(l);
        const reason = reasons[l.chargeCode];
        const id = `rxp-${l.chargeCode}`;
        return (
          <div key={l.chargeCode} className={styles.chargeItem} style={{ cursor: "default", display: "block" }}>
            <label htmlFor={id} style={{ display: "flex", gap: "var(--nb-space-8, 8px)", alignItems: "center", minBlockSize: 44 }}>
              <input id={id} type="checkbox" checked={ticked.has(l.chargeCode)} disabled={short && !reason}
                onChange={(e) => onToggle(l, e.target.checked)} />
              <span className={styles.chargeName}>{l.chargeName} × {l.qty}</span>
              <StatusPill tone={l.onHand === 0 ? "danger" : short ? "warn" : "ok"} label={`On hand ${l.onHand}`} />
            </label>
            {short && !reason && asking !== l.chargeCode && (
              <div>
                <span className={styles.muted}>Not enough stock ({l.onHand} on hand for {l.qty}). </span>
                <button type="button" className={styles.removeBtn} onClick={() => { setAsking(l.chargeCode); setText(""); }}>Dispense anyway…</button>
              </div>
            )}
            {short && !reason && asking === l.chargeCode && (
              <div>
                <label htmlFor={`${id}-why`} className={styles.muted}>Why is the shelf short? (recorded for the next stock count)</label>
                <input id={`${id}-why`} className={styles.discountInput} autoFocus maxLength={200} value={text} onChange={(e) => setText(e.target.value)} />
                <button type="button" className={styles.removeBtn} disabled={!reasonOk(text)} onClick={() => { onReason(l, text.trim()); setAsking(null); }}>Dispense anyway</button>
                <button type="button" className={styles.removeBtn} onClick={() => setAsking(null)}>Cancel</button>
                {!reasonOk(text) && text.length > 0 && <div className={styles.muted}>At least {REASON_MIN} characters.</div>}
              </div>
            )}
            {short && reason && (
              <div className={styles.muted}>Dispensing anyway: {reason}{" "}
                <button type="button" className={styles.removeBtn} onClick={() => onReason(l, null)}>Undo</button>
              </div>
            )}
          </div>
        );
      })}
    </section>
  );
}
