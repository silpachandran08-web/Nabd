"use client";

import { moneyFormatter } from "@nabd/i18n";
import { paymentMethodLabel } from "@nabd/domain";
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
  const q = useQuery({ queryKey: key("desk-summary", { day: day ?? "today" }), queryFn: getter<Desk>(day ? `/reports/desk-summary?date=${day}` : "/reports/desk-summary"), placeholderData: keepPreviousData });
  const d = q.data;
  if (!d) {
    return (
      <section className={styles.desk} aria-label="End-of-day desk summary">
        {q.isError ? <div className={styles.error} role="alert">{q.error instanceof ApiError ? "Couldn't load the desk summary. Try again." : "Couldn't reach the server. Check your connection and try again."}</div>
          : <p className={styles.state}>Loading desk summary…</p>}
      </section>
    );
  }
  const money = moneyFormatter(d.currency);
  const region = getIdentity()?.tenantRegion;
  return (
    <section className={styles.desk} aria-label="End-of-day desk summary">
      <h2 className={styles.deskTitle}>End-of-day desk summary</h2>
      {q.isError && <div className={styles.error} role="alert">Couldn&apos;t refresh the desk summary. Showing the last figures.</div>}
      <div className={styles.deskGrid}>
        <section className={styles.card} aria-label="Visits">
          <div className={styles.cardHead}>Visits<span className={styles.amt}>{d.visits.total}</span></div>
          <div className={styles.line}><span>Completed</span><span className={styles.amt}>{d.visits.completed}</span></div>
          <div className={styles.line}><span>Still in the flow</span><span className={d.visits.inFlow ? styles.amtWarn : styles.amt}>{d.visits.inFlow}</span></div>
        </section>

        <section className={styles.card} aria-label="Collections by mode">
          <div className={styles.cardHead}>Collections<span className={styles.amtOk}>{money.format(collectedTotal(d.collectionsByMode))}</span></div>
          {d.collectionsByMode.map((m) => (
            <div key={m.method} className={styles.line}>
              <span>{paymentMethodLabel(m.method, region)}<div className={styles.lineSub}>{m.transactions} {m.transactions === 1 ? "payment" : "payments"}</div></span>
              <span className={styles.amt}>{money.format(Number(m.amount))}</span>
            </div>
          ))}
        </section>

        <section className={styles.card} aria-label="Unbilled visits">
          <div className={styles.cardHead}>Unbilled visits<span className={d.unbilled.length ? styles.pillBad : styles.pillOk}>{d.unbilled.length}</span></div>
          {d.unbilled.length === 0 ? <div className={styles.line}><span className={styles.lineSub}>Every visit sent to checkout is billed.</span></div>
            : d.unbilled.map((u) => (
              <div key={u.queueEntryId} className={styles.line}>
                <span className={styles.rowMain}>
                  <span className={styles.rowName}>T-{u.token} · {u.patientName}</span>
                  {u.doctorName && <div className={styles.rowSub}>{u.doctorName}</div>}
                </span>
                <Link className={styles.btnTap} href={checkoutHref(u.queueEntryId)}>Open checkout</Link>
              </div>
            ))}
        </section>

        <section className={styles.card} aria-label="No-shows">
          <div className={styles.cardHead}>No-shows<span className={d.noShows.length ? styles.pillWarn : styles.pillOk}>{d.noShows.length}</span></div>
          {d.noShows.length === 0 ? <div className={styles.line}><span className={styles.lineSub}>No missed bookings.</span></div>
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
