"use client";

// FEF-030: the note fields of the Reason / Examine / Assess / Plan steps. Each is a small component over the note editor's state.
import { useTranslations } from "next-intl";
import { useNoteFieldLabels } from "../noteLabels";
import { can } from "../../../lib/permissions";
import type { AuthedFetch, PatientDetail, Row } from "../shared";
import type { ClinicalContext } from "../clinical";
import DiagnosisField from "../DiagnosisField";
import NoteTemplates from "../NoteTemplates";
import SpecialtySlot from "../specialty/SpecialtySlot";
import VitalsCard from "../VitalsCard";
import type { NoteDraft } from "../visit/types";
import styles from "../consult.module.css";
import p from "../consultParts.module.css";

type Field = keyof NoteDraft;
export type NoteFieldProps = { note: NoteDraft; disabled: boolean; onChange: (field: Field, value: string) => void };

export function ReasonStep({ entry, ctx, onRetry, onNext }: { entry: Row; ctx: ClinicalContext; onRetry: () => void; onNext: () => void }) {
  const t = useTranslations("consult.noteSteps");
  const g = useTranslations("glossary");
  return (
    <>
      <section className={p.card} aria-label={g("soap.presentingComplaint")}>
        <h3 className={p.cardTitle}>{g("soap.presentingComplaint")}</h3>
        {entry.presentingComplaint?.trim()
          ? <p className={p.complaint}>{entry.presentingComplaint}</p>
          : <p className={p.muted}>{t("noReasonWasRecordedAt")}</p>}
      </section>
      <VitalsCard vitals={ctx.vitals} onRetry={onRetry} />
      <div><button type="button" className={p.btn} onClick={onNext}>{t("continueToExamine")}</button></div>
    </>
  );
}

export function ExamineStep({ entry, patient, authedFetch, locked, noteLoaded, onOpenDental, ...f }: NoteFieldProps & {
  entry: Row; patient: PatientDetail | null; authedFetch: AuthedFetch; locked: boolean; noteLoaded: boolean; onOpenDental: () => void;
}) {
  const tn = useTranslations("consult.noteSteps");
  const L = useNoteFieldLabels();
  return (
    <>
      {!locked && can("clinical:edit") && noteLoaded && <NoteTemplates authedFetch={authedFetch} patientId={entry.patientId} entryId={entry.id} note={f.note} onSetField={f.onChange} />}
      <div className={`${styles.card} ${styles.cardPad24}`}>
        <h3 className={p.cardTitle}>{tn("consultationNote")}</h3>
        <div className={styles.noteGrid}>
          {(["subjective", "objective"] as const).map((field) => (
            <div className={styles.noteField} key={field}>
              <label className={styles.noteLabel} htmlFor={field}>{L[field]}</label>
              <textarea id={field} className={styles.textarea} value={f.note[field]} disabled={f.disabled} onChange={(e) => f.onChange(field, e.target.value)} />
            </div>
          ))}
        </div>
      </div>
      <SpecialtySlot patient={patient} onOpenDental={onOpenDental} />
    </>
  );
}

export function AssessStep({ note, disabled, onChange }: NoteFieldProps) {
  const tn = useTranslations("consult.noteSteps");
  const L = useNoteFieldLabels();
  return (
    <div className={`${styles.card} ${styles.cardPad24}`}>
      <h3 className={p.cardTitle}>{tn("consultationNote")}</h3>
      <div className={styles.noteGrid}>
        <div className={styles.noteField}>
          <label className={styles.noteLabel} htmlFor="assessment">{L.assessment}</label>
          <textarea id="assessment" className={styles.textarea} value={note.assessment} disabled={disabled} onChange={(e) => onChange("assessment", e.target.value)} />
        </div>
        <div className={styles.noteField}>
          <DiagnosisField value={note.diagnosis} disabled={disabled} inputClassName={styles.input} onChange={(v) => onChange("diagnosis", v)} />
        </div>
      </div>
    </div>
  );
}

/** The Plan step's free-text plan; the prescription card is a separate panel below it. */
export function PlanField({ note, disabled, onChange }: NoteFieldProps) {
  const L = useNoteFieldLabels();
  return (
    <div className={`${styles.card} ${styles.cardPad24}`}>
      <div className={styles.noteField}>
        <label className={styles.noteLabel} htmlFor="plan">{L.plan}</label>
        <textarea id="plan" className={styles.textarea} value={note.plan} disabled={disabled} onChange={(e) => onChange("plan", e.target.value)} />
      </div>
    </div>
  );
}
