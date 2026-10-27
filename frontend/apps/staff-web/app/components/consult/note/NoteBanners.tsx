"use client";

import { useTranslations } from "next-intl";
import { NOTE_FIELD_LABEL } from "@nabd/domain";
import Icon from "../../ui/Icon";
import type { NoteFields } from "../draftStore";
import styles from "../consult.module.css";
import p from "../consultParts.module.css";

type Props = {
  online: boolean; shellError: string | null; loadFailed: boolean; notice: string | null; orphanText: NoteFields | null; hasConflict: boolean;
  onRetryLoad: () => void; onDismissNotice: () => void; onDismissOrphan: () => void; onReviewConflict: () => void;
};

// FEF-030: everything that can interrupt the note editor, in the order the old screen showed it.
export default function NoteBanners(a: Props) {
  const tx = useTranslations("consult.noteBanners");
  return (
    <>
      {!a.online && (
        <div className={`${p.notice} ${p.noticeInfo} ${p.mbL}`} role="status">
          <Icon name="sync" size={16} />
          <span>{tx("youReOfflineTheNote")}</span>
        </div>
      )}
      {a.shellError && <div className={styles.errorState} role="alert">{a.shellError}</div>}
      {a.loadFailed && (
        <div className={`${p.notice} ${p.noticeDanger} ${p.mbL}`} role="alert">
          <Icon name="alert" size={16} />
          <span>{tx("couldnTLoadTheNote")}{" "}<button type="button" className={p.linkBtn} onClick={a.onRetryLoad}>{tx("tryAgain")}</button></span>
        </div>
      )}
      {a.notice && (
        <div className={`${p.notice} ${p.noticeInfo} ${p.mbL}`} role="status">
          <Icon name="info" size={16} />
          <span>{a.notice} <button type="button" className={p.linkBtn} onClick={a.onDismissNotice}>{tx("dismiss")}</button></span>
        </div>
      )}
      {a.orphanText && (
        <div className={`${p.notice} ${p.noticeWarn} ${p.mbL}`} role="alert">
          <Icon name="alert" size={16} />
          <span>
            {tx("thisNoteWasSignedIn")}
            <ul className={p.noticeList}>
              {(Object.keys(a.orphanText) as (keyof NoteFields)[]).filter((k) => a.orphanText![k].trim() !== "").map((k) => <li key={k}><strong>{NOTE_FIELD_LABEL[k]}:</strong> {a.orphanText![k]}</li>)}
            </ul>
            <button type="button" className={p.linkBtn} onClick={a.onDismissOrphan}>{tx("dismiss")}</button>
          </span>
        </div>
      )}
      {a.hasConflict && (
        <div className={`${p.notice} ${p.noticeWarn} ${p.mbL}`} role="alert">
          <Icon name="alert" size={16} />
          <span>{tx("savingIsPausedThisNote")}{" "}<button type="button" className={p.linkBtn} onClick={a.onReviewConflict}>{tx("reviewDifferences")}</button></span>
        </div>
      )}
    </>
  );
}
