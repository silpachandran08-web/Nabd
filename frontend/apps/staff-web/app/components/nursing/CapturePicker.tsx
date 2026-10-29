"use client";

import styles from "./nursing.module.css";
import { EmptyState } from "../ui/StateViews";
import { waitMinutes, type Row } from "./model";

// FEF-031: "Capture vitals — pick a patient" over the vitals-due rows.
export default function CapturePicker({ rows, onPick, onClose }: { rows: Row[]; onPick: (id: string) => void; onClose: () => void }) {
  return (
        <div className={styles.overlay} onClick={() => onClose()}>
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <h2 className={styles.modalTitle}>Capture vitals — pick a patient</h2>
            {rows.length === 0 ? (
              <EmptyState icon="pulse" title="No one is waiting for vitals" hint="New arrivals appear here as soon as reception checks them in." />
            ) : (
              rows.map((r) => (
                <button type="button" key={r.id} className={styles.pickerRow} onClick={() => { onPick(r.id); }}>
                  <span className={styles.patientName}>{r.patientName}</span>
                  <span className={styles.muted}>{waitMinutes(r.createdAt)}m waiting</span>
                </button>
              ))
            )}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => onClose()}>Cancel</button>
            </div>
          </div>
        </div>
  );
}
