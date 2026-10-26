import { useLocale, useTranslations } from "next-intl";
import type { CallbackEntryResponse } from "@nabd/api-client/generated";
import styles from "../arrivals.module.css";
import StatusPill from "../../ui/StatusPill";
import { EmptyState, ErrorState } from "../../ui/StateViews";
import type { DoctorQueue, Grants } from "../types";

export default function SideColumn({ doctors, rosterFailed, onRetryRoster, callbacks, grants, onAnnounce, onClear }: {
  doctors: DoctorQueue[]; rosterFailed: boolean; onRetryRoster: () => void; callbacks: CallbackEntryResponse[]; grants: Grants;
  onAnnounce: (doctorId: string) => void; onClear: (doctorId: string) => void;
}) {
  const t = useTranslations("arrivals.side");
  const tc = useTranslations("common");
  const locale = useLocale();
  return (
    <div className={styles.sideCol}>
      <div className={styles.card}>
        <div className={styles.sideTitle}>{t("doctorQueues")}</div>
        {rosterFailed ? (
          <ErrorState message={t("rosterFail")} onRetry={onRetryRoster} retryLabel={tc("retry")} />
        ) : doctors.length === 0 ? (
          <EmptyState icon="stethoscope" title={t("noDoctors")} />
        ) : (
          doctors.map((d) => (
            <div key={d.id} className={`${styles.doctorRow} ${styles.doctorRowStack}${d.waiting === 0 ? ` ${styles.doctorIdle}` : ""}`}>
              <div className={styles.doctorLine}>
                <span>
                  <span className={styles.doctorName}>{d.name}</span>
                  {d.departmentName && <span className={styles.doctorDept}>{d.departmentName}</span>}
                </span>
                <span className={styles.doctorCount}>
                  {d.waiting === 0 ? t("noneWaiting") : <>{t("waiting", { count: d.waiting })}{d.estimatedMinutes !== undefined && <> · {t("estimate", { min: d.estimatedMinutes })}</>}</>}
                </span>
              </div>
              {d.delay ? (
                <div className={styles.delayLine}>
                  <span><StatusPill tone="danger" label={t("delayed")} icon="clock" /> {d.delay.reason ? t("lateReason", { min: d.delay.delayMinutes ?? 0, reason: d.delay.reason }) : t("late", { min: d.delay.delayMinutes ?? 0 })}</span>
                  {grants.queueEdit && <button type="button" className={styles.actionBtn} onClick={() => onClear(d.id)}>{t("clearDelay")}</button>}
                </div>
              ) : grants.queueEdit && (
                <button type="button" className={`${styles.actionBtn} ${styles.alignStart}`} onClick={() => onAnnounce(d.id)}>{t("announceDelay")}</button>
              )}
            </div>
          ))
        )}
      </div>

      {callbacks.length > 0 && (
        <div className={styles.card}>
          <div className={styles.sideTitle}>{t("callbacks")}</div>
          {callbacks.map((c) => (
            <div key={c.appointmentId} className={`${styles.doctorRow} ${styles.doctorRowStack}`}>
              <div className={styles.doctorLine}>
                <span className={styles.doctorName}>{c.patientName}</span>
                {c.status === "no_show" ? <StatusPill tone="danger" label={t("missed")} /> : <StatusPill tone="warn" label={t("overdue")} />}
              </div>
              <span className={styles.callbackNote}>{t("followUpWas", { date: new Date(c.startTime ?? "").toLocaleDateString(locale) })}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
