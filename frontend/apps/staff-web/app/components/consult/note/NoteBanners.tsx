"use client";

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
  return (
    <>
      {!a.online && (
        <div className={`${p.notice} ${p.noticeInfo} ${p.mbL}`} role="status">
          <Icon name="sync" size={16} />
          <span>You&apos;re offline. The note is saved on this device and will never silently overwrite server data; it is compared with the server copy when you reconnect. Prescriptions, orders and follow-ups need a connection.</span>
        </div>
      )}
      {a.shellError && <div className={styles.errorState} role="alert">{a.shellError}</div>}
      {a.loadFailed && (
        <div className={`${p.notice} ${p.noticeDanger} ${p.mbL}`} role="alert">
          <Icon name="alert" size={16} />
          <span>Couldn&apos;t load the note, so editing and saving are locked to protect the saved copy. <button type="button" className={p.linkBtn} onClick={a.onRetryLoad}>Try again</button></span>
        </div>
      )}
      {a.notice && (
        <div className={`${p.notice} ${p.noticeInfo} ${p.mbL}`} role="status">
          <Icon name="info" size={16} />
          <span>{a.notice} <button type="button" className={p.linkBtn} onClick={a.onDismissNotice}>Dismiss</button></span>
        </div>
      )}
      {a.orphanText && (
        <div className={`${p.notice} ${p.noticeWarn} ${p.mbL}`} role="alert">
          <Icon name="alert" size={16} />
          <span>
            This note was signed in another session, so the text on this device was not saved. Copy anything still needed into an amendment.
            <ul className={p.noticeList}>
              {(Object.keys(a.orphanText) as (keyof NoteFields)[]).filter((k) => a.orphanText![k].trim() !== "").map((k) => <li key={k}><strong>{NOTE_FIELD_LABEL[k]}:</strong> {a.orphanText![k]}</li>)}
            </ul>
            <button type="button" className={p.linkBtn} onClick={a.onDismissOrphan}>Dismiss</button>
          </span>
        </div>
      )}
      {a.hasConflict && (
        <div className={`${p.notice} ${p.noticeWarn} ${p.mbL}`} role="alert">
          <Icon name="alert" size={16} />
          <span>Saving is paused: this note changed on the server. <button type="button" className={p.linkBtn} onClick={a.onReviewConflict}>Review differences</button></span>
        </div>
      )}
    </>
  );
}
