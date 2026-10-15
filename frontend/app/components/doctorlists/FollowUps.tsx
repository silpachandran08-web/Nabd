"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import styles from "./doctorLists.module.css";
import Icon from "../ui/Icon";
import StatusPill, { type PillTone } from "../ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { addDays, fmtDate, fmtTime, getJson, isoDate, type PageMeta } from "./api";

// DTOs mirror queue/FollowUpListModels.FollowUpItem and clinical/dto/DueConditionResponse exactly.
type FollowUp = { appointmentId: string; patientId: string; patientName: string; patientMrn: string; startTime: string; status: "scheduled" | "cancelled" | "completed" | "no_show" };
type FollowUpPage = { data: FollowUp[]; page: PageMeta };
type DueCondition = { id: string; patientId: string; patientName: string; condition: string; reviewDueDate: string };

const VIEWS = [
  { key: "upcoming", label: "Upcoming" },
  { key: "missed", label: "Missed" },
  { key: "all", label: "All (last 90 days on)" },
] as const;
type ViewKey = (typeof VIEWS)[number]["key"];
const PAGE = 20;

const STATUS: Record<FollowUp["status"], { tone: PillTone; label: string }> = {
  scheduled: { tone: "info", label: "Scheduled" },
  completed: { tone: "ok", label: "Completed" },
  no_show: { tone: "danger", label: "Missed (no-show)" },
  cancelled: { tone: "neutral", label: "Cancelled" },
};

// There is no reminder system (no reminder table, no WhatsApp send log for follow-ups), so the
// column is stated as unavailable on every row rather than guessed.
function Reminder() {
  return <span className={styles.na}>Not available yet</span>;
}

function queryFor(view: ViewKey, cursor: string | null): string {
  const today = new Date();
  const p = new URLSearchParams({ limit: String(PAGE) });
  if (view === "upcoming") { p.set("status", "scheduled"); p.set("from", isoDate(today)); }
  else if (view === "missed") { p.set("status", "no_show"); p.set("from", isoDate(addDays(today, -90))); }
  else p.set("from", isoDate(addDays(today, -90)));
  if (cursor) p.set("cursor", cursor);
  return `/appointments/follow-ups?${p.toString()}`;
}

function Appointments() {
  const [view, setView] = useState<ViewKey>("upcoming");
  const [rows, setRows] = useState<FollowUp[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [moreBusy, setMoreBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [moreError, setMoreError] = useState(false);
  const seq = useRef(0);

  const load = useCallback(async () => {
    const my = ++seq.current;
    setLoading(true);
    setError(null);
    try {
      const page = await getJson<FollowUpPage>(queryFor(view, null));
      if (my !== seq.current) return;
      setRows(page.data);
      setNext(page.page.nextCursor);
      setMoreError(false);
    } catch (e) {
      if (my !== seq.current || (e as Error).message === "auth") return;
      setError("Couldn't load your follow-up appointments. Check your connection and try again.");
    } finally {
      if (my === seq.current) setLoading(false);
    }
  }, [view]);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  async function loadMore() {
    if (!next) return;
    const my = seq.current;
    setMoreBusy(true);
    setMoreError(false);
    try {
      const page = await getJson<FollowUpPage>(queryFor(view, next));
      if (my !== seq.current) return;
      setRows((r) => [...r, ...page.data]);
      setNext(page.page.nextCursor);
    } catch { if (my === seq.current) setMoreError(true); }
    finally { setMoreBusy(false); }
  }

  return (
    <section className={styles.card} aria-label="Follow-up appointments">
      <h2 className={styles.cardTitle}>Follow-up appointments {!loading && !error && <span className={styles.count}>{rows.length}{next ? "+" : ""}</span>}</h2>
      <p className={styles.hint}>Your appointments booked as a follow-up. The booking records no reason, so none is shown.</p>
      <div className={styles.filters}>
        <div className={styles.chips} role="group" aria-label="Follow-up view">
          {VIEWS.map((v) => <button key={v.key} type="button" className={styles.chip} aria-pressed={view === v.key} onClick={() => setView(v.key)}>{v.label}</button>)}
        </div>
      </div>
      {loading && rows.length === 0 ? <Skeleton rows={4} label="Loading follow-ups" />
        : error ? <ErrorState message={error} onRetry={() => void load()} />
        : rows.length === 0 ? <EmptyState icon="calendar" title={view === "upcoming" ? "No upcoming follow-ups." : view === "missed" ? "No missed follow-ups in the last 90 days." : "No follow-up appointments in the last 90 days."} />
        : (
          <>
            <div className={`${styles.head} ${styles.cols4}`} aria-hidden="true"><span>Patient</span><span>Due</span><span>Reason</span><span>Reminder</span><span>State</span></div>
            <ul className={styles.list} aria-busy={loading}>
              {rows.map((r) => (
                <li key={r.appointmentId} className={`${styles.row} ${styles.cols4}`}>
                  <span className={styles.cell}><span className={styles.name}>{r.patientName}</span><span className={styles.meta}>{r.patientMrn}</span></span>
                  <span className={styles.cell}><span className={styles.mlabel}>Due</span>{fmtDate(r.startTime)}<span className={styles.meta}>{fmtTime(r.startTime)}</span></span>
                  <span className={styles.cell}><span className={styles.mlabel}>Reason</span>Follow-up visit</span>
                  <span className={styles.cell}><span className={styles.mlabel}>Reminder</span><Reminder /></span>
                  <span className={styles.cell}><span className={styles.mlabel}>State</span><span className={styles.pills}><StatusPill tone={STATUS[r.status].tone} label={STATUS[r.status].label} /></span></span>
                </li>
              ))}
            </ul>
            {moreError && <div className={styles.inlineErr} role="alert">Couldn&apos;t load more. <button type="button" className={styles.btn} onClick={() => void loadMore()}>Retry</button></div>}
            {next && !moreError && <div className={styles.more}><button type="button" className={styles.btn} onClick={() => void loadMore()} disabled={moreBusy}>{moreBusy ? "Loading…" : "Load more"}</button></div>}
          </>
        )}
    </section>
  );
}

function ChronicDue() {
  const [rows, setRows] = useState<DueCondition[] | null>(null);
  const [error, setError] = useState(false);
  const load = useCallback(async () => {
    setError(false);
    setRows(null);
    try { setRows(await getJson<DueCondition[]>("/clinical/conditions/due")); }
    catch (e) { if ((e as Error).message !== "auth") setError(true); }
  }, []);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);
  const today = isoDate(new Date());

  return (
    <section className={styles.card} aria-label="Chronic reviews due">
      <h2 className={styles.cardTitle}>Chronic reviews due {rows && <span className={styles.count}>{rows.length}</span>}</h2>
      <p className={styles.hint}>Clinic-wide: active conditions whose review date has arrived (not limited to your own patients).</p>
      {error ? <ErrorState message="Couldn't load chronic reviews due." onRetry={() => void load()} />
        : rows === null ? <Skeleton rows={3} label="Loading chronic reviews" />
        : rows.length === 0 ? <EmptyState icon="check" title="No chronic reviews are due." />
        : (
          <>
            <div className={`${styles.head} ${styles.cols4}`} aria-hidden="true"><span>Patient</span><span>Due</span><span>Reason</span><span>Reminder</span><span>State</span></div>
            <ul className={styles.list}>
              {rows.map((r) => (
                <li key={r.id} className={`${styles.row} ${styles.cols4}`}>
                  <span className={styles.cell}><span className={styles.name}>{r.patientName}</span></span>
                  <span className={styles.cell}><span className={styles.mlabel}>Due</span>{fmtDate(r.reviewDueDate)}</span>
                  <span className={styles.cell}><span className={styles.mlabel}>Reason</span>{r.condition} review</span>
                  <span className={styles.cell}><span className={styles.mlabel}>Reminder</span><Reminder /></span>
                  <span className={styles.cell}><span className={styles.mlabel}>State</span><span className={styles.pills}>
                    {r.reviewDueDate < today ? <StatusPill tone="danger" label="Overdue" /> : <StatusPill tone="warn" label="Due today" />}
                  </span></span>
                </li>
              ))}
            </ul>
          </>
        )}
    </section>
  );
}

export default function FollowUps() {
  const [tick, setTick] = useState(0);
  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.title}>Follow-ups</h1>
          <p className={styles.subtitle}>Follow-up appointments and chronic reviews that are due. Reminders are not tracked yet.</p>
        </div>
        <button type="button" className={styles.btn} onClick={() => setTick((t) => t + 1)} aria-label="Refresh follow-ups">
          <Icon name="refresh" size={14} /> Refresh
        </button>
      </header>
      <Appointments key={`a${tick}`} />
      {can("clinical:view") && <ChronicDue key={`c${tick}`} />}
    </main>
  );
}
