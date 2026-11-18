"use client";

import { useEffect, useState, type ReactNode } from "react";
import styles from "./myDay.module.css";
import Icon from "../ui/Icon";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { DONE_STATUSES, WAITING_STATUSES } from "@nabd/domain";
import { useMyDay } from "./useMyDay";
import DoctorControls from "./DoctorControls";
import { can } from "../../lib/permissions";
import { clinicToday, formatClinicDate } from "../../lib/clinicTime";
import { useClinicTimeZone } from "../../lib/useClinicTimeZone";
import { AppointmentsCard, ChronicDueCard, NeedsMeCard, NowNext, QueueList, SessionStrip } from "./MyDayParts";
import SpecialtyWidgets from "./widgets/SpecialtyWidgets";
import { myDayOptions } from "../../(clinic)/specialty/specialtyModel";
import { useSpecialtyMe } from "../../(clinic)/specialty/useSpecialtyMe";

// DOC-002 "My Day" (wireframe NabdShell.html DOC-01 / "Doctor home"): session strip, In the room /
// Up next, My queue, Needs me and optional widgets. Only real endpoints feed it; anything with no
// API yet is named as such in "Needs me" rather than shown as a number.
// CLN-043: the doctor's specialty profile adds its widgets and Today-list options; the profile read runs beside My Day's reads
// and a failed or missing profile just leaves plain My Day.
export default function MyDay({ requestsSlot }: { requestsSlot?: ReactNode }) {
  const { data, loading, error, reload } = useMyDay();
  const opts = myDayOptions(useSpecialtyMe().data);
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
            {can("queue:edit") && <DoctorControls doctorId={data.selfId} inRoomId={inRoom?.id ?? null} onChanged={() => void reload()} />}
            {requestsSlot}
            <div className={styles.grid}>
              <div className={styles.col}>
                <NowNext inRoom={inRoom} next={next} now={now} opts={opts} />
                {active.length > 0 && <QueueList rows={waiting} now={now} opts={opts} />}
                {active.length === 0 && (
                  <section className={styles.card}>
                    <EmptyState icon="users" title="No patients in your queue today." hint="Patients appear here once they are checked in to you." />
                  </section>
                )}
              </div>
              <div className={styles.col}>
                <NeedsMeCard />
                {data.due && <ChronicDueCard part={data.due} />}
                <AppointmentsCard part={data.appointments} names={data.patientNames} opts={opts} />
              </div>
            </div>
          </>
        )}
      <SpecialtyWidgets ids={opts.widgets} names={data?.patientNames ?? {}} />
    </main>
  );
}

