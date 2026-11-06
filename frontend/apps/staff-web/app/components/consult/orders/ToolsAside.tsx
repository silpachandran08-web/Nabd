"use client";

// FEF-030: the side column of the workspace: prescription summary, doctor orders, follow-up, package.
import { useTranslations } from "next-intl";
import Link from "next/link";
import { can } from "../../../lib/permissions";
import type { AuthedFetch, PatientDetail, PrescriptionItem, Row } from "../shared";
import DoctorOrders from "../DoctorOrders";
import FollowUpPanel from "../FollowUpPanel";
import PackageSessionCard from "../PackageSessionCard";
import RxSummary from "../rx/RxSummary";
import PanelBoundary from "../visit/PanelBoundary";
import styles from "../consult.module.css";
import p from "../consultParts.module.css";

type Props = {
  entry: Row; patient: PatientDetail | null; authedFetch: AuthedFetch; rxItems: PrescriptionItem[]; rxStatus: "draft" | "signed";
  onOpenRx: () => void; onFollowUp: (label: string | null | undefined) => void;
};

export default function ToolsAside({ entry, patient, authedFetch, rxItems, rxStatus, onOpenRx, onFollowUp }: Props) {
  const t = useTranslations("consult.toolsAside");
  return (
    <aside className={styles.tools} aria-label={t("contextualTools")}>
      <PanelBoundary panel="rxSummary"><RxSummary items={rxItems} status={rxStatus} onOpen={onOpenRx} /></PanelBoundary>
      <PanelBoundary panel="orders"><DoctorOrders authedFetch={authedFetch} queueEntryId={entry.id} /></PanelBoundary>
      <PanelBoundary panel="followUp">
        <FollowUpPanel key={entry.id} authedFetch={authedFetch} entryId={entry.id} doctorId={entry.doctorId} patientId={entry.patientId} onBooked={onFollowUp} canBook={can("queue:create")} />
      </PanelBoundary>
      <section className={p.card} aria-label={t("package")}>
        <h3 className={p.cardTitle}>{t("package")}</h3>
        {patient && patient.activePackages > 0
          ? <p className={p.muted}>{t("activePackage", { activePackages: patient.activePackages })}{patient.activePackages === 1 ? "" : "s"}. <Link href="/packages" className={p.linkBtn}>{t("openPackages")}</Link></p>
          : <p className={p.muted}>{t("noActivePackage")}</p>}
        {can("packages:view") && <PanelBoundary panel="package"><PackageSessionCard authedFetch={authedFetch} patientId={entry.patientId} canRedeem={can("packages:edit")} /></PanelBoundary>}
      </section>
    </aside>
  );
}
