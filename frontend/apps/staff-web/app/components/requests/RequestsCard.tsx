"use client";

import Link from "next/link";
import { formatClinicDate, formatClinicTime } from "../../lib/clinicTime";
import { useClinicTimeZone } from "../../lib/useClinicTimeZone";
import Can from "../ui/Can";
import { reasonLabel } from "./requestsLogic";
import { useBookingRequests } from "./useBookingRequests";
import styles from "./requests.module.css";

/** Solo doctor's home: up to three pending requests with a one-tap Accept (thumb-sized). Absent without queue:create and when nothing waits. */
export default function RequestsCard() {
  return <Can permission="queue:create"><Body /></Can>;
}

function Body() {
  const r = useBookingRequests(3);
  const tz = useClinicTimeZone();
  const items = r.items ?? [];
  if (items.length === 0) return null;
  return (
    <section className={styles.soloCard} aria-label="Booking requests waiting">
      <h2 className={styles.soloTitle}>Requests waiting <span className={styles.badge}>{items.length}</span></h2>
      {r.notice && <div className={`${styles.notice} ${r.notice.tone === "warn" ? styles.noticeWarn : ""}`} role="status">{r.notice.text}</div>}
      <ul className={styles.soloList}>
        {items.map((it) => (
          <li key={it.appointmentId} className={styles.soloRow}>
            <div className={styles.main}>
              <span className={styles.name}>{it.patient?.displayName}</span>
              <span className={styles.meta}>{formatClinicDate(it.start ?? "", undefined, tz)} {formatClinicTime(it.start ?? "", tz)} · {reasonLabel(it.reviewReason)}</span>
            </div>
            <button type="button" className={styles.primary} onClick={() => void r.accept(it.appointmentId)}>Accept</button>
          </li>
        ))}
      </ul>
      <Link href="/requests" className={styles.moreLink}>See all requests</Link>
    </section>
  );
}
