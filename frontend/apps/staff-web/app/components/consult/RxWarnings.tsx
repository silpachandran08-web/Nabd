"use client";

import { useState } from "react";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { rxItemLine, type PrescriptionItem, type RxWarning } from "./shared";
import s from "./consultParts.module.css";

export const WARNING_TITLE: Record<RxWarning["kind"], string> = {
  allergy: "Allergy warning",
  pregnancy: "Pregnancy / lactation warning",
  controlled: "Controlled substance",
};

const WARNING_BODY: Record<RxWarning["kind"], (w: RxWarning) => string> = {
  allergy: (w) => `Conflicts with a recorded allergy: ${w.message}.`,
  pregnancy: (w) => w.message,
  controlled: (w) => w.message,
};

const SEVERITY_LABEL: Record<string, string> = { mild: "Mild", moderate: "Moderate", severe: "Severe" };

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString("en-GB", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" }) : "");

/** Decision card for the warnings of one prescription line (DOC-031/032). An unresolved warning that
 * needs a decision shows the two ways forward: change the medicine, or override with a named reason.
 * A recorded decision is shown with its reason, who and when. Advisory warnings (moderate/mild allergy)
 * stay visible but do not block. Nothing here clears a warning: only the server's answer does. */
export default function RxWarnings({
  item, locked, onOverride, onChangeMedicine, onRemove, source,
}: {
  item: PrescriptionItem; locked: boolean;
  /** CLN-004: "Source: Nabd CSO dictionary IN v1"; undefined while loading, null when unavailable. */
  source?: string | null;
  /** Absent for someone who may not record a decision (not the visit's doctor, no clinical:approve): the button is then not rendered. */
  onOverride?: (w: RxWarning) => void; onChangeMedicine: () => void; onRemove?: () => void;
}) {
  const warnings = item.warnings ?? [];
  if (warnings.length === 0) return null;
  return (
    <div className={s.warnStack}>
      {warnings.map((w) => {
        const open = w.requiresDecision && !w.resolved;
        return (
          <div key={w.kind} className={`${s.notice} ${open ? s.noticeDanger : w.requiresDecision ? s.noticeInfo : s.noticeWarn} ${s.warnCard}`} role={open ? "alert" : "status"}>
            <Icon name="alert" size={16} />
            <div className={s.warnBody}>
              <strong>{WARNING_TITLE[w.kind]}{w.kind === "allergy" && SEVERITY_LABEL[w.severity] ? ` · ${SEVERITY_LABEL[w.severity]}` : ""}{w.requiresDecision ? "" : " (advisory)"}</strong>
              <span>{WARNING_BODY[w.kind](w)}</span>
              {source !== undefined && <span className={s.muted}>{source ?? "Source: safety dictionary version unavailable"}</span>}
              {w.decision && (
                <span>
                  <StatusPill tone="info" icon="check" label="Decision recorded" />{" "}
                  {w.decision.reasonLabel ?? w.decision.reasonCode}{w.decision.note ? ` — ${w.decision.note}` : ""}
                  {w.decision.byName ? ` · ${w.decision.byName}` : ""}{w.decision.at ? ` · ${fmt(w.decision.at)}` : ""}
                </span>
              )}
              {open && !locked && onOverride && <span className={s.muted}>An override logs the warning, your name, the time, your reason and the final item.</span>}
              {open && !locked && (
                <span className={s.row}>
                  <button type="button" className={s.btn} onClick={onChangeMedicine}>Change medicine</button>
                  {onOverride && (
                    <button type="button" className={s.btn} onClick={() => onOverride(w)}>
                      {w.kind === "controlled" ? "Record acknowledgement" : "Override with a clinical reason"}
                    </button>
                  )}
                  {onRemove && <button type="button" className={s.btn} onClick={onRemove}>Remove medicine</button>}
                </span>
              )}
              {w.decision && !locked && onOverride && (
                <span><button type="button" className={s.linkBtn} onClick={() => onOverride(w)}>Change reason</button></span>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}

/** The override overlay (wireframe `rxoverride`): one named reason is mandatory, and the doctor sees
 * exactly what will be logged before confirming. */
export function OverrideDialog({
  item, warning, doctorName, busy, error, onConfirm, onClose,
}: {
  item: PrescriptionItem; warning: RxWarning; doctorName: string; busy: boolean; error: string | null;
  onConfirm: (reasonCode: string, note: string) => void; onClose: () => void;
}) {
  const [code, setCode] = useState(warning.decision?.reasonCode ?? "");
  const [note, setNote] = useState(warning.decision?.note ?? "");
  const [at] = useState(() => new Date());
  const reason = warning.reasonOptions.find((o) => o.code === code);
  const single = warning.reasonOptions.length === 1;
  const name = `overrideReason-${warning.kind}`;

  return (
    <Dialog title={warning.kind === "controlled" ? "Acknowledge a controlled substance" : "Override a clinical warning"} onClose={onClose}>
      <div className={s.stack}>
        <div className={`${s.notice} ${s.noticeDanger}`}>
          <Icon name="alert" size={16} />
          <span><strong>{WARNING_TITLE[warning.kind]}.</strong> {WARNING_BODY[warning.kind](warning)} <br />Medicine: {rxItemLine(item)}</span>
        </div>
        <fieldset className={s.fieldset}>
          <legend className={s.legend}>Clinical reason (mandatory)</legend>
          {warning.reasonOptions.map((o) => (
            <label key={o.code} className={s.check}>
              <input type="radio" name={name} value={o.code} checked={code === o.code} onChange={() => setCode(o.code)} />
              {o.label}
            </label>
          ))}
        </fieldset>
        <label className={`${s.check} ${s.colGap4}`}>
          <span className={s.muted}>Note (optional)</span>
          <textarea className={s.textarea} value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
        </label>
        {reason && (
          <section className={s.card} aria-label="What gets logged">
            <h3 className={s.cardTitle}>What gets logged</h3>
            <ul className={s.list}>
              <li>Warning: {WARNING_TITLE[warning.kind]} · {warning.matched}</li>
              <li>Doctor acknowledgement: {doctorName || "You"}</li>
              <li>Date and time: {at.toLocaleString("en-GB", { day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" })}</li>
              <li>Reason: {reason.label}{note.trim() ? ` — ${note.trim()}` : ""}</li>
              <li>Final prescribed item: {rxItemLine(item)}</li>
            </ul>
          </section>
        )}
        {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert">{error}</div>}
      </div>
      <div className={s.dialogActions}>
        <button type="button" className={s.btn} onClick={onClose}>Cancel</button>
        <button type="button" className={s.btnPrimary} disabled={!code || busy} onClick={() => onConfirm(code, note.trim())}>
          {busy ? "Saving…" : !code ? "Choose a reason" : single ? "Record and save" : "Override and save"}
        </button>
      </div>
    </Dialog>
  );
}
