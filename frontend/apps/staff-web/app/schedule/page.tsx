"use client";

import { useCallback, useEffect, useState, type ReactNode } from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { ErrorState, Skeleton } from "../components/ui/StateViews";
import Link from "next/link";
import styles from "./schedule.module.css";
import NewAppointmentDialog from "./NewAppointmentDialog";
import Dialog from "../components/ui/Dialog";
import { can } from "../lib/permissions";
import { CancelAppointmentDialog, MoveAppointmentDialog } from "./AppointmentActions";
import LiveQueueBoard from "./LiveQueueBoard";
import WaitlistPanel from "./WaitlistPanel";
import Icon from "../components/ui/Icon";
import { hijriLabel } from "../lib/hijri";
import { ApiError } from "../lib/api";
import { getter, key, prefix } from "../lib/query";
import { useHasMounted } from "../lib/useHasMounted";
import type { Full } from "../lib/gen";
import DeskShortcuts from "../components/arrivals/shortcuts/DeskShortcuts";
import type { Booking as GBooking, Cell as GCell, Counts as GCounts, Day as GWeekDay, Doctor as GDoctor, ScheduleDayResponse, ScheduleWeekResponse, Session as GSession, SessionCount as GSessionCount } from "@nabd/api-client/generated";

// GET /v1/schedule/day and /week (ScheduleBoardController): the generated types, with the fields the server always sends marked present.
type Booking = Full<GBooking, "appointmentId" | "queueEntryId" | "token"> & { visitType: "appointment" | "follow_up" | "walk_in" | "transfer" };
type Cell = Omit<Full<GCell, "slotStart">, "bookings"> & { bookings: Booking[] };
type Session = Omit<Full<GSession>, "rows"> & { rows: { time: string; cells: Cell[] }[] };
type Doctor = Full<GDoctor, "department" | "leaveReason">;
type Day = Omit<Full<ScheduleDayResponse, "holiday">, "doctors" | "sessions" | "counts"> & { doctors: Doctor[]; sessions: Session[]; counts: Full<GCounts> };
type Week = Omit<Full<ScheduleWeekResponse>, "days"> & { days: (Omit<Full<GWeekDay, "holiday">, "sessions"> & { sessions: Full<GSessionCount>[] })[] };

const DOCTOR_COLORS = ["var(--nb-accent-500)", "var(--nb-info-500)", "var(--nb-sync-500)", "var(--nb-warn-500)", "var(--nb-ok-500)", "var(--nb-urgent-500)"];
const VISIT_LABELS: Record<Booking["visitType"], string> = {
  appointment: "Appointment", follow_up: "Follow-up", walk_in: "Walk-in", transfer: "Transfer",
};
const STATUS_LABELS: Record<string, string> = {
  scheduled: "Not arrived", arrived: "Arrived", checked_in: "Arrived", waiting: "Waiting", billing_pending: "Billing pending",
  vitals_pending: "Vitals pending", vitals_done: "Vitals done", in_consult: "In consultation",
  procedures_pending: "Procedures pending", checkout_pending: "Checkout pending", completed: "Completed", no_show: "No-show",
};
const statusLabel = (status: string) => STATUS_LABELS[status] ?? status.replace(/_/g, " ");
// Non-bookable cells: label shown in the grid, and what the slot-info dialog explains.
const CELL_INFO: Record<string, { label: string; explain: (doctor: Doctor) => string }> = {
  break: { label: "Break", explain: (d) => `Outside ${d.name}'s working hours for this day.` },
  leave: { label: "Doctor leave", explain: (d) => `${d.name} is on leave${d.leaveReason ? ` (${d.leaveReason})` : ""}.` },
  off: { label: "Not working", explain: (d) => `${d.name} has no working hours on this weekday.` },
  past: { label: "Passed", explain: () => "This time has already passed." },
  closed: { label: "Clinic closed", explain: () => "The clinic is closed for a holiday — nothing can be booked." },
};
const SESSION_ICONS: Record<string, string> = { Morning: "☀", Afternoon: "◐", Evening: "☾" };

/** "YYYY-MM-DD" ± days, in pure calendar arithmetic (no timezone involved). */
function addDays(iso: string, days: number): string {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** Monday on or before the date — the week overview's first column. */
function mondayOf(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  const dow = new Date(Date.UTC(y, m - 1, d)).getUTCDay(); // 0 = Sunday
  return addDays(iso, -((dow + 6) % 7));
}

function formatDay(iso: string, opts: Intl.DateTimeFormatOptions): string {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString("en-GB", { ...opts, timeZone: "UTC" });
}

function bookingClass(b: Booking): string {
  if (b.status === "no_show") return styles.bookedMissed;
  if (b.status === "scheduled") return styles.bookedPending; // booked, patient not here yet
  return styles.booked;
}

type Dialog =
  | { kind: "new"; doctorId: string; date: string; slot: string | null }
  | { kind: "booking"; booking: Booking; doctor: Doctor }
  | { kind: "slot"; cell: Cell; doctor: Doctor; time: string }
  | { kind: "cancel"; booking: Booking; doctor: Doctor }
  | { kind: "move"; booking: Booking; doctor: Doctor };

export default function SchedulePage() {
  const qc = useQueryClient();
  const mounted = useHasMounted(); // permissions live in the browser: role-dependent controls wait for hydration
  const [date, setDate] = useState<string | null>(null); // null = the clinic's today (server decides)
  const [mode, setMode] = useState<"day" | "week" | "queue">("day");
  const [doctorFilter, setDoctorFilter] = useState("all");
  const [visitFilter, setVisitFilter] = useState<"all" | Booking["visitType"]>("all");
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [waitlistOpen, setWaitlistOpen] = useState(false);
  const closeDialog = useCallback(() => setDialog(null), []);
  const closeWaitlist = useCallback(() => setWaitlistOpen(false), []);
  // Booking needs queue:create (POST /appointments) and patient search needs patients:view.
  const canBook = mounted && can("queue:create") && can("patients:view");
  const canEdit = mounted && can("queue:edit"); // cancel / move / waitlist
  const canQueue = mounted && can("queue:view"); // live queue board

  // Keep the last day on screen while the next one loads (the old state-based page did the same).
  const dayQ = useQuery({
    queryKey: key("schedule", { view: "day", date: date ?? "today" }),
    queryFn: getter<Day>(`/schedule/day${date ? `?date=${date}` : ""}`),
    placeholderData: keepPreviousData,
  });
  const day = dayQ.data ?? null;
  const weekStart = day ? mondayOf(day.date) : "";
  const weekQ = useQuery({
    queryKey: key("schedule", { view: "week", start: weekStart }),
    queryFn: getter<Week>(`/schedule/week?start=${weekStart}`),
    enabled: mode === "week" && !!day,
    placeholderData: keepPreviousData,
  });
  const week = weekQ.data ?? null;
  const forbidden = dayQ.error instanceof ApiError && dayQ.error.status === 403;
  const error = forbidden ? null
    : dayQ.error ? (dayQ.error instanceof ApiError ? "Couldn't load the schedule. Try again." : "Couldn't reach the server. Check your connection and try again.")
    : weekQ.error ? "Couldn't load the week overview. Try again." : null;
  // Reload the day and the week (retry, and after a booking, move, cancel or waitlist change).
  const load = useCallback(() => qc.invalidateQueries({ queryKey: prefix("schedule") }), [qc]);

  if (forbidden) {
    return <main className={styles.page}><p className={styles.state}>You don&apos;t have access to the schedule.</p></main>;
  }

  const current = day?.date ?? null;
  const shift = (days: number) => current && setDate(addDays(current, days));
  const doctors = day ? day.doctors.filter((d) => doctorFilter === "all" || d.id === doctorFilter) : [];
  const shownIds = new Set(doctors.map((d) => d.id));
  const colorOf = (id: string) => DOCTOR_COLORS[Math.max(0, day?.doctors.findIndex((d) => d.id === id) ?? 0) % DOCTOR_COLORS.length];
  const sessionLabels = week ? [...new Set(week.days.flatMap((d) => d.sessions.map((s) => s.label)))]
    .sort((a, b) => ["Morning", "Afternoon", "Evening"].indexOf(a) - ["Morning", "Afternoon", "Evening"].indexOf(b)) : [];

  // queueEntryId → patient name, so the live queue board can label entries it only knows by token.
  const queueNames: Record<string, string> = {};
  day?.sessions.forEach((s) => s.rows.forEach((r) => r.cells.forEach((c) => c.bookings.forEach((b) => {
    if (b.queueEntryId) queueNames[b.queueEntryId] = b.patientName;
  }))));

  function openCell(cell: Cell, doctor: Doctor, time: string) {
    if (cell.kind === "available" && cell.slotStart) {
      if (canBook && current) setDialog({ kind: "new", doctorId: doctor.id, date: current, slot: cell.slotStart });
      return;
    }
    if (CELL_INFO[cell.kind]) setDialog({ kind: "slot", cell, doctor, time });
  }

  return (
    <main className={styles.page}>
      <DeskShortcuts />
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Schedule &amp; Appointments</h1>
          {day && (
            <p className={styles.subtitle}>
              {formatDay(day.date, { weekday: "short", day: "numeric", month: "short", year: "numeric" })}
              {day.today && " · Today"}
              {" · "}{day.counts.appointments} {day.counts.appointments === 1 ? "appointment" : "appointments"}
              {day.counts.walkIns > 0 && ` · ${day.counts.walkIns} walk-in${day.counts.walkIns === 1 ? "" : "s"}`}
              {" · "}{day.counts.freeSlots} {day.counts.freeSlots === 1 ? "slot" : "slots"} free
            </p>
          )}
        </div>
        <nav className={styles.segmented} aria-label="Clinic operations view">
          <button type="button" className={mode !== "queue" ? styles.segmentActive : styles.segment}
            aria-current={mode !== "queue" ? "page" : undefined}
            onClick={() => setMode((m) => (m === "queue" ? "day" : m))}>Schedule</button>
          {canQueue && (
            <button type="button" className={mode === "queue" ? styles.segmentActive : styles.segment}
              aria-current={mode === "queue" ? "page" : undefined}
              onClick={() => { setDate(null); setMode("queue"); }}>Live Queue</button>
          )}
          <Link className={styles.segment} href="/arrivals">Arrivals</Link>
        </nav>
      </div>

      {mode !== "queue" && <div className={styles.toolbar}>
        <button type="button" className={styles.tbBtn} aria-label={mode === "day" ? "Previous day" : "Previous week"}
          onClick={() => shift(mode === "day" ? -1 : -7)} disabled={!current}>‹</button>
        <button type="button" className={day?.today ? styles.tbBtnOn : styles.tbBtn} onClick={() => setDate(null)}>Today</button>
        <button type="button" className={styles.tbBtn} aria-label={mode === "day" ? "Next day" : "Next week"}
          onClick={() => shift(mode === "day" ? 1 : 7)} disabled={!current}>›</button>
        <input type="date" aria-label="Pick date" className={styles.tbDate} value={current ?? ""}
          onChange={(e) => e.target.value && setDate(e.target.value)} />
        {current && <span className={styles.hijri} data-testid="hijri-date">{hijriLabel(current)}</span>}
        <select aria-label="Doctor" className={styles.tbSelect} value={doctorFilter} onChange={(e) => setDoctorFilter(e.target.value)}>
          <option value="all">All doctors</option>
          {day?.doctors.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
        </select>
        <select aria-label="Visit type" className={styles.tbSelect} value={visitFilter}
          onChange={(e) => setVisitFilter(e.target.value as typeof visitFilter)}>
          <option value="all">All visit types</option>
          {Object.entries(VISIT_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <button type="button" className={mode === "week" ? styles.tbBtnOn : styles.tbBtn} onClick={() => setMode(mode === "day" ? "week" : "day")}>
          {mode === "day" ? "Week overview" : "Day view"}
        </button>
        {canEdit && (
          <button type="button" className={styles.tbBtn} onClick={() => setWaitlistOpen(true)}>
            <Icon name="list" size={14} /> Waitlist
          </button>
        )}
        {canBook && day && day.doctors.length > 0 && (
          <button type="button" className={styles.tbPrimary}
            onClick={() => setDialog({ kind: "new", doctorId: doctors[0]?.id ?? day.doctors[0].id, date: day.date, slot: null })}>
            + New appointment
          </button>
        )}
      </div>}

      {error && (
        <ErrorState message={error} onRetry={() => void load()} />
      )}
      {!day && !error && <Skeleton rows={6} label="Loading schedule" />}

      {day && mode === "queue" && (
        <LiveQueueBoard doctors={day.doctors} names={queueNames} />
      )}

      {day && mode === "day" && (
        <>
          {day.holiday && <div className={styles.banner}>Clinic closed — {day.holiday}. Nothing can be booked on this day.</div>}
          {day.doctors.length === 0 ? (
            <div className={styles.empty}>No doctors yet. Add staff with a Doctor role or working hours to see their schedule here.</div>
          ) : day.sessions.length === 0 ? (
            <div className={styles.empty}>
              No working hours on {formatDay(day.date, { weekday: "long" })}s and nothing booked.
              Doctors&apos; working hours decide which slots open here.
            </div>
          ) : (
            day.sessions.map((session) => (
              <section key={session.start} aria-label={`${session.label} session`}>
                <div className={styles.sessionHead}>
                  <span aria-hidden="true">{SESSION_ICONS[session.label]}</span>
                  {session.label} session
                  <span className={styles.sessionRule} />
                  <span className={styles.sessionMeta}>{session.start} – {session.end}</span>
                </div>
                <div className={styles.gridWrap}>
                  <div className={styles.grid} style={{ gridTemplateColumns: `72px repeat(${doctors.length}, minmax(170px, 1fr))` }}>
                    <div className={styles.gridHead}>Time</div>
                    {doctors.map((d) => (
                      <div key={d.id} className={styles.gridHead} title={d.department ?? undefined}>
                        <span className={styles.docDot} style={{ background: colorOf(d.id) }} />
                        {d.name}
                        {d.onLeave && <span className={styles.leaveTag}>On leave</span>}
                      </div>
                    ))}
                    {session.rows.map((row) => (
                      <RowCells key={row.time} time={row.time} cells={row.cells.filter((c) => shownIds.has(c.doctorId))}
                        doctors={doctors} visitFilter={visitFilter} canBook={canBook}
                        onBooking={(b, d) => setDialog({ kind: "booking", booking: b, doctor: d })}
                        onCell={openCell} />
                    ))}
                  </div>
                </div>
              </section>
            ))
          )}
        </>
      )}

      {day && mode === "week" && (
        !week ? <p className={styles.state}>Loading week…</p> : (
          <div className={styles.gridWrap}>
            <div className={styles.weekGrid}>
              <div />
              {week.days.map((d) => (
                <div key={d.date} className={d.date === week.today ? styles.weekHeadToday : styles.weekHead}>
                  {formatDay(d.date, { weekday: "short", day: "numeric" })}
                </div>
              ))}
              {sessionLabels.length === 0 && (
                <div className={styles.weekLabel} style={{ gridColumn: "1 / -1" }}>No working hours or bookings this week.</div>
              )}
              {sessionLabels.map((label) => (
                <WeekRow key={label} label={label} week={week} onPick={(d) => { setDate(d); setMode("day"); }} />
              ))}
            </div>
          </div>
        )
      )}

      {dialog?.kind === "new" && day && (
        <NewAppointmentDialog doctors={day.doctors} timezone={day.timezone} initialDoctorId={dialog.doctorId}
          initialDate={dialog.date} initialSlot={dialog.slot}
          onClose={closeDialog} onBooked={() => { setDialog(null); void load(); }} />
      )}
      {dialog?.kind === "booking" && day && (
        <InfoDialog title={dialog.booking.patientName} onClose={closeDialog}
          sub={`${dialog.booking.time} · ${dialog.doctor.name} · ${formatDay(day.date, { day: "numeric", month: "short", year: "numeric" })}`}
          rows={[
            ["Visit type", VISIT_LABELS[dialog.booking.visitType]],
            ["Token", dialog.booking.token != null ? `T-${dialog.booking.token}` : "Not checked in yet"],
            ["Status", statusLabel(dialog.booking.status)],
          ]}
          action={day.today ? { label: "Open live queue", href: "/arrivals" } : undefined}
          extra={canEdit && dialog.booking.appointmentId && dialog.booking.status === "scheduled" ? (
            <>
              <button type="button" className={styles.btn} onClick={() => setDialog({ kind: "move", booking: dialog.booking, doctor: dialog.doctor })}>Move</button>
              <button type="button" className={styles.btnDangerOutline} onClick={() => setDialog({ kind: "cancel", booking: dialog.booking, doctor: dialog.doctor })}>Cancel appointment</button>
            </>
          ) : undefined} />
      )}
      {(dialog?.kind === "cancel" || dialog?.kind === "move") && day && dialog.booking.appointmentId && (() => {
        const target = {
          appointmentId: dialog.booking.appointmentId, patientName: dialog.booking.patientName,
          doctorId: dialog.doctor.id, doctorName: dialog.doctor.name, time: dialog.booking.time,
          dateLabel: formatDay(day.date, { day: "numeric", month: "short", year: "numeric" }),
        };
        const done = () => { setDialog(null); void load(); };
        return dialog.kind === "cancel"
          ? <CancelAppointmentDialog target={target} onClose={closeDialog} onDone={done} />
          : <MoveAppointmentDialog target={target} timezone={day.timezone} initialDate={day.date} onClose={closeDialog} onDone={done} />;
      })()}
      {waitlistOpen && day && (
        <WaitlistPanel doctors={day.doctors} timezone={day.timezone} canJoin={canBook}
          onClose={closeWaitlist} onBooked={() => void load()} />
      )}
      {dialog?.kind === "slot" && day && (
        <InfoDialog title={CELL_INFO[dialog.cell.kind].label} onClose={closeDialog}
          sub={`${dialog.time} · ${dialog.doctor.name} · ${formatDay(day.date, { day: "numeric", month: "short", year: "numeric" })}`}
          rows={[["Not bookable", CELL_INFO[dialog.cell.kind].explain(dialog.doctor)]]} />
      )}
    </main>
  );
}

function RowCells({ time, cells, doctors, visitFilter, canBook, onBooking, onCell }: {
  time: string; cells: Cell[]; doctors: Doctor[]; visitFilter: string; canBook: boolean;
  onBooking: (b: Booking, d: Doctor) => void; onCell: (c: Cell, d: Doctor, time: string) => void;
}) {
  return (
    <>
      <div className={styles.timeCell}>{time}</div>
      {cells.map((cell) => {
        const doctor = doctors.find((d) => d.id === cell.doctorId)!;
        return (
          <div key={cell.doctorId} className={styles.cellWrap}>
            {cell.kind === "booked" ? (
              <>
                {cell.bookings.slice(0, 2).map((b) => (
                  <button key={b.appointmentId ?? b.queueEntryId} type="button"
                    className={`${bookingClass(b)} ${visitFilter !== "all" && b.visitType !== visitFilter ? styles.dim : ""}`}
                    style={cell.bookings.length > 1 ? { minHeight: 36, marginBottom: 4 } : undefined}
                    onClick={() => onBooking(b, doctor)}>
                    <span className={styles.slotName}>{b.status === "scheduled" ? "◷" : b.status === "no_show" ? "✕" : "✓"} {b.patientName}</span>
                    <span className={styles.slotSub}>{VISIT_LABELS[b.visitType]} · {b.token != null ? `T-${b.token}` : b.time}</span>
                  </button>
                ))}
                {cell.bookings.length > 2 && <div className={styles.more}>+{cell.bookings.length - 2} more</div>}
              </>
            ) : cell.kind === "available" ? (
              canBook
                ? <button type="button" className={styles.available} onClick={() => onCell(cell, doctor, time)}>+ Available</button>
                : <div className={styles.available}>Available</div>
            ) : CELL_INFO[cell.kind] ? (
              <button type="button" className={cell.kind === "past" ? styles.faint : styles.muted} onClick={() => onCell(cell, doctor, time)}>
                {CELL_INFO[cell.kind].label}
              </button>
            ) : (
              <div className={styles.faint} aria-hidden="true" /> /* occupied: inside a longer slot */
            )}
          </div>
        );
      })}
    </>
  );
}

function WeekRow({ label, week, onPick }: { label: string; week: Week; onPick: (date: string) => void }) {
  return (
    <>
      <div className={styles.weekLabel}>{label}</div>
      {week.days.map((d) => {
        const s = d.sessions.find((x) => x.label === label);
        const empty = !s || (s.booked === 0 && s.free === 0);
        return (
          <button key={d.date} type="button" onClick={() => onPick(d.date)}
            className={d.date === week.today ? styles.weekCellToday : empty ? styles.weekCellEmpty : styles.weekCell}
            aria-label={`${label}, ${d.date}: ${d.holiday ? `closed for ${d.holiday}` : `${s?.booked ?? 0} booked, ${s?.free ?? 0} free`}`}>
            {d.holiday ? <span className={styles.weekFree}>Closed</span> : (
              <>
                <span className={styles.weekBooked}>{s?.booked ?? 0}</span>
                <span className={styles.weekFree}>{s?.free ?? 0} free</span>
              </>
            )}
          </button>
        );
      })}
    </>
  );
}

function InfoDialog({ title, sub, rows, action, extra, onClose }: {
  title: string; sub: string; rows: [string, string][]; action?: { label: string; href: string }; extra?: ReactNode; onClose: () => void;
}) {
  return (
    <Dialog title={title} onClose={onClose}>
      <div>
        <p className={styles.dialogSub}>{sub}</p>
        {rows.map(([k, v]) => (
          <div key={k} className={styles.detailRow}><span className={styles.detailKey}>{k}</span><span>{v}</span></div>
        ))}
        <div className={styles.actions}>
          {extra}
          {action && <Link className={styles.btn} href={action.href}>{action.label}</Link>}
          <button type="button" className={styles.btnPrimary} onClick={onClose} autoFocus>Got it</button>
        </div>
      </div>
    </Dialog>
  );
}
