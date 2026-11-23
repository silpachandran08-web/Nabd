"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import styles from "../../(clinic)/checkout/checkout.module.css";
import { ACK_MAX, ackReason } from "./scheduleAck";

// PHL-023: the second confirmation of a flagged schedule class (Schedule H1). The seller gives a reason; the bill line carries it
// (scheduleAckReason) and the server audits it with the sale. `initial` is the seller's last reason, selected on focus, so a
// repeat sale is one Enter. Needs no permission of its own: whoever may sell the line may confirm it.
export default function ScheduleAckDialog({ itemName, initial = "", onConfirm, onCancel }: {
  itemName: string;
  initial?: string;
  onConfirm: (reason: string) => void;
  onCancel?: () => void;
}) {
  const t = useTranslations("checkout.scheduleAck");
  const [reason, setReason] = useState(initial);
  const [bad, setBad] = useState(false);

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const r = ackReason(reason);
    if (r) onConfirm(r);
    else setBad(true);
  }

  return (
    <form className={styles.rxPicker} role="dialog" aria-label={t("dialog", { item: itemName })} onSubmit={submit}>
      <div className={styles.cardTitle}>{t("title", { item: itemName })}</div>
      <p className={styles.muted}>{t("body")}</p>
      <div className={styles.rxPickerRow}>
        <input autoFocus aria-label={t("reason")} placeholder={t("reason")} className={styles.rxPickerInput} maxLength={ACK_MAX}
          value={reason} onFocus={(e) => e.target.select()} onChange={(e) => { setReason(e.target.value); setBad(false); }} />
        <button type="submit" className={styles.rxPickerBtn}>{t("confirm")}</button>
        {onCancel && <button type="button" className={styles.rxPickerBtn} onClick={onCancel}>{t("cancel")}</button>}
      </div>
      {bad && <div className={styles.errorState} role="alert">{t("needReason")}</div>}
    </form>
  );
}
