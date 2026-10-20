"use client";

import { useEffect, useState } from "react";
import styles from "./myDay.module.css";
import Icon from "../ui/Icon";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { DONE_STATUSES, WAITING_STATUSES } from "@nabd/domain";
import { useMyDay } from "./useMyDay";
import { clinicToday, formatClinicDate } from "../../lib/clinicTime";
import { useClinicTimeZone } from "../../lib/useClinicTimeZone";
import { AppointmentsCard, ChronicDueCard, NeedsMeCard, NowNext, QueueList, SessionStrip } from "./MyDayParts";

// DOC-002 "My Day" (wireframe NabdShell.html DOC-01 / "Doctor home"): session strip, In the room /
// Up next, My queue, Needs me and optional widgets. Only real endpoints feed it; anything with no
// API yet is named as such in "Needs me" rather than shown as a number.
export default function MyDay() {
  const { data, loading, error, reload } = useMyDay();
  const [now, setNow] = useState<number | null>(null);
  useEffect(() => {
    const tick = () => setNow(Date.now());
    const first = setTimeout(tick, 0);
    const t = setInterval(tick, 30_000);
    return () => { clearTimeout(first); clearInterval(t); };
  }, []);

  const tz = useClinicTimeZone();
  const today = tz ? formatClinicDate(clinicToday(tz), { weekday: "long", day: "numeric", month: "long" }) : "";
  const active = data?.rows.filter((r) => r.status !== "no_show") ?? [];
  const inRoom = active.find((r) => r.status === "in_consult" && r.doctorId === data?.selfId) ?? null;
  const waiting = active.filter((r) => WAITING_STATUSES.includes(r.status));
  const next = waiting[0] ?? null;
  const seen = active.filter((r) => DONE_STATUSES.includes(r.status)).length;

  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.title}>My Day</h1>
          <p className={styles.subtitle}>{today}</p>
        </div>
        <button type="button" className={styles.refresh} onClick={() => void reload()} aria-label="Refresh My Day">
          <Icon name="refresh" size={14} /> Refresh
        </button>
      </header>

      {loading && !data ? <Skeleton rows={6} label="Loading My Day" />
        : error ? <ErrorState message={error} onRetry={() => void reload()} />
        : data && (
          <>
            <SessionStrip data={data} seen={seen} total={active.length} />
            <div className={styles.grid}>
              <div className={styles.col}>
                <NowNext inRoom={inRoom} next={next} now={now} />
                {active.length > 0 && <QueueList rows={waiting} now={now} />}
                {active.length === 0 && (
                  <section className={styles.card}>
                    <EmptyState icon="users" title="No patients in your queue today." hint="Patients appear here once they are checked in to you." />
                  </section>
                )}
              </div>
              <div className={styles.col}>
                <NeedsMeCard />
                {data.due && <ChronicDueCard part={data.due} />}
                <AppointmentsCard part={data.appointments} names={data.patientNames} />
              </div>
            </div>
          </>
        )}
    </main>
  );
}

