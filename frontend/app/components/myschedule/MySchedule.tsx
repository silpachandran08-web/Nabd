"use client";

import { useMemo, useState } from "react";
import styles from "./mySchedule.module.css";
import Icon from "../ui/Icon";
import StatusPill, { type PillTone } from "../ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { hhmm, isoDate, useDayGrid, useMySchedule, type DayCell, type Leave, type WorkingHours } from "./useMySchedule";
import { LeaveDialog, WorkingHoursDialog } from "./ScheduleDialogs";


function parseIso(s: string): Date {
  const [y, m, d] = s.split("-").map(Number);
  return new Date(y, m - 1, d);
}
function addDays(d: Date, n: number): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
}
const fmtDay = (d: Date) => d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" });
const fmtLong = (d: Date) => d.toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long", year: "numeric" });
const leaveOn = (leave: Leave[], iso: string) => leave.find((l) => l.dateFrom <= iso && iso <= l.dateTo) ?? null;

const CELL_PILL: Record<string, { tone: PillTone; label: string; icon?: "check" | "clock" | "info" | "alert" | "list" }> = {
  booked: { tone: "ok", label: "Booked", icon: "check" },
  available: { tone: "info", label: "Free", icon: "clock" },
  past: { tone: "neutral", label: "Past", icon: "list" },
  occupied: { tone: "neutral", label: "In an appointment", icon: "list" },
  break: { tone: "neutral", label: "Break", icon: "list" },
  off: { tone: "neutral", label: "Not working", icon: "list" },
  leave: { tone: "warn", label: "On leave", icon: "alert" },
};

function WeekView({ start, hours, leave, onPickDay }: { start: Date; hours: WorkingHours[]; leave: Leave[]; onPickDay: (iso: string) => void }) {
  const days = Array.from({ length: 7 }, (_, i) => addDays(start, i));
  return (
    <ul className={styles.week} aria-label="Week of working hours">
      {days.map((d) => {
        const iso = isoDate(d);
        const blocks = hours.filter((h) => h.dayOfWeek === d.getDay());
        const onLeave = leaveOn(leave, iso);
        return (
          <li key={iso} className={styles.dayCard}>
            <button type="button" className={styles.dayHead} onClick={() => onPickDay(iso)} aria-label={`Open ${fmtLong(d)}`}>
              <span>{fmtDay(d)}</span>
              <Icon name="chevron" size={14} />
            </button>
            {onLeave ? (
              <div className={styles.dayBody}>
                <StatusPill tone="warn" icon="alert" label="On leave" />
                {onLeave.reason && <span className={styles.meta}>{onLeave.reason}</span>}
              </div>
            ) : blocks.length === 0 ? (
              <div className={styles.dayBody}><StatusPill tone="neutral" icon="list" label="Not working" /></div>
            ) : (
              <div className={styles.dayBody}>
                {blocks.map((b) => (
                  <div key={b.id} className={styles.block}>
                    <span className={styles.blockTime}>{hhmm(b.startTime)}–{hhmm(b.endTime)}</span>
                    <span className={styles.meta}>{b.slotMinutes} min slots{b.maxPatients ? ` · max ${b.maxPatients}` : ""}</span>
                  </div>
                ))}
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function DayView({ date, selfId, hours, leave }: { date: string; selfId: string; hours: WorkingHours[]; leave: Leave[] }) {
  const { grid, loading, error, reload } = useDayGrid(date, true);
  const d = parseIso(date);
  const blocks = hours.filter((h) => h.dayOfWeek === d.getDay());
  const onLeave = leaveOn(leave, date);
  const me = grid?.doctors.find((x) => x.id === selfId) ?? null;
  const slots = useMemo(() => {
    if (!grid) return [];
    return grid.sessions.flatMap((s) =>
      s.rows.map((r) => ({ time: r.time, session: s.label, cell: r.cells.find((c) => c.doctorId === selfId) as DayCell | undefined })),
    ).filter((x): x is { time: string; session: string; cell: DayCell } => !!x.cell && x.cell.kind !== "off");
  }, [grid, selfId]);

  return (
    <div className={styles.dayView}>
      <div className={styles.dayFacts}>
        {onLeave ? <StatusPill tone="warn" icon="alert" label={`On leave${onLeave.reason ? `: ${onLeave.reason}` : ""}`} />
          : blocks.length === 0 ? <StatusPill tone="neutral" icon="list" label="Not working" />
          : blocks.map((b) => <span key={b.id} className={styles.chip}>{hhmm(b.startTime)}–{hhmm(b.endTime)} · {b.slotMinutes} min{b.maxPatients ? ` · max ${b.maxPatients}` : ""}</span>)}
        {grid?.holiday && <StatusPill tone="info" icon="info" label={`Clinic holiday: ${grid.holiday}`} />}
      </div>
      {loading ? <Skeleton rows={5} label="Loading day" />
        : error ? <ErrorState message={error} onRetry={reload} />
        : !me ? <EmptyState icon="calendar" title="You are not on the clinic's schedule grid for this day." hint="Add working hours to appear here." />
        : slots.length === 0 ? <EmptyState icon="calendar" title="No slots for this day." hint="You have no working hours on this weekday, or you are on leave." />
        : (
          <ul className={styles.slots} aria-label="Slots">
            {slots.map(({ time, cell }) => {
              const p = CELL_PILL[cell.kind] ?? { tone: "neutral" as PillTone, label: cell.kind };
              const b = cell.bookings[0];
              return (
                <li key={time} className={styles.slot}>
                  <span className={styles.slotTime}>{time}</span>
                  <StatusPill tone={p.tone} icon={p.icon} label={p.label} />
                  {b && <span className={styles.meta}>{b.patientName ?? "Patient"} · {b.visitType.replace(/_/g, " ")}{cell.bookings.length > 1 ? ` (+${cell.bookings.length - 1})` : ""}</span>}
                </li>
              );
            })}
          </ul>
        )}
    </div>
  );
}

export default function MySchedule() {
  const { selfId, hours, leave, loading, error, reload, addHours, addLeave } = useMySchedule();
  const canEdit = can("queue:edit");
  const [mode, setMode] = useState<"week" | "day">("week");
  const [anchor, setAnchor] = useState(() => isoDate(new Date()));
  const [dialog, setDialog] = useState<"hours" | "leave" | null>(null);

  const step = mode === "week" ? 7 : 1;
  const shift = (dir: number) => setAnchor(isoDate(addDays(parseIso(anchor), dir * step)));
  const todayIso = isoDate(new Date());
  const upcomingLeave = leave.filter((l) => l.dateTo >= todayIso);

  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.title}>My Schedule</h1>
          <p className={styles.subtitle}>Your weekly hours and leave</p>
        </div>
        <div className={styles.actions}>
          {canEdit && (
            <>
              <button type="button" className={styles.btn} onClick={() => setDialog("hours")}><Icon name="plus" size={14} /> Add working hours</button>
              <button type="button" className={styles.btnPrimary} onClick={() => setDialog("leave")}><Icon name="calendar" size={14} /> Apply leave</button>
            </>
          )}
          <button type="button" className={styles.btn} onClick={() => void reload()} aria-label="Refresh My Schedule"><Icon name="refresh" size={14} /> Refresh</button>
        </div>
      </header>

      {loading && hours.length === 0 && leave.length === 0 ? <Skeleton rows={6} label="Loading My Schedule" />
        : error ? <ErrorState message={error} onRetry={() => void reload()} />
        : (
          <div className={styles.grid}>
            <section className={styles.card} aria-label="Hours">
              <div className={styles.cardHead}>
                <div className={styles.seg} role="group" aria-label="View">
                  <button type="button" className={mode === "week" ? styles.segOn : styles.segBtn} aria-pressed={mode === "week"} onClick={() => setMode("week")}>Week</button>
                  <button type="button" className={mode === "day" ? styles.segOn : styles.segBtn} aria-pressed={mode === "day"} onClick={() => setMode("day")}>Day</button>
                </div>
                <div className={styles.nav}>
                  <button type="button" className={styles.navBtn} onClick={() => shift(-1)} aria-label={mode === "week" ? "Previous week" : "Previous day"}><span className={styles.flip}><Icon name="chevron" size={14} /></span></button>
                  <button type="button" className={styles.navToday} onClick={() => setAnchor(todayIso)}>Today</button>
                  <button type="button" className={styles.navBtn} onClick={() => shift(1)} aria-label={mode === "week" ? "Next week" : "Next day"}><Icon name="chevron" size={14} /></button>
                </div>
              </div>
              <h2 className={styles.range}>
                {mode === "week" ? `${fmtDay(parseIso(anchor))} – ${fmtDay(addDays(parseIso(anchor), 6))}` : fmtLong(parseIso(anchor))}
              </h2>
              {hours.length === 0 && mode === "week" ? (
                <EmptyState icon="clock" title="No working hours set yet."
                  hint={canEdit ? "Add working hours so patients can be booked with you." : "Ask the clinic manager to set your working hours."} />
              ) : mode === "week" ? (
                <WeekView start={parseIso(anchor)} hours={hours} leave={leave} onPickDay={(iso) => { setAnchor(iso); setMode("day"); }} />
              ) : (
                <DayView date={anchor} selfId={selfId} hours={hours} leave={leave} />
              )}
              <p className={styles.note}><Icon name="info" size={12} /> Hours repeat every week. Bookings live in the appointment book (Schedule).</p>
            </section>

            <div className={styles.col}>
              <section className={styles.card} aria-label="Leave">
                <h2 className={styles.cardTitle}>Leave <span className={styles.count}>{upcomingLeave.length}</span></h2>
                {upcomingLeave.length === 0 ? <p className={styles.quiet}>No upcoming leave recorded.</p> : (
                  <ul className={styles.leaveList}>
                    {upcomingLeave.map((l) => (
                      <li key={l.id} className={styles.leaveRow}>
                        <span className={styles.blockTime}>{l.dateFrom === l.dateTo ? fmtDay(parseIso(l.dateFrom)) : `${fmtDay(parseIso(l.dateFrom))} – ${fmtDay(parseIso(l.dateTo))}`}</span>
                        {l.reason && <span className={styles.meta}>{l.reason}</span>}
                      </li>
                    ))}
                  </ul>
                )}
                <p className={styles.note}><Icon name="info" size={12} /> Leave takes effect as soon as it is saved; there is no approval step.</p>
              </section>

              <section className={styles.card} aria-label="Not available yet">
                <h2 className={styles.cardTitle}>Not available yet</h2>
                <ul className={styles.unavail}>
                  <li><Icon name="clock" size={14} /> Block time <span className={styles.na}>No block-time service exists yet</span></li>
                  <li><Icon name="clock" size={14} /> Leave approval <span className={styles.na}>Leave is recorded directly</span></li>
                </ul>
              </section>
            </div>
          </div>
        )}

      {dialog === "hours" && canEdit && <WorkingHoursDialog existing={hours} onClose={() => setDialog(null)} onSave={addHours} />}
      {dialog === "leave" && canEdit && <LeaveDialog onClose={() => setDialog(null)} onSave={addLeave} />}
    </main>
  );
}
