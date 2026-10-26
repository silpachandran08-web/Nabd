import { useTranslations } from "next-intl";
import styles from "../arrivals.module.css";
import Dialog from "../../ui/Dialog";
import { EmptyState } from "../../ui/StateViews";
import { bucketOf } from "../board/boardLogic";
import type { Row } from "../types";

export default function CheckoutPicker({ rows, onPick, onClose }: { rows: Row[]; onPick: (id: string) => void; onClose: () => void }) {
  const t = useTranslations("arrivals");
  const ready = rows.filter((r) => bucketOf(r.status) === "checkout_pending");
  return (
    <Dialog title={t("checkoutPicker.title")} onClose={onClose}>
      {ready.length === 0 ? (
        <EmptyState icon="receipt" title={t("checkoutPicker.empty")} />
      ) : (
        <div className={styles.searchResults}>
          {ready.map((r) => <button type="button" key={r.id} className={styles.searchResultRow} onClick={() => onPick(r.id)}>#{r.tokenNumber} · {r.patientName}</button>)}
        </div>
      )}
      <div className={styles.modalActions}>
        <button type="button" className={styles.cancelBtn} onClick={onClose}>{t("dialog.cancel")}</button>
      </div>
    </Dialog>
  );
}
