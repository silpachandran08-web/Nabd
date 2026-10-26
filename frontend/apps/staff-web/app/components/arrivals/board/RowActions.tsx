import { useTranslations } from "next-intl";
import type { Grants, Row } from "../types";
import styles from "../arrivals.module.css";
import { bucketOf } from "./boardLogic";

export type RowHandlers = {
  onPriority: (id: string) => void; onSendForVitals: (row: Row) => void; onVitals: (id: string) => void; onCheckout: (id: string) => void;
};

// One contextual set of row actions, shared by the table and the detail rail. Each is absent (not disabled)
// when the role lacks the permission the endpoint needs. `after` lets the rail close itself first.
export default function RowActions({ row: r, grants, sendingId, h, after = (fn) => fn() }: {
  row: Row; grants: Grants; sendingId?: string; h: RowHandlers; after?: (fn: () => void) => void;
}) {
  const t = useTranslations("arrivals.actions");
  const b = bucketOf(r.status);
  return (
    <>
      {grants.queueEdit && !r.priority && b === "waiting" && (
        <button type="button" className={styles.actionBtn} onClick={() => after(() => h.onPriority(r.id))}>{t("markPriority")}</button>
      )}
      {grants.queueEdit && (r.status === "checked_in" || r.status === "waiting") && (
        <button type="button" className={styles.actionBtn} disabled={sendingId === r.id} onClick={() => after(() => h.onSendForVitals(r))}>{sendingId === r.id ? t("sending") : t("sendVitals")}</button>
      )}
      {grants.vitalsRecord && r.status === "vitals_pending" && (
        <button type="button" className={styles.actionBtn} onClick={() => after(() => h.onVitals(r.id))}>{t("recordVitals")}</button>
      )}
      {grants.checkout && b === "checkout_pending" && (
        <button type="button" className={styles.actionBtn} onClick={() => after(() => h.onCheckout(r.id))}>{t("checkout")}</button>
      )}
    </>
  );
}
