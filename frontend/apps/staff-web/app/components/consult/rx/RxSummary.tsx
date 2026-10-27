"use client";

import { useTranslations } from "next-intl";
import StatusPill from "../../ui/StatusPill";
import { rxItemLine, type PrescriptionItem } from "../shared";
import p from "../consultParts.module.css";

// FEF-030: "Prescription" card of the side column.
export default function RxSummary({ items, status, onOpen }: { items: PrescriptionItem[]; status: "draft" | "signed"; onOpen: () => void }) {
  const t = useTranslations("consult.rxSummary");
  const g = useTranslations("glossary");
  const drugs = items.filter((i) => i.drugName.trim());
  return (
    <section className={p.card} aria-label={t("prescriptionSummary")}>
      <h3 className={p.cardTitle}>
        {g("rx.prescription")}
        {drugs.length === 0 ? <StatusPill tone="neutral" icon="list" label={t("none")} /> : status === "signed" ? <StatusPill tone="ok" label={t("signed")} /> : <StatusPill tone="warn" label={t("draft")} />}
      </h3>
      {drugs.length > 0 ? <ul className={p.list}>{drugs.map((i, n) => <li key={n}>{rxItemLine(i)}</li>)}</ul> : <p className={p.muted}>{t("noDrugsAdded")}</p>}
      <button type="button" className={`${p.linkBtn} ${p.linkBtnTouch}`} onClick={onOpen}>{t("openPrescription")}</button>
    </section>
  );
}
