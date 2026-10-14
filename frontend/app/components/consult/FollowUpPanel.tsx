"use client";

import { useEffect, useState } from "react";
import { getIdentity } from "../../lib/session";
import StatusPill from "../ui/StatusPill";
import { Skeleton } from "../ui/StateViews";
import { upcomingFollowUp, type AppointmentLite } from "./consultFlow";
import type { AuthedFetch, Problem } from "./shared";
import s from "./consultParts.module.css";

// Clinic wall-clock time, not the browser's (DOC-038). The region is chosen at provisioning.
const REGION_TZ: Record<string, string> = { IN: "Asia/Kolkata", KSA: "Asia/Riyadh" };

const INTERVALS: { id: string; label: string; days?: number; months?: number }[] = [
  { id: "3d", label: "3 days", days: 3 },
  { id: "1w", label: "1 week", days: 7 },
  { id: "2w", label: "2 weeks", days: 14 },
  { id: "1m", label: "1 month", months: 1 },
  { id: "custom", label: "Custom" },
];

function clinicToday(tz: string | undefined): string {
  return new Date().toLocaleDateString("en-CA", { timeZone: tz });
}
function addToDate(ymd: string, days: number, months: number): string {
  const [y, m, d] = ymd.split("-").map(Number);
  const dt = new Date(Date.UTC(y, m - 1 + months, d + days));
  return dt.toISOString().slice(0, 10);
}

export default function FollowUpPanel({
  authedFetch, doctorId, patientId, onBooked, canBook,
}: {
  authedFetch: AuthedFetch; doctorId: string; patientId: string; onBooked: (label: string | null) => void;
  /** queue:create — booking controls are absent without it; the existing follow-up still shows. */
  canBook: boolean;
}) {
  const [tz] = useState<string | undefined>(() => REGION_TZ[getIdentity()?.tenantRegion ?? ""]);
  const [interval, setIntervalId] = useState<string | null>(null);
  const [date, setDate] = useState("");
  const [slots, setSlots] = useState<string[] | null>(null);
  const [slotsError, setSlotsError] = useState(false);
  const [slot, setSlot] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [booked, setBooked] = useState<string | null>(null);
  const [existing, setExisting] = useState<"loading" | "error" | "done">("loading");

  const fmtSlot = (iso: string) => new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", timeZone: tz });
  const fmtFull = (iso: string) => `${new Date(iso).toLocaleDateString("en-GB", { weekday: "short", day: "2-digit", month: "short", timeZone: tz })} ${fmtSlot(iso)}`;

  // A follow-up booked earlier (another session, or before a reload) is the current state: the
  // soonest scheduled follow-up appointment with this doctor (GET /appointments, is_follow_up).
  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(async () => {
      if (cancelled) return;
      setExisting("loading");
      try {
        const res = await authedFetch(`/appointments?patientId=${patientId}&doctorId=${doctorId}&limit=100`);
        if (cancelled) return;
        if (!res?.ok) { setExisting("error"); return; }
        const page: { data: AppointmentLite[] } = await res.json();
        const fu = upcomingFollowUp(page.data ?? [], doctorId, Date.now());
        const label = fu ? fmtFull(fu.startTime) : null;
        setBooked(label);
        onBooked(label);
        setExisting("done");
      } catch {
        if (!cancelled) setExisting("error");
      }
    });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once per patient/doctor; fmtFull/onBooked are render-local
  }, [authedFetch, patientId, doctorId]);

  useEffect(() => {
    if (!date) return;
    let cancelled = false;
    void Promise.resolve().then(async () => {
      if (cancelled) return;
      setSlots(null);
      setSlotsError(false);
      setSlot("");
      try {
        const res = await authedFetch(`/doctors/${doctorId}/availability?date=${date}`);
        if (cancelled) return;
        if (!res?.ok) { setSlotsError(true); setSlots([]); return; }
        setSlots(await res.json());
      } catch {
        if (!cancelled) { setSlotsError(true); setSlots([]); }
      }
    });
    return () => { cancelled = true; };
  }, [authedFetch, doctorId, date]);

  function pick(id: string) {
    setIntervalId(id);
    setMessage(null);
    const iv = INTERVALS.find((i) => i.id === id);
    if (iv && (iv.days || iv.months)) setDate(addToDate(clinicToday(tz), iv.days ?? 0, iv.months ?? 0));
    else setDate("");
  }

  async function book() {
    if (!slot) return;
    setBusy(true);
    setMessage(null);
    try {
      const res = await authedFetch("/appointments", { method: "POST", body: JSON.stringify({ patientId, doctorId, startTime: slot, isFollowUp: true }) });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't schedule the follow-up." }));
        setMessage(p.detail || "Couldn't schedule the follow-up.");
        return;
      }
      const label = fmtFull(slot);
      setBooked(label);
      onBooked(label);
      setIntervalId(null);
      setDate("");
      setSlots(null);
    } catch {
      setMessage("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={s.card} aria-label="Follow-up">
      <h3 className={s.cardTitle}>
        Follow-up
        {existing === "loading" ? null : booked ? <StatusPill tone="ok" label={`Set · ${booked}`} /> : <StatusPill tone="neutral" icon="calendar" label="Not set" />}
      </h3>
      {existing === "loading" && <Skeleton rows={1} label="Checking for a booked follow-up" />}
      {existing === "error" && <p className={s.muted}>Couldn&apos;t check for an existing follow-up; one may already be booked.</p>}
      {canBook && (<>
      {booked && <p className={s.muted}>Book another only if a second visit is needed; the existing one is not moved.</p>}
      <div className={s.row} role="group" aria-label="Follow-up interval">
        {INTERVALS.map((i) => (
          <button key={i.id} type="button" className={`${s.chip} ${interval === i.id ? s.chipOn : ""}`} aria-pressed={interval === i.id} onClick={() => pick(i.id)}>
            {i.label}
          </button>
        ))}
      </div>
      {interval === "custom" && (
        <div className={s.row} style={{ marginBlockStart: "var(--nb-space-8)" }}>
          <label className={s.muted} htmlFor="fuDate">Date</label>
          <input id="fuDate" type="date" className={s.input} value={date} min={clinicToday(tz)} onChange={(e) => setDate(e.target.value)} />
        </div>
      )}
      {date && (
        <div style={{ marginBlockStart: "var(--nb-space-12)" }}>
          <p className={s.muted}>Free slots on {date}{tz ? ` (${tz.split("/")[1].replace("_", " ")} time)` : ""}</p>
          {slots === null ? <Skeleton rows={1} label="Loading slots" /> : slots.length === 0 ? (
            <p className={s.muted}>{slotsError ? "Couldn't load free slots. Pick the date again to retry." : "No free slots that day. Try another interval."}</p>
          ) : (
            <div className={s.slots} role="group" aria-label="Free slots">
              {slots.map((sl) => (
                <button key={sl} type="button" className={`${s.chip} ${slot === sl ? s.chipOn : ""}`} aria-pressed={slot === sl} onClick={() => setSlot(sl)}>
                  {fmtSlot(sl)}
                </button>
              ))}
            </div>
          )}
          <div className={s.row} style={{ marginBlockStart: "var(--nb-space-12)" }}>
            <button type="button" className={s.btnPrimary} onClick={book} disabled={busy || !slot}>{busy ? "Booking…" : "Book follow-up"}</button>
          </div>
        </div>
      )}
      {message && <div className={`${s.notice} ${s.noticeDanger}`} role="alert" style={{ marginBlockStart: "var(--nb-space-8)" }}>{message}</div>}
      </>)}
      <p className={s.muted} style={{ marginBlockStart: "var(--nb-space-8)" }}>
        Reminder: not available yet. No WhatsApp reminder or booking link is sent; the follow-up appears on the schedule and in Follow-ups.
      </p>
    </section>
  );
}
