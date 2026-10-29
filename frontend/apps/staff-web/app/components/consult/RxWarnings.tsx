"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { rxItemLine, type PrescriptionItem, type RxWarning } from "./shared";
import s from "./consultParts.module.css";

/** Glossary ids (packages/i18n/glossary): the warning title is a clinical term, so every screen shows the same reviewed wording. */
export const WARNING_TITLE_ID: Record<RxWarning["kind"], string> = {
  allergy: "allergy.warning.title",
  pregnancy: "pregnancy.warning.title",
  controlled: "controlled.warning.title",
};

type Glossary = ReturnType<typeof useTranslations>;
/** The server's own text for pregnancy and controlled warnings; the allergy one is the glossary sentence around the matched allergy. */
const warningBody = (g: Glossary, w: RxWarning) => (w.kind === "allergy" ? g("allergy.warning.conflict", { message: w.message }) : w.message);
const SEVERITIES = ["mild", "moderate", "severe"];

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
  const t = useTranslations("consult.rxWarnings");
  const g = useTranslations("glossary");
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
              <strong>{g(WARNING_TITLE_ID[w.kind])}{w.kind === "allergy" && SEVERITIES.includes(w.severity) && <> · {g(`allergy.severity.${w.severity}`)}</>}{!w.requiresDecision && <> {g("warning.advisory")}</>}</strong>
              <span>{warningBody(g, w)}</span>
              {source !== undefined && <span className={s.muted}>{source ?? t("sourceSafetyDictionaryVersionUnavailable")}</span>}
              {w.decision && (
                <span>
                  <StatusPill tone="info" icon="check" label={g("warning.decisionRecorded")} />{" "}
                  {w.decision.reasonLabel ?? w.decision.reasonCode}{w.decision.note ? ` — ${w.decision.note}` : ""}
                  {w.decision.byName ? ` · ${w.decision.byName}` : ""}{w.decision.at ? ` · ${fmt(w.decision.at)}` : ""}
                </span>
              )}
              {open && !locked && onOverride && <span className={s.muted}>{t("anOverrideLogsTheWarning")}</span>}
              {open && !locked && (
                <span className={s.row}>
                  <button type="button" className={s.btn} onClick={onChangeMedicine}>{t("changeMedicine")}</button>
                  {onOverride && (
                    <button type="button" className={s.btn} onClick={() => onOverride(w)}>
                      {w.kind === "controlled" ? t("recordAcknowledgement") : t("overrideWithAClinicalReason")}
                    </button>
                  )}
                  {onRemove && <button type="button" className={s.btn} onClick={onRemove}>{t("removeMedicine")}</button>}
                </span>
              )}
              {w.decision && !locked && onOverride && (
                <span><button type="button" className={s.linkBtn} onClick={() => onOverride(w)}>{t("changeReason")}</button></span>
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
  const t = useTranslations("consult.rxWarnings");
  const g = useTranslations("glossary");
  const [code, setCode] = useState(warning.decision?.reasonCode ?? "");
  const [note, setNote] = useState(warning.decision?.note ?? "");
  const [at] = useState(() => new Date());
  const reason = warning.reasonOptions.find((o) => o.code === code);
  const single = warning.reasonOptions.length === 1;
  const name = `overrideReason-${warning.kind}`;

  return (
    <Dialog title={warning.kind === "controlled" ? t("acknowledgeControlled") : t("overrideWarning")} onClose={onClose}>
      <div className={s.stack}>
        <div className={`${s.notice} ${s.noticeDanger}`}>
          <Icon name="alert" size={16} />
          <span><strong>{g(WARNING_TITLE_ID[warning.kind])}.</strong> {warningBody(g, warning)} <br />{t("medicine")}{" "}{rxItemLine(item)}</span>
        </div>
        <fieldset className={s.fieldset}>
          <legend className={s.legend}>{t("clinicalReasonMandatory")}</legend>
          {warning.reasonOptions.map((o) => (
            <label key={o.code} className={s.check}>
              <input type="radio" name={name} value={o.code} checked={code === o.code} onChange={() => setCode(o.code)} />
              {o.label}
            </label>
          ))}
        </fieldset>
        <label className={`${s.check} ${s.colGap4}`}>
          <span className={s.muted}>{t("noteOptional")}</span>
          <textarea className={s.textarea} value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
        </label>
        {reason && (
          <section className={s.card} aria-label={t("whatGetsLogged")}>
            <h3 className={s.cardTitle}>{t("whatGetsLogged")}</h3>
            <ul className={s.list}>
              <li>{t("warning")}{" "}{g(WARNING_TITLE_ID[warning.kind])} · {warning.matched}</li>
              <li>{t("doctorAcknowledgement")}{" "}{doctorName || t("you")}</li>
              <li>{t("dateAndTime")}{" "}{at.toLocaleString("en-GB", { day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" })}</li>
              <li>{t("reason", { label: reason.label })}{note.trim() ? ` — ${note.trim()}` : ""}</li>
              <li>{t("finalPrescribedItem")}{" "}{rxItemLine(item)}</li>
            </ul>
          </section>
        )}
        {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert">{error}</div>}
      </div>
      <div className={s.dialogActions}>
        <button type="button" className={s.btn} onClick={onClose}>{t("cancel")}</button>
        <button type="button" className={s.btnPrimary} disabled={!code || busy} onClick={() => onConfirm(code, note.trim())}>
          {busy ? t("saving") : !code ? t("chooseReason") : single ? t("recordAndSave") : t("overrideAndSave")}
        </button>
      </div>
    </Dialog>
  );
}
