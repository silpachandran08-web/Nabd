"use client";

import { moneyFormatter } from "@nabd/i18n";
import { useTranslations } from "next-intl";
import { usePaymentMethodLabel } from "../../lib/paymentMethodText";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import Link from "next/link";
import type { DeskNoShow, DeskSummaryResponse, DeskVisits, Method, Unbilled } from "@nabd/api-client/generated";
import styles from "./billing.module.css";
import { getIdentity } from "../../lib/session";
import { ApiError } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { formatClinicTime } from "../../lib/clinicTime";
import type { Full } from "../../lib/gen";
import { checkoutHref, collectedTotal } from "./deskSummaryModel";

// GET /v1/reports/desk-summary (DeskSummaryController → DeskSummaryResponse), with the always-sent fields marked present.
type Desk = Omit<Full<DeskSummaryResponse>, "visits" | "collectionsByMode" | "unbilled" | "noShows"> & {
  visits: Full<DeskVisits>; collectionsByMode: Full<Method>[]; unbilled: Full<Unbilled, "doctorName">[]; noShows: Full<DeskNoShow, "doctorName">[];
};

/** ADM-044: the reception's end-of-day check before day close — visits, collections by mode, unbilled visits, no-shows.
 * Rendered only for queue:view (the endpoint's grant); the server scopes every figure to the caller's clinic. */
export default function DeskSummary({ day }: { day: string | null }) {
  const t = useTranslations("billing.desk");
  const paymentMethodLabel = usePaymentMethodLabel();
  const q = useQuery({ queryKey: key("desk-summary", { day: day ?? "today" }), queryFn: getter<Desk>(day ? `/reports/desk-summary?date=${day}` : "/reports/desk-summary"), placeholderData: keepPreviousData });
  const d = q.data;
  if (!d) {
    return (
      <section className={styles.desk} aria-label={t("region")}>
        {q.isError ? <div className={styles.error} role="alert">{q.error instanceof ApiError ? t("loadFailed") : t("unreachable")}</div>
          : <p className={styles.state}>{t("loading")}</p>}
      </section>
    );
  }
  const money = moneyFormatter(d.currency);
  const region = getIdentity()?.tenantRegion;
  return (
    <section className={styles.desk} aria-label={t("region")}>
      <h2 className={styles.deskTitle}>{t("region")}</h2>
      {q.isError && <div className={styles.error} role="alert">{t("refreshFailed")}</div>}
      <div className={styles.deskGrid}>
        <section className={styles.card} aria-label={t("visits")}>
          <div className={styles.cardHead}>{t("visits")}<span className={styles.amt}>{d.visits.total}</span></div>
          <div className={styles.line}><span>{t("completed")}</span><span className={styles.amt}>{d.visits.completed}</span></div>
          <div className={styles.line}><span>{t("inFlow")}</span><span className={d.visits.inFlow ? styles.amtWarn : styles.amt}>{d.visits.inFlow}</span></div>
        </section>

        <section className={styles.card} aria-label={t("collectionsByMode")}>
          <div className={styles.cardHead}>{t("collections")}<span className={styles.amtOk}>{money.format(collectedTotal(d.collectionsByMode))}</span></div>
          {d.collectionsByMode.map((m) => (
            <div key={m.method} className={styles.line}>
              <span>{paymentMethodLabel(m.method, region)}<div className={styles.lineSub}>{t("payments", { count: m.transactions })}</div></span>
              <span className={styles.amt}>{money.format(Number(m.amount))}</span>
            </div>
          ))}
        </section>

        <section className={styles.card} aria-label={t("unbilled")}>
          <div className={styles.cardHead}>{t("unbilled")}<span className={d.unbilled.length ? styles.pillBad : styles.pillOk}>{d.unbilled.length}</span></div>
          {d.unbilled.length === 0 ? <div className={styles.line}><span className={styles.lineSub}>{t("allBilled")}</span></div>
            : d.unbilled.map((u) => (
              <div key={u.queueEntryId} className={styles.line}>
                <span className={styles.rowMain}>
                  <span className={styles.rowName}>{t("tokenPatient", { token: u.token, name: u.patientName })}</span>
                  {u.doctorName && <div className={styles.rowSub}>{u.doctorName}</div>}
                </span>
                <Link className={styles.btnTap} href={checkoutHref(u.queueEntryId)}>{t("openCheckout")}</Link>
              </div>
            ))}
        </section>

        <section className={styles.card} aria-label={t("noShows")}>
          <div className={styles.cardHead}>{t("noShows")}<span className={d.noShows.length ? styles.pillWarn : styles.pillOk}>{d.noShows.length}</span></div>
          {d.noShows.length === 0 ? <div className={styles.line}><span className={styles.lineSub}>{t("noMissed")}</span></div>
            : d.noShows.map((n) => (
              <div key={n.appointmentId} className={styles.line}>
                <span className={styles.rowMain}>
                  <span className={styles.rowName}>{n.patientName}</span>
                  <div className={styles.rowSub}>{[n.doctorName, formatClinicTime(n.startTime)].filter(Boolean).join(" · ")}</div>
                </span>
              </div>
            ))}
        </section>
      </div>
    </section>
  );
}
