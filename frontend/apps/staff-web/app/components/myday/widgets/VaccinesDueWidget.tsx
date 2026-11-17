"use client";

// CLN-043 (paediatrics "vaccines due today"): GET /v1/recall-orders/vaccines-due-today: the doctor's own patients booked or arrived today with an open vaccine
// recall due (COM-053 writes vaccine recalls). No schedule logic here: the server's recall is the only source of "due".
import { useTranslations } from "next-intl";
import type { VaccineDueResponse } from "@nabd/api-client/generated";
import StatusPill from "../../ui/StatusPill";
import { EmptyState } from "../../ui/StateViews";
import { clinicToday, formatClinicDate } from "../../../lib/clinicTime";
import WidgetCard from "./WidgetCard";
import { useWidgetData } from "./useWidgetData";
import type { WidgetProps } from "./registry";
import styles from "../myDay.module.css";

export default function VaccinesDueWidget({ names }: WidgetProps) {
  const t = useTranslations("shell.specialty.myDay");
  const data = useWidgetData<VaccineDueResponse[]>("vaccinesDueToday", "/recall-orders/vaccines-due-today");
  const today = clinicToday();
  return (
    <WidgetCard id="vaccinesDueToday" title={t("vaccinesDueToday")} hint={t("vaccinesHint")} data={data} count={(d) => d.length}>
      {(rows) => rows.length === 0 ? <EmptyState icon="check" title={t("vaccinesEmpty")} /> : (
        <ul className={styles.list}>
          {rows.map((r) => {
            const due = r.dueDate ?? today;
            const when = formatClinicDate(due, { day: "numeric", month: "short" });
            return (
              <li key={r.recallId} className={styles.drow}>
                <span className={styles.qmain}><span className={styles.qname}>{(r.patientId && names[r.patientId]) || t("patient")}</span></span>
                <StatusPill tone={r.arrived ? "info" : "neutral"} label={r.arrived ? t("arrived") : t("booked")} />
                <StatusPill tone={due < today ? "danger" : "warn"} label={due < today ? t("overdue", { date: when }) : t("due", { date: when })} />
              </li>
            );
          })}
        </ul>
      )}
    </WidgetCard>
  );
}
