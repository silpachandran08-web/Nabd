import styles from "../arrivals.module.css";
import Dialog from "../../ui/Dialog";
import { EmptyState } from "../../ui/StateViews";
import { bucketOf } from "../board/boardLogic";
import type { Row } from "../types";

export default function CheckoutPicker({ rows, onPick, onClose }: { rows: Row[]; onPick: (id: string) => void; onClose: () => void }) {
  const ready = rows.filter((r) => bucketOf(r.status) === "checkout_pending");
  return (
    <Dialog title="Checkout — pick a patient" onClose={onClose}>
      {ready.length === 0 ? (
        <EmptyState icon="receipt" title="No one is ready for checkout" />
      ) : (
        <div className={styles.searchResults}>
          {ready.map((r) => <button type="button" key={r.id} className={styles.searchResultRow} onClick={() => onPick(r.id)}>#{r.tokenNumber} · {r.patientName}</button>)}
        </div>
      )}
      <div className={styles.modalActions}>
        <button type="button" className={styles.cancelBtn} onClick={onClose}>Cancel</button>
      </div>
    </Dialog>
  );
}
