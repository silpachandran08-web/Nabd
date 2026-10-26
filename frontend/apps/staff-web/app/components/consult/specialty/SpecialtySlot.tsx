"use client";

// FEF-030: where the specialty workspace framework (T-298, Clinical squad) plugs into the Examine step. Today: the dental tooth chart.
import { can } from "../../../lib/permissions";
import Icon from "../../ui/Icon";
import type { PatientDetail } from "../shared";
import p from "../consultParts.module.css";

export default function SpecialtySlot({ patient, onOpenDental }: { patient: PatientDetail | null; onOpenDental: () => void }) {
  if (!can("specialty_dental:view")) return null;
  return (
    <section className={p.card} aria-label="Specialty tools">
      <h3 className={p.cardTitle}>Specialty tools</h3>
      <div className={p.row}>
        <button type="button" className={p.btn} onClick={onOpenDental}>
          <Icon name="stethoscope" size={16} /> Tooth chart
        </button>
        <span className={p.muted}>Opens beside this note with {patient?.name ?? "the patient"}&apos;s chart.</span>
      </div>
    </section>
  );
}
