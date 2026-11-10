"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
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
  const t = useTranslations("checkout.rxPrefill");
  const [asking, setAsking] = useState<string | null>(null);
  const [text, setText] = useState("");
  if (lines.length === 0) return null;
  return (
    <section className={styles.card} data-testid="rx-prefill" aria-label={t("region")}>
      <h2 className={styles.cardTitle}>{t("title")}</h2>
      {lines.map((l) => {
        const short = isShort(l);
        const reason = reasons[l.chargeCode];
        const id = `rxp-${l.chargeCode}`;
        return (
          <div key={l.chargeCode} className={styles.chargeItem} style={{ cursor: "default", display: "block" }}>
            <label htmlFor={id} style={{ display: "flex", gap: "var(--nb-space-8, 8px)", alignItems: "center", minBlockSize: 44 }}>
              <input id={id} type="checkbox" checked={ticked.has(l.chargeCode)} disabled={short && !reason}
                onChange={(e) => onToggle(l, e.target.checked)} />
              <span className={styles.chargeName}>{t("lineQty", { name: l.chargeName, qty: l.qty })}</span>
              <StatusPill tone={l.onHand === 0 ? "danger" : short ? "warn" : "ok"} label={t("onHand", { count: l.onHand })} />
            </label>
            {short && !reason && asking !== l.chargeCode && (
              <div>
                <span className={styles.muted}>{t("notEnough", { onHand: l.onHand, qty: l.qty })}</span>
                <button type="button" className={styles.removeBtn} onClick={() => { setAsking(l.chargeCode); setText(""); }}>{t("dispenseAnywayAsk")}</button>
              </div>
            )}
            {short && !reason && asking === l.chargeCode && (
              <div>
                <label htmlFor={`${id}-why`} className={styles.muted}>{t("why")}</label>
                <input id={`${id}-why`} className={styles.discountInput} autoFocus maxLength={200} value={text} onChange={(e) => setText(e.target.value)} />
                <button type="button" className={styles.removeBtn} disabled={!reasonOk(text)} onClick={() => { onReason(l, text.trim()); setAsking(null); }}>{t("dispenseAnyway")}</button>
                <button type="button" className={styles.removeBtn} onClick={() => setAsking(null)}>{t("cancel")}</button>
                {!reasonOk(text) && text.length > 0 && <div className={styles.muted}>{t("minChars", { min: REASON_MIN })}</div>}
              </div>
            )}
            {short && reason && (
              <div className={styles.muted}>{t("dispensingAnyway", { reason })}{" "}
                <button type="button" className={styles.removeBtn} onClick={() => onReason(l, null)}>{t("undo")}</button>
              </div>
            )}
          </div>
        );
      })}
    </section>
  );
}
