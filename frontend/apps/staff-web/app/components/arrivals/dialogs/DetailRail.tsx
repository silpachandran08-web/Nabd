"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import styles from "../arrivals.module.css";
import Dialog from "../../ui/Dialog";
import StatusPill from "../../ui/StatusPill";
import { ErrorState, Skeleton } from "../../ui/StateViews";
import { dateLocale } from "../dateLocale";
import { bucketOf } from "../board/boardLogic";
import { visitLabel } from "../board/visitLabel";
import RowActions, { type RowHandlers } from "../board/RowActions";
import StatusBadge from "../board/StatusBadge";
import WaitCell from "../board/WaitCell";
import { patientDetailQuery } from "../hooks/useArrivals";
import ReackPrompt from "../ReackPrompt";
import ScanShareConsentPrompt from "../ScanShareConsentPrompt";
import type { Grants, Row } from "../types";

// Detail rail: the same cached patient query the board row uses (revalidated when the rail opens).
export default function DetailRail({ row, doctorName, grants, sendingId, h, onClose }: {
  row: Row; doctorName: string; grants: Grants; sendingId?: string; h: RowHandlers; onClose: () => void;
}) {
  const t = useTranslations("arrivals");
  const tc = useTranslations("common");
  const locale = useLocale();
  const patientQ = useQuery(patientDetailQuery(row.patientId));
  const p = patientQ.data;
  const b = bucketOf(row.status);
  return (
    <Dialog size="drawer" title={t("rail.title", { token: row.tokenNumber, name: row.patientName })} onClose={onClose}>
      <div className={styles.railStatus}>
        <StatusBadge status={row.status} />
        {row.priority && <StatusPill tone="urgent" label={t("board.priority")} />}
      </div>
      {row.priority && row.priorityReason && <p className={styles.railNote}>{t("rail.priorityReason", { reason: row.priorityReason ?? "" })}</p>}
      <dl className={styles.railList}>
        <div><dt>{t("rail.mrn")}</dt><dd>{row.patientMrn || "—"}</dd></div>
        <div><dt>{t("rail.visit")}</dt><dd>{visitLabel(t, row)}</dd></div>
        <div><dt>{t("rail.doctor")}</dt><dd>{doctorName}</dd></div>
        <div><dt>{t("rail.waiting")}</dt><dd>{b === "waiting" || b === "in_consult" ? <WaitCell createdAt={row.createdAt} long /> : "—"}</dd></div>
        <div><dt>{t("rail.arrived")}</dt><dd>{new Date(row.createdAt).toLocaleTimeString(dateLocale(locale), { hour: "2-digit", minute: "2-digit" })}</dd></div>
      </dl>
      <div className={styles.sideTitle} style={{ paddingInline: 0 }}>{t("rail.patient")}</div>
      {!p && !patientQ.isError ? (
        <Skeleton rows={3} label={t("rail.loadingPatient")} />
      ) : !p ? (
        <ErrorState message={t("rail.patientFail")} onRetry={() => void patientQ.refetch()} retryLabel={tc("retry")} />
      ) : (
        <dl className={styles.railList}>
          <div><dt>{t("rail.phone")}</dt><dd>{p.phone || "—"}</dd></div>
          <div><dt>{t("rail.dob")}</dt><dd>{p.dob || "—"}</dd></div>
          <div><dt>{t("rail.sex")}</dt><dd>{p.gender ? (t.has(`walkIn.${p.gender}`) ? t(`walkIn.${p.gender}`) : p.gender) : "—"}</dd></div>
        </dl>
      )}
      {grants.patientsView && <ReackPrompt patientId={row.patientId} />}
      {grants.patientsView && row.source === "abdm_scan_share" && <ScanShareConsentPrompt patientId={row.patientId} mrn={row.patientMrn} />}
      <div className={styles.railActions}>
        {/* Rail actions close the rail first so only one dialog traps focus at a time (the page's dialog state is one slot). */}
        <RowActions row={row} grants={grants} sendingId={sendingId} h={h} />
        {grants.patientsView && row.patientMrn && <Link className={styles.linkBtn} href={`/patients?q=${encodeURIComponent(row.patientMrn)}`}>{t("rail.findInRegistry")}</Link>}
      </div>
    </Dialog>
  );
}
