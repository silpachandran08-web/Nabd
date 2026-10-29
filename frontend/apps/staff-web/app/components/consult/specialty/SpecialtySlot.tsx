"use client";

// FEF-030: where the specialty workspace framework (T-298, Clinical squad) plugs into the Examine step. Today: the dental tooth chart.
import { useTranslations } from "next-intl";
import { can } from "../../../lib/permissions";
import Icon from "../../ui/Icon";
import type { PatientDetail } from "../shared";
import p from "../consultParts.module.css";

export default function SpecialtySlot({ patient, onOpenDental }: { patient: PatientDetail | null; onOpenDental: () => void }) {
  const t = useTranslations("consult.specialtySlot");
  if (!can("specialty_dental:view")) return null;
  return (
    <section className={p.card} aria-label={t("specialtyTools")}>
      <h3 className={p.cardTitle}>{t("specialtyTools")}</h3>
      <div className={p.row}>
        <button type="button" className={p.btn} onClick={onOpenDental}>
          <Icon name="stethoscope" size={16} /> {" "}{t("toothChart")}
        </button>
        <span className={p.muted}>{t("opensBesideThisNoteWith")}{" "}{patient?.name ?? t("thePatient")}{t("sChart")}</span>
      </div>
    </section>
  );
}
