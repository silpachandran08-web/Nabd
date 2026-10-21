"use client";

import Link from "next/link";
import styles from "./myDay.module.css";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { EmptyState } from "../ui/StateViews";
import { age, minutesSince, queueStatusLabel as statusLabel } from "@nabd/domain";
import { clinicToday, formatClinicDate, formatClinicTime } from "../../lib/clinicTime";
import type { AppointmentPage, DayRow, DueCondition, MyDayData, Part } from "./useMyDay";

const hhmm = (iso: string) => formatClinicTime(iso);
const consultHref = (id: string) => `/consult?entry=${encodeURIComponent(id)}`;
const cap = (t: string) => t.charAt(0).toUpperCase() + t.slice(1);
const nameOf = (r: DayRow) => r.patientName ?? `Token T-${r.tokenNumber}`;

function meta(r: DayRow): string {
  const p = r.patient;
  if (!p) return "";
  const parts: string[] = [];
  if (p.dob) { const a = age(p.dob); if (Number.isFinite(a)) parts.push(`${a}y`); }
  const g = (p.gender ?? "").trim();
  if (g) parts.push(g.charAt(0).toUpperCase() + g.slice(1));
  return parts.join(" · ");
}

// One clinical fact per row (wireframe): an allergy outranks the presenting complaint. When the
// patient record could not be read, allergies are UNKNOWN, not absent, so nothing is implied.
function Fact({ r }: { r: DayRow }) {
  if (r.patient && r.patient.allergies.length > 0) {
    return <StatusPill tone="danger" icon="alert" label={`Allergy: ${r.patient.allergies.join(", ")}`} />;
  }
  if (r.presentingComplaint) return <span className={styles.complaint}>{r.presentingComplaint}</span>;
  return null;
}

export function SessionStrip({ data, seen, total }: { data: MyDayData; seen: number; total: number }) {
  const pct = total > 0 ? Math.round((seen / total) * 100) : 0;
  const booked = data.appointments.state === "ok" ? data.appointments.data.data.filter((a) => a.status !== "cancelled").length : null;
  const more = data.appointments.state === "ok" && data.appointments.data.page.nextCursor ? "+" : "";
  const delay = data.delay.state === "ok" ? data.delay.data : undefined;
  return (
    <section className={`${styles.card} ${styles.strip}`} aria-label="Session status">
      <div className={styles.stripMain}>
        <div className={styles.stripLabel}>{seen} of {total} patients seen</div>
        <div className={styles.progress} role="progressbar" aria-valuemin={0} aria-valuemax={total} aria-valuenow={seen} aria-label="Patients seen today">
          <div className={styles.progressFill} style={{ inlineSize: `${pct}%` }} />
        </div>
      </div>
      <div className={styles.stripFacts}>
        {booked !== null && <span className={styles.stat}><Icon name="calendar" size={14} /> {booked}{more} appointment{booked === 1 && !more ? "" : "s"} today</span>}
        {data.wait.state === "ok" && data.wait.data.basedOnHistory && (
          <span className={styles.stat}><Icon name="clock" size={14} /> {Math.round(data.wait.data.avgVisitMinutes)} min avg visit</span>
        )}
        {data.delay.state === "unavailable" ? (
          <span className={styles.stat}>Schedule status not available right now</span>
        ) : delay ? (
          <StatusPill tone="warn" icon="clock" label={`${delay.delayMinutes} min behind schedule`} />
        ) : (
          <StatusPill tone="ok" label="No delay announced" />
        )}
      </div>
      {delay?.reason && <div className={styles.stripNote}>Reason announced: {delay.reason}</div>}
    </section>
  );
}

export function NowNext({ inRoom, next, now }: { inRoom: DayRow | null; next: DayRow | null; now: number | null }) {
  return (
    <section className={styles.card} aria-label="In the room and up next">
      <div className={styles.nowNext}>
        <div className={styles.nowBox}>
          <div className={styles.eyebrow}>In the room</div>
          {inRoom ? (
            <>
              <div className={styles.bigName}>{nameOf(inRoom)} <span className={styles.token}>T-{inRoom.tokenNumber}</span></div>
              <div className={styles.metaLine}>{meta(inRoom)}</div>
              <div className={styles.rowFlags}><StatusPill tone="sync" icon="stethoscope" label="In consultation" /><Fact r={inRoom} /></div>
              <Link href={consultHref(inRoom.id)} className={styles.primary}><Icon name="stethoscope" size={16} /> Resume consultation</Link>
            </>
          ) : <div className={styles.quiet}>Nobody in the room.</div>}
        </div>
        <div className={styles.nowBox}>
          <div className={styles.eyebrow}>Up next</div>
          {next ? (
            <>
              <div className={styles.bigName}>{nameOf(next)} <span className={styles.token}>T-{next.tokenNumber}</span></div>
              <div className={styles.metaLine}>{meta(next)}</div>
              <div className={styles.rowFlags}>
                <StatusPill tone={next.status === "vitals_done" ? "info" : "neutral"} icon={next.status === "vitals_done" ? "check" : "clock"} label={cap(statusLabel(next.status))} />
                {now !== null && minutesSince(next.createdAt, now) !== null && <span className={styles.stat}><Icon name="clock" size={14} /> waiting {minutesSince(next.createdAt, now)} min</span>}
                <Fact r={next} />
              </div>
              <Link href={consultHref(next.id)} className={styles.primary}><Icon name="stethoscope" size={16} /> Start consultation</Link>
            </>
          ) : <div className={styles.quiet}>No one is waiting.</div>}
        </div>
      </div>
    </section>
  );
}

export function QueueList({ rows, now }: { rows: DayRow[]; now: number | null }) {
  return (
    <section className={styles.card} aria-label="My queue">
      <h2 className={styles.cardTitle}>My queue <span className={styles.count}>{rows.length}</span></h2>
      {rows.length === 0 ? <EmptyState icon="check" title="Queue is clear." hint="No patients are waiting for you." /> : (
        <ul className={styles.list}>
          {rows.map((r) => {
            const wait = now !== null ? minutesSince(r.createdAt, now) : null;
            return (
              <li key={r.id}>
                <Link href={consultHref(r.id)} className={styles.qrow}>
                  <span className={styles.token}>T-{r.tokenNumber}</span>
                  <span className={styles.qmain}>
                    <span className={styles.qname}>{nameOf(r)}</span>
                    <span className={styles.metaLine}>{[meta(r), r.source ? r.source.replace(/_/g, " ") : ""].filter(Boolean).join(" · ")}</span>
                  </span>
                  <span className={styles.qflags}>
                    {r.priority && <StatusPill tone="urgent" label={r.priorityReason ? `Urgent: ${r.priorityReason}` : "Urgent"} />}
                    <Fact r={r} />
                  </span>
                  <span className={styles.qwait}>{wait !== null ? `${wait} min` : ""}</span>
                  <Icon name="stethoscope" size={14} />
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

// No endpoint exists for these three yet, so no counts are shown (never a fake number).
export function NeedsMeCard() {
  return (
    <section className={styles.card} aria-label="Needs me">
      <h2 className={styles.cardTitle}>Needs me</h2>
      <ul className={styles.unavail}>
        {["Reports awaiting my review", "Unsigned notes", "Escalated messages"].map((l) => (
          <li key={l}><Icon name="info" size={14} /> <span>{l}</span><span className={styles.na}>Not available yet</span></li>
        ))}
      </ul>
    </section>
  );
}

export function ChronicDueCard({ part }: { part: Part<DueCondition[]> }) {
  const todayIso = clinicToday();
  return (
    <section className={styles.card} aria-label="Chronic reviews due">
      <h2 className={styles.cardTitle}>Chronic reviews due {part.state === "ok" && <span className={styles.count}>{part.data.length}</span>}</h2>
      <p className={styles.hint}>Clinic-wide list, not only your patients.</p>
      {part.state === "unavailable" ? <p className={styles.quiet}>Couldn&apos;t load chronic reviews right now.</p>
        : part.data.length === 0 ? <EmptyState icon="check" title="No chronic reviews due." />
        : (
          <ul className={styles.list}>
            {part.data.slice(0, 6).map((c) => {
              const overdue = c.reviewDueDate < todayIso;
              return (
                <li key={c.id} className={styles.drow}>
                  <span className={styles.qmain}><span className={styles.qname}>{c.patientName}</span><span className={styles.metaLine}>{c.condition}</span></span>
                  <StatusPill tone={overdue ? "danger" : "warn"} icon={overdue ? "alert" : "clock"} label={`${overdue ? "Overdue" : "Due"} ${formatClinicDate(c.reviewDueDate, { day: "numeric", month: "short" })}`} />
                </li>
              );
            })}
            {part.data.length > 6 && <li className={styles.more}>+{part.data.length - 6} more</li>}
          </ul>
        )}
    </section>
  );
}

export function AppointmentsCard({ part, names }: { part: Part<AppointmentPage>; names: Record<string, string> }) {
  const list = part.state === "ok" ? part.data.data.filter((a) => a.status !== "cancelled") : [];
  return (
    <section className={styles.card} aria-label="Today's appointments">
      <h2 className={styles.cardTitle}>Today&apos;s appointments {part.state === "ok" && <span className={styles.count}>{list.length}</span>}</h2>
      {part.state === "unavailable" ? <p className={styles.quiet}>Couldn&apos;t load today&apos;s appointments right now.</p>
        : list.length === 0 ? <EmptyState icon="calendar" title="No appointments today." />
        : (
          <ul className={styles.list}>
            {list.map((a) => (
              <li key={a.id} className={styles.drow}>
                <span className={styles.time}>{hhmm(a.startTime)}</span>
                <span className={styles.qmain}><span className={styles.qname}>{names[a.patientId] ?? "Patient"}</span>{a.isFollowUp && <span className={styles.metaLine}>Follow-up</span>}</span>
                <span className={styles.apptStatus}>{statusLabel(a.status)}</span>
              </li>
            ))}
          </ul>
        )}
    </section>
  );
}
