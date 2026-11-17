"use client";

// CLN-043 (homeopathy "follow-ups due"): GET /v1/appointments/follow-ups, the scheduled follow-up appointments from today on (clinic-wide,
// as the Follow-ups screen lists them).
import Link from "next/link";
import { useTranslations } from "next-intl";
import type { FollowUpPage } from "@nabd/api-client/generated";
import { EmptyState } from "../../ui/StateViews";
import { clinicToday, formatClinicDate, formatClinicTime } from "../../../lib/clinicTime";
import WidgetCard from "./WidgetCard";
import { useWidgetData } from "./useWidgetData";
import styles from "../myDay.module.css";

const SHOWN = 6;

export default function FollowUpsDueWidget() {
  const t = useTranslations("shell.specialty.myDay");
  const data = useWidgetData<FollowUpPage>("followUpsDue", `/appointments/follow-ups?status=scheduled&from=${clinicToday()}&limit=20`);
  return (
    <WidgetCard id="followUpsDue" title={t("followUpsDue")} hint={t("followUpsHint")} data={data} count={(d) => d.data?.length ?? 0}>
      {(page) => {
        const rows = page.data ?? [];
        return rows.length === 0 ? <EmptyState icon="calendar" title={t("followUpsEmpty")} /> : (
          <ul className={styles.list}>
            {rows.slice(0, SHOWN).map((r) => (
              <li key={r.appointmentId} className={styles.drow}>
                <span className={styles.qmain}><span className={styles.qname}>{r.patientName ?? t("patient")}</span></span>
                {r.startTime && <span className={styles.time}>{formatClinicDate(r.startTime, { day: "numeric", month: "short" })} {formatClinicTime(r.startTime)}</span>}
              </li>
            ))}
            {rows.length > SHOWN && <li className={styles.more}><Link href="/follow-ups">{t("seeAll")}</Link></li>}
          </ul>
        );
      }}
    </WidgetCard>
  );
}
