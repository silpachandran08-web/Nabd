"use client";

import type { ReactNode } from "react";
import type { Row } from "../shared";
import type { Step } from "../workflow/consultWorkflow";
import styles from "../consult.module.css";

type Props = {
  badge: ReactNode; step: Step; closedOut: boolean; locked: boolean; canEditNote: boolean; noteLoaded: boolean; mayAmend: boolean; mayDecideRx: boolean;
  entryStatus: string; saveState: string; dirty: boolean; hasConflict: boolean; completing: boolean; signedBlocked: boolean; primaryLabel: string; canTransfer: boolean;
  next: Row | null;
  onAmend: () => void; onOutputs: () => void; onNext: (r: Row) => void; onBack: () => void; onSaveDraft: () => void; onTransfer: () => void; onCloseStep: () => void; onComplete: () => void;
};

// FEF-030: the sticky bar: save state on the left, the visit's next action on the right.
export default function ActionBar(a: Props) {
  return (
    <div className={styles.actionBar}>
      <div className={styles.stripActions}>{a.badge ?? <span className={styles.saveStatus}>No changes yet</span>}</div>
      <div className={styles.stripActions}>
        {a.closedOut ? (
          <>
            {a.mayAmend && <button className={styles.completeBtn} onClick={a.onAmend}>Add amendment</button>}
            {a.step !== "close" && <button className={styles.backBtn} onClick={a.onOutputs}>Visit outputs</button>}
            {a.next
              ? <button className={styles.backBtn} onClick={() => a.onNext(a.next!)}>Next patient · #{a.next.tokenNumber}</button>
              : <button className={styles.backBtn} onClick={a.onBack}>Back to queue</button>}
          </>
        ) : (
          <>
            {!a.locked && a.canEditNote && a.noteLoaded && (
              <button className={styles.backBtn} onClick={a.onSaveDraft} disabled={a.saveState === "saving" || a.hasConflict || (!a.dirty && a.saveState !== "error")}>
                {a.saveState === "saving" ? "Saving…" : "Save draft"}
              </button>
            )}
            {a.entryStatus === "in_consult" && a.canTransfer && (
              <button className={styles.backBtn} onClick={a.onTransfer} disabled={a.completing}>Transfer to another department</button>
            )}
            {a.canEditNote && (a.step !== "close" ? (
              <button className={styles.completeBtn} onClick={a.onCloseStep}>Close consultation</button>
            ) : a.mayDecideRx && (
              <button className={styles.completeBtn} onClick={a.onComplete} disabled={a.completing || a.closedOut || a.signedBlocked || a.hasConflict}>
                {a.primaryLabel}
              </button>
            ))}
          </>
        )}
      </div>
    </div>
  );
}
