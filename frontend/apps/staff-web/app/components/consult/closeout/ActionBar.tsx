"use client";

import { keyHint } from "../../../shell/shortcuts";
import { useTranslations } from "next-intl";
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
  const t = useTranslations("consult.actionBar");
  return (
    <div className={styles.actionBar}>
      <div className={styles.stripActions}>{a.badge ?? <span className={styles.saveStatus}>{t("noChangesYet")}</span>}</div>
      <div className={styles.stripActions}>
        {a.closedOut ? (
          <>
            {a.mayAmend && <button className={styles.completeBtn} onClick={a.onAmend}>{t("addAmendment")}</button>}
            {a.step !== "close" && <button className={styles.backBtn} onClick={a.onOutputs}>{t("visitOutputs")}</button>}
            {a.next
              ? <button className={styles.backBtn} onClick={() => a.onNext(a.next!)}>{t("nextPatient", { tokenNumber: a.next.tokenNumber })}</button>
              : <button className={styles.backBtn} onClick={a.onBack}>{t("backToQueue")}</button>}
          </>
        ) : (
          <>
            {!a.locked && a.canEditNote && a.noteLoaded && (
              <button className={styles.backBtn} {...keyHint("save")} onClick={a.onSaveDraft} disabled={a.saveState === "saving" || a.hasConflict || (!a.dirty && a.saveState !== "error")}>
                {a.saveState === "saving" ? t("saving") : t("saveDraft")}
              </button>
            )}
            {a.entryStatus === "in_consult" && a.canTransfer && (
              <button className={styles.backBtn} onClick={a.onTransfer} disabled={a.completing}>{t("transferToAnotherDepartment")}</button>
            )}
            {a.canEditNote && (a.step !== "close" ? (
              <button className={styles.completeBtn} onClick={a.onCloseStep}>{t("closeConsultation")}</button>
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
